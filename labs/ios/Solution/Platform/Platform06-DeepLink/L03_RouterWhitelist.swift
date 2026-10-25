// L03：行銷連結一點就下單，還能被導到釣魚網站（Router 要用白名單）
// 對應複習站：Platform06DeepLink 第 5 題「怎麼避免外部連結觸發下單這類敏感操作？」
//
// 情境：App 的 Router 把外部連結（推播、簡訊、網頁、別的 App）轉成要開的畫面。官方網域是 www.example.com，
//       只開放兩條路徑：/ticker/{代號}（行情頁）和 /alert/{編號}（價格提醒，要登入）。
//       演練用 11 個連結測試 Router：3 個正常的，8 個是別人精心做的壞連結，每個連結都有「應該得到的結果」。
// Bug 版：Router 用「盡量處理」的寫法：只看路徑的第一段，不檢查 scheme 和 host，query 裡帶什麼就用什麼：
//        連結 /order?...&action=buy 會預填並自動送出，/withdraw 直接開到提領頁，redirect= 會被直接開啟，
//        其他看不懂的連結就回首頁。
// 修正版：白名單：只接受 https 加上 www.example.com，路徑解碼後只接受 /ticker/{代號} 和 /alert/{編號}，
//        代號與編號用正規表示式限制字元；query 完全不讀；其他一律 .invalid。
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
        guard let comps = URLComponents(string: urlString) else { return .home }
        let query = (comps.queryItems ?? []).reduce(into: [String: String]()) { $0[$1.name] = $1.value ?? "" }
        let parts = comps.path.split(separator: "/").map(String.init)       // 沒檢查 scheme、host

        if let redirect = query["redirect"] { return .web(redirect) }       // 連結指定要開哪個網址，就開
        switch parts.first {
        case "ticker": return .ticker(parts.count > 1 ? parts[1] : "btcjpy")   // 代號沒驗證
        case "alert": return isLoggedIn ? .alert(parts.count > 1 ? parts[1] : "") : .login(resume: urlString)
        case "order": return .order(symbol: query["symbol"] ?? "", amount: query["amount"] ?? "", autoSubmit: query["action"] == "buy")
        case "withdraw": return .withdraw(address: query["address"] ?? "", amount: query["amount"] ?? "")
        default: return .home                                                // 看不懂的連結，盡量回首頁
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { url, loggedIn in route(for: url, isLoggedIn: loggedIn) }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    static func route(for urlString: String, isLoggedIn: Bool) -> LinkRoute {
        guard let comps = URLComponents(string: urlString),
              comps.scheme == "https",                                       // 只接受 https
              let host = comps.host?.lowercased(),
              allowedHosts.contains(host) else { return .invalid }           // host 不在白名單
        // comps.path 已經解碼過，%2F 會變成 /，多出來的段落會讓下面的比對失敗
        let parts = comps.path.split(separator: "/").map(String.init)
        switch parts.count {
        case 0: return .home
        case 2 where parts[0] == "ticker" && parts[1].range(of: "^[a-z0-9]{3,12}$", options: .regularExpression) != nil:
            return .ticker(parts[1])
        case 2 where parts[0] == "alert" && parts[1].range(of: "^[A-Za-z0-9]{6,32}$", options: .regularExpression) != nil:
            return isLoggedIn ? .alert(parts[1]) : .login(resume: urlString)
        default: return .invalid                                             // 沒有列出的路徑，一律無效；query 完全沒讀
        }
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
            "選「Bug 版」執行，看 11 個連結的結果：哪些壞連結被接受了？「帶金額的下單連結」變成什麼？",
            "想一想：連結的內容是誰寫的？「盡量處理」的 Router 對沒看過的連結會怎麼做？",
            "選「修正版」執行，11 個連結都要得到應該的結果。",
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
