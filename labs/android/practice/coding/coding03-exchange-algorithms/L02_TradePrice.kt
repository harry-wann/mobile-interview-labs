// L02：買方限價 102，成交價卻全部變成 102（成交價用了 taker 的限價）
// 對應複習站：Coding03ExchangeAlgorithms 第 1 題「撮合引擎怎麼決定誰和誰成交、用什麼價格？」
//
// 情境：賣單簿有 A（100 円 × 1）、B（101 円 × 1）、C（103 円 × 5）。使用者下限價買單：102 円 × 3。
//       C 的 103 比限價貴，不成交；A、B 各成交 1 個，剩下 1 個沒吃到。
//
// 你要寫：
//   ① Bug 版（L02Bug.buy）：成交價用買方自己的限價 price。
//   ② 修正版（L02Fixed.buy）：成交價用簿上掛單 best（maker）的價格 best.price。
// 只改有 TODO 的地方（撮合的迴圈已經寫好）。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/coding/coding03-exchange-algorithms/L02_TradePrice.kt
package tw.harry.labs.practice.coding03exchangealgorithms

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：用限價 102 買 3 個，看成交價與總共付出多少 */
private fun scenario(log: LabLog, buy: (price: Int, qty: Int, asks: MutableList<C03Order>) -> List<C03Trade>) {
    val asks = mutableListOf(C03Order("A", 100, 1), C03Order("B", 101, 1), C03Order("C", 103, 5))
    log("賣單簿：${c03OrdersText(asks)}")
    val trades = buy(102, 3, asks)
    log("買進限價 102 × 3 → 成交 ${c03TradesText(trades)}")
    log("買方總共付出 ${trades.sumOf { it.price * it.qty }} 円")
}

// MARK: - Bug 版
private object L02Bug {
    fun buy(price: Int, qty: Int, asks: MutableList<C03Order>): List<C03Trade> {
        var remaining = qty
        val trades = ArrayList<C03Trade>()
        while (remaining > 0 && asks.isNotEmpty() && asks[0].price <= price) {
            val best = asks[0]
            val fill = minOf(remaining, best.qty)
            // TODO ①：成交價用買方自己的限價 price
            val tradePrice = 0
            trades.add(C03Trade(best.id, tradePrice, fill))
            remaining -= fill
            best.qty -= fill
            if (best.qty == 0) asks.removeAt(0)
        }
        return trades
    }

    suspend fun run(log: LabLog) = scenario(log, ::buy)
}

// MARK: - 修正版
private object L02Fixed {
    fun buy(price: Int, qty: Int, asks: MutableList<C03Order>): List<C03Trade> {
        var remaining = qty
        val trades = ArrayList<C03Trade>()
        while (remaining > 0 && asks.isNotEmpty() && asks[0].price <= price) {
            val best = asks[0]
            val fill = minOf(remaining, best.qty)
            // TODO ②：成交價是 maker（簿上掛單 best）的價格
            val tradePrice = 0
            trades.add(C03Trade(best.id, tradePrice, fill))
            remaining -= fill
            best.qty -= fill
            if (best.qty == 0) asks.removeAt(0)
        }
        return trades
    }

    suspend fun run(log: LabLog) = scenario(log, ::buy)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "買方限價 102，成交價全變成 102",
    question = "第 1 題：撮合引擎怎麼決定誰和誰成交、用什麼價格？",
    goal = "成交價是 maker（簿上先掛的那一方）的價格；買方的限價只決定「最貴願意吃到哪一檔」。先寫出成交價用買方限價的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/coding/coding03-exchange-algorithms/L02_TradePrice.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看每一筆成交的價格，以及買方總共付出多少円。",
        "寫 TODO ②（修正版），選「練習」「修正版」執行，對照賣單簿：A 掛 100、B 掛 101，成交價應該是多少？",
        "想一想：市價單為什麼會滑價？（吃到比較貴的價位時，成交價是每一檔各自的價格）",
    ),
    expectBug = "成交 [A:1@102, B:1@102]，買方付出 204 円（多付了 3 円）。",
    expectFixed = "成交 [A:1@100, B:1@101]，買方付出 201 円。",
    file = "coding03-exchange-algorithms/L02_TradePrice.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("買進限價 102") } -> "演練沒有跑完：要用 scenario(log, buy) 執行"
            "買進限價 102 × 3 → 成交 [A:1@102, B:1@102]" !in lines -> "Bug 還沒重現：成交價要寫成買方自己的限價 price，A、B 才會都變成 102"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("買進限價 102") } -> "演練沒有跑完：要用 scenario(log, buy) 執行"
            "買進限價 102 × 3 → 成交 [A:1@100, B:1@101]" !in lines -> "成交價要用簿上掛單（best）自己的價格 best.price，A 是 100、B 是 101"
            else -> null
        }
    },
)
