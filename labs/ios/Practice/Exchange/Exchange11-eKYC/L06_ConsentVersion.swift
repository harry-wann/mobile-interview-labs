// L06：規約更新到 1.10 版，同意過 1.9
// 版的使用者卻沒有被要求重新同意（版本號用字串比較）
// 對應複習站：Exchange11eKYC 第 6 題「旅行規則、同意書、風險揭露，對 App
// 有什麼影響？」
//
// 情境：同意書要記錄「版本、時間、語系」。App 啟動時，
// 拿「使用者上次同意的版本」和「伺服器要求的版本」比較，
//       伺服器的版本比較新，就要擋住相關功能、要求重新同意。
// 複習站範例用日期當版本號；演練改用「主版本.次版本」（1.9、1.10、2.0），
//       因為這種格式最容易讓人直接拿字串比較。（版本格式是演練的自訂，
// 實際格式依產品與法務的規約。）
//       needsReconsent(accepted:required:) 回傳要不要重新同意。六種情況：
//       沒同意過／1.9 → 1.10／1.10 → 1.10（一樣）／2.0 → 10.0／1.10 → 1.
// 9（伺服器退回舊版）／1.2 → 1.3。
//
// 你要寫：
//   ① Bug 版（L06Bug.needsReconsent）：沒同意過（accepted 是 nil）回傳 true；
// 否則回傳 required > accepted（直接用字串比較大小）。
//   ② 修正版（L06Fixed.needsReconsent）：沒同意過回傳 true；否則用
// split(separator: ".") 把兩個版本拆成整數陣列，逐段比較，
//      required 比 accepted 新（第一個不同的段，required 的數字比較大）才回傳
// true；全部相同回傳 false。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange11-eKYC/L06_ConsentVersion.swift
import Foundation
import LabKit

/// （描述, 上次同意的版本, 伺服器要求的版本, 正確答案：要不要重新同意）
private let cases: [(String, String?, String, Bool)] = [
    ("沒同意過", nil, "1.0", true),
    ("同意過 1.9，伺服器要求 1.10", "1.9", "1.10", true),
    ("同意過 1.10，伺服器要求 1.10", "1.10", "1.10", false),
    ("同意過 2.0，伺服器要求 10.0", "2.0", "10.0", true),
    ("同意過 1.10，伺服器退回 1.9", "1.10", "1.9", false),
    ("同意過 1.2，伺服器要求 1.3", "1.2", "1.3", true),
]

/// 演練流程（兩個版本共用）：六種情況逐一判斷，和正確答案比對
private func scenario(
    _ log: LabLog,
    needsReconsent: (_ accepted: String?, _ required: String) -> Bool
) {
    var wrong = 0
    for (name, accepted, required, expected) in cases {
        let result = needsReconsent(accepted, required)
        let text = result ? "要重新同意" : "不用重新同意"
        if result == expected {
            log("\(name) → \(text) ✓")
        } else {
            wrong += 1
            log("\(name) → \(text) ✗（應該\(expected ? "要重新同意" : "不用重新同意")）")
        }
    }
    log("6 種情況，判斷錯的有 \(wrong) 種")
}

// MARK: - Bug 版
private enum L06Bug {
    fileprivate static func needsReconsent(
        accepted: String?, required: String
    ) -> Bool {
        // TODO ①：accepted 是 nil 回傳 true；否則回傳 required >
        // accepted（直接比較兩個字串）
        return false
    }

    static func run(_ log: LabLog) async {
        scenario(log, needsReconsent: needsReconsent(accepted:required:))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    fileprivate static func needsReconsent(
        accepted: String?, required: String
    ) -> Bool {
        // TODO ②：accepted 是 nil 回傳 true；否則把 accepted、required 各自
        // split(separator: ".") 並轉成 Int 陣列，
        //        逐段比較（段數不同時，缺的段當 0）：第一個不同的段，required
        // 的數字比較大就回傳 true、比較小回傳 false；全部相同回傳 false
        return false
    }

    static func run(_ log: LabLog) async {
        scenario(log, needsReconsent: needsReconsent(accepted:required:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("6 種情況") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange11eKYC {
    static let l06 = Lab(
        id: "L06",
        title: "規約更新到 1.10，同意過 1.9 的人沒被要求重新同意",
        question: "第 6 題：旅行規則、同意書、風險揭露，對 App 有什麼影響？",
        goal: "版本號要拆開成數字再比較，不能直接比較字串。判斷錯了，使用者在沒有同意新版規約的情況下繼續使用，這是合規問題。",
        steps: [
            "打開 Practice/Exchange/Exchange11-eKYC/L06_ConsentVersion.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看哪幾種情況判斷錯了：1.9 → 1.10、2.0 → 10.0、1.10 → 1.9。",
            "想一想：字串 \"1.10\" 和 \"1.9\" 比大小，是比哪一個字元決定的？",
            "寫 TODO ②（修正版），選「修正版」執行，六種情況都要判斷對。",
            "進階：同意要記錄版本、時間、語系；日文版規約通常是正本，其他語言版本的法律地位依公司規約（未證實）。啟動時要檢查，同意之前要擋住相關功能。",
        ],
        expectBug:
            "「同意過 1.9，伺服器要求 1.10 → 不用重新同意 ✗」「同意過 2.0，伺服器要求 10.0 → 不用重新同意 ✗」「同意過 1.10，伺服器退回 1.9 → 要重新同意 ✗」：「6 種情況，判斷錯的有 3 種」。",
        expectFixed: "六種情況都 ✓，「6 種情況，判斷錯的有 0 種」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, needsReconsent:) 執行"
            }
            if !lines.contains("同意過 1.2，伺服器要求 1.3 → 要重新同意 ✓") {
                return "Bug 版在版本長度一樣時要算對：字串比較 \"1.3\" > \"1.2\" 是 true"
            }
            return n == 3
                ? nil
                : "Bug 還沒重現：用字串比較 required > accepted，會有 3 種情況判斷錯（目前 \(n) 種）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, needsReconsent:) 執行"
            }
            return n == 0 ? nil : "還有 \(n) 種情況判斷錯：用 \".\" 拆開、轉成整數，逐段比較數字"
        }
    )
}
