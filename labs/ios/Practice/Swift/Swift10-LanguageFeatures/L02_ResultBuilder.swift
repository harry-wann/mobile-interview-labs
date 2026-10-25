// L02：出金備註含「&」，伺服器把備註讀成另一個參數（用 result builder 組
// query）
// 對應複習站：Swift10LanguageFeatures 第 2 題「result builder 是什麼？SwiftUI
// 的 body 為什麼可以那樣寫？」
//
// 情境：出金 API 的網址要帶 symbol、memo（使用者輸入的備註）、depth 三個參數。
// 使用者的備註是「ETH&BTC=1」。
//
// 你要寫：
//   ① Bug 版：用字串拼接組出出金網址，備註直接放進去、不編碼。
//   ② 修正版：把 QueryBuilder（result builder）的五個方法寫完整，用
// URLQueryItem 組參數，讓網址由 URLComponents 編碼。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift10-LanguageFeatures/L02_ResultBuilder.swift
import Foundation
import LabKit

/// 要送出的參數（兩個版本共用）
private let symbol = "btcjpy"
// 使用者在備註欄打的字
private let memo = "ETH&BTC=1"
private let precise = false

/// 模擬伺服器：把網址裡的參數解析出來（兩個版本共用）
private func serverParse(_ urlString: String) -> [URLQueryItem] {
    URLComponents(string: urlString)?.queryItems ?? []
}

/// 把解析結果印出來（兩個版本共用）
private func report(_ log: LabLog, url: String) {
    let items = serverParse(url)
    log("網址：\(url)")
    log("伺服器解析出 \(items.count) 個參數：\(items.map(\.name).joined(separator: "、"))")
    log("伺服器讀到的 memo：\(items.first { $0.name == "memo" }?.value ?? "（沒有）")")
}

// MARK: - Bug 版
private enum L02Bug {
    static func run(_ log: LabLog) async {
        // TODO ①：用字串拼接出網址：https://api.example.com/withdraw?symbol=…&memo=…&depth=…（symbol、memo 直接放進字串，不做編碼；depth 依 precise 選 step0 或 step5）
        let url = "https://api.example.com/withdraw"
        report(log, url: url)
    }
}

// MARK: - 修正版
private enum L02Fixed {
    @resultBuilder
    enum QueryBuilder {
        // TODO ②：把五個方法寫完整（現在都回傳空陣列，所以組不出任何參數）
        // 一行一個 URLQueryItem：包成陣列
        static func buildExpression(_ item: URLQueryItem) -> [URLQueryItem] {
            []
        }
        // 把每一行的結果合併成一個陣列
        static func buildBlock(_ parts: [URLQueryItem]...) -> [URLQueryItem] {
            []
        }
        // 沒有 else 的 if：條件不成立時是 nil
        static func buildOptional(_ part: [URLQueryItem]?) -> [URLQueryItem] {
            []
        }
        // if / else 的第一個分支
        static func buildEither(first: [URLQueryItem]) -> [URLQueryItem] { [] }
        // if / else 的第二個分支
        static func buildEither(second: [URLQueryItem]) -> [URLQueryItem] { [] }
    }

    static func url(
        _ base: String, @QueryBuilder _ content: () -> [URLQueryItem]
    ) -> String {
        var components = URLComponents(string: base)!
        // URLComponents 會替每個值做百分比編碼
        components.queryItems = content()
        return components.url!.absoluteString
    }

    static func run(_ log: LabLog) async {
        let result = url("https://api.example.com/withdraw") {
            URLQueryItem(name: "symbol", value: symbol)
            URLQueryItem(name: "memo", value: memo)
            // if / else 靠 buildEither
            if precise {
                URLQueryItem(name: "depth", value: "step0")
            } else {
                URLQueryItem(name: "depth", value: "step5")
            }
        }
        report(log, url: result)
    }
}

// MARK: - 演練設定
extension Swift10LanguageFeatures {
    static let l02 = Lab(
        id: "L02",
        title: "備註含 &，伺服器讀成另一個參數",
        question: "第 2 題：result builder 是什麼？SwiftUI 的 body 為什麼可以那樣寫？",
        goal:
            "出金網址要帶使用者輸入的備註。先寫出用字串拼接、備註被截斷的 Bug 版，再自己寫一個 result builder，用 URLQueryItem 組出正確的網址。",
        steps: [
            "打開 Practice/Swift/Swift10-LanguageFeatures/L02_ResultBuilder.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：伺服器解析出幾個參數、讀到的 memo 是什麼。",
            "寫 TODO ②，選「修正版」執行：memo 完整，網址裡的 & 與 = 被編碼成 %26、%3D。",
            "看修正版的 QueryBuilder：if / else 為什麼可以直接寫在 closure 裡？是哪個方法在處理？",
            "進階：把 buildEither 其中一個拿掉，看編譯器怎麼說。",
        ],
        expectBug: "伺服器解析出 4 個參數（symbol、memo、BTC、depth），讀到的 memo 只有「ETH」。",
        expectFixed: "伺服器解析出 3 個參數（symbol、memo、depth），memo 是完整的「ETH&BTC=1」。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("伺服器解析出 4 個參數：symbol、memo、BTC、depth") {
                return "Bug 還沒重現：用字串拼接，備註裡的 & 沒有編碼，伺服器會多讀出一個 BTC 參數"
            }
            if !lines.contains("伺服器讀到的 memo：ETH") {
                return "Bug 還沒重現：伺服器讀到的 memo 應該只有「ETH」"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("伺服器解析出 3 個參數：symbol、memo、depth") {
                return
                    "應該解析出 3 個參數（symbol、memo、depth）：用 URLQueryItem 與 URLComponents 組網址，depth 的 if / else 要能運作（buildEither）"
            }
            if !lines.contains("伺服器讀到的 memo：ETH&BTC=1") {
                return "memo 應該完整是「ETH&BTC=1」：值要經過 URLComponents 編碼"
            }
            return nil
        }
    )
}
