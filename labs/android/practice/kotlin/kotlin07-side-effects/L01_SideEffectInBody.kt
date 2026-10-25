// L01：價格每跳一次，就再向伺服器訂閱一次（副作用不能直接寫在 Composable 裡）
// 對應複習站：Kotlin07SideEffects 第 1 題「什麼是副作用？為什麼不能直接寫在 Composable 裡？」
//
// 情境：行情畫面進來時要向伺服器訂閱這個交易對的行情，畫面同時顯示價格。價格共更新 10 次（每 30 ms 一次）。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L01Bug）：在 TickerScreen 的函式裡直接呼叫 socket.subscribe(symbol)。價格每變一次就重組，伺服器收到一堆重複的訂閱。
//   ② 修正版（L01Fixed）：把訂閱放進 LaunchedEffect(symbol)，只在進入畫面時執行一次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L01_SideEffectInBody.kt
package tw.harry.labs.practice.kotlin07sideeffects

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
        // TODO ①：在這裡直接呼叫 socket.subscribe(symbol)（不要放進任何 effect）
        HeadlessText("$symbol $price")                      // 畫面上的價格（Text 的替身）
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, price, socket -> TickerScreen(symbol, price, socket) }
}

// MARK: - 修正版
private object L01Fixed {
    @Composable
    fun TickerScreen(symbol: String, price: Long, socket: L01Socket) {
        // TODO ②：用 LaunchedEffect(symbol) { … } 包住 socket.subscribe(symbol)（symbol 就是 key）
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
    goal = "行情畫面進來時訂閱一次行情。看副作用直接寫在 Composable 函式裡，價格每變一次就重複訂閱，再改放進 LaunchedEffect。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L01_SideEffectInBody.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 log 的「向伺服器訂閱 BTC_JPY（第 N 次）」一共跳了幾次。",
        "寫 TODO ②（修正版），選「修正版」執行，確認價格更新 10 次之後，仍然只訂閱 1 次。",
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
