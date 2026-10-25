// L03：同一個使用者，一下看得到新功能、一下又看不到（放量要用穩定的分桶）
// 對應複習站：Platform07Release 第 4 題「分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？」（功能開關與放量）
//
// 情境：新功能先對 20% 的使用者開放（遠端 feature flag），出問題能立刻關掉。演練有 1000 位使用者，每位使用者在這一天內打開 App 5 次，
//        每次 App 都問「我在名單裡嗎？」。放量比例從 10% 調到 50% 時，原本看得到的人不應該消失。
//
// 你要寫：
//   ① Bug 版：isEnabled 每次詢問都用 random.nextInt(100) 擲一次骰子，小於 percent 就開。
//   ② 修正版：bucket(userId) 用 FNV-1a 算出使用者的固定桶號（0 到 99）；isEnabled 回傳 桶號 < percent。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform07-release/L03_RolloutBucket.kt
package tw.harry.labs.practice.platform07release

import kotlin.random.Random
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：isEnabled(userId, percent) 回答「這位使用者要不要開」 */
private fun scenario(log: LabLog, isEnabled: (String, Int) -> Boolean) {
    val users = List(1000) { "user-$it" }

    // 20%：每位使用者問 5 次，答案不一致的算「閃爍」
    val flickering = users.count { user -> List(5) { isEnabled(user, 20) }.toSet().size > 1 }
    log("放量 20%：1000 位使用者各問 5 次，答案不一致的有 $flickering 位")

    // 10% 調到 50%：10% 時看得到的人，50% 時是不是還看得到
    val at10 = users.filter { isEnabled(it, 10) }
    val lost = at10.count { !isEnabled(it, 50) }
    log("放量 10% 看得到 ${at10.size} 位，調到 50% 後反而看不到的有 $lost 位")
}

// MARK: - Bug 版
private object L03Bug {
    fun isEnabled(userId: String, percent: Int, random: Random): Boolean {
        // TODO ①：每次詢問都擲一次骰子：random.nextInt(100) < percent
        return false
    }

    suspend fun run(log: LabLog) {
        val random = Random(2026)
        scenario(log) { user, percent -> isEnabled(user, percent, random) }
    }
}

// MARK: - 修正版
private object L03Fixed {
    /** FNV-1a 32 位元雜湊：同樣的字串，不管哪一次、哪一支手機、哪個平台，算出來都一樣 */
    fun bucket(userId: String): Int {
        // TODO ②-1：FNV-1a 32 位元雜湊：var hash = 2_166_136_261L；對 "rollout:$userId" 的每個 UTF-8 byte：
        //          hash = ((hash xor (byte.toLong() and 0xFF)) * 16_777_619L) and 0xFFFFFFFFL；最後回傳 (hash % 100).toInt()
        return 0
    }

    fun isEnabled(userId: String, percent: Int): Boolean {
        // TODO ②-2：桶號小於 percent 就開：bucket(userId) < percent
        return false
    }

    suspend fun run(log: LabLog) = scenario(log, ::isEnabled)
}

// MARK: - 演練設定
private fun numbers(lines: List<String>, prefix: String): List<Int>? =
    lines.firstOrNull { it.startsWith(prefix) }?.let { line -> Regex("\\d+").findAll(line).map { it.value.toInt() }.toList() }

val l03 = Lab(
    id = "L03",
    title = "同一個使用者，一下看得到新功能、一下又看不到",
    question = "第 4 題：分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？（功能開關與放量）",
    goal = "遠端功能開關對一部分使用者放量時，同一位使用者的答案要固定；放量比例調高，原本看得到的人不能消失。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看放量 20% 時答案不一致的使用者有幾位，以及 10% 調到 50% 後消失的人數，出現 ✓。",
        "想一想：App 每次問「我在名單裡嗎？」，答案是怎麼決定的？同一位使用者每次一樣嗎？",
        "寫 TODO ②，選「修正版」執行，兩個數字都要是 0。",
        "進階：分桶要用固定的雜湊（FNV-1a、SHA-256），而且 iOS、Android、伺服器要用同一套演算法，同一位使用者在兩個平台才會得到一樣的答案。",
    ),
    expectBug = "「放量 20%：1000 位使用者各問 5 次，答案不一致的有」約 660 位；「調到 50% 後反而看不到的有」約 45 位。",
    expectFixed = "兩個數字都是 0：「答案不一致的有 0 位」「調到 50% 後反而看不到的有 0 位」。",
    file = "platform07-release/L03_RolloutBucket.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val twenty = numbers(lines, "放量 20%")
        val ten = numbers(lines, "放量 10%")
        if (twenty == null || ten == null || ten.size != 4) {
            "演練沒有跑完：要用 scenario(log, isEnabled) 執行"
        } else if (twenty.last() < 300 || ten[3] == 0) {
            "Bug 還沒重現：每次詢問都用 random.nextInt(100) 擲骰子，答案才會閃爍（目前不一致 ${twenty.last()} 位、消失 ${ten[3]} 位）"
        } else {
            null
        }
    },
    checkFixed = { lines ->
        val twenty = numbers(lines, "放量 20%")
        val ten = numbers(lines, "放量 10%")
        if (twenty == null || ten == null || ten.size != 4) {
            "演練沒有跑完：要用 scenario(log, isEnabled) 執行"
        } else if (twenty.last() != 0 || ten[3] != 0) {
            "同一位使用者的答案要固定：用 bucket(userId) 算出 0 到 99 的桶號，桶號小於 percent 才開（目前不一致 ${twenty.last()} 位、消失 ${ten[3]} 位）"
        } else if (ten[1] !in 70..130) {
            "10% 應該約有 100 位使用者看得到，目前 ${ten[1]} 位：桶號要平均分布在 0 到 99"
        } else {
            null
        }
    },
)
