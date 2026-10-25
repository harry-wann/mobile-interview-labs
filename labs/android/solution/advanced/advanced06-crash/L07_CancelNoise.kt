// L07：Crashlytics 被「Job was cancelled」洗版，真正的錯誤反而看不到（取消不是錯誤）
// 對應複習站：Advanced06Crash 第 5 題「怎麼把 crash-free users 從約 90% 提升到 99%，而且長期維持？」（步驟六：降低雜訊）
//
// 情境：使用者快速在畫面之間切換。每個畫面離開時，scope 會取消還在載入的 coroutine，
//       被取消的 coroutine 會丟出 CancellationException。另外有 1 個畫面載入時，真的遇到資料解碼失敗。
//       團隊在每個 catch 裡把例外用 non-fatal 回報（Crashlytics 的 recordException）。
// Bug 版：catch (e: Exception) 什麼都回報。5 筆「已取消」跟 1 筆真正的錯誤混在一起，問題列表被取消洗版；
//        而且沒有把 CancellationException 再丟出去，取消的訊號也被吃掉了。
// 修正版：取消是正常的流程控制：先 catch CancellationException，不回報、重新丟出去讓取消繼續往上傳；其餘才回報。
package tw.harry.labs.solution.advanced06crash

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 Crashlytics：記下被回報的 non-fatal 例外（兩個版本共用） */
private class L07Reporter {
    val reports = CopyOnWriteArrayList<String>()
    fun recordException(e: Throwable) {
        reports += "${e.javaClass.simpleName}: ${e.message}"
    }
}

/** 載入一個畫面的資料：5 秒才會有結果，被取消時 delay 會丟出 CancellationException（兩個版本共用） */
private suspend fun loadScreen(kind: Int) {
    if (kind == 99) throw IOException("資料解碼失敗")          // 這一個畫面真的遇到壞資料
    delay(5_000)
}

/** 演練流程（兩個版本共用）：開 6 個畫面的載入工作，使用者很快離開（取消其中 5 個） */
private suspend fun scenario(log: LabLog, load: suspend (Int, L07Reporter) -> Unit) {
    val reporter = L07Reporter()
    val scope = CoroutineScope(Job() + Dispatchers.Default)
    val jobs = listOf(1, 2, 3, 4, 5, 99).map { kind -> scope.launch { load(kind, reporter) } }
    delay(100)
    scope.coroutineContext[Job]!!.children.filter { it.isActive }.forEach { it.cancel() }   // 使用者離開畫面：取消還在載入的工作
    jobs.joinAll()
    log("Crashlytics 收到 ${reporter.reports.size} 筆 non-fatal")
    for (report in reporter.reports) log("　• $report")
}

// MARK: - Bug 版
private object L07Bug {
    suspend fun load(kind: Int, reporter: L07Reporter) {
        try {
            loadScreen(kind)
        } catch (e: Exception) {
            reporter.recordException(e)                         // 什麼例外都回報，包括取消
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::load)
}

// MARK: - 修正版
private object L07Fixed {
    suspend fun load(kind: Int, reporter: L07Reporter) {
        try {
            loadScreen(kind)
        } catch (e: CancellationException) {
            throw e                                             // 取消是正常的流程：不回報，並且繼續往上傳
        } catch (e: Exception) {
            reporter.recordException(e)                         // 真正的錯誤才回報
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::load)
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "Crashlytics 被「Job was cancelled」洗版",
    question = "第 5 題：怎麼把 crash-free users 從約 90% 提升到 99%，而且長期維持？（降低雜訊）",
    goal = "只回報真正需要處理的錯誤：使用者離開畫面造成的取消，不該出現在 non-fatal 列表裡。",
    steps = listOf(
        "選「Bug 版」執行，看 Crashlytics 收到幾筆，以及是哪些例外。",
        "想一想：coroutine 被取消時 delay 丟出什麼例外？它是 Exception 的子類別嗎？catch (e: Exception) 會接到嗎？",
        "選「修正版」執行：先 catch CancellationException 並重新丟出去，只剩下 1 筆「資料解碼失敗」。",
        "進階：iOS 對應的是 CancellationError 與 URLError(.cancelled)。",
    ),
    expectBug = "「Crashlytics 收到 6 筆 non-fatal」，其中 5 筆是 JobCancellationException（Job was cancelled 這類訊息）。",
    expectFixed = "「Crashlytics 收到 1 筆 non-fatal」，只有「IOException: 資料解碼失敗」。",
    file = "advanced06-crash/L07_CancelNoise.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("Crashlytics 收到") } -> "演練沒有跑完：要用 scenario 執行"
            "Crashlytics 收到 6 筆 non-fatal" !in lines -> "Bug 還沒重現：catch (e: Exception) 把例外都回報（reporter.recordException），應該有 6 筆"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("Crashlytics 收到") } -> "演練沒有跑完：要用 scenario 執行"
            "Crashlytics 收到 1 筆 non-fatal" !in lines -> "應該只回報 1 筆：要先 catch CancellationException，不回報並重新丟出去"
            lines.none { it.startsWith("　• IOException") } -> "真正的錯誤（IOException 資料解碼失敗）不能被濾掉"
            else -> null
        }
    },
)
