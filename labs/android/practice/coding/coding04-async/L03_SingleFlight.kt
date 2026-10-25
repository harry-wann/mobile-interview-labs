// L03：API 故障過一次，之後每次要這份設定都直接失敗，要重開 App 才好（single-flight 把失敗也留下來了）
// 對應複習站：Coding04Async 第 3 題「好幾個地方同時要同一份資料，怎麼只載入一次？」
//
// 情境：交易對設定用 single-flight 快取（Mutex 保護的 Map，裡面記錄每個 key 正在進行的 CompletableDeferred），
//       好幾個畫面同時要同一份就共用同一個請求。演練分三輪：
//       ① BTC 同時要 3 次、ETH 同時要 2 次，API 正常；② SOL 同時要 2 次，這一次 API 剛好故障；③ API 恢復後，SOL 再要一次。
//       （Mutex 把「檢查快取」和「打 API」放在同一個 withLock 裡，見 Kotlin04 L02；token 過期時只刷新一次，見 Kotlin04 L07。）
//
// 你要寫：
//   ① Bug 版（L03Bug.SingleFlight.get）：登記 inFlight、等別人載入的部分已經寫好，你要寫「自己載入」那段：
//      try { val loaded = api.fetch(key)；mutex.withLock { values[key] = loaded；inFlight.remove(key) }；deferred.complete(loaded)；return loaded }
//      catch (e: C04ApiException) { deferred.completeExceptionally(e)；throw e }。失敗時不要把 inFlight 清掉（Bug 版就是沒清）。
//   ② 修正版（L03Fixed.SingleFlight.get）：同樣的步驟；catch 裡多一行：先 mutex.withLock { inFlight.remove(key) }，再 completeExceptionally 與 throw。
// 只改有 TODO 的地方（查 values、查 inFlight、登記 Deferred 已經寫好）。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/coding/coding04-async/L03_SingleFlight.kt
package tw.harry.labs.practice.coding04async

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的設定 API：每次花 80 毫秒，並記下每個 key 被呼叫幾次；指定的 key 第一次呼叫會失敗（逾時） */
private class L03Api(private val failFirstCall: Set<String>) {
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun fetch(key: String): String {
        val n = calls.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        delay(80)
        if (key in failFirstCall && n == 1) throw C04ApiException(C04ApiError.TIMEOUT)
        return "$key 的設定"
    }

    fun count(key: String): Int = calls[key]?.get() ?: 0
}

/** 演練流程（兩個版本共用）：三輪呼叫。make 用假 API 建出「取得設定」的函式 */
private suspend fun scenario(log: LabLog, make: (L03Api) -> suspend (String) -> String) {
    val api = L03Api(setOf("SOL"))
    val value = make(api)

    // ① BTC 同時要 3 次、ETH 同時要 2 次
    val okCount = coroutineScope {
        listOf("BTC", "BTC", "BTC", "ETH", "ETH").map { key ->
            async { runCatching { value(key) }.isSuccess }
        }.awaitAll().count { it }
    }
    log("① BTC 同時要 3 次、ETH 同時要 2 次：$okCount 個拿到設定，API 呼叫 BTC ${api.count("BTC")} 次、ETH ${api.count("ETH")} 次")

    // ② SOL 同時要 2 次，API 這次故障
    val failedCount = coroutineScope {
        List(2) { async { runCatching { value("SOL") }.isFailure } }.awaitAll().count { it }
    }
    log("② SOL 同時要 2 次（API 故障）：$failedCount 個失敗，API 呼叫 ${api.count("SOL")} 次")

    // ③ API 恢復後，SOL 再要一次
    try {
        val text = value("SOL")
        log("③ API 恢復後 SOL 再要一次：成功（$text），API 累計 ${api.count("SOL")} 次")
    } catch (e: C04ApiException) {
        log("③ API 恢復後 SOL 再要一次：還是失敗，API 累計 ${api.count("SOL")} 次")
    }
}

// MARK: - Bug 版
private object L03Bug {
    class SingleFlight(private val api: L03Api) {
        private val mutex = Mutex()
        private val values = HashMap<String, String>()
        private val inFlight = HashMap<String, CompletableDeferred<String>>()

        suspend fun get(key: String): String {
            var owner = false
            val deferred = mutex.withLock {
                values[key]?.let { return it }                                  // 已經有值
                inFlight[key] ?: CompletableDeferred<String>().also {           // 沒有人在載入：我來載入，先登記
                    inFlight[key] = it
                    owner = true
                }
            }
            if (!owner) return deferred.await()                                 // 有人正在載入：等同一個結果
            // TODO ①-1：自己載入：val loaded = api.fetch(key)；成功後 mutex.withLock { values[key] = loaded；inFlight.remove(key) }；
            //          deferred.complete(loaded)；return loaded
            // TODO ①-2：失敗（catch (e: C04ApiException)）：deferred.completeExceptionally(e) 讓等的人也拿到錯誤，再 throw e。
            //          Bug 版不清 inFlight：失敗的 Deferred 留在 Map 裡
            deferred.completeExceptionally(C04ApiException(C04ApiError.TIMEOUT))   // 先讓等的人不要卡住；寫好 ① 之後刪掉這兩行
            throw C04ApiException(C04ApiError.TIMEOUT)
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { api ->
        val cache = SingleFlight(api)
        val get: suspend (String) -> String = { cache.get(it) }
        get
    }
}

// MARK: - 修正版
private object L03Fixed {
    class SingleFlight(private val api: L03Api) {
        private val mutex = Mutex()
        private val values = HashMap<String, String>()
        private val inFlight = HashMap<String, CompletableDeferred<String>>()

        suspend fun get(key: String): String {
            var owner = false
            val deferred = mutex.withLock {
                values[key]?.let { return it }                                  // 已經有值
                inFlight[key] ?: CompletableDeferred<String>().also {           // 沒有人在載入：我來載入，先登記
                    inFlight[key] = it
                    owner = true
                }
            }
            if (!owner) return deferred.await()                                 // 有人正在載入：等同一個結果
            // TODO ②-1：自己載入：val loaded = api.fetch(key)；成功後 mutex.withLock { values[key] = loaded；inFlight.remove(key) }；
            //          deferred.complete(loaded)；return loaded
            // TODO ②-2：失敗（catch (e: C04ApiException)）：先 mutex.withLock { inFlight.remove(key) }（失敗不要留著），
            //          再 deferred.completeExceptionally(e)，最後 throw e
            deferred.completeExceptionally(C04ApiException(C04ApiError.TIMEOUT))   // 先讓等的人不要卡住；寫好 ② 之後刪掉這兩行
            throw C04ApiException(C04ApiError.TIMEOUT)
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { api ->
        val cache = SingleFlight(api)
        val get: suspend (String) -> String = { cache.get(it) }
        get
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "API 故障一次，之後永遠拿到同一個錯誤",
    question = "第 3 題：好幾個地方同時要同一份資料，怎麼只載入一次？",
    goal = "single-flight 要先登記進行中的工作；成功與失敗都要把它清掉，失敗不快取，下一次才會重新載入。先寫出失敗時沒清掉 inFlight 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/coding/coding04-async/L03_SingleFlight.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看第 ① 輪 API 各被呼叫幾次（single-flight 有沒有生效），以及第 ③ 輪 SOL 的結果。",
        "寫 TODO ②（修正版），選「練習」「修正版」執行，確認第 ③ 輪成功，API 累計 2 次。",
        "想一想：如果把失敗也存進 values，會發生什麼事？Token 過期時的刷新也是同樣的做法。",
    ),
    expectBug = "①「5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次」，②「2 個失敗，API 呼叫 1 次」，③「還是失敗，API 累計 1 次」（API 恢復了，卻根本沒再呼叫）。",
    expectFixed = "①② 和 Bug 版一樣；③「成功（SOL 的設定），API 累計 2 次」。",
    file = "coding04-async/L03_SingleFlight.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.size < 3 -> "演練沒有跑完：要用 scenario(log, make) 執行"
            "① BTC 同時要 3 次、ETH 同時要 2 次：5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次" !in lines ->
                "get 還沒寫對：先看 values，再看 inFlight，都沒有才由自己載入，並在呼叫 API 之前把 CompletableDeferred 存進 inFlight"
            "② SOL 同時要 2 次（API 故障）：2 個失敗，API 呼叫 1 次" !in lines ->
                "SOL 同時要 2 次，應該共用同一個 Deferred：API 只呼叫 1 次，兩個都拿到錯誤"
            "③ API 恢復後 SOL 再要一次：還是失敗，API 累計 1 次" !in lines ->
                "Bug 還沒重現：成功時才 inFlight.remove(key)，失敗時不要清，下一次呼叫才會等到同一個失敗的 Deferred"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.size < 3 -> "演練沒有跑完：要用 scenario(log, make) 執行"
            "① BTC 同時要 3 次、ETH 同時要 2 次：5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次" !in lines ->
                "get 還沒寫對：先看 values，再看 inFlight，都沒有才由自己載入，並在呼叫 API 之前把 CompletableDeferred 存進 inFlight"
            "② SOL 同時要 2 次（API 故障）：2 個失敗，API 呼叫 1 次" !in lines ->
                "SOL 同時要 2 次，應該共用同一個 Deferred：API 只呼叫 1 次，兩個都拿到錯誤"
            "③ API 恢復後 SOL 再要一次：成功（SOL 的設定），API 累計 2 次" !in lines ->
                "失敗時要在 catch 裡把 inFlight[key] 清掉再 throw，下一次呼叫才會重新載入"
            else -> null
        }
    },
)
