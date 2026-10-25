// L04：點推播登入後卻停在首頁（未登入時要先記下目標，登入後再繼續）
// 對應複習站：Platform06DeepLink 第 5
// 題「怎麼避免外部連結觸發下單這類敏感操作？」（登入與權限）
//
// 情境：價格提醒的連結 /alert/{編號} 要登入才能看。未登入的使用者點了推播，
// Router 要求先登入。
//       演練的劇本：使用者點推播 → 看到登入畫面 → 登入成功。接著他登出，
// 過幾天再正常登入一次（沒有連結）。
// Bug 版：Router 只要求顯示登入畫面，沒有記下原本要去哪裡。登入成功後停在首頁，
// 使用者得自己再找一次。
// 修正版：Router 把原本的連結存進 pendingLink。登入成功後取出來繼續處理，
// 而且取出來就清掉，
//        下一次單純的登入不會又被帶去同一個提醒頁。
import Foundation
import LabKit

/// 簡化的導覽（兩個版本共用的部分是 screen、log；差別在 handle 與
/// onLoginSuccess）
private let alertLink = "https://www.example.com/alert/ab12cd34"

// MARK: - Bug 版
private enum L04Bug {
    final class Navigator {
        private(set) var screen = "首頁"

        func handle(_ url: String, isLoggedIn: Bool) {
            if url.contains("/alert/") && !isLoggedIn {
                // 只要求登入，沒有記下原本的連結
                screen = "登入畫面"
            } else if url.contains("/alert/") {
                screen = "提醒 ab12cd34"
            }
        }

        func onLoginSuccess() {
            // 登入完成：不知道原本要去哪
            screen = "首頁"
        }
    }

    static func run(_ log: LabLog) async {
        let nav = Navigator()
        nav.handle(alertLink, isLoggedIn: false)
        log("未登入點到推播：畫面是 \(nav.screen)")
        nav.onLoginSuccess()
        log("登入成功後，畫面停在：\(nav.screen)")
        nav.onLoginSuccess()
        log("之後單純登入一次，畫面停在：\(nav.screen)")
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Navigator {
        private(set) var screen = "首頁"
        // 登入前先記下的目標
        private var pendingLink: String?

        func handle(_ url: String, isLoggedIn: Bool) {
            if url.contains("/alert/") && !isLoggedIn {
                // 先記下來，登入完成再繼續
                pendingLink = url
                screen = "登入畫面"
            } else if url.contains("/alert/") {
                screen = "提醒 ab12cd34"
            }
        }

        func onLoginSuccess() {
            screen = "首頁"
            if let link = pendingLink {
                // 取出來就清掉：只用一次
                pendingLink = nil
                handle(link, isLoggedIn: true)
            }
        }
    }

    static func run(_ log: LabLog) async {
        let nav = Navigator()
        nav.handle(alertLink, isLoggedIn: false)
        log("未登入點到推播：畫面是 \(nav.screen)")
        nav.onLoginSuccess()
        log("登入成功後，畫面停在：\(nav.screen)")
        nav.onLoginSuccess()
        log("之後單純登入一次，畫面停在：\(nav.screen)")
    }
}

// MARK: - 演練設定
extension Platform06DeepLink {
    static let l04 = Lab(
        id: "L04",
        title: "點推播登入後卻停在首頁",
        question: "第 5 題：怎麼避免外部連結觸發下單這類敏感操作？（登入與權限）",
        goal: "需要登入的連結，未登入時先記下目標；登入成功後繼續前往，而且只前往一次。",
        steps: [
            "選「Bug 版」執行，看登入成功後畫面停在哪裡。",
            "想一想：Router 要求登入的時候，原本的連結存到哪裡去了？",
            "選「修正版」執行，登入後要到提醒頁；再單純登入一次，要回到首頁。",
            "進階：敏感畫面（提領、下單確認）即使已登入，也要再用生物辨識或密碼確認一次。",
        ],
        expectBug: "「未登入點到推播：畫面是 登入畫面」「登入成功後，畫面停在：首頁」。",
        expectFixed: "登入成功後停在「提醒 ab12cd34」，之後單純登入一次停在「首頁」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("未登入點到推播：畫面是 登入畫面") {
                return "未登入點到提醒連結，Router 要顯示登入畫面"
            }
            return lines.contains("登入成功後，畫面停在：首頁")
                ? nil : "Bug 還沒重現：Router 沒有記下原本的連結，登入成功後只能停在首頁"
        },
        checkFixed: { lines in
            if !lines.contains("未登入點到推播：畫面是 登入畫面") {
                return "未登入點到提醒連結，Router 要顯示登入畫面"
            }
            if !lines.contains("登入成功後，畫面停在：提醒 ab12cd34") {
                return
                    "登入成功後要前往原本的連結：未登入時存進 pendingLink，onLoginSuccess 取出來 handle"
            }
            return lines.contains("之後單純登入一次，畫面停在：首頁")
                ? nil : "pendingLink 取出來之後要清成 nil，否則下一次登入又被帶去提醒頁"
        }
    )
}
