// L04：登出之後還收到前一個帳號的提醒，換了 token 之後卻收不到提醒（推播 token
// 的管理）
// 對應複習站：Platform03BackgroundPush 第 4
// 題「推播怎麼從伺服器送到手機？token 是什麼？」
//
// 情境：推播 token 是 APNs 給「這支手機上這個 App」的地址（Android 是 FCM 的
// registration token），伺服器靠它把價格提醒送到手機。
//        伺服器的對照表是「帳號 → 這個帳號登入過的裝置 token」。演練跑兩段：
//        ① App 啟動時系統發了 token T1，帳號 A 登入、登出，帳號 B 登入。看 A
// 的價格提醒還會不會送到這支手機。
//        ② 帳號 B 登入之後，系統因為重新安裝／還原備份換了新的 token T2。看 B
// 的價格提醒會送到哪一個 token。
//        系統通知 App 的時機：iOS 是
// didRegisterForRemoteNotificationsWithDeviceToken，Android 是
// FirebaseMessagingService.onNewToken。
//
// 你要寫：
//   ① Bug 版：登入時上傳 token 一次；token 換新不處理；登出什麼都不做。
//   ② 修正版：拿到 token 存起來，已登入就立刻上傳並註銷舊的；登入時綁定帳號；
// 登出時註銷。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform03-BackgroundPush/L04_TokenLifecycle.swift
import Foundation
import LabKit

/// 假的推播伺服器（兩個版本共用）
private final class PushServer {
    private var tokensByUser: [String: Set<String>] = [:]

    func register(user: String, token: String) {
        tokensByUser[user, default: []].insert(token)
    }
    func unregister(user: String, token: String) {
        tokensByUser[user]?.remove(token)
    }

    /// 價格提醒要送給某個帳號：會送到哪些 token
    func deliver(to user: String) -> Set<String> { tokensByUser[user] ?? [] }
}

/// App 這一邊管理推播 token 的物件（兩個版本各自實作）
private protocol PushClient: AnyObject {
    // 系統給了 token（iOS：didRegisterForRemoteNotificationsWithDeviceToken）
    func onTokenReceived(_ token: String)
    func onLogin(user: String)
    func onLogout()
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeClient: (PushServer) -> PushClient) {
    // ① 帳號 A 登入、登出，帳號 B 登入
    let server1 = PushServer()
    let client1 = makeClient(server1)
    client1.onTokenReceived("T1")
    client1.onLogin(user: "A")
    client1.onLogout()
    client1.onLogin(user: "B")
    log("A 登出、B 登入之後，A 的價格提醒還會送到 \(server1.deliver(to: "A").count) 台裝置")

    // ② 登入之後，系統換了新的 token
    let server2 = PushServer()
    let client2 = makeClient(server2)
    client2.onTokenReceived("T1")
    client2.onLogin(user: "B")
    client2.onTokenReceived("T2")
    log(
        "token 換新（T1 → T2）之後，B 的價格提醒會送到：\(server2.deliver(to: "B").sorted().joined(separator: "、"))"
    )
}

// MARK: - Bug 版
private enum L04Bug {
    final class Client: PushClient {
        private let server: PushServer
        private var token: String?

        init(server: PushServer) {
            self.server = server
        }

        func onTokenReceived(_ token: String) {
            // TODO ①-1：只把 token 記在 self.token（換新時也不處理）
        }

        func onLogin(user: String) {
            // TODO ①-2：登入時，如果手上有 token，就 server.register(user:
            // token:) 上傳一次
        }

        func onLogout() {}  // 登出什麼都不做
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Client(server: $0) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Client: PushClient {
        private let server: PushServer
        private var token: String?
        private var user: String?

        init(server: PushServer) {
            self.server = server
        }

        func onTokenReceived(_ token: String) {
            // TODO ②-1：把新 token 記下來；如果已經登入（user 不是 nil），
            // 要把舊的 token 註銷（server.unregister），再把新的上傳（server.
            // register）
        }

        func onLogin(user: String) {
            // TODO ②-2：記下 user；如果手上有 token，就 server.register
            // 綁定這個帳號
        }

        func onLogout() {
            // TODO ②-3：如果有 user 和 token，就 server.unregister 註銷；再把
            // user 清成 nil
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Client(server: $0) }
    }
}

// MARK: - 演練設定
private func leakedDevices(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("A 登出、B 登入之後，A 的價格提醒還會送到 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(
                    of: "A 登出、B 登入之後，A 的價格提醒還會送到 ", with: ""
                ).replacingOccurrences(of: " 台裝置", with: ""))
        }
}

private func deliveredTokens(_ lines: [String]) -> String? {
    lines.first { $0.hasPrefix("token 換新（T1 → T2）之後，B 的價格提醒會送到：") }
        .map {
            $0.replacingOccurrences(
                of: "token 換新（T1 → T2）之後，B 的價格提醒會送到：", with: "")
        }
}

extension Platform03BackgroundPush {
    static let l04 = Lab(
        id: "L04",
        title: "登出後還收到別人的提醒，換 token 後收不到",
        question: "第 4 題：推播怎麼從伺服器送到手機？token 是什麼？",
        goal:
            "推播 token 要登入後才綁定帳號、換新時重新上傳並註銷舊的、登出時註銷。先寫出登出不註銷、換 token 不更新的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform03-BackgroundPush/L04_TokenLifecycle.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看 A 登出之後還會送到幾台裝置，以及 token 換新之後 B 的提醒送到哪個 token。",
            "寫 TODO ②（修正版），選「修正版」執行：A 登出後 0 台；換新之後只送到 T2。",
            "想一想：伺服器還要處理什麼？（APNs 回 410、FCM 回 UNREGISTERED 代表 token 已失效，要從資料庫刪掉）",
        ],
        expectBug:
            "A 登出、B 登入之後，A 的提醒還會送到 1 台裝置；token 換新之後，B 的提醒送到：T1（舊的，這支手機已經收不到）。",
        expectFixed: "A 登出、B 登入之後，A 的提醒送到 0 台裝置；token 換新之後，B 的提醒送到：T2。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let leaked = leakedDevices(lines),
                let tokens = deliveredTokens(lines)
            else { return "演練沒有跑完" }
            if leaked == 0 { return "Bug 還沒重現：Bug 版登出時什麼都不做，A 的 token 還留在伺服器" }
            if tokens != "T1" {
                return "Bug 還沒重現：Bug 版在 token 換新時不處理，伺服器手上還是舊的 T1"
            }
            return nil
        },
        checkFixed: { lines in
            guard let leaked = leakedDevices(lines),
                let tokens = deliveredTokens(lines)
            else { return "演練沒有跑完" }
            if leaked != 0 {
                return
                    "A 登出之後，伺服器還會把提醒送到 \(leaked) 台裝置：onLogout 要呼叫 server.unregister"
            }
            if tokens != "T2" {
                return "token 換新之後，B 的提醒送到「\(tokens)」：換新時要上傳 T2，並註銷舊的 T1"
            }
            return nil
        }
    )
}
