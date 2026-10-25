// L03：指値買單的手續費算少了（一張單的成交可能同時有 taker 和 maker）
// 對應複習站：Exchange01Orders 第 2 題「訂單簿是什麼？「約定」「部分約定」
// 「maker／taker」是什麼意思？」
//
// 情境：和上一個演練同一張單：指値買單 0.5 BTC、委託價 10,005,000 円。
//       先吃掉賣板上的 0.2 BTC（成交價 10,000,000 円，這一筆是 taker），剩下的
// 0.3 BTC 掛在買板上，之後有人賣給他（成交價 10,005,000 円，這一筆是 maker）。
//       手續費率是演練的假設，不是交易所的費率：maker 0.05%、taker 0.1%。
// Bug 版：用「下單的種類」決定費率：指値單就全部當成 maker。但那 0.2 BTC
// 是吃板，實際上是 taker，手續費少收了。
// 修正版：每一筆成交各自看它的角色（吃板是 taker、掛單等到的是 maker），
// 分別套用費率再加總。
import Foundation
import LabKit

private enum OrderType { case limit, market }

private let makerRate = e01("0.0005")  // 演練假設：0.05%
private let takerRate = e01("0.001")  // 演練假設：0.1%

/// 演練流程（兩個版本共用）：指値單先吃板、再等到對手，算每一筆成交的手續費
private func scenario(_ log: LabLog, rate: (E01Fill, OrderType) -> Decimal) {
    var book = E01Book(asks: [
        E01Level(price: e01("10000000"), qty: e01("0.2")),
        E01Level(price: e01("10010000"), qty: e01("0.3")),
    ])
    // 立刻吃板：taker
    var fills = book.takeAsks(qty: e01("0.5"), limit: e01("10005000"))
    // 之後有人賣給掛單：maker
    fills.append(E01Fill(qty: e01("0.3"), price: e01("10005000"), role: .maker))
    var total: Decimal = 0
    for fill in fills {
        let fee = fill.qty * fill.price * rate(fill, .limit)
        total += fee
        log(
            "成交 \(fill.qty) BTC @ \(fill.price)（\(fill.role == .taker ? "吃板 taker" : "掛單 maker")），手續費 \(fee) 円"
        )
    }
    log("手續費合計 \(total) 円")
}

// MARK: - Bug 版
private enum L03Bug {
    static func rate(for fill: E01Fill, orderType: OrderType) -> Decimal {
        // 指値單就當成 maker，成行單就當成 taker
        orderType == .limit ? makerRate : takerRate
    }

    static func run(_ log: LabLog) async {
        scenario(log) { fill, type in rate(for: fill, orderType: type) }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func rate(for fill: E01Fill, orderType: OrderType) -> Decimal {
        // 看這一筆成交實際的角色
        fill.role == .maker ? makerRate : takerRate
    }

    static func run(_ log: LabLog) async {
        scenario(log) { fill, type in rate(for: fill, orderType: type) }
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l03 = Lab(
        id: "L03",
        title: "指値買單的手續費算少了",
        question: "第 2 題：訂單簿是什麼？「約定」「部分約定」「maker／taker」是什麼意思？",
        goal: "一張指値單可能同時有 taker（吃板）和 maker（掛單）兩種成交。手續費要依每一筆成交的角色分別計算。",
        steps: [
            "選「Bug 版」執行，看兩筆成交各用了哪個費率、手續費合計。",
            "想一想：價格跨過對手的最佳價時，那一筆成交是吃板還是掛單？指値單是不是一定是 maker？",
            "選「修正版」執行，吃板那一筆應該用 taker 費率。",
            "進階：手續費率以交易所的公告為準，這裡的 0.05%、0.1% 只是演練用的假設。",
        ],
        expectBug: "兩筆成交都用 maker 費率：手續費 1000 + 1500.75，「手續費合計 2500.75 円」。",
        expectFixed:
            "吃板的 0.2 BTC 用 taker 費率（手續費 2000），掛單的 0.3 BTC 用 maker 費率（手續費 1500.75），「手續費合計 3500.75 円」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("手續費合計") }) {
                return "演練沒有跑完：要用 scenario(log, rate:) 執行"
            }
            return lines.contains("手續費合計 2500.75 円")
                ? nil
                : "Bug 還沒重現：指値單（orderType == .limit）每一筆都用 makerRate，合計應該是 2500.75"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("手續費合計") }) {
                return "演練沒有跑完：要用 scenario(log, rate:) 執行"
            }
            return lines.contains("手續費合計 3500.75 円")
                ? nil
                : "要看每一筆成交的 fill.role：maker 用 makerRate，taker 用 takerRate，合計應該是 3500.75"
        }
    )
}
