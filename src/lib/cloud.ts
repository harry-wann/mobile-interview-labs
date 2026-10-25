// 靜態版（Artifact）的雲端存檔：db capability，存在使用者自己的私有路徑 data/users/<uid>/ 底下。
//   進度：data/users/<uid>/progress／sections/<sectionId>（每個 section 一份文件）
//   計畫：data/users/<uid>/plan
//   模擬面試：data/users/<uid>/mock／sessions/<sessionId>
//   口徑：data/users/<uid>/answers3／items/<exerciseId>（每題一份文件；事實以穩定 id 對齊）
//   舊版口徑：data/users/<uid>/answers／items/<exerciseId>（事實以 index 對齊）。開機時把「上次合併之後有變動」的文件合併進來
//     （用目前內容把 index 對到事實 id），寫到新路徑後在 data/users/<uid>/meta 記下各文件合併過的指紋；新版不再寫舊路徑，
//     所以還開著的舊版頁面不會因為看不懂新格式而把事實清掉。
//
// 規則：
//  - 開機讀取後與 localStorage 做欄位級合併（時間戳記），輸掉的內容進 history。
//  - 寫入 debounce ≥ 2 秒、只寫有變動的文件、同一時間只有一個寫入；每份文件 < 256 KiB。
//  - 寫入前一律「先讀 → 欄位級合併 → set」，set 之後再讀回確認（見 writeDoc）：讀回來的不含這次寫入時先當成「還沒讀到」，
//    稍後再確認；連續多次都不含才確定被別台覆蓋、重新合併再寫。不會用舊內容蓋掉別台的編輯。
//  - 前景每 20 秒讀一次、focus／切回前景立即讀；開始編輯某題、按 AI 評分前先讀那一題。
//  - 切到背景／離開頁面時立即寫入（不等 debounce），排在所有元件把暫存內容存進 store 之後（lifecycle 的 sync 階段）；
//    這時走快速路徑：不先讀、不讀回確認，所有有變動的文件同時 set（見 flushOnHide）。
//  - uid 或 db 為 null、或 revoked／not_granted 時退回 localStorage。
import { getIndex } from '../content.ts'
import { IS_STATIC } from '../env.ts'
import type { AnswerEntry, AnswersData, MockSession, ProgressData } from '../types.ts'
import { claudeUse, errCode, type Db, type DocRef, type UserNs } from './claude.ts'
import { mergeProgress, newDirty, normalizeAnswers, normalizeProgress, type DirtyKeys } from './data.ts'
import { onPageHide } from './lifecycle.ts'
import { exerciseLabel, getState, mergeRemote, registerFreshness, relinkFacts, setSync, startFreshness, subscribeChanges } from './store.ts'

const WRITE_DEBOUNCE_MS = 2000
const PULL_INTERVAL_MS = 20_000
const MAX_DOC_BYTES = 250 * 1024
const OTHER_SECTION = '_other'
/** v3 的口徑（事實以 id 對齊） */
const ANSWERS_DOC = 'answers3'
/** 舊版的口徑（事實以 index 對齊）：只讀不寫 */
const LEGACY_ANSWERS_DOC = 'answers'
const META_DOC = 'meta'
/** set 之後讀回來的內容不含這次寫入時，隔多久再確認一次（db 不保證 set 之後立即 get 一定讀到新值） */
const VERIFY_DELAY_MS = 3000
/** 連續幾次讀回來都不含這次寫入（含寫入後立即的那一次），才確定被別台覆蓋、重新合併再寫 */
const OVERWRITE_CONFIRMATIONS = 3
const log = (...a: unknown[]) => console.warn('[cloud]', ...a)

const bytes = (o: unknown) => new TextEncoder().encode(JSON.stringify(o)).length
const rand = (min: number, max: number) => min + Math.random() * (max - min)
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
/** 鍵順序固定的 JSON（雲端回傳的鍵順序不一定和本地一樣） */
const stable = (o: unknown): string => JSON.stringify(o, (_k, v) => (v && typeof v === 'object' && !Array.isArray(v) ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => (a < b ? -1 : 1))) : v))

/**
 * 比較用的內容：每次都會變的 updatedAt 不算；歷史版本的 replacedAt 不算（重新合併同一個輸掉的版本只會更新被取代的時間，
 * 不代表內容不同，不需要為此重寫或判定成「沒寫進去」）
 */
const canonical = (o: unknown): unknown => {
  if (!o || typeof o !== 'object') return o
  const { updatedAt: _u, ...rest } = o as Record<string, unknown>
  void _u
  if (Array.isArray(rest.history)) {
    rest.history = (rest.history as Array<Record<string, unknown>>)
      .map((h) => ({ field: h.field, text: h.text, at: h.at, device: h.device }))
      .sort((a, b) => (stable(a) < stable(b) ? -1 : 1))
  }
  return rest
}
const same = (a: unknown, b: unknown) => stable(canonical(a)) === stable(canonical(b))

/** 文件內容的指紋（FNV-1a，鍵順序固定） */
const fingerprint = (o: unknown): string => {
  const t = stable(o)
  let h = 2166136261
  for (let i = 0; i < t.length; i++) {
    h ^= t.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return `${t.length.toString(36)}-${(h >>> 0).toString(36)}`
}

type DocKey = string // p:<sectionId> ｜ plan ｜ m:<sessionId> ｜ a:<exerciseId>

interface Parsed {
  progress: ProgressData
  answers: AnswersData
}

class CloudSync {
  private dirty = new Set<DocKey>()
  private timer: ReturnType<typeof setTimeout> | undefined
  private flushing = false
  private flushAgain = false
  private stopped = false
  private backoffMs = 0
  private loaded = false
  private lastPull = 0
  private pulling: Promise<void> | null = null
  /** 舊路徑各文件合併過的指紋：新路徑寫完（下一次完整成功的 flush）後記進 meta 文件 */
  private pendingLegacy: Record<string, string> | null = null
  /** set 之後讀回來還不含那次寫入的文件 → 寫入的內容與連續幾次沒讀到 */
  private unverified = new Map<DocKey, { body: Record<string, unknown>; misses: number }>()
  /** 離開頁面時直接發出、還沒完成的 set（同一份文件同一時間只有一個寫入） */
  private fastSets = new Map<DocKey, Promise<unknown>>()
  /** 一般 flush 正在寫的文件 */
  private writing: DocKey | null = null

  constructor(
    private db: Db,
    private uid: string,
  ) {}

  private base = () => `data/users/${this.uid}`

  private ref(key: DocKey): DocRef {
    const base = this.base()
    if (key === 'plan') return this.db.doc(`${base}/plan`)
    const id = key.slice(2)
    if (key.startsWith('p:')) return this.db.doc(`${base}/progress`).collection('sections').doc(id)
    if (key.startsWith('m:')) return this.db.doc(`${base}/mock`).collection('sessions').doc(id)
    return this.db.doc(`${base}/${ANSWERS_DOC}`).collection('items').doc(id)
  }

  // ───────── 讀取 ─────────

  /** unavailable：隨機延遲後重試一次 */
  private async call<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn()
    } catch (e) {
      if (errCode(e) !== 'unavailable') throw e
      await sleep(rand(500, 1500))
      return await fn()
    }
  }

  private parseDoc(key: DocKey, data: Record<string, unknown> | undefined): Parsed {
    const empty = { progress: normalizeProgress(null), answers: normalizeAnswers(null) }
    if (!data) return empty
    if (key.startsWith('p:')) return { ...empty, progress: normalizeProgress({ units: data.units, exercises: data.exercises }) }
    if (key === 'plan') return { ...empty, progress: normalizeProgress({ plan: data.tasks }) }
    if (key.startsWith('m:')) return { ...empty, progress: normalizeProgress({ mock: [data] }) }
    return { ...empty, answers: normalizeAnswers({ answers: { [key.slice(2)]: data } }) }
  }

  /** includeSessions=false：定期 pull 不重讀模擬面試（很少變，省呼叫次數） */
  private async load(includeSessions: boolean): Promise<Parsed> {
    const base = this.base()
    const [sections, plan, sessions, items] = await Promise.all([
      this.call(() => this.db.doc(`${base}/progress`).collection('sections').get()),
      this.call(() => this.db.doc(`${base}/plan`).get()),
      includeSessions ? this.call(() => this.db.doc(`${base}/mock`).collection('sessions').get()) : Promise.resolve(null),
      this.call(() => this.db.doc(`${base}/${ANSWERS_DOC}`).collection('items').get()),
    ])
    let progress = normalizeProgress(null)
    for (const d of sections.docs) if (d.exists) progress = mergeProgress(progress, this.parseDoc(`p:${d.id}`, d.data()).progress)
    if (plan.exists) progress = mergeProgress(progress, this.parseDoc('plan', plan.data()).progress)
    if (sessions) {
      const mock: MockSession[] = []
      for (const d of sessions.docs) if (d.exists) mock.push(d.data() as unknown as MockSession)
      if (mock.length) progress = mergeProgress(progress, normalizeProgress({ mock }))
    }
    const answers: Record<string, unknown> = {}
    for (const d of items.docs) if (d.exists) answers[d.id] = d.data()
    return { progress, answers: normalizeAnswers({ answers }) }
  }

  private pullAll(includeSessions: boolean): Promise<void> {
    if (!this.pulling) {
      this.pulling = (async () => {
        const remote = await this.load(includeSessions)
        this.mark(mergeRemote(remote.progress, remote.answers)) // 本地比雲端新的 → 補寫上去
        this.lastPull = Date.now()
        relinkFacts() // 剛讀到最新的：內容改字的事實答案自動搬到新的事實（會標成待寫）
      })().finally(() => (this.pulling = null))
    }
    return this.pulling
  }

  /** 開始編輯某題、按 AI 評分前：只讀那一題（一次 get） */
  private async pullEntry(id: string) {
    const snap = await this.call(() => this.ref(`a:${id}`).get())
    const remote = this.parseDoc(`a:${id}`, snap.exists ? snap.data() : undefined)
    this.mark(mergeRemote(remote.progress, remote.answers))
  }

  async start() {
    subscribeChanges((c) => this.mark(c)) // 讀取完成前的變動先排隊
    registerFreshness(async (id) => {
      if (this.stopped || !this.loaded) return
      try {
        if (id) await this.pullEntry(id)
        else if (Date.now() - this.lastPull >= PULL_INTERVAL_MS - 1000 || !this.flushing) await this.pullAll(false)
      } catch (e) {
        this.onError(e, 'load')
      }
    })
    startFreshness()
    // 切到背景／離開頁面時立即寫入，不等 debounce（手機切 App 時頁面還活著）。
    // sync 階段：排在所有元件（輸入框）把還在 debounce 的內容存進 store 之後，最後一次編輯也會一起寫上去
    onPageHide(() => this.flushOnHide(), 'sync')
    await this.loadFirst()
  }

  /**
   * 舊路徑（事實以 index 對齊）的口徑：合併「內容和上次記下的指紋不同」的文件（輸掉的進 history），
   * 合併後有變動的題目寫到新路徑；全部寫完後才把指紋記進 meta（中途關閉的話，下次開機再合併一次，結果相同）。
   * 用每份文件的指紋而不是時間：還開著的舊版頁面時鐘可能比較慢，用時間判斷會漏掉它的編輯。
   */
  private async absorbLegacyAnswers() {
    const base = this.base()
    const [meta, legacy] = await Promise.all([
      this.call(() => this.db.doc(`${base}/${META_DOC}`).get()),
      this.call(() => this.db.doc(`${base}/${LEGACY_ANSWERS_DOC}`).collection('items').get()),
    ])
    const seenRaw = meta.exists ? meta.data()?.legacyAnswers : undefined
    const seen: Record<string, string> = seenRaw && typeof seenRaw === 'object' && !Array.isArray(seenRaw) ? (seenRaw as Record<string, string>) : {}
    const raw: Record<string, unknown> = {}
    const prints: Record<string, string> = {}
    for (const d of legacy.docs) {
      if (!d.exists) continue
      const data = d.data() ?? {}
      prints[d.id] = fingerprint(data)
      if (seen[d.id] !== prints[d.id]) raw[d.id] = data
    }
    if (!Object.keys(raw).length) return
    const legacyA = normalizeAnswers({ answers: raw }) // 用目前內容把 index 對到事實 id
    const before = getState().answers.answers
    mergeRemote(normalizeProgress(null), legacyA)
    const after = getState().answers.answers
    const changed = newDirty()
    for (const id of Object.keys(legacyA.answers)) if (after[id] !== before[id]) changed.answers.add(id)
    this.pendingLegacy = prints
    this.mark(changed)
    if (!changed.answers.size) await this.writeMeta()
  }

  private async writeMeta() {
    const v = this.pendingLegacy
    if (!v) return
    await this.call(() => this.db.doc(`${this.base()}/${META_DOC}`).set({ legacyAnswers: v, updatedAt: Date.now() }))
    if (this.pendingLegacy === v) this.pendingLegacy = null
  }

  private async loadFirst() {
    if (this.stopped) return
    try {
      await this.pullAll(true)
    } catch (e) {
      if (this.onError(e, 'load')) {
        setSync({ mode: 'cloud', status: 'offline', gate: 'offline-edit' })
        setTimeout(() => void this.loadFirst(), 20_000) // 暫時性失敗：稍後再讀
      }
      return
    }
    this.loaded = true
    setSync({ mode: 'cloud', status: 'ok', gate: 'ready', lastAt: Date.now(), notice: null })
    try {
      await this.absorbLegacyAnswers()
    } catch (e) {
      this.onError(e, 'legacy') // 讀不到舊路徑不影響使用；下次開機再合併
    }
    this.schedule()
  }

  // ───────── 寫入 ─────────

  /** 把變動的 entry 對應到它所在的文件 */
  mark(c: DirtyKeys) {
    if (this.stopped) return
    const idx = getIndex()
    for (const id of c.exercises) this.dirty.add(`p:${idx.exerciseById.get(id)?.sectionId ?? OTHER_SECTION}`)
    for (const id of c.units) this.dirty.add(`p:${idx.unitById.get(id)?.sectionId ?? OTHER_SECTION}`)
    if (c.plan.size) this.dirty.add('plan')
    for (const id of c.mock) this.dirty.add(`m:${id}`)
    for (const id of c.answers) this.dirty.add(`a:${id}`)
    if (this.dirty.size) this.schedule()
  }

  private schedule(delay = WRITE_DEBOUNCE_MS) {
    clearTimeout(this.timer)
    if (this.stopped || !this.loaded || !this.dirty.size) return
    this.timer = setTimeout(() => void this.flush(), delay)
  }

  /**
   * 切到背景／離開頁面（lifecycle 的 sync 階段）：頁面可能馬上被凍結或關閉，沒有時間「讀 → 寫 → 讀回確認」兩個來回。
   * 直接把每份有變動的文件目前（已合併）的內容 set 出去，多份文件同時發出、不逐一等待；不先讀也不確認。
   * 與別台的競態交給下次開機／pull 的欄位級合併：別台沒被合併進來的內容還在它自己那裡，它下次 pull 發現雲端較舊就會再寫。
   * 文件仍留在待寫清單：頁面回到前景時，一般的 flush 會讀回確認。正在被一般 flush 寫的那份不重複發出（同一份文件一次一個寫入）。
   */
  private flushOnHide() {
    clearTimeout(this.timer)
    if (!this.loaded || this.stopped) return
    for (const key of this.dirty) {
      if (key === this.writing || this.fastSets.has(key)) continue
      const body = this.build(key)
      if (!body || bytes(body) > MAX_DOC_BYTES) continue
      const done = this.ref(key)
        .set(body)
        .catch((e) => void this.onError(e, key))
        .finally(() => this.fastSets.delete(key))
      this.fastSets.set(key, done)
    }
    this.schedule()
  }

  /** 這份文件目前（合併後）應有的內容 */
  private build(key: DocKey): Record<string, unknown> | null {
    const { progress, answers } = getState()
    const idx = getIndex()
    const now = Date.now()
    if (key.startsWith('p:')) {
      const sec = key.slice(2)
      const secOf = (kind: 'e' | 'u', id: string) => (kind === 'e' ? idx.exerciseById.get(id)?.sectionId : idx.unitById.get(id)?.sectionId) ?? OTHER_SECTION
      const body = { updatedAt: now, units: {} as Record<string, unknown>, exercises: {} as Record<string, unknown> }
      for (const [id, v] of Object.entries(progress.units)) if (secOf('u', id) === sec) body.units[id] = v
      for (const [id, v] of Object.entries(progress.exercises)) if (secOf('e', id) === sec) body.exercises[id] = v
      return body
    }
    if (key === 'plan') return { updatedAt: now, tasks: { ...progress.plan } }
    if (key.startsWith('m:')) {
      const s = progress.mock.find((m) => m.id === key.slice(2))
      return s ? (s as unknown as Record<string, unknown>) : null
    }
    const a = answers.answers[key.slice(2)]
    return a ? (this.fitEntry(a) as unknown as Record<string, unknown>) : null
  }

  /** 文件上限 256 KiB：太大時從最舊的 history 開始丟（現有內容一律保留） */
  private fitEntry(e: AnswerEntry): AnswerEntry {
    let cur = e
    while (bytes(cur) > MAX_DOC_BYTES && cur.history.length) cur = { ...cur, history: cur.history.slice(0, -1) }
    return cur
  }

  /**
   * 寫一份文件：讀 → 欄位級合併進本地 → set → 讀回確認。
   * db 沒有交易（last-writer-wins），而且不保證 set 之後立即 get 就讀到新值。讀回來的內容不含這次寫入時，
   * 先當成「還沒讀到」：回傳 'unverified'（不是錯誤），稍後（VERIFY_DELAY_MS）只讀不寫再確認一次；
   * 連續 OVERWRITE_CONFIRMATIONS 次都不含，才確定被別台覆蓋，用讀到的內容重新合併再寫。
   * 合併一律是欄位級、輸掉的進 history，所以重寫不會丟掉任何一邊的內容。
   *
   * 仍有的時間窗：別台在我們確認之後才 set，而它的內容是在我們 set 之前讀的 → 雲端上這次的內容會被它蓋掉。
   * 這台的 state（localStorage 可寫時也在磁碟上）仍保有它：下一次 pull（前景每 20 秒、切回前景、開始編輯）發現雲端比本地舊，
   * 就會再寫一次。如果這台在那之前關閉、而且 localStorage 不能寫（沙箱／無痕），這次的內容就只存在被蓋掉之前的雲端版本裡——會遺失。
   */
  private async writeDoc(key: DocKey): Promise<'ok' | 'skipped' | 'toolarge' | 'unverified'> {
    await this.fastSets.get(key)?.catch(() => {}) // 離開頁面時發出的 set 還沒完成：等它（一次一個寫入）
    const ref = this.ref(key)
    const snap = await this.call(() => ref.get())
    const pending = this.unverified.get(key)
    if (pending) {
      if (snap.exists && (same(snap.data(), pending.body) || this.holds(key, snap))) {
        this.unverified.delete(key) // 上次的寫入已經在雲端（或別台已經合併了它）
      } else if (pending.misses + 1 < OVERWRITE_CONFIRMATIONS) {
        pending.misses++
        return 'unverified' // 可能只是還讀不到：先不重寫，稍後再確認
      } else {
        this.unverified.delete(key)
        log('連續多次讀回的內容都不含這次寫入：確定被別台覆蓋，重新合併再寫', key)
      }
    }
    if (snap.exists) {
      const remote = this.parseDoc(key, snap.data())
      mergeRemote(remote.progress, remote.answers) // 這份文件本來就要寫（本地比遠端新的部分就是這次要寫的內容），不用再標成待寫
    }
    const body = this.build(key)
    if (!body) return 'skipped'
    if (bytes(body) > MAX_DOC_BYTES) return 'toolarge'
    if (snap.exists && same(snap.data(), body)) return 'skipped' // 雲端已經是合併後的內容
    await this.call(() => ref.set(body))
    const check = await this.call(() => ref.get())
    if (check.exists && (same(check.data(), body) || this.holds(key, check))) return 'ok'
    this.unverified.set(key, { body, misses: 1 })
    return 'unverified'
  }

  /** 讀到的文件是否已經包含本地的內容：先欄位級合併進本地（別台的新內容不會丟），再和這份文件應有的內容比較 */
  private holds(key: DocKey, snap: { exists: boolean; data(): Record<string, unknown> | undefined }): boolean {
    if (!snap.exists) return false
    const remote = this.parseDoc(key, snap.data())
    mergeRemote(remote.progress, remote.answers)
    const body = this.build(key)
    return !body || same(snap.data(), body)
  }

  private async flush() {
    if (this.stopped || !this.loaded) return
    if (this.flushing) {
      this.flushAgain = true
      return
    }
    this.flushing = true
    setSync({ status: 'syncing' })
    let retryLater = false
    let hardError = false
    let verifyLater = false
    try {
      for (const key of [...this.dirty]) {
        if (this.stopped) break
        this.dirty.delete(key) // 寫入期間又有變動會重新加回來，下一輪再寫
        try {
          this.writing = key
          const r = await this.writeDoc(key) // 一次只有一個寫入在進行
          if (r === 'unverified') {
            this.dirty.add(key) // 已經寫了、只是還沒讀回確認：不是錯誤，稍後再確認
            verifyLater = true
            continue
          }
          if (r === 'toolarge') {
            hardError = true
            log('文件超過 256 KiB', key)
            const what = key.startsWith('a:') ? exerciseLabel(key.slice(2)) : key
            setSync({ notice: `雲端存檔失敗：${what} 的內容超過單一文件上限（256 KiB），這份資料改存在這個瀏覽器。請縮短內容，或用「我的口徑」匯出備份。` })
          }
        } catch (e) {
          const retry = this.onError(e, key)
          if (this.stopped) break
          if (retry) {
            this.dirty.add(key)
            retryLater = true
            break
          }
          hardError = true
        }
      }
    } finally {
      this.flushing = false
      this.writing = null
    }
    if (this.stopped) return
    if (retryLater) {
      setSync({ status: 'offline' })
      this.schedule(Math.max(15_000, this.backoffMs))
    } else if (hardError) {
      setSync({ status: 'error' }) // 雲端異常：不能標成已同步
    } else if (verifyLater) {
      setSync({ status: 'syncing' }) // 寫入後還沒讀回確認：維持「同步中」，不是異常
    } else {
      this.backoffMs = 0
      setSync({ status: 'ok', lastAt: Date.now(), notice: null })
      if (this.pendingLegacy && !this.dirty.size) {
        try {
          await this.writeMeta()
        } catch (e) {
          this.onError(e, 'meta') // 沒記成功：下次開機再合併一次舊路徑（結果相同）
        }
      }
    }
    if (this.flushAgain || (this.dirty.size && !retryLater)) {
      this.flushAgain = false
      this.schedule(verifyLater ? VERIFY_DELAY_MS : WRITE_DEBOUNCE_MS)
    }
  }

  /** 回傳 true 表示這份文件應保留在佇列、稍後重試 */
  private onError(e: unknown, where: string): boolean {
    const code = errCode(e)
    switch (code) {
      case 'revoked':
      case 'not_granted':
      case 'capability_disabled':
      case 'capability_removed':
        // 靜默退回 localStorage
        this.stopped = true
        this.dirty.clear()
        setSync({ mode: 'none', status: 'ok', gate: 'ready', notice: null })
        return false
      case 'quota_exceeded':
        log('quota_exceeded', where, e)
        // 每一次失敗都提示（使用者關掉提示後，下一次失敗會再出現）
        setSync({ status: 'error', notice: '雲端存檔空間已滿，新資料無法存到雲端（已存在這個瀏覽器）。請到「我的口徑」匯出備份；刪除不需要的雲端資料後才能繼續同步。' })
        return false
      case 'invalid_argument':
      case 'transform_error':
        log('invalid_argument', where, e)
        setSync({ status: 'error', notice: `雲端存檔失敗：資料格式不符（${code}，${where}）。這份資料改存在這個瀏覽器。` })
        return false
      case 'resource_exhausted':
        this.backoffMs = Math.min(60_000, Math.max(5_000, this.backoffMs * 2))
        log('resource_exhausted, backoff', this.backoffMs, 'ms')
        return true
      default:
        // unavailable（已重試過一次）與未知的 code
        log(code, where, e)
        return true
    }
  }
}

/** 啟動雲端存檔（只在靜態版）。不阻塞第一次渲染；任何一步拿不到就退回「僅存本機」 */
export async function initCloud(): Promise<void> {
  if (!IS_STATIC) return
  const fallback = () => {
    setSync({ mode: 'none', status: 'ok', gate: 'ready', notice: null })
    relinkFacts() // 只存本機：沒有遠端要先同步，直接搬內容改字的事實答案
  }
  try {
    const [db, user] = await Promise.all([claudeUse<Db>('db'), claudeUse<UserNs>('user')])
    if (!db || !user) return fallback()
    let uid: string | null = null
    try {
      uid = await user.id()
    } catch {
      uid = null
    }
    if (!uid) return fallback()
    await new CloudSync(db, uid).start()
  } catch (e) {
    log('init failed', e)
    fallback()
  }
}
