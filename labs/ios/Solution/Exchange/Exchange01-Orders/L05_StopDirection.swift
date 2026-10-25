// L05：想設停損，送出去的逆指値卻立刻成交了（觸發價的方向）
// 對應複習站：Exchange01Orders 第 3 題「成行、指値、
// 逆指値這三種基本的下單方式有什麼差別？」
//
// 情境：BTC 現價 10,000,000 円。逆指値是「價格到達觸發價才送出實際委託」：
//       賣出的逆指値（停損），觸發價要低於現價，跌到才賣；
// 買入的逆指値（突破追買），觸發價要高於現價，漲到才買。
//       方向放錯邊，伺服器收到時價格已經滿足條件，單子會立刻被觸發。
//       App 在送出前要先檢查方向。演練依序試四張單：賣出 9,500,000（正確）、
// 賣出 10,500,000（放錯邊）、買入 10,500,000（正確）、買入 9,500,
// 000（放錯邊）。
// Bug 版：檢查方向寫反了：賣出要求觸發價「高於」現價，買入要求「低於」現價。
// 正確的單被擋下，放錯邊的單反而被放行，伺服器立刻觸發。
// 修正版：賣出要求觸發價低於現價，買入要求觸發價高於現價；不符合就在 App
// 端擋下，不送出。
import Foundation
import LabKit

private enum Side: String {
    case buy = "買入"
    case sell = "賣出"
}

private let lastPrice = e01("10000000")

/// 假伺服器：收到逆指値後，如果現價已經滿足觸發條件，就立刻觸發
private func serverResult(side: Side, trigger: Decimal) -> String {
    let triggered = side == .sell ? lastPrice <= trigger : lastPrice >= trigger
    return triggered
        ? "伺服器：現價已經滿足條件，立刻觸發，以 \(lastPrice) 成交" : "伺服器：進入待機，等價格到 \(trigger) 才觸發"
}

/// 演練流程（兩個版本共用）：四張單依序檢查、送出
private func scenario(
    _ log: LabLog, validate: (Side, Decimal, Decimal) -> String?
) {
    let cases: [(Side, String, Bool)] = [
        (.sell, "9500000", true), (.sell, "10500000", false),
        (.buy, "10500000", true), (.buy, "9500000", false),
    ]
    var wrongBlocked = 0
    var instantlyTriggered = 0
    for (side, text, isCorrect) in cases {
        let trigger = e01(text)
        let name = "\(side.rawValue)逆指値 觸發價 \(trigger)"
        if let error = validate(side, trigger, lastPrice) {
            log("\(name)：App 擋下（\(error)）")
            if isCorrect { wrongBlocked += 1 }
        } else {
            let result = serverResult(side: side, trigger: trigger)
            log("\(name)：App 放行。\(result)")
            if result.contains("立刻觸發") { instantlyTriggered += 1 }
        }
    }
    log("正確的單被誤擋 \(wrongBlocked) 張，放錯邊卻立刻觸發 \(instantlyTriggered) 張")
}

// MARK: - Bug 版
private enum L05Bug {
    static func validate(
        side: Side, trigger: Decimal, lastPrice: Decimal
    ) -> String? {
        switch side {
        // 寫反了
        case .sell: return trigger > lastPrice ? nil : "賣出的觸發價要高於現價"
        case .buy: return trigger < lastPrice ? nil : "買入的觸發價要低於現價"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, validate: validate(side:trigger:lastPrice:))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func validate(
        side: Side, trigger: Decimal, lastPrice: Decimal
    ) -> String? {
        switch side {
        // 停損：跌到才賣
        case .sell: return trigger < lastPrice ? nil : "賣出的觸發價要低於現價"
        // 追買：漲到才買
        case .buy: return trigger > lastPrice ? nil : "買入的觸發價要高於現價"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, validate: validate(side:trigger:lastPrice:))
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l05 = Lab(
        id: "L05",
        title: "想設停損，逆指値送出去卻立刻成交",
        question: "第 3 題：成行、指値、逆指値這三種基本的下單方式有什麼差別？",
        goal: "逆指値的觸發價要放在現價的正確一邊：賣出（停損）在現價下方，買入（追買）在現價上方。App 要在送出前擋下放錯邊的單。",
        steps: [
            "選「Bug 版」執行，看四張單各被擋下還是放行，放行後伺服器的反應。",
            "想一想：現價 10,000,000，賣出逆指値觸發價 10,500,000，伺服器收到時條件滿足了嗎？",
            "選「修正版」執行，正確的兩張單進入待機，放錯邊的兩張在 App 端就被擋下。",
            "進階：觸發由伺服器判斷，App 進背景或斷線也會生效；App 只負責輸入驗證，不能自己盯價格再下單。",
        ],
        expectBug:
            "賣出 9500000 與買入 10500000 被誤擋；賣出 10500000 與買入 9500000 被放行，伺服器立刻以 10000000 成交，「正確的單被誤擋 2 張，放錯邊卻立刻觸發 2 張」。",
        expectFixed:
            "賣出 9500000、買入 10500000 進入待機；另外兩張在 App 擋下，「正確的單被誤擋 0 張，放錯邊卻立刻觸發 0 張」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("正確的單被誤擋") }) {
                return "演練沒有跑完：要用 scenario(log, validate:) 執行"
            }
            return lines.contains("正確的單被誤擋 2 張，放錯邊卻立刻觸發 2 張")
                ? nil : "Bug 還沒重現：賣出要求觸發價高於現價、買入要求觸發價低於現價（方向寫反），四張單全都判斷錯"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("正確的單被誤擋") }) {
                return "演練沒有跑完：要用 scenario(log, validate:) 執行"
            }
            return lines.contains("正確的單被誤擋 0 張，放錯邊卻立刻觸發 0 張")
                ? nil : "賣出的觸發價要低於現價，買入的要高於現價"
        }
    )
}
