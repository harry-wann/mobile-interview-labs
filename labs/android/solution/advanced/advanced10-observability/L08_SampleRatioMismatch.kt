// L08：A/B 測試的人數比例明明不是 50 比 50，卻宣布 B 勝出（要先檢查 SRM）
// 對應複習站：Advanced10Observability 第 6 題「A/B 測試有哪些統計陷阱？」
// （SRM）
//
// 情境：三個實驗都設計成 50% 對 50% 分流。演練有三組實際的人數與轉換率：
//         實驗 1：A 50080 人、B 49920 人，B 轉換率較高。
//         實驗 2：A 52500 人、B 47500 人，B 轉換率較高（分流有問題）。
//         實驗 3：A 50800 人、B 49200 人，B 轉換率較高（分流有問題）。
//       SRM（sample ratio mismatch，樣本比例不符）：
// 實際人數比例偏離設計得太多，代表分流或事件記錄有 bug
//       （例如 B 組的畫面更容易 crash 或載入失敗，事件因此遺失），
// 這時轉換率的比較已經被汙染。
// Bug 版：不檢查人數比例，哪一組轉換率高就宣布哪一組勝出。三個實驗全部「B
// 勝出」。
// 修正版：先做卡方檢定（chi-square，自由度 1）：Σ (實際 − 預期)² ÷ 預期；大於
// 10.83（p 值小於 0.001）就判定 SRM，
//        不下結論，先查分流或事件記錄的問題。只有實驗 1 可以宣布 B 勝出。
package tw.harry.labs.solution.advanced10observability

import kotlin.math.pow
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L08Experiment(
    val name: String,
    val a: Int,
    val b: Int,
    val aRate: Double,
    val bRate: Double,
)

private val experiments = listOf(
    L08Experiment("實驗 1", 50_080, 49_920, 4.8, 5.0),
    L08Experiment("實驗 2", 52_500, 47_500, 4.8, 5.1),
    L08Experiment("實驗 3", 50_800, 49_200, 4.8, 5.0),
)

/**
 * 演練流程（兩個版本共用）：hasSrm(A 組人數, B 組人數) 回傳要不要判定為
 * SRM（預期各 50%）
 */
private fun scenario(log: LabLog, hasSrm: (Int, Int) -> Boolean) {
    for (e in experiments) {
        val verdict = when {
            hasSrm(e.a, e.b) -> "分流異常（SRM），結果不可信，先查原因"
            e.bRate > e.aRate -> "B 勝出"
            else -> "A 勝出"
        }
        log(
            "${e.name}：A ${e.a} 人、B ${e.b} 人，轉換率 A ${e.aRate}%／B ${e.bRate}% → $verdict",
        )
    }
}

// MARK: - Bug 版
private object L08Bug {
    fun hasSrm(a: Int, b: Int): Boolean = // 沒有檢查人數比例
        false

    suspend fun run(log: LabLog) = scenario(log, ::hasSrm)
}

// MARK: - 修正版
private object L08Fixed {
    fun hasSrm(a: Int, b: Int): Boolean {
        // 設計是 50% 對 50%
        val expected = (a + b) / 2.0
        val chiSquare = listOf(a, b).sumOf { (it - expected).pow(2) / expected }
        // 自由度 1，p 值 0.001 的門檻
        return chiSquare > 10.83
    }

    suspend fun run(log: LabLog) = scenario(log, ::hasSrm)
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "人數比例不對，卻宣布 B 勝出",
    question = "第 6 題：A/B 測試有哪些統計陷阱？（SRM）",
    goal = "比較轉換率之前，先檢查兩組的人數比例是否符合設計；差太多（SRM）就代表分流或事件記錄有問題，結果不能用。",
    steps = listOf(
        "選「Bug 版」執行，看三個實驗的結論。人數 52500 對 47500，設計是 50 對 50，這正常嗎？",
        "想一想：設計 50/50 的實驗，實際 50080 對 49920 是隨機誤差，52500 對 47500 呢？為什麼不能只看轉換率？",
        "選「修正版」執行：只有實驗 1 通過 SRM 檢查，實驗 2、3 被擋下。",
        "進階：卡方值 = Σ (實際 − 預期)² ÷ 預期。實驗 1 約 0.5；實驗 3 約 25.6，超過 10.83。偵測到 SRM 時，要先查是不是某組的畫面更容易 crash、載入失敗，事件因此遺失。",
    ),
    expectBug = "三個實驗都是「B 勝出」。",
    expectFixed = "實驗 1：「B 勝出」；實驗 2、3：「分流異常（SRM），結果不可信，先查原因」。",
    file = "advanced10-observability/L08_SampleRatioMismatch.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L08Bug.run(log)
            Variant.FIXED -> L08Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val winners = lines.count { it.endsWith("→ B 勝出") }
        when {
            lines.none { it.startsWith("實驗 1") } -> "演練沒有跑完：要用 scenario 執行"
            winners != 3 -> "Bug 還沒重現：hasSrm 一律回傳 false，三個實驗都應該宣布 B 勝出（目前 $winners 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        fun line(prefix: String) =
            lines.firstOrNull { it.startsWith(prefix) } ?: ""
        when {
            lines.none { it.startsWith("實驗 1") } -> "演練沒有跑完：要用 scenario 執行"

            !line(
                "實驗 1",
            ).endsWith("→ B 勝出") -> "實驗 1（50080 對 49920）是正常的隨機誤差，不該被判成 SRM"

            !line(
                "實驗 2",
            ).endsWith("先查原因") -> "實驗 2（52500 對 47500）應該被判成 SRM：卡方值要大於 10.83"

            !line(
                "實驗 3",
            ).endsWith(
                "先查原因",
            ) -> "實驗 3（50800 對 49200）應該被判成 SRM：卡方值約 25.6，大於 10.83"

            else -> null
        }
    },
)
