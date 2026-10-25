// L04：舊手機的 token 失效了，同一個帳號的其他手機也都收不到提醒（逐一推播時，一個失敗就整批中斷）
// 對應複習站：Design04PriceAlerts 第 5 題「有哪些例外情況？」（使用者有好幾台裝置、token 失效）
//
// 情境：一個使用者登記了三台裝置的推播 token，順序是：舊 iPhone（已經解除安裝，APNs 回報 token 無效）、iPad、新 iPhone。
//       價格兩次達到他的提醒條件，伺服器各發一次推播給他所有的裝置。
//       假的 APNs：送給無效的 token 會丟出 unregistered 錯誤（真的 APNs 是 HTTP 410，FCM 是 UNREGISTERED），其他 token 正常送達。
//
// 你要寫（Sender.send）：
//   ① Bug 版：用一個 for 迴圈依序 try apns.send(to:)，整個迴圈包在同一個 do/catch 裡。
//   ② 修正版：每個 token 各自 do/catch；收到 PushError.unregistered 就把這個 token 從 tokens 移除。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Design/Design04-PriceAlerts/L04_DeviceTokens.swift
import LabKit

private enum PushError: Error { case unregistered }

/// 假的 APNs（兩個版本共用）
private final class FakeAPNs {
    private let invalid: Set<String>
    private(set) var attempts = 0
    private var delivered: [String: Int] = [:]
    init(invalid: Set<String>) { self.invalid = invalid }

    func send(to token: String) throws {
        attempts += 1
        if invalid.contains(token) { throw PushError.unregistered }
        delivered[token, default: 0] += 1
    }

    func deliveredCount(_ token: String) -> Int { delivered[token, default: 0] }
}

/// 伺服器端：把一則提醒推播給這個使用者登記的所有裝置
private protocol AlertSender: AnyObject {
    var tokens: [String] { get }
    func send(alert: String)
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeSender: (FakeAPNs, [String]) -> AlertSender) {
    let apns = FakeAPNs(invalid: ["iPhone-old"])
    let sender = makeSender(apns, ["iPhone-old", "iPad", "iPhone-new"])
    log("登記的 token：\(sender.tokens.joined(separator: "、"))（iPhone-old 已經失效）")
    for alert in ["BTC 高於 10,000,000 円", "BTC 高於 10,100,000 円"] {
        sender.send(alert: alert)
        log("提醒「\(alert)」→ 累計嘗試送出 \(apns.attempts) 次；iPad 收到 \(apns.deliveredCount("iPad")) 則、iPhone-new 收到 \(apns.deliveredCount("iPhone-new")) 則；token 清單：\(sender.tokens.joined(separator: "、"))")
    }
    log("最後：伺服器共嘗試送出 \(apns.attempts) 次；iPad 收到 \(apns.deliveredCount("iPad")) 則、iPhone-new 收到 \(apns.deliveredCount("iPhone-new")) 則；失效的 token \(sender.tokens.contains("iPhone-old") ? "還在清單裡" : "已刪掉")")
}

// MARK: - Bug 版
private enum L04Bug {
    final class Sender: AlertSender {
        private let apns: FakeAPNs
        private(set) var tokens: [String]
        init(apns: FakeAPNs, tokens: [String]) { self.apns = apns; self.tokens = tokens }

        func send(alert: String) {
            // TODO ①：用一個 for 迴圈依序對 tokens 裡的每個 token 呼叫 try apns.send(to: token)，而且整個迴圈只包在一個 do/catch 裡
            //         （一個 token 失敗，後面的就都不送了；也不處理失效的 token）
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Sender(apns: $0, tokens: $1) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Sender: AlertSender {
        private let apns: FakeAPNs
        private(set) var tokens: [String]
        init(apns: FakeAPNs, tokens: [String]) { self.apns = apns; self.tokens = tokens }

        func send(alert: String) {
            // TODO ②：for 迴圈裡，每個 token 各自 do { try apns.send(to: token) } catch，失敗不影響其他裝置；
            //         catch PushError.unregistered 時，把這個 token 從 tokens 移除（下一則就不再送給它）
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Sender(apns: $0, tokens: $1) }
    }
}

// MARK: - 演練設定
extension Design04PriceAlerts {
    static let l04 = Lab(
        id: "L04",
        title: "舊手機的 token 失效，其他手機也收不到提醒",
        question: "第 5 題：有哪些例外情況？",
        goal: "一個使用者有好幾台裝置時，要推播給所有還有效的 token：每個 token 的失敗各自處理，不影響其他裝置；APNs／FCM 回報 token 無效時，把它從清單刪掉。先寫出整批中斷的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Design/Design04-PriceAlerts/L04_DeviceTokens.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看兩則提醒各送了幾次、iPad 和新 iPhone 各收到幾則、失效的 token 還在不在清單裡。",
            "想一想：失效的 token 排第一個，後面兩台為什麼都收不到？（丟出的錯誤跳出了整個迴圈。）",
            "寫 TODO ②（修正版），選「修正版」執行：iPad、新 iPhone 各收到 2 則；失效的 token 第一次失敗後就被刪掉，第二次只送 2 次。",
            "想一想：伺服器怎麼知道 token 失效？（APNs 回 HTTP 410 或 400 的 BadDeviceToken 這類錯誤，FCM 回 UNREGISTERED，實際的錯誤碼以官方文件為準。）",
        ],
        expectBug: "第一個 token 失敗就中斷整個迴圈，兩則提醒都只嘗試送給失效的 token，iPad 和新 iPhone 一則都沒收到：「最後：伺服器共嘗試送出 2 次；iPad 收到 0 則、iPhone-new 收到 0 則；失效的 token 還在清單裡」。",
        expectFixed: "第一則送 3 次（舊 iPhone 失敗後被刪掉），第二則只送 2 次：「最後：伺服器共嘗試送出 5 次；iPad 收到 2 則、iPhone-new 收到 2 則；失效的 token 已刪掉」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else { return "演練沒有跑完：要用 scenario(log, makeSender:) 執行" }
            if last.hasPrefix("最後：伺服器共嘗試送出 0 次") { return "send 還沒送出：用 for token in tokens { try apns.send(to: token) } 依序送" }
            return last == "最後：伺服器共嘗試送出 2 次；iPad 收到 0 則、iPhone-new 收到 0 則；失效的 token 還在清單裡"
                ? nil : "Bug 還沒重現：整個 for 迴圈包在同一個 do/catch 裡，送給舊 iPhone 失敗就跳出迴圈；而且沒有刪掉失效的 token"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else { return "演練沒有跑完：要用 scenario(log, makeSender:) 執行" }
            if last.hasPrefix("最後：伺服器共嘗試送出 0 次") { return "send 還沒送出：每個 token 各自 do { try apns.send(to: token) } catch" }
            return last == "最後：伺服器共嘗試送出 5 次；iPad 收到 2 則、iPhone-new 收到 2 則；失效的 token 已刪掉"
                ? nil : "每個 token 各自 do/catch；catch PushError.unregistered 時，把這個 token 從 tokens 移除（下一則就不再送給它）"
        }
    )
}
