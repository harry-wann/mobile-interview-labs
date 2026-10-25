// Exchange06RealtimeUI 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：從字串建立 Decimal 的簡寫。
import Foundation

/// 從字串建立 Decimal（價格不經過 Double）
func e06(_ text: String) -> Decimal { Decimal(string: text)! }
