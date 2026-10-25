// L01：離開行情畫面後，輪詢還在繼續（Task {} 開出去就不管）
// 對應複習站：Swift04StructuredConcurrency 第 1 題「什麼是結構化並行？和直接開
// Task {} 差在哪？」
//
// 情境：行情畫面出現時開始每 0.1 秒輪詢一次價格（最多 30 次）。使用者看了約 0.
// 35 秒就離開畫面。
// Bug 版：onAppear 用 Task { } 開出輪詢，沒有保存 Task。
// 離開畫面時沒有人能叫它停，輪詢繼續跑，還抓著畫面的物件。
// 修正版：把 Task 存進屬性，onDisappear 時呼叫 cancel()。Task.sleep
// 發現被取消會丟錯，輪詢就跟著結束。
//        （SwiftUI 的話，更好的做法是用 .task { }，畫面消失時框架自動取消。）
import Foundation
import LabKit

// MARK: - Bug 版
private enum L01Bug {
    final class PriceScreen {
        let polls: Swift04StructuredConcurrency.Counter

        init(polls: Swift04StructuredConcurrency.Counter) { self.polls = polls }

        func onAppear() {
            // 沒有保存這個 Task：離開畫面時沒有人能取消它
            Task {
                for _ in 0..<30 {
                    polls.hit()
                    guard
                        (try? await Task.sleep(for: .milliseconds(100))) != nil
                    else { return }
                }
            }
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
            // 保存 Task，離開畫面時才有辦法取消
            task = Task {
                for _ in 0..<30 {
                    polls.hit()
                    guard
                        (try? await Task.sleep(for: .milliseconds(100))) != nil
                    else { return }
                }
            }
        }

        func onDisappear() {
            // 舉旗子：Task.sleep 會丟出 CancellationError，輪詢結束
            task?.cancel()
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
        return line.hasPrefix(prefix)
            ? Int(line.dropFirst(prefix.count).dropLast(2)) : nil
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
            "選「Bug 版」執行，看最後兩行：離開畫面時輪詢了幾次、離開 1 秒後又輪詢了幾次。",
            "想一想：Task { } 開出去之後，onDisappear 能對它做什麼？",
            "選「修正版」執行，離開畫面後不應該再輪詢。",
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
            guard let before = pollsAtLeave(lines), before >= 2 else {
                return
                    "畫面開著的 0.35 秒內應該輪詢至少 2 次：onAppear 要用 Task 開始輪詢，每輪先 polls.hit()"
            }
            guard let after = pollsAfterLeave(lines) else {
                return "找不到離開畫面之後的輪詢次數"
            }
            return after >= 5 ? nil : "Bug 還沒重現：離開畫面後輪詢應該繼續（不要保存 Task、不要取消）"
        },
        checkFixed: { lines in
            guard let before = pollsAtLeave(lines), before >= 2 else {
                return
                    "畫面開著的 0.35 秒內應該輪詢至少 2 次：onAppear 要用 Task 開始輪詢，每輪先 polls.hit()"
            }
            guard let after = pollsAfterLeave(lines) else {
                return "找不到離開畫面之後的輪詢次數"
            }
            return after == 0
                ? nil : "離開畫面後又輪詢了 \(after) 次：要保存 Task，onDisappear 時呼叫 cancel()"
        }
    )
}
