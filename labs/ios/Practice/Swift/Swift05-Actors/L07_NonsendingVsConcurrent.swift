// L07：升級 Swift 6.2 之後，原本在背景跑的 async 函式留在主執行緒，畫面卡住（nonisolated(nonsending) 與 @concurrent）
// 對應複習站：Swift05Actors 第 6 題「Swift 6.2 的預設 MainActor 與 @concurrent 改了什麼？」
//
// 情境：K 線解析函式 parseCandles 是 nonisolated 的 async 函式，從 @MainActor 的畫面呼叫。
//       升級到 Swift 6.2 並啟用 NonisolatedNonsendingByDefault（Xcode 26 的 Approachable Concurrency 會一起打開）之後，
//       nonisolated 的 async 函式預設「留在呼叫者的 actor」，不再自動跳到背景。
//
// 你要寫：
//   ① Bug 版：parseCandles 寫成 nonisolated(nonsending) static func（留在呼叫者的 actor），回傳 (sumCloses(lines), 是否在主執行緒)。
//   ② 修正版：parseCandles 加上 @concurrent（離開呼叫者的 actor），回傳同樣的內容。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L07_NonsendingVsConcurrent.swift
import Foundation
import LabKit

/// 40 萬行收盤價資料（兩個版本共用）
private let candleLines = (0..<400_000).map { "15000\($0 % 1_000).25" }

/// 解析並加總收盤價：重運算（兩個版本共用）
private func sumCloses(_ lines: [String]) -> Double {
    var total: Decimal = 0
    for line in lines { total += Decimal(string: line) ?? 0 }
    return NSDecimalNumber(decimal: total).doubleValue
}

/// 三種 async 函式分別在不在主執行緒：這個專案目前的設定（沒有啟用 NonisolatedNonsendingByDefault）會是 false true false（兩個版本共用）
private nonisolated func plainNonisolated() async -> Bool { Swift05Actors.onMainThread() }
private nonisolated(nonsending) func nonsending() async -> Bool { Swift05Actors.onMainThread() }
private func markedConcurrent() async -> Bool { await concurrentProbe() }
@concurrent private func concurrentProbe() async -> Bool { Swift05Actors.onMainThread() }

// MARK: - Bug 版
private enum L07Bug {
    /// 新預設的行為：留在呼叫者的 actor。呼叫者是主 actor，解析就佔著主執行緒
    // TODO ①：在 static func 前面加 nonisolated(nonsending)；回傳 (sumCloses(lines), Swift05Actors.onMainThread())
    static func parseCandles(_ lines: [String]) async -> (total: Double, onMain: Bool) {
        (0, false)
    }

    @MainActor
    static func run(_ log: LabLog) async {
        log("一般 nonisolated、nonsending、@concurrent 的函式在主執行緒嗎：\(await plainNonisolated()) \(await nonsending()) \(await markedConcurrent())")
        let meter = Swift05Actors.StallMeter()
        meter.start()
        let result = await parseCandles(candleLines)
        let stall = meter.stop()
        log("解析在主執行緒：\(result.onMain)")
        log("主執行緒最長被卡住 \(Int(stall)) 毫秒")
    }
}

// MARK: - 修正版
private enum L07Fixed {
    /// @concurrent：離開呼叫者的 actor，在背景並行執行
    // TODO ②：在 static func 前面加 @concurrent；回傳 (sumCloses(lines), Swift05Actors.onMainThread())
    static func parseCandles(_ lines: [String]) async -> (total: Double, onMain: Bool) {
        (0, true)
    }

    @MainActor
    static func run(_ log: LabLog) async {
        log("一般 nonisolated、nonsending、@concurrent 的函式在主執行緒嗎：\(await plainNonisolated()) \(await nonsending()) \(await markedConcurrent())")
        let meter = Swift05Actors.StallMeter()
        meter.start()
        let result = await parseCandles(candleLines)
        let stall = meter.stop()
        log("解析在主執行緒：\(result.onMain)")
        log("主執行緒最長被卡住 \(Int(stall)) 毫秒")
    }
}

// MARK: - 演練設定
private func stallMs(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "主執行緒最長被卡住 "
        return line.hasPrefix(prefix) ? Int(line.dropFirst(prefix.count).dropLast(" 毫秒".count)) : nil
    }
}

extension Swift05Actors {
    static let l07 = Lab(
        id: "L07",
        title: "升級後 async 函式留在主執行緒，畫面卡住",
        question: "第 6 題：Swift 6.2 的預設 MainActor 與 @concurrent 改了什麼？",
        goal: "從 @MainActor 的畫面呼叫的解析函式，在新的預設行為下會留在主執行緒。讓它明確離開主 actor。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，先看第一行：一般 nonisolated、nonsending、@concurrent 的函式在不在主執行緒（這個專案目前是 false true false）。",
            "再看最後兩行：nonisolated(nonsending) 的解析函式在主執行緒，主執行緒被卡住好幾百毫秒，出現 ✓。",
            "寫 TODO ②，選「修正版」執行：解析函式標了 @concurrent，不在主執行緒，主執行緒只被卡住一點點。",
            "進階：在專案的 Build Settings 搜尋 Approachable Concurrency，改成 Yes（或把 NonisolatedNonsendingByDefault 設成 Yes），再看第一行：一般 nonisolated 的函式從 false 變成 true。",
        ],
        expectBug: "第一行 false true false；解析在主執行緒：true；主執行緒最長被卡住幾百毫秒（實測約 430 毫秒）。",
        expectFixed: "第一行 false true false；解析在主執行緒：false；主執行緒最長只被卡住幾十毫秒以內（實測約 10 毫秒）。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("解析在主執行緒：true") else { return "Bug 還沒重現：解析應該在主執行緒（parseCandles 要用 nonisolated(nonsending)）" }
            guard let ms = stallMs(lines) else { return "找不到主執行緒被卡住的毫秒數" }
            return ms >= 150 ? nil : "主執行緒只被卡住 \(ms) 毫秒：解析要真的在主執行緒執行"
        },
        checkFixed: { lines in
            guard lines.contains("解析在主執行緒：false") else { return "解析不應該在主執行緒：parseCandles 要標 @concurrent" }
            guard let ms = stallMs(lines) else { return "找不到主執行緒被卡住的毫秒數" }
            return ms < 100 ? nil : "主執行緒被卡住 \(ms) 毫秒，還是太久：parseCandles 要標 @concurrent"
        }
    )
}
