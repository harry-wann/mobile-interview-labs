// L05：想設停損，送出去的逆指値卻立刻成交了（觸發價的方向）
// 對應複習站：Exchange01Orders 第 3 題「成行、指値、
// 逆指値這三種基本的下單方式有什麼差別？」
//
// 情境：BTC 現價 10,000,000 円。逆指値是「價格到達觸發價才送出實際委託」：
//        賣出的逆指値（停損），觸發價要低於現價，跌到才賣；
// 買入的逆指値（突破追買），觸發價要高於現價，漲到才買。
//        方向放錯邊，伺服器收到時價格已經滿足條件，單子會立刻被觸發。
//        App 在送出前要先檢查方向。演練依序試四張單：賣出 9,500,000（正確）、
// 賣出 10,500,000（放錯邊）、買入 10,500,000（正確）、買入 9,500,
// 000（放錯邊）。
// Bug 版：檢查方向寫反了：賣出要求觸發價「高於」現價，買入要求「低於」現價。
// 正確的單被擋下，放錯邊的單反而被放行，伺服器立刻觸發。
// 修正版：賣出要求觸發價低於現價，買入要求觸發價高於現價；不符合就在 App
// 端擋下，不送出。
package tw.harry.labs.solution.exchange01orders

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Side(val text: String) {
    BUY("買入"),
    SELL("賣出"),
}

private val lastPrice = e01("10000000")

/** 假伺服器：收到逆指値後，如果現價已經滿足觸發條件，就立刻觸發 */
private fun serverResult(side: Side, trigger: BigDecimal): String {
    val triggered = if (side ==
        Side.SELL
    ) {
        lastPrice <= trigger
    } else {
        lastPrice >= trigger
    }
    return if (triggered) {
        "伺服器：現價已經滿足條件，立刻觸發，以 ${e01Text(lastPrice)} 成交"
    } else {
        "伺服器：進入待機，等價格到 ${e01Text(trigger)} 才觸發"
    }
}

/** 演練流程（兩個版本共用）：四張單依序檢查、送出 */
private fun scenario(
    log: LabLog,
    validate: (Side, BigDecimal, BigDecimal) -> String?,
) {
    val cases = listOf(
        Triple(Side.SELL, "9500000", true),
        Triple(Side.SELL, "10500000", false),
        Triple(Side.BUY, "10500000", true),
        Triple(Side.BUY, "9500000", false),
    )
    var wrongBlocked = 0
    var instantlyTriggered = 0
    for ((side, text, isCorrect) in cases) {
        val trigger = e01(text)
        val name = "${side.text}逆指値 觸發價 ${e01Text(trigger)}"
        val error = validate(side, trigger, lastPrice)
        if (error != null) {
            log("$name：App 擋下（$error）")
            if (isCorrect) wrongBlocked++
        } else {
            val result = serverResult(side, trigger)
            log("$name：App 放行。$result")
            if ("立刻觸發" in result) instantlyTriggered++
        }
    }
    log("正確的單被誤擋 $wrongBlocked 張，放錯邊卻立刻觸發 $instantlyTriggered 張")
}

// MARK: - Bug 版
private object L05Bug {
    fun validate(
        side: Side,
        trigger: BigDecimal,
        lastPrice: BigDecimal,
    ): String? = when (side) {
        // 寫反了
        Side.SELL -> if (trigger > lastPrice) null else "賣出的觸發價要高於現價"

        Side.BUY -> if (trigger < lastPrice) null else "買入的觸發價要低於現價"
    }

    suspend fun run(log: LabLog) = scenario(log, ::validate)
}

// MARK: - 修正版
private object L05Fixed {
    fun validate(
        side: Side,
        trigger: BigDecimal,
        lastPrice: BigDecimal,
    ): String? = when (side) {
        // 停損：跌到才賣
        Side.SELL -> if (trigger < lastPrice) null else "賣出的觸發價要低於現價"

        // 追買：漲到才買
        Side.BUY -> if (trigger > lastPrice) null else "買入的觸發價要高於現價"
    }

    suspend fun run(log: LabLog) = scenario(log, ::validate)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "想設停損，逆指値送出去卻立刻成交",
    question = "第 3 題：成行、指値、逆指値這三種基本的下單方式有什麼差別？",
    goal = "逆指値的觸發價要放在現價的正確一邊：賣出（停損）在現價下方，買入（追買）在現價上方。App 要在送出前擋下放錯邊的單。",
    steps = listOf(
        "選「Bug 版」執行，看四張單各被擋下還是放行，放行後伺服器的反應。",
        "想一想：現價 10,000,000，賣出逆指値觸發價 10,500,000，伺服器收到時條件滿足了嗎？",
        "選「修正版」執行，正確的兩張單進入待機，放錯邊的兩張在 App 端就被擋下。",
        "進階：觸發由伺服器判斷，App 進背景或斷線也會生效；App 只負責輸入驗證，不能自己盯價格再下單。",
    ),
    expectBug = "賣出 9500000 與買入 10500000 被誤擋；賣出 10500000 與買入 9500000 被放行，伺服器立刻以 10000000 成交，「正確的單被誤擋 2 張，放錯邊卻立刻觸發 2 張」。",
    expectFixed = "賣出 9500000、買入 10500000 進入待機；另外兩張在 App 擋下，「正確的單被誤擋 0 張，放錯邊卻立刻觸發 0 張」。",
    file = "exchange01-orders/L05_StopDirection.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("正確的單被誤擋") } -> {
                "演練沒有跑完：要用 scenario(log, ::validate) 執行"
            }

            "正確的單被誤擋 2 張，放錯邊卻立刻觸發 2 張" !in lines -> {
                "Bug 還沒重現：賣出要求觸發價高於現價、買入要求觸發價低於現價（方向寫反），四張單全都判斷錯"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("正確的單被誤擋") } -> {
                "演練沒有跑完：要用 scenario(log, ::validate) 執行"
            }

            "正確的單被誤擋 0 張，放錯邊卻立刻觸發 0 張" !in lines -> {
                "賣出的觸發價要低於現價，買入的要高於現價"
            }

            else -> {
                null
            }
        }
    },
)
