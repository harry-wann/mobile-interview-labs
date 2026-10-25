// L02：3.20 版的使用者被強制更新，3.9 版的使用者卻沒有（版本比較不能用字串）
// 對應複習站：Platform07Release 第 4 題「分階段發布（phased release 與 staged
// rollout）怎麼運作？出問題能不能回滾？」（強制更新）
//
// 情境：不能降版，出事只能靠強制更新：伺服器設定「最低支援版本」，App
// 啟動時比較自己的版本，低於最低版本就導向商店。
//       演練用 5 個情況測試 isUpdateRequired(current, minimum)，
// 每個情況都有「應該得到的結果」。
//
// 你要寫：
//   ① Bug 版：isUpdateRequired 直接用字串比較 current < minimum。
//   ② 修正版：兩個版本都用「.」切成整數陣列，
// 從第一段開始一段一段比數字大小（不一樣長時缺的段落當成 0）；current 比
// minimum 小才回傳 true，完全一樣回傳 false。
// 只改有 TODO 的地方。解答：
// Solution/Platform/Platform07-Release/L02_ForceUpdateCompare.swift
import Foundation
import LabKit

/// 測試的情況（兩個版本共用）：目前的版本、最低支援版本、是否應該強制更新
private let cases: [(current: String, minimum: String, expected: Bool)] = [
    ("3.9.0", "3.10.0", true),
    ("3.10.0", "3.10.0", false),
    ("3.20.1", "3.9.0", false),
    ("3.10", "3.10.0", false),
    ("2.99.9", "3.0.0", true),
]

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, isUpdateRequired: (String, String) -> Bool)
{
    var wrong = 0
    for item in cases {
        let result = isUpdateRequired(item.current, item.minimum)
        if result == item.expected {
            log(
                "✓ 目前 \(item.current)，最低 \(item.minimum) → \(result ? "強制更新" : "不用更新")"
            )
        } else {
            wrong += 1
            log(
                "✗ 目前 \(item.current)，最低 \(item.minimum) → \(result ? "強制更新" : "不用更新")（應該是\(item.expected ? "強制更新" : "不用更新")）"
            )
        }
    }
    log("共 \(cases.count) 個情況，判斷錯誤 \(wrong) 個")
}

// MARK: - Bug 版
private enum L02Bug {
    static func isUpdateRequired(current: String, minimum: String) -> Bool {
        // TODO ①：直接用字串比較：current < minimum
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log) { isUpdateRequired(current: $0, minimum: $1) }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func isUpdateRequired(current: String, minimum: String) -> Bool {
        // TODO ②：1. current、minimum 各自用 "." 切成 [Int]（轉不成數字當 0）
        //        2. 從 0 到兩者較長的長度，一段一段取出數字（缺的段落當 0）：
        // 不一樣就回傳「current 這一段 < minimum 這一段」
        //        3. 每一段都一樣：回傳 false
        false
    }

    static func run(_ log: LabLog) async {
        scenario(log) { isUpdateRequired(current: $0, minimum: $1) }
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard
        let line = lines.first(where: {
            $0.hasPrefix("共 ") && $0.contains("判斷錯誤")
        })
    else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Platform07Release {
    static let l02 = Lab(
        id: "L02",
        title: "3.20 版的使用者被強制更新，3.9 版卻沒有",
        question:
            "第 4 題：分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？（強制更新）",
        goal: "App 啟動時比較自己的版本和伺服器設定的最低支援版本，低的才強制更新。版本要怎麼比才對？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 5 個情況哪幾個判斷錯了，出現 ✓。",
            "想一想：字串 \"3.9.0\" 和 \"3.10.0\" 是一個字元一個字元比的，比到哪一個字元就分出大小？",
            "寫 TODO ②，選「修正版」執行，5 個情況都要得到應該的結果。",
            "進階：也可以用 current.compare(minimum, options: .numeric) 比較（Foundation 內建），它也是一段一段比數字。",
        ],
        expectBug:
            "「共 5 個情況，判斷錯誤 3 個」：3.9.0 對最低 3.10.0 沒被擋；3.20.1 對最低 3.9.0、3.10 對最低 3.10.0 被誤擋。",
        expectFixed: "「共 5 個情況，判斷錯誤 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, isUpdateRequired:) 執行"
            }
            return n == 3
                ? nil
                : "Bug 還沒重現：直接用字串比較 current < minimum，應該判斷錯 3 個（目前 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, isUpdateRequired:) 執行"
            }
            if n == 0 { return nil }
            return
                "還有 \(n) 個情況判斷錯誤，例如：\(lines.first(where: { $0.hasPrefix("✗") }) ?? "")"
        }
    )
}
