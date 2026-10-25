// L02：使用者剛好在換碼的瞬間輸入就被拒絕，而同一個碼卻可以被重複使用（TOTP
// 的驗證視窗與重放）
// 對應複習站：Exchange10AccountSecurity 第 2 題「伺服器驗證 TOTP
// 時要注意什麼？」
//
// 情境：伺服器收到使用者輸入的 6 位數驗證碼，要判斷對不對。
// 手機和伺服器的時鐘有一點誤差，使用者輸入也要幾秒，
//        所以通常除了「現在這個 30 秒」，也接受前一個和後一個（視窗 ±1，共 3
// 個碼）；而且同一個 counter 的碼只能用一次，否則被旁人看到的碼可以重放。
//        演練用 4 個情境（每個情境各自一台全新的伺服器，秘密是 RFC 的測試資料；
// 第 59 秒的碼是 287082，第 100 秒的碼是 969429）：
//        ① 使用者在第 59 秒看到 287082，第 61 秒才送出（跨過了 30 秒的邊界）→
// 應該受理
//        ② 同一個碼 287082 在第 40 秒送出被受理，第 55 秒（還在同一個 30 秒裡）
// 又被送出一次（旁人重放）→ 第 2 次應該拒絕
//        ③ 第 59 秒的碼 287082，到第 119 秒才送出（已經差了 2 個 time step）→
// 應該拒絕
//        ④ 使用者手機的時鐘快了 30 秒：第 61 秒收到第 100 秒的碼 969429 →
// 應該受理
//
// 你要寫：
//   ① Bug 版（L02Bug.makeVerifier）：回傳的函式只比對現在這個 30 秒（counter =
// now / 30）的碼，沒有視窗，也不記住用過的 counter。
//   ② 修正版（L02Fixed.makeVerifier）：用 var lastUsed 記住上次成功的 counter；
// 比對 current - 1、current、current + 1 三個 counter，
//      counter 小於等於 lastUsed 的跳過；比對成功就更新 lastUsed 並回傳 true。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange10-account-security/L02_TotpWindow.kt
package tw.harry.labs.practice.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 一台伺服器的驗證函式：給驗證碼與伺服器現在的 Unix 秒，回傳受理（true）
 * 或拒絕（false）。每次建立都是全新的狀態。
 */
private typealias Verifier = (code: String, now: Long) -> Boolean

/** 演練流程（兩個版本共用）：4 個情境，每個情境各自一台全新的伺服器 */
private fun scenario(log: LabLog, makeVerifier: () -> Verifier) {
    var wrong = 0
    fun check(name: String, expected: Boolean, actual: Boolean, text: String) {
        val answer = if (actual) "受理" else "拒絕"
        if (actual == expected) {
            log("$name：$text → $answer ✓")
        } else {
            wrong++
            log("$name：$text → $answer ✗（應該${if (expected) "受理" else "拒絕"}）")
        }
    }
    var v = makeVerifier()
    check("情境 1（跨過邊界）", true, v("287082", 61), "第 61 秒送出第 59 秒的碼 287082")

    v = makeVerifier()
    check("情境 2（重放）：第 1 次", true, v("287082", 40), "第 40 秒送出 287082")
    check("情境 2（重放）：第 2 次", false, v("287082", 55), "第 55 秒又送出同一個碼 287082")

    v = makeVerifier()
    check("情境 3（太舊）", false, v("287082", 119), "第 119 秒送出第 59 秒的碼 287082")

    v = makeVerifier()
    check("情境 4（手機時鐘快）", true, v("969429", 61), "第 61 秒送出第 100 秒的碼 969429")
    log("4 個情境，判斷錯的有 $wrong 個")
}

// MARK: - Bug 版
private object L02Bug {
    fun makeVerifier(): Verifier {
        // TODO ①：回傳 { code, now -> … }：只算 counter = now / 30 的碼（E10.
        // hotp(E10.rfcSecret, …)），和 code 比較
        return { _, _ -> false }
    }

    suspend fun run(log: LabLog) = scenario(log) { makeVerifier() }
}

// MARK: - 修正版
private object L02Fixed {
    fun makeVerifier(): Verifier {
        // TODO ②：var lastUsed: Long? 記住上次成功的 counter。回傳 { code, now
        // -> … }：
        //        current = now / 30；對 current - 1、current、current + 1 三個
        // counter 逐一檢查：
        //        counter <= lastUsed 的跳過；E10.hotp(E10.rfcSecret, counter)
        // 等於 code 就把 lastUsed 設成 counter，回傳 true；都不對回傳 false
        return { _, _ -> false }
    }

    suspend fun run(log: LabLog) = scenario(log) { makeVerifier() }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("4 個情境")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l02 = Lab(
    id = "L02",
    title = "換碼瞬間輸入被拒絕，舊碼卻能重複使用",
    question = "第 2 題：伺服器驗證 TOTP 時要注意什麼？",
    goal = "伺服器驗證要容許前後各 1 個 time step（視窗 ±1，但不能更大），而且同一個 counter 的碼只能成功一次；兩件事都要做，只做一件都不夠。",
    steps = listOf(
        "打開 practice/exchange/exchange10-account-security/L02_TotpWindow.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看 4 個情境哪些判斷錯了：邊界、重放、時鐘快的使用者。",
        "想一想：為什麼不乾脆把視窗放大到 ±10，減少客訴？6 位數只有 100 萬種可能，21 個碼同時有效，亂猜的成功率是多少？",
        "寫 TODO ②（修正版），選「修正版」執行，4 個情境都要判斷對（情境 3 要拒絕：視窗不能太大）。",
        "進階：還要限制嘗試次數、用固定時間比較碼；時鐘誤差大的使用者應該去校正手機時間。",
    ),
    expectBug = "情境 1 拒絕 ✗、情境 2 的第 2 次受理 ✗（重放成功）、情境 4 拒絕 ✗，情境 3 剛好拒絕 ✓：「4 個情境，判斷錯的有 3 個」。",
    expectFixed = "情境 1 受理、情境 2 第 1 次受理第 2 次拒絕、情境 3 拒絕、情境 4 受理，「4 個情境，判斷錯的有 0 個」。",
    file = "exchange10-account-security/L02_TotpWindow.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log) { makeVerifier() } 執行"
            }

            "情境 2（重放）：第 1 次：第 40 秒送出 287082 → 受理 ✓" !in lines -> {
                "Bug 版也要能驗證：現在這個 30 秒的碼要受理（第 40 秒送出 287082）"
            }

            "情境 2（重放）：第 2 次：第 55 秒又送出同一個碼 287082 → 受理 ✗（應該拒絕）" !in lines -> {
                "Bug 還沒重現：沒有記住用過的 counter，同一個碼第 2 次也會被受理"
            }

            "情境 1（跨過邊界）：第 61 秒送出第 59 秒的碼 287082 → 拒絕 ✗（應該受理）" !in lines -> {
                "Bug 還沒重現：只比對現在這個 30 秒，跨過邊界的碼會被拒絕"
            }

            n != 3 -> {
                "Bug 版應該判斷錯 3 個情境（目前 $n 個）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { makeVerifier() } 執行"
            n != 0 -> "還有 $n 個情境判斷錯：比對前一個、現在、後一個三個 counter，而且 counter 要比 lastUsed 大才算數"
            else -> null
        }
    },
)
