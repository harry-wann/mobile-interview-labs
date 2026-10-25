// L06：回到前景又送了一次，同一筆委託下了兩張單（用 clientOrderId 查詢，
// 不要重送）
// 對應複習站：Platform01Lifecycle 第 6 題「交易所 App 在進背景、回前景時，
// 要做哪些事？」
//
// 情境：使用者按下買入，請求送出去的瞬間 App 被切到背景，回應沒有收到。App
// 不知道伺服器有沒有收到這筆單。
//        回到前景時要處理這筆「送出狀態不明」的委託。演練跑兩種情況：
// 伺服器其實已經收到了、伺服器根本沒收到。
//
// 你要寫：
//   ① Bug 版（L06Bug）：回前景時發現上次送出沒有等到結果，就再送一次（新的
// clientOrderId）。
//   ② 修正版（L06Fixed）：先用 clientOrderId 向伺服器查詢；查不到才用同一個
// clientOrderId 重送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform01-lifecycle/L06_ResendOrder.kt
package tw.harry.labs.practice.platform01lifecycle

import java.util.UUID
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 假的交易所（兩個版本共用）：用 clientOrderId 辨識委託；同一個 clientOrderId
 * 送第二次會被拒絕
 */
private class L06Exchange {
    val orders = mutableListOf<String>()

    fun place(clientOrderId: String): Boolean {
        // 重複的 clientOrderId：拒絕
        if (clientOrderId in orders) return false
        orders += clientOrderId
        return true
    }

    fun query(clientOrderId: String): Boolean = clientOrderId in orders
}

/** 下單頁的狀態（兩個版本共用）：記得最後一張「送出狀態不明」的委託 */
private class L06OrderScreen(val exchange: L06Exchange) {
    var pendingOrderId: String? = null
        private set

    /**
     * 按下買入。requestReachesServer：
     * 請求有沒有真的送到伺服器（演練用來模擬進背景時的網路狀況）
     */
    fun tapBuy(requestReachesServer: Boolean) {
        val id = UUID.randomUUID().toString()
        // 先記下來：回應還沒收到
        pendingOrderId = id
        if (requestReachesServer) exchange.place(id)
        // 回應沒有收到（App 進了背景）：pendingOrderId 維持不變
    }

    /** 送一張新的委託（每次都產生新的 clientOrderId） */
    fun placeOrder() {
        val id = UUID.randomUUID().toString()
        pendingOrderId = id
        exchange.place(id)
    }

    fun confirm() {
        pendingOrderId = null
    }
}

/** 演練流程（兩個版本共用）：兩種情況，各自看伺服器最後有幾張單 */
private fun scenario(log: LabLog, onForeground: (L06OrderScreen) -> Unit) {
    val received = L06Exchange()
    val screenA = L06OrderScreen(received)
    // 伺服器收到了，但 App 沒等到回應就進背景
    screenA.tapBuy(requestReachesServer = true)
    onForeground(screenA) // 回到前景
    log("伺服器已經收到的情況：回前景後伺服器共有 ${received.orders.size} 張單")

    val lost = L06Exchange()
    val screenB = L06OrderScreen(lost)
    // 請求根本沒送到伺服器
    screenB.tapBuy(requestReachesServer = false)
    onForeground(screenB)
    log("請求沒有送到的情況：回前景後伺服器共有 ${lost.orders.size} 張單")
}

// MARK: - Bug 版
private object L06Bug {
    fun onForeground(screen: L06OrderScreen) {
        // TODO ①：如果 screen.pendingOrderId 不是
        // null（上次送出沒有等到結果），就再呼叫 screen.placeOrder() 送一次
    }

    fun run(log: LabLog) = scenario(log, ::onForeground)
}

// MARK: - 修正版
private object L06Fixed {
    fun onForeground(screen: L06OrderScreen) {
        val id = screen.pendingOrderId ?: return
        // TODO ②：先用 screen.exchange.query(id) 查。查得到就只要 screen.
        // confirm()；
        //        查不到才用同一個 id 呼叫 screen.exchange.place(id) 重送，再
        // screen.confirm()
    }

    fun run(log: LabLog) = scenario(log, ::onForeground)
}

// MARK: - 演練設定
private fun orderCount(lines: List<String>, prefix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.removeSuffix(" 張單")?.toIntOrNull()

val l06 = Lab(
    id = "L06",
    title = "回到前景又送一次，下了兩張單",
    question = "第 6 題：交易所 App 在進背景、回前景時，要做哪些事？",
    goal = "送出狀態不明的委託，回到前景時要先用 clientOrderId 查詢：伺服器已經有就不重送，沒有才用同一個 clientOrderId 補送。兩種情況最後都只能有 1 張單。先寫出重複下單的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/platform/platform01-lifecycle/L06_ResendOrder.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」按執行，看「伺服器已經收到的情況」共有幾張單。",
        "寫 TODO ②（修正版），選「修正版」執行：兩種情況都是 1 張單。",
        "想一想：為什麼重送時要沿用同一個 clientOrderId？（即使查詢和重送之間又發生什麼事，伺服器也能用它擋掉重複的單）",
    ),
    expectBug = "伺服器已經收到的情況：回前景後共有 2 張單（重複下單）；請求沒有送到的情況是 1 張。",
    expectFixed = "兩種情況，伺服器都只有 1 張單。",
    file = "platform01-lifecycle/L06_ResendOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val a = orderCount(lines, "伺服器已經收到的情況：回前景後伺服器共有 ")
        when {
            a == null -> "演練沒有跑完"
            a < 2 -> "Bug 還沒重現：回前景時發現 pendingOrderId 不是 null，就再呼叫 placeOrder() 重送"
            else -> null
        }
    },
    checkFixed = { lines ->
        val a = orderCount(lines, "伺服器已經收到的情況：回前景後伺服器共有 ")
        val b = orderCount(lines, "請求沒有送到的情況：回前景後伺服器共有 ")
        when {
            a == null || b == null -> "演練沒有跑完"
            a != 1 -> "伺服器已經收到的情況，結果有 $a 張單：先用 exchange.query(id) 查，查得到就不要送"
            b != 1 -> "請求沒有送到的情況，結果有 $b 張單：查不到時，要用同一個 clientOrderId 重送一次"
            else -> null
        }
    },
)
