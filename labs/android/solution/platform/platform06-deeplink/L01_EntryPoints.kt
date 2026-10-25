// L01：App 已經開著時，點推播裡的連結卻沒反應（onNewIntent 漏接）
// 對應複習站：Platform06DeepLink 第 3 題「App 怎麼接到連結？App
// 沒開著（冷啟動）和已經開著有什麼不同？」
//
// 情境：MainActivity 的 launchMode 是 singleTop：App 已經開著、而且
// MainActivity 在最上層時，新的連結不會重新建立 Activity，
//        而是呼叫現有 Activity 的 onNewIntent。連結進來有兩個入口，
// 不管從哪個入口進來，都要交給同一個 Router：
//        1. App 沒開著，被連結啟動（冷啟動）：連結在 onCreate 收到的 intent.
// data。
//        2. App 已經開著：連結在 onNewIntent 收到的 intent.data。
//        演練用簡化的假 Activity 模擬這兩個入口（不依賴真的 Android 系統），
// Router 只記下「收到什麼連結」。
//        劇本：App 被 App Link 冷啟動，接著開著的時候又收到兩個連結，共 3 個。
// Bug 版：只在 onCreate 處理連結，沒有覆寫 onNewIntent。App
// 開著時點的連結全部被忽略。
// 修正版：覆寫 onNewIntent：先 setIntent(intent)，讓之後取得的 intent
// 是新的這一個，再交給同一個 Router。
package tw.harry.labs.solution.platform06deeplink

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 簡化的 Intent：data 就是連結（真實的是 intent.data，一個 Uri） */
private class FakeIntent(val data: String?)

/**
 * 簡化的 Activity（兩個版本共用）：沒有覆寫 onNewIntent 的話，什麼事都不會發生
 */
private open class FakeActivity {
    private var current: FakeIntent? = null
    open fun onCreate(intent: FakeIntent) {}
    open fun onNewIntent(intent: FakeIntent) {}
    fun getIntent(): FakeIntent? = current
    fun setIntent(intent: FakeIntent) { current = intent }
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    makeActivity: (LinkCollector) -> FakeActivity,
) {
    val collector = LinkCollector()
    val activity = makeActivity(collector)

    val cold = FakeIntent("https://www.example.com/ticker/btcjpy")
    activity.setIntent(cold)
    // 冷啟動：被 App Link 啟動
    activity.onCreate(cold)
    // App 已經開著：第二個連結
    activity.onNewIntent(FakeIntent("https://www.example.com/ticker/ethjpy"))
    // App 已經開著：第三個連結
    activity.onNewIntent(FakeIntent("https://www.example.com/alert/ab12cd34"))

    val received = collector.urls
    log("Router 收到 ${received.size} 個連結，漏接 ${3 - received.size} 個（共 3 個）")
    received.forEach { log("收到：$it") }
}

// MARK: - Bug 版
private object L01Bug {
    class MainActivity(private val router: LinkCollector) : FakeActivity() {
        override fun onCreate(intent: FakeIntent) {
            // 冷啟動：交給 Router
            intent.data?.let(router::handle)
        }
        // 沒有覆寫 onNewIntent：App 開著時收到的連結被忽略
    }

    suspend fun run(log: LabLog) = scenario(log) { MainActivity(it) }
}

// MARK: - 修正版
private object L01Fixed {
    class MainActivity(private val router: LinkCollector) : FakeActivity() {
        override fun onCreate(intent: FakeIntent) {
            intent.data?.let(router::handle) // 冷啟動
        }

        override fun onNewIntent(intent: FakeIntent) {
            // 讓之後取得的 intent 是新的這一個
            setIntent(intent)
            // App 已經開著：同一個 Router
            intent.data?.let(router::handle)
        }
    }

    suspend fun run(log: LabLog) = scenario(log) { MainActivity(it) }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "App 開著時點推播的連結，沒有反應",
    question = "第 3 題：App 怎麼接到連結？App 沒開著（冷啟動）和已經開著有什麼不同？",
    goal = "每個入口收到的連結，都要交給同一個 Router，一個都不能漏。App 已經開著時，新的連結從哪裡進來？",
    steps = listOf(
        "選「Bug 版」執行，看 Router 收到幾個連結、漏接哪些。",
        "想一想：MainActivity 是 singleTop，App 開著時再點連結，系統會呼叫哪個函式？Bug 版有沒有處理？",
        "選「修正版」執行，3 個連結都要收到。",
        "進階：iOS 的對應是 SceneDelegate 的 willConnectTo（冷啟動）與 continue／openURLContexts（已經開著）；這題的 iOS 版漏的是冷啟動的 Universal Link。",
    ),
    expectBug = "「Router 收到 1 個連結，漏接 2 個（共 3 個）」，只收到冷啟動的 https://www.example.com/ticker/btcjpy。",
    expectFixed = "「Router 收到 3 個連結，漏接 0 個（共 3 個）」。",
    file = "platform06-deeplink/L01_EntryPoints.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("Router 收到") } -> {
                "演練沒有跑完：要用 scenario(log) { … } 執行"
            }

            "收到：https://www.example.com/ticker/btcjpy" !in lines -> {
                "Bug 版的 onCreate 還是要處理冷啟動的連結：intent.data 交給 router.handle"
            }

            lines.any {
                it.startsWith("收到：") &&
                    it != "收到：https://www.example.com/ticker/btcjpy"
            } -> {
                "Bug 還沒重現：不要覆寫 onNewIntent，App 開著時收到的連結才會被忽略"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        if ("Router 收到 3 個連結，漏接 0 個（共 3 個）" in lines) {
            null
        } else {
            "3 個連結都要收到：覆寫 onNewIntent，setIntent(intent) 之後把 intent.data 交給 router.handle"
        }
    },
)
