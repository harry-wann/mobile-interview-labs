// 把 vite-plugin-singlefile 產生的 dist-static/index.html 後處理成 claude.ai Artifact 用的片段：
//   <title> → <meta claude:viewport-fit-sides> → Google Fonts <link> → <style> → #root → 內嵌 <script>
// 規則：沒有 <!doctype>/<html>/<head>/<body>、沒有 charset／viewport meta、沒有外部 script、title 在前 8KB。
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'dist-static/index.html')
const OUT = path.join(ROOT, 'artifact/mobile-prep.html')
const MAX_BYTES = 16 * 1024 * 1024
const TITLE = 'Mobile 面試複習站'

const fail = (msg) => {
  console.error(`✗ artifact：${msg}`)
  process.exit(1)
}

const html = readFileSync(SRC, 'utf8')

// 1. 內嵌的 JS（vite 已把 </script 跳脫成 <\/script，所以第一個 </script> 就是結尾）
const sOpen = html.search(/<script\b[^>]*>/)
if (sOpen < 0) fail('找不到內嵌的 <script>')
const sOpenEnd = html.indexOf('>', sOpen) + 1
const sClose = html.indexOf('</script>', sOpenEnd)
if (sClose < 0) fail('找不到 </script>')
let js = html.slice(sOpenEnd, sClose)
if (/<script\b[^>]*\ssrc=/i.test(html)) fail('出現外部 <script src>，所有 script 都必須內嵌')

// 2. 樣式
const styles = [...html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1])
if (styles.length === 0) fail('找不到內嵌的 <style>')
const css = styles.join('\n')

// 3. Google Fonts link（只允許這個外部樣式）
const links = [...html.matchAll(/<link\b[^>]*>/g)].map((m) => m[0])
const fontLinks = links.map((l) => l.replace(/\s+/g, ' ').replace(/\s*\/>$/, '>')).filter((l) => /href="https:\/\/fonts\.googleapis\.com\//.test(l) && /rel="stylesheet"/.test(l))
const badLinks = links.filter((l) => /rel="stylesheet"/.test(l) && !/href="https:\/\/fonts\.googleapis\.com\//.test(l))
if (badLinks.length) fail(`有不允許的外部樣式：${badLinks.join(' ')}`)

// 4. 內嵌 script 的安全處理：<!-- 與 </script 在 HTML 裡會出問題；在 JS 字串中以反斜線跳脫是等價的
js = js.replace(/<!--/g, '<\\!--').replace(/<\/script/gi, '<\\/script')
try {
  new vm.Script(js, { filename: 'artifact-inline.js' }) // classic script 語法檢查（不執行）
} catch (e) {
  fail(`內嵌 JS 不是合法的 classic script：${e.message}`)
}

const parts = [
  `<title>${TITLE}</title>`,
  `<meta name="claude:viewport-fit-sides" content="cover">`,
  ...fontLinks,
  `<style>${css}</style>`,
  `<div id="root"></div>`,
  `<script>${js}</script>`,
]
const out = parts.join('\n') + '\n'

// 5. 驗證輸出
const bytes = Buffer.byteLength(out)
const titleAt = Buffer.from(out).indexOf('<title>')
const problems = []
if (/<!doctype|<html|<head|<\/head|<body|<\/body|<\/html/i.test(out.replace(/<script>[\s\S]*<\/script>/, '').replace(/<style>[\s\S]*<\/style>/, ''))) problems.push('殘留 html/head/body 標籤')
if (/<meta[^>]+(charset|name="viewport")/i.test(out.slice(0, 2000))) problems.push('殘留 charset／viewport meta')
if (titleAt < 0 || titleAt > 8192) problems.push(`title 不在前 8KB（位置 ${titleAt}）`)
if (/<script\b[^>]*\ssrc=/i.test(out)) problems.push('有外部 script')
if (!/^<title>/.test(out)) problems.push('title 不是第一個標籤')
if (bytes >= MAX_BYTES) problems.push(`檔案過大：${bytes} bytes（上限 16MB）`)
if (/__mockClaude|mock-claude|__mockdb/.test(out)) problems.push('產物裡有測試用的 mock-claude 痕跡（scripts/mock-claude.js 只能在本機測試注入）')
if (problems.length) fail(problems.join('；'))

mkdirSync(path.dirname(OUT), { recursive: true })
writeFileSync(OUT, out)
console.log(`✓ ${path.relative(ROOT, OUT)}  ${(bytes / 1024 / 1024).toFixed(2)} MB（${bytes} bytes），title 位置 ${titleAt}，Google Fonts link ${fontLinks.length} 個`)
