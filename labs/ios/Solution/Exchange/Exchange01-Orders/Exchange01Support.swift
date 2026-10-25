// Exchange01Orders 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、一個只有賣板的假訂單簿（買單從最便宜的賣價開始吃）。
import Foundation

/// 從字串建立 Decimal（金額與數量不經過 Double）
func e01(_ text: String) -> Decimal { Decimal(string: text)! }

/// 成交的角色：taker 是吃掉板上既有的單，maker 是掛在板上等人來吃
enum E01Role { case taker, maker }

/// 一筆成交
struct E01Fill {
    let qty: Decimal
    let price: Decimal
    let role: E01Role
}

/// 板上的一個價位
struct E01Level {
    let price: Decimal
    var qty: Decimal
}

/// 只有賣板的假訂單簿，價格由低排到高
struct E01Book {
    var asks: [E01Level]

    /// 買單從最便宜的價位開始吃。`limit` 不是 nil 時（指値），價格高於 limit
    /// 的價位不吃。
    /// 吃到的成交價是「板上的價格」，不是使用者的委託價；這些成交都是 taker。
    mutating func takeAsks(qty: Decimal, limit: Decimal? = nil) -> [E01Fill] {
        var remaining = qty
        var fills: [E01Fill] = []
        for i in asks.indices where remaining > 0 {
            if let limit, asks[i].price > limit { break }
            let take = min(remaining, asks[i].qty)
            guard take > 0 else { continue }
            fills.append(E01Fill(qty: take, price: asks[i].price, role: .taker))
            asks[i].qty -= take
            remaining -= take
        }
        return fills
    }
}
