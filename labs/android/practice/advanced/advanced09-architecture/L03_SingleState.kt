// L03：數量填了正確的數字，送出按鈕卻還是灰的；改成亂碼，
// 按鈕反而亮著（狀態有兩個地方在管）
// 對應複習站：Advanced09Architecture 第 2 題「MVVM、MVI、Clean Architecture、
// TCA 各解決什麼問題？代價是什麼？」（單向資料流）
//
// 情境：下單畫面有價格、數量兩個輸入框和一個送出按鈕。按鈕能不能按（canSubmit）
// 取決於兩個欄位是不是都是大於 0 的數字。
//       演練依序輸入：價格 15000000、數量 0.5、價格 15000001、數量 abc，
// 每次輸入之後看按鈕該不該亮。
//
// 你要寫：
//   ① Bug 版：price、quantity、canSubmit 各自是儲存的屬性。價格改變有重算
// canSubmit（已寫好），數量改變時忘了重算。
//   ② 修正版：單向資料流：state 只有 price、quantity，所有操作都送進 reduce；
// canSubmit 由 state 算出來。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced09-architecture/L03_SingleState.kt
package tw.harry.labs.practice.advanced09architecture

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 大於 0 的數字才算有效（兩個版本共用） */
private fun isValid(text: String): Boolean =
    Regex("""^\d+(\.\d+)?$""").matches(text) &&
        (text.toDoubleOrNull() ?: 0.0) > 0

/** 演練流程（兩個版本共用）：依序輸入，記下每次輸入之後送出按鈕的狀態 */
private fun scenario(
    log: LabLog,
    typePrice: (String) -> Unit,
    typeQuantity: (String) -> Unit,
    canSubmit: () -> Boolean,
) {
    fun button() = if (canSubmit()) "可以按" else "不能按"

    typePrice("15000000")
    log("輸入價格 15000000（數量還是空的）：送出按鈕${button()}")
    typeQuantity("0.5")
    log("輸入數量 0.5：送出按鈕${button()}")
    typePrice("15000001")
    log("價格改成 15000001：送出按鈕${button()}")
    typeQuantity("abc")
    log("數量改成 abc：送出按鈕${button()}")
}

// MARK: - Bug 版
private object L03Bug {
    class OrderViewModel {
        var price = ""
            private set
        var quantity = ""
            private set

        // 另一個儲存的屬性，要自己記得同步
        var canSubmit = false
            private set

        fun priceChanged(text: String) {
            price = text
            canSubmit = isValid(price) && isValid(quantity)
        }

        fun quantityChanged(text: String) {
            // TODO ①：只把 quantity 存起來（quantity = text），不要重算
            // canSubmit（重現「改了數量卻忘了更新按鈕」）
        }
    }

    suspend fun run(log: LabLog) {
        val viewModel = OrderViewModel()
        scenario(log, viewModel::priceChanged, viewModel::quantityChanged) {
            viewModel.canSubmit
        }
    }
}

// MARK: - 修正版
private object L03Fixed {
    data class OrderState(val price: String = "", val quantity: String = "") {
        // TODO ②-1：canSubmit 改成由 price、quantity 算出來的計算屬性：
        // 兩個欄位都是有效的數字（用 isValid）才是 true
        val canSubmit: Boolean get() = false
    }

    sealed interface OrderAction {
        data class PriceChanged(val text: String) : OrderAction
        data class QuantityChanged(val text: String) : OrderAction
    }

    /** 狀態怎麼變，只有這一個地方決定 */
    fun reduce(state: OrderState, action: OrderAction): OrderState =
        when (action) {
            // TODO ②-2：PriceChanged：回傳 state.copy(price = …)，用 action.text
            is OrderAction.PriceChanged -> state

            // TODO ②-3：QuantityChanged：回傳 state.copy(quantity = …)，用 action.
            // text
            is OrderAction.QuantityChanged -> state
        }

    class OrderViewModel {
        var state = OrderState()
            private set

        fun send(action: OrderAction) {
            state = reduce(state, action)
        }
    }

    suspend fun run(log: LabLog) {
        val viewModel = OrderViewModel()
        scenario(
            log,
            typePrice = { viewModel.send(OrderAction.PriceChanged(it)) },
            typeQuantity = { viewModel.send(OrderAction.QuantityChanged(it)) },
            canSubmit = { viewModel.state.canSubmit },
        )
    }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "數量填對了按鈕還是灰的",
    question = "第 2 題：MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？代價是什麼？（單向資料流）",
    goal = "能從其他欄位算出來的值，不要另外存一份；畫面的狀態集中在一份 state，只在一個地方修改。 先寫出忘了重算的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced09-architecture/L03_SingleState.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看每次輸入之後送出按鈕該不該亮，哪兩次不對？",
        "想一想：canSubmit 是誰負責更新的？有幾個地方會改價格、數量？每多一個欄位要多記得幾個地方？",
        "寫 TODO ②，選「修正版」執行：canSubmit 改成由 state 算出來，四次都對。",
        "進階：Android 的做法是 ViewModel 暴露一個 StateFlow<OrderUiState>，畫面用 collectAsStateWithLifecycle 收集；iOS 對應的是 TCA 或自己寫的 reducer。",
    ),
    expectBug = "「輸入數量 0.5：送出按鈕不能按」（應該可以按）；「數量改成 abc：送出按鈕可以按」（應該不能按）。",
    expectFixed = "依序是：不能按、可以按、可以按、不能按，四次都正確。",
    file = "advanced09-architecture/L03_SingleState.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "輸入價格 15000000（數量還是空的）：送出按鈕不能按" !in lines -> "演練沒有跑完：要用 scenario 執行，價格輸入後（數量空的）按鈕應該不能按"
            "輸入數量 0.5：送出按鈕不能按" !in lines -> "Bug 還沒重現：quantityChanged 只更新 quantity，沒有重算 canSubmit，數量填對了按鈕還是不能按"
            "數量改成 abc：送出按鈕可以按" !in lines -> "Bug 還沒重現：quantityChanged 沒有重算 canSubmit，數量改成亂碼時按鈕還是亮著"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "輸入價格 15000000（數量還是空的）：送出按鈕不能按" !in lines -> "價格輸入後（數量空的）按鈕應該不能按"
            "輸入數量 0.5：送出按鈕可以按" !in lines -> "數量填了 0.5 之後按鈕應該可以按：canSubmit 要由 price、quantity 算出來"
            "價格改成 15000001：送出按鈕可以按" !in lines -> "價格改成 15000001 之後按鈕應該可以按"
            "數量改成 abc：送出按鈕不能按" !in lines -> "數量改成 abc 之後按鈕應該不能按：canSubmit 要由 price、quantity 算出來"
            else -> null
        }
    },
)
