// L02：大小寫混用的 bc1 地址被當成合法（Bech32 地址不能大小寫混用）
// 對應複習站：Exchange12Wallet 第 2 題「加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？」
//
// 情境：Bech32（SegWit）與 Bech32m（Taproot）地址以 bc1 開頭，有多項式校驗碼。規則：全小寫或全大寫都合法（QR code 常用全大寫），
//       **大小寫混用則無效**。校驗碼的計算（E12.bech32Kind，要傳入全小寫的字串，同時支援 Bech32 與 Bech32m）已經寫好在 Exchange12Support.swift，
//       這題只看「大小寫」。演練檢查 6 個地址（都是 BIP 173／350 文件的公開測試地址）：全小寫的合法地址、全大寫的同一個地址、
//       Taproot 的 Bech32m 地址、第一個字母改成大寫的混用地址、中間一個字母改成大寫的混用地址、校驗碼被改掉的地址。
// Bug 版：先 lowercased() 再驗證校驗碼：看起來很周到（「大小寫都接受」），卻把大小寫混用的地址也當成合法了
//        （轉成小寫之後，校驗碼是對的）。
// 修正版：先檢查「全小寫」或「全大寫」，混用就直接無效；通過了再轉小寫驗證校驗碼。
import Foundation
import LabKit

/// （描述, 地址, 是否合法）
private let cases: [(String, String, Bool)] = [
    ("全小寫的合法地址", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", true),
    ("同一個地址，全大寫", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", true),
    ("Taproot 的 Bech32m 地址", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", true),
    ("第一個字母改成大寫", "Bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", false),
    ("中間一個字母改成大寫", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8F3t4", false),
    ("校驗碼被改掉", "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5", false),
]

/// 演練流程（兩個版本共用）：6 個地址逐一驗證，和正確答案比對
private func scenario(_ log: LabLog, isValid: (String) -> Bool) {
    var wrong = 0
    for (name, address, expected) in cases {
        let result = isValid(address)
        let text = result ? "合法" : "無效"
        if result == expected {
            log("\(name)：\(text) ✓")
        } else {
            wrong += 1
            log("\(name)：\(text) ✗（應該\(expected ? "合法" : "無效")）")
        }
    }
    log("6 個地址，驗證結果錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L02Bug {
    static func isValid(_ address: String) -> Bool {
        E12.bech32Kind(lowercased: address.lowercased()) != nil       // 轉小寫就驗證：大小寫混用的也過了
    }

    static func run(_ log: LabLog) async {
        scenario(log, isValid: isValid(_:))
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func isValid(_ address: String) -> Bool {
        guard address == address.lowercased() || address == address.uppercased() else { return false }   // 大小寫混用：無效
        return E12.bech32Kind(lowercased: address.lowercased()) != nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, isValid: isValid(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("6 個地址") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange12Wallet {
    static let l02 = Lab(
        id: "L02",
        title: "大小寫混用的 bc1 地址被當成合法",
        question: "第 2 題：加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？",
        goal: "Bech32 地址全小寫或全大寫都合法，大小寫混用則無效；不能先轉小寫再驗證校驗碼，就把混用的地址放行。",
        steps: [
            "選「Bug 版」執行，看 6 個地址哪些判斷錯了。",
            "想一想：大小寫混用的地址，轉成小寫之後校驗碼為什麼是對的？規格為什麼還要禁止混用？",
            "選「修正版」執行，6 個地址都要判斷對（全大寫和 Taproot 的 Bech32m 都要合法）。",
            "進階：EIP-55 的 Ethereum 地址剛好相反，大小寫混合是校驗碼；全小寫代表沒有校驗碼。兩種地址不能用同一套「大小寫」邏輯。",
        ],
        expectBug: "「第一個字母改成大寫：合法 ✗」「中間一個字母改成大寫：合法 ✗」，其他四個判斷對（全大寫、Taproot 都合法，校驗碼被改掉的無效）：「6 個地址，驗證結果錯的有 2 個」。",
        expectFixed: "6 個地址都判斷對：「6 個地址，驗證結果錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, isValid:) 執行" }
            if !lines.contains("同一個地址，全大寫：合法 ✓") { return "Bug 版要把地址轉成小寫再驗證，所以全大寫的合法地址要判成合法" }
            return n == 2 ? nil : "Bug 還沒重現：轉小寫就驗證，兩個大小寫混用的地址都會被當成合法（目前錯 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, isValid:) 執行" }
            return n == 0 ? nil : "還有 \(n) 個地址判斷錯：混用大小寫先判無效；全小寫或全大寫才轉小寫驗證校驗碼"
        }
    )
}
