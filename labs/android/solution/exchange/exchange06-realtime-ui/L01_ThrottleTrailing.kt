// L01：行情停了，畫面卻停在幾百毫秒前的價格（降低更新頻率時，最後一筆沒有送出去）
// 對應複習站：Exchange06RealtimeUI 第 3 題「畫面多久更新一次？用什麼來驅動？」
//
// 情境：行情每 30 毫秒來一筆價格（價格 = 100 + 第幾筆），從 0 毫秒到 330 毫秒共 12 筆，最新價是 111。畫面每 100 毫秒更新一次（降頻）。
//        0、100、200、300 毫秒，畫面的計時器各響一次；330 毫秒是最後一筆，之後行情停了，計時器也跟著停。
// Bug 版：「進來的價格，距離上次送給畫面超過 100 毫秒才送，不到 100 毫秒就直接丟掉」。中間的價格丟掉沒關係，但最後一筆剛好落在 100 毫秒之內，
//        也被丟掉，行情停了之後沒有人再送：畫面停在 108，最新價是 111。
// 修正版：ThrottleLatest：進來的價格只記下最新的（蓋掉還沒送的），由計時器響的時候才送，而且距離上次送出超過 100 毫秒才送；
//        串流結束時，把還沒送出的最新值取走（flush）。
package tw.harry.labs.solution.exchange06realtimeui

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private interface L01PriceThrottle {
    /** 一則價格到達（背景）。回傳有值代表立刻送給畫面 */
    fun onValue(value: Int, nowMs: Long): Int?

    /** 畫面的計時器響了（每 100 毫秒一次） */
    fun onTick(nowMs: Long): Int?

    /** 串流結束：把還沒送出的值取走 */
    fun onEnd(): Int?
}

private const val INTERVAL_MS = 100L

/** 演練流程（兩個版本共用）：價格每 30 毫秒一筆，計時器每 100 毫秒響一次（同一毫秒時，價格先到） */
private fun scenario(log: LabLog, throttle: L01PriceThrottle) {
    var shown: Int? = null
    fun show(value: Int?, ms: Long) {
        if (value == null) return
        shown = value
        log("t=$ms：畫面顯示 $value")
    }
    val events = (0..11).map { it * 30L to true } + listOf(0L, 100L, 200L, 300L).map { it to false }
    for ((ms, isValue) in events.sortedWith(compareBy({ it.first }, { if (it.second) 0 else 1 }))) {
        if (isValue) show(throttle.onValue(100 + (ms / 30).toInt(), ms), ms) else show(throttle.onTick(ms), ms)
    }
    show(throttle.onEnd(), 330)
    log("最後：畫面顯示 ${shown ?: "-"}，最新價是 111")
}

// MARK: - Bug 版
private object L01Bug {
    class Throttle : L01PriceThrottle {
        private var lastEmitMs: Long? = null

        override fun onValue(value: Int, nowMs: Long): Int? {
            val last = lastEmitMs
            if (last != null && nowMs - last < INTERVAL_MS) return null     // 距離上次送出不到 100 毫秒：直接丟掉
            lastEmitMs = nowMs
            return value
        }

        override fun onTick(nowMs: Long): Int? = null
        override fun onEnd(): Int? = null
    }

    suspend fun run(log: LabLog) = scenario(log, Throttle())
}

// MARK: - 修正版
private object L01Fixed {
    class Throttle : L01PriceThrottle {
        private var pending: Int? = null
        private var lastEmitMs: Long? = null

        override fun onValue(value: Int, nowMs: Long): Int? {
            pending = value                                                 // 只記下最新的，蓋掉還沒送出的
            return null
        }

        override fun onTick(nowMs: Long): Int? {
            val value = pending ?: return null                              // 沒有待送的值：不送
            val last = lastEmitMs
            if (last != null && nowMs - last < INTERVAL_MS) return null     // 還不到 100 毫秒：待送的值留到下次
            pending = null
            lastEmitMs = nowMs
            return value
        }

        override fun onEnd(): Int? {                                        // 串流結束：把尾端還沒送出的值取走
            val value = pending
            pending = null
            return value
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Throttle())
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "行情停了，畫面停在舊價格",
    question = "第 3 題：畫面多久更新一次？用什麼來驅動？",
    goal = "降低更新頻率時，中間的價格可以丟掉，但「最新的一筆」不能丟：先記下最新值，由計時器固定節奏送出，串流結束時再把尾端還沒送出的值取走。",
    steps = listOf(
        "選「Bug 版」執行，看畫面一路顯示了哪些價格，最後停在哪裡？最新價是多少？",
        "想一想：最後一筆 111 在 330 毫秒到達，距離上次送出（240 毫秒）才 90 毫秒，被丟掉之後，還有誰會再送一次？",
        "選「修正版」執行，畫面最後要顯示 111。",
        "進階：時鐘倒退（現在的時間比上次送出的時間還早）時該怎麼辦？規則是不送出，待送的值保留。",
    ),
    expectBug = "畫面依序顯示 100、104、108，「最後：畫面顯示 108，最新價是 111」：最後三筆價格被丟掉，行情停了就沒有人再更新。",
    expectFixed = "畫面依序顯示 100、103、106、110，串流結束再顯示 111：「最後：畫面顯示 111，最新價是 111」。",
    file = "exchange06-realtime-ui/L01_ThrottleTrailing.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, throttle) 執行"
            "t=120：畫面顯示 104" !in lines -> "Bug 版的 onValue 要「距離上次送出超過 100 毫秒才送出」：沒有上次送出的紀錄，或已經過了 100 毫秒，就更新 lastEmitMs 並回傳這個價格"
            "最後：畫面顯示 108，最新價是 111" !in lines -> "Bug 還沒重現：不到 100 毫秒的價格直接丟掉（回傳 null），onTick、onEnd 都不送，最後一筆 111 就送不出去"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, throttle) 執行"
            "t=100：畫面顯示 103" !in lines -> "onValue 只記下最新的值（pending = value）；onTick 在計時器響的時候送出 pending，t=100 要顯示 103"
            "最後：畫面顯示 111，最新價是 111" !in lines -> "onEnd 要把還沒送出的 pending 取走（flush）並清掉，不然最後一筆 111 送不出去"
            else -> null
        }
    },
)
