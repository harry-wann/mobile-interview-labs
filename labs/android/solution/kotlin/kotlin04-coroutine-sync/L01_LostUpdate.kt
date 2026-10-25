// L01：10 筆入帳同時進來，餘額少算了（共享可變狀態與 Mutex）
// 對應複習站：Kotlin04CoroutineSync 第 1 題「多個 coroutine 同時改同一份資料，怎麼選工具？」
//
// 情境：10 個 coroutine（例如 10 個畫面元件）各自連續入帳 10 次，每次入帳 1 日圓。入帳流程是：
//        讀目前餘額 → 呼叫風控檢查（suspend，約 1 ms）→ 把「讀到的餘額 + 入帳金額」寫回去。
//        預期最後餘額 100 日圓。
// Bug 版：沒有任何保護。風控檢查暫停的這一毫秒，別的 coroutine 也讀到同一個舊餘額，
//        大家都寫回「舊餘額 + 1」，後寫的把先寫的蓋掉，入帳被吃掉（lost update，更新遺失）。
// 修正版：用 Mutex，把「讀、檢查、寫」放在同一個 withLock 裡，一次只讓一個 coroutine 進去。
package tw.harry.labs.solution.kotlin04coroutinesync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val WORKERS = 10
private const val DEPOSITS = 10

/** 風控檢查：suspend 函式，約 1 ms（兩個版本共用） */
private suspend fun riskCheck() = delay(1)

// MARK: - Bug 版
private object L01Bug {
    class Wallet {
        var balance = 0L

        suspend fun deposit(amount: Long) {
            val current = balance                           // 讀
            riskCheck()                                     // 暫停：別的 coroutine 也讀到同一個 current
            balance = current + amount                      // 寫：蓋掉別人剛寫的
        }
    }

    suspend fun run(log: LabLog) {
        val wallet = Wallet()
        coroutineScope {
            repeat(WORKERS) { launch(Dispatchers.Default) { repeat(DEPOSITS) { wallet.deposit(1) } } }
        }
        log("預期餘額 ${WORKERS * DEPOSITS}，實際餘額 ${wallet.balance}")
    }
}

// MARK: - 修正版
private object L01Fixed {
    class Wallet {
        var balance = 0L
        private val mutex = Mutex()

        suspend fun deposit(amount: Long) = mutex.withLock {   // 讀、檢查、寫都在同一個鎖裡
            val current = balance
            riskCheck()
            balance = current + amount
        }
    }

    suspend fun run(log: LabLog) {
        val wallet = Wallet()
        coroutineScope {
            repeat(WORKERS) { launch(Dispatchers.Default) { repeat(DEPOSITS) { wallet.deposit(1) } } }
        }
        log("預期餘額 ${WORKERS * DEPOSITS}，實際餘額 ${wallet.balance}")
    }
}

// MARK: - 演練設定
private fun actual(lines: List<String>): Long? =
    lines.firstOrNull { it.startsWith("預期餘額 ") }?.substringAfter("實際餘額 ")?.toLongOrNull()

val l01 = Lab(
    id = "L01",
    title = "10 筆入帳同時進來，餘額少算了",
    question = "第 1 題：多個 coroutine 同時改同一份資料，怎麼選工具？",
    goal = "10 個 coroutine 各入帳 10 次，每次 1 日圓。看沒有保護時最後餘額是多少，再用 Mutex 讓它變成 100。",
    steps = listOf(
        "選「Bug 版」執行，看「預期餘額 100，實際餘額 N」的 N。多執行幾次，數字每次可能不同。",
        "選「修正版」執行，確認實際餘額是 100。",
        "想一想：如果只把「讀」和「寫」各自包一個 withLock，會不會有用？（不會，兩次之間仍然會被插隊）",
    ),
    expectBug = "實際餘額遠小於 100（通常是 10 左右）：每一輪 10 個入帳只算到 1 個。",
    expectFixed = "「預期餘額 100，實際餘額 100」。",
    file = "kotlin04-coroutine-sync/L01_LostUpdate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val balance = actual(lines)
        when {
            balance == null || balance == 0L -> "沒有入帳：deposit 要讀餘額、呼叫 riskCheck()、再寫回 current + amount"
            balance >= WORKERS * DEPOSITS -> "餘額沒有少算：Bug 版不要加任何保護"
            else -> null
        }
    },
    checkFixed = { lines ->
        val balance = actual(lines)
        when {
            balance == null || balance == 0L -> "沒有入帳：deposit 要讀餘額、呼叫 riskCheck()、再寫回 current + amount"
            balance != (WORKERS * DEPOSITS).toLong() -> "餘額是 $balance，應該是 ${WORKERS * DEPOSITS}：讀、riskCheck()、寫要放在同一個 mutex.withLock 裡"
            else -> null
        }
    },
)
