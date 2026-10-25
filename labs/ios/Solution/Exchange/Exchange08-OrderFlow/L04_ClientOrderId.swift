// L04：使用者只按了一次「買入」，伺服器卻有三張訂單（自動重試時，
// 每次都產生新的 client order id）
// 對應複習站：Exchange08OrderFlow 第 3 題「client order id
// 是什麼？為什麼它能讓重送不會變成兩筆？」
//
// 情境：使用者按一次「買入」。網路層最多自動重試 3 次：第 1、2
// 次伺服器其實收到了、也記下了這張單，但回應在路上遺失（逾時）；第 3
// 次才收到回應。
//       假伺服器的規則（和這套 API 的 `client-order-id` 一樣）：
// 同一個編號只會記一張單；沒有帶編號（空字串）的請求會被拒絕。
//       （逾時之後先用查詢確認再決定重不重送，Platform05 演練 5 練過；
// 這個演練看的是：自動重試時，編號什麼時候產生。）
// Bug 版：每一次網路嘗試都產生一個新的編號（UUID）。伺服器看到三個不同的編號，
// 記成三張訂單。
// 修正版：使用者按下「買入」的那一刻就產生編號，存在草稿裡；
// 之後每一次重試都用同一個編號，伺服器只記一張單。
import Foundation
import LabKit

/// 假伺服器：同一個編號只記一張單，沒有編號（空字串）的請求拒絕
private final class FakeServer {
    private var ids: Set<String> = []
    private(set) var rejected = 0

    func place(clientOrderId: String) {
        if clientOrderId.isEmpty {
            rejected += 1
        } else {
            ids.insert(clientOrderId)
        }
    }

    var orderCount: Int { ids.count }
}

private protocol OrderSender: AnyObject {
    /// 使用者按下「買入」（只按一次）
    func tapBuy()
    /// 第 attempt 次網路嘗試要帶的 client order id
    func clientOrderId(attempt: Int) -> String
}

/// 演練流程（兩個版本共用）：按一次買入，網路層自動重試最多 3 次
private func scenario(_ log: LabLog, sender: OrderSender) {
    let server = FakeServer()
    sender.tapBuy()
    log("使用者按下「買入」（只按了一次）")
    var labels: [String: String] = [:]
    for attempt in 1...3 {
        let id = sender.clientOrderId(attempt: attempt)
        server.place(clientOrderId: id)
        let label =
            id.isEmpty
            ? "（沒有編號）"
            : (labels[id]
                ?? {
                    let name = "編號 \(["A", "B", "C"][labels.count])";
                    labels[id] = name; return name
                }())
        log(
            "第 \(attempt) 次嘗試：帶\(label) → \(attempt < 3 ? "伺服器收到並記下了，但回應逾時" : "收到回應")"
        )
    }
    log(
        "最後：伺服器上有 \(server.orderCount) 張訂單（使用者只按了一次），3 次嘗試用了 \(labels.count) 種不同的編號，沒帶編號而被拒絕 \(server.rejected) 次"
    )
}

// MARK: - Bug 版
private enum L04Bug {
    final class Sender: OrderSender {
        func tapBuy() {}

        func clientOrderId(attempt: Int) -> String {
            // 每一次嘗試都產生新的編號
            UUID().uuidString
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, sender: Sender())
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Sender: OrderSender {
        private var draftId = ""

        func tapBuy() {
            // 按下按鈕的那一刻就產生，存在草稿裡
            draftId = UUID().uuidString
        }

        func clientOrderId(attempt: Int) -> String {
            // 每一次重試都用同一個
            draftId
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, sender: Sender())
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l04 = Lab(
        id: "L04",
        title: "只按一次買入，伺服器有三張訂單",
        question: "第 3 題：client order id 是什麼？為什麼它能讓重送不會變成兩筆？",
        goal:
            "client order id 是冪等鍵：在使用者按下按鈕、送出請求之前就產生，隨草稿保存，同一次意圖的所有重送都用同一個；不是每次網路重試就換。",
        steps: [
            "選「Bug 版」執行，看三次嘗試各帶什麼編號，伺服器最後有幾張訂單。",
            "想一想：伺服器靠什麼認出「這是同一次下單」？每次重試都換一個新的，伺服器還認得出來嗎？",
            "選「修正版」執行，三次嘗試要帶同一個編號，伺服器只有 1 張訂單。",
            "進階：這套 API 的 `client-order-id` 最長 64 個字元，8 小時內必須唯一。UUID 去掉 `-` 之後是 32 個字元，夠用。",
        ],
        expectBug:
            "三次嘗試分別帶編號 A、B、C，「最後：伺服器上有 3 張訂單（使用者只按了一次），3 次嘗試用了 3 種不同的編號」。",
        expectFixed: "三次嘗試都帶編號 A，「最後：伺服器上有 1 張訂單（使用者只按了一次），3 次嘗試用了 1 種不同的編號」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, sender:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：伺服器上有 3 張訂單（使用者只按了一次），3 次嘗試用了 3 種不同的編號")
            })
                ? nil
                : "Bug 還沒重現：clientOrderId(attempt:) 每次都回傳新的 UUID().uuidString，伺服器會記成三張單"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, sender:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：伺服器上有 1 張訂單（使用者只按了一次），3 次嘗試用了 1 種不同的編號")
            })
                ? nil
                : "tapBuy 時產生編號存進 draftId（UUID().uuidString），clientOrderId(attempt:) 每次都回傳 draftId"
        }
    )
}
