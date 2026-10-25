// L05：下單表單一直顯示錯誤訊息，Compose UI 測試卻顯示通過（assertDoesNotExist 找錯名字）
// 對應複習站：Testing03AndroidTesting 第 5 題「畫面要怎麼測？」
//
// 這題在電腦上跑單元測試（JVM，用 Robolectric 模擬 Android，不用模擬器），沒有「執行」按鈕。
// 測試在 practice/testing/test/Testing03L05Test.kt。第一次執行 Robolectric 會下載 Android 的模擬環境，要等一下。
//
// 情境：下單表單在「數量低於 10,000 satoshi」時，要用 testTag 是 "order.error" 的文字顯示「數量低於最小下單量」。
//       測試要驗證「數量足夠時，不會顯示錯誤訊息」。演練把同一個測試，分別對三個實作各跑一次：
//       正確的、一直顯示錯誤訊息的、從來不顯示錯誤訊息的。
//
// 你要寫（在 test/Testing03L05Test.kt）：
//   ① Bug 版：L05Bug.test(form) 用 runComposeUiTest：setContent { form(50_000) }，再 onNodeWithTag("order.errorLabel").assertDoesNotExist()
//      （標籤故意寫錯，實際是 "order.error"）。
//   ② 修正版：L05Fixed.test(form) 寫兩個 runComposeUiTest：數量 50_000 時 onNodeWithTag("order.error").assertDoesNotExist()；
//      數量 9_999 時 onNodeWithTag("order.error").assertTextEquals("數量低於最小下單量")。
// 只改有 TODO 的地方。解答：solution/testing/testing03-android-testing/L05_ComposeNegativeAssert.kt
package tw.harry.labs.practice.testing03androidtesting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.util.concurrent.atomic.AtomicInteger
import tw.harry.labs.kit.Lab

/** 記錄表單被組合（畫到畫面上）了幾次，讓自動檢查知道測試真的有把表單畫出來 */
object T03ComposeProbe {
    val compositions = AtomicInteger()
}

@Composable
private fun T03OrderFormBody(amountSatoshi: Int, showError: Boolean) {
    SideEffect { T03ComposeProbe.compositions.incrementAndGet() }
    Column {
        Text("數量：$amountSatoshi satoshi", Modifier.testTag("order.amount"))
        if (showError) Text("數量低於最小下單量", Modifier.testTag("order.error"))
    }
}

/** 正確的實作：數量低於 10,000 satoshi 才顯示錯誤訊息 */
@Composable
fun T03OrderFormCorrect(amountSatoshi: Int) = T03OrderFormBody(amountSatoshi, showError = amountSatoshi < 10_000)

/** 被改壞：一直顯示錯誤訊息 */
@Composable
fun T03OrderFormAlwaysError(amountSatoshi: Int) = T03OrderFormBody(amountSatoshi, showError = true)

/** 被改壞：從來不顯示錯誤訊息 */
@Composable
fun T03OrderFormNeverError(amountSatoshi: Int) = T03OrderFormBody(amountSatoshi, showError = false)

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "表單一直顯示錯誤，UI 測試卻通過",
    question = "第 5 題：畫面要怎麼測？",
    goal = "看 Compose UI 測試的「不存在」檢查在標籤名字寫錯時永遠通過；再補上「存在」的檢查，確認標籤是對的。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試（Robolectric）。打開 test/Testing03L05Test.kt，點 l05() 旁邊的 ▶ 執行。第一次要下載 Robolectric 的環境，會比較久。",
        "或在終端機：cd labs/android && ./gradlew :practice-testing:testDebugUnitTest --tests '*Testing03L05Test*' -i | grep SELFTEST。",
        "寫完 TODO ① ② 之後，看 Bug 版：一直顯示錯誤訊息的實作為什麼沒被抓到？把標籤改成 \"order.error\" 會怎樣？",
        "想一想：assertDoesNotExist() 這類「不存在」的檢查，為什麼一定要搭配一個「存在」的檢查？",
    ),
    expectBug = "正確的實作通過；一直顯示錯誤訊息的實作也通過（沒抓到：標籤寫錯，assertDoesNotExist() 永遠通過）。",
    expectFixed = "正確的實作通過；一直顯示與從來不顯示錯誤訊息的實作都失敗。",
    file = "test/Testing03L05Test.kt",
)
