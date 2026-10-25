// Shiki 語法高亮：建置時執行。
// 自訂一個「以 CSS 變數上色」的主題：token 只輸出 class="t1".."t8"，
// 實際顏色由 CSS 的 --syn-* 變數（light／dark 各一組）決定，所以會跟著網站主題切換。
import { bundledLanguages, createHighlighter, type HighlighterGeneric, type ThemeRegistration } from 'shiki'

const THEME_NAME = 'bt-vars'

// slot → 佔位色（只用來辨識，不會出現在輸出）
const SLOT_COLORS = [
  '#000000', // 0 預設（不輸出 class）
  '#000001', // 1 comment
  '#000002', // 2 keyword
  '#000003', // 3 string
  '#000004', // 4 number / constant
  '#000005', // 5 type
  '#000006', // 6 function
  '#000007', // 7 attribute / annotation
  '#000008', // 8 property key / tag
]
const COLOR_TO_SLOT = new Map(SLOT_COLORS.map((c, i) => [c, i]))

const rule = (slot: number, scope: string[]) => ({ scope, settings: { foreground: SLOT_COLORS[slot] } })

const theme: ThemeRegistration = {
  name: THEME_NAME,
  type: 'light',
  fg: SLOT_COLORS[0],
  bg: '#ffffff',
  tokenColors: [
    rule(0, ['keyword.operator', 'punctuation', 'meta.embedded', 'meta.brace', 'source.swift meta.embedded source.swift']),
    rule(1, ['comment', 'punctuation.definition.comment']),
    rule(2, [
      'keyword',
      'storage',
      'variable.language',
      'constant.language.boolean',
      'constant.language.null',
      'constant.language.nil',
    ]),
    rule(3, ['string', 'punctuation.definition.string', 'string.regexp']),
    rule(4, ['constant', 'support.constant', 'constant.numeric', 'constant.character.escape', 'keyword.other.unit']),
    rule(5, [
      'entity.name.type',
      'entity.name.class',
      'entity.name.struct',
      'entity.name.enum',
      'entity.name.namespace',
      'entity.other.inherited-class',
      'support.type',
      'support.class',
      'meta.type-name',
    ]),
    rule(6, ['entity.name.function', 'support.function', 'meta.function-call entity.name.function']),
    rule(7, [
      'storage.modifier.attribute',
      'punctuation.definition.attribute',
      'meta.attribute',
      'storage.type.annotation',
      'punctuation.definition.annotation',
      'meta.annotation',
      'entity.other.attribute-name',
    ]),
    rule(8, ['support.type.property-name', 'entity.name.tag', 'string.unquoted.plain.out.yaml entity.name.tag']),
  ],
}

const PRELOAD = ['swift', 'kotlin', 'java', 'objective-c', 'typescript', 'json', 'yaml', 'bash']

const ALIASES: Record<string, string> = {
  objectivec: 'objective-c',
  objc: 'objective-c',
  'obj-c': 'objective-c',
  ts: 'typescript',
  js: 'javascript',
  kt: 'kotlin',
  kts: 'kotlin',
  sh: 'bash',
  shell: 'bash',
  zsh: 'bash',
  yml: 'yaml',
  gradle: 'groovy',
  text: 'text',
  plain: 'text',
  plaintext: 'text',
  txt: 'text',
  '': 'text',
}

type Hl = HighlighterGeneric<string, string>
let hlPromise: Promise<Hl> | null = null
const getHl = () =>
  (hlPromise ??= createHighlighter({ themes: [theme], langs: PRELOAD }) as unknown as Promise<Hl>)

export const escapeHtml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** 解析語言名稱；回傳 shiki 語言 id（確保已載入），未知語言回 'text' */
export async function ensureLang(raw: string): Promise<{ lang: string; known: boolean }> {
  const key = raw.trim().toLowerCase()
  const lang = ALIASES[key] ?? key
  if (lang === 'text') return { lang: 'text', known: true }
  if (!(lang in bundledLanguages)) return { lang: 'text', known: false }
  const hl = await getHl()
  if (!hl.getLoadedLanguages().includes(lang)) await hl.loadLanguage(lang as never)
  return { lang, known: true }
}

export const BLANK_MARKER = '<i class="blank"></i>'

interface Piece {
  text: string
  slot: number
}

function piecesToHtml(pieces: Piece[]): string {
  let out = ''
  let curSlot = -1
  let buf = ''
  const flush = () => {
    if (!buf) return
    out += curSlot > 0 ? `<span class="t${curSlot}">${escapeHtml(buf)}</span>` : escapeHtml(buf)
    buf = ''
  }
  for (const p of pieces) {
    if (p.slot === -2) {
      flush()
      out += BLANK_MARKER
      curSlot = -1
      continue
    }
    if (p.slot !== curSlot) {
      flush()
      curSlot = p.slot
    }
    buf += p.text
  }
  flush()
  return out
}

/**
 * 同步高亮。lang 必須先經過 ensureLang。
 * blanks=true 時，把每個連續 4 個底線 `____` 換成 BLANK_MARKER（供 fill 題使用）。
 */
export function highlightSync(code: string, lang: string, opts: { blanks?: boolean } = {}): string {
  const body = code.replace(/\s+$/, '')
  const lines = body.split('\n')
  const hl = resolvedHl
  if (!hl || lang === 'text') {
    return lines.map((l) => renderPlainLine(l, opts.blanks)).join('\n')
  }
  const { tokens } = hl.codeToTokens(body, { lang, theme: THEME_NAME })
  return tokens
    .map((line) => {
      const pieces: Piece[] = line.map((t) => ({ text: t.content, slot: COLOR_TO_SLOT.get(normalizeColor(t.color)) ?? 0 }))
      return piecesToHtml(opts.blanks ? splitBlanks(pieces) : pieces)
    })
    .join('\n')
}

function renderPlainLine(line: string, blanks?: boolean): string {
  if (!blanks) return escapeHtml(line)
  return piecesToHtml(splitBlanks([{ text: line, slot: 0 }]))
}

function normalizeColor(c: string | undefined): string {
  return (c ?? '#000000').toLowerCase().slice(0, 7)
}

/** 把 pieces 中的 `____` 切出來，標成 slot -2（blank marker） */
function splitBlanks(pieces: Piece[]): Piece[] {
  const text = pieces.map((p) => p.text).join('')
  if (!text.includes('____')) return pieces
  const isBlank = new Array<boolean>(text.length).fill(false)
  const starts = new Set<number>()
  for (let i = 0; i <= text.length - 4; ) {
    if (text.startsWith('____', i)) {
      for (let k = 0; k < 4; k++) isBlank[i + k] = true
      starts.add(i)
      i += 4
    } else i++
  }
  const out: Piece[] = []
  let pos = 0
  for (const p of pieces) {
    for (const ch of p.text) {
      // for...of 以 code point 迭代；程式碼裡的 surrogate pair 長度為 2
      const len = ch.length
      if (isBlank[pos]) {
        if (starts.has(pos)) out.push({ text: '', slot: -2 })
      } else {
        out.push({ text: ch, slot: p.slot })
      }
      pos += len
    }
  }
  return out
}

let resolvedHl: Hl | null = null
/** 必須在呼叫 highlightSync 之前 await 一次 */
export async function initHighlighter(): Promise<void> {
  resolvedHl = await getHl()
}

/** 供 markdown fence 使用：<pre class="code" data-lang=...><code>…</code></pre> */
export function codeBlockHtml(code: string, lang: string, label: string): string {
  const inner = highlightSync(code, lang)
  const l = escapeHtml(label || '')
  return `<pre class="code"${l ? ` data-lang="${l}"` : ''}><code>${inner}</code></pre>\n`
}

/** 演練程式碼：上方顯示「檔名 · 區段」（不轉大寫） */
export function labCodeHtml(code: string, lang: string, label: string): string {
  const inner = highlightSync(code, lang)
  return `<pre class="code lab-code" data-file="${escapeHtml(label)}"><code>${inner}</code></pre>\n`
}
