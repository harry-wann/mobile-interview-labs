// L04：手指剛好按在一根 K 線的開始時間上，十字線卻跳到前一根；
// 比第一根還早時回傳 -1（二分搜尋的邊界）
// 對應複習站：Coding03ExchangeAlgorithms 第 2 題「手指在 K 線圖上拖曳時，
// 怎麼快速找到對應的 K 線？」
//
// 情境：K 線依開始時間排序。手指換算成時間 t 後，要找「開始時間 <= t
// 的最後一根」的索引；t 比第一根還早就回傳 nil。
//       演練用 6 組固定的資料檢查答案，最後再用 10 萬根 K 線數「探測了幾次」。
// Bug 版：搜尋的是「第一根 >= t」（`times[mid] < t` 才往右），再回傳它的前一根
// `lo - 1`。
//        t 剛好等於某一根的開始時間時，答案少一根；t 比第一根還早時回傳
// -1（不是 nil），拿去當索引會當掉。
// 修正版：搜尋的是「第一根 > t」（`times[mid] <= t` 就往右），
// 答案是它的前一根；`lo == 0` 代表 t 比第一根還早，回傳 nil。
import LabKit

private struct Case {
    let title: String
    let times: [Int]
    let t: Int
    let expected: Int?
}

private let cases = [
    Case(
        title: "落在兩根之間", times: [100, 160, 220, 280, 340], t: 250, expected: 2),
    Case(
        title: "剛好是某一根的開始時間", times: [100, 160, 220, 280, 340], t: 280,
        expected: 3),
    Case(
        title: "比第一根還早", times: [100, 160, 220, 280, 340], t: 50, expected: nil),
    Case(
        title: "比最後一根還晚", times: [100, 160, 220, 280, 340], t: 1000, expected: 4
    ),
    Case(
        title: "有重複的時間，取最後一根", times: [10, 20, 20, 20, 30], t: 20, expected: 3),
    Case(title: "沒有 K 線", times: [], t: 100, expected: nil),
]

private func show(_ value: Int?) -> String {
    value.map(String.init) ?? "nil"
}

/// 演練流程（兩個版本共用）：6 組資料各找一次，再用 10 萬根 K 線數探測次數
private func scenario(
    _ log: LabLog, search: (_ times: [Int], _ t: Int, _ probe: C03Probe) -> Int?
) {
    var right = 0
    for (index, item) in cases.enumerated() {
        let got = search(item.times, item.t, C03Probe())
        let ok = got == item.expected
        if ok { right += 1 }
        log(
            "案例 \(index + 1)（\(item.title)）：t=\(item.t) → 回傳 \(show(got))，期待 \(show(item.expected)) \(ok ? "✓" : "✗")"
        )
    }
    log("答對 \(right)/\(cases.count)")
    let probe = C03Probe()
    let big = (0..<100_000).map { $0 * 60 }
    _ = search(big, 3_000_030, probe)
    log("10 萬根 K 線找一次，探測了 \(probe.count) 次")
}

/// 從「…探測了 N 次」取出 N（自動檢查用）
private func probes(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("10 萬根 K 線找一次，探測了 ") })
    else { return nil }
    return Int(line.dropFirst("10 萬根 K 線找一次，探測了 ".count).dropLast(2))
}

// MARK: - Bug 版
private enum L04Bug {
    static func search(_ times: [Int], _ t: Int, _ probe: C03Probe) -> Int? {
        var lo = 0
        var hi = times.count
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            probe.count += 1
            // 用 <：找的是「第一根 >= t」
            if times[mid] < t {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        // lo 是 0 時回傳 -1，沒有檢查
        return lo - 1
    }

    static func run(_ log: LabLog) async {
        scenario(log, search: search)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func search(_ times: [Int], _ t: Int, _ probe: C03Probe) -> Int? {
        var lo = 0
        // 搜尋範圍是 [lo, hi)：包含 lo，不包含 hi
        var hi = times.count
        while lo < hi {
            let mid = lo + (hi - lo) / 2
            probe.count += 1
            // 用 <=：找的是「第一根 > t」
            if times[mid] <= t {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        // lo 是 0 代表 t 比第一根還早，找不到
        return lo == 0 ? nil : lo - 1
    }

    static func run(_ log: LabLog) async {
        scenario(log, search: search)
    }
}

// MARK: - 演練設定
extension Coding03ExchangeAlgorithms {
    static let l04 = Lab(
        id: "L04",
        title: "十字線跳到前一根，找不到時回傳 -1",
        question: "第 2 題：手指在 K 線圖上拖曳時，怎麼快速找到對應的 K 線？",
        goal: "先決定搜尋範圍是 [lo, hi)，找「第一根 > t」，答案是它的前一根；lo 是 0 就是找不到，回傳 nil。",
        steps: [
            "選「Bug 版」執行，看案例 2（剛好落在開始時間）、案例 3（比第一根早）、案例 5（重複時間）各回傳什麼。",
            "選「修正版」執行，確認 6 個案例全部答對，最後一行的探測次數不到 20 次。",
            "想一想：回傳 -1 的版本，如果呼叫端直接拿它當陣列索引，會發生什麼事？",
        ],
        expectBug:
            "案例 2 回傳 2（期待 3）、案例 3 回傳 -1（期待 nil）、案例 5 回傳 0（期待 3）、案例 6 回傳 -1；6 組只答對 2 組。",
        expectFixed: "「答對 6/6」，10 萬根 K 線只探測 17 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) {
                return "演練沒有跑完：要用 scenario(log, search:) 執行"
            }
            if !lines.contains("案例 2（剛好是某一根的開始時間）：t=280 → 回傳 2，期待 3 ✗") {
                return
                    "Bug 還沒重現：times[mid] 小於 t 才往右，找的是「第一根 >= t」，再回傳 lo - 1，t 剛好等於開始時間時會少一根"
            }
            return lines.contains("案例 3（比第一根還早）：t=50 → 回傳 -1，期待 nil ✗")
                ? nil : "Bug 還沒重現：直接回傳 lo - 1，不檢查 lo 是不是 0，t 比第一根還早時就會回傳 -1"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) {
                return "演練沒有跑完：要用 scenario(log, search:) 執行"
            }
            if let wrong = lines.first(where: {
                $0.hasPrefix("案例") && $0.hasSuffix("✗")
            }) {
                return "還有案例答錯：\(wrong)"
            }
            guard let count = probes(lines) else { return "找不到探測次數那一行" }
            return count <= 20
                ? nil : "10 萬根 K 線探測了 \(count) 次，太多了：每一步要把範圍砍一半（二分搜尋）"
        }
    )
}
