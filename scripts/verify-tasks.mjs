// 驗證所有 code 題：solution 必須全部通過，starter 必須能編譯且至少一個測試失敗
// 用法：node scripts/verify-tasks.mjs [題目或單元 id 前綴 ...]
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { runCode } from '../runner/run-code.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const CONTENT = path.join(ROOT, 'content')
const prefixes = process.argv.slice(2)
const CONCURRENCY = 2

const sections = YAML.parse(readFileSync(path.join(CONTENT, 'sections.yaml'), 'utf8'))
const jobs = []
for (const sec of sections) {
  const dir = path.join(CONTENT, sec.dir)
  if (!existsSync(dir)) continue
  for (const f of readdirSync(dir).filter((f) => f.endsWith('.ex.yaml'))) {
    let items
    try {
      items = YAML.parse(readFileSync(path.join(dir, f), 'utf8'))
    } catch {
      continue
    }
    for (const it of items ?? []) {
      if (it?.type !== 'code') continue
      if (prefixes.length && !prefixes.some((p) => it.id.startsWith(p))) continue
      for (const lang of ['swift', 'kotlin']) {
        if (it[lang]) jobs.push({ id: it.id, lang, spec: it[lang], timeoutSec: it.timeoutSec })
      }
    }
  }
}

const failures = []
async function verify(job) {
  const base = { lang: job.lang, tests: job.spec.tests, swiftVersion: job.spec.swiftVersion ?? 5, timeoutSec: job.timeoutSec ?? 10 }
  const sol = await runCode({ ...base, code: job.spec.solution })
  const st = await runCode({ ...base, code: job.spec.starter })
  const problems = []
  if (!sol.ok) {
    const failed = sol.tests.filter((t) => !t.pass).map((t) => `${t.name}（${t.detail}）`)
    problems.push(`solution 未全過 ${sol.passed}/${sol.total} ${sol.error ? '\n      ' + sol.error.split('\n').slice(0, 8).join('\n      ') : ''}${failed.length ? '\n      失敗：' + failed.join('；') : ''}`)
  }
  if (st.phase === 'compile') problems.push(`starter 無法編譯：\n      ${st.error.split('\n').slice(0, 6).join('\n      ')}`)
  else if (st.ok) problems.push('starter 全部通過（至少要有一個測試失敗）')
  const mark = problems.length ? '✗' : '✓'
  console.log(`${mark} ${job.id} [${job.lang}] solution ${sol.passed}/${sol.total}, starter ${st.passed}/${st.total}${st.phase === 'compile' ? '（編譯失敗）' : ''}  ${sol.durationMs}ms`)
  for (const p of problems) console.log(`    ${p}`)
  if (problems.length) failures.push(`${job.id} [${job.lang}]`)
}

const queue = [...jobs]
await Promise.all(
  Array.from({ length: CONCURRENCY }, async () => {
    while (queue.length) await verify(queue.shift())
  }),
)
console.log(`\n共 ${jobs.length} 個（題目×語言），失敗 ${failures.length}`)
if (failures.length) console.log('失敗清單：' + failures.join('、'))
process.exit(failures.length ? 1 : 0)
