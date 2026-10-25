// L06：使用者把手機時間調早一小時，20 秒前的價格被當成最新的（過期判斷用了系統時間）
// 對應複習站：Exchange04Reconnect 第 6 題「斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？」
//
// 情境：App 記錄「最後收到資料的時間」，超過 5 秒沒有新資料，就把價格變灰，並阻止用這個價格下市價單（成行）。
//       演練用一個假的時鐘，同時有「系統時間」（使用者可以改）和「單調時鐘」（只會往前走，不受使用者修改影響）。
//       兩種情況：
//       ① 收到資料後，連線斷了，過了 20 秒，使用者（或系統自動校時）把系統時間調早一小時，這時價格已經是 20 秒前的舊價格；
//       ② 剛收到新資料，使用者把系統時間調晚一小時。
//
// 你要寫：
//   ① Bug 版（L06Bug.Tracker）：收到資料時記下 clock.systemMs；isStale 用 clock.systemMs 算，距離上次 ≥ 5000 毫秒就過期。
//   ② 修正版（L06Fixed.Tracker）：改用 clock.monotonicMs（單調時鐘）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange04-Reconnect/L06_MonotonicClock.swift
import Foundation
import LabKit

/// 假的時鐘：系統時間可以被使用者改，單調時鐘只會往前走
private final class FakeClock {
    private(set) var systemMs: Int64 = 1_700_000_000_000
    private(set) var monotonicMs: Int64 = 100_000

    func advance(_ ms: Int64) { systemMs += ms; monotonicMs += ms }
    func userChangesSystemTime(by ms: Int64) { systemMs += ms }       // 只動系統時間，單調時鐘不受影響
}

private protocol FreshnessTracker {
    init()
    mutating func didReceiveData(clock: FakeClock)
    func isStale(clock: FakeClock) -> Bool
}

/// 演練流程（兩個版本共用）：兩種情況各判斷一次，看有沒有判斷錯
private func scenario<T: FreshnessTracker>(_ log: LabLog, tracker: T.Type) {
    var wrong = 0

    var a = T()
    let clockA = FakeClock()
    a.didReceiveData(clock: clockA)
    clockA.advance(20_000)                                      // 連線斷了，20 秒沒有新資料
    clockA.userChangesSystemTime(by: -3_600_000)                // 使用者把系統時間調早一小時
    let staleA = a.isStale(clock: clockA)
    log("① 20 秒沒有新資料，系統時間被調早一小時：價格\(staleA ? "已過期，變灰並擋下市價單" : "被當成最新的，下單按鈕還開著")")
    if !staleA { wrong += 1 }

    var b = T()
    let clockB = FakeClock()
    clockB.advance(1_000)
    b.didReceiveData(clock: clockB)
    clockB.userChangesSystemTime(by: 3_600_000)                 // 使用者把系統時間調晚一小時
    let staleB = b.isStale(clock: clockB)
    log("② 剛收到新資料，系統時間被調晚一小時：價格\(staleB ? "被誤判成過期，下單被擋下" : "是最新的，可以下單")")
    if staleB { wrong += 1 }

    log("2 種情況裡，過期判斷錯誤 \(wrong) 次")
}

// MARK: - Bug 版
private enum L06Bug {
    struct Tracker: FreshnessTracker {
        private var lastDataAt: Int64?
        init() {}

        mutating func didReceiveData(clock: FakeClock) {
            // TODO ①-1：收到資料時，把 lastDataAt 設成 clock.systemMs（系統時間）
        }

        func isStale(clock: FakeClock) -> Bool {
            // TODO ①-2：從來沒收到過資料（lastDataAt 是 nil）算過期；否則 clock.systemMs 距離 lastDataAt 達 5_000 毫秒以上就過期
            return true
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, tracker: Tracker.self)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    struct Tracker: FreshnessTracker {
        private var lastDataAt: Int64?
        init() {}

        mutating func didReceiveData(clock: FakeClock) {
            // TODO ②-1：收到資料時，把 lastDataAt 設成 clock.monotonicMs（單調時鐘）
        }

        func isStale(clock: FakeClock) -> Bool {
            // TODO ②-2：從來沒收到過資料（lastDataAt 是 nil）算過期；否則 clock.monotonicMs 距離 lastDataAt 達 5_000 毫秒以上就過期
            return true
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, tracker: Tracker.self)
    }
}

// MARK: - 演練設定
extension Exchange04Reconnect {
    static let l06 = Lab(
        id: "L06",
        title: "手機時間調早一小時，舊價格被當成最新的",
        question: "第 6 題：斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？",
        goal: "判斷價格是不是過期，要用單調時鐘計時，不受使用者修改系統時間的影響。過期的價格要擋下市價單。先寫出用系統時間的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange04-Reconnect/L06_MonotonicClock.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看兩種情況各判斷成什麼、有沒有判斷錯。",
            "想一想：使用者把系統時間調早一小時，「現在 − 最後收到資料的時間」會變成多少？",
            "寫 TODO ②（修正版），選「修正版」執行，兩種情況都要判斷對。",
            "進階：iOS 的 systemUptime 在裝置睡眠時不會增加；App 進背景時已經主動斷線，回前景一律先標示過期，所以不需要靠這個時鐘跨過背景。顯示橫幅前先等 2 到 3 秒的寬限時間，避免短暫抖動讓畫面閃爍。",
        ],
        expectBug: "① 被當成最新的（距離上次 −3580 秒，不到 5 秒），下單按鈕還開著；② 被誤判成過期，「2 種情況裡，過期判斷錯誤 2 次」。",
        expectFixed: "① 已過期，變灰並擋下市價單；② 是最新的，可以下單，「2 種情況裡，過期判斷錯誤 0 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("2 種情況裡") }) { return "演練沒有跑完：要用 scenario(log, tracker:) 執行" }
            return lines.contains("2 種情況裡，過期判斷錯誤 2 次") ? nil : "Bug 還沒重現：didReceiveData、isStale 都改用 clock.systemMs（系統時間），兩種情況都會判斷錯"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("2 種情況裡") }) { return "演練沒有跑完：要用 scenario(log, tracker:) 執行" }
            return lines.contains("2 種情況裡，過期判斷錯誤 0 次") ? nil : "didReceiveData、isStale 都要用 clock.monotonicMs（單調時鐘）；從來沒收到過資料也算過期"
        }
    )
}
