package tw.harry.labs.practice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tw.harry.labs.practice.exchange06realtimeui.E06PriceRowBug
import tw.harry.labs.practice.exchange06realtimeui.E06PriceRowFixed
import tw.harry.labs.practice.exchange06realtimeui.E06_PRICE_SAMPLE

/**
 * Exchange06RealtimeUI L05：字體放大後價格被截斷（練習：寫完 lab 檔的 TODO 之後執行）。
 *
 * 還沒寫完時印 FAIL 是正常的，這個測試不會因此失敗。
 * 在 Android Studio 點 l05() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 * NATIVE：用真的文字排版（不是 Robolectric 的假排版），字體縮放才會真的影響文字寬度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class Exchange06L05Test {
    private val scales = listOf(1f, 1.3f, 2f)

    /**
     * 每個字體縮放比例各排版一次，回傳價格 Text 的排版結果（沒有畫出來就是
     * null）
     */
    private fun layouts(
        row: @Composable ((TextLayoutResult) -> Unit) -> Unit,
    ): List<TextLayoutResult?> = scales.map { scale ->
        var result: TextLayoutResult? = null
        runComposeUiTest {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, scale),
                ) {
                    row { result = it }
                }
            }
            waitForIdle()
        }
        result
    }

    private fun describe(results: List<TextLayoutResult?>): String =
        scales.indices.joinToString("；") { i ->
            val r = results[i]
            "字體縮放 ${scales[i]}：" +
                (
                    if (r ==
                        null
                    ) {
                        "沒有畫出來"
                    } else {
                        (if (r.hasVisualOverflow) "被截斷" else "完整顯示") +
                            "（價格欄寬 ${r.size.width} px）"
                    }
                )
        }

    @Test
    fun l05() {
        val failures = mutableListOf<String>()

        val bug =
            layouts { onLayout ->
                E06PriceRowBug("BTC_JPY", E06_PRICE_SAMPLE, onLayout)
            }
        val bugReason = when {
            bug.any { it == null } -> {
                "價格沒有畫出來：價格的 Text 要加 onTextLayout = onPriceLayout"
            }

            bug[0]!!.hasVisualOverflow -> {
                "字體縮放 1.0 就被截斷了：預設字體要放得下，寬度用 96.dp"
            }

            !bug[1]!!.hasVisualOverflow || !bug[2]!!.hasVisualOverflow -> {
                "Bug 還沒重現：字體縮放 1.3 與 2.0 的價格要被截斷：價格 Text 要 Modifier.width(96.dp)、maxLines = 1、overflow = TextOverflow.Ellipsis"
            }

            bug[0]!!.size.width != bug[2]!!.size.width -> {
                "價格欄的寬度要寫死：Modifier.width(96.dp)，字體縮放變大它也不會變寬"
            }

            else -> {
                null
            }
        }
        e06Report("L05", "Bug 版", bugReason, describe(bug), failures)

        val fixed =
            layouts { onLayout ->
                E06PriceRowFixed("BTC_JPY", E06_PRICE_SAMPLE, onLayout)
            }
        val fixedReason = when {
            fixed.any { it == null } -> {
                "價格沒有畫出來：價格的 Text 要加 onTextLayout = onPriceLayout"
            }

            fixed.any { it!!.hasVisualOverflow } -> {
                "價格還是被截斷：不要寫死寬度、不要 maxLines = 1 加 overflow = Ellipsis，改用 Modifier.widthIn(min = 96.dp)"
            }

            else -> {
                null
            }
        }
        e06Report("L05", "修正版", fixedReason, describe(fixed), failures)

        println(
            "[SELFTEST] Exchange06RealtimeUI L05 還差 ${failures.size} 個版本沒通過",
        )
    }
}
