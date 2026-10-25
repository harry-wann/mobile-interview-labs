// L07：價格跌了 40%，維持率還顯示 125%，從來沒有追証，也沒有ロスカット（槓桿的維持率）
// 對應複習站：Exchange01Orders 第 5 題「槓桿交易的証拠金、維持率、追証、ロスカット是什麼？」
//
// 情境：使用者放 1,000,000 円的証拠金，用 1.6 倍槓桿以 10,000,000 円買進 0.16 BTC（建玉 1,600,000 円）。
//        假設必要証拠金固定是建玉的一半，800,000 円；維持率低於 100% 要追証，低於 50% 強制平倉（ロスカット）。
//        （這些數字與門檻都是為了說明假設的，不是交易所的規則。畫面上的維持率真正要以伺服器推送的值為準，這個演練只練算法。）
//        維持率 = 有効証拠金 ÷ 必要証拠金；有効証拠金 = 証拠金 + 評価損益。價格依序變成：不變、−10%、−15%、−40%。
//
// 你要寫：
//   ① Bug 版（L07Bug.effectiveMargin）：有効証拠金直接回傳放進去的証拠金 deposit，不加評価損益。
//   ② 修正版（L07Fixed.effectiveMargin）：有効証拠金 = 証拠金 + 評価損益（虧損時 pnl 是負數）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange01-orders/L07_MarginRatio.kt
package tw.harry.labs.practice.exchange01orders

import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Health(val text: String) {
    NORMAL("正常"),
    MARGIN_CALL("追証"),
    LIQUIDATION("ロスカット"),
}

private val deposit = e01("1000000")         // 証拠金
private val required = e01("800000")         // 必要証拠金（演練假設固定不變）
private val entry = e01("10000000")          // 買進價格
private val quantity = e01("0.16")           // 持有 BTC 數量

private fun health(ratio: BigDecimal): Health = when {
    ratio >= BigDecimal.ONE -> Health.NORMAL                // 維持率 100% 以上
    ratio >= e01("0.5") -> Health.MARGIN_CALL
    else -> Health.LIQUIDATION
}

/** 演練流程（兩個版本共用）：價格依序下跌，每一步算維持率並判斷狀態 */
private fun scenario(log: LabLog, effectiveMargin: (BigDecimal, BigDecimal) -> BigDecimal) {
    var liquidatedAt: BigDecimal? = null
    for (text in listOf("10000000", "9000000", "8500000", "6000000")) {
        val price = e01(text)
        val pnl = (price - entry) * quantity                // 評価損益
        val ratio = effectiveMargin(deposit, pnl).divide(required, 8, RoundingMode.HALF_UP)
        val state = health(ratio)
        log("價格 ${e01Text(price)}：評価損益 ${e01Text(pnl)}，維持率 ${e01Text(ratio * BigDecimal(100))}%，${state.text}")
        if (state == Health.LIQUIDATION && liquidatedAt == null) liquidatedAt = price
    }
    log("被強制平倉（ロスカット）的價格：${liquidatedAt?.let { e01Text(it) } ?: "沒有"}")
}

// MARK: - Bug 版
private object L07Bug {
    fun effectiveMargin(deposit: BigDecimal, pnl: BigDecimal): BigDecimal {
        // TODO ①：直接回傳 deposit，不加評価損益
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::effectiveMargin)
}

// MARK: - 修正版
private object L07Fixed {
    fun effectiveMargin(deposit: BigDecimal, pnl: BigDecimal): BigDecimal {
        // TODO ②：有効証拠金 = 証拠金 + 評価損益
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::effectiveMargin)
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "跌了 40%，維持率還顯示 125%",
    question = "第 5 題：槓桿交易的証拠金、維持率、追証、ロスカット是什麼？",
    goal = "維持率 = 有効証拠金 ÷ 必要証拠金，有効証拠金要把評価損益算進去。價格下跌時，維持率要跟著降，並在門檻觸發追証與ロスカット。先寫出維持率永遠不變的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange01-orders/L07_MarginRatio.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看四個價格的維持率與狀態。",
        "想一想：價格跌了，使用者帳面上虧了 160,000 円，有効証拠金還是 1,000,000 円嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，對照 125%／105%／95%／45% 與各自的狀態。",
        "進階：維持率會隨價格一直變，畫面上要用伺服器推送的值；App 自己用過期的價格推算，會讓使用者在危險的時候看到安全的數字。",
    ),
    expectBug = "四個價格的維持率都是 125%，狀態都是「正常」，「被強制平倉（ロスカット）的價格：沒有」。",
    expectFixed = "維持率依序 125%、105%（正常）、95%（追証）、45%（ロスカット），「被強制平倉（ロスカット）的價格：6000000」。",
    file = "exchange01-orders/L07_MarginRatio.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("被強制平倉") } -> "演練沒有跑完：要用 scenario(log, ::effectiveMargin) 執行"
            "價格 6000000：評価損益 -640000，維持率 125%，正常" !in lines -> "Bug 還沒重現：有効証拠金直接回傳 deposit，維持率一直是 125%"
            "被強制平倉（ロスカット）的價格：沒有" !in lines -> "Bug 版從來不會ロスカット"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("被強制平倉") } -> "演練沒有跑完：要用 scenario(log, ::effectiveMargin) 執行"
            "價格 8500000：評価損益 -240000，維持率 95%，追証" !in lines -> "有効証拠金 = deposit + pnl：跌 15% 時維持率應該是 95%，要追証"
            "被強制平倉（ロスカット）的價格：6000000" !in lines -> "跌到 6000000 時維持率 45%，應該ロスカット"
            else -> null
        }
    },
)
