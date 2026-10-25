// L07：同一個 API 錯誤，三個畫面三種文案，還有畫面在不該重試的錯誤上重試（錯誤處理沒有分層）
// 對應複習站：Advanced09Architecture 第 4 題「模組化怎麼切才不會切過頭？」（錯誤型別放在哪一層、API 的錯誤碼不能越過 Data 層）、第 5 題「依賴注入（DI）怎麼做？」（Domain 宣告錯誤型別，Data 層實作並翻譯）
//
// 情境：下單 API 會失敗的原因有五種（下單帶有 clientOrderId，所以重送不會重複下單）：
//       {"code": 1043} 餘額不足、HTTP 429 太頻繁（Retry-After 標頭 3 秒）、連線逾時、{"code": 1102} 價格不符合最小跳動單位、{"code": 9999} 沒看過的錯誤碼。
//       「一般下單」「快速下單」「撤單」三個畫面都會遇到這五種錯誤。演練對同一組五個錯誤，列出三個畫面各自顯示的文案與重試決定。
//
// 你要寫：
//   ① Bug 版：三個畫面各自翻譯原始錯誤。
//      一般下單：只認得 api(code: 1043)，文案「餘額不足，請先入金或減少數量」、不重試；其他都是「未知錯誤」、不重試。
//      快速下單：直接顯示原始碼。api 顯示「錯誤碼 <code>」、不重試；http 顯示「HTTP <status>」、立刻重試（.now）；逾時顯示「timeout」、立刻重試。
//      撤單：每一種錯誤都立刻重試（.now），文案是自己縮寫的：1043「餘額不足」、1102「價格不符」、其他 api 錯誤「系統錯誤」、http「操作太頻繁」、逾時「網路不穩」。
//   ② 修正版：分三層。Data 層 OrderErrorMapper 把原始錯誤翻成 Domain 的 OrderError；
//      Domain 的 OrderError.retry 決定可不可以重試（限流等 Retry-After 的秒數、逾時立刻重試、其他不重試）；
//      Presentation 層 OrderErrorMessages 是唯一的文案對照表：
//        insufficientBalance「餘額不足，請先入金或減少數量」、rateLimited「操作太頻繁，請 N 秒後再試」、network「網路不穩，請確認連線後再試」、
//        invalidPrice「價格不符合最小跳動單位，請修改價格」、unknown「發生未預期的錯誤（N），請聯絡客服」。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced09-Architecture/L07_ErrorLayers.swift
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
        // TODO ①-1：api(code: 1043) 回傳 Shown(message: "餘額不足，請先入金或減少數量", retry: .never)；其他錯誤一律 Shown(message: "未知錯誤", retry: .never)
        return Shown(message: "", retry: .never)
    }

    /// 快速下單：直接顯示原始碼；HTTP 錯誤與逾時都立刻重試（429 沒看 Retry-After）
    static func quickScreen(_ raw: RawAPIError) -> Shown {
        // TODO ①-2：switch raw：api 顯示 "錯誤碼 \(code)"（.never）；http 顯示 "HTTP \(status)"（.now）；timeout 顯示 "timeout"（.now）
        return Shown(message: "", retry: .never)
    }

    /// 撤單：文案是自己縮寫的，而且每一種錯誤都重試，包含餘額不足與價格錯誤
    static func cancelScreen(_ raw: RawAPIError) -> Shown {
        // TODO ①-3：每一種錯誤的 retry 都是 .now；文案：1043「餘額不足」、1102「價格不符」、其他 api 錯誤「系統錯誤」、http「操作太頻繁」、timeout「網路不穩」
        return Shown(message: "", retry: .never)
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
            // TODO ②-2：rateLimited 回傳 .after(seconds: retryAfter 的秒數)；network 回傳 .now；其他（insufficientBalance、invalidPrice、unknown）回傳 .never
            return .never
        }
    }

    // Data 層：只有這裡看得到錯誤碼與 HTTP 狀態碼，翻成 Domain 的錯誤再往上傳
    enum OrderErrorMapper {
        static func map(_ raw: RawAPIError) -> OrderError {
            // TODO ②-1：api 1043 → insufficientBalance、api 1102 → invalidPrice、其他 api 錯誤碼 → unknown(code:)；
            //          http 429 → rateLimited(retryAfter: Retry-After 的秒數，沒有就用 1)、其他 http → unknown(code: 狀態碼)；timeout → network
            return .unknown(code: 0)
        }
    }

    // Presentation 層：只有這一張文案對照表，三個畫面共用
    enum OrderErrorMessages {
        static func text(for error: OrderError) -> String {
            // TODO ②-3：switch error，每個 case 回傳檔案開頭列的文案（rateLimited 與 unknown 要把秒數、錯誤碼放進文案）
            return ""
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
        goal: "API 的錯誤碼不能越過 Data 層：Data 層把它翻成 Domain 的 error 型別；可不可以重試由 Domain 決定；Presentation 層只做一張文案對照表。 先寫出三個畫面各自翻譯的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced09-Architecture/L07_ErrorLayers.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看同一個錯誤在三個畫面各顯示什麼：哪個畫面顯示「未知錯誤」？哪個顯示原始碼？哪個在餘額不足時還說可以重試？",
            "想一想：餘額不足重試一百次會成功嗎？HTTP 429 立刻重試，會發生什麼？這些判斷應該寫在哪一層？",
            "寫 TODO ②，選「修正版」執行：五個錯誤在三個畫面的文案與重試決定都一樣；429 的文案帶著 3 秒，餘額不足不重試，逾時可以立刻重試。",
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
