import { useSyncExternalStore } from 'react'
import { getItem, setItem } from './storage.ts'

export type ThemePref = 'system' | 'light' | 'dark'
const KEY = 'bt.theme'

const read = (): ThemePref => {
  const v = getItem(KEY)
  return v === 'light' || v === 'dark' ? v : 'system'
}

let pref: ThemePref = read()
const listeners = new Set<() => void>()

export function applyTheme(p: ThemePref = pref) {
  document.documentElement.setAttribute('data-theme', p)
}

export function setTheme(p: ThemePref) {
  pref = p
  setItem(KEY, p)
  applyTheme(p)
  listeners.forEach((l) => l())
}

export function useThemePref(): ThemePref {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => pref,
  )
}
