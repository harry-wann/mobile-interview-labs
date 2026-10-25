import { useRef, useState, type ReactNode } from 'react'
import { useSpeech } from '../lib/speech.ts'
import { Icon } from './Icon.tsx'

/** 回答輸入框＋語音輸入（Web Speech API，不支援就不顯示麥克風按鈕） */
export function AnswerBox({
  id,
  label,
  value,
  onChange,
  lang,
  rows = 6,
  placeholder,
  readOnly,
  maxChars,
  onFocus,
  onBlur,
  note,
}: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  lang: 'zh' | 'ja'
  rows?: number
  placeholder?: string
  readOnly?: boolean
  /** 字數上限：超過就拒絕這次輸入並提示（不會靜默截斷） */
  maxChars?: number
  onFocus?: () => void
  onBlur?: () => void
  /** 顯示在輸入框下方的說明（同步狀態等） */
  note?: ReactNode
}) {
  const valueRef = useRef(value)
  valueRef.current = value
  const [overLimit, setOverLimit] = useState(false)
  const accept = (v: string) => {
    if (maxChars !== undefined && v.length > maxChars) {
      setOverLimit(true) // 這次輸入不被接受（例如貼上太長的內容），畫面維持原本的內容
      return
    }
    setOverLimit(false)
    onChange(v)
  }
  const speech = useSpeech(lang, (text) => {
    const cur = valueRef.current
    accept(cur ? (/\s$/.test(cur) ? cur + text : cur + (lang === 'ja' || /[一-鿿]$/.test(cur) ? '' : ' ') + text) : text)
  })

  return (
    <div className="field">
      <div className="field-row">
        <label htmlFor={id} className="field-label">
          {label}
        </label>
        {speech.supported && !readOnly && (
          <button
            type="button"
            className={`btn btn-sm ${speech.listening ? 'btn-danger' : ''}`}
            aria-pressed={speech.listening}
            onClick={speech.listening ? speech.stop : speech.start}
          >
            <Icon name="mic" size={14} />
            {speech.listening ? '停止（聆聽中…）' : lang === 'ja' ? '語音輸入（日本語）' : '語音輸入（中文）'}
          </button>
        )}
      </div>
      <textarea
        id={id}
        className="input"
        rows={rows}
        value={value}
        readOnly={readOnly}
        placeholder={placeholder}
        lang={lang === 'ja' ? 'ja' : undefined}
        onChange={(e) => accept(e.target.value)}
        onFocus={onFocus}
        onBlur={onBlur}
        aria-describedby={`${id}-foot`}
      />
      <div className="field-foot muted small" id={`${id}-foot`}>
        <span className="num">
          {value.length.toLocaleString()}
          {maxChars !== undefined ? ` / ${maxChars.toLocaleString()}` : ''} 字
        </span>
        {overLimit && maxChars !== undefined && <span className="text-bad">超過 {maxChars.toLocaleString()} 字上限，剛才的輸入沒有被接受（內容沒有被截斷）。</span>}
        {note}
        {speech.listening && speech.interim && <span className="interim">…{speech.interim}</span>}
        {speech.error && <span className="text-bad">{speech.error}</span>}
      </div>
    </div>
  )
}
