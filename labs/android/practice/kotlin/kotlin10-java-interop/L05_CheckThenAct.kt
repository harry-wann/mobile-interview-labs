// L05：每個存取都有同步，餘額還是被扣成負的（race condition：先檢查、再行動）
// 對應複習站：Kotlin10JavaInterop 第 5 題「happens-before 是什麼？volatile、
// synchronized、Atomic 各保證什麼？」
//
// 情境：帳戶餘額 100 日圓，4 條執行緒同時開始，各嘗試提領 1 日圓 100 次（共
// 400 次嘗試）。正確結果是剛好成功 100 次，餘額 0。
//        每次提領在「檢查餘額」和「扣款」之間，還要做一次風控檢查（約 20
// 微秒）。
//        演練重複最多 100 輪，看有沒有哪一輪成功的次數超過 100。
//
// 你要寫：
//   ① Bug 版（L05Bug.Account.withdraw）：先用 balance() 檢查夠不夠，接著
// riskCheck()，再 setBalance(balance() - amount)，三步分開呼叫（讀、寫各自都有
// @Synchronized）。
//   ② 修正版（L05Fixed.Account）：用 synchronized(lock) 把「檢查、riskCheck()、
// 扣款」放在同一個區塊。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L05_CheckThenAct.kt
package tw.harry.labs.practice.kotlin10javainterop

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

        // TODO ①：withdraw(amount)：if (balance() >= amount) { riskCheck();
        // setBalance(balance() - amount); return true }，否則回傳 false
        //         （檢查與扣款是分開的呼叫，每次呼叫各自有同步）
        fun withdraw(amount: Long): Boolean = false
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
        // TODO ②-1：private var balance = initial；private val lock = Any()
        // TODO ②-2：withdraw(amount)：整段放進 synchronized(lock) { … }：if
        // (balance >= amount) { riskCheck(); balance -= amount; true } else
        // false
        fun withdraw(amount: Long): Boolean = false
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
    goal = "讀餘額、寫餘額各自都有同步，4 條執行緒同時提領時仍然超額。看 check-then-act 為什麼不安全，再把檢查加扣款放進同一個鎖。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L05_CheckThenAct.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「其中 N 輪成功提領的次數超過餘額」的 N。",
        "寫 TODO ②（修正版），選「修正版」執行，確認 N 是 0（跑完 100 輪）。",
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
