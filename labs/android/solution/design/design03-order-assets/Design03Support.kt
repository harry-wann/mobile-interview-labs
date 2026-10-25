// Design03OrderAssets 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：把日圓金額加上千分位，讓 log 好讀。
package tw.harry.labs.solution.design03orderassets

import java.text.NumberFormat
import java.util.Locale

/** 日圓金額加上千分位，例如 1000000 → "1,000,000" */
fun d03Yen(amount: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(amount)
