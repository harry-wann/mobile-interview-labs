// 本機編譯執行 Swift / Kotlin 練習題，並解析 prelude 輸出的測試結果。
// Vite 外掛（/api/run）與 scripts/verify-tasks 共用這個模組。
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const TMP_ROOT = path.join(ROOT, '.runner-tmp')
const TOOLS = path.join(ROOT, '.tools')
const MAX_OUTPUT = 200_000

const IMPORT_RE = /^\s*import\s+[\w.*]+\s*$/

// 把各段程式碼的 import 行抽出、去重，放到最上方
function hoistImports(parts) {
  const imports = new Set()
  const bodies = parts.map((src) =>
    src
      .split('\n')
      .filter((line) => {
        if (IMPORT_RE.test(line)) {
          imports.add(line.trim())
          return false
        }
        return true
      })
      .join('\n'),
  )
  return { imports: [...imports], bodies }
}

// 回傳 { src, map }：map 用來把編譯錯誤行號換回「你的程式碼／測試」的行號
function assemble(chunks) {
  const lines = []
  const map = []
  for (const { text, label } of chunks) {
    const start = lines.length + 1
    const parts = text.split('\n')
    lines.push(...parts)
    if (label) map.push({ label, start, end: start + parts.length - 1 })
  }
  return { src: lines.join('\n') + '\n', map }
}

export async function composeSwift(userCode, tests) {
  const prelude = await readFile(path.join(ROOT, 'runner/prelude.swift'), 'utf8')
  const { imports, bodies } = hoistImports([prelude, userCode, tests])
  return assemble([
    { text: imports.join('\n') },
    { text: bodies[0] },
    { text: bodies[1], label: '你的程式碼' },
    { text: bodies[2], label: '測試' },
    { text: '__testsDone()' },
  ])
}

export async function composeKotlin(userCode, tests) {
  const prelude = await readFile(path.join(ROOT, 'runner/prelude.kt'), 'utf8')
  const { imports, bodies } = hoistImports([prelude, userCode, tests])
  return assemble([
    { text: imports.join('\n') },
    { text: bodies[0] },
    { text: bodies[1], label: '你的程式碼' },
    { text: 'fun main() = runBlocking {' },
    { text: bodies[2], label: '測試' },
    { text: '    __testsDone()\n}' },
  ])
}

// 目前還在跑的 process group；dev server 結束時一併殺掉，避免留下孤兒 java／swiftc
const activeGroups = new Set()
function killGroup(pid, signal = 'SIGKILL') {
  try {
    process.kill(-pid, signal) // 負號 = 整個 process group（kotlinc 是 bash 包裝，真正的 java 是孫程序）
  } catch {
    try {
      process.kill(pid, signal)
    } catch {
      /* 已經結束 */
    }
  }
}
process.once('exit', () => {
  for (const pid of activeGroups) killGroup(pid)
})

/**
 * 執行子程序，回傳 { code, signal, stdout, stderr, timedOut }。
 * - detached: true → 子程序自成一個 process group，逾時時整組 SIGKILL（包含 kotlinc 底下的 java）
 * - 聽 'exit' 而不是 'close'：被殺掉後不必等孫程序放掉 pipe；結束後 destroy stdout/stderr
 * - setEncoding('utf8')：避免中文跨 chunk 時被切成亂碼
 */
function exec(cmd, args, { cwd, timeoutMs, env, signal }) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { cwd, env: { ...process.env, ...env }, detached: true, stdio: ['ignore', 'pipe', 'pipe'] })
    const pid = child.pid
    if (pid) activeGroups.add(pid)
    let stdout = ''
    let stderr = ''
    let timedOut = false
    let settled = false
    let graceTimer
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', (d) => {
      if (stdout.length < MAX_OUTPUT) stdout += d
    })
    child.stderr.on('data', (d) => {
      if (stderr.length < MAX_OUTPUT) stderr += d
    })
    const finish = (code, signal) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      clearTimeout(graceTimer)
      if (pid) {
        activeGroups.delete(pid)
        killGroup(pid) // 正常結束後也掃一次，避免留下沒結束的孫程序
      }
      child.stdout.destroy()
      child.stderr.destroy()
      resolve({ code, signal, stdout, stderr, timedOut })
    }
    const timer = setTimeout(() => {
      timedOut = true
      if (pid) killGroup(pid)
    }, timeoutMs)
    // 呼叫端取消（例如瀏覽器已經斷線）→ 直接殺掉整組
    const onAbort = () => {
      timedOut = true
      if (pid) killGroup(pid)
    }
    if (signal) {
      if (signal.aborted) onAbort()
      else signal.addEventListener('abort', onAbort, { once: true })
    }
    child.on('exit', (code, sig) => {
      signal?.removeEventListener('abort', onAbort)
      if (timedOut) return finish(code, sig)
      // 正常結束：等 stdio 收完（close）；最多再等 300ms，避免孫程序握著 pipe 造成卡住
      graceTimer = setTimeout(() => finish(code, sig), 300)
      child.once('close', () => finish(code, sig))
    })
    child.on('error', (err) => {
      stderr += String(err)
      finish(-1, null)
    })
  })
}

/** 清掉 .runner-tmp/ 底下超過 maxAgeMs 的 swift-／kotlin- 暫存目錄（上次 server 被強制結束時留下的） */
export async function sweepRunnerTmp(maxAgeMs = 10 * 60 * 1000) {
  let removed = 0
  try {
    for (const name of await readdir(TMP_ROOT)) {
      if (!/^(swift|kotlin)-/.test(name)) continue
      const full = path.join(TMP_ROOT, name)
      try {
        const st = await stat(full)
        if (Date.now() - st.mtimeMs > maxAgeMs) {
          await rm(full, { recursive: true, force: true })
          removed++
        }
      } catch {
        /* 被別人刪掉了 */
      }
    }
  } catch {
    /* 目錄不存在 */
  }
  return removed
}

export function parseResults(stdout) {
  const tests = []
  let done = null
  const plain = []
  for (const line of stdout.split('\n')) {
    if (line.startsWith('@@TEST|')) {
      const [, pass, name, ...rest] = line.split('|')
      tests.push({ name, pass: pass === '1', detail: rest.join('|') })
    } else if (line.startsWith('@@DONE|')) {
      const [, passed, total] = line.split('|')
      done = { passed: Number(passed), total: Number(total) }
    } else {
      plain.push(line)
    }
  }
  return { tests, done, output: plain.join('\n').trim() }
}

function kotlinPaths() {
  const kotlinc = path.join(TOOLS, 'kotlinc/bin/kotlinc')
  const libDir = path.join(TOOLS, 'lib')
  const jars = existsSync(libDir)
    ? readdirSync(libDir).filter((f) => f.endsWith('.jar')).map((f) => path.join(libDir, f))
    : []
  const stdlib = path.join(TOOLS, 'kotlinc/lib/kotlin-stdlib.jar')
  return { kotlinc, jars, stdlib }
}

// 前端顯示用：拿掉暫存目錄路徑，並把 main.swift:71 / Main.kt:71 換成「你的程式碼:3」
function tidy(text, dir, map = []) {
  const cleaned = text.split(dir + path.sep).join('').split(dir).join('')
  return cleaned
    .replace(/(?:main\.swift|Main\.kt):(\d+)/g, (m, n) => {
      const line = Number(n)
      const hit = map.find((r) => line >= r.start && line <= r.end)
      return hit ? `${hit.label}:${line - hit.start + 1}` : m
    })
    .trim()
}

/**
 * @param {{ lang: 'swift'|'kotlin', code: string, tests: string, swiftVersion?: 5|6, timeoutSec?: number, compileTimeoutMs?: number, signal?: AbortSignal }} req
 * @returns {Promise<{ ok: boolean, phase: 'compile'|'run'|'done', tests: Array<{name:string,pass:boolean,detail:string}>, passed: number, total: number, output: string, error: string, durationMs: number }>}
 */
export async function runCode({ lang, code, tests, swiftVersion = 5, timeoutSec = 10, compileTimeoutMs, signal }) {
  const started = Date.now()
  await import('node:fs/promises').then((fs) => fs.mkdir(TMP_ROOT, { recursive: true }))
  const dir = await mkdtemp(path.join(TMP_ROOT, `${lang}-`))
  const result = (phase, extra) => ({
    ok: false,
    phase,
    tests: [],
    passed: 0,
    total: 0,
    output: '',
    error: '',
    durationMs: Date.now() - started,
    ...extra,
  })
  try {
    if (lang === 'swift') {
      const { src, map } = await composeSwift(code, tests)
      await writeFile(path.join(dir, 'main.swift'), src)
      const compile = await exec(
        'swiftc',
        ['-swift-version', String(swiftVersion), '-Onone', 'main.swift', '-o', 'main'],
        { cwd: dir, timeoutMs: compileTimeoutMs ?? 60_000, signal },
      )
      if (compile.code !== 0) {
        return result('compile', { error: compile.timedOut ? (signal?.aborted ? '已取消' : '編譯逾時') : tidy(compile.stderr, dir, map) })
      }
      const run = await exec('./main', [], { cwd: dir, timeoutMs: timeoutSec * 1000, signal })
      return finish(run, dir, map, result, started)
    }
    if (lang === 'kotlin') {
      const { kotlinc, jars, stdlib } = kotlinPaths()
      if (!existsSync(kotlinc)) {
        return result('compile', { error: '找不到 Kotlin 編譯器，請先執行 scripts/setup-kotlin.sh' })
      }
      const { src, map } = await composeKotlin(code, tests)
      await writeFile(path.join(dir, 'Main.kt'), src)
      const cp = jars.join(path.delimiter)
      const compile = await exec(
        kotlinc,
        ['Main.kt', '-cp', cp, '-d', 'out', '-nowarn'],
        { cwd: dir, timeoutMs: compileTimeoutMs ?? 120_000, env: { JAVA_OPTS: '-Xss8m' }, signal },
      )
      if (compile.code !== 0) {
        return result('compile', { error: compile.timedOut ? (signal?.aborted ? '已取消' : '編譯逾時') : tidy(compile.stderr, dir, map) })
      }
      const run = await exec(
        'java',
        ['-cp', ['out', stdlib, ...jars].join(path.delimiter), 'MainKt'],
        { cwd: dir, timeoutMs: timeoutSec * 1000, signal },
      )
      return finish(run, dir, map, result, started)
    }
    return result('compile', { error: `不支援的語言：${lang}` })
  } finally {
    rm(dir, { recursive: true, force: true }).catch(() => {})
  }
}

function finish(run, dir, map, result, started) {
  const parsed = parseResults(run.stdout)
  const total = parsed.tests.length
  const passed = parsed.tests.filter((t) => t.pass).length
  let error = ''
  if (run.timedOut) error = '執行逾時（可能有無窮迴圈或 deadlock）'
  else if (!parsed.done) error = `程式異常結束${run.signal ? `（${run.signal}）` : ''}\n${tidy(run.stderr, dir, map)}`.trim()
  else if (run.stderr.trim()) error = tidy(run.stderr, dir, map)
  // 通過條件：測試全過，而且程式是正常結束（exit code 0、沒有被 signal 殺掉）
  const cleanExit = run.code === 0 && !run.signal
  if (parsed.done && !cleanExit && !run.timedOut && !error) error = `程式結束狀態異常（exit ${run.code}${run.signal ? `, ${run.signal}` : ''}）`
  return {
    ...result('done'),
    ok: !!parsed.done && total > 0 && passed === total && !run.timedOut && cleanExit,
    phase: parsed.done ? 'done' : 'run',
    tests: parsed.tests,
    passed,
    total,
    output: parsed.output,
    error,
    durationMs: Date.now() - started,
  }
}
