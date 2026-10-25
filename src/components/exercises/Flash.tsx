import { useState } from 'react'
import type { FlashExercise, Result } from '../../types.ts'
import { Html } from '../ui.tsx'
import type { BodyProps } from './common.tsx'

const RATINGS: Array<{ result: Result; label: string; hint: string }> = [
  { result: 'correct', label: '記得', hint: '升一箱' },
  { result: 'partial', label: '不熟', hint: '留在原箱，隔天再看' },
  { result: 'wrong', label: '忘了', hint: '回第 1 箱' },
]

export function FlashBody({ ex, report }: BodyProps<FlashExercise>) {
  const [flipped, setFlipped] = useState(false)
  const [rated, setRated] = useState<Result | null>(null)

  return (
    <div className="flash">
      <div className="flash-face">
        <div className="flash-side">問</div>
        <Html className="prose" html={ex.frontHtml} />
      </div>
      {!flipped ? (
        <div className="ex-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setFlipped(true)}>
            翻面看答案
          </button>
        </div>
      ) : (
        <>
          <div className="flash-face flash-back">
            <div className="flash-side">答</div>
            <Html className="prose" html={ex.backHtml} />
          </div>
          {rated === null ? (
            <div className="ex-actions flash-rate" role="group" aria-label="這張卡你記得嗎">
              {RATINGS.map((r) => (
                <button
                  key={r.result}
                  type="button"
                  className={`btn btn-sm ${r.result === 'correct' ? 'btn-ok' : r.result === 'wrong' ? 'btn-danger' : ''}`}
                  title={r.hint}
                  onClick={() => {
                    setRated(r.result)
                    report(r.result)
                  }}
                >
                  {r.label}
                </button>
              ))}
            </div>
          ) : (
            <div className="ex-actions">
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => {
                  setFlipped(false)
                  setRated(null)
                }}
              >
                再看一次
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
