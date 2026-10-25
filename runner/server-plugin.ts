// 本機 API（掛在 Vite dev / preview server 上，不另開 server）
//   POST /api/run      編譯執行 Swift / Kotlin 練習題（只限 localhost）
//   POST /api/grade    呼叫本機 claude CLI 做 AI 面試官評分（只限 localhost）
//   GET/PUT /api/answers   ↔ data/my-answers.json  （區網可用）
//   GET/PUT /api/progress  ↔ data/progress.json    （區網可用）
//   GET /api/health, GET /api/content
import { spawn } from 'node:child_process'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { copyFile, mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import type { IncomingMessage, ServerResponse } from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
import {
  DATA_VERSION,
  DataApiError,
  applyAnswersPut,
  applyProgressPut,
  emptyAnswers,
  emptyProgress,
  normalizeAnswers,
  normalizeProgress,
  setFactCatalog,
  validateAnswersPut,
  validateProgressPut,
} from '../src/lib/data.ts'
import { GRADE_SCHEMA, GRADE_SYSTEM, buildGradePrompt, coerceGrade, parseGradeText } from '../src/lib/gradePrompt.ts'
import type { AnswersData, GradeResult, ProgressData, ServerData } from '../src/types.ts'
import { LockTimeoutError, withFileLock } from './file-lock.ts'
import { runCode, sweepRunnerTmp } from './run-code.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
/** 測試用：PREP_DATA_DIR 可指向另一個資料目錄 */
const DATA_DIR = process.env.PREP_DATA_DIR ? path.resolve(process.env.PREP_DATA_DIR) : path.join(ROOT, 'data')
const GENERATED = path.join(ROOT, 'src/generated')
const RUN_TMP = path.join(ROOT, '.runner-tmp')
// AI 評分可以附上你的履歷（純文字檔）讓改寫建議引用你的經歷：設環境變數 RESUME_PATH；沒設就只用你這次的回答

const LOCAL_ADDRS = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])
const NOT_LOCAL_MSG = '程式執行只開放在這台電腦上使用'
const GRADE_TIMEOUT_MS = Number(process.env.GRADE_TIMEOUT_MS) || 180_000 // 從收到請求算起（含排隊）；GRADE_TIMEOUT_MS 只給測試用
const MAX_BODY = 2_000_000

class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
  }
}

// ───────── 小工具 ─────────

function send(res: ServerResponse, status: number, body: unknown) {
  const text = JSON.stringify(body)
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.setHeader('Cache-Control', 'no-store')
  res.end(text)
}

function isLocal(req: IncomingMessage) {
  return LOCAL_ADDRS.has(req.socket.remoteAddress ?? '')
}

function requireLocal(req: IncomingMessage) {
  if (!isLocal(req)) throw new HttpError(403, NOT_LOCAL_MSG)
}

/** 跨站請求防護：瀏覽器送出的 Origin 必須和 Host 相同 */
function requireSameOrigin(req: IncomingMessage) {
  const origin = req.headers.origin
  if (!origin) return
  try {
    if (new URL(origin).host !== req.headers.host) throw new Error()
  } catch {
    throw new HttpError(403, '不允許的來源')
  }
}

async function readJson(req: IncomingMessage): Promise<unknown> {
  const type = String(req.headers['content-type'] ?? '')
  if (!type.toLowerCase().startsWith('application/json')) throw new HttpError(415, 'Content-Type 需為 application/json')
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req) {
    size += (chunk as Buffer).length
    if (size > MAX_BODY) throw new HttpError(413, '資料太大')
    chunks.push(chunk as Buffer)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new HttpError(400, 'JSON 格式錯誤')
  }
}

class Semaphore {
  private active = 0
  private waiters: Array<{ wake: () => void; cancel: (e: Error) => void }> = []
  constructor(private max: number) {}
  /** signal 中止時，還在排隊的請求直接放棄（不會佔用名額、也不會啟動子程序） */
  async run<T>(fn: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) throw new HttpError(499, '已取消')
    if (this.active >= this.max) {
      await new Promise<void>((resolve, reject) => {
        const w = {
          wake: () => {
            signal?.removeEventListener('abort', onAbort)
            resolve()
          },
          cancel: reject,
        }
        const onAbort = () => {
          this.waiters = this.waiters.filter((x) => x !== w)
          reject(new HttpError(499, '已取消'))
        }
        signal?.addEventListener('abort', onAbort, { once: true })
        this.waiters.push(w)
      })
    }
    this.active++
    try {
      return await fn()
    } finally {
      this.active--
      this.waiters.shift()?.wake()
    }
  }
}
const runSlots = new Semaphore(2)
const gradeSlots = new Semaphore(2)

// ───────── server-data（tests、open 題原文）─────────

let sdCache: { mtime: number; data: ServerData } | null = null
function serverData(): ServerData {
  const file = path.join(GENERATED, 'server-data.json')
  if (!existsSync(file)) throw new HttpError(503, '內容尚未建置，請先執行 npm run content:build')
  const mtime = statSync(file).mtimeMs
  if (!sdCache || sdCache.mtime !== mtime) sdCache = { mtime, data: JSON.parse(readFileSync(file, 'utf8')) as ServerData }
  return sdCache.data
}

// 舊格式的口徑（事實以 index 對齊）遷移時，用目前內容的事實 id；內容還沒建置時先用位置 key（@i），之後自動對應
setFactCatalog((id) => {
  const facts = serverData().facts?.[id]
  return facts ? { ids: facts.map((f) => f.id), labels: facts.map((f) => f.q) } : undefined
})

// ───────── /api/run ─────────

async function handleRun(req: IncomingMessage, res: ServerResponse) {
  requireLocal(req)
  requireSameOrigin(req)
  const body = (await readJson(req)) as { exerciseId?: unknown; lang?: unknown; code?: unknown }
  const { exerciseId, lang, code } = body
  if (typeof exerciseId !== 'string' || typeof code !== 'string' || (lang !== 'swift' && lang !== 'kotlin')) {
    throw new HttpError(400, '需要 exerciseId、lang（swift｜kotlin）、code')
  }
  if (code.length > 100_000) throw new HttpError(413, '程式碼太長')
  const spec = serverData().code[exerciseId]
  if (!spec) throw new HttpError(404, `找不到練習題：${exerciseId}`)
  const langSpec = spec[lang]
  if (!langSpec) throw new HttpError(400, `這題沒有 ${lang} 版本`)
  const ac = abortOnDisconnect(res)
  const result = await runSlots.run(
    () =>
      runCode({
        lang,
        code,
        tests: langSpec.tests,
        swiftVersion: 'swiftVersion' in langSpec ? langSpec.swiftVersion : 5,
        timeoutSec: spec.timeoutSec,
        signal: ac.signal,
      }),
    ac.signal,
  )
  send(res, 200, result)
}

// ───────── /api/grade ─────────

function readResume(): string {
  const file = process.env.RESUME_PATH
  if (!file) return ''
  try {
    return readFileSync(file, 'utf8').slice(0, 14_000)
  } catch {
    return ''
  }
}

/** 瀏覽器斷線（或前端放棄等待）時中止；response 正常送完則不觸發 */
function abortOnDisconnect(res: ServerResponse): AbortController {
  const ac = new AbortController()
  res.on('close', () => {
    if (!res.writableFinished) ac.abort()
  })
  return ac
}

interface ClaudeOutput {
  stdout: string
  stderr: string
  code: number | null
  timedOut: boolean
  aborted: boolean
}

/**
 * 呼叫 claude CLI。總期限由呼叫端以 timeoutMs 給（從收到請求開始算，含排隊時間）；
 * signal 中止（前端放棄）時殺掉整個 process group，不再消耗額度。
 */
function runClaude(prompt: string, signal: AbortSignal, timeoutMs: number): Promise<ClaudeOutput> {
  const model = process.env.GRADE_MODEL || 'sonnet'
  const args = [
    '-p',
    '--output-format',
    'json',
    '--tools',
    '',
    '--no-session-persistence',
    '--model',
    model,
    '--setting-sources',
    '',
    '--strict-mcp-config',
    '--system-prompt',
    GRADE_SYSTEM,
    '--json-schema',
    JSON.stringify(GRADE_SCHEMA),
  ]
  return new Promise((resolve) => {
    const cwd = path.join(RUN_TMP, 'grade')
    mkdir(cwd, { recursive: true })
      .catch(() => {})
      .then(() => {
        if (signal.aborted) return resolve({ stdout: '', stderr: '', code: null, timedOut: false, aborted: true })
        const child = spawn(process.env.CLAUDE_BIN || 'claude', args, { cwd, stdio: ['pipe', 'pipe', 'pipe'], detached: true })
        const pid = child.pid
        let stdout = ''
        let stderr = ''
        let timedOut = false
        let aborted = false
        let settled = false
        const kill = () => {
          if (!pid) return
          try {
            process.kill(-pid, 'SIGKILL')
          } catch {
            try {
              process.kill(pid, 'SIGKILL')
            } catch {
              /* 已結束 */
            }
          }
        }
        const done = (code: number | null) => {
          if (settled) return
          settled = true
          clearTimeout(timer)
          signal.removeEventListener('abort', onAbort)
          child.stdout.destroy()
          child.stderr.destroy()
          resolve({ stdout, stderr, code, timedOut, aborted })
        }
        const timer = setTimeout(() => {
          timedOut = true
          kill()
        }, Math.max(1, timeoutMs))
        const onAbort = () => {
          aborted = true
          kill()
        }
        signal.addEventListener('abort', onAbort, { once: true })
        child.stdout.setEncoding('utf8')
        child.stderr.setEncoding('utf8')
        child.stdout.on('data', (d: string) => (stdout += d))
        child.stderr.on('data', (d: string) => (stderr += d))
        child.on('error', (e) => {
          stderr += String(e)
          done(-1)
        })
        child.on('exit', (code) => {
          if (timedOut || aborted) return done(code)
          // 正常結束：等 stdout 收完（close）；最多再等 300ms
          const t = setTimeout(() => done(code), 300)
          child.once('close', () => {
            clearTimeout(t)
            done(code)
          })
        })
        child.stdin.on('error', () => {})
        child.stdin.end(prompt)
      })
  })
}

/** CLI json 輸出：優先用 structured_output，其次 result（可能是 JSON 字串，或包在 ``` 裡） */
function parseClaudeOutput(stdout: string): GradeResult | null {
  let outer: unknown
  try {
    outer = JSON.parse(stdout)
  } catch {
    // 某些版本會輸出多行（stream）；取最後一個 type=result 的物件
    const lines = stdout.trim().split('\n').reverse()
    for (const line of lines) {
      try {
        const j = JSON.parse(line)
        if (j && j.type === 'result') {
          outer = j
          break
        }
      } catch {
        /* 下一行 */
      }
    }
  }
  const candidates: unknown[] = []
  const visit = (o: unknown) => {
    if (Array.isArray(o)) o.forEach(visit)
    else if (o && typeof o === 'object') {
      const r = o as Record<string, unknown>
      if (r.structured_output) candidates.push(r.structured_output)
      if (typeof r.result === 'string') candidates.push(r.result)
      else if (r.result) candidates.push(r.result)
      if ('score' in r && 'summary' in r) candidates.push(r)
    }
  }
  visit(outer)
  for (const c of candidates) {
    const g = typeof c === 'string' ? parseGradeText(c) : coerceGrade(c)
    if (g) return g
  }
  return null
}

const GRADE_DEADLINE_MS = GRADE_TIMEOUT_MS

async function handleGrade(req: IncomingMessage, res: ServerResponse) {
  const received = Date.now() // 180 秒的總期限從收到請求開始算（含排隊）
  requireLocal(req)
  requireSameOrigin(req)
  const body = (await readJson(req)) as { exerciseId?: unknown; answer?: unknown; lang?: unknown }
  if (typeof body.exerciseId !== 'string' || typeof body.answer !== 'string') throw new HttpError(400, '需要 exerciseId、answer')
  const lang = body.lang === 'ja' ? 'ja' : 'zh'
  if (!body.answer.trim()) throw new HttpError(400, '回答是空的')
  if (body.answer.length > 20_000) throw new HttpError(413, '回答太長')
  const q = serverData().open[body.exerciseId]
  if (!q) throw new HttpError(404, `找不到口說題：${body.exerciseId}`)
  const prompt = buildGradePrompt(q, body.answer, lang, readResume())
  const ac = abortOnDisconnect(res)
  const started = Date.now()
  const out = await gradeSlots.run(async () => {
    const remaining = GRADE_DEADLINE_MS - (Date.now() - received)
    if (remaining <= 1000) throw new HttpError(504, `AI 評分排隊太久，已超過 ${Math.round(GRADE_DEADLINE_MS / 1000)} 秒期限，請稍後再試`)
    return runClaude(prompt, ac.signal, remaining)
  }, ac.signal)
  if (out.aborted) return // 瀏覽器已經斷線，沒有人在等回應
  if (out.timedOut) throw new HttpError(504, `AI 評分逾時（從送出起算 ${Math.round(GRADE_DEADLINE_MS / 1000)} 秒），請稍後再試`)
  const grade = parseClaudeOutput(out.stdout)
  if (!grade) {
    const detail = (out.stderr || out.stdout).trim().slice(0, 600)
    throw new HttpError(502, `AI 評分失敗${out.code ? `（exit ${out.code}）` : ''}：${detail || '沒有可解析的輸出'}`)
  }
  send(res, 200, { ...grade, durationMs: Date.now() - started })
}

// ───────── /api/answers、/api/progress ─────────

/**
 * 讀寫資料檔的鎖：同一個行程內先排隊（Promise 鏈），再拿跨行程的檔案鎖（dev 與 preview 同時執行時，兩個行程不會互相覆蓋）。
 */
const writeLocks = new Map<string, Promise<unknown>>()
function withLock<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const prev = writeLocks.get(key) ?? Promise.resolve()
  const locked = () =>
    withFileLock(DATA_DIR, key, fn).catch((e) => {
      if (e instanceof LockTimeoutError) throw new HttpError(503, `資料檔正被另一個 server 行程使用中（${e.message}），請稍後再試`)
      throw e
    })
  const next = prev.then(locked, locked)
  writeLocks.set(key, next.catch(() => {}))
  return next
}

async function atomicWrite(file: string, text: string) {
  await mkdir(path.dirname(file), { recursive: true })
  const tmp = `${file}.tmp-${process.pid}-${Date.now()}`
  await writeFile(tmp, text)
  await rename(tmp, file)
}

/**
 * 讀資料檔。不存在（ENOENT）→ null；存在但壞掉（不是合法 JSON、或不是物件）→ 先把原檔改名備份成
 * <name>.corrupt-<timestamp>.json，再丟 500（絕不覆寫）。備份後下一次請求會當作沒有檔案，
 * 前端會用 localStorage 的內容補送回來。
 */
async function readJsonFile(file: string): Promise<unknown> {
  let text: string
  try {
    text = await readFile(file, 'utf8')
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') return null
    throw new HttpError(500, `讀取 ${path.basename(file)} 失敗：${(e as Error).message}`)
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    parsed = undefined
  }
  if (typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)) return parsed
  const backup = file.replace(/\.json$/, `.corrupt-${Date.now()}.json`)
  try {
    await rename(file, backup)
  } catch (e) {
    throw new HttpError(500, `${path.basename(file)} 內容損壞，而且無法備份：${(e as Error).message}`)
  }
  throw new HttpError(500, `${path.basename(file)} 內容損壞，已備份成 ${path.basename(backup)}；請重新整理頁面（本機資料會自動補回）`)
}

// ───────── 區網存取驗證 ─────────
// 非 localhost 的請求要帶 X-Prep-Token（data/.lan-token，第一次啟動時隨機產生，刪掉檔案即可換新）。
// 手機第一次用終端機印出的網址（?t=<token>）開啟，前端會把 token 存起來並從網址移除。

const TOKEN_FILE = path.join(DATA_DIR, '.lan-token')
let lanToken: string | null = null

function getLanToken(): string {
  if (lanToken) return lanToken
  try {
    const t = readFileSync(TOKEN_FILE, 'utf8').trim()
    if (/^[a-f0-9]{32}$/.test(t)) return (lanToken = t)
  } catch {
    /* 還沒有 → 產生 */
  }
  const t = randomBytes(16).toString('hex')
  mkdirSync(DATA_DIR, { recursive: true })
  writeFileSync(TOKEN_FILE, t + '\n', { mode: 0o600 })
  return (lanToken = t)
}

function tokenOk(req: IncomingMessage): boolean {
  const h = req.headers['x-prep-token']
  if (typeof h !== 'string') return false
  const a = Buffer.from(h)
  const b = Buffer.from(getLanToken())
  return a.length === b.length && timingSafeEqual(a, b)
}

/** 資料 API（口徑、進度）：localhost 免 token；區網要帶正確的 token，否則 401（GET 也擋） */
function requireDataAccess(req: IncomingMessage) {
  if (isLocal(req)) return
  if (!tokenOk(req)) throw new HttpError(401, '區網存取需要 token：請用電腦終端機印出的網址（含 ?t=…）重新開啟這個頁面')
}

function lanUrls(port: number): string[] {
  const token = getLanToken()
  const all: Array<{ name: string; address: string }> = []
  for (const [name, infos] of Object.entries(os.networkInterfaces())) {
    for (const i of infos ?? []) if (i.family === 'IPv4' && !i.internal) all.push({ name, address: i.address })
  }
  const preferred = all.filter((x) => /^en\d/.test(x.name))
  return (preferred.length ? preferred : all).map((x) => `http://${x.address}:${port}/?t=${token}`)
}

// ───────── 資料檔（口徑、進度）─────────

type Versioned = { version?: unknown }

/** 讀並自動升級：舊格式先備份成 <name>.v<舊版本>-<timestamp>.json，再用新格式重寫（呼叫端要持有鎖） */
async function loadData<T>(file: string, normalize: (raw: unknown) => T, empty: () => T): Promise<T> {
  const raw = await readJsonFile(file)
  if (raw === null) return empty()
  const data = normalize(raw)
  const ver = (raw as Versioned).version
  if (ver !== DATA_VERSION) {
    await copyFile(file, file.replace(/\.json$/, `.v${typeof ver === 'number' ? ver : 1}-${Date.now()}.json`))
    await atomicWrite(file, JSON.stringify(data, null, 2) + '\n')
  }
  return data
}

const ANSWERS_FILE = path.join(DATA_DIR, 'my-answers.json')
const PROGRESS_FILE = path.join(DATA_DIR, 'progress.json')

async function answersEndpoint(req: IncomingMessage, res: ServerResponse) {
  requireDataAccess(req)
  if (req.method === 'GET') {
    const data = await withLock('answers', () => loadData<AnswersData>(ANSWERS_FILE, normalizeAnswers, emptyAnswers))
    return send(res, 200, { ...data, serverTime: Date.now() })
  }
  if (req.method === 'PUT') {
    requireSameOrigin(req)
    const body = await readJson(req)
    validateAnswersPut(body) // 舊版頁面 → 426、太長 → 413（拿鎖之前先擋）
    const response = await withLock('answers', async () => {
      const data = await loadData<AnswersData>(ANSWERS_FILE, normalizeAnswers, emptyAnswers)
      const r = applyAnswersPut(data, body, Date.now()) // 時間以 server 為準
      if (r.changed) await atomicWrite(ANSWERS_FILE, JSON.stringify(data, null, 2) + '\n')
      return r.response
    })
    return send(res, 200, response)
  }
  throw new HttpError(405, '不支援的方法')
}

async function progressEndpoint(req: IncomingMessage, res: ServerResponse) {
  requireDataAccess(req)
  if (req.method === 'GET') {
    const data = await withLock('progress', () => loadData<ProgressData>(PROGRESS_FILE, normalizeProgress, emptyProgress))
    return send(res, 200, { ...data, serverTime: Date.now() })
  }
  if (req.method === 'PUT') {
    requireSameOrigin(req)
    const body = await readJson(req)
    validateProgressPut(body) // 舊版頁面（作答次數是總數格式，合併會重複計算）→ 426
    const now = Date.now()
    const merged = await withLock('progress', async () => {
      const existing = await loadData<ProgressData>(PROGRESS_FILE, normalizeProgress, emptyProgress)
      const m = applyProgressPut(existing, body, now)
      await atomicWrite(PROGRESS_FILE, JSON.stringify(m, null, 2) + '\n')
      return m
    })
    return send(res, 200, { ...merged, serverTime: now })
  }
  throw new HttpError(405, '不支援的方法')
}

// ───────── 路由 ─────────

async function route(req: IncomingMessage, res: ServerResponse) {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const p = url.pathname
  switch (p) {
    case '/api/health':
      return send(res, 200, { ok: true, local: isLocal(req), auth: isLocal(req) || tokenOk(req) })
    case '/api/content': {
      const file = path.join(GENERATED, 'content.json')
      if (!existsSync(file)) throw new HttpError(503, '內容尚未建置')
      res.setHeader('Content-Type', 'application/json; charset=utf-8')
      res.setHeader('Cache-Control', 'no-store')
      return res.end(await readFile(file))
    }
    case '/api/run':
      if (req.method !== 'POST') throw new HttpError(405, '請用 POST')
      return handleRun(req, res)
    case '/api/grade':
      if (req.method !== 'POST') throw new HttpError(405, '請用 POST')
      return handleGrade(req, res)
    case '/api/answers':
      return answersEndpoint(req, res)
    case '/api/progress':
      return progressEndpoint(req, res)
    default:
      throw new HttpError(404, '找不到 API')
  }
}

type Next = (err?: unknown) => void
function middleware(req: IncomingMessage, res: ServerResponse, next: Next) {
  if (!req.url?.startsWith('/api/')) return next()
  route(req, res).catch((e) => {
    if (e instanceof HttpError || e instanceof DataApiError) return send(res, e.status, { error: e.message })
    console.error('[api]', e)
    send(res, 500, { error: e instanceof Error ? e.message : String(e) })
  })
}

/** 啟動後在終端機印出「手機連線」網址（含 token） */
function announceLan(server: { httpServer: { once: (e: 'listening', cb: () => void) => unknown; address: () => unknown } | null; config: { logger: { info: (m: string) => void }; server: { host?: string | boolean }; preview: { host?: string | boolean } } }, kind: 'server' | 'preview') {
  const host = kind === 'server' ? server.config.server.host : server.config.preview.host
  if (!host || host === 'localhost' || host === '127.0.0.1') return // 沒開放區網，不用印
  server.httpServer?.once('listening', () => {
    const addr = server.httpServer?.address() as { port?: number } | string | null | undefined
    const port = typeof addr === 'object' && addr ? addr.port : undefined
    if (!port) return
    const urls = lanUrls(port)
    const lines = urls.length ? urls.map((u) => `  手機連線（含 token）：${u}`) : ['  （找不到區網 IP）']
    server.config.logger.info(`\n${lines.join('\n')}\n  第一次用這個網址開啟後，手機會記住 token；要換新 token 就刪掉 data/.lan-token 再重啟。\n`)
  })
}

/** 測試用：直接呼叫 API 的 middleware（不經過 Vite） */
export const apiMiddleware = middleware

export function apiPlugin(): Plugin {
  return {
    name: 'prep-api',
    configureServer(server) {
      void sweepRunnerTmp() // 清掉上次被強制結束時留下的暫存目錄（超過 10 分鐘）
      server.middlewares.use(middleware)
      announceLan(server as never, 'server')
    },
    configurePreviewServer(server) {
      void sweepRunnerTmp()
      server.middlewares.use(middleware)
      announceLan(server as never, 'preview')
    },
  }
}
