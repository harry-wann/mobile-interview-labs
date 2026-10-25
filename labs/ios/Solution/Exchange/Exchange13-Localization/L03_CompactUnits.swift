// L03：成交量顯示「10000万」、「1万」少了零頭（万／億的縮寫：沒有進位升級，用 Double 除再捨入）
// 對應複習站：Exchange13Localization 第 3 題「万、億這類大數單位怎麼顯示？」
//
// 情境：產品規格要把市值、成交量縮寫成大數單位：兩位小數、四捨五入（half-up）、去掉尾端的 0、捨入後剛好到下一個單位要進位升級。
//       日文：万（10 的 4 次方）、億（8 次方）、兆（12 次方）；繁體中文：萬、億、兆；簡體中文：万、亿、万亿（12 次方）；英文：K、M、B、T（三位一組）。
//       小於最小單位的數字不縮寫，但要有千分位。演練檢查 11 筆：
//       ja 9999 → 9,999；12345 → 1.23万；10050 → 1.01万；100000000 → 1億；99999999 → 1億（9999.9999万 捨入成 10000万，要升級成 1億）；
//       123456789 → 1.23億；1234567890123 → 1.23兆；en 123456789 → 123.46M；zh-Hans 123456789 → 1.23亿；1234567890123 → 1.23万亿；zh-Hant 12345 → 1.23萬。
//       compact 是有損的顯示，只用在市值、成交量這類輔助標籤，不能用在下單數量、確認頁、損益明細。
// Bug 版：用 Double 除以單位，乘 100 之後 rounded() 再除以 100（常見的「取兩位小數」寫法），去掉尾端的 .0：10050 ÷ 10000 = 1.005，
//        在二進位裡 1.005 × 100 = 100.49999999999999，rounded() 得到 100，顯示成「1万」（應該是 1.01万）；
//        99999999 ÷ 10000 = 9999.9999 捨入成 10000.0，顯示「10000万」，沒有升級成 1億；小於 1万的數字沒有千分位（9999）。
// 修正版：全程用整數運算：先放大 100 倍再整除（加上單位的一半 = half-up），捨入後如果達到下一個單位，就升級；最後把整數部分與兩位小數拼回字串。
import Foundation
import LabKit

/// 一個大數單位：數值與顯示文字
private struct Unit {
    let value: Int64
    let label: String
}

/// 每個語言的單位，由小排到大
private func units(for language: String) -> [Unit] {
    switch language {
    case "ja": return [Unit(value: 10_000, label: "万"), Unit(value: 100_000_000, label: "億"), Unit(value: 1_000_000_000_000, label: "兆")]
    case "zh-Hant": return [Unit(value: 10_000, label: "萬"), Unit(value: 100_000_000, label: "億"), Unit(value: 1_000_000_000_000, label: "兆")]
    case "zh-Hans": return [Unit(value: 10_000, label: "万"), Unit(value: 100_000_000, label: "亿"), Unit(value: 1_000_000_000_000, label: "万亿")]
    default: return [Unit(value: 1_000, label: "K"), Unit(value: 1_000_000, label: "M"), Unit(value: 1_000_000_000, label: "B"), Unit(value: 1_000_000_000_000, label: "T")]
    }
}

/// 千分位（小於最小單位的數字用）
private func grouped(_ value: Int64) -> String {
    var digits = Array(String(value))
    var out: [Character] = []
    while digits.count > 3 {
        out.insert(contentsOf: [","] + digits.suffix(3), at: 0)
        digits.removeLast(3)
    }
    return String(digits + out)
}

/// （語言, 數值, 正確的結果）
private let cases: [(String, Int64, String)] = [
    ("ja", 9_999, "9,999"),
    ("ja", 12_345, "1.23万"),
    ("ja", 10_050, "1.01万"),
    ("ja", 100_000_000, "1億"),
    ("ja", 99_999_999, "1億"),
    ("ja", 123_456_789, "1.23億"),
    ("ja", 1_234_567_890_123, "1.23兆"),
    ("en", 123_456_789, "123.46M"),
    ("zh-Hans", 123_456_789, "1.23亿"),
    ("zh-Hans", 1_234_567_890_123, "1.23万亿"),
    ("zh-Hant", 12_345, "1.23萬"),
]

/// 演練流程（兩個版本共用）：11 筆逐一縮寫，和正確答案比對
private func scenario(_ log: LabLog, compact: (Int64, String) -> String) {
    var wrong = 0
    for (language, value, expected) in cases {
        let result = compact(value, language)
        if result == expected {
            log("\(language) \(value) → \(result) ✓")
        } else {
            wrong += 1
            log("\(language) \(value) → \(result) ✗（應該是 \(expected)）")
        }
    }
    log("共 11 筆，縮寫錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L03Bug {
    static func compact(_ value: Int64, _ language: String) -> String {
        let us = units(for: language)
        guard let unit = us.last(where: { value >= $0.value }) else { return String(value) }       // 小於最小單位：沒有千分位
        let rounded = (Double(value) / Double(unit.value) * 100).rounded() / 100                   // Double 運算：1.005 × 100 = 100.49999999999999
        var text = String(rounded)                                                                  // "1.0"、"10000.0"、"1.23"
        if text.hasSuffix(".0") { text.removeLast(2) }                                              // 去掉結尾的 .0
        return text + unit.label                                                                    // 沒有檢查捨入後是不是已經到下一個單位
    }

    static func run(_ log: LabLog) async {
        scenario(log, compact: compact(_:_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func compact(_ value: Int64, _ language: String) -> String {
        let us = units(for: language)
        guard var index = us.lastIndex(where: { value >= $0.value }) else { return grouped(value) }       // 小於最小單位：不縮寫，有千分位
        var scaled = (value * 100 + us[index].value / 2) / us[index].value                               // 兩位小數、half-up，全程整數
        while index + 1 < us.count, scaled >= (us[index + 1].value / us[index].value) * 100 {            // 捨入後達到下一個單位：升級
            index += 1
            scaled = (value * 100 + us[index].value / 2) / us[index].value
        }
        var text = String(scaled / 100)                                                                  // 整數部分
        let fraction = scaled % 100
        if fraction != 0 {
            text += "." + (fraction % 10 == 0 ? String(fraction / 10) : String(format: "%02d", fraction))   // 兩位小數，去掉尾端的 0
        }
        return text + us[index].label
    }

    static func run(_ log: LabLog) async {
        scenario(log, compact: compact(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 11 筆") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange13Localization {
    static let l03 = Lab(
        id: "L03",
        title: "成交量顯示 10000万，1.005万 少了零頭",
        question: "第 3 題：万、億這類大數單位怎麼顯示？",
        goal: "万／億是四位一組，K／M／B 是三位一組；縮寫要用整數運算（不用 Double），捨入後達到下一個單位要進位升級，小於最小單位的數字要有千分位。",
        steps: [
            "選「Bug 版」執行，看 11 筆哪些縮寫錯了：10050、99999999、9999 各顯示成什麼？",
            "想一想：1.005 乘 100 用 Double 算是多少？rounded() 為什麼得到 100？99999999 捨入後為什麼不應該顯示成 10000万？",
            "選「修正版」執行，11 筆都要和正確答案一致。",
            "進階：平台的 compact formatter（iOS 15 起的 .compactName、Android 的 CompactDecimalFormat）只保留約兩位有效數字，依 CLDR 版本結果不同，zh_Hant_HK 在本機實測是英文式的單位 123M；產品要明確規則時，自己實作並用固定輸入的測試固定下來。",
        ],
        expectBug: "「ja 10050 → 1万 ✗」（應該是 1.01万）、「ja 99999999 → 10000万 ✗」（應該升級成 1億）、「ja 9999 → 9999 ✗」（沒有千分位），其他 8 筆剛好對：「共 11 筆，縮寫錯的有 3 筆」。",
        expectFixed: "11 筆都和正確答案一致，例如 1.01万、1億、1.23万亿、123.46M：「共 11 筆，縮寫錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, compact:) 執行" }
            if !lines.contains("ja 123456789 → 1.23億 ✓") { return "Bug 版在一般的數字要算對：123456789 ÷ 1億 = 1.23456789，捨入成 1.23億" }
            if !lines.contains("ja 10050 → 1万 ✗（應該是 1.01万）") { return "Bug 還沒重現：用 Double 乘 100 再取整，10050 ÷ 10000 = 1.005 會算成 1.00，顯示 1万" }
            if !lines.contains("ja 99999999 → 10000万 ✗（應該是 1億）") { return "Bug 還沒重現：沒有檢查捨入後是不是已經到下一個單位，99999999 會顯示成 10000万" }
            return n == 3 ? nil : "Bug 版應該有 3 筆縮寫錯（目前 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, compact:) 執行" }
            return n == 0 ? nil : "還有 \(n) 筆縮寫錯：用整數運算 (value * 100 + 單位 / 2) / 單位 做 half-up，達到下一個單位要升級，小於最小單位用 grouped"
        }
    )
}
