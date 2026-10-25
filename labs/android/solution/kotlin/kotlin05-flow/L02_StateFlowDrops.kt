// L02：5 筆成交回報，只處理了最後 1 筆（StateFlow 只保留最新值）
// 對應複習站：Kotlin05Flow 第 2 題「StateFlow、SharedFlow、Channel 怎麼選？」
//
// 情境：交易所推送成交回報，每一筆都要寫進成交紀錄，漏掉一筆就是帳對不起來。5
// 筆成交在同一瞬間到達。
// Bug 版：用 MutableStateFlow 傳成交回報。StateFlow 只保留最新值，collector
// 還沒來得及處理，
//        中間 4 筆就被後面的蓋掉了，成交紀錄只寫進最後 1 筆。
// 修正版：每一筆都要處理的工作用 Channel（點對點、有緩衝），收集端用
// receiveAsFlow()，5 筆全部依序處理。
package tw.harry.labs.solution.kotlin05flow

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Fill(val id: Int)

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) {
        // StateFlow：只保留最新值
        val fills = MutableStateFlow<Fill?>(null)
        coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                fills.collect { fill ->
                    if (fill !=
                        null
                    ) {
                        log("寫入成交紀錄 #${fill.id}")
                    }
                }
            }
            // 5 筆同時到達：中間的被蓋掉
            repeat(5) { fills.value = Fill(it + 1) }
            delay(100)
            collector.cancel()
        }
    }
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun run(log: LabLog) {
        // Channel：每一筆都保留到有人處理
        val fills = Channel<Fill>(Channel.BUFFERED)
        coroutineScope {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                fills.receiveAsFlow().collect { fill ->
                    log("寫入成交紀錄 #${fill.id}")
                }
            }
            repeat(5) { fills.send(Fill(it + 1)) }
            delay(100)
            collector.cancel()
        }
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "5 筆成交回報，只處理了最後 1 筆",
    question = "第 2 題：StateFlow、SharedFlow、Channel 怎麼選？",
    goal = "5 筆成交回報同一瞬間到達，每一筆都要寫進成交紀錄。看 StateFlow 漏掉幾筆，再改用 Channel。",
    steps = listOf(
        "選「Bug 版」執行，數「寫入成交紀錄」出現幾次、是哪幾筆。",
        "選「修正版」執行，確認 5 筆全部依序寫入。",
        "想一想：畫面上的「目前餘額」適合用 StateFlow 嗎？為什麼？（適合，畫面只在意現在是什麼，中間的值不重要）",
    ),
    expectBug = "只印出「寫入成交紀錄 #5」，#1 到 #4 沒有被處理。",
    expectFixed = "依序印出「寫入成交紀錄 #1」到「#5」，一筆都沒少。",
    file = "kotlin05-flow/L02_StateFlowDrops.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val written = lines.count { it.startsWith("寫入成交紀錄 #") }
        when {
            written == 0 -> "沒有寫入任何成交紀錄：collector 要收集 fills 並 log(\"寫入成交紀錄 #\${fill.id}\")"
            written >= 5 -> "5 筆都處理了：Bug 版要用 MutableStateFlow，StateFlow 只保留最新值"
            else -> null
        }
    },
    checkFixed = { lines ->
        val written = lines.filter { it.startsWith("寫入成交紀錄 #") }
        when {
            written != List(5) {
                "寫入成交紀錄 #${it + 1}"
            } -> "5 筆要依序全部寫入，現在是 $written：改用 Channel(Channel.BUFFERED) 與 receiveAsFlow()"

            else -> null
        }
    },
)
