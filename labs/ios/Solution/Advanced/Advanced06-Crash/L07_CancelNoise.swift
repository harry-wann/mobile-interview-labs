// L07：Crashlytics 被「已取消」洗版，真正的錯誤反而看不到（取消不是錯誤）
// 對應複習站：Advanced06Crash 第 5 題「怎麼把 crash-free users 從約 90% 提升到 99%，而且長期維持？」（步驟六：降低雜訊）
//
// 情境：使用者快速在畫面之間切換。每個畫面離開時，會取消還在載入的工作：有的是 Swift Concurrency 的 CancellationError，
//       有的是 URLSession 被取消時丟出的 URLError(.cancelled)。另外有 1 個畫面載入時，真的遇到資料解碼失敗。
//       團隊在每個 catch 裡把錯誤用 non-fatal 回報（Crashlytics 的 record(error:)）。
// Bug 版：catch 到什麼就回報什麼。5 筆「已取消」跟 1 筆真正的錯誤混在一起，問題列表被取消洗版。
// 修正版：取消是正常的流程控制，不是錯誤：先把 CancellationError 與 URLError(.cancelled) 挑出來，什麼都不做；其餘才回報。
import Foundation
import LabKit

/// 假的 Crashlytics：記下被回報的 non-fatal 錯誤（兩個版本共用）
private final class Reporter: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String] = []
    func record(_ error: Error) { lock.withLock { storage.append(String(describing: error)) } }
    var reports: [String] { lock.withLock { storage } }
}

private struct DecodeFailure: Error, CustomStringConvertible {
    var description: String { "資料解碼失敗" }
}

/// 載入一個畫面的資料：5 秒才會有結果，被取消時丟出取消錯誤（兩個版本共用）
private func loadScreen(kind: Int) async throws {
    if kind == 99 { throw DecodeFailure() }              // 這一個畫面真的遇到壞資料
    do {
        try await Task.sleep(for: .seconds(5))
    } catch {
        // kind 偶數：直接丟 CancellationError；奇數：像 URLSession 那樣丟 URLError(.cancelled)
        if kind % 2 == 0 { throw error } else { throw URLError(.cancelled) }
    }
}

/// 演練流程（兩個版本共用）：開 6 個畫面的載入工作，使用者很快離開（取消其中 5 個）
private func scenario(_ log: LabLog, handle: @escaping @Sendable (Error, Reporter) -> Void) async {
    let reporter = Reporter()
    var tasks: [Task<Void, Never>] = []
    for kind in [1, 2, 3, 4, 5, 99] {
        tasks.append(Task {
            do { try await loadScreen(kind: kind) } catch { handle(error, reporter) }
        })
    }
    try? await Task.sleep(for: .milliseconds(100))
    for task in tasks { task.cancel() }                  // 使用者離開畫面：取消還在載入的工作
    for task in tasks { await task.value }
    log("Crashlytics 收到 \(reporter.reports.count) 筆 non-fatal")
    for report in reporter.reports { log("　• \(report)") }
}

// MARK: - Bug 版
private enum L07Bug {
    static func run(_ log: LabLog) async {
        await scenario(log) { error, reporter in
            reporter.record(error)                       // 什麼錯誤都回報，包括取消
        }
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func run(_ log: LabLog) async {
        await scenario(log) { error, reporter in
            if error is CancellationError { return }     // 取消是正常的流程，不回報
            if let urlError = error as? URLError, urlError.code == .cancelled { return }
            reporter.record(error)                       // 真正的錯誤才回報
        }
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l07 = Lab(
        id: "L07",
        title: "Crashlytics 被「已取消」洗版",
        question: "第 5 題：怎麼把 crash-free users 從約 90% 提升到 99%，而且長期維持？（降低雜訊）",
        goal: "只回報真正需要處理的錯誤：使用者離開畫面造成的取消，不該出現在 non-fatal 列表裡。",
        steps: [
            "選「Bug 版」執行，看 Crashlytics 收到幾筆，以及是哪些錯誤。",
            "想一想：URLSession 的請求被取消時，會丟出什麼錯誤？Swift Concurrency 的 Task 被取消呢？這兩種算錯誤嗎？",
            "選「修正版」執行：取消的不回報，只剩下 1 筆「資料解碼失敗」。",
            "進階：Android 對應的是 Kotlin 的 CancellationException（Crashlytics 上看到的「Job was cancelled」）。",
        ],
        expectBug: "「Crashlytics 收到 6 筆 non-fatal」，其中 5 筆是 CancellationError 或 URLError(.cancelled)。",
        expectFixed: "「Crashlytics 收到 1 筆 non-fatal」，只有「資料解碼失敗」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("Crashlytics 收到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("Crashlytics 收到 6 筆 non-fatal") { return "Bug 還沒重現：catch 到的錯誤都回報（reporter.record），應該有 6 筆" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("Crashlytics 收到") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("Crashlytics 收到 1 筆 non-fatal") {
                return "應該只回報 1 筆：CancellationError 與 URLError(.cancelled) 要先挑出來，不回報"
            }
            if !lines.contains("　• 資料解碼失敗") { return "真正的錯誤（資料解碼失敗）不能被濾掉" }
            return nil
        }
    )
}
