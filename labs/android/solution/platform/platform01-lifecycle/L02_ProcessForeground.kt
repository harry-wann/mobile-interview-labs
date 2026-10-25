// L02：從行情頁打開下單頁，行情就斷線了（Activity 的 onStop 不等於 App 進背景）
// 對應複習站：Platform01Lifecycle 第 2 題「App 切到背景、回到前景，
// 兩個平台怎麼知道？」
//
// 情境：App 有行情頁、下單頁兩個 Activity。行情連線要在「整個 App 進背景」
// 時才斷開。使用者做三件事：
//        從行情頁點進下單頁、返回、按 Home 回桌面再回來。
//        演練用 LifecycleRegistry 依序送出兩個 Activity 的事件。Activity
// 跳轉時，新畫面會先 onStart，舊畫面才 onStop，
//        所以正式的 App 用 ProcessLifecycleOwner 看「整個 App」：只有最後一個
// Activity 也 onStop，它才會 ON_STOP。
//        （這裡的 L02FakeProcess 用同樣的規則算出整個 App 的狀態，原因是
// ProcessLifecycleOwner 需要真的 Application。）
// Bug 版：把行情連線的 observer 掛在行情頁自己的 lifecycle 上，onStop 就斷線。
// 打開下單頁時行情頁會 onStop，行情就斷了。
// 修正版：observer 掛在整個 App 的 lifecycle 上（正式 App：
// ProcessLifecycleOwner.get().lifecycle），只有整個 App 進背景才斷線。
package tw.harry.labs.solution.platform01lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 行情連線（兩個版本共用） */
private class L02MarketConnection {
    var connected = false
        private set
    var disconnects = 0
        private set

    fun connect() { connected = true }

    fun disconnect() {
        if (connected) disconnects++
        connected = false
    }
}

/** 行情連線的 observer（兩個版本共用）：畫面變成可見就連線，不可見就斷線 */
private class L02MarketObserver(private val connection: L02MarketConnection) :
    DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) = connection.connect()
    override fun onStop(owner: LifecycleOwner) = connection.disconnect()
}

/** 假的 Activity */
private class L02FakeActivity : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
    fun move(vararg events: Lifecycle.Event) =
        events.forEach { registry.handleLifecycleEvent(it) }
}

/** 模擬 ProcessLifecycleOwner：所有 Activity 都不可見，整個 App 才算進背景 */
private class L02FakeProcess : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    private var started = 0
    override val lifecycle: Lifecycle get() = registry

    fun watch(activity: L02FakeActivity) {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        if (++started ==
                            1
                        ) {
                            registry.handleLifecycleEvent(
                                Lifecycle.Event.ON_START,
                            )
                        }
                    }

                    Lifecycle.Event.ON_STOP -> {
                        if (--started ==
                            0
                        ) {
                            registry.handleLifecycleEvent(
                                Lifecycle.Event.ON_STOP,
                            )
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            },
        )
    }
}

/** 演練流程（兩個版本共用）：[attach] 決定 observer 掛在哪個 lifecycle 上 */
private fun scenario(
    log: LabLog,
    attach: (
        market: L02FakeActivity,
        process: L02FakeProcess,
        observer: L02MarketObserver,
    ) -> Unit,
) {
    val connection = L02MarketConnection()
    val market = L02FakeActivity()
    val order = L02FakeActivity()
    val process = L02FakeProcess()
    process.watch(market)
    process.watch(order)
    attach(market, process, L02MarketObserver(connection))

    // 打開行情頁
    market.move(
        Lifecycle.Event.ON_CREATE,
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME,
    )

    // 點進下單頁
    market.move(Lifecycle.Event.ON_PAUSE)
    order.move(
        Lifecycle.Event.ON_CREATE,
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME,
    )
    market.move(Lifecycle.Event.ON_STOP)
    // 按返回，回到行情頁
    order.move(Lifecycle.Event.ON_PAUSE)
    market.move(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
    order.move(Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)
    log("行情頁→下單頁→返回：行情斷線 ${connection.disconnects} 次")

    // 按 Home 回桌面
    market.move(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP)
    log("按 Home 之後：行情連線${if (connection.connected) "還連著" else "已斷開"}")
    // 從最近工作清單回來
    market.move(Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME)
    log("回到前景之後：行情連線${if (connection.connected) "還連著" else "已斷開"}")
}

// MARK: - Bug 版
private object L02Bug {
    fun run(log: LabLog) = scenario(log) { market, _, observer ->
        // 掛在行情頁自己的 lifecycle：打開下單頁時它會 onStop
        market.lifecycle.addObserver(observer)
    }
}

// MARK: - 修正版
private object L02Fixed {
    fun run(log: LabLog) = scenario(log) { _, process, observer ->
        // 掛在整個 App 的 lifecycle（正式 App：ProcessLifecycleOwner.get().
        // lifecycle）
        process.lifecycle.addObserver(observer)
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "打開下單頁，行情就斷線了",
    question = "第 2 題：App 切到背景、回到前景，兩個平台怎麼知道？",
    goal = "只有整個 App 進背景才斷開行情；在 App 裡從一個 Activity 跳到另一個，行情要維持連線。",
    steps = listOf(
        "選「Bug 版」按執行，看「行情頁→下單頁→返回」行情斷線了幾次。",
        "想一想：點進下單頁的時候，行情頁和下單頁的事件誰先誰後？（下單頁先 onStart，行情頁才 onStop）",
        "選「修正版」執行：跳轉時不斷線，按 Home 才斷線，回來重連。",
    ),
    expectBug = "行情頁→下單頁→返回：行情斷線 1 次（跳轉時行情頁 onStop 就斷了）；按 Home 之後已斷開。",
    expectFixed = "行情頁→下單頁→返回：行情斷線 0 次；按 Home 之後已斷開；回到前景之後還連著。",
    file = "platform01-lifecycle/L02_ProcessForeground.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("行情頁→下單頁→返回：") } -> "演練沒有跑完"

            lines.contains(
                "行情頁→下單頁→返回：行情斷線 0 次",
            ) -> "Bug 還沒重現：observer 要掛在行情頁自己的 lifecycle，onStop 就斷線"

            !lines.contains("按 Home 之後：行情連線已斷開") -> "按 Home 之後行情連線要斷開"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("行情頁→下單頁→返回：") } -> "演練沒有跑完"

            !lines.contains(
                "行情頁→下單頁→返回：行情斷線 0 次",
            ) -> "在 App 裡跳轉頁面時行情不該斷線：observer 要掛在整個 App 的 lifecycle（process.lifecycle）"

            !lines.contains(
                "按 Home 之後：行情連線已斷開",
            ) -> "整個 App 進背景（按 Home）之後，行情連線要斷開"

            !lines.contains("回到前景之後：行情連線還連著") -> "回到前景要重新連線"

            else -> null
        }
    },
)
