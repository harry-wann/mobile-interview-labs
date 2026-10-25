// L02：120 Hz 的手機上，掉了一格卻沒被偵測到（每一格的時間預算 = 1000 ÷
// 刷新率）
// 對應複習站：Platform02Threading 第 2 題「每一格畫面有多少時間？60 Hz 和 120
// Hz 差在哪？」
//
// 情境：App 裡有一個卡頓偵測，量每一格花了多少 ms，超過「時間預算」的 1.5
// 倍就算掉格（jank）。
//        演練用兩份錄下來的資料：60 Hz 與 120 Hz 的手機各量了一段，
// 每一格花的毫秒數已經寫好。
// Bug 版：時間預算寫死 16.7 ms（只想到 60 Hz）。120 Hz 的手機一格只有 8.3 ms，
// 掉一格就是 16.7 ms，卻低於 25 ms 的門檻，沒被偵測到。
// 修正版：時間預算 = 1000 ÷ 這支手機的刷新率。60 Hz 約 16.7 ms，120 Hz 約 8.3
// ms。
//        （正式的 App：display.refreshRate；Choreographer.FrameCallback 的
// frameTimeNanos 相減就是一格花的時間。）
package tw.harry.labs.solution.platform02threading

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 卡頓偵測（兩個版本共用）：一格花的時間超過預算的 1.5 倍，就算掉格 */
private fun hitchCount(frameMs: List<Double>, budgetMs: Double): Int =
    frameMs.count {
        it >
            budgetMs * 1.5
    }

/** 錄下來的資料（兩個版本共用）：每一格花的毫秒數 */
// 實際掉了 2 次
private val frames60 = listOf(16.67, 16.67, 33.33, 16.67, 50.0, 16.67)

// 實際掉了 3 次
private val frames120 =
    listOf(8.33, 8.33, 16.67, 8.33, 16.67, 8.33, 33.33, 8.33)

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, budgetMs: (refreshRate: Double) -> Double) {
    val on60 = hitchCount(frames60, budgetMs(60.0))
    val on120 = hitchCount(frames120, budgetMs(120.0))
    log("60 Hz 的手機：偵測到 $on60 次掉格（實際 2 次）")
    log("120 Hz 的手機：偵測到 $on120 次掉格（實際 3 次）")
}

// MARK: - Bug 版
private object L02Bug {
    /** 這一格的時間預算 */
    fun budgetMs(refreshRate: Double): Double = 16.7 // 寫死 60 Hz 的預算

    fun run(log: LabLog) = scenario(log, ::budgetMs)
}

// MARK: - 修正版
private object L02Fixed {
    fun budgetMs(refreshRate: Double): Double = 1000 / refreshRate // 一秒 ÷ 每秒幾格

    fun run(log: LabLog) = scenario(log, ::budgetMs)
}

// MARK: - 演練設定
private fun detected(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.substringBefore(" ")?.toIntOrNull()

val l02 = Lab(
    id = "L02",
    title = "120 Hz 的手機上，掉格沒被偵測到",
    question = "第 2 題：每一格畫面有多少時間？60 Hz 和 120 Hz 差在哪？",
    goal = "每一格的時間預算 = 1000 ÷ 刷新率。卡頓偵測要用手機實際的刷新率算預算，兩種手機都要抓到所有掉格。",
    steps = listOf(
        "選「Bug 版」按執行，比較兩種手機「偵測到幾次」和「實際幾次」。",
        "算一算：120 Hz 的預算是幾 ms？掉一格（16.67 ms）要超過預算的 1.5 倍才算掉格，門檻是多少？",
        "選「修正版」執行：兩種手機都抓到實際的次數。",
    ),
    expectBug = "60 Hz 的手機偵測到 2 次（正確）；120 Hz 的手機只偵測到 1 次，漏了 2 次（16.67 ms 的掉格都被當成正常）。",
    expectFixed = "60 Hz 偵測到 2 次、120 Hz 偵測到 3 次，都和實際相同。",
    file = "platform02-threading/L02_FrameBudget.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val a = detected(lines, "60 Hz 的手機：偵測到 ")
        val b = detected(lines, "120 Hz 的手機：偵測到 ")
        when {
            a == null || b == null -> "演練沒有跑完"
            a != 2 -> "Bug 版的預算寫死 16.7 ms，60 Hz 的手機應該剛好偵測到 2 次（現在 $a 次）"
            b >= 3 -> "Bug 還沒重現：Bug 版的預算要寫死 16.7，120 Hz 的手機就會漏掉掉格"
            else -> null
        }
    },
    checkFixed = { lines ->
        val a = detected(lines, "60 Hz 的手機：偵測到 ")
        val b = detected(lines, "120 Hz 的手機：偵測到 ")
        when {
            a == null || b == null -> "演練沒有跑完"
            a != 2 -> "60 Hz 的手機應該偵測到 2 次（現在 $a 次）"
            b != 3 -> "120 Hz 的手機應該偵測到 3 次（現在 $b 次）：預算要依刷新率算，1000 ÷ refreshRate"
            else -> null
        }
    },
)
