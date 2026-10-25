// L07：新插入的幣種顯示成展開，原本展開的卻收起來了（LazyColumn 沒給 key）
// 對應複習站：Kotlin06Recomposition 第 6 題「remember、rememberSaveable 和 key 怎麼用？」
//
// 情境：行情列表每一列有一個「展開詳細資料」的狀態，用 remember 保存在那一列裡。
//        使用者把 BTC 展開，這時列表最上面插入了一檔新的 SOL。
// Bug 版：items(coins) { … } 沒有給 key。沒給 key 時，Compose 用「位置」當身分，展開的狀態留在原本的位置（第 1 列），
//        但第 1 列現在是 SOL：畫面變成「SOL 被展開、BTC 卻收起來」。
// 修正版：items(coins, key = { it.symbol }) { … }。Compose 用 symbol 辨認每一列，展開的狀態跟著 BTC 走。
//        key 必須唯一而且穩定（同一檔幣種的 key 不能變）。
package tw.harry.labs.solution.kotlin06recomposition

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Coin(val symbol: String)

/** 一列（兩個版本共用）：點一下展開或收起，展開的狀態用 remember 保存在這一列裡 */
@Composable
private fun CoinRow(coin: Coin) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
    ) {
        Text("${if (expanded) "▼" else "▶"} ${coin.symbol}")
        if (expanded) Text("　${coin.symbol} 的詳細資料：成交量、24 小時高低價…")
    }
}

// MARK: - Bug 版
@Composable
private fun L07BugList(coins: List<Coin>) {
    LazyColumn(Modifier.height(240.dp)) {
        items(coins) { coin -> CoinRow(coin) }                              // 沒給 key：用位置當身分
    }
}

// MARK: - 修正版
@Composable
private fun L07FixedList(coins: List<Coin>) {
    LazyColumn(Modifier.height(240.dp)) {
        items(coins, key = { it.symbol }) { coin -> CoinRow(coin) }         // 用 symbol 當身分
    }
}

// MARK: - 演練設定
@Composable
private fun L07Demo(variant: Variant, log: LabLog) {
    val coins = remember { mutableStateListOf(Coin("BTC"), Coin("ETH"), Coin("XRP")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = coins.none { it.symbol == "SOL" },
            onClick = {
                coins.add(0, Coin("SOL"))
                log("在最上面插入 SOL")
            },
        ) { Text("在最上面插入 SOL") }
        when (variant) {
            Variant.BUG -> L07BugList(coins)
            Variant.FIXED -> L07FixedList(coins)
        }
    }
}

val l07 = Lab(
    id = "L07",
    title = "插入新幣種後，展開的狀態黏錯列",
    question = "第 6 題：remember、rememberSaveable 和 key 怎麼用？",
    goal = "把 BTC 展開，再在最上面插入 SOL。看沒給 key 時，展開的狀態為什麼黏在位置上而不是黏在 BTC 上。",
    steps = listOf(
        "選「Bug 版」，點一下 BTC 那一列展開，再按「在最上面插入 SOL」，看哪一列是展開的。",
        "選「修正版」，同樣操作，確認展開的還是 BTC。",
        "想一想：key 用列表的位置（index）可以嗎？（不行，那就和沒給一樣；key 要用資料本身唯一而且穩定的值）",
    ),
    expectBug = "插入 SOL 後，SOL 那一列是展開的，BTC 反而收起來了。",
    expectFixed = "插入 SOL 後，SOL 收起來，BTC 仍然展開。",
    file = "kotlin06-recomposition/L07_LazyColumnKey.kt",
    demo = { variant, log -> L07Demo(variant, log) },
)
