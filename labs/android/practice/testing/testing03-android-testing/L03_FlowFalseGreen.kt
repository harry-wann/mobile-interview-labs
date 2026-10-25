// L03：行情資料流少發了一筆資料，測試卻顯示通過（斷言寫在 collect 裡）
// 對應複習站：Testing03AndroidTesting 第 3 題「Flow 發出的一連串值，要怎麼檢查？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 practice/testing/test/Testing03L03Test.kt。
//
// 情境：T03PriceStream 的行情資料流應該「先發 Loading，再發 Data(15_000_000)」。
//       演練把同一個測試，分別對三個實作各跑一次：正確的、漏發 Data 的、價格算錯（Data(0)）的。
//
// 你要寫（在 test/Testing03L03Test.kt）：
//   ① Bug 版：L03Bug.test(stream) 用 runTest：launch { stream.collect { 遇到 T03UiState.Data 才 assertEquals(15_000_000, state.price) } }，最後 job.join()。
//   ② 修正版：L03Fixed.test(stream) 用 Turbine：stream.test { awaitItem() 是 Loading、awaitItem() 是 Data(15_000_000)、awaitComplete() }。
// 只改有 TODO 的地方。解答：solution/testing/testing03-android-testing/L03_FlowFalseGreen.kt
package tw.harry.labs.practice.testing03androidtesting

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tw.harry.labs.kit.Lab

sealed interface T03UiState {
    data object Loading : T03UiState
    data class Data(val price: Int) : T03UiState
}

/** 被測的程式：行情資料流（先發 Loading，再發 Data）。後兩個是故意改壞的版本 */
object T03PriceStream {
    fun correct(): Flow<T03UiState> = flow {
        emit(T03UiState.Loading)
        emit(T03UiState.Data(15_000_000))
    }

    /** 漏發 Data：例如 API 失敗時沒有處理，直接結束 */
    fun forgetsData(): Flow<T03UiState> = flow {
        emit(T03UiState.Loading)
    }

    /** 價格算錯 */
    fun wrongPrice(): Flow<T03UiState> = flow {
        emit(T03UiState.Loading)
        emit(T03UiState.Data(0))
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "資料流漏發資料，測試卻顯示通過",
    question = "第 3 題：Flow 發出的一連串值，要怎麼檢查？",
    goal = "看斷言寫在 collect 裡，資料流沒發資料時斷言根本不會執行；再用 Turbine 一個值一個值檢查，漏發就失敗。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。打開 test/Testing03L03Test.kt，點 l03() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :practice-testing:testDebugUnitTest --tests '*Testing03L03Test*' -i | grep SELFTEST。",
        "寫完 TODO ① ② 之後，看 Bug 版：三個實作各自通過還是失敗？漏發 Data 的那個為什麼沒被抓到？",
        "看修正版：漏發 Data 的實作失敗時，Turbine 的錯誤訊息是什麼？",
    ),
    expectBug = "正確的實作通過；價格算錯的實作失敗（抓到）；漏發 Data 的實作也通過（沒抓到：collect 裡的斷言根本沒有執行）。",
    expectFixed = "正確的實作通過；漏發 Data 與價格算錯的實作都失敗。",
    file = "test/Testing03L03Test.kt",
)
