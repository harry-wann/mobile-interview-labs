// L06：取消下單後，委託還掛在交易所（取消後的清理 await 立刻丟錯）
// 對應複習站：Swift04StructuredConcurrency 第 4 題「Task 的取消是怎麼運作的？」
//
// 情境：下單流程送出委託後，等交易所確認（300 毫秒）。使用者在等的時候按取消，流程要把已經送出的委託撤掉（撤單 API 要 50 毫秒）。
//
// 你要寫：
//   ① Bug 版：在 catch is CancellationError 裡直接 await exchange.cancelOrder()（用 try? 忽略錯誤）。
//   ② 修正版：撤單放進新的 Task { }，再 await 它的 value。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift04-StructuredConcurrency/L06_CancelCleanup.swift
import Foundation
import LabKit

/// 假的交易所（兩個版本共用）：送出委託後委託立刻存在，要成功撤單才會消失
private final class FakeExchange: @unchecked Sendable {
    private let lock = NSLock()
    private var open = false
    private var attempts = 0
    var hasOpenOrder: Bool { lock.withLock { open } }
    var cancelAttempts: Int { lock.withLock { attempts } }

    func place() async throws {
        lock.withLock { open = true }
        try await Task.sleep(for: .milliseconds(300))        // 等交易所確認
    }

    func cancelOrder() async throws {
        lock.withLock { attempts += 1 }
        try await Task.sleep(for: .milliseconds(50))         // 撤單的網路請求；Task 被取消時這裡會立刻丟錯
        lock.withLock { open = false }
    }
}

// MARK: - Bug 版
private enum L06Bug {
    static func run(_ log: LabLog) async {
        let exchange = FakeExchange()
        let flow = Task {
            do {
                try await exchange.place()
            } catch is CancellationError {
                // TODO ①：取消了：直接 try? await exchange.cancelOrder()，把已經送出的委託撤掉
            } catch {}
        }
        try? await Task.sleep(for: .milliseconds(100))
        flow.cancel()                                        // 使用者按下取消
        await flow.value
        log("撤單請求被呼叫 \(exchange.cancelAttempts) 次")
        log("取消流程結束後，交易所的委託：\(exchange.hasOpenOrder ? "仍掛在交易所" : "已撤單")")
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func run(_ log: LabLog) async {
        let exchange = FakeExchange()
        let flow = Task {
            do {
                try await exchange.place()
            } catch is CancellationError {
                // TODO ②：撤單放進一個新的 Task { }，再 await 它的 value（新的 Task 沒有被取消）
            } catch {}
        }
        try? await Task.sleep(for: .milliseconds(100))
        flow.cancel()
        await flow.value
        log("撤單請求被呼叫 \(exchange.cancelAttempts) 次")
        log("取消流程結束後，交易所的委託：\(exchange.hasOpenOrder ? "仍掛在交易所" : "已撤單")")
    }
}

// MARK: - 演練設定
extension Swift04StructuredConcurrency {
    static let l06 = Lab(
        id: "L06",
        title: "取消下單後，委託還掛在交易所",
        question: "第 4 題：Task 的取消是怎麼運作的？",
        goal: "使用者取消下單流程時，已經送出的委託要被撤掉。找出為什麼取消之後的撤單沒有執行，並修好。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後兩行：撤單請求有被呼叫，但取消流程結束後，交易所的委託還在不在，出現 ✓。",
            "想一想：catch 到 CancellationError 之後，Task 的取消旗子還舉著嗎？撤單 API 裡的 Task.sleep 看到旗子會怎麼樣？",
            "寫 TODO ②，選「修正版」執行，委託應該已撤單。",
        ],
        expectBug: "撤單請求被呼叫 1 次，但取消流程結束後，交易所的委託：仍掛在交易所。",
        expectFixed: "撤單請求被呼叫 1 次，取消流程結束後，交易所的委託：已撤單。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("撤單請求被呼叫 1 次") else { return "取消時要呼叫一次 exchange.cancelOrder()（在 catch is CancellationError 裡）" }
            return lines.last == "取消流程結束後，交易所的委託：仍掛在交易所"
                ? nil : "Bug 還沒重現：撤單應該失敗，委託仍掛在交易所（catch 裡直接 await 撤單，不要開新的 Task）"
        },
        checkFixed: { lines in
            guard lines.contains("撤單請求被呼叫 1 次") else { return "取消時要呼叫一次 exchange.cancelOrder()（在 catch is CancellationError 裡）" }
            return lines.last == "取消流程結束後，交易所的委託：已撤單"
                ? nil : "委託應該已撤單：撤單要放進新的 Task { } 再 await 它的 value"
        }
    )
}
