// L01：App 被關掉之後，價格到了卻沒有提醒（提醒的條件只存在 App 裡，由 App
// 判斷）
// 對應複習站：Design04PriceAlerts 第 2 題「為什麼價格的判斷一定要放在伺服器？」
//
// 情境：使用者建立兩個提醒：a1「BTC 高於 9,960,000 円」、a2「BTC 高於 10,000,
// 000 円」。價格依序是 9,900,000、9,970,000、10,020,000、10,050,000。
//       在第三個價格出現之前，使用者把 App 從最近使用的 App 清單滑掉（App
// 被關閉）。
//       行情只會送到還活著的 App；伺服器的價格串流則一直都在。
// 手機的通知列不管通知是 App 自己發的，還是伺服器透過 FCM 推播來的，
//       App 關掉之後都看得到推播（本機通知要靠 App 活著才發得出來）。
// 看手機最後收到哪些通知。
// Bug 版：提醒條件存在 App 裡，App 每收到一筆價格就比對、發通知。a1 在 9,970,
// 000 觸發了；App 被關閉之後沒有人再看價格，
//        a2 到了 10,020,000 也沒有通知。
// 修正版：建立提醒時把條件送到伺服器（register）；App 自己不判斷。
// 伺服器一直在看價格，條件成立就用推播送到手機：
//        a1、a2 都收到，包括 App 被關閉之後的 a2。
package tw.harry.labs.solution.design04pricealerts

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L01Alert(val id: String, val threshold: Int)

/** 手機的通知列（兩個版本共用） */
private class L01Phone {
    val notifications = mutableListOf<String>()
    fun show(alertId: String) {
        notifications += alertId
    }

    val summary: String get() = if (notifications.isEmpty()) {
        "（沒有）"
    } else {
        notifications.joinToString(
            "、",
        )
    }
}

/**
 * 伺服器（兩個版本共用，已經寫好）：記下 App 登記的提醒；每來一個價格，
 * 條件成立又還沒觸發過的，就推播到手機
 */
private class L01FakeServer {
    private val alerts = mutableListOf<L01Alert>()
    private val fired = mutableSetOf<String>()

    fun register(alert: L01Alert) {
        alerts += alert
    }

    fun onPrice(price: Int, push: (String) -> Unit) {
        for (alert in alerts) {
            if (price >= alert.threshold && fired.add(alert.id)) push(alert.id)
        }
    }
}

private interface L01AlertApp {
    /** 使用者建立一個提醒 */
    fun createAlert(alert: L01Alert)

    /** App 收到一筆價格（只有 App 活著、在收行情的時候才會被呼叫） */
    fun onPrice(price: Int)
}

private val l01Alerts =
    listOf(L01Alert("a1", 9_960_000), L01Alert("a2", 10_000_000))
private val l01Prices = listOf(9_900_000, 9_970_000, 10_020_000, 10_050_000)

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    makeApp: (L01FakeServer, L01Phone) -> L01AlertApp,
) {
    val phone = L01Phone()
    val server = L01FakeServer()
    val app = makeApp(server, phone)
    var appRunning = true
    for (alert in l01Alerts) {
        app.createAlert(alert)
        log("使用者建立提醒 ${alert.id}：BTC 高於 ${d04Yen(alert.threshold)} 円")
    }
    for ((index, price) in l01Prices.withIndex()) {
        if (index == 2) {
            appRunning = false
            log("使用者把 App 從最近使用的 App 清單滑掉（App 被關閉）")
        }
        // 行情只送到還活著的 App
        if (appRunning) app.onPrice(price)
        // 伺服器的價格串流一直都在
        server.onPrice(price) { phone.show(it) }
        log("價格 ${d04Yen(price)} 円 → 手機收到的通知：${phone.summary}")
    }
    log("最後：手機收到 ${phone.notifications.size} 則通知：${phone.summary}")
}

// MARK: - Bug 版
private object L01Bug {
    class App(private val phone: L01Phone) : L01AlertApp {
        private val alerts = mutableListOf<L01Alert>()
        private val fired = mutableSetOf<String>()

        override fun createAlert(alert: L01Alert) {
            // 條件只存在 App 裡
            alerts += alert
        }

        override fun onPrice(price: Int) {
            for (alert in alerts) {
                if (price >= alert.threshold && fired.add(alert.id)) {
                    // App 自己比對，自己發通知
                    phone.show(alert.id)
                }
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { _, phone -> App(phone) }
}

// MARK: - 修正版
private object L01Fixed {
    class App(private val server: L01FakeServer) : L01AlertApp {
        override fun createAlert(alert: L01Alert) {
            // 條件送到伺服器，由伺服器判斷
            server.register(alert)
        }

        // App 不判斷價格
        override fun onPrice(price: Int) {}
    }

    suspend fun run(log: LabLog) = scenario(log) { server, _ -> App(server) }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "App 被關掉之後，價格到了卻沒有提醒",
    question = "第 2 題：為什麼價格的判斷一定要放在伺服器？",
    goal = "價格有沒有到，只能由伺服器判斷：App 在背景不能可靠地持續執行（被關掉就完全不執行）。App 的工作是把提醒條件送到伺服器，伺服器條件成立就推播。",
    steps = listOf(
        "選「Bug 版」執行，看使用者把 App 滑掉前後，手機收到的通知有什麼不同。",
        "想一想：a2 在 App 被關閉之後才到價，誰會發現？（App 沒有在跑，沒有人比對。）",
        "選「修正版」執行：a1、a2 都收到，包括 App 被關閉之後的 a2。",
        "想一想：Android 的 WorkManager 或 iOS 的 BGAppRefreshTask 能不能讓 App 在背景定期檢查價格？（系統決定什麼時候執行，Doze 時還會被延後，可能很久才一次，不能當成「到價就通知」的機制。）",
    ),
    expectBug = "a1 在 9,970,000 觸發；App 被關閉後 a2 沒有人判斷：「最後：手機收到 1 則通知：a1」。",
    expectFixed = "伺服器一直在看價格：「最後：手機收到 2 則通知：a1、a2」。",
    file = "design04-price-alerts/L01_ServerSideJudge.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeApp) 執行"

            last.startsWith(
                "最後：手機收到 0 則通知",
            ) -> "App 還沒判斷價格：createAlert 要把提醒存進 alerts，onPrice 要比對價格並呼叫 phone.show"

            last != "最後：手機收到 1 則通知：a1" -> "Bug 還沒重現：App 活著的時候比對價格、發通知（a1 會響）；App 被關閉之後沒有人比對，a2 不會響"

            else -> null
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeApp) 執行"

            last.startsWith(
                "最後：手機收到 0 則通知",
            ) -> "createAlert 還沒把條件送給伺服器：呼叫 server.register(alert)"

            last != "最後：手機收到 2 則通知：a1、a2" -> "App 不判斷價格：createAlert 只要 server.register(alert)，a1、a2 都由伺服器推播"

            else -> null
        }
    },
)
