import { useMemo, useRef, useState } from 'react'
import { useContent } from '../content.ts'
import { AnswerField, AnswerHistory, FactField, RemovedFacts, useEditableField } from '../components/AnswerField.tsx'
import { GradePanel } from '../components/exercises/GradePanel.tsx'
import { ConfirmButton, Link, PriorityBadge } from '../components/ui.tsx'
import { copyText } from '../lib/clipboard.ts'
import { stripTags } from '../lib/stats.ts'
import { exportJson, importBackup, selectAnswers, selectImportUndo, undoImport, useStore } from '../lib/store.ts'
import type { OpenExercise } from '../types.ts'

// 依 section 順序（舊版的履歷、策略區若存在則放最前面）
const FIRST = ['resume', 'strategy']

export function Answers() {
  const index = useContent()
  const answers = useStore(selectAnswers)
  const [onlyPending, setOnlyPending] = useState(false)
  const [onlyFilled, setOnlyFilled] = useState(false)

  const groups = useMemo(() => {
    const order = [...FIRST.filter((id) => index.sectionById.has(id)), ...index.sections.map((s) => s.id).filter((id) => !FIRST.includes(id))]
    return order
      .map((sid) => ({
        section: index.sectionById.get(sid)!,
        items: index.content.exercises.filter((e): e is OpenExercise => e.type === 'open' && e.sectionId === sid),
      }))
      .filter((g) => g.items.length > 0)
  }, [index])

  const pendingOf = (e: OpenExercise) => e.factIds.filter((k) => !(answers.answers[e.id]?.facts[k] ?? '').trim()).length
  const filledOf = (e: OpenExercise) => !!answers.answers[e.id]?.text.trim()
  const total = groups.reduce((n, g) => n + g.items.length, 0)
  const filled = groups.reduce((n, g) => n + g.items.filter(filledOf).length, 0)
  const pending = groups.reduce((n, g) => n + g.items.reduce((m, e) => m + pendingOf(e), 0), 0)

  return (
    <div className="page">
      <h1>我的口徑</h1>
      <p className="lead">
        你對每一道口說題打算怎麼回答，加上只有你知道的事實。內容自動儲存
        {` `}（本機版會同步到 <code>data/my-answers.json</code>）。
      </p>
      <div className="stat-line">
        <span className="num">
          已寫 {filled}/{total} 題
        </span>
        <span className={`num ${pending ? 'text-warn' : ''}`}>待確認事實 {pending} 項</span>
        <label htmlFor="a-pending" className="inline-check">
          <input id="a-pending" type="checkbox" checked={onlyPending} onChange={(e) => setOnlyPending(e.target.checked)} /> 只看有待確認事實的
        </label>
        <label htmlFor="a-filled" className="inline-check">
          <input id="a-filled" type="checkbox" checked={onlyFilled} onChange={(e) => setOnlyFilled(e.target.checked)} /> 只看已寫的
        </label>
      </div>

      <BackupPanel />

      {total === 0 && <p className="empty">還沒有口說題（open）。</p>}
      <OrphanAnswers />
      {groups.map((g) => {
        const items = g.items.filter((e) => (!onlyPending || pendingOf(e) > 0) && (!onlyFilled || filledOf(e)))
        if (items.length === 0) return null
        return (
          <section key={g.section.id} className="answers-group" aria-labelledby={`ag-${g.section.id}`}>
            <h2 id={`ag-${g.section.id}`}>{g.section.title}</h2>
            <ul className="answers-list">
              {items.map((e) => (
                <AnswerRow key={e.id} ex={e} pending={pendingOf(e)} defaultOpen={onlyPending && pendingOf(e) > 0} />
              ))}
            </ul>
          </section>
        )
      })}
    </div>
  )
}

function AnswerRow({ ex, pending, defaultOpen }: { ex: OpenExercise; pending: number; defaultOpen: boolean }) {
  const index = useContent()
  const entry = useStore(selectAnswers).answers[ex.id]
  const field = useEditableField(ex.id, { type: 'text' })
  const value = field.value
  const unit = index.unitById.get(ex.unitId)
  const title = stripTags(ex.promptHtml)
  return (
    <li>
      <details className="answer-row" open={defaultOpen}>
        <summary>
          <span className="answer-title">{title.length > 120 ? title.slice(0, 120) + '…' : title}</span>
          <span className="answer-chips">
            <PriorityBadge priority={ex.priority} />
            {value.trim() ? <span className="badge badge-ok">已寫</span> : <span className="badge">空白</span>}
            {pending > 0 && <span className="badge badge-warn">{pending} 項待確認</span>}
            {entry?.grade && <span className="badge num">AI {entry.grade.score}/5</span>}
          </span>
        </summary>
        <div className="answer-body">
          <div className="answer-links">
            {unit && (
              <Link to={unit.id} state={{ focus: ex.id }}>
                到單元作答／對照要點：{unit.title}
              </Link>
            )}
          </div>
          <AnswerField exerciseId={ex.id} f={field} id={`ans-${ex.id}`} label="我的口徑" lang="zh" rows={6} />
          {ex.factsHtml.length > 0 && (
            <fieldset className="facts">
              <legend>只有你知道的事</legend>
              {ex.factsHtml.map((h, i) => (
                <FactField key={ex.factIds[i]} exerciseId={ex.id} factId={ex.factIds[i]} html={h} index={i} count={ex.factIds.length} />
              ))}
            </fieldset>
          )}
          <RemovedFacts exerciseId={ex.id} factIds={ex.factIds} factsHtml={ex.factsHtml} />
          {entry?.grade && (
            <details className="fold">
              <summary>最後一次 AI 評分（{entry.grade.score}/5）</summary>
              <GradePanel grade={entry.grade} currentAnswer={value} />
            </details>
          )}
        </div>
      </details>
    </li>
  )
}

/** 內容已經沒有這一題（整題被移除或改了 id），但口徑還在：唯讀列出，不讓它隱形 */
function OrphanAnswers() {
  const index = useContent()
  const answers = useStore(selectAnswers)
  const orphans = Object.entries(answers.answers).filter(
    ([id, e]) => !index.exerciseById.has(id) && (e.text.trim() || Object.values(e.facts).some((f) => f.trim()) || e.history.length > 0),
  )
  if (orphans.length === 0) return null
  return (
    <section className="answers-group" aria-labelledby="ag-orphans">
      <h2 id="ag-orphans">內容已移除的題目（唯讀）</h2>
      <p className="muted small">這些題目已經不在目前的內容裡，你寫過的口徑保留在這裡，可以複製或匯出備份。</p>
      <ul className="answers-list">
        {orphans.map(([id, e]) => (
          <li key={id}>
            <details className="answer-row">
              <summary>
                <span className="answer-title">{id}</span>
              </summary>
              <div className="answer-body">
                {e.text.trim() && <pre className="history-text">{e.text}</pre>}
                <AnswerHistory exerciseId={id} field="text" />
                <RemovedFacts exerciseId={id} factIds={[]} factsHtml={[]} />
              </div>
            </details>
          </li>
        ))}
      </ul>
    </section>
  )
}

function BackupPanel() {
  const [exported, setExported] = useState<string | null>(null)
  const [copyState, setCopyState] = useState<'idle' | 'ok' | 'fail'>('idle')
  const [importText, setImportText] = useState('')
  const [importMsg, setImportMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const exportRef = useRef<HTMLTextAreaElement>(null)
  const undo = useStore(selectImportUndo)

  const doExport = async () => {
    const json = exportJson()
    setExported(json)
    try {
      const ok = await copyText(json)
      setCopyState(ok ? 'ok' : 'fail')
      if (!ok) setTimeout(() => exportRef.current?.select(), 50)
    } catch {
      setCopyState('fail')
      setTimeout(() => exportRef.current?.select(), 50)
    }
  }
  const doImport = (mode: 'merge' | 'replace') => {
    const r = importBackup(importText, mode)
    setImportMsg({ ok: r.ok, text: r.message })
    if (r.ok) setImportText('')
  }

  return (
    <details className="fold backup">
      <summary>匯出／匯入（備份、換裝置）</summary>
      <div className="backup-body">
        <div className="backup-col">
          <h3 className="h-sm">匯出</h3>
          <p className="muted small">把我的口徑與進度的 JSON 複製到剪貼簿。貼到筆記或訊息裡保存。</p>
          <button type="button" className="btn btn-sm btn-primary" onClick={doExport}>
            複製 JSON 到剪貼簿
          </button>
          {copyState === 'ok' && <span className="text-ok small"> 已複製</span>}
          {copyState === 'fail' && <span className="text-warn small"> 無法自動複製，內容已選取，請手動複製（Ctrl/⌘ + C）</span>}
          {exported !== null && <textarea ref={exportRef} id="export-json" className="input mono" readOnly rows={6} value={exported} aria-label="匯出的 JSON" onFocus={(e) => e.currentTarget.select()} />}
        </div>
        <div className="backup-col">
          <h3 className="h-sm">匯入</h3>
          <p className="muted small">
            貼上先前匯出的 JSON。「合併」是欄位級：只採用比現有新的欄位、現有缺的補上，輸掉的內容會留在歷史版本。匯入前會自動存快照，可以復原。
          </p>
          <label htmlFor="import-json" className="sr-only">
            要匯入的 JSON
          </label>
          <textarea id="import-json" className="input mono" rows={6} value={importText} placeholder="貼上 JSON" onChange={(e) => setImportText(e.target.value)} />
          <div className="ex-actions">
            <button type="button" className="btn btn-sm btn-primary" disabled={!importText.trim()} onClick={() => doImport('merge')}>
              匯入（合併）
            </button>
            <ConfirmButton
              label="以備份完全取代"
              confirmLabel="取代"
              message="備份的內容會當成新的編輯覆蓋同一題的現有內容（現有的會留在歷史版本）。確定嗎？"
              disabled={!importText.trim()}
              onConfirm={() => doImport('replace')}
            />
            {importMsg && <span className={`small ${importMsg.ok ? 'text-ok' : 'text-bad'}`}>{importMsg.text}</span>}
          </div>
          {undo && (
            <div className="ex-actions">
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => {
                  const r = undoImport()
                  setImportMsg({ ok: r.ok, text: r.message })
                }}
              >
                復原這次匯入
              </button>
              <span className="muted small num">（{new Date(undo.at).toLocaleString()} 匯入，動到 {undo.count} 題）</span>
            </div>
          )}
        </div>
      </div>
    </details>
  )
}
