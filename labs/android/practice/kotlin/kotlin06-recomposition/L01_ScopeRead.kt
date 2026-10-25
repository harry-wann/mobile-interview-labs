// L01：價格每秒跳好幾次，整個畫面都跟著重組（State 在哪裡讀，哪裡就重組）
// 對應複習站：Kotlin06Recomposition 第 1 題「什麼是重組？State 變了，
// 畫面上哪一塊會重畫？」
//
// 情境：行情畫面有標題區（ChartHeader）和價格文字。價格每 250 ms 更新一次，共
// 20 次。畫面上的橘色數字顯示「重組幾次」。
//
// 你要寫：
//   ① Bug 版（L01Bug）：在 PriceScreen 的最上層讀 price，並把 Text 寫在 Column
// { } 裡。Column 是 inline，整個 PriceScreen 每次都重新執行。
//   ② 修正版（L01Fixed）：把「讀 price」的地方往下推到小的 PriceText。只有
// PriceText 重組，PriceScreen 不動。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，
// 看橘色的重組次數（或畫面）是不是照預期。
// 解答：solution/kotlin/kotlin06-recomposition/L01_ScopeRead.kt
package tw.harry.labs.practice.kotlin06recomposition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

/** 標題區：參數不會變，所以父層重組時它會被跳過（兩個版本共用） */
@Composable
private fun ChartHeader(title: String) {
    RecomposeCounter("ChartHeader")
    Text(title)
}

// MARK: - Bug 版
private object L01Bug {
    @Composable
    fun PriceScreen(prices: StateFlow<Long>) {
        // TODO ①-1：在這裡讀價格：val price by prices.collectAsState()
        // （PriceScreen 的最上層）
        Column {
            RecomposeCounter("PriceScreen")
            ChartHeader("BTC/JPY")
            // TODO ①-2：顯示 Text("價格：$price")（寫在 Column 裡：Column 是
            // inline，讀取算在 PriceScreen 身上）
        }
    }
}

// MARK: - 修正版
private object L01Fixed {
    @Composable
    fun PriceScreen(prices: StateFlow<Long>) {
        Column {
            RecomposeCounter("PriceScreen")
            ChartHeader("BTC/JPY")
            // TODO ②-1：呼叫 PriceText(prices)：這裡只傳 Flow，不要在
            // PriceScreen 裡讀 price
        }
    }

    @Composable
    fun PriceText(prices: StateFlow<Long>) {
        // TODO ②-2：在這個小函式裡讀價格：val price by prices.
        // collectAsState()，並顯示 Text("價格：$price")
        RecomposeCounter("PriceText")
    }
}

// MARK: - 演練設定
@Composable
private fun L01Demo(variant: Variant, log: LabLog) {
    var round by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { round++ }) { Text("重新開始（第 ${round + 1} 輪）") }
        key(round) {
            val prices = remember { MutableStateFlow(100_000L) }
            LaunchedEffect(Unit) {
                repeat(20) {
                    delay(250)
                    prices.value += 1
                }
                log("價格更新完畢（共 20 次）")
            }
            when (variant) {
                Variant.BUG -> L01Bug.PriceScreen(prices)
                Variant.FIXED -> L01Fixed.PriceScreen(prices)
            }
        }
    }
}

val l01 = Lab(
    id = "L01",
    title = "價格一跳，整個畫面都跟著重組",
    question = "第 1 題：什麼是重組？State 變了，畫面上哪一塊會重畫？",
    goal = "價格每 250 ms 更新一次。先寫出在最上層讀 State 的 Bug 版，看重組的範圍有多大，再把讀取往下推。",
    steps = listOf(
        "打開 practice/kotlin/kotlin06-recomposition/L01_ScopeRead.kt，寫 TODO ①-1、①-2（Bug 版）。",
        "App 選「練習」「Bug 版」，等約 5 秒（價格更新 20 次），看橘色數字：PriceScreen 重組了幾次、ChartHeader 呢？",
        "寫 TODO ②-1、②-2（修正版），選「修正版」，等約 5 秒，看 PriceScreen、PriceText 各重組了幾次。",
        "想一想：Bug 版的 ChartHeader 為什麼沒有跟著重組？（它的參數 \"BTC/JPY\" 沒有變，Compose 跳過了它）",
    ),
    expectBug = "PriceScreen 的次數一直增加，更新完畢後約 21 次；ChartHeader 維持 1 次（被跳過）。",
    expectFixed = "PriceScreen 與 ChartHeader 都維持 1 次；只有 PriceText 一直增加，更新完畢後約 21 次。",
    file = "kotlin06-recomposition/L01_ScopeRead.kt",
    demo = { variant, log -> L01Demo(variant, log) },
)
