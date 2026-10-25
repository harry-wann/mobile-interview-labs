// L06：兩個執行緒同時第一次用，建立了兩條連線（lazy 的執行緒模式）
// 對應複習站：Kotlin02Functions 第 5 題「lazy
// 的三種模式差在哪？初始化丟例外會怎樣？」
//
// 情境：Gateway 用 by lazy 延後建立交易所的連線，建立一次要 100 ms。
// 畫面載入時，行情和下單兩個地方
//        同時（在不同的執行緒）第一次用到 socket。
// Bug 版：為了「省掉鎖的成本」寫 by lazy(LazyThreadSafetyMode.NONE)。NONE
// 完全不保證執行緒安全，
//        兩個執行緒都看到「還沒初始化」，各自執行一次初始化器，建立了兩條連線，
// 其中一條沒有人管，洩漏。
// 修正版：用預設的 by lazy { }（SYNCHRONIZED）。加鎖，初始化器最多只執行一次，
// 兩個執行緒拿到同一條連線。
package tw.harry.labs.solution.kotlin02functions

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 建立連線要 100 ms，並記下建立了幾條 */
private class Dialer(private val log: LabLog) {
    val created = AtomicInteger(0)

    fun connect(): String {
        val n = created.incrementAndGet()
        log("建立連線 #$n")
        Thread.sleep(100)
        return "socket-$n"
    }
}

/** 演練流程（兩個版本共用）：行情和下單同時第一次用 socket */
private suspend fun scenario(
    log: LabLog,
    dialer: Dialer,
    socket: () -> String,
) {
    withContext(Dispatchers.Default) {
        coroutineScope {
            val market = async { socket() }
            val trading = async { socket() }
            log("行情拿到 ${market.await()}，下單拿到 ${trading.await()}")
        }
    }
    log("共建立 ${dialer.created.get()} 條連線")
}

// MARK: - Bug 版
private object L06Bug {
    class Gateway(dialer: Dialer) {
        // NONE：沒有任何鎖
        val socket by lazy(LazyThreadSafetyMode.NONE) { dialer.connect() }
    }

    suspend fun run(log: LabLog) {
        val dialer = Dialer(log)
        val gateway = Gateway(dialer)
        scenario(log, dialer) { gateway.socket }
    }
}

// MARK: - 修正版
private object L06Fixed {
    class Gateway(dialer: Dialer) {
        // 預設 SYNCHRONIZED：初始化器最多執行一次
        val socket by lazy { dialer.connect() }
    }

    suspend fun run(log: LabLog) {
        val dialer = Dialer(log)
        val gateway = Gateway(dialer)
        scenario(log, dialer) { gateway.socket }
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "兩個執行緒同時第一次用，建了兩條連線",
    question = "第 5 題：lazy 的三種模式差在哪？初始化丟例外會怎樣？",
    goal = "行情和下單同時第一次用 lazy 的 socket。看 NONE 模式為什麼會建立兩條連線。",
    steps = listOf(
        "選「Bug 版」執行，看「建立連線」出現幾次、兩邊拿到的是不是同一條。",
        "選「修正版」執行，確認只建立一條連線，兩邊拿到同一條。",
        "把修正版改成 lazy(LazyThreadSafetyMode.PUBLICATION) 再執行：最後拿到的會是同一個值，但「建立連線」會出現幾次？",
    ),
    expectBug = "「建立連線 #1」「建立連線 #2」都出現；行情與下單拿到不同的 socket；「共建立 2 條連線」。",
    expectFixed = "只有「建立連線 #1」；兩邊都拿到 socket-1；「共建立 1 條連線」。",
    file = "kotlin02-functions/L06_LazyThreadMode.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("共建立 ")
            } -> "演練沒有跑完：兩邊都要讀 gateway.socket"

            "共建立 2 條連線" !in lines -> "Bug 版要建立兩條連線：socket 用 by lazy(LazyThreadSafetyMode.NONE)"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("共建立 ")
            } -> "演練沒有跑完：兩邊都要讀 gateway.socket"

            "共建立 1 條連線" !in lines -> "還是建立了不只一條連線：socket 改用預設的 by lazy { }"

            "行情拿到 socket-1，下單拿到 socket-1" !in lines -> "兩邊要拿到同一條連線 socket-1"

            else -> null
        }
    },
)
