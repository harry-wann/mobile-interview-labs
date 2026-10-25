// L04：用了 strong skipping，列表還是每次都重組（每次產生內容相同的新 List）
// 對應複習站：Kotlin06Recomposition 第 4 題「strong skipping mode
// 改變了什麼？哪個版本開始預設開啟？」
//
// 情境：自選清單從所有幣種裡篩出「我的最愛」，交給 TickList 顯示。
// 畫面上方的「最後更新」計數每 300 ms 加 1（共 20 次），
//        allTicks 本身整個過程都沒變。專案用 Kotlin 2.x，strong skipping
// 預設開啟。
//
// 你要寫：
//   ① Bug 版（L04BugScreen）：直接 allTicks.filter { it.isFavorite }。
// 每次重組都產生新的 List 物件，=== 比較失敗，TickList 每次都重組。
//   ② 修正版（L04FixedScreen）：用 remember(allTicks) { allTicks.filter { … }
// }，allTicks 沒變就沿用同一個 List，TickList 被跳過。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，
// 看橘色的重組次數（或畫面）是不是照預期。
// 解答：solution/kotlin/kotlin06-recomposition/L04_NewListEachTime.kt
package tw.harry.labs.practice.kotlin06recomposition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

private data class TickUi(
    val symbol: String,
    val price: Long,
    val isFavorite: Boolean,
)

/**
 * 列表（兩個版本共用）：參數是 List<TickUi>，List 是 unstable，strong skipping
 * 用 === 比較
 */
@Composable
private fun TickList(ticks: List<TickUi>) {
    RecomposeCounter("TickList")
    ticks.forEach { Text("${it.symbol}　${it.price}") }
}

// MARK: - Bug 版
@Composable
private fun L04BugScreen(allTicks: List<TickUi>, updates: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("最後更新：第 $updates 次")
        // TODO ①：val favorites = allTicks.filter { it.isFavorite
        // }（每次重組都產生新的 List 物件），再 TickList(favorites)
        val favorites = emptyList<TickUi>()
        TickList(favorites)
    }
}

// MARK: - 修正版
@Composable
private fun L04FixedScreen(allTicks: List<TickUi>, updates: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("最後更新：第 $updates 次")
        // TODO ②：val favorites = remember(allTicks) { allTicks.filter { it.
        // isFavorite } }（allTicks 沒變就沿用同一個 List），再
        // TickList(favorites)
        val favorites = emptyList<TickUi>()
        TickList(favorites)
    }
}

// MARK: - 演練設定
@Composable
private fun L04Demo(variant: Variant, log: LabLog) {
    var round by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { round++ }) { Text("重新開始（第 ${round + 1} 輪）") }
        key(round) {
            val allTicks = remember {
                listOf(
                    TickUi("BTC", 10_000_000, isFavorite = true),
                    TickUi("ETH", 400_000, isFavorite = true),
                    TickUi("XRP", 80, isFavorite = false),
                )
            }
            var updates by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                repeat(20) {
                    delay(300)
                    updates++
                }
                log("更新完畢（共 20 次）")
            }
            when (variant) {
                Variant.BUG -> L04BugScreen(allTicks, updates)
                Variant.FIXED -> L04FixedScreen(allTicks, updates)
            }
        }
    }
}

val l04 = Lab(
    id = "L04",
    title = "開了 strong skipping，列表還是每次都重組",
    question = "第 4 題：strong skipping mode 改變了什麼？哪個版本開始預設開啟？",
    goal = "allTicks 沒有變，TickList 卻跟著計數一起重組。先寫出每次產生新 List 的 Bug 版，再用 remember 修好。",
    steps = listOf(
        "打開 practice/kotlin/kotlin06-recomposition/L04_NewListEachTime.kt，寫 TODO ①（Bug 版）。",
        "App 選「練習」「Bug 版」，等約 6 秒（更新 20 次），看橘色數字：TickList 重組了幾次？",
        "寫 TODO ②（修正版），選「修正版」，等約 6 秒，看 TickList 重組了幾次。",
        "想一想：不用 remember，讓資料層只在內容真的改變時才產生新 List（distinctUntilChanged），效果一樣嗎？（一樣，目的都是讓 === 比較時拿到同一個物件）",
    ),
    expectBug = "TickList 的次數一直增加，更新完畢後約 21 次。",
    expectFixed = "TickList 維持 1 次（被跳過）。",
    file = "kotlin06-recomposition/L04_NewListEachTime.kt",
    demo = { variant, log -> L04Demo(variant, log) },
)
