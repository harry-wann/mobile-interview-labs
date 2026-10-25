// L06：把警示線調高了，畫面還是顯示「超過警示線」（derivedStateOf 只追蹤
// State，不追蹤一般參數）
// 對應複習站：Kotlin11ComposeState 第 6 題「derivedStateOf、snapshotFlow、
// produceState 怎麼選？」
//
// 情境：價格提醒元件 PriceAlert 的價格是 State（每秒變很多次），警示線
// alertLine 是使用者設定、從外面傳進來的一般參數。
//        畫面只關心「價格有沒有超過警示線」這個布林值，所以用 derivedStateOf。
//        價格 120、警示線 100：超過。使用者把警示線調高到 200，價格仍是 120：
// 應該變成「未超過」。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose
// 在電腦上真的跑 Compose 的組合與重組。
// Bug 版：val over by remember { derivedStateOf { price.value > alertLine } }。
// remember 沒有 key，derivedStateOf 只建立一次，
//        它的計算式抓住的是第一次進來的 alertLine（100）。alertLine
// 是一般參數，不是 State，變了 derivedStateOf 不會知道。
// 修正版：remember(alertLine) { derivedStateOf { … } }。一般參數要當成
// remember 的 key，參數變了就重新建立 derivedStateOf。
package tw.harry.labs.solution.kotlin11composestate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/**
 * 演練流程（兩個版本共用）：價格 120、警示線 100 → 使用者把警示線調高到
 * 200（價格不變）→ 看畫面顯示什麼
 */
private suspend fun scenario(
    log: LabLog,
    alert: @Composable (
        price: State<Long>,
        alertLine: Long,
        onShow: (String) -> Unit,
    ) -> Unit,
) {
    var shown = ""
    withHeadlessCompose {
        val price = mutableLongStateOf(120)
        // 使用者設定的警示線：父層的 State，傳給 alert 時變成一般的 Long
        val alertLine = mutableLongStateOf(100)
        setContent { alert(price, alertLine.longValue) { shown = it } }
        settle()
        log("價格 120、警示線 100，畫面顯示：$shown")
        alertLine.longValue = 200
        settle()
        log("警示線調高到 200，畫面顯示：$shown")
    }
}

// MARK: - Bug 版
private object L06Bug {
    @Composable
    fun PriceAlert(
        price: State<Long>,
        alertLine: Long,
        onShow: (String) -> Unit,
    ) {
        // alertLine 不是 State：計算式抓住第一次的值
        val over by remember { derivedStateOf { price.value > alertLine } }
        HeadlessText(if (over) "超過警示線" else "未超過警示線", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { price, alertLine, onShow ->
        PriceAlert(price, alertLine, onShow)
    }
}

// MARK: - 修正版
private object L06Fixed {
    @Composable
    fun PriceAlert(
        price: State<Long>,
        alertLine: Long,
        onShow: (String) -> Unit,
    ) {
        // 一般參數放進 key：變了就重建
        val over by remember(alertLine) {
            derivedStateOf {
                price.value >
                    alertLine
            }
        }
        HeadlessText(if (over) "超過警示線" else "未超過警示線", onShow)
    }

    suspend fun run(log: LabLog) = scenario(log) { price, alertLine, onShow ->
        PriceAlert(price, alertLine, onShow)
    }
}

// MARK: - 演練設定
private fun line(lines: List<String>, prefix: String): String? =
    lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

val l06 = Lab(
    id = "L06",
    title = "警示線調高了，畫面還是顯示超過",
    question = "第 6 題：derivedStateOf、snapshotFlow、produceState 怎麼選？",
    goal = "價格是 State、警示線是一般參數，用 derivedStateOf 判斷有沒有超過。看警示線變了為什麼畫面不跟著變，再把警示線放進 remember 的 key。",
    steps = listOf(
        "選「Bug 版」執行，比較兩行「畫面顯示」：警示線調高之後，結果有沒有跟著變。",
        "選「修正版」執行，確認調高到 200 之後變成「未超過警示線」。",
        "想一想：如果警示線也是 State（例如 alertLine: State<Long>，計算式裡讀 alertLine.value），還需要 remember(key) 嗎？（不需要，derivedStateOf 會追蹤到它）",
    ),
    expectBug = "第一行「超過警示線」，第二行（警示線調高到 200）還是「超過警示線」：derivedStateOf 用的是第一次的警示線 100。",
    expectFixed = "第一行「超過警示線」，第二行（警示線調高到 200）變成「未超過警示線」。",
    file = "kotlin11-compose-state/L06_DerivedParam.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val first = line(lines, "價格 120、警示線 100，畫面顯示：")
        val second = line(lines, "警示線調高到 200，畫面顯示：")
        when {
            first != "超過警示線" -> "價格 120 比警示線 100 高，畫面要顯示「超過警示線」：用 derivedStateOf { price.value > alertLine }"
            second != "超過警示線" -> "警示線調高之後，畫面變成「$second」：Bug 版的 remember 不要給 key，derivedStateOf 才會一直用第一次的警示線"
            else -> null
        }
    },
    checkFixed = { lines ->
        val first = line(lines, "價格 120、警示線 100，畫面顯示：")
        val second = line(lines, "警示線調高到 200，畫面顯示：")
        when {
            first != "超過警示線" -> "價格 120 比警示線 100 高，畫面要顯示「超過警示線」：用 derivedStateOf { price.value > alertLine }"
            second != "未超過警示線" -> "警示線調高到 200 之後，畫面還是「$second」：remember 要用 alertLine 當 key，警示線變了就重新建立 derivedStateOf"
            else -> null
        }
    },
)
