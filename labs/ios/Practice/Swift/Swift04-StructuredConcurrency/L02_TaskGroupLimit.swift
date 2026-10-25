// L02：一次抓 30 個交易對的行情，30 個請求同時送出（TaskGroup
// 沒有限制同時進行的數量）
// 對應複習站：Swift04StructuredConcurrency 第 2 題「async let 和 TaskGroup
// 怎麼選？怎麼限制同時進行的請求數量？」
//
// 情境：自選清單有 30 個交易對，要各抓一次 ticker。交易所的公開 API
// 有流量限制，不能同時送出幾十個請求。
//
// 你要寫：
//   ① Bug 版：fetchAll 用 withTaskGroup，一口氣把 30 個交易對都 addTask，再用
// for await 把結果收進字典。
//   ② 修正版：fetchAll 用滑動視窗：先放進 limit 個任務，每完成一個（group.
// next()）就補進下一個，直到全部抓完。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift04-StructuredConcurrency/L02_TaskGroupLimit.swift
import Foundation
import LabKit

/// 假的 ticker API：每次花 50 毫秒，並記錄同時有幾個請求在進行（兩個版本共用）
private func fetchTicker(
    _ symbol: String, meter: Swift04StructuredConcurrency.ConcurrencyMeter
) async -> Double {
    meter.enter()
    defer { meter.leave() }
    try? await Task.sleep(for: .milliseconds(50))
    return Double(symbol.count)
}

private let symbols = (1...30).map { "COIN\($0)/JPY" }

// MARK: - Bug 版
private enum L02Bug {
    static func fetchAll(
        _ symbols: [String],
        meter: Swift04StructuredConcurrency.ConcurrencyMeter
    ) async -> [String: Double] {
        // TODO ①：withTaskGroup(of: (String, Double).self)：對每個 symbol 都
        // group.addTask { (symbol, await fetchTicker(symbol, meter: meter)) }，
        //        再用 for await 把結果收進字典回傳
        return [:]
    }

    static func run(_ log: LabLog) async {
        let meter = Swift04StructuredConcurrency.ConcurrencyMeter()
        let result = await fetchAll(symbols, meter: meter)
        log("收到 \(result.count) 筆行情")
        log("同時進行的請求最多 \(meter.peak) 個")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func fetchAll(
        _ symbols: [String], limit: Int,
        meter: Swift04StructuredConcurrency.ConcurrencyMeter
    ) async -> [String: Double] {
        // TODO ②：withTaskGroup 滑動視窗：用 symbols.makeIterator() 取下一個
        // symbol；
        //        先放進 limit 個任務；再用 while let (symbol, price) = await
        // group.next() 收結果，每收到一個就補進下一個（如果還有）
        return [:]
    }

    static func run(_ log: LabLog) async {
        let meter = Swift04StructuredConcurrency.ConcurrencyMeter()
        let result = await fetchAll(symbols, limit: 5, meter: meter)
        log("收到 \(result.count) 筆行情")
        log("同時進行的請求最多 \(meter.peak) 個")
    }
}

// MARK: - 演練設定
private func peakInFlight(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "同時進行的請求最多 "
        return line.hasPrefix(prefix)
            ? Int(line.dropFirst(prefix.count).dropLast(2)) : nil
    }
}

extension Swift04StructuredConcurrency {
    static let l02 = Lab(
        id: "L02",
        title: "30 個請求同時送出，被交易所擋下",
        question: "第 2 題：async let 和 TaskGroup 怎麼選？怎麼限制同時進行的請求數量？",
        goal: "要抓 30 個交易對的行情，但同時進行的請求不能超過 5 個。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：同時進行的請求最多幾個，出現 ✓。",
            "寫 TODO ②，選「修正版」執行：收到的行情還是 30 筆，同時進行的請求最多 5 個。",
            "想一想：限制「同時進行的數量」就等於「每秒最多 10 次」嗎？（不等於，還需要自己控制發送間隔）",
        ],
        expectBug: "收到 30 筆行情，同時進行的請求最多 30 個。",
        expectFixed: "收到 30 筆行情，同時進行的請求最多 5 個。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("收到 30 筆行情") else { return "30 個交易對都要抓到" }
            guard let peak = peakInFlight(lines) else { return "找不到同時進行的數量" }
            return peak >= 20
                ? nil : "Bug 還沒重現：同時進行的請求只有 \(peak) 個（要一次 addTask 30 個）"
        },
        checkFixed: { lines in
            guard lines.contains("收到 30 筆行情") else {
                return "30 個交易對都要抓到：每完成一個要補進下一個，直到全部抓完"
            }
            guard let peak = peakInFlight(lines) else { return "找不到同時進行的數量" }
            return peak <= 5
                ? nil : "同時進行的請求有 \(peak) 個，超過限制的 5 個：先只放 limit 個，每完成一個再補一個"
        }
    )
}
