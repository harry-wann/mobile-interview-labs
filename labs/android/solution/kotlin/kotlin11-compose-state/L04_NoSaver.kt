// L04：下單草稿用 rememberSaveable 存，畫面一打開就當掉（自訂型別沒寫 Saver）
// 對應複習站：Kotlin11ComposeState 第 3 題「remember 和 rememberSaveable 怎麼選？Saver 怎麼寫？」
//
// 情境：下單畫面把草稿（交易對加數量）放在 rememberSaveable，希望旋轉螢幕後還在。草稿是自訂的 class OrderDraft。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose，
//        再用 SimulatedRotation.kt 模擬旋轉螢幕（存起來、畫面重建、還原），Android 預設的「只收得下 Bundle 能放的型別」規則也一併模擬。
// Bug 版：rememberSaveable { mutableStateOf(OrderDraft(…)) }，沒有給 Saver。OrderDraft 不是 String、數字、Serializable、Parcelable，
//        預設的規則存不下，第一次組合就丟 IllegalArgumentException（手機上就是 App 當掉）。
// 修正版：用 listSaver 告訴 Compose 怎麼把 OrderDraft 拆成 List（交易對、數量）以及怎麼組回來，
//        rememberSaveable(stateSaver = draftSaver) { mutableStateOf(OrderDraft(…)) }。旋轉之後草稿還在。
package tw.harry.labs.solution.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 下單草稿：自訂的 class，沒有實作 Serializable 或 Parcelable */
private class L04Draft(val symbol: String, val qty: Double) {
    override fun toString() = "$symbol $qty"
}

/** 演練流程（兩個版本共用）：打開畫面 → 使用者把草稿改成 ETH_JPY 0.25 → 旋轉螢幕 → 看旋轉後畫面顯示什麼 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (onState: (MutableState<L04Draft>) -> Unit, onShow: (String) -> Unit) -> Unit,
) {
    try {
        val (before, after) = rotateScreen(screen) { it.value = L04Draft("ETH_JPY", 0.25) }
        log("旋轉前，畫面顯示：$before")
        log("旋轉後，畫面顯示：$after")
    } catch (e: IllegalArgumentException) {
        log("第一次組合就當掉了：${e::class.simpleName}，訊息含「cannot be saved using the current SaveableStateRegistry」=" +
            (e.message?.contains("cannot be saved using the current SaveableStateRegistry") == true))
    }
}

// MARK: - Bug 版
private object L04Bug {
    @Composable
    fun OrderScreen(onState: (MutableState<L04Draft>) -> Unit, onShow: (String) -> Unit) {
        val draft = rememberSaveable { mutableStateOf(L04Draft("BTC_JPY", 0.1)) }   // 沒給 Saver：預設的規則存不下 L04Draft
        SideEffect { onState(draft) }
        HeadlessText(draft.value.toString(), onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { onState, onShow -> OrderScreen(onState, onShow) }
}

// MARK: - 修正版
private object L04Fixed {
    /** 告訴 Compose：怎麼把 L04Draft 拆成 List（只放 String 和 Double），以及怎麼組回來 */
    val draftSaver = listSaver<L04Draft, Any>(
        save = { listOf(it.symbol, it.qty) },
        restore = { L04Draft(it[0] as String, it[1] as Double) },
    )

    @Composable
    fun OrderScreen(onState: (MutableState<L04Draft>) -> Unit, onShow: (String) -> Unit) {
        val draft = rememberSaveable(stateSaver = draftSaver) { mutableStateOf(L04Draft("BTC_JPY", 0.1)) }
        SideEffect { onState(draft) }
        HeadlessText(draft.value.toString(), onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { onState, onShow -> OrderScreen(onState, onShow) }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l04 = Lab(
    id = "L04",
    title = "下單草稿用 rememberSaveable，一打開就當掉",
    question = "第 3 題：remember 和 rememberSaveable 怎麼選？Saver 怎麼寫？",
    goal = "把自訂的 OrderDraft 放進 rememberSaveable。看沒寫 Saver 為什麼存不下、丟什麼例外，再用 listSaver 寫一個 Saver，讓旋轉後草稿還在。",
    steps = listOf(
        "選「Bug 版」執行，看 log 的「第一次組合就當掉了：IllegalArgumentException」。手機上這就是 App 一打開畫面就閃退。",
        "選「修正版」執行，確認旋轉後畫面顯示的是 ETH_JPY 0.25，不是預設的 BTC_JPY 0.1。",
        "想一想：把 OrderDraft 改成 data class 並實作 Serializable 也能存，為什麼多數人還是寫 Saver？（Serializable 要靠反射、很慢，而且欄位改了舊資料可能還原失敗；Saver 把存哪些欄位寫得很明確）",
    ),
    expectBug = "「第一次組合就當掉了：IllegalArgumentException，訊息含「cannot be saved using the current SaveableStateRegistry」=true」。",
    expectFixed = "「旋轉前，畫面顯示：ETH_JPY 0.25」，「旋轉後，畫面顯示：ETH_JPY 0.25」。",
    file = "kotlin11-compose-state/L04_NoSaver.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val crashed = line(lines, "第一次組合就當掉了：")
        when {
            crashed == null -> "沒有當掉：Bug 版要用 rememberSaveable { mutableStateOf(L04Draft(…)) }，而且不要給 Saver"
            !crashed.endsWith("=true") -> "當掉了，但不是 rememberSaveable 存不下：$crashed"
            else -> null
        }
    },
    checkFixed = { lines ->
        val crashed = line(lines, "第一次組合就當掉了：")
        val before = line(lines, "旋轉前，畫面顯示：")
        val after = line(lines, "旋轉後，畫面顯示：")
        when {
            crashed != null -> "還是當掉了：要用 rememberSaveable(stateSaver = draftSaver) { … } 傳入 listSaver 寫的 Saver"
            before != "ETH_JPY 0.25" -> "旋轉前畫面顯示「$before」：scenario 會把草稿改成 ETH_JPY 0.25，畫面要顯示 draft.value"
            after != "ETH_JPY 0.25" -> "旋轉後畫面顯示「$after」，草稿沒有還原：用 rememberSaveable(stateSaver = draftSaver)，Saver 要存 symbol 和 qty"
            else -> null
        }
    },
)
