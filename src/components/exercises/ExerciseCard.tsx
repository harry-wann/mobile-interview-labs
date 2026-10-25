import { useCallback, useRef, useState } from 'react'
import { exerciseKey, useContent } from '../../content.ts'
import { formatDateTime } from '../../lib/date.ts'
import { dueLabel } from '../../lib/srs.ts'
import { recordPractice, recordResult, selectProgress, useStore } from '../../lib/store.ts'
import type { Exercise, ExerciseProgress, Result } from '../../types.ts'
import { ErrorBoundary } from '../ErrorBoundary.tsx'
import { Icon } from '../Icon.tsx'
import { Link, PriorityBadge } from '../ui.tsx'
import { CodeBody } from './CodeExercise.tsx'
import { MultiBody, SingleBody } from './Choice.tsx'
import { RESULT_LABEL, TYPE_LABEL, progressSummary } from './common.tsx'
import { FillBody, PredictBody } from './FillPredict.tsx'
import { FlashBody } from './Flash.tsx'
import { OpenBody } from './Open.tsx'
import { OrderBody } from './Order.tsx'

function Body({ ex, report }: { ex: Exercise; report: (r: Result) => void }) {
  switch (ex.type) {
    case 'single':
      return <SingleBody ex={ex} report={report} />
    case 'multi':
      return <MultiBody ex={ex} report={report} />
    case 'fill':
      return <FillBody ex={ex} report={report} />
    case 'predict':
      return <PredictBody ex={ex} report={report} />
    case 'order':
      return <OrderBody ex={ex} report={report} />
    case 'code':
      return <CodeBody ex={ex} report={report} />
    case 'open':
      return <OpenBody ex={ex} report={report} />
    case 'flash':
      return <FlashBody ex={ex} report={report} />
  }
}

export function ExerciseCard({ ex, label, showUnit }: { ex: Exercise; label?: string; showUnit?: boolean }) {
  const index = useContent()
  const prior = useStore(selectProgress).exercises[ex.id]
  const [fresh, setFresh] = useState<ExerciseProgress | null>(null)
  const [practiceOnly, setPracticeOnly] = useState(false)
  // 這次掛載期間是否已經答錯過（看過正解）。之後的重試只算練習：記錄作答，但不升箱、不移出錯題本。
  // 重新進入（重新載入、SRS 到期、練習中心再抽到）才是新的一次作答。
  const failedHere = useRef(false)
  const report = useCallback(
    (r: Result) => {
      if (failedHere.current && r !== 'wrong') {
        recordPractice(ex.id)
        setFresh(null)
        setPracticeOnly(true)
        return
      }
      if (r === 'wrong') failedHere.current = true
      setPracticeOnly(false)
      setFresh(recordResult(ex.id, r))
    },
    [ex.id],
  )
  const ckey = exerciseKey(ex)
  const unit = index.unitById.get(ex.unitId)

  return (
    <article className="ex" id={`ex-${ex.id}`} data-type={ex.type} aria-label={`${label ?? ''} ${TYPE_LABEL[ex.type]}`}>
      <header className="ex-head">
        {label && <span className="ex-label num">{label}</span>}
        <span className="ex-type">{TYPE_LABEL[ex.type]}</span>
        <span className="ex-level" title={`難度 ${ex.level}（1 熟悉／2 資深／3 深挖）`}>
          {'●'.repeat(ex.level)}
          <span className="ex-level-off">{'●'.repeat(3 - ex.level)}</span>
        </span>
        <PriorityBadge priority={ex.priority} />
        {ex.tags.map((t) => (
          <span key={t} className="tag">
            {t}
          </span>
        ))}
        <span className={`ex-status ${prior ? `is-${prior.last}` : ''}`}>{progressSummary(prior)}</span>
      </header>
      {showUnit && unit && (
        <div className="ex-unit">
          <Link to={unit.id}>{unit.title}</Link>
        </div>
      )}
      <div className="ex-body">
        <ErrorBoundary key={ckey} what="這題">
          <Body ex={ex} report={report} />
        </ErrorBoundary>
      </div>
      {practiceOnly && (
        <footer className="ex-foot" role="status" aria-live="polite">
          <Icon name="check" size={14} />
          練習：這題這次已經答錯過，重試只記錄作答次數，不升箱、也不會移出錯題本。下次重新進入再答對才算數。
        </footer>
      )}
      {fresh && (
        <footer className="ex-foot" role="status" aria-live="polite">
          <Icon name="check" size={14} />
          已記錄：{RESULT_LABEL[fresh.last]}・第 {fresh.box} 箱・{dueLabel(fresh)}
          <span className="muted small num">（{formatDateTime(fresh.at)}）</span>
        </footer>
      )}
    </article>
  )
}
