// Coding02TimeStreams 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：帶時間的事件、可以手動快轉的假時鐘、計數器、一個寫好的 debounce、
// 把結果印成一行文字。
import Foundation

/// 一個帶時間的輸入事件（時間單位是毫秒）。例如搜尋框在第 50 毫秒的內容是 "bt"
struct C02Event: Equatable {
    let time: Int
    let value: String
}

/// 一筆帶時間的價格（時間單位是毫秒、價格單位是円）。行情、成交都用它
struct C02Tick: Equatable {
    let time: Int
    let price: Int
}

/// 假時鐘：時間只會在你叫它前進時才前進，測試不用真的等
final class C02FakeClock {
    private(set) var now = 0

    func advance(to time: Int) {
        now = time
    }
}

/// 數「處理了幾次」（演練用來看複雜度）
final class C02Counter {
    var steps = 0
}

/// 已經寫好的 debounce（純函式，時間是輸入）：下一筆在 wait 之內到達，
/// 這一筆就被取消；否則在 time + wait 送出；最後一筆一定送出
func c02Debounce(_ ticks: [C02Tick], wait: Int) -> [C02Tick] {
    var result: [C02Tick] = []
    for (index, tick) in ticks.enumerated() {
        let isLast = index == ticks.count - 1
        if isLast || ticks[index + 1].time - tick.time >= wait {
            result.append(C02Tick(time: tick.time + wait, price: tick.price))
        }
    }
    return result
}

/// 把事件清單印成一行：[(150, bt), (400, btc)]
func c02Text(_ events: [C02Event]) -> String {
    "[" + events.map { "(\($0.time), \($0.value))" }.joined(separator: ", ")
        + "]"
}
