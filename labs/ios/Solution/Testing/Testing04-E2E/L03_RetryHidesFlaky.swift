// L03：CI 說只有 1 個測試失敗，其實有 2 個第一次就失敗（自動重試把 flaky
// 藏起來）
// 對應複習站：Testing04E2E 第 4 題「時好時壞（flaky）的測試要怎麼管理？」
//
// 情境：CI 的 E2E 測試每個最多試 2 次（第一次失敗就自動重試一次）。4 個測試：
//       「登入加兩步驟驗證」「日圓入金說明畫面」穩定通過；「限價買單到撤單」
// 第一次失敗（等畫面等不夠久）、重試才通過（flaky）；
//       「出庫新增地址」每次都失敗（真的 bug）。runAll 負責跑完所有測試，
// 回傳報告 T04Report。
// Bug 版：只要有一次通過就算通過，不記錄它試了幾次。報告：通過 3、重試後才通過
// 0、失敗 1。
//        flaky 的「限價買單到撤單」被當成穩定通過，沒有人知道它有問題。
// 修正版：記錄每個測試試了幾次，重試後才通過的放進 flakyPassed。報告：通過 2、
// 重試後才通過 1、失敗 1，
//        並列出 flaky 清單「限價買單到撤單」，團隊才看得到、
// 才能限期修好或隔離。
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
        var report = T04Report()
        for test in tests {
            var ok = false
            for attempt in 1...maxAttempts where await test.run(attempt) {
                // 有一次通過就算通過，不記錄試了幾次
                ok = true
                break
            }
            if ok {
                report.passed.append(test.name)
            } else {
                report.failed.append(test.name)
            }
        }
        return report
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
        var report = T04Report()
        for test in tests {
            var passedAt: Int?
            for attempt in 1...maxAttempts where await test.run(attempt) {
                // 記下是第幾次才通過
                passedAt = attempt
                break
            }
            switch passedAt {
            case 1?: report.passed.append(test.name)
            // 重試後才通過：記錄起來
            case .some: report.flakyPassed.append(test.name)
            case nil: report.failed.append(test.name)
            }
        }
        return report
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
            "選「Bug 版」執行，比較「CI 報告」和最後一行「實際上第一次就失敗的測試」。",
            "想一想：報告上看不到的那一個，下次它會在什麼時候才被發現？",
            "選「修正版」執行：報告要列出 flaky 清單，失敗的測試仍然要擋下 PR。",
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
