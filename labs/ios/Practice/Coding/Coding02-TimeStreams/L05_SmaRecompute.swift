// L05：移動平均每加一筆價格，就把最近 200 筆重新加一次（O(期數)）
// 對應複習站：Coding02TimeStreams 第 5 題「移動平均怎麼做到每加一筆都很快？」
//
// 情境：K 線圖的 MA200（最近 200 根收盤價的平均），收盤價一根一根進來，共 2000 根。演練用計數器數「做了幾次加減」。
//
// 你要寫：
//   ① Bug 版（L05Bug.RecomputeSMA.add）：環狀緩衝區已經幫你寫好，你要把緩衝區裡的 count 筆全部重新加總（每加一次 counter.steps += 1），
//      緩衝區滿了（count == period）回傳 sum / Double(period)，沒滿回傳 nil。
//   ② 修正版（L05Fixed.RunningSumSMA.add）：維持 sum。緩衝區滿了先減掉 buffer[index]（counter.steps += 1），沒滿就 count += 1；
//      再把新價格放進 buffer[index]、加進 sum（counter.steps += 1）；index 往前一格並繞回開頭；滿了回傳平均，沒滿回傳 nil。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding02-TimeStreams/L05_SmaRecompute.swift
import LabKit

private let period = 200

/// 固定種子的偽亂數，每次產生一模一樣的收盤價
private func makePrices() -> [Double] {
    var state: UInt64 = 11
    return (0..<2000).map { _ in
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return 15_000_000 + Double(Int(state >> 33) % 100_000) / 10
    }
}

private protocol SMA: AnyObject {
    /// 加一筆價格，回傳最近 period 筆的平均；資料不足 period 筆時回傳 nil
    func add(_ price: Double) -> Double?
}

/// 演練流程（兩個版本共用）：2000 筆價格一筆一筆加，數加減次數，並和標準答案比對最後一筆的平均
private func scenario(_ log: LabLog, make: (Int, C02Counter) -> SMA) {
    let prices = makePrices()
    let counter = C02Counter()
    let sma = make(period, counter)
    var last: Double?
    for price in prices { last = sma.add(price) }
    let exact = prices.suffix(period).reduce(0, +) / Double(period)
    log("\(prices.count) 筆價格、期數 \(period)：做了 \(counter.steps) 次加減")
    log("最後一筆的平均和標準答案相差不到 0.01：\(abs((last ?? .nan) - exact) < 0.01 ? "是" : "否")")
}

/// 從「…做了 N 次加減」取出 N（自動檢查用）
private func steps(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.contains("做了 ") && $0.hasSuffix(" 次加減") }),
          let range = line.range(of: "做了 ") else { return nil }
    return Int(line[range.upperBound...].dropLast(" 次加減".count))
}

// MARK: - Bug 版
private enum L05Bug {
    final class RecomputeSMA: SMA {
        private let period: Int
        private let counter: C02Counter
        private var buffer: [Double]
        private var index = 0
        private var count = 0

        init(period: Int, counter: C02Counter) {
            self.period = period
            self.counter = counter
            buffer = [Double](repeating: 0, count: period)
        }

        func add(_ price: Double) -> Double? {
            buffer[index] = price
            index = (index + 1) % period
            if count < period { count += 1 }
            // TODO ①：用迴圈把 buffer[0..<count] 全部加起來（var sum = 0.0；每加一次 counter.steps += 1），
            //         count == period 就回傳 sum / Double(period)，否則回傳 nil
            return nil
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { RecomputeSMA(period: $0, counter: $1) }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    final class RunningSumSMA: SMA {
        private let period: Int
        private let counter: C02Counter
        private var buffer: [Double]
        private var index = 0
        private var count = 0
        private var sum = 0.0

        init(period: Int, counter: C02Counter) {
            self.period = period
            self.counter = counter
            buffer = [Double](repeating: 0, count: period)
        }

        func add(_ price: Double) -> Double? {
            // TODO ②-1：緩衝區已滿（count == period）：sum -= buffer[index]（減掉即將被覆蓋的最舊值），counter.steps += 1；沒滿就 count += 1
            // TODO ②-2：buffer[index] = price，sum += price，counter.steps += 1
            // TODO ②-3：index = (index + 1) % period（繞回開頭）；count == period 就回傳 sum / Double(period)，否則回傳 nil
            return nil
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { RunningSumSMA(period: $0, counter: $1) }
    }
}

// MARK: - 演練設定
extension Coding02TimeStreams {
    static let l05 = Lab(
        id: "L05",
        title: "每加一筆，就把 200 筆重新加一次",
        question: "第 5 題：移動平均怎麼做到每加一筆都很快？",
        goal: "維持「最近 n 筆的總和」：新的加上去、最舊的減掉，每次 add 都是 O(1)，不隨期數變大。先寫出每次重新加總的 Bug 版，再寫維持總和的修正版。",
        steps: [
            "打開 Practice/Coding/Coding02-TimeStreams/L05_SmaRecompute.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「做了幾次加減」，以及最後一筆的平均是否正確。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，比較次數（平均一樣正確）。",
            "想一想：期數從 200 改成 1000，兩個版本的次數各會變成幾倍？",
        ],
        expectBug: "平均正確，但「做了 380000 次加減」左右（每筆加 200 次）。",
        expectFixed: "平均一樣正確，「做了 3800 次加減」左右（每筆 2 次，緩衝區還沒滿時每筆 1 次）。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let count = steps(lines) else { return "演練沒有跑完：要用 scenario(log, make:) 執行" }
            if !lines.contains("最後一筆的平均和標準答案相差不到 0.01：是") { return "add 還沒寫對：緩衝區滿了要回傳最近 200 筆的平均" }
            return count >= 100_000 ? nil : "Bug 還沒重現：每次 add 都要把緩衝區全部重新加一次（每加一次 counter.steps += 1）"
        },
        checkFixed: { lines in
            guard let count = steps(lines) else { return "演練沒有跑完：要用 scenario(log, make:) 執行" }
            if !lines.contains("最後一筆的平均和標準答案相差不到 0.01：是") { return "add 還沒寫對：緩衝區滿了先減掉最舊的 buffer[index]，再加上新的價格" }
            return count <= 6_000 ? nil : "做了 \(count) 次加減，太多了：每筆只能減一次、加一次，不能重新加總"
        }
    )
}
