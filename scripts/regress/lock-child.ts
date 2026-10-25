// 跨行程鎖的測試子行程（scripts/regress-store.ts 會同時開兩個）：用真正的 server middleware 對同一個資料目錄送 n 次 PUT。
//   tsx scripts/regress/lock-child.ts <dataDir> <prefix> <n>
export {}
const [dir, prefix, n] = process.argv.slice(2)
process.env.PREP_DATA_DIR = dir
const { apiMiddleware } = await import('../../runner/server-plugin.ts')
const { callApi } = await import('./http.ts')
for (let i = 0; i < Number(n); i++) {
  const r = await callApi(apiMiddleware, 'PUT', '/api/answers', { version: 3, patches: { [`${prefix}-${i}`]: { text: { v: `${prefix} 的第 ${i} 次編輯`, base: 0 } } } })
  if (r.status !== 200) {
    console.error(prefix, i, r.status, JSON.stringify(r.json))
    process.exit(1)
  }
  // 同一題也交錯寫（兩個行程搶同一個 entry 的 history）
  const r2 = await callApi(apiMiddleware, 'PUT', '/api/answers', { version: 3, patches: { shared: { text: { v: `${prefix}-${i}`, base: 0 } } } })
  if (r2.status !== 200) process.exit(1)
}
process.exit(0)
