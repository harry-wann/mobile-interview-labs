import type { ReactNode } from 'react'
import type { Exercise, ExerciseProgress, Result } from '../../types.ts'
import { CodeBlock, Html } from '../ui.tsx'
import { Icon } from '../Icon.tsx'

export interface BodyProps<T extends Exercise> {
  ex: T
  /** 記錄作答結果進 SRS */
  report: (r: Result) => void
}

export const TYPE_LABEL: Record<Exercise['type'], string> = {
  single: '單選',
  multi: '複選',
  fill: '填空',
  predict: '預測輸出',
  order: '排序',
  code: '實作',
  open: '口說／設計',
  flash: '速記卡',
}

export const RESULT_LABEL: Record<Result, string> = { correct: '答對', partial: '不熟', wrong: '答錯' }

export function Prompt({ ex, withCode = true }: { ex: Exercise; withCode?: boolean }) {
  return (
    <>
      {ex.promptHtml && <Html className="prose ex-prompt" html={ex.promptHtml} />}
      {withCode && ex.codeHtml && <CodeBlock html={ex.codeHtml} lang={ex.codeLang} />}
    </>
  )
}

export function Verdict({ ok, children }: { ok: boolean; children?: ReactNode }) {
  return (
    <div className={`verdict ${ok ? 'verdict-ok' : 'verdict-bad'}`} role="status">
      <Icon name={ok ? 'check' : 'x'} size={16} />
      <strong>{ok ? '答對' : '答錯'}</strong>
      {children && <span className="verdict-detail">{children}</span>}
    </div>
  )
}

export function Explain({ html }: { html?: string }) {
  if (!html) return null
  return (
    <div className="explain">
      <div className="explain-label">解析</div>
      <Html className="prose" html={html} />
    </div>
  )
}

export function progressSummary(p: ExerciseProgress | undefined): string {
  if (!p) return '未作答'
  return `${RESULT_LABEL[p.last]}・${p.correct}/${p.attempts} 次答對`
}
