// L04：委託狀態從來沒有更新，因為前一段 collect 永遠不會結束（同一個 LaunchedEffect 裡有兩段 collect）
// 對應複習站：Kotlin07SideEffects 第 2 題「LaunchedEffect 解決什麼問題？key 是什麼意思？」
//
// 情境：交易畫面要同時收兩個不會結束的 Flow：行情（ticks）和委託狀態（orders），各自每 20 ms 送一筆。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：在同一個 LaunchedEffect 裡，先 collect 行情，再 collect 委託狀態。行情的 collect 永遠不會結束，
//        後面那一行永遠執行不到，委託狀態一筆都收不到。
// 修正版：兩段各自用 launch { } 啟動，同時收集。LaunchedEffect 離開畫面時，兩個一起取消。
package tw.harry.labs.solution.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的伺服器：行情與委託狀態都是每 20 ms 送一筆、永遠不會結束的 Flow */
private class L04Server {
    val ticks: Flow<Int> = endless()
    val orders: Flow<Int> = endless()

    private fun endless(): Flow<Int> = flow {
        var n = 0
        while (true) {
            emit(++n)
            delay(20)
        }
    }
}

/** 演練流程（兩個版本共用）：進入畫面 300 ms，看收到幾筆行情、幾筆委託狀態 */
private suspend fun scenario(log: LabLog, screen: @Composable (server: L04Server, onTick: () -> Unit, onOrder: () -> Unit) -> Unit) {
    val server = L04Server()
    val ticks = AtomicInteger()
    val orders = AtomicInteger()
    withHeadlessCompose {
        setContent { screen(server, { ticks.incrementAndGet() }, { orders.incrementAndGet() }) }
        delay(300)
        log("進入畫面 300 ms：收到行情 ${ticks.get()} 筆")
        log("進入畫面 300 ms：收到委託狀態 ${orders.get()} 筆")
    }
}

// MARK: - Bug 版
private object L04Bug {
    @Composable
    fun TradeScreen(server: L04Server, onTick: () -> Unit, onOrder: () -> Unit) {
        LaunchedEffect(Unit) {
            server.ticks.collect { onTick() }               // 這個 Flow 不會結束，collect 一直掛在這裡
            server.orders.collect { onOrder() }             // 永遠執行不到
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { server, onTick, onOrder -> TradeScreen(server, onTick, onOrder) }
}

// MARK: - 修正版
private object L04Fixed {
    @Composable
    fun TradeScreen(server: L04Server, onTick: () -> Unit, onOrder: () -> Unit) {
        LaunchedEffect(Unit) {
            launch { server.ticks.collect { onTick() } }    // 各自用 launch 啟動，同時收集
            launch { server.orders.collect { onOrder() } }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { server, onTick, onOrder -> TradeScreen(server, onTick, onOrder) }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(" 筆")?.toIntOrNull()

private const val TICKS = "進入畫面 300 ms：收到行情 "
private const val ORDERS = "進入畫面 300 ms：收到委託狀態 "

val l04 = Lab(
    id = "L04",
    title = "委託狀態一筆都沒收到",
    question = "第 2 題：LaunchedEffect 解決什麼問題？key 是什麼意思？",
    goal = "交易畫面要同時收行情和委託狀態兩個不會結束的 Flow。看把兩段 collect 排在同一個 LaunchedEffect 裡，為什麼第二段永遠不會執行，再改成各自 launch。",
    steps = listOf(
        "選「Bug 版」執行，比較「收到行情」和「收到委託狀態」的筆數。",
        "選「修正版」執行，確認兩個都有收到。",
        "想一想：為什麼 collect 後面的程式碼執行不到？（collect 是 suspend 函式，Flow 不結束，它就不會回傳）",
    ),
    expectBug = "收到行情約 10 筆，收到委託狀態 0 筆：第二段 collect 永遠沒有執行。",
    expectFixed = "行情和委託狀態都收到約 10 筆。",
    file = "kotlin07-side-effects/L04_CollectBlocks.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ticks = count(lines, TICKS) ?: 0
        val orders = count(lines, ORDERS) ?: 0
        when {
            ticks == 0 -> "行情一筆都沒收到：LaunchedEffect 裡要先 server.ticks.collect { onTick() }"
            orders > 0 -> "委託狀態收到了 $orders 筆：Bug 版要在同一個 LaunchedEffect 裡，先 collect 行情，再 collect 委託狀態（不要用 launch）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val ticks = count(lines, TICKS) ?: 0
        val orders = count(lines, ORDERS) ?: 0
        when {
            ticks == 0 -> "行情一筆都沒收到：兩段 collect 都要各自放進 launch { }"
            orders == 0 -> "委託狀態一筆都沒收到：兩段 collect 都要各自放進 launch { }，不然第二段會被第一段擋住"
            else -> null
        }
    },
)
