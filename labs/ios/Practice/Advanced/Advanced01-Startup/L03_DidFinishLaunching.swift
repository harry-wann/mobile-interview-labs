// L03：網路一慢，使用者盯著 launch screen 好幾秒（didFinishLaunching 裡同步初始化所有 SDK）
// 對應複習站：Advanced01Startup 第 4 題「`didFinishLaunching` 做太多事會怎樣？什麼是預熱？」
//
// 情境：application(_:didFinishLaunchingWithOptions:) 在主執行緒執行，系統要等它回傳才會畫出第一個畫面（首幀）。
//        這個 App 啟動時要初始化 5 件事：當機回報（15 ms）、分析 SDK（80 ms）、廣告 SDK（120 ms）、資料庫（開啟並升級資料表，100 ms），
//        還有向伺服器要遠端設定。這次網路很慢，遠端設定要 1500 ms 才回得來。
//        （演練用一條背景執行緒代表主執行緒，不會真的把 App 的畫面卡住。）
//
// 你要寫：
//   ① Bug 版：在 didFinishLaunching 裡把 5 件事依序同步做完，遠端設定 timeout 傳 nil（一直等到回應）。
//   ② 修正版：首幀之前只做當機回報；首幀之後用 Task.detached(priority: .utility) 在背景做其餘的事，遠端設定加 0.3 秒逾時。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced01-Startup/L03_DidFinishLaunching.swift
import Foundation
import LabKit

/// 5 個初始化步驟（兩個版本共用）。每個都會花時間，並寫一行 log
private final class LaunchSteps {
    let log: LabLog
    init(_ log: LabLog) { self.log = log }

    func crashReporter() { a01Work(15); log("初始化 CrashReporter") }
    func analytics() { a01Work(80); log("初始化 Analytics") }
    func adsSDK() { a01Work(120); log("初始化 AdsSDK") }
    func database() { a01Work(100); log("初始化 Database（開啟並升級資料表）") }

    /// 向伺服器要遠端設定。這次網路很慢，1500 ms 後才回得來。timeout 是 nil 代表一直等；有值就最多等那麼多秒
    func remoteConfig(timeout: TimeInterval?) {
        let reply = DispatchSemaphore(value: 0)
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.5) { reply.signal() }    // 伺服器 1.5 秒後才回
        if let timeout {
            if reply.wait(timeout: .now() + timeout) == .timedOut {
                log("遠端設定逾時（\(Int(timeout * 1000)) ms），先用上次的設定")
                return
            }
        } else {
            reply.wait()                                                                // 沒有逾時：一直等到回應為止
        }
        log("取得遠端設定")
    }
}

/// 演練流程（兩個版本共用）：didFinishLaunching → 首幀 → 首幀之後的工作 → 等背景工作做完
private func scenario(
    _ log: LabLog,
    didFinishLaunching: (LaunchSteps) -> Void,
    afterFirstFrame: (LaunchSteps) -> Task<Void, Never>?
) async {
    let steps = LaunchSteps(log)
    let t0 = A01Clock.nowMs()
    didFinishLaunching(steps)
    log("首幀畫出來：\(Int(A01Clock.nowMs() - t0)) ms")
    let deferred = afterFirstFrame(steps)
    await deferred?.value
    log("所有初始化做完：\(Int(A01Clock.nowMs() - t0)) ms")
}

// MARK: - Bug 版
private enum L03Bug {
    static func run(_ log: LabLog) async {
        await Task.detached {
            await scenario(log, didFinishLaunching: { steps in
                // TODO ①：依序呼叫 steps.crashReporter()、analytics()、adsSDK()、database()，
                //        最後 steps.remoteConfig(timeout: nil)：同步等網路，網路慢就一直卡著
                _ = steps
            }, afterFirstFrame: { _ in nil })
        }.value
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func run(_ log: LabLog) async {
        await Task.detached {
            await scenario(log, didFinishLaunching: { steps in
                // TODO ②-1：首幀之前只呼叫 steps.crashReporter()（當機回報不能延後）
                _ = steps
            }, afterFirstFrame: { steps in
                // TODO ②-2：回傳 Task.detached(priority: .utility) { … }，在裡面依序做 analytics()、adsSDK()、database()，
                //          最後 steps.remoteConfig(timeout: 0.3)：網路的工作一定要設逾時
                _ = steps
                return nil
            })
        }.value
    }
}

// MARK: - 演練設定
private func firstFrameIndex(_ lines: [String]) -> Int? { lines.firstIndex { $0.hasPrefix("首幀畫出來：") } }

extension Advanced01Startup {
    static let l03 = Lab(
        id: "L03",
        title: "網路一慢，使用者盯著 launch screen 好幾秒",
        question: "第 4 題：`didFinishLaunching` 做太多事會怎樣？什麼是預熱？",
        goal: "首幀之前只做首畫面必須的事。分析、廣告、資料庫升級、遠端設定都等首幀之後；網路的工作一定要設逾時。先寫出全部同步做的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced01-Startup/L03_DidFinishLaunching.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看「首幀畫出來」的毫秒數，以及每個 SDK 初始化的順序。",
            "寫 TODO ②，選「修正版」執行：首幀約 15 ms，只有 CrashReporter 排在它前面；其他 SDK 的 log 都在首幀之後，遠端設定在 300 ms 後逾時。",
            "進階：Instruments 的 App Launch 範本可以看真實 App 的啟動各階段；在 didFinishLaunching 前後各打一個 OSSignposter 的 interval，就能在時間軸上看到這一段。",
            "想一想：iOS 15 之後有預熱（prewarm）。為什麼量啟動時間不能從行程建立時間算起？（見這個單元的第 4 題）",
        ],
        expectBug: "「首幀畫出來」約 1800 ms（15 + 80 + 120 + 100 + 1500）；5 個 SDK 的 log 全部排在首幀之前，遠端設定一直等到「取得遠端設定」。",
        expectFixed: "「首幀畫出來」約 15 ms，前面只有「初始化 CrashReporter」；其餘 SDK 在首幀之後初始化，「遠端設定逾時（300 ms）」；全部做完約 0.9 秒（背景用 utility 優先權，系統忙的時候會更久）。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let first = a01Number(in: lines, prefix: "首幀畫出來："), let frameAt = firstFrameIndex(lines) else { return "演練沒有跑完" }
            let before = lines[..<frameAt]
            for name in ["CrashReporter", "Analytics", "AdsSDK", "Database"] where !before.contains(where: { $0.hasPrefix("初始化 \(name)") }) {
                return "\(name) 還沒在首幀之前初始化：Bug 版要在 didFinishLaunching 裡同步做完全部"
            }
            if !before.contains("取得遠端設定") { return "遠端設定要在首幀之前同步等到回應（remoteConfig(timeout: nil)）" }
            return first >= 1000 ? nil : "首幀只花了 \(Int(first)) ms：Bug 版同步等網路，應該要超過 1 秒"
        },
        checkFixed: { lines in
            guard let first = a01Number(in: lines, prefix: "首幀畫出來："), let frameAt = firstFrameIndex(lines) else { return "演練沒有跑完" }
            let before = Array(lines[..<frameAt])
            let after = Array(lines[(frameAt + 1)...])
            if !before.contains(where: { $0.hasPrefix("初始化 CrashReporter") }) { return "CrashReporter 要在首幀之前初始化：當機回報不能延後" }
            if let late = before.first(where: { $0.hasPrefix("初始化 ") && !$0.hasPrefix("初始化 CrashReporter") }) { return "「\(late)」不該在首幀之前：延到首幀之後（Task.detached）" }
            if first > 250 { return "首幀花了 \(Int(first)) ms，太久：didFinishLaunching 裡只留 CrashReporter" }
            for name in ["Analytics", "AdsSDK", "Database"] where !after.contains(where: { $0.hasPrefix("初始化 \(name)") }) {
                return "\(name) 在首幀之後沒有初始化：延後不是不做"
            }
            if !after.contains(where: { $0.hasPrefix("遠端設定逾時") }) { return "遠端設定要加逾時：remoteConfig(timeout: 0.3)，網路慢就先用上次的設定" }
            return nil
        }
    )
}
