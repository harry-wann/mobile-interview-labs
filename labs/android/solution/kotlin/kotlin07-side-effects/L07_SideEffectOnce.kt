// L07：畫面只打開一次，screen_view 事件卻送了好幾次（SideEffect 不能做只該做一次的事）
// 對應複習站：Kotlin07SideEffects 第 4 題「註冊 listener 要用什麼？DisposableEffect 和 SideEffect 差在哪？」
//
// 情境：行情畫面打開時要送一筆 screen_view 的分析事件（analytics），整個畫面期間只該送一次。價格在畫面上每 40 ms 更新一次，共 5 次。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：用 SideEffect { analytics.track("screen_view") }。SideEffect 在「每一次成功重組之後」都會執行，
//        價格更新 5 次，事件就多送 5 次，分析報表裡這個畫面的瀏覽數被灌水。
// 修正版：改用 LaunchedEffect(Unit)。進入畫面時執行一次，重組不會再執行。
//        （SideEffect 適合「每次重組後，把最新的值同步給非 Compose 的物件」，不適合只做一次的事。）
package tw.harry.labs.solution.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的分析服務：記下 screen_view 送了幾次 */
private class L07Analytics {
    var screenViews = 0
        private set

    fun track(event: String) {
        if (event == "screen_view") screenViews++
    }
}

/** 演練流程（兩個版本共用）：進入畫面 → 價格每 40 ms 更新一次，共 5 次 → 看 screen_view 送了幾次 */
private suspend fun scenario(log: LabLog, screen: @Composable (price: Long, analytics: L07Analytics) -> Unit) {
    val analytics = L07Analytics()
    withHeadlessCompose {
        val price = mutableLongStateOf(100_000L)
        setContent { screen(price.longValue, analytics) }
        settle()
        repeat(5) {
            delay(40)
            price.longValue++
            settle()
        }
        log("價格更新 5 次之後，screen_view 事件送了 ${analytics.screenViews} 次")
    }
}

// MARK: - Bug 版
private object L07Bug {
    @Composable
    fun TickerScreen(price: Long, analytics: L07Analytics) {
        SideEffect {                                        // 每一次成功重組之後都會執行
            analytics.track("screen_view")
        }
        HeadlessText("價格 $price")
    }

    suspend fun run(log: LabLog) = scenario(log) { price, analytics -> TickerScreen(price, analytics) }
}

// MARK: - 修正版
private object L07Fixed {
    @Composable
    fun TickerScreen(price: Long, analytics: L07Analytics) {
        LaunchedEffect(Unit) {                              // 進入畫面時執行一次
            analytics.track("screen_view")
        }
        HeadlessText("價格 $price")
    }

    suspend fun run(log: LabLog) = scenario(log) { price, analytics -> TickerScreen(price, analytics) }
}

// MARK: - 演練設定
private fun views(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("價格更新 5 次之後，screen_view 事件送了 ") }
        ?.removePrefix("價格更新 5 次之後，screen_view 事件送了 ")?.removeSuffix(" 次")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "畫面只開一次，瀏覽事件卻送了六次",
    question = "第 4 題：註冊 listener 要用什麼？DisposableEffect 和 SideEffect 差在哪？",
    goal = "畫面打開時送一次 screen_view 事件。看把它放在 SideEffect 裡，為什麼每次重組都送一次，再改成 LaunchedEffect(Unit)。",
    steps = listOf(
        "選「Bug 版」執行，看「價格更新 5 次之後，screen_view 事件送了 N 次」的 N。",
        "選「修正版」執行，確認只送 1 次。",
        "想一想：SideEffect 適合做什麼？（每次重組成功後，把最新的值同步給不屬於 Compose 的物件，例如舊的 View 或第三方圖表）",
    ),
    expectBug = "「screen_view 事件送了 6 次」：進入畫面 1 次，加上價格更新造成的 5 次重組各 1 次。",
    expectFixed = "「screen_view 事件送了 1 次」。",
    file = "kotlin07-side-effects/L07_SideEffectOnce.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (views(lines)) {
            null, 0 -> "screen_view 一次都沒送：TickerScreen 要在 SideEffect { } 裡呼叫 analytics.track(\"screen_view\")"
            1 -> "只送了 1 次：Bug 版要用 SideEffect（每次重組成功後都會執行），不是 LaunchedEffect"
            else -> null
        }
    },
    checkFixed = { lines ->
        when (val n = views(lines)) {
            null, 0 -> "screen_view 一次都沒送：TickerScreen 要在 LaunchedEffect(Unit) { } 裡呼叫 analytics.track(\"screen_view\")"
            1 -> null
            else -> "screen_view 送了 $n 次：只該做一次的事不要放 SideEffect，改用 LaunchedEffect(Unit)"
        }
    },
)
