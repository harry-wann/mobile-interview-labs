// Exchange12Wallet 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：Base58 解碼成位元組（大整數轉位元組，不處理開頭的 0）、雙 SHA-256 的前 4 個位元組、Bech32／Bech32m 的校驗碼（polymod）、
//      BIP21 付款連結的切分（scheme、地址、query 的原始 key／value）。
// 地址都是公開的測試地址（BIP 文件與比特幣創世區塊的地址），不是任何人的真實地址。
import CryptoKit
import Foundation

enum E12 {
    // MARK: Base58Check

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".utf8)

    /// 把 Base58 字串當成 58 進位的大整數，轉成位元組（大端序）。
    /// 注意：開頭的 `1` 代表 0x00 位元組，轉成大整數時會消失，這個函式不會補回來；字元不在字母表裡回傳 nil。
    static func base58Magnitude(_ s: String) -> [UInt8]? {
        var bytes: [UInt8] = []
        for c in s.utf8 {
            guard let digit = base58Alphabet.firstIndex(of: c) else { return nil }
            var carry = digit
            for i in stride(from: bytes.count - 1, through: 0, by: -1) {
                carry += Int(bytes[i]) * 58
                bytes[i] = UInt8(carry & 0xff)
                carry >>= 8
            }
            while carry > 0 {
                bytes.insert(UInt8(carry & 0xff), at: 0)
                carry >>= 8
            }
        }
        return bytes
    }

    /// 校驗碼：對 payload 連續做兩次 SHA-256，取前 4 個位元組
    static func checksum4(_ payload: [UInt8]) -> [UInt8] {
        let once = Array(SHA256.hash(data: payload))
        return Array(SHA256.hash(data: once).prefix(4))
    }

    // MARK: Bech32 / Bech32m

    enum Bech32Kind { case bech32, bech32m }

    private static let bech32Charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")

    private static func polymod(_ values: [Int]) -> Int {
        let generator = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
        var chk = 1
        for v in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ v
            for i in 0..<5 where (top >> i) & 1 == 1 { chk ^= generator[i] }
        }
        return chk
    }

    /// 檢查校驗碼。傳進來的字串必須已經是全小寫（大小寫混用的檢查由呼叫端負責）。
    /// 只檢查校驗碼，不檢查 witness 版本與資料長度。校驗碼不對或字元不合法回傳 nil。
    static func bech32Kind(lowercased s: String) -> Bech32Kind? {
        guard let sep = s.lastIndex(of: "1") else { return nil }
        let hrp = String(s[..<sep])
        let dataPart = s[s.index(after: sep)...]
        guard !hrp.isEmpty, dataPart.count >= 6 else { return nil }
        var values: [Int] = []
        for c in dataPart {
            guard let i = bech32Charset.firstIndex(of: c) else { return nil }
            values.append(i)
        }
        let scalars = hrp.unicodeScalars.map { Int($0.value) }
        switch polymod(scalars.map { $0 >> 5 } + [0] + scalars.map { $0 & 31 } + values) {
        case 1: return .bech32
        case 0x2bc830a3: return .bech32m
        default: return nil
        }
    }

    // MARK: BIP21

    /// BIP21 付款連結的切分結果：地址，以及 query 裡每一個參數的原始 key 與 value（還沒做百分比解碼）
    struct Bip21Parts {
        let address: String
        let query: [(key: String, rawValue: String)]
    }

    /// scheme 不分大小寫（`bitcoin:`）；地址到 `?` 為止；query 用 `&` 分隔、第一個 `=` 分出 key 與 value。不是 bitcoin 連結回傳 nil。
    static func splitBip21(_ uri: String) -> Bip21Parts? {
        let prefix = "bitcoin:"
        guard uri.lowercased().hasPrefix(prefix) else { return nil }
        let rest = String(uri.dropFirst(prefix.count))
        let halves = rest.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let address = String(halves[0])
        guard !address.isEmpty else { return nil }
        var query: [(key: String, rawValue: String)] = []
        if halves.count == 2 {
            for part in halves[1].split(separator: "&", omittingEmptySubsequences: true) {
                let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                query.append((String(kv[0]), kv.count == 2 ? String(kv[1]) : ""))
            }
        }
        return Bip21Parts(address: address, query: query)
    }
}
