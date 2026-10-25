// L06：使用者把手機時間調早一小時，20 秒前的價格被當成最新的（過期判斷用了系統時間）
// 對應複習站：Exchange04Reconnect 第 6 題「斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？」
//
// 情境：App 記錄「最後收到資料的時間」，超過 5 秒沒有新資料，就把價格變灰，並阻止用這個價格下市價單（成行）。
//        演練用一個假的時鐘，同時有「系統時間」（使用者可以改）和「單調時鐘」（只會往前走，不受使用者修改影響）。
//        兩種情況：
//        ① 收到資料後，連線斷了，過了 20 秒，使用者（或系統自動校時）把系統時間調早一小時，這時價格已經是 20 秒前的舊價格；
//        ② 剛收到新資料，使用者把系統時間調晚一小時。
// Bug 版：用系統時間（System.currentTimeMillis）算「距離上次收到資料過了幾秒」。① 算出來是 −3580 秒，不到 5 秒，舊價格被當成最新的，下單按鈕還開著；
//        ② 算出來是 3600 秒，剛收到的資料被誤判成過期。
// 修正版：用單調時鐘（Android 的 SystemClock.elapsedRealtime、iOS 的 ProcessInfo.systemUptime）算，不受系統時間修改的影響。
package tw.harry.labs.solution.exchange04reconnect

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的時鐘：系統時間可以被使用者改，單調時鐘只會往前走 */
private class FakeClock {
    var systemMs = 1_700_000_000_000L
        private set
    var monotonicMs = 100_000L
        private set

    fun advance(ms: Long) { systemMs += ms; monotonicMs += ms }
    fun userChangesSystemTime(ms: Long) { systemMs += ms }      // 只動系統時間，單調時鐘不受影響
}

private interface FreshnessTracker {
    fun didReceiveData(clock: FakeClock)
    fun isStale(clock: FakeClock): Boolean
}

/** 演練流程（兩個版本共用）：兩種情況各判斷一次，看有沒有判斷錯 */
private fun scenario(log: LabLog, makeTracker: () -> FreshnessTracker) {
    var wrong = 0

    val a = makeTracker()
    val clockA = FakeClock()
    a.didReceiveData(clockA)
    clockA.advance(20_000)                                      // 連線斷了，20 秒沒有新資料
    clockA.userChangesSystemTime(-3_600_000)                    // 使用者把系統時間調早一小時
    val staleA = a.isStale(clockA)
    log("① 20 秒沒有新資料，系統時間被調早一小時：價格${if (staleA) "已過期，變灰並擋下市價單" else "被當成最新的，下單按鈕還開著"}")
    if (!staleA) wrong++

    val b = makeTracker()
    val clockB = FakeClock()
    clockB.advance(1_000)
    b.didReceiveData(clockB)
    clockB.userChangesSystemTime(3_600_000)                     // 使用者把系統時間調晚一小時
    val staleB = b.isStale(clockB)
    log("② 剛收到新資料，系統時間被調晚一小時：價格${if (staleB) "被誤判成過期，下單被擋下" else "是最新的，可以下單"}")
    if (staleB) wrong++

    log("2 種情況裡，過期判斷錯誤 $wrong 次")
}

// MARK: - Bug 版
private object L06Bug {
    class Tracker : FreshnessTracker {
        private var lastDataAt: Long? = null

        override fun didReceiveData(clock: FakeClock) {
            lastDataAt = clock.systemMs                         // 用系統時間記
        }

        override fun isStale(clock: FakeClock): Boolean {
            val last = lastDataAt ?: return true
            return clock.systemMs - last >= 5_000               // 用系統時間算
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::Tracker)
}

// MARK: - 修正版
private object L06Fixed {
    class Tracker : FreshnessTracker {
        private var lastDataAt: Long? = null

        override fun didReceiveData(clock: FakeClock) {
            lastDataAt = clock.monotonicMs                      // 用單調時鐘記
        }

        override fun isStale(clock: FakeClock): Boolean {
            val last = lastDataAt ?: return true                // 從來沒收到過，也算過期
            return clock.monotonicMs - last >= 5_000            // 用單調時鐘算
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::Tracker)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "手機時間調早一小時，舊價格被當成最新的",
    question = "第 6 題：斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？",
    goal = "判斷價格是不是過期，要用單調時鐘計時，不受使用者修改系統時間的影響。過期的價格要擋下市價單。",
    steps = listOf(
        "選「Bug 版」執行，看兩種情況各判斷成什麼、有沒有判斷錯。",
        "想一想：使用者把系統時間調早一小時，「現在 − 最後收到資料的時間」會變成多少？",
        "選「修正版」執行，兩種情況都要判斷對。",
        "進階：Android 的 SystemClock.elapsedRealtime() 包含裝置睡眠的時間，uptimeMillis() 不包含；App 進背景時已經主動斷線，回前景一律先標示過期。顯示橫幅前先等 2 到 3 秒的寬限時間，避免短暫抖動讓畫面閃爍。",
    ),
    expectBug = "① 被當成最新的（距離上次 −3580 秒，不到 5 秒），下單按鈕還開著；② 被誤判成過期，「2 種情況裡，過期判斷錯誤 2 次」。",
    expectFixed = "① 已過期，變灰並擋下市價單；② 是最新的，可以下單，「2 種情況裡，過期判斷錯誤 0 次」。",
    file = "exchange04-reconnect/L06_MonotonicClock.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("2 種情況裡") } -> "演練沒有跑完：要用 scenario(log, ::Tracker) 執行"
            "2 種情況裡，過期判斷錯誤 2 次" !in lines -> "Bug 還沒重現：didReceiveData、isStale 都改用 clock.systemMs（系統時間），兩種情況都會判斷錯"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("2 種情況裡") } -> "演練沒有跑完：要用 scenario(log, ::Tracker) 執行"
            "2 種情況裡，過期判斷錯誤 0 次" !in lines -> "didReceiveData、isStale 都要用 clock.monotonicMs（單調時鐘）；從來沒收到過資料也算過期"
            else -> null
        }
    },
)
