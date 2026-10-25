// L05：離開行情頁之後，背景還在一直收價格（SwiftUI 的 .task 會跟著畫面取消）
// 對應複習站：Platform01Lifecycle 第 5 題「Fragment 為什麼有兩套生命週期？Flow 要怎麼跟著生命週期收集？」
//
// 情境：行情頁收集價格串流（每 20 ms 一筆，最多 100 筆）。iOS 沒有 Fragment「物件活著、畫面先死」的情況，
//        對應的問題是：畫面離開之後，收集價格的工作有沒有跟著停？演練把畫面放進視窗，300 ms 後關掉，再看 300 ms 內又收到幾筆。
//
// 你要寫：
//   ① Bug 版：在 .onAppear 裡用 Task { } 收集價格。離開畫面之後 Task 還在收。
//   ② 修正版：改用 .task { }，畫面消失時自動取消。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform01-Lifecycle/L05_TaskLifetime.swift
import SwiftUI
import LabKit

/// 計數器（兩個版本共用）：每個版本、每次執行各用一個，避免 Bug 版留下的工作污染修正版
private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func add() { lock.lock(); value += 1; lock.unlock() }
    var count: Int { lock.lock(); defer { lock.unlock() }; return value }
}

/// 假的價格串流（兩個版本共用）：每 20 ms 一筆，最多 100 筆；沒有人收的時候（被取消）就停止生產
private func prices() -> AsyncStream<Int> {
    AsyncStream { continuation in
        let producer = Task {
            for price in 1...100 {
                try? await Task.sleep(for: .milliseconds(20))
                if Task.isCancelled { break }
                continuation.yield(price)
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in producer.cancel() }
    }
}

/// 演練流程（兩個版本共用）：畫面出現 300 ms 後關掉，再看 300 ms 內又收到幾筆
@MainActor
private func scenario(_ log: LabLog, counter: Counter, screen: some View) async {
    let stage = SwiftUILabStage(screen)
    try? await Task.sleep(for: .milliseconds(300))
    log("離開行情頁之前收到 \(counter.count) 筆價格")
    stage.remove()                                     // 離開畫面
    try? await Task.sleep(for: .milliseconds(60))      // 給系統一點時間處理「畫面消失」，順手取消 .task
    let atLeave = counter.count
    try? await Task.sleep(for: .milliseconds(300))
    log("離開行情頁之後又收到 \(counter.count - atLeave) 筆價格")
}

// MARK: - Bug 版
private enum L05Bug {
    struct MarketView: View {
        let counter: Counter

        var body: some View {
            Text("行情")
                .onAppear {
                    // TODO ①：在這裡開一個 Task { }，用 for await 收集 prices()，每收到一筆呼叫 counter.add()（Task 不屬於畫面，離開之後不會被取消）
                }
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        let counter = Counter()
        await scenario(log, counter: counter, screen: MarketView(counter: counter))
    }
}

// MARK: - 修正版
private enum L05Fixed {
    struct MarketView: View {
        let counter: Counter

        var body: some View {
            Text("行情")
                // TODO ②：改成 .task { } 修飾器，在裡面用 for await 收集 prices()，每收到一筆呼叫 counter.add()
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        let counter = Counter()
        await scenario(log, counter: counter, screen: MarketView(counter: counter))
    }
}

// MARK: - 演練設定
private func after(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("離開行情頁之後又收到 ") }
        .flatMap { Int($0.replacingOccurrences(of: "離開行情頁之後又收到 ", with: "").replacingOccurrences(of: " 筆價格", with: "")) }
}

private func before(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("離開行情頁之前收到 ") }
        .flatMap { Int($0.replacingOccurrences(of: "離開行情頁之前收到 ", with: "").replacingOccurrences(of: " 筆價格", with: "")) }
}

extension Platform01Lifecycle {
    static let l05 = Lab(
        id: "L05",
        title: "離開行情頁之後，背景還在收價格",
        question: "第 5 題：Fragment 為什麼有兩套生命週期？Flow 要怎麼跟著生命週期收集？",
        goal: "畫面離開之後，收集價格的工作要跟著停；畫面在的時候照常收。先寫出離開後還在收的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform01-Lifecycle/L05_TaskLifetime.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「離開行情頁之後又收到幾筆價格」。",
            "寫 TODO ②（修正版），選「修正版」執行，確認離開後是 0 筆、離開前有收到。",
            "想一想：UIKit 沒有 .task，要怎麼達到一樣的效果？（把 Task 存起來，在 viewDidDisappear 取消）",
        ],
        expectBug: "離開行情頁之前收到約 12 筆；離開之後又收到十幾筆：Task 沒有人取消，一直收到串流結束。",
        expectFixed: "離開之前收到約 12 筆；離開之後又收到 0 筆：.task 跟著畫面取消。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let b = before(lines), let a = after(lines) else { return "演練沒有跑完" }
            if b == 0 { return "畫面出現後沒有收到價格：要在 .onAppear 裡開 Task 收集 prices()" }
            if a == 0 { return "Bug 還沒重現：離開之後應該還在收（用 .onAppear 加 Task，不要用 .task）" }
            return nil
        },
        checkFixed: { lines in
            guard let b = before(lines), let a = after(lines) else { return "演練沒有跑完" }
            if b == 0 { return "畫面出現後沒有收到價格：用 .task { } 收集 prices()" }
            if a != 0 { return "離開之後還收到 \(a) 筆：改用 .task，工作屬於畫面，畫面消失才會自動取消" }
            return nil
        }
    )
}
