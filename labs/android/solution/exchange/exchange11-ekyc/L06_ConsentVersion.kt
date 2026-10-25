// L06：規約更新到 1.10 版，同意過 1.9 版的使用者卻沒有被要求重新同意（版本號用字串比較）
// 對應複習站：Exchange11eKYC 第 6 題「旅行規則、同意書、風險揭露，對 App 有什麼影響？」
//
// 情境：同意書要記錄「版本、時間、語系」。App 啟動時，拿「使用者上次同意的版本」和「伺服器要求的版本」比較，
//        伺服器的版本比較新，就要擋住相關功能、要求重新同意。複習站範例用日期當版本號；演練改用「主版本.次版本」（1.9、1.10、2.0），
//        因為這種格式最容易讓人直接拿字串比較。（版本格式是演練的自訂，實際格式依產品與法務的規約。）
//        needsReconsent(accepted, required) 回傳要不要重新同意。六種情況：
//        沒同意過／1.9 → 1.10／1.10 → 1.10（一樣）／2.0 → 10.0／1.10 → 1.9（伺服器退回舊版）／1.2 → 1.3。
// Bug 版：用字串比較 required > accepted。字串是逐字元比較：「1.10」和「1.9」比到第三個字元，"1" 小於 "9"，所以 "1.10" 被當成比 "1.9" 小，
//        使用者沒有被要求重新同意；反過來，伺服器退回舊版 1.9 時，卻被當成比較新。
// 修正版：把版本號用 "." 拆開，轉成整數，逐段比較數字。
package tw.harry.labs.solution.exchange11ekyc

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L06Case(val name: String, val accepted: String?, val required: String, val expected: Boolean)

private val cases = listOf(
    L06Case("沒同意過", null, "1.0", true),
    L06Case("同意過 1.9，伺服器要求 1.10", "1.9", "1.10", true),
    L06Case("同意過 1.10，伺服器要求 1.10", "1.10", "1.10", false),
    L06Case("同意過 2.0，伺服器要求 10.0", "2.0", "10.0", true),
    L06Case("同意過 1.10，伺服器退回 1.9", "1.10", "1.9", false),
    L06Case("同意過 1.2，伺服器要求 1.3", "1.2", "1.3", true),
)

/** 演練流程（兩個版本共用）：六種情況逐一判斷，和正確答案比對 */
private fun scenario(log: LabLog, needsReconsent: (accepted: String?, required: String) -> Boolean) {
    var wrong = 0
    for (c in cases) {
        val result = needsReconsent(c.accepted, c.required)
        val text = if (result) "要重新同意" else "不用重新同意"
        if (result == c.expected) {
            log("${c.name} → $text ✓")
        } else {
            wrong++
            log("${c.name} → $text ✗（應該${if (c.expected) "要重新同意" else "不用重新同意"}）")
        }
    }
    log("6 種情況，判斷錯的有 $wrong 種")
}

// MARK: - Bug 版
private object L06Bug {
    fun needsReconsent(accepted: String?, required: String): Boolean {
        if (accepted == null) return true
        return required > accepted                      // 字串比較：逐字元比，"1.10" < "1.9"
    }

    suspend fun run(log: LabLog) = scenario(log) { accepted, required -> needsReconsent(accepted, required) }
}

// MARK: - 修正版
private object L06Fixed {
    fun needsReconsent(accepted: String?, required: String): Boolean {
        if (accepted == null) return true
        val a = accepted.split(".").map { it.toIntOrNull() ?: 0 }
        val r = required.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, r.size)) {                    // 逐段比較「數字」
            val x = a.getOrElse(i) { 0 }
            val y = r.getOrElse(i) { 0 }
            if (x != y) return y > x                                  // 第一個不同的段決定誰比較新
        }
        return false
    }

    suspend fun run(log: LabLog) = scenario(log) { accepted, required -> needsReconsent(accepted, required) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("6 種情況") }?.let { Regex("(\\d+) 種$").find(it)?.groupValues?.get(1)?.toInt() }

val l06 = Lab(
    id = "L06",
    title = "規約更新到 1.10，同意過 1.9 的人沒被要求重新同意",
    question = "第 6 題：旅行規則、同意書、風險揭露，對 App 有什麼影響？",
    goal = "版本號要拆開成數字再比較，不能直接比較字串。判斷錯了，使用者在沒有同意新版規約的情況下繼續使用，這是合規問題。",
    steps = listOf(
        "選「Bug 版」執行，看哪幾種情況判斷錯了：1.9 → 1.10、2.0 → 10.0、1.10 → 1.9。",
        "想一想：字串 \"1.10\" 和 \"1.9\" 比大小，是比哪一個字元決定的？",
        "選「修正版」執行，六種情況都要判斷對。",
        "進階：同意要記錄版本、時間、語系；日文版規約通常是正本，其他語言版本的法律地位依公司規約（未證實）。啟動時要檢查，同意之前要擋住相關功能。",
    ),
    expectBug = "「同意過 1.9，伺服器要求 1.10 → 不用重新同意 ✗」「同意過 2.0，伺服器要求 10.0 → 不用重新同意 ✗」「同意過 1.10，伺服器退回 1.9 → 要重新同意 ✗」：「6 種情況，判斷錯的有 3 種」。",
    expectFixed = "六種情況都 ✓，「6 種情況，判斷錯的有 0 種」。",
    file = "exchange11-ekyc/L06_ConsentVersion.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { accepted, required -> … } 執行"
            "同意過 1.2，伺服器要求 1.3 → 要重新同意 ✓" !in lines -> "Bug 版在版本長度一樣時要算對：字串比較 \"1.3\" > \"1.2\" 是 true"
            n != 3 -> "Bug 還沒重現：用字串比較 required > accepted，會有 3 種情況判斷錯（目前 $n 種）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { accepted, required -> … } 執行"
            n != 0 -> "還有 $n 種情況判斷錯：用 \".\" 拆開、轉成整數，逐段比較數字"
            else -> null
        }
    },
)
