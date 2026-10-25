// L01：價格閃一下綠色，那一列每個畫面格都在重組（延後讀取：drawBehind）
// 對應複習站：Kotlin08ComposePerformance 第 2 題「延後讀取（deferred read）
// 是什麼？」
//
// 情境：行情列表的一列（TickRow）在價格變動時，背景閃一下綠色，400 ms
// 內從綠色漸變到透明。
//        按「價格跳一下」模擬價格變動。畫面上的橘色數字顯示 TickRow
// 重組了幾次。
// Bug 版：Modifier.background(Color.Green.copy(alpha = flash.value * 0.3f))。
// 建立 Modifier 的當下就讀了 flash.value，
//        這個讀取發生在 Composition 階段。動畫的每個畫面格（frame）值都不一樣，
// TickRow 每一格都重組一次。
// 修正版：改用 Modifier.drawBehind { drawRect(…) }。大括號裡的讀取延後到
// Drawing 階段才發生，
//        動畫期間只需要重新繪製，TickRow 完全不重組。
package tw.harry.labs.solution.kotlin08composeperformance

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

// MARK: - Bug 版
private object L01Bug {
    @Composable
    fun TickRow(symbol: String, price: Long) {
        // 閃色動畫的進度：1 是最亮，0 是透明
        val flash = remember { Animatable(0f) }
        // 價格一變，就播一次閃色動畫
        LaunchedEffect(price) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(400))
        }
        Row(
            Modifier
                .fillMaxWidth()
                // 在這裡讀 flash.value：Composition 階段
                .background(Color.Green.copy(alpha = flash.value * 0.3f))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$symbol　$price")
            RecomposeCounter("TickRow")
        }
    }
}

// MARK: - 修正版
private object L01Fixed {
    @Composable
    fun TickRow(symbol: String, price: Long) {
        val flash = remember { Animatable(0f) }
        LaunchedEffect(price) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(400))
        }
        Row(
            Modifier
                .fillMaxWidth()
                // 大括號在 Drawing 階段才執行
                .drawBehind {
                    drawRect(
                        Color.Green.copy(
                            alpha =
                                flash.value * 0.3f,
                        ),
                    )
                }
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$symbol　$price")
            RecomposeCounter("TickRow")
        }
    }
}

// MARK: - 演練設定
@Composable
private fun L01Demo(variant: Variant, log: LabLog) {
    var price by remember { mutableLongStateOf(100_000L) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            price += 100
            log("價格跳到 $price（動畫約 400 ms）")
        }) { Text("價格跳一下") }
        when (variant) {
            Variant.BUG -> L01Bug.TickRow("BTC_JPY", price)
            Variant.FIXED -> L01Fixed.TickRow("BTC_JPY", price)
        }
    }
}

val l01 = Lab(
    id = "L01",
    title = "價格閃一下，那一列每格都重組",
    question = "第 2 題：延後讀取（deferred read）是什麼？",
    goal = "價格變動時背景閃一下綠色（400 ms）。看把動畫的值直接放進 Modifier.background 時 TickRow 重組幾次，再改用 drawBehind 延後到 Drawing 階段才讀。",
    steps = listOf(
        "選「Bug 版」，等畫面穩定（進入畫面時會先閃一次），記下橘色的「重組 TickRow：N 次」，再按一次「價格跳一下」，看 N 增加多少。",
        "選「修正版」，做一樣的事，確認每按一次只增加 1 次（價格文字變了，那一次重組是必要的）。",
        "想一想：offset 也有同樣的問題嗎？（有：Modifier.offset(x.dp) 在 Composition 階段讀，改成 Modifier.offset { IntOffset(…) } 就延後到 Layout 階段）",
    ),
    expectBug = "進入畫面時先閃一次，TickRow 的次數會一路增加到約 25 次；之後每按一次「價格跳一下」再增加約 25 次（400 ms 內每個畫面格重組一次）。",
    expectFixed = "TickRow 維持 1 次，閃色動畫照常播放；每按一次「價格跳一下」只增加 1 次（價格文字變了）。",
    file = "kotlin08-compose-performance/L01_DeferredRead.kt",
    demo = { variant, log -> L01Demo(variant, log) },
)
