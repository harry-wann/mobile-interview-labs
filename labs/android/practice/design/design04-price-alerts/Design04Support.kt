// Design04PriceAlerts 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：把日圓價格加上千分位，讓 log 好讀。
package tw.harry.labs.practice.design04pricealerts

import java.text.NumberFormat
import java.util.Locale

/** 日圓價格加上千分位，例如 10000000 → "10,000,000" */
fun d04Yen(amount: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(amount)
