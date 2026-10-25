// 「我的口徑」輸入欄位：回答（textarea）與待確認事實（input）。
//  - 首次同步前唯讀（顯示「同步中…」）；5 秒沒同步成功就允許離線編輯（標示「離線編輯，連線後合併」）。
//  - 開始編輯（focus）時先 pull 那一題。
//  - 別台裝置更新了這題：不直接換掉正在編輯的內容，只顯示提示；沒在編輯就直接更新。
//    繼續打字時，對方的版本會先進「歷史版本」（規則見 src/lib/fieldEditor.ts）。
//  - 待確認事實以穩定的事實 id 對齊（內容增刪事實不會錯位）。
//  - 字數上限：回答 100,000 字、事實 5,000 字；超過就拒絕輸入並提示，不會靜默截斷。
//  - 歷史版本：任何一次合併輸掉的內容都留在 history，可以檢視與還原。
import { useEffect, useReducer, useRef, useState } from 'react'
import { MAX_FACT, MAX_TEXT, factField, fieldAt, fieldText } from '../lib/data.ts'
import { formatDateTime } from '../lib/date.ts'
import { FieldEditor } from '../lib/fieldEditor.ts'
import { clearRemoteNotice, factLabelOf, moveFact, pullFresh, restoreHistory, selectAnswers, selectGate, selectRemoteNotice, useStore, type FactMeta, type SyncGate } from '../lib/store.ts'
import type { HistoryItem } from '../types.ts'
import { AnswerBox } from './AnswerBox.tsx'
import { Html, useDebouncedSave } from './ui.tsx'

type FieldKind = { type: 'text' } | { type: 'fact'; factId: string; meta?: FactMeta }

/** 本地輸入框的狀態與 store 的同步規則（見檔案開頭） */
export function useEditableField(exerciseId: string, kind: FieldKind) {
  const field = kind.type === 'text' ? 'text' : factField(kind.factId)
  const entry = useStore(selectAnswers).answers[exerciseId]
  const storedV = fieldText(entry, field)
  const storedAt = fieldAt(entry, field)
  const gate = useStore(selectGate)
  const noticeAt = useStore(selectRemoteNotice)[exerciseId]
  const [focused, setFocused] = useState(false)
  const [, rerender] = useReducer((x: number) => x + 1, 0)
  const edRef = useRef<FieldEditor | null>(null)
  if (!edRef.current || edRef.current.exerciseId !== exerciseId || edRef.current.field !== field) {
    edRef.current = new FieldEditor(exerciseId, field, { v: storedV, at: storedAt }, kind.type === 'fact' ? kind.meta : undefined)
  }
  const ed = edRef.current

  // 進到這一題時，先前累積的「別台更新了」提示不用再顯示（現在看到的就是最新的）
  useEffect(() => {
    clearRemoteNotice(exerciseId)
  }, [exerciseId])

  useEffect(() => {
    if (ed.sync({ v: storedV, at: storedAt })) {
      clearRemoteNotice(exerciseId)
      rerender()
    }
  }, [ed, storedV, storedAt, exerciseId])

  useDebouncedSave(ed.value, () => ed.save())

  return {
    value: ed.value,
    setValue: (v: string) => {
      ed.input(v)
      rerender()
    },
    /** 立即把輸入框的內容存進 store（AI 評分前） */
    commit: () => ed.save(),
    gate,
    focused,
    notice: noticeAt !== undefined,
    dismissNotice: () => clearRemoteNotice(exerciseId),
    onFocus: () => {
      ed.focus()
      setFocused(true)
      void pullFresh(exerciseId, true) // 開始編輯前先 pull 這一題
    },
    onBlur: () => {
      setFocused(false)
      if (ed.blur({ v: storedV, at: storedAt })) {
        clearRemoteNotice(exerciseId)
        rerender()
      }
    },
  }
}

export type EditableField = ReturnType<typeof useEditableField>

export function gateNote(gate: SyncGate): string | null {
  if (gate === 'syncing') return '同步中…（同步完成前先唯讀，避免蓋掉別台的內容）'
  if (gate === 'offline-edit') return '離線編輯，連線後合併（不會清掉別台的其他欄位）'
  return null
}

function GateNote({ gate }: { gate: SyncGate }) {
  const t = gateNote(gate)
  return t ? <span className={gate === 'syncing' ? 'sync-note' : 'sync-note is-offline'}>{t}</span> : null
}

function RemoteNotice({ f }: { f: EditableField }) {
  if (!f.notice) return null
  return (
    <div className="note-inline" role="status">
      <span>另一台裝置更新了這題。你正在編輯的內容不會被換掉；你繼續編輯的話，對方的版本會留在「歷史版本」。</span>
      <button type="button" className="btn btn-sm" onClick={f.dismissNotice}>
        知道了
      </button>
    </div>
  )
}

/** 回答欄位（含歷史版本） */
export function AnswerField({ exerciseId, f, id, label, lang, rows, placeholder }: { exerciseId: string; f: EditableField; id: string; label: string; lang: 'zh' | 'ja'; rows?: number; placeholder?: string }) {
  return (
    <>
      <AnswerBox
        id={id}
        label={label}
        value={f.value}
        onChange={f.setValue}
        lang={lang}
        rows={rows}
        placeholder={placeholder}
        readOnly={f.gate === 'syncing'}
        maxChars={MAX_TEXT}
        onFocus={f.onFocus}
        onBlur={f.onBlur}
        note={<GateNote gate={f.gate} />}
      />
      <RemoteNotice f={f} />
      <AnswerHistory exerciseId={exerciseId} field="text" />
    </>
  )
}

/** 待確認事實欄位（以事實 id 對齊）。index／count：它在目前內容中的位置（內容改字時自動搬移用） */
export function FactField({ exerciseId, factId, html, index, count }: { exerciseId: string; factId: string; html: string; index: number; count: number }) {
  const f = useEditableField(exerciseId, { type: 'fact', factId, meta: { label: factLabelOf(html), pos: [index, count] } })
  const id = `${exerciseId}-fact-${factId}`
  const tooLong = f.value.length >= MAX_FACT
  return (
    <div className="fact">
      <label htmlFor={id}>
        <Html inline html={html} />
      </label>
      <input
        id={id}
        className="input"
        type="text"
        value={f.value}
        readOnly={f.gate === 'syncing'}
        placeholder={f.gate === 'syncing' ? '同步中…' : '填寫事實（沒有就寫「無」）'}
        onFocus={f.onFocus}
        onBlur={f.onBlur}
        onChange={(e) => {
          if (e.target.value.length <= MAX_FACT) f.setValue(e.target.value)
        }}
      />
      <div className="field-foot muted small">
        <span className="num">
          {f.value.length.toLocaleString()} / {MAX_FACT.toLocaleString()} 字
        </span>
        {tooLong && <span className="text-bad">已達 {MAX_FACT.toLocaleString()} 字上限，再多的輸入不會被接受。</span>}
        <GateNote gate={f.gate} />
      </div>
      <RemoteNotice f={f} />
      <AnswerHistory exerciseId={exerciseId} field={factField(factId)} />
    </div>
  )
}

/**
 * 內容已經移除（或改了文字）的事實：口徑還在，唯讀顯示（不讓它隱形），每筆可以「移到目前事實」。
 * 只列出還有內容、或歷史裡有「目前任何一個事實都沒有」的內容的事實（已經搬到新事實的就不重複顯示）。
 */
export function RemovedFacts({ exerciseId, factIds, factsHtml }: { exerciseId: string; factIds: string[]; factsHtml: string[] }) {
  const entry = useStore(selectAnswers).answers[exerciseId]
  if (!entry) return null
  const current = new Set(factIds)
  const values = new Set(Object.values(entry.facts).filter((v) => v.trim()))
  const keys = Object.keys(entry.facts).filter(
    (k) => !current.has(k) && (entry.facts[k].trim() || entry.history.some((h) => h.field === factField(k) && !values.has(h.text))),
  )
  if (keys.length === 0) return null
  return (
    <fieldset className="facts removed-facts">
      <legend>已移除的題目事實（內容已經沒有這些事實；你寫過的內容保留在這裡）</legend>
      {keys.map((k) => (
        <div className="fact" key={k}>
          <div className="muted small">{entry.factQ?.[k] ?? '（原本的題目文字不明）'}</div>
          <pre className="history-text">{entry.facts[k] || '（目前是空的）'}</pre>
          <div className="field-foot muted small">
            <span className="num">寫於 {formatDateTime(entry.factsAt[k] ?? 0)}</span>
          </div>
          {entry.facts[k].trim() && factIds.length > 0 && <MoveFact exerciseId={exerciseId} fromId={k} factIds={factIds} factsHtml={factsHtml} />}
          <AnswerHistory exerciseId={exerciseId} field={factField(k)} />
        </div>
      ))}
    </fieldset>
  )
}

/** 「移到目前事實 ▸ 選擇欄位」：目標原本有內容的話會先進它的歷史版本 */
function MoveFact({ exerciseId, fromId, factIds, factsHtml }: { exerciseId: string; fromId: string; factIds: string[]; factsHtml: string[] }) {
  const [target, setTarget] = useState('')
  const selectId = `${exerciseId}-move-${fromId}`
  return (
    <div className="ex-actions">
      <label htmlFor={selectId} className="small">
        移到目前事實 ▸
      </label>
      <select id={selectId} className="input" value={target} onChange={(e) => setTarget(e.target.value)}>
        <option value="">選擇欄位…</option>
        {factIds.map((id, i) => (
          <option key={id} value={id}>
            {factLabelOf(factsHtml[i] ?? '').slice(0, 60) || id}
          </option>
        ))}
      </select>
      <button
        type="button"
        className="btn btn-sm"
        disabled={!target}
        onClick={() => {
          const i = factIds.indexOf(target)
          if (i >= 0 && moveFact(exerciseId, fromId, target, { label: factLabelOf(factsHtml[i] ?? ''), pos: [i, factIds.length] })) setTarget('')
        }}
      >
        移過去
      </button>
      <span className="muted small">目標欄位原本的內容會先存進歷史版本</span>
    </div>
  )
}

/** 歷史版本：合併時輸掉的、被還原取代的內容。可檢視、可還原（還原不會讓目前的內容消失） */
export function AnswerHistory({ exerciseId, field }: { exerciseId: string; field: string }) {
  const entry = useStore(selectAnswers).answers[exerciseId]
  const [open, setOpen] = useState(false)
  const current = fieldText(entry, field)
  const items: HistoryItem[] = (entry?.history ?? []).filter((h) => h.field === field && h.text !== current)
  if (items.length === 0) return null
  return (
    <div className="history">
      <button type="button" className="btn btn-sm btn-ghost" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        歷史版本（{items.length}）
      </button>
      {open && (
        <ol className="history-list" aria-label="歷史版本">
          {items.map((h, i) => (
            <li key={`${h.at}-${i}`}>
              <div className="history-meta muted small">
                <span className="num">寫於 {formatDateTime(h.at || h.replacedAt)}</span>
                {h.device && <span>{h.device}</span>}
                {h.replacedAt > h.at && <span className="num">被取代於 {formatDateTime(h.replacedAt)}</span>}
              </div>
              <pre className="history-text">{h.text}</pre>
              <button type="button" className="btn btn-sm" onClick={() => restoreHistory(exerciseId, h)}>
                還原這個版本
              </button>
              <span className="muted small"> 目前的內容會先存進歷史版本</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
