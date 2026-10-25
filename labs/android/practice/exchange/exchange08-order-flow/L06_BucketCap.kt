// L06：App 閒置一分鐘，回來一次打出 600 個請求，伺服器全部拒絕（token bucket
// 沒有封頂）
// 對應複習站：Exchange08OrderFlow 第 6 題「怎麼用 token bucket 做限流？」
//
// 情境：限流器是 token bucket：容量 10、每秒補 10 個 token，一個請求用掉 1 個。
// 為了避免浮點數誤差，用整數：以「千分之一個 token」為單位，
//        每過 1 毫秒補 `refillPerSecond`（10）個千分之一 token；取得 1 個
// token 要 1000 個。時間從參數傳進來（注入時鐘）。
//        演練：0 毫秒時把桶裡的 token 用光（成功 10 次）；閒置 60 秒；60,000
// 毫秒時在同一毫秒連續取 token，看成功幾次。
//        正確答案：桶的容量是 10，閒置再久，一次最多只能突發 10 個請求。
//
// 你要寫：
//   ① Bug 版（L06Bug.Bucket.refill）：elapsed = nowMs - lastMs；milliTokens +=
// elapsed * refillPerSecond（沒有封頂）；lastMs = nowMs。
//   ② 修正版（L06Fixed.Bucket.refill）：elapsed 時鐘倒退用 0（maxOf(0L, …)）；
// milliTokens = minOf(capacity * 1000, milliTokens + elapsed *
// refillPerSecond)；lastMs = nowMs。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange08-order-flow/L06_BucketCap.kt
package tw.harry.labs.practice.exchange08orderflow

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 一個 token bucket：容量 capacity，每秒補 refillPerSecond 個。`refill`
 * 由兩個版本各自實作。
 */
private open class L06TokenBucket(
    val capacity: Long,
    val refillPerSecond: Long,
    startMs: Long,
) {
    // 千分之一個 token；1 個 token = 1000
    var milliTokens: Long = capacity * 1000

    // 上次補充的時間（毫秒）
    var lastMs: Long = startMs

    fun tryAcquire(nowMs: Long): Boolean {
        refill(nowMs)
        if (milliTokens < 1000) return false
        milliTokens -= 1000
        return true
    }

    open fun refill(nowMs: Long) {}
}

/** 演練流程（兩個版本共用）：用光 → 閒置 60 秒 → 同一毫秒連續取 */
private fun scenario(log: LabLog, bucket: L06TokenBucket) {
    fun drain(ms: Long): Int {
        var count = 0
        while (count < 1000 && bucket.tryAcquire(ms)) count++
        return count
    }
    log("桶的容量是 ${bucket.capacity}，每秒補 ${bucket.refillPerSecond} 個")
    val first = drain(0)
    log("0 毫秒：連續取 token，成功 $first 次，桶空了")
    val burst = drain(60_000)
    log("閒置 60 秒之後（60,000 毫秒）：同一毫秒連續取 token，成功 $burst 次")
    log("最後：閒置之後一次最多成功 $burst 次（正確答案：10 次）")
}

// MARK: - Bug 版
private object L06Bug {
    class Bucket :
        L06TokenBucket(capacity = 10, refillPerSecond = 10, startMs = 0) {
        override fun refill(nowMs: Long) {
            // TODO ①：elapsed = nowMs - lastMs；milliTokens += elapsed *
            // refillPerSecond（沒有封頂）；lastMs = nowMs
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Bucket())
}

// MARK: - 修正版
private object L06Fixed {
    class Bucket :
        L06TokenBucket(capacity = 10, refillPerSecond = 10, startMs = 0) {
        override fun refill(nowMs: Long) {
            // TODO ②：elapsed = maxOf(0L, nowMs - lastMs)（時鐘倒退不補充）；
            // milliTokens = minOf(capacity * 1000, milliTokens + elapsed *
            // refillPerSecond)（補充之後封頂）；
            //        lastMs = nowMs
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Bucket())
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "閒置一分鐘，回來一次打出 600 個請求",
    question = "第 6 題：怎麼用 token bucket 做限流？",
    goal = "token bucket 的容量決定允許的突發量：補充 token 之後一定要用容量封頂，不然閒置越久、累積的 token 越多，一次可以打出遠超過限制的請求。先寫出補充 token 沒有封頂的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange08-order-flow/L06_BucketCap.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看閒置 60 秒之後，同一毫秒連續取 token 成功了幾次。",
        "想一想：每秒補 10 個，閒置 60 秒累積了多少？這套 API 每個 API key 每秒只能 10 次，一次打 600 個會怎樣？",
        "寫 TODO ②（修正版），選「修正版」執行，閒置之後一次最多只能成功 10 次（桶的容量）。",
        "進階：時鐘倒退（現在的時間比上次記錄的早）時不補充，也不能讓之後的補充算錯。",
    ),
    expectBug = "閒置 60 秒之後成功 600 次：「最後：閒置之後一次最多成功 600 次（正確答案：10 次）」。",
    expectFixed = "閒置 60 秒之後只成功 10 次：「最後：閒置之後一次最多成功 10 次（正確答案：10 次）」。",
    file = "exchange08-order-flow/L06_BucketCap.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, bucket) 執行"
            }

            lines.none { it.startsWith("最後：閒置之後一次最多成功 600 次") } -> {
                "Bug 還沒重現：refill 要用 milliTokens += elapsed * refillPerSecond（沒有封頂），再把 lastMs 換成 nowMs"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> {
                "演練沒有跑完：要用 scenario(log, bucket) 執行"
            }

            lines.none { it.startsWith("最後：閒置之後一次最多成功 10 次") } -> {
                "refill 要算出經過的毫秒（時鐘倒退用 0），milliTokens 用 minOf(capacity * 1000, …) 封頂，再把 lastMs 換成 nowMs"
            }

            else -> {
                null
            }
        }
    },
)
