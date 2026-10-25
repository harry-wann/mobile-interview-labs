// L03：數字明明是漲的，閃爍卻是下跌的顏色（閃爍方向和「上一則推播」比，不是和「畫面上的價格」比）
// 對應複習站：Exchange06RealtimeUI 第 5 題「價格變動的閃爍提示怎麼做？」
//
// 情境：行情推播會先合併，畫面每隔一段時間才更新一次，中間的價格使用者根本沒看過。
//        推播依序是：100 → 畫面更新 → 105、103 → 畫面更新 → 103 → 畫面更新。
//        使用者看到的是 100、103、103：第二次畫面更新，數字從 100 變成 103，是上漲。
//        （第一次顯示沒有「上次畫在畫面上的價格」，不閃爍。）
//
// 你要寫：
//   ① Bug 版（L03Bug.Model.refresh）：用 direction(previousPush, current)：和「上一則推播」比。
//   ② 修正版（L03Fixed.Model.refresh）：用 direction(lastShown, current)：和「上次畫在畫面上的價格」比，比完把 lastShown 換成 current。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange06-realtime-ui/L03_FlashDirection.kt
package tw.harry.labs.practice.exchange06realtimeui

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class L03Direction(val text: String) {
    UP("▲ 上漲"),
    DOWN("▼ 下跌"),
    FLAT("－ 持平"),
}

/** old 是「拿來比較的舊價格」，第一次顯示時沒有舊值 */
private fun direction(old: BigDecimal?, new: BigDecimal): L03Direction {
    if (old == null) return L03Direction.FLAT
    return when {
        new > old -> L03Direction.UP
        new < old -> L03Direction.DOWN
        else -> L03Direction.FLAT
    }
}

private class L03Refresh(val shown: BigDecimal, val direction: L03Direction)

private interface L03FlashModel {
    /** 每一則推播到達（背景） */
    fun onPush(price: BigDecimal)

    /** 畫面更新：回傳要顯示的價格與閃爍方向；還沒有任何推播時回傳 null */
    fun refresh(): L03Refresh?
}

/** 演練流程（兩個版本共用）：推播與畫面更新交錯 */
private fun scenario(log: LabLog, model: L03FlashModel) {
    val directions = mutableListOf<String>()
    val shownPrices = mutableListOf<String>()
    fun refresh() {
        val result = model.refresh() ?: return
        directions += result.direction.text
        shownPrices += result.shown.toPlainString()
        log("畫面更新：顯示 ${result.shown.toPlainString()}，${result.direction.text}")
    }
    model.onPush(e06("100")); refresh()
    model.onPush(e06("105")); model.onPush(e06("103")); refresh()
    model.onPush(e06("103")); refresh()
    log("最後：三次畫面更新的閃爍方向：${directions.joinToString("、")}（使用者看到的價格：${shownPrices.joinToString(" → ")}）")
}

// MARK: - Bug 版
private object L03Bug {
    class Model : L03FlashModel {
        private var latest: BigDecimal? = null
        private var previousPush: BigDecimal? = null            // 上一則推播的價格

        override fun onPush(price: BigDecimal) {
            previousPush = latest
            latest = price
        }

        override fun refresh(): L03Refresh? {
            val current = latest ?: return null
            // TODO ①：回傳 L03Refresh(current, direction(previousPush, current))：拿「上一則推播」（previousPush）和 current 比。
            //        目前回傳 FLAT 只是佔位，要改掉
            return L03Refresh(current, L03Direction.FLAT)
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Model())
}

// MARK: - 修正版
private object L03Fixed {
    class Model : L03FlashModel {
        private var latest: BigDecimal? = null
        private var lastShown: BigDecimal? = null               // 上次畫在畫面上的價格

        override fun onPush(price: BigDecimal) {
            latest = price
        }

        override fun refresh(): L03Refresh? {
            val current = latest ?: return null
            // TODO ②：拿「上次畫在畫面上的價格」（lastShown）和 current 比：direction(lastShown, current)；
            //        比完把 lastShown 換成 current，回傳 L03Refresh(current, 方向)。目前回傳 FLAT 只是佔位，要改掉
            return L03Refresh(current, L03Direction.FLAT)
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Model())
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "數字漲了，閃爍卻是下跌的顏色",
    question = "第 5 題：價格變動的閃爍提示怎麼做？",
    goal = "閃爍方向要拿「這次要顯示的價格」和「上次畫在畫面上的價格」比，不是和上一則推播比：推播合併之後，中間的價格使用者沒看過。先寫出和上一則推播比的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange06-realtime-ui/L03_FlashDirection.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看第二次畫面更新：使用者看到的數字從 100 變成 103，閃的方向對嗎？",
        "想一想：推播依序是 105、103，拿 103 和 105 比，得到什麼？使用者其實沒有看過 105。",
        "寫 TODO ②（修正版），選「修正版」執行，三次畫面更新的方向要依序是「持平、上漲、持平」。",
        "進階：閃爍除了顏色，還要加 ▲▼ 符號（色覺異常的使用者），也要尊重系統的「移除動畫」設定（`ValueAnimator.areAnimatorsEnabled()`）。",
    ),
    expectBug = "第二次畫面更新顯示 103，卻是「▼ 下跌」：「最後：三次畫面更新的閃爍方向：－ 持平、▼ 下跌、－ 持平」。",
    expectFixed = "「最後：三次畫面更新的閃爍方向：－ 持平、▲ 上漲、－ 持平（使用者看到的價格：100 → 103 → 103）」。",
    file = "exchange06-realtime-ui/L03_FlashDirection.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, model) 執行"
            lines.none { it.startsWith("最後：三次畫面更新的閃爍方向：－ 持平、▼ 下跌、－ 持平") } ->
                "Bug 還沒重現：refresh 用 previousPush（上一則推播）和 latest 比，第二次會變成 103 比 105，得到下跌"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, model) 執行"
            lines.none { it.startsWith("最後：三次畫面更新的閃爍方向：－ 持平、▲ 上漲、－ 持平") } ->
                "refresh 要拿 lastShown（上次畫在畫面上的價格）和 latest 比，比完把 lastShown 換成 latest：第一次沒有舊值是持平，第二次 103 比 100 是上漲"
            else -> null
        }
    },
)
