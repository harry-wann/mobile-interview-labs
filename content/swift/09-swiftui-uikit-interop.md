---
id: swift-swiftui-uikit-interop
title: SwiftUI 與 UIKit 混合：UIViewRepresentable、UIHostingController 與 FlowController
summary: 實際的 App 常常 SwiftUI 和 UIKit 並存。這個單元講兩邊怎麼互相嵌入（UIViewRepresentable 與 UIHostingController）、Representable 裡的 Coordinator 是什麼、導覽由 FlowController 負責的設計，以及最低版本是 iOS 15 時有哪些 SwiftUI 功能不能用。
priority: P1
day: 1
tags: [uihostingcontroller, uiviewrepresentable, coordinator, flowcontroller, navigationstack, interop]
---

## 重點講解

這個單元回答六個面試常見問題：

1. SwiftUI 和 UIKit 要同時存在時，兩邊怎麼互相嵌入？
2. 最低版本是 iOS 15 的專案，有哪些 SwiftUI 的功能不能用？
3. `UIViewRepresentable` 的每個方法什麼時候被呼叫？它的 Coordinator 是做什麼的？
4. 在 UIKit 裡嵌入 SwiftUI 畫面（`UIHostingController`），要怎麼做才正確？
5. FlowController（Coordinator 模式）是什麼？為什麼要用？
6. 混合架構有什麼好處和代價？導覽到底該由 UIKit 還是 SwiftUI 負責？

### 1. SwiftUI 和 UIKit 要同時存在時，兩邊怎麼互相嵌入？

兩個方向各有一個官方橋接工具：

- **SwiftUI 裡放 UIKit：`UIViewRepresentable`**（要包整個畫面時用 `UIViewControllerRepresentable`）。你寫一個遵守這個協定的 struct，在裡面建立並更新一個 UIKit 的 view，這個 struct 就能像一般的 SwiftUI view 一樣使用。常見用途：SwiftUI 沒有對應元件的東西，例如 `WKWebView`、第三方圖表函式庫、相機預覽。
- **UIKit 裡放 SwiftUI：`UIHostingController`**。它是一個 view controller，把一個 SwiftUI view 當作它的內容（`rootView`）。你可以把它 push 進 `UINavigationController`，也可以當作子 view controller 放在 UIKit 畫面的一部分。

為什麼實務上會混用？因為既有的 App 通常已經有大量 UIKit，一口氣全部改寫風險太高，所以會「逐個畫面遷移」：新畫面用 SwiftUI，用 `UIHostingController` 嵌進原本的導覽；SwiftUI 缺的功能，再用 `UIViewRepresentable` 把 UIKit 元件包回來。兩種工具搭配，就能在同一個 App 裡平順共存。

> [!TIP]
> 面試時可以這樣說：「SwiftUI 進 UIKit 用 `UIHostingController`，UIKit 進 SwiftUI 用 `UIViewRepresentable`。我會逐畫面遷移，新畫面用 SwiftUI，缺的元件用 Representable 包回來，這樣風險小，也能持續上線。」

### 2. 最低版本是 iOS 15 的專案，有哪些 SwiftUI 的功能不能用？

SwiftUI 每一年都會新增很多 API，最低版本是 iOS 15 的專案要小心。下列這些都是 **iOS 16 才有**，在 iOS 15 的專案裡編譯會報錯 `only available in iOS 16.0 or newer`（我們用 iOS 15 當最低版本實際編譯確認過）：

- `NavigationStack` 與 `navigationDestination`：新的導覽容器。
- `UIHostingConfiguration`：在 `UICollectionView`、`UITableView` 的 cell 裡放 SwiftUI。
- `UIHostingController.sizingOptions`：讓 hosting controller 根據內容調整自己的大小。
- `UIViewRepresentable` 的 `sizeThatFits(_:uiView:context:)`：讓 Representable 回報自己想要的大小。

反過來，`.task` 和 `@StateObject` 在 iOS 15 可以用（`@StateObject` 其實從 iOS 14 就有）。所以 iOS 15 的專案通常是：導覽由 UIKit（`UINavigationController` 加 FlowController）負責，SwiftUI 只負責畫面內容。

有一個常見的誤會：「Swift concurrency 可以向下相容到比較舊的 iOS，所以新的 SwiftUI API 也可以」。不是這樣。Swift concurrency 的 back-deployment（向下部署）只涵蓋 concurrency 本身的 runtime，不包含 SwiftUI 的新 API。

> [!TIP]
> 面試時可以這樣說：「如果專案最低版本是 iOS 15，`NavigationStack`、`UIHostingConfiguration` 這些 iOS 16 的 API 不能直接用。我會讓 UIKit 負責導覽，SwiftUI 負責畫面內容，等最低版本提升再逐步改用。」

### 3. `UIViewRepresentable` 的每個方法什麼時候被呼叫？Coordinator 是做什麼的？

先記住四個方法的時機：

| 方法 | 什麼時候被呼叫 |
|---|---|
| `makeCoordinator()` | 建立 Representable 時，一次 |
| `makeUIView(context:)` | 一次，建立 UIKit view |
| `updateUIView(_:context:)` | **每次 SwiftUI 更新時都可能被呼叫**，包含傳進來的資料根本沒變的更新 |
| `dismantleUIView(_:coordinator:)` | UIKit view 從畫面移除時，這是 static 方法，用來做清理 |

我們用 macOS 版的 `NSViewRepresentable`（生命週期和 UIKit 版相同）放進 SwiftUI 視窗實測：把傳入的 `value` 更新 3 次，接著把它從畫面移除。`makeCoordinator`、`make`、`dismantle` 各被呼叫 1 次，但 `update` 被呼叫了 5 次，其中有一次連輸入都沒變也被呼叫。**所以 `updateUIView` 裡的程式必須冪等（重複執行結果一樣）而且便宜。**

**Coordinator 是什麼？** 注意，這裡的 Coordinator 是 `UIViewRepresentable` 裡的一個物件，和第 5 題的「Coordinator 模式」是兩件不同的事，只是剛好同名。Representable 本身是 struct，會被一直重建，沒辦法當 UIKit delegate，也沒地方放跨更新的狀態，所以 SwiftUI 讓你另外定義一個 class 叫 Coordinator，它的生命週期和 UIKit view 一致，常見用途有三個：

- 當 UIKit 的 delegate 或 target-action 的目標，例如 `WKScriptMessageHandler`、`UITextFieldDelegate`。
- 保存跨更新的狀態，例如「上次推送到圖表的時間點」。
- 把 UIKit 的事件轉回 SwiftUI，透過 `Binding` 或 closure 通知。

例子：行情 App 的 K 線圖。每次報價更新，SwiftUI 都會呼叫 `updateUIView`，如果每次都重設整張圖，畫面會閃爍。解法是在 Coordinator 記住上次推送到哪裡，只推送新的資料：

```swift
import SwiftUI
import UIKit

struct Candle { let time: Int; let close: Double }

final class CandleChartView: UIView {
    func update(_ candle: Candle) { /* 以 time 為 key 新增或取代這根 K 線 */ }
}

struct ChartRepresentable: UIViewRepresentable {
    let candles: [Candle]

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> CandleChartView { CandleChartView() }

    func updateUIView(_ view: CandleChartView, context: Context) {
        let coordinator = context.coordinator
        // 只推送上次之後的 K 線；比上次更早的時間點跳過
        for candle in candles where candle.time >= (coordinator.lastPushedTime ?? Int.min) {
            view.update(candle)
            coordinator.lastPushedTime = candle.time
        }
    }

    final class Coordinator {
        var lastPushedTime: Int?     // 跨更新保存的狀態
    }
}
```

另一個典型用途是 `WKWebView` 接收網頁 JavaScript 傳來的訊息。`WKUserContentController` 會**強持有**（strong reference）註冊進去的 handler，如果不移除，handler 與 WebView 會互相拖著不被釋放（上一個 ARC 單元提過的 retain cycle）。清理要放在 `dismantleUIView`：

```swift
import SwiftUI
import WebKit

struct WebChart: UIViewRepresentable {
    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(context.coordinator, name: "delayWall")   // 註冊 handler
        return WKWebView(frame: .zero, configuration: config)
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        // view 離開畫面時移除 handler，避免互相持有而無法釋放
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "delayWall")
    }

    final class Coordinator: NSObject, WKScriptMessageHandler {
        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {}
    }
}
```

還有兩個常見的坑：

- 不要在 `updateUIView` 裡每次都 `reloadData()` 或 `loadHTMLString`，會造成閃爍，甚至無限迴圈。用 Coordinator 記住上次的狀態，只送出差異。
- 不要在 `updateUIView` 裡同步修改 `@State` 或 `Binding`，會出現「Modifying state during view update」的警告。要延後一個 run loop（run loop 是主執行緒不斷處理事件的迴圈，例如用 `DispatchQueue.main.async` 排到下一輪再改）。

> [!TIP]
> 面試時可以這樣說：「`makeUIView` 只建立一次，`updateUIView` 每次 SwiftUI 更新都可能被呼叫，所以要冪等而且便宜。Coordinator 用來當 UIKit delegate 的目標，也用來保存跨更新的狀態，我用它記住上次推送到圖表的時間，只送差異。清理放在 `dismantleUIView`，例如移除 `WKScriptMessageHandler`。」

### 4. 在 UIKit 裡嵌入 SwiftUI 畫面（`UIHostingController`），要怎麼做才正確？

最簡單的情況是直接 push 一個 `UIHostingController(rootView: ...)`。如果要把 SwiftUI 當作 UIKit 畫面的**一部分**，要做完整的 containment（把一個 view controller 正式加成另一個的子 view controller）：

```swift
import SwiftUI
import UIKit

final class MarketViewModel: ObservableObject { @Published var title = "Market" }

struct MarketView: View {
    @ObservedObject var viewModel: MarketViewModel      // 外面建立、傳進來，所以用 @ObservedObject
    var body: some View { Text(viewModel.title) }
}

final class MarketHostViewController: UIViewController {
    private let viewModel: MarketViewModel
    private lazy var host = UIHostingController(rootView: MarketView(viewModel: viewModel))

    init(viewModel: MarketViewModel) {
        self.viewModel = viewModel
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(host)                                   // 步驟 1：先宣告 host 是我的子 view controller
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)                       // 步驟 2：把它的 view 加進來並設定約束
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        host.didMove(toParent: self)                     // 步驟 3：通知 host「加完了」
    }
}
```

重點有幾個：

- **完整的 containment 三步驟**（`addChild`、加 view、`didMove(toParent:)`）。少了它，生命週期事件（例如 `viewWillAppear`）與 safe area 的資訊都不會正確傳給 SwiftUI。
- **ViewModel 由外部（通常是 FlowController）建立後傳入**，SwiftUI 這邊用 `@ObservedObject`（因為擁有者在外面，見狀態管理單元）。`rootView` 只在建立時設定一次，之後的畫面更新靠 ViewModel 的 `@Published` 屬性觸發，不要每次更新都換一個新的 `rootView`，否則 SwiftUI 內部的狀態會被丟掉。
- **小心 closure 造成循環參考。** `rootView` 裡的 closure（例如 `onClose`）如果捕獲了持有 hosting controller 的 view controller，會形成 retain cycle，要用 `[weak self]`（見 ARC 單元）。
- **環境（environment）不會自動穿過 hosting 的邊界。** 每一個 `UIHostingController` 的 `rootView` 都是一棵全新的 SwiftUI 樹，需要的 `.environmentObject`、`.environment` 要各自注入，漏了會在執行時 crash。
- **SwiftUI 的 `onAppear` 和 UIKit 的 `viewWillAppear` 呼叫時機不完全一樣**，不要拿來互相替代。巢狀 hosting 的 safe area、鍵盤避讓、導覽列樣式，都要在實機上逐一確認。

> [!TIP]
> 面試時可以這樣說：「嵌入 `UIHostingController` 我會做完整的 containment。ViewModel 由 FlowController 建立後注入，SwiftUI 這邊用 `@ObservedObject`，`rootView` 只設定一次。另外 environment 不會穿過 hosting 邊界，每個 hosting 的根 view 都要自己注入。」

### 5. FlowController（Coordinator 模式）是什麼？為什麼要用？

**Coordinator 模式是一種導覽的設計方式：把「下一個要顯示哪個畫面」的決定，從 view controller 裡拿出來，交給另一個專門的物件負責。** 這個專門的物件，有些團隊叫 Coordinator，有些叫 FlowController，意思是一樣的。（再提醒一次：這和第 3 題 `UIViewRepresentable` 裡的 Coordinator 不是同一件事。）

在沒有 FlowController 的 UIKit 專案裡，常見的寫法是 view controller 自己 `navigationController?.pushViewController(OrderViewController(), ...)`。問題是：這個 view controller 必須認識下一個畫面，換一個進入點、要從別的地方重用這個畫面，或做 deep link（從通知或外部連結直接跳到深層的畫面），都要改它。

FlowController 的做法：

- FlowController 持有 `UINavigationController`，**負責建立 view controller 與 ViewModel，並決定下一個畫面**。
- view controller 只認得一個 `navigator` 協定（例如 `MarketNavigator`），要換畫面時只說「我要顯示委託頁」，不知道實際上是誰、怎麼顯示。

```swift
import UIKit

final class MarketViewModel {}          // 示意用的 ViewModel

protocol MarketNavigator: AnyObject {
    func showOrder(symbol: String)
}

final class MarketViewController: UIViewController {
    weak var navigator: MarketNavigator?          // 只認得協定，不知道下一個畫面是誰；weak 避免循環參考

    static func create(viewModel: MarketViewModel) -> MarketViewController {
        // 實際專案會在這裡把 viewModel 設定好
        return MarketViewController()
    }

    func didTapRow(symbol: String) {
        navigator?.showOrder(symbol: symbol)      // 只表達意圖
    }
}

final class MarketFlowController: MarketNavigator {
    private let navigationController: UINavigationController

    init(navigationController: UINavigationController) {
        self.navigationController = navigationController
    }

    func start() {
        let vc = MarketViewController.create(viewModel: MarketViewModel())
        vc.navigator = self                       // FlowController 把自己當成 navigator 交給 view controller
        navigationController.setViewControllers([vc], animated: false)
    }

    func showOrder(symbol: String) {
        // 在這裡建立下一個畫面和它需要的 ViewModel，再 push
    }
}
```

好處有四個：導覽邏輯集中在一個地方、畫面可以重用、測試時可以用假的（mock）navigator 取代、deep link 只需要一個入口。

缺點也要能說：FlowController 需要把畫面用到的依賴一路往下傳，容易變成一個什麼都管的巨大物件（god object）；解法是拆成子 flow（每個功能一個小的 FlowController）。另外父子 FlowController 的生命週期（誰持有誰、什麼時候釋放）要管理好。

> [!TIP]
> 面試時可以這樣說：「FlowController 就是 Coordinator 模式：它持有導覽控制器，負責建立 view controller 和 ViewModel，並決定下一個畫面。view controller 只認得 navigator 協定，不知道下一個畫面是誰，所以畫面好重用、導覽好測試、deep link 只有一個入口。缺點是依賴容易越傳越多，我會把它拆成子 flow。」

### 6. 混合架構有什麼好處和代價？導覽到底該由 UIKit 還是 SwiftUI 負責？

導覽的選擇跟最低版本有關。**iOS 16 以上**有 `NavigationStack`，用 `path` 綁定導覽的狀態：`NavigationStack(path: $path)`。`path` 就是「目前導覽到哪些畫面」的唯一來源，直接把陣列指派給它，就能 deep link 到多層深的畫面；`NavigationPath` 還可以編碼存起來，用來還原狀態。

**iOS 15** 只有 `NavigationView` 搭配 `NavigationLink(isActive:)`，在「用程式碼控制導覽」與 deep link 上很脆弱，所以多數 iOS 15 的專案選擇繼續讓 UIKit（FlowController 加 `UINavigationController`）負責導覽。

混合架構的整體取捨：

| 面向 | 好處 | 代價 |
|---|---|---|
| 逐步遷移 | 逐個畫面改寫，風險小，可以持續上線 | 團隊要同時熟悉兩套思維與慣例 |
| 導覽 | UIKit 的控制力強，iOS 15 也能用 | 和 SwiftUI 原生導覽重複，樣式要橋接（導覽列、轉場） |
| 狀態 | ViewModel 是兩邊共通的邊界 | environment、主題這類資料，要在每個 hosting 邊界重新注入 |
| 效能 | 重要的列表仍可用成熟的 UIKit | 多個 hosting controller 有額外成本，尺寸計算與 safe area 有坑 |

> [!TIP]
> 面試時可以這樣說：「iOS 16 之後我會考慮 `NavigationStack(path:)`，用 path 當單一真相來源；但 iOS 15 的專案，我會讓 UIKit 的 FlowController 負責導覽，SwiftUI 負責畫面內容。混合架構的代價是兩套思維，以及環境資料要在每個 hosting 邊界重新注入，這些我會用統一的 hosting 基底類別處理。」

> [!FIELD]
> 在一個社交 App 的全 App 重構中，採用 SwiftUI 加 UIKit 的混合式架構，架構關鍵字是 MVVM（Model-View-ViewModel）與 Coordinator（FlowController）。實作上，FlowController 持有導覽控制器並符合各個 `*Navigator` 協定，view controller 用 `static func create(...)` 建立並設定 `vc.navigator = self`；ViewModel 用 Inputs／Outputs 協定分開輸入與輸出；SwiftUI 畫面透過 `UIHostingController` 嵌入，並有加上導覽列樣式的 hosting 基底類別。這個 App 的最低版本是 iOS 15，沒有 `NavigationStack` 可用，所以導覽由 UIKit 的 FlowController 負責是很合理的選擇。
> 在一個即時行情 App 裡，K 線是 `UIViewRepresentable` 包 `WKWebView`：Coordinator 記住上次推送的時間點，只推送新資料，並在 `dismantleUIView` 移除 `WKScriptMessageHandler`。這兩個例子正好分別對應混合架構的導覽，以及 Representable 的細節。

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift09UIKitInterop**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift09-UIKitInterop/`，解答在 `labs/ios/Solution/Swift/Swift09-UIKitInterop/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **怎麼量**：按「執行」時，App 會把 SwiftUI 畫面或 UIKit 的 view controller 放進螢幕外的視窗自動跑一遍，把次數與有沒有被釋放寫進 log，自動檢查靠這些判斷。

### 演練 1：每來一筆報價，K 線圖就整張重畫（第 3 題）

**檔案**：`L01_UpdateUIViewReset.swift`

**情境**：K 線圖是 UIKit 的 view（`CandleChartView`），用 `UIViewRepresentable` 包進 SwiftUI。每一筆新報價進來，SwiftUI 就呼叫一次 `updateUIView`。

**怎麼寫**

1. **Bug 版**：`updateUIView` 每次都用 `view.reload(candles)` 把整份 K 線重設一遍。
2. **修正版**：加上 `makeCoordinator()` 與一個 `Coordinator` class，裡面放 `var lastPushedTime: Int?`；`updateUIView` 只用 `view.update(_:)` 推送 `time >= lastPushedTime` 的 K 線，並更新 `lastPushedTime`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 新報價進來 3 次，`updateUIView` 被呼叫 4 次，整張圖被重設 4 次，一共推送 406 根 K 線（100＋101＋102＋103） |
| 修正版 | 整張圖重設 0 次，一共只推送 106 根 K 線（第一次 100 根，之後每次重推最後一根再加上新的一根） |

**為什麼**：`updateUIView` 每次 SwiftUI 更新都可能被呼叫，所以裡面的程式要能重複執行、而且便宜。每次整張重設，畫面會閃爍，K 線越多越慢。Coordinator 的生命週期和 UIKit view 一致，用它保存「上次推送到哪一根」，每次只推送差異。

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L01_UpdateUIViewReset.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L01_UpdateUIViewReset.swift#fixed
```

### 演練 2：關掉圖表畫面，WKWebView 還留在記憶體（第 3 題）

**檔案**：`L02_WebViewHandlerLeak.swift`

**情境**：圖表是網頁，用 `UIViewRepresentable` 包 `WKWebView`。網頁用 JavaScript 傳訊息回來，所以 `WebCoordinator` 註冊成 `WKScriptMessageHandler`；它也要拿著 `WKWebView`，之後才能呼叫 `evaluateJavaScript`。

**怎麼寫**

1. **Bug 版**：在 `makeUIView` 裡用 `config.userContentController.add(context.coordinator, name: "delayWall")` 註冊 handler，並把建立好的 web view 存進 `context.coordinator.webView`；不寫 `dismantleUIView`。
2. **修正版**：加上 `static func dismantleUIView(_:coordinator:)`，在裡面用 `removeScriptMessageHandler(forName: "delayWall")` 移除 handler。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 圖表畫面關掉後，WKWebView 與 Coordinator 都還活著 1 個，沒有被釋放；在 App 上方的操作區開關畫面，數字會一直增加 |
| 修正版 | 關掉後兩個都是 0 個 |

**為什麼**：`WKUserContentController` 會強持有註冊進去的 handler。WKWebView → configuration → userContentController → Coordinator → WKWebView 繞成一圈（retain cycle），沒有人切斷，就永遠不會被釋放。`dismantleUIView` 是 view 離開畫面時被呼叫的地方，在這裡移除 handler 就能切斷這一圈。

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L02_WebViewHandlerLeak.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L02_WebViewHandlerLeak.swift#fixed
```

### 演練 3：SwiftUI 嵌進 UIKit，生命週期沒傳過去（第 4 題）

**檔案**：`L03_HostingContainment.swift`

**情境**：行情頁是 UIKit 的 view controller（放在 `UINavigationController` 裡），它要把一個 SwiftUI 畫面（`UIHostingController`）當作自己的一部分。

**怎麼寫**

1. **Bug 版**：`viewDidLoad` 裡只把 `host.view` 加進來（`addSubview`）並設定約束，不呼叫 `addChild`，也不呼叫 `didMove(toParent:)`。
2. **修正版**：完整做 containment 三步驟：`addChild(host)` → 加 view 並設定約束 → `host.didMove(toParent: self)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | host 的 parent 不是行情頁；行情頁出現時 host 的 `viewDidAppear` 被呼叫 0 次，被蓋住時 `viewDidDisappear` 卻被呼叫 1 次，出現與消失對不起來 |
| 修正版 | host 的 parent 是行情頁；`viewDidAppear`、`viewDidDisappear` 各被呼叫 1 次 |

**為什麼**：沒有 `addChild`，UIKit 不知道 host 是子 view controller，行情頁出現時不會通知它；沒有 `didMove(toParent:)`，host 也不知道自己已經加完了。SwiftUI 靠 hosting controller 收到的生命週期事件，以及 safe area 等資訊運作，所以要完整做完三步驟。

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L03_HostingContainment.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L03_HostingContainment.swift#fixed
```

### 演練 4：關掉下單頁，view controller 沒被釋放（第 4 題）

**檔案**：`L04_HostingClosureCycle.swift`

**情境**：下單頁是 UIKit 的 view controller，裡面嵌了一個 SwiftUI 畫面（`OrderView`），SwiftUI 的「關閉」按鈕要呼叫 view controller 的 `close()`。

**怎麼寫**

1. **Bug 版**：建立 `host` 時，傳給 `OrderView` 的 `onClose` closure 直接寫 `self.close()`。
2. **修正版**：closure 的捕獲清單加上 `[weak self]`，呼叫寫成 `self?.close()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 打開下單頁後存活 1 個 `OrderViewController`；關掉之後還活著 |
| 修正版 | 關掉之後已釋放 |

**為什麼**：view controller 持有 hosting controller（`host`），hosting controller 持有 `rootView`，`rootView` 裡的 closure 又強持有 view controller，繞成一圈。用 `[weak self]` 讓 closure 不增加 view controller 的引用計數，就沒有循環。

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L04_HostingClosureCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L04_HostingClosureCycle.swift#fixed
```

### 演練 5：離開下單流程，FlowController 沒被釋放（第 5 題）

**檔案**：`L05_FlowControllerCycle.swift`

**情境**：行情頁（`MarketViewController`）只認得 `MarketNavigator` 協定，要換畫面時說「我要顯示委託頁」，由 `MarketFlowController` 負責實際的導覽。FlowController 持有 `UINavigationController`，`UINavigationController` 持有行情頁，行情頁再持有 navigator。

**怎麼寫**

1. **Bug 版**：行情頁的 `navigator` 寫成普通的（強）參考：`var navigator: MarketNavigator?`。
2. **修正版**：寫成 `weak var navigator: MarketNavigator?`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 離開流程之後，`MarketFlowController` 與 `MarketViewController` 都還活著 1 個 |
| 修正版 | 離開流程之後，兩個都是 0 個 |

**為什麼**：FlowController → navigationController → 行情頁 → navigator（FlowController）繞成一圈。行情頁只是「回報意圖」的一方，不該擁有 FlowController，所以 navigator 要用 `weak`。另外，log 第一行示範了 FlowController 模式的好處：行情頁只認得協定，所以用假的 navigator（`SpyNavigator`）就能測「點了哪一列」，不用真的導覽。

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L05_FlowControllerCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift09-UIKitInterop/L05_FlowControllerCycle.swift#fixed
```

第 1、2、6 題是概念、版本差異與取捨題（兩個方向用哪個橋接工具、iOS 15 與 iOS 16 的 API 差異、導覽該由誰負責），沒有可以執行的 bug，沒有演練。

## 常見陷阱

- **在 `updateUIView` 裡重建或重設 UIKit view。** 造成閃爍與狀態遺失。用 Coordinator 記住上次的狀態，只送差異。
- **忘了在 `dismantleUIView` 移除 handler 或 observer。** `WKUserContentController` 會強持有 handler，不移除就無法釋放。
- **每次更新都換 `UIHostingController.rootView`。** SwiftUI 內部的狀態會被丟掉。`rootView` 只設定一次，更新靠 ViewModel。
- **把 `@StateObject` 寫在由 UIKit 重複建立的 `rootView` 裡。** 每次重新建立 `rootView`，物件跟著重建。ViewModel 由外面建立並注入。
- **假設 `UIHostingConfiguration`、`NavigationStack` 可以用。** 它們需要 iOS 16，最低版本是 iOS 15 的專案不能用。
- **環境物件沒有傳進另一個 hosting 的根 view。** 執行時 crash。每個 `UIHostingController` 都要自己注入。
- **把 `UIViewRepresentable` 的 Coordinator 和 Coordinator 導覽模式搞混。** 兩者只是同名：前者是 Representable 的 delegate 目標，後者是導覽的設計方式。

## 面試官想聽到的

1. 對 `UIViewRepresentable` 每個方法的呼叫時機清楚，並知道 `updateUIView` 要冪等、便宜。
2. 嵌入 `UIHostingController` 時有做完整的 containment，ViewModel 由外部建立並注入。
3. 說得出 FlowController（Coordinator 模式）的責任與缺點，並能和 `NavigationStack` 比較。
4. 知道哪些 API 需要 iOS 16，並對最低版本 iOS 15 的專案有務實的做法。
5. 對混合架構的取捨有具體的例子：environment 邊界、樣式橋接、效能。

> [!WARNING]
> 面試官常追問：「你們為什麼不全部改成 SwiftUI？」不要只說「還沒時間」。要說出具體理由：最低版本、既有 UIKit 畫面的風險、效能敏感的列表仍適合 UIKit，以及用逐畫面遷移讓每次上線都可控。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 新舊 UI 共存 | `UIHostingController`（UIKit 裡放 SwiftUI）、`UIViewRepresentable`（SwiftUI 裡放 UIKit） | `ComposeView`（View 或 Fragment 裡放 Compose）、`AndroidView`（Compose 裡放 View） |
| 橋接元件的清理 | `dismantleUIView` | `AndroidView` 的 `factory`／`update`，清理用 `onRelease`（較新的版本）；`ComposeView` 要設定適當的 `ViewCompositionStrategy` |
| 導覽 | FlowController 加 `UINavigationController`；iOS 16 起有 `NavigationStack` | Navigation Component（XML 的 nav graph）或 Navigation Compose |
| 狀態還原 | `NavigationPath` 編碼、`SceneDelegate` 還原 | `SavedStateHandle`、`rememberSaveable`、Navigation 的 back stack 還原 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 混合式架構 | hybrid architecture（SwiftUI + UIKit） | ハイブリッド構成 |
| Coordinator 模式（負責導覽的設計方式） | Coordinator pattern／FlowController | コーディネーターパターン／FlowController |
| view controller 的父子嵌套 | view controller containment | ビューコントローラのコンテインメント |
| 冪等 | idempotent | 冪等 |
| 向下部署 | back-deployment | バックデプロイ |
| 深層連結 | deep link | ディープリンク |
| 最低支援版本 | deployment target | デプロイメントターゲット |
