// L03：API 故障過一次，之後每次要這份設定都直接失敗，要重開 App 才好（single-flight 把失敗也留下來了）
// 對應複習站：Coding04Async 第 3 題「好幾個地方同時要同一份資料，怎麼只載入一次？」
//
// 情境：交易對設定用 single-flight 快取（actor 裡記錄每個 key 正在進行的 Task），好幾個畫面同時要同一份就共用同一個請求。
//       演練分三輪：① BTC 同時要 3 次、ETH 同時要 2 次，API 正常；② SOL 同時要 2 次，這一次 API 剛好故障；
//       ③ API 恢復後，SOL 再要一次。（「await 之前沒登記進行中的工作，大家各打一次 API」的 actor reentrancy，見 Swift05 L03。）
// Bug 版：「進行中的 Task」登記好了，但只有成功時才清掉。API 故障時，失敗的 Task 還留在 inFlight 裡，
//        之後每次要 SOL，都等到同一個已經失敗的 Task，永遠拿到同一個錯誤，API 恢復了也沒有再呼叫。
// 修正版：用 do/catch 包起來，失敗時也把 inFlight[key] 清掉再丟出錯誤。失敗不快取，下一次呼叫會重新載入。
import Foundation
import LabKit

/// 假的設定 API：每次花 80 毫秒，並記下每個 key 被呼叫幾次；指定的 key 第一次呼叫會失敗（逾時）
private final class FakeApi: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [String: Int] = [:]
    private let failFirstCall: Set<String>

    init(failFirstCall: Set<String>) {
        self.failFirstCall = failFirstCall
    }

    func fetch(_ key: String) async throws -> String {
        let shouldFail = register(key)
        try await Task.sleep(for: .milliseconds(80))
        if shouldFail { throw C04ApiError.timeout }
        return "\(key) 的設定"
    }

    /// 記下這個 key 被呼叫了一次；回傳這次是否該失敗
    private func register(_ key: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        calls[key, default: 0] += 1
        return failFirstCall.contains(key) && calls[key] == 1
    }

    func count(_ key: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return calls[key, default: 0]
    }
}

/// 演練流程（兩個版本共用）：三輪呼叫。make 用假 API 建出「取得設定」的函式
private func scenario(_ log: LabLog, make: (FakeApi) -> (String) async throws -> String) async {
    let api = FakeApi(failFirstCall: ["SOL"])
    let value = make(api)

    // ① BTC 同時要 3 次、ETH 同時要 2 次
    let okCount = await withTaskGroup(of: Bool.self) { group in
        for key in ["BTC", "BTC", "BTC", "ETH", "ETH"] {
            group.addTask { (try? await value(key)) != nil }
        }
        var n = 0
        for await ok in group where ok { n += 1 }
        return n
    }
    log("① BTC 同時要 3 次、ETH 同時要 2 次：\(okCount) 個拿到設定，API 呼叫 BTC \(api.count("BTC")) 次、ETH \(api.count("ETH")) 次")

    // ② SOL 同時要 2 次，API 這次故障
    let failedCount = await withTaskGroup(of: Bool.self) { group in
        for _ in 0..<2 {
            group.addTask { (try? await value("SOL")) == nil }
        }
        var n = 0
        for await failed in group where failed { n += 1 }
        return n
    }
    log("② SOL 同時要 2 次（API 故障）：\(failedCount) 個失敗，API 呼叫 \(api.count("SOL")) 次")

    // ③ API 恢復後，SOL 再要一次
    do {
        let text = try await value("SOL")
        log("③ API 恢復後 SOL 再要一次：成功（\(text)），API 累計 \(api.count("SOL")) 次")
    } catch {
        log("③ API 恢復後 SOL 再要一次：還是失敗，API 累計 \(api.count("SOL")) 次")
    }
}

// MARK: - Bug 版
private enum L03Bug {
    actor SingleFlight {
        private let api: FakeApi
        private var values: [String: String] = [:]
        private var inFlight: [String: Task<String, Error>] = [:]

        init(api: FakeApi) {
            self.api = api
        }

        func value(for key: String) async throws -> String {
            if let cached = values[key] { return cached }
            if let task = inFlight[key] { return try await task.value }          // 有人正在載入：等同一個結果
            let task = Task { [api] in try await api.fetch(key) }
            inFlight[key] = task                                                 // 在 await 之前就登記
            let loaded = try await task.value                                    // 失敗時直接丟出去，下面兩行不會執行
            values[key] = loaded
            inFlight[key] = nil
            return loaded
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { api in
            let cache = SingleFlight(api: api)
            return { try await cache.value(for: $0) }
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    actor SingleFlight {
        private let api: FakeApi
        private var values: [String: String] = [:]
        private var inFlight: [String: Task<String, Error>] = [:]

        init(api: FakeApi) {
            self.api = api
        }

        func value(for key: String) async throws -> String {
            if let cached = values[key] { return cached }
            if let task = inFlight[key] { return try await task.value }          // 有人正在載入：等同一個結果
            let task = Task { [api] in try await api.fetch(key) }
            inFlight[key] = task                                                 // 在 await 之前就登記
            do {
                let loaded = try await task.value
                values[key] = loaded
                inFlight[key] = nil
                return loaded
            } catch {
                inFlight[key] = nil                                              // 失敗不要留著：下一次呼叫重新載入
                throw error
            }
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { api in
            let cache = SingleFlight(api: api)
            return { try await cache.value(for: $0) }
        }
    }
}

// MARK: - 演練設定
extension Coding04Async {
    static let l03 = Lab(
        id: "L03",
        title: "API 故障一次，之後永遠拿到同一個錯誤",
        question: "第 3 題：好幾個地方同時要同一份資料，怎麼只載入一次？",
        goal: "single-flight 要在 await 之前登記進行中的工作；成功與失敗都要把它清掉，失敗不快取，下一次才會重新載入。",
        steps: [
            "選「Bug 版」執行，看第 ① 輪 API 各被呼叫幾次（single-flight 有沒有生效），以及第 ③ 輪 SOL 的結果。",
            "選「修正版」執行，確認第 ③ 輪成功，API 累計 2 次。",
            "想一想：如果把失敗也存進 values，會發生什麼事？Token 過期時的刷新也是同樣的做法。",
        ],
        expectBug: "①「5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次」，②「2 個失敗，API 呼叫 1 次」，③「還是失敗，API 累計 1 次」（API 恢復了，卻根本沒再呼叫）。",
        expectFixed: "①② 和 Bug 版一樣；③「成功（SOL 的設定），API 累計 2 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if lines.count < 3 { return "演練沒有跑完：要用 scenario(log, make:) 執行" }
            if !lines.contains("① BTC 同時要 3 次、ETH 同時要 2 次：5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次") {
                return "value(for:) 還沒寫對：先看 values，再看 inFlight，都沒有才建立 Task，並在 await 之前存進 inFlight"
            }
            if !lines.contains("② SOL 同時要 2 次（API 故障）：2 個失敗，API 呼叫 1 次") {
                return "SOL 同時要 2 次，應該共用同一個 Task：API 只呼叫 1 次，兩個都拿到錯誤"
            }
            return lines.contains("③ API 恢復後 SOL 再要一次：還是失敗，API 累計 1 次")
                ? nil : "Bug 還沒重現：成功時才 inFlight[key] = nil，失敗時不要清，下一次呼叫才會等到同一個失敗的 Task"
        },
        checkFixed: { lines in
            if lines.count < 3 { return "演練沒有跑完：要用 scenario(log, make:) 執行" }
            if !lines.contains("① BTC 同時要 3 次、ETH 同時要 2 次：5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次") {
                return "value(for:) 還沒寫對：先看 values，再看 inFlight，都沒有才建立 Task，並在 await 之前存進 inFlight"
            }
            if !lines.contains("② SOL 同時要 2 次（API 故障）：2 個失敗，API 呼叫 1 次") {
                return "SOL 同時要 2 次，應該共用同一個 Task：API 只呼叫 1 次，兩個都拿到錯誤"
            }
            return lines.contains("③ API 恢復後 SOL 再要一次：成功（SOL 的設定），API 累計 2 次")
                ? nil : "失敗時要用 do/catch 把 inFlight[key] 清掉再 throw，下一次呼叫才會重新載入"
        }
    )
}
