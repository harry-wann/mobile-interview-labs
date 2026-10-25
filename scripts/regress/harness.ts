// store 層整合測試的執行環境：每台「裝置」是一個 vm context（各自的 window、document、localStorage、fetch、計時器），
// 執行打包後的真正前端模組（store、cloud、lifecycle、fieldEditor …）；所有裝置共用一個假的時鐘、假的本機 server、假的雲端 db。
//  - 假 server 用的是 server-plugin 同一份寫入邏輯（data.ts 的 applyAnswersPut／applyProgressPut），時間用假時鐘。
//  - 請求在送出的當下由 server 處理（server 的處理順序＝送出順序）；回應可以被「扣住」稍後才送達（模擬亂序）。
//  - 計時器只在 advance() 時依時間順序執行，每執行一個就把 microtask 跑完。
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
import { build } from 'vite'
import { DataApiError, applyAnswersPut, applyProgressPut, emptyAnswers, emptyProgress, factIdsOf } from '../../src/lib/data.ts'
import type { AnswersData, Content, ProgressData } from '../../src/types.ts'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const clone = <T>(v: T): T => (v === undefined ? v : (JSON.parse(JSON.stringify(v)) as T))

/** 讓所有已排定的 microtask（含其他 vm context 的 promise 鏈）跑完 */
export async function settle(rounds = 4) {
  for (let i = 0; i < rounds; i++) await new Promise((r) => setImmediate(r))
}

// ───────── 打包 ─────────

const bundles = new Map<string, string>()
/** 打包前端模組（static：VITE_STATIC=1 的靜態版；否則本機 server 版）。內容由每台裝置自己提供（globalThis.__BT_CONTENT__） */
export async function bundle(mode: 'server' | 'static'): Promise<string> {
  const hit = bundles.get(mode)
  if (hit) return hit
  const prev = process.env.VITE_STATIC
  process.env.VITE_STATIC = mode === 'static' ? '1' : ''
  try {
    const out = (await build({
      configFile: false,
      root: ROOT,
      logLevel: 'silent',
      mode: 'production',
      publicDir: false,
      define: { 'process.env.NODE_ENV': '"production"' },
      plugins: [
        {
          name: 'regress-content',
          resolveId: (id) => (id === 'virtual:prep-content' ? '\0regress-content' : undefined),
          load: (id) => (id === '\0regress-content' ? 'export default globalThis.__BT_CONTENT__' : undefined),
        },
      ],
      build: {
        write: false,
        minify: false,
        target: 'es2022',
        copyPublicDir: false,
        lib: { entry: path.join(ROOT, 'scripts/regress/store-entry.ts'), formats: ['iife'], name: 'BT', fileName: () => 'bt.js' },
      },
    })) as unknown as { output: Array<{ code?: string }> } | Array<{ output: Array<{ code?: string }> }>
    const first = Array.isArray(out) ? out[0] : out
    const code = first.output[0].code
    if (!code) throw new Error('bundle failed')
    bundles.set(mode, code)
    return code
  } finally {
    if (prev === undefined) delete process.env.VITE_STATIC
    else process.env.VITE_STATIC = prev
  }
}

// ───────── 內容 ─────────

/** 測試用內容：q1（有待確認事實）、q2（沒有事實）、c1（選擇題，測作答次數） */
export function makeContent(q1Facts: string[]): Content {
  const base = { unitId: 'u1', sectionId: 's1', level: 1 as const, priority: 'P0' as const, tags: [], search: '' }
  const open = (id: string, facts: string[]) => ({
    ...base,
    id,
    type: 'open' as const,
    promptHtml: `<p>${id}</p>`,
    keyPointsHtml: [],
    modelAnswerHtml: '',
    followUpsHtml: [],
    factsHtml: facts,
    factIds: factIdsOf(facts),
    timeLimitSec: 60,
  })
  return {
    version: 1,
    builtAt: '',
    strict: false,
    sections: [{ id: 's1', title: 'S', short: 'S', description: '', unitIds: ['u1'] }],
    units: [{ id: 'u1', sectionId: 's1', order: 0, title: 'U', summary: '', priority: 'P0', tags: [], html: '', toc: [], search: '', exerciseIds: ['q1', 'q2', 'c1'], file: '' }],
    exercises: [open('q1', q1Facts), open('q2', []), { ...base, id: 'c1', type: 'single', promptHtml: '<p>c1</p>', optionsHtml: ['a', 'b'], answer: 0 }],
    plan: [],
    issues: [],
  }
}

// ───────── 時鐘與計時器 ─────────

interface Timer {
  at: number
  fn: () => void
  every?: number
  owner: Device
}

export class Clock {
  t: number
  private seq = 0
  private timers = new Map<number, Timer>()
  errors: unknown[] = []
  constructor(t0: number) {
    this.t = t0
  }
  api(owner: Device) {
    const add = (fn: unknown, ms: unknown, every: boolean) => {
      const id = ++this.seq
      const d = Math.max(0, Number(ms) || 0)
      if (typeof fn === 'function') this.timers.set(id, { at: this.t + d, fn: fn as () => void, owner, ...(every ? { every: Math.max(1, d) } : {}) })
      return id
    }
    const clear = (id: unknown) => void this.timers.delete(Number(id))
    return {
      setTimeout: (fn: unknown, ms?: unknown) => add(fn, ms, false),
      setInterval: (fn: unknown, ms?: unknown) => add(fn, ms, true),
      clearTimeout: clear,
      clearInterval: clear,
    }
  }
  /** 時間往前走 ms；途中到期的計時器依時間順序執行 */
  async advance(ms: number) {
    const end = this.t + ms
    for (;;) {
      await settle()
      let next: [number, Timer] | undefined
      for (const e of this.timers) {
        if (e[1].at > end) continue
        if (!next || e[1].at < next[1].at || (e[1].at === next[1].at && e[0] < next[0])) next = e
      }
      if (!next) break
      const [id, tm] = next
      this.t = Math.max(this.t, tm.at)
      if (!tm.owner.alive) {
        this.timers.delete(id)
        continue
      }
      if (tm.every) tm.at += tm.every
      else this.timers.delete(id)
      try {
        tm.fn()
      } catch (e) {
        this.errors.push(e)
      }
    }
    this.t = end
    await settle()
  }
}

// ───────── 假的本機 server ─────────

export interface ReqLog {
  device: string
  method: string
  url: string
  body: unknown
  status: number
}

export class FakeServer {
  answers: AnswersData = emptyAnswers()
  progress: ProgressData = emptyProgress()
  constructor(private clock: Clock) {}
  handle(dev: Device, method: string, url: string, body: unknown): { status: number; json: unknown } {
    const now = this.clock.t
    const u = url.split('?')[0]
    if (u === '/api/health') return { status: 200, json: { ok: true, local: dev.local, auth: dev.local || dev.token } }
    if (u !== '/api/answers' && u !== '/api/progress') return { status: 404, json: { error: '找不到 API' } }
    if (!dev.local && !dev.token) return { status: 401, json: { error: '區網存取需要 token' } }
    try {
      if (u === '/api/answers') {
        if (method === 'GET') return { status: 200, json: clone({ ...this.answers, serverTime: now }) }
        return { status: 200, json: clone(applyAnswersPut(this.answers, body, now).response) }
      }
      if (method === 'GET') return { status: 200, json: clone({ ...this.progress, serverTime: now }) }
      this.progress = applyProgressPut(this.progress, body, now)
      return { status: 200, json: clone({ ...this.progress, serverTime: now }) }
    } catch (e) {
      if (e instanceof DataApiError) return { status: e.status, json: { error: e.message } }
      throw e
    }
  }
}

// ───────── 網路（可扣住回應）─────────

export class Net {
  log: ReqLog[] = []
  private holds: Array<(r: ReqLog) => boolean> = []
  private held: Array<{ req: ReqLog; release: () => void }> = []
  constructor(private server: FakeServer) {}
  /** 符合條件的請求：server 照常處理，但回應扣住，直到 release */
  hold(match: (r: ReqLog) => boolean) {
    this.holds.push(match)
  }
  release(match: (r: ReqLog) => boolean = () => true) {
    this.holds = []
    for (const h of this.held.filter((x) => match(x.req))) {
      this.held.splice(this.held.indexOf(h), 1)
      h.release()
    }
  }
  heldCount = () => this.held.length
  fetchFor(dev: Device) {
    return async (url: string, init: { method?: string; body?: string; keepalive?: boolean } = {}) => {
      if (dev.offline || (!dev.alive && !init.keepalive)) throw new TypeError('Failed to fetch')
      const method = init.method ?? 'GET'
      const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined
      const res = this.server.handle(dev, method, url, body)
      const req: ReqLog = { device: dev.name, method, url, body, status: res.status }
      this.log.push(req)
      if (this.holds.some((m) => m(req))) await new Promise<void>((resolve) => this.held.push({ req, release: resolve }))
      return { ok: res.status >= 200 && res.status < 300, status: res.status, json: async () => clone(res.json) }
    }
  }
}

// ───────── 假的雲端 db（claude.ai Artifact 的 db capability）─────────

interface Snap {
  id: string
  exists: boolean
  data(): Record<string, unknown> | undefined
}

export class FakeDb {
  docs = new Map<string, Record<string, unknown>>()
  calls: Array<{ device: string; op: 'get' | 'set' | 'list'; path: string }> = []
  /** 某台裝置 set 完之後立刻執行（模擬別台在「寫入」與「讀回確認」之間寫了同一份文件）；prev 是這次 set 之前的內容 */
  afterSet: ((path: string, device: string, prev: Record<string, unknown> | undefined) => void) | null = null
  /** set 之後，接下來幾次 get 仍讀到 set 之前的內容（db 不保證 set 後立即讀到新值） */
  staleReads = 0
  private stale = new Map<string, { left: number; prev: Record<string, unknown> | undefined }>()
  /** 不是 null 時，set 會等它 resolve 才完成（檢查多份文件的 set 是否同時發出） */
  setGate: Promise<void> | null = null
  /** 每次 get 讀到的文件（測試用：看讀回來的是新值還是舊值） */
  readLog: Array<{ device: string; path: string; data: Record<string, unknown> | undefined }> = []
  sets = (device?: string, prefix = '') => this.calls.filter((c) => c.op === 'set' && (!device || c.device === device) && c.path.startsWith(prefix)).length
  doc = (p: string) => this.docs.get(p)
  api(dev: Device) {
    const snap = (p: string): Snap => {
      const d = this.docs.get(p)
      return { id: p.split('/').pop()!, exists: !!d, data: () => (d ? clone(d) : undefined) }
    }
    const docRef = (p: string): unknown => ({
      get: async () => {
        this.calls.push({ device: dev.name, op: 'get', path: p })
        const st = this.stale.get(p)
        if (st && st.left > 0) {
          st.left--
          this.readLog.push({ device: dev.name, path: p, data: clone(st.prev) })
          return { id: p.split('/').pop()!, exists: !!st.prev, data: () => clone(st.prev) }
        }
        this.readLog.push({ device: dev.name, path: p, data: clone(this.docs.get(p)) })
        return snap(p)
      },
      set: async (body: Record<string, unknown>) => {
        this.calls.push({ device: dev.name, op: 'set', path: p })
        if (this.setGate) await this.setGate
        const prev = this.docs.get(p)
        this.docs.set(p, clone(body))
        if (this.staleReads) this.stale.set(p, { left: this.staleReads, prev: clone(prev) })
        this.afterSet?.(p, dev.name, clone(prev))
      },
      update: async () => {
        throw { code: 'invalid_argument', message: 'not used' }
      },
      delete: async () => void this.docs.delete(p),
      collection: (sub: string) => colRef(`${p}/${sub}`),
    })
    const colRef = (p: string): unknown => ({
      doc: (id: string) => docRef(`${p}/${id}`),
      get: async () => {
        this.calls.push({ device: dev.name, op: 'list', path: p })
        const docs = [...this.docs.keys()].filter((k) => k.startsWith(p + '/') && !k.slice(p.length + 1).includes('/')).sort().map(snap)
        return { docs, size: docs.length, empty: docs.length === 0 }
      },
    })
    return { doc: docRef, collection: colRef }
  }
}

// ───────── localStorage ─────────

export class FakeStorage {
  map = new Map<string, string>()
  /** 寫入失敗（容量滿）的 key */
  failSet: (k: string) => boolean = () => false
  /** 完全不能用（沙箱、無痕） */
  disabled = false
  getItem(k: string) {
    if (this.disabled) throw new Error('SecurityError')
    return this.map.has(k) ? this.map.get(k)! : null
  }
  setItem(k: string, v: string) {
    if (this.disabled || this.failSet(k)) throw new Error('QuotaExceededError')
    this.map.set(k, String(v))
  }
  removeItem(k: string) {
    if (this.disabled) throw new Error('SecurityError')
    this.map.delete(k)
  }
  json(k: string): unknown {
    const v = this.map.get(k)
    return v === undefined ? null : JSON.parse(v)
  }
}

// ───────── 裝置 ─────────

export interface Env {
  clock: Clock
  server: FakeServer
  net: Net
  db: FakeDb
}

export function makeEnv(t0 = Date.now()): Env {
  const clock = new Clock(t0)
  const server = new FakeServer(clock)
  return { clock, server, net: new Net(server), db: new FakeDb() }
}

export interface DeviceOpts {
  name: string
  mode: 'server' | 'static'
  content: Content
  storage?: FakeStorage
  /** 本機（127.0.0.1）連線：不需要 token */
  local?: boolean
  /** 區網連線有沒有 token */
  token?: boolean
  /** 靜態版：有沒有雲端 db（window.claude） */
  cloud?: boolean
  ua?: string
}

/* eslint-disable @typescript-eslint/no-explicit-any */
export class Device {
  name: string
  alive = true
  offline = false
  local: boolean
  token: boolean
  storage: FakeStorage
  logs: unknown[][] = []
  win: EventTarget & Record<string, unknown>
  doc: EventTarget & { visibilityState: string }
  BT: any
  constructor(
    private env: Env,
    code: string,
    o: DeviceOpts,
  ) {
    this.name = o.name
    this.local = o.local ?? true
    this.token = o.token ?? false
    this.storage = o.storage ?? new FakeStorage()
    this.win = new EventTarget() as EventTarget & Record<string, unknown>
    this.doc = Object.assign(new EventTarget(), { visibilityState: 'visible' })
    const quiet = { log: (...a: unknown[]) => this.logs.push(a), warn: (...a: unknown[]) => this.logs.push(a), error: (...a: unknown[]) => this.logs.push(a), info: () => {}, debug: () => {} }
    const ctx: Record<string, unknown> = {
      console: quiet,
      TextEncoder,
      TextDecoder,
      AbortController,
      URL,
      URLSearchParams,
      queueMicrotask,
      structuredClone,
      Event,
      EventTarget,
      ...env.clock.api(this),
      window: this.win,
      document: this.doc,
      localStorage: this.storage,
      navigator: { userAgent: o.ua ?? 'Macintosh' },
      location: { href: 'http://localhost:5173/', pathname: '/', search: '', hash: '' },
      history: { replaceState() {} },
      fetch: env.net.fetchFor(this),
      __now: () => env.clock.t,
      __BT_CONTENT_JSON__: JSON.stringify(o.content),
    }
    if (o.mode === 'static' && o.cloud !== false) {
      const db = env.db.api(this)
      this.win.claude = { use: async (n: string) => (n === 'db' ? db : n === 'user' ? { id: async () => 'u1' } : null) }
    }
    vm.createContext(ctx)
    vm.runInContext('Date.now = () => __now(); globalThis.__BT_CONTENT__ = JSON.parse(__BT_CONTENT_JSON__)', ctx)
    vm.runInContext(code, ctx, { filename: `bt-${o.mode}.js` })
    this.BT = ctx.BT
  }
  get store() {
    return this.BT.store
  }
  entry(id: string) {
    return this.BT.store.getState().answers.answers[id]
  }
  /** 欄位目前在 store 的版本（{ v, at }） */
  stored(id: string, field: string) {
    const e = this.entry(id)
    return { v: this.BT.data.fieldText(e, field), at: this.BT.data.fieldAt(e, field) }
  }
  /** 開一個輸入框（等同 useEditableField）：回傳 FieldEditor，sync() 模擬 React 在 store 變動後的 effect。meta：事實的題目文字與位置 */
  editor(id: string, field: string, meta?: { label?: string; pos?: [number, number] }) {
    const ed = new this.BT.FieldEditor(id, field, this.stored(id, field), meta)
    return {
      ed,
      sync: () => ed.sync(this.stored(id, field)),
      focus: () => {
        ed.focus()
        void this.BT.store.pullFresh(id, true)
      },
      blur: () => ed.blur(this.stored(id, field)),
    }
  }
  hide() {
    this.doc.visibilityState = 'hidden'
    this.doc.dispatchEvent(new Event('visibilitychange'))
  }
  show() {
    this.doc.visibilityState = 'visible'
    this.doc.dispatchEvent(new Event('visibilitychange'))
  }
  /** 關閉分頁：pagehide（flush）後計時器與一般請求都停止 */
  close() {
    this.win.dispatchEvent(new Event('pagehide'))
    this.alive = false
  }
  /** 其他分頁寫了 localStorage（storage 事件） */
  storageEvent(key: string) {
    const ev = new Event('storage') as Event & { key: string; newValue: string | null }
    ev.key = key
    ev.newValue = this.storage.map.get(key) ?? null
    this.win.dispatchEvent(ev)
  }
}

export async function device(env: Env, o: DeviceOpts): Promise<Device> {
  return new Device(env, await bundle(o.mode), o)
}
