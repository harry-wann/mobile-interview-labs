// L06：剛好對齊的價格，被歸到下面一檔（合併價格用 Double 做除法再取整）
// 對應複習站：Exchange05OrderBook 第 4 題「依價格間隔（step）合併時，買方、
// 賣方各往哪個方向取整？」
//
// 情境：一檔價格在 80 円左右的幣，買方要合併成每 0.1 円一檔（step = 0.1），
// 往下取整，同一檔的數量相加。
//       買方：80.37×0.2、80.30×0.5、80.29×0.1。80.30 已經對齊 0.1，應該留在 80.
// 3 這一檔；
//       正確的合併結果：80.3×0.7（80.37 和 80.30）、80.2×0.1（80.29）。
//
// 你要寫：
//   ① Bug 版（L06Bug.bucket）：用 Double：(price / step) 往下取整（.rounded(.
// down)），再乘回 step。
//   ② 修正版（L06Fixed.bucket）：用 Decimal：price / step 之後用
// NSDecimalRound 往下取整（.down），再乘回 step。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange05-OrderBook/L06_DoubleBucket.swift
import Foundation
import LabKit

private let rawBids = [("80.37", "0.2"), ("80.30", "0.5"), ("80.29", "0.1")]
private let answer = "80.3×0.7、80.2×0.1"

private func scenario(_ log: LabLog, merged: String) {
    log(
        "合併前的買方：" + rawBids.map { "\($0.0)×\($0.1)" }.joined(separator: "、")
            + "（step 0.1，往下取整）")
    log("合併後的買方：\(merged)")
    log("最後：\(merged == answer ? "和正確答案一致" : "和正確答案不一致，正確答案是 \(answer)")")
}

// MARK: - Bug 版
private enum L06Bug {
    static func bucket(_ price: Double, step: Double) -> Double {
        // TODO ①：用 Double 算：price ÷ step，往下取整（.rounded(.down)），
        // 再乘回 step。目前直接回傳 price 只是佔位
        price
    }

    static func run(_ log: LabLog) async {
        var merged: [Double: Double] = [:]
        for (price, qty) in rawBids {
            merged[bucket(Double(price)!, step: 0.1), default: 0] += Double(
                qty)!
        }
        let text = merged.sorted { $0.key > $1.key }
            .map { String(format: "%.1f×%.1f", $0.key, $0.value) }.joined(
                separator: "、")
        scenario(log, merged: text)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func bucket(_ price: Decimal, step: Decimal) -> Decimal {
        // TODO ②：用 Decimal 算：q = price / step，用 NSDecimalRound(&r, &q,
        // 0, down) 往下取整成 r，再回傳 r * step
        return price
    }

    static func run(_ log: LabLog) async {
        var merged: [Decimal: Decimal] = [:]
        for (price, qty) in rawBids {
            merged[bucket(e05(price), step: e05("0.1")), default: 0] += e05(qty)
        }
        let text = merged.sorted { $0.key > $1.key }
            .map { "\($0.key)×\($0.value)" }.joined(separator: "、")
        scenario(log, merged: text)
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l06 = Lab(
        id: "L06",
        title: "剛好對齊的價格被歸到下面一檔",
        question: "第 4 題：依價格間隔（step）合併時，買方、賣方各往哪個方向取整？",
        goal:
            "合併價格要用十進位型別（Decimal／BigDecimal）做除法和取整。用 Double 的 floor，剛好對齊的價格會因為誤差少算一格。先寫出用 Double 計算的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange05-OrderBook/L06_DoubleBucket.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看合併後的買方：80.30 這一筆被歸到哪一檔？",
            "想一想：80.30 ÷ 0.1 在 Double 裡等於多少？往下取整會變成幾？（可以自己印出來看）",
            "寫 TODO ②（修正版），選「修正版」執行，要得到 80.3×0.7、80.2×0.1。",
            "進階：賣方往上取整（ceil）也有問題：80.30 算出來是 80.30000000000001，拿它當字典的 key，會和另一個來源寫的 80.3 分成兩檔。",
        ],
        expectBug:
            "合併後的買方是「80.3×0.2、80.2×0.6」：剛好對齊的 80.30 被歸到 80.2，「和正確答案不一致」。",
        expectFixed: "合併後的買方是「80.3×0.7、80.2×0.1」，「和正確答案一致」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, merged:) 執行"
            }
            return lines.contains("合併後的買方：80.3×0.2、80.2×0.6")
                ? nil
                : "Bug 還沒重現：bucket 用 Double 計算 (price / step).rounded(.down) * step，80.30 會被歸到 80.2"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, merged:) 執行"
            }
            return lines.contains("合併後的買方：80.3×0.7、80.2×0.1")
                ? nil
                : "bucket 要用 Decimal：price / step 之後用 NSDecimalRound(&r, &q, 0, .down) 取整，再乘回 step"
        }
    )
}
