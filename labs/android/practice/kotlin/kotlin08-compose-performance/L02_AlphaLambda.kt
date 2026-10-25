// L02：警示標籤淡入淡出，連父層的卡片也每格重組（把動畫值傳給子層，改傳 () ->
// Float）
// 對應複習站：Kotlin08ComposePerformance 第 2 題「延後讀取（deferred read）
// 是什麼？」
//
// 情境：交易卡片（TradeCard）有一個警示標籤（AlertBadge），按「切換警示」時，
// 標籤用 600 ms 淡入或淡出。
//        畫面上的橘色數字顯示 TradeCard 與 AlertBadge 各重組了幾次。
//
// 你要寫：
//   ① Bug 版（L02Bug）：TradeCard 用 val alpha by animateFloatAsState(…) 取得
// alpha，把 Float 傳給 AlertBadge，AlertBadge 用 Modifier.alpha(alpha)。
//   ② 修正版（L02Fixed）：TradeCard 用 val alpha = animateFloatAsState(…)
// （不加 by），傳 { alpha.value } 給 AlertBadge；AlertBadge 在 graphicsLayer {
// } 裡呼叫它。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，
// 看橘色的重組次數是不是照預期。
// 解答：solution/kotlin/kotlin08-compose-performance/L02_AlphaLambda.kt
package tw.harry.labs.practice.kotlin08composeperformance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L02Bug {
    @Composable
    fun TradeCard(alert: Boolean) {
        // TODO ①-1：val alpha by animateFloatAsState(if (alert) 1f else 0.15f,
        // tween(600), label = "alert")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecomposeCounter("TradeCard")
            // TODO ①-2：把 1f 換成上面的 alpha（Float：在這裡讀，Composition
            // 階段）
            AlertBadge(alpha = 1f)
        }
    }

    @Composable
    fun AlertBadge(alpha: Float) {
        RecomposeCounter("AlertBadge")
        // TODO ①-3：在 Modifier 的最前面加上 .alpha(alpha)
        Text("警示：價格偏離", Modifier.background(Color(0xFFFFCDD2)).padding(8.dp))
    }
}

// MARK: - 修正版
private object L02Fixed {
    @Composable
    fun TradeCard(alert: Boolean) {
        // TODO ②-1：val alpha = animateFloatAsState(if (alert) 1f else 0.15f,
        // tween(600), label = "alert")（不要加 by，這裡不讀 value）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecomposeCounter("TradeCard")
            // TODO ②-2：把 { 1f } 換成 { alpha.value }（只傳函式，沒有讀 alpha.
            // value）
            AlertBadge(alpha = { 1f })
        }
    }

    @Composable
    fun AlertBadge(alpha: () -> Float) {
        RecomposeCounter("AlertBadge")
        Text(
            "警示：價格偏離",
            Modifier
                // TODO ②-3：加上 graphicsLayer { this.alpha = alpha()
                // }（大括號在 Drawing 階段才執行，這時才讀 alpha）
                .background(Color(0xFFFFCDD2))
                .padding(8.dp),
        )
    }
}

// MARK: - 演練設定
@Composable
private fun L02Demo(variant: Variant, log: LabLog) {
    var alert by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            alert = !alert
            log(if (alert) "警示淡入（約 600 ms）" else "警示淡出（約 600 ms）")
        }) { Text("切換警示") }
        when (variant) {
            Variant.BUG -> L02Bug.TradeCard(alert)
            Variant.FIXED -> L02Fixed.TradeCard(alert)
        }
    }
}

val l02 = Lab(
    id = "L02",
    title = "標籤淡入淡出，連父層卡片也每格重組",
    question = "第 2 題：延後讀取（deferred read）是什麼？",
    goal = "按「切換警示」，標籤用 600 ms 淡入淡出。看直接傳 Float 時父層與子層各重組幾次，再改傳 () -> Float 並用 graphicsLayer 讀取。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "打開 practice/kotlin/kotlin08-compose-performance/L02_AlphaLambda.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」，記下橘色的 TradeCard 與 AlertBadge 次數，按一次「切換警示」，等動畫結束（約 1 秒），看兩個次數各增加多少。",
        "寫 TODO ②（修正版），選「修正版」，做一樣的事，確認 TradeCard 只增加 1 次（alert 參數變了，那一次是必要的）、AlertBadge 沒有增加，標籤照常淡入淡出。",
        "想一想：為什麼傳 { alpha.value } 比傳 alpha.value 好？（前者是函式，讀取發生在拿到函式的那一層呼叫它的時候；後者在傳參數時就讀了）",
    ),
    expectBug = "按一次「切換警示」，TradeCard 與 AlertBadge 的次數各增加約 35 次（600 ms 內每個畫面格重組一次）。",
    expectFixed = "按一次「切換警示」，TradeCard 只增加 1 次（alert 參數變了，那一次是必要的），AlertBadge 維持 1 次，標籤照常淡入淡出。",
    file = "kotlin08-compose-performance/L02_AlphaLambda.kt",
    demo = { variant, log -> L02Demo(variant, log) },
)
