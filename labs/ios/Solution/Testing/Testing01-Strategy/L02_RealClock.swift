// L02：同一個測試，半夜跑就失敗（測試依賴真實的系統時間）
// 對應複習站：Testing01Strategy 第 3 題「怎麼把程式設計成好測試的樣子？」
//
// 情境：交易所每天日本時間 04:00 到 04:30 維護，這段時間不能下單。MaintenanceGuard.canPlaceOrder() 判斷現在能不能下單。
//       有人寫了測試「平日白天應該可以下單」，在公司下午跑都是綠燈。
//       演練用 T01SystemClock 模擬「系統時間」（程式裡直接寫 Date() 讀到的就是它），
//       並讓同一個測試分別跑在 10:00、04:10、23:50，代表 CI 不同時間點的執行。
// Bug 版：canPlaceOrder() 在裡面直接讀系統時間。測試跑在 04:10 就失敗，沒改任何程式，卻時過時不過。
// 修正版：MaintenanceGuard 的建構子接受 `now: () -> Date`，程式用注入的時間判斷；
//        測試傳入固定的時間，邊界（04:00、04:29、04:30）也能驗，不管什麼時候跑結果都一樣。
import Foundation
import LabKit

/// 同一個測試跑在三個不同的系統時間
private func runAtDifferentTimes(_ log: LabLog, makeSuite: () -> T01Suite) async {
    for (label, hour, minute) in [("10:00", 10, 0), ("04:10", 4, 10), ("23:50", 23, 50)] {
        T01SystemClock.now = T01Time.at(hour, minute)                     // 模擬：這次測試剛好跑在這個時間點
        await makeSuite().run(label: "跑在 \(label)", log: log)
    }
}

// MARK: - Bug 版
private enum L02Bug {
    struct MaintenanceGuard {
        func canPlaceOrder() -> Bool {
            !T01Time.isInMaintenance(T01SystemClock.now)                    // 直接讀系統時間
        }
    }

    static func run(_ log: LabLog) async {
        await runAtDifferentTimes(log) {
            let suite = T01Suite()
            suite.test("平日白天應該可以下單") { t in
                t.isTrue(MaintenanceGuard().canPlaceOrder(), "不能下單")       // 沒有指定時間：跑在哪個時間就用哪個
            }
            return suite
        }
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct MaintenanceGuard {
        let now: () -> Date                                                 // 時間從外面注入

        func canPlaceOrder() -> Bool {
            !T01Time.isInMaintenance(now())                                 // 用注入的時間判斷
        }
    }

    static func run(_ log: LabLog) async {
        await runAtDifferentTimes(log) {
            let suite = T01Suite()
            let cases: [(String, Int, Int, Bool)] = [
                ("10:00 可以下單", 10, 0, true),
                ("04:00 維護開始，不能下單", 4, 0, false),
                ("04:29 還在維護，不能下單", 4, 29, false),
                ("04:30 維護結束，可以下單", 4, 30, true),
            ]
            for (name, hour, minute, expected) in cases {
                suite.test(name) { t in
                    let guardAtThatTime = MaintenanceGuard(now: { T01Time.at(hour, minute) })   // 測試自己指定時間
                    t.equal(guardAtThatTime.canPlaceOrder(), expected)
                }
            }
            return suite
        }
    }
}

// MARK: - 演練設定
extension Testing01Strategy {
    static let l02 = Lab(
        id: "L02",
        title: "同一個測試，半夜跑就失敗",
        question: "第 3 題：怎麼把程式設計成好測試的樣子？",
        goal: "讓測試不再依賴真實的系統時間：把時間注入進去，測試才能指定時間點，也驗得到維護時段的邊界。",
        steps: [
            "選「Bug 版」執行，看同一個測試跑在 10:00、04:10、23:50 的結果。",
            "想一想：04:10 失敗時，程式有壞嗎？還是測試的假設（現在一定能下單）錯了？",
            "選「修正版」執行：三個系統時間下結果一樣，而且驗到 04:00、04:29、04:30 的邊界。",
        ],
        expectBug: "跑在 10:00 和 23:50 通過，跑在 04:10 失敗（「不能下單」）：程式沒改，結果卻跟著執行的時間變。",
        expectFixed: "三個系統時間下都是「跑了 4 個案例，通過 4、失敗 0」，邊界 04:00、04:29、04:30 都驗到了。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let day = T01Summary.parse(lines, label: "跑在 10:00"),
                  let night = T01Summary.parse(lines, label: "跑在 04:10"),
                  let late = T01Summary.parse(lines, label: "跑在 23:50") else { return "找不到三個時間點的摘要：要用 runAtDifferentTimes 跑測試" }
            if day.failed > 0 || late.failed > 0 { return "10:00 或 23:50 不該失敗：canPlaceOrder() 在維護時段以外要回傳 true" }
            return night.failed > 0 ? nil : "跑在 04:10 沒有失敗：Bug 還沒重現（canPlaceOrder() 要直接讀 T01SystemClock.now）"
        },
        checkFixed: { lines in
            for label in ["10:00", "04:10", "23:50"] {
                guard let s = T01Summary.parse(lines, label: "跑在 \(label)") else { return "找不到「跑在 \(label)」的摘要" }
                if s.cases != 4 { return "跑在 \(label) 只有 \(s.cases) 個案例：測試要驗 4 個時間點" }
                if s.assertions != 4 { return "跑在 \(label) 只檢查了 \(s.assertions) 項：每個案例要檢查一次" }
                if s.failed > 0 { return "跑在 \(label) 有 \(s.failed) 個案例失敗：要用注入的 now()，不能讀 T01SystemClock.now" }
            }
            return nil
        }
    )
}
