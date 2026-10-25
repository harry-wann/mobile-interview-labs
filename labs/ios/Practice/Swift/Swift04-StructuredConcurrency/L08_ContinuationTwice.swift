// L08：把價格 delegate 包成 async，第二筆價格進來 App 就當掉（continuation 被 resume 兩次）
// 對應複習站：Swift04StructuredConcurrency 第 5 題「continuation 是什麼？把舊的 callback 接成 async 時有哪三種常見誤用？」
//
// 情境：舊的價格連線（PriceFeedClient）每 0.1 秒就呼叫一次 onPrice，連續三筆。想把它包成 async 函式。
//
// 你要寫：
//   ① Bug 版：nextPrice 用 withCheckedContinuation 包起來：client.onPrice 裡 resume(returning:)，再 client.start()。
//   ② 修正版：prices(from:) 回傳 AsyncStream<Int>：onPrice 裡 yield，onTermination 時 client.stop()，再 client.start()。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift04-StructuredConcurrency/L08_ContinuationTwice.swift
import Foundation
import LabKit

/// 舊的價格連線（兩個版本共用）：start() 之後，每 0.1 秒呼叫一次 onPrice，共三筆
private final class PriceFeedClient: @unchecked Sendable {
    var onPrice: ((Int) -> Void)?
    private var stopped = false

    func start() {
        let prices = [15_000_000, 15_000_100, 15_000_200]
        for (i, price) in prices.enumerated() {
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.1 * Double(i + 1)) { [self] in
                if !stopped { onPrice?(price) }
            }
        }
    }

    func stop() { stopped = true }
}

// MARK: - Bug 版
private enum L08Bug {
    /// 想等下一筆價格，用 continuation 包起來
    static func nextPrice(from client: PriceFeedClient) async -> Int {
        // TODO ①：return await withCheckedContinuation { continuation in … }：
        //        client.onPrice 裡 continuation.resume(returning: price)，然後 client.start()
        return 0
    }

    static func run(_ log: LabLog) async {
        let client = PriceFeedClient()
        let price = await nextPrice(from: client)
        log("收到價格 \(price)")
        try? await Task.sleep(for: .seconds(1))                 // 等後面的價格進來
        log("跑到這裡代表沒有當掉")
    }
}

// MARK: - 修正版
private enum L08Fixed {
    /// 會多次回呼的 API：包成 AsyncStream，每筆價格 yield 一次
    static func prices(from client: PriceFeedClient) -> AsyncStream<Int> {
        // TODO ②：AsyncStream { continuation in … }：client.onPrice 裡 continuation.yield(price)；
        //        continuation.onTermination 時呼叫 client.stop()；最後 client.start()
        AsyncStream { continuation in continuation.finish() }
    }

    static func run(_ log: LabLog) async {
        let client = PriceFeedClient()
        var received = 0
        for await price in prices(from: client) {
            log("收到價格 \(price)")
            received += 1
            if received == 3 { break }                          // 收三筆就不要了：離開迴圈時 onTermination 會停掉連線
        }
        try? await Task.sleep(for: .seconds(1))
        log("跑到這裡代表沒有當掉")
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l08 = Lab(
        id: "L08",
        title: "第二筆價格進來，App 就當掉",
        question: "第 5 題：continuation 是什麼？把舊的 callback 接成 async 時有哪三種常見誤用？",
        goal: "舊的價格連線會一直回呼 onPrice。找出為什麼用 continuation 包起來會當掉，改成適合多次回呼的做法。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "寫 TODO ①，選「練習」「Bug 版」按「執行」：收到第一筆價格後，約 0.1 秒內 App 當掉，主控台印出 SWIFT TASK CONTINUATION MISUSE: … tried to resume its continuation more than once。",
            "想一想：continuation 只能 resume 幾次？onPrice 會被呼叫幾次？",
            "按 ■ 停止後重新執行，寫 TODO ②，選「修正版」執行：三筆價格都收到，沒有當掉。",
        ],
        expectBug: "收到價格 15000000 之後 App 當掉，主控台：SWIFT TASK CONTINUATION MISUSE: … tried to resume its continuation more than once, returning 15000100!",
        expectFixed: "依序收到價格 15000000、15000100、15000200，最後印出「跑到這裡代表沒有當掉」。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkFixed: { lines in
            guard lines.contains("收到價格 15000000"), lines.contains("收到價格 15000100"), lines.contains("收到價格 15000200") else {
                return "三筆價格都要收到：prices(from:) 要回傳 AsyncStream，onPrice 裡 yield，再用 for await 收"
            }
            return lines.last == "跑到這裡代表沒有當掉" ? nil : "修正版不應該當掉"
        }
    )
}
