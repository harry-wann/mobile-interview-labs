// L04：成行買單預估的平均成交價，把滑價藏起來了
// 對應複習站：Exchange01Orders 第 3 題「成行、指値、逆指値這三種基本的下單方式有什麼差別？」
//
// 情境：使用者要用成行買 0.6 BTC。賣板有三層：10,000,000 円 0.2 BTC、10,010,000 円 0.3 BTC、10,030,000 円 0.5 BTC。
//        下單前，App 要預估平均成交價；預估的滑價（平均成交價比最低賣價貴多少）超過 0.05% 就要警告使用者。
//        （0.05% 是演練的假設。）
//
// 你要寫：
//   ① Bug 版（L04Bug.estimateCost）：全部用最低賣價 book.asks[0].price 乘以數量估算。
//   ② 修正版（L04Fixed.estimateCost）：從最便宜的價位開始一層一層吃，每層最多吃 min(剩餘數量, 這層的數量)，加總花費。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange01-orders/L04_MarketSlippage.kt
package tw.harry.labs.practice.exchange01orders

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：預估 → 判斷要不要警告 → 對照實際吃板的結果 */
private fun scenario(log: LabLog, estimateCost: (E01Book, BigDecimal) -> BigDecimal) {
    fun newBook() = E01Book(listOf(
        E01Level(e01("10000000"), e01("0.2")),
        E01Level(e01("10010000"), e01("0.3")),
        E01Level(e01("10030000"), e01("0.5")),
    ))
    val book = newBook()
    val want = e01("0.6")
    val best = book.asks[0].price
    val cost = estimateCost(book, want)
    val average = cost.divide(want, 8, RoundingMode.HALF_UP)
    val slippagePercent = (average - best).divide(best, 8, RoundingMode.HALF_UP) * BigDecimal(100)
    log("預估花費 ${e01Text(cost)} 円，平均成交價 ${e01Text(average)}（最低賣價 ${e01Text(best)}）")
    log("預估滑價 ${e01Text(slippagePercent)}%")
    if (slippagePercent > e01("0.05")) {
        log("⚠︎ 滑價超過 0.05%，要警告使用者")
    } else {
        log("滑價在 0.05% 以內，不警告")
    }
    val actual = newBook().takeAsks(want).fold(BigDecimal.ZERO) { sum, fill -> sum + fill.qty * fill.price }
    log("實際吃板花費 ${e01Text(actual)} 円，和預估差 ${e01Text(actual - cost)} 円")
}

// MARK: - Bug 版
private object L04Bug {
    fun estimateCost(book: E01Book, qty: BigDecimal): BigDecimal {
        // TODO ①：數量 × 最低賣價（book.asks[0].price）
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::estimateCost)
}

// MARK: - 修正版
private object L04Fixed {
    fun estimateCost(book: E01Book, qty: BigDecimal): BigDecimal {
        // TODO ②：remaining 一開始是 qty；依序走過 book.asks，每層吃 remaining.min(level.qty)，
        //        花費加上 吃的數量 × level.price，remaining 扣掉吃的數量，吃完就停；最後回傳總花費
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::estimateCost)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "成行買單預估的價格，看不出滑價",
    question = "第 3 題：成行、指値、逆指値這三種基本的下單方式有什麼差別？",
    goal = "成行單會一路吃掉好幾個價位。送出前用深度資料預估平均成交價，滑價太大要警告使用者。先寫出看不出滑價的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange01-orders/L04_MarketSlippage.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看預估花費和「實際吃板花費」差多少、有沒有警告。",
        "想一想：0.6 BTC 買得到全部都是 10,000,000 円嗎？第一層只有 0.2 BTC。",
        "寫 TODO ②（修正版），選「修正版」執行，預估要等於實際吃板的結果。",
        "進階：最後的結果一律以成交回報為準，預估只是幫使用者在送出前看到風險。",
    ),
    expectBug = "「預估花費 6000000 円，平均成交價 10000000」「預估滑價 0%」「滑價在 0.05% 以內，不警告」，但「實際吃板花費 6006000 円，和預估差 6000 円」。",
    expectFixed = "「預估花費 6006000 円，平均成交價 10010000」「預估滑價 0.1%」「⚠︎ 滑價超過 0.05%，要警告使用者」，和實際吃板的結果差 0 円。",
    file = "exchange01-orders/L04_MarketSlippage.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("實際吃板花費 6006000 円") } -> "演練沒有跑完：要用 scenario(log, ::estimateCost) 執行"
            "滑價在 0.05% 以內，不警告" !in lines -> "Bug 還沒重現：預估要用 qty × 最低賣價（book.asks[0].price），看不出滑價，所以不會警告"
            "實際吃板花費 6006000 円，和預估差 6000 円" !in lines -> "Bug 版預估花費應該是 6000000，和實際差 6000"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("實際吃板花費 6006000 円") } -> "演練沒有跑完：要用 scenario(log, ::estimateCost) 執行"
            "⚠︎ 滑價超過 0.05%，要警告使用者" !in lines -> "預估要一層一層吃板（0.2 + 0.3 + 0.1），平均成交價 10010000，滑價 0.1% 要警告"
            "實際吃板花費 6006000 円，和預估差 0 円" !in lines -> "預估花費應該等於實際吃板的 6006000"
            else -> null
        }
    },
)
