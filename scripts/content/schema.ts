// 內容合約（content/SCHEMA.md）的 zod 版驗證。結構檢查交給 zod，跨欄位規則在 crossCheck*。
import { z } from 'zod'

export const PRIORITIES = ['P0', 'P1', 'P2'] as const
export const TYPES = ['single', 'multi', 'fill', 'predict', 'order', 'code', 'open', 'flash'] as const
export const CODE_LANGS = ['swift', 'kotlin', 'objectivec', 'java', 'ts', 'json', 'text'] as const

/** 這些前綴不能當單元 id 開頭（避免和網址錨點衝突） */
export const RESERVED_ID_PREFIXES = ['sec-', 'settings', 'sidebar', 'customize', 'upgrade', 'downloads', 'comment-']
export const RESERVED_ROUTES = ['home', 'plan', 'practice', 'wrong', 'mock', 'answers']

// YAML 會把 123、true 轉成數字／布林；字串欄位在這裡轉回字串
const toStr = (v: unknown) => (typeof v === 'number' || typeof v === 'boolean' ? String(v) : v)
const Str = z.preprocess(toStr, z.string().trim().min(1, '不可為空字串'))
const OptStr = z.preprocess(toStr, z.string().trim().min(1, '不可為空字串').optional())
const StrList = z.array(Str)
const Slug = z.string().regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, '只能用小寫英數與 -')

export const SectionSchema = z.object({
  id: Slug,
  dir: Str,
  title: Str,
  short: Str,
  description: Str,
})
export const SectionsSchema = z.array(SectionSchema).min(1)

export const FrontmatterSchema = z.object({
  id: Slug,
  title: Str,
  summary: Str,
  priority: z.enum(PRIORITIES),
  day: z.number().int().min(1).max(7).optional(),
  tags: z.array(Str).default([]),
})

const Level = z.number().int().min(1, 'level 需為 1／2／3').max(3, 'level 需為 1／2／3')
const SwiftVer = z.number().refine((v) => v === 5 || v === 6, 'swiftVersion 只能是 5 或 6').transform((v) => v as 5 | 6)

const base = {
  id: Str,
  level: Level,
  priority: z.enum(PRIORITIES),
  tags: z.array(Str).default([]),
  prompt: OptStr,
  explain: OptStr,
  code: z.preprocess(toStr, z.string().min(1).optional()),
  codeLang: z.enum(CODE_LANGS).optional(),
}

const CodeLangBlock = z.object({
  starter: z.string().min(1, '不可為空'),
  solution: z.string().min(1, '不可為空'),
  tests: z.string().min(1, '不可為空'),
  swiftVersion: SwiftVer.optional(),
})

export const ExerciseSchemas = {
  single: z.object({
    ...base,
    type: z.literal('single'),
    options: z.array(Str),
    answer: z.number().int(),
  }),
  multi: z.object({
    ...base,
    type: z.literal('multi'),
    options: z.array(Str),
    answer: z.array(z.number().int()),
  }),
  fill: z.object({
    ...base,
    type: z.literal('fill'),
    blanks: z.array(z.array(Str)),
    caseInsensitive: z.boolean().optional(),
  }),
  predict: z.object({
    ...base,
    type: z.literal('predict'),
    answer: Str,
    acceptAlso: z.array(Str).optional(),
  }),
  order: z.object({
    ...base,
    type: z.literal('order'),
    items: z.array(Str),
  }),
  code: z.object({
    ...base,
    type: z.literal('code'),
    hints: z.array(Str).optional(),
    swift: CodeLangBlock.optional(),
    kotlin: CodeLangBlock.optional(),
    swiftVersion: SwiftVer.optional(),
    timeoutSec: z.number().positive().max(60).optional(),
    /** Live Coding 計時模式（秒），例如 1200 = 20 分鐘 */
    timeLimitSec: z.number().int().positive().max(7200).optional(),
  }),
  open: z.object({
    ...base,
    type: z.literal('open'),
    promptJa: OptStr,
    keyPoints: StrList,
    modelAnswer: Str,
    modelAnswerJa: OptStr,
    followUps: StrList.optional(),
    factsToConfirm: StrList.optional(),
    timeLimitSec: z.number().int().positive().max(1800).optional(),
  }),
  flash: z.object({
    ...base,
    type: z.literal('flash'),
    front: Str,
    back: Str,
  }),
} as const

export type ExerciseSource = {
  [K in keyof typeof ExerciseSchemas]: z.infer<(typeof ExerciseSchemas)[K]>
}[keyof typeof ExerciseSchemas]

export const PlanSchema = z.array(
  z.object({
    day: z.number().int().min(1).max(31),
    date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '日期格式需為 YYYY-MM-DD'),
    title: Str,
    goal: Str,
    tasks: z.array(
      z.object({
        id: Str,
        label: Str,
        links: z.array(Str).optional(),
      }),
    ),
  }),
)

/** zod issue → 中文訊息 */
export function formatIssue(issue: z.core.$ZodIssue): string {
  const path = issue.path
    .map((p) => (typeof p === 'number' ? `[${p}]` : `.${String(p)}`))
    .join('')
    .replace(/^\./, '')
  const where = path ? `${path}：` : ''
  switch (issue.code) {
    case 'invalid_type': {
      const received = issue.message.match(/received (\w+)/)?.[1]
      if (received === 'undefined') return `${where}缺少必填欄位（需要 ${issue.expected}）`
      const hint =
        issue.expected === 'string' && (received === 'object' || received === 'array')
          ? '（YAML 把這項解析成物件／陣列了，常見原因是文字裡有「: 」，請改用引號或 | 區塊）'
          : ''
      return `${where}型別錯誤，需要 ${issue.expected}，實際是 ${received ?? '其他'}${hint}`
    }
    case 'invalid_value':
      return `${where}只能是 ${issue.values.map((v) => JSON.stringify(v)).join(' / ')}`
    default:
      return `${where}${issue.message}`
  }
}

/** 找出不在 schema 裡的欄位（只警告，避免 typo 靜默失效） */
export function unknownKeys(raw: Record<string, unknown>, schema: z.ZodObject): string[] {
  const known = new Set(Object.keys(schema.shape))
  return Object.keys(raw).filter((k) => !known.has(k))
}
