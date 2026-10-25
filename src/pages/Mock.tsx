import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useContent } from '../content.ts'
import { AnswerBox } from '../components/AnswerBox.tsx'
import { GradePanel } from '../components/exercises/GradePanel.tsx'
import { GradeStatus } from '../components/exercises/GradeStatus.tsx'
import { Html, ProgressBar, Segmented, ConfirmButton } from '../components/ui.tsx'
import { useGrader } from '../lib/grader.ts'
import { formatDateTime, formatDuration } from '../lib/date.ts'
import { MAX_TEXT } from '../lib/data.ts'
import { shuffle } from '../lib/grading.ts'
import { onPageHide } from '../lib/lifecycle.ts'
import { getJSON, removeItem, setJSON } from '../lib/storage.ts'
import { stripTags } from '../lib/stats.ts'
import { getState, recordResult, saveMockSession, selectProgress, useStore } from '../lib/store.ts'
import type { MockItem, MockSession, OpenExercise, StoredGrade } from '../types.ts'

const ACTIVE_KEY = 'bt.mock.active'
const SETUP_KEY = 'bt.mock.setup'

interface Active {
  id: string
  startedAt: number
  ids: string[]
  idx: number
  answers: string[]
  seconds: number[]
  /** 目前這題已經用掉的秒數 */
  elapsed: number
}

interface Setup {
  count: number
  section: string
  priority: string
  unseenFirst: boolean
}
const DEFAULT_SETUP: Setup = { count: 8, section: 'all', priority: 'all', unseenFirst: true }

function loadActive(): Active | null {
  const raw = getJSON(ACTIVE_KEY) as Active | null
  if (!raw || !Array.isArray(raw.ids) || !Array.isArray(raw.answers) || typeof raw.idx !== 'number') return null
  return raw
}

const limitOf = (ex: OpenExercise) => ex.timeLimitSec || 120

export function Mock() {
  const index = useContent()
  const [phase, setPhase] = useState<'setup' | 'run' | 'review'>('setup')
  const [active, setActive] = useState<Active | null>(loadActive)
  const [session, setSession] = useState<MockSession | null>(null)

  const openList = useMemo(() => index.content.exercises.filter((e): e is OpenExercise => e.type === 'open'), [index])

  const startNew = (setup: Setup) => {
    const p = getState().progress
    let pool = openList.filter((e) => (setup.section === 'all' || e.sectionId === setup.section) && (setup.priority === 'all' || e.priority === setup.priority))
    pool = shuffle(pool)
    if (setup.unseenFirst) pool = [...pool.filter((e) => !p.exercises[e.id]), ...pool.filter((e) => p.exercises[e.id])]
    const picked = pool.slice(0, Math.max(1, Math.min(setup.count, pool.length)))
    if (picked.length === 0) return false
    const a: Active = {
      id: `mock-${Date.now().toString(36)}`,
      startedAt: Date.now(),
      ids: picked.map((e) => e.id),
      idx: 0,
      answers: picked.map(() => ''),
      seconds: picked.map(() => 0),
      elapsed: 0,
    }
    setJSON(ACTIVE_KEY, a)
    setActive(a)
    setPhase('run')
    return true
  }

  const finish = (a: Active, answers: string[], seconds: number[]) => {
    const items: MockItem[] = a.ids.map((id, i) => ({ exerciseId: id, answer: answers[i] ?? '', seconds: seconds[i] ?? 0 }))
    const s: MockSession = { id: a.id, startedAt: a.startedAt, finishedAt: Date.now(), updatedAt: Date.now(), items }
    saveMockSession(s)
    removeItem(ACTIVE_KEY)
    setActive(null)
    setSession(s)
    setPhase('review')
  }

  if (phase === 'run' && active) {
    return (
      <div className="page">
        <MockRun
          key={active.id}
          initial={active}
          openById={(id) => index.exerciseById.get(id) as OpenExercise | undefined}
          onFinish={finish}
          onAbort={() => {
            removeItem(ACTIVE_KEY)
            setActive(null)
            setPhase('setup')
          }}
        />
      </div>
    )
  }
  if (phase === 'review' && session) {
    return (
      <div className="page">
        <MockReview key={session.id} initial={session} onBack={() => setPhase('setup')} />
      </div>
    )
  }
  return (
    <div className="page">
      <MockSetup
        openList={openList}
        resumable={active}
        onStart={startNew}
        onResume={() => setPhase('run')}
        onDiscard={() => {
          removeItem(ACTIVE_KEY)
          setActive(null)
        }}
        onOpenSession={(s) => {
          setSession(s)
          setPhase('review')
        }}
      />
    </div>
  )
}


// ───────────────── 設定 ─────────────────

function MockSetup({
  openList,
  resumable,
  onStart,
  onResume,
  onDiscard,
  onOpenSession,
}: {
  openList: OpenExercise[]
  resumable: Active | null
  onStart: (s: Setup) => boolean
  onResume: () => void
  onDiscard: () => void
  onOpenSession: (s: MockSession) => void
}) {
  const index = useContent()
  const progress = useStore(selectProgress)
  const [setup, setSetup] = useState<Setup>(() => ({ ...DEFAULT_SETUP, ...((getJSON(SETUP_KEY) as Partial<Setup> | null) ?? {}) }))
  const [error, setError] = useState('')
  const sections = index.sections.filter((s) => openList.some((e) => e.sectionId === s.id))
  const matching = openList.filter((e) => (setup.section === 'all' || e.sectionId === setup.section) && (setup.priority === 'all' || e.priority === setup.priority)).length
  const sessions = [...progress.mock].sort((a, b) => b.finishedAt - a.finishedAt).slice(0, 8)

  return (
    <>
      <h1>模擬面試</h1>
      <p className="lead">從口說題（open）抽題，每題倒數計時，用講的或打字作答。全部結束後逐題對照要點，本機版可請 AI 面試官評分。</p>

      {resumable && (
        <div className="note-inline resume-note" role="status">
          <span>
            有一場進行到第 {resumable.idx + 1}/{resumable.ids.length} 題的模擬面試。
          </span>
          <button type="button" className="btn btn-sm btn-primary" onClick={onResume}>
            繼續
          </button>
          <ConfirmButton label="放棄" confirmLabel="放棄這場" message="放棄後作答內容會消失。" onConfirm={onDiscard} />
        </div>
      )}

      <form
        className="filters"
        onSubmit={(e) => {
          e.preventDefault()
          setJSON(SETUP_KEY, setup)
          setError(onStart(setup) ? '' : '沒有符合條件的口說題')
        }}
      >
        <div className="filter">
          <label htmlFor="m-count">題數</label>
          <input id="m-count" type="number" min={1} max={30} value={setup.count} onChange={(e) => setSetup((s) => ({ ...s, count: Math.max(1, Math.min(30, Number(e.target.value) || 1)) }))} />
        </div>
        <div className="filter">
          <label htmlFor="m-section">區段</label>
          <select id="m-section" value={setup.section} onChange={(e) => setSetup((s) => ({ ...s, section: e.target.value }))}>
            <option value="all">全部</option>
            {sections.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </select>
        </div>
        <div className="filter">
          <label htmlFor="m-priority">優先度</label>
          <select id="m-priority" value={setup.priority} onChange={(e) => setSetup((s) => ({ ...s, priority: e.target.value }))}>
            <option value="all">全部</option>
            <option value="P0">P0</option>
            <option value="P1">P1</option>
            <option value="P2">P2</option>
          </select>
        </div>
        <div className="filter-checks">
          <label htmlFor="m-unseen">
            <input id="m-unseen" type="checkbox" checked={setup.unseenFirst} onChange={(e) => setSetup((s) => ({ ...s, unseenFirst: e.target.checked }))} /> 優先抽沒練過的題
          </label>
        </div>
        <div className="filter filter-submit">
          <button type="submit" className="btn btn-primary" disabled={matching === 0}>
            開始（{Math.min(setup.count, matching)} 題）
          </button>
        </div>
      </form>
      <p className="muted small num">符合條件的口說題共 {matching} 題；每題時間用題目的 timeLimitSec（預設 120 秒）。</p>
      {error && <p className="text-bad">{error}</p>}

      {sessions.length > 0 && (
        <section className="home-block" aria-labelledby="past-h">
          <h2 id="past-h">過去的模擬面試</h2>
          <ul className="past-list">
            {sessions.map((s) => {
              const self = s.items.filter((i) => i.selfScore)
              const ai = s.items.filter((i) => i.aiScore)
              const avg = (xs: MockItem[], k: 'selfScore' | 'aiScore') => (xs.reduce((n, i) => n + (i[k] ?? 0), 0) / xs.length).toFixed(1)
              return (
                <li key={s.id}>
                  <button type="button" className="past-row" onClick={() => onOpenSession(s)}>
                    <span className="num">{formatDateTime(s.finishedAt)}</span>
                    <span className="num">{s.items.length} 題</span>
                    <span className="num muted">用時 {formatDuration(s.items.reduce((n, i) => n + i.seconds, 0))}</span>
                    <span className="num">{self.length ? `自評平均 ${avg(self, 'selfScore')}` : '尚未自評'}</span>
                    {ai.length > 0 && <span className="num">AI 平均 {avg(ai, 'aiScore')}</span>}
                  </button>
                </li>
              )
            })}
          </ul>
        </section>
      )}
    </>
  )
}

// ───────────────── 作答 ─────────────────

function MockRun({
  initial,
  openById,
  onFinish,
  onAbort,
}: {
  initial: Active
  openById: (id: string) => OpenExercise | undefined
  onFinish: (a: Active, answers: string[], seconds: number[]) => void
  onAbort: () => void
}) {
  const [idx, setIdx] = useState(initial.idx)
  const [answers, setAnswers] = useState<string[]>(initial.answers)
  const [seconds, setSeconds] = useState<number[]>(initial.seconds)
  const [elapsed, setElapsed] = useState(initial.elapsed)
  const [lang, setLang] = useState<'zh' | 'ja'>('zh')
  const doneRef = useRef(false) // 已經結束／放棄的場次，卸載時不要再寫回 localStorage
  const ex = openById(initial.ids[idx])
  const total = initial.ids.length
  const limit = ex ? limitOf(ex) : 120

  // 每題計時（切回前景才計；背景分頁的 setInterval 會被瀏覽器降頻，所以用實際時間差）
  const last = useRef(Date.now())
  useEffect(() => {
    last.current = Date.now()
    const t = setInterval(() => {
      const now = Date.now()
      const d = (now - last.current) / 1000
      last.current = now
      setElapsed((e) => e + d)
    }, 500)
    return () => clearInterval(t)
  }, [idx])

  // 進行中的狀態存起來，重新整理可以繼續
  const persist = useCallback(() => {
    if (doneRef.current) return
    // 連同「上次計時 tick 之後」的時間一起算進去，離開時才不會少掉最後半秒
    const liveElapsed = elapsed + (Date.now() - last.current) / 1000
    setJSON(ACTIVE_KEY, { ...initial, idx, answers, seconds, elapsed: Math.floor(liveElapsed) } satisfies Active)
  }, [initial, idx, answers, seconds, elapsed])
  const persistRef = useRef(persist)
  persistRef.current = persist
  useEffect(() => {
    const t = setTimeout(() => persistRef.current(), 600)
    return () => clearTimeout(t)
  }, [answers, idx])
  useEffect(() => {
    const t = setInterval(() => persistRef.current(), 5000)
    // 離開頁面（pagehide）與卸載（換頁）時立即存，不等 600ms 的 debounce
    const off = onPageHide(() => persistRef.current())
    return () => {
      clearInterval(t)
      off()
      persistRef.current()
    }
  }, [])

  const remaining = Math.round(limit - elapsed)
  const over = remaining < 0
  const next = () => {
    const nextSeconds = seconds.map((s, i) => (i === idx ? Math.round(elapsed) : s))
    setSeconds(nextSeconds)
    if (idx + 1 >= total) {
      doneRef.current = true
      onFinish(initial, answers, nextSeconds)
    } else {
      setIdx(idx + 1)
      setElapsed(0)
      setLang('zh')
    }
  }
  const endNow = () => {
    const nextSeconds = seconds.map((s, i) => (i === idx ? Math.round(elapsed) : s))
    doneRef.current = true
    onFinish(initial, answers, nextSeconds)
  }
  const abort = () => {
    doneRef.current = true
    onAbort()
  }

  if (!ex) {
    return (
      <div className="empty">
        <p>這一題已經不存在（內容更新過）。</p>
        <button type="button" className="btn" onClick={next}>
          跳過
        </button>
      </div>
    )
  }
  const hasJa = !!ex.promptJaHtml

  return (
    <>
      <div className="mock-bar">
        <div className="mock-q num">
          第 {idx + 1} / {total} 題
        </div>
        <div className={`mock-timer num ${over ? 'is-over' : remaining <= 20 ? 'is-low' : ''}`} role="timer" aria-live="off">
          {over ? `+${formatDuration(-remaining)} 超時` : formatDuration(remaining)}
          <span className="muted small"> / {formatDuration(limit)}</span>
        </div>
      </div>
      <ProgressBar value={Math.min(elapsed, limit)} max={limit} label="本題剩餘時間" tone={over || remaining <= 20 ? 'primary' : 'ok'} />

      <div className="mock-question">
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
        {lang === 'ja' && ex.promptJaHtml ? <Html className="prose ex-prompt" lang="ja" html={ex.promptJaHtml} /> : <Html className="prose ex-prompt" html={ex.promptHtml} />}
      </div>

      <AnswerBox
        key={ex.id}
        id={`mock-answer-${idx}`}
        label="你的回答（用講的或打字）"
        value={answers[idx] ?? ''}
        onChange={(v) => setAnswers((a) => a.map((x, i) => (i === idx ? v : x)))}
        lang={lang}
        rows={9}
        maxChars={MAX_TEXT}
        placeholder="像在面試中一樣回答。計時結束不會強制跳題。"
      />
      <div className="ex-actions">
        <button type="button" className="btn btn-primary" onClick={next}>
          {idx + 1 >= total ? '完成，看結果' : '下一題'}
        </button>
        <ConfirmButton label="提早結束" confirmLabel="結束並看結果" message="後面的題目會留白。" onConfirm={endNow} />
        <ConfirmButton label="放棄這場" confirmLabel="放棄" message="這場作答不會被儲存。" onConfirm={abort} />
      </div>
    </>
  )
}

// ───────────────── 結果 ─────────────────

function MockReview({ initial, onBack }: { initial: MockSession; onBack: () => void }) {
  const index = useContent()
  const [session, setSession] = useState(initial)
  const [rated, setRated] = useState<Set<string>>(() => new Set())

  // 最新的 session 放在 ref：副作用（saveMockSession）不能寫在 setState 的 updater 裡（updater 必須是純函式）
  const sessionRef = useRef(initial)
  const update = (exerciseId: string, patch: Partial<MockItem>, record?: 'correct' | 'partial' | 'wrong') => {
    const s = sessionRef.current
    const next = { ...s, updatedAt: Date.now(), items: s.items.map((i) => (i.exerciseId === exerciseId ? { ...i, ...patch } : i)) }
    sessionRef.current = next
    setSession(next)
    saveMockSession(next)
    if (record && !rated.has(exerciseId)) {
      recordResult(exerciseId, record)
      setRated((r) => new Set(r).add(exerciseId))
    }
  }

  const self = session.items.filter((i) => i.selfScore)
  const totalSec = session.items.reduce((n, i) => n + i.seconds, 0)

  return (
    <>
      <h1>模擬面試結果</h1>
      <div className="stat-line">
        <span className="num">{formatDateTime(session.finishedAt)}</span>
        <span className="num">{session.items.length} 題</span>
        <span className="num">用時 {formatDuration(totalSec)}</span>
        <span className="num">{self.length ? `自評平均 ${(self.reduce((n, i) => n + (i.selfScore ?? 0), 0) / self.length).toFixed(1)}（${self.length}/${session.items.length} 題已自評）` : '還沒自評'}</span>
        <button type="button" className="btn btn-sm" onClick={onBack}>
          回設定
        </button>
      </div>
      <ol className="review-list">
        {session.items.map((item, i) => {
          const ex = index.exerciseById.get(item.exerciseId)
          if (!ex || ex.type !== 'open') return <li key={item.exerciseId} className="muted">第 {i + 1} 題已不存在</li>
          return <ReviewItem key={item.exerciseId} n={i + 1} ex={ex} item={item} onChange={(patch, rec) => update(item.exerciseId, patch, rec)} />
        })}
      </ol>
    </>
  )
}

const SCORE_LABELS = ['', '完全不會', '很弱', '普通', '不錯', '可直接上場']

function ReviewItem({ n, ex, item, onChange }: { n: number; ex: OpenExercise; item: MockItem; onChange: (patch: Partial<MockItem>, record?: 'correct' | 'partial' | 'wrong') => void }) {
  const [compare, setCompare] = useState(false)
  const [checks, setChecks] = useState<boolean[]>(() => ex.keyPointsHtml.map(() => false))
  const [grade, setGrade] = useState<StoredGrade | null>(null)
  const grader = useGrader(ex)
  const limit = limitOf(ex)
  const covered = checks.filter(Boolean).length

  const runGrade = async () => {
    const g = await grader.run(item.answer, 'zh')
    if (g) {
      setGrade({ ...g, at: Date.now(), lang: 'zh', answer: item.answer })
      onChange({ aiScore: g.score })
    }
  }

  const title = stripTags(ex.promptHtml)
  return (
    <li className="review-item">
      <header className="review-head">
        <span className="ex-label num">Q{n}</span>
        <h3 className="review-title">{title.length > 160 ? title.slice(0, 160) + '…' : title}</h3>
        <span className={`num small ${item.seconds > limit ? 'text-bad' : 'muted'}`}>
          {formatDuration(item.seconds)} / {formatDuration(limit)}
        </span>
      </header>
      <details className="fold">
        <summary>完整題目</summary>
        <Html className="prose" html={ex.promptHtml} />
      </details>
      <div className="review-answer">
        <div className="explain-label">你的回答</div>
        {item.answer.trim() ? <p className="pre-wrap">{item.answer}</p> : <p className="muted">（沒有作答）</p>}
      </div>
      <div className="ex-actions">
        <button type="button" className="btn btn-sm btn-primary" disabled={compare} onClick={() => setCompare(true)}>
          對照要點
        </button>
        {!grader.availability.hidden && (
          <>
            <button type="button" className="btn btn-sm" disabled={!grader.availability.canGrade || grader.grading || !item.answer.trim()} onClick={runGrade}>
              {grader.grading ? '思考中…' : item.aiScore ? `AI 重新評分（上次 ${item.aiScore}/5）` : 'AI 面試官評分'}
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
      {grade && <GradePanel grade={grade} currentAnswer={item.answer} />}
      {compare && (
        <div className="compare">
          <div className="field-row">
            <h4 className="h-sm">對照要點</h4>
            <span className="num muted small">
              {covered}/{ex.keyPointsHtml.length}
            </span>
          </div>
          <ul className="keypoints">
            {ex.keyPointsHtml.map((h, i) => (
              <li key={i}>
                <label htmlFor={`${item.exerciseId}-rk-${i}`} className={checks[i] ? 'is-on' : ''}>
                  <input id={`${item.exerciseId}-rk-${i}`} type="checkbox" checked={checks[i]} onChange={() => setChecks((c) => c.map((x, k) => (k === i ? !x : x)))} />
                  <Html inline html={h} />
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}
      <details className="fold">
        <summary>參考答案</summary>
        <Html className="prose" html={ex.modelAnswerHtml} />
      </details>
      <div className="rate">
        <div className="explain-label">自評（會排進複習）</div>
        <div className="rate-btns" role="group" aria-label="自評 1 到 5 分">
          {[1, 2, 3, 4, 5].map((s) => (
            <button
              key={s}
              type="button"
              className={`btn btn-sm rate-btn ${item.selfScore === s ? 'btn-primary' : ''}`}
              aria-pressed={item.selfScore === s}
              onClick={() => onChange({ selfScore: s }, s >= 4 ? 'correct' : s === 3 ? 'partial' : 'wrong')}
            >
              <span className="num">{s}</span>
              <span className="rate-label">{SCORE_LABELS[s]}</span>
            </button>
          ))}
        </div>
      </div>
    </li>
  )
}
