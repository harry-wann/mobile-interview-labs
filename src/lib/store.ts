// 使用者資料 store：進度、我的口徑。
//
// 核心目標：使用者寫的口徑永遠不會被靜默覆蓋或遺失（同時用電腦、手機、靜態版）。
//  - 欄位級合併＋歷史：text、每個事實（穩定 id）、grade 各自帶時間戳記；輸掉的版本進 history（見 data.ts）。
//  - 本機版（server）：時間以 server 為準。client 只送「我改了哪些欄位」（patch，附 base＝最後看到的版本），
//    server 套用時蓋上自己的時間；base 比 server 現存的舊就照樣套用，但 server 原本的內容進 history。
//    還沒送出的欄位（dirty）存在 localStorage，重新整理也不會忘。
//  - 編輯中別台更新了同一個欄位：輸入框不換掉內容；使用者繼續打字時，store 裡「他沒看過」的版本先進 history，
//    patch 也帶 archive，server 一定會把它現有的版本封存（saveField）。
//  - 伺服器快照可能過期（GET 在 ack 之後才到）：逐欄位比較時間戳記，較舊的欄位不套用（adoptServerEntry）。
//  - 靜態版（Artifact）：localStorage ＋ 雲端存檔（src/lib/cloud.ts），沒有 server 時用時間戳記合併，寫入時間 clamp 到 now+60s。
//  - 新鮮度：前景每 20 秒 pull，focus／pageshow／切回分頁立即 pull；開始編輯某題、按 AI 評分前先 pull。
import { useSyncExternalStore } from 'react'
import { getIndex, subscribeContent } from '../content.ts'
import { IS_STATIC } from '../env.ts'
import type { AnswerEntry, AnswersData, ExerciseProgress, HistoryItem, MockSession, ProgressData, Result, StoredGrade } from '../types.ts'
import {
  DATA_VERSION,
  MAX_FACT_LABEL,
  addDirty,
  addHistory,
  adoptServerEntry,
  bumpDeviceCount,
  diffByIdentity,
  diffNewer,
  emptyAnswers,
  emptyEntry,
  entryFields,
  entryUpdatedAt,
  factField,
  factKeyOf,
  fieldAt,
  fieldText,
  fieldValue,
  isDirtyEmpty,
  isFactField,
  localNewerFields,
  mergeAnswers,
  mergeEntry,
  mergeProgress,
  newDirty,
  nextStamp,
  normalizeAnswers,
  normalizeProgress,
  planFactMoves,
  progressDelta,
  resolveField,
  setFactCatalog,
  stableJSON,
  type AnswerDirty,
  type AnswerField,
  type AnswerPatch,
  type DirtyKeys,
} from './data.ts'
import { getDevice } from './device.ts'
import { htmlToText } from './gradePrompt.ts'
import { lanHeaders } from './lan.ts'
import { onPageHide } from './lifecycle.ts'
import { applyResult } from './srs.ts'
import { getJSON, removeItem, setJSON } from './storage.ts'

const KEY_PROGRESS = 'bt.progress.v3'
const KEY_ANSWERS = 'bt.answers.v3'
const KEY_DIRTY = 'bt.dirty.v3'
/** 哪些題目還要用時間戳記推斷一次「哪些欄位還沒送出」；只有推斷真的跑過之後才會寫成 { all: false } */
const KEY_INFER = 'bt.infer.v3'
const KEY_UNDO = 'bt.importUndo.v1'
/** 舊版頁面的 localStorage：開機（和舊分頁寫入時）合併進來，存成新格式後刪除 */
const LEGACY_ANSWERS = ['bt.answers.v2', 'bt.answers.v1']
const LEGACY_PROGRESS = ['bt.progress.v2', 'bt.progress.v1']
const LEGACY_DIRTY = 'bt.dirty.v2'

const PULL_INTERVAL_MS = 20_000
const GATE_TIMEOUT_MS = 5_000
const BATCH_BYTES = 1_000_000
const MAX_ENTRY_BYTES = 1_800_000
const KEEPALIVE_ANSWERS_BYTES = 40_000
const KEEPALIVE_PROGRESS_BYTES = 20_000

export type SyncMode = 'server' | 'cloud' | 'none'
export type SyncStatus = 'connecting' | 'ok' | 'syncing' | 'offline' | 'error'
/** 首次同步前不能編輯（避免用「只有本地」的內容蓋掉遠端）：syncing 唯讀；5 秒沒同步成功就 offline-edit（可編輯，連線後欄位級合併） */
export type SyncGate = 'syncing' | 'ready' | 'offline-edit'
export interface SyncState {
  mode: SyncMode
  status: SyncStatus
  gate: SyncGate
  /** 需要讓使用者看到的訊息（配額已滿、資料太大 …） */
  notice: string | null
  lastAt: number | null
}

export interface ImportUndoInfo {
  at: number
  /** 這次匯入動到幾題口徑 */
  count: number
}

interface State {
  progress: ProgressData
  answers: AnswersData
  sync: SyncState
  /** 這個連線是否在本機（可以執行程式、AI 評分）；null = 還不知道 */
  isLocalClient: boolean | null
  /** 別台裝置更新了這題的口徑（題目 id → 時間）；UI 用來提示，不會直接換掉正在編輯的內容 */
  remoteNotice: Record<string, number>
  importUndo: ImportUndoInfo | null
}

/** 編輯器開始編輯時所依據的版本（值＋時間戳記） */
export interface FieldBase {
  v: string
  at: number
}

/** 事實欄位的內容資訊：題目文字、在目前內容中的 [位置, 事實數]（內容移除或改字之後顯示、自動搬移用） */
export interface FactMeta {
  label?: string
  pos?: [number, number]
}

// ───────── 事實 id 的對照（舊格式以 index 對齊的事實，用目前內容的第 i 個事實 id 遷移）─────────

/** 題目事實的純文字（存進 factQ，內容移除這個事實後仍可顯示） */
export const factLabelOf = (html: string) => htmlToText(html).replace(/\s+/g, ' ').trim().slice(0, MAX_FACT_LABEL)

setFactCatalog((id) => {
  const ex = getIndex().exerciseById.get(id)
  if (!ex || ex.type !== 'open' || !Array.isArray(ex.factIds)) return undefined
  return { ids: ex.factIds, labels: ex.factsHtml.map(factLabelOf) }
})

// ───────── 開機：讀 localStorage ─────────

const isRec = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** dirty 紀錄（v3 或舊的 v2：f0、f1… 換成 f:<id>） */
function parseDirty(raw: unknown): AnswerDirty {
  const dirty: AnswerDirty = {}
  if (!isRec(raw)) return dirty
  for (const [id, fields] of Object.entries(raw)) {
    if (!isRec(fields)) continue
    for (const [f0, v] of Object.entries(fields)) {
      const f = resolveField(id, f0)
      if (!f || !isRec(v)) continue
      const base = Number(v.base)
      const rec = (dirty[id] ??= {})
      const prev = rec[f]
      rec[f] = { base: Math.min(prev?.base ?? Infinity, Number.isFinite(base) ? base : 0), ...(v.archive === true || prev?.archive ? { archive: true as const } : {}) }
    }
  }
  return dirty
}

interface InferPending {
  /** true：所有題目都要推斷（升級後第一次同步） */
  all: boolean
  ids: string[]
}
function loadInfer(): InferPending {
  const raw = getJSON(KEY_INFER)
  // 沒有標記＝推斷還沒跑過（升級後第一次）：之前的版本把「dirty 有沒有存過」當成標記，flushFinal 每次都會寫，所以會被誤判成已遷移
  if (!isRec(raw) || typeof raw.all !== 'boolean') return { all: true, ids: [] }
  return { all: raw.all, ids: Array.isArray(raw.ids) ? raw.ids.filter((x): x is string => typeof x === 'string') : [] }
}

let answerDirty: AnswerDirty = parseDirty(getJSON(KEY_DIRTY))
let infer: InferPending = loadInfer()
const persistInfer = () => setJSON(KEY_INFER, infer)

let state: State = {
  progress: normalizeProgress(getJSON(KEY_PROGRESS)),
  answers: normalizeAnswers(getJSON(KEY_ANSWERS)),
  // 靜態版先假設會連雲端（等 claude.use 回答，最多 10 秒），連不上再降級成「僅存本機」
  sync: { mode: IS_STATIC ? 'cloud' : 'server', status: 'connecting', gate: 'syncing', notice: null, lastAt: null },
  isLocalClient: IS_STATIC ? false : null,
  remoteNotice: {},
  importUndo: (() => {
    const u = getJSON(KEY_UNDO) as { at?: number; count?: number } | null
    return u && typeof u.at === 'number' ? { at: u.at, count: Number(u.count) || 0 } : null
  })(),
}

const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}
export const getState = () => state
export function useStore<T>(selector: (s: State) => T): T {
  return useSyncExternalStore(subscribe, () => selector(state))
}
export const selectProgress = (s: State) => s.progress
export const selectAnswers = (s: State) => s.answers
export const selectSync = (s: State) => s.sync
export const selectGate = (s: State) => s.sync.gate
export const selectIsLocal = (s: State) => s.isLocalClient
export const selectRemoteNotice = (s: State) => s.remoteNotice
export const selectImportUndo = (s: State) => s.importUndo

export function setSync(patch: Partial<SyncState>) {
  state = { ...state, sync: { ...state.sync, ...patch } }
  emit()
}
export const dismissNotice = () => setSync({ notice: null })

if (typeof window !== 'undefined') {
  // 首次同步前編輯唯讀；等太久（5 秒）就允許離線編輯，連線後欄位級合併，不會清掉遠端的其他欄位
  setTimeout(() => {
    if (state.sync.gate === 'syncing') setSync({ gate: 'offline-edit' })
  }, GATE_TIMEOUT_MS)
}

export function clearRemoteNotice(id: string) {
  if (!(id in state.remoteNotice)) return
  const next = { ...state.remoteNotice }
  delete next[id]
  state = { ...state, remoteNotice: next }
  emit()
}

// ───────── 變動通知（雲端存檔用）─────────

const changeListeners = new Set<(c: DirtyKeys) => void>()
/** 本地發生變動時通知；只含使用者造成的變動，不含從遠端合併進來的內容 */
export function subscribeChanges(l: (c: DirtyKeys) => void) {
  changeListeners.add(l)
  return () => changeListeners.delete(l)
}

// ───────── 持久化 ─────────

const serverDirty = newDirty() // 進度（progress）還沒送出的 entry；口徑用 answerDirty（欄位級）
let saveTimer: ReturnType<typeof setTimeout> | undefined
let pushTimer: ReturnType<typeof setTimeout> | undefined
let pushRetryTimer: ReturnType<typeof setTimeout> | undefined
let pullRetryTimer: ReturnType<typeof setTimeout> | undefined

/** 寫 localStorage；兩份都寫成功才回傳 true */
function saveLocal(): boolean {
  clearTimeout(saveTimer)
  saveTimer = undefined
  const a = setJSON(KEY_PROGRESS, state.progress)
  const b = setJSON(KEY_ANSWERS, state.answers)
  return a && b
}
const persistDirty = () => setJSON(KEY_DIRTY, answerDirty)

function schedulePush() {
  if (IS_STATIC) return
  clearTimeout(pushTimer)
  pushTimer = setTimeout(() => void push(), 1000)
}

/** 套用本地變動：以 entry 的物件參照找出變動的 key，記到 dirty 並通知同步後端 */
function applyLocal(next: { progress?: ProgressData; answers?: AnswersData }) {
  const prev = state
  state = { ...state, progress: next.progress ?? state.progress, answers: next.answers ?? state.answers }
  const changed = diffByIdentity(prev, state)
  if (!isDirtyEmpty(changed)) {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(saveLocal, 300)
    if (!IS_STATIC) {
      for (const k of ['units', 'exercises', 'plan', 'mock'] as const) for (const v of changed[k]) serverDirty[k].add(v)
      schedulePush()
    }
    for (const l of changeListeners) l(changed)
  }
  emit()
}

function setProgress(fn: (p: ProgressData) => ProgressData) {
  applyLocal({ progress: { ...fn(state.progress), updatedAt: Date.now() } })
}
function setAnswers(fn: (a: AnswersData) => AnswersData) {
  applyLocal({ answers: { ...fn(state.answers), updatedAt: Date.now() } })
}

// ───────── 本分頁「最近一次得知 server 的新版本」（判斷快照是否過期）─────────

let learnSeq = 0
/** 題目 id → 最後一次在 pull 以外得知 server 版本（push 的 ack、其他分頁合併）時的序號 */
const learnedAt = new Map<string, number>()
const noteLearned = (id: string) => learnedAt.set(id, ++learnSeq)

// ───────── 舊版 localStorage（v1、v2）的合併 ─────────

/**
 * 把舊版頁面留在 localStorage 的內容合併進來（欄位級，輸掉的進 history），存成新格式後刪掉舊 key（存失敗就留著，下次再合併）。
 * 開機時一定跑一次；舊分頁還開著、又寫了舊 key 時（storage 事件）也會再跑。
 * 合併進來而改變的題目要再推斷一次哪些欄位還沒送出（和 server 比時間戳記）。回傳有變動的題目。
 */
function absorbLegacy(): Set<string> {
  const found: string[] = []
  const changed = new Set<string>()
  let answers = state.answers
  let progress = state.progress
  for (const key of LEGACY_ANSWERS) {
    const raw = getJSON(key)
    if (raw === null) continue
    found.push(key)
    const merged = mergeAnswers(answers, normalizeAnswers(raw))
    for (const id of Object.keys(merged.answers)) if (merged.answers[id] !== answers.answers[id]) changed.add(id)
    answers = merged
  }
  for (const key of LEGACY_PROGRESS) {
    const raw = getJSON(key)
    if (raw === null) continue
    found.push(key)
    progress = mergeProgress(progress, normalizeProgress(raw))
  }
  const legacyDirty = getJSON(LEGACY_DIRTY)
  if (legacyDirty !== null) {
    found.push(LEGACY_DIRTY)
    for (const [id, fields] of Object.entries(parseDirty(legacyDirty))) for (const [f, v] of Object.entries(fields)) ((answerDirty[id] ??= {})[f] ??= v)
  }
  if (!found.length) return changed
  if (changed.size) {
    infer = { all: infer.all, ids: [...new Set([...infer.ids, ...changed])] }
    persistInfer()
  }
  state = { ...state, answers, progress }
  const ok = saveLocal() && persistDirty()
  if (ok) for (const k of found) removeItem(k)
  return changed
}
absorbLegacy()

// ───────── 口徑的欄位與 dirty ─────────

function markDirty(id: string, field: string, base: number, archive?: boolean) {
  if (IS_STATIC) return
  const rec = (answerDirty[id] ??= {})
  const cur = rec[field]
  // base＝這個欄位最後一次從 server 看到的版本；已經是 dirty 就沿用最早的 base
  rec[field] = { base: cur ? Math.min(cur.base, base) : base, ...(archive || cur?.archive ? { archive: true as const } : {}) }
  persistDirty()
  schedulePush()
}

function withFact(e: AnswerEntry, key: string, value: string, at: number, meta?: FactMeta): AnswerEntry {
  const out: AnswerEntry = { ...e, facts: { ...e.facts, [key]: value }, factsAt: { ...e.factsAt, [key]: at } }
  if (meta?.label) out.factQ = { ...(e.factQ ?? {}), [key]: meta.label }
  if (meta?.pos) out.factPos = { ...(e.factPos ?? {}), [key]: meta.pos }
  return out
}

/** 目前內容中某題某個事實的資訊（題目文字、位置） */
function factMetaOf(exerciseId: string, factId: string): FactMeta | undefined {
  const ex = getIndex().exerciseById.get(exerciseId)
  if (!ex || ex.type !== 'open' || !Array.isArray(ex.factIds)) return undefined
  const i = ex.factIds.indexOf(factId)
  return i < 0 ? undefined : { label: factLabelOf(ex.factsHtml[i] ?? ''), pos: [i, ex.factIds.length] }
}

/**
 * 對某題的某個文字欄位（'text' 或 `f:<事實 id>`）做一次本地編輯：蓋上（clamp 過的）時間戳記、記下 dirty、更新 state。
 * base：輸入框開始編輯時依據的版本。如果 store 現在的內容和它不同（編輯中別台更新了這個欄位，使用者沒看過），
 * 那個版本先放進 history，patch 也帶 archive（server 一定封存它現有的版本），兩個版本都不會遺失。
 * 回傳寫入後的版本（輸入框把它當成新的 base）。
 */
export function saveField(id: string, field: AnswerField, value: string, base?: FieldBase, meta?: FactMeta): FieldBase | null {
  if (field !== 'text' && !isFactField(field)) return null
  const cur = state.answers.answers[id] ?? emptyEntry()
  const curV = fieldText(cur, field)
  const curAt = fieldAt(cur, field)
  if (curV === value) return { v: curV, at: curAt }
  const at = nextStamp(curAt)
  const unseen = base !== undefined && curV !== base.v && curV.trim() !== ''
  markDirty(id, field, base ? Math.min(curAt, base.at) : curAt, unseen)
  const next = field === 'text' ? { ...cur, text: value, textAt: at, textBy: getDevice().label } : withFact(cur, factKeyOf(field), value, at, meta)
  if (unseen) next.history = addHistory(cur.history, { field, text: curV, at: curAt, replacedAt: at, device: field === 'text' ? (cur.textBy ?? '') : '' })
  if (stableJSON(next.history) !== stableJSON(cur.history)) markDirty(id, 'history', 0)
  next.updatedAt = Math.max(entryUpdatedAt(next), cur.updatedAt)
  setAnswers((a) => ({ ...a, answers: { ...a.answers, [id]: next } }))
  return { v: value, at }
}

export const saveAnswerText = (id: string, text: string, base?: FieldBase) => saveField(id, 'text', text, base)
export const saveFact = (id: string, factId: string, value: string, base?: FieldBase, meta?: FactMeta) => saveField(id, factField(factId), value, base, meta)

/**
 * 把某個事實的答案搬到另一個事實（內容改了事實的文字、或使用者手動「移到目前事實」）：
 * 目標原本有內容就先進 history；來源清空，它的值也留在 history。兩個欄位都標成 dirty，所以會同步到 server／雲端。
 */
export function moveFact(id: string, fromId: string, toId: string, meta: FactMeta | undefined = factMetaOf(id, toId)): boolean {
  const cur = state.answers.answers[id]
  if (!cur || fromId === toId) return false
  const from = factField(fromId)
  const to = factField(toId)
  const v = fieldText(cur, from)
  if (!v.trim()) return false
  const fromAt = fieldAt(cur, from)
  const toAt = fieldAt(cur, to)
  const toV = fieldText(cur, to)
  const at = nextStamp(Math.max(fromAt, toAt))
  const lost: HistoryItem[] = [{ field: from, text: v, at: fromAt, replacedAt: at, device: '' }]
  if (toV.trim() && toV !== v) lost.push({ field: to, text: toV, at: toAt, replacedAt: at, device: '' })
  markDirty(id, to, toAt, toV.trim() !== '' && toV !== v)
  markDirty(id, from, fromAt, true)
  markDirty(id, 'history', 0)
  const next = withFact(withFact(cur, fromId, '', at), toId, v, at, meta)
  next.history = addHistory(cur.history, ...lost)
  next.updatedAt = Math.max(entryUpdatedAt(next), cur.updatedAt)
  setAnswers((a) => ({ ...a, answers: { ...a.answers, [id]: next } }))
  return true
}

/** 內容改了事實的文字（id 變了）：能確定的答案自動搬到同位置的新事實（規則見 data.ts planFactMoves）。回傳搬了幾個 */
export function relinkFacts(): number {
  const idx = getIndex()
  let moved = 0
  for (const [id, e] of Object.entries(state.answers.answers)) {
    const ex = idx.exerciseById.get(id)
    if (!ex || ex.type !== 'open' || !Array.isArray(ex.factIds) || !ex.factIds.length) continue
    for (const m of planFactMoves(e, ex.factIds)) if (moveFact(id, m.from, m.to)) moved++
  }
  return moved
}

// dev 熱更新換了內容：同步過之後才搬（避免用過期的本地資料搬）
subscribeContent(() => {
  if (state.sync.gate !== 'syncing') relinkFacts()
})

export function saveGrade(id: string, grade: StoredGrade) {
  const cur = state.answers.answers[id] ?? emptyEntry()
  const at = nextStamp(cur.gradeAt)
  markDirty(id, 'grade', cur.gradeAt)
  const next = { ...cur, grade, gradeAt: at }
  next.updatedAt = Math.max(entryUpdatedAt(next), cur.updatedAt)
  setAnswers((a) => ({ ...a, answers: { ...a.answers, [id]: next } }))
}

/** 還原歷史版本：目前的內容會被放進 history（不會因為還原而遺失） */
export function restoreHistory(id: string, item: HistoryItem) {
  const field = item.field
  if (field !== 'text' && !isFactField(field)) return
  const cur = state.answers.answers[id] ?? emptyEntry()
  const curV = fieldText(cur, field)
  const curAt = fieldAt(cur, field)
  const at = nextStamp(curAt)
  markDirty(id, field, curAt, true)
  const next = field === 'text' ? { ...cur, text: item.text, textAt: at, textBy: getDevice().label } : withFact(cur, factKeyOf(field), item.text, at)
  next.history = addHistory(cur.history, ...(curV.trim() && curV !== item.text ? [{ field, text: curV, at: curAt, replacedAt: at, device: field === 'text' ? (cur.textBy ?? '') : '' }] : []))
  if (stableJSON(next.history) !== stableJSON(cur.history)) markDirty(id, 'history', 0)
  next.updatedAt = Math.max(entryUpdatedAt(next), cur.updatedAt)
  setAnswers((a) => ({ ...a, answers: { ...a.answers, [id]: next } }))
}

// ───────── 遠端內容合併（共用）─────────

/** 第一次同步成功之前不提示（那是把整份資料載進來，不是「另一台剛剛改了」） */
let noticesEnabled = false

/** 比較合併前後，找出「被遠端改掉文字」的題目，讓 UI 提示（不直接換掉正在編輯的內容） */
function noteRemoteChanges(before: Record<string, AnswerEntry>, after: Record<string, AnswerEntry>, extra: Record<string, boolean> = {}) {
  if (!noticesEnabled) return
  let notices = state.remoteNotice
  for (const [id, a] of Object.entries(after)) {
    const b = before[id] ?? emptyEntry()
    if (a === b) continue
    const textChanged = a.text !== b.text
    const factChanged = Object.keys(a.facts).some((k) => a.facts[k] !== (b.facts[k] ?? ''))
    if (textChanged || factChanged || extra[id]) notices = { ...notices, [id]: Date.now() }
  }
  if (notices !== state.remoteNotice) state = { ...state, remoteNotice: notices }
}

/**
 * 把遠端（雲端存檔）的資料用時間戳記欄位級合併進來。不會把遠端內容當成「本地變動」；
 * 回傳「本地比遠端新（或遠端沒有）」的 entry，呼叫端要把它們補送回去。
 */
export function mergeRemote(remoteP: ProgressData, remoteA: AnswersData): DirtyKeys {
  const diff = diffNewer(state, { progress: remoteP, answers: remoteA })
  const answers = mergeAnswers(state.answers, remoteA)
  noteRemoteChanges(state.answers.answers, answers.answers)
  noticesEnabled = true
  state = { ...state, progress: mergeProgress(state.progress, remoteP), answers }
  saveLocal()
  emit()
  return diff
}

// ───────── 新鮮度 ─────────

let freshnessHook: ((id?: string) => Promise<void>) | null = null
let lastFresh = 0
/** 雲端存檔註冊自己的 pull（靜態版） */
export function registerFreshness(fn: (id?: string) => Promise<void>) {
  freshnessHook = fn
}

/** 讀取最新資料。id 是要編輯／評分的那一題（只讀那題，比較便宜）；force 略過 300ms 的合併 */
export async function pullFresh(id?: string, force = false): Promise<void> {
  // 同時觸發的 focus／pageshow／visibilitychange 合併成一次（各自的 pull 也會合併進行中的請求）
  if (!force && Date.now() - lastFresh < 300) return
  lastFresh = Date.now()
  try {
    if (IS_STATIC) await freshnessHook?.(id)
    else await pull()
  } catch {
    /* 失敗已由各自的狀態處理；編輯與評分不因此被擋住 */
  }
}

let freshnessStarted = false
/** 前景每 20 秒 pull；focus／pageshow／切回前景立即 pull */
export function startFreshness() {
  if (freshnessStarted || typeof window === 'undefined') return
  freshnessStarted = true
  setInterval(() => {
    if (document.visibilityState === 'visible') void pullFresh(undefined, true)
  }, PULL_INTERVAL_MS)
  const now = () => void pullFresh()
  window.addEventListener('focus', now)
  window.addEventListener('pageshow', now)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') now()
  })
}

// ───────── 多分頁：合併其他分頁的變更，不用舊狀態整份覆寫 ─────────

if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => {
    if (e.key === KEY_ANSWERS || e.key === KEY_PROGRESS) {
      const answers = mergeAnswers(state.answers, normalizeAnswers(getJSON(KEY_ANSWERS)))
      const progress = mergeProgress(state.progress, normalizeProgress(getJSON(KEY_PROGRESS)))
      for (const id of Object.keys(answers.answers)) if (answers.answers[id] !== state.answers.answers[id]) noteLearned(id)
      noteRemoteChanges(state.answers.answers, answers.answers)
      state = { ...state, answers, progress }
      emit()
    } else if (e.key === KEY_DIRTY) {
      // 其他分頁新增的 dirty 也要由這個分頁（任何一個分頁）送出
      for (const [id, fields] of Object.entries(parseDirty(getJSON(KEY_DIRTY)))) for (const [f, v] of Object.entries(fields)) ((answerDirty[id] ??= {})[f] ??= v)
    } else if (e.key && (LEGACY_ANSWERS.includes(e.key) || LEGACY_PROGRESS.includes(e.key) || e.key === LEGACY_DIRTY) && e.newValue !== null) {
      // 舊版分頁還開著、又寫了舊格式：合併進來（靜態版通知雲端存檔；本機版下一次 pull 推斷要補送的欄位）
      const before = { progress: state.progress, answers: state.answers }
      const changed = absorbLegacy()
      const diff = diffByIdentity(before, state)
      if (!isDirtyEmpty(diff)) for (const l of changeListeners) l(diff)
      emit()
      if (changed.size && !IS_STATIC) void pullFresh(undefined, true)
    }
  })
}

// ───────── 本機 server 同步 ─────────

class HttpStatusError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
  }
}

async function request(url: string, init: RequestInit = {}): Promise<unknown> {
  let r: Response
  try {
    r = await fetch(url, { ...init, headers: { ...(init.headers as Record<string, string> | undefined), ...lanHeaders() } })
  } catch {
    throw new HttpStatusError('連不到本機 server', 0)
  }
  if (!r.ok) {
    let msg = `HTTP ${r.status}`
    try {
      const j = (await r.json()) as { error?: string }
      if (j.error) msg = j.error
    } catch {
      /* 非 JSON */
    }
    throw new HttpStatusError(msg, r.status)
  }
  return r.json()
}

const utf8 = (s: string) => new TextEncoder().encode(s).length

const putJson = (url: string, body: string, keepalive = false) =>
  request(url, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body, keepalive })

async function getJson(url: string, ms = 4000): Promise<unknown> {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), ms)
  try {
    return await request(url, { signal: ctrl.signal, cache: 'no-store' })
  } finally {
    clearTimeout(t)
  }
}

function syncFailure(e: unknown, which: 'push' | 'pull') {
  const status = e instanceof HttpStatusError ? e.status : 0
  const message = e instanceof Error ? e.message : String(e)
  if (state.sync.gate === 'syncing') setSync({ gate: 'offline-edit' })
  if (status === 401 || status === 426 || status === 413) {
    // 重試也不會成功：直接告訴使用者（資料仍保存在這台裝置）
    const why = status === 413 ? '同步失敗：這次要送出的資料超過 server 上限（413）。資料仍保存在這台裝置；請到「我的口徑」匯出備份。' : message
    setSync({ status: 'error', notice: why })
    return
  }
  setSync({ status: status === 0 ? 'offline' : 'error', notice: status === 0 ? null : `同步失敗：${message}` })
  // push 與 pull 各有自己的重試計時器，彼此不會互相清掉
  if (which === 'push') {
    clearTimeout(pushRetryTimer)
    pushRetryTimer = setTimeout(() => void push(), 15_000)
  } else {
    clearTimeout(pullRetryTimer)
    pullRetryTimer = setTimeout(() => void pull(), 15_000)
  }
}

export const exerciseLabel = (id: string) => {
  const ex = getIndex().exerciseById.get(id)
  const t = ex ? htmlToText(ex.promptHtml || ('frontHtml' in ex ? ex.frontHtml : '')).replace(/\s+/g, ' ').slice(0, 30) : ''
  return t ? `「${t}…」（${id}）` : id
}

/** 歷史版本隨 patch 送出時限制總大小（新的優先；舊的丟掉只是不同步到 server，本地仍保留） */
function trimHistoryBytes(history: HistoryItem[], budget = 600_000): HistoryItem[] {
  const out: HistoryItem[] = []
  let used = 0
  for (const h of history) {
    const n = utf8(h.text) + 200
    if (used + n > budget) continue
    used += n
    out.push(h)
  }
  return out
}

function buildPatch(entry: AnswerEntry, fields: AnswerDirty[string]): AnswerPatch {
  const patch: AnswerPatch = { device: getDevice().label }
  for (const [f, meta] of Object.entries(fields)) {
    const base = { base: meta.base, ...(meta.archive ? { archive: true as const } : {}) }
    if (f === 'text') patch.text = { v: entry.text, ...base }
    else if (f === 'grade' && entry.grade) patch.grade = { v: entry.grade, ...base }
    else if (f === 'history') patch.history = trimHistoryBytes(entry.history)
    else if (isFactField(f)) {
      const key = factKeyOf(f)
      const q = entry.factQ?.[key]
      const pos = entry.factPos?.[key]
      ;(patch.facts ??= {})[key] = { v: fieldText(entry, f), ...base, ...(q ? { q } : {}), ...(pos ? { pos } : {}) }
    }
  }
  return patch
}

interface PendingPatch {
  id: string
  patch: AnswerPatch
  fields: AnswerDirty[string]
  sent: Record<string, unknown>
  bytes: number
}

/** 目前所有還沒送出的口徑欄位 → patch（超過上限的單筆另外回報） */
function collectPatches(): { items: PendingPatch[]; oversize: string[] } {
  const items: PendingPatch[] = []
  const oversize: string[] = []
  for (const id of Object.keys(answerDirty)) {
    const fields = answerDirty[id]
    const entry = state.answers.answers[id]
    if (!entry || Object.keys(fields).length === 0) {
      delete answerDirty[id]
      continue
    }
    const copy = { ...fields }
    const patch = buildPatch(entry, copy)
    const bytes = utf8(JSON.stringify(patch))
    if (bytes > MAX_ENTRY_BYTES) {
      oversize.push(id)
      continue
    }
    items.push({ id, patch, fields: copy, sent: Object.fromEntries(Object.keys(copy).map((f) => [f, fieldValue(entry, f)])), bytes })
  }
  return { items, oversize }
}

/** 依大小分批，每批不超過 BATCH_BYTES */
function batchBySize(items: PendingPatch[]): PendingPatch[][] {
  const batches: PendingPatch[][] = []
  let cur: PendingPatch[] = []
  let size = 0
  for (const it of items) {
    if (cur.length && size + it.bytes > BATCH_BYTES) {
      batches.push(cur)
      cur = []
      size = 0
    }
    cur.push(it)
    size += it.bytes
  }
  if (cur.length) batches.push(cur)
  return batches
}

/**
 * server 回應後：已送出且沒再改的欄位不再是 dirty，改用 server 蓋好時間的版本；送出後又改過的欄位，base 更新成 server 剛給的版本。
 * 其他欄位逐欄位比較時間戳記（回應裡的其他欄位可能比本地已經拿到的舊）。
 */
function ackBatch(batch: PendingPatch[], resp: unknown) {
  const srv = normalizeAnswers(resp).answers
  const nextAnswers = { ...state.answers.answers }
  const before = state.answers.answers
  for (const it of batch) {
    const s = srv[it.id]
    if (!s) continue
    const local = nextAnswers[it.id]
    const rec = answerDirty[it.id] ?? {}
    const acked: string[] = []
    for (const f of Object.keys(it.fields)) {
      if (stableJSON(fieldValue(local, f)) === stableJSON(it.sent[f])) {
        delete rec[f]
        acked.push(f)
      } else if (rec[f]) rec[f] = { base: fieldAt(s, f), ...(rec[f].archive ? { archive: true as const } : {}) }
    }
    if (Object.keys(rec).length === 0) delete answerDirty[it.id]
    else answerDirty[it.id] = rec
    nextAnswers[it.id] = adoptServerEntry(local, s, { dirty: rec, force: acked })
    noteLearned(it.id)
  }
  noteRemoteChanges(before, nextAnswers)
  state = { ...state, answers: { ...state.answers, answers: nextAnswers } }
  persistDirty()
}

let pushing = false
let pushQueued = false

async function push() {
  if (IS_STATIC) return
  clearTimeout(pushTimer)
  if (pushing) {
    pushQueued = true
    return
  }
  const progressSending = newDirty()
  addDirty(progressSending, serverDirty)
  for (const k of Object.keys(serverDirty) as Array<keyof DirtyKeys>) serverDirty[k].clear()
  const hasProgress = progressSending.units.size + progressSending.exercises.size + progressSending.plan.size + progressSending.mock.size > 0
  const { items, oversize } = collectPatches()
  if (!hasProgress && items.length === 0 && oversize.length === 0) return
  pushing = true
  setSync({ status: 'syncing' })
  try {
    if (hasProgress) {
      const merged = normalizeProgress(await putJson('/api/progress', JSON.stringify(progressDelta(state.progress, progressSending))))
      state = { ...state, progress: mergeProgress(state.progress, merged) }
    }
    for (const batch of batchBySize(items)) {
      const body = JSON.stringify({ version: DATA_VERSION, patches: Object.fromEntries(batch.map((b) => [b.id, b.patch])) })
      ackBatch(batch, await putJson('/api/answers', body))
    }
    saveLocal()
    const tooBig = oversize.length ? `這幾題的回答太大，無法同步到本機 server（單題上限約 ${Math.round(MAX_ENTRY_BYTES / 1_000_000)} MB）：${oversize.map(exerciseLabel).join('、')}。請縮短內容；資料仍保存在這台裝置。` : null
    setSync({ status: tooBig ? 'error' : 'ok', lastAt: Date.now(), notice: tooBig, gate: 'ready' })
  } catch (e) {
    addDirty(serverDirty, progressSending)
    syncFailure(e, 'push')
  } finally {
    pushing = false
    if (pushQueued) {
      pushQueued = false
      schedulePush()
    }
  }
}

const nonEmpty = (v: unknown) => (typeof v === 'string' ? v.trim() !== '' : v !== undefined)

/**
 * 升級前的本地資料沒有可靠的 dirty 紀錄：用時間戳記推斷一次。本地比 server 新的欄位當成還沒送出（archive：server 現有的版本進 history），
 * 本地較舊的版本合併進 history（一起送出）。回傳合併後的本地 entry。
 */
function inferDirty(id: string, local: AnswerEntry, srv: AnswerEntry | undefined, now: number): AnswerEntry {
  const add = (f: string, meta: { base: number; archive?: true }) => ((answerDirty[id] ??= {})[f] ??= meta)
  if (!srv) {
    for (const f of entryFields(local)) if (nonEmpty(fieldValue(local, f))) add(f, { base: 0 })
    if (local.history.length) add('history', { base: 0 })
    return local
  }
  const merged = mergeEntry(srv, local, now)
  for (const f of entryFields(merged)) {
    if (stableJSON(fieldValue(merged, f)) !== stableJSON(fieldValue(srv, f))) add(f, { base: fieldAt(srv, f), archive: true })
  }
  if (stableJSON(merged.history) !== stableJSON(srv.history)) add('history', { base: 0 })
  return merged
}

function applyServerSnapshot(remoteP: ProgressData, remoteA: AnswersData, pullSeq: number) {
  const now = Date.now()
  // progress：對稱合併；本地比較新的 entry 補送回去
  const diff = diffNewer({ progress: state.progress, answers: emptyAnswers() }, { progress: remoteP, answers: emptyAnswers() })
  for (const k of ['units', 'exercises', 'plan', 'mock'] as const) for (const v of diff[k]) serverDirty[k].add(v)
  const progress = mergeProgress(state.progress, remoteP)
  // answers：以 server 為準，但還沒送出的欄位（dirty）與比快照新的欄位保留本地版本
  const before = state.answers.answers
  const next = { ...before }
  const conflicts: Record<string, boolean> = {}
  const inferIds = new Set(infer.ids)
  for (const [id, srv] of Object.entries(remoteA.answers)) {
    let local = before[id]
    if (local && (infer.all || inferIds.has(id))) local = inferDirty(id, local, srv, now)
    if (local && (learnedAt.get(id) ?? 0) <= pullSeq) {
      // 本地比快照新、又沒有標成 dirty 的欄位：這份快照不是「本分頁拿到更新版本之前發出的過期快照」，
      // 所以是 server 的資料倒退了（例如資料檔被還原）→ 補送回去，server 現有的內容進 history
      for (const f of localNewerFields(local, srv, answerDirty[id])) ((answerDirty[id] ??= {})[f] = { base: fieldAt(srv, f), archive: true })
    }
    const rec = answerDirty[id]
    const adopted = adoptServerEntry(local, srv, { dirty: rec })
    // 本地有未送出的 text／事實，server 又有不同的新版本 → 提示（兩個版本都會留著：server 的進 history）
    if (local && rec) {
      for (const f of Object.keys(rec)) if (f !== 'history' && f !== 'grade' && stableJSON(fieldValue(local, f)) !== stableJSON(fieldValue(srv, f)) && fieldAt(srv, f) > rec[f].base) conflicts[id] = true
    }
    if (adopted !== before[id]) next[id] = adopted
    if (local && stableJSON(adopted.history) !== stableJSON(srv.history)) ((answerDirty[id] ??= {}).history ??= { base: 0 })
  }
  // server 沒有的題目：本地的內容全部要送上去
  for (const [id, local] of Object.entries(before)) {
    if (!remoteA.answers[id]) inferDirty(id, local, undefined, now)
  }
  // 推斷跑過了才寫「已推斷」標記（M1：不能用其他會被寫入的 key 當標記）
  if (infer.all || infer.ids.length) {
    infer = { all: false, ids: [] }
    persistInfer()
  }
  noteRemoteChanges(before, next, conflicts)
  noticesEnabled = true
  state = { ...state, progress, answers: { ...state.answers, updatedAt: Math.max(state.answers.updatedAt, remoteA.updatedAt), answers: next } }
  persistDirty()
  saveLocal()
  emit()
  relinkFacts() // 剛同步完（資料是新的）：內容改字的事實答案自動搬到新的事實
}

let pulling: Promise<void> | null = null

/** 從 server 拉最新資料並合併（開機、切回前景、每 20 秒、編輯前） */
export function pull(): Promise<void> {
  if (IS_STATIC) return Promise.resolve()
  if (!pulling) pulling = doPull().finally(() => (pulling = null))
  return pulling
}

const hasPending = () => !isDirtyEmpty({ ...newDirty(), units: serverDirty.units, exercises: serverDirty.exercises, plan: serverDirty.plan, mock: serverDirty.mock }) || Object.keys(answerDirty).length > 0

async function doPull() {
  const seq = learnSeq // 這次 pull 發出時，本分頁已知的 server 版本
  try {
    const [p, a] = await Promise.all([getJson('/api/progress'), getJson('/api/answers')])
    applyServerSnapshot(normalizeProgress(p), normalizeAnswers(a), seq)
    clearTimeout(pullRetryTimer)
    setSync({ status: 'ok', lastAt: Date.now(), notice: null, gate: 'ready' })
    if (hasPending()) void push()
  } catch (e) {
    syncFailure(e, 'pull')
  }
}

/** 最後一道 flush：寫 localStorage，並把還沒送出的 delta 用 keepalive 送出（超過 keepalive 預算就改用一般請求，盡力而為） */
function flushFinal() {
  saveLocal()
  persistDirty()
  if (IS_STATIC) return
  try {
    const send = (url: string, body: string, budget: number) => {
      // keepalive 的上限看「位元組」，不是字數；answers 與 progress 各自計算預算
      void fetch(url, { method: 'PUT', headers: { 'Content-Type': 'application/json', ...lanHeaders() }, body, keepalive: utf8(body) <= budget }).catch(() => {})
    }
    const hasProgress = serverDirty.units.size + serverDirty.exercises.size + serverDirty.plan.size + serverDirty.mock.size > 0
    if (hasProgress) send('/api/progress', JSON.stringify(progressDelta(state.progress, serverDirty)), KEEPALIVE_PROGRESS_BYTES)
    const { items } = collectPatches()
    if (items.length) {
      const b = batchBySize(items)[0]
      send('/api/answers', JSON.stringify({ version: DATA_VERSION, patches: Object.fromEntries(b.map((x) => [x.id, x.patch])) }), KEEPALIVE_ANSWERS_BYTES)
    }
  } catch {
    /* ignore */
  }
}
onPageHide(flushFinal, 'final')

export async function initStore() {
  if (IS_STATIC) return // 靜態版由 cloud.ts 處理
  try {
    const h = (await getJson('/api/health')) as { local?: boolean; auth?: boolean }
    state = { ...state, isLocalClient: h.local === true }
    emit()
    if (h.local !== true && h.auth === false) {
      setSync({ status: 'error', gate: 'offline-edit', notice: '區網存取需要 token：請用電腦終端機印出的網址（含 ?t=…）重新開啟這個頁面。在那之前，變更只存在這台裝置。' })
      return
    }
  } catch {
    state = { ...state, isLocalClient: false }
    emit()
  }
  startFreshness()
  await pull()
  window.addEventListener('online', () => void push())
}

// ───────── 進度 Actions ─────────

export function setUnitRead(unitId: string, read: boolean) {
  setProgress((p) => ({ ...p, units: { ...p.units, [unitId]: { read, at: Date.now() } } }))
}

export function recordResult(exerciseId: string, result: Result): ExerciseProgress {
  const next = applyResult(state.progress.exercises[exerciseId], result)
  setProgress((p) => ({ ...p, exercises: { ...p.exercises, [exerciseId]: next } }))
  return next
}

/** 答錯之後的重試：只算練習（記錄作答次數），不升箱、不改最後結果、不移出錯題本 */
export function recordPractice(exerciseId: string): ExerciseProgress | undefined {
  const prev = state.progress.exercises[exerciseId]
  if (!prev) return undefined
  const now = Date.now()
  const next: ExerciseProgress = { ...prev, ...bumpDeviceCount(prev, getDevice().id, false, now), at: now }
  setProgress((p) => ({ ...p, exercises: { ...p.exercises, [exerciseId]: next } }))
  return next
}

export function setPlanDone(taskId: string, done: boolean) {
  setProgress((p) => ({ ...p, plan: { ...p.plan, [taskId]: { done, at: Date.now() } } }))
}

export function saveMockSession(session: MockSession) {
  setProgress((p) => ({ ...p, mock: [...p.mock.filter((m) => m.id !== session.id), session] }))
}

// ───────── 匯出入 ─────────

export function exportJson(): string {
  return JSON.stringify(
    { kind: 'mobile-prep', version: DATA_VERSION, exportedAt: new Date().toISOString(), answers: state.answers, progress: state.progress },
    null,
    2,
  )
}

interface UndoSnapshot {
  at: number
  /** 這次匯入前，被動到的題目的口徑（null＝原本沒有） */
  answers: Record<string, AnswerEntry | null>
  progress: { exercises: Record<string, ExerciseProgress>; units: Record<string, { read: boolean; at: number }>; plan: Record<string, { done: boolean; at: number }> }
}

/** 把整個 entry 當成新的編輯寫回（每個欄位都重新蓋時間、標成 dirty）：用於還原匯入／以備份取代 */
function writeEntryAsEdit(id: string, target: AnswerEntry) {
  const cur = state.answers.answers[id] ?? emptyEntry()
  const textAt = nextStamp(cur.textAt)
  const lost: HistoryItem[] = []
  if (cur.text.trim() && cur.text !== target.text) lost.push({ field: 'text', text: cur.text, at: cur.textAt, replacedAt: textAt, device: cur.textBy ?? '' })
  const facts: Record<string, string> = {}
  const factsAt: Record<string, number> = {}
  for (const k of new Set([...Object.keys(cur.facts), ...Object.keys(target.facts)])) {
    const v = Object.prototype.hasOwnProperty.call(target.facts, k) ? target.facts[k] : ''
    const curV = Object.prototype.hasOwnProperty.call(cur.facts, k) ? cur.facts[k] : ''
    const at = nextStamp(cur.factsAt[k] ?? 0)
    if (curV.trim() && curV !== v) lost.push({ field: factField(k), text: curV, at: cur.factsAt[k] ?? 0, replacedAt: at, device: '' })
    facts[k] = v
    factsAt[k] = at
  }
  const next: AnswerEntry = {
    text: target.text,
    textAt,
    textBy: getDevice().label,
    facts,
    factsAt,
    gradeAt: target.grade ? nextStamp(cur.gradeAt) : cur.gradeAt,
    history: addHistory(target.history, ...cur.history, ...lost),
    updatedAt: 0,
  }
  const factQ = { ...(cur.factQ ?? {}), ...(target.factQ ?? {}) }
  if (Object.keys(factQ).length) next.factQ = factQ
  if (target.grade) next.grade = target.grade
  else if (cur.grade) next.grade = cur.grade
  next.updatedAt = entryUpdatedAt(next)
  markDirty(id, 'text', cur.textAt, true)
  for (const k of Object.keys(facts)) markDirty(id, factField(k), cur.factsAt[k] ?? 0, true)
  if (target.grade) markDirty(id, 'grade', cur.gradeAt)
  markDirty(id, 'history', 0)
  setAnswers((a) => ({ ...a, answers: { ...a.answers, [id]: next } }))
}

/** 欄位級合併後，本地有變動的欄位要標成 dirty */
function markDirtyFromDiff(id: string, prev: AnswerEntry | undefined, next: AnswerEntry) {
  const p = prev ?? emptyEntry()
  for (const f of entryFields(next)) {
    if (stableJSON(fieldValue(next, f)) !== stableJSON(fieldValue(p, f))) markDirty(id, f, fieldAt(p, f))
  }
  if (stableJSON(next.history) !== stableJSON(p.history)) markDirty(id, 'history', 0)
}

function saveUndo(snapshot: UndoSnapshot) {
  setJSON(KEY_UNDO, snapshot)
  state = { ...state, importUndo: { at: snapshot.at, count: Object.keys(snapshot.answers).length } }
}

/**
 * 匯入備份。
 *  - merge：欄位級合併，只採用比現有新的欄位、現有缺的補上；輸掉的內容進 history（不會「一律勝出」）。
 *  - replace：以備份完全取代（備份裡的內容當成新的編輯；現有的內容進 history，不會遺失）。
 * 匯入前自動存一份快照，可用 undoImport() 復原。
 */
export function importBackup(text: string, mode: 'merge' | 'replace' = 'merge'): { ok: boolean; message: string } {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    return { ok: false, message: '不是有效的 JSON，請確認貼上的內容完整' }
  }
  if (typeof raw !== 'object' || raw === null) return { ok: false, message: 'JSON 格式不符' }
  const o = raw as Record<string, unknown>
  const inner = o.answers as Record<string, unknown> | undefined
  // 支援：完整匯出 {answers:{answers:{…}}, progress}、my-answers.json {answers:{…}}、直接是 {題目id: 回答}（新舊格式都可以）
  let incoming: AnswersData
  let incomingProgress: ProgressData | null = null
  if (inner && typeof inner === 'object' && typeof inner.answers === 'object') {
    incoming = normalizeAnswers(inner)
    if (o.progress) incomingProgress = normalizeProgress(o.progress)
  } else if (inner && typeof inner === 'object') {
    incoming = normalizeAnswers(o)
  } else {
    incoming = normalizeAnswers({ answers: o })
  }
  for (const [k, v] of Object.entries(incoming.answers)) {
    if (!v.text.trim() && !Object.values(v.facts).some((f) => f.trim()) && !v.grade && v.history.length === 0) delete incoming.answers[k]
  }
  const count = Object.keys(incoming.answers).length
  if (count === 0 && !incomingProgress) return { ok: false, message: '沒有找到可匯入的回答或進度' }

  // 先存快照（復原用）
  const snap: UndoSnapshot = { at: Date.now(), answers: {}, progress: { exercises: {}, units: {}, plan: {} } }
  const touched = new Set<string>()
  if (mode === 'replace') {
    for (const id of Object.keys(incoming.answers)) touched.add(id)
    for (const id of Object.keys(state.answers.answers)) touched.add(id)
  } else {
    const merged = mergeAnswers(state.answers, incoming)
    for (const id of Object.keys(merged.answers)) if (merged.answers[id] !== state.answers.answers[id]) touched.add(id)
  }
  for (const id of touched) snap.answers[id] = state.answers.answers[id] ?? null
  if (incomingProgress) {
    for (const k of Object.keys(incomingProgress.exercises)) if (state.progress.exercises[k]) snap.progress.exercises[k] = state.progress.exercises[k]
    for (const k of Object.keys(incomingProgress.units)) if (state.progress.units[k]) snap.progress.units[k] = state.progress.units[k]
    for (const k of Object.keys(incomingProgress.plan)) if (state.progress.plan[k]) snap.progress.plan[k] = state.progress.plan[k]
  }
  saveUndo(snap)

  if (mode === 'replace') {
    for (const id of touched) writeEntryAsEdit(id, incoming.answers[id] ?? emptyEntry())
  } else {
    const before = state.answers.answers
    const merged = mergeAnswers(state.answers, incoming)
    for (const id of touched) markDirtyFromDiff(id, before[id], merged.answers[id])
    setAnswers(() => merged)
  }
  if (incomingProgress) {
    const p = incomingProgress
    if (mode === 'replace') {
      const now = Date.now()
      for (const e of Object.values(p.exercises)) e.at = now
      for (const e of Object.values(p.units)) e.at = now
      for (const e of Object.values(p.plan)) e.at = now
    }
    setProgress((cur) => mergeProgress(cur, p))
  }
  emit()
  return { ok: true, message: `${mode === 'replace' ? '已用備份取代' : '已合併'} ${count} 題回答${incomingProgress ? '與進度' : ''}（可以「復原這次匯入」）` }
}

/** 復原最近一次匯入：把被動到的題目還原成匯入前的內容（當成新的編輯，所以也會同步回 server／雲端；匯入進來的內容留在 history） */
export function undoImport(): { ok: boolean; message: string } {
  const snap = getJSON(KEY_UNDO) as UndoSnapshot | null
  if (!snap || typeof snap.at !== 'number') return { ok: false, message: '沒有可以復原的匯入' }
  for (const [id, entry] of Object.entries(snap.answers)) {
    const prev = entry ? normalizeAnswers({ answers: { [id]: entry } }).answers[id] : null
    writeEntryAsEdit(id, prev ?? emptyEntry())
  }
  const now = Date.now()
  const restore = <T extends { at: number }>(rec: Record<string, T>) => Object.fromEntries(Object.entries(rec).map(([k, v]) => [k, { ...v, at: now }]))
  const prog = normalizeProgress({ exercises: snap.progress.exercises, units: snap.progress.units, plan: snap.progress.plan })
  setProgress((p) => ({
    ...p,
    exercises: { ...p.exercises, ...restore(prog.exercises) },
    units: { ...p.units, ...restore(prog.units) },
    plan: { ...p.plan, ...restore(prog.plan) },
  }))
  removeItem(KEY_UNDO)
  state = { ...state, importUndo: null }
  emit()
  return { ok: true, message: `已復原這次匯入（${Object.keys(snap.answers).length} 題）` }
}

/** 相容舊名稱：欄位級合併匯入 */
export const importJson = (text: string) => importBackup(text, 'merge')
