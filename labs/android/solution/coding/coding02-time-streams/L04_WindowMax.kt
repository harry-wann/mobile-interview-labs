// L04：最近 500 毫秒的最高價，每筆成交都往回掃 249 筆（暴力法，處理次數是 n 乘以視窗大小）
// 對應複習站：Coding02TimeStreams 第 4 題「「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？」
//
// 情境：成交依時間排序，每 2 毫秒一筆，共 3000 筆。對每一筆成交，算出時間在 (t − 500, t] 之內的最高價。
//       演練用計數器數「處理了幾次」，同時和標準答案比對（兩種做法算出來的結果是一樣的，差的是成本）。
// Bug 版：每一筆都往回看整個視窗找最大值。視窗裡有 249 筆，3000 筆就是 70 多萬次，資料多一倍、視窗大一倍，成本就變 4 倍。
// 修正版：單調佇列（monotonic queue）。存成交的索引，價格由大到小：新成交進來，先把尾端價格 ≤ 新價格的丟掉，再放進去；
//        再把前端已經過期的丟掉；前端就是答案。每筆最多進出佇列各一次，總共不超過 3 倍筆數。（ArrayDeque 兩端都是 O(1)。）
package tw.harry.labs.solution.coding02timestreams

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val WINDOW_MS = 500

/** 固定種子的偽亂數，每次產生一模一樣的成交 */
private fun makeTrades(): List<C02Tick> {
    var state = 7L
    return List(3000) { i ->
        state = state * 6364136223846793005L + 1442695040888963407L
        C02Tick(i * 2, 15_000_000 + (state ushr 33).toInt() % 100_000)
    }
}

/** 標準答案（不計次數的暴力法） */
private fun reference(trades: List<C02Tick>, window: Int): List<Int> = trades.indices.map { i ->
    var best = trades[i].price
    var back = i - 1
    while (back >= 0 && trades[back].time > trades[i].time - window) {
        best = maxOf(best, trades[back].price)
        back--
    }
    best
}

/** 演練流程（兩個版本共用）：算每一筆成交的視窗最高價，數處理次數，並和標準答案比對 */
private fun scenario(log: LabLog, windowMax: (List<C02Tick>, Int, C02Counter) -> List<Int>) {
    val trades = makeTrades()
    val counter = C02Counter()
    val result = windowMax(trades, WINDOW_MS, counter)
    log("${trades.size} 筆成交、視窗 $WINDOW_MS 毫秒：處理了 ${counter.steps} 次")
    log("結果和標準答案一致：${if (result == reference(trades, WINDOW_MS)) "是" else "否"}")
}

/** 從「…處理了 N 次」取出 N（自動檢查用） */
private fun steps(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("處理了 ") && it.endsWith(" 次") }?.substringAfter("處理了 ")?.removeSuffix(" 次")?.toIntOrNull()

// MARK: - Bug 版
private object L04Bug {
    fun windowMax(trades: List<C02Tick>, window: Int, counter: C02Counter): List<Int> {
        val result = ArrayList<Int>()
        for ((index, trade) in trades.withIndex()) {
            var best = trade.price
            var back = index - 1
            while (back >= 0 && trades[back].time > trade.time - window) {   // 往回看整個視窗
                counter.steps++
                best = maxOf(best, trades[back].price)
                back--
            }
            result.add(best)
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::windowMax)
}

// MARK: - 修正版
private object L04Fixed {
    fun windowMax(trades: List<C02Tick>, window: Int, counter: C02Counter): List<Int> {
        val result = ArrayList<Int>()
        val queue = ArrayDeque<Int>()                                        // 存成交的索引，價格由大到小
        for ((index, trade) in trades.withIndex()) {
            while (queue.isNotEmpty() && trades[queue.last()].price <= trade.price) {
                queue.removeLast()                                           // 尾端比較便宜的，以後不可能再是最高價
                counter.steps++
            }
            queue.addLast(index)
            counter.steps++
            while (trades[queue.first()].time <= trade.time - window) {
                queue.removeFirst()                                          // 前端已經過期
                counter.steps++
            }
            result.add(trades[queue.first()].price)                          // 前端就是視窗內的最高價
        }
        return result
    }

    suspend fun run(log: LabLog) = scenario(log, ::windowMax)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "每筆成交都往回掃整個視窗",
    question = "第 4 題：「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？",
    goal = "用單調佇列讓每筆成交最多進出佇列各一次，總處理次數不超過筆數的 3 倍，不隨視窗變大而增加。",
    steps = listOf(
        "選「Bug 版」執行，看「處理了幾次」，以及結果和標準答案是否一致。",
        "選「修正版」執行，比較處理次數（結果一樣是正確的）。",
        "想一想：把視窗改成 1000 毫秒，兩個版本的次數各會變成幾倍？",
    ),
    expectBug = "結果正確，但「處理了 715875 次」左右（每筆往回看 249 筆）。",
    expectFixed = "結果一樣正確，處理次數只有約 6000 次（5988 次，不超過 9000 次，也就是筆數的 3 倍）。",
    file = "coding02-time-streams/L04_WindowMax.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val count = steps(lines)
        when {
            count == null -> "演練沒有跑完：要用 scenario(log, windowMax) 執行"
            "結果和標準答案一致：是" !in lines -> "windowMax 還沒寫對：每一筆往回找視窗 (t - window, t] 內的最大值"
            count < 150_000 -> "Bug 還沒重現：每一筆都要往回掃整個視窗（每看一筆 counter.steps++）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val count = steps(lines)
        when {
            count == null -> "演練沒有跑完：要用 scenario(log, windowMax) 執行"
            "結果和標準答案一致：是" !in lines ->
                "windowMax 還沒寫對：佇列前端要是視窗內的最高價（尾端價格不大於新價格的先丟掉，前端過期的也丟掉）"
            count > 9_000 -> "處理了 $count 次，太多了：每筆成交最多進佇列一次、出佇列一次"
            else -> null
        }
    },
)
