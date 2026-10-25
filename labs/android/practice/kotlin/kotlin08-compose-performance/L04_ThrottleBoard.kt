// L04：WebSocket 每來一筆就更新一次畫面，
// 不到一秒更新上百次（資料層降低更新頻率）
// 對應複習站：Kotlin08ComposePerformance 第 5 題「上百個幣種、每秒更新數十次，
// 整個行情列表怎麼設計才不卡？」
//
// 情境：WebSocket 每 5 ms 推一筆行情，共 120 筆，輪流更新 20 個幣種（約 0.7
// 秒）。畫面每收到一份清單就更新一次，
//        演練計算「畫面總共更新了幾次」，
// 以及最後一份清單是不是每個幣種都是最新價。
//
// 你要寫：
//   ① Bug 版（L04Bug.MarketRepository.board）：socket.collect 裡每收到一筆，就
// emit(latest.values.toList()) 給畫面。
//   ② 修正版（L04Fixed.MarketRepository）：在 scope 裡收集 socket，用 latest.
// update 合併成「每個幣種最新的一筆」；board 每 200 ms 輸出一份，
// 內容沒變就不送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin08-compose-performance/L04_ThrottleBoard.kt
package tw.harry.labs.practice.kotlin08composeperformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L04Tick(val symbol: String, val price: Long)

/** 畫面用的資料：價格已經轉成字串 */
private data class L04TickUi(val symbol: String, val priceText: String)

private fun L04Tick.toUi() = L04TickUi(symbol, price.toString())

private val symbols = List(20) { "COIN${it + 1}_JPY" }

/**
 * 演練流程（兩個版本共用）：WebSocket 每 5 ms 推一筆，共 120 筆，第 i 筆更新第 i % 20 個幣種，價格是 100000 + i。
 * [makeBoard] 決定怎麼把 WebSocket 的訊息變成給畫面的清單。
 */
private suspend fun scenario(
    log: LabLog,
    makeBoard: (
        socket: Flow<L04Tick>,
        scope: CoroutineScope,
    ) -> Flow<List<L04TickUi>>,
) {
    val socketDone = CompletableDeferred<Unit>()
    val socket = flow {
        repeat(120) { i ->
            emit(L04Tick(symbols[i % 20], 100_000L + i))
            delay(5)
        }
    }.onCompletion { socketDone.complete(Unit) }
    var updates = 0
    var last = emptyList<L04TickUi>()
    coroutineScope {
        val ui = launch {
            // 畫面：每收到一份清單就更新一次
            makeBoard(socket, this@coroutineScope).collect {
                updates++
                last = it
            }
        }
        // 等 WebSocket 送完（最多等 3 秒）
        withTimeoutOrNull(3_000) { socketDone.await() }
        // 再多等 500 ms（兩個多輸出週期），讓最後一批送出去
        delay(500)
        ui.cancel()
        coroutineContext.cancelChildren()
    }
    // 第 k 個幣種最後一次更新是第 100 + k 筆，價格 100000 + 100 + k
    val allLatest =
        last.size == 20 &&
            last.all {
                it.priceText ==
                    (100_100L + symbols.indexOf(it.symbol)).toString()
            }
    log("WebSocket 推了 120 筆，畫面共更新了 $updates 次")
    log("最後一份清單有 ${last.size} 檔，每一檔都是最新價：${if (allLatest) "是" else "否"}")
}

// MARK: - Bug 版
private object L04Bug {
    class MarketRepository(private val socket: Flow<L04Tick>) {
        // TODO ①：board 用 flow { … }：準備一個 LinkedHashMap<String,
        // L04TickUi>，socket.collect 每收到一筆，
        //         就更新這個幣種的 tick.toUi()，並 emit(latest.values.toList())
        // （每筆訊息都送一份完整的清單給畫面）
        val board: Flow<List<L04TickUi>> = emptyFlow()
    }

    suspend fun run(log: LabLog) = scenario(log) { socket, _ ->
        MarketRepository(socket).board
    }
}

// MARK: - 修正版
private object L04Fixed {
    class MarketRepository(socket: Flow<L04Tick>, scope: CoroutineScope) {
        private val latest =
            MutableStateFlow<Map<String, L04TickUi>>(emptyMap())

        // TODO ②-1：init { scope.launch { … } }：收集 socket，每一筆用 latest.
        // update { it + (tick.symbol to tick.toUi()) } 合併
        //           （只保留每個幣種最新的一筆，不通知畫面）

        // TODO ②-2：board 改成固定週期輸出：flow { while (true) { emit(latest.
        // value.values.toList()); delay(200) } }，
        //           後面接 .distinctUntilChanged()（內容完全沒變就不往下送）
        val board: Flow<List<L04TickUi>> = emptyFlow()
    }

    suspend fun run(log: LabLog) = scenario(log) { socket, scope ->
        MarketRepository(socket, scope).board
    }
}

// MARK: - 演練設定
private fun updates(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("WebSocket 推了 120 筆，畫面共更新了 ") }
        ?.removePrefix(
            "WebSocket 推了 120 筆，畫面共更新了 ",
        )?.removeSuffix(" 次")?.toIntOrNull()

private fun allLatest(lines: List<String>): Boolean =
    lines.any { it.endsWith("每一檔都是最新價：是") }

val l04 = Lab(
    id = "L04",
    title = "行情每來一筆，畫面就更新一次",
    question = "第 5 題：上百個幣種、每秒更新數十次，整個行情列表怎麼設計才不卡？",
    goal = "WebSocket 每 5 ms 推一筆行情。看每筆訊息都更新畫面時更新了幾次，再改成在背景依幣種合併、固定週期輸出。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin08-compose-performance/L04_ThrottleBoard.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「畫面共更新了 N 次」的 N。",
        "寫 TODO ②（修正版），選「修正版」執行，確認更新次數降到個位數，而且「每一檔都是最新價：是」。",
        "想一想：為什麼不用 debounce？（行情不會安靜，debounce 要等安靜一段時間才送出，列表會長時間凍結；Kotlin05Flow 的演練 4 做過）",
    ),
    expectBug = "「畫面共更新了 120 次」：每一筆訊息都更新一次畫面。",
    expectFixed = "「畫面共更新了 5 次」左右（每 200 ms 一次），最後一份清單 20 檔「每一檔都是最新價：是」。",
    file = "kotlin08-compose-performance/L04_ThrottleBoard.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = updates(lines) ?: 0
        when {
            n == 0 -> "畫面沒有收到任何清單：board 要在 socket.collect 裡，每收到一筆就 emit(latest.values.toList())"
            n < 60 -> "畫面只更新了 $n 次：Bug 版要每收到一筆訊息就 emit 一份清單"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = updates(lines) ?: 0
        when {
            n == 0 -> "畫面沒有收到任何清單：board 要每 200 ms emit 一次 latest.value.values.toList()"

            n > 30 -> "畫面更新了 $n 次，太頻繁：改成固定週期（delay(200)）輸出，不要每筆訊息都送"

            !allLatest(
                lines,
            ) -> "最後一份清單不是每一檔都是最新價：socket 的每一筆都要合併進 latest（update { it + (symbol to ui) }）"

            else -> null
        }
    },
)
