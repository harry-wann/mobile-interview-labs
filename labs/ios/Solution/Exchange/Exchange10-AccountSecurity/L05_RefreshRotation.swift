// L05：用了一陣子就被強制登出，要重新輸入密碼和驗證碼（換新 token 之後沒有存下新的 refresh token）
// 對應複習站：Exchange10AccountSecurity 第 5 題「怎麼認出新裝置？登入狀態（session）要怎麼管理？」
//
// 情境：登入狀態用「短效的 access token（每次呼叫 API 都帶著）」加上「可輪替的 refresh token（只用來換新的 access token）」。
//       伺服器每次換新時同時發新的 refresh token，舊的作廢（輪替，rotation）；如果已作廢的舊 refresh token 又被拿來用，
//       代表可能被偷了，伺服器把整組登入狀態作廢，所有裝置都要重新登入。
//       演練用一台假伺服器：登入 → access token 過期 → 呼叫 API 得到 401 → 用 refresh token 換新 → 存起來 → 再呼叫 API；
//       之後 access token 又過期一次，再重複一次。（這題不處理「同時很多請求一起換新」的競爭，只看換新成功之後「存什麼」。）
// Bug 版：換新成功後只更新 access token，refresh token 還是登入時拿到的那一個。第一次換新沒問題；
//        第二次拿已經被作廢的舊 refresh token 去換，伺服器判定「舊 token 被重複使用」，整組登入作廢，使用者被登出。
// 修正版：換新成功後，access token 和新的 refresh token 一起存起來。
import Foundation
import LabKit

private struct Tokens {
    let access: String
    let refresh: String
}

private struct Session {
    var access: String
    var refresh: String
}

private enum RefreshResult {
    case success(Tokens)
    case reuseDetected          // 已作廢的舊 refresh token 又被拿來用：整組登入作廢
    case invalid
}

/// 假伺服器：access token 會過期，refresh token 每次換新就輪替，舊的被重複使用就作廢整組登入
private final class AuthServer {
    private var sequence = 1
    private var currentRefresh = "refresh-1"
    private var validAccess = "access-1"
    private var usedRefresh: Set<String> = []
    private(set) var sessionRevoked = false

    func login() -> Tokens { Tokens(access: validAccess, refresh: currentRefresh) }

    /// access token 過期（時間到了）
    func expireAccess() { validAccess = "expired" }

    /// 呼叫 API：access token 有效才是 200
    func call(access: String) -> Bool { !sessionRevoked && access == validAccess }

    func refresh(_ token: String) -> RefreshResult {
        if sessionRevoked { return .reuseDetected }
        if token == currentRefresh {
            usedRefresh.insert(token)                         // 舊的作廢
            sequence += 1
            currentRefresh = "refresh-\(sequence)"
            validAccess = "access-\(sequence)"
            return .success(Tokens(access: validAccess, refresh: currentRefresh))
        }
        if usedRefresh.contains(token) {
            sessionRevoked = true                             // 重複使用已作廢的 token：整組登入作廢
            return .reuseDetected
        }
        return .invalid
    }
}

/// 演練流程（兩個版本共用）：登入後，access token 過期兩次，每次都換新再呼叫 API
private func scenario(_ log: LabLog, store: (inout Session, Tokens) -> Void) {
    let server = AuthServer()
    let first = server.login()
    var session = Session(access: first.access, refresh: first.refresh)
    log("登入：\(session.access)、\(session.refresh)")
    var succeeded = 0
    var forcedLogout = false

    for round in 1...2 {
        server.expireAccess()
        if server.call(access: session.access) { log("第 \(round) 輪：access token 還有效（不應該）"); continue }
        log("第 \(round) 輪：access token 過期，呼叫 API 得到 401")
        switch server.refresh(session.refresh) {
        case .success(let tokens):
            succeeded += 1
            store(&session, tokens)
            log("第 \(round) 次換新成功，呼叫 API：\(server.call(access: session.access) ? "成功" : "失敗（401）")")
        case .reuseDetected:
            forcedLogout = true
            log("第 \(round) 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢")
        case .invalid:
            log("第 \(round) 次換新：refresh token 無效")
        }
        if forcedLogout { break }
    }
    log("共換新 2 輪，成功 \(succeeded) 次；使用者被強制登出：\(forcedLogout ? "是" : "否")")
}

// MARK: - Bug 版
private enum L05Bug {
    fileprivate static func store(_ session: inout Session, _ tokens: Tokens) {
        session.access = tokens.access            // 只更新 access token；refresh token 還是舊的那一個
    }

    static func run(_ log: LabLog) async {
        scenario(log, store: store(_:_:))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    fileprivate static func store(_ session: inout Session, _ tokens: Tokens) {
        session.access = tokens.access
        session.refresh = tokens.refresh          // 輪替：新的 refresh token 一起存起來，舊的已經作廢
    }

    static func run(_ log: LabLog) async {
        scenario(log, store: store(_:_:))
    }
}

// MARK: - 演練設定
extension Exchange10AccountSecurity {
    static let l05 = Lab(
        id: "L05",
        title: "用了一陣子就被強制登出",
        question: "第 5 題：怎麼認出新裝置？登入狀態（session）要怎麼管理？",
        goal: "refresh token 每次換新都會輪替：伺服器發新的、舊的作廢。換新成功後，新的 access token 和新的 refresh token 都要存起來；繼續用舊的 refresh token，伺服器會當成被偷。",
        steps: [
            "選「Bug 版」執行，看第 1 次換新後 API 成功，第 2 次換新發生什麼事。",
            "想一想：第 2 次拿去換的 refresh token 是哪一個？伺服器為什麼把它當成「被偷」？",
            "選「修正版」執行，兩輪換新都成功，沒有被強制登出。",
            "進階：這種錯很容易只在「過了一段時間」才出現，開發時 access token 沒過期兩次，測試就看不到；寫單元測試時用假伺服器，連續換新三次以上。",
        ],
        expectBug: "「第 1 次換新成功，呼叫 API：成功」，「第 2 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢」，「共換新 2 輪，成功 1 次；使用者被強制登出：是」。",
        expectFixed: "兩次換新都成功，每次換新後呼叫 API 都成功，「共換新 2 輪，成功 2 次；使用者被強制登出：否」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("共換新 2 輪") }) else { return "演練沒有跑完：要用 scenario(log, store:) 執行" }
            if !lines.contains("第 1 次換新成功，呼叫 API：成功") { return "Bug 版也要把新的 access token 存起來，第 1 次換新之後呼叫 API 才會成功" }
            if !lines.contains("第 2 次換新：伺服器判定舊的 refresh token 被重複使用，整組登入作廢") {
                return "Bug 還沒重現：refresh token 不更新，第 2 次會拿已經作廢的舊 refresh token 去換"
            }
            return lines.contains("共換新 2 輪，成功 1 次；使用者被強制登出：是") ? nil : "使用者應該被強制登出"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("共換新 2 輪") }) else { return "演練沒有跑完：要用 scenario(log, store:) 執行" }
            if !lines.contains("第 1 次換新成功，呼叫 API：成功") { return "第 1 次換新之後，呼叫 API 要成功（要把新的 access token 存起來）" }
            if !lines.contains("第 2 次換新成功，呼叫 API：成功") { return "第 2 次換新要成功：要把新的 refresh token 一起存起來，舊的已經被作廢" }
            return lines.contains("共換新 2 輪，成功 2 次；使用者被強制登出：否") ? nil : "不應該被強制登出"
        }
    )
}
