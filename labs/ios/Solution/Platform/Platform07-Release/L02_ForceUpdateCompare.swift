// L02：3.20 版的使用者被強制更新，3.9 版的使用者卻沒有（版本比較不能用字串）
// 對應複習站：Platform07Release 第 4 題「分階段發布（phased release 與 staged
// rollout）怎麼運作？出問題能不能回滾？」（強制更新）
//
// 情境：不能降版，出事只能靠強制更新：伺服器設定「最低支援版本」，App
// 啟動時比較自己的版本，低於最低版本就導向商店。
//       演練用 5 個情況測試 isUpdateRequired(current, minimum)，
// 每個情況都有「應該得到的結果」。
// Bug 版：版本是字串，直接用「小於」比較。字串是一個字元一個字元比的：「3.9.0」
// 和「3.10.0」比到第 3 個字元，'9' 大於 '1'，
//        所以 3.9.0 被當成比 3.10.0 新，該被強制更新的人沒被擋；「3.20.1」
// 比「3.9.0」，'2' 小於 '9'，
//        新版的使用者反而被要求更新。
// 修正版：用「.」切成數字，從主版號開始一段一段比數字大小；不一樣長的版本（3.
// 10 和 3.10.0）缺的段落當成 0。
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
        // 字串比較：一個字元一個字元比
        current < minimum
    }

    static func run(_ log: LabLog) async {
        scenario(log) { isUpdateRequired(current: $0, minimum: $1) }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func isUpdateRequired(current: String, minimum: String) -> Bool {
        let a = current.split(separator: ".").map { Int($0) ?? 0 }
        let b = minimum.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(a.count, b.count) {
            // 缺的段落當成 0
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            // 一段一段比數字大小
            if x != y { return x < y }
        }
        // 完全一樣：不用更新
        return false
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
            "選「Bug 版」執行，看 5 個情況哪幾個判斷錯了。",
            "想一想：字串 \"3.9.0\" 和 \"3.10.0\" 是一個字元一個字元比的，比到哪一個字元就分出大小？",
            "選「修正版」執行，5 個情況都要得到應該的結果。",
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
