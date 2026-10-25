import { useEffect, useRef, useState, type AnchorHTMLAttributes, type ReactNode } from 'react'
import { onPageHide } from '../lib/lifecycle.ts'
import { navigate, type NavState } from '../lib/router.ts'
import type { Priority } from '../types.ts'

/** 把 HTML 字串（建置時已轉好、來源是自己的內容）渲染出來 */
export function Html({
  html,
  className,
  inline,
  lang,
}: {
  html: string
  className?: string
  inline?: boolean
  lang?: string
}) {
  const Tag = inline ? 'span' : 'div'
  return <Tag className={className} lang={lang} dangerouslySetInnerHTML={{ __html: html }} />
}

export function CodeBlock({ html, lang, className = '' }: { html: string; lang?: string; className?: string }) {
  return (
    <pre className={`code ${className}`} data-lang={lang}>
      <code dangerouslySetInnerHTML={{ __html: html }} />
    </pre>
  )
}

/** 站內連結：用 <a href="#token">，點擊時由 router 處理（沙箱裡 hash 導覽失效時也能運作） */
export function Link({
  to,
  state,
  children,
  onNavigate,
  ...rest
}: { to: string; state?: NavState; children: ReactNode; onNavigate?: () => void } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'>) {
  return (
    <a
      {...rest}
      href={`#${to}`}
      onClick={(e) => {
        if (e.defaultPrevented || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return
        e.preventDefault()
        navigate(to, state)
        onNavigate?.()
      }}
    >
      {children}
    </a>
  )
}

export function PriorityBadge({ priority }: { priority: Priority }) {
  return (
    <span className={`badge badge-${priority.toLowerCase()}`} title={priority === 'P0' ? '面試前一定要會' : priority === 'P1' ? '很可能被問' : '加分'}>
      {priority}
    </span>
  )
}

export function ProgressBar({ value, max, label, tone = 'primary' }: { value: number; max: number; label?: string; tone?: 'primary' | 'ok' }) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0
  return (
    <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-label={label}>
      <div className={`progress-fill progress-${tone}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

/** 頁面內二次確認（不使用 confirm()／alert()） */
export function ConfirmButton({
  label,
  confirmLabel,
  message,
  onConfirm,
  className = 'btn btn-sm',
  icon,
  disabled,
}: {
  label: string
  confirmLabel: string
  message: string
  onConfirm: () => void
  className?: string
  icon?: ReactNode
  disabled?: boolean
}) {
  const [asking, setAsking] = useState(false)
  const cancelRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (asking) cancelRef.current?.focus()
  }, [asking])
  if (!asking) {
    return (
      <button type="button" className={className} disabled={disabled} onClick={() => setAsking(true)}>
        {icon}
        {label}
      </button>
    )
  }
  return (
    <span className="confirm" role="alertdialog" aria-label={message}>
      <span className="confirm-msg">{message}</span>
      <button
        type="button"
        className="btn btn-sm btn-danger"
        onClick={() => {
          setAsking(false)
          onConfirm()
        }}
      >
        {confirmLabel}
      </button>
      <button type="button" ref={cancelRef} className="btn btn-sm" onClick={() => setAsking(false)}>
        取消
      </button>
    </span>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
}: {
  value: T
  options: Array<{ value: T; label: string }>
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" className={o.value === value ? 'seg-on' : ''} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** textarea：輸入先存在本地 state，debounce 後才寫進 store（避免每個按鍵都重繪整個 app）。卸載與離開頁面時立即存。 */
export function useDebouncedSave(value: string, save: (v: string) => void, ms = 500) {
  const saveRef = useRef(save)
  saveRef.current = save
  const lastSaved = useRef(value)
  const latest = useRef(value)
  latest.current = value
  useEffect(() => {
    if (value === lastSaved.current) return
    const t = setTimeout(() => {
      lastSaved.current = value
      saveRef.current(value)
    }, ms)
    return () => clearTimeout(t)
  }, [value, ms])
  useEffect(() => {
    const flush = () => {
      if (latest.current !== lastSaved.current) {
        lastSaved.current = latest.current
        saveRef.current(latest.current)
      }
    }
    const off = onPageHide(flush) // 離開頁面（pagehide／分頁隱藏）
    return () => {
      off()
      flush() // 卸載（換頁、換題）
    }
  }, [])
}
