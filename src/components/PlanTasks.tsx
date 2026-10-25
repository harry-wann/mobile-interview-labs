import { useContent } from '../content.ts'
import { setPlanDone, selectProgress, useStore } from '../lib/store.ts'
import type { PlanTask } from '../types.ts'
import { Link } from './ui.tsx'

export function PlanTasks({ tasks }: { tasks: PlanTask[] }) {
  const index = useContent()
  const progress = useStore(selectProgress)
  return (
    <ul className="tasks">
      {tasks.map((t) => {
        const done = !!progress.plan[t.id]?.done
        const id = `task-${t.id}`
        return (
          <li key={t.id} className={done ? 'is-done' : ''}>
            <input id={id} type="checkbox" checked={done} onChange={(e) => setPlanDone(t.id, e.target.checked)} />
            <label htmlFor={id}>{t.label}</label>
            {t.links.length > 0 && (
              <span className="task-links">
                {t.links.map((l) => {
                  const unit = index.unitById.get(l)
                  const sec = index.sectionById.get(l)
                  if (unit) return <Link key={l} to={unit.id}>{unit.title}</Link>
                  if (sec) return <Link key={l} to={`sec-${sec.id}`}>{sec.short}</Link>
                  return (
                    <span key={l} className="muted" title="這個連結的內容還沒有（或有內容錯誤）">
                      {l}
                    </span>
                  )
                })}
              </span>
            )}
          </li>
        )
      })}
    </ul>
  )
}
