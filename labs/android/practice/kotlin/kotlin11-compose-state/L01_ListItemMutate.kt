// L01：成交了一部分，委託列表那一列卻沒變（mutableStateListOf 裡的物件被原地修改）
// 對應複習站：Kotlin11ComposeState 第 1 題「mutableStateOf 與它的家族該怎麼選？」
//
// 情境：委託列表放在 mutableStateListOf 裡，畫面顯示每一筆委託已成交多少。收到「ETH_JPY 成交了 0.5」的回報後，要更新那一列。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合與重組。
//
// 你要寫：
//   ① Bug 版（L01Bug）：收到回報就直接 orders[1].filled = 0.5，原地修改物件的欄位。清單的內容沒有變，Compose 不知道。
//   ② 修正版（L01Fixed）：換掉清單的那一格：orders[1] = orders[1].copy(filled = 0.5)，清單的內容變了，Compose 就會重組。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L01_ListItemMutate.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 演練流程（兩個版本共用）：組合畫面 → 收到回報（ETH_JPY 成交 0.5）→ 看清單裡的資料和畫面顯示的是不是一樣。
 * [screen] 是畫面，[fillEth] 是收到回報要做的事，[inList] 回傳清單裡現在真正的內容。
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (onShow: (String) -> Unit) -> Unit,
    fillEth: () -> Unit,
    inList: () -> String,
) {
    var shown = ""
    var shows = 0
    withHeadlessCompose {
        setContent { screen { shown = it; shows++ } }
        settle()
        log("收到回報前，畫面顯示：$shown")
        fillEth()
        settle()
        log("收到回報後，清單裡的資料：${inList()}")
        log("收到回報後，畫面顯示：$shown")
        log("畫面共更新了 $shows 次")
    }
}

// MARK: - Bug 版
private object L01Bug {
    class OrderRow(val symbol: String, var filled: Double)          // filled 是 var：可以直接改

    @Composable
    fun OrderList(orders: List<OrderRow>, onShow: (String) -> Unit) {
        HeadlessText(orders.joinToString("、") { "${it.symbol} 已成交 ${it.filled}" }, onShow)
    }

    suspend fun run(log: LabLog) {
        val orders = mutableStateListOf(OrderRow("BTC_JPY", 0.0), OrderRow("ETH_JPY", 0.0))
        scenario(
            log,
            screen = { onShow -> OrderList(orders, onShow) },
            // TODO ①：收到回報，直接改 ETH_JPY 那一筆的欄位：orders[1].filled = 0.5（原地修改，不要換掉清單的那一格）
            fillEth = { },
            inList = { orders.joinToString("、") { "${it.symbol} 已成交 ${it.filled}" } },
        )
    }
}

// MARK: - 修正版
private object L01Fixed {
    data class OrderRow(val symbol: String, val filled: Double)     // 欄位都是 val：要改就換一個新的

    @Composable
    fun OrderList(orders: List<OrderRow>, onShow: (String) -> Unit) {
        HeadlessText(orders.joinToString("、") { "${it.symbol} 已成交 ${it.filled}" }, onShow)
    }

    suspend fun run(log: LabLog) {
        val orders = mutableStateListOf(OrderRow("BTC_JPY", 0.0), OrderRow("ETH_JPY", 0.0))
        scenario(
            log,
            screen = { onShow -> OrderList(orders, onShow) },
            // TODO ②：收到回報，換掉清單的那一格：orders[1] = orders[1].copy(filled = 0.5)
            fillEth = { },
            inList = { orders.joinToString("、") { "${it.symbol} 已成交 ${it.filled}" } },
        )
    }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l01 = Lab(
    id = "L01",
    title = "成交了一部分，委託那一列卻沒變",
    question = "第 1 題：mutableStateOf 與它的家族該怎麼選？",
    goal = "收到「ETH_JPY 成交 0.5」的回報後更新委託列表。看原地修改 mutableStateListOf 裡的物件為什麼不會讓畫面更新，再改成換掉清單的那一格。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin11-compose-state/L01_ListItemMutate.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「清單裡的資料」和「畫面顯示」，再看「畫面共更新了 N 次」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認畫面跟著更新，次數多了 1 次。",
        "想一想：如果清單不是 mutableStateListOf，而是 mutableStateOf(listOf(…))，收到回報時該怎麼更新？（每次換成新的 list，例如 orders = orders.map { … }；Kotlin06 演練 2 也練過同一個觀念）",
    ),
    expectBug = "「清單裡的資料」是 ETH_JPY 已成交 0.5，「畫面顯示」卻還是 0.0，「畫面共更新了 1 次」。",
    expectFixed = "「畫面顯示」跟清單一樣是 ETH_JPY 已成交 0.5，「畫面共更新了 2 次」。",
    file = "kotlin11-compose-state/L01_ListItemMutate.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = line(lines, "收到回報前，畫面顯示：")
        val inList = line(lines, "收到回報後，清單裡的資料：")
        val after = line(lines, "收到回報後，畫面顯示：")
        val shows = line(lines, "畫面共更新了 ")?.removeSuffix(" 次")?.toIntOrNull()
        when {
            before?.contains("ETH_JPY 已成交 0.0") != true -> "畫面還沒顯示委託：OrderList 要用 HeadlessText 顯示每一筆的 symbol 和 filled"
            inList?.contains("ETH_JPY 已成交 0.5") != true -> "清單裡的資料沒有更新：收到回報時要直接改 orders[1].filled = 0.5"
            after?.contains("ETH_JPY 已成交 0.5") == true -> "畫面已經更新了：Bug 版要原地修改 orders[1].filled，不要換掉清單的那一格"
            shows != 1 -> "畫面更新了 $shows 次：Bug 版原地修改欄位，畫面不會重組，應該只有第一次組合的 1 次"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "收到回報前，畫面顯示：")
        val after = line(lines, "收到回報後，畫面顯示：")
        val shows = line(lines, "畫面共更新了 ")?.removeSuffix(" 次")?.toIntOrNull()
        when {
            before?.contains("ETH_JPY 已成交 0.0") != true -> "畫面還沒顯示委託：OrderList 要用 HeadlessText 顯示每一筆的 symbol 和 filled"
            after?.contains("ETH_JPY 已成交 0.5") != true -> "畫面沒有跟著更新：要換掉清單的那一格，orders[1] = orders[1].copy(filled = 0.5)"
            shows != 2 -> "畫面更新了 $shows 次：換掉清單的那一格之後應該重組 1 次，加上第一次組合共 2 次"
            else -> null
        }
    },
)
