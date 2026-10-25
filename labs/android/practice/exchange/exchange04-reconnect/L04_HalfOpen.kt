// L04：走出電梯，行情停在 9 秒前的價格，卻沒有重連（半開連線，App 自己送出的 ping 騙了監看）
// 對應複習站：Exchange04Reconnect 第 4 題「系統說「有網路」就真的連得上嗎？什麼是半開連線？」
//
// 情境：半開連線：連線在 App 這端看起來還開著，但對方其實已經收不到你、你也收不到資料；系統不會通知，也不會觸發 onClosed 的回呼。
//        App 每 5 秒主動送一次 ping（寫進本機的傳送緩衝就算「送出成功」，半開的連線也一樣）。
//        演練用虛擬的時間，一秒一秒跑 60 秒：前 10 秒（第 0 到 9 秒）每秒收到一則行情，之後什麼都收不到。
//        應用層的監看：超過 15 秒沒有「活動」，就主動關閉連線並重連。
//
// 你要寫：
//   ① Bug 版（L04Bug.Dog）：送出（didSend）和收到（didReceive）都更新 lastActivityMs；isDead = 現在 − lastActivityMs > 15000。
//   ② 修正版（L04Fixed.Dog）：只有收到（didReceive）才更新 lastActivityMs，didSend 什麼都不做；isDead 一樣。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange04-reconnect/L04_HalfOpen.kt
package tw.harry.labs.practice.exchange04reconnect

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface Watchdog {
    fun didSend(nowMs: Long)
    fun didReceive(nowMs: Long)
    fun isDead(nowMs: Long): Boolean
    val lastActivityMs: Long
}

/** 演練流程（兩個版本共用）：虛擬時間一秒一秒走 */
private fun scenario(log: LabLog, watchdog: Watchdog) {
    var detectedAt: Int? = null
    for (second in 0..60) {
        val now = second * 1000L
        if (second <= 9) watchdog.didReceive(now)               // 前 10 秒每秒收到一則行情
        if (second % 5 == 0) watchdog.didSend(now)              // App 每 5 秒送一次 ping（半開連線也寫得進本機緩衝）
        if (watchdog.isDead(now)) {
            detectedAt = second
            log("第 $second 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連")
            break
        }
    }
    log("最後一次活動是第 ${watchdog.lastActivityMs / 1000} 秒")
    if (detectedAt != null) {
        log("半開連線在第 $detectedAt 秒才被偵測到（行情在第 9 秒就停了，過了 ${detectedAt - 9} 秒才發現）")
    } else {
        log("60 秒內都沒有偵測到：畫面停在第 9 秒的價格，使用者看著 51 秒前的價格")
    }
}

// MARK: - Bug 版
private object L04Bug {
    class Dog : Watchdog {
        override var lastActivityMs = 0L
            private set

        // TODO ①-1：didSend 把 lastActivityMs 設成 nowMs（送出也算活動）
        override fun didSend(nowMs: Long) {}
        // TODO ①-2：didReceive 也把 lastActivityMs 設成 nowMs
        override fun didReceive(nowMs: Long) {}
        // TODO ①-3：isDead：現在（nowMs）− lastActivityMs 超過 15_000 毫秒就回傳 true
        override fun isDead(nowMs: Long) = false
    }

    suspend fun run(log: LabLog) = scenario(log, Dog())
}

// MARK: - 修正版
private object L04Fixed {
    class Dog : Watchdog {
        override var lastActivityMs = 0L
            private set

        override fun didSend(nowMs: Long) {}                                // 送出成功不代表對方收到，不算活動
        // TODO ②-1：didReceive 把 lastActivityMs 設成 nowMs（didSend 維持什麼都不做）
        override fun didReceive(nowMs: Long) {}
        // TODO ②-2：isDead：現在（nowMs）− lastActivityMs 超過 15_000 毫秒就回傳 true
        override fun isDead(nowMs: Long) = false
    }

    suspend fun run(log: LabLog) = scenario(log, Dog())
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "走出電梯，行情停住了卻沒有重連",
    question = "第 4 題：系統說「有網路」就真的連得上嗎？什麼是半開連線？",
    goal = "半開連線系統不會通知。App 要自己監看「有沒有收到伺服器的訊息」，超過一段時間沒收到就主動重連。送出成功不能算成連線還活著。先寫出把送出也算活動的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange04-reconnect/L04_HalfOpen.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看監看有沒有觸發、最後一次活動是第幾秒。",
        "想一想：App 每 5 秒送一次 ping，半開連線的 send 會失敗嗎？它能證明對方還活著嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，看第幾秒偵測到連線已死。",
        "進階：伺服器約每 5 秒送一次 ping，15 秒是三次 ping 的時間（建議值，要依實際測試調整）。監看的計時請用單調時鐘，放在 OkHttp 的背景路徑，不要放在主執行緒；ConnectivityManager 的 onAvailable 只代表系統有路，要看 NET_CAPABILITY_VALIDATED，也不能當作連得上的證明。",
    ),
    expectBug = "60 秒內都沒有偵測到：「最後一次活動是第 60 秒」，畫面停在第 9 秒的價格。",
    expectFixed = "「第 25 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連」，「最後一次活動是第 9 秒」。",
    file = "exchange04-reconnect/L04_HalfOpen.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後一次活動是第") } -> "演練沒有跑完：要用 scenario(log, watchdog) 執行"
            "最後一次活動是第 60 秒" !in lines -> "Bug 還沒重現：didSend 也要更新 lastActivityMs（把送出也算活動），最後一次活動才會是第 60 秒"
            lines.none { it.startsWith("60 秒內都沒有偵測到") } -> "Bug 版的監看永遠不會觸發：isDead 是「現在 − 最後活動時間 > 15000」"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後一次活動是第") } -> "演練沒有跑完：要用 scenario(log, watchdog) 執行"
            "最後一次活動是第 9 秒" !in lines -> "只有 didReceive 更新 lastActivityMs，didSend 什麼都不做"
            "第 25 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連" !in lines -> "isDead 應該是「現在 − 最後活動時間 > 15000」：第 9 秒之後過了 15 秒，第 25 秒才超過"
            else -> null
        }
    },
)
