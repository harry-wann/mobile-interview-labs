package tw.harry.labs.solution

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tw.harry.labs.solution.exchange06realtimeui.E06TickerRowBug
import tw.harry.labs.solution.exchange06realtimeui.E06TickerRowFixed

/**
 * Exchange06RealtimeUI L06：行情列的無障礙標籤與 testTag（解答：Bug 版要重現問題、修正版要正確）。
 *
 * 在 Android Studio 點 l06() 旁邊的 ▶ 執行；結果也會印成 [SELFTEST] 開頭的行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalTestApi::class)
class Exchange06L06Test {
    /** TalkBack 看到的一個元素（Compose 的 semantics 樹，合併之後） */
    private class Item(
        val spoken: String,
        val tag: String,
        val isButton: Boolean,
    )

    private fun toItem(node: SemanticsNode): Item {
        val config = node.config
        val parts = buildList {
            config.getOrNull(SemanticsProperties.ContentDescription)?.let {
                addAll(it)
            }
            config.getOrNull(SemanticsProperties.Text)?.let { texts ->
                addAll(texts.map { it.text })
            }
            config.getOrNull(SemanticsProperties.StateDescription)?.let {
                add(it)
            }
        }.filter { it.isNotEmpty() }
        val isButton = config.getOrNull(SemanticsProperties.Role) == Role.Button
        val text = if (parts.isEmpty()) "（沒有名稱）" else parts.joinToString("，")
        return Item(
            if (isButton) "$text，按鈕" else text,
            config.getOrNull(SemanticsProperties.TestTag).orEmpty(),
            isButton,
        )
    }

    /** 把行情列畫出來，讀出 TalkBack 會停留的每個元素 */
    private fun items(row: @Composable () -> Unit): List<Item> {
        var result = emptyList<Item>()
        runComposeUiTest {
            setContent { row() }
            waitForIdle()
            result = onRoot().fetchSemanticsNode().children.map(::toItem)
        }
        return result
    }

    private fun describe(items: List<Item>): String =
        "TalkBack 看到 ${items.size} 個元素：" +
            items.joinToString("；") { "「${it.spoken}」testTag「${it.tag}」" }

    @Test
    fun l06() {
        val failures = mutableListOf<String>()

        val bug = items { E06TickerRowBug() }
        val bugReason = when {
            bug.size != 4 -> {
                "Bug 還沒重現：行情列要拆成 4 個元素：交易對 Text、價格 Text、「▲」Text、按鈕 IconButton { Image(…, contentDescription = null) }，不要加 semantics（現在是 ${bug.size} 個）"
            }

            bug.none { it.isButton } -> {
                "Bug 還沒重現：要有一顆只有圖示的按鈕（IconButton 裡面 Image 的 contentDescription = null）"
            }

            bug.any { it.spoken.contains("上漲") } -> {
                "Bug 還沒重現：漲跌只靠顏色與 ▲ 符號，不要加 stateDescription"
            }

            bug.any { it.spoken.contains("下單") } -> {
                "Bug 還沒重現：按鈕只有圖示，Image 的 contentDescription 要是 null"
            }

            bug.any { it.tag.startsWith("ticker.BTC_JPY") } -> {
                "Bug 還沒重現：Bug 版不要加 testTag，UI 測試找不到"
            }

            else -> {
                null
            }
        }
        e06Report("L06", "Bug 版", bugReason, describe(bug), failures)

        val fixed = items { E06TickerRowFixed() }
        val quote = fixed.firstOrNull { it.tag == "ticker.BTC_JPY.quote" }
        val order = fixed.firstOrNull { it.tag == "ticker.BTC_JPY.order" }
        val fixedReason = when {
            quote == null || !quote.spoken.contains("12,345,678") ||
                !quote.spoken.contains("上漲") -> {
                "行情那一塊要合成一個元素：Modifier.clearAndSetSemantics { contentDescription 寫幣種、stateDescription 寫「12,345,678 日圓，上漲 1.23%」、testTag = \"ticker.BTC_JPY.quote\" }"
            }

            order == null || !order.spoken.contains(
                "下單",
            ) || !order.isButton -> {
                "下單按鈕要有名稱與 testTag：Image 的 contentDescription = \"下單 BTC_JPY\"，IconButton 加 Modifier.testTag(\"ticker.BTC_JPY.order\")"
            }

            fixed.size != 2 -> {
                "行情列應該只剩 2 個元素：行情（交易對、價格、▲ 合成一個）與下單按鈕（現在是 ${fixed.size} 個）"
            }

            else -> {
                null
            }
        }
        e06Report("L06", "修正版", fixedReason, describe(fixed), failures)

        assertEquals(emptyList<String>(), failures)
    }
}
