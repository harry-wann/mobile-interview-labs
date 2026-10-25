// Design04PriceAlerts 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：把日圓價格加上千分位，讓 log 好讀。
import Foundation

/// 日圓價格加上千分位，例如 10000000 → "10,000,000"
func d04Yen(_ amount: Int) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.locale = Locale(identifier: "en_US")
    return formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
}
