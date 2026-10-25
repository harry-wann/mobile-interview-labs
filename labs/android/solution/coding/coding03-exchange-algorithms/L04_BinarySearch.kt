// L04：手指剛好按在一根 K 線的開始時間上，十字線卻跳到前一根；比第一根還早時回傳 -1（二分搜尋的邊界）
// 對應複習站：Coding03ExchangeAlgorithms 第 2 題「手指在 K 線圖上拖曳時，怎麼快速找到對應的 K 線？」
//
// 情境：K 線依開始時間排序。手指換算成時間 t 後，要找「開始時間 <= t 的最後一根」的索引；t 比第一根還早就回傳 null。
//       演練用 6 組固定的資料檢查答案，最後再用 10 萬根 K 線數「探測了幾次」。
// Bug 版：搜尋的是「第一根 >= t」（`times[mid] < t` 才往右），再回傳它的前一根 `lo - 1`。
//        t 剛好等於某一根的開始時間時，答案少一根；t 比第一根還早時回傳 -1（不是 null），拿去當索引會丟出 IndexOutOfBoundsException。
// 修正版：搜尋的是「第一根 > t」（`times[mid] <= t` 就往右），答案是它的前一根；`lo == 0` 代表 t 比第一根還早，回傳 null。
package tw.harry.labs.solution.coding03exchangealgorithms

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L04Case(val title: String, val times: List<Int>, val t: Int, val expected: Int?)

private val cases = listOf(
    L04Case("落在兩根之間", listOf(100, 160, 220, 280, 340), 250, 2),
    L04Case("剛好是某一根的開始時間", listOf(100, 160, 220, 280, 340), 280, 3),
    L04Case("比第一根還早", listOf(100, 160, 220, 280, 340), 50, null),
    L04Case("比最後一根還晚", listOf(100, 160, 220, 280, 340), 1000, 4),
    L04Case("有重複的時間，取最後一根", listOf(10, 20, 20, 20, 30), 20, 3),
    L04Case("沒有 K 線", emptyList(), 100, null),
)

private fun show(value: Int?): String = value?.toString() ?: "null"

/** 演練流程（兩個版本共用）：6 組資料各找一次，再用 10 萬根 K 線數探測次數 */
private fun scenario(log: LabLog, search: (times: List<Int>, t: Int, probe: C03Probe) -> Int?) {
    var right = 0
    cases.forEachIndexed { index, item ->
        val got = search(item.times, item.t, C03Probe())
        val ok = got == item.expected
        if (ok) right++
        log("案例 ${index + 1}（${item.title}）：t=${item.t} → 回傳 ${show(got)}，期待 ${show(item.expected)} ${if (ok) "✓" else "✗"}")
    }
    log("答對 $right/${cases.size}")
    val probe = C03Probe()
    val big = List(100_000) { it * 60 }
    search(big, 3_000_030, probe)
    log("10 萬根 K 線找一次，探測了 ${probe.count} 次")
}

/** 從「…探測了 N 次」取出 N（自動檢查用） */
private fun probes(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("10 萬根 K 線找一次，探測了 ") }
        ?.removePrefix("10 萬根 K 線找一次，探測了 ")?.removeSuffix(" 次")?.toIntOrNull()

// MARK: - Bug 版
private object L04Bug {
    fun search(times: List<Int>, t: Int, probe: C03Probe): Int? {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            probe.count++
            if (times[mid] < t) {                  // 用 <：找的是「第一根 >= t」
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo - 1                              // lo 是 0 時回傳 -1，沒有檢查
    }

    suspend fun run(log: LabLog) = scenario(log, ::search)
}

// MARK: - 修正版
private object L04Fixed {
    fun search(times: List<Int>, t: Int, probe: C03Probe): Int? {
        var lo = 0
        var hi = times.size                        // 搜尋範圍是 [lo, hi)：包含 lo，不包含 hi
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            probe.count++
            if (times[mid] <= t) {                 // 用 <=：找的是「第一根 > t」
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return if (lo == 0) null else lo - 1       // lo 是 0 代表 t 比第一根還早，找不到
    }

    suspend fun run(log: LabLog) = scenario(log, ::search)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "十字線跳到前一根，找不到時回傳 -1",
    question = "第 2 題：手指在 K 線圖上拖曳時，怎麼快速找到對應的 K 線？",
    goal = "先決定搜尋範圍是 [lo, hi)，找「第一根 > t」，答案是它的前一根；lo 是 0 就是找不到，回傳 null。",
    steps = listOf(
        "選「Bug 版」執行，看案例 2（剛好落在開始時間）、案例 3（比第一根早）、案例 5（重複時間）各回傳什麼。",
        "選「修正版」執行，確認 6 個案例全部答對，最後一行的探測次數不到 20 次。",
        "想一想：回傳 -1 的版本，如果呼叫端直接拿它當 List 索引，會發生什麼事？",
    ),
    expectBug = "案例 2 回傳 2（期待 3）、案例 3 回傳 -1（期待 null）、案例 5 回傳 0（期待 3）、案例 6 回傳 -1；6 組只答對 2 組。",
    expectFixed = "「答對 6/6」，10 萬根 K 線只探測 17 次。",
    file = "coding03-exchange-algorithms/L04_BinarySearch.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("答對") } -> "演練沒有跑完：要用 scenario(log, search) 執行"
            "案例 2（剛好是某一根的開始時間）：t=280 → 回傳 2，期待 3 ✗" !in lines ->
                "Bug 還沒重現：times[mid] 小於 t 才往右，找的是「第一根 >= t」，再回傳 lo - 1，t 剛好等於開始時間時會少一根"
            "案例 3（比第一根還早）：t=50 → 回傳 -1，期待 null ✗" !in lines ->
                "Bug 還沒重現：直接回傳 lo - 1，不檢查 lo 是不是 0，t 比第一根還早時就會回傳 -1"
            else -> null
        }
    },
    checkFixed = { lines ->
        val wrong = lines.firstOrNull { it.startsWith("案例") && it.endsWith("✗") }
        val count = probes(lines)
        when {
            lines.none { it.startsWith("答對") } -> "演練沒有跑完：要用 scenario(log, search) 執行"
            wrong != null -> "還有案例答錯：$wrong"
            count == null -> "找不到探測次數那一行"
            count > 20 -> "10 萬根 K 線探測了 $count 次，太多了：每一步要把範圍砍一半（二分搜尋）"
            else -> null
        }
    },
)
