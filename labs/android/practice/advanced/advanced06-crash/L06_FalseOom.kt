// L06：記憶體不足的次數看起來是 crash 的好幾倍（背景被 lmkd 回收不算 OOM）
// 對應複習站：Advanced06Crash 第 4 題「ANR、watchdog、OOM
// 是什麼？為什麼它們常常沒有一般的 crash 報告？」（ApplicationExitInfo）
//
// 情境：Android 11 起，ActivityManager.getHistoricalProcessExitReasons 會回傳
// App 行程最近幾次結束的原因（ApplicationExitInfo），
//       每一筆有 reason（原因）與 importance（結束當下行程的重要性：前景 100、
// 可見 200、快取 400…）。
//       演練有 12 筆紀錄，其中真正在前景被記憶體不足終止的只有 2 筆。
//       lmkd（low memory killer daemon）會一直回收「快取中的背景行程」，這是
// Android 的正常行為，使用者根本不會發現。
//
// 你要寫：
//   ① Bug 版：只看原因：凡是 REASON_LOW_MEMORY 都算 OOM。背景快取行程被 lmkd
// 回收的也被算進來。
//   ② 修正版：REASON_LOW_MEMORY 還要看 importance，前景或可見的時候被終止才算。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced06-crash/L06_FalseOom.kt
package tw.harry.labs.practice.advanced06crash

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 一筆結束紀錄（對應 ApplicationExitInfo 的 reason 與 importance，
 * 兩個版本共用）
 */
private class L06ExitRecord(
    val note: String,
    val reason: Int,
    val importance: Int,
)

private val history = listOf(
    L06ExitRecord(
        "背景快取行程被 lmkd 回收",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "背景快取行程被 lmkd 回收",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "背景快取行程被 lmkd 回收",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "背景快取行程被 lmkd 回收",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "從最近使用畫面滑掉",
        ApplicationExitInfo.REASON_USER_REQUESTED,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "自己呼叫 finish 結束",
        ApplicationExitInfo.REASON_EXIT_SELF,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ),
    L06ExitRecord(
        "版本更新",
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        RunningAppProcessInfo.IMPORTANCE_CACHED,
    ),
    L06ExitRecord(
        "crash",
        ApplicationExitInfo.REASON_CRASH,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ),
    L06ExitRecord(
        "native crash",
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ),
    L06ExitRecord(
        "ANR",
        ApplicationExitInfo.REASON_ANR,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ),
    L06ExitRecord(
        "前景被記憶體不足終止",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
    ),
    L06ExitRecord(
        "前景被記憶體不足終止",
        ApplicationExitInfo.REASON_LOW_MEMORY,
        RunningAppProcessInfo.IMPORTANCE_VISIBLE,
    ),
)

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, isOom: (L06ExitRecord) -> Boolean) {
    val flagged = history.filter(isOom)
    log("共 ${history.size} 筆結束紀錄，判定為前景記憶體不足被終止：${flagged.size} 筆")
    val wrong = flagged.filter { it.note != "前景被記憶體不足終止" }.map { it.note }
    if (wrong.isNotEmpty()) log("其中誤判：${wrong.joinToString("、")}")
}

// MARK: - Bug 版
private object L06Bug {
    fun isOom(record: L06ExitRecord): Boolean {
        // TODO ①：只要 record.reason 是 ApplicationExitInfo.REASON_LOW_MEMORY
        // 就算 OOM（不看 importance）
        return false
    }

    suspend fun run(log: LabLog) = scenario(log, ::isOom)
}

// MARK: - 修正版
private object L06Fixed {
    fun isOom(record: L06ExitRecord): Boolean {
        // TODO ②：REASON_LOW_MEMORY 還要看 record.importance：
        //         行程在前景或可見的時候被終止（importance ≤
        // RunningAppProcessInfo.IMPORTANCE_VISIBLE）才算
        return false
    }

    suspend fun run(log: LabLog) = scenario(log, ::isOom)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "記憶體不足的次數比 crash 還多好幾倍",
    question = "第 4 題：ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？",
    goal = "統計 OOM 時只算使用者遇得到的：背景快取行程被 lmkd 回收是系統的正常行為，不能算進來。 先寫出會誤判的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced06-crash/L06_FalseOom.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看判定了幾筆 OOM，以及「其中誤判」列了哪些情況。",
        "想一想：REASON_LOW_MEMORY 的 importance 是 IMPORTANCE_CACHED（400）代表什麼？使用者看得到 App 被回收嗎？",
        "寫 TODO ②，選「修正版」執行，確認只剩下 2 筆真正的前景 OOM。",
        "進階：Play Console 的 Android vitals 也把「使用者在使用 App 時被終止」和「背景被回收」分開統計。",
    ),
    expectBug = "「共 12 筆結束紀錄，判定為前景記憶體不足被終止：6 筆」，誤判列出 4 筆背景快取行程被回收。",
    expectFixed = "「共 12 筆結束紀錄，判定為前景記憶體不足被終止：2 筆」，沒有誤判。",
    file = "advanced06-crash/L06_FalseOom.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("共 12 筆") } -> "演練沒有跑完：要用 scenario 執行"
            "共 12 筆結束紀錄，判定為前景記憶體不足被終止：6 筆" !in lines -> "Bug 還沒重現：只要 reason 是 REASON_LOW_MEMORY 就算 OOM，會判定 6 筆"
            else -> null
        }
    },
    checkFixed = { lines ->
        val wrong = lines.firstOrNull { it.startsWith("其中誤判") }
        when {
            lines.none { it.startsWith("共 12 筆") } -> "演練沒有跑完：要用 scenario 執行"
            wrong != null -> "還有誤判：$wrong"
            "共 12 筆結束紀錄，判定為前景記憶體不足被終止：2 筆" !in lines -> "應該只有 2 筆前景 OOM：REASON_LOW_MEMORY 還要確認 importance 是前景或可見（≤ IMPORTANCE_VISIBLE）"
            else -> null
        }
    },
)
