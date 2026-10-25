// L04：兩個地方同時格式化價格，有些數字的小數位數不對（共用一個 DecimalFormat，邊用邊改設定）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、也不會拖慢畫面？」
//
// 情境：行情列表的價格要依交易對的精度顯示：BTC_JPY 0 位小數（10,234,568）、XRP_JPY 4 位小數（0.1235）。
//        背景有兩個工作同時在格式化價格：一個格式化 BTC 的價格、一個格式化 XRP 的價格，各 10 萬次，
//        每次都和「單獨格式化的正確答案」比對。
//        筆記的規則：DecimalFormat 本身不是 thread-safe（多個執行緒同時使用會出錯），要每個執行緒各一個，或集中在同一個執行緒使用。
//
// 你要寫：
//   ① Bug 版（L04Bug.Shared.text）：共用同一個 formatter：每次先把 minimumFractionDigits、maximumFractionDigits 設成 digits，再 format。
//   ② 修正版（L04Fixed.PerThread.text）：依 digits 從 formatters.get()（這個執行緒自己的一組）取出對應的 formatter，再 format，不修改它。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange06-realtime-ui/L04_SharedFormatter.kt
package tw.harry.labs.practice.exchange06realtimeui

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L04PriceFormatter {
    fun text(value: BigDecimal, digits: Int): String
}

private fun makeFormatter(digits: Int): DecimalFormat {
    val formatter = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    formatter.minimumFractionDigits = digits
    formatter.maximumFractionDigits = digits
    return formatter
}

private const val ROUNDS = 100_000

private class L04Job(val value: BigDecimal, val digits: Int, val expected: String)

/** 演練流程（兩個版本共用）：兩個工作同時格式化，和正確答案比對（丟出例外也算一次不對） */
private suspend fun scenario(log: LabLog, formatter: L04PriceFormatter) {
    val jobs = listOf(L04Job(e06("10234567.891"), 0, "10,234,568"), L04Job(e06("0.123456"), 4, "0.1235"))
    val wrong = coroutineScope {
        jobs.map { job ->
            async(Dispatchers.Default) {
                var bad = 0
                repeat(ROUNDS) {
                    val text = try {
                        formatter.text(job.value, job.digits)
                    } catch (e: Exception) {
                        "例外"
                    }
                    if (text != job.expected) bad++
                }
                bad
            }
        }.awaitAll().sum()
    }
    log("兩個工作各格式化 $ROUNDS 次，共 ${ROUNDS * jobs.size} 次，字串不對的有 $wrong 次")
    log("最後：字串不對的有 $wrong 次（${if (wrong == 0) "全部正確" else "有些數字的小數位數錯了"}）")
}

// MARK: - Bug 版
private object L04Bug {
    class Shared : L04PriceFormatter {
        private val formatter = makeFormatter(0)

        override fun text(value: BigDecimal, digits: Int): String {
            // TODO ①：先把 formatter 的 minimumFractionDigits、maximumFractionDigits 設成 digits，再回傳 formatter.format(value)。
            //        目前回傳空字串只是佔位，要改掉
            return ""
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Shared())
}

// MARK: - 修正版
private object L04Fixed {
    class PerThread : L04PriceFormatter {
        private val formatters = object : ThreadLocal<Map<Int, DecimalFormat>>() {         // 每個執行緒各一組，建好就不再修改
            override fun initialValue(): Map<Int, DecimalFormat> = (0..4).associateWith { makeFormatter(it) }
        }

        override fun text(value: BigDecimal, digits: Int): String {
            // TODO ②：從 formatters.get()!!.getValue(digits) 取出這個執行緒、這個精度的 formatter（不要修改它），回傳 format(value)。
            //        目前回傳空字串只是佔位，要改掉
            return ""
        }
    }

    suspend fun run(log: LabLog) = scenario(log, PerThread())
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("最後：字串不對的有") }
        ?.split(Regex("[^0-9]+"))?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()

val l04 = Lab(
    id = "L04",
    title = "同時格式化價格，小數位數錯亂",
    question = "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？",
    goal = "formatter 要依精度各建一個、建好就不再修改。共用一個 formatter 又邊用邊改設定，多個執行緒同時使用時，設定會互相覆蓋。先寫出共用 formatter、每次改設定的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange06-realtime-ui/L04_SharedFormatter.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「字串不對的有幾次」。同樣的數字、同樣的程式，為什麼有時候對、有時候錯？",
        "想一想：一條執行緒剛把小數位數設成 0，另一條執行緒就把它設成 4，第一條接著格式化會得到什麼？",
        "寫 TODO ②（修正版），選「修正版」執行，要看到字串不對的有 0 次。",
        "進階：iOS 的 `NumberFormatter` 比較寬鬆：多個執行緒同時「使用」是安全的，只有同時「修改」不安全。Android 的 `DecimalFormat` 連「只使用、不修改」都不安全，所以修正版要用 `ThreadLocal`。",
    ),
    expectBug = "20 萬次裡有幾千到幾萬次字串不對（次數每次執行都不同，但一定大於 0）：BTC 的價格被格式化成 4 位小數，XRP 的價格被格式化成整數。",
    expectFixed = "20 萬次全部正確：「字串不對的有 0 次（全部正確）」。",
    file = "exchange06-realtime-ui/L04_SharedFormatter.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, formatter) 執行"
            n == 200_000 -> "格式化結果全部都不對：Bug 版要共用一個 formatter，每次先依 digits 改設定，再 format，這樣只有交錯的時候才會錯"
            n == 0 -> "Bug 還沒重現（這次剛好都對）：共用同一個 DecimalFormat，每次格式化都先改 minimumFractionDigits／maximumFractionDigits，再重新執行一次"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, formatter) 執行"
            n == 200_000 -> "格式化結果全部都不對：依 digits 從 formatters.get() 取出對應的 formatter，再 format"
            n != 0 -> "還有 $n 次字串不對：不要共用同一個 formatter 又修改它的設定，每個執行緒各建一組（ThreadLocal），建好就不再修改"
            else -> null
        }
    },
)
