// L01：香港和簡體區的使用者看到的是英文介面（語系標籤寫死 zh-CN、zh-TW，沒有降級鏈）
// 對應複習站：Exchange13Localization 第 1 題「四種語言（日文、簡體中文、繁體中文、英文）要怎麼標記與降級？」
//
// 情境：App 提供四種語言：ja、en、zh-Hans（簡體）、zh-Hant（繁體）。使用者的系統語言標籤五花八門：zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、
//       舊式的 zh-TW／zh-CN／zh-HK、ja-JP、en-US，甚至沒有支援的 fr-FR。簡體和繁體是「文字（script）」的差異，不是地區的差異。
//       resolve 要回傳 App 該用的語言；找不到時，依「完整標籤、去掉最後一段、繁簡推斷、預設語言 en」的順序降級。九個標籤：
//       ja-JP、en-US、zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、zh-TW、zh-CN、zh-HK、fr-FR。
// Bug 版：把 zh-CN 和 zh-TW 寫死成簡體和繁體，其他的標籤只比對「語言」（去掉第一個 - 後面的部分）。zh-Hant-TW 去掉後面剩 zh，
//        App 沒有 zh，退回英文；香港（zh-Hant-HK、zh-HK）和新式的 zh-Hans-CN 也都變成英文。
// 修正版：先看完整標籤是不是支援的；不是就從最後面開始去掉一段（zh-Hant-HK → zh-Hant）；中文沒有 script 時，用地區推斷
//        （TW、HK、MO 是繁體，其他是簡體）；還是找不到就用預設語言 en。
import Foundation
import LabKit

private let supported = ["ja", "en", "zh-Hans", "zh-Hant"]

/// （使用者的語言標籤, 正確的結果）
private let cases: [(String, String)] = [
    ("ja-JP", "ja"),
    ("en-US", "en"),
    ("zh-Hant-TW", "zh-Hant"),
    ("zh-Hant-HK", "zh-Hant"),
    ("zh-Hans-CN", "zh-Hans"),
    ("zh-TW", "zh-Hant"),
    ("zh-CN", "zh-Hans"),
    ("zh-HK", "zh-Hant"),
    ("fr-FR", "en"),
]

/// 演練流程（兩個版本共用）：九個標籤逐一決定語言，和正確答案比對
private func scenario(_ log: LabLog, resolve: (String, [String]) -> String) {
    var wrong = 0
    for (tag, expected) in cases {
        let result = resolve(tag, supported)
        if result == expected {
            log("\(tag) → \(result) ✓")
        } else {
            wrong += 1
            log("\(tag) → \(result) ✗（應該是 \(expected)）")
        }
    }
    log("共 9 個語言標籤，選錯語言的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L01Bug {
    static func resolve(_ requested: String, _ supported: [String]) -> String {
        if requested == "zh-CN" { return "zh-Hans" }                 // 寫死兩個舊式的標籤
        if requested == "zh-TW" { return "zh-Hant" }
        let language = String(requested.split(separator: "-").first ?? "")
        return supported.contains(language) ? language : "en"         // zh-Hant-TW 只剩 zh，不在 supported 裡，退回英文
    }

    static func run(_ log: LabLog) async {
        scenario(log, resolve: resolve(_:_:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func resolve(_ requested: String, _ supported: [String]) -> String {
        if supported.contains(requested) { return requested }                          // (1) 完整標籤
        let parts = requested.split(separator: "-").map(String.init)
        let language = parts.first ?? ""
        if language == "zh" {                                                          // (2) 中文：簡體和繁體是文字（script）的差異
            let script = parts.dropFirst().first { $0.count == 4 }                     // Hans、Hant
                ?? (parts.dropFirst().contains(where: { ["TW", "HK", "MO"].contains($0) }) ? "Hant" : "Hans")
            return "zh-\(script)"
        }
        return supported.contains(language) ? language : "en"                          // (3) 語言碼，(4) 預設語言
    }

    static func run(_ log: LabLog) async {
        scenario(log, resolve: resolve(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 9 個語言標籤") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange13Localization {
    static let l01 = Lab(
        id: "L01",
        title: "香港和簡體區的使用者看到英文介面",
        question: "第 1 題：四種語言（日文、簡體中文、繁體中文、英文）要怎麼標記與降級？",
        goal: "簡體和繁體是文字（script）的差異，用 zh-Hans 和 zh-Hant；語系標籤要有降級鏈（完整標籤、去掉最後一段、繁簡推斷、預設語言），不要只寫死 zh-CN、zh-TW。",
        steps: [
            "選「Bug 版」執行，看九個標籤哪些變成了英文：香港的使用者（zh-Hant-HK）看到什麼語言？",
            "想一想：zh-Hant-TW 去掉最後一段是 zh-Hant，再去掉一段才是 zh。App 的字串資源裡有 zh 嗎？",
            "選「修正版」執行，九個標籤都要選對（fr-FR 才退回預設語言 en）。",
            "進階：App 內選的語言和系統地區是兩件事；數字和日期的格式要用同一個 Locale，才不會同一個畫面一半是日文格式、一半是英文格式。",
        ],
        expectBug: "zh-Hant-TW、zh-Hant-HK、zh-Hans-CN、zh-HK 都變成 en ✗（舊式的 zh-TW、zh-CN 因為寫死所以剛好對）：「共 9 個語言標籤，選錯語言的有 4 個」。",
        expectFixed: "九個標籤都選對：zh-Hant-HK → zh-Hant、zh-HK → zh-Hant、zh-Hans-CN → zh-Hans，fr-FR → en：「共 9 個語言標籤，選錯語言的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, resolve:) 執行" }
            if !lines.contains("zh-TW → zh-Hant ✓") || !lines.contains("ja-JP → ja ✓") { return "Bug 版要能處理 ja-JP 與寫死的 zh-TW、zh-CN" }
            if !lines.contains("zh-Hant-HK → en ✗（應該是 zh-Hant）") { return "Bug 還沒重現：只比對語言碼時，zh-Hant-HK 會退回英文" }
            return n == 4 ? nil : "Bug 版應該有 4 個標籤選錯（目前 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, resolve:) 執行" }
            return n == 0 ? nil : "還有 \(n) 個標籤選錯：完整標籤 → 中文用 script（沒有就用地區推斷）→ 語言碼 → en"
        }
    )
}
