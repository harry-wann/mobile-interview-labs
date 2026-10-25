// L04：關掉下單頁之後，view controller 沒有被釋放（rootView 的 closure 捕獲了
// self）
// 對應複習站：Swift09UIKitInterop 第 4 題「在 UIKit 裡嵌入 SwiftUI
// 畫面（UIHostingController），要怎麼做才正確？」
//
// 情境：下單頁是 UIKit 的 view controller，裡面嵌了一個 SwiftUI 畫面，SwiftUI
// 的「關閉」按鈕要呼叫 view controller 的 close()。
// Bug 版：傳給 rootView 的 closure 直接用 self。view controller 持有 hosting
// controller，hosting controller 持有 rootView，
//        rootView 裡的 closure 又持有 view controller，繞成一圈（retain
// cycle），這個畫面關掉之後永遠不會被釋放。
// 修正版：closure 用 [weak self]，不要強持有 view controller。
import SwiftUI
import UIKit
import LabKit

/// 嵌進去的 SwiftUI 畫面（兩個版本共用）：有一個「關閉」按鈕
private struct OrderView: View {
    let onClose: () -> Void

    var body: some View {
        Button("關閉", action: onClose)
    }
}

/// 演練流程（兩個版本共用）：打開下單頁（載入 view、嵌入 SwiftUI），
/// 再把它關掉；關掉之後沒有人持有它
@MainActor
private func scenario(_ log: LabLog, open: () -> UIViewController) async {
    LiveObjects.shared.reset()
    weak var weakVC: UIViewController?
    // UIKit 會 autorelease 一些參考，包起來讓它們在這一段結束時就放掉
    autoreleasepool {
        let vc = open()
        // 載入 view：這時才會建立 hosting controller 並嵌進去
        vc.loadViewIfNeeded()
        weakVC = vc
        log(
            "打開下單頁：存活的 OrderViewController \(LiveObjects.shared.count("L04 OrderViewController")) 個"
        )
        // 離開這一段，外面沒有人再持有 vc
    }
    // 等 run loop 轉一圈，UIKit 收尾後再檢查
    try? await Task.sleep(for: .milliseconds(100))
    log("關掉下單頁之後：OrderViewController \(weakVC != nil ? "還活著" : "已釋放")")
}

// MARK: - Bug 版
private enum L04Bug {
    final class OrderViewController: UIViewController {
        private lazy var host = UIHostingController(
            rootView: OrderView(onClose: {
                // 強持有 self：view controller → host → rootView → closure → view
                // controller
                self.close()
            }))

        init() {
            super.init(nibName: nil, bundle: nil)
            LiveObjects.shared.add("L04 OrderViewController")
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }
        deinit { LiveObjects.shared.remove("L04 OrderViewController") }

        override func viewDidLoad() {
            super.viewDidLoad()
            addChild(host)
            view.addSubview(host.view)
            host.didMove(toParent: self)
        }

        func close() {}
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { OrderViewController() }
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class OrderViewController: UIViewController {
        private lazy var host = UIHostingController(
            rootView: OrderView(onClose: { [weak self] in
                // 不強持有 self：沒有循環
                self?.close()
            }))

        init() {
            super.init(nibName: nil, bundle: nil)
            LiveObjects.shared.add("L04 OrderViewController")
        }

        required init?(coder: NSCoder) {
            fatalError("init(coder:) has not been implemented")
        }
        deinit { LiveObjects.shared.remove("L04 OrderViewController") }

        override func viewDidLoad() {
            super.viewDidLoad()
            addChild(host)
            view.addSubview(host.view)
            host.didMove(toParent: self)
        }

        func close() {}
    }

    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { OrderViewController() }
    }
}

// MARK: - 演練設定
extension Swift09UIKitInterop {
    static let l04 = Lab(
        id: "L04",
        title: "關掉下單頁，view controller 沒被釋放",
        question: "第 4 題：在 UIKit 裡嵌入 SwiftUI 畫面（UIHostingController），要怎麼做才正確？",
        goal:
            "下單頁嵌了一個 SwiftUI 畫面，它的「關閉」按鈕要呼叫 view controller 的 close()。關掉下單頁之後，view controller 要被釋放。",
        steps: [
            "按「執行」選「Bug 版」，看 log：關掉下單頁之後，OrderViewController 還活著。",
            "選「修正版」執行：關掉之後 OrderViewController 已釋放。",
            "對照兩個版本傳給 rootView 的 closure：差別只有 [weak self]。",
            "進階：Bug 版執行後，用 Xcode 的 Debug Memory Graph 看 OrderViewController，會看到它被 closure 的 context 持有。",
        ],
        expectBug:
            "關掉下單頁之後，OrderViewController 還活著：view controller → hosting controller → rootView 的 closure → view controller 繞成一圈。",
        expectFixed: "關掉下單頁之後，OrderViewController 已釋放。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains("打開下單頁：存活的 OrderViewController 1 個") {
                return
                    "下單頁沒有跑起來：打開時要有 1 個 OrderViewController（init 要 add、deinit 要 remove）"
            }
            if !lines.contains("關掉下單頁之後：OrderViewController 還活著") {
                return "Bug 還沒重現：rootView 的 closure 直接用 self，下單頁關掉後不會被釋放"
            }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("打開下單頁：存活的 OrderViewController 1 個") {
                return
                    "下單頁沒有跑起來：打開時要有 1 個 OrderViewController（init 要 add、deinit 要 remove）"
            }
            if !lines.contains("關掉下單頁之後：OrderViewController 已釋放") {
                return "下單頁關掉後應該被釋放：rootView 的 closure 不要強持有 self"
            }
            return nil
        }
    )
}
