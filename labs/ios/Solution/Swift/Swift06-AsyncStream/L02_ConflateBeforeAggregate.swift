// L02：K 線的最高價比真正的最高價低（先 conflate 才聚合，最高點被丟掉了）
// 對應複習站：Swift06AsyncStream 第 2 題「行情來得比畫面處理得快時怎麼辦？」
//
// 情境：一分鐘內連續收到 5 筆成交：價格 100、101、150、102、103，成交量 1、2、
// 3、1、2。
//       畫面要顯示最新價，K 線要算這一分鐘的最高價（真正是 150）
// 和成交量合計（真正是 9）。
// Bug 版：成交先送進 bufferingNewest(1) 的 stream（conflation），消費者再從
// stream 讀出來算最高價與成交量。
//        消費者還沒開始讀的時候，前四筆已經被擠掉，只剩最後一筆：最高價 103、
// 成交量 2。
// 修正版：聚合要在 conflate 之前做：每筆成交一收到就累積進 K 線，
// 再把最新價送進 stream 給畫面。
import Foundation
import LabKit

/// 一筆成交（兩個版本共用）
private struct Trade {
    let price: Int
    let volume: Int
}

private let trades = [
    Trade(price: 100, volume: 1), Trade(price: 101, volume: 2),
    Trade(price: 150, volume: 3),
    Trade(price: 102, volume: 1), Trade(price: 103, volume: 2),
]

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        let (stream, continuation) = AsyncStream.makeStream(
            of: Trade.self, bufferingPolicy: .bufferingNewest(1))
        // 消費者還沒讀，舊的被擠掉
        for trade in trades { continuation.yield(trade) }
        continuation.finish()

        var high = 0
        var volume = 0
        var latest = 0
        // 先 conflate，再聚合
        for await trade in stream {
            high = max(high, trade.price)
            volume += trade.volume
            latest = trade.price
        }
        log("畫面最新價：\(latest)")
        log("K 線最高價 \(high)、成交量 \(volume)")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func run(_ log: LabLog) async {
        let (stream, continuation) = AsyncStream.makeStream(
            of: Int.self, bufferingPolicy: .bufferingNewest(1))
        var high = 0
        var volume = 0
        for trade in trades {
            // 先聚合：每一筆都累積進 K 線
            high = max(high, trade.price)
            volume += trade.volume
            // 再 conflate：畫面只需要最新價
            continuation.yield(trade.price)
        }
        continuation.finish()

        var latest = 0
        for await price in stream { latest = price }
        log("畫面最新價：\(latest)")
        log("K 線最高價 \(high)、成交量 \(volume)")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l02 = Lab(
        id: "L02",
        title: "K 線最高價比真正的最高價低",
        question: "第 2 題：行情來得比畫面處理得快時怎麼辦？",
        goal: "畫面可以只看最新價，但 K 線的最高價和成交量必須算進每一筆成交。找出為什麼聚合的結果不對，並修好。",
        steps: [
            "選「Bug 版」執行，看兩行 log：畫面最新價是對的，K 線最高價與成交量卻不對（真正是 150 和 9）。",
            "想一想：.bufferingNewest(1) 的緩衝區滿了之後，丟掉的是哪一筆？150 那一筆什麼時候被丟掉的？",
            "選「修正版」執行：K 線最高價 150、成交量 9，畫面最新價還是 103。",
        ],
        expectBug: "畫面最新價：103；K 線最高價 103、成交量 2（最高點與前四筆的成交量都被丟掉了）。",
        expectFixed: "畫面最新價：103；K 線最高價 150、成交量 9。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("畫面最新價：103") else { return "畫面最新價應該是 103" }
            return lines.last == "K 線最高價 103、成交量 2"
                ? nil
                : "Bug 還沒重現：聚合應該發生在 stream 的消費端（conflate 之後），最高價 103、成交量 2"
        },
        checkFixed: { lines in
            guard lines.contains("畫面最新價：103") else { return "畫面最新價應該是 103" }
            return lines.last == "K 線最高價 150、成交量 9"
                ? nil : "K 線最高價應該是 150、成交量 9：每一筆成交都要在送進 stream 之前先聚合"
        }
    )
}
