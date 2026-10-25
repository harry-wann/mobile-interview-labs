// L05：主執行緒卡住了，偵測程式卻在卡完之後才說話（卡住偵測要放在別的執行緒）
// 對應複習站：Advanced06Crash 第 4 題「ANR、watchdog、OOM
// 是什麼？為什麼它們常常沒有一般的 crash 報告？」（怎麼偵測）
//
// 情境：按鈕的點擊事件在主執行緒「同步等設定檔」（演練用 Thread.sleep 0.8
// 秒代替 runBlocking 等網路），主執行緒被佔住，畫面不能操作。
//       團隊想自己做一個卡住偵測：主執行緒超過 0.3 秒沒反應就回報（Android
// 在輸入事件 5 秒沒處理完時會判定 ANR）。
//       （JVM 單元測試沒有 Looper，演練用一條專用執行緒 fake-main 當主執行緒，
// 行為一樣：卡在一個工作上，後面的工作都要等。）
// Bug 版：偵測排在主執行緒自己的工作佇列上，每 0.1 秒檢查「距離上一次 tick
// 多久」。主執行緒被佔住的時候，這個偵測一次也不會跑；
//        卡完才發現隔了 0.8 秒。回報的當下主執行緒已經恢復，
// 抓不到它卡在哪個函式。
// 修正版：偵測放在自己的背景執行緒：每 0.1 秒請主執行緒回一聲「我還活著」，
// 超過 0.3 秒沒回就回報，
//        這時主執行緒還在卡住，馬上讀它的 stack trace，
// 就知道卡在哪個函式（ANR-WatchDog 這類函式庫的做法）。
package tw.harry.labs.solution.advanced06crash

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的主執行緒：一條專用執行緒，依序執行 post 進來的工作（兩個版本共用） */
private class L05FakeMain {
    @Volatile
    lateinit var thread: Thread
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fake-main").also {
            thread =
                it
        }
    }

    fun post(task: Runnable) {
        executor.execute(task)
    }

    fun repeatEvery(ms: Long, task: Runnable): ScheduledFuture<*> =
        executor.scheduleWithFixedDelay(task, ms, ms, TimeUnit.MILLISECONDS)

    fun shutdown() {
        executor.shutdownNow()
    }
}

/** 同步等設定檔：呼叫的人（主執行緒）會被佔住 0.8 秒（兩個版本共用） */
private fun waitForConfigSync() {
    Thread.sleep(800)
}

/** 記下偵測程式的回報（兩個版本共用） */
private class L05Probe(private val main: L05FakeMain) {
    class Report(
        val afterMs: Long,
        val stillBlocked: Boolean,
        val culprit: String,
    )

    @Volatile
    private var stalling = false

    @Volatile
    private var stallStart = System.nanoTime()

    @Volatile
    var report: Report? = null
        private set

    fun beginStall() {
        stallStart = System.nanoTime()
        stalling = true
    }

    fun endStall() {
        stalling = false
    }

    /**
     * 偵測程式發現卡住時呼叫。只記第一次；同時讀主執行緒現在的 stack，
     * 看它卡在哪個函式
     */
    @Synchronized
    fun reportHang() {
        if (report != null) return
        val culprit =
            main.thread.stackTrace.firstOrNull {
                it.methodName ==
                    "waitForConfigSync"
            }?.methodName
                ?: "（看不出來）"
        report =
            Report(
                (System.nanoTime() - stallStart) / 1_000_000,
                stalling,
                culprit,
            )
    }
}

/**
 * 演練流程（兩個版本共用）：啟動偵測程式 → 主執行緒被佔住 0.8 秒 →
 * 看偵測程式什麼時候回報。startDetector 回傳「停止偵測」的函式
 */
private suspend fun scenario(
    log: LabLog,
    startDetector: (L05FakeMain, L05Probe) -> () -> Unit,
) {
    val main = L05FakeMain()
    val probe = L05Probe(main)
    val stop = startDetector(main, probe)
    try {
        // 先讓偵測程式跑一會兒（主執行緒正常的時候不能誤報）
        delay(300)
        log("開始：主執行緒同步等設定檔，被佔住 800 ms")
        main.post {
            probe.beginStall()
            // 在主執行緒同步等：畫面完全沒有反應
            waitForConfigSync()
            probe.endStall()
        }
        delay(1_200)
    } finally {
        stop()
        main.shutdown()
    }
    val report = probe.report
    if (report == null) {
        log("沒有偵測到卡住")
    } else {
        log("偵測到卡住：卡住後 ${report.afterMs} ms 才回報")
        log(
            if (report.stillBlocked) "回報時主執行緒還在卡住：可以馬上抓現場" else "回報時主執行緒已經恢復：來不及知道卡在哪裡",
        )
        log("卡在：${report.culprit}")
    }
}

// MARK: - Bug 版
private object L05Bug {
    fun startDetector(main: L05FakeMain, probe: L05Probe): () -> Unit {
        // 偵測程式排在主執行緒的佇列上：它和被偵測的對象在同一條執行緒
        var lastTick = System.nanoTime()
        val future = main.repeatEvery(100) {
            if (System.nanoTime() - lastTick > 300_000_000L) probe.reportHang()
            lastTick = System.nanoTime()
        }
        return { future.cancel(false) }
    }

    suspend fun run(log: LabLog) = scenario(log, ::startDetector)
}

// MARK: - 修正版
private object L05Fixed {
    fun startDetector(main: L05FakeMain, probe: L05Probe): () -> Unit {
        val lastPong = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        // 偵測程式跑在自己的背景執行緒，主執行緒卡住也不會影響它
        val watchdog = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    // 請主執行緒回一聲
                    main.post { lastPong.set(System.nanoTime()) }
                    Thread.sleep(100)
                    // 超過 0.3 秒沒回：卡住了
                    if (System.nanoTime() - lastPong.get() >
                        300_000_000L
                    ) {
                        probe.reportHang()
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; name = "watchdog"; start() }
        return { watchdog.interrupt() }
    }

    suspend fun run(log: LabLog) = scenario(log, ::startDetector)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "卡住偵測要等卡完才說話",
    question = "第 4 題：ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？",
    goal = "偵測「主執行緒卡住」的程式，必須在卡住的當下就回報；它自己不能跑在被偵測的那條執行緒上。",
    steps = listOf(
        "選「Bug 版」執行，看「卡住後幾 ms 才回報」、回報時主執行緒是不是還在卡住，以及「卡在」哪個函式。",
        "想一想：主執行緒被 Thread.sleep 佔住的時候，排在主執行緒佇列上的定時工作還會跑嗎？",
        "選「修正版」執行：偵測放在背景執行緒，卡住約 200 到 400 ms 就回報，而且讀得到主執行緒卡在 waitForConfigSync。",
        "進階：真正的 App 用 Handler(Looper.getMainLooper()).post 請主執行緒回覆，用 Looper.getMainLooper().thread.stackTrace 讀 stack。Play Console 的 ANR 報告就是這樣的 stack。",
    ),
    expectBug = "約卡住後 800 ms（主執行緒恢復之後）才回報：「回報時主執行緒已經恢復」，「卡在：（看不出來）」。",
    expectFixed = "約卡住後 200 到 400 ms 就回報：「回報時主執行緒還在卡住」，「卡在：waitForConfigSync」。",
    file = "advanced06-crash/L05_HangWatchdog.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "沒有偵測到卡住" in lines -> "偵測程式沒有回報：每 0.1 秒檢查距離上次 tick 超過 0.3 秒就呼叫 probe.reportHang()"

            lines.any {
                "還在卡住" in it
            } -> "Bug 還沒重現：偵測要排在主執行緒的佇列上，卡住期間它跑不了，只能卡完才回報"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "沒有偵測到卡住" in lines -> "偵測程式沒有回報：背景執行緒每 0.1 秒請主執行緒回一聲，超過 0.3 秒沒回就呼叫 probe.reportHang()"
            lines.none { "還在卡住" in it } -> "回報太晚：偵測要放在背景執行緒，主執行緒還在卡住的時候就回報"
            "卡在：waitForConfigSync" !in lines -> "回報時應該讀得到主執行緒卡在 waitForConfigSync"
            else -> null
        }
    },
)
