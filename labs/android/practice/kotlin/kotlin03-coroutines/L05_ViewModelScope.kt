// L05：ViewModel 自己開 scope，關掉畫面後還在背景輪詢（viewModelScope）
// 對應複習站：Kotlin03Coroutines 第 5 題「viewModelScope 和 lifecycleScope 是什麼？dispatcher 為什麼要注入？」
//
// 情境：行情畫面的 ViewModel 一建立就開始每 0.5 秒輪詢價格。這個演練用畫面操作：「打開行情畫面」「關閉行情畫面」。
//
// 你要寫：
//   ① Bug 版：在 ViewModel 裡自己建立 CoroutineScope 來輪詢。反覆開關畫面，會看到「輪詢中」一直增加。
//   ② 修正版：改用 viewModelScope。
// 只改有 TODO 的地方。解答：solution/kotlin/kotlin03-coroutines/L05_ViewModelScope.kt
package tw.harry.labs.practice.kotlin03coroutines

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

/** 每 0.5 秒輪詢一次價格（已寫好）。最多 30 次（15 秒）就停，避免洩漏的輪詢一直跑 */
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
        // TODO ①-1：宣告一個自己的 scope：CoroutineScope(Dispatchers.Main)

        init {
            // TODO ①-2：在自己的 scope 裡 launch，呼叫 pollPrices(log)
            log("（Bug 版的 ViewModel 還沒開始輪詢）")
        }
    }
}

// MARK: - 修正版
private object L05Fixed {
    class PriceViewModel(log: LabLog) : ViewModel() {
        init {
            // TODO ②：用 viewModelScope.launch 呼叫 pollPrices(log)
            log("（修正版的 ViewModel 還沒開始輪詢）")
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
    goal = "關掉行情畫面後，輪詢應該停止。先寫出會留在背景的 Bug 版，再寫修正版，反覆開關畫面比較。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」，按「打開行情畫面」，等 log 印幾筆，再按「關閉行情畫面」。",
        "重複開關 3 次，看上方「存活中 輪詢中」的數字，以及 log 印的速度。",
        "寫 TODO ②，等 Bug 版的輪詢停下來（「輪詢中」回到 0），按「清除 log」，選「修正版」做一樣的事。",
    ),
    expectBug = "關掉畫面後 log 繼續印；每開關一次「輪詢中」就加 1，log 越印越快（每個輪詢最多跑 30 次，約 15 秒後才停）。",
    expectFixed = "關掉畫面後立刻停止，「輪詢中」回到 0。",
    file = "kotlin03-coroutines/L05_ViewModelScope.kt",
    demo = { variant, log -> L05Demo(variant, log) },
)
