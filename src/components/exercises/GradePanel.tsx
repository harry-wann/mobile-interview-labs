import { useEffect, useRef, useState } from 'react'
import { copyText } from '../../lib/clipboard.ts'
import { formatDateTime } from '../../lib/date.ts'
import type { StoredGrade } from '../../types.ts'
import { Icon } from '../Icon.tsx'

function List({ title, items, tone }: { title: string; items: string[]; tone: 'ok' | 'bad' | 'warn' | 'plain' }) {
  return (
    <div className={`grade-list grade-${tone}`}>
      <h5>
        {title} <span className="num muted">{items.length}</span>
      </h5>
      {items.length === 0 ? (
        <p className="muted small">（無）</p>
      ) : (
        <ul>
          {items.map((t, i) => (
            <li key={i}>{t}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function GradePanel({ grade, currentAnswer }: { grade: StoredGrade; currentAnswer: string }) {
  const [copied, setCopied] = useState<'idle' | 'ok' | 'fail'>('idle')
  const improvedRef = useRef<HTMLTextAreaElement>(null)
  const stale = currentAnswer.trim() !== grade.answer.trim()

  const copy = async () => {
    const ok = await copyText(grade.improved)
    setCopied(ok ? 'ok' : 'fail')
    if (!ok) improvedRef.current?.select()
  }

  return (
    <section className="grade" aria-label="AI 面試官評分結果">
      <header className="grade-head">
        <div className="grade-score" aria-label={`評分 ${grade.score} 分（滿分 5）`}>
          <span className="grade-num num">{grade.score}</span>
          <span className="grade-max num">/ 5</span>
        </div>
        <div className="grade-meta">
          <strong>AI 面試官評分</strong>
          <span className="muted small">
            {formatDateTime(grade.at)}・{grade.lang === 'ja' ? '日文改寫' : '中文'}
          </span>
        </div>
      </header>
      {stale && <p className="note-inline">你在評分之後修改過回答，這是舊回答的評分。</p>}
      <p className="grade-summary">{grade.summary}</p>
      <div className="grade-cols">
        <List title="已涵蓋" items={grade.covered} tone="ok" />
        <List title="缺漏" items={grade.missing} tone="bad" />
        <List title="不精確" items={grade.inaccurate} tone="warn" />
        <List title="面試官可能追問" items={grade.followUps} tone="plain" />
      </div>
      {grade.improved && (
        <div className="grade-improved">
          <div className="field-row">
            <h5>改寫建議</h5>
            <button type="button" className="btn btn-sm" onClick={copy}>
              <Icon name="copy" size={14} />
              {copied === 'ok' ? '已複製' : copied === 'fail' ? '複製失敗，請手動選取' : '複製'}
            </button>
          </div>
          <textarea ref={improvedRef} className="input improved" readOnly rows={Math.min(14, Math.max(4, grade.improved.split('\n').length + 1))} value={grade.improved} lang={grade.lang === 'ja' ? 'ja' : undefined} aria-label="改寫建議" />
        </div>
      )}
    </section>
  )
}

/** 計時顯示：評分最多會等 3 分鐘，讓使用者知道還在跑 */
export function useElapsed(running: boolean): number {
  const [sec, setSec] = useState(0)
  useEffect(() => {
    if (!running) return
    setSec(0)
    const t0 = Date.now()
    const id = setInterval(() => setSec(Math.floor((Date.now() - t0) / 1000)), 500)
    return () => clearInterval(id)
  }, [running])
  return sec
}
