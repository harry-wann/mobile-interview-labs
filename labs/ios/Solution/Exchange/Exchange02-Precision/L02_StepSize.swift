// L02：按「全部賣出」，數量卻被進位，伺服器說餘額不足（數量要往下對齊 step
// size）
// 對應複習站：Exchange02Precision 第 2 題「交易所對價格和數量有哪些規則？App
// 要怎麼對齊？」
//
// 情境：交易對的 step size（數量最小單位）是 0.0001 BTC，最小下單量也是 0.0001
// BTC。
//       使用者按「全部賣出」，App 要把持有量對齊 step size 再送出。兩種情況：
// 持有 0.12349999 BTC、持有 0.00009 BTC。
//       假伺服器：數量超過持有量就拒絕（餘額不足）。
// Bug 版：用四捨五入對齊：0.12349999 → 0.1235，比持有量多，被伺服器拒絕；0.
// 00009 → 0.0001，也比持有量多，被拒絕。
// 修正版：往下對齊：0.12349999 → 0.1234，可以成交；0.00009 → 0，
// 不到最小下單量，App 直接提示使用者，不送出。
import Foundation
import LabKit

private let step = e02("0.0001")
private let minQty = e02("0.0001")

/// 演練流程（兩個版本共用）：兩種持有量各按一次「全部賣出」
private func scenario(_ log: LabLog, align: (Decimal, Decimal) -> Decimal) {
    var sent = 0
    var refused = 0
    var blocked = 0
    for text in ["0.12349999", "0.00009"] {
        let holding = e02(text)
        let qty = align(holding, step)
        if qty < minQty {
            blocked += 1
            log("持有 \(holding) BTC，對齊後 \(qty)：不到最小下單量 \(minQty)，App 提示使用者，不送出")
            continue
        }
        sent += 1
        if qty > holding {
            refused += 1
            log("持有 \(holding) BTC，對齊後 \(qty)：伺服器拒絕（餘額不足）")
        } else {
            log("持有 \(holding) BTC，對齊後 \(qty)：伺服器受理")
        }
    }
    log("送到伺服器 \(sent) 筆，被拒絕 \(refused) 筆，App 端提示最小下單量 \(blocked) 次")
}

// MARK: - Bug 版
private enum L02Bug {
    static func align(_ qty: Decimal, step: Decimal) -> Decimal {
        var units = qty / step  // 有幾個 step
        var result = Decimal()
        // 四捨五入：1234.9999 → 1235
        NSDecimalRound(&result, &units, 0, .plain)
        return result * step
    }

    static func run(_ log: LabLog) async {
        scenario(log, align: align(_:step:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func align(_ qty: Decimal, step: Decimal) -> Decimal {
        var units = qty / step
        var result = Decimal()
        // 往下取整：1234.9999 → 1234
        NSDecimalRound(&result, &units, 0, .down)
        return result * step
    }

    static func run(_ log: LabLog) async {
        scenario(log, align: align(_:step:))
    }
}

// MARK: - 演練設定
extension Exchange02Precision {
    static let l02 = Lab(
        id: "L02",
        title: "按「全部賣出」，數量被進位後被拒絕",
        question: "第 2 題：交易所對價格和數量有哪些規則？App 要怎麼對齊？",
        goal:
            "數量要對齊 step size，而且一律往下對齊，才不會超過持有量。往下對齊後不到最小下單量，要在 App 提示使用者，不要送出。",
        steps: [
            "選「Bug 版」執行，看兩種持有量對齊後的數量、伺服器的反應。",
            "想一想：0.12349999 ÷ 0.0001 = 1234.9999，四捨五入會變成多少？比持有量大還是小？",
            "選「修正版」執行，看 0.12349999 和 0.00009 各自的結果。",
            "進階：這裡的 NSDecimalRound 是 Decimal 的做法；Android 用 BigDecimal.divide(step, 0, RoundingMode.DOWN)。",
        ],
        expectBug:
            "「持有 0.12349999 BTC，對齊後 0.1235：伺服器拒絕（餘額不足）」「持有 0.00009 BTC，對齊後 0.0001：伺服器拒絕」，「送到伺服器 2 筆，被拒絕 2 筆」。",
        expectFixed:
            "「對齊後 0.1234：伺服器受理」「持有 0.00009 BTC，對齊後 0：不到最小下單量，App 提示使用者」，「送到伺服器 1 筆，被拒絕 0 筆，App 端提示最小下單量 1 次」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("送到伺服器") }) {
                return "演練沒有跑完：要用 scenario(log, align:) 執行"
            }
            return lines.contains("送到伺服器 2 筆，被拒絕 2 筆，App 端提示最小下單量 0 次")
                ? nil
                : "Bug 還沒重現：用 NSDecimalRound(…, 0, .plain) 四捨五入，兩種持有量對齊後都會比持有量大，兩筆都被伺服器拒絕"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("送到伺服器") }) {
                return "演練沒有跑完：要用 scenario(log, align:) 執行"
            }
            return lines.contains("送到伺服器 1 筆，被拒絕 0 筆，App 端提示最小下單量 1 次")
                ? nil
                : "要往下對齊（NSDecimalRound 的模式用 .down）：0.12349999 → 0.1234 可以成交，0.00009 → 0 要在 App 端提示"
        }
    )
}
