// Exchange05OrderBook 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、板上的一個價位、一份「價格 → 數量」的買賣板（數量 0
// 會刪除那一檔）、把板印成一行文字。
import Foundation

/// 從字串建立 Decimal（價格與數量不經過 Double）
func e05(_ text: String) -> Decimal { Decimal(string: text)! }

/// 板上的一個價位：某個價格上總共掛了多少數量
struct E05Level {
    let price: Decimal
    let qty: Decimal

    init(price: Decimal, qty: Decimal) {
        self.price = price
        self.qty = qty
    }

    /// 從字串建立：E05Level("10000000", "0.4")
    init(_ price: String, _ qty: String) {
        self.init(price: e05(price), qty: e05(qty))
    }
}

/// 一份買賣板：價格 → 數量。`apply` 遇到數量 0 會把那一檔刪掉。
struct E05Depth {
    var bids: [Decimal: Decimal] = [:]
    var asks: [Decimal: Decimal] = [:]

    var bestBid: E05Level? {
        bids.max { $0.key < $1.key }.map {
            E05Level(price: $0.key, qty: $0.value)
        }
    }
    var bestAsk: E05Level? {
        asks.min { $0.key < $1.key }.map {
            E05Level(price: $0.key, qty: $0.value)
        }
    }

    mutating func apply(bids newBids: [E05Level], asks newAsks: [E05Level]) {
        for level in newBids {
            bids[level.price] = level.qty == 0 ? nil : level.qty
        }
        for level in newAsks {
            asks[level.price] = level.qty == 0 ? nil : level.qty
        }
    }

    /// 一行文字：最佳買、最佳賣（價格×數量）與各有幾檔。例：最佳買 9995000×0.
    /// 2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔
    var summary: String {
        "最佳買 \(Self.text(bestBid))，最佳賣 \(Self.text(bestAsk))，買 \(bids.count) 檔，賣 \(asks.count) 檔"
    }

    static func text(_ level: E05Level?) -> String {
        level.map { "\($0.price)×\($0.qty)" } ?? "-"
    }
}
