// L04：被限流時，使用者看到「發生未知錯誤」（typed throws 與窮舉的 switch）
// 對應複習站：Swift10LanguageFeatures 第 5 題「typed throws 解決什麼問題？」
//
// 情境：網路層的錯誤 NetworkError 一開始只有 offline、badStatus。後來伺服器開始限流，有人新增了 rateLimited(retryAfter:)，
//      但顯示錯誤訊息的地方沒有人記得改。
// Bug 版：fetch 只寫 throws，丟出來的是 any Error，錯誤訊息那邊要自己轉型，最後用 default 兜底。新增的 case 沒有人處理，掉進 default，
//        使用者看到「發生未知錯誤」，而不是「請 30 秒後再試」。
// 修正版：fetch 寫 throws(NetworkError)。catch 拿到的 error 就是 NetworkError，switch 要窮舉、不用寫 default。
//        新增 case 又沒處理的話，編譯器會直接報錯：switch must be exhaustive。
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
    static func fetch() throws -> Int {                     // 只知道「會丟錯誤」，不知道是哪一種
        throw NetworkError.rateLimited(retryAfter: 30)
    }

    static func message(for error: Error) -> String {
        switch error as? NetworkError {                     // 要自己轉型
        case .offline?: return "沒有網路"
        case .badStatus(let code)?: return "伺服器錯誤 \(code)"
        default: return "發生未知錯誤"                         // 新增的 rateLimited 掉進這裡
        }
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
    static func fetch() throws(NetworkError) -> Int {       // 簽名寫明：只會丟 NetworkError
        throw .rateLimited(retryAfter: 30)
    }

    static func run(_ log: LabLog) async {
        do {
            _ = try fetch()
        } catch {                                           // error 的型別是 NetworkError，不用轉型
            let text: String
            switch error {                                  // 沒有 default：要窮舉每一個 case
            case .offline: text = "沒有網路"
            case .badStatus(let code): text = "伺服器錯誤 \(code)"
            case .rateLimited(let seconds): text = "請 \(seconds) 秒後再試"
            // 如果 NetworkError 又新增一個 case（例如 maintenance），這裡沒處理的話，編譯器會報錯：
            //   error: switch must be exhaustive
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
        goal: "NetworkError 新增了 rateLimited，但錯誤訊息沒有跟著處理。先寫出掉進 default 的 Bug 版，再用 typed throws 讓編譯器幫你檢查有沒有處理完。",
        steps: [
            "選「Bug 版」執行，看 log：使用者看到什麼？",
            "選「修正版」執行：使用者看到「請 30 秒後再試」。",
            "在修正版的 NetworkError 新增一個 case maintenance（先不要處理它），看編譯器怎麼說；再回到 Bug 版做同樣的事，看編譯器有沒有說話。",
            "想想取捨：公開的 API 簽名寫死錯誤型別，之後新增 case 會有什麼影響？",
        ],
        expectBug: "使用者看到「發生未知錯誤」：rateLimited 掉進 default，沒有人處理。",
        expectFixed: "使用者看到「請 30 秒後再試」。新增 case 沒處理時，編譯器會報 switch must be exhaustive。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("使用者看到：發生未知錯誤") ? nil : "Bug 還沒重現：message(for:) 要用 error as? NetworkError 轉型，沒有處理 rateLimited，用 default 兜底"
        },
        checkFixed: { lines in
            lines.contains("使用者看到：請 30 秒後再試") ? nil : "使用者應該看到「請 30 秒後再試」：fetch 要寫 throws(NetworkError)，catch 裡的 switch 要處理 rateLimited（不用 default）"
        }
    )
}
