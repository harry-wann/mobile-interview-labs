// L02：每次打開 App 都跳到同一個活動頁（延遲的深層連結只能用一次）
// 對應複習站：Platform06DeepLink 第 4 題「使用者還沒安裝 App 時，
// 連結要怎麼處理？（deferred deep link）」
//
// 情境：行銷活動的商店連結帶著 referrer 參數，使用者點了去 Google Play 安裝，
// 第一次打開 App 時，要用 Play Install Referrer API
//        讀回來，直接到活動指定的行情頁（deferred deep link）。這個 API
// 每次詢問都回傳同一份 referrer，
//        因為它只知道安裝時帶了什麼，不知道 App 有沒有已經用掉。（iOS
// 的歸因工具也一樣。）
//        演練模擬使用者連續冷啟動 3 次；App 用 SharedPreferences（演練裡用
// LaunchStore 代表，關掉 App 再開也還在）記錄狀態。
// Bug 版：每次冷啟動都讀 referrer，有答案就跳過去。3 次啟動都被帶到活動頁。
// 修正版：第一次處理完就在 LaunchStore 記下「已經處理過」，之後的啟動不再讀取、
// 也不再跳轉。
package tw.harry.labs.solution.platform06deeplink

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/**
 * 代表 Play Install Referrer API（兩個版本共用）：
 * 每次詢問都回傳同一個安裝前點過的連結
 */
private class InstallReferrer {
    fun deferredLink(): String? =
        "https://www.example.com/ticker/btcjpy?campaign=autumn"
}

/** 代表 SharedPreferences：App 關掉再開，內容還在（兩個版本共用） */
private class LaunchStore {
    var deferredLinkHandled = false
}

/**
 * 演練流程（兩個版本共用）：連續冷啟動 3 次，每次建立一個新的 handler（App
 * 重新啟動，記憶體裡的東西都沒了），store 是同一個
 */
private fun scenario(
    log: LabLog,
    makeHandler: (
        LaunchStore,
        InstallReferrer,
        (String) -> Unit,
    ) -> (() -> Unit),
) {
    val store = LaunchStore()
    var jumps = 0
    for (launch in 1..3) {
        var jumped: String? = null
        val onLaunch = makeHandler(store, InstallReferrer()) { jumped = it }
        onLaunch()
        val link = jumped
        if (link != null) {
            jumps++
            log("第 $launch 次啟動：跳到 $link")
        } else {
            log("第 $launch 次啟動：照常顯示首頁")
        }
    }
    log("3 次冷啟動，被活動連結帶走 $jumps 次")
}

// MARK: - Bug 版
private object L02Bug {
    fun makeHandler(
        store: LaunchStore,
        referrer: InstallReferrer,
        navigate: (String) -> Unit,
    ): () -> Unit = {
        // 每次啟動都讀，有答案就跳
        referrer.deferredLink()?.let(navigate)
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 修正版
private object L02Fixed {
    fun makeHandler(
        store: LaunchStore,
        referrer: InstallReferrer,
        navigate: (String) -> Unit,
    ): () -> Unit = {
        // 已經處理過了：不再讀取、不再跳轉
        if (!store.deferredLinkHandled) {
            referrer.deferredLink()?.let { link ->
                // 先記下來，再跳轉
                store.deferredLinkHandled = true
                navigate(link)
            }
        }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeHandler)
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "每次打開 App 都跳到同一個活動頁",
    question = "第 4 題：使用者還沒安裝 App 時，連結要怎麼處理？（deferred deep link）",
    goal = "安裝前點過的連結，只有第一次打開 App 時要帶使用者過去；之後再怎麼冷啟動，都照常顯示首頁。",
    steps = listOf(
        "選「Bug 版」執行，看 3 次冷啟動各自到哪裡。",
        "想一想：Install Referrer API 每次被問，答案會變嗎？App 要自己記住什麼？",
        "選「修正版」執行，只有第 1 次啟動會跳到活動頁。",
        "進階：這個連結是行銷同事設定的、來自外部，跳轉前也要交給 Router 做白名單與參數驗證（見演練 3）。",
    ),
    expectBug = "「第 1、2、3 次啟動：跳到 https://www.example.com/ticker/btcjpy?campaign=autumn」，「3 次冷啟動，被活動連結帶走 3 次」。",
    expectFixed = "第 1 次啟動跳到活動頁，第 2、3 次「照常顯示首頁」，「3 次冷啟動，被活動連結帶走 1 次」。",
    file = "platform06-deeplink/L02_DeferredOnce.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        if ("3 次冷啟動，被活動連結帶走 3 次" in lines) {
            null
        } else {
            "Bug 還沒重現：每次啟動都讀 referrer.deferredLink()，有答案就 navigate，不要記錄是否處理過"
        }
    },
    checkFixed = { lines ->
        when {
            "第 1 次啟動：跳到 https://www.example.com/ticker/btcjpy?campaign=autumn" !in
                lines -> {
                "第 1 次啟動要跳到活動頁：沒處理過時，讀 referrer.deferredLink() 並 navigate"
            }

            "3 次冷啟動，被活動連結帶走 1 次" !in lines -> {
                "只有第 1 次啟動要跳轉：navigate 之前先把 store.deferredLinkHandled 設成 true，之後的啟動一開始就不處理"
            }

            else -> {
                null
            }
        }
    },
)
