// L01：資料一大包進來，心跳來不及回，伺服器把連線切掉（管線切段：socket 這一段不能被解析和畫面卡住）
// 對應複習站：Advanced05HighFrequency 第 2 題「行情從伺服器到畫面，整條處理管線怎麼切？」
//
// 情境：伺服器的規則：每 150 毫秒送一次 ping，client 要在 300 毫秒內回 pong；連續兩次逾時就切斷連線。
//        連線一開始，伺服器就推來一大包行情（解析 JSON、解壓縮要花 800 毫秒）。後面接著是 4 次 ping。
//        （演練把真實的「約 5 秒一次、連續兩次沒回」縮小了，邏輯一樣。）
// Bug 版：收到的所有訊息（資料與 ping）都丟給同一條單執行緒的 executor 處理。那 800 毫秒的解析把它占滿，排在後面的 ping 要等解析做完才輪到，
//        pong 晚了 650、500、350 毫秒，連續逾時，伺服器切斷連線，使用者莫名其妙被斷線重連。
// 修正版：ping 在 socket 這一段（收訊息的執行緒）立刻回 pong；資料交給另一條單執行緒的 executor 去解析。解析再慢，心跳也不受影響。
package tw.harry.labs.solution.advanced05highfrequency

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val PING_INTERVAL_MS = 150L
private const val PONG_DEADLINE_MS = 300
private const val PARSE_COST_MS = 800L
private const val PING_COUNT = 4

/** 伺服器送來的訊息（兩個版本共用） */
private sealed interface Message {
    data object Data : Message
    data class Ping(val sentAt: Double) : Message
}

/**
 * 演練流程（兩個版本共用）：模擬伺服器，把訊息交給 client 的 onMessage（在「socket 執行緒」上呼叫）；最後依 pong 的延遲判斷有沒有被切斷。
 * [makeClient] 拿到「回 pong」與「解析完成」兩個回報函式，回傳「收到一則訊息」的處理函式。
 */
private suspend fun scenario(
    log: LabLog,
    makeClient: (onPong: (index: Int, delayMs: Double) -> Unit, onParsed: () -> Unit) -> (Message) -> Unit,
) = withContext(Dispatchers.Default) {
    val delays = ConcurrentHashMap<Int, Double>()
    val parsed = java.util.concurrent.atomic.AtomicInteger()
    val allPongs = CountDownLatch(PING_COUNT)
    val parsedDone = CountDownLatch(1)
    val onMessage = makeClient(
        { index, delay -> delays[index] = delay; allPongs.countDown() },
        { parsed.incrementAndGet(); parsedDone.countDown() },
    )

    thread(name = "socket") {
        onMessage(Message.Data)                                    // 連線一開始：一大包行情
        repeat(PING_COUNT) {
            Thread.sleep(PING_INTERVAL_MS)
            onMessage(Message.Ping(a05NowMs()))
        }
    }
    allPongs.await(3, TimeUnit.SECONDS)                            // 等 4 次 pong 都回了
    parsedDone.await(3, TimeUnit.SECONDS)                          // 等解析做完

    var misses = 0
    var disconnected = false
    for (i in 1..PING_COUNT) {
        val delay = delays[i] ?: 99_999.0
        val late = delay > PONG_DEADLINE_MS
        misses = if (late) misses + 1 else 0
        log("第 $i 次 ping，pong 延遲 ${minOf(delay, 99_999.0).toInt()} ms${if (late) "（逾時）" else ""}")
        if (misses >= 2) disconnected = true
    }
    log(if (disconnected) "連續 2 次逾時：伺服器切斷連線" else "每次 pong 都在 $PONG_DEADLINE_MS ms 內送出：連線正常")
    log("資料解析完成：${parsed.get()} 包")
}

// MARK: - Bug 版
private object L01Bug {
    suspend fun run(log: LabLog) = scenario(log) { onPong, onParsed ->
        val everything = Executors.newSingleThreadExecutor { Thread(it, "everything").apply { isDaemon = true } }       // 所有訊息都排進同一條執行緒
        var pingIndex = 0
        val handler: (Message) -> Unit = { message ->
            everything.execute {
                when (message) {
                    Message.Data -> {
                        Thread.sleep(PARSE_COST_MS)                                           // 解析 JSON、解壓縮：800 毫秒
                        onParsed()
                    }
                    is Message.Ping -> {
                        pingIndex++
                        onPong(pingIndex, a05NowMs() - message.sentAt)                        // 回 pong：輪到的時候才回
                    }
                }
            }
        }
        handler
    }
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun run(log: LabLog) = scenario(log) { onPong, onParsed ->
        val parser = Executors.newSingleThreadExecutor { Thread(it, "parser").apply { isDaemon = true } }               // 解析另外用一條執行緒
        var pingIndex = 0
        val handler: (Message) -> Unit = { message ->
            when (message) {
                Message.Data -> parser.execute {
                    Thread.sleep(PARSE_COST_MS)
                    onParsed()
                }
                is Message.Ping -> {
                    pingIndex++
                    onPong(pingIndex, a05NowMs() - message.sentAt)                            // 在 socket 這一段立刻回，不排隊
                }
            }
        }
        handler
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "資料一大包進來，心跳來不及回，連線被切掉",
    question = "第 2 題：行情從伺服器到畫面，整條處理管線怎麼切？",
    goal = "心跳（ping／pong）要在 socket 這一段立刻回，解析與畫面更新放到別的執行緒。解析再慢，連線也不會被切掉。",
    steps = listOf(
        "選「Bug 版」執行，看每次 ping 的 pong 延遲：前幾次排在 800 毫秒的解析後面，延遲都超過 300 毫秒，連續逾時，連線被切斷。",
        "選「修正版」執行：每次 pong 延遲幾乎是 0 ms，連線正常，資料照樣解析完成。",
        "進階：真的 App 用 Perfetto 錄製，看 socket 的執行緒與解析的執行緒各自在做什麼；主執行緒一卡，如果心跳也排在主執行緒上，pong 就會來不及回。",
        "想一想：gzip 解壓縮和 JSON 解析為什麼都屬於「socket 這一段之後」？主執行緒的畫面更新又該在管線的哪一段？（見這個單元的第 2 題）",
    ),
    expectBug = "4 次 ping 的 pong 延遲約 650、490、340、180 ms，前三次逾時（超過 300 ms），連續 2 次逾時：伺服器切斷連線。",
    expectFixed = "4 次 ping 的 pong 延遲都是 0 ms 左右，連線正常；資料解析完成：1 包。",
    file = "advanced05-high-frequency/L01_HeartbeatStarvation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("資料解析完成：1") } -> "資料要解析完成（onParsed 要被呼叫）：Bug 版也會解析，只是排在同一條執行緒"
            "連續 2 次逾時：伺服器切斷連線" !in lines -> "Bug 還沒重現：資料與 ping 要排進同一條執行緒，ping 才會排在 800 毫秒的解析後面"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "連續 2 次逾時：伺服器切斷連線" in lines -> "還是被切斷：ping 要在收到的當下立刻回 pong，不能排在解析後面"
            lines.none { it.startsWith("每次 pong 都在") } -> "演練沒有跑完"
            lines.none { it.startsWith("資料解析完成：1") } -> "資料沒有解析完成：Message.Data 要交給 parser 去解析"
            else -> null
        }
    },
)
