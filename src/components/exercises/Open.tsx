import { useState } from 'react'
import { useGrader } from '../../lib/grader.ts'
import { dueLabel } from '../../lib/srs.ts'
import { pullFresh, saveGrade, selectAnswers, selectProgress, useStore } from '../../lib/store.ts'
import type { OpenExercise } from '../../types.ts'
import { AnswerField, FactField, RemovedFacts, useEditableField } from '../AnswerField.tsx'
import { Icon } from '../Icon.tsx'
import { Html, Segmented } from '../ui.tsx'
import { Explain, Prompt, type BodyProps } from './common.tsx'
import { GradePanel } from './GradePanel.tsx'
import { GradeStatus } from './GradeStatus.tsx'

const SCORE_LABELS = ['', '完全不會', '很弱', '普通', '不錯', '可直接上場']

export function OpenBody({ ex, report }: BodyProps<OpenExercise>) {
  const hasJa = !!ex.promptJaHtml
  const [lang, setLang] = useState<'zh' | 'ja'>('zh')
  const field = useEditableField(ex.id, { type: 'text' })
  const value = field.value
  const answers = useStore(selectAnswers)
  const progress = useStore(selectProgress).exercises[ex.id]
  const entry = answers.answers[ex.id]
  const [compared, setCompared] = useState(false)
  const [checks, setChecks] = useState<boolean[]>(() => ex.keyPointsHtml.map(() => false))
  const [rated, setRated] = useState<number | null>(null)
  const grader = useGrader(ex)
  const covered = checks.filter(Boolean).length

  const runGrade = async () => {
    field.commit() // 先存（帶輸入框自己的 base：編輯中別台改過的版本會進歷史，不會被蓋掉）
    await pullFresh(ex.id, true) // 評分前先 pull 這一題
    const g = await grader.run(value, lang)
    if (g) {
      const { durationMs: _ms, ...rest } = g as typeof g & { durationMs?: number }
      saveGrade(ex.id, { ...rest, at: Date.now(), lang, answer: value })
    }
  }

  const rate = (score: number) => {
    setRated(score)
    report(score >= 4 ? 'correct' : score === 3 ? 'partial' : 'wrong')
  }

  return (
    <>
      {hasJa && (
        <div className="lang-switch">
          <Segmented
            label="題目語言"
            value={lang}
            onChange={setLang}
            options={[
              { value: 'zh', label: '中文' },
              { value: 'ja', label: '日本語' },
            ]}
          />
        </div>
      )}
      {lang === 'ja' && ex.promptJaHtml ? <Html className="prose ex-prompt" lang="ja" html={ex.promptJaHtml} /> : <Prompt ex={ex} />}

      <AnswerField
        exerciseId={ex.id}
        f={field}
        id={`${ex.id}-answer`}
        label="我的回答（＝我的口徑，會自動儲存）"
        lang={lang}
        rows={7}
        placeholder={lang === 'ja' ? '面接で話すつもりの内容を書く／話す' : '寫下你打算在面試中講的內容，或按語音輸入直接講'}
      />

      {ex.factsHtml.length > 0 && (
        <fieldset className="facts">
          <legend>只有你知道的事（面試前填好，會存進我的口徑）</legend>
          {ex.factsHtml.map((html, i) => (
            <FactField key={ex.factIds[i]} exerciseId={ex.id} factId={ex.factIds[i]} html={html} index={i} count={ex.factIds.length} />
          ))}
        </fieldset>
      )}
      <RemovedFacts exerciseId={ex.id} factIds={ex.factIds} factsHtml={ex.factsHtml} />

      <div className="ex-actions">
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setCompared(true)} disabled={compared}>
          對照要點
        </button>
        {!grader.availability.hidden && (
          <>
            <button type="button" className="btn btn-sm" onClick={runGrade} disabled={!grader.availability.canGrade || grader.grading || !value.trim()} title={!value.trim() ? '先寫下你的回答' : undefined}>
              {grader.grading ? '思考中…' : 'AI 面試官評分'}
            </button>
            {grader.grading && (
              <button type="button" className="btn btn-sm btn-danger" onClick={grader.stop}>
                停止
              </button>
            )}
            {!grader.availability.canGrade && grader.availability.reason && <span className="muted small">{grader.availability.reason}</span>}
          </>
        )}
      </div>
      <GradeStatus grader={grader} />
      {entry?.grade && <GradePanel grade={entry.grade} currentAnswer={value} />}

      {compared && (
        <div className="compare">
          <div className="field-row">
            <h4 className="h-sm">對照要點：你講到了哪些？</h4>
            <span className="num muted small">
              {covered}/{ex.keyPointsHtml.length}
            </span>
          </div>
          <ul className="keypoints">
            {ex.keyPointsHtml.map((html, i) => (
              <li key={i}>
                <label htmlFor={`${ex.id}-kp-${i}`} className={checks[i] ? 'is-on' : ''}>
                  <input id={`${ex.id}-kp-${i}`} type="checkbox" checked={checks[i]} onChange={() => setChecks((c) => c.map((x, k) => (k === i ? !x : x)))} />
                  <Html inline html={html} />
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}

      <details className="fold">
        <summary>參考答案</summary>
        <Html className="prose" html={ex.modelAnswerHtml} />
        {ex.modelAnswerJaHtml && (
          <>
            <div className="explain-label">日本語</div>
            <Html className="prose" lang="ja" html={ex.modelAnswerJaHtml} />
          </>
        )}
        <Explain html={ex.explainHtml} />
      </details>

      {ex.followUpsHtml.length > 0 && (
        <details className="fold">
          <summary>面試官可能的追問（{ex.followUpsHtml.length}）</summary>
          <ul className="followups">
            {ex.followUpsHtml.map((h, i) => (
              <li key={i}>
                <Html inline html={h} />
              </li>
            ))}
          </ul>
        </details>
      )}

      <div className="rate">
        <div className="explain-label">自評（會排進複習）</div>
        <div className="rate-btns" role="group" aria-label="自評 1 到 5 分">
          {[1, 2, 3, 4, 5].map((s) => (
            <button key={s} type="button" className={`btn btn-sm rate-btn ${rated === s ? 'btn-primary' : ''}`} aria-pressed={rated === s} disabled={rated !== null && rated !== s} onClick={() => rate(s)}>
              <span className="num">{s}</span>
              <span className="rate-label">{SCORE_LABELS[s]}</span>
            </button>
          ))}
          {rated !== null && (
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => setRated(null)}>
              重新自評
            </button>
          )}
        </div>
        {rated !== null && progress && (
          <p className="muted small">
            <Icon name="check" size={14} /> 已記錄自評 {rated} 分，{dueLabel(progress)}。
          </p>
        )}
      </div>
    </>
  )
}
