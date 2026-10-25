// L01：離開畫面了，價格輪詢還在跑（structured concurrency）
// 對應複習站：Kotlin03Coroutines 第 1 題「什麼是 structured concurrency？」
//
// 情境：行情畫面打開時開始每 100 ms 輪詢一次價格，離開畫面時應該停止。
// Bug 版：用 GlobalScope.launch 開 coroutine，它不屬於任何畫面，
// 沒有人能取消它，離開畫面後照樣在跑。
// 修正版：畫面有自己的 scope，coroutine 開在這個 scope 裡；離開畫面時取消
// scope，底下的工作一起停。
//        （在真正的 App 裡，這個 scope 就是 viewModelScope 或
// lifecycleScope。）
@file:OptIn(DelicateCoroutinesApi::class)

package tw.harry.labs.solution.kotlin03coroutines

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 演練流程（兩個版本共用）：打開畫面 → 350 ms 後離開 → 再等 500 ms，
 * 看離開後還輪詢了幾次
 */
private suspend fun scenario(
    log: LabLog,
    open: () -> Unit,
    close: () -> Unit,
    polls: () -> Int,
) {
    open()
    delay(350)
    log("離開前輪詢了 ${polls()} 次")
    close()
    val atClose = polls()
    delay(500)
    log("離開畫面後又輪詢了 ${polls() - atClose} 次")
}

// MARK: - Bug 版
private object L01Bug {
    class PriceScreen(private val log: LabLog) {
        val polls = AtomicInteger(0)

        /** 每 100 ms 輪詢一次價格，最多 20 次 */
        private suspend fun pollLoop() {
            repeat(20) {
                delay(100)
                log("輪詢價格 #${polls.incrementAndGet()}")
            }
        }

        fun onOpen() {
            // 不屬於任何畫面
            GlobalScope.launch(Dispatchers.Default) { pollLoop() }
        }

        fun onClose() {
            // 手上沒有任何東西可以取消
            log("離開畫面")
        }
    }

    suspend fun run(log: LabLog) {
        val screen = PriceScreen(log)
        scenario(log, screen::onOpen, screen::onClose) { screen.polls.get() }
    }
}

// MARK: - 修正版
private object L01Fixed {
    class PriceScreen(private val log: LabLog) {
        val polls = AtomicInteger(0)

        // 畫面自己的 scope：畫面在，工作才在
        private val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private suspend fun pollLoop() {
            repeat(20) {
                delay(100)
                log("輪詢價格 #${polls.incrementAndGet()}")
            }
        }

        fun onOpen() {
            scope.launch { pollLoop() }
        }

        fun onClose() {
            log("離開畫面")
            // 底下所有的 coroutine 一起取消
            scope.cancel()
        }
    }

    suspend fun run(log: LabLog) {
        val screen = PriceScreen(log)
        scenario(log, screen::onOpen, screen::onClose) { screen.polls.get() }
    }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removeSuffix(" 次")?.toIntOrNull()

val l01 = Lab(
    id = "L01",
    title = "離開畫面了，輪詢還在跑",
    question = "第 1 題：什麼是 structured concurrency？",
    goal = "畫面打開時開始輪詢價格，離開後應該立刻停止。",
    steps = listOf(
        "選「Bug 版」執行，看「離開畫面」之後還有沒有「輪詢價格」。",
        "選「修正版」執行，確認離開畫面後是 0 次。",
    ),
    expectBug = "「離開畫面」之後還繼續印「輪詢價格」，最後一行是「離開畫面後又輪詢了 5 次」左右；演練結束後 log 還在跳。",
    expectFixed = "離開畫面後立刻停止：「離開畫面後又輪詢了 0 次」。",
    file = "kotlin03-coroutines/L01_ScopeLeak.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            (count(lines, "離開前輪詢了 ") ?: 0) == 0 -> "離開畫面前沒有輪詢：onOpen 還沒開始輪詢"

            (
                count(
                    lines,
                    "離開畫面後又輪詢了 ",
                ) ?: 0
            ) == 0 -> "離開畫面後就停了：Bug 還沒重現（要讓輪詢在離開後繼續跑）"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            (count(lines, "離開前輪詢了 ") ?: 0) == 0 -> "離開畫面前沒有輪詢：onOpen 還沒開始輪詢"

            (
                count(
                    lines,
                    "離開畫面後又輪詢了 ",
                ) ?: 0
            ) != 0 -> "離開畫面後還在輪詢：onClose 要取消 scope"

            else -> null
        }
    },
)
