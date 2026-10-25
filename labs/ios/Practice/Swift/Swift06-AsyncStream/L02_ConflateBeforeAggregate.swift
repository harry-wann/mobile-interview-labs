// L02：K 線的最高價比真正的最高價低（先 conflate 才聚合，最高點被丟掉了）
// 對應複習站：Swift06AsyncStream 第 2 題「行情來得比畫面處理得快時怎麼辦？」
//
// 情境：一分鐘內連續收到 5 筆成交：價格 100、101、150、102、103，成交量 1、2、
// 3、1、2。
//       畫面要顯示最新價，K 線要算這一分鐘的最高價（真正是 150）
// 和成交量合計（真正是 9）。
//
// 你要寫：
//   ① Bug 版：把五筆成交 yield 進 bufferingNewest(1) 的 Trade stream，再
// finish；消費端用 for await 讀出來，邊讀邊算最高價、成交量合計和最新價。
//   ② 修正版：每筆成交先累積進最高價與成交量，再把成交價 yield 進
// bufferingNewest(1) 的 Int stream；finish 後讀出最新價。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift06-AsyncStream/L02_ConflateBeforeAggregate.swift
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
        // TODO ①-1：建立 AsyncStream.makeStream(of: Trade.self,
        // bufferingPolicy: bufferingNewest(1))，
        //          把 trades 全部 yield 進去（消費者還沒開始讀），再 finish()
        var high = 0
        var volume = 0
        var latest = 0
        // TODO ①-2：用 for await 讀出 stream 裡的 trade，每筆更新
        // high（取較大）、volume（累加）、latest（最新價）
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
        // TODO ②：走過每筆成交：先更新 high、volume（聚合），再 continuation.
        // yield(trade.price)（conflate）；最後 finish()

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
            "寫 TODO ①，選「練習」「Bug 版」執行，看兩行 log：畫面最新價是對的，K 線最高價與成交量卻不對（真正是 150 和 9），出現 ✓。",
            "想一想：.bufferingNewest(1) 的緩衝區滿了之後，丟掉的是哪一筆？150 那一筆什麼時候被丟掉的？",
            "寫 TODO ②，選「修正版」執行：K 線最高價 150、成交量 9，畫面最新價還是 103。",
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
