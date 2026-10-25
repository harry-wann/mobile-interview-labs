// L03：在清單最前面插入一檔幣種，原本的每一列都跟著重組（LazyColumn 沒給 key）
// 對應複習站：Kotlin08ComposePerformance 第 3 題「LazyColumn 為什麼一定要給 key？contentType 是什麼？」
//
// 情境：自選清單用 LazyColumn 顯示 6 檔幣種，每一列右邊的橘色數字是這一列重組了幾次。按「在最上面插入 SOL」新增一檔。
// Bug 版：items(coins) { … } 沒有給 key。沒給 key 時，Compose 用「位置」當身分。插入之後，每個位置對應的資料都往下移了一格，
//        所有位置的參數都變了，原本那些位置的列都重組一次。
// 修正版：items(coins, key = { it.symbol }) { … }。Compose 用 symbol 辨認每一列，知道 BTC 只是往下移動，參數沒變，
//        可以跳過重組；只有新插入的 SOL 那一列需要組合。
package tw.harry.labs.solution.kotlin08composeperformance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

private class L03Coin(val symbol: String)

/** 一列（兩個版本共用）：參數沒變時會被跳過，橘色數字是這一列重組的次數 */
@Composable
private fun L03CoinRow(coin: L03Coin) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(coin.symbol, Modifier.width(64.dp))
        RecomposeCounter(coin.symbol)
    }
}

// MARK: - Bug 版
@Composable
private fun L03BugList(coins: List<L03Coin>) {
    LazyColumn(Modifier.height(280.dp)) {
        items(coins) { coin -> L03CoinRow(coin) }                           // 沒給 key：用位置當身分
    }
}

// MARK: - 修正版
@Composable
private fun L03FixedList(coins: List<L03Coin>) {
    LazyColumn(Modifier.height(280.dp)) {
        items(coins, key = { it.symbol }) { coin -> L03CoinRow(coin) }      // 用 symbol 當身分
    }
}

// MARK: - 演練設定
@Composable
private fun L03Demo(variant: Variant, log: LabLog) {
    val coins = remember { listOf("BTC", "ETH", "XRP", "ADA", "DOGE", "LTC").map { L03Coin(it) }.toMutableStateList() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = coins.none { it.symbol == "SOL" },
            onClick = {
                coins.add(0, L03Coin("SOL"))
                log("在最上面插入 SOL")
            },
        ) { Text("在最上面插入 SOL") }
        when (variant) {
            Variant.BUG -> L03BugList(coins)
            Variant.FIXED -> L03FixedList(coins)
        }
    }
}

val l03 = Lab(
    id = "L03",
    title = "插入一檔幣種，原本每一列都重組",
    question = "第 3 題：LazyColumn 為什麼一定要給 key？contentType 是什麼？",
    goal = "在清單最前面插入 SOL。看沒給 key 時，原本的每一列為什麼都重組一次，再用 symbol 當 key。",
    steps = listOf(
        "選「Bug 版」，按「在最上面插入 SOL」，看每一列右邊的橘色數字（重組幾次）。",
        "選「修正版」，同樣操作，確認原本的 BTC、ETH… 仍然是 1 次，只有 SOL 是新的。",
        "想一想：contentType 又是做什麼的？（告訴 Compose 哪些項目的結構相同，可以重用已經建好的結構；列表混有標題、廣告、一般列時才看得出差別）",
    ),
    expectBug = "插入 SOL 之後，原本那 6 個位置的橘色數字都變成 2 次（每個位置的資料都換了）；最下面新增的第 7 列是新組合的，是 1 次。",
    expectFixed = "插入 SOL 之後，SOL 是 1 次，原本的 BTC、ETH… 到 LTC 6 列都仍然是 1 次（被跳過，只是往下移動）。",
    file = "kotlin08-compose-performance/L03_ListKey.kt",
    demo = { variant, log -> L03Demo(variant, log) },
)
