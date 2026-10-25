// L04：App 被系統結束後回來，下單表單停在「送出中」，
// 還顯示三十分鐘前的報價（狀態還原）
// 對應複習站：Platform01Lifecycle 第 4 題「App 在背景被系統殺掉之後，
// 使用者回來會看到什麼？」
//
// 情境：使用者在下單頁選好 ETH/JPY、輸入數量 0.05，按下送出（請求還沒回來）
// 就切去 LINE。三十分鐘後 App 已經被系統結束（jetsam，沒有任何通知），
//        使用者從 App 圖示打開它。要還原的資料交給 NSUserActivity.userInfo（和
// SwiftUI 的 @SceneStorage 一樣，由系統存起來，App 重新啟動後交還）。
//        演練用 PropertyListSerialization 把它變成 Data 再讀回來，
// 模擬「存到磁碟、行程消失、重新啟動」。
//        （App 真的被結束再重開很難在自動檢查裡重現，所以用這種縮小版；
// 真機的觀察方式見複習站。）
// Bug 版：把整個表單原封不動存起來，包含「送出中」的旗標和當時的報價。
// 還原後按鈕永遠停在送出中（那個請求早就不在了），畫面上是三十分鐘前的報價。
// 修正版：只存使用者輸入的東西（交易對、數量）。報價回來後重新向伺服器取得，
// 「送出中」不還原。
import Foundation
import LabKit

/// 下單表單（兩個版本共用）
private struct OrderForm {
    var symbol = "btcjpy"
    var quantity = ""
    // 三十分鐘前向伺服器取得的報價
    var quotePrice: Int?
    // 已按下送出，請求還在途中
    var isSubmitting = false
}

/// 演練流程（兩個版本共用）：存起來 → 行程消失 → 重新啟動後還原
private func scenario(
    _ log: LabLog,
    save: (OrderForm) -> [String: Any],
    restore: ([String: Any]) -> OrderForm
) {
    // 第一個行程：使用者填到一半，已按下送出
    let form = OrderForm(
        symbol: "ethjpy", quantity: "0.05", quotePrice: 15_000_000,
        isSubmitting: true)
    let activity = NSUserActivity(activityType: "tw.harry.MobileLabs.order")
    activity.addUserInfoEntries(from: save(form))
    let onDisk = try? PropertyListSerialization.data(
        fromPropertyList: activity.userInfo ?? [:], format: .binary, options: 0)
    // ……行程被系統結束，記憶體裡的東西全部消失，只剩 onDisk……

    // 第二個行程：重新啟動，系統把存好的資料交回來
    let loaded =
        onDisk.flatMap {
            try? PropertyListSerialization.propertyList(
                from: $0, options: [], format: nil) as? [String: Any]
        } ?? [:]
    let restored = restore(loaded)
    log("還原後：交易對 \(restored.symbol)、數量 \(restored.quantity)")
    log("還原後：報價 \(restored.quotePrice.map(String.init) ?? "（沒有，要重新取得）")")
    log("還原後：按鈕\(restored.isSubmitting ? "停在送出中" : "可以操作")")
}

// MARK: - Bug 版
private enum L04Bug {
    static func save(_ form: OrderForm) -> [String: Any] {
        var info: [String: Any] = [
            "symbol": form.symbol, "quantity": form.quantity,
            "isSubmitting": form.isSubmitting,
        ]
        // 連當時的報價也存
        if let price = form.quotePrice { info["quotePrice"] = price }
        return info
    }

    static func restore(_ info: [String: Any]) -> OrderForm {
        OrderForm(
            symbol: info["symbol"] as? String ?? "btcjpy",
            quantity: info["quantity"] as? String ?? "",
            quotePrice: info["quotePrice"] as? Int,
            isSubmitting: info["isSubmitting"] as? Bool ?? false
        )
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save, restore: restore)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func save(_ form: OrderForm) -> [String: Any] {
        // 只存使用者輸入的東西
        ["symbol": form.symbol, "quantity": form.quantity]
    }

    static func restore(_ info: [String: Any]) -> OrderForm {
        OrderForm(
            symbol: info["symbol"] as? String ?? "btcjpy",
            quantity: info["quantity"] as? String ?? ""
                // 報價、送出中：留預設值，回來後重新取得
        )
    }

    static func run(_ log: LabLog) async {
        scenario(log, save: save, restore: restore)
    }
}

// MARK: - 演練設定
extension Platform01Lifecycle {
    static let l04 = Lab(
        id: "L04",
        title: "還原後停在送出中，還顯示舊報價",
        question: "第 4 題：App 在背景被系統殺掉之後，使用者回來會看到什麼？",
        goal: "App 被結束再打開，使用者輸入的交易對與數量要還原；報價要重新取得，不能停在「送出中」。",
        steps: [
            "選「Bug 版」按執行，看三行「還原後」：哪些不該還原卻被還原了？",
            "選「修正版」執行：只還原交易對與數量。",
            "真機觀察：把 App 放到背景後，從 Xcode 按停止鍵，再從主畫面點 App 圖示（見複習站第 4 題）。",
        ],
        expectBug: "數量 0.05 還原了，但報價還顯示 15000000（三十分鐘前的），按鈕停在送出中。",
        expectFixed: "交易對 ethjpy 與數量 0.05 還原；報價「（沒有，要重新取得）」；按鈕可以操作。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("還原後：交易對 ethjpy、數量 0.05") {
                return "Bug 版要把整個表單存起來，還原後數量是 0.05"
            }
            if !lines.contains("還原後：按鈕停在送出中") {
                return "Bug 還沒重現：Bug 版要連 isSubmitting 一起存、一起還原"
            }
            if !lines.contains("還原後：報價 15000000") {
                return "Bug 還沒重現：Bug 版要連 quotePrice 一起存、一起還原"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("還原後：交易對 ethjpy、數量 0.05") {
                return "使用者輸入的交易對與數量要還原：存 symbol 和 quantity"
            }
            if !lines.contains("還原後：報價 （沒有，要重新取得）") {
                return "報價會變，不要還原：不要存 quotePrice，回來後重新向伺服器取得"
            }
            if !lines.contains("還原後：按鈕可以操作") {
                return "「送出中」不要還原：請求早就不在了，還原會讓按鈕永遠停在送出中"
            }
            return nil
        }
    )
}
