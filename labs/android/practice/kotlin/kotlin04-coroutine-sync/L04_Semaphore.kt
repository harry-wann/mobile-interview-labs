// L04：一次查 20 個交易對的報價，同時送出 20 個請求（Semaphore 限流）
// 對應複習站：Kotlin04CoroutineSync 第 1 題「多個 coroutine 同時改同一份資料，怎麼選工具？」
//
// 情境：行情畫面要一次查 20 個交易對的報價。交易所的公開 API 有請求頻率限制，App 自己要限制同時進行的請求數，最多 4 個。
//
// 你要寫：
//   ① Bug 版（L04Bug）：每個交易對一個 async，全部同時送出，同時進行的請求數衝到 20。
//   ② 修正版（L04Fixed）：用 Semaphore(4)，每個請求用 withPermit 包起來，同時最多 4 個在跑。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L04_Semaphore.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的報價 API：每次 50 ms，並記下「同時進行」的最大請求數 */
private class QuoteApi {
    private val inFlight = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)

    suspend fun quote(symbol: String): Long {
        val now = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, now) }
        delay(50)
        inFlight.decrementAndGet()
        return symbol.length.toLong()
    }
}

private val symbols = List(20) { "COIN${it}_JPY" }

// MARK: - Bug 版
private object L04Bug {
    suspend fun run(log: LabLog) {
        val api = QuoteApi()
        val quotes = coroutineScope {
            // TODO ①：每個交易對一個 async(Dispatchers.Default) { api.quote(symbol) }，全部 awaitAll()，不要限制數量
            emptyList<Long>()
        }
        log("查了 ${quotes.size} 個交易對")
        log("同時進行的請求數最多 ${api.maxInFlight.get()} 個")
    }
}

// MARK: - 修正版
private object L04Fixed {
    // TODO ②-1：宣告 private val gate = Semaphore(4)，同時最多 4 個

    suspend fun run(log: LabLog) {
        val api = QuoteApi()
        val quotes = coroutineScope {
            // TODO ②-2：和 Bug 版一樣每個交易對一個 async，但請求要用 gate.withPermit { api.quote(symbol) } 包起來
            emptyList<Long>()
        }
        log("查了 ${quotes.size} 個交易對")
        log("同時進行的請求數最多 ${api.maxInFlight.get()} 個")
    }
}

// MARK: - 演練設定
private fun maxInFlight(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("同時進行的請求數最多 ") }?.removePrefix("同時進行的請求數最多 ")?.removeSuffix(" 個")?.toIntOrNull()

val l04 = Lab(
    id = "L04",
    title = "一次查 20 個報價，同時送出 20 個請求",
    question = "第 1 題：多個 coroutine 同時改同一份資料，怎麼選工具？",
    goal = "查 20 個交易對的報價，同時進行的請求最多 4 個。先寫出同時送出 20 個的 Bug 版，再用 Semaphore 限制。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L04_Semaphore.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「同時進行的請求數最多 N 個」。",
        "寫 TODO ②-1、②-2（修正版），選「修正版」執行，確認最多 4 個，而且 20 個交易對都查到了。",
        "想一想：Semaphore 能保證「每秒不超過 10 個請求」嗎？（不能，它只限制同時幾個，不限制速率）",
    ),
    expectBug = "「同時進行的請求數最多 20 個」。",
    expectFixed = "「同時進行的請求數最多 4 個」，仍然查了 20 個交易對。",
    file = "kotlin04-coroutine-sync/L04_Semaphore.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "查了 20 個交易對" !in lines -> "20 個交易對都要查：symbols.map { async { api.quote(it) } }.awaitAll()"
            (maxInFlight(lines) ?: 0) <= 4 -> "同時進行的請求沒有超過 4 個：Bug 版不要限制，全部同時送出"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "查了 20 個交易對" !in lines -> "20 個交易對都要查"
            (maxInFlight(lines) ?: 99) > 4 -> "同時進行的請求超過 4 個：用 Semaphore(4)，每個請求用 gate.withPermit { } 包起來"
            maxInFlight(lines) != 4 -> "同時進行的請求應該剛好最多 4 個：Semaphore(4)"
            else -> null
        }
    },
)
