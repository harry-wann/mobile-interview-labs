// L01：登入的 E2E 測試偶爾失敗，而且只發生在 30 秒的最後幾秒（驗證碼先算好，送出時已經換新的了）
// 對應複習站：Testing04E2E 第 2 題「交易所 App 的測試環境要注意什麼？」
//
// 情境：測試帳號用固定的 TOTP 密鑰，測試程式自己用同樣的算法（T04Totp.code，RFC 6238）算出驗證碼。
//       登入流程：輸入帳號密碼（2.5 秒）→ 等兩步驟驗證畫面出現（1.5 秒）→ 送出驗證碼（0.5 秒送到伺服器）。
//       伺服器很嚴格，只接受「現在這個 30 秒」的驗證碼。時間用假的時鐘推進，不會真的等。
//       演練把同一個登入測試跑 4 次，分別從某個 30 秒區間的第 3、12、22、27.5 秒開始。
// Bug 版：一開始就算好驗證碼，才去做後面的步驟。從第 27.5 秒開始的那次，送出時已經是下一個 30 秒，被伺服器拒絕。
//        其他三次都通過，所以這個測試「大約每 30 次有 4 次」失敗，很難重現。
// 修正版：等兩步驟驗證畫面出現後，才算驗證碼；如果這個 30 秒只剩不到 5 秒，先等到下一個區間再算（送出還要 0.5 秒）。
//        4 次都通過。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：同一個登入測試從不同的時間點開始跑 4 次
private func scenario(_ log: LabLog, login: @escaping (T04FakeLoginUI, T04Clock) async -> Void) async {
    log("TOTP 算法對照 RFC 6238 的測試向量：\(T04Totp.selfCheck() ? "正確" : "不正確")")
    let windowStart: TimeInterval = 1_700_000_010                    // 某個 30 秒區間的起點（30 的倍數）
    let suite = T04Suite()
    for (label, offset) in [("第 3 秒", 3.0), ("第 12 秒", 12.0), ("第 22 秒", 22.0), ("第 27.5 秒", 27.5)] {
        suite.test("從 30 秒區間的\(label)開始登入") { t in
            let clock = T04Clock(start: windowStart + offset)
            let ui = T04FakeLoginUI(clock: clock, server: T04FakeAuthServer(clock: clock))
            await login(ui, clock)
            t.isTrue(ui.loggedIn, "登入失敗：\(ui.errorMessage.isEmpty ? "沒有送出驗證碼" : ui.errorMessage)")
        }
    }
    await suite.run(label: "登入測試", log: log)
}

// MARK: - Bug 版
private enum L01Bug {
    static func login(_ ui: T04FakeLoginUI, _ clock: T04Clock) async {
        let code = T04Totp.code(at: clock.now)                       // 一開始就算好驗證碼
        await ui.typeCredentials()
        await ui.waitForTwoFactorScreen()
        await ui.submitCode(code)                                    // 送出時，這個驗證碼可能已經過期
    }

    static func run(_ log: LabLog) async {
        await scenario(log, login: login)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func login(_ ui: T04FakeLoginUI, _ clock: T04Clock) async {
        await ui.typeCredentials()
        await ui.waitForTwoFactorScreen()
        let left = T04Totp.secondsLeft(at: clock.now)
        if left < 5 { clock.advance(left + 0.1) }                    // 這個 30 秒快結束了：等到下一個區間（真實測試裡是 sleep）
        await ui.submitCode(T04Totp.code(at: clock.now))             // 在要用之前才算驗證碼
    }

    static func run(_ log: LabLog) async {
        await scenario(log, login: login)
    }
}

// MARK: - 演練設定
extension Testing04E2E {
    static let l01 = Lab(
        id: "L01",
        title: "登入測試偶爾失敗，只發生在 30 秒的尾巴",
        question: "第 2 題：交易所 App 的測試環境要注意什麼？",
        goal: "測試帳號用固定的 TOTP 密鑰時，驗證碼要在送出前才算，並避開 30 秒區間的邊界。",
        steps: [
            "選「Bug 版」執行，看 4 次登入測試哪幾次失敗，以及失敗的訊息。",
            "想一想：失敗的那次是從區間的第幾秒開始的？從第 22 秒開始的那次為什麼通過？",
            "選「修正版」執行：4 次都要通過。從第 22 秒開始的那次，流程會多等一下。",
        ],
        expectBug: "從第 3、12、22 秒開始的 3 次通過，從第 27.5 秒開始的那次失敗：登入失敗：驗證碼過期或錯誤。",
        expectFixed: "4 次都通過；第一行印出「TOTP 算法對照 RFC 6238 的測試向量：正確」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("TOTP 算法對照 RFC 6238 的測試向量：正確") else { return "TOTP 算法和 RFC 6238 的測試向量不一致" }
            guard let s = T04Summary.parse(lines, label: "登入測試") else { return "找不到摘要：要用 scenario 跑" }
            if s.cases != 4 { return "只跑了 \(s.cases) 個測試" }
            if s.passed == 0 { return "4 次全部失敗：登入要做完整：typeCredentials、waitForTwoFactorScreen、submitCode(code)" }
            return s.failed > 0 ? nil : "4 次都通過：Bug 還沒重現（要一開始就用 T04Totp.code(at: clock.now) 算好驗證碼，再做後面的步驟）"
        },
        checkFixed: { lines in
            guard let s = T04Summary.parse(lines, label: "登入測試") else { return "找不到摘要：要用 scenario 跑" }
            if s.cases != 4 { return "只跑了 \(s.cases) 個測試" }
            return s.failed == 0 ? nil : "還有 \(s.failed) 次失敗：要等 waitForTwoFactorScreen 之後才算驗證碼；secondsLeft 小於 5 時，先 clock.advance 到下一個區間"
        }
    )
}
