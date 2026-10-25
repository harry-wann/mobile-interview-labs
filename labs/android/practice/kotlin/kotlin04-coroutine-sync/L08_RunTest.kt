// L08：測試 load() 要真的等 2 秒，虛擬時間完全沒用（runTest 與注入 dispatcher）
// 對應複習站：Kotlin04CoroutineSync 第 5 題「runTest 怎麼測
// coroutine？虛擬時間是什麼？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// practice/kotlin/test/Kotlin04RunTest.kt（已經寫好，不用改）。
//
// 情境：OrderRepo.load() 向伺服器載入訂單，用 withContext 切到背景，約 2
// 秒（delay(2_000) 代表等伺服器）。
//        測試用 runTest，希望 delay 不要真的等。
//
// 你要寫：
//   ① Bug 版（L08Bug.OrderRepo）：load() 寫死 withContext(Dispatchers.IO) {
// delay(2_000); "50 筆訂單" }。測試真的等了 2 秒，虛擬時鐘沒動。
//   ② 修正版（L08Fixed.OrderRepo）：dispatcher 用建構子注入的 io：
// withContext(io) { delay(2_000); "50 筆訂單" }。
//      測試傳入 StandardTestDispatcher(testScheduler)，虛擬時鐘走 2000 ms，
// 瞬間完成。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/kotlin/kotlin04-coroutine-sync/L08_RunTest.kt
package tw.harry.labs.practice.kotlin04coroutinesync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L08Bug {
    class OrderRepo {
        // TODO ①：load() 寫死 withContext(Dispatchers.IO) { … }，裡面
        // delay(2_000)（代表等伺服器），最後回傳 "50 筆訂單"
        suspend fun load(): String = "（還沒寫）"
    }
}

// MARK: - 修正版
internal object L08Fixed {
    // dispatcher 用建構子注入
    class OrderRepo(private val io: CoroutineDispatcher) {
        // TODO ②：load() 改用注入的 io：withContext(io) { delay(2_000); "50
        // 筆訂單" }（目前寫死 Dispatchers.IO，虛擬時間不會生效）
        suspend fun load(): String = withContext(Dispatchers.IO) {
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
        "這題不用開 App，在電腦上跑單元測試。先寫 TODO ①（Bug 版）、TODO ②（修正版）。",
        "打開 practice/kotlin/test/Kotlin04RunTest.kt，點 l08() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :practice-kotlin:testDebugUnitTest --tests '*Kotlin04RunTest*' -i | grep SELFTEST。",
        "看 Bug 版與修正版各印出 PASS 或 FAIL，以及「真實花了幾 ms、虛擬時鐘走了幾 ms」。",
        "想一想：runTest 的 backgroundScope 是做什麼的？（放永遠不會結束的 coroutine，測試結束時自動取消）",
    ),
    expectBug = "load() 真實花了約 2000 ms，虛擬時鐘幾乎沒動（約 0 ms）。",
    expectFixed = "load() 真實只花幾十 ms，虛擬時鐘走了 2000 ms，而且結果是「50 筆訂單」。",
    file = "kotlin04-coroutine-sync/L08_RunTest.kt",
)
