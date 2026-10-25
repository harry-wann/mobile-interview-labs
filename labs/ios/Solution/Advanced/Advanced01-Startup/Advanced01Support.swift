// Advanced01 共用的小工具。型別與函式名稱都以 A01 開頭：同一個 module 裡還有其他單元，不能重名。
import Foundation

/// 單調時鐘（毫秒）：量啟動時間用，不受使用者改系統時間影響
enum A01Clock {
    static func nowMs() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000 }
}

/// 模擬「這件事要花 ms 毫秒」：讓目前這條執行緒停住（和主執行緒上同步做事一樣會擋住後面的工作）
func a01Work(_ ms: Int) {
    Thread.sleep(forTimeInterval: Double(ms) / 1000)
}

/// 從 log 找出以 prefix 開頭的第一行，取出後面接的數字，例如 `a01Number(in: lines, prefix: "首幀：")`
func a01Number(in lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else { return nil }
    let body = line.dropFirst(prefix.count)
    let digits = body.prefix { $0.isNumber || $0 == "." || $0 == "-" }
    return Double(digits)
}
