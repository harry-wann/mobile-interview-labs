// L07：最佳買價的深度條最長（累計數量從錯的一端開始加）
// 對應複習站：Exchange05OrderBook 第 5 題「累計數量和深度條怎麼算？」
//
// 情境：深度圖的每一列顯示「累計數量」：從最佳價位開始，往外一路加總，意思是「要吃到這個價位，總共需要多少量」。
//       深度條的寬度 = 該列的累計量 ÷ 兩邊最大的累計量。買方 9,995,000×0.2、9,990,000×0.6；賣方 10,010,000×0.1、10,020,000×0.5。
//       畫面由上到下，最佳價在最上面：賣方由低價排到高價，買方由高價排到低價。
//       正確的買方：9995000 累計 0.2（25%）、9990000 累計 0.8（100%）；賣方：10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）。
//
// 你要寫：
//   ① Bug 版（L07Bug.run）：買方也依價格由低排到高（sortedBy { level -> level.price }）。
//   ② 修正版（L07Fixed.run）：買方依價格由高排到低（sortedByDescending { level -> level.price }），最佳買價排第一。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange05-order-book/L07_CumulativeOrder.kt
package tw.harry.labs.practice.exchange05orderbook

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private fun levels(prices: Map<BigDecimal, BigDecimal>): List<E05Level> = prices.map { E05Level(it.key, it.value) }

/** 累計：由第一列開始，往下一路加總 */
private fun cumulative(levels: List<E05Level>): List<BigDecimal> {
    var running = BigDecimal.ZERO
    return levels.map { running += it.qty; running }
}

private fun describe(levels: List<E05Level>, maxCumulative: BigDecimal): String =
    levels.zip(cumulative(levels)).joinToString("、") { (level, total) ->
        val percent = total.divide(maxCumulative, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100))
        "${e05Text(level.price)} 累計 ${e05Text(total)}（${e05Text(percent)}%）"
    }

/** 演練流程（兩個版本共用）：賣方由低價排到高價；買方怎麼排，由 `orderBids` 決定 */
private fun scenario(log: LabLog, orderBids: (List<E05Level>) -> List<E05Level>) {
    val depth = E05Depth()
    depth.apply(
        listOf(E05Level("9995000", "0.2"), E05Level("9990000", "0.6")),
        listOf(E05Level("10010000", "0.1"), E05Level("10020000", "0.5")),
    )
    val asks = levels(depth.asks).sortedBy { it.price }
    val bids = orderBids(levels(depth.bids))
    val maxCumulative = maxOf(cumulative(asks).last(), cumulative(bids).last())     // 兩邊最大的累計量：0.8
    log("賣方（由上到下）：${describe(asks, maxCumulative)}")
    log("買方（由上到下）：${describe(bids, maxCumulative)}")
    log("最後：買方第一列是 ${e05Text(bids[0].price)}，真正的最佳買價是 ${e05Text(depth.bestBid!!.price)}")
}

// MARK: - Bug 版
private object L07Bug {
    suspend fun run(log: LabLog) = scenario(log) {
        // TODO ①：買方也依價格由低排到高：it.sortedBy { level -> level.price }。目前的由高到低只是佔位，要改掉
        it.sortedByDescending { level -> level.price }
    }
}

// MARK: - 修正版
private object L07Fixed {
    suspend fun run(log: LabLog) = scenario(log) {
        // TODO ②：買方依價格由高排到低，最佳買價排第一：it.sortedByDescending { level -> level.price }。目前的由低到高只是佔位，要改掉
        it.sortedBy { level -> level.price }
    }
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "最佳買價的深度條最長",
    question = "第 5 題：累計數量和深度條怎麼算？",
    goal = "累計數量要從最佳價位開始往外加。買方最佳價是最高價，所以買方要由高價排到低價再累計。先寫出買方排成由低到高的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange05-order-book/L07_CumulativeOrder.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看買方第一列（畫面最上面、應該是最佳買價）的累計與深度條。",
        "最後一行：買方第一列是不是真正的最佳買價？",
        "寫 TODO ②（修正版），選「修正版」執行，買方第一列要是 9995000，累計 0.2，深度條 25%。",
        "想一想：預估市價單的成交價時，也是從最佳價位往外吃。排錯順序會算出什麼價格？",
    ),
    expectBug = "買方第一列是 9990000，累計 0.6（75%），最佳買價 9995000 排在最後，累計 0.8（100%）；「買方第一列是 9990000，真正的最佳買價是 9995000」。",
    expectFixed = "買方「9995000 累計 0.2（25%）、9990000 累計 0.8（100%）」，賣方「10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）」。",
    file = "exchange05-order-book/L07_CumulativeOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, orderBids) 執行"
            "買方（由上到下）：9990000 累計 0.6（75%）、9995000 累計 0.8（100%）" !in lines ->
                "Bug 還沒重現：買方也要由低價排到高價（sortedBy { it.price }），最佳買價 9,995,000 就會排到最後"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, orderBids) 執行"
            "賣方（由上到下）：10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）" !in lines -> "賣方原本就對：由低價排到高價"
            "買方（由上到下）：9995000 累計 0.2（25%）、9990000 累計 0.8（100%）" !in lines ->
                "買方要由高價排到低價（sortedByDescending { it.price }），最佳買價 9,995,000 排第一，累計 0.2"
            else -> null
        }
    },
)
