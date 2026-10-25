// Vite 外掛：把 content/ 建成虛擬模組 `virtual:prep-content`，dev 時監看 content/ 並通知前端熱更新。
//   build:static（發布 Artifact 用）：嚴格，有內容錯誤就中止；CONTENT_LENIENT=1 可改成跳過有錯的單元
//   build（本機 preview）與 dev：容錯，有錯的單元跳過並印出錯誤；CONTENT_STRICT=1 可改成嚴格
//   網站頂部會顯示「N 個內容錯誤」清單
import path from 'node:path'
import type { Plugin } from 'vite'
import { buildContent, CONTENT_DIR, formatIssues, writeOutputs, type BuildResult } from '../scripts/content/build.ts'
import { LABS_DIR } from '../scripts/content/lab-code.ts'

const VIRTUAL_ID = 'virtual:prep-content'
const RESOLVED_ID = '\0' + VIRTUAL_ID
export const CONTENT_EVENT = 'prep:content'

export function contentPlugin(): Plugin {
  let strict = true
  let current: BuildResult | null = null
  let building: Promise<void> | null = null

  async function rebuild() {
    const result = await buildContent({ strict })
    current = result
    await writeOutputs(result)
  }

  return {
    name: 'prep-content',
    configResolved(config) {
      const isBuild = config.command === 'build'
      const isStatic = process.env.VITE_STATIC === '1'
      strict = isBuild && (process.env.CONTENT_STRICT === '1' || (isStatic && process.env.CONTENT_LENIENT !== '1'))
    },
    async buildStart() {
      await rebuild()
      if (strict && current && current.errors.length) {
        throw new Error(`內容驗證失敗（${current.errors.length} 個錯誤）：\n${formatIssues(current.errors)}\n\n（先跑 npm run content:check 看錯誤；要忽略有錯的單元可加 CONTENT_LENIENT=1）`)
      }
      if (current) {
        if (current.errors.length) this.warn(`有 ${current.errors.length} 個內容錯誤，對應單元已被略過：\n${formatIssues(current.errors)}`)
        const s = current.stats
        this.info(`內容：${s.units} 個單元、${s.exercises} 題${current.errors.length ? `，${current.errors.length} 個錯誤（已跳過）` : ''}`)
      }
    },
    resolveId(id) {
      if (id === VIRTUAL_ID) return RESOLVED_ID
    },
    load(id) {
      if (id !== RESOLVED_ID || !current) return
      // JSON.parse 比同等大小的物件字面值解析快，且避免超大 JS 物件拖慢 parser
      return `export default JSON.parse(${JSON.stringify(JSON.stringify(current.content))})`
    },
    configureServer(server) {
      let timer: NodeJS.Timeout | undefined
      const isLabSource = (p: string) =>
        p.startsWith(LABS_DIR + path.sep) && /\.(swift|kt|java|m|h)$/.test(p) && !/[/\\](build|\.gradle|\.build)[/\\]/.test(p)
      const schedule = (file: string) => {
        const p = path.resolve(file)
        if (!p.startsWith(CONTENT_DIR + path.sep) && !isLabSource(p)) return
        clearTimeout(timer)
        timer = setTimeout(() => {
          building = (building ?? Promise.resolve())
            .then(async () => {
              await rebuild()
              const mod = server.moduleGraph.getModuleById(RESOLVED_ID)
              if (mod) server.moduleGraph.invalidateModule(mod)
              server.ws.send({ type: 'custom', event: CONTENT_EVENT, data: { builtAt: current?.content.builtAt } })
              const e = current?.errors.length ?? 0
              server.config.logger.info(`[content] 已重建：${current?.stats.units} 單元、${current?.stats.exercises} 題${e ? `，${e} 個錯誤` : ''}`, { timestamp: true })
            })
            .catch((err) => server.config.logger.error(`[content] 重建失敗：${err instanceof Error ? err.message : err}`))
        }, 350)
      }
      server.watcher.add(CONTENT_DIR)
      for (const ev of ['add', 'change', 'unlink'] as const) server.watcher.on(ev, schedule)
    },
  }
}
