// L05：只要前 10 名，卻把 2 萬筆全部排序（比較次數差 10 倍以上）
// 對應複習站：Coding01DataStructures 第 4 題「排行榜取前 k 名，要排序還是用
// heap？」（也是第 2 題「時間複雜度怎麼用白話講」的實測）
//
// 情境：逐筆成交的統計表有 2 萬個項目，要取成交量最高的前 10 名。成交量一樣時，
// 依代號字母排序。
//       演練用計數器數「比較了幾次」，看兩種做法的成本。
// （幣種只有幾百個的排行榜，排序完全夠用，這裡是 n 很大、k 很小的情況。）
//
// 你要寫：
//   ① Bug 版（L05Bug.topK）：entries.sortedWith 全部排序（比較函式裡先 counter.
// count++，再依 isBetter 回傳 -1、1 或 0），取前 k 個。
//   ② 修正版（L05Fixed.topK）：用大小為 k 的 min-heap（C01MinHeap）。heap
// 最上面是目前保留的最差的一筆；
//      還沒滿 k 筆就 push；滿了就和 heap.top 比一次（counter.count++），
// 新的比較好才 replaceTop。最後由好到差回傳。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/coding/coding01-data-structures/L05_TopKHeap.kt
package tw.harry.labs.practice.coding01datastructures

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
private fun isBetter(lhs: C01Entry, rhs: C01Entry): Boolean = if (lhs.volume !=
    rhs.volume
) {
    lhs.volume > rhs.volume
} else {
    lhs.symbol < rhs.symbol
}

/** 從「…比較了 N 次」那一行取出 N（自動檢查用） */
private fun comparisons(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("比較了") && it.endsWith(" 次") }
        ?.substringAfter("比較了 ")?.removeSuffix(" 次")?.toIntOrNull()

/**
 * 演練流程（兩個版本共用）：產生資料 → 取前 10 名 → 數比較次數，
 * 並和標準答案比對
 */
private fun scenario(
    log: LabLog,
    topK: (List<C01Entry>, Int, C01Counter) -> List<C01Entry>,
) {
    val entries = makeEntries()
    val counter = C01Counter()
    val result = topK(entries, K, counter)
    val expected = entries.sortedWith {
        a,
        b,
        ->
        if (isBetter(a, b)) {
            -1
        } else if (isBetter(b, a)) {
            1
        } else {
            0
        }
    }.take(K)
    log("$COUNT 筆資料取前 $K 名：比較了 ${counter.count} 次")
    log("前 $K 名和標準答案一致：${if (result == expected) "是" else "否"}")
    log(
        "最高的是 ${result.firstOrNull()?.symbol ?: "-"}，成交量 ${result.firstOrNull()?.volume ?: 0}",
    )
}

// MARK: - Bug 版
private object L05Bug {
    fun topK(
        entries: List<C01Entry>,
        k: Int,
        counter: C01Counter,
    ): List<C01Entry> {
        // TODO ①-1：entries.sortedWith { a, b -> … }：每比較一次先 counter.
        // count++，再回傳 isBetter(a, b) 為真 -1、isBetter(b, a) 為真 1、否則 0
        // TODO ①-2：全部排好之後，只取前 k 個回傳
        return emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 修正版
private object L05Fixed {
    fun topK(
        entries: List<C01Entry>,
        k: Int,
        counter: C01Counter,
    ): List<C01Entry> {
        if (k <= 0) return emptyList()
        // TODO ②-1：建立 heap：val heap = C01MinHeap<C01Entry> { lhs, rhs -> …
        // }。heap 最上面要是「目前保留的 k 筆裡最差的」，
        //          所以 isLess(lhs, rhs) 代表 lhs 比 rhs 差：先 counter.
        // count++，再回傳 isBetter(rhs, lhs)
        // TODO ②-2：逐一處理 entries：heap.count < k 就 heap.push(entry)；
        // 否則拿 heap.top 當 worst，counter.count++，
        //          entry 比 worst 好（isBetter(entry, worst)）才 heap.
        // replaceTop(entry)
        // TODO ②-3：heap.drainAscending() 是由差到好，反過來（reversed）
        // 就是由好到差，回傳
        return emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log, ::topK)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "只要前 10 名，卻排了 2 萬筆",
    question = "第 4 題：排行榜取前 k 名，要排序還是用 heap？（第 2 題：時間複雜度怎麼用白話講）",
    goal = "n 很大、k 很小時，用大小為 k 的 min-heap：每筆資料只和 heap 最差的比一次，比較次數從 O(n log n) 降到接近 n。先寫出全部排序的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/coding/coding01-data-structures/L05_TopKHeap.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看「比較了幾次」，以及前 10 名是否和標準答案一致。",
        "寫 TODO ②（修正版），選「練習」「修正版」執行，比較兩個版本的次數。",
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
