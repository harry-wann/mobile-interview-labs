// L01：私有頻道連上沒多久就被伺服器切斷（三個端點的心跳格式不一樣）
// 對應複習站：Exchange03WebSocket 第 1 題「這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？」
//
// 情境：App 同時連兩個端點：公開行情 /ws，和私有的訂單與資產 /ws/v2。兩個端點都會定時送 ping，連續 2 次沒有收到正確的 pong，伺服器就切斷連線：
//        /ws 的 ping 是 {"ping":數字}，要回 {"pong":同一個數字}；
//        /ws/v2 的 ping 是 {"action":"ping","data":{"ts":數字}}，要回 {"action":"pong","data":{"ts":同一個數字}}。
//        （為了專心看心跳的格式，這個演練的訊息已經解壓好了，直接是 JSON 文字。）
//
// 你要寫：
//   ① Bug 版（L01Bug.reply）：只處理 /ws 的 {"ping":N}，回 {"pong":N}；其他格式回傳 null。
//   ② 修正版（L01Fixed.reply）：依 endpoint 分別處理：/ws 回 {"pong":N}；/ws/v2 回 {"action":"pong","data":{"ts":N}}。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange03-websocket/L01_PrivatePing.kt
package tw.harry.labs.practice.exchange03websocket

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Endpoint(val path: String) {
    MARKET("/ws"),
    PRIVATE_ORDERS("/ws/v2"),
}

/** 伺服器端：這個端點的 ping 長什麼樣子、收到的回覆是不是對應的 pong */
private fun pingText(endpoint: Endpoint, value: Long): String = when (endpoint) {
    Endpoint.MARKET -> """{"ping":$value}"""
    Endpoint.PRIVATE_ORDERS -> """{"action":"ping","data":{"ts":$value}}"""
}

private fun isCorrectPong(endpoint: Endpoint, reply: String?, value: Long): Boolean {
    val json = reply?.let { e03Parse(it) } ?: return false
    return when (endpoint) {
        Endpoint.MARKET -> json.e03Long("pong") == value
        Endpoint.PRIVATE_ORDERS -> json.e03String("action") == "pong" && json.e03Object("data")?.e03Long("ts") == value
    }
}

/** 演練流程（兩個版本共用）：每個端點依序收到 4 次 ping，連續 2 次沒有正確回覆就被切斷 */
private fun scenario(log: LabLog, reply: (String, Endpoint) -> String?) {
    var closedCount = 0
    for (endpoint in Endpoint.entries) {
        var missed = 0
        var closed = false
        for (i in 1..4) {
            val value = 18_212_558_000L + i * 5_000
            if (isCorrectPong(endpoint, reply(pingText(endpoint, value), endpoint), value)) {
                missed = 0
            } else {
                missed++
                log("${endpoint.path}：第 $i 次 ping 沒有收到正確的 pong（連續 $missed 次）")
                if (missed >= 2) { closed = true; break }
            }
        }
        if (closed) closedCount++
        log("${endpoint.path}：${if (closed) "連續 2 次沒回 pong，伺服器切斷連線" else "連線維持"}")
    }
    log("被伺服器切斷的連線有 $closedCount 條")
}

// MARK: - Bug 版
private object L01Bug {
    fun reply(message: String, endpoint: Endpoint): String? {
        // TODO ①：用 e03Parse 解析 message，取出 "ping" 的數字 N（e03Long("ping")），回傳 """{"pong":$N}"""；
        //        沒有 "ping" 欄位就回傳 null（只處理 /ws 的格式，不管 endpoint）
        return null
    }

    suspend fun run(log: LabLog) = scenario(log, ::reply)
}

// MARK: - 修正版
private object L01Fixed {
    fun reply(message: String, endpoint: Endpoint): String? {
        // TODO ②：先用 e03Parse 解析 message；再用 when (endpoint) 分別處理：
        //   MARKET：取 "ping" 的數字 N，回傳 """{"pong":$N}"""
        //   PRIVATE_ORDERS："action" 要是 "ping"，取 data 裡 "ts" 的數字 N，回傳 """{"action":"pong","data":{"ts":$N}}"""
        //   取不到就回傳 null
        return null
    }

    suspend fun run(log: LabLog) = scenario(log, ::reply)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "私有頻道連上沒多久就被伺服器切斷",
    question = "第 1 題：這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？",
    goal = "公開行情 /ws 與私有 /ws/v2 的 ping 和 pong 格式不一樣。每個端點都要用自己的格式回 pong，連線才不會被切斷。先寫出只認得 /ws 格式的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange03-websocket/L01_PrivatePing.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看哪個端點被伺服器切斷、是第幾次 ping 沒有回。",
        "想一想：/ws/v2 送來的 ping 長什麼樣子？有 \"ping\" 這個欄位嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，兩個端點都要維持連線。",
        "進階：販売所的 /retail/ws 又是另一套：App 每 60 秒主動送 {\"action\":4,...}（4 代表 ping）。三套協定共用同一個連線層，各自寫解析。",
    ),
    expectBug = "/ws 連線維持；/ws/v2 的第 1、2 次 ping 都沒有回，「/ws/v2：連續 2 次沒回 pong，伺服器切斷連線」，「被伺服器切斷的連線有 1 條」。",
    expectFixed = "兩個端點都維持連線，「被伺服器切斷的連線有 0 條」。",
    file = "exchange03-websocket/L01_PrivatePing.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("被伺服器切斷的連線有") } -> "演練沒有跑完：要用 scenario(log, ::reply) 執行"
            "/ws：連線維持" !in lines -> "公開行情 /ws 的 ping 要能回 pong：{\"ping\":N} → {\"pong\":N}"
            "/ws/v2：連續 2 次沒回 pong，伺服器切斷連線" !in lines -> "Bug 還沒重現：只處理 {\"ping\":N} 的格式，/ws/v2 的 ping 不會回 pong"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("被伺服器切斷的連線有") } -> "演練沒有跑完：要用 scenario(log, ::reply) 執行"
            "/ws：連線維持" !in lines -> "公開行情 /ws 的 ping 要能回 pong：{\"ping\":N} → {\"pong\":N}"
            "/ws/v2：連線維持" !in lines ->
                "私有 /ws/v2 的 ping 是 {\"action\":\"ping\",\"data\":{\"ts\":N}}，要回 {\"action\":\"pong\",\"data\":{\"ts\":N}}（N 要一模一樣）"
            else -> null
        }
    },
)
