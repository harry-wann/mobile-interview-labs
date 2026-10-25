// Exchange08OrderFlow 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、去掉尾端 0 的文字。
package tw.harry.labs.solution.exchange08orderflow

import java.math.BigDecimal

/** 從字串建立 BigDecimal（價格與數量不經過 Double） */
fun e08(text: String) = BigDecimal(text)

/** 去掉尾端的 0，轉成不含科學記號的文字：BigDecimal("2.0") → "2" */
fun e08Text(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()
