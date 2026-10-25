// L05：手續費被算成 0，沒有任何錯誤（Objective-C 對 nil 傳訊息）
// 對應複習站：Swift11ObjC 第 6 題「對 nil 傳訊息會怎樣？什麼是 method swizzling？」
//
// 情境：Objective-C 的 FeeCalculator 用手續費率表（table）算手續費：金額乘以費率。呼叫端忘了設定 table，它是 nil。
// Bug 版：[self.table rateForSymbol:] 對 nil 傳訊息，不會當掉，回傳 0，所以手續費算出 0 日圓。沒有 crash、沒有錯誤，使用者少付了手續費，要很久之後才被發現。
// 修正版：先檢查 table 是不是 nil，是的話回傳 nil 並填入 NSError。Swift 端會看到 throws，編譯器強迫呼叫端處理。
// Objective-C 的程式在 Solution/SwiftObjC/include/SOLabL05.h 與 SOLabL05.m。
import Foundation
import LabKit
import SolutionSwiftObjC

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        let calculator = SOLabL05BugFeeCalculator()          // 忘了設定 calculator.table
        let fee = calculator.fee(forAmount: 1_000_000, symbol: "BTC/JPY")
        log("手續費：\(fee) 日圓（沒有 crash，也沒有任何錯誤）")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        let calculator = SOLabL05FixedFeeCalculator()        // 一樣忘了設定 calculator.table
        do {
            let fee = try calculator.fee(forAmount: 1_000_000, symbol: "BTC/JPY")
            log("手續費：\(fee) 日圓")
        } catch {
            log("算手續費失敗：\(error.localizedDescription)")
        }
        calculator.table = SOLabL05FeeTable()                // 設定好之後再算
        if let fee = try? calculator.fee(forAmount: 1_000_000, symbol: "BTC/JPY") {
            log("設定率表之後，手續費：\(fee) 日圓")
        }
    }
}

// MARK: - 演練設定
extension Swift11ObjC {
    static let l05 = Lab(
        id: "L05",
        title: "手續費被靜默算成 0",
        question: "第 6 題：對 `nil` 傳訊息會怎樣？什麼是 method swizzling？",
        goal: "Objective-C 對 nil 傳訊息是安全的、回傳 0，所以忘了設定的物件不會讓 App 當掉，錯誤卻被吞掉。看它怎麼發生，再改成明確回報錯誤。",
        steps: [
            "選「Bug 版」執行，看 log：手續費是多少？有沒有任何錯誤或 crash？",
            "選「修正版」執行：同樣忘了設定率表，這次得到「手續費率表還沒設定」的錯誤；設定之後算出 1000 日圓。",
            "打開 SOLabL05.m：Bug 版直接 [self.table rateForSymbol:]；修正版先檢查 table。",
            "想想：同樣的事在 Swift 會怎樣？（Optional 要先 unwrap，沒設定就是編譯器或 guard 擋下來。）",
        ],
        expectBug: "手續費：0.0 日圓。沒有 crash、沒有錯誤：對 nil 傳訊息回傳 0。",
        expectFixed: "忘了設定率表時得到「手續費率表還沒設定」；設定之後手續費是 1000 日圓。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("手續費：0.0 日圓（沒有 crash，也沒有任何錯誤）") ? nil : "Bug 還沒重現：table 是 nil 時，直接對它傳訊息，手續費要算出 0.0"
        },
        checkFixed: { lines in
            if !lines.contains("算手續費失敗：手續費率表還沒設定") { return "沒設定率表時，要回傳錯誤「手續費率表還沒設定」：先檢查 table 是不是 nil，填入 NSError" }
            if !lines.contains("設定率表之後，手續費：1000 日圓") { return "設定率表之後，手續費應該是 1000 日圓" }
            return nil
        }
    )
}
