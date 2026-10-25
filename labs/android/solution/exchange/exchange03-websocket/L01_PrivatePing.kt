// L01：私有頻道連上沒多久就被伺服器切斷（三個端點的心跳格式不一樣）
// 對應複習站：Exchange03WebSocket 第 1 題「這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？」
//
// 情境：App 同時連兩個端點：公開行情 /ws，和私有的訂單與資產 /ws/v2。兩個端點都會定時送 ping，連續 2 次沒有收到正確的 pong，伺服器就切斷連線：
//        /ws 的 ping 是 {"ping":數字}，要回 {"pong":同一個數字}；
//        /ws/v2 的 ping 是 {"action":"ping","data":{"ts":數字}}，要回 {"action":"pong","data":{"ts":同一個數字}}。
//        （為了專心看心跳的格式，這個演練的訊息已經解壓好了，直接是 JSON 文字。）
// Bug 版：心跳的處理只認識 /ws 的格式。/ws/v2 的 ping 沒有 "ping" 這個欄位，被當成「不是心跳」，沒有回 pong，連線被切斷。
// 修正版：依端點分別處理：每個端點各有自己的 ping 格式與對應的 pong 格式。
package tw.harry.labs.solution.exchange03websocket

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
        val ping = e03Parse(message)?.e03Long("ping") ?: return null
        return """{"pong":$ping}"""                             // 只認得 /ws 的 {"ping":N}
    }

    suspend fun run(log: LabLog) = scenario(log, ::reply)
}

// MARK: - 修正版
private object L01Fixed {
    fun reply(message: String, endpoint: Endpoint): String? {
        val json = e03Parse(message) ?: return null
        return when (endpoint) {
            Endpoint.MARKET -> {                                // 公開行情：{"ping":N} → {"pong":N}
                val ping = json.e03Long("ping") ?: return null
                """{"pong":$ping}"""
            }
            Endpoint.PRIVATE_ORDERS -> {                        // 私有：{"action":"ping","data":{"ts":N}} → {"action":"pong","data":{"ts":N}}
                if (json.e03String("action") != "ping") return null
                val ts = json.e03Object("data")?.e03Long("ts") ?: return null
                """{"action":"pong","data":{"ts":$ts}}"""
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::reply)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "私有頻道連上沒多久就被伺服器切斷",
    question = "第 1 題：這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？",
    goal = "公開行情 /ws 與私有 /ws/v2 的 ping 和 pong 格式不一樣。每個端點都要用自己的格式回 pong，連線才不會被切斷。",
    steps = listOf(
        "選「Bug 版」執行，看哪個端點被伺服器切斷、是第幾次 ping 沒有回。",
        "想一想：/ws/v2 送來的 ping 長什麼樣子？有 \"ping\" 這個欄位嗎？",
        "選「修正版」執行，兩個端點都要維持連線。",
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
