// L03：沒有設定 Face ID 的手機、驗證失敗太多次的人，鎖定畫面直接放行（生物辨識失敗後的降級流程）
// 對應複習站：Exchange10AccountSecurity 第 3 題「生物辨識只看『成功』為什麼不夠安全？」
//        （生物辨識綁在金鑰上、不能只看布林值，Platform04 的演練 6 已經練過；這題練的是「失敗、不能用、金鑰失效」之後怎麼辦。）
//
// 情境：鎖定畫面要求生物辨識才能解鎖。系統會回報 7 種結果：成功、失敗（第 N 次）、使用者按了取消、被系統鎖定（嘗試太多次）、
//       金鑰失效（使用者新增了別人的指紋或臉部）、這支手機不能用生物辨識（沒設定或被關閉）。
//       每一種結果都要決定下一步：放行、再試一次、維持鎖定、改用密碼加兩步驟驗證（2FA）、或要求密碼加 2FA 之後重新註冊金鑰。
//       規則：只有「成功」可以放行；失敗累積 3 次、被鎖定、不能用，都改用密碼加 2FA；金鑰失效要重新註冊，而且重新註冊本身也要密碼加 2FA。
//
// 你要寫：
//   ① Bug 版（L03Bug.decide）：success → unlock；failed → retry；userCancelled → stayLocked；其他（default）一律 unlock。
//   ② 修正版（L03Fixed.decide）：success → unlock；failed 累積 3 次（attempt >= 3）→ passwordAnd2FA，沒到 3 次 → retry；
//      userCancelled → stayLocked；lockedOut、unavailable → passwordAnd2FA；keyInvalidated → reenroll。不寫 default。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange10-AccountSecurity/L03_BiometricFallback.swift
import Foundation
import LabKit

private enum BioResult {
    case success
    case failed(attempt: Int)       // 這是第幾次失敗
    case userCancelled
    case lockedOut                  // 嘗試太多次，系統鎖定生物辨識
    case keyInvalidated             // 使用者新增了指紋或臉部，綁定的金鑰失效
    case unavailable                // 沒設定生物辨識，或被關閉
}

private enum Decision: String {
    case unlock = "放行"
    case retry = "再試一次"
    case stayLocked = "維持鎖定"
    case passwordAnd2FA = "改用密碼加 2FA"
    case reenroll = "密碼加 2FA 後重新註冊"
}

/// （描述, 系統回報的結果, 正確的決定）
private let cases: [(String, BioResult, Decision)] = [
    ("辨識成功", .success, .unlock),
    ("第 1 次失敗", .failed(attempt: 1), .retry),
    ("第 3 次失敗", .failed(attempt: 3), .passwordAnd2FA),
    ("使用者按了取消", .userCancelled, .stayLocked),
    ("被系統鎖定", .lockedOut, .passwordAnd2FA),
    ("金鑰失效（新增了別人的指紋）", .keyInvalidated, .reenroll),
    ("這支手機沒有設定生物辨識", .unavailable, .passwordAnd2FA),
]

/// 演練流程（兩個版本共用）：7 種結果逐一決定下一步，和正確答案比對
private func scenario(_ log: LabLog, decide: (BioResult) -> Decision) {
    var wrong = 0, wrongUnlocks = 0
    for (name, result, expected) in cases {
        let decision = decide(result)
        if decision == expected {
            log("\(name) → \(decision.rawValue) ✓")
        } else {
            wrong += 1
            if decision == .unlock { wrongUnlocks += 1 }
            log("\(name) → \(decision.rawValue) ✗（應該是\(expected.rawValue)）")
        }
    }
    log("7 種結果，判斷錯的有 \(wrong) 種，其中不該放行卻放行的有 \(wrongUnlocks) 種")
}

// MARK: - Bug 版
private enum L03Bug {
    fileprivate static func decide(_ result: BioResult) -> Decision {
        // TODO ①：用 switch result：.success → .unlock；.failed → .retry；.userCancelled → .stayLocked；default → .unlock（其他錯誤一律放行）
        return .stayLocked
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide(_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    fileprivate static func decide(_ result: BioResult) -> Decision {
        // TODO ②：用 switch result（不寫 default）：.success → .unlock；.failed(let attempt) → attempt >= 3 就 .passwordAnd2FA，否則 .retry；
        //        .userCancelled → .stayLocked；.lockedOut、.unavailable → .passwordAnd2FA；.keyInvalidated → .reenroll
        return .stayLocked
    }

    static func run(_ log: LabLog) async {
        scenario(log, decide: decide(_:))
    }
}

// MARK: - 演練設定
private func counts(_ lines: [String]) -> (wrong: Int, unlocks: Int)? {
    guard let line = lines.first(where: { $0.hasPrefix("7 種結果") }) else { return nil }
    let numbers = line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
    guard numbers.count >= 3 else { return nil }
    return (numbers[1], numbers[2])
}

extension Exchange10AccountSecurity {
    static let l03 = Lab(
        id: "L03",
        title: "沒設定生物辨識的手機，鎖定畫面直接放行",
        question: "第 3 題：生物辨識只看「成功」為什麼不夠安全？",
        goal: "生物辨識失敗、不能用、金鑰失效時，只能改走密碼加 2FA 或重新註冊，不能放行；每一種結果都要明確對應一個決定，不要用「其他一律放行」。",
        steps: [
            "打開 Practice/Exchange/Exchange10-AccountSecurity/L03_BiometricFallback.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看哪幾種結果被錯誤放行了，哪一種該改用密碼卻讓他無限再試。",
            "想一想：「金鑰失效」代表什麼？如果這時候直接放行，或只用生物辨識就讓他重新註冊，攻擊者把自己的指紋加進借來的手機會怎樣？",
            "寫 TODO ②（修正版），選「修正版」執行，7 種結果的決定都要和規則一致，「不該放行卻放行」是 0。",
            "進階：Android 的 BiometricPrompt 回呼分 onAuthenticationFailed（沒認出來，可以再試）和 onAuthenticationError（錯誤碼，例如鎖定、沒有設定），不要混為一談。",
        ],
        expectBug: "「被系統鎖定 → 放行 ✗」「金鑰失效 → 放行 ✗」「這支手機沒有設定生物辨識 → 放行 ✗」，第 3 次失敗還在「再試一次 ✗」：「7 種結果，判斷錯的有 4 種，其中不該放行卻放行的有 3 種」。",
        expectFixed: "每一種都 ✓：被鎖定與沒設定 → 改用密碼加 2FA，金鑰失效 → 密碼加 2FA 後重新註冊，「7 種結果，判斷錯的有 0 種，其中不該放行卻放行的有 0 種」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let c = counts(lines) else { return "演練沒有跑完：要用 scenario(log, decide:) 執行" }
            if !lines.contains("第 1 次失敗 → 再試一次 ✓") { return "Bug 版的失敗要「再試一次」（不限次數）" }
            if c.unlocks != 3 { return "Bug 還沒重現：其他的錯誤（被鎖定、金鑰失效、沒設定）一律放行，應該有 3 種不該放行卻放行（目前 \(c.unlocks) 種）" }
            return c.wrong == 4 ? nil : "Bug 版應該判斷錯 4 種（3 種錯誤放行，加上第 3 次失敗還在再試），目前 \(c.wrong) 種"
        },
        checkFixed: { lines in
            guard let c = counts(lines) else { return "演練沒有跑完：要用 scenario(log, decide:) 執行" }
            return c.wrong == 0 ? nil : "還有 \(c.wrong) 種判斷錯（其中 \(c.unlocks) 種錯誤放行）：每一種結果都要明確對應規則，不要寫 default 放行"
        }
    )
}
