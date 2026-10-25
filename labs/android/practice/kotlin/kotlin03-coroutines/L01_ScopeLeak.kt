// L01：離開畫面了，價格輪詢還在跑（structured concurrency）
// 對應複習站：Kotlin03Coroutines 第 1 題「什麼是 structured concurrency？」
//
// 情境：行情畫面打開時開始每 100 ms 輪詢一次價格，離開畫面時應該停止。
//
// 你要寫：
//   ① Bug 版（L01Bug）：用 GlobalScope 開輪詢。跑起來會看到「離開畫面」
// 之後還一直在輪詢。
//   ② 修正版（L01Fixed）：讓 PriceScreen 擁有自己的 scope，離開畫面時取消它。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin03-coroutines/L01_ScopeLeak.kt
@file:OptIn(DelicateCoroutinesApi::class)

package tw.harry.labs.practice.kotlin03coroutines

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
 * 演練流程（兩個版本共用，不用改）：打開畫面 → 350 ms 後離開 → 再等 500 ms，
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

        /** 每 100 ms 輪詢一次價格，最多 20 次（已寫好） */
        private suspend fun pollLoop() {
            repeat(20) {
                delay(100)
                log("輪詢價格 #${polls.incrementAndGet()}")
            }
        }

        fun onOpen() {
            // TODO ①：用 GlobalScope.launch(Dispatchers.Default) 開一個
            // coroutine，在裡面呼叫 pollLoop()
        }

        fun onClose() {
            log("離開畫面")
            // （Bug 版這裡什麼都不做：GlobalScope 開出去的 coroutine，
            // 手上沒有東西可以取消）
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

        // TODO ②-1：宣告這個畫面自己的 scope：CoroutineScope(SupervisorJob() +
        // Dispatchers.Default)

        private suspend fun pollLoop() {
            repeat(20) {
                delay(100)
                log("輪詢價格 #${polls.incrementAndGet()}")
            }
        }

        fun onOpen() {
            // TODO ②-2：在自己的 scope 裡 launch，呼叫 pollLoop()
        }

        fun onClose() {
            log("離開畫面")
            // TODO ②-3：取消 scope（cancel），底下所有的 coroutine 會一起停
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
    goal = "畫面打開時開始輪詢價格，離開後應該立刻停止。先寫出會一直跑下去的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin03-coroutines/L01_ScopeLeak.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「離開畫面」之後還有沒有「輪詢價格」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認離開畫面後是 0 次。",
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
