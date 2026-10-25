// Testing04E2E 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：
//   迷你測試執行器 T04Suite／T04Expect：用它模擬 E2E 測試的通過與失敗。
//   TOTP（兩步驟驗證碼）的正確算法 T04Totp（RFC 6238）、假的時鐘、假的驗證伺服器、假的登入畫面。
//   假的交易所後端 T04FakeExchange（帳號與未成交訂單），以及 CI 重試用的 T04TestCase／T04Report。
package tw.harry.labs.solution.testing04e2e

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import tw.harry.labs.kit.LabLog

// 迷你測試執行器

/** 迷你版的 assertEquals：失敗時只記下訊息，不中斷測試 */
class T04Expect {
    val failures = mutableListOf<String>()
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

/** 迷你版的測試套件：依序執行，把摘要寫進 log */
class T04Suite {
    private val tests = mutableListOf<Pair<String, suspend (T04Expect) -> Unit>>()

    fun test(name: String, body: suspend (T04Expect) -> Unit) {
        tests += name to body
    }

    suspend fun run(label: String, log: LabLog) {
        val failed = mutableListOf<Pair<String, String>>()
        var checked = 0
        for ((name, body) in tests) {
            val t = T04Expect()
            body(t)
            checked += t.assertions
            t.failures.firstOrNull()?.let { failed += name to it }
        }
        for ((name, reason) in failed) log("  ✗ $name：$reason")
        log("$label：跑了 ${tests.size} 個測試，通過 ${tests.size - failed.size}、失敗 ${failed.size}，檢查了 $checked 項")
    }
}

/** 從 log 讀回 T04Suite 寫的摘要（自動檢查用） */
class T04Summary(val cases: Int, val passed: Int, val failed: Int, val assertions: Int) {
    companion object {
        fun parse(lines: List<String>, label: String): T04Summary? {
            val line = lines.firstOrNull { it.startsWith("$label：跑了 ") } ?: return null
            val numbers = Regex("\\d+").findAll(line.removePrefix("$label：")).map { it.value.toInt() }.toList()
            if (numbers.size < 4) return null
            return T04Summary(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}

// L01：TOTP、假的時鐘、假的伺服器、假的登入畫面

/** TOTP（Time-based One-Time Password，RFC 6238）：用密鑰和現在的時間算出 6 位數驗證碼，每 30 秒換一個 */
object T04Totp {
    /**
     * 測試帳號用的固定密鑰。這裡直接用 RFC 6238 附錄的測試密鑰（20 個位元組）；
     * 正式 App 的密鑰通常是 Base32 字串，解碼後就是這樣的位元組。
     */
    val testSecret: ByteArray = "12345678901234567890".toByteArray()

    fun code(seconds: Double, secret: ByteArray = testSecret, digits: Int = 6, step: Int = 30): String {
        val counter = (seconds / step).toLong()
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }.doFinal(message)
        val offset = mac[mac.size - 1].toInt() and 0x0f
        val truncated = ((mac[offset].toInt() and 0x7f) shl 24) or ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or (mac[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (truncated % modulus).toString().padStart(digits, '0')
    }

    /** 目前這個 30 秒的區間還剩幾秒 */
    fun secondsLeft(seconds: Double, step: Int = 30): Double = step - seconds % step

    /** RFC 6238 附錄的測試向量（6 位數）：確認上面的算法是對的 */
    fun selfCheck(): Boolean =
        code(59.0) == "287082" && code(1_111_111_109.0) == "081804" && code(1_234_567_890.0) == "005924"
}

/** 假的時鐘（單位：秒）。測試和伺服器看同一個時鐘；UI 動作用 advance 代表「花了這麼久」，不會真的等 */
class T04Clock(start: Double) {
    var now: Double = start
        private set

    fun advance(seconds: Double) {
        now += seconds
    }
}

/** 假的驗證伺服器：只接受「現在這個 30 秒」的驗證碼（嚴格的伺服器） */
class T04FakeAuthServer(private val clock: T04Clock) {
    fun verify(code: String): Boolean = code == T04Totp.code(clock.now)
}

/** 假的登入畫面。每個動作花的時間：輸入帳號密碼 2.5 秒、等兩步驟驗證畫面出現 1.5 秒、送出驗證碼 0.5 秒 */
class T04FakeLoginUI(private val clock: T04Clock, private val server: T04FakeAuthServer) {
    var loggedIn = false
        private set
    var errorMessage = ""
        private set

    suspend fun typeCredentials() = clock.advance(2.5)
    suspend fun waitForTwoFactorScreen() = clock.advance(1.5)

    suspend fun submitCode(code: String) {
        clock.advance(0.5)                                          // 驗證碼送到伺服器時，已經過了 0.5 秒
        if (server.verify(code)) loggedIn = true else errorMessage = "驗證碼過期或錯誤"
    }
}

// L02：假的交易所後端

/** 假的交易所後端：每個帳號有自己的未成交訂單 */
class T04FakeExchange {
    private val orders = mutableMapOf<String, MutableList<String>>()
    private var nextAccount = 1

    /** 建立一個全新的測試帳號（相當於用 API 建立乾淨的帳號狀態） */
    fun createAccount(): String = "e2e-${nextAccount++}"

    fun placeLimitBuy(account: String, priceJpy: Int) {
        orders.getOrPut(account) { mutableListOf() } += "買 @$priceJpy"
    }

    /** 撤掉第一筆未成交訂單；回傳有沒有真的撤掉（沒有訂單可撤時回傳 false） */
    fun cancelFirst(account: String): Boolean {
        val list = orders[account]
        if (list.isNullOrEmpty()) return false
        list.removeAt(0)
        return true
    }

    fun openOrders(account: String): List<String> = orders[account]?.toList() ?: emptyList()
}

// L03：CI 重試

/** 一個 E2E 測試。run(attempt) 回傳這一次有沒有通過；attempt 從 1 開始 */
class T04TestCase(val name: String, val run: suspend (attempt: Int) -> Boolean) {
    companion object {
        /** 演練用的 4 個測試：穩定通過 2 個、第一次失敗第二次才通過（時間類 flaky）1 個、每次都失敗（真的 bug）1 個 */
        val suite: List<T04TestCase> = listOf(
            T04TestCase("登入加兩步驟驗證") { true },
            T04TestCase("限價買單到撤單") { attempt -> attempt >= 2 },      // 第一次等畫面等不夠久
            T04TestCase("日圓入金說明畫面") { true },
            T04TestCase("出庫新增地址") { false },                         // 真的 bug：每次都失敗
        )
    }
}

/** CI 的測試報告 */
class T04Report {
    val passed = mutableListOf<String>()
    val flakyPassed = mutableListOf<String>()       // 重試之後才通過的
    val failed = mutableListOf<String>()
}
