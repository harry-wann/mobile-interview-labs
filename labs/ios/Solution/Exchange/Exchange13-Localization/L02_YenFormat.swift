// L02：日圓金額顯示成「¥1,234,567.80」，負數變成「¥-1,234.40」（貨幣符號、
// 小數位數、負號位置寫死）
// 對應複習站：Exchange13Localization 第 2 題「數字怎麼顯示？千分位、
// 日圓沒有小數、預設捨入有什麼陷阱？」
//
// 情境：日圓（JPY）的最小單位就是 1 圓，沒有小數位數；
// 貨幣符號和負號的位置依語系不同
//       （本機實測：ja_JP 與 en_US 是 -¥1,234，zh_Hans_CN 是 -JP¥1,234）。
// 演練把四筆資料交給 formatYen 顯示：
//       ①1234567.8（ja_JP）②-1234.4（ja_JP）③1234567.8（en_US）④1234567.
// 8（zh_Hans_CN）。
//       檢查看三件事：數字是不是「整數、有千分位」（日圓沒有小數）、負數的 -
// 是不是在最前面、符號出現在哪裡不檢查（交給平台依語系決定）。
//       （四捨五入的模式在 Exchange02 的演練 5 練過，
// 這題的資料都避開剛好在中間的值。）
// Bug 版：自己拼字串：寫死 "¥"，數字用固定兩位小數的格式。日圓顯示成「¥1,234,
// 567.80」，多了不存在的小數；負數變成「¥-1,234.40」，負號跑到符號後面；
//        其他語系的符號位置（例如簡體中文的 JP¥）也被忽略。
// 修正版：用貨幣格式（NumberFormatter 的 currency），currencyCode 設 "JPY"，
// locale 用明確傳進來的使用者語系：
//        小數位數、符號、符號位置、負號都交給平台依 JPY 與語系處理；
// 捨入模式明確設成 halfUp。
import Foundation
import LabKit

/// （描述, 金額, 語系, 數字部分應該是什麼（只有數字）, 是不是負數）
private let cases: [(String, Decimal, String, String, Bool)] = [
    (
        "1234567.8，ja_JP", Decimal(string: "1234567.8")!, "ja_JP", "1234568",
        false
    ),
    ("-1234.4，ja_JP", Decimal(string: "-1234.4")!, "ja_JP", "1234", true),
    (
        "1234567.8，en_US", Decimal(string: "1234567.8")!, "en_US", "1234568",
        false
    ),
    (
        "1234567.8，zh_Hans_CN", Decimal(string: "1234567.8")!, "zh_Hans_CN",
        "1234568", false
    ),
]

/// 演練流程（兩個版本共用）：四筆金額逐一顯示，檢查數字、千分位與負號的位置
private func scenario(_ log: LabLog, formatYen: (Decimal, Locale) -> String) {
    var wrong = 0
    for (name, amount, localeID, digits, negative) in cases {
        let text = formatYen(amount, Locale(identifier: localeID))
        let shownDigits = text.filter(\.isNumber)
        let negativeOK =
            negative
            ? (text.first == "-" || text.first == "−") : !text.contains("-")
        let groupingOK =
            !digits.isEmpty && digits.count <= 3 || text.contains(",")
        var problems: [String] = []
        if shownDigits != digits { problems.append("數字應該是 \(digits)（日圓沒有小數）") }
        if !negativeOK { problems.append("負號要在最前面") }
        if !groupingOK { problems.append("要有千分位") }
        if problems.isEmpty {
            log("\(name) → \(text) ✓")
        } else {
            wrong += 1
            log("\(name) → \(text) ✗（\(problems.joined(separator: "；"))）")
        }
    }
    log("共 4 筆金額，顯示錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L02Bug {
    static func formatYen(_ amount: Decimal, _ locale: Locale) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.locale = Locale(identifier: "en_US")
        // 固定兩位小數：日圓沒有小數
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        // 符號寫死在最前面：負號跑到後面
        return "¥"
            + (formatter.string(from: NSDecimalNumber(decimal: amount)) ?? "")
    }

    static func run(_ log: LabLog) async {
        scenario(log, formatYen: formatYen(_:_:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func formatYen(_ amount: Decimal, _ locale: Locale) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        // 日圓：小數位數、符號、負號的位置都由平台決定
        formatter.currencyCode = "JPY"
        // 明確傳進來的使用者語系，不用 Locale.current
        formatter.locale = locale
        // 預設是 half-even，明確指定
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSDecimalNumber(decimal: amount)) ?? ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, formatYen: formatYen(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 4 筆金額") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange13Localization {
    static let l02 = Lab(
        id: "L02",
        title: "日圓顯示成 ¥1,234,567.80，負數的負號在符號後面",
        question: "第 2 題：數字怎麼顯示？千分位、日圓沒有小數、預設捨入有什麼陷阱？",
        goal:
            "日圓沒有小數位數；貨幣符號與負號的位置依語系不同。不要自己拼字串，用平台的貨幣格式，指定 currencyCode 與明確的使用者語系。",
        steps: [
            "選「Bug 版」執行，看四筆金額顯示成什麼：小數位數、負號的位置各有什麼問題？",
            "想一想：同樣是 1234567.8 圓，ja_JP、en_US、zh_Hans_CN 的符號各是什麼？自己寫死 ¥ 會漏掉什麼？",
            "選「修正版」執行，四筆都沒有小數、有千分位，負數的 - 在最前面。",
            "進階：顯示用的字串和送給 API 的字串要分開，API 一律用固定格式（en_US_POSIX 或 Decimal.description）；加密資產用代號（BTC、ETH），不用符號。",
        ],
        expectBug:
            "「1234567.8，ja_JP → ¥1,234,567.80 ✗」（多了小數，數字變成 123456780），「-1234.4，ja_JP → ¥-1,234.40 ✗」（負號在符號後面），其他兩筆同樣多了小數：「共 4 筆金額，顯示錯的有 4 筆」。",
        expectFixed:
            "「¥1,234,568」「-¥1,234」，簡體中文是「JP¥1,234,568」：「共 4 筆金額，顯示錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, formatYen:) 執行"
            }
            if !lines.contains(where: {
                $0.hasPrefix("1234567.8，ja_JP → ¥1,234,567.80")
            }) {
                return "Bug 還沒重現：寫死 \"¥\" 並固定兩位小數，1234567.8 會顯示成 ¥1,234,567.80"
            }
            if !lines.contains(where: {
                $0.hasPrefix("-1234.4，ja_JP → ¥-1,234.40")
            }) {
                return "Bug 還沒重現：符號寫死在最前面，負號會跑到後面（¥-1,234.40）"
            }
            return n == 4 ? nil : "Bug 版四筆都應該顯示錯（目前錯 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, formatYen:) 執行"
            }
            return n == 0
                ? nil
                : "還有 \(n) 筆顯示錯：用 .currency 加 currencyCode \"JPY\" 與傳進來的 locale，不要自己拼符號和小數位數"
        }
    )
}
