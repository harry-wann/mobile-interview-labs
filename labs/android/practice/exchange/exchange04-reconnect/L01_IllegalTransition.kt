// L01：App 在背景，連線卻自己又連回來了（狀態機的非法轉移）
// 對應複習站：Exchange04Reconnect 第 1 題「為什麼重連要寫成狀態機？狀態和事件有哪些？」
//
// 情境：連線狀態機（state machine）：Idle、Connecting、Connected、Waiting（等一段時間再重連）、Offline、Suspended（App 在背景，主動斷線）。
//        轉移是純函式 reduce(狀態, 事件) → (新狀態, 要做的事)，要做的事（effect）只用資料描述，由外面去執行。
//        演練依序送進這些事件：開始連線 → 連上了 → 連線意外斷了（closed，狀態機要排重試）→ 等待結束（retryTimerFired，重新連線）→ 又連上了
//        → App 進背景（狀態機要求關掉 socket、取消重試的計時器）
//        → socket 關閉的通知（closed，我們自己關的，通知會晚一點才到）→ 先前排的計時器響了（retryTimerFired）→ App 回前景。
//
// 你要寫：
//   ① Bug 版（L01Bug.reduce）：Closed 在任何狀態都當成連線失敗（排重試）；RetryTimerFired 在任何狀態都連線。
//   ② 修正版（L01Fixed.reduce）：Closed 只有在 Connecting／Connected 才算失敗；RetryTimerFired 只有在 Waiting 才連線；其他組合忽略。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange04-reconnect/L01_IllegalTransition.kt
package tw.harry.labs.practice.exchange04reconnect

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private sealed interface State {
    val attempt: Int get() = 0

    data object Idle : State
    data class Connecting(override val attempt: Int) : State
    data class Connected(override val attempt: Int, val sinceMs: Long) : State
    data class Waiting(override val attempt: Int, val retryAtMs: Long) : State
    data object Offline : State
    data object Suspended : State
}

private sealed interface Event {
    data object Start : Event
    data class Opened(val nowMs: Long) : Event
    data class Closed(val nowMs: Long) : Event
    data object RetryTimerFired : Event
    data object NetworkLost : Event
    data object NetworkAvailable : Event
    data object EnteredBackground : Event
    data object EnteredForeground : Event
}

private sealed interface Effect {
    /** 會讓連線或計時器開始動起來的事 */
    val startsWork: Boolean get() = false

    data object Connect : Effect { override val startsWork get() = true }
    data class ScheduleRetry(val delayMs: Long) : Effect { override val startsWork get() = true }
    data object CancelRetry : Effect
    data object CloseSocket : Effect
}

private typealias Step = Pair<State, List<Effect>>

private fun text(state: State): String = when (state) {
    State.Idle -> "idle"
    is State.Connecting -> "connecting(attempt: ${state.attempt})"
    is State.Connected -> "connected(attempt: ${state.attempt}, sinceMs: ${state.sinceMs})"
    is State.Waiting -> "waiting(attempt: ${state.attempt}, retryAtMs: ${state.retryAtMs})"
    State.Offline -> "offline"
    State.Suspended -> "suspended"
}

private fun text(effect: Effect): String = when (effect) {
    Effect.Connect -> "connect"
    is Effect.ScheduleRetry -> "scheduleRetry(delayMs: ${effect.delayMs})"
    Effect.CancelRetry -> "cancelRetry"
    Effect.CloseSocket -> "closeSocket"
}

/** 兩個版本共用的轉移（closed 與 retryTimerFired 以外的事件）。回傳 null 代表這個組合這裡沒有處理 */
private fun commonReduce(state: State, event: Event): Step? = when {
    state is State.Idle && event is Event.Start -> State.Connecting(0) to listOf(Effect.Connect)
    state is State.Connecting && event is Event.Opened -> State.Connected(state.attempt, event.nowMs) to emptyList()
    (state is State.Connecting || state is State.Connected || state is State.Waiting) && event is Event.NetworkLost ->
        State.Offline to listOf(Effect.CancelRetry, Effect.CloseSocket)
    state is State.Offline && event is Event.NetworkAvailable -> State.Connecting(0) to listOf(Effect.Connect)
    (state is State.Connecting || state is State.Connected || state is State.Waiting || state is State.Offline) && event is Event.EnteredBackground ->
        State.Suspended to listOf(Effect.CancelRetry, Effect.CloseSocket)           // 進背景：主動斷線並取消計時器
    state is State.Suspended && event is Event.EnteredForeground -> State.Connecting(0) to listOf(Effect.Connect)   // 回前景：當成一條全新的連線
    else -> null
}

/** 連線失敗後的下一個狀態：等待時間用正確的指數退避（亂數固定 0.5，結果每次一樣） */
private fun failed(state: State, nowMs: Long): Step {
    val next = state.attempt + 1
    val delay = E04Backoff.delayMs(attempt = next, random = 0.5)
    return State.Waiting(next, nowMs + delay) to listOf(Effect.ScheduleRetry(delay))
}

/** 演練流程（兩個版本共用）：依序送進事件，記下每一步的結果，數「App 在背景時，狀態機要求連線或排計時器」的次數 */
private fun scenario(log: LabLog, reduce: (State, Event) -> Step) {
    val events = listOf(
        "start" to Event.Start, "opened" to Event.Opened(100),
        "closed（連線意外斷了）" to Event.Closed(1000), "retryTimerFired（等待結束）" to Event.RetryTimerFired, "opened" to Event.Opened(1600),
        "enteredBackground" to Event.EnteredBackground,
        "closed（我們自己關 socket 的通知）" to Event.Closed(1800), "retryTimerFired（先前排的計時器）" to Event.RetryTimerFired,
        "enteredForeground" to Event.EnteredForeground,
    )
    var state: State = State.Idle
    var inBackground = false
    var violations = 0
    for ((name, event) in events) {
        if (event is Event.EnteredBackground) inBackground = true
        if (event is Event.EnteredForeground) inBackground = false
        val (next, effects) = reduce(state, event)
        val bad = inBackground && effects.any { it.startsWork }
        if (bad) violations++
        val todo = if (effects.isEmpty()) "無" else effects.joinToString("、") { text(it) }
        log("$name → ${text(next)}，要做的事：$todo${if (bad) "  ← App 在背景，不該連線或排計時器" else ""}")
        state = next
    }
    log("App 在背景時，狀態機要求連線或排計時器 $violations 次；最後狀態：${text(state)}")
}

// MARK: - Bug 版
private object L01Bug {
    fun reduce(state: State, event: Event): Step {
        commonReduce(state, event)?.let { return it }
        // TODO ①：除了 commonReduce 處理的事件，只剩下 Closed 和 RetryTimerFired（其他事件維持 state to emptyList()）：
        //   Closed：不管現在是什麼狀態，都當成「連線失敗」，回傳 failed(state, event.nowMs)
        //   RetryTimerFired：不管現在是什麼狀態，都回傳 State.Connecting(state.attempt) to listOf(Effect.Connect)
        return state to emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log, ::reduce)
}

// MARK: - 修正版
private object L01Fixed {
    fun reduce(state: State, event: Event): Step {
        commonReduce(state, event)?.let { return it }
        // TODO ②：用 when 配對 state 與 event，只處理這兩種合理的組合，其他組合都忽略（state to emptyList()）：
        //   (state 是 Connecting 或 Connected，event 是 Closed)：連線失敗，回傳 failed(state, event.nowMs)
        //   (state 是 Waiting，event 是 RetryTimerFired)：等待結束，回傳 State.Connecting(state.attempt) to listOf(Effect.Connect)
        return state to emptyList()
    }

    suspend fun run(log: LabLog) = scenario(log, ::reduce)
}

// MARK: - 演練設定
private fun violationCount(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("App 在背景時") }?.let { Regex("排計時器 (\\d+) 次").find(it)?.groupValues?.get(1)?.toInt() }

val l01 = Lab(
    id = "L01",
    title = "App 在背景，連線卻自己又連回來了",
    question = "第 1 題：為什麼重連要寫成狀態機？狀態和事件有哪些？",
    goal = "狀態機要明確列出每個狀態可以接受哪些事件。不合理的組合（例如在背景時收到 socket 關閉的通知、計時器響了）一律忽略，不能排計時器或連線。先寫出任何狀態都處理 closed 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/exchange/exchange04-reconnect/L01_IllegalTransition.kt，寫 TODO ①（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看進背景之後的 Closed、RetryTimerFired 讓狀態機做了什麼事、最後狀態是什麼。",
        "想一想：進背景時我們自己關了 socket，socket 的 closed 通知遲早會來。這時是「連線失敗」嗎？",
        "寫 TODO ②（修正版），選「修正版」執行，進背景之後不該有任何連線或排計時器的動作，回前景才連線。",
        "進階：狀態機只回傳「要做什麼」，真正連線、排計時器由外面執行，所以可以用一串事件寫單元測試，不用真的連網路。",
    ),
    expectBug = "進背景後 closed 讓狀態變成 waiting 並排了重試計時器、retryTimerFired 又要求連線，「App 在背景時，狀態機要求連線或排計時器 2 次」，回前景時狀態早就不是 suspended，沒有重新連線。",
    expectFixed = "進背景後的 closed、retryTimerFired 都被忽略，「App 在背景時，狀態機要求連線或排計時器 0 次；最後狀態：connecting(attempt: 0)」。",
    file = "exchange04-reconnect/L01_IllegalTransition.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val n = violationCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::reduce) 執行"
            lines.none { it.startsWith("start → connecting(attempt: 0)") } -> "Bug 版也要能開始連線：commonReduce 已經處理了 start，reduce 要先呼叫它"
            n != 2 -> "Bug 還沒重現：closed 在任何狀態都要排重試（Waiting + ScheduleRetry），retryTimerFired 在任何狀態都要連線（目前違規 $n 次）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val n = violationCount(lines)
        when {
            n == null -> "演練沒有跑完：要用 scenario(log, ::reduce) 執行"
            n != 0 -> "App 在背景時還是連線或排了計時器 $n 次：closed 只有 Connecting／Connected 才算失敗，retryTimerFired 只有 Waiting 才連線"
            "closed（連線意外斷了） → waiting(attempt: 1, retryAtMs: 1500)，要做的事：scheduleRetry(delayMs: 500)" !in lines -> "連線意外斷了（Connecting／Connected 時收到 Closed）要排重試：回傳 failed(state, event.nowMs)"
            "retryTimerFired（等待結束） → connecting(attempt: 1)，要做的事：connect" !in lines -> "等待中（Waiting）收到 RetryTimerFired，要重新連線：State.Connecting(attempt) to listOf(Effect.Connect)"
            "App 在背景時，狀態機要求連線或排計時器 0 次；最後狀態：connecting(attempt: 0)" !in lines -> "回前景時 Suspended 要變成 Connecting(0)（commonReduce 已經處理，reduce 要先呼叫它）"
            else -> null
        }
    },
)
