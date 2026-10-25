// L01：防連點的測試要真的等 300 毫秒才跑得完（函式裡直接讀 Date()）
// 對應複習站：Coding02TimeStreams 第 1 題「和時間有關的題目，為什麼要把時間當成參數傳進去？」
//
// 情境：「買入」按鈕防連點：第一次點擊立刻送出，之後 100 毫秒內的點擊都忽略（throttle，送第一個）。
//       測試要驗證這組點擊：第 0、30、150、300 毫秒各點一次，預期結果是 [送出, 忽略, 送出, 送出]。
//
// 你要寫：
//   ① Bug 版（L01Bug.Throttle.shouldFire）：在函式裡用 Date() 讀現在時間；距離上一次送出不到 intervalMs 毫秒就回傳 false，
//      否則記下這次的時間、回傳 true。跑起來會看到測試要真的等 300 多毫秒。
//   ② 修正版（L01Fixed.Throttle.shouldFire）：不能讀 Date()，改用 init 傳進來的 now() 取得現在幾毫秒，其餘邏輯一樣。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding02-TimeStreams/L01_ThrottleClock.swift
import Foundation
import LabKit

private let taps = [0, 30, 150, 300]
private let intervalMs = 100

private func text(_ results: [Bool]) -> String {
    "[" + results.map { $0 ? "送出" : "忽略" }.joined(separator: ", ") + "]"
}

/// 演練流程（兩個版本共用）：到了每個點擊的時間就點一下，記錄結果與測試花了多久
/// advanceTo：把時間走到某個毫秒（Bug 版真的等，修正版撥假時鐘）
private func scenario(_ log: LabLog, advanceTo: (Int) async -> Void, tap: () -> Bool) async {
    let start = DispatchTime.now()
    var results: [Bool] = []
    for time in taps {
        await advanceTo(time)
        results.append(tap())
    }
    let ms = Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
    log("點擊時間 \(taps) 毫秒 → \(text(results))")
    log("這個測試花了 \(ms) 毫秒")
}

/// 從「這個測試花了 N 毫秒」取出 N（自動檢查用）
private func elapsedMs(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("這個測試花了 ") }) else { return nil }
    return Int(line.dropFirst("這個測試花了 ".count).dropLast(" 毫秒".count))
}

// MARK: - Bug 版
private enum L01Bug {
    final class Throttle {
        private let intervalMs: Int
        private var lastFire: Date?

        init(intervalMs: Int) {
            self.intervalMs = intervalMs
        }

        func shouldFire() -> Bool {
            // TODO ①-1：let now = Date()，現在時間直接在函式裡讀
            // TODO ①-2：已經有 lastFire，而且 now.timeIntervalSince(last) * 1000 小於 intervalMs：回傳 false（忽略這次點擊）
            // TODO ①-3：否則記下 lastFire = now，回傳 true
            return true
        }
    }

    static func run(_ log: LabLog) async {
        let throttle = Throttle(intervalMs: intervalMs)
        let start = DispatchTime.now()
        await scenario(log, advanceTo: { time in
            let elapsed = Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)
            try? await Task.sleep(for: .milliseconds(max(0, time - elapsed)))   // 測試只能真的等
        }, tap: { throttle.shouldFire() })
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class Throttle {
        private let intervalMs: Int
        private let now: () -> Int                                    // 「現在幾毫秒」由外面傳進來
        private var lastFire: Int?

        init(intervalMs: Int, now: @escaping () -> Int) {
            self.intervalMs = intervalMs
            self.now = now
        }

        func shouldFire() -> Bool {
            // TODO ②-1：let current = now()，用傳進來的函式取得現在幾毫秒（不要呼叫 Date()）
            // TODO ②-2：已經有 lastFire，而且 current - last 小於 intervalMs：回傳 false
            // TODO ②-3：否則記下 lastFire = current，回傳 true
            return true
        }
    }

    static func run(_ log: LabLog) async {
        let clock = C02FakeClock()
        let throttle = Throttle(intervalMs: intervalMs, now: { clock.now })
        await scenario(log, advanceTo: { clock.advance(to: $0) }, tap: { throttle.shouldFire() })   // 假時鐘：直接撥時間
    }
}

// MARK: - 演練設定
extension Coding02TimeStreams {
    static let l01 = Lab(
        id: "L01",
        title: "防連點的測試要真的等 300 毫秒",
        question: "第 1 題：和時間有關的題目，為什麼要把時間當成參數傳進去？",
        goal: "把「現在時間」變成輸入（注入的函式或參數），測試就不用真的等，結果也不會因為機器忙碌而時對時錯。先寫出自己讀 Date() 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding02-TimeStreams/L01_ThrottleClock.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看最後一行「這個測試花了幾毫秒」；多按幾次，每次的數字一樣嗎？",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，比較花的時間與點擊結果。",
            "想一想：如果要測「300 毫秒」改成「30 秒」的防連點，Bug 版的測試要等多久？",
        ],
        expectBug: "結果 [送出, 忽略, 送出, 送出]，但測試花了 300 多毫秒（每次略有不同），因為真的在等。",
        expectFixed: "結果一樣是 [送出, 忽略, 送出, 送出]，測試花的時間不到 10 毫秒。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let ms = elapsedMs(lines) else { return "演練沒有跑完：要用 scenario(log, advanceTo:, tap:) 執行" }
            if !lines.contains("點擊時間 [0, 30, 150, 300] 毫秒 → [送出, 忽略, 送出, 送出]") {
                return "shouldFire 還沒寫對：用 Date() 記下上一次送出的時間，距離不到 100 毫秒就回傳 false"
            }
            return ms >= 250 ? nil : "Bug 還沒重現：Throttle 要在函式裡自己讀 Date()，測試才需要真的等"
        },
        checkFixed: { lines in
            guard let ms = elapsedMs(lines) else { return "演練沒有跑完：要用 scenario(log, advanceTo:, tap:) 執行" }
            if !lines.contains("點擊時間 [0, 30, 150, 300] 毫秒 → [送出, 忽略, 送出, 送出]") {
                return "shouldFire 還沒寫對：用 now() 取得現在時間，距離上一次送出不到 100 毫秒就回傳 false"
            }
            return ms < 100 ? nil : "測試花了 \(ms) 毫秒：Throttle 不能自己讀 Date()，要用傳進來的 now()，測試用假時鐘撥時間"
        }
    )
}
