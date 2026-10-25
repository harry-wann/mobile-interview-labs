package tw.harry.labs.solution

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.harry.labs.solution.testing03androidtesting.T03PriceStream
import tw.harry.labs.solution.testing03androidtesting.T03UiState

/**
 * Testing03AndroidTesting L03：Flow 的斷言寫在 collect 裡，會變成假的綠燈（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點 l03() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
class Testing03L03Test {
    private val implementations: List<Pair<String, Flow<T03UiState>>> = listOf(
        "正確的實作" to T03PriceStream.correct(),
        "漏發 Data 的實作" to T03PriceStream.forgetsData(),
        "價格算錯的實作" to T03PriceStream.wrongPrice(),
    )

    /**
     * 同一個測試對三個實作各跑一次：通過（true）或失敗（false，測試丟出
     * AssertionError 之類的例外）
     */
    private fun matrix(test: (Flow<T03UiState>) -> Unit): List<Boolean> =
        implementations.map { (_, stream) ->
            runCatching { test(stream) }.isSuccess
        }

    @Test
    fun l03() {
        val failures = mutableListOf<String>()

        val bug = matrix(L03Bug::test)
        val bugReason = when {
            !bug[0] -> "測試在正確的實作上要通過：collect 裡遇到 Data 才檢查價格是 15_000_000"
            bug[2] -> "價格算錯的實作也通過了：collect 裡要用 assertEquals(15_000_000, it.price) 檢查 Data 的價格"
            !bug[1] -> "Bug 還沒重現：漏發 Data 時 collect 裡的斷言根本不會執行，測試應該要通過（假的綠燈）"
            else -> null
        }
        t03Report(
            "L03",
            "Bug 版",
            bugReason,
            "正確的實作通過；價格算錯的實作失敗（抓到）；漏發 Data 的實作也通過（沒抓到）",
            failures,
        )

        val fixed = matrix(L03Fixed::test)
        val fixedReason = when {
            !fixed[0] -> "測試在正確的實作上要通過：依序 awaitItem() 是 Loading、Data(15_000_000)，最後 awaitComplete()"
            fixed[1] -> "漏發 Data 的實作也通過了：用 Turbine 的 awaitItem() 取第二個值，沒有值時會失敗"
            fixed[2] -> "價格算錯的實作也通過了：要用 assertEquals 檢查 awaitItem() 的內容"
            else -> null
        }
        t03Report(
            "L03",
            "修正版",
            fixedReason,
            "正確的實作通過；漏發 Data 與價格算錯的實作都失敗（抓到）",
            failures,
        )

        assertEquals(emptyList<String>(), failures)
    }
}

// MARK: - Bug 版
internal object L03Bug {
    fun test(stream: Flow<T03UiState>) = runTest {
        val job = launch {
            stream.collect { state ->
                // 資料流沒發 Data，這行永遠不會執行
                if (state is T03UiState.Data) {
                    assertEquals(
                        15_000_000,
                        state.price,
                    )
                }
            }
        }
        job.join()
    }
}

// MARK: - 修正版
internal object L03Fixed {
    fun test(stream: Flow<T03UiState>) = runTest {
        stream.test {
            assertEquals(T03UiState.Loading, awaitItem())
            // 漏發時，這裡遇到的是「資料流結束」，測試失敗
            assertEquals(T03UiState.Data(15_000_000), awaitItem())
            awaitComplete()
        }
    }
}
