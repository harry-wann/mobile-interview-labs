import { useEffect, useMemo, useRef, useState } from 'react'
import { IS_STATIC } from '../env.ts'
import { useContent } from '../content.ts'
import { INTERVIEW_DATE, daysBetween, formatMD, todayStr } from '../lib/date.ts'
import { navigate } from '../lib/router.ts'
import { searchContent } from '../lib/search.ts'
import { storageWritable } from '../lib/storage.ts'
import { dismissNotice, selectSync, useStore, type SyncState } from '../lib/store.ts'
import { setTheme, useThemePref, type ThemePref } from '../lib/theme.ts'
import { Icon } from './Icon.tsx'
import { Link } from './ui.tsx'

function Countdown() {
  if (!INTERVIEW_DATE) return null
  const days = daysBetween(todayStr(), INTERVIEW_DATE)
  const long = days > 0 ? `距離 ${formatMD(INTERVIEW_DATE)} 面試還有 ${days} 天` : days === 0 ? `${formatMD(INTERVIEW_DATE)} 面試就是今天` : `${formatMD(INTERVIEW_DATE)} 面試已結束`
  const short = days > 0 ? `D-${days}` : days === 0 ? 'D-Day' : '已結束'
  return (
    <div className="countdown" title={long}>
      <span className="cd-long num">{long}</span>
      <span className="cd-short num" aria-hidden="true">
        {short}
      </span>
    </div>
  )
}

const THEMES: Array<{ value: ThemePref; label: string; icon: string }> = [
  { value: 'system', label: '跟隨系統', icon: 'monitor' },
  { value: 'light', label: '淺色', icon: 'sun' },
  { value: 'dark', label: '深色', icon: 'moon' },
]

function ThemeSwitch() {
  const pref = useThemePref()
  return (
    <div className="theme-switch" role="group" aria-label="主題">
      {THEMES.map((t) => (
        <button key={t.value} type="button" className={pref === t.value ? 'seg-on' : ''} aria-pressed={pref === t.value} aria-label={t.label} title={t.label} onClick={() => setTheme(t.value)}>
          <Icon name={t.icon} size={16} />
        </button>
      ))}
    </div>
  )
}

function SearchBox() {
  const index = useContent()
  const [q, setQ] = useState('')
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const box = useRef<HTMLDivElement>(null)
  const hits = useMemo(() => searchContent(index, q), [index, q])

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [])

  const close = () => {
    setOpen(false)
    setQ('')
  }

  return (
    <div className="search" ref={box} role="search">
      <label htmlFor="site-search" className="sr-only">
        搜尋單元與題目
      </label>
      <Icon name="search" size={16} />
      <input
        id="site-search"
        type="search"
        placeholder="搜尋單元、tag、題目…"
        value={q}
        autoComplete="off"
        onChange={(e) => {
          setQ(e.target.value)
          setOpen(true)
          setActive(0)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') close()
          else if (e.key === 'ArrowDown') {
            e.preventDefault()
            setActive((a) => Math.min(a + 1, hits.length - 1))
          } else if (e.key === 'ArrowUp') {
            e.preventDefault()
            setActive((a) => Math.max(a - 1, 0))
          } else if (e.key === 'Enter' && hits[active]) {
            const h = hits[active]
            navigate(h.unit.id, h.kind === 'exercise' ? { focus: h.exercise.id } : undefined)
            close()
          }
        }}
      />
      {open && q.trim() && (
        <div className="search-results" role="listbox" aria-label="搜尋結果">
          {hits.length === 0 && <div className="search-empty">找不到「{q}」</div>}
          {hits.map((h, i) => (
            <Link
              key={h.kind === 'unit' ? h.unit.id : h.exercise.id}
              to={h.unit.id}
              state={h.kind === 'exercise' ? { focus: h.exercise.id } : undefined}
              className={`search-hit ${i === active ? 'is-active' : ''}`}
              role="option"
              aria-selected={i === active}
              onNavigate={close}
            >
              <span className="search-kind">{h.kind === 'unit' ? '單元' : '題目'}</span>
              <span className="search-title">{h.kind === 'unit' ? h.unit.title : promptSnippet(h.exercise.search)}</span>
              {h.kind === 'exercise' && <span className="search-sub">{h.unit.title}</span>}
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

const promptSnippet = (s: string) => (s.length > 80 ? s.slice(0, 80) + '…' : s)

/** 右上角的模式／同步狀態標示 */
function modeBadge(sync: SyncState): { text: string; cls: string; title: string; dot?: 'ok' | 'warn' } {
  if (IS_STATIC) {
    // 靜態版（Artifact）：雲端存檔（db capability）可用就同步，否則只存這台裝置的 localStorage
    if (sync.mode === 'none') return { text: '靜態版・僅存本機', cls: 'badge-static', title: '沒有雲端存檔：進度與口徑只存在這個瀏覽器；可用「我的口徑」頁的匯出備份' }
    if (sync.status === 'connecting') return { text: '靜態版・雲端連線中…', cls: 'badge-static', title: '正在確認雲端存檔是否可用' }
    if (sync.status === 'error' || sync.status === 'offline') return { text: '靜態版・雲端異常', cls: 'badge-error', title: sync.notice ?? '雲端存檔暫時失敗，變更先存在這個瀏覽器，稍後會重試' }
    return { text: sync.status === 'syncing' ? '靜態版・同步中…' : '靜態版・雲端同步', cls: 'badge-local', title: '進度與口徑已存到你的雲端存檔，換裝置也能繼續' }
  }
  const title = sync.status === 'ok' ? '已同步' : sync.status === 'offline' ? '離線（變更先存在這台裝置）' : sync.status === 'connecting' ? '連線中…' : sync.notice ?? '同步異常'
  return { text: '本機版', cls: 'badge-local', title, dot: sync.status === 'ok' ? 'ok' : sync.status === 'offline' || sync.status === 'error' ? 'warn' : undefined }
}

export function Topbar({ onMenu, menuOpen, errorCount, issuesOpen, onToggleIssues }: { onMenu: () => void; menuOpen: boolean; errorCount: number; issuesOpen: boolean; onToggleIssues: () => void }) {
  const sync = useStore(selectSync)
  const badge = modeBadge(sync)
  return (
    <header className="topbar">
      <div className="topbar-row">
        <button type="button" className="icon-btn menu-btn" aria-label="開啟單元選單" aria-expanded={menuOpen} aria-controls="sidebar" onClick={onMenu}>
          <Icon name="menu" size={20} />
        </button>
        <Link to="home" className="brand">
          <span className="brand-mark" aria-hidden="true">
            BT
          </span>
          <span className="brand-name">Mobile 面試複習站</span>
        </Link>
        <SearchBox />
        <Countdown />
        <div className="topbar-badges">
          {errorCount > 0 && (
            <button type="button" className="badge badge-error badge-btn" aria-expanded={issuesOpen} onClick={onToggleIssues}>
              {errorCount} 個內容錯誤
            </button>
          )}
          <span className={`badge ${badge.cls}`} title={badge.title}>
            {badge.text}
            {badge.dot && <span className={`badge-dot is-${badge.dot}`} aria-label={badge.title} />}
          </span>
        </div>
        <ThemeSwitch />
      </div>
    </header>
  )
}

/** 同步時需要讓使用者看到的訊息（雲端空間已滿、資料太大 …） */
export function SyncNotice() {
  const sync = useStore(selectSync)
  // 這個瀏覽器不能寫 localStorage，而且沒有雲端／本機 server 可以存：資料只在記憶體，關閉即遺失
  const persisted = (sync.mode === 'cloud' && (sync.status === 'ok' || sync.status === 'syncing')) || (sync.mode === 'server' && sync.status === 'ok')
  const cannotPersist = !storageWritable && !persisted && sync.status !== 'connecting'
  return (
    <>
      {cannotPersist && (
        <section className="notice notice-bad" role="alert" aria-label="無法保存">
          <span>這個瀏覽器無法保存資料，關閉即遺失。請定期用「我的口徑」頁的「匯出」備份。</span>
        </section>
      )}
      {sync.notice && (
        <section className="notice" role="status" aria-label="同步訊息">
          <span>{sync.notice}</span>
          <button type="button" className="btn btn-sm" onClick={dismissNotice}>
            關閉
          </button>
        </section>
      )}
    </>
  )
}

export function IssuesPanel({ onClose }: { onClose: () => void }) {
  const { errors } = useContent()
  return (
    <section className="issues" aria-label="內容錯誤清單">
      <div className="issues-head">
        <strong>{errors.length} 個內容錯誤</strong>
        <span className="muted small">有錯誤的單元已被略過；修正檔案後會自動重新載入。</span>
        <button type="button" className="btn btn-sm" onClick={onClose}>
          收合
        </button>
      </div>
      <ul>
        {errors.map((e, i) => (
          <li key={i}>
            <code>{e.file}</code>
            {e.id && <span className="issues-id">{e.id}</span>}
            <span>{e.message}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
