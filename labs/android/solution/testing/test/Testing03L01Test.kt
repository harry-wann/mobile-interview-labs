package tw.harry.labs.solution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.solution.testing03androidtesting.T03FlakyApi
import tw.harry.labs.solution.testing03androidtesting.t03Retry

/**
 * Testing03AndroidTesting L01：runBlocking 與 runTest 的差別（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點 l01() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
class Testing03L01Test {
    /**
     * 一次執行的結果：取得的價格、真實花了幾 ms、虛擬時鐘走了幾 ms、
     * 不同時間點的呼叫次數
     */
    class Measured(
        val price: Int,
        val realMs: Long,
        val virtualMs: Long,
        val callsTimeline: List<Int>,
    )

    @Test
    fun l01() {
        val failures = mutableListOf<String>()

        val b = L01Bug.run()
        val bugReason = when {
            b.price != 15_000_000 -> "Bug 版要取得價格 15000000：用 runBlocking { t03Retry { api.fetchPrice() } }"
            b.realMs < 2_500 -> "Bug 版沒有真的等：runBlocking 裡的 delay 是真實時間，應該要花約 3000 ms（實際 ${b.realMs} ms）"
            else -> null
        }
        t03Report("L01", "Bug 版", bugReason, "真實花了 ${b.realMs} ms", failures)

        val f = L01Fixed.run()
        val fixedReason = when {
            f.price != 15_000_000 -> {
                "修正版要取得價格 15000000：用 async { t03Retry { api.fetchPrice() } }，最後 await()"
            }

            f.callsTimeline != listOf(1, 1, 2, 3) -> {
                "呼叫次數應該是 1、1、2、3（依序在 runCurrent()、advanceTimeBy(1_000)、再 runCurrent()、advanceTimeBy(2_000) 加 runCurrent() 之後記錄），實際是 ${f.callsTimeline}"
            }

            f.virtualMs != 3_000L -> {
                "虛擬時鐘應該走 3000 ms（實際 ${f.virtualMs} ms）"
            }

            f.realMs >= 500 -> {
                "修正版還是真的等了 ${f.realMs} ms：要用 runTest，不要用 runBlocking"
            }

            else -> {
                null
            }
        }
        t03Report(
            "L01",
            "修正版",
            fixedReason,
            "真實 ${f.realMs} ms、虛擬 ${f.virtualMs} ms、呼叫次數 ${f.callsTimeline}",
            failures,
        )

        assertEquals(emptyList<String>(), failures)
    }
}

// MARK: - Bug 版
internal object L01Bug {
    fun run(): Testing03L01Test.Measured {
        val api = T03FlakyApi()
        var price = 0
        val start = System.nanoTime()
        // delay 是真實時間：測試真的卡 3 秒
        runBlocking { price = t03Retry { api.fetchPrice() } }
        return Testing03L01Test.Measured(
            price,
            (System.nanoTime() - start) / 1_000_000,
            0,
            emptyList(),
        )
    }
}

// MARK: - 修正版
internal object L01Fixed {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(): Testing03L01Test.Measured {
        var measured: Testing03L01Test.Measured? = null
        runTest {
            val api = T03FlakyApi()
            val start = System.nanoTime()
            val result = async { t03Retry { api.fetchPrice() } }
            // 執行「現在」排好的工作：第 1 次呼叫
            runCurrent()
            val atStart = api.calls
            // 時鐘到 1_000，但剛好排在 1_000 的工作還沒執行
            advanceTimeBy(1_000)
            val afterAdvance = api.calls
            // 執行排在 1_000 的工作：第 2 次呼叫
            runCurrent()
            val afterRun = api.calls
            advanceTimeBy(2_000)
            // 時鐘到 3_000：第 3 次呼叫
            runCurrent()
            val atEnd = api.calls
            val price = result.await()
            measured = Testing03L01Test.Measured(
                price,
                (System.nanoTime() - start) / 1_000_000,
                currentTime,
                listOf(atStart, afterAdvance, afterRun, atEnd),
            )
        }
        return measured!!
    }
}
