// L02：一個幣種斷線，整個行情列表都停了（SupervisorJob／supervisorScope）
// 對應複習站：Kotlin03Coroutines 第 2 題「SupervisorJob 和 supervisorScope 改變了哪一條規則？」
//
// 情境：行情列表同時訂閱 BTC、ETH、XRP 三個幣種，每個幣種一個 coroutine。XRP 的連線在第 3 筆時出錯。
// Bug 版：三個 coroutine 開在 coroutineScope 裡。一般的父子關係是「一個孩子失敗，父親取消所有孩子」，
//        所以 XRP 出錯，BTC、ETH 也一起被取消。
// 修正版：改用 supervisorScope，孩子失敗不會牽連兄弟；同時用 CoroutineExceptionHandler 處理 XRP 的錯誤，
//        不處理的話例外沒人接，App 一樣會當掉。
package tw.harry.labs.solution.kotlin03coroutines

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

/** 模擬一個幣種的行情訂閱：每 100 ms 收到一筆，共 5 筆；XRP 在第 3 筆時斷線 */
private suspend fun subscribe(symbol: String, received: MutableMap<String, Int>, log: LabLog) {
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
            coroutineScope {
                for (symbol in symbols) {
                    launch { subscribe(symbol, received, log) }
                }
            }
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
        // 處理單一幣種的失敗：畫面顯示「XRP 暫時無法更新」，不影響其他幣種
        val handler = CoroutineExceptionHandler { _, e -> log("只有這個幣種失敗：${e.message}") }
        supervisorScope {
            for (symbol in symbols) {
                launch(handler) { subscribe(symbol, received, log) }
            }
        }
        log(summary(received))
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "一個幣種斷線，整個列表都停了",
    question = "第 2 題：SupervisorJob 和 supervisorScope 改變了哪一條規則？",
    goal = "XRP 的連線出錯時，BTC 和 ETH 應該繼續更新。",
    steps = listOf(
        "選「Bug 版」執行，看最後一行各幣種收到的筆數。",
        "選「修正版」執行，比較筆數與 log。",
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
            lines.none { it.startsWith("整個列表停止") } -> "沒有看到「整個列表停止」：三個幣種要開在 coroutineScope 裡"
            lines.any { it == "BTC 收完 5 筆" } -> "BTC 還是收完了：Bug 還沒重現"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("只有這個幣種失敗") } -> "沒有處理 XRP 的錯誤：要加 CoroutineExceptionHandler"
            "收到的筆數：BTC=5, ETH=5, XRP=2" !in lines -> "BTC、ETH 應該各收完 5 筆：要改用 supervisorScope"
            else -> null
        }
    },
)
