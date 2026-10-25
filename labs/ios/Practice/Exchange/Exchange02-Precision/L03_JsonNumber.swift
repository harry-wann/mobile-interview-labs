// L03：伺服器回 1.234567890123456789 ETH，App 讀進來卻是 1.
// 2345678901234567（JSON 的數字被當成 Double）
// 對應複習站：Exchange02Precision 第 3 題「API 回來的數字要怎麼讀，
// 才不會一開始就不準？」
//
// 情境：資產 API 回傳 JSON，balance 是一個有 18 位小數的數字（ETH 最小單位 wei
// 是小數第 18 位）：
//       {"symbol":"ethjpy","balance":1.234567890123456789}
//       App 用 JSONDecoder 解析。Double 只有 15 到 17 位有效數字，
// 超過的位數在讀進來的那一刻就丟了。
//
// 你要寫：
//   ① Bug 版（L03Bug）：Account 的 balance 宣告成 Double，用 JSONDecoder 解析。
//   ② 修正版（L03Fixed）：balance 改宣告成 Decimal。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange02-Precision/L03_JsonNumber.swift
import Foundation
import LabKit

private let json = #"{"symbol":"ethjpy","balance":1.234567890123456789}"#
private let original = "1.234567890123456789"

/// 演練流程（兩個版本共用）：解析 JSON，和伺服器的原文比對
private func scenario(_ log: LabLog, parse: (Data) -> String) {
    log("伺服器回傳：\(json)")
    let parsed = parse(Data(json.utf8))
    log("App 讀到 balance = \(parsed)")
    log(parsed == original ? "和伺服器的原文一致" : "和伺服器的原文 \(original) 不一致，位數被吃掉了")
}

// MARK: - Bug 版
private enum L03Bug {
    struct Account: Decodable {
        let symbol: String
        // TODO ①-1：宣告 balance，型別用 Double
    }

    static func parse(_ data: Data) -> String {
        // TODO ①-2：用 JSONDecoder().decode 把 data 解析成 Account；失敗回傳
        // "解析失敗"，成功回傳 "\(account.balance)"
        return "解析失敗"
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    struct Account: Decodable {
        let symbol: String
        // TODO ②-1：宣告 balance，這次型別用 Decimal
    }

    static func parse(_ data: Data) -> String {
        // TODO ②-2：一樣用 JSONDecoder().decode 解析成 Account；失敗回傳
        // "解析失敗"，成功回傳 "\(account.balance)"
        return "解析失敗"
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 演練設定
extension Exchange02Precision {
    static let l03 = Lab(
        id: "L03",
        title: "伺服器回的位數，讀進來少了幾位",
        question: "第 3 題：API 回來的數字要怎麼讀，才不會一開始就不準？",
        goal:
            "API 回傳的金額有很多位小數時，解析後的數值要和伺服器的原文一模一樣。資料模型的欄位要用什麼型別？先寫出位數被吃掉的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Exchange/Exchange02-Precision/L03_JsonNumber.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，比較「App 讀到的 balance」和伺服器原文差在哪。",
            "想一想：Double 有幾位有效數字？1.234567890123456789 有幾位？",
            "寫 TODO ②（修正版），選「修正版」執行，位數要完整保留。",
            "進階：解答實測在 iOS 26.3 模擬器上，JSONDecoder 能把 JSON 數字直接讀成精確的 Decimal；較舊的系統版本不一定，要支援舊版本時，最保險的做法是讓伺服器把金額用字串傳。Android 用 Gson 把欄位宣告成 BigDecimal 也會直接讀成精確的值。",
        ],
        expectBug:
            "「App 讀到 balance = 1.2345678901234567」，「和伺服器的原文 1.234567890123456789 不一致，位數被吃掉了」。",
        expectFixed: "「App 讀到 balance = 1.234567890123456789」，「和伺服器的原文一致」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("App 讀到") }) else {
                return "演練沒有跑完：要用 scenario(log, parse:) 執行"
            }
            if lines.contains("App 讀到 balance = 解析失敗") {
                return
                    "還沒讀到 balance：Account 要宣告 balance 欄位（Double），再用 JSONDecoder().decode 解析"
            }
            return lines.contains("App 讀到 balance = 1.2345678901234567")
                ? nil : "Bug 還沒重現：balance 宣告成 Double，讀進來會變成 1.2345678901234567"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("App 讀到") }) else {
                return "演練沒有跑完：要用 scenario(log, parse:) 執行"
            }
            return lines.contains("和伺服器的原文一致")
                ? nil : "位數還是被吃掉了：Account.balance 要宣告成 Decimal，不要經過 Double"
        }
    )
}
