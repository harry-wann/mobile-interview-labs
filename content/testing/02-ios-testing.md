---
id: testing-ios
title: iOS 測試工具：XCTest、Swift Testing、XCUITest、截圖比對
summary: 會用測試工具不稀奇，資深工程師的差別在於：非同步的程式怎麼測才不會卡住、UI 測試怎麼做到每次結果都一樣、截圖比對怎麼避免誤報，以及新的 Swift Testing 帶來什麼改變。
priority: P1
day: 5
tags: [testing, ios, xctest, swift-testing, xcuitest, snapshot]
---

## 重點講解

這個單元回答五個問題：

1. XCTest 和 Swift Testing 差在哪？要不要換？
2. 非同步的程式要怎麼測？
3. UI 測試（XCUITest）怎麼寫才會穩定？
4. 截圖比對測試適合測什麼？為什麼會誤報？
5. 還有哪些工具值得知道？

### 1. XCTest 和 Swift Testing 差在哪？要不要換？

**結論：Swift Testing 是 Xcode 16（2024 年）推出的新測試框架，寫法更簡潔、失敗訊息更清楚、預設平行執行。它和 XCTest 可以放在同一個 target 裡共存，所以可以新測試用新的寫、舊測試慢慢搬。UI 測試還是只能用 XCUITest。**

| | XCTest | Swift Testing |
|---|---|---|
| 怎麼宣告測試 | 繼承 `XCTestCase` 的 class，方法名稱以 `test` 開頭 | 在函式前面加 `@Test`，可以放在 struct 裡 |
| 怎麼檢查結果 | `XCTAssertEqual`、`XCTAssertTrue` 等幾十種 | 主要只有 `#expect(a == b)`：失敗時會自動顯示兩邊的值；`#require` 用來解開 Optional，失敗就中止這個測試 |
| 同一個測試跑多組資料 | 自己寫迴圈，失敗時不容易看出是哪一組 | `@Test(arguments: [...])`：每組資料當成獨立的測試回報 |
| 平行執行 | 可以設定依測試類別平行執行 | **預設平行**；需要依序執行時加 `.serialized` |
| 等待非同步事件 | `XCTestExpectation` 加 `fulfillment(of:)` | `confirmation { … }` |
| UI 測試 | XCUITest | 不支援，仍然用 XCUITest |

```swift
import Testing

struct PrecisionTests {
    // 兩組資料，各自是一個獨立的測試案例
    @Test(arguments: [
        ("1234.567", "0.01", "1234.56"),
        ("0.123456789", "0.0001", "0.1234"),
    ])
    func floors(value: String, step: String, expected: String) {
        let result = floorToStep(Decimal(string: value)!, step: Decimal(string: step)!)
        #expect(result == Decimal(string: expected)!)   // 失敗時會印出 result 和 expected 的實際值
    }
}
```

**預設平行要注意**：如果測試之間共用了單例或全域變數（例如 `UserDefaults.standard`、某個 `shared` 物件），平行執行時會互相干擾，出現時好時壞的失敗。解法是讓每個測試用自己的實例，或對那組測試加 `.serialized`。

> [!TIP]
> 面試時可以這樣說：「Swift Testing 和 XCTest 可以共存，所以我會新的邏輯測試用 Swift Testing 寫，用參數化測試跑多組精度資料；舊的 XCTest 不急著搬。要注意它預設平行執行，共用的單例會讓測試互相干擾。UI 測試仍然用 XCUITest。」

### 2. 非同步的程式要怎麼測？

**結論：測試本身寫成 `async`，用 `await` 等結果，不要用 `sleep` 等。時間相關的邏輯，把時鐘注入進去，測試時手動推進。**

幾個常見的情況：

- **XCTest 裡等待 expectation**：在 `async` 的測試方法裡，要用 `await fulfillment(of: [exp], timeout: 1)`（Xcode 14.3 起）。舊的 `wait(for:timeout:)` 是同步的，在 async 的環境裡呼叫會卡住目前的執行緒，可能造成死結（deadlock：兩邊都在等對方，永遠不會結束），編譯器也會警告。
- **測 `@MainActor` 的 ViewModel**：測試方法也標上 `@MainActor`，這樣就能直接存取 ViewModel 的屬性。
- **不要用 `Task.sleep` 等結果**：等太短會失敗、等太長又慢。更好的做法是讓被測的方法本身回傳可以 `await` 的結果；或把時間做成可以注入的 `Clock`，測試時手動往前推。
- **Combine 的 publisher**：可以轉成 `values`（一個 AsyncSequence），再用 `for await` 或 `await` 取得結果。

```swift
@MainActor
@Test func mergesTicksIntoLatestPrice() async {
    let (stream, continuation) = AsyncStream.makeStream(of: Decimal.self)
    let vm = PriceViewModel(prices: stream)       // 行情來源用可以手動送值的 stream 取代

    continuation.yield(100)
    continuation.yield(101)
    continuation.yield(102)
    continuation.finish()
    await vm.consumeAll()                         // 等 ViewModel 處理完，不用 sleep

    #expect(vm.latestPrice == 102)
}
```

> [!TIP]
> 面試時可以這樣說：「非同步的測試我一律用 await 等結果，不用 sleep。XCTest 在 async 方法裡要用 await fulfillment，不能用舊的 wait，否則可能卡住。和時間有關的邏輯，我會注入 Clock，測試時手動推進。」

### 3. UI 測試（XCUITest）怎麼寫才會穩定？

UI 測試最大的問題是「時好時壞」（flaky）。讓它穩定的方法：

- **用 `accessibilityIdentifier` 找元素，不要用畫面上的文字**：App 有多種語言，用「買入」這個字找按鈕，切到日文就找不到了。identifier 是開發者自己設定的固定名稱，不受語言影響。
- **啟動時注入 stub**：UI 測試啟動 App 時，透過 `launchArguments` 或 `launchEnvironment` 告訴 App「現在是 UI 測試」。App 啟動時看到這個參數，就把網路、推播、分析等換成固定回傳資料的假實作（stub）。這樣測試不依賴真的伺服器，每次結果都一樣。
- **用條件等待，不要 `sleep`**：`waitForExistence(timeout:)` 會等到元素出現為止，出現就立刻往下走。
- **固定環境**：關閉動畫、固定時間和語系。
- **直接進入要測的畫面**：不要每個測試都從登入開始點，那樣一個測試就要好幾分鐘。用啟動參數讓 App 直接進到要測的畫面和狀態。
- **Page Object**：把每個畫面的操作包成方法（例如 `orderPage.tapBuy()`），測試讀起來就像使用者的操作步驟，畫面改版時也只要改一個地方。

```swift
// UI 測試這一端
let app = XCUIApplication()
app.launchArguments = ["-uiTesting", "-startAt", "orderForm"]   // 告訴 App：用 stub、直接進到下單畫面
app.launch()
let buy = app.buttons["order.buyButton"]                       // 用 identifier 找按鈕
XCTAssertTrue(buy.waitForExistence(timeout: 5))
buy.tap()
```

> [!TIP]
> 面試時可以這樣說：「UI 測試我用 accessibilityIdentifier 找元素，不受語言影響；用啟動參數讓 App 換成 stub，不依賴真的伺服器，也直接進到要測的畫面；等待用 waitForExistence，不用 sleep。」

### 4. 截圖比對測試適合測什麼？為什麼會誤報？

截圖比對（snapshot test）是把畫面或元件畫成圖片，和之前存好、確認過的參考圖片比對。常用的工具是 Point-Free 的 swift-snapshot-testing。

**適合測**：一個元件的各種狀態，例如訂單簿的一列在「上漲閃爍」「下跌」「數字特別長」時長什麼樣子；多種語言的版面會不會擠壞；深色模式；使用者把字體調大（Dynamic Type）時會不會被切掉。這些用一般的單元測試很難檢查。

**為什麼會誤報**：同一個畫面，在不同的模擬器、不同的 iOS 版本上，字體的繪製可能有一兩個像素的差異，比對就失敗了，但其實畫面沒有問題。解法：

- 固定裝置尺寸、iOS 版本、語系、深色模式和字體大小。
- 參考圖只在 CI（Continuous Integration，持續整合，每次有人推程式碼就自動建置、跑測試的機制）上產生，不要本機產生、CI 比對。
- 設定可以容忍的像素差異比例。

> [!TIP]
> 面試時可以這樣說：「截圖比對適合測一個元件的各種狀態，例如不同語言、深色模式、字體放大之後的版面。它會誤報，是因為不同模擬器或 iOS 版本的字體繪製有細微差異，所以我會固定裝置、iOS 版本、語系和字體大小，參考圖只在 CI 上產生，並設定可以容忍的像素差異。」

### 5. 還有哪些工具值得知道？

- **Test Plan**：同一套測試，用不同的設定各跑一次。例如分別用日文、英文跑；或打開 Sanitizer 跑。
- **Thread Sanitizer**：執行測試時偵測多個執行緒同時存取同一份資料的問題（data race）。**Address Sanitizer**：偵測讀寫到不該存取的記憶體。
- **效能測試**：`measure(metrics:)` 量一段程式碼的執行時間或記憶體，例如「解析 1 萬筆成交要多久」，之後如果變慢，測試就會提醒。

> [!TIP]
> 面試時可以這樣說：「同一套測試我會用 Test Plan 以不同設定各跑一次，例如日文和英文。Thread Sanitizer 可以在測試時找出 data race，Address Sanitizer 可以找出讀寫到不該存取的記憶體。解析大量成交資料這類程式，我會用 `measure(metrics:)` 量時間，變慢時測試就會提醒。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Testing02iOSTesting**。這個單元只有 iOS；Android 的對應在 Testing03。
> **檔案**：你寫在 `labs/ios/Practice/Testing/Testing02-iOSTesting/`，解答在 `labs/ios/Solution/Testing/Testing02-iOSTesting/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **這個專案沒有 XCTest 或 XCUITest 的 target**：每個演練用一個很小的「迷你測試執行器」（`T02Suite`、`T02Expect`，在 `Testing02Support.swift`，已經寫好）模擬測試的通過與失敗，它可以依序或平行執行，也會記下「測試結束之後才出現的斷言失敗」。真正的 XCTest、Swift Testing、XCUITest 寫法放在每個演練的「為什麼」裡對照。

### 演練 1：單獨跑都通過，平行跑就有測試失敗（第 1 題）

**檔案**：`L01_SharedState.swift`

**情境**：三個測試都用 `T02PriceFormatter` 驗證價格顯示：「日圓顯示 ¥1,500,000」「美元顯示 $10,000」「日圓有千分位」。格式要看設定的幣別。每個測試先設定幣別，再（模擬載入資料）`await` 20 ms，最後比較格式化的結果。演練把同樣三個測試跑兩次：依序執行（XCTest 預設）、平行執行（Swift Testing 預設）。

**怎麼寫**

1. **Bug 版**：三個測試都用 App 共用的 `T02AppSettings.shared`（`runBothWays(log) { T02AppSettings.shared }`）。
2. **修正版**：每個測試用自己新建的 `T02AppSettings()` 實例（`runBothWays(log) { T02AppSettings() }`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「依序執行：跑了 3 個測試，通過 3、失敗 0」；「平行執行」有 1 到 2 個測試失敗，例如「預期 $10,000，實際 ¥1,500,000」（大家同時改同一個幣別，讀到別人設的值），每次失敗的是哪一個可能不同 |
| 修正版 | 依序與平行都是「通過 3、失敗 0」 |

**為什麼**：測試共用單例（`UserDefaults.standard`、`Settings.shared`）時，依序跑不會出事，因為一個測試跑完才輪到下一個；平行跑時，兩個測試的中間只要有 `await`，另一個測試就有機會在這個空檔改掉共用的值。Swift Testing 預設平行，所以這類「以前沒事、換框架就時好時壞」的測試會一次冒出來。根本的解法是讓每個測試用自己的實例；`@Suite(.serialized)` 可以讓一組測試依序執行，但只是把共用狀態的問題藏起來，測試之間仍然互相依賴。

```swift
import Testing

// 共用單例：平行執行時會互相干擾
@Test func formatsYen() async {
    AppSettings.shared.currency = .jpy
    await loadRates()                                   // 中間有 await，別的測試可能改掉 currency
    #expect(PriceFormatter().format(1_500_000) == "¥1,500,000")
}

// 每個測試用自己的實例
@Test func formatsYen() async {
    let settings = AppSettings(currency: .jpy)
    await loadRates()
    #expect(PriceFormatter(settings: settings).format(1_500_000) == "¥1,500,000")
}
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L01_SharedState.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L01_SharedState.swift#fixed
```

### 演練 2：程式壞了，測試卻顯示通過（第 2 題）

**檔案**：`L02_ForgotAwait.swift`

**情境**：`PriceViewModel.load()` 向伺服器載入價格（約 30 ms），載入完把價格存進 `price`。要驗證：`load()` 之後 `price` 是 15,000,000。演練把同一個測試，分別對「正確的實作」和「被改壞的實作」（`load()` 完忘了存價格，`price` 永遠是 0）各跑一次，全部測試結束後多等 150 ms，看有沒有「測試結束之後才出現的失敗」。

**怎麼寫**

1. **Bug 版**：`test()` 裡用 `Task { await vm.load(); t.equal(vm.price, 15_000_000) }`，不要 `await` 這個 `Task`。
2. **修正版**：`test()` 裡直接 `await vm.load()`，再 `t.equal(vm.price, 15_000_000)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「正確的實作」和「被改壞的實作」都是「通過 1、失敗 0、檢查了 0 項」；被改壞的實作另外印出「測試結束後才收到 1 個失敗，沒有被算進結果」：假的綠燈 |
| 修正版 | 「正確的實作：通過 1、失敗 0、檢查了 1 項」；「被改壞的實作：通過 0、失敗 1」（預期 15000000，實際 0） |

**為什麼**：測試函式回傳的那一刻，框架就決定「通過」或「失敗」了。`Task { }` 是另外一個非同步的工作，測試函式不會等它，所以斷言要到測試結束之後才執行；這時候失敗，不保證會被算進結果（行為依框架與版本而定），不能依賴它。「被改壞的實作也通過」才是關鍵：看起來有寫斷言、全部綠燈，實際上什麼都沒驗證到。XCTest 裡建立了 `XCTestExpectation` 卻沒有 `await fulfillment(of:)` 也是同一類問題。

```swift
// 假的綠燈：Task 沒有 await，測試函式馬上結束
@Test func loadsPrice() {
    let vm = PriceViewModel()
    Task {
        await vm.load()
        #expect(vm.price == 15_000_000)
    }
}

// 測試本身寫成 async，用 await 等結果
@Test func loadsPrice() async {
    let vm = PriceViewModel()
    await vm.load()
    #expect(vm.price == 15_000_000)
}
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L02_ForgotAwait.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L02_ForgotAwait.swift#fixed
```

### 演練 3：UI 測試的價格每次都不一樣，還要先登入（第 3 題）

**檔案**：`L03_LaunchArguments.swift`

**情境**：UI 測試在每次啟動 App 時傳 `launchArguments`：`["-uiTesting", "-startAt", "orderForm"]`（意思是：用 stub，直接進到下單畫面）。測試驗證下單畫面顯示的 BTC 價格是 15,000,000。`makeApp(arguments:)` 是 App 啟動時決定「用哪個 API、從哪個畫面開始」的地方。真的伺服器（`T02LiveAPI`）行情一直在動；從登入畫面走到下單畫面要 4 個步驟。演練把同一個 UI 測試跑 3 次。

**怎麼寫**

1. **Bug 版**：`makeApp` 完全不看 `arguments`：回傳連真的伺服器（`T02LiveAPI()`）、從 `.login` 畫面開始的 `T02App`。
2. **修正版**：`arguments` 有 `"-uiTesting"` 就用 `T02StubAPI()`，否則用 `T02LiveAPI()`；`arguments` 有 `"-startAt"`，下一個值（`"orderForm"`）就是起始畫面（用 `T02Screen(rawValue:)` 轉換），沒有就從 `.login` 開始。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「啟動 App 測試：跑了 3 個測試，通過 1、失敗 2」（價格只有一次剛好是 15,000,000，其他兩次是 15012300、14987650），「3 次測試一共多做了 12 個登入步驟」 |
| 修正版 | 「通過 3、失敗 0」，「3 次測試一共多做了 0 個登入步驟」 |

**為什麼**：UI 測試要穩定，App 本身要配合：啟動時看 `launchArguments`（或 `launchEnvironment`），把網路換成固定回傳資料的 stub，並直接進到要測的畫面。Bug 版失敗的那兩次，下單畫面並沒有 bug，只是價格本來就在變；每個測試都從登入開始，一輪測試就要多花好幾分鐘。UI 測試這一端和 App 這一端是這樣對應的：

```swift
// UI 測試這一端
let app = XCUIApplication()
app.launchArguments = ["-uiTesting", "-startAt", "orderForm"]
app.launch()
XCTAssertTrue(app.staticTexts["order.btcPrice"].waitForExistence(timeout: 5))
XCTAssertEqual(app.staticTexts["order.btcPrice"].label, "¥15,000,000")

// App 這一端（啟動時看參數）
let args = ProcessInfo.processInfo.arguments
let api: PriceAPI = args.contains("-uiTesting") ? StubPriceAPI() : LivePriceAPI()
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L03_LaunchArguments.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L03_LaunchArguments.swift#fixed
```

### 演練 4：換一台模擬器，截圖比對就失敗（第 4 題）

**檔案**：`L04_SnapshotTolerance.swift`

**情境**：訂單簿一列的截圖比對測試。`T02Renderer` 把一列畫成 60×12 的灰階圖（用固定的演算法模擬，不是真的畫字）。三種情況要判斷：① 同一台模擬器重畫一次；② 換另一台模擬器（字體邊緣的反鋸齒有細微差異，一小部分像素的亮度差 1 到 3）；③ 真的 bug：價格被切掉右邊 20 欄。`isSame(a, b)` 判斷兩張圖「算不算同一個畫面」。

**怎麼寫**

1. **Bug 版**：比較兩張圖的 `pixels` 陣列，每個像素都要完全一樣才算相同。
2. **修正版**：先確認寬高一樣；逐一比較像素，亮度差超過 8 才算「這個像素不一樣」；不一樣的像素不超過 1% 就算相同。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ① 判定相同；② 判定不同（16 個像素不一樣，最大差 3，其實畫面沒壞：誤報）；③ 判定不同（66 個像素不一樣，最大差 235） |
| 修正版 | ① ② 判定相同；③ 判定不同：真的 bug 抓得到 |

**為什麼**：截圖比對的誤報，常常只是不同模擬器、不同 iOS 版本的字體邊緣差了一兩個亮度，人眼根本看不出來。比對要求「每個像素完全一樣」，CI 就會時不時紅燈，最後大家養成「截圖測試失敗先重跑」的習慣，真的版面壞掉也被當成誤報。容忍度要訂得剛好：太嚴格會誤報，太鬆（例如允許 50% 的像素不同）真的 bug 就抓不到，所以修正版的③ 要確認仍然判成不同。swift-snapshot-testing 的 `precision`（相同像素的比例）和 `perceptualPrecision`（單一像素的色差容忍度）就是這兩個旋鈕；參考圖也要固定裝置、iOS 版本、語系，並只在 CI 上產生。

```swift
import SnapshotTesting
import XCTest

func testOrderBookRow() {
    let view = OrderBookRowView(price: "15,000,000")
    assertSnapshot(of: view, as: .image(precision: 0.99, perceptualPrecision: 0.98, layout: .fixed(width: 375, height: 44)))
}
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L04_SnapshotTolerance.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing02-iOSTesting/L04_SnapshotTolerance.swift#fixed
```

第 5 題（Test Plan、Thread Sanitizer、效能測試）是 Xcode 的工具與設定，沒有演練。做法：Test Plan 在 Xcode 用 File > New > File > Test Plan 建立，可以為同一套測試設定不同的語系、Sanitizer；Thread Sanitizer 在 Scheme > Test > Diagnostics 勾選；效能測試在 XCTest 的方法裡呼叫 `measure(metrics:block:)`，Xcode 會記下基準值，之後變慢就提醒。這些都需要 XCTest 的 test target，這個專案沒有。

## 常見陷阱

- **UI 測試用中文字找按鈕。** 切到日文就失敗。要用 `accessibilityIdentifier`。
- **每個 UI 測試都從登入開始。** 一輪測試跑 20 分鐘。用啟動參數直接進到要測的畫面和狀態。
- **Swift Testing 預設平行，測試共用單例或全域狀態。** 測試會互相干擾，時好時壞。
- **在 async 測試裡用舊的 `wait(for:timeout:)`。** 可能卡住。要用 `await fulfillment(of:timeout:)`。

## 面試官想聽到的

1. 知道 Swift Testing 的特點，以及「和 XCTest 共存、逐步遷移」的做法。
2. 非同步測試的正確寫法：await，不用 sleep；時間可以注入。
3. UI 測試穩定的方法：identifier、注入 stub、條件等待、直接進入要測的畫面。
4. 截圖比對適合測什麼，以及誤報怎麼處理。

> [!FIELD]
> 在一個社交 App 的 iOS 版，測試用 XCTest，UI 測試透過依賴注入把網路等換成 stub，讓每次結果都一樣；也用 AI 協助撰寫單元測試來提升覆蓋率。
> 面試時可以這樣說：「既有的測試都在 XCTest 上時，新的邏輯測試我會用 Swift Testing 寫，兩者可以共存，逐步遷移。」

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 參數化測試 | parameterized test | パラメータ化テスト |
| 斷言（檢查結果） | assertion | アサーション |
| 假實作 | stub／fake | スタブ／フェイク |
| 截圖比對測試 | snapshot test | スナップショットテスト |
| 死結 | deadlock | デッドロック |
| 資料競爭 | data race | データ競合 |
