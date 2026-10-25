// L04：被限流時，使用者看到「發生未知錯誤」（typed throws 與窮舉的 switch）
// 對應複習站：Swift10LanguageFeatures 第 5 題「typed throws 解決什麼問題？」
//
// 情境：網路層的錯誤 NetworkError 一開始只有 offline、badStatus。
// 後來伺服器開始限流，有人新增了 rateLimited(retryAfter:)，
//      但顯示錯誤訊息的地方沒有人記得改。
//
// 你要寫：
//   ① Bug 版：message(for:) 用 as? 轉型成 NetworkError，沒處理 rateLimited，用
// default 兜底。
//   ② 修正版：fetch 改成 typed throws，catch 裡的 switch 窮舉三個 case，不用
// default。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift10-LanguageFeatures/L04_TypedThrows.swift
import Foundation
import LabKit

/// 網路層的錯誤（兩個版本共用）。rateLimited 是後來新增的
private enum NetworkError: Error {
    case offline
    case badStatus(Int)
    case rateLimited(retryAfter: Int)
}

// MARK: - Bug 版
private enum L04Bug {
    // 只知道「會丟錯誤」，不知道是哪一種
    static func fetch() throws -> Int {
        throw NetworkError.rateLimited(retryAfter: 30)
    }

    static func message(for error: Error) -> String {
        // TODO ①：把 error 轉型成 NetworkError（as?），再 switch：offline
        // 回傳「沒有網路」、badStatus 回傳「伺服器錯誤 \(code)」，
        //        其他的用 default 兜底，回傳「發生未知錯誤」（rateLimited
        // 就是這樣被漏掉的）
        ""
    }

    static func run(_ log: LabLog) async {
        do {
            _ = try fetch()
        } catch {
            log("使用者看到：\(message(for: error))")
        }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    // TODO ②-1：在 throws 後面用括號寫明錯誤型別 NetworkError（typed throws），
    // 丟錯誤時也可以省略型別，直接寫 rateLimited(…)
    static func fetch() throws -> Int {
        throw NetworkError.rateLimited(retryAfter: 30)
    }

    static func run(_ log: LabLog) async {
        do {
            _ = try fetch()
        } catch {
            // TODO ②-2：fetch 改成 typed throws 之後，error 的型別就是
            // NetworkError，不用轉型。
            //          把下面改成 switch error，處理三個 case（rateLimited
            // 要顯示「請 \(seconds) 秒後再試」），而且不要寫 default
            let text: String
            switch error as? NetworkError {
            case .offline?: text = "沒有網路"
            case .badStatus(let code)?: text = "伺服器錯誤 \(code)"
            default: text = "發生未知錯誤"
            }
            log("使用者看到：\(text)")
        }
    }
}

// MARK: - 演練設定
extension Swift10LanguageFeatures {
    static let l04 = Lab(
        id: "L04",
        title: "被限流時，使用者看到「未知錯誤」",
        question: "第 5 題：typed throws 解決什麼問題？",
        goal:
            "NetworkError 新增了 rateLimited，但錯誤訊息沒有跟著處理。先寫出掉進 default 的 Bug 版，再用 typed throws 讓編譯器幫你檢查有沒有處理完。",
        steps: [
            "打開 Practice/Swift/Swift10-LanguageFeatures/L04_TypedThrows.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：使用者看到什麼？",
            "寫 TODO ②，選「修正版」執行：使用者看到「請 30 秒後再試」。",
            "在修正版的 NetworkError 新增一個 case maintenance（先不要處理它），看編譯器怎麼說；再回到 Bug 版做同樣的事，看編譯器有沒有說話。",
            "想想取捨：公開的 API 簽名寫死錯誤型別，之後新增 case 會有什麼影響？",
        ],
        expectBug: "使用者看到「發生未知錯誤」：rateLimited 掉進 default，沒有人處理。",
        expectFixed:
            "使用者看到「請 30 秒後再試」。新增 case 沒處理時，編譯器會報 switch must be exhaustive。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("使用者看到：發生未知錯誤")
                ? nil
                : "Bug 還沒重現：message(for:) 要用 error as? NetworkError 轉型，沒有處理 rateLimited，用 default 兜底"
        },
        checkFixed: { lines in
            lines.contains("使用者看到：請 30 秒後再試")
                ? nil
                : "使用者應該看到「請 30 秒後再試」：fetch 要寫 throws(NetworkError)，catch 裡的 switch 要處理 rateLimited（不用 default）"
        }
    )
}
