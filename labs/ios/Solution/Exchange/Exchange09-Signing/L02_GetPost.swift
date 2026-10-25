// L02：查詢都正常，一下單簽名就被拒絕（POST 把 body 的欄位也簽進去了）
// 對應複習站：Exchange09Signing 第 2 題「GET 和 POST 要簽的東西有什麼不同？」
//
// 情境：兩個簽名請求：
//       ① GET /v1/order/orders?order-id=1234567890：參數在網址的 query，要和 4 個認證參數一起簽。
//       ② POST /v1/order/orders/place：下單資料（account-id、symbol、type、amount、price）放在 JSON body，
//          網址上只有 4 個認證參數與 Signature；簽名只簽這 4 個認證參數，body 不參與簽名。
//       簽名函式把「要簽的參數」交給 paramsToSign 決定；假伺服器用自己看到的網址 query 重新算簽名。
// Bug 版：不分 GET 與 POST，一律把 query 和 body 的欄位全部簽進去（覺得「簽越多越安全」）。
//        GET 沒問題（body 是空的），但 POST 簽了 5 個伺服器不會拿來簽的欄位，簽名字串不同，下單永遠被拒絕。
// 修正版：GET 簽 query 參數；POST 不簽任何自己的參數（只簽 4 個認證參數，認證參數由共用的程式加進去）。
import Foundation
import LabKit

private struct Request {
    let method: String
    let path: String
    let query: [(String, String)]    // 網址 ? 後面自己的參數
    let body: [(String, String)]     // JSON body 的欄位（POST 才有）
}

private let requests = [
    Request(method: "GET", path: "/v1/order/orders", query: [("order-id", "1234567890")], body: []),
    Request(method: "POST", path: "/v1/order/orders/place", query: [],
            body: [("account-id", "100009"), ("symbol", "btcjpy"), ("type", "buy-limit"), ("amount", "0.01"), ("price", "4800000")]),
]

private func sortedASCII(_ params: [(String, String)]) -> [(String, String)] {
    params.sorted { Array($0.0.utf8).lexicographicallyPrecedes(Array($1.0.utf8)) }
}

private func encode(_ params: [(String, String)]) -> String {
    params.map { "\(E09.percentEncode($0.0))=\(E09.percentEncode($0.1))" }.joined(separator: "&")
}

/// 演練流程（兩個版本共用）：簽名、送出、假伺服器驗證
private func scenario(_ log: LabLog, paramsToSign: (Request) -> [(String, String)]) {
    var rejected = 0
    for request in requests {
        let signed = sortedASCII(paramsToSign(request) + E09.authParams())
        let signature = E09.hmacBase64("\(request.method)\n\(E09.host)\n\(request.path)\n\(encode(signed))")
        // 實際送出的網址：GET 帶自己的 query；POST 的資料在 body，網址上只有認證參數
        let sentParams = sortedASCII((request.method == "GET" ? request.query : []) + E09.authParams())
        let sent = encode(sentParams) + "&Signature=" + E09.percentEncode(signature)
        log("\(request.method) \(request.path)：簽進去的參數 \(signed.map(\.0).joined(separator: ", "))")
        if request.method == "POST" { log("POST 簽名與 test vector \(signature == E09.postVector ? "一致" : "不一致")") }
        if let error = E09.verifyRest(method: request.method, host: E09.host, path: request.path, rawQuery: sent) {
            rejected += 1
            log("\(request.method) \(request.path)：伺服器拒絕（\(error)）")
        } else {
            log("\(request.method) \(request.path)：伺服器受理")
        }
    }
    log("2 個請求，被伺服器拒絕 \(rejected) 個")
}

// MARK: - Bug 版
private enum L02Bug {
    fileprivate static func paramsToSign(_ request: Request) -> [(String, String)] {
        request.query + request.body            // 不分 GET 與 POST：全部簽進去
    }

    static func run(_ log: LabLog) async {
        scenario(log, paramsToSign: paramsToSign(_:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    fileprivate static func paramsToSign(_ request: Request) -> [(String, String)] {
        request.method == "GET" ? request.query : []    // GET 簽 query；POST 只簽認證參數（body 不簽）
    }

    static func run(_ log: LabLog) async {
        scenario(log, paramsToSign: paramsToSign(_:))
    }
}

// MARK: - 演練設定
private func rejectedCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("2 個請求") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange09Signing {
    static let l02 = Lab(
        id: "L02",
        title: "查詢都正常，一下單簽名就被拒絕",
        question: "第 2 題：GET 和 POST 要簽的東西有什麼不同？",
        goal: "GET 要把網址上的 query 參數一起簽；POST 只簽 4 個認證參數，body 的資料不參與簽名。",
        steps: [
            "選「Bug 版」執行，比較 GET 和 POST「簽進去的參數」有什麼不同，伺服器對兩個請求各怎麼回。",
            "想一想：伺服器重新算簽名的時候，POST 的 body 在不在它的簽名字串裡？",
            "選「修正版」執行，兩個請求都要被受理，POST 的簽名要和 test vector 一致。",
            "進階：body 不在簽名範圍內，所以它只靠 HTTPS 保護；這是這套簽名的設計特性，面試時可以主動點出來。",
        ],
        expectBug: "「GET /v1/order/orders：伺服器受理」，「POST /v1/order/orders/place：簽進去的參數」多了 account-id、amount、price、symbol、type，「POST 簽名與 test vector 不一致」「POST /v1/order/orders/place：伺服器拒絕（api-signature-not-valid）」，「2 個請求，被伺服器拒絕 1 個」。",
        expectFixed: "POST 只簽 AccessKeyId、SignatureMethod、SignatureVersion、Timestamp，「POST 簽名與 test vector 一致」，「2 個請求，被伺服器拒絕 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = rejectedCount(lines) else { return "演練沒有跑完：要用 scenario(log, paramsToSign:) 執行" }
            if !lines.contains("GET /v1/order/orders：伺服器受理") { return "Bug 版的 GET 要簽 query 的參數（GET 沒有 body，所以不會有問題）" }
            if !lines.contains(where: { $0.hasPrefix("POST /v1/order/orders/place：簽進去的參數") && $0.contains("amount") }) {
                return "Bug 還沒重現：POST 也要把 body 的欄位（amount、price…）簽進去"
            }
            return n == 1 ? nil : "只有 POST 應該被伺服器拒絕（目前拒絕 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = rejectedCount(lines) else { return "演練沒有跑完：要用 scenario(log, paramsToSign:) 執行" }
            if n != 0 { return "還有 \(n) 個請求被拒絕：GET 簽 query 的參數，POST 一個都不簽（只簽認證參數）" }
            return lines.contains("POST 簽名與 test vector 一致") ? nil : "POST 的簽名要和 test vector 一致"
        }
    )
}
