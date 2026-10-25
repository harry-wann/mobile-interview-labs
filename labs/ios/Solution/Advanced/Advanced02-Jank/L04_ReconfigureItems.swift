// L04：行情每跳一次，列表每一個看得到的 cell 都重新設定一次（reloadData 與 diffable data source 的 reconfigureItems）
// 對應複習站：Advanced02Jank 第 6 題「行情列表每秒更新幾十次，怎麼做才不會卡？」
//
// 情境：行情列表有 40 檔，畫面一次看得到約 11 列（UICollectionView 的 list 版面）。每次行情更新，只有前 3 檔的價格變了。共更新 10 次。
//        每個 cell 設定一次（cell registration 的 handler 被呼叫一次），主執行緒就要多做一份建立內容的工作。
//        演練把 UICollectionView 放進螢幕外的視窗真的跑起來，數 handler 被呼叫幾次。
// Bug 版：每次更新都 dataSource.applySnapshotUsingReloadData（等於 reloadData()）：所有看得到的 cell 全部重新設定，10 次約 110 次。
// 修正版：snapshot.reconfigureItems(有變的 3 檔) 再 apply（iOS 15 起）：只重新設定這 3 個 cell，10 次剛好 30 次；
//        animatingDifferences: false，不要列表動畫，避免高頻更新時閃爍。
import UIKit
import LabKit

private let symbolCount = 40
private let tickCount = 10

/// 行情資料：交易對代號 → 目前的價格文字。cell 設定時從這裡讀（兩個版本共用）
@MainActor
private final class Quotes {
    let symbols = (0..<symbolCount).map { "COIN\($0)" }
    private(set) var prices: [String: String] = [:]
    private(set) var configured = 0           // cell 的 handler 被呼叫了幾次

    init() {
        for (i, symbol) in symbols.enumerated() { prices[symbol] = "1,000,\(String(format: "%03d", i))" }
    }

    /// 第 tick 次更新：前 3 檔的價格變了，回傳有變的代號
    func update(tick: Int) -> [String] {
        let changed = Array(symbols.prefix(3))
        for (i, symbol) in changed.enumerated() { prices[symbol] = "\(tick),000,\(String(format: "%03d", i))" }
        return changed
    }

    func didConfigure() { configured += 1 }
    func resetCount() { configured = 0 }
}

/// 行情列表（兩個版本共用）：diffable data source，item 是交易對代號，cell 的內容從 Quotes 讀
@MainActor
private final class MarketList {
    let collectionView: UICollectionView
    let dataSource: UICollectionViewDiffableDataSource<Int, String>

    init(quotes: Quotes) {
        let layout = UICollectionViewCompositionalLayout.list(using: UICollectionLayoutListConfiguration(appearance: .plain))
        collectionView = UICollectionView(frame: CGRect(x: 0, y: 0, width: 390, height: 800), collectionViewLayout: layout)
        let registration = UICollectionView.CellRegistration<UICollectionViewListCell, String> { cell, _, symbol in
            quotes.didConfigure()
            var content = cell.defaultContentConfiguration()
            content.text = symbol
            content.secondaryText = quotes.prices[symbol]
            cell.contentConfiguration = content
        }
        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { collectionView, indexPath, symbol in
            collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: symbol)
        }
        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(quotes.symbols)
        dataSource.apply(snapshot, animatingDifferences: false)
    }

    /// 畫面上看得到的 cell，顯示的價格和最新資料不一樣的有幾個
    func staleCells(quotes: Quotes) -> Int {
        collectionView.layoutIfNeeded()
        return collectionView.visibleCells.reduce(0) { count, cell in
            guard let list = cell as? UICollectionViewListCell,
                  let content = list.contentConfiguration as? UIListContentConfiguration,
                  let symbol = content.text else { return count }
            return quotes.prices[symbol] == content.secondaryText ? count : count + 1
        }
    }
}

/// 演練流程（兩個版本共用）：放進螢幕外的視窗 → 更新 10 次 → 數 cell 設定了幾次
@MainActor
private func scenario(_ log: LabLog, apply: (MarketList, [String]) -> Void) async {
    let quotes = Quotes()
    let list = MarketList(quotes: quotes)
    let host = UIViewController()
    host.view = list.collectionView
    let stage = SwiftUILabStage(viewController: host)
    await stage.settle()
    quotes.resetCount()                              // 只算更新期間
    for tick in 1...tickCount {
        let changed = quotes.update(tick: tick)
        apply(list, changed)
        await stage.settle(ms: 60)                   // 讓 UICollectionView 排版、設定 cell
    }
    log("價格更新 \(tickCount) 次，cell 一共設定了 \(quotes.configured) 次")
    log("畫面上顯示的價格和最新資料不一樣的 cell：\(list.staleCells(quotes: quotes)) 個")
    stage.remove()
}

// MARK: - Bug 版
private enum L04Bug {
    @MainActor
    static func apply(_ list: MarketList, changed: [String]) {
        list.dataSource.applySnapshotUsingReloadData(list.dataSource.snapshot())   // 等於 reloadData()：全部重新設定
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log) { list, changed in apply(list, changed: changed) }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    @MainActor
    static func apply(_ list: MarketList, changed: [String]) {
        var snapshot = list.dataSource.snapshot()
        snapshot.reconfigureItems(changed)                                          // 只重新設定有變的 cell，不重新建立
        list.dataSource.apply(snapshot, animatingDifferences: false)                // 不要列表動畫，避免閃爍
    }

    @MainActor
    static func run(_ log: LabLog) async {
        await scenario(log) { list, changed in apply(list, changed: changed) }
    }
}

// MARK: - 演練設定
extension Advanced02Jank {
    static let l04 = Lab(
        id: "L04",
        title: "行情每跳一次，列表每個 cell 都重新設定",
        question: "第 6 題：行情列表每秒更新幾十次，怎麼做才不會卡？",
        goal: "只重新設定有變的 cell（reconfigureItems），不要每次 reloadData；畫面上的價格還是要跟最新資料一致。",
        steps: [
            "選「Bug 版」按「執行」，看「cell 一共設定了幾次」：10 次更新、每次畫面上約 11 個 cell 全部重新設定，約 110 次。",
            "選「修正版」按「執行」：10 次更新、每次 3 個 cell，剛好 30 次，畫面上的價格仍然是最新的（不一樣的 cell 是 0 個）。",
            "進階：Instruments 的 Time Profiler 錄 Bug 版，看主執行緒上 `cellForItemAt`（cell provider）佔的比例；修正版同樣的操作，這一段會小很多。",
            "想一想：預先在背景把價格字串格式化好、關掉隱式動畫（CATransaction.setDisableActions(true)）分別能再省什麼？（見這個單元的第 6 題）",
        ],
        expectBug: "價格更新 10 次，cell 一共設定了約 110 次（每次畫面上約 11 個 cell 全部重新設定）。",
        expectFixed: "價格更新 10 次，cell 一共設定了 30 次；畫面上顯示的價格和最新資料不一樣的 cell：0 個。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = a02Number(in: lines, prefix: "價格更新 10 次，cell 一共設定了 ") else { return "演練沒有跑完" }
            if n < 60 { return "cell 只設定了 \(Int(n)) 次：Bug 版每次更新都要 applySnapshotUsingReloadData，畫面上的 cell 全部重新設定" }
            return nil
        },
        checkFixed: { lines in
            guard let n = a02Number(in: lines, prefix: "價格更新 10 次，cell 一共設定了 ") else { return "演練沒有跑完" }
            let stale = a02Number(in: lines, prefix: "畫面上顯示的價格和最新資料不一樣的 cell：") ?? 0
            if n == 0 { return "沒有任何 cell 被重新設定：snapshot.reconfigureItems(changed) 之後要 dataSource.apply(snapshot, animatingDifferences: false)" }
            if n > 40 { return "cell 設定了 \(Int(n)) 次，太多了：只有有變的 3 檔（changed）要 reconfigure，10 次共 30 次" }
            if stale > 0 { return "有 \(Int(stale)) 個 cell 顯示的還是舊價格：reconfigureItems 要傳入有變的代號（changed）" }
            return nil
        }
    )
}
