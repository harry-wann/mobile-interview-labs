// L02：警示標籤淡入淡出，連父層的卡片也每格重組（把動畫值傳給子層，改傳 () ->
// Float）
// 對應複習站：Kotlin08ComposePerformance 第 2 題「延後讀取（deferred read）
// 是什麼？」
//
// 情境：交易卡片（TradeCard）有一個警示標籤（AlertBadge），按「切換警示」時，
// 標籤用 600 ms 淡入或淡出。
//        畫面上的橘色數字顯示 TradeCard 與 AlertBadge 各重組了幾次。
// Bug 版：TradeCard 用 animateFloatAsState 取得 alpha，直接把 Float 傳給
// AlertBadge，AlertBadge 再用 Modifier.alpha(alpha)。
//        讀取動畫值發生在 TradeCard 的 Composition 階段，動畫期間每一格，
// TradeCard 與 AlertBadge 都重組。
// 修正版：TradeCard 只把 State 包成函式 { alpha.value } 傳下去，AlertBadge 在
// graphicsLayer { } 的大括號裡才呼叫它。
//        讀取延後到 Drawing 階段，動畫期間 TradeCard 與 AlertBadge 都不用重組。
package tw.harry.labs.solution.kotlin08composeperformance

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
        val alpha by animateFloatAsState(
            if (alert) 1f else 0.15f,
            tween(600),
            label = "alert",
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecomposeCounter("TradeCard")
            // 在這裡讀 alpha：Composition 階段
            AlertBadge(alpha = alpha)
        }
    }

    @Composable
    fun AlertBadge(alpha: Float) {
        RecomposeCounter("AlertBadge")
        Text(
            "警示：價格偏離",
            Modifier.alpha(alpha).background(Color(0xFFFFCDD2)).padding(8.dp),
        )
    }
}

// MARK: - 修正版
private object L02Fixed {
    @Composable
    fun TradeCard(alert: Boolean) {
        val alpha =
            animateFloatAsState(
                if (alert) 1f else 0.15f,
                tween(600),
                label = "alert",
            )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecomposeCounter("TradeCard")
            // 只傳函式，沒有讀 alpha.value
            AlertBadge(alpha = { alpha.value })
        }
    }

    @Composable
    fun AlertBadge(alpha: () -> Float) {
        RecomposeCounter("AlertBadge")
        Text(
            "警示：價格偏離",
            Modifier
                // 大括號在 Drawing 階段才執行，這時才讀 alpha
                .graphicsLayer { this.alpha = alpha() }
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
    goal = "按「切換警示」，標籤用 600 ms 淡入淡出。看直接傳 Float 時父層與子層各重組幾次，再改傳 () -> Float 並用 graphicsLayer 讀取。",
    steps = listOf(
        "選「Bug 版」，記下橘色的 TradeCard 與 AlertBadge 次數，按一次「切換警示」，等動畫結束（約 1 秒），看兩個次數各增加多少。",
        "選「修正版」，做一樣的事，確認 TradeCard 只增加 1 次（alert 參數變了，那一次是必要的）、AlertBadge 沒有增加，標籤照常淡入淡出。",
        "想一想：為什麼傳 { alpha.value } 比傳 alpha.value 好？（前者是函式，讀取發生在拿到函式的那一層呼叫它的時候；後者在傳參數時就讀了）",
    ),
    expectBug = "按一次「切換警示」，TradeCard 與 AlertBadge 的次數各增加約 35 次（600 ms 內每個畫面格重組一次）。",
    expectFixed = "按一次「切換警示」，TradeCard 只增加 1 次（alert 參數變了，那一次是必要的），AlertBadge 維持 1 次，標籤照常淡入淡出。",
    file = "kotlin08-compose-performance/L02_AlphaLambda.kt",
    demo = { variant, log -> L02Demo(variant, log) },
)
