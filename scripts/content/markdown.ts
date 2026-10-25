// Markdown → HTML（建置時）。GFM 表格、shiki 程式碼、GitHub alert、標題 id。
import MarkdownIt from 'markdown-it'
import type { Token } from 'markdown-it'
import type { TocItem } from '../../src/types.ts'
import { codeBlockHtml, ensureLang, escapeHtml, labCodeHtml } from './highlight.ts'
import { loadLabSnippet, type LabSnippet } from './lab-code.ts'

type LabToken = Token & { _lab?: LabSnippet }

const ALERTS: Record<string, { cls: string; title: string }> = {
  TIP: { cls: 'tip', title: '面試加分說法' },
  WARNING: { cls: 'warning', title: '注意：常見誤解／會被追問' },
  NOTE: { cls: 'note', title: '補充' },
  FIELD: { cls: 'field', title: '實務上' },
  LAB: { cls: 'lab', title: '怎麼執行這些演練' },
  IMPORTANT: { cls: 'warning', title: '重點' },
  CAUTION: { cls: 'warning', title: '小心' },
}

interface Env {
  [key: string]: unknown
  toc: TocItem[]
  slugs: Map<string, number>
  warnings: string[]
}

function slugify(text: string): string {
  const s = text
    .toLowerCase()
    .replace(/[`*_~]/g, '')
    .replace(/[^\p{L}\p{N}\s-]+/gu, ' ')
    .trim()
    .replace(/\s+/g, '-')
  return s || 'section'
}

function createMd() {
  const md = new MarkdownIt({ html: false, linkify: false, typographer: false, breaks: true })

  // 文字：允許 <br> 當換行（表格內換行常用）
  const defaultText = md.renderer.rules.text
  md.renderer.rules.text = (tokens, idx, options, env, self) => {
    const raw = defaultText ? defaultText(tokens, idx, options, env, self) : escapeHtml(tokens[idx].content)
    return raw.replace(/&lt;br\s*\/?&gt;/gi, '<br>')
  }

  // 表格：外包一層可水平捲動的容器
  md.renderer.rules.table_open = () => '<div class="table-wrap"><table>\n'
  md.renderer.rules.table_close = () => '</table></div>\n'

  // 外部連結另開分頁
  const defaultLinkOpen =
    md.renderer.rules.link_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const href = String(tokens[idx].attrGet('href') ?? '')
    if (/^https?:\/\//i.test(href)) {
      tokens[idx].attrSet('target', '_blank')
      tokens[idx].attrSet('rel', 'noopener noreferrer')
    }
    return defaultLinkOpen(tokens, idx, options, env, self)
  }

  // 標題加 id，收集目錄
  md.renderer.rules.heading_open = (tokens, idx, options, rawEnv, self) => {
    const env = rawEnv as unknown as Env
    const level = Number(tokens[idx].tag.slice(1))
    const inline = tokens[idx + 1]
    const text = (inline.children ?? [])
      .filter((t) => t.type === 'text' || t.type === 'code_inline')
      .map((t) => t.content)
      .join('')
    const base = `h-${slugify(text)}`
    const n = env.slugs.get(base) ?? 0
    env.slugs.set(base, n + 1)
    const id = n === 0 ? base : `${base}-${n + 1}`
    tokens[idx].attrSet('id', id)
    if (level === 2 || level === 3) env.toc.push({ id, text, level })
    return self.renderToken(tokens, idx, options)
  }

  // 程式碼區塊
  md.renderer.rules.fence = (tokens, idx) => {
    const t = tokens[idx]
    const label = t.info.trim().split(/\s+/)[0] ?? ''
    const lab = (t as LabToken)._lab
    if (lab) {
      const html = labCodeHtml(lab.code, lab.lang, lab.label)
      const what = lab.region ? `看解答：${lab.region}` : '看解答'
      return label === 'lab-solution'
        ? `<details class="lab-solution"><summary>${escapeHtml(what)}（先自己寫完再打開）</summary>\n${html}</details>\n`
        : html
    }
    const resolved = (t as Token & { _lang?: string })._lang ?? 'text'
    return codeBlockHtml(t.content, resolved, label)
  }
  md.renderer.rules.code_block = (tokens, idx) => codeBlockHtml(tokens[idx].content, 'text', '')

  // GitHub alert：> [!TIP] ...
  md.core.ruler.push('github_alerts', (state) => {
    const tokens = state.tokens
    for (let i = 0; i < tokens.length; i++) {
      const open = tokens[i]
      if (open.type !== 'blockquote_open') continue
      const p = tokens[i + 1]
      const inline = tokens[i + 2]
      if (p?.type !== 'paragraph_open' || inline?.type !== 'inline') continue
      const m = inline.content.match(/^\[!(\w+)\][ \t]*(?:\n|$)/)
      const info = m ? ALERTS[m[1].toUpperCase()] : undefined
      if (!m || !info) continue
      // 移除標記
      inline.content = inline.content.slice(m[0].length)
      const kids = inline.children ?? []
      if (kids[0]?.type === 'text') {
        kids[0].content = kids[0].content.replace(/^\[!\w+\][ \t]*/, '')
        if (kids[0].content === '') kids.shift()
      }
      if (kids[0]?.type === 'softbreak') kids.shift()
      if (inline.content.trim() === '' && kids.length === 0) {
        // 第一段只有標記：把空段落移除
        tokens.splice(i + 1, 3)
      }
      open.tag = 'div'
      open.attrSet('class', `alert alert-${info.cls}`)
      open.attrSet('role', 'note')
      open.meta = { alertTitle: info.title }
      // 對應的 blockquote_close
      let depth = 0
      for (let j = i; j < tokens.length; j++) {
        if (tokens[j].type === 'blockquote_open') depth++
        else if (tokens[j].type === 'blockquote_close') {
          depth--
          if (depth === 0) {
            tokens[j].tag = 'div'
            break
          }
        }
      }
    }
  })
  const defaultBqOpen =
    md.renderer.rules.blockquote_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.blockquote_open = (tokens, idx, options, env, self) => {
    const base = defaultBqOpen(tokens, idx, options, env, self)
    const title = tokens[idx].meta?.alertTitle as string | undefined
    return title ? `${base}<div class="alert-title">${escapeHtml(title)}</div>\n` : base
  }

  return md
}

const md = createMd()

/** 先解析 fence 的語言（shiki 需要非同步載入），結果掛在 token 上 */
async function resolveFenceLangs(tokens: Token[], warnings: string[]) {
  for (const t of tokens) {
    if (t.type !== 'fence') continue
    const label = t.info.trim().split(/\s+/)[0] ?? ''
    if (label === 'lab' || label === 'lab-solution') {
      const snippet = loadLabSnippet(t.content.trim())
      if ('error' in snippet) {
        warnings.push(snippet.error)
        ;(t as LabToken)._lab = { code: snippet.error, lang: 'text', label: '演練程式碼', region: '' }
      } else {
        const { lang } = await ensureLang(snippet.lang)
        ;(t as LabToken)._lab = { ...snippet, lang }
      }
      continue
    }
    const { lang, known } = await ensureLang(label)
    if (!known) warnings.push(`未知的程式碼語言「${label}」，以純文字顯示`)
    ;(t as Token & { _lang?: string })._lang = lang
  }
}

export interface RenderedBlock {
  html: string
  toc: TocItem[]
  warnings: string[]
}

/** 區塊 Markdown（筆記全文、prompt、explain …） */
export async function renderBlock(src: string): Promise<RenderedBlock> {
  const env: Env = { toc: [], slugs: new Map(), warnings: [] }
  const tokens = md.parse(src, env as never)
  await resolveFenceLangs(tokens, env.warnings)
  const html = md.renderer.render(tokens, md.options, env as never)
  warnLeftoverEmphasis(html, env.warnings)
  return { html, toc: env.toc, warnings: env.warnings }
}

/** 單行 Markdown（options、items、keyPoints …），不包 <p> */
export function renderInline(src: string): string {
  const env: Env = { toc: [], slugs: new Map(), warnings: [] }
  return md.renderInline(src.trim(), env as never)
}

/** 粗體符號沒被解析（CJK 標點旁邊常見）就提醒作者 */
function warnLeftoverEmphasis(html: string, warnings: string[]) {
  const stripped = html.replace(/<pre[\s\S]*?<\/pre>/g, '').replace(/<code>[\s\S]*?<\/code>/g, '')
  const m = stripped.match(/[^<>]{0,12}\*\*[^<>]{0,12}/)
  if (m) warnings.push(`有未解析的粗體符號 ** （通常是 ** 緊鄰中文標點）：「${m[0].trim()}」`)
}
