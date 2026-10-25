// L02：按「全部賣出」，數量卻被進位，伺服器說餘額不足（數量要往下對齊 step
// size）
// 對應複習站：Exchange02Precision 第 2 題「交易所對價格和數量有哪些規則？App
// 要怎麼對齊？」
//
// 情境：交易對的 step size（數量最小單位）是 0.0001 BTC，最小下單量也是 0.0001
// BTC。
//        使用者按「全部賣出」，App 要把持有量對齊 step size 再送出。兩種情況：
// 持有 0.12349999 BTC、持有 0.00009 BTC。
//        假伺服器：數量超過持有量就拒絕（餘額不足）。
//
// 你要寫：
//   ① Bug 版（L02Bug.align）：用 divide(step, 0, RoundingMode.HALF_UP)
// 四捨五入對齊 step size。
//   ② 修正版（L02Fixed.align）：改成 RoundingMode.DOWN（往下取整）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange02-precision/L02_StepSize.kt
package tw.harry.labs.practice.exchange02precision

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val step = e02("0.0001")
private val minQty = e02("0.0001")

/** 演練流程（兩個版本共用）：兩種持有量各按一次「全部賣出」 */
private fun scenario(
    log: LabLog,
    align: (BigDecimal, BigDecimal) -> BigDecimal,
) {
    var sent = 0
    var refused = 0
    var blocked = 0
    for (text in listOf("0.12349999", "0.00009")) {
        val holding = e02(text)
        val qty = align(holding, step)
        if (qty < minQty) {
            blocked++
            log(
                "持有 ${e02Text(
                    holding,
                )} BTC，對齊後 ${e02Text(
                    qty,
                )}：不到最小下單量 ${e02Text(minQty)}，App 提示使用者，不送出",
            )
            continue
        }
        sent++
        if (qty > holding) {
            refused++
            log("持有 ${e02Text(holding)} BTC，對齊後 ${e02Text(qty)}：伺服器拒絕（餘額不足）")
        } else {
            log("持有 ${e02Text(holding)} BTC，對齊後 ${e02Text(qty)}：伺服器受理")
        }
    }
    log("送到伺服器 $sent 筆，被拒絕 $refused 筆，App 端提示最小下單量 $blocked 次")
}

// MARK: - Bug 版
private object L02Bug {
    fun align(qty: BigDecimal, step: BigDecimal): BigDecimal {
        // TODO ①：用 qty.divide(step, 0, …) 除成整數個 step，捨入模式用
        // RoundingMode.HALF_UP（四捨五入），再 multiply(step) 回傳
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::align)
}

// MARK: - 修正版
private object L02Fixed {
    fun align(qty: BigDecimal, step: BigDecimal): BigDecimal {
        // TODO ②：一樣用 qty.divide(step, 0, …) 除成整數個 step，這次模式改成
        // RoundingMode.DOWN，再 multiply(step) 回傳
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::align)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "按「全部賣出」，數量被進位後被拒絕",
    question = "第 2 題：交易所對價格和數量有哪些規則？App 要怎麼對齊？",
    goal = "數量要對齊 step size，而且一律往下對齊，才不會超過持有量。往下對齊後不到最小下單量，要在 App 提示使用者，不要送出。先寫出四捨五入對齊的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange02-precision/L02_StepSize.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看兩種持有量對齊後的數量、伺服器的反應。",
        "想一想：0.12349999 ÷ 0.0001 = 1234.9999，四捨五入會變成多少？比持有量大還是小？",
        "寫 TODO ②（修正版），選「修正版」執行，看 0.12349999 和 0.00009 各自的結果。",
        "進階：這裡的 divide(step, 0, RoundingMode.DOWN) 是 BigDecimal 的做法；iOS 用 NSDecimalRound(…, 0, .down)。",
    ),
    expectBug = "「持有 0.12349999 BTC，對齊後 0.1235：伺服器拒絕（餘額不足）」「持有 0.00009 BTC，對齊後 0.0001：伺服器拒絕」，「送到伺服器 2 筆，被拒絕 2 筆」。",
    expectFixed = "「對齊後 0.1234：伺服器受理」「持有 0.00009 BTC，對齊後 0：不到最小下單量，App 提示使用者」，「送到伺服器 1 筆，被拒絕 0 筆，App 端提示最小下單量 1 次」。",
    file = "exchange02-precision/L02_StepSize.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("送到伺服器") } -> {
                "演練沒有跑完：要用 scenario(log, ::align) 執行"
            }

            "送到伺服器 2 筆，被拒絕 2 筆，App 端提示最小下單量 0 次" !in lines -> {
                "Bug 還沒重現：用 divide(step, 0, RoundingMode.HALF_UP) 四捨五入，兩種持有量對齊後都會比持有量大，兩筆都被伺服器拒絕"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("送到伺服器") } -> {
                "演練沒有跑完：要用 scenario(log, ::align) 執行"
            }

            "送到伺服器 1 筆，被拒絕 0 筆，App 端提示最小下單量 1 次" !in lines -> {
                "要往下對齊（RoundingMode.DOWN）：0.12349999 → 0.1234 可以成交，0.00009 → 0 要在 App 端提示"
            }

            else -> {
                null
            }
        }
    },
)
