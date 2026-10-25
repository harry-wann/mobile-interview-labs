// L04：同樣寫 down，負的損益差了 0.01（兩個平台的捨入規則不一樣）
// 對應複習站：Exchange02Precision 第 4 題「iOS 和 Android
// 的捨入規則有什麼差異？」
//
// 情境：損益要顯示到小數 2 位。伺服器有兩種規則（演練假設）：floor
// 是「取比它小的最大值」（負數往更負的方向，-1.239 → -1.24），
//       truncate 是「直接捨去小數」（往 0 的方向，-1.239 → -1.23）。
//       iOS 與 Android 要共用同一份測試資料（規則、輸入、標準答案，
// 兩個平台的文字一模一樣），各自跑一次，不一致就是 bug，而且一定要有負數。
//       （Platform08 的演練 2 練過這個做法，那題看的是 Double；
// 這題看的是兩個平台對 down 的定義不同。）
// Bug 版：兩種規則都照字面選 down。Swift 的 down 是 floor（往負無限大），所以
// floor 的 3 筆對，
//        但 truncate 的負數差了 0.01：-1.239 變成 -1.24，標準答案是 -1.23。
//        （Android 的 RoundingMode.DOWN 是 truncate，剛好反過來：truncate 對，
// floor 的負數錯。）
// 修正版：floor 用 down；truncate 在負數時改用 up（-1.239 往 0
// 的方向是「往上」），正數用 down。
import Foundation
import LabKit

/// 共用測試資料（兩個平台的文字一模一樣）：規則,輸入,標準答案
private let vectors = """
    floor,-1.239,-1.24
    floor,-1.231,-1.24
    floor,1.239,1.23
    truncate,-1.239,-1.23
    truncate,-1.231,-1.23
    truncate,1.239,1.23
    """

/// 演練流程（兩個版本共用）：逐筆跑共用測試資料，捨入到小數 2 位
private func scenario(_ log: LabLog, roundTo2: (Decimal, String) -> Decimal) {
    var mismatches = 0
    let rows = vectors.split(separator: "\n").map {
        $0.split(separator: ",").map(String.init)
    }
    for row in rows {
        let (rule, input, expected) = (row[0], row[1], row[2])
        let result = "\(roundTo2(e02(input), rule))"
        if result == expected {
            log("✓ \(rule)：\(input) → \(result)")
        } else {
            mismatches += 1
            log("✗ \(rule)：\(input) → \(result)（標準答案是 \(expected)）")
        }
    }
    log("共 \(rows.count) 筆共用測試資料，和標準答案不一致 \(mismatches) 筆")
}

// MARK: - Bug 版
private enum L04Bug {
    static func roundTo2(_ value: Decimal, rule: String) -> Decimal {
        var input = value
        var result = Decimal()
        // 兩種規則都用 .down：.down 是 floor，不是往 0 截斷
        NSDecimalRound(&result, &input, 2, .down)
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, roundTo2: { roundTo2($0, rule: $1) })
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func roundTo2(_ value: Decimal, rule: String) -> Decimal {
        var input = value
        var result = Decimal()
        // 往 0 截斷：負數要「往上」
        let mode: NSDecimalNumber.RoundingMode =
            (rule == "truncate" && value < 0) ? .up : .down
        NSDecimalRound(&result, &input, 2, mode)
        return result
    }

    static func run(_ log: LabLog) async {
        scenario(log, roundTo2: { roundTo2($0, rule: $1) })
    }
}

// MARK: - 演練設定
private func mismatchCount(_ lines: [String]) -> Int? {
    guard
        let line = lines.first(where: {
            $0.hasPrefix("共 ") && $0.contains("不一致")
        })
    else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange02Precision {
    static let l04 = Lab(
        id: "L04",
        title: "同樣寫 down，負的損益差了 0.01",
        question: "第 4 題：iOS 和 Android 的捨入規則有什麼差異？",
        goal:
            "損益可能是負數。floor（往負無限大）和 truncate（往 0 截斷）在負數時結果差一個單位，要讓每個規則都和共用測試資料的標準答案一致。",
        steps: [
            "選「Bug 版」執行，看哪幾筆和標準答案不一致，是正數還是負數。",
            "想一想：-1.239 往「下」是 -1.24 還是 -1.23？Swift 的 .down 是哪一種？",
            "選「修正版」執行，6 筆都要和標準答案一致。",
            "進階：Android 的演練用同一份測試資料。RoundingMode.DOWN 是 truncate，FLOOR 才是往負無限大；兩個平台各自通過，才代表規則真的一致。",
        ],
        expectBug:
            "「共 6 筆共用測試資料，和標準答案不一致 2 筆」：truncate 的 -1.239、-1.231 變成 -1.24，標準答案是 -1.23。",
        expectFixed: "「共 6 筆共用測試資料，和標準答案不一致 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = mismatchCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, roundTo2:) 執行"
            }
            if !lines.contains("✓ floor：-1.239 → -1.24") {
                return
                    "Bug 版也要能算：用 NSDecimalRound(…, 2, .down)，floor 的 -1.239 應該是 -1.24"
            }
            return n == 2
                ? nil
                : "Bug 還沒重現：兩種規則都用 .down，truncate 的兩筆負數應該和標準答案差 0.01（目前不一致 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = mismatchCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, roundTo2:) 執行"
            }
            if n == 0 { return nil }
            return
                "還有 \(n) 筆和標準答案不一致，例如：\(lines.first(where: { $0.hasPrefix("✗") }) ?? "")。truncate 遇到負數要用 .up"
        }
    )
}
