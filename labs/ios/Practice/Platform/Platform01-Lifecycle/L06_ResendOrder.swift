// L06：回到前景又送了一次，同一筆委託下了兩張單（用 clientOrderId 查詢，不要重送）
// 對應複習站：Platform01Lifecycle 第 6 題「交易所 App 在進背景、回前景時，要做哪些事？」
//
// 情境：使用者按下買入，請求送出去的瞬間 App 被切到背景，回應沒有收到。App 不知道伺服器有沒有收到這筆單。
//        回到前景時要處理這筆「送出狀態不明」的委託。演練跑兩種情況：伺服器其實已經收到了、伺服器根本沒收到。
//
// 你要寫：
//   ① Bug 版：回前景時發現上次送出沒有等到結果，就再送一次（新的 clientOrderId）。
//   ② 修正版：先用 clientOrderId 向伺服器查詢；查不到才用同一個 clientOrderId 重送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform01-Lifecycle/L06_ResendOrder.swift
import Foundation
import LabKit

/// 假的交易所（兩個版本共用）：用 clientOrderId 辨識委託；同一個 clientOrderId 送第二次會被拒絕
private final class Exchange {
    private(set) var orders: [String] = []

    func place(clientOrderId: String) -> Bool {
        if orders.contains(clientOrderId) { return false }      // 重複的 clientOrderId：拒絕
        orders.append(clientOrderId)
        return true
    }

    func query(clientOrderId: String) -> Bool {
        orders.contains(clientOrderId)
    }
}

/// 下單頁的狀態（兩個版本共用）：記得最後一張「送出狀態不明」的委託
private final class OrderScreen {
    let exchange: Exchange
    private(set) var pendingOrderId: String?

    init(exchange: Exchange) {
        self.exchange = exchange
    }

    /// 按下買入。requestReachesServer：請求有沒有真的送到伺服器（演練用來模擬進背景時的網路狀況）
    func tapBuy(requestReachesServer: Bool) {
        let id = UUID().uuidString
        pendingOrderId = id                                    // 先記下來：回應還沒收到
        if requestReachesServer { _ = exchange.place(clientOrderId: id) }
        // 回應沒有收到（App 進了背景）：pendingOrderId 維持不變
    }

    /// 送一張新的委託（每次都產生新的 clientOrderId）
    func placeOrder() {
        let id = UUID().uuidString
        pendingOrderId = id
        _ = exchange.place(clientOrderId: id)
    }

    func confirm() {
        pendingOrderId = nil
    }
}

/// 演練流程（兩個版本共用）：兩種情況，各自看伺服器最後有幾張單
private func scenario(_ log: LabLog, onForeground: (OrderScreen) -> Void) {
    let received = Exchange()
    let screenA = OrderScreen(exchange: received)
    screenA.tapBuy(requestReachesServer: true)                  // 伺服器收到了，但 App 沒等到回應就進背景
    onForeground(screenA)                                       // 回到前景
    log("伺服器已經收到的情況：回前景後伺服器共有 \(received.orders.count) 張單")

    let lost = Exchange()
    let screenB = OrderScreen(exchange: lost)
    screenB.tapBuy(requestReachesServer: false)                 // 請求根本沒送到伺服器
    onForeground(screenB)
    log("請求沒有送到的情況：回前景後伺服器共有 \(lost.orders.count) 張單")
}

// MARK: - Bug 版
private enum L06Bug {
    static func onForeground(_ screen: OrderScreen) {
        // TODO ①：如果 screen.pendingOrderId 不是 nil（上次送出沒有等到結果），就再呼叫 screen.placeOrder() 送一次
    }

    static func run(_ log: LabLog) async {
        scenario(log, onForeground: onForeground)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func onForeground(_ screen: OrderScreen) {
        guard let id = screen.pendingOrderId else { return }
        // TODO ②：先用 screen.exchange.query(clientOrderId: id) 查。查得到就只要 screen.confirm()；
        //        查不到才用同一個 id 呼叫 screen.exchange.place(clientOrderId:) 重送，再 screen.confirm()
    }

    static func run(_ log: LabLog) async {
        scenario(log, onForeground: onForeground)
    }
}

// MARK: - 演練設定
private func orderCount(_ lines: [String], prefix: String) -> Int? {
    lines.first { $0.hasPrefix(prefix) }
        .flatMap { Int($0.replacingOccurrences(of: prefix, with: "").replacingOccurrences(of: " 張單", with: "")) }
}

extension Platform01Lifecycle {
    static let l06 = Lab(
        id: "L06",
        title: "回到前景又送一次，下了兩張單",
        question: "第 6 題：交易所 App 在進背景、回前景時，要做哪些事？",
        goal: "送出狀態不明的委託，回到前景時要先用 clientOrderId 查詢：伺服器已經有就不重送，沒有才用同一個 clientOrderId 補送。兩種情況最後都只能有 1 張單。先寫出重複下單的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform01-Lifecycle/L06_ResendOrder.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「伺服器已經收到的情況」共有幾張單。",
            "寫 TODO ②（修正版），選「修正版」執行：兩種情況都是 1 張單。",
            "想一想：為什麼重送時要沿用同一個 clientOrderId？（即使查詢和重送之間又發生什麼事，伺服器也能用它擋掉重複的單）",
        ],
        expectBug: "伺服器已經收到的情況：回前景後共有 2 張單（重複下單）；請求沒有送到的情況是 1 張。",
        expectFixed: "兩種情況，伺服器都只有 1 張單。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let a = orderCount(lines, prefix: "伺服器已經收到的情況：回前景後伺服器共有 ") else { return "演練沒有跑完" }
            if a < 2 { return "Bug 還沒重現：回前景時發現 pendingOrderId 不是 nil，就再呼叫 placeOrder() 重送" }
            return nil
        },
        checkFixed: { lines in
            guard let a = orderCount(lines, prefix: "伺服器已經收到的情況：回前景後伺服器共有 "),
                  let b = orderCount(lines, prefix: "請求沒有送到的情況：回前景後伺服器共有 ") else { return "演練沒有跑完" }
            if a != 1 { return "伺服器已經收到的情況，結果有 \(a) 張單：先用 exchange.query(clientOrderId:) 查，查得到就不要送" }
            if b != 1 { return "請求沒有送到的情況，結果有 \(b) 張單：查不到時，要用同一個 clientOrderId 重送一次" }
            return nil
        }
    )
}
