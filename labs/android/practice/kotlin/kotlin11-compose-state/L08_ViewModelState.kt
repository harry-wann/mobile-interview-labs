// L08：價格警示在單元測試裡只收到第一筆（ViewModel 用 mutableStateOf 暴露狀態，非 Compose 的程式收不到變化）
// 對應複習站：Kotlin11ComposeState 第 6 題「ViewModel 的畫面狀態用 mutableStateOf 還是 StateFlow？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 practice/kotlin/test/Kotlin11StateTest.kt（已經寫好，不用改）。
// （為什麼不在 App 裡跑：App 本身是 Compose 畫面，畫面在跑的時候 Compose 會替你通知 snapshot 系統「有寫入了」，
//   結果看起來就是收得到。單元測試和沒有 Compose 畫面的環境沒有人做這件事，問題才看得到。）
//
// 情境：價格警示（可以想成 WorkManager 的 Worker，或是測試 ViewModel 的單元測試）要觀察 ViewModel 的價格變化，
//        它不是 Compose，只會用 Flow。ViewModel 收到 5 筆新價格（101 到 105），每筆間隔 40 ms。
//
// 你要寫：
//   ① Bug 版（L08Bug.PriceViewModel）：var price by mutableLongStateOf(100L)，prices() 用 snapshotFlow { price } 包成 Flow。
//      單元測試裡沒有 Compose 在跑，沒有人通知 snapshot 系統「有寫入了」，所以只收到開始時的第一筆 100。
//   ② 修正版（L08Fixed.PriceViewModel）：用 MutableStateFlow(100L)，對外給唯讀的 StateFlow，onPrice 寫進 MutableStateFlow。5 筆新價格都收得到。
// 只改有 TODO 的地方。寫完執行 test/Kotlin11StateTest.kt 的 l08()，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin11-compose-state/L08_ViewModelState.kt
package tw.harry.labs.practice.kotlin11composestate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog

/**
 * 演練流程（兩個版本共用）：非 Compose 的價格警示開始收 [prices] → ViewModel 依序收到 101 到 105（每筆間隔 40 ms）
 * → 看警示收到了哪些價格。[current] 回傳 ViewModel 現在的價格。
 */
internal suspend fun l08Scenario(log: LabLog, prices: Flow<Long>, push: (Long) -> Unit, current: () -> Long) = coroutineScope {
    val received = CopyOnWriteArrayList<Long>()
    val alert = launch(Dispatchers.Default) {                       // 沒有 Compose 的環境：像 Worker 或單元測試
        prices.collect { received += it }
    }
    delay(100)                                                      // 讓警示先開始收
    for (price in 101L..105L) {
        push(price)
        delay(40)
    }
    delay(100)
    alert.cancel()
    log("ViewModel 裡的價格：${current()}")
    log("非 Compose 的價格警示收到：${received.toList()}")
}

// MARK: - Bug 版
internal object L08Bug {
    class PriceViewModel {
        // TODO ①-1：var price by mutableLongStateOf(100L)，setter 設成 private（目前是一般的 var，沒有 State）
        var price: Long = 100L
            private set

        fun onPrice(newPrice: Long) {
            price = newPrice
        }

        /** 給非 Compose 的程式觀察價格：只能用 snapshotFlow 把 State 包成 Flow */
        // TODO ①-2：改成 snapshotFlow { price }（目前回傳空的 Flow）
        fun prices(): Flow<Long> = emptyFlow()
    }

    suspend fun run(log: LabLog) {
        val vm = PriceViewModel()
        l08Scenario(log, vm.prices(), vm::onPrice) { vm.price }
    }
}

// MARK: - 修正版
internal object L08Fixed {
    class PriceViewModel {
        // TODO ②-1：建立 private val _price = MutableStateFlow(100L)，並讓 price 回傳 _price.asStateFlow()（對外是唯讀的 StateFlow）
        val price: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()

        fun onPrice(newPrice: Long) {
            // TODO ②-2：把 newPrice 寫進 _price.value
        }
    }

    suspend fun run(log: LabLog) {
        val vm = PriceViewModel()
        l08Scenario(log, vm.price, vm::onPrice) { vm.price.value }
    }
}

// MARK: - 演練設定
private fun received(lines: List<String>): List<Long>? =
    lines.firstOrNull { it.startsWith("非 Compose 的價格警示收到：[") }
        ?.removePrefix("非 Compose 的價格警示收到：[")?.removeSuffix("]")
        ?.split(",")?.mapNotNull { it.trim().toLongOrNull() }

private fun current(lines: List<String>): Long? =
    lines.firstOrNull { it.startsWith("ViewModel 裡的價格：") }?.removePrefix("ViewModel 裡的價格：")?.toLongOrNull()

val l08 = Lab(
    id = "L08",
    title = "價格警示在單元測試裡只收到第一筆",
    question = "第 6 題：ViewModel 的畫面狀態用 mutableStateOf 還是 StateFlow？",
    goal = "價格警示不是 Compose，只會用 Flow。看 ViewModel 用 mutableStateOf 時，單元測試裡為什麼只收到第一筆，再改成 StateFlow。先寫出 Bug 版，確認問題重現，再寫修正版。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。先寫 TODO ①（Bug 版）、TODO ②（修正版）。",
        "打開 practice/kotlin/test/Kotlin11StateTest.kt，點 l08() 旁邊的 ▶ 執行；或在終端機：cd labs/android && ./gradlew :practice-kotlin:testDebugUnitTest --tests '*Kotlin11StateTest*' -i | grep SELFTEST。",
        "看 Bug 版與修正版各印出 PASS 或 FAIL，以及「非 Compose 的價格警示收到」的清單。",
        "想一想：在測試裡呼叫 Snapshot.sendApplyNotifications() 之後，Bug 版就收得到最後一筆了。這代表什麼？（snapshotFlow 的更新要靠 Compose 通知，單元測試要自己補這一步，所以 ViewModel 直接用 StateFlow 比較省事）",
    ),
    expectBug = "「ViewModel 裡的價格：105」，但「非 Compose 的價格警示收到：[100]」：只收到開始時的第一筆。",
    expectFixed = "「非 Compose 的價格警示收到：[100, 101, 102, 103, 104, 105]」，每一筆都收到。",
    file = "kotlin11-compose-state/L08_ViewModelState.kt",
    checkBug = { lines ->
        val list = received(lines)
        when {
            current(lines) != 105L -> "ViewModel 的價格沒有更新到 105：onPrice 要把新價格寫進 price"
            list.isNullOrEmpty() -> "價格警示一筆都沒收到：Bug 版 prices() 要用 snapshotFlow { price }，至少會收到開始時的 100"
            list != listOf(100L) -> "價格警示收到 $list：Bug 版在單元測試裡只該收到第一筆 [100]（snapshotFlow 沒有人通知，不會重新計算）"
            else -> null
        }
    },
    checkFixed = { lines ->
        val list = received(lines)
        when {
            list.isNullOrEmpty() -> "價格警示一筆都沒收到：ViewModel 要有 MutableStateFlow(100L)，對外給 StateFlow，讓警示 collect"
            list.first() != 100L || list.last() != 105L || list.size < 4 -> "價格警示收到 $list：要從 100 收到 105，onPrice 要把新價格寫進 MutableStateFlow"
            else -> null
        }
    },
)
