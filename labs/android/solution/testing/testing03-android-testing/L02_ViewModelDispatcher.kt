// L02：ViewModel 的測試要用 Thread.sleep 等結果，時過時不過（寫死 Dispatchers.
// IO、沒設定 Dispatchers.Main）
// 對應複習站：Testing03AndroidTesting 第 2 題「ViewModel 用了 Dispatchers.IO
// 或 viewModelScope，測試時要怎麼處理？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// solution/testing/test/Testing03L02Test.kt。
//
// 情境：PriceViewModel.load() 用 viewModelScope 啟動，切到背景載入價格，
// 載入完把價格存進 price。
//       假的伺服器 T03LatencyApi 每次要等一段時間（演練用 6 種：30、150、50、
// 130、70、170 ms）。
// Bug 版：load() 裡寫死 withContext(Dispatchers.IO)。單元測試沒有 Android
// 的主執行緒，也沒辦法控制時間，
//        測試只好 Thread.sleep(100) 再檢查：伺服器比 100 ms 快的通過，
// 比較慢的失敗，同樣的程式有時過有時不過。
//        （新版的 viewModelScope 在沒有主執行緒時不會丟例外，
// 而是悄悄改在背景執行緒跑，所以測試是真的在背景跑。）
// 修正版：dispatcher 用建構子注入：PriceViewModel(api, io)，load() 用
// withContext(io)。
//        測試傳入 StandardTestDispatcher，並用 Dispatchers.setMain
// 換掉主執行緒的 dispatcher，
//        再用 advanceUntilIdle() 執行；6 種延遲都通過，而且不用真的等。
package tw.harry.labs.solution.testing03androidtesting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab

/** 假的伺服器：每次呼叫要等 latencyMs 毫秒（用 delay 模擬），回傳價格 */
class T03LatencyApi(private val latencyMs: Long) {
    suspend fun fetchPrice(): Int {
        delay(latencyMs)
        return 15_000_000
    }
}

// MARK: - Bug 版
internal object L02Bug {
    class PriceViewModel(private val api: T03LatencyApi) : ViewModel() {
        val price = MutableStateFlow(0)

        fun load() {
            viewModelScope.launch {
                // 寫死 Dispatchers.IO：測試換不掉
                price.value = withContext(Dispatchers.IO) { api.fetchPrice() }
            }
        }
    }
}

// MARK: - 修正版
internal object L02Fixed {
    class PriceViewModel(
        private val api: T03LatencyApi,
        private val io: CoroutineDispatcher,
    ) : ViewModel() {
        val price = MutableStateFlow(0)

        fun load() {
            viewModelScope.launch {
                // dispatcher 從建構子注入
                price.value = withContext(io) { api.fetchPrice() }
            }
        }
    }
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "ViewModel 測試要 sleep 等結果，時過時不過",
    question = "第 2 題：ViewModel 用了 Dispatchers.IO 或 viewModelScope，測試時要怎麼處理？",
    goal = "把 dispatcher 注入 ViewModel，測試換成 StandardTestDispatcher、設定 Dispatchers.setMain，不用 sleep 也能穩定通過。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。打開 test/Testing03L02Test.kt，點 l02() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :solution-testing:testDebugUnitTest --tests '*Testing03L02Test*' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：6 種伺服器延遲裡，哪幾種通過、哪幾種失敗；修正版是不是 6 種都通過。",
        "想一想：把 Bug 版測試的 sleep 改成 1 秒，會變穩定嗎？代價是什麼？",
    ),
    expectBug = "6 種延遲裡只有伺服器比 100 ms 快的 3 種通過（30、50、70 ms），其他 3 種失敗；整輪真實花了約 600 ms 以上。",
    expectFixed = "6 種延遲全部通過，而且真實只花幾百毫秒以內（虛擬時間不用真的等）。",
    file = "testing03-android-testing/L02_ViewModelDispatcher.kt",
)
