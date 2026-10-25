// L05：只有 3 檔的價格變了，20 列卻全部重組（沒變的幣種要沿用同一個物件）
// 對應複習站：Kotlin08ComposePerformance 第 5 題「上百個幣種、每秒更新數十次，整個行情列表怎麼設計才不卡？」
//
// 情境：行情板有 20 列（TickRow）。資料層輸出 5 份新的清單，每次只有 3 檔的價格真的變了。畫面用的 TickUi 含一個 List（走勢），
//        所以它是 unstable，strong skipping 用 === 比較。這個演練不需要手機的畫面：用 LabKit 的 withHeadlessCompose 在電腦上真的跑 Compose 的重組。
// Bug 版：每次輸出都把原始資料重新轉成 TickUi（raws.map { it.toUi() }），20 檔都是新的物件。=== 比較永遠不相等，20 列全部重組。
// 修正版：記下每個幣種上一次的 TickUi。原始資料沒變的幣種沿用同一個物件，=== 相等，列被跳過；只有 3 列需要重組。
package tw.harry.labs.solution.kotlin08composeperformance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.atomic.AtomicInteger
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant
import tw.harry.labs.kit.withHeadlessCompose

private data class L05Raw(val symbol: String, val price: Long)

/** 畫面用的資料。trend 是 List，所以 TickUi 是 unstable */
private data class L05TickUi(val symbol: String, val priceText: String, val trend: List<Long>)

private fun L05Raw.toUi() = L05TickUi(symbol, price.toString(), listOf(price))

/** 一列（兩個版本共用）：參數沒變就會被跳過；真的執行時，記下這一列現在顯示什麼 */
@Composable
private fun L05TickRow(tick: L05TickUi, onCompose: (symbol: String, text: String) -> Unit) {
    SideEffect { onCompose(tick.symbol, tick.priceText) }
}

@Composable
private fun L05Board(ticks: List<L05TickUi>, onCompose: (symbol: String, text: String) -> Unit) {
    ticks.forEach { tick -> key(tick.symbol) { L05TickRow(tick, onCompose) } }
}

/**
 * 演練流程（兩個版本共用）：20 檔，資料層輸出 5 份新清單；第 r 份有 3 檔的價格加 1（其餘內容一樣，但每次都是新的原始資料物件）。
 * [build] 決定怎麼把原始資料轉成畫面用的清單。
 */
private suspend fun scenario(log: LabLog, build: (List<L05Raw>) -> List<L05TickUi>) {
    var raws = List(20) { L05Raw("COIN${it + 1}_JPY", 100_000L + it) }
    val rowRuns = AtomicInteger()
    val shown = HashMap<String, String>()
    withHeadlessCompose {
        val board = mutableStateOf(build(raws))
        setContent {
            L05Board(board.value) { symbol, text ->
                rowRuns.incrementAndGet()
                shown[symbol] = text
            }
        }
        settle()
        for (round in 0 until 5) {
            val changed = (0 until 3).map { (round * 3 + it) % 20 }.toSet()
            raws = raws.mapIndexed { i, raw -> raw.copy(price = if (i in changed) raw.price + 1 else raw.price) }
            board.value = build(raws)
            settle()
        }
    }
    val upToDate = raws.all { shown[it.symbol] == it.price.toString() }
    log("20 列、更新 5 次（每次 3 檔價格有變），列的重組共 ${rowRuns.get()} 次")
    log("畫面上每一列都是最新價：${if (upToDate) "是" else "否"}")
}

// MARK: - Bug 版
private object L05Bug {
    fun build(raws: List<L05Raw>): List<L05TickUi> = raws.map { it.toUi() }    // 每次 20 檔都是新物件

    suspend fun run(log: LabLog) = scenario(log, ::build)
}

// MARK: - 修正版
private object L05Fixed {
    class BoardBuilder {
        private val cache = HashMap<String, Pair<L05Raw, L05TickUi>>()      // 每個幣種：上一次的原始資料與 TickUi

        fun build(raws: List<L05Raw>): List<L05TickUi> = raws.map { raw ->
            val cached = cache[raw.symbol]
            if (cached != null && cached.first == raw) {
                cached.second                                               // 沒變：沿用同一個物件
            } else {
                raw.toUi().also { cache[raw.symbol] = raw to it }           // 有變：產生新的 TickUi
            }
        }
    }

    suspend fun run(log: LabLog) = BoardBuilder().let { builder -> scenario(log, builder::build) }
}

// MARK: - 演練設定
private fun rowRuns(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("20 列、更新 5 次（每次 3 檔價格有變），列的重組共 ") }
        ?.removePrefix("20 列、更新 5 次（每次 3 檔價格有變），列的重組共 ")?.removeSuffix(" 次")?.toIntOrNull()

private fun upToDate(lines: List<String>): Boolean = lines.any { it.endsWith("每一列都是最新價：是") }

val l05 = Lab(
    id = "L05",
    title = "只有 3 檔變價，20 列全部重組",
    question = "第 5 題：上百個幣種、每秒更新數十次，整個行情列表怎麼設計才不卡？",
    goal = "20 列行情，每次只有 3 檔的價格變了。看每次都產生新的 TickUi 物件時重組了幾列，再改成沒變的幣種沿用同一個物件。",
    steps = listOf(
        "選「Bug 版」執行，看「列的重組共 N 次」的 N。",
        "選「修正版」執行，確認次數降到 35 次（第一次組合 20 列，加上 5 次更新各 3 列）。",
        "想一想：如果 TickUi 的欄位都是 stable 的型別（例如 ImmutableList），還需要沿用同一個物件嗎？（不需要，stable 型別用 equals 比較，內容一樣就會被跳過）",
    ),
    expectBug = "「列的重組共 120 次」：第一次組合 20 列，加上 5 次更新每次 20 列全部重組。",
    expectFixed = "「列的重組共 35 次」：第一次組合 20 列，加上 5 次更新每次只有 3 列；「畫面上每一列都是最新價：是」。",
    file = "kotlin08-compose-performance/L05_ReuseRowModel.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = rowRuns(lines) ?: 0
        when {
            n == 0 -> "畫面沒有組合任何一列：build 要把 raws 轉成 TickUi 清單（raws.map { it.toUi() }）"
            n < 100 -> "列的重組只有 $n 次：Bug 版每次都要產生新的 TickUi（raws.map { it.toUi() }），不要沿用舊物件"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = rowRuns(lines) ?: 0
        when {
            n == 0 -> "畫面沒有組合任何一列：build 要把 raws 轉成 TickUi 清單"
            !upToDate(lines) -> "有的列沒有更新到最新價：原始資料有變的幣種，要產生新的 TickUi"
            n > 40 -> "列的重組有 $n 次，應該是 35 次：原始資料沒變的幣種，沿用上一次的同一個 TickUi 物件"
            else -> null
        }
    },
)
