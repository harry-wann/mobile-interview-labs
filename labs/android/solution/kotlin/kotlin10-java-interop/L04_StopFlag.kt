// L04：把停止旗標設成 false 了，背景迴圈卻停不下來（可見性：@Volatile）
// 對應複習站：Kotlin10JavaInterop 第 4 題「Java Memory Model（JMM）在規範什麼？什麼是可見性？」
//
// 情境：Poller 在背景執行緒用 while (running) 持續輪詢行情，另一條執行緒呼叫 stop() 把 running 設成 false 要它停下來。
//        演練先讓迴圈跑 100 ms，呼叫 stop()，再等 300 ms，看迴圈結束了沒有。
//        為了不讓 Bug 版一直空轉，迴圈最多跑 10 億圈（約 1 到 3 秒）就自己結束。
// Bug 版：var running 沒有加 @Volatile。JIT（執行時才編譯程式碼的編譯器）發現迴圈裡沒有人改 running，
//        可能把它的讀取移到迴圈外，只讀一次；另一條執行緒寫入的 false 這條執行緒永遠看不到，迴圈停不下來。
// 修正版：@Volatile var running。每次讀都讀最新的值，寫入立刻讓其他執行緒看得到，stop() 之後迴圈很快結束。
//
// 注意：這個 bug 取決於 JIT 有沒有最佳化這個迴圈。HotSpot JVM（電腦上）幾乎每次都會停不下來，
//        Android 的 ART 不一定，所以 Bug 版沒有自動檢查，要看 log 判斷（跑幾次，或讓迴圈先熱身一下）。
package tw.harry.labs.solution.kotlin10javainterop

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val MAX_SPINS = 1_000_000_000L

/**
 * 演練流程（兩個版本共用）：背景迴圈跑 100 ms → stop() → 再等 300 ms，看迴圈結束了沒有。
 * [loop] 在背景執行緒執行，[stop] 在另一條執行緒（呼叫端）呼叫。
 */
private suspend fun scenario(log: LabLog, loop: () -> Unit, stop: () -> Unit) {
    val finished = AtomicBoolean(false)
    thread(isDaemon = true) {
        loop()
        finished.set(true)
    }
    delay(100)
    log("停止前，迴圈還在跑：${if (!finished.get()) "是" else "否"}")
    stop()
    log("呼叫 stop()：running = false")
    delay(300)
    log("stop() 之後 300 ms，迴圈已結束：${if (finished.get()) "是" else "否"}")
}

// MARK: - Bug 版
private object L04Bug {
    class Poller {
        var running = true                                  // 沒有 @Volatile
        var spins = 0L

        fun loop() {
            var hash = 1L
            var i = 0L
            while (running && i < MAX_SPINS) {              // JIT 可能把 running 的讀取移到迴圈外
                hash = hash * 31 + i                        // 模擬輪詢的工作
                i++
            }
            spins = i + hash
        }

        fun stop() {
            running = false
        }
    }

    suspend fun run(log: LabLog) {
        val poller = Poller()
        scenario(log, poller::loop, poller::stop)
    }
}

// MARK: - 修正版
private object L04Fixed {
    class Poller {
        @Volatile
        var running = true                                  // 每次讀都讀最新的值
        var spins = 0L

        fun loop() {
            var hash = 1L
            var i = 0L
            while (running && i < MAX_SPINS) {
                hash = hash * 31 + i
                i++
            }
            spins = i + hash
        }

        fun stop() {
            running = false
        }
    }

    suspend fun run(log: LabLog) {
        val poller = Poller()
        scenario(log, poller::loop, poller::stop)
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "停止旗標設成 false，迴圈卻停不下來",
    question = "第 4 題：Java Memory Model（JMM）在規範什麼？什麼是可見性？",
    goal = "背景迴圈用 while (running) 輪詢，另一條執行緒把 running 設成 false。看沒加 @Volatile 時迴圈為什麼可能停不下來，再補上 @Volatile。",
    steps = listOf(
        "選「Bug 版」執行，看「stop() 之後 300 ms，迴圈已結束」是「是」還是「否」。電腦上通常是「否」；多按幾次執行。",
        "選「修正版」執行，確認是「是」。",
        "想一想：為什麼迴圈裡沒有人改 running，JIT 就能把讀取移到迴圈外？（沒有同步，JMM 允許這種最佳化；volatile 禁止它）",
    ),
    expectBug = "「stop() 之後 300 ms，迴圈已結束：否」：stop() 的寫入對背景執行緒不可見，迴圈一直跑到 10 億圈的上限才結束（電腦上通常如此，Android 不一定）。",
    expectFixed = "「stop() 之後 300 ms，迴圈已結束：是」。",
    file = "kotlin10-java-interop/L04_StopFlag.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkFixed = { lines ->
        when {
            "停止前，迴圈還在跑：是" !in lines -> "迴圈沒有跑起來：running 的初始值要是 true，loop() 才會在 while (running && i < MAX_SPINS) 裡一直工作"
            "stop() 之後 300 ms，迴圈已結束：是" !in lines -> "stop() 之後迴圈還在跑：running 要加 @Volatile，寫入的 false 才看得到"
            else -> null
        }
    },
)
