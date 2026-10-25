// Coding03ExchangeAlgorithms 共用的小工具（和 bug 本身無關，已經寫好，
// 不用改）。
// 內容：掛單、成交、K 線，以及把它們印成一行文字的函式。
// 價格與數量都用整數（價格單位是円）。
package tw.harry.labs.solution.coding03exchangealgorithms

/** 簿上的一筆掛單。qty 是還沒成交的剩餘數量 */
data class C03Order(val id: String, val price: Int, var qty: Int)

/** 一筆成交：和哪一筆掛單（maker）成交、成交價、數量 */
data class C03Trade(val maker: String, val price: Int, val qty: Int)

/** 一根 K 線（時間單位是秒） */
data class C03Candle(
    val time: Int,
    val open: Int,
    val high: Int,
    val low: Int,
    val close: Int,
    val volume: Int,
)

/** 數「探測了幾次」（演練用來看二分搜尋的成本） */
class C03Probe {
    var count = 0
}

/** 掛單印成一行：[A 100×1, B 101×1] */
fun c03OrdersText(orders: List<C03Order>): String =
    orders.joinToString(", ", "[", "]") {
        "${it.id} ${it.price}×${it.qty}"
    }

/** 成交印成一行：[A:1@100, B:1@101]（掛單編號:數量@成交價） */
fun c03TradesText(trades: List<C03Trade>): String =
    trades.joinToString(", ", "[", "]") {
        "${it.maker}:${it.qty}@${it.price}"
    }
