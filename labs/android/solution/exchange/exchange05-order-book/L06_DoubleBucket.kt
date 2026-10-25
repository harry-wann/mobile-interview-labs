// L06：剛好對齊的價格，被歸到下面一檔（合併價格用 Double 做除法再取整）
// 對應複習站：Exchange05OrderBook 第 4 題「依價格間隔（step）合併時，買方、
// 賣方各往哪個方向取整？」
//
// 情境：一檔價格在 80 円左右的幣，買方要合併成每 0.1 円一檔（step = 0.1），
// 往下取整，同一檔的數量相加。
//       買方：80.37×0.2、80.30×0.5、80.29×0.1。80.30 已經對齊 0.1，應該留在 80.
// 3 這一檔；
//       正確的合併結果：80.3×0.7（80.37 和 80.30）、80.2×0.1（80.29）。
// Bug 版：用 Double：Math.floor(price / step) * step。80.30 ÷ 0.1 在 Double
// 裡是 802.9999999999999，往下取整變成 802，
//        剛好對齊的 80.30 被歸到 80.2；80.37 算出來的 80.30000000000001
// 又自己成為一檔。
// 修正版：用 BigDecimal 做除法和取整（divide(step, 0, RoundingMode.FLOOR)），
// 80.30 ÷ 0.1 剛好是 803。
package tw.harry.labs.solution.exchange05orderbook

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.TreeMap
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val rawBids = listOf(
    "80.37" to "0.2",
    "80.30" to "0.5",
    "80.29" to "0.1",
)
private const val ANSWER = "80.3×0.7、80.2×0.1"

private fun scenario(log: LabLog, merged: String) {
    log(
        "合併前的買方：" + rawBids.joinToString("、") { "${it.first}×${it.second}" } +
            "（step 0.1，往下取整）",
    )
    log("合併後的買方：$merged")
    log("最後：" + if (merged == ANSWER) "和正確答案一致" else "和正確答案不一致，正確答案是 $ANSWER")
}

// MARK: - Bug 版
private object L06Bug {
    fun bucket(price: Double, step: Double): Double =
        // Double 的除法有誤差：80.30 ÷ 0.1 = 802.9999999999999
        Math.floor(price / step) * step

    suspend fun run(log: LabLog) {
        val merged = HashMap<Double, Double>()
        for ((price, qty) in rawBids) {
            merged.merge(bucket(price.toDouble(), 0.1), qty.toDouble()) {
                a,
                b,
                ->
                a +
                    b
            }
        }
        val text = merged.entries.sortedByDescending { it.key }
            .joinToString("、") {
                String.format(Locale.ROOT, "%.1f×%.1f", it.key, it.value)
            }
        scenario(log, text)
    }
}

// MARK: - 修正版
private object L06Fixed {
    fun bucket(price: BigDecimal, step: BigDecimal): BigDecimal =
        // BigDecimal 的除法：80.30 ÷ 0.1 = 803
        price.divide(step, 0, RoundingMode.FLOOR).multiply(step)

    suspend fun run(log: LabLog) {
        val merged = TreeMap<BigDecimal, BigDecimal>(Comparator.reverseOrder())
        for ((price, qty) in rawBids) {
            merged.merge(bucket(e05(price), e05("0.1")), e05(qty)) { a, b ->
                a +
                    b
            }
        }
        val text = merged.entries.joinToString("、") {
            "${e05Text(it.key)}×${e05Text(it.value)}"
        }
        scenario(log, text)
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "剛好對齊的價格被歸到下面一檔",
    question = "第 4 題：依價格間隔（step）合併時，買方、賣方各往哪個方向取整？",
    goal = "合併價格要用十進位型別（Decimal／BigDecimal）做除法和取整。用 Double 的 floor，剛好對齊的價格會因為誤差少算一格。",
    steps = listOf(
        "選「Bug 版」執行，看合併後的買方：80.30 這一筆被歸到哪一檔？",
        "想一想：80.30 ÷ 0.1 在 Double 裡等於多少？往下取整會變成幾？（可以自己印出來看）",
        "選「修正版」執行，要得到 80.3×0.7、80.2×0.1。",
        "進階：賣方往上取整（ceil）也有問題：80.30 算出來是 80.30000000000001，拿它當 Map 的 key，會和另一個來源寫的 80.3 分成兩檔。",
    ),
    expectBug = "合併後的買方是「80.3×0.2、80.2×0.6」：剛好對齊的 80.30 被歸到 80.2，「和正確答案不一致」。",
    expectFixed = "合併後的買方是「80.3×0.7、80.2×0.1」，「和正確答案一致」。",
    file = "exchange05-order-book/L06_DoubleBucket.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, merged) 執行"

            "合併後的買方：80.3×0.2、80.2×0.6" !in lines -> "Bug 還沒重現：bucket 用 Double 計算 Math.floor(price / step) * step，80.30 會被歸到 80.2"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, merged) 執行"

            "合併後的買方：80.3×0.7、80.2×0.1" !in lines -> "bucket 要用 BigDecimal：price.divide(step, 0, RoundingMode.FLOOR) 取整，再乘回 step"

            else -> null
        }
    },
)
