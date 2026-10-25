// L03：把 SwiftUI 畫面嵌進 UIKit 畫面，少了 addChild／didMove，生命週期沒有傳過去（UIHostingController）
// 對應複習站：Swift09UIKitInterop 第 4 題「在 UIKit 裡嵌入 SwiftUI 畫面（UIHostingController），要怎麼做才正確？」
//
// 情境：行情頁是 UIKit 的 view controller，它要把一個 SwiftUI 畫面當作自己的一部分。
// Bug 版：只把 hosting controller 的 view 加進來（addSubview），沒有 addChild、也沒有 didMove(toParent:)。
//        UIKit 不知道 host 是子 view controller，行情頁出現時不會通知它（viewDidAppear 沒有傳過去），safe area 等資訊也不會正確傳給 SwiftUI。
// 修正版：完整的 containment 三步驟：addChild(host) → 加 view 並設定約束 → host.didMove(toParent: self)。
import SwiftUI
import UIKit
import LabKit

/// 嵌進去的 SwiftUI 畫面（兩個版本共用）
private struct MarketView: View {
    var body: some View {
        Text("行情")
    }
}

/// 會記錄 viewDidAppear 有沒有被呼叫的 hosting controller（兩個版本共用）
private final class TrackedHost: UIHostingController<MarketView> {
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        SwiftUILabTally.shared.add("L03 host 的 viewDidAppear")
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        SwiftUILabTally.shared.add("L03 host 的 viewDidDisappear")
    }
}

/// 演練流程（兩個版本共用）：行情頁放進 UINavigationController 出現 → 再 push 另一個畫面蓋住它，看 host 收到哪些生命週期事件
@MainActor
private func scenario(_ log: LabLog, controller: UIViewController, host: UIViewController) async {
    SwiftUILabTally.shared.reset()
    let nav = UINavigationController(rootViewController: controller)
    let stage = SwiftUILabStage(viewController: nav)
    await stage.settle(ms: 300)
    nav.pushViewController(UIViewController(), animated: false)     // 行情頁被蓋住
    await stage.settle(ms: 300)
    log("host 的 parent 是行情頁：\(host.parent === controller ? "是" : "不是")")
    log("行情頁出現、被蓋住之後：host 的 viewDidAppear 被呼叫 \(SwiftUILabTally.shared.count("L03 host 的 viewDidAppear")) 次，viewDidDisappear 被呼叫 \(SwiftUILabTally.shared.count("L03 host 的 viewDidDisappear")) 次")
    stage.remove()
}

/// 畫面類演練的操作區（兩個版本共用）：看嵌進去的畫面
private struct L03Demo: UIViewControllerRepresentable {
    let variant: LabVariant

    func makeUIViewController(context: Context) -> UIViewController {
        switch variant {
        case .bug: L03Bug.MarketHostViewController()
        case .fixed: L03Fixed.MarketHostViewController()
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

// MARK: - Bug 版
private enum L03Bug {
    @MainActor static func run(_ log: LabLog) async {
        let vc = MarketHostViewController()
        await scenario(log, controller: vc, host: vc.host)
    }

    final class MarketHostViewController: UIViewController {
        let host = TrackedHost(rootView: MarketView())

        override func viewDidLoad() {
            super.viewDidLoad()
            host.view.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(host.view)               // 只加了 view：UIKit 不知道 host 是子 view controller
            NSLayoutConstraint.activate([
                host.view.topAnchor.constraint(equalTo: view.topAnchor),
                host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    @MainActor static func run(_ log: LabLog) async {
        let vc = MarketHostViewController()
        await scenario(log, controller: vc, host: vc.host)
    }

    final class MarketHostViewController: UIViewController {
        let host = TrackedHost(rootView: MarketView())

        override func viewDidLoad() {
            super.viewDidLoad()
            addChild(host)                           // 步驟 1：先宣告 host 是我的子 view controller
            host.view.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(host.view)               // 步驟 2：把它的 view 加進來並設定約束
            NSLayoutConstraint.activate([
                host.view.topAnchor.constraint(equalTo: view.topAnchor),
                host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            ])
            host.didMove(toParent: self)             // 步驟 3：通知 host「加完了」
        }
    }
}

// MARK: - 演練設定
extension Swift09UIKitInterop {
    static let l03 = Lab(
        id: "L03",
        title: "SwiftUI 嵌進 UIKit，生命週期沒傳過去",
        question: "第 4 題：在 UIKit 裡嵌入 SwiftUI 畫面（UIHostingController），要怎麼做才正確？",
        goal: "UIKit 的行情頁要把 SwiftUI 畫面當作自己的一部分。比較只 addSubview，和完整做 containment 的差別。",
        steps: [
            "選「Bug 版」按「執行」，看 log：host 的 parent 不是行情頁；行情頁出現時 host 的 viewDidAppear 沒有被呼叫，被蓋住時 viewDidDisappear 卻被呼叫了，出現與消失對不起來。",
            "選「修正版」執行：host 的 parent 是行情頁，viewDidAppear、viewDidDisappear 各被呼叫 1 次。",
            "對照兩個版本的 viewDidLoad：差別是 addChild(host) 與 host.didMove(toParent:) 兩行。",
            "進階：在 Xcode 的 Debug View Hierarchy 看兩個版本的 view controller 階層。",
        ],
        expectBug: "host 的 parent 不是行情頁；行情頁出現時 host 的 viewDidAppear 被呼叫 0 次（UIKit 沒有把「出現」傳給它），被蓋住時 viewDidDisappear 卻被呼叫 1 次，出現與消失不成對。",
        expectFixed: "host 的 parent 是行情頁；viewDidAppear、viewDidDisappear 各被呼叫 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        demo: { variant, _ in AnyView(L03Demo(variant: variant).frame(height: 40)) },
        checkBug: { lines in
            if !lines.contains("host 的 parent 是行情頁：不是") { return "Bug 還沒重現：不要呼叫 addChild，host 的 parent 就不會是行情頁" }
            if !lines.contains("行情頁出現、被蓋住之後：host 的 viewDidAppear 被呼叫 0 次，viewDidDisappear 被呼叫 1 次") { return "Bug 還沒重現：host 沒有被當成子 view controller，行情頁出現時它的 viewDidAppear 不會被呼叫" }
            return nil
        },
        checkFixed: { lines in
            if !lines.contains("host 的 parent 是行情頁：是") { return "host 的 parent 應該是行情頁：要先呼叫 addChild(host)" }
            if !lines.contains("行情頁出現、被蓋住之後：host 的 viewDidAppear 被呼叫 1 次，viewDidDisappear 被呼叫 1 次") { return "host 的 viewDidAppear、viewDidDisappear 都應該被呼叫 1 次：完整做 addChild、加 view、didMove(toParent:)" }
            return nil
        }
    )
}
