// Testing02iOSTesting 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   迷你測試執行器 T02Suite／T02Expect：這個 App 沒有 XCTest target，
// 用它模擬測試（可以依序或平行執行，
//   也會記下「測試已經結束之後才出現的斷言失敗」）。
//   以及四個演練用到的小東西：設定單例與價格格式、價格 ViewModel、
// 啟動參數與假伺服器、截圖比對用的圖片。
import Foundation
import LabKit

// 迷你測試執行器

/// 迷你版的 XCTAssert／#expect：失敗時只記下訊息，不中斷測試。
/// 測試結束後才執行的斷言，失敗會記在 lateFailures。
final class T02Expect: @unchecked Sendable {
    private let lock = NSLock()
    private var failureList: [String] = []
    private var lateList: [String] = []
    private var count = 0
    private var finished = false

    func equal<V: Equatable>(_ actual: V, _ expected: V, _ note: String = "") {
        lock.lock()
        defer { lock.unlock() }
        guard actual != expected else {
            if !finished { count += 1 }
            return
        }
        let message =
            "預期 \(expected)，實際 \(actual)" + (note.isEmpty ? "" : "（\(note)）")
        if finished {
            lateList.append(message)
        } else {
            count += 1; failureList.append(message)
        }
    }

    func finish() {
        lock.lock()
        finished = true
        lock.unlock()
    }

    /// 測試函式結束之前記下的失敗
    var failures: [String] {
        lock.lock(); defer { lock.unlock() }; return failureList
    }
    /// 測試函式結束之後才出現的失敗（執行器已經算完結果，沒有人會看到）
    var lateFailures: [String] {
        lock.lock(); defer { lock.unlock() }; return lateList
    }
    /// 測試函式結束之前一共檢查了幾項
    var assertions: Int { lock.lock(); defer { lock.unlock() }; return count }
}

struct T02Result {
    let name: String
    let failures: [String]
    let assertions: Int
    var passed: Bool { failures.isEmpty }
}

/// 迷你版的測試套件。parallel = true 時所有測試同時開始（Swift Testing
/// 的預設）；false 時一個跑完才跑下一個（XCTest 預設）
final class T02Suite {
    private var tests: [(name: String, body: (T02Expect) async -> Void)] = []

    func test(_ name: String, _ body: @escaping (T02Expect) async -> Void) {
        tests.append((name, body))
    }

    /// settleMs：全部測試結束後多等幾毫秒，讓沒有被 await 的 Task 有機會跑完，
    /// 才抓得到「結束之後才失敗」的斷言
    @discardableResult
    func run(
        label: String, parallel: Bool = false, settleMs: Int = 0, log: LabLog
    ) async -> [T02Result] {
        let items = tests.map {
            (name: $0.name, body: $0.body, expect: T02Expect())
        }
        if parallel {
            await withTaskGroup(of: Void.self) { group in
                for item in items {
                    group.addTask {
                        await item.body(item.expect)
                        item.expect.finish()
                    }
                }
            }
        } else {
            for item in items {
                await item.body(item.expect)
                item.expect.finish()
            }
        }
        let results = items.map {
            T02Result(
                name: $0.name, failures: $0.expect.failures,
                assertions: $0.expect.assertions)
        }
        let failed = results.filter { !$0.passed }
        for r in failed { log("  ✗ \(r.name)：\(r.failures[0])") }
        log(
            "\(label)：跑了 \(results.count) 個測試，通過 \(results.count - failed.count)、失敗 \(failed.count)，檢查了 \(results.reduce(0) { $0 + $1.assertions }) 項"
        )
        if settleMs > 0 {
            try? await Task.sleep(for: .milliseconds(settleMs))
            let late = items.reduce(0) { $0 + $1.expect.lateFailures.count }
            if late > 0 { log("\(label)：測試結束後才收到 \(late) 個失敗，沒有被算進結果") }
        }
        return results
    }
}

/// 從 log 讀回 T02Suite 寫的摘要（自動檢查用）
struct T02Summary {
    let cases: Int
    let passed: Int
    let failed: Int
    let assertions: Int
    /// 測試結束之後才收到的失敗數（沒有這一行就是 0）
    let lateFailures: Int

    static func parse(_ lines: [String], label: String) -> T02Summary? {
        guard let line = lines.first(where: { $0.hasPrefix("\(label)：跑了 ") })
        else { return nil }
        let numbers = line.dropFirst("\(label)：".count).split(whereSeparator: {
            !$0.isNumber
        }).compactMap { Int($0) }
        guard numbers.count >= 4 else { return nil }
        let lateLine = lines.first(where: { $0.hasPrefix("\(label)：測試結束後才收到 ") }
        )
        let late =
            lateLine.flatMap {
                $0.dropFirst("\(label)：測試結束後才收到 ".count).split(whereSeparator: {
                    !$0.isNumber
                }).first.flatMap { Int($0) }
            } ?? 0
        return T02Summary(
            cases: numbers[0], passed: numbers[1], failed: numbers[2],
            assertions: numbers[3], lateFailures: late)
    }
}

// L01：設定單例與價格格式

enum T02Currency {
    case jpy, usd
}

/// App 的設定。`shared` 是整個 App 共用的單例
final class T02AppSettings {
    static let shared = T02AppSettings()
    var currency: T02Currency = .jpy
}

struct T02PriceFormatter {
    let settings: T02AppSettings

    /// 日圓顯示 ¥1,500,000；美元（匯率 150）顯示 $10,000
    func format(_ amountJPY: Int) -> String {
        switch settings.currency {
        case .jpy: return "¥" + T02PriceFormatter.grouped(amountJPY)
        case .usd: return "$" + T02PriceFormatter.grouped(amountJPY / 150)
        }
    }

    static func grouped(_ value: Int) -> String {
        let digits = String(value)
        var out = ""
        for (i, ch) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(ch)
        }
        return out
    }
}

// L02：價格 ViewModel（正確的與被改壞的）

protocol T02PriceLoading: AnyObject {
    var price: Int { get }
    func load() async
}

/// 向伺服器載入價格，約 30 ms
final class T02PriceViewModel: T02PriceLoading {
    private(set) var price = 0

    func load() async {
        try? await Task.sleep(for: .milliseconds(30))
        price = 15_000_000
    }
}

/// 被改壞的版本：載入完忘了把價格存起來，畫面永遠顯示 0
final class T02BrokenPriceViewModel: T02PriceLoading {
    private(set) var price = 0

    func load() async {
        try? await Task.sleep(for: .milliseconds(30))
    }
}

// L03：啟動參數、真伺服器與 stub

protocol T02PriceAPI {
    func btcPrice() async -> Int
}

/// stub：固定回傳同一個價格
struct T02StubAPI: T02PriceAPI {
    func btcPrice() async -> Int { 15_000_000 }
}

/// 連到真的伺服器：行情一直在動，
/// 每次問到的價格都不一樣（演練用固定的一串價格代表）
final class T02LiveAPI: T02PriceAPI {
    static var calls = 0
    private static let prices = [
        15_012_300, 15_000_000, 14_987_650, 15_031_200,
    ]

    func btcPrice() async -> Int {
        defer { T02LiveAPI.calls += 1 }
        return T02LiveAPI.prices[T02LiveAPI.calls % T02LiveAPI.prices.count]
    }
}

enum T02Screen: String {
    case login, orderForm
}

/// 啟動後的 App：用哪個 API、從哪個畫面開始
struct T02App {
    let api: T02PriceAPI
    let startScreen: T02Screen
    /// 從登入畫面走到下單畫面要幾個步驟（輸入帳號、輸入密碼、輸入兩步驟驗證碼、
    /// 進入首頁再點下單）
    static let loginSteps = 4
}

/// 累計 UI 測試多做了幾個登入步驟
final class T02Tally {
    var steps = 0
}

// L04：截圖比對用的圖片

/// 灰階圖片，每個像素 0（黑）到 255（白）
struct T02Image {
    let width: Int
    let height: Int
    var pixels: [Int]

    /// 和另一張圖比：有幾個像素不一樣、最大差多少
    func diff(from other: T02Image) -> (count: Int, maxDelta: Int) {
        var count = 0
        var maxDelta = 0
        for i in 0..<min(pixels.count, other.pixels.count) {
            let delta = abs(pixels[i] - other.pixels[i])
            if delta > 0 { count += 1 }
            maxDelta = max(maxDelta, delta)
        }
        return (count, maxDelta)
    }
}

enum T02Device {
    case simulatorA, simulatorB
}

enum T02Renderer {
    /// 把訂單簿的一列畫成 60×12 的灰階圖（用固定的演算法模擬，不是真的畫字）。
    /// simulatorB 代表另一台模擬器或另一個 iOS 版本：
    /// 字體邊緣的反鋸齒讓一小部分像素的亮度差 1 到 3。
    /// clipped = true 代表真的 bug：價格被切掉右邊 20 欄。
    static func orderBookRow(
        price: String, device: T02Device, clipped: Bool = false
    ) -> T02Image {
        let width = 60
        let height = 12
        var pixels = [Int](repeating: 255, count: width * height)
        var seed = price.unicodeScalars.reduce(7) {
            ($0 &* 31 &+ Int($1.value)) % 9973
        }
        for i in 0..<pixels.count {
            seed = (seed &* 1_103_515_245 &+ 12_345) % 2_147_483_648
            // 字的筆畫
            if (seed >> 8) % 4 == 0 { pixels[i] = 20 }
        }
        if device == .simulatorB {
            var noise = 99
            for i in 0..<pixels.count {
                noise = (noise &* 1_103_515_245 &+ 12_345) % 2_147_483_648
                guard (noise >> 8) % 20 == 0 else { continue }
                let delta = (noise >> 16) % 3 + 1
                pixels[i] = min(
                    255,
                    max(0, pixels[i] + ((noise >> 4) % 2 == 0 ? delta : -delta))
                )
            }
        }
        if clipped {
            for y in 0..<height {
                for x in 40..<width { pixels[y * width + x] = 255 }
            }
        }
        return T02Image(width: width, height: height, pixels: pixels)
    }
}
