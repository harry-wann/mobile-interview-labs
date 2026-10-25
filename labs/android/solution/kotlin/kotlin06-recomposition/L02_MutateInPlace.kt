// L02：按了「收到新報價」，清單明明變長了，畫面卻沒更新（原地修改 State 裡的物件）
// 對應複習站：Kotlin06Recomposition 第 2 題「Compose 怎麼知道哪個 State 被誰讀過？」
//
// 情境：行情畫面把收到的報價放進清單，並顯示「畫面顯示 N 筆」。按下「收到一筆新報價」模擬 WebSocket 推來一筆。
//        log 會印出清單裡真正有幾筆。
// Bug 版：用 remember { mutableStateOf(mutableListOf()) }，按鈕裡 ticks.value.add(…)。
//        State 裡存的還是同一個 list，Compose 只看得到「State 的值被換掉」，看不到你改了 list 的內容，所以不會重組。
// 修正版：改用 mutableStateListOf()。它自己會在內容改變時通知 Compose。（也可以每次換成新的 list：ticks = ticks + tick）
package tw.harry.labs.solution.kotlin06recomposition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class FeedTick(val symbol: String, val price: Long)

// MARK: - Bug 版
@Composable
private fun L02BugScreen(log: LabLog) {
    val ticks = remember { mutableStateOf(mutableListOf<FeedTick>()) }   // State 裡存一個會被原地修改的 list
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            ticks.value.add(FeedTick("BTC_JPY", 10_000_000L + ticks.value.size))   // 原地修改：Compose 不知道
            log("收到一筆報價，list 裡現在有 ${ticks.value.size} 筆")
        }) { Text("收到一筆新報價") }
        Text("畫面顯示 ${ticks.value.size} 筆")
    }
}

// MARK: - 修正版
@Composable
private fun L02FixedScreen(log: LabLog) {
    val ticks = remember { mutableStateListOf<FeedTick>() }              // 內容改變時會通知 Compose 的 list
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            ticks.add(FeedTick("BTC_JPY", 10_000_000L + ticks.size))
            log("收到一筆報價，list 裡現在有 ${ticks.size} 筆")
        }) { Text("收到一筆新報價") }
        Text("畫面顯示 ${ticks.size} 筆")
    }
}

// MARK: - 演練設定
@Composable
private fun L02Demo(variant: Variant, log: LabLog) {
    when (variant) {
        Variant.BUG -> L02BugScreen(log)
        Variant.FIXED -> L02FixedScreen(log)
    }
}

val l02 = Lab(
    id = "L02",
    title = "清單變長了，畫面卻沒更新",
    question = "第 2 題：Compose 怎麼知道哪個 State 被誰讀過？",
    goal = "按下按鈕把報價加進清單。看原地修改 State 裡的 list 為什麼不會讓畫面更新，再改用 mutableStateListOf。",
    steps = listOf(
        "選「Bug 版」，連按三次「收到一筆新報價」，比較 log 的筆數和畫面上的「畫面顯示 N 筆」。",
        "選「修正版」，同樣按三次，確認畫面跟著更新。",
        "想一想：把 Bug 版改成 ticks.value = ticks.value + tick（每次換成新的 list）可以嗎？（可以，State 的值被換掉了）",
    ),
    expectBug = "log 印出「list 裡現在有 3 筆」，畫面卻一直是「畫面顯示 0 筆」。",
    expectFixed = "畫面跟 log 一樣，按三次後是「畫面顯示 3 筆」。",
    file = "kotlin06-recomposition/L02_MutateInPlace.kt",
    demo = { variant, log -> L02Demo(variant, log) },
)
