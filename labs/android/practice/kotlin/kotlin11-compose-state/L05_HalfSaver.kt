// L05：旋轉螢幕後，下單數量歸零了（Saver 只存了一半的欄位）
// 對應複習站：Kotlin11ComposeState 第 3 題「remember 和 rememberSaveable 怎麼選？Saver 怎麼寫？」
//
// 情境：下單草稿 OrderDraft 本來只有交易對和買賣方向，Saver 是那時候寫的。後來為了「數量」加了一個新欄位 qty（有預設值 0.0），
//        Saver 沒跟著改。使用者把草稿填成 ETH_JPY、賣出、0.25，然後旋轉螢幕。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose，
//        再用 SimulatedRotation.kt 模擬旋轉螢幕（存起來、畫面重建、還原）。
//
// 你要寫：
//   ① Bug 版（L05Bug）：mapSaver 只存 symbol 和 side，restore 時 L05Draft(symbol, side)，qty 用預設值 0.0。沒有錯誤，旋轉後數量靜悄悄地歸零。
//   ② 修正版（L05Fixed）：save 和 restore 都加上 qty。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L05_HalfSaver.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 下單草稿：後來加了 qty，有預設值，所以呼叫端不用改 */
private class L05Draft(val symbol: String, val side: String, val qty: Double = 0.0) {
    override fun toString() = "$symbol $side $qty"
}

/** 演練流程（兩個版本共用）：打開畫面 → 使用者把草稿改成 ETH_JPY 賣出 0.25 → 旋轉螢幕 → 看旋轉後畫面顯示什麼 */
private suspend fun scenario(
    log: LabLog,
    screen: @Composable (onState: (MutableState<L05Draft>) -> Unit, onShow: (String) -> Unit) -> Unit,
) {
    val (before, after) = rotateScreen(screen) { it.value = L05Draft("ETH_JPY", "SELL", 0.25) }
    log("旋轉前，畫面顯示：$before")
    log("旋轉後，畫面顯示：$after")
}

// MARK: - Bug 版
private object L05Bug {
    /** 加了 qty 之後沒改：只存 symbol 和 side */
    // TODO ①：用 mapSaver 只存 symbol 和 side（"symbol" to it.symbol、"side" to it.side）；
    //         restore 用 L05Draft(it["symbol"] as String, it["side"] as String)，qty 不傳，用預設值
    val draftSaver = mapSaver(
        save = { emptyMap() },
        restore = { L05Draft("BTC_JPY", "BUY") },
    )

    @Composable
    fun OrderScreen(onState: (MutableState<L05Draft>) -> Unit, onShow: (String) -> Unit) {
        val draft = rememberSaveable(stateSaver = draftSaver) { mutableStateOf(L05Draft("BTC_JPY", "BUY")) }
        SideEffect { onState(draft) }
        HeadlessText(draft.value.toString(), onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { onState, onShow -> OrderScreen(onState, onShow) }
}

// MARK: - 修正版
private object L05Fixed {
    /** 每個欄位都要存、都要還原 */
    // TODO ②：save 多存 "qty" to it.qty；restore 多傳 it["qty"] as Double
    val draftSaver = mapSaver(
        save = { emptyMap() },
        restore = { L05Draft("BTC_JPY", "BUY") },
    )

    @Composable
    fun OrderScreen(onState: (MutableState<L05Draft>) -> Unit, onShow: (String) -> Unit) {
        val draft = rememberSaveable(stateSaver = draftSaver) { mutableStateOf(L05Draft("BTC_JPY", "BUY")) }
        SideEffect { onState(draft) }
        HeadlessText(draft.value.toString(), onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { onState, onShow -> OrderScreen(onState, onShow) }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l05 = Lab(
    id = "L05",
    title = "旋轉螢幕後，下單數量歸零了",
    question = "第 3 題：remember 和 rememberSaveable 怎麼選？Saver 怎麼寫？",
    goal = "OrderDraft 後來多了 qty 欄位，Saver 沒跟著改。看旋轉後數量為什麼靜悄悄地歸零，再讓 Saver 把每個欄位都存起來、還原回來。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin11-compose-state/L05_HalfSaver.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，比較「旋轉前」和「旋轉後」畫面顯示的草稿。沒有例外、沒有當掉，只有數量不對。",
        "寫 TODO ②（修正版），選「修正版」執行，確認旋轉後還是 ETH_JPY SELL 0.25。",
        "想一想：怎麼避免下次又漏掉？（給 Saver 寫一個單元測試：存起來再還原，確認每個欄位都一樣；以後有人加欄位，測試就會失敗）",
    ),
    expectBug = "「旋轉前，畫面顯示：ETH_JPY SELL 0.25」，「旋轉後，畫面顯示：ETH_JPY SELL 0.0」：交易對和方向還在，數量歸零。",
    expectFixed = "「旋轉後，畫面顯示：ETH_JPY SELL 0.25」，和旋轉前一樣。",
    file = "kotlin11-compose-state/L05_HalfSaver.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val before = line(lines, "旋轉前，畫面顯示：")
        val after = line(lines, "旋轉後，畫面顯示：")
        when {
            before != "ETH_JPY SELL 0.25" -> "旋轉前畫面顯示「$before」：scenario 會把草稿改成 ETH_JPY SELL 0.25，畫面要顯示 draft.value"
            after != "ETH_JPY SELL 0.0" -> "旋轉後畫面顯示「$after」：Bug 版的 Saver 只存 symbol 和 side，還原後 qty 要是預設的 0.0"
            else -> null
        }
    },
    checkFixed = { lines ->
        val before = line(lines, "旋轉前，畫面顯示：")
        val after = line(lines, "旋轉後，畫面顯示：")
        when {
            before != "ETH_JPY SELL 0.25" -> "旋轉前畫面顯示「$before」：scenario 會把草稿改成 ETH_JPY SELL 0.25，畫面要顯示 draft.value"
            after != "ETH_JPY SELL 0.25" -> "旋轉後畫面顯示「$after」：Saver 的 save 和 restore 都要加上 qty"
            else -> null
        }
    },
)
