// L05：用 Combine 訂閱行情，關掉畫面後 ViewModel 沒有被釋放（sink 加 store(in:)）
// 對應複習站：Swift02ARC 第 4 題「實務上哪些寫法最容易造成 retain cycle？」（Combine 的 sink）
//
// 情境：行情畫面的 ViewModel 訂閱價格推播，每收到一筆就更新 last，訂閱存在自己的 cancellables 裡。
//
// 你要寫：
//   ① Bug 版：init 裡用 feed.sink 訂閱，closure 直接用 self 把 value 寫進 last，再 store(in: &cancellables)。
//   ② 修正版：sink 的 closure 改用 [weak self]。
// 只改有 TODO 的地方。解答：Solution/Swift/Swift02-ARC/L05_CombineSink.swift
import Combine
import SwiftUI
import LabKit

// MARK: - Bug 版
private enum L05Bug {
    static let live = "PriceViewModel（Bug 版）"

    final class PriceViewModel {
        private var cancellables = Set<AnyCancellable>()
        private(set) var last = 0

        init(feed: PassthroughSubject<Int, Never>) {
            LiveObjects.shared.add(live)
            // TODO ①：feed.sink { … }：closure 裡直接用 self，把 value 寫進 self.last；再 .store(in: &cancellables)
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
private enum L05Fixed {
    static let live = "PriceViewModel（修正版）"

    final class PriceViewModel {
        private var cancellables = Set<AnyCancellable>()
        private(set) var last = 0

        init(feed: PassthroughSubject<Int, Never>) {
            LiveObjects.shared.add(live)
            // TODO ②：同樣的 sink，但 closure 用 [weak self]；再 .store(in: &cancellables)
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
    static let l05 = Lab(
        id: "L05",
        title: "Combine 的 sink 讓 ViewModel 釋放不掉",
        question: "第 4 題：實務上哪些寫法最容易造成 retain cycle？",
        goal: "ViewModel 用 sink 訂閱價格推播並存進 cancellables。找出為什麼關掉畫面後它不會被釋放，並修好。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」，按畫面上方的「打開畫面」再按「關閉畫面」：「存活中 PriceViewModel」維持 1。",
            "按下方的「執行」看 log：先收到一筆推播（last = 15000000），關閉畫面後 weak 參考還在，出現 ✓。",
            "想一想：這一圈是 ViewModel → cancellables → 訂閱 → closure → ViewModel，哪一段可以改成 weak？",
            "寫 TODO ②，選「修正版」執行，存活數應該回到 0。",
        ],
        expectBug: "關閉畫面後，存活中的 PriceViewModel（Bug 版）仍是 1，weak 參考還在。",
        expectFixed: "收到推播後 last = 15000000，關閉畫面後存活數回到 0，weak 參考變成 nil。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        demo: { variant, _ in
            switch variant {
            case .bug: AnyView(ScreenDemo(name: L05Bug.live) { L05Bug.PriceViewModel(feed: PassthroughSubject<Int, Never>()) })
            case .fixed: AnyView(ScreenDemo(name: L05Fixed.live) { L05Fixed.PriceViewModel(feed: PassthroughSubject<Int, Never>()) })
            }
        },
        checkBug: { lines in
            guard lines.contains("收到推播後，last = 15000000") else { return "推播送出後 last 要更新成 15000000：用 feed.sink 訂閱，closure 裡把 value 寫進 self.last" }
            guard lines.last?.hasSuffix("：1（weak 參考還在）") == true else { return "Bug 還沒重現：關閉畫面後 ViewModel 應該還活著（sink 的 closure 要直接用 self）" }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains("收到推播後，last = 15000000") else { return "推播送出後 last 要更新成 15000000：用 feed.sink 訂閱，closure 裡把 value 寫進 last" }
            guard lines.last?.hasSuffix("：0（weak 參考已變成 nil）") == true else { return "關閉畫面後 ViewModel 還活著：sink 的 closure 要用 [weak self]" }
            return nil
        }
    )
}
