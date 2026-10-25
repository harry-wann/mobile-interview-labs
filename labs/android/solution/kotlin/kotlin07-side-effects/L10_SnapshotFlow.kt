// L10：捲動位置回報只通知了第一次（在 LaunchedEffect 裡直接讀 State，要用 snapshotFlow）
// 對應複習站：Kotlin07SideEffects 第 6 題「produceState、snapshotFlow、derivedStateOf 各自在轉換什麼？」
//
// 情境：列表每捲動一格，要把第一個看得到的項目位置回報給 ViewModel（例如載入下一頁、送分析事件）。位置從 0 每 30 ms 加 1，加到 5。
//        演練用 mutableIntStateOf 代替 LazyListState 的位置。
//        這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的組合、重組與 effect。
// Bug 版：LaunchedEffect(Unit) { report(index.value) }。effect 的區塊只執行一次，讀 State 也只讀一次，
//        之後位置怎麼變，都沒有人通知它。
// 修正版：snapshotFlow { index.value }。它在大括號裡讀 State，State 變了就發射新值（相同的值自動去重），
//        再用 collect 一筆一筆回報。
package tw.harry.labs.solution.kotlin07sideeffects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

/** 演練流程（兩個版本共用）：位置從 0 每 30 ms 加 1，加到 5，看回報了哪些位置 */
private suspend fun scenario(log: LabLog, reporter: @Composable (index: State<Int>, report: (Int) -> Unit) -> Unit) {
    val reported = mutableListOf<Int>()
    withHeadlessCompose {
        val index = mutableIntStateOf(0)
        setContent { reporter(index) { reported += it } }
        settle()
        repeat(5) {
            delay(30)
            index.intValue++
        }
        delay(60)
    }
    log("回報過的位置：$reported")
}

// MARK: - Bug 版
private object L10Bug {
    @Composable
    fun ScrollReporter(index: State<Int>, report: (Int) -> Unit) {
        LaunchedEffect(Unit) {
            report(index.value)                             // 區塊只執行一次，這個讀取也只發生一次
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { index, report -> ScrollReporter(index, report) }
}

// MARK: - 修正版
private object L10Fixed {
    @Composable
    fun ScrollReporter(index: State<Int>, report: (Int) -> Unit) {
        LaunchedEffect(Unit) {
            snapshotFlow { index.value }                    // State 變了就發射新值
                .distinctUntilChanged()
                .collect { report(it) }
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { index, report -> ScrollReporter(index, report) }
}

// MARK: - 演練設定
private fun reported(lines: List<String>): List<Int>? =
    lines.firstOrNull { it.startsWith("回報過的位置：[") }
        ?.removePrefix("回報過的位置：[")?.removeSuffix("]")
        ?.split(", ")?.mapNotNull { it.toIntOrNull() }

val l10 = Lab(
    id = "L10",
    title = "捲動位置只回報了第一次",
    question = "第 6 題：produceState、snapshotFlow、derivedStateOf 各自在轉換什麼？",
    goal = "列表每捲動一格就要回報位置。看在 LaunchedEffect 裡直接讀 State 為什麼只通知一次，再改用 snapshotFlow 把 State 的變化轉成 Flow。",
    steps = listOf(
        "選「Bug 版」執行，看「回報過的位置」有哪些。",
        "選「修正版」執行，確認 0 到 5 每個位置都回報了一次。",
        "想一想：snapshotFlow 和 produceState 的方向差在哪？（snapshotFlow 是 Compose 的 State 變成 Flow；produceState 是外面的非同步資料變成 State）",
    ),
    expectBug = "「回報過的位置：[0]」：只通知了第一次，之後的捲動沒有人回報。",
    expectFixed = "「回報過的位置：[0, 1, 2, 3, 4, 5]」。",
    file = "kotlin07-side-effects/L10_SnapshotFlow.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L10Bug.run(log)
            Variant.FIXED -> L10Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val list = reported(lines)
        when {
            list.isNullOrEmpty() -> "一個位置都沒回報：LaunchedEffect(Unit) 裡要呼叫 report(index.value)"
            list.size > 1 -> "回報了 ${list.size} 個位置：Bug 版只在 LaunchedEffect(Unit) 裡讀一次 index.value，不要用 snapshotFlow"
            else -> null
        }
    },
    checkFixed = { lines ->
        val list = reported(lines)
        when {
            list.isNullOrEmpty() -> "一個位置都沒回報：LaunchedEffect(Unit) 裡用 snapshotFlow { index.value }.distinctUntilChanged().collect { report(it) }"
            list != listOf(0, 1, 2, 3, 4, 5) -> "回報過的位置是 $list，應該是 [0, 1, 2, 3, 4, 5]：位置每變一次都要回報，用 snapshotFlow 收集 State 的變化"
            else -> null
        }
    },
)
