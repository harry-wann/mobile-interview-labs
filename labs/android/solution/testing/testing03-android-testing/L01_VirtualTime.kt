// L01：測一個會重試的函式，測試真的等了 3 秒（runBlocking 的 delay 是真實時間）
// 對應複習站：Testing03AndroidTesting 第 1 題「有 delay 的 coroutine，測試時怎麼不用真的等？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 solution/testing/test/Testing03L01Test.kt。
//
// 情境：t03Retry 在遇到 IOException 時，等 1 秒再試、再失敗就等 2 秒再試（每次加倍），最多試 3 次。
//       假的伺服器 T03FlakyApi 前兩次呼叫逾時、第三次成功，所以整個流程要「等」3 秒。
// Bug 版：測試用 runBlocking 呼叫 t03Retry。delay 是真實時間，測試真的卡了 3 秒，而且看不到中間的狀態。
// 修正版：測試改用 runTest：delay 變成虛擬時間，不用真的等。用 runCurrent()、advanceTimeBy() 一步一步推進時鐘，
//        檢查每個時間點呼叫了幾次。注意 advanceTimeBy(1_000) 不會執行「剛好排在 1_000 這個時間點」的工作，要再呼叫 runCurrent()。
package tw.harry.labs.solution.testing03androidtesting

import java.io.IOException
import kotlinx.coroutines.delay
import tw.harry.labs.kit.Lab

/** 假的伺服器：前兩次呼叫逾時（丟 IOException），第三次成功回傳價格 */
class T03FlakyApi {
    var calls = 0
        private set

    suspend fun fetchPrice(): Int {
        calls++
        if (calls < 3) throw IOException("逾時")
        return 15_000_000
    }
}

/** 失敗就等一下再試：先等 1 秒，再等 2 秒（每次加倍），最多試 3 次 */
suspend fun <T> t03Retry(block: suspend () -> T): T {
    var wait = 1_000L
    repeat(2) {
        try {
            return block()
        } catch (_: IOException) {
            delay(wait)
            wait *= 2
        }
    }
    return block()
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "測試真的等了 3 秒，看不到中間狀態",
    question = "第 1 題：有 delay 的 coroutine，測試時怎麼不用真的等？",
    goal = "用 runTest 的虛擬時間測 t03Retry：不用真的等 3 秒，還能檢查每個時間點呼叫了幾次。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。打開 test/Testing03L01Test.kt，點 l01() 旁邊的 ▶ 執行。",
        "或在終端機：cd labs/android && ./gradlew :solution-testing:testDebugUnitTest --tests '*Testing03L01Test*' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版與修正版各印出「真實花了幾 ms、虛擬時鐘走了幾 ms」，以及修正版每個時間點的呼叫次數。",
        "想一想：為什麼 advanceTimeBy(1_000) 之後呼叫次數還是 1 次？（它不會執行剛好排在 1_000 這個時間點的工作）",
    ),
    expectBug = "runBlocking 版真實花了約 3000 ms 才取得價格。",
    expectFixed = "runTest 版真實只花幾十 ms，虛擬時鐘走了 3000 ms；呼叫次數依序是 1、1、2、3（t=0、advanceTimeBy(1000) 之後、再 runCurrent() 之後、advanceTimeBy(2000) 加 runCurrent() 之後）。",
    file = "test/Testing03L01Test.kt",
)
