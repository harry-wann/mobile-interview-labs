// L03：資料明明沒變，卡片還是每次都重組（含 List 的 class 是 unstable）
// 對應複習站：Kotlin06Recomposition 第 3 題「為什麼資料明明沒變，這個 Composable 還是每次都重組？」
//
// 情境：自選清單畫面上方有一個「最後更新」計數（每 300 ms 加 1，共 20 次），下方是 WatchlistCard，顯示一個 Board（名稱 + 幣種清單）。
//        Board 的內容整個過程都沒變，只是畫面每次重組都用同樣的內容建立一個新的 Board 傳給卡片。
//        專案用的是 Kotlin 2.x，strong skipping 預設開啟。
//
// 你要寫：
//   ① Bug 版（L03Bug）：每次重組都 WatchlistCard(Board("主要幣種", ticks))。Board 的 ticks 是 List<WatchTick>（unstable），
//      strong skipping 用 === 比較，每次都是新的 Board，卡片每次都重組。
//   ② 修正版（L03Fixed）：Board 的 ticks 改成 ImmutableList<WatchTick>，並用 toImmutableList() 建立。Board 變成 stable，改用 equals 比較，卡片被跳過。
// 只改有 TODO 的地方。寫完在 App 選「練習」「Bug 版」「修正版」，看橘色的重組次數（或畫面）是不是照預期。
// 解答：solution/kotlin/kotlin06-recomposition/L03_UnstableType.kt
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList   // 寫 TODO ② 時用
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.RecomposeCounter
import tw.harry.labs.kit.Variant

/** 只有 val、型別都是 stable：WatchTick 本身是 stable */
private data class WatchTick(val symbol: String, val price: Long)

// MARK: - Bug 版
private object L03Bug {
    data class Board(val name: String, val ticks: List<WatchTick>)       // List 是介面 → Board 是 unstable

    @Composable
    fun WatchlistCard(board: Board) {
        RecomposeCounter("WatchlistCard")
        Text("${board.name}：${board.ticks.size} 檔")
    }

    @Composable
    fun WatchlistScreen(updates: Int) {
        val ticks = remember { listOf(WatchTick("BTC", 10_000_000), WatchTick("ETH", 400_000), WatchTick("XRP", 80)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最後更新：第 $updates 次")                          // 讀 updates：每次更新，WatchlistScreen 重組
            // TODO ①：呼叫 WatchlistCard(Board("主要幣種", ticks))：內容一樣，但每次重組都是新的 Board 物件
        }
    }
}

// MARK: - 修正版
private object L03Fixed {
    // TODO ②-1：ticks 的型別從 List<WatchTick> 改成 ImmutableList<WatchTick>（ImmutableList 是 stable → Board 是 stable）
    data class Board(val name: String, val ticks: List<WatchTick>)

    @Composable
    fun WatchlistCard(board: Board) {
        RecomposeCounter("WatchlistCard")
        Text("${board.name}：${board.ticks.size} 檔")
    }

    @Composable
    fun WatchlistScreen(updates: Int) {
        // TODO ②-2：remember 裡的 listOf(…) 後面加 .toImmutableList()，建立 ImmutableList
        val ticks = remember { listOf(WatchTick("BTC", 10_000_000), WatchTick("ETH", 400_000), WatchTick("XRP", 80)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最後更新：第 $updates 次")
            WatchlistCard(Board("主要幣種", ticks))                  // 一樣是新的 Board 物件，但 equals 相等 → 跳過
        }
    }
}

// MARK: - 演練設定
@Composable
private fun L03Demo(variant: Variant, log: LabLog) {
    var round by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { round++ }) { Text("重新開始（第 ${round + 1} 輪）") }
        key(round) {
            var updates by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                repeat(20) {
                    delay(300)
                    updates++
                }
                log("更新完畢（共 20 次）")
            }
            when (variant) {
                Variant.BUG -> L03Bug.WatchlistScreen(updates)
                Variant.FIXED -> L03Fixed.WatchlistScreen(updates)
            }
        }
    }
}

val l03 = Lab(
    id = "L03",
    title = "資料沒變，卡片還是每次都重組",
    question = "第 3 題：為什麼資料明明沒變，這個 Composable 還是每次都重組？",
    goal = "Board 的內容一直沒變，卡片卻跟著上方的計數一起重組。先寫出 List 讓 Board 變成 unstable 的 Bug 版，再改用 ImmutableList。",
    steps = listOf(
        "打開 practice/kotlin/kotlin06-recomposition/L03_UnstableType.kt，寫 TODO ①（Bug 版）。",
        "App 選「練習」「Bug 版」，等約 6 秒（更新 20 次），看橘色數字：WatchlistCard 重組了幾次？",
        "寫 TODO ②-1、②-2（修正版），選「修正版」，等約 6 秒，看 WatchlistCard 重組了幾次。",
        "想看編譯器怎麼判斷：在 solution／practice 的 kotlin 資料夾的 build.gradle.kts 最後加 composeCompiler { reportsDestination = layout.buildDirectory.dir(\"compose_reports\") }，建置後打開 build/compose_reports 裡的 *-classes.txt，找 Board：Bug 版是 unstable class（unstable val ticks: List<WatchTick>），修正版是 stable class。",
    ),
    expectBug = "WatchlistCard 的次數一直增加，更新完畢後約 21 次。",
    expectFixed = "WatchlistCard 維持 1 次（被跳過）。",
    file = "kotlin06-recomposition/L03_UnstableType.kt",
    demo = { variant, log -> L03Demo(variant, log) },
)
