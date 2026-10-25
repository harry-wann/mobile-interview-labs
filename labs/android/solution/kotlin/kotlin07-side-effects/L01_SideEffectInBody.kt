// L01：價格每跳一次，就再向伺服器訂閱一次（副作用不能直接寫在 Composable 裡）
// 對應複習站：Kotlin07SideEffects 第 1 題「什麼是副作用？為什麼不能直接寫在 Composable 裡？」
//
// 情境：行情畫面進來時要向伺服器訂閱這個交易對的行情，畫面同時顯示價格。價格共更新 10 次（每 30 ms 一次）。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：在 TickerScreen 的函式裡直接呼叫 socket.subscribe(symbol)。價格每變一次，畫面就重組一次，
//        函式又被執行一次，伺服器收到 11 次重複的訂閱。
// 修正版：把訂閱放進 LaunchedEffect(symbol)。只有進入畫面（或 symbol 換了）才執行，重組不會再訂閱。
package tw.harry.labs.solution.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的行情伺服器：記下收到幾次訂閱 */
private class L01Socket(private val log: LabLog) {
    var subscribeCount = 0
        private set

    fun subscribe(symbol: String) {
        subscribeCount++
        log("向伺服器訂閱 $symbol（第 $subscribeCount 次）")
    }
}

/** 演練流程（兩個版本共用）：進入畫面 → 價格每 30 ms 更新一次，共 10 次 → 看總共訂閱了幾次 */
private suspend fun scenario(log: LabLog, screen: @Composable (symbol: String, price: Long, socket: L01Socket) -> Unit) {
    val socket = L01Socket(log)
    withHeadlessCompose {
        val price = mutableLongStateOf(100_000L)
        setContent { screen("BTC_JPY", price.longValue, socket) }
        settle()
        log("進入畫面時，共訂閱 ${socket.subscribeCount} 次")
        repeat(10) {
            delay(30)
            price.longValue++
            settle()
        }
        log("價格更新 10 次之後，共訂閱 ${socket.subscribeCount} 次")
    }
}

// MARK: - Bug 版
private object L01Bug {
    @Composable
    fun TickerScreen(symbol: String, price: Long, socket: L01Socket) {
        socket.subscribe(symbol)                            // 副作用直接寫在函式裡：每次重組都會再執行一次
        HeadlessText("$symbol $price")                      // 畫面上的價格（Text 的替身）
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, price, socket -> TickerScreen(symbol, price, socket) }
}

// MARK: - 修正版
private object L01Fixed {
    @Composable
    fun TickerScreen(symbol: String, price: Long, socket: L01Socket) {
        LaunchedEffect(symbol) {                            // 進入畫面時執行一次；symbol 換了才會重新執行
            socket.subscribe(symbol)
        }
        HeadlessText("$symbol $price")
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, price, socket -> TickerScreen(symbol, price, socket) }
}

// MARK: - 演練設定
private fun total(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.removeSuffix(" 次")?.toIntOrNull()

val l01 = Lab(
    id = "L01",
    title = "價格一跳，就再訂閱一次",
    question = "第 1 題：什麼是副作用？為什麼不能直接寫在 Composable 裡？",
    goal = "行情畫面進來時訂閱一次行情。看副作用直接寫在 Composable 函式裡，價格每變一次就重複訂閱，再改放進 LaunchedEffect。",
    steps = listOf(
        "選「Bug 版」執行，看 log 的「向伺服器訂閱 BTC_JPY（第 N 次）」一共跳了幾次。",
        "選「修正版」執行，確認價格更新 10 次之後，仍然只訂閱 1 次。",
        "想一想：如果交易對從 BTC_JPY 換成 ETH_JPY，LaunchedEffect(symbol) 會怎樣？（舊的取消，用新的 symbol 再執行一次）",
    ),
    expectBug = "價格更新 10 次之後，「共訂閱 11 次」：進入畫面 1 次，加上每次重組各 1 次。",
    expectFixed = "「進入畫面時，共訂閱 1 次」，價格更新之後仍然是「共訂閱 1 次」。",
    file = "kotlin07-side-effects/L01_SideEffectInBody.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            (total(lines, "進入畫面時，共訂閱 ") ?: 0) == 0 -> "進入畫面時沒有訂閱：TickerScreen 要呼叫 socket.subscribe(symbol)"
            (total(lines, "價格更新 10 次之後，共訂閱 ") ?: 0) <= 1 -> "價格更新後沒有重複訂閱：Bug 版要把 socket.subscribe(symbol) 直接寫在 TickerScreen 的函式裡，不要放進 effect"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            (total(lines, "進入畫面時，共訂閱 ") ?: 0) == 0 -> "進入畫面時沒有訂閱：TickerScreen 要在 LaunchedEffect 裡呼叫 socket.subscribe(symbol)"
            (total(lines, "價格更新 10 次之後，共訂閱 ") ?: 0) != 1 -> "價格更新後又訂閱了：把 socket.subscribe(symbol) 放進 LaunchedEffect(symbol) { }"
            else -> null
        }
    },
)
