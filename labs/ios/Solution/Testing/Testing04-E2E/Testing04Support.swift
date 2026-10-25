// Testing04E2E 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   迷你測試執行器 T04Suite／T04Expect：這個 App 沒有 UI 測試 target，用它模擬
// E2E 測試的通過與失敗。
//   TOTP（兩步驟驗證碼）的正確算法 T04Totp（RFC 6238）、假的時鐘、
// 假的驗證伺服器、假的登入畫面。
//   假的交易所後端 T04FakeExchange（帳號與未成交訂單），以及 CI 重試用的
// T04TestCase／T04Report。
import CryptoKit
import Foundation
import LabKit

// 迷你測試執行器

/// 迷你版的 XCTAssert：失敗時只記下訊息，不中斷測試
final class T04Expect {
    private(set) var failures: [String] = []
    private(set) var assertions = 0

    func equal<V: Equatable>(_ actual: V, _ expected: V, _ note: String = "") {
        assertions += 1
        guard actual != expected else { return }
        failures.append(
            "預期 \(expected)，實際 \(actual)" + (note.isEmpty ? "" : "（\(note)）"))
    }

    func isTrue(_ condition: Bool, _ note: String) {
        assertions += 1
        if !condition { failures.append(note) }
    }
}

/// 迷你版的測試套件：依序執行，把摘要寫進 log
final class T04Suite {
    private var tests: [(name: String, body: (T04Expect) async -> Void)] = []

    func test(_ name: String, _ body: @escaping (T04Expect) async -> Void) {
        tests.append((name, body))
    }

    func run(label: String, log: LabLog) async {
        var failed: [(String, String)] = []
        var checked = 0
        for (name, body) in tests {
            let t = T04Expect()
            await body(t)
            checked += t.assertions
            if let first = t.failures.first { failed.append((name, first)) }
        }
        for (name, reason) in failed { log("  ✗ \(name)：\(reason)") }
        log(
            "\(label)：跑了 \(tests.count) 個測試，通過 \(tests.count - failed.count)、失敗 \(failed.count)，檢查了 \(checked) 項"
        )
    }
}

/// 從 log 讀回 T04Suite 寫的摘要（自動檢查用）
struct T04Summary {
    let cases: Int
    let passed: Int
    let failed: Int
    let assertions: Int

    static func parse(_ lines: [String], label: String) -> T04Summary? {
        guard let line = lines.first(where: { $0.hasPrefix("\(label)：跑了 ") })
        else { return nil }
        let numbers = line.dropFirst("\(label)：".count).split(whereSeparator: {
            !$0.isNumber
        }).compactMap { Int($0) }
        guard numbers.count >= 4 else { return nil }
        return T04Summary(
            cases: numbers[0], passed: numbers[1], failed: numbers[2],
            assertions: numbers[3])
    }
}

// L01：TOTP、假的時鐘、假的伺服器、假的登入畫面

/// TOTP（Time-based One-Time Password，RFC 6238）：用密鑰和現在的時間算出 6
/// 位數驗證碼，每 30 秒換一個
enum T04Totp {
    /// 測試帳號用的固定密鑰。這裡直接用 RFC 6238 附錄的測試密鑰（20
    /// 個位元組）；
    /// 正式 App 的密鑰通常是 Base32 字串，解碼後就是這樣的位元組。
    static let testSecret = Data("12345678901234567890".utf8)

    static func code(
        secret: Data = testSecret, at seconds: TimeInterval, digits: Int = 6,
        step: Int = 30
    ) -> String {
        var counter = UInt64(seconds / Double(step)).bigEndian
        let message = withUnsafeBytes(of: &counter) { Data($0) }
        let mac = Data(
            HMAC<Insecure.SHA1>.authenticationCode(
                for: message, using: SymmetricKey(data: secret)))
        let offset = Int(mac[mac.count - 1] & 0x0f)
        let truncated =
            (UInt32(mac[offset] & 0x7f) << 24) | (UInt32(mac[offset + 1]) << 16)
            | (UInt32(mac[offset + 2]) << 8) | UInt32(mac[offset + 3])
        var modulus = 1
        for _ in 0..<digits { modulus *= 10 }
        return String(format: "%0\(digits)d", Int(truncated) % modulus)
    }

    /// 目前這個 30 秒的區間還剩幾秒
    static func secondsLeft(at seconds: TimeInterval, step: Int = 30) -> Double
    {
        Double(step) - seconds.truncatingRemainder(dividingBy: Double(step))
    }

    /// RFC 6238 附錄的測試向量（6 位數）：確認上面的算法是對的
    static func selfCheck() -> Bool {
        code(at: 59) == "287082" && code(at: 1_111_111_109) == "081804"
            && code(at: 1_234_567_890) == "005924"
    }
}

/// 假的時鐘（單位：秒）。測試和伺服器看同一個時鐘；UI 動作用 advance
/// 代表「花了這麼久」，不會真的等
final class T04Clock {
    private(set) var now: TimeInterval

    init(start: TimeInterval) {
        now = start
    }

    func advance(_ seconds: TimeInterval) {
        now += seconds
    }
}

/// 假的驗證伺服器：只接受「現在這個 30 秒」的驗證碼（嚴格的伺服器）
final class T04FakeAuthServer {
    private let clock: T04Clock

    init(clock: T04Clock) {
        self.clock = clock
    }

    func verify(code: String) -> Bool {
        code == T04Totp.code(at: clock.now)
    }
}

/// 假的登入畫面。每個動作花的時間：輸入帳號密碼 2.5 秒、等兩步驟驗證畫面出現 1.
/// 5 秒、送出驗證碼 0.5 秒
final class T04FakeLoginUI {
    private let clock: T04Clock
    private let server: T04FakeAuthServer
    private(set) var loggedIn = false
    private(set) var errorMessage = ""

    init(clock: T04Clock, server: T04FakeAuthServer) {
        self.clock = clock
        self.server = server
    }

    func typeCredentials() async { clock.advance(2.5) }
    func waitForTwoFactorScreen() async { clock.advance(1.5) }

    func submitCode(_ code: String) async {
        // 驗證碼送到伺服器時，已經過了 0.5 秒
        clock.advance(0.5)
        if server.verify(code: code) {
            loggedIn = true
        } else {
            errorMessage = "驗證碼過期或錯誤"
        }
    }
}

// L02：假的交易所後端

/// 假的交易所後端：每個帳號有自己的未成交訂單
final class T04FakeExchange {
    private var orders: [String: [String]] = [:]
    private var nextAccount = 1

    /// 建立一個全新的測試帳號（相當於用 API 建立乾淨的帳號狀態）
    func createAccount() -> String {
        defer { nextAccount += 1 }
        return "e2e-\(nextAccount)"
    }

    func placeLimitBuy(account: String, priceJPY: Int) {
        orders[account, default: []].append("買 @\(priceJPY)")
    }

    /// 撤掉第一筆未成交訂單；回傳有沒有真的撤掉（沒有訂單可撤時回傳 false）
    @discardableResult
    func cancelFirst(account: String) -> Bool {
        guard var list = orders[account], !list.isEmpty else { return false }
        list.removeFirst()
        orders[account] = list
        return true
    }

    func openOrders(account: String) -> [String] {
        orders[account] ?? []
    }
}

// L03：CI 重試

/// 一個 E2E 測試。run(attempt) 回傳這一次有沒有通過；attempt 從 1 開始
struct T04TestCase {
    let name: String
    let run: (_ attempt: Int) async -> Bool

    /// 演練用的 4 個測試：穩定通過 2 個、第一次失敗第二次才通過（時間類 flaky）
    /// 1 個、每次都失敗（真的 bug）1 個
    static let suite: [T04TestCase] = [
        T04TestCase(name: "登入加兩步驟驗證") { _ in true },
        // 第一次等畫面等不夠久
        T04TestCase(name: "限價買單到撤單") { attempt in attempt >= 2 },
        T04TestCase(name: "日圓入金說明畫面") { _ in true },
        // 真的 bug：每次都失敗
        T04TestCase(name: "出庫新增地址") { _ in false },
    ]
}

/// CI 的測試報告
struct T04Report {
    var passed: [String] = []
    var flakyPassed: [String] = []  // 重試之後才通過的
    var failed: [String] = []
}
