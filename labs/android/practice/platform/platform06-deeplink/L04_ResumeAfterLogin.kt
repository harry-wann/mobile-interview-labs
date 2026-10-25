// L04：點推播登入後卻停在首頁（未登入時要先記下目標，登入後再繼續）
// 對應複習站：Platform06DeepLink 第 5
// 題「怎麼避免外部連結觸發下單這類敏感操作？」（登入與權限）
//
// 情境：價格提醒的連結 /alert/{編號} 要登入才能看。未登入的使用者點了推播，
// Router 要求先登入。
//        演練的劇本：使用者點推播 → 看到登入畫面 → 登入成功。接著他登出，
// 過幾天再正常登入一次（沒有連結）。
//
// 你要寫：
//   ① Bug 版：handle 對未登入的 /alert/ 連結只顯示登入畫面（不記下連結）；
// onLoginSuccess 回到首頁。
//   ② 修正版：handle 把未登入的 /alert/ 連結存進 pendingLink；onLoginSuccess
// 先回首頁，有 pendingLink 就清成 null 並用 handle 處理它。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform06-deeplink/L04_ResumeAfterLogin.kt
package tw.harry.labs.practice.platform06deeplink

import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private const val ALERT_LINK = "https://www.example.com/alert/ab12cd34"

// MARK: - Bug 版
private object L04Bug {
    class Navigator {
        var screen = "首頁"
            private set

        fun handle(url: String, isLoggedIn: Boolean) {
            if ("/alert/" in url && !isLoggedIn) {
                // TODO ①-1：只要求登入：screen =
                // "登入畫面"（不要記下原本的連結）
            } else if ("/alert/" in url) {
                screen = "提醒 ab12cd34"
            }
        }

        fun onLoginSuccess() {
            // TODO ①-2：登入完成：screen = "首頁"
        }
    }

    suspend fun run(log: LabLog) {
        val nav = Navigator()
        nav.handle(ALERT_LINK, isLoggedIn = false)
        log("未登入點到推播：畫面是 ${nav.screen}")
        nav.onLoginSuccess()
        log("登入成功後，畫面停在：${nav.screen}")
        nav.onLoginSuccess()
        log("之後單純登入一次，畫面停在：${nav.screen}")
    }
}

// MARK: - 修正版
private object L04Fixed {
    class Navigator {
        var screen = "首頁"
            private set

        // 登入前先記下的目標
        private var pendingLink: String? = null

        fun handle(url: String, isLoggedIn: Boolean) {
            if ("/alert/" in url && !isLoggedIn) {
                // TODO ②-1：先把 url 存進 pendingLink，再 screen = "登入畫面"
            } else if ("/alert/" in url) {
                screen = "提醒 ab12cd34"
            }
        }

        fun onLoginSuccess() {
            screen = "首頁"
            // TODO ②-2：有 pendingLink 的話：先把 pendingLink 清成
            // null（只用一次），再用 handle(link, isLoggedIn = true) 前往
            pendingLink = pendingLink
        }
    }

    suspend fun run(log: LabLog) {
        val nav = Navigator()
        nav.handle(ALERT_LINK, isLoggedIn = false)
        log("未登入點到推播：畫面是 ${nav.screen}")
        nav.onLoginSuccess()
        log("登入成功後，畫面停在：${nav.screen}")
        nav.onLoginSuccess()
        log("之後單純登入一次，畫面停在：${nav.screen}")
    }
}

// MARK: - 演練設定
val l04 = Lab(
    id = "L04",
    title = "點推播登入後卻停在首頁",
    question = "第 5 題：怎麼避免外部連結觸發下單這類敏感操作？（登入與權限）",
    goal = "需要登入的連結，未登入時先記下目標；登入成功後繼續前往，而且只前往一次。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看登入成功後畫面停在哪裡，出現 ✓。",
        "想一想：Router 要求登入的時候，原本的連結存到哪裡去了？",
        "寫 TODO ②，選「修正版」執行，登入後要到提醒頁；再單純登入一次，要回到首頁。",
        "進階：敏感畫面（提領、下單確認）即使已登入，也要再用生物辨識或密碼確認一次。",
    ),
    expectBug = "「未登入點到推播：畫面是 登入畫面」「登入成功後，畫面停在：首頁」。",
    expectFixed = "登入成功後停在「提醒 ab12cd34」，之後單純登入一次停在「首頁」。",
    file = "platform06-deeplink/L04_ResumeAfterLogin.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L04Bug.run(log)
            Variant.FIXED -> L04Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            "未登入點到推播：畫面是 登入畫面" !in lines -> "未登入點到提醒連結，Router 要顯示登入畫面"
            "登入成功後，畫面停在：首頁" !in lines -> "Bug 還沒重現：Router 沒有記下原本的連結，登入成功後只能停在首頁"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            "未登入點到推播：畫面是 登入畫面" !in lines -> "未登入點到提醒連結，Router 要顯示登入畫面"
            "登入成功後，畫面停在：提醒 ab12cd34" !in lines -> "登入成功後要前往原本的連結：未登入時存進 pendingLink，onLoginSuccess 取出來 handle"
            "之後單純登入一次，畫面停在：首頁" !in lines -> "pendingLink 取出來之後要清成 null，否則下一次登入又被帶去提醒頁"
            else -> null
        }
    },
)
