// L04：把系統時間往回調，就繞過了自動鎖定（自動鎖定要用單調時鐘計時）
// 對應複習站：Exchange10AccountSecurity 第 4 題「自動鎖定（auto-lock）要做到什麼程度，才不只是『蓋一個畫面』？」
//        （Exchange04 的演練 6 練過「行情的新鮮度也要用單調時鐘」；這題是安全功能：計時被繞過，就等於沒有鎖。）
//
// 情境：App 進背景時記下時間，回前景時比較：離開超過 60 秒就要重新驗證（鎖定）。演練用一台假裝置模擬兩種時鐘：
//       系統時間（wall clock，使用者可以在設定裡改，網路校時也會讓它跳動）、單調時鐘（只會往前走、不受修改系統時間影響，含睡眠；
//       iOS 16 以上用 ContinuousClock，Android 用 SystemClock.elapsedRealtime()）。五個情境：
//       A. 離開 30 秒 → 不鎖
//       B. 離開 5 分鐘 → 鎖
//       C. 離開 5 分鐘，期間使用者把系統時間往回調 1 小時 → 鎖
//       D. 離開 10 秒，期間系統時間因網路校時往前跳了 1 天 → 不鎖
//       E. 沒有進背景的記錄（例如 App 被系統收掉重新啟動）→ 鎖（這個情境已經寫好）
//
// 你要寫：
//   ① Bug 版（L04Bug.onBackground、shouldLockOnForeground）：用 device.wallMs（系統時間）記下進背景的時間、計算離開多久。
//   ② 修正版（L04Fixed.onBackground、shouldLockOnForeground）：改用 device.continuousMs（單調時鐘）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange10-AccountSecurity/L04_AutoLockClock.swift
import Foundation
import LabKit

/// 假裝置：兩種時鐘。系統時間可以被使用者或網路校時改動，單調時鐘只會隨時間往前走。
private final class FakeDevice {
    private(set) var wallMs: Int64 = 1_700_000_000_000       // 系統時間
    private(set) var continuousMs: Int64 = 5_000_000          // 單調時鐘（含睡眠）

    func pass(_ ms: Int64) { wallMs += ms; continuousMs += ms }
    func userChangesSystemTime(by ms: Int64) { wallMs += ms }    // 只改系統時間；單調時鐘不受影響
}

private protocol AutoLock {
    func onBackground()
    func shouldLockOnForeground() -> Bool
}

private let timeoutMs: Int64 = 60_000
private let minute: Int64 = 60_000

/// 演練流程（兩個版本共用）：五個情境，每個情境各自一台全新的裝置與鎖
private func scenario(_ log: LabLog, makeLock: (FakeDevice) -> AutoLock) {
    var wrong = 0
    func check(_ name: String, expected: Bool, _ steps: (FakeDevice, AutoLock) -> Void, record: Bool = true) {
        let device = FakeDevice()
        let lock = makeLock(device)
        if record { lock.onBackground() }
        steps(device, lock)
        let locked = lock.shouldLockOnForeground()
        if locked == expected {
            log("\(name)：\(locked ? "鎖定" : "不鎖") ✓")
        } else {
            wrong += 1
            log("\(name)：\(locked ? "鎖定" : "不鎖") ✗（應該\(expected ? "鎖定" : "不鎖")）")
        }
    }
    check("A 離開 30 秒", expected: false) { d, _ in d.pass(30_000) }
    check("B 離開 5 分鐘", expected: true) { d, _ in d.pass(5 * minute) }
    check("C 離開 5 分鐘，系統時間往回調 1 小時", expected: true) { d, _ in
        d.pass(2 * minute)
        d.userChangesSystemTime(by: -60 * minute)
        d.pass(3 * minute)
    }
    check("D 離開 10 秒，系統時間往前跳 1 天", expected: false) { d, _ in
        d.pass(5_000)
        d.userChangesSystemTime(by: 24 * 60 * minute)
        d.pass(5_000)
    }
    check("E 沒有進背景的記錄", expected: true, { d, _ in d.pass(1_000) }, record: false)
    log("5 個情境，判斷錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private final class L04BugAutoLock: AutoLock {
    private let device: FakeDevice
    private var backgroundedAt: Int64?
    init(_ device: FakeDevice) { self.device = device }

    func onBackground() {
        // TODO ①-1：用 device.wallMs（系統時間）記下進背景的時間，存進 backgroundedAt
    }

    func shouldLockOnForeground() -> Bool {
        guard let start = backgroundedAt else { return true }      // 沒有記錄就當作要鎖
        // TODO ①-2：回傳「device.wallMs 減掉 start」是否大於等於 timeoutMs
        return false
    }
}

private enum L04Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { L04BugAutoLock($0) }
    }
}

// MARK: - 修正版
private final class L04FixedAutoLock: AutoLock {
    private let device: FakeDevice
    private var backgroundedAt: Int64?
    init(_ device: FakeDevice) { self.device = device }

    func onBackground() {
        // TODO ②-1：改用 device.continuousMs（單調時鐘）記下進背景的時間，存進 backgroundedAt
    }

    func shouldLockOnForeground() -> Bool {
        guard let start = backgroundedAt else { return true }      // 沒有記錄就當作要鎖
        // TODO ②-2：回傳「device.continuousMs 減掉 start」是否大於等於 timeoutMs
        return false
    }
}

private enum L04Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { L04FixedAutoLock($0) }
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("5 個情境") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange10AccountSecurity {
    static let l04 = Lab(
        id: "L04",
        title: "把系統時間往回調，就繞過了自動鎖定",
        question: "第 4 題：自動鎖定（auto-lock）要做到什麼程度，才不只是「蓋一個畫面」？",
        goal: "自動鎖定的計時要用單調時鐘（只會往前走、不受修改系統時間影響）。用系統時間計算「離開多久」，使用者往回調時間就能永遠不被鎖，往前跳則會被誤鎖。",
        steps: [
            "打開 Practice/Exchange/Exchange10-AccountSecurity/L04_AutoLockClock.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看五個情境哪些判斷錯了：往回調的 C 和往前跳的 D。",
            "想一想：系統時間往回調 1 小時，「現在減掉進背景的時間」會是正的還是負的？",
            "寫 TODO ②（修正版），選「修正版」執行，五個情境都要判斷對。",
            "進階：iOS 的 ProcessInfo.systemUptime 在裝置睡眠時不計時，不適合算「離開多久」；要用 ContinuousClock（iOS 16 起）或 mach_continuous_time()（iOS 15）。",
        ],
        expectBug: "「C 離開 5 分鐘，系統時間往回調 1 小時：不鎖 ✗」「D 離開 10 秒，系統時間往前跳 1 天：鎖定 ✗」，A、B、E 是對的：「5 個情境，判斷錯的有 2 個」。",
        expectFixed: "五個情境都是 ✓，「5 個情境，判斷錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, makeLock:) 執行" }
            if !lines.contains("B 離開 5 分鐘：鎖定 ✓") { return "Bug 版在系統時間沒被改的時候要算對：離開 5 分鐘要鎖定" }
            if !lines.contains("C 離開 5 分鐘，系統時間往回調 1 小時：不鎖 ✗（應該鎖定）") { return "Bug 還沒重現：用系統時間算，往回調 1 小時之後「現在減掉進背景的時間」是負的，不會鎖" }
            if !lines.contains("D 離開 10 秒，系統時間往前跳 1 天：鎖定 ✗（應該不鎖）") { return "Bug 還沒重現：用系統時間算，往前跳 1 天之後只離開 10 秒也會被鎖" }
            return n == 2 ? nil : "Bug 版應該判斷錯 2 個情境（C 和 D），目前 \(n) 個"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, makeLock:) 執行" }
            return n == 0 ? nil : "還有 \(n) 個情境判斷錯：進背景和回前景都要用 device.continuousMs（單調時鐘），不要用 wallMs"
        }
    )
}
