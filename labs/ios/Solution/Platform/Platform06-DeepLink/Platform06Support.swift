// Platform06DeepLink 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
import CryptoKit
import Foundation

/// 收集 Router 收到的連結（演練裡的 Router 只記下「收到什麼」）
final class P06LinkCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String] = []
    func handle(_ url: URL) { lock.withLock { storage.append(url.absoluteString) } }
    var urls: [String] { lock.withLock { storage } }
}

/// 假的錢包。真實的錢包用橢圓曲線簽章（secp256k1），伺服器只需要錢包地址就能驗證；
/// 演練用 HMAC 代替簽章，重點是「簽的訊息裡有什麼」，不是簽章演算法。
enum P06Wallet {
    static let address = "0x1234abcd5678ef90"
    private static let key = SymmetricKey(data: Data("wallet-private-key".utf8))

    static func sign(_ message: String) -> String {
        Data(HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)).map { String(format: "%02x", $0) }.joined()
    }

    /// 伺服器端的驗證：簽章是不是這個錢包對這段訊息簽的
    static func verify(message: String, signature: String) -> Bool {
        sign(message) == signature
    }
}

enum P06Siwe {
    /// 從訊息裡取出「欄位名稱: 值」那一行的值，例如 field("Nonce", in: message)
    static func field(_ name: String, in message: String) -> String? {
        message.split(separator: "\n").first { $0.hasPrefix("\(name): ") }.map { String($0.dropFirst(name.count + 2)) }
    }
}
