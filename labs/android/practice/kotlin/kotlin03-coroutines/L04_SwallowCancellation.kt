// L04：已經離開畫面，卻還用 null 更新畫面（吞掉 CancellationException）
// 對應複習站：Kotlin03Coroutines 第 4 題「取消是怎麼運作的？為什麼不能把 CancellationException 吞掉？」
//
// 情境：資產畫面載入餘額要 0.3 秒。情境 A 使用者一直停在畫面上；情境 B 使用者 0.1 秒就離開，coroutine 被取消。
//
// 你要寫：
//   ① Bug 版：用 runCatching 包住 fetchBalance()。跑起來會看到離開畫面後還用 null 更新畫面。
//   ② 修正版：寫一個 safeCall，遇到 CancellationException 往外丟、只把 IOException 當成失敗。
// 只改有 TODO 的地方。解答：solution/kotlin/kotlin03-coroutines/L04_SwallowCancellation.kt
package tw.harry.labs.practice.kotlin03coroutines

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

/** 模擬載入餘額（已寫好）：要 0.3 秒，回傳 "1.5 BTC" */
private suspend fun fetchBalance(): String {
    delay(300)
    return "1.5 BTC"
}

/** 演練流程（兩個版本共用，不用改）：情境 A 等載入完成；情境 B 0.1 秒後離開畫面 */
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
        // TODO ①：用 runCatching { fetchBalance() }.getOrNull() 取得 balance，
        //        接著 log("更新畫面：餘額 = $balance")
    }

    suspend fun run(log: LabLog) = scenario(log, ::load)
}

// MARK: - 修正版
private object L04Fixed {
    /** 只把真正的錯誤包成 Result；取消一律往外丟 */
    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> {
        // TODO ②-1：try 呼叫 block() 回傳 Result.success(…)；
        //          catch CancellationException → 直接 throw；catch IOException → Result.failure(e)
        return Result.failure(IOException("safeCall 還沒寫"))
    }

    suspend fun load(log: LabLog) {
        // TODO ②-2：用 safeCall { fetchBalance() }.getOrNull() 取得 balance，接著 log("更新畫面：餘額 = $balance")
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
        "寫 TODO ①，選「練習」「Bug 版」執行，看情境 B「使用者離開畫面」之後還有沒有「更新畫面」。",
        "寫 TODO ②，選「修正版」執行對照。",
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
