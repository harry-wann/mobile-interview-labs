// L01：逾時明明設 1 秒，下載卻等了 2 秒多還不逾時（OkHttp 的 readTimeout 與
// callTimeout）
// 對應複習站：Platform05Networking 第 1 題「URLSession 和 OkHttp
// 怎麼對照？預設的逾時和傳輸安全規則差在哪？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// practice/platform/test/Platform05NetworkingTest.kt（已經寫好，不用改；
// 解答的在 solution/platform/test/）。
//
// 情境：App 下載一份資產明細。測試用 MockWebServer 當假伺服器，每 200 ms
// 只送出 1 個位元組，共 12 個，
//        全部送完約 2.2 秒（網速很慢，但連線沒有斷）。
// 工程師希望「整個下載最多等 1 秒，超過就逾時」。
//
// 你要寫：
//   ① Bug 版（L01Bug.client）：只設 readTimeout(1 秒)。
//   ② 修正版（L01Fixed.client）：readTimeout(1 秒) 之外，再加 callTimeout(1
// 秒)。
// 只改有 TODO 的地方。寫完在終端機跑測試（步驟裡有指令），結果會印成
// [SELFTEST]，FAIL 的後面會說還差什麼。
// 解答：solution/platform/platform05-networking/L01_RequestTimeout.kt
package tw.harry.labs.practice.platform05networking

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L01Bug {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        // TODO ①：.readTimeout(1, TimeUnit.SECONDS)
        // （工程師以為這樣整個呼叫最多等 1 秒），不要設 callTimeout
        .build()
}

// MARK: - 修正版
internal object L01Fixed {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        // TODO ②：再加上 callTimeout(1, TimeUnit.SECONDS)，
        // 它才是整個呼叫的總時限
        .build()
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "逾時設 1 秒，下載卻等了 2 秒多",
    question = "第 1 題：URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？",
    goal = "伺服器網速很慢但一直有資料送來。讓下載在 1 秒內逾時，要設對 OkHttp 的哪一個逾時。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。先寫 TODO ①（Bug 版）、TODO ②（修正版），再在終端機：cd labs/android && ./gradlew :practice-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l01' -i | grep SELFTEST。",
        "看 Bug 版（寫完 TODO ①）：只設 readTimeout(1 秒)，下載有沒有逾時、花了多久，出現 PASS。",
        "想一想：伺服器每 200 ms 就送一點資料，「兩次讀取之間最久等多久」會不會超過 1 秒？",
        "看修正版（寫完 TODO ②）：加上 callTimeout 之後，是不是約 1 秒就逾時。",
        "進階：URLSession 也有同樣的差別：timeoutIntervalForRequest 是兩次收到資料之間的空檔，timeoutIntervalForResource 才是總時限。",
    ),
    expectBug = "下載成功，花了約 2200 ms（超過 2000 ms）；沒有逾時。",
    expectFixed = "約 1000 ms 就丟出逾時例外（不到 1800 ms）。",
    file = "platform05-networking/L01_RequestTimeout.kt",
)
