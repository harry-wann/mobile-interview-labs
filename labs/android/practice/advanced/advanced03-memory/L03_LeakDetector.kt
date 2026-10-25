// L03：離開畫面之後，整棵 view 樹還留在記憶體裡（Android 的洩漏：被活得更久的東西意外抓著）
// 對應複習站：Advanced03Memory 第 4 題「Android 有垃圾回收，為什麼還會洩漏？」
//
// 情境：Android 有 GC，互相持有沒關係；洩漏是「該被回收的物件，被一個活得更久的東西意外抓著」。LeakCanary 的做法：
//        畫面銷毀之後，用 WeakReference 觀察它，等一下、做一次 GC，如果 WeakReference 還拿得到物件，就是洩漏。演練用同樣的做法檢查兩種最常見的洩漏
//        （JVM 單元測試沒有 Activity 與 Fragment，所以用假的畫面物件，各背一份 1 MB 的資料代表整棵 view 樹）：
//        A. 單例（PriceFeed）的 listener 清單抓著 Activity。 B. Fragment 進了 back stack，view 被銷毀了，Fragment 的 binding 欄位卻還抓著整棵 view 樹。
//
// 你要寫：
//   ① Bug 版：A. FakeActivity.onCreate 把 lambda { price -> lastPrice = price } 加進 PriceFeed.listeners，onDestroy 不移除。
//             B. FakeFragment.onViewCreated 把 tree 存進 _binding，onDestroyView 不設成 null。
//   ② 修正版：A. onCreate 把已經宣告好的 listener 加進 PriceFeed.listeners，onDestroy 用 -= 移除同一個 listener。
//             B. onViewCreated 存進 _binding，onDestroyView 把 _binding 設成 null。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced03-memory/L03_LeakDetector.kt
package tw.harry.labs.practice.advanced03memory

import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的 view 樹：背 1 MB 資料（兩個版本共用） */
private class FakeViewTree {
    val pixels = ByteArray(1_000_000)
}

/** 單例：活得比任何一個畫面都久（兩個版本共用）。每次演練開始都清空 */
private object PriceFeed {
    val listeners = mutableListOf<(Double) -> Unit>()
}

/** 假的 Activity 與 Fragment 的生命週期（兩個版本共用的介面，各版本自己實作） */
private interface FakeActivityLike {
    fun onCreate()
    fun onDestroy()
}

private interface FakeFragmentLike {
    val hasBinding: Boolean
    fun onViewCreated(tree: FakeViewTree)
    fun onDestroyView()
}

/** 像 LeakCanary 一樣檢查：做幾次 GC，WeakReference 還拿得到物件就是洩漏（兩個版本共用） */
private fun isLeaked(ref: WeakReference<*>): Boolean {
    repeat(5) {
        a03ForceGc()                                              // 不能只用 System.gc()：在 Android 上它只是提示
        if (ref.get() == null) return false
    }
    return true
}

/** 演練流程（兩個版本共用）：A. 開啟再關閉 Activity，B. Fragment 的 view 被銷毀（Fragment 還在 back stack） */
private suspend fun scenario(log: LabLog, newActivity: () -> FakeActivityLike, newFragment: () -> FakeFragmentLike) = withContext(Dispatchers.Default) {
    PriceFeed.listeners.clear()
    fun openAndCloseActivity(): WeakReference<FakeActivityLike> {
        val activity = newActivity()
        activity.onCreate()
        log("A. 打開 Activity 時，PriceFeed 的 listener 有 ${PriceFeed.listeners.size} 個")
        activity.onDestroy()
        return WeakReference(activity)
    }
    val activityRef = openAndCloseActivity()
    log("A. 關閉 Activity 之後：${if (isLeaked(activityRef)) "還抓得到（洩漏）" else "已回收"}")
    PriceFeed.listeners.clear()

    val backStack = mutableListOf<FakeFragmentLike>()
    fun destroyFragmentView(): WeakReference<FakeViewTree> {
        val tree = FakeViewTree()
        val fragment = newFragment()
        fragment.onViewCreated(tree)
        log("B. 打開 Fragment 時，Fragment ${if (fragment.hasBinding) "有" else "沒有"}存 binding")
        fragment.onDestroyView()
        backStack += fragment                                        // Fragment 實例還活著（在 back stack 裡）
        return WeakReference(tree)
    }
    val treeRef = destroyFragmentView()
    log("B. Fragment 的 view 被銷毀之後：${if (isLeaked(treeRef)) "view 樹還抓得到（洩漏）" else "view 樹已回收"}")
}

// MARK: - Bug 版
private object L03Bug {
    class FakeActivity : FakeActivityLike {
        val viewTree = FakeViewTree()
        var lastPrice = 0.0

        override fun onCreate() {
            // TODO ①-1：PriceFeed.listeners += { price -> lastPrice = price }：lambda 會抓著 this（Activity）
        }

        override fun onDestroy() {
            // （Bug 版這裡什麼都不做：沒有把 listener 從 PriceFeed 移除）
        }
    }

    class FakeFragment : FakeFragmentLike {
        private var _binding: FakeViewTree? = null
        override val hasBinding: Boolean get() = _binding != null

        override fun onViewCreated(tree: FakeViewTree) {
            // TODO ①-2：_binding = tree
        }

        override fun onDestroyView() {
            // （Bug 版這裡什麼都不做：沒有 _binding = null，Fragment 還在 back stack，它的 view 樹卻放不掉）
        }
    }

    suspend fun run(log: LabLog) = scenario(log, { FakeActivity() }, { FakeFragment() })
}

// MARK: - 修正版
private object L03Fixed {
    class FakeActivity : FakeActivityLike {
        val viewTree = FakeViewTree()
        var lastPrice = 0.0
        private val listener: (Double) -> Unit = { price -> lastPrice = price }

        override fun onCreate() {
            // TODO ②-1：PriceFeed.listeners += listener
        }

        override fun onDestroy() {
            // TODO ②-2：PriceFeed.listeners -= listener：畫面銷毀時，把自己從活得更久的單例移除
        }
    }

    class FakeFragment : FakeFragmentLike {
        private var _binding: FakeViewTree? = null
        override val hasBinding: Boolean get() = _binding != null

        override fun onViewCreated(tree: FakeViewTree) {
            // TODO ②-3：_binding = tree
        }

        override fun onDestroyView() {
            // TODO ②-4：_binding = null：放掉 view 樹，GC 才能回收它
        }
    }

    suspend fun run(log: LabLog) = scenario(log, { FakeActivity() }, { FakeFragment() })
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "離開畫面之後，整棵 view 樹還留在記憶體裡",
    question = "第 4 題：Android 有垃圾回收，為什麼還會洩漏？",
    goal = "找出「被活得更久的東西意外抓著」的兩種常見寫法：單例的 listener 清單、Fragment 的 binding 欄位，並用 WeakReference 加 GC 驗證。先寫出會洩漏的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced03-memory/L03_LeakDetector.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看 log：A 與 B 都是「（洩漏）」。這就是 LeakCanary 檢查的方式。",
        "寫 TODO ②，選「修正版」執行：A 的 Activity 與 B 的 view 樹都已回收。",
        "進階：在真的 App 的 debug 版（已經裝了 LeakCanary 2.14）反覆進出一個有洩漏的畫面，通知列會跳出洩漏通知，點進去看 reference chain：從哪個 static 或單例一路抓到 Activity。Android Studio 的 Memory Profiler 也可以抓 heap dump，離開畫面後看 Activity 實例數是否還大於 0。",
        "想一想：把單例換成 applicationContext 為什麼也能避免 A？（applicationContext 活得和行程一樣久，不會抓住任何一個畫面，見這個單元的第 4 題）",
    ),
    expectBug = "A. 關閉 Activity 之後：還抓得到（洩漏）；B. Fragment 的 view 被銷毀之後：view 樹還抓得到（洩漏）。",
    expectFixed = "A. 關閉 Activity 之後：已回收；B. Fragment 的 view 被銷毀之後：view 樹已回收。",
    file = "advanced03-memory/L03_LeakDetector.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val a = lines.firstOrNull { it.startsWith("A. 關閉 Activity") }
        val b = lines.firstOrNull { it.startsWith("B. Fragment 的 view 被銷毀") }
        when {
            a == null || b == null -> "演練沒有跑完"
            "A. 打開 Activity 時，PriceFeed 的 listener 有 1 個" !in lines -> "Bug 版 onCreate 要把 listener 加進 PriceFeed.listeners"
            "B. 打開 Fragment 時，Fragment 有存 binding" !in lines -> "Bug 版 onViewCreated 要把 tree 存進 _binding"
            "洩漏" !in a -> "A 沒有洩漏：Bug 版的 onDestroy 不能把 listener 移除"
            "洩漏" !in b -> "B 沒有洩漏：Bug 版的 onDestroyView 不能把 _binding 設成 null"
            else -> null
        }
    },
    checkFixed = { lines ->
        val a = lines.firstOrNull { it.startsWith("A. 關閉 Activity") }
        val b = lines.firstOrNull { it.startsWith("B. Fragment 的 view 被銷毀") }
        when {
            a == null || b == null -> "演練沒有跑完"
            "A. 打開 Activity 時，PriceFeed 的 listener 有 1 個" !in lines -> "onCreate 還是要把 listener 加進 PriceFeed.listeners（打開時要有 1 個），只是 onDestroy 要移除"
            "B. 打開 Fragment 時，Fragment 有存 binding" !in lines -> "onViewCreated 還是要把 tree 存進 _binding，只是 onDestroyView 要設成 null"
            "洩漏" in a -> "A 還在洩漏：onDestroy 要 PriceFeed.listeners -= listener（同一個 listener 物件，所以要存在欄位上）"
            "洩漏" in b -> "B 還在洩漏：onDestroyView 要 _binding = null"
            else -> null
        }
    },
)
