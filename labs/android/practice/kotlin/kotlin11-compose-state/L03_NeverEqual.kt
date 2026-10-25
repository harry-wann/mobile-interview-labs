// L03：訂單簿明明更新了，最佳買價卻不動（neverEqualPolicy）
// 對應複習站：Kotlin11ComposeState 第 4 題「mutableStateOf 怎麼判斷『值變了』？什麼時候要換 policy？」
//
// 情境：訂單簿引擎 OrderBook 為了效率，內部用 TreeMap 就地更新，整個 App 共用同一個物件，不會每次複製一份。
//        收到深度更新（價位 101 有 1.5 BTC 的買單）後，畫面要顯示最新的最佳買價。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合與重組。
//
// 你要寫：
//   ① Bug 版（L03Bug）：mutableStateOf(book)，預設的比較方式。更新引擎後再寫一次 state.value = book，同一個物件被當成「沒變」，畫面不重組。
//   ② 修正版（L03Fixed）：mutableStateOf(book, neverEqualPolicy())，任何一次寫入都算變了，同一個物件寫進去也會通知畫面。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L03_NeverEqual.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import java.util.TreeMap
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 訂單簿引擎：買單的「價位 → 數量」，就地更新，從不複製 */
private class L03OrderBook {
    private val bids = TreeMap<Long, Double>()

    fun update(price: Long, qty: Double) {
        if (qty == 0.0) bids.remove(price) else bids[price] = qty
    }

    val bestBid: Long? get() = if (bids.isEmpty()) null else bids.lastKey()
}

@Composable
private fun L03BestBid(state: MutableState<L03OrderBook>, onShow: (String) -> Unit) {
    HeadlessText("最佳買價 ${state.value.bestBid}", onShow)
}

/** 演練流程（兩個版本共用）：組合畫面（最佳買價 100）→ 收到深度更新（價位 101）→ 更新引擎，再寫一次 state.value */
private suspend fun scenario(log: LabLog, book: L03OrderBook, state: MutableState<L03OrderBook>) {
    var shown = ""
    var shows = 0
    withHeadlessCompose {
        setContent { L03BestBid(state) { shown = it; shows++ } }
        settle()
        log("更新前，畫面顯示：$shown")
        book.update(101, 1.5)                                       // 就地更新引擎
        state.value = book                                          // 以為這樣會通知畫面
        settle()
        log("更新後，訂單簿的最佳買價：${book.bestBid}")
        log("更新後，畫面顯示：$shown")
        log("畫面共更新了 $shows 次")
    }
}

// MARK: - Bug 版
private object L03Bug {
    suspend fun run(log: LabLog) {
        val book = L03OrderBook().apply { update(100, 2.0); update(99, 4.0) }
        // TODO ①：建立 State，裡面放 book，用預設的比較方式（不傳 policy）
        val state = mutableStateOf(L03OrderBook())
        scenario(log, book, state)
    }
}

// MARK: - 修正版
private object L03Fixed {
    suspend fun run(log: LabLog) {
        val book = L03OrderBook().apply { update(100, 2.0); update(99, 4.0) }
        // TODO ②：建立 State，裡面放 book，傳入 neverEqualPolicy()：每次寫入都算變了
        val state = mutableStateOf(L03OrderBook())
        scenario(log, book, state)
    }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l03 = Lab(
    id = "L03",
    title = "訂單簿更新了，最佳買價卻不動",
    question = "第 4 題：mutableStateOf 怎麼判斷「值變了」？什麼時候要換 policy？",
    goal = "訂單簿引擎就地更新、整個 App 共用同一個物件。看再寫一次同一個物件為什麼不會通知畫面，再換成 neverEqualPolicy。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin11-compose-state/L03_NeverEqual.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「訂單簿的最佳買價」和「畫面顯示」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認畫面跟著更新成 101，次數多了 1 次。",
        "想一想：neverEqualPolicy 有什麼代價？（每次寫入都會重組，連內容完全沒變的寫入也一樣；只適合「物件就地更新、更新之後明確通知」的情境）",
    ),
    expectBug = "「訂單簿的最佳買價：101」，「畫面顯示」卻還是最佳買價 100，「畫面共更新了 1 次」。",
    expectFixed = "「畫面顯示：最佳買價 101」，「畫面共更新了 2 次」。",
    file = "kotlin11-compose-state/L03_NeverEqual.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = line(lines, "更新前，畫面顯示：")
        val inBook = line(lines, "更新後，訂單簿的最佳買價：")
        val after = line(lines, "更新後，畫面顯示：")
        when {
            before != "最佳買價 100" -> "畫面要先顯示「最佳買價 100」：用 mutableStateOf(book) 建立 State，畫面讀 state.value.bestBid"
            inBook != "101" -> "訂單簿沒有更新到 101：scenario 會呼叫 book.update(101, 1.5)，State 要放同一個 book"
            after != "最佳買價 100" -> "畫面變成「$after」了：Bug 版用預設的比較方式（不要傳 policy），同一個物件寫進去會被當成沒變"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "更新前，畫面顯示：")
        val after = line(lines, "更新後，畫面顯示：")
        val shows = line(lines, "畫面共更新了 ")?.removeSuffix(" 次")?.toIntOrNull()
        when {
            before != "最佳買價 100" -> "畫面要先顯示「最佳買價 100」：用 mutableStateOf(book, …) 建立 State，畫面讀 state.value.bestBid"
            after != "最佳買價 101" -> "畫面還是「$after」：加上 neverEqualPolicy()，同一個物件寫進去也要通知畫面"
            shows != 2 -> "畫面更新了 $shows 次：第一次組合加上寫入之後的重組，應該共 2 次"
            else -> null
        }
    },
)
