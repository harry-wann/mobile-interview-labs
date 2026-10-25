// L03：入金的 Destination Tag 悄悄變成別的數字（把 tag 當成 32 位元整數處理）
// 對應複習站：Exchange12Wallet 第 3 題「memo／destination tag 和網路選擇，
// 為什麼最容易出事？」
//
// 情境：XRP 的 Destination Tag 是 32 位元的無號整數，範圍 0 到 4294967295；
// Stellar 的 memo 可以是文字，開頭的 0 要保留。
//       API 的 addr-tag 是字串。App 把使用者輸入的 tag 整理成「要送出的字串」，
// 不合法的回傳 nil（畫面顯示錯誤）。
//       （本演練的 Bug 版用 Int32，是為了在 iOS 重現複習站說的 Android Int
// 溢位：有些 SDK 或伺服器的模型就是 32 位元整數。）
//       演練檢查 7 筆輸入：XRP 的 123456、3000000000、4294967295、
// 4294967296（超出範圍）、0、12a（不是數字），以及 Stellar 的文字 memo 007。
//
// 你要寫：
//   ① Bug 版（L03Bug.normalize）：value = Int32(truncatingIfNeeded:
// Int64(text) ?? 0)，回傳 String(value)（不分網路）。
//   ② 修正版（L03Fixed.normalize）：xrp：text 不能是空的、每個字元都是 0-9
// 的數字、UInt64(text) 不超過 4_294_967_295，通過就原樣回傳 text，否則回傳
// nil；
//      xlm：原樣回傳 text。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L03_TagOverflow.swift
import Foundation
import LabKit

private enum Network: String {
    case xrp = "XRP"
    case xlm = "XLM"
}

/// （網路, 使用者輸入的 tag 或 memo, 正確的結果；nil 代表不合法）
private let cases: [(Network, String, String?)] = [
    (.xrp, "123456", "123456"),
    (.xrp, "3000000000", "3000000000"),
    (.xrp, "4294967295", "4294967295"),
    (.xrp, "4294967296", nil),
    (.xrp, "0", "0"),
    (.xrp, "12a", nil),
    (.xlm, "007", "007"),
]

/// 演練流程（兩個版本共用）：7 筆輸入逐一整理，和正確答案比對
private func scenario(_ log: LabLog, normalize: (String, Network) -> String?) {
    var wrong = 0
    for (network, text, expected) in cases {
        let result = normalize(text, network)
        let shown = result.map { "\"\($0)\"" } ?? "不合法"
        if result == expected {
            log("\(network.rawValue) 輸入 \"\(text)\" → \(shown) ✓")
        } else {
            wrong += 1
            log(
                "\(network.rawValue) 輸入 \"\(text)\" → \(shown) ✗（應該是 \(expected.map { "\"\($0)\"" } ?? "不合法")）"
            )
        }
    }
    log("共 7 筆輸入，結果錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L03Bug {
    fileprivate static func normalize(
        _ text: String, _ network: Network
    ) -> String? {
        // TODO ①：value = Int32(truncatingIfNeeded: Int64(text) ?? 0)；回傳
        // String(value)（不分網路）
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, normalize: normalize(_:_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    fileprivate static func normalize(
        _ text: String, _ network: Network
    ) -> String? {
        // TODO ②：switch network：
        //        xrp → text 不是空的、每個字元都在 "0"..."9"、UInt64(text)
        // 不超過 4_294_967_295，通過就原樣回傳 text，否則回傳 nil；
        //        .xlm → 原樣回傳 text
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, normalize: normalize(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 7 筆") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange12Wallet {
    static let l03 = Lab(
        id: "L03",
        title: "入金的 Destination Tag 悄悄變成別的數字",
        question: "第 3 題：memo／destination tag 和網路選擇，為什麼最容易出事？",
        goal: "tag 和 memo 保持字串，依網路驗證格式與範圍；轉成 32 位元整數會悄悄溢位（不丟例外），資產就入到錯的帳戶。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L03_TagOverflow.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 7 筆輸入哪些結果錯了：3000000000 變成多少？4294967296、12a 變成多少？",
            "想一想：為什麼這個 bug 不會 crash、也不會顯示錯誤？使用者會在什麼時候才發現？",
            "寫 TODO ②（修正版），選「修正版」執行，7 筆都要和正確答案一致。",
            "進階：Android 的 Int 是 32 位元有號整數，tag.toLong().toInt() 同樣不丟例外；iOS 的 Int 在 64 位元平台是 64 位元，但模型若用 Int32／UInt32 就會有同樣問題。",
        ],
        expectBug:
            "XRP 輸入 \"3000000000\" → \"-1294967296\"，\"4294967295\" → \"-1\"，\"4294967296\"（超出範圍）→ \"0\"，\"12a\" → \"0\"，Stellar 的 memo \"007\" → \"7\"；123456 與 0 剛好對：「共 7 筆輸入，結果錯的有 5 筆」。",
        expectFixed:
            "7 筆都和正確答案一致（超出範圍與不是數字的回傳「不合法」，memo 007 原樣保留）：「共 7 筆輸入，結果錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, normalize:) 執行"
            }
            if !lines.contains("XRP 輸入 \"123456\" → \"123456\" ✓") {
                return "Bug 版在小的數字時要算對：123456 轉成 Int32 再轉回字串還是 123456"
            }
            if !lines.contains(
                "XRP 輸入 \"3000000000\" → \"-1294967296\" ✗（應該是 \"3000000000\"）")
            {
                return "Bug 還沒重現：轉成 Int32 之後，3000000000 會悄悄溢位成 -1294967296"
            }
            return n == 5 ? nil : "Bug 版應該有 5 筆結果錯（目前 \(n) 筆）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, normalize:) 執行"
            }
            return n == 0
                ? nil
                : "還有 \(n) 筆結果錯：XRP 檢查「只有數字、不超過 4294967295」後原樣回傳字串；XLM 的 memo 原樣回傳"
        }
    )
}
