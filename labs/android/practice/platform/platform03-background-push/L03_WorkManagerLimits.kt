// L03：想每 1 分鐘檢查一次價格，WorkManager 實際只會 15 分鐘一次（週期性工作的下限）
// 對應複習站：Platform03BackgroundPush 第 3 題「Android 有哪些背景執行的機制？Doze 和 App Standby 是什麼？」
//
// 情境：價格提醒想用 WorkManager 的週期性工作「每 1 分鐘檢查一次價格」。WorkManager 不會報錯，
//        但週期性工作的最短間隔是 15 分鐘，設得比較短會被默默提高成 15 分鐘（而且還是不準時，Doze 時更會被延後）。
//        演練不真的執行工作，只檢查你組出來的 WorkRequest：用 request.workSpec 看實際排的間隔、是不是加急（expedited）。
//        （Doze、App Standby 要用 adb 觀察，見複習站的說明。）
//
// 你要寫：
//   ① Bug 版（L03Bug）：用 PeriodicWorkRequestBuilder 要求每 1 分鐘檢查價格；收到推播時排一個一般的一次性工作。
//   ② 修正版（L03Fixed）：週期性工作只做可延遲的同步（間隔 15 分鐘）；收到推播時排加急（expedited）的一次性工作，並帶著 alertId。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform03-background-push/L03_WorkManagerLimits.kt
package tw.harry.labs.practice.platform03backgroundpush

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 檢查價格的工作（只用來組 WorkRequest，演練不真的執行，兩個版本共用） */
private class L03PriceCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/** 處理一個價格提醒的工作（兩個版本共用） */
private class L03AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/** 演練流程（兩個版本共用）：要求的間隔交給 [periodic] 組成 WorkRequest，再看實際排的間隔；收到推播時用 [onPush] 組工作 */
private fun scenario(
    log: LabLog,
    requestedMinutes: Long,
    periodic: (Long) -> PeriodicWorkRequest?,
    onPush: (alertId: String) -> OneTimeWorkRequest?,
) {
    val request = periodic(requestedMinutes)
    if (request == null) {
        log("要求每 $requestedMinutes 分鐘執行一次：還沒有建立週期性工作")
    } else {
        val actual = request.workSpec.intervalDuration / 60_000
        log("要求每 $requestedMinutes 分鐘執行一次，WorkManager 實際排的間隔是 $actual 分鐘")
    }
    val work = onPush("a1b2c3")
    if (work == null) {
        log("收到推播時排的工作：還沒有建立")
    } else {
        val expedited = work.workSpec.expedited
        val alertId = work.workSpec.input.getString("alertId")
        log("收到推播時排的工作：加急（expedited）${if (expedited) "是" else "否"}，帶著 alertId ${if (alertId == "a1b2c3") "是" else "否"}")
    }
}

// MARK: - Bug 版
private object L03Bug {
    fun run(log: LabLog) = scenario(
        log,
        requestedMinutes = 1,
        periodic = { minutes ->
            // TODO ①-1：用 PeriodicWorkRequestBuilder<L03PriceCheckWorker>(minutes, TimeUnit.MINUTES).build() 組出週期性工作（要每 1 分鐘檢查價格）
            null
        },
        onPush = { _ ->
            // TODO ①-2：用 OneTimeWorkRequestBuilder<L03AlertWorker>().build() 組出一般的一次性工作（沒有加急）
            null
        },
    )
}

// MARK: - 修正版
private object L03Fixed {
    fun run(log: LabLog) = scenario(
        log,
        requestedMinutes = 15,
        periodic = { minutes ->
            // TODO ②-1：一樣用 PeriodicWorkRequestBuilder 組出週期性工作（minutes 是 15），再用 setConstraints 要求網路連線（NetworkType.CONNECTED）
            null
        },
        onPush = { alertId ->
            // TODO ②-2：用 OneTimeWorkRequestBuilder<L03AlertWorker>() 組出一次性工作：
            //          setInputData 帶 alertId（Data.Builder().putString("alertId", alertId)）、setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            null
        },
    )
}

// MARK: - 演練設定
private fun intervals(lines: List<String>): Pair<Long, Long>? {
    val line = lines.firstOrNull { it.startsWith("要求每 ") && it.contains("實際排的間隔是 ") } ?: return null
    val requested = line.removePrefix("要求每 ").substringBefore(" 分鐘").toLongOrNull() ?: return null
    val actual = line.substringAfter("實際排的間隔是 ").substringBefore(" 分鐘").toLongOrNull() ?: return null
    return requested to actual
}

val l03 = Lab(
    id = "L03",
    title = "要求每 1 分鐘檢查，實際變成 15 分鐘",
    question = "第 3 題：Android 有哪些背景執行的機制？Doze 和 App Standby 是什麼？",
    goal = "WorkManager 的週期性工作最短 15 分鐘，不準時，不能拿來做即時價格提醒。週期性工作只做可延遲的同步，收到推播時要盡快做完的事用加急（expedited）工作。先寫出「要求 1 分鐘卻變 15 分鐘」的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform03-background-push/L03_WorkManagerLimits.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「要求每 1 分鐘，實際排的間隔是幾分鐘」。",
        "寫 TODO ②（修正版），選「修正版」執行：週期性工作用 15 分鐘；收到推播時排的工作是加急、帶著 alertId。",
        "用 adb 觀察 Doze：adb shell dumpsys battery unplug，再 adb shell dumpsys deviceidle force-idle，之後 adb shell dumpsys deviceidle unforce 離開；App Standby：adb shell am set-standby-bucket tw.harry.labs rare（見複習站第 3 題）。",
    ),
    expectBug = "要求每 1 分鐘執行一次，WorkManager 實際排的間隔是 15 分鐘；收到推播時排的工作不是加急。",
    expectFixed = "要求每 15 分鐘執行一次，實際排的間隔也是 15 分鐘；收到推播時排的工作是加急，而且帶著 alertId。",
    file = "platform03-background-push/L03_WorkManagerLimits.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val pair = intervals(lines)
        when {
            lines.any { it.contains("還沒有建立週期性工作") } -> "還沒有建立週期性工作：用 PeriodicWorkRequestBuilder<L03PriceCheckWorker>(minutes, TimeUnit.MINUTES).build()"
            pair == null -> "演練沒有跑完"
            pair.first >= pair.second -> "Bug 還沒重現：要求的間隔要比 15 分鐘短（用傳進來的 minutes，也就是 1 分鐘）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val pair = intervals(lines)
        when {
            lines.any { it.contains("還沒有建立週期性工作") } -> "還沒有建立週期性工作：用 PeriodicWorkRequestBuilder<L03PriceCheckWorker>(minutes, TimeUnit.MINUTES).build()"
            pair == null -> "演練沒有跑完"
            pair.first != pair.second -> "要求 ${pair.first} 分鐘，實際是 ${pair.second} 分鐘：週期性工作的間隔不能低於 15 分鐘"
            lines.any { it.contains("收到推播時排的工作：還沒有建立") } -> "收到推播時要排一個一次性工作：OneTimeWorkRequestBuilder<L03AlertWorker>()"
            !lines.contains("收到推播時排的工作：加急（expedited）是，帶著 alertId 是") ->
                "收到推播時排的工作要加急並帶著 alertId：.setInputData(Data.Builder().putString(\"alertId\", alertId).build())，再 .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)"
            else -> null
        }
    },
)
