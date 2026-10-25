// L01：後台顯示啟動只要 20 毫秒，
// 使用者卻等了將近半秒才看到行情（啟動時間要量到「看見行情」）
// 對應複習站：Advanced01Startup 第 2 題「「打開 App 到看見行情」
// 要量到哪一個時間點？」
//
// 情境：App 啟動後先在約 20 毫秒內畫出骨架畫面（首幀，系統自動記成 TTID），
//        再向伺服器要行情（約 450 毫秒），行情畫出來，使用者才真的能看價格、
// 下單。
//        要告訴系統與後台「內容準備好了」（TTFD），得自己呼叫
// reportFullyDrawn()；這裡用 LaunchTrace 模擬它。
//
// 你要寫：
//   ① Bug 版：在「首幀出現」的時間點就呼叫 trace.reportFullyDrawn()。
// 回報的數字會比使用者實際等的短很多。
//   ② 修正版：改在「行情第一次畫出來」的時間點才呼叫 reportFullyDrawn()。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced01-startup/L01_LaunchEndpoint.kt
package tw.harry.labs.practice.advanced01startup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 啟動時間記錄：begin 放在啟動流程最前面，reportFullyDrawn
 * 由呼叫端決定放在哪個時間點（兩個版本共用）
 */
private class LaunchTrace {
    private var startMs = 0.0
    var reportedMs: Double? = null
        private set

    fun begin() {
        startMs = a01NowMs()
    }

    /**
     * 像 Activity.reportFullyDrawn()：告訴系統「畫面內容準備好了」，
     * 只有第一次呼叫有效
     */
    fun reportFullyDrawn() {
        if (reportedMs == null) reportedMs = a01NowMs() - startMs
    }
}

/**
 * 啟動流程（兩個版本共用）：骨架畫面 → 向伺服器要行情 → 行情畫出來。
 * onFirstFrame、onPricesShown 是兩個該不該回報的時間點
 */
private fun simulateLaunch(
    log: LabLog,
    trace: LaunchTrace,
    onFirstFrame: () -> Unit,
    onPricesShown: () -> Unit,
) {
    trace.begin()
    val t0 = a01NowMs()
    a01Work(20) // 建立骨架畫面
    log("首幀（骨架畫面）出現：${(a01NowMs() - t0).toInt()} ms")
    onFirstFrame()
    // 向伺服器要行情（網路）
    a01Work(450)
    log("使用者看到行情：${(a01NowMs() - t0).toInt()} ms")
    onPricesShown()
    log("回報給後台的啟動時間：${(trace.reportedMs ?: -1.0).toInt()} ms")
}

// MARK: - Bug 版
private object L01Bug {
    suspend fun run(log: LabLog) = withContext(Dispatchers.Default) {
        val trace = LaunchTrace()
        simulateLaunch(
            log,
            trace,
            onFirstFrame = {
                // TODO ①：骨架畫面一出現就回報「畫完了」（呼叫 trace.
                // reportFullyDrawn()）
            },
            onPricesShown = { },
        )
    }
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun run(log: LabLog) = withContext(Dispatchers.Default) {
        val trace = LaunchTrace()
        simulateLaunch(
            log,
            trace,
            // 首幀只是中間的一個時間點（系統已自動記成 TTID）
            onFirstFrame = { },
            onPricesShown = {
                // TODO ②：行情畫出來、使用者才真的能用的這一刻，才回報（呼叫
                // trace.reportFullyDrawn()）
            },
        )
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "後台說啟動 20 毫秒，使用者等了半秒才看到行情",
    question = "第 2 題：「打開 App 到看見行情」要量到哪一個時間點？",
    goal = "啟動時間要量到使用者看見行情（TTFD），而不是第一個畫面（TTID）。先寫出「首幀就回報」的 Bug 版，再把 reportFullyDrawn() 放到對的時間點。",
    steps = listOf(
        "打開 practice/advanced/advanced01-startup/L01_LaunchEndpoint.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，比較三行 log：首幀出現的時間、使用者看到行情的時間，和回報給後台的啟動時間。",
        "寫 TODO ②，選「修正版」執行：回報的數字會和「使用者看到行情」的時間幾乎一樣。",
        "進階：在真正的 App 裡，冷啟動後看 Logcat 過濾 `Displayed`（系統記的 TTID）和 `Fully drawn`（你呼叫 reportFullyDrawn() 之後才會出現）；兩行的時間差就是使用者看著骨架畫面等的時間。",
        "想一想：iOS 的首幀與 OSSignposter 對應 Bug 版和修正版的哪一個時間點？（見這個單元的第 2 題）",
    ),
    expectBug = "首幀約 20 ms、使用者看到行情約 470 ms，回報給後台的卻是約 20 ms：差了二十幾倍。",
    expectFixed = "首幀約 20 ms、使用者看到行情約 470 ms，回報給後台的也是約 470 ms。",
    file = "advanced01-startup/L01_LaunchEndpoint.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val reported = a01Number(lines, "回報給後台的啟動時間：")
        val shown = a01Number(lines, "使用者看到行情：")
        when {
            reported == null || shown == null -> "演練沒有跑完"
            reported < 0 -> "沒有回報啟動時間：要在某個時間點呼叫 trace.reportFullyDrawn()"
            reported < 150 && shown >= 300 -> null
            else -> "回報了 ${reported.toInt()} ms：Bug 版要在首幀出現時就回報（onFirstFrame），數字才會比使用者實際等的短很多"
        }
    },
    checkFixed = { lines ->
        val reported = a01Number(lines, "回報給後台的啟動時間：")
        val shown = a01Number(lines, "使用者看到行情：")
        when {
            reported == null || shown == null -> "演練沒有跑完"
            reported < 0 -> "沒有回報啟動時間：要在某個時間點呼叫 trace.reportFullyDrawn()"
            reported >= shown - 5 -> null
            else -> "回報了 ${reported.toInt()} ms，比使用者實際等的 ${shown.toInt()} ms 短：要等行情畫出來（onPricesShown）才回報"
        }
    },
)
