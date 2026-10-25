// L04：帳號被凍結後，App 對伺服器狂打 21 次請求（Authenticator 要檢查 priorResponse）
// 對應複習站：Platform05Networking 第 4 題「很多請求同時收到 401，怎麼只刷新一次 token？」
//
// 這題在電腦上跑單元測試（JVM），不用模擬器，也沒有「執行」按鈕。測試在 practice/platform/test/Platform05NetworkingTest.kt（已經寫好，不用改；解答的在 solution/platform/test/）。
//
// 情境：使用者的帳號被風控凍結，MockWebServer 對 /assets 一律回 401，即使換了新 token 也一樣。
//        App 用 OkHttp 的 Authenticator 處理 401：刷新 token，回傳帶新 token 的請求讓 OkHttp 重送。
//        （多個請求同時 401 時共用一次刷新，已在 Kotlin04 L07 用 Mutex 練過，這題只看「重送幾次」。）
//
// 你要寫：
//   ① Bug 版（L04Bug.TokenAuthenticator.authenticate）：每次都 refresh()，回傳帶新 token 的請求，不檢查 priorResponse。
//   ② 修正版（L04Fixed.TokenAuthenticator.authenticate）：先檢查 response.priorResponse，不是 null 就回傳 null；否則 refresh() 並回傳帶新 token 的請求。
// 只改有 TODO 的地方。寫完在終端機跑測試（步驟裡有指令），結果會印成 [SELFTEST]，FAIL 的後面會說還差什麼。
// 解答：solution/platform/platform05-networking/L04_RetryOnce.kt
package tw.harry.labs.practice.platform05networking

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import tw.harry.labs.kit.Lab

// MARK: - Bug 版
internal object L04Bug {
    /** refresh：向伺服器換新 token（呼叫一次算一次） */
    class TokenAuthenticator(private val refresh: () -> String) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // TODO ①：val fresh = refresh()，然後回傳 response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
            //        （每次 401 都刷新、都重送，不要檢查這個請求是不是已經重送過）
            return null
        }
    }
}

// MARK: - 修正版
internal object L04Fixed {
    class TokenAuthenticator(private val refresh: () -> String) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // TODO ②：1. response.priorResponse 不是 null（這個請求已經重送過一次還是 401）就回傳 null 放棄
            //        2. 否則 val fresh = refresh()，回傳帶新 token 的請求（做法同 Bug 版）
            return null
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
        "這題不用開 App，在電腦上跑單元測試。先寫 TODO ①（Bug 版）、TODO ②（修正版），再在終端機：cd labs/android && ./gradlew :practice-platform:testDebugUnitTest --tests '*Platform05NetworkingTest.l04' -i | grep SELFTEST。",
        "看 Bug 版（寫完 TODO ①）：伺服器收到幾次 /assets 請求、刷新了幾次 token、最後丟出什麼例外，出現 PASS。",
        "想一想：重送之後如果還是 401，Bug 版的 authenticate() 會做什麼？是誰讓它停下來的？",
        "看修正版（寫完 TODO ②）：只打 2 次 /assets、刷新 1 次，呼叫端拿到 401 的回應。",
        "進階：iOS 的 URLSession 沒有 Authenticator，也沒有這個內建上限，要自己限制重送次數（iOS 的 L04）。",
    ),
    expectBug = "伺服器收到 21 次 /assets 請求，刷新 token 21 次，最後丟出 ProtocolException（Too many follow-up requests: 21）。",
    expectFixed = "伺服器收到 2 次 /assets 請求，刷新 token 1 次，呼叫端拿到 HTTP 401，沒有丟例外。",
    file = "platform05-networking/L04_RetryOnce.kt",
)
