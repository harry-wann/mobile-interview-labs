// L03：@Volatile 的計數器還是少算了（volatile 保證可見性，不保證原子性）
// 對應複習站：Kotlin10JavaInterop 第 4 題「Java Memory Model（JMM）在規範什麼？什麼是可見性？」
//
// 情境：行情 SDK 的 Counter 要統計收到幾筆訊息。4 條執行緒各自呼叫 increment() 100,000 次，預期總共 400,000。
//
// 你要寫：
//   ① Bug 版（L03Bug.Counter）：@Volatile var count，increment() 裡寫 count++。
//   ② 修正版（L03Fixed.Counter）：用 AtomicInteger，increment() 呼叫 incrementAndGet()，get() 回傳目前的值。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin10-java-interop/L03_VolatileCounter.kt
package tw.harry.labs.practice.kotlin10javainterop

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val THREADS = 4
private const val PER_THREAD = 100_000

/** 演練流程（兩個版本共用）：4 條執行緒各呼叫 increment() 100,000 次，最後讀 [total] */
private suspend fun scenario(log: LabLog, increment: () -> Unit, total: () -> Int) {
    withContext(Dispatchers.Default) {
        List(THREADS) { thread { repeat(PER_THREAD) { increment() } } }.forEach { it.join() }
    }
    log("預期 ${THREADS * PER_THREAD}，實際 ${total()}")
}

// MARK: - Bug 版
private object L03Bug {
    class Counter {
        // TODO ①-1：@Volatile var count = 0（volatile：可見，但不是原子的）
        var count = 0

        fun increment() {
            // TODO ①-2：count++（讀出、加一、寫回：三步，中間會被插隊）
        }
    }

    suspend fun run(log: LabLog) {
        val counter = Counter()
        scenario(log, counter::increment) { counter.count }
    }
}

// MARK: - 修正版
private object L03Fixed {
    class Counter {
        // TODO ②-1：private val count = AtomicInteger()

        fun increment() {
            // TODO ②-2：count.incrementAndGet()（原子操作：CAS 失敗就重試）
        }

        // TODO ②-3：get() 回傳 count.get()
        fun get(): Int = 0
    }

    suspend fun run(log: LabLog) {
        val counter = Counter()
        scenario(log, counter::increment, counter::get)
    }
}

// MARK: - 演練設定
private fun actual(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("預期 ") }?.substringAfter("實際 ")?.toIntOrNull()

val l03 = Lab(
    id = "L03",
    title = "volatile 的計數器還是少算了",
    question = "第 4 題：Java Memory Model（JMM）在規範什麼？什麼是可見性？",
    goal = "4 條執行緒各加 100,000 次。看 @Volatile 加 count++ 為什麼還是少算，再改用 AtomicInteger。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin10-java-interop/L03_VolatileCounter.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看「預期 400000，實際 N」的 N。多執行幾次，數字每次不同。",
        "寫 TODO ②（修正版），選「修正版」執行，確認實際是 400000。",
        "想一想：volatile 保證什麼、不保證什麼？（保證可見性與順序；不保證複合操作 count++ 的原子性）",
    ),
    expectBug = "實際數字小於 400000（通常差幾萬到幾十萬），每次都不一樣。",
    expectFixed = "「預期 400000，實際 400000」。",
    file = "kotlin10-java-interop/L03_VolatileCounter.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = actual(lines)
        when {
            n == null || n == 0 -> "沒有計數：increment() 要把 count 加一"
            n >= THREADS * PER_THREAD -> "沒有少算：Bug 版要用 @Volatile var count，increment() 裡寫 count++（不要用 Atomic 或鎖）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = actual(lines)
        when {
            n == null || n == 0 -> "沒有計數：increment() 要把 count 加一"
            n != THREADS * PER_THREAD -> "實際是 $n，應該是 ${THREADS * PER_THREAD}：用 AtomicInteger 的 incrementAndGet()"
            else -> null
        }
    },
)
