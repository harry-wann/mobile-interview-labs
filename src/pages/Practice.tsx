import { useEffect, useMemo, useState } from 'react'
import { useContent } from '../content.ts'
import { ExerciseCard } from '../components/exercises/ExerciseCard.tsx'
import { TYPE_LABEL } from '../components/exercises/common.tsx'
import { shuffle } from '../lib/grading.ts'
import { todayStr } from '../lib/date.ts'
import { consumeNavState, useNavVersion } from '../lib/router.ts'
import { isDue } from '../lib/srs.ts'
import { getState, selectProgress, useStore } from '../lib/store.ts'
import { getJSON, setJSON } from '../lib/storage.ts'
import type { Exercise, ExerciseType } from '../types.ts'

interface Filters {
  section: string
  type: string
  priority: string
  wrongOnly: boolean
  dueOnly: boolean
  unseenOnly: boolean
  order: 'random' | 'sequential'
}

const DEFAULTS: Filters = { section: 'all', type: 'all', priority: 'all', wrongOnly: false, dueOnly: false, unseenOnly: false, order: 'random' }
const KEY = 'bt.practice.filters'

function initialFilters(): Filters {
  const saved = getJSON(KEY)
  const base: Filters = { ...DEFAULTS, ...(typeof saved === 'object' && saved ? (saved as Partial<Filters>) : {}) }
  const preset = consumeNavState()?.practice
  if (preset) return { ...DEFAULTS, order: base.order, ...(preset as Partial<Filters>) }
  return base
}

export function matchesFilters(ex: Exercise, f: Filters, progress: ReturnType<typeof getState>['progress'], today: string): boolean {
  if (f.section !== 'all' && ex.sectionId !== f.section) return false
  if (f.type !== 'all' && ex.type !== f.type) return false
  if (f.priority !== 'all' && ex.priority !== f.priority) return false
  const p = progress.exercises[ex.id]
  if (f.wrongOnly && p?.last !== 'wrong') return false
  if (f.dueOnly && !isDue(p, today)) return false
  if (f.unseenOnly && p) return false
  return true
}

export function Practice() {
  const index = useContent()
  const progress = useStore(selectProgress)
  const [filters, setFilters] = useState<Filters>(initialFilters)
  const [seed, setSeed] = useState(0)
  const [pos, setPos] = useState(0)
  const today = todayStr()
  const navVersion = useNavVersion()

  // 已經在練習中心時，從別處帶著預設篩選（例如首頁的「SRS 到期題」）再導回來，也要套用
  useEffect(() => {
    const preset = consumeNavState()?.practice
    if (preset) setFilters((f) => ({ ...DEFAULTS, order: f.order, ...(preset as Partial<Filters>) }))
  }, [navVersion])

  useEffect(() => {
    setJSON(KEY, filters)
  }, [filters])

  // 題目清單在條件改變時才重抽；作答後不會讓目前這題消失（所以不隨 progress 重算）
  const queue = useMemo(() => {
    const p = getState().progress
    const list = index.content.exercises.filter((e) => matchesFilters(e, filters, p, today))
    return (filters.order === 'random' ? shuffle(list) : list).map((e) => e.id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, filters, seed, today])

  useEffect(() => setPos(0), [queue])

  const matchCount = useMemo(() => index.content.exercises.filter((e) => matchesFilters(e, filters, progress, today)).length, [index, filters, progress, today])
  const ex = index.exerciseById.get(queue[pos] ?? '')
  const set = <K extends keyof Filters>(k: K, v: Filters[K]) => setFilters((f) => ({ ...f, [k]: v }))

  const sectionsWithEx = index.sections.filter((s) => index.content.exercises.some((e) => e.sectionId === s.id))

  return (
    <div className="page">
      <h1>練習中心</h1>
      <form className="filters" onSubmit={(e) => e.preventDefault()} aria-label="篩選條件">
        <div className="filter">
          <label htmlFor="f-section">區段</label>
          <select id="f-section" value={filters.section} onChange={(e) => set('section', e.target.value)}>
            <option value="all">全部</option>
            {sectionsWithEx.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </select>
        </div>
        <div className="filter">
          <label htmlFor="f-type">題型</label>
          <select id="f-type" value={filters.type} onChange={(e) => set('type', e.target.value)}>
            <option value="all">全部</option>
            {(Object.keys(TYPE_LABEL) as ExerciseType[]).map((t) => (
              <option key={t} value={t}>
                {TYPE_LABEL[t]}
              </option>
            ))}
          </select>
        </div>
        <div className="filter">
          <label htmlFor="f-priority">優先度</label>
          <select id="f-priority" value={filters.priority} onChange={(e) => set('priority', e.target.value)}>
            <option value="all">全部</option>
            <option value="P0">P0 必會</option>
            <option value="P1">P1 很可能被問</option>
            <option value="P2">P2 加分</option>
          </select>
        </div>
        <div className="filter">
          <label htmlFor="f-order">順序</label>
          <select id="f-order" value={filters.order} onChange={(e) => set('order', e.target.value as Filters['order'])}>
            <option value="random">隨機</option>
            <option value="sequential">依單元順序</option>
          </select>
        </div>
        <div className="filter-checks">
          <label htmlFor="f-wrong">
            <input id="f-wrong" type="checkbox" checked={filters.wrongOnly} onChange={(e) => set('wrongOnly', e.target.checked)} /> 只看錯題
          </label>
          <label htmlFor="f-due">
            <input id="f-due" type="checkbox" checked={filters.dueOnly} onChange={(e) => set('dueOnly', e.target.checked)} /> 只看到期
          </label>
          <label htmlFor="f-unseen">
            <input id="f-unseen" type="checkbox" checked={filters.unseenOnly} onChange={(e) => set('unseenOnly', e.target.checked)} /> 只看沒做過
          </label>
        </div>
      </form>

      <div className="practice-bar">
        <span className="num muted">
          符合條件 {matchCount} 題{queue.length > 0 && `　目前第 ${Math.min(pos + 1, queue.length)} / ${queue.length} 題`}
        </span>
        <button type="button" className="btn btn-sm" onClick={() => setSeed((s) => s + 1)}>
          重新抽題
        </button>
      </div>

      {queue.length === 0 ? (
        <p className="empty">沒有符合條件的題目。放寬條件，或先去讀單元、做幾題。</p>
      ) : pos >= queue.length ? (
        <div className="empty">
          <p>這一輪 {queue.length} 題做完了。</p>
          <button type="button" className="btn btn-primary" onClick={() => { setSeed((s) => s + 1); setPos(0) }}>
            再來一輪
          </button>
        </div>
      ) : ex ? (
        <>
          <ExerciseCard key={ex.id} ex={ex} label={`${pos + 1}/${queue.length}`} showUnit />
          <div className="practice-nav">
            <button type="button" className="btn" disabled={pos === 0} onClick={() => setPos((p) => Math.max(0, p - 1))}>
              上一題
            </button>
            <button type="button" className="btn btn-primary" onClick={() => setPos((p) => p + 1)}>
              {pos + 1 >= queue.length ? '完成這一輪' : '下一題'}
            </button>
          </div>
        </>
      ) : null}
    </div>
  )
}
