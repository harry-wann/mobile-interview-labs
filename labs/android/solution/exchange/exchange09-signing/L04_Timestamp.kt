// L04：只有部分使用者簽名永遠失敗（Timestamp 跟著手機的語言、曆法、時區變）
// 對應複習站：Exchange09Signing 第 4 題「Timestamp 怎麼產生才不會出錯？」
//
// 情境：Signature V2 的 Timestamp 要求 UTC、格式固定是 yyyy-MM-ddTHH:mm:ss（24
// 小時制，沒有毫秒與時區後綴）。
//        同一個時間 2017-05-11 15:20:06 UTC，
// 演練用三位使用者手機的設定各產生一次：
//        台灣的使用者（西曆、UTC+8）、日本的使用者（日曆設成和曆、UTC+9）、
// 泰國的使用者（佛曆、UTC+7）。
//        三個人的 Timestamp 都應該是 2017-05-11T15:20:06。
// Bug 版：用舊的 SimpleDateFormat，沒有另外固定語言與時區：
// 語言用使用者的（演練把使用者的語言與時區明確帶進來模擬），
//        結果是本地時間而不是 UTC，和曆的年份變成「平成 29 年」的 29（電腦上的
// JVM 是 29，手機上可能補成 0029），佛曆變成 2560。
// 修正版：用 java.time 的 DateTimeFormatter，搭配 Locale.
// ROOT（不跟著語言設定），並固定 ZoneOffset.UTC。
package tw.harry.labs.solution.exchange09signing

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 使用者手機的設定（演練把它們傳進來，不管電腦或手機本身設定成什麼，
 * 結果都一樣）
 */
private class L04User(
    val name: String,
    val locale: Locale,
    val timeZone: TimeZone,
)

private val users = listOf(
    L04User(
        "台灣的使用者",
        Locale.forLanguageTag("zh-TW"),
        TimeZone.getTimeZone("Asia/Taipei"),
    ),
    L04User(
        "日本的使用者（和曆）",
        Locale.forLanguageTag("ja-JP-u-ca-japanese"),
        TimeZone.getTimeZone("Asia/Tokyo"),
    ),
    L04User(
        "泰國的使用者（佛曆）",
        Locale.forLanguageTag("th-TH-u-ca-buddhist"),
        TimeZone.getTimeZone("Asia/Bangkok"),
    ),
)

private const val EXPECTED = "2017-05-11T15:20:06"

// 2017-05-11 15:20:06 UTC
private const val MOMENT_MS = 1_494_516_006_000L

/** 演練流程（兩個版本共用）：三位使用者各產生一次 Timestamp，和正確答案比對 */
private fun scenario(log: LabLog, timestamp: (Long, L04User) -> String) {
    var wrong = 0
    for (user in users) {
        val text = timestamp(MOMENT_MS, user)
        if (text == EXPECTED) {
            log("${user.name}：$text ✓")
        } else {
            wrong++
            log("${user.name}：$text ✗（應該是 $EXPECTED）")
        }
    }
    log("3 位使用者，Timestamp 算錯的有 $wrong 位")
}

// MARK: - Bug 版
private object L04Bug {
    fun timestamp(epochMs: Long, user: L04User): String {
        // 語言跟著使用者：曆法跟著變
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", user.locale)
        // 時區也是使用者的，不是 UTC
        formatter.timeZone = user.timeZone
        return formatter.format(Date(epochMs))
    }

    suspend fun run(log: LabLog) = scenario(log) { epochMs, user ->
        timestamp(epochMs, user)
    }
}

// MARK: - 修正版
private object L04Fixed {
    fun timestamp(epochMs: Long, user: L04User): String {
        // Locale.ROOT：不跟著語言設定；HH 是 24 小時制
        val formatter = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss",
            Locale.ROOT,
        )
            // 固定用 UTC
            .withZone(ZoneOffset.UTC)
        // java.time 用西曆（ISO），毫秒被忽略
        return formatter.format(Instant.ofEpochMilli(epochMs))
    }

    suspend fun run(log: LabLog) = scenario(log) { epochMs, user ->
        timestamp(epochMs, user)
    }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("3 位使用者")
}?.let { Regex("(\\d+) 位$").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "只有部分使用者簽名永遠失敗",
    question = "第 4 題：Timestamp 怎麼產生才不會出錯？",
    goal = "Timestamp 要固定成 UTC、西曆、24 小時制的 yyyy-MM-ddTHH:mm:ss，不能受手機的語言、曆法、時區影響。",
    steps = listOf(
        "選「Bug 版」執行，看三位使用者的 Timestamp：時間、年份各錯成什麼樣子？",
        "想一想：台灣的使用者為什麼是 23:20:06？日本的使用者為什麼連日期都變了？年份開頭的 29 是哪一年？",
        "選「修正版」執行，三位使用者都要得到 2017-05-11T15:20:06。",
        "進階：Android 的 Locale.ROOT 是不跟著語言設定的固定格式；iOS 的對應做法是 DateFormatter 加 en_US_POSIX、西曆、UTC。",
    ),
    expectBug = "台灣的使用者是 2017-05-11T23:20:06（本地時間，不是 UTC）。電腦上的 JVM：日本的使用者是 29-05-12T00:20:06（平成 29 年、日期也跨日），泰國的使用者是 2560-05-11T22:20:06（佛曆）；Android（模擬器 API 29 實測）的 SimpleDateFormat 不切換曆法，日本是 2017-05-12T00:20:06、泰國是 2017-05-11T22:20:06，只有時區錯。兩種都是「3 位使用者，Timestamp 算錯的有 3 位」。",
    expectFixed = "三位使用者都是 2017-05-11T15:20:06，「3 位使用者，Timestamp 算錯的有 0 位」。",
    file = "exchange09-signing/L04_Timestamp.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> {
                "演練沒有跑完：要用 scenario(log) { epochMs, user -> … } 執行"
            }

            lines.none { it.startsWith("台灣的使用者：2017-05-11T23:20:06") } -> {
                "Bug 還沒重現：沒有固定時區時，台灣的使用者（UTC+8）會得到 23:20:06 的本地時間"
            }

            // 和曆年份只有電腦上的 JVM 會出現：Android 的 SimpleDateFormat
            // 不理會 u-ca-japanese，一律用西曆，只剩時區錯誤
            lines.none {
                it.startsWith("日本的使用者（和曆）：") &&
                    !it.contains("2017-05-11T15:20:06 ✓")
            } -> {
                "Bug 還沒重現：語言與時區跟著使用者時，日本的使用者也會算錯（時區變成 UTC+9，電腦上的 JVM 年份還會變成和曆 29）"
            }

            n != 3 -> {
                "三位使用者的 Timestamp 都應該算錯（目前錯 $n 位）"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { epochMs, user -> … } 執行"
            n != 0 -> "還有 $n 位使用者算錯：用 Locale.ROOT 的 DateTimeFormatter 並固定 ZoneOffset.UTC，不要用 user 的設定"
            else -> null
        }
    },
)
