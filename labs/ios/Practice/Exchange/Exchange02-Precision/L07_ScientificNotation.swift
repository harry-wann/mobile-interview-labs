// L07：1 satoshi 的價格顯示成 1e-08（很小的數字變成科學記號）
// 對應複習站：Exchange02Precision 第 5 題「數字要怎麼顯示？」
//
// 情境：行情畫面要顯示三個數字：0.00000001（1 satoshi，BTC 的最小單位）、0.00000015、100.00000000（尾端有多餘的 0）。
//       畫面上要看到一般的小數，不能出現科學記號。
//
// 你要寫：
//   ① Bug 版（L07Bug.display）：把文字轉成 Double，再用字串插值轉成字串。
//   ② 修正版（L07Fixed.display）：保留 Decimal，用 formatted(.number.precision(…)) 指定格式。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange02-Precision/L07_ScientificNotation.swift
import Foundation
import LabKit

private let values = ["0.00000001", "0.00000015", "100.00000000"]

/// 演練流程（兩個版本共用）：逐一顯示
private func scenario(_ log: LabLog, display: (String) -> String) {
    var scientific = 0
    for text in values {
        let shown = display(text)
        let isScientific = shown.lowercased().contains("e")
        if isScientific { scientific += 1 }
        log("\(text) → 畫面顯示 \(shown)\(isScientific ? "（科學記號）" : "")")
    }
    log("3 個數字裡，顯示成科學記號的有 \(scientific) 個")
}

// MARK: - Bug 版
private enum L07Bug {
    static func display(_ text: String) -> String {
        // TODO ①：用 Double(text) 轉成 Double（失敗用 0），再用字串插值 "\(value)" 轉成字串回傳
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, display: display(_:))
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func display(_ text: String) -> String {
        let value = Decimal(string: text) ?? 0
        // TODO ②：呼叫 value.formatted(…)：.number 加上 precision(.fractionLength(最少 0 位、最多 8 位))、
        //        grouping(.never)、locale 用 Locale(identifier: "en_US_POSIX")，回傳這個字串
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, display: display(_:))
    }
}

// MARK: - 演練設定
private func scientificCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("3 個數字裡") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange02Precision {
    static let l07 = Lab(
        id: "L07",
        title: "1 satoshi 的價格顯示成 1e-08",
        question: "第 5 題：數字要怎麼顯示？",
        goal: "很小的數字在畫面上要顯示成一般的小數，不能出現科學記號。顯示用的字串要怎麼產生？先寫出顯示成科學記號的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange02-Precision/L07_ScientificNotation.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看三個數字在畫面上變成什麼樣子。",
            "想一想：Double 轉成字串時，很小的數字會用什麼格式？",
            "寫 TODO ②（修正版），選「修正版」執行，三個數字都要是一般的小數。",
            "進階：Android 的 BigDecimal.toString() 也會用科學記號（0.00000001 印成 1E-8，stripTrailingZeros 之後的 100 印成 1E+2），顯示一律用 toPlainString()。",
        ],
        expectBug: "「0.00000001 → 畫面顯示 1e-08（科學記號）」「0.00000015 → 畫面顯示 1.5e-07（科學記號）」，「3 個數字裡，顯示成科學記號的有 2 個」。",
        expectFixed: "顯示 0.00000001、0.00000015、100，「3 個數字裡，顯示成科學記號的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = scientificCount(lines) else { return "演練沒有跑完：要用 scenario(log, display:) 執行" }
            return n == 2 ? nil : "Bug 還沒重現：先轉成 Double，再用 \"\\(value)\" 轉成字串，很小的數字會變成 1e-08（目前科學記號 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = scientificCount(lines) else { return "演練沒有跑完：要用 scenario(log, display:) 執行" }
            if n != 0 { return "還有 \(n) 個數字顯示成科學記號：保留 Decimal，用 formatted(.number.precision(...)) 指定格式" }
            return lines.contains("0.00000001 → 畫面顯示 0.00000001") ? nil : "1 satoshi 應該顯示 0.00000001（最多 8 位小數）"
        }
    )
}
