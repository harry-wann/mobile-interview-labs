// L03：try／catch 包住 launch，下單失敗時 App 還是當掉（例外怎麼往上傳）
// 對應複習站：Kotlin03Coroutines 第 3 題「例外怎麼往上傳？launch 和 async
// 的例外去哪？」
//
// 情境：按下「下單」後開一個 coroutine 呼叫下單 API，API 逾時丟出 IOException。
// Bug 版：在 launch 的「外面」包 try／catch。launch 只是把工作交出去就回來，
// 例外是之後在 coroutine 裡面丟的，
//        外面的 catch 接不到；例外一路往上傳到 scope，沒人處理，App 當掉。
// 修正版：在 coroutine 的「裡面」try／catch（或在 scope 上裝
// CoroutineExceptionHandler），
//        失敗時顯示錯誤訊息，App 不會當掉。
package tw.harry.labs.solution.kotlin03coroutines

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 模擬下單 API：100 ms 後逾時 */
private suspend fun placeOrder(): String {
    delay(100)
    throw IOException("下單 API 逾時")
}

// MARK: - Bug 版
private object L03Bug {
    suspend fun run(log: LabLog) {
        // 畫面自己的 scope
        val screenScope = CoroutineScope(Job() + Dispatchers.Main)
        try {
            screenScope.launch {
                val id = placeOrder()
                log("下單成功：$id")
            }.join()
        } catch (e: Exception) {
            // 接不到
            log("接到例外：${e.message}")
        }
        log("下單流程結束")
    }
}

// MARK: - 修正版
private object L03Fixed {
    suspend fun run(log: LabLog) {
        val screenScope = CoroutineScope(Job() + Dispatchers.Main)
        screenScope.launch {
            // 在 coroutine 裡面接
            try {
                val id = placeOrder()
                log("下單成功：$id")
            } catch (e: IOException) {
                log("顯示錯誤：${e.message}，請稍後再試")
            }
        }.join()
        log("下單流程結束")
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "try／catch 包住 launch，App 還是當掉",
    question = "第 3 題：例外怎麼往上傳？launch 和 async 的例外去哪？",
    goal = "下單 API 逾時時，畫面應該顯示錯誤訊息，而不是整個 App 當掉。",
    steps = listOf(
        "打開 Android Studio 下方的 Logcat。",
        "選「Bug 版」執行：App 會當掉，Logcat 出現 FATAL EXCEPTION 和 IOException: 下單 API 逾時。",
        "重新打開 App，選「修正版」執行，比較 log。",
        "想一想：如果把 launch 換成 async，再在 try 裡 await()，會怎樣？",
    ),
    expectBug = "App 當掉。log 裡沒有「接到例外」，因為外面的 catch 根本接不到。",
    expectFixed = "印出「顯示錯誤：下單 API 逾時，請稍後再試」，接著「下單流程結束」，App 沒有當掉。",
    file = "kotlin03-coroutines/L03_UncaughtException.kt",
    bugCrashes = true,
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        // 寫對的話 App 會當掉，不會走到這裡
        if (lines.any { it.startsWith("接到例外") }) {
            "外面的 catch 接到了：這不是 Bug 版要重現的情況"
        } else {
            "App 沒有當掉：要在 launch 的外面包 try／catch，裡面呼叫 placeOrder()"
        }
    },
    checkFixed = { lines ->
        when {
            "顯示錯誤：下單 API 逾時，請稍後再試" !in lines -> "沒有顯示錯誤訊息：try／catch 要寫在 launch 的大括號裡面"
            "下單流程結束" !in lines -> "流程沒有走完"
            else -> null
        }
    },
)
