// L04：已經離開畫面，卻還用 null 更新畫面（吞掉 CancellationException）
// 對應複習站：Kotlin03Coroutines 第 4 題「取消是怎麼運作的？為什麼不能把 CancellationException 吞掉？」
//
// 情境：資產畫面載入餘額要 0.3 秒。情境 A 使用者一直停在畫面上；情境 B 使用者 0.1 秒就離開，coroutine 被取消。
// Bug 版：用 runCatching 包 suspend 呼叫。取消時丟出的 CancellationException 也被它接住，
//        變成「沒有資料」，後面的程式照樣執行，用 null 去更新一個已經不存在的畫面。
// 修正版：遇到 CancellationException 一定要往外丟，只處理真正的錯誤（例如 IOException）。
package tw.harry.labs.solution.kotlin03coroutines

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 模擬載入餘額：要 0.3 秒 */
private suspend fun fetchBalance(): String {
    delay(300)
    return "1.5 BTC"
}

/** 演練流程（兩個版本共用）：情境 A 等載入完成；情境 B 0.1 秒後離開畫面 */
private suspend fun scenario(log: LabLog, load: suspend (LabLog) -> Unit) {
    val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    log("情境 A：使用者一直停在畫面上")
    screenScope.launch { load(log) }.join()
    log("情境 B：使用者 0.1 秒就離開")
    val job = screenScope.launch { load(log) }
    delay(100)
    log("使用者離開畫面，取消載入")
    job.cancelAndJoin()
    log("載入流程結束")
}

// MARK: - Bug 版
private object L04Bug {
    suspend fun load(log: LabLog) {
        val balance = runCatching { fetchBalance() }.getOrNull()   // 取消也被接住，變成 null
        log("更新畫面：餘額 = $balance")
    }

    suspend fun run(log: LabLog) = scenario(log, ::load)
}

// MARK: - 修正版
private object L04Fixed {
    /** 只把真正的錯誤包成 Result；取消一律往外丟 */
    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result.failure(e)
        }

    suspend fun load(log: LabLog) {
        val balance = safeCall { fetchBalance() }.getOrNull()      // 取消時直接離開，不會走到下一行
        log("更新畫面：餘額 = $balance")
    }

    suspend fun run(log: LabLog) = scenario(log, ::load)
}

// MARK: - 演練設定
/** 「使用者離開畫面」之後有沒有更新畫面 */
private fun updatedAfterLeaving(lines: List<String>): String? {
    val leave = lines.indexOf("使用者離開畫面，取消載入")
    if (leave < 0) return null
    return lines.drop(leave + 1).firstOrNull { it.startsWith("更新畫面") }
}

val l04 = Lab(
    id = "L04",
    title = "離開畫面後，還用 null 更新畫面",
    question = "第 4 題：取消是怎麼運作的？為什麼不能把 CancellationException 吞掉？",
    goal = "使用者離開畫面、載入被取消後，不應該再更新畫面；沒離開時要正常顯示餘額。",
    steps = listOf(
        "選「Bug 版」執行，看情境 B「使用者離開畫面」之後還有沒有「更新畫面」。",
        "選「修正版」執行對照。",
    ),
    expectBug = "情境 A 正常顯示 1.5 BTC；情境 B 離開畫面之後還印出「更新畫面：餘額 = null」。",
    expectFixed = "情境 A 正常顯示 1.5 BTC；情境 B 離開畫面之後只剩「載入流程結束」。",
    file = "kotlin03-coroutines/L04_SwallowCancellation.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "更新畫面：餘額 = 1.5 BTC" !in lines -> "情境 A 沒有顯示餘額：load 要呼叫 fetchBalance() 並更新畫面"
            updatedAfterLeaving(lines) != "更新畫面：餘額 = null" -> "離開畫面後沒有用 null 更新畫面：Bug 還沒重現（用 runCatching 包住 fetchBalance）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "更新畫面：餘額 = 1.5 BTC" !in lines -> "情境 A 沒有顯示餘額：load 要呼叫 fetchBalance() 並更新畫面"
            updatedAfterLeaving(lines) != null -> "離開畫面後還在更新畫面：CancellationException 要往外丟"
            else -> null
        }
    },
)
