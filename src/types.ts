// content.json 的輸出型別（build-content 產生、前端使用）＋使用者資料型別

export type Priority = 'P0' | 'P1' | 'P2'
export type ExerciseType = 'single' | 'multi' | 'fill' | 'predict' | 'order' | 'code' | 'open' | 'flash'
export type RunLang = 'swift' | 'kotlin'

export interface Section {
  id: string
  title: string
  short: string
  description: string
  unitIds: string[]
}

export interface TocItem {
  id: string
  text: string
  level: 2 | 3
}

export interface Unit {
  id: string
  sectionId: string
  order: number
  title: string
  summary: string
  priority: Priority
  day?: number
  tags: string[]
  html: string
  toc: TocItem[]
  /** 小寫純文字，給前端搜尋用 */
  search: string
  exerciseIds: string[]
  file: string
}

interface ExerciseBase {
  id: string
  unitId: string
  sectionId: string
  level: 1 | 2 | 3
  priority: Priority
  tags: string[]
  /** 小寫純文字，給前端搜尋用 */
  search: string
  promptHtml: string
  explainHtml?: string
  codeLang?: string
  /** 原文 */
  code?: string
  /** 高亮後的 HTML（fill 題以 <i class="blank"></i> 取代 ____） */
  codeHtml?: string
}

export interface SingleExercise extends ExerciseBase {
  type: 'single'
  optionsHtml: string[]
  answer: number
}
export interface MultiExercise extends ExerciseBase {
  type: 'multi'
  optionsHtml: string[]
  answer: number[]
}
export interface FillExercise extends ExerciseBase {
  type: 'fill'
  blanks: string[][]
  caseInsensitive: boolean
}
export interface PredictExercise extends ExerciseBase {
  type: 'predict'
  answer: string
  acceptAlso: string[]
}
export interface OrderExercise extends ExerciseBase {
  type: 'order'
  /** 正確順序 */
  itemsHtml: string[]
}
export interface CodeLangSpec {
  starter: string
  solution: string
  solutionHtml: string
}
export interface CodeExercise extends ExerciseBase {
  type: 'code'
  hintsHtml: string[]
  swift?: CodeLangSpec
  kotlin?: CodeLangSpec
  /** Live Coding 計時（秒） */
  timeLimitSec?: number
}
export interface OpenExercise extends ExerciseBase {
  type: 'open'
  promptJaHtml?: string
  keyPointsHtml: string[]
  modelAnswerHtml: string
  modelAnswerJaHtml?: string
  followUpsHtml: string[]
  factsHtml: string[]
  /** 每個待確認事實的穩定 id（事實原文的 hash，見 data.ts factIdsOf）；口徑以這個 id 對齊，內容增刪事實不會錯位 */
  factIds: string[]
  timeLimitSec: number
}
export interface FlashExercise extends ExerciseBase {
  type: 'flash'
  frontHtml: string
  backHtml: string
}

export type Exercise =
  | SingleExercise
  | MultiExercise
  | FillExercise
  | PredictExercise
  | OrderExercise
  | CodeExercise
  | OpenExercise
  | FlashExercise

export interface PlanTask {
  id: string
  label: string
  links: string[]
}
export interface PlanDay {
  day: number
  date: string
  title: string
  goal: string
  tasks: PlanTask[]
}

export interface ContentIssue {
  level: 'error' | 'warning'
  file: string
  /** 單元 id 或題目 id */
  id?: string
  message: string
}

export interface Content {
  version: 1
  builtAt: string
  strict: boolean
  sections: Section[]
  units: Unit[]
  exercises: Exercise[]
  plan: PlanDay[]
  issues: ContentIssue[]
}

/** 只給本機 server 用（不進前端 bundle） */
export interface ServerData {
  version: 1
  code: Record<
    string,
    {
      timeoutSec: number
      swift?: { tests: string; swiftVersion: 5 | 6 }
      kotlin?: { tests: string }
    }
  >
  open: Record<
    string,
    {
      prompt: string
      promptJa?: string
      keyPoints: string[]
      modelAnswer: string
      followUps: string[]
    }
  >
  /** 口說題的待確認事實：題目 id → [{ id（穩定 id）, q（事實原文）}]。server 遷移舊格式（以 index 對齊）的口徑時用 */
  facts?: Record<string, Array<{ id: string; q: string }>>
}

// ───────── 使用者資料 ─────────

export type Result = 'correct' | 'partial' | 'wrong'

/** [作答次數, 答對次數, 這台裝置最後一次作答的時間（ms，舊資料沒有）] */
export type DeviceCount = [number, number] | [number, number, number]

export interface ExerciseProgress {
  /** 作答次數／答對次數：各裝置各自計數再加總（counts），所以兩台裝置同時作答不會互相蓋掉 */
  attempts: number
  correct: number
  /**
   * deviceId → DeviceCount。裝置超過上限（data.ts COUNTS_CAP）時，最久沒作答的裝置併進 '~others' 加總，
   * 它的 id 記在 folded（合併時用來避免重複計算），不會丟掉次數。
   */
  counts?: Record<string, DeviceCount>
  /** 已經併進 '~others' 的裝置 id */
  folded?: string[]
  last: Result
  /** 最後作答時間（ms） */
  at: number
  /** Leitner 箱 1–5 */
  box: number
  /** 到期日 YYYY-MM-DD（本機時區） */
  due: string
}

export interface MockItem {
  exerciseId: string
  answer: string
  seconds: number
  /** 自評 1–5 */
  selfScore?: number
  aiScore?: number
}
export interface MockSession {
  id: string
  startedAt: number
  finishedAt: number
  updatedAt: number
  items: MockItem[]
}

export interface ProgressData {
  version: number
  updatedAt: number
  units: Record<string, { read: boolean; at: number }>
  exercises: Record<string, ExerciseProgress>
  plan: Record<string, { done: boolean; at: number }>
  mock: MockSession[]
}

export interface GradeResult {
  score: number
  summary: string
  covered: string[]
  missing: string[]
  inaccurate: string[]
  followUps: string[]
  improved: string
}

export interface StoredGrade extends GradeResult {
  at: number
  lang: 'zh' | 'ja'
  /** 評分當下的回答（用來判斷之後有沒有改過） */
  answer: string
}

/**
 * 被取代的舊版本（合併「輸掉」、被別台覆蓋、被還原前的內容）。
 * 保留規則：每個欄位最多 10 筆（依「被取代的時間」replacedAt 新的優先；同欄位同內容只留一筆）；超過的最舊一筆會被淘汰。
 * 另外兩個上限也會淘汰最舊的歷史：雲端文件（256 KiB）、隨 patch 送到本機 server 的歷史（約 600 KB）。目前的內容一律保留。
 */
export interface HistoryItem {
  /** 'text' 或 `f:${factId}`（某個待確認事實） */
  field: string
  text: string
  /** 這個版本寫下的時間 */
  at: number
  /** 被取代的時間（合併、覆蓋當下的時間；淘汰時依這個排序，剛輸掉的離線版本不會先被丟掉） */
  replacedAt: number
  /** 寫下這個版本的裝置（可讀標籤），不明則空字串 */
  device: string
}

/** 欄位級：text、每個事實、grade 各自帶時間戳記（textAt、factsAt[id]、gradeAt），合併時逐欄位取新者 */
export interface AnswerEntry {
  text: string
  textAt: number
  /** 寫下目前 text 的裝置 */
  textBy?: string
  /** 待確認事實：事實 id（OpenExercise.factIds）→ 內容。以 id 對齊，內容增刪事實不會錯位 */
  facts: Record<string, string>
  factsAt: Record<string, number>
  /** 事實 id → 題目事實的文字（寫入當時的內容）；內容移除這個事實後，「我的口徑」頁用它顯示「已移除的題目事實」 */
  factQ?: Record<string, string>
  /** 事實 id → [寫入當時它在內容中的位置, 當時這題的事實數]。內容改了事實的文字（id 變了）時，用來把答案自動搬到同位置的新事實 */
  factPos?: Record<string, [number, number]>
  grade?: StoredGrade
  gradeAt: number
  history: HistoryItem[]
  /** 所有欄位時間戳記的最大值（相容舊格式） */
  updatedAt: number
}

export interface AnswersData {
  version: number
  updatedAt: number
  answers: Record<string, AnswerEntry>
}
