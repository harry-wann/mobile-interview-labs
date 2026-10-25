// L06：停利單成交了，停損單還在，後來又被觸發，賣掉了根本沒有的 BTC（OCO）
// 對應複習站：Exchange01Orders 第 4 題「OCO、IFD、IFDOCO 這些組合單是怎麼運作的？」
//
// 情境：使用者持有 0.1 BTC，掛了一組 OCO（二選一）：賣出指値 10,300,000 円（停利）加賣出逆指値、觸發價 9,500,000 円（停損），各 0.1 BTC。
//       價格先漲到停利價，停利單成交；之後價格暴跌到停損價。OCO 的規則：其中一張成交，另一張要自動取消。
//       （這是組合單的狀態機：伺服器用它決定每一張單的狀態，App 用同一套規則顯示。）
//
// 你要寫：
//   ① Bug 版（L06Bug.onFilled）：成交的那一張標成 .filled，其他什麼都不做。
//   ② 修正版（L06Fixed.onFilled）：成交的那一張標成 .filled；另一張如果還是 .active，改成 .cancelled。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange01-Orders/L06_OcoCancel.swift
import Foundation
import LabKit

private enum Leg { case takeProfit, stopLoss }
private enum LegStatus: String { case active, filled, cancelled }

/// OCO 組合單的狀態
private struct OcoGroup {
    var takeProfit: LegStatus = .active
    var stopLoss: LegStatus = .active

    subscript(leg: Leg) -> LegStatus {
        get { leg == .takeProfit ? takeProfit : stopLoss }
        set { if leg == .takeProfit { takeProfit = newValue } else { stopLoss = newValue } }
    }
}

/// 演練流程（兩個版本共用）：價格依序變動，每一張還在 active 的單檢查有沒有觸發
private func scenario(_ log: LabLog, onFilled: (inout OcoGroup, Leg) -> Void) {
    var group = OcoGroup()
    var position = e01("0.1")                               // 持有的 BTC
    let prices = ["10000000", "10300000", "9800000", "9500000"].map { e01($0) }
    log("持有 0.1 BTC，OCO：停利 10300000 ／ 停損 9500000")
    for price in prices {
        if group.takeProfit == .active, price >= e01("10300000") {
            position -= e01("0.1")
            onFilled(&group, .takeProfit)
            log("價格 \(price)：停利單成交，賣出 0.1 BTC，持倉 \(position) BTC")
        }
        if group.stopLoss == .active, price <= e01("9500000") {
            position -= e01("0.1")
            onFilled(&group, .stopLoss)
            log("價格 \(price)：停損單被觸發，賣出 0.1 BTC，持倉 \(position) BTC")
        }
    }
    log("最後狀態：停利 \(group.takeProfit.rawValue)、停損 \(group.stopLoss.rawValue)，持倉 \(position) BTC")
}

// MARK: - Bug 版
private enum L06Bug {
    static func onFilled(group: inout OcoGroup, leg: Leg) {
        // TODO ①：把成交的這一張（group[leg]）標成 .filled，其他什麼都不做
    }

    static func run(_ log: LabLog) async {
        scenario(log) { group, leg in onFilled(group: &group, leg: leg) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func onFilled(group: inout OcoGroup, leg: Leg) {
        // TODO ②-1：把成交的這一張（group[leg]）標成 .filled
        // TODO ②-2：找出另一張（停利的另一張是停損，停損的另一張是停利），如果它還是 .active，改成 .cancelled
    }

    static func run(_ log: LabLog) async {
        scenario(log) { group, leg in onFilled(group: &group, leg: leg) }
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l06 = Lab(
        id: "L06",
        title: "停利成交了，停損單還在、又賣了一次",
        question: "第 4 題：OCO、IFD、IFDOCO 這些組合單是怎麼運作的？",
        goal: "OCO 是二選一：其中一張成交，另一張要自動取消，之後不能再被觸發。先寫出另一張單沒被取消的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange01-Orders/L06_OcoCancel.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看價格跌到 9500000 時發生什麼事、最後的持倉。",
            "想一想：停利單成交時，狀態表裡另一張單的狀態是什麼？",
            "寫 TODO ②（修正版），選「修正版」執行，看最後狀態和持倉。",
            "進階：IFD 是「第一張成交，才送出第二張」，IFDOCO 是 IFD 加 OCO。它們都是伺服器端的狀態機，App 負責輸入驗證、顯示每一張單的狀態與取消。",
        ],
        expectBug: "停利成交後持倉 0，價格跌到 9500000 時停損單又被觸發，「最後狀態：停利 filled、停損 filled，持倉 -0.1 BTC」。",
        expectFixed: "停利成交時停損單改成 cancelled，價格跌到 9500000 什麼都不會發生，「最後狀態：停利 filled、停損 cancelled，持倉 0 BTC」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後狀態") }) { return "演練沒有跑完：要用 scenario(log, onFilled:) 執行" }
            return lines.contains("最後狀態：停利 filled、停損 filled，持倉 -0.1 BTC")
                ? nil : "Bug 還沒重現：onFilled 只把成交的那一張標成 filled，另一張維持 active，後來會被再次觸發"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後狀態") }) { return "演練沒有跑完：要用 scenario(log, onFilled:) 執行" }
            return lines.contains("最後狀態：停利 filled、停損 cancelled，持倉 0 BTC")
                ? nil : "一張成交時，另一張如果還是 active，要改成 cancelled"
        }
    )
}
