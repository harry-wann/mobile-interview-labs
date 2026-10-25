// L03：行情推進來的同時下單，兩邊都卡住不動（持鎖時呼叫 callback，
// 兩把鎖的順序相反造成 deadlock）
// 對應複習站：Advanced04Races 第 4 題「deadlock 是什麼？怎麼避免、怎麼診斷？」
//
// 情境：價格表 PriceStore 有自己的鎖（價格鎖），委託管理 OrderManager
// 有自己的鎖（委託鎖）。
//        1. WebSocket 執行緒：PriceStore.update 寫入新價格，接著通知
// listener（OrderManager.onPrice）去更新持倉的估值，onPrice 需要委託鎖。
//        2. 畫面執行緒：OrderManager.placeOrder 下單，先拿委託鎖，再向
// PriceStore 查最新價（latest），latest 需要價格鎖。
//        兩條執行緒同時進來：A 拿著價格鎖等委託鎖，B 拿著委託鎖等價格鎖，
// 互相等對方放開，誰也動不了。
//        （演練用 ReentrantLock.tryLock(300, MILLISECONDS) 當保險，
// 逾時就放棄並記錄，不會真的把 App 卡死。真的 App 用 synchronized 或 lock()
// 沒有這個保險，
//        就是整個凍住，主執行緒被卡住超過 5 秒就是 ANR。）
// Bug 版：update 持有價格鎖的時候，直接呼叫 listener（callback 在鎖裡面）。
// 修正版：持鎖期間只做資料的讀寫，把要通知的 listener 複製出來；放掉鎖之後，
// 才呼叫它。這樣沒有任何一條執行緒同時拿著兩把鎖。
package tw.harry.labs.solution.advanced04races

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 拿鎖，最多等 300 毫秒；拿不到就記錄「疑似 deadlock」並回傳
 * false（兩個版本共用）
 */
private fun timedLock(
    lock: ReentrantLock,
    name: String,
    who: String,
    log: LabLog,
): Boolean {
    if (lock.tryLock(300, TimeUnit.MILLISECONDS)) return true
    log("$who：等${name}等了 300 ms 還拿不到，疑似 deadlock，放棄")
    return false
}

/** 持有鎖的期間做一點事（讓兩條執行緒一定會互相卡到） */
private fun work() = Thread.sleep(30)

/** 演練流程（兩個版本共用）：兩條執行緒同時進來，等兩邊都結束 */
private suspend fun scenario(update: () -> Unit, placeOrder: () -> Unit) =
    withContext(Dispatchers.Default) {
        val start = CountDownLatch(1)
        val threads = listOf(update, placeOrder).map { body ->
            thread {
                // 兩條執行緒同時開始
                start.await()
                body()
            }
        }
        start.countDown()
        threads.forEach { it.join() }
    }

// MARK: - Bug 版
private object L03Bug {
    class PriceStore(private val log: LabLog) {
        val lock = ReentrantLock()
        private val prices = mutableMapOf("BTC/JPY" to 15_000_000)
        var listener: ((String, Int) -> Unit)? = null

        fun update(symbol: String, price: Int) {
            if (!timedLock(lock, "價格鎖", "WebSocket 執行緒", log)) return
            try {
                prices[symbol] = price
                work()
                // 還拿著價格鎖，就呼叫 callback：callback 裡面會去拿委託鎖
                listener?.invoke(symbol, price)
            } finally {
                lock.unlock()
            }
        }

        fun latest(symbol: String, who: String): Int? {
            if (!timedLock(lock, "價格鎖", who, log)) return null
            try {
                return prices[symbol]
            } finally {
                lock.unlock()
            }
        }
    }

    class OrderManager(private val store: PriceStore, private val log: LabLog) {
        private val lock = ReentrantLock()

        fun onPrice(symbol: String, price: Int) {
            if (!timedLock(lock, "委託鎖", "WebSocket 執行緒", log)) return
            try {
                log("WebSocket 執行緒：更新持倉估值 $symbol $price")
            } finally {
                lock.unlock()
            }
        }

        fun placeOrder(symbol: String) {
            if (!timedLock(lock, "委託鎖", "畫面執行緒", log)) return
            try {
                work()
                // 拿著委託鎖，去拿價格鎖
                store.latest(symbol, "畫面執行緒")?.let { log("畫面執行緒：下單成功，用價格 $it") }
            } finally {
                lock.unlock()
            }
        }
    }

    suspend fun run(log: LabLog) {
        val store = PriceStore(log)
        val orders = OrderManager(store, log)
        store.listener = { symbol, price -> orders.onPrice(symbol, price) }
        scenario(update = {
            store.update("BTC/JPY", 15_100_000)
        }, placeOrder = { orders.placeOrder("BTC/JPY") })
    }
}

// MARK: - 修正版
private object L03Fixed {
    class PriceStore(private val log: LabLog) {
        val lock = ReentrantLock()
        private val prices = mutableMapOf("BTC/JPY" to 15_000_000)
        var listener: ((String, Int) -> Unit)? = null

        fun update(symbol: String, price: Int) {
            if (!timedLock(lock, "價格鎖", "WebSocket 執行緒", log)) return
            val callback: ((String, Int) -> Unit)?
            try {
                prices[symbol] = price
                work()
                // 把要通知的東西複製出來
                callback = listener
            } finally {
                lock.unlock() // 先放掉鎖
            }
            // 再呼叫 callback：不再同時拿著兩把鎖
            callback?.invoke(symbol, price)
        }

        fun latest(symbol: String, who: String): Int? {
            if (!timedLock(lock, "價格鎖", who, log)) return null
            try {
                return prices[symbol]
            } finally {
                lock.unlock()
            }
        }
    }

    class OrderManager(private val store: PriceStore, private val log: LabLog) {
        private val lock = ReentrantLock()

        fun onPrice(symbol: String, price: Int) {
            if (!timedLock(lock, "委託鎖", "WebSocket 執行緒", log)) return
            try {
                log("WebSocket 執行緒：更新持倉估值 $symbol $price")
            } finally {
                lock.unlock()
            }
        }

        fun placeOrder(symbol: String) {
            if (!timedLock(lock, "委託鎖", "畫面執行緒", log)) return
            try {
                work()
                store.latest(symbol, "畫面執行緒")?.let { log("畫面執行緒：下單成功，用價格 $it") }
            } finally {
                lock.unlock()
            }
        }
    }

    suspend fun run(log: LabLog) {
        val store = PriceStore(log)
        val orders = OrderManager(store, log)
        store.listener = { symbol, price -> orders.onPrice(symbol, price) }
        scenario(update = {
            store.update("BTC/JPY", 15_100_000)
        }, placeOrder = { orders.placeOrder("BTC/JPY") })
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "行情推進來的同時下單，兩邊都卡住不動",
    question = "第 4 題：deadlock 是什麼？怎麼避免、怎麼診斷？",
    goal = "持有鎖的時候不要呼叫 callback：先把需要的資料複製出來、放掉鎖，再呼叫。這樣不會同時拿著兩把鎖，也就不會有循環等待。",
    steps = listOf(
        "選「Bug 版」執行，看 log：「等…等了 300 ms 還拿不到，疑似 deadlock」。WebSocket 執行緒拿著價格鎖在等委託鎖，畫面執行緒拿著委託鎖在等價格鎖。",
        "選「修正版」執行：兩條執行緒都完成，沒有逾時，log 裡有「更新持倉估值」與「下單成功」。",
        "診斷練習：真的 App 卡住（ANR）時，看系統留下的 /data/anr/ trace，主執行緒的堆疊會寫 `waiting to lock <0x…> held by thread 17`，再去看 17 號執行緒的堆疊，追出誰在等誰，就找到循環。",
        "想一想：另一個解法是兩邊都固定「先拿價格鎖、再拿委託鎖」的順序。為什麼「持鎖時不呼叫外部程式碼」通常比固定順序更好維護？（見這個單元的第 4 題）",
    ),
    expectBug = "「WebSocket 執行緒：等委託鎖等了 300 ms 還拿不到，疑似 deadlock，放棄」（也可能是畫面執行緒等價格鎖逾時）；其中一邊放棄之後另一邊才拿得到鎖，晚了 300 ms 才完成。真的 App 沒有逾時保險，就是兩邊永遠卡住。",
    expectFixed = "沒有任何「疑似 deadlock」；log 有「WebSocket 執行緒：更新持倉估值 BTC/JPY 15100000」與「畫面執行緒：下單成功，用價格 …」。",
    file = "advanced04-races/L03_CallbackDeadlock.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        if (lines.none {
                "疑似 deadlock" in it
            }
        ) {
            "Bug 還沒重現：update 要在持有價格鎖的時候呼叫 listener（callback 在鎖裡面）"
        } else {
            null
        }
    },
    checkFixed = { lines ->
        when {
            lines.any {
                "疑似 deadlock" in it
            } -> "還是 deadlock：update 要先把 listener 複製出來、unlock() 之後才呼叫它"

            lines.none {
                it.startsWith("WebSocket 執行緒：更新持倉估值")
            } -> "listener 沒有被呼叫：放掉鎖之後要呼叫 callback?.invoke(symbol, price)"

            lines.none { it.startsWith("畫面執行緒：下單成功") } -> "下單沒有成功"

            else -> null
        }
    },
)
