// L05：伺服器發了價格提醒，App 在背景時 onMessageReceived 沒有被呼叫，Doze
// 時還被延後（FCM 的訊息種類與優先權）
// 對應複習站：Platform03BackgroundPush 第 5 題「APNs 和 FCM
// 的訊息有哪幾種？優先權、collapse、TTL 是什麼？」
//
// 情境：伺服器要把價格提醒推到使用者的手機。App 需要在 onMessageReceived
// 裡把提醒存進資料庫，再自己顯示通知。
//        手機現在的狀態：App 在背景、手機在 Doze（靜止、沒充電、
// 螢幕關閉一段時間）。你要寫的是 buildPriceAlert()：組出 FCM 訊息。
//        FakeFcm 依 Google 文件上的規則決定訊息怎麼送：
//        · notification 訊息：App 在背景時由系統直接顯示通知，
// onMessageReceived 不會被呼叫；
//        · data 訊息：不論 App 在前景或背景都會呼叫 onMessageReceived；
//        · 優先權 NORMAL 在 Doze 時會被延後，HIGH 可以喚醒手機。
//        （真的 FCM 要用伺服器金鑰連線，演練只檢查你組出來的東西。）
//
// 你要寫：
//   ① Bug 版（L05Bug）：訊息帶 notification、優先權 NORMAL、沒有 collapse_key
// 與 ttl。
//   ② 修正版（L05Fixed）：只帶 data、優先權 HIGH、collapse_key、ttl 600 秒。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform03-background-push/L05_FcmMessage.kt
package tw.harry.labs.practice.platform03backgroundpush

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 要送給 FCM 的一則訊息（兩個版本共用） */
private class L05FcmMessage(
    val notification: Map<String, String>?,
    val data: Map<String, String>,
    // "NORMAL" 或 "HIGH"
    val priority: String,
    val collapseKey: String?,
    val ttlSeconds: Int?,
)

/** 假的 FCM（兩個版本共用）：App 在背景、手機在 Doze 時，這則訊息怎麼送 */
private object L05FakeFcm {
    class Result(
        val delayedByDoze: Boolean,
        val onMessageReceivedCalled: Boolean,
    )

    fun deliver(message: L05FcmMessage): Result {
        // Doze：只有 HIGH 能喚醒
        val delayed = message.priority != "HIGH"
        // 背景：notification 訊息由系統顯示，不會呼叫 onMessageReceived
        val called = message.notification == null
        return Result(delayedByDoze = delayed, onMessageReceivedCalled = called)
    }
}

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, build: (alertId: String) -> L05FcmMessage) {
    val message = build("a1b2c3")
    val result = L05FakeFcm.deliver(message)
    log(
        "訊息種類：${if (message.notification != null) "notification 訊息" else "data 訊息"}、優先權 ${message.priority}",
    )
    log(
        "App 在背景、手機在 Doze：訊息${if (result.delayedByDoze) "被 Doze 延後" else "立刻到達"}",
    )
    log(
        "onMessageReceived 被呼叫：${if (result.onMessageReceivedCalled) "是" else "否"}",
    )
    log(
        "collapse_key：${message.collapseKey ?: "（沒有）"}、ttl：${message.ttlSeconds?.let {
            "$it 秒"
        } ?: "（沒有）"}",
    )
}

// MARK: - Bug 版
private object L05Bug {
    /** 組出價格提醒的 FCM 訊息 */
    fun buildPriceAlert(alertId: String): L05FcmMessage {
        // TODO ①：組出 L05FcmMessage：notification 放 title「價格提醒」與
        // body「BTC/JPY 到達 10,000,000」；
        //        data 放 type 是 price_alert、alertId；priority 用 "NORMAL"；
        // collapseKey、ttlSeconds 都是 null
        return L05FcmMessage(
            notification = null,
            data = emptyMap(),
            priority = "NORMAL",
            collapseKey = null,
            ttlSeconds = null,
        )
    }

    fun run(log: LabLog) = scenario(log, ::buildPriceAlert)
}

// MARK: - 修正版
private object L05Fixed {
    fun buildPriceAlert(alertId: String): L05FcmMessage {
        // TODO ②：組出 L05FcmMessage：不帶 notification（null），只帶
        // data（type 是 price_alert、symbol 是 btcjpy、alertId）；
        //        priority 用 "HIGH"（使用者要立刻看到）；collapseKey 用
        // "price_alert_btcjpy"（新的取代舊的）；ttlSeconds 用 600（10
        // 分鐘還沒送到就丟掉）
        return L05FcmMessage(
            notification = null,
            data = emptyMap(),
            priority = "NORMAL",
            collapseKey = null,
            ttlSeconds = null,
        )
    }

    fun run(log: LabLog) = scenario(log, ::buildPriceAlert)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "價格提醒在背景進不了 onMessageReceived",
    question = "第 5 題：APNs 和 FCM 的訊息有哪幾種？優先權、collapse、TTL 是什麼？",
    goal = "價格提醒要送到 onMessageReceived 讓 App 自己處理，而且 Doze 時也要立刻到：用 data 訊息、優先權 HIGH，並設定 collapse_key 與 ttl。先寫出背景進不了 onMessageReceived 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform03-background-push/L05_FcmMessage.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「onMessageReceived 被呼叫」和訊息有沒有被 Doze 延後。",
        "寫 TODO ②（修正版），選「修正版」執行：立刻到達、onMessageReceived 被呼叫、collapse_key 與 ttl 都有。",
        "想一想：HIGH 為什麼不能濫用？（沒有顯示使用者看得到的通知時，FCM 會把它降級）",
    ),
    expectBug = "notification 訊息；App 在背景、手機在 Doze：訊息被 Doze 延後；onMessageReceived 被呼叫：否；collapse_key 與 ttl 都沒有。",
    expectFixed = "data 訊息、優先權 HIGH；訊息立刻到達；onMessageReceived 被呼叫：是；collapse_key：price_alert_btcjpy、ttl：600 秒。",
    file = "platform03-background-push/L05_FcmMessage.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("訊息種類：") } -> "演練沒有跑完"

            !lines.contains(
                "onMessageReceived 被呼叫：否",
            ) -> "Bug 還沒重現：Bug 版要帶 notification（title 與 body），背景時由系統顯示，onMessageReceived 就不會被呼叫"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("訊息種類：") } -> "演練沒有跑完"

            !lines.contains(
                "onMessageReceived 被呼叫：是",
            ) -> "onMessageReceived 沒有被呼叫：不要帶 notification，只帶 data"

            !lines.contains(
                "App 在背景、手機在 Doze：訊息立刻到達",
            ) -> "訊息被 Doze 延後了：優先權要設 HIGH"

            lines.none {
                it.startsWith("collapse_key：") &&
                    !it.startsWith("collapse_key：（沒有）")
            } -> "要設 collapse_key，讓舊的價格提醒被新的取代"

            lines.none {
                it.contains("、ttl：") && !it.endsWith("ttl：（沒有）")
            } -> "要設 ttl：價格過了幾分鐘的提醒沒有意義，超過就丟掉"

            else -> null
        }
    },
)
