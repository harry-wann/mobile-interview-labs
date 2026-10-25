// L01：市價單的手續費少收了（extension function 沒有多型）
// 對應複習站：Kotlin02Functions 第 1 題「extension function
// 是什麼？為什麼它沒有多型？」
//
// 情境：一般委託的手續費是成交金額的 0.1%，市價單是 0.3%。清單裡放的是
// List<Order>，裡面有一般委託，也有市價單。
//
// 你要寫：
//   ① Bug 版（L01Bug）：用 extension function 寫手續費：Order.fee() 和
// MarketOrder.fee()。
//      extension 是靜態分派，宣告成 Order 的變數即使實際是 MarketOrder，
// 也只會用 Order.fee()，市價單少收手續費。
//   ② 修正版（L01Fixed）：fee() 寫成成員函式：Order 裡 open fun fee()，
// MarketOrder 用 override 覆寫。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin02-functions/L01_ExtensionDispatch.kt
package tw.harry.labs.practice.kotlin02functions

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L01Bug {
    // notional：成交金額（日圓）
    open class Order(val symbol: String, val notional: Long)
    class MarketOrder(symbol: String, notional: Long) : Order(symbol, notional)

    // TODO ①-1：寫 extension：fun Order.fee(): Long = notional /
    // 1000（一般委託 0.1%）
    fun Order.fee(): Long = 0

    // TODO ①-2：再寫一個 extension：fun MarketOrder.fee(): Long = notional * 3
    // / 1000（市價單 0.3%，以為這個會被用到）
    fun MarketOrder.fee(): Long = 0

    fun run(log: LabLog) {
        val orders: List<Order> =
            listOf(
                Order("BTC_JPY", 1_000_000),
                MarketOrder("ETH_JPY", 1_000_000),
            )
        // o 宣告成 Order：編譯器固定選 Order.fee()
        for (o in orders) log("${o.symbol} 手續費 ${o.fee()}")
        log("手續費合計 ${orders.sumOf { it.fee() }}")
    }
}

// MARK: - 修正版
private object L01Fixed {
    open class Order(val symbol: String, val notional: Long) {
        // TODO ②-1：寫成員函式 open fun fee(): Long = notional /
        // 1000（一般委託 0.1%）
        open fun fee(): Long = 0
    }

    class MarketOrder(symbol: String, notional: Long) :
        Order(symbol, notional) {
        // TODO ②-2：用 override fun fee(): Long = notional * 3 / 1000
        // 覆寫（市價單 0.3%）
    }

    fun run(log: LabLog) {
        val orders: List<Order> =
            listOf(
                Order("BTC_JPY", 1_000_000),
                MarketOrder("ETH_JPY", 1_000_000),
            )
        for (o in orders) log("${o.symbol} 手續費 ${o.fee()}")
        log("手續費合計 ${orders.sumOf { it.fee() }}")
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "市價單的手續費少收了",
    question = "第 1 題：extension function 是什麼？為什麼它沒有多型？",
    goal = "一般委託收 0.1%、市價單收 0.3%。先寫出收不到市價單手續費的 extension，再改成成員函式。",
    steps = listOf(
        "打開 practice/kotlin/kotlin02-functions/L01_ExtensionDispatch.kt，寫 TODO ①-1、①-2（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 ETH_JPY（市價單）的手續費是 1000 還是 3000。",
        "寫 TODO ②-1、②-2（修正版），選「修正版」執行，確認市價單收到 3000。",
        "想一想：如果寫成 val m = MarketOrder(…); m.fee()，Bug 版會用哪一個？（變數宣告的型別是 MarketOrder，所以會用 MarketOrder.fee()）",
    ),
    expectBug = "ETH_JPY 的手續費是 1000（應該是 3000），「手續費合計 2000」。",
    expectFixed = "ETH_JPY 的手續費是 3000，「手續費合計 4000」。",
    file = "kotlin02-functions/L01_ExtensionDispatch.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "BTC_JPY 手續費 1000" !in lines -> "一般委託的手續費要是 1000：Order.fee() = notional / 1000"
            "手續費合計 2000" !in lines -> "Bug 版市價單也要被當成 0.1%（合計 2000）：用 extension 寫 Order.fee() 與 MarketOrder.fee()"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "BTC_JPY 手續費 1000" !in lines -> "一般委託的手續費要是 1000：Order 的 open fun fee() = notional / 1000"
            "ETH_JPY 手續費 3000" !in lines -> "市價單的手續費要是 3000：MarketOrder 用 override fun fee() 覆寫"
            else -> null
        }
    },
)
