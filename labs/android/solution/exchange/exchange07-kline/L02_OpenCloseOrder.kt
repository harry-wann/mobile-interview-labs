// L02：收盤價是最後「收到」的那一筆，不是最後「發生」的那一筆（成交亂序，用到達順序決定 open 和 close）
// 對應複習站：Exchange07Kline 第 2 題「怎麼把一筆一筆的成交聚合成 K 線？」
//
// 情境：重連補資料時，成交常常亂序到達。一分鐘內收到四筆成交（依收到的順序）：
//        第 40 秒 4,800,000×0.2、第 5 秒 4,790,000×0.1、第 55 秒 4,810,000×0.05、第 20 秒 4,780,000×0.3。
//        正確的 K 線：open 4790000（最早的：第 5 秒）、close 4810000（最晚的：第 55 秒）、high 4810000、low 4780000、amount 0.65、vol 3113500、count 4。
//        （high、low、amount、vol、count 和順序無關，已經寫好。）
// Bug 版：open 取收到的第一筆、close 取收到的最後一筆：open 4800000、close 4780000。
// 修正版：先依（成交時間, 成交編號）排序，open 取最早的一筆、close 取最晚的一筆。
package tw.harry.labs.solution.exchange07kline

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L02Trade(val tradeId: Long, val ts: Long, val price: BigDecimal, val qty: BigDecimal)

/** 演練流程（兩個版本共用）：`openClose` 決定怎麼找出 open 與 close */
private fun scenario(log: LabLog, openClose: (List<L02Trade>) -> Pair<BigDecimal, BigDecimal>) {
    val base = 1_632_931_200_000L          // 這一分鐘的起點（毫秒）
    val trades = listOf(
        L02Trade(4, base + 40_000, e07("4800000"), e07("0.2")),
        L02Trade(1, base + 5_000, e07("4790000"), e07("0.1")),
        L02Trade(6, base + 55_000, e07("4810000"), e07("0.05")),
        L02Trade(3, base + 20_000, e07("4780000"), e07("0.3")),
    )
    val (open, close) = openClose(trades)
    val high = trades.maxOf { it.price }
    val low = trades.minOf { it.price }
    val amount = trades.fold(BigDecimal.ZERO) { sum, trade -> sum + trade.qty }
    val vol = trades.fold(BigDecimal.ZERO) { sum, trade -> sum + trade.price * trade.qty }
    log("正確答案：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4")
    log("最後：open ${e07Text(open)}，close ${e07Text(close)}，high ${e07Text(high)}，low ${e07Text(low)}，amount ${e07Text(amount)}，vol ${e07Text(vol)}，count ${trades.size}")
}

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) = scenario(log) { trades ->
        trades.first().price to trades.last().price                 // 收到的第一筆、最後一筆
    }
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun run(log: LabLog) = scenario(log) { trades ->
        val ordered = trades.sortedWith(compareBy({ it.ts }, { it.tradeId }))   // 先依（時間, 成交編號）排序
        ordered.first().price to ordered.last().price
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "收盤價是最後收到的那一筆，不是最後發生的",
    question = "第 2 題：怎麼把一筆一筆的成交聚合成 K 線？",
    goal = "聚合成交時，open 是時間最早的那一筆、close 是時間最晚的那一筆，不是陣列的第一筆和最後一筆：要先依（時間, 成交編號）排序。",
    steps = listOf(
        "選「Bug 版」執行，看 open 和 close，和正確答案比一比。",
        "想一想：重連補資料時，成交為什麼會亂序？「收到的最後一筆」和「最後發生的一筆」差在哪？",
        "選「修正版」執行，open 要是 4790000、close 要是 4810000。",
        "進階：同一毫秒有好幾筆成交時，用什麼決定先後？（成交編號 tradeId，排序的鍵是 (ts, tradeId)）",
    ),
    expectBug = "「最後：open 4800000，close 4780000，…」：收到的第一筆與最後一筆，和正確答案不同。",
    expectFixed = "「最後：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4」。",
    file = "exchange07-kline/L02_OpenCloseOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, openClose) 執行"
            lines.none { it.startsWith("最後：open 4800000，close 4780000") } -> "Bug 還沒重現：open 取 trades.first().price、close 取 trades.last().price（收到的順序，不排序）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, openClose) 執行"
            lines.none { it.startsWith("最後：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4") } ->
                "先依（ts, tradeId）排序，open 取排序後的第一筆、close 取排序後的最後一筆"
            else -> null
        }
    },
)
