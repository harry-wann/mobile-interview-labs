// L03：UI 測試顯示的價格每次都不一樣，還要先走一遍登入（啟動時沒有換 stub）
// 對應複習站：Testing02iOSTesting 第 3 題「UI 測試（XCUITest）
// 怎麼寫才會穩定？」
//
// 情境：UI 測試在每次啟動 App 時傳 launchArguments：["-uiTesting", "-startAt",
// "orderForm"]
//       （意思是：用 stub，直接進到下單畫面）。測試驗證下單畫面顯示的 BTC
// 價格是 15,000,000。
//       makeApp(arguments:) 是 App 啟動時決定「用哪個 API、從哪個畫面開始」
// 的地方。
//       真的伺服器（T02LiveAPI）行情一直在動；從登入畫面走到下單畫面要 4
// 個步驟。
//       演練把同一個 UI 測試跑 3 次。
//
// 你要寫：
//   ① Bug 版：makeApp 完全不看 arguments：回傳連真的伺服器（T02LiveAPI()）、從
// login 畫面開始的 T02App。
//   ② 修正版：arguments 有 "-uiTesting" 就用 T02StubAPI()，否則用
// T02LiveAPI()；
//      arguments 有 "-startAt"，下一個值（例如 "orderForm"）
// 就是起始畫面（T02Screen(rawValue:)），沒有就從 login 開始。
// 只改有 TODO 的地方。解答：
// Solution/Testing/Testing02-iOSTesting/L03_LaunchArguments.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：同一個 UI 測試跑 3 次
private func scenario(
    _ log: LabLog, makeApp: @escaping ([String]) -> T02App
) async {
    // 真的伺服器的行情從頭開始
    T02LiveAPI.calls = 0
    let tally = T02Tally()
    let suite = T02Suite()
    for i in 1...3 {
        suite.test("第 \(i) 次：下單畫面顯示 BTC 價格") { t in
            // 相當於 XCUIApplication().launchArguments
            let app = makeApp(["-uiTesting", "-startAt", "orderForm"])
            // 沒有直接進到下單畫面：要先走一遍登入
            if app.startScreen == .login { tally.steps += T02App.loginSteps }
            t.equal(await app.api.btcPrice(), 15_000_000, "畫面上的 BTC 價格")
        }
    }
    await suite.run(label: "啟動 App 測試", log: log)
    log("3 次測試一共多做了 \(tally.steps) 個登入步驟")
}

// MARK: - Bug 版
private enum L03Bug {
    static func makeApp(arguments: [String]) -> T02App {
        // TODO ①：完全不看 arguments，回傳 T02App(api: T02LiveAPI(),
        // startScreen: login)
        //        （目前這行直接進到下單畫面又用 stub，Bug 不會出現）
        T02App(api: T02StubAPI(), startScreen: .orderForm)
    }

    static func run(_ log: LabLog) async {
        await scenario(log, makeApp: makeApp)
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func makeApp(arguments: [String]) -> T02App {
        // TODO ②-1：arguments 有 "-uiTesting" 時用 T02StubAPI()，否則用
        // T02LiveAPI()
        // TODO ②-2：arguments 有 "-startAt" 時，下一個值就是起始畫面（用
        // T02Screen(rawValue:) 轉換）；沒有就從 login 開始
        return T02App(api: T02LiveAPI(), startScreen: .login)
    }

    static func run(_ log: LabLog) async {
        await scenario(log, makeApp: makeApp)
    }
}

// MARK: - 演練設定
extension Testing02iOSTesting {
    static let l03 = Lab(
        id: "L03",
        title: "UI 測試的價格每次都不一樣，還要先登入",
        question: "第 3 題：UI 測試（XCUITest）怎麼寫才會穩定？",
        goal: "讓 App 啟動時看 launchArguments：換成 stub、直接進到要測的畫面，UI 測試才穩定又快。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 3 次測試的結果和「多做了幾個登入步驟」。",
            "想一想：失敗的那兩次，下單畫面有 bug 嗎？還是價格本來就在變？",
            "寫 TODO ②，選「修正版」執行：3 次都要通過，登入步驟 0 個。",
        ],
        expectBug:
            "3 次測試「通過 1、失敗 2」（價格只有一次剛好是 15,000,000），3 次測試一共多做了 12 個登入步驟。",
        expectFixed: "3 次測試「通過 3、失敗 0」，3 次測試一共多做了 0 個登入步驟。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let s = T02Summary.parse(lines, label: "啟動 App 測試") else {
                return "找不到摘要：要用 scenario 跑"
            }
            if s.assertions != 3 {
                return
                    "只檢查了 \(s.assertions) 項：makeApp 要回傳一個可以 btcPrice() 的 T02App"
            }
            if s.failed == 0 {
                return
                    "3 次測試都通過：Bug 還沒重現（makeApp 要連真的伺服器 T02LiveAPI，不看 arguments）"
            }
            return lines.contains("3 次測試一共多做了 12 個登入步驟")
                ? nil : "Bug 版的 App 不看 -startAt：每次都要從 .login 畫面開始，3 次共 12 個登入步驟"
        },
        checkFixed: { lines in
            guard let s = T02Summary.parse(lines, label: "啟動 App 測試") else {
                return "找不到摘要：要用 scenario 跑"
            }
            if s.assertions != 3 {
                return
                    "只檢查了 \(s.assertions) 項：makeApp 要回傳一個可以 btcPrice() 的 T02App"
            }
            if s.failed > 0 {
                return
                    "還有 \(s.failed) 次價格不是 15,000,000：arguments 有 \"-uiTesting\" 時要用 T02StubAPI()"
            }
            return lines.contains("3 次測試一共多做了 0 個登入步驟")
                ? nil
                : "還是多走了登入步驟：arguments 有 \"-startAt\" 時，下一個值（orderForm）就是起始畫面"
        }
    )
}
