// Coding04Async 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：數「同時有幾個在進行」的計數器、一個要抓的項目、API
// 的錯誤種類（哪些值得重試）、記錄等待時間的假 sleep。
import Foundation

/// 數同時有幾個請求在進行，並記下最多的時候是幾個（任何執行緒都可以呼叫）
final class C04Meter: @unchecked Sendable {
    private let lock = NSLock()
    private var current = 0
    private var highest = 0

    func enter() {
        lock.lock()
        current += 1
        highest = max(highest, current)
        lock.unlock()
    }

    func leave() {
        lock.lock()
        current -= 1
        lock.unlock()
    }

    /// 同時進行的最高紀錄
    var peak: Int {
        lock.lock()
        defer { lock.unlock() }
        return highest
    }
}

/// 要向 API 抓的一個項目：名稱，以及這個請求要花幾毫秒
struct C04Job: Sendable {
    let name: String
    let ms: Int
}

/// API 可能回傳的錯誤
enum C04ApiError: Error, Equatable {
    case timeout
    case serverBusy  // HTTP 503
    case rateLimited  // HTTP 429
    case badRequest  // HTTP 400
    case insufficientBalance  // 餘額不足

    /// 暫時性的錯誤，等一下再試有機會成功；其他的錯誤重試結果都一樣
    var isTransient: Bool {
        switch self {
        case .timeout, .serverBusy, .rateLimited: true
        case .badRequest, .insufficientBalance: false
        }
    }

    var text: String {
        switch self {
        case .timeout: "逾時"
        case .serverBusy: "伺服器忙碌 503"
        case .rateLimited: "請求太多 429"
        case .badRequest: "請求有問題 400"
        case .insufficientBalance: "餘額不足"
        }
    }
}

/// 假的 sleep：不真的等，只記下「被要求等多久」（毫秒）
final class C04SleepLog: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [Int] = []

    func sleep(_ ms: Int) async {
        record(ms)
    }

    private func record(_ ms: Int) {
        lock.lock()
        recorded.append(ms)
        lock.unlock()
    }

    var waits: [Int] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }
}

/// 數被呼叫了幾次
final class C04Calls: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0

    @discardableResult
    func next() -> Int {
        lock.lock()
        defer { lock.unlock() }
        value += 1
        return value
    }

    var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}
