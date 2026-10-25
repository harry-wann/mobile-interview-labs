// L05：行情列表每次更新，各列的順序一直亂跳；資料量一大還很慢（「同一個交易對只留最新一筆」的合併函式）
// 對應複習站：Advanced05HighFrequency 第 6 題「「同一個交易對只保留最新一筆」的合併函式怎麼寫？」（面試現場寫程式常考）
//
// 情境：輸入一串更新，同一個交易對只留最後一筆，輸出順序依該交易對「第一次出現」的順序。例如 [A1, B1, A2] 要變成 [A2, B1]。
//        演練用 4 組不同順序的資料檢查結果與順序，再用 1 萬筆更新、500 個交易對，數「比較兩個交易對相不相等」做了幾次，量演算法的成本。
//
// 你要寫：
//   ① Bug 版 coalesce：只用字典 latest 存每個交易對的最新值，回傳 Array(latest.values)。值是對的，順序沒有保證。
//   ② 修正版 coalesce：字典 latest 存最新值，陣列 order 記下交易對第一次出現的順序（用 updateValue 的回傳值，舊值是 nil 就是第一次出現），
//      最後依 order 取出 latest 的值。只掃一次，不能用巢狀迴圈。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced05-HighFrequency/L05_CoalesceFunction.swift
import Foundation
import LabKit

/// 會數「== 被呼叫幾次」的交易對型別：用來量演算法做了多少次比較（兩個版本共用）
private struct CountedSymbol: Hashable {
    static var comparisons = 0
    let name: String

    static func == (lhs: CountedSymbol, rhs: CountedSymbol) -> Bool {
        comparisons += 1
        return lhs.name == rhs.name
    }

    func hash(into hasher: inout Hasher) { hasher.combine(name) }
}

/// 合併函式：Bug 版與修正版各自實作（泛型：交易對可以是 String，也可以是會數比較次數的型別）
private protocol Coalescer {
    func coalesce<S: Hashable>(_ updates: [A05Tick<S>]) -> [A05Tick<S>]
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, _ coalescer: some Coalescer) {
    let symbols = ["XRP", "BTC", "ETH", "DOGE", "ADA", "SOL"]
    var latestOK = 0, orderOK = 0
    for k in 0..<4 {
        let order = Array(symbols[k...] + symbols[..<k])                       // 第 k 組：換一個第一次出現的順序
        // 每個交易對先出現一次（價格 1），再用相反的順序各更新一次（價格 2）
        let updates = order.map { A05Tick(symbol: $0, price: 1) } + order.reversed().map { A05Tick(symbol: $0, price: 2) }
        let result = coalescer.coalesce(updates)
        if result.count == order.count && result.allSatisfy({ $0.price == 2 }) { latestOK += 1 }
        if result.map(\.symbol) == order { orderOK += 1 }
    }
    log("合併後每個交易對都是最新價的組數：\(latestOK)／4")
    log("順序和第一次出現的順序一致的組數：\(orderOK)／4")

    let big = (0..<10_000).map { A05Tick(symbol: CountedSymbol(name: "S\($0 % 500)"), price: $0) }
    CountedSymbol.comparisons = 0
    let merged = coalescer.coalesce(big)
    log("1 萬筆更新、500 個交易對：合併成 \(merged.count) 筆，比較交易對 \(CountedSymbol.comparisons) 次")
}

// MARK: - Bug 版
private enum L05Bug {
    struct DictionaryOnly: Coalescer {
        func coalesce<S: Hashable>(_ updates: [A05Tick<S>]) -> [A05Tick<S>] {
            // TODO ①：var latest: [S: A05Tick<S>]；每一筆 latest[update.symbol] = update；回傳 Array(latest.values)（字典沒有固定順序）
            _ = updates
            return []
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, DictionaryOnly())
    }
}

// MARK: - 修正版
private enum L05Fixed {
    struct DictionaryAndOrder: Coalescer {
        func coalesce<S: Hashable>(_ updates: [A05Tick<S>]) -> [A05Tick<S>] {
            // TODO ②：var order: [S] = []、var latest: [S: A05Tick<S>] = [:]；
            //        每一筆 latest.updateValue(update, forKey: update.symbol) == nil（第一次出現）就 order.append(update.symbol)；
            //        最後 return order.compactMap { latest[$0] }
            _ = updates
            return []
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, DictionaryAndOrder())
    }
}

// MARK: - 演練設定
extension Advanced05HighFrequency {
    static let l05 = Lab(
        id: "L05",
        title: "行情列表每次更新，各列的順序一直亂跳",
        question: "第 6 題：「同一個交易對只保留最新一筆」的合併函式怎麼寫？",
        goal: "寫出同一個交易對只留最新一筆、輸出依第一次出現順序、只掃一次（O(n)）的合併函式。先寫出順序會亂的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced05-HighFrequency/L05_CoalesceFunction.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看第二行：順序一致的組數是 0／4（或幾乎是 0）；第一行的值都是對的。",
            "寫 TODO ②，選「修正版」執行：兩行都是 4／4，第三行比較次數約 1.5 萬次。",
            "想一想：如果改用「每一筆更新都從頭找有沒有同一個交易對」的巢狀迴圈，第三行會變成多少？為什麼面試官會問時間複雜度？（見這個單元的第 6 題）",
            "想一想：Kotlin 的 LinkedHashMap 為什麼不需要另外的順序陣列？（覆蓋 key 不改變它第一次插入的位置，見 Android 版的解答）",
        ],
        expectBug: "合併後每個交易對都是最新價的組數：4／4；順序和第一次出現的順序一致的組數：0／4（字典的順序每次執行都不同，偶爾會有 1 組湊巧一致）；比較約 1.5 萬次。",
        expectFixed: "兩個組數都是 4／4；1 萬筆更新、500 個交易對：合併成 500 筆，比較交易對約 1.5 萬次（巢狀迴圈的寫法會超過 100 萬次）。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let latest = a05Number(in: lines, prefix: "合併後每個交易對都是最新價的組數："),
                  let order = a05Number(in: lines, prefix: "順序和第一次出現的順序一致的組數：") else { return "演練沒有跑完" }
            if latest < 4 { return "值要是對的：同一個交易對留最後一筆（latest[symbol] = update）" }
            return order < 4 ? nil : "Bug 還沒重現：只用字典、直接取 latest.values，順序就不會固定"
        },
        checkFixed: { lines in
            guard let latest = a05Number(in: lines, prefix: "合併後每個交易對都是最新價的組數："),
                  let order = a05Number(in: lines, prefix: "順序和第一次出現的順序一致的組數："),
                  let compares = a05Number(in: lines, prefix: "1 萬筆更新、500 個交易對：合併成 500 筆，比較交易對 ") else {
                return "演練沒有跑完（合併 1 萬筆更新要得到 500 筆）"
            }
            if latest < 4 { return "每個交易對要留最後一筆（價格 2）" }
            if order < 4 { return "順序要依第一次出現的順序：用陣列記下第一次出現的交易對（updateValue 回傳 nil 的時候）" }
            return compares < 100_000 ? nil : "比較了 \(Int(compares)) 次，太多了：只能掃一次，用字典找，不要每一筆都從頭找（O(n²)）"
        }
    )
}
