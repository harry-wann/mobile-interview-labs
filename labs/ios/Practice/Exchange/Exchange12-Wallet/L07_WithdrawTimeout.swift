// L07：出金送出後逾時，App 自動重送，帳戶被扣了兩筆（出金申請沒有冪等鍵，
// 逾時要先對帳）
// 對應複習站：Exchange12Wallet 第 5 題「出金申請的 API
// 長什麼樣子？送出逾時時為什麼不能直接重送？」
//
// 情境：這套交易所 API 的出金申請沒有冪等鍵（idempotency key：
// 讓同一個請求送多次，結果和送一次一樣的識別碼）。
//       送出後逾時，只代表「沒收到回應」，伺服器可能已經建立了出金。
// 正確的做法是不重送，先用出入金記錄（type=withdraw）查詢，
//       比對地址、數量、tag 是否已經有這一筆：有，就追蹤那一筆；找不到，
// 也不代表一定沒成功（記錄可能有延遲），要明確告訴使用者「結果不明」，
// 由使用者決定。
//       演練用一台假伺服器（記憶體裡的出金記錄），兩個情境：
//       ①請求送到了，伺服器已經建立出金，但回應遺失（App 看到逾時）
// ②請求沒有送到伺服器（App 同樣看到逾時）。
//       App 送出時逾時，交給 handleTimeout 決定怎麼辦。（地址、
// 數量是演練用的假資料。）
//
// 你要寫：
//   ① Bug 版（L07Bug.handleTimeout）：呼叫 server.create(request) 再送一次，
// 成功就回傳 sent(id)，再逾時就回傳 unknown。
//   ② 修正版（L07Fixed.handleTimeout）：在 server.records() 裡找地址、數量、
// tag 都和 request 相同的一筆：找到回傳 found(id)，找不到回傳 unknown。
// 不要重送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Exchange/Exchange12-Wallet/L07_WithdrawTimeout.swift
import Foundation
import LabKit

private struct WithdrawRequest: Equatable {
    let address: String
    let amount: String
    let tag: String?
}

private struct WithdrawRecord {
    let id: Int
    let request: WithdrawRequest
}

private enum SendError: Error { case timeout }

/// 假伺服器：沒有冪等鍵，送一次就建立一筆。可以設定「下一次的回應遺失」
/// 或「下一次的請求遺失」
private final class FakeServer {
    private var all: [WithdrawRecord] = []
    // 伺服器收到並建立了，但回應沒回到 App
    var loseNextResponse = false
    // 請求沒有送到伺服器
    var loseNextRequest = false

    var count: Int { all.count }

    func create(_ request: WithdrawRequest) throws -> Int {
        if loseNextRequest {
            loseNextRequest = false
            throw SendError.timeout
        }
        let id = all.count + 1
        all.append(WithdrawRecord(id: id, request: request))
        if loseNextResponse {
            loseNextResponse = false
            throw SendError.timeout
        }
        return id
    }

    /// 出入金記錄（type=withdraw）
    func records() -> [WithdrawRecord] { all }
}

private enum Outcome: Equatable {
    case sent(Int)  // 送出成功，出金 id
    // 逾時，但在記錄裡找到了這一筆
    case found(Int)
    // 逾時，找不到：結果不明
    case unknown
}

private func text(_ o: Outcome) -> String {
    switch o {
    case .sent(let id): return "已送出，出金 #\(id)"
    case .found(let id): return "逾時，但記錄裡已有這一筆，追蹤出金 #\(id)"
    case .unknown: return "結果不明，請到出入金記錄確認後再決定要不要重送"
    }
}

private let request = WithdrawRequest(
    address: "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", amount: "0.5", tag: nil)

/// 送出：成功回傳 .sent；逾時交給 handleTimeout
private func send(
    _ server: FakeServer,
    handleTimeout: (FakeServer, WithdrawRequest) -> Outcome
) -> Outcome {
    do {
        return .sent(try server.create(request))
    } catch {
        return handleTimeout(server, request)
    }
}

/// 演練流程（兩個版本共用）：兩個情境，每個情境各自一台全新的伺服器
private func scenario(
    _ log: LabLog, handleTimeout: (FakeServer, WithdrawRequest) -> Outcome
) {
    var wrong = 0

    let a = FakeServer()
    a.loseNextResponse = true
    let outcomeA = send(a, handleTimeout: handleTimeout)
    let okA = a.count == 1 && outcomeA == .found(1)
    if !okA { wrong += 1 }
    log(
        "情境 1（伺服器已建立，回應遺失）：伺服器上有 \(a.count) 筆出金；App：\(text(outcomeA)) \(okA ? "✓" : "✗（應該只有 1 筆，並追蹤那一筆）")"
    )

    let b = FakeServer()
    b.loseNextRequest = true
    let outcomeB = send(b, handleTimeout: handleTimeout)
    let okB = b.count == 0 && outcomeB == .unknown
    if !okB { wrong += 1 }
    log(
        "情境 2（請求沒有送到）：伺服器上有 \(b.count) 筆出金；App：\(text(outcomeB)) \(okB ? "✓" : "✗（使用者還沒確認，不應該自動送出）")"
    )
    log("2 個情境，處理錯的有 \(wrong) 個")
}

// MARK: - Bug 版
private enum L07Bug {
    fileprivate static func handleTimeout(
        _ server: FakeServer, _ request: WithdrawRequest
    ) -> Outcome {
        // TODO ①：用 try? server.create(request) 再送一次：成功就回傳
        // sent(id)；又逾時（nil）就回傳 unknown
        return .unknown
    }

    static func run(_ log: LabLog) async {
        scenario(log, handleTimeout: handleTimeout(_:_:))
    }
}

// MARK: - 修正版
private enum L07Fixed {
    fileprivate static func handleTimeout(
        _ server: FakeServer, _ request: WithdrawRequest
    ) -> Outcome {
        // TODO ②：不要重送。在 server.records() 裡找 request 和傳進來的
        // request 完全相同的一筆（.last(where:)）：
        //        找到回傳 .found(它的 id)；找不到回傳 .unknown
        return .unknown
    }

    static func run(_ log: LabLog) async {
        scenario(log, handleTimeout: handleTimeout(_:_:))
    }
}

// MARK: - 演練設定
private func wrongCount(_ lines: [String]) -> Int? {
    guard let line = lines.first(where: { $0.hasPrefix("2 個情境") }) else {
        return nil
    }
    return line.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
        .last
}

extension Exchange12Wallet {
    static let l07 = Lab(
        id: "L07",
        title: "出金逾時後自動重送，帳戶被扣了兩次",
        question: "第 5 題：出金申請的 API 長什麼樣子？送出逾時時為什麼不能直接重送？",
        goal:
            "出金申請沒有冪等鍵，逾時只代表沒收到回應，伺服器可能已經建立。不能重送，要先用出入金記錄對帳：找到就追蹤，找不到就告訴使用者結果不明。",
        steps: [
            "打開 Practice/Exchange/Exchange12-Wallet/L07_WithdrawTimeout.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看兩個情境結束時伺服器上各有幾筆出金。",
            "想一想：App 看到逾時的時候，能不能分辨「伺服器已經建立」和「請求根本沒送到」？",
            "寫 TODO ②（修正版），選「修正版」執行，情境 1 只有 1 筆並追蹤它，情境 2 沒有自動送出、顯示「結果不明」。",
            "進階：送出時要鎖住按鈕、顯示「處理中」；下單 API 有 client-order-id 可以冪等重送，出金申請的公開文件沒有，這是兩者最大的差別。",
        ],
        expectBug:
            "「情境 1（伺服器已建立，回應遺失）：伺服器上有 2 筆出金」（同一筆出了兩次 ✗），「情境 2（請求沒有送到）：伺服器上有 1 筆出金」（使用者沒確認就自動送出 ✗）：「2 個情境，處理錯的有 2 個」。",
        expectFixed:
            "情境 1 只有 1 筆，App 追蹤出金 #1；情境 2 伺服器上 0 筆，顯示「結果不明」：「2 個情境，處理錯的有 0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, handleTimeout:) 執行"
            }
            if !lines.contains(where: {
                $0.hasPrefix("情境 1") && $0.contains("伺服器上有 2 筆出金")
            }) {
                return "Bug 還沒重現：逾時就重送，情境 1 的伺服器上會有 2 筆一模一樣的出金"
            }
            return n == 2 ? nil : "Bug 版兩個情境都應該處理錯（目前錯 \(n) 個）"
        },
        checkFixed: { lines in
            guard let n = wrongCount(lines) else {
                return "演練沒有跑完：要用 scenario(log, handleTimeout:) 執行"
            }
            return n == 0
                ? nil : "還有 \(n) 個情境處理錯：逾時不重送；在出入金記錄裡找到相同的一筆就追蹤它，找不到回傳 .unknown"
        }
    )
}
