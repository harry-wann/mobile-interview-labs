// L04：在 leg { } 裡寫錯，改到的卻是整張訂單的 symbol（@DslMarker）
// 對應複習站：Kotlin02Functions 第 3 題「什麼是 lambda with receiver？DSL 怎麼做，@DslMarker 是做什麼的？」
//
// 情境：用 DSL 組訂單：order { symbol = …; leg { price = …; qty = … } }。每個 leg（一筆分批委託）只有 price 和 qty，沒有 symbol。
// Bug 版：builder 沒有加 @DslMarker。在 leg { } 裡面寫 symbol = "ETH_JPY"，LegBuilder 沒有 symbol，
//        Kotlin 就往外層找，找到 OrderBuilder.symbol 並且默默改掉。編譯器不會提醒，整張訂單的 symbol 被改成 ETH_JPY。
// 修正版：定義 @DslMarker 標註，標在 OrderBuilder 和 LegBuilder 上。區塊裡只能隱含地使用最近的一層 receiver，
//        在 leg { } 裡寫 symbol = … 會編譯錯誤，要用外層必須明確寫 this@order.symbol。
package tw.harry.labs.solution.kotlin02functions

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class DslLeg(val price: BigDecimal, val qty: BigDecimal)
private class DslOrder(val symbol: String, val legs: List<DslLeg>)

// MARK: - Bug 版
private object L04Bug {
    class LegBuilder {
        var price = BigDecimal.ZERO
        var qty = BigDecimal.ZERO
        fun build() = DslLeg(price, qty)
    }

    class OrderBuilder {
        var symbol = ""
        private val legs = mutableListOf<DslLeg>()
        fun leg(block: LegBuilder.() -> Unit) {
            legs += LegBuilder().apply(block).build()
        }
        fun build() = DslOrder(symbol, legs.toList())
    }

    fun order(block: OrderBuilder.() -> Unit): DslOrder = OrderBuilder().apply(block).build()

    fun run(log: LabLog) {
        val o = order {
            symbol = "BTC_JPY"
            leg {
                symbol = "ETH_JPY"                         // 手滑：LegBuilder 沒有 symbol，往外層找到 OrderBuilder.symbol，默默改掉
                price = BigDecimal("100")
                qty = BigDecimal("0.5")
            }
        }
        log("訂單 symbol = ${o.symbol}，${o.legs.size} 筆 leg")
    }
}

// MARK: - 修正版
private object L04Fixed {
    @DslMarker
    @Retention(AnnotationRetention.RUNTIME)
    annotation class OrderDsl                              // 自己定義的 DSL 標註

    @OrderDsl
    class LegBuilder {
        var price = BigDecimal.ZERO
        var qty = BigDecimal.ZERO
        fun build() = DslLeg(price, qty)
    }

    @OrderDsl
    class OrderBuilder {
        var symbol = ""
        private val legs = mutableListOf<DslLeg>()
        fun leg(block: LegBuilder.() -> Unit) {
            legs += LegBuilder().apply(block).build()
        }
        fun build() = DslOrder(symbol, legs.toList())
    }

    fun order(block: OrderBuilder.() -> Unit): DslOrder = OrderBuilder().apply(block).build()

    fun run(log: LabLog) {
        val o = order {
            symbol = "BTC_JPY"
            leg {
                // symbol = "ETH_JPY"                      // 取消註解看看：有 @DslMarker 之後，這一行會編譯錯誤
                price = BigDecimal("100")
                qty = BigDecimal("0.5")
            }
        }
        log("訂單 symbol = ${o.symbol}，${o.legs.size} 筆 leg")
        // @DslMarker 要標在每一層 builder 上才有效（用反射確認）
        log("OrderBuilder 標了 @OrderDsl：${OrderBuilder::class.java.isAnnotationPresent(OrderDsl::class.java)}")
        log("LegBuilder 標了 @OrderDsl：${LegBuilder::class.java.isAnnotationPresent(OrderDsl::class.java)}")
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "在 leg 裡寫錯，整張訂單的 symbol 被改掉",
    question = "第 3 題：什麼是 lambda with receiver？DSL 怎麼做，@DslMarker 是做什麼的？",
    goal = "巢狀 DSL 的內層區塊可以隱含地用到外層 builder 的成員。看寫錯地方時編譯器為什麼不提醒，再用 @DslMarker 擋住。",
    steps = listOf(
        "選「Bug 版」執行，看訂單的 symbol 是 BTC_JPY 還是 ETH_JPY。",
        "選「修正版」執行，確認訂單的 symbol 沒有被改掉，兩層 builder 都標了 @OrderDsl。",
        "在修正版的 leg { } 裡取消註解 symbol = \"ETH_JPY\"，看 Android Studio 怎麼報錯，再復原。",
    ),
    expectBug = "「訂單 symbol = ETH_JPY」：leg 裡的 symbol = … 默默改到外層的訂單。",
    expectFixed = "「訂單 symbol = BTC_JPY」，OrderBuilder、LegBuilder 都標了 @OrderDsl：true。",
    file = "kotlin02-functions/L04_DslMarker.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("訂單 symbol = ") } -> "沒有組出訂單：order { } 要回傳 DslOrder"
            "訂單 symbol = ETH_JPY，1 筆 leg" !in lines -> "Bug 版 symbol 要被 leg 區塊裡的 symbol = \"ETH_JPY\" 默默改掉：builder 先不要加 @DslMarker"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "訂單 symbol = BTC_JPY，1 筆 leg" !in lines -> "訂單要組出 symbol = BTC_JPY、1 筆 leg"
            "OrderBuilder 標了 @OrderDsl：true" !in lines -> "OrderBuilder 要標上 @OrderDsl"
            "LegBuilder 標了 @OrderDsl：true" !in lines -> "LegBuilder 要標上 @OrderDsl"
            else -> null
        }
    },
)
