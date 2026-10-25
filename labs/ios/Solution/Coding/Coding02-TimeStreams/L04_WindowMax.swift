// L04：最近 500 毫秒的最高價，每筆成交都往回掃 249 筆（暴力法，處理次數是 n 乘以視窗大小）
// 對應複習站：Coding02TimeStreams 第 4 題「「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？」
//
// 情境：成交依時間排序，每 2 毫秒一筆，共 3000 筆。對每一筆成交，算出時間在 (t − 500, t] 之內的最高價。
//       演練用計數器數「處理了幾次」，同時和標準答案比對（兩種做法算出來的結果是一樣的，差的是成本）。
// Bug 版：每一筆都往回看整個視窗找最大值。視窗裡有 249 筆，3000 筆就是 70 多萬次，資料多一倍、視窗大一倍，成本就變 4 倍。
// 修正版：單調佇列（monotonic queue）。存成交的索引，價格由大到小：新成交進來，先把尾端價格 ≤ 新價格的丟掉，再放進去；
//        再把前端已經過期的丟掉；前端就是答案。每筆最多進出佇列各一次，總共不超過 3 倍筆數。用 head 索引取代 removeFirst()。
import LabKit

private let windowMs = 500

/// 固定種子的偽亂數，每次產生一模一樣的成交
private func makeTrades() -> [C02Tick] {
    var state: UInt64 = 7
    return (0..<3000).map { i in
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return C02Tick(time: i * 2, price: 15_000_000 + Int(state >> 33) % 100_000)
    }
}

/// 標準答案（不計次數的暴力法）
private func reference(_ trades: [C02Tick], window: Int) -> [Int] {
    trades.indices.map { i in
        var best = trades[i].price
        var back = i - 1
        while back >= 0, trades[back].time > trades[i].time - window {
            best = max(best, trades[back].price)
            back -= 1
        }
        return best
    }
}

/// 演練流程（兩個版本共用）：算每一筆成交的視窗最高價，數處理次數，並和標準答案比對
private func scenario(_ log: LabLog, windowMax: ([C02Tick], Int, C02Counter) -> [Int]) {
    let trades = makeTrades()
    let counter = C02Counter()
    let result = windowMax(trades, windowMs, counter)
    log("\(trades.count) 筆成交、視窗 \(windowMs) 毫秒：處理了 \(counter.steps) 次")
    log("結果和標準答案一致：\(result == reference(trades, window: windowMs) ? "是" : "否")")
}

/// 從「…處理了 N 次」取出 N（自動檢查用）
private func steps(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.contains("處理了 ") && $0.hasSuffix(" 次") }),
          let range = line.range(of: "處理了 ") else { return nil }
    return Int(line[range.upperBound...].dropLast(2))
}

// MARK: - Bug 版
private enum L04Bug {
    static func windowMax(_ trades: [C02Tick], _ window: Int, _ counter: C02Counter) -> [Int] {
        var result: [Int] = []
        for (index, trade) in trades.enumerated() {
            var best = trade.price
            var back = index - 1
            while back >= 0, trades[back].time > trade.time - window {   // 往回看整個視窗
                counter.steps += 1
                best = max(best, trades[back].price)
                back -= 1
            }
            result.append(best)
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, windowMax: windowMax)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func windowMax(_ trades: [C02Tick], _ window: Int, _ counter: C02Counter) -> [Int] {
        var result: [Int] = []
        var queue: [Int] = []                                            // 存成交的索引，價格由大到小
        var head = 0                                                     // 前端在哪裡（不真的刪除，避免 removeFirst 的 O(n)）
        for (index, trade) in trades.enumerated() {
            while queue.count > head, trades[queue[queue.count - 1]].price <= trade.price {
                queue.removeLast()                                       // 尾端比較便宜的，以後不可能再是最高價
                counter.steps += 1
            }
            queue.append(index)
            counter.steps += 1
            while trades[queue[head]].time <= trade.time - window {
                head += 1                                                // 前端已經過期
                counter.steps += 1
            }
            result.append(trades[queue[head]].price)                     // 前端就是視窗內的最高價
        }
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, windowMax: windowMax)
    }
}

// MARK: - 演練設定
extension Coding02TimeStreams {
    static let l04 = Lab(
        id: "L04",
        title: "每筆成交都往回掃整個視窗",
        question: "第 4 題：「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？",
        goal: "用單調佇列讓每筆成交最多進出佇列各一次，總處理次數不超過筆數的 3 倍，不隨視窗變大而增加。",
        steps: [
            "選「Bug 版」執行，看「處理了幾次」，以及結果和標準答案是否一致。",
            "選「修正版」執行，比較處理次數（結果一樣是正確的）。",
            "想一想：把視窗改成 1000 毫秒，兩個版本的次數各會變成幾倍？",
        ],
        expectBug: "結果正確，但「處理了 715875 次」左右（每筆往回看 249 筆）。",
        expectFixed: "結果一樣正確，處理次數只有約 6000 次（5988 次，不超過 9000 次，也就是筆數的 3 倍）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let count = steps(lines) else { return "演練沒有跑完：要用 scenario(log, windowMax:) 執行" }
            if !lines.contains("結果和標準答案一致：是") { return "windowMax 還沒寫對：每一筆往回找視窗 (t - window, t] 內的最大值" }
            return count >= 150_000 ? nil : "Bug 還沒重現：每一筆都要往回掃整個視窗（每看一筆 counter.steps += 1）"
        },
        checkFixed: { lines in
            guard let count = steps(lines) else { return "演練沒有跑完：要用 scenario(log, windowMax:) 執行" }
            if !lines.contains("結果和標準答案一致：是") { return "windowMax 還沒寫對：佇列前端要是視窗內的最高價（尾端價格不大於新價格的先丟掉，前端過期的也丟掉）" }
            return count <= 9_000 ? nil : "處理了 \(count) 次，太多了：每筆成交最多進佇列一次、出佇列一次"
        }
    )
}
