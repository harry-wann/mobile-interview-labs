// L03：在主執行緒讀檔，開發時完全沒有人發現（StrictMode）
// 對應複習站：Platform02Threading 第 3 題「ANR 是什麼？iOS 的卡頓怎麼偵測？」
//
// 情境：有一個函式要讀取設定檔。它被直接寫在主執行緒呼叫：檔案小，開發時感覺不到卡。但檔案變大、手機變慢（磁碟忙碌）時，
//        主執行緒就會被卡住，嚴重的話超過 5 秒沒處理輸入事件就是 ANR。StrictMode 是開發時抓這種違規的工具：
//        主執行緒一碰磁碟或網路，就通知你（正式的 App 通常是 penaltyLog，讓 Logcat 留下警告；這裡用 penaltyListener 好讓演練把它記下來）。
//        要在手機上執行（StrictMode、Android 9 以上；讀檔的是系統檔 /system/etc/hosts）。
// Bug 版：直接在主執行緒讀檔。StrictMode 抓到一次磁碟讀取違規。
// 修正版：用 withContext(Dispatchers.IO) 把讀檔放到背景。StrictMode 只看主執行緒，沒有違規。
package tw.harry.labs.solution.platform02threading

import android.os.Build
import android.os.StrictMode
import android.os.strictmode.DiskReadViolation
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 讀取設定檔（兩個版本共用） */
private fun readConfig(): String =
    runCatching { File("/system/etc/hosts").readText() }.getOrDefault("")

/** 演練流程（兩個版本共用）：開著 StrictMode 的磁碟讀取偵測，執行 [load]，數它抓到幾次 */
private suspend fun scenario(log: LabLog, load: suspend () -> String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        log("StrictMode 的 penaltyListener 需要 Android 9（API 28）以上")
        return
    }
    val violations = AtomicInteger(0)
    val previous = StrictMode.getThreadPolicy()
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .penaltyListener(Executor { it.run() }) { violation ->      // 違規時在這裡通知（也可以用 penaltyLog 寫進 Logcat）
                if (violation is DiskReadViolation) violations.incrementAndGet()
            }
            .build(),
    )
    try {
        val config = load()
        log("讀到設定檔，共 ${config.length} 個字元")
        // 主執行緒上的違規不會馬上通知：系統等這個訊息處理完，下一輪 looper 才呼叫 listener。
        // 所以先讓出主執行緒等一下，policy 也要等通知完才還原。
        delay(500)
    } finally {
        StrictMode.setThreadPolicy(previous)
    }
    log("StrictMode 抓到 ${violations.get()} 次主執行緒磁碟讀取")
}

// MARK: - Bug 版
private object L03Bug {
    suspend fun run(log: LabLog) = scenario(log) {
        readConfig()                                     // 在主執行緒直接讀檔
    }
}

// MARK: - 修正版
private object L03Fixed {
    suspend fun run(log: LabLog) = scenario(log) {
        withContext(Dispatchers.IO) { readConfig() }     // 磁碟讀取放到 IO 執行緒
    }
}

// MARK: - 演練設定
private fun configChars(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("讀到設定檔，共 ") }?.removePrefix("讀到設定檔，共 ")?.substringBefore(" ")?.toIntOrNull()

private fun violations(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("StrictMode 抓到 ") }?.removePrefix("StrictMode 抓到 ")?.substringBefore(" ")?.toIntOrNull()

val l03 = Lab(
    id = "L03",
    title = "主執行緒讀檔，開發時沒人發現",
    question = "第 3 題：ANR 是什麼？iOS 的卡頓怎麼偵測？",
    goal = "開 StrictMode 抓出主執行緒的磁碟讀取，再把讀檔放到背景。",
    steps = listOf(
        "在手機（模擬器）上選「Bug 版」按執行，看「StrictMode 抓到幾次主執行緒磁碟讀取」。",
        "選「修正版」執行：讀檔放到 Dispatchers.IO，StrictMode 抓到 0 次。",
        "想一想：為什麼 StrictMode 只在 debug 版開？（它是開發時的警報，不是正式版的保護；正式版看 Android vitals 的 ANR 統計）",
    ),
    expectBug = "StrictMode 抓到 1 次以上的主執行緒磁碟讀取（模擬器 API 29 實測 4 次：一次讀檔會碰到好幾次磁碟）。",
    expectFixed = "StrictMode 抓到 0 次。",
    file = "platform02-threading/L03_StrictMode.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = violations(lines)
        when {
            n == null -> "演練沒有跑完"
            (configChars(lines) ?: 0) == 0 -> "沒有讀到設定檔：要在 scenario 的 load 裡呼叫 readConfig()"
            n == 0 -> "Bug 還沒重現：要在主執行緒直接呼叫 readConfig()"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = violations(lines)
        when {
            n == null -> "演練沒有跑完"
            (configChars(lines) ?: 0) == 0 -> "沒有讀到設定檔：要在 scenario 的 load 裡呼叫 readConfig()"
            n != 0 -> "StrictMode 還是抓到 $n 次：用 withContext(Dispatchers.IO) 包住 readConfig()"
            else -> null
        }
    },
)
