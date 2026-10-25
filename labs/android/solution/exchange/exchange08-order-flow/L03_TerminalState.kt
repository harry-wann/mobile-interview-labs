// L03：訂單明明取消了，畫面卻又變回「委託中」，後來還變成「部分成交」（終態被晚到的事件覆蓋）
// 對應複習站：Exchange08OrderFlow 第 2 題「訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？」
//
// 情境：委託量 2.0 BTC，本地狀態從 pending（本地已送出，還沒收到 ack）開始。私有推送不保證順序，事件依序到達：
//        accepted（受理）→ fill 累計 0.5 → canceled（取消，已有成交量，所以是 partialCanceled）→ accepted（晚到的重複事件）→ fill 累計 0.3（過期）→ fill 累計 1.2。
//        正確的最終狀態：partialCanceled，成交量 1.2：取消的通知比最後一筆成交先到，成交量照常更新，但狀態不會退回 partialFilled；晚到的 accepted 和過期的 fill 都忽略（2 個）。
//        （成交量只增不減的規則，已經寫好。）
// Bug 版：accepted 一律把狀態改成 submitted；fill 之後的狀態一律是 partialFilled（沒有滿量的話）。終態被覆蓋：取消之後又變回 submitted，最後變成 partialFilled。
// 修正版：accepted 只有 pending 才變成 submitted；fill 之後，canceling 維持 canceling，canceled 或 partialCanceled 維持 partialCanceled，其他才是 partialFilled。
package tw.harry.labs.solution.exchange08orderflow

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L03Status(val label: String) {
    PENDING("pending"),
    SUBMITTED("submitted"),
    PARTIAL_FILLED("partialFilled"),
    FILLED("filled"),
    CANCELING("canceling"),
    CANCELED("canceled"),
    PARTIAL_CANCELED("partialCanceled"),
}

private data class L03Order(val status: L03Status, val filled: BigDecimal, val amount: BigDecimal)

private sealed interface L03Event {
    data object Accepted : L03Event
    data class Fill(val total: BigDecimal) : L03Event
    data object Canceled : L03Event
}

private interface L03Reducer {
    fun reduce(order: L03Order, event: L03Event): L03Order
}

private fun describe(event: L03Event) = when (event) {
    L03Event.Accepted -> "accepted"
    is L03Event.Fill -> "fill(累計 ${e08Text(event.total)})"
    L03Event.Canceled -> "canceled"
}

/** 演練流程（兩個版本共用）：六個事件依序到達 */
private fun scenario(log: LabLog, reducer: L03Reducer) {
    var order = L03Order(L03Status.PENDING, e08("0"), e08("2.0"))
    var ignored = 0
    val events = listOf(L03Event.Accepted, L03Event.Fill(e08("0.5")), L03Event.Canceled, L03Event.Accepted, L03Event.Fill(e08("0.3")), L03Event.Fill(e08("1.2")))
    for (event in events) {
        val next = reducer.reduce(order, event)
        val isIgnored = next.status == order.status && next.filled.compareTo(order.filled) == 0
        if (isIgnored) ignored++
        order = next
        log("${describe(event)} → ${order.status.label} ${e08Text(order.filled)}${if (isIgnored) "（忽略）" else ""}")
    }
    log("最後：${order.status.label} ${e08Text(order.filled)}，忽略了 $ignored 個事件（正確答案：partialCanceled 1.2，忽略 2 個）")
}

// MARK: - Bug 版
private object L03Bug {
    class Merge : L03Reducer {
        override fun reduce(order: L03Order, event: L03Event): L03Order = when (event) {
            L03Event.Accepted -> order.copy(status = L03Status.SUBMITTED)               // 不管現在是什麼狀態，一律變成 submitted
            is L03Event.Fill -> {
                if (event.total <= order.filled || event.total > order.amount) order
                else order.copy(
                    filled = event.total,
                    status = if (event.total.compareTo(order.amount) == 0) L03Status.FILLED else L03Status.PARTIAL_FILLED,   // 一律變成 partialFilled
                )
            }
            L03Event.Canceled -> when (order.status) {
                L03Status.PENDING, L03Status.SUBMITTED, L03Status.PARTIAL_FILLED, L03Status.CANCELING ->
                    order.copy(status = if (order.filled.signum() > 0) L03Status.PARTIAL_CANCELED else L03Status.CANCELED)
                else -> order
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Merge())
}

// MARK: - 修正版
private object L03Fixed {
    class Merge : L03Reducer {
        override fun reduce(order: L03Order, event: L03Event): L03Order = when (event) {
            L03Event.Accepted -> if (order.status == L03Status.PENDING) order.copy(status = L03Status.SUBMITTED) else order    // 只有 pending 才變成 submitted
            is L03Event.Fill -> {
                if (event.total <= order.filled || event.total > order.amount) {
                    order
                } else {
                    val status = when {
                        event.total.compareTo(order.amount) == 0 -> L03Status.FILLED
                        order.status == L03Status.CANCELING -> L03Status.CANCELING      // 取消中或已取消的訂單，成交量更新了，狀態不能退回 partialFilled
                        order.status == L03Status.CANCELED || order.status == L03Status.PARTIAL_CANCELED -> L03Status.PARTIAL_CANCELED
                        else -> L03Status.PARTIAL_FILLED
                    }
                    order.copy(filled = event.total, status = status)
                }
            }
            L03Event.Canceled -> when (order.status) {
                L03Status.PENDING, L03Status.SUBMITTED, L03Status.PARTIAL_FILLED, L03Status.CANCELING ->
                    order.copy(status = if (order.filled.signum() > 0) L03Status.PARTIAL_CANCELED else L03Status.CANCELED)
                else -> order
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Merge())
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "訂單取消了，又變回委託中",
    question = "第 2 題：訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？",
    goal = "終態不可離開：已經 canceled／partialCanceled／filled 的訂單，晚到的 accepted、過期的事件都不能把狀態改回去；成交量照常更新，但狀態要維持取消的終態。",
    steps = listOf(
        "選「Bug 版」執行，看每個事件之後的狀態，哪一步開始，取消的訂單又復活了？",
        "想一想：canceled 之後，晚到的 accepted 代表什麼？最後的 fill 累計 1.2 是取消之前就成交的，還是取消之後才成交的？",
        "選「修正版」執行，最後要是 partialCanceled 1.2，忽略了 2 個事件。",
        "進階：文件警告 `trade.clearing` 不保證順序，所以 reducer 要能抵抗亂序：成交先於 ack、取消先於最後一筆成交，都要能收斂到正確的最終狀態。",
    ),
    expectBug = "canceled 之後，晚到的 accepted 把狀態改回 submitted，最後的 fill 變成 partialFilled：「最後：partialFilled 1.2，忽略了 1 個事件」。",
    expectFixed = "晚到的 accepted 與過期的 fill 0.3 顯示「（忽略）」，fill 1.2 更新成交量但狀態維持 partialCanceled：「最後：partialCanceled 1.2，忽略了 2 個事件」。",
    file = "exchange08-order-flow/L03_TerminalState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, reducer) 執行"
            lines.none { it.startsWith("最後：partialFilled 1.2，忽略了 1 個事件") } ->
                "Bug 還沒重現：Accepted 一律把 status 改成 SUBMITTED；Fill（沒滿量）之後一律是 PARTIAL_FILLED"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, reducer) 執行"
            lines.none { it.startsWith("最後：partialCanceled 1.2，忽略了 2 個事件") } ->
                "Accepted 只有 PENDING 才變 SUBMITTED（其他狀態忽略）；Fill 更新成交量後，CANCELING 維持 CANCELING，CANCELED 或 PARTIAL_CANCELED 維持 PARTIAL_CANCELED，其他才是 PARTIAL_FILLED"
            else -> null
        }
    },
)
