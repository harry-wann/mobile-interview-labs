// L04：離開行情畫面後，WebSocket 的 topic 還在訂閱（AsyncStream 沒有設
// onTermination 退訂）
// 對應複習站：Swift06AsyncStream 第 3 題「怎麼把 callback 風格的 WebSocket
// 包成 AsyncStream，並且確實退訂？」
//
// 情境：行情畫面用 AsyncStream 包住 WebSocket 的 btcjpy topic，每收到價格就
// yield。使用者離開畫面，畫面的 Task 被取消。
//
// 你要寫：
//   ① Bug 版：tickStream 在 AsyncStream 的 closure 裡呼叫 socket.
// subscribe(topic)，handler 裡 continuation.yield(price)。不設 onTermination。
//   ② 修正版：同樣的訂閱，保存 token，並設定 continuation.onTermination，
// stream 結束時 socket.unsubscribe(token)。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift06-AsyncStream/L04_Unsubscribe.
// swift
import Foundation
import LabKit

/// 假的 WebSocket（兩個版本共用）：訂閱一個 topic，會一直回呼；退訂用 token
private final class FakeSocket: @unchecked Sendable {
    private let lock = NSLock()
    private var handlers: [UUID: @Sendable (Int) -> Void] = [:]
    private var delivered = 0

    var activeSubscriptions: Int { lock.withLock { handlers.count } }
    var deliveredCount: Int { lock.withLock { delivered } }

    func subscribe(
        _ topic: String, handler: @escaping @Sendable (Int) -> Void
    ) -> UUID {
        let token = UUID()
        lock.withLock { handlers[token] = handler }
        return token
    }

    func unsubscribe(_ token: UUID) {
        lock.withLock { _ = handlers.removeValue(forKey: token) }
    }

    /// 伺服器推來一筆價格：有訂閱的 handler 都會被呼叫
    func push(_ price: Int) {
        let current = lock.withLock { Array(handlers.values) }
        lock.withLock { delivered += current.count }
        for handler in current { handler(price) }
    }
}

// MARK: - Bug 版
private enum L04Bug {
    static func tickStream(
        topic: String, socket: FakeSocket
    ) -> AsyncStream<Int> {
        AsyncStream(Int.self, bufferingPolicy: .bufferingNewest(1)) {
            continuation in
            // TODO ①：呼叫 socket.subscribe(topic)，handler 收到 price 就
            // continuation.yield(price)。不要設 onTermination
            continuation.finish()
        }
    }

    static func run(_ log: LabLog) async {
        let socket = FakeSocket()
        let screen = Task {
            for await _ in tickStream(topic: "btcjpy", socket: socket) {}
        }
        try? await Task.sleep(for: .milliseconds(50))
        log("畫面開著時，WebSocket 上的訂閱數：\(socket.activeSubscriptions)")
        // 使用者離開畫面
        screen.cancel()
        await screen.value
        try? await Task.sleep(for: .milliseconds(50))
        log("離開畫面後，WebSocket 上的訂閱數：\(socket.activeSubscriptions)")
        socket.push(15_000_000)
        log("離開畫面後又推一筆價格，被送進 handler 的次數：\(socket.deliveredCount)")
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func tickStream(
        topic: String, socket: FakeSocket
    ) -> AsyncStream<Int> {
        AsyncStream(Int.self, bufferingPolicy: .bufferingNewest(1)) {
            continuation in
            // TODO ②：呼叫 socket.subscribe(topic) 並保存回傳的 token，handler
            // 裡 continuation.yield(price)；
            //        再設定 continuation.onTermination，裡面呼叫 socket.
            // unsubscribe(token)
            continuation.finish()
        }
    }

    static func run(_ log: LabLog) async {
        let socket = FakeSocket()
        let screen = Task {
            for await _ in tickStream(topic: "btcjpy", socket: socket) {}
        }
        try? await Task.sleep(for: .milliseconds(50))
        log("畫面開著時，WebSocket 上的訂閱數：\(socket.activeSubscriptions)")
        screen.cancel()
        await screen.value
        try? await Task.sleep(for: .milliseconds(50))
        log("離開畫面後，WebSocket 上的訂閱數：\(socket.activeSubscriptions)")
        socket.push(15_000_000)
        log("離開畫面後又推一筆價格，被送進 handler 的次數：\(socket.deliveredCount)")
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l04 = Lab(
        id: "L04",
        title: "離開畫面後，行情的訂閱還在",
        question: "第 3 題：怎麼把 callback 風格的 WebSocket 包成 AsyncStream，並且確實退訂？",
        goal: "畫面的 Task 被取消後，WebSocket 上的 topic 必須退訂，伺服器不再送價格進來。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看三行 log：畫面開著時有 1 個訂閱；畫面離開後還剩幾個；又推一筆價格有沒有被送進 handler，出現 ✓。",
            "想一想：Task 被取消、for await 結束後，誰負責通知 WebSocket 退訂？",
            "寫 TODO ②，選「修正版」執行：離開畫面後訂閱數是 0，又推的價格沒有被送進任何 handler。",
        ],
        expectBug: "畫面開著時訂閱數 1；離開畫面後訂閱數仍是 1；又推一筆價格後，被送進 handler 的次數：1（洩漏）。",
        expectFixed: "畫面開著時訂閱數 1；離開畫面後訂閱數 0；又推一筆價格後，被送進 handler 的次數：0。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("畫面開著時，WebSocket 上的訂閱數：1") else {
                return
                    "畫面開著時應該有 1 個訂閱：tickStream 要在 AsyncStream 的 closure 裡呼叫 socket.subscribe"
            }
            return lines.contains("離開畫面後，WebSocket 上的訂閱數：1")
                ? nil : "Bug 還沒重現：離開畫面後訂閱數應該還是 1（不要設 onTermination）"
        },
        checkFixed: { lines in
            guard lines.contains("畫面開著時，WebSocket 上的訂閱數：1") else {
                return
                    "畫面開著時應該有 1 個訂閱：tickStream 要在 AsyncStream 的 closure 裡呼叫 socket.subscribe"
            }
            guard lines.contains("離開畫面後，WebSocket 上的訂閱數：0") else {
                return
                    "離開畫面後應該退訂：在 continuation.onTermination 裡呼叫 socket.unsubscribe(token)"
            }
            return lines.last == "離開畫面後又推一筆價格，被送進 handler 的次數：0"
                ? nil : "退訂後不應該再有價格被送進 handler"
        }
    )
}
