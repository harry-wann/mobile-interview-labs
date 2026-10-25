// 內容管線：讀 content/ → zod 驗證 → Markdown/程式碼轉 HTML → Content + ServerData
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import type {
  CodeLangSpec,
  Content,
  ContentIssue,
  Exercise,
  PlanDay,
  Priority,
  Section,
  ServerData,
  Unit,
} from '../../src/types.ts'
import { factIdsOf } from '../../src/lib/data.ts'
import { ensureLang, highlightSync, initHighlighter } from './highlight.ts'
import { labFileFingerprint, labSpecsIn } from './lab-code.ts'
import { renderBlock, renderInline } from './markdown.ts'
import {
  ExerciseSchemas,
  FrontmatterSchema,
  PlanSchema,
  RESERVED_ID_PREFIXES,
  RESERVED_ROUTES,
  SectionsSchema,
  TYPES,
  formatIssue,
  unknownKeys,
  type ExerciseSource,
} from './schema.ts'

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
/** 測試用：PREP_CONTENT_DIR 可指向另一個內容目錄 */
export const CONTENT_DIR = process.env.PREP_CONTENT_DIR ? path.resolve(process.env.PREP_CONTENT_DIR) : path.join(ROOT, 'content')
export const GENERATED_DIR = path.join(ROOT, 'src/generated')

const NEEDS_EXPLAIN = new Set(['single', 'multi', 'fill', 'predict', 'order'])
const rel = (p: string) => path.relative(ROOT, p)

export interface BuildResult {
  content: Content
  serverData: ServerData
  errors: ContentIssue[]
  warnings: ContentIssue[]
  stats: { sections: number; units: number; exercises: number; skippedUnits: number }
}

interface LoadedUnit {
  unit: Unit
  exercises: Exercise[]
  server: { code: ServerData['code']; open: ServerData['open']; facts: NonNullable<ServerData['facts']> }
}

interface UnitLoad {
  loaded: LoadedUnit | null
  issues: ContentIssue[]
}

/** 型別錯誤時，把有問題的值（YAML 解析成什麼）附在訊息後面，方便找位置 */
function showValue(raw: unknown, pathParts: ReadonlyArray<PropertyKey>): string {
  let v: unknown = raw
  for (const k of pathParts) {
    if (typeof v !== 'object' || v === null) return ''
    v = (v as Record<PropertyKey, unknown>)[k]
  }
  if (typeof v !== 'object' || v === null) return ''
  const t = JSON.stringify(v)
  return `  ← 解析結果：${t.length > 90 ? t.slice(0, 90) + '…' : t}`
}

const plainSearch = (s: string) =>
  s
    .replace(/[`*_#>\[\]()|~]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()

// 單檔結果快取：內容沒變就不重新轉 HTML（dev 時 watcher 重建很快）
const cache = new Map<string, { hash: string; result: UnitLoad }>()

async function loadUnit(sectionId: string, mdFile: string): Promise<UnitLoad> {
  const exFile = mdFile.replace(/\.md$/, '.ex.yaml')
  const mdRaw = readFileSync(mdFile, 'utf8')
  const exRaw = existsSync(exFile) ? readFileSync(exFile, 'utf8') : null
  // 「實際演練」引用的 labs/ 原始檔也算進去：程式碼改了，網站上的擷取段落要跟著更新
  const labFiles = labSpecsIn(mdRaw).map(labFileFingerprint).join('\0')
  const hash = createHash('sha1')
    .update(sectionId + '\0' + mdRaw + '\0' + (exRaw ?? '<none>') + '\0' + labFiles)
    .digest('hex')
  const hit = cache.get(mdFile)
  if (hit && hit.hash === hash) return hit.result
  const result = await loadUnitUncached(sectionId, mdFile, exFile, mdRaw, exRaw)
  cache.set(mdFile, { hash, result })
  return result
}

async function loadUnitUncached(
  sectionId: string,
  mdFile: string,
  exFile: string,
  mdRaw: string,
  exRaw: string | null,
): Promise<UnitLoad> {
  const issues: ContentIssue[] = []
  let unitId: string | undefined
  const add = (level: 'error' | 'warning', file: string, message: string, id?: string) =>
    issues.push({ level, file: rel(file), id: id ?? unitId, message })
  const fail = (): UnitLoad => ({ loaded: null, issues })

  // ── frontmatter ──
  const fm = mdRaw.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/)
  if (!fm) {
    add('error', mdFile, '缺少 frontmatter（檔案開頭需有 --- … ---）')
    return fail()
  }
  let fmRaw: unknown
  try {
    fmRaw = YAML.parse(fm[1])
  } catch (e) {
    add('error', mdFile, `frontmatter YAML 解析錯誤：${(e as Error).message.split('\n')[0]}`)
    return fail()
  }
  if (typeof fmRaw !== 'object' || fmRaw === null || Array.isArray(fmRaw)) {
    add('error', mdFile, 'frontmatter 必須是 key: value 物件')
    return fail()
  }
  unitId = typeof (fmRaw as { id?: unknown }).id === 'string' ? (fmRaw as { id: string }).id : undefined
  const fmParsed = FrontmatterSchema.safeParse(fmRaw)
  if (!fmParsed.success) {
    for (const i of fmParsed.error.issues) add('error', mdFile, `frontmatter ${formatIssue(i)}`)
    return fail()
  }
  const meta = fmParsed.data
  unitId = meta.id
  for (const k of unknownKeys(fmRaw as Record<string, unknown>, FrontmatterSchema)) {
    add('warning', mdFile, `frontmatter 有未知欄位「${k}」（會被忽略）`)
  }
  if (!meta.id.startsWith(sectionId + '-')) add('error', mdFile, `單元 id 必須以「${sectionId}-」開頭（目前：${meta.id}）`)
  if (RESERVED_ID_PREFIXES.some((p) => meta.id.startsWith(p)) || RESERVED_ROUTES.includes(meta.id)) {
    add('error', mdFile, `單元 id「${meta.id}」與網址保留字衝突`)
  }

  // ── 筆記本文 ──
  const body = mdRaw.slice(fm[0].length)
  for (const h of ['## 重點講解', '## 常見陷阱', '## 面試官想聽到的']) {
    if (!body.includes(h)) add('warning', mdFile, `缺少段落「${h}」`)
  }
  if (!body.trim()) add('error', mdFile, '筆記本文是空的')
  const rendered = await renderBlock(body)
  for (const w of rendered.warnings) add('warning', mdFile, w)

  // ── 練習題 ──
  if (exRaw === null) {
    add('error', mdFile, `缺少同名練習題檔 ${path.basename(exFile)}`)
    return fail()
  }
  let exParsed: unknown
  try {
    exParsed = YAML.parse(exRaw)
  } catch (e) {
    add('error', exFile, `YAML 解析錯誤：${(e as Error).message.split('\n')[0]}`)
    return fail()
  }
  if (!Array.isArray(exParsed) || exParsed.length === 0) {
    add('error', exFile, '內容必須是非空陣列（每題以 "- id: …" 開頭）')
    return fail()
  }

  const exercises: Exercise[] = []
  const server: LoadedUnit['server'] = { code: {}, open: {}, facts: {} }
  const seen = new Set<string>()
  for (let idx = 0; idx < exParsed.length; idx++) {
    const raw = exParsed[idx] as Record<string, unknown> | null
    const label = typeof raw?.id === 'string' ? raw.id : `第 ${idx + 1} 題`
    const err = (m: string) => add('error', exFile, m, label)
    const warn = (m: string) => add('warning', exFile, m, label)
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) {
      err('每題必須是物件')
      continue
    }
    const type = raw.type
    if (typeof type !== 'string' || !(TYPES as readonly string[]).includes(type)) {
      err(`type 不合法：${JSON.stringify(type)}（可用：${TYPES.join('、')}）`)
      continue
    }
    const schema = ExerciseSchemas[type as keyof typeof ExerciseSchemas]
    const parsed = schema.safeParse(raw)
    if (!parsed.success) {
      for (const i of parsed.error.issues) err(formatIssue(i) + showValue(raw, i.path))
      continue
    }
    for (const k of unknownKeys(raw, schema as never)) warn(`有未知欄位「${k}」（會被忽略）`)
    const ex = parsed.data as ExerciseSource
    const errCount = issues.filter((i) => i.level === 'error').length

    // id
    if (seen.has(ex.id)) err('題目 id 在同一檔案內重複')
    seen.add(ex.id)
    if (!ex.id.startsWith(meta.id + '-')) err(`題目 id 必須以「${meta.id}-」開頭`)
    else if (!new RegExp(`^${meta.id}-\\d{2,3}$`).test(ex.id)) warn(`題目 id 建議使用 ${meta.id}-NN 格式`)

    // 共同規則
    if (type !== 'flash' && !ex.prompt) err('缺 prompt')
    if (NEEDS_EXPLAIN.has(type) && !ex.explain) err('缺 explain')
    if (ex.code && !ex.codeLang) err('有 code 時需要 codeLang')
    if (!ex.code && ex.codeLang) warn('有 codeLang 但沒有 code')

    // 型別專屬規則
    switch (ex.type) {
      case 'single':
        if (ex.options.length < 2) err('options 至少 2 個')
        else if (ex.options.length < 3 || ex.options.length > 6) warn(`options 建議 3–6 個（目前 ${ex.options.length}）`)
        if (ex.answer < 0 || ex.answer >= ex.options.length) err(`answer ${ex.answer} 超出範圍（0–${ex.options.length - 1}）`)
        break
      case 'multi': {
        if (ex.options.length < 2) err('options 至少 2 個')
        else if (ex.options.length < 3 || ex.options.length > 6) warn(`options 建議 3–6 個（目前 ${ex.options.length}）`)
        if (ex.answer.length === 0) err('answer 不可為空陣列')
        if (new Set(ex.answer).size !== ex.answer.length) err('answer 有重複的索引')
        if (ex.answer.some((a) => a < 0 || a >= ex.options.length)) err(`answer 含超出範圍的索引（0–${ex.options.length - 1}）`)
        break
      }
      case 'fill': {
        if (!ex.code) {
          err('fill 需要 code')
          break
        }
        const n = ex.code.split('____').length - 1
        if (n === 0) err('code 中沒有 ____ 空格')
        if (ex.blanks.length !== n) err(`____ 數量（${n}）與 blanks 數量（${ex.blanks.length}）不符`)
        if (ex.blanks.some((b) => b.length === 0)) err('blanks 每一項都要是非空陣列')
        break
      }
      case 'predict':
        if (!ex.code) err('predict 需要 code')
        if (ex.answer.split(/[\s,]+/).filter(Boolean).length === 0) err('answer 沒有任何 token')
        break
      case 'order':
        if (ex.items.length < 3) err('items 至少 3 個')
        else if (ex.items.length > 7) warn(`items 建議 3–7 個（目前 ${ex.items.length}）`)
        {
          // 重複的項目會讓前端無法判斷順序（文字相同的兩項互換位置也算對）
          const norm = ex.items.map((t) => t.replace(/\s+/g, ' ').trim())
          const dups = norm.filter((t, i) => norm.indexOf(t) !== i)
          if (dups.length) err(`items 不得重複：${[...new Set(dups)].map((d) => `「${d.length > 30 ? d.slice(0, 30) + '…' : d}」`).join('、')}`)
        }
        break
      case 'code':
        if (!ex.swift && !ex.kotlin) err('code 題至少要有 swift 或 kotlin')
        break
      case 'open':
        if (ex.keyPoints.length < 2) err('keyPoints 至少 2 點')
        else if (ex.keyPoints.length < 4 || ex.keyPoints.length > 8) warn(`keyPoints 建議 4–8 點（目前 ${ex.keyPoints.length}）`)
        break
      default:
        break
    }
    if (issues.filter((i) => i.level === 'error').length > errCount) continue // 這題有錯，不用再轉換

    // ── 轉換 ──
    const common = {
      id: ex.id,
      unitId: meta.id,
      sectionId,
      level: ex.level as 1 | 2 | 3,
      priority: ex.priority as Priority,
      tags: ex.tags,
    }
    const promptBlock = ex.prompt ? await renderBlock(ex.prompt) : { html: '', warnings: [] as string[] }
    for (const w of promptBlock.warnings) warn(w)
    const explainBlock = ex.explain ? await renderBlock(ex.explain) : null
    for (const w of explainBlock?.warnings ?? []) warn(w)
    let codeHtml: string | undefined
    if (ex.code && ex.codeLang) {
      const { lang } = await ensureLang(ex.codeLang)
      codeHtml = highlightSync(ex.code, lang, { blanks: ex.type === 'fill' })
    }
    const searchBase = plainSearch(`${ex.prompt ?? ''} ${ex.tags.join(' ')}`)
    const shared = {
      ...common,
      search: ex.type === 'flash' ? plainSearch(`${ex.front} ${ex.back} ${ex.tags.join(' ')}`) : searchBase,
      promptHtml: promptBlock.html,
      ...(explainBlock ? { explainHtml: explainBlock.html } : {}),
      ...(ex.code ? { codeLang: ex.codeLang, code: ex.code.replace(/\s+$/, ''), codeHtml } : {}),
    }
    try {
      switch (ex.type) {
        case 'single':
          exercises.push({ ...shared, type: 'single', optionsHtml: ex.options.map(renderInline), answer: ex.answer })
          break
        case 'multi':
          exercises.push({ ...shared, type: 'multi', optionsHtml: ex.options.map(renderInline), answer: [...ex.answer].sort((a, b) => a - b) })
          break
        case 'fill':
          exercises.push({ ...shared, type: 'fill', blanks: ex.blanks, caseInsensitive: ex.caseInsensitive ?? false })
          break
        case 'predict':
          exercises.push({ ...shared, type: 'predict', answer: ex.answer, acceptAlso: ex.acceptAlso ?? [] })
          break
        case 'order':
          exercises.push({ ...shared, type: 'order', itemsHtml: ex.items.map(renderInline) })
          break
        case 'code': {
          const spec = async (lang: 'swift' | 'kotlin'): Promise<CodeLangSpec | undefined> => {
            const s = ex[lang]
            if (!s) return undefined
            const { lang: l } = await ensureLang(lang)
            return { starter: s.starter, solution: s.solution, solutionHtml: highlightSync(s.solution, l) }
          }
          exercises.push({
            ...shared,
            type: 'code',
            hintsHtml: (ex.hints ?? []).map(renderInline),
            swift: await spec('swift'),
            kotlin: await spec('kotlin'),
            ...(ex.timeLimitSec ? { timeLimitSec: ex.timeLimitSec } : {}),
          })
          server.code[ex.id] = {
            timeoutSec: ex.timeoutSec ?? 10,
            ...(ex.swift ? { swift: { tests: ex.swift.tests, swiftVersion: (ex.swift.swiftVersion ?? ex.swiftVersion ?? 5) as 5 | 6 } } : {}),
            ...(ex.kotlin ? { kotlin: { tests: ex.kotlin.tests } } : {}),
          }
          break
        }
        case 'open': {
          const model = await renderBlock(ex.modelAnswer)
          const modelJa = ex.modelAnswerJa ? await renderBlock(ex.modelAnswerJa) : null
          const promptJa = ex.promptJa ? await renderBlock(ex.promptJa) : null
          for (const w of [...model.warnings, ...(modelJa?.warnings ?? []), ...(promptJa?.warnings ?? [])]) warn(w)
          exercises.push({
            ...shared,
            type: 'open',
            ...(promptJa ? { promptJaHtml: promptJa.html } : {}),
            keyPointsHtml: ex.keyPoints.map(renderInline),
            modelAnswerHtml: model.html,
            ...(modelJa ? { modelAnswerJaHtml: modelJa.html } : {}),
            followUpsHtml: (ex.followUps ?? []).map(renderInline),
            factsHtml: (ex.factsToConfirm ?? []).map(renderInline),
            factIds: factIdsOf(ex.factsToConfirm ?? []),
            timeLimitSec: ex.timeLimitSec ?? 120,
          })
          if (ex.factsToConfirm?.length) {
            const ids = factIdsOf(ex.factsToConfirm)
            server.facts[ex.id] = ex.factsToConfirm.map((q, i) => ({ id: ids[i], q }))
          }
          server.open[ex.id] = {
            prompt: ex.prompt ?? '',
            ...(ex.promptJa ? { promptJa: ex.promptJa } : {}),
            keyPoints: ex.keyPoints,
            modelAnswer: ex.modelAnswer,
            followUps: ex.followUps ?? [],
          }
          break
        }
        case 'flash':
          exercises.push({ ...shared, type: 'flash', frontHtml: (await renderBlock(ex.front)).html, backHtml: (await renderBlock(ex.back)).html })
          break
      }
    } catch (e) {
      err(`轉換 HTML 失敗：${(e as Error).message}`)
    }
  }

  if (issues.some((i) => i.level === 'error')) return fail()
  const unit: Unit = {
    id: meta.id,
    sectionId,
    order: 0,
    title: meta.title,
    summary: meta.summary,
    priority: meta.priority,
    ...(meta.day ? { day: meta.day } : {}),
    tags: meta.tags,
    html: rendered.html,
    toc: rendered.toc,
    search: plainSearch(`${meta.title} ${meta.summary} ${meta.tags.join(' ')}`),
    exerciseIds: exercises.map((e) => e.id),
    file: rel(mdFile),
  }
  return { loaded: { unit, exercises, server }, issues }
}

export async function buildContent(opts: { strict: boolean }): Promise<BuildResult> {
  await initHighlighter()
  const all: ContentIssue[] = []
  const push = (level: 'error' | 'warning', file: string, message: string, id?: string) =>
    all.push({ level, file: rel(file), ...(id ? { id } : {}), message })

  // sections.yaml
  const sectionsFile = path.join(CONTENT_DIR, 'sections.yaml')
  const sectionsParsed = SectionsSchema.safeParse(YAML.parse(readFileSync(sectionsFile, 'utf8')))
  if (!sectionsParsed.success) {
    throw new Error(`content/sections.yaml 格式錯誤：\n${sectionsParsed.error.issues.map(formatIssue).join('\n')}`)
  }
  const sectionDefs = sectionsParsed.data
  const sectionIds = new Set<string>()
  for (const s of sectionDefs) {
    if (sectionIds.has(s.id)) throw new Error(`content/sections.yaml：section id 重複：${s.id}`)
    sectionIds.add(s.id)
  }

  const sections: Section[] = []
  const units: Unit[] = []
  const exercises: Exercise[] = []
  const serverData: ServerData = { version: 1, code: {}, open: {}, facts: {} }
  const unitIds = new Map<string, string>()
  const exIds = new Map<string, string>()
  let skipped = 0

  for (const def of sectionDefs) {
    const dir = path.join(CONTENT_DIR, def.dir)
    const section: Section = { id: def.id, title: def.title, short: def.short, description: def.description, unitIds: [] }
    sections.push(section)
    if (!existsSync(dir)) {
      push('warning', dir, `section「${def.id}」的目錄不存在`)
      continue
    }
    const entries = readdirSync(dir).sort()
    const mdNames = entries.filter((f) => f.endsWith('.md'))
    for (const f of mdNames) {
      if (!/^\d\d-[a-z0-9-]+\.md$/.test(f)) push('error', path.join(dir, f), '檔名需為 NN-slug.md（NN 兩位數字）')
    }
    for (const f of entries.filter((f) => f.endsWith('.ex.yaml'))) {
      if (!existsSync(path.join(dir, f.replace(/\.ex\.yaml$/, '.md')))) push('error', path.join(dir, f), '找不到同名的 .md 單元檔')
    }
    let order = 0
    for (const name of mdNames.filter((f) => /^\d\d-[a-z0-9-]+\.md$/.test(f))) {
      const file = path.join(dir, name)
      const { loaded, issues } = await loadUnit(def.id, file)
      all.push(...issues)
      if (!loaded) {
        skipped++
        continue
      }
      const { unit, exercises: exs, server } = loaded
      // 全站唯一性
      const dupUnit = unitIds.get(unit.id)
      if (dupUnit) {
        push('error', file, `單元 id「${unit.id}」與 ${dupUnit} 重複`, unit.id)
        skipped++
        continue
      }
      const dupEx = exs.find((e) => exIds.has(e.id))
      if (dupEx) {
        push('error', file, `題目 id「${dupEx.id}」與 ${exIds.get(dupEx.id)} 重複`, dupEx.id)
        skipped++
        continue
      }
      unitIds.set(unit.id, rel(file))
      for (const e of exs) exIds.set(e.id, rel(file))
      units.push({ ...unit, order: order++ })
      section.unitIds.push(unit.id)
      exercises.push(...exs)
      Object.assign(serverData.code, server.code)
      Object.assign(serverData.open, server.open)
      Object.assign(serverData.facts!, server.facts)
    }
    if (section.unitIds.length === 0) push('warning', dir, `section「${def.id}」目前沒有可用的單元`)
  }

  // 7 日計畫
  let plan: PlanDay[] = []
  const planFile = path.join(CONTENT_DIR, 'plan/days.yaml')
  if (existsSync(planFile)) {
    try {
      const parsed = PlanSchema.safeParse(YAML.parse(readFileSync(planFile, 'utf8')))
      if (!parsed.success) {
        for (const i of parsed.error.issues) push('error', planFile, formatIssue(i))
      } else {
        const taskIds = new Set<string>()
        for (const d of parsed.data) {
          for (const t of d.tasks) {
            if (taskIds.has(t.id)) push('error', planFile, `任務 id 重複：${t.id}`)
            taskIds.add(t.id)
            for (const l of t.links ?? []) {
              if (!sectionIds.has(l) && !unitIds.has(l)) push('warning', planFile, `任務 ${t.id} 的連結「${l}」找不到對應的 section 或單元`)
            }
          }
        }
        plan = parsed.data.map((d) => ({
          day: d.day,
          date: d.date,
          title: d.title,
          goal: d.goal,
          tasks: d.tasks.map((t) => ({ id: t.id, label: t.label, links: t.links ?? [] })),
        }))
      }
    } catch (e) {
      push('error', planFile, `YAML 解析錯誤：${(e as Error).message.split('\n')[0]}`)
    }
  } else {
    // 計畫是選填：沒有 days.yaml 就沒有 7 日計畫頁
  }

  const errors = all.filter((i) => i.level === 'error')
  const warnings = all.filter((i) => i.level === 'warning')
  const content: Content = {
    version: 1,
    builtAt: new Date().toISOString(),
    strict: opts.strict,
    sections,
    units,
    exercises,
    plan,
    issues: all,
  }
  return {
    content,
    serverData,
    errors,
    warnings,
    stats: { sections: sections.length, units: units.length, exercises: exercises.length, skippedUnits: skipped },
  }
}

async function writeIfChanged(file: string, text: string, volatile?: RegExp): Promise<boolean> {
  let prev: string | null = null
  try {
    prev = await readFile(file, 'utf8')
  } catch {
    /* 不存在 */
  }
  const norm = (s: string) => (volatile ? s.replace(volatile, '') : s)
  if (prev !== null && norm(prev) === norm(text)) return false
  await mkdir(path.dirname(file), { recursive: true })
  const tmp = `${file}.tmp-${process.pid}`
  await writeFile(tmp, text)
  await rename(tmp, file)
  return true
}

/** 寫出 src/generated/content.json 與 server-data.json；內容沒變就不寫（避免觸發不必要的重載） */
export async function writeOutputs(result: BuildResult): Promise<{ changed: boolean }> {
  const a = await writeIfChanged(
    path.join(GENERATED_DIR, 'content.json'),
    JSON.stringify(result.content),
    /"builtAt":"[^"]*"/,
  )
  const b = await writeIfChanged(path.join(GENERATED_DIR, 'server-data.json'), JSON.stringify(result.serverData))
  return { changed: a || b }
}

export function formatIssues(issues: ContentIssue[]): string {
  return issues
    .map((i) => `${i.level === 'error' ? '✗' : '⚠︎'} ${i.file}${i.id ? ` [${i.id}]` : ''}：${i.message}`)
    .join('\n')
}
