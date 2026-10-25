// L03：指値買單的手續費算少了（一張單的成交可能同時有 taker 和 maker）
// 對應複習站：Exchange01Orders 第 2 題「訂單簿是什麼？「約定」「部分約定」
// 「maker／taker」是什麼意思？」
//
// 情境：和上一個演練同一張單：指値買單 0.5 BTC、委託價 10,005,000 円。
//        先吃掉賣板上的 0.2 BTC（成交價 10,000,000 円，這一筆是 taker），
// 剩下的 0.3 BTC 掛在買板上，之後有人賣給他（成交價 10,005,000 円，這一筆是
// maker）。
//        手續費率是演練的假設，不是交易所的費率：maker 0.05%、taker 0.1%。
//
// 你要寫：
//   ① Bug 版（L03Bug.rate）：用下單的種類決定費率：orderType 是 LIMIT 就回傳
// makerRate，否則回傳 takerRate。
//   ② 修正版（L03Fixed.rate）：看這一筆成交實際的角色 fill.role：MAKER 用
// makerRate，TAKER 用 takerRate。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange01-orders/L03_MakerTakerFee.kt
package tw.harry.labs.practice.exchange01orders

import java.math.BigDecimal
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class OrderType { LIMIT, MARKET }

private val makerRate = e01("0.0005") // 演練假設：0.05%
private val takerRate = e01("0.001") // 演練假設：0.1%

/** 演練流程（兩個版本共用）：指値單先吃板、再等到對手，算每一筆成交的手續費 */
private fun scenario(log: LabLog, rate: (E01Fill, OrderType) -> BigDecimal) {
    val book =
        E01Book(
            listOf(
                E01Level(e01("10000000"), e01("0.2")),
                E01Level(e01("10010000"), e01("0.3")),
            ),
        )
    // 立刻吃板：taker
    val fills = book.takeAsks(
        e01("0.5"),
        limit = e01("10005000"),
    ).toMutableList()
    // 之後有人賣給掛單：maker
    fills += E01Fill(e01("0.3"), e01("10005000"), E01Role.MAKER)
    var total = BigDecimal.ZERO
    for (fill in fills) {
        val fee = fill.qty * fill.price * rate(fill, OrderType.LIMIT)
        total += fee
        val role = if (fill.role == E01Role.TAKER) "吃板 taker" else "掛單 maker"
        log(
            "成交 ${e01Text(
                fill.qty,
            )} BTC @ ${e01Text(fill.price)}（$role），手續費 ${e01Text(fee)} 円",
        )
    }
    log("手續費合計 ${e01Text(total)} 円")
}

// MARK: - Bug 版
private object L03Bug {
    fun rate(fill: E01Fill, orderType: OrderType): BigDecimal {
        // TODO ①：orderType 是 LIMIT 就回傳 makerRate，否則回傳 takerRate
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::rate)
}

// MARK: - 修正版
private object L03Fixed {
    fun rate(fill: E01Fill, orderType: OrderType): BigDecimal {
        // TODO ②：看 fill.role：MAKER 回傳 makerRate，TAKER 回傳 takerRate
        return BigDecimal.ZERO
    }

    suspend fun run(log: LabLog) = scenario(log, ::rate)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "指値買單的手續費算少了",
    question = "第 2 題：訂單簿是什麼？「約定」「部分約定」「maker／taker」是什麼意思？",
    goal = "一張指値單可能同時有 taker（吃板）和 maker（掛單）兩種成交。手續費要依每一筆成交的角色分別計算。先寫出手續費算少的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange01-orders/L03_MakerTakerFee.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看兩筆成交各用了哪個費率、手續費合計。",
        "想一想：價格跨過對手的最佳價時，那一筆成交是吃板還是掛單？指値單是不是一定是 maker？",
        "寫 TODO ②（修正版），選「修正版」執行，吃板那一筆應該用 taker 費率。",
        "進階：手續費率以交易所的公告為準，這裡的 0.05%、0.1% 只是演練用的假設。",
    ),
    expectBug = "兩筆成交都用 maker 費率：手續費 1000 + 1500.75，「手續費合計 2500.75 円」。",
    expectFixed = "吃板的 0.2 BTC 用 taker 費率（手續費 2000），掛單的 0.3 BTC 用 maker 費率（手續費 1500.75），「手續費合計 3500.75 円」。",
    file = "exchange01-orders/L03_MakerTakerFee.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("手續費合計")
            } -> "演練沒有跑完：要用 scenario(log, ::rate) 執行"

            "手續費合計 2500.75 円" !in lines -> "Bug 還沒重現：指値單（orderType == LIMIT）每一筆都用 makerRate，合計應該是 2500.75"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("手續費合計")
            } -> "演練沒有跑完：要用 scenario(log, ::rate) 執行"

            "手續費合計 3500.75 円" !in lines -> "要看每一筆成交的 fill.role：MAKER 用 makerRate，TAKER 用 takerRate，合計應該是 3500.75"

            else -> null
        }
    },
)
