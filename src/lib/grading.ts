// 各題型的判分規則（照 content/SCHEMA.md）
import type { FillExercise, PredictExercise } from '../types.ts'

/** 比對前 trim、合併空白 */
export const normalizeText = (s: string, caseInsensitive = false) => {
  const t = s.trim().replace(/\s+/g, ' ')
  return caseInsensitive ? t.toLowerCase() : t
}

export function gradeFill(ex: FillExercise, inputs: string[]): boolean[] {
  return ex.blanks.map((accept, i) => {
    const got = normalizeText(inputs[i] ?? '', ex.caseInsensitive)
    return got !== '' && accept.some((a) => normalizeText(a, ex.caseInsensitive) === got)
  })
}

export const tokenize = (s: string) => s.split(/[\s,]+/).filter(Boolean)

export function gradePredict(ex: PredictExercise, input: string): boolean {
  const got = tokenize(input)
  if (got.length === 0) return false
  const candidates = [ex.answer, ...ex.acceptAlso].map(tokenize)
  return candidates.some((c) => c.length === got.length && c.every((t, i) => t === got[i]))
}

export function gradeMulti(answer: number[], picked: number[]): boolean {
  if (answer.length !== picked.length) return false
  const set = new Set(picked)
  return answer.every((a) => set.has(a))
}

/** 洗牌；保證結果和原順序不同（長度 ≥ 2） */
export function shuffleNotSame(n: number): number[] {
  const identity = Array.from({ length: n }, (_, i) => i)
  if (n < 2) return identity
  for (let attempt = 0; attempt < 20; attempt++) {
    const a = [...identity]
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      ;[a[i], a[j]] = [a[j], a[i]]
    }
    if (a.some((v, i) => v !== i)) return a
  }
  return [...identity.slice(1), identity[0]]
}

export function shuffle<T>(arr: readonly T[]): T[] {
  const a = [...arr]
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[a[i], a[j]] = [a[j], a[i]]
  }
  return a
}
