// L06：旋轉螢幕時行情連線被重連（stateIn 的 WhileSubscribed）
// 對應複習站：Kotlin05Flow 第 5 題「stateIn(WhileSubscribed(5000)) 是什麼？為什麼是 5 秒？」
//
// 情境：ViewModel 用 stateIn 把行情（cold flow，上游是一條 WebSocket）轉成 hot flow 給畫面。
//        演練模擬旋轉螢幕：畫面（collector）消失 200 ms 後，新的畫面又開始收集。
//
// 你要寫：
//   ① Bug 版（L06Bug）：repo.tickers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), 0)。旋轉的瞬間上游立刻停掉，新畫面一出現又重啟。
//   ② 修正版（L06Fixed）：started 改用 WhileSubscribed(5_000)：沒有人收集之後再等 5 秒才停，旋轉不會打斷上游。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin05-flow/L06_WhileSubscribed.kt
package tw.harry.labs.practice.kotlin05flow

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 行情來源：開始收集就「開一條 WebSocket」，每 50 ms 一個價格；停止收集就「關閉」 */
private class LiveTickerRepo(private val log: LabLog) {
    val starts = AtomicInteger(0)
    val running = AtomicBoolean(false)

    fun tickers(): Flow<Int> = flow {
        log("上游啟動（第 ${starts.incrementAndGet()} 次）")
        running.set(true)
        try {
            var price = 0
            while (true) {
                emit(++price)
                delay(50)
            }
        } finally {
            running.set(false)
            log("上游停止")
        }
    }
}

/** 演練流程（兩個版本共用）：畫面收集 300 ms → 旋轉（消失 200 ms）→ 新畫面收集 300 ms → 離開 2 秒 */
private suspend fun scenario(log: LabLog, repo: LiveTickerRepo, prices: StateFlow<Int>) {
    val screenBeforeRotate = CoroutineScope(Dispatchers.Default).launch { prices.collect { } }
    delay(300)
    screenBeforeRotate.cancelAndJoin()
    log("旋轉：畫面消失")
    delay(200)
    val screenAfterRotate = CoroutineScope(Dispatchers.Default).launch { prices.collect { } }
    log("旋轉：新畫面出現")
    delay(300)
    screenAfterRotate.cancelAndJoin()
    log("使用者離開畫面")
    delay(2_000)
    log("離開 2 秒後，上游還在跑嗎：${repo.running.get()}")
    log("上游共啟動 ${repo.starts.get()} 次")
}

// MARK: - Bug 版
private object L06Bug {
    suspend fun run(log: LabLog) {
        val repo = LiveTickerRepo(log)
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // TODO ①：val prices = repo.tickers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), 0)（沒人收集就立刻停）
            val prices = MutableStateFlow(0)
            scenario(log, repo, prices)
        } finally {
            viewModelScope.cancel()
        }
    }
}

// MARK: - 修正版
private object L06Fixed {
    suspend fun run(log: LabLog) {
        val repo = LiveTickerRepo(log)
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // TODO ②：val prices = repo.tickers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)（沒人收集後再等 5 秒才停）
            val prices = MutableStateFlow(0)
            scenario(log, repo, prices)
        } finally {
            viewModelScope.cancel()
        }
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "旋轉螢幕時，行情連線被重連",
    question = "第 5 題：stateIn(WhileSubscribed(5000)) 是什麼？為什麼是 5 秒？",
    goal = "旋轉螢幕時畫面短暫消失。先寫出讓上游重啟的 WhileSubscribed(0)，再用 5 秒的等待時間撐過去。",
    steps = listOf(
        "打開 practice/kotlin/kotlin05-flow/L06_WhileSubscribed.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行（約 3 秒），看「上游啟動」出現幾次、「離開 2 秒後，上游還在跑嗎」。",
        "寫 TODO ②（修正版），選「修正版」執行，確認旋轉時上游沒有重啟，離開 2 秒後上游還在（5 秒還沒到）。",
        "想一想：如果改用 SharingStarted.Eagerly 或 Lazily 會怎樣？（上游永遠不停，使用者離開畫面後還在耗電耗流量）",
    ),
    expectBug = "「上游啟動（第 2 次）」：旋轉時重啟；「離開 2 秒後，上游還在跑嗎：false」；「上游共啟動 2 次」。",
    expectFixed = "只有「上游啟動（第 1 次）」；「離開 2 秒後，上游還在跑嗎：true」（還在 5 秒的等待時間內）；「上游共啟動 1 次」。",
    file = "kotlin05-flow/L06_WhileSubscribed.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "上游共啟動 2 次" !in lines -> "Bug 版旋轉時上游要重啟（共啟動 2 次）：stateIn 用 SharingStarted.WhileSubscribed(0)"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "上游共啟動 1 次" !in lines -> "旋轉時上游不應該重啟（共啟動 1 次）：stateIn 用 SharingStarted.WhileSubscribed(5_000)"
            "離開 2 秒後，上游還在跑嗎：true" !in lines -> "離開 2 秒（還沒到 5 秒）上游應該還在跑：WhileSubscribed(5_000)"
            else -> null
        }
    },
)
