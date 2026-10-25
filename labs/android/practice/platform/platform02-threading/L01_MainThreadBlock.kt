// L01：按下「買入」之後，要等一下才有反應（重工作寫在主執行緒）
// 對應複習站：Platform02Threading 第 1
// 題「主執行緒到底在做什麼？為什麼不能被卡住？」
//
// 情境：行情頁收到一份訂單簿資料，解碼要花約 80
// ms（演練用忙碌迴圈代替真的解碼，每次花的時間固定）。
//        收到資料之後 10 ms，使用者按下「買入」。
// 按鈕的事件要排進主執行緒的佇列（Looper 的 MessageQueue），
// 等主執行緒有空才會處理。
//        要在手機上執行（要用 Android 的主執行緒 Dispatchers.Main）。
//
// 你要寫：
//   ① Bug 版（L01Bug）：在主執行緒直接解碼。按鈕的事件排在解碼後面，要等 60 到
// 80 ms。
//   ② 修正版（L01Fixed）：用 withContext(Dispatchers.Default) 把解碼放到背景，
// 只有更新畫面回主執行緒。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform02-threading/L01_MainThreadBlock.kt
package tw.harry.labs.practice.platform02threading

import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 一份訂單簿（兩個版本共用） */
private class L01OrderBook(val bestBid: Int)

/** 解碼要花約 80 ms（兩個版本共用）：用忙碌迴圈代替，每次花的時間固定 */
private fun decodeOrderBook(raw: ByteArray): L01OrderBook {
    val end = System.nanoTime() + 80_000_000
    while (System.nanoTime() < end) { /* 忙碌等待 */ }
    return L01OrderBook(raw.size)
}

/**
 * 演練流程（兩個版本共用）：收到資料 → 10 ms 後使用者按下買入 →
 * 看按鈕的事件等了多久才被處理
 */
private suspend fun scenario(
    log: LabLog,
    receive: (CoroutineScope, ByteArray, (L01OrderBook) -> Unit) -> Unit,
) = coroutineScope {
    var rendered = false
    val tapDelayMs = CompletableDeferred<Long>()
    // 10 ms 後，使用者按下買入：事件從別的執行緒排進主執行緒的佇列，
    // 記下排進去的時間，等主執行緒處理時再量
    thread(isDaemon = true) {
        Thread.sleep(10)
        val posted = System.nanoTime()
        CoroutineScope(Dispatchers.Main).launch {
            tapDelayMs.complete((System.nanoTime() - posted) / 1_000_000)
        }
    }
    // 收到一份訂單簿資料
    receive(this, ByteArray(1_000)) { _ -> rendered = true }
    val ms = withTimeoutOrNull(2_000) { tapDelayMs.await() } ?: -1
    withTimeoutOrNull(2_000) { while (!rendered) delay(10) }
    log("訂單簿${if (rendered) "已經顯示" else "沒有顯示"}")
    log("按下買入的事件，在佇列裡等了 $ms ms 才被處理")
}

// MARK: - Bug 版
private object L01Bug {
    suspend fun run(log: LabLog) = scenario(log) { scope, raw, render ->
        scope.launch {
            // 在主執行緒開始
            // TODO ①：在這裡直接呼叫 decodeOrderBook(raw) 解碼（會佔住主執行緒
            // 80 ms），再把結果交給 render(book)
        }
    }
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun run(log: LabLog) = scenario(log) { scope, raw, render ->
        scope.launch {
            // TODO ②：用 withContext(Dispatchers.Default) 在背景解碼
            // decodeOrderBook(raw)，解碼完回到主執行緒再呼叫 render(book)
        }
    }
}

// MARK: - 演練設定
private fun delayMs(lines: List<String>): Long? =
    lines.firstOrNull { it.startsWith("按下買入的事件，在佇列裡等了 ") }
        ?.removePrefix(
            "按下買入的事件，在佇列裡等了 ",
        )?.removeSuffix(" ms 才被處理")?.toLongOrNull()

val l01 = Lab(
    id = "L01",
    title = "按下買入之後，要等一下才有反應",
    question = "第 1 題：主執行緒到底在做什麼？為什麼不能被卡住？",
    goal = "解碼這類耗時的工作要放背景，只有更新畫面回主執行緒。使用者按下買入時，事件不該排在重工作後面。先寫出會卡住按鈕的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform02-threading/L01_MainThreadBlock.kt，寫 TODO ①（Bug 版）。",
        "在手機（模擬器）的 App 選「練習」「Bug 版」按執行，看「按下買入的事件，在佇列裡等了幾 ms 才被處理」。",
        "寫 TODO ②（修正版），選「修正版」執行：解碼放背景之後，按鈕的事件幾乎立刻被處理。",
        "想一想：為什麼解碼 80 ms，使用者卻等了不到 80 ms？（按鈕在解碼開始後 10 ms 才按，之後的時間都在排隊）",
    ),
    expectBug = "按下買入的事件等了約 60 到 80 ms 才被處理（解碼佔住主執行緒約 80 ms，按鈕在解碼開始後 10 ms 才按）。",
    expectFixed = "按下買入的事件等了不到 5 ms，幾乎立刻被處理。",
    file = "platform02-threading/L01_MainThreadBlock.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = delayMs(lines)
        when {
            !lines.contains(
                "訂單簿已經顯示",
            ) -> "訂單簿沒有顯示：要呼叫 decodeOrderBook(raw)，再呼叫 render(book)"

            ms == null -> "演練沒有跑完"

            ms < 40 -> "Bug 還沒重現（等了 $ms ms）：要在主執行緒直接呼叫 decodeOrderBook"

            else -> null
        }
    },
    checkFixed = { lines ->
        val ms = delayMs(lines)
        when {
            !lines.contains(
                "訂單簿已經顯示",
            ) -> "訂單簿沒有顯示：要呼叫 decodeOrderBook(raw)，再呼叫 render(book)"

            ms == null -> "演練沒有跑完"

            ms >= 25 -> "按鈕還是等了 $ms ms：解碼要放到背景（withContext(Dispatchers.Default)），只有 render(book) 回主執行緒"

            else -> null
        }
    },
)
