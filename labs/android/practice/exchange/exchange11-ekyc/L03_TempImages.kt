// L03：使用者中途離開開戶流程，證件和臉部影像還留在 App 的沙盒裡（個資只在上傳成功時才刪除）
// 對應複習站：Exchange11eKYC 第 4 題「證件和臉部影像是高敏感的個人資料，App 要怎麼處理？」
//
// 情境：開戶時要拍證件的正面、背面和臉部自拍，先存成暫存檔（App 私有的內部儲存）再上傳。個資的原則是「不要長期保留原始影像」：
//        上傳成功就立刻刪除；使用者回上一頁、切到背景、被來電中斷、離開整個流程，也要把暫存檔清掉（重試用「可續傳的上傳」滿足，不是留著原始影像）。
//        演練用一個記憶體裡的假沙盒（不碰真的檔案，也不會有真的影像），三個情境，每個情境各自一個全新的沙盒，都已經拍好三張（front、back、face）：
//        ①上傳成功 ②使用者按返回離開流程 ③上傳失敗（沒有網路），保留影像讓使用者重試，之後使用者離開流程。
//        每個情境結束時，沙盒裡都不應該留下任何影像。
//
// 你要寫：
//   ① Bug 版（L03Bug.onUploadSucceeded、onLeaveFlow）：上傳成功時呼叫 sandbox.clear()；離開流程時什麼都不做。
//   ② 修正版（L03Fixed.onUploadSucceeded、onLeaveFlow）：兩個時機都呼叫 sandbox.clear()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange11-ekyc/L03_TempImages.kt
package tw.harry.labs.practice.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假沙盒：只記錄有哪些暫存檔（檔名），不碰真的檔案系統 */
private class L03Sandbox {
    val files = mutableListOf<String>()
    fun write(name: String) {
        files += name
    }

    fun clear() {
        files.clear()
    }
}

/** 演練流程（兩個版本共用）：三個情境，每個情境各自一個全新的沙盒 */
private fun scenario(
    log: LabLog,
    onUploadSucceeded: (L03Sandbox) -> Unit,
    onLeaveFlow: (L03Sandbox) -> Unit,
) {
    var leftover = 0
    fun finish(name: String, sandbox: L03Sandbox) {
        if (sandbox.files.isEmpty()) {
            log("$name：結束時沙盒裡沒有證件影像 ✓")
        } else {
            leftover++
            log("$name：結束時沙盒裡還留著 ${sandbox.files.size} 個影像檔（${sandbox.files.joinToString("、")}）✗")
        }
    }

    fun capture(): L03Sandbox {
        val sandbox = L03Sandbox()
        for (name in listOf("front.jpg", "back.jpg", "face.jpg")) sandbox.write(name)
        return sandbox
    }

    val s1 = capture()
    onUploadSucceeded(s1)                                    // 上傳成功
    finish("情境 1（上傳成功）", s1)

    val s2 = capture()
    onLeaveFlow(s2)                                          // 使用者按返回離開流程
    finish("情境 2（按返回離開）", s2)

    val s3 = capture()                                       // 上傳失敗：不呼叫任何清理，影像要留著讓使用者重試
    log("情境 3：上傳失敗後、離開流程前，沙盒裡有 ${s3.files.size} 個影像檔（保留給使用者重試）")
    onLeaveFlow(s3)                                          // 之後使用者離開流程
    finish("情境 3（上傳失敗後離開）", s3)
    log("3 個情境，結束時還留著影像的有 $leftover 個")
}

// MARK: - Bug 版
private object L03Bug {
    fun onUploadSucceeded(sandbox: L03Sandbox) {
        // TODO ①-1：呼叫 sandbox.clear()
    }

    fun onLeaveFlow(sandbox: L03Sandbox) {
        // TODO ①-2：什麼都不做（這就是 Bug：離開流程時沒有清暫存）
    }

    suspend fun run(log: LabLog) = scenario(log, ::onUploadSucceeded, ::onLeaveFlow)
}

// MARK: - 修正版
private object L03Fixed {
    fun onUploadSucceeded(sandbox: L03Sandbox) {
        // TODO ②-1：呼叫 sandbox.clear()
    }

    fun onLeaveFlow(sandbox: L03Sandbox) {
        // TODO ②-2：呼叫 sandbox.clear()
    }

    suspend fun run(log: LabLog) = scenario(log, ::onUploadSucceeded, ::onLeaveFlow)
}

// MARK: - 演練設定
private fun leftoverCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("3 個情境") }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l03 = Lab(
    id = "L03",
    title = "中途離開開戶流程，證件影像還留在手機裡",
    question = "第 4 題：證件和臉部影像是高敏感的個人資料，App 要怎麼處理？",
    goal = "證件與臉部影像只在需要的時候存在沙盒：上傳成功就刪，離開流程（返回、進背景、被中斷）也要刪；上傳失敗時才保留給使用者重試。",
    steps = listOf(
        "打開 practice/exchange/exchange11-ekyc/L03_TempImages.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看三個情境結束時沙盒裡還留著什麼。",
        "想一想：使用者拍好證件又不想申請了，這三張影像會留在手機裡多久？別的 App 或備份有可能讀到嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，三個情境結束時都沒有影像；情境 3 在離開流程之前仍保留 3 個檔案（給使用者重試）。",
        "進階：Android 用 App 私有的內部儲存（filesDir 或 cacheDir），用完就刪；iOS 寫檔時用 completeFileProtection；影像一律不放相簿，也不進 log 與分析工具。",
    ),
    expectBug = "「情境 1（上傳成功）：結束時沙盒裡沒有證件影像 ✓」，「情境 2（按返回離開）：結束時沙盒裡還留著 3 個影像檔（front.jpg、back.jpg、face.jpg）✗」，情境 3 也留著 3 個：「3 個情境，結束時還留著影像的有 2 個」。",
    expectFixed = "三個情境結束時沙盒裡都沒有影像，「情境 3：上傳失敗後、離開流程前，沙盒裡有 3 個影像檔（保留給使用者重試）」，「3 個情境，結束時還留著影像的有 0 個」。",
    file = "exchange11-ekyc/L03_TempImages.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = leftoverCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, onUploadSucceeded, onLeaveFlow) 執行"
            "情境 1（上傳成功）：結束時沙盒裡沒有證件影像 ✓" !in lines -> "Bug 版在上傳成功時要刪除影像（情境 1 結束時沙盒是空的）"
            n != 2 -> "Bug 還沒重現：離開流程時什麼都不做，情境 2、3 都會留著 3 個影像檔（目前 $n 個情境有留）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = leftoverCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, onUploadSucceeded, onLeaveFlow) 執行"
            n != 0 -> "還有 $n 個情境結束時留著影像：上傳成功和離開流程這兩個時機都要清掉暫存"
            else -> null
        }
    },
)
