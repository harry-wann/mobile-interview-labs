// Exchange07Kline 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：十進位數字的簡寫、floor 除法（往負無限大取整）。
import Foundation

/// 從字串建立 Decimal（價格與數量不經過 Double）
func e07(_ text: String) -> Decimal { Decimal(string: text)! }

/// floor 除法：往負無限大取整。Swift 的 `/` 是往 0 取整，值為負時不一樣：-7 / 2 = -3，floorDiv(-7, 2) = -4
func e07FloorDiv(_ a: Int64, _ b: Int64) -> Int64 {
    let q = a / b
    return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
}
