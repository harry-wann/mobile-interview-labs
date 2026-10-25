// L03：自己裝了一個 crash 處理，Crashlytics 就收不到 crash 了（uncaught
// exception handler 要串起來）
// 對應複習站：Advanced06Crash 第 2 題「iOS 和 Android 常見的 crash 有哪幾種？」
// （沒被接住的例外要怎麼回報）
//
// 情境：App 啟動時先初始化 Crashlytics，它用 Thread.
// setDefaultUncaughtExceptionHandler 登記自己。
//       後來有人想「crash 前先補寫一筆本機紀錄」，在 Crashlytics
// 之後又呼叫了一次 setDefaultUncaughtExceptionHandler。
//       演練用「假的 Crashlytics」，並直接呼叫目前登記的 handler
// 來模擬系統在例外沒被接住時做的事（不會真的當掉）。
//
// 你要寫：
//   ① Bug 版：用 setDefaultUncaughtExceptionHandler 登記自己的 handler，
// 只寫自己的紀錄。系統只會呼叫最後登記的那一個，Crashlytics 收不到。
//   ② 修正版：登記前先用 getDefaultUncaughtExceptionHandler() 把前一個存起來，
// 自己的紀錄寫完後，再呼叫前一個 handler。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced06-crash/L03_HandlerChain.kt
package tw.harry.labs.practice.advanced06crash

import java.util.concurrent.CopyOnWriteArrayList
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 假的 Crashlytics：App 啟動時登記自己的 handler，收到的 crash 記在
 * received（兩個版本共用）
 */
private class L03FakeCrashlytics : Thread.UncaughtExceptionHandler {
    val received = CopyOnWriteArrayList<String>()

    fun install() = Thread.setDefaultUncaughtExceptionHandler(this)

    override fun uncaughtException(t: Thread, e: Throwable) {
        received += e.javaClass.simpleName
    }
}

/** 演練流程（兩個版本共用） */
private fun scenario(
    log: LabLog,
    installOwnHandler: (own: MutableList<String>) -> Unit,
) {
    val original = Thread.getDefaultUncaughtExceptionHandler()
    try {
        val crashlytics = L03FakeCrashlytics()
        val own = CopyOnWriteArrayList<String>()
        // App 啟動：Crashlytics 先登記
        crashlytics.install()
        // 後來有人又登記了一個
        installOwnHandler(own)

        val exception = IllegalStateException("下單 API 回傳格式錯誤")
        log("沒被接住的例外：${exception.javaClass.simpleName}")
        // 例外沒被接住時，系統會呼叫「目前登記的」預設 handler
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
            Thread.currentThread(),
            exception,
        )
        log("自己的紀錄收到 ${own.size} 筆")
        log("Crashlytics 收到 ${crashlytics.received.size} 筆")
    } finally {
        // 演練結束還原，不影響 App 本身
        Thread.setDefaultUncaughtExceptionHandler(original)
    }
}

// MARK: - Bug 版
private object L03Bug {
    fun installOwnHandler(own: MutableList<String>) {
        // TODO ①：用 Thread.setDefaultUncaughtExceptionHandler { _, e -> … }
        // 登記自己的 handler，
        //         裡面只做一件事：own += e.javaClass.simpleName
    }

    suspend fun run(log: LabLog) = scenario(log, ::installOwnHandler)
}

// MARK: - 修正版
private object L03Fixed {
    fun installOwnHandler(own: MutableList<String>) {
        // TODO ②-1：登記之前，先用 Thread.getDefaultUncaughtExceptionHandler()
        // 取得前一個 handler，存進區域變數
        // TODO ②-2：再登記自己的 handler：先寫自己的紀錄（和 Bug 版一樣），
        // 最後呼叫 previous?.uncaughtException(t, e)
    }

    suspend fun run(log: LabLog) = scenario(log, ::installOwnHandler)
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "自己裝了 crash 處理，Crashlytics 收不到了",
    question = "第 2 題：iOS 和 Android 常見的 crash 有哪幾種？（沒被接住的例外要怎麼回報）",
    goal = "新增自己的 crash 處理時，不能把已經登記的 Crashlytics 擠掉：兩邊都要收到這次 crash。 先寫出會擠掉 Crashlytics 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced06-crash/L03_HandlerChain.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看「Crashlytics 收到幾筆」。",
        "想一想：setDefaultUncaughtExceptionHandler 只能登記一個 handler，後登記的會怎樣？前一個去哪了？",
        "寫 TODO ②，選「修正版」執行：登記前先用 getDefaultUncaughtExceptionHandler() 存起來，自己做完再呼叫它。",
        "進階：iOS 對應的是 NSSetUncaughtExceptionHandler 與 NSGetUncaughtExceptionHandler。",
    ),
    expectBug = "「自己的紀錄收到 1 筆」，但「Crashlytics 收到 0 筆」：真正的 crash 報告不見了。",
    expectFixed = "「自己的紀錄收到 1 筆」，「Crashlytics 收到 1 筆」。",
    file = "advanced06-crash/L03_HandlerChain.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "自己的紀錄收到 1 筆" !in lines -> "自己的 handler 沒有被呼叫：installOwnHandler 要用 setDefaultUncaughtExceptionHandler 登記，並記錄 e"
            "Crashlytics 收到 0 筆" !in lines -> "Bug 還沒重現：直接登記新的 handler，不存前一個，Crashlytics 就收不到"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "自己的紀錄收到 1 筆" !in lines -> "自己的 handler 沒有被呼叫：installOwnHandler 要登記新的 handler，並記錄 e"
            "Crashlytics 收到 1 筆" !in lines -> "Crashlytics 沒有收到：登記前先用 getDefaultUncaughtExceptionHandler() 存起來，handler 裡最後呼叫它"
            else -> null
        }
    },
)
