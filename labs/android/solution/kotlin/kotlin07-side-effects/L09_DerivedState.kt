// L09：列表捲動時，「回到頂端」按鈕每動一格就重組一次（derivedStateOf）
// 對應複習站：Kotlin07SideEffects 第 6 題「produceState、snapshotFlow、derivedStateOf 各自在轉換什麼？」
//
// 情境：行情列表往下捲，第一個看得到的項目的位置（firstVisibleItemIndex）從 0 一路增加到 20。
//        「回到頂端」按鈕只關心「位置是不是大於 0」。演練用 mutableIntStateOf 代替 LazyListState 的位置。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：val showTop = index.value > 0。在 Composable 裡直接讀位置，位置每變一格，這個 Composable 就重組一次。
// 修正版：val showTop by remember { derivedStateOf { index.value > 0 } }。derivedStateOf 的結果只在
//        false 變 true（或相反）時才通知重組，位置從 1 變 2、3、4… 時，結果一樣，Composable 不用重組。
package tw.harry.labs.solution.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import tw.harry.labs.kit.HeadlessText
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 演練流程（兩個版本共用）：位置從 0 每 15 ms 加 1，加到 20，看「回到頂端」按鈕的 Composable 共執行了幾次 */
private suspend fun scenario(log: LabLog, button: @Composable (index: State<Int>, onCompose: () -> Unit) -> Unit) {
    var compositions = 0
    withHeadlessCompose {
        val index = mutableIntStateOf(0)
        setContent { button(index) { compositions++ } }
        settle()
        repeat(20) {
            delay(15)
            index.intValue++
        }
        settle()
        log("捲動 20 格，「回到頂端」按鈕共執行了 $compositions 次")
    }
}

// MARK: - Bug 版
private object L09Bug {
    @Composable
    fun BackToTopButton(index: State<Int>, onCompose: () -> Unit) {
        val showTop = index.value > 0                       // 直接讀位置：位置每變一格就重組
        SideEffect { onCompose() }                          // 演練用：每次執行完就記一次
        HeadlessText(if (showTop) "回到頂端" else "")
    }

    suspend fun run(log: LabLog) = scenario(log) { index, onCompose -> BackToTopButton(index, onCompose) }
}

// MARK: - 修正版
private object L09Fixed {
    @Composable
    fun BackToTopButton(index: State<Int>, onCompose: () -> Unit) {
        val showTop by remember { derivedStateOf { index.value > 0 } }   // 只在 true／false 換邊時才通知重組
        SideEffect { onCompose() }
        HeadlessText(if (showTop) "回到頂端" else "")
    }

    suspend fun run(log: LabLog) = scenario(log) { index, onCompose -> BackToTopButton(index, onCompose) }
}

// MARK: - 演練設定
private fun runs(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("捲動 20 格，「回到頂端」按鈕共執行了 ") }
        ?.removePrefix("捲動 20 格，「回到頂端」按鈕共執行了 ")?.removeSuffix(" 次")?.toIntOrNull()

val l09 = Lab(
    id = "L09",
    title = "捲動每動一格，按鈕就重組一次",
    question = "第 6 題：produceState、snapshotFlow、derivedStateOf 各自在轉換什麼？",
    goal = "「回到頂端」按鈕只關心位置是不是大於 0。看直接讀位置時它重組了幾次，再用 derivedStateOf 讓它只在結果換邊時才重組。",
    steps = listOf(
        "選「Bug 版」執行，看「按鈕共執行了 N 次」的 N。",
        "選「修正版」執行，確認次數降到 2 次（第一次組合，加上位置從 0 變成 1 的那一次）。",
        "想一想：什麼情況下 derivedStateOf 沒有幫助？（輸出和輸入一起變，例如 price * qty；這時直接計算就好）",
    ),
    expectBug = "「按鈕共執行了 21 次」：第一次組合，加上位置變動 20 次各重組 1 次。",
    expectFixed = "「按鈕共執行了 2 次」：第一次組合，加上 false 變 true 的那一次。",
    file = "kotlin07-side-effects/L09_DerivedState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L09Bug.run(log)
            Variant.FIXED -> L09Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = runs(lines) ?: 0
        when {
            n == 0 -> "按鈕沒有執行：BackToTopButton 要讀 index.value，並呼叫 SideEffect { onCompose() }"
            n <= 5 -> "只執行了 $n 次，位置變動沒有造成重組：Bug 版要在函式裡直接寫 val showTop = index.value > 0，不要用 derivedStateOf"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = runs(lines) ?: 0
        when {
            n == 0 -> "按鈕沒有執行：BackToTopButton 要呼叫 SideEffect { onCompose() }"
            n == 1 -> "按鈕沒有跟著捲動出現：showTop 要用 derivedStateOf { index.value > 0 } 判斷，位置離開 0 時要重組一次"
            n > 5 -> "執行了 $n 次，位置每變一格都重組：用 val showTop by remember { derivedStateOf { index.value > 0 } }"
            else -> null
        }
    },
)
