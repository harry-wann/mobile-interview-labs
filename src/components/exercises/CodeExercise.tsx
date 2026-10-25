import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { IS_STATIC } from '../../env.ts'
import { ApiError, runCode, type RunResponse } from '../../lib/api.ts'
import { onPageHide } from '../../lib/lifecycle.ts'
import { getItem, removeItem, setItem } from '../../lib/storage.ts'
import { selectIsLocal, useStore } from '../../lib/store.ts'
import type { CodeExercise, RunLang } from '../../types.ts'
import { CodeEditor } from '../CodeEditor.tsx'
import { Icon } from '../Icon.tsx'
import { CodeBlock, ConfirmButton, Html } from '../ui.tsx'
import { Explain, Prompt, type BodyProps } from './common.tsx'

const LANG_LABEL: Record<RunLang, string> = { swift: 'Swift', kotlin: 'Kotlin' }
const LANG_PREF_KEY = 'bt.codeLang'
const draftKey = (id: string, lang: RunLang) => `bt.draft.${id}.${lang}`

export function CodeBody({ ex, report }: BodyProps<CodeExercise>) {
  const langs = useMemo(() => (['swift', 'kotlin'] as RunLang[]).filter((l) => ex[l]), [ex])
  const [lang, setLang] = useState<RunLang>(() => {
    const pref = getItem(LANG_PREF_KEY)
    return langs.includes(pref as RunLang) ? (pref as RunLang) : langs[0]
  })
  const [codes, setCodes] = useState<Partial<Record<RunLang, string>>>(() => {
    const out: Partial<Record<RunLang, string>> = {}
    for (const l of langs) out[l] = getItem(draftKey(ex.id, l)) ?? ex[l]!.starter
    return out
  })
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<(RunResponse & { lang: RunLang }) | null>(null)
  const [runError, setRunError] = useState('')
  const [hints, setHints] = useState(0)
  const [showSolution, setShowSolution] = useState(false)
  const solved = useRef(false)
  const isLocal = useStore(selectIsLocal)
  const canRun = !IS_STATIC && isLocal !== false

  const timer = useCodeTimer(ex.timeLimitSec)

  const spec = ex[lang]!
  const code = codes[lang] ?? spec.starter
  const dirty = code !== spec.starter

  // 草稿自動存（每題每語言），debounce 400ms；切換語言、卸載、離開頁面時立即存，不會掉最後幾個字
  const saveTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const pending = useRef<{ lang: RunLang; value: string; starter: string } | null>(null)
  const flushDraft = useCallback(() => {
    clearTimeout(saveTimer.current)
    const p = pending.current
    if (!p) return
    pending.current = null
    if (p.value === p.starter) removeItem(draftKey(ex.id, p.lang))
    else setItem(draftKey(ex.id, p.lang), p.value)
  }, [ex.id])
  const onChange = (v: string) => {
    setCodes((c) => ({ ...c, [lang]: v }))
    pending.current = { lang, value: v, starter: spec.starter }
    clearTimeout(saveTimer.current)
    saveTimer.current = setTimeout(flushDraft, 400)
  }
  useEffect(() => {
    const off = onPageHide(flushDraft)
    return () => {
      off()
      flushDraft()
    }
  }, [flushDraft])

  const switchLang = (l: RunLang) => {
    flushDraft()
    setLang(l)
    setItem(LANG_PREF_KEY, l)
    setResult(null)
    setRunError('')
  }

  const run = async () => {
    setRunning(true)
    setRunError('')
    setResult(null)
    try {
      const r = await runCode(ex.id, lang, code)
      setResult({ ...r, lang })
      if (r.ok) timer.stop()
      if (r.ok && !solved.current) {
        solved.current = true
        report('correct')
      }
    } catch (e) {
      setRunError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setRunning(false)
    }
  }

  const reset = () => {
    pending.current = null
    removeItem(draftKey(ex.id, lang))
    setCodes((c) => ({ ...c, [lang]: spec.starter }))
    setResult(null)
    setRunError('')
  }

  const revealSolution = () => {
    setShowSolution(true)
    if (!solved.current) {
      solved.current = true // 看過解答後不再因為通過而記為答對
      report('wrong')
    }
  }

  return (
    <>
      <Prompt ex={ex} />
      {ex.timeLimitSec && <TimerBar timer={timer} limit={ex.timeLimitSec} />}
      <div className="code-tabs" role="tablist" aria-label="語言">
        {langs.map((l) => (
          <button key={l} type="button" role="tab" id={`${ex.id}-tab-${l}`} aria-selected={l === lang} className={l === lang ? 'tab-on' : ''} onClick={() => switchLang(l)}>
            {LANG_LABEL[l]}
          </button>
        ))}
        {dirty && <span className="muted small tab-note">草稿已自動儲存</span>}
      </div>
      <CodeEditor id={`${ex.id}-editor`} lang={lang} value={code} onChange={onChange} />

      <div className="ex-actions">
        <button type="button" className="btn btn-primary btn-sm" disabled={running || !canRun} onClick={run}>
          <Icon name="play" size={14} />
          {running ? '編譯執行中…' : '執行測試'}
        </button>
        <ConfirmButton label="重設" confirmLabel="確定重設" message="還原成起始程式碼，目前的修改會消失。" onConfirm={reset} disabled={!dirty} icon={<Icon name="refresh" size={14} />} />
        {ex.hintsHtml.length > 0 && (
          <button type="button" className="btn btn-sm" disabled={hints >= ex.hintsHtml.length} onClick={() => setHints((h) => h + 1)}>
            <Icon name="hint" size={14} />
            看提示（{hints}/{ex.hintsHtml.length}）
          </button>
        )}
        {!showSolution ? (
          <ConfirmButton label="看解答" confirmLabel="看解答" message="看了解答這題會記為答錯，確定嗎？" onConfirm={revealSolution} />
        ) : (
          <button type="button" className="btn btn-sm" onClick={() => setShowSolution(false)}>
            隱藏解答
          </button>
        )}
      </div>

      {!canRun && (
        <p className="note-inline" role="note">
          {IS_STATIC ? '本機版才能執行（靜態版只能看提示與解答）。' : '程式執行只開放在這台電腦上使用。'}
        </p>
      )}

      {hints > 0 && (
        <ol className="hints">
          {ex.hintsHtml.slice(0, hints).map((h, i) => (
            <li key={i}>
              <Html inline html={h} />
            </li>
          ))}
        </ol>
      )}

      {runError && (
        <div className="run-result run-fail" role="alert">
          {runError}
        </div>
      )}
      {result && <RunPanel result={result} />}

      {showSolution && (
        <div className="solution">
          <div className="explain-label">參考解答（{LANG_LABEL[lang]}）</div>
          <CodeBlock html={spec.solutionHtml} lang={lang} />
          <Explain html={ex.explainHtml} />
          <ConfirmButton label="套用到編輯器" confirmLabel="取代" message="用解答取代編輯器內容？" onConfirm={() => onChange(spec.solution)} />
        </div>
      )}
    </>
  )
}

function RunPanel({ result }: { result: RunResponse & { lang: RunLang } }) {
  const compileFailed = result.phase === 'compile'
  return (
    <div className={`run-result ${result.ok ? 'run-ok' : 'run-fail'}`} role="status" aria-live="polite">
      <div className="run-head">
        <Icon name={result.ok ? 'check' : 'x'} size={16} />
        <strong>{compileFailed ? '編譯失敗' : result.ok ? `全部通過 ${result.passed}/${result.total}` : `${result.passed}/${result.total} 通過`}</strong>
        <span className="muted small num">
          {LANG_LABEL[result.lang]}・{(result.durationMs / 1000).toFixed(1)}s
        </span>
      </div>
      {result.tests.length > 0 && (
        <ul className="tests">
          {result.tests.map((t, i) => (
            <li key={i} className={t.pass ? 'is-ok' : 'is-bad'}>
              <span className="tests-mark">
                <Icon name={t.pass ? 'check' : 'x'} size={14} />
              </span>
              <span className="tests-name">{t.name}</span>
              {t.detail && <span className="tests-detail">{t.detail}</span>}
            </li>
          ))}
        </ul>
      )}
      {result.error && <pre className="run-error">{result.error}</pre>}
      {result.output && (
        <details className="run-output">
          <summary>程式輸出（print）</summary>
          <pre>{result.output}</pre>
        </details>
      )}
    </div>
  )
}

/** Live Coding 計時：按「開始計時」後倒數；用實際時間差計算，背景分頁也準 */
function useCodeTimer(limit: number | undefined) {
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const [stoppedAt, setStoppedAt] = useState<number | null>(null)
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (startedAt === null || stoppedAt !== null) return
    const t = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(t)
  }, [startedAt, stoppedAt])
  const end = stoppedAt ?? now
  const elapsed = startedAt === null || !limit ? 0 : Math.floor((end - startedAt) / 1000)
  return {
    running: startedAt !== null && stoppedAt === null,
    started: startedAt !== null,
    elapsed,
    remaining: limit ? limit - elapsed : 0,
    start: () => {
      setStartedAt(Date.now())
      setStoppedAt(null)
      setNow(Date.now())
    },
    stop: () => setStoppedAt((s) => s ?? (startedAt !== null ? Date.now() : null)),
    reset: () => {
      setStartedAt(null)
      setStoppedAt(null)
    },
  }
}

function TimerBar({ timer, limit }: { timer: ReturnType<typeof useCodeTimer>; limit: number }) {
  const over = timer.started && timer.remaining < 0
  const fmt = (sec: number) => `${Math.floor(Math.abs(sec) / 60)}:${String(Math.abs(sec) % 60).padStart(2, '0')}`
  return (
    <div className="code-timer">
      {!timer.started ? (
        <button type="button" className="btn btn-sm" onClick={timer.start}>
          <Icon name="play" size={14} />
          開始計時（{fmt(limit)}）
        </button>
      ) : (
        <>
          <span className={`mock-timer num ${over ? 'is-over' : timer.remaining <= 60 && timer.running ? 'is-low' : ''}`} role="timer" aria-live="off">
            {over ? `+${fmt(timer.remaining)} 超時` : fmt(timer.remaining)}
            <span className="muted small"> / {fmt(limit)}</span>
          </span>
          <span className="muted small">{timer.running ? '計時中' : `已停止（用時 ${fmt(timer.elapsed)}）`}</span>
          <button type="button" className="btn btn-sm" onClick={timer.reset}>
            重置計時
          </button>
        </>
      )}
    </div>
  )
}
