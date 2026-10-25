// L02：價格一下子跳過門檻，提醒沒有響（用「等於」判斷，而不是「跨過」）
// 對應複習站：Design04PriceAlerts 第 3 題「伺服器端大概怎麼做？」
//
// 情境：伺服器替這個交易對保存一組提醒。「高於」提醒：A1 10,000,000、A2 10,100,
// 000、A3 10,200,000；「低於」提醒：B1 9,800,000、B2 9,700,000
//       （加入的順序是打亂的：A2、A1、A3、B2、B1）。價格依序變成 9,950,000、10,
// 030,000（一下子跳過 A1）、10,250,000（一下子跳過 A2 和 A3）、
//       10,250,000（伺服器重送了同一筆）、9,990,000、9,800,000（剛好等於 B1
// 的門檻）、9,600,000（跳過 B2）。
//       看每個新價格觸發了哪些提醒，以及最後一共觸發幾個（正確是 5 個，
// 每個只觸發一次）。
// Bug 版：用「門檻 → 提醒」的字典存，每個新價格去字典裡找「剛好等於這個價格」
// 的提醒。只有價格剛好踩到門檻才會響：
//        9,800,000 觸發了 B1，其他四個提醒因為價格直接跳過門檻，永遠不會響。
// 修正版：依方向各存一個排序好的清單（高於：門檻由小到大；低於：
// 門檻由大到小）。每個新價格只從清單最前面開始取，
//        門檻已經被跨過（價格 >= 門檻，或價格 <= 門檻）
// 的全部取出來並從清單移除（所以每個提醒只會觸發一次），遇到還沒跨過的就停。
import LabKit

private enum Direction { case above, below }

private struct Alert {
    let id: String
    let direction: Direction
    let threshold: Int
}

/// 伺服器端的提醒判斷器（依交易對分開，這裡只有一個交易對）
private protocol AlertBook: AnyObject {
    func add(_ alert: Alert)
    /// 來了一個新價格：回傳這次觸發的提醒 id
    func onPrice(_ price: Int) -> [String]
}

private let alerts = [
    Alert(id: "A2", direction: .above, threshold: 10_100_000),
    Alert(id: "A1", direction: .above, threshold: 10_000_000),
    Alert(id: "A3", direction: .above, threshold: 10_200_000),
    Alert(id: "B2", direction: .below, threshold: 9_700_000),
    Alert(id: "B1", direction: .below, threshold: 9_800_000),
]
private let prices = [
    9_950_000, 10_030_000, 10_250_000, 10_250_000, 9_990_000, 9_800_000,
    9_600_000,
]

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, book: AlertBook) {
    alerts.forEach(book.add)
    var all: [String] = []
    for price in prices {
        let fired = book.onPrice(price)
        all += fired
        log(
            "價格 \(d04Yen(price)) 円 → \(fired.isEmpty ? "沒有觸發" : "觸發 " + fired.joined(separator: "、"))"
        )
    }
    log(
        "最後：一共觸發 \(all.count) 個提醒：\(all.isEmpty ? "（沒有）" : all.joined(separator: "、"))"
    )
}

// MARK: - Bug 版
private enum L02Bug {
    final class Book: AlertBook {
        // 門檻 → 這個門檻的提醒
        private var byThreshold: [Int: [Alert]] = [:]

        func add(_ alert: Alert) {
            byThreshold[alert.threshold, default: []].append(alert)
        }

        func onPrice(_ price: Int) -> [String] {
            // 只有價格剛好等於門檻，才取得出來
            (byThreshold.removeValue(forKey: price) ?? []).map(\.id)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, book: Book())
    }
}

// MARK: - 修正版
private enum L02Fixed {
    final class Book: AlertBook {
        // 高於：門檻由小到大
        private var above: [Alert] = []
        // 低於：門檻由大到小
        private var below: [Alert] = []

        func add(_ alert: Alert) {
            switch alert.direction {
            case .above:
                above.append(alert)
                above.sort { $0.threshold < $1.threshold }
            case .below:
                below.append(alert)
                below.sort { $0.threshold > $1.threshold }
            }
        }

        func onPrice(_ price: Int) -> [String] {
            var fired: [Alert] = []
            // 門檻已經被跨過：取出來，同時從清單移除
            while let first = above.first, first.threshold <= price {
                fired.append(above.removeFirst())
            }
            while let first = below.first, first.threshold >= price {
                fired.append(below.removeFirst())
            }
            return fired.map(\.id)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, book: Book())
    }
}

// MARK: - 演練設定
extension Design04PriceAlerts {
    static let l02 = Lab(
        id: "L02",
        title: "價格一下子跳過門檻，提醒沒有響",
        question: "第 3 題：伺服器端大概怎麼做？",
        goal:
            "判斷要用「跨過」（價格 >= 門檻、價格 <= 門檻），不能用「等於」：價格可能直接從 99 跳到 101，從來沒有剛好等於 100。把門檻排好順序，每個新價格只取最前面已經被跨過的那幾個，取出來就移除，同一個提醒只會觸發一次。",
        steps: [
            "選「Bug 版」執行，看每個價格觸發了哪些提醒，最後一共觸發幾個。",
            "想一想：為什麼只有 9,800,000 觸發了？其他價格明明已經超過門檻。（字典只找得到「剛好等於」的門檻。）",
            "選「修正版」執行：10,250,000 一次觸發 A2、A3；重送的同一個價格不會再觸發；一共 5 個。",
            "想一想：門檻排好順序有什麼好處？（每個新價格只要從最前面取，取到沒跨過的就停，不用把全部提醒都檢查一次。）",
        ],
        expectBug: "只有價格剛好踩到門檻（9,800,000）才觸發 B1，其餘四個永遠不響：「最後：一共觸發 1 個提醒：B1」。",
        expectFixed:
            "10,030,000 觸發 A1；10,250,000 一次觸發 A2、A3，重送同一個價格不再觸發；9,800,000 觸發 B1；9,600,000 觸發 B2：「最後：一共觸發 5 個提醒：A1、A2、A3、B1、B2」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, book:) 執行"
            }
            if last.hasPrefix("最後：一共觸發 0 個提醒") {
                return
                    "add 要把提醒依門檻存進 byThreshold，onPrice 要用 removeValue(forKey: price) 取出剛好等於價格的提醒（9,800,000 要觸發 B1）"
            }
            return last == "最後：一共觸發 1 個提醒：B1"
                ? nil : "Bug 還沒重現：onPrice 只取出「門檻剛好等於價格」的提醒，價格跳過門檻的提醒就不會響"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, book:) 執行"
            }
            if last.hasPrefix("最後：一共觸發 0 個提醒") {
                return "add 要把提醒依方向放進 above／below 並排好順序，onPrice 要把已經跨過門檻的提醒取出來"
            }
            return last == "最後：一共觸發 5 個提醒：A1、A2、A3、B1、B2"
                ? nil
                : "above 依門檻由小到大、below 由大到小排好；onPrice 從最前面取出 threshold <= price（below 是 >=）的提醒，取出來就從清單移除"
        }
    )
}
