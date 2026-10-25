// 直接呼叫 server-plugin 的 middleware（不開 HTTP port）：假的 req／res
import { Readable } from 'node:stream'

type Middleware = (req: never, res: never, next: (err?: unknown) => void) => void

export function callApi(mw: Middleware, method: string, url: string, body?: unknown, remoteAddress = '127.0.0.1'): Promise<{ status: number; json: unknown }> {
  return new Promise((resolve) => {
    const payload = body === undefined ? [] : [Buffer.from(JSON.stringify(body))]
    const req = Object.assign(Readable.from(payload), {
      method,
      url,
      headers: { host: 'localhost:5173', 'content-type': 'application/json' },
      socket: { remoteAddress },
    })
    const res = {
      statusCode: 200,
      writableFinished: false,
      setHeader() {},
      on() {},
      end(text: string) {
        this.writableFinished = true
        resolve({ status: this.statusCode, json: text ? JSON.parse(text) : null })
      },
    }
    mw(req as never, res as never, () => resolve({ status: 404, json: null }))
  })
}
