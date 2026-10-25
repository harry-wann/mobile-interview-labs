// L05：合併之後，最佳買價比真實的高，最佳賣價比真實的低（合併價格間隔時，買賣方向都用四捨五入）
// 對應複習站：Exchange05OrderBook 第 4 題「依價格間隔（step）合併時，買方、賣方各往哪個方向取整？」
//
// 情境：App 自己把價格合併成每 1,000 円一檔（step = 1000），同一檔的數量相加。
//       真實的買方：4,860,620×0.2、4,860,120×0.5、4,859,900×0.1（最佳買價 4,860,620）；
//       真實的賣方：4,861,120×0.3、4,861,850×0.4、4,862,001×0.2（最佳賣價 4,861,120）。
//       合併的原則：不能讓使用者以為成交條件比真實更有利（買價不能比真實高，賣價不能比真實低）。
//
// 你要寫：
//   ① Bug 版（L05Bug.run）：買方、賣方都用四捨五入（.plain）。
//   ② 修正版（L05Fixed.run）：買方（isBid 是 true）往下取整（.down），賣方往上取整（.up）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange05-OrderBook/L05_StepDirection.swift
import Foundation
import LabKit

/// 把價格取整到 step 的倍數；`mode` 決定往哪個方向取整
private func bucket(_ price: Decimal, step: Decimal, mode: NSDecimalNumber.RoundingMode) -> Decimal {
    var q = price / step                        // 有幾個 step
    var r = Decimal()
    NSDecimalRound(&r, &q, 0, mode)             // 去掉小數
    return r * step
}

/// 合併：同一檔的數量相加，最佳價排在最前面
private func aggregate(_ levels: [E05Level], step: Decimal, isBid: Bool, mode: (Bool) -> NSDecimalNumber.RoundingMode) -> [E05Level] {
    var merged: [Decimal: Decimal] = [:]
    for level in levels {
        merged[bucket(level.price, step: step, mode: mode(isBid)), default: 0] += level.qty
    }
    let result = merged.map { E05Level(price: $0.key, qty: $0.value) }
    return isBid ? result.sorted { $0.price > $1.price } : result.sorted { $0.price < $1.price }
}

private func describe(_ levels: [E05Level]) -> String {
    levels.map { "\($0.price)×\($0.qty)" }.joined(separator: "、")
}

/// 演練流程（兩個版本共用）：用 `mode`（買方或賣方各用什麼取整方式）合併，再和真實的板比較
private func scenario(_ log: LabLog, mode: @escaping (Bool) -> NSDecimalNumber.RoundingMode) {
    let step = e05("1000")
    let bids = [E05Level("4860620", "0.2"), E05Level("4860120", "0.5"), E05Level("4859900", "0.1")]
    let asks = [E05Level("4861120", "0.3"), E05Level("4861850", "0.4"), E05Level("4862001", "0.2")]
    let mergedBids = aggregate(bids, step: step, isBid: true, mode: mode)
    let mergedAsks = aggregate(asks, step: step, isBid: false, mode: mode)
    log("合併後的買方：\(describe(mergedBids))")
    log("合併後的賣方：\(describe(mergedAsks))")
    let bestBid = mergedBids[0].price, bestAsk = mergedAsks[0].price
    log("最後：最佳買 \(bestBid)（真實 4860620），最佳賣 \(bestAsk)（真實 4861120）")
    log("買價\(bestBid > e05("4860620") ? "高於" : "不高於")真實，賣價\(bestAsk < e05("4861120") ? "低於" : "不低於")真實，\(bestBid >= bestAsk ? "是" : "不是")交叉簿")
}

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { _ in
            // TODO ①：買方、賣方都用同一種取整方式：四捨五入（.plain）。目前的 .down 只是佔位，要改掉
            .down
        }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { isBid in
            // TODO ②：買方（isBid 是 true）往下取整（.down），賣方往上取整（.up）。目前的寫法只是佔位，要改掉
            .down
        }
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l05 = Lab(
        id: "L05",
        title: "合併之後買價變高、賣價變低",
        question: "第 4 題：依價格間隔（step）合併時，買方、賣方各往哪個方向取整？",
        goal: "合併價格間隔時，買方往下取整、賣方往上取整，合併後的最佳買價不會比真實高，最佳賣價不會比真實低。先寫出買賣都四捨五入的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange05-OrderBook/L05_StepDirection.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看合併後的最佳買價與最佳賣價，和真實的最佳價比一比。",
            "最後一行：最佳買價是不是大於等於最佳賣價？這代表板變成什麼狀態？",
            "寫 TODO ②（修正版），選「修正版」執行，最佳買要是 4860000，最佳賣要是 4862000。",
            "想一想：如果伺服器用 `type=step1` 幫你合併，取整方向是什麼？（文件沒寫，未證實，要用實際資料確認）",
        ],
        expectBug: "合併後最佳買 4861000、最佳賣 4861000，「買價高於真實，賣價低於真實，是交叉簿」。",
        expectFixed: "合併後買方 4860000×0.7、4859000×0.1，賣方 4862000×0.7、4863000×0.2，最佳買 4860000、最佳賣 4862000，「買價不高於真實，賣價不低於真實，不是交叉簿」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, mode:) 執行" }
            return lines.contains("買價高於真實，賣價低於真實，是交叉簿")
                ? nil : "Bug 還沒重現：買方、賣方都用四捨五入（.plain），4,860,620 會變成 4,861,000，4,861,120 也變成 4,861,000"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, mode:) 執行" }
            if !lines.contains("合併後的買方：4860000×0.7、4859000×0.1") { return "買方要往下取整（.down）：4,860,620 和 4,860,120 都歸到 4,860,000，合計 0.7" }
            return lines.contains("合併後的賣方：4862000×0.7、4863000×0.2")
                ? nil : "賣方要往上取整（.up）：4,861,120 和 4,861,850 都歸到 4,862,000，合計 0.7"
        }
    )
}
