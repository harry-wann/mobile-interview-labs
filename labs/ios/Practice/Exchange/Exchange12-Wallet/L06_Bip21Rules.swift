// L06：掃到的付款連結帶 amount=1e3，欄位被填入 1000 BTC（BIP21 的嚴格解析：指數、req- 參數、重複的參數）
// 對應複習站：Exchange12Wallet 第 4 題「掃描 QR 得到的付款連結（BIP21）要怎麼解析？」
//
// 情境：掃描或貼上的付款連結是不可信的輸入，要嚴格解析。這個單元採用比 BIP21 文法更嚴格的自訂規則：
//       ・scheme（bitcoin:）不分大小寫
//       ・amount 的單位是 BTC，只接受「數字，可以有最多 8 位小數」，不能有指數、正負號、千分位
//       ・名稱以 req- 開頭的參數代表「必須理解」，不認識就要拒絕整個連結；其他不認識的參數可以忽略
//       ・同一個參數出現兩次是有歧義的，拒絕
//       連結的切分（E12.splitBip21：scheme、地址、query 的原始 key 與 value）已經寫好在 Exchange12Support.swift，
//       parse 要回傳「受理（金額）」或「拒絕」。七個連結：金額 20.3 加 label／大寫的 BITCOIN: 加金額 0.01／沒有 amount／
//       amount=1e3／amount=1 加 req-pay=x／amount=1&amount=2／amount=1 加不認識的 foo=bar（要忽略）。
//
// 你要寫：
//   ① Bug 版（L06Bug.parse）：把 parts.query 轉成字典（同名的後面蓋掉前面），不管 req- 與重複；amount 用 Decimal(string:) 轉換（失敗當作沒有）；
//      回傳 .accepted(amount)。
//   ② 修正版（L06Fixed.parse）：query 裡有 key 以 "req-" 開頭 → .rejected；同一個 key 出現第二次 → .rejected；
//      amount 存在時要符合正規表示式 ^[0-9]+(\.[0-9]{1,8})?$，否則 .rejected，通過才用 Decimal(string:) 轉換；其他參數忽略；回傳 .accepted(amount)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L06_Bip21Rules.swift
import Foundation
import LabKit

private enum Outcome: Equatable {
    case accepted(Decimal?)     // 受理：amount（BTC），沒有 amount 就是 nil
    case rejected
}

private func text(_ o: Outcome) -> String {
    switch o {
    case .accepted(let amount): return "受理，金額 " + (amount.map { "\($0) BTC" } ?? "沒有")
    case .rejected: return "拒絕"
    }
}

private let address = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"            // 公開的測試地址（比特幣創世區塊）

/// （描述, 連結, 正確的結果）
private let cases: [(String, String, Outcome)] = [
    ("金額 20.3 加 label", "bitcoin:\(address)?amount=20.3&label=Luke-Jr", .accepted(Decimal(string: "20.3"))),
    ("大寫的 BITCOIN:", "BITCOIN:\(address)?amount=0.01", .accepted(Decimal(string: "0.01"))),
    ("沒有 amount", "bitcoin:\(address)?label=donation", .accepted(nil)),
    ("amount=1e3", "bitcoin:\(address)?amount=1e3", .rejected),
    ("不認識的 req- 參數", "bitcoin:\(address)?amount=1&req-pay=x", .rejected),
    ("amount 出現兩次", "bitcoin:\(address)?amount=1&amount=2", .rejected),
    ("不認識的一般參數（忽略）", "bitcoin:\(address)?amount=1&foo=bar", .accepted(Decimal(string: "1"))),
]

/// 演練流程（兩個版本共用）：7 個連結逐一解析，和正確答案比對
private func scenario(_ log: LabLog, parse: (E12.Bip21Parts) -> Outcome) {
    var wrong = 0
    for (name, uri, expected) in cases {
        guard let parts = E12.splitBip21(uri) else {
            wrong += 1
            log("\(name) → 連結切分失敗")
            continue
        }
        let result = parse(parts)
        if result == expected {
            log("\(name) → \(text(result)) ✓")
        } else {
            wrong += 1
            log("\(name) → \(text(result)) ✗（應該是：\(text(expected))）")
        }
    }
    log("共 7 個連結，解析結果錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L06Bug {
    fileprivate static func parse(_ parts: E12.Bip21Parts) -> Outcome {
        // TODO ①：parts.query 轉成 [String: String] 字典（同名的後面蓋掉前面），不管 req- 與重複的參數；
        //        dict["amount"] 用 Decimal(string:) 轉換（失敗就是 nil）；回傳 .accepted(amount)
        return .rejected
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 修正版
private enum L06Fixed {
    fileprivate static func parse(_ parts: E12.Bip21Parts) -> Outcome {
        // TODO ②：parts.query 裡有 key 以 "req-" 開頭 → 回傳 .rejected；同一個 key 出現第二次 → 回傳 .rejected；
        //        amount 存在時要符合正規表示式 ^[0-9]+(\.[0-9]{1,8})?$（用 range(of:options: .regularExpression)），不符合 → .rejected；
        //        符合就用 Decimal(string:) 轉換；沒有 amount 回傳 .accepted(nil)
        return .rejected
    }

    static func run(_ log: LabLog) async {
        scenario(log, parse: parse(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 7 個連結") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Exchange12Wallet {
    static let l06 = Lab(
        id: "L06",
        title: "掃到 amount=1e3，欄位被填入 1000 BTC",
        question: "第 4 題：掃描 QR 得到的付款連結（BIP21）要怎麼解析？",
        goal: "付款連結是不可信的輸入：amount 只接受數字加最多 8 位小數（不接受指數），不認識的 req- 參數與重複的參數要拒絕整個連結。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L06_Bip21Rules.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 7 個連結哪些解析錯了：amount=1e3 變成多少 BTC？req- 的參數呢？",
            "想一想：為什麼 Decimal(string: \"1e3\") 不是 nil？這個值如果直接填進出金欄位，使用者看得出來嗎？",
            "寫 TODO ②（修正版），選「修正版」執行，7 個連結都要和正確答案一致。",
            "進階：Decimal(string:) 與 BigDecimal 都認得科學記號，它們解決的是 Double 的精度問題，不負責「格式驗證」；格式要先檢查，再轉換。",
        ],
        expectBug: "「amount=1e3 → 受理，金額 1000 BTC ✗」「不認識的 req- 參數 → 受理 ✗」「amount 出現兩次 → 受理，金額 2 BTC ✗」，其他四個剛好對：「共 7 個連結，解析結果錯的有 3 個」。",
        expectFixed: "7 個連結都和正確答案一致（1e3、req-、重複的 amount 都是「拒絕」，不認識的 foo=bar 被忽略）：「共 7 個連結，解析結果錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            if !lines.contains("金額 20.3 加 label → 受理，金額 20.3 BTC ✓") { return "Bug 版也要能解出正常的連結：amount=20.3 → 受理，金額 20.3 BTC" }
            if !lines.contains("amount=1e3 → 受理，金額 1000 BTC ✗（應該是：拒絕）") { return "Bug 還沒重現：Decimal(string: \"1e3\") 會變成 1000，沒有先檢查格式就會填進 1000 BTC" }
            return n == 3 ? nil : "Bug 版應該有 3 個連結解析錯（目前 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, parse:) 執行" }
            return n == 0 ? nil : "還有 \(n) 個連結解析錯：req- 與重複的參數要拒絕；amount 先用正規表示式檢查格式，通過才轉成 Decimal"
        }
    )
}
