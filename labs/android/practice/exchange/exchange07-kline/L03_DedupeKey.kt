// L03：重連補資料之後，成交筆數和成交量反而變少了（去重用的是 27 位數的 `id`，
// 轉成 Double 之後不同的成交變成同一個）
// 對應複習站：Exchange07Kline 第 2 題「怎麼把一筆一筆的成交聚合成 K 線？」
//
// 情境：逐筆成交的 JSON 有兩個編號：`tradeId`（成交編號，一般的整數）和
// `id`（27 位數的數字，例如 137005445109359286410323766）。
//        重連之後收到 5 則：成交編號 1、2、2（重送的同一筆）、3、4；4
// 筆不同的成交，數量 0.2、0.1、0.3、0.05。
//        這 5 則的 `id` 只差最後幾位數，其中成交編號 1、2、3、4 的 `id`
// 各不相同，但超出 Double 能精確表示的範圍。
//        正確答案：去重後 4 筆，數量合計 0.65。
//
// 你要寫：
//   ① Bug 版（L03Bug.run 的 key 閉包）：用 trade.id（27 位數，已經被轉成
// Double）轉成字串當 key。
//   ② 修正版（L03Fixed.run 的 key 閉包）：用 trade.tradeId 轉成字串當 key。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange07-kline/L03_DedupeKey.kt
package tw.harry.labs.practice.exchange07kline

import com.google.gson.Gson
import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L03Trade(
    val tradeId: Long,
    // 27 位數：Long 放不下，一般的 JSON 解析只能轉成 Double
    val id: Double,
    val price: Double,
    val amount: Double,
)

private const val JSON = """
[{"tradeId":1,"id":137005445109359286410323766,"price":4800000,"amount":0.2},
 {"tradeId":2,"id":137005445109359286410323767,"price":4790000,"amount":0.1},
 {"tradeId":2,"id":137005445109359286410323767,"price":4790000,"amount":0.1},
 {"tradeId":3,"id":137005445109359286410323768,"price":4810000,"amount":0.3},
 {"tradeId":4,"id":137005445109359286410323769,"price":4780000,"amount":0.05}]
"""

/**
 * 演練流程（兩個版本共用）：`key` 決定怎麼辨認「同一筆成交」，同樣的 key
 * 只算一次
 */
private fun scenario(log: LabLog, key: (L03Trade) -> String) {
    val trades = Gson().fromJson(JSON, Array<L03Trade>::class.java).toList()
    val seen = HashSet<String>()
    val kept = trades.filter { seen.add(key(it)) }
    val amount = kept.fold(BigDecimal.ZERO) { sum, trade ->
        sum +
            BigDecimal(trade.amount.toString())
    }
    log("收到 ${trades.size} 則，去重後 ${kept.size} 筆，數量合計 ${e07Text(amount)}")
    log("最後：去重後 ${kept.size} 筆，數量合計 ${e07Text(amount)}（正確答案：4 筆，0.65）")
}

// MARK: - Bug 版
private object L03Bug {
    suspend fun run(log: LabLog) = scenario(log) { trade ->
        // TODO ①：用 trade.id.toString() 當 key。目前每則都回傳不同的
        // UUID（等於不去重）只是佔位，要改掉
        java.util.UUID.randomUUID().toString()
    }
}

// MARK: - 修正版
private object L03Fixed {
    suspend fun run(log: LabLog) = scenario(log) { trade ->
        // TODO ②：用 trade.tradeId.toString() 當 key。目前每則都回傳不同的
        // UUID（等於不去重）只是佔位，要改掉
        java.util.UUID.randomUUID().toString()
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "補資料之後，成交筆數反而變少",
    question = "第 2 題：怎麼把一筆一筆的成交聚合成 K 線？",
    goal = "成交去重要用 `tradeId`（REST 是 `trade-id`），不要用逐筆資料裡 27 位數的 `id`：它超出 Long 和 Double 能精確表示的範圍，不同的成交會變成同一個。先寫出用 id 去重的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange07-kline/L03_DedupeKey.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看去重後剩幾筆、數量合計多少，和正確答案比一比。",
        "想一想：5 則的 `id` 看起來不一樣（最後一位數不同），為什麼轉成 Double 之後變成同一個？（Double 約 16 位有效數字）",
        "寫 TODO ②（修正版），選「修正版」執行，要去重後 4 筆、數量合計 0.65。",
        "進階：非用 `id` 不可的時候，要當字串解析（不經過數字型別）。",
    ),
    expectBug = "「收到 5 則，去重後 1 筆，數量合計 0.2」：不同的成交被當成重複丟掉，連真正重送的那一筆都分不出來。",
    expectFixed = "「收到 5 則，去重後 4 筆，數量合計 0.65」：只丟掉重送的那一筆。",
    file = "exchange07-kline/L03_DedupeKey.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, key) 執行"

            lines.none {
                it.startsWith("最後：去重後 1 筆，數量合計 0.2")
            } -> "Bug 還沒重現：key 要用 trade.id（Double）轉成的字串，5 則的 id 轉成 Double 之後是同一個值"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, key) 執行"

            lines.none {
                it.startsWith("最後：去重後 4 筆，數量合計 0.65")
            } -> "key 要用 trade.tradeId 轉成的字串：成交編號相同的才是重複，應該去重後 4 筆"

            else -> null
        }
    },
)
