// L01：平均 FPS 看起來很好，使用者卻覺得畫面凍住過（卡頓不能只看平均 FPS）
// 對應複習站：Advanced02Jank 第 1 題「卡頓（jank、hitch）
// 是什麼？一格畫面有多少時間可以用？」
//
// 情境：行情列表捲動 10 秒的畫面記錄：60Hz 螢幕（一格 16.7 ms），共 600 格，
// 其中兩格花了 40 ms，另外有一格花了 800 ms（畫面凍住將近一秒）。
//        其餘 597 格都剛好 16.7 ms。這份記錄是 JankStats 或 Macrobenchmark 的
// FrameTimingMetric 會給你的那種資料。
//        規則（Android vitals 的分法）：間隔超過一格預算 1.5 倍的算 slow
// frame；超過 700 ms 的算 frozen frame。
// Bug 版：只看平均 FPS（格數 ÷ 總秒數），≥ 55 就判定「良好」。平均 55.3 FPS，
// 一格 800 ms 的凍結被 597 格正常的稀釋掉了。
// 修正版：數 slow frame 與 frozen frame。有任何一格 frozen 就判定「嚴重」；
// 沒有的話 slow frame 超過 5% 判定「警告」。
package tw.harry.labs.solution.advanced02jank

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val FRAME_BUDGET_MS = 16.7

/**
 * 600 格的間隔（毫秒）：第 101、301 格是 40 ms，第 451 格是 800 ms，其餘 16.7
 * ms（兩個版本共用）
 */
private val frameIntervalsMs: List<Double> = MutableList(600) {
    FRAME_BUDGET_MS
}.also {
    it[100] = 40.0
    it[300] = 40.0
    it[450] = 800.0
}

/** 判定結果：等級（良好／警告／嚴重）和依據 */
private class Verdict(val rating: String, val detail: String)

/** 演練流程（兩個版本共用）：記錄的概況，再用各版本的規則下判定 */
private fun scenario(log: LabLog, judge: (List<Double>) -> Verdict) {
    val totalMs = frameIntervalsMs.sum()
    val avgFps = frameIntervalsMs.size / (totalMs / 1000)
    log(
        "共 ${frameIntervalsMs.size} 格，平均 ${"%.1f".format(
            avgFps,
        )} FPS，最長一格 ${frameIntervalsMs.max().toInt()} ms",
    )
    val verdict = judge(frameIntervalsMs)
    log("判定：${verdict.rating}（${verdict.detail}）")
}

// MARK: - Bug 版
private object L01Bug {
    fun judge(frames: List<Double>): Verdict {
        // 只看平均 FPS
        val avgFps = frames.size / (frames.sum() / 1000)
        return Verdict(
            if (avgFps >=
                55
            ) {
                "良好"
            } else {
                "嚴重"
            },
            "平均 ${"%.1f".format(avgFps)} FPS",
        )
    }

    fun run(log: LabLog) = scenario(log) { judge(it) }
}

// MARK: - 修正版
private object L01Fixed {
    fun judge(frames: List<Double>): Verdict {
        // slow frame：超過一格預算的 1.5 倍
        val slow = frames.count { it > FRAME_BUDGET_MS * 1.5 }
        // frozen frame：超過 700 ms
        val frozen = frames.count { it > 700 }
        val slowPercent = slow * 100.0 / frames.size
        val rating = when {
            frozen > 0 -> "嚴重"
            slowPercent > 5 -> "警告"
            else -> "良好"
        }
        return Verdict(
            rating,
            "slow frame $slow 格（${"%.1f".format(
                slowPercent,
            )}%）、frozen frame $frozen 格",
        )
    }

    fun run(log: LabLog) = scenario(log) { judge(it) }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "平均 FPS 很好，使用者卻覺得畫面凍住過",
    question = "第 1 題：卡頓（jank、hitch）是什麼？一格畫面有多少時間可以用？",
    goal = "用 slow frame、frozen frame 的格數判斷卡頓，而不是平均 FPS：使用者感受到的是偶爾一次的長停頓。",
    steps = listOf(
        "選「Bug 版」執行，看判定：平均 55.3 FPS 被判成「良好」，但最長一格是 800 ms。",
        "選「修正版」執行：數出 3 格 slow frame、1 格 frozen frame，判定「嚴重」。",
        "進階：在真的 App 裡，Macrobenchmark 的 `FrameTimingMetric` 會輸出每一格的耗時；JankStats 則在實機收集，並可以用 `PerformanceMetricsState` 標記「正在捲動」。Google Play Console 的 Android vitals 看的就是 slow frame 與 frozen frame 的比例。",
        "想一想：換成 120Hz 的螢幕，一格的預算變多少？slow frame 的門檻要跟著改嗎？（見這個單元的第 1 題）",
    ),
    expectBug = "共 600 格，平均 55.3 FPS，最長一格 800 ms；判定：良好（平均 55.3 FPS）——錯的，使用者看到了一次將近一秒的凍結。",
    expectFixed = "判定：嚴重（slow frame 3 格（0.5%）、frozen frame 1 格）。",
    file = "advanced02-jank/L01_FrameStats.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val avg = a02Number(lines, "共 600 格，平均 ")
        val verdict = lines.firstOrNull { it.startsWith("判定：") }
        when {
            avg == null -> "演練沒有跑完"

            verdict == null || verdict == "判定：（）" -> "還沒有判定：judge 要回傳 Verdict(等級, 依據)"

            !verdict.startsWith(
                "判定：良好（平均 ",
            ) -> "Bug 版只看平均 FPS（格數 ÷ 總秒數）：平均 $avg FPS ≥ 55 就要判定「良好」"

            else -> null
        }
    },
    checkFixed = { lines ->
        val verdict = lines.firstOrNull { it.startsWith("判定：") }
        when {
            verdict == null -> {
                "演練沒有跑完"
            }

            "slow frame 3 格" !in verdict || "frozen frame 1 格" !in verdict -> {
                "應該數出 3 格 slow frame（間隔超過 ${"%.1f".format(
                    FRAME_BUDGET_MS * 1.5,
                )} ms）、1 格 frozen frame（超過 700 ms）：$verdict"
            }

            !verdict.startsWith("判定：嚴重") -> {
                "有 frozen frame，應該判定「嚴重」：$verdict"
            }

            else -> {
                null
            }
        }
    },
)
