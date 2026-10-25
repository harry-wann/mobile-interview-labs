package tw.harry.labs.practice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tw.harry.labs.practice.testing03androidtesting.T03FlakyApi
import tw.harry.labs.practice.testing03androidtesting.t03Retry

/**
 * Testing03AndroidTesting L01：runBlocking 與 runTest 的差別（練習：寫完這個檔案的 TODO 之後執行）。
 *
 * 還沒寫完時印 FAIL 是正常的，這個測試不會因此失敗。
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

        println(
            "[SELFTEST] Testing03AndroidTesting L01 還差 ${failures.size} 個版本沒通過",
        )
    }
}

// MARK: - Bug 版
internal object L01Bug {
    fun run(): Testing03L01Test.Measured {
        val api = T03FlakyApi()
        // TODO ①：用 runBlocking { … } 呼叫 t03Retry { api.fetchPrice() }，
        // 把價格存起來；用 System.nanoTime() 量真實花了幾 ms
        //        回傳 Measured(價格, 真實 ms, 0, emptyList())
        return Testing03L01Test.Measured(0, 0, 0, emptyList())
    }
}

// MARK: - 修正版
internal object L01Fixed {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun run(): Testing03L01Test.Measured {
        // TODO ②：寫在 runTest { … } 裡
        //   1. val api = T03FlakyApi()，val result = async { t03Retry { api.
        // fetchPrice() } }
        //   2. runCurrent() 之後記下 api.calls；advanceTimeBy(1_000)
        // 之後記一次；再 runCurrent() 之後記一次；advanceTimeBy(2_000) 加
        // runCurrent() 之後記一次
        //   3. result.await() 取得價格；currentTime 是虛擬時鐘走了幾 ms
        //   回傳 Measured(價格, 真實 ms, 虛擬 ms, 上面四次記下的呼叫次數 list)
        return Testing03L01Test.Measured(0, 0, 0, emptyList())
    }
}
