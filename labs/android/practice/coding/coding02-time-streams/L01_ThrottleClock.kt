// L01：防連點的測試要真的等 300 毫秒才跑得完（函式裡直接讀 System.currentTimeMillis()）
// 對應複習站：Coding02TimeStreams 第 1 題「和時間有關的題目，為什麼要把時間當成參數傳進去？」
//
// 情境：「買入」按鈕防連點：第一次點擊立刻送出，之後 100 毫秒內的點擊都忽略（throttle，送第一個）。
//       測試要驗證這組點擊：第 0、30、150、300 毫秒各點一次，預期結果是 [送出, 忽略, 送出, 送出]。
//
// 你要寫：
//   ① Bug 版（L01Bug.Throttle.shouldFire）：在函式裡用 System.currentTimeMillis() 讀現在時間；距離上一次送出不到 intervalMs 毫秒就回傳 false，
//      否則記下這次的時間、回傳 true。跑起來會看到測試要真的等 300 多毫秒。
//   ② 修正版（L01Fixed.Throttle.shouldFire）：不能讀系統時間，改用建構子傳進來的 now() 取得現在幾毫秒，其餘邏輯一樣。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/coding/coding02-time-streams/L01_ThrottleClock.kt
package tw.harry.labs.practice.coding02timestreams

import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val taps = listOf(0, 30, 150, 300)
private const val INTERVAL_MS = 100

private fun text(results: List<Boolean>): String = results.joinToString(", ", "[", "]") { if (it) "送出" else "忽略" }

/**
 * 演練流程（兩個版本共用）：到了每個點擊的時間就點一下，記錄結果與測試花了多久
 * advanceTo：把時間走到某個毫秒（Bug 版真的等，修正版撥假時鐘）
 */
private suspend fun scenario(log: LabLog, advanceTo: suspend (Int) -> Unit, tap: () -> Boolean) {
    val start = System.nanoTime()
    val results = ArrayList<Boolean>()
    for (time in taps) {
        advanceTo(time)
        results.add(tap())
    }
    val ms = (System.nanoTime() - start) / 1_000_000
    log("點擊時間 $taps 毫秒 → ${text(results)}")
    log("這個測試花了 $ms 毫秒")
}

/** 從「這個測試花了 N 毫秒」取出 N（自動檢查用） */
private fun elapsedMs(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("這個測試花了 ") }?.removePrefix("這個測試花了 ")?.removeSuffix(" 毫秒")?.toIntOrNull()

// MARK: - Bug 版
private object L01Bug {
    class Throttle(private val intervalMs: Int) {
        private var lastFire: Long? = null

        fun shouldFire(): Boolean {
            // TODO ①-1：val now = System.currentTimeMillis()，現在時間直接在函式裡讀
            // TODO ①-2：已經有 lastFire，而且 now - last 小於 intervalMs：回傳 false（忽略這次點擊）
            // TODO ①-3：否則記下 lastFire = now，回傳 true
            return true
        }
    }

    suspend fun run(log: LabLog) {
        val throttle = Throttle(INTERVAL_MS)
        val start = System.nanoTime()
        scenario(log, advanceTo = { time ->
            val elapsed = ((System.nanoTime() - start) / 1_000_000).toInt()
            delay(maxOf(0, time - elapsed).toLong())                // 測試只能真的等
        }, tap = { throttle.shouldFire() })
    }
}

// MARK: - 修正版
private object L01Fixed {
    class Throttle(private val intervalMs: Int, private val now: () -> Int) {   // 「現在幾毫秒」由外面傳進來
        private var lastFire: Int? = null

        fun shouldFire(): Boolean {
            // TODO ②-1：val current = now()，用傳進來的函式取得現在幾毫秒（不要呼叫 System.currentTimeMillis()）
            // TODO ②-2：已經有 lastFire，而且 current - last 小於 intervalMs：回傳 false
            // TODO ②-3：否則記下 lastFire = current，回傳 true
            return true
        }
    }

    suspend fun run(log: LabLog) {
        val clock = C02FakeClock()
        val throttle = Throttle(INTERVAL_MS) { clock.now }
        scenario(log, advanceTo = { clock.advanceTo(it) }, tap = { throttle.shouldFire() })   // 假時鐘：直接撥時間
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "防連點的測試要真的等 300 毫秒",
    question = "第 1 題：和時間有關的題目，為什麼要把時間當成參數傳進去？",
    goal = "把「現在時間」變成輸入（注入的函式或參數），測試就不用真的等，結果也不會因為機器忙碌而時對時錯。先寫出自己讀系統時間的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/coding/coding02-time-streams/L01_ThrottleClock.kt，寫 TODO ①（Bug 版）。",
        "選「練習」「Bug 版」執行，看最後一行「這個測試花了幾毫秒」；多按幾次，每次的數字一樣嗎？",
        "寫 TODO ②（修正版），選「練習」「修正版」執行，比較花的時間與點擊結果。",
        "想一想：如果要測「300 毫秒」改成「30 秒」的防連點，Bug 版的測試要等多久？",
    ),
    expectBug = "結果 [送出, 忽略, 送出, 送出]，但測試花了 300 多毫秒（每次略有不同），因為真的在等。",
    expectFixed = "結果一樣是 [送出, 忽略, 送出, 送出]，測試花的時間不到 10 毫秒。",
    file = "coding02-time-streams/L01_ThrottleClock.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = elapsedMs(lines)
        when {
            ms == null -> "演練沒有跑完：要用 scenario(log, advanceTo, tap) 執行"
            "點擊時間 [0, 30, 150, 300] 毫秒 → [送出, 忽略, 送出, 送出]" !in lines ->
                "shouldFire 還沒寫對：用 System.currentTimeMillis() 記下上一次送出的時間，距離不到 100 毫秒就回傳 false"
            ms < 250 -> "Bug 還沒重現：Throttle 要在函式裡自己讀 System.currentTimeMillis()，測試才需要真的等"
            else -> null
        }
    },
    checkFixed = { lines ->
        val ms = elapsedMs(lines)
        when {
            ms == null -> "演練沒有跑完：要用 scenario(log, advanceTo, tap) 執行"
            "點擊時間 [0, 30, 150, 300] 毫秒 → [送出, 忽略, 送出, 送出]" !in lines ->
                "shouldFire 還沒寫對：用 now() 取得現在時間，距離上一次送出不到 100 毫秒就回傳 false"
            ms >= 100 -> "測試花了 $ms 毫秒：Throttle 不能自己讀系統時間，要用傳進來的 now()，測試用假時鐘撥時間"
            else -> null
        }
    },
)
