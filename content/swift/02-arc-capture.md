---
id: swift-arc-capture
title: ARC 與記憶體洩漏：retain cycle、weak／unowned 怎麼選、怎麼找出洩漏
summary: 這類職缺常要求記憶體與效能，這是 iOS 面試最常考的記憶體題。這個單元說明 retain cycle 是怎麼形成的、weak 和 unowned 差在哪、實務上哪些寫法最容易洩漏，以及用什麼工具找出來。
priority: P0
day: 1
tags: [arc, weak, unowned, capture-list, combine, wkwebview, memory]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 什麼是 retain cycle？為什麼 Swift 會有、Android 比較不用擔心？
2. `weak` 和 `unowned` 差在哪？什麼時候用哪個？
3. closure 是怎麼「帶走」外面的變數的？`[weak self]` 到底做了什麼？
4. 實務上哪些寫法最容易造成 retain cycle？
5. WKWebView 和 JavaScript 溝通時，為什麼容易洩漏？
6. 懷疑有記憶體洩漏時，怎麼找出來？

最後補充一個比較少考、但被問到要答得出來的主題：`autoreleasepool`。

### 1. 什麼是 retain cycle？

上一個單元提過，Swift 用 ARC（Automatic Reference Counting）管理 class 物件的記憶體：每多一個地方用一般的參考（strong 參考）持有物件，引用計數就 +1；計數歸零時，物件才會被釋放，`deinit` 才會執行。

問題出在**兩個物件互相持有**的時候：A 持有 B，B 也持有 A。即使外面已經沒有人用它們了，A 的計數至少是 1（B 抓著它），B 的計數也至少是 1（A 抓著它），兩個都永遠不會歸零，記憶體永遠不會被釋放。這就是 retain cycle（循環參考），也是 iOS 記憶體洩漏最常見的原因。

在 Swift 裡，最常見的情況是「物件持有一個 closure，而 closure 又用到這個物件」：

```swift
final class ChatViewModel {
    var onMessage: ((String) -> Void)?
    var lastMessage = ""

    init() {
        onMessage = { text in
            self.lastMessage = text   // closure 裡用到 self，closure 就會 strong 持有 self
        }
    }

    deinit { print("ChatViewModel 被釋放了") }
}
```

- `ChatViewModel` 透過 `onMessage` 屬性持有這個 closure。
- closure 裡用到 `self`，所以 closure 也持有 `ChatViewModel`。
- 兩邊互相持有，使用者離開聊天畫面之後，`deinit` 永遠不會印出來。

修法是讓 closure **不要 strong 持有** self：

```swift
onMessage = { [weak self] text in
    self?.lastMessage = text   // self 已經被釋放的話，什麼都不做
}
```

**為什麼 Android 比較不用擔心？** Android（JVM）用的是垃圾回收（GC）：GC 會從「一定活著的地方」（例如全域變數、正在執行的執行緒）開始往下找，找不到的物件就回收，不管它們之間有沒有互相持有。所以 A、B 互相持有、但外面沒人用的情況，GC 還是會回收。Android 的洩漏通常是另一種：長壽的東西（單例、static 變數、沒移除的 listener）抓著 Activity 或 Context。

> [!TIP]
> 面試時可以這樣說：「ARC 是靠引用計數歸零來釋放物件，兩個物件互相 strong 持有，計數就永遠不會歸零，這就是 retain cycle。iOS 最常見的是物件持有 closure、closure 又捕獲了 self。Android 的 GC 會回收互相持有但沒人用的物件，所以那邊的洩漏通常是被單例或 static 抓住。」

### 2. `weak` 和 `unowned` 差在哪？什麼時候用哪個？

`weak` 和 `unowned` 都是「持有，但不讓引用計數 +1」，所以都能打斷 retain cycle。差別在**物件被釋放之後**：

| | `weak` | `unowned` |
|---|---|---|
| 型別 | 一定是 Optional（`self?`） | 不是 Optional，可以直接用（`self.`） |
| 物件被釋放後 | 自動變成 `nil`，讀到 `nil` | 不會變，再讀取就**直接 crash** |
| crash 訊息 | 不會 crash | `Attempted to read an unowned reference but object ... was already deallocated` |

**什麼時候可以用 `unowned`？** 只有在你能確定「closure 被執行的時候，self 一定還活著」的時候。最典型的例子是：closure 被 self 自己擁有的子物件持有，而且這個子物件不會被傳到外面。

```swift
final class OrderViewModel {
    // formatter 只有 OrderViewModel 持有。ViewModel 被釋放時，formatter 和它的 closure 也一起消失，
    // 所以這個 closure 執行時，self 一定還在
    lazy var formatter = PriceFormatter(onError: { [unowned self] error in
        self.showError(error)
    })

    func showError(_ error: Error) { /* ... */ }
}
```

**什麼時候一定要用 `weak`？** closure 被「外面、活得比較久的東西」持有時：網路請求的 completion handler、單例（例如 `SocketManager.shared`）、`NotificationCenter`。這些 closure 可能在使用者離開畫面之後才被呼叫，如果用 `unowned`，self 已經被釋放，App 就 crash。這種 crash 只在特定時機出現（例如網路慢、使用者剛好退出畫面），很難重現。

**那全部都用 `weak` 好不好？** 不會 crash，但也有缺點：如果物件比你預期的更早被釋放，回呼就會默默什麼都不做，畫面沒更新也沒有任何錯誤，bug 反而更難發現。所以比較好的做法是先想清楚「誰擁有誰」，再決定用哪一個。

> [!TIP]
> 面試時可以這樣說：「我依擁有關係決定。closure 只被 self 自己的子物件持有、self 一定比它活得久，才用 unowned；網路、單例、通知這種外部持有的 closure，一律用 weak。我不會無腦全部加 [weak self]，因為那會把生命週期的問題藏起來。」

### 3. closure 是怎麼帶走外面的變數的？

closure 用到外面的變數時，會把它「捕獲」（capture）進來。Swift 預設捕獲的是**變數本身**，不是當下的值：

```swift
var x = 1
let byRef = { print(x) }          // 捕獲的是變數 x 本身：closure 和外面共用同一個 x
let byValue = { [x] in print(x) } // 寫在 [ ] 裡：捕獲的是建立 closure 這一刻 x 的值
x = 2
byRef()     // 2
byValue()   // 1
```

開頭的 `[ ]` 叫做 capture list，用來明確指定「這個變數要怎麼帶進去」。`[weak self]` 也是同一個機制：在建立 closure 的這一刻，用 weak 的方式帶走 self，而不是預設的 strong。

兩個常見的誤解：

**誤解一：所有 closure 都要加 `[weak self]`。** 只有「被存起來、之後才執行」的 closure 才可能造成 retain cycle，例如存成屬性、交給 Task、Combine 的訂閱。`map`、`filter` 這種當下就執行完的 closure，或 `UIView.animate` 這種不會被 self 存起來的 closure，不會造成循環。

**誤解二：寫了 `[weak self]` 就一定沒事。** `guard let self else { return }` 之後，從那一行到 closure 結束，self 都是 strong。如果 closure 裡面是一段很長、甚至不會結束的流程，self 就會被抓住一整段時間：

```swift
task = Task { [weak self] in
    guard let self else { return }    // 從這裡開始 self 是 strong
    for await price in stream {       // stream 不結束，這個迴圈就不結束
        self.handle(price)            // self 在整個迴圈期間都被抓住，無法釋放
    }
}
```

正確的寫法是在**每次迴圈裡**才取出 self，處理完這一筆就放開：

```swift
task = Task { [weak self] in
    for await price in stream {
        guard let self else { return }   // 每一輪才取出 self，這一輪結束就放開
        self.handle(price)
    }
}
```

> [!TIP]
> 面試時可以這樣說：「只有被存起來、之後才執行的 closure 會造成循環，所以我不會每個 closure 都加 weak self。另外要注意 guard let self 之後就是 strong，在長時間跑的 Task 裡，我會在每次迴圈裡才 guard，而不是在最外面。」

### 4. 實務上哪些寫法最容易造成 retain cycle？

規則只有一條：**一個活得很久的東西持有 closure，而 closure 又持有 self**，就會形成循環。下面是面試和實務最常見的幾種。

**(1) Combine 的 `sink` 加上 `store(in:)`**

```swift
feed.sink { value in
    self.last = value                 // closure 持有 self
}
.store(in: &cancellables)             // self 持有 cancellables，cancellables 持有這個訂閱，訂閱持有 closure
```

繞了一圈又回到 self。修法：`sink { [weak self] value in self?.last = value }`。

**(2) Combine 的 `assign(to:on:)`**

```swift
feed.assign(to: \.last, on: self)     // assign 會 strong 持有 on 後面的物件
    .store(in: &cancellables)
```

這個寫法沒有 closure，但 `assign(to:on:)` 本身就會 strong 持有 `self`，一樣形成循環。iOS 14 之後改用 `feed.assign(to: &$last)`，直接綁到 `@Published` 屬性上，不會持有 self。

**(3) `Task { }`**

Task 的 closure 會一直持有它用到的東西，直到 Task 執行結束。如果裡面是 `for await` 這種可能永遠不會結束的迴圈，self 就永遠不會被釋放。而且 Swift 允許你在 Task 的 closure 裡**省略 `self.`**，所以寫 `Task { handle(tick) }` 時，你可能根本沒注意到它抓住了 self。

```swift
final class TickerStream {
    private var task: Task<Void, Never>?

    func start(_ stream: AsyncStream<Int>) {
        task = Task { [weak self] in
            for await tick in stream {
                guard let self else { return }   // 每一輪才取出 self
                self.handle(tick)
            }
        }
    }

    func handle(_ tick: Int) {}
    deinit { task?.cancel() }   // 沒有循環時，deinit 才會被執行到，才能取消 Task
}
```

在 SwiftUI 裡，更好的做法是用 `.task { }` modifier：view 消失時，SwiftUI 會自動取消這個 Task。

**(4) 會重複執行的 `Timer`**

`Timer.scheduledTimer(withTimeInterval:repeats: true)` 建立的 timer 會被主執行緒的 RunLoop（不斷處理事件的迴圈）持有，timer 又持有你的 closure 或 target。除非呼叫 `invalidate()`，否則 timer 和它抓住的 self 永遠不會被釋放。修法：在畫面離開時呼叫 `invalidate()`，closure 裡用 `[weak self]`。

**(5) `NotificationCenter` 的 closure 版 observer**

`addObserver(forName:object:queue:using:)` 會讓 NotificationCenter 一直持有你的 closure，直到你呼叫 `removeObserver(token)`。修法：保存回傳的 token 並在適當時機移除，closure 裡用 `[weak self]`。

**(6) delegate**

如果 A 持有 B，B 的 `delegate` 又指回 A，兩邊都是 strong 就形成循環。所以 delegate 屬性要宣告成 `weak var delegate: SomeDelegate?`（protocol 要限定 `AnyObject` 才能用 weak）。

**(7) `URLSession` 的 delegate**

`URLSession(configuration:delegate:delegateQueue:)` 會**強持有** delegate（這是文件寫明的特例），直到你呼叫 `invalidateAndCancel()` 或 `finishTasksAndInvalidate()`。把 ViewModel 設成 delegate 又沒有 invalidate，ViewModel 就永遠不釋放，deinit 裡寫的 invalidate 也永遠跑不到。修法：在畫面離開時（不是 deinit）invalidate；或用 `URLSession.shared` 加 async API，不要自己當 delegate。

> [!TIP]
> 面試時可以這樣說：「最常見的循環來源是 Combine 的 sink 加 store、assign(to:on:)、Task 裡的無限迴圈、repeating Timer、NotificationCenter，還有 URLSession 強持有 delegate。判斷方法都一樣：有沒有一個活很久的東西持有 closure，closure 又持有 self。」

### 5. WKWebView 和 JavaScript 溝通時，為什麼容易洩漏？

讓網頁裡的 JavaScript 呼叫原生程式碼，要註冊一個 script message handler：

```swift
config.userContentController.add(handler, name: "delayWall")
```

`userContentController` 會 **strong 持有** 這個 handler，而 WebView 又持有 `userContentController`。如果 handler（通常是 Coordinator）為了呼叫 `evaluateJavaScript` 又持有了 WebView，就形成循環：WebView → userContentController → handler → WebView。就算沒有循環，handler 也會跟著 WebView 一起活著，不會在你預期的時候釋放。

在 SwiftUI 的 `UIViewRepresentable` 裡，正確的做法是在 `dismantleUIView`（SwiftUI 把這個 view 移除時會呼叫的方法）裡把 handler 移除：

```swift
import SwiftUI
import WebKit

struct ChartWebView: UIViewRepresentable {
    let html: String
    var onSubscribe: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onSubscribe: onSubscribe) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(context.coordinator, name: "delayWall")   // 註冊：會 strong 持有 coordinator
        let web = WKWebView(frame: .zero, configuration: config)
        web.loadHTMLString(html, baseURL: nil)
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    // SwiftUI 移除這個 view 時呼叫：在這裡解除註冊
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "delayWall")
    }

    final class Coordinator: NSObject, WKScriptMessageHandler {
        let onSubscribe: () -> Void
        init(onSubscribe: @escaping () -> Void) { self.onSubscribe = onSubscribe }

        func userContentController(_ controller: WKUserContentController,
                                   didReceive message: WKScriptMessage) {
            if message.name == "delayWall" { onSubscribe() }
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「WKUserContentController 會 strong 持有 script message handler，所以我在 SwiftUI 的 dismantleUIView 裡移除 handler。我之前做的即時行情 App，K 線圖就是用 WebView 載入 TradingView Lightweight Charts，我就是這樣處理的。」

### 6. 懷疑有記憶體洩漏時，怎麼找出來？

面試官常問「你怎麼發現和處理記憶體洩漏」，回答時要有一套順序：

1. **先確認有沒有釋放：在 `deinit` 印 log。** 離開畫面後，該消失的 ViewModel、ViewController 應該要印出 deinit log。沒有印，就代表還有東西抓著它。
2. **找出是誰抓著它：Xcode 的 Memory Graph Debugger。** App 執行中，點 Xcode 下方除錯列的 Debug Memory Graph 按鈕（三個小圓圈連在一起的圖示），左邊會列出記憶體裡所有的物件。點選應該已經消失的物件，右邊會畫出是哪些物件一路抓著它。Xcode 判斷是洩漏的物件，會標上紫色驚嘆號。在 scheme 打開 Malloc Stack Logging，還能看到這個物件是在哪一行程式建立的。
3. **量化問題：Instruments。** 用 Leaks 工具找出已經沒有人能用到、卻沒被釋放的記憶體；用 Allocations 工具反覆進出同一個畫面，每次進出前按 Mark Generation，比較每一輪多出來的物件。如果每進出一次就多一組 ViewModel，就是洩漏。
4. **修完之後，加測試防止再發生：**

```swift
func testChatViewModelIsReleased() {
    var vm: ChatViewModel? = ChatViewModel()
    weak var weakVM = vm   // weak 參考不會讓計數 +1，只用來觀察
    vm = nil               // 放掉唯一的 strong 參考
    XCTAssertNil(weakVM)   // 如果還有 retain cycle，物件不會被釋放，weakVM 就不是 nil
}
```

> [!TIP]
> 面試時可以這樣說：「我會先看 deinit log 確認物件有沒有被釋放；沒有的話用 Memory Graph 找出是誰抓著它，必要時用 Instruments 的 Allocations 反覆進出畫面確認。修完後寫一個測試：用 weak 變數指向物件，放掉 strong 參考後斷言它變成 nil。」

### 7. autoreleasepool（比較少考，被問到能答就好）

`autoreleasepool` 是 Objective-C 留下來的機制。有些 Objective-C API 回傳的物件，不會在用完時馬上釋放，而是先放進一個「autorelease pool」，等到這個 pool 被清空時才一起釋放。主執行緒每處理完一輪事件（RunLoop 轉一圈），就會清空一次。

一般情況下你不需要管它。只有一種情況要注意：**在同一個函式裡跑一個很長的迴圈，而且迴圈裡呼叫了 Objective-C 寫成的 API**（例如 `UIImage`、`NSString`、`FileManager`）。這時迴圈還沒跑完，pool 不會被清空，暫存的物件會一直累積，記憶體用量衝得很高，嚴重時 App 會因為記憶體不足被系統終止。

```swift
for url in imageURLs {            // 例如一次處理 1000 張圖片
    autoreleasepool {
        let image = UIImage(contentsOfFile: url.path)
        let thumbnail = makeThumbnail(image)
        save(thumbnail)
    }                             // 每處理完一張，就清空這一輪產生的暫存物件
}
```

純 Swift 的物件不會進 autorelease pool，所以純 Swift 的迴圈不需要這樣包。

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift02ARC**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift02-ARC/`，解答在 `labs/ios/Solution/Swift/Swift02-ARC/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **看洩漏**：多數演練的畫面上方有「打開畫面」「關閉畫面」兩個按鈕和「存活中」的數字，關閉後數字沒有回到 0，就是洩漏。從 Xcode 執行後，可以按除錯列的 Debug Memory Graph（三個小圓圈相連的圖示）看是誰抓著它。

### 演練 1：關掉畫面後 ViewModel 沒有被釋放（第 1 題）

**檔案**：`L01_ClosureCycle.swift`

**情境**：聊天室的 ViewModel 把「收到訊息時要做的事」存成 closure 屬性，closure 裡用到 `self`。

**怎麼寫**

1. **Bug 版**：在 `ChatViewModel` 的 `init` 裡給 `onMessage` 一個 closure，收到文字就寫進 `self.lastMessage`，直接用 `self`、不加 capture list。
2. **修正版**：同樣的 closure，capture list 改成 `[weak self]`，用 `self?.` 存取。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後，存活中的 `ChatViewModel` 仍是 1，weak 參考還在；Memory Graph 上看得到 ViewModel 和 closure 互相持有 |
| 修正版 | 關閉畫面後存活數回到 0，weak 參考變成 nil |

**為什麼**：ViewModel 透過 `onMessage` 持有 closure，closure 用到 `self` 又持有 ViewModel，兩邊的引用計數都不會歸零，`deinit` 永遠不會執行。`[weak self]` 讓 closure 不增加引用計數。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L01_ClosureCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L01_ClosureCycle.swift#fixed
```

### 演練 2：下單後馬上離開畫面，App 當掉（第 2 題）

**檔案**：`L02_UnownedCrash.swift`

**情境**：下單畫面按「送出」，ViewModel 呼叫交易所 API，回應 0.3 秒後回來。使用者按完送出就立刻離開畫面。API 物件（`ExchangeAPI.shared`）是單例，活得比任何畫面都久。

**怎麼寫**

1. **Bug 版**：`submit` 的回呼用 `[unowned self]`，回應回來時把結果寫進 `self.status`。**從 Xcode 執行**，才看得到 App 停在哪一行。
2. **修正版**：回呼改用 `[weak self]`，再用 `guard let self` 取出；取不到就印出「回應回來時畫面已經關閉，什麼都不做」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 約 0.3 秒後 App 當掉，主控台：`Fatal error: Attempted to read an unowned reference but object … was already deallocated`（這個版本會當掉，所以自動檢查略過它） |
| 修正版 | 印出「回應回來時畫面已經關閉，什麼都不做」，接著印出「跑到這裡代表沒有當掉」 |

**為什麼**：這個 closure 是網路層的單例持有的，回應回來時 ViewModel 早就被釋放了。`unowned` 不會在物件消失時變成 nil，再讀取就直接當掉；`weak` 會變成 nil，可以安全地什麼都不做。closure 被「活得比畫面久的東西」持有時，一律用 `weak`。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L02_UnownedCrash.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L02_UnownedCrash.swift#fixed
```

### 演練 3：確認下單的價格被偷偷換成新報價（第 3 題）

**檔案**：`L03_CaptureByReference.swift`

**情境**：使用者看到報價 1500 萬，按「下單」，App 先跳出確認視窗，使用者按「確認」才真的送出。使用者看確認視窗的這段時間，新的推播進來，報價變成 1510 萬。

**怎麼寫**

1. **Bug 版**：寫一個 `confirm` closure，印出「按下確認，送出委託，價格 …」，裡面直接用 `price`、不加 capture list。
2. **修正版**：同樣的 closure，在 `{` 後面加 capture list `[price]`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 最後一行是「送出委託，價格 15100000」：送出的是新報價，不是使用者看到的 1500 萬 |
| 修正版 | 最後一行是「送出委託，價格 15000000」 |

**為什麼**：closure 預設捕獲的是變數本身，不是當時的值，所以它讀到的是呼叫時最新的 `price`。capture list `[price]` 在建立 closure 的那一刻把值複製進去，之後 `price` 怎麼變都不影響。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L03_CaptureByReference.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L03_CaptureByReference.swift#fixed
```

### 演練 4：寫了 `[weak self]`，離開畫面還是被抓住（第 3 題）

**檔案**：`L04_GuardLetSelf.swift`

**情境**：行情畫面的 ViewModel 用 `Task` 一直收價格推播（共 24 筆，每 50 毫秒一筆，約 1.2 秒），使用者開了畫面 0.2 秒就離開。

**怎麼寫**

1. **Bug 版**：`start` 裡用 `Task { [weak self] in … }`，一開始就 `guard let self else { return }`，再用 `for await price in stream` 把價格寫進 `lastPrice`。
2. **修正版**：同樣的 `Task`，但 `guard let self` 改放進 `for await` 迴圈裡面，每一輪才取出 `self`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後存活中的 `TickerViewModel` 仍是 1（weak 參考還在），要等價格流播完才變成 0 |
| 修正版 | 關閉畫面後馬上是 0（weak 參考已變成 nil） |

**為什麼**：`guard let self` 之後，`self` 在整個 closure 剩下的時間都是 strong。迴圈不結束，ViewModel 就一直被抓住。改成每一輪才 `guard let self`，這一輪結束就放開，ViewModel 一離開畫面就能被釋放，下一筆推播進來時 `guard` 失敗，Task 自己結束。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L04_GuardLetSelf.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L04_GuardLetSelf.swift#fixed
```

### 演練 5：Combine 的 `sink` 讓 ViewModel 釋放不掉（第 4 題）

**檔案**：`L05_CombineSink.swift`

**情境**：行情畫面的 ViewModel 訂閱價格推播，每收到一筆就更新 `last`，訂閱存在自己的 `cancellables` 裡。

**怎麼寫**

1. **Bug 版**：`init` 裡用 `feed.sink { … }`，closure 直接用 `self` 把 `value` 寫進 `last`，再 `.store(in: &cancellables)`。
2. **修正版**：同樣的 `sink`，closure 改用 `[weak self]`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後，存活中的 `PriceViewModel` 仍是 1，weak 參考還在 |
| 修正版 | 收到推播後 `last = 15000000`，關閉畫面後存活數回到 0 |

**為什麼**：繞了一圈：ViewModel 持有 `cancellables`，`cancellables` 持有訂閱，訂閱持有 closure，closure 又持有 ViewModel。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L05_CombineSink.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L05_CombineSink.swift#fixed
```

### 演練 6：沒有 closure 也會洩漏：`assign(to:on:)`（第 4 題）

**檔案**：`L06_CombineAssign.swift`

**情境**：ViewModel 的 `last` 要跟著價格推播更新，用 `assign` 把推播綁到 `last` 上。

**怎麼寫**

1. **Bug 版**：`init` 裡用 `feed.assign(to: \.last, on: self)`，再 `.store(in: &cancellables)`。
2. **修正版**：改用 `feed.assign(to: &$last)`，直接綁到 `@Published` 屬性，不需要自己存 `cancellables`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後，存活中的 `PriceViewModel` 仍是 1，weak 參考還在 |
| 修正版 | 收到推播後 `last = 15000000`，關閉畫面後存活數回到 0 |

**為什麼**：這段程式沒有任何 closure，但 `assign(to:on:)` 本身就會 strong 持有 `on:` 後面的物件，一樣繞成一圈。`assign(to: &$last)`（iOS 14 起）不持有 `self`。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L06_CombineAssign.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L06_CombineAssign.swift#fixed
```

### 演練 7：關掉畫面後 Timer 還在跑（第 4 題）

**檔案**：`L07_TimerCycle.swift`

**情境**：行情畫面每 0.1 秒用 `Timer` 更新一次報價。`deinit` 裡已經寫了 `invalidate()`，以為關畫面時 Timer 會被停掉。

**怎麼寫**

1. **Bug 版**：`init` 裡用 `Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true)`，closure 直接用 `self` 呼叫 `refresh()`。
2. **修正版**：同樣的 Timer，closure 改用 `[weak self]`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後存活數仍是 1；關掉畫面 0.5 秒後，Timer 又觸發了約 5 次（本機實測 6 次），而且會一直跑下去 |
| 修正版 | 關閉畫面後存活數是 0；關掉畫面之後 Timer 觸發 0 次 |

**為什麼**：RunLoop（主執行緒不斷處理事件的迴圈）持有 Timer，Timer 持有 closure，closure 持有 `self`。ViewModel 永遠不會釋放，所以 `deinit` 裡的 `invalidate()` 根本不會執行，這行就是白寫。closure 改用 `[weak self]` 之後，ViewModel 離開畫面就能釋放，`deinit` 才會執行、Timer 才會跟著停。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L07_TimerCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L07_TimerCycle.swift#fixed
```

### 演練 8：關掉畫面後通知還在更新畫面（第 4 題）

**檔案**：`L08_NotificationCycle.swift`

**情境**：資產畫面的 ViewModel 監聽「報價更新」通知，每收到一次就呼叫 `refresh()`。

**怎麼寫**

1. **Bug 版**：`init` 裡用 `addObserver(forName:object:queue:using:)` 監聽 `priceUpdated`，closure 直接用 `self` 呼叫 `refresh()`，不保存回傳的 token。
2. **修正版**：closure 用 `[weak self]`，把回傳值存進 `token`，並在 `deinit` 裡呼叫 `removeObserver` 移除。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關閉畫面後存活數仍是 1；畫面關掉後再發一次通知，`refresh` 累計被呼叫 2 次 |
| 修正版 | 關閉畫面後存活數是 0；畫面關掉後再發通知，`refresh` 累計還是 1 次 |

**為什麼**：closure 版的 observer 由 `NotificationCenter` 一直持有，直到呼叫 `removeObserver(token)`。closure 又用到 `self`，所以 ViewModel 離開畫面後還活著，通知一來還在處理。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L08_NotificationCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L08_NotificationCycle.swift#fixed
```

### 演練 9：delegate 沒寫 `weak`，兩個物件互相抓住（第 4 題）

**檔案**：`L09_DelegateCycle.swift`

**情境**：下單畫面的 ViewModel 建立一條連線 `OrderSocket`，把自己設成連線的 delegate，連線有狀態時通知 ViewModel。

**怎麼寫**

1. **Bug 版**：`OrderSocket` 的 `delegate` 已經是一般的（strong）屬性，在 ViewModel 的 `init` 裡把 `socket.delegate` 設成自己。
2. **修正版**：把 `delegate` 屬性宣告成 `weak var`（protocol 已經限定 `AnyObject`），`init` 一樣設定 delegate。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 連線後 `connected = true`；關閉畫面後存活中的 `OrderViewModel` 仍是 1 |
| 修正版 | 連線後 `connected = true`；關閉畫面後存活數回到 0 |

**為什麼**：ViewModel 持有連線，連線的 `delegate` 又 strong 指回 ViewModel。delegate 屬性要宣告成 `weak`，protocol 要限定 `AnyObject` 才能這樣宣告。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L09_DelegateCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L09_DelegateCycle.swift#fixed
```

### 演練 10：關掉圖表畫面後 WebView 還在記憶體裡（第 5 題）

**檔案**：`L10_WebViewHandler.swift`

**情境**：K 線圖用 `WKWebView` 載入網頁圖表，網頁用 `window.webkit.messageHandlers.delayWall.postMessage(…)` 通知 App。Coordinator 接收訊息，也為了之後呼叫 `evaluateJavaScript` 而持有 WebView。演練用 `makeWebView`、`dismantleWebView` 兩個函式模擬 SwiftUI `UIViewRepresentable` 的 `makeUIView`、`dismantleUIView`。

**怎麼寫**

1. **Bug 版**：`makeWebView` 裡建立 `WKWebViewConfiguration`，用 `userContentController.add(self, name: "delayWall")` 註冊 handler，再建立 `WKWebView`、載入 `chartHTML`，並存進 `webView`。`dismantleWebView` 什麼都不做。
2. **修正版**：`makeWebView` 和 Bug 版一樣（已寫好），在 `dismantleWebView` 裡對 `userContentController` 呼叫 `removeScriptMessageHandler(forName:)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 網頁傳來「subscribe」；關閉畫面後存活中的 `ChartCoordinator` 仍是 1，Memory Graph 上看得到 WebView → userContentController → Coordinator → WebView 一圈 |
| 修正版 | 網頁傳來「subscribe」；關閉畫面後存活數回到 0 |

**為什麼**：`userContentController` 會 strong 持有註冊的 handler，WebView 持有 `userContentController`，Coordinator 又持有 WebView，繞成一圈。解除註冊就打斷了最後一段。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L10_WebViewHandler.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L10_WebViewHandler.swift#fixed
```

### 演練 11：洩漏檢查永遠說有洩漏（第 6 題）

**檔案**：`L11_LeakTest.swift`

**情境**：想寫一個檢查函式 `isLeaked`，防止洩漏再發生：建立物件、放掉自己的參考，用 `weak` 參考看物件是不是還活著。有兩個 ViewModel：`CleanViewModel` 沒問題，`LeakyViewModel` 的 closure 用到 `self`（有 retain cycle）。

**怎麼寫**

1. **Bug 版**：`isLeaked` 裡用 `let object = make()` 建立物件，建立 `weak` 參考指向它，直接回傳 `weak` 參考是不是還在。
2. **修正版**：用 Optional `var` 接住 `make()` 的結果，建立 `weak` 參考後，把 `object` 設成 `nil`，再回傳 `weak` 參考是不是還在。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `CleanViewModel：洩漏`（誤報）、`LeakyViewModel：洩漏`：分不出差別 |
| 修正版 | `CleanViewModel：已釋放`、`LeakyViewModel：洩漏` |

**為什麼**：函式裡的 `let object` 一直抓著物件，`weak` 參考不可能變成 nil（Debug 與 Release 建置實測的結果相同）。要把自己持有的 strong 參考放掉，才看得出是不是還有別人抓著。實務上把這個函式放進 XCTest，用 `XCTAssertFalse` 當作防止洩漏再發生的測試。找洩漏的前兩步（`deinit` 印 log、Memory Graph 看是誰抓著）在演練 1 到演練 10 練過了。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L11_LeakTest.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L11_LeakTest.swift#fixed
```

### 演練 12：批次處理 100 張圖，記憶體衝到幾百 MB（第 7 題）

**檔案**：`L12_Autoreleasepool.swift`

**情境**：相簿 App 一次替 100 張圖片產生縮圖並轉成 JPEG。每張圖用 `UIGraphicsImageRenderer` 畫出來，再呼叫 `jpegData`。

**怎麼寫**

1. **Bug 版**：在迴圈裡呼叫 `makeThumbnail(index)`，回傳值大於 0 就把 `processed` 加 1，不要包 `autoreleasepool`。
2. **修正版**：同樣的事，但整段包進 `autoreleasepool { }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 處理了 100 張圖片，記憶體高峰比開始時多好幾百 MB（Debug 建置、本機模擬器實測約 385 MB） |
| 修正版 | 處理了 100 張圖片，高峰只多幾 MB（實測 0 MB，一張圖的大小約 4 MB 量不太出來） |

**為什麼**：UIKit 這類 Objective-C API 回傳的物件，會先放進 autorelease pool，等 pool 被清空才真的釋放。迴圈在同一個工作裡跑完之前 pool 不會清空，100 張圖的暫存物件全堆在一起。每一輪包一個 `autoreleasepool`，這一輪結束就清空。純 Swift 的物件不會進 autorelease pool，不需要這樣包。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L12_Autoreleasepool.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L12_Autoreleasepool.swift#fixed
```

### 演練 13：關掉畫面後 `URLSession` 還抓著 ViewModel（第 4 題）

**檔案**：`L13_URLSessionDelegate.swift`

**情境**：下單畫面的 ViewModel 自己建立一個 `URLSession`，把自己設成 `delegate`（接收事件通知的物件）來接收報價資料。`deinit` 裡已經寫了 `invalidateAndCancel()`（讓 session 失效並取消進行中的請求），以為關畫面時 session 會被收掉。演練用自訂的 `URLProtocol` 攔截請求，直接回一筆假報價，不會真的打網路。

**怎麼寫**

1. **Bug 版**：`init` 裡用 `URLSession(configuration:delegate:delegateQueue:)` 建立 session（設定用 `makeQuoteConfiguration()`），`delegate` 設成 `self`。離開畫面時什麼都不做（`screenDidDisappear` 已經是空的）。
2. **修正版**：session 的建立和 Bug 版一樣（已寫好），在 `screenDidDisappear`（對應 SwiftUI 的 `onDisappear`、UIKit 的 `viewDidDisappear`）裡呼叫 `session?.invalidateAndCancel()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面開著時，delegate 收到 1 筆報價；關閉畫面後，存活中的 `QuoteViewModel` 仍是 1，weak 參考還在 |
| 修正版 | 畫面開著時，delegate 收到 1 筆報價；關閉畫面後存活數回到 0，weak 參考變成 nil |

**為什麼**：`URLSession` 對 `delegate` 是 strong 參考。官方文件寫明：session 會一直持有 delegate，直到 session 被 invalidate（失效）或 App 結束。ViewModel 又持有 session，繞成 ViewModel → session → ViewModel 一圈，所以 `deinit` 裡的 `invalidateAndCancel()` 在有循環時永遠不會執行，這行就是白寫（和演練 7 的 Timer 一樣）。離開畫面時就 invalidate，session 會通知 delegate 它結束了，然後放掉 delegate，循環就斷了。`finishTasksAndInvalidate()` 是另一種選擇：讓進行中的請求跑完再失效，適合不想中斷的請求。規則和演練 7、8 一樣：活得很久的東西（這裡是 session）持有 `self`。沒有設定 delegate 的 session（例如 `URLSession.shared`）不會有這個問題。

```lab-solution
ios/Solution/Swift/Swift02-ARC/L13_URLSessionDelegate.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift02-ARC/L13_URLSessionDelegate.swift#fixed
```

## 常見陷阱

- **在外部持有的 closure 用 `[unowned self]`。** 網路回呼、單例、通知的 closure 可能在 self 被釋放後才執行，這時讀取 unowned 會直接 crash，而且只在特定時機發生，很難重現。這種情況要用 `weak`。
- **`[weak self]` 之後立刻 `guard let self`，接著跑一段很長的流程。** 從 guard 那行開始 self 就是 strong，長時間的 Task 會一直抓著 self。要在每次迴圈裡才 guard。
- **以為 Task 的變數被丟掉，Task 就會停止。** 把 `task` 變數設成 `nil` 不會取消 Task，它會繼續執行，也繼續抓著它捕獲的東西。要明確呼叫 `cancel()`，或用 SwiftUI 的 `.task { }` 讓 view 消失時自動取消。
- **`sink` 之後忘了 `store(in:)`。** 這是相反的錯誤：沒有人持有這個訂閱，它會立刻被取消，畫面收不到任何更新。
- **以為 SwiftUI 的 View 是 struct，所以不會有 retain cycle。** View 本身是 struct 沒錯，但 ViewModel（class）裡的 closure 屬性、Combine 訂閱一樣會形成循環。

## 面試官想聽到的

1. 能用自己的話解釋 retain cycle：兩個物件互相 strong 持有，引用計數永遠不會歸零。
2. 能依「誰擁有誰」說明 weak 和 unowned 的選擇，而且知道 unowned 在物件被釋放後讀取會 crash。
3. 能指出 Combine、Task、Timer、NotificationCenter、WKWebView 這些常見的循環來源，特別是 `assign(to:on:)` 和 Task 可以省略 `self.` 這兩個容易漏掉的地方。
4. 有一套找出洩漏的流程（deinit log → Memory Graph → Instruments），並且說得出修完之後怎麼防止再發生。

> [!WARNING]
> 面試官常追問：「你是不是每個 closure 都加 `[weak self]`？」不要只回答「對」。要說明你會依擁有關係判斷，並且知道 `guard let self` 之後 self 就變回 strong。

> [!FIELD]
> 在一個即時行情 App 裡，K 線圖是用 WebView 載入 TradingView Lightweight Charts，JavaScript 透過 `WKScriptMessageHandler` 呼叫原生程式碼。因為 `userContentController` 會 strong 持有 handler，所以在 SwiftUI 的 `dismantleUIView` 裡移除 handler。這是講記憶體管理時可以直接舉的實際例子。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 怎麼回收記憶體 | ARC：引用計數歸零就釋放。**互相持有會洩漏** | GC：從全域變數、執行中的執行緒等地方往下找，找不到的就回收。互相持有沒關係 |
| 典型的洩漏 | closure 捕獲 self、delegate 沒宣告 weak、Timer、WebView handler | Activity 或 Context 被單例、static 變數持有；匿名內部類別；Handler 的延遲訊息；沒有取消的 coroutine |
| 不持有物件的參考 | `weak`、`unowned` | `WeakReference`；更常見的做法是綁定生命週期（`repeatOnLifecycle`、`viewLifecycleOwner`），畫面結束時自動停止 |
| 找洩漏的工具 | Memory Graph Debugger、Instruments（Leaks、Allocations）、`deinit` log | LeakCanary（debug build 自動偵測）、Android Studio 的 Memory Profiler |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 循環參考 | retain cycle | 循環参照 |
| 強參考／弱參考 | strong reference／weak reference | 強参照／弱参照 |
| 捕獲 | capture | キャプチャ |
| 記憶體洩漏 | memory leak | メモリリーク |
| 垃圾回收 | garbage collection（GC） | ガベージコレクション |
