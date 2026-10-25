// L03：沒有設定指紋的手機、驗證失敗太多次的人，鎖定畫面直接放行（生物辨識失敗後的降級流程）
// 對應複習站：Exchange10AccountSecurity 第 3 題「生物辨識只看『成功』為什麼不夠安全？」
//        （生物辨識綁在金鑰上、不能只看布林值，Platform04 的演練 6 已經練過；這題練的是「失敗、不能用、金鑰失效」之後怎麼辦。）
//
// 情境：鎖定畫面要求生物辨識才能解鎖。系統會回報 7 種結果：成功、失敗（第 N 次）、使用者按了取消、被系統鎖定（嘗試太多次）、
//        金鑰失效（使用者新增了別人的指紋或臉部，KeyPermanentlyInvalidatedException）、這支手機不能用生物辨識（沒設定或被關閉）。
//        每一種結果都要決定下一步：放行、再試一次、維持鎖定、改用密碼加兩步驟驗證（2FA）、或要求密碼加 2FA 之後重新註冊金鑰。
//        規則：只有「成功」可以放行；失敗累積 3 次、被鎖定、不能用，都改用密碼加 2FA；金鑰失效要重新註冊，而且重新註冊本身也要密碼加 2FA。
//
// 你要寫：
//   ① Bug 版（L03Bug.decide）：Success → Unlock；Failed → Retry；UserCancelled → StayLocked；else 一律 Unlock。
//   ② 修正版（L03Fixed.decide）：Success → Unlock；Failed 累積 3 次（attempt >= 3）→ PasswordAnd2FA，沒到 3 次 → Retry；
//      UserCancelled → StayLocked；LockedOut、Unavailable → PasswordAnd2FA；KeyInvalidated → Reenroll。不寫 else。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange10-account-security/L03_BiometricFallback.kt
package tw.harry.labs.practice.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private sealed interface L03Bio {
    data object Success : L03Bio
    data class Failed(val attempt: Int) : L03Bio     // 這是第幾次失敗
    data object UserCancelled : L03Bio
    data object LockedOut : L03Bio                   // 嘗試太多次，系統鎖定生物辨識
    data object KeyInvalidated : L03Bio              // 使用者新增了指紋或臉部，綁定的金鑰失效
    data object Unavailable : L03Bio                 // 沒設定生物辨識，或被關閉
}

private enum class L03Decision(val text: String) {
    Unlock("放行"),
    Retry("再試一次"),
    StayLocked("維持鎖定"),
    PasswordAnd2FA("改用密碼加 2FA"),
    Reenroll("密碼加 2FA 後重新註冊"),
}

/** （描述, 系統回報的結果, 正確的決定） */
private val cases = listOf(
    Triple("辨識成功", L03Bio.Success, L03Decision.Unlock),
    Triple("第 1 次失敗", L03Bio.Failed(1), L03Decision.Retry),
    Triple("第 3 次失敗", L03Bio.Failed(3), L03Decision.PasswordAnd2FA),
    Triple("使用者按了取消", L03Bio.UserCancelled, L03Decision.StayLocked),
    Triple("被系統鎖定", L03Bio.LockedOut, L03Decision.PasswordAnd2FA),
    Triple("金鑰失效（新增了別人的指紋）", L03Bio.KeyInvalidated, L03Decision.Reenroll),
    Triple("這支手機沒有設定生物辨識", L03Bio.Unavailable, L03Decision.PasswordAnd2FA),
)

/** 演練流程（兩個版本共用）：7 種結果逐一決定下一步，和正確答案比對 */
private fun scenario(log: LabLog, decide: (L03Bio) -> L03Decision) {
    var wrong = 0
    var wrongUnlocks = 0
    for ((name, result, expected) in cases) {
        val decision = decide(result)
        if (decision == expected) {
            log("$name → ${decision.text} ✓")
        } else {
            wrong++
            if (decision == L03Decision.Unlock) wrongUnlocks++
            log("$name → ${decision.text} ✗（應該是${expected.text}）")
        }
    }
    log("7 種結果，判斷錯的有 $wrong 種，其中不該放行卻放行的有 $wrongUnlocks 種")
}

// MARK: - Bug 版
private object L03Bug {
    fun decide(result: L03Bio): L03Decision {
        // TODO ①：用 when (result)：Success → Unlock；is Failed → Retry；UserCancelled → StayLocked；else → Unlock（其他錯誤一律放行）
        return L03Decision.StayLocked
    }

    suspend fun run(log: LabLog) = scenario(log) { decide(it) }
}

// MARK: - 修正版
private object L03Fixed {
    fun decide(result: L03Bio): L03Decision {
        // TODO ②：用 when (result)（不寫 else）：Success → Unlock；is Failed → attempt >= 3 就 PasswordAnd2FA，否則 Retry；
        //        UserCancelled → StayLocked；LockedOut、Unavailable → PasswordAnd2FA；KeyInvalidated → Reenroll
        return L03Decision.StayLocked
    }

    suspend fun run(log: LabLog) = scenario(log) { decide(it) }
}

// MARK: - 演練設定
private fun counts(lines: List<String>): Pair<Int, Int>? {
    val line = lines.firstOrNull { it.startsWith("7 種結果") } ?: return null
    val numbers = Regex("\\d+").findAll(line).map { it.value.toInt() }.toList()
    return if (numbers.size >= 3) numbers[1] to numbers[2] else null
}

val l03 = Lab(
    id = "L03",
    title = "沒設定指紋的手機，鎖定畫面直接放行",
    question = "第 3 題：生物辨識只看「成功」為什麼不夠安全？",
    goal = "生物辨識失敗、不能用、金鑰失效時，只能改走密碼加 2FA 或重新註冊，不能放行；每一種結果都要明確對應一個決定，不要用「其他一律放行」。",
    steps = listOf(
        "打開 practice/exchange/exchange10-account-security/L03_BiometricFallback.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看哪幾種結果被錯誤放行了，哪一種該改用密碼卻讓他無限再試。",
        "想一想：「金鑰失效」代表什麼？如果這時候直接放行，或只用生物辨識就讓他重新註冊，攻擊者把自己的指紋加進借來的手機會怎樣？",
        "寫 TODO ②（修正版），選「修正版」執行，7 種結果的決定都要和規則一致，「不該放行卻放行」是 0。",
        "進階：BiometricPrompt 的回呼分 onAuthenticationFailed（沒認出來，可以再試）和 onAuthenticationError（錯誤碼，例如鎖定、沒有設定），不要混為一談。",
    ),
    expectBug = "「被系統鎖定 → 放行 ✗」「金鑰失效 → 放行 ✗」「這支手機沒有設定生物辨識 → 放行 ✗」，第 3 次失敗還在「再試一次 ✗」：「7 種結果，判斷錯的有 4 種，其中不該放行卻放行的有 3 種」。",
    expectFixed = "每一種都 ✓：被鎖定與沒設定 → 改用密碼加 2FA，金鑰失效 → 密碼加 2FA 後重新註冊，「7 種結果，判斷錯的有 0 種，其中不該放行卻放行的有 0 種」。",
    file = "exchange10-account-security/L03_BiometricFallback.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val c = counts(lines)
        when {
            c == null -> "演練沒有跑完：要用 scenario(log) { result -> … } 執行"
            "第 1 次失敗 → 再試一次 ✓" !in lines -> "Bug 版的失敗要「再試一次」（不限次數）"
            c.second != 3 -> "Bug 還沒重現：其他的錯誤（被鎖定、金鑰失效、沒設定）一律放行，應該有 3 種不該放行卻放行（目前 ${c.second} 種）"
            c.first != 4 -> "Bug 版應該判斷錯 4 種（3 種錯誤放行，加上第 3 次失敗還在再試），目前 ${c.first} 種"
            else -> null
        }
    },
    checkFixed = { lines ->
        val c = counts(lines)
        when {
            c == null -> "演練沒有跑完：要用 scenario(log) { result -> … } 執行"
            c.first != 0 -> "還有 ${c.first} 種判斷錯（其中 ${c.second} 種錯誤放行）：每一種結果都要明確對應規則，不要寫 else 放行"
            else -> null
        }
    },
)
