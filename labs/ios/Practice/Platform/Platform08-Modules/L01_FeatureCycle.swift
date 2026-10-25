// L01：測下單頁，卻被迫準備資產頁，還留下記憶體洩漏（Feature 之間不能互相依賴）
// 對應複習站：Platform08Modules 第 1 題「為什麼要模組化？模組之間的依賴方向要怎麼規劃？」
//
// 情境：交易所 App 有「下單」和「資產」兩個功能模組。下單成功後要跳到資產頁；資產頁的「入金後去下單」又要跳回下單頁。
//       演練做兩件事：1. 單獨測試下單頁（onOrderPlaced），看資產頁被牽連多少；2. 兩個畫面都關閉後，看還有幾個 ViewModel 活著。
//
// 你要寫：
//   ① Bug 版：OrderViewModel 用 lazy var 直接建立 WalletViewModel(order: self, api: walletApi)；onOrderPlaced 呼叫 wallet.open()。
//   ② 修正版：OrderViewModel 對 walletNavigator 用 weak 參考；onOrderPlaced 呼叫 walletNavigator?.openWallet()；AppCoordinator.openWallet 呼叫 wallet.open()。
// 只改有 TODO 的地方。解答：Solution/Platform/Platform08-Modules/L01_FeatureCycle.swift
import Foundation
import LabKit

/// 假的資產 API（兩個版本共用）：記下被呼叫幾次
private final class WalletApi: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func fetchBalance() { lock.withLock { count += 1 } }
    var calls: Int { lock.withLock { count } }
}

// MARK: - Bug 版
private enum L01Bug {
    /// 下單功能：直接建立、直接持有資產功能的具體型別
    final class OrderViewModel {
        private let walletApi: WalletApi
        // TODO ①-1：宣告 private lazy var wallet = WalletViewModel(order: self, api: walletApi)
        //          （直接建立另一個功能的具體型別，還把自己傳進去）
        init(walletApi: WalletApi) {
            self.walletApi = walletApi
            LiveObjects.shared.add("L01Bug.OrderViewModel")
        }
        deinit { LiveObjects.shared.remove("L01Bug.OrderViewModel") }
        func onOrderPlaced() {
            // TODO ①-2：下單成功：打開資產頁（wallet.open()）
        }
    }

    /// 資產功能：入金後要回到下單頁，所以直接持有 OrderViewModel
    final class WalletViewModel {
        let order: OrderViewModel
        private let api: WalletApi
        init(order: OrderViewModel, api: WalletApi) {
            self.order = order
            self.api = api
            LiveObjects.shared.add("L01Bug.WalletViewModel")
        }
        deinit { LiveObjects.shared.remove("L01Bug.WalletViewModel") }
        func open() { api.fetchBalance() }                                       // 打開資產頁：載入餘額
    }

    static func run(_ log: LabLog) async {
        let api = WalletApi()
        do {
            let order = OrderViewModel(walletApi: api)       // 單獨測試下單頁，卻要準備資產頁的 API
            order.onOrderPlaced()
        }                                                    // 畫面關閉：離開這個範圍，沒有人再用它們
        log("單獨測試下單：資產頁的 API 被呼叫 \(api.calls) 次，導覽請求 0 次")
        let alive = LiveObjects.shared.count("L01Bug.OrderViewModel") + LiveObjects.shared.count("L01Bug.WalletViewModel")
        log("兩個畫面都關閉後，還活著的 ViewModel：\(alive) 個")
    }
}

// MARK: - 修正版
private enum L01Fixed {
    /// Core 模組定義的協定：Feature 只依賴它們
    protocol WalletNavigator: AnyObject { func openWallet() }
    protocol OrderNavigator: AnyObject { func openOrder() }

    final class OrderViewModel {
        // TODO ②-1：下面這行要改成 weak（對 App 層只用 weak 參考），不然 ViewModel 和 AppCoordinator 會互相強參考，關閉後釋放不掉
        private var walletNavigator: WalletNavigator?
        init(walletNavigator: WalletNavigator) {
            self.walletNavigator = walletNavigator
            LiveObjects.shared.add("L01Fixed.OrderViewModel")
        }
        deinit { LiveObjects.shared.remove("L01Fixed.OrderViewModel") }
        func onOrderPlaced() {
            // TODO ②-2：只請 App 層帶使用者去資產頁：walletNavigator?.openWallet()（OrderViewModel 不知道資產頁長什麼樣）
        }
    }

    final class WalletViewModel {
        private weak var orderNavigator: OrderNavigator?
        private let api: WalletApi
        init(orderNavigator: OrderNavigator, api: WalletApi) {
            self.orderNavigator = orderNavigator
            self.api = api
            LiveObjects.shared.add("L01Fixed.WalletViewModel")
        }
        deinit { LiveObjects.shared.remove("L01Fixed.WalletViewModel") }
        func open() { api.fetchBalance() }
        func onDepositDone() { orderNavigator?.openOrder() }
    }

    /// 測試用的假導覽：只記下被要求過幾次
    final class FakeWalletNavigator: WalletNavigator {
        private(set) var opens = 0
        func openWallet() { opens += 1 }
    }

    /// App 層（Composition Root）：唯一同時認識兩個功能的地方，負責組裝與跳轉
    final class AppCoordinator: WalletNavigator, OrderNavigator {
        private(set) var order: OrderViewModel!
        private(set) var wallet: WalletViewModel!
        init(api: WalletApi) {
            order = OrderViewModel(walletNavigator: self)
            wallet = WalletViewModel(orderNavigator: self, api: api)
        }
        func openWallet() {
            // TODO ②-3：App 層才認識兩個功能：真的打開資產頁（wallet.open()）
        }
        func openOrder() {}
    }

    static func run(_ log: LabLog) async {
        let api = WalletApi()
        let navigator = FakeWalletNavigator()
        do {
            let order = OrderViewModel(walletNavigator: navigator)   // 單獨測試下單頁：只要一個假的導覽
            order.onOrderPlaced()
        }
        log("單獨測試下單：資產頁的 API 被呼叫 \(api.calls) 次，導覽請求 \(navigator.opens) 次")

        do {
            let app = AppCoordinator(api: api)                        // 真的 App：由 App 層組裝兩個功能
            app.order.onOrderPlaced()
        }                                                             // 畫面關閉
        let alive = LiveObjects.shared.count("L01Fixed.OrderViewModel") + LiveObjects.shared.count("L01Fixed.WalletViewModel")
        log("兩個畫面都關閉後，還活著的 ViewModel：\(alive) 個")
    }
}

// MARK: - 演練設定
extension Platform08Modules {
    static let l01 = Lab(
        id: "L01",
        title: "測下單頁卻被迫準備資產頁，還留下記憶體洩漏",
        question: "第 1 題：為什麼要模組化？模組之間的依賴方向要怎麼規劃？",
        goal: "兩個功能之間不互相依賴：測試下單頁不需要資產頁，關閉畫面後也不會有物件釋放不掉。跨功能的跳轉要怎麼寫？",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看單獨測試下單時資產頁的 API 被呼叫幾次，以及關閉畫面後還活著幾個 ViewModel，出現 ✓。",
            "想一想：OrderViewModel 為什麼一定要有 walletApi 才能建立？WalletViewModel 持有 order，OrderViewModel 又持有 wallet，會發生什麼事？",
            "寫 TODO ②，選「修正版」執行，資產頁的 API 0 次、導覽請求 1 次、沒有物件洩漏。",
            "進階：打開 Debug Memory Graph（Xcode 偵錯列的三個圓圈圖示），在 Bug 版執行後找 L01Bug.OrderViewModel，看它和 WalletViewModel 之間的循環。",
        ],
        expectBug: "「單獨測試下單：資產頁的 API 被呼叫 1 次，導覽請求 0 次」，「兩個畫面都關閉後，還活著的 ViewModel：2 個」。",
        expectFixed: "「單獨測試下單：資產頁的 API 被呼叫 0 次，導覽請求 1 次」，「兩個畫面都關閉後，還活著的 ViewModel：0 個」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("兩個畫面都關閉後") }) else { return "演練沒有跑完" }
            if !lines.contains("單獨測試下單：資產頁的 API 被呼叫 1 次，導覽請求 0 次") {
                return "Bug 版：OrderViewModel 用 lazy var 直接建立 WalletViewModel(order: self, api:)，onOrderPlaced 呼叫 wallet.open()，資產頁的 API 會被呼叫 1 次"
            }
            return lines.contains("兩個畫面都關閉後，還活著的 ViewModel：2 個")
                ? nil : "Bug 還沒重現：WalletViewModel 要用強參考持有 order（let order: OrderViewModel），兩個物件互相持有，關閉後才會洩漏"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("兩個畫面都關閉後") }) else { return "演練沒有跑完" }
            if !lines.contains("單獨測試下單：資產頁的 API 被呼叫 0 次，導覽請求 1 次") {
                return "測試下單頁只用假的 WalletNavigator：onOrderPlaced 呼叫 walletNavigator?.openWallet()，資產頁的 API 不該被呼叫，導覽請求 1 次"
            }
            return lines.contains("兩個畫面都關閉後，還活著的 ViewModel：0 個")
                ? nil : "還有 ViewModel 沒釋放：ViewModel 對 App 層（導覽）要用 weak 參考，不要強參考"
        }
    )
}
