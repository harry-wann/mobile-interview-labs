// 內容建置 CLI
//   npm run content:check   只驗證（嚴格，有錯誤 exit 1）
//   npm run content:build   驗證並輸出 src/generated/content.json、server-data.json
// 旗標：--lenient（有錯誤的單元跳過、不 exit 1）、--quiet（不印警告）
import { buildContent, formatIssues, GENERATED_DIR, writeOutputs } from './content/build.ts'

const args = new Set(process.argv.slice(2))
const checkOnly = args.has('--check')
const lenient = args.has('--lenient')
const quiet = args.has('--quiet')

const started = Date.now()
const result = await buildContent({ strict: !lenient })

if (!quiet && result.warnings.length) console.log(formatIssues(result.warnings) + '\n')
if (result.errors.length) console.log(formatIssues(result.errors) + '\n')

const { sections, units, exercises, skippedUnits } = result.stats
const summary = `section ${sections}、單元 ${units}、題目 ${exercises}${skippedUnits ? `（跳過 ${skippedUnits} 個有錯誤的單元）` : ''}；錯誤 ${result.errors.length}、警告 ${result.warnings.length}`

if (result.errors.length && !lenient) {
  console.log(`✗ ${summary}`)
  process.exit(1)
}
if (!checkOnly) {
  const { changed } = await writeOutputs(result)
  console.log(`✓ ${summary}；${changed ? `已輸出到 ${GENERATED_DIR}` : '輸出沒有變動'}（${Date.now() - started}ms）`)
} else {
  console.log(`✓ ${summary}（${Date.now() - started}ms）`)
}
