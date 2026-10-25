// L07：關掉畫面後，Timer 還在跑（repeating Timer 的 closure 用到 self）
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」
// （會重複執行的 Timer）
//
// 情境：行情畫面每 0.1 秒用 Timer 更新一次報價。ViewModel 的 deinit 裡有
// invalidate()，以為關畫面時 Timer 會被停掉。
//
// 你要寫：
//   ① Bug 版：init 裡用 Timer.scheduledTimer(withTimeInterval: 0.1, repeats:
// true)，closure 直接用 self 呼叫 refresh()。
//   ② 修正版：同樣的 Timer，closure 改用 [weak self]。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L07_TimerCycle.swift
import SwiftUI
import LabKit

// MARK: - Bug 版
private enum L07Bug {
    static let live = "PriceTicker（Bug 版）"

    final class PriceTicker {
        private var timer: Timer?
        private let hits: Swift02ARC.HitCounter

        init(hits: Swift02ARC.HitCounter) {
            self.hits = hits
            LiveObjects.shared.add(live)
            // TODO ①：timer = Timer.scheduledTimer(withTimeInterval: 0.1,
            // repeats: true) { … }，closure 裡直接用 self 呼叫 refresh()
        }

        private func refresh() { hits.hit() }

        deinit {
            // 有循環時 deinit 不會執行，這行就是白寫
            timer?.invalidate()
            LiveObjects.shared.remove(live)
        }
    }

    static func run(_ log: LabLog) async {
        let hits = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(
            log, name: live, stayOpen: .milliseconds(350),
            settle: .milliseconds(100), open: { PriceTicker(hits: hits) })
        let atClose = hits.count
        log("離開畫面時，Timer 已經觸發 \(atClose) 次")
        try? await Task.sleep(for: .milliseconds(500))
        log("關掉畫面 0.5 秒後，Timer 又觸發了 \(hits.count - atClose) 次")
    }
}

// MARK: - 修正版
private enum L07Fixed {
    static let live = "PriceTicker（修正版）"

    final class PriceTicker {
        private var timer: Timer?
        private let hits: Swift02ARC.HitCounter

        init(hits: Swift02ARC.HitCounter) {
            self.hits = hits
            LiveObjects.shared.add(live)
            // TODO ②：同樣的 Timer，但 closure 用 [weak self]，用 self?.
            // refresh() 呼叫
        }

        private func refresh() { hits.hit() }

        deinit {
            // ViewModel 能被釋放了，這行才會執行，Timer 跟著停
            timer?.invalidate()
            LiveObjects.shared.remove(live)
        }
    }

    static func run(_ log: LabLog) async {
        let hits = Swift02ARC.HitCounter()
        await Swift02ARC.openThenClose(
            log, name: live, stayOpen: .milliseconds(350),
            settle: .milliseconds(100), open: { PriceTicker(hits: hits) })
        let atClose = hits.count
        log("離開畫面時，Timer 已經觸發 \(atClose) 次")
        try? await Task.sleep(for: .milliseconds(500))
        log("關掉畫面 0.5 秒後，Timer 又觸發了 \(hits.count - atClose) 次")
    }
}

// MARK: - 演練設定
private func triggeredAfterClose(_ lines: [String]) -> Int? {
    lines.last.flatMap { line in
        let prefix = "關掉畫面 0.5 秒後，Timer 又觸發了 "
        return line.hasPrefix(prefix)
            ? Int(line.dropFirst(prefix.count).dropLast(2)) : nil
    }
}

private func triggeredBeforeClose(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("離開畫面時，Timer 已經觸發 ") }
        .flatMap { Int($0.dropFirst("離開畫面時，Timer 已經觸發 ".count).dropLast(2)) }
}

extension Swift02ARC {
    static let l07 = Lab(
        id: "L07",
        title: "關掉畫面後 Timer 還在跑",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal:
            "ViewModel 用 repeating Timer 更新報價，deinit 裡有 invalidate()。找出為什麼畫面關掉後 Timer 還在觸發，並修好。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看最後兩行：離開畫面時觸發了幾次、關掉畫面之後又觸發了幾次。",
            "再看關閉畫面那一行：存活中的 PriceTicker 是 1，weak 參考還在，deinit 沒有執行，invalidate() 當然也沒有執行。",
            "想一想：RunLoop 持有 Timer，Timer 持有 closure，closure 持有 self，誰該放手？",
            "寫 TODO ②，選「修正版」執行，關掉畫面後 Timer 不應該再觸發。",
        ],
        expectBug:
            "關閉畫面後存活數仍是 1；關掉畫面 0.5 秒後，Timer 又觸發了約 5 次（每 0.1 秒一次），而且會一直跑下去。",
        expectFixed: "關閉畫面後存活數是 0；關掉畫面之後，Timer 觸發 0 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L07Bug.run(log)
            case .fixed: await L07Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug:
                AnyView(
                    ScreenDemo(name: L07Bug.live) {
                        L07Bug.PriceTicker(hits: HitCounter())
                    })
            case .fixed:
                AnyView(
                    ScreenDemo(name: L07Fixed.live) {
                        L07Fixed.PriceTicker(hits: HitCounter())
                    })
            }
        },
        checkBug: { lines in
            guard let before = triggeredBeforeClose(lines), before >= 2 else {
                return
                    "畫面開著的 0.35 秒內 Timer 應該觸發至少 2 次：要用 Timer.scheduledTimer(repeats: true)，每次呼叫 refresh()"
            }
            guard let after = triggeredAfterClose(lines) else {
                return "找不到關掉畫面之後的觸發次數"
            }
            return after >= 3
                ? nil : "Bug 還沒重現：關掉畫面後 Timer 應該還在觸發（closure 要直接用 self）"
        },
        checkFixed: { lines in
            guard let before = triggeredBeforeClose(lines), before >= 2 else {
                return
                    "畫面開著的 0.35 秒內 Timer 應該觸發至少 2 次：要用 Timer.scheduledTimer(repeats: true)，每次呼叫 refresh()"
            }
            guard let after = triggeredAfterClose(lines) else {
                return "找不到關掉畫面之後的觸發次數"
            }
            return after == 0
                ? nil
                : "關掉畫面後 Timer 又觸發了 \(after) 次：closure 要用 [weak self]，讓 ViewModel 能被釋放，deinit 才會 invalidate"
        }
    )
}
