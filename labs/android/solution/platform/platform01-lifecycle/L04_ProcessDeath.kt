// L04：App 被系統結束後回來，下單數量不見了（process death 與
// SavedStateHandle）
// 對應複習站：Platform01Lifecycle 第 4 題「App 在背景被系統殺掉之後，
// 使用者回來會看到什麼？」
//
// 情境：使用者在下單頁選好 ETH/JPY、輸入數量 0.05、按下送出（請求還沒回來）
// 就切去 LINE。三十分鐘後系統為了回收記憶體，結束了整個行程。
//        使用者從最近工作清單回來，系統重新建立行程、Activity，連 ViewModel
// 也是全新的。
//        演練用縮小版重現：第一個行程把 SavedStateHandle
// 的內容抄成一份（系統存進 Bundle 的就是這份），整個 ViewModel 丟掉，
//        再用抄下來的內容建立第二個行程的 SavedStateHandle 和 ViewModel。
//        真的在模擬器重現的方法見複習站：先按 Home，再執行 adb shell am kill
// <套件名稱>，最後從最近工作清單回到 App。
// Bug 版：交易對、數量放在 ViewModel 的欄位。ViewModel 撐得過旋轉，
// 撐不過行程被結束，全部消失。
// 修正版：交易對、數量放進 SavedStateHandle；報價、
// 送出中不放（報價回來後重新取得，送出中的請求早就不在了）。
package tw.harry.labs.solution.platform01lifecycle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 下單表單的狀態（兩個版本共用） */
private abstract class L04FormViewModel : ViewModel() {
    abstract var symbol: String
    abstract var quantity: String

    // 三十分鐘前向伺服器取得的報價
    var quotePrice: Int? = null

    // 已按下送出，請求還在途中
    var isSubmitting = false
}

/** 演練流程（兩個版本共用）：存起來 → 行程消失 → 重新啟動後還原 */
private fun scenario(
    log: LabLog,
    create: (SavedStateHandle) -> L04FormViewModel,
) {
    // 第一個行程
    val handle = SavedStateHandle()
    val first = create(handle)
    first.symbol = "ethjpy"
    first.quantity = "0.05"
    first.quotePrice = 15_000_000
    first.isSubmitting = true
    log("還原前：交易對 ${first.symbol}、數量 ${first.quantity.ifEmpty { "（空白）" }}")
    // onSaveInstanceState：系統把 SavedStateHandle 裡的東西放進 Bundle（演練用
    // Map 代替）
    val bundle: Map<String, Any?> = handle.keys().associateWith {
        handle.get<Any?>(it)
    }
    // ……行程被系統結束，first 和 handle 都消失，只剩 bundle……

    // 第二個行程：系統用 Bundle 的內容建立新的 SavedStateHandle，再建立全新的
    // ViewModel
    val second = create(SavedStateHandle(bundle))
    log("還原後：交易對 ${second.symbol}、數量 ${second.quantity.ifEmpty { "（空白）" }}")
    log("還原後：報價 ${second.quotePrice?.toString() ?: "（沒有，要重新取得）"}")
    log("還原後：按鈕${if (second.isSubmitting) "停在送出中" else "可以操作"}")
}

// MARK: - Bug 版
private object L04Bug {
    class OrderViewModel(
        @Suppress("UNUSED_PARAMETER") handle: SavedStateHandle,
    ) : L04FormViewModel() {
        // 放在 ViewModel 的欄位：行程一結束就沒了
        override var symbol = "btcjpy"
        override var quantity = ""
    }

    fun run(log: LabLog) = scenario(log) { OrderViewModel(it) }
}

// MARK: - 修正版
private object L04Fixed {
    class OrderViewModel(private val handle: SavedStateHandle) :
        L04FormViewModel() {
        override var symbol: String
            get() = handle["symbol"] ?: "btcjpy"
            // 使用者輸入的小量資料：放進 SavedStateHandle
            set(value) { handle["symbol"] = value }
        override var quantity: String
            get() = handle["quantity"] ?: ""
            set(value) { handle["quantity"] = value }
        // quotePrice、isSubmitting 繼承自父類別，不放進 SavedStateHandle：
        // 還原後是預設值
    }

    fun run(log: LabLog) = scenario(log) { OrderViewModel(it) }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "App 被結束再回來，數量不見了",
    question = "第 4 題：App 在背景被系統殺掉之後，使用者回來會看到什麼？",
    goal = "行程被系統結束再回來，使用者輸入的交易對與數量要還原；報價要重新取得，不能停在「送出中」。",
    steps = listOf(
        "選「Bug 版」按執行，看「還原後」的交易對與數量。",
        "選「修正版」執行：交易對與數量還原；報價沒有還原；按鈕可以操作。",
        "在模擬器重現：按 Home 回桌面，執行 adb shell am kill tw.harry.labs，再從最近工作清單回到 App（見複習站第 4 題）。",
    ),
    expectBug = "還原後交易對回到預設的 btcjpy、數量（空白）：ViewModel 的欄位隨行程一起消失。",
    expectFixed = "還原後交易對 ethjpy、數量 0.05；報價（沒有，要重新取得）；按鈕可以操作。",
    file = "platform01-lifecycle/L04_ProcessDeath.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            !lines.contains(
                "還原前：交易對 ethjpy、數量 0.05",
            ) -> "還原前要能輸入交易對與數量：symbol、quantity 要能存取"

            lines.contains(
                "還原後：交易對 ethjpy、數量 0.05",
            ) -> "Bug 還沒重現：Bug 版要把數量放在 ViewModel 的欄位（不要用 SavedStateHandle）"

            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            !lines.contains(
                "還原前：交易對 ethjpy、數量 0.05",
            ) -> "還原前要能輸入交易對與數量：symbol、quantity 要能存取"

            !lines.contains(
                "還原後：交易對 ethjpy、數量 0.05",
            ) -> "使用者輸入的數量要還原：把 symbol、quantity 存進 SavedStateHandle"

            !lines.contains(
                "還原後：報價 （沒有，要重新取得）",
            ) -> "報價會變，不要還原：不要把 quotePrice 放進 SavedStateHandle，回來後重新向伺服器取得"

            !lines.contains("還原後：按鈕可以操作") -> "「送出中」不要還原：請求早就不在了，還原會讓按鈕永遠停在送出中"

            else -> null
        }
    },
)
