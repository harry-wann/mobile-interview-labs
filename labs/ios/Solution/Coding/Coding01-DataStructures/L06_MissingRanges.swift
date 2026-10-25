// L06：算出來的缺口多包了一根已經載入的 K 線，相鄰的兩段還被當成有缺口（區間的 +1、-1 寫錯）
// 對應複習站：Coding01DataStructures 第 5 題「已經載入幾段 K 線，怎麼算出還缺哪幾段？」
//
// 情境：K 線分段載入，區間用 K 線的索引表示，兩端都包含（閉區間），`3...4` 代表第 3、4 根。
//       使用者要看一個範圍，只該請求「還沒載入」的部分。演練用 5 組固定的資料檢查答案。
// Bug 版：先排序，游標從範圍起點往右掃。遇到空隙時，缺口的終點寫成這一段的起點（少減 1，把已經載入的那一根也算進去），
//        掃過一段之後，游標只移到這一段的終點（少加 1）。結果缺口多包一根，相鄰的 `1...3` 與 `4...6` 還被判成中間有缺口。
// 修正版：缺口 = 游標 ... 這一段的起點 - 1；掃過之後游標移到終點 + 1。已經超出範圍終點的區間不用看。
import LabKit

private struct Case {
    let title: String
    let loaded: [ClosedRange<Int>]
    let request: ClosedRange<Int>
    let expected: [ClosedRange<Int>]
}

private let cases = [
    Case(title: "中間缺兩段", loaded: [6...8, 3...4], request: 1...10, expected: [1...2, 5...5, 9...10]),
    Case(title: "相鄰的兩段", loaded: [1...3, 4...6], request: 1...10, expected: [7...10]),
    Case(title: "什麼都沒載入", loaded: [], request: 0...99, expected: [0...99]),
    Case(title: "已經全部載入", loaded: [0...200], request: 10...20, expected: []),
    Case(title: "重疊與範圍外", loaded: [6...9, 1...7, 20...30], request: 5...15, expected: [10...15]),
]

/// 演練流程（兩個版本共用）：5 組資料各算一次，和標準答案比對
private func scenario(_ log: LabLog, missing: ([ClosedRange<Int>], ClosedRange<Int>) -> [ClosedRange<Int>]) {
    var right = 0
    for (index, item) in cases.enumerated() {
        let got = missing(item.loaded, item.request)
        let ok = got == item.expected
        if ok { right += 1 }
        log("案例 \(index + 1)（\(item.title)）：得到 \(c01Text(got))，期待 \(c01Text(item.expected)) \(ok ? "✓" : "✗")")
    }
    log("答對 \(right)/\(cases.count)")
}

// MARK: - Bug 版
private enum L06Bug {
    static func missing(loaded: [ClosedRange<Int>], request: ClosedRange<Int>) -> [ClosedRange<Int>] {
        var gaps: [ClosedRange<Int>] = []
        var cursor = request.lowerBound
        for range in loaded.sorted(by: { $0.lowerBound < $1.lowerBound }) {
            if range.lowerBound > cursor {
                gaps.append(cursor...range.lowerBound)           // 少減 1：缺口多包了這一段的第一根
            }
            cursor = max(cursor, range.upperBound)               // 少加 1：游標停在已經載入的最後一根
        }
        if cursor < request.upperBound {
            gaps.append(cursor...request.upperBound)
        }
        return gaps
    }

    static func run(_ log: LabLog) async {
        scenario(log, missing: { missing(loaded: $0, request: $1) })
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func missing(loaded: [ClosedRange<Int>], request: ClosedRange<Int>) -> [ClosedRange<Int>] {
        var gaps: [ClosedRange<Int>] = []
        var cursor = request.lowerBound
        for range in loaded.sorted(by: { $0.lowerBound < $1.lowerBound }) {
            if range.lowerBound > request.upperBound { break }                    // 這一段已經在範圍外面了
            if range.lowerBound > cursor {
                gaps.append(cursor...(range.lowerBound - 1))                      // 缺口停在這一段的前一根
            }
            cursor = max(cursor, range.upperBound + 1)                            // 游標移到這一段的下一根
        }
        if cursor <= request.upperBound {
            gaps.append(cursor...request.upperBound)                              // 最後剩下的尾巴
        }
        return gaps
    }

    static func run(_ log: LabLog) async {
        scenario(log, missing: { missing(loaded: $0, request: $1) })
    }
}

// MARK: - 演練設定
extension Coding01DataStructures {
    static let l06 = Lab(
        id: "L06",
        title: "缺口多包一根，相鄰區間被當成有缺口",
        question: "第 5 題：已經載入幾段 K 線，怎麼算出還缺哪幾段？",
        goal: "閉區間的缺口是「游標 ... 起點 − 1」，掃過一段後游標移到「終點 + 1」；相鄰的區間中間沒有缺口。",
        steps: [
            "選「Bug 版」執行，看案例 1 與案例 2 得到什麼，和期待的差在哪裡（每一段都差 1）。",
            "選「修正版」執行，確認 5 個案例全部答對，包含相鄰的兩段與範圍外的區間。",
            "想一想：開始寫之前，要先向面試官確認哪兩件事？（區間兩端包不包含、相鄰算不算連續）",
        ],
        expectBug: "案例 1 得到 [1...3, 4...6, 8...10]，案例 2 得到 [3...4, 6...10]；5 組裡答對 2 組。",
        expectFixed: "5 組全部答對：「答對 5/5」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) { return "演練沒有跑完：要用 scenario(log, missing:) 執行" }
            if !lines.contains("案例 1（中間缺兩段）：得到 [1...3, 4...6, 8...10]，期待 [1...2, 5...5, 9...10] ✗") {
                return "Bug 還沒重現：缺口寫成 cursor...range.lowerBound（少減 1），游標只移到 range.upperBound（少加 1）"
            }
            return lines.contains("案例 2（相鄰的兩段）：得到 [3...4, 6...10]，期待 [7...10] ✗")
                ? nil : "Bug 還沒重現：游標少加 1，相鄰的 1...3 與 4...6 才會被當成中間有缺口"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("答對") }) { return "演練沒有跑完：要用 scenario(log, missing:) 執行" }
            let wrong = lines.filter { $0.hasPrefix("案例") && $0.hasSuffix("✗") }
            if let first = wrong.first { return "還有案例答錯：\(first)" }
            return lines.contains("答對 5/5") ? nil : "5 個案例都要答對：缺口 = cursor...(lowerBound - 1)，游標 = upperBound + 1"
        }
    )
}
