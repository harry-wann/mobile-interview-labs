// L03：出金時卡住不動了（Mutex 不可重入造成死結）
// 對應複習站：Kotlin04CoroutineSync 第 1 題「多個 coroutine 同時改同一份資料，怎麼選工具？」
//
// 情境：Account 用 Mutex 保護餘額。withdraw() 扣款後，要順便取一份餘額快照（snapshot()）寫進稽核紀錄。
//        snapshot() 平常也會被別的地方呼叫，所以它自己也會拿鎖。
// Bug 版：withdraw() 已經拿著鎖，又呼叫 snapshot() 想再拿同一把鎖。Kotlin 的 Mutex 不可重入，
//        同一個 coroutine 持鎖時再 lock() 會永遠等下去（deadlock，死結）。這裡用 withTimeoutOrNull 當保險，300 ms 後放棄。
// 修正版：把「已經持鎖」的版本拆成內部函式 snapshotLocked()，withdraw() 在鎖裡直接呼叫它；
//        對外的 snapshot() 才自己拿鎖，再呼叫 snapshotLocked()。
package tw.harry.labs.solution.kotlin04coroutinesync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L03Bug {
    class Account(private var balance: Long) {
        private val mutex = Mutex()

        suspend fun withdraw(amount: Long): Long = mutex.withLock {
            balance -= amount
            snapshot()                                      // 已經持鎖，又想拿同一把鎖 → 永遠等下去
        }

        suspend fun snapshot(): Long = mutex.withLock { balance }
    }

    suspend fun run(log: LabLog) {
        val account = Account(1000)
        val result = withTimeoutOrNull(300) { account.withdraw(100) }
        log(if (result == null) "出金 300 ms 內沒有完成：死結" else "出金完成，餘額 $result")
    }
}

// MARK: - 修正版
private object L03Fixed {
    class Account(private var balance: Long) {
        private val mutex = Mutex()

        suspend fun withdraw(amount: Long): Long = mutex.withLock {
            balance -= amount
            snapshotLocked()                                // 已經持鎖：呼叫不拿鎖的內部版本
        }

        suspend fun snapshot(): Long = mutex.withLock { snapshotLocked() }

        private fun snapshotLocked(): Long = balance        // 呼叫它之前，一定要已經拿著鎖
    }

    suspend fun run(log: LabLog) {
        val account = Account(1000)
        val result = withTimeoutOrNull(300) { account.withdraw(100) }
        log(if (result == null) "出金 300 ms 內沒有完成：死結" else "出金完成，餘額 $result")
        log("另外單獨取快照：${account.snapshot()}")
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "出金時卡住不動，Mutex 死結",
    question = "第 1 題：多個 coroutine 同時改同一份資料，怎麼選工具？",
    goal = "withdraw() 持鎖時又呼叫要拿同一把鎖的 snapshot()。看死結怎麼發生，再把「已持鎖」的版本拆成內部函式。",
    steps = listOf(
        "選「Bug 版」執行，看是不是等了 300 ms 才印出「死結」。",
        "選「修正版」執行，確認立刻完成，餘額 900，而且對外的 snapshot() 仍然可以單獨使用。",
        "想一想：如果不加 withTimeoutOrNull，Bug 版會怎樣？（永遠不會結束，畫面上的出金按鈕就一直轉圈圈）",
    ),
    expectBug = "300 ms 後印出「出金 300 ms 內沒有完成：死結」。",
    expectFixed = "「出金完成，餘額 900」，接著「另外單獨取快照：900」。",
    file = "kotlin04-coroutine-sync/L03_MutexReentrant.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "出金 300 ms 內沒有完成：死結" !in lines -> "Bug 版 withdraw() 要卡住：在 mutex.withLock 裡呼叫另一個也會 withLock 的 snapshot()"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "出金完成，餘額 900" !in lines -> "出金要完成且餘額 900：withdraw() 在鎖裡改呼叫不拿鎖的 snapshotLocked()"
            "另外單獨取快照：900" !in lines -> "對外的 snapshot() 要自己拿鎖，再呼叫 snapshotLocked()"
            else -> null
        }
    },
)
