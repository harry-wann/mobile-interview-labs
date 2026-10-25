// Exchange02Precision 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
import Foundation

/// 從字串建立 Decimal（金額與數量不經過 Double）
func e02(_ text: String) -> Decimal { Decimal(string: text)! }
