// 從 labs/topics.yaml 產生：
//   labs/ios/LabKit/Sources/LabKit/JdFocus.swift            iOS App 首頁的「四大主題」排序
//   labs/android/labkit/src/main/java/tw/harry/labs/kit/JdFocus.kt   Android 同上
// 演練的標題、平台、單元 id 直接從 labs 的 Solution 原始碼讀，yaml 只寫「代號 L編號」。
// 用法：npm run topics:gen
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import YAML from 'yaml'

const ROOT = path.resolve(import.meta.dirname, '..')
const YAML_FILE = path.join(ROOT, 'labs/topics.yaml')

interface Group { title: string; note?: string; labs: string[] }
interface Topic { id: string; title: string; short: string; why: string; units: string[]; groups: Group[]; gaps: string[] }
interface Spec { topics: Topic[] }

interface LabInfo { code: string; id: string; title: string; question: string; platforms: Set<'iOS' | 'Android'> }
interface UnitInfo { code: string; unitId: string; title: string }

function walk(dir: string, ext: string): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    if (name === 'build' || name === '.build' || name === '.gradle' || name === '.kotlin') continue
    const p = path.join(dir, name)
    if (statSync(p).isDirectory()) out.push(...walk(p, ext))
    else if (p.endsWith(ext)) out.push(p)
  }
  return out
}

/** 讀 Solution 原始碼：單元代號 → 單元 id／標題，以及每個演練的標題與平台 */
function scanLabs() {
  const labs = new Map<string, LabInfo>()
  const units = new Map<string, UnitInfo>()
  const scan = (files: string[], unitRe: RegExp, labRe: RegExp, platform: 'iOS' | 'Android') => {
    for (const f of files) {
      const s = readFileSync(f, 'utf8')
      const u = s.match(unitRe)
      if (u) {
        units.set(u[2], { unitId: u[1], code: u[2], title: u[3] })
        continue
      }
      // 演練檔：代號從單元資料夾名稱推（Swift02-ARC → Swift02ARC；kotlin03-coroutines → 由單元清單檔補）
      for (const m of s.matchAll(labRe)) {
        const dir = path.basename(path.dirname(f))
        const key = `${dir.toLowerCase().replace(/-/g, '')}|${m[1]}`
        const e = labs.get(key) ?? { code: '', id: m[1], title: m[2], question: m[3], platforms: new Set() }
        e.platforms.add(platform)
        labs.set(key, e)
      }
    }
  }
  scan(
    walk(path.join(ROOT, 'labs/ios/Solution'), '.swift'),
    /LabUnit\(\s*id:\s*"([^"]+)",\s*code:\s*"([^"]+)",\s*title:\s*"([^"]+)"/,
    /id:\s*"(L\d\d)",\s*title:\s*"([^"]*)",\s*question:\s*"([^"]*)"/g,
    'iOS',
  )
  scan(
    walk(path.join(ROOT, 'labs/android/solution'), '.kt'),
    /LabUnit\(\s*id\s*=\s*"([^"]+)",\s*code\s*=\s*"([^"]+)",\s*title\s*=\s*"([^"]+)"/,
    /id\s*=\s*"(L\d\d)",\s*title\s*=\s*"([^"]*)",\s*question\s*=\s*"([^"]*)"/g,
    'Android',
  )
  // 把資料夾名稱對回代號：代號小寫去掉連字號就是資料夾名稱（Swift02-ARC、swift02-arc 都變 swift02arc）
  const byFolder = new Map<string, LabInfo>()
  for (const [key, e] of labs) {
    const folder = key.split('|')[0]
    const unit = [...units.values()].find((u) => u.code.toLowerCase() === folder)
    if (!unit) throw new Error(`找不到資料夾 ${folder} 對應的單元代號`)
    e.code = unit.code
    byFolder.set(`${unit.code} ${e.id}`, e)
  }
  return { labs: byFolder, units }
}

function parseSpec(): Spec {
  const spec = YAML.parse(readFileSync(YAML_FILE, 'utf8')) as Spec
  if (!spec?.topics?.length) throw new Error('topics.yaml 沒有 topics')
  return spec
}

const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, ' ')

function genSwift(spec: Spec, labs: Map<string, LabInfo>) {
  const L: string[] = []
  L.push('// 由 labs/topics.yaml 產生（npm run topics:gen），不要手改。')
  L.push('// App 首頁的「四大主題」：記憶體 → 效能 → 架構 → UI/UX，每一項列出該寫的演練。')
  L.push('')
  L.push('public struct JdFocusLab: Hashable {')
  L.push('    public let code: String')
  L.push('    public let labID: String')
  L.push('}')
  L.push('')
  L.push('public struct JdFocusGroup {')
  L.push('    public let title: String')
  L.push('    public let note: String?')
  L.push('    public let labs: [JdFocusLab]')
  L.push('}')
  L.push('')
  L.push('public struct JdFocusTopic: Identifiable {')
  L.push('    public let id: String')
  L.push('    public let title: String')
  L.push('    public let why: String')
  L.push('    public let groups: [JdFocusGroup]')
  L.push('}')
  L.push('')
  L.push('public enum JdFocus {')
  L.push('    public static let topics: [JdFocusTopic] = [')
  for (const t of spec.topics) {
    L.push(`        JdFocusTopic(id: "${t.id}", title: "${esc(t.title)}", why: "${esc(t.why.trim())}", groups: [`)
    for (const g of t.groups) {
      const refs = g.labs.filter((r) => labs.get(r)!.platforms.has('iOS'))
      if (refs.length === 0) continue
      L.push(`            JdFocusGroup(title: "${esc(g.title)}", note: ${g.note ? `"${esc(g.note.trim())}"` : 'nil'}, labs: [`)
      for (const r of refs) {
        const [code, id] = r.split(' ')
        L.push(`                JdFocusLab(code: "${code}", labID: "${id}"),`)
      }
      L.push('            ]),')
    }
    L.push('        ]),')
  }
  L.push('    ]')
  L.push('}')
  L.push('')
  return L.join('\n')
}

function genKotlin(spec: Spec, labs: Map<string, LabInfo>) {
  const L: string[] = []
  L.push('// 由 labs/topics.yaml 產生（npm run topics:gen），不要手改。')
  L.push('// App 首頁的「四大主題」：記憶體 → 效能 → 架構 → UI/UX，每一項列出該寫的演練。')
  L.push('package tw.harry.labs.kit')
  L.push('')
  L.push('class JdFocusLab(val code: String, val labId: String)')
  L.push('class JdFocusGroup(val title: String, val note: String?, val labs: List<JdFocusLab>)')
  L.push('class JdFocusTopic(val id: String, val title: String, val why: String, val groups: List<JdFocusGroup>)')
  L.push('')
  L.push('object JdFocus {')
  L.push('    val topics: List<JdFocusTopic> = listOf(')
  for (const t of spec.topics) {
    L.push(`        JdFocusTopic("${t.id}", "${esc(t.title)}", "${esc(t.why.trim())}", listOf(`)
    for (const g of t.groups) {
      const refs = g.labs.filter((r) => labs.get(r)!.platforms.has('Android'))
      if (refs.length === 0) continue
      L.push(`            JdFocusGroup("${esc(g.title)}", ${g.note ? `"${esc(g.note.trim())}"` : 'null'}, listOf(`)
      for (const r of refs) {
        const [code, id] = r.split(' ')
        L.push(`                JdFocusLab("${code}", "${id}"),`)
      }
      L.push('            )),')
    }
    L.push('        )),')
  }
  L.push('    )')
  L.push('}')
  L.push('')
  return L.join('\n')
}

const spec = parseSpec()
const { labs, units } = scanLabs()
const seen = new Map<string, string>()
for (const t of spec.topics) for (const g of t.groups) for (const r of g.labs) {
  if (!labs.has(r)) throw new Error(`topics.yaml 的演練「${r}」找不到`)
  if (seen.has(r) && seen.get(r) !== t.id) console.warn(`提醒：${r} 同時出現在 ${seen.get(r)} 與 ${t.id}`)
  seen.set(r, t.id)
}

writeFileSync(path.join(ROOT, 'labs/ios/LabKit/Sources/LabKit/JdFocus.swift'), genSwift(spec, labs))
writeFileSync(path.join(ROOT, 'labs/android/labkit/src/main/java/tw/harry/labs/kit/JdFocus.kt'), genKotlin(spec, labs))
const total = [...seen.keys()].length
console.log(`✓ 四大主題：${spec.topics.map((t) => `${t.title} ${t.groups.reduce((n, g) => n + g.labs.length, 0)}`).join('、')}；共 ${total} 個演練（全部 ${labs.size} 個）`)
