// Exchange09Signing 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：HMAC-SHA256 加 Base64、符合 RFC 3986 的 URL 編碼、REST 的 4 個認證參數、test vector，
//      以及一個假伺服器（照 Signature V2、私有 WebSocket 的 2.1 規則，用自己保存的 Secret Key 重新算一次簽名）。
// 金鑰、host、時間都是演練用的假資料（複習站的 test vector），不是真的帳號。
import CryptoKit
import Foundation

enum E09 {
    static let host = "api.example-exchange.com"
    static let accessKey = "test-access-key-0001"
    static let secret = "test-secret-key-0001"

    /// REST test vector 用的時間戳記
    static let timestamp = "2017-05-11T15:19:30"
    /// GET /v1/order/orders?order-id=1234567890 的簽名（複習站第 1 題，用 openssl 交叉確認過）
    static let getVector = "KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg="
    /// POST /v1/order/orders/place（只簽 4 個認證參數）的簽名
    static let postVector = "zwVrzld/U+nC8m9CEzxGWbX7TMyGsK6AgtkPFEHCOXM="
    /// 私有 WebSocket（/ws/v2、版本 2.1）的 test vector：時間戳記與簽名
    static let wsTimestamp = "2019-09-01T18:16:16"
    static let wsVector = "+x+yn2NZlLnGl0RT5sniij34+6ooPrxqiSJ2u2GC7OU="

    /// HMAC-SHA256，再轉 Base64。金鑰與字串都用 UTF-8 的 byte，不 trim、不重新編碼。
    static func hmacBase64(_ text: String, secret: String = secret) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(text.utf8), using: SymmetricKey(data: Data(secret.utf8)))
        return Data(mac).base64EncodedString()
    }

    /// RFC 3986 的 URL 編碼：只有 A-Z a-z 0-9 - . _ ~ 不編碼，其他逐個 UTF-8 byte 編成大寫的 %XX。
    static func percentEncode(_ s: String) -> String {
        let hex = Array("0123456789ABCDEF")
        var out = ""
        for b in s.utf8 {
            switch b {
            case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2D, 0x2E, 0x5F, 0x7E:
                out.unicodeScalars.append(UnicodeScalar(b))
            default:
                out.append("%")
                out.append(hex[Int(b >> 4)])
                out.append(hex[Int(b & 0x0F)])
            }
        }
        return out
    }

    /// REST（Signature V2）的 4 個認證參數
    static func authParams(timestamp: String = timestamp) -> [(String, String)] {
        [("AccessKeyId", accessKey), ("SignatureMethod", "HmacSHA256"), ("SignatureVersion", "2"), ("Timestamp", timestamp)]
    }

    // MARK: 假伺服器

    private static func byteOrder(_ a: (String, String), _ b: (String, String)) -> Bool {
        Array(a.0.utf8).lexicographicallyPrecedes(Array(b.0.utf8))
    }

    /// 表單式的解碼（伺服器常見的做法：+ 當成空白，%XX 還原成 byte）
    private static func formDecode(_ s: String) -> String {
        s.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? s
    }

    /// REST：收到網址上的 query（含 Signature），照規則重新算一次。一致回傳 nil，不一致回傳錯誤碼。
    static func verifyRest(method: String, host: String, path: String, rawQuery: String) -> String? {
        var received: String?
        var params: [(String, String)] = []
        for part in rawQuery.split(separator: "&") {
            let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { return "api-signature-not-valid" }
            let name = formDecode(String(kv[0])), value = formDecode(String(kv[1]))
            if name == "Signature" { received = value } else { params.append((name, value)) }
        }
        guard let received, params.contains(where: { $0.0 == "SignatureVersion" && $0.1 == "2" }) else {
            return "api-signature-not-valid"
        }
        let query = params.sorted(by: byteOrder).map { "\(percentEncode($0.0))=\(percentEncode($0.1))" }.joined(separator: "&")
        let expected = hmacBase64("\(method)\n\(host.lowercased())\n\(path)\n\(query)")
        return expected == received ? nil : "api-signature-not-valid"
    }

    /// 私有 WebSocket：收到 auth 訊息的 JSON。一致回傳 nil，否則回傳錯誤說明。
    static func verifyWS(_ json: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let params = root["params"] as? [String: Any] else { return "auth fail: 訊息格式不對" }
        for key in ["accessKey", "signatureMethod", "signatureVersion", "timestamp", "signature"] where params[key] as? String == nil {
            return "auth fail: 缺少欄位 \(key)"
        }
        guard params["signatureVersion"] as? String == "2.1" else { return "auth fail: signatureVersion 必須是 2.1" }
        let signed: [(String, String)] = ["accessKey", "signatureMethod", "signatureVersion", "timestamp"].map { ($0, params[$0] as! String) }
        let query = signed.sorted(by: byteOrder).map { "\(percentEncode($0.0))=\(percentEncode($0.1))" }.joined(separator: "&")
        let expected = hmacBase64("GET\n\(host)\n/ws/v2\n\(query)")
        return expected == params["signature"] as? String ? nil : "auth fail: signature 不一致"
    }
}
