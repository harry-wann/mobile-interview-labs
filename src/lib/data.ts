// 使用者資料（進度、我的口徑）：正規化、欄位級合併、歷史、變動追蹤。純函式，前端與本機 server 共用。
//
// 設計原則：使用者寫的口徑永遠不會被靜默覆蓋或遺失。
//  - answers 的 text、每個待確認事實（以穩定的事實 id 對齊）、grade 各自帶時間戳記，合併時逐欄位取新者；本地缺的欄位保留遠端。
//  - 合併「輸掉」的 text／fact 一律進 entry.history（規則見 types.ts HistoryItem），UI 可以檢視與還原。
//  - progress 的作答次數用「各裝置各自計數再加總」，SRS 狀態以最新一次作答為準。
//  - 時間戳記不可信：讀到超過 now + 1 天的視為 now；寫入時最多 now + 60 秒。
import type {
  AnswerEntry,
  AnswersData,
  DeviceCount,
  ExerciseProgress,
  HistoryItem,
  MockItem,
  MockSession,
  ProgressData,
  Result,
  StoredGrade,
} from '../types.ts'

/** 3：事實改成以穩定 id 對齊（facts 由陣列改成 id → 內容）、作答次數可併進 others。舊版頁面（2 以下）送資料會被 426 拒絕 */
export const DATA_VERSION = 3
export const MAX_TEXT = 100_000
export const MAX_FACT = 5_000
/** 每題最多幾個事實（含內容已移除、但口徑還留著的） */
export const MAX_FACTS = 60
export const MAX_FACT_LABEL = 300
export const HISTORY_MAX = 10
export const FUTURE_READ_MS = 24 * 3600 * 1000
export const FUTURE_WRITE_MS = 60 * 1000
export const MAX_PATCHES = 500

export const emptyProgress = (): ProgressData => ({ version: DATA_VERSION, updatedAt: 0, units: {}, exercises: {}, plan: {}, mock: [] })
export const emptyAnswers = (): AnswersData => ({ version: DATA_VERSION, updatedAt: 0, answers: {} })
export const emptyEntry = (): AnswerEntry => ({ text: '', textAt: 0, facts: {}, factsAt: {}, gradeAt: 0, history: [], updatedAt: 0 })

// ───────── 時間戳記 ─────────

/** 讀進來的時間戳記：超過 now + 1 天視為 now（時鐘錯亂或被灌入未來時間，不能讓它永遠贏） */
export const sanitizeStamp = (at: number, now: number) => (at > now + FUTURE_READ_MS ? now : at)
/** 寫出去的時間戳記：最多 now + 60 秒 */
export const clampWrite = (at: number, now: number = Date.now()) => Math.min(at, now + FUTURE_WRITE_MS)
/** 下一次編輯的戳記：不早於 now、比上一次大，但不超過 now + 60 秒 */
export const nextStamp = (prevAt: number, now: number = Date.now()) => clampWrite(Math.max(now, Math.min(prevAt, now + FUTURE_WRITE_MS) + 1), now)

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const has = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k)
const num = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const int = (v: unknown, d = 0) => Math.max(0, Math.round(num(v, d)))
const str = (v: unknown, max = 50_000) => (typeof v === 'string' ? v.slice(0, max) : '')
const strList = (v: unknown, maxItems = 30, maxLen = 5_000) => (Array.isArray(v) ? v.slice(0, maxItems).map((x) => str(x, maxLen)) : [])
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const RESULTS: Result[] = ['correct', 'partial', 'wrong']
const keyOk = (k: string) => k.length > 0 && k.length <= 200 && k !== '__proto__'
const cmp = (x: string, y: string) => (x < y ? -1 : x > y ? 1 : 0)
/** 鍵順序固定的 JSON：物件不論用什麼順序建出來，內容相同就是同一個字串 */
export const stableJSON = (o: unknown): string =>
  JSON.stringify(o, (_k, v) => (isObj(v) ? Object.fromEntries(Object.entries(v).sort(([a], [b]) => cmp(a, b))) : v))
const sameJSON = (a: unknown, b: unknown) => stableJSON(a) === stableJSON(b)
const sortRec = <T>(o: Record<string, T>): Record<string, T> => Object.fromEntries(Object.entries(o).sort(([a], [b]) => cmp(a, b)))

// ───────── 待確認事實的穩定 id ─────────

/** 事實 id：'k' + 事實原文（去頭尾空白、空白壓縮）的 FNV-1a 32-bit hash（base36）；同一題原文重複時加 -2、-3 */
export function factIdsOf(sources: string[]): string[] {
  const seen = new Map<string, number>()
  return sources.map((s) => {
    const text = s.replace(/\s+/g, ' ').trim()
    let h = 2166136261
    for (let i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i)
      h = Math.imul(h, 16777619)
    }
    const base = `k${(h >>> 0).toString(36)}`
    const n = (seen.get(base) ?? 0) + 1
    seen.set(base, n)
    return n === 1 ? base : `${base}-${n}`
  })
}

/** 事實 key：穩定 id（k…），或還對應不到 id 的舊格式位置（@i：遷移當下找不到這題的內容，之後找得到時自動對應） */
export const FACT_KEY_RE = /^(k[a-z0-9]{1,8}(-\d{1,2})?|@\d{1,2})$/
/** 欄位 id：'text' | 'grade' | 'history' | `f:${factKey}` */
export type AnswerField = string
export const factField = (key: string): AnswerField => `f:${key}`
export const isFactField = (f: string) => f.startsWith('f:') && FACT_KEY_RE.test(f.slice(2))
export const factKeyOf = (f: AnswerField) => f.slice(2)

export interface FactCatalogEntry {
  /** 內容中這題的事實 id（依內容順序） */
  ids: string[]
  /** 對應的事實文字（純文字） */
  labels: string[]
}
let factCatalog: (exerciseId: string) => FactCatalogEntry | undefined = () => undefined
/**
 * 舊格式的事實以 index 對齊：遷移時用「目前內容」的第 i 個事實 id。前端註冊內容、server 註冊 server-data.json。
 * 查不到（內容還沒建置、這題已不存在）時先用位置 key（@i），之後查得到時自動對應。
 */
export function setFactCatalog(fn: (exerciseId: string) => FactCatalogEntry | undefined) {
  factCatalog = fn
}
function catalogOf(exerciseId: string | undefined): FactCatalogEntry | undefined {
  if (!exerciseId) return undefined
  try {
    return factCatalog(exerciseId)
  } catch {
    return undefined
  }
}
/** 舊格式第 i 個事實對應的 key */
export function legacyFactKey(exerciseId: string | undefined, index: number): string {
  const cat = catalogOf(exerciseId)
  return cat && index < cat.ids.length ? cat.ids[index] : `@${index}`
}
/** 位置 key（@i）在查得到內容時換成穩定 id；其他 key 原樣 */
function resolveFactKey(exerciseId: string | undefined, key: string): string {
  const m = /^@(\d{1,2})$/.exec(key)
  return m ? legacyFactKey(exerciseId, Number(m[1])) : key
}

// ───────── 歷史 ─────────

/** 欄位 id 的遷移：舊格式 f0、f1…（第 i 個事實）→ f:<id>；f:@i 查得到內容時 → f:<id>。不認得的欄位回傳 null */
export function resolveField(exerciseId: string | undefined, f: string): AnswerField | null {
  if (f === 'text' || f === 'grade' || f === 'history') return f
  const legacy = /^f(\d{1,2})$/.exec(f)
  if (legacy) return factField(legacyFactKey(exerciseId, Number(legacy[1])))
  if (f.startsWith('f:')) {
    const k = resolveFactKey(exerciseId, f.slice(2))
    return FACT_KEY_RE.test(k) ? factField(k) : null
  }
  return null
}

function normalizeHistoryField(field: string, exerciseId: string | undefined): string | null {
  const f = resolveField(exerciseId, field)
  return f === 'text' || (f !== null && isFactField(f)) ? f : null
}

function normalizeHistory(v: unknown, now: number, exerciseId?: string): HistoryItem[] {
  if (!Array.isArray(v)) return []
  const items: HistoryItem[] = []
  for (const h of v.slice(0, 400)) {
    if (!isObj(h) || typeof h.text !== 'string' || typeof h.field !== 'string') continue
    const field = normalizeHistoryField(h.field, exerciseId)
    if (!field) continue
    items.push({
      field,
      text: str(h.text, MAX_TEXT),
      at: sanitizeStamp(num(h.at), now),
      replacedAt: sanitizeStamp(num(h.replacedAt), now),
      device: str(h.device, 80),
    })
  }
  return trimHistory(items)
}

/** 去重（同欄位同內容只留一筆）、被取代時間新的在前、每個欄位最多 HISTORY_MAX 筆 */
function trimHistory(items: HistoryItem[]): HistoryItem[] {
  // 全序（時間、欄位、裝置、內容），所以不論合併順序，同一份內容留下的是同一筆
  const sorted = [...items].sort((a, b) => b.replacedAt - a.replacedAt || b.at - a.at || cmp(a.field, b.field) || cmp(a.device, b.device) || cmp(a.text, b.text))
  const seen = new Set<string>()
  const perField = new Map<string, number>()
  const out: HistoryItem[] = []
  for (const h of sorted) {
    const key = `${h.field}\u0000${h.text}`
    if (seen.has(key) || !h.text.trim()) continue
    const n = perField.get(h.field) ?? 0
    if (n >= HISTORY_MAX) continue
    seen.add(key)
    perField.set(h.field, n + 1)
    out.push(h)
  }
  return out
}

export const addHistory = (history: HistoryItem[], ...items: HistoryItem[]) => trimHistory([...history, ...items])

// ───────── answers：欄位 ─────────

export function entryUpdatedAt(e: Pick<AnswerEntry, 'textAt' | 'factsAt' | 'gradeAt'>): number {
  return Math.max(e.textAt, e.gradeAt, ...Object.values(e.factsAt), 0)
}

/** 欄位的時間戳記（沒有這個欄位是 0） */
export function fieldAt(e: AnswerEntry | undefined, f: AnswerField): number {
  if (!e) return 0
  if (f === 'text') return e.textAt
  if (f === 'grade') return e.grade ? e.gradeAt : 0
  if (isFactField(f)) return e.factsAt[factKeyOf(f)] ?? 0
  return 0
}
/** 欄位的內容（比較用） */
export function fieldValue(e: AnswerEntry | undefined, f: AnswerField): unknown {
  if (!e) return undefined
  if (f === 'text') return e.text
  if (f === 'grade') return e.grade
  if (f === 'history') return e.history
  if (isFactField(f)) return has(e.facts, factKeyOf(f)) ? e.facts[factKeyOf(f)] : ''
  return undefined
}
/** 文字欄位（text 或事實）的內容 */
export const fieldText = (e: AnswerEntry | undefined, f: AnswerField): string => {
  const v = fieldValue(e, f)
  return typeof v === 'string' ? v : ''
}
/** 這個 entry 有內容的欄位（text、grade、各事實） */
export function entryFields(e: AnswerEntry): AnswerField[] {
  const out: AnswerField[] = ['text']
  if (e.grade) out.push('grade')
  for (const k of Object.keys(e.facts)) out.push(factField(k))
  return out
}

/** facts／factsAt／factQ 依 key 排序、factQ 只留有事實的 key、重算 updatedAt */
function finishEntry(e: AnswerEntry): AnswerEntry {
  e.facts = sortRec(e.facts)
  e.factsAt = sortRec(Object.fromEntries(Object.keys(e.facts).map((k) => [k, e.factsAt[k] ?? 0])))
  const q = Object.fromEntries(Object.entries(e.factQ ?? {}).filter(([k, v]) => has(e.facts, k) && v))
  if (Object.keys(q).length) e.factQ = sortRec(q)
  else delete e.factQ
  const pos = Object.fromEntries(Object.entries(e.factPos ?? {}).filter(([k, v]) => has(e.facts, k) && isPos(v)))
  if (Object.keys(pos).length) e.factPos = sortRec(pos)
  else delete e.factPos
  e.updatedAt = Math.max(entryUpdatedAt(e), e.updatedAt)
  return e
}

const cloneEntry = (e: AnswerEntry): AnswerEntry => ({
  ...e,
  facts: { ...e.facts },
  factsAt: { ...e.factsAt },
  ...(e.factQ ? { factQ: { ...e.factQ } } : {}),
  ...(e.factPos ? { factPos: { ...e.factPos } } : {}),
  history: [...e.history],
})

/** [位置, 事實數]：0 ≤ 位置 < 事實數 ≤ MAX_FACTS */
const isPos = (v: unknown): v is [number, number] =>
  Array.isArray(v) && v.length === 2 && Number.isInteger(v[0]) && Number.isInteger(v[1]) && v[0] >= 0 && v[0] < v[1] && v[1] <= MAX_FACTS

/** 兩邊的事實文字取聯集；同一個 id 兩邊不同時取固定的一邊（和合併順序無關） */
function mergeLabels(a: Record<string, string> | undefined, b: Record<string, string> | undefined): Record<string, string> {
  const out: Record<string, string> = { ...(a ?? {}) }
  for (const [k, v] of Object.entries(b ?? {})) if (!out[k] || cmp(v, out[k]) > 0) out[k] = v
  return out
}

// ───────── answers：entry ─────────

function normalizeGrade(v: unknown): StoredGrade | undefined {
  if (!isObj(v)) return undefined
  const score = Math.round(num(v.score))
  if (score < 1 || score > 5) return undefined
  return {
    score,
    summary: str(v.summary, 4000),
    covered: strList(v.covered),
    missing: strList(v.missing),
    inaccurate: strList(v.inaccurate),
    followUps: strList(v.followUps),
    improved: str(v.improved, 10_000),
    at: num(v.at),
    lang: v.lang === 'ja' ? 'ja' : 'zh',
    answer: str(v.answer, MAX_TEXT),
  }
}

/** 兩個時間戳記相同時用內容決定勝負，讓合併和順序無關（a、b 對調結果一樣） */
const newerOf = (aAt: number, bAt: number, aKey: string, bKey: string): 'a' | 'b' => (aAt !== bAt ? (aAt > bAt ? 'a' : 'b') : aKey >= bKey ? 'a' : 'b')

/**
 * 正規化一筆口徑。自動升級舊格式：
 *  - 只有整筆 updatedAt：每個欄位都沿用那個時間。
 *  - facts 是陣列（以 index 對齊內容）：第 i 個事實換成目前內容第 i 個事實的 id（查不到就先用 @i）。
 *  - history 的 f0、f1… 同樣換成 f:<id>。
 * exerciseId 給了才能做 index → id 的遷移。
 */
export function normalizeEntry(v: unknown, now: number = Date.now(), exerciseId?: string): AnswerEntry | null {
  if (!isObj(v)) return null
  const legacyAt = sanitizeStamp(num(v.updatedAt), now)
  const grade = normalizeGrade(v.grade)
  const e: AnswerEntry = {
    text: str(v.text, MAX_TEXT),
    textAt: sanitizeStamp(num(v.textAt, legacyAt), now),
    facts: {},
    factsAt: {},
    gradeAt: grade ? sanitizeStamp(num(v.gradeAt, legacyAt), now) : 0,
    history: normalizeHistory(v.history, now, exerciseId),
    updatedAt: 0,
  }
  if (typeof v.textBy === 'string' && v.textBy) e.textBy = str(v.textBy, 80)
  if (grade) e.grade = grade
  const lost: HistoryItem[] = []
  const labels: Record<string, string> = {}
  const positions: Record<string, [number, number]> = {}
  const put = (key: string, value: string, at: number) => {
    if (!FACT_KEY_RE.test(key)) return
    if (!value && !at) return // 舊格式補齊 index 用的空佔位
    if (has(e.facts, key)) {
      // 兩個來源對應到同一個 id（例如 @0 在內容找得到後換成 k…，而 k… 已經有內容）：取新的，另一個進 history
      const curV = e.facts[key]
      const curAt = e.factsAt[key]
      const keepCur = newerOf(curAt, at, curV, value) === 'a'
      const [wv, wat, lv, lat] = keepCur ? [curV, curAt, value, at] : [value, at, curV, curAt]
      if (lv !== wv && lv.trim()) lost.push({ field: factField(key), text: lv, at: lat, replacedAt: Math.max(now, lat), device: '' })
      e.facts[key] = wv
      e.factsAt[key] = wat
      return
    }
    if (Object.keys(e.facts).length >= MAX_FACTS) return
    e.facts[key] = value
    e.factsAt[key] = at
  }
  const cat = catalogOf(exerciseId)
  const rawLabels = isObj(v.factQ) ? v.factQ : {}
  const rawPos = isObj(v.factPos) ? v.factPos : {}
  if (Array.isArray(v.facts)) {
    const atRaw = Array.isArray(v.factsAt) ? v.factsAt : []
    v.facts.slice(0, MAX_FACTS).forEach((x, i) => {
      const key = legacyFactKey(exerciseId, i)
      put(key, str(x, MAX_FACT), sanitizeStamp(num(atRaw[i], legacyAt), now))
      if (cat && i < cat.labels.length) labels[key] ||= cat.labels[i].slice(0, MAX_FACT_LABEL)
      if (cat && i < cat.ids.length) positions[key] ??= [i, cat.ids.length]
    })
  } else if (isObj(v.facts)) {
    const atRaw = isObj(v.factsAt) ? v.factsAt : {}
    for (const [k, x] of Object.entries(v.facts).slice(0, MAX_FACTS * 2)) {
      if (typeof x !== 'string') continue
      const key = resolveFactKey(exerciseId, k)
      put(key, x.slice(0, MAX_FACT), sanitizeStamp(num(atRaw[k], legacyAt), now))
      if (typeof rawLabels[k] === 'string') labels[key] ||= (rawLabels[k] as string).slice(0, MAX_FACT_LABEL)
      if (isPos(rawPos[k])) positions[key] ??= [rawPos[k][0], rawPos[k][1]]
      const m = /^@(\d{1,2})$/.exec(k)
      if (m && cat && key !== k && Number(m[1]) < cat.labels.length) labels[key] ||= cat.labels[Number(m[1])].slice(0, MAX_FACT_LABEL)
      if (m && cat && key !== k) positions[key] ??= [Number(m[1]), cat.ids.length]
    }
  }
  if (Object.keys(labels).length) e.factQ = labels
  if (Object.keys(positions).length) e.factPos = positions
  if (lost.length) e.history = addHistory(e.history, ...lost)
  finishEntry(e)
  e.updatedAt = Math.max(entryUpdatedAt(e), legacyAt)
  return e
}

export function normalizeAnswers(raw: unknown, now: number = Date.now()): AnswersData {
  const out = emptyAnswers()
  if (!isObj(raw)) return out
  out.updatedAt = sanitizeStamp(num(raw.updatedAt), now)
  if (isObj(raw.answers)) {
    for (const [k, v] of Object.entries(raw.answers)) {
      const e = keyOk(k) ? normalizeEntry(v, now, k) : null
      if (e) out.answers[k] = e
    }
  }
  return out
}

const sameEntry = (a: AnswerEntry, b: AnswerEntry) => sameJSON(a, b)

/**
 * 欄位級合併：text、每個事實（依 id）、grade 各自取時間戳記較新者，缺的欄位保留另一邊。
 * 輸掉的 text／fact（有內容且不同）進 history，replacedAt＝now（被取代的時間；淘汰時依它排序，剛輸掉的離線版本不會先被丟掉）。
 * 結果和 a 相同就回傳 a（保留物件參照，方便做變動偵測）。now 相同時，合併滿足交換律與結合律。
 */
export function mergeEntry(a: AnswerEntry, b: AnswerEntry, now: number = Date.now()): AnswerEntry {
  if (a === b) return a
  const lost: HistoryItem[] = []
  // 同欄位同內容在 history 只留一筆、取最新的 replacedAt（trimHistory），所以合併和順序無關（now 相同時），重複合併只會更新被取代的時間
  const lose = (field: string, text: string, at: number, device: string) => {
    if (text.trim()) lost.push({ field, text, at, replacedAt: Math.max(now, at), device })
  }
  const wText = newerOf(a.textAt, b.textAt, a.text, b.text) === 'a' ? a : b
  const lText = wText === a ? b : a
  if (lText.text !== wText.text) lose('text', lText.text, lText.textAt, lText.textBy ?? '')
  const facts: Record<string, string> = {}
  const factsAt: Record<string, number> = {}
  const factPos: Record<string, [number, number]> = {}
  for (const k of [...new Set([...Object.keys(a.facts), ...Object.keys(b.facts)])].sort(cmp)) {
    const inA = has(a.facts, k)
    const inB = has(b.facts, k)
    let src: AnswerEntry
    let other: AnswerEntry | null = null
    if (inA && inB) {
      src = newerOf(a.factsAt[k] ?? 0, b.factsAt[k] ?? 0, a.facts[k], b.facts[k]) === 'a' ? a : b
      other = src === a ? b : a
      if (other.facts[k] !== src.facts[k]) lose(factField(k), other.facts[k], other.factsAt[k] ?? 0, '')
    } else src = inA ? a : b
    facts[k] = src.facts[k]
    factsAt[k] = src.factsAt[k] ?? 0
    const pos = src.factPos?.[k] ?? other?.factPos?.[k] // 位置取寫下目前內容的那一邊
    if (pos) factPos[k] = pos
  }
  let grade: StoredGrade | undefined
  let gradeAt = 0
  if (a.grade && b.grade) {
    const w = newerOf(a.gradeAt, b.gradeAt, JSON.stringify(a.grade), JSON.stringify(b.grade)) === 'a' ? a : b
    grade = w.grade
    gradeAt = w.gradeAt
  } else if (a.grade || b.grade) {
    const w = a.grade ? a : b
    grade = w.grade
    gradeAt = w.gradeAt
  }
  const merged: AnswerEntry = {
    text: wText.text,
    textAt: wText.textAt,
    facts,
    factsAt,
    factQ: mergeLabels(a.factQ, b.factQ),
    factPos,
    gradeAt,
    history: addHistory(a.history, ...b.history, ...lost),
    updatedAt: Math.max(a.updatedAt, b.updatedAt),
  }
  if (wText.textBy) merged.textBy = wText.textBy
  if (grade) merged.grade = grade
  finishEntry(merged)
  if (sameEntry(merged, a)) return a
  if (sameEntry(merged, b)) return b
  return merged
}

export function mergeAnswers(a: AnswersData, b: AnswersData, now: number = Date.now()): AnswersData {
  const answers = { ...a.answers }
  for (const [k, v] of Object.entries(b.answers)) {
    const cur = answers[k]
    answers[k] = cur ? mergeEntry(cur, v, now) : v
  }
  return { version: DATA_VERSION, updatedAt: Math.max(a.updatedAt, b.updatedAt), answers }
}

// ───────── answers：欄位級 patch（本機 server 用）─────────
// 本機版以 server 的時間為準：client 只送「我改了哪些欄位」與它最後一次看到的版本（base），
// server 套用時自己蓋上時間戳記；base 比 server 現存的舊（別台改過）就照樣套用這次編輯，但把 server 原本的內容放進 history。

export interface FieldPatch<T> {
  v: T
  /** 這個欄位最後一次從 server 看到的時間戳記（沒同步過是 0） */
  base: number
  /** 不論有沒有衝突，都先把 server 現有的內容存進 history（還原歷史版本、編輯時別台改過這個欄位） */
  archive?: boolean
}
export interface FactPatch extends FieldPatch<string> {
  /** 題目事實的文字（顯示「已移除的題目事實」用） */
  q?: string
  /** [寫入當時在內容中的位置, 事實數]（內容改字後自動搬移用） */
  pos?: [number, number]
}
export interface AnswerPatch {
  text?: FieldPatch<string>
  /** 事實 id → patch */
  facts?: Record<string, FactPatch>
  grade?: FieldPatch<StoredGrade>
  /** 這台裝置本地才有的歷史版本（還原、匯入、離線合併時產生）；server 取聯集 */
  history?: HistoryItem[]
  device?: string
}

function normalizeFieldPatch<T>(raw: unknown, value: (v: unknown) => T | undefined): FieldPatch<T> | undefined {
  if (!isObj(raw)) return undefined
  const v = value(raw.v)
  if (v === undefined) return undefined
  const p: FieldPatch<T> = { v, base: Math.max(0, num(raw.base)) }
  if (raw.archive === true) p.archive = true
  return p
}

export function normalizePatch(raw: unknown, exerciseId?: string): AnswerPatch | null {
  if (!isObj(raw)) return null
  const out: AnswerPatch = {}
  const text = normalizeFieldPatch(raw.text, (v) => (typeof v === 'string' ? v.slice(0, MAX_TEXT) : undefined))
  if (text) out.text = text
  if (isObj(raw.facts)) {
    const facts: Record<string, FactPatch> = {}
    for (const [k, v] of Object.entries(raw.facts).slice(0, MAX_FACTS)) {
      const key = resolveFactKey(exerciseId, k)
      if (!FACT_KEY_RE.test(key)) continue
      const p: FactPatch | undefined = normalizeFieldPatch(v, (x) => (typeof x === 'string' ? x.slice(0, MAX_FACT) : undefined))
      if (!p) continue
      const q = isObj(v) && typeof v.q === 'string' ? v.q.slice(0, MAX_FACT_LABEL) : ''
      if (q) p.q = q
      if (isObj(v) && isPos(v.pos)) p.pos = [v.pos[0], v.pos[1]]
      facts[key] = p
    }
    if (Object.keys(facts).length) out.facts = facts
  }
  const grade = normalizeFieldPatch(raw.grade, (v) => normalizeGrade(v))
  if (grade) out.grade = grade
  const history = normalizeHistory(raw.history, Date.now(), exerciseId)
  if (history.length) out.history = history
  if (typeof raw.device === 'string') out.device = str(raw.device, 80)
  return out.text || out.facts || out.grade || out.history ? out : null
}

/** server 套用一筆 patch：時間戳記一律用 server 的 now（保證遞增）。不會丟掉任何一次 text／fact 編輯 */
export function applyPatch(prev: AnswerEntry | undefined, patch: AnswerPatch, now: number = Date.now()): AnswerEntry {
  const e = cloneEntry(prev ?? emptyEntry())
  const device = patch.device ?? ''
  if (patch.text) {
    const p = patch.text
    const stamp = Math.max(now, e.textAt + 1)
    if ((e.textAt > p.base || p.archive) && e.text !== p.v && e.text.trim()) {
      e.history = addHistory(e.history, { field: 'text', text: e.text, at: e.textAt, replacedAt: stamp, device: e.textBy ?? '' })
    }
    e.text = p.v
    e.textAt = stamp
    if (device) e.textBy = device
    else delete e.textBy
  }
  for (const [k, p] of Object.entries(patch.facts ?? {})) {
    if (!has(e.facts, k) && Object.keys(e.facts).length >= MAX_FACTS) continue
    const cur = has(e.facts, k) ? e.facts[k] : ''
    const curAt = e.factsAt[k] ?? 0
    const stamp = Math.max(now, curAt + 1)
    if ((curAt > p.base || p.archive) && cur !== p.v && cur.trim()) {
      e.history = addHistory(e.history, { field: factField(k), text: cur, at: curAt, replacedAt: stamp, device: '' })
    }
    e.facts[k] = p.v
    e.factsAt[k] = stamp
    if (p.q) e.factQ = { ...(e.factQ ?? {}), [k]: p.q }
    if (p.pos) e.factPos = { ...(e.factPos ?? {}), [k]: p.pos }
  }
  if (patch.grade) {
    e.grade = patch.grade.v
    e.gradeAt = Math.max(now, e.gradeAt + 1)
  }
  if (patch.history) e.history = addHistory(e.history, ...patch.history)
  return finishEntry(e)
}

/** 每個題目還沒送出的欄位 → { base, archive } */
export type AnswerDirty = Record<string, Record<AnswerField, { base: number; archive?: true }>>

/**
 * client 套用 server 回傳的 entry：以 server 的內容為準，但這些欄位保留本地版本：
 *  - 還沒送出的欄位（dirty；它之後會用 patch 送出）；
 *  - 本地比 server 回傳的「新」的欄位（server 的時間戳記逐欄位遞增，較舊代表這份回應是過期的快照，例如在 ack 之後才到的 GET）。
 * force 裡的欄位（剛被 server 確認、蓋上 server 時間的欄位）一律採用 server 的版本。history 取聯集。
 */
export function adoptServerEntry(
  local: AnswerEntry | undefined,
  server: AnswerEntry,
  opts: { dirty?: Record<AnswerField, unknown>; force?: Iterable<AnswerField> } = {},
): AnswerEntry {
  if (!local) return server
  const dirty = opts.dirty ?? {}
  const force = new Set(opts.force ?? [])
  const keep = (f: AnswerField) => !force.has(f) && (has(dirty, f) || fieldAt(local, f) > fieldAt(server, f))
  const e = cloneEntry(server)
  e.history = addHistory(server.history, ...local.history)
  e.factQ = mergeLabels(server.factQ, local.factQ)
  e.factPos = { ...(local.factPos ?? {}), ...(server.factPos ?? {}) }
  if (keep('text')) {
    e.text = local.text
    e.textAt = local.textAt
    if (local.textBy) e.textBy = local.textBy
    else delete e.textBy
  }
  if (keep('grade') && local.grade) {
    e.grade = local.grade
    e.gradeAt = local.gradeAt
  }
  for (const k of Object.keys(local.facts)) {
    if (keep(factField(k))) {
      e.facts[k] = local.facts[k]
      e.factsAt[k] = local.factsAt[k] ?? 0
      if (local.factPos?.[k]) e.factPos[k] = local.factPos[k]
    }
  }
  finishEntry(e)
  return sameEntry(e, local) ? local : e
}

/**
 * 內容改了事實的文字（id 變了）時，哪些答案可以自動搬到新的事實：
 *  - 來源：內容裡已經沒有、有答案、而且記得寫入當時位置（factPos）的事實；
 *  - 目標：目前內容同一個位置的事實，而且當時與現在的事實數相同、這題從來沒有這個事實的資料（沒有答案也沒有歷史）；
 *  - 多個來源對到同一個目標、位置或數量對不上都不搬（不確定就交給使用者手動搬）。
 * 搬過的來源會清空（舊值進 history），永遠不會再當成目標，所以內容版本不同的裝置不會把答案搬來搬去；重複執行不會再搬。
 */
export function planFactMoves(e: AnswerEntry, factIds: string[]): Array<{ from: string; to: string }> {
  const current = new Set(factIds)
  const byTarget = new Map<string, string[]>()
  for (const [k, v] of Object.entries(e.facts)) {
    if (current.has(k) || !v.trim()) continue
    const pos = e.factPos?.[k]
    if (!pos || pos[1] !== factIds.length || pos[0] >= factIds.length) continue
    const to = factIds[pos[0]]
    if (has(e.facts, to) || e.history.some((h) => h.field === factField(to))) continue
    byTarget.set(to, [...(byTarget.get(to) ?? []), k])
  }
  return [...byTarget].filter(([, from]) => from.length === 1).map(([to, from]) => ({ from: from[0], to }))
}

/** 本地沒有標成 dirty、但比 server 新而且內容不同的欄位（server 的資料倒退了：要補送回去） */
export function localNewerFields(local: AnswerEntry, server: AnswerEntry, dirty?: Record<AnswerField, unknown>): AnswerField[] {
  return entryFields(local).filter((f) => !(dirty && has(dirty, f)) && fieldAt(local, f) > fieldAt(server, f) && !sameJSON(fieldValue(local, f), fieldValue(server, f)))
}

// ───────── progress ─────────

/** 每題最多單獨記幾台裝置的作答次數；超過的（最久沒作答的）併進 OTHERS */
export const COUNTS_CAP = 20
export const OTHERS = '~others'
const FOLDED_MAX = 500

type Counts = Record<string, DeviceCount>

function normalizeCount(c: unknown, now: number): DeviceCount | null {
  if (!Array.isArray(c)) return null
  const at = sanitizeStamp(Math.max(0, num(c[2])), now)
  return at ? [int(c[0]), int(c[1]), at] : [int(c[0]), int(c[1])]
}
const countAt = (c: DeviceCount | undefined) => (c && c.length > 2 ? (c[2] ?? 0) : 0)
const mkCount = (a: number, c: number, at: number): DeviceCount => (at ? [a, c, at] : [a, c])
const addCount = (x: DeviceCount, y: DeviceCount): DeviceCount => mkCount(x[0] + y[0], x[1] + y[1], Math.max(countAt(x), countAt(y)))
const maxCount = (x: DeviceCount, y: DeviceCount): DeviceCount => mkCount(Math.max(x[0], y[0]), Math.max(x[1], y[1]), Math.max(countAt(x), countAt(y)))

export const sumCounts = (c: Counts): [number, number] => Object.values(c).reduce<[number, number]>((s, x) => [s[0] + x[0], s[1] + x[1]], [0, 0])

/** 裝置數超過 COUNTS_CAP：最久沒作答的裝置（時間相同時依 id）併進 OTHERS，id 記進 folded */
function foldCounts(counts: Counts, folded: string[]): { counts: Counts; folded: string[] } {
  const devs = Object.keys(counts).filter((k) => k !== OTHERS)
  if (devs.length <= COUNTS_CAP) return { counts: sortRec(counts), folded: [...new Set(folded)].sort(cmp).slice(0, FOLDED_MAX) }
  const victims = devs.sort((x, y) => countAt(counts[x]) - countAt(counts[y]) || cmp(x, y)).slice(0, devs.length - COUNTS_CAP)
  const out: Counts = { ...counts }
  let others: DeviceCount = out[OTHERS] ?? [0, 0]
  for (const k of victims) {
    others = addCount(others, out[k])
    delete out[k]
  }
  out[OTHERS] = others
  return { counts: sortRec(out), folded: [...new Set([...folded, ...victims])].sort(cmp).slice(0, FOLDED_MAX) }
}

function normalizeCounts(v: unknown, foldedRaw: unknown, now: number): { counts: Counts; folded: string[] } {
  const folded = Array.isArray(foldedRaw) ? [...new Set(foldedRaw.filter((x): x is string => typeof x === 'string' && keyOk(x)).map((x) => x.slice(0, 80)))] : []
  const foldedSet = new Set(folded)
  const counts: Counts = {}
  if (isObj(v)) {
    for (const [k, c] of Object.entries(v).slice(0, 5000)) {
      const key = k.slice(0, 80)
      // 已經併進 others 的裝置不再單獨計算（避免重複計算）
      if (!keyOk(k) || foldedSet.has(key)) continue
      const n = normalizeCount(c, now)
      if (n) counts[key] = has(counts, key) ? maxCount(counts[key], n) : n
    }
  }
  return foldCounts(counts, folded)
}

/** 這台裝置多一次作答：只動自己的計數（自己已被併進 others 的話就加在 others） */
export function bumpDeviceCount(prev: ExerciseProgress | undefined, device: string, correct: boolean, now: number): Pick<ExerciseProgress, 'attempts' | 'correct' | 'counts' | 'folded'> {
  const counts: Counts = { ...(prev?.counts ?? (prev ? { legacy: [prev.attempts, prev.correct] as DeviceCount } : {})) }
  const folded = prev?.folded ?? []
  const key = folded.includes(device) ? OTHERS : device
  const cur = counts[key] ?? [0, 0]
  counts[key] = [cur[0] + 1, cur[1] + (correct ? 1 : 0), now]
  const f = foldCounts(counts, folded)
  const [attempts, c] = sumCounts(f.counts)
  return { attempts, correct: c, counts: f.counts, ...(f.folded.length ? { folded: f.folded } : {}) }
}

function normalizeExerciseProgress(v: unknown, now: number): ExerciseProgress | null {
  if (!isObj(v)) return null
  const last = RESULTS.includes(v.last as Result) ? (v.last as Result) : null
  if (!last) return null
  let { counts, folded } = normalizeCounts(v.counts, v.folded, now)
  // 舊格式只有總數：當作一個叫 legacy 的「裝置」，之後各裝置再各自累加
  if (Object.keys(counts).length === 0) counts = { legacy: [int(v.attempts, 1), int(v.correct)] }
  const [attempts, correct] = sumCounts(counts)
  return {
    attempts,
    correct,
    counts,
    ...(folded.length ? { folded } : {}),
    last,
    at: sanitizeStamp(num(v.at), now),
    box: Math.min(5, Math.max(1, int(v.box, 1))),
    due: typeof v.due === 'string' && DATE_RE.test(v.due) ? v.due : '1970-01-01',
  }
}

function normalizeMockItem(v: unknown): MockItem | null {
  if (!isObj(v) || typeof v.exerciseId !== 'string') return null
  const item: MockItem = { exerciseId: v.exerciseId, answer: str(v.answer, MAX_TEXT), seconds: int(v.seconds) }
  if (typeof v.selfScore === 'number') item.selfScore = Math.min(5, Math.max(1, Math.round(v.selfScore)))
  if (typeof v.aiScore === 'number') item.aiScore = Math.min(5, Math.max(1, Math.round(v.aiScore)))
  return item
}

function normalizeMock(v: unknown, now: number): MockSession | null {
  if (!isObj(v) || typeof v.id !== 'string') return null
  const items = Array.isArray(v.items) ? v.items.map(normalizeMockItem).filter((x): x is MockItem => !!x) : []
  return {
    id: v.id,
    startedAt: sanitizeStamp(num(v.startedAt), now),
    finishedAt: sanitizeStamp(num(v.finishedAt), now),
    updatedAt: sanitizeStamp(num(v.updatedAt, num(v.finishedAt)), now),
    items,
  }
}

export function normalizeProgress(raw: unknown, now: number = Date.now()): ProgressData {
  const out = emptyProgress()
  if (!isObj(raw)) return out
  out.updatedAt = sanitizeStamp(num(raw.updatedAt), now)
  if (isObj(raw.units)) {
    for (const [k, v] of Object.entries(raw.units)) {
      if (keyOk(k) && isObj(v)) out.units[k] = { read: v.read === true, at: sanitizeStamp(num(v.at), now) }
    }
  }
  if (isObj(raw.exercises)) {
    for (const [k, v] of Object.entries(raw.exercises)) {
      const e = keyOk(k) ? normalizeExerciseProgress(v, now) : null
      if (e) out.exercises[k] = e
    }
  }
  if (isObj(raw.plan)) {
    for (const [k, v] of Object.entries(raw.plan)) {
      if (keyOk(k) && isObj(v)) out.plan[k] = { done: v.done === true, at: sanitizeStamp(num(v.at), now) }
    }
  }
  if (Array.isArray(raw.mock)) {
    out.mock = raw.mock
      .map((m) => normalizeMock(m, now))
      .filter((x): x is MockSession => !!x)
      .slice(-100)
  }
  return out
}

/**
 * 兩台裝置的作答次數各自累加（取各裝置的最大值再加總）；SRS 狀態（last／box／due）以最新一次作答為準。
 * 被併進 others 的裝置（folded 的聯集）不再單獨計算；others 取「兩邊各自看到的 folded 裝置總數」的較大者，
 * 所以一邊已經併進 others、另一邊還單獨記著的裝置不會被算兩次。
 */
export function mergeExerciseProgress(a: ExerciseProgress, b: ExerciseProgress): ExerciseProgress {
  const ca: Counts = a.counts ?? { legacy: [a.attempts, a.correct] }
  const cb: Counts = b.counts ?? { legacy: [b.attempts, b.correct] }
  const fa = new Set(a.folded ?? [])
  const fb = new Set(b.folded ?? [])
  const F = new Set([...fa, ...fb])
  const view = (c: Counts, f: Set<string>): DeviceCount => {
    let o: DeviceCount = c[OTHERS] ?? [0, 0]
    for (const k of F) if (!f.has(k) && has(c, k)) o = addCount(o, c[k])
    return o
  }
  const others = maxCount(view(ca, fa), view(cb, fb))
  const counts: Counts = {}
  for (const k of new Set([...Object.keys(ca), ...Object.keys(cb)])) {
    if (k === OTHERS || F.has(k)) continue
    counts[k] = has(ca, k) && has(cb, k) ? maxCount(ca[k], cb[k]) : has(ca, k) ? ca[k] : cb[k]
  }
  if (others[0] || others[1] || has(ca, OTHERS) || has(cb, OTHERS)) counts[OTHERS] = others
  const folded = foldCounts(counts, [...F])
  const [attempts, correct] = sumCounts(folded.counts)
  const w = newerOf(a.at, b.at, JSON.stringify([a.last, a.box, a.due]), JSON.stringify([b.last, b.box, b.due])) === 'a' ? a : b
  const merged: ExerciseProgress = { attempts, correct, counts: folded.counts, ...(folded.folded.length ? { folded: folded.folded } : {}), last: w.last, at: w.at, box: w.box, due: w.due }
  if (sameJSON(merged, a)) return a
  if (sameJSON(merged, b)) return b
  return merged
}

function mergeStamped<T extends { at: number }>(a: T, b: T): T {
  return newerOf(a.at, b.at, JSON.stringify(a), JSON.stringify(b)) === 'a' ? a : b
}

function mergeRecords<T>(a: Record<string, T>, b: Record<string, T>, one: (x: T, y: T) => T): Record<string, T> {
  const out: Record<string, T> = { ...a }
  for (const [k, v] of Object.entries(b)) {
    const cur = out[k]
    out[k] = cur === undefined ? v : one(cur, v)
  }
  return out
}

export function mergeProgress(a: ProgressData, b: ProgressData): ProgressData {
  const mock = new Map<string, MockSession>()
  for (const s of [...a.mock, ...b.mock]) {
    const cur = mock.get(s.id)
    if (!cur || s.updatedAt > cur.updatedAt) mock.set(s.id, s)
  }
  return {
    version: DATA_VERSION,
    updatedAt: Math.max(a.updatedAt, b.updatedAt),
    units: mergeRecords(a.units, b.units, mergeStamped),
    exercises: mergeRecords(a.exercises, b.exercises, mergeExerciseProgress),
    plan: mergeRecords(a.plan, b.plan, mergeStamped),
    mock: [...mock.values()].sort((x, y) => x.startedAt - y.startedAt).slice(-100),
  }
}

export const isEmptyProgress = (p: ProgressData) =>
  p.updatedAt === 0 && !Object.keys(p.units).length && !Object.keys(p.exercises).length && !Object.keys(p.plan).length && !p.mock.length

// ───────── 本機 server 的寫入（server-plugin 與測試共用；I/O 與鎖在呼叫端）─────────

export class DataApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
  }
}

/** 舊版頁面（資料格式較舊）送資料時的 426 訊息 */
export const UPGRADE_MESSAGE =
  '資料格式已升級：這個分頁是舊版，請重新整理頁面載入新版。這個分頁還沒送出的內容存在這個瀏覽器（localStorage），重新整理後新版會自動合併進來（較舊的版本留在「歷史版本」）；如果這個瀏覽器不能儲存資料（例如無痕模式），請先複製內容再重新整理。'

/** PUT /api/answers 的格式檢查：舊版頁面 → 426；太長的內容 → 413（不靜默截斷） */
export function validateAnswersPut(body: unknown): asserts body is { version: number; patches: Record<string, unknown> } {
  // 舊版頁面（整筆 entry 覆蓋、或事實以 index 對齊）會把較新的內容蓋掉，所以直接拒絕，請它重新整理
  if (!isObj(body) || body.version !== DATA_VERSION || !isObj(body.patches)) throw new DataApiError(426, UPGRADE_MESSAGE)
  // 前端在輸入時就會擋，這裡是最後一道防線
  for (const [id, raw] of Object.entries(body.patches).slice(0, MAX_PATCHES)) {
    const r = raw as { text?: { v?: unknown }; facts?: Record<string, { v?: unknown }> } | null
    const longText = typeof r?.text?.v === 'string' && r.text.v.length > MAX_TEXT
    const longFact = Object.values(isObj(r?.facts) ? (r.facts as Record<string, { v?: unknown }>) : {}).some((f) => typeof f?.v === 'string' && f.v.length > MAX_FACT)
    if (longText || longFact) throw new DataApiError(413, `題目 ${id} 的回答或事實超過字數上限（回答 ${MAX_TEXT} 字、事實 ${MAX_FACT} 字），沒有儲存`)
  }
}

/** PUT /api/answers：套用各題的 patch（就地修改 data），回傳要回給 client 的內容 */
export function applyAnswersPut(data: AnswersData, body: unknown, now: number): { changed: boolean; response: AnswersData & { serverTime: number } } {
  validateAnswersPut(body)
  const entries = Object.entries(body.patches).slice(0, MAX_PATCHES)
  const out: Record<string, AnswerEntry> = {}
  for (const [id, raw] of entries) {
    const patch = keyOk(id) ? normalizePatch(raw, id) : null
    if (!patch) continue
    data.answers[id] = out[id] = applyPatch(data.answers[id], patch, now)
  }
  data.updatedAt = Math.max(data.updatedAt, ...Object.values(out).map((e) => e.updatedAt), 0)
  return { changed: Object.keys(out).length > 0, response: { version: DATA_VERSION, serverTime: now, updatedAt: data.updatedAt, answers: out } }
}

/** PUT /api/progress 的格式檢查：舊版頁面的作答次數是「總數」格式（會被當成 legacy 裝置重複計算）→ 426 */
export function validateProgressPut(body: unknown) {
  if (!isObj(body) || body.version !== DATA_VERSION) throw new DataApiError(426, UPGRADE_MESSAGE)
}

/** PUT /api/progress：client 送來的 delta 與現有資料合併（不信任 client 的時間：最多 now + 60 秒） */
export function applyProgressPut(existing: ProgressData, body: unknown, now: number): ProgressData {
  validateProgressPut(body)
  const incoming = normalizeProgress(body, now)
  for (const u of Object.values(incoming.units)) u.at = clampWrite(u.at, now)
  for (const x of Object.values(incoming.exercises)) x.at = clampWrite(x.at, now)
  for (const t of Object.values(incoming.plan)) t.at = clampWrite(t.at, now)
  for (const m of incoming.mock) m.updatedAt = clampWrite(m.updatedAt, now)
  return mergeProgress(existing, incoming)
}

// ───────── 變動追蹤（只同步有變動的 entry）─────────

export interface DirtyKeys {
  units: Set<string>
  exercises: Set<string>
  plan: Set<string>
  mock: Set<string>
  answers: Set<string>
}

export const newDirty = (): DirtyKeys => ({ units: new Set(), exercises: new Set(), plan: new Set(), mock: new Set(), answers: new Set() })

export const isDirtyEmpty = (d: DirtyKeys) => !d.units.size && !d.exercises.size && !d.plan.size && !d.mock.size && !d.answers.size

export function addDirty(into: DirtyKeys, from: DirtyKeys) {
  for (const k of Object.keys(into) as Array<keyof DirtyKeys>) for (const v of from[k]) into[k].add(v)
}

function changedKeys<T>(prev: Record<string, T>, next: Record<string, T>, into: Set<string>) {
  for (const k of new Set([...Object.keys(prev), ...Object.keys(next)])) if (prev[k] !== next[k]) into.add(k)
}

/** 以物件參照比較：合併函式會保留沒變動 entry 的參照，所以只會抓到真的有變的 */
export function diffByIdentity(
  prev: { progress: ProgressData; answers: AnswersData },
  next: { progress: ProgressData; answers: AnswersData },
): DirtyKeys {
  const d = newDirty()
  if (prev.progress !== next.progress) {
    changedKeys(prev.progress.units, next.progress.units, d.units)
    changedKeys(prev.progress.exercises, next.progress.exercises, d.exercises)
    changedKeys(prev.progress.plan, next.progress.plan, d.plan)
    const pm = new Map(prev.progress.mock.map((m) => [m.id, m]))
    for (const m of next.progress.mock) if (pm.get(m.id) !== m) d.mock.add(m.id)
  }
  if (prev.answers !== next.answers) changedKeys(prev.answers.answers, next.answers.answers, d.answers)
  return d
}

function localAhead<T>(local: Record<string, T>, remote: Record<string, T>, one: (remote: T, local: T) => T, into: Set<string>) {
  for (const [k, v] of Object.entries(local)) {
    const r = remote[k]
    if (r === undefined || one(r, v) !== r) into.add(k)
  }
}

/** local 含有 remote 沒有的資訊（比較新的欄位、遠端缺的東西、history）的 entry：開機合併後要補送回去的東西 */
export function diffNewer(
  local: { progress: ProgressData; answers: AnswersData },
  remote: { progress: ProgressData; answers: AnswersData },
  now: number = Date.now(),
): DirtyKeys {
  const d = newDirty()
  localAhead(local.progress.units, remote.progress.units, mergeStamped, d.units)
  localAhead(local.progress.exercises, remote.progress.exercises, mergeExerciseProgress, d.exercises)
  localAhead(local.progress.plan, remote.progress.plan, mergeStamped, d.plan)
  const rm = new Map(remote.progress.mock.map((m) => [m.id, m]))
  for (const m of local.progress.mock) {
    const r = rm.get(m.id)
    if (!r || m.updatedAt > r.updatedAt) d.mock.add(m.id)
  }
  localAhead(local.answers.answers, remote.answers.answers, (r, l) => mergeEntry(r, l, now), d.answers)
  return d
}

const pick = <T>(rec: Record<string, T>, keys: Set<string>): Record<string, T> => {
  const out: Record<string, T> = {}
  for (const k of keys) if (rec[k] !== undefined) out[k] = rec[k]
  return out
}

/** 只含有變動 entry 的 progress（delta） */
export function progressDelta(p: ProgressData, d: DirtyKeys): ProgressData {
  return {
    version: DATA_VERSION,
    updatedAt: p.updatedAt,
    units: pick(p.units, d.units),
    exercises: pick(p.exercises, d.exercises),
    plan: pick(p.plan, d.plan),
    mock: p.mock.filter((m) => d.mock.has(m.id)),
  }
}
