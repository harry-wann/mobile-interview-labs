// L04：使用者只按了一次「買入」，伺服器卻有三張訂單（自動重試時，每次都產生新的 client order id）
// 對應複習站：Exchange08OrderFlow 第 3 題「client order id 是什麼？為什麼它能讓重送不會變成兩筆？」
//
// 情境：使用者按一次「買入」。網路層最多自動重試 3 次：第 1、2 次伺服器其實收到了、也記下了這張單，但回應在路上遺失（逾時）；第 3 次才收到回應。
//        假伺服器的規則（和這套 API 的 `client-order-id` 一樣）：同一個編號只會記一張單；沒有帶編號（空字串）的請求會被拒絕。
//        （逾時之後先用查詢確認再決定重不重送，Platform05 演練 5 練過；這個演練看的是：自動重試時，編號什麼時候產生。）
//
// 你要寫：
//   ① Bug 版（L04Bug.Sender.clientOrderId）：每一次嘗試都產生新的編號（UUID.randomUUID().toString()）。
//   ② 修正版（L04Fixed.Sender）：tapBuy 時產生編號存進 draftId；clientOrderId(attempt) 每次都回傳 draftId。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange08-order-flow/L04_ClientOrderId.kt
package tw.harry.labs.practice.exchange08orderflow

import java.util.UUID
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假伺服器：同一個編號只記一張單，沒有編號（空字串）的請求拒絕 */
private class L04FakeServer {
    private val ids = HashSet<String>()
    var rejected = 0
        private set

    fun place(clientOrderId: String) {
        if (clientOrderId.isEmpty()) rejected++ else ids.add(clientOrderId)
    }

    val orderCount: Int get() = ids.size
}

private interface L04OrderSender {
    /** 使用者按下「買入」（只按一次） */
    fun tapBuy()

    /** 第 attempt 次網路嘗試要帶的 client order id */
    fun clientOrderId(attempt: Int): String
}

/** 演練流程（兩個版本共用）：按一次買入，網路層自動重試最多 3 次 */
private fun scenario(log: LabLog, sender: L04OrderSender) {
    val server = L04FakeServer()
    sender.tapBuy()
    log("使用者按下「買入」（只按了一次）")
    val labels = LinkedHashMap<String, String>()
    for (attempt in 1..3) {
        val id = sender.clientOrderId(attempt)
        server.place(id)
        val label = if (id.isEmpty()) "（沒有編號）" else labels.getOrPut(id) { "編號 ${listOf("A", "B", "C")[labels.size]}" }
        log("第 $attempt 次嘗試：帶$label → ${if (attempt < 3) "伺服器收到並記下了，但回應逾時" else "收到回應"}")
    }
    log("最後：伺服器上有 ${server.orderCount} 張訂單（使用者只按了一次），3 次嘗試用了 ${labels.size} 種不同的編號，沒帶編號而被拒絕 ${server.rejected} 次")
}

// MARK: - Bug 版
private object L04Bug {
    class Sender : L04OrderSender {
        override fun tapBuy() {}

        override fun clientOrderId(attempt: Int): String {
            // TODO ①：每一次嘗試都產生新的編號：回傳 UUID.randomUUID().toString()。目前回傳空字串（沒有編號）只是佔位
            return ""
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Sender())
}

// MARK: - 修正版
private object L04Fixed {
    class Sender : L04OrderSender {
        private var draftId = ""

        override fun tapBuy() {
            // TODO ②-1：按下按鈕的那一刻就產生編號，存進 draftId（UUID.randomUUID().toString()）
        }

        override fun clientOrderId(attempt: Int): String {
            // TODO ②-2：每一次重試都回傳同一個編號 draftId。目前回傳空字串只是佔位
            return ""
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Sender())
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "只按一次買入，伺服器有三張訂單",
    question = "第 3 題：client order id 是什麼？為什麼它能讓重送不會變成兩筆？",
    goal = "client order id 是冪等鍵：在使用者按下按鈕、送出請求之前就產生，隨草稿保存，同一次意圖的所有重送都用同一個；不是每次網路重試就換。先寫出每次重試都換編號的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange08-order-flow/L04_ClientOrderId.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看三次嘗試各帶什麼編號，伺服器最後有幾張訂單。",
        "想一想：伺服器靠什麼認出「這是同一次下單」？每次重試都換一個新的，伺服器還認得出來嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，三次嘗試要帶同一個編號，伺服器只有 1 張訂單。",
        "進階：這套 API 的 `client-order-id` 最長 64 個字元，8 小時內必須唯一。UUID 去掉 `-` 之後是 32 個字元，夠用。",
    ),
    expectBug = "三次嘗試分別帶編號 A、B、C，「最後：伺服器上有 3 張訂單（使用者只按了一次），3 次嘗試用了 3 種不同的編號」。",
    expectFixed = "三次嘗試都帶編號 A，「最後：伺服器上有 1 張訂單（使用者只按了一次），3 次嘗試用了 1 種不同的編號」。",
    file = "exchange08-order-flow/L04_ClientOrderId.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, sender) 執行"
            lines.none { it.startsWith("最後：伺服器上有 3 張訂單（使用者只按了一次），3 次嘗試用了 3 種不同的編號") } ->
                "Bug 還沒重現：clientOrderId(attempt) 每次都回傳新的 UUID.randomUUID().toString()，伺服器會記成三張單"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, sender) 執行"
            lines.none { it.startsWith("最後：伺服器上有 1 張訂單（使用者只按了一次），3 次嘗試用了 1 種不同的編號") } ->
                "tapBuy 時產生編號存進 draftId（UUID.randomUUID().toString()），clientOrderId(attempt) 每次都回傳 draftId"
            else -> null
        }
    },
)
