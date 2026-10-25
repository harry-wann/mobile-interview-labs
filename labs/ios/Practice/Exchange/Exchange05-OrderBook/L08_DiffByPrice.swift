// L08：多了一檔最佳買價，整張板的每一列都重畫（比較新舊兩版時用「位置」而不是「價格」）
// 對應複習站：Exchange05OrderBook 第 6 題「板更新得很頻繁，畫面怎麼更新才不會卡？」
//
// 情境：買方顯示最好的 5 檔。新的一版比舊的多了一個最佳買價 10,005,000，原本最後一檔 9,980,000 被擠出畫面；
//       另外 9,995,000 的數量從 0.2 變成 0.5，其他列的價格與數量都沒變。
//       該更新的只有 3 列：新增 10,005,000、移除 9,980,000、9,995,000 數量改變。
//
// 你要寫：
//   ① Bug 版（L08Bug.diff）：用位置對應：第 index 列的新舊價格或數量不同，就算「數量改變」；超出舊版長度的算「新增」。
//   ② 修正版（L08Fixed.diff）：用價格當識別：舊版沒有的價格是 added，新版沒有的是 removed，兩邊都有但數量不同的是 changed。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange05-OrderBook/L08_DiffByPrice.swift
import Foundation
import LabKit

private struct Diff {
    var added: [Decimal] = []
    var removed: [Decimal] = []
    var changed: [Decimal] = []
    var updateCount: Int { added.count + removed.count + changed.count }
}

private let oldBook = [E05Level("10000000", "0.4"), E05Level("9995000", "0.2"), E05Level("9990000", "0.6"),
                       E05Level("9985000", "0.3"), E05Level("9980000", "0.1")]
private let newBook = [E05Level("10005000", "0.1"), E05Level("10000000", "0.4"), E05Level("9995000", "0.5"),
                       E05Level("9990000", "0.6"), E05Level("9985000", "0.3")]

/// 演練流程（兩個版本共用）：比較新舊兩版，列出要更新的列
private func scenario(_ log: LabLog, diff: ([E05Level], [E05Level]) -> Diff) {
    let result = diff(oldBook, newBook)
    log("新增 \(result.added)，移除 \(result.removed)，數量改變 \(result.changed)（共要更新 \(result.updateCount) 列）")
    log("最後：共要更新 \(result.updateCount) 列（該更新的只有 3 列：新增 10005000、移除 9980000、9995000 數量改變）")
}

// MARK: - Bug 版
private enum L08Bug {
    static func diff(old: [E05Level], new: [E05Level]) -> Diff {
        var result = Diff()
        // TODO ①：用 new.enumerated() 逐列比：index >= old.count 的 level.price 放進 result.added；
        //        否則 old[index] 的 price 或 qty 和 level 不同，就把 level.price 放進 result.changed（用位置對應，不查價格）
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, diff: diff)
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static func diff(old: [E05Level], new: [E05Level]) -> Diff {
        // TODO ②：先把 old、new 各做成「價格 → 數量」的字典（Dictionary(uniqueKeysWithValues:)），再算出：
        //   added = new 裡有、old 沒有的價格；removed = old 裡有、new 沒有的價格；changed = 兩邊都有但數量不同的價格
        return Diff()
    }

    static func run(_ log: LabLog) async {
        scenario(log, diff: diff)
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l08 = Lab(
        id: "L08",
        title: "多一檔最佳買價，整張板都重畫",
        question: "第 6 題：板更新得很頻繁，畫面怎麼更新才不會卡？",
        goal: "比較新舊兩版板，要用價格當識別，只更新新增、移除、數量改變的那幾列；用位置對應，只要中間多一檔，後面每一列都會被判斷成有變動。先寫出用位置比較的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange05-OrderBook/L08_DiffByPrice.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「共要更新幾列」。新舊兩版其實只差 3 列。",
            "想一想：為什麼新增一檔最佳買價，連沒變的 10,000,000、9,990,000 都被判斷成有變動？",
            "寫 TODO ②（修正版），選「修正版」執行，要看到新增 [10005000]，移除 [9980000]，數量改變 [9995000]，共 3 列。",
            "進階：iOS 的 diffable data source 用什麼當識別？（項目的 Hashable 識別，這裡是價格，不要包含數量）Android 的 DiffUtil 對應 areItemsTheSame。",
        ],
        expectBug: "新增 []，移除 []，數量改變 [10005000, 10000000, 9995000, 9990000, 9985000]，「共要更新 5 列」。",
        expectFixed: "新增 [10005000]，移除 [9980000]，數量改變 [9995000]，「共要更新 3 列」。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, diff:) 執行" }
            return lines.contains(where: { $0.hasPrefix("最後：共要更新 5 列") })
                ? nil : "Bug 還沒重現：用位置比較（old[index] 和 new[index]），新增一檔之後 5 列都會被判斷成有變動"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, diff:) 執行" }
            return lines.contains("新增 [10005000]，移除 [9980000]，數量改變 [9995000]（共要更新 3 列）")
                ? nil : "用價格當識別：舊版沒有的價格是新增，新版沒有的是移除，兩邊都有但數量不同的才是數量改變"
        }
    )
}
