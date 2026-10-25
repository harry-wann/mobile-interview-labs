// L08：行情每秒幾百筆，每一筆都丟回主執行緒，畫面被淹沒（背景合併，
// 固定頻率更新畫面）
// 對應複習站：Platform02Threading 第 6 題「行情每秒推幾百筆，
// 執行緒要怎麼設計？」
//
// 情境：行情頁收到 WebSocket 推來的報價。演練送出 300 筆報價（6 檔幣種輪流，每
// 2 ms 一筆，約 0.6 秒），看畫面被更新了幾次，以及最後畫面上的價格對不對。
//
// 你要寫：
//   ① Bug 版：每收到一筆報價就 DispatchQueue.main.async 更新一次畫面。
//   ② 修正版：背景依序把報價放進 actor 的字典（每檔只留最新一筆），主執行緒每
// 200 ms 取一次整份快照更新畫面。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform02-Threading/L08_TickFlood.swift
import Foundation
import LabKit

private struct Tick: Sendable {
    let symbol: String
    let price: Int
}

/// 畫面（兩個版本共用）：記下畫面更新了幾次、現在顯示什麼
@MainActor
private final class MarketScreen {
    var updates = 0
    var shown: [String: Int] = [:]

    func render(_ prices: [String: Int]) {
        updates += 1
        shown.merge(prices) { _, new in new }
    }
}

/// 收到報價之後要做的事（兩個版本各自實作）
private protocol Pipeline: AnyObject, Sendable {
    // 在背景執行緒被呼叫（WebSocket 收到一筆）
    func receive(_ tick: Tick)
    // 演練結束前，等還沒處理完的更新都處理完
    func finish() async
}

private let symbols = ["BTC", "ETH", "XRP", "LTC", "ADA", "DOT"]

/// 演練流程（兩個版本共用）：背景每 2 ms 送一筆報價，共 300 筆
@MainActor
private func scenario(
    _ log: LabLog, makePipeline: (MarketScreen) -> Pipeline
) async {
    let screen = MarketScreen()
    let pipeline = makePipeline(screen)
    var lastPrice: [String: Int] = [:]
    await withCheckedContinuation { continuation in
        DispatchQueue.global().async {
            for i in 0..<300 {
                let tick = Tick(
                    symbol: symbols[i % symbols.count], price: 1_000 + i)
                pipeline.receive(tick)
                Thread.sleep(forTimeInterval: 0.002)
            }
            continuation.resume()
        }
    }
    for i in 0..<300 { lastPrice[symbols[i % symbols.count]] = 1_000 + i }
    await pipeline.finish()
    log("收到 300 筆報價，畫面更新了 \(screen.updates) 次")
    log("最後畫面上的價格\(screen.shown == lastPrice ? "和每檔最後一筆一致" : "和每檔最後一筆不一致")")
}

// MARK: - Bug 版
private enum L08Bug {
    final class DirectPipeline: Pipeline, @unchecked Sendable {
        private let screen: MarketScreen

        init(screen: MarketScreen) {
            self.screen = screen
        }

        func receive(_ tick: Tick) {
            // TODO ①：每收到一筆就用 DispatchQueue.main.async 排進主執行緒，
            // 在裡面（用 MainActor.assumeIsolated 包起來）呼叫 screen.
            // render([tick.symbol: tick.price])
        }

        func finish() async {
            try? await Task.sleep(for: .milliseconds(300))
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { DirectPipeline(screen: $0) }
    }
}

// MARK: - 修正版
private enum L08Fixed {
    /// 每檔只保留最新一筆（actor 保證字典一次只被一條執行緒存取）
    actor TickerStore {
        private var latest: [String: Int] = [:]
        func update(_ tick: Tick) { latest[tick.symbol] = tick.price }
        func snapshot() -> [String: Int] { latest }
    }

    final class ConflatingPipeline: Pipeline, @unchecked Sendable {
        private let store = TickerStore()
        private let continuation: AsyncStream<Tick>.Continuation
        private let consumer: Task<Void, Never>
        private let refresher: Task<Void, Never>

        @MainActor init(screen: MarketScreen) {
            let store = store
            let (stream, continuation) = AsyncStream.makeStream(of: Tick.self)
            self.continuation = continuation
            // 背景合併：依序放進字典，不碰主執行緒
            consumer = Task {
                // TODO ②-1：用 for await 依序處理 stream 收到的 tick，每一筆都
                // await store.update(tick)（背景合併，不碰主執行緒）
            }
            refresher = Task { @MainActor in
                while !Task.isCancelled {
                    // 固定頻率
                    try? await Task.sleep(for: .milliseconds(200))
                    // TODO ②-2：取整份快照（await store.snapshot()），交給
                    // screen.render，只更新畫面一次
                }
            }
        }

        func receive(_ tick: Tick) {
            continuation.yield(tick)
        }

        func finish() async {
            continuation.finish()
            // 全部放進字典
            await consumer.value
            // 讓最後一次更新跑完
            try? await Task.sleep(for: .milliseconds(250))
            refresher.cancel()
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { ConflatingPipeline(screen: $0) }
    }
}

// MARK: - 演練設定
private func updateCount(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("收到 300 筆報價，畫面更新了 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "收到 300 筆報價，畫面更新了 ", with: "")
                    .replacingOccurrences(of: " 次", with: ""))
        }
}

extension Platform02Threading {
    static let l08 = Lab(
        id: "L08",
        title: "每一筆報價都更新畫面，主執行緒被淹沒",
        question: "第 6 題：行情每秒推幾百筆，執行緒要怎麼設計？",
        goal:
            "報價在背景合併（每檔只留最新一筆），再用固定頻率把整份快照交給主執行緒。畫面更新的次數要遠少於報價的筆數，而且最後的價格要是對的。先寫出主執行緒被淹沒的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform02-Threading/L08_TickFlood.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「畫面更新了幾次」：和收到的報價筆數比一比。",
            "寫 TODO ②（修正版），選「修正版」執行：畫面只更新幾次？最後畫面上的價格是不是每檔的最後一筆？",
            "想一想：每 200 ms 才更新一次，中間被覆蓋的價格會不會漏掉最高價、最低價？（畫面顯示可以丟，K 線的高低點要在合併之前的資料層累計）",
        ],
        expectBug: "收到 300 筆報價，畫面更新了 300 次；最後價格正確。",
        expectFixed: "收到 300 筆報價，畫面只更新約 5 次（每 200 ms 一次）；最後價格和每檔最後一筆一致。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = updateCount(lines) else { return "演練沒有跑完" }
            return n >= 250
                ? nil
                : "Bug 還沒重現（畫面只更新 \(n) 次）：receive 要對每一筆報價都 DispatchQueue.main.async 更新一次畫面"
        },
        checkFixed: { lines in
            guard let n = updateCount(lines) else { return "演練沒有跑完" }
            if n == 0 { return "畫面一次都沒有更新：每 200 ms 要取一次快照，呼叫 screen.render" }
            if n > 12 {
                return "畫面更新了 \(n) 次，太多了：receive 只存進字典，每 200 ms 才取一次快照更新畫面"
            }
            if !lines.contains("最後畫面上的價格和每檔最後一筆一致") {
                return "最後畫面上的價格不對：每一檔都要保留最新的一筆，快照要包含全部 6 檔"
            }
            return nil
        }
    )
}
