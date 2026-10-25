// L01：測下單頁，卻被迫準備資產頁（Feature 之間不能互相依賴）
// 對應複習站：Platform08Modules 第 1 題「為什麼要模組化？模組之間的依賴方向要怎麼規劃？」
//
// 情境：交易所 App 有「下單」和「資產」兩個功能模組。下單成功後要跳到資產頁；資產頁的「入金後去下單」又要跳回下單頁。
//        演練單獨測試下單頁（onOrderPlaced），看資產頁被牽連多少：資產頁的 API 被呼叫幾次、資產頁的物件被建立幾個。
//
// 你要寫：
//   ① Bug 版：OrderViewModel 用 by lazy 直接建立 WalletViewModel(this, walletApi)；onOrderPlaced 呼叫 wallet.open()。
//   ② 修正版：OrderViewModel.onOrderPlaced 呼叫 walletNavigator.openWallet()（只依賴介面）。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform08-modules/L01_FeatureCycle.kt
package tw.harry.labs.practice.platform08modules

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的資產 API（兩個版本共用）：記下被呼叫幾次、資產頁被建立幾個 */
private class WalletApi {
    var calls = 0
    var created = 0
    fun fetchBalance() { calls++ }
}

// MARK: - Bug 版
private object L01Bug {
    /** 下單功能：直接建立、直接持有資產功能的具體類別 */
    class OrderViewModel(private val walletApi: WalletApi) {
        // TODO ①-1：宣告 private val wallet by lazy { WalletViewModel(this, walletApi) }（直接建立另一個功能的具體類別，還把自己傳進去）
        fun onOrderPlaced() {
            // TODO ①-2：下單成功：打開資產頁（wallet.open()）
        }
    }

    /** 資產功能：入金後要回到下單頁，所以直接持有 OrderViewModel */
    class WalletViewModel(val order: OrderViewModel, private val api: WalletApi) {
        init { api.created++ }
        fun open() = api.fetchBalance()                                   // 打開資產頁：載入餘額
    }

    suspend fun run(log: LabLog) {
        val api = WalletApi()
        val order = OrderViewModel(api)                      // 單獨測試下單頁，卻要準備資產頁的 API
        order.onOrderPlaced()
        log("單獨測試下單：資產頁的 API 被呼叫 ${api.calls} 次，導覽請求 0 次")
        log("單獨測試下單時，被建立出來的資產頁物件：${api.created} 個")
    }
}

// MARK: - 修正版
private object L01Fixed {
    /** Core 模組定義的介面：Feature 只依賴它們 */
    interface WalletNavigator { fun openWallet() }
    interface OrderNavigator { fun openOrder() }

    class OrderViewModel(private val walletNavigator: WalletNavigator) {
        fun onOrderPlaced() {
            // TODO ②：只請 App 層帶使用者去資產頁：walletNavigator.openWallet()（OrderViewModel 不知道資產頁長什麼樣）
        }
    }

    class WalletViewModel(private val orderNavigator: OrderNavigator, private val api: WalletApi) {
        init { api.created++ }
        fun open() = api.fetchBalance()
        fun onDepositDone() = orderNavigator.openOrder()
    }

    /** 測試用的假導覽：只記下被要求過幾次 */
    class FakeWalletNavigator : WalletNavigator {
        var opens = 0
        override fun openWallet() { opens++ }
    }

    suspend fun run(log: LabLog) {
        val api = WalletApi()
        val navigator = FakeWalletNavigator()
        val order = OrderViewModel(navigator)                // 單獨測試下單頁：只要一個假的導覽
        order.onOrderPlaced()
        log("單獨測試下單：資產頁的 API 被呼叫 ${api.calls} 次，導覽請求 ${navigator.opens} 次")
        log("單獨測試下單時，被建立出來的資產頁物件：${api.created} 個")
    }
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "測下單頁卻被迫準備資產頁",
    question = "第 1 題：為什麼要模組化？模組之間的依賴方向要怎麼規劃？",
    goal = "兩個功能之間不互相依賴：測試下單頁不需要資產頁。跨功能的跳轉要怎麼寫？",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看單獨測試下單時資產頁的 API 被呼叫幾次、資產頁物件被建立幾個，出現 ✓。",
        "想一想：OrderViewModel 為什麼一定要有 walletApi 才能建立？如果這是兩個 Gradle 模組，:feature:order 與 :feature:wallet 會怎樣？",
        "寫 TODO ②，選「修正版」執行，資產頁的 API 0 次、導覽請求 1 次、資產頁物件 0 個。",
        "進階：Hilt 或手動注入，都是在 :app（Composition Root）把 WalletNavigator 的實作交給 OrderViewModel。iOS 的對應演練還能看到互相強參考造成的記憶體洩漏；JVM 的垃圾回收會處理循環參考，所以這裡看不到洩漏。",
    ),
    expectBug = "「單獨測試下單：資產頁的 API 被呼叫 1 次，導覽請求 0 次」，「被建立出來的資產頁物件：1 個」。",
    expectFixed = "「單獨測試下單：資產頁的 API 被呼叫 0 次，導覽請求 1 次」，「被建立出來的資產頁物件：0 個」。",
    file = "platform08-modules/L01_FeatureCycle.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L01Bug.run(log)
            Variant.FIXED -> L01Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("單獨測試下單時") } -> "演練沒有跑完"
            "單獨測試下單：資產頁的 API 被呼叫 1 次，導覽請求 0 次" !in lines ->
                "Bug 版：OrderViewModel 用 by lazy 直接建立 WalletViewModel(this, walletApi)，onOrderPlaced 呼叫 wallet.open()，資產頁的 API 會被呼叫 1 次"
            "單獨測試下單時，被建立出來的資產頁物件：1 個" !in lines -> "Bug 還沒重現：下單頁要真的建立 WalletViewModel"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("單獨測試下單時") } -> "演練沒有跑完"
            "單獨測試下單：資產頁的 API 被呼叫 0 次，導覽請求 1 次" !in lines ->
                "測試下單頁只用假的 WalletNavigator：onOrderPlaced 呼叫 walletNavigator.openWallet()，資產頁的 API 不該被呼叫，導覽請求 1 次"
            "單獨測試下單時，被建立出來的資產頁物件：0 個" !in lines -> "下單頁不該建立資產頁的物件"
            else -> null
        }
    },
)
