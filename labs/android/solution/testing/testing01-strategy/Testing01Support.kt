// Testing01Strategy 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   迷你測試執行器 T01Suite／T01Expect：用它模擬「一個測試通過或失敗」，讓結果能寫進 log 給自動檢查看。
//   被測的下單規則 T01OrderRules、出金規則 T01Withdrawal，以及模擬 UI 測試、系統時鐘的小工具。
package tw.harry.labs.solution.testing01strategy

import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import tw.harry.labs.kit.LabLog

// 迷你測試執行器

/** 迷你版的 assertEquals：失敗時只記下訊息，不中斷測試 */
class T01Expect {
    val failures = mutableListOf<String>()

    /** 一共檢查了幾項（空的測試什麼都沒檢查，也會顯示「通過」，所以要數一下） */
    var assertions = 0
        private set

    fun <V> equal(actual: V, expected: V, note: String = "") {
        assertions++
        if (actual != expected) failures += "預期 $expected，實際 $actual" + if (note.isEmpty()) "" else "（$note）"
    }

    fun isTrue(condition: Boolean, note: String) {
        assertions++
        if (!condition) failures += note
    }
}

class T01Result(val name: String, val failures: List<String>, val assertions: Int) {
    val passed get() = failures.isEmpty()
}

/** 迷你版的測試套件：依序執行每個測試，統計通過幾個、花了幾毫秒，把摘要寫進 log */
class T01Suite {
    private val tests = mutableListOf<Pair<String, suspend (T01Expect) -> Unit>>()

    fun test(name: String, body: suspend (T01Expect) -> Unit) {
        tests += name to body
    }

    suspend fun run(label: String, log: LabLog): List<T01Result> {
        val start = System.nanoTime()
        val results = mutableListOf<T01Result>()
        for ((name, body) in tests) {
            val t = T01Expect()
            body(t)
            results += T01Result(name, t.failures.toList(), t.assertions)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val failed = results.filter { !it.passed }
        for (r in failed) log("  ✗ ${r.name}：${r.failures[0]}")
        val checked = results.sumOf { it.assertions }
        log("$label：跑了 ${results.size} 個案例，通過 ${results.size - failed.size}、失敗 ${failed.size}，檢查了 $checked 項，花了 $ms ms")
        return results
    }
}

/** 從 log 讀回 T01Suite 寫的摘要（自動檢查用） */
class T01Summary(val cases: Int, val passed: Int, val failed: Int, val assertions: Int, val ms: Int) {
    companion object {
        fun parse(lines: List<String>, label: String): T01Summary? {
            val line = lines.firstOrNull { it.startsWith("$label：跑了 ") } ?: return null
            // 標籤本身可能有數字（例如 04:10），只讀後面的統計
            val numbers = Regex("\\d+").findAll(line.removePrefix("$label：")).map { it.value.toInt() }.toList()
            if (numbers.size < 5) return null
            return T01Summary(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4])
        }
    }
}

// 被測的程式：下單規則

enum class T01OrderError(val message: String) {
    PRICE_NOT_ON_TICK("價格不是 1,000 日圓的倍數"),
    AMOUNT_TOO_SMALL("數量低於最小下單量 0.0001 BTC"),
    AMOUNT_TOO_LARGE("數量超過單筆上限 5 BTC"),
}

object T01OrderRules {
    /** 價格單位是日圓，數量單位是 satoshi（1 BTC = 100,000,000 satoshi）。回傳 null 代表可以下單。 */
    fun validate(priceJpy: Int, amountSatoshi: Int): T01OrderError? {
        if (priceJpy % 1_000 != 0) return T01OrderError.PRICE_NOT_ON_TICK
        if (amountSatoshi < 10_000) return T01OrderError.AMOUNT_TOO_SMALL
        if (amountSatoshi > 500_000_000) return T01OrderError.AMOUNT_TOO_LARGE
        return null
    }
}

/** 一個下單規則的測試案例 */
class T01OrderCase(
    val name: String,
    val priceJpy: Int,
    val amountSatoshi: Int,
    val expected: T01OrderError?,
    /** 模擬 UI 的動畫要跑幾毫秒，錯誤訊息才會出現在畫面上 */
    val animationMs: Int,
) {
    companion object {
        val all: List<T01OrderCase> = listOf(
            T01OrderCase("正常的限價單", 15_000_000, 100_000, null, 60),
            T01OrderCase("價格不是 1,000 的倍數", 15_000_500, 100_000, T01OrderError.PRICE_NOT_ON_TICK, 140),
            T01OrderCase("剛好最小下單量", 15_000_000, 10_000, null, 75),
            T01OrderCase("剛好單筆上限", 15_000_000, 500_000_000, null, 80),
            T01OrderCase("最小價格", 1_000, 10_000, null, 70),
            T01OrderCase("超過單筆上限 1 satoshi", 15_000_000, 500_000_001, T01OrderError.AMOUNT_TOO_LARGE, 150),
            T01OrderCase("一般價格與數量", 14_999_000, 250_000_000, null, 65),
            T01OrderCase("價格差 1 日圓", 999, 10_000, T01OrderError.PRICE_NOT_ON_TICK, 130),
            T01OrderCase("數量差 1 satoshi", 15_000_000, 9_999, T01OrderError.AMOUNT_TOO_SMALL, 85),
            T01OrderCase("數量是 0", 16_500_000, 0, T01OrderError.AMOUNT_TOO_SMALL, 160),
            T01OrderCase("價格與數量都錯：先報價格", 16_000_001, 600_000_000, T01OrderError.PRICE_NOT_ON_TICK, 70),
            T01OrderCase("數量遠超過上限", 15_000_000, 1_000_000_000, T01OrderError.AMOUNT_TOO_LARGE, 145),
        )
    }
}

/**
 * 模擬 UI 測試（Compose UI 測試、Espresso）的代價。真正的 UI 測試要啟動 App、登入、等動畫、點按鈕，這裡用等待時間代替：
 * 冷啟動加登入 120 ms、輸入 40 ms、點確認 20 ms；錯誤訊息要等動畫結束才會出現在畫面上。
 */
class T01FakeOrderScreen(private val animationMs: Int) {
    private var priceJpy = 0
    private var amountSatoshi = 0
    private var tappedAt = System.nanoTime()

    /** 冷啟動、登入、進入下單頁（120 ms） */
    suspend fun launch() {
        delay(120)
    }

    /** 在價格與數量兩個輸入框打字（40 ms） */
    suspend fun type(priceJpy: Int, amountSatoshi: Int) {
        this.priceJpy = priceJpy
        this.amountSatoshi = amountSatoshi
        delay(40)
    }

    /** 點「確認」（20 ms） */
    suspend fun tapConfirm() {
        delay(20)
        tappedAt = System.nanoTime()
    }

    /** 讀目前畫面上的錯誤訊息。點確認之後動畫還沒結束時，畫面上還沒有訊息（回傳 null） */
    fun errorLabel(): T01OrderError? {
        val elapsedMs = (System.nanoTime() - tappedAt) / 1_000_000
        if (elapsedMs < animationMs) return null
        return T01OrderRules.validate(priceJpy, amountSatoshi)
    }
}

// 被測的程式：出金規則

object T01Withdrawal {
    /** 單筆出金達到 100 萬日圓，要再做一次兩步驟驗證 */
    fun requiresTwoFactor(amountJpy: Int): Boolean {
        if (amountJpy >= 1_000_000) return true
        return false
    }

    /** 被改壞的版本：>= 變成 >。mutation testing 工具會自動產生這種版本，這裡手寫一個 */
    fun requiresTwoFactorMutant(amountJpy: Int): Boolean {
        if (amountJpy > 1_000_000) return true
        return false
    }
}

// 被測的程式：維護時段（時間相關）

/**
 * 模擬「系統時間」：程式裡直接寫 ZonedDateTime.now() 讀到的就是它。真實的系統時間沒辦法調，
 * 演練用它代表「這個測試剛好跑在哪個時間點」（例如 CI 在半夜跑）。
 */
object T01SystemClock {
    @Volatile
    var clock: Clock = Clock.systemDefaultZone()

    /** 等於 ZonedDateTime.now() */
    fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}

object T01Time {
    private val jst: ZoneId = ZoneId.of("Asia/Tokyo")

    /** 2026-10-26（星期一）日本時間幾點幾分 */
    fun at(hour: Int, minute: Int): ZonedDateTime = ZonedDateTime.of(2026, 10, 26, hour, minute, 0, 0, jst)

    /** 固定在某個時間點的 Clock（測試用） */
    fun fixedClock(hour: Int, minute: Int): Clock = Clock.fixed(at(hour, minute).toInstant(), jst)

    /** 維護時段：每天 04:00 到 04:30（含 04:00，不含 04:30），不能下單 */
    fun isInMaintenance(time: ZonedDateTime): Boolean {
        val minutes = time.withZoneSameInstant(jst).let { it.hour * 60 + it.minute }
        return minutes in 240 until 270
    }
}
