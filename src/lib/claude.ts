// claude.ai Artifact 的 capability（db／user／sample）。只用 `await window.claude.use(name)` 取得 namespace：
// window.claude 只保證有 use；沒有 window.claude（本機預覽、另存的檔案）時一律回傳 null，頁面照常運作。
// 型別是依 artifact-capabilities（db.d.ts／user.d.ts／sample.d.ts）手寫的最小子集。

export interface DbError {
  code: string
  message: string
}
export interface DocSnap {
  id: string
  exists: boolean
  data(): Record<string, unknown> | undefined
}
export interface QuerySnap {
  docs: DocSnap[]
  size: number
}
export interface DocRef {
  get(): Promise<DocSnap>
  set(data: Record<string, unknown>): Promise<void>
  update(data: Record<string, unknown>): Promise<void>
  delete(): Promise<void>
  collection(path: string): ColRef
}
export interface ColRef {
  doc(id?: string): DocRef
  get(): Promise<QuerySnap>
}
export interface Db {
  doc(path: string): DocRef
  collection(path: string): ColRef
}
export interface UserNs {
  id(): Promise<string | null>
}

export interface SampleError {
  code: string
  message: string
  text?: string
}
export interface SampleOptions {
  onText?: (u: { text: string; delta: string }) => void
  signal?: AbortSignal
  modelTier?: 'quick' | 'default' | 'complex'
  cache?: boolean
}
export interface SampleNs {
  json<T = unknown>(input: string, options?: SampleOptions): Promise<T>
}

const USE_TIMEOUT_MS = 12_000

/** 取得 capability；任何失敗（沒有 window.claude、拋例外、逾時）都回傳 null */
export async function claudeUse<T>(name: 'db' | 'user' | 'sample'): Promise<T | null> {
  try {
    const c = (window as unknown as { claude?: { use?: (n: string) => Promise<unknown> } }).claude
    if (!c || typeof c.use !== 'function') return null
    const result = await Promise.race([
      Promise.resolve(c.use(name)).catch(() => null),
      new Promise<null>((resolve) => setTimeout(() => resolve(null), USE_TIMEOUT_MS)),
    ])
    return (result as T | null) ?? null
  } catch {
    return null
  }
}

export const errCode = (e: unknown): string => {
  const c = (e as { code?: unknown } | null)?.code
  return typeof c === 'string' ? c : 'unavailable' // 未知的 code 當作 unavailable（依 db.d.ts）
}
