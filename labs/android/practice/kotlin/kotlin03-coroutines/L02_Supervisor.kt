// L02：一個幣種斷線，整個行情列表都停了（SupervisorJob／supervisorScope）
// 對應複習站：Kotlin03Coroutines 第 2 題「SupervisorJob 和 supervisorScope
// 改變了哪一條規則？」
//
// 情境：行情列表同時訂閱 BTC、ETH、XRP 三個幣種，每個幣種一個 coroutine。XRP
// 的連線在第 3 筆時出錯。
//
// 你要寫：
//   ① Bug 版：三個幣種開在 coroutineScope 裡。跑起來會看到 XRP 一出錯，BTC、
// ETH 也被取消。
//   ② 修正版：改用 supervisorScope，並用 CoroutineExceptionHandler 處理 XRP
// 的錯誤。
//      提示：先只換成 supervisorScope、不加 handler 跑一次，
// 看看在手機上會發生什麼事。
// 只改有 TODO 的地方。解答：solution/kotlin/kotlin03-coroutines/L02_Supervisor.
// kt
package tw.harry.labs.practice.kotlin03coroutines

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val symbols = listOf("BTC", "ETH", "XRP")

/**
 * 模擬一個幣種的行情訂閱（已寫好）：每 100 ms 收到一筆，共 5 筆；XRP 在第 3
 * 筆時丟出 IOException
 */
private suspend fun subscribe(
    symbol: String,
    received: MutableMap<String, Int>,
    log: LabLog,
) {
    repeat(5) { i ->
        delay(100)
        if (symbol == "XRP" && i == 2) throw IOException("XRP 連線中斷")
        received.merge(symbol, 1, Int::plus)
    }
    log("$symbol 收完 5 筆")
}

private fun summary(received: Map<String, Int>) =
    "收到的筆數：BTC=${received["BTC"] ?: 0}, ETH=${received["ETH"] ?: 0}, XRP=${received["XRP"] ?: 0}"

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) {
        val received = ConcurrentHashMap<String, Int>()
        try {
            // TODO ①：寫一個 coroutineScope { }，在裡面對 symbols 的每個幣種
            // launch 一個 coroutine，
            //        呼叫 subscribe(symbol, received, log)
        } catch (e: IOException) {
            log("整個列表停止：${e.message}")
        }
        log(summary(received))
    }
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun run(log: LabLog) {
        val received = ConcurrentHashMap<String, Int>()
        // TODO ②-1：建立 CoroutineExceptionHandler，收到例外時
        // log("只有這個幣種失敗：${e.message}")
        // TODO ②-2：寫一個 supervisorScope { }，對每個幣種 launch(handler) {
        // subscribe(symbol, received, log) }
        log(summary(received))
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "一個幣種斷線，整個列表都停了",
    question = "第 2 題：SupervisorJob 和 supervisorScope 改變了哪一條規則？",
    goal = "XRP 的連線出錯時，BTC 和 ETH 應該繼續更新。先寫出會整個停掉的 Bug 版，再寫修正版。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行各幣種收到的筆數。",
        "寫 TODO ②-2（先不加 handler）執行一次，看看會發生什麼事。",
        "補上 TODO ②-1 的 handler，再執行一次。",
    ),
    expectBug = "XRP 出錯時，BTC、ETH 也被取消，各只收到 2–3 筆，log 出現「整個列表停止」。",
    expectFixed = "BTC、ETH 收完 5 筆；XRP 停在 2 筆，log 出現「只有這個幣種失敗」。",
    file = "kotlin03-coroutines/L02_Supervisor.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none {
                it.startsWith("整個列表停止")
            } -> "沒有看到「整個列表停止」：三個幣種要開在 coroutineScope 裡"

            lines.any { it == "BTC 收完 5 筆" } -> "BTC 還是收完了：Bug 還沒重現"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none {
                it.startsWith("只有這個幣種失敗")
            } -> "沒有處理 XRP 的錯誤：要加 CoroutineExceptionHandler"

            "收到的筆數：BTC=5, ETH=5, XRP=2" !in lines -> "BTC、ETH 應該各收完 5 筆：要改用 supervisorScope"

            else -> null
        }
    },
)
