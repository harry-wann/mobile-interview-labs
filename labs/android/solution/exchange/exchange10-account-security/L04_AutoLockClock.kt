// L04：把系統時間往回調，就繞過了自動鎖定（自動鎖定要用單調時鐘計時）
// 對應複習站：Exchange10AccountSecurity 第 4 題「自動鎖定（auto-lock）
// 要做到什麼程度，才不只是『蓋一個畫面』？」
//        （Exchange04 的演練 6 練過「行情的新鮮度也要用單調時鐘」；
// 這題是安全功能：計時被繞過，就等於沒有鎖。）
//
// 情境：App 進背景時記下時間，回前景時比較：離開超過 60
// 秒就要重新驗證（鎖定）。演練用一台假裝置模擬兩種時鐘：
//        系統時間（System.currentTimeMillis()，使用者可以在設定裡改，
// 網路校時也會讓它跳動）、單調時鐘（只會往前走、不受修改系統時間影響，含睡眠；
//        Android 用 SystemClock.elapsedRealtime()，iOS 16 以上用
// ContinuousClock）。五個情境：
//        A. 離開 30 秒 → 不鎖
//        B. 離開 5 分鐘 → 鎖
//        C. 離開 5 分鐘，期間使用者把系統時間往回調 1 小時 → 鎖
//        D. 離開 10 秒，期間系統時間因網路校時往前跳了 1 天 → 不鎖
//        E. 沒有進背景的記錄（例如 App 被系統收掉重新啟動）→
// 鎖（這個情境已經寫好）
// Bug 版：用系統時間（wallMs）：進背景時記下 wallMs，回前景時用「現在的 wallMs
// 減掉記下的值」判斷。
//        C：系統時間往回調 1 小時，算出來是負的，不會鎖；D：系統時間往前跳 1
// 天，只離開 10 秒也被鎖。
// 修正版：用單調時鐘（elapsedMs）：只看「這段時間裝置真的走了多久」，
// 改系統時間也影響不到。
package tw.harry.labs.solution.exchange10accountsecurity

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 假裝置：兩種時鐘。系統時間可以被使用者或網路校時改動，
 * 單調時鐘只會隨時間往前走。
 */
private class L04Device {
    var wallMs: Long = 1_700_000_000_000L // 系統時間
        private set

    // 單調時鐘（含睡眠）
    var elapsedMs: Long = 5_000_000L
        private set

    fun pass(ms: Long) {
        wallMs += ms
        elapsedMs += ms
    }

    /** 只改系統時間；單調時鐘不受影響 */
    fun userChangesSystemTime(ms: Long) {
        wallMs += ms
    }
}

private interface L04AutoLock {
    fun onBackground()
    fun shouldLockOnForeground(): Boolean
}

private const val TIMEOUT_MS = 60_000L
private const val MINUTE = 60_000L

/** 演練流程（兩個版本共用）：五個情境，每個情境各自一台全新的裝置與鎖 */
private fun scenario(log: LabLog, makeLock: (L04Device) -> L04AutoLock) {
    var wrong = 0
    fun check(
        name: String,
        expected: Boolean,
        record: Boolean = true,
        steps: (L04Device) -> Unit,
    ) {
        val device = L04Device()
        val lock = makeLock(device)
        if (record) lock.onBackground()
        steps(device)
        val locked = lock.shouldLockOnForeground()
        val text = if (locked) "鎖定" else "不鎖"
        if (locked == expected) {
            log("$name：$text ✓")
        } else {
            wrong++
            log("$name：$text ✗（應該${if (expected) "鎖定" else "不鎖"}）")
        }
    }
    check("A 離開 30 秒", expected = false) { it.pass(30_000) }
    check("B 離開 5 分鐘", expected = true) { it.pass(5 * MINUTE) }
    check("C 離開 5 分鐘，系統時間往回調 1 小時", expected = true) {
        it.pass(2 * MINUTE)
        it.userChangesSystemTime(-60 * MINUTE)
        it.pass(3 * MINUTE)
    }
    check("D 離開 10 秒，系統時間往前跳 1 天", expected = false) {
        it.pass(5_000)
        it.userChangesSystemTime(24 * 60 * MINUTE)
        it.pass(5_000)
    }
    check("E 沒有進背景的記錄", expected = true, record = false) { it.pass(1_000) }
    log("5 個情境，判斷錯的有 $wrong 個")
}

// MARK: - Bug 版
private class L04BugAutoLock(private val device: L04Device) : L04AutoLock {
    private var backgroundedAt: Long? = null

    override fun onBackground() {
        backgroundedAt = device.wallMs // 用系統時間記錄
    }

    override fun shouldLockOnForeground(): Boolean {
        // 沒有記錄就當作要鎖
        val start = backgroundedAt ?: return true
        // 系統時間被改過，這個差就不準了
        return device.wallMs - start >= TIMEOUT_MS
    }
}

private object L04Bug {
    suspend fun run(log: LabLog) = scenario(log) { L04BugAutoLock(it) }
}

// MARK: - 修正版
private class L04FixedAutoLock(private val device: L04Device) : L04AutoLock {
    private var backgroundedAt: Long? = null

    override fun onBackground() {
        // 用單調時鐘記錄（真實的 App：SystemClock.elapsedRealtime()）
        backgroundedAt = device.elapsedMs
    }

    override fun shouldLockOnForeground(): Boolean {
        // 沒有記錄就當作要鎖
        val start = backgroundedAt ?: return true
        return device.elapsedMs - start >= TIMEOUT_MS
    }
}

private object L04Fixed {
    suspend fun run(log: LabLog) = scenario(log) { L04FixedAutoLock(it) }
}

// MARK: - 演練設定
private fun wrongCount(lines: List<String>): Int? = lines.firstOrNull {
    it.startsWith("5 個情境")
}?.let { Regex("(\\d+) 個$").find(it)?.groupValues?.get(1)?.toInt() }

val l04 = Lab(
    id = "L04",
    title = "把系統時間往回調，就繞過了自動鎖定",
    question = "第 4 題：自動鎖定（auto-lock）要做到什麼程度，才不只是「蓋一個畫面」？",
    goal = "自動鎖定的計時要用單調時鐘（只會往前走、不受修改系統時間影響）。用系統時間計算「離開多久」，使用者往回調時間就能永遠不被鎖，往前跳則會被誤鎖。",
    steps = listOf(
        "選「Bug 版」執行，看五個情境哪些判斷錯了：往回調的 C 和往前跳的 D。",
        "想一想：系統時間往回調 1 小時，「現在減掉進背景的時間」會是正的還是負的？",
        "選「修正版」執行，五個情境都要判斷對。",
        "進階：Handler.postDelayed 用的是 uptimeMillis，裝置睡眠時不計時，也不適合算「離開多久」；要用 SystemClock.elapsedRealtime()（含睡眠）。",
    ),
    expectBug = "「C 離開 5 分鐘，系統時間往回調 1 小時：不鎖 ✗」「D 離開 10 秒，系統時間往前跳 1 天：鎖定 ✗」，A、B、E 是對的：「5 個情境，判斷錯的有 2 個」。",
    expectFixed = "五個情境都是 ✓，「5 個情境，判斷錯的有 0 個」。",
    file = "exchange10-account-security/L04_AutoLockClock.kt",
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
                "演練沒有跑完：要用 scenario(log) { device -> … } 執行"
            }

            "B 離開 5 分鐘：鎖定 ✓" !in lines -> {
                "Bug 版在系統時間沒被改的時候要算對：離開 5 分鐘要鎖定"
            }

            "C 離開 5 分鐘，系統時間往回調 1 小時：不鎖 ✗（應該鎖定）" !in lines -> {
                "Bug 還沒重現：用系統時間算，往回調 1 小時之後「現在減掉進背景的時間」是負的，不會鎖"
            }

            "D 離開 10 秒，系統時間往前跳 1 天：鎖定 ✗（應該不鎖）" !in lines -> {
                "Bug 還沒重現：用系統時間算，往前跳 1 天之後只離開 10 秒也會被鎖"
            }

            n != 2 -> {
                "Bug 版應該判斷錯 2 個情境（C 和 D），目前 $n 個"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        val n = wrongCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log) { device -> … } 執行"
            n != 0 -> "還有 $n 個情境判斷錯：進背景和回前景都要用 device.elapsedMs（單調時鐘），不要用 wallMs"
            else -> null
        }
    },
)
