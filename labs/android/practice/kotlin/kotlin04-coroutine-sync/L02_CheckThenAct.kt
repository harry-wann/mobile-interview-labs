// L02：10 個地方同時要餘額，API 被打了 10 次（check-then-act）
// 對應複習站：Kotlin04CoroutineSync 第 1 題「多個 coroutine 同時改同一份資料，怎麼選工具？」
//
// 情境：資產畫面有 10 個元件同時呼叫 BalanceRepo.getBalance()。餘額有快取：有快取就直接回傳，沒有才打 API（約 100 ms）。
//
// 你要寫：
//   ① Bug 版（L02Bug）：先檢查快取、沒有再打 API 並存起來，不加鎖。10 個 coroutine 都在快取還是 null 時通過檢查，API 被打 10 次。
//   ② 修正版（L02Fixed）：用 Mutex，把檢查和動作放在同一個 withLock 裡，API 只打 1 次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L02_CheckThenAct.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的餘額 API：每次呼叫 100 ms，並記下被呼叫幾次 */
private class BalanceApi {
    val calls = AtomicInteger(0)

    suspend fun fetchBalance(): Long {
        calls.incrementAndGet()
        delay(100)
        return 1_500_000
    }
}

// MARK: - Bug 版
private object L02Bug {
    class BalanceRepo(private val api: BalanceApi) {
        private var cache: Long? = null

        suspend fun getBalance(): Long {
            // TODO ①：不加鎖，依序：有快取就直接回傳（cache?.let { return it }）→ 否則 api.fetchBalance() → 存進 cache → 回傳
            return 0
        }
    }

    suspend fun run(log: LabLog) {
        val api = BalanceApi()
        val repo = BalanceRepo(api)
        val results = coroutineScope { List(10) { async(Dispatchers.Default) { repo.getBalance() } }.awaitAll() }
        log("10 個元件都拿到 ${results.toSet()}")
        log("API 被呼叫 ${api.calls.get()} 次")
    }
}

// MARK: - 修正版
private object L02Fixed {
    class BalanceRepo(private val api: BalanceApi) {
        private var cache: Long? = null
        // TODO ②-1：宣告 private val mutex = Mutex()

        suspend fun getBalance(): Long {
            // TODO ②-2：整段放進 mutex.withLock { }：快取是 null 才 api.fetchBalance() 並存進 cache，最後回傳 cache
            return 0
        }
    }

    suspend fun run(log: LabLog) {
        val api = BalanceApi()
        val repo = BalanceRepo(api)
        val results = coroutineScope { List(10) { async(Dispatchers.Default) { repo.getBalance() } }.awaitAll() }
        log("10 個元件都拿到 ${results.toSet()}")
        log("API 被呼叫 ${api.calls.get()} 次")
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "10 個元件同時要餘額，API 被打了 10 次",
    question = "第 1 題：多個 coroutine 同時改同一份資料，怎麼選工具？",
    goal = "餘額有快取，但 10 個元件同時第一次要餘額。先寫出「先檢查再動作」被插隊的 Bug 版，再用 Mutex 修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin04-coroutine-sync/L02_CheckThenAct.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「API 被呼叫 N 次」。",
        "寫 TODO ②-1、②-2（修正版），選「修正版」執行，確認只打 1 次，而且 10 個元件都拿到同一個餘額。",
        "想一想：這和 L01 的 Mutex 有什麼相同的地方？（檢查與動作必須在同一個 withLock 裡）",
    ),
    expectBug = "「API 被呼叫 10 次」，10 個元件都拿到 1500000。",
    expectFixed = "「API 被呼叫 1 次」，10 個元件都拿到 1500000。",
    file = "kotlin04-coroutine-sync/L02_CheckThenAct.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "10 個元件都拿到 [1500000]" !in lines -> "10 個元件都要拿到 1500000：getBalance 要回傳餘額"
            "API 被呼叫 10 次" !in lines -> "Bug 版 API 要被呼叫 10 次：先 cache?.let { return it }，再 api.fetchBalance()，不要加鎖"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "10 個元件都拿到 [1500000]" !in lines -> "10 個元件都要拿到 1500000：getBalance 要回傳餘額"
            "API 被呼叫 1 次" !in lines -> "API 應該只被呼叫 1 次：檢查快取與呼叫 API 要放在同一個 mutex.withLock 裡"
            else -> null
        }
    },
)
