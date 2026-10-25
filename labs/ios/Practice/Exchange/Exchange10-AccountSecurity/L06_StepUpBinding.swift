// L06：使用者核准的是「出金 0.1 BTC 到自己的地址」，
// 簽名卻能拿去出金到別的地址（step-up 的簽名沒有綁定這一筆操作）
// 對應複習站：Exchange10AccountSecurity 第 6 題「出金這類高風險操作，
// 為什麼要再驗證一次？」
//        （Platform06 的演練 5 練過簽名的「重放」：同一份簽名原封不動重送。
// 這題是另一件事：簽名內容沒有綁定金額與地址，被竄改後仍然有效。）
//
// 情境：出金前要 step-up：伺服器送一個一次性的 challenge（nonce），
// 使用者用生物辨識核准，裝置用金鑰對「簽名內容」簽名，
//       App 把出金請求（nonce、金額、地址、簽名）送給伺服器，
// 伺服器重新組出簽名內容、驗證簽名、檢查 nonce 沒用過。
//       這裡的「簽名內容」怎麼組，是客戶端和伺服器共同的約定，演練用
// signedMessage 表示。
//       （金鑰用 HMAC 代替；真實的 App 是 Secure Enclave／Keystore
// 裡的私鑰簽章，伺服器用公鑰驗證。）
//       四個情境，伺服器是同一台（記得用過的 nonce）：
//       ① 使用者核准 0.1 BTC → 地址 A，請求原樣送出 → 應該受理
//       ② 手機被植入惡意程式：使用者核准的是 0.1 BTC → 地址 A，
// 惡意程式把地址換成攻擊者的地址 B 再送出 → 應該拒絕
//       ③ 同樣情況，惡意程式把金額 0.1 改成 5 → 應該拒絕
//       ④ 情境 ① 的請求被原封不動再送一次（重放）→ 應該拒絕（nonce 已經用過）
//
// 你要寫：
//   ① Bug 版（L06Bug.signedMessage）：只回傳 nonce。
//   ② 修正版（L06Fixed.signedMessage）：回傳 nonce、金額、地址，用換行（\n）
// 接在一起。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange10-AccountSecurity/L06_StepUpBinding.swift
import Foundation
import LabKit

// 演練用的假金鑰
private let deviceKey = "device-key-test-0001"
// BIP173 的測試地址：使用者自己的地址
private let addressA = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
// 另一個公開的測試地址：代表攻擊者的地址
private let addressB = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

private struct Request {
    let nonce: String
    let amount: String
    let address: String
    let signature: String
}

/// 假伺服器：記得發出去、也用過的 nonce；用 signedMessage 組出簽名內容，
/// 驗證簽名
private final class Server {
    private var used: Set<String> = []
    private let signedMessage:
        (_ nonce: String, _ amount: String, _ address: String) -> String
    init(signedMessage: @escaping (String, String, String) -> String) {
        self.signedMessage = signedMessage
    }

    /// 回傳 nil 代表受理，否則是拒絕的原因
    func withdraw(_ r: Request) -> String? {
        if used.contains(r.nonce) { return "nonce 已經用過" }
        let expected = E10.hmacHex(
            key: deviceKey, message: signedMessage(r.nonce, r.amount, r.address)
        )
        guard expected == r.signature else { return "簽名不符" }
        used.insert(r.nonce)
        return nil
    }
}

/// 演練流程（兩個版本共用）：四個情境
private func scenario(
    _ log: LabLog, signedMessage: @escaping (String, String, String) -> String
) {
    let server = Server(signedMessage: signedMessage)
    var wrong = 0
    var wrongAccepted = 0

    /// 使用者核准（amount → address）之後，惡意程式可能把送出的欄位換掉
    func attempt(
        _ name: String, nonce: String,
        approved: (amount: String, address: String),
        sent: (amount: String, address: String), expectAccepted: Bool
    ) -> Request {
        let signature = E10.hmacHex(
            key: deviceKey,
            message: signedMessage(nonce, approved.amount, approved.address))
        let request = Request(
            nonce: nonce, amount: sent.amount, address: sent.address,
            signature: signature)
        let rejectedReason = server.withdraw(request)
        let accepted = rejectedReason == nil
        if accepted == expectAccepted {
            log("\(name)：\(accepted ? "受理" : "拒絕（\(rejectedReason!)）") ✓")
        } else {
            wrong += 1
            if accepted { wrongAccepted += 1 }
            log(
                "\(name)：\(accepted ? "受理" : "拒絕（\(rejectedReason!)）") ✗（應該\(expectAccepted ? "受理" : "拒絕")）"
            )
        }
        return request
    }

    log(
        "簽名內容（核准當下，情境 ①）：\(signedMessage("ch-0001", "0.1", addressA).replacingOccurrences(of: "\n", with: " / "))"
    )
    let first = attempt(
        "情境 ① 原樣送出", nonce: "ch-0001", approved: ("0.1", addressA),
        sent: ("0.1", addressA), expectAccepted: true)
    _ = attempt(
        "情境 ② 地址被換成 B", nonce: "ch-0002", approved: ("0.1", addressA),
        sent: ("0.1", addressB), expectAccepted: false)
    _ = attempt(
        "情境 ③ 金額被改成 5", nonce: "ch-0003", approved: ("0.1", addressA),
        sent: ("5", addressA), expectAccepted: false)
    let replayReason = server.withdraw(first)
    if replayReason != nil {
        log("情境 ④ 重放情境 ① 的請求：拒絕（\(replayReason!)） ✓")
    } else {
        wrong += 1
        wrongAccepted += 1
        log("情境 ④ 重放情境 ① 的請求：受理 ✗（應該拒絕）")
    }
    log("4 個情境，判斷錯的有 \(wrong) 個，其中不該受理卻受理的有 \(wrongAccepted) 個")
}

// MARK: - Bug 版
private enum L06Bug {
    fileprivate static func signedMessage(
        nonce: String, amount: String, address: String
    ) -> String {
        // TODO ①：只回傳 nonce
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log) { signedMessage(nonce: $0, amount: $1, address: $2) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    fileprivate static func signedMessage(
        nonce: String, amount: String, address: String
    ) -> String {
        // TODO ②：回傳 nonce、amount、address 三個字串，用換行（"\n"）接在一起
        return ""
    }

    static func run(_ log: LabLog) async {
        scenario(log) { signedMessage(nonce: $0, amount: $1, address: $2) }
    }
}

// MARK: - 演練設定
private func counts(_ lines: [String]) -> (wrong: Int, accepted: Int)? {
    guard let line = lines.first(where: { $0.hasPrefix("4 個情境") }) else {
        return nil
    }
    let numbers = line.split(whereSeparator: { !$0.isNumber }).compactMap {
        Int($0)
    }
    guard numbers.count >= 3 else { return nil }
    return (numbers[1], numbers[2])
}

extension Exchange10AccountSecurity {
    static let l06 = Lab(
        id: "L06",
        title: "核准的是一筆出金，簽名卻能換地址使用",
        question: "第 6 題：出金這類高風險操作，為什麼要再驗證一次？（step-up）",
        goal:
            "step-up 的簽名內容要綁定這一筆操作（nonce、金額、地址），伺服器用實際收到的欄位重新組出簽名內容驗證。只簽 nonce 的話，攻擊者拿到簽名就能換收款地址。",
        steps: [
            "打開 Practice/Exchange/Exchange10-AccountSecurity/L06_StepUpBinding.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看「簽名內容」裡有什麼；情境 ② ③ 被竄改的請求結果是什麼。",
            "想一想：使用者在畫面上看到並核准的是哪些資訊？簽名有沒有涵蓋它們？",
            "寫 TODO ②（修正版），選「修正版」執行，情境 ② ③ 都被拒絕（簽名不符），情境 ① 受理，情境 ④ 因為 nonce 用過而拒絕。",
            "進階：新增出金地址之後，可以加一段冷卻時間，期間限制出金額度；判斷一律在伺服器，客戶端的檢查只是讓流程順暢。",
        ],
        expectBug:
            "「簽名內容（核准當下，情境 ①）：ch-0001」，情境 ② 地址被換成 B：受理 ✗，情境 ③ 金額被改成 5：受理 ✗（錢轉到攻擊者的地址），情境 ④ 重放因為 nonce 用過而拒絕：「4 個情境，判斷錯的有 2 個，其中不該受理卻受理的有 2 個」。",
        expectFixed:
            "簽名內容是 ch-0001 / 0.1 / 地址 A；情境 ① 受理，② ③ 拒絕（簽名不符），④ 拒絕（nonce 已經用過），「4 個情境，判斷錯的有 0 個，其中不該受理卻受理的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let c = counts(lines) else {
                return
                    "演練沒有跑完：要用 scenario(log) { nonce, amount, address in … } 執行"
            }
            if !lines.contains("簽名內容（核准當下，情境 ①）：ch-0001") {
                return "Bug 版的簽名內容只有 nonce（ch-0001）：不含金額與地址"
            }
            if !lines.contains("情境 ① 原樣送出：受理 ✓") {
                return "Bug 版的簽名要能通過驗證：合法的請求要受理"
            }
            return c.accepted == 2 && c.wrong == 2
                ? nil
                : "Bug 還沒重現：簽名內容只有 nonce 時，地址被換掉（情境 ②）、金額被改掉（情境 ③）的請求都會被受理"
        },
        checkFixed: { lines in
            guard let c = counts(lines) else {
                return
                    "演練沒有跑完：要用 scenario(log) { nonce, amount, address in … } 執行"
            }
            if !lines.contains(
                "簽名內容（核准當下，情境 ①）：ch-0001 / 0.1 / bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
            ) {
                return "簽名內容要包含 nonce、金額、地址（用換行接在一起）"
            }
            return c.wrong == 0
                ? nil
                : "還有 \(c.wrong) 個情境判斷錯（其中 \(c.accepted) 個不該受理卻受理）：伺服器用實際收到的金額與地址組簽名內容，被改過就對不上"
        }
    )
}
