// L08：多了一檔最佳買價，整張板的每一列都重畫（比較新舊兩版時用「位置」
// 而不是「價格」）
// 對應複習站：Exchange05OrderBook 第 6 題「板更新得很頻繁，
// 畫面怎麼更新才不會卡？」
//
// 情境：買方顯示最好的 5 檔。新的一版比舊的多了一個最佳買價 10,005,000，
// 原本最後一檔 9,980,000 被擠出畫面；
//       另外 9,995,000 的數量從 0.2 變成 0.5，其他列的價格與數量都沒變。
//       該更新的只有 3 列：新增 10,005,000、移除 9,980,000、9,995,000
// 數量改變。
//
// 你要寫：
//   ① Bug 版（L08Bug.diff）：用位置對應：第 index 列的新舊價格或數量不同，
// 就算「數量改變」；超出舊版長度的算「新增」。
//   ② 修正版（L08Fixed.diff）：用價格當識別：舊版沒有的價格是 added，
// 新版沒有的是 removed，兩邊都有但數量不同的是 changed。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange05-order-book/L08_DiffByPrice.kt
package tw.harry.labs.practice.exchange05orderbook

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L08Diff(
    val added: MutableList<BigDecimal> = mutableListOf(),
    val removed: MutableList<BigDecimal> = mutableListOf(),
    val changed: MutableList<BigDecimal> = mutableListOf(),
) {
    val updateCount: Int get() = added.size + removed.size + changed.size
}

private val oldBook = listOf(
    E05Level("10000000", "0.4"),
    E05Level("9995000", "0.2"),
    E05Level("9990000", "0.6"),
    E05Level("9985000", "0.3"),
    E05Level("9980000", "0.1"),
)
private val newBook = listOf(
    E05Level("10005000", "0.1"),
    E05Level("10000000", "0.4"),
    E05Level("9995000", "0.5"),
    E05Level("9990000", "0.6"),
    E05Level("9985000", "0.3"),
)

private fun texts(prices: List<BigDecimal>) =
    prices.joinToString(", ", "[", "]") {
        e05Text(it)
    }

/** 演練流程（兩個版本共用）：比較新舊兩版，列出要更新的列 */
private fun scenario(
    log: LabLog,
    diff: (List<E05Level>, List<E05Level>) -> L08Diff,
) {
    val result = diff(oldBook, newBook)
    log(
        "新增 ${texts(
            result.added,
        )}，移除 ${texts(
            result.removed,
        )}，數量改變 ${texts(result.changed)}（共要更新 ${result.updateCount} 列）",
    )
    log(
        "最後：共要更新 ${result.updateCount} 列（該更新的只有 3 列：新增 10005000、移除 9980000、9995000 數量改變）",
    )
}

// MARK: - Bug 版
private object L08Bug {
    fun diff(old: List<E05Level>, new: List<E05Level>): L08Diff {
        val result = L08Diff()
        // TODO ①：用 new.forEachIndexed 逐列比：index >= old.size 的 level.
        // price 放進 result.added；
        //        否則 old[index] 的 price 或 qty 和 level 不同（用 compareTo
        // 比），就把 level.price 放進 result.changed（用位置對應，不查價格）
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::diff)
}

// MARK: - 修正版
private object L08Fixed {
    fun diff(old: List<E05Level>, new: List<E05Level>): L08Diff {
        // TODO ②：先把 old、new 各做成「價格 → 數量」的 Map（價格先
        // stripTrailingZeros()，數量比大小用 compareTo），再算出：
        //   added = new 裡有、old 沒有的價格；removed = old 裡有、new
        // 沒有的價格；changed = 兩邊都有但數量不同的價格
        return L08Diff()
    }

    suspend fun run(log: LabLog) = scenario(log, ::diff)
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "多一檔最佳買價，整張板都重畫",
    question = "第 6 題：板更新得很頻繁，畫面怎麼更新才不會卡？",
    goal = "比較新舊兩版板，要用價格當識別，只更新新增、移除、數量改變的那幾列；用位置對應，只要中間多一檔，後面每一列都會被判斷成有變動。先寫出用位置比較的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange05-order-book/L08_DiffByPrice.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「共要更新幾列」。新舊兩版其實只差 3 列。",
        "想一想：為什麼新增一檔最佳買價，連沒變的 10,000,000、9,990,000 都被判斷成有變動？",
        "寫 TODO ②（修正版），選「修正版」執行，要看到新增 [10005000]，移除 [9980000]，數量改變 [9995000]，共 3 列。",
        "進階：Android 的 DiffUtil 用哪個方法判斷「同一列」？（areItemsTheSame，這裡是價格，不要包含數量）iOS 的 diffable data source 對應項目的識別。",
    ),
    expectBug = "新增 []，移除 []，數量改變 [10005000, 10000000, 9995000, 9990000, 9985000]，「共要更新 5 列」。",
    expectFixed = "新增 [10005000]，移除 [9980000]，數量改變 [9995000]，「共要更新 3 列」。",
    file = "exchange05-order-book/L08_DiffByPrice.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, diff) 執行"

            lines.none {
                it.startsWith("最後：共要更新 5 列")
            } -> "Bug 還沒重現：用位置比較（old[index] 和 new[index]），新增一檔之後 5 列都會被判斷成有變動"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, diff) 執行"
            }

            "新增 [10005000]，移除 [9980000]，數量改變 [9995000]（共要更新 3 列）" !in lines -> {
                "用價格當識別：舊版沒有的價格是新增，新版沒有的是移除，兩邊都有但數量不同的才是數量改變"
            }

            else -> {
                null
            }
        }
    },
)
