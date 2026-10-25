// L02：成交量比委託量還多，訂單卻顯示「已成交」（把伺服器給的累計成交量當成增量加起來）
// 對應複習站：Exchange08OrderFlow 第 2 題「訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？」
//
// 情境：委託量 1.0 BTC。伺服器每次推送「到目前為止一共成交多少」（累計值），依序到達：0.1、0.4、0.2（過期的）、0.4（重複的）、0.9。
//       正確的最終狀態：partialFilled，成交量 0.9；其中 0.2 和第二個 0.4 是過期或重複的，要忽略（2 個）。
// Bug 版：把每個 fill 的數字加到成交量上（增量的想法）：0.1、0.5、0.7、1.1、2.0。第 4 個事件就超過委託量，狀態變成 filled，最後成交量 2.0。
// 修正版：累計值只增不減：小於或等於目前成交量（過期、重複）或超過委託量（無效）的事件忽略；否則成交量換成這個累計值，等於委託量就是 filled，否則 partialFilled。
import Foundation
import LabKit

private enum Status: String {
    case submitted, partialFilled, filled
}

private struct Order {
    var status: Status
    var filled: Decimal
    let amount: Decimal
}

private protocol Reducer {
    /// 累計成交量 total 的 fill 事件到達：回傳新的訂單（事件要忽略就原樣回傳）
    func fill(_ order: Order, total: Decimal) -> Order
}

/// 演練流程（兩個版本共用）：五個 fill 事件依序到達
private func scenario(_ log: LabLog, reducer: Reducer) {
    var order = Order(status: .submitted, filled: e08("0"), amount: e08("1.0"))
    var ignored = 0
    for total in ["0.1", "0.4", "0.2", "0.4", "0.9"] {
        let next = reducer.fill(order, total: e08(total))
        let isIgnored = next.status == order.status && next.filled == order.filled
        if isIgnored { ignored += 1 }
        order = next
        log("fill(累計 \(total)) → \(order.status.rawValue) \(order.filled)\(isIgnored ? "（忽略）" : "")")
    }
    log("最後：\(order.status.rawValue) \(order.filled)，忽略了 \(ignored) 個過期或重複的事件（正確答案：partialFilled 0.9，忽略 2 個）")
}

// MARK: - Bug 版
private enum L02Bug {
    struct Merge: Reducer {
        func fill(_ order: Order, total: Decimal) -> Order {
            var next = order
            next.filled += total                                            // 把累計值當成增量加上去
            next.status = next.filled >= next.amount ? .filled : .partialFilled
            return next
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, reducer: Merge())
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct Merge: Reducer {
        func fill(_ order: Order, total: Decimal) -> Order {
            guard total > order.filled, total <= order.amount else { return order }     // 過期、重複、超過委託量：忽略
            var next = order
            next.filled = total                                             // 累計值直接取代，只增不減
            next.status = total == order.amount ? .filled : .partialFilled
            return next
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, reducer: Merge())
    }
}

// MARK: - 演練設定
extension Exchange08OrderFlow {
    static let l02 = Lab(
        id: "L02",
        title: "成交量比委託量還多，訂單卻顯示已成交",
        question: "第 2 題：訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？",
        goal: "伺服器給的成交量是「累計值」：只增不減，小於等於目前值的是過期或重複的事件，超過委託量的是無效的事件，都要忽略；用增量去加，重複和亂序的事件會讓成交量越加越多。",
        steps: [
            "選「Bug 版」執行，看每個 fill 事件之後的成交量，哪一步開始超過委託量 1.0？",
            "想一想：0.2 是過期的、第二個 0.4 是重複的，把它們加進去會怎樣？",
            "選「修正版」執行，最後要是 partialFilled 0.9，而且忽略了 2 個事件。",
            "進階：為什麼用累計值比增量好？（重複、亂序的事件都不會破壞成交量）",
        ],
        expectBug: "每個 fill 都加上去：0.1、0.5、0.7、1.1（超過委託量 1.0，狀態變成 filled）、2：「最後：filled 2，忽略了 0 個過期或重複的事件」。",
        expectFixed: "0.2 和第二個 0.4 顯示「（忽略）」：「最後：partialFilled 0.9，忽略了 2 個過期或重複的事件」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, reducer:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：filled 2，忽略了 0 個") })
                ? nil : "Bug 還沒重現：fill 要把 total 加到 filled 上（next.filled += total），並依 filled 是否大於等於 amount 決定 filled 或 partialFilled"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, reducer:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：partialFilled 0.9，忽略了 2 個") })
                ? nil : "total 小於等於 order.filled（過期、重複）或大於 order.amount（無效）就原樣回傳 order；否則 filled 換成 total，等於 amount 是 filled，否則 partialFilled"
        }
    )
}
