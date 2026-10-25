// L04：日報表的筆數和後台對不上，半夜的交易被算到前一天（日報表的「日」
// 用了裝置時區，而不是 JST）
// 對應複習站：Exchange13Localization 第 4 題「日期與時區怎麼處理？JST
// 是什麼？」
//
// 情境：日本的交易所，日報表的「一天」以日本標準時間（JST，UTC＋9，
// 沒有夏令時間）的 0 點為界。使用者出差到台北（UTC＋8），手機的時區是
// Asia/Taipei。
//        伺服器回傳的交易時間是一個時間點（Unix 時間戳記）。演練有四筆 JST
// 的交易：2026-10-23 23:50、2026-10-24 00:10、2026-10-24 08:55、2026-10-24 23:
// 59，
//        dayKey 要回傳這筆交易屬於哪一天（yyyy-MM-dd）；然後統計每一天有幾筆。
// 後台（JST）的答案是：10-23 有 1 筆，10-24 有 3 筆。
//        （資料內部存時間點，只有「顯示、分組」時才換算成某個時區；到底用 JST
// 還是裝置時區是產品決定，標籤要寫明確。）
// Bug 版：用裝置的時區（台北）切日。台北比 JST 慢 1 小時：JST 的 00:10
// 在台北還是前一天的 23:10，被算進 10-23；
//        日報表 10-23 變成 2 筆、10-24 變成 2 筆，和後台對不上。
// 修正版：日報表的「日」固定用 JST（Asia/Tokyo）切日，不管手機在哪個時區。
package tw.harry.labs.solution.exchange13localization

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

// 使用者出差到台北，手機是 UTC+8
private val deviceZone: ZoneId = ZoneId.of("Asia/Taipei")

/** 四筆交易的時間點（用帶 +09:00 的 ISO 8601 字串建立，等於 JST 的當地時間） */
private val trades: List<Instant> = listOf(
    "2026-10-23T23:50:00+09:00",
    "2026-10-24T00:10:00+09:00",
    "2026-10-24T08:55:00+09:00",
    "2026-10-24T23:59:00+09:00",
).map { OffsetDateTime.parse(it).toInstant() }

/** 後台（JST）的答案 */
private val expected = mapOf("2026-10-23" to 1, "2026-10-24" to 3)

/**
 * 演練流程（兩個版本共用）：每筆交易算出屬於哪一天，統計每一天的筆數，
 * 和後台比對
 */
private fun scenario(log: LabLog, dayKey: (Instant, ZoneId) -> String) {
    val counts = mutableMapOf<String, Int>()
    for (trade in trades) counts.merge(dayKey(trade, deviceZone), 1, Int::plus)
    var wrong = 0
    for (day in expected.keys.sorted()) {
        val n = counts[day] ?: 0
        if (n == expected[day]) {
            log("日報表 $day：$n 筆 ✓")
        } else {
            wrong++
            log("日報表 $day：$n 筆 ✗（後台是 ${expected[day]} 筆）")
        }
    }
    for (day in counts.keys.sorted()) {
        if (day !in expected) {
            wrong++
            log("日報表多出一天 $day：${counts[day]} 筆 ✗")
        }
    }
    log("日報表有 $wrong 天的筆數和後台不一致")
}

// MARK: - Bug 版
private object L04Bug {
    fun dayKey(instant: Instant, deviceZone: ZoneId): String {
        // 用裝置的時區切日：台北比 JST 晚 1 小時
        return instant.atZone(deviceZone).toLocalDate().toString()
    }

    suspend fun run(log: LabLog) = scenario(log) { instant, zone ->
        dayKey(instant, zone)
    }
}

// MARK: - 修正版
private object L04Fixed {
    fun dayKey(instant: Instant, deviceZone: ZoneId): String {
        // 日報表的「日」固定用 JST
        return instant.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate().toString()
    }

    suspend fun run(log: LabLog) = scenario(log) { instant, zone ->
        dayKey(instant, zone)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("日報表有")
}?.let { Regex("(\\d+) 天").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "日報表的筆數和後台對不上，半夜的交易算到前一天",
    question = "第 4 題：日期與時區怎麼處理？JST 是什麼？",
    goal = "資料內部存時間點（UTC 或 Unix 時間戳記）；日報表這種「以哪一天為界」的功能，要固定用業務定義的時區（JST），不能跟著手機的時區。",
    steps = listOf(
        "選「Bug 版」執行，看日報表 10-23 與 10-24 各有幾筆，和後台比對。",
        "想一想：JST 的 00:10 在台北是幾點？它屬於台北時間的哪一天？",
        "選「修正版」執行，日報表 10-23 有 1 筆、10-24 有 3 筆，和後台一致。",
        "進階：畫面上的時間標籤要寫明確（例如 2026-10-24 00:10 JST），顯示 JST 還是裝置時區是產品決定；與 API 交換的時間字串固定用 UTC 與 Locale.ROOT。",
    ),
    expectBug = "「日報表 2026-10-23：2 筆 ✗（後台是 1 筆）」「日報表 2026-10-24：2 筆 ✗（後台是 3 筆）」：「日報表有 2 天的筆數和後台不一致」。",
    expectFixed = "「日報表 2026-10-23：1 筆 ✓」「日報表 2026-10-24：3 筆 ✓」：「日報表有 0 天的筆數和後台不一致」。",
    file = "exchange13-localization/L04_JstDay.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { instant, zone -> … } 執行"
            "日報表 2026-10-23：2 筆 ✗（後台是 1 筆）" !in lines -> "Bug 還沒重現：用台北（UTC+8）切日，JST 的 00:10 在台北還是 10-23，10-23 會變成 2 筆"
            n != 2 -> "Bug 版應該有 2 天的筆數不一致（目前 $n 天）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { instant, zone -> … } 執行"
            n != 0 -> "還有 $n 天不一致：dayKey 的時區要固定 Asia/Tokyo，不要用 deviceZone"
            else -> null
        }
    },
)
