// localStorage 讀寫一律包 try/catch（Artifact 沙箱、無痕模式、容量滿都可能丟例外）；失敗就退回記憶體
const mem = new Map<string, string>()
/** 最後一次寫進 localStorage 失敗的 key：讀取時以記憶體裡的新值為準（localStorage 裡留的是舊值） */
const memNewer = new Set<string>()

export function getItem(key: string): string | null {
  if (memNewer.has(key)) return mem.get(key) ?? null
  try {
    const v = localStorage.getItem(key)
    if (v !== null) return v
  } catch {
    /* 無法使用 localStorage */
  }
  return mem.get(key) ?? null
}

export function setItem(key: string, value: string): boolean {
  mem.set(key, value)
  try {
    localStorage.setItem(key, value)
    memNewer.delete(key)
    return true
  } catch {
    memNewer.add(key)
    return false
  }
}

export function removeItem(key: string): void {
  mem.delete(key)
  memNewer.delete(key)
  try {
    localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}

export function getJSON(key: string): unknown {
  const raw = getItem(key)
  if (raw === null) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function setJSON(key: string, value: unknown): boolean {
  try {
    return setItem(key, JSON.stringify(value))
  } catch {
    return false
  }
}

/** 這個環境能不能真的寫 localStorage（Artifact 沙箱、無痕模式可能不行）；不行的話資料只存在記憶體，關閉即遺失 */
export const storageWritable: boolean = (() => {
  try {
    localStorage.setItem('__bt_probe', '1')
    localStorage.removeItem('__bt_probe')
    return true
  } catch {
    return false
  }
})()
