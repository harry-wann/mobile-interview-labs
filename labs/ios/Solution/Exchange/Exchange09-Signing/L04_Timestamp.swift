// L04：只有部分使用者簽名永遠失敗（Timestamp 跟著手機的語言、曆法、時區變）
// 對應複習站：Exchange09Signing 第 4 題「Timestamp 怎麼產生才不會出錯？」
//
// 情境：Signature V2 的 Timestamp 要求 UTC、格式固定是 yyyy-MM-ddTHH:mm:ss（24 小時制，沒有毫秒與時區後綴）。
//       同一個時間 2017-05-11 15:20:06 UTC，演練用三位使用者手機的設定各產生一次：
//       台灣的使用者（西曆、UTC+8）、日本的使用者（日曆設成和曆、UTC+9）、泰國的使用者（佛曆、UTC+7）。
//       三個人的 Timestamp 都應該是 2017-05-11T15:20:06。
// Bug 版：DateFormatter 沒有另外固定設定，用的就是使用者手機的語言、曆法與時區（演練把它們明確帶進來模擬），
//        只設了 dateFormat。結果是本地時間而不是 UTC，和曆的年份變成「平成 29 年」的 0029，佛曆變成 2560。
// 修正版：locale 固定 en_US_POSIX、calendar 固定西曆、timeZone 固定 UTC，再設 dateFormat。
import Foundation
import LabKit

/// 使用者手機的設定（演練把它們傳進來，不管模擬器本身設定成什麼，結果都一樣）
private struct UserSettings {
    let name: String
    let locale: Locale
    let calendar: Calendar
    let timeZone: TimeZone
}

private let users = [
    UserSettings(name: "台灣的使用者", locale: Locale(identifier: "zh_TW"), calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(identifier: "Asia/Taipei")!),
    UserSettings(name: "日本的使用者（和曆）", locale: Locale(identifier: "ja_JP"), calendar: Calendar(identifier: .japanese), timeZone: TimeZone(identifier: "Asia/Tokyo")!),
    UserSettings(name: "泰國的使用者（佛曆）", locale: Locale(identifier: "th_TH"), calendar: Calendar(identifier: .buddhist), timeZone: TimeZone(identifier: "Asia/Bangkok")!),
]

private let expected = "2017-05-11T15:20:06"
private let moment = Date(timeIntervalSince1970: 1_494_516_006)      // 2017-05-11 15:20:06 UTC

/// 演練流程（兩個版本共用）：三位使用者各產生一次 Timestamp，和正確答案比對
private func scenario(_ log: LabLog, timestamp: (Date, UserSettings) -> String) {
    var wrong = 0
    for user in users {
        let text = timestamp(moment, user)
        if text == expected {
            log("\(user.name)：\(text) ✓")
        } else {
            wrong += 1
            log("\(user.name)：\(text) ✗（應該是 \(expected)）")
        }
    }
    log("3 位使用者，Timestamp 算錯的有 \(wrong) 位")
}

// MARK: - Bug 版
private enum L04Bug {
    fileprivate static func timestamp(_ date: Date, user: UserSettings) -> String {
        let formatter = DateFormatter()
        formatter.locale = user.locale          // 沒有另外固定 = 跟著使用者手機的設定（這三行模擬 DateFormatter() 預設會帶的值）
        formatter.calendar = user.calendar
        formatter.timeZone = user.timeZone
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return formatter.string(from: date)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { timestamp($0, user: $1) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    fileprivate static func timestamp(_ date: Date, user: UserSettings) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")           // 固定的格式，不跟著語言設定
        formatter.calendar = Calendar(identifier: .gregorian)          // 固定用西曆
        formatter.timeZone = TimeZone(identifier: "UTC")               // 固定用 UTC
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"                 // HH 是 24 小時制
        return formatter.string(from: date)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { timestamp($0, user: $1) }
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("3 位使用者") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange09Signing {
    static let l04 = Lab(
        id: "L04",
        title: "只有部分使用者簽名永遠失敗",
        question: "第 4 題：Timestamp 怎麼產生才不會出錯？",
        goal: "Timestamp 要固定成 UTC、西曆、24 小時制的 yyyy-MM-ddTHH:mm:ss，不能受手機的語言、曆法、時區影響。",
        steps: [
            "選「Bug 版」執行，看三位使用者的 Timestamp：時間、年份各錯成什麼樣子？",
            "想一想：台灣的使用者為什麼是 23:20:06？日本的使用者為什麼連日期都變了？0029 是哪一年？",
            "選「修正版」執行，三位使用者都要得到 2017-05-11T15:20:06。",
            "進階：iOS 的 en_US_POSIX 是 Apple 提供的固定格式語言設定；Android 的對應做法是 java.time 的 DateTimeFormatter 加 Locale.ROOT 與 ZoneOffset.UTC。",
        ],
        expectBug: "台灣的使用者是 2017-05-11T23:20:06（本地時間，不是 UTC），日本的使用者是 0029-05-12T00:20:06（平成 29 年、日期也跨日），泰國的使用者是 2560-05-11T22:20:06（佛曆），「3 位使用者，Timestamp 算錯的有 3 位」。",
        expectFixed: "三位使用者都是 2017-05-11T15:20:06，「3 位使用者，Timestamp 算錯的有 0 位」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log) { date, user in … } 執行" }
            if !lines.contains(where: { $0.hasPrefix("台灣的使用者：2017-05-11T23:20:06") }) {
                return "Bug 還沒重現：沒有固定 timeZone 時，台灣的使用者（UTC+8）會得到 23:20:06 的本地時間"
            }
            if !lines.contains(where: { $0.hasPrefix("日本的使用者（和曆）：0029-") }) {
                return "Bug 還沒重現：calendar 用使用者的和曆時，年份會變成平成 29 年的 0029"
            }
            return n == 3 ? nil : "三位使用者的 Timestamp 都應該算錯（目前錯 \(n) 位）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log) { date, user in … } 執行" }
            return n == 0 ? nil : "還有 \(n) 位使用者算錯：locale 固定 en_US_POSIX、calendar 固定西曆、timeZone 固定 UTC，不要用 user 的設定"
        }
    )
}
