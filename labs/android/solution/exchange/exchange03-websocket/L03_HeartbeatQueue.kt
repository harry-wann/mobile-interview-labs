// L03：行情一多，伺服器就把連線切斷（心跳排在行情處理的後面）
// 對應複習站：Exchange03WebSocket 第 3 題「心跳 ping／pong
// 是什麼？為什麼要最先處理？」
//
// 情境：伺服器一次推來 20 則行情，緊接著送一則 {"ping":N}，並規定 0.5
// 秒內要收到 {"pong":N}，否則切斷連線。
//        每則行情要在「畫面執行緒」上處理，一則花 40 毫秒（用 Thread.sleep
// 代表列表正在忙著畫畫面）。
//        訊息從 socket 的背景執行緒進來；畫面執行緒是另一條單一執行緒，
// 一次只做一件事。（演練的時限是為了縮短等待而假設的，真正的伺服器約每 5
// 秒送一次 ping。）
// Bug 版：每一則訊息（包括 ping）都丟到畫面執行緒處理。ping 排在 20
// 則行情後面，等 0.8 秒才輪到，超過 0.5 秒，連線被切斷。
// 修正版：解壓後先在 socket 的背景執行緒檢查心跳，是 ping 就立刻回 pong；
// 只有行情才丟到畫面執行緒。
package tw.harry.labs.solution.exchange03websocket

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 計時用的簡單鎖 */
private class Stopwatch {
    private val start = System.nanoTime()
    private var pingSentAt: Double? = null
    private var pongAt: Double? = null
    private var pongText: String? = null

    private fun nowMs() = (System.nanoTime() - start) / 1_000_000.0

    @Synchronized fun markPingSent() { pingSentAt = nowMs() }

    @Synchronized fun markPong(text: String) {
        if (pongAt ==
            null
        ) { pongAt = nowMs(); pongText = text }
    }

    @Synchronized fun result(): Pair<Double, String>? {
        val sent = pingSentAt ?: return null
        val at = pongAt ?: return null
        return (at - sent) to (pongText ?: "")
    }
}

/** 處理一則行情要花的時間（代表畫面忙著更新列表） */
private fun renderQuote() = Thread.sleep(40)

/**
 * 演練流程（兩個版本共用）：socket 執行緒依序送進 20 則行情與 1 則 ping，等 1.
 * 3 秒，看 pong 什麼時候到
 */
private suspend fun scenario(
    log: LabLog,
    makeHandler: (ui: Executor, send: (String) -> Unit) -> (ByteArray) -> Unit,
) {
    val clock = Stopwatch()
    val socket: ExecutorService = Executors.newSingleThreadExecutor()
    val ui: ExecutorService = Executors.newSingleThreadExecutor()
    try {
        val handle = makeHandler(ui) { text -> clock.markPong(text) }
        log("伺服器：送出 20 則行情，接著送 {\"ping\":777}，要求 0.5 秒內回 pong")
        for (i in 1..20) {
            val frame = E03Wire.frame(
                """{"ch":"market.btcjpy.bbo","tick":{"seqId":$i}}""",
            )
            socket.execute { handle(frame) }
        }
        val pingFrame = E03Wire.frame("""{"ping":777}""")
        socket.execute {
            clock.markPingSent()
            handle(pingFrame)
        }
        delay(1300)
        val result = clock.result()
        if (result != null) {
            val (elapsed, text) = result
            log("收到 $text，在 ping 送出後 ${elapsed.toInt()} ms")
            if (elapsed > 500 || text != """{"pong":777}""") {
                log("伺服器：沒有在 0.5 秒內收到正確的 pong，切斷連線")
            } else {
                log("伺服器：pong 準時，連線維持")
            }
        } else {
            log("伺服器：1.3 秒內沒有收到 pong，切斷連線")
        }
    } finally {
        socket.shutdownNow()
        ui.shutdownNow()
    }
}

// MARK: - Bug 版
private object L03Bug {
    fun makeHandler(ui: Executor, send: (String) -> Unit): (ByteArray) -> Unit =
        { frame ->
            // 全部丟到畫面執行緒：ping 要排在行情後面
            ui.execute {
                val json = E03Wire.text(frame)?.let { e03Parse(it) }
                if (json != null) {
                    val ping = json.e03Long("ping")
                    if (ping !=
                        null
                    ) {
                        send("""{"pong":$ping}""")
                    } else {
                        renderQuote()
                    }
                }
            }
        }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 修正版
private object L03Fixed {
    fun makeHandler(ui: Executor, send: (String) -> Unit): (ByteArray) -> Unit =
        handler@{ frame ->
            val json =
                E03Wire.text(frame)?.let { e03Parse(it) } ?: return@handler
            val ping = json.e03Long("ping")
            // 心跳最先處理：在背景執行緒馬上回，pong 的數字原樣回傳
            if (ping != null) {
                send("""{"pong":$ping}""")
                return@handler
            }
            // 只有行情才丟到畫面執行緒
            ui.execute { renderQuote() }
        }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 演練設定
private fun elapsedMs(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("收到 ") && "在 ping 送出後" in it
}?.let { Regex("送出後 (\\d+) ms").find(it)?.groupValues?.get(1)?.toInt() }

val l03 = Lab(
    id = "L03",
    title = "行情一多，伺服器就把連線切斷",
    question = "第 3 題：心跳 ping／pong 是什麼？為什麼要最先處理？",
    goal = "心跳要在背景最先處理，不能排在行情處理的後面。ping 來了就立刻原樣回 pong。",
    steps = listOf(
        "選「Bug 版」執行（約 1.3 秒），看 pong 在 ping 送出後幾毫秒才到、伺服器有沒有切斷連線。",
        "想一想：20 則行情每則處理 40 毫秒，ping 排在它們後面要等多久？",
        "選「修正版」執行，pong 應該在幾毫秒內就回去，而且數字和 ping 一模一樣。",
        "進階：真實的做法是解壓與解碼都在 OkHttp 的背景執行緒做（onMessage 本來就不在主執行緒），心跳是不依賴任何狀態的純函式；行情處理再交給別的執行緒。OkHttp 的 pingInterval 是 WebSocket 協定層的 ping，不能取代這個 JSON 心跳。",
    ),
    expectBug = "「收到 {\"pong\":777}，在 ping 送出後 870 到 920 ms」（一定超過 800 ms），「伺服器：沒有在 0.5 秒內收到正確的 pong，切斷連線」。",
    expectFixed = "pong 在幾毫秒內就回去（遠小於 500 ms），「伺服器：pong 準時，連線維持」。",
    file = "exchange03-websocket/L03_HeartbeatQueue.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = elapsedMs(lines)
        when {
            lines.none {
                it.startsWith("伺服器：送出 20 則行情")
            } -> "演練沒有跑完：要用 scenario(log, ::makeHandler) 執行"

            ms == null -> "Bug 版也要回 pong：解壓、解析之後，收到 ping 就回 {\"pong\":N}"

            ms <= 500 -> "Bug 還沒重現：ping 要和行情一樣丟到畫面執行緒（ui.execute）處理，才會排在 20 則行情後面（目前 $ms ms 就回了）"

            else -> null
        }
    },
    checkFixed = { lines ->
        val ms = elapsedMs(lines)
        when {
            lines.none {
                it.startsWith("伺服器：送出 20 則行情")
            } -> "演練沒有跑完：要用 scenario(log, ::makeHandler) 執行"

            ms == null -> "沒有回 pong：解壓、解析之後，收到 ping 要馬上回 {\"pong\":N}"

            "伺服器：pong 準時，連線維持" !in lines -> "pong 太晚才回（$ms ms）：ping 不要丟到畫面執行緒，在 socket 的背景執行緒直接回；數字要和 ping 一模一樣"

            else -> null
        }
    },
)
