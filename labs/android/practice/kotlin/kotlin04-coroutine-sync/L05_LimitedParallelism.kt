// L05：用 limitedParallelism(1) 保護 Realm，卻丟出「不能跨執行緒存取」（limitedParallelism 不等於單執行緒）
// 對應複習站：Kotlin04CoroutineSync 第 2 題「limitedParallelism(1) 是單執行緒嗎？可以拿來保護 Realm 嗎？」
//
// 情境：本機資料庫（像 Realm）有 thread affinity：只能在「建立它的那條執行緒」上使用，換一條就丟例外。
//        這裡用 ThreadBoundDb 模擬。每次寫入前先 delay(10)（模擬等網路），總共寫 5 次。
//        共用的執行緒池用有 2 條執行緒的 pool 代表（Dispatchers.Default／IO 也是這樣的共用池，只是執行緒更多）。
//
// 你要寫：
//   ① Bug 版（L05Bug）：用 pool.limitedParallelism(1) 當資料庫的 dispatcher，執行 scenario。它只保證互斥，delay 之後可能換執行緒。
//   ② 修正版（L05Fixed）：用真正的單執行緒 dispatcher（Executors.newSingleThreadExecutor），執行 scenario，最後 close()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L05_LimitedParallelism.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 模擬有 thread affinity 的資料庫：只能在建立它的那條執行緒使用 */
private class ThreadBoundDb {
    private val owner = Thread.currentThread()

    fun write(index: Int) {
        check(Thread.currentThread() === owner) { "寫入 #$index 失敗：資料庫是在 ${owner.name} 建立的，不能在 ${Thread.currentThread().name} 使用" }
    }
}

/** 演練流程（兩個版本共用）：在 dispatcher 上建立資料庫，每次 delay(10) 之後寫入，共 5 次 */
private suspend fun scenario(log: LabLog, dispatcher: CoroutineDispatcher) {
    var ok = 0
    var wrongThread = 0
    withContext(dispatcher) {
        val db = ThreadBoundDb()
        repeat(5) { i ->
            delay(10)
            try {
                db.write(i)
                ok++
            } catch (e: IllegalStateException) {
                wrongThread++
            }
        }
    }
    log("寫入成功 $ok 次，跨執行緒失敗 $wrongThread 次")
}

// MARK: - Bug 版
private object L05Bug {
    suspend fun run(log: LabLog) {
        val counter = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2) { Thread(it, "pool-${counter.incrementAndGet()}") }   // 共用的執行緒池，2 條
        try {
            // TODO ①：val serial = pool.asCoroutineDispatcher().limitedParallelism(1)，然後呼叫 scenario(log, serial)
            //        （limitedParallelism(1) 只保證同一時間最多一個任務在跑，不保證同一條執行緒）
        } finally {
            pool.shutdown()
        }
    }
}

// MARK: - 修正版
private object L05Fixed {
    suspend fun run(log: LabLog) {
        // TODO ②-1：建立真正的單執行緒 dispatcher：Executors.newSingleThreadExecutor { Thread(it, "db-thread") }.asCoroutineDispatcher()
        try {
            // TODO ②-2：呼叫 scenario(log, 你建立的 dispatcher)
        } finally {
            // TODO ②-3：用完要 close()，否則那條執行緒不會釋放
        }
    }
}

// MARK: - 演練設定
private fun failures(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("寫入成功 ") }?.substringAfter("跨執行緒失敗 ")?.removeSuffix(" 次")?.toIntOrNull()

val l05 = Lab(
    id = "L05",
    title = "limitedParallelism(1) 保護資料庫，卻跨執行緒失敗",
    question = "第 2 題：limitedParallelism(1) 是單執行緒嗎？可以拿來保護 Realm 嗎？",
    goal = "有 thread affinity 的資料庫只能在建立它的執行緒使用。先寫出 limitedParallelism(1) 擋不住的 Bug 版，再改成真正的單執行緒 dispatcher。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L05_LimitedParallelism.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「跨執行緒失敗」幾次。想一想：為什麼 limitedParallelism(1) 每次 delay 之後會換執行緒？",
        "寫 TODO ②-1、②-2、②-3（修正版），選「修正版」執行，確認 5 次都成功。",
        "想一想：為什麼修正版最後一定要 close()？（單執行緒的 executor 不關就一直占著一條執行緒）",
    ),
    expectBug = "至少 1 次「跨執行緒失敗」（第 1 次 delay 之後就換到池子裡的另一條執行緒）。",
    expectFixed = "「寫入成功 5 次，跨執行緒失敗 0 次」。",
    file = "kotlin04-coroutine-sync/L05_LimitedParallelism.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val failed = failures(lines)
        when {
            failed == null -> "演練沒有跑完：要用 scenario(log, dispatcher) 執行"
            failed == 0 -> "沒有跨執行緒失敗：Bug 版要用有 2 條執行緒的池子加上 limitedParallelism(1)"
            else -> null
        }
    },
    checkFixed = { lines ->
        val failed = failures(lines)
        when {
            failed == null -> "演練沒有跑完：要用 scenario(log, dispatcher) 執行"
            failed != 0 -> "還是跨執行緒失敗 $failed 次：要用 Executors.newSingleThreadExecutor().asCoroutineDispatcher()"
            else -> null
        }
    },
)
