// 資料層回歸測試：「使用者的口徑永遠不會被靜默覆蓋或遺失」。
//   npm run test:data
// 包含 reviewer 的四個情境（A 舊分頁評分覆蓋手機編輯、B 時鐘偏差、C 首次同步前編輯、D 未來時間戳）、
// 舊格式遷移，以及隨機多裝置模擬（不變量：每一次 text 編輯不是在最後的內容裡，就是在 history 裡）。
// 後半段（I 之後）是事實 id 對齊、作答次數上限、426 等純函式測試；最後執行 store 層的整合測試（scripts/regress-store.ts）。
// 事實改成以穩定 id 對齊後，A–H 的事實改用 F(entry, i)（測試內容第 i 個事實的 id）讀取，情境本身不變。
import {
  COUNTS_CAP,
  DATA_VERSION,
  DataApiError,
  FACT_KEY_RE,
  HISTORY_MAX,
  OTHERS,
  addHistory,
  adoptServerEntry,
  applyAnswersPut,
  applyPatch,
  applyProgressPut,
  bumpDeviceCount,
  factIdsOf,
  planFactMoves,
  diffNewer,
  emptyEntry,
  emptyProgress,
  mergeAnswers,
  mergeEntry,
  mergeExerciseProgress,
  mergeProgress,
  nextStamp,
  normalizeAnswers,
  normalizePatch,
  normalizeProgress,
  setFactCatalog,
  stableJSON,
  validateAnswersPut,
  validateProgressPut,
  type AnswerPatch,
} from '../src/lib/data.ts'
import type { AnswerEntry, StoredGrade } from '../src/types.ts'

let failures = 0
let passes = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) passes++
  else failures++
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${name}${cond ? '' : `\n      ${detail}`}`)
}

const NOW = 1_000_000_000_000
const grade: StoredGrade = { score: 4, summary: 's', covered: [], missing: [], inaccurate: [], followUps: [], improved: '', at: 1, lang: 'zh', answer: 'x' }
// 測試內容：題目 X 的事實 id（舊格式 facts[i] 遷移成 X_FACTS[i]）
const X_FACTS = ['kfa', 'kfb', 'kfc', 'kfd']
setFactCatalog((id) => (id === 'X' ? { ids: X_FACTS, labels: X_FACTS.map((k) => `事實 ${k}`) } : undefined))
type Loose = Omit<Partial<AnswerEntry>, 'facts' | 'factsAt'> & { text?: string; facts?: string[] | Record<string, string>; factsAt?: number[] | Record<string, number> }
const E = (o: Loose): AnswerEntry => normalizeAnswers({ answers: { X: o } }, NOW).answers.X
/** 第 i 個事實（依測試內容的順序） */
const F = (e: AnswerEntry, i: number) => e.facts[X_FACTS[i]]
const inHistoryOrCurrent = (e: AnswerEntry, text: string, field = 'text') => (field === 'text' ? e.text === text : e.facts[field.slice(2)] === text) || e.history.some((h) => h.field === field && h.text === text)
const sameJSON = (a: unknown, b: unknown) => stableJSON(a) === stableJSON(b)

// ───────── A：桌機沒 pull（stale base）對同一題存評分 → 不能覆蓋手機較新的文字 ─────────
{
  const phone = E({ text: 'PHONE-B', facts: ['f-phone'], updatedAt: NOW - 800 })
  const patch = normalizePatch({ grade: { v: grade, base: 0 }, device: 'Mac・aaaa' }) as AnswerPatch
  const afterServer = applyPatch(phone, patch, NOW)
  check('A server: stale desktop saving a grade keeps the phone text and facts', afterServer.text === 'PHONE-B' && F(afterServer, 0) === 'f-phone' && !!afterServer.grade)
  // 靜態版（時間戳記合併）：桌機舊 text(100)＋新 grade(300) vs 手機 text(200)
  const phoneS = E({ text: 'PHONE-B', facts: ['f-phone'], updatedAt: 200 })
  const deskS = E({ text: 'OLD-A', grade, updatedAt: 100 })
  deskS.gradeAt = 300
  const merged = mergeEntry(phoneS, deskS)
  check('A cloud: field-level merge — newer text from phone, newer grade from desktop', merged.text === 'PHONE-B' && !!merged.grade && F(merged, 0) === 'f-phone')
  check('A cloud: the losing OLD-A text is kept in history', merged.history.some((h) => h.field === 'text' && h.text === 'OLD-A'))
}

// ───────── B：時鐘偏差（手機快 30 秒、桌機 10 秒後真實時間編輯）─────────
{
  // 本機 server：時間以 server 為準，兩次編輯依抵達順序，後到的贏、先到的進 history
  let srv: AnswerEntry | undefined
  srv = applyPatch(srv, normalizePatch({ text: { v: 'phone', base: 0 }, device: 'iPhone' }) as AnswerPatch, NOW)
  const base = srv.textAt // 桌機看到的是手機之前的版本
  srv = applyPatch(srv, normalizePatch({ text: { v: 'desktop-newer-edit', base: 0 }, device: 'Mac' }) as AnswerPatch, NOW + 10_000)
  check('B server: later arrival wins regardless of client clocks', srv.text === 'desktop-newer-edit' && srv.textAt > base)
  check('B server: the earlier phone text is in history', srv.history.some((h) => h.text === 'phone'))
  // 靜態版：手機時鐘快 30 秒 → 桌機真實時間較晚的編輯在戳記上輸了，但不會遺失
  const phone = E({ text: 'phone', updatedAt: NOW + 30_000 })
  const desk = E({ text: 'desktop-newer-edit', updatedAt: NOW + 10_000 })
  // 輸掉的版本記被取代的時間（now）：比較順序無關時兩次合併用同一個 now
  const m1 = mergeEntry(phone, desk, NOW + 20_000)
  const m2 = mergeEntry(desk, phone, NOW + 20_000)
  check('B cloud: both texts survive (one current, one in history)', inHistoryOrCurrent(m1, 'phone') && inHistoryOrCurrent(m1, 'desktop-newer-edit'))
  check('B cloud: merge is order-independent', sameJSON(m1, m2))
}

// ───────── C：開機視窗（新裝置 localStorage 空，在 load 完成前打字）─────────
{
  const remote = E({ text: 'cloud text', facts: ['10 人團隊'], grade, updatedAt: 500 })
  const typed = E({ text: 'typed before load', facts: [], updatedAt: 900 })
  const merged = mergeEntry(typed, remote)
  check('C cloud: typed text wins, remote facts and grade are kept', merged.text === 'typed before load' && F(merged, 0) === '10 人團隊' && !!merged.grade)
  check('C cloud: remote text is in history', merged.history.some((h) => h.text === 'cloud text'))
  const dn = diffNewer({ progress: emptyProgress(), answers: { version: 2, updatedAt: 900, answers: { X: typed } } }, { progress: emptyProgress(), answers: { version: 2, updatedAt: 500, answers: { X: remote } } })
  check('C: the typed entry is marked to be pushed back', dn.answers.has('X'))
  // 本機 server：base 0 的編輯照樣套用，server 原本的 text 進 history，facts 與 grade 不受影響
  const srv = applyPatch(remote, normalizePatch({ text: { v: 'typed before load', base: 0 } }) as AnswerPatch, NOW)
  check('C server: offline edit applied, remote text archived, remote facts/grade untouched', srv.text === 'typed before load' && F(srv, 0) === '10 人團隊' && !!srv.grade && srv.history.some((h) => h.text === 'cloud text'))
  // client 套用 server 回應時，dirty 的欄位保留本地版本
  const adopted = adoptServerEntry(E({ text: 'local unsent', facts: ['x'], updatedAt: 400 }), remote, { dirty: { text: { base: 0 } } })
  check('C adopt: unsent local text is kept, other fields come from the server', adopted.text === 'local unsent' && F(adopted, 0) === '10 人團隊' && !!adopted.grade)
}

// ───────── D：未來時間戳凍結 ─────────
{
  const evil = normalizeAnswers({ answers: { X: { text: 'evil/future', facts: [], updatedAt: 9e15, textAt: 9e15 } } }, NOW).answers.X
  check('D: a timestamp beyond now + 1 day is read as now', evil.textAt === NOW && evil.updatedAt === NOW)
  const real = E({ text: 'real edit', updatedAt: NOW + 5 })
  const m = mergeEntry(evil, real)
  check('D: a later real edit beats the clamped future timestamp', m.text === 'real edit' && inHistoryOrCurrent(m, 'evil/future'))
  check('D: a new edit stamp never exceeds now + 60s even when the previous stamp is far in the future', nextStamp(NOW + 3 * 3600_000, NOW) <= NOW + 60_000)
  // server 模型：client 帶什麼時間都不影響（時間由 server 蓋）
  const s = applyPatch(undefined, normalizePatch({ text: { v: 'a', base: 9e15 } }) as AnswerPatch, NOW)
  check('D server: stamps are server time', s.textAt === NOW)
}

// ───────── E：舊格式遷移 ─────────
{
  const legacy = { version: 1, updatedAt: 1234, answers: { 'resume-a-01': { text: '舊的口徑', facts: ['a', 'b'], grade, updatedAt: 1234 } } }
  const n = normalizeAnswers(legacy, NOW).answers['resume-a-01']
  const legacyX = normalizeAnswers({ ...legacy, answers: { X: legacy.answers['resume-a-01'] } }, NOW).answers.X
  check('E: legacy entry upgrades to field-level (every field carries the old updatedAt)', n.textAt === 1234 && sameJSON(Object.values(n.factsAt), [1234, 1234]) && n.gradeAt === 1234 && n.history.length === 0 && legacyX.factsAt[X_FACTS[1]] === 1234)
  const p = normalizeProgress({ version: 1, exercises: { q1: { attempts: 3, correct: 2, last: 'correct', at: 100, box: 2, due: '2026-10-24' } } }, NOW)
  check('E: legacy progress counts become one "legacy" device', p.exercises.q1.counts?.legacy?.[0] === 3 && p.exercises.q1.attempts === 3)
  const twice = mergeProgress(p, normalizeProgress({ exercises: { q1: { attempts: 3, correct: 2, last: 'correct', at: 100, box: 2, due: '2026-10-24' } } }, NOW))
  check('E: merging the same legacy data from two places does not double count', twice.exercises.q1.attempts === 3)
  const a = { ...p.exercises.q1, counts: { legacy: [3, 2], devA: [2, 1] } as Record<string, [number, number]>, attempts: 5, correct: 3, at: 200 }
  const b = { ...p.exercises.q1, counts: { legacy: [3, 2], devB: [1, 1] } as Record<string, [number, number]>, attempts: 4, correct: 3, at: 300, box: 4, last: 'correct' as const }
  const m = mergeExerciseProgress(a, b)
  check('E: two devices answering concurrently add up (no overwrite); SRS follows the latest answer', m.attempts === 6 && m.correct === 4 && m.box === 4 && m.at === 300)
  const legacyEntry = normalizeAnswers({ answers: { X: { text: 'old', facts: [], updatedAt: 100 } } }, NOW).answers.X
  check('E: unknown/garbage fields do not break normalization', normalizeAnswers({ answers: { X: { text: 123, facts: 'x', history: 'y' }, Y: 5 } }, NOW).answers.X.text === '' && !!legacyEntry)
}

// ───────── F：history 規則 ─────────
{
  let h = [] as AnswerEntry['history']
  for (let i = 0; i < 15; i++) h = addHistory(h, { field: 'text', text: `v${i}`, at: i, replacedAt: i + 1, device: '' })
  check('F: history keeps at most 10 per field, newest first', h.length === 10 && h[0].text === 'v14' && h[9].text === 'v5')
  const dup = addHistory(h, { field: 'text', text: 'v14', at: 99, replacedAt: 99, device: '' })
  check('F: identical text is not duplicated', dup.filter((x) => x.text === 'v14').length === 1)
  const empty = mergeEntry(E({ text: '', updatedAt: 5 }), E({ text: 'real', updatedAt: 3 }))
  check('F: an empty text that "wins" does not erase the other text silently (it goes to history)', empty.text === '' && empty.history.some((x) => x.text === 'real'))
  check('F: merging an entry with itself returns the same object (identity preserved)', (() => { const e = E({ text: 'z', updatedAt: 9 }); return mergeEntry(e, e) === e })())
}

// ───────── G：隨機多裝置模擬 ─────────
// 不變量：每一次 text 編輯，不是在最後的 text／history 裡，就是被「看過它的人」有意識地覆寫掉（自己改自己的）。
// 兩台裝置「同時」編輯（互相沒看過對方的版本）造成的輸家，一定要留在 history。
{
  let rngSeed = 12345
  const rnd = () => ((rngSeed = (rngSeed * 1664525 + 1013904223) >>> 0) / 2 ** 32)
  const pick = <T,>(xs: T[]) => xs[Math.floor(rnd() * xs.length)]
  let allOk = true
  let assocOk = true
  let detail = ''
  for (let round = 0; round < 500; round++) {
    // 靜態版模型：3 台裝置，各自有時鐘偏差，隨機編輯與隨機兩兩合併
    const skew = [0, rnd() * 60_000 - 30_000, rnd() * 120_000 - 60_000]
    const devices: AnswerEntry[] = [emptyEntry(), emptyEntry(), emptyEntry()]
    const written: string[] = []
    const superseded = new Set<string>()
    let clock = NOW
    for (let step = 0; step < 14; step++) {
      clock += Math.floor(rnd() * 5000) + 1
      if (rnd() < 0.6 && written.length < 8) {
        const d = Math.floor(rnd() * 3)
        const text = `r${round}-s${step}-d${d}`
        written.push(text)
        if (devices[d].text) superseded.add(devices[d].text) // 這台裝置看著舊內容改成新內容：有意識的覆寫
        const at = nextStamp(devices[d].textAt, clock + skew[d])
        devices[d] = { ...devices[d], text, textAt: at, updatedAt: Math.max(devices[d].updatedAt, at) }
      } else {
        const a = Math.floor(rnd() * 3)
        const b = (a + 1 + Math.floor(rnd() * 2)) % 3
        const m = mergeEntry(devices[a], devices[b], clock)
        devices[a] = m
        if (rnd() < 0.5) devices[b] = m
      }
    }
    const final = devices.reduce((acc, e) => mergeEntry(acc, e, clock), emptyEntry())
    for (const t of written) {
      if (!inHistoryOrCurrent(final, t) && !superseded.has(t)) {
        allOk = false
        detail = `lost ${t} in round ${round}`
      }
    }
    const [a, b, c] = devices
    if (!sameJSON(mergeEntry(mergeEntry(a, b, clock), c, clock), mergeEntry(a, mergeEntry(b, c, clock), clock)) || !sameJSON(mergeEntry(a, b, clock), mergeEntry(b, a, clock))) {
      assocOk = false
      detail ||= `non-commutative/associative in round ${round}`
    }
  }
  check('G cloud model: 500 random 3-device runs — no concurrently written text is ever lost', allOk, detail)
  check('G cloud model: merge is commutative and associative', assocOk, detail)

  // 本機 server 模型：隨機的 base（可能很舊）、隨機順序的 patch
  let srvOk = true
  let srvDetail = ''
  for (let round = 0; round < 500; round++) {
    let e: AnswerEntry | undefined
    const written: string[] = []
    const superseded = new Set<string>()
    let t = NOW
    for (let step = 0; step < 8; step++) {
      t += Math.floor(rnd() * 3000) + 1
      const text = `s${round}-${step}`
      written.push(text)
      const base = e ? pick([0, e.textAt, Math.max(0, e.textAt - 500)]) : 0
      if (e && base >= e.textAt) superseded.add(e.text) // base 是最新的：使用者看著目前的內容改的
      e = applyPatch(e, normalizePatch({ text: { v: text, base }, device: pick(['Mac', 'iPhone']) }) as AnswerPatch, t)
    }
    for (const x of written) {
      if (!inHistoryOrCurrent(e!, x) && !superseded.has(x)) {
        srvOk = false
        srvDetail = `lost ${x}`
      }
    }
  }
  check('G server model: 500 random runs with stale bases — a conflicting (stale-base) edit never destroys the other version', srvOk, srvDetail)
  const restore = applyPatch(E({ text: 'current', updatedAt: NOW - 1 }), normalizePatch({ text: { v: 'restored', base: NOW - 1, archive: true } }) as AnswerPatch, NOW)
  check('G: archive flag (restore from history) keeps the replaced text even without a conflict', restore.history.some((h) => h.text === 'current'))
  const idem = (() => {
    let e = applyPatch(undefined, normalizePatch({ text: { v: 'same', base: 0 } }) as AnswerPatch, NOW)
    e = applyPatch(e, normalizePatch({ text: { v: 'same', base: 0 } }) as AnswerPatch, NOW + 5) // 回應遺失後重送
    return e.history.length === 0
  })()
  check('G: re-sending the same patch (lost response) does not pollute history', idem)
}

// ───────── H：匯入的欄位級合併 ─────────
{
  const current = E({ text: 'new text', facts: ['fact-now'], updatedAt: 900 })
  const backup = E({ text: 'older backup text', facts: ['fact-old', 'extra-fact'], updatedAt: 100 })
  const merged = mergeAnswers({ version: 2, updatedAt: 900, answers: { X: current } }, { version: 2, updatedAt: 100, answers: { X: backup } }).answers.X
  check('H import merge: older backup fields do not win; missing fields are filled in', merged.text === 'new text' && F(merged, 0) === 'fact-now' && F(merged, 1) === 'extra-fact')
  check('H import merge: the older backup text is kept in history (never lost)', merged.history.some((h) => h.text === 'older backup text'))
}

// ───────── I：事實以穩定 id 對齊（內容增刪事實不會錯位）─────────
{
  const ids = factIdsOf(['團隊人數', '使用的 CI', '團隊人數'])
  check('I: fact ids are stable hashes of the fact text; duplicates get a suffix', ids[0] === factIdsOf(['  團隊人數 '])[0] && ids[0] !== ids[1] && ids[2] === `${ids[0]}-2` && ids.every((k) => FACT_KEY_RE.test(k)))
  // 內容在最前面插入一個事實、刪掉一個：已經以 id 存的口徑不受影響
  const stored = { text: '', textAt: 0, facts: { kfa: '10 人', kfb: 'GitHub Actions' }, factsAt: { kfa: NOW - 5, kfb: NOW - 4 }, gradeAt: 0, history: [], updatedAt: NOW - 4 }
  setFactCatalog((id) => (id === 'X' ? { ids: ['kfnew', 'kfa'], labels: ['新事實', '團隊人數'] } : undefined))
  const after = normalizeAnswers({ answers: { X: stored } }, NOW).answers.X
  check('I: after facts are inserted/removed in content, id-keyed answers stay on the same fact (removed fact is kept, not shifted)', after.facts.kfa === '10 人' && after.facts.kfb === 'GitHub Actions' && after.facts.kfnew === undefined)
  // 舊格式（index）只在遷移當下用內容對應一次
  const legacy = normalizeAnswers({ answers: { X: { text: 't', facts: ['新的答案', '舊的 10 人'], updatedAt: NOW - 9, history: [{ field: 'f1', text: '更舊的', at: 1, replacedAt: 2, device: '' }] } } }, NOW).answers.X
  check('I: legacy index-based facts and history fields migrate to the fact ids of the current content', legacy.facts.kfnew === '新的答案' && legacy.facts.kfa === '舊的 10 人' && legacy.history.some((h) => h.field === 'f:kfa' && h.text === '更舊的') && legacy.factQ?.kfa === '團隊人數')
  setFactCatalog((id) => (id === 'X' ? { ids: X_FACTS, labels: X_FACTS.map((k) => `事實 ${k}`) } : undefined))
  // 遷移時查不到內容：先用位置 key，之後查得到時自動對應；撞到已有的 id 就取新的、另一個進 history
  setFactCatalog(() => undefined)
  const unresolved = normalizeAnswers({ answers: { X: { text: '', facts: ['位置 0 的舊值'], factsAt: [NOW - 50], updatedAt: NOW - 50 } } }, NOW).answers.X
  setFactCatalog((id) => (id === 'X' ? { ids: X_FACTS, labels: X_FACTS.map((k) => `事實 ${k}`) } : undefined))
  const resolved = normalizeAnswers({ answers: { X: { ...unresolved, facts: { ...unresolved.facts, kfa: '新版本已經填的值' }, factsAt: { ...unresolved.factsAt, kfa: NOW - 10 } } } }, NOW).answers.X
  check('I: unresolved positional keys (@i) resolve once content is known; on collision the older value goes to history', unresolved.facts['@0'] === '位置 0 的舊值' && resolved.facts.kfa === '新版本已經填的值' && !('@0' in resolved.facts) && resolved.history.some((h) => h.field === 'f:kfa' && h.text === '位置 0 的舊值'))
  // 兩個內容版本的裝置各自填不同的事實（舊做法：兩個都是 index 0 → 互相衝突、錯位）
  const deskOld = E({ facts: { kfa: 'A 填的事實 a' }, factsAt: { kfa: NOW - 3 } })
  const phoneNew = E({ facts: { kfb: 'B 填的事實 b' }, factsAt: { kfb: NOW - 2 } })
  const m = mergeEntry(deskOld, phoneNew, NOW)
  check('I: merging by fact id keeps both devices\' facts on their own facts (no index collision)', m.facts.kfa === 'A 填的事實 a' && m.facts.kfb === 'B 填的事實 b' && m.history.length === 0)
  const p = applyPatch(E({ facts: { kfa: '舊' }, factsAt: { kfa: NOW - 100 } }), normalizePatch({ facts: { kfa: { v: '新', base: NOW - 200, q: '團隊人數' } } }, 'X') as AnswerPatch, NOW)
  check('I server: a fact patch is keyed by fact id, archives the conflicting value, and keeps the fact label', p.facts.kfa === '新' && p.factQ?.kfa === '團隊人數' && p.history.some((h) => h.field === 'f:kfa' && h.text === '舊'))
}

// ───────── J：history 的 replacedAt＝被取代的時間 ─────────
{
  let cur = E({ text: 'current', updatedAt: NOW })
  for (let i = 0; i < HISTORY_MAX; i++) cur = { ...cur, history: addHistory(cur.history, { field: 'text', text: `recent-${i}`, at: NOW - 5000 + i, replacedAt: NOW - 1000 + i, device: '' }) }
  const offline = E({ text: '離線很久才回來的版本', updatedAt: NOW - 3_600_000 })
  const m = mergeEntry(cur, offline, NOW + 10)
  const item = m.history.find((h) => h.text === '離線很久才回來的版本')
  check('J: a version that just lost a merge is stamped with the time it was replaced, so eviction drops older history first', !!item && item.replacedAt === NOW + 10 && item.at === NOW - 3_600_000 && m.history.filter((h) => h.field === 'text').length === HISTORY_MAX && !m.history.some((h) => h.text === 'recent-0'))
}

// ───────── K：作答次數：裝置超過上限時併進 others，不丟、不重複計算 ─────────
{
  const many: Record<string, [number, number, number]> = {}
  for (let i = 0; i < 25; i++) many[`dev${String(i).padStart(2, '0')}`] = [1, i % 2, NOW - 1000 + i]
  const p = normalizeProgress({ exercises: { q: { last: 'correct', at: NOW, box: 1, due: '2026-10-24', counts: many } } }, NOW).exercises.q
  check('K: more than COUNTS_CAP devices — the oldest are folded into others, nothing is dropped', p.attempts === 25 && p.correct === 12 && Object.keys(p.counts!).filter((k) => k !== OTHERS).length === COUNTS_CAP && p.folded?.length === 5 && p.folded.includes('dev00'))
  // 另一份還沒折疊的舊資料（同樣的 25 台）合併進來：不會重複計算
  const stale = normalizeProgress({ exercises: { q: { last: 'correct', at: NOW - 5, box: 1, due: '2026-10-24', counts: Object.fromEntries(Object.entries(many).slice(0, 20)) } } }, NOW).exercises.q
  const m1 = mergeExerciseProgress(p, stale)
  const m2 = mergeExerciseProgress(stale, p)
  check('K: merging with a replica that still has folded devices separately does not double count (and is order-independent)', m1.attempts === 25 && sameJSON(m1, m2))
  // 被折疊的裝置在另一邊又多作答一次：這次也要算進去
  const later = { ...stale, counts: { ...stale.counts, dev00: [2, 1, NOW] as [number, number, number] } }
  check('K: an increment on a device that was folded elsewhere is still counted', mergeExerciseProgress(p, later).attempts === 26)
  const bumped = bumpDeviceCount(p, 'dev00', true, NOW + 1)
  check('K: a folded device answering again bumps others instead of reappearing', bumped.attempts === 26 && !('dev00' in bumped.counts!) && mergeExerciseProgress({ ...p, ...bumped }, p).attempts === 26)
}

// ───────── L：舊版頁面送 progress／answers 一律 426（不會把總數當 legacy 重複計算）─────────
{
  const code = (fn: () => unknown) => {
    try {
      fn()
      return 200
    } catch (e) {
      return e instanceof DataApiError ? e.status : -1
    }
  }
  const oldTab = { version: 2, updatedAt: NOW, units: {}, exercises: { q: { attempts: 6, correct: 4, last: 'correct', at: NOW, box: 3, due: '2026-10-25' } }, plan: {}, mock: [] }
  check('L: an old tab PUTting progress (totals format) gets 426', code(() => validateProgressPut(oldTab)) === 426 && code(() => applyProgressPut(normalizeProgress({}), oldTab, NOW)) === 426)
  check('L: an old tab PUTting answers gets 426; the current version is accepted', code(() => validateAnswersPut({ version: 2, patches: {} })) === 426 && code(() => applyAnswersPut(normalizeAnswers({}), { version: DATA_VERSION, patches: {} }, NOW)) === 200)
}

// ───────── M：較舊的快照不會蓋掉本地已經拿到的新版本 ─────────
{
  const local = E({ text: 'acked v2', textAt: NOW, facts: { kfa: 'f2' }, factsAt: { kfa: NOW } } as Loose)
  const staleSnap = E({ text: 'v1', textAt: NOW - 100, facts: { kfa: 'f1' }, factsAt: { kfa: NOW - 100 } } as Loose)
  const kept = adoptServerEntry(local, staleSnap)
  check('M: adopting a stale server snapshot keeps the newer local fields', kept === local)
  const forced = adoptServerEntry(local, staleSnap, { force: ['text'] })
  check('M: fields just acknowledged by the server (force) take the server version', forced.text === 'v1' && forced.facts.kfa === 'f2')
}

// ───────── N：內容改了事實的文字 → 哪些答案可以自動搬（planFactMoves）─────────
{
  const base = (facts: Record<string, string>, pos: Record<string, [number, number]>, history: AnswerEntry['history'] = []): AnswerEntry => ({
    ...emptyEntry(),
    facts,
    factsAt: Object.fromEntries(Object.keys(facts).map((k) => [k, NOW])),
    factPos: pos,
    history,
  })
  const rename = planFactMoves(base({ kfa: '10 人', kfb: 'CI' }, { kfa: [0, 2], kfb: [1, 2] }), ['kfnew', 'kfb'])
  check('N: a renamed fact at the same position with the same fact count is moved', sameJSON(rename, [{ from: 'kfa', to: 'kfnew' }]))
  check('N: not moved when the fact count changed (insert + rename)', planFactMoves(base({ kfa: '10 人' }, { kfa: [0, 2] }), ['kfx', 'kfnew', 'kfb']).length === 0)
  check('N: not moved when the target position holds a fact this entry already knows (reorder + rename)', planFactMoves(base({ kfa: '10 人', kfb: 'CI' }, { kfa: [0, 2], kfb: [1, 2] }), ['kfb', 'kfnew']).length === 0)
  check('N: not moved when two removed answers claim the same new fact (many-to-one)', planFactMoves(base({ kfa: 'x', kfz: 'z' }, { kfa: [0, 2], kfz: [0, 2] }), ['kfnew', 'kfb']).length === 0)
  check('N: not moved into a fact that has history (it is not new), nor from a fact without a recorded position', planFactMoves(base({ kfa: 'x' }, { kfa: [0, 2] }, [{ field: 'f:kfnew', text: 'old', at: 1, replacedAt: 2, device: '' }]), ['kfnew', 'kfb']).length === 0 && planFactMoves(base({ kfa: 'x' }, {}), ['kfnew', 'kfb']).length === 0)
  const moved = base({ kfa: '', kfnew: '10 人' }, { kfa: [0, 2], kfnew: [0, 2] })
  check('N: after the move nothing is planned again (idempotent), also from an older content version', planFactMoves(moved, ['kfnew', 'kfb']).length === 0 && planFactMoves(moved, ['kfa', 'kfb']).length === 0)
  const legacyPos = normalizeAnswers({ answers: { X: { text: '', facts: ['a', 'b'], updatedAt: NOW - 1 } } }, NOW).answers.X
  check('N: legacy index-based facts record their original position when migrated', sameJSON(legacyPos.factPos, { kfa: [0, 4], kfb: [1, 4] }))
}

// ───────── store 層整合測試（假的 server／db／時鐘，打包後的前端模組）─────────
{
  const { runStoreTests } = await import('./regress-store.ts')
  await runStoreTests(check)
}

console.log(`\n${passes} passed, ${failures} failed`)
process.exit(failures ? 1 : 0)
