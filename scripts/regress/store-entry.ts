// scripts/regress-store.ts 用的打包入口：把前端的 store／cloud／lifecycle 等模組打包成一個 IIFE（全域 BT），
// 每台「裝置」在各自的 vm context 裡執行一份（各自的 window、localStorage、fetch、計時器），模擬多台裝置同時使用。
export * as store from '../../src/lib/store.ts'
export * as cloud from '../../src/lib/cloud.ts'
export * as lifecycle from '../../src/lib/lifecycle.ts'
export * as data from '../../src/lib/data.ts'
export * as storage from '../../src/lib/storage.ts'
export { FieldEditor } from '../../src/lib/fieldEditor.ts'
export { getIndex, setContent } from '../../src/content.ts'
export { pendingFacts } from '../../src/lib/stats.ts'
