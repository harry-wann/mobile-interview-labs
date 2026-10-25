// L05：改版後的啟動時間「快了 40%」，其實一點都沒有變快（量一次、只看平均值）
// 對應複習站：Advanced01Startup 第 7 題「怎麼量啟動時間？」
//
// 情境：優化前後各量了 10 次冷啟動（毫秒）。優化前有一次碰巧遇到系統忙碌，花了
// 3010 ms；其他次都在 400 ms 左右。
//        優化後 10 次都在 400 ms 左右。這個改動其實沒有影響啟動時間，
// 差別只是那一次碰巧的極端值。
//        團隊的規則：代表值和慢的那一端都改善超過 10%，才算有效。
//
// 你要寫：
//   ① Bug 版 summarize：代表值用平均值，慢的那一端用最大值。
// 一次極端值就把平均拉高，結論被騙成「有效」。
//   ② 修正版 summarize：代表值用中位數（排序後正中間的值，
// 筆數是偶數就取中間兩個的平均），
//      慢的那一端用 p90（nearest-rank：排序後第 ceil(0.9 × n) 個）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced01-startup/L05_LaunchStats.kt
package tw.harry.labs.practice.advanced01startup

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 優化前後各 10 次冷啟動的毫秒數（兩個版本共用） */
private val beforeRuns =
    listOf(
        412.0,
        398.0,
        405.0,
        3010.0,
        401.0,
        409.0,
        396.0,
        403.0,
        399.0,
        407.0,
    )
private val afterRuns =
    listOf(401.0, 397.0, 404.0, 399.0, 406.0, 395.0, 402.0, 398.0, 403.0, 400.0)

/** 一組量測的摘要：代表值，和慢的那一端 */
private class Summary(
    val centerName: String,
    val center: Double,
    val tailName: String,
    val tail: Double,
)

/** 演練流程（兩個版本共用）：摘要優化前後，比較，下結論 */
private fun scenario(log: LabLog, summarize: (List<Double>) -> Summary) {
    val before = summarize(beforeRuns)
    val after = summarize(afterRuns)
    log(
        "優化前 ${beforeRuns.size} 次：${before.centerName} ${before.center.toInt()} ms、${before.tailName} ${before.tail.toInt()} ms",
    )
    log(
        "優化後 ${afterRuns.size} 次：${after.centerName} ${after.center.toInt()} ms、${after.tailName} ${after.tail.toInt()} ms",
    )
    if (before.center <= 0 || before.tail <= 0) {
        // 還沒寫完 summarize 時，避免除以 0
        log("結論：還沒有算出代表值")
        return
    }
    val centerGain = (before.center - after.center) / before.center * 100
    val tailGain = (before.tail - after.tail) / before.tail * 100
    if (centerGain >= 10 && tailGain >= 10) {
        log("結論：有效，${before.centerName}改善 ${centerGain.toInt()}%")
    } else {
        log("結論：沒有顯著差異（${before.centerName}改善 ${centerGain.toInt()}%）")
    }
}

// MARK: - Bug 版
private object L05Bug {
    fun summarize(samples: List<Double>): Summary {
        // TODO ①：center 用平均值（總和 ÷ 筆數），tail 用最大值（samples.
        // max()）
        return Summary("平均", 0.0, "最大", 0.0)
    }

    fun run(log: LabLog) = scenario(log) { summarize(it) }
}

// MARK: - 修正版
private object L05Fixed {
    fun summarize(samples: List<Double>): Summary {
        val sorted = samples.sorted()
        // TODO ②-1：算中位數（sorted 正中間的值；筆數是偶數就取中間兩個的平均）
        // TODO ②-2：算 p90：排序後第 ceil(0.9 × n) 個（第 1 個的索引是 0），
        // 可以用 kotlin.math.ceil
        return Summary("中位數", 0.0, "p90", sorted.size * 0.0)
    }

    fun run(log: LabLog) = scenario(log) { summarize(it) }
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "優化後「快了 40%」，其實一點都沒有變快",
    question = "第 7 題：怎麼量啟動時間？",
    goal = "量啟動時間要重複多次，看中位數和 p90（慢的那一端），不要只看平均值或單次結果。先寫出被平均值騙到的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced01-startup/L05_LaunchStats.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看結論：用平均值比較，優化前被一次 3010 ms 拉高，結論變成「有效」。",
        "寫 TODO ②，選「修正版」執行：中位數與 p90 都幾乎沒變，結論是「沒有顯著差異」。",
        "進階：Macrobenchmark 的 `measureRepeated(iterations = 10)` 會幫你重複跑並輸出 min／median／max；iOS 的 Instruments 要自己多錄幾次。",
        "想一想：模擬器與 debug build 的啟動時間為什麼沒有參考價值？（見這個單元的第 7 題）",
    ),
    expectBug = "優化前：平均 664 ms、最大 3010 ms；優化後：平均 400 ms、最大 406 ms；結論：有效，平均改善 39%（錯的）。",
    expectFixed = "優化前：中位數 404 ms、p90 412 ms；優化後：中位數 400 ms、p90 404 ms；結論：沒有顯著差異（中位數改善 0%）。",
    file = "advanced01-startup/L05_LaunchStats.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("結論：") } -> {
                "演練沒有跑完"
            }

            lines.none { it.startsWith("優化前 10 次：平均 664 ms、最大 3010 ms") } -> {
                "Bug 版的代表值要用平均值（samples 的總和除以次數），慢的那一端用最大值"
            }

            lines.none { it.startsWith("結論：有效") } -> {
                "Bug 還沒重現：用平均值比較，結論應該被極端值騙成「有效」"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("結論：") } -> {
                "演練沒有跑完"
            }

            "優化前 10 次：中位數 404 ms、p90 412 ms" !in lines -> {
                "優化前的中位數應該是 404 ms（排序後第 5、6 個的平均）、p90 是 412 ms（排序後第 9 個）"
            }

            "優化後 10 次：中位數 400 ms、p90 404 ms" !in lines -> {
                "優化後的中位數應該是 400 ms、p90 是 404 ms"
            }

            lines.none { it.startsWith("結論：沒有顯著差異") } -> {
                "中位數與 p90 都沒有明顯改善，結論應該是「沒有顯著差異」"
            }

            else -> {
                null
            }
        }
    },
)
