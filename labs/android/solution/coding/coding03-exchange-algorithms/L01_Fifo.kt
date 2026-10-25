// L01：同價格的賣單，後掛的先成交了（撮合沒有照時間先後，FIFO 被插隊）
// 對應複習站：Coding03ExchangeAlgorithms 第 1 題「撮合引擎怎麼決定誰和誰成交、用什麼價格？」
//
// 情境：賣方的掛單用 List 存，價格由低到高。依序收到 A（100）、D（101）、B（100）、C（100）四張賣單，
//       其中 A、B、C 都是 100 円、各 1 個，掛單的先後是 A → B → C。接著有人用限價 100 買 2 個，
//       依「價格優先、時間優先」，應該和 A、B 成交。
// Bug 版：插入新賣單時，找「第一個價格 >= 新價格」的位置插進去，新單就插在同價格那一排的最前面，後掛的變成先成交。
//        演練結果：和 C、B 成交，A 先掛卻沒有輪到。
// 修正版：找「第一個價格 > 新價格」的位置，新單排在同價格那一排的最後面，同價格先掛的先成交（FIFO，先進先出）。
package tw.harry.labs.solution.coding03exchangealgorithms

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：依序掛上四張賣單（insertIndex 決定插在 List 的哪裡），再用限價 100 買 2 個 */
private fun scenario(log: LabLog, insertIndex: (List<C03Order>, Int) -> Int) {
    val asks = ArrayList<C03Order>()
    for (order in listOf(C03Order("A", 100, 1), C03Order("D", 101, 1), C03Order("B", 100, 1), C03Order("C", 100, 1))) {
        asks.add(insertIndex(asks, order.price), order)
    }
    log("掛單的先後：A、D、B、C。賣單簿（由低價到高價）：${c03OrdersText(asks)}")

    var remaining = 2
    val trades = ArrayList<C03Trade>()
    while (remaining > 0 && asks.isNotEmpty() && asks[0].price <= 100) {
        val best = asks[0]
        val fill = minOf(remaining, best.qty)
        trades.add(C03Trade(best.id, best.price, fill))
        remaining -= fill
        best.qty -= fill
        if (best.qty == 0) asks.removeAt(0)
    }
    log("買進 100 × 2 → 成交 ${c03TradesText(trades)}")
}

// MARK: - Bug 版
private object L01Bug {
    /** 新賣單該插在 List 的哪個位置 */
    fun insertIndex(asks: List<C03Order>, price: Int): Int {
        val index = asks.indexOfFirst { it.price >= price }             // >=：新單插在同價格那一排的最前面
        return if (index < 0) asks.size else index
    }

    suspend fun run(log: LabLog) = scenario(log, ::insertIndex)
}

// MARK: - 修正版
private object L01Fixed {
    fun insertIndex(asks: List<C03Order>, price: Int): Int {
        val index = asks.indexOfFirst { it.price > price }              // >：新單排在同價格那一排的最後面
        return if (index < 0) asks.size else index
    }

    suspend fun run(log: LabLog) = scenario(log, ::insertIndex)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "同價格後掛的賣單，先成交了",
    question = "第 1 題：撮合引擎怎麼決定誰和誰成交、用什麼價格？",
    goal = "價格優先、時間優先：同價格的掛單，先掛的先成交（FIFO）。新單要排在同價格那一排的最後面。",
    steps = listOf(
        "選「Bug 版」執行，看賣單簿裡 A、B、C 的順序，以及買進 100 × 2 之後和誰成交。",
        "選「修正版」執行，確認先掛的 A、B 先成交。",
        "想一想：插入位置用 >= 或 > 差在哪？如果只有 A、B、C 三張同價格的單，這個差別看得出來嗎？",
    ),
    expectBug = "賣單簿 [C 100×1, B 100×1, A 100×1, D 101×1]；買進 100 × 2 → 成交 [C:1@100, B:1@100]，先掛的 A 沒輪到。",
    expectFixed = "賣單簿 [A 100×1, B 100×1, C 100×1, D 101×1]；買進 100 × 2 → 成交 [A:1@100, B:1@100]。",
    file = "coding03-exchange-algorithms/L01_Fifo.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("買進 100 × 2") } -> "演練沒有跑完：要用 scenario(log, insertIndex) 執行"
            "買進 100 × 2 → 成交 [C:1@100, B:1@100]" !in lines ->
                "Bug 還沒重現：新單要插在「第一個價格 >= 新價格」的位置，後掛的 C 才會排在同價格的最前面"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("買進 100 × 2") } -> "演練沒有跑完：要用 scenario(log, insertIndex) 執行"
            "買進 100 × 2 → 成交 [A:1@100, B:1@100]" !in lines ->
                "賣單簿要由低價到高價排好，同價格依掛單先後：新單插在「第一個價格 > 新價格」的位置（找不到就放最後）"
            else -> null
        }
    },
)
