// L08：出庫送出去才被伺服器退回（地址還在審核中，餘額也不夠付手續費）
// 對應複習站：Exchange01Orders 第 6 題「積立、貸暗号資産、
// 入出金和入出庫（含トラベルルール）是什麼？」
//
// 情境：出庫（把幣轉到外部地址）要先登錄地址並通過審核（承認）才能用；
// 轉帳還要付網路手續費。
//       使用者有 0.1 BTC 可用，網路手續費 0.0005 BTC。演練依序試六筆出庫：
//       ① 已承認的地址、0.05 BTC（可以）② 已承認的地址、0.1
// BTC（餘額不夠付手續費）③ 審査中的地址 ④ 否認的地址
//       ⑤ 沒有登錄過的地址 ⑥ 已承認的地址、0.5 BTC（超過餘額）。
//       App 要在送出前先預檢，擋得住的就不要送到伺服器，並說明原因。
// （地址的審核與手續費是演練的假設；實際規則以交易所為準。）
//
// 你要寫：
//   ① Bug 版（L08Bug.precheck）：只檢查地址有沒有登錄過（addresses[address]
// 不是 nil），以及 amount 不超過 available。
//   ② 修正版（L08Fixed.precheck）：地址的狀態必須是 approved，而且 amount +
// networkFee 不能超過 available。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange01-Orders/L08_WithdrawPrecheck.swift
import Foundation
import LabKit

private enum Review: String {
    case reviewing = "審査中"
    case approved = "承認"
    case rejected = "否認"
}

private let addresses: [String: Review] = [
    "bc1q-approved": .approved, "bc1q-reviewing": .reviewing,
    "bc1q-rejected": .rejected,
]
private let available = e01("0.1")
private let networkFee = e01("0.0005")

/// 假伺服器：不管 App 預檢有沒有擋，伺服器一定會再驗證一次
private func serverCheck(address: String, amount: Decimal) -> String? {
    if addresses[address] != .approved { return "地址還沒通過審核" }
    if amount + networkFee > available { return "餘額不足以支付手續費" }
    return nil
}

/// 演練流程（兩個版本共用）：六筆出庫依序預檢、送出
private func scenario(_ log: LabLog, precheck: (String, Decimal) -> String?) {
    let requests: [(String, String)] = [
        ("bc1q-approved", "0.05"), ("bc1q-approved", "0.1"),
        ("bc1q-reviewing", "0.01"), ("bc1q-rejected", "0.01"),
        ("bc1q-unknown", "0.01"), ("bc1q-approved", "0.5"),
    ]
    var sent = 0
    var refused = 0
    for (address, text) in requests {
        let amount = e01(text)
        if let reason = precheck(address, amount) {
            log("出庫 \(amount) BTC → \(address)：App 預檢擋下（\(reason)）")
            continue
        }
        sent += 1
        if let reason = serverCheck(address: address, amount: amount) {
            refused += 1
            log("出庫 \(amount) BTC → \(address)：預檢放行，伺服器退回（\(reason)）")
        } else {
            log("出庫 \(amount) BTC → \(address)：預檢放行，伺服器受理")
        }
    }
    log("送到伺服器的出庫 \(sent) 筆，被伺服器退回 \(refused) 筆")
}

// MARK: - Bug 版
private enum L08Bug {
    static func precheck(address: String, amount: Decimal) -> String? {
        // TODO ①：檢查 1) addresses[address] 不是 nil，否則回傳
        // "地址沒有登錄過"；
        //        2) amount <= available，否則回傳 "餘額不足"（不要算手續費）。
        // 都通過回傳 nil
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, precheck: precheck(address:amount:))
    }
}

// MARK: - 修正版
private enum L08Fixed {
    static func precheck(address: String, amount: Decimal) -> String? {
        // TODO ②：檢查 1) 地址有登錄過；2) 地址的狀態是 approved，
        // 否則回傳原因（可以用 review.rawValue 說明現在的狀態）；
        //        3) amount + networkFee <= available，否則回傳原因。都通過回傳
        // nil
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, precheck: precheck(address:amount:))
    }
}

// MARK: - 演練設定
extension Exchange01Orders {
    static let l08 = Lab(
        id: "L08",
        title: "出庫送出去才被退回，地址還在審核中",
        question: "第 6 題：積立、貸暗号資産、入出金和入出庫（含トラベルルール）是什麼？",
        goal:
            "出庫前，App 先預檢：地址要通過審核、數量加手續費不能超過可用餘額。擋得住的不要送到伺服器，並告訴使用者原因。先寫出預檢太鬆的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange01-Orders/L08_WithdrawPrecheck.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看六筆出庫各自的結果、有幾筆被伺服器退回。",
            "想一想：地址「審査中」時，預檢只檢查「有登錄過」夠嗎？使用者轉出 0.1 BTC，還剩多少可以付手續費？",
            "寫 TODO ②（修正版），選「修正版」執行，只有第一筆送到伺服器。",
            "進階：地址審核是非同步的，畫面要畫出「審査中、承認、否認」三種狀態，並用推播通知結果；預檢只是提早擋錯，最終以伺服器為準。",
        ],
        expectBug:
            "沒登錄過的地址、超過餘額的那兩筆被擋下，其餘四筆都放行，其中三筆被伺服器退回：「送到伺服器的出庫 4 筆，被伺服器退回 3 筆」。",
        expectFixed:
            "只有已承認地址的 0.05 BTC 送到伺服器，其餘五筆在 App 就被擋下並說明原因：「送到伺服器的出庫 1 筆，被伺服器退回 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L08Bug.run(log)
            case .fixed: await L08Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("送到伺服器的出庫") }) {
                return "演練沒有跑完：要用 scenario(log, precheck:) 執行"
            }
            return lines.contains("送到伺服器的出庫 4 筆，被伺服器退回 3 筆")
                ? nil
                : "Bug 還沒重現：預檢只檢查地址有登錄過、數量不超過 available，審査中、否認的地址和 0.1 BTC 會放行，四筆送到伺服器、三筆被退回"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("送到伺服器的出庫") }) {
                return "演練沒有跑完：要用 scenario(log, precheck:) 執行"
            }
            return lines.contains("送到伺服器的出庫 1 筆，被伺服器退回 0 筆")
                ? nil : "預檢要擋下：地址不是承認（.approved），以及數量加手續費超過 available"
        }
    )
}
