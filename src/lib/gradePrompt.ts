// AI 面試官評分：prompt、輸出格式與結果解析。本機 server（claude CLI）與靜態版（sample.json）共用。
import type { GradeResult } from '../types.ts'

export interface GradeSource {
  prompt: string
  promptJa?: string
  keyPoints: string[]
  modelAnswer: string
  followUps: string[]
}

export const GRADE_SCHEMA = {
  type: 'object',
  properties: {
    score: { type: 'integer', minimum: 1, maximum: 5, description: '1–5 整數' },
    summary: { type: 'string', description: '整體評語，繁體中文，120 字內' },
    covered: { type: 'array', items: { type: 'string' }, description: '回答已涵蓋的要點' },
    missing: { type: 'array', items: { type: 'string' }, description: '缺漏的要點' },
    inaccurate: { type: 'array', items: { type: 'string' }, description: '不精確或錯誤的說法，並說明正確內容' },
    followUps: { type: 'array', items: { type: 'string' }, description: '面試官可能的追問（2–4 題）' },
    improved: { type: 'string', description: '改寫建議：可直接在面試中念出來的版本' },
  },
  required: ['score', 'summary', 'covered', 'missing', 'inaccurate', 'followUps', 'improved'],
  additionalProperties: false,
}

export const GRADE_SYSTEM = `你是一家加密資產交易所的資深 Mobile 技術主管，同時擔任面試官。
你正在面試一位 iOS／Android 雙平台工程師（以下稱「面試者」），應徵 Mobile 工程師職位。
任務：針對面試者對某一題的口頭回答評分並給出可行動的回饋。

評分標準（score 為 1–5 整數）：
5 = 要點齊全、技術正確、結構清楚，可直接在面試中使用
4 = 大致正確且有結構，只缺一兩個次要要點或可更精煉
3 = 方向正確但有明顯缺漏，或太空泛、缺少具體例子
2 = 有重要的技術錯誤，或主要要點大多沒講到
1 = 離題、無法判斷，或幾乎沒有內容

規則：
1. 你只能根據「題目、參考要點、參考答案、面試者的回答、面試者的履歷事實」評分；不得捏造面試者沒說過的經歷、數字或專案細節。
2. improved（改寫建議）必須以面試者自己的回答與履歷事實為素材改寫成可直接念出來的口語版本；若需要只有面試者本人才知道的細節，用「【請補：……】」標示，不要自己編。
3. covered／missing／inaccurate 逐條對照參考要點與技術正確性，每條一句話；沒有就給空陣列。
4. followUps 給 2–4 個面試官最可能的追問。
5. 回饋一律使用繁體中文（台灣用語），技術名詞保留英文；當使用者指定 lang=ja 時，improved 改用自然的日文面接口調（ですます調），其他欄位仍用繁體中文。
6. 簡潔、具體，不要客套。只輸出符合 JSON schema 的結果。`

const list = (items: string[]) => (items.length ? items.map((x, i) => `${i + 1}. ${x}`).join('\n') : '（無）')

/** user prompt。resume 為空字串時標示「未提供」（靜態版不帶履歷檔，避免個資進到靜態版） */
export function buildGradePrompt(q: GradeSource, answer: string, lang: 'zh' | 'ja', resume: string): string {
  return [
    `# lang=${lang}`,
    `## 題目\n${q.prompt.trim()}${q.promptJa ? `\n\n（日文版）\n${q.promptJa.trim()}` : ''}`,
    `## 參考要點（keyPoints）\n${list(q.keyPoints)}`,
    `## 參考答案（modelAnswer）\n${q.modelAnswer.trim()}`,
    `## 面試官可能的追問（followUps）\n${list(q.followUps)}`,
    resume ? `## 面試者的履歷事實（只能引用這裡有的經歷）\n${resume.trim()}` : '## 面試者的履歷事實\n（未提供；只能使用面試者這次回答裡說過的內容）',
    `## 面試者的回答\n${answer.trim()}`,
    '請依規則評分，輸出 JSON。',
  ].join('\n\n')
}

/** 靜態版：sample 沒有 system prompt，把角色說明與輸出格式都放進 input */
export function buildSamplePrompt(q: GradeSource, answer: string, lang: 'zh' | 'ja'): string {
  const fields = Object.entries(GRADE_SCHEMA.properties)
    .map(([k, v]) => `  "${k}": ${v.type === 'array' ? '["…"]' : v.type === 'integer' ? '4' : '"…"'}  // ${v.description}`)
    .join('\n')
  return [
    GRADE_SYSTEM,
    '---',
    buildGradePrompt(q, answer, lang, ''),
    `只回覆一個 JSON 物件，不要其他文字，格式如下（所有欄位都要有）：\n{\n${fields}\n}`,
  ].join('\n\n')
}

const strArr = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x.trim() !== '').map((x) => x.trim()) : [])

export function coerceGrade(v: unknown): GradeResult | null {
  if (typeof v !== 'object' || v === null) return null
  const o = v as Record<string, unknown>
  const score = Math.round(Number(o.score))
  if (!Number.isFinite(score) || typeof o.summary !== 'string') return null
  return {
    score: Math.min(5, Math.max(1, score)),
    summary: o.summary.trim(),
    covered: strArr(o.covered),
    missing: strArr(o.missing),
    inaccurate: strArr(o.inaccurate),
    followUps: strArr(o.followUps),
    improved: typeof o.improved === 'string' ? o.improved.trim() : '',
  }
}

/** 文字（可能包在 ``` 裡、或前後有一句話）→ GradeResult */
export function parseGradeText(text: string): GradeResult | null {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/)
  const body = (fenced ? fenced[1] : text).trim()
  for (const attempt of [body, body.slice(body.indexOf('{'), body.lastIndexOf('}') + 1)]) {
    try {
      const g = coerceGrade(JSON.parse(attempt))
      if (g) return g
    } catch {
      /* 下一個 */
    }
  }
  return null
}

/** 建置時轉好的 HTML → 給 AI 讀的純文字（靜態版沒有原始 Markdown） */
export function htmlToText(html: string): string {
  return html
    .replace(/<pre[^>]*>([\s\S]*?)<\/pre>/g, (_m, inner: string) => `\n${inner.replace(/<[^>]+>/g, '')}\n`)
    .replace(/<br\s*\/?>/g, '\n')
    .replace(/<\/(p|div|h[1-6]|tr)>/g, '\n')
    .replace(/<li[^>]*>/g, '- ')
    .replace(/<\/li>/g, '\n')
    .replace(/<\/t[dh]>/g, ' | ')
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}
