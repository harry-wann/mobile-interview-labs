// L08：多了一檔最佳買價，整張板的每一列都重畫（比較新舊兩版時用「位置」而不是「價格」）
// 對應複習站：Exchange05OrderBook 第 6 題「板更新得很頻繁，畫面怎麼更新才不會卡？」
//
// 情境：買方顯示最好的 5 檔。新的一版比舊的多了一個最佳買價 10,005,000，原本最後一檔 9,980,000 被擠出畫面；
//       另外 9,995,000 的數量從 0.2 變成 0.5，其他列的價格與數量都沒變。
//       該更新的只有 3 列：新增 10,005,000、移除 9,980,000、9,995,000 數量改變。
// Bug 版：比較新舊兩版時，用「第幾列」對應：第 0 列和第 0 列比，第 1 列和第 1 列比…。新增一檔之後，所有的列都往下移一格，
//        每一列都和另一個價格比，全部被判斷成「有變動」，5 列都要更新。
// 修正版：用價格當識別（DiffUtil 的 areItemsTheSame）：舊版沒有的價格是新增，新版沒有的價格是移除，兩邊都有但數量不同的才是數量改變。
package tw.harry.labs.solution.exchange05orderbook

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
    E05Level("10000000", "0.4"), E05Level("9995000", "0.2"), E05Level("9990000", "0.6"),
    E05Level("9985000", "0.3"), E05Level("9980000", "0.1"),
)
private val newBook = listOf(
    E05Level("10005000", "0.1"), E05Level("10000000", "0.4"), E05Level("9995000", "0.5"),
    E05Level("9990000", "0.6"), E05Level("9985000", "0.3"),
)

private fun texts(prices: List<BigDecimal>) = prices.joinToString(", ", "[", "]") { e05Text(it) }

/** 演練流程（兩個版本共用）：比較新舊兩版，列出要更新的列 */
private fun scenario(log: LabLog, diff: (List<E05Level>, List<E05Level>) -> L08Diff) {
    val result = diff(oldBook, newBook)
    log("新增 ${texts(result.added)}，移除 ${texts(result.removed)}，數量改變 ${texts(result.changed)}（共要更新 ${result.updateCount} 列）")
    log("最後：共要更新 ${result.updateCount} 列（該更新的只有 3 列：新增 10005000、移除 9980000、9995000 數量改變）")
}

// MARK: - Bug 版
private object L08Bug {
    fun diff(old: List<E05Level>, new: List<E05Level>): L08Diff {
        val result = L08Diff()
        new.forEachIndexed { index, level ->
            if (index >= old.size) {
                result.added += level.price
            } else if (old[index].price.compareTo(level.price) != 0 || old[index].qty.compareTo(level.qty) != 0) {   // 用位置對應：第 index 列和第 index 列比
                result.changed += level.price
            }
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::diff)
}

// MARK: - 修正版
private object L08Fixed {
    fun diff(old: List<E05Level>, new: List<E05Level>): L08Diff {
        val oldByPrice = old.associate { it.price.stripTrailingZeros() to it.qty }          // 用價格當識別
        val newByPrice = new.associate { it.price.stripTrailingZeros() to it.qty }
        val result = L08Diff()
        for (level in new) {
            val before = oldByPrice[level.price.stripTrailingZeros()]
            if (before == null) result.added += level.price
            else if (before.compareTo(level.qty) != 0) result.changed += level.price
        }
        for (level in old) if (level.price.stripTrailingZeros() !in newByPrice) result.removed += level.price
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::diff)
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "多一檔最佳買價，整張板都重畫",
    question = "第 6 題：板更新得很頻繁，畫面怎麼更新才不會卡？",
    goal = "比較新舊兩版板，要用價格當識別，只更新新增、移除、數量改變的那幾列；用位置對應，只要中間多一檔，後面每一列都會被判斷成有變動。",
    steps = listOf(
        "選「Bug 版」執行，看「共要更新幾列」。新舊兩版其實只差 3 列。",
        "想一想：為什麼新增一檔最佳買價，連沒變的 10,000,000、9,990,000 都被判斷成有變動？",
        "選「修正版」執行，要看到新增 [10005000]，移除 [9980000]，數量改變 [9995000]，共 3 列。",
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
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, diff) 執行"
            lines.none { it.startsWith("最後：共要更新 5 列") } -> "Bug 還沒重現：用位置比較（old[index] 和 new[index]），新增一檔之後 5 列都會被判斷成有變動"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, diff) 執行"
            "新增 [10005000]，移除 [9980000]，數量改變 [9995000]（共要更新 3 列）" !in lines ->
                "用價格當識別：舊版沒有的價格是新增，新版沒有的是移除，兩邊都有但數量不同的才是數量改變"
            else -> null
        }
    },
)
