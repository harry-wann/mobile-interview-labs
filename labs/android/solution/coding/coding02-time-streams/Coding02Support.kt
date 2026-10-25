// Coding02TimeStreams 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：帶時間的事件、可以手動快轉的假時鐘、計數器、一個寫好的 debounce、
// 把結果印成一行文字。
package tw.harry.labs.solution.coding02timestreams

/**
 * 一個帶時間的輸入事件（時間單位是毫秒）。例如搜尋框在第 50 毫秒的內容是 "bt"
 */
data class C02Event(val time: Int, val value: String)

/** 一筆帶時間的價格（時間單位是毫秒、價格單位是円）。行情、成交都用它 */
data class C02Tick(val time: Int, val price: Int)

/** 假時鐘：時間只會在你叫它前進時才前進，測試不用真的等 */
class C02FakeClock {
    var now = 0
        private set

    fun advanceTo(time: Int) {
        now = time
    }
}

/** 數「處理了幾次」（演練用來看複雜度） */
class C02Counter {
    var steps = 0
}

/**
 * 已經寫好的 debounce（純函式，時間是輸入）：下一筆在 wait 之內到達，
 * 這一筆就被取消；否則在 time + wait 送出；最後一筆一定送出
 */
fun c02Debounce(ticks: List<C02Tick>, wait: Int): List<C02Tick> {
    val result = ArrayList<C02Tick>()
    for ((index, tick) in ticks.withIndex()) {
        val isLast = index == ticks.size - 1
        if (isLast || ticks[index + 1].time - tick.time >= wait) {
            result.add(C02Tick(tick.time + wait, tick.price))
        }
    }
    return result
}

/** 把事件清單印成一行：[(150, bt), (400, btc)] */
fun c02Text(events: List<C02Event>): String =
    events.joinToString(", ", "[", "]") { "(${it.time}, ${it.value})" }
