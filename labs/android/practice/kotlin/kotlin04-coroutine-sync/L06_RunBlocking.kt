// L06：按下按鈕後畫面卡住不動（runBlocking 卡住執行緒）
// 對應複習站：Kotlin04CoroutineSync 第 3 題「runBlocking
// 為什麼危險？什麼時候可以用？」
//
// 情境：用一條叫 fake-main 的執行緒代表 Android 主執行緒，上面有一個「畫面」每
// 10 ms 更新一次。
//        使用者按下按鈕，要向伺服器載入資料（約 600 ms）。
// 演練會量「畫面最長一次沒有更新」是多久。
//
// 你要寫：
//   ① Bug 版（L06Bug）：按鈕的處理函式在主執行緒上用 runBlocking 等資料，
// 卡住主執行緒約 600 ms。
//   ② 修正版（L06Fixed）：處理函式直接用 withContext(Dispatchers.IO)
// 到背景等資料，主執行緒繼續更新畫面。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L06_RunBlocking.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的載入資料 API：約 600 ms（兩個版本共用） */
private suspend fun loadOrders(): String {
    delay(600)
    return "50 筆訂單"
}

/**
 * 演練流程（兩個版本共用）：在 fake-main 上更新畫面；0.1 秒後使用者按下按鈕，
 * 執行 onTap
 */
private suspend fun scenario(log: LabLog, onTap: suspend () -> String) {
    val main = Executors.newSingleThreadExecutor {
        Thread(it, "fake-main")
    }.asCoroutineDispatcher()
    val maxGapMs = AtomicLong(0)
    try {
        coroutineScope {
            // 畫面：每 10 ms 更新一次，記下兩次更新之間最長隔了多久
            val frames = launch(main) {
                var last = System.nanoTime()
                while (isActive) {
                    delay(10)
                    val now = System.nanoTime()
                    maxGapMs.updateAndGet {
                        maxOf(it, (now - last) / 1_000_000)
                    }
                    last = now
                }
            }
            delay(100)
            // 按鈕的處理函式在主執行緒上執行
            val result = withContext(main) { onTap() }
            log("載入完成：$result")
            frames.cancelAndJoin()
        }
    } finally {
        main.close()
    }
    log("畫面最長一次沒有更新：${maxGapMs.get()} ms")
}

// MARK: - Bug 版
private object L06Bug {
    suspend fun run(log: LabLog) {
        scenario(log) {
            // TODO ①：在這裡用 runBlocking { withContext(Dispatchers.IO) {
            // loadOrders() } } 等資料，並回傳結果（卡住主執行緒）
            "（還沒寫）"
        }
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) {
        scenario(log) {
            // TODO ②：不要用 runBlocking，直接 withContext(Dispatchers.IO) {
            // loadOrders() } 並回傳結果
            "（還沒寫）"
        }
    }
}

// MARK: - 演練設定
private fun maxGap(lines: List<String>): Long? = lines.firstOrNull {
    it.startsWith("畫面最長一次沒有更新：")
}?.removePrefix("畫面最長一次沒有更新：")?.removeSuffix(" ms")?.toLongOrNull()

val l06 = Lab(
    id = "L06",
    title = "按下按鈕後，畫面卡住不動",
    question = "第 3 題：runBlocking 為什麼危險？什麼時候可以用？",
    goal = "按鈕的處理函式在主執行緒上用 runBlocking 等資料。先寫出卡住畫面的 Bug 版並量卡了多久，再改成 suspend 函式。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L06_RunBlocking.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「畫面最長一次沒有更新」是多少 ms。",
        "寫 TODO ②（修正版），選「修正版」執行，確認畫面最多只隔幾十 ms。",
        "想一想：如果 Bug 版的 runBlocking 裡面是 withContext(Dispatchers.Main)（要回到主執行緒），會怎樣？（主執行緒等 runBlocking，runBlocking 等主執行緒，互相等，永遠卡住）",
    ),
    expectBug = "「畫面最長一次沒有更新」約 600 ms（至少 500 ms）。",
    expectFixed = "「畫面最長一次沒有更新」只有幾十 ms（低於 300 ms），資料照樣載入完成。",
    file = "kotlin04-coroutine-sync/L06_RunBlocking.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val gap = maxGap(lines)
        when {
            "載入完成：50 筆訂單" !in lines -> "資料沒有載入：onTap 要載入訂單（loadOrders()）"
            gap == null || gap < 500 -> "畫面沒有卡住（只隔 ${gap ?: 0} ms）：Bug 版要在 onTap 裡用 runBlocking 等資料"
            else -> null
        }
    },
    checkFixed = { lines ->
        val gap = maxGap(lines)
        when {
            "載入完成：50 筆訂單" !in lines -> "資料沒有載入：onTap 要載入訂單（loadOrders()）"
            gap == null || gap >= 300 -> "畫面還是卡了 ${gap ?: 0} ms：onTap 不要用 runBlocking，改用 withContext(Dispatchers.IO)"
            else -> null
        }
    },
)
