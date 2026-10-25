// L03：自己裝了一個 crash 處理，Crashlytics 就收不到 crash 了（uncaught
// exception handler 要串起來）
// 對應複習站：Advanced06Crash 第 2 題「iOS 和 Android 常見的 crash 有哪幾種？」
// （沒被接住的 Objective-C 例外）
//
// 情境：App 啟動時先初始化 Crashlytics，它用 NSSetUncaughtExceptionHandler
// 登記自己。後來有人想「crash 前先補寫一筆本機紀錄」，
//       在 Crashlytics 之後又呼叫了一次 NSSetUncaughtExceptionHandler。
//       演練用「假的 Crashlytics」，並直接呼叫目前登記的 handler
// 來模擬系統在例外沒被接住時做的事（不會真的當掉）。
//
// 你要寫：
//   ① Bug 版：用 NSSetUncaughtExceptionHandler 登記自己的 handler，
// 只寫自己的紀錄。系統只會呼叫最後登記的那一個，Crashlytics 收不到。
//   ② 修正版：登記前先用 NSGetUncaughtExceptionHandler() 把前一個存起來，
// 自己的紀錄寫完後，再呼叫前一個 handler。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced06-Crash/L03_HandlerChain.swift
import Foundation
import LabKit

/// 兩個 handler 要記錄的東西。NSSetUncaughtExceptionHandler 要的是 C 函式，
/// 不能捕獲外面的變數，所以放在 static（兩個版本共用）
private enum L03State {
    static var crashlyticsReceived: [String] = []
    static var ownReceived: [String] = []
    static var previous: NSUncaughtExceptionHandler?
}

/// 假的 Crashlytics：App 啟動時登記自己的 handler（兩個版本共用）
private func installFakeCrashlytics() {
    NSSetUncaughtExceptionHandler { exception in
        L03State.crashlyticsReceived.append(exception.name.rawValue)
    }
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, installOwnHandler: () -> Void) {
    let original = NSGetUncaughtExceptionHandler()
    // 演練結束還原，不影響 App 本身
    defer { NSSetUncaughtExceptionHandler(original) }
    L03State.crashlyticsReceived = []
    L03State.ownReceived = []
    L03State.previous = nil

    // App 啟動：Crashlytics 先登記
    installFakeCrashlytics()
    // 後來有人又登記了一個
    installOwnHandler()

    let exception = NSException(
        name: .invalidArgumentException,
        reason: "-[Order submit]: unrecognized selector", userInfo: nil)
    log("沒被接住的例外：\(exception.name.rawValue)")
    // 系統在 crash 前，會呼叫「目前登記的」handler
    NSGetUncaughtExceptionHandler()?(exception)
    log("自己的紀錄收到 \(L03State.ownReceived.count) 筆")
    log("Crashlytics 收到 \(L03State.crashlyticsReceived.count) 筆")
}

// MARK: - Bug 版
private enum L03Bug {
    static func installOwnHandler() {
        // TODO ①：用 NSSetUncaughtExceptionHandler { exception in … }
        // 登記自己的 handler，
        //         裡面只做一件事：L03State.ownReceived.append(exception.name.
        // rawValue)
    }

    static func run(_ log: LabLog) async {
        scenario(log, installOwnHandler: installOwnHandler)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func installOwnHandler() {
        // TODO ②-1：登記之前，先用 NSGetUncaughtExceptionHandler() 取得前一個
        // handler，存進 L03State.previous
        // TODO ②-2：再登記自己的 handler：先寫自己的紀錄（和 Bug 版一樣），
        // 最後呼叫 L03State.previous?(exception)
    }

    static func run(_ log: LabLog) async {
        scenario(log, installOwnHandler: installOwnHandler)
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l03 = Lab(
        id: "L03",
        title: "自己裝了 crash 處理，Crashlytics 收不到了",
        question: "第 2 題：iOS 和 Android 常見的 crash 有哪幾種？（沒被接住的例外要怎麼回報）",
        goal:
            "新增自己的 crash 處理時，不能把已經登記的 Crashlytics 擠掉：兩邊都要收到這次 crash。 先寫出會擠掉 Crashlytics 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced06-Crash/L03_HandlerChain.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看「Crashlytics 收到幾筆」。",
            "想一想：NSSetUncaughtExceptionHandler 只能登記一個 handler，後登記的會怎樣？前一個去哪了？",
            "寫 TODO ②，選「修正版」執行：登記前先用 NSGetUncaughtExceptionHandler() 存起來，自己做完再呼叫它。",
            "進階：signal(SIGABRT, …) 也有同樣的問題，signal() 的回傳值就是前一個 handler。Android 對應的是 Thread.setDefaultUncaughtExceptionHandler。",
        ],
        expectBug: "「自己的紀錄收到 1 筆」，但「Crashlytics 收到 0 筆」：真正的 crash 報告不見了。",
        expectFixed: "「自己的紀錄收到 1 筆」，「Crashlytics 收到 1 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("自己的紀錄收到 1 筆") {
                return
                    "自己的 handler 沒有被呼叫：installOwnHandler 要用 NSSetUncaughtExceptionHandler 登記，並記錄 exception"
            }
            if !lines.contains("Crashlytics 收到 0 筆") {
                return "Bug 還沒重現：直接登記新的 handler，不存前一個，Crashlytics 就收不到"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("自己的紀錄收到 1 筆") {
                return
                    "自己的 handler 沒有被呼叫：installOwnHandler 要登記新的 handler，並記錄 exception"
            }
            if !lines.contains("Crashlytics 收到 1 筆") {
                return
                    "Crashlytics 沒有收到：登記前先用 NSGetUncaughtExceptionHandler() 存起來，handler 裡最後呼叫它"
            }
            return nil
        }
    )
}
