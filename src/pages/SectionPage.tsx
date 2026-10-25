import { useContent } from '../content.ts'
import { PriorityBadge, ProgressBar, Link } from '../components/ui.tsx'
import { sectionStats, unitCounts } from '../lib/stats.ts'
import { selectProgress, useStore } from '../lib/store.ts'
import { NotFound } from './misc.tsx'

export function SectionPage({ sectionId }: { sectionId: string }) {
  const index = useContent()
  const progress = useStore(selectProgress)
  const section = index.sectionById.get(sectionId)
  if (!section) return <NotFound what={`區段「${sectionId}」`} />
  const st = sectionStats(index, section, progress)
  return (
    <div className="page">
      <h1>{section.title}</h1>
      <p className="lead">{section.description}</p>
      <div className="stat-line">
        <span className="num">
          已讀 {st.read}/{st.units.length} 單元
        </span>
        <span className="num">
          答對 {st.exercises.correct}/{st.exercises.total} 題
        </span>
        <Link to="practice" state={{ practice: { section: section.id } }} className="btn btn-sm">
          練習這區的題目
        </Link>
      </div>
      <ProgressBar value={st.exercises.correct} max={st.exercises.total} label="答對進度" tone="ok" />
      {st.units.length === 0 ? (
        <p className="empty">這個區段還沒有可用的單元（內容撰寫中，或有內容錯誤被略過）。</p>
      ) : (
        <ol className="unit-list">
          {st.units.map((u) => {
            const c = unitCounts(index, u, progress)
            const read = progress.units[u.id]?.read
            return (
              <li key={u.id}>
                <Link to={u.id} className="unit-row">
                  <div className="unit-row-main">
                    <div className="unit-row-title">
                      {u.title}
                      {read && <span className="badge badge-ok">已讀</span>}
                    </div>
                    <div className="unit-row-sum">{u.summary}</div>
                  </div>
                  <div className="unit-row-side">
                    <PriorityBadge priority={u.priority} />
                    <span className="num muted small">
                      {c.correct}/{c.total} 題
                    </span>
                  </div>
                </Link>
              </li>
            )
          })}
        </ol>
      )}
    </div>
  )
}
