// L05：ViewModel 自己開 scope，關掉畫面後還在背景輪詢（viewModelScope）
// 對應複習站：Kotlin03Coroutines 第 5 題「viewModelScope 和 lifecycleScope 是什麼？dispatcher 為什麼要注入？」
//
// 情境：行情畫面的 ViewModel 一建立就開始每 0.5 秒輪詢價格。這個演練用畫面操作：「打開行情畫面」「關閉行情畫面」。
// Bug 版：ViewModel 裡自己建立一個 CoroutineScope。畫面關掉、ViewModel 被清掉（onCleared）時，
//        沒有人取消這個 scope，輪詢在背景繼續跑。每打開一次畫面就多一個。
// 修正版：用 viewModelScope。它在 onCleared 時自動取消，底下的輪詢一起停。
package tw.harry.labs.solution.kotlin03coroutines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.harry.labs.kit.FakeScreenHost
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.LiveObjects
import tw.harry.labs.kit.LiveObjectsView
import tw.harry.labs.kit.Variant

private const val POLLING = "輪詢中"

/** 每 0.5 秒輪詢一次價格。為了不讓洩漏的輪詢一直跑到 App 關掉，最多 30 次（15 秒）就停 */
private suspend fun pollPrices(log: LabLog) {
    LiveObjects.add(POLLING)
    try {
        repeat(30) { i ->
            delay(500)
            log("輪詢價格 #${i + 1}")
        }
    } finally {
        LiveObjects.remove(POLLING)
    }
}

// MARK: - Bug 版
private object L05Bug {
    class PriceViewModel(log: LabLog) : ViewModel() {
        private val scope = CoroutineScope(Dispatchers.Main)    // 自己開的 scope，沒有人會取消它

        init {
            scope.launch { pollPrices(log) }
        }
    }
}

// MARK: - 修正版
private object L05Fixed {
    class PriceViewModel(log: LabLog) : ViewModel() {
        init {
            viewModelScope.launch { pollPrices(log) }           // onCleared 時自動取消
        }
    }
}

// MARK: - 演練設定
@Composable
private fun L05Demo(variant: Variant, log: LabLog) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveObjectsView(POLLING)
        FakeScreenHost(log, name = "行情畫面") {
            // 每次打開畫面都會拿到一個新的 ViewModel；關閉時它的 onCleared 會被呼叫
            when (variant) {
                Variant.BUG -> viewModel { L05Bug.PriceViewModel(log) }
                Variant.FIXED -> viewModel { L05Fixed.PriceViewModel(log) }
            }
            Text("行情畫面打開中，ViewModel 每 0.5 秒輪詢一次價格")
        }
    }
}

val l05 = Lab(
    id = "L05",
    title = "關掉畫面後，ViewModel 還在背景輪詢",
    question = "第 5 題：viewModelScope 和 lifecycleScope 是什麼？dispatcher 為什麼要注入？",
    goal = "關掉行情畫面後，輪詢應該停止。反覆開關幾次，看「存活中 輪詢中」的數字。",
    steps = listOf(
        "選「Bug 版」，按「打開行情畫面」，等 log 印幾筆，再按「關閉行情畫面」。",
        "重複開關 3 次，看上方「存活中 輪詢中」的數字，以及 log 印的速度。",
        "等 Bug 版的輪詢停下來（「輪詢中」回到 0），按「清除 log」，再選「修正版」做一樣的事。",
    ),
    expectBug = "關掉畫面後 log 繼續印；每開關一次「輪詢中」就加 1，log 越印越快（每個輪詢最多跑 30 次，約 15 秒後才停）。",
    expectFixed = "關掉畫面後立刻停止，「輪詢中」回到 0。",
    file = "kotlin03-coroutines/L05_ViewModelScope.kt",
    demo = { variant, log -> L05Demo(variant, log) },
)
