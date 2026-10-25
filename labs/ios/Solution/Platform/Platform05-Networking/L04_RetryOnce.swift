// L04：帳號被凍結後，App 對伺服器狂打 25 次請求（401 只能刷新、重送一次）
// 對應複習站：Platform05Networking 第 4 題「很多請求同時收到 401，怎麼只刷新一次 token？」
//
// 情境：使用者的帳號被風控凍結，伺服器對 /assets 一律回 401，即使換了新 token 也一樣。App 的網路層規則是：
//       收到 401 就刷新 token，然後重送請求。（多個請求同時 401 時共用一次刷新，已在 Kotlin04 L07 與 Swift05 L03 練過，這題只看「重送幾次」。）
//       本機假伺服器為了保護演練，/assets 被打超過 25 次之後會改回 503，讓 Bug 版停下來。
// Bug 版：重送用遞迴呼叫 fetch，沒有任何次數限制。每次重送又是 401，又刷新、又重送，一路打到伺服器放棄為止。
// 修正版：用 retried 旗標，401 只刷新一次、只重送一次。重送後還是 401，就回傳 401，由呼叫端清除登入狀態、回到登入畫面。
import Foundation
import LabKit

/// 網路層（兩個版本共用）：send 送出請求並回傳狀態碼，refreshToken 向伺服器換新 token
private struct ApiClient {
    let session: URLSession
    let server: P05LocalServer
    let token: TokenBox

    final class TokenBox: @unchecked Sendable {
        private let lock = NSLock()
        private var value = "token-0"
        var current: String { lock.withLock { value } }
        func update(_ new: String) { lock.withLock { value = new } }
    }

    func send(_ path: String) async throws -> Int {
        var request = URLRequest(url: server.url(path))
        request.setValue("Bearer \(token.current)", forHTTPHeaderField: "Authorization")
        let (_, response) = try await session.data(for: request)
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }

    func refreshToken() async throws {
        var request = URLRequest(url: server.url("/auth/refresh"))
        request.httpMethod = "POST"
        let (data, _) = try await session.data(for: request)
        token.update(String(decoding: data, as: UTF8.self))
    }
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, fetch: (ApiClient, String) async throws -> Int) async {
    let assets = P05Counter()
    let refreshes = P05Counter()
    guard let server = try? P05LocalServer(handler: { request, writer in
        if request.path == "/auth/refresh" {
            writer.respond(200, "token-\(refreshes.hit())")
        } else {
            let n = assets.hit()
            writer.respond(n > 25 ? 503 : 401, "")           // 帳號被凍結：一律 401；超過 25 次是演練的保護
        }
    }) else {
        log("本機伺服器啟動失敗")
        return
    }
    defer { server.stop() }
    let session = URLSession(configuration: .ephemeral)
    defer { session.invalidateAndCancel() }

    let api = ApiClient(session: session, server: server, token: .init())
    let status = (try? await fetch(api, "/assets")) ?? -1
    log("伺服器收到 /assets 請求 \(assets.count) 次，刷新 token \(refreshes.count) 次")
    log("最後得到的狀態碼：\(status)")
    if status == 401 { log("重送後還是 401：清除登入狀態，回到登入畫面") }
}

// MARK: - Bug 版
private enum L04Bug {
    static func fetch(_ api: ApiClient, _ path: String) async throws -> Int {
        let status = try await api.send(path)
        if status == 401 {
            try await api.refreshToken()
            return try await fetch(api, path)               // 遞迴重送：沒有次數限制，只要又是 401 就再來一次
        }
        return status
    }

    static func run(_ log: LabLog) async {
        await scenario(log, fetch: fetch)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func fetch(_ api: ApiClient, _ path: String, retried: Bool = false) async throws -> Int {
        let status = try await api.send(path)
        guard status == 401, !retried else { return status }    // 已經重送過一次，就把 401 交給呼叫端
        try await api.refreshToken()
        return try await fetch(api, path, retried: true)        // 只重送這一次
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { api, path in try await fetch(api, path) }
    }
}

// MARK: - 演練設定
private func requestCounts(_ lines: [String]) -> (assets: Int, refreshes: Int)? {
    guard let line = lines.first(where: { $0.hasPrefix("伺服器收到 /assets 請求 ") }) else { return nil }
    let numbers = line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
    return numbers.count == 2 ? (numbers[0], numbers[1]) : nil
}

extension Platform05Networking {
    static let l04 = Lab(
        id: "L04",
        title: "帳號被凍結，App 對伺服器狂打 25 次請求",
        question: "第 4 題：很多請求同時收到 401，怎麼只刷新一次 token？",
        goal: "收到 401 就刷新 token 再重送，但重送只能做一次。刷新後還是 401，要停下來交給呼叫端處理，不能一直重送。",
        steps: [
            "選「Bug 版」執行，看伺服器收到 /assets 幾次請求、刷新了幾次 token。",
            "想一想：重送之後如果還是 401，Bug 版的 fetch 會做什麼？什麼時候才會停？",
            "選「修正版」執行，確認只打 2 次 /assets、刷新 1 次，最後回傳 401 並回到登入畫面。",
            "進階：Android 的 OkHttp 內建上限，Authenticator 一直回傳新請求，最多跟隨 20 次就丟 ProtocolException；URLSession 沒有這種保護，要自己限制。",
        ],
        expectBug: "「伺服器收到 /assets 請求 26 次，刷新 token 25 次」，最後得到的狀態碼是 503（伺服器的保護才讓它停下來）。",
        expectFixed: "「伺服器收到 /assets 請求 2 次，刷新 token 1 次」，最後得到 401，並印出「清除登入狀態，回到登入畫面」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let counts = requestCounts(lines) else { return "演練沒有跑完：要用 scenario(log, fetch:) 執行" }
            if counts.refreshes == 0 { return "Bug 版收到 401 要刷新 token（api.refreshToken()）再重送" }
            return counts.assets > 20 ? nil : "Bug 還沒重現：重送要用遞迴呼叫 fetch，每次 401 都再刷新、再重送，沒有次數限制（目前只打了 \(counts.assets) 次）"
        },
        checkFixed: { lines in
            guard let counts = requestCounts(lines) else { return "演練沒有跑完：要用 scenario(log, fetch:) 執行" }
            if counts.assets != 2 || counts.refreshes != 1 {
                return "401 只能刷新 1 次、重送 1 次，/assets 共 2 次請求（目前 \(counts.assets) 次請求、刷新 \(counts.refreshes) 次）：用 retried 旗標限制"
            }
            return lines.contains("最後得到的狀態碼：401") ? nil : "重送後還是 401，要把 401 回傳給呼叫端，不要吞掉"
        }
    )
}
