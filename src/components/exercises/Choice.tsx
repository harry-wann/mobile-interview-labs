import { useState } from 'react'
import { gradeMulti } from '../../lib/grading.ts'
import type { MultiExercise, SingleExercise } from '../../types.ts'
import { Icon } from '../Icon.tsx'
import { Html } from '../ui.tsx'
import { Explain, Prompt, Verdict, type BodyProps } from './common.tsx'

const LETTERS = 'ABCDEFGH'

export function SingleBody({ ex, report }: BodyProps<SingleExercise>) {
  const [picked, setPicked] = useState<number | null>(null)
  const answered = picked !== null
  const ok = picked === ex.answer

  const choose = (i: number) => {
    if (answered) return
    setPicked(i)
    report(i === ex.answer ? 'correct' : 'wrong')
  }

  return (
    <>
      <Prompt ex={ex} />
      <ul className="opts" role="radiogroup" aria-label="選項">
        {ex.optionsHtml.map((html, i) => {
          const state = !answered ? '' : i === ex.answer ? 'opt-correct' : i === picked ? 'opt-wrong' : 'opt-dim'
          return (
            <li key={i}>
              <button
                type="button"
                role="radio"
                aria-checked={picked === i}
                className={`opt ${state}`}
                aria-disabled={answered}
                onClick={() => choose(i)}
              >
                <span className="opt-key">{LETTERS[i]}</span>
                <Html inline className="opt-text" html={html} />
                {answered && i === ex.answer && <Icon name="check" size={16} />}
                {answered && i === picked && i !== ex.answer && <Icon name="x" size={16} />}
              </button>
            </li>
          )
        })}
      </ul>
      {answered && (
        <>
          <Verdict ok={ok}>{!ok && `正解是 ${LETTERS[ex.answer]}`}</Verdict>
          <Explain html={ex.explainHtml} />
          <div className="ex-actions">
            <button type="button" className="btn btn-sm" onClick={() => setPicked(null)}>
              再試一次
            </button>
          </div>
        </>
      )}
    </>
  )
}

export function MultiBody({ ex, report }: BodyProps<MultiExercise>) {
  const [picked, setPicked] = useState<number[]>([])
  const [submitted, setSubmitted] = useState(false)
  const ok = submitted && gradeMulti(ex.answer, picked)

  const toggle = (i: number) => {
    if (submitted) return
    setPicked((p) => (p.includes(i) ? p.filter((x) => x !== i) : [...p, i]))
  }
  const submit = () => {
    if (picked.length === 0) return
    setSubmitted(true)
    report(gradeMulti(ex.answer, picked) ? 'correct' : 'wrong')
  }
  const reset = () => {
    setPicked([])
    setSubmitted(false)
  }

  return (
    <>
      <Prompt ex={ex} />
      <ul className="opts" aria-label="選項（可複選）">
        {ex.optionsHtml.map((html, i) => {
          const isAns = ex.answer.includes(i)
          const isPicked = picked.includes(i)
          let state = isPicked ? 'opt-picked' : ''
          if (submitted) state = isAns && isPicked ? 'opt-correct' : isAns ? 'opt-missed' : isPicked ? 'opt-wrong' : 'opt-dim'
          return (
            <li key={i}>
              <button type="button" role="checkbox" aria-checked={isPicked} className={`opt ${state}`} aria-disabled={submitted} onClick={() => toggle(i)}>
                <span className="opt-key opt-key-box">{isPicked ? <Icon name="check" size={14} /> : LETTERS[i]}</span>
                <Html inline className="opt-text" html={html} />
                {submitted && isAns && <span className="opt-tag">{isPicked ? '正確' : '漏選'}</span>}
                {submitted && !isAns && isPicked && <span className="opt-tag">不該選</span>}
              </button>
            </li>
          )
        })}
      </ul>
      {!submitted ? (
        <div className="ex-actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={picked.length === 0} onClick={submit}>
            送出答案
          </button>
          <span className="muted small">已選 {picked.length} 項</span>
        </div>
      ) : (
        <>
          <Verdict ok={ok}>{!ok && `正解是 ${ex.answer.map((a) => LETTERS[a]).join('、')}`}</Verdict>
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
