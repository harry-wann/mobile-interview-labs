// L03：CI 看板的「flaky 比例」是 50%，其實只有 25%（把真的 bug 也算成 flaky）
// 對應複習站：Testing05AITesting 第 4 題「整體架構長什麼樣子？」（指標：flaky 的比例）
//
// 情境：CI 歷史有 3 個 commit、4 個測試的執行紀錄（T05History.runs）：
//       testFee、testChart 一直通過；testLogin 在 c2 壞掉（兩次重試都失敗，是真的 bug），c3 修好；
//       testOrderUI 在 c1 和 c3 的同一個 commit 上，有時失敗有時通過（flaky）。
//       flakyTests(runs) 回傳「flaky 的測試名稱」，看板用它算 flaky 比例 = flaky 測試數 ÷ 全部測試數。
// Bug 版：只要失敗過就算 flaky。testLogin（真的 bug）和 testOrderUI 都被算進去：2／4 = 50%。
// 修正版：flaky 的定義是「同一個 commit 上，同一個測試既有通過也有失敗」。只有 testOrderUI：1／4 = 25%。
//        真的 bug（在同一個 commit 上每次都失敗）不是 flaky，要另外追蹤。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：算出 flaky 的測試，再印出 flaky 比例
private func scenario(_ log: LabLog, flakyTests: ([T05CIRun]) -> Set<String>) {
    let runs = T05History.runs
    let all = Set(runs.map(\.test))
    let flaky = flakyTests(runs)
    log("flaky 的測試：" + (flaky.isEmpty ? "（沒有）" : flaky.sorted().joined(separator: "、")))
    log("flaky 比例：\(flaky.count)／\(all.count) = \(flaky.count * 100 / all.count)%")
}

// MARK: - Bug 版
private enum L03Bug {
    static func flakyTests(_ runs: [T05CIRun]) -> Set<String> {
        Set(runs.filter { !$0.passed }.map(\.test))               // 只要失敗過就算 flaky
    }

    static func run(_ log: LabLog) async {
        scenario(log, flakyTests: flakyTests)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func flakyTests(_ runs: [T05CIRun]) -> Set<String> {
        var results: [String: Set<Bool>] = [:]                    // (commit, 測試) → 這個 commit 上出現過的結果
        for run in runs { results["\(run.commit)/\(run.test)", default: []].insert(run.passed) }
        // 同一個 commit 上，既有通過也有失敗的測試才是 flaky
        let keys = results.filter { $0.value.count == 2 }.keys
        return Set(keys.map { String($0.split(separator: "/")[1]) })
    }

    static func run(_ log: LabLog) async {
        scenario(log, flakyTests: flakyTests)
    }
}

// MARK: - 演練設定
extension Testing05AITesting {
    static let l03 = Lab(
        id: "L03",
        title: "flaky 比例顯示 50%，其實只有 25%",
        question: "第 4 題：整體架構長什麼樣子？（指標：flaky 的比例）",
        goal: "flaky 的定義是「程式沒改，同一個測試有時過有時不過」：同一個 commit 上既有通過也有失敗。真的 bug 不能算進去。",
        steps: [
            "選「Bug 版」執行，看被算成 flaky 的測試有哪些、比例是多少。",
            "對照 T05History.runs：testLogin 在 c2 為什麼不是 flaky？",
            "選「修正版」執行：只剩 testOrderUI，比例 25%。",
        ],
        expectBug: "flaky 的測試：testLogin、testOrderUI；flaky 比例：2／4 = 50%（testLogin 是真的 bug，不該算）。",
        expectFixed: "flaky 的測試：testOrderUI；flaky 比例：1／4 = 25%。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("flaky 比例：2／4 = 50%") ? nil : "Bug 還沒重現：只要有失敗紀錄（passed 是 false）的測試都算 flaky，預期 testLogin 與 testOrderUI（2／4 = 50%）"
        },
        checkFixed: { lines in
            guard lines.contains("flaky 的測試：testOrderUI") else { return "只有 testOrderUI 在同一個 commit 上既有通過也有失敗" }
            return lines.contains("flaky 比例：1／4 = 25%") ? nil : "flaky 比例應該是 1／4 = 25%"
        }
    )
}
