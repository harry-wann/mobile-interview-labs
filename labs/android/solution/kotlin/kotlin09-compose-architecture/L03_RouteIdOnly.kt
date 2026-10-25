// L03：從訂單列表點進詳情，看到的狀態是點擊當下的舊資料（路由只傳識別碼）
// 對應複習站：Kotlin09ComposeArchitecture 第 3 題「Navigation Compose 的 type-safe 路由怎麼寫？」
//
// 情境：訂單列表點進第 1 筆（A1001，狀態「待成交」）。點擊 100 ms 後，伺服器把這筆訂單更新成「已成交」，
//        再過 100 ms 詳情畫面才完成載入。詳情畫面應該顯示最新的狀態。
//        路由（route）用一個 data class 代表目的地，和 type-safe navigation 的寫法一樣；這個演練只看路由裡放什麼。
// Bug 版：路由帶整個 Order 物件（OrderDetail(order)），詳情畫面直接顯示路由帶來的 order。它是點擊當下的快照，狀態已經過期。
//        （真的序列化進 Navigation 的參數時，還會有大小限制。）
// 修正版：路由只帶識別碼（OrderDetail(orderId)），詳情畫面的 ViewModel 再向 repository 取最新的訂單。
package tw.harry.labs.solution.kotlin09composearchitecture

import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private data class L03Order(val id: String, val status: String)

/** 假的 repository：伺服器上最新的訂單 */
private class L03OrderRepository {
    private val orders = mutableMapOf("A1001" to L03Order("A1001", "待成交"))

    fun all(): List<L03Order> = orders.values.toList()
    fun find(id: String): L03Order = orders.getValue(id)
    fun update(id: String, status: String) {
        orders[id] = L03Order(id, status)
    }
}

/**
 * 演練流程（兩個版本共用）：點擊訂單 → [navigate] 建立路由 → 導航期間伺服器更新了訂單 → [showDetail] 回傳詳情畫面顯示的狀態
 */
private suspend fun <Route> scenario(
    log: LabLog,
    navigate: (L03Order) -> Route,
    showDetail: (Route, L03OrderRepository) -> String,
) {
    val repo = L03OrderRepository()
    val tapped = repo.all().first()
    log("列表上的訂單 ${tapped.id}：${tapped.status}")
    val route = navigate(tapped)                            // 點擊：導航，路由裡放什麼由版本決定
    delay(100)
    repo.update(tapped.id, "已成交")                         // 導航期間，伺服器更新了這筆訂單
    delay(100)
    log("詳情畫面顯示：訂單 ${tapped.id}：${showDetail(route, repo)}")
    log("伺服器上的最新狀態：${repo.find(tapped.id).status}")
}

// MARK: - Bug 版
private object L03Bug {
    /** 路由：帶整個 Order */
    data class OrderDetail(val order: L03Order)

    fun navigate(order: L03Order) = OrderDetail(order)      // navigate(OrderDetail(order))

    fun showDetail(route: OrderDetail): String = route.order.status    // 直接用路由帶來的物件：點擊當下的快照

    suspend fun run(log: LabLog) = scenario(log, ::navigate) { route, _ -> showDetail(route) }
}

// MARK: - 修正版
private object L03Fixed {
    /** 路由：只帶識別碼 */
    data class OrderDetail(val orderId: String)

    fun navigate(order: L03Order) = OrderDetail(order.id)   // navigate(OrderDetail(order.id))

    fun showDetail(route: OrderDetail, repo: L03OrderRepository): String =
        repo.find(route.orderId).status                     // 詳情畫面向 repository 取最新的資料

    suspend fun run(log: LabLog) = scenario(log, ::navigate, ::showDetail)
}

// MARK: - 演練設定
private fun shown(lines: List<String>): String? =
    lines.firstOrNull { it.startsWith("詳情畫面顯示：訂單 A1001：") }?.removePrefix("詳情畫面顯示：訂單 A1001：")

val l03 = Lab(
    id = "L03",
    title = "點進詳情，看到的是舊的訂單狀態",
    question = "第 3 題：Navigation Compose 的 type-safe 路由怎麼寫？",
    goal = "點進訂單詳情的期間，伺服器把訂單更新成「已成交」。看路由帶整個物件時，詳情畫面為什麼顯示過期的狀態，再改成只傳識別碼。",
    steps = listOf(
        "選「Bug 版」執行，比較「詳情畫面顯示」和「伺服器上的最新狀態」。",
        "選「修正版」執行，確認詳情畫面顯示的是最新狀態。",
        "想一想：type-safe 路由除了編譯時檢查參數，還有什麼好處？（用 @Serializable 的型別宣告目的地，composable<OrderDetail> 搭配 toRoute()，不用手寫字串路由與解析參數）",
    ),
    expectBug = "「詳情畫面顯示：訂單 A1001：待成交」，但「伺服器上的最新狀態：已成交」：顯示的是點擊當下的快照。",
    expectFixed = "「詳情畫面顯示：訂單 A1001：已成交」，和伺服器一致。",
    file = "kotlin09-compose-architecture/L03_RouteIdOnly.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (shown(lines)) {
            "待成交" -> null
            "已成交" -> "詳情畫面顯示的是最新狀態：Bug 版的路由要帶整個 Order，showDetail 直接回傳 route.order.status"
            else -> "詳情畫面沒有顯示訂單狀態：showDetail 要回傳 route.order.status"
        }
    },
    checkFixed = { lines ->
        when (shown(lines)) {
            "已成交" -> null
            "待成交" -> "詳情畫面顯示的是舊的狀態：路由只帶 orderId，showDetail 向 repo.find(route.orderId) 取最新的訂單"
            else -> "詳情畫面沒有顯示訂單狀態：showDetail 要回傳 repo.find(route.orderId).status"
        }
    },
)
