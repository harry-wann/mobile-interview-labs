// L01：合法的比特幣地址被判定無效（Base58Check 解碼丟掉了開頭的 1）
// 對應複習站：Exchange12Wallet 第 2 題「加密資產的地址怎麼驗證？Base58Check、
// Bech32、EIP-55 是什麼？」
//
// 情境：比特幣傳統地址是 Base58Check：把字串當成 58 進位的大整數轉成位元組，
// 第一個位元組是版本、最後 4 個位元組是校驗碼，
//       校驗碼 = 前面所有位元組做兩次 SHA-256 的前 4 個位元組。**開頭每個 `1`
// 代表一個 0x00 位元組**，轉成大整數時開頭的 0 會消失，要自己補回來。
//       大整數轉位元組（E12.base58Magnitude，不處理開頭的 0）與校驗碼（E12.
// checksum4）已經寫好在 Exchange12Support.swift。
//       演練檢查 5 個地址（都是公開的測試地址）：比特幣創世區塊的地址
// 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa、另一個以 1 開頭的合法地址、
//       以 3 開頭的合法地址（P2SH），最後一個字元被改掉的地址（校驗碼不符），
// 含有 0（不在字母表）的地址。
//
// 你要寫：
//   ① Bug 版（L01Bug.isValid）：bytes 直接用 magnitude（不補開頭的 0x00），
// 之後檢查長度至少 5、校驗碼是否相符。
//   ② 修正版（L01Fixed.isValid）：數出 s 開頭連續幾個 "1"，bytes = 同樣數量的
// 0（UInt8）加上 magnitude，之後同樣檢查。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L01_Base58Check.swift
import Foundation
import LabKit

/// （描述, 地址, 是否合法）
private let cases: [(String, String, Bool)] = [
    ("創世區塊的地址（以 1 開頭）", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", true),
    ("另一個以 1 開頭的合法地址", "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", true),
    ("以 3 開頭的合法地址（P2SH）", "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", true),
    ("最後一個字元被改掉", "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb", false),
    ("含有 0（不在字母表）", "0A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", false),
]

/// 演練流程（兩個版本共用）：5 個地址逐一驗證，和正確答案比對
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
    log("5 個地址，驗證結果錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L01Bug {
    static func isValid(_ s: String) -> Bool {
        guard let magnitude = E12.base58Magnitude(s) else { return false }
        // TODO ①：let bytes = magnitude（不補開頭的 0x00）
        let bytes: [UInt8] = []
        // 至少要有 1 個版本位元組加 4 個校驗位元組
        guard bytes.count >= 5 else { return false }
        return E12.checksum4(Array(bytes.dropLast(4))) == Array(bytes.suffix(4))
    }

    static func run(_ log: LabLog) async {
        scenario(log, isValid: isValid(_:))
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func isValid(_ s: String) -> Bool {
        guard let magnitude = E12.base58Magnitude(s) else { return false }
        // TODO ②：數出 s 開頭連續幾個 "1"（s.prefix(while:)），bytes =
        // 同樣數量的 0（UInt8）+ magnitude
        let bytes: [UInt8] = []
        // 至少要有 1 個版本位元組加 4 個校驗位元組
        guard bytes.count >= 5 else { return false }
        return E12.checksum4(Array(bytes.dropLast(4))) == Array(bytes.suffix(4))
    }

    static func run(_ log: LabLog) async {
        scenario(log, isValid: isValid(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("5 個地址") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange12Wallet {
    static let l01 = Lab(
        id: "L01",
        title: "合法的比特幣地址被判定無效",
        question: "第 2 題：加密資產的地址怎麼驗證？Base58Check、Bech32、EIP-55 是什麼？",
        goal: "Base58 解碼要把開頭的每個 1 補回成 0x00 位元組，否則以 1 開頭的地址會算錯校驗碼，被誤判為無效。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L01_Base58Check.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 5 個地址哪些判斷錯了：以 1 開頭和以 3 開頭的地址有什麼不同？",
            "想一想：大整數 0x0062e9… 轉成位元組時，開頭的 00 會怎樣？校驗碼是用哪一段資料算的？",
            "寫 TODO ②（修正版），選「修正版」執行，5 個地址都要判斷對。",
            "進階：Kotlin 的 Byte 有正負號，版本位元組如果大於 127，要先 and 0xFF；BigInteger.toByteArray 可能多一個符號用的 0x00，也要處理。",
        ],
        expectBug:
            "兩個以 1 開頭的合法地址都被判成「無效 ✗」，以 3 開頭的剛好判對，校驗碼被改過的、含 0 的本來就無效所以也對：「5 個地址，驗證結果錯的有 2 個」。",
        expectFixed: "5 個地址都判斷對：「5 個地址，驗證結果錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, isValid:) 執行"
            }
            if !lines.contains("以 3 開頭的合法地址（P2SH）：合法 ✓") {
                return "Bug 版對以 3 開頭的地址要判斷對（沒有開頭的 1，不補 0x00 也不影響）"
            }
            return n == 2
                ? nil : "Bug 還沒重現：不補開頭的 0x00，兩個以 1 開頭的合法地址都會被判成無效（目前錯 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, isValid:) 執行"
            }
            return n == 0 ? nil : "還有 \(n) 個地址判斷錯：數出開頭有幾個 1，在位元組前面補同樣數量的 0x00"
        }
    )
}
