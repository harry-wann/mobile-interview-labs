import type { GradeResult, RunLang } from '../types.ts'

export interface RunResponse {
  ok: boolean
  phase: 'compile' | 'run' | 'done'
  tests: Array<{ name: string; pass: boolean; detail: string }>
  passed: number
  total: number
  output: string
  error: string
  durationMs: number
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
  }
}

async function post<T>(url: string, body: unknown, timeoutMs: number, external?: AbortSignal): Promise<T> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  // 使用者按「停止」：中止 fetch → server 偵測到斷線會殺掉 claude 子程序
  const onExternal = () => ctrl.abort()
  external?.addEventListener('abort', onExternal, { once: true })
  try {
    const r = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    })
    let data: unknown = null
    try {
      data = await r.json()
    } catch {
      /* 非 JSON */
    }
    if (!r.ok) {
      const msg = (data as { error?: string } | null)?.error
      throw new ApiError(msg ?? `HTTP ${r.status}`, r.status)
    }
    return data as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    if ((e as Error).name === 'AbortError') throw new ApiError(external?.aborted ? '已停止' : '等待逾時', external?.aborted ? -1 : 0)
    throw new ApiError('連不到本機 server（請確認 npm run dev 還在執行）', 0)
  } finally {
    clearTimeout(timer)
    external?.removeEventListener('abort', onExternal)
  }
}

export const runCode = (exerciseId: string, lang: RunLang, code: string) =>
  post<RunResponse>('/api/run', { exerciseId, lang, code }, 150_000)

export const gradeAnswer = (exerciseId: string, answer: string, lang: 'zh' | 'ja', signal?: AbortSignal) =>
  post<GradeResult & { durationMs: number }>('/api/grade', { exerciseId, answer, lang }, 200_000, signal)
