// L03：出金時卡住不動了（Mutex 不可重入造成死結）
// 對應複習站：Kotlin04CoroutineSync 第 1 題「多個 coroutine 同時改同一份資料，
// 怎麼選工具？」
//
// 情境：Account 用 Mutex 保護餘額。withdraw() 扣款後，
// 要順便取一份餘額快照（snapshot()）寫進稽核紀錄。
//        snapshot() 平常也會被別的地方呼叫，所以它自己也會拿鎖。
//
// 你要寫：
//   ① Bug 版（L03Bug）：withdraw() 在 withLock 裡呼叫 snapshot()
// （它也要拿同一把鎖）。Mutex 不可重入，永遠等下去，300 ms 後逾時。
//   ② 修正版（L03Fixed）：拆出不拿鎖的 snapshotLocked()，withdraw()
// 在鎖裡呼叫它；對外的 snapshot() 自己拿鎖再呼叫它。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L03_MutexReentrant.kt
package tw.harry.labs.practice.kotlin04coroutinesync

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
            // TODO ①：回傳 snapshot() 的結果（已經持鎖，又想拿同一把鎖 →
            // 永遠等下去）
            0
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
            // TODO ②-2：已經持鎖，回傳 snapshotLocked() 的結果
            0
        }

        // TODO ②-3：改成呼叫 snapshotLocked()
        suspend fun snapshot(): Long = mutex.withLock { balance }

        // TODO ②-1：寫 private fun snapshotLocked(): Long，直接回傳 balance，
        // 不拿鎖（呼叫它之前，一定要已經拿著鎖）
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
    goal = "withdraw() 持鎖時又呼叫要拿同一把鎖的 snapshot()。先寫出死結的 Bug 版，再把「已持鎖」的版本拆成內部函式。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L03_MutexReentrant.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看是不是等了 300 ms 才印出「死結」。",
        "寫 TODO ②-1、②-2、②-3（修正版），選「修正版」執行，確認立刻完成，餘額 900，而且對外的 snapshot() 仍然可以單獨使用。",
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
