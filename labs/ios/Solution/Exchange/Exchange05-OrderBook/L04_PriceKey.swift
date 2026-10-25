// L04：同一個價位在板上分成兩檔（用價格的文字當字典的 key）
// 對應複習站：Exchange05OrderBook 第 3
// 題「本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？」
//
// 情境：買方板的快照（REST）把價格寫成 "4860000.0"、"4859000.0"；之後
// WebSocket 的更新把同一個價格寫成 "4860000"（數量變 0.9）、
//       "4859000.00"（數量 0，刪除這一檔），又新增 "4858000"（數量 0.3）。
// 同一個價格，不同的寫法，兩個來源很常不一致。
//       伺服器的板：4860000×0.9、4858000×0.3，共 2 檔。
//       （Android 的 `BigDecimal` 當 `HashMap` 的 key 也有一樣的問題：`equals`
// 連小數位數一起比。）
// Bug 版：用價格的文字當字典的 key。"4860000.0" 與 "4860000" 是兩個不同的字串，
// 同一個價位分成兩檔；"4859000.00" 刪不到 "4859000.0"。
// 修正版：先把文字轉成 Decimal，用 Decimal 當 key。Decimal 比的是數值，4860000.
// 0 與 4860000 是同一個 key。
import Foundation
import LabKit

private protocol PriceBook: AnyObject {
    func set(priceText: String, qty: Decimal)
    /// 由高價排到低價
    var levels: [E05Level] { get }
}

private func describe(_ levels: [E05Level]) -> String {
    "共 \(levels.count) 檔："
        + levels.map { "\($0.price)×\($0.qty)" }.joined(separator: "、")
}

/// 演練流程（兩個版本共用）：快照與即時更新，同一個價格用不同的文字寫法
private func scenario(_ log: LabLog, book: PriceBook) {
    book.set(priceText: "4860000.0", qty: e05("0.4"))
    book.set(priceText: "4859000.0", qty: e05("0.6"))
    log("快照（REST）→ \(describe(book.levels))")
    book.set(priceText: "4860000", qty: e05("0.9"))
    book.set(priceText: "4859000.00", qty: e05("0"))
    book.set(priceText: "4858000", qty: e05("0.3"))
    log("更新（WebSocket）→ \(describe(book.levels))")
    log("最後：\(describe(book.levels))（伺服器的板：共 2 檔：4860000×0.9、4858000×0.3）")
}

// MARK: - Bug 版
private enum L04Bug {
    final class Book: PriceBook {
        private var byText: [String: Decimal] = [:]

        func set(priceText: String, qty: Decimal) {
            // 用價格的文字當 key
            byText[priceText] = qty == 0 ? nil : qty
        }

        var levels: [E05Level] {
            byText.map { E05Level(price: e05($0.key), qty: $0.value) }.sorted {
                ($0.price, $0.qty) > ($1.price, $1.qty)
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, book: Book())
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Book: PriceBook {
        private var byPrice: [Decimal: Decimal] = [:]

        func set(priceText: String, qty: Decimal) {
            // 先轉成 Decimal 再當 key：比的是數值
            byPrice[e05(priceText)] = qty == 0 ? nil : qty
        }

        var levels: [E05Level] {
            byPrice.map { E05Level(price: $0.key, qty: $0.value) }.sorted {
                $0.price > $1.price
            }
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, book: Book())
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l04 = Lab(
        id: "L04",
        title: "同一個價位在板上分成兩檔",
        question: "第 3 題：本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？",
        goal:
            "價格當字典的 key，要比「數值」，不能比文字（或 BigDecimal 的 equals）：4860000.0 與 4860000 必須是同一檔。",
        steps: [
            "選「Bug 版」執行，看快照之後有幾檔？更新之後呢？4,860,000 這個價格出現了幾次？",
            "想一想：「4859000.00」數量 0 想刪掉 4,859,000 這一檔，為什麼沒刪到？",
            "選「修正版」執行，更新之後要剩 2 檔：4860000×0.9、4858000×0.3。",
            "進階：Android 的 `BigDecimal` 有同樣的問題。想一想：`HashMap` 要怎麼處理，或換成哪個結構？（`TreeMap`、`stripTrailingZeros()`）",
        ],
        expectBug:
            "更新之後「共 4 檔：4860000×0.9、4860000×0.4、4859000×0.6、4858000×0.3」，4,860,000 分成兩檔，要刪除的 4,859,000 還在。",
        expectFixed: "更新之後「共 2 檔：4860000×0.9、4858000×0.3」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, book:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix(
                    "最後：共 4 檔：4860000×0.9、4860000×0.4、4859000×0.6、4858000×0.3")
            })
                ? nil
                : "Bug 還沒重現：set 要用 priceText（字串）當 byText 的 key，數量 0 就移除；4860000.0 與 4860000 會變成兩檔"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) {
                return "演練沒有跑完：要用 scenario(log, book:) 執行"
            }
            return lines.contains(where: {
                $0.hasPrefix("最後：共 2 檔：4860000×0.9、4858000×0.3")
            })
                ? nil
                : "set 要先用 e05(priceText) 轉成 Decimal 再當 byPrice 的 key（數量 0 就移除），同一個價格才會是同一檔"
        }
    )
}
