// L05：離開下單流程之後，FlowController 沒有被釋放（navigator 用了強參考）
// 對應複習站：Swift09UIKitInterop 第 5 題「FlowController（Coordinator 模式）
// 是什麼？為什麼要用？」
//
// 情境：行情頁（view controller）只認得 MarketNavigator 協定，
// 要換畫面時說「我要顯示委託頁」，由 FlowController 負責實際的導覽。
//      FlowController 持有 UINavigationController，navigationController
// 持有行情頁，行情頁再持有 navigator。
//
// 你要寫：
//   ① Bug 版：行情頁的 navigator 用普通的（強）參考。
//   ② 修正版：navigator 用 weak，行情頁不擁有 FlowController。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Swift/Swift09-UIKitInterop/L05_FlowControllerCycle.swift
import UIKit
import LabKit

/// 導覽協定（兩個版本共用）：行情頁只認得它，不知道下一個畫面是誰
private protocol MarketNavigator: AnyObject {
    func showOrder(symbol: String)
}

/// 測試用的假 navigator（兩個版本共用）：記下被要求顯示哪個畫面。協定的好處：
/// 不用真的導覽，就能測行情頁
private final class SpyNavigator: MarketNavigator {
    private(set) var shown: [String] = []
    func showOrder(symbol: String) { shown.append(symbol) }
}

/// 演練流程（兩個版本共用）
@MainActor
private func scenario(
    _ log: LabLog, test: () -> [String], openFlow: () -> Void
) async {
    LiveObjects.shared.reset()
    log("用假的 navigator 測行情頁：點了 BTC/JPY，navigator 收到 \(test())")
    // 打開下單流程，離開時外面沒有人再持有它
    openFlow()
    // 讓 UIKit 把暫時持有的東西放掉（run loop 轉一圈）
    try? await Task.sleep(for: .milliseconds(100))
    log(
        "離開流程之後：MarketFlowController 還活著 \(LiveObjects.shared.count("L05 MarketFlowController")) 個，MarketViewController 還活著 \(LiveObjects.shared.count("L05 MarketViewController")) 個"
    )
}

// MARK: - Bug 版
private enum L05Bug {
    final class MarketViewController: UIViewController {
        // TODO ①：現在 navigator 是 weak，不會有 bug。Bug 版改成普通的（強）
        // 參考
        weak var navigator: MarketNavigator?

        init() {
            super.init(nibName: nil, bundle: nil)
            LiveObjects.shared.add("L05 MarketViewController")
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }
        deinit { LiveObjects.shared.remove("L05 MarketViewController") }

        func didTapRow(symbol: String) {
            navigator?.showOrder(symbol: symbol)  // 只表達意圖
        }
    }

    final class MarketFlowController: MarketNavigator {
        private let navigationController: UINavigationController

        init(navigationController: UINavigationController) {
            self.navigationController = navigationController
            LiveObjects.shared.add("L05 MarketFlowController")
        }

        deinit { LiveObjects.shared.remove("L05 MarketFlowController") }

        func start() {
            let vc = MarketViewController()
            // FlowController 把自己當成 navigator 交給行情頁
            vc.navigator = self
            navigationController.setViewControllers([vc], animated: false)
        }

        func showOrder(symbol: String) {}
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(
            log,
            test: {
                let vc = MarketViewController()
                let spy = SpyNavigator()
                vc.navigator = spy
                vc.didTapRow(symbol: "BTC/JPY")
                return spy.shown
            },
            openFlow: {
                let flow = MarketFlowController(
                    navigationController: UINavigationController())
                flow.start()
            })
    }
}

// MARK: - 修正版
private enum L05Fixed {
    final class MarketViewController: UIViewController {
        // TODO ②：現在 navigator 是強參考（Bug 的寫法）。行情頁不該擁有
        // FlowController，改成不增加參考計數的宣告
        var navigator: MarketNavigator?

        init() {
            super.init(nibName: nil, bundle: nil)
            LiveObjects.shared.add("L05 MarketViewController")
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }
        deinit { LiveObjects.shared.remove("L05 MarketViewController") }

        func didTapRow(symbol: String) {
            navigator?.showOrder(symbol: symbol)  // 只表達意圖
        }
    }

    final class MarketFlowController: MarketNavigator {
        private let navigationController: UINavigationController

        init(navigationController: UINavigationController) {
            self.navigationController = navigationController
            LiveObjects.shared.add("L05 MarketFlowController")
        }

        deinit { LiveObjects.shared.remove("L05 MarketFlowController") }

        func start() {
            let vc = MarketViewController()
            vc.navigator = self
            navigationController.setViewControllers([vc], animated: false)
        }

        func showOrder(symbol: String) {}
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(
            log,
            test: {
                let vc = MarketViewController()
                let spy = SpyNavigator()
                vc.navigator = spy
                vc.didTapRow(symbol: "BTC/JPY")
                return spy.shown
            },
            openFlow: {
                let flow = MarketFlowController(
                    navigationController: UINavigationController())
                flow.start()
            })
    }
}

// MARK: - 演練設定
extension Swift09UIKitInterop {
    static let l05 = Lab(
        id: "L05",
        title: "離開下單流程，FlowController 沒被釋放",
        question: "第 5 題：FlowController（Coordinator 模式）是什麼？為什麼要用？",
        goal:
            "FlowController 持有導覽控制器，行情頁只認得 MarketNavigator 協定。先寫出離開流程後沒被釋放的 Bug 版，再修好。",
        steps: [
            "打開 Practice/Swift/Swift09-UIKitInterop/L05_FlowControllerCycle.swift，寫 TODO ①。",
            "選「練習」「Bug 版」按「執行」，看 log：離開流程之後，FlowController 與行情頁都還活著。",
            "寫 TODO ②，選「修正版」執行：兩個都是 0 個。",
            "看第一行 log：用假的 navigator 測行情頁，不用真的導覽，就知道點了哪一列。這是 FlowController 模式的好處之一。",
            "對照兩個版本的 navigator 宣告：只差在 weak。",
        ],
        expectBug:
            "離開流程之後，MarketFlowController 與 MarketViewController 都還活著 1 個：FlowController → navigationController → 行情頁 → navigator 繞成一圈。",
        expectFixed: "離開流程之後，兩個都是 0 個。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(
                "用假的 navigator 測行情頁：點了 BTC/JPY，navigator 收到 [\"BTC/JPY\"]")
            {
                return
                    "行情頁要能用假的 navigator 測：點了 BTC/JPY 要呼叫 navigator 的 showOrder"
            }
            if !lines.contains(
                "離開流程之後：MarketFlowController 還活著 1 個，MarketViewController 還活著 1 個"
            ) {
                return
                    "Bug 還沒重現：行情頁的 navigator 是強參考，離開流程後 FlowController 與行情頁都不會被釋放"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains(
                "用假的 navigator 測行情頁：點了 BTC/JPY，navigator 收到 [\"BTC/JPY\"]")
            {
                return
                    "行情頁要能用假的 navigator 測：點了 BTC/JPY 要呼叫 navigator 的 showOrder"
            }
            if !lines.contains(
                "離開流程之後：MarketFlowController 還活著 0 個，MarketViewController 還活著 0 個"
            ) {
                return "離開流程後 FlowController 與行情頁都應該被釋放：navigator 要宣告成 weak"
            }
            return nil
        }
    )
}
