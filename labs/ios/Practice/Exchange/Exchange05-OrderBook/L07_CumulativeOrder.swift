// L07：最佳買價的深度條最長（累計數量從錯的一端開始加）
// 對應複習站：Exchange05OrderBook 第 5 題「累計數量和深度條怎麼算？」
//
// 情境：深度圖的每一列顯示「累計數量」：從最佳價位開始，往外一路加總，
// 意思是「要吃到這個價位，總共需要多少量」。
//       深度條的寬度 = 該列的累計量 ÷ 兩邊最大的累計量。買方 9,995,000×0.2、9,
// 990,000×0.6；賣方 10,010,000×0.1、10,020,000×0.5。
//       畫面由上到下，最佳價在最上面：賣方由低價排到高價，買方由高價排到低價。
//       正確的買方：9995000 累計 0.2（25%）、9990000 累計 0.8（100%）；賣方：
// 10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）。
//
// 你要寫：
//   ① Bug 版（L07Bug.run）：買方也依價格由低排到高（$0.sorted { $0.price < $1.
// price }）。
//   ② 修正版（L07Fixed.run）：買方依價格由高排到低（$0.sorted { $0.price > $1.
// price }），最佳買價排第一。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange05-OrderBook/L07_CumulativeOrder.swift
import Foundation
import LabKit

private func levels(_ prices: [Decimal: Decimal]) -> [E05Level] {
    prices.map { E05Level(price: $0.key, qty: $0.value) }
}

/// 累計：由第一列開始，往下一路加總
private func cumulative(_ levels: [E05Level]) -> [Decimal] {
    var running: Decimal = 0
    return levels.map {
        running += $0.qty; return running
    }
}

private func describe(_ levels: [E05Level], maxCumulative: Decimal) -> String {
    zip(levels, cumulative(levels)).map { level, total in
        "\(level.price) 累計 \(total)（\(total / maxCumulative * 100)%）"
    }.joined(separator: "、")
}

/// 演練流程（兩個版本共用）：賣方由低價排到高價；買方怎麼排，由 `orderBids`
/// 決定
private func scenario(_ log: LabLog, orderBids: ([E05Level]) -> [E05Level]) {
    var depth = E05Depth()
    depth.apply(
        bids: [E05Level("9995000", "0.2"), E05Level("9990000", "0.6")],
        asks: [E05Level("10010000", "0.1"), E05Level("10020000", "0.5")])
    let asks = levels(depth.asks).sorted { $0.price < $1.price }
    let bids = orderBids(levels(depth.bids))
    // 兩邊最大的累計量：0.8
    let maxCumulative = max(cumulative(asks).last!, cumulative(bids).last!)
    log("賣方（由上到下）：\(describe(asks, maxCumulative: maxCumulative))")
    log("買方（由上到下）：\(describe(bids, maxCumulative: maxCumulative))")
    log("最後：買方第一列是 \(bids[0].price)，真正的最佳買價是 \(depth.bestBid!.price)")
}

// MARK: - Bug 版
private enum L07Bug {
    static func run(_ log: LabLog) async {
        scenario(log) {
            // TODO ①：買方也依價格由低排到高：$0.sorted { $0.price < $1.price
            // }。目前的由高到低只是佔位，要改掉
            $0.sorted { $0.price > $1.price }
        }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) {
            // TODO ②：買方依價格由高排到低，最佳買價排第一：$0.sorted { $0.
            // price > $1.price }。目前的由低到高只是佔位，要改掉
            $0.sorted { $0.price < $1.price }
        }
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l07 = Lab(
        id: "L07",
        title: "最佳買價的深度條最長",
        question: "第 5 題：累計數量和深度條怎麼算？",
        goal:
            "累計數量要從最佳價位開始往外加。買方最佳價是最高價，所以買方要由高價排到低價再累計。先寫出買方排成由低到高的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange05-OrderBook/L07_CumulativeOrder.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看買方第一列（畫面最上面、應該是最佳買價）的累計與深度條。",
            "最後一行：買方第一列是不是真正的最佳買價？",
            "寫 TODO ②（修正版），選「修正版」執行，買方第一列要是 9995000，累計 0.2，深度條 25%。",
            "想一想：預估市價單的成交價時，也是從最佳價位往外吃。排錯順序會算出什麼價格？",
        ],
        expectBug:
            "買方第一列是 9990000，累計 0.6（75%），最佳買價 9995000 排在最後，累計 0.8（100%）；「買方第一列是 9990000，真正的最佳買價是 9995000」。",
        expectFixed:
            "買方「9995000 累計 0.2（25%）、9990000 累計 0.8（100%）」，賣方「10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, orderBids:) 執行"
            }
            return lines.contains(
                "買方（由上到下）：9990000 累計 0.6（75%）、9995000 累計 0.8（100%）")
                ? nil
                : "Bug 還沒重現：買方也要由低價排到高價（sorted { $0.price < $1.price }），最佳買價 9,995,000 就會排到最後"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, orderBids:) 執行"
            }
            if !lines.contains(
                "賣方（由上到下）：10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）")
            {
                return "賣方原本就對：由低價排到高價"
            }
            return lines.contains(
                "買方（由上到下）：9995000 累計 0.2（25%）、9990000 累計 0.8（100%）")
                ? nil
                : "買方要由高價排到低價（sorted { $0.price > $1.price }），最佳買價 9,995,000 排第一，累計 0.2"
        }
    )
}
