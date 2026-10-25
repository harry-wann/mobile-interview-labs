// L02：訂單狀態變成 Filled，畫面卻停在 New（structuralEqualityPolicy 與 referentialEqualityPolicy）
// 對應複習站：Kotlin11ComposeState 第 2 題「mutableStateOf 怎麼判斷『值變了』？什麼時候要換 policy？」
//
// 情境：領域模型 Order 的 equals 只比 id（Room 的 entity 和領域物件常見這樣寫）。同一筆訂單 A1 的狀態從 New 變成 Filled，
//        要把新的 Order 寫進 State。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合與重組。
//
// 你要寫：
//   ① Bug 版（L02Bug）：mutableStateOf(L02Order("A1", "New"))，用預設的比較方式（equals）。新舊 Order 的 id 一樣，這次寫入被當成「值沒變」丟掉。
//   ② 修正版（L02Fixed）：mutableStateOf(L02Order("A1", "New"), referentialEqualityPolicy())，換成另一個物件才算變了，寫入生效。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L02_EqualsById.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 領域模型：equals 只比 id，狀態不同也算「同一筆」 */
private class L02Order(val id: String, val status: String) {
    override fun equals(other: Any?) = other is L02Order && other.id == id
    override fun hashCode() = id.hashCode()
    override fun toString() = "$id：$status"
}

@Composable
private fun L02OrderStatus(order: MutableState<L02Order>, onShow: (String) -> Unit) {
    HeadlessText("訂單 ${order.value}", onShow)
}

/** 演練流程（兩個版本共用）：組合畫面 → 同一筆訂單 A1 的狀態變成 Filled，寫進 State → 看 State 裡存的和畫面顯示的 */
private suspend fun scenario(log: LabLog, order: MutableState<L02Order>) {
    var shown = ""
    var shows = 0
    withHeadlessCompose {
        setContent { L02OrderStatus(order) { shown = it; shows++ } }
        settle()
        log("更新前，畫面顯示：$shown")
        order.value = L02Order("A1", "Filled")                      // 同一筆訂單 A1，狀態變成 Filled
        settle()
        log("更新後，State 裡的訂單：${order.value}")
        log("更新後，畫面顯示：$shown")
        log("畫面共更新了 $shows 次")
    }
}

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) {
        // TODO ①：建立 State，初始值是 L02Order("A1", "New")，用預設的比較方式（不傳 policy）
        val order = mutableStateOf(L02Order("?", "（還沒寫）"))
        scenario(log, order)
    }
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun run(log: LabLog) {
        // TODO ②：建立 State，初始值同上，但傳入 referentialEqualityPolicy()：換成另一個物件才算變了
        val order = mutableStateOf(L02Order("?", "（還沒寫）"))
        scenario(log, order)
    }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l02 = Lab(
    id = "L02",
    title = "訂單狀態變成 Filled，畫面卻停在 New",
    question = "第 2 題：mutableStateOf 怎麼判斷「值變了」？什麼時候要換 policy？",
    goal = "Order 的 equals 只比 id。同一筆訂單的狀態從 New 變成 Filled，看預設的比較方式為什麼把這次寫入丟掉，再換成 referentialEqualityPolicy。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin11-compose-state/L02_EqualsById.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「State 裡的訂單」和「畫面顯示」。注意：寫進去的新值，讀回來還是舊的。",
        "寫 TODO ②（修正版），選「修正版」執行，確認 State 和畫面都變成 Filled。",
        "想一想：不換 policy，還有更好的做法嗎？（UI 用的 model 用 data class，equals 會比所有欄位；equals 只比 id 的領域物件不要直接放進 State）",
    ),
    expectBug = "「State 裡的訂單：A1：New」，「畫面顯示」也還是 A1：New，「畫面共更新了 1 次」：寫進去的新值被當成沒變而丟掉。",
    expectFixed = "「State 裡的訂單：A1：Filled」，「畫面顯示：訂單 A1：Filled」，「畫面共更新了 2 次」。",
    file = "kotlin11-compose-state/L02_EqualsById.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = line(lines, "更新前，畫面顯示：")
        val inState = line(lines, "更新後，State 裡的訂單：")
        val after = line(lines, "更新後，畫面顯示：")
        when {
            before != "訂單 A1：New" -> "畫面要先顯示「訂單 A1：New」：用 mutableStateOf(L02Order(\"A1\", \"New\")) 建立 State"
            inState != "A1：New" -> "State 裡的訂單變成「$inState」了：Bug 版用預設的比較方式（不要傳 policy），寫進去的新值才會被當成沒變而丟掉"
            after != "訂單 A1：New" -> "畫面變成「$after」了：Bug 版不傳 policy，畫面應該停在 New"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "更新前，畫面顯示：")
        val inState = line(lines, "更新後，State 裡的訂單：")
        val after = line(lines, "更新後，畫面顯示：")
        val shows = line(lines, "畫面共更新了 ")?.removeSuffix(" 次")?.toIntOrNull()
        when {
            before != "訂單 A1：New" -> "畫面要先顯示「訂單 A1：New」：用 mutableStateOf(L02Order(\"A1\", \"New\"), …) 建立 State"
            inState != "A1：Filled" -> "State 裡的訂單還是「$inState」：加上 referentialEqualityPolicy()，換成另一個物件才算變了"
            after != "訂單 A1：Filled" -> "畫面還是「$after」：寫入生效之後，畫面要重組成 Filled"
            shows != 2 -> "畫面更新了 $shows 次：第一次組合加上狀態變更後的重組，應該共 2 次"
            else -> null
        }
    },
)
