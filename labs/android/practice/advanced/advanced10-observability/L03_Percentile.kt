// L03：看板上平均延遲 311 ms 很健康，但每 100 個使用者就有 6 個等了 3 秒（平均值掩蓋長尾，要看 p95／p99）
// 對應複習站：Advanced10Observability 第 2 題「上線之後要監控哪些指標？用什麼工具？」（p95 與業務指標）
//
// 情境：新版本放量時，release gate 看「下單 API 的延遲」。演練有 200 筆下單請求的延遲：188 筆在 100 到 180 毫秒之間，
//       12 筆（6%）因為伺服器逾時重試，花了 3000 毫秒。gate 的門檻是 1000 毫秒，超過就暫停放量。
//
// 你要寫：
//   ① Bug 版：看板顯示平均延遲（311 ms），低於門檻，gate 通過，但 6% 的使用者等了 3 秒。
//   ② 修正版：看 p95：用 nearest-rank 法，排序後取第 ⌈0.95 × 筆數⌉ 筆。p95 超過門檻，gate 不通過。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced10-observability/L03_Percentile.kt
package tw.harry.labs.practice.advanced10observability

import kotlin.math.ceil
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 200 筆延遲（毫秒）：188 筆正常、12 筆 3000 */
private val latencies: List<Int> = (0 until 188).map { 100 + (it % 9) * 10 } + List(12) { 3_000 }
private const val GATE_THRESHOLD_MS = 1_000

/** 演練流程（兩個版本共用）：dashboard 回傳看板顯示的指標名稱與數值（毫秒） */
private fun scenario(log: LabLog, dashboard: (List<Int>) -> Pair<String, Int>) {
    val slow = latencies.count { it >= 3_000 }
    log("實際情況：${latencies.size} 筆請求裡有 $slow 筆（${slow * 100 / latencies.size}%）等了 3000 ms")
    val (name, value) = dashboard(latencies)
    log("看板顯示：$name $value ms")
    log("release gate（門檻 $GATE_THRESHOLD_MS ms）：${if (value < GATE_THRESHOLD_MS) "通過，繼續放量" else "不通過，暫停放量"}")
}

// MARK: - Bug 版
private object L03Bug {
    fun dashboard(latencies: List<Int>): Pair<String, Int> {
        // TODO ①：看板顯示平均值：回傳 "平均" to （全部延遲加總除以筆數）
        return "平均" to 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::dashboard)
}

// MARK: - 修正版
private object L03Fixed {
    /** nearest-rank：排序後取第 ⌈p% × 筆數⌉ 筆（從 1 開始算） */
    fun percentile(values: List<Int>, p: Int): Int {
        // TODO ②-1：先排序；名次 = p% × 筆數，無條件進位（ceil）；回傳排序後第「名次」筆（名次從 1 開始，索引要減 1）
        return 0
    }

    fun dashboard(latencies: List<Int>): Pair<String, Int> {
        // TODO ②-2：看板改顯示 p95：回傳 "p95" to percentile(latencies, 95)
        return "p95" to 0
    }

    suspend fun run(log: LabLog) = scenario(log, ::dashboard)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "平均 311 ms 很健康，卻有 6% 的人等了 3 秒",
    question = "第 2 題：上線之後要監控哪些指標？用什麼工具？（p95 與長尾）",
    goal = "看延遲要看百分位數（p95、p99），不是平均值：平均會把少數很慢的請求稀釋掉。 先寫出顯示平均值的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced10-observability/L03_Percentile.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看看板顯示的平均值，以及 release gate 的結果。",
        "想一想：6% 的使用者下單要等 3 秒，這個版本該繼續放量嗎？平均值為什麼看不出來？",
        "寫 TODO ②，選「修正版」執行：p95 是多少？gate 的結果呢？",
        "進階：p50 看一般人的感受，p95、p99 看最慢的那群人；延遲的分布通常不是常態分布，平均值很少有代表性。",
    ),
    expectBug = "「看板顯示：平均 311 ms」，「release gate（門檻 1000 ms）：通過，繼續放量」。",
    expectFixed = "「看板顯示：p95 3000 ms」，「release gate（門檻 1000 ms）：不通過，暫停放量」。",
    file = "advanced10-observability/L03_Percentile.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("看板顯示") } -> "演練沒有跑完：要用 scenario 執行"
            "看板顯示：平均 311 ms" !in lines -> "Bug 還沒重現：看板顯示平均延遲（總和除以筆數），應該是 311 ms"
            "release gate（門檻 1000 ms）：通過，繼續放量" !in lines -> "Bug 版的 gate 應該通過（平均值低於門檻）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("看板顯示") } -> "演練沒有跑完：要用 scenario 執行"
            "看板顯示：p95 3000 ms" !in lines -> "看板應該顯示 p95 = 3000 ms：排序後取第 ⌈0.95 × 200⌉ = 190 筆"
            "release gate（門檻 1000 ms）：不通過，暫停放量" !in lines -> "p95 超過門檻，gate 應該不通過"
            else -> null
        }
    },
)
