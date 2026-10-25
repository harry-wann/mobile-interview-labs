// L05：網路逾時重試，同一筆委託送出了兩次（~Copyable 讓下單 token 只能用一次）
// 對應複習站：Swift10LanguageFeatures 第 6 題「~Copyable
// 是什麼？怎麼用它保證「只能用一次」？」
//
// 情境：下單前，先用私鑰簽出一個一次性的 SignedOrderToken，送出時用它。
// 送出之後網路逾時，程式碼「重試」，又用了同一個 token。
// Bug 版：SignedOrderToken 是普通的 struct，可以隨意複製、重複使用。第二次
// submit 一樣會送出去，伺服器收到兩筆同樣的委託。
// 修正版：SignedOrderToken 改成 ~Copyable，submit 改成 consuming。送出之後
// token 就用完了，第二次使用會直接編譯失敗：
//        error: 'token' consumed more than once
import Foundation
import LabKit

/// 送單的閘道（兩個版本共用）：記下每一次送出的委託編號
private final class OrderGateway {
    private(set) var sent: [Int] = []
    func send(_ id: Int) { sent.append(id) }
}

// MARK: - Bug 版
private enum L05Bug {
    struct SignedOrderToken {
        let id: Int
        let gateway: OrderGateway

        // 普通的 struct：submit 之後 token 還在
        func submit() { gateway.send(id) }
    }

    static func run(_ log: LabLog) async {
        let gateway = OrderGateway()
        let token = SignedOrderToken(id: 7, gateway: gateway)
        token.submit()
        log("第一次送出，伺服器收到：\(gateway.sent)")
        // 網路逾時，程式碼決定重試：用同一個 token 再送一次
        token.submit()
        log("重試之後，伺服器收到：\(gateway.sent)")
        log("委託 7 被送出了 \(gateway.sent.filter { $0 == 7 }.count) 次")
    }
}

// MARK: - 修正版
private enum L05Fixed {
    struct SignedOrderToken: ~Copyable {
        let id: Int
        let gateway: OrderGateway
        let log: LabLog

        // consuming：呼叫之後，token 就用完了
        consuming func submit() { gateway.send(id) }
        // noncopyable 型別可以有 deinit：用完的那一刻被呼叫
        deinit { log("token \(id) 已作廢（deinit）") }
    }

    static func run(_ log: LabLog) async {
        let gateway = OrderGateway()
        let token = SignedOrderToken(id: 7, gateway: gateway, log: log)
        token.submit()
        log("第一次送出，伺服器收到：\(gateway.sent)")
        // 網路逾時，想重試：
        // token.submit()      // 編譯錯誤：'token' consumed more than once
        // 要重試，只能重新簽一個新的 token（新的 token 才是新的一筆委託）
        log("委託 7 被送出了 \(gateway.sent.filter { $0 == 7 }.count) 次")
    }
}

// MARK: - 演練設定
extension Swift10LanguageFeatures {
    static let l05 = Lab(
        id: "L05",
        title: "網路逾時重試，同一筆委託送了兩次",
        question: "第 6 題：~Copyable 是什麼？怎麼用它保證「只能用一次」？",
        goal:
            "一次性的簽名下單 token 只能送出一次。先寫出重試時重複送單的 Bug 版，再用 ~Copyable 與 consuming 讓編譯器擋住第二次使用。",
        steps: [
            "選「Bug 版」執行，看 log：委託 7 被送出幾次？",
            "選「修正版」執行：只送出 1 次，而且 token 在送出的那一刻就被 deinit。",
            "在修正版的 run 裡把 token.submit() 那一行的註解打開，看編譯器的錯誤訊息。",
            "再把第一次的 submit 改成先指派給另一個變數（let t2 = token），看之後再用 token 會得到什麼錯誤。",
        ],
        expectBug: "委託 7 被送出了 2 次：普通的 struct 可以重複使用同一個 token。",
        expectFixed:
            "委託 7 只送出 1 次；token 在 submit 結束時印出「token 7 已作廢（deinit）」；第二次 submit 無法通過編譯：'token' consumed more than once。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.contains("委託 7 被送出了 2 次")
                ? nil
                : "Bug 還沒重現：同一個 token 要能 submit 兩次（普通的 struct 可以重複使用），讓伺服器收到 2 次"
        },
        checkFixed: { lines in
            if !lines.contains("委託 7 被送出了 1 次") { return "委託 7 應該只送出 1 次" }
            if !lines.contains("token 7 已作廢（deinit）") {
                return
                    "token 應該是 ~Copyable，submit 是 consuming，用完的那一刻會呼叫 deinit（印出「token 7 已作廢（deinit）」）"
            }
            return nil
        }
    )
}
