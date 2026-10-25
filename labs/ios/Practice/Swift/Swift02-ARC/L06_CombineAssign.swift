// L06：用 assign(to:on: self) 綁定價格，關掉畫面後 ViewModel 沒有被釋放
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」（Combine 的 assign(to:on:)）
//
// 情境：ViewModel 的 last 要跟著價格推播更新，用 assign 把推播綁到 last 上，訂閱存在自己的 cancellables 裡。
//
// 你要寫：
//   ① Bug 版：init 裡用 feed.assign(to:on:) 把推播綁到 self 的 last，再 store(in: &cancellables)。
//   ② 修正版：改用 feed.assign(to: &$last)，綁到 @Published 屬性，不用自己存 cancellables。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L06_CombineAssign.swift
import Combine
import SwiftUI
import LabKit

// MARK: - Bug 版
private enum L06Bug {
    static let live = "PriceViewModel（Bug 版）"

    final class PriceViewModel {
        private var cancellables = Set<AnyCancellable>()
        @Published private(set) var last = 0

        init(feed: PassthroughSubject<Int, Never>) {
            LiveObjects.shared.add(live)
            // TODO ①：用 assign(to:on:) 把 feed 綁到 self 的 \.last，再 .store(in: &cancellables)
        }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        let feed = PassthroughSubject<Int, Never>()
        await Swift02ARC.openThenClose(log, name: live, beforeClose: { (vm: PriceViewModel) in
            feed.send(15_000_000)
            log("收到推播後，last = \(vm.last)")
        }) { PriceViewModel(feed: feed) }
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static let live = "PriceViewModel（修正版）"

    final class PriceViewModel {
        @Published private(set) var last = 0

        init(feed: PassthroughSubject<Int, Never>) {
            LiveObjects.shared.add(live)
            // TODO ②：改用 assign(to:) 綁到 @Published 屬性本身（參數寫 &$last）
        }

        deinit { LiveObjects.shared.remove(live) }
    }

    static func run(_ log: LabLog) async {
        let feed = PassthroughSubject<Int, Never>()
        await Swift02ARC.openThenClose(log, name: live, beforeClose: { (vm: PriceViewModel) in
            feed.send(15_000_000)
            log("收到推播後，last = \(vm.last)")
        }) { PriceViewModel(feed: feed) }
    }
}

// MARK: - 演練設定
extension Swift02ARC {
    static let l06 = Lab(
        id: "L06",
        title: "沒有 closure 也會洩漏：assign(to:on:)",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal: "用 assign 把價格推播綁到 ViewModel 的 last。找出為什麼沒有 closure 也會洩漏，並改成不會洩漏的綁法。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」，按畫面上方的「打開畫面」再按「關閉畫面」：「存活中 PriceViewModel」維持 1。",
            "按下方的「執行」看 log：推播有更新 last，但關閉畫面後 weak 參考還在，出現 ✓。",
            "想一想：這段程式裡沒有任何 closure，是誰持有 self？（提示：assign(to:on:) 的文件）",
            "寫 TODO ②，選「修正版」執行，存活數應該回到 0。",
        ],
        expectBug: "關閉畫面後，存活中的 PriceViewModel（Bug 版）仍是 1，weak 參考還在。",
        expectFixed: "收到推播後 last = 15000000，關閉畫面後存活數回到 0，weak 參考變成 nil。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(ScreenDemo(name: L06Bug.live) { L06Bug.PriceViewModel(feed: PassthroughSubject<Int, Never>()) })
            case .fixed: AnyView(ScreenDemo(name: L06Fixed.live) { L06Fixed.PriceViewModel(feed: PassthroughSubject<Int, Never>()) })
            }
        },
        checkBug: { lines in
            guard lines.contains("收到推播後，last = 15000000") else { return "推播送出後 last 要更新成 15000000：用 feed.assign(to: \\.last, on: self) 綁定" }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else { return "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（要用 assign(to:on:) 並存進 cancellables）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("收到推播後，last = 15000000") else { return "推播送出後 last 要更新成 15000000：用 feed.assign(to: &$last) 綁定" }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else { return "關閉畫面後 ViewModel 還活著：改用 assign(to: &$last)" }
            return nil
        }
    )
}
