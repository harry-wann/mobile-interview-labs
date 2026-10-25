// 這台裝置的識別：作答次數各裝置各自計數、歷史版本標示「哪一台寫的」
import { getJSON, setJSON } from './storage.ts'

export interface Device {
  id: string
  /** 人看得懂的標籤，例如「iPhone・a1b2」 */
  label: string
}

const KEY = 'bt.device'
let cached: Device | null = null

function guessPlatform(): string {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent
  if (/iPhone/.test(ua)) return 'iPhone'
  if (/iPad/.test(ua)) return 'iPad'
  if (/Android/.test(ua)) return 'Android'
  if (/Macintosh/.test(ua)) return 'Mac'
  if (/Windows/.test(ua)) return 'Windows'
  if (/Linux/.test(ua)) return 'Linux'
  return '裝置'
}

export function getDevice(): Device {
  if (cached) return cached
  const raw = getJSON(KEY) as Partial<Device> | null
  if (raw && typeof raw.id === 'string' && /^[a-z0-9]{4,12}$/.test(raw.id) && typeof raw.label === 'string') {
    cached = { id: raw.id, label: raw.label }
    return cached
  }
  const id = Math.random().toString(36).slice(2, 8).padEnd(6, '0')
  cached = { id, label: `${guessPlatform()}・${id.slice(0, 4)}` }
  setJSON(KEY, cached)
  return cached
}
