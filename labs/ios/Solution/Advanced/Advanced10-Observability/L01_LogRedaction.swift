// L01：送到遠端 log 服務的訊息裡，有登入 token、完整的錢包地址和餘額（log 要遮罩個資）
// 對應複習站：Advanced10Observability 第 1 題「可觀測性是什麼？log、指標、追蹤各回答什麼問題？」（隱私優先）
//
// 情境：下單時 App 會把一行 log 送到遠端 log 服務，方便追問題。log 裡有 requestId（要留著，才能串起 App 與伺服器的紀錄），
//       也被不小心寫進了 token、地址、餘額；另外還有兩種常見的洩漏：token 出現在網址的查詢參數裡、錯誤訊息裡印出整個 Authorization 標頭。
//       演練的 log 服務是假的（只是一個陣列），token 與地址都是編出來的。
// Bug 版：log 原封不動送出去。遠端 log 服務、任何有權限看 log 的人、log 被外洩時，都拿得到完整的 token（有效期內能直接登入）、地址與餘額。
// 修正版：送出前先遮罩：Bearer token、token／password 查詢參數、balance 開頭的欄位換成 ***，錢包地址只留頭尾幾碼；requestId 不是敏感資料，保留。
import Foundation
import LabKit

private let token = "eyJhbGciOiJIUzI1NiJ9.eyJ1aWQiOjQyfQ.c2lnbmF0dXJl"
private let address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

/** 演練流程（兩個版本共用）：redact 是送出前的處理，傳進去的是一行 log，回傳真正送出的內容 */
private func scenario(_ log: LabLog, redact: (String) -> String) {
    var sent: [String] = []                                 // 假的遠端 log 服務收到的內容
    func emit(_ line: String) { sent.append(redact(line)) }

    emit("order submit requestId=req-1a2b token=\(token) address=\(address) balanceJPY=1234567")
    emit("GET /api/assets?token=\(token)&limit=20 requestId=req-1a2c")
    emit("decode failed requestId=req-1a2d header Authorization: Bearer \(token)")

    for line in sent { log("遠端 log：\(line)") }
    log("含完整 token 的行：\(sent.filter { $0.contains(token) }.count)")
    log("含完整錢包地址的行：\(sent.filter { $0.contains(address) }.count)")
    log("含 requestId 的行：\(sent.filter { $0.contains("requestId=req-1a2") }.count)")
}

// MARK: - Bug 版
private enum L01Bug {
    static func redact(_ line: String) -> String {
        line                                                    // 原封不動送出
    }

    static func run(_ log: LabLog) async {
        scenario(log, redact: redact)
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// (規則, 取代成什麼)：依序套用
    private static let rules: [(pattern: String, template: String)] = [
        (#"Bearer\s+[A-Za-z0-9._-]+"#, "Bearer ***"),                           // Authorization 標頭
        (#"(token|password|secret)=[^&\s]+"#, "$1=***"),                        // 網址或欄位裡的 token、密碼
        (#"(balance\w*)=\S+"#, "$1=***"),                                       // 餘額
        (#"\b(bc1[a-z0-9]{3})[a-z0-9]+([a-z0-9]{4})\b"#, "$1…$2"),              // 錢包地址只留頭尾
    ]

    static func redact(_ line: String) -> String {
        rules.reduce(line) { text, rule in
            text.replacingOccurrences(of: rule.pattern, with: rule.template, options: .regularExpression)
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, redact: redact)
    }
}

// MARK: - 演練設定
extension Advanced10Observability {
    static let l01 = Lab(
        id: "L01",
        title: "遠端 log 裡有 token、地址和餘額",
        question: "第 1 題：可觀測性是什麼？log、指標、追蹤各回答什麼問題？（隱私優先）",
        goal: "送到遠端的 log 不能有 token、地址、餘額、個資；但 requestId 要留著，才能串起一次操作。",
        steps: [
            "選「Bug 版」執行，看送到遠端的每一行，以及最後三行的計數。",
            "想一想：token 在三行 log 裡用三種不同的樣子出現（欄位、網址查詢參數、Authorization 標頭），只遮第一種夠嗎？",
            "選「修正版」執行：token、地址、餘額都遮掉，requestId 還在。",
            "進階：更好的做法是一開始就不要把敏感值寫進 log（iOS 的 os.Logger 用 privacy: .private，Android 的 Timber 在 release 不種輸出的 Tree），遮罩是最後一道防線。",
        ],
        expectBug: "三行 log 都含完整 token（含完整 token 的行：3），地址也在（含完整錢包地址的行：1）。",
        expectFixed: "含完整 token 的行：0，含完整錢包地址的行：0，含 requestId 的行：3。地址變成 bc1qxy2…0wlh。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("含完整 token 的行") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("含完整 token 的行：3") { return "Bug 還沒重現：redact 要原封不動回傳，三行都應該含完整 token" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("含完整 token 的行") }) else { return "演練沒有跑完：要用 scenario 執行" }
            if !lines.contains("含完整 token 的行：0") { return "還有 token 沒遮掉：欄位、網址查詢參數、Authorization 標頭三種樣子都要處理" }
            if !lines.contains("含完整錢包地址的行：0") { return "錢包地址沒遮掉：只留頭尾幾碼" }
            if !lines.contains("含 requestId 的行：3") { return "requestId 不是敏感資料，要保留，不要全部遮掉" }
            if !lines.contains(where: { $0.contains("balanceJPY=***") }) { return "餘額（balanceJPY=…）沒遮掉" }
            return nil
        }
    )
}
