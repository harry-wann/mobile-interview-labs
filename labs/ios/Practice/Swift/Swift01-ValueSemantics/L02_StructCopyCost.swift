// L02：struct 不一定比較快（複製 struct 時，裡面的 class 欄位都要 retain）
// 對應複習站：Swift01ValueSemantics 第 2 題「struct 一定比 class 快嗎？」
//
// 情境：行情推播每一筆報價都要經過「解析 → 過濾 → 換算」三個函式，
// 每經過一個函式就複製一次。
//
// 你要寫：
//   ① Bug 版：在 Quote 裡加上 4 個 class 欄位（symbol、exchange、tags、meta），
// 量處理 100 萬筆要多久。
//   ② 修正版：Quote 只放數字（幣種、交易所用編號），再量一次。
//   Debug 下差距只有約 1.5 倍；把 Scheme 的 Build Configuration 改成 Release，
// 差距會拉大到 20 倍以上。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift01-ValueSemantics/L02_StructCopyCost.swift
import Foundation
import LabKit

/// 數一個值裡有幾個 class 型別的欄位（已寫好，自動檢查用）
private func classFieldCount(_ value: Any) -> Int {
    Mirror(reflecting: value).children.filter {
        Swift.type(of: $0.value) is AnyClass
    }.count
}

/// 列出一個值的欄位名稱
private func fieldNames(_ value: Any) -> String {
    Mirror(reflecting: value).children.compactMap(\.label).joined(
        separator: ", ")
}

// MARK: - Bug 版
private enum L02Bug {
    // 4 個 class（已寫好）
    final class SymbolInfo { let name = "BTC/JPY" }
    final class ExchangeInfo { let name = "Example Exchange" }
    final class Tags { let values = ["spot"] }
    final class Meta { let source = "ws" }

    /// 每一筆報價
    struct Quote {
        // TODO ①-1：加上 4 個欄位：symbol: SymbolInfo、exchange: ExchangeInfo、
        // tags: Tags、meta: Meta
        var price: Int64
        var qty: Int64
    }

    @inline(never) static func parse(_ q: Quote) -> Quote { q }
    @inline(never) static func filter(_ q: Quote) -> Quote { q }
    @inline(never) static func convert(_ q: Quote) -> Quote {
        var q = q
        q.price = q.price * 100
        return q
    }

    static func run(_ log: LabLog) async {
        // TODO ①-2：加了欄位之後，這裡也要傳入 SymbolInfo()、ExchangeInfo()、
        // Tags()、Meta()
        let base = Quote(price: 0, qty: 1)
        log("Quote 的欄位：\(fieldNames(base))；class 欄位 \(classFieldCount(base)) 個")
        let ms = await Task.detached { () -> Int in
            let start = DispatchTime.now()
            var total: Int64 = 0
            for i in 0..<1_000_000 {
                var q = base
                q.price = Int64(i)
                // 每經過一個函式就複製一次
                q = convert(filter(parse(q)))
                total &+= q.price
            }
            _ = total
            return Int(
                (DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds)
                    / 1_000_000)
        }.value
        log("處理 100 萬筆報價：\(ms) ms")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// 每一筆報價
    struct Quote {
        // TODO ②-1：只放數字：symbolID: Int32、exchangeID:
        // Int16（要顯示名稱時再查表）
        var price: Int64
        var qty: Int64
    }

    @inline(never) static func parse(_ q: Quote) -> Quote { q }
    @inline(never) static func filter(_ q: Quote) -> Quote { q }
    @inline(never) static func convert(_ q: Quote) -> Quote {
        var q = q
        q.price = q.price * 100
        return q
    }

    static func run(_ log: LabLog) async {
        // TODO ②-2：加了欄位之後，這裡也要傳入 symbolID、exchangeID（例如都填
        // 1）
        let base = Quote(price: 0, qty: 1)
        log("Quote 的欄位：\(fieldNames(base))；class 欄位 \(classFieldCount(base)) 個")
        let ms = await Task.detached { () -> Int in
            let start = DispatchTime.now()
            var total: Int64 = 0
            for i in 0..<1_000_000 {
                var q = base
                q.price = Int64(i)
                q = convert(filter(parse(q)))
                total &+= q.price
            }
            _ = total
            return Int(
                (DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds)
                    / 1_000_000)
        }.value
        log("處理 100 萬筆報價：\(ms) ms")
    }
}

// MARK: - 演練設定
extension Swift01ValueSemantics {
    static let l02 = Lab(
        id: "L02",
        title: "struct 裡放 class，複製就變慢",
        question: "第 2 題：struct 一定比 class 快嗎？",
        goal: "同樣處理 100 萬筆報價，只差在 struct 裡有沒有 class 欄位。量量看差多少，並說明原因。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，記下毫秒數。",
            "寫 TODO ②，選「修正版」執行，比較毫秒數。",
            "把 Scheme 的 Build Configuration 改成 Release，兩個版本再各量一次。",
            "進階：用 Instruments 的 Time Profiler 錄 Bug 版，找 swift_retain／swift_release 占了多少時間。",
        ],
        expectBug:
            "比修正版慢。Debug 下大約慢 1.5 倍；Release 下差到 20 倍以上（本機實測約 51 ms 對 2 ms）。多出來的時間花在 retain／release。",
        expectFixed: "Quote 裡沒有 class 欄位，複製只是搬數字，幾乎不花時間。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains { $0.hasSuffix("class 欄位 4 個") }
                ? nil
                : "Bug 版的 Quote 要有 4 個 class 欄位（symbol、exchange、tags、meta）"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasSuffix("class 欄位 0 個") }) {
                return "修正版的 Quote 不要有 class 欄位，只放數字"
            }
            if !lines.contains(where: { $0.contains("symbolID") }) {
                return "修正版的 Quote 要用 symbolID、exchangeID 這種編號代替 class 欄位"
            }
            return nil
        }
    )
}
