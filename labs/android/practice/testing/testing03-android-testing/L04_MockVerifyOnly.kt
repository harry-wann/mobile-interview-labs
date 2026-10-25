// L04：送出的訂單數量錯了十倍，測試卻顯示通過（只驗證 mock 有被呼叫）
// 對應複習站：Testing03AndroidTesting 第 4 題「假的依賴要用 mock 還是自己寫
// fake？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// practice/testing/test/Testing03L04Test.kt。
//
// 情境：PlaceOrder.place() 把使用者的價格、數量、買賣方向包成 OrderRequest，
// 交給 OrderApi.submit() 送出。
//       演練把同一個測試，分別對三個實作各跑一次：正確的、數量多乘了 10 的、
// 根本沒有送出的。
//
// 你要寫（在 test/Testing03L04Test.kt）：
//   ① Bug 版：L04Bug.test(newPlacer) 用 MockK：val api =
// mockk<T03OrderApi>(relaxed = true)；newPlacer(api).place(15_000_000, 2,
// "BUY")；
//      只用 coVerify { api.submit(any()) } 確認有呼叫。
//   ② 修正版：L04Fixed.test(newPlacer) 用 T03FakeOrderApi（已經寫好，
// 會把收到的請求記在 requests）：place 之後
//      assertEquals(listOf(T03OrderRequest(15_000_000, 2, "BUY")), api.
// requests)。
// 只改有 TODO 的地方。解答：
// solution/testing/testing03-android-testing/L04_MockVerifyOnly.kt
package tw.harry.labs.practice.testing03androidtesting

import tw.harry.labs.kit.Lab

data class T03OrderRequest(
    val priceJpy: Int,
    val quantity: Int,
    val side: String,
)

interface T03OrderApi {
    suspend fun submit(request: T03OrderRequest): Boolean
}

/** 被測的程式：下單。後兩個是故意改壞的版本 */
interface T03OrderPlacer {
    suspend fun place(priceJpy: Int, quantity: Int, side: String)
}

class T03PlaceOrder(private val api: T03OrderApi) : T03OrderPlacer {
    override suspend fun place(priceJpy: Int, quantity: Int, side: String) {
        api.submit(T03OrderRequest(priceJpy, quantity, side))
    }
}

/** 被改壞：數量多乘了 10 */
class T03PlaceOrderWrongQuantity(private val api: T03OrderApi) :
    T03OrderPlacer {
    override suspend fun place(priceJpy: Int, quantity: Int, side: String) {
        api.submit(T03OrderRequest(priceJpy, quantity * 10, side))
    }
}

/** 被改壞：根本沒有送出 */
class T03PlaceOrderNeverSubmits(
    @Suppress("unused") private val api: T03OrderApi,
) : T03OrderPlacer {
    override suspend fun place(priceJpy: Int, quantity: Int, side: String) =
        Unit
}

/** 手寫的 fake：把收到的請求記下來，測試可以直接檢查 */
class T03FakeOrderApi : T03OrderApi {
    val requests = mutableListOf<T03OrderRequest>()

    override suspend fun submit(request: T03OrderRequest): Boolean {
        requests += request
        return true
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "訂單數量錯了十倍，測試卻顯示通過",
    question = "第 4 題：假的依賴要用 mock 還是自己寫 fake？",
    goal = "看只用 mock 驗證「有呼叫」的測試抓不到內容錯誤；再改成 fake 檢查送出的內容。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。打開 test/Testing03L04Test.kt，點 l04() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :practice-testing:testDebugUnitTest --tests '*Testing03L04Test*' -i | grep SELFTEST。",
        "寫完 TODO ① ② 之後，看 Bug 版：數量多乘 10 的實作為什麼沒被抓到？",
        "想一想：mockk(relaxed = true) 少設定了一個回傳值時，會把什麼錯誤藏起來？",
    ),
    expectBug = "正確的實作通過；根本沒送出的實作失敗（抓到）；數量多乘 10 的實作也通過（沒抓到：只驗證有呼叫）。",
    expectFixed = "正確的實作通過；數量多乘 10 與根本沒送出的實作都失敗。",
    file = "test/Testing03L04Test.kt",
)
