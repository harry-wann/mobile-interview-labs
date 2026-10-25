// L04：放量 1% 時只有 40 位使用者，gate
// 卻憑這個數字決定要擴大或暫停（樣本太少的數字不能拿來決定）
// 對應複習站：Advanced10Observability 第 3 題「新版本發布時，
// 怎麼用指標決定要不要繼續擴大？」
//
// 情境：新版本分階段放量，每一階段結束時 gate 要決定「擴大」或「暫停」。
// 比較的指標是 crash-free users，基準是上一版的 99.2%。
//       演練有四個階段的資料：
//         A. 放量 1%，只有 40 位使用者，沒有人 crash（100%）。B. 放量 1%，40
// 位使用者，1 位 crash（97.5%）。
//         C. 放量 5%，2000 位使用者，60 位 crash（97.0%）。D. 放量 5%，2000
// 位使用者，14 位 crash（99.3%）。
// Bug 版：只比較數字：新版 crash-free users 大於等於上一版就擴大，否則暫停。40
// 位使用者的 100% 沒有意義，卻決定擴大；
//        同樣 40 位、只多 1 位 crash，
// 卻決定暫停（一位使用者的偶然就改變了結論）。
// 修正版：先看樣本數：使用者不到 1000 位就「等待，繼續收集」，不做決定；
// 樣本夠了才比較，並容許 0.3 個百分點以內的誤差。
package tw.harry.labs.solution.advanced10observability

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L04Stage(
    val name: String,
    val users: Int,
    val crashedUsers: Int,
) {
    val crashFree get() = 1.0 - crashedUsers.toDouble() / users
}

private const val PREVIOUS_CRASH_FREE = 0.992
private val stages = listOf(
    L04Stage("A. 放量 1%（40 位，0 位 crash）", 40, 0),
    L04Stage("B. 放量 1%（40 位，1 位 crash）", 40, 1),
    L04Stage("C. 放量 5%（2000 位，60 位 crash）", 2_000, 60),
    L04Stage("D. 放量 5%（2000 位，14 位 crash）", 2_000, 14),
)

/**
 * 演練流程（兩個版本共用）：decide(階段, 上一版的 crash-free users)
 * 回傳「擴大」「暫停」或「等待」
 */
private fun scenario(log: LabLog, decide: (L04Stage, Double) -> String) {
    for (stage in stages) {
        log(
            "${stage.name}：${decide(stage, PREVIOUS_CRASH_FREE)}",
        )
    }
}

// MARK: - Bug 版
private object L04Bug {
    fun decide(stage: L04Stage, previous: Double): String = // 只比數字，不管樣本有多少
        if (stage.crashFree >= previous) "擴大" else "暫停"

    suspend fun run(log: LabLog) = scenario(log, ::decide)
}

// MARK: - 修正版
private object L04Fixed {
    private const val MINIMUM_USERS = 1_000
    private const val TOLERANCE = 0.003

    fun decide(stage: L04Stage, previous: Double): String {
        if (stage.users < MINIMUM_USERS) return "等待（樣本不足，繼續收集）"
        return if (stage.crashFree >= previous - TOLERANCE) "擴大" else "暫停"
    }

    suspend fun run(log: LabLog) = scenario(log, ::decide)
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "40 位使用者的數字，就決定要不要擴大",
    question = "第 3 題：新版本發布時，怎麼用指標決定要不要繼續擴大？（樣本數）",
    goal = "release gate 要先確認樣本數夠大，才比較指標；樣本不足時的決定是「等待」，不是「擴大」或「暫停」。",
    steps = listOf(
        "選「Bug 版」執行，看 A、B 兩個只有 40 位使用者的階段，gate 做了什麼決定。",
        "想一想：A 和 B 只差 1 位使用者 crash，結論卻相反，這個決定可靠嗎？",
        "選「修正版」執行：A、B 都是「等待」，C 暫停，D 擴大。",
        "進階：樣本數的門檻、容許的誤差要事前定義；更嚴謹的做法是用信賴區間，但「樣本夠大才決定、事前定義門檻」是最基本的。",
    ),
    expectBug = "A「擴大」、B「暫停」，只差 1 位使用者的偶然；C「暫停」、D「擴大」。",
    expectFixed = "A、B：「等待（樣本不足，繼續收集）」；C：「暫停」；D：「擴大」。",
    file = "advanced10-observability/L04_ReleaseGate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("A.") } -> "演練沒有跑完：要用 scenario 執行"
            "A. 放量 1%（40 位，0 位 crash）：擴大" !in lines -> "Bug 還沒重現：只比較 crash-free users 大於等於上一版就擴大，A 應該是「擴大」"
            "B. 放量 1%（40 位，1 位 crash）：暫停" !in lines -> "Bug 還沒重現：B 的 crash-free users 是 97.5%，低於上一版，應該是「暫停」"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("A.") } -> "演練沒有跑完：要用 scenario 執行"
            "A. 放量 1%（40 位，0 位 crash）：等待（樣本不足，繼續收集）" !in lines -> "A 只有 40 位使用者，應該回「等待（樣本不足，繼續收集）」"
            "B. 放量 1%（40 位，1 位 crash）：等待（樣本不足，繼續收集）" !in lines -> "B 只有 40 位使用者，應該回「等待（樣本不足，繼續收集）」"
            "C. 放量 5%（2000 位，60 位 crash）：暫停" !in lines -> "C 樣本夠了，97.0% 比上一版低很多，應該「暫停」"
            "D. 放量 5%（2000 位，14 位 crash）：擴大" !in lines -> "D 樣本夠了，99.3% 不比上一版差，應該「擴大」"
            else -> null
        }
    },
)
