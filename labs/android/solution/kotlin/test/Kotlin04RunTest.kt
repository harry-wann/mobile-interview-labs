package tw.harry.labs.solution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.solution.kotlin04coroutinesync.L08Bug
import tw.harry.labs.solution.kotlin04coroutinesync.L08Fixed

/**
 * Kotlin04CoroutineSync L08：runTest 與虛擬時間（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點 l08() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Kotlin04RunTest {
    /** 一次執行的結果：載入的資料、真實花了幾 ms、runTest 的虛擬時鐘走了幾 ms */
    private class Measured(val data: String, val realMs: Long, val virtualMs: Long)

    private fun bug(): Measured {
        var measured: Measured? = null
        runTest {
            val repo = L08Bug.OrderRepo()
            val start = System.nanoTime()
            val data = repo.load()
            measured = Measured(data, (System.nanoTime() - start) / 1_000_000, currentTime)
        }
        return measured!!
    }

    private fun fixed(): Measured {
        var measured: Measured? = null
        runTest {
            val repo = L08Fixed.OrderRepo(StandardTestDispatcher(testScheduler))   // 共用測試的 scheduler
            val start = System.nanoTime()
            val data = repo.load()
            measured = Measured(data, (System.nanoTime() - start) / 1_000_000, currentTime)
        }
        return measured!!
    }

    @Test
    fun l08() {
        val failures = mutableListOf<String>()

        val b = bug()
        val bugReason = when {
            b.data != "50 筆訂單" -> "Bug 版要載入「50 筆訂單」：load() 寫死 withContext(Dispatchers.IO)，裡面 delay(2_000) 後回傳 \"50 筆訂單\""
            b.realMs < 1_500 -> "Bug 版沒有真的等：load() 要在 withContext(Dispatchers.IO) 裡 delay(2_000)"
            b.virtualMs >= 100 -> "Bug 版的虛擬時鐘不應該走（走了 ${b.virtualMs} ms）"
            else -> null
        }
        report("Bug 版", bugReason, "真實 ${b.realMs} ms、虛擬 ${b.virtualMs} ms", failures)

        val f = fixed()
        val fixedReason = when {
            f.data != "50 筆訂單" -> "修正版要載入「50 筆訂單」：load() 用 withContext(io)，裡面 delay(2_000) 後回傳 \"50 筆訂單\""
            f.virtualMs != 2_000L -> "虛擬時鐘沒有走 2000 ms（走了 ${f.virtualMs} ms）：load() 要用注入的 io，不能寫死 Dispatchers.IO"
            f.realMs >= 500 -> "修正版還是真的等了 ${f.realMs} ms：load() 要用 withContext(io)，測試傳入 StandardTestDispatcher(testScheduler)"
            else -> null
        }
        report("修正版", fixedReason, "真實 ${f.realMs} ms、虛擬 ${f.virtualMs} ms", failures)

        assertEquals(emptyList<String>(), failures)
    }

    private fun report(variant: String, reason: String?, detail: String, failures: MutableList<String>) {
        if (reason == null) {
            println("[SELFTEST] Kotlin04CoroutineSync L08 $variant PASS　$detail")
        } else {
            println("[SELFTEST] Kotlin04CoroutineSync L08 $variant FAIL：$reason")
            failures += "$variant：$reason"
        }
    }
}
