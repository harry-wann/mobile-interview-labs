// L02：背景更新只執行了一次，之後再也沒有（BGAppRefreshTask 要先排下一次、
// 做完要回報）
// 對應複習站：Platform03BackgroundPush 第 2 題「iOS
// 有哪些背景執行的機制？各有什麼限制？」
//
// 情境：App 用 BGAppRefreshTask 在背景同步資產。真的 BGTaskScheduler
// 在模擬器上不能用（submit 會回報不支援），
//        BGAppRefreshTask 也不能自己建立，所以演練用一個假的系統代替：
// FakeScheduler 記下 App 排的下一次（submit），
//        之後「系統覺得適合的時候」啟動 App、呼叫你登記的 handler；handler
// 必須在時間內呼叫 setTaskCompleted，
//        時間到了系統會先呼叫 expirationHandler，
// 還不結束就懲罰（之後給的背景時間會減少）。
//        演練模擬連續三次：前兩次正常，第三次同步特別慢（超過系統給的時間）。
//        （要在真機驗證：在 Xcode 的 Debug console 執行 e -l objc -- (void)
// [[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"tw.
// harry.MobileLabs.refresh"]）
// Bug 版：handler 做完同步就結束，沒有先排下一次，也沒有呼叫 setTaskCompleted。
// 結果只執行一次，而且系統認為它沒做完。
// 修正版：handler 一開始先排下一次；同步放在 Task 裡，做完呼叫
// setTaskCompleted(success: true)；
//        另外設定 expirationHandler：時間到了取消同步，並呼叫
// setTaskCompleted(success: false)。
import Foundation
import LabKit

/// 假的 BGAppRefreshTask（兩個版本共用）
private final class FakeRefreshTask {
    var expirationHandler: (() -> Void)?
    private(set) var completed: Bool?

    func setTaskCompleted(success: Bool) {
        if completed == nil { completed = success }
    }
}

/// 假的系統（兩個版本共用）：記下 App 排的下一次；啟動 App 時呼叫 handler，
/// 等一段時間，沒完成就叫 expirationHandler
@MainActor
private final class FakeScheduler {
    // 一開始 App 啟動時已經排過第一次
    var hasPendingRequest = true
    private(set) var launches = 0
    // 時間到了還是沒有呼叫 setTaskCompleted 的次數
    private(set) var neverCompleted = 0
    private(set) var reportedFailure = 0

    func submit() { hasPendingRequest = true }

    /// 系統覺得適合的時候：啟動 App 做一次背景更新（只有 App 排過才會啟動）
    func launchIfScheduled(handler: (FakeRefreshTask) -> Void) async {
        guard hasPendingRequest else { return }
        // 這一次的排程用掉了
        hasPendingRequest = false
        launches += 1
        let task = FakeRefreshTask()
        handler(task)
        // 系統給的時間（真的約 30 秒）
        try? await Task.sleep(for: .milliseconds(150))
        if task.completed == nil {
            // 時間到：系統先呼叫 expirationHandler
            task.expirationHandler?()
            try? await Task.sleep(for: .milliseconds(30))
        }
        if task.completed == nil { neverCompleted += 1 }
        if task.completed == false { reportedFailure += 1 }
    }
}

/// 演練流程（兩個版本共用）：連續三次，前兩次同步很快，
/// 第三次特別慢（超過系統給的時間）
@MainActor
private func scenario(
    _ log: LabLog,
    handle:
        @escaping (
            FakeRefreshTask, FakeScheduler,
            _ syncPortfolio: @escaping () async -> Void
        ) -> Void
) async {
    let scheduler = FakeScheduler()
    var syncs = 0
    for attempt in 1...3 {
        let slow = attempt == 3
        await scheduler.launchIfScheduled { task in
            handle(task, scheduler) {
                syncs += 1
                // 同步資產；第三次很慢，但可以被取消
                try? await Task.sleep(for: .milliseconds(slow ? 5_000 : 20))
            }
        }
    }
    log("連續三次背景更新的機會，系統實際啟動了 \(scheduler.launches) 次")
    log("同步資產被執行了 \(syncs) 次")
    log("時間到了還沒有呼叫 setTaskCompleted 的有 \(scheduler.neverCompleted) 次")
    log("時間到了，主動回報失敗（success: false）的有 \(scheduler.reportedFailure) 次")
}

// MARK: - Bug 版
private enum L02Bug {
    @MainActor static func handle(
        _ task: FakeRefreshTask, _ scheduler: FakeScheduler,
        _ syncPortfolio: @escaping () async -> Void
    ) {
        // 只做同步：沒有排下一次，也沒有告訴系統做完了
        Task { await syncPortfolio() }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log, handle: handle)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    @MainActor static func handle(
        _ task: FakeRefreshTask, _ scheduler: FakeScheduler,
        _ syncPortfolio: @escaping () async -> Void
    ) {
        // 先排下一次，不然只會執行這一次
        scheduler.submit()
        let work = Task {
            await syncPortfolio()
            // 做完要告訴系統
            if !Task.isCancelled { task.setTaskCompleted(success: true) }
        }
        // 時間到了系統會呼叫，要立刻收尾
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log, handle: handle)
    }
}

// MARK: - 演練設定
private func number(_ lines: [String], prefix: String, suffix: String) -> Int? {
    lines.first { $0.hasPrefix(prefix) }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: prefix, with: "")
                    .replacingOccurrences(of: suffix, with: ""))
        }
}

extension Platform03BackgroundPush {
    static let l02 = Lab(
        id: "L02",
        title: "背景更新只執行了一次，之後再也沒有",
        question: "第 2 題：iOS 有哪些背景執行的機制？各有什麼限制？",
        goal:
            "BGAppRefreshTask 的 handler 要先排下一次、做完呼叫 setTaskCompleted、設好 expirationHandler。三次機會都要被系統啟動，而且每一次時間到之前都要回報。",
        steps: [
            "選「Bug 版」按執行，看「系統實際啟動了幾次」和「時間到了還沒有呼叫 setTaskCompleted 的有幾次」。",
            "選「修正版」執行：三次都啟動；第三次很慢，expirationHandler 取消同步並回報失敗。",
            "想一想：earliestBeginDate 為什麼只是「最早」？（由系統依使用習慣和電量決定，不保證準時，所以不能拿它做價格提醒）",
        ],
        expectBug: "系統只啟動了 1 次（沒有排下一次）；時間到了還沒有呼叫 setTaskCompleted 的有 1 次。",
        expectFixed: "系統啟動了 3 次；沒有「忘了回報」的情況；第三次同步太慢，時間到時主動回報失敗 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                let launches = number(
                    lines, prefix: "連續三次背景更新的機會，系統實際啟動了 ", suffix: " 次"),
                let never = number(
                    lines, prefix: "時間到了還沒有呼叫 setTaskCompleted 的有 ",
                    suffix: " 次")
            else { return "演練沒有跑完" }
            if number(lines, prefix: "同步資產被執行了 ", suffix: " 次") != 1 {
                return "同步沒有執行：handler 要開一個 Task，在裡面 await syncPortfolio()"
            }
            if launches != 1 {
                return
                    "Bug 還沒重現：Bug 版的 handler 不要呼叫 scheduler.submit()，只會被啟動 1 次"
            }
            if never == 0 { return "Bug 還沒重現：Bug 版不要呼叫 task.setTaskCompleted" }
            return nil
        },
        checkFixed: { lines in
            guard
                let launches = number(
                    lines, prefix: "連續三次背景更新的機會，系統實際啟動了 ", suffix: " 次"),
                let never = number(
                    lines, prefix: "時間到了還沒有呼叫 setTaskCompleted 的有 ",
                    suffix: " 次"),
                let failed = number(
                    lines, prefix: "時間到了，主動回報失敗（success: false）的有 ",
                    suffix: " 次")
            else { return "演練沒有跑完" }
            if launches == 1
                && number(lines, prefix: "同步資產被執行了 ", suffix: " 次") == 0
            {
                return "同步沒有執行：handler 要開一個 Task，在裡面 await syncPortfolio()"
            }
            if launches != 3 {
                return
                    "系統只啟動了 \(launches) 次：handler 一開始要先 scheduler.submit() 排下一次"
            }
            if never != 0 {
                return
                    "有 \(never) 次沒有回報：同步做完要呼叫 setTaskCompleted(success: true)；時間到了（expirationHandler）要取消同步並呼叫 setTaskCompleted(success: false)"
            }
            if failed != 1 {
                return
                    "第三次同步太慢，時間到時要在 expirationHandler 裡取消同步，並呼叫 setTaskCompleted(success: false)"
            }
            return nil
        }
    )
}
