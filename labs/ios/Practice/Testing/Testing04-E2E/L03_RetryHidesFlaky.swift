// L03：CI 說只有 1 個測試失敗，其實有 2 個第一次就失敗（自動重試把 flaky
// 藏起來）
// 對應複習站：Testing04E2E 第 4 題「時好時壞（flaky）的測試要怎麼管理？」
//
// 情境：CI 的 E2E 測試每個最多試 2 次（第一次失敗就自動重試一次）。4 個測試：
//       「登入加兩步驟驗證」「日圓入金說明畫面」穩定通過；「限價買單到撤單」
// 第一次失敗（等畫面等不夠久）、重試才通過（flaky）；
//       「出庫新增地址」每次都失敗（真的 bug）。runAll 負責跑完所有測試，
// 回傳報告 T04Report。
//
// 你要寫：
//   ① Bug 版：runAll 對每個測試最多試 maxAttempts 次（test.run(attempt)，
// attempt 從 1 開始），有一次通過就算通過（放 report.passed），
//      都沒通過放 report.failed；不記錄試了幾次。
//   ② 修正版：記下是第幾次才通過。第 1 次就通過放 report.passed，
// 重試後才通過放 report.flakyPassed，都沒通過放 report.failed。
// 只改有 TODO 的地方。解答：Solution/Testing/Testing04-E2E/L03_RetryHidesFlaky.
// swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：用 runAll 跑 4 個測試（每個最多試 2 次），
/// 再把報告印出來
private func scenario(
    _ log: LabLog, runAll: ([T04TestCase], Int) async -> T04Report
) async {
    let report = await runAll(T04TestCase.suite, 2)
    log(
        "CI 報告：通過 \(report.passed.count)、重試後才通過 \(report.flakyPassed.count)、失敗 \(report.failed.count)"
    )
    if !report.flakyPassed.isEmpty {
        log("flaky 清單：" + report.flakyPassed.joined(separator: "、"))
    }
    if !report.failed.isEmpty {
        log("失敗清單：" + report.failed.joined(separator: "、"))
    }
    log("擋不擋 PR：\(report.failed.isEmpty ? "不擋" : "擋下")")
    // 真相：第一次就失敗的測試（報告裡看不到）
    var firstTryFailed: [String] = []
    for test in T04TestCase.suite where !(await test.run(1)) {
        firstTryFailed.append(test.name)
    }
    log(
        "實際上第一次就失敗的測試有 \(firstTryFailed.count) 個："
            + firstTryFailed.joined(separator: "、"))
}

// MARK: - Bug 版
private enum L03Bug {
    static func runAll(
        _ tests: [T04TestCase], _ maxAttempts: Int
    ) async -> T04Report {
        // TODO ①：對每個測試最多試 maxAttempts 次（test.run(attempt)），
        // 有一次通過就放進 report.passed，都沒通過放 report.failed；
        // 不要記錄試了幾次
        _ = (tests, maxAttempts)
        return T04Report()
    }

    static func run(_ log: LabLog) async {
        await scenario(log, runAll: runAll)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func runAll(
        _ tests: [T04TestCase], _ maxAttempts: Int
    ) async -> T04Report {
        // TODO ②：和 Bug 版一樣最多試 maxAttempts 次，但要記下是第幾次才通過：
        //        第 1 次就通過放 report.passed，重試後才通過放 report.
        // flakyPassed，都沒通過放 report.failed
        _ = (tests, maxAttempts)
        return T04Report()
    }

    static func run(_ log: LabLog) async {
        await scenario(log, runAll: runAll)
    }
}

// MARK: - 演練設定
extension Testing04E2E {
    static let l03 = Lab(
        id: "L03",
        title: "CI 只說 1 個失敗，其實 2 個第一次就失敗",
        question: "第 4 題：時好時壞（flaky）的測試要怎麼管理？",
        goal: "自動重試可以讓 PR 先過，但一定要記錄「重試後才通過」的測試，否則 flaky 被藏起來了。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，比較「CI 報告」和最後一行「實際上第一次就失敗的測試」。",
            "想一想：報告上看不到的那一個，下次它會在什麼時候才被發現？",
            "寫 TODO ②，選「修正版」執行：報告要列出 flaky 清單，失敗的測試仍然要擋下 PR。",
        ],
        expectBug:
            "CI 報告：通過 3、重試後才通過 0、失敗 1；但實際上第一次就失敗的測試有 2 個（限價買單到撤單、出庫新增地址）。",
        expectFixed: "CI 報告：通過 2、重試後才通過 1、失敗 1；flaky 清單：限價買單到撤單；失敗的測試仍然擋下 PR。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("CI 報告：通過 3、重試後才通過 0、失敗 1") else {
                return
                    "Bug 還沒重現：每個測試最多試 maxAttempts 次，有一次通過就算 passed，不記錄試了幾次（預期「通過 3、重試後才通過 0、失敗 1」）"
            }
            return lines.contains("擋不擋 PR：擋下")
                ? nil : "真的失敗的測試（出庫新增地址）要放進 report.failed，PR 要擋下"
        },
        checkFixed: { lines in
            guard lines.contains("CI 報告：通過 2、重試後才通過 1、失敗 1") else {
                return
                    "報告要分三類：第 1 次就通過放 passed、重試後才通過放 flakyPassed、都沒通過放 failed（預期「通過 2、重試後才通過 1、失敗 1」）"
            }
            return lines.contains("flaky 清單：限價買單到撤單")
                ? nil : "flakyPassed 裡要有「限價買單到撤單」"
        }
    )
}
