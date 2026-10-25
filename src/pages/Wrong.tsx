import { useMemo, useState } from 'react'
import { useContent } from '../content.ts'
import { ExerciseCard } from '../components/exercises/ExerciseCard.tsx'
import { TYPE_LABEL } from '../components/exercises/common.tsx'
import { Link, PriorityBadge } from '../components/ui.tsx'
import { formatDateTime } from '../lib/date.ts'
import { stripTags, wrongExercises } from '../lib/stats.ts'
import { getState, selectProgress, useStore } from '../lib/store.ts'
import type { Exercise } from '../types.ts'

const title = (ex: Exercise) => {
  const raw = ex.type === 'flash' ? ex.frontHtml : ex.promptHtml
  const t = stripTags(raw)
  return t.length > 110 ? t.slice(0, 110) + '…' : t
}

export function Wrong() {
  const index = useContent()
  const progress = useStore(selectProgress)
  // 清單在進入頁面時固定：重做答對後這題仍留在畫面上（標示已答對），按「重新整理」才會移除
  const [snapshot, setSnapshot] = useState<string[]>(() =>
    wrongExercises(index, getState().progress)
      .sort((a, b) => (getState().progress.exercises[b.id]?.at ?? 0) - (getState().progress.exercises[a.id]?.at ?? 0))
      .map((e) => e.id),
  )
  const [open, setOpen] = useState<string | null>(null)
  const items = useMemo(() => snapshot.map((id) => index.exerciseById.get(id)).filter((e): e is Exercise => !!e), [snapshot, index])
  const stillWrong = items.filter((e) => progress.exercises[e.id]?.last === 'wrong').length

  return (
    <div className="page">
      <h1>錯題本</h1>
      <p className="lead">
        目前答錯、還沒答對過的題目。展開後重做，答對就會從錯題本移除。
      </p>
      <div className="practice-bar">
        <span className="num muted">
          還有 {stillWrong} 題錯題{items.length !== stillWrong && `（本頁共 ${items.length} 題，已答對 ${items.length - stillWrong} 題）`}
        </span>
        <span className="row-gap">
          <Link to="practice" state={{ practice: { wrongOnly: true, order: 'random' } }} className="btn btn-sm btn-primary">
            逐題複習錯題
          </Link>
          <button
            type="button"
            className="btn btn-sm"
            onClick={() => {
              setSnapshot(wrongExercises(index, getState().progress).map((e) => e.id))
              setOpen(null)
            }}
          >
            重新整理清單
          </button>
        </span>
      </div>
      {items.length === 0 ? (
        <p className="empty">沒有錯題。去練習中心做幾題吧。</p>
      ) : (
        <ul className="wrong-list">
          {items.map((ex) => {
            const p = progress.exercises[ex.id]
            const fixed = p?.last !== 'wrong'
            return (
              <li key={ex.id} className={fixed ? 'is-fixed' : ''}>
                <div className="wrong-row">
                  <div className="wrong-main">
                    <div className="wrong-title">{title(ex)}</div>
                    <div className="wrong-meta muted small">
                      <span>{TYPE_LABEL[ex.type]}</span>
                      <PriorityBadge priority={ex.priority} />
                      <Link to={ex.unitId}>{index.unitById.get(ex.unitId)?.title ?? ex.unitId}</Link>
                      {p && (
                        <span className="num">
                          {p.correct}/{p.attempts} 次答對・{formatDateTime(p.at)}
                        </span>
                      )}
                      {fixed && <span className="badge badge-ok">已答對</span>}
                    </div>
                  </div>
                  <button type="button" className="btn btn-sm" aria-expanded={open === ex.id} onClick={() => setOpen(open === ex.id ? null : ex.id)}>
                    {open === ex.id ? '收合' : '重做'}
                  </button>
                </div>
                {open === ex.id && <ExerciseCard ex={ex} />}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
