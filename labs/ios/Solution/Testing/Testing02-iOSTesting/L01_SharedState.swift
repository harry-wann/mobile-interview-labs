// L01：單獨跑都通過，平行跑就有測試失敗（測試共用單例）
// 對應複習站：Testing02iOSTesting 第 1 題「XCTest 和 Swift Testing 差在哪？要不要換？」
//
// 情境：三個測試都用 T02PriceFormatter 驗證價格顯示：「日圓顯示 ¥1,500,000」「美元顯示 $10,000」「日圓有千分位」。
//       格式要看設定的幣別。每個測試先設定幣別，再（模擬載入資料）await 20 ms，最後比較格式化的結果。
//       演練把同樣三個測試跑兩次：依序執行（XCTest 預設）、平行執行（Swift Testing 預設）。
// Bug 版：三個測試都用 App 共用的 T02AppSettings.shared。依序跑沒問題；平行跑時，大家同時改同一個幣別，有測試讀到別人設的值而失敗。
// 修正版：每個測試用自己的 T02AppSettings() 實例，互不干擾，平行跑也全過。
//        （另一個做法是對這組測試標 .serialized，讓它們依序執行；但根本的問題是共用狀態，沒有解決。）
import Foundation
import LabKit

/// 三個測試（兩個版本共用）；settings 決定每個測試用哪一個設定物件
private func formatSuite(settings makeSettings: @escaping () -> T02AppSettings) -> T02Suite {
    let suite = T02Suite()
    suite.test("日圓顯示 ¥1,500,000") { t in
        let settings = makeSettings()
        settings.currency = .jpy
        try? await Task.sleep(for: .milliseconds(20))                       // 模擬：測試中間有 await（載入資料）
        t.equal(T02PriceFormatter(settings: settings).format(1_500_000), "¥1,500,000")
    }
    suite.test("美元顯示 $10,000") { t in
        let settings = makeSettings()
        settings.currency = .usd
        try? await Task.sleep(for: .milliseconds(20))
        t.equal(T02PriceFormatter(settings: settings).format(1_500_000), "$10,000")
    }
    suite.test("日圓有千分位") { t in
        let settings = makeSettings()
        settings.currency = .jpy
        try? await Task.sleep(for: .milliseconds(20))
        t.equal(T02PriceFormatter(settings: settings).format(1_234_567), "¥1,234,567")
    }
    return suite
}

/// 同樣三個測試跑兩次：依序、平行
private func runBothWays(_ log: LabLog, settings: @escaping () -> T02AppSettings) async {
    T02AppSettings.shared.currency = .jpy
    await formatSuite(settings: settings).run(label: "依序執行", parallel: false, log: log)
    T02AppSettings.shared.currency = .jpy
    await formatSuite(settings: settings).run(label: "平行執行", parallel: true, log: log)
}

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        await runBothWays(log) { T02AppSettings.shared }          // 所有測試共用同一個設定物件
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        await runBothWays(log) { T02AppSettings() }               // 每個測試自己的設定物件
    }
}

// MARK: - 演練設定
extension Testing02iOSTesting {
    static let l01 = Lab(
        id: "L01",
        title: "單獨跑都通過，平行跑就有測試失敗",
        question: "第 1 題：XCTest 和 Swift Testing 差在哪？要不要換？",
        goal: "看測試共用單例時，依序執行沒事、平行執行就互相干擾；再改成每個測試用自己的實例。",
        steps: [
            "選「Bug 版」執行，比較 log 的「依序執行」和「平行執行」：哪一次有測試失敗？",
            "想一想：失敗的測試，它要測的程式有壞嗎？是誰改了它的幣別？（Swift Testing 預設平行；XCTest 預設依序）",
            "選「修正版」執行：兩種執行方式都要全過。",
        ],
        expectBug: "依序執行：通過 3、失敗 0；平行執行：有 1 到 2 個測試失敗（預期 ¥1,500,000，實際 $10,000，或相反），每次失敗的測試可能不同。",
        expectFixed: "依序執行與平行執行都是「通過 3、失敗 0」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let serial = T02Summary.parse(lines, label: "依序執行"),
                  let parallel = T02Summary.parse(lines, label: "平行執行") else { return "找不到摘要：要用 runBothWays 跑" }
            if serial.failed > 0 { return "依序執行也有測試失敗：每個測試用共用的 T02AppSettings.shared，依序跑不該互相干擾" }
            return parallel.failed > 0 ? nil : "平行執行沒有測試失敗：Bug 還沒重現（三個測試要共用 T02AppSettings.shared）"
        },
        checkFixed: { lines in
            guard let serial = T02Summary.parse(lines, label: "依序執行"),
                  let parallel = T02Summary.parse(lines, label: "平行執行") else { return "找不到摘要：要用 runBothWays 跑" }
            if serial.failed > 0 || parallel.failed > 0 { return "平行執行還是有測試失敗：每個測試要用自己的 T02AppSettings()，不能共用 shared" }
            return T02AppSettings.shared.currency == .jpy ? nil : "共用的 T02AppSettings.shared 被改了：測試不該碰 shared"
        }
    )
}
