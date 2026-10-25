// L06：ConcurrentHashMap 的 getOrPut，同一個 Session 被建立了好幾次
// 對應複習站：Kotlin10JavaInterop 第 6 題「HashMap 和 ConcurrentHashMap 差在哪？」
//
// 情境：SessionRegistry 用 ConcurrentHashMap 保存每個帳號的 Session，沒有就建立（建立 Session 要開一條連線，所以只能建立一次）。
//        每一輪有 8 條執行緒同時取得同一個帳號的 Session，共 20 輪，應該只建立 20 個 Session。
// Bug 版：用 Kotlin 的 getOrPut { Session(id) }。ConcurrentMap.getOrPut 不是原子的：先 get，沒有才執行預設值函式再 putIfAbsent，
//        8 條執行緒可能都沒有 get 到，各自執行預設值函式，Session 被建立了好幾次（最後存進 map 的只有一個，其他的白白開了連線）。
// 修正版：用 computeIfAbsent(id) { Session(id) }。它對單一 key 是原子的，預設值函式只會執行一次。
package tw.harry.labs.solution.kotlin10javainterop

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
        sessions.getOrPut("harry") { L06Session("harry", created) }          // 不是原子的：預設值函式可能執行很多次
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) = scenario(log) { sessions, created ->
        sessions.computeIfAbsent("harry") { L06Session("harry", created) }   // 對單一 key 是原子的：只執行一次
    }
}

// MARK: - 演練設定
private fun createdCount(lines: List<String>): Int? =
    lines.firstOrNull { it.contains("共建立了 ") }?.substringAfter("共建立了 ")?.substringBefore(" 個")?.toIntOrNull()

val l06 = Lab(
    id = "L06",
    title = "同一個 Session 被建立了好幾次",
    question = "第 6 題：HashMap 和 ConcurrentHashMap 差在哪？",
    goal = "8 條執行緒同時要同一個 Session，應該只建立一次。看 Kotlin 的 getOrPut 在 ConcurrentHashMap 上為什麼會建立多次，再改用 computeIfAbsent。",
    steps = listOf(
        "選「Bug 版」執行，看「共建立了 N 個（應該是 20 個）」的 N。",
        "選「修正版」執行，確認剛好是 20 個。",
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
