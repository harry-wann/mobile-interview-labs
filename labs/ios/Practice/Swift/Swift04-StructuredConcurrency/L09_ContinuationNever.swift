// L09：查詢價格的錯誤分支忘了 resume，呼叫端永遠等不到結果（continuation 從不 resume）
// 對應複習站：Swift04StructuredConcurrency 第 5 題「continuation 是什麼？把舊的 callback 接成 async 時有哪三種常見誤用？」
//
// 情境：舊的價格查詢 API 用 completion handler 回傳：成功給價格，失敗（例如交易對不存在）給錯誤。
//
// 你要寫：
//   ① Bug 版：fetchPrice 用 withCheckedThrowingContinuation 包 api.fetch：只在成功（.success）時 resume(returning:)，失敗的分支不 resume。
//   ② 修正版：fetchPrice 在 callback 裡把 result 直接 resume 出去（成功、失敗都要 resume 一次）。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift04-StructuredConcurrency/L09_ContinuationNever.swift
import Foundation
import LabKit

private struct PriceError: Error, CustomStringConvertible {
    var description: String { "交易對不存在" }
}

/// 呼叫端收到的結果，沒收到就是 nil（兩個版本共用）
private final class Outcome: @unchecked Sendable {
    private let lock = NSLock()
    private var text: String?
    func set(_ value: String) { lock.withLock { text = value } }
    var value: String? { lock.withLock { text } }
}

/// 舊的價格查詢 API（兩個版本共用）：0.1 秒後呼叫 completion
private final class LegacyPriceAPI: @unchecked Sendable {
    func fetch(_ symbol: String, completion: @escaping @Sendable (Result<Int, Error>) -> Void) {
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.1) {
            completion(symbol == "BTC/JPY" ? .success(15_000_000) : .failure(PriceError()))
        }
    }
}

// MARK: - Bug 版
private enum L09Bug {
    static func fetchPrice(_ symbol: String, api: LegacyPriceAPI) async throws -> Int {
        // TODO ①：try await withCheckedThrowingContinuation { continuation in … }：
        //        api.fetch(symbol) 的 callback 裡，如果 result 是 .success(let price)，就 continuation.resume(returning: price)；失敗的分支先不處理
        return 0
    }

    static func run(_ log: LabLog) async {
        let api = LegacyPriceAPI()
        let outcome = Outcome()
        Task {
            do { outcome.set("價格 \(try await fetchPrice("DOGE/JPY", api: api))") }
            catch { outcome.set("錯誤（\(error)）") }              // 有結果（成功或失敗）才會執行到這裡
        }
        try? await Task.sleep(for: .seconds(1))
        log("查詢不存在的交易對，等了 1 秒：\(outcome.value ?? "呼叫端還在等")")
    }
}

// MARK: - 修正版
private enum L09Fixed {
    static func fetchPrice(_ symbol: String, api: LegacyPriceAPI) async throws -> Int {
        // TODO ②：同樣用 withCheckedThrowingContinuation 包 api.fetch，callback 裡把 result 成功、失敗都 resume 出去（只 resume 一次）
        return 0
    }

    static func run(_ log: LabLog) async {
        let api = LegacyPriceAPI()
        let outcome = Outcome()
        Task {
            do { outcome.set("價格 \(try await fetchPrice("DOGE/JPY", api: api))") }
            catch { outcome.set("錯誤（\(error)）") }
        }
        try? await Task.sleep(for: .seconds(1))
        log("查詢不存在的交易對，等了 1 秒：\(outcome.value ?? "呼叫端還在等")")
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l09 = Lab(
        id: "L09",
        title: "查詢失敗時，呼叫端永遠在等",
        question: "第 5 題：continuation 是什麼？把舊的 callback 接成 async 時有哪三種常見誤用？",
        goal: "舊的查詢 API 失敗時也會呼叫 completion。包成 async 函式後，失敗要讓呼叫端收到錯誤，不能一直等下去。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後一行：查詢一個不存在的交易對，等了 1 秒，呼叫端有沒有收到結果（沒收到會顯示呼叫端還在等），出現 ✓。",
            "從 Xcode 執行時，主控台會印出 SWIFT TASK CONTINUATION MISUSE: … leaked its continuation without resuming it。",
            "想一想：callback 的每個分支都 resume 了嗎？",
            "寫 TODO ②，選「修正版」執行，呼叫端應該收到錯誤。",
        ],
        expectBug: "查詢不存在的交易對，等了 1 秒：呼叫端還在等（那個 Task 永遠不會結束，是洩漏）。",
        expectFixed: "查詢不存在的交易對，等了 1 秒：錯誤（交易對不存在）。",
        run: { variant, log in
            switch variant {
            case .bug: await L09Bug.run(log)
            case .fixed: await L09Fixed.run(log)
            }
        },
        checkBug: { lines in
            lines.last == "查詢不存在的交易對，等了 1 秒：呼叫端還在等"
                ? nil : "Bug 還沒重現：失敗的分支不要 resume，呼叫端才會一直等"
        },
        checkFixed: { lines in
            lines.last == "查詢不存在的交易對，等了 1 秒：錯誤（交易對不存在）"
                ? nil : "呼叫端應該收到「交易對不存在」的錯誤：fetchPrice 要把 result 的成功與失敗都 resume 出去"
        }
    )
}
