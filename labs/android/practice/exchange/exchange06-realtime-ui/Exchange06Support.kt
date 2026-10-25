// Exchange06RealtimeUI 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：從字串建立 BigDecimal 的簡寫。
package tw.harry.labs.practice.exchange06realtimeui

import java.math.BigDecimal

/** 從字串建立 BigDecimal（價格不經過 Double） */
fun e06(text: String) = BigDecimal(text)
