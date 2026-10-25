// L04：新版才放給 1% 的使用者就被暫停，舊版本身有問題時又一直暫停（分階段發布的判斷太粗糙）
// 對應複習站：Testing06CI 第 4 題「發布的時候，怎麼避免把有問題的版本推給所有使用者？」
//
// 情境：分階段發布時，用 crash-free session 比例（沒有 crash 的 session 佔幾 %）決定下一步：擴大發布、再等一等、暫停發布。
//       decide(newVersion, baseline) 拿新版和舊版（baseline）的統計來判斷。演練用四種情況：
//       ① 1% 階段：只有 40 個 session，其中 1 個 crash，crash-free 97.5%，舊版 99.4%。資料太少，還不能判斷。
//       ② 10% 階段：5,000 個 session，crash-free 99.5%，舊版 99.4%。正常，該擴大。
//       ③ 25% 階段：12,000 個 session，crash-free 99.0%，舊版 99.4%。明顯變差，該暫停。
//       ④ 舊版本身就只有 98.8%，新版 98.9%，10,000 個 session。新版沒有比較差，該擴大。
//
// 你要寫：
//   ① Bug 版：decide 看新版的 crashFreeRate：低於 99.5 回傳 HALT，否則 EXPAND。
//   ② 修正版：decide 先看 newVersion.sessions：不到 1,000 回傳 HOLD；
//      夠了就和舊版比：新版 crashFreeRate 低於 baseline.crashFreeRate − 0.3 回傳 HALT，否則 EXPAND。
// 只改有 TODO 的地方。解答：solution/testing/testing06-ci/L04_RolloutDecision.kt
package tw.harry.labs.practice.testing06ci

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：四種情況各判斷一次 */
private fun scenario(log: LabLog, decide: (T06Stats, T06Stats) -> T06Decision) {
    val cases = listOf(
        Triple("① 1% 階段，40 個 session（crash-free 97.5%）", T06Stats(40, 39), T06Stats(100_000, 99_400)),
        Triple("② 10% 階段，5,000 個 session（crash-free 99.5%）", T06Stats(5_000, 4_975), T06Stats(100_000, 99_400)),
        Triple("③ 25% 階段，12,000 個 session（crash-free 99.0%）", T06Stats(12_000, 11_880), T06Stats(100_000, 99_400)),
        Triple("④ 舊版只有 98.8%，新版 98.9%，10,000 個 session", T06Stats(10_000, 9_890), T06Stats(100_000, 98_800)),
    )
    for ((title, new, baseline) in cases) {
        log("$title：決定${decide(new, baseline).title}")
    }
}

// MARK: - Bug 版
private object L04Bug {
    fun decide(newVersion: T06Stats, baseline: T06Stats): T06Decision =
        // TODO ①：新版的 crashFreeRate 低於 99.5 回傳 HALT，否則 EXPAND（目前一律 EXPAND）
        T06Decision.EXPAND

    fun run(log: LabLog) = scenario(log, ::decide)
}

// MARK: - 修正版
private object L04Fixed {
    fun decide(newVersion: T06Stats, baseline: T06Stats): T06Decision {
        // TODO ②-1：newVersion.sessions 不到 1,000 回傳 HOLD（資料太少，先等）
        // TODO ②-2：新版的 crashFreeRate 低於 baseline.crashFreeRate − 0.3 回傳 HALT，否則 EXPAND
        return T06Decision.EXPAND
    }

    fun run(log: LabLog) = scenario(log, ::decide)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "新版才放 1% 就被暫停，舊版有問題時一直暫停",
    question = "第 4 題：發布的時候，怎麼避免把有問題的版本推給所有使用者？",
    goal = "用 crash-free 比例決定要不要繼續擴大發布時，要看資料夠不夠多，並且和舊版比較，不是只看一個固定門檻。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看四種情況各自的決定。① 和 ④ 的決定合理嗎？",
        "想一想：1% 階段只有 40 個 session，1 個 crash 就是 2.5% 的 crash 率，這個數字可信嗎？",
        "寫 TODO ②，選「修正版」執行：① 再等一等、② 擴大、③ 暫停、④ 擴大。",
    ),
    expectBug = "① 暫停發布（資料太少，誤判）；② 擴大發布；③ 暫停發布；④ 暫停發布（新版沒有比舊版差，誤判）。",
    expectFixed = "① 再等一等；② 擴大發布；③ 暫停發布；④ 擴大發布。",
    file = "testing06-ci/L04_RolloutDecision.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "② 10% 階段，5,000 個 session（crash-free 99.5%）：決定擴大發布" !in lines -> "② crash-free 99.5% 不低於門檻 99.5%，應該擴大"
            "③ 25% 階段，12,000 個 session（crash-free 99.0%）：決定暫停發布" !in lines -> "③ crash-free 99.0% 低於門檻，應該暫停"
            "① 1% 階段，40 個 session（crash-free 97.5%）：決定暫停發布" !in lines ||
                "④ 舊版只有 98.8%，新版 98.9%，10,000 個 session：決定暫停發布" !in lines -> "Bug 還沒重現：只看新版的 crash-free 有沒有低於 99.5%，① 和 ④ 都會被暫停"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "① 1% 階段，40 個 session（crash-free 97.5%）：決定再等一等" !in lines -> "① 只有 40 個 session，資料太少（不到 1,000 個），應該再等一等"
            "② 10% 階段，5,000 個 session（crash-free 99.5%）：決定擴大發布" !in lines -> "② 新版 99.5% 比舊版 99.4% 好，應該擴大"
            "③ 25% 階段，12,000 個 session（crash-free 99.0%）：決定暫停發布" !in lines -> "③ 新版 99.0% 比舊版 99.4% 低 0.4 個百分點，超過 0.3，應該暫停"
            "④ 舊版只有 98.8%，新版 98.9%，10,000 個 session：決定擴大發布" !in lines -> "④ 新版 98.9% 比舊版 98.8% 好，應該擴大"
            else -> null
        }
    },
)
