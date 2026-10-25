import { useEffect, useMemo, useRef } from 'react'
import { useContent } from '../content.ts'
import { Icon } from '../components/Icon.tsx'
import { ExerciseCard } from '../components/exercises/ExerciseCard.tsx'
import { Html, Link, PriorityBadge } from '../components/ui.tsx'
import { consumeNavState, useNavVersion } from '../lib/router.ts'
import { setUnitRead, selectProgress, useStore } from '../lib/store.ts'
import { unitCounts } from '../lib/stats.ts'
import { NotFound } from './misc.tsx'

const smooth = () => (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth') as ScrollBehavior

export function UnitPage({ unitId }: { unitId: string }) {
  const index = useContent()
  const unit = index.unitById.get(unitId)
  const progress = useStore(selectProgress)
  const practiceRef = useRef<HTMLElement>(null)
  const navVersion = useNavVersion()

  // 搜尋結果點到題目：跳到該題。依 navVersion 重跑，所以停在同一單元時再點另一題也會捲動
  useEffect(() => {
    const st = consumeNavState()
    if (!st?.focus) return
    const id = `ex-${st.focus}`
    let clear: ReturnType<typeof setTimeout> | undefined
    const t = setTimeout(() => {
      document.querySelectorAll('.ex.is-focus').forEach((e) => e.classList.remove('is-focus'))
      const el = document.getElementById(id)
      el?.scrollIntoView({ behavior: smooth(), block: 'start' })
      el?.classList.add('is-focus')
      clear = setTimeout(() => el?.classList.remove('is-focus'), 4000)
    }, 60)
    return () => {
      clearTimeout(t)
      clearTimeout(clear)
    }
  }, [unitId, navVersion])

  const exercises = useMemo(() => (unit ? (index.exercisesByUnit.get(unit.id) ?? []) : []), [index, unit])
  if (!unit) return <NotFound what={`單元「${unitId}」`} />

  const read = !!progress.units[unit.id]?.read
  const counts = unitCounts(index, unit, progress)
  const section = index.sectionById.get(unit.sectionId)
  const prev = index.prevUnit.get(unit.id)
  const next = index.nextUnit.get(unit.id)

  const scrollTo = (id: string) => document.getElementById(id)?.scrollIntoView({ behavior: smooth(), block: 'start' })

  return (
    <article className="page unit">
      <nav className="crumbs" aria-label="路徑">
        <Link to="home">首頁</Link>
        <span aria-hidden="true">/</span>
        {section && <Link to={`sec-${section.id}`}>{section.title}</Link>}
      </nav>
      <header className="unit-head">
        <h1>{unit.title}</h1>
        <p className="lead">{unit.summary}</p>
        <div className="meta-row">
          <PriorityBadge priority={unit.priority} />
          {unit.day && <span className="badge">計畫第 {unit.day} 天</span>}
          {unit.tags.map((t) => (
            <span key={t} className="tag">
              {t}
            </span>
          ))}
        </div>
        <div className="unit-actions">
          <button type="button" className={`btn btn-sm ${read ? 'btn-ok' : 'btn-primary'}`} aria-pressed={read} onClick={() => setUnitRead(unit.id, !read)}>
            <Icon name="check" size={14} />
            {read ? '已讀（點一下取消）' : '標記為已讀'}
          </button>
          {exercises.length > 0 && (
            <button type="button" className="btn btn-sm" onClick={() => practiceRef.current?.scrollIntoView({ behavior: smooth(), block: 'start' })}>
              跳到練習（{counts.correct}/{counts.total} 答對）
            </button>
          )}
        </div>
        {unit.toc.length > 0 && (
          <nav className="toc" aria-label="本頁目錄">
            <div className="toc-title">本頁目錄</div>
            <ul>
              {unit.toc.map((t) => (
                <li key={t.id} className={`toc-l${t.level}`}>
                  <a
                    href={`#${unit.id}`}
                    onClick={(e) => {
                      e.preventDefault()
                      scrollTo(t.id)
                    }}
                  >
                    {t.text}
                  </a>
                </li>
              ))}
            </ul>
          </nav>
        )}
      </header>

      <Html className="prose unit-body" html={unit.html} />

      <div className="read-foot">
        <button type="button" className={`btn ${read ? 'btn-ok' : 'btn-primary'}`} aria-pressed={read} onClick={() => setUnitRead(unit.id, !read)}>
          <Icon name="check" size={16} />
          {read ? '已讀（點一下取消）' : '標記為已讀'}
        </button>
      </div>

      <section ref={practiceRef} className="practice-area" aria-labelledby="practice-h">
        <div className="practice-head">
          <h2 id="practice-h">練習</h2>
          <span className="muted num">
            {exercises.length} 題・已作答 {counts.answered}・答對 {counts.correct}
          </span>
        </div>
        <div className="ex-list">
          {exercises.map((ex, i) => (
            <ExerciseCard key={ex.id} ex={ex} label={`Q${i + 1}`} />
          ))}
        </div>
      </section>

      <nav className="unit-nav" aria-label="上一單元／下一單元">
        {prev ? (
          <Link to={prev.id} className="unit-nav-prev">
            <span className="unit-nav-dir">
              <Icon name="arrowLeft" size={14} /> 上一單元
            </span>
            <span className="unit-nav-title">{prev.title}</span>
          </Link>
        ) : (
          <span />
        )}
        {next ? (
          <Link to={next.id} className="unit-nav-next">
            <span className="unit-nav-dir">
              下一單元 <Icon name="arrowRight" size={14} />
            </span>
            <span className="unit-nav-title">{next.title}</span>
          </Link>
        ) : (
          <span />
        )}
      </nav>
    </article>
  )
}
