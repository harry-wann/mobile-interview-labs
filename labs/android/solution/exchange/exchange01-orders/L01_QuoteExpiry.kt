// L01：畫面寫購入價格 10,100,000 円，實際成交卻是 10,160,000 円（販売所的報價有有效時間）
// 對應複習站：Exchange01Orders 第 1 題「販売所和取引所差在哪？使用者到底是在跟誰買賣？」
//
// 情境：販売所（店頭交易）的買進畫面顯示「購入價格 10,100,000 円、売却價格 9,900,000 円」，報價 5 秒內有效。
//        使用者看了 7 秒才按確認，這段時間交易所的報價已經漲到 10,160,000 円。
//        （演練用本機的假報價櫃檯，時間用毫秒數傳進去，不用真的等。5 秒是演練的假設，不是交易所的規格。）
// Bug 版：按確認時只送出數量，沒有帶使用者看到的報價。櫃檯用當下的價格成交，使用者多付了 60,000 円，畫面上沒有任何提示。
// 修正版：確認時帶上報價的編號。報價已經過期，櫃檯不成交，回傳新的報價；App 顯示「価格が変動しました」，
//        請使用者看過新價格再按一次確認。
package tw.harry.labs.solution.exchange01orders

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的販売所櫃檯：報價 5 秒內有效，3 秒時價格上漲 */
private class Desk {
    class Quote(
        val id: Int,
        val buy: BigDecimal,            // 購入價格：使用者買 1 BTC 要付的價格
        val sell: BigDecimal,           // 売却價格：使用者賣 1 BTC 能拿到的價格
        val expiresAtMs: Int,
    )

    sealed interface Result {
        class Filled(val price: BigDecimal) : Result
        class Requote(val quote: Quote) : Result
    }

    private var nextId = 1
    private val issued = mutableMapOf<Int, Quote>()

    private fun market(nowMs: Int): Pair<BigDecimal, BigDecimal> =
        if (nowMs < 3_000) e01("10100000") to e01("9900000") else e01("10160000") to e01("9960000")

    fun quote(nowMs: Int): Quote {
        val (buy, sell) = market(nowMs)
        return Quote(nextId++, buy, sell, nowMs + 5_000).also { issued[it.id] = it }
    }

    /** quoteId 是 null：用當下的價格成交。有帶 quoteId：報價還有效才用報價的價格成交，過期就回傳新的報價。 */
    fun buy(qty: BigDecimal, quoteId: Int?, nowMs: Int): Result {
        if (quoteId == null) return Result.Filled(market(nowMs).first)
        val quote = issued[quoteId]
        if (quote != null && nowMs <= quote.expiresAtMs) return Result.Filled(quote.buy)
        return Result.Requote(quote(nowMs))
    }
}

/** 演練流程（兩個版本共用）：看報價 → 7 秒後按確認 */
private fun scenario(log: LabLog, confirm: (Desk, Desk.Quote, BigDecimal, Int) -> Desk.Result) {
    val desk = Desk()
    val shown = desk.quote(0)
    log("畫面顯示：購入價格 ${e01Text(shown.buy)}／売却價格 ${e01Text(shown.sell)}，價差 ${e01Text(shown.buy - shown.sell)} 円（販売所的成本在這裡）")
    log("報價在 ${shown.expiresAtMs} ms 到期，使用者 7000 ms 時按下確認")
    when (val result = confirm(desk, shown, BigDecimal.ONE, 7_000)) {
        is Desk.Result.Filled ->
            log("成交價 ${e01Text(result.price)}，畫面上顯示的是 ${e01Text(shown.buy)}，多付 ${e01Text(result.price - shown.buy)} 円")
        is Desk.Result.Requote -> {
            val fresh = result.quote
            log("価格が変動しました：新的購入價格 ${e01Text(fresh.buy)}（原本 ${e01Text(shown.buy)}），沒有成交，請使用者重新確認")
            val again = desk.buy(BigDecimal.ONE, fresh.id, 7_500)
            if (again is Desk.Result.Filled) log("使用者看過新價格再按確認，成交價 ${e01Text(again.price)}")
        }
    }
}

// MARK: - Bug 版
private object L01Bug {
    fun confirm(desk: Desk, quote: Desk.Quote, qty: BigDecimal, nowMs: Int): Desk.Result =
        desk.buy(qty, quoteId = null, nowMs = nowMs)            // 只送數量：櫃檯用當下的價格成交

    suspend fun run(log: LabLog) = scenario(log, ::confirm)
}

// MARK: - 修正版
private object L01Fixed {
    fun confirm(desk: Desk, quote: Desk.Quote, qty: BigDecimal, nowMs: Int): Desk.Result =
        desk.buy(qty, quoteId = quote.id, nowMs = nowMs)        // 帶上使用者看到的報價：過期就由櫃檯回傳新報價

    suspend fun run(log: LabLog) = scenario(log, ::confirm)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "畫面寫的價格和實際成交價不同",
    question = "第 1 題：販売所和取引所差在哪？使用者到底是在跟誰買賣？",
    goal = "販売所的報價只在幾秒內有效。使用者按確認時，如果價格已經變了，不能悄悄用新價格成交，要請使用者重新確認。",
    steps = listOf(
        "選「Bug 版」執行，比較「畫面上顯示的」和「成交價」差多少。",
        "想一想：按確認時，App 有沒有告訴櫃檯「我看到的是哪一個報價」？",
        "選「修正版」執行，看有沒有出現「価格が変動しました」，以及重新確認後的成交價。",
        "進階：販売所的成本是購入價格和売却價格的價差（畫面上第一行），就算手續費寫 0 円，買進後馬上賣掉還是會少 200,000 円。",
    ),
    expectBug = "「成交價 10160000，畫面上顯示的是 10100000，多付 60000 円」，沒有任何提示。",
    expectFixed = "「価格が変動しました：新的購入價格 10160000（原本 10100000），沒有成交」，使用者重新確認後才以 10160000 成交。",
    file = "exchange01-orders/L01_QuoteExpiry.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("畫面顯示：購入價格 10100000") } -> "演練沒有跑完：要用 scenario(log, ::confirm) 執行"
            "成交價 10160000，畫面上顯示的是 10100000，多付 60000 円" !in lines -> "Bug 還沒重現：按確認時不帶報價（quoteId 傳 null），櫃檯會用當下的價格 10160000 成交"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.any { it.startsWith("成交價") } -> "報價已經過期，不該直接成交：呼叫 desk.buy 時要帶 quote.id"
            lines.none { it.startsWith("価格が変動しました") } -> "沒有出現「価格が変動しました」：帶上 quote.id，櫃檯才會回傳新報價"
            "使用者看過新價格再按確認，成交價 10160000" !in lines -> "重新確認後應該以新報價 10160000 成交"
            else -> null
        }
    },
)
