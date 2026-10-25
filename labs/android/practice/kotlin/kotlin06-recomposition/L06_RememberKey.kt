// L06：切換交易對後，價格的小數位數還是上一個交易對的（remember 漏寫 key）
// 對應複習站：Kotlin06Recomposition 第 6 題「remember、rememberSaveable 和 key 怎麼用？」
//
// 情境：價格要依交易對的 tick size（最小跳動單位）決定顯示幾位小數：BTC_JPY 的 tick size 是 1（整數），
//        ETH_JPY 的是 0.01（兩位小數）。PriceFormatter 依 tick size 建立。畫面有兩個按鈕切換交易對。
//
// 你要寫：
//   ① Bug 版：val formatter = remember { PriceFormatter(tickSize) }。tickSize 變了也不會重新建立。
//   ② 修正版：val formatter = remember(tickSize) { PriceFormatter(tickSize) }，tickSize 變了就重建。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，看橘色的重組次數（或畫面）是不是照預期。
// 解答：solution/kotlin/kotlin06-recomposition/L06_RememberKey.kt
package tw.harry.labs.practice.kotlin06recomposition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.RoundingMode
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 依 tick size 的小數位數格式化價格（無條件捨去） */
private class PriceFormatter(tickSize: BigDecimal) {
    private val scale = tickSize.stripTrailingZeros().scale().coerceAtLeast(0)

    fun format(price: BigDecimal): String = price.setScale(scale, RoundingMode.DOWN).toPlainString()
}

private val price = BigDecimal("12345.6789")

// MARK: - Bug 版
@Composable
private fun L06BugPrice(symbol: String, tickSize: BigDecimal) {
    // TODO ①：val formatter = remember { PriceFormatter(tickSize) }（只在第一次建立，tickSize 變了也不會重建）
    val formatter = PriceFormatter(tickSize)
    Text("$symbol 價格：${formatter.format(price)}")
}

// MARK: - 修正版
@Composable
private fun L06FixedPrice(symbol: String, tickSize: BigDecimal) {
    // TODO ②：val formatter = remember(tickSize) { PriceFormatter(tickSize) }（tickSize 變了就重建）
    val formatter = PriceFormatter(tickSize)
    Text("$symbol 價格：${formatter.format(price)}")
}

// MARK: - 演練設定
@Composable
private fun L06Demo(variant: Variant, log: LabLog) {
    var symbol by remember { mutableStateOf("BTC_JPY") }
    val tickSize = if (symbol == "BTC_JPY") BigDecimal("1") else BigDecimal("0.01")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { symbol = "BTC_JPY"; log("切換到 BTC_JPY（tick size 1）") }) { Text("BTC_JPY") }
            Button(onClick = { symbol = "ETH_JPY"; log("切換到 ETH_JPY（tick size 0.01）") }) { Text("ETH_JPY") }
        }
        Text("同一個價格 12345.6789")
        when (variant) {
            Variant.BUG -> L06BugPrice(symbol, tickSize)
            Variant.FIXED -> L06FixedPrice(symbol, tickSize)
        }
    }
}

val l06 = Lab(
    id = "L06",
    title = "切換交易對後，小數位數還是上一個的",
    question = "第 6 題：remember、rememberSaveable 和 key 怎麼用？",
    goal = "價格依 tick size 決定小數位數。先寫出 remember 漏寫 key 的 Bug 版，看切換交易對後格式為什麼不會跟著換，再補上 key。",
    steps = listOf(
        "打開 practice/kotlin/kotlin06-recomposition/L06_RememberKey.kt，寫 TODO ①（Bug 版）。",
        "App 選「練習」「Bug 版」，先按「BTC_JPY」，再按「ETH_JPY」，看價格的小數位數有沒有跟著變。",
        "寫 TODO ②（修正版），選「修正版」，同樣操作，確認 ETH_JPY 顯示兩位小數。",
        "想一想：把 tickSize 放進 remember 的 key 之後，每次 tickSize 變動都會重新計算，有什麼代價？（計算要夠便宜，或者值很少變動）",
    ),
    expectBug = "切到 ETH_JPY 後，價格仍然顯示 12345（整數），因為格式化規則還是 BTC_JPY 的。",
    expectFixed = "切到 ETH_JPY 後，價格顯示 12345.67；切回 BTC_JPY 顯示 12345。",
    file = "kotlin06-recomposition/L06_RememberKey.kt",
    demo = { variant, log -> L06Demo(variant, log) },
)
