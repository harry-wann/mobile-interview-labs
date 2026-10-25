// L02：行情 API 維護中少了一個欄位，App 一打開就當掉（強制解包）
// 對應複習站：Advanced06Crash 第 2 題「iOS 和 Android 常見的 crash 有哪幾種？」
// （強制解包）
//
// 情境：行情 API 回傳 JSON，App 取出 "last"（最新成交價）顯示。API
// 維護期間會回傳不完整的資料：
//       少了 last，或 last 是 "--"。演練依序處理三筆回應：正常、少欄位、"--"。
// Bug 版：用 JSONSerialization 取出字典，`as!` 轉型、`Decimal(string:)!`
// 強制解包。
//        第二筆回應沒有 last，程式直接中止（例外類型 EXC_BREAKPOINT），整個
// App 當掉。
// 修正版：在解碼的那一層處理「欄位可能不存在」：last 宣告成 Decimal?，
// 缺資料或格式不對就是 nil；
//        畫面拿到的是安全的資料，沒有新價格時沿用上一筆。
import Foundation
import LabKit

/// 三筆回應（兩個版本共用）
private let responses = [
    #"{"symbol":"BTC_JPY","last":"15000000"}"#,
    #"{"symbol":"BTC_JPY"}"#,  // 維護中：少了 last
    #"{"symbol":"BTC_JPY","last":"--"}"#,  // 維護中：格式不對
]

// MARK: - Bug 版
private enum L02Bug {
    static func parseLast(_ data: Data) -> Decimal {
        // 以為一定是字典
        let json =
            try! JSONSerialization.jsonObject(with: data) as! [String: Any]
        // 以為 last 一定存在、一定是數字
        return Decimal(string: json["last"] as! String)!
    }

    static func run(_ log: LabLog) async {
        for body in responses {
            let last = parseLast(Data(body.utf8))
            log("BTC_JPY 最新價 \(last)")
        }
        log("三筆回應都處理完，App 沒有當掉")
    }
}

// MARK: - 修正版
private enum L02Fixed {
    struct Ticker: Decodable {
        let symbol: String
        let last: Decimal?

        enum CodingKeys: String, CodingKey { case symbol, last }

        /// 只接受純數字（可有小數點）。Decimal(string: "--") 不會是 nil，
        /// 要自己先檢查格式
        static func parsePrice(_ text: String) -> Decimal? {
            text.range(of: #"^\d+(\.\d+)?$"#, options: .regularExpression)
                == nil ? nil : Decimal(string: text)
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            symbol = try container.decode(String.self, forKey: .symbol)
            // 缺欄位、型別不對、"--" 都變成 nil，不丟例外也不中止
            last = (try? container.decode(String.self, forKey: .last)).flatMap(
                Ticker.parsePrice)
        }
    }

    static func run(_ log: LabLog) async {
        var previous: Decimal?
        for body in responses {
            guard
                let ticker = try? JSONDecoder().decode(
                    Ticker.self, from: Data(body.utf8))
            else {
                log("回應不是合法的 JSON，略過這一筆")
                continue
            }
            if let last = ticker.last {
                previous = last
                log("\(ticker.symbol) 最新價 \(last)")
            } else {
                log(
                    "\(ticker.symbol) 最新價 \(previous.map { "\($0)" } ?? "—")（這筆資料缺少 last，沿用上一筆）"
                )
            }
        }
        log("三筆回應都處理完，App 沒有當掉")
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l02 = Lab(
        id: "L02",
        title: "API 少一個欄位，App 一打開就當掉",
        question: "第 2 題：iOS 和 Android 常見的 crash 有哪幾種？（強制解包）",
        goal: "API 回傳的資料不可靠。缺欄位、格式不對時，App 要沿用上一筆價格，而不是當掉。",
        steps: [
            "從 Xcode 執行（⌘R），才看得到當掉的位置。",
            "選「Bug 版」按「執行」：第二筆回應時 App 當掉，Xcode 停在 `as! String` 或 `Decimal(string:)!` 那一行（Thread 1: Fatal error 或 EXC_BREAKPOINT）。",
            "想一想：與其一行一行補 `if let`，能不能在解碼的那一層就把「欄位可能不存在」處理掉？",
            "按 ■ 停止後重新執行，選「修正版」執行，確認三筆都處理完。",
        ],
        expectBug:
            "App 當掉，主控台：Fatal error: Unexpectedly found nil while unwrapping an Optional value（或 Could not cast value）。",
        expectFixed:
            "第一筆印出價格 15000000，第二、第三筆印出「沿用上一筆」，最後印出「三筆回應都處理完，App 沒有當掉」。",
        bugCrashes: true,
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkFixed: { lines in
            if !lines.contains("BTC_JPY 最新價 15000000") {
                return "第一筆正常的回應應該印出「BTC_JPY 最新價 15000000」"
            }
            let fallbacks = lines.filter { $0.contains("沿用上一筆") }
            if fallbacks.count != 2 {
                return
                    "第二、第三筆（缺 last、\"--\"）都應該印出「沿用上一筆」，目前有 \(fallbacks.count) 筆"
            }
            if lines.last != "三筆回應都處理完，App 沒有當掉" {
                return "三筆回應要全部處理完，最後印出「三筆回應都處理完，App 沒有當掉」"
            }
            return nil
        }
    )
}
