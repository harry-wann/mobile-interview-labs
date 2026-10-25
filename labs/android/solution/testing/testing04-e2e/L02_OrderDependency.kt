// L02：「撤單」測試單獨跑就失敗，換個順序也失敗（測試依賴別的測試留下的資料）
// 對應複習站：Testing04E2E 第 2 題「交易所 App 的測試環境要注意什麼？」（每次測試前重設資料）
//
// 情境：兩個 E2E 測試：A「限價買單後，未成交列表有這筆訂單」、B「撤單後，未成交列表是空的」。
//       後端是 T04FakeExchange（每個帳號有自己的未成交訂單；createAccount() 建立全新的帳號）。
//       演練用三種方式跑：依序 A→B、順序換成 B→A、只跑 B（例如只重跑失敗的測試）。每一輪開始前，後端都是乾淨的。
// Bug 版：兩個測試共用同一個測試帳號 "e2e-user"，B 假設 A 已經下過單，自己沒有準備資料。
//        A→B 通過；B→A 和只跑 B，B 一開始就沒有訂單可以撤，失敗。
// 修正版：每個測試用 createAccount() 建立自己的帳號，B 自己先下一筆單當作前置條件再撤單。三種跑法都通過。
package tw.harry.labs.solution.testing04e2e

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private typealias L02Test = Pair<String, suspend (T04Expect) -> Unit>

/** 演練流程（兩個版本共用）：同樣兩個測試，用三種順序各跑一輪 */
private suspend fun scenario(log: LabLog, makeTests: (T04FakeExchange) -> List<L02Test>) {
    for ((label, order) in listOf("順序 A→B" to listOf(0, 1), "順序 B→A" to listOf(1, 0), "只跑 B" to listOf(1))) {
        val exchange = T04FakeExchange()                           // 每一輪開始前，後端都是乾淨的
        val all = makeTests(exchange)
        val suite = T04Suite()
        for (i in order) suite.test(all[i].first, all[i].second)
        suite.run(label, log)
    }
}

// MARK: - Bug 版
private object L02Bug {
    fun tests(exchange: T04FakeExchange): List<L02Test> = listOf(
        "A 限價買單後，未成交列表有這筆訂單" to { t ->
            exchange.placeLimitBuy("e2e-user", 15_000_000)
            t.equal(exchange.openOrders("e2e-user").size, 1)
        },
        "B 撤單後，未成交列表是空的" to { t ->
            t.isTrue(exchange.cancelFirst("e2e-user"), "沒有未成交的訂單可以撤")      // 假設 A 已經下過單
            t.equal(exchange.openOrders("e2e-user").size, 0)
        },
    )

    suspend fun run(log: LabLog) = scenario(log, ::tests)
}

// MARK: - 修正版
private object L02Fixed {
    fun tests(exchange: T04FakeExchange): List<L02Test> = listOf(
        "A 限價買單後，未成交列表有這筆訂單" to { t ->
            val account = exchange.createAccount()                 // 自己的帳號
            exchange.placeLimitBuy(account, 15_000_000)
            t.equal(exchange.openOrders(account).size, 1)
        },
        "B 撤單後，未成交列表是空的" to { t ->
            val account = exchange.createAccount()
            exchange.placeLimitBuy(account, 15_000_000)            // 自己準備前置條件：先有一筆未成交訂單
            t.isTrue(exchange.cancelFirst(account), "沒有未成交的訂單可以撤")
            t.equal(exchange.openOrders(account).size, 0)
        },
    )

    suspend fun run(log: LabLog) = scenario(log, ::tests)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "撤單測試單獨跑就失敗",
    question = "第 2 題：交易所 App 的測試環境要注意什麼？（每次測試前重設資料）",
    goal = "讓每個 E2E 測試自己準備資料（自己的帳號、自己的前置條件），不依賴別的測試，任何順序、單獨跑都通過。",
    steps = listOf(
        "選「Bug 版」執行，看三種跑法各自通過幾個、失敗的訊息是什麼。",
        "想一想：B 失敗時，撤單功能有 bug 嗎？CI 常常只重跑失敗的測試，會發生什麼事？",
        "選「修正版」執行：三種跑法都要全過。",
    ),
    expectBug = "順序 A→B：通過 2；順序 B→A 和只跑 B：B 失敗（沒有未成交的訂單可以撤）。",
    expectFixed = "三種跑法都沒有失敗：順序 A→B、順序 B→A 通過 2，只跑 B 通過 1。",
    file = "testing04-e2e/L02_OrderDependency.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ab = T04Summary.parse(lines, "順序 A→B")
        val ba = T04Summary.parse(lines, "順序 B→A")
        val onlyB = T04Summary.parse(lines, "只跑 B")
        when {
            ab == null || ba == null || onlyB == null -> "找不到摘要：要用 scenario 跑"
            ab.assertions != 3 -> "順序 A→B 只檢查了 ${ab.assertions} 項：A 檢查 1 項、B 檢查 2 項（撤得掉、撤完是空的）"
            ab.failed > 0 -> "順序 A→B 也失敗了：兩個測試都用 \"e2e-user\"，A 下單、B 撤單，依序跑應該全過"
            ba.failed == 0 || onlyB.failed == 0 -> "Bug 還沒重現：B 不要自己下單，直接 cancelFirst(\"e2e-user\")，換順序或單獨跑才會失敗"
            else -> null
        }
    },
    checkFixed = { lines ->
        val ab = T04Summary.parse(lines, "順序 A→B")
        val ba = T04Summary.parse(lines, "順序 B→A")
        val onlyB = T04Summary.parse(lines, "只跑 B")
        when {
            ab == null || ba == null || onlyB == null -> "找不到摘要：要用 scenario 跑"
            ab.assertions != 3 || onlyB.assertions != 2 -> "檢查的數量不對：A 要檢查 1 項、B 要檢查 2 項（撤得掉、撤完是空的）"
            ab.failed > 0 || ba.failed > 0 || onlyB.failed > 0 -> "還有測試失敗：每個測試要 createAccount() 用自己的帳號，B 要先自己 placeLimitBuy 再撤單"
            else -> null
        }
    },
)
