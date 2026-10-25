// L03：開著訂單簿詳細頁，行情列表跟著變慢（分派訊息時，一個慢的畫面拖住所有畫面）
// 對應複習站：Design02MarketData 第 5 題「每秒很多則訊息，畫面怎麼保持順暢？」
//
// 情境：分派器收到 socket 傳來的價格，要交給兩個畫面：行情列表（處理很快）和 BTC 訂單簿詳細頁（每筆要算 20 檔、格式化，約 40 毫秒）。
//       一瞬間來了 8 筆價格（編號 1 到 8），都在 socket 的執行緒上交給分派器。
//       看行情列表什麼時候收到最後一筆（#8），以及詳細頁處理了幾筆。
//
// 你要寫：
//   ① Bug 版（L03Bug.Hub）：register 把 handler 存起來；publish 在 socket 的執行緒上，依序直接呼叫每個 handler。
//   ② 修正版（L03Fixed.Hub）：每個畫面一個 Channel（Channel.CONFLATED）加一個消費者協程（用 scope.launch）；publish 只負責 trySend 進每個畫面的緩衝區。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/design/design02-market-data/L03_SlowSubscriber.kt
package tw.harry.labs.practice.design02marketdata

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val L03_TICK_COUNT = 8
private const val L03_SLOW_COST_MS = 40L

private interface L03Dispatcher {
    /** 登記一個畫面：之後每一筆價格都要交給它的 handler */
    fun register(name: String, handler: (Int) -> Unit)

    /** 收到一筆價格（在 socket 的執行緒上呼叫） */
    fun publish(tick: Int)
}

/** 記下兩個畫面各自收到什麼（兩個版本共用） */
private class L03Board {
    private var listGotLastAt: Double? = null
    private val detailTicks = mutableListOf<Int>()

    @Synchronized
    fun listGot(tick: Int) {
        if (tick == L03_TICK_COUNT) listGotLastAt = d02NowMs()
    }

    @Synchronized
    fun detailDone(tick: Int) {
        detailTicks += tick
    }

    @Synchronized
    fun snapshot(): Pair<Double?, List<Int>> = listGotLastAt to detailTicks.toList()
}

/** 演練流程（兩個版本共用）；[makeDispatcher] 會拿到一個消費者協程可以放的範圍，演練結束時一起取消 */
private suspend fun scenario(log: LabLog, makeDispatcher: (CoroutineScope) -> L03Dispatcher) = withContext(Dispatchers.Default) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val socket = Executors.newSingleThreadExecutor { Thread(it, "socket").apply { isDaemon = true } }
    try {
        val dispatcher = makeDispatcher(scope)
        val board = L03Board()
        dispatcher.register("行情列表") { tick -> board.listGot(tick) }
        dispatcher.register("訂單簿詳細頁") { tick ->
            Thread.sleep(L03_SLOW_COST_MS)                                  // 算 20 檔、格式化：40 毫秒
            board.detailDone(tick)
        }

        val start = d02NowMs()
        for (tick in 1..L03_TICK_COUNT) {
            socket.execute { dispatcher.publish(tick) }                     // 一瞬間來了 8 筆
        }
        log("一瞬間來了 $L03_TICK_COUNT 筆價格（#1 到 #$L03_TICK_COUNT），交給分派器")

        val deadline = start + 2000                                         // 最多等 2 秒
        while (d02NowMs() < deadline) {
            val (listAt, detail) = board.snapshot()
            if (listAt != null && detail.lastOrNull() == L03_TICK_COUNT) break
            delay(5)
        }

        val (listAt, detailTicks) = board.snapshot()
        if (listAt == null) {
            log("行情列表沒有收到最後一筆（#$L03_TICK_COUNT）：register 與 publish 還沒寫完")
            log("最後：行情列表沒有收到最後一筆")
            return@withContext
        }
        val lag = (listAt - start).toInt()
        log("行情列表收到最後一筆（#$L03_TICK_COUNT）：距離這批價格送到 $lag 毫秒")
        log("訂單簿詳細頁處理了 ${detailTicks.size} 筆：${detailTicks.joinToString(" ") { "#$it" }}")
        val listText = when {
            lag >= 150 -> "被拖慢了（最後一筆晚了 150 毫秒以上）"
            lag < 100 -> "不受影響（最後一筆在 100 毫秒內收到）"
            else -> "有點慢（最後一筆晚了 $lag 毫秒）"
        }
        val detailText = if (detailTicks.size == L03_TICK_COUNT) "把 $L03_TICK_COUNT 筆全部依序處理" else "只處理最新的幾筆（少於 $L03_TICK_COUNT 筆）"
        log("最後：行情列表$listText；詳細頁$detailText，最後處理的是 #${detailTicks.lastOrNull() ?: 0}")
    } finally {
        scope.cancel()
        socket.shutdownNow()
    }
}

// MARK: - Bug 版
private object L03Bug {
    class Hub : L03Dispatcher {
        private val handlers = mutableListOf<(Int) -> Unit>()

        override fun register(name: String, handler: (Int) -> Unit) {
            // TODO ①-1：把 handler 存進 handlers
        }

        override fun publish(tick: Int) {
            // TODO ①-2：依序呼叫 handlers 裡的每個 handler(tick)，就直接在這個函式裡呼叫（不要另外開執行緒或協程）
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Hub() }
}

// MARK: - 修正版
private object L03Fixed {
    class Hub(private val scope: CoroutineScope) : L03Dispatcher {
        private val inboxes = mutableListOf<Channel<Int>>()

        override fun register(name: String, handler: (Int) -> Unit) {
            // TODO ②-1：替這個畫面建立自己的緩衝區：Channel<Int>(Channel.CONFLATED)，存進 inboxes；
            //           再用 scope.launch 開一個消費者協程，for (tick in inbox) 讀自己的 Channel，每一筆呼叫 handler
        }

        override fun publish(tick: Int) {
            // TODO ②-2：對 inboxes 裡的每個 Channel 呼叫 trySend(tick)。只丟進緩衝區，不要在這裡呼叫 handler
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { Hub(it) }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "開著訂單簿詳細頁，行情列表跟著變慢",
    question = "第 5 題：每秒很多則訊息，畫面怎麼保持順暢？",
    goal = "分派器不能在 socket 的執行緒上直接呼叫每個畫面的處理函式：一個慢的畫面會拖住所有畫面。每個畫面要有自己的緩衝區（只留最新一筆）和自己的消費者，分派器只負責把價格丟進去。先寫出同步呼叫的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/design/design02-market-data/L03_SlowSubscriber.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看行情列表收到最後一筆（#8）距離這批價格送到幾毫秒，以及詳細頁處理了幾筆。",
        "想一想：行情列表的處理明明很快，為什麼會被詳細頁拖慢？（在同一條執行緒上一個接一個呼叫。）",
        "寫 TODO ②（修正版），選「修正版」執行：行情列表幾乎馬上收到 #8；詳細頁只處理最新的幾筆，最後一筆仍然是 #8。",
        "想一想：詳細頁少處理了幾筆，使用者會看到錯的資料嗎？（行情只在乎最新的價格；成交明細這類不能丟的資料不能這樣做，見 Advanced05 第 4 題。）",
    ),
    expectBug = "行情列表最後一筆 #8 晚了約 300 毫秒才收到（超過 150 毫秒）；詳細頁把 8 筆全部依序處理：「最後：行情列表被拖慢了（最後一筆晚了 150 毫秒以上）；詳細頁把 8 筆全部依序處理，最後處理的是 #8」。",
    expectFixed = "行情列表幾乎馬上（0 到幾毫秒）收到 #8；詳細頁只處理最新的幾筆（通常是 #1 和 #8 共 2 筆），最後處理的仍是 #8：「最後：行情列表不受影響（最後一筆在 100 毫秒內收到）；詳細頁只處理最新的幾筆（少於 8 筆），最後處理的是 #8」。",
    file = "design02-market-data/L03_SlowSubscriber.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeDispatcher) 執行"
            last == "最後：行情列表沒有收到最後一筆" -> "行情列表沒有收到價格：register 要把 handler 存起來，publish 要依序呼叫每個 handler"
            last != "最後：行情列表被拖慢了（最後一筆晚了 150 毫秒以上）；詳細頁把 8 筆全部依序處理，最後處理的是 #8" ->
                "Bug 還沒重現：publish 要在 socket 的執行緒上直接 for (handler in handlers) handler(tick)，慢的詳細頁才會拖住行情列表"
            else -> null
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeDispatcher) 執行"
            last == "最後：行情列表沒有收到最後一筆" -> "行情列表沒有收到價格：register 要建立 Channel 與消費者協程，publish 要 trySend 給每個畫面"
            !last.startsWith("最後：行情列表不受影響（最後一筆在 100 毫秒內收到）") ->
                "行情列表還是被拖慢：publish 不能直接呼叫 handler，要 trySend 進每個畫面各自的 Channel（Channel.CONFLATED）"
            !(last.endsWith("最後處理的是 #8") && "只處理最新的幾筆" in last) -> "每個畫面自己的緩衝區要用 Channel.CONFLATED：慢的詳細頁只處理最新的幾筆，最後一筆是 #8"
            else -> null
        }
    },
)
