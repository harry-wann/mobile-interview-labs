// L05：關掉畫面之後，Activity 還被 static 欄位和主執行緒的延遲訊息抓著（static
// 持有 Activity、Handler.postDelayed）
// 對應複習站：Advanced03Memory 第 4 題「Android 有垃圾回收，為什麼還會洩漏？」
//
// 情境：價格提醒功能有兩個很常見的寫法（JVM 單元測試沒有 Activity，
// 所以用假的畫面物件，各背一份 1 MB 資料代表整棵 view 樹）：
//        A. 下單畫面為了「從任何地方都能跳出提示」，把自己存進全域的 AppState.
// currentActivity（object 裡的欄位是 static，活得和行程一樣久）。
//        B. 價格提醒畫面用 Handler(Looper.getMainLooper()).postDelayed(…,
// 30_000) 排一個 30 秒後「提醒使用者」的工作。
//           使用者在 30 秒內離開畫面，這個訊息還在主執行緒的 MessageQueue 裡：
// MessageQueue → Message → Runnable → Activity。
//        JVM 單元測試裡的 Handler 是空殼（postDelayed 什麼都不做），
// 所以用小型的 FakeHandler：把 Runnable 存進 list，removeCallbacks 真的移除。
//        真的 Handler 的行為一樣：postDelayed 把 Runnable 包成 Message
// 放進主執行緒 Looper 的 MessageQueue，
//        removeCallbacks(r) 只移除 callback 是「同一個 r」的 Message。
// Bug 版：A. onCreate 存進 AppState.currentActivity，onDestroy 沒有清掉。 B.
// postDelayed 之後，onDestroy 沒有 removeCallbacks。
// 修正版：A. onDestroy 把 AppState.currentActivity 清成 null（只清自己）。 B.
// Runnable 存成欄位，onDestroy 用 removeCallbacks 移除同一個。
package tw.harry.labs.solution.advanced03memory

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 view 樹：背 1 MB 資料（兩個版本共用） */
private class L05ViewTree {
    val pixels = ByteArray(1_000_000)
}

/** 假的 Activity 生命週期（兩個版本共用的介面，各版本自己實作） */
private interface L05Screen {
    fun onCreate()
    fun onDestroy()
}

/**
 * 全域的「目前畫面」：object 裡的欄位是 static，
 * 活得比任何一個畫面都久（兩個版本共用）。真的 App 裡常叫 AppState 或 Session
 */
private object L05AppState {
    var currentActivity: L05Screen? = null
}

/**
 * 主執行緒的訊息佇列：和行程一樣久（真的 Looper.getMainLooper() 的
 * MessageQueue 也是）（兩個版本共用）
 */
private object L05MainQueue {
    class Message(val callback: Runnable, val delayMs: Long)

    val messages = mutableListOf<Message>()

    /** 快轉時間：到時間的訊息拿出來執行（真的 Looper 到時間會自己做） */
    fun advance(elapsedMs: Long) {
        val due = messages.filter { it.delayMs <= elapsedMs }
        messages.removeAll(due)
        due.forEach { it.callback.run() }
    }
}

/**
 * 小型假 Handler（兩個版本共用）：簽名和真的 Handler.
 * postDelayed／removeCallbacks 一樣
 */
private class L05FakeHandler {
    fun postDelayed(callback: Runnable, delayMs: Long) {
        L05MainQueue.messages += L05MainQueue.Message(callback, delayMs)
    }

    fun removeCallbacks(callback: Runnable) {
        // 真的 Handler 也是比對「同一個」Runnable
        L05MainQueue.messages.removeAll { it.callback === callback }
    }
}

/**
 * 演練流程（兩個版本共用）：
 * A. 打開再關閉下單畫面，B. 打開再關閉價格提醒畫面，然後快轉 30 秒。
 * 兩個畫面分開檢查，才知道各是誰抓著。
 */
private suspend fun scenario(
    log: LabLog,
    newOrderScreen: () -> L05Screen,
    newAlertScreen: (reminded: AtomicInteger) -> L05Screen,
) = withContext(Dispatchers.Default) {
    L05AppState.currentActivity = null
    L05MainQueue.messages.clear()

    fun openAndCloseOrderScreen(): WeakReference<L05Screen> {
        val screen = newOrderScreen()
        screen.onCreate()
        log(
            "A. 打開下單畫面時，AppState.currentActivity 指向這個畫面：${if (L05AppState.currentActivity === screen) "是" else "否"}",
        )
        screen.onDestroy()
        return WeakReference(screen)
    }
    val orderRef = openAndCloseOrderScreen()
    log("A. 關閉下單畫面之後：${if (a03IsLeaked(orderRef)) "還抓得到（洩漏）" else "已回收"}")
    L05AppState.currentActivity = null

    val reminded = AtomicInteger()
    fun openAndCloseAlertScreen(): WeakReference<L05Screen> {
        val screen = newAlertScreen(reminded)
        screen.onCreate()
        log("B. 打開提醒畫面時，主執行緒佇列裡有 ${L05MainQueue.messages.size} 個 30 秒後的提醒")
        screen.onDestroy()
        return WeakReference(screen)
    }
    val alertRef = openAndCloseAlertScreen()
    log("B. 關閉提醒畫面之後：${if (a03IsLeaked(alertRef)) "還抓得到（洩漏）" else "已回收"}")
    // 過了 30 秒，佇列裡到時間的訊息會執行
    L05MainQueue.advance(30_000)
    log("B. 過了 30 秒：已經關掉的畫面又被提醒了 ${reminded.get()} 次")
    L05MainQueue.messages.clear()
}

// MARK: - Bug 版
private object L05Bug {
    class FakeOrderActivity : L05Screen {
        val viewTree = L05ViewTree()

        override fun onCreate() {
            // static 欄位抓著 this（Activity）
            L05AppState.currentActivity = this
        }

        override fun onDestroy() {
            // 沒有把 AppState.currentActivity 清掉
        }
    }

    class FakeAlertActivity(private val reminded: AtomicInteger) : L05Screen {
        val viewTree = L05ViewTree()

        // 真的是 Handler(Looper.getMainLooper())
        private val handler = L05FakeHandler()

        override fun onCreate() {
            // lambda 用到 this：MessageQueue → Message → lambda → Activity
            handler.postDelayed({ showReminder() }, 30_000)
        }

        override fun onDestroy() {
            // 沒有 removeCallbacks：30 秒內這個訊息一直待在佇列裡，
            // 到時間還會對已經關掉的畫面執行
        }

        // 真的 App 裡這裡會跳 AlertDialog 或更新 view，對已經銷毀的 Activity
        // 做這些事會丟 WindowManager.BadTokenException 或更新不存在的 view
        private fun showReminder() {
            reminded.incrementAndGet()
        }
    }

    suspend fun run(log: LabLog) = scenario(log, {
        FakeOrderActivity()
    }, { reminded -> FakeAlertActivity(reminded) })
}

// MARK: - 修正版
private object L05Fixed {
    class FakeOrderActivity : L05Screen {
        val viewTree = L05ViewTree()

        override fun onCreate() {
            L05AppState.currentActivity = this
        }

        override fun onDestroy() {
            // 只清掉自己：下一個畫面的 onCreate 會在這個畫面的 onDestroy
            // 之前執行，不能把人家剛存進去的也清掉
            if (L05AppState.currentActivity ===
                this
            ) {
                L05AppState.currentActivity = null
            }
        }
    }

    class FakeAlertActivity(private val reminded: AtomicInteger) : L05Screen {
        val viewTree = L05ViewTree()
        private val handler = L05FakeHandler()

        // 存成欄位：removeCallbacks 才傳得到同一個 Runnable
        private val reminder = Runnable { showReminder() }

        override fun onCreate() {
            handler.postDelayed(reminder, 30_000)
        }

        override fun onDestroy() {
            // 畫面銷毀時，把還沒執行的訊息從佇列拿掉
            handler.removeCallbacks(reminder)
        }

        private fun showReminder() {
            reminded.incrementAndGet()
        }
    }

    suspend fun run(log: LabLog) = scenario(log, {
        FakeOrderActivity()
    }, { reminded -> FakeAlertActivity(reminded) })
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "static 欄位和 30 秒提醒讓 Activity 回收不了",
    question = "第 4 題：Android 有垃圾回收，為什麼還會洩漏？",
    goal = "找出另外兩種「被活得更久的東西意外抓著」的寫法：static 欄位存了 Activity、Handler.postDelayed 的 Runnable 抓著畫面，並用 WeakReference 加 GC 驗證。",
    steps = listOf(
        "選「Bug 版」執行，看 log：A 與 B 的「關閉……之後」都是「（洩漏）」，B 還多一行「過了 30 秒：已經關掉的畫面又被提醒了 1 次」。",
        "想一想誰抓著 Activity：A 是 AppState（object 裡的欄位是 static）；B 是主執行緒的 MessageQueue，postDelayed 的訊息會待在佇列裡直到時間到。為什麼主執行緒的 Looper 活得和行程一樣久？",
        "選「修正版」執行：A 的 Activity 與 B 的 Activity 都已回收，B 過了 30 秒也沒有再提醒。",
        "進階：在你自己 App 的 debug 版（有裝 LeakCanary）寫一個有同樣問題的畫面，進出幾次，通知列會跳出洩漏通知，reference chain 會從 static 欄位或主執行緒的 MessageQueue 一路連到 Activity。",
        "想一想：A 的修正為什麼只清掉「自己」？更好的 B 寫法是 lifecycleScope.launch { delay(30_000); … }，畫面銷毀時協程會自動取消，不用自己 removeCallbacks。",
    ),
    expectBug = "A. 關閉下單畫面之後：還抓得到（洩漏）；B. 關閉提醒畫面之後：還抓得到（洩漏），過了 30 秒：已經關掉的畫面又被提醒了 1 次。",
    expectFixed = "A. 關閉下單畫面之後：已回收；B. 關閉提醒畫面之後：已回收，過了 30 秒：已經關掉的畫面又被提醒了 0 次。",
    file = "advanced03-memory/L05_StaticAndHandler.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val a = lines.firstOrNull { it.startsWith("A. 關閉下單畫面之後") }
        val b = lines.firstOrNull { it.startsWith("B. 關閉提醒畫面之後") }
        when {
            a == null || b == null -> "演練沒有跑完"
            "A. 打開下單畫面時，AppState.currentActivity 指向這個畫面：是" !in lines -> "Bug 版 FakeOrderActivity.onCreate 要把 this 存進 L05AppState.currentActivity"
            "B. 打開提醒畫面時，主執行緒佇列裡有 1 個 30 秒後的提醒" !in lines -> "Bug 版 FakeAlertActivity.onCreate 要用 handler.postDelayed({ showReminder() }, 30_000) 排一個提醒"
            "洩漏" !in a -> "A 沒有洩漏：Bug 版的 onDestroy 不能把 L05AppState.currentActivity 清掉"
            "洩漏" !in b -> "B 沒有洩漏：Bug 版的 onDestroy 不能 removeCallbacks"
            "B. 過了 30 秒：已經關掉的畫面又被提醒了 1 次" !in lines -> "Bug 版 postDelayed 的 lambda 要呼叫 showReminder()，30 秒後對已經關掉的畫面執行"
            else -> null
        }
    },
    checkFixed = { lines ->
        val a = lines.firstOrNull { it.startsWith("A. 關閉下單畫面之後") }
        val b = lines.firstOrNull { it.startsWith("B. 關閉提醒畫面之後") }
        when {
            a == null || b == null -> "演練沒有跑完"
            "A. 打開下單畫面時，AppState.currentActivity 指向這個畫面：是" !in lines -> "onCreate 還是要把 this 存進 L05AppState.currentActivity，只是 onDestroy 要清掉"
            "B. 打開提醒畫面時，主執行緒佇列裡有 1 個 30 秒後的提醒" !in lines -> "onCreate 還是要用 handler.postDelayed(reminder, 30_000) 排提醒，只是 onDestroy 要移除"
            "洩漏" in a -> "A 還在洩漏：onDestroy 要把 L05AppState.currentActivity 設成 null（先確認它還是自己）"
            "洩漏" in b -> "B 還在洩漏：onDestroy 要 handler.removeCallbacks(reminder)，傳進去的要和 postDelayed 是同一個 Runnable（所以要存在欄位上）"
            "B. 過了 30 秒：已經關掉的畫面又被提醒了 0 次" !in lines -> "B 關掉畫面之後，30 秒到了不該再執行 showReminder()"
            else -> null
        }
    },
)
