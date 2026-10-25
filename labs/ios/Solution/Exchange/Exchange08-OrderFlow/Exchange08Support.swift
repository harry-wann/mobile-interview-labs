// Exchange08OrderFlow 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：從字串建立 Decimal 的簡寫。
import Foundation

/// 從字串建立 Decimal（價格與數量不經過 Double）
func e08(_ text: String) -> Decimal { Decimal(string: text)! }
