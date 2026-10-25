// L05：付款連結的備註 a+b 變成「a b」，不完整的百分比編碼也解出亂碼（BIP21
// 的百分比解碼）
// 對應複習站：Exchange12Wallet 第 4 題「掃描 QR 得到的付款連結（BIP21）
// 要怎麼解析？」
//
// 情境：BIP21 的參數值用百分比編碼（RFC 3986）：`%XX` 是一個位元組，
// 中文等字元是 UTF-8 的位元組。**`+` 在這裡是字面的加號，不是空白**
//       （網頁表單的規則才是 + 當空白）。解碼後必須是合法的 UTF-8，不完整的
// `%` 序列要視為無效。
//       連結的切分（scheme、地址、query）已經寫好在 Exchange12Support.swift，
// 這題只寫「單一個參數值怎麼解碼」。
//       演練檢查 7 個值：Luke-Jr、Donation%20for%20project%20xyz、a+b、
// %E5%92%96%E5%95%A1（咖啡）、
//       %E5%92（UTF-8 只有一半）、100%（% 後面沒有兩個十六進位字元）、
// %zz（不是十六進位）。
//
// 你要寫：
//   ① Bug 版（L05Bug.decode）：raw.replacingOccurrences(of: "+", with: " ")，
// 再回傳 removingPercentEncoding（失敗是 nil）。
//   ② 修正版（L05Fixed.decode）：逐個 UTF-8 位元組掃描 raw：遇到 "%"（0x25）
// 就讀接下來兩個十六進位字元還原成一個位元組（不足兩個或不是十六進位就回傳
// nil），
//      其他位元組（包含 +）原樣保留；最後用 String(bytes: bytes, encoding:
// utf8) 轉回字串，不是合法的 UTF-8 就是 nil。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L05_Bip21Decode.swift
import Foundation
import LabKit

/// （原始的參數值, 正確的解碼結果；nil 代表無效）
private let cases: [(String, String?)] = [
    ("Luke-Jr", "Luke-Jr"),
    ("Donation%20for%20project%20xyz", "Donation for project xyz"),
    ("a+b", "a+b"),
    ("%E5%92%96%E5%95%A1", "咖啡"),
    ("%E5%92", nil),
    ("100%", nil),
    ("%zz", nil),
]

/// 演練流程（兩個版本共用）：7 個值逐一解碼，和正確答案比對
private func scenario(_ log: LabLog, decode: (String) -> String?) {
    var wrong = 0
    for (raw, expected) in cases {
        let result = decode(raw)
        let shown = result.map { "\"\($0)\"" } ?? "無效"
        if result == expected {
            log("解碼 \"\(raw)\" → \(shown) ✓")
        } else {
            wrong += 1
            log(
                "解碼 \"\(raw)\" → \(shown) ✗（應該是 \(expected.map { "\"\($0)\"" } ?? "無效")）"
            )
        }
    }
    log("共 7 個值，解碼結果錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L05Bug {
    static func decode(_ raw: String) -> String? {
        // TODO ①：先 raw.replacingOccurrences(of: "+", with: " ")，再回傳
        // removingPercentEncoding
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode(_:))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func decode(_ raw: String) -> String? {
        // TODO ②：把 raw.utf8 轉成 [UInt8]，逐個掃描：遇到 0x25（%）
        // 就讀接下來兩個十六進位字元（0-9、a-f、A-F）還原成一個位元組，
        //        不足兩個或不是十六進位就回傳 nil；其他位元組（包含 +）
        // 原樣放進結果；
        //        最後 String(bytes: bytes, encoding: utf8)，不是合法的 UTF-8
        // 就是 nil
        return nil
    }

    static func run(_ log: LabLog) async {
        scenario(log, decode: decode(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 7 個值") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange12Wallet {
    static let l05 = Lab(
        id: "L05",
        title: "付款連結的備註 a+b 變成 a b",
        question: "第 4 題：掃描 QR 得到的付款連結（BIP21）要怎麼解析？",
        goal:
            "BIP21 的百分比解碼，+ 是字面的加號，不是空白；解碼後要是合法的 UTF-8，不完整的 % 序列視為無效。掃描到的內容是不可信的輸入，要嚴格解析。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L05_Bip21Decode.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 7 個值哪些解碼錯了（Android 版的結果會多一個）。",
            "想一想：a+b 是使用者想傳的備註，變成 a b 之後，金額沒變，但資訊被悄悄改了；如果是地址的一部分呢？",
            "寫 TODO ②（修正版），選「修正版」執行，7 個值都要和正確答案一致。",
            "進階：掃到的 amount 也要用 Decimal／BigDecimal 重新驗證，不要用 Double；掃描只填入欄位，不自動送出。",
        ],
        expectBug:
            "iOS：「解碼 \"a+b\" → \"a b\" ✗」，其他 6 個剛好對（removingPercentEncoding 遇到不合法的序列會回傳 nil）：「共 7 個值，解碼結果錯的有 1 個」。Android 的 URLDecoder 還會把只有半個字元的 %E5%92 解成亂碼 ✗：「錯的有 2 個」。",
        expectFixed:
            "7 個值都和正確答案一致：a+b 保持 \"a+b\"，咖啡解出中文，三個不完整的序列都是「無效」：「共 7 個值，解碼結果錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, decode:) 執行"
            }
            if !lines.contains("解碼 \"%E5%92%96%E5%95%A1\" → \"咖啡\" ✓") {
                return "Bug 版也要能解出百分比編碼的中文（removingPercentEncoding）"
            }
            if !lines.contains("解碼 \"a+b\" → \"a b\" ✗（應該是 \"a+b\"）") {
                return "Bug 還沒重現：先把 + 換成空白，a+b 會變成 a b"
            }
            return n >= 1 ? nil : "Bug 還沒重現：至少有一個值解碼錯"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, decode:) 執行"
            }
            return n == 0
                ? nil : "還有 \(n) 個值解碼錯：+ 原樣保留；% 後面要有兩個十六進位字元；解出來要是合法的 UTF-8"
        }
    )
}
