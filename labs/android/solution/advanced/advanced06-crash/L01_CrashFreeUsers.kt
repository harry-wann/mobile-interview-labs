// L01：後台說 crash-free users 是 92%，其實是 95%（crash-free users 怎麼算、
// 問題怎麼排序）
// 對應複習站：Advanced06Crash 第 1 題「crash-free users 是什麼？和 crash-free
// sessions 差在哪？」（問題的排序見第 5 題的步驟二）
//
// 情境：一天的資料：100 位使用者、1000 次 session，共 8 筆 crash 事件，分屬 3
// 個問題。
//       其中 u07 卡在「失敗就重試」的迴圈，同一個問題連續 crash 了 4 次；另外
// 4 位使用者各 crash 1 次。
// Bug 版：把「crash 事件的筆數」當成「受影響的使用者數」。u07 一個人被算成 4
// 位，crash-free users 變成 92%；
//        問題排行也按筆數排，u07 一個人製造的問題排第一，真正影響 3
// 位使用者的問題被排在後面。
// 修正版：先用 userId 去重，再算受影響的使用者數；
// 問題排行按「影響幾位使用者」。
//        crash-free sessions 才是用筆數除以 session 數（一次 session
// 最多算一次 crash）。
package tw.harry.labs.solution.advanced06crash

import java.util.Locale
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class L01CrashEvent(val userId: String, val issue: String)

private const val TOTAL_USERS = 100
private const val TOTAL_SESSIONS = 1_000

/**
 * 一天的 crash 事件：OrderViewModel 強制解包 4 筆（都是 u07）、Realm 跨執行緒
 * 3 筆（3 位）、K 線圖記憶體 1 筆（1 位）
 */
private val events = listOf(
    L01CrashEvent("u07", "OrderViewModel 強制解包"),
    L01CrashEvent("u07", "OrderViewModel 強制解包"),
    L01CrashEvent("u07", "OrderViewModel 強制解包"),
    L01CrashEvent("u07", "OrderViewModel 強制解包"),
    L01CrashEvent("u21", "Realm 跨執行緒"),
    L01CrashEvent("u35", "Realm 跨執行緒"),
    L01CrashEvent("u58", "Realm 跨執行緒"),
    L01CrashEvent("u90", "K 線圖記憶體"),
)

private fun percent(part: Int, whole: Int) = String.format(
    Locale.ROOT,
    "%.1f%%",
    part.toDouble() / whole * 100,
)

/** 演練流程（兩個版本共用）：算 crash-free users／sessions，再列出問題排行 */
private fun scenario(
    log: LabLog,
    affectedUsers: (List<L01CrashEvent>) -> Int,
    usersPerIssue: (List<L01CrashEvent>) -> Map<String, Int>,
) {
    val affected = affectedUsers(events)
    log(
        "crash-free users：${percent(
            TOTAL_USERS - affected,
            TOTAL_USERS,
        )}（受影響 $affected 位／共 $TOTAL_USERS 位）",
    )
    log(
        "crash-free sessions：${percent(
            TOTAL_SESSIONS - events.size,
            TOTAL_SESSIONS,
        )}（crash ${events.size} 次／共 $TOTAL_SESSIONS 次）",
    )
    val ranking = usersPerIssue(events).entries.sortedWith(
        compareByDescending<Map.Entry<String, Int>> {
            it.value
        }.thenBy { it.key },
    )
    for ((issue, users) in ranking) log("問題排行：$issue，影響 $users 位")
    ranking.firstOrNull()?.let { log("最優先要修：${it.key}") }
}

// MARK: - Bug 版
private object L01Bug {
    /** 受影響的使用者數 */
    fun affectedUsers(events: List<L01CrashEvent>): Int = // 一筆事件算一位使用者
        events.size

    /** 每個問題影響幾位使用者 */
    fun usersPerIssue(events: List<L01CrashEvent>): Map<String, Int> =
        // 數的是筆數，不是人數
        events.groupBy { it.issue }.mapValues { it.value.size }

    suspend fun run(log: LabLog) =
        scenario(log, ::affectedUsers, ::usersPerIssue)
}

// MARK: - 修正版
private object L01Fixed {
    fun affectedUsers(events: List<L01CrashEvent>): Int = // 同一位使用者只算一次
        events.map { it.userId }.toSet().size

    fun usersPerIssue(events: List<L01CrashEvent>): Map<String, Int> =
        events.groupBy { it.issue }.mapValues { entry ->
            entry.value.map { it.userId }.toSet().size
        }

    suspend fun run(log: LabLog) =
        scenario(log, ::affectedUsers, ::usersPerIssue)
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "後台說 92%，其實是 95%",
    question = "第 1 題：crash-free users 是什麼？和 crash-free sessions 差在哪？",
    goal = "crash-free users 的分母和分子都是「人」，不是 crash 的次數；問題的優先順序也要看影響幾位使用者。",
    steps = listOf(
        "選「Bug 版」執行，看 crash-free users 的數字，以及「最優先要修」是哪一個問題。",
        "想一想：u07 一個人 crash 了 4 次，算 1 位使用者還是 4 位？crash-free sessions 為什麼可以直接用筆數除？",
        "選「修正版」執行，比較 crash-free users 與問題排行。",
    ),
    expectBug = "「crash-free users：92.0%（受影響 8 位）」；最優先要修的是只影響 1 位使用者的「OrderViewModel 強制解包」。",
    expectFixed = "「crash-free users：95.0%（受影響 5 位）」；最優先要修的是影響 3 位使用者的「Realm 跨執行緒」。crash-free sessions 兩個版本都是 99.2%。",
    file = "advanced06-crash/L01_CrashFreeUsers.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("crash-free users：")
            } -> "演練沒有跑完：要用 scenario 執行"

            lines.none {
                it.startsWith("crash-free users：92.0%")
            } -> "Bug 還沒重現：把事件筆數 8 當成受影響的使用者數，crash-free users 會是 92.0%"

            "最優先要修：OrderViewModel 強制解包" !in lines -> "Bug 還沒重現：問題排行要按筆數排，u07 一個人 4 筆會排第一"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("crash-free users：")
            } -> "演練沒有跑完：要用 scenario 執行"

            lines.none {
                it.startsWith("crash-free users：95.0%（受影響 5 位")
            } -> "crash-free users 應該是 95.0%：受影響的是 5 位不同的使用者（用 userId 去重）"

            "最優先要修：Realm 跨執行緒" !in lines -> "最優先要修的應該是影響 3 位使用者的「Realm 跨執行緒」：每個問題要數不同的 userId"

            else -> null
        }
    },
)
