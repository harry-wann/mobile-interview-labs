// L02：同一個價格，iOS 和 Android 對齊的結果不一樣（共用測試資料與 Decimal）
// 對應複習站：Platform08Modules 第 5 題「Kotlin Multiplatform（KMP）是什麼？該共用什麼？有什麼代價？」（價格精度的規則要一致）
//
// 情境：限價單的價格必須是 tick size（最小跳動單位）的整數倍。App 要把使用者輸入的價格「往下對齊」到 tick size 的倍數，
//       例如價格 0.57、tick size 0.01，結果還是 0.57。這個規則 iOS、Android、伺服器都要一模一樣。
//       就算不導入 KMP，至少兩個平台、伺服器共用同一份測試資料（下面的 vectors：價格、tick size、標準答案），各自跑一次，不一致就是 bug。
//       （演練把測試資料直接寫在檔案裡，iOS 與 Android 的文字一模一樣；真的專案會放成一份 JSON 檔，兩邊都讀。）
//
// 你要寫：
//   ① Bug 版：alignToTick 用 Double 計算：(p / t).rounded(.down) * t，再用 String(format: "%.8f") 轉成文字。
//   ② 修正版：alignToTick 用 Decimal(string:) 計算：p / t 用 NSDecimalRound 往下取整，再乘回 t，轉成文字。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform08-Modules/L02_SharedVectors.swift
import Foundation
import LabKit

/// 共用測試資料（兩個平台的文字一模一樣）：價格,tick size,標準答案
private let vectors = """
0.3,0.1,0.3
0.57,0.01,0.57
4.35,0.05,4.35
19.99,0.01,19.99
0.00012345,0.00000001,0.00012345
0.57,0.05,0.55
15000123,5,15000120
1.1,0.1,1.1
"""

/// 去掉小數尾端多餘的 0（兩個版本共用）："0.30000000" → "0.3"、"15000120.0" → "15000120"
private func trimmed(_ text: String) -> String {
    guard text.contains(".") else { return text }
    var result = text
    while result.hasSuffix("0") { result.removeLast() }
    if result.hasSuffix(".") { result.removeLast() }
    return result
}

/// 演練流程（兩個版本共用）：逐筆跑共用測試資料
private func scenario(_ log: LabLog, align: (String, String) -> String) {
    var mismatches = 0
    let rows = vectors.split(separator: "\n").map { $0.split(separator: ",").map(String.init) }
    for row in rows {
        let (price, tick, expected) = (row[0], row[1], row[2])
        let result = trimmed(align(price, tick))
        if result == expected {
            log("✓ \(price) 對齊 \(tick) → \(result)")
        } else {
            mismatches += 1
            log("✗ \(price) 對齊 \(tick) → \(result)（標準答案是 \(expected)）")
        }
    }
    log("共 \(rows.count) 筆共用測試資料，和標準答案不一致 \(mismatches) 筆")
}

// MARK: - Bug 版
private enum L02Bug {
    static func alignToTick(price: String, tick: String) -> String {
        // TODO ①：1. guard let p = Double(price), let t = Double(tick), t > 0 else { return "" }
        //        2. aligned = (p / t).rounded(.down) * t（用 Double 算）
        //        3. 回傳 String(format: "%.8f", aligned)
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log) { alignToTick(price: $0, tick: $1) }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func alignToTick(price: String, tick: String) -> String {
        // TODO ②：1. guard let p = Decimal(string: price), let t = Decimal(string: tick), t > 0 else { return "" }
        //        2. var quotient = p / t；用 NSDecimalRound(&floored, &quotient, 0, .down) 往下取整到個位數（floored 是 Decimal()）
        //        3. 回傳 "\(floored * t)"
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log) { alignToTick(price: $0, tick: $1) }
    }
}

// MARK: - 演練設定
private func mismatchCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 ") && $0.contains("不一致") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Platform08Modules {
    static let l02 = Lab(
        id: "L02",
        title: "同一個價格，iOS 和 Android 對齊的結果不一樣",
        question: "第 5 題：Kotlin Multiplatform（KMP）是什麼？該共用什麼？有什麼代價？",
        goal: "價格對齊 tick size 的規則，兩個平台算出來要一模一樣，而且要和共用測試資料的標準答案一致。價格要用什麼型別計算？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看哪些筆和標準答案不一致，不一致的結果比標準答案大還是小？出現 ✓。",
            "想一想：Double 能精確表示 0.01 嗎？0.57 ÷ 0.01 算出來是剛好 57 嗎？往下取整會怎樣？",
            "寫 TODO ②，選「修正版」執行，8 筆都要和標準答案一致。",
            "進階：Android 的演練用同一份測試資料與 BigDecimal；兩個平台各自都通過，才代表規則真的一致。價格與數量永遠不要用 Double（見 Exchange 區的精度單元）。",
        ],
        expectBug: "「共 8 筆共用測試資料，和標準答案不一致 5 筆」：0.3 變成 0.2、0.57 變成 0.56、4.35 變成 4.3、19.99 變成 19.98、0.00012345 變成 0.00012344，都被悄悄改小一格。",
        expectFixed: "「共 8 筆共用測試資料，和標準答案不一致 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = mismatchCount(lines) else { return "演練沒有跑完：要用 scenario(log, align:) 執行" }
            if !lines.contains("✓ 15000123 對齊 5 → 15000120") { return "Bug 版也要能算整數：先轉成 Double，(p / t).rounded(.down) * t，再用 String(format: \"%.8f\") 轉回文字" }
            return n == 5 ? nil : "Bug 還沒重現：用 Double 計算 (p / t).rounded(.down) * t，應該有 5 筆和標準答案不一致（目前 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = mismatchCount(lines) else { return "演練沒有跑完：要用 scenario(log, align:) 執行" }
            if n == 0 { return nil }
            return "還有 \(n) 筆和標準答案不一致，例如：\(lines.first(where: { $0.hasPrefix("✗") }) ?? "")。用 Decimal(string:) 計算，NSDecimalRound 往下取整"
        }
    )
}
