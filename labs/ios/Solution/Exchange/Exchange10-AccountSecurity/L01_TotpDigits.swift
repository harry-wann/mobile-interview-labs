// L01：驗證碼有時候少一位、有時候是亂數（TOTP 的動態截斷沒清最高位元、
// 沒有補零）
// 對應複習站：Exchange10AccountSecurity 第 1 題「兩步驟驗證的 6
// 位數驗證碼（TOTP）是怎麼算出來的？」
//
// 情境：TOTP（RFC 6238）把「現在是第幾個 30 秒」當 counter，用秘密算
// HMAC-SHA-1（20 個位元組），再做動態截斷：
//       取最後一個位元組的低 4 位元當 offset，從 offset 取 4 個位元組，
// 最高位元清成 0（& 0x7f），對 1,000,000 取餘數，不足 6 位時左邊補零。
//       演練拿 RFC 6238 附錄 B 的 6 組測試資料（秘密 12345678901234567890）：
// 時間 59、1111111109、1111111111、1234567890、2000000000、20000000000 秒，
//       答案分別是 287082、081804、050471、005924、279037、353130。HMAC
// 已經算好（在 Exchange10Support.swift），這題只寫「20 個位元組 → 6 位數碼」。
// Bug 版：取 4 個位元組組成整數時沒有清最高位元（沒有 & 0x7f），
// 取餘數後直接轉成文字，沒有補零。
//        最高位元是 1 的資料（6 組裡有 4 組）整數太大，
// 取餘數的結果就是別的數字；開頭是 0 的碼（081804、050471、005924）變成 5
// 位數以下。
// 修正版：第一個位元組先 & 0x7f 再組成整數；取後 6 位之後用 %06d 補零。
import Foundation
import LabKit

/// RFC 6238 附錄 B 的 SHA-1 測試資料：時間（Unix 秒）與 6 位數答案
private let vectors: [(time: UInt64, expected: String)] = [
    (59, "287082"), (1_111_111_109, "081804"), (1_111_111_111, "050471"),
    (1_234_567_890, "005924"), (2_000_000_000, "279037"),
    (20_000_000_000, "353130"),
]

/// 演練流程（兩個版本共用）：6 組時間逐一算出驗證碼，和 RFC 的答案比對
private func scenario(_ log: LabLog, code: ([UInt8]) -> String) {
    var wrong = 0
    for v in vectors {
        // counter = 第幾個 30 秒
        let mac = E10.mac(secret: E10.rfcSecret, counter: v.time / 30)
        let result = code(mac)
        if result == v.expected {
            log("時間 \(v.time) 秒 → 驗證碼 \(result) ✓")
        } else {
            wrong += 1
            log("時間 \(v.time) 秒 → 驗證碼 \(result) ✗（RFC 的答案是 \(v.expected)）")
        }
    }
    log("共 6 組 RFC 6238 測試資料，算錯的有 \(wrong) 組")
}

// MARK: - Bug 版
private enum L01Bug {
    static func code(fromMac mac: [UInt8]) -> String {
        // 最後一個位元組的低 4 位元
        let offset = Int(mac[mac.count - 1] & 0x0f)
        // 沒有 & 0x7f：最高位元可能是 1
        let binary =
            (UInt32(mac[offset]) << 24) | (UInt32(mac[offset + 1]) << 16)
            | (UInt32(mac[offset + 2]) << 8) | UInt32(mac[offset + 3])
        // 沒有補零：081804 變成 81804
        return String(binary % 1_000_000)
    }

    static func run(_ log: LabLog) async {
        scenario(log, code: code(fromMac:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func code(fromMac mac: [UInt8]) -> String {
        let offset = Int(mac[mac.count - 1] & 0x0f)
        // 第一個位元組 & 0x7f：最高位元清成 0
        let binary =
            (UInt32(mac[offset] & 0x7f) << 24) | (UInt32(mac[offset + 1]) << 16)
            | (UInt32(mac[offset + 2]) << 8) | UInt32(mac[offset + 3])
        // 補零到 6 位
        return String(format: "%06d", Int(binary % 1_000_000))
    }

    static func run(_ log: LabLog) async {
        scenario(log, code: code(fromMac:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 6 組") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange10AccountSecurity {
    static let l01 = Lab(
        id: "L01",
        title: "驗證碼有時少一位、有時對不上",
        question: "第 1 題：兩步驟驗證的 6 位數驗證碼（TOTP）是怎麼算出來的？",
        goal:
            "動態截斷要把最高位元清成 0，取後 6 位之後要左邊補零。用 RFC 6238 附錄 B 的測試資料驗證，是確認 TOTP 寫對的標準做法。",
        steps: [
            "選「Bug 版」執行，看 6 組裡哪些算錯了：開頭是 0 的碼變成幾位？最高位元是 1 的資料算出什麼？",
            "想一想：081804 轉成整數是什麼？為什麼要 & 0x7f？（在 Kotlin 的 Int 上不清最高位元會變成負數。）",
            "選「修正版」執行，6 組都要和 RFC 的答案一致。",
            "進階：伺服器端驗證時，要容許前後各 1 個 time step、同一個 counter 只能用一次，見下一個演練。",
        ],
        expectBug:
            "時間 1111111109 秒算出 81804（應該是 081804，少了開頭的 0）；時間 59、1111111111、1234567890、20000000000 秒的最高位元是 1，沒清掉的話算出 770730、534119、489572、836778 這類完全不同的數字；只有 2000000000 秒剛好對：「共 6 組 RFC 6238 測試資料，算錯的有 5 組」。",
        expectFixed:
            "6 組都和 RFC 的答案一致：287082、081804、050471、005924、279037、353130，「共 6 組 RFC 6238 測試資料，算錯的有 0 組」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, code:) 執行"
            }
            if !lines.contains(where: {
                $0.hasPrefix("時間 2000000000 秒") && $0.hasSuffix("✓")
            }) {
                return
                    "Bug 版也要算出大部分的碼：時間 2000000000 秒的結果應該是 279037（這一組不需要補零，最高位元也是 0）"
            }
            if !lines.contains("時間 1111111109 秒 → 驗證碼 81804 ✗（RFC 的答案是 081804）")
            {
                return "Bug 還沒重現：取餘數之後直接轉成文字、不補零，081804 會變成 81804"
            }
            return n >= 3 ? nil : "Bug 還沒重現：至少有 3 組開頭是 0 的碼會算錯（目前錯 \(n) 組）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, code:) 執行"
            }
            return n == 0
                ? nil : "還有 \(n) 組算錯：第一個位元組要先 & 0x7f 清掉最高位元；取餘數後用 %06d 補零到 6 位"
        }
    )
}
