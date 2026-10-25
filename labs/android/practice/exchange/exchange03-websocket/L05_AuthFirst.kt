// L05：私有頻道連上了，卻一直收不到自己的訂單（還沒認證成功就送 sub）
// 對應複習站：Exchange03WebSocket 第 5 題「私有頻道怎麼用？認證、限流、順序不保證是什麼意思？」
//
// 情境：連上私有頻道 /ws/v2 之後，要先送簽名過的 auth（認證），收到 {"action":"req","code":200,"ch":"auth",...} 才能訂閱。
//        假伺服器：auth 的結果要過一小段時間才會回來；認證成功之前收到的 sub 一律拒絕，
//        回 {"action":"sub","code":2002,...}（錯誤碼與文字是演練的假設，不是交易所的規格）。
//        App 想訂閱 orders#btcjpy（自己的訂單）。
//
// 你要寫：
//   ① Bug 版（L05Bug.Client.onOpen）：連上之後把 auth 和 sub 連續送出，不等認證結果。
//   ② 修正版（L05Fixed.Client.onMessage）：收到認證成功（ch 是 auth、code 是 200）的回覆，才送 sub。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange03-websocket/L05_AuthFirst.kt
package tw.harry.labs.practice.exchange03websocket

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val AUTH_REQUEST = """{"action":"req","ch":"auth","params":{"authType":"api","signatureMethod":"HmacSHA256","signatureVersion":"2.1"}}"""
private const val SUB_REQUEST = """{"action":"sub","ch":"orders#btcjpy"}"""

/** 假的私有頻道伺服器 */
private class PrivateServer {
    private var authenticated = false
    private var authPending = false
    private val outbox = mutableListOf<String>()
    var rejectedSubs = 0
        private set

    fun receive(text: String) {
        val json = e03Parse(text) ?: return
        val action = json.e03String("action") ?: return
        val ch = json.e03String("ch") ?: return
        if (action == "req" && ch == "auth") {
            authPending = true                                  // 認證要過一小段時間才有結果
        } else if (action == "sub") {
            if (authenticated) {
                outbox += """{"action":"sub","code":200,"ch":"$ch","data":{}}"""
            } else {
                rejectedSubs++
                outbox += """{"action":"sub","code":2002,"ch":"$ch","message":"invalid.auth.state"}"""
            }
        }
    }

    /** 過了一小段時間：把等待中的結果送出去 */
    fun tick(): List<String> {
        if (authPending) {
            authPending = false
            authenticated = true
            outbox.add(0, """{"action":"req","code":200,"ch":"auth","data":{}}""")
        }
        val result = outbox.toList()
        outbox.clear()
        return result
    }
}

/** 演練流程（兩個版本共用）：連上 → 伺服器每過一小段時間把結果送回來，App 逐一處理 */
private fun scenario(
    log: LabLog,
    onOpen: (PrivateServer) -> Unit,
    onMessage: (String, PrivateServer) -> Unit,
    subscribed: () -> Boolean,
) {
    val server = PrivateServer()
    onOpen(server)
    repeat(3) {
        for (message in server.tick()) {
            log("收到：${message.take(70)}")
            onMessage(message, server)
        }
    }
    log("伺服器拒絕了 ${server.rejectedSubs} 次 sub")
    log("訂閱 orders#btcjpy：${if (subscribed()) "成功，會收到自己的訂單" else "沒有成功，畫面永遠收不到訂單"}")
}

// MARK: - Bug 版
private object L05Bug {
    class Client {
        var subscribed = false

        fun onOpen(server: PrivateServer) {
            server.receive(AUTH_REQUEST)
            // TODO ①：不等認證結果，接著馬上送出訂閱 server.receive(SUB_REQUEST)
        }

        fun onMessage(text: String, server: PrivateServer) {
            val json = e03Parse(text) ?: return
            if (json.e03String("action") == "sub" && json.e03Long("code") == 200L) subscribed = true
        }
    }

    suspend fun run(log: LabLog) {
        val client = Client()
        scenario(log, client::onOpen, client::onMessage) { client.subscribed }
    }
}

// MARK: - 修正版
private object L05Fixed {
    class Client {
        var subscribed = false

        fun onOpen(server: PrivateServer) {
            server.receive(AUTH_REQUEST)                        // 連上之後只送 auth
        }

        fun onMessage(text: String, server: PrivateServer) {
            val json = e03Parse(text) ?: return
            val code = json.e03Long("code")
            // TODO ②：收到認證成功的回覆（"ch" 是 "auth"，code 是 200）時，才送出訂閱 server.receive(SUB_REQUEST)
            if (json.e03String("action") == "sub" && code == 200L) subscribed = true
        }
    }

    suspend fun run(log: LabLog) {
        val client = Client()
        scenario(log, client::onOpen, client::onMessage) { client.subscribed }
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "私有頻道連上了，卻一直收不到自己的訂單",
    question = "第 5 題：私有頻道怎麼用？認證、限流、順序不保證是什麼意思？",
    goal = "私有頻道要先認證成功，才能訂閱。要等收到認證成功的回覆，再送 sub。先寫出不等認證結果的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange03-websocket/L05_AuthFirst.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看伺服器對 sub 的回覆、最後訂閱有沒有成功。",
        "想一想：認證的結果什麼時候才回來？sub 是在那之前還是之後到伺服器？",
        "寫 TODO ②（修正版），選「修正版」執行，看 sub 是在收到哪一則訊息之後才送出。",
        "進階：成交和取消的通知順序不保證，狀態合併要容許亂序（見下單流程單元）；限流的 429 或 too.many.connection 要退避再連（見連線韌性單元）。",
    ),
    expectBug = "「伺服器拒絕了 1 次 sub」，認證成功之後 App 沒有再送 sub，「訂閱 orders#btcjpy：沒有成功，畫面永遠收不到訂單」。",
    expectFixed = "「伺服器拒絕了 0 次 sub」，收到認證成功之後才送 sub，「訂閱 orders#btcjpy：成功，會收到自己的訂單」。",
    file = "exchange03-websocket/L05_AuthFirst.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("伺服器拒絕了") } -> "演練沒有跑完：要用 scenario(log, onOpen, onMessage, subscribed) 執行"
            "伺服器拒絕了 1 次 sub" !in lines || "訂閱 orders#btcjpy：沒有成功，畫面永遠收不到訂單" !in lines ->
                "Bug 還沒重現：onOpen 要把 auth 和 sub 連續送出（不等認證結果），sub 會被拒絕一次"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("伺服器拒絕了") } -> "演練沒有跑完：要用 scenario(log, onOpen, onMessage, subscribed) 執行"
            "伺服器拒絕了 0 次 sub" !in lines -> "sub 不能比認證結果先送出：onOpen 只送 auth"
            "訂閱 orders#btcjpy：成功，會收到自己的訂單" !in lines -> "收到認證成功（ch 是 auth、code 是 200）之後，要送 sub；收到 sub 的成功回覆才算訂閱成功"
            else -> null
        }
    },
)
