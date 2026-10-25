// L03：重連補資料之後，成交筆數和成交量反而變少了（去重用的是 27 位數的 `id`，
// 轉成 Double 之後不同的成交變成同一個）
// 對應複習站：Exchange07Kline 第 2 題「怎麼把一筆一筆的成交聚合成 K 線？」
//
// 情境：逐筆成交的 JSON 有兩個編號：`tradeId`（成交編號，一般的整數）和
// `id`（27 位數的數字，例如 137005445109359286410323766）。
//       重連之後收到 5 則：成交編號 1、2、2（重送的同一筆）、3、4；4
// 筆不同的成交，數量 0.2、0.1、0.3、0.05。
//       這 5 則的 `id` 只差最後幾位數，其中成交編號 1、2、3、4 的 `id`
// 各不相同，但超出 Double 能精確表示的範圍。
//       正確答案：去重後 4 筆，數量合計 0.65。
// Bug 版：用 `id`（資料模型把它宣告成 Double，因為 Int64 放不下）當去重的 key。
// 27 位數轉成 Double 只剩約 16 位有效數字，5 則的 `id` 變成同一個值，
//        只留下第一筆：1 筆，數量 0.2。
// 修正版：用 `tradeId`（Int64）當去重的 key。
import Foundation
import LabKit

private struct Trade: Decodable {
    let tradeId: Int64
    // 27 位數：Int64 放不下，一般的 JSON 解析只能轉成 Double
    let id: Double
    let price: Double
    let amount: Double
}

private let json = """
    [{"tradeId":1,"id":137005445109359286410323766,"price":4800000,"amount":0.2},
     {"tradeId":2,"id":137005445109359286410323767,"price":4790000,"amount":0.1},
     {"tradeId":2,"id":137005445109359286410323767,"price":4790000,"amount":0.1},
     {"tradeId":3,"id":137005445109359286410323768,"price":4810000,"amount":0.3},
     {"tradeId":4,"id":137005445109359286410323769,"price":4780000,"amount":0.05}]
    """

/// 演練流程（兩個版本共用）：`key` 決定怎麼辨認「同一筆成交」，同樣的 key
/// 只算一次
private func scenario(_ log: LabLog, key: (Trade) -> String) {
    let trades = try! JSONDecoder().decode([Trade].self, from: Data(json.utf8))
    var seen = Set<String>()
    var kept: [Trade] = []
    for trade in trades where seen.insert(key(trade)).inserted {
        kept.append(trade)
    }
    let amount = kept.reduce(Decimal(0)) {
        $0 + Decimal(string: "\($1.amount)")!
    }
    log("收到 \(trades.count) 則，去重後 \(kept.count) 筆，數量合計 \(amount)")
    log("最後：去重後 \(kept.count) 筆，數量合計 \(amount)（正確答案：4 筆，0.65）")
}

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        // 用 27 位數的 id 去重
        scenario(log) { trade in "\(trade.id)" }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        // 用成交編號 tradeId 去重
        scenario(log) { trade in "\(trade.tradeId)" }
    }
}

// MARK: - 演練設定
extension Exchange07Kline {
    static let l03 = Lab(
        id: "L03",
        title: "補資料之後，成交筆數反而變少",
        question: "第 2 題：怎麼把一筆一筆的成交聚合成 K 線？",
        goal:
            "成交去重要用 `tradeId`（REST 是 `trade-id`），不要用逐筆資料裡 27 位數的 `id`：它超出 Int64 和 Double 能精確表示的範圍，不同的成交會變成同一個。",
        steps: [
            "選「Bug 版」執行，看去重後剩幾筆、數量合計多少，和正確答案比一比。",
            "想一想：5 則的 `id` 看起來不一樣（最後一位數不同），為什麼轉成 Double 之後變成同一個？（Double 約 16 位有效數字）",
            "選「修正版」執行，要去重後 4 筆、數量合計 0.65。",
            "進階：非用 `id` 不可的時候，要當字串解析（不經過數字型別）。",
        ],
        expectBug: "「收到 5 則，去重後 1 筆，數量合計 0.2」：不同的成交被當成重複丟掉，連真正重送的那一筆都分不出來。",
        expectFixed: "「收到 5 則，去重後 4 筆，數量合計 0.65」：只丟掉重送的那一筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, key:) 執行"
            }
            return lines.contains(where: { $0.hasPrefix("最後：去重後 1 筆，數量合計 0.2") }
            )
                ? nil
                : "Bug 還沒重現：key 要用 trade.id（Double）轉成的字串，5 則的 id 轉成 Double 之後是同一個值"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, key:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：去重後 4 筆，數量合計 0.65")
            })
                ? nil : "key 要用 trade.tradeId 轉成的字串：成交編號相同的才是重複，應該去重後 4 筆"
        }
    )
}
