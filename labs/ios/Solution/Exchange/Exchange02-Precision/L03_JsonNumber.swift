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
// Bug 版：資料模型的 balance 宣告成 Double。解析後變成 1.2345678901234567，
// 和伺服器的原文對不起來。
// 修正版：balance 宣告成 Decimal。JSONDecoder 會直接把數字文字轉成 Decimal，
// 不經過 Double，位數完整保留。
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
        let balance: Double  // 用 Double 接
    }

    static func parse(_ data: Data) -> String {
        guard let account = try? JSONDecoder().decode(Account.self, from: data)
        else { return "解析失敗" }
        return "\(account.balance)"
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    struct Account: Decodable {
        let symbol: String
        let balance: Decimal  // 用 Decimal 接
    }

    static func parse(_ data: Data) -> String {
        guard let account = try? JSONDecoder().decode(Account.self, from: data)
        else { return "解析失敗" }
        return "\(account.balance)"
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
        goal: "API 回傳的金額有很多位小數時，解析後的數值要和伺服器的原文一模一樣。資料模型的欄位要用什麼型別？",
        steps: [
            "選「Bug 版」執行，比較「App 讀到的 balance」和伺服器原文差在哪。",
            "想一想：Double 有幾位有效數字？1.234567890123456789 有幾位？",
            "選「修正版」執行，位數要完整保留。",
            "進階：Android 用 Gson 把欄位宣告成 BigDecimal 也會直接讀成精確的值；如果能改 API，讓伺服器把金額用字串傳最保險。",
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
