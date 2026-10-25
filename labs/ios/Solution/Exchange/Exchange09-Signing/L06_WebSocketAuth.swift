// L06：REST 簽名好好的，私有 WebSocket 的 auth 訊息卻一直被拒絕（版本 2.1
// 的欄位名稱與 JSON 的值）
// 對應複習站：Exchange09Signing 第 7 題「私有 WebSocket 的簽名有什麼不同？」
//
// 情境：連上私有 WebSocket（/ws/v2）後，要先送 auth 訊息。簽名原理和 REST
// 一樣（HMAC-SHA256 加 Base64），但是：
//       ① 參數名稱改成小寫開頭的駝峰：accessKey、signatureMethod、
// signatureVersion（值是 2.1，不是 2）、timestamp；
//       ② 簽名字串是 GET、host、固定的 /ws/v2、排序並 URL 編碼後的 query；
//       ③ 送出的 JSON 裡，欄位的值不需要 URL 編碼，
// 直接放原始字串（簽名字串裡才編碼）。
//       演練用複習站的時間戳記 2019-09-01T18:16:16 組出 auth 訊息，
// 假伺服器收到後重新算一次簽名。
// Bug 版：直接重用 REST 的認證參數（AccessKeyId、SignatureMethod、
// SignatureVersion=2、Timestamp），
//        而且把 JSON 裡每個欄位的值也做了 URL 編碼（timestamp 變成
// 2019-09-01T18%3A16%3A16）。伺服器找不到 accessKey、也不認得版本 2，直接拒絕。
// 修正版：用 WebSocket 的欄位名稱與版本 2.1；JSON 裡的值放原始字串，
// 只有簽名字串裡的 query 才 URL 編碼。
import Foundation
import LabKit

/// 演練流程（兩個版本共用）：組出 auth 訊息送給假伺服器
private func scenario(
    _ log: LabLog,
    authParams: (String) -> [(String, String)],
    jsonValue: (String) -> String
) {
    let params = authParams(E09.wsTimestamp)
    let ordered = params.sorted {
        Array($0.0.utf8).lexicographicallyPrecedes(Array($1.0.utf8))
    }
    let query = ordered.map {
        "\(E09.percentEncode($0.0))=\(E09.percentEncode($0.1))"
    }.joined(separator: "&")
    let signature = E09.hmacBase64("GET\n\(E09.host)\n/ws/v2\n\(query)")

    // authType 是訊息本身的欄位，不在簽名裡
    var fields: [String: Any] = ["authType": "api"]
    for (name, value) in params { fields[name] = jsonValue(value) }
    fields["signature"] = jsonValue(signature)
    let message: [String: Any] = [
        "action": "req", "ch": "auth", "params": fields,
    ]
    let json = try! JSONSerialization.data(
        withJSONObject: message,
        options: [.sortedKeys, .withoutEscapingSlashes])

    log("送出的欄位：\(params.map(\.0).joined(separator: ", "))")
    let sentTimestamp =
        params.first { $0.0.lowercased() == "timestamp" }.map {
            jsonValue($0.1)
        } ?? "（沒有）"
    log("送出的 timestamp：\(sentTimestamp)")
    log("簽名與 test vector \(signature == E09.wsVector ? "一致" : "不一致")")
    if let error = E09.verifyWS(json) {
        log("伺服器拒絕：\(error)")
    } else {
        log("伺服器受理：auth 成功")
    }
}

// MARK: - Bug 版
private enum L06Bug {
    static func authParams(timestamp: String) -> [(String, String)] {
        // 重用 REST 的欄位：AccessKeyId、…、SignatureVersion=2
        E09.authParams(timestamp: timestamp)
    }

    static func jsonValue(_ value: String) -> String {
        // JSON 的值也編碼：timestamp 變成 …18%3A16%3A16
        E09.percentEncode(value)
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, authParams: authParams(timestamp:), jsonValue: jsonValue(_:))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func authParams(timestamp: String) -> [(String, String)] {
        [
            ("accessKey", E09.accessKey), ("signatureMethod", "HmacSHA256"),
            ("signatureVersion", "2.1"), ("timestamp", timestamp),
        ]
    }

    static func jsonValue(_ value: String) -> String {
        // JSON 裡放原始字串，不編碼
        value
    }

    static func run(_ log: LabLog) async {
        scenario(
            log, authParams: authParams(timestamp:), jsonValue: jsonValue(_:))
    }
}

// MARK: - 演練設定
extension Exchange09Signing {
    static let l06 = Lab(
        id: "L06",
        title: "REST 簽名正常，私有 WebSocket 的 auth 被拒絕",
        question: "第 7 題：私有 WebSocket 的簽名有什麼不同？",
        goal:
            "私有 WebSocket 用 SignatureVersion 2.1：欄位名稱是小寫開頭的駝峰，簽名字串的 path 固定為 /ws/v2，送出的 JSON 裡的值不需要 URL 編碼。",
        steps: [
            "選「Bug 版」執行，看送出的欄位名稱、timestamp 的值，以及伺服器的回應。",
            "對照複習站第 7 題的表格，列出 REST 和私有 WebSocket 有哪四個地方不一樣。",
            "選「修正版」執行，簽名要和 test vector 一致，伺服器受理。",
            "進階：authType 是訊息本身的欄位，不在簽名的參數裡；如果不小心簽進去，伺服器會回什麼？",
        ],
        expectBug:
            "「送出的欄位：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp」，「送出的 timestamp：2019-09-01T18%3A16%3A16」，「伺服器拒絕：auth fail: 缺少欄位 accessKey」。",
        expectFixed:
            "「送出的欄位：accessKey, signatureMethod, signatureVersion, timestamp」，「送出的 timestamp：2019-09-01T18:16:16」，「簽名與 test vector 一致」，「伺服器受理：auth 成功」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("簽名與 test vector") })
            else {
                return "演練沒有跑完：要用 scenario(log, authParams:, jsonValue:) 執行"
            }
            if !lines.contains(
                "送出的欄位：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp"
            ) {
                return
                    "Bug 還沒重現：直接重用 REST 的欄位（AccessKeyId、SignatureMethod、SignatureVersion、Timestamp）"
            }
            if !lines.contains("送出的 timestamp：2019-09-01T18%3A16%3A16") {
                return
                    "Bug 還沒重現：JSON 裡的值也做了 URL 編碼，timestamp 變成 2019-09-01T18%3A16%3A16"
            }
            return lines.contains(where: { $0.hasPrefix("伺服器拒絕") })
                ? nil : "伺服器應該拒絕這個 auth 訊息"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("簽名與 test vector") })
            else {
                return "演練沒有跑完：要用 scenario(log, authParams:, jsonValue:) 執行"
            }
            if !lines.contains(
                "送出的欄位：accessKey, signatureMethod, signatureVersion, timestamp")
            {
                return
                    "欄位名稱要是 accessKey、signatureMethod、signatureVersion、timestamp（小寫開頭的駝峰，signatureVersion 的值是 2.1）"
            }
            if !lines.contains("送出的 timestamp：2019-09-01T18:16:16") {
                return "JSON 裡的值放原始字串，不要 URL 編碼"
            }
            if !lines.contains("簽名與 test vector 一致") {
                return "簽名要和 test vector 一致：簽名字串是 GET、host、/ws/v2、排序並編碼後的 query"
            }
            return lines.contains("伺服器受理：auth 成功")
                ? nil : "伺服器還是拒絕：\(lines.last ?? "")"
        }
    )
}
