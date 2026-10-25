// L05：下單時走舊流程，取消時遠端開關剛好翻成新流程，結果找不到這筆委託（feature flag 一個 session 只評估一次）
// 對應複習站：Advanced09Architecture 第 6 題「feature flag 在重構時怎麼用？要注意什麼？」
//
// 情境：新的下單流程放在遠端 feature flag 後面（newOrderFlow）。舊流程把委託記在舊的儲存，新流程記在新的儲存，兩邊互不認得。
//       使用者用舊流程下了一筆委託，這時營運把放量比例調高，App 在背景刷新了遠端設定，開關翻成「開」，使用者接著按取消。
//       演練的順序：開關=關 → 下單 → 遠端設定刷新（開關變成開）→ 取消 → 下一個 session（重新啟動 App）→ 下單 → 取消。
//
// 你要寫：
//   ① Bug 版：每個動作要用哪個流程時，才去讀目前的遠端設定。session 中途開關翻了，同一筆委託下單和取消走不同的流程。
//   ② 修正版：session 開始時讀一次，存成快照，整個 session 都用同一個值；新的值等到下一個 session 才生效。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced09-architecture/L05_FlagSnapshot.kt
package tw.harry.labs.practice.advanced09architecture

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的遠端設定：App 會在背景刷新它（兩個版本共用） */
private class L05FakeRemoteConfig {
    @Volatile
    var newOrderFlow = false
}

/** 下單服務：flag 決定走新流程還是舊流程。兩個流程各有自己的儲存（兩個版本共用） */
private class L05OrderService(private val flag: () -> Boolean) {
    private val legacyOrders = mutableSetOf<Int>()
    private val newOrders = mutableSetOf<Int>()

    fun place(id: Int): String = if (flag()) { newOrders += id; "新流程" } else { legacyOrders += id; "舊流程" }

    fun cancel(id: Int): String {
        val removed = if (flag()) newOrders.remove(id) else legacyOrders.remove(id)
        return if (removed) "取消成功" else "找不到委託 #$id"
    }
}

/** 演練流程（兩個版本共用）：makeFlagReader 建立「這個 session 用來讀開關的函式」 */
private fun scenario(log: LabLog, makeFlagReader: (L05FakeRemoteConfig) -> () -> Boolean) {
    val remote = L05FakeRemoteConfig()

    // session 1：開關=關
    val session1 = L05OrderService(makeFlagReader(remote))
    log("下單 #1：走${session1.place(1)}")
    remote.newOrderFlow = true                                  // 背景刷新了遠端設定：開關翻成「開」
    log("遠端設定刷新：newOrderFlow = 開")
    log("取消 #1：${session1.cancel(1)}")

    // session 2：重新啟動 App，開關=開
    val session2 = L05OrderService(makeFlagReader(remote))
    log("（重新啟動 App）下單 #2：走${session2.place(2)}")
    log("取消 #2：${session2.cancel(2)}")
}

// MARK: - Bug 版
private object L05Bug {
    fun makeFlagReader(remote: L05FakeRemoteConfig): () -> Boolean {
        // TODO ①：回傳一個函式，每次被呼叫才去讀目前的 remote.newOrderFlow
        return { false }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeFlagReader)
}

// MARK: - 修正版
private object L05Fixed {
    fun makeFlagReader(remote: L05FakeRemoteConfig): () -> Boolean {
        // TODO ②：session 開始（建立這個函式）時，只讀一次 remote.newOrderFlow 存起來；回傳的函式一直回傳這個值
        return { false }
    }

    suspend fun run(log: LabLog) = scenario(log, ::makeFlagReader)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "下單走舊流程，取消卻走新流程",
    question = "第 6 題：feature flag 在重構時怎麼用？要注意什麼？（同一個 session 內不要中途翻轉）",
    goal = "同一個 session 內，一個 feature flag 要得到同一個值；遠端設定更新後，新的值等到下一個 session 才生效。 先寫出每次都讀最新值的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced09-architecture/L05_FlagSnapshot.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看「取消 #1」的結果：下單時走哪個流程？取消時走哪個？",
        "想一想：遠端設定在 session 中途更新了。使用者操作到一半，功能從舊的突然變成新的，會發生什麼事？",
        "寫 TODO ②，選「修正版」執行：取消 #1 成功；重新啟動 App 之後，#2 走新流程，取消也成功。",
        "進階：flag 取不到值（離線、請求失敗）時用 App 內寫好的預設值，金融功能預設關閉；kill switch 要「立刻生效」，那是另一個情境（Advanced10 的演練）。",
    ),
    expectBug = "「下單 #1：走舊流程」，「取消 #1：找不到委託 #1」：開關翻了，取消走去新流程的儲存找，找不到。",
    expectFixed = "「取消 #1：取消成功」（整個 session 都走舊流程）；重新啟動之後「下單 #2：走新流程」、「取消 #2：取消成功」。",
    file = "advanced09-architecture/L05_FlagSnapshot.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "下單 #1：走舊流程" !in lines -> "演練沒有跑完：下單 #1 開關是關的，應該走舊流程"
            "取消 #1：找不到委託 #1" !in lines -> "Bug 還沒重現：取消時要重新讀目前的遠端設定（已經翻成開），改走新流程就找不到"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "下單 #1：走舊流程" !in lines -> "演練沒有跑完：下單 #1 開關是關的，應該走舊流程"
            "取消 #1：取消成功" !in lines -> "取消 #1 應該成功：整個 session 要用同一個開關的值（session 開始時讀一次）"
            "（重新啟動 App）下單 #2：走新流程" !in lines -> "重新啟動之後，新的 session 應該讀到新的值（開），走新流程"
            "取消 #2：取消成功" !in lines -> "取消 #2 應該成功"
            else -> null
        }
    },
)
