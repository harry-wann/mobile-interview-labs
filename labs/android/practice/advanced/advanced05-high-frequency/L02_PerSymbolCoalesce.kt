// L02：3 個交易對各更新兩次，畫面上只有一個交易對有價格（conflate 是對整條串流，不是對每個交易對）
// 對應複習站：Advanced05HighFrequency 第 3 題「背壓是什麼？有哪些處理策略？」
//
// 情境：行情串流混著 3 個交易對（BTC、ETH、XRP）的報價，一瞬間來了 6 筆：BTC1、ETH1、XRP1、BTC2、ETH2、XRP2（數字是第幾次更新）。
//        畫面處理一筆要 20 毫秒，比來得慢。畫面應該顯示三個交易對各自最新的價格（BTC2、ETH2、XRP2）。
//
// 你要寫：
//   ① Bug 版：在混著多個交易對的 flow 上加 .conflate()：整條串流只處理最新的一筆，其他交易對的更新被擠掉。
//   ② 修正版 QuoteBuffer：用 HashMap（交易對 → 最新一筆）當緩衝區。put 同一個交易對後來的蓋掉先來的；drain 取走全部的值，換一個新的空 HashMap。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced05-high-frequency/L02_PerSymbolCoalesce.kt
package tw.harry.labs.practice.advanced05highfrequency

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Quote(val symbol: String, val update: Int)

private val burst = listOf("BTC" to 1, "ETH" to 1, "XRP" to 1, "BTC" to 2, "ETH" to 2, "XRP" to 2).map { Quote(it.first, it.second) }

/** 畫面上目前每個交易對顯示的是第幾次更新（兩個版本共用） */
private class Board {
    private val shown = ConcurrentHashMap<String, Int>()

    fun show(quote: Quote) {
        shown[quote.symbol] = quote.update
    }

    fun summary(): String = listOf("BTC", "ETH", "XRP").joinToString("、") { "$it：${shown[it]?.let { n -> "第 $n 次" } ?: "沒有"}" }
}

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) {
        val board = Board()
        flow { for (quote in burst) emit(quote) }                  // 一瞬間來了 6 筆
            // TODO ①：在這裡加 .conflate()：下游慢的時候，只留整條串流最新的一筆
            .collect { quote ->
                board.show(quote)
                delay(20)                                          // 畫面處理一筆要 20 毫秒
            }
        log("畫面上各交易對顯示的更新：${board.summary()}")
    }
}

// MARK: - 修正版
private object L02Fixed {
    /** 緩衝區：每個交易對只留最新一筆。背景寫入、畫面取走 */
    class QuoteBuffer {
        private val lock = Any()
        private var pending = HashMap<String, Quote>()

        fun put(quote: Quote) {
            // TODO ②-1：synchronized(lock) { pending[quote.symbol] = quote }：同一個交易對，後來的蓋掉先來的
        }

        fun drain(): List<Quote> {
            // TODO ②-2：synchronized(lock) 裡：batch = pending.values.toList()；pending = HashMap()（換一個新的空字典）；回傳 batch
            return emptyList()
        }
    }

    suspend fun run(log: LabLog) {
        val board = Board()
        val buffer = QuoteBuffer()
        for (quote in burst) buffer.put(quote)                     // 一瞬間來了 6 筆
        for (quote in buffer.drain()) {                            // 畫面取走：3 個交易對各一筆最新的
            board.show(quote)
            delay(20)
        }
        log("畫面上各交易對顯示的更新：${board.summary()}")
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "3 個交易對各更新兩次，畫面上只有一個有價格",
    question = "第 3 題：背壓是什麼？有哪些處理策略？",
    goal = "合併要「依交易對」做：每個交易對只留最新的一筆。對整條混著多個交易對的串流用 conflate 或 sample，其他交易對的更新會被擠掉。先寫出整條串流 conflate 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced05-high-frequency/L02_PerSymbolCoalesce.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看最後一行：XRP 是第 2 次，ETH 從頭到尾沒有出現，BTC 停在舊價或根本沒出現。",
        "寫 TODO ②，選「修正版」執行：BTC、ETH、XRP 都是第 2 次。",
        "想一想：Flow 的 `sample`、Combine 的 `throttle(latest: true)` 對混著多個交易對的串流會有同樣的問題嗎？（會，它們也是對整條串流取最新一筆，見這個單元的第 3 題）",
    ),
    expectBug = "畫面上各交易對顯示的更新：BTC：沒有（或停在第 1 次）、ETH：沒有、XRP：第 2 次。只有最後一筆 XRP 的第 2 次到得了畫面，ETH 一定沒有，BTC 最新的第 2 次一定沒有。",
    expectFixed = "畫面上各交易對顯示的更新：BTC：第 2 次、ETH：第 2 次、XRP：第 2 次。",
    file = "advanced05-high-frequency/L02_PerSymbolCoalesce.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull()
        when {
            last == null || !last.startsWith("畫面上各交易對顯示的更新：") -> "演練沒有跑完"
            "沒有" in last -> null
            else -> "Bug 還沒重現：在混著多個交易對的串流上 conflate()，其中會有交易對從頭到尾沒有出現在畫面上"
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull()
        when {
            last == null || !last.startsWith("畫面上各交易對顯示的更新：") -> "演練沒有跑完"
            last == "畫面上各交易對顯示的更新：BTC：第 2 次、ETH：第 2 次、XRP：第 2 次" -> null
            else -> "每個交易對都要顯示最新的一次（第 2 次）：用字典依交易對分組，drain 時取走全部。目前是：$last"
        }
    },
)
