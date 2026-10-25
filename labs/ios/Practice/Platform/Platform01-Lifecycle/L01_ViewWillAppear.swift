// L01：每返回行情頁一次，同一筆報價就多處理一次（viewWillAppear 會重複執行）
// 對應複習站：Platform01Lifecycle 第 1 題「畫面從打開到關掉，iOS 和 Android
// 各會經過哪些階段？」
//
// 情境：行情頁（MarketViewController）一出現就訂閱價格。
// 使用者點進下單頁再返回，來回三次，最後來一筆新報價。
//
// 你要寫：
//   ① Bug 版：在 viewWillAppear 訂閱行情，而且不取消。來回進出三次，
// 一筆報價會被處理好幾次。
//   ② 修正版：在 viewDidDisappear 取消訂閱，和 viewWillAppear 成對。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform01-Lifecycle/L01_ViewWillAppear.swift
import UIKit
import LabKit

/// 假的行情來源（兩個版本共用）：可以有很多個訂閱者
private final class PriceFeed {
    private var subscribers: [UUID: (Int) -> Void] = [:]
    var subscriberCount: Int { subscribers.count }

    func subscribe(_ handler: @escaping (Int) -> Void) -> UUID {
        let id = UUID()
        subscribers[id] = handler
        return id
    }

    func unsubscribe(_ id: UUID) {
        subscribers[id] = nil
    }

    func publish(_ price: Int) {
        for handler in subscribers.values { handler(price) }
    }
}

/// 演練流程（兩個版本共用）：行情頁放在導覽列裡，點進下單頁再返回，來回三次，
/// 最後來一筆報價
@MainActor
private func scenario(
    _ log: LabLog,
    makeMarket: (PriceFeed, @escaping (Int) -> Void) -> UIViewController
) async {
    let feed = PriceFeed()
    var handled = 0
    let market = makeMarket(feed) { _ in handled += 1 }
    let nav = UINavigationController(rootViewController: market)
    let stage = SwiftUILabStage(viewController: nav)
    await stage.settle()
    for _ in 1...3 {
        // 點進下單頁：行情頁 viewDidDisappear
        nav.pushViewController(UIViewController(), animated: false)
        await stage.settle(ms: 60)
        // 返回行情頁：行情頁 viewWillAppear 又執行一次
        nav.popViewController(animated: false)
        await stage.settle(ms: 60)
    }
    feed.publish(10_000_000)
    log("來回 3 次後，行情來源有 \(feed.subscriberCount) 個訂閱者")
    log("來回 3 次後，來一筆新報價，行情頁處理了 \(handled) 次")
    stage.remove()
}

// MARK: - Bug 版
private enum L01Bug {
    final class MarketViewController: UIViewController {
        private let feed: PriceFeed
        private let onPrice: (Int) -> Void

        init(feed: PriceFeed, onPrice: @escaping (Int) -> Void) {
            self.feed = feed
            self.onPrice = onPrice
            super.init(nibName: nil, bundle: nil)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            // TODO ①：在這裡訂閱行情（呼叫 feed.subscribe，把 onPrice
            // 傳進去）。Bug 版訂閱之後不取消，所以不用保存它回傳的 id
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { feed, onPrice in
            MarketViewController(feed: feed, onPrice: onPrice)
        }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class MarketViewController: UIViewController {
        private let feed: PriceFeed
        private let onPrice: (Int) -> Void
        private var subscription: UUID?

        init(feed: PriceFeed, onPrice: @escaping (Int) -> Void) {
            self.feed = feed
            self.onPrice = onPrice
            super.init(nibName: nil, bundle: nil)
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            subscription = feed.subscribe(onPrice)
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            // TODO ②：畫面消失就取消訂閱（用 feed.unsubscribe 取消
            // subscription），再把 subscription 清成 nil，和 viewWillAppear
            // 成對
        }
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { feed, onPrice in
            MarketViewController(feed: feed, onPrice: onPrice)
        }
    }
}

// MARK: - 演練設定
private func handledCount(_ lines: [String]) -> Int? {
    guard
        let line = lines.first(where: { $0.hasPrefix("來回 3 次後，來一筆新報價，行情頁處理了 ") }
        )
    else { return nil }
    return Int(
        line.replacingOccurrences(of: "來回 3 次後，來一筆新報價，行情頁處理了 ", with: "")
            .replacingOccurrences(of: " 次", with: ""))
}

extension Platform01Lifecycle {
    static let l01 = Lab(
        id: "L01",
        title: "每返回行情頁一次，報價就多處理一次",
        question: "第 1 題：畫面從打開到關掉，iOS 和 Android 各會經過哪些階段？",
        goal: "行情頁一出現就訂閱價格，來回進出下單頁三次之後，一筆新報價只該被處理一次。先寫出會重複處理的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform01-Lifecycle/L01_ViewWillAppear.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「行情來源有幾個訂閱者」和「行情頁處理了幾次」。",
            "對照 viewWillAppear 和 viewDidLoad：哪一個每次回到畫面都會執行？",
            "寫 TODO ②（修正版），選「修正版」執行，確認訂閱者只有 1 個、報價只處理 1 次。",
        ],
        expectBug: "來回 3 次後有 4 個訂閱者（第一次出現加上三次返回），一筆報價被處理 4 次。",
        expectFixed: "來回 3 次後只有 1 個訂閱者，一筆報價處理 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = handledCount(lines) else { return "演練沒有跑完" }
            if n == 0 { return "行情頁沒有收到報價：要在 viewWillAppear 裡訂閱" }
            if n == 1 { return "Bug 還沒重現：訂閱要放在 viewWillAppear，而且離開時不取消" }
            return nil
        },
        checkFixed: { lines in
            guard let n = handledCount(lines) else { return "演練沒有跑完" }
            if n == 0 { return "行情頁沒有收到報價：要在 viewWillAppear 裡訂閱" }
            if n > 1 {
                return
                    "一筆報價被處理了 \(n) 次：每次回到畫面都訂閱，卻沒有取消。要在 viewDidDisappear 取消上一次的訂閱"
            }
            return nil
        }
    )
}
