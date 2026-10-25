// L01：明明有排序，簽名還是被拒絕（參數排序要用 ASCII 順序）
// 對應複習站：Exchange09Signing 第 1 題「為什麼 API 請求要簽名？這套交易所 API
// 的 Signature V2 怎麼做？」
//
// 情境：Signature V2 要求把參數「依 ASCII 順序」排序，再串成簽名字串。
// 演練送出兩個簽名請求：
//       ① 複習站的 test vector：GET /v1/order/orders?order-id=1234567890，
// 簽名應該是 KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=
//       ② GET /v1/order/matchresults?symbol=btcjpy&size=10
//       假伺服器用自己保存的 Secret Key，照規則重新算一次簽名，不一致就回
// api-signature-not-valid。
//       （HMAC、Base64、URL 編碼都已經寫好在 Exchange09Support.swift，
// 這題只看排序。）
//
// 你要寫：
//   ① Bug 版（L01Bug.sorted）：依名稱轉成小寫之後排序（不分大小寫，
// 一般人會想到的「字母順序」）。
//   ② 修正版（L01Fixed.sorted）：依名稱的 UTF-8 byte 順序（ASCII 順序）排序，
// 不轉小寫。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange09-Signing/L01_ParamOrder.swift
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：送出兩個簽名請求，假伺服器照規則重新算簽名
private func scenario(
    _ log: LabLog, sorted: ([(String, String)]) -> [(String, String)]
) {
    var rejected = 0
    let requests: [(path: String, own: [(String, String)])] = [
        ("/v1/order/orders", [("order-id", "1234567890")]),
        ("/v1/order/matchresults", [("symbol", "btcjpy"), ("size", "10")]),
    ]
    for (index, request) in requests.enumerated() {
        // 自己的參數在前、認證參數在後，由 sorted 排好
        let ordered = sorted(request.own + E09.authParams())
        log(
            "\(request.path) 排序後的參數：\(ordered.map(\.0).joined(separator: ", "))"
        )
        let query = ordered.map {
            "\(E09.percentEncode($0.0))=\(E09.percentEncode($0.1))"
        }.joined(separator: "&")
        let signature = E09.hmacBase64(
            "GET\n\(E09.host)\n\(request.path)\n\(query)")
        if index == 0 {
            log("test vector 比對：\(signature == E09.getVector ? "一致" : "不一致")")
        }
        let sent = query + "&Signature=" + E09.percentEncode(signature)
        if let error = E09.verifyRest(
            method: "GET", host: E09.host, path: request.path, rawQuery: sent)
        {
            rejected += 1
            log("\(request.path)：伺服器拒絕（\(error)）")
        } else {
            log("\(request.path)：伺服器受理")
        }
    }
    log("2 個請求，被伺服器拒絕 \(rejected) 個")
}

// MARK: - Bug 版
private enum L01Bug {
    static func sorted(_ params: [(String, String)]) -> [(String, String)] {
        // TODO ①：把 params 依名稱（$0.0）轉成小寫（lowercased()）
        // 之後比較大小，用 sorted 排序後回傳
        return params
    }

    static func run(_ log: LabLog) async {
        scenario(log, sorted: sorted(_:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func sorted(_ params: [(String, String)]) -> [(String, String)] {
        // TODO ②：把 params 依名稱（$0.0）的 UTF-8 byte（Array($0.0.utf8)）
        // 逐個比較，用 lexicographicallyPrecedes 排序後回傳
        return params
    }

    static func run(_ log: LabLog) async {
        scenario(log, sorted: sorted(_:))
    }
}

// MARK: - 演練設定
private func rejectedCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("2 個請求") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange09Signing {
    static let l01 = Lab(
        id: "L01",
        title: "明明有排序，簽名還是被拒絕",
        question: "第 1 題：為什麼 API 請求要簽名？這套交易所 API 的 Signature V2 怎麼做？",
        goal: "簽名字串裡的參數要依 ASCII 順序排序：大寫字母在小寫字母之前。轉小寫再比較的「字母順序」，算出來的簽名和伺服器不同。",
        steps: [
            "打開 Practice/Exchange/Exchange09-Signing/L01_ParamOrder.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看兩個請求「排序後的參數」，order-id 排在哪裡？簽名和 test vector 一樣嗎？伺服器怎麼回？",
            "想一想：大寫的 S 和小寫的 o，在 ASCII 表裡誰先誰後？",
            "寫 TODO ②（修正版），選「修正版」執行，兩個請求都要被受理，第一個請求的簽名要和 test vector 一致。",
            "進階：Swift 的 localizedCompare、Java 的 Collator 也會讓大小寫的順序和伺服器不同，簽名的排序一律用 byte 順序。",
        ],
        expectBug:
            "「/v1/order/orders 排序後的參數：AccessKeyId, order-id, SignatureMethod, SignatureVersion, Timestamp」（order-id 夾在中間），「test vector 比對：不一致」，「2 個請求，被伺服器拒絕 2 個」（api-signature-not-valid）。",
        expectFixed:
            "「排序後的參數：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp, order-id」，「test vector 比對：一致」，「2 個請求，被伺服器拒絕 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = rejectedCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, sorted:) 執行"
            }
            guard
                lines.contains(
                    "/v1/order/orders 排序後的參數：AccessKeyId, order-id, SignatureMethod, SignatureVersion, Timestamp"
                )
            else {
                return
                    "Bug 還沒重現：名稱轉小寫（lowercased()）之後再比較，order-id 會夾在 AccessKeyId 與 SignatureMethod 中間"
            }
            return n == 2 ? nil : "兩個請求都應該被伺服器拒絕（目前拒絕 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = rejectedCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, sorted:) 執行"
            }
            if n != 0 {
                return "還有 \(n) 個請求被拒絕：依名稱的 UTF-8 byte 順序排序（大寫在小寫之前），不要轉小寫"
            }
            return lines.contains("test vector 比對：一致")
                ? nil : "第一個請求的簽名要和 test vector 一致"
        }
    )
}
