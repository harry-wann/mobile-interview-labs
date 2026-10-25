// Advanced04 共用的小工具。型別與函式名稱都以 A04 開頭：同一個 module 裡還有其他單元，不能重名。
import Foundation

/// 從 log 找出以 prefix 開頭的第一行，取出後面接的數字
func a04Number(in lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else { return nil }
    let digits = line.dropFirst(prefix.count).prefix { $0.isNumber || $0 == "." || $0 == "-" }
    return Double(digits)
}

/// 單調時鐘（毫秒）
func a04NowMs() -> Double { Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000 }
