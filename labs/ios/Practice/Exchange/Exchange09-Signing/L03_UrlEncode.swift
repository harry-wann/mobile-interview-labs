// L03：帶了空白、冒號或 Base64 的請求，簽名就是不對（URL 編碼不是 RFC 3986）
// 對應複習站：Exchange09Signing 第 3 題「URL 編碼有哪些陷阱？」
//
// 情境：簽名字串裡每個參數的名稱與值都要做 URL 編碼，規則是 RFC 3986：只有 A-Z
// a-z 0-9 -  _ ~ 不編碼，
//       其他一律編成大寫的 %XX（非 ASCII 字元先轉 UTF-8 的 byte）。演練拿 6
// 個字串，和正確的編碼結果比對：
//       含空白與 & = + 的字串、Timestamp（有冒號）、é、日文、含 / * ~ 的字串，
// 以及 Base64 的簽名（要放進 query 的 Signature）。
//
// 你要寫：
//   ① Bug 版（L03Bug.encode）：用內建的
// addingPercentEncoding(withAllowedCharacters: urlQueryAllowed)
// （失敗時回傳原字串）。
//   ② 修正版（L03Fixed.encode）：逐個 UTF-8 byte 處理：A-Z a-z 0-9 -  _ ~
// 原樣保留，其他編成大寫的 %XX。
//      （Exchange09Support.swift 裡有一份寫好的 E09.percentEncode
// 給別的演練用，這題請自己寫，不要抄。）
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange09-Signing/L03_UrlEncode.swift
import Foundation
import LabKit

private let cases: [(input: String, expected: String)] = [
    ("tea & cake=1+1", "tea%20%26%20cake%3D1%2B1"),
    ("2017-05-11T15:19:30", "2017-05-11T15%3A19%3A30"),
    ("é", "%C3%A9"),
    ("日本円", "%E6%97%A5%E6%9C%AC%E5%86%86"),
    ("a/b*c~d", "a%2Fb%2Ac~d"),
    (E09.getVector, "KLn4U%2FAu9BfxNEAqNebkXqZGpgjxOmyhMwOi%2BFmMfdg%3D"),
]

/// 演練流程（兩個版本共用）：6 個字串逐一編碼，和正確答案比對
private func scenario(_ log: LabLog, encode: (String) -> String) {
    var wrong = 0
    for item in cases {
        let out = encode(item.input)
        if out == item.expected {
            log("編碼 \"\(item.input)\" → \"\(out)\" ✓")
        } else {
            wrong += 1
            log("編碼 \"\(item.input)\" → \"\(out)\" ✗（應該是 \"\(item.expected)\"）")
        }
    }
    log("共 6 筆，編碼錯的有 \(wrong) 筆")
}

// MARK: - Bug 版
private enum L03Bug {
    static func encode(_ s: String) -> String {
        // TODO ①：用 s.addingPercentEncoding(withAllowedCharacters:
        // urlQueryAllowed) 編碼，失敗（nil）就回傳 s
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, encode: encode(_:))
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func encode(_ s: String) -> String {
        // TODO ②：逐個 UTF-8 byte（for b in s.utf8）處理：
        //        0-9（0x30...0x39）、A-Z（0x41...0x5A）、a-z（0x61...0x7A）、-
        // _ ~（0x2D、0x2E、0x5F、0x7E）原樣保留；
        //        其他編成 "%" 加上兩位大寫十六進位（例如 0x20 → %20，0xC3 →
        // %C3）
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log, encode: encode(_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 6 筆") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

private func isCorrect(_ lines: [String], _ input: String) -> Bool? {
    guard let line = lines.first(where: { $0.hasPrefix("編碼 \"\(input)\"") })
    else { return nil }
    return line.hasSuffix("✓")
}

extension Exchange09Signing {
    static let l03 = Lab(
        id: "L03",
        title: "帶了空白、冒號或 Base64 的請求，簽名不對",
        question: "第 3 題：URL 編碼有哪些陷阱？",
        goal:
            "簽名用的 URL 編碼只保留 A-Z a-z 0-9 - . _ ~，其他一律編成大寫的 %XX（空白是 %20，不是 +）；平台內建的編碼函式規則不同，不能直接拿來用。",
        steps: [
            "打開 Practice/Exchange/Exchange09-Signing/L03_UrlEncode.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看 6 個字串哪些編錯了：空白、冒號、/、*、Base64 的 +／=。",
            "想一想：.urlQueryAllowed 為什麼保留 & 和 =？它原本是拿來做什麼的？",
            "寫 TODO ②（修正版），選「修正版」執行，6 個字串都要和正確答案一致。",
            "進階：Android 上用 Java 的 URLEncoder.encode 也會出錯：空白變成 +，* 不編碼，~ 被編成 %7E（Android 版的演練可以看到結果）。",
        ],
        expectBug:
            "「tea & cake=1+1」編成 \"tea%20&%20cake=1+1\"、Timestamp 的冒號沒編碼、\"a/b*c~d\" 原樣不動、Base64 簽名的 / + = 都沒編碼；é 與日文是對的。「共 6 筆，編碼錯的有 4 筆」。",
        expectFixed:
            "6 筆都和正確答案一致：\"tea%20%26%20cake%3D1%2B1\"、\"2017-05-11T15%3A19%3A30\"、\"a%2Fb%2Ac~d\"…，「共 6 筆，編碼錯的有 0 筆」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, encode:) 執行"
            }
            if isCorrect(lines, "é") != true || isCorrect(lines, "日本円") != true
            {
                return
                    "Bug 版要用內建的 addingPercentEncoding(.urlQueryAllowed)：非 ASCII 字元（é、日文）它會編對"
            }
            if isCorrect(lines, "tea & cake=1+1") != false {
                return "Bug 還沒重現：.urlQueryAllowed 不會把 & = + 編碼，空白以外的符號都留著"
            }
            return n >= 1 ? nil : "Bug 還沒重現：至少要有一筆編錯"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, encode:) 執行"
            }
            return n == 0
                ? nil
                : "還有 \(n) 筆編錯：只有 A-Z a-z 0-9 - . _ ~ 不編碼，其他逐個 byte 編成大寫的 %XX"
        }
    )
}
