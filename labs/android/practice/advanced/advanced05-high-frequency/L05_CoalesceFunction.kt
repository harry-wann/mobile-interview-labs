// L05：行情列表每次更新，各列的順序一直亂跳；
// 資料量一大還很慢（「同一個交易對只留最新一筆」的合併函式）
// 對應複習站：Advanced05HighFrequency 第 6 題「「同一個交易對只保留最新一筆」
// 的合併函式怎麼寫？」（面試現場寫程式常考）
//
// 情境：輸入一串更新，同一個交易對只留最後一筆，
// 輸出順序依該交易對「第一次出現」的順序。例如 [A1, B1, A2] 要變成 [A2, B1]。
//        演練用 4 組不同順序的資料檢查結果與順序，再用 1 萬筆更新、500
// 個交易對，數「比較兩個交易對相不相等（equals）」做了幾次，量演算法的成本。
//
// 你要寫：
//   ① Bug 版 coalesce：用 HashMap<S, A05Tick<S>> 存每個交易對的最新值，回傳
// latest.values.toList()。值是對的，順序是依雜湊值排的，不是依第一次出現。
//   ② 修正版 coalesce：改用 LinkedHashMap（記住 key 第一次插入的順序，
// 覆蓋舊值時順序不變），回傳 latest.values.toList()。只掃一次，不能用巢狀迴圈。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced05-high-frequency/L05_CoalesceFunction.kt
package tw.harry.labs.practice.advanced05highfrequency

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 會數「equals 被呼叫幾次」的交易對型別：
 * 用來量演算法做了多少次比較（兩個版本共用）
 */
private class CountedSymbol(val name: String) {
    override fun equals(other: Any?): Boolean {
        comparisons++
        return other is CountedSymbol && other.name == name
    }

    override fun hashCode(): Int = name.hashCode()

    companion object {
        var comparisons = 0
    }
}

/**
 * 合併函式：Bug 版與修正版各自實作（泛型：交易對可以是 String，
 * 也可以是會數比較次數的型別）
 */
private interface Coalescer {
    fun <S> coalesce(updates: List<A05Tick<S>>): List<A05Tick<S>>
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, coalescer: Coalescer) {
    val symbols = listOf("XRP", "BTC", "ETH", "DOGE", "ADA", "SOL")
    var latestOk = 0
    var orderOk = 0
    for (k in 0 until 4) {
        // 第 k 組：換一個第一次出現的順序
        val order = symbols.drop(k) + symbols.take(k)
        // 每個交易對先出現一次（價格 1），再用相反的順序各更新一次（價格 2）
        val updates =
            order.map { A05Tick(it, 1) } +
                order.reversed().map { A05Tick(it, 2) }
        val result = coalescer.coalesce(updates)
        if (result.size == order.size &&
            result.all { it.price == 2 }
        ) {
            latestOk++
        }
        if (result.map { it.symbol } == order) orderOk++
    }
    log("合併後每個交易對都是最新價的組數：$latestOk／4")
    log("順序和第一次出現的順序一致的組數：$orderOk／4")

    val big = List(10_000) { A05Tick(CountedSymbol("S${it % 500}"), it) }
    CountedSymbol.comparisons = 0
    val merged = coalescer.coalesce(big)
    log(
        "1 萬筆更新、500 個交易對：合併成 ${merged.size} 筆，比較交易對 ${CountedSymbol.comparisons} 次",
    )
}

// MARK: - Bug 版
private object L05Bug {
    class MapOnly : Coalescer {
        override fun <S> coalesce(updates: List<A05Tick<S>>): List<A05Tick<S>> {
            // TODO ①：val latest = HashMap<S, A05Tick<S>>()；每一筆
            // latest[update.symbol] = update；回傳 latest.values.toList()
            return emptyList()
        }
    }

    fun run(log: LabLog) = scenario(log, MapOnly())
}

// MARK: - 修正版
private object L05Fixed {
    class InsertionOrdered : Coalescer {
        override fun <S> coalesce(updates: List<A05Tick<S>>): List<A05Tick<S>> {
            // TODO ②：val latest = LinkedHashMap<S, A05Tick<S>>()（覆蓋既有的
            // key，不會改變它第一次插入的位置）；
            //        每一筆 latest[update.symbol] = update；回傳 latest.values.
            // toList()
            return emptyList()
        }
    }

    fun run(log: LabLog) = scenario(log, InsertionOrdered())
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "行情列表每次更新，各列的順序一直亂跳",
    question = "第 6 題：「同一個交易對只保留最新一筆」的合併函式怎麼寫？",
    goal = "寫出同一個交易對只留最新一筆、輸出依第一次出現順序、只掃一次（O(n)）的合併函式。先寫出順序會亂的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced05-high-frequency/L05_CoalesceFunction.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看第二行：順序一致的組數是 0／4（HashMap 依雜湊值排，不是依第一次出現）；第一行的值都是對的。",
        "寫 TODO ②，選「修正版」執行：兩行都是 4／4，第三行比較次數約 1 萬次。",
        "想一想：如果改用「每一筆更新都從頭找有沒有同一個交易對」的巢狀迴圈，第三行會變成多少？為什麼面試官會問時間複雜度？（見這個單元的第 6 題）",
        "想一想：iOS 的 Swift 為什麼要另外用一個陣列記順序？（Swift 的 Dictionary 沒有固定順序，也沒有 LinkedHashMap 這種記住插入順序的字典，見 iOS 版的解答）",
    ),
    expectBug = "合併後每個交易對都是最新價的組數：4／4；順序和第一次出現的順序一致的組數：0／4（或 1／4，剛好有一組湊巧）；比較約 1 萬次。",
    expectFixed = "兩個組數都是 4／4；1 萬筆更新、500 個交易對：合併成 500 筆，比較交易對約 1 萬次（巢狀迴圈的寫法會超過 100 萬次）。",
    file = "advanced05-high-frequency/L05_CoalesceFunction.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val latest = a05Number(lines, "合併後每個交易對都是最新價的組數：")
        val order = a05Number(lines, "順序和第一次出現的順序一致的組數：")
        when {
            latest == null || order == null -> "演練沒有跑完"
            latest < 4 -> "值要是對的：同一個交易對留最後一筆（latest[symbol] = update）"
            order >= 4 -> "Bug 還沒重現：只用 HashMap、直接取 values，順序就不會固定"
            else -> null
        }
    },
    checkFixed = { lines ->
        val latest = a05Number(lines, "合併後每個交易對都是最新價的組數：")
        val order = a05Number(lines, "順序和第一次出現的順序一致的組數：")
        val compares = a05Number(lines, "1 萬筆更新、500 個交易對：合併成 500 筆，比較交易對 ")
        when {
            latest == null || order == null || compares == null -> "演練沒有跑完（合併 1 萬筆更新要得到 500 筆）"
            latest < 4 -> "每個交易對要留最後一筆（價格 2）"
            order < 4 -> "順序要依第一次出現的順序：改用 LinkedHashMap，覆蓋 key 不會改變它第一次插入的位置"
            compares >= 100_000 -> "比較了 ${compares.toInt()} 次，太多了：只能掃一次，用 Map 找，不要每一筆都從頭找（O(n²)）"
            else -> null
        }
    },
)
