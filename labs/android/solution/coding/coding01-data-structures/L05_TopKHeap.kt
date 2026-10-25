// L05：只要前 10 名，卻把 2 萬筆全部排序（比較次數差 10 倍以上）
// 對應複習站：Coding01DataStructures 第 4 題「排行榜取前 k 名，要排序還是用 heap？」（也是第 2 題「時間複雜度怎麼用白話講」的實測）
//
// 情境：逐筆成交的統計表有 2 萬個項目，要取成交量最高的前 10 名。成交量一樣時，依代號字母排序。
//       演練用計數器數「比較了幾次」，看兩種做法的成本。（幣種只有幾百個的排行榜，排序完全夠用，這裡是 n 很大、k 很小的情況。）
// Bug 版：全部排序再取前 10 個。O(n log n)，2 萬筆要比較 20 多萬次，其中大部分是在排「根本不會顯示」的名次。
// 修正版：用大小為 k 的 min-heap（Java 的 PriorityQueue 也是同樣的做法），只保留目前最大的 10 筆。
//        新的一筆只要和 heap 最小的比一次，比它大才換進去。O(n log k)。
package tw.harry.labs.solution.coding01datastructures

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val COUNT = 20_000
private const val K = 10

/** 固定種子的偽亂數，每次產生一模一樣的資料 */
private fun makeEntries(): List<C01Entry> {
    var state = 2026L
    return List(COUNT) { i ->
        state = state * 6364136223846793005L + 1442695040888963407L
        C01Entry("COIN$i", (state ushr 33).toInt() % 1_000_000)
    }
}

/** 比較兩個項目誰「比較前面」：成交量大的在前；一樣大，代號字母順序在前 */
private fun isBetter(lhs: C01Entry, rhs: C01Entry): Boolean =
    if (lhs.volume != rhs.volume) lhs.volume > rhs.volume else lhs.symbol < rhs.symbol

/** 從「…比較了 N 次」那一行取出 N（自動檢查用） */
private fun comparisons(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("比較了") && it.endsWith(" 次") }
        ?.substringAfter("比較了 ")?.removeSuffix(" 次")?.toIntOrNull()

/** 演練流程（兩個版本共用）：產生資料 → 取前 10 名 → 數比較次數，並和標準答案比對 */
private fun scenario(log: LabLog, topK: (List<C01Entry>, Int, C01Counter) -> List<C01Entry>) {
    val entries = makeEntries()
    val counter = C01Counter()
    val result = topK(entries, K, counter)
    val expected = entries.sortedWith { a, b -> if (isBetter(a, b)) -1 else if (isBetter(b, a)) 1 else 0 }.take(K)
    log("$COUNT 筆資料取前 $K 名：比較了 ${counter.count} 次")
    log("前 $K 名和標準答案一致：${if (result == expected) "是" else "否"}")
    log("最高的是 ${result.firstOrNull()?.symbol ?: "-"}，成交量 ${result.firstOrNull()?.volume ?: 0}")
}

// MARK: - Bug 版
private object L05Bug {
    fun topK(entries: List<C01Entry>, k: Int, counter: C01Counter): List<C01Entry> {
        val sorted = entries.sortedWith { a, b ->
            counter.count++                                       // 每比較一次記一筆
            if (isBetter(a, b)) -1 else if (isBetter(b, a)) 1 else 0
        }
        return sorted.take(k)                                     // 全部排好，只取前 k 個
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 修正版
private object L05Fixed {
    fun topK(entries: List<C01Entry>, k: Int, counter: C01Counter): List<C01Entry> {
        if (k <= 0) return emptyList()
        // heap 最上面是「目前保留的 k 筆裡最差的」：isLess(a, b) 代表 a 比 b 差
        val heap = C01MinHeap<C01Entry> { lhs, rhs ->
            counter.count++                                       // 每比較一次記一筆
            isBetter(rhs, lhs)
        }
        for (entry in entries) {
            if (heap.count < k) {
                heap.push(entry)                                  // 還沒滿 k 筆：直接放進去
            } else {
                val worst = heap.top ?: continue
                counter.count++
                if (isBetter(entry, worst)) heap.replaceTop(entry)    // 比最差的好，才換進去
            }
        }
        return heap.drainAscending().reversed()                   // 由差到好，反過來就是由好到差
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "只要前 10 名，卻排了 2 萬筆",
    question = "第 4 題：排行榜取前 k 名，要排序還是用 heap？（第 2 題：時間複雜度怎麼用白話講）",
    goal = "n 很大、k 很小時，用大小為 k 的 min-heap：每筆資料只和 heap 最差的比一次，比較次數從 O(n log n) 降到接近 n。",
    steps = listOf(
        "選「Bug 版」執行，看「比較了幾次」，以及前 10 名是否和標準答案一致。",
        "選「修正版」執行，比較兩個版本的次數。",
        "對照第 4 題：為什麼幣種只有幾百個的排行榜，直接排序就夠了？",
    ),
    expectBug = "2 萬筆全部排序，比較 26 萬到 39 萬次（iOS 約 39 萬、Android 約 26 萬，排序演算法不同），一定超過 10 萬次。",
    expectFixed = "比較約 2 萬次出頭（每筆和 heap 最差的比一次，少數幾次換進去時多比幾次），前 10 名一致。",
    file = "coding01-data-structures/L05_TopKHeap.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val count = comparisons(lines)
        when {
            count == null -> "演練沒有跑完：要用 scenario(log, topK) 執行"
            "前 10 名和標準答案一致：是" !in lines -> "topK 還沒寫：排序之後取前 10 個"
            count < 100_000 -> "Bug 還沒重現：要把 2 萬筆全部排序（sortedWith），比較次數才會超過 10 萬次"
            else -> null
        }
    },
    checkFixed = { lines ->
        val count = comparisons(lines)
        when {
            count == null -> "演練沒有跑完：要用 scenario(log, topK) 執行"
            "前 10 名和標準答案一致：是" !in lines -> "前 10 名不對：heap 最上面要是目前最差的，比它好才換進去，最後由好到差輸出"
            count >= 60_000 -> "比較了 $count 次，太多了：新的一筆只要和 heap.top 比一次，比它好才 replaceTop"
            else -> null
        }
    },
)
