// Exchange10AccountSecurity 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：RFC 4226／6238 的 HMAC-SHA-1 與完整的 HOTP（驗證碼算法的正確版本）、HMAC-SHA256 的十六進位字串（示範用的「裝置簽章」）。
// 秘密與金鑰都是 RFC 的測試資料或演練用的假資料，不是真的帳號。
import CryptoKit
import Foundation

enum E10 {
    /// RFC 6238 附錄 B 的秘密（SHA-1 版）：文字 12345678901234567890
    static let rfcSecret = Array("12345678901234567890".utf8)

    /// HMAC-SHA-1：金鑰是秘密，資料是 8 個位元組的 counter（最高位放最前面，big-endian）。結果是 20 個位元組。
    static func mac(secret: [UInt8], counter: UInt64) -> [UInt8] {
        var message = [UInt8](repeating: 0, count: 8)
        var c = counter
        for i in stride(from: 7, through: 0, by: -1) {
            message[i] = UInt8(c & 0xff)
            c >>= 8
        }
        return Array(HMAC<Insecure.SHA1>.authenticationCode(for: message, using: SymmetricKey(data: secret)))
    }

    /// 正確的 HOTP（RFC 4226）：動態截斷、清掉最高位元、取後 6 位並補零
    static func hotp(secret: [UInt8], counter: UInt64) -> String {
        let h = mac(secret: secret, counter: counter)
        let offset = Int(h[h.count - 1] & 0x0f)
        let binary = (UInt32(h[offset] & 0x7f) << 24) | (UInt32(h[offset + 1]) << 16) | (UInt32(h[offset + 2]) << 8) | UInt32(h[offset + 3])
        return String(format: "%06d", Int(binary % 1_000_000))
    }

    /// HMAC-SHA256 的十六進位字串。演練用它代替「裝置私鑰的簽章」（真實的 App 是 Secure Enclave／Keystore 裡的私鑰簽章）。
    static func hmacHex(key: String, message: String) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: SymmetricKey(data: Data(key.utf8)))
        return mac.map { String(format: "%02x", $0) }.joined()
    }
}
