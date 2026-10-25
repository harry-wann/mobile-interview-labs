// 「實際演練」的程式碼：從 labs/ 的原始檔擷取一段，讓網站上顯示的和 Xcode／Android Studio 裡跑的一模一樣。
//
// Markdown 寫法：
//   ```lab-solution           ← 預設摺疊（「看解答：Bug 版」），寫完練習再打開
//   ios/Solution/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift#bug
//   ```
//   ```lab                    ← 直接顯示（例如練習的骨架）
//   android/practice/kotlin/kotlin03-coroutines/L01_ScopeLeak.kt#bug
//   ```
//
// 區段以 `// MARK: - <名稱>` 開頭，到下一個 `// MARK: -` 或檔尾為止。
// #bug、#fixed、#setup 分別對應「Bug 版」「修正版」「演練設定」；也可以直接寫 MARK 的名稱。
// 不寫 # 就是整個檔案。
import { readFileSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const LABS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../labs')

const REGION_ALIASES: Record<string, string> = {
  bug: 'Bug 版',
  fixed: '修正版',
  setup: '演練設定',
}

const LANG_BY_EXT: Record<string, string> = {
  '.swift': 'swift',
  '.kt': 'kotlin',
  '.kts': 'kotlin',
  '.java': 'java',
  '.m': 'objective-c',
  '.h': 'objective-c',
  '.xml': 'xml',
  '.yml': 'yaml',
  '.yaml': 'yaml',
  '.sh': 'bash',
}

export interface LabSnippet {
  code: string
  lang: string
  /** 顯示在程式碼上方：檔名 · 區段 */
  label: string
  /** 區段名稱（整個檔案時為空字串） */
  region: string
}

/** 從 Markdown 原文找出所有 lab fence 的規格（給快取計算 hash 用） */
export function labSpecsIn(markdown: string): string[] {
  const out: string[] = []
  const re = /^```lab(?:-solution)?[ \t]*\r?\n([^\r\n]+)\r?\n```/gm
  for (let m = re.exec(markdown); m; m = re.exec(markdown)) out.push(m[1].trim())
  return out
}

/** 規格對應的檔案內容（不存在回傳空字串），給快取計算 hash 用 */
export function labFileFingerprint(spec: string): string {
  const file = path.join(LABS_DIR, spec.split('#')[0])
  return existsSync(file) ? readFileSync(file, 'utf8') : ''
}

export function loadLabSnippet(spec: string): LabSnippet | { error: string } {
  const [relPath, regionRaw] = spec.split('#')
  const rel = relPath.trim()
  if (!rel || rel.includes('..')) return { error: `演練程式碼路徑不合法：「${spec}」` }
  const file = path.join(LABS_DIR, rel)
  if (!existsSync(file)) return { error: `找不到演練程式碼：labs/${rel}` }

  const lang = LANG_BY_EXT[path.extname(file)] ?? 'text'
  const name = path.basename(file)
  const lines = readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n')

  if (!regionRaw) {
    return { code: trimBlank(lines).join('\n'), lang, label: name, region: '' }
  }

  const region = REGION_ALIASES[regionRaw.trim()] ?? regionRaw.trim()
  const isMark = (l: string) => /^\s*\/\/ MARK: - /.test(l)
  const start = lines.findIndex((l) => isMark(l) && l.replace(/^\s*\/\/ MARK: - /, '').trim() === region)
  if (start < 0) return { error: `labs/${rel} 裡找不到區段「// MARK: - ${region}」` }
  let end = lines.findIndex((l, i) => i > start && isMark(l))
  if (end < 0) end = lines.length
  const body = trimBlank(lines.slice(start + 1, end))
  if (body.length === 0) return { error: `labs/${rel} 的區段「${region}」是空的` }
  return { code: dedent(body).join('\n'), lang, label: `${name} · ${region}`, region }
}

function trimBlank(lines: string[]): string[] {
  let a = 0
  let b = lines.length
  while (a < b && lines[a].trim() === '') a++
  while (b > a && lines[b - 1].trim() === '') b--
  return lines.slice(a, b)
}

function dedent(lines: string[]): string[] {
  const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)![0].length)
  const n = indents.length ? Math.min(...indents) : 0
  return n ? lines.map((l) => l.slice(n)) : lines
}
