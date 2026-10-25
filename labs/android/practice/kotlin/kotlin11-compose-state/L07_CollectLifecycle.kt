// L07：App 退到背景，畫面還一直在更新（collectAsState 與
// collectAsStateWithLifecycle）
// 對應複習站：Kotlin11ComposeState 第 7 題「外部資料怎麼變成
// State？collectAsState 還是 collectAsStateWithLifecycle？」
//
// 情境：行情畫面用 StateFlow 收價格（伺服器每 50 ms 推一筆，
// 不管畫面在不在前景）。
//        演練用自己的 lifecycle 模擬畫面：前景（STARTED）0.3 秒 →
// 退到背景（STOPPED）0.5 秒 → 回到前景 0.2 秒。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose，
//        lifecycle 用 LifecycleRegistry 自己控制（寫法同 Kotlin05 演練 9）。
//        要在手機上執行（collectAsStateWithLifecycle 內部用 repeatOnLifecycle，
// 需要 Android 的主執行緒）；電腦上的單元測試會替換主執行緒後執行。
//
// 你要寫：
//   ① Bug 版（L07Bug）：val price by prices.collectAsState()。不管 lifecycle，
// 退到背景之後 collector 還在，每一筆價格都讓畫面重組。
//   ② 修正版（L07Fixed）：val price by prices.collectAsStateWithLifecycle()。
// 預設只在 STARTED 以上才收，退到背景就取消，回到前景再重新收。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L07_CollectLifecycle.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的畫面：自己控制 lifecycle，可以模擬出現、退到背景、回到前景、離開 */
private class L07Screen : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun move(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
}

/**
 * 演練流程（兩個版本共用）：[screen] 是行情畫面；前景 0.3 秒 → 背景 0.5 秒 →
 * 回前景 0.2 秒，記下各階段畫面更新了幾次
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (
        prices: StateFlow<Long>,
        onShow: (String) -> Unit,
    ) -> Unit,
) = coroutineScope {
    val owner = L07Screen()
    owner.move(Lifecycle.Event.ON_CREATE)
    // 畫面在前景
    owner.move(Lifecycle.Event.ON_START)
    val prices = MutableStateFlow(0L)
    // 伺服器推行情：每 50 ms 一筆
    val feed = launch {
        var price = 0L
        while (true) {
            delay(50)
            prices.value = ++price
        }
    }
    var shown = ""
    var shows = 0
    withHeadlessCompose {
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                screen(prices) { shown = it; shows++ }
            }
        }
        delay(300)
        settle()
        log("前景 0.3 秒，畫面更新了 $shows 次")
        log("前景結束時，畫面顯示：$shown")

        owner.move(Lifecycle.Event.ON_STOP) // 退到背景
        settle()
        val atStop = shows
        delay(500)
        settle()
        log("退到背景 0.5 秒，畫面又更新了 ${shows - atStop} 次")
        log("退到背景 0.5 秒後，還有 ${prices.subscriptionCount.value} 個畫面在收行情")

        owner.move(Lifecycle.Event.ON_START) // 回到前景
        delay(200)
        settle()
        log("回到前景 0.2 秒後，畫面顯示：$shown")
    }
    feed.cancel()
    owner.move(Lifecycle.Event.ON_STOP)
    owner.move(Lifecycle.Event.ON_DESTROY)
}

// MARK: - Bug 版
private object L07Bug {
    @Composable
    fun PriceText(prices: StateFlow<Long>, onShow: (String) -> Unit) {
        // TODO ①：val price by prices.collectAsState()（不管 lifecycle，
        // 在背景也一直收）
        val price = 0L
        HeadlessText("價格 $price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { prices, onShow ->
        PriceText(prices, onShow)
    }
}

// MARK: - 修正版
private object L07Fixed {
    @Composable
    fun PriceText(prices: StateFlow<Long>, onShow: (String) -> Unit) {
        // TODO ②：val price by prices.collectAsStateWithLifecycle()（預設
        // STARTED 才收）
        val price = 0L
        HeadlessText("價格 $price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { prices, onShow ->
        PriceText(prices, onShow)
    }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String, suffix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removeSuffix(suffix)?.toIntOrNull()

private fun price(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removePrefix("價格 ")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "App 退到背景，畫面還一直在更新",
    question = "第 7 題：外部資料怎麼變成 State？collectAsState 還是 collectAsStateWithLifecycle？",
    goal = "行情畫面退到背景（STOPPED）後，不該再收價格。看 collectAsState 為什麼停不下來，再改用 collectAsStateWithLifecycle。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin11-compose-state/L07_CollectLifecycle.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「退到背景 0.5 秒，畫面又更新了 N 次」和「還有 N 個畫面在收行情」。",
        "寫 TODO ②（修正版），選「修正版」按執行，確認背景時是 0 次、0 個，回到前景後畫面顯示的價格是最新的。",
        "想一想：collectAsStateWithLifecycle 的 minActiveState 預設是什麼？（Lifecycle.State.STARTED；改成 RESUMED 的話，被別的視窗搶走焦點、Activity 進入 paused 時也會停）",
    ),
    expectBug = "「退到背景 0.5 秒，畫面又更新了」約 10 次，「還有 1 個畫面在收行情」：退到背景後還在收。",
    expectFixed = "「退到背景 0.5 秒，畫面又更新了 0 次」，「還有 0 個畫面在收行情」；回到前景後畫面顯示最新的價格。",
    file = "kotlin11-compose-state/L07_CollectLifecycle.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val foreground = count(lines, "前景 0.3 秒，畫面更新了 ", " 次")
        val inBackground = count(lines, "退到背景 0.5 秒，畫面又更新了 ", " 次")
        val subscribers = count(lines, "退到背景 0.5 秒後，還有 ", " 個畫面在收行情")
        when {
            foreground == null || foreground < 3 -> "前景時沒有收到行情（畫面更新了 ${foreground ?: 0} 次）：PriceText 要用 prices.collectAsState() 收集價格，並顯示出來"
            inBackground == null || inBackground == 0 -> "背景時畫面沒有更新：Bug 版要用 collectAsState()（不要用 collectAsStateWithLifecycle）"
            subscribers != 1 -> "背景時有 ${subscribers ?: 0} 個畫面在收行情：Bug 版的 collectAsState() 不會因為退到背景而停止收集"
            else -> null
        }
    },
    checkFixed = { lines ->
        val foreground = count(lines, "前景 0.3 秒，畫面更新了 ", " 次")
        val inBackground = count(lines, "退到背景 0.5 秒，畫面又更新了 ", " 次")
        val subscribers = count(lines, "退到背景 0.5 秒後，還有 ", " 個畫面在收行情")
        val before = price(lines, "前景結束時，畫面顯示：")
        val back = price(lines, "回到前景 0.2 秒後，畫面顯示：")
        when {
            foreground == null || foreground < 3 -> "前景時沒有收到行情（畫面更新了 ${foreground ?: 0} 次）：PriceText 要用 prices.collectAsStateWithLifecycle() 收集價格，並顯示出來"
            inBackground != 0 -> "背景時畫面還更新了 ${inBackground ?: 0} 次：改用 collectAsStateWithLifecycle()，退到背景就會取消收集"
            subscribers != 0 -> "背景時還有 ${subscribers ?: 0} 個畫面在收行情：改用 collectAsStateWithLifecycle()"
            before == null || back == null || back - before < 3 -> "回到前景後畫面沒有更新到最新價格（$before → $back）：collectAsStateWithLifecycle 回到 STARTED 會重新收集"
            else -> null
        }
    },
)
