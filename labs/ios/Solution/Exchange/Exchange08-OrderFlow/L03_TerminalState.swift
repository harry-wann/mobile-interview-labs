// L03：訂單明明取消了，畫面卻又變回「委託中」，後來還變成「部分成交」
// （終態被晚到的事件覆蓋）
// 對應複習站：Exchange08OrderFlow 第 2 題「訂單有哪些狀態？事件亂序或重複時，
// 怎麼合併成正確的狀態？」
//
// 情境：委託量 2.0 BTC，本地狀態從 pending（本地已送出，還沒收到 ack）開始。
// 私有推送不保證順序，事件依序到達：
//       accepted（受理）→ fill 累計 0.5 → canceled（取消，已有成交量，所以是
// partialCanceled）→ accepted（晚到的重複事件）→ fill 累計 0.3（過期）→ fill
// 累計 1.2。
//       正確的最終狀態：partialCanceled，成交量 1.2：
// 取消的通知比最後一筆成交先到，成交量照常更新，但狀態不會退回 partialFilled；
// 晚到的 accepted 和過期的 fill 都忽略（2 個）。
//       （成交量只增不減的規則，已經寫好。）
// Bug 版：accepted 一律把狀態改成 submitted；fill 之後的狀態一律是
// partialFilled（沒有滿量的話）。終態被覆蓋：取消之後又變回 submitted，
// 最後變成 partialFilled。
// 修正版：accepted 只有 pending 才變成 submitted；fill 之後，canceling 維持
// canceling，canceled 或 partialCanceled 維持 partialCanceled，其他才是
// partialFilled。
import Foundation
import LabKit

private enum Status: String {
    case pending, submitted, partialFilled, filled, canceling, canceled,
        partialCanceled
}

private struct Order {
    var status: Status
    var filled: Decimal
    let amount: Decimal
}

private enum Event {
    case accepted
    case fill(total: Decimal)
    case canceled
}

private protocol Reducer {
    func reduce(_ order: Order, _ event: Event) -> Order
}

private func describe(_ event: Event) -> String {
    switch event {
    case .accepted: "accepted"
    case .fill(let total): "fill(累計 \(total))"
    case .canceled: "canceled"
    }
}

/// 演練流程（兩個版本共用）：六個事件依序到達
private func scenario(_ log: LabLog, reducer: Reducer) {
    var order = Order(status: .pending, filled: e08("0"), amount: e08("2.0"))
    var ignored = 0
    let events: [Event] = [
        .accepted, .fill(total: e08("0.5")), .canceled, .accepted,
        .fill(total: e08("0.3")), .fill(total: e08("1.2")),
    ]
    for event in events {
        let next = reducer.reduce(order, event)
        let isIgnored =
            next.status == order.status && next.filled == order.filled
        if isIgnored { ignored += 1 }
        order = next
        log(
            "\(describe(event)) → \(order.status.rawValue) \(order.filled)\(isIgnored ? "（忽略）" : "")"
        )
    }
    log(
        "最後：\(order.status.rawValue) \(order.filled)，忽略了 \(ignored) 個事件（正確答案：partialCanceled 1.2，忽略 2 個）"
    )
}

// MARK: - Bug 版
private enum L03Bug {
    struct Merge: Reducer {
        func reduce(_ order: Order, _ event: Event) -> Order {
            var next = order
            switch event {
            case .accepted:
                // 不管現在是什麼狀態，一律變成 submitted
                next.status = .submitted
            case .fill(let total):
                guard total > order.filled, total <= order.amount else {
                    return order
                }
                next.filled = total
                // 一律變成 partialFilled
                next.status = total == order.amount ? .filled : .partialFilled
            case .canceled:
                switch order.status {
                case .pending, .submitted, .partialFilled, .canceling:
                    next.status =
                        order.filled > 0 ? .partialCanceled : .canceled
                default: break
                }
            }
            return next
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, reducer: Merge())
    }
}

// MARK: - 修正版
private enum L03Fixed {
    struct Merge: Reducer {
        func reduce(_ order: Order, _ event: Event) -> Order {
            var next = order
            switch event {
            case .accepted:
                // 只有 pending 才變成 submitted
                if order.status == .pending { next.status = .submitted }
            case .fill(let total):
                guard total > order.filled, total <= order.amount else {
                    return order
                }
                next.filled = total
                if total == order.amount {
                    next.status = .filled
                } else {
                    // 取消中或已取消的訂單，成交量更新了，狀態不能退回
                    // partialFilled
                    switch order.status {
                    case .canceling: next.status = .canceling
                    case .canceled, .partialCanceled:
                        next.status = .partialCanceled
                    default: next.status = .partialFilled
                    }
                }
            case .canceled:
                switch order.status {
                case .pending, .submitted, .partialFilled, .canceling:
                    next.status =
                        order.filled > 0 ? .partialCanceled : .canceled
                default: break
                }
            }
            return next
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, reducer: Merge())
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l03 = Lab(
        id: "L03",
        title: "訂單取消了，又變回委託中",
        question: "第 2 題：訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？",
        goal:
            "終態不可離開：已經 canceled／partialCanceled／filled 的訂單，晚到的 accepted、過期的事件都不能把狀態改回去；成交量照常更新，但狀態要維持取消的終態。",
        steps: [
            "選「Bug 版」執行，看每個事件之後的狀態，哪一步開始，取消的訂單又復活了？",
            "想一想：canceled 之後，晚到的 accepted 代表什麼？最後的 fill 累計 1.2 是取消之前就成交的，還是取消之後才成交的？",
            "選「修正版」執行，最後要是 partialCanceled 1.2，忽略了 2 個事件。",
            "進階：文件警告 `trade.clearing` 不保證順序，所以 reducer 要能抵抗亂序：成交先於 ack、取消先於最後一筆成交，都要能收斂到正確的最終狀態。",
        ],
        expectBug:
            "canceled 之後，晚到的 accepted 把狀態改回 submitted，最後的 fill 變成 partialFilled：「最後：partialFilled 1.2，忽略了 1 個事件」。",
        expectFixed:
            "晚到的 accepted 與過期的 fill 0.3 顯示「（忽略）」，fill 1.2 更新成交量但狀態維持 partialCanceled：「最後：partialCanceled 1.2，忽略了 2 個事件」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, reducer:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：partialFilled 1.2，忽略了 1 個事件")
            })
                ? nil
                : "Bug 還沒重現：accepted 一律把 status 改成 submitted；fill（沒滿量）之後一律是 partialFilled"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, reducer:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：partialCanceled 1.2，忽略了 2 個事件")
            })
                ? nil
                : "accepted 只有 pending 才變 submitted（其他狀態忽略）；fill 更新成交量後，canceling 維持 canceling，canceled 或 partialCanceled 維持 partialCanceled，其他才是 partialFilled"
        }
    )
}
