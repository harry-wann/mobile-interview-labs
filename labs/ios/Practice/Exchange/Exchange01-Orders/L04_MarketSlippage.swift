// L04：成行買單預估的平均成交價，把滑價藏起來了
// 對應複習站：Exchange01Orders 第 3 題「成行、指値、逆指値這三種基本的下單方式有什麼差別？」
//
// 情境：使用者要用成行買 0.6 BTC。賣板有三層：10,000,000 円 0.2 BTC、10,010,000 円 0.3 BTC、10,030,000 円 0.5 BTC。
//       下單前，App 要預估平均成交價；預估的滑價（平均成交價比最低賣價貴多少）超過 0.05% 就要警告使用者。
//       （0.05% 是演練的假設。）
//
// 你要寫：
//   ① Bug 版（L04Bug.estimateCost）：全部用最低賣價 book.asks[0].price 乘以數量估算。
//   ② 修正版（L04Fixed.estimateCost）：從最便宜的價位開始一層一層吃，每層最多吃 min(剩餘數量, 這層的數量)，加總花費。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange01-Orders/L04_MarketSlippage.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：預估 → 判斷要不要警告 → 對照實際吃板的結果
private func scenario(_ log: LabLog, estimateCost: (E01Book, Decimal) -> Decimal) {
    let book = E01Book(asks: [
        E01Level(price: e01("10000000"), qty: e01("0.2")),
        E01Level(price: e01("10010000"), qty: e01("0.3")),
        E01Level(price: e01("10030000"), qty: e01("0.5")),
    ])
    let want = e01("0.6")
    let best = book.asks[0].price
    let cost = estimateCost(book, want)
    let average = cost / want
    let slippagePercent = (average - best) / best * 100
    log("預估花費 \(cost) 円，平均成交價 \(average)（最低賣價 \(best)）")
    log("預估滑價 \(slippagePercent)%")
    if slippagePercent > e01("0.05") {
        log("⚠︎ 滑價超過 0.05%，要警告使用者")
    } else {
        log("滑價在 0.05% 以內，不警告")
    }
    var actualBook = book
    let actual = actualBook.takeAsks(qty: want).reduce(Decimal(0)) { $0 + $1.qty * $1.price }
    log("實際吃板花費 \(actual) 円，和預估差 \(actual - cost) 円")
}

// MARK: - Bug 版
private enum L04Bug {
    static func estimateCost(book: E01Book, qty: Decimal) -> Decimal {
        // TODO ①：數量 × 最低賣價（book.asks[0].price）
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, estimateCost: estimateCost(book:qty:))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func estimateCost(book: E01Book, qty: Decimal) -> Decimal {
        // TODO ②：remaining 一開始是 qty；依序走過 book.asks，每層吃 min(remaining, level.qty)，
        //        花費加上 吃的數量 × level.price，remaining 扣掉吃的數量，吃完就停；最後回傳總花費
        return 0
    }

    static func run(_ log: LabLog) async {
        scenario(log, estimateCost: estimateCost(book:qty:))
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l04 = Lab(
        id: "L04",
        title: "成行買單預估的價格，看不出滑價",
        question: "第 3 題：成行、指値、逆指値這三種基本的下單方式有什麼差別？",
        goal: "成行單會一路吃掉好幾個價位。送出前用深度資料預估平均成交價，滑價太大要警告使用者。先寫出看不出滑價的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange01-Orders/L04_MarketSlippage.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看預估花費和「實際吃板花費」差多少、有沒有警告。",
            "想一想：0.6 BTC 買得到全部都是 10,000,000 円嗎？第一層只有 0.2 BTC。",
            "寫 TODO ②（修正版），選「修正版」執行，預估要等於實際吃板的結果。",
            "進階：最後的結果一律以成交回報為準，預估只是幫使用者在送出前看到風險。",
        ],
        expectBug: "「預估花費 6000000 円，平均成交價 10000000」「預估滑價 0%」「滑價在 0.05% 以內，不警告」，但「實際吃板花費 6006000 円，和預估差 6000 円」。",
        expectFixed: "「預估花費 6006000 円，平均成交價 10010000」「預估滑價 0.1%」「⚠︎ 滑價超過 0.05%，要警告使用者」，和實際吃板的結果差 0 円。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("實際吃板花費 6006000 円") }) { return "演練沒有跑完：要用 scenario(log, estimateCost:) 執行" }
            if !lines.contains("滑價在 0.05% 以內，不警告") { return "Bug 還沒重現：預估要用 qty × 最低賣價（book.asks[0].price），看不出滑價，所以不會警告" }
            return lines.contains("實際吃板花費 6006000 円，和預估差 6000 円") ? nil : "Bug 版預估花費應該是 6000000，和實際差 6000"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("實際吃板花費 6006000 円") }) { return "演練沒有跑完：要用 scenario(log, estimateCost:) 執行" }
            if !lines.contains("⚠︎ 滑價超過 0.05%，要警告使用者") { return "預估要一層一層吃板（0.2 + 0.3 + 0.1），平均成交價 10010000，滑價 0.1% 要警告" }
            return lines.contains("實際吃板花費 6006000 円，和預估差 0 円") ? nil : "預估花費應該等於實際吃板的 6006000"
        }
    )
}
