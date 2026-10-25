// L01：每回到前景一次，同一筆報價就多處理一次（onStart 會重複執行）
// 對應複習站：Platform01Lifecycle 第 1 題「畫面從打開到關掉，iOS 和 Android
// 各會經過哪些階段？」
//
// 情境：行情頁（Activity）一變成可見就訂閱價格。使用者按 Home 回桌面、
// 再從最近工作清單回來，來回三次，最後來一筆新報價。
//        演練用 Jetpack 的 LifecycleRegistry 依序送出 onCreate、onStart、
// onResume、onPause、onStop 這些事件（和真的 Activity 一樣的順序）。
// Bug 版：在 onStart 訂閱，卻沒有取消。onCreate 只會執行一次，但 onStart
// 每次「回到可見」都會執行，每回來一次就多一個訂閱，一筆報價被處理好幾次。
// 修正版：onStart 訂閱，onStop 取消，兩個成對出現；來回幾次都只有一個訂閱。
package tw.harry.labs.solution.platform01lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的行情來源（兩個版本共用）：可以有很多個訂閱者 */
private class L01PriceFeed {
    private val listeners = mutableListOf<(Int) -> Unit>()
    val listenerCount: Int get() = listeners.size
    fun add(listener: (Int) -> Unit) { listeners += listener }
    fun remove(listener: (Int) -> Unit) { listeners -= listener }
    fun publish(price: Int) = listeners.toList().forEach { it(price) }
}

/** 假的 Activity（兩個版本共用）：自己控制 lifecycle 事件的順序 */
private class L01FakeActivity : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
    fun move(vararg events: Lifecycle.Event) =
        events.forEach { registry.handleLifecycleEvent(it) }
}

/**
 * 演練流程（兩個版本共用）：打開行情頁，按 Home 再回來，來回三次，
 * 最後來一筆報價
 */
private fun scenario(
    log: LabLog,
    makeScreen: (L01PriceFeed, () -> Unit) -> DefaultLifecycleObserver,
) {
    val feed = L01PriceFeed()
    var handled = 0
    val activity = L01FakeActivity()
    activity.lifecycle.addObserver(makeScreen(feed) { handled++ })
    // 打開行情頁
    activity.move(
        Lifecycle.Event.ON_CREATE,
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME,
    )
    repeat(3) {
        // 按 Home 回桌面
        activity.move(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)
        // 從最近工作清單回來
        activity.move(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
    }
    feed.publish(10_000_000)
    log("來回 3 次後，行情來源有 ${feed.listenerCount} 個訂閱者")
    log("來回 3 次後，來一筆新報價，行情頁處理了 $handled 次")
}

// MARK: - Bug 版
private object L01Bug {
    class MarketScreen(
        private val feed: L01PriceFeed,
        private val onPrice: () -> Unit,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // 每次回到可見都訂閱一次，從來沒有取消
            feed.add { onPrice() }
        }
    }

    fun run(log: LabLog) = scenario(log) { feed, onPrice ->
        MarketScreen(feed, onPrice)
    }
}

// MARK: - 修正版
private object L01Fixed {
    class MarketScreen(private val feed: L01PriceFeed, onPrice: () -> Unit) :
        DefaultLifecycleObserver {
        private val listener: (Int) -> Unit = { onPrice() }

        override fun onStart(owner: LifecycleOwner) {
            feed.add(listener)
        }

        override fun onStop(owner: LifecycleOwner) {
            // 不可見就取消，和 onStart 成對
            feed.remove(listener)
        }
    }

    fun run(log: LabLog) = scenario(log) { feed, onPrice ->
        MarketScreen(feed, onPrice)
    }
}

// MARK: - 演練設定
private fun handled(lines: List<String>): Int? =
    lines.firstOrNull { it.startsWith("來回 3 次後，來一筆新報價，行情頁處理了 ") }
        ?.removePrefix(
            "來回 3 次後，來一筆新報價，行情頁處理了 ",
        )?.removeSuffix(" 次")?.toIntOrNull()

val l01 = Lab(
    id = "L01",
    title = "每回到前景一次，報價就多處理一次",
    question = "第 1 題：畫面從打開到關掉，iOS 和 Android 各會經過哪些階段？",
    goal = "行情頁一變成可見就訂閱價格，按 Home 來回三次之後，一筆新報價只該被處理一次。",
    steps = listOf(
        "選「Bug 版」按執行，看「行情來源有幾個訂閱者」和「行情頁處理了幾次」。",
        "對照 onCreate 和 onStart：哪一個每次回到畫面都會執行？",
        "選「修正版」執行，確認訂閱者只有 1 個、報價只處理 1 次。",
    ),
    expectBug = "來回 3 次後有 4 個訂閱者（第一次打開加上三次回來），一筆報價被處理 4 次。",
    expectFixed = "來回 3 次後只有 1 個訂閱者，一筆報價處理 1 次。",
    file = "platform01-lifecycle/L01_ObserverRemoval.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when (val n = handled(lines)) {
            null -> "演練沒有跑完"
            0 -> "行情頁沒有收到報價：要在 onStart 裡訂閱"
            1 -> "Bug 還沒重現：訂閱要放在 onStart，而且離開時不取消"
            else -> if (n > 1) null else "演練沒有跑完"
        }
    },
    checkFixed = { lines ->
        when (val n = handled(lines)) {
            null -> "演練沒有跑完"
            0 -> "行情頁沒有收到報價：要在 onStart 裡訂閱"
            1 -> null
            else -> "一筆報價被處理了 $n 次：每次回到畫面都訂閱，卻沒有取消。要在 onStop 取消上一次的訂閱"
        }
    },
)
