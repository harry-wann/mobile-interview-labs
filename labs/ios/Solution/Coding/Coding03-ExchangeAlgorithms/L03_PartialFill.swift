// L03：部分成交之後，賣方少了一大截，
// 買方的剩餘數量也不見了（成交後的帳沒有處理好）
// 對應複習站：Coding03ExchangeAlgorithms 第 1 題「撮合引擎怎麼決定誰和誰成交、
// 用什麼價格？」
//
// 情境：賣單簿有 A（100 × 1）、B（101 × 1）、C（103 × 5）。
// 連續三張買單（限價買）：
//       ① 102 × 3：吃掉 A、B，還剩 1 個，應該用自己的限價 102 掛在買方；
//       ② 103 × 2：和 C 成交 2 個，C 是部分成交，還剩 3 個要留在簿上；
//       ③ 103 × 3：把 C 剩下的 3 個吃完。
//       最後應該是：買方只剩第 ① 張剩下的 1 個，賣方空了，共成交 7 個。
// Bug 版：①-1 maker 只要被吃到一點點，就整筆從簿上移除（C 剩下的 3
// 個憑空消失）；①-2 吃不完的數量沒有掛到買方，直接丟掉。
// 修正版：maker 扣掉成交量，數量變 0 才移除（沒吃完的留在簿上、位置不變）；
// 買方吃不完的數量，用自己的限價掛上去，成為新的 maker。
import LabKit

/// 演練流程（兩個版本共用）：連續三張限價買單，每張之後印出兩邊的簿
private func scenario(
    _ log: LabLog,
    buy: (
        _ id: String, _ price: Int, _ qty: Int, _ asks: inout [C03Order],
        _ bids: inout [C03Order]
    ) -> [C03Trade]
) {
    var asks = [
        C03Order(id: "A", price: 100, qty: 1),
        C03Order(id: "B", price: 101, qty: 1),
        C03Order(id: "C", price: 103, qty: 5),
    ]
    var bids: [C03Order] = []
    var total = 0
    for (index, order) in [(102, 3), (103, 2), (103, 3)].enumerated() {
        let trades = buy("T\(index + 1)", order.0, order.1, &asks, &bids)
        total += trades.reduce(0) { $0 + $1.qty }
        log(
            "買進限價 \(order.0) × \(order.1) → 成交 \(c03Text(trades))；買方 \(c03Text(bids))，賣方 \(c03Text(asks))"
        )
    }
    log("最後：買方 \(c03Text(bids))，賣方 \(c03Text(asks))，共成交 \(total) 個")
}

// MARK: - Bug 版
private enum L03Bug {
    static func buy(
        id: String, price: Int, qty: Int, asks: inout [C03Order],
        bids: inout [C03Order]
    ) -> [C03Trade] {
        var remaining = qty
        var trades: [C03Trade] = []
        while remaining > 0, let best = asks.first, best.price <= price {
            let fill = min(remaining, best.qty)
            trades.append(
                C03Trade(maker: best.id, price: best.price, qty: fill))
            remaining -= fill
            // 不管有沒有吃完，整筆 maker 都拿掉
            asks.removeFirst()
        }
        // 吃不完的 remaining 沒有掛到買方
        return trades
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, buy: { buy(id: $0, price: $1, qty: $2, asks: &$3, bids: &$4) })
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func buy(
        id: String, price: Int, qty: Int, asks: inout [C03Order],
        bids: inout [C03Order]
    ) -> [C03Trade] {
        var remaining = qty
        var trades: [C03Trade] = []
        while remaining > 0, let best = asks.first, best.price <= price {
            let fill = min(remaining, best.qty)
            trades.append(
                C03Trade(maker: best.id, price: best.price, qty: fill))
            remaining -= fill
            // maker 扣掉成交量
            asks[0].qty -= fill
            // 數量變 0 才移除；沒吃完的留在簿上
            if asks[0].qty == 0 { asks.removeFirst() }
        }
        if remaining > 0 {
            // 吃不完的部分，用自己的限價掛到買方
            bids.append(C03Order(id: id, price: price, qty: remaining))
        }
        return trades
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, buy: { buy(id: $0, price: $1, qty: $2, asks: &$3, bids: &$4) })
    }
}

// MARK: - 演練設定
extension Coding03ExchangeAlgorithms {
    static let l03 = Lab(
        id: "L03",
        title: "部分成交後，賣方憑空少了一截",
        question: "第 1 題：撮合引擎怎麼決定誰和誰成交、用什麼價格？",
        goal: "部分成交後要把帳算清楚：maker 扣掉成交量、數量變 0 才移除；買方吃不完的數量掛到簿上。",
        steps: [
            "選「Bug 版」執行，看第 2 張買單之後賣方的 C 還剩幾個（應該剩 3 個），以及第 1 張買單剩下的 1 個到哪去了。",
            "選「修正版」執行，確認最後買方剩 [T1 102×1]，賣方空了，共成交 7 個。",
            "想一想：maker 部分成交後，為什麼它的排隊位置不能變？（時間優先）",
        ],
        expectBug:
            "第 1 張買單剩下的 1 個消失（買方一直是 []）；C 被吃 2 個就整筆移除，第 3 張買單吃不到：「最後：買方 []，賣方 []，共成交 4 個」。",
        expectFixed: "「最後：買方 [T1 102×1]，賣方 []，共成交 7 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, buy:) 執行"
            }
            if !lines.contains(where: {
                $0.hasPrefix("買進限價 102 × 3 → 成交 [A:1@100, B:1@101]")
            }) {
                return "buy 還沒寫：價格夠的賣單要依序成交，成交價用 best.price"
            }
            return lines.contains("最後：買方 []，賣方 []，共成交 4 個")
                ? nil
                : "Bug 還沒重現：maker 被吃到之後整筆 asks.removeFirst()，吃不完的 remaining 也不要掛到買方"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, buy:) 執行"
            }
            if !lines.contains(where: {
                $0.hasPrefix(
                    "買進限價 102 × 3 → 成交 [A:1@100, B:1@101]；買方 [T1 102×1]")
            }) {
                return "第 1 張買單吃完 A、B 還剩 1 個，要用自己的限價 102 掛到買方（bids）"
            }
            return lines.contains("最後：買方 [T1 102×1]，賣方 []，共成交 7 個")
                ? nil
                : "C 被吃 2 個要剩 3 個留在簿上：asks[0].qty -= fill，變 0 才 removeFirst()"
        }
    )
}
