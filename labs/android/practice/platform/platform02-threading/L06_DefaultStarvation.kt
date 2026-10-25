// L06：資料庫在等回應，一個簡單的運算卻等了好久（在 Dispatchers.Default 做阻塞 I/O）
// 對應複習站：Platform02Threading 第 5 題「Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 runBlocking？」
//
// 情境：App 同時送出好幾個資料庫查詢，每個都要等 300 ms（演練用 Thread.sleep 代替「等磁碟」，執行緒被佔住什麼都不能做）。
//        查詢開始 50 ms 之後，來了一個很快的 CPU 運算（例如把行情解碼），它丟進 Dispatchers.Default。看它等了多久才開始。
//        Default 的執行緒數量約等於 CPU 核心數（至少 2 條），目的是讓所有 CPU 運算剛好吃滿 CPU。
//
// 你要寫：
//   ① Bug 版（L06Bug）：資料庫查詢（會阻塞等待）放在 Dispatchers.Default，把 CPU 運算用的執行緒全佔住。
//   ② 修正版（L06Fixed）：阻塞的 I/O 放 Dispatchers.IO。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform02-threading/L06_DefaultStarvation.kt
package tw.harry.labs.practice.platform02threading

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：[startQueries] 決定資料庫查詢放在哪個 Dispatcher 開始 */
private suspend fun scenario(log: LabLog, startQueries: (scope: CoroutineScope, count: Int, query: () -> Unit) -> Unit) {
    val queries = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)    // 數量和 Default 的執行緒一樣多
    val finished = AtomicInteger(0)
    coroutineScope {
        // 一個資料庫查詢：等 300 ms（執行緒被佔住）
        startQueries(this, queries) { Thread.sleep(300); finished.incrementAndGet() }
        delay(50)                                                                // 讓查詢都開始
        val began = System.nanoTime()
        withContext(Dispatchers.Default) { /* 一個很快的 CPU 運算 */ }
        val ms = (System.nanoTime() - began) / 1_000_000
        log("同時有 $queries 個資料庫查詢，CPU 運算等了 $ms ms 才開始")
    }
    log("資料庫查詢完成了 ${finished.get()} 個（共 $queries 個）")
}

// MARK: - Bug 版
private object L06Bug {
    suspend fun run(log: LabLog) = scenario(log) { scope, count, query ->
        // TODO ①：用 repeat(count) 開 count 個協程，每個在 Dispatchers.Default 上呼叫 query()（阻塞的 I/O 放在 Default）
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) = scenario(log) { scope, count, query ->
        // TODO ②：用 repeat(count) 開 count 個協程，每個在 Dispatchers.IO 上呼叫 query()（阻塞的 I/O 放在 IO）
    }
}

// MARK: - 演練設定
private fun finishedQueries(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("資料庫查詢完成了 ") }?.removePrefix("資料庫查詢完成了 ")?.substringBefore(" ")?.toIntOrNull()

private fun waited(lines: List<String>): Long? =
    lines.firstOrNull { it.contains("個資料庫查詢，CPU 運算等了 ") }
        ?.substringAfter("CPU 運算等了 ")?.substringBefore(" ms")?.toLongOrNull()

val l06 = Lab(
    id = "L06",
    title = "資料庫在等，簡單的運算卻等了好久",
    question = "第 5 題：Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 runBlocking？",
    goal = "會阻塞等待的 I/O 要放 Dispatchers.IO，不能佔住 Dispatchers.Default 那幾條給 CPU 運算用的執行緒。先寫出佔住 Default 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform02-threading/L06_DefaultStarvation.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「CPU 運算等了幾 ms 才開始」。",
        "寫 TODO ②（修正版），選「修正版」執行：改放 Dispatchers.IO 之後，CPU 運算立刻開始。",
        "想一想：Default 和 IO 共用同一個底層執行緒池，為什麼放 IO 就不會佔住 Default 的執行緒？（IO 的任務被視為會阻塞，系統會另外補執行緒給 Default）",
    ),
    expectBug = "CPU 運算等了約 250 ms 才開始（資料庫查詢佔住 Default 全部的執行緒，要等到第一個查詢做完）。",
    expectFixed = "CPU 運算等了不到 50 ms（幾乎立刻開始）。",
    file = "platform02-threading/L06_DefaultStarvation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = waited(lines)
        when {
            ms == null -> "演練沒有跑完"
            finishedQueries(lines) == 0 -> "資料庫查詢沒有開始：用 repeat(count) { scope.launch(Dispatchers.Default) { query() } }"
            ms < 150 -> "Bug 還沒重現（只等了 $ms ms）：資料庫查詢也要放在 Dispatchers.Default"
            else -> null
        }
    },
    checkFixed = { lines ->
        val ms = waited(lines)
        when {
            ms == null -> "演練沒有跑完"
            finishedQueries(lines) == 0 -> "資料庫查詢沒有開始：用 repeat(count) { scope.launch(Dispatchers.IO) { query() } }"
            ms >= 100 -> "CPU 運算還是等了 $ms ms：資料庫查詢要放 Dispatchers.IO，不要佔住 Default"
            else -> null
        }
    },
)
