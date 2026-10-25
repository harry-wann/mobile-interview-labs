import type { PlanDay } from '../types.ts'

export type PlanPhase = 'before' | 'during' | 'after'

/** 今天屬於計畫第幾天。開始前 → 第 1 天（phase=before）；結束後 → 最後一天（phase=after） */
export function currentPlanDay(plan: PlanDay[], today: string): { day: PlanDay; phase: PlanPhase } | null {
  if (plan.length === 0) return null
  const sorted = [...plan].sort((a, b) => a.date.localeCompare(b.date))
  if (today < sorted[0].date) return { day: sorted[0], phase: 'before' }
  const last = sorted[sorted.length - 1]
  if (today > last.date) return { day: last, phase: 'after' }
  const hit = [...sorted].reverse().find((d) => d.date <= today)!
  return { day: hit, phase: 'during' }
}
