// L02：切換成 ETH_JPY，畫面卻還在收 BTC_JPY 的行情（LaunchedEffect 的 key
// 寫太少）
// 對應複習站：Kotlin07SideEffects 第 2 題「LaunchedEffect 解決什麼問題？key
// 是什麼意思？」
//
// 情境：行情畫面依 symbol（交易對）訂閱價格並顯示。使用者先看 BTC_JPY，0.1
// 秒後切換成 ETH_JPY，再等 0.15 秒。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合、重組與 effect。
//
// 你要寫：
//   ① Bug 版（L02Bug）：LaunchedEffect(Unit) { 收集 feed.prices(symbol) }。key
// 永遠不變，symbol 換了 effect 也不會重新啟動。
//   ② 修正版（L02Fixed）：LaunchedEffect(symbol)。symbol 一變，舊的取消，
// 用新的 symbol 重新收集。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin07-side-effects/L02_KeyTooFew.kt
package tw.harry.labs.practice.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 假的行情來源：每 15 ms 送一次價格，並記下有哪些交易對正在被收集 */
private class L02Feed {
    val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun prices(symbol: String): Flow<String> = flow {
        active += symbol
        try {
            var n = 0
            while (true) {
                emit("$symbol 價格 ${++n}")
                delay(15)
            }
        } finally {
            // 被取消時才會走到這裡
            active -= symbol
        }
    }
}

/**
 * 演練流程（兩個版本共用）：看 BTC_JPY 100 ms → 切換成 ETH_JPY → 再等 150 ms，
 * 看畫面上顯示什麼、還有誰在收行情
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (
        symbol: String,
        feed: L02Feed,
        onShow: (String) -> Unit,
    ) -> Unit,
) {
    val feed = L02Feed()
    var shown = ""
    withHeadlessCompose {
        val symbol = mutableStateOf("BTC_JPY")
        setContent { screen(symbol.value, feed) { shown = it } }
        delay(100)
        log("切換前，畫面顯示：$shown")
        symbol.value = "ETH_JPY"
        delay(150)
        log("切換後，畫面顯示：$shown")
        log("切換後，正在收行情的交易對：${feed.active.sorted().joinToString("、")}")
    }
}

// MARK: - Bug 版
private object L02Bug {
    @Composable
    fun PriceHeader(symbol: String, feed: L02Feed, onShow: (String) -> Unit) {
        // symbol 變了，price 重設為 "--"
        var price by remember(symbol) { mutableStateOf("--") }
        // TODO ①：用 LaunchedEffect(Unit) { … } 收集 feed.prices(symbol)，
        // 把收到的值寫進 price（key 寫 Unit：effect 只會執行一次）
        HeadlessText("$symbol：$price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, feed, onShow ->
        PriceHeader(symbol, feed, onShow)
    }
}

// MARK: - 修正版
private object L02Fixed {
    @Composable
    fun PriceHeader(symbol: String, feed: L02Feed, onShow: (String) -> Unit) {
        var price by remember(symbol) { mutableStateOf("--") }
        // TODO ②：用 LaunchedEffect(symbol) { … } 收集 feed.prices(symbol)，
        // 把收到的值寫進 price（key 換成 symbol）
        HeadlessText("$symbol：$price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, feed, onShow ->
        PriceHeader(symbol, feed, onShow)
    }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l02 = Lab(
    id = "L02",
    title = "切換交易對，卻還在收舊的行情",
    question = "第 2 題：LaunchedEffect 解決什麼問題？key 是什麼意思？",
    goal = "使用者把 BTC_JPY 切換成 ETH_JPY。看 key 寫成 Unit 時，effect 為什麼不會跟著 symbol 重新啟動，再把 symbol 加進 key。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin07-side-effects/L02_KeyTooFew.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「切換後，畫面顯示」和「切換後，正在收行情的交易對」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認切換之後畫面是 ETH_JPY 的價格，而且只剩 ETH_JPY 在收行情。",
        "想一想：怎麼判斷 key 要寫哪些？（effect 裡面用到、而且變了就該重做的值，都要當成 key）",
    ),
    expectBug = "「切換後，畫面顯示：ETH_JPY：--」，而且「正在收行情的交易對：BTC_JPY」：舊的訂閱沒取消，新的也沒開始。",
    expectFixed = "「切換後，畫面顯示：ETH_JPY：ETH_JPY 價格 …」，「正在收行情的交易對：ETH_JPY」：舊的取消了。",
    file = "kotlin07-side-effects/L02_KeyTooFew.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = line(lines, "切換前，畫面顯示：")
        val after = line(lines, "切換後，畫面顯示：")
        val active = line(lines, "切換後，正在收行情的交易對：")
        when {
            before == null || !before.contains(
                "BTC_JPY 價格",
            ) -> "切換前沒有收到 BTC_JPY 的價格：LaunchedEffect 裡要 collect feed.prices(symbol)，把值寫進 price"

            after?.contains(
                "ETH_JPY 價格",
            ) == true -> "切換後畫面已經是 ETH_JPY 的價格：Bug 版的 key 要寫 Unit，讓 effect 不會跟著 symbol 重新啟動"

            active != "BTC_JPY" -> "舊的訂閱被取消了（現在在收：$active）：Bug 版的 key 要寫 Unit"

            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "切換前，畫面顯示：")
        val after = line(lines, "切換後，畫面顯示：")
        val active = line(lines, "切換後，正在收行情的交易對：")
        when {
            before == null || !before.contains(
                "BTC_JPY 價格",
            ) -> "切換前沒有收到 BTC_JPY 的價格：LaunchedEffect 裡要 collect feed.prices(symbol)，把值寫進 price"

            after?.contains(
                "ETH_JPY 價格",
            ) != true -> "切換後畫面還不是 ETH_JPY 的價格：key 要寫 symbol，symbol 變了 effect 才會重新啟動"

            active != "ETH_JPY" -> "舊的訂閱還在（現在在收：$active）：key 要寫 symbol，才會取消舊的 coroutine"

            else -> null
        }
    },
)
