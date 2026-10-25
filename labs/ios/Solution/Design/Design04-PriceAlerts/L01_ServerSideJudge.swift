// L01：App 被關掉之後，價格到了卻沒有提醒（提醒的條件只存在 App 裡，由 App
// 判斷）
// 對應複習站：Design04PriceAlerts 第 2 題「為什麼價格的判斷一定要放在伺服器？」
//
// 情境：使用者建立兩個提醒：a1「BTC 高於 9,960,000 円」、a2「BTC 高於 10,000,
// 000 円」。價格依序是 9,900,000、9,970,000、10,020,000、10,050,000。
//       在第三個價格出現之前，使用者把 App 從多工畫面滑掉（App 被關閉）。
//       行情只會送到還活著的 App；伺服器的價格串流則一直都在。
// 手機的通知中心不管通知是 App 自己發的，還是伺服器透過 APNs／FCM 推播來的，
//       App 關掉之後都看得到推播（本機通知要靠 App 活著才發得出來）。
// 看手機最後收到哪些通知。
// Bug 版：提醒條件存在 App 裡，App 每收到一筆價格就比對、發通知。a1 在 9,970,
// 000 觸發了；App 被關閉之後沒有人再看價格，
//        a2 到了 10,020,000 也沒有通知。
// 修正版：建立提醒時把條件送到伺服器（register）；App 自己不判斷。
// 伺服器一直在看價格，條件成立就用推播送到手機：
//        a1、a2 都收到，包括 App 被關閉之後的 a2。
import LabKit

private struct Alert {
    let id: String
    let threshold: Int
}

/// 手機的通知中心（兩個版本共用）
private final class Phone {
    private(set) var notifications: [String] = []
    func notify(_ alertId: String) { notifications.append(alertId) }
    var summary: String {
        notifications.isEmpty ? "（沒有）" : notifications.joined(separator: "、")
    }
}

/// 伺服器（兩個版本共用，已經寫好）：記下 App 登記的提醒；每來一個價格，
/// 條件成立又還沒觸發過的，就推播到手機
private final class FakeServer {
    private var alerts: [Alert] = []
    private var fired: Set<String> = []

    func register(_ alert: Alert) { alerts.append(alert) }

    func onPrice(_ price: Int, push: (String) -> Void) {
        for alert in alerts
        where price >= alert.threshold && !fired.contains(alert.id) {
            fired.insert(alert.id)
            push(alert.id)
        }
    }
}

private protocol AlertApp: AnyObject {
    /// 使用者建立一個提醒
    func createAlert(_ alert: Alert)
    /// App 收到一筆價格（只有 App 活著、在收行情的時候才會被呼叫）
    func onPrice(_ price: Int)
}

private let alertList = [
    Alert(id: "a1", threshold: 9_960_000),
    Alert(id: "a2", threshold: 10_000_000),
]
private let prices = [9_900_000, 9_970_000, 10_020_000, 10_050_000]

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeApp: (FakeServer, Phone) -> AlertApp) {
    let phone = Phone()
    let server = FakeServer()
    let app = makeApp(server, phone)
    var appRunning = true
    for alert in alertList {
        app.createAlert(alert)
        log("使用者建立提醒 \(alert.id)：BTC 高於 \(d04Yen(alert.threshold)) 円")
    }
    for (index, price) in prices.enumerated() {
        if index == 2 {
            appRunning = false
            log("使用者把 App 從多工畫面滑掉（App 被關閉）")
        }
        // 行情只送到還活著的 App
        if appRunning { app.onPrice(price) }
        // 伺服器的價格串流一直都在
        server.onPrice(price) { phone.notify($0) }
        log("價格 \(d04Yen(price)) 円 → 手機收到的通知：\(phone.summary)")
    }
    log("最後：手機收到 \(phone.notifications.count) 則通知：\(phone.summary)")
}

// MARK: - Bug 版
private enum L01Bug {
    final class App: AlertApp {
        private let phone: Phone
        private var alerts: [Alert] = []
        private var fired: Set<String> = []
        init(phone: Phone) { self.phone = phone }

        func createAlert(_ alert: Alert) {
            // 條件只存在 App 裡
            alerts.append(alert)
        }

        func onPrice(_ price: Int) {
            for alert in alerts
            where price >= alert.threshold && !fired.contains(alert.id) {
                fired.insert(alert.id)
                // App 自己比對，自己發通知
                phone.notify(alert.id)
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { _, phone in App(phone: phone) }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class App: AlertApp {
        private let server: FakeServer
        init(server: FakeServer) { self.server = server }

        func createAlert(_ alert: Alert) {
            // 條件送到伺服器，由伺服器判斷
            server.register(alert)
        }

        // App 不判斷價格
        func onPrice(_ price: Int) {}
    }

    static func run(_ log: LabLog) async {
        scenario(log) { server, _ in App(server: server) }
    }
}

// MARK: - 演練設定
extension Design04PriceAlerts {
    static let l01 = Lab(
        id: "L01",
        title: "App 被關掉之後，價格到了卻沒有提醒",
        question: "第 2 題：為什麼價格的判斷一定要放在伺服器？",
        goal:
            "價格有沒有到，只能由伺服器判斷：App 在背景不能可靠地持續執行（被關掉就完全不執行）。App 的工作是把提醒條件送到伺服器，伺服器條件成立就推播。",
        steps: [
            "選「Bug 版」執行，看使用者把 App 滑掉前後，手機收到的通知有什麼不同。",
            "想一想：a2 在 App 被關閉之後才到價，誰會發現？（App 沒有在跑，沒有人比對。）",
            "選「修正版」執行：a1、a2 都收到，包括 App 被關閉之後的 a2。",
            "想一想：iOS 的 BGAppRefreshTask、Android 的 WorkManager 能不能讓 App 在背景定期檢查價格？（系統決定什麼時候執行，可能幾小時一次，也可能不執行，不能當成「到價就通知」的機制。）",
        ],
        expectBug: "a1 在 9,970,000 觸發；App 被關閉後 a2 沒有人判斷：「最後：手機收到 1 則通知：a1」。",
        expectFixed: "伺服器一直在看價格：「最後：手機收到 2 則通知：a1、a2」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeApp:) 執行"
            }
            if last.hasPrefix("最後：手機收到 0 則通知") {
                return
                    "App 還沒判斷價格：createAlert 要把提醒存進 alerts，onPrice 要比對價格並呼叫 phone.notify"
            }
            return last == "最後：手機收到 1 則通知：a1"
                ? nil
                : "Bug 還沒重現：App 活著的時候比對價格、發通知（a1 會響）；App 被關閉之後沒有人比對，a2 不會響"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeApp:) 執行"
            }
            if last.hasPrefix("最後：手機收到 0 則通知") {
                return "createAlert 還沒把條件送給伺服器：呼叫 server.register(alert)"
            }
            return last == "最後：手機收到 2 則通知：a1、a2"
                ? nil
                : "App 不判斷價格：createAlert 只要 server.register(alert)，a1、a2 都由伺服器推播"
        }
    )
}
