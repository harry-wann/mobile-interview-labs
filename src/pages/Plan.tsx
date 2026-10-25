import { useContent } from '../content.ts'
import { PlanTasks } from '../components/PlanTasks.tsx'
import { currentPlanDay } from '../lib/plan.ts'
import { formatMD, todayStr } from '../lib/date.ts'
import { selectProgress, useStore } from '../lib/store.ts'

export function Plan() {
  const { content } = useContent()
  const progress = useStore(selectProgress)
  const today = todayStr()
  const cur = currentPlanDay(content.plan, today)
  return (
    <div className="page">
      <h1>7 日衝刺計畫</h1>
      <p className="lead">勾選任務會自動儲存。連結可直接跳到對應的區段或單元。</p>
      {content.plan.length === 0 && <p className="empty">沒有設定計畫。要用 7 日計畫，在 content/plan/days.yaml 寫每天的任務（格式見 content/SCHEMA.md）。</p>}
      <div className="plan-days">
        {content.plan.map((d) => {
          const done = d.tasks.filter((t) => progress.plan[t.id]?.done).length
          const isToday = cur?.phase === 'during' && cur.day.day === d.day
          return (
            <section key={d.day} className={`plan-day ${isToday ? 'is-today' : ''}`} aria-labelledby={`day-${d.day}`}>
              <header>
                <h2 id={`day-${d.day}`}>
                  <span className="plan-day-n num">Day {d.day}</span>
                  <span className="plan-day-date num">{formatMD(d.date)}</span>
                  {d.title}
                  {isToday && <span className="badge badge-p0">今天</span>}
                </h2>
                <span className="num muted small">
                  {done}/{d.tasks.length}
                </span>
              </header>
              <p className="goal">{d.goal}</p>
              <PlanTasks tasks={d.tasks} />
            </section>
          )
        })}
      </div>
    </div>
  )
}
