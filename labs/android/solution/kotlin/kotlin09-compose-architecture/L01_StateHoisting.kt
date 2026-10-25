// L01：下單成功清空數量後，輸入欄卻還留著舊數量（state hoisting：只留一份狀態）
// 對應複習站：Kotlin09ComposeArchitecture 第 1 題「state hoisting
// 是什麼？狀態應該放在哪一層？」
//
// 情境：下單畫面有輸入欄（OrderForm）和預估金額（Summary），
// 兩邊都要用同一個數量 qty。使用者打字輸入 0.5，
//        下單成功後，父層（OrderScreen）把數量清成空字串。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合與重組。
// Bug 版：OrderForm 把傳進來的 qty 複製進自己的 remember { mutableStateOf(qty)
// }，使用者打字時改自己那一份，再通知父層。
//        現在有兩份 qty：父層清空的是它自己那一份，OrderForm
// 裡的那份沒有跟著清，兩邊對不起來。
// 修正版：狀態提升（state hoisting）。qty 只放在 OrderScreen 一份，OrderForm
// 變成 stateless：
//        值（qty）往下傳，事件（onQtyChange）往上傳，自己不保存任何東西。
package tw.harry.labs.solution.kotlin09composearchitecture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 演練用的操作：模擬使用者在輸入欄打字、
 * 下單成功後父層清空數量（由畫面在組合時交出來）
 */
private class L01Actions {
    var type: (String) -> Unit = {}
    var clear: () -> Unit = {}
}

/** 預估金額（兩個版本共用）：顯示父層傳進來的 qty */
@Composable
private fun L01Summary(qty: String, onShow: (String) -> Unit) {
    HeadlessText(qty, onShow)
}

/**
 * 演練流程（兩個版本共用）：打字輸入 0.5 → 下單成功，父層清空數量，
 * 看輸入欄與預估金額欄各是什麼
 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (
        actions: L01Actions,
        onForm: (String) -> Unit,
        onSummary: (String) -> Unit,
    ) -> Unit,
) {
    val actions = L01Actions()
    var form = ""
    var summary = ""
    withHeadlessCompose {
        setContent { screen(actions, { form = it }, { summary = it }) }
        settle()
        actions.type("0.5")
        settle()
        log("輸入 0.5 之後：輸入欄「$form」，預估金額欄的數量「$summary」")
        actions.clear()
        settle()
        log("下單成功、清空數量之後：輸入欄「$form」，預估金額欄的數量「$summary」")
    }
}

// MARK: - Bug 版
private object L01Bug {
    @Composable
    fun OrderScreen(
        actions: L01Actions,
        onForm: (String) -> Unit,
        onSummary: (String) -> Unit,
    ) {
        // 父層的一份（預估金額用）
        var qty by remember { mutableStateOf("") }
        // 演練用：下單成功後父層清空數量
        SideEffect { actions.clear = { qty = "" } }
        OrderForm(qty, { qty = it }, actions, onForm)
        L01Summary(qty, onSummary)
    }

    @Composable
    fun OrderForm(
        qty: String,
        onQtyChange: (String) -> Unit,
        actions: L01Actions,
        onShow: (String) -> Unit,
    ) {
        // 把參數複製進自己的狀態：第二份
        var text by remember { mutableStateOf(qty) }
        // 演練用：模擬使用者打字
        SideEffect { actions.type = { text = it; onQtyChange(it) } }
        HeadlessText(text, onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { actions, onForm, onSummary ->
        OrderScreen(actions, onForm, onSummary)
    }
}

// MARK: - 修正版
private object L01Fixed {
    @Composable
    fun OrderScreen(
        actions: L01Actions,
        onForm: (String) -> Unit,
        onSummary: (String) -> Unit,
    ) {
        // qty 只放在這裡一份
        var qty by remember { mutableStateOf("") }
        SideEffect { actions.clear = { qty = "" } }
        OrderForm(qty, { qty = it }, actions, onForm)
        L01Summary(qty, onSummary)
    }

    @Composable
    fun OrderForm(
        qty: String,
        onQtyChange: (String) -> Unit,
        actions: L01Actions,
        onShow: (String) -> Unit,
    ) {
        // stateless：打字直接交給上層
        SideEffect { actions.type = onQtyChange }
        // 顯示上層傳下來的值
        HeadlessText(qty, onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { actions, onForm, onSummary ->
        OrderScreen(actions, onForm, onSummary)
    }
}

// MARK: - 演練設定
private val pattern = Regex("輸入欄「(.*)」，預估金額欄的數量「(.*)」")

/** 取得某一行 log 裡的（輸入欄, 預估金額欄） */
private fun values(lines: List<String>, prefix: String): Pair<String, String>? =
    lines.firstOrNull { it.startsWith(prefix) }?.let { line ->
        pattern.find(line)?.let {
            it.groupValues[1] to
                it.groupValues[2]
        }
    }

private const val TYPED = "輸入 0.5 之後："
private const val CLEARED = "下單成功、清空數量之後："

val l01 = Lab(
    id = "L01",
    title = "清空數量後，輸入欄還留著舊的",
    question = "第 1 題：state hoisting 是什麼？狀態應該放在哪一層？",
    goal = "數量同時給輸入欄和預估金額用。看輸入欄自己再存一份時，父層清空數量之後兩邊為什麼對不起來，再把狀態提升到父層。",
    steps = listOf(
        "選「Bug 版」執行，比較「下單成功、清空數量之後」輸入欄與預估金額欄的內容。",
        "選「修正版」執行，確認兩邊都清空了。",
        "想一想：狀態要提到哪一層？（需要用它的所有 Composable 的最低共同祖先；只有畫面自己需要的狀態，不要硬塞進 ViewModel）",
    ),
    expectBug = "輸入 0.5 之後兩邊都是 0.5；清空之後，輸入欄還是「0.5」，預估金額欄的數量卻是空的。",
    expectFixed = "輸入 0.5 之後兩邊都是 0.5；清空之後，兩邊都是空的。",
    file = "kotlin09-compose-architecture/L01_StateHoisting.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val typed = values(lines, TYPED)
        val cleared = values(lines, CLEARED)
        when {
            typed == null || cleared == null -> "演練沒有跑完"
            typed != "0.5" to "0.5" -> "輸入 0.5 之後兩邊不一致（輸入欄「${typed.first}」，預估金額欄「${typed.second}」）：OrderForm 打字時要改自己的 text，再呼叫 onQtyChange(it) 通知父層"
            cleared.first.isEmpty() -> "清空之後輸入欄也清空了：Bug 版的 OrderForm 要用 remember { mutableStateOf(qty) } 複製一份自己的狀態"
            else -> null
        }
    },
    checkFixed = { lines ->
        val typed = values(lines, TYPED)
        val cleared = values(lines, CLEARED)
        when {
            typed == null || cleared == null -> "演練沒有跑完"
            typed != "0.5" to "0.5" -> "輸入 0.5 之後兩邊不一致（輸入欄「${typed.first}」，預估金額欄「${typed.second}」）：OrderForm 打字要交給 onQtyChange，顯示 qty"
            cleared != "" to "" -> "清空之後兩邊還沒一致（輸入欄「${cleared.first}」，預估金額欄「${cleared.second}」）：OrderForm 不要自己存狀態，直接顯示 qty"
            else -> null
        }
    },
)
