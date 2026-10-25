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
//   ① Bug 版：每個案例都走 UI 測試：建立 T01FakeOrderScreen(c.animationMs)，
// 依序
//      launch()、type(c.priceJpy, c.amountSatoshi)、tapConfirm()，再用
// delay(100) 固定等 100 ms，最後 t.equal(screen.errorLabel(), c.expected)。
//   ② 修正版：規則是純邏輯，直接 t.equal(T01OrderRules.validate(…), c.
// expected)，不經過 T01FakeOrderScreen。
// 只改有 TODO 的地方。解答：solution/testing/testing01-strategy/L01_WrongLayer.
// kt
package tw.harry.labs.practice.testing01strategy

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L01Bug {
    suspend fun run(log: LabLog) {
        val suite = T01Suite()
        for (c in T01OrderCase.all) {
            suite.test(c.name) { t ->
                // TODO ①：用 UI
                // 測試的方式驗證這個案例（寫法見檔案開頭的說明）。固定等待用
                // kotlinx.coroutines.delay(100)
            }
        }
        suite.run("UI 層", log)
    }
}

// MARK: - 修正版
private object L01Fixed {
    suspend fun run(log: LabLog) {
        val suite = T01Suite()
        for (c in T01OrderCase.all) {
            suite.test(c.name) { t ->
                // TODO ②：直接呼叫 T01OrderRules.validate(priceJpy,
                // amountSatoshi)，用 t.equal 比較結果和 c.expected
            }
        }
        suite.run("單元層", log)
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "12 條規則全用 UI 測，慢又時過時不過",
    question = "第 1 題：測試分成哪幾層？每一層測什麼？",
    goal = "同樣 12 個下單規則案例，比較「走 UI 測試」和「直接驗純函式」要花多久、穩不穩定。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看 log：跑了幾個案例、花了幾毫秒、哪幾個案例失敗，想一想失敗的案例，規則本身是不是其實是對的。",
        "寫 TODO ②，選「修正版」執行，比較花的時間和失敗數。",
        "想一想：哪些事情一定要用 UI 測試才驗得到？（例如「點了確認，畫面真的有跳出錯誤」這一條）",
    ),
    expectBug = "UI 層跑 12 個案例要三秒多（解答實測約 3.4 秒），有 5 個案例失敗：動畫比 100 ms 久，固定等待之後畫面還沒有錯誤訊息，規則其實是對的。",
    expectFixed = "單元層跑 12 個案例不到 5 ms，12 個全過，每次結果都一樣。",
    file = "testing01-strategy/L01_WrongLayer.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val s = T01Summary.parse(lines, "UI 層")
        when {
            s == null -> "找不到「UI 層」的摘要：要把 12 個案例交給 suite.test 並呼叫 suite.run"
            s.cases != 12 -> "只跑了 ${s.cases} 個案例：T01OrderCase.all 的 12 個都要走 UI 測試"
            s.assertions != 12 -> "只檢查了 ${s.assertions} 項：每個案例都要用 t.equal 比較 errorLabel() 和 c.expected"
            s.ms < 2_000 -> "只花了 ${s.ms} ms：每個案例都要 launch、type、tapConfirm，再固定 delay 100 ms"
            s.failed == 0 -> "沒有案例失敗：Bug 還沒重現（要固定 delay 100 ms 再讀 errorLabel，動畫比較久的案例會讀不到）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val s = T01Summary.parse(lines, "單元層")
        when {
            s == null -> "找不到「單元層」的摘要：要把 12 個案例交給 suite.test 並呼叫 suite.run"
            s.cases != 12 -> "只跑了 ${s.cases} 個案例：T01OrderCase.all 的 12 個都要驗"
            s.assertions != 12 -> "只檢查了 ${s.assertions} 項：每個案例都要用 t.equal 比較 validate 的結果和 c.expected"
            s.failed > 0 -> "有 ${s.failed} 個案例失敗：直接比較 T01OrderRules.validate 的結果和 c.expected"
            s.ms >= 50 -> "花了 ${s.ms} ms，太慢了：不要經過 T01FakeOrderScreen"
            else -> null
        }
    },
)
