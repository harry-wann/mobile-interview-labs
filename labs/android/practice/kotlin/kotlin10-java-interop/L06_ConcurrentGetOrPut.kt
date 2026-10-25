// L06：ConcurrentHashMap 的 getOrPut，同一個 Session 被建立了好幾次
// 對應複習站：Kotlin10JavaInterop 第 6 題「HashMap 和 ConcurrentHashMap 差在哪？」
//
// 情境：SessionRegistry 用 ConcurrentHashMap 保存每個帳號的 Session，沒有就建立（建立 Session 要開一條連線，所以只能建立一次）。
//        每一輪有 8 條執行緒同時取得同一個帳號的 Session，共 20 輪，應該只建立 20 個 Session。
//
// 你要寫：
//   ① Bug 版（L06Bug）：sessions.getOrPut("harry") { L06Session("harry", created) }，不是原子的。
//   ② 修正版（L06Fixed）：sessions.computeIfAbsent("harry") { L06Session("harry", created) }，預設值函式只執行一次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L06_ConcurrentGetOrPut.kt
package tw.harry.labs.practice.kotlin10javainterop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val ROUNDS = 20
private const val THREADS = 8

/** 假的 Session：建立時要開連線（約 0.1 ms），並記下一共建立了幾個 */
private class L06Session(val id: String, created: AtomicInteger) {
    init {
        created.incrementAndGet()
        LockSupport.parkNanos(100_000)
    }
}

/**
 * 演練流程（兩個版本共用）：共 [ROUNDS] 輪，每輪 [THREADS] 條執行緒同時取得同一個帳號的 Session。
 * [getSession] 是「取得（沒有就建立）Session」的函式。
 */
private suspend fun scenario(log: LabLog, getSession: (sessions: ConcurrentHashMap<String, L06Session>, created: AtomicInteger) -> L06Session) {
    val created = AtomicInteger()
    withContext(Dispatchers.Default) {
        repeat(ROUNDS) {
            val sessions = ConcurrentHashMap<String, L06Session>()
            val start = CountDownLatch(1)
            val threads = List(THREADS) { thread { start.await(); getSession(sessions, created) } }
            start.countDown()
            threads.forEach { it.join() }
        }
    }
    log("$ROUNDS 輪、每輪 $THREADS 條執行緒同時取得同一個帳號的 Session：共建立了 ${created.get()} 個（應該是 $ROUNDS 個）")
}

// MARK: - Bug 版
private object L06Bug {
    suspend fun run(log: LabLog) = scenario(log) { sessions, created ->
        // TODO ①：用 sessions.getOrPut("harry") { L06Session("harry", created) } 取得（沒有就建立）Session
        L06Session("harry", AtomicInteger())
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) = scenario(log) { sessions, created ->
        // TODO ②：用 sessions.computeIfAbsent("harry") { L06Session("harry", created) } 取得（沒有就建立）Session
        L06Session("harry", AtomicInteger())
    }
}

// MARK: - 演練設定
private fun createdCount(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("共建立了 ") }?.substringAfter("共建立了 ")?.substringBefore(" 個")?.toIntOrNull()

val l06 = Lab(
    id = "L06",
    title = "同一個 Session 被建立了好幾次",
    question = "第 6 題：HashMap 和 ConcurrentHashMap 差在哪？",
    goal = "8 條執行緒同時要同一個 Session，應該只建立一次。看 Kotlin 的 getOrPut 在 ConcurrentHashMap 上為什麼會建立多次，再改用 computeIfAbsent。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L06_ConcurrentGetOrPut.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「共建立了 N 個（應該是 20 個）」的 N。",
        "寫 TODO ②（修正版），選「修正版」執行，確認剛好是 20 個。",
        "想一想：containsKey 再 put 和 getOrPut 的問題一樣嗎？（一樣，都是 check-then-act：檢查和行動之間，別的執行緒也通過了檢查）",
    ),
    expectBug = "「共建立了」的數字大於 20（通常接近 160，也就是每輪每條執行緒各建立一個）：預設值函式被重複執行。",
    expectFixed = "「共建立了 20 個（應該是 20 個）」。",
    file = "kotlin10-java-interop/L06_ConcurrentGetOrPut.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = createdCount(lines)
        when {
            n == null || n == 0 -> "沒有建立任何 Session：要在 getSession 裡取得（沒有就建立）Session(\"harry\", created)"
            n <= ROUNDS -> "每輪只建立了一個 Session（共 $n 個）：Bug 版要用 getOrPut { … }"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = createdCount(lines)
        when {
            n == null || n == 0 -> "沒有建立任何 Session：要在 getSession 裡取得（沒有就建立）Session(\"harry\", created)"
            n != ROUNDS -> "共建立了 $n 個，應該是 $ROUNDS 個：改用 computeIfAbsent(\"harry\") { … }"
            else -> null
        }
    },
)
