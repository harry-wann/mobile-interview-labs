// Design03OrderAssets 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：把日圓金額加上千分位，讓 log 好讀。
import Foundation

/// 日圓金額加上千分位，例如 1000000 → "1,000,000"
func d03Yen(_ amount: Int) -> String {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.locale = Locale(identifier: "en_US")
    return formatter.string(from: NSNumber(value: amount)) ?? "\(amount)"
}
