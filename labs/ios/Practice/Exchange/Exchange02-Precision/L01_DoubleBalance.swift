// L01：明明剛好夠，卻顯示「餘額不足」（可用餘額用 Double 算）
// 對應複習站：Exchange02Precision 第 1 題「為什麼金額不能用 `Double`？」
//
// 情境：使用者的資產有一部分掛在還沒成交的單上（凍結）。可用餘額 = 總額 − 凍結。
//       API 回傳的總額與凍結是字串。三個交易對的情況：總額 1.0 凍結 0.9、總額 0.3 凍結 0.2、總額 0.7 凍結 0.6，可用餘額都是剛好 0.1。
//       使用者按「全部賣出」，輸入框填入 0.1，App 判斷「輸入的數量不超過可用餘額」才能送出。
//       （Platform08 的演練 2 練過「Double 對齊 tick size 出錯」，這題看的是餘額的加減。）
//
// 你要寫：
//   ① Bug 版（L01Bug.check）：用 Double 算可用餘額 = 總額 − 凍結，再比較輸入的數量。
//   ② 修正版（L01Fixed.check）：改用 Decimal(string:) 算。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange02-Precision/L01_DoubleBalance.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：三種情況各按一次「全部賣出」
private func scenario(_ log: LabLog, check: (String, String, String) -> (available: String, enough: Bool)) {
    var refused = 0
    for (total, locked) in [("1.0", "0.9"), ("0.3", "0.2"), ("0.7", "0.6")] {
        let result = check(total, locked, "0.1")
        log("總額 \(total) − 凍結 \(locked) = 可用 \(result.available)，輸入 0.1 → \(result.enough ? "可以送出" : "顯示「餘額不足」")")
        if !result.enough { refused += 1 }
    }
    log("3 次「全部賣出」，被誤判餘額不足 \(refused) 次")
}

// MARK: - Bug 版
private enum L01Bug {
    /// 回傳可用餘額（文字）和「輸入的數量夠不夠」
    static func check(total: String, locked: String, input: String) -> (available: String, enough: Bool) {
        // TODO ①：用 Double(total)!、Double(locked)! 算出可用餘額 available = 總額 − 凍結；
        //        回傳 ("\(available)", 輸入的數量 Double(input)! 是不是不超過 available)
        return ("", true)
    }

    static func run(_ log: LabLog) async {
        scenario(log, check: check(total:locked:input:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func check(total: String, locked: String, input: String) -> (available: String, enough: Bool) {
        // TODO ②：改用 Decimal(string:)! 建立 total、locked、input，一樣算可用餘額並比較
        return ("", false)
    }

    static func run(_ log: LabLog) async {
        scenario(log, check: check(total:locked:input:))
    }
}

// MARK: - 演練設定
private func refusedCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("3 次") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange02Precision {
    static let l01 = Lab(
        id: "L01",
        title: "明明剛好夠，卻顯示「餘額不足」",
        question: "第 1 題：為什麼金額不能用 `Double`？",
        goal: "可用餘額 = 總額 − 凍結，使用者輸入剛好等於可用餘額時，一定要能送出。餘額的加減要用什麼型別？先寫出用 Double 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange02-Precision/L01_DoubleBalance.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看三次「全部賣出」的可用餘額是多少、有幾次被誤判餘額不足。",
            "想一想：1.0 − 0.9 在 Double 裡剛好等於 0.1 嗎？比較「輸入的 0.1」和「算出來的可用餘額」誰大。",
            "寫 TODO ②（修正版），選「修正版」執行，三次都應該可以送出。",
            "進階：金額與數量一律用 Decimal／BigDecimal，API 的數字用字串讀進來再轉，不經過 Double。",
        ],
        expectBug: "可用餘額顯示 0.09999999999999998，輸入 0.1 反而比較大，「3 次「全部賣出」，被誤判餘額不足 3 次」。",
        expectFixed: "三次的可用餘額都是剛好 0.1，都可以送出，「3 次「全部賣出」，被誤判餘額不足 0 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = refusedCount(lines) else { return "演練沒有跑完：要用 scenario(log, check:) 執行" }
            if !lines.contains("總額 1.0 − 凍結 0.9 = 可用 0.09999999999999998，輸入 0.1 → 顯示「餘額不足」") { return "Bug 還沒重現：用 Double(total)! − Double(locked)! 算可用餘額，1.0 − 0.9 應該是 0.09999999999999998，比 0.1 小一點點" }
            return n == 3 ? nil : "Bug 還沒重現：三次都應該比 0.1 小一點點（目前誤判 \(n) 次）"
        },
        checkFixed: { lines in
            guard let n = refusedCount(lines) else { return "演練沒有跑完：要用 scenario(log, check:) 執行" }
            if n != 0 { return "還有 \(n) 次被誤判餘額不足：用 Decimal(string:) 算可用餘額，也用 Decimal 比較輸入的數量" }
            return lines.contains("總額 1.0 − 凍結 0.9 = 可用 0.1，輸入 0.1 → 可以送出") ? nil : "可用餘額應該是剛好 0.1：total 和 locked 都要用 Decimal(string:) 建立"
        }
    )
}
