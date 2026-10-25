import { Fragment, useMemo, useState } from 'react'
import { gradeFill, gradePredict } from '../../lib/grading.ts'
import type { FillExercise, PredictExercise } from '../../types.ts'
import { Explain, Prompt, Verdict, type BodyProps } from './common.tsx'

const BLANK = '<i class="blank"></i>'

export function FillBody({ ex, report }: BodyProps<FillExercise>) {
  const parts = useMemo(() => (ex.codeHtml ?? '').split(BLANK), [ex.codeHtml])
  const [vals, setVals] = useState<string[]>(() => ex.blanks.map(() => ''))
  const [marks, setMarks] = useState<boolean[] | null>(null)
  const checked = marks !== null
  const allOk = checked && marks.every(Boolean)

  const check = () => {
    const m = gradeFill(ex, vals)
    setMarks(m)
    report(m.every(Boolean) ? 'correct' : 'wrong')
  }
  const reset = () => {
    setVals(ex.blanks.map(() => ''))
    setMarks(null)
  }

  return (
    <>
      <Prompt ex={ex} withCode={false} />
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (!checked) check()
        }}
      >
        <pre className="code code-fill" data-lang={ex.codeLang}>
          <code>
            {parts.map((html, i) => (
              <Fragment key={i}>
                <span dangerouslySetInnerHTML={{ __html: html }} />
                {i < ex.blanks.length && (
                  <input
                    id={`${ex.id}-blank-${i + 1}`}
                    className={`blank-input ${checked ? (marks[i] ? 'is-ok' : 'is-bad') : ''}`}
                    style={{ width: `${Math.max(5, Math.max(...ex.blanks[i].map((b) => b.length)) + 2)}ch` }}
                    value={vals[i]}
                    readOnly={checked}
                    autoComplete="off"
                    autoCapitalize="off"
                    autoCorrect="off"
                    spellCheck={false}
                    aria-label={`空格 ${i + 1}`}
                    onChange={(e) => setVals((v) => v.map((x, k) => (k === i ? e.target.value : x)))}
                  />
                )}
              </Fragment>
            ))}
          </code>
        </pre>
        {!checked ? (
          <div className="ex-actions">
            <button type="submit" className="btn btn-primary btn-sm" disabled={vals.every((v) => !v.trim())}>
              檢查答案
            </button>
            <span className="muted small">{ex.blanks.length} 個空格</span>
          </div>
        ) : (
          <>
            <Verdict ok={allOk}>{`${marks.filter(Boolean).length}/${marks.length} 格正確`}</Verdict>
            <ul className="accepted">
              {ex.blanks.map((accept, i) => (
                <li key={i} className={marks[i] ? 'is-ok' : 'is-bad'}>
                  <span className="accepted-n">空格 {i + 1}</span>
                  {accept.map((a, k) => (
                    <code key={k}>{a}</code>
                  ))}
                  {!marks[i] && vals[i].trim() && <span className="muted small">（你填的：{vals[i]}）</span>}
                </li>
              ))}
            </ul>
            <Explain html={ex.explainHtml} />
            <div className="ex-actions">
              <button type="button" className="btn btn-sm" onClick={reset}>
                再試一次
              </button>
            </div>
          </>
        )}
      </form>
    </>
  )
}

export function PredictBody({ ex, report }: BodyProps<PredictExercise>) {
  const [val, setVal] = useState('')
  const [ok, setOk] = useState<boolean | null>(null)
  const checked = ok !== null

  const check = () => {
    const r = gradePredict(ex, val)
    setOk(r)
    report(r ? 'correct' : 'wrong')
  }

  return (
    <>
      <Prompt ex={ex} />
      <div className="field">
        <label htmlFor={`${ex.id}-out`} className="field-label">
          輸出（以空白或換行分隔）
        </label>
        <textarea
          id={`${ex.id}-out`}
          className="input mono"
          rows={2}
          value={val}
          readOnly={checked}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          placeholder="例：A C B"
          onChange={(e) => setVal(e.target.value)}
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && !checked && val.trim()) check()
          }}
        />
      </div>
      {!checked ? (
        <div className="ex-actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={!val.trim()} onClick={check}>
            檢查答案
          </button>
        </div>
      ) : (
        <>
          <Verdict ok={ok} />
          {!ok && (
            <p className="answer-line">
              正確輸出：<code>{ex.answer.trim().replace(/\s+/g, ' ')}</code>
              {ex.acceptAlso.length > 0 && <span className="muted small">　也接受：{ex.acceptAlso.join('　／　')}</span>}
            </p>
          )}
          <Explain html={ex.explainHtml} />
          <div className="ex-actions">
            <button
              type="button"
              className="btn btn-sm"
              onClick={() => {
                setVal('')
                setOk(null)
              }}
            >
              再試一次
            </button>
          </div>
        </>
      )}
    </>
  )
}
