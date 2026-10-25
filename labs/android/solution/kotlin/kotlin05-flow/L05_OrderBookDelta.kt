// L05：本機的掛單簿和交易所對不上（order book 的增量更新不能 conflate）
// 對應複習站：Kotlin05Flow 第 4 題「行情資料進 UI 時，conflate、sample、debounce 差在哪？」
//
// 情境：交易所用 delta（增量更新）推送 order book（買賣掛單的完整清單）：每筆 delta 是「某個價位現在有多少數量」。
//        共 60 筆，每筆更新不同的價位，每 1 ms 一筆。本機要靠依序套用每一筆 delta，維護出和交易所一樣的 order book。
// Bug 版：在收集端對 delta 串流用 conflate()，收集端套用一筆要 5 ms，比來得慢，中間的 delta 被丟掉，
//        少了一些價位，本機的 order book 和交易所對不上（而且之後的增量更新全都建立在錯的基礎上）。
// 修正版：delta 一筆都不能丟：用一個 coroutine 依序套用每一筆（很快），維護本機 order book；
//        再把「快照」（某一刻的完整內容）發佈成 StateFlow，慢的 UI 只看最新的快照（StateFlow 本來就只保留最新值）。
package tw.harry.labs.solution.kotlin05flow

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Delta(val price: Int, val qty: Int)

/** 交易所推送的 delta：60 筆，價位 100 到 159，每 1 ms 一筆 */
private fun deltas(): Flow<Delta> = flow {
    for (i in 0 until 60) {
        emit(Delta(price = 100 + i, qty = i + 1))
        delay(1)
    }
}

/** 交易所那邊真正的 order book：每個價位的數量 */
private val serverBook: Map<Int, Int> = (0 until 60).associate { 100 + it to it + 1 }

private fun report(log: LabLog, localBook: Map<Int, Int>) {
    log("本機 order book ${localBook.size} 個價位，交易所 ${serverBook.size} 個價位")
    log(if (localBook == serverBook) "兩邊一致" else "兩邊對不上")
}

// MARK: - Bug 版
private object L05Bug {
    suspend fun run(log: LabLog) {
        val localBook = HashMap<Int, Int>()
        deltas().conflate().collect { delta ->                  // 收集端比較慢，conflate 把中間的 delta 丟掉
            delay(5)                                            // 套用一筆並更新畫面要 5 ms
            localBook[delta.price] = delta.qty
        }
        report(log, localBook)
    }
}

// MARK: - 修正版
private object L05Fixed {
    suspend fun run(log: LabLog) {
        val localBook = HashMap<Int, Int>()                     // 只有下面這一個 coroutine 會動它
        val snapshots = MutableStateFlow<Map<Int, Int>>(emptyMap())
        var shownByUi: Map<Int, Int> = emptyMap()
        coroutineScope {
            val ui = launch {
                snapshots.collect { snapshot ->                 // UI 很慢，StateFlow 只保留最新的快照，剛好
                    delay(20)
                    shownByUi = snapshot
                }
            }
            deltas().collect { delta ->                         // 一筆都不丟：依序套用
                localBook[delta.price] = delta.qty
                snapshots.value = localBook.toMap()             // 對外發佈快照
            }
            delay(100)                                          // 讓 UI 追上最新的快照
            ui.cancelAndJoin()
        }
        report(log, shownByUi)
    }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "本機的 order book 和交易所對不上",
    question = "第 4 題：行情資料進 UI 時，conflate、sample、debounce 差在哪？",
    goal = "order book 的增量更新每一筆都不能丟。看 conflate 丟掉 delta 之後本機少了哪些價位，再改成「依序套用、發佈快照」。",
    steps = listOf(
        "選「Bug 版」執行，看「本機 order book N 個價位」是不是少於 60，以及「兩邊一致」還是「對不上」。",
        "選「修正版」執行，確認 60 個價位都在，兩邊一致。",
        "想一想：快照可以 conflate 嗎？（可以，快照是完整內容，丟掉舊的沒關係；delta 不行，因為每一筆都建立在前一筆之上）",
    ),
    expectBug = "「本機 order book」少於 60 個價位（電腦上的 JVM 實測 18 個），「兩邊對不上」。",
    expectFixed = "「本機 order book 60 個價位，交易所 60 個價位」，「兩邊一致」。",
    file = "kotlin05-flow/L05_OrderBookDelta.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("本機 order book ") } -> "演練沒有跑完：收集 deltas() 之後要呼叫 report(log, localBook)"
            "兩邊對不上" !in lines -> "Bug 版 order book 要對不上：收集端對 deltas() 加 conflate()，再用 delay(5) 慢慢套用"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("本機 order book ") } -> "演練沒有跑完：最後要呼叫 report(log, …)"
            "兩邊一致" !in lines -> "order book 對不上：delta 一筆都不能丟，要依序套用每一筆（不要 conflate），再發佈快照給 UI"
            else -> null
        }
    },
)
