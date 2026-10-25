// L04：透過計算屬性 append，每一次都複製整個陣列（COW 失效）
// 對應複習站：Swift01ValueSemantics 第 4 題「什麼情況下 COW 會失效，
// 讓程式突然變慢？」
//
// 情境：ViewModel 存了 5 萬筆成交價，推播每來一筆就 append 一筆，共 5000 筆。
// Bug 版：外部透過計算屬性 `prices` 的 get／set 去 append。get 拿出來的那份和
// _prices 共用 storage，
//        append 時發現被共用，只好先把 5 萬筆整個複製一次。
// 修正版：陣列是儲存屬性，外部只能讀；要加資料就呼叫 ViewModel 的方法，
// 直接改原本的陣列。
import Foundation
import LabKit

/// 量一段程式花了幾毫秒（兩個版本共用）
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

        /// 計算屬性：用 get／set 包住 _prices
        var prices: [Double] {
            get { _prices }
            set { _prices = newValue }
        }
    }

    static func run(_ log: LabLog) async {
        let (ms, count) = await Task.detached { () -> (Double, Int) in
            let vm = PriceViewModel()
            let ms = measureMs {
                for i in 0..<5_000 {
                    // get → append（複製 5 萬筆）→ set
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
        /// 儲存屬性：外部只能讀
        private(set) var prices: [Double] = Array(repeating: 100, count: 50_000)

        /// 要加資料就呼叫這個方法，直接修改原本的陣列，不會複製
        func append(_ price: Double) {
            prices.append(price)
        }
    }

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
            "選「Bug 版」執行，記下毫秒數。",
            "選「修正版」執行，比較毫秒數。",
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
