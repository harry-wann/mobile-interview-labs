// 跨行程的檔案鎖：npm run dev 與 npm run preview 同時執行時，兩個 Node 行程「讀 → 合併 → 寫」同一份資料檔不會互相覆蓋。
//
// 做法：mkdir 鎖目錄（原子操作，已存在就是被別人持有），目錄裡記下持有者（pid、token、時間）。
// 拿不到就短暫等待後重試；持有者行程已不存在、或鎖超過 staleMs 沒釋放（行程當掉留下的），視為殘留並清掉。
// 釋放時只刪自己的鎖（token 相同）。清除殘留鎖時先改名成唯一的名字再刪，改名後確認拿到的確實是剛剛判定為殘留的那一個。
import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import path from 'node:path'

export class LockTimeoutError extends Error {}

export interface FileLockOptions {
  /** 鎖超過這個時間沒釋放就當成殘留（預設 15 秒；正常的讀寫在 1 秒內完成） */
  staleMs?: number
  /** 最多等多久（預設 10 秒），之後丟 LockTimeoutError */
  timeoutMs?: number
  pollMs?: number
}

interface Owner {
  pid: number
  token: string
  at: number
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

async function readOwner(lockDir: string): Promise<Owner | null> {
  try {
    const o = JSON.parse(await readFile(path.join(lockDir, 'owner'), 'utf8')) as Owner
    return typeof o?.token === 'string' ? o : null
  } catch {
    return null
  }
}

function pidAlive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid <= 0) return false
  if (pid === process.pid) return true
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM' // 存在但不是我們的行程
  }
}

/** 殘留的鎖：回傳它的 token（owner 檔還沒寫好時用目錄時間判斷，token 為 ''）；不是殘留回傳 null */
async function staleToken(lockDir: string, staleMs: number): Promise<string | null> {
  const owner = await readOwner(lockDir)
  if (owner) {
    if (!pidAlive(owner.pid) || Date.now() - owner.at > staleMs) return owner.token
    return null
  }
  try {
    const st = await stat(lockDir)
    return Date.now() - st.mtimeMs > staleMs ? '' : null
  } catch {
    return null // 已經被釋放
  }
}

async function removeStale(lockDir: string, token: string) {
  const tomb = `${lockDir}.stale-${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`
  try {
    await rename(lockDir, tomb)
  } catch {
    return // 別人已經處理了
  }
  const owner = await readOwner(tomb)
  if ((owner?.token ?? '') !== token) {
    // 判定之後鎖已經換人持有：放回去（放不回去代表又有人拿到新的鎖，原本的持有者會在釋放時發現 token 不符而不刪）
    await rename(tomb, lockDir).catch(() => {})
    return
  }
  await rm(tomb, { recursive: true, force: true }).catch(() => {})
}

export async function withFileLock<T>(dir: string, name: string, fn: () => Promise<T>, opts: FileLockOptions = {}): Promise<T> {
  const staleMs = opts.staleMs ?? 15_000
  const timeoutMs = opts.timeoutMs ?? 10_000
  const pollMs = opts.pollMs ?? 10
  const lockDir = path.join(dir, `.lock-${name}`)
  const token = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`
  const started = Date.now()
  await mkdir(dir, { recursive: true })
  for (;;) {
    try {
      await mkdir(lockDir)
      await writeFile(path.join(lockDir, 'owner'), JSON.stringify({ pid: process.pid, token, at: Date.now() } satisfies Owner))
      break
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e
      const stale = await staleToken(lockDir, staleMs)
      if (stale !== null) {
        await removeStale(lockDir, stale)
        continue
      }
      if (Date.now() - started > timeoutMs) throw new LockTimeoutError(`等待 ${name} 的檔案鎖超過 ${Math.round(timeoutMs / 1000)} 秒`)
      await sleep(pollMs + Math.random() * pollMs)
    }
  }
  try {
    return await fn()
  } finally {
    const owner = await readOwner(lockDir)
    if (owner?.token === token) await rm(lockDir, { recursive: true, force: true }).catch(() => {})
  }
}
