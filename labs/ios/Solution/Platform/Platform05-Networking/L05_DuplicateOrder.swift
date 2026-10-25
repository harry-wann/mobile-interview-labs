// L05：下單逾時後自動重送，結果買了兩次（clientOrderId 與先查詢再重送）
// 對應複習站：Platform05Networking 第 5 題「網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？」
//
// 情境：使用者按下「買入」，App 送出 POST /orders。伺服器收到訂單、記下來了，但處理很久（0.6 秒）才回應；
//       App 的請求逾時是 0.3 秒，等不到回應，以為失敗。（本機假伺服器：有帶 clientOrderId 的訂單，同一個 ID 只會記一次。）
// Bug 版：逾時就自動重送一次，沒有帶任何識別碼。伺服器看不出這是同一筆，記成第二張訂單。
// 修正版：每次按「買入」產生一個 clientOrderId（UUID），放在 X-Client-Order-Id 標頭。逾時後先用 GET /orders/{id} 查詢：
//        查得到就不重送；查不到才重送，而且帶同一個 ID。
import Foundation
import LabKit

/// 本機假伺服器上的訂單簿（兩個版本共用）
private final class OrderBook: @unchecked Sendable {
    private let lock = NSLock()
    private var ids: Set<String> = []
    private var anonymous = 0

    /// 有 clientOrderId 的訂單，同一個 ID 只記一次；沒有的，每次都是新的一張
    func place(clientOrderId: String?) {
        lock.withLock {
            if let id = clientOrderId { ids.insert(id) } else { anonymous += 1 }
        }
    }

    func contains(_ id: String) -> Bool { lock.withLock { ids.contains(id) } }
    var total: Int { lock.withLock { ids.count + anonymous } }
}

/// 網路層（兩個版本共用）：postOrder 送出下單請求，逾時 0.3 秒；orderExists 用 ID 查詢訂單
private struct OrderApi {
    let session: URLSession
    let server: P05LocalServer

    func postOrder(clientOrderId: String? = nil) async throws {
        var request = URLRequest(url: server.url("/orders"))
        request.httpMethod = "POST"
        if let id = clientOrderId { request.setValue(id, forHTTPHeaderField: "X-Client-Order-Id") }
        _ = try await session.data(for: request)
    }

    func orderExists(_ id: String) async throws -> Bool {
        let (_, response) = try await session.data(from: server.url("/orders/\(id)"))
        return (response as? HTTPURLResponse)?.statusCode == 200
    }
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, placeOrder: (OrderApi, LabLog) async -> Void) async {
    let book = OrderBook()
    guard let server = try? P05LocalServer(handler: { request, writer in
        if request.method == "POST" {
            book.place(clientOrderId: request.headers["x-client-order-id"])   // 先記下訂單
            writer.pause(milliseconds: 600)                                   // 處理很久，回應晚到
            writer.respond(200, "ok")
        } else {
            let id = request.path.replacingOccurrences(of: "/orders/", with: "")
            writer.respond(book.contains(id) ? 200 : 404, "")
        }
    }) else {
        log("本機伺服器啟動失敗")
        return
    }
    defer { server.stop() }
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 0.3                  // App 只等 0.3 秒
    let session = URLSession(configuration: config)
    defer { session.invalidateAndCancel() }

    await placeOrder(OrderApi(session: session, server: server), log)
    try? await Task.sleep(for: .milliseconds(100))
    log("伺服器上的訂單數：\(book.total)")
}

// MARK: - Bug 版
private enum L05Bug {
    static func placeOrder(_ api: OrderApi, _ log: LabLog) async {
        for attempt in 1...2 {                              // 逾時就自動重送一次
            do {
                try await api.postOrder()                   // 沒有帶任何識別碼
                log("第 \(attempt) 次下單成功")
                return
            } catch {
                log("第 \(attempt) 次下單逾時，沒有收到回應")
            }
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, placeOrder: placeOrder)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func placeOrder(_ api: OrderApi, _ log: LabLog) async {
        let clientOrderId = UUID().uuidString               // 這一次按「買入」只產生一個 ID，重送也用同一個
        do {
            try await api.postOrder(clientOrderId: clientOrderId)
            log("下單成功")
        } catch {
            log("下單逾時，沒有收到回應")
            if (try? await api.orderExists(clientOrderId)) == true {
                log("用 clientOrderId 查詢：訂單已經存在，不重送")      // 先查詢：伺服器其實已經收到了
            } else {
                try? await api.postOrder(clientOrderId: clientOrderId)  // 查不到才重送，仍然是同一個 ID
                log("查不到訂單，用同一個 clientOrderId 重送")
            }
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, placeOrder: placeOrder)
    }
}

// MARK: - 演練設定
extension Platform05Networking {
    static let l05 = Lab(
        id: "L05",
        title: "下單逾時後自動重送，結果買了兩次",
        question: "第 5 題：網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？",
        goal: "下單的 POST 逾時，不代表伺服器沒成功。讓同一次按「買入」不論逾時幾次，伺服器上都只有一張訂單。",
        steps: [
            "選「Bug 版」執行，看兩次下單的結果，以及最後一行「伺服器上的訂單數」。",
            "想一想：App 看到「逾時」的時候，伺服器其實已經做完了嗎？第二次 POST 伺服器怎麼知道它和第一次是同一筆？",
            "選「修正版」執行，看逾時之後 App 先做了什麼，訂單數是不是 1。",
            "進階：429 要等 Retry-After、4xx 不重試、可以自動重試的請求要用指數退避加 jitter（下一個演練）。",
        ],
        expectBug: "「第 1 次下單逾時」「第 2 次下單逾時」，最後「伺服器上的訂單數：2」。",
        expectFixed: "「下單逾時，沒有收到回應」「用 clientOrderId 查詢：訂單已經存在，不重送」，最後「伺服器上的訂單數：1」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器上的訂單數") }) else { return "演練沒有跑完：要用 scenario(log, placeOrder:) 執行" }
            if !lines.contains("第 1 次下單逾時，沒有收到回應") { return "Bug 版要送出 POST 下單（api.postOrder()），0.3 秒內等不到回應就逾時" }
            return lines.contains("伺服器上的訂單數：2") ? nil : "Bug 還沒重現：逾時後要自動重送一次 postOrder()（不帶識別碼），伺服器才會記成兩張"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("伺服器上的訂單數") }) else { return "演練沒有跑完：要用 scenario(log, placeOrder:) 執行" }
            if !lines.contains("下單逾時，沒有收到回應") { return "要先用 clientOrderId 送出 POST 下單（api.postOrder(clientOrderId:)）" }
            if !lines.contains("用 clientOrderId 查詢：訂單已經存在，不重送") { return "逾時之後要先用 api.orderExists(id) 查詢，查得到就不重送" }
            return lines.contains("伺服器上的訂單數：1") ? nil : "伺服器上應該只有 1 張訂單"
        }
    )
}
