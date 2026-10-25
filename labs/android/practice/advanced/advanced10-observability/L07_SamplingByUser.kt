// L07：抽樣之後，漏斗的轉換率從 60% 變成 10%，error
// 事件也只剩一成（抽樣要以使用者為單位，error 一律送）
// 對應複習站：Advanced10Observability 第 5 題「analytics 事件怎麼設計，
// 才能算出漏斗又不洩漏資料？」（遠端 log 要有取樣與預算，見第 1 題）
//
// 情境：為了省流量與費用，App 對事件抽樣，只送 10%。演練有 2000 位使用者，
// 每位都有 order_form_viewed；其中 60% 有 order_submitted、
//       50% 有 order_acked、40% 有 order_filled；另有 40 位使用者遇到 1 次
// error 事件。分析端要算：
//       「有 order_form_viewed 紀錄的使用者，有多少比例也有 order_submitted
// 紀錄」，真實值是 60%。
//
// 你要寫：
//   ① Bug 版：每個事件各擲一次骰子（隨機數小於 10% 才送）。
// 一位使用者的事件被拆開抽，轉換率只剩約 10%，error 也只收到一成。
//   ② 修正版：以使用者為單位抽樣（用 bucket() 分桶，桶號小於 10
// 的使用者所有事件都送）；error 一律送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced10-observability/L07_SamplingByUser.kt
package tw.harry.labs.practice.advanced10observability

import java.util.Random
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L07Event(val userId: String, val name: String) {
    val isError get() = name == "error"
}

private const val SAMPLE_PERCENT = 10

/** 2000 位使用者的所有事件（兩個版本共用） */
private val allEvents: List<L07Event> = buildList {
    for (i in 0 until 2_000) {
        val user = "u$i"
        add(L07Event(user, "order_form_viewed"))
        if (i % 10 < 6) add(L07Event(user, "order_submitted"))
        if (i % 10 < 5) add(L07Event(user, "order_acked"))
        if (i % 10 < 4) add(L07Event(user, "order_filled"))
        if (i % 50 == 0) add(L07Event(user, "error"))
    }
}

/** 演練流程（兩個版本共用）：makeSampler 回傳「這個事件要不要送」 */
private fun scenario(log: LabLog, makeSampler: () -> (L07Event) -> Boolean) {
    val shouldSend = makeSampler()
    // 分析端收到的事件
    val received = allEvents.filter(shouldSend)

    val viewedUsers = received.filter {
        it.name == "order_form_viewed"
    }.map { it.userId }.toSet()
    val submittedUsers = received.filter {
        it.name == "order_submitted"
    }.map { it.userId }.toSet()
    val both = viewedUsers.intersect(submittedUsers).size
    val percent = if (viewedUsers.isEmpty()) {
        0
    } else {
        both * 100 /
            viewedUsers.size
    }
    log("收到 ${received.size} 筆事件（全部 ${allEvents.size} 筆）")
    log(
        "有 order_form_viewed 紀錄的使用者 ${viewedUsers.size} 位，其中也有 order_submitted 紀錄的 $both 位：轉換率 $percent%（真實是 60%）",
    )
    log(
        "error 事件：收到 ${received.count {
            it.isError
        }} 筆（實際 ${allEvents.count { it.isError }} 筆）",
    )
}

// MARK: - Bug 版
private object L07Bug {
    fun makeSampler(): (L07Event) -> Boolean {
        val random = Random(2026)
        // TODO ①：回傳的函式每次被呼叫，就擲一次骰子：random.nextInt(100) 小於
        // SAMPLE_PERCENT 才送
        return { random.nextInt(100) < 0 }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeSampler)
}

// MARK: - 修正版
private object L07Fixed {
    /** FNV-1a 32 位元雜湊：同一個 userId，不管哪一次、哪個平台，分桶都一樣 */
    fun bucket(userId: String): Int {
        var hash = 2_166_136_261L
        for (byte in "sample:$userId".toByteArray()) {
            hash =
                ((hash xor (byte.toLong() and 0xFF)) * 16_777_619L) and
                0xFFFFFFFFL
        }
        return (hash % 100).toInt()
    }

    fun makeSampler(): (L07Event) -> Boolean {
        // TODO ②：回傳的函式：error 事件（event.isError）一律送；
        // 其他事件以使用者為單位，bucket(event.userId) 小於 SAMPLE_PERCENT 才送
        return { false }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeSampler)
}

// MARK: - 演練設定
private fun receivedCount(lines: List<String>): Int = lines.firstOrNull {
    it.startsWith("收到 ")
}?.split(" ")?.getOrNull(1)?.toIntOrNull()
    ?: 0

private fun conversionPercent(lines: List<String>): Int? = lines.firstOrNull {
    "轉換率" in it
}?.substringAfter("轉換率 ")?.substringBefore("%")?.toIntOrNull()

val l07 = Lab(
    id = "L07",
    title = "抽樣之後轉換率從 60% 變 10%",
    question = "第 5 題：analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？（取樣與預算）",
    goal = "抽樣要以使用者為單位（同一位使用者的事件全送或全不送），而且 error 這類關鍵事件不抽樣。 先寫出每個事件各擲骰子的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced10-observability/L07_SamplingByUser.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看分析端算出來的轉換率與 error 事件的數量。",
        "想一想：每個事件各擲一次骰子，一位使用者的 order_form_viewed 和 order_submitted 同時被送出的機率是多少？",
        "寫 TODO ②，選「修正版」執行：以 userId 分桶抽樣，轉換率回到 60% 附近，error 事件全部收到。",
        "進階：分桶用固定的雜湊（FNV-1a），這樣同一位使用者在 iOS、Android、每次啟動都得到一樣的結果；抽樣比例要隨事件送出，分析端才能還原總量。",
    ),
    expectBug = "轉換率只剩 3%（真實是 60%），error 事件只收到 9 筆（實際 40 筆）。",
    expectFixed = "轉換率 56%（接近真實的 60%，以收到的 209 位使用者計算），error 事件收到 40 筆（實際 40 筆）。",
    file = "advanced10-observability/L07_SamplingByUser.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val percent = conversionPercent(lines)
        when {
            percent == null -> "演練沒有跑完：要用 scenario 執行"

            receivedCount(
                lines,
            ) !in 300..800 -> "Bug 版應該送出約 10% 的事件（約 500 筆）：每個事件各擲一次骰子，隨機數小於 SAMPLE_PERCENT 才送"

            percent > 25 -> "Bug 還沒重現：每個事件各擲一次骰子（10% 才送），轉換率應該只剩約 10%，目前 $percent%"

            else -> null
        }
    },
    checkFixed = { lines ->
        val percent = conversionPercent(lines)
        when {
            percent == null -> "演練沒有跑完：要用 scenario 執行"

            percent !in 45..75 -> "轉換率應該接近 60%（以使用者為單位抽樣），目前 $percent%"

            lines.none {
                it.startsWith("error 事件：收到 40 筆")
            } -> "error 事件應該一律送出，要收到全部 40 筆"

            else -> null
        }
    },
)
