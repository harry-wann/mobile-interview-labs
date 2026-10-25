// L04：按下「更新餘額」，App 直接當掉（在主執行緒呼叫 DispatchQueue.main.sync）
// 對應複習站：Platform02Threading 第 4 題「iOS 的 GCD 有哪些常見的坑？」
//
// 情境：有一個共用函式 updateBalance(_:)，為了「保證在主執行緒更新畫面」，大家都用它。有時從背景執行緒呼叫（收到推播），有時從主執行緒呼叫（按下按鈕）。
// Bug 版：函式裡用 DispatchQueue.main.sync 切到主執行緒。從背景呼叫沒問題；從主執行緒呼叫，等於「主執行緒等自己做完」，
//        GCD 偵測到之後直接讓 App 當掉：BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread。
// 修正版：先判斷目前是不是在主執行緒：是就直接做；不是才用 DispatchQueue.main.async 排進主執行緒（async 不會等）。
import Foundation
import LabKit

/// 假的餘額文字（兩個版本共用，代替 UILabel）
private final class BalanceLabel: @unchecked Sendable {
    var text = ""
}

/// 演練流程（兩個版本共用）：先從背景執行緒更新一次，再從主執行緒（按下按鈕）更新一次
@MainActor
private func scenario(_ log: LabLog, update: @escaping @Sendable (BalanceLabel, String) -> Void) async {
    let label = BalanceLabel()
    await withCheckedContinuation { continuation in
        DispatchQueue.global().async {                  // 收到推播：在背景執行緒
            update(label, "BTC 0.5")
            continuation.resume()
        }
    }
    try? await Task.sleep(for: .milliseconds(50))
    log("從背景執行緒更新之後，餘額：\(label.text)")
    log("接著從主執行緒（按下按鈕）更新……")
    update(label, "BTC 0.8")                            // 按下按鈕：在主執行緒
    log("從主執行緒更新之後，餘額：\(label.text)")
}

// MARK: - Bug 版
private enum L04Bug {
    /// 保證在主執行緒更新餘額
    static func updateBalance(_ label: BalanceLabel, _ text: String) {
        DispatchQueue.main.sync {                       // 從主執行緒呼叫時：主執行緒等自己，當掉
            label.text = text
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { updateBalance($0, $1) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func updateBalance(_ label: BalanceLabel, _ text: String) {
        if Thread.isMainThread {
            label.text = text                           // 已經在主執行緒：直接做
        } else {
            DispatchQueue.main.async { label.text = text }      // 不在主執行緒：排進佇列，不等結果
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { updateBalance($0, $1) }
    }
}

// MARK: - 演練設定
extension Platform02Threading {
    static let l04 = Lab(
        id: "L04",
        title: "按下更新餘額，App 直接當掉",
        question: "第 4 題：iOS 的 GCD 有哪些常見的坑？",
        goal: "共用的更新函式要能從任何執行緒呼叫。不能在主執行緒對主佇列呼叫 sync。",
        steps: [
            "寫好 Bug 版，從 Xcode 執行（⌘R）才看得到當掉的位置。選「Bug 版」執行：App 當掉，主控台印出 BUG IN CLIENT OF LIBDISPATCH。",
            "在 Debug navigator 看呼叫堆疊：updateBalance 的 DispatchQueue.main.sync 那一行。",
            "選「修正版」執行：兩個呼叫都成功。",
            "想一想：為什麼從背景執行緒呼叫 main.sync 沒事？（主執行緒是空的，可以處理排進去的工作）",
        ],
        expectBug: "log 印到「接著從主執行緒（按下按鈕）更新……」之後 App 當掉；主控台：BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread。",
        expectFixed: "背景更新後餘額 BTC 0.5，主執行緒更新後餘額 BTC 0.8。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { _ in
            // 寫對的話 App 會當掉，不會走到這裡
            "App 沒有當掉：updateBalance 要用 DispatchQueue.main.sync，而且從主執行緒呼叫它"
        },
        checkFixed: { lines in
            if !lines.contains("從背景執行緒更新之後，餘額：BTC 0.5") { return "從背景執行緒呼叫之後，餘額應該更新成 BTC 0.5：不在主執行緒時用 DispatchQueue.main.async 排進佇列" }
            if !lines.contains("從主執行緒更新之後，餘額：BTC 0.8") { return "從主執行緒呼叫之後，餘額應該立刻是 BTC 0.8：已經在主執行緒（Thread.isMainThread）就直接做" }
            return nil
        }
    )
}
