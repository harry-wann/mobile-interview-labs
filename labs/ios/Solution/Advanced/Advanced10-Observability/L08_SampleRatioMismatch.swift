// L08：A/B 測試的人數比例明明不是 50 比 50，卻宣布 B 勝出（要先檢查 SRM）
// 對應複習站：Advanced10Observability 第 6 題「A/B 測試有哪些統計陷阱？」（SRM）
//
// 情境：三個實驗都設計成 50% 對 50% 分流。演練有三組實際的人數與轉換率：
//         實驗 1：A 50080 人、B 49920 人，B 轉換率較高。
//         實驗 2：A 52500 人、B 47500 人，B 轉換率較高（分流有問題）。
//         實驗 3：A 50800 人、B 49200 人，B 轉換率較高（分流有問題）。
//       SRM（sample ratio mismatch，樣本比例不符）：實際人數比例偏離設計得太多，代表分流或事件記錄有 bug
//       （例如 B 組的畫面更容易 crash 或載入失敗，事件因此遺失），這時轉換率的比較已經被汙染。
// Bug 版：不檢查人數比例，哪一組轉換率高就宣布哪一組勝出。三個實驗全部「B 勝出」。
// 修正版：先做卡方檢定（chi-square，自由度 1）：Σ (實際 − 預期)² ÷ 預期；大於 10.83（p 值小於 0.001）就判定 SRM，
//        不下結論，先查分流或事件記錄的問題。只有實驗 1 可以宣布 B 勝出。
import Foundation
import LabKit

private struct Experiment {
    let name: String
    let a: Int
    let b: Int
    let aRate: Double       // 轉換率（%）
    let bRate: Double
}

private let experiments = [
    Experiment(name: "實驗 1", a: 50_080, b: 49_920, aRate: 4.8, bRate: 5.0),
    Experiment(name: "實驗 2", a: 52_500, b: 47_500, aRate: 4.8, bRate: 5.1),
    Experiment(name: "實驗 3", a: 50_800, b: 49_200, aRate: 4.8, bRate: 5.0),
]

/// 演練流程（兩個版本共用）：hasSRM(A 組人數, B 組人數) 回傳要不要判定為 SRM（預期各 50%）
private func scenario(_ log: LabLog, hasSRM: (Int, Int) -> Bool) {
    for e in experiments {
        let verdict = hasSRM(e.a, e.b)
            ? "分流異常（SRM），結果不可信，先查原因"
            : (e.bRate > e.aRate ? "B 勝出" : "A 勝出")
        log("\(e.name)：A \(e.a) 人、B \(e.b) 人，轉換率 A \(e.aRate)%／B \(e.bRate)% → \(verdict)")
    }
}

// MARK: - Bug 版
private enum L08Bug {
    static func hasSRM(_ a: Int, _ b: Int) -> Bool {
        false                                                   // 沒有檢查人數比例
    }

    static func run(_ log: LabLog) async {
        scenario(log, hasSRM: hasSRM)
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static func hasSRM(_ a: Int, _ b: Int) -> Bool {
        let expected = Double(a + b) / 2                        // 設計是 50% 對 50%
        let chiSquare = [a, b].reduce(0.0) { sum, observed in
            sum + pow(Double(observed) - expected, 2) / expected
        }
        return chiSquare > 10.83                                // 自由度 1，p 值 0.001 的門檻
    }

    static func run(_ log: LabLog) async {
        scenario(log, hasSRM: hasSRM)
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l08 = Lab(
        id: "L08",
        title: "人數比例不對，卻宣布 B 勝出",
        question: "第 6 題：A/B 測試有哪些統計陷阱？（SRM）",
        goal: "比較轉換率之前，先檢查兩組的人數比例是否符合設計；差太多（SRM）就代表分流或事件記錄有問題，結果不能用。",
        steps: [
            "選「Bug 版」執行，看三個實驗的結論。人數 52500 對 47500，設計是 50 對 50，這正常嗎？",
            "想一想：設計 50/50 的實驗，實際 50080 對 49920 是隨機誤差，52500 對 47500 呢？為什麼不能只看轉換率？",
            "選「修正版」執行：只有實驗 1 通過 SRM 檢查，實驗 2、3 被擋下。",
            "進階：卡方值 = Σ (實際 − 預期)² ÷ 預期。實驗 1 約 0.5；實驗 3 約 25.6，超過 10.83。偵測到 SRM 時，要先查是不是某組的畫面更容易 crash、載入失敗，事件因此遺失。",
        ],
        expectBug: "三個實驗都是「B 勝出」。",
        expectFixed: "實驗 1：「B 勝出」；實驗 2、3：「分流異常（SRM），結果不可信，先查原因」。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("實驗 1") }) else { return "演練沒有跑完：要用 scenario 執行" }
            let winners = lines.filter { $0.hasSuffix("→ B 勝出") }.count
            if winners != 3 { return "Bug 還沒重現：hasSRM 一律回傳 false，三個實驗都應該宣布 B 勝出（目前 \(winners) 個）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("實驗 1") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !(lines.first { $0.hasPrefix("實驗 1") }?.hasSuffix("→ B 勝出") ?? false) { return "實驗 1（50080 對 49920）是正常的隨機誤差，不該被判成 SRM" }
            if !(lines.first { $0.hasPrefix("實驗 2") }?.hasSuffix("先查原因") ?? false) { return "實驗 2（52500 對 47500）應該被判成 SRM：卡方值要大於 10.83" }
            if !(lines.first { $0.hasPrefix("實驗 3") }?.hasSuffix("先查原因") ?? false) { return "實驗 3（50800 對 49200）應該被判成 SRM：卡方值約 25.6，大於 10.83" }
            return nil
        }
    )
}
