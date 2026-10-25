import { useMemo } from 'react'
import { useContent } from '../content.ts'
import { PlanTasks } from '../components/PlanTasks.tsx'
import { Link, ProgressBar } from '../components/ui.tsx'
import { INTERVIEW_DATE, daysBetween, formatMD, todayStr } from '../lib/date.ts'
import { currentPlanDay } from '../lib/plan.ts'
import { dueExercises, pendingFacts, sectionStats, wrongExercises } from '../lib/stats.ts'
import { selectAnswers, selectProgress, useStore } from '../lib/store.ts'

export function Home() {
  const index = useContent()
  const progress = useStore(selectProgress)
  const answers = useStore(selectAnswers)
  const today = todayStr()
  const days = INTERVIEW_DATE ? daysBetween(today, INTERVIEW_DATE) : null
  const cur = currentPlanDay(index.content.plan, today)
  const due = useMemo(() => dueExercises(index, progress, today), [index, progress, today])
  const wrong = useMemo(() => wrongExercises(index, progress), [index, progress])
  const facts = useMemo(() => pendingFacts(index, answers), [index, answers])
  const factsCount = facts.reduce((n, f) => n + f.missing.length, 0)
  const stats = useMemo(() => index.sections.map((s) => sectionStats(index, s, progress)), [index, progress])
  const totalUnits = index.unitList.length
  const readUnits = index.unitList.filter((u) => progress.units[u.id]?.read).length
  const totalEx = index.content.exercises.length
  const correctEx = index.content.exercises.filter((e) => progress.exercises[e.id]?.last === 'correct').length
  const tasksDone = cur ? cur.day.tasks.filter((t) => progress.plan[t.id]?.done).length : 0

  return (
    <div className="page home">
      <section className="hero" aria-label="倒數">
        <div className="hero-count">
          <span className="hero-num num">{days === null ? '' : days > 0 ? days : days === 0 ? 'Day' : '—'}</span>
          <span className="hero-unit">{days === null ? '' : days > 0 ? '天' : days === 0 ? '0' : ''}</span>
        </div>
        <div className="hero-text">
          <h1>{days === null ? 'Mobile 面試複習站：交易所 App 的 iOS／Android 技術' : days > 0 ? `距離 ${formatMD(INTERVIEW_DATE)} 面試還有 ${days} 天` : days === 0 ? `${formatMD(INTERVIEW_DATE)} 面試就是今天` : `${formatMD(INTERVIEW_DATE)} 面試已經過了`}</h1>
          {cur && (
            <p className="lead">
              {cur.phase === 'before' && `今天是 ${formatMD(today)}，計畫從 ${formatMD(cur.day.date)} 開始。先預習第 ${cur.day.day} 天的內容。`}
              {cur.phase === 'during' && `今天是計畫第 ${cur.day.day} 天（${formatMD(cur.day.date)}）：${cur.day.title}`}
              {cur.phase === 'after' && `7 日計畫已結束（最後一天是 ${formatMD(cur.day.date)}）。`}
            </p>
          )}
        </div>
      </section>

      <section className="home-stats" aria-label="今日待辦">
        <Link to="practice" state={{ practice: { dueOnly: true } }} className="stat">
          <span className="stat-num num">{due.length}</span>
          <span className="stat-label">SRS 到期題</span>
          <span className="stat-hint">{due.length > 0 ? '開始複習' : '目前沒有到期的題目'}</span>
        </Link>
        <Link to="wrong" className="stat">
          <span className={`stat-num num ${wrong.length ? 'text-bad' : ''}`}>{wrong.length}</span>
          <span className="stat-label">錯題</span>
          <span className="stat-hint">{wrong.length > 0 ? '打開錯題本' : '全部答對'}</span>
        </Link>
        <Link to="answers" className="stat">
          <span className={`stat-num num ${factsCount ? 'text-warn' : ''}`}>{factsCount}</span>
          <span className="stat-label">只有你知道的事（待填）</span>
          <span className="stat-hint">{factsCount > 0 ? `${facts.length} 題還有空格要填` : '都填好了'}</span>
        </Link>
        <div className="stat stat-static">
          <span className="stat-num num">
            {readUnits}/{totalUnits}
          </span>
          <span className="stat-label">已讀單元</span>
          <span className="stat-hint num">
            答對 {correctEx}/{totalEx} 題
          </span>
        </div>
      </section>

      {cur && (
      <section aria-labelledby="today-h" className="home-block">
        <div className="block-head">
          <h2 id="today-h">{cur ? (cur.phase === 'before' ? `第 ${cur.day.day} 天任務（預習）` : `今天的任務：第 ${cur.day.day} 天`) : '今天的任務'}</h2>
          {cur && (
            <span className="muted num">
              {tasksDone}/{cur.day.tasks.length} 完成　<Link to="plan">看完整計畫</Link>
            </span>
          )}
        </div>
        {cur ? (
          <>
            <p className="goal">{cur.day.goal}</p>
            <PlanTasks tasks={cur.day.tasks} />
          </>
        ) : null}
      </section>
      )}

      <section aria-labelledby="quick-h" className="home-block">
        <h2 id="quick-h">快速入口</h2>
        <div className="quick">
          <Link to="practice" className="btn btn-primary">
            練習中心
          </Link>
          <Link to="mock" className="btn btn-primary">
            模擬面試
          </Link>
          <Link to="answers" className="btn">
            我的口徑
          </Link>
          {index.content.plan.length > 0 && (
            <Link to="plan" className="btn">
              7 日計畫
            </Link>
          )}
        </div>
      </section>

      <section aria-labelledby="prog-h" className="home-block">
        <h2 id="prog-h">各區段進度</h2>
        <ul className="sec-progress">
          {stats.map((st) => (
            <li key={st.section.id}>
              <Link to={`sec-${st.section.id}`} className="sec-row">
                <span className="sec-name">{st.section.title}</span>
                <span className="sec-bars">
                  <span className="sec-bar">
                    <ProgressBar value={st.read} max={st.units.length} label={`${st.section.short} 已讀`} />
                    <span className="num small muted">
                      已讀 {st.read}/{st.units.length}
                    </span>
                  </span>
                  <span className="sec-bar">
                    <ProgressBar value={st.exercises.correct} max={st.exercises.total} label={`${st.section.short} 答對`} tone="ok" />
                    <span className="num small muted">
                      答對 {st.exercises.correct}/{st.exercises.total}
                    </span>
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
