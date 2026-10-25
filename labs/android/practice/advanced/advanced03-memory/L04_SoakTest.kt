// L04：行情畫面開了 6 個小時，App 越來越慢，最後被系統殺掉（沒有上限的累積，用 soak test 看記憶體曲線）
// 對應複習站：Advanced03Memory 第 6 題「行情畫面開了幾個小時，App 變慢然後被殺，會是什麼原因？」（第 5 題的找法也用在這裡）
//
// 情境：行情畫面每秒收到一筆成交（Tick），開著 6 個小時就是 21,600 筆。每一筆都連同伺服器傳來的原始訊息（約 1 KB，除錯用）一起留著。
//        演練把 6 個小時壓縮成一個迴圈，每「過一小時」記一次保留的筆數與 Java heap 用量（GC 之後量）：這就是 soak test
//        （長時間跑同一個操作，看記憶體會不會一直增加）。
//
// 你要寫：
//   ① Bug 版 UnboundedHistory.append：每一筆都加進 ticks，永遠不丟。
//   ② 修正版 BoundedHistory.append：加進 ticks 之後，超過 capacity（500）就 removeFirst，丟掉最舊的。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced03-memory/L04_SoakTest.kt
package tw.harry.labs.practice.advanced03memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val TICKS_PER_HOUR = 3_600
private const val HOURS = 6

/** 一筆成交：交易對、價格，和伺服器傳來的原始訊息（約 1 KB）（兩個版本共用）。
 *  沒有用 2 KB：模擬器（API 29）每個 App 的 heap 上限只有 48 MB，2 KB 的 Bug 版到第 6 小時就 OutOfMemoryError，App 直接被殺掉 */
private class Tick(val symbol: String, val price: Int, val raw: ByteArray)

/** 行情歷史的共同介面（兩個版本共用） */
private interface TickHistory {
    fun append(tick: Tick)
    val count: Int
}

/** 演練流程（兩個版本共用）：模擬 6 小時，每小時記一次保留幾筆、記憶體多了多少 */
private suspend fun scenario(log: LabLog, history: TickHistory) = withContext(Dispatchers.Default) {
    val start = a03UsedHeapMB()
    var n = 0
    for (hour in 1..HOURS) {
        repeat(TICKS_PER_HOUR) {
            n++
            history.append(Tick("BTC/JPY", 15_000_000 + n % 1_000, ByteArray(1_024) { (n % 251).toByte() }))
        }
        log("第 $hour 小時結束：保留 ${history.count} 筆，記憶體比開始時多 ${(a03UsedHeapMB() - start).toInt()} MB")
    }
    log("6 小時共收到 $n 筆，最後保留 ${history.count} 筆")
}

// MARK: - Bug 版
private object L04Bug {
    class UnboundedHistory : TickHistory {
        private val ticks = ArrayList<Tick>()

        override fun append(tick: Tick) {
            // TODO ①：ticks.add(tick)：只加不減，沒有任何地方把舊的丟掉
        }

        override val count get() = ticks.size
    }

    suspend fun run(log: LabLog) = scenario(log, UnboundedHistory())
}

// MARK: - 修正版
private object L04Fixed {
    class BoundedHistory : TickHistory {
        private val capacity = 500
        private val ticks = ArrayDeque<Tick>()

        override fun append(tick: Tick) {
            // TODO ②：ticks.addLast(tick)；超過 capacity 就 ticks.removeFirst()，丟掉最舊的（用 while，直到不超過）
        }

        override val count get() = ticks.size
    }

    suspend fun run(log: LabLog) = scenario(log, BoundedHistory())
}

// MARK: - 演練設定
/** 第 6 小時結束時，記憶體比開始時多幾 MB */
private fun lastHourMB(lines: List<String>): Double? {
    val line = lines.firstOrNull { it.startsWith("第 6 小時結束：") } ?: return null
    return line.substringAfter("多 ", "").takeWhile { it.isDigit() || it == '-' }.toDoubleOrNull()
}

val l04 = Lab(
    id = "L04",
    title = "行情畫面開 6 小時，App 越來越慢，最後被系統殺掉",
    question = "第 6 題：行情畫面開了幾個小時，App 變慢然後被殺，會是什麼原因？",
    goal = "所有 buffer、歷史資料都要有明確的上限。用 soak test（長時間重複同一個操作，看記憶體曲線）確認記憶體不會一直長。先寫出只加不減的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced03-memory/L04_SoakTest.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看每小時一行的 log：保留的筆數和記憶體一路往上，沒有盡頭。",
        "寫 TODO ②，選「修正版」執行：保留的筆數固定是 500，記憶體幾乎不變。",
        "進階：用第 5 題的工具找出是誰在長。真的 App 用 Android Studio 的 Memory Profiler 錄製，曲線一路往上就抓一份 heap dump，依 class 排序看哪一類物件最多；debug 版有 LeakCanary 的話，只能抓「被意外抓著的畫面物件」，這種「不斷累積的資料」要靠 heap dump 才看得到。",
        "想一想：除了加上限，onTrimMemory 或進到背景時還能再放掉什麼？（見這個單元的第 1、6 題）",
    ),
    expectBug = "第 1 小時保留 3600 筆、記憶體多約 4 MB；第 6 小時保留 21600 筆、多約 22–25 MB，一路往上沒有盡頭。",
    expectFixed = "每個小時結束都是「保留 500 筆」，記憶體幾乎不變；6 小時共收到 21600 筆，最後保留 500 筆。",
    file = "advanced03-memory/L04_SoakTest.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val kept = a03Number(lines, "6 小時共收到 21600 筆，最後保留 ")
        val mb = lastHourMB(lines)
        when {
            kept == null || mb == null -> "演練沒有跑完"
            kept < 21_600 -> "最後只保留 ${kept.toInt()} 筆：Bug 版什麼都不丟，要全部留著（21600 筆）"
            mb < 15 -> "記憶體只多了 ${mb.toInt()} MB：每筆 Tick 都要連同 raw（約 1 KB）一起留著"
            else -> null
        }
    },
    checkFixed = { lines ->
        val kept = a03Number(lines, "6 小時共收到 21600 筆，最後保留 ")
        val mb = lastHourMB(lines)
        when {
            kept == null || mb == null -> "演練沒有跑完"
            kept > 500 -> "最後保留了 ${kept.toInt()} 筆，沒有上限：addLast 之後超過 500 筆就要 removeFirst，丟掉最舊的"
            kept < 500 -> "最後只保留 ${kept.toInt()} 筆：上限是 500 筆，不是更少"
            mb > 8 -> "記憶體多了 ${mb.toInt()} MB，還是太多：丟掉舊的 Tick 時，它的 raw 資料才會跟著釋放"
            else -> null
        }
    },
)
