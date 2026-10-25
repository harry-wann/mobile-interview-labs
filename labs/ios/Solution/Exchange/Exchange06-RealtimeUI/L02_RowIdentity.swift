// L02：價格一跳，被選取的那一列就取消選取了（列表用「包含價格的整個 struct」當識別）
// 對應複習站：Exchange06RealtimeUI 第 4 題「列表怎麼只更新有變動的列？」
//
// 情境：行情列表有三列（BTC_JPY、ETH_JPY、XRP_JPY）。使用者點了 ETH_JPY，那一列變成選取狀態（狀態存在「列」這個物件上）。
//       接著價格更新三次：第 1 次 BTC 和 ETH 變、第 2 次三檔都變、第 3 次只有 ETH 變。
//       列表用「識別」（identity）決定新的一份資料對應到哪一個既有的列：對得上就沿用那一列（只換內容），對不上就新建一列。
//       （diffable data source 的 Hashable 項目、SwiftUI 的 ForEach id、Compose 的 key，都是這個意思；列表用 index 當識別的問題，Swift08 L02、Kotlin06 L07 練過。）
// Bug 版：識別 = 整個 Row（交易對代號加價格）。價格一變，識別就變了，對不上既有的列，等於「刪掉舊的一列、新建一列」，選取狀態跟著舊的一列消失。
// 修正版：識別 = 交易對代號。價格變了還是同一列，沿用既有的列，只換內容，選取狀態保留。
import Foundation
import LabKit

private struct Row {
    let symbol: String
    let price: Decimal
}

/// 列表上的一列（物件）：選取狀態存在這裡，換內容時沿用，新建時從沒有選取開始
private final class Cell {
    var row: Row
    var selected = false

    init(row: Row) {
        self.row = row
    }
}

/// 模擬列表如何用識別對應新舊資料（diffable data source 的行為）
private final class ListView {
    private var cells: [String: Cell] = [:]
    private(set) var created = 0
    private(set) var reused = 0
    private(set) var duplicates = 0

    func apply(_ rows: [Row], identity: (Row) -> String) {
        var next: [String: Cell] = [:]
        for row in rows {
            let id = identity(row)
            if next[id] != nil {
                duplicates += 1                         // 兩列的識別一樣：這不是正常的寫法
                continue
            }
            if let cell = cells[id] {
                cell.row = row                          // 識別對得上：沿用既有的列，只換內容
                reused += 1
                next[id] = cell
            } else {
                next[id] = Cell(row: row)               // 對不上：新建一列（沒有選取狀態）
                created += 1
            }
        }
        cells = next
    }

    func select(_ symbol: String) {
        cells.values.first { $0.row.symbol == symbol }?.selected = true
    }

    var selectedSymbol: String? {
        cells.values.first { $0.selected }?.row.symbol
    }
}

private func rows(_ btc: String, _ eth: String, _ xrp: String) -> [Row] {
    [Row(symbol: "BTC_JPY", price: e06(btc)), Row(symbol: "ETH_JPY", price: e06(eth)), Row(symbol: "XRP_JPY", price: e06(xrp))]
}

/// 演練流程（兩個版本共用）：`identity` 決定新的一份資料對應到哪一個既有的列
private func scenario(_ log: LabLog, identity: (Row) -> String) {
    let list = ListView()
    list.apply(rows("10000000", "400000", "80"), identity: identity)
    list.select("ETH_JPY")
    log("開始：選取 \(list.selectedSymbol ?? "沒有")")
    let updates = [rows("10000100", "400100", "80"), rows("10000200", "400200", "80.3"), rows("10000200", "400300", "80.3")]
    let before = list.created
    for (index, update) in updates.enumerated() {
        let createdBefore = list.created, reusedBefore = list.reused
        list.apply(update, identity: identity)
        log("第 \(index + 1) 次更新：新建 \(list.created - createdBefore) 列，沿用 \(list.reused - reusedBefore) 列，選取 \(list.selectedSymbol ?? "沒有")")
    }
    log("最後：選取 \(list.selectedSymbol ?? "沒有")，更新期間共新建 \(list.created - before) 列，沿用 \(list.reused) 列，識別重複 \(list.duplicates) 次")
}

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        scenario(log) { row in "\(row)" }               // 整個 Row 當識別：價格一變，識別就變了
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func run(_ log: LabLog) async {
        scenario(log) { row in row.symbol }             // 交易對代號當識別：價格變了還是同一列
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l02 = Lab(
        id: "L02",
        title: "價格一跳，選取的那一列就取消選取了",
        question: "第 4 題：列表怎麼只更新有變動的列？",
        goal: "列表的識別要用穩定的交易對代號，不能把價格放進識別：價格一變就變成「刪一列、新建一列」，列上的選取、展開狀態都會消失。",
        steps: [
            "選「Bug 版」執行，看每次更新「新建幾列、沿用幾列」，以及選取的那一列還在不在。",
            "想一想：第 1 次更新只有 BTC 和 ETH 的價格變了，為什麼被選取的 ETH_JPY 就不見了？",
            "選「修正版」執行，三次更新都要沿用三列、不新建，選取一直是 ETH_JPY。",
            "進階：iOS 的 diffable data source 搭配 `reconfigureItems`（只更新內容、沿用 cell），和 Android 的 DiffUtil 的 `areItemsTheSame`，各自對應這裡的哪一個步驟？",
        ],
        expectBug: "每次更新價格有變的列都被新建：第 1 次新建 2 列，選取變成「沒有」，「最後：選取 沒有，更新期間共新建 6 列，沿用 3 列」。",
        expectFixed: "三次更新都沿用 3 列、不新建，選取一直是 ETH_JPY，「最後：選取 ETH_JPY，更新期間共新建 0 列，沿用 9 列」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, identity:) 執行" }
            return lines.contains("最後：選取 沒有，更新期間共新建 6 列，沿用 3 列，識別重複 0 次")
                ? nil : "Bug 還沒重現：識別要把整個 Row 都放進去（例如 \"\\(row)\"，包含交易對代號和價格），價格有變的列才會被新建"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, identity:) 執行" }
            return lines.contains("最後：選取 ETH_JPY，更新期間共新建 0 列，沿用 9 列，識別重複 0 次")
                ? nil : "識別要用 row.symbol（交易對代號）：每一列的識別不同，價格變了還是對得上既有的列"
        }
    )
}
