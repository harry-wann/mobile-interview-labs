// L03：旋轉螢幕之後，下單數量欄位被清空（Activity 會被銷毀重建，ViewModel 不會）
// 對應複習站：Platform01Lifecycle 第 3 題「旋轉螢幕或切換深色模式時，兩個平台各發生什麼事？」
//
// 情境：使用者在下單頁輸入數量 0.05，然後旋轉螢幕。Android 預設把 Activity 銷毀再重建（configuration change），
//        系統會把 ViewModelStore（放 ViewModel 的地方）從舊的 Activity 交給新的 Activity。
//        演練用 ViewModelStore 和 ViewModelProvider 模擬這件事：旋轉 = 丟掉舊的畫面物件，用同一個 ViewModelStore 建新的。
// Bug 版：數量放在畫面（Activity）自己的欄位。旋轉之後畫面物件是全新的，欄位回到預設值。（Compose 的 remember 也是一樣。）
// 修正版：數量放在 ViewModel。ViewModel 撐得過旋轉，新畫面拿到的還是同一個。
//        （Compose 要在旋轉、被系統結束後都保留，用 rememberSaveable；ViewModel 撐不過行程被結束，下一題會做。）
package tw.harry.labs.solution.platform01lifecycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 下單頁需要的東西（兩個版本共用） */
private interface L03OrderScreen {
    var quantity: String
}

/** 演練流程（兩個版本共用）：輸入數量 → 旋轉（舊畫面丟掉，用同一個 ViewModelStore 建新畫面） */
private fun scenario(log: LabLog, create: (ViewModelStore) -> L03OrderScreen) {
    val store = ViewModelStore()                  // 系統在旋轉時替我們保管的 ViewModelStore
    val before = create(store)
    before.quantity = "0.05"
    log("旋轉前，數量欄位：${before.quantity}")
    val after = create(store)                     // 旋轉：舊的 Activity 銷毀，新的 Activity 建立
    log("旋轉後，畫面物件是同一個：${if (before === after) "是" else "否（全新的 Activity）"}")
    log("旋轉後，數量欄位：${after.quantity.ifEmpty { "（空白）" }}")
}

// MARK: - Bug 版
private object L03Bug {
    /** 數量放在畫面自己的欄位 */
    class OrderActivity : L03OrderScreen {
        override var quantity = ""
    }

    fun run(log: LabLog) = scenario(log) { OrderActivity() }
}

// MARK: - 修正版
private object L03Fixed {
    class OrderViewModel : ViewModel() {
        var quantity = ""
    }

    /** 數量放在 ViewModel，畫面只是轉手 */
    class OrderActivity(store: ViewModelStore) : L03OrderScreen {
        private val viewModel = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { OrderViewModel() } },
        )[OrderViewModel::class]

        override var quantity: String
            get() = viewModel.quantity
            set(value) { viewModel.quantity = value }
    }

    fun run(log: LabLog) = scenario(log) { OrderActivity(it) }
}

// MARK: - 演練設定
val l03 = Lab(
    id = "L03",
    title = "旋轉螢幕之後，數量欄位被清空",
    question = "第 3 題：旋轉螢幕或切換深色模式時，兩個平台各發生什麼事？",
    goal = "旋轉螢幕時 Android 會銷毀並重建 Activity。使用者輸入的數量要撐得過旋轉。",
    steps = listOf(
        "選「Bug 版」按執行，看「旋轉後，數量欄位」。",
        "選「修正版」執行，確認旋轉後畫面物件是新的（否），數量卻還在。",
        "想一想：iOS 旋轉時 ViewController 會被重建嗎？（不會；iOS 的 L03 會確認這件事）",
    ),
    expectBug = "旋轉後畫面物件是全新的，數量欄位是（空白）。",
    expectFixed = "旋轉後畫面物件是全新的，數量欄位還是 0.05。",
    file = "platform01-lifecycle/L03_ConfigChange.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L03Bug.run(log)
            Variant.FIXED -> L03Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            !lines.contains("旋轉前，數量欄位：0.05") -> "旋轉前數量要能輸入：quantity 要能存取"
            !lines.contains("旋轉後，數量欄位：（空白）") -> "Bug 還沒重現：Bug 版把數量放在畫面自己的欄位，旋轉後新畫面要是空白"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            !lines.contains("旋轉前，數量欄位：0.05") -> "旋轉前數量要能輸入"
            !lines.contains("旋轉後，數量欄位：0.05") -> "旋轉後數量不見了：把 quantity 放進 ViewModel，用同一個 ViewModelStore 取得"
            else -> null
        }
    },
)
