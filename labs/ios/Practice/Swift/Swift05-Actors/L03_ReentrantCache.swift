// L03：三個畫面同時要 BTC 價格，同一個價格被下載了三次（actor 快取的 reentrancy）
// 對應複習站：Swift05Actors 第 2 題「actor 的 reentrancy 是什麼？怎麼修？」
//
// 情境：價格快取是 actor。三個地方（首頁、下單頁、通知）同時要 BTC/JPY 的價格，快取還是空的，下載要 100 毫秒。
//
// 你要寫：
//   ① Bug 版：price(for:) 快取沒有就 await network.fetchPrice，下載完才寫進 cache。
//   ② 修正版：用 inFlight 字典記住正在下載的 Task：在 await 之前登記，後面進來的呼叫等同一個 Task 的結果。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift05-Actors/L03_ReentrantCache.swift
import Foundation
import LabKit

/// 假的下載：每次花 100 毫秒，並記錄被呼叫幾次（兩個版本共用）
private final class FakeNetwork: @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    var requestCount: Int { lock.withLock { calls } }

    func fetchPrice(_ symbol: String) async -> Int {
        lock.withLock { calls += 1 }
        try? await Task.sleep(for: .milliseconds(100))
        return 15_000_000
    }
}

// MARK: - Bug 版
private enum L03Bug {
    actor PriceCache {
        private var cache: [String: Int] = [:]
        private let network: FakeNetwork

        init(network: FakeNetwork) { self.network = network }

        func price(for symbol: String) async -> Int {
            // TODO ①：1. 快取裡有就直接回傳  2. 沒有就 await network.fetchPrice(symbol)  3. 下載完寫進 cache，回傳
            return 0
        }
    }

    static func run(_ log: LabLog) async {
        let network = FakeNetwork()
        let cache = PriceCache(network: network)
        async let home = cache.price(for: "BTC/JPY")
        async let order = cache.price(for: "BTC/JPY")
        async let notification = cache.price(for: "BTC/JPY")
        let prices = await [home, order, notification]
        log("三個呼叫拿到的價格：\(prices)")
        log("實際送出的下載請求：\(network.requestCount) 次")
    }
}

// MARK: - 修正版
private enum L03Fixed {
    actor PriceCache {
        private var cache: [String: Int] = [:]
        private var inFlight: [String: Task<Int, Never>] = [:]       // 正在下載的工作
        private let network: FakeNetwork

        init(network: FakeNetwork) { self.network = network }

        func price(for symbol: String) async -> Int {
            // TODO ②：1. 快取裡有就直接回傳  2. inFlight[symbol] 有 Task 在跑，就 await 它的 value 回傳
            //        3. 否則建立 Task { await network.fetchPrice(symbol) }，在 await 之前先存進 inFlight[symbol]（用 defer 在結束時清掉）
            //        4. await 這個 Task 的 value，寫進 cache，回傳
            return 0
        }
    }

    static func run(_ log: LabLog) async {
        let network = FakeNetwork()
        let cache = PriceCache(network: network)
        async let home = cache.price(for: "BTC/JPY")
        async let order = cache.price(for: "BTC/JPY")
        async let notification = cache.price(for: "BTC/JPY")
        let prices = await [home, order, notification]
        log("三個呼叫拿到的價格：\(prices)")
        log("實際送出的下載請求：\(network.requestCount) 次")
    }
}

// MARK: - 演練設定
extension Swift05Actors {
    static let l03 = Lab(
        id: "L03",
        title: "同一個價格被下載了三次",
        question: "第 2 題：actor 的 reentrancy 是什麼？怎麼修？",
        goal: "三個呼叫同時要同一個價格，只能下載一次，三個呼叫都拿到同一個結果。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：實際送出幾次下載請求，出現 ✓。",
            "想一想：快取是 actor，為什麼三個呼叫都看到「快取是空的」？",
            "寫 TODO ②，選「修正版」執行：只送出 1 次請求，三個呼叫都拿到價格。",
        ],
        expectBug: "三個呼叫都拿到 15000000，但實際送出的下載請求：3 次。",
        expectFixed: "三個呼叫都拿到 15000000，實際送出的下載請求：1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("三個呼叫拿到的價格：[15000000, 15000000, 15000000]") else { return "三個呼叫都要拿到價格 15000000：快取沒有就下載，下載完寫進快取" }
            return lines.last == "實際送出的下載請求：3 次" ? nil : "Bug 還沒重現：三個呼叫應該各下載一次（下載完才寫進快取，await 之前不要登記任何東西）"
        },
        checkFixed: { lines in
            guard lines.contains("三個呼叫拿到的價格：[15000000, 15000000, 15000000]") else { return "三個呼叫都要拿到價格 15000000" }
            return lines.last == "實際送出的下載請求：1 次" ? nil : "只應該送出 1 次請求：await 之前先把進行中的 Task 存進字典，後面的呼叫等同一個結果"
        }
    )
}
