// L01：登入的 E2E 測試偶爾失敗，而且只發生在 30 秒的最後幾秒（驗證碼先算好，送出時已經換新的了）
// 對應複習站：Testing04E2E 第 2 題「交易所 App 的測試環境要注意什麼？」
//
// 情境：測試帳號用固定的 TOTP 密鑰，測試程式自己用同樣的算法（T04Totp.code，RFC 6238）算出驗證碼。
//       登入流程：輸入帳號密碼（2.5 秒）→ 等兩步驟驗證畫面出現（1.5 秒）→ 送出驗證碼（0.5 秒送到伺服器）。
//       伺服器很嚴格，只接受「現在這個 30 秒」的驗證碼。時間用假的時鐘推進，不會真的等。
//       演練把同一個登入測試跑 4 次，分別從某個 30 秒區間的第 3、12、22、27.5 秒開始。
//
// 你要寫：
//   ① Bug 版：login 一開始就用 T04Totp.code(clock.now) 算好驗證碼，再依序 typeCredentials()、waitForTwoFactorScreen()、submitCode(code)。
//   ② 修正版：login 先 typeCredentials()、waitForTwoFactorScreen()；再用 T04Totp.secondsLeft(clock.now) 看這個 30 秒還剩幾秒，
//      少於 5 秒就 clock.advance(剩下的秒數 + 0.1) 等到下一個區間（真實測試裡是 delay 或 sleep）；最後才算驗證碼並 submitCode。
// 只改有 TODO 的地方。解答：solution/testing/testing04-e2e/L01_TotpBoundary.kt
package tw.harry.labs.practice.testing04e2e

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：同一個登入測試從不同的時間點開始跑 4 次 */
private suspend fun scenario(log: LabLog, login: suspend (T04FakeLoginUI, T04Clock) -> Unit) {
    log("TOTP 算法對照 RFC 6238 的測試向量：${if (T04Totp.selfCheck()) "正確" else "不正確"}")
    val windowStart = 1_700_000_010.0                              // 某個 30 秒區間的起點（30 的倍數）
    val suite = T04Suite()
    for ((label, offset) in listOf("第 3 秒" to 3.0, "第 12 秒" to 12.0, "第 22 秒" to 22.0, "第 27.5 秒" to 27.5)) {
        suite.test("從 30 秒區間的${label}開始登入") { t ->
            val clock = T04Clock(windowStart + offset)
            val ui = T04FakeLoginUI(clock, T04FakeAuthServer(clock))
            login(ui, clock)
            t.isTrue(ui.loggedIn, "登入失敗：${ui.errorMessage.ifEmpty { "沒有送出驗證碼" }}")
        }
    }
    suite.run("登入測試", log)
}

// MARK: - Bug 版
private object L01Bug {
    suspend fun login(ui: T04FakeLoginUI, clock: T04Clock) {
        // TODO ①：一開始就用 T04Totp.code(clock.now) 算好驗證碼，再依序 typeCredentials()、waitForTwoFactorScreen()、submitCode(code)
    }

    suspend fun run(log: LabLog) = scenario(log, ::login)
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun login(ui: T04FakeLoginUI, clock: T04Clock) {
        // TODO ②-1：先 typeCredentials()、waitForTwoFactorScreen()
        // TODO ②-2：用 T04Totp.secondsLeft(clock.now) 看這個 30 秒還剩幾秒；少於 5 秒就 clock.advance(剩下的秒數 + 0.1) 等到下一個區間
        // TODO ②-3：最後才用 T04Totp.code(clock.now) 算驗證碼，submitCode
    }

    suspend fun run(log: LabLog) = scenario(log, ::login)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "登入測試偶爾失敗，只發生在 30 秒的尾巴",
    question = "第 2 題：交易所 App 的測試環境要注意什麼？",
    goal = "測試帳號用固定的 TOTP 密鑰時，驗證碼要在送出前才算，並避開 30 秒區間的邊界。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看 4 次登入測試哪幾次失敗，以及失敗的訊息。",
        "想一想：失敗的那次是從區間的第幾秒開始的？從第 22 秒開始的那次為什麼通過？",
        "寫 TODO ②，選「修正版」執行：4 次都要通過。從第 22 秒開始的那次，流程會多等一下。",
    ),
    expectBug = "從第 3、12、22 秒開始的 3 次通過，從第 27.5 秒開始的那次失敗：登入失敗：驗證碼過期或錯誤。",
    expectFixed = "4 次都通過；第一行印出「TOTP 算法對照 RFC 6238 的測試向量：正確」。",
    file = "testing04-e2e/L01_TotpBoundary.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val s = T04Summary.parse(lines, "登入測試")
        when {
            "TOTP 算法對照 RFC 6238 的測試向量：正確" !in lines -> "TOTP 算法和 RFC 6238 的測試向量不一致"
            s == null -> "找不到摘要：要用 scenario 跑"
            s.cases != 4 -> "只跑了 ${s.cases} 個測試"
            s.passed == 0 -> "4 次全部失敗：登入要做完整：typeCredentials、waitForTwoFactorScreen、submitCode(code)"
            s.failed == 0 -> "4 次都通過：Bug 還沒重現（要一開始就用 T04Totp.code(clock.now) 算好驗證碼，再做後面的步驟）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val s = T04Summary.parse(lines, "登入測試")
        when {
            s == null -> "找不到摘要：要用 scenario 跑"
            s.cases != 4 -> "只跑了 ${s.cases} 個測試"
            s.failed > 0 -> "還有 ${s.failed} 次失敗：要等 waitForTwoFactorScreen 之後才算驗證碼；secondsLeft 小於 5 時，先 clock.advance 到下一個區間"
            else -> null
        }
    },
)
