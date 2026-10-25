// store 層的整合測試：打包後真正的前端模組（store、cloud、lifecycle、fieldEditor）在 vm context 裡執行，
// 每台裝置各自的 localStorage／fetch／計時器，共用假的時鐘、假的本機 server（與 server-plugin 同一份寫入邏輯）與假的雲端 db。
// 另外用真正的 server middleware 測 426 與舊資料檔遷移，並開兩個子行程測跨行程檔案鎖（dev 與 preview 同時執行）。
// 由 scripts/regress-data.ts 呼叫（npm run test:data）。
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { factIdsOf, setFactCatalog } from '../src/lib/data.ts'
import type { AnswerEntry } from '../src/types.ts'
import { FakeStorage, device, makeContent, makeEnv, settle, type Device, type Env } from './regress/harness.ts'
import { callApi } from './regress/http.ts'

type Check = (name: string, cond: boolean, detail?: string) => void

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const TMP = path.join(ROOT, '.runner-tmp/regress-store')

const C1 = makeContent(['團隊人數', '使用的 CI'])
const [kT, kC] = factIdsOf(['團隊人數', '使用的 CI'])
const C2 = makeContent(['新加的事實', '團隊人數']) // 內容改版：最前面插入一個事實、刪掉「使用的 CI」
const [kN] = factIdsOf(['新加的事實'])
const fT = `f:${kT}`
const fC = `f:${kC}`
const fN = `f:${kN}`
/** 事實欄位的題目文字與位置（FactField 會把它們存進 factQ／factPos） */
type Meta = { label?: string; pos?: [number, number] }
const META: Record<string, Meta> = { [fT]: { label: '團隊人數', pos: [0, 2] }, [fC]: { label: '使用的 CI', pos: [1, 2] }, [fN]: { label: '新加的事實', pos: [0, 2] } }
const C3 = makeContent(['團隊規模（人數）', '使用的 CI']) // 內容改版：第 0 個事實改了文字（id 變了），數量不變
const [kT3] = factIdsOf(['團隊規模（人數）'])
const C4 = makeContent(['新加的事實', '團隊規模（人數）', '使用的 CI']) // 插入＋改字：位置與數量都對不上

const hasHist = (e: AnswerEntry | undefined, field: string, text: string) => !!e?.history.some((h) => h.field === field && h.text === text)
const show = (o: unknown) => JSON.stringify(o)?.slice(0, 600)

async function serverDevice(env: Env, name: string, o: Partial<Parameters<typeof device>[1]> = {}): Promise<Device> {
  const d = await device(env, { name, mode: 'server', content: C1, ...o })
  await d.store.initStore()
  await settle()
  return d
}
async function cloudDevice(env: Env, name: string, o: Partial<Parameters<typeof device>[1]> = {}): Promise<Device> {
  const d = await device(env, { name, mode: 'static', content: C1, ...o })
  await d.BT.cloud.initCloud()
  await settle()
  return d
}
/** 打字並等 debounce 存檔、push（本機版 1 秒、雲端 2 秒） */
async function type(env: Env, d: Device, id: string, field: string, text: string, wait = 2500, meta: Meta | undefined = META[field]) {
  const e = d.editor(id, field, meta)
  e.ed.input(text)
  e.ed.save()
  await env.clock.advance(wait)
}

export async function runStoreTests(check: Check) {
  // server（假 server）遷移舊格式時用的事實對照：和 server-data.json 一樣是內容 C1
  setFactCatalog((id) => (id === 'q1' ? { ids: [kT, kC], labels: ['團隊人數', '使用的 CI'] } : undefined))

  // ───────── H1：編輯中收到遠端新版 → 繼續打字 → 兩個版本都保留 ─────────
  for (const field of ['text', fT]) {
    const label = field === 'text' ? 'answer text' : 'fact'
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac')
    const phone = await serverDevice(env, 'iPhone', { local: false, token: true, ua: 'iPhone' })
    await type(env, mac, 'q1', field, 'A：電腦寫的初版')
    await phone.store.pull()
    const m = mac.editor('q1', field)
    m.focus() // 游標停在輸入框
    await env.clock.advance(50)
    await type(env, phone, 'q1', field, 'B：手機改的版本')
    await env.clock.advance(20_000) // 前景每 20 秒 pull：手機的版本進到電腦的 state
    m.sync()
    const pending = mac.stored('q1', field).v === 'B：手機改的版本' && m.ed.value === 'A：電腦寫的初版' && m.ed.pendingRemote
    m.ed.input('A：電腦寫的初版＋繼續打的字')
    m.ed.save()
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    const cur = field === 'text' ? s.text : s.facts[kT]
    check(`H1 server (${label}): while editing, a remote update only marks the editor (input not replaced)`, pending, show(mac.stored('q1', field)))
    check(`H1 server (${label}): typing on after the remote update keeps BOTH versions (phone's goes to history)`, cur === 'A：電腦寫的初版＋繼續打的字' && hasHist(s, field, 'B：手機改的版本'), show(s))
    check(`H1 server (${label}): the device's own copy also has the overwritten version in history`, hasHist(mac.entry('q1'), field, 'B：手機改的版本'))
  }
  {
    // AI 評分前（Open.tsx）：commit（輸入框自己的 base）→ pull
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac')
    const phone = await serverDevice(env, 'iPhone', { local: false, token: true })
    await type(env, mac, 'q1', 'text', 'v1')
    const m = mac.editor('q1', 'text')
    m.focus()
    await phone.store.pull()
    await type(env, phone, 'q1', 'text', 'phone-v2')
    await env.clock.advance(20_000)
    m.sync()
    m.ed.input('v1 + 評分前補的一句')
    m.ed.save() // field.commit()
    await mac.store.pullFresh('q1', true)
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    check('H1 server (grade path): commit-before-grade with a stale editor archives the remote version instead of dropping it', s.text === 'v1 + 評分前補的一句' && hasHist(s, 'text', 'phone-v2'), show(s))
  }
  {
    // 靜態版（雲端）：同樣的情境
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    const phone = await cloudDevice(env, 'iPhone')
    await type(env, mac, 'q1', 'text', 'A：電腦寫的初版')
    await phone.store.pullFresh(undefined, true)
    await settle()
    const m = mac.editor('q1', 'text')
    m.focus()
    await env.clock.advance(50)
    await type(env, phone, 'q1', 'text', 'B：手機改的版本')
    await env.clock.advance(20_000)
    m.sync()
    const pending = mac.stored('q1', 'text').v === 'B：手機改的版本' && m.ed.pendingRemote
    m.ed.input('A：電腦寫的初版＋繼續打的字')
    m.ed.save()
    await env.clock.advance(2500)
    const doc = env.db.doc('data/users/u1/answers3/items/q1') as unknown as AnswerEntry | undefined
    check('H1 cloud: remote update arrives while editing (editor keeps the local text)', pending)
    check('H1 cloud: typing on keeps both versions in the cloud document', doc?.text === 'A：電腦寫的初版＋繼續打的字' && hasHist(doc, 'text', 'B：手機改的版本'), show(doc))
  }

  // ───────── M1：舊格式升級時的推斷不會被跳過 ─────────
  {
    const env = makeEnv()
    const desk = await serverDevice(env, 'Desk')
    await type(env, desk, 'q1', 'text', 'server 上的舊版')
    await type(env, desk, 'q1', fT, 'server 的事實')
    const newer = env.clock.t + 5_000
    // 手機：升級前（v2，事實以 index 對齊）離線改了內容，沒有 dirty 紀錄
    const storage = new FakeStorage()
    storage.map.set(
      'bt.answers.v2',
      JSON.stringify({ version: 2, updatedAt: newer, answers: { q1: { text: '手機離線改的（較新）', textAt: newer, facts: ['手機的事實'], factsAt: [newer], gradeAt: 0, history: [], updatedAt: newer } } }),
    )
    await env.clock.advance(10_000)
    // 升級後先用舊書籤開啟（區網、沒有 token → 401），切到背景（flushFinal 寫入 dirty={}）
    const p1 = await serverDevice(env, 'iPhone', { storage, local: false, token: false })
    p1.hide()
    await settle()
    p1.close()
    const inferAfter401 = storage.json('bt.infer.v3') as { all?: boolean } | null
    const dirtyWritten = storage.map.has('bt.dirty.v3')
    // 改用含 token 的網址重新開啟
    const p2 = await serverDevice(env, 'iPhone', { storage, local: false, token: true })
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    check('M1: the 401 visit and the hidden-page flush do not mark the migration as done', dirtyWritten && inferAfter401?.all === true, show({ inferAfter401, dirtyWritten }))
    check('M1: after reopening with the token, the newer pre-upgrade local text wins on the server and the server version goes to history', s.text === '手機離線改的（較新）' && hasHist(s, 'text', 'server 上的舊版'), show(s))
    check('M1: the pre-upgrade index-based fact maps to the fact id and wins; the server value is kept in history', s.facts[kT] === '手機的事實' && hasHist(s, fT, 'server 的事實'), show(s.facts))
    check('M1: the marker is written only after the inference ran; the v2 localStorage key is removed after migration', (storage.json('bt.infer.v3') as { all?: boolean })?.all === false && !storage.map.has('bt.answers.v2') && p2.entry('q1')?.text === '手機離線改的（較新）')
  }

  // ───────── M2：亂序的快照（GET 在 ack 之後才套用）不會把本地退回舊版 ─────────
  {
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac')
    await type(env, mac, 'q1', 'text', 'v1')
    const e = mac.editor('q1', 'text')
    e.ed.input('v2')
    e.ed.save() // dirty；1 秒後 push
    // pull：GET progress 的回應被扣住；GET answers 立刻處理（server 還是 v1）
    env.net.hold((r) => r.method === 'GET' && r.url === '/api/progress')
    const pulling = mac.store.pull()
    await settle()
    await env.clock.advance(1100) // push：PUT answers 被 ack（server v2），本地換成 server 的版本
    e.sync()
    const ackedV2 = mac.entry('q1')?.text === 'v2' && env.server.answers.answers.q1.text === 'v2'
    env.net.release()
    await pulling
    await settle()
    const reverted = e.sync()
    const putsBefore = env.net.log.filter((r) => r.method === 'PUT' && r.url === '/api/answers').length
    await env.clock.advance(1500)
    const putsAfter = env.net.log.filter((r) => r.method === 'PUT' && r.url === '/api/answers').length
    check('M2: setup — the edit was acked before the stale snapshot arrived', ackedV2)
    check('M2: the stale snapshot does not revert the local text or the editor', mac.entry('q1')?.text === 'v2' && !reverted && e.ed.value === 'v2', show(mac.entry('q1')))
    check('M2: no false "another device updated this" notice and no spurious re-push', !('q1' in mac.store.getState().remoteNotice) && putsAfter === putsBefore && env.server.answers.answers.q1.text === 'v2')
    // 對照：server 的資料真的倒退了（例如資料檔被還原）→ 本地較新的版本補送回去，server 的內容進 history
    env.server.answers.answers.q1 = { ...env.server.answers.answers.q1, text: '還原的舊備份', textAt: env.server.answers.answers.q1.textAt - 10_000 }
    await mac.store.pull()
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    check('M2: a genuinely regressed server (restored file) gets the newer local version back, with its own value archived', s.text === 'v2' && hasHist(s, 'text', '還原的舊備份'), show(s))
  }

  // ───────── M3：離開頁面時，雲端寫入排在所有元件的 flush 之後 ─────────
  {
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    await env.clock.advance(100)
    // 雲端存檔啟動之後才掛載的輸入框（換到某一題）：它的 flush 註冊在 early 階段
    const m = mac.editor('q1', 'text')
    mac.BT.lifecycle.onPageHide(() => m.ed.save())
    m.ed.input('最後一次編輯（還在 debounce）')
    const setsBefore = env.db.sets('Mac', 'data/users/u1/answers3/items/q1')
    mac.hide()
    await settle(8)
    const doc = env.db.doc('data/users/u1/answers3/items/q1') as unknown as AnswerEntry | undefined
    check('M3: on page hide the cloud write runs after component flushes, so the last edit is written immediately', setsBefore === 0 && doc?.text === '最後一次編輯（還在 debounce）', show(doc))
    // 本機版：最後一道 flush（keepalive PUT）也包含最後一次編輯
    const env2 = makeEnv()
    const d = await serverDevice(env2, 'Mac')
    const m2 = d.editor('q1', fT)
    d.BT.lifecycle.onPageHide(() => m2.ed.save())
    m2.ed.input('離開前最後打的事實')
    d.close()
    await settle()
    check('M3 server: the final keepalive PUT carries the last edit', env2.server.answers.answers.q1?.facts[kT] === '離開前最後打的事實', show(env2.server.answers.answers.q1))
  }

  // ───────── 事實以 id 對齊：不同內容版本的裝置 ─────────
  {
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac') // 內容 C1：[團隊人數, 使用的 CI]
    await type(env, mac, 'q1', fT, '10 人')
    await type(env, mac, 'q1', fC, 'GitHub Actions')
    const phone = await serverDevice(env, 'iPhone', { content: C2, local: false, token: true }) // 內容 C2：[新加的事實, 團隊人數]
    const ex = phone.BT.getIndex().exerciseById.get('q1')
    const pend = phone.BT.pendingFacts(phone.BT.getIndex(), phone.store.getState().answers).find((p: { exercise: { id: string } }) => p.exercise.id === 'q1')
    const shownAtIndex1 = phone.editor('q1', `f:${ex.factIds[1]}`).ed.value
    const shownAtIndex0 = phone.editor('q1', `f:${ex.factIds[0]}`).ed.value
    const removed = Object.keys(phone.entry('q1').facts).filter((k) => !ex.factIds.includes(k))
    check('Facts: a device on newer content (fact inserted at the top) shows each answer under the same fact', shownAtIndex1 === '10 人' && shownAtIndex0 === '' && pend?.missing.length === 1 && pend.missing[0] === kN, show({ shownAtIndex0, shownAtIndex1, pend: pend?.missing }))
    check('Facts: a fact removed from content stays in the data with its label (shown as a removed fact)', removed.length === 1 && removed[0] === kC && phone.entry('q1').facts[kC] === 'GitHub Actions' && phone.entry('q1').factQ?.[kC] === '使用的 CI')
    await type(env, phone, 'q1', fN, '新事實的答案')
    await mac.store.pull()
    const s = env.server.answers.answers.q1
    check('Facts: both content versions write to their own facts; nothing is shifted or overwritten', s.facts[kT] === '10 人' && s.facts[kC] === 'GitHub Actions' && s.facts[kN] === '新事實的答案' && mac.entry('q1').facts[kN] === '新事實的答案', show(s.facts))
    // 舊的 localStorage（v2，事實以 index 對齊）在開機時用當時的內容對到 id
    const storage = new FakeStorage()
    storage.map.set('bt.answers.v2', JSON.stringify({ version: 2, updatedAt: 5, answers: { q2: { text: 'x', facts: [], updatedAt: 5 }, q1: { text: '', facts: ['舊 10 人', '舊 CI'], factsAt: [5, 5], textAt: 0, gradeAt: 0, history: [{ field: 'f1', text: '更舊的 CI', at: 1, replacedAt: 2, device: '' }], updatedAt: 5 } } }))
    const legacy = await device(env, { name: 'Old', mode: 'server', content: C1, storage })
    const le = legacy.entry('q1')
    check('Facts: legacy index-based localStorage data migrates onto fact ids (values and history)', le.facts[kT] === '舊 10 人' && le.facts[kC] === '舊 CI' && hasHist(le, fC, '更舊的 CI'), show(le))
  }

  // ───────── 內容改了事實的文字：答案自動搬到同位置的新事實（可同步、冪等）；不確定的不搬，可以手動搬 ─────────
  {
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac') // 內容 C1
    await type(env, mac, 'q1', fT, '10 人')
    await type(env, mac, 'q1', fC, 'GitHub Actions')
    const desk = await serverDevice(env, 'Desk') // 還停在舊內容 C1 的另一台
    mac.BT.setContent(C3) // dev 熱更新：第 0 個事實改了文字
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    check('Relink: a renamed fact (same position, same count) — the answer moves to the new fact id; the old field is cleared and its value kept in history', s.facts[kT3] === '10 人' && s.facts[kT] === '' && hasHist(s, fT, '10 人') && s.facts[kC] === 'GitHub Actions' && mac.entry('q1').facts[kT3] === '10 人', show(s))
    const phone = await serverDevice(env, 'iPhone', { content: C3, local: false, token: true })
    const puts = () => env.net.log.filter((r) => r.method === 'PUT' && r.url === '/api/answers').length
    const puts0 = puts()
    const again = mac.store.relinkFacts() + phone.store.relinkFacts()
    await desk.store.pull()
    const deskMoves = desk.store.relinkFacts()
    await env.clock.advance(1500)
    check('Relink: another device on the new content sees the answer under the renamed fact', phone.editor('q1', `f:${kT3}`).ed.value === '10 人')
    check('Relink: idempotent, and a device still on the old content does not move it back', again === 0 && deskMoves === 0 && puts() === puts0 && env.server.answers.answers.q1.facts[kT3] === '10 人', show({ again, deskMoves, puts0, puts: puts() }))
    // 插入＋改字（位置與數量都對不上）：不自動搬，交給「移到目前事實」
    const env2 = makeEnv()
    const m2 = await serverDevice(env2, 'Mac')
    await type(env2, m2, 'q1', fT, '10 人')
    m2.BT.setContent(C4)
    await env2.clock.advance(1500)
    const s1 = env2.server.answers.answers.q1
    const kept = s1.facts[kT] === '10 人' && s1.facts[kT3] === undefined
    const ok = m2.store.moveFact('q1', kT, kT3)
    await env2.clock.advance(1500)
    const s2 = env2.server.answers.answers.q1
    check('Relink: insert + rename (position and count differ) is not moved automatically', kept, show(s1))
    check('Manual move ("移到目前事實"): the answer moves and syncs; the source value stays in history', ok && s2.facts[kT3] === '10 人' && s2.facts[kT] === '' && hasHist(s2, fT, '10 人') && s2.factPos?.[kT3]?.[0] === 1, show(s2))
  }
  {
    // 靜態版：雲端的口徑是舊內容時寫的；新內容的裝置讀到後自動搬，並寫回雲端
    const env = makeEnv()
    const oldDev = await cloudDevice(env, 'Old')
    await type(env, oldDev, 'q1', fT, '雲端的 10 人')
    const newDev = await cloudDevice(env, 'New', { content: C3 })
    await env.clock.advance(2500)
    const doc = env.db.doc('data/users/u1/answers3/items/q1') as unknown as AnswerEntry
    check('Relink cloud: a device on the new content moves the answer and writes it back to the cloud', doc.facts[kT3] === '雲端的 10 人' && doc.facts[kT] === '' && hasHist(doc, fT, '雲端的 10 人') && newDev.entry('q1').facts[kT3] === '雲端的 10 人', show(doc))
  }

  // ───────── 作答次數：storage 不能寫（每次載入都是新裝置）也不會低估 ─────────
  {
    const env = makeEnv()
    for (let i = 0; i < 22; i++) {
      const st = new FakeStorage()
      st.disabled = true
      const d = await serverDevice(env, `eph${i}`, { storage: st })
      d.store.recordResult('c1', i % 2 ? 'correct' : 'wrong')
      await env.clock.advance(1500)
      d.close()
    }
    const p = env.server.progress.exercises.c1
    check('Counts: 22 page loads without storage (22 device ids) are all counted on the server', p.attempts === 22 && p.correct === 11 && (p.folded?.length ?? 0) === 2, show(p))
    const keep = await serverDevice(env, 'Mac')
    keep.store.recordResult('c1', 'correct')
    await env.clock.advance(1500)
    await keep.store.pull()
    const local = keep.store.getState().progress.exercises.c1
    check('Counts: a device that pulls the folded counts and answers again is not double counted', env.server.progress.exercises.c1.attempts === 23 && local.attempts === 23 && env.server.progress.exercises.c1.correct === 12, show(local))
  }

  // ───────── 雲端：先讀再寫的競態 → 讀回確認；連續多次確定被覆蓋才重新合併再寫 ─────────
  {
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    await type(env, mac, 'q1', 'text', 'A0')
    const p = 'data/users/u1/answers3/items/q1'
    // 別台在我們 set 之前讀了這份文件、在我們 set 之後才寫入（它的內容沒有我們這次的編輯；它改的是事實）
    let injected = 0
    env.db.afterSet = (path, dev, prev) => {
      if (dev !== 'Mac' || path !== p || injected || !prev) return
      injected++
      const b = prev as unknown as AnswerEntry
      env.db.docs.set(p, { ...b, facts: { ...b.facts, [kT]: 'B 改的事實' }, factsAt: { ...b.factsAt, [kT]: env.clock.t + 1 }, updatedAt: env.clock.t + 1 } as unknown as Record<string, unknown>)
    }
    const sets0 = env.db.sets('Mac', p)
    await type(env, mac, 'q1', 'text', 'A1：電腦的新版')
    const first = { sets: env.db.sets('Mac', p) - sets0, status: mac.store.getState().sync.status }
    await env.clock.advance(3_100) // 第 2 次確認：仍不含
    const second = { sets: env.db.sets('Mac', p) - sets0, status: mac.store.getState().sync.status }
    await env.clock.advance(3_100) // 第 3 次確認：確定被覆蓋 → 重新合併再寫
    let doc = env.db.doc(p) as unknown as AnswerEntry
    check('Cloud race: a mismatching read-back is not treated as an error and is not rewritten right away', first.sets === 1 && first.status === 'syncing' && second.sets === 1 && second.status === 'syncing', show({ first, second }))
    check('Cloud race: after repeated confirmations the doc is re-merged and rewritten once (both edits kept)', env.db.sets('Mac', p) - sets0 === 2 && doc.text === 'A1：電腦的新版' && doc.facts[kT] === 'B 改的事實' && mac.store.getState().sync.status === 'ok', show(doc))
    // 同一個欄位：別台的較新版本贏，我們的版本留在雲端的 history
    env.db.afterSet = (path, dev, prev) => {
      if (dev !== 'Mac' || path !== p || injected > 1 || !prev) return
      injected++
      const b = prev as unknown as AnswerEntry
      env.db.docs.set(p, { ...b, text: 'B 的版本（較新）', textAt: env.clock.t + 1, updatedAt: env.clock.t + 1 } as unknown as Record<string, unknown>)
    }
    await type(env, mac, 'q1', 'text', 'A2：電腦又改了', 9_000)
    doc = env.db.doc(p) as unknown as AnswerEntry
    check('Cloud race: same-field conflict — the newer one is current and ours is kept in the cloud history', doc.text === 'B 的版本（較新）' && hasHist(doc, 'text', 'A2：電腦又改了') && doc.facts[kT] === 'B 改的事實', show(doc))
    // 一直被別台蓋掉：只在連續多次確認後才重寫（有節制），狀態一直是「同步中」而不是「雲端異常」
    let n = 0
    env.db.afterSet = (path, dev, prev) => {
      if (dev !== 'Mac' || path !== p || !prev) return
      n++
      const b = prev as unknown as AnswerEntry
      env.db.docs.set(p, { ...b, facts: { ...b.facts, [kC]: `別台第 ${n} 次` }, factsAt: { ...b.factsAt, [kC]: env.clock.t + n }, updatedAt: env.clock.t + n } as unknown as Record<string, unknown>)
    }
    const setTimes: number[] = []
    const prevHook = env.db.afterSet
    env.db.afterSet = (path, dev, prev) => {
      if (dev === 'Mac' && path === p) setTimes.push(env.clock.t)
      prevHook(path, dev, prev)
    }
    const statuses = new Set<string>()
    await type(env, mac, 'q1', 'text', 'A3：一直被打斷', 2_100) // debounce 到期，第一次寫入
    for (let i = 0; i < 30; i++) {
      statuses.add(mac.store.getState().sync.status)
      await env.clock.advance(1_000)
    }
    const gaps = setTimes.slice(1).map((t, i) => t - setTimes[i])
    env.db.afterSet = null
    await env.clock.advance(12_000)
    doc = env.db.doc(p) as unknown as AnswerEntry
    check(
      'Cloud race: persistent interference — a rewrite only after 3 confirmations (>= 6 s apart), status stays "syncing", never a cloud error',
      setTimes.length >= 2 && gaps.every((g) => g >= 6_000) && statuses.size === 1 && statuses.has('syncing'),
      show({ sets: setTimes.length, gaps, statuses: [...statuses] }),
    )
    check('Cloud race: once the interference stops it converges with every edit present', doc.text === 'A3：一直被打斷' && doc.facts[kC] === `別台第 ${n} 次` && mac.store.getState().sync.status === 'ok', show(doc))
  }

  // ───────── 雲端：set 之後讀到舊值（db 不保證立即讀到）只是稍後再確認，不重寫、不報錯 ─────────
  {
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    await type(env, mac, 'q1', 'text', 'v1')
    const p = 'data/users/u1/answers3/items/q1'
    env.db.staleReads = 1
    const sets0 = env.db.sets('Mac', p)
    await type(env, mac, 'q1', 'text', 'v2')
    const after = { status: mac.store.getState().sync.status, sets: env.db.sets('Mac', p) - sets0, readBack: (env.db.readLog.filter((r) => r.path === p).at(-1)?.data as AnswerEntry | undefined)?.text }
    env.db.staleReads = 0
    await env.clock.advance(3_100)
    const later = { status: mac.store.getState().sync.status, sets: env.db.sets('Mac', p) - sets0 }
    check('Cloud verify: a stale read right after set keeps the status "syncing" (not a cloud error)', after.readBack === 'v1' && after.status === 'syncing' && after.sets === 1, show(after))
    check('Cloud verify: the later re-check confirms the write without rewriting it', later.status === 'ok' && later.sets === 1 && (env.db.doc(p) as unknown as AnswerEntry).text === 'v2', show(later))
  }

  // ───────── 雲端：只差在歷史版本的 replacedAt（重新合併同一個輸掉的版本）不會觸發寫入 ─────────
  {
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    await type(env, mac, 'q2', 'text', '舊的草稿')
    await type(env, mac, 'q2', 'text', '目前的版本') // 雲端：目前的版本＋歷史裡有舊的草稿
    const p = 'data/users/u1/answers3/items/q2'
    const other = JSON.parse(JSON.stringify(env.db.doc(p))) as AnswerEntry
    other.history = other.history.map((h) => ({ ...h, replacedAt: h.replacedAt - 5_000 })) // 別台寫的同一份內容，只是被取代的時間較早
    env.db.docs.set(p, other as unknown as Record<string, unknown>)
    const sets0 = env.db.sets('Mac', p)
    await mac.store.pullFresh(undefined, true)
    await env.clock.advance(2_500)
    check('Cloud compare: a history replacedAt-only difference is not treated as new content (no rewrite)', env.db.sets('Mac', p) === sets0 && mac.store.getState().sync.status === 'ok', show({ sets: env.db.sets('Mac', p) - sets0 }))
  }

  // ───────── 雲端：切到背景時的快速路徑（不先讀、不確認、多份文件同時 set）─────────
  {
    const env = makeEnv()
    const mac = await cloudDevice(env, 'Mac')
    await type(env, mac, 'q1', 'text', 'q1 舊的')
    await env.clock.advance(100)
    const e1 = mac.editor('q1', 'text')
    const e2 = mac.editor('q2', 'text')
    mac.BT.lifecycle.onPageHide(() => e1.ed.save())
    mac.BT.lifecycle.onPageHide(() => e2.ed.save())
    e1.ed.input('q1 離開前的編輯')
    e2.ed.input('q2 離開前的編輯')
    mac.store.recordResult('c1', 'correct')
    let release!: () => void
    env.db.setGate = new Promise<void>((r) => (release = r))
    const mark = env.db.calls.length
    mac.hide()
    const during = env.db.calls.slice(mark)
    release()
    env.db.setGate = null
    await settle(8)
    const paths = during.filter((c) => c.op === 'set').map((c) => c.path).sort()
    check(
      'Cloud hide fast path: every changed doc is set at once (issued before any completes) with no read first',
      during.every((c) => c.op === 'set') && paths.length === 3 && paths.includes('data/users/u1/answers3/items/q1') && paths.includes('data/users/u1/answers3/items/q2') && paths.includes('data/users/u1/progress/sections/s1'),
      show(during),
    )
    check(
      'Cloud hide fast path: the docs contain the last edits right away',
      (env.db.doc('data/users/u1/answers3/items/q1') as unknown as AnswerEntry)?.text === 'q1 離開前的編輯' && (env.db.doc('data/users/u1/answers3/items/q2') as unknown as AnswerEntry)?.text === 'q2 離開前的編輯',
    )
    // 別台在這之前改了同一份文件的另一個欄位：快速路徑蓋掉了它，但它下次 pull 時發現雲端比較舊會再寫回來（欄位級合併）
    const phone = await cloudDevice(env, 'iPhone')
    await type(env, phone, 'q2', fT, '手機的事實')
    const e3 = mac.editor('q2', 'text')
    mac.BT.lifecycle.onPageHide(() => e3.ed.save())
    e3.ed.input('q2 再改一次（電腦沒讀到手機的事實）')
    mac.show()
    mac.hide()
    await settle(8)
    const overwritten = (env.db.doc('data/users/u1/answers3/items/q2') as unknown as AnswerEntry)?.facts[kT] === undefined
    await env.clock.advance(21_000) // 手機的定期 pull
    const d2 = env.db.doc('data/users/u1/answers3/items/q2') as unknown as AnswerEntry
    check('Cloud hide fast path: a race it causes (another device\'s field overwritten) is healed by the next pull (field-level merge)', overwritten && d2.facts[kT] === '手機的事實' && d2.text === 'q2 再改一次（電腦沒讀到手機的事實）', show(d2))
  }

  // ───────── storage：寫入失敗時讀到記憶體裡的新值；遷移成功才刪舊 key ─────────
  {
    const env = makeEnv()
    const st = new FakeStorage()
    const d = await device(env, { name: 'Mac', mode: 'server', content: C1, storage: st })
    const S = d.BT.storage
    S.setItem('k', 'old')
    st.failSet = (k) => k === 'k'
    const wrote = S.setItem('k', 'new')
    const afterFail = S.getItem('k')
    st.failSet = () => false
    S.setItem('k', 'newer')
    st.map.set('k', 'other-tab')
    check('Storage: after a failed write, getItem returns the newer in-memory value (not the stale localStorage one)', wrote === false && afterFail === 'new' && st.map.get('k') === 'other-tab' && S.getItem('k') === 'other-tab')
    const v1 = JSON.stringify({ version: 1, updatedAt: 9, answers: { q2: { text: 'v1 時代的口徑', facts: [], updatedAt: 9 } } })
    const ok = new FakeStorage()
    ok.map.set('bt.answers.v1', v1)
    const a = await device(env, { name: 'A', mode: 'server', content: C1, storage: ok })
    const full = new FakeStorage()
    full.map.set('bt.answers.v1', v1)
    full.failSet = (k) => k === 'bt.answers.v3'
    const b = await device(env, { name: 'B', mode: 'server', content: C1, storage: full })
    check('Storage: the v1 key is removed after the data is saved in the new format', a.entry('q2')?.text === 'v1 時代的口徑' && !ok.map.has('bt.answers.v1') && ok.map.has('bt.answers.v3'))
    check('Storage: if saving the new format fails, the v1 key is kept', b.entry('q2')?.text === 'v1 時代的口徑' && full.map.has('bt.answers.v1'))
  }

  // ───────── 426 之後：舊分頁存在舊 key 的編輯會被新版合併並送出 ─────────
  {
    const env = makeEnv()
    const mac = await serverDevice(env, 'Mac')
    await type(env, mac, 'q1', 'text', '新版分頁的內容')
    const oldAt = env.clock.t + 2_000
    // 還開著的舊版分頁：送出被 426 拒絕，內容存在 bt.answers.v2
    const oldPut = env.server.handle(mac, 'PUT', '/api/answers', { version: 2, answers: { q1: { text: '舊分頁的編輯' } } })
    mac.storage.map.set('bt.answers.v2', JSON.stringify({ version: 2, updatedAt: oldAt, answers: { q1: { text: '舊分頁的編輯', textAt: oldAt, facts: [], factsAt: [], gradeAt: 0, history: [], updatedAt: oldAt } } }))
    await env.clock.advance(3_000)
    mac.storageEvent('bt.answers.v2')
    await env.clock.advance(1500)
    const s = env.server.answers.answers.q1
    check('426: an old tab is rejected (426) instead of overwriting', oldPut.status === 426)
    check('426: its newer edit in the old localStorage key is merged by the new version and synced; the other version stays in history', s.text === '舊分頁的編輯' && hasHist(s, 'text', '新版分頁的內容') && !mac.storage.map.has('bt.answers.v2'), show(s))
  }

  await runServerTests(check)
}

/** 真正的 server middleware：426、舊資料檔遷移、跨行程檔案鎖 */
async function runServerTests(check: Check) {
  rmSync(TMP, { recursive: true, force: true })
  const dir = path.join(TMP, 'data')
  mkdirSync(dir, { recursive: true })
  // 舊格式資料檔（v2：事實以 index 對齊）；用真的內容裡有事實的題目
  const sd = JSON.parse(readFileSync(path.join(ROOT, 'src/generated/server-data.json'), 'utf8')) as { facts?: Record<string, Array<{ id: string; q: string }>> }
  // 內容裡可能沒有任何帶 factsToConfirm 的題目：那就只測備份，不測事實遷移
  const [exId, facts] = Object.entries(sd.facts ?? {})[0] ?? ['legacy-q', []]
  writeFileSync(path.join(dir, 'my-answers.json'), JSON.stringify({ version: 2, updatedAt: 5, answers: { [exId]: { text: 't', textAt: 5, facts: facts.length ? ['舊的事實答案'] : [], factsAt: facts.length ? [5] : [], gradeAt: 0, history: [], updatedAt: 5 } } }))
  process.env.PREP_DATA_DIR = dir
  const { apiMiddleware } = await import('../runner/server-plugin.ts')
  const progOld = await callApi(apiMiddleware, 'PUT', '/api/progress', { version: 2, units: {}, exercises: { q: { attempts: 6, correct: 4, last: 'correct', at: 1, box: 1, due: '2026-10-24' } }, plan: {}, mock: [] })
  const progNew = await callApi(apiMiddleware, 'PUT', '/api/progress', { version: 3, units: {}, exercises: {}, plan: {}, mock: [] })
  const ansOld = await callApi(apiMiddleware, 'PUT', '/api/answers', { version: 2, patches: {} })
  check('Server: PUT /api/progress from an old tab gets 426; the current format is accepted', progOld.status === 426 && progNew.status === 200, show([progOld, progNew]))
  check('Server: PUT /api/answers from an old tab gets 426', ansOld.status === 426)
  const got = (await callApi(apiMiddleware, 'GET', '/api/answers')) as { status: number; json: { answers: Record<string, AnswerEntry> } }
  const backups = readdirSync(dir).filter((f) => /^my-answers\.v2-\d+\.json$/.test(f))
  const migrated = facts.length ? got.json.answers[exId]?.facts[facts[0].id] === '舊的事實答案' : got.json.answers[exId]?.text === 't'
  check('Server: an old-format data file is backed up and its index-based facts migrate to fact ids', migrated && backups.length === 1, show({ exId, backups, e: got.json.answers[exId] }))
  // 殘留的鎖（持有者行程已不存在）會被清掉
  mkdirSync(path.join(dir, '.lock-answers'))
  writeFileSync(path.join(dir, '.lock-answers', 'owner'), JSON.stringify({ pid: 2 ** 22 + 12345, token: 'dead', at: Date.now() }))
  const t0 = Date.now()
  const afterStale = await callApi(apiMiddleware, 'PUT', '/api/answers', { version: 3, patches: { stale: { text: { v: 'ok', base: 0 } } } })
  check('Server lock: a stale lock left by a dead process is cleaned up', afterStale.status === 200 && Date.now() - t0 < 3000 && !existsSync(path.join(dir, '.lock-answers')))
  // 兩個 server 行程（dev 與 preview）同時寫同一個資料檔
  const N = 30
  const tsx = path.join(ROOT, 'node_modules/.bin/tsx')
  const child = (prefix: string) =>
    new Promise<number>((resolve) => {
      const c = spawn(tsx, [path.join(ROOT, 'scripts/regress/lock-child.ts'), dir, prefix, String(N)], { cwd: ROOT, stdio: ['ignore', 'ignore', 'inherit'] })
      c.on('exit', (code) => resolve(code ?? -1))
    })
  const codes = await Promise.all([child('dev'), child('preview')])
  const final = JSON.parse(readFileSync(path.join(dir, 'my-answers.json'), 'utf8')) as { answers: Record<string, AnswerEntry> }
  const ids = Object.keys(final.answers)
  const all = ['dev', 'preview'].every((p) => Array.from({ length: N }, (_, i) => i).every((i) => final.answers[`${p}-${i}`]?.text === `${p} 的第 ${i} 次編輯`))
  check('Server lock: two server processes writing the same data file concurrently lose no update', codes.every((c) => c === 0) && all && ids.length === 2 * N + 3 && !existsSync(path.join(dir, '.lock-answers')), show({ codes, n: ids.length }))
  rmSync(TMP, { recursive: true, force: true })
}
