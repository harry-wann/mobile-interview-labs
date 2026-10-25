// L01：單元測試根本沒跑，PR 卻可以合併（把「被略過」「被取消」當成通過）
// 對應複習站：Testing06CI 第 1 題「CI 要分成哪幾段？每段跑什麼？」
//
// 情境：PR 的 CI 有幾個檢查（lint、建置、單元測試、截圖比對，
// 另外有一個非必要的夜間檢查）。
//       canMerge(checks) 決定「這個 PR 能不能合併」。每個檢查的結果有四種：
// 成功、失敗、被取消（例如逾時）、被略過（例如條件寫錯）。
//       演練用五種情況各判斷一次。
// Bug 版：只要「沒有必要檢查失敗」就允許合併。單元測試逾時被取消、
// 或因為條件寫錯被略過時，都不是「失敗」，PR 照樣可以合併：
//        單元測試根本沒有跑完，卻被當成通過。
// 修正版：必要的檢查都必須是「成功」才允許合併；被取消、被略過都不行。
// 非必要的檢查失敗不影響。
import Foundation
import LabKit

private func check(
    _ name: String, _ result: T06JobResult, required: Bool = true
) -> T06Check {
    T06Check(name: name, result: result, required: required)
}

/// 演練流程（兩個版本共用）：五種情況各判斷一次
private func scenario(_ log: LabLog, canMerge: ([T06Check]) -> Bool) {
    let cases: [(String, [T06Check])] = [
        (
            "① 全部成功",
            [
                check("lint", .success), check("build", .success),
                check("unit-tests", .success), check("snapshot", .success),
            ]
        ),
        (
            "② 單元測試失敗",
            [
                check("lint", .success), check("build", .success),
                check("unit-tests", .failure), check("snapshot", .success),
            ]
        ),
        (
            "③ 單元測試逾時被取消",
            [
                check("lint", .success), check("build", .success),
                check("unit-tests", .cancelled), check("snapshot", .success),
            ]
        ),
        (
            "④ 單元測試被略過（條件寫錯）",
            [
                check("lint", .success), check("build", .success),
                check("unit-tests", .skipped), check("snapshot", .success),
            ]
        ),
        (
            "⑤ 非必要的夜間檢查失敗",
            [
                check("lint", .success), check("build", .success),
                check("unit-tests", .success),
                check("nightly", .failure, required: false),
            ]
        ),
    ]
    for (title, checks) in cases {
        log("\(title)：\(canMerge(checks) ? "允許合併" : "擋下")")
    }
}

// MARK: - Bug 版
private enum L01Bug {
    static func canMerge(_ checks: [T06Check]) -> Bool {
        // 只要沒有必要檢查「失敗」就允許
        !checks.contains { $0.required && $0.result == .failure }
    }

    static func run(_ log: LabLog) async {
        scenario(log, canMerge: canMerge)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func canMerge(_ checks: [T06Check]) -> Bool {
        // 必要的檢查都要「成功」
        checks.filter(\.required).allSatisfy { $0.result == .success }
    }

    static func run(_ log: LabLog) async {
        scenario(log, canMerge: canMerge)
    }
}

// MARK: - 演練設定
extension Testing06CI {
    static let l01 = Lab(
        id: "L01",
        title: "單元測試根本沒跑，PR 卻可以合併",
        question: "第 1 題：CI 要分成哪幾段？每段跑什麼？",
        goal: "合併 PR 的條件要寫成「必要的檢查都成功」，而不是「沒有失敗」：被取消、被略過的檢查沒有驗證任何東西。",
        steps: [
            "選「Bug 版」執行，看五種情況各自允許合併還是擋下。③ ④ 的單元測試有沒有真的跑完？",
            "想一想：GitHub 的 required check 被略過（skipped）時，會被當成通過。要怎麼避免？（提示：再加一個 job，檢查前面的 job 結果都是 success）",
            "選「修正版」執行：只有 ① ⑤ 允許合併。",
        ],
        expectBug: "① 允許合併；② 擋下；③ 允許合併、④ 允許合併（單元測試沒有跑完或根本沒跑）；⑤ 允許合併。",
        expectFixed: "① 允許合併；② ③ ④ 擋下；⑤ 允許合併（非必要的檢查失敗不影響）。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("① 全部成功：允許合併") else { return "全部成功應該允許合併" }
            guard lines.contains("② 單元測試失敗：擋下") else { return "必要的檢查失敗應該擋下" }
            return lines.contains("③ 單元測試逾時被取消：允許合併")
                && lines.contains("④ 單元測試被略過（條件寫錯）：允許合併")
                ? nil : "Bug 還沒重現：只擋下 result 是 .failure 的必要檢查，被取消、被略過的都放行"
        },
        checkFixed: { lines in
            guard lines.contains("① 全部成功：允許合併") else { return "全部成功應該允許合併" }
            guard lines.contains("⑤ 非必要的夜間檢查失敗：允許合併") else {
                return "非必要的檢查（required 是 false）失敗，不該擋住合併"
            }
            guard lines.contains("② 單元測試失敗：擋下") else { return "必要的檢查失敗應該擋下" }
            return lines.contains("③ 單元測試逾時被取消：擋下")
                && lines.contains("④ 單元測試被略過（條件寫錯）：擋下")
                ? nil : "必要的檢查必須是 .success：被取消、被略過也要擋下"
        }
    )
}
