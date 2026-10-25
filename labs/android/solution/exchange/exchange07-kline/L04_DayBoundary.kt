// L04：同一筆成交，在不同國家的手機上被歸到不同天的日 K（用裝置的時區切日界）
// 對應複習站：Exchange07Kline 第 3 題「日 K 的邊界在哪裡？UTC 和 JST 差在哪？」
//
// 情境：App 要把成交聚合進日 K。這套 API 文件的日 K 範例，起點是 16:00
// UTC（UTC+8 的午夜，偏移 28800 秒），要和伺服器的 `id` 一致，
// 聚合才會對得上官方圖表。
//        兩筆成交：2021-09-29 20:00 UTC（1632945600000 毫秒）、2021-09-29 15:
// 30 UTC（1632929400000 毫秒）。
//        伺服器的日 K：第一筆在 id 1632931200 那根，第二筆在 id 1632844800
// 那根。
//        演練模擬三支手機：裝置時區分別是 Asia/Tokyo、America/New_York、UTC。
// Bug 版：用 Calendar（時區設成裝置的時區）把時間歸零，找「這一天的起點」，
// 日界就是各地的午夜：東京是 JST 0 點、紐約是紐約 0 點，都不是伺服器的日界。
// 修正版：用 floor 除法：floorDiv(秒 + 偏移, 週期秒) × 週期秒 − 偏移，偏移是
// 28800（資料來源的日界，做成設定），和裝置時區無關。
package tw.harry.labs.solution.exchange07kline

import java.util.Calendar
import java.util.TimeZone
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val zones = listOf("Asia/Tokyo", "America/New_York", "UTC")

private class L04Trade(val label: String, val ts: Long, val expected: Long)

private val trades = listOf(
    L04Trade("20:00 UTC 的成交", 1_632_945_600_000, 1_632_931_200),
    L04Trade("15:30 UTC 的成交", 1_632_929_400_000, 1_632_844_800),
)

/**
 * 演練流程（兩個版本共用）：`bucketId` 回傳這筆成交所屬那根日 K 的起點（秒）
 */
private fun scenario(
    log: LabLog,
    bucketId: (tsMs: Long, zone: TimeZone) -> Long,
) {
    var wrong = 0
    for (zoneId in zones) {
        val zone = TimeZone.getTimeZone(zoneId)
        for (trade in trades) {
            val id = bucketId(trade.ts, zone)
            if (id != trade.expected) wrong++
            log(
                "裝置時區 $zoneId，${trade.label} → 日 K id $id（伺服器是 ${trade.expected}）${if (id == trade.expected) "✓" else "✗"}",
            )
        }
    }
    log("最後：6 個結果裡，和伺服器對不上的有 $wrong 個")
}

// MARK: - Bug 版
private object L04Bug {
    suspend fun run(log: LabLog) = scenario(log) { tsMs, zone ->
        val calendar = Calendar.getInstance(zone) // 裝置的時區
        calendar.timeInMillis = tsMs
        // 把時間歸零：各地的午夜
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.timeInMillis / 1000
    }
}

// MARK: - 修正版
private object L04Fixed {
    // 資料來源的日界：UTC+8 的午夜（做成設定，向交易所確認）
    private const val ORIGIN_OFFSET_SEC = 28_800L

    suspend fun run(log: LabLog) = scenario(log) { tsMs, _ ->
        val sec = Math.floorDiv(tsMs, 1000L)
        // 和裝置時區無關
        Math.floorDiv(sec + ORIGIN_OFFSET_SEC, 86_400L) * 86_400L -
            ORIGIN_OFFSET_SEC
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "同一筆成交，換個國家就歸到別天的日 K",
    question = "第 3 題：日 K 的邊界在哪裡？UTC 和 JST 差在哪？",
    goal = "日 K 的日界由資料來源決定，不能用裝置的時區：用 floor 除法加上資料來源的偏移，所有手機算出來的 K 線起點才會一致，也和伺服器一致。",
    steps = listOf(
        "選「Bug 版」執行，看三個裝置時區算出來的日 K id，和伺服器的比，哪些對不上？",
        "想一想：東京的手機算出的 1632927600，對應的 UTC 時間是幾點？和伺服器的 16:00 UTC 差多少？",
        "選「修正版」執行，六個結果都要和伺服器一致，而且三個裝置時區算出來一樣。",
        "進階：畫伺服器給的 K 線時，一律用它回傳的 `id`，不要自己重算；只有本機聚合才需要決定日界。",
    ),
    expectBug = "六個結果全部對不上（東京 1632927600、紐約 1632888000、UTC 1632873600…），「最後：6 個結果裡，和伺服器對不上的有 6 個」。",
    expectFixed = "六個結果都 ✓：20:00 UTC 的成交是 1632931200，15:30 UTC 的成交是 1632844800，「和伺服器對不上的有 0 個」。",
    file = "exchange07-kline/L04_DayBoundary.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, bucketId) 執行"
            }

            "裝置時區 Asia/Tokyo，20:00 UTC 的成交 → 日 K id 1632927600（伺服器是 1632931200）✗" !in
                lines -> {
                "Bug 還沒重現：用 Calendar.getInstance(zone) 把時間歸零（時、分、秒、毫秒都設 0）找一天的起點，東京算出來應該是 1632927600"
            }

            "最後：6 個結果裡，和伺服器對不上的有 6 個" !in lines -> {
                "三個裝置時區、兩筆成交，六個結果都應該和伺服器對不上"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("最後")
            } -> "演練沒有跑完：要用 scenario(log, bucketId) 執行"

            "最後：6 個結果裡，和伺服器對不上的有 0 個" !in lines -> "用 floor 除法：sec = Math.floorDiv(tsMs, 1000)；floorDiv(sec + 28800, 86400) * 86400 - 28800，不要用 zone"

            else -> null
        }
    },
)
