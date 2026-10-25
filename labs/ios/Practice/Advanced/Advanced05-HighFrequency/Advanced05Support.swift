// Advanced05 共用的小工具。型別與函式名稱都以 A05 開頭：同一個 module
// 裡還有其他單元，不能重名。
import Foundation

/// 從 log 找出以 prefix 開頭的第一行，取出後面接的數字
func a05Number(in lines: [String], prefix: String) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix(prefix) }) else {
        return nil
    }
    let digits = line.dropFirst(prefix.count).prefix {
        $0.isNumber || $0 == "." || $0 == "-"
    }
    return Double(digits)
}

/// 單調時鐘（毫秒）
func a05NowMs() -> Double {
    Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000
}

/// 一筆行情：交易對與價格。交易對的型別用泛型，演練 5 要用「會數比較次數」
/// 的型別來量演算法
struct A05Tick<Symbol: Hashable>: Equatable {
    var symbol: Symbol
    var price: Int
}
