// L02：餘額不足也重試 6 次，伺服器忙碌時又馬上重試（重試沒有分錯誤種類，
// 也沒有退避）
// 對應複習站：Coding04Async 第 2 題「API 失敗時要怎麼重試？」
//
// 情境：呼叫 API 的 retry 函式，最多試 6 次，第一次失敗後等 100 毫秒，
// 之後每次加倍，最多等 1000 毫秒（sleep 由外面傳進來，
//       演練的假 sleep 只記錄「被要求等多久」，不會真的等）。三種情境：
//       A 前 3 次逾時、第 4 次成功；B 餘額不足；C 伺服器一直回 503。
// Bug 版：用 catch (e: Exception) 接住所有錯誤再試，而且失敗後馬上重試，
// 一毫秒也沒等。餘額不足這種「重試結果都一樣」的錯誤也被送了 6 次；
//        伺服器忙碌時，所有手機一起馬上重試，只會讓伺服器更忙。
// 修正版：只重試暫時性的錯誤（逾時、503、429）；其他錯誤第一次失敗就丟出去。
// 每次失敗等待時間加倍（指數退避），最多等 1000 毫秒。
//        只接 C04ApiException，不接 Exception。（重試加上隨機的 jitter，見
// Platform05 L06；
//        catch (e: Exception) 會把 CancellationException 也吞掉，見 Kotlin03
// L04。）
package tw.harry.labs.solution.coding04async

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 重試的函式簽名（兩個版本共用）：最多試 maxAttempts 次；sleep 是等待函式；
 * operation 是要做的事
 */
private typealias L02Retry = suspend (
    maxAttempts: Int,
    baseDelayMs: Int,
    maxDelayMs: Int,
    sleep: suspend (Int) -> Unit,
    operation: suspend () -> String,
) -> String

/** 演練流程（兩個版本共用）：三種情境各跑一次，記錄呼叫幾次、等了多久、結果 */
private suspend fun scenario(log: LabLog, retry: L02Retry) {
    // 每個情境是「依序發生的失敗」，用完之後就成功
    val cases = listOf(
        "A（前 3 次逾時）" to
            listOf(
                C04ApiError.TIMEOUT,
                C04ApiError.TIMEOUT,
                C04ApiError.TIMEOUT,
            ),
        "B（餘額不足）" to List(10) { C04ApiError.INSUFFICIENT_BALANCE },
        "C（伺服器一直 503）" to List(10) { C04ApiError.SERVER_BUSY },
    )
    for ((title, failures) in cases) {
        val calls = C04Calls()
        val sleepLog = C04SleepLog()
        val outcome = try {
            retry(6, 100, 1000, { sleepLog.sleep(it) }) {
                val n = calls.next()
                if (n <= failures.size) throw C04ApiException(failures[n - 1])
                "ok"
            }
            "成功"
        } catch (e: C04ApiException) {
            "失敗（${e.error.text}）"
        }
        log("情境 $title：呼叫 ${calls.count} 次，等待 ${sleepLog.waits} 毫秒，$outcome")
    }
}

// MARK: - Bug 版
private object L02Bug {
    suspend fun retry(
        maxAttempts: Int,
        baseDelayMs: Int,
        maxDelayMs: Int,
        sleep: suspend (Int) -> Unit,
        operation: suspend () -> String,
    ): String {
        var lastError: Exception = C04ApiException(C04ApiError.TIMEOUT)
        repeat(maxAttempts) {
            try {
                return operation()
            } catch (e: Exception) {
                // 什麼錯都再試一次，而且馬上試，沒有等
                lastError = e
            }
        }
        throw lastError
    }

    suspend fun run(log: LabLog) = scenario(log, ::retry)
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun retry(
        maxAttempts: Int,
        baseDelayMs: Int,
        maxDelayMs: Int,
        sleep: suspend (Int) -> Unit,
        operation: suspend () -> String,
    ): String {
        var delay = baseDelayMs
        for (attempt in 1..maxAttempts) {
            try {
                return operation()
            } catch (e: C04ApiException) {
                // 不是暫時性的錯誤，或已經是最後一次：不要再試，直接丟出去
                if (!e.error.isTransient || attempt == maxAttempts) throw e
                sleep(delay) // 等一下再試
                // 下次等加倍，最多等到上限
                delay = minOf(delay * 2, maxDelayMs)
            }
        }
        throw C04ApiException(C04ApiError.TIMEOUT) // 走不到這裡
    }

    suspend fun run(log: LabLog) = scenario(log, ::retry)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "餘額不足也重試 6 次，而且馬上重試",
    question = "第 2 題：API 失敗時要怎麼重試？",
    goal = "只重試暫時性的錯誤；重試之間等待時間加倍（指數退避）並且有上限；重試次數也要有上限。",
    steps = listOf(
        "選「Bug 版」執行，看情境 B（餘額不足）呼叫了幾次，以及三個情境「等待」的欄位。",
        "選「修正版」執行，確認情境 A 等待 [100, 200, 400]，情境 B 只呼叫 1 次，情境 C 的等待加倍到 1000 就不再變大。",
        "想一想：下單逾時可以這樣直接重試嗎？（要帶同一個 client order id，見 Platform05 L05）",
    ),
    expectBug = "A 呼叫 4 次、B 呼叫 6 次、C 呼叫 6 次，三個情境的等待都是 []（一毫秒也沒等）。",
    expectFixed = "A 等待 [100, 200, 400]；B 呼叫 1 次就失敗；C 呼叫 6 次，等待 [100, 200, 400, 800, 1000]（封頂）。",
    file = "coding04-async/L02_Retry.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.size < 3 -> {
                "演練沒有跑完：要用 scenario(log, retry) 執行"
            }

            "情境 A（前 3 次逾時）：呼叫 4 次，等待 [] 毫秒，成功" !in lines -> {
                "retry 還沒寫：失敗就再呼叫 operation，直到成功或試滿 maxAttempts 次"
            }

            "情境 B（餘額不足）：呼叫 6 次，等待 [] 毫秒，失敗（餘額不足）" !in lines -> {
                "Bug 還沒重現：不分錯誤種類全部重試，餘額不足也要試滿 6 次，而且不呼叫 sleep"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.size < 3 -> {
                "演練沒有跑完：要用 scenario(log, retry) 執行"
            }

            "情境 A（前 3 次逾時）：呼叫 4 次，等待 [100, 200, 400] 毫秒，成功" !in lines -> {
                "暫時性的錯誤要等一下再試：sleep(delay)，之後 delay 加倍"
            }

            "情境 B（餘額不足）：呼叫 1 次，等待 [] 毫秒，失敗（餘額不足）" !in lines -> {
                "餘額不足不是暫時性的錯誤（isTransient 是 false），第一次失敗就要丟出去，不要重試"
            }

            "情境 C（伺服器一直 503）：呼叫 6 次，等待 [100, 200, 400, 800, 1000] 毫秒，失敗（伺服器忙碌 503）" !in
                lines -> {
                "等待時間每次加倍但最多 maxDelayMs（1000）；試滿 6 次後把最後一個錯誤丟出去，最後一次失敗不用再等"
            }

            else -> {
                null
            }
        }
    },
)
