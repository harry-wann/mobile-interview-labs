import type { ContentIndex } from '../content.ts'
import type { Exercise, Unit } from '../types.ts'

export type SearchHit =
  | { kind: 'unit'; unit: Unit; score: number }
  | { kind: 'exercise'; exercise: Exercise; unit: Unit; score: number }

/** 單元標題、tags、題目 prompt 的全文搜尋（所有關鍵字都要命中；以空白分隔） */
export function searchContent(index: ContentIndex, query: string, limit = 30): SearchHit[] {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (terms.length === 0) return []
  const hits: SearchHit[] = []
  for (const u of index.unitList) {
    if (terms.every((t) => u.search.includes(t))) {
      const title = u.title.toLowerCase()
      const score = 10 + terms.filter((t) => title.includes(t)).length * 5 + (u.priority === 'P0' ? 1 : 0)
      hits.push({ kind: 'unit', unit: u, score })
    }
  }
  for (const u of index.unitList) {
    for (const e of index.exercisesByUnit.get(u.id) ?? []) {
      if (terms.every((t) => e.search.includes(t) || u.search.includes(t))) {
        if (terms.every((t) => u.search.includes(t) && !e.search.includes(t))) continue // 已由單元命中
        hits.push({ kind: 'exercise', exercise: e, unit: u, score: 1 })
      }
    }
  }
  return hits.sort((a, b) => b.score - a.score).slice(0, limit)
}
