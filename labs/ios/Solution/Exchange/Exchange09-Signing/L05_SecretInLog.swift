// L05：簽名失敗時為了除錯寫的 log，把 Secret Key 和完整簽名都留在紀錄裡了（金鑰不能進 log）
// 對應複習站：Exchange09Signing 第 6 題「簽名的程式要怎麼測試？金鑰要怎麼保管？」
//
// 情境：簽名請求被伺服器退回（api-signature-not-valid）。工程師為了找原因，在 App 寫了一行「請求紀錄」，
//       這一行會進 App 的 log、crash 報告，也可能被使用者截圖給客服。
//       演練給 describe 這個函式 4 樣東西：method、完整網址（含 AccessKeyId 與 Signature）、簽名字串、Secret Key，
//       由它決定「要寫進紀錄的那一行」；然後掃描這一行有沒有洩漏 Secret Key 或完整的 Signature。
// Bug 版：把網址、簽名字串、Secret Key 全部寫進紀錄。Secret Key 一旦進了 log，任何看得到 log 的人都能偽造這個帳號的所有簽名請求。
// 修正版：只寫「method 加 path」、遮罩過的 AccessKeyId（前 5 碼加後 4 碼），Signature 寫成 ***；Secret Key 與簽名字串完全不寫。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：簽一個請求，產生要寫進 log 的那一行，再掃描它有沒有洩漏
private func scenario(_ log: LabLog, describe: (_ method: String, _ url: String, _ stringToSign: String, _ secret: String) -> String) {
    let query = "AccessKeyId=\(E09.accessKey)&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2017-05-11T15%3A19%3A30&order-id=1234567890"
    let stringToSign = "GET\n\(E09.host)\n/v1/order/orders\n\(query)"
    let signature = E09.hmacBase64(stringToSign)
    let url = "https://\(E09.host)/v1/order/orders?\(query)&Signature=\(E09.percentEncode(signature))"
    let line = describe("GET", url, stringToSign, E09.secret)
    log("[App log] \(line)")
    let leaksSecret = line.contains(E09.secret)
    let leaksSignature = line.contains(signature) || line.contains(E09.percentEncode(signature))
    log("掃描這一行：Secret Key \(leaksSecret ? "洩漏" : "沒有洩漏")，完整的 Signature \(leaksSignature ? "洩漏" : "沒有洩漏")")
}

// MARK: - Bug 版
private enum L05Bug {
    static func describe(method: String, url: String, stringToSign: String, secret: String) -> String {
        "\(method) \(url) stringToSign=\(stringToSign) secret=\(secret)"        // 什麼都印出來，方便除錯
    }

    static func run(_ log: LabLog) async {
        scenario(log) { describe(method: $0, url: $1, stringToSign: $2, secret: $3) }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func describe(method: String, url: String, stringToSign: String, secret: String) -> String {
        let path = URL(string: url)?.path ?? "?"
        let key = E09.accessKey
        let masked = "\(key.prefix(5))…\(key.suffix(4))"                    // 前 5 碼加後 4 碼：認得出是哪把，但不是完整的金鑰
        return "\(method) \(path) AccessKeyId=\(masked) Signature=***"      // Secret Key、簽名字串、完整簽名一個都不寫
    }

    static func run(_ log: LabLog) async {
        scenario(log) { describe(method: $0, url: $1, stringToSign: $2, secret: $3) }
    }
}

// MARK: - 演練設定
extension Exchange09Signing {
    static let l05 = Lab(
        id: "L05",
        title: "除錯用的 log 把 Secret Key 留下來了",
        question: "第 6 題：簽名的程式要怎麼測試？金鑰要怎麼保管？",
        goal: "Secret Key 和完整的簽名不能出現在 log、crash 報告或截圖裡；log 只留認得出是哪個請求的資訊，金鑰與簽名要遮罩。",
        steps: [
            "選「Bug 版」執行，看「[App log]」那一行有哪些東西，再看掃描的結果。",
            "想一想：如果使用者把這一行貼給客服、或 crash 報告被上傳，會發生什麼事？Secret Key 只在建立時顯示一次，洩漏了要怎麼辦？",
            "選「修正版」執行，那一行還認得出是哪個請求，但掃描結果都要是「沒有洩漏」。",
            "進階：跟 crash 報告一樣，分析工具（analytics）的事件參數、網路除錯用的 proxy log 也都不能含金鑰。",
        ],
        expectBug: "「[App log] GET https://api.example-exchange.com/v1/order/orders?AccessKeyId=…&Signature=… stringToSign=… secret=test-secret-key-0001」，「掃描這一行：Secret Key 洩漏，完整的 Signature 洩漏」。",
        expectFixed: "「[App log] GET /v1/order/orders AccessKeyId=test-…0001 Signature=***」，「掃描這一行：Secret Key 沒有洩漏，完整的 Signature 沒有洩漏」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("掃描這一行") }) else { return "演練沒有跑完：要用 scenario(log) { method, url, stringToSign, secret in … } 執行" }
            if !lines.contains("掃描這一行：Secret Key 洩漏，完整的 Signature 洩漏") {
                return "Bug 還沒重現：這一行要同時包含 url（裡面有 Signature）和 secret"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("掃描這一行") }) else { return "演練沒有跑完：要用 scenario(log) { method, url, stringToSign, secret in … } 執行" }
            guard let line = lines.first(where: { $0.hasPrefix("[App log] ") }), line.contains("GET /v1/order/orders"), line.contains("Signature=***") else {
                return "紀錄要認得出是哪個請求：寫出 method 和 path（GET /v1/order/orders），並把 Signature 寫成 ***"
            }
            if !line.contains("AccessKeyId=test-…0001") { return "AccessKeyId 要遮罩：只留前 5 碼和後 4 碼，中間用 …" }
            return lines.contains("掃描這一行：Secret Key 沒有洩漏，完整的 Signature 沒有洩漏") ? nil : "不能出現 Secret Key，也不能出現完整的 Signature（簽名字串、網址整段都不能寫）"
        }
    )
}
