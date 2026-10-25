// Exchange02Precision 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
package tw.harry.labs.solution.exchange02precision

import java.math.BigDecimal

/** 從字串建立 BigDecimal（金額與數量不經過 Double） */
fun e02(text: String): BigDecimal = BigDecimal(text)

/**
 * 把 BigDecimal 轉成不帶科學記號、沒有多餘 0 的文字（"0.1000" → "0.1"、"100.
 * 00000000" → "100"）
 */
fun e02Text(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()
