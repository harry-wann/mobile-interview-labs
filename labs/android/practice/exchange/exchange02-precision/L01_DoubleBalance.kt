// L01：明明剛好夠，卻顯示「餘額不足」（可用餘額用 Double 算）
// 對應複習站：Exchange02Precision 第 1 題「為什麼金額不能用 `Double`？」
//
// 情境：使用者的資產有一部分掛在還沒成交的單上（凍結）。可用餘額 = 總額 − 凍結。
//        API 回傳的總額與凍結是字串。三個交易對的情況：總額 1.0 凍結 0.9、總額 0.3 凍結 0.2、總額 0.7 凍結 0.6，可用餘額都是剛好 0.1。
//        使用者按「全部賣出」，輸入框填入 0.1，App 判斷「輸入的數量不超過可用餘額」才能送出。
//        （Platform08 的演練 2 練過「Double 對齊 tick size 出錯」，這題看的是餘額的加減。）
//
// 你要寫：
//   ① Bug 版（L01Bug.check）：用 Double 算可用餘額 = 總額 − 凍結，再比較輸入的數量。
//   ② 修正版（L01Fixed.check）：改用 BigDecimal(String) 算。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange02-precision/L01_DoubleBalance.kt
package tw.harry.labs.practice.exchange02precision

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：三種情況各按一次「全部賣出」 */
private fun scenario(log: LabLog, check: (String, String, String) -> Pair<String, Boolean>) {
    var refused = 0
    for ((total, locked) in listOf("1.0" to "0.9", "0.3" to "0.2", "0.7" to "0.6")) {
        val (available, enough) = check(total, locked, "0.1")
        log("總額 $total − 凍結 $locked = 可用 $available，輸入 0.1 → ${if (enough) "可以送出" else "顯示「餘額不足」"}")
        if (!enough) refused++
    }
    log("3 次「全部賣出」，被誤判餘額不足 $refused 次")
}

// MARK: - Bug 版
private object L01Bug {
    /** 回傳可用餘額（文字）和「輸入的數量夠不夠」 */
    fun check(total: String, locked: String, input: String): Pair<String, Boolean> {
        // TODO ①：用 total.toDouble()、locked.toDouble() 算出可用餘額 available = 總額 − 凍結；
        //        回傳 "$available" to (輸入的數量 input.toDouble() 是不是不超過 available)
        return "" to true
    }

    suspend fun run(log: LabLog) = scenario(log, ::check)
}

// MARK: - 修正版
private object L01Fixed {
    fun check(total: String, locked: String, input: String): Pair<String, Boolean> {
        // TODO ②：改用 BigDecimal(String) 建立 total、locked、input，一樣算可用餘額並比較（顯示用 e02Text(available)）
        return "" to false
    }

    suspend fun run(log: LabLog) = scenario(log, ::check)
}

// MARK: - 演練設定
private fun refusedCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("3 次") }?.let { Regex("(\\d+) 次$").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "明明剛好夠，卻顯示「餘額不足」",
    question = "第 1 題：為什麼金額不能用 `Double`？",
    goal = "可用餘額 = 總額 − 凍結，使用者輸入剛好等於可用餘額時，一定要能送出。餘額的加減要用什麼型別？先寫出用 Double 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange02-precision/L01_DoubleBalance.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看三次「全部賣出」的可用餘額是多少、有幾次被誤判餘額不足。",
        "想一想：1.0 − 0.9 在 Double 裡剛好等於 0.1 嗎？比較「輸入的 0.1」和「算出來的可用餘額」誰大。",
        "寫 TODO ②（修正版），選「修正版」執行，三次都應該可以送出。",
        "進階：金額與數量一律用 Decimal／BigDecimal，API 的數字用字串讀進來再轉，不經過 Double。",
    ),
    expectBug = "可用餘額顯示 0.09999999999999998，輸入 0.1 反而比較大，「3 次「全部賣出」，被誤判餘額不足 3 次」。",
    expectFixed = "三次的可用餘額都是剛好 0.1，都可以送出，「3 次「全部賣出」，被誤判餘額不足 0 次」。",
    file = "exchange02-precision/L01_DoubleBalance.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = refusedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::check) 執行"
            "總額 1.0 − 凍結 0.9 = 可用 0.09999999999999998，輸入 0.1 → 顯示「餘額不足」" !in lines ->
                "Bug 還沒重現：用 total.toDouble() − locked.toDouble() 算可用餘額，1.0 − 0.9 應該是 0.09999999999999998，比 0.1 小一點點"
            n != 3 -> "Bug 還沒重現：三次都應該比 0.1 小一點點（目前誤判 $n 次）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = refusedCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::check) 執行"
            n != 0 -> "還有 $n 次被誤判餘額不足：用 BigDecimal(String) 算可用餘額，也用 BigDecimal 比較輸入的數量"
            "總額 1.0 − 凍結 0.9 = 可用 0.1，輸入 0.1 → 可以送出" !in lines -> "可用餘額應該是剛好 0.1：total 和 locked 都要用 BigDecimal(String) 建立"
            else -> null
        }
    },
)
