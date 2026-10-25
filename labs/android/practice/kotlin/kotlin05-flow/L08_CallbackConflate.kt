// L08：一瞬間來了 200 筆價格，畫面卻停在第 65 筆（callbackFlow 沒加 conflate）
// 對應複習站：Kotlin05Flow 第 6 題「怎麼用 callbackFlow 把 WebSocket 的
// callback 包成 Flow？」
//
// 情境：WebSocket 在一瞬間推送 200 筆價格（價格 1 到 200，最新的是 200）。
// 畫面處理一筆要 5 ms，比來得慢。
//
// 你要寫：
//   ① Bug 版（L08Bug）：用 callbackFlow 包 socket（register、trySend、
// awaitClose 解除註冊），不加 conflate()。
//      預設的 buffer 約 64，滿了之後 trySend 失敗，丟掉的是最新的一筆，
// 畫面停在舊價格。
//   ② 修正版（L08Fixed）：在 callbackFlow { } 後面加 conflate()，丟的是舊值，
// 最後顯示 200。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin05-flow/L08_CallbackConflate.kt
package tw.harry.labs.practice.kotlin05flow

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 WebSocket（和 L07 一樣，但這個檔案自己用） */
private class BurstSocket {
    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    val listenerCount: Int get() = listeners.size

    fun register(listener: (Int) -> Unit) {
        listeners += listener
    }

    fun unregister(listener: (Int) -> Unit) {
        listeners -= listener
    }

    /** 一瞬間推送 1 到 [count] 的價格 */
    fun burst(count: Int) {
        for (price in 1..count) listeners.forEach { it(price) }
    }
}

/**
 * 演練流程（兩個版本共用）：畫面開始收集 → socket 一瞬間推送 200 筆 →
 * 等畫面處理完，看最後顯示哪一筆
 */
private suspend fun scenario(
    log: LabLog,
    socket: BurstSocket,
    prices: Flow<Int>,
) {
    var shown = 0
    val screen = CoroutineScope(Dispatchers.Default).launch {
        prices.collect { price ->
            delay(5) // 處理一筆要 5 ms
            shown = price
        }
    }
    // 等畫面註冊好 listener
    withTimeoutOrNull(1_000) { while (socket.listenerCount == 0) delay(1) }
    socket.burst(200)
    // 給畫面足夠的時間處理完
    delay(800)
    screen.cancelAndJoin()
    log("最後顯示的價格 #$shown（最新的是 #200）")
}

// MARK: - Bug 版
private object L08Bug {
    // TODO ①：用 callbackFlow { } 包 socket：val listener: (Int) -> Unit = {
    // price -> trySend(price) }、socket.register(listener)、
    //        awaitClose { socket.unregister(listener) }。不要加 conflate()
    // （buffer 滿了，trySend 失敗，丟掉的是最新的）
    fun prices(socket: BurstSocket): Flow<Int> = emptyFlow()

    suspend fun run(log: LabLog) {
        val socket = BurstSocket()
        scenario(log, socket, prices(socket))
    }
}

// MARK: - 修正版
private object L08Fixed {
    fun prices(socket: BurstSocket): Flow<Int> = callbackFlow {
        val listener: (Int) -> Unit = { price -> trySend(price) }
        socket.register(listener)
        awaitClose { socket.unregister(listener) }
    }
    // TODO ②：在上面 callbackFlow { } 的右大括號後面加 conflate()
    // （只保留最新的一筆：丟的是舊值）

    suspend fun run(log: LabLog) {
        val socket = BurstSocket()
        scenario(log, socket, prices(socket))
    }
}

// MARK: - 演練設定
private fun shownPrice(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("最後顯示的價格 #")
}?.removePrefix("最後顯示的價格 #")?.substringBefore("（")?.toIntOrNull()

val l08 = Lab(
    id = "L08",
    title = "一瞬間來了 200 筆價格，畫面停在舊價格",
    question = "第 6 題：怎麼用 callbackFlow 把 WebSocket 的 callback 包成 Flow？",
    goal = "價格在一瞬間推送 200 筆，畫面處理得比較慢。先寫出 callbackFlow 預設的 buffer 丟掉最新價的 Bug 版，再用 conflate 修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin05-flow/L08_CallbackConflate.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「最後顯示的價格 #N」的 N。",
        "寫 TODO ②（修正版），選「修正版」執行，確認最後顯示的是最新價 #200。",
        "想一想：為什麼是丟最新的？（callbackFlow 的 buffer 預設在滿了之後，trySend 直接失敗，新來的進不去）",
    ),
    expectBug = "「最後顯示的價格 #65」左右（電腦上的 JVM 實測；buffer 大小約 64），不是 #200。",
    expectFixed = "「最後顯示的價格 #200」。",
    file = "kotlin05-flow/L08_CallbackConflate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val shown = shownPrice(lines)
        when {
            shown == null || shown == 0 -> "畫面沒有收到價格：callbackFlow 裡註冊 listener，用 trySend 送出價格"
            shown == 200 -> "畫面已經顯示最新價 #200：Bug 版不要加 conflate()"
            else -> null
        }
    },
    checkFixed = { lines ->
        val shown = shownPrice(lines)
        when {
            shown == null || shown == 0 -> "畫面沒有收到價格：callbackFlow 裡註冊 listener，用 trySend 送出價格"
            shown != 200 -> "最後顯示 #$shown，應該是最新價 #200：在 callbackFlow { } 後面加 .conflate()"
            else -> null
        }
    },
)
