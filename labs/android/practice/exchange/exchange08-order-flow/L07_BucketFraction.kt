// L07：一直輪詢卻拿不到 token，等了好幾秒還是 0 個（補充 token 時，整數除法把不到一個的零頭丟掉）
// 對應複習站：Exchange08OrderFlow 第 6 題「怎麼用 token bucket 做限流？」
//
// 情境：限流器是 token bucket：容量 3、每秒補 3 個 token（約每 333 毫秒補一個）。0 毫秒時先把桶用光，之後每 100 毫秒問一次「有沒有 token」，問 30 次（3 秒）。
//        正確答案：每秒補 3 個，3 秒內應該成功 9 次；之後再隔 1 秒一次問 5 次，成功 3 次（桶補滿了）。
//
// 你要寫：
//   ① Bug 版（L07Bug.Bucket.refill）：whole = elapsed * refillPerSecond / 1000（整數 token）；whole > 0 才 milliTokens 加 whole * 1000（再封頂）；lastMs 每次都換成 nowMs。
//   ② 修正版（L07Fixed.Bucket.refill）：以千分之一個 token 為單位：milliTokens = minOf(capacity * 1000, milliTokens + elapsed * refillPerSecond)；lastMs = nowMs。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange08-order-flow/L07_BucketFraction.kt
package tw.harry.labs.practice.exchange08orderflow

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 一個 token bucket：容量 capacity，每秒補 refillPerSecond 個。`refill` 由兩個版本各自實作。 */
private open class L07TokenBucket(val capacity: Long, val refillPerSecond: Long, startMs: Long) {
    var milliTokens: Long = capacity * 1000     // 千分之一個 token；1 個 token = 1000
    var lastMs: Long = startMs                  // 上次補充的時間（毫秒）

    fun tryAcquire(nowMs: Long): Boolean {
        refill(nowMs)
        if (milliTokens < 1000) return false
        milliTokens -= 1000
        return true
    }

    open fun refill(nowMs: Long) {}
}

/** 演練流程（兩個版本共用）：用光 → 每 100 毫秒問一次，共 3 秒 → 隔 1 秒一次問 5 次 */
private fun scenario(log: LabLog, bucket: L07TokenBucket) {
    var initial = 0
    while (bucket.tryAcquire(0)) initial++
    log("0 毫秒：把桶用光，成功 $initial 次")
    var polled = 0
    for (ms in 100L..3000L step 100L) if (bucket.tryAcquire(ms)) polled++
    log("接著每 100 毫秒問一次，3 秒內成功 $polled 次（正確答案：9 次）")
    var later = 0
    repeat(5) { if (bucket.tryAcquire(4000)) later++ }
    log("再隔 1 秒（4,000 毫秒）一次問 5 次，成功 $later 次（正確答案：3 次）")
    log("最後：每 100 毫秒問一次，3 秒內成功 $polled 次；隔 1 秒一次問，成功 $later 次")
}

// MARK: - Bug 版
private object L07Bug {
    class Bucket : L07TokenBucket(capacity = 3, refillPerSecond = 3, startMs = 0) {
        override fun refill(nowMs: Long) {
            // TODO ①：elapsed = nowMs - lastMs；whole = elapsed * refillPerSecond / 1000（只算整數個 token）；
            //        whole > 0 才 milliTokens = minOf(capacity * 1000, milliTokens + whole * 1000)；不管有沒有補，lastMs 每次都換成 nowMs
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Bucket())
}

// MARK: - 修正版
private object L07Fixed {
    class Bucket : L07TokenBucket(capacity = 3, refillPerSecond = 3, startMs = 0) {
        override fun refill(nowMs: Long) {
            // TODO ②：elapsed = maxOf(0L, nowMs - lastMs)；milliTokens = minOf(capacity * 1000, milliTokens + elapsed * refillPerSecond)
            //        （以千分之一個 token 為單位，不丟零頭）；lastMs = nowMs
        }
    }

    suspend fun run(log: LabLog) = scenario(log, Bucket())
}

// MARK: - 演練設定
val l07 = Lab(
    id = "L07",
    title = "一直輪詢，卻拿不到 token",
    question = "第 6 題：怎麼用 token bucket 做限流？",
    goal = "補充 token 要用整數的最小單位（千分之一個 token）累積，不能每次都把不到一個 token 的零頭丟掉，同時把上次補充的時間往前移。先寫出用整數 token 補充、丟掉零頭的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange08-order-flow/L07_BucketFraction.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看每 100 毫秒問一次，3 秒內成功幾次？隔 1 秒再一次問 5 次呢？",
        "想一想：每秒補 3 個，100 毫秒應該補 0.3 個。用整數 100 × 3 ÷ 1000 算出來是多少？剩下的 0.3 去哪了？",
        "寫 TODO ②（修正版），選「修正版」執行，3 秒內成功 9 次，隔 1 秒再問成功 3 次。",
        "進階：向上取整要等多久：每秒補 3 個，補一個要 1000 ÷ 3 = 333.33… 毫秒，所以是 334 毫秒；等 333 毫秒還不夠。",
    ),
    expectBug = "每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問才成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問，成功 3 次」。",
    expectFixed = "3 秒內成功 9 次，隔 1 秒成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 9 次；隔 1 秒一次問，成功 3 次」。",
    file = "exchange08-order-flow/L07_BucketFraction.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, bucket) 執行"
            "最後：每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問，成功 3 次" !in lines ->
                "Bug 還沒重現：refill 用整數 token（elapsed * refillPerSecond / 1000），不到 1 個就不補，但 lastMs 每次都換成 nowMs"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("最後") } -> "演練沒有跑完：要用 scenario(log, bucket) 執行"
            "最後：每 100 毫秒問一次，3 秒內成功 9 次；隔 1 秒一次問，成功 3 次" !in lines ->
                "milliTokens 以千分之一個 token 為單位累積：milliTokens + elapsed * refillPerSecond，再用 minOf(capacity * 1000, …) 封頂，lastMs 換成 nowMs"
            else -> null
        }
    },
)
