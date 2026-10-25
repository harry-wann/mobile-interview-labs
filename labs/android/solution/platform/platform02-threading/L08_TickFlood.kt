// L08：行情每秒幾百筆，每一筆都丟回主執行緒，畫面被淹沒（背景合併，固定頻率更新畫面）
// 對應複習站：Platform02Threading 第 6 題「行情每秒推幾百筆，執行緒要怎麼設計？」
//
// 情境：行情頁收到 WebSocket 推來的報價。演練送出 300 筆報價（6 檔幣種輪流，約每 2 ms 一筆），看畫面被更新了幾次，以及最後畫面上的價格對不對。
//        要在手機上執行（畫面更新要用 Android 的主執行緒 Dispatchers.Main）。
// Bug 版：每收到一筆就 withContext／launch 到 Dispatchers.Main 更新一次畫面。300 筆就更新 300 次，主執行緒的佇列被塞滿，觸控排在後面。
// 修正版：收到的報價先存進 ConcurrentHashMap（每檔只留最新一筆），再用固定頻率（每 200 ms）取一次整份快照，在主執行緒更新畫面一次。
package tw.harry.labs.solution.platform02threading

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L08Tick(val symbol: String, val price: Int)

/** 畫面（兩個版本共用）：記下畫面更新了幾次、現在顯示什麼。只能在主執行緒呼叫 */
private class L08Screen {
    var updates = 0
    val shown = mutableMapOf<String, Int>()

    fun render(prices: Map<String, Int>) {
        updates++
        shown.putAll(prices)
    }
}

/** 收到報價之後要做的事（兩個版本各自實作） */
private interface L08Pipeline {
    fun receive(tick: L08Tick)             // 在背景執行緒被呼叫（WebSocket 收到一筆）
    suspend fun finish()                   // 演練結束前，等還沒處理完的更新都處理完
}

private val symbols = listOf("BTC", "ETH", "XRP", "LTC", "ADA", "DOT")

/** 演練流程（兩個版本共用）：背景約每 2 ms 送一筆報價，共 300 筆 */
private suspend fun scenario(log: LabLog, makePipeline: (L08Screen) -> L08Pipeline) {
    val screen = L08Screen()
    val pipeline = makePipeline(screen)
    withContext(Dispatchers.Default) {
        for (i in 0 until 300) {
            pipeline.receive(L08Tick(symbols[i % symbols.size], 1_000 + i))
            delay(2)
        }
    }
    val lastPrice = (0 until 300).associate { symbols[it % symbols.size] to 1_000 + it }
    pipeline.finish()
    log("收到 300 筆報價，畫面更新了 ${screen.updates} 次")
    log("最後畫面上的價格${if (screen.shown == lastPrice) "和每檔最後一筆一致" else "和每檔最後一筆不一致"}")
}

// MARK: - Bug 版
private object L08Bug {
    class DirectPipeline(private val screen: L08Screen) : L08Pipeline {
        private val uiScope = CoroutineScope(Dispatchers.Main)

        override fun receive(tick: L08Tick) {
            uiScope.launch { screen.render(mapOf(tick.symbol to tick.price)) }     // 每一筆都排進主執行緒，更新一次畫面
        }

        override suspend fun finish() {
            delay(300)
            uiScope.cancel()
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { DirectPipeline(it) }
}

// MARK: - 修正版
private object L08Fixed {
    class ConflatingPipeline(screen: L08Screen) : L08Pipeline {
        private val latest = ConcurrentHashMap<String, Int>()          // 每檔只保留最新一筆
        private val uiScope = CoroutineScope(Dispatchers.Main)
        private val refresher: Job = uiScope.launch {
            flow<Map<String, Int>> {
                while (true) {
                    delay(200)                                         // 固定頻率
                    emit(latest.toMap())                               // 整份快照
                }
            }.flowOn(Dispatchers.Default)
                .collect { screen.render(it) }                         // 主執行緒：更新畫面一次
        }

        override fun receive(tick: L08Tick) {
            latest[tick.symbol] = tick.price                           // 背景合併：只更新字典，不碰主執行緒
        }

        override suspend fun finish() {
            delay(250)                                                 // 讓最後一次更新跑完
            refresher.cancel()
            uiScope.cancel()
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { ConflatingPipeline(it) }
}

// MARK: - 演練設定
private fun updateCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("收到 300 筆報價，畫面更新了 ") }
        ?.removePrefix("收到 300 筆報價，畫面更新了 ")?.removeSuffix(" 次")?.toIntOrNull()

val l08 = Lab(
    id = "L08",
    title = "每一筆報價都更新畫面，主執行緒被淹沒",
    question = "第 6 題：行情每秒推幾百筆，執行緒要怎麼設計？",
    goal = "報價在背景合併（每檔只留最新一筆），再用固定頻率把整份快照交給主執行緒。畫面更新的次數要遠少於報價的筆數，而且最後的價格要是對的。",
    steps = listOf(
        "選「Bug 版」按執行，看「畫面更新了幾次」：和收到的報價筆數比一比。",
        "選「修正版」執行：畫面只更新幾次？最後畫面上的價格是不是每檔的最後一筆？",
        "想一想：每 200 ms 才更新一次，中間被覆蓋的價格會不會漏掉最高價、最低價？（畫面顯示可以丟，K 線的高低點要在合併之前的資料層累計）",
    ),
    expectBug = "收到 300 筆報價，畫面更新了 300 次；最後價格正確。",
    expectFixed = "收到 300 筆報價，畫面只更新約 3 到 5 次（每 200 ms 一次）；最後價格和每檔最後一筆一致。",
    file = "platform02-threading/L08_TickFlood.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = updateCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n < 250 -> "Bug 還沒重現（畫面只更新 $n 次）：receive 要對每一筆報價都切到 Dispatchers.Main 更新一次畫面"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = updateCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n == 0 -> "畫面一次都沒有更新：每 200 ms 要取一次快照，呼叫 screen.render"
            n > 12 -> "畫面更新了 $n 次，太多了：receive 只存進字典，每 200 ms 才取一次快照更新畫面"
            !lines.contains("最後畫面上的價格和每檔最後一筆一致") -> "最後畫面上的價格不對：每一檔都要保留最新的一筆，快照要包含全部 6 檔"
            else -> null
        }
    },
)
