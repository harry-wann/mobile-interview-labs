// L08：測試 load() 要真的等 2 秒，虛擬時間完全沒用（runTest 與注入 dispatcher）
// 對應複習站：Kotlin04CoroutineSync 第 5 題「runTest 怎麼測 coroutine？虛擬時間是什麼？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 solution/kotlin/test/Kotlin04RunTest.kt。
//
// 情境：OrderRepo.load() 向伺服器載入訂單，用 withContext 切到背景，約 2 秒（delay(2_000) 代表等伺服器）。
//        測試用 runTest，希望 delay 不要真的等。
// Bug 版：load() 裡寫死 withContext(Dispatchers.IO)。虛擬時間只對「和測試共用同一個 scheduler 的 dispatcher」有效，
//        Dispatchers.IO 不是，delay(2_000) 是真實時間，測試真的卡了 2 秒，runTest 的虛擬時鐘卻沒動。
// 修正版：dispatcher 用建構子注入：class OrderRepo(private val io: CoroutineDispatcher)，load() 用 withContext(io)。
//        測試時傳入 StandardTestDispatcher(testScheduler)，delay(2_000) 由 scheduler 撥快虛擬時鐘，瞬間完成。
package tw.harry.labs.solution.kotlin04coroutinesync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L08Bug {
    class OrderRepo {
        suspend fun load(): String = withContext(Dispatchers.IO) {   // 寫死 Dispatchers.IO：測試換不掉
            delay(2_000)                                           // 代表等伺服器回應
            "50 筆訂單"
        }
    }
}

// MARK: - 修正版
internal object L08Fixed {
    class OrderRepo(private val io: CoroutineDispatcher) {          // dispatcher 用建構子注入
        suspend fun load(): String = withContext(io) {
            delay(2_000)
            "50 筆訂單"
        }
    }
}

// MARK: - 演練設定
val l08 = Lab(
    id = "L08",
    title = "測試真的等了 2 秒，虛擬時間沒用",
    question = "第 5 題：runTest 怎麼測 coroutine？虛擬時間是什麼？",
    goal = "用 runTest 測有 delay(2_000) 的 load()。看寫死 Dispatchers.IO 時測試為什麼真的等 2 秒，再把 dispatcher 注入，讓虛擬時間生效。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。打開 test/Kotlin04RunTest.kt，點 l08() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :solution-kotlin:testDebugUnitTest --tests '*Kotlin04RunTest*' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版與修正版各印出「真實花了幾 ms、虛擬時鐘走了幾 ms」。",
        "想一想：runTest 的 backgroundScope 是做什麼的？（放永遠不會結束的 coroutine，測試結束時自動取消）",
    ),
    expectBug = "load() 真實花了約 2000 ms，虛擬時鐘幾乎沒動（約 0 ms）。",
    expectFixed = "load() 真實只花幾十 ms，虛擬時鐘走了 2000 ms，而且結果是「50 筆訂單」。",
    file = "kotlin04-coroutine-sync/L08_RunTest.kt",
)
