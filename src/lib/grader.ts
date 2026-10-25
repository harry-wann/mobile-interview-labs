// AI 面試官評分的前端：本機版走 /api/grade（claude CLI），靜態版走 claude.ai 的 sample capability（sample.json）。
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { IS_STATIC } from '../env.ts'
import { ApiError, gradeAnswer } from './api.ts'
import { claudeUse, type SampleError, type SampleNs } from './claude.ts'
import { buildSamplePrompt, coerceGrade, htmlToText, parseGradeText, type GradeSource } from './gradePrompt.ts'
import { selectIsLocal, useStore } from './store.ts'
import type { GradeResult, OpenExercise } from '../types.ts'

// ───────── 靜態版：sample capability 的可用狀態 ─────────

type SampleState = 'pending' | 'yes' | 'no'
let sample: SampleNs | null = null
let sampleState: SampleState = IS_STATIC ? 'pending' : 'no'
/** 本次造訪是否被判定不能用（not_granted 等）→ 隱藏按鈕 */
let hidden = false
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())
const subscribe = (l: () => void) => {
  listeners.add(l)
  return () => listeners.delete(l)
}

export async function initSample() {
  if (!IS_STATIC) return
  sample = await claudeUse<SampleNs>('sample')
  sampleState = sample ? 'yes' : 'no'
  emit()
}

const snapshot = () => (hidden ? 'hidden' : sampleState)

export interface GradeAvailability {
  /** 可以按「AI 評分」 */
  canGrade: boolean
  /** 完全不顯示按鈕（not_granted 等，本次造訪不再提供） */
  hidden: boolean
  /** 還在確認能不能用 */
  pending: boolean
  /** 不能用時，給使用者看的一句話 */
  reason: string
}

export function useGradeAvailability(): GradeAvailability {
  const snap = useSyncExternalStore(subscribe, snapshot)
  const isLocal = useStore(selectIsLocal)
  if (!IS_STATIC) {
    const ok = isLocal !== false
    return { canGrade: ok, hidden: false, pending: false, reason: ok ? '' : 'AI 評分只開放在這台電腦上使用' }
  }
  if (snap === 'hidden') return { canGrade: false, hidden: true, pending: false, reason: '' }
  if (snap === 'pending') return { canGrade: false, hidden: false, pending: true, reason: '正在確認能不能使用 Claude…' }
  if (snap === 'no') return { canGrade: false, hidden: false, pending: false, reason: 'AI 評分需要在 claude.ai 的 Artifact 內開啟（這裡無法使用）' }
  return { canGrade: true, hidden: false, pending: false, reason: '' }
}

// ───────── 評分 ─────────

export interface GradeFailure {
  message: string
  /** invalid_json：Claude 的原始回覆 */
  rawText?: string
  cancelled?: boolean
}

function describeSampleError(e: SampleError): GradeFailure {
  switch (e.code) {
    case 'cancelled':
      return { message: '', cancelled: true }
    case 'not_granted':
    case 'sampling_disabled':
    case 'not_declared':
    case 'capability_disabled':
    case 'capability_removed':
      hidden = true // 本次造訪隱藏按鈕，不再重問
      emit()
      return { message: '這個環境沒有允許使用 Claude，AI 評分已隱藏。' }
    case 'rate_limited':
      return { message: '太頻繁或已達用量上限，請稍後再試。' }
    case 'invalid_json':
      return { message: 'Claude 的回覆不是有效的 JSON，無法解析（原始回覆如下）。可以再按一次評分。', rawText: e.text }
    case 'session_expired':
      return { message: '登入已過期，請重新整理頁面並重新登入。' }
    case 'prompt_too_large':
      return { message: '回答太長，請縮短後再評分。' }
    case 'refused':
      return { message: 'Claude 拒絕處理這個內容。' }
    default:
      return { message: `AI 評分暫時失敗（${e.code}），請稍後再試。`, rawText: e.text }
  }
}

const sourceOf = (ex: OpenExercise): GradeSource => ({
  prompt: htmlToText(ex.promptHtml),
  promptJa: ex.promptJaHtml ? htmlToText(ex.promptJaHtml) : undefined,
  keyPoints: ex.keyPointsHtml.map(htmlToText),
  modelAnswer: htmlToText(ex.modelAnswerHtml),
  followUps: ex.followUpsHtml.map(htmlToText),
})

async function gradeWithSample(ex: OpenExercise, answer: string, lang: 'zh' | 'ja', signal: AbortSignal, onText: (len: number) => void): Promise<GradeResult> {
  if (!sample) throw { code: 'capability_disabled', message: 'sample unavailable' } satisfies SampleError
  const raw = await sample.json<unknown>(buildSamplePrompt(sourceOf(ex), answer, lang), {
    modelTier: 'default',
    signal,
    onText: (u) => onText(u.text.length),
  })
  const g = typeof raw === 'string' ? parseGradeText(raw) : coerceGrade(raw)
  if (!g) throw { code: 'invalid_json', message: 'reply is not a grade object', text: JSON.stringify(raw)?.slice(0, 2000) } satisfies SampleError
  return g
}

export type GradePhase = 'idle' | 'thinking' | 'streaming'

export function useGrader(ex: OpenExercise) {
  const availability = useGradeAvailability()
  const [phase, setPhase] = useState<GradePhase>('idle')
  const [chars, setChars] = useState(0)
  const [failure, setFailure] = useState<GradeFailure | null>(null)
  const [elapsed, setElapsed] = useState(0)
  const ctl = useRef<AbortController | null>(null)

  useEffect(() => {
    if (phase === 'idle') return
    const t0 = Date.now()
    setElapsed(0)
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 500)
    return () => clearInterval(id)
  }, [phase === 'idle'])

  // 卸載時中止還在跑的評分（不再花額度）
  useEffect(() => () => ctl.current?.abort(), [])

  const stop = useCallback(() => ctl.current?.abort(), [])

  const run = useCallback(
    async (answer: string, lang: 'zh' | 'ja'): Promise<GradeResult | null> => {
      ctl.current?.abort()
      const c = new AbortController() // 每次呼叫新建 controller（aborted 的 signal 不能重用）
      ctl.current = c
      setFailure(null)
      setChars(0)
      setPhase('thinking')
      try {
        const g = IS_STATIC
          ? await gradeWithSample(ex, answer, lang, c.signal, (n) => {
              setChars(n)
              setPhase('streaming')
            })
          : await gradeAnswer(ex.id, answer, lang, c.signal)
        return g
      } catch (e) {
        if (IS_STATIC) {
          const f = describeSampleError(e as SampleError)
          if (!f.cancelled) setFailure(f)
        } else if (e instanceof ApiError) {
          if (e.status !== -1) setFailure({ message: e.message })
        } else {
          setFailure({ message: String(e) })
        }
        return null
      } finally {
        if (ctl.current === c) ctl.current = null
        setPhase('idle')
      }
    },
    [ex],
  )

  return { availability, phase, grading: phase !== 'idle', chars, elapsed, failure, run, stop }
}
