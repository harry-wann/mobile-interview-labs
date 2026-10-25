// L04：寫了 [weak self]，離開畫面後 ViewModel 還是被抓住（guard let self 放在迴圈外面）
// 對應複習站：Swift02ARC 第 3 題「closure 是怎麼帶走外面的變數的？」（誤解二：寫了 [weak self] 就一定沒事）
//
// 情境：行情畫面的 ViewModel 用 Task 一直收價格推播（共 24 筆，每 50 毫秒一筆），使用者開了畫面 0.2 秒就離開。
//
// 你要寫：
//   ① Bug 版：start 裡用 Task { [weak self] in … }，一開始就 guard let self，再用 for await 逐筆把價格寫進 lastPrice。
//   ② 修正版：同樣的 Task，但 guard let self 改成放在 for await 迴圈裡面（每一輪才取出 self）。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L04_GuardLetSelf.swift
import LabKit

/// 假的價格推播：24 筆，每 50 毫秒一筆，然後結束（兩個版本共用）
private func priceStream() -> AsyncStream<Int> {
    AsyncStream { continuation in
        let producer = Task {
            for i in 0..<24 {
                guard (try? await Task.sleep(for: .milliseconds(50))) != nil else { break }
                continuation.yield(15_000_000 + i * 1_000)
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in producer.cancel() }
    }
}

// MARK: - Bug 版
private enum L04Bug {
    static let live = "TickerViewModel（Bug 版）"

    final class TickerViewModel {
        private var task: Task<Void, Never>?
        private(set) var lastPrice = 0

        init() { LiveObjects.shared.add(live) }

        deinit {
            task?.cancel()
            LiveObjects.shared.remove(live)
        }

        func start(_ stream: AsyncStream<Int>) {
            // TODO ①：task = Task { [weak self] in … }：先 guard let self else { return }，再 for await price in stream，把 price 寫進 self.lastPrice
        }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, stayOpen: .milliseconds(200), beforeClose: { (vm: TickerViewModel) in
            log("離開畫面前，收到的最新價格：\(vm.lastPrice)")
        }, open: {
            let viewModel = TickerViewModel()
            viewModel.start(priceStream())
            return viewModel
        })
        try? await Task.sleep(for: .seconds(1.2))     // 等價格流播完
        log("價格流結束後，存活中的 \(live)：\(LiveObjects.shared.count(live))")
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static let live = "TickerViewModel（修正版）"

    final class TickerViewModel {
        private var task: Task<Void, Never>?
        private(set) var lastPrice = 0

        init() { LiveObjects.shared.add(live) }

        deinit {
            task?.cancel()
            LiveObjects.shared.remove(live)
        }

        func start(_ stream: AsyncStream<Int>) {
            // TODO ②：同樣的 Task，但 guard let self else { return } 要寫在 for await 迴圈裡面
        }
    }

    static func run(_ log: LabLog) async {
        await Swift02ARC.openThenClose(log, name: live, stayOpen: .milliseconds(200), beforeClose: { (vm: TickerViewModel) in
            log("離開畫面前，收到的最新價格：\(vm.lastPrice)")
        }, open: {
            let viewModel = TickerViewModel()
            viewModel.start(priceStream())
            return viewModel
        })
        try? await Task.sleep(for: .seconds(1.2))
        log("價格流結束後，存活中的 \(live)：\(LiveObjects.shared.count(live))")
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l04 = Lab(
        id: "L04",
        title: "寫了 weak self，離開畫面還是被抓住",
        question: "第 3 題：closure 是怎麼帶走外面的變數的？（誤解二：寫了 [weak self] 就一定沒事）",
        goal: "Task 裡用 for await 一直收價格。ViewModel 在使用者離開畫面後應該馬上被釋放，而不是等價格流播完。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看關閉畫面後的那一行：存活數是 1，weak 參考還在。",
            "再看最後一行：價格流播完（約 1.2 秒）之後，存活數才變成 0。",
            "想一想：guard let self 放在 for await 外面，self 在迴圈期間是 strong 還是 weak？",
            "寫 TODO ②，選「修正版」執行，關閉畫面後存活數應該立刻是 0。",
        ],
        expectBug: "關閉畫面後存活中的 TickerViewModel（Bug 版）仍是 1（weak 參考還在），要等價格流播完才變成 0。",
        expectFixed: "關閉畫面後存活數馬上是 0（weak 參考已變成 nil）。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("離開畫面前，收到的最新價格：15") }) else { return "離開畫面前應該已經收到價格：start 裡要用 Task 跑 for await price in stream，把價格寫進 lastPrice" }
            guard lines.contains(where: { $0.hasSuffix("：1（weak 參考還在）") }) else { return "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（guard let self 要放在 for await 外面）" }
            guard lines.last?.hasSuffix("：0") == true else { return "價格流播完之後，ViewModel 應該被釋放" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("離開畫面前，收到的最新價格：15") }) else { return "離開畫面前應該已經收到價格：start 裡要用 Task 跑 for await price in stream，把價格寫進 lastPrice" }
            guard lines.contains(where: { $0.hasSuffix("：0（weak 參考已變成 nil）") }) else { return "關閉畫面後 ViewModel 還活著：guard let self 要放進 for await 迴圈裡" }
            return nil
        }
    )
}
