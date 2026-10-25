// L05：離開 A 頁之後，畫面已經不在了，餘額還在更新；
// 回來之後同一筆餘額被處理兩次（Fragment 的 view 有自己的 lifecycle）
// 對應複習站：Platform01Lifecycle 第 5 題「Fragment 為什麼有兩套生命週期？Flow
// 要怎麼跟著生命週期收集？」
//
// 情境：A 頁是一個 Fragment，顯示餘額（每次收到新餘額就更新畫面上的文字）。
// 使用者前往 B 頁，A 頁進入 back stack：
//        Fragment 物件還活著（lifecycle 停在 CREATED），
// 但它的畫面已經銷毀（onDestroyView）。再返回 A 頁時，會建立一個全新的畫面。
//        演練用兩個 LifecycleRegistry 模擬這兩套 lifecycle：Fragment 本身，
// 和它的畫面（viewLifecycleOwner）。
//        要在手機上執行（lifecycleScope 需要 Android 的主執行緒）。
// Bug 版：在 onViewCreated 用 Fragment 自己的 lifecycleScope.launch 收集餘額。
// Fragment 還活著，這個協程就不會停：
//        畫面銷毀後還在更新不存在的畫面；返回 A 頁時又開了一個新的收集，
// 同一筆餘額被處理兩次。
// 修正版：改用 viewLifecycleOwner.lifecycleScope，並用
// repeatOnLifecycle(STARTED) 包住收集：畫面銷毀（或進背景）就取消，
// 回來再重新收集。
//        （Compose 裡對應的是 collectAsStateWithLifecycle()）
package tw.harry.labs.solution.platform01lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 假的 Fragment（兩個版本共用）：Fragment 本身和它的畫面（view）各有一套
 * lifecycle
 */
private class L05FakeFragment(
    private val onViewCreated: (L05FakeFragment) -> Unit,
) : LifecycleOwner {
    /** 畫面（view）的 lifecycle：每次 onCreateView 都是新的一套 */
    private class ViewOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** 畫面上的文字（binding）：onDestroyView 之後不存在 */
    class Binding

    private val registry = LifecycleRegistry.createUnsafe(this)
    private var viewOwner: ViewOwner? = null
    override val lifecycle: Lifecycle get() = registry
    val viewLifecycleOwner: LifecycleOwner get() = viewOwner
        ?: error("onCreateView 之前、onDestroyView 之後，沒有畫面")
    var binding: Binding? = null
        private set

    private fun createView() {
        binding = Binding()
        viewOwner =
            ViewOwner().also {
                it.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
        onViewCreated(this)
    }

    /** 第一次顯示：onCreate → onCreateView → onStart */
    fun show() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createView()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        viewOwner!!.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    /**
     * 前往 B 頁，A 頁進 back stack：onStop → onDestroyView（Fragment
     * 本身還活著）
     */
    fun pushToBackStack() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        viewOwner!!.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        viewOwner!!.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        binding = null
        viewOwner = null
    }

    /** 從 B 頁返回：重新 onCreateView（全新的畫面）→ onStart */
    fun returnFromBackStack() {
        createView()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        viewOwner!!.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }
}

/** 演練流程（兩個版本共用）：[collectIn] 決定在哪個 lifecycle 上、怎麼收集 */
private suspend fun scenario(
    log: LabLog,
    collectIn: (
        L05FakeFragment,
        MutableSharedFlow<Int>,
        onBalance: (Int) -> Unit,
    ) -> Unit,
) {
    val balance = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    // 收到餘額時，畫面已經不存在
    var onDeadView = 0
    // 收到餘額時，畫面還在（更新成功）
    var onLiveView = 0
    lateinit var fragment: L05FakeFragment
    val onBalance: (Int) -> Unit = {
        if (fragment.binding == null) onDeadView++ else onLiveView++
    }
    fragment = L05FakeFragment { collectIn(it, balance, onBalance) }

    fragment.show()
    delay(100)
    balance.tryEmit(1)
    delay(100)
    fragment.pushToBackStack() // 前往 B 頁
    delay(50)
    repeat(3) { balance.tryEmit(2 + it); delay(50) }
    log("畫面銷毀之後，又收到 $onDeadView 筆餘額，更新到不存在的畫面")

    // 返回 A 頁：全新的畫面
    fragment.returnFromBackStack()
    delay(100)
    val before = onLiveView
    balance.tryEmit(100)
    delay(100)
    log("返回 A 頁之後，一筆新餘額被畫面處理了 ${onLiveView - before} 次")
}

// MARK: - Bug 版
private object L05Bug {
    suspend fun run(log: LabLog) =
        scenario(log) { fragment, balance, onBalance ->
            // Fragment 本身的 lifecycleScope：畫面銷毀了它還活著
            fragment.lifecycleScope.launch {
                balance.collect { onBalance(it) }
            }
        }
}

// MARK: - 修正版
private object L05Fixed {
    suspend fun run(log: LabLog) =
        scenario(log) { fragment, balance, onBalance ->
            // 畫面的 lifecycle：onDestroyView 就結束
            val owner = fragment.viewLifecycleOwner
            owner.lifecycleScope.launch {
                // 離開 STARTED 就取消，回來再重新收集
                owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    balance.collect { onBalance(it) }
                }
            }
        }
}

// MARK: - 演練設定
private fun count(lines: List<String>, prefix: String, suffix: String): Int? =
    lines.firstOrNull {
        it.startsWith(prefix)
    }?.removePrefix(prefix)?.substringBefore(suffix)?.trim()?.toIntOrNull()

val l05 = Lab(
    id = "L05",
    title = "離開 A 頁之後，餘額還在更新不存在的畫面",
    question = "第 5 題：Fragment 為什麼有兩套生命週期？Flow 要怎麼跟著生命週期收集？",
    goal = "Fragment 進 back stack 時，畫面已經銷毀。收集餘額要綁在畫面的 lifecycle（viewLifecycleOwner）上，畫面不在就停，返回時只收集一次。",
    steps = listOf(
        "選「Bug 版」按執行，看「畫面銷毀之後，又收到幾筆餘額」和「返回 A 頁之後，一筆新餘額被處理了幾次」。",
        "想一想：真的 App 裡，更新不存在的 binding 會發生什麼事？（binding 是 null，NullPointerException 讓 App 當掉）",
        "選「修正版」執行，確認畫面銷毀後是 0 筆、返回後是 1 次。",
    ),
    expectBug = "畫面銷毀之後，又收到 3 筆餘額（更新到不存在的畫面）；返回 A 頁之後一筆餘額被處理 2 次（舊的收集沒停，又開了新的）。",
    expectFixed = "畫面銷毀之後，又收到 0 筆餘額；返回 A 頁之後一筆餘額被處理 1 次。",
    file = "platform01-lifecycle/L05_ViewLifecycle.kt",
    needsDevice = true,
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val dead = count(lines, "畫面銷毀之後，又收到 ", " 筆")
        val again = count(lines, "返回 A 頁之後，一筆新餘額被畫面處理了 ", " 次")
        when {
            dead == null || again == null -> "演練沒有跑完：要在 onViewCreated 裡收集 balance，每收到一筆呼叫 onBalance"
            dead == 0 -> "Bug 還沒重現：Bug 版要用 Fragment 自己的 lifecycleScope.launch { balance.collect { … } }，畫面銷毀後協程還在"
            else -> null
        }
    },
    checkFixed = { lines ->
        val dead = count(lines, "畫面銷毀之後，又收到 ", " 筆")
        val again = count(lines, "返回 A 頁之後，一筆新餘額被畫面處理了 ", " 次")
        when {
            dead == null || again == null -> "演練沒有跑完：要在 onViewCreated 裡收集 balance，每收到一筆呼叫 onBalance"
            dead != 0 -> "畫面銷毀之後還收到 $dead 筆：改用 viewLifecycleOwner.lifecycleScope，並用 repeatOnLifecycle(Lifecycle.State.STARTED) 包住收集"
            again != 1 -> "返回之後一筆餘額被處理 $again 次：收集要綁在畫面的 lifecycle，畫面銷毀就取消"
            else -> null
        }
    },
)
