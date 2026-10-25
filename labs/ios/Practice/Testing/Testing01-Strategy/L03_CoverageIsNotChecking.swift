// L03：覆蓋率 100%、測試全過，但程式被改壞測試還是過（沒有檢查邊界）
// 對應複習站：Testing01Strategy 第 4 題「怎麼知道測試真的有用？」
//
// 情境：單筆出金達到 100 萬日圓，就要再做一次兩步驟驗證。T01Withdrawal.requiresTwoFactor 是被測的函式。
//       mutation testing 的做法：故意把程式改壞（這裡手寫一個 requiresTwoFactorMutant：>= 變成 >），再跑一次測試。
//       演練把同一個測試，分別對「正確的實作」和「被改壞的實作」各跑一次。
//
// 你要寫：
//   ① Bug 版：寫兩個離邊界很遠的檢查：requires(5_000_000) 是 true、requires(100) 是 false（用 t.equal）。
//      兩個分支都跑到了，覆蓋率 100%，但被改壞的實作測試也會通過。
//   ② 修正版：再補上邊界的檢查：999_999 是 false、1_000_000 是 true、1_000_001 是 true。
// 只改有 TODO 的地方。解答：Solution/Testing/Testing01-Strategy/L03_CoverageIsNotChecking.swift
import Foundation
import LabKit

/// 把同一個測試分別對「正確的實作」和「被改壞的實作」各跑一次，並記下函式被呼叫了幾次、兩個分支有沒有跑到（已寫好）
private func runMatrix(_ log: LabLog, test: (_ requires: @escaping (Int) -> Bool, _ t: T01Expect) -> Void) {
    let implementations: [(String, (Int) -> Bool)] = [
        ("正確的實作", T01Withdrawal.requiresTwoFactor),
        ("被改壞的實作（>= 改成 >）", T01Withdrawal.requiresTwoFactorMutant),
    ]
    for (name, implementation) in implementations {
        var calls = 0
        var sawTrue = false
        var sawFalse = false
        let spy: (Int) -> Bool = { amount in
            calls += 1
            let result = implementation(amount)
            if result { sawTrue = true } else { sawFalse = true }
            return result
        }
        let t = T01Expect()
        test(spy, t)
        log("\(name)：\(t.failures.isEmpty ? "測試通過" : "測試失敗（\(t.failures[0])）")")
        if name == "正確的實作" {
            log("覆蓋：函式被呼叫 \(calls) 次，true 分支\(sawTrue ? "有" : "沒有")跑到，false 分支\(sawFalse ? "有" : "沒有")跑到")
        }
    }
}

// MARK: - Bug 版
private enum L03Bug {
    static func test(_ requires: (Int) -> Bool, _ t: T01Expect) {
        // TODO ①：用 t.equal(requires(5_000_000), true)、t.equal(requires(100), false) 寫兩個離邊界很遠的檢查
    }

    static func run(_ log: LabLog) async {
        runMatrix(log, test: test)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func test(_ requires: (Int) -> Bool, _ t: T01Expect) {
        // TODO ②-1：先寫和 Bug 版一樣的兩個檢查
        // TODO ②-2：再補邊界：999_999 預期 false、1_000_000 預期 true、1_000_001 預期 true
    }

    static func run(_ log: LabLog) async {
        runMatrix(log, test: test)
    }
}

// MARK: - 演練設定
extension Testing01Strategy {
    static let l03 = Lab(
        id: "L03",
        title: "覆蓋率 100%，程式被改壞測試還是過",
        question: "第 4 題：怎麼知道測試真的有用？",
        goal: "看一個「每一行都跑到、卻抓不到邊界 bug」的測試，再補上邊界的例子，讓被改壞的程式測試會失敗。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行：兩個分支都跑到了（覆蓋率 100%），再看被改壞的實作，測試是通過還是失敗？",
            "想一想：mutation score 是什麼？這個測試對「>= 改成 >」這一種改壞，抓到了幾個？",
            "寫 TODO ②，選「修正版」執行：被改壞的實作要讓測試失敗。",
        ],
        expectBug: "正確的實作：測試通過；覆蓋：函式被呼叫 2 次，兩個分支都跑到；被改壞的實作：測試也通過（沒抓到）。",
        expectFixed: "正確的實作：測試通過；被改壞的實作：測試失敗（預期 true，實際 false），抓到了。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("正確的實作：測試通過") else { return "測試在正確的實作上要通過：requires(5_000_000) 是 true、requires(100) 是 false" }
            guard lines.contains("覆蓋：函式被呼叫 2 次，true 分支有跑到，false 分支有跑到") else { return "測試要呼叫 requires 兩次，讓 true 與 false 兩個分支都跑到（覆蓋率 100%）" }
            return lines.contains("被改壞的實作（>= 改成 >）：測試通過") ? nil : "Bug 還沒重現：被改壞的實作也要讓測試通過（不要驗剛好 1,000,000 的邊界）"
        },
        checkFixed: { lines in
            guard lines.contains("正確的實作：測試通過") else { return "測試在正確的實作上要通過" }
            guard lines.contains(where: { $0.hasPrefix("覆蓋：函式被呼叫 ") && !$0.hasPrefix("覆蓋：函式被呼叫 0 次") }) else { return "測試沒有呼叫 requires：要驗邊界 999_999、1_000_000、1_000_001" }
            return lines.contains(where: { $0.hasPrefix("被改壞的實作（>= 改成 >）：測試失敗") }) ? nil : "被改壞的實作也通過了：補上剛好 1_000_000 的例子，預期 true"
        }
    )
}
