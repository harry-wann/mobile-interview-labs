// L06：一筆壞資料進來又離開視窗之後，移動平均永遠差了好幾円（累加的總和留下浮點誤差）
// 對應複習站：Coding02TimeStreams 第 5 題「移動平均怎麼做到每加一筆都很快？」（延伸：浮點數誤差）
//
// 情境：5 期移動平均，價格約 1500 萬円。第 11 筆資料來源送來一筆壞資料（價格 1e18），第 16 筆之後它已經離開視窗。
//       Double 只有約 16 位有效數字：總和裡有 1e18 時，一千五百萬那部分的小數位被吃掉，之後減掉 1e18 也補不回來。
// Bug 版：一直加加減減同一個總和（上一題修正版的寫法）。壞資料離開視窗之後，總和裡留著當時被吃掉的誤差，平均永遠偏掉好幾円。
// 修正版：每加滿一輪（period 筆）就把緩衝區重新加總一次，丟掉累積的誤差。壞資料離開視窗後的下一次重新加總，平均就恢復正確。
package tw.harry.labs.solution.coding02timestreams

import java.util.Locale
import kotlin.math.abs
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val PERIOD = 5

private fun makePrices(): List<Double> {
    val prices = (0 until 40).map { 15_000_000 + (it % 7) * 10 + 0.25 }.toMutableList()
    prices[10] = 1e18                                            // 壞資料
    return prices
}

private interface L06Sma {
    /** 加一筆價格，回傳最近 period 筆的平均；資料不足 period 筆時回傳 null */
    fun add(price: Double): Double?
}

private fun format(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "nil"

/** 演練流程（兩個版本共用）：40 筆價格（第 11 筆是壞資料）一筆一筆加，最後和直接加總的正確值比較 */
private fun scenario(log: LabLog, make: (Int) -> L06Sma) {
    val prices = makePrices()
    val sma = make(PERIOD)
    var last: Double? = null
    for (price in prices) last = sma.add(price)
    val exact = prices.takeLast(PERIOD).sum() / PERIOD
    val error = abs((last ?: Double.NaN) - exact)
    log("第 11 筆是壞資料（1e18），最近 5 筆在第 16 筆之後就不含它了")
    log("最後一筆的移動平均 ${format(last)}，直接加總的正確值 ${format(exact)}")
    log("誤差 ${format(error)} 円，超過 1 円：${if (error > 1) "是" else "否"}")
}

// MARK: - Bug 版
private object L06Bug {
    class RunningSumSma(private val period: Int) : L06Sma {
        private val buffer = DoubleArray(period)
        private var index = 0
        private var count = 0
        private var sum = 0.0

        override fun add(price: Double): Double? {
            if (count == period) {
                sum -= buffer[index]
            } else {
                count++
            }
            buffer[index] = price
            sum += price                                         // 一直在同一個總和上加加減減，誤差只會留著
            index = (index + 1) % period
            return if (count == period) sum / period else null
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { RunningSumSma(it) }
}

// MARK: - 修正版
private object L06Fixed {
    class RefreshingSma(private val period: Int) : L06Sma {
        private val buffer = DoubleArray(period)
        private var index = 0
        private var count = 0
        private var sum = 0.0
        private var added = 0

        override fun add(price: Double): Double? {
            if (count == period) {
                sum -= buffer[index]
            } else {
                count++
            }
            buffer[index] = price
            sum += price
            index = (index + 1) % period
            added++
            if (added % period == 0) {
                sum = buffer.take(count).sum()                   // 每滿一輪，從緩衝區重新加總，丟掉累積的誤差
            }
            return if (count == period) sum / period else null
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { RefreshingSma(it) }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "壞資料離開視窗後，平均永遠差好幾円",
    question = "第 5 題：移動平均怎麼做到每加一筆都很快？（延伸：浮點數誤差）",
    goal = "累加的總和會留下浮點誤差。長時間執行的移動平均，要定期從緩衝區重新加總，讓誤差不會永遠留著。",
    steps = listOf(
        "選「Bug 版」執行，看最後一筆的移動平均和正確值差多少円。",
        "選「修正版」執行，確認誤差消失。",
        "想一想：重新加總每 period 筆做一次，平均每筆的成本還是 O(1) 嗎？",
    ),
    expectBug = "壞資料早就離開視窗，最後一筆卻還差好幾円：「誤差 4.600 円，超過 1 円：是」。",
    expectFixed = "誤差約 0 円：「誤差 0.000 円，超過 1 円：否」。",
    file = "coding02-time-streams/L06_SmaDrift.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("誤差") } -> "演練沒有跑完：要用 scenario(log, make) 執行"
            lines.any { it.startsWith("最後一筆的移動平均 nil") } -> "add 還沒寫：緩衝區滿了要回傳最近 5 筆的平均"
            lines.none { it.startsWith("誤差") && it.endsWith("超過 1 円：是") } ->
                "Bug 還沒重現：要在同一個總和上一直加、減，不能重新加總，壞資料留下的誤差才會一直在"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("誤差") } -> "演練沒有跑完：要用 scenario(log, make) 執行"
            lines.any { it.startsWith("最後一筆的移動平均 nil") } -> "add 還沒寫：緩衝區滿了要回傳最近 5 筆的平均"
            lines.none { it.startsWith("誤差") && it.endsWith("超過 1 円：否") } ->
                "誤差還在：每加滿 period 筆，要用 buffer.take(count).sum() 重新加總 sum"
            else -> null
        }
    },
)
