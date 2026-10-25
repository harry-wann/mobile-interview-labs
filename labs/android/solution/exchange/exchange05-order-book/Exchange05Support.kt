// Exchange05OrderBook 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、板上的一個價位、一份「價格 → 數量」的買賣板（數量 0 會刪除那一檔）、把板印成一行文字。
package tw.harry.labs.solution.exchange05orderbook

import java.math.BigDecimal
import java.util.TreeMap

/** 從字串建立 BigDecimal（價格與數量不經過 Double） */
fun e05(text: String) = BigDecimal(text)

/** 去掉尾端的 0，轉成不含科學記號的文字：BigDecimal("4860000.0") → "4860000" */
fun e05Text(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

/** 板上的一個價位：某個價格上總共掛了多少數量 */
data class E05Level(val price: BigDecimal, val qty: BigDecimal) {
    /** 從字串建立：E05Level("10000000", "0.4") */
    constructor(price: String, qty: String) : this(BigDecimal(price), BigDecimal(qty))
}

/** 一份買賣板：價格 → 數量。用 TreeMap（比的是數值，100.0 與 100 是同一檔）。`apply` 遇到數量 0 會把那一檔刪掉。 */
class E05Depth {
    val bids = TreeMap<BigDecimal, BigDecimal>()
    val asks = TreeMap<BigDecimal, BigDecimal>()

    val bestBid: E05Level? get() = bids.lastEntry()?.let { E05Level(it.key, it.value) }
    val bestAsk: E05Level? get() = asks.firstEntry()?.let { E05Level(it.key, it.value) }

    fun apply(bids: List<E05Level>, asks: List<E05Level>) {
        for (level in bids) if (level.qty.signum() == 0) this.bids.remove(level.price) else this.bids[level.price] = level.qty
        for (level in asks) if (level.qty.signum() == 0) this.asks.remove(level.price) else this.asks[level.price] = level.qty
    }

    fun copy(): E05Depth {
        val result = E05Depth()
        result.bids.putAll(bids)
        result.asks.putAll(asks)
        return result
    }

    /** 一行文字：最佳買、最佳賣（價格×數量）與各有幾檔。例：最佳買 9995000×0.2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔 */
    val summary: String
        get() = "最佳買 ${text(bestBid)}，最佳賣 ${text(bestAsk)}，買 ${bids.size} 檔，賣 ${asks.size} 檔"

    companion object {
        fun text(level: E05Level?): String = level?.let { "${e05Text(it.price)}×${e05Text(it.qty)}" } ?: "-"
    }
}
