// L01：畫面寫購入價格 10,100,000 円，實際成交卻是 10,160,000
// 円（販売所的報價有有效時間）
// 對應複習站：Exchange01Orders 第 1
// 題「販売所和取引所差在哪？使用者到底是在跟誰買賣？」
//
// 情境：販売所（店頭交易）的買進畫面顯示「購入價格 10,100,000 円、売却價格 9,
// 900,000 円」，報價 5 秒內有效。
//       使用者看了 7 秒才按確認，這段時間交易所的報價已經漲到 10,160,000 円。
//       （演練用本機的假報價櫃檯，時間用毫秒數傳進去，不用真的等。5
// 秒是演練的假設，不是交易所的規格。）
//
// 你要寫：
//   ① Bug 版（L01Bug.confirm）：按確認時只送出數量，
// 不帶使用者看到的報價（quoteID 傳 nil）。
//   ② 修正版（L01Fixed.confirm）：帶上報價的編號 quote.id，
// 過期時由櫃檯回傳新報價。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange01-Orders/L01_QuoteExpiry.swift
import Foundation
import LabKit

/// 假的販売所櫃檯：報價 5 秒內有效，3 秒時價格上漲
private final class Desk {
    struct Quote {
        let id: Int
        // 購入價格：使用者買 1 BTC 要付的價格
        let buy: Decimal
        // 売却價格：使用者賣 1 BTC 能拿到的價格
        let sell: Decimal
        let expiresAtMs: Int
    }

    enum Result {
        case filled(price: Decimal)
        case requote(Quote)
    }

    private var nextID = 1
    private var issued: [Int: Quote] = [:]

    private func market(atMs now: Int) -> (buy: Decimal, sell: Decimal) {
        now < 3_000
            ? (e01("10100000"), e01("9900000"))
            : (e01("10160000"), e01("9960000"))
    }

    func quote(atMs now: Int) -> Quote {
        let prices = market(atMs: now)
        let quote = Quote(
            id: nextID, buy: prices.buy, sell: prices.sell,
            expiresAtMs: now + 5_000)
        issued[quote.id] = quote
        nextID += 1
        return quote
    }

    /// quoteID 是 nil：用當下的價格成交。有帶 quoteID：
    /// 報價還有效才用報價的價格成交，過期就回傳新的報價。
    func buy(qty: Decimal, quoteID: Int?, atMs now: Int) -> Result {
        guard let quoteID else { return .filled(price: market(atMs: now).buy) }
        if let quote = issued[quoteID], now <= quote.expiresAtMs {
            return .filled(price: quote.buy)
        }
        return .requote(quote(atMs: now))
    }
}

/// 演練流程（兩個版本共用）：看報價 → 7 秒後按確認
private func scenario(
    _ log: LabLog, confirm: (Desk, Desk.Quote, Decimal, Int) -> Desk.Result
) {
    let desk = Desk()
    let shown = desk.quote(atMs: 0)
    log(
        "畫面顯示：購入價格 \(shown.buy)／売却價格 \(shown.sell)，價差 \(shown.buy - shown.sell) 円（販売所的成本在這裡）"
    )
    log("報價在 \(shown.expiresAtMs) ms 到期，使用者 7000 ms 時按下確認")
    switch confirm(desk, shown, 1, 7_000) {
    case .filled(let price):
        log("成交價 \(price)，畫面上顯示的是 \(shown.buy)，多付 \(price - shown.buy) 円")
    case .requote(let fresh):
        log("価格が変動しました：新的購入價格 \(fresh.buy)（原本 \(shown.buy)），沒有成交，請使用者重新確認")
        if case .filled(let price) = desk.buy(
            qty: 1, quoteID: fresh.id, atMs: 7_500)
        {
            log("使用者看過新價格再按確認，成交價 \(price)")
        }
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func confirm(
        desk: Desk, quote: Desk.Quote, qty: Decimal, nowMs: Int
    ) -> Desk.Result {
        // TODO ①：呼叫 desk.buy(qty:quoteID:atMs:)，只送數量，quoteID 傳
        // nil（櫃檯會用當下的價格成交）
        // 先回傳一個安全的預設值，寫完請刪掉這行
        return .filled(price: 0)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { desk, quote, qty, now in
            confirm(desk: desk, quote: quote, qty: qty, nowMs: now)
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func confirm(
        desk: Desk, quote: Desk.Quote, qty: Decimal, nowMs: Int
    ) -> Desk.Result {
        // TODO ②：呼叫 desk.buy(qty:quoteID:atMs:)，帶上使用者看到的報價編號
        // quote.id
        // 先回傳一個安全的預設值，寫完請刪掉這行
        return .filled(price: 0)
    }

    static func run(_ log: LabLog) async {
        scenario(log) { desk, quote, qty, now in
            confirm(desk: desk, quote: quote, qty: qty, nowMs: now)
        }
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l01 = Lab(
        id: "L01",
        title: "畫面寫的價格和實際成交價不同",
        question: "第 1 題：販売所和取引所差在哪？使用者到底是在跟誰買賣？",
        goal:
            "販売所的報價只在幾秒內有效。使用者按確認時，如果價格已經變了，不能悄悄用新價格成交，要請使用者重新確認。先寫出會悄悄用新價格成交的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange01-Orders/L01_QuoteExpiry.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，比較「畫面上顯示的」和「成交價」差多少。",
            "想一想：按確認時，App 有沒有告訴櫃檯「我看到的是哪一個報價」？",
            "寫 TODO ②（修正版），選「修正版」執行，看有沒有出現「価格が変動しました」，以及重新確認後的成交價。",
            "進階：販売所的成本是購入價格和売却價格的價差（畫面上第一行），就算手續費寫 0 円，買進後馬上賣掉還是會少 200,000 円。",
        ],
        expectBug: "「成交價 10160000，畫面上顯示的是 10100000，多付 60000 円」，沒有任何提示。",
        expectFixed:
            "「価格が変動しました：新的購入價格 10160000（原本 10100000），沒有成交」，使用者重新確認後才以 10160000 成交。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("畫面顯示：購入價格 10100000") }) {
                return "演練沒有跑完：要用 scenario(log, confirm:) 執行"
            }
            return lines.contains("成交價 10160000，畫面上顯示的是 10100000，多付 60000 円")
                ? nil : "Bug 還沒重現：按確認時不帶報價（quoteID 傳 nil），櫃檯會用當下的價格 10160000 成交"
        },
        checkFixed: { lines in
            if lines.contains(where: { $0.hasPrefix("成交價") }) {
                return "報價已經過期，不該直接成交：呼叫 desk.buy 時要帶 quote.id"
            }
            if !lines.contains(where: { $0.hasPrefix("価格が変動しました") }) {
                return "沒有出現「価格が変動しました」：帶上 quote.id，櫃檯才會回傳新報價"
            }
            return lines.contains("使用者看過新價格再按確認，成交價 10160000")
                ? nil : "重新確認後應該以新報價 10160000 成交"
        }
    )
}
