// L04：存快照的時候，畫面讀一下價格就被卡住（鎖裡面做 I/O，
// 主執行緒等背景執行緒持有的鎖）
// 對應複習站：Advanced04Races 第 5 題「priority inversion 是什麼？」
//
// 情境：價格表同時被兩個地方使用：背景執行緒（優先權比較低，能分到的 CPU
// 時間比較少）每隔一陣子把整份價格寫到檔案（存快照，約 400 毫秒的 I/O），
//        主執行緒（演練用 fake-main 這條執行緒代表）要讀一筆最新價來顯示。
//
// 你要寫：
//   ① Bug 版 PriceTable：saveSnapshot 在 synchronized(lock) 裡面先 started.
// countDown()，再 Thread.sleep(400)（持鎖的期間寫檔案）；
//      latest() 用 synchronized(lock) 回傳 prices["BTC/JPY"] ?: 0。
// 主執行緒得等整個寫檔案做完。
//   ② 修正版 PriceTable：saveSnapshot 持鎖只複製一份資料（synchronized(lock) {
// prices.toMap() }），
//      放掉鎖之後才 started.countDown() 與 Thread.sleep(400)
// （寫檔案在鎖外面）；latest() 一樣用 synchronized 讀。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced04-races/L04_LongCriticalSection.kt
package tw.harry.labs.practice.advanced04races

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 演練流程（兩個版本共用）：背景開始存快照，fake-main 在存的途中讀價格，
 * 量它等了多久
 */
private suspend fun scenario(
    log: LabLog,
    saveSnapshot: (started: CountDownLatch) -> Unit,
    readLatest: () -> Int,
) = withContext(Dispatchers.Default) {
    val started = CountDownLatch(1)
    val saver = thread(name = "snapshot-writer") { saveSnapshot(started) }
    // 等背景開始存（Bug 版是已經拿到鎖）
    started.await()
    Thread.sleep(20)
    var price = 0
    var waited = 0.0
    val reader = thread(name = "fake-main") {
        val t0 = a04NowMs()
        // 「主執行緒」讀一筆價格
        price = readLatest()
        waited = a04NowMs() - t0
    }
    reader.join()
    saver.join()
    log("主執行緒讀到價格 $price，等了 ${waited.toInt()} ms")
    log("快照寫完了")
}

// MARK: - Bug 版
private object L04Bug {
    class PriceTable {
        private val lock = Any()
        private val prices = mutableMapOf(
            "BTC/JPY" to 15_000_000,
            "ETH/JPY" to 400_000,
        )

        fun saveSnapshot(started: CountDownLatch) {
            // TODO ①-1：synchronized(lock) { started.countDown(); Thread.
            // sleep(400) }：持鎖的期間寫檔案（示意），I/O 在鎖裡面
            started.countDown()
        }

        fun latest(): Int {
            // TODO ①-2：synchronized(lock) { prices["BTC/JPY"] ?: 0 }
            return 0
        }
    }

    suspend fun run(log: LabLog) {
        val table = PriceTable()
        scenario(log, saveSnapshot = {
            table.saveSnapshot(it)
        }, readLatest = { table.latest() })
    }
}

// MARK: - 修正版
private object L04Fixed {
    class PriceTable {
        private val lock = Any()
        private val prices = mutableMapOf(
            "BTC/JPY" to 15_000_000,
            "ETH/JPY" to 400_000,
        )

        fun saveSnapshot(started: CountDownLatch) {
            // TODO ②-1：val copy = synchronized(lock) { prices.toMap() }：
            // 持鎖期間只複製資料
            started.countDown()
            // 寫檔案在鎖外面（已寫好）
            Thread.sleep(400)
        }

        fun latest(): Int {
            // TODO ②-2：synchronized(lock) { prices["BTC/JPY"] ?: 0 }
            return 0
        }
    }

    suspend fun run(log: LabLog) {
        val table = PriceTable()
        scenario(log, saveSnapshot = {
            table.saveSnapshot(it)
        }, readLatest = { table.latest() })
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "存快照的時候，畫面讀一下價格就被卡住",
    question = "第 5 題：priority inversion 是什麼？",
    goal = "鎖裡面只放很短的資料讀寫，不要做網路或讀寫檔案這類 I/O；主執行緒不要去拿「背景可能長時間占用」的鎖。先寫出鎖裡面做 I/O 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced04-races/L04_LongCriticalSection.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看「主執行緒讀到價格，等了幾 ms」：約 380 ms。",
        "寫 TODO ②，選「修正版」執行：等了不到幾毫秒。",
        "進階：真的 App 在背景用 `Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)` 的執行緒持有這把鎖，手機上優先權低的執行緒能分到的 CPU 時間更少，主執行緒會等得更久。用 Perfetto 錄下來，主執行緒的 `monitor contention with owner snapshot-writer` 區段就是在等這把鎖；StrictMode 的 `detectDiskReads/detectDiskWrites` 可以抓到主執行緒上的磁碟存取。",
        "想一想：iOS 的 os_unfair_lock 會把等待者的優先權借給持鎖的人，Android 的 synchronized 沒有這個機制；所以 Android 上更要把臨界區縮短。（見這個單元的第 5 題）",
    ),
    expectBug = "主執行緒讀到價格 15000000，等了約 380 ms（背景持鎖寫檔案 400 ms，扣掉啟動後的 20 ms）。",
    expectFixed = "主執行緒讀到價格 15000000，等了不到 5 ms。",
    file = "advanced04-races/L04_LongCriticalSection.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val ms = a04Number(lines, "主執行緒讀到價格 15000000，等了 ")
        when {
            ms == null -> "演練沒有跑完：latest() 要回傳 BTC/JPY 的價格 15000000"
            ms >= 200 -> null
            else -> "主執行緒只等了 ${ms.toInt()} ms：Bug 版要在 synchronized(lock) 的期間寫檔案（Thread.sleep(400)），主執行緒的 latest() 要拿同一把鎖"
        }
    },
    checkFixed = { lines ->
        val ms = a04Number(lines, "主執行緒讀到價格 15000000，等了 ")
        when {
            ms == null -> "演練沒有跑完：latest() 要回傳 BTC/JPY 的價格 15000000"
            ms <= 150 -> null
            else -> "主執行緒等了 ${ms.toInt()} ms，太久：寫檔案要放到鎖外面，持鎖期間只複製資料（prices.toMap()）"
        }
    },
)
