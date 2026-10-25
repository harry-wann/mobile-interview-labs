// L02：餘額不足也重試 6 次，伺服器忙碌時又馬上重試（重試沒有分錯誤種類，
// 也沒有退避）
// 對應複習站：Coding04Async 第 2 題「API 失敗時要怎麼重試？」
//
// 情境：呼叫 API 的 retry 函式，最多試 6 次，第一次失敗後等 100 毫秒，
// 之後每次加倍，最多等 1000 毫秒（sleep 由外面傳進來，
//       演練的假 sleep 只記錄「被要求等多久」，不會真的等）。三種情境：
//       A 前 3 次逾時、第 4 次成功；B 餘額不足；C 伺服器一直回 503。
//
// 你要寫：
//   ① Bug 版（L02Bug.retry）：for 迴圈最多試 maxAttempts 次，成功就回傳；
// 任何錯誤都記進 lastError 然後馬上再試（不呼叫 sleep）；全部失敗就丟出
// lastError。
//   ② 修正版（L02Fixed.retry）：delay 從 baseDelayMs 開始。失敗時：
// 不是暫時性的錯誤（C04ApiError.isTransient 是 false），或已經是最後一次，
// 就把錯誤丟出去；
//      否則 await sleep(delay)，delay 加倍但不超過 maxDelayMs，再試。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Coding/Coding04-Async/L02_Retry.swift
import LabKit

/// 重試的函式簽名（兩個版本共用）：最多試 maxAttempts 次；sleep 是等待函式；
/// operation 是要做的事
private typealias Retry = (
    _ maxAttempts: Int, _ baseDelayMs: Int, _ maxDelayMs: Int,
    _ sleep: (Int) async -> Void, _ operation: () async throws -> String
) async throws -> String

/// 演練流程（兩個版本共用）：三種情境各跑一次，記錄呼叫幾次、等了多久、結果
private func scenario(_ log: LabLog, retry: Retry) async {
    // 每個情境是「依序發生的失敗」，用完之後就成功
    let cases: [(String, [C04ApiError])] = [
        ("A（前 3 次逾時）", [.timeout, .timeout, .timeout]),
        ("B（餘額不足）", Array(repeating: .insufficientBalance, count: 10)),
        ("C（伺服器一直 503）", Array(repeating: .serverBusy, count: 10)),
    ]
    for (title, failures) in cases {
        let calls = C04Calls()
        let sleepLog = C04SleepLog()
        var outcome = "成功"
        do {
            _ = try await retry(
                6, 100, 1000, { await sleepLog.sleep($0) },
                {
                    let n = calls.next()
                    if n <= failures.count { throw failures[n - 1] }
                    return "ok"
                })
        } catch {
            outcome = "失敗（\((error as? C04ApiError)?.text ?? "其他錯誤")）"
        }
        log(
            "情境 \(title)：呼叫 \(calls.count) 次，等待 \(sleepLog.waits) 毫秒，\(outcome)"
        )
    }
}

// MARK: - Bug 版
private enum L02Bug {
    static func retry(
        maxAttempts: Int, baseDelayMs: Int, maxDelayMs: Int,
        sleep: (Int) async -> Void, operation: () async throws -> String
    ) async throws -> String {
        var lastError: Error = C04ApiError.timeout
        // TODO ①：for _ in 0..<maxAttempts：do { return try await operation()
        // } catch { lastError = error }
        //         什麼錯都再試一次，而且馬上試（不呼叫 sleep）。全部失敗就丟出
        // lastError
        throw lastError
    }

    static func run(_ log: LabLog) async {
        await scenario(
            log,
            retry: {
                try await retry(
                    maxAttempts: $0, baseDelayMs: $1, maxDelayMs: $2, sleep: $3,
                    operation: $4)
            })
    }
}

// MARK: - 修正版
private enum L02Fixed {
    static func retry(
        maxAttempts: Int, baseDelayMs: Int, maxDelayMs: Int,
        sleep: (Int) async -> Void, operation: () async throws -> String
    ) async throws -> String {
        var delay = baseDelayMs
        // TODO ②-1：for attempt in 1...maxAttempts：do { return try await
        // operation() } catch { … }
        // TODO ②-2：catch 裡：error 不是 C04ApiError、或 isTransient 是 false、
        // 或 attempt == maxAttempts：直接 throw error（guard … else { throw
        // error }）
        // TODO ②-3：否則 await sleep(delay)，再 delay = min(delay * 2,
        // maxDelayMs)
        throw C04ApiError.timeout
    }

    static func run(_ log: LabLog) async {
        await scenario(
            log,
            retry: {
                try await retry(
                    maxAttempts: $0, baseDelayMs: $1, maxDelayMs: $2, sleep: $3,
                    operation: $4)
            })
    }
}

// MARK: - 演練設定
extension Coding04Async {
    static let l02 = Lab(
        id: "L02",
        title: "餘額不足也重試 6 次，而且馬上重試",
        question: "第 2 題：API 失敗時要怎麼重試？",
        goal:
            "只重試暫時性的錯誤；重試之間等待時間加倍（指數退避）並且有上限；重試次數也要有上限。先寫出什麼錯都馬上重試的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Coding/Coding04-Async/L02_Retry.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看情境 B（餘額不足）呼叫了幾次，以及三個情境「等待」的欄位。",
            "寫 TODO ②（修正版），選「練習」「修正版」執行，確認情境 A 等待 [100, 200, 400]，情境 B 只呼叫 1 次，情境 C 的等待加倍到 1000 就不再變大。",
            "想一想：下單逾時可以這樣直接重試嗎？（要帶同一個 client order id，見 Platform05 L05）",
        ],
        expectBug: "A 呼叫 4 次、B 呼叫 6 次、C 呼叫 6 次，三個情境的等待都是 []（一毫秒也沒等）。",
        expectFixed:
            "A 等待 [100, 200, 400]；B 呼叫 1 次就失敗；C 呼叫 6 次，等待 [100, 200, 400, 800, 1000]（封頂）。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if lines.count < 3 { return "演練沒有跑完：要用 scenario(log, retry:) 執行" }
            if !lines.contains("情境 A（前 3 次逾時）：呼叫 4 次，等待 [] 毫秒，成功") {
                return "retry 還沒寫：失敗就再呼叫 operation，直到成功或試滿 maxAttempts 次"
            }
            return lines.contains("情境 B（餘額不足）：呼叫 6 次，等待 [] 毫秒，失敗（餘額不足）")
                ? nil : "Bug 還沒重現：不分錯誤種類全部重試，餘額不足也要試滿 6 次，而且不呼叫 sleep"
        },
        checkFixed: { lines in
            if lines.count < 3 { return "演練沒有跑完：要用 scenario(log, retry:) 執行" }
            if !lines.contains("情境 A（前 3 次逾時）：呼叫 4 次，等待 [100, 200, 400] 毫秒，成功")
            {
                return "暫時性的錯誤要等一下再試：await sleep(delay)，之後 delay 加倍"
            }
            if !lines.contains("情境 B（餘額不足）：呼叫 1 次，等待 [] 毫秒，失敗（餘額不足）") {
                return "餘額不足不是暫時性的錯誤（isTransient 是 false），第一次失敗就要丟出去，不要重試"
            }
            return lines.contains(
                "情境 C（伺服器一直 503）：呼叫 6 次，等待 [100, 200, 400, 800, 1000] 毫秒，失敗（伺服器忙碌 503）"
            )
                ? nil
                : "等待時間每次加倍但最多 maxDelayMs（1000）；試滿 6 次後把最後一個錯誤丟出去，最後一次失敗不用再等"
        }
    )
}
