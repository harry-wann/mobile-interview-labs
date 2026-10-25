// L03：行銷連結一點就下單，還能被導到釣魚網站（Router 要用白名單）
// 對應複習站：Platform06DeepLink 第 5 題「怎麼避免外部連結觸發下單這類敏感操作？」
//
// 情境：App 的 Router 把外部連結（推播、簡訊、網頁、別的 App）轉成要開的畫面。官方網域是 www.example.com，
//       只開放兩條路徑：/ticker/{代號}（行情頁）和 /alert/{編號}（價格提醒，要登入）。
//       演練用 11 個連結測試 Router：3 個正常的，8 個是別人精心做的壞連結，每個連結都有「應該得到的結果」。
//
// 你要寫：
//   ① Bug 版：route(for:isLoggedIn:) 用「盡量處理」的寫法：不檢查 scheme 和 host；有 redirect 參數就 .web；
//      只看路徑第一段，ticker／alert／order／withdraw 各回傳對應的 case，其他回 .home。
//   ② 修正版：白名單。只接受 https 加上 allowedHosts 內的 host；路徑解碼後切段，只接受 /ticker/{代號}、/alert/{編號}
//      （用正規表示式限制字元）和根路徑；query 完全不讀；其他一律 .invalid。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform06-DeepLink/L03_RouterWhitelist.swift
import Foundation
import LabKit

/// 連結解析出來的結果（兩個版本共用）。order、withdraw、web 是「不應該由連結直接觸發」的動作，只有 Bug 版會回傳它們
private enum LinkRoute: Equatable, CustomStringConvertible {
    case home
    case ticker(String)
    case alert(String)
    case login(resume: String)
    case order(symbol: String, amount: String, autoSubmit: Bool)
    case withdraw(address: String, amount: String)
    case web(String)
    case invalid

    var description: String {
        switch self {
        case .home: "home"
        case .ticker(let s): "ticker(\(s))"
        case .alert(let id): "alert(\(id))"
        case .login(let resume): "login(resume: \(resume))"
        case .order(let s, let a, let auto): "order(\(s), \(a), 自動送出: \(auto))"
        case .withdraw(let address, let amount): "withdraw(\(address), \(amount))"
        case .web(let url): "web(\(url))"
        case .invalid: "invalid"
        }
    }
}

private let allowedHosts: Set<String> = ["www.example.com"]

/// 測試的連結（兩個版本共用）：名稱、連結、是否已登入、應該得到的結果
private let cases: [(name: String, url: String, loggedIn: Bool, expected: LinkRoute)] = [
    ("正常的行情連結", "https://www.example.com/ticker/btcjpy", false, .ticker("btcjpy")),
    ("正常的提醒連結（已登入）", "https://www.example.com/alert/ab12cd34", true, .alert("ab12cd34")),
    ("正常的提醒連結（未登入）", "https://www.example.com/alert/ab12cd34", false, .login(resume: "https://www.example.com/alert/ab12cd34")),
    ("假的網域", "https://evil.example.org/ticker/btcjpy", true, .invalid),
    ("不是 https", "http://www.example.com/ticker/btcjpy", true, .invalid),
    ("帶金額的下單連結", "https://www.example.com/order?symbol=btcjpy&amount=10&action=buy", true, .invalid),
    ("提領連結", "https://www.example.com/withdraw?address=abc&amount=1", true, .invalid),
    ("路徑用 %2F 繞過", "https://www.example.com/ticker/btcjpy%2F..%2Fwithdraw", true, .invalid),
    ("代號夾帶特殊字元", "https://www.example.com/ticker/btc%3Cscript%3E", true, .invalid),
    ("redirect 參數指向別的網站", "https://www.example.com/ticker/btcjpy?redirect=https://evil.example.org/login", true, .ticker("btcjpy")),
    ("沒列出的路徑", "https://www.example.com/settings/reset", true, .invalid),
]

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, route: (String, Bool) -> LinkRoute) {
    var wrong = 0
    for item in cases {
        let result = route(item.url, item.loggedIn)
        if result == item.expected {
            log("✓ \(item.name) → \(result)")
        } else {
            wrong += 1
            log("✗ \(item.name) → \(result)（應該是 \(item.expected)）")
        }
    }
    log("共 \(cases.count) 個連結，處理錯誤 \(wrong) 個")
}

// MARK: - Bug 版
private enum L03Bug {
    static func route(for urlString: String, isLoggedIn: Bool) -> LinkRoute {
        // TODO ①：用「盡量處理」的寫法，不檢查 scheme 和 host，也不驗證任何參數：
        //        1. URLComponents(string:) 解析（失敗回 .home）；comps.queryItems 整理成字典；comps.path 用 "/" 切成 parts
        //        2. query 有 "redirect" 就回傳 .web(它的值)
        //        3. 看 parts.first：ticker → .ticker(parts[1])；alert → 已登入 .alert(parts[1])，未登入 .login(resume: urlString)；
        //           order → .order(symbol:amount:autoSubmit:)（query["action"] == "buy" 就自動送出）；withdraw → .withdraw(address:amount:)；
        //           其他 → .home
        return .home
    }

    static func run(_ log: LabLog) async {
        scenario(log) { url, loggedIn in route(for: url, isLoggedIn: loggedIn) }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func route(for urlString: String, isLoggedIn: Bool) -> LinkRoute {
        // TODO ②：白名單：
        //        1. URLComponents(string:) 解析；scheme 要是 "https"，host（轉小寫）要在 allowedHosts 裡，否則 .invalid
        //        2. comps.path（已解碼，%2F 會變成 /）用 "/" 切成 parts；parts.count == 0 回 .home
        //        3. 兩段而且 parts[0] == "ticker"、代號符合 ^[a-z0-9]{3,12}$（String.range(of:options: .regularExpression)）→ .ticker
        //        4. 兩段而且 parts[0] == "alert"、編號符合 ^[A-Za-z0-9]{6,32}$ → 已登入 .alert，未登入 .login(resume: urlString)
        //        5. 其他一律 .invalid；query 完全不要讀
        return .invalid
    }

    static func run(_ log: LabLog) async {
        scenario(log) { url, loggedIn in route(for: url, isLoggedIn: loggedIn) }
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("共 ") && $0.contains("處理錯誤") }) else { return nil }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last
}

extension Platform06DeepLink {
    static let l03 = Lab(
        id: "L03",
        title: "行銷連結一點就下單，還能導去釣魚網站",
        question: "第 5 題：怎麼避免外部連結觸發下單這類敏感操作？",
        goal: "Router 把外部連結當成不可信的輸入：只接受白名單內的 host 與路徑，驗證參數，連結只能「開到畫面」，不能直接下單、提領或導向別的網站。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看 11 個連結的結果：哪些壞連結被接受了？「帶金額的下單連結」變成什麼？出現 ✓。",
            "想一想：連結的內容是誰寫的？「盡量處理」的 Router 對沒看過的連結會怎麼做？",
            "寫 TODO ②，選「修正版」執行，11 個連結都要得到應該的結果。",
            "進階：試試把路徑改成 /ticker/BTCJPY（大寫），或在代號後面加 %00，看正規表示式擋不擋得住。",
        ],
        expectBug: "有 8 個連結處理錯誤：假的網域、http、下單、提領、%2F、特殊字元、redirect、沒列出的路徑；其中下單連結變成 order(…, 自動送出: true)。",
        expectFixed: "「共 11 個連結，處理錯誤 0 個」，壞連結全部是 invalid。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let wrong = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, route:) 執行" }
            if !lines.contains(where: { $0.hasPrefix("✓ 正常的行情連結") }) { return "Bug 版也要能處理正常的連結：/ticker/{代號} 回傳 .ticker(代號)" }
            return wrong >= 6 ? nil : "Bug 還沒重現：Router 要用「盡量處理」的寫法，不檢查 scheme 與 host，也不驗證參數（目前只擋錯 \(wrong) 個，應該 6 個以上）"
        },
        checkFixed: { lines in
            guard let wrong = wrongCount(lines) else { return "演練沒有跑完：要用 scenario(log, route:) 執行" }
            if wrong == 0 { return nil }
            let first = lines.first(where: { $0.hasPrefix("✗") }) ?? ""
            return "還有 \(wrong) 個連結處理錯誤，例如：\(first)"
        }
    )
}
