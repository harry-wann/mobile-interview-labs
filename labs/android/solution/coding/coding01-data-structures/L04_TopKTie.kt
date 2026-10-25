// L04：成交量一樣的幣種，每次更新排名都換位置（排行榜沒有處理數值相同）
// 對應複習站：Coding01DataStructures 第 4 題「排行榜取前 k 名，要排序還是用 heap？」
//
// 情境：成交量排行榜取前 3 名。ETH、XRP、ADA 三個幣種的成交量一樣（5100），第 3 名的位置只能留一個。
//       伺服器每次回傳的幣種順序不一定相同（Map 轉成 List，順序會變），演練用兩次更新模擬：第二次的順序剛好反過來。
// Bug 版：只比成交量，成交量一樣的幣種保持「輸入的順序」（sortedByDescending 是穩定排序）。第一次是 ETH 排進前 3 名，第二次變成 ADA，列表一直在跳。
// 修正版：成交量一樣時，再依代號字母排序。不管輸入順序是什麼，排名都一樣。
package tw.harry.labs.solution.coding01datastructures

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val update1 = listOf(
    C01Entry("BTC", 9200), C01Entry("ETH", 5100), C01Entry("XRP", 5100),
    C01Entry("ADA", 5100), C01Entry("DOGE", 7800), C01Entry("SOL", 900),
)
private val update2 = update1.reversed()

/** 演練流程（兩個版本共用）：同樣的成交量、不同的輸入順序，各取一次前 3 名 */
private fun scenario(log: LabLog, topK: (List<C01Entry>, Int) -> List<C01Entry>) {
    val first = topK(update1, 3).map { it.symbol }
    val second = topK(update2, 3).map { it.symbol }
    log("第 1 次更新（輸入順序 ${c01Text(update1.map { it.symbol })}）：前 3 名 ${c01Text(first)}")
    log("第 2 次更新（輸入順序 ${c01Text(update2.map { it.symbol })}）：前 3 名 ${c01Text(second)}")
    log("兩次排名相同：${if (first == second) "是" else "否"}")
}

// MARK: - Bug 版
private object L04Bug {
    fun topK(entries: List<C01Entry>, k: Int): List<C01Entry> {
        if (k <= 0) return emptyList()
        return entries.sortedByDescending { it.volume }.take(k)      // 成交量一樣時，沒有第二個排序條件
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 修正版
private object L04Fixed {
    fun topK(entries: List<C01Entry>, k: Int): List<C01Entry> {
        if (k <= 0) return emptyList()
        return entries
            .sortedWith(compareByDescending<C01Entry> { it.volume }.thenBy { it.symbol })   // 成交量一樣：代號字母順序在前
            .take(k)
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "成交量一樣，排名每次都換",
    question = "第 4 題：排行榜取前 k 名，要排序還是用 heap？",
    goal = "數值相同時要有固定的排序規則（再依代號排），輸入順序不同，排名也不能變。",
    steps = listOf(
        "選「Bug 版」執行，比較兩次更新的前 3 名：同樣的成交量，第 3 名為什麼不一樣？",
        "選「修正版」執行，確認兩次排名一樣，第 3 名是代號字母最前面的 ADA。",
        "想一想：真的 App 裡，這個排行榜每秒更新很多次，沒處理會看到什麼？",
    ),
    expectBug = "第 1 次前 3 名 [BTC, DOGE, ETH]，第 2 次 [BTC, DOGE, ADA]：「兩次排名相同：否」。",
    expectFixed = "兩次都是 [BTC, DOGE, ADA]：「兩次排名相同：是」。",
    file = "coding01-data-structures/L04_TopKTie.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("兩次排名相同") } -> "演練沒有跑完：要用 scenario(log, topK) 執行"
            lines.none { it.startsWith("第 1 次更新") && it.endsWith("前 3 名 [BTC, DOGE, ETH]") } ->
                "topK 還沒寫：用 sortedByDescending 依成交量由大到小排，再取前 k 個"
            "兩次排名相同：否" !in lines -> "Bug 還沒重現：成交量一樣時不能再比代號，排名才會跟著輸入順序變"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("兩次排名相同") } -> "演練沒有跑完：要用 scenario(log, topK) 執行"
            lines.none { it.startsWith("第 1 次更新") && it.endsWith("前 3 名 [BTC, DOGE, ADA]") } ->
                "前 3 名應該是 [BTC, DOGE, ADA]：成交量一樣時，依代號字母順序（ADA 在前）"
            "兩次排名相同：是" !in lines -> "兩次的輸入順序不同，排名卻不一樣：成交量一樣時要再比 symbol"
            else -> null
        }
    },
)
