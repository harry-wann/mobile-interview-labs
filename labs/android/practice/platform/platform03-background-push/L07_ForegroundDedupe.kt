// L07：App 開著的時候，同一個價格提醒跳了兩次（前景去重）
// 對應複習站：Platform03BackgroundPush 第 6 題「價格提醒要怎麼設計？」
//
// 情境：使用者正在看行情頁，App 在前景，已經用 WebSocket 串流收行情與提醒。
// 同一個價格提醒，伺服器同時用兩條路送：
//        WebSocket（App 內橫幅）和 FCM 推播（App 在前景時，
// FirebaseMessagingService.onMessageReceived 會被呼叫，由 App
// 決定要不要顯示）。
//        演練送兩個提醒：提醒 a1 兩條路都到了，提醒 a2 只有推播到。
// 看畫面上一共顯示了幾次橫幅。
//
// 你要寫：
//   ① Bug 版（L07Bug）：串流與推播收到就各自顯示橫幅。
//   ② 修正版（L07Fixed）：用 alertId 記下已經顯示過的，同一個 alertId
// 只顯示一次。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform03-background-push/L07_ForegroundDedupe.kt
package tw.harry.labs.practice.platform03backgroundpush

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 一個價格提醒（兩個版本共用） */
private class L07PriceAlert(val id: String, val text: String)

/** App 內顯示橫幅的地方（兩個版本各自實作） */
private interface L07AlertPresenter {
    val banners: List<String>
    fun fromWebSocket(alert: L07PriceAlert) // App 內串流收到

    // 推播收到（Android：onMessageReceived）
    fun fromPush(alert: L07PriceAlert)
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, presenter: L07AlertPresenter) {
    val a1 = L07PriceAlert("a1", "BTC/JPY 到達 10,000,000")
    val a2 = L07PriceAlert("a2", "ETH/JPY 到達 500,000")
    presenter.fromWebSocket(a1) // 提醒 a1：串流先到
    presenter.fromPush(a1) //        推播也到
    // 提醒 a2：只有推播到
    presenter.fromPush(a2)
    log(
        "畫面上一共顯示了 ${presenter.banners.size} 次橫幅：${presenter.banners.joinToString(
            "、",
        )}",
    )
}

// MARK: - Bug 版
private object L07Bug {
    class Presenter : L07AlertPresenter {
        override val banners = mutableListOf<String>()

        // TODO ①：兩個函式都一樣：收到就把 alert.id 加進 banners（直接顯示，
        // 不檢查有沒有顯示過）。現在先什麼都不做
        override fun fromWebSocket(alert: L07PriceAlert) {}
        override fun fromPush(alert: L07PriceAlert) {}
    }

    fun run(log: LabLog) = scenario(log, Presenter())
}

// MARK: - 修正版
private object L07Fixed {
    class Presenter : L07AlertPresenter {
        override val banners = mutableListOf<String>()

        // 已經顯示過的 alertId
        private val shown = mutableSetOf<String>()

        override fun fromWebSocket(alert: L07PriceAlert) = show(alert)
        override fun fromPush(alert: L07PriceAlert) = show(alert)

        private fun show(alert: L07PriceAlert) {
            // TODO ②：同一個 alertId 只顯示一次：用 shown 記下 alert.id，
            // 已經在裡面就 return；否則把 alert.id 加進 banners
        }
    }

    fun run(log: LabLog) = scenario(log, Presenter())
}

// MARK: - 演練設定
private fun bannerCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("畫面上一共顯示了 ")
}?.removePrefix("畫面上一共顯示了 ")?.substringBefore(" ")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "App 開著，同一個提醒跳了兩次",
    question = "第 6 題：價格提醒要怎麼設計？",
    goal = "同一個提醒用不同的路送到（串流、推播），畫面上只能顯示一次；只從一條路到的提醒還是要顯示。先寫出同一個提醒顯示兩次的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform03-background-push/L07_ForegroundDedupe.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「畫面上一共顯示了幾次橫幅」。",
        "寫 TODO ②（修正版），選「修正版」執行：a1 只顯示一次，a2 照常顯示，一共 2 次。",
        "想一想：alertId 要由誰產生？（由伺服器產生，推播與串流帶同一個 alertId，client 才能去重）",
    ),
    expectBug = "畫面上一共顯示了 3 次橫幅：a1、a1、a2。",
    expectFixed = "畫面上一共顯示了 2 次橫幅：a1、a2。",
    file = "platform03-background-push/L07_ForegroundDedupe.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = bannerCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n < 3 -> "Bug 還沒重現（只顯示 $n 次）：兩條路收到就各自顯示"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = bannerCount(lines)
        when {
            n == null -> "演練沒有跑完"
            n > 2 -> "顯示了 $n 次：用 alertId 記下已經顯示過的，同一個 alertId 不重複顯示"
            n < 2 -> "只顯示了 $n 次：只從一條路到的提醒（a2）還是要顯示"
            else -> null
        }
    },
)
