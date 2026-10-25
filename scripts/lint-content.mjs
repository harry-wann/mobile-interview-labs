// 輕量內容檢查（引擎的 zod 版 content:check 完成前，內容作者先用這個自我檢查）
// 用法：node scripts/lint-content.mjs [section-dir ...]
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const CONTENT = path.join(ROOT, 'content')
const TYPES = ['single', 'multi', 'fill', 'predict', 'order', 'code', 'open', 'flash']
const NEEDS_EXPLAIN = ['single', 'multi', 'fill', 'predict', 'order']
const LANGS = ['swift', 'kotlin', 'objectivec', 'java', 'ts', 'json', 'text']

const sections = YAML.parse(readFileSync(path.join(CONTENT, 'sections.yaml'), 'utf8'))
const only = process.argv.slice(2)
const errors = []
const warn = []
const unitIds = new Set()
const exIds = new Set()
let unitCount = 0
let exCount = 0

const err = (file, msg) => errors.push(`${path.relative(ROOT, file)}: ${msg}`)

for (const sec of sections) {
  if (only.length && !only.includes(sec.dir) && !only.includes(sec.id)) continue
  const dir = path.join(CONTENT, sec.dir)
  if (!existsSync(dir)) continue
  const mds = readdirSync(dir).filter((f) => /^\d\d-[a-z0-9-]+\.md$/.test(f))
  for (const f of readdirSync(dir)) {
    if (f.endsWith('.md') && !/^\d\d-[a-z0-9-]+\.md$/.test(f)) err(path.join(dir, f), '檔名需為 NN-slug.md')
  }
  for (const md of mds) {
    const file = path.join(dir, md)
    const raw = readFileSync(file, 'utf8')
    const m = raw.match(/^---\n([\s\S]*?)\n---\n/)
    if (!m) {
      err(file, '缺少 frontmatter')
      continue
    }
    let fm
    try {
      fm = YAML.parse(m[1])
    } catch (e) {
      err(file, `frontmatter YAML 錯誤：${e.message}`)
      continue
    }
    for (const k of ['id', 'title', 'summary', 'priority']) if (!fm[k]) err(file, `frontmatter 缺 ${k}`)
    if (fm.id && !fm.id.startsWith(sec.id + '-')) err(file, `單元 id 必須以「${sec.id}-」開頭`)
    if (fm.id && !/^[a-z0-9-]+$/.test(fm.id)) err(file, '單元 id 只能用小寫英數與 -')
    if (unitIds.has(fm.id)) err(file, `單元 id 重複：${fm.id}`)
    unitIds.add(fm.id)
    if (fm.priority && !['P0', 'P1', 'P2'].includes(fm.priority)) err(file, 'priority 需為 P0/P1/P2')
    const body = raw.slice(m[0].length)
    for (const h of ['## 重點講解', '## 常見陷阱', '## 面試官想聽到的']) {
      if (!body.includes(h)) warn.push(`${path.relative(ROOT, file)}: 缺少段落「${h}」`)
    }
    unitCount++

    const exFile = file.replace(/\.md$/, '.ex.yaml')
    if (!existsSync(exFile)) {
      err(file, '缺少同名 .ex.yaml')
      continue
    }
    let items
    try {
      items = YAML.parse(readFileSync(exFile, 'utf8'))
    } catch (e) {
      err(exFile, `YAML 解析錯誤：${e.message}`)
      continue
    }
    if (!Array.isArray(items) || items.length === 0) {
      err(exFile, '內容必須是非空陣列')
      continue
    }
    for (const it of items) {
      const where = `${it.id ?? '(無 id)'}`
      const e = (msg) => err(exFile, `${where}：${msg}`)
      exCount++
      if (!it.id) e('缺 id')
      else {
        if (!it.id.startsWith(fm.id + '-')) e(`題目 id 必須以「${fm.id}-」開頭`)
        if (exIds.has(it.id)) e('題目 id 重複')
        exIds.add(it.id)
      }
      if (!TYPES.includes(it.type)) e(`type 不合法：${it.type}`)
      if (![1, 2, 3].includes(it.level)) e('level 需為 1/2/3')
      if (!['P0', 'P1', 'P2'].includes(it.priority)) e('priority 需為 P0/P1/P2')
      if (it.type !== 'flash' && !it.prompt) e('缺 prompt')
      if (NEEDS_EXPLAIN.includes(it.type) && !it.explain) e('缺 explain')
      if (it.code && !LANGS.includes(it.codeLang)) e(`有 code 時需 codeLang（${LANGS.join('/')}）`)
      switch (it.type) {
        case 'single':
          if (!Array.isArray(it.options) || it.options.length < 2) e('options 至少 2 個')
          else if (!Number.isInteger(it.answer) || it.answer < 0 || it.answer >= it.options.length) e('answer 索引超出範圍')
          break
        case 'multi':
          if (!Array.isArray(it.options) || it.options.length < 2) e('options 至少 2 個')
          else if (!Array.isArray(it.answer) || it.answer.length === 0 || it.answer.some((a) => !Number.isInteger(a) || a < 0 || a >= it.options.length))
            e('answer 需為索引陣列且在範圍內')
          break
        case 'fill': {
          const n = (it.code ?? '').split('____').length - 1
          if (n === 0) e('code 中沒有 ____ 空格')
          if (!Array.isArray(it.blanks) || it.blanks.length !== n) e(`____ 數量（${n}）與 blanks 數量（${it.blanks?.length ?? 0}）不符`)
          else if (it.blanks.some((b) => !Array.isArray(b) || b.length === 0)) e('blanks 每一項都要是非空陣列')
          break
        }
        case 'predict':
          if (!it.code) e('predict 需要 code')
          if (it.answer == null || String(it.answer).trim() === '') e('缺 answer')
          break
        case 'order':
          if (!Array.isArray(it.items) || it.items.length < 3) e('items 至少 3 個')
          break
        case 'code':
          if (!it.swift && !it.kotlin) e('code 題至少要有 swift 或 kotlin')
          for (const lang of ['swift', 'kotlin']) {
            if (!it[lang]) continue
            for (const k of ['starter', 'solution', 'tests']) if (!it[lang][k]) e(`${lang}.${k} 缺少`)
          }
          break
        case 'open':
          if (!Array.isArray(it.keyPoints) || it.keyPoints.length < 2) e('keyPoints 至少 2 點')
          if (!it.modelAnswer) e('缺 modelAnswer')
          break
        case 'flash':
          if (!it.front || !it.back) e('flash 需 front 與 back')
          break
      }
    }
  }
}

for (const w of warn) console.log(`⚠︎ ${w}`)
for (const x of errors) console.log(`✗ ${x}`)
console.log(`\n單元 ${unitCount}、題目 ${exCount}；錯誤 ${errors.length}、警告 ${warn.length}`)
process.exit(errors.length ? 1 : 0)
