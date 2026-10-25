// Leitner 5 箱。面試只剩一週，間隔縮短為 [0, 1, 2, 3, 5] 天。
import type { ExerciseProgress, Result } from '../types.ts'
import { bumpDeviceCount } from './data.ts'
import { addDays, todayStr } from './date.ts'
import { getDevice } from './device.ts'

export const INTERVALS = [0, 1, 2, 3, 5] as const

/** 這台裝置多一次作答：只動自己的計數，所以兩台裝置同時作答不會互相蓋掉（裝置太多時舊的併進 others，見 data.ts） */
export function bumpCounts(prev: ExerciseProgress | undefined, correct: boolean, device: string = getDevice().id, now: number = Date.now()) {
  return bumpDeviceCount(prev, device, correct, now)
}

export function applyResult(prev: ExerciseProgress | undefined, result: Result, now: number = Date.now()): ExerciseProgress {
  const today = todayStr(now)
  const prevBox = prev?.box ?? 1
  let box: number
  let due: string
  if (result === 'correct') {
    box = Math.min(5, prevBox + 1)
    due = addDays(today, INTERVALS[box - 1])
  } else if (result === 'partial') {
    // 不熟：留在原本的箱，至少隔 1 天再看
    box = prevBox
    due = addDays(today, Math.max(1, INTERVALS[box - 1]))
  } else {
    box = 1
    due = addDays(today, INTERVALS[0])
  }
  return {
    ...bumpCounts(prev, result === 'correct', getDevice().id, now),
    last: result,
    at: now,
    box,
    due,
  }
}

export function isDue(p: ExerciseProgress | undefined, today: string = todayStr()): boolean {
  return !!p && p.due <= today
}

/** 「下次複習」的白話說明 */
export function dueLabel(p: ExerciseProgress, today: string = todayStr()): string {
  if (p.due <= today) return '現在可複習'
  const days = Math.round((Date.parse(p.due) - Date.parse(today)) / 86_400_000)
  return days === 1 ? '明天複習' : `${days} 天後複習`
}
