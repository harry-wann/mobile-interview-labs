import type { useGrader } from '../../lib/grader.ts'

/** 評分進行中的狀態（思考中／回覆中、已等待幾秒）與失敗訊息 */
export function GradeStatus({ grader }: { grader: ReturnType<typeof useGrader> }) {
  return (
    <>
      {grader.grading && (
        <p className="muted small" role="status" aria-live="polite">
          {grader.phase === 'streaming' ? `Claude 回覆中…（${grader.chars} 字）` : '思考中…'}　已等待 <span className="num">{grader.elapsed}</span> 秒，可以按「停止」。
        </p>
      )}
      {grader.failure && (
        <div className="run-result run-fail" role="alert">
          {grader.failure.message}
          {grader.failure.rawText && <pre className="run-error">{grader.failure.rawText}</pre>}
        </div>
      )}
    </>
  )
}
