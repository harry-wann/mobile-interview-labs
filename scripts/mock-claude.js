/*
 * 本機測試用的假 window.claude（db／user／sample）。只在測試時注入（例如 Playwright 的 page.addInitScript），
 * 不屬於任何建置流程；scripts/make-artifact.mjs 會檢查正式產物裡沒有它的痕跡。
 *
 * 設定（在這支 script 之前先設 window.__mockClaudeConfig）：
 *   { uid: 'u_test' | null, db: true, user: true, sample: true,
 *     sampleDelay: 400, sampleError: 'rate_limited' | 'not_granted' | 'invalid_json' | null,
 *     dbDelay: 0 }   // dbDelay：每個 db 呼叫額外延遲幾毫秒（模擬慢速連線）
 * 測試用控制介面：window.__mockClaude
 *   .calls            每次 db／sample 呼叫的紀錄（[{ ts, ns, op, path, ... }]）
 *   .violations       「同一份文件同時有兩個寫入」的紀錄（必須是空的）
 *   .fail             [{ op, code, times, match? }]：讓符合條件的呼叫依序失敗（錯誤碼依 db.d.ts）
 *   .dump()           目前 db 內容 { path: body }
 *   .clear()          清空 db
 * db 內容存在 localStorage['__mockdb']（模擬「重新載入後資料還在」）；localStorage 不能用時退回記憶體。
 */
;(function () {
  var cfg = Object.assign({ uid: 'u_test', db: true, user: true, sample: true, sampleDelay: 400, sampleError: null, dbDelay: 0 }, window.__mockClaudeConfig || {})
  var MEM = {}
  var KEY = '__mockdb'

  function load() {
    try {
      var raw = localStorage.getItem(KEY)
      return raw ? JSON.parse(raw) : {}
    } catch (e) {
      return MEM
    }
  }
  function save(db) {
    try {
      localStorage.setItem(KEY, JSON.stringify(db))
    } catch (e) {
      MEM = db
    }
  }

  var ctl = (window.__mockClaude = {
    calls: [],
    violations: [],
    fail: [],
    config: cfg,
    dump: function () {
      return load()
    },
    clear: function () {
      save({})
    },
    /** 直接寫入文件（模擬「另一台裝置」寫了資料） */
    put: function (path, body) {
      var db = load()
      db[path] = JSON.parse(JSON.stringify(body))
      save(db)
    },
  })

  function dbErr(code, message) {
    return { code: code, message: message || code }
  }
  var SEG = /^[A-Za-z0-9_\-.~:@+]+$/
  function checkPath(path, wantEven) {
    var segs = path.split('/')
    if (segs.length > 16 || path.length > 1000) throw new TypeError('path too long: ' + path)
    segs.forEach(function (s) {
      if (!SEG.test(s) || s === '.' || s === '..' || s.length > 200) throw new TypeError('bad segment "' + s + '" in ' + path)
    })
    if (wantEven && segs.length % 2 !== 0) throw new TypeError('document path needs an even number of segments: ' + path)
    if (!wantEven && segs.length % 2 !== 1) throw new TypeError('collection path needs an odd number of segments: ' + path)
    return segs
  }
  function isPlain(o) {
    return o && typeof o === 'object' && !Array.isArray(o)
  }
  function merge(a, b) {
    var out = Object.assign({}, a)
    Object.keys(b).forEach(function (k) {
      out[k] = isPlain(out[k]) && isPlain(b[k]) ? merge(out[k], b[k]) : b[k]
    })
    return out
  }
  function maybeFail(op, path) {
    for (var i = 0; i < ctl.fail.length; i++) {
      var f = ctl.fail[i]
      if ((f.op === '*' || f.op === op) && (!f.match || path.indexOf(f.match) >= 0) && f.times > 0) {
        f.times--
        return dbErr(f.code)
      }
    }
    return null
  }
  var inflight = {}
  function latency() {
    return new Promise(function (r) {
      setTimeout(r, 10 + Math.random() * 30 + (cfg.dbDelay || 0))
    })
  }
  function ownSubtreeOnly(path) {
    var m = path.match(/^data\/users\/([^/]+)/)
    return !m || m[1] === cfg.uid
  }

  function snap(path, body) {
    return {
      id: path.split('/').pop(),
      exists: body !== undefined,
      data: function () {
        return body === undefined ? undefined : JSON.parse(JSON.stringify(body))
      },
      metadata: { fromCache: false, hasPendingWrites: false },
    }
  }

  function docRef(path) {
    checkPath(path, true)
    async function guarded(op, fn) {
      ctl.calls.push({ ts: Date.now(), ns: 'db', op: op, path: path })
      var isWrite = op === 'set' || op === 'update' || op === 'delete'
      if (isWrite) {
        if (inflight[path]) ctl.violations.push({ path: path, op: op })
        inflight[path] = (inflight[path] || 0) + 1
      }
      try {
        await latency()
        var err = maybeFail(op, path)
        if (err) throw err
        return fn(load())
      } finally {
        if (isWrite) inflight[path]--
      }
    }
    return {
      id: path.split('/').pop(),
      path: path,
      get: function () {
        return guarded('get', function (db) {
          return snap(path, ownSubtreeOnly(path) ? db[path] : undefined)
        })
      },
      set: function (data) {
        return guarded('set', function (db) {
          if (!isPlain(data)) throw dbErr('invalid_argument', 'body must be an object')
          if (!ownSubtreeOnly(path)) throw dbErr('invalid_argument', 'not your subtree')
          if (new TextEncoder().encode(JSON.stringify(data)).length > 256 * 1024) throw dbErr('invalid_argument', 'document over 256 KiB')
          db[path] = JSON.parse(JSON.stringify(data))
          save(db)
        })
      },
      update: function (data) {
        return guarded('update', function (db) {
          if (!isPlain(data)) throw dbErr('invalid_argument', 'body must be an object')
          if (!(path in db)) throw dbErr('invalid_argument', 'document does not exist')
          db[path] = merge(db[path], JSON.parse(JSON.stringify(data)))
          if (new TextEncoder().encode(JSON.stringify(db[path])).length > 256 * 1024) throw dbErr('invalid_argument', 'document over 256 KiB')
          save(db)
        })
      },
      delete: function () {
        return guarded('delete', function (db) {
          delete db[path]
          save(db)
        })
      },
      collection: function (p) {
        return colRef(path + '/' + p)
      },
      onSnapshot: function () {
        return function () {}
      },
    }
  }

  function colRef(path) {
    var segs = checkPath(path, false)
    return {
      path: path,
      doc: function (id) {
        return docRef(path + '/' + (id || 'auto' + Math.random().toString(36).slice(2, 8)))
      },
      get: async function () {
        ctl.calls.push({ ts: Date.now(), ns: 'db', op: 'get-collection', path: path })
        await latency()
        var err = maybeFail('get', path)
        if (err) throw err
        var db = load()
        var docs = Object.keys(db)
          .filter(function (p) {
            return p.indexOf(path + '/') === 0 && p.split('/').length === segs.length + 1 && ownSubtreeOnly(p)
          })
          .sort()
          .map(function (p) {
            return snap(p, db[p])
          })
        return { docs: docs, size: docs.length, empty: docs.length === 0 }
      },
    }
  }

  var dbNs = Object.freeze({
    doc: function (p) {
      return docRef(p)
    },
    collection: function (p) {
      return colRef(p)
    },
  })

  var userNs = Object.freeze({
    id: async function () {
      return cfg.uid
    },
    isOwner: async function () {
      return true
    },
    canEdit: async function () {
      return true
    },
  })

  var gradeJson = {
    score: 4,
    summary: '（mock）結構清楚，缺少雙平台捨入差異。',
    covered: ['Decimal／BigDecimal'],
    missing: ['雙平台捨入規則一致性'],
    inaccurate: [],
    followUps: ['為什麼不用 Int64？'],
    improved: '（mock）改寫後的口語版本。',
  }

  async function sampleJson(input, options) {
    options = options || {}
    ctl.calls.push({ ts: Date.now(), ns: 'sample', op: 'json', input: String(input), options: { modelTier: options.modelTier, hasSignal: !!options.signal } })
    var signal = options.signal
    if (signal && signal.aborted) throw { code: 'cancelled', message: 'aborted' }
    if (cfg.sampleError === 'not_granted') throw { code: 'not_granted', message: 'not granted' }
    if (cfg.sampleError === 'rate_limited') throw { code: 'rate_limited', message: 'rate limited' }
    await new Promise(function (resolve, reject) {
      var t = setTimeout(function () {
        if (options.onText) options.onText({ text: '{"score":', delta: '{"score":' })
      }, cfg.sampleDelay / 2)
      var t2 = setTimeout(function () {
        if (signal) signal.removeEventListener('abort', onAbort)
        resolve()
      }, cfg.sampleDelay)
      function onAbort() {
        clearTimeout(t)
        clearTimeout(t2)
        reject({ code: 'cancelled', message: 'aborted' })
      }
      if (signal) signal.addEventListener('abort', onAbort, { once: true })
    })
    if (cfg.sampleError === 'invalid_json') throw { code: 'invalid_json', message: 'no JSON', text: '抱歉，我無法用 JSON 回答：RAW-REPLY' }
    if (options.onText) options.onText({ text: JSON.stringify(gradeJson), delta: '...' })
    return JSON.parse(JSON.stringify(gradeJson))
  }
  var sampleNs = Object.assign(
    async function () {
      throw { code: 'invalid_request', message: 'use sample.json' }
    },
    { json: sampleJson },
  )

  var enabled = { db: cfg.db, user: cfg.user, sample: cfg.sample }
  var spaces = { db: dbNs, user: userNs, sample: sampleNs }
  // window.claude 只有 use（和真實環境一致）
  window.claude = Object.freeze({
    use: function (name) {
      return Promise.resolve(enabled[name] ? spaces[name] : null)
    },
  })
})()
