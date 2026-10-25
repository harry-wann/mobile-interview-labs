package tw.harry.labs.practice

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import tw.harry.labs.practice.testing03androidtesting.T03FakeOrderApi
import tw.harry.labs.practice.testing03androidtesting.T03OrderApi
import tw.harry.labs.practice.testing03androidtesting.T03OrderPlacer
import tw.harry.labs.practice.testing03androidtesting.T03OrderRequest
import tw.harry.labs.practice.testing03androidtesting.T03PlaceOrder
import tw.harry.labs.practice.testing03androidtesting.T03PlaceOrderNeverSubmits
import tw.harry.labs.practice.testing03androidtesting.T03PlaceOrderWrongQuantity

/**
 * Testing03AndroidTesting L04：只驗證 mock 有被呼叫，抓不到送出內容的錯誤（練習：寫完這個檔案的 TODO 之後執行）。
 *
 * 還沒寫完時印 FAIL 是正常的，這個測試不會因此失敗。
 * 在 Android Studio 點 l04() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
class Testing03L04Test {
    private val implementations: List<Pair<String, (T03OrderApi) -> T03OrderPlacer>> = listOf(
        "正確的實作" to { api -> T03PlaceOrder(api) },
        "數量多乘 10 的實作" to { api -> T03PlaceOrderWrongQuantity(api) },
        "根本沒送出的實作" to { api -> T03PlaceOrderNeverSubmits(api) },
    )

    /** 同一個測試對三個實作各跑一次：通過（true）或失敗（false） */
    private fun matrix(test: ((T03OrderApi) -> T03OrderPlacer) -> Unit): List<Boolean> =
        implementations.map { (_, make) -> runCatching { test(make) }.isSuccess }

    @Test
    fun l04() {
        val failures = mutableListOf<String>()

        val bug = matrix(L04Bug::test)
        val bugReason = when {
            !bug[0] -> "測試在正確的實作上要通過：coVerify { api.submit(any()) } 確認有呼叫"
            bug[2] -> "根本沒送出的實作也通過了：要用 coVerify { api.submit(any()) } 確認有呼叫"
            !bug[1] -> "Bug 還沒重現：只驗證「有呼叫」，數量多乘 10 的實作也會通過（假的綠燈）"
            else -> null
        }
        t03Report("L04", "Bug 版", bugReason, "正確的實作通過；根本沒送出的實作失敗（抓到）；數量多乘 10 的實作也通過（沒抓到）", failures)

        val fixed = matrix(L04Fixed::test)
        val fixedReason = when {
            !fixed[0] -> "測試在正確的實作上要通過：fake.requests 應該剛好是 OrderRequest(15_000_000, 2, \"BUY\") 一筆"
            fixed[1] -> "數量多乘 10 的實作也通過了：要檢查 fake.requests 的內容，不只是有沒有呼叫"
            fixed[2] -> "根本沒送出的實作也通過了：fake.requests 應該剛好有一筆"
            else -> null
        }
        t03Report("L04", "修正版", fixedReason, "正確的實作通過；數量多乘 10 與根本沒送出的實作都失敗（抓到）", failures)

        println("[SELFTEST] Testing03AndroidTesting L04 還差 ${failures.size} 個版本沒通過")
    }
}

// MARK: - Bug 版
internal object L04Bug {
    fun test(newPlacer: (T03OrderApi) -> T03OrderPlacer) = runTest {
        // TODO ①：val api = mockk<T03OrderApi>(relaxed = true)；newPlacer(api).place(15_000_000, 2, "BUY")；
        //        再用 coVerify { api.submit(any()) } 只確認有呼叫
    }
}

// MARK: - 修正版
internal object L04Fixed {
    fun test(newPlacer: (T03OrderApi) -> T03OrderPlacer) = runTest {
        // TODO ②：val api = T03FakeOrderApi()；newPlacer(api).place(15_000_000, 2, "BUY")；
        //        用 assertEquals(listOf(T03OrderRequest(15_000_000, 2, "BUY")), api.requests) 檢查送出的內容
    }
}
