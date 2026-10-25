// L05：跨年那一天的交易紀錄顯示成明年，晚上 11 點顯示成 11:59（日期格式的 YYYY 與 hh）
// 對應複習站：Exchange13Localization 第 4 題「日期與時區怎麼處理？JST 是什麼？」
//        （語言、曆法、時區不固定造成的問題，在 Exchange09 的演練 4 練過；這題格式的 locale、曆法、時區都已經固定，只看日期格式字串本身。）
//
// 情境：交易紀錄的時間標籤要用固定、沒有歧義的格式：「2026-10-23 14:05:09 JST」（24 小時制，加上 JST 後綴）。formatter 的語言固定 en_US_POSIX、西曆、時區 Asia/Tokyo，
//       只剩日期格式字串要寫。演練有三筆 JST 的時間：2024-12-31 23:59:59、2025-01-01 00:00:05、2026-10-24 13:05:09。
//
// 你要寫：
//   ① Bug 版（L05Bug.pattern）：回傳 "YYYY-MM-dd hh:mm:ss"。
//   ② 修正版（L05Fixed.pattern）：回傳 "yyyy-MM-dd HH:mm:ss"。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange13-Localization/L05_WeekYear.swift
import Foundation
import LabKit

/// （描述, 時間點, 正確的標籤）
private let cases: [(String, String, String)] = [
    ("跨年前一秒", "2024-12-31T23:59:59+09:00", "2024-12-31 23:59:59 JST"),
    ("新年第一天 0 點", "2025-01-01T00:00:05+09:00", "2025-01-01 00:00:05 JST"),
    ("一般的下午", "2026-10-24T13:05:09+09:00", "2026-10-24 13:05:09 JST"),
]

/// 語言、曆法、時區都固定好的 formatter（兩個版本共用），只有日期格式字串由 pattern 決定
private func label(_ date: Date, pattern: String) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(identifier: "Asia/Tokyo")!
    formatter.dateFormat = pattern
    return formatter.string(from: date) + " JST"                    // 時區縮寫不依賴系統給的，自己加上後綴
}

/// 演練流程（兩個版本共用）：三筆時間逐一產生標籤，和正確答案比對
private func scenario(_ log: LabLog, pattern: String) {
    var wrong = 0
    for (name, iso, expected) in cases {
        let result = label(ISO8601DateFormatter().date(from: iso)!, pattern: pattern)
        if result == expected {
            log("\(name) → \(result) ✓")
        } else {
            wrong += 1
            log("\(name) → \(result) ✗（應該是 \(expected)）")
        }
    }
    log("共 3 筆時間標籤，顯示錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L05Bug {
    static var pattern: String {
        // TODO ①：回傳 "YYYY-MM-dd hh:mm:ss"
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, pattern: pattern)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static var pattern: String {
        // TODO ②：回傳 "yyyy-MM-dd HH:mm:ss"
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, pattern: pattern)
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 3 筆") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange13Localization {
    static let l05 = Lab(
        id: "L05",
        title: "跨年那天的紀錄顯示成明年，晚上 11 點顯示 11:59",
        question: "第 4 題：日期與時區怎麼處理？JST 是什麼？",
        goal: "日期格式字串：yyyy 是西曆年，YYYY 是週年（跨年那一週會顯示成下一年）；HH 是 24 小時制，hh 是 12 小時制。交易紀錄用固定、沒有歧義的格式，並標示 JST。",
        steps: [
            "打開 Practice/Exchange/Exchange13-Localization/L05_WeekYear.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看三筆時間：2024-12-31 的年份、23:59:59 的小時各顯示成什麼？",
            "想一想：12 月 31 日那一週為什麼屬於 2025 年的第 1 週？這種 bug 一年只有幾天會出現，平常測試看得到嗎？",
            "寫 TODO ②（修正版），選「修正版」執行，三筆時間都要和正確答案一致。",
            "進階：時區縮寫（zzz）在 ja_JP 是 JST，在 en_US、zh_Hant_TW 是 GMT+9；想顯示 JST 就自己加上後綴，不要依賴系統給的縮寫。",
        ],
        expectBug: "「跨年前一秒 → 2025-12-31 11:59:59 JST ✗」（年份變成 2025，小時變成 11），「新年第一天 0 點 → 2025-01-01 12:00:05 JST ✗」，「一般的下午 → 2026-10-24 01:05:09 JST ✗」（13 點顯示成 01）：「共 3 筆時間標籤，顯示錯的有 3 筆」。",
        expectFixed: "三筆都和正確答案一致，例如「2024-12-31 23:59:59 JST」：「共 3 筆時間標籤，顯示錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, pattern:) 執行" }
            if !lines.contains("跨年前一秒 → 2025-12-31 11:59:59 JST ✗（應該是 2024-12-31 23:59:59 JST）") {
                return "Bug 還沒重現：格式字串用 \"YYYY-MM-dd hh:mm:ss\"，2024-12-31 會顯示成 2025-12-31，23 點顯示成 11"
            }
            return n == 3 ? nil : "Bug 版三筆都應該顯示錯（目前錯 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, pattern:) 執行" }
            return n == 0 ? nil : "還有 \(n) 筆顯示錯：yyyy 是西曆年（不是 YYYY 週年），HH 是 24 小時制（不是 hh）"
        }
    )
}
