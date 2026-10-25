// L05：跨年那一天的交易紀錄顯示成明年，晚上 11 點顯示成 11:59（日期格式的 YYYY 與 hh）
// 對應複習站：Exchange13Localization 第 4 題「日期與時區怎麼處理？JST 是什麼？」
//        （語言、曆法、時區不固定造成的問題，在 Exchange09 的演練 4 練過；這題格式的 Locale、時區都已經固定，只看日期格式字串本身。）
//
// 情境：交易紀錄的時間標籤要用固定、沒有歧義的格式：「2026-10-23 14:05:09 JST」（24 小時制，加上 JST 後綴）。formatter 的語言固定 Locale.ROOT、時區 Asia/Tokyo，
//        只剩日期格式字串要寫。演練有三筆 JST 的時間：2024-12-31 23:59:59、2025-01-01 00:00:05、2026-10-24 13:05:09。
// Bug 版：格式字串寫成 "YYYY-MM-dd hh:mm:ss"。YYYY 是「週年」（week-based year，以週為單位算的年份），12 月 31 日那一週屬於 2025 年的第 1 週，
//        所以 2024-12-31 顯示成 2025-12-31；hh 是 12 小時制（1 到 12），23 點顯示成 11、0 點顯示成 12。
// 修正版：格式字串用 "yyyy-MM-dd HH:mm:ss"（yyyy 是西曆年，HH 是 24 小時制），再接上 " JST"。
package tw.harry.labs.solution.exchange13localization

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** （描述, 時間點, 正確的標籤） */
private class L05Case(val name: String, val iso: String, val expected: String)

private val cases = listOf(
    L05Case("跨年前一秒", "2024-12-31T23:59:59+09:00", "2024-12-31 23:59:59 JST"),
    L05Case("新年第一天 0 點", "2025-01-01T00:00:05+09:00", "2025-01-01 00:00:05 JST"),
    L05Case("一般的下午", "2026-10-24T13:05:09+09:00", "2026-10-24 13:05:09 JST"),
)

/** 語言、時區都固定好的 formatter（兩個版本共用），只有日期格式字串由 pattern 決定 */
private fun label(iso: String, pattern: String): String {
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(ZoneId.of("Asia/Tokyo"))
    return formatter.format(OffsetDateTime.parse(iso).toInstant()) + " JST"     // 時區縮寫不依賴系統給的，自己加上後綴
}

/** 演練流程（兩個版本共用）：三筆時間逐一產生標籤，和正確答案比對 */
private fun scenario(log: LabLog, pattern: String) {
    var wrong = 0
    for (c in cases) {
        val result = label(c.iso, pattern)
        if (result == c.expected) {
            log("${c.name} → $result ✓")
        } else {
            wrong++
            log("${c.name} → $result ✗（應該是 ${c.expected}）")
        }
    }
    log("共 3 筆時間標籤，顯示錯的有 $wrong 筆")
}

// MARK: - Bug 版
private object L05Bug {
    val pattern: String
        get() {
            return "YYYY-MM-dd hh:mm:ss"                              // YYYY 是週年，hh 是 12 小時制
        }

    suspend fun run(log: LabLog) = scenario(log, pattern)
}

// MARK: - 修正版
private object L05Fixed {
    val pattern: String
        get() {
            return "yyyy-MM-dd HH:mm:ss"                              // yyyy 是西曆年，HH 是 24 小時制
        }

    suspend fun run(log: LabLog) = scenario(log, pattern)
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("共 3 筆") }?.let { Regex("(\\d+) 筆$").find(it)?.groupValues?.get(1)?.toInt() }

val l05 = Lab(
    id = "L05",
    title = "跨年那天的紀錄顯示成明年，晚上 11 點顯示 11:59",
    question = "第 4 題：日期與時區怎麼處理？JST 是什麼？",
    goal = "日期格式字串：yyyy 是西曆年，YYYY 是週年（跨年那一週會顯示成下一年）；HH 是 24 小時制，hh 是 12 小時制。交易紀錄用固定、沒有歧義的格式，並標示 JST。",
    steps = listOf(
        "選「Bug 版」執行，看三筆時間：2024-12-31 的年份、23:59:59 的小時各顯示成什麼？",
        "想一想：12 月 31 日那一週為什麼屬於 2025 年的第 1 週？這種 bug 一年只有幾天會出現，平常測試看得到嗎？",
        "選「修正版」執行，三筆時間都要和正確答案一致。",
        "進階：java.time 的 y 是 year-of-era、u 是 year、Y 是 week-based-year；SimpleDateFormat 的 y 與 Y 也是同樣的差別。",
    ),
    expectBug = "「跨年前一秒 → 2025-12-31 11:59:59 JST ✗」（年份變成 2025，小時變成 11），「新年第一天 0 點 → 2025-01-01 12:00:05 JST ✗」，「一般的下午 → 2026-10-24 01:05:09 JST ✗」（13 點顯示成 01）：「共 3 筆時間標籤，顯示錯的有 3 筆」。",
    expectFixed = "三筆都和正確答案一致，例如「2024-12-31 23:59:59 JST」：「共 3 筆時間標籤，顯示錯的有 0 筆」。",
    file = "exchange13-localization/L05_WeekYear.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, pattern) 執行"
            "跨年前一秒 → 2025-12-31 11:59:59 JST ✗（應該是 2024-12-31 23:59:59 JST）" !in lines ->
                "Bug 還沒重現：格式字串用 \"YYYY-MM-dd hh:mm:ss\"，2024-12-31 會顯示成 2025-12-31，23 點顯示成 11"
            n != 3 -> "Bug 版三筆都應該顯示錯（目前錯 $n 筆）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, pattern) 執行"
            n != 0 -> "還有 $n 筆顯示錯：yyyy 是西曆年（不是 YYYY 週年），HH 是 24 小時制（不是 hh）"
            else -> null
        }
    },
)
