// L04：透過計算屬性 append，每一次都複製整個陣列（COW 失效）
// 對應複習站：Swift01ValueSemantics 第 4 題「什麼情況下 COW 會失效，
// 讓程式突然變慢？」
//
// 情境：ViewModel 存了 5 萬筆成交價，推播每來一筆就 append 一筆，共 5000 筆。
//
// 你要寫：
//   ① Bug 版：用計算屬性 prices（get 回傳 _prices、set 寫回 _prices）包住陣列，
// 外部透過 vm.prices.append 加資料。
//   ② 修正版：prices 改成外部只能讀的儲存屬性，另外寫一個 append 方法，
// 直接改原本的陣列。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift01-ValueSemantics/L04_COWBreaks.swift
import Foundation
import LabKit

/// 量一段程式花了幾毫秒（已寫好）
private func measureMs(_ work: () -> Void) -> Double {
    let start = DispatchTime.now()
    work()
    return Double(
        DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds)
        / 1_000_000
}

// MARK: - Bug 版
private enum L04Bug {
    final class PriceViewModel {
        private var _prices: [Double] = Array(repeating: 100, count: 50_000)

        // TODO ①：把下面這行改成計算屬性：get { _prices }、set { _prices =
        // newValue }
        var prices: [Double] = []
    }

    /// 演練流程（不用改）
    static func run(_ log: LabLog) async {
        let (ms, count) = await Task.detached { () -> (Double, Int) in
            let vm = PriceViewModel()
            let ms = measureMs {
                for i in 0..<5_000 {
                    // 透過 prices 加資料
                    vm.prices.append(Double(i))
                }
            }
            return (ms, vm.prices.count)
        }.value
        log("陣列現在有 \(count) 筆")
        log(String(format: "append 5000 筆：%.2f ms", ms))
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class PriceViewModel {
        // TODO ②-1：把 prices 改成「外部只能讀」的儲存屬性（private(set)），
        // 初始值是 5 萬筆 100
        var prices: [Double] = []

        func append(_ price: Double) {
            // TODO ②-2：直接 append 到 prices
        }
    }

    /// 演練流程（不用改）
    static func run(_ log: LabLog) async {
        let (ms, count) = await Task.detached { () -> (Double, Int) in
            let vm = PriceViewModel()
            let ms = measureMs {
                for i in 0..<5_000 {
                    vm.append(Double(i))
                }
            }
            return (ms, vm.prices.count)
        }.value
        log("陣列現在有 \(count) 筆")
        log(String(format: "append 5000 筆：%.2f ms", ms))
    }
}

// MARK: - 演練設定
private func appendMs(_ lines: [String]) -> Double? {
    lines.first { $0.hasPrefix("append 5000 筆：") }
        .flatMap { Double($0.dropFirst("append 5000 筆：".count).dropLast(3)) }
}

extension Swift01ValueSemantics {
    static let l04 = Lab(
        id: "L04",
        title: "append 一筆，卻複製整個陣列",
        question: "第 4 題：什麼情況下 COW 會失效，讓程式突然變慢？",
        goal: "兩個版本都是對 5 萬筆的陣列 append 5000 次，差在「怎麼 append」。量量看差幾倍。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，記下毫秒數。",
            "寫 TODO ②，選「修正版」執行，比較毫秒數。",
            "進階：用 Instruments 的 Allocations 錄 Bug 版，會看到大量約 400 KB 的配置（每次複製 5 萬個 Double）。",
        ],
        expectBug: "append 5000 筆要幾十毫秒（本機模擬器約 45 ms），陣列越大越慢。",
        expectFixed: "不到 1 毫秒（本機模擬器約 0.6 ms）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("陣列現在有 55000 筆") else {
                return "陣列應該有 55000 筆：append 要寫進 _prices"
            }
            guard let ms = appendMs(lines) else { return "找不到毫秒數" }
            return ms >= 5
                ? nil
                : String(
                    format: "只花了 %.2f ms：Bug 還沒重現（要透過計算屬性的 get／set 去 append）",
                    ms)
        },
        checkFixed: { lines in
            guard lines.contains("陣列現在有 55000 筆") else {
                return "陣列應該有 55000 筆：append 要寫進 prices"
            }
            guard let ms = appendMs(lines) else { return "找不到毫秒數" }
            return ms < 5 ? nil : String(format: "花了 %.2f ms，還是太慢：不要經過計算屬性", ms)
        }
    )
}
