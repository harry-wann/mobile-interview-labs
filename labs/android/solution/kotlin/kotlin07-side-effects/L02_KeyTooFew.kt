// L02：切換成 ETH_JPY，畫面卻還在收 BTC_JPY 的行情（LaunchedEffect 的 key 寫太少）
// 對應複習站：Kotlin07SideEffects 第 2 題「LaunchedEffect 解決什麼問題？key 是什麼意思？」
//
// 情境：行情畫面依 symbol（交易對）訂閱價格並顯示。使用者先看 BTC_JPY，0.1 秒後切換成 ETH_JPY，再等 0.15 秒。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：LaunchedEffect(Unit)。key 永遠不變，effect 只在進入畫面時執行一次，裡面抓到的 symbol 一直是 BTC_JPY。
//        切換之後，畫面停在「--」，舊的 BTC_JPY 訂閱也沒有被取消。
// 修正版：LaunchedEffect(symbol)。effect 裡用到、而且變了就該重做的值，都要當成 key：
//        symbol 一變，舊的 coroutine 被取消，用新的 symbol 重新收集。
package tw.harry.labs.solution.kotlin07sideeffects

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
            active -= symbol                                // 被取消時才會走到這裡
        }
    }
}

/** 演練流程（兩個版本共用）：看 BTC_JPY 100 ms → 切換成 ETH_JPY → 再等 150 ms，看畫面上顯示什麼、還有誰在收行情 */
private suspend fun scenario(log: LabLog, screen: @Composable (symbol: String, feed: L02Feed, onShow: (String) -> Unit) -> Unit) {
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
        var price by remember(symbol) { mutableStateOf("--") }      // symbol 變了，price 重設為 "--"
        LaunchedEffect(Unit) {                                      // key 永遠不變：effect 只會跑一次
            feed.prices(symbol).collect { price = it }              // symbol 永遠是第一次進來的 BTC_JPY
        }
        HeadlessText("$symbol：$price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, feed, onShow -> PriceHeader(symbol, feed, onShow) }
}

// MARK: - 修正版
private object L02Fixed {
    @Composable
    fun PriceHeader(symbol: String, feed: L02Feed, onShow: (String) -> Unit) {
        var price by remember(symbol) { mutableStateOf("--") }
        LaunchedEffect(symbol) {                                    // symbol 變了：取消舊的，用新的 symbol 重新收集
            feed.prices(symbol).collect { price = it }
        }
        HeadlessText("$symbol：$price", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { symbol, feed, onShow -> PriceHeader(symbol, feed, onShow) }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l02 = Lab(
    id = "L02",
    title = "切換交易對，卻還在收舊的行情",
    question = "第 2 題：LaunchedEffect 解決什麼問題？key 是什麼意思？",
    goal = "使用者把 BTC_JPY 切換成 ETH_JPY。看 key 寫成 Unit 時，effect 為什麼不會跟著 symbol 重新啟動，再把 symbol 加進 key。",
    steps = listOf(
        "選「Bug 版」執行，比較「切換後，畫面顯示」和「切換後，正在收行情的交易對」。",
        "選「修正版」執行，確認切換之後畫面是 ETH_JPY 的價格，而且只剩 ETH_JPY 在收行情。",
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
            before == null || !before.contains("BTC_JPY 價格") -> "切換前沒有收到 BTC_JPY 的價格：LaunchedEffect 裡要 collect feed.prices(symbol)，把值寫進 price"
            after?.contains("ETH_JPY 價格") == true -> "切換後畫面已經是 ETH_JPY 的價格：Bug 版的 key 要寫 Unit，讓 effect 不會跟著 symbol 重新啟動"
            active != "BTC_JPY" -> "舊的訂閱被取消了（現在在收：$active）：Bug 版的 key 要寫 Unit"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "切換前，畫面顯示：")
        val after = line(lines, "切換後，畫面顯示：")
        val active = line(lines, "切換後，正在收行情的交易對：")
        when {
            before == null || !before.contains("BTC_JPY 價格") -> "切換前沒有收到 BTC_JPY 的價格：LaunchedEffect 裡要 collect feed.prices(symbol)，把值寫進 price"
            after?.contains("ETH_JPY 價格") != true -> "切換後畫面還不是 ETH_JPY 的價格：key 要寫 symbol，symbol 變了 effect 才會重新啟動"
            active != "ETH_JPY" -> "舊的訂閱還在（現在在收：$active）：key 要寫 symbol，才會取消舊的 coroutine"
            else -> null
        }
    },
)
