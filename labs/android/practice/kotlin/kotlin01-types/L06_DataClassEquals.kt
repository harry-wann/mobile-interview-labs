// L06：價格明明沒變，畫面卻一直刷新（data class 的 equals 與 BigDecimal）
// 對應複習站：Kotlin01Types 第 6 題「data class 的 equals 有哪些容易踩的坑？」
//
// 情境：行情畫面收到新報價時，先和上一筆比較，不一樣才刷新畫面。
// 後端傳來的價格字串位數不固定：
//        "1.0"、"1.00"、"1.000" 數值都是 1，只是小數位數（scale）不同。
//
// 你要寫：
//   ① Bug 版（L06Bug）：Quote 宣告成普通的 data class（symbol 與 BigDecimal 的
// price）。
//      自動產生的 equals 連 scale 都比，1.0 和 1.00 不相等，
// 每一筆都被當成「價格變了」。
//   ② 修正版（L06Fixed）：Quote 自己覆寫 equals／hashCode，價格用 compareTo
// 比數值大小。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin01-types/L06_DataClassEquals.kt
package tw.harry.labs.practice.kotlin01types

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 後端依序傳來的 BTC_JPY 價格：前三筆數值都是 1，後兩筆都是 1.5 */
private val feed = listOf("1.0", "1.00", "1.000", "1.5", "1.50")

/**
 * 先直接比一比：內容完全一樣、1.0 對 1.00，以及放進 HashSet
 * 會剩幾個（兩個版本共用）
 */
private fun showEquality(log: LabLog, one0: Any, one00: Any, anotherOne0: Any) {
    log("內容完全一樣的兩筆報價相等嗎：${one0 == anotherOne0}")
    log("1.0 和 1.00 相等嗎：${one0 == one00}")
    log("放進 HashSet 剩幾個：${setOf(one0, one00).size}")
}

// MARK: - Bug 版
private object L06Bug {
    // TODO ①：把 Quote 改成 data class Quote(val symbol: String, val price:
    // BigDecimal)，不要覆寫 equals
    //        （自動產生的 equals 會呼叫 BigDecimal.equals，連 scale 一起比）
    class Quote(val symbol: String, val price: BigDecimal)

    fun run(log: LabLog) {
        showEquality(
            log,
            Quote("BTC_JPY", BigDecimal("1.0")),
            Quote("BTC_JPY", BigDecimal("1.00")),
            Quote("BTC_JPY", BigDecimal("1.0")),
        )
        var last: Quote? = null
        var refreshes = 0
        for (raw in feed) {
            val quote = Quote("BTC_JPY", BigDecimal(raw))
            // 和上一筆不一樣才刷新畫面
            if (quote != last) {
                refreshes++
                last = quote
                log("刷新畫面：$raw")
            }
        }
        log("共刷新 $refreshes 次")
    }
}

// MARK: - 修正版
private object L06Fixed {
    data class Quote(val symbol: String, val price: BigDecimal) {
        // TODO ②-1：覆寫 equals：other 是 Quote、symbol 相同，而且 price.
        // compareTo(other.price) == 0（比數值，不比 scale）
        // TODO ②-2：覆寫 hashCode：相等的物件要有相同的 hashCode，價格用 price.
        // stripTrailingZeros().hashCode()
    }

    fun run(log: LabLog) {
        showEquality(
            log,
            Quote("BTC_JPY", BigDecimal("1.0")),
            Quote("BTC_JPY", BigDecimal("1.00")),
            Quote("BTC_JPY", BigDecimal("1.0")),
        )
        var last: Quote? = null
        var refreshes = 0
        for (raw in feed) {
            val quote = Quote("BTC_JPY", BigDecimal(raw))
            if (quote != last) {
                refreshes++
                last = quote
                log("刷新畫面：$raw")
            }
        }
        log("共刷新 $refreshes 次")
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "價格沒變，畫面卻一直刷新",
    question = "第 6 題：data class 的 equals 有哪些容易踩的坑？",
    goal = "報價的價格字串位數不固定（1.0、1.00）。看 data class 自動產生的 equals 怎麼把它們當成不同，再改成比數值。",
    steps = listOf(
        "選「Bug 版」執行，數一數「刷新畫面」出現幾次。",
        "選「修正版」執行，確認數值沒變時不刷新。",
        "想一想：只覆寫 equals、沒有覆寫 hashCode，放進 HashSet 會怎樣？（1.0 和 1.00 會被當成兩個不同的元素）",
    ),
    expectBug = "「1.0 和 1.00 相等嗎：false」、「放進 HashSet 剩幾個：2」；五筆報價每一筆都刷新，最後印出「共刷新 5 次」。",
    expectFixed = "「1.0 和 1.00 相等嗎：true」、「放進 HashSet 剩幾個：1」；只有 1.0 和 1.5 刷新，最後印出「共刷新 2 次」。",
    file = "kotlin01-types/L06_DataClassEquals.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "內容完全一樣的兩筆報價相等嗎：true" !in lines -> "Quote 要宣告成 data class（內容一樣就相等）"
            "1.0 和 1.00 相等嗎：false" !in lines -> "Bug 版 1.0 和 1.00 要不相等：Quote 不要覆寫 equals"
            "共刷新 5 次" !in lines -> "Bug 版每一筆都要刷新（共 5 次）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "內容完全一樣的兩筆報價相等嗎：true" !in lines -> "Quote 要宣告成 data class（內容一樣就相等）"
            "1.0 和 1.00 相等嗎：true" !in lines -> "1.0 和 1.00 應該相等：覆寫 equals，價格用 compareTo 比"
            "放進 HashSet 剩幾個：1" !in lines -> "相等的物件要有相同的 hashCode：覆寫 hashCode，價格用 stripTrailingZeros()"
            "共刷新 2 次" !in lines -> "只有 1.0 和 1.5 應該刷新（共 2 次）"
            else -> null
        }
    },
)
