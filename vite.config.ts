import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { viteSingleFile } from 'vite-plugin-singlefile'
import { contentPlugin } from './runner/content-plugin.ts'
import { apiPlugin } from './runner/server-plugin.ts'

const isStatic = process.env.VITE_STATIC === '1'

export default defineConfig({
  plugins: [react(), contentPlugin(), apiPlugin(), ...(isStatic ? [viteSingleFile()] : [])],
  server: {
    // 避開 Vite 預設的 5173（其他專案常用）；被佔用時直接報錯，避免 port 漂移讓手機的 token 網址失效
    port: 6030,
    strictPort: true,
    // 手機用 http://<電腦名稱>.local:6030 開啟時需要
    allowedHosts: ['.local'],
    // 口徑資料、區網 token、程式碼掃描筆記不得經由 dev server 直接讀取（保留 Vite 預設的 deny 項目）
    fs: {
      deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/data/**', '**/.lan-token', '**/research/**', '**/.tools/**', '**/.runner-tmp/**'],
    },
    watch: {
      ignored: [
        '**/.runner-tmp/**', '**/.tools/**', '**/research/**', '**/data/**', '**/artifact/**', '**/dist*/**',
        // 演練專案的建置產物（Gradle、Xcode）很大又一直變，不要監看
        '**/labs/**/build/**', '**/labs/**/.gradle/**', '**/labs/**/.kotlin/**', '**/labs/**/*.xcodeproj/**', '**/.claude/worktrees/**',
      ],
    },
  },
  preview: { port: 6031, strictPort: true, allowedHosts: ['.local'] },
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 6000,
    ...(isStatic
      ? {
          // Artifact：單一檔案、classic script（IIFE）
          cssCodeSplit: false,
          assetsInlineLimit: 100_000_000,
          rolldownOptions: { output: { format: 'iife' } },
        }
      : {}),
  },
})
