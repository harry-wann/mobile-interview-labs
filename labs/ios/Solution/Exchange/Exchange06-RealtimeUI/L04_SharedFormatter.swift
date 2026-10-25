// L04：兩個地方同時格式化價格，有些數字的小數位數不對（共用一個 NumberFormatter，邊用邊改設定）
// 對應複習站：Exchange06RealtimeUI 第 6 題「數字怎麼顯示，才不會抖動、也不會拖慢畫面？」
//
// 情境：行情列表的價格要依交易對的精度顯示：BTC_JPY 0 位小數（10,234,568）、XRP_JPY 4 位小數（0.1235）。
//       背景有兩個工作同時在格式化價格：一個格式化 BTC 的價格、一個格式化 XRP 的價格，各 10 萬次，
//       每次都和「單獨格式化的正確答案」比對。
//       筆記的規則：NumberFormatter 多個執行緒同時「使用」是安全的，但同時「修改它的屬性」不安全。
// Bug 版：App 只建一個 NumberFormatter 共用；每次格式化都先依精度改 minimumFractionDigits／maximumFractionDigits，再格式化。
//        兩條執行緒的「改設定」和「格式化」交錯：BTC 的價格剛好被格式化成 4 位小數，XRP 的價格被格式化成整數。
// 修正版：依精度各建一個 NumberFormatter（0 到 4 位小數各一個），建好之後不再修改，格式化時依精度取用。
import Foundation
import LabKit

private protocol PriceFormatter: AnyObject {
    func text(_ value: Decimal, digits: Int) -> String
}

private func makeFormatter(digits: Int) -> NumberFormatter {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.locale = Locale(identifier: "en_US")
    formatter.minimumFractionDigits = digits
    formatter.maximumFractionDigits = digits
    return formatter
}

private let rounds = 100_000

/// 演練流程（兩個版本共用）：兩個工作同時格式化，和正確答案比對
private func scenario(_ log: LabLog, formatter: PriceFormatter) async {
    let jobs: [(name: String, value: Decimal, digits: Int, expected: String)] = [
        ("BTC_JPY", e06("10234567.891"), 0, "10,234,568"),
        ("XRP_JPY", e06("0.123456"), 4, "0.1235"),
    ]
    let wrong = await withTaskGroup(of: Int.self) { group in
        for job in jobs {
            group.addTask {
                var bad = 0
                for _ in 0..<rounds where formatter.text(job.value, digits: job.digits) != job.expected { bad += 1 }
                return bad
            }
        }
        var total = 0
        for await bad in group { total += bad }
        return total
    }
    log("兩個工作各格式化 \(rounds) 次，共 \(rounds * jobs.count) 次，字串不對的有 \(wrong) 次")
    log("最後：字串不對的有 \(wrong) 次（\(wrong == 0 ? "全部正確" : "有些數字的小數位數錯了")）")
}

// MARK: - Bug 版
private enum L04Bug {
    final class Shared: PriceFormatter, @unchecked Sendable {
        private let formatter = makeFormatter(digits: 0)

        func text(_ value: Decimal, digits: Int) -> String {
            formatter.minimumFractionDigits = digits            // 共用同一個 formatter，每次都改設定
            formatter.maximumFractionDigits = digits
            return formatter.string(from: value as NSDecimalNumber) ?? ""
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, formatter: Shared())
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class PerPrecision: PriceFormatter, @unchecked Sendable {
        private let formatters = Dictionary(uniqueKeysWithValues: (0...4).map { ($0, makeFormatter(digits: $0)) })   // 建好就不再修改

        func text(_ value: Decimal, digits: Int) -> String {
            formatters[digits]?.string(from: value as NSDecimalNumber) ?? ""
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, formatter: PerPrecision())
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l04 = Lab(
        id: "L04",
        title: "同時格式化價格，小數位數錯亂",
        question: "第 6 題：數字怎麼顯示，才不會抖動、也不會拖慢畫面？",
        goal: "formatter 要依精度各建一個、建好就不再修改。共用一個 formatter 又邊用邊改設定，多個執行緒同時使用時，設定會互相覆蓋。",
        steps: [
            "選「Bug 版」執行，看「字串不對的有幾次」。同樣的數字、同樣的程式，為什麼有時候對、有時候錯？",
            "想一想：一條執行緒剛把小數位數設成 0，另一條執行緒就把它設成 4，第一條接著格式化會得到什麼？",
            "選「修正版」執行，要看到字串不對的有 0 次。",
            "進階：Android 的 `DecimalFormat` 不是 thread-safe，連「只使用、不修改」都不安全，要每個執行緒各一個（`ThreadLocal`）。iOS 的 `FormatStyle` 是值型別，沒有這個問題。",
        ],
        expectBug: "20 萬次裡有好幾百次字串不對（次數每次執行都不同，但一定大於 0）：BTC 的價格被格式化成 4 位小數，XRP 的價格被格式化成整數。",
        expectFixed: "20 萬次全部正確：「字串不對的有 0 次（全部正確）」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, formatter:) 執行" }
            if n == 200_000 { return "格式化結果全部都不對：Bug 版要共用一個 formatter，每次先依 digits 改設定，再 string(from:)，這樣只有交錯的時候才會錯" }
            return n > 0 ? nil : "Bug 還沒重現（這次剛好都對）：共用同一個 NumberFormatter，每次格式化都先改 minimumFractionDigits／maximumFractionDigits，再重新執行一次"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, formatter:) 執行" }
            if n == 200_000 { return "格式化結果全部都不對：依 digits 從 formatters 取出對應的 formatter，再 string(from:)" }
            return n == 0 ? nil : "還有 \(n) 次字串不對：不要共用同一個 formatter 又修改它的設定，依精度各建一個，建好就不再修改"
        }
    )
}

private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("最後：字串不對的有") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.first
}
