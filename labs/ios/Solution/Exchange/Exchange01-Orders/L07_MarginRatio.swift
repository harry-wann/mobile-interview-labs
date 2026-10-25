// L07：價格跌了 40%，維持率還顯示 125%，從來沒有追証，
// 也沒有ロスカット（槓桿的維持率）
// 對應複習站：Exchange01Orders 第 5 題「槓桿交易的証拠金、維持率、追証、
// ロスカット是什麼？」
//
// 情境：使用者放 1,000,000 円的証拠金，用 1.6 倍槓桿以 10,000,000 円買進 0.16
// BTC（建玉 1,600,000 円）。
//       假設必要証拠金固定是建玉的一半，800,000 円；維持率低於 100% 要追証，
// 低於 50% 強制平倉（ロスカット）。
//       （這些數字與門檻都是為了說明假設的，不是交易所的規則。
// 畫面上的維持率真正要以伺服器推送的值為準，這個演練只練算法。）
//       維持率 = 有効証拠金 ÷ 必要証拠金；有効証拠金 = 証拠金 + 評価損益。
// 價格依序變成：不變、−10%、−15%、−40%。
// Bug 版：有効証拠金直接用放進去的証拠金 1,000,000 円，沒有加上評価損益。
// 維持率永遠是 125%，價格跌多少都顯示「正常」。
// 修正版：有効証拠金 = 証拠金 + 評価損益，評価損益 =（目前價格 − 買進價格）×
// 數量。
import Foundation
import LabKit

private enum Health: String {
    case normal = "正常"
    case marginCall = "追証"
    case liquidation = "ロスカット"
}

private let deposit = e01("1000000")  // 証拠金
// 必要証拠金（演練假設固定不變）
private let required = e01("800000")
private let entry = e01("10000000")  // 買進價格
private let quantity = e01("0.16")  // 持有 BTC 數量

private func health(ofRatio ratio: Decimal) -> Health {
    if ratio >= 1 { return .normal }  // 維持率 100% 以上
    return ratio >= e01("0.5") ? .marginCall : .liquidation
}

/// 演練流程（兩個版本共用）：價格依序下跌，每一步算維持率並判斷狀態
private func scenario(
    _ log: LabLog, effectiveMargin: (Decimal, Decimal) -> Decimal
) {
    var liquidatedAt: Decimal?
    for text in ["10000000", "9000000", "8500000", "6000000"] {
        let price = e01(text)
        let pnl = (price - entry) * quantity  // 評価損益
        let ratio = effectiveMargin(deposit, pnl) / required
        let state = health(ofRatio: ratio)
        log("價格 \(price)：評価損益 \(pnl)，維持率 \(ratio * 100)%，\(state.rawValue)")
        if state == .liquidation, liquidatedAt == nil { liquidatedAt = price }
    }
    log("被強制平倉（ロスカット）的價格：\(liquidatedAt.map { "\($0)" } ?? "沒有")")
}

// MARK: - Bug 版
private enum L07Bug {
    static func effectiveMargin(deposit: Decimal, pnl: Decimal) -> Decimal {
        // 只看放進去的証拠金，沒有加上評価損益
        deposit
    }

    static func run(_ log: LabLog) async {
        scenario(log, effectiveMargin: effectiveMargin(deposit:pnl:))
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static func effectiveMargin(deposit: Decimal, pnl: Decimal) -> Decimal {
        // 有効証拠金 = 証拠金 + 評価損益（虧損時 pnl 是負數）
        deposit + pnl
    }

    static func run(_ log: LabLog) async {
        scenario(log, effectiveMargin: effectiveMargin(deposit:pnl:))
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l07 = Lab(
        id: "L07",
        title: "跌了 40%，維持率還顯示 125%",
        question: "第 5 題：槓桿交易的証拠金、維持率、追証、ロスカット是什麼？",
        goal:
            "維持率 = 有効証拠金 ÷ 必要証拠金，有効証拠金要把評価損益算進去。價格下跌時，維持率要跟著降，並在門檻觸發追証與ロスカット。",
        steps: [
            "選「Bug 版」執行，看四個價格的維持率與狀態。",
            "想一想：價格跌了，使用者帳面上虧了 160,000 円，有効証拠金還是 1,000,000 円嗎？",
            "選「修正版」執行，對照 125%／105%／95%／45% 與各自的狀態。",
            "進階：維持率會隨價格一直變，畫面上要用伺服器推送的值；App 自己用過期的價格推算，會讓使用者在危險的時候看到安全的數字。",
        ],
        expectBug: "四個價格的維持率都是 125%，狀態都是「正常」，「被強制平倉（ロスカット）的價格：沒有」。",
        expectFixed:
            "維持率依序 125%、105%（正常）、95%（追証）、45%（ロスカット），「被強制平倉（ロスカット）的價格：6000000」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("被強制平倉") }) {
                return "演練沒有跑完：要用 scenario(log, effectiveMargin:) 執行"
            }
            if !lines.contains("價格 6000000：評価損益 -640000，維持率 125%，正常") {
                return "Bug 還沒重現：有効証拠金直接回傳 deposit，維持率一直是 125%"
            }
            return lines.contains("被強制平倉（ロスカット）的價格：沒有") ? nil : "Bug 版從來不會ロスカット"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("被強制平倉") }) {
                return "演練沒有跑完：要用 scenario(log, effectiveMargin:) 執行"
            }
            if !lines.contains("價格 8500000：評価損益 -240000，維持率 95%，追証") {
                return "有効証拠金 = deposit + pnl：跌 15% 時維持率應該是 95%，要追証"
            }
            return lines.contains("被強制平倉（ロスカット）的價格：6000000")
                ? nil : "跌到 6000000 時維持率 45%，應該ロスカット"
        }
    )
}
