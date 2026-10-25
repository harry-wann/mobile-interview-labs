// L07：畫面關了，行情 listener 還掛在 WebSocket 上（callbackFlow 的 awaitClose）
// 對應複習站：Kotlin05Flow 第 6 題「怎麼用 callbackFlow 把 WebSocket 的 callback 包成 Flow？」
//
// 情境：PriceSocket 是「註冊 listener、等 callback 通知」風格的 API（register／unregister）。
//        用 callbackFlow 包成 Flow。畫面只看 3 筆價格就不再收集了，之後 socket 還會繼續推送。
//
// 你要寫：
//   ① Bug 版（L07Bug）：用 callbackFlow 包 socket：註冊 listener，callback 裡 trySend；awaitClose { } 的大括號留空，忘了解除註冊。
//   ② 修正版（L07Fixed）：在 awaitClose { } 裡呼叫 socket.unregister(listener)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin05-flow/L07_AwaitClose.kt
package tw.harry.labs.practice.kotlin05flow

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 WebSocket：listener 用 register／unregister 管理，push 把價格推給目前註冊的 listener */
private class PriceSocket {
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    val listenerCount: Int get() = listeners.size

    fun register(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun unregister(listener: (Int) -> Unit) {
        listeners -= listener
    }

    fun push(price: Int) {
        listeners.forEach { it(price) }
    }
}

/** 演練流程（兩個版本共用）：畫面收集 3 筆價格就不再收集，之後 socket 又推送 2 筆 */
private suspend fun scenario(log: LabLog, socket: PriceSocket, prices: Flow<Int>) {
    // socket 在背景每 20 ms 推送一筆價格
    val pusher = CoroutineScope(Dispatchers.Default).launch {
        var price = 0
        while (true) {
            socket.push(++price)
            delay(20)
        }
    }
    var first = true
    prices.take(3).collect {
        log("畫面收到價格 $it")
        if (first) log("收集中，socket 上註冊著 ${socket.listenerCount} 個 listener")
        first = false
    }
    log("畫面不再收集了")
    delay(100)                                              // 之後 socket 還會推送
    log("不再收集之後，socket 上還註冊著 ${socket.listenerCount} 個 listener")
    pusher.cancel()
}

// MARK: - Bug 版
private object L07Bug {
    // TODO ①：用 callbackFlow { } 包 socket：
    //        ① val listener: (Int) -> Unit = { price -> trySend(price) }　② socket.register(listener)　③ awaitClose { }（大括號留空，忘了解除註冊）
    fun prices(socket: PriceSocket): Flow<Int> = flowOf(1, 2, 3)

    suspend fun run(log: LabLog) {
        val socket = PriceSocket()
        scenario(log, socket, prices(socket))
    }
}

// MARK: - 修正版
private object L07Fixed {
    fun prices(socket: PriceSocket): Flow<Int> = callbackFlow {
        val listener: (Int) -> Unit = { price -> trySend(price) }
        socket.register(listener)
        awaitClose { }                                      // TODO ②：在大括號裡呼叫 socket.unregister(listener)，Flow 被取消或關閉時解除註冊
    }

    suspend fun run(log: LabLog) {
        val socket = PriceSocket()
        scenario(log, socket, prices(socket))
    }
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "畫面關了，行情 listener 還掛在 socket 上",
    question = "第 6 題：怎麼用 callbackFlow 把 WebSocket 的 callback 包成 Flow？",
    goal = "用 callbackFlow 包 WebSocket。畫面只收 3 筆就停止收集。先寫出 awaitClose 忘了解除註冊的 Bug 版，再修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin05-flow/L07_AwaitClose.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「收集中」和「不再收集之後」socket 上各註冊著幾個 listener。",
        "寫 TODO ②（修正版），選「修正版」執行，確認收集結束後 socket 上是 0 個 listener。",
        "想一想：如果完全沒寫 awaitClose，callbackFlow 會怎樣？（直接丟 IllegalStateException，刻意設計來防止忘記解除註冊）",
    ),
    expectBug = "「不再收集之後，socket 上還註冊著 1 個 listener」：listener 洩漏。",
    expectFixed = "「不再收集之後，socket 上還註冊著 0 個 listener」。",
    file = "kotlin05-flow/L07_AwaitClose.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.count { it.startsWith("畫面收到價格 ") } != 3 -> "畫面要收到 3 筆價格：callbackFlow 裡註冊 listener，用 trySend 送出價格"
            "收集中，socket 上註冊著 1 個 listener" !in lines -> "收集中 socket 上要有 1 個 listener：callbackFlow 裡要 socket.register(listener)"
            "不再收集之後，socket 上還註冊著 1 個 listener" !in lines -> "Bug 版 listener 要洩漏：awaitClose { } 的大括號留空，不要 unregister"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.count { it.startsWith("畫面收到價格 ") } != 3 -> "畫面要收到 3 筆價格：callbackFlow 裡註冊 listener，用 trySend 送出價格"
            "收集中，socket 上註冊著 1 個 listener" !in lines -> "收集中 socket 上要有 1 個 listener：callbackFlow 裡要 socket.register(listener)"
            "不再收集之後，socket 上還註冊著 0 個 listener" !in lines -> "listener 還掛著：awaitClose { socket.unregister(listener) }"
            else -> null
        }
    },
)
