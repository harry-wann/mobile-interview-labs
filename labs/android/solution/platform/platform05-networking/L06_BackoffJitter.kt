// L06：伺服器剛恢復，1000 支手機在同一瞬間一起湧入（重試要加 jitter）
// 對應複習站：Platform05Networking 第 5
// 題「網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？」（指數退避與 jitter）
//
// 情境：伺服器短暫當機，1000 支手機同時斷線，各自用「指數退避」決定第 3
// 次重試要等多久。
//        演練把這 1000 個等待時間依 100 ms 分組，看同一個 100 ms
// 內最多有幾支手機一起重連。
// Bug 版：等待時間 = min(30 秒, 1 秒 × 2 的 attempt 次方)。
// 每支手機算出來的都一樣（8 秒），1000 支在同一瞬間全部湧入，
//        剛恢復的伺服器再次被打垮（thundering herd，驚群效應）。
// 修正版：在 0 到上限之間隨機挑一個時間（jitter，隨機抖動），重連的時間散開。
package tw.harry.labs.solution.platform05networking

import java.util.Locale
import kotlin.random.Random
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 演練流程（兩個版本共用）：1000 支手機各自算第 3 次重試的等待時間。
 * 亂數用固定種子，結果每次一樣
 */
private fun scenario(
    log: LabLog,
    backoffMillis: (attempt: Int, random: Random) -> Long,
) {
    val random = Random(2026)
    val delays = List(1000) { backoffMillis(3, random) }
    val buckets = delays.groupingBy { it / 100 }.eachCount()
    fun seconds(ms: Long) = String.format(Locale.US, "%.1f", ms / 1000.0)
    log(
        "第 3 次重試：等待時間上限 8 秒，實際在 ${seconds(
            delays.min(),
        )} 到 ${seconds(delays.max())} 秒之間",
    )
    log("1000 支手機裡，同一個 0.1 秒內湧入最多的有 ${buckets.values.max()} 支")
}

// MARK: - Bug 版
private object L06Bug {
    /**
     * attempt：第幾次重試（從 0 開始）。上限依序是 1、2、4、8 秒……最多 30 秒
     */
    fun backoffMillis(
        attempt: Int,
        random: Random,
        baseMs: Long = 1_000,
        capMs: Long = 30_000,
    ): Long = // 只有指數退避：大家算出來的時間一模一樣
        minOf(capMs, baseMs * (1L shl attempt))

    suspend fun run(log: LabLog) = scenario(log) { attempt, random ->
        backoffMillis(attempt, random)
    }
}

// MARK: - 修正版
private object L06Fixed {
    fun backoffMillis(
        attempt: Int,
        random: Random,
        baseMs: Long = 1_000,
        capMs: Long = 30_000,
    ): Long {
        val ceiling = minOf(capMs, baseMs * (1L shl attempt))
        // 在 0 到上限之間隨機挑一個時間
        return random.nextLong(0, ceiling + 1)
    }

    suspend fun run(log: LabLog) = scenario(log) { attempt, random ->
        backoffMillis(attempt, random)
    }
}

// MARK: - 演練設定
private fun peak(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("1000 支手機裡")
}?.let { Regex("(\\d+) 支$").find(it)?.groupValues?.get(1)?.toInt() }

val l06 = Lab(
    id = "L06",
    title = "伺服器一恢復，1000 支手機同一瞬間湧入",
    question = "第 5 題：網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？",
    goal = "1000 支手機同時斷線、各自退避重連。讓它們的重連時間散開，不要擠在同一個瞬間。",
    steps = listOf(
        "選「Bug 版」執行，看最後一行：同一個 0.1 秒內湧入最多幾支手機。",
        "想一想：1000 支手機算出來的等待時間一樣嗎？伺服器剛恢復，同一瞬間收到 1000 個重連會怎樣？",
        "選「修正版」執行，看最多的那一組降到幾支。",
        "進階：jitter 只解決「大家同時重連」。429 的 Retry-After 標頭是伺服器明確告訴你要等多久，要優先遵守。",
    ),
    expectBug = "「第 3 次重試：等待時間上限 8 秒，實際在 8.0 到 8.0 秒之間」「同一個 0.1 秒內湧入最多的有 1000 支」。",
    expectFixed = "實際等待時間分散在 0 到 8 秒之間，「同一個 0.1 秒內湧入最多的」只有二十幾支（不到 100 支）。",
    file = "platform05-networking/L06_BackoffJitter.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = peak(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"

            lines.none {
                "實際在 8.0 到 8.0 秒之間" in it
            } -> "第 3 次重試的等待時間應該是 min(30 秒, 2 的 3 次方) = 8 秒"

            n < 900 -> "Bug 還沒重現：只用指數退避、不加亂數，1000 支手機的等待時間應該一模一樣（目前最多 $n 支）"

            else -> null
        }
    },
    checkFixed = { lines ->
        val n = peak(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { … } 執行"
            n >= 100 -> "重連時間還是擠在一起（最多 $n 支）：在 0 到上限之間用 random.nextLong(0, ceiling + 1) 隨機挑一個時間"
            else -> null
        }
    },
)
