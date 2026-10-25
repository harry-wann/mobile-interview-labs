// L02：3.20 版的使用者被強制更新，3.9 版的使用者卻沒有（版本比較不能用字串）
// 對應複習站：Platform07Release 第 4 題「分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？」（強制更新）
//
// 情境：不能降版，出事只能靠強制更新：伺服器設定「最低支援版本」，App 啟動時比較自己的版本，低於最低版本就導向商店。
//        演練用 5 個情況測試 isUpdateRequired(current, minimum)，每個情況都有「應該得到的結果」。
// Bug 版：版本是字串，直接用「小於」比較。字串是一個字元一個字元比的：「3.9.0」和「3.10.0」比到第 3 個字元，'9' 大於 '1'，
//        所以 3.9.0 被當成比 3.10.0 新，該被強制更新的人沒被擋；「3.20.1」比「3.9.0」，'2' 小於 '9'，
//        新版的使用者反而被要求更新。
// 修正版：用「.」切成數字，從主版號開始一段一段比數字大小；不一樣長的版本（3.10 和 3.10.0）缺的段落當成 0。
package tw.harry.labs.solution.platform07release

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 測試的情況（兩個版本共用）：目前的版本、最低支援版本、是否應該強制更新 */
private class UpdateCase(val current: String, val minimum: String, val expected: Boolean)

private val cases = listOf(
    UpdateCase("3.9.0", "3.10.0", true),
    UpdateCase("3.10.0", "3.10.0", false),
    UpdateCase("3.20.1", "3.9.0", false),
    UpdateCase("3.10", "3.10.0", false),
    UpdateCase("2.99.9", "3.0.0", true),
)

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, isUpdateRequired: (String, String) -> Boolean) {
    var wrong = 0
    fun text(required: Boolean) = if (required) "強制更新" else "不用更新"
    for (item in cases) {
        val result = isUpdateRequired(item.current, item.minimum)
        if (result == item.expected) {
            log("✓ 目前 ${item.current}，最低 ${item.minimum} → ${text(result)}")
        } else {
            wrong++
            log("✗ 目前 ${item.current}，最低 ${item.minimum} → ${text(result)}（應該是${text(item.expected)}）")
        }
    }
    log("共 ${cases.size} 個情況，判斷錯誤 $wrong 個")
}

// MARK: - Bug 版
private object L02Bug {
    fun isUpdateRequired(current: String, minimum: String): Boolean =
        current < minimum                                       // 字串比較：一個字元一個字元比

    suspend fun run(log: LabLog) = scenario(log, ::isUpdateRequired)
}

// MARK: - 修正版
private object L02Fixed {
    fun isUpdateRequired(current: String, minimum: String): Boolean {
        val a = current.split(".").map { it.toIntOrNull() ?: 0 }
        val b = minimum.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }                        // 缺的段落當成 0
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y                            // 一段一段比數字大小
        }
        return false                                            // 完全一樣：不用更新
    }

    suspend fun run(log: LabLog) = scenario(log, ::isUpdateRequired)
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 ") && "判斷錯誤" in it }?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "3.20 版的使用者被強制更新，3.9 版卻沒有",
    question = "第 4 題：分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？（強制更新）",
    goal = "App 啟動時比較自己的版本和伺服器設定的最低支援版本，低的才強制更新。版本要怎麼比才對？",
    steps = listOf(
        "選「Bug 版」執行，看 5 個情況哪幾個判斷錯了。",
        "想一想：字串 \"3.9.0\" 和 \"3.10.0\" 是一個字元一個字元比的，比到哪一個字元就分出大小？",
        "選「修正版」執行，5 個情況都要得到應該的結果。",
        "進階：Android 的 In-App Updates API 可以用 Play 的更新優先順序（updatePriority）判斷要不要強制，但「低於最低版本一律擋住」還是要自己比版本。",
    ),
    expectBug = "「共 5 個情況，判斷錯誤 3 個」：3.9.0 對最低 3.10.0 沒被擋；3.20.1 對最低 3.9.0、3.10 對最低 3.10.0 被誤擋。",
    expectFixed = "「共 5 個情況，判斷錯誤 0 個」。",
    file = "platform07-release/L02_ForceUpdateCompare.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::isUpdateRequired) 執行"
            n != 3 -> "Bug 還沒重現：直接用字串比較 current < minimum，應該判斷錯 3 個（目前 $n 個）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::isUpdateRequired) 執行"
            n == 0 -> null
            else -> "還有 $n 個情況判斷錯誤，例如：${lines.firstOrNull { it.startsWith("✗") }}"
        }
    },
)
