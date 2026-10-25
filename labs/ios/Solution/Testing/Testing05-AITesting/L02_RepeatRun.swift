// L02：AI 產生的測試，合併當天通過，之後卻時好時壞（只跑一次就判斷穩定）
// 對應複習站：Testing05AITesting 第 2 題「怎麼確保 AI 產生的測試是可信的？」（連續跑好幾次，確認不是時好時壞的）
//
// 情境：AI 產生了一個用「亂數金額」的測試：隨機取一個金額，檢查 T05Fee.calculate 的結果等於「金額 × 0.15%，四捨五入」。
//       但規格是「無條件捨去」，所以只要金額乘以費率的小數部分 ≥ 0.5，測試就會失敗，大約四成的亂數會踩到。
//       isStable(runTest) 決定這個測試「穩不穩定，能不能合併」。演練用固定的一串數字代表亂數，每次結果相同。
//       兩個測試：AI 產生的（用亂數金額）、固定金額的（穩定）。
// Bug 版：只跑一次，通過就算穩定。第一個亂數剛好沒踩到，AI 的測試被放行；之後每次 CI 都有機會失敗。
// 修正版：連續跑 20 次（每次取下一個亂數），只要有一次失敗就判定不穩定，擋下來。固定金額的測試 20 次都通過，允許合併。
import Foundation
import LabKit

private let runsForStability = 20

/// 演練流程（兩個版本共用）：審查兩個測試，再印出實際連跑 20 次的結果
private func scenario(_ log: LabLog, isStable: (() -> Bool) -> Bool) {
    let candidates: [(String, (T05Random) -> Bool)] = [
        ("AI 產生的隨機金額測試", { random in
            let amount = random.nextAmount()
            let expected = Int((Double(amount) * 0.0015).rounded())                // AI 用四捨五入算期望值
            return T05Fee.calculate(amountJPY: amount, isMaker: false) == expected
        }),
        ("固定金額的測試", { _ in T05Fee.calculate(amountJPY: 1_000_000, isMaker: false) == 1_500 }),
    ]
    for (name, test) in candidates {
        let random = T05Random()
        log("\(name)：審查結果：\(isStable { test(random) } ? "穩定，允許合併" : "不穩定，擋下")")
        let truthRandom = T05Random()
        let failures = (0..<runsForStability).filter { _ in !test(truthRandom) }.count
        log("\(name)：實際連跑 \(runsForStability) 次，失敗 \(failures) 次")
    }
}

// MARK: - Bug 版
private enum L02Bug {
    static func isStable(_ runTest: () -> Bool) -> Bool {
        runTest()                                                // 只跑一次，通過就算穩定
    }

    static func run(_ log: LabLog) async {
        scenario(log, isStable: isStable)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func isStable(_ runTest: () -> Bool) -> Bool {
        for _ in 0..<runsForStability where !runTest() {
            return false                                         // 連跑 20 次，只要有一次失敗就是不穩定
        }
        return true
    }

    static func run(_ log: LabLog) async {
        scenario(log, isStable: isStable)
    }
}

// MARK: - 演練設定
extension Testing05AITesting {
    static let l02 = Lab(
        id: "L02",
        title: "AI 的測試合併時通過，之後時好時壞",
        question: "第 2 題：怎麼確保 AI 產生的測試是可信的？",
        goal: "AI 產生的測試要連續跑好幾次才能合併；只跑一次，用到亂數的測試可能剛好通過。",
        steps: [
            "選「Bug 版」執行，看兩個測試的審查結果，以及實際連跑 20 次失敗幾次。",
            "想一想：失敗的原因是程式壞了，還是測試的期望值寫錯了？（規格是無條件捨去）",
            "選「修正版」執行：隨機金額的測試要被擋下，固定金額的測試允許合併。",
        ],
        expectBug: "AI 產生的隨機金額測試被判定「穩定，允許合併」，但實際連跑 20 次失敗 8 次；固定金額的測試也允許合併。",
        expectFixed: "隨機金額測試「不穩定，擋下」；固定金額的測試「穩定，允許合併」（連跑 20 次失敗 0 次）。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("AI 產生的隨機金額測試：實際連跑 20 次，失敗 8 次") else { return "找不到實際連跑的結果：要用 scenario 審查" }
            guard lines.contains("AI 產生的隨機金額測試：審查結果：穩定，允許合併") else { return "Bug 還沒重現：只跑一次 runTest()，第一個亂數剛好通過，就應該判定穩定" }
            return lines.contains("固定金額的測試：審查結果：穩定，允許合併") ? nil : "固定金額的測試應該判定穩定"
        },
        checkFixed: { lines in
            guard lines.contains("AI 產生的隨機金額測試：審查結果：不穩定，擋下") else { return "隨機金額的測試連跑 20 次會失敗，應該判定不穩定：連續呼叫 runTest()，有一次回傳 false 就不穩定" }
            return lines.contains("固定金額的測試：審查結果：穩定，允許合併") ? nil : "固定金額的測試連跑 20 次都通過，應該判定穩定"
        }
    )
}
