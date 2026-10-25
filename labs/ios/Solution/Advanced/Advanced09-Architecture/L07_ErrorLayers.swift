// L07：同一個 API 錯誤，三個畫面三種文案，還有畫面在不該重試的錯誤上重試（錯誤處理沒有分層）
// 對應複習站：Advanced09Architecture 第 4 題「模組化怎麼切才不會切過頭？」（錯誤型別放在哪一層、API 的錯誤碼不能越過 Data 層）、第 5 題「依賴注入（DI）怎麼做？」（Domain 宣告錯誤型別，Data 層實作並翻譯）
//
// 情境：下單 API 會失敗的原因有五種（下單帶有 clientOrderId，所以重送不會重複下單）：
//       {"code": 1043} 餘額不足、HTTP 429 太頻繁（Retry-After 標頭 3 秒）、連線逾時、{"code": 1102} 價格不符合最小跳動單位、{"code": 9999} 沒看過的錯誤碼。
//       「一般下單」「快速下單」「撤單」三個畫面都會遇到這五種錯誤。演練對同一組五個錯誤，列出三個畫面各自顯示的文案與重試決定。
// Bug 版：API 的原始錯誤（錯誤碼、HTTP 狀態碼、逾時）一路傳到畫面，三個畫面各自翻譯：
//        一般下單只認得 1043，其他都顯示「未知錯誤」；快速下單直接顯示原始碼，限流與逾時都立刻重試；撤單的文案縮寫不同，每種錯誤都重試（包含餘額不足）。
// 修正版：分三層。Data 層把原始錯誤翻成 Domain 的 OrderError（insufficientBalance、rateLimited(retryAfter:)、network…）；
//        可不可以重試是業務判斷，由 Domain 的 OrderError.retry 回答；Presentation 層只有一張文案對照表，三個畫面共用。
import Foundation
import LabKit

/// API 回來的原始錯誤（Data 層才看得懂）
private enum RawAPIError {
    case api(code: Int)                         // 回應內容 {"code": 1043}
    case http(status: Int, retryAfter: Int?)    // HTTP 狀態碼；429 會帶 Retry-After 標頭（秒）
    case timeout                                // 連線逾時

    var label: String {
        switch self {
        case .api(let code): "code \(code)"
        case .http(let status, _): "HTTP \(status)"
        case .timeout: "逾時"
        }
    }
}

/// 同一組五個錯誤，三個畫面都會遇到
private let rawErrors: [RawAPIError] = [
    .api(code: 1043), .http(status: 429, retryAfter: 3), .timeout, .api(code: 1102), .api(code: 9999),
]

/// 能不能重試（型別兩個版本共用，差別是「誰決定」）
private enum Retry: Equatable {
    case never
    case now
    case after(seconds: Int)

    var text: String {
        switch self {
        case .never: "不重試"
        case .now: "可以立刻重試"
        case .after(let seconds): "\(seconds) 秒後可重試"
        }
    }
}

/// 畫面上最後看到的：一段文案，加上要不要讓使用者（或自動）重試
private struct Shown: Equatable {
    let message: String
    let retry: Retry
}

/// 演練流程（兩個版本共用）：同一組五個錯誤，列出三個畫面各自的結果
private func scenario(_ log: LabLog, screens: [(name: String, show: (RawAPIError) -> Shown)]) {
    var consistent = 0
    for raw in rawErrors {
        let results = screens.map { screen -> Shown in
            let shown = screen.show(raw)
            log("\(raw.label)｜\(screen.name)｜\(shown.message)｜\(shown.retry.text)")
            return shown
        }
        if results.dropFirst().allSatisfy({ $0 == results[0] }) { consistent += 1 }
    }
    log("\(rawErrors.count) 個錯誤中，三個畫面的文案與重試決定都一致的有 \(consistent) 個")
}

// MARK: - Bug 版
private enum L07Bug {
    /// 一般下單：只認得餘額不足，其他都是「未知錯誤」，從不重試
    static func orderScreen(_ raw: RawAPIError) -> Shown {
        if case .api(code: 1043) = raw { return Shown(message: "餘額不足，請先入金或減少數量", retry: .never) }
        return Shown(message: "未知錯誤", retry: .never)
    }

    /// 快速下單：直接顯示原始碼；HTTP 錯誤與逾時都立刻重試（429 沒看 Retry-After）
    static func quickScreen(_ raw: RawAPIError) -> Shown {
        switch raw {
        case .api(let code): return Shown(message: "錯誤碼 \(code)", retry: .never)
        case .http(status: let status, retryAfter: _): return Shown(message: "HTTP \(status)", retry: .now)
        case .timeout: return Shown(message: "timeout", retry: .now)
        }
    }

    /// 撤單：文案是自己縮寫的，而且每一種錯誤都重試，包含餘額不足與價格錯誤
    static func cancelScreen(_ raw: RawAPIError) -> Shown {
        switch raw {
        case .api(code: 1043): return Shown(message: "餘額不足", retry: .now)
        case .api(code: 1102): return Shown(message: "價格不符", retry: .now)
        case .api: return Shown(message: "系統錯誤", retry: .now)
        case .http: return Shown(message: "操作太頻繁", retry: .now)
        case .timeout: return Shown(message: "網路不穩", retry: .now)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, screens: [("一般下單", orderScreen), ("快速下單", quickScreen), ("撤單", cancelScreen)])
    }
}

// MARK: - 修正版
private enum L07Fixed {
    // Domain 層：錯誤型別在這裡定義，不認識錯誤碼，也不認識 HTTP
    enum OrderError: Error, Equatable {
        case insufficientBalance
        case rateLimited(retryAfter: Int)
        case network
        case invalidPrice
        case unknown(code: Int)

        /// 可不可以重試，由 Domain 決定（業務判斷）：餘額不足重試一百次也不會成功；逾時可以重送，因為下單帶有 clientOrderId
        var retry: Retry {
            switch self {
            case .rateLimited(let seconds): .after(seconds: seconds)
            case .network: .now
            case .insufficientBalance, .invalidPrice, .unknown: .never
            }
        }
    }

    // Data 層：只有這裡看得到錯誤碼與 HTTP 狀態碼，翻成 Domain 的錯誤再往上傳
    enum OrderErrorMapper {
        static func map(_ raw: RawAPIError) -> OrderError {
            switch raw {
            case .api(code: 1043): return .insufficientBalance
            case .api(code: 1102): return .invalidPrice
            case .api(let code): return .unknown(code: code)
            case .http(status: 429, retryAfter: let retryAfter): return .rateLimited(retryAfter: retryAfter ?? 1)
            case .http(status: let status, retryAfter: _): return .unknown(code: status)
            case .timeout: return .network
            }
        }
    }

    // Presentation 層：只有這一張文案對照表，三個畫面共用
    enum OrderErrorMessages {
        static func text(for error: OrderError) -> String {
            switch error {
            case .insufficientBalance: "餘額不足，請先入金或減少數量"
            case .rateLimited(let seconds): "操作太頻繁，請 \(seconds) 秒後再試"
            case .network: "網路不穩，請確認連線後再試"
            case .invalidPrice: "價格不符合最小跳動單位，請修改價格"
            case .unknown(let code): "發生未預期的錯誤（\(code)），請聯絡客服"
            }
        }
    }

    /// 三個畫面共用的錯誤顯示：拿到的是 Domain 的 OrderError，不再有原始碼
    static func show(_ raw: RawAPIError) -> Shown {
        let error = OrderErrorMapper.map(raw)               // Repository 在 Data 層翻譯
        return Shown(message: OrderErrorMessages.text(for: error), retry: error.retry)
    }

    static func run(_ log: LabLog) async {
        scenario(log, screens: [("一般下單", show), ("快速下單", show), ("撤單", show)])
    }
}

// MARK: - 演練設定
extension Advanced09Architecture {
    static let l07 = Lab(
        id: "L07",
        title: "同一個錯誤三種文案，還重試了不該重試的",
        question: "第 4 題：模組化怎麼切才不會切過頭？（錯誤型別放在哪一層）、第 5 題：依賴注入（DI）怎麼做？（Domain 宣告、Data 層實作）",
        goal: "API 的錯誤碼不能越過 Data 層：Data 層把它翻成 Domain 的 error 型別；可不可以重試由 Domain 決定；Presentation 層只做一張文案對照表。",
        steps: [
            "選「Bug 版」執行，看同一個錯誤在三個畫面各顯示什麼：哪個畫面顯示「未知錯誤」？哪個顯示原始碼？哪個在餘額不足時還說可以重試？",
            "想一想：餘額不足重試一百次會成功嗎？HTTP 429 立刻重試，會發生什麼？這些判斷應該寫在哪一層？",
            "選「修正版」執行：五個錯誤在三個畫面的文案與重試決定都一樣；429 的文案帶著 3 秒，餘額不足不重試，逾時可以立刻重試。",
            "對照三層：Data 層的 OrderErrorMapper（只有它看得懂錯誤碼）、Domain 的 OrderError.retry（可不可以重試）、Presentation 的 OrderErrorMessages（文案）。",
            "進階：Android 一樣，Retrofit／OkHttp 的 HttpException 與 IOException 在 repository 翻成 sealed interface；非同步的 Result 也不要把 Throwable 原封不動丟給 ViewModel。",
        ],
        expectBug: "三個畫面不一致：「HTTP 429｜一般下單｜未知錯誤｜不重試」、「code 1043｜快速下單｜錯誤碼 1043｜不重試」、「code 1043｜撤單｜餘額不足｜可以立刻重試」；「5 個錯誤中，三個畫面的文案與重試決定都一致的有 0 個」。",
        expectFixed: "三個畫面一致：「HTTP 429｜…｜操作太頻繁，請 3 秒後再試｜3 秒後可重試」、「code 1043｜…｜餘額不足，請先入金或減少數量｜不重試」；「5 個錯誤中，三個畫面的文案與重試決定都一致的有 5 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.contains("個錯誤中，三個畫面的文案與重試決定都一致的有") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("HTTP 429｜一般下單｜未知錯誤｜不重試") { return "Bug 還沒重現：一般下單只認得 1043，其他錯誤（例如 HTTP 429）都要顯示「未知錯誤」、不重試" }
            if !lines.contains("code 1043｜快速下單｜錯誤碼 1043｜不重試") { return "Bug 還沒重現：快速下單把原始碼直接顯示出來，api(code:) 要顯示「錯誤碼 1043」" }
            if !lines.contains("code 1043｜撤單｜餘額不足｜可以立刻重試") { return "Bug 還沒重現：撤單每一種錯誤都重試，包含餘額不足（retry: .now）" }
            if !lines.contains("5 個錯誤中，三個畫面的文案與重試決定都一致的有 0 個") { return "Bug 還沒重現：三個畫面對同一個錯誤應該都不一樣" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.contains("個錯誤中，三個畫面的文案與重試決定都一致的有") }) else { return "演練沒有跑完：要用 scenario 執行" }
            let expected: [(label: String, message: String, retry: String)] = [
                ("code 1043", "餘額不足，請先入金或減少數量", "不重試"),
                ("HTTP 429", "操作太頻繁，請 3 秒後再試", "3 秒後可重試"),
                ("逾時", "網路不穩，請確認連線後再試", "可以立刻重試"),
                ("code 1102", "價格不符合最小跳動單位，請修改價格", "不重試"),
                ("code 9999", "發生未預期的錯誤（9999），請聯絡客服", "不重試"),
            ]
            for item in expected {
                let matched = lines.filter { $0.hasPrefix("\(item.label)｜") && $0.hasSuffix("｜\(item.message)｜\(item.retry)") }
                if matched.count != 3 {
                    return "\(item.label) 三個畫面都要顯示「\(item.message)」、\(item.retry)，現在只有 \(matched.count) 個畫面對：檢查 Data 層的對照（OrderErrorMapper）、Domain 的 OrderError.retry、Presentation 的 OrderErrorMessages"
                }
            }
            if !lines.contains("5 個錯誤中，三個畫面的文案與重試決定都一致的有 5 個") { return "三個畫面都要用同一組對照：Data 層翻譯、Domain 決定重試、Presentation 一張文案表" }
            return nil
        }
    )
}
