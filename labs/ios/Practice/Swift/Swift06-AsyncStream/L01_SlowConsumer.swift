// L01：行情送完了，畫面還在顯示幾秒前的價格（慢消費者搭配預設的 unbounded
// 緩衝）
// 對應複習站：Swift06AsyncStream 第 2 題「行情來得比畫面處理得快時怎麼辦？」
//
// 情境：行情每 1 毫秒左右送來一筆價格（共 300 筆，編號就是價格的第幾筆），
// 畫面每處理一筆要 5 毫秒。
//
// 你要寫：
//   ① Bug 版：makeTicks 回傳 AsyncStream.makeStream(of: Int.self)，不指定
// bufferingPolicy（預設 unbounded）。
//   ② 修正版：makeTicks 回傳 makeStream，bufferingPolicy 指定
// bufferingNewest(1)。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift06-AsyncStream/L01_SlowConsumer.swift
import Foundation
import LabKit

private let tickCount = 300

// MARK: - Bug 版
private enum L01Bug {
    /// 建立價格 stream：預設的 .unbounded，全部緩衝
    static func makeTicks() -> (AsyncStream<Int>, AsyncStream<Int>.Continuation)
    {
        // TODO ①：回傳 AsyncStream.makeStream(of: Int.self)，不指定
        // bufferingPolicy（預設 unbounded）
        let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
        continuation.finish()
        return (stream, continuation)
    }

    static func run(_ log: LabLog) async {
        // 畫面目前顯示到第幾筆
        let shown = Swift06AsyncStream.Gauge()
        // 畫面落後最新價幾筆
        let lag = Swift06AsyncStream.Gauge()
        let handled = Swift06AsyncStream.Counter()

        let (stream, continuation) = makeTicks()

        let consumer = Task {
            for await tick in stream {
                shown.set(tick)
                handled.hit()
                // 畫面處理一筆要 5 毫秒
                try? await Task.sleep(for: .milliseconds(5))
            }
        }

        for tick in 1...tickCount {
            continuation.yield(tick)
            lag.set(tick - shown.current)
            // 行情每 1 毫秒左右一筆
            try? await Task.sleep(for: .milliseconds(1))
        }
        continuation.finish()
        log("行情送完 \(tickCount) 筆時，畫面顯示到第 \(shown.current) 筆")
        await consumer.value
        log("畫面落後最新價最多 \(lag.peak) 筆")
        log("畫面共處理 \(handled.count) 筆")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// 建立價格 stream：.bufferingNewest(1)，緩衝區只留最新一筆
    static func makeTicks() -> (AsyncStream<Int>, AsyncStream<Int>.Continuation)
    {
        // TODO ②：回傳 AsyncStream.makeStream，用 bufferingPolicy
        // 參數指定只留最新一筆（.bufferingNewest(1)）
        let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
        continuation.finish()
        return (stream, continuation)
    }

    static func run(_ log: LabLog) async {
        let shown = Swift06AsyncStream.Gauge()
        let lag = Swift06AsyncStream.Gauge()
        let handled = Swift06AsyncStream.Counter()

        let (stream, continuation) = makeTicks()

        let consumer = Task {
            for await tick in stream {
                shown.set(tick)
                handled.hit()
                try? await Task.sleep(for: .milliseconds(5))
            }
        }

        for tick in 1...tickCount {
            continuation.yield(tick)
            lag.set(tick - shown.current)
            try? await Task.sleep(for: .milliseconds(1))
        }
        continuation.finish()
        log("行情送完 \(tickCount) 筆時，畫面顯示到第 \(shown.current) 筆")
        await consumer.value
        log("畫面落後最新價最多 \(lag.peak) 筆")
        log("畫面共處理 \(handled.count) 筆")
    }
}

// MARK: - 演練設定
private func number(after prefix: String, in line: String?) -> Int? {
    guard let line, line.hasPrefix(prefix) else { return nil }
    return Int(line.dropFirst(prefix.count).prefix { $0.isNumber })
}

extension Swift06AsyncStream {
    static let l01 = Lab(
        id: "L01",
        title: "行情送完了，畫面還停在好幾秒前",
        question: "第 2 題：行情來得比畫面處理得快時怎麼辦？",
        goal: "行情比畫面處理得快。畫面每次讀到的都要是最新價，不能一筆一筆慢慢追舊的價格。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看三行 log：行情送完時畫面顯示到第幾筆、畫面落後最新價最多幾筆、畫面共處理幾筆，出現 ✓。",
            "想一想：預設的 .unbounded 把來不及處理的價格放在哪裡？消費者要花多久才追得上？",
            "寫 TODO ②，選「修正版」執行：送完時畫面顯示到的就是最新一筆，落後最多幾筆，共處理的筆數少很多（中間的舊價格被丟掉了）。",
            "進階：行情是可以丟的資料；成交回報、訂單狀態這種不能丟的，要用 .unbounded 並搭配序號偵測漏掉。",
        ],
        expectBug:
            "行情送完 300 筆時，畫面只顯示到第 100 筆左右；畫面落後最新價最多約 200 筆；畫面共處理 300 筆（要花一秒多才追完）。",
        expectFixed: "行情送完 300 筆時，畫面顯示到第 295 筆左右；畫面落後最新價最多只有幾筆；畫面共處理約 100 筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                let shown = lines.compactMap({
                    number(after: "行情送完 300 筆時，畫面顯示到第 ", in: $0)
                }).first,
                let lag = lines.compactMap({
                    number(after: "畫面落後最新價最多 ", in: $0)
                }).first,
                let handled = lines.compactMap({
                    number(after: "畫面共處理 ", in: $0)
                }).first
            else {
                return "找不到結果：消費者每處理一筆要 shown.set(tick)、handled.hit()，再睡 5 毫秒"
            }
            guard handled == 300 else {
                return
                    "Bug 還沒重現：畫面應該一筆不漏處理 300 筆，實際處理 \(handled) 筆（stream 要用預設的 .unbounded）"
            }
            return shown < 200 && lag >= 100
                ? nil : "Bug 還沒重現：送完時畫面只顯示到第 \(shown) 筆、落後 \(lag) 筆，應該落後很多"
        },
        checkFixed: { lines in
            guard
                let shown = lines.compactMap({
                    number(after: "行情送完 300 筆時，畫面顯示到第 ", in: $0)
                }).first,
                let lag = lines.compactMap({
                    number(after: "畫面落後最新價最多 ", in: $0)
                }).first,
                let handled = lines.compactMap({
                    number(after: "畫面共處理 ", in: $0)
                }).first
            else {
                return "找不到結果：消費者每處理一筆要 shown.set(tick)、handled.hit()，再睡 5 毫秒"
            }
            guard handled > 0 else { return "畫面至少要處理一筆價格" }
            guard handled < 200 else {
                return
                    "畫面處理了 \(handled) 筆，舊價格沒有被丟掉：stream 要用 .bufferingNewest(1)"
            }
            return shown >= 280 && lag <= 20
                ? nil
                : "送完時畫面只顯示到第 \(shown) 筆、落後 \(lag) 筆：緩衝區只留最新一筆，畫面每次讀到的就是最新價"
        }
    )
}
