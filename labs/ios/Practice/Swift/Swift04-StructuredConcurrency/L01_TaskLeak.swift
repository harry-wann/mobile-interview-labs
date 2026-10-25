// L01：離開行情畫面後，輪詢還在繼續（Task {} 開出去就不管）
// 對應複習站：Swift04StructuredConcurrency 第 1 題「什麼是結構化並行？和直接開 Task {} 差在哪？」
//
// 情境：行情畫面出現時開始每 0.1 秒輪詢一次價格（最多 30 次）。使用者看了約 0.35 秒就離開畫面。
//
// 你要寫：
//   ① Bug 版：onAppear 用 Task { } 開始輪詢：最多 30 輪，每輪先 polls.hit()，再睡 0.1 秒（睡的時候被取消就結束）。不保存這個 Task。
//   ② 修正版：onAppear 的輪詢和 Bug 版一樣（已寫好），改成把 Task 存進 task 屬性；onDisappear 時呼叫 cancel()。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift04-StructuredConcurrency/L01_TaskLeak.swift
import Foundation
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    final class PriceScreen {
        let polls: Swift04StructuredConcurrency.Counter

        init(polls: Swift04StructuredConcurrency.Counter) { self.polls = polls }

        func onAppear() {
            // TODO ①：用 Task { } 開輪詢：for _ in 0..<30，每輪 polls.hit()，再用 guard (try? await Task.sleep(for: .milliseconds(100))) != nil else { return } 睡 0.1 秒
            //        （睡的時候如果被取消，sleep 會丟錯，就結束）。注意：不要保存這個 Task
        }

        func onDisappear() {}
    }

    static func run(_ log: LabLog) async {
        let polls = Swift04StructuredConcurrency.Counter()
        let screen = PriceScreen(polls: polls)
        screen.onAppear()
        try? await Task.sleep(for: .milliseconds(350))
        screen.onDisappear()
        let atLeave = polls.count
        log("離開畫面時，已經輪詢 \(atLeave) 次")
        try? await Task.sleep(for: .seconds(1))
        log("離開畫面 1 秒後，又輪詢了 \(polls.count - atLeave) 次")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class PriceScreen {
        let polls: Swift04StructuredConcurrency.Counter
        private var task: Task<Void, Never>?

        init(polls: Swift04StructuredConcurrency.Counter) { self.polls = polls }

        func onAppear() {
            // TODO ②-1：下面的 Task 已經寫好（和 Bug 版一樣）。改成把它存進 task 屬性
            Task {
                for _ in 0..<30 {
                    polls.hit()
                    guard (try? await Task.sleep(for: .milliseconds(100))) != nil else { return }
                }
            }
        }

        func onDisappear() {
            // TODO ②-2：離開畫面時，取消 task
        }
    }

    static func run(_ log: LabLog) async {
        let polls = Swift04StructuredConcurrency.Counter()
        let screen = PriceScreen(polls: polls)
        screen.onAppear()
        try? await Task.sleep(for: .milliseconds(350))
        screen.onDisappear()
        let atLeave = polls.count
        log("離開畫面時，已經輪詢 \(atLeave) 次")
        try? await Task.sleep(for: .seconds(1))
        log("離開畫面 1 秒後，又輪詢了 \(polls.count - atLeave) 次")
    }
}

// MARK: - 演練設定
private func pollsAfterLeave(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "離開畫面 1 秒後，又輪詢了 "
        return line.hasPrefix(prefix) ? Int(line.dropFirst(prefix.count).dropLast(2)) : nil
    }
}

private func pollsAtLeave(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("離開畫面時，已經輪詢 ") }
        .flatMap { Int($0.dropFirst("離開畫面時，已經輪詢 ".count).dropLast(2)) }
}

extension Swift04StructuredConcurrency {
    static let l01 = Lab(
        id: "L01",
        title: "離開畫面後，輪詢還在繼續",
        question: "第 1 題：什麼是結構化並行？和直接開 Task {} 差在哪？",
        goal: "畫面出現時用 Task 開始輪詢價格。使用者離開畫面後，輪詢必須停下來。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後兩行：離開畫面時輪詢了幾次、離開 1 秒後又輪詢了幾次，出現 ✓。",
            "想一想：Task { } 開出去之後，onDisappear 能對它做什麼？",
            "寫 TODO ②，選「修正版」執行，離開畫面後不應該再輪詢。",
            "進階：SwiftUI 的 .task { } 修飾器會在畫面消失時自動取消 Task，不用自己保存。",
        ],
        expectBug: "離開畫面時輪詢約 4 次；離開 1 秒後又輪詢了約 9 次，一直到 30 次輪詢完才停。",
        expectFixed: "離開畫面時輪詢約 4 次；離開 1 秒後又輪詢了 0 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let before = pollsAtLeave(lines), before >= 2 else { return "畫面開著的 0.35 秒內應該輪詢至少 2 次：onAppear 要用 Task 開始輪詢，每輪先 polls.hit()" }
            guard let after = pollsAfterLeave(lines) else { return "找不到離開畫面之後的輪詢次數" }
            return after >= 5 ? nil : "Bug 還沒重現：離開畫面後輪詢應該繼續（不要保存 Task、不要取消）"
        },
        checkFixed: { lines in
            guard let before = pollsAtLeave(lines), before >= 2 else { return "畫面開著的 0.35 秒內應該輪詢至少 2 次：onAppear 要用 Task 開始輪詢，每輪先 polls.hit()" }
            guard let after = pollsAfterLeave(lines) else { return "找不到離開畫面之後的輪詢次數" }
            return after == 0 ? nil : "離開畫面後又輪詢了 \(after) 次：要保存 Task，onDisappear 時呼叫 cancel()"
        }
    )
}
