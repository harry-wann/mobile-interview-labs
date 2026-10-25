// 區網存取 token：手機第一次用「終端機印出的網址」（?t=<token>）開啟，存進 localStorage 並從網址移除；
// 之後所有 /api/answers、/api/progress 請求都帶 X-Prep-Token。localhost 不需要 token。
import { getItem, setItem } from './storage.ts'

const KEY = 'bt.lanToken'

export function captureLanToken() {
  try {
    const u = new URL(location.href)
    const t = u.searchParams.get('t')
    if (t && /^[a-f0-9]{32}$/.test(t)) {
      setItem(KEY, t)
      u.searchParams.delete('t')
      history.replaceState(null, '', u.pathname + u.search + u.hash)
    }
  } catch {
    /* 沙箱不允許改網址：token 仍已存起來（若有） */
  }
}

export function lanHeaders(): Record<string, string> {
  const t = getItem(KEY)
  return t ? { 'X-Prep-Token': t } : {}
}
