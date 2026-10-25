// L06：停利單成交了，停損單還在，後來又被觸發，賣掉了根本沒有的 BTC（OCO）
// 對應複習站：Exchange01Orders 第 4 題「OCO、IFD、IFDOCO 這些組合單是怎麼運作的？」
//
// 情境：使用者持有 0.1 BTC，掛了一組 OCO（二選一）：賣出指値 10,300,000 円（停利）加賣出逆指値、觸發價 9,500,000 円（停損），各 0.1 BTC。
//        價格先漲到停利價，停利單成交；之後價格暴跌到停損價。OCO 的規則：其中一張成交，另一張要自動取消。
//        （這是組合單的狀態機：伺服器用它決定每一張單的狀態，App 用同一套規則顯示。）
// Bug 版：一張成交時，只把成交的那一張標成 filled，另一張還是 active。價格後來跌到 9,500,000，停損單又被觸發，再賣出 0.1 BTC，持倉變成 −0.1 BTC。
// 修正版：一張成交時，另一張如果還在 active，立刻改成 cancelled，之後不會再被觸發。
package tw.harry.labs.solution.exchange01orders

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private enum class Leg { TAKE_PROFIT, STOP_LOSS }
private enum class LegStatus(val text: String) { ACTIVE("active"), FILLED("filled"), CANCELLED("cancelled") }

/** OCO 組合單的狀態 */
private class OcoGroup {
    var takeProfit = LegStatus.ACTIVE
    var stopLoss = LegStatus.ACTIVE

    operator fun get(leg: Leg) = if (leg == Leg.TAKE_PROFIT) takeProfit else stopLoss
    operator fun set(leg: Leg, value: LegStatus) {
        if (leg == Leg.TAKE_PROFIT) takeProfit = value else stopLoss = value
    }
}

/** 演練流程（兩個版本共用）：價格依序變動，每一張還在 active 的單檢查有沒有觸發 */
private fun scenario(log: LabLog, onFilled: (OcoGroup, Leg) -> Unit) {
    val group = OcoGroup()
    var position = e01("0.1")                               // 持有的 BTC
    val prices = listOf("10000000", "10300000", "9800000", "9500000").map { e01(it) }
    log("持有 0.1 BTC，OCO：停利 10300000 ／ 停損 9500000")
    for (price in prices) {
        if (group.takeProfit == LegStatus.ACTIVE && price >= e01("10300000")) {
            position -= e01("0.1")
            onFilled(group, Leg.TAKE_PROFIT)
            log("價格 ${e01Text(price)}：停利單成交，賣出 0.1 BTC，持倉 ${e01Text(position)} BTC")
        }
        if (group.stopLoss == LegStatus.ACTIVE && price <= e01("9500000")) {
            position -= e01("0.1")
            onFilled(group, Leg.STOP_LOSS)
            log("價格 ${e01Text(price)}：停損單被觸發，賣出 0.1 BTC，持倉 ${e01Text(position)} BTC")
        }
    }
    log("最後狀態：停利 ${group.takeProfit.text}、停損 ${group.stopLoss.text}，持倉 ${e01Text(position)} BTC")
}

// MARK: - Bug 版
private object L06Bug {
    fun onFilled(group: OcoGroup, leg: Leg) {
        group[leg] = LegStatus.FILLED                       // 只標記成交的這一張
    }

    suspend fun run(log: LabLog) = scenario(log, ::onFilled)
}

// MARK: - 修正版
private object L06Fixed {
    fun onFilled(group: OcoGroup, leg: Leg) {
        group[leg] = LegStatus.FILLED
        val other = if (leg == Leg.TAKE_PROFIT) Leg.STOP_LOSS else Leg.TAKE_PROFIT
        if (group[other] == LegStatus.ACTIVE) group[other] = LegStatus.CANCELLED    // 二選一：另一張自動取消
    }

    suspend fun run(log: LabLog) = scenario(log, ::onFilled)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "停利成交了，停損單還在、又賣了一次",
    question = "第 4 題：OCO、IFD、IFDOCO 這些組合單是怎麼運作的？",
    goal = "OCO 是二選一：其中一張成交，另一張要自動取消，之後不能再被觸發。",
    steps = listOf(
        "選「Bug 版」執行，看價格跌到 9500000 時發生什麼事、最後的持倉。",
        "想一想：停利單成交時，狀態表裡另一張單的狀態是什麼？",
        "選「修正版」執行，看最後狀態和持倉。",
        "進階：IFD 是「第一張成交，才送出第二張」，IFDOCO 是 IFD 加 OCO。它們都是伺服器端的狀態機，App 負責輸入驗證、顯示每一張單的狀態與取消。",
    ),
    expectBug = "停利成交後持倉 0，價格跌到 9500000 時停損單又被觸發，「最後狀態：停利 filled、停損 filled，持倉 -0.1 BTC」。",
    expectFixed = "停利成交時停損單改成 cancelled，價格跌到 9500000 什麼都不會發生，「最後狀態：停利 filled、停損 cancelled，持倉 0 BTC」。",
    file = "exchange01-orders/L06_OcoCancel.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後狀態") } -> "演練沒有跑完：要用 scenario(log, ::onFilled) 執行"
            "最後狀態：停利 filled、停損 filled，持倉 -0.1 BTC" !in lines -> "Bug 還沒重現：onFilled 只把成交的那一張標成 FILLED，另一張維持 ACTIVE，後來會被再次觸發"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後狀態") } -> "演練沒有跑完：要用 scenario(log, ::onFilled) 執行"
            "最後狀態：停利 filled、停損 cancelled，持倉 0 BTC" !in lines -> "一張成交時，另一張如果還是 ACTIVE，要改成 CANCELLED"
            else -> null
        }
    },
)
