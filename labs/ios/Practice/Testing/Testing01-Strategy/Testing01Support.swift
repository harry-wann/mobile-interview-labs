// Testing01Strategy 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   迷你測試執行器 T01Suite／T01Expect：這個 App 沒有 XCTest target，用它模擬「一個測試通過或失敗」。
//   被測的下單規則 T01OrderRules、出金規則 T01Withdrawal，以及模擬 UI 測試、系統時鐘的小工具。
import Foundation
import LabKit

// 迷你測試執行器

/// 迷你版的 XCTAssert：失敗時只記下訊息，不中斷測試
final class T01Expect {
    private(set) var failures: [String] = []
    /// 一共檢查了幾項（空的測試什麼都沒檢查，也會顯示「通過」，所以要數一下）
    private(set) var assertions = 0

    func equal<V: Equatable>(_ actual: V, _ expected: V, _ note: String = "") {
        assertions += 1
        guard actual != expected else { return }
        failures.append("預期 \(expected)，實際 \(actual)" + (note.isEmpty ? "" : "（\(note)）"))
    }

    func isTrue(_ condition: Bool, _ note: String) {
        assertions += 1
        if !condition { failures.append(note) }
    }
}

struct T01Result {
    let name: String
    let failures: [String]
    let assertions: Int
    var passed: Bool { failures.isEmpty }
}

/// 迷你版的測試套件：依序執行每個測試，統計通過幾個、花了幾毫秒，把摘要寫進 log
final class T01Suite {
    private var tests: [(name: String, body: (T01Expect) async -> Void)] = []

    func test(_ name: String, _ body: @escaping (T01Expect) async -> Void) {
        tests.append((name, body))
    }

    @discardableResult
    func run(label: String, log: LabLog) async -> [T01Result] {
        let start = DispatchTime.now()
        var results: [T01Result] = []
        for (name, body) in tests {
            let t = T01Expect()
            await body(t)
            results.append(T01Result(name: name, failures: t.failures, assertions: t.assertions))
        }
        let ms = Int(Double(DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
        let failed = results.filter { !$0.passed }
        for r in failed { log("  ✗ \(r.name)：\(r.failures[0])") }
        let checked = results.reduce(0) { $0 + $1.assertions }
        log("\(label)：跑了 \(results.count) 個案例，通過 \(results.count - failed.count)、失敗 \(failed.count)，檢查了 \(checked) 項，花了 \(ms) ms")
        return results
    }
}

/// 從 log 讀回 T01Suite 寫的摘要（自動檢查用）
struct T01Summary {
    let cases: Int
    let passed: Int
    let failed: Int
    let assertions: Int
    let ms: Int

    static func parse(_ lines: [String], label: String) -> T01Summary? {
        guard let line = lines.first(where: { $0.hasPrefix("\(label)：跑了 ") }) else { return nil }
        let body = line.dropFirst("\(label)：".count)        // 標籤本身可能有數字（例如 04:10），只讀後面的統計
        let numbers = body.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        guard numbers.count >= 5 else { return nil }
        return T01Summary(cases: numbers[0], passed: numbers[1], failed: numbers[2], assertions: numbers[3], ms: numbers[4])
    }
}

// 被測的程式：下單規則

enum T01OrderError: String, Equatable {
    case priceNotOnTick = "價格不是 1,000 日圓的倍數"
    case amountTooSmall = "數量低於最小下單量 0.0001 BTC"
    case amountTooLarge = "數量超過單筆上限 5 BTC"
}

enum T01OrderRules {
    /// 價格單位是日圓，數量單位是 satoshi（1 BTC = 100,000,000 satoshi）。回傳 nil 代表可以下單。
    static func validate(priceJPY: Int, amountSatoshi: Int) -> T01OrderError? {
        if priceJPY % 1_000 != 0 { return .priceNotOnTick }
        if amountSatoshi < 10_000 { return .amountTooSmall }
        if amountSatoshi > 500_000_000 { return .amountTooLarge }
        return nil
    }
}

/// 一個下單規則的測試案例
struct T01OrderCase {
    let name: String
    let priceJPY: Int
    let amountSatoshi: Int
    let expected: T01OrderError?
    /// 模擬 UI 的動畫要跑幾毫秒，錯誤訊息才會出現在畫面上
    let animationMs: Int

    static let all: [T01OrderCase] = [
        T01OrderCase(name: "正常的限價單", priceJPY: 15_000_000, amountSatoshi: 100_000, expected: nil, animationMs: 60),
        T01OrderCase(name: "價格不是 1,000 的倍數", priceJPY: 15_000_500, amountSatoshi: 100_000, expected: .priceNotOnTick, animationMs: 140),
        T01OrderCase(name: "剛好最小下單量", priceJPY: 15_000_000, amountSatoshi: 10_000, expected: nil, animationMs: 75),
        T01OrderCase(name: "剛好單筆上限", priceJPY: 15_000_000, amountSatoshi: 500_000_000, expected: nil, animationMs: 80),
        T01OrderCase(name: "最小價格", priceJPY: 1_000, amountSatoshi: 10_000, expected: nil, animationMs: 70),
        T01OrderCase(name: "超過單筆上限 1 satoshi", priceJPY: 15_000_000, amountSatoshi: 500_000_001, expected: .amountTooLarge, animationMs: 150),
        T01OrderCase(name: "一般價格與數量", priceJPY: 14_999_000, amountSatoshi: 250_000_000, expected: nil, animationMs: 65),
        T01OrderCase(name: "價格差 1 日圓", priceJPY: 999, amountSatoshi: 10_000, expected: .priceNotOnTick, animationMs: 130),
        T01OrderCase(name: "數量差 1 satoshi", priceJPY: 15_000_000, amountSatoshi: 9_999, expected: .amountTooSmall, animationMs: 85),
        T01OrderCase(name: "數量是 0", priceJPY: 16_500_000, amountSatoshi: 0, expected: .amountTooSmall, animationMs: 160),
        T01OrderCase(name: "價格與數量都錯：先報價格", priceJPY: 16_000_001, amountSatoshi: 600_000_000, expected: .priceNotOnTick, animationMs: 70),
        T01OrderCase(name: "數量遠超過上限", priceJPY: 15_000_000, amountSatoshi: 1_000_000_000, expected: .amountTooLarge, animationMs: 145),
    ]
}

/// 模擬 UI 測試（XCUITest）的代價。真正的 UI 測試要啟動 App、登入、等動畫、點按鈕，這裡用等待時間代替：
/// 冷啟動加登入 120 ms、輸入 40 ms、點確認 20 ms；錯誤訊息要等動畫結束才會出現在畫面上。
final class T01FakeOrderScreen {
    private let animationMs: Int
    private var priceJPY = 0
    private var amountSatoshi = 0
    private var tappedAt = DispatchTime.now()

    init(animationMs: Int) {
        self.animationMs = animationMs
    }

    /// 冷啟動、登入、進入下單頁（120 ms）
    func launch() async {
        try? await Task.sleep(for: .milliseconds(120))
    }

    /// 在價格與數量兩個輸入框打字（40 ms）
    func type(priceJPY: Int, amountSatoshi: Int) async {
        self.priceJPY = priceJPY
        self.amountSatoshi = amountSatoshi
        try? await Task.sleep(for: .milliseconds(40))
    }

    /// 點「確認」（20 ms）
    func tapConfirm() async {
        try? await Task.sleep(for: .milliseconds(20))
        tappedAt = DispatchTime.now()
    }

    /// 讀目前畫面上的錯誤訊息。點確認之後動畫還沒結束時，畫面上還沒有訊息（回傳 nil）
    func errorLabel() -> T01OrderError? {
        let elapsed = Int((DispatchTime.now().uptimeNanoseconds - tappedAt.uptimeNanoseconds) / 1_000_000)
        guard elapsed >= animationMs else { return nil }
        return T01OrderRules.validate(priceJPY: priceJPY, amountSatoshi: amountSatoshi)
    }
}

// 被測的程式：出金規則

enum T01Withdrawal {
    /// 單筆出金達到 100 萬日圓，要再做一次兩步驟驗證
    static func requiresTwoFactor(_ amountJPY: Int) -> Bool {
        if amountJPY >= 1_000_000 { return true }
        return false
    }

    /// 被改壞的版本：>= 變成 >。mutation testing 工具會自動產生這種版本，這裡手寫一個
    static func requiresTwoFactorMutant(_ amountJPY: Int) -> Bool {
        if amountJPY > 1_000_000 { return true }
        return false
    }
}

// 被測的程式：維護時段（時間相關）

/// 模擬「系統時間」：程式裡直接寫 Date() 讀到的就是它。真實的 Date() 沒辦法調，
/// 演練用它代表「這個測試剛好跑在哪個時間點」（例如 CI 在半夜跑）。
enum T01SystemClock {
    static var now = Date()
}

enum T01Time {
    private static var jst: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        return calendar
    }

    /// 2026-10-26（星期一）日本時間幾點幾分
    static func at(_ hour: Int, _ minute: Int) -> Date {
        jst.date(from: DateComponents(year: 2026, month: 10, day: 26, hour: hour, minute: minute))!
    }

    /// 這個時間點是當天的第幾分鐘（日本時間）
    static func minutesOfDay(_ date: Date) -> Int {
        let c = jst.dateComponents([.hour, .minute], from: date)
        return c.hour! * 60 + c.minute!
    }

    /// 維護時段：每天 04:00 到 04:30（含 04:00，不含 04:30），不能下單
    static func isInMaintenance(_ date: Date) -> Bool {
        (240..<270).contains(minutesOfDay(date))
    }
}
