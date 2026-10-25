// L02：買方限價 102，成交價卻全部變成 102（成交價用了 taker 的限價）
// 對應複習站：Coding03ExchangeAlgorithms 第 1 題「撮合引擎怎麼決定誰和誰成交、
// 用什麼價格？」
//
// 情境：賣單簿有 A（100 円 × 1）、B（101 円 × 1）、C（103 円 × 5）。
// 使用者下限價買單：102 円 × 3。
//       C 的 103 比限價貴，不成交；A、B 各成交 1 個，剩下 1 個沒吃到。
// Bug 版：成交價寫成買方自己的限價 102。簿上的 A 本來願意用 100 賣，卻被記成
// 102，買方多付、帳也對不起來。
// 修正版：成交價是 maker（先掛在簿上的那一方）的價格：A 用 100、B 用 101。
// 買方的限價只是「最多願意付多少」的上限。
import LabKit

/// 演練流程（兩個版本共用）：用限價 102 買 3 個，看成交價與總共付出多少
private func scenario(
    _ log: LabLog,
    buy: (_ price: Int, _ qty: Int, _ asks: inout [C03Order]) -> [C03Trade]
) {
    var asks = [
        C03Order(id: "A", price: 100, qty: 1),
        C03Order(id: "B", price: 101, qty: 1),
        C03Order(id: "C", price: 103, qty: 5),
    ]
    log("賣單簿：\(c03Text(asks))")
    let trades = buy(102, 3, &asks)
    log("買進限價 102 × 3 → 成交 \(c03Text(trades))")
    log("買方總共付出 \(trades.reduce(0) { $0 + $1.price * $1.qty }) 円")
}

// MARK: - Bug 版
private enum L02Bug {
    static func buy(price: Int, qty: Int, asks: inout [C03Order]) -> [C03Trade]
    {
        var remaining = qty
        var trades: [C03Trade] = []
        while remaining > 0, let best = asks.first, best.price <= price {
            let fill = min(remaining, best.qty)
            // 成交價用了買方自己的限價
            trades.append(C03Trade(maker: best.id, price: price, qty: fill))
            remaining -= fill
            asks[0].qty -= fill
            if asks[0].qty == 0 { asks.removeFirst() }
        }
        return trades
    }

    static func run(_ log: LabLog) async {
        scenario(log, buy: { buy(price: $0, qty: $1, asks: &$2) })
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func buy(price: Int, qty: Int, asks: inout [C03Order]) -> [C03Trade]
    {
        var remaining = qty
        var trades: [C03Trade] = []
        while remaining > 0, let best = asks.first, best.price <= price {
            let fill = min(remaining, best.qty)
            // 成交價是 maker（簿上掛單）的價格
            trades.append(
                C03Trade(maker: best.id, price: best.price, qty: fill))
            remaining -= fill
            asks[0].qty -= fill
            if asks[0].qty == 0 { asks.removeFirst() }
        }
        return trades
    }

    static func run(_ log: LabLog) async {
        scenario(log, buy: { buy(price: $0, qty: $1, asks: &$2) })
    }
}

// MARK: - 演練設定
extension Coding03ExchangeAlgorithms {
    static let l02 = Lab(
        id: "L02",
        title: "買方限價 102，成交價全變成 102",
        question: "第 1 題：撮合引擎怎麼決定誰和誰成交、用什麼價格？",
        goal: "成交價是 maker（簿上先掛的那一方）的價格；買方的限價只決定「最貴願意吃到哪一檔」。",
        steps: [
            "選「Bug 版」執行，看每一筆成交的價格，以及買方總共付出多少円。",
            "選「修正版」執行，對照賣單簿：A 掛 100、B 掛 101，成交價應該是多少？",
            "想一想：市價單為什麼會滑價？（吃到比較貴的價位時，成交價是每一檔各自的價格）",
        ],
        expectBug: "成交 [A:1@102, B:1@102]，買方付出 204 円（多付了 3 円）。",
        expectFixed: "成交 [A:1@100, B:1@101]，買方付出 201 円。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("買進限價 102") }) {
                return "演練沒有跑完：要用 scenario(log, buy:) 執行"
            }
            return lines.contains("買進限價 102 × 3 → 成交 [A:1@102, B:1@102]")
                ? nil : "Bug 還沒重現：成交價要寫成買方自己的限價 price，A、B 才會都變成 102"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("買進限價 102") }) {
                return "演練沒有跑完：要用 scenario(log, buy:) 執行"
            }
            return lines.contains("買進限價 102 × 3 → 成交 [A:1@100, B:1@101]")
                ? nil : "成交價要用簿上掛單（best）自己的價格 best.price，A 是 100、B 是 101"
        }
    )
}
