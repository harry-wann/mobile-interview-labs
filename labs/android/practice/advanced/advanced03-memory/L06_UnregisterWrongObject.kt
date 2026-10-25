// L06：以為 unregister 了，Activity 還是被抓著（unregister 傳進去的是另一個
// lambda）
// 對應複習站：Advanced03Memory 第 4 題「Android 有垃圾回收，為什麼還會洩漏？」
// （匿名內部類別與 lambda 會隱含持有外部類別）
//
// 情境：行情畫面在 onCreate 向單例 PriceFeed 註冊 listener 接收報價，onDestroy
// 有呼叫 unregister，看起來該做的都做了。
//        和 L03 的差別：L03 是「忘了解除」，這裡是「以為解除了，其實沒有」。
//        （JVM 單元測試沒有 Activity，所以用假的畫面物件，背一份 1 MB
// 資料代表整棵 view 樹。
//          真的 SharedPreferences.registerOnSharedPreferenceChangeListener 用
// WeakHashMap 存 listener，不會因此洩漏，
//          反而是 listener 沒存成欄位會被 GC 回收；所以演練用自己寫的單例
// PriceFeed，它和多數自製的 callback 清單一樣，用一般的 list 強持有
// listener。）
//
// 你要寫：
//   ① Bug 版：onCreate 用 L06PriceFeed.register { price -> render(price) }
// 註冊，onDestroy 用 L06PriceFeed.unregister { price -> render(price) } 解除
//            （兩邊各寫一個 lambda，lambda 用到 this，所以是兩個不同的物件）。
//   ② 修正版：宣告欄位 listener = L06PriceListener { price -> render(price) }，
// onCreate 註冊它，onDestroy 解除同一個。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced03-memory/L06_UnregisterWrongObject.kt
package tw.harry.labs.practice.advanced03memory

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 view 樹：背 1 MB 資料（兩個版本共用） */
private class L06ViewTree {
    val pixels = ByteArray(1_000_000)
}

/**
 * 價格 listener：只有一個方法的 interface，Kotlin 可以直接用 lambda
 * 代替它（SAM 轉換，single abstract method conversion），和 OnClickListener
 * 的用法一樣（兩個版本共用）
 */
private fun interface L06PriceListener {
    fun onPrice(price: Double)
}

/**
 * 單例：活得比任何一個畫面都久（兩個版本共用）。unregister 找不到就不會移除，
 * 和 MutableList.remove 一樣比對「同一個物件」
 */
private object L06PriceFeed {
    private val listeners = mutableListOf<L06PriceListener>()
    var unregisterCalls = 0
        private set
    var removed = 0
        private set
    val listenerCount: Int get() = listeners.size

    fun register(listener: L06PriceListener) {
        listeners += listener
    }

    fun unregister(listener: L06PriceListener) {
        unregisterCalls++
        if (listeners.remove(listener)) removed++
    }

    fun publish(price: Double) {
        listeners.toList().forEach { it.onPrice(price) }
    }

    fun reset() {
        listeners.clear()
        unregisterCalls = 0
        removed = 0
    }
}

/** 假的 Activity 生命週期（兩個版本共用的介面，各版本自己實作） */
private interface L06Screen {
    fun onCreate()
    fun onDestroy()
}

/**
 * 演練流程（兩個版本共用）：打開再關閉畫面，關閉後推一筆新價格，最後用
 * WeakReference 加 GC 檢查有沒有洩漏
 */
private suspend fun scenario(
    log: LabLog,
    newScreen: (rendered: AtomicInteger) -> L06Screen,
) = withContext(Dispatchers.Default) {
    L06PriceFeed.reset()
    val rendered = AtomicInteger()
    fun openAndCloseScreen(): WeakReference<L06Screen> {
        val screen = newScreen(rendered)
        screen.onCreate()
        log("打開畫面時，PriceFeed 有 ${L06PriceFeed.listenerCount} 個 listener")
        screen.onDestroy()
        return WeakReference(screen)
    }
    val ref = openAndCloseScreen()
    log(
        "onDestroy 呼叫了 unregister ${L06PriceFeed.unregisterCalls} 次，真的移除了 ${L06PriceFeed.removed} 個",
    )
    log("關閉畫面後，PriceFeed 還有 ${L06PriceFeed.listenerCount} 個 listener")
    L06PriceFeed.publish(15_000_000.0)
    log("關閉畫面後推一筆新價格，已經關掉的畫面又重畫了 ${rendered.get()} 次")
    log("關閉畫面之後：${if (a03IsLeaked(ref)) "還抓得到（洩漏）" else "已回收"}")
    L06PriceFeed.reset()
}

// MARK: - Bug 版
private object L06Bug {
    class FakeQuoteActivity(private val rendered: AtomicInteger) : L06Screen {
        val viewTree = L06ViewTree()
        var lastPrice = 0.0

        override fun onCreate() {
            // TODO ①-1：用 L06PriceFeed.register 註冊一個 { price ->
            // render(price) }：這個 lambda 用到 this，是第 1 個物件
        }

        override fun onDestroy() {
            // TODO ①-2：用 L06PriceFeed.unregister 解除，一樣直接寫 { price ->
            // render(price) }：又寫了一次，這是第 2 個物件，PriceFeed
            // 裡找不到它
        }

        private fun render(price: Double) {
            lastPrice = price
            // 真的 App 裡這裡會更新畫面
            rendered.incrementAndGet()
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { rendered ->
        FakeQuoteActivity(rendered)
    }
}

// MARK: - 修正版
private object L06Fixed {
    class FakeQuoteActivity(private val rendered: AtomicInteger) : L06Screen {
        val viewTree = L06ViewTree()
        var lastPrice = 0.0
        // TODO ②-1：宣告 private val listener：用 L06PriceListener { … } 建立，
        // 裡面呼叫 render(price)。存成欄位，從頭到尾只有這一個物件

        override fun onCreate() {
            // TODO ②-2：把 listener 註冊到 L06PriceFeed
        }

        override fun onDestroy() {
            // TODO ②-3：把同一個 listener 從 L06PriceFeed 解除：傳同一個參考，
            // PriceFeed 才找得到、移得掉
        }

        private fun render(price: Double) {
            lastPrice = price
            rendered.incrementAndGet()
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { rendered ->
        FakeQuoteActivity(rendered)
    }
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "呼叫了 unregister，Activity 還是被抓著",
    question = "第 4 題：Android 有垃圾回收，為什麼還會洩漏？",
    goal = "onDestroy 有呼叫 unregister，Activity 卻還是回收不了。找出為什麼（傳進去的不是註冊時的那個 lambda），並用 WeakReference 加 GC 驗證修好了。",
    steps = listOf(
        "打開 practice/advanced/advanced03-memory/L06_UnregisterWrongObject.kt，寫 TODO ①，選「練習」「Bug 版」執行，看 log：onDestroy 呼叫了 unregister 1 次，但「真的移除了 0 個」，關閉畫面後 PriceFeed 還有 1 個 listener，最後一行是「（洩漏）」。",
        "再看「關閉畫面後推一筆新價格，已經關掉的畫面又重畫了 1 次」：關掉的畫面還在處理價格。",
        "想一想：onCreate 與 onDestroy 各寫一個 { price -> render(price) }，為什麼不是同一個物件？和 L03 比，那邊是沒解除，這裡是解除失敗，面試時怎麼說明差別？",
        "寫 TODO ②，選「修正版」執行：真的移除了 1 個、重畫 0 次、已回收。",
        "進階：真的 App 的 SharedPreferences 用 WeakHashMap 存 listener，不會因為註冊而洩漏，反而是 listener 如果沒存成欄位，會被 GC 回收、之後收不到通知。想一想這和這個演練的 PriceFeed 差在哪。",
    ),
    expectBug = "onDestroy 呼叫了 unregister 1 次，真的移除了 0 個；關閉畫面後 PriceFeed 還有 1 個 listener，又重畫了 1 次；關閉畫面之後：還抓得到（洩漏）。",
    expectFixed = "onDestroy 呼叫了 unregister 1 次，真的移除了 1 個；關閉畫面後 PriceFeed 還有 0 個 listener，重畫 0 次；關閉畫面之後：已回收。",
    file = "advanced03-memory/L06_UnregisterWrongObject.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val closed = lines.firstOrNull { it.startsWith("關閉畫面之後") }
        when {
            closed == null -> "演練沒有跑完"
            "打開畫面時，PriceFeed 有 1 個 listener" !in lines -> "Bug 版 onCreate 要用 PriceFeed.register { price -> render(price) } 註冊一個 listener"
            "onDestroy 呼叫了 unregister 1 次，真的移除了 0 個" !in lines -> "Bug 版 onDestroy 要呼叫 PriceFeed.unregister，傳新寫的 { price -> render(price) }：它不是註冊時的那一個，所以移除 0 個"
            "關閉畫面後推一筆新價格，已經關掉的畫面又重畫了 1 次" !in lines -> "Bug 版關閉畫面後 listener 還在 PriceFeed 裡，推新價格時 render 應該被呼叫 1 次"
            "洩漏" !in closed -> "沒有洩漏：Bug 版的 listener 要留在 PriceFeed 裡抓著 Activity"
            else -> null
        }
    },
    checkFixed = { lines ->
        val closed = lines.firstOrNull { it.startsWith("關閉畫面之後") }
        when {
            closed == null -> "演練沒有跑完"
            "打開畫面時，PriceFeed 有 1 個 listener" !in lines -> "onCreate 還是要把 listener 註冊到 PriceFeed（打開時要有 1 個），只是 onDestroy 要用同一個物件解除"
            "onDestroy 呼叫了 unregister 1 次，真的移除了 1 個" !in lines -> "unregister 要真的移除 1 個：listener 存成欄位，register 與 unregister 傳同一個參考"
            "關閉畫面後推一筆新價格，已經關掉的畫面又重畫了 0 次" !in lines -> "關閉畫面之後，推新價格時 render 不該再被呼叫"
            "洩漏" in closed -> "還在洩漏：onDestroy 要 unregister 註冊時的那一個 listener"
            else -> null
        }
    },
)
