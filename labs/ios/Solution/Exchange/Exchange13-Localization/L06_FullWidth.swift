// L06：日本使用者輸入全形的 １２３．５，數量變成 0；輸入 1,234.5abc 卻被當成 1（輸入的正規化與嚴格解析）
// 對應複習站：Exchange13Localization 第 6 題「日本使用者輸入全形數字時，要怎麼解析？」
//
// 情境：日文輸入法可能輸入全形的數字與符號，例如 １２３．５。下單的數量欄位要解析成數字。規則：先做 NFKC 正規化（全形轉半形），
//       再用嚴格的格式解析（只接受數字加最多一個小數點），不合法的一律拒絕並顯示錯誤，不可以悄悄採用部分結果，更不可以把失敗當成 0。
//       parse 回傳解析出的數字字串，拒絕時回傳 nil。八筆輸入：１２３．５、123.5、０．００１２、1,234.5abc、−5（U+2212 的負號）、空字串、1 000（中間有空白）、12.5.3。
// Bug 版：直接用 Decimal(string:) 解析，失敗就當成 0（`?? 0`）。Swift 的 Decimal(string:) 不認得全形，回傳 nil，被當成 0；
//        而它對「前面像數字、後面是垃圾」的字串很寬鬆：1,234.5abc 解成 1，1 000 解成 1，12.5.3 解成 12.5，悄悄採用了部分結果。
// 修正版：先 trim，再做 NFKC 正規化（precomposedStringWithCompatibilityMapping），用正規表示式 ^[0-9]+(\.[0-9]+)?$ 嚴格檢查，通過才轉成 Decimal；其他回傳 nil。
import Foundation
import LabKit

/// （描述, 使用者輸入, 正確的結果；nil 代表要拒絕）
private let cases: [(String, String, String?)] = [
    ("全形的 123.5", "１２３．５", "123.5"),
    ("半形的 123.5", "123.5", "123.5"),
    ("全形的 0.0012", "０．００１２", "0.0012"),
    ("1,234.5abc（後面有垃圾）", "1,234.5abc", nil),
    ("負號 U+2212", "−5", nil),
    ("空字串", "", nil),
    ("中間有空白", "1 000", nil),
    ("兩個小數點", "12.5.3", nil),
]

/// 演練流程（兩個版本共用）：八筆輸入逐一解析，和正確答案比對
private func scenario(_ log: LabLog, parse: (String) -> String?) {
    var wrong = 0
    for (name, input, expected) in cases {
        let result = parse(input)
        let shown = result ?? "拒絕"
        if result == expected {
            log("\(name) → \(shown) ✓")
        } else {
            wrong += 1
            log("\(name) → \(shown) ✗（應該是 \(expected ?? "拒絕")）")
        }
    }
    log("共 8 筆輸入，解析結果錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L06Bug {
    static func parse(_ text: String) -> String? {
        (Decimal(string: text) ?? 0).description                      // 失敗當成 0；不嚴格，前面像數字就採用
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func parse(_ text: String) -> String? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCompatibilityMapping   // NFKC：全形轉半形
        guard normalized.range(of: #"^[0-9]+(\.[0-9]+)?$"#, options: .regularExpression) != nil else { return nil }          // 嚴格：只有數字與一個小數點
        return Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX"))?.description
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 8 筆") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange13Localization {
    static let l06 = Lab(
        id: "L06",
        title: "全形輸入 １２３．５ 變成 0，1,234.5abc 被當成 1",
        question: "第 6 題：日本使用者輸入全形數字時，要怎麼解析？",
        goal: "數量欄位先 NFKC 正規化（全形轉半形），再用嚴格的格式解析；不合法就拒絕並顯示錯誤，不可以悄悄採用部分結果，更不可以把失敗當成 0。",
        steps: [
            "選「Bug 版」執行，看八筆輸入哪些解析錯了：全形的數字變成多少？1,234.5abc、1 000、12.5.3 各變成多少？",
            "想一想：數量變成 0 時，使用者按下買入會發生什麼事？1,234.5abc 變成 1 呢？",
            "選「修正版」執行，八筆都要和正確答案一致（全形要解出來，其餘不合法的要拒絕）。",
            "進階：負號 −（U+2212）和長音符 ー 不會被 NFKC 轉成 -，交易所的數量與價格欄位不需要負號，直接拒絕；貼上的字串可能有千分位與不換行空白（NBSP），先定義清楚接受哪些格式，其餘一律當錯誤。",
        ],
        expectBug: "「全形的 123.5 → 0 ✗」「全形的 0.0012 → 0 ✗」（解析失敗被當成 0），「1,234.5abc → 1 ✗」「中間有空白 → 1 ✗」「兩個小數點 → 12.5 ✗」（悄悄採用部分結果），負號 U+2212 與空字串變成 0 ✗：「共 8 筆輸入，解析結果錯的有 7 筆」（只有半形的 123.5 是對的）。",
        expectFixed: "全形的 １２３．５ 解出 123.5，０．００１２ 解出 0.0012，其餘不合法的都「拒絕」：「共 8 筆輸入，解析結果錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            if !lines.contains("半形的 123.5 → 123.5 ✓") { return "Bug 版對半形的 123.5 要解對（Decimal(string:) 認得）" }
            if !lines.contains("全形的 123.5 → 0 ✗（應該是 123.5）") { return "Bug 還沒重現：Decimal(string:) 不認得全形，回傳 nil，再 ?? 0 就變成 0" }
            if !lines.contains("1,234.5abc（後面有垃圾） → 1 ✗（應該是 拒絕）") { return "Bug 還沒重現：Decimal(string:) 對前面像數字、後面是垃圾的字串很寬鬆，1,234.5abc 會解成 1" }
            return n >= 6 ? nil : "Bug 版應該有 7 筆解析錯（目前 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            return n == 0 ? nil : "還有 \(n) 筆解析錯：先 trim 與 NFKC，再用 ^[0-9]+(\\.[0-9]+)?$ 嚴格檢查，不符合回傳 nil"
        }
    )
}
