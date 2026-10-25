// L05：每個存取都有同步，餘額還是被扣成負的（race condition：先檢查、再行動）
// 對應複習站：Kotlin10JavaInterop 第 5 題「happens-before 是什麼？volatile、
// synchronized、Atomic 各保證什麼？」
//
// 情境：帳戶餘額 100 日圓，4 條執行緒同時開始，各嘗試提領 1 日圓 100 次（共
// 400 次嘗試）。正確結果是剛好成功 100 次，餘額 0。
//        每次提領在「檢查餘額」和「扣款」之間，還要做一次風控檢查（約 20
// 微秒）。
//        演練重複最多 100 輪，看有沒有哪一輪成功的次數超過 100。
// Bug 版：讀餘額和寫餘額各自都有 @Synchronized，但「檢查餘額夠不夠」和「扣款」
// 是兩次分開的呼叫，
//        兩條執行緒都看到餘額夠，都去扣款，成功次數超過餘額（沒有 data race，
// 但有 race condition）。
// 修正版：把「檢查加扣款」放進同一個 synchronized(lock) 區塊，
// 一次只有一條執行緒能做完整個動作。
package tw.harry.labs.solution.kotlin10javainterop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val INITIAL = 100L
private const val ROUNDS = 100

/** 風控檢查：約 20 微秒（兩個版本共用） */
private fun riskCheck() = LockSupport.parkNanos(20_000)

/**
 * 演練流程（兩個版本共用）：每輪建一個新帳戶，4 條執行緒各嘗試提領 100 次；
 * [newAccount] 回傳「提領 1 日圓是否成功」的函式
 */
private suspend fun scenario(
    log: LabLog,
    newAccount: (initial: Long) -> () -> Boolean,
) {
    var overdrawn = 0
    var short = 0
    var rounds = 0
    withContext(Dispatchers.Default) {
        while (rounds < ROUNDS && overdrawn < 3) {
            val withdraw = newAccount(INITIAL)
            val succeeded = AtomicInteger()
            val start = CountDownLatch(1)
            val threads =
                List(4) {
                    thread {
                        start.await(); repeat(100) {
                            if (withdraw()) succeeded.incrementAndGet()
                        }
                    }
                }
            // 4 條執行緒同時開始
            start.countDown()
            threads.forEach { it.join() }
            if (succeeded.get() > INITIAL) overdrawn++
            if (succeeded.get() < INITIAL) short++
            rounds++
        }
    }
    log("跑了 $rounds 輪，其中 $overdrawn 輪成功提領的次數超過餘額（$INITIAL 次）")
    log("另外有 $short 輪成功提領的次數不到 $INITIAL 次")
}

// MARK: - Bug 版
private object L05Bug {
    class Account(initial: Long) {
        private var balance = initial

        @Synchronized
        fun balance(): Long = balance // 讀有同步

        @Synchronized
        fun setBalance(value: Long) { // 寫也有同步
            balance = value
        }

        fun withdraw(amount: Long): Boolean {
            if (balance() >= amount) { // 檢查
                // 風控檢查：這段時間，別的執行緒也通過了上面的檢查
                riskCheck()
                setBalance(balance() - amount) // 行動
                return true
            }
            return false
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { initial ->
        val account = Account(initial)
        val withdrawOne: () -> Boolean = { account.withdraw(1) }
        withdrawOne
    }
}

// MARK: - 修正版
private object L05Fixed {
    class Account(initial: Long) {
        private var balance = initial
        private val lock = Any()

        // 檢查和扣款在同一個鎖裡
        fun withdraw(amount: Long): Boolean = synchronized(lock) {
            if (balance >= amount) {
                riskCheck()
                balance -= amount
                true
            } else {
                false
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { initial ->
        val account = Account(initial)
        val withdrawOne: () -> Boolean = { account.withdraw(1) }
        withdrawOne
    }
}

// MARK: - 演練設定
private fun overdrawn(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("跑了 ")
}?.substringAfter("其中 ")?.substringBefore(" 輪")?.toIntOrNull()

private fun shortRounds(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("另外有 ")
}?.removePrefix("另外有 ")?.substringBefore(" 輪")?.toIntOrNull()

val l05 = Lab(
    id = "L05",
    title = "每個存取都同步了，餘額還是超額扣款",
    question = "第 5 題：happens-before 是什麼？volatile、synchronized、Atomic 各保證什麼？",
    goal = "讀餘額、寫餘額各自都有同步，4 條執行緒同時提領時仍然超額。看 check-then-act 為什麼不安全，再把檢查加扣款放進同一個鎖。",
    steps = listOf(
        "選「Bug 版」執行，看「其中 N 輪成功提領的次數超過餘額」的 N。",
        "選「修正版」執行，確認 N 是 0（跑完 100 輪）。",
        "想一想：data race 和 race condition 差在哪？（data race 是沒有同步的並行存取；race condition 是結果取決於執行時間順序，每次存取都有同步也可能發生）",
    ),
    expectBug = "最多跑 100 輪；通常前幾輪就有成功提領超過 100 次的情況（累積 3 輪就停止）。",
    expectFixed = "「跑了 100 輪，其中 0 輪成功提領的次數超過餘額」。",
    file = "kotlin10-java-interop/L05_CheckThenAct.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (overdrawn(lines)) {
            null -> "演練沒有跑完"
            0 -> "沒有超額扣款：Bug 版的 withdraw 要先 balance() 檢查，再 riskCheck()，最後 setBalance(balance() - amount)，三步分開呼叫"
            else -> null
        }
    },
    checkFixed = { lines ->
        val over = overdrawn(lines)
        val under = shortRounds(lines)
        when {
            over == null || under == null -> "演練沒有跑完"
            over > 0 -> "還是有超額扣款：檢查和扣款要放進同一個 synchronized(lock) { … }"
            under > 0 -> "有 $under 輪成功提領的次數不到 $INITIAL 次：withdraw 要檢查 balance >= amount，夠就 balance -= amount 並回傳 true"
            !lines.any { it.startsWith("跑了 $ROUNDS 輪") } -> "沒有跑完 $ROUNDS 輪"
            else -> null
        }
    },
)
