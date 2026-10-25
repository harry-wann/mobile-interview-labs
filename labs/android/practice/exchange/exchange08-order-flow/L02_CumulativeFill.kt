// L02：成交量比委託量還多，訂單卻顯示「已成交」
// （把伺服器給的累計成交量當成增量加起來）
// 對應複習站：Exchange08OrderFlow 第 2 題「訂單有哪些狀態？事件亂序或重複時，
// 怎麼合併成正確的狀態？」
//
// 情境：委託量 1.0 BTC。伺服器每次推送「到目前為止一共成交多少」（累計值），
// 依序到達：0.1、0.4、0.2（過期的）、0.4（重複的）、0.9。
//        正確的最終狀態：partialFilled，成交量 0.9；其中 0.2 和第二個 0.4
// 是過期或重複的，要忽略（2 個）。
//
// 你要寫：
//   ① Bug 版（L02Bug.Merge.fill）：把 total 加到 filled 上（增量的想法），
// filled 大於等於 amount 就是 FILLED，否則 PARTIAL_FILLED。
//   ② 修正版（L02Fixed.Merge.fill）：total 小於等於目前的 filled（過期、重複）
// 或大於 amount（無效）就原樣回傳；否則 filled 換成 total，等於 amount 是
// FILLED，否則 PARTIAL_FILLED。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange08-order-flow/L02_CumulativeFill.kt
package tw.harry.labs.practice.exchange08orderflow

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L02Status(val label: String) {
    SUBMITTED("submitted"),
    PARTIAL_FILLED("partialFilled"),
    FILLED("filled"),
}

private data class L02Order(
    val status: L02Status,
    val filled: BigDecimal,
    val amount: BigDecimal,
)

private interface L02Reducer {
    /**
     * 累計成交量 total 的 fill 事件到達：回傳新的訂單（事件要忽略就原樣回傳）
     */
    fun fill(order: L02Order, total: BigDecimal): L02Order
}

/** 演練流程（兩個版本共用）：五個 fill 事件依序到達 */
private fun scenario(log: LabLog, reducer: L02Reducer) {
    var order = L02Order(L02Status.SUBMITTED, e08("0"), e08("1.0"))
    var ignored = 0
    for (total in listOf("0.1", "0.4", "0.2", "0.4", "0.9")) {
        val next = reducer.fill(order, e08(total))
        val isIgnored =
            next.status == order.status &&
                next.filled.compareTo(order.filled) == 0
        if (isIgnored) ignored++
        order = next
        log(
            "fill(累計 $total) → ${order.status.label} ${e08Text(
                order.filled,
            )}${if (isIgnored) "（忽略）" else ""}",
        )
    }
    log(
        "最後：${order.status.label} ${e08Text(
            order.filled,
        )}，忽略了 $ignored 個過期或重複的事件（正確答案：partialFilled 0.9，忽略 2 個）",
    )
}

// MARK: - Bug 版
private object L02Bug {
    class Merge : L02Reducer {
        override fun fill(order: L02Order, total: BigDecimal): L02Order {
            // TODO ①：filled = order.filled + total（把累計值當成增量加上去）；
            //        status：filled 大於等於 amount 是 FILLED，否則
            // PARTIAL_FILLED；用 order.copy(...) 回傳。目前原樣回傳 order
            // 只是佔位
            return order
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Merge())
}

// MARK: - 修正版
private object L02Fixed {
    class Merge : L02Reducer {
        override fun fill(order: L02Order, total: BigDecimal): L02Order {
            // TODO ②：total 小於等於 order.filled（過期、重複），或大於 order.
            // amount（無效）：原樣回傳 order；
            //        否則 filled 換成 total，status：total 等於 amount 是
            // FILLED，否則 PARTIAL_FILLED；用 order.copy(...) 回傳。
            //        目前原樣回傳 order 只是佔位
            return order
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Merge())
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "成交量比委託量還多，訂單卻顯示已成交",
    question = "第 2 題：訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？",
    goal = "伺服器給的成交量是「累計值」：只增不減，小於等於目前值的是過期或重複的事件，超過委託量的是無效的事件，都要忽略；用增量去加，重複和亂序的事件會讓成交量越加越多。先寫出把累計值當增量加上去的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange08-order-flow/L02_CumulativeFill.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看每個 fill 事件之後的成交量，哪一步開始超過委託量 1.0？",
        "想一想：0.2 是過期的、第二個 0.4 是重複的，把它們加進去會怎樣？",
        "寫 TODO ②（修正版），選「修正版」執行，最後要是 partialFilled 0.9，而且忽略了 2 個事件。",
        "進階：為什麼用累計值比增量好？（重複、亂序的事件都不會破壞成交量）",
    ),
    expectBug = "每個 fill 都加上去：0.1、0.5、0.7、1.1（超過委託量 1.0，狀態變成 filled）、2：「最後：filled 2，忽略了 0 個過期或重複的事件」。",
    expectFixed = "0.2 和第二個 0.4 顯示「（忽略）」：「最後：partialFilled 0.9，忽略了 2 個過期或重複的事件」。",
    file = "exchange08-order-flow/L02_CumulativeFill.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, reducer) 執行"
            }

            lines.none { it.startsWith("最後：filled 2，忽略了 0 個") } -> {
                "Bug 還沒重現：fill 要把 total 加到 filled 上（order.filled + total），並依 filled 是否大於等於 amount 決定 filled 或 partialFilled"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, reducer) 執行"
            }

            lines.none { it.startsWith("最後：partialFilled 0.9，忽略了 2 個") } -> {
                "total 小於等於 order.filled（過期、重複）或大於 order.amount（無效）就原樣回傳 order；否則 filled 換成 total，等於 amount 是 filled，否則 partialFilled"
            }

            else -> {
                null
            }
        }
    },
)
