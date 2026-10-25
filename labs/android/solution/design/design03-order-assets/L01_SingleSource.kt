// L01：下單之後，首頁、資產頁、下單頁顯示三個不同的可用餘額（每個畫面各存一份資料）
// 對應複習站：Design03OrderAssets 第 3 題「整體架構怎麼安排，才能讓每個畫面的數字一致？」
//
// 情境：App 有三個分頁：首頁、資產頁、下單頁，打開 App 時都已經建立好，之後一直留在記憶體裡，每一頁都會顯示「可用餘額」。
//       伺服器上日圓可用 1,000,000、凍結 0。使用者在下單頁送出一張 300,000 円的限價買單：
//       先是下單的 REST 回覆回來（只有下單頁這一頁知道），接著私有 WebSocket 推送資產變動（可用 700,000、凍結 300,000）。
//       看三個分頁各自顯示的可用餘額。
// Bug 版：每個畫面建立時各打一次 `GET /balance`，自己存一份。下單頁收到 REST 回覆後再打一次，更新自己那一份；
//        WebSocket 的推送沒有人處理。首頁和資產頁停在 1,000,000，下單頁是 700,000，三個畫面兩種數字。
// 修正版：資產只有一個 BalanceStore，資料只來自伺服器（第一次的查詢和之後的 WebSocket 推送）；三個畫面都只讀 Store。
//        推送一來，Store 換成新的，三個畫面一起變。
package tw.harry.labs.solution.design03orderassets

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private data class L01Balance(val available: Int, val frozen: Int)

/** 假的伺服器（兩個版本共用）：日圓可用 1,000,000、凍結 0 */
private class L01FakeServer {
    var balance = L01Balance(available = 1_000_000, frozen = 0)
        private set

    fun fetchBalance(): L01Balance = balance

    fun placeLimitBuy(yen: Int) {
        balance = L01Balance(balance.available - yen, balance.frozen + yen)
    }
}

private interface L01BalanceApp {
    /** 畫面建立（打開 App 時三個分頁都建立好了） */
    fun openScreen(name: String)

    /** 下單的 REST 回覆回來了（只有下單頁知道；回覆裡只有訂單編號，沒有餘額） */
    fun orderAccepted()

    /** 私有 WebSocket 推送資產變動 */
    fun balancePushed(balance: L01Balance)

    /** 某個畫面現在顯示的可用餘額 */
    fun shownAvailable(screen: String): Int
}

private val l01Screens = listOf("首頁", "資產頁", "下單頁")

/** 演練流程（兩個版本共用） */
private fun scenario(log: LabLog, makeApp: (L01FakeServer) -> L01BalanceApp) {
    val server = L01FakeServer()
    val app = makeApp(server)
    fun summary() = l01Screens.joinToString("／") { "$it ${d03Yen(app.shownAvailable(it))}" }

    l01Screens.forEach(app::openScreen)
    log("三個分頁都建立了，可用餘額：${summary()}")
    server.placeLimitBuy(300_000)
    log("使用者在下單頁送出限價買單 300,000 円：伺服器凍結了 300,000，可用 ${d03Yen(server.balance.available)}、凍結 ${d03Yen(server.balance.frozen)}")
    app.orderAccepted()
    log("下單的 REST 回覆回來（只有下單頁知道）：${summary()}")
    app.balancePushed(server.balance)
    log("私有 WebSocket 推送資產變動：${summary()}")
    val distinct = l01Screens.map(app::shownAvailable).toSet().size
    log("最後：${summary()}（伺服器 ${d03Yen(server.balance.available)}），$distinct 種不同的數字")
}

// MARK: - Bug 版
private object L01Bug {
    class App(private val server: L01FakeServer) : L01BalanceApp {
        private val copies = mutableMapOf<String, Int>()                // 每個畫面自己存一份可用餘額

        override fun openScreen(name: String) {
            copies[name] = server.fetchBalance().available               // 畫面建立時各打一次 API，自己存
        }

        override fun orderAccepted() {
            copies["下單頁"] = server.fetchBalance().available           // 只有下單頁知道下單了，只更新自己那一份
        }

        override fun balancePushed(balance: L01Balance) {}              // 推送沒有人處理

        override fun shownAvailable(screen: String): Int = copies[screen] ?: 0
    }

    suspend fun run(log: LabLog) = scenario(log) { App(it) }
}

// MARK: - 修正版
private object L01Fixed {
    class App(private val server: L01FakeServer) : L01BalanceApp {
        private var store: L01Balance? = null                           // 資產只有一份：BalanceStore

        override fun openScreen(name: String) {
            if (store == null) store = server.fetchBalance()             // Store 還沒有資料才向伺服器要
        }

        override fun orderAccepted() {}                                 // 回覆裡沒有餘額：不自己算，等伺服器推送

        override fun balancePushed(balance: L01Balance) {
            store = balance                                             // 以伺服器推送的為準，換掉 Store
        }

        override fun shownAvailable(screen: String): Int = store?.available ?: 0   // 三個畫面都讀同一個 Store
    }

    suspend fun run(log: LabLog) = scenario(log) { App(it) }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "下單之後，三個分頁的可用餘額不一樣",
    question = "第 3 題：整體架構怎麼安排，才能讓每個畫面的數字一致？",
    goal = "資產只有一個 BalanceStore，資料只來自伺服器（REST 回覆與 WebSocket 推送）；所有畫面都只讀 Store，不各自打 API、各存一份。這樣推送一來，每個畫面同時換成新的數字。",
    steps = listOf(
        "選「Bug 版」執行，看三個分頁的可用餘額在每一步之後各是多少。",
        "想一想：下單的 REST 回覆只有下單頁知道，WebSocket 推送又沒人處理。首頁與資產頁什麼時候才會更新？（等使用者重新打開那一頁，或下一次輪詢。）",
        "選「修正版」執行：推送一來，三個分頁一起變成 700,000，只有 1 種數字。",
        "想一想：下單的 REST 回覆裡只有訂單編號，沒有餘額。為什麼修正版在 orderAccepted 裡不自己用「餘額 − 下單金額」算？（見第 6 題，L03 會練。）",
    ),
    expectBug = "首頁和資產頁一直停在 1,000,000，下單頁是 700,000：「最後：首頁 1,000,000／資產頁 1,000,000／下單頁 700,000（伺服器 700,000），2 種不同的數字」。",
    expectFixed = "推送一來三個分頁一起變：「最後：首頁 700,000／資產頁 700,000／下單頁 700,000（伺服器 700,000），1 種不同的數字」。",
    file = "design03-order-assets/L01_SingleSource.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeApp) 執行"
            last.startsWith("最後：首頁 0／資產頁 0／下單頁 0") -> "openScreen 還沒寫：每個畫面建立時，自己向伺服器要一份餘額（server.fetchBalance().available）存進 copies"
            last != "最後：首頁 1,000,000／資產頁 1,000,000／下單頁 700,000（伺服器 700,000），2 種不同的數字" ->
                "Bug 還沒重現：每個畫面各存一份；orderAccepted 只重新抓下單頁那一份；balancePushed 不處理，首頁與資產頁就會停在舊數字"
            else -> null
        }
    },
    checkFixed = { lines ->
        val last = lines.lastOrNull { it.startsWith("最後") }
        when {
            last == null -> "演練沒有跑完：要用 scenario(log, makeApp) 執行"
            last.startsWith("最後：首頁 0／資產頁 0／下單頁 0") -> "Store 還沒有資料：openScreen 在 store 是 null 時向伺服器要一份存起來"
            last != "最後：首頁 700,000／資產頁 700,000／下單頁 700,000（伺服器 700,000），1 種不同的數字" ->
                "只留一份 store：openScreen 第一次向伺服器要，balancePushed 換成推送來的資料，三個畫面都讀 store.available"
            else -> null
        }
    },
)
