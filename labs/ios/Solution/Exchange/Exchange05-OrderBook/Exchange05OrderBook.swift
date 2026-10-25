// Exchange05OrderBook：訂單簿
import LabKit

enum Exchange05OrderBook {
    static let unit = LabUnit(
        id: "exchange-order-book",
        code: "Exchange05OrderBook",
        title: "訂單簿",
        // 新增演練時，把 l01、l02… 加進來
        labs: [l01, l02, l03, l04, l05, l06, l07, l08]
    )
}
