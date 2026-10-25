// L04：行情一直在跳，畫面卻完全沒更新（debounce 不適合價格）
// 對應複習站：Kotlin05Flow 第 4 題「行情資料進 UI 時，conflate、sample、
// debounce 差在哪？」
//
// 情境：WebSocket 每 100 ms 推送一次價格，共 20 筆（約 2 秒）。畫面更新一次要
// 250 ms，比價格來得慢，所以要丟掉一些中間的價格。
// Bug 版：用 debounce(300) 壓低更新頻率。debounce 要「安靜」滿 300 ms 才發，
// 價格每 100 ms 就來一筆，從不安靜，
//        整個串流期間一筆都沒發，畫面一直停在舊價格，要等串流結束才更新一次。
// 修正版：用 conflate()。collector 忙的時候只保留最新的一筆，畫面持續更新，
// 而且永遠顯示最新價。
//        （sample(250) 也可以：固定週期取最新的一筆）
@file:OptIn(FlowPreview::class)

package tw.harry.labs.solution.kotlin05flow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 演練流程（兩個版本共用）：價格每 100 ms 一筆，共 20 筆；畫面更新一次要 250 ms。
 * [shape] 是 ViewModel 對價格串流做的處理。
 */
private suspend fun scenario(log: LabLog, shape: (Flow<Int>) -> Flow<Int>) {
    val renders = AtomicInteger(0)
    var shown = 0
    val start = System.nanoTime()
    val prices = flow {
        for (price in 1..20) {
            emit(price)
            delay(100)
        }
        // 在處理（shape）之前：價格都送完的那一刻
    }.onCompletion {
        log("價格全部送完花了 ${(System.nanoTime() - start) / 1_000_000} ms")
        log("串流結束時，畫面已更新 ${renders.get()} 次")
    }

    shape(prices).collect { price ->
        delay(250) // 更新畫面要 250 ms
        renders.incrementAndGet()
        shown = price
    }
    log("最後顯示的價格是 $shown")
}

// MARK: - Bug 版
private object L04Bug {
    // 價格從不安靜 300 ms：整個串流期間都不發
    suspend fun run(log: LabLog) = scenario(log) { it.debounce(300) }
}

// MARK: - 修正版
private object L04Fixed {
    // 忙的時候只留最新的一筆
    suspend fun run(log: LabLog) = scenario(log) { it.conflate() }
}

// MARK: - 演練設定
private fun sendMs(lines: List<String>): Long? = lines.firstOrNull {
    it.startsWith("價格全部送完花了 ")
}?.removePrefix("價格全部送完花了 ")?.removeSuffix(" ms")?.toLongOrNull()

private fun rendersAtEnd(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("串流結束時，畫面已更新 ")
}?.removePrefix("串流結束時，畫面已更新 ")?.removeSuffix(" 次")?.toIntOrNull()

val l04 = Lab(
    id = "L04",
    title = "行情一直在跳，畫面卻完全沒更新",
    question = "第 4 題：行情資料進 UI 時，conflate、sample、debounce 差在哪？",
    goal = "價格每 100 ms 一筆，畫面更新一次要 250 ms。看 debounce 為什麼讓畫面停住，再改用 conflate。",
    steps = listOf(
        "選「Bug 版」執行（約 2 秒），看「串流結束時，畫面已更新 N 次」的 N。",
        "選「修正版」執行，確認串流進行中畫面持續更新，最後顯示 20。",
        "想一想：改用 sample(250) 的結果和 conflate() 有什麼不一樣？（sample 固定週期取最新一筆，collector 閒著也要等週期；conflate 是 collector 一閒下來就拿最新的）",
    ),
    expectBug = "「價格全部送完花了」約 2000 ms，「串流結束時，畫面已更新 0 次」：整個串流期間畫面完全沒動，要等串流結束才跳到 20。",
    expectFixed = "「價格全部送完花了」約 2000 ms，「串流結束時，畫面已更新 8 次」左右（實測 8），最後顯示的價格是 20。",
    file = "kotlin05-flow/L04_DebouncePrice.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val renders = rendersAtEnd(lines)
        when {
            renders == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            renders > 0 -> "串流期間畫面更新了 $renders 次：Bug 版要用 debounce(300)，價格從不安靜，一筆都不會發"
            else -> null
        }
    },
    checkFixed = { lines ->
        val renders = rendersAtEnd(lines)
        when {
            renders == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"

            (
                sendMs(
                    lines,
                ) ?: 0
            ) > 3_500 -> "價格被畫面拖慢了（全部送完花了 ${sendMs(
                lines,
            )} ms，應該約 2000 ms）：要丟掉中間的價格，改用 conflate()（或 sample(250)）"

            renders < 5 -> "串流期間畫面只更新了 $renders 次：改用 conflate()（或 sample(250)）"

            "最後顯示的價格是 20" !in lines -> "最後要顯示最新價 20"

            else -> null
        }
    },
)
