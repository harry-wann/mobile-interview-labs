package tw.harry.labs.practice

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.harry.labs.practice.testing03androidtesting.T03ComposeProbe
import tw.harry.labs.practice.testing03androidtesting.T03OrderFormAlwaysError
import tw.harry.labs.practice.testing03androidtesting.T03OrderFormCorrect
import tw.harry.labs.practice.testing03androidtesting.T03OrderFormNeverError

/**
 * Testing03AndroidTesting L05：Compose UI 測試的「不存在」檢查（練習：寫完這個檔案的 TODO 之後執行）。
 *
 * 還沒寫完時印 FAIL 是正常的，這個測試不會因此失敗。
 * 在 Android Studio 點 l05() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class Testing03L05Test {
    private val implementations: List<Pair<String, @Composable (Int) -> Unit>> = listOf(
        "正確的實作" to { amount -> T03OrderFormCorrect(amount) },
        "一直顯示錯誤的實作" to { amount -> T03OrderFormAlwaysError(amount) },
        "從來不顯示錯誤的實作" to { amount -> T03OrderFormNeverError(amount) },
    )

    /** 同一個測試對三個實作各跑一次：通過（true）或失敗（false）；同時記下表單被畫出來幾次 */
    private fun matrix(test: (@Composable (Int) -> Unit) -> Unit): Pair<List<Boolean>, Int> {
        T03ComposeProbe.compositions.set(0)
        val results = implementations.map { (_, form) -> runCatching { test(form) }.isSuccess }
        return results to T03ComposeProbe.compositions.get()
    }

    @Test
    fun l05() {
        val failures = mutableListOf<String>()

        val (bug, bugCompositions) = matrix(L05Bug::test)
        val bugReason = when {
            bugCompositions == 0 -> "測試沒有把表單畫出來：要用 setContent { form(50_000) } 畫出數量足夠的表單"
            !bug[0] -> "測試在正確的實作上要通過：數量足夠時，order.error 不該存在"
            !bug[1] -> "Bug 還沒重現：標籤寫成 \"order.errorLabel\"（實際是 \"order.error\"），assertDoesNotExist() 永遠通過，一直顯示錯誤的實作也要通過"
            else -> null
        }
        t03Report("L05", "Bug 版", bugReason, "正確的實作通過；一直顯示錯誤的實作也通過（沒抓到）", failures)

        val (fixed, _) = matrix(L05Fixed::test)
        val fixedReason = when {
            !fixed[0] -> "測試在正確的實作上要通過：數量足夠時 order.error 不存在；數量 9_999 時 order.error 存在、文字是「數量低於最小下單量」"
            fixed[1] -> "一直顯示錯誤的實作也通過了：數量足夠時要檢查 order.error 不存在"
            fixed[2] -> "從來不顯示錯誤的實作也通過了：數量 9_999 時要檢查 order.error 存在、文字是「數量低於最小下單量」"
            else -> null
        }
        t03Report("L05", "修正版", fixedReason, "正確的實作通過；一直顯示與從來不顯示錯誤的實作都失敗（抓到）", failures)

        println("[SELFTEST] Testing03AndroidTesting L05 還差 ${failures.size} 個版本沒通過")
    }
}

// MARK: - Bug 版
@OptIn(ExperimentalTestApi::class)
internal object L05Bug {
    fun test(form: @Composable (Int) -> Unit) = runComposeUiTest {
        // TODO ①：setContent { form(50_000) }；再用 onNodeWithTag("order.errorLabel").assertDoesNotExist() 檢查（標籤故意寫成 order.errorLabel）
    }
}

// MARK: - 修正版
@OptIn(ExperimentalTestApi::class)
internal object L05Fixed {
    fun test(form: @Composable (Int) -> Unit) {
        // TODO ②-1：runComposeUiTest { setContent { form(50_000) }；onNodeWithTag("order.error").assertDoesNotExist() }
        // TODO ②-2：再一個 runComposeUiTest { setContent { form(9_999) }；onNodeWithTag("order.error").assertTextEquals("數量低於最小下單量") }
    }
}
