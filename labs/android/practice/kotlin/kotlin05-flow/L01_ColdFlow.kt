// L01：兩個畫面元件各收一次行情，開了兩條 WebSocket（cold flow 與 shareIn／stateIn）
// 對應複習站：Kotlin05Flow 第 1 題「Flow 是什麼？cold 和 hot 差在哪？」
//
// 情境：行情的上游是「開一條 WebSocket 訂閱價格」（用 flow { } 寫的 cold flow，已經寫好）。
//        行情頁上有兩個元件（價格看板、迷你走勢圖）同時收集同一份價格。
//
// 你要寫：
//   ① Bug 版（L01Bug）：ViewModel 的 prices 直接回傳 cold flow（repo.tickers()）。兩個元件各開一條 WebSocket。
//   ② 修正版（L01Fixed）：用 stateIn(scope, WhileSubscribed(5000), 初始值) 轉成 hot flow，兩個元件共用同一條上游。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin05-flow/L01_ColdFlow.kt
package tw.harry.labs.practice.kotlin05flow

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 行情來源：每次有人開始收集，就「開一條 WebSocket」，每 50 ms 送出一個價格（1 到 5） */
private class TickerRepo(private val log: LabLog) {
    val opened = AtomicInteger(0)

    fun tickers(): Flow<Int> = flow {
        log("開啟 WebSocket（第 ${opened.incrementAndGet()} 條）")
        for (price in 1..5) {
            emit(price)
            delay(50)
        }
    }
}

/** 演練流程（兩個版本共用）：兩個元件同時收集，各自等到價格 5 */
private suspend fun scenario(log: LabLog, repo: TickerRepo, prices: Flow<Int>) {
    coroutineScope {
        listOf("價格看板", "迷你走勢圖").map { name ->
            async(Dispatchers.Default) {
                prices.first { it >= 5 }
                log("$name 收到價格 5")
            }
        }.awaitAll()
    }
    log("共開了 ${repo.opened.get()} 條 WebSocket")
}

// MARK: - Bug 版
private object L01Bug {
    class TickerViewModel(repo: TickerRepo) {
        // TODO ①：prices 直接回傳 repo.tickers()（cold flow：每個 collector 都重新開一條）
        val prices: Flow<Int> = flowOf(5)
    }

    suspend fun run(log: LabLog) {
        val repo = TickerRepo(log)
        scenario(log, repo, TickerViewModel(repo).prices)
    }
}

// MARK: - 修正版
private object L01Fixed {
    class TickerViewModel(repo: TickerRepo, scope: CoroutineScope) {
        // TODO ②：repo.tickers() 後面接 .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)，轉成 hot flow 共用上游
        val prices: Flow<Int> = flowOf(5)
    }

    suspend fun run(log: LabLog) {
        val repo = TickerRepo(log)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)   // 代表 viewModelScope
        try {
            scenario(log, repo, TickerViewModel(repo, scope).prices)
        } finally {
            scope.cancel()
        }
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "兩個元件收行情，開了兩條 WebSocket",
    question = "第 1 題：Flow 是什麼？cold 和 hot 差在哪？",
    goal = "兩個畫面元件同時收集同一份行情。先寫出會開兩條連線的 Bug 版，再用 stateIn 讓大家共用同一條。",
    steps = listOf(
        "打開 practice/kotlin/kotlin05-flow/L01_ColdFlow.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，數「開啟 WebSocket」出現幾次。",
        "寫 TODO ②（修正版），選「修正版」執行，確認只開一條，兩個元件都收到價格 5。",
        "想一想：把 stateIn 換成 shareIn（replay = 1）可以嗎？和 stateIn 差在哪？（沒有初始值、不會去重）",
    ),
    expectBug = "「開啟 WebSocket」出現兩次，最後一行是「共開了 2 條 WebSocket」。",
    expectFixed = "「開啟 WebSocket」只出現一次，兩個元件都收到價格 5，最後一行是「共開了 1 條 WebSocket」。",
    file = "kotlin05-flow/L01_ColdFlow.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "價格看板 收到價格 5" !in lines || "迷你走勢圖 收到價格 5" !in lines -> "兩個元件都要收到價格 5：ViewModel 的 prices 要回傳 repo.tickers()"
            "共開了 2 條 WebSocket" !in lines -> "Bug 版兩個元件要各開一條（共 2 條）：prices 直接用 cold flow，不要共用"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "價格看板 收到價格 5" !in lines || "迷你走勢圖 收到價格 5" !in lines -> "兩個元件都要收到價格 5：prices 要轉成 StateFlow 並共用上游"
            "共開了 1 條 WebSocket" !in lines -> "只應該開 1 條：用 stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0) 轉成 hot flow"
            else -> null
        }
    },
)
