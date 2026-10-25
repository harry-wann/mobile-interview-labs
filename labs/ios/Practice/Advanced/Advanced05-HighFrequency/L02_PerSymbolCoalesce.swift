// L02：3 個交易對各更新兩次，畫面上只有一個交易對有價格（AsyncStream 的
// bufferingNewest(1) 是對整條串流，不是對每個交易對）
// 對應複習站：Advanced05HighFrequency 第 3 題「背壓是什麼？有哪些處理策略？」
//
// 情境：行情串流混著 3 個交易對（BTC、ETH、XRP）的報價，一瞬間來了 6 筆：BTC1、
// ETH1、XRP1、BTC2、ETH2、XRP2（數字是第幾次更新）。
//        畫面處理一筆要 20 毫秒，比來得慢。
// 畫面應該顯示三個交易對各自最新的價格（BTC2、ETH2、XRP2）。
//
// 你要寫：
//   ① Bug 版：建立 AsyncStream 時，bufferingPolicy 用 bufferingNewest(1)：
// 整條串流只留最新 1 筆，其他交易對的更新被擠掉。
//   ② 修正版 QuoteBuffer：用字典（交易對 → 最新一筆）當緩衝區。put
// 同一個交易對後來的蓋掉先來的；drain 取走全部的值並清空。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced05-HighFrequency/L02_PerSymbolCoalesce.swift
import Foundation
import LabKit

private struct Quote {
    let symbol: String
    let update: Int
}

private let burst = [
    ("BTC", 1), ("ETH", 1), ("XRP", 1), ("BTC", 2), ("ETH", 2), ("XRP", 2),
].map { Quote(symbol: $0.0, update: $0.1) }

/// 畫面上目前每個交易對顯示的是第幾次更新（兩個版本共用）
private final class Board: @unchecked Sendable {
    private let lock = NSLock()
    private var shown: [String: Int] = [:]

    func show(_ quote: Quote) {
        lock.lock(); shown[quote.symbol] = quote.update; lock.unlock()
    }

    func summary() -> String {
        lock.lock(); defer { lock.unlock() }
        return ["BTC", "ETH", "XRP"].map {
            "\($0)：\(shown[$0].map { "第 \($0) 次" } ?? "沒有")"
        }.joined(separator: "、")
    }
}

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        let board = Board()
        // TODO ①：makeStream 加上 bufferingPolicy: bufferingNewest(1)：
        // 整條串流只留最新 1 筆
        let (stream, continuation) = AsyncStream.makeStream(of: Quote.self)
        let consumer = Task {
            for await quote in stream {
                board.show(quote)
                // 畫面處理一筆要 20 毫秒
                try? await Task.sleep(for: .milliseconds(20))
            }
        }
        // 一瞬間來了 6 筆
        for quote in burst { continuation.yield(quote) }
        continuation.finish()
        await consumer.value
        log("畫面上各交易對顯示的更新：\(board.summary())")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    /// 緩衝區：每個交易對只留最新一筆。背景寫入、畫面取走
    final class QuoteBuffer: @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [String: Quote] = [:]

        func put(_ quote: Quote) {
            // TODO ②-1：鎖住之後，pending[quote.symbol] = quote：同一個交易對，
            // 後來的蓋掉先來的
            _ = (lock, pending)
        }

        func drain() -> [Quote] {
            // TODO ②-2：鎖住之後，取走 pending 所有的值（Array(pending.
            // values)），再清空 pending，回傳那批值
            []
        }
    }

    static func run(_ log: LabLog) async {
        let board = Board()
        let buffer = QuoteBuffer()
        // 一瞬間來了 6 筆
        for quote in burst { buffer.put(quote) }
        // 畫面取走：3 個交易對各一筆最新的
        for quote in buffer.drain() {
            board.show(quote)
            try? await Task.sleep(for: .milliseconds(20))
        }
        log("畫面上各交易對顯示的更新：\(board.summary())")
    }
}

// MARK: - 演練設定
extension Advanced05HighFrequency {
    static let l02 = Lab(
        id: "L02",
        title: "3 個交易對各更新兩次，畫面上只有一個有價格",
        question: "第 3 題：背壓是什麼？有哪些處理策略？",
        goal:
            "合併要「依交易對」做：每個交易對只留最新的一筆。對整條混著多個交易對的串流用 bufferingNewest(1) 或 conflate，其他交易對的更新會被擠掉。先寫出整條串流只留 1 筆的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced05-HighFrequency/L02_PerSymbolCoalesce.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看最後一行：XRP 是第 2 次，ETH 從頭到尾沒有出現，BTC 停在第 1 次的舊價，或根本沒出現（看消費者開始讀的時間點）。",
            "寫 TODO ②，選「修正版」執行：BTC、ETH、XRP 都是第 2 次。",
            "想一想：Swift 的 Combine `throttle(for:scheduler:latest: true)`、Kotlin 的 `sample`，對混著多個交易對的串流會有同樣的問題嗎？（會，它們也是對整條串流取最新一筆，見這個單元的第 3 題）",
        ],
        expectBug:
            "畫面上各交易對顯示的更新：BTC：沒有（或停在第 1 次）、ETH：沒有、XRP：第 2 次。只有最後一筆 XRP 的第 2 次到得了畫面，ETH 一定沒有，BTC 最新的第 2 次一定沒有。",
        expectFixed: "畫面上各交易對顯示的更新：BTC：第 2 次、ETH：第 2 次、XRP：第 2 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last, last.hasPrefix("畫面上各交易對顯示的更新：") else {
                return "演練沒有跑完"
            }
            if last.contains("沒有") { return nil }
            return "Bug 還沒重現：整條串流用 .bufferingNewest(1)，其中會有交易對從頭到尾沒有出現在畫面上"
        },
        checkFixed: { lines in
            guard let last = lines.last, last.hasPrefix("畫面上各交易對顯示的更新：") else {
                return "演練沒有跑完"
            }
            return last == "畫面上各交易對顯示的更新：BTC：第 2 次、ETH：第 2 次、XRP：第 2 次"
                ? nil
                : "每個交易對都要顯示最新的一次（第 2 次）：用字典依交易對分組，drain 時取走全部。目前是：\(last)"
        }
    )
}
