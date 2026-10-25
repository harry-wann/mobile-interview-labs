// Coding03ExchangeAlgorithms 共用的小工具（和 bug 本身無關，已經寫好，
// 不用改）。
// 內容：掛單、成交、K 線，以及把它們印成一行文字的函式。
// 價格與數量都用整數（價格單位是円）。
import Foundation

/// 簿上的一筆掛單。qty 是還沒成交的剩餘數量
struct C03Order: Equatable {
    let id: String
    let price: Int
    var qty: Int
}

/// 一筆成交：和哪一筆掛單（maker）成交、成交價、數量
struct C03Trade: Equatable {
    let maker: String
    let price: Int
    let qty: Int
}

/// 一根 K 線（時間單位是秒）
struct C03Candle: Equatable {
    let time: Int
    let open: Int
    let high: Int
    let low: Int
    let close: Int
    let volume: Int
}

/// 數「探測了幾次」（演練用來看二分搜尋的成本）
final class C03Probe {
    var count = 0
}

/// 掛單印成一行：[A 100×1, B 101×1]
func c03Text(_ orders: [C03Order]) -> String {
    "["
        + orders.map { "\($0.id) \($0.price)×\($0.qty)" }.joined(
            separator: ", ") + "]"
}

/// 成交印成一行：[A:1@100, B:1@101]（掛單編號:數量@成交價）
func c03Text(_ trades: [C03Trade]) -> String {
    "["
        + trades.map { "\($0.maker):\($0.qty)@\($0.price)" }.joined(
            separator: ", ") + "]"
}
