// L04：帳號被凍結後，App 對伺服器狂打 21 次請求（Authenticator 要檢查
// priorResponse）
// 對應複習站：Platform05Networking 第 4 題「很多請求同時收到 401，
// 怎麼只刷新一次 token？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在
// solution/platform/test/Platform05NetworkingTest.kt。
//
// 情境：使用者的帳號被風控凍結，MockWebServer 對 /assets 一律回 401，
// 即使換了新 token 也一樣。
//        App 用 OkHttp 的 Authenticator 處理 401：刷新 token，回傳帶新 token
// 的請求讓 OkHttp 重送。
//        （多個請求同時 401 時共用一次刷新，已在 Kotlin04 L07 用 Mutex 練過，
// 這題只看「重送幾次」。）
// Bug 版：authenticate() 每次都刷新、都回傳新請求，
// 沒有檢查這個請求是不是已經重送過。重送後又是 401，又進來刷新、又重送，
//        一路打到 OkHttp 自己的上限（最多跟隨 20 次）才丟出 ProtocolException。
// 修正版：authenticate() 一開始先檢查 response.priorResponse：不是 null，
// 代表這個請求已經重送過一次還是 401，回傳 null 放棄，
//        OkHttp 把 401 交給呼叫端，由呼叫端清除登入狀態、回到登入畫面。
package tw.harry.labs.solution.platform05networking

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L04Bug {
    /** refresh：向伺服器換新 token（呼叫一次算一次） */
    class TokenAuthenticator(private val refresh: () -> String) :
        Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val fresh = refresh() // 每次 401 都刷新
            // 每次都回傳新請求，要 OkHttp 重送
            return response.request.newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
        }
    }
}

// MARK: - 修正版
internal object L04Fixed {
    class TokenAuthenticator(private val refresh: () -> String) :
        Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // 已經重送過一次還是 401：放棄，不再刷新
            if (response.priorResponse != null) return null
            val fresh = refresh()
            return response.request.newBuilder()
                .header("Authorization", "Bearer $fresh")
                .build()
        }
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "帳號被凍結，App 對伺服器狂打 21 次請求",
    question = "第 4 題：很多請求同時收到 401，怎麼只刷新一次 token？",
    goal = "收到 401 就刷新 token 再重送，但重送只能做一次。刷新後還是 401，要停下來交給呼叫端處理，不能一直重送。",
    steps = listOf(
        "這題不用開 App，在電腦上跑單元測試。終端機：cd labs/android && ./gradlew :solution-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l04' -i | grep SELFTEST（練習版把 solution 換成 practice）。",
        "看 Bug 版：伺服器收到幾次 /assets 請求、刷新了幾次 token、最後丟出什麼例外。",
        "想一想：重送之後如果還是 401，Bug 版的 authenticate() 會做什麼？是誰讓它停下來的？",
        "看修正版：只打 2 次 /assets、刷新 1 次，呼叫端拿到 401 的回應。",
        "進階：iOS 的 URLSession 沒有 Authenticator，也沒有這個內建上限，要自己限制重送次數（iOS 的 L04）。",
    ),
    expectBug = "伺服器收到 21 次 /assets 請求，刷新 token 21 次，最後丟出 ProtocolException（Too many follow-up requests: 21）。",
    expectFixed = "伺服器收到 2 次 /assets 請求，刷新 token 1 次，呼叫端拿到 HTTP 401，沒有丟例外。",
    file = "platform05-networking/L04_RetryOnce.kt",
)
