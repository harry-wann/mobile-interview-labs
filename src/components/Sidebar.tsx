import { useEffect, useMemo, useState } from 'react'
import { useContent } from '../content.ts'
import { todayStr } from '../lib/date.ts'
import type { Route } from '../lib/router.ts'
import { sectionStats, dueExercises, wrongExercises, unitCounts } from '../lib/stats.ts'
import { selectProgress, useStore } from '../lib/store.ts'
import { Icon } from './Icon.tsx'
import { Link } from './ui.tsx'

const NAV: Array<{ to: string; label: string; name: Route['name'] }> = [
  { to: 'home', label: '首頁', name: 'home' },
  { to: 'plan', label: '7 日計畫', name: 'plan' },
  { to: 'practice', label: '練習中心', name: 'practice' },
  { to: 'wrong', label: '錯題本', name: 'wrong' },
  { to: 'mock', label: '模擬面試', name: 'mock' },
  { to: 'answers', label: '我的口徑', name: 'answers' },
]

export function Sidebar({ route, open, onClose }: { route: Route; open: boolean; onClose: () => void }) {
  const index = useContent()
  const progress = useStore(selectProgress)
  const due = useMemo(() => dueExercises(index, progress, todayStr()).length, [index, progress])
  const nav = index.content.plan.length > 0 ? NAV : NAV.filter((n) => n.name !== 'plan')
  const wrong = useMemo(() => wrongExercises(index, progress).length, [index, progress])

  const activeSectionId = route.name === 'unit' ? index.unitById.get(route.id)?.sectionId : route.name === 'section' ? route.id : undefined
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(activeSectionId ? [activeSectionId] : []))
  useEffect(() => {
    if (activeSectionId) setExpanded((s) => (s.has(activeSectionId) ? s : new Set(s).add(activeSectionId)))
  }, [activeSectionId])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const toggle = (id: string) =>
    setExpanded((s) => {
      const n = new Set(s)
      if (n.has(id)) n.delete(id)
      else n.add(id)
      return n
    })

  const badgeFor = (name: Route['name']) => (name === 'practice' && due > 0 ? due : name === 'wrong' && wrong > 0 ? wrong : 0)

  return (
    <>
      {open && <button type="button" className="scrim" aria-label="關閉選單" onClick={onClose} />}
      <nav id="sidebar" className={`sidebar ${open ? 'is-open' : ''}`} aria-label="單元目錄">
        <ul className="sb-nav">
          {nav.map((n) => (
            <li key={n.to}>
              <Link to={n.to} className={route.name === n.name ? 'is-active' : ''} aria-current={route.name === n.name ? 'page' : undefined} onNavigate={onClose}>
                {n.label}
                {badgeFor(n.name) > 0 && <span className="sb-count num">{badgeFor(n.name)}</span>}
              </Link>
            </li>
          ))}
        </ul>
        <div className="sb-sections">
          {index.sections.map((s) => {
            const st = sectionStats(index, s, progress)
            const isOpen = expanded.has(s.id)
            const isActive = route.name === 'section' && route.id === s.id
            return (
              <div key={s.id} className="sb-section">
                <div className={`sb-section-head ${isActive ? 'is-active' : ''}`}>
                  <button type="button" className="sb-toggle" aria-expanded={isOpen} aria-label={`${isOpen ? '收合' : '展開'} ${s.title}`} onClick={() => toggle(s.id)}>
                    <Icon name={isOpen ? 'chevronDown' : 'chevronRight'} size={14} />
                  </button>
                  <Link to={`sec-${s.id}`} className="sb-section-link" onNavigate={onClose}>
                    <span className="sb-section-title">{s.title}</span>
                    <span className="sb-frac num" title="已讀單元／答對題數">
                      {st.units.length > 0 ? `${st.read}/${st.units.length}` : '—'}
                    </span>
                  </Link>
                </div>
                {isOpen && (
                  <ul className="sb-units">
                    {st.units.length === 0 && <li className="sb-empty">尚無單元</li>}
                    {st.units.map((u) => {
                      const c = unitCounts(index, u, progress)
                      const read = progress.units[u.id]?.read
                      const activeUnit = route.name === 'unit' && route.id === u.id
                      return (
                        <li key={u.id}>
                          <Link to={u.id} className={`sb-unit ${activeUnit ? 'is-active' : ''}`} aria-current={activeUnit ? 'page' : undefined} onNavigate={onClose}>
                            <span className={`sb-read ${read ? 'is-read' : ''}`} aria-label={read ? '已讀' : '未讀'}>
                              {read && <Icon name="check" size={12} />}
                            </span>
                            <span className="sb-unit-title">{u.title}</span>
                            <span className="sb-meta">
                              <span className={`pri pri-${u.priority.toLowerCase()}`}>{u.priority}</span>
                              <span className="num" title="答對／題數">
                                {c.correct}/{c.total}
                              </span>
                            </span>
                          </Link>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>
            )
          })}
        </div>
      </nav>
    </>
  )
}
