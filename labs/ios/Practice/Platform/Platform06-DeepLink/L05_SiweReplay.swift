// L05：簽章被偷走之後，攻擊者原封不動重送也能登入（簽名訊息要帶 nonce
// 與到期時間）
// 對應複習站：Platform06DeepLink 第 6 題「錢包 App 回跳（WalletConnect）
// 怎麼運作？」
//
// 情境：使用者用錢包簽一段訊息，證明自己擁有某個錢包地址，
// 伺服器驗證簽章後讓他登入。
//       簽章曾經外洩（網路紀錄、釣魚網站…），攻擊者拿到「同一份訊息和簽章」，
// 原封不動送給伺服器。
//       （真實的錢包簽章是橢圓曲線簽章，伺服器只需要錢包地址就能驗證；演練用
// HMAC 代替簽章，重點是「簽的訊息裡有什麼」。）
//       劇本：1. 使用者簽名登入；2. 攻擊者 1 分鐘後重放同一份簽章；3.
// 另一份簽好、但使用者還沒送出的訊息，10 分鐘後才被攻擊者送出。
//
// 你要寫：
//   ① Bug 版：challenge 每次回傳固定的 "Sign in to Example Exchange"；login
// 只用 P06Wallet.verify 驗簽章，對就回傳 "成功"，不對回傳 "拒絕：簽章不正確"。
//   ② 修正版：challenge 產生 SIWE 風格的訊息（網域、地址、Nonce、Issued At、
// Expiration Time = 簽發後 300 秒），並記下發出的 nonce；
//      login 依序檢查簽章、nonce 是不是發出過的、有沒有過期、nonce
// 有沒有用過（用過就拒絕，沒用過就記進 usedNonces）。
// 只改有 TODO 的地方。解答：
// Solution/Platform/Platform06-DeepLink/L05_SiweReplay.swift
import Foundation
import LabKit

/// 登入伺服器（兩個版本各自實作）：challenge 產生要簽的訊息，login
/// 驗證並回傳結果文字
private protocol LoginServer {
    func challenge(for address: String, now: Date) -> String
    func login(message: String, signature: String, now: Date) -> String
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, server: LoginServer) {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    let message = server.challenge(for: P06Wallet.address, now: now)
    let signature = P06Wallet.sign(message)
    log(
        "使用者簽名登入：\(server.login(message: message, signature: signature, now: now))"
    )
    log(
        "攻擊者 1 分鐘後重放同一份簽章：\(server.login(message: message, signature: signature, now: now.addingTimeInterval(60)))"
    )

    let unused = server.challenge(for: P06Wallet.address, now: now)
    let stolen = P06Wallet.sign(unused)
    log(
        "另一份簽好但沒送出的簽章，10 分鐘後被攻擊者送出：\(server.login(message: unused, signature: stolen, now: now.addingTimeInterval(600)))"
    )
}

// MARK: - Bug 版
private enum L05Bug {
    final class Server: LoginServer {
        func challenge(for address: String, now: Date) -> String {
            // TODO ①-1：每次都回傳同一段固定的文字："Sign in to Example
            // Exchange"
            ""
        }

        func login(message: String, signature: String, now: Date) -> String {
            // TODO ①-2：只驗簽章：P06Wallet.verify(message:signature:)
            // 對就回傳 "成功"，不對回傳 "拒絕：簽章不正確"
            "拒絕：還沒寫"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, server: Server())
    }
}

// MARK: - 修正版
private enum L05Fixed {
    final class Server: LoginServer {
        // 伺服器發出去的 nonce
        private var issuedNonces: Set<String> = []
        // 已經用過的 nonce
        private var usedNonces: Set<String> = []

        func challenge(for address: String, now: Date) -> String {
            // TODO ②-1：1. nonce = UUID().uuidString，存進 issuedNonces；
            // issuedAt = Int(now.timeIntervalSince1970)
            //          2. 回傳多行訊息，每一行依序是：
            //             "www.example.com wants you to sign in with your
            // wallet:"、地址、"Nonce: <nonce>"、
            //             "Issued At: <issuedAt>"、"Expiration Time: <issuedAt
            // + 300>"
            ""
        }

        func login(message: String, signature: String, now: Date) -> String {
            // TODO ②-2：依序檢查，不通過就回傳對應的文字（P06Siwe.
            // field("名稱", in: message) 可以取出欄位的值）：
            //          1. 簽章（P06Wallet.verify）不對 → "拒絕：簽章不正確"
            //          2. Nonce 不在 issuedNonces → "拒絕：nonce
            // 不是伺服器發的"
            //          3. 現在時間（Int(now.timeIntervalSince1970)）超過
            // Expiration Time → "拒絕：訊息已過期"
            //          4. nonce 已經在 usedNonces → "拒絕：nonce
            // 已經用過"（沒用過就記進去：usedNonces.insert）
            //          5. 都通過 → "成功"
            _ = usedNonces
            return "拒絕：還沒寫"
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, server: Server())
    }
}

// MARK: - 演練設定
extension Platform06DeepLink {
    static let l05 = Lab(
        id: "L05",
        title: "簽章被偷走，攻擊者原封不動重送也能登入",
        question: "第 6 題：錢包 App 回跳（WalletConnect）怎麼運作？",
        goal: "簽名的訊息要防重放：同一份簽章只能用一次，而且過了時限就不能用。伺服器發的訊息裡要放什麼？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看攻擊者重放簽章、以及 10 分鐘後才送出的結果，出現 ✓。",
            "想一想：伺服器怎麼分辨「使用者本人剛簽的」和「攻擊者拿舊的簽章來送」？簽的訊息每次一樣嗎？",
            "寫 TODO ②，選「修正版」執行，重放要被擋下，而且要說出被擋的原因。",
            "進階：簽章的驗證一定要在伺服器做，不能只在 App 裡驗；deep link 只負責切換 App，簽章資料不放在連結裡。",
        ],
        expectBug: "三行結果都是「成功」：包含「攻擊者 1 分鐘後重放同一份簽章：成功」與 10 分鐘後送出：成功。",
        expectFixed: "使用者簽名登入：成功；攻擊者重放：「拒絕：nonce 已經用過」；10 分鐘後送出：「拒絕：訊息已過期」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("使用者簽名登入：成功") {
                return "使用者簽名登入要成功：login 驗簽章對得上就回傳「成功」"
            }
            return lines.contains("攻擊者 1 分鐘後重放同一份簽章：成功")
                ? nil : "Bug 還沒重現：伺服器只驗簽章，不看 nonce、也不看時間，同一份簽章重放要成功"
        },
        checkFixed: { lines in
            if !lines.contains("使用者簽名登入：成功") { return "使用者簽名登入要成功" }
            if !lines.contains("攻擊者 1 分鐘後重放同一份簽章：拒絕：nonce 已經用過") {
                return "重放要被擋下：驗證通過後把 nonce 記進 usedNonces，第二次用同一個 nonce 就拒絕"
            }
            return lines.contains("另一份簽好但沒送出的簽章，10 分鐘後被攻擊者送出：拒絕：訊息已過期")
                ? nil
                : "超過 Expiration Time 的訊息要拒絕：challenge 把到期時間（簽發時間加 300 秒）放進訊息，login 比對現在時間"
        }
    )
}
