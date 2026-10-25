// Exchange01Orders 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、一個只有賣板的假訂單簿（買單從最便宜的賣價開始吃）。
package tw.harry.labs.solution.exchange01orders

import java.math.BigDecimal
import java.math.RoundingMode

/** 從字串建立 BigDecimal（金額與數量不經過 Double） */
fun e01(text: String): BigDecimal = BigDecimal(text)

/**
 * 把 BigDecimal 轉成不帶科學記號、沒有多餘 0 的文字（"2000000.0" → "2000000"）
 */
fun e01Text(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

/** 成交的角色：TAKER 是吃掉板上既有的單，MAKER 是掛在板上等人來吃 */
enum class E01Role { TAKER, MAKER }

/** 一筆成交 */
class E01Fill(val qty: BigDecimal, val price: BigDecimal, val role: E01Role)

/** 板上的一個價位 */
class E01Level(val price: BigDecimal, var qty: BigDecimal)

/** 只有賣板的假訂單簿，價格由低排到高 */
class E01Book(val asks: List<E01Level>) {
    /**
     * 買單從最便宜的價位開始吃。`limit` 不是 null 時（指値），價格高於 limit 的價位不吃。
     * 吃到的成交價是「板上的價格」，不是使用者的委託價；這些成交都是 taker。
     */
    fun takeAsks(qty: BigDecimal, limit: BigDecimal? = null): List<E01Fill> {
        var remaining = qty
        val fills = mutableListOf<E01Fill>()
        for (level in asks) {
            if (remaining.signum() <= 0) break
            if (limit != null && level.price > limit) break
            val take = remaining.min(level.qty)
            if (take.signum() <= 0) continue
            fills += E01Fill(take, level.price, E01Role.TAKER)
            level.qty -= take
            remaining -= take
        }
        return fills
    }
}
