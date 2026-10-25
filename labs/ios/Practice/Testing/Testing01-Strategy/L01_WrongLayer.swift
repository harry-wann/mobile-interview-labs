// L01：12 條下單規則全用 UI 測試驗，慢又時過時不過（選錯測試層級）
// 對應複習站：Testing01Strategy 第 1 題「測試分成哪幾層？每一層測什麼？」
//
// 情境：下單有 12 個案例要驗（價格要是 1,000 日圓的倍數、數量不能低於 0.0001
// BTC、不能超過 5 BTC…）。
//       規則本身是一個純函式 T01OrderRules.validate（已經寫好）。
//       UI 測試用 T01FakeOrderScreen 模擬：每個案例要冷啟動加登入 120 ms、輸入
// 40 ms、點確認 20 ms，
//       錯誤訊息要等動畫結束才會出現在畫面上（有的案例動畫超過 100 ms）。
//
// 你要寫：
//   ① Bug 版：每個案例都走 UI 測試：建立 T01FakeOrderScreen(animationMs: c.
// animationMs)，依序
//      launch()、type(priceJPY:amountSatoshi:)、tapConfirm()，再用 Task.sleep
// 固定等 100 ms，最後 t.equal(screen.errorLabel(), c.expected)。
//   ② 修正版：規則是純邏輯，直接 t.equal(T01OrderRules.validate(…), c.
// expected)，不經過 T01FakeOrderScreen。
// 只改有 TODO 的地方。解答：Solution/Testing/Testing01-Strategy/L01_WrongLayer.
// swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        let suite = T01Suite()
        for c in T01OrderCase.all {
            suite.test(c.name) { t in
                // TODO ①：用 UI
                // 測試的方式驗證這個案例（寫法見檔案開頭的說明）。
                //        固定等待用 try? await Task.sleep(for:
                // milliseconds(100))
                _ = (t, c)
            }
        }
        await suite.run(label: "UI 層", log: log)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        let suite = T01Suite()
        for c in T01OrderCase.all {
            suite.test(c.name) { t in
                // TODO ②：直接呼叫 T01OrderRules.validate(priceJPY:
                // amountSatoshi:)，用 t.equal 比較結果和 c.expected
                _ = (t, c)
            }
        }
        await suite.run(label: "單元層", log: log)
    }
}

// MARK: - 演練設定
extension Testing01Strategy {
    static let l01 = Lab(
        id: "L01",
        title: "12 條規則全用 UI 測，慢又時過時不過",
        question: "第 1 題：測試分成哪幾層？每一層測什麼？",
        goal: "同樣 12 個下單規則案例，比較「走 UI 測試」和「直接驗純函式」要花多久、穩不穩定。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 log：跑了幾個案例、花了幾毫秒、哪幾個案例失敗，想一想失敗的案例，規則本身是不是其實是對的。",
            "寫 TODO ②，選「修正版」執行，比較花的時間和失敗數。",
            "想一想：哪些事情一定要用 UI 測試才驗得到？（例如「點了確認，畫面真的有跳出錯誤」這一條）",
        ],
        expectBug:
            "UI 層跑 12 個案例要三秒多（解答實測約 3.4 秒），有 5 個案例失敗：動畫比 100 ms 久，固定 sleep 之後畫面還沒有錯誤訊息，規則其實是對的。",
        expectFixed: "單元層跑 12 個案例不到 5 ms，12 個全過，每次結果都一樣。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let s = T01Summary.parse(lines, label: "UI 層") else {
                return "找不到「UI 層」的摘要：要把 12 個案例交給 suite.test 並呼叫 suite.run"
            }
            if s.cases != 12 {
                return "只跑了 \(s.cases) 個案例：T01OrderCase.all 的 12 個都要走 UI 測試"
            }
            if s.assertions != 12 {
                return
                    "只檢查了 \(s.assertions) 項：每個案例都要用 t.equal 比較 errorLabel() 和 c.expected"
            }
            if s.ms < 2_000 {
                return
                    "只花了 \(s.ms) ms：每個案例都要 launch、type、tapConfirm，再固定 sleep 100 ms"
            }
            if s.failed == 0 {
                return
                    "沒有案例失敗：Bug 還沒重現（要用固定 sleep 100 ms 再讀 errorLabel，動畫比較久的案例會讀不到）"
            }
            return nil
        },
        checkFixed: { lines in
            guard let s = T01Summary.parse(lines, label: "單元層") else {
                return "找不到「單元層」的摘要：要把 12 個案例交給 suite.test 並呼叫 suite.run"
            }
            if s.cases != 12 {
                return "只跑了 \(s.cases) 個案例：T01OrderCase.all 的 12 個都要驗"
            }
            if s.assertions != 12 {
                return
                    "只檢查了 \(s.assertions) 項：每個案例都要用 t.equal 比較 validate 的結果和 c.expected"
            }
            if s.failed > 0 {
                return
                    "有 \(s.failed) 個案例失敗：直接比較 T01OrderRules.validate 的結果和 c.expected"
            }
            return s.ms < 50 ? nil : "花了 \(s.ms) ms，太慢了：不要經過 T01FakeOrderScreen"
        }
    )
}
