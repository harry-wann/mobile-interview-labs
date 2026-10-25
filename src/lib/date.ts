/** 面試日（選填）：在 .env 設 VITE_INTERVIEW_DATE=YYYY-MM-DD 就會顯示倒數；沒設就不顯示 */
export const INTERVIEW_DATE: string = import.meta.env.VITE_INTERVIEW_DATE ?? ''

const pad = (n: number) => String(n).padStart(2, '0')

/** 本機時區的 YYYY-MM-DD */
export function todayStr(now: number = Date.now()): string {
  const d = new Date(now)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const toUtc = (s: string) => {
  const [y, m, d] = s.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

/** b − a（日曆天數） */
export function daysBetween(a: string, b: string): number {
  return Math.round((toUtc(b) - toUtc(a)) / 86_400_000)
}

export function addDays(dateStr: string, n: number): string {
  const d = new Date(toUtc(dateStr) + n * 86_400_000)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

/** 例如 10/30 */
export function formatMD(dateStr: string): string {
  const [, m, d] = dateStr.split('-').map(Number)
  return `${m}/${d}`
}

export function formatDateTime(ms: number): string {
  const d = new Date(ms)
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export function formatDuration(totalSec: number): string {
  const s = Math.max(0, Math.round(totalSec))
  return `${Math.floor(s / 60)}:${pad(s % 60)}`
}
