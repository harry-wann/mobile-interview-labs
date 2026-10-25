package tw.harry.labs.solution

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.solution.testing03androidtesting.L02Bug
import tw.harry.labs.solution.testing03androidtesting.L02Fixed
import tw.harry.labs.solution.testing03androidtesting.T03LatencyApi

/**
 * Testing03AndroidTesting L02：ViewModel 的 dispatcher 注入與 Dispatchers.setMain（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點 l02() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Testing03L02Test {
    /** 伺服器的 6 種延遲（毫秒） */
    private val latencies = listOf(30L, 150L, 50L, 130L, 70L, 170L)

    /** 一輪測試的結果：每種延遲測試有沒有通過、整輪真實花了幾 ms */
    private class Round(val passed: List<Boolean>, val realMs: Long)

    private fun bugRound(): Round {
        val start = System.nanoTime()
        val passed = latencies.map { latency ->
            val vm = L02Bug.PriceViewModel(T03LatencyApi(latency))
            vm.load()
            // 沒辦法控制時間，只好 sleep 一個「應該夠久」的時間
            Thread.sleep(100)
            vm.price.value == 15_000_000
        }
        return Round(passed, (System.nanoTime() - start) / 1_000_000)
    }

    private fun fixedRound(): Round {
        val start = System.nanoTime()
        val passed = latencies.map { latency ->
            var ok = false
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                // viewModelScope 用主執行緒的 dispatcher：換成測試用的
                Dispatchers.setMain(dispatcher)
                try {
                    val vm = L02Fixed.PriceViewModel(
                        T03LatencyApi(latency),
                        dispatcher,
                    )
                    vm.load()
                    // 虛擬時間推進到沒有待執行的工作
                    advanceUntilIdle()
                    ok = vm.price.value == 15_000_000
                } finally {
                    Dispatchers.resetMain()
                }
            }
            ok
        }
        return Round(passed, (System.nanoTime() - start) / 1_000_000)
    }

    @Test
    fun l02() {
        val failures = mutableListOf<String>()

        val b = bugRound()
        val passCount = b.passed.count { it }
        val bugReason = when {
            passCount == 0 -> "6 種延遲全部失敗：load() 要在背景載入價格並存進 price（withContext(Dispatchers.IO) 裡呼叫 api.fetchPrice()）"
            passCount == latencies.size -> "6 種延遲全部通過：Bug 還沒重現（load() 要寫死 Dispatchers.IO，測試才只能 sleep 等）"
            else -> null
        }
        t03Report(
            "L02",
            "Bug 版",
            bugReason,
            "6 種延遲 $passCount 種通過（$latencies ms → ${b.passed}），真實花了 ${b.realMs} ms",
            failures,
        )

        val f = fixedRound()
        val fixedReason = when {
            f.passed.any {
                !it
            } -> "有 ${f.passed.count {
                !it
            }} 種延遲失敗（${f.passed}）：load() 要用注入的 io（withContext(io)），不能寫死 Dispatchers.IO"

            f.realMs >= 1_000 -> "修正版還是真的等了 ${f.realMs} ms：load() 要用注入的 io，測試傳入 StandardTestDispatcher"

            else -> null
        }
        t03Report(
            "L02",
            "修正版",
            fixedReason,
            "6 種延遲全部通過，真實花了 ${f.realMs} ms",
            failures,
        )

        assertEquals(emptyList<String>(), failures)
    }
}
