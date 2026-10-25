// L04：行情列表只更新了一次就不動了；離開畫面之後計時器還在跑（Choreographer
// 的 postFrameCallback 只會觸發一次）
// 對應複習站：Advanced05HighFrequency 第 5 題「怎麼配合螢幕的更新頻率輸出？」
//
// 情境：背景把最新的值依交易對寫進緩衝區（TickBuffer），畫面端用 Choreographer
// 在每一格畫面取走並清空，再更新列表。
//        Choreographer.postFrameCallback 只會在「下一格畫面」呼叫一次；
// 要每一格都更新，要在 doFrame 裡再註冊一次；
//        離開畫面時要 removeFrameCallback，不然已經排好的那一次還是會跑。
//        JVM 單元測試沒有真的 Choreographer，演練用 FakeChoreographer
// 重現同樣的規則（frame() 代表「螢幕又畫了一格」）；
//        FrameTicker 的寫法和複習站的範例一樣，在真的 App 裡把
// FakeChoreographer 換成 Choreographer.getInstance() 即可。
// Bug 版：doFrame 取走緩衝區、更新畫面，但沒有再 postFrameCallback：
// 只更新一次，之後的行情都不會顯示。
// 修正版：doFrame 最後再 postFrameCallback(this)；stop() 呼叫
// removeFrameCallback(this)，離開畫面之後不再更新。
package tw.harry.labs.solution.advanced05highfrequency

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private class Tick(val symbol: String, val price: Int)

/** 和 Choreographer.FrameCallback 一樣的介面（兩個版本共用） */
private fun interface FrameCallback {
    fun doFrame(frameTimeNanos: Long)
}

/**
 * 縮小版的 Choreographer：postFrameCallback 只會在「下一格」呼叫一次，
 * 呼叫完就清掉（兩個版本共用）
 */
private class FakeChoreographer {
    private val callbacks = ArrayList<FrameCallback>()
    private var now = 0L

    fun postFrameCallback(callback: FrameCallback) {
        callbacks += callback
    }

    fun removeFrameCallback(callback: FrameCallback) {
        callbacks -= callback
    }

    /** 螢幕又畫了一格：把排好的 callback 各呼叫一次 */
    fun frame() {
        now += 16_666_667
        val due = callbacks.toList()
        callbacks.clear()
        due.forEach { it.doFrame(now) }
    }
}

/**
 * 緩衝區：背景把最新的值依交易對寫進來，畫面每一格取走並清空（兩個版本共用）
 */
private class TickBuffer {
    private val lock = Any()
    private var pending = HashMap<String, Tick>()

    fun put(tick: Tick) {
        synchronized(lock) { pending[tick.symbol] = tick }
    }

    fun drain(): List<Tick> = synchronized(lock) {
        val batch = pending.values.toList()
        pending = HashMap()
        batch
    }
}

/**
 * 演練流程（兩個版本共用）：10 格畫面，每格之前背景寫進一筆新價；
 * 然後離開畫面（stop），再過 3 格
 */
private fun scenario(
    log: LabLog,
    makeTicker: (
        FakeChoreographer,
        TickBuffer,
        onBatch: (List<Tick>) -> Unit,
    ) -> Pair<() -> Unit, () -> Unit>,
) {
    val choreographer = FakeChoreographer()
    val buffer = TickBuffer()
    var updates = 0
    var lastPrice = 0
    val (start, stop) = makeTicker(choreographer, buffer) { batch ->
        updates++
        lastPrice = batch.maxOf { it.price }
    }
    start()
    for (frame in 1..10) {
        // 背景每一格之前寫進一筆新價
        buffer.put(Tick("BTC", frame))
        choreographer.frame()
    }
    log("10 格畫面，列表更新了 $updates 次，最後顯示的價格 $lastPrice")
    stop() // 離開畫面
    val before = updates
    for (frame in 11..13) {
        buffer.put(Tick("BTC", frame))
        choreographer.frame()
    }
    log("離開畫面之後再過 3 格，又更新了 ${updates - before} 次")
}

// MARK: - Bug 版
private object L04Bug {
    class FrameTicker(
        private val choreographer: FakeChoreographer,
        private val buffer: TickBuffer,
        private val onBatch: (List<Tick>) -> Unit,
    ) : FrameCallback {
        fun start() = choreographer.postFrameCallback(this)

        fun stop() {
            // 沒有 removeFrameCallback
        }

        override fun doFrame(frameTimeNanos: Long) {
            val batch = buffer.drain()
            if (batch.isNotEmpty()) onBatch(batch)
            // 沒有再 postFrameCallback：postFrameCallback 只會觸發一次，
            // 之後就不會再被呼叫了
        }
    }

    fun run(log: LabLog) = scenario(log) { choreographer, buffer, onBatch ->
        val ticker = FrameTicker(choreographer, buffer, onBatch)
        ticker::start to ticker::stop
    }
}

// MARK: - 修正版
private object L04Fixed {
    class FrameTicker(
        private val choreographer: FakeChoreographer,
        private val buffer: TickBuffer,
        private val onBatch: (List<Tick>) -> Unit,
    ) : FrameCallback {
        fun start() = choreographer.postFrameCallback(this)

        // 離開畫面：把已經排好的那一次也取消
        fun stop() = choreographer.removeFrameCallback(this)

        override fun doFrame(frameTimeNanos: Long) {
            val batch = buffer.drain()
            if (batch.isNotEmpty()) onBatch(batch)
            // 要持續更新，就在回呼裡再註冊下一次
            choreographer.postFrameCallback(this)
        }
    }

    fun run(log: LabLog) = scenario(log) { choreographer, buffer, onBatch ->
        val ticker = FrameTicker(choreographer, buffer, onBatch)
        ticker::start to ticker::stop
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "行情列表只更新了一次就不動了，離開畫面後計時器還在跑",
    question = "第 5 題：怎麼配合螢幕的更新頻率輸出？",
    goal = "用 Choreographer 每一格畫面取走緩衝區：doFrame 裡要再註冊下一次，離開畫面要 removeFrameCallback。",
    steps = listOf(
        "選「Bug 版」執行，看第一行 log：10 格畫面，列表只更新了 1 次，最後顯示的價格是 1（第一格的），後面的行情都沒顯示。",
        "選「修正版」執行：更新 10 次，最後顯示的價格 10；離開畫面之後再過 3 格，更新 0 次。",
        "想一想：把 stop() 裡的 removeFrameCallback 拿掉會怎樣？（doFrame 最後排好的那一次還是會跑，離開畫面之後多更新 1 次，而且 FrameTicker 與它抓著的畫面都被 Choreographer 抓著，見這個單元的第 5 題）",
        "進階：Compose 裡可以在 LaunchedEffect 的迴圈中用 `withFrameNanos` 達到同樣的效果；iOS 的 CADisplayLink 版見 iOS 的演練 4（`.common` mode 與 weak target）。",
    ),
    expectBug = "10 格畫面，列表更新了 1 次，最後顯示的價格 1；離開畫面之後再過 3 格，又更新了 0 次。",
    expectFixed = "10 格畫面，列表更新了 10 次，最後顯示的價格 10；離開畫面之後再過 3 格，又更新了 0 次。",
    file = "advanced05-high-frequency/L04_FrameTicker.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val updates = a05Number(lines, "10 格畫面，列表更新了 ")?.toInt()
        when {
            updates == null -> "演練沒有跑完"
            updates == 0 -> "第一格也沒有更新：start() 要 postFrameCallback(this)，doFrame 要取走緩衝區並呼叫 onBatch"
            updates > 1 -> "更新了 $updates 次：Bug 版 doFrame 不再註冊下一次，應該只更新 1 次"
            else -> null
        }
    },
    checkFixed = { lines ->
        val updates = a05Number(lines, "10 格畫面，列表更新了 ")?.toInt()
        val after = a05Number(lines, "離開畫面之後再過 3 格，又更新了 ")?.toInt()
        when {
            updates == null || after == null -> "演練沒有跑完"
            updates < 10 -> "10 格只更新了 $updates 次：doFrame 最後要再 postFrameCallback(this)"
            after > 0 -> "離開畫面之後還更新了 $after 次：stop() 要 removeFrameCallback(this)"
            !lines.any { it.endsWith("最後顯示的價格 10") } -> "最後顯示的價格應該是 10（第 10 格的）"
            else -> null
        }
    },
)
