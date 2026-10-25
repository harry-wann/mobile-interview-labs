// L05：只要前 10 名，卻把 2 萬筆全部排序（比較次數差 10 倍以上）
// 對應複習站：Coding01DataStructures 第 4 題「排行榜取前 k 名，要排序還是用
// heap？」（也是第 2 題「時間複雜度怎麼用白話講」的實測）
//
// 情境：逐筆成交的統計表有 2 萬個項目，要取成交量最高的前 10 名。成交量一樣時，
// 依代號字母排序。
//       演練用計數器數「比較了幾次」，看兩種做法的成本。
// （幣種只有幾百個的排行榜，排序完全夠用，這裡是 n 很大、k 很小的情況。）
//
// 你要寫：
//   ① Bug 版（L05Bug.topK）：entries.sorted 全部排序（比較函式裡先 counter.
// count += 1，再回傳 isBetter(lhs, rhs)），取前 k 個。
//   ② 修正版（L05Fixed.topK）：用大小為 k 的 min-heap（C01MinHeap）。heap
// 最上面是目前保留的最差的一筆；
//      還沒滿 k 筆就 push；滿了就和 heap.top 比一次（counter.count += 1），
// 新的比較好才 replaceTop。最後由好到差回傳。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding01-DataStructures/L05_TopKHeap.swift
import LabKit

private let count = 20_000
private let k = 10

/// 固定種子的偽亂數，每次產生一模一樣的資料
private func makeEntries() -> [C01Entry] {
    var state: UInt64 = 2026
    return (0..<count).map { i in
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return C01Entry(
            symbol: "COIN\(i)", volume: Int(state >> 33) % 1_000_000)
    }
}

/// 比較兩個項目誰「比較前面」：成交量大的在前；一樣大，代號字母順序在前
private func isBetter(_ lhs: C01Entry, _ rhs: C01Entry) -> Bool {
    lhs.volume != rhs.volume ? lhs.volume > rhs.volume : lhs.symbol < rhs.symbol
}

/// 演練流程（兩個版本共用）：產生資料 → 取前 10 名 → 數比較次數，
/// 並和標準答案比對
private func scenario(
    _ log: LabLog, topK: ([C01Entry], Int, C01Counter) -> [C01Entry]
) {
    let entries = makeEntries()
    let counter = C01Counter()
    let result = topK(entries, k, counter)
    let expected = Array(entries.sorted(by: isBetter).prefix(k))
    log("\(count) 筆資料取前 \(k) 名：比較了 \(counter.count) 次")
    log("前 \(k) 名和標準答案一致：\(result == expected ? "是" : "否")")
    log("最高的是 \(result.first?.symbol ?? "-")，成交量 \(result.first?.volume ?? 0)")
}

/// 從「…比較了 N 次」那一行取出 N（自動檢查用）
private func comparisons(in lines: [String]) -> Int? {
    guard
        let line = lines.first(where: {
            $0.hasSuffix(" 次") && $0.contains("比較了")
        }),
        let range = line.range(of: "比較了 ")
    else { return nil }
    return Int(line[range.upperBound...].dropLast(2))
}

// MARK: - Bug 版
private enum L05Bug {
    static func topK(
        _ entries: [C01Entry], _ k: Int, _ counter: C01Counter
    ) -> [C01Entry] {
        // TODO ①-1：entries.sorted { lhs, rhs in … }：每比較一次先 counter.
        // count += 1，再回傳 isBetter(lhs, rhs)
        // TODO ①-2：全部排好之後，只取前 k 個，轉成 Array 回傳
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, topK: topK)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func topK(
        _ entries: [C01Entry], _ k: Int, _ counter: C01Counter
    ) -> [C01Entry] {
        guard k > 0 else { return [] }
        // TODO ②-1：建立 heap：var heap = C01MinHeap<C01Entry> { lhs, rhs in …
        // }。heap 最上面要是「目前保留的 k 筆裡最差的」，
        //          所以 isLess(lhs, rhs) 代表 lhs 比 rhs 差：先 counter.count
        // += 1，再回傳 isBetter(rhs, lhs)
        // TODO ②-2：逐一處理 entries：heap.count < k 就 heap.push(entry)；
        // 否則拿 heap.top 當 worst，counter.count += 1，
        //          entry 比 worst 好（isBetter(entry, worst)）才 heap.
        // replaceTop(with: entry)
        // TODO ②-3：heap.drainAscending() 是由差到好，反過來（reversed）
        // 就是由好到差，轉成 Array 回傳
        return []
    }

    static func run(_ log: LabLog) async {
        scenario(log, topK: topK)
    }
}

// MARK: - 演練設定
extension Coding01DataStructures {
    static let l05 = Lab(
        id: "L05",
        title: "只要前 10 名，卻排了 2 萬筆",
        question: "第 4 題：排行榜取前 k 名，要排序還是用 heap？（第 2 題：時間複雜度怎麼用白話講）",
        goal:
            "n 很大、k 很小時，用大小為 k 的 min-heap：每筆資料只和 heap 最差的比一次，比較次數從 O(n log n) 降到接近 n。先寫出全部排序的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding01-DataStructures/L05_TopKHeap.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「比較了幾次」，以及前 10 名是否和標準答案一致。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，比較兩個版本的次數。",
            "對照第 4 題：為什麼幣種只有幾百個的排行榜，直接排序就夠了？",
        ],
        expectBug:
            "2 萬筆全部排序，比較 26 萬到 39 萬次（iOS 約 39 萬、Android 約 26 萬，排序演算法不同），一定超過 10 萬次。",
        expectFixed: "比較約 2 萬次出頭（每筆和 heap 最差的比一次，少數幾次換進去時多比幾次），前 10 名一致。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let comparisons = comparisons(in: lines) else {
                return "演練沒有跑完：要用 scenario(log, topK:) 執行"
            }
            if !lines.contains("前 10 名和標準答案一致：是") {
                return "topK 還沒寫：排序之後取前 10 個"
            }
            return comparisons >= 100_000
                ? nil : "Bug 還沒重現：要把 2 萬筆全部排序（sorted），比較次數才會超過 10 萬次"
        },
        checkFixed: { lines in
            guard let comparisons = comparisons(in: lines) else {
                return "演練沒有跑完：要用 scenario(log, topK:) 執行"
            }
            if !lines.contains("前 10 名和標準答案一致：是") {
                return "前 10 名不對：heap 最上面要是目前最差的，比它好才換進去，最後由好到差輸出"
            }
            return comparisons < 60_000
                ? nil
                : "比較了 \(comparisons) 次，太多了：新的一筆只要和 heap.top 比一次，比它好才 replaceTop"
        }
    )
}
