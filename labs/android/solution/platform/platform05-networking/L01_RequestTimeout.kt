// L01：逾時明明設 1 秒，下載卻等了 2 秒多還不逾時（OkHttp 的 readTimeout 與
// callTimeout）
// 對應複習站：Platform05Networking 第 1 題「URLSession 和 OkHttp
// 怎麼對照？預設的逾時和傳輸安全規則差在哪？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// solution/platform/test/Platform05NetworkingTest.kt。
//
// 情境：App 下載一份資產明細。測試用 MockWebServer 當假伺服器，每 200 ms
// 只送出 1 個位元組，共 12 個，
//        全部送完約 2.2 秒（網速很慢，但連線沒有斷）。
// 工程師希望「整個下載最多等 1 秒，超過就逾時」。
// Bug 版：只設 readTimeout(1 秒)。它是「兩次讀取之間最久等多久」，
// 每收到一點資料就重新計時，伺服器每 200 ms 就送一點，
//        永遠不會逾時，等了約 2.2 秒才完成。
// 修正版：再加上 callTimeout(1 秒)。它才是「整個呼叫的總時限」
// （預設是不限制），1 秒一到就逾時。
package tw.harry.labs.solution.platform05networking

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L01Bug {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        // 以為這是「整個呼叫最多 1 秒」：其實是兩次讀取之間的空檔
        .readTimeout(1, TimeUnit.SECONDS)
        .build()
}

// MARK: - 修正版
internal object L01Fixed {
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        // 整個呼叫的總時限：1 秒到就逾時
        .callTimeout(1, TimeUnit.SECONDS)
        .build()
}

// MARK: - 演練設定
val l01 = Lab(
    id = "L01",
    title = "逾時設 1 秒，下載卻等了 2 秒多",
    question = "第 1 題：URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？",
    goal = "伺服器網速很慢但一直有資料送來。讓下載在 1 秒內逾時，要設對 OkHttp 的哪一個逾時。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。終端機：cd labs/android && ./gradlew :solution-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l01' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：只設 readTimeout(1 秒)，下載有沒有逾時、花了多久。",
        "想一想：伺服器每 200 ms 就送一點資料，「兩次讀取之間最久等多久」會不會超過 1 秒？",
        "看修正版：加上 callTimeout 之後，是不是約 1 秒就逾時。",
        "進階：URLSession 也有同樣的差別：timeoutIntervalForRequest 是兩次收到資料之間的空檔，timeoutIntervalForResource 才是總時限。",
    ),
    expectBug = "下載成功，花了約 2200 ms（超過 2000 ms）；沒有逾時。",
    expectFixed = "約 1000 ms 就丟出逾時例外（不到 1800 ms）。",
    file = "platform05-networking/L01_RequestTimeout.kt",
)
