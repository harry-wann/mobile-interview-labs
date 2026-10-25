// 極簡 hash router。網址只用「不含斜線的純錨點」：
//   #home #plan #practice #wrong #mock #answers #sec-<sectionId> #<unitId>
import { useSyncExternalStore } from 'react'

export type Route =
  | { name: 'home' | 'plan' | 'practice' | 'wrong' | 'mock' | 'answers' }
  | { name: 'section'; id: string }
  | { name: 'unit'; id: string }

const FIXED = new Set(['home', 'plan', 'practice', 'wrong', 'mock', 'answers'])

export function parseToken(token: string): Route {
  let t = token.replace(/^#/, '')
  try {
    t = decodeURIComponent(t)
  } catch {
    /* 保持原樣 */
  }
  if (!t) return { name: 'home' }
  if (FIXED.has(t)) return { name: t } as Route
  if (t.startsWith('sec-')) return { name: 'section', id: t.slice(4) }
  return { name: 'unit', id: t }
}

export function routeToken(r: Route): string {
  if (r.name === 'section') return `sec-${r.id}`
  if (r.name === 'unit') return r.id
  return r.name
}

function readLocationToken(): string {
  try {
    return location.hash.replace(/^#/, '')
  } catch {
    return ''
  }
}

let token = readLocationToken()
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

/** 導頁時帶的記憶體內狀態（不進網址）：要聚焦的題目、練習中心預設篩選 … */
export interface NavState {
  focus?: string
  practice?: Record<string, unknown>
}
let pending: NavState | null = null
/** 每次 navigate() 都 +1：同一頁再次導覽（例如搜尋點同一單元的另一題）也能讓頁面重新處理 focus */
let navVersion = 0
export const consumeNavState = (): NavState | null => {
  const p = pending
  pending = null
  return p
}
export const peekNavState = (): NavState | null => pending

function sync() {
  const t = readLocationToken()
  if (t !== token) {
    token = t
    emit()
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('hashchange', sync)
  window.addEventListener('popstate', sync)
}

export function navigate(target: string | Route, state?: NavState) {
  const t = typeof target === 'string' ? target.replace(/^#/, '') : routeToken(target)
  pending = state ?? null
  navVersion++
  if (t === token) {
    emit() // 同一頁但帶了 focus 時也要通知
    return
  }
  token = t
  try {
    history.pushState(null, '', `#${t}`)
  } catch {
    try {
      location.hash = t
    } catch {
      /* 沙箱不允許改網址：只更新內部狀態 */
    }
  }
  emit()
}

/** 第一次載入時把網址正規化成 #home 等（用 replaceState，不增加歷史） */
export function normalizeInitialHash() {
  if (!token) {
    try {
      history.replaceState(null, '', '#home')
    } catch {
      /* ignore */
    }
  }
}

const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}
const getToken = () => token

const getNavVersion = () => navVersion
export function useNavVersion(): number {
  return useSyncExternalStore(subscribe, getNavVersion, () => 0)
}

export function useRouteToken(): string {
  return useSyncExternalStore(subscribe, getToken, () => '')
}
