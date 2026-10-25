// L01：下單回應才剛回來，畫面就顯示「已成交」，其實根本沒成交（把下單 API
// 的成功當成成交）
// 對應複習站：Exchange08OrderFlow 第 1 題「下單 API 回傳成功，
// 代表訂單成交了嗎？」
//
// 情境：使用者按下「買入」，送出一張 IOC（immediate or cancel：
// 能成交的立刻成交，剩下的立刻取消）買單，數量 0.01 BTC。
//       這時板上沒有符合價格的賣單。下單回應 `{"status":"ok","data":"59378"}`
// 很快就回來，只代表伺服器「受理」了這張單；
//       約 0.3 秒後，私有 WebSocket 的訂單事件才通知：這張單被取消了，成交量
// 0。
// Bug 版：收到下單回應就把畫面改成「已成交」，成交量填委託量 0.01，
// 還彈出一次成交通知。
// 修正版：下單回應只代表受理：畫面顯示「已送出，確認中」，成交量維持 0；
// 等訂單事件來了，才依事件更新。
import Foundation
import LabKit

private enum ServerEvent {
    // IOC 沒成交的部分被取消，filled 是累計成交量
    case canceled(filled: Decimal)
}

/// 下單畫面的狀態。`onEvent` 已經寫好：收到取消事件，依伺服器給的成交量更新。
private class OrderScreen {
    var status = "尚未送出"
    var filled = e08("0")
    var fillNotifications = 0

    func onPlaceResponse(orderId: String, requestedAmount: Decimal) {}

    func onEvent(_ event: ServerEvent) {
        switch event {
        case .canceled(let filledAmount):
            status = "已取消"
            filled = filledAmount
        }
    }
}

/// 演練流程（兩個版本共用）：下單回應先到，訂單事件 0.3 秒後才到
private func scenario(_ log: LabLog, screen: OrderScreen) {
    log("使用者按下「買入」：IOC 買單 0.01 BTC，板上沒有符合價格的賣單")
    screen.onPlaceResponse(orderId: "59378", requestedAmount: e08("0.01"))
    let afterAck = screen.status
    log("下單回應 ok，訂單 ID 59378 → 畫面「\(afterAck)」，成交量 \(screen.filled) BTC")
    log("（0.3 秒後）私有 WebSocket 通知：訂單被取消，成交量 0")
    screen.onEvent(.canceled(filled: e08("0")))
    log("收到訂單事件 → 畫面「\(screen.status)」，成交量 \(screen.filled) BTC")
    log(
        "最後：下單回應後畫面顯示「\(afterAck)」，成交通知 \(screen.fillNotifications) 次，最終狀態「\(screen.status)」"
    )
}

// MARK: - Bug 版
private enum L01Bug {
    final class Screen: OrderScreen {
        override func onPlaceResponse(orderId: String, requestedAmount: Decimal)
        {
            // 把「受理」當成「成交」
            status = "已成交"
            filled = requestedAmount
            fillNotifications += 1
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: Screen())
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class Screen: OrderScreen {
        override func onPlaceResponse(orderId: String, requestedAmount: Decimal)
        {
            // 下單回應只代表伺服器受理，成交量維持 0，等訂單事件
            status = "已送出，確認中"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: Screen())
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l01 = Lab(
        id: "L01",
        title: "下單回應一回來就顯示「已成交」",
        question: "第 1 題：下單 API 回傳成功，代表訂單成交了嗎？",
        goal:
            "下單回應只有訂單 ID，代表伺服器「受理」了這張單；成交、部分成交、取消，都要靠後續的訂單事件或查詢確認。畫面先顯示「已送出，確認中」。",
        steps: [
            "選「Bug 版」執行，看下單回應回來的那一刻，畫面顯示什麼、成交量多少、有沒有彈出成交通知。",
            "想一想：使用者那一刻看到「已成交」，他會怎麼做？0.3 秒後訂單其實是被取消的。",
            "選「修正版」執行，下單回應回來時要顯示「已送出，確認中」，成交通知 0 次，之後才依事件變成「已取消」。",
            "進階：`trade.clearing` 不保證順序，順序有依賴時要用 `orders#`（見下一題）。",
        ],
        expectBug:
            "下單回應後畫面顯示「已成交」，成交量 0.01，彈出 1 次成交通知，0.3 秒後才被更正成「已取消」：「最後：下單回應後畫面顯示「已成交」，成交通知 1 次，最終狀態「已取消」」。",
        expectFixed:
            "下單回應後畫面顯示「已送出，確認中」，成交通知 0 次：「最後：下單回應後畫面顯示「已送出，確認中」，成交通知 0 次，最終狀態「已取消」」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, screen:) 執行"
            }
            return lines.contains("最後：下單回應後畫面顯示「已成交」，成交通知 1 次，最終狀態「已取消」")
                ? nil
                : "Bug 還沒重現：onPlaceResponse 收到回應就把 status 設成「已成交」、filled 設成 requestedAmount，並讓 fillNotifications 加 1"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, screen:) 執行"
            }
            return lines.contains("最後：下單回應後畫面顯示「已送出，確認中」，成交通知 0 次，最終狀態「已取消」")
                ? nil
                : "onPlaceResponse 只把 status 設成「已送出，確認中」，不要動 filled 和 fillNotifications"
        }
    )
}
