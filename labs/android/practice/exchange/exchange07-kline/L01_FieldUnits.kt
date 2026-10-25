// L01：K 線的日期跑到 1970 年，成交量差了好幾個數量級（`id` 的單位是秒；`vol` 是日圓金額、`amount` 才是 BTC 數量）
// 對應複習站：Exchange07Kline 第 1 題「這套交易所 API 的 K 線資料長什麼樣子？每個欄位是什麼意思？」
//
// 情境：REST 的日 K 回應（數字是為了說明編的，格式依文件），新的在前：
//        第 1 根 id 1632931200（2021-09-29 16:00 UTC），amount 6.63（BTC 的數量），vol 31208129（日圓的成交金額）；
//        第 2 根 id 1632844800（2021-09-28 16:00 UTC），amount 5.1，vol 23900000。
//        畫面要顯示每根 K 線的起點時間（UTC）與「成交量（BTC）」。
//
// 你要寫：
//   ① Bug 版（L01Bug.Mapper）：date：把 id 當成毫秒，直接 Date(id)；volumeBTC：直接回傳 bar.vol。
//   ② 修正版（L01Fixed.Mapper）：date：id 是秒，Date(id * 1000)；volumeBTC：回傳 bar.amount。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange07-kline/L01_FieldUnits.kt
package tw.harry.labs.practice.exchange07kline

import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L01Response(val data: List<L01RawBar>)

private class L01RawBar(
    val id: Long,
    val open: Double,
    val close: Double,
    val low: Double,
    val high: Double,
    val amount: Double,
    val vol: Double,
    val count: Int,
)

private const val JSON = """
{"status":"ok","ch":"market.btcjpy.kline.1day","data":[
 {"id":1632931200,"open":4663135,"close":4815018,"low":4580081,"high":4830000,"amount":6.63,"vol":31208129,"count":7534},
 {"id":1632844800,"open":4650000,"close":4663135,"low":4600000,"high":4700000,"amount":5.1,"vol":23900000,"count":6000}]}
"""

private fun utcText(date: Date): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(date)
}

private interface L01KlineMapper {
    /** K 線的起點時間 */
    fun date(id: Long): Date

    /** 要顯示的「成交量（BTC）」 */
    fun volumeBTC(bar: L01RawBar): Double
}

/** 演練流程（兩個版本共用）：解析 REST 回應，逐根顯示 */
private fun scenario(log: LabLog, mapper: L01KlineMapper) {
    val bars = Gson().fromJson(JSON, L01Response::class.java).data
    bars.forEachIndexed { index, bar ->
        log("第 ${index + 1} 根：${utcText(mapper.date(bar.id))} UTC，成交量 ${String.format(Locale.ROOT, "%.2f", mapper.volumeBTC(bar))} BTC")
    }
    log("最後：共 ${bars.size} 根（正確答案：第 1 根 2021-09-29 16:00 UTC、6.63 BTC；第 2 根 2021-09-28 16:00 UTC、5.10 BTC）")
}

// MARK: - Bug 版
private object L01Bug {
    class Mapper : L01KlineMapper {
        override fun date(id: Long): Date {
            // TODO ①-1：把 id 當成毫秒：直接 Date(id)。目前回傳 Date(0)（1970-01-01）只是佔位
            return Date(0)
        }

        override fun volumeBTC(bar: L01RawBar): Double {
            // TODO ①-2：直接回傳 bar.vol 當成交量。目前回傳 0.0 只是佔位
            return 0.0
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Mapper())
}

// MARK: - 修正版
private object L01Fixed {
    class Mapper : L01KlineMapper {
        override fun date(id: Long): Date {
            // TODO ②-1：id 的單位是秒，Date 要毫秒：Date(id * 1000)。目前回傳 Date(0)（1970-01-01）只是佔位
            return Date(0)
        }

        override fun volumeBTC(bar: L01RawBar): Double {
            // TODO ②-2：BTC 的數量是 bar.amount。目前回傳 0.0 只是佔位
            return 0.0
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Mapper())
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "K 線日期變成 1970 年，成交量差好幾個數量級",
    question = "第 1 題：這套交易所 API 的 K 線資料長什麼樣子？每個欄位是什麼意思？",
    goal = "`id` 是 K 線的起點時間，單位是秒（不是毫秒）；`amount` 是標的數量（BTC），`vol` 是計價金額（日圓）。顯示成交量要標清楚單位。先寫出把秒當成毫秒、用 vol 當成交量的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange07-kline/L01_FieldUnits.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看每根 K 線顯示的日期與成交量，和最後一行的正確答案比一比。",
        "想一想：1632931200 當秒是 2021 年，除以 1000 之後當秒是幾年？成交量 31208129 BTC 合理嗎？",
        "自己驗算：`vol ÷ amount` 是平均成交價，應該落在這根的最低價與最高價之間（31208129 ÷ 6.63 ≈ 4.7M）。",
        "寫 TODO ②（修正版），選「修正版」執行，兩根 K 線的日期與成交量都要和正確答案一致。",
    ),
    expectBug = "第 1 根顯示「1970-01-19 21:35 UTC，成交量 31208129.00 BTC」，日期錯了、單位也錯了。",
    expectFixed = "第 1 根「2021-09-29 16:00 UTC，成交量 6.63 BTC」，第 2 根「2021-09-28 16:00 UTC，成交量 5.10 BTC」。",
    file = "exchange07-kline/L01_FieldUnits.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, mapper) 執行"
            "第 1 根：1970-01-19 21:35 UTC，成交量 31208129.00 BTC" !in lines ->
                "Bug 還沒重現：date 要直接 Date(id)（把秒當成毫秒）；volumeBTC 要回傳 bar.vol（日圓金額）"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, mapper) 執行"
            "第 1 根：2021-09-29 16:00 UTC，成交量 6.63 BTC" !in lines -> "date 要用 id * 1000（id 的單位是秒，Date 要毫秒）；volumeBTC 要回傳 bar.amount（BTC 的數量），不是 vol"
            "第 2 根：2021-09-28 16:00 UTC，成交量 5.10 BTC" !in lines -> "第 2 根也要一樣處理"
            else -> null
        }
    },
)
