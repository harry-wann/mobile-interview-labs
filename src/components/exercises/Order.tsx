import { useState } from 'react'
import { shuffleNotSame } from '../../lib/grading.ts'
import type { OrderExercise } from '../../types.ts'
import { Icon } from '../Icon.tsx'
import { Html } from '../ui.tsx'
import { Explain, Prompt, Verdict, type BodyProps } from './common.tsx'

export function OrderBody({ ex, report }: BodyProps<OrderExercise>) {
  const n = ex.itemsHtml.length
  const [arr, setArr] = useState<number[]>(() => shuffleNotSame(n))
  const [submitted, setSubmitted] = useState(false)
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const [over, setOver] = useState<number | null>(null)
  const marks = arr.map((v, i) => v === i)
  const ok = submitted && marks.every(Boolean)

  const move = (from: number, to: number) => {
    if (submitted || to < 0 || to >= n || from === to) return
    setArr((a) => {
      const c = [...a]
      const [x] = c.splice(from, 1)
      c.splice(to, 0, x)
      return c
    })
  }
  const submit = () => {
    setSubmitted(true)
    report(marks.every(Boolean) ? 'correct' : 'wrong')
  }
  const reset = () => {
    setArr(shuffleNotSame(n))
    setSubmitted(false)
  }

  return (
    <>
      <Prompt ex={ex} />
      <p className="muted small">拖曳項目、或用右側的上移／下移按鈕調整順序。</p>
      <ol className="order-list">
        {arr.map((itemIdx, pos) => (
          <li
            key={itemIdx}
            className={`order-item ${submitted ? (marks[pos] ? 'is-ok' : 'is-bad') : ''} ${over === pos && dragFrom !== null && dragFrom !== pos ? 'is-over' : ''} ${dragFrom === pos ? 'is-dragging' : ''}`}
            draggable={!submitted}
            onDragStart={(e) => {
              setDragFrom(pos)
              e.dataTransfer.effectAllowed = 'move'
              e.dataTransfer.setData('text/plain', String(pos))
            }}
            onDragOver={(e) => {
              if (dragFrom === null) return
              e.preventDefault()
              setOver(pos)
            }}
            onDrop={(e) => {
              e.preventDefault()
              if (dragFrom !== null) move(dragFrom, pos)
              setDragFrom(null)
              setOver(null)
            }}
            onDragEnd={() => {
              setDragFrom(null)
              setOver(null)
            }}
          >
            <span className="order-grip" aria-hidden="true">
              <Icon name="grip" size={16} />
            </span>
            <span className="order-n">{pos + 1}</span>
            <Html inline className="order-text" html={ex.itemsHtml[itemIdx]} />
            {submitted ? (
              <span className="order-mark">{marks[pos] ? <Icon name="check" size={16} /> : <Icon name="x" size={16} />}</span>
            ) : (
              <span className="order-btns">
                <button type="button" className="icon-btn" aria-label={`第 ${pos + 1} 項上移`} disabled={pos === 0} onClick={() => move(pos, pos - 1)}>
                  <Icon name="up" size={16} />
                </button>
                <button type="button" className="icon-btn" aria-label={`第 ${pos + 1} 項下移`} disabled={pos === n - 1} onClick={() => move(pos, pos + 1)}>
                  <Icon name="down" size={16} />
                </button>
              </span>
            )}
          </li>
        ))}
      </ol>
      {!submitted ? (
        <div className="ex-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={submit}>
            檢查順序
          </button>
          <button type="button" className="btn btn-sm" onClick={() => setArr(shuffleNotSame(n))}>
            重新打亂
          </button>
        </div>
      ) : (
        <>
          <Verdict ok={ok}>{!ok && `${marks.filter(Boolean).length}/${n} 項位置正確`}</Verdict>
          {!ok && (
            <div className="answer-order">
              <div className="explain-label">正確順序</div>
              <ol>
                {ex.itemsHtml.map((h, i) => (
                  <li key={i}>
                    <Html inline html={h} />
                  </li>
                ))}
              </ol>
            </div>
          )}
          <Explain html={ex.explainHtml} />
          <div className="ex-actions">
            <button type="button" className="btn btn-sm" onClick={reset}>
              再試一次
            </button>
          </div>
        </>
      )}
    </>
  )
}
