---
id: testing-strategy
title: 交易所 App 的測試策略：要測什麼、測到哪一層
summary: 測試的目標是「錢和資料不會錯、關鍵流程不會壞」。這個單元說明測試分成哪幾層、資源有限時先測什麼、怎麼把程式設計成好測試的樣子，以及用什麼指標判斷測試有沒有用。工具和 AI 留到後面的單元。
priority: P0
day: 5
tags: [testing, strategy, pyramid]
---

## 重點講解

這個單元回答四個問題：

1. 測試分成哪幾層？每一層測什麼？
2. 資源有限時，要先測什麼？
3. 怎麼把程式設計成好測試的樣子？
4. 怎麼知道測試真的有用？

### 1. 測試分成哪幾層？每一層測什麼？

**結論：越底層的測試越快、越穩定，數量應該越多；越上層越接近真實使用情況，但又慢又容易不穩定，只放在最關鍵的流程。** 這個形狀常被畫成一個金字塔（test pyramid）。

| 層 | 測什麼 | 速度與穩定度 | 交易所 App 的例子 |
|---|---|---|---|
| 單元測試（unit test） | 純邏輯、ViewModel、資料解析 | 毫秒級，最穩定 | 精度與捨入、訂單狀態的合併規則、WebSocket 訊息解析、API 簽名、K 線合併 |
| 整合測試（integration test） | 好幾個元件一起運作，例如 Repository 搭配假的伺服器、資料庫的版本升級 | 秒級 | 重連後有沒有重新訂閱、下單逾時後有沒有去查詢對帳 |
| 截圖比對（snapshot test） | 把元件或畫面截圖，和之前存好的正確截圖比對 | 秒級 | 訂單簿的一列、價格閃爍的狀態、深色模式、多種語言、字體放大 |
| UI 測試／端到端測試（E2E） | 在模擬器或實機上跑完整的使用流程 | 分鐘級，最容易不穩定 | 登入加兩步驟驗證、下單加撤單、入金、出庫申請 |
| 手動／探索式測試 | 新功能、使用體驗、不同裝置的差異 | — | 新版 K 線、網路很慢時的體驗 |

為什麼上層的測試要少？UI 測試要啟動整個 App、等畫面出現、點按鈕，一個就要幾十秒到幾分鐘；而且網路、動畫、模擬器的狀態都會影響結果，容易出現「同樣的程式，有時候過、有時候不過」的情況（flaky）。如果 PR（pull request，把程式碼提交給團隊審查、通過後才合併的請求）要等 40 分鐘的 UI 測試，大家就會開始想辦法跳過測試。

> [!TIP]
> 面試時可以這樣說：「純邏輯我會大量寫單元測試，又快又穩；整合測試驗證重連、逾時這類元件之間的互動；截圖比對驗證 UI 狀態；端到端測試只放登入、下單、入出金這幾條關鍵流程。」

### 2. 資源有限時，要先測什麼？

**結論：依「出錯的影響有多大」排序，而不是依「哪個比較好測」。**

交易所 App 的風險順序：

1. **錢**：金額計算、下單與撤單、入出金、手續費的顯示。這裡錯一次就是客訴，甚至是金錢損失。
2. **身分與安全**：登入、兩步驟驗證、生物辨識、登入過期、越獄偵測。
3. **即時資料的正確性**：行情、訂單簿、斷線重連後的資料。
4. **法規相關**：風險說明、槓桿上限、出金時的トラベルルール（travel rule，轉帳要附上收款方資訊）表單、eKYC（線上身分驗證）。
5. 一般的 UI 和設定。

在每一類裡，先挑「邏輯複雜、而且是純邏輯」的部分寫單元測試，例如精度規則、訂單狀態的合併、API 簽名，這些寫起來快、跑起來穩，又最容易藏 bug。

> [!TIP]
> 面試時可以這樣說：「我會依風險排優先順序：先是跟錢有關的，再來是登入和安全、即時資料的正確性、法規相關的畫面，最後才是一般 UI。每一類先從邏輯複雜的純函式開始寫單元測試。」

### 3. 怎麼把程式設計成好測試的樣子？

**結論：好不好測，是設計出來的。把「判斷邏輯」和「外部的東西」（網路、時間、隨機數、儲存）分開，外部的東西都能從外面換成假的。**

- **依賴注入**（dependency injection：物件需要的東西由外面傳進來）：網路、時鐘、隨機數、儲存都透過參數或建構子傳進來，測試時換成假的實作。
- **純函式**：把判斷邏輯從網路、資料庫這些操作裡抽出來，寫成「同樣的輸入永遠得到同樣的輸出」的函式。例如訂單狀態的合併規則、價格的格式化。
- **時間可以控制**：不要在邏輯裡直接讀現在時間。Swift 注入 `Clock` 或一個 `now` 函式；Kotlin 用 `runTest` 提供的虛擬時間，`delay(1000)` 不會真的等 1 秒。
- **UI 測試可以重現**：用依賴注入把網路和第三方 SDK 換成固定回傳資料的假實作（stub）；固定測試資料、固定時間、關閉動畫。
- **測試資料**：把真實的 WebSocket 訊息和 API 回覆錄下來，存成測試用的檔案（fixture），測試時拿來用。

```swift
// 不好測：直接用單例打網路
final class PriceAlertChecker {
    func shouldNotify(target: Decimal) async -> Bool {
        // 測試時會真的打網路
        guard let price = try? await APIClient.shared.fetchPrice() else {
            return false
        }
        return price >= target
    }
}

// 好測：外部的東西從外面傳進來
final class PriceAlertChecker2 {
    private let fetchPrice: () async throws -> Decimal

    init(fetchPrice: @escaping () async throws -> Decimal) {
        self.fetchPrice = fetchPrice
    }

    func shouldNotify(target: Decimal) async -> Bool {
        guard let price = try? await fetchPrice() else {
            return false
        }
        return price >= target
    }
}
// 測試時：PriceAlertChecker2(fetchPrice: { 15_000_000 })，不需要網路
```

> [!TIP]
> 面試時可以這樣說：「可測試性是設計出來的。我會把網路、時間、儲存都透過依賴注入傳進來，把判斷邏輯抽成純函式，UI 測試用 stub 取代網路和第三方 SDK，這樣每次跑結果都一樣。」

### 4. 怎麼知道測試真的有用？

**結論：覆蓋率只能當參考。更能看出測試有沒有用的，是 mutation score：故意把程式改壞，看測試能不能抓到。**

- **覆蓋率**（coverage）：測試執行時，有多少行程式碼（line coverage）或多少分支（branch coverage）被跑到。問題是「被跑到」不等於「被檢查到」。一個測試呼叫了函式、卻沒有檢查回傳值，覆蓋率照樣會算進去。
- **mutation score**：工具自動把程式做小小的修改（例如把 `<` 改成 `<=`、把 `+` 改成 `-`），每改一處就跑一次測試。如果測試還是全部通過，代表測試沒有真正檢查到那段邏輯。被測試抓到的修改比例，就是 mutation score。這也是驗證 **AI 產生的測試**有沒有用的好方法。

其他實用的指標：

- **flaky 的比例**：有多少測試「同樣的程式、有時過有時不過」。
- **測試執行時間**：PR 要等多久。
- **上線後逃過測試的 bug 數量**：測試的最終目的，是讓線上的 bug 變少。

> [!TIP]
> 面試時可以這樣說：「覆蓋率只代表程式被執行過，不代表有被檢查。我會搭配 mutation testing，故意改壞程式看測試抓不抓得到，這也是驗證 AI 產生的測試有沒有用的方法。另外追蹤 flaky 比例、測試時間和上線後的 bug 數。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Testing01Strategy**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Testing01Strategy**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Testing/Testing01-Strategy/`，解答在 `labs/ios/Solution/Testing/Testing01-Strategy/`；Android 你寫在 `labs/android/practice/testing/testing01-strategy/`，解答在 `labs/android/solution/testing/testing01-strategy/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **這一區練的是「測試本身」**：Bug 版是「測試寫錯」的樣子，例如測錯層級、依賴真實時間、抓不到錯誤。App 裡沒有 XCTest 或 JUnit 的測試 target，所以每個演練用一個很小的「迷你測試執行器」（`T01Suite`、`T01Expect`，在 `Testing01Support` 檔案裡，已經寫好）模擬一個測試的通過與失敗，結果寫進 log。UI 測試的啟動時間、系統時間都是模擬的，不會真的連網路。

### 演練 1：12 條規則全用 UI 測，慢又時過時不過（第 1 題）

**檔案**：`L01_WrongLayer.swift`（iOS）、`L01_WrongLayer.kt`（Android）

**情境**：下單有 12 個案例要驗：價格要是 1,000 日圓的倍數、數量不能低於 0.0001 BTC、不能超過 5 BTC…。規則本身是一個純函式 `T01OrderRules.validate`（已經寫好）。UI 測試用 `T01FakeOrderScreen` 模擬：每個案例要冷啟動加登入 120 ms、輸入 40 ms、點確認 20 ms，而且錯誤訊息要等動畫結束才會出現在畫面上（有的案例動畫超過 100 ms）。

**怎麼寫**

1. **Bug 版**：12 個案例都走 UI 測試。在 `suite.test` 裡建立 `T01FakeOrderScreen`，依序呼叫 `launch()`、`type(…)`、`tapConfirm()`，然後固定等 100 ms（iOS 用 `Task.sleep(for: .milliseconds(100))`，Android 用 `delay(100)`），最後比較 `errorLabel()` 和案例的預期結果。
2. **修正版**：規則是純邏輯，直接比較 `T01OrderRules.validate(…)` 的結果和預期結果，不經過 `T01FakeOrderScreen`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「UI 層：跑了 12 個案例，通過 7、失敗 5」，花了約 3500 ms（解答實測 iOS 3.5 秒、Android 3.5 秒）；失敗的 5 個案例都是動畫比 100 ms 久，固定等待之後畫面還沒有錯誤訊息，但規則其實是對的 |
| 修正版 | 「單元層：跑了 12 個案例，通過 12、失敗 0」，花了 0 ms，每次結果都一樣 |

**為什麼**：UI 測試要經過整個 App（啟動、登入、輸入、動畫），每個案例的成本是單元測試的幾千倍，而且「固定 sleep 等畫面」讓結果取決於動畫跑得多快，規則明明沒壞卻會失敗，這就是 flaky。真正的 XCUITest 和直接驗純函式的差別長這樣：

```swift
// UI 測試：每個案例都要經過整個 App
func testPriceNotOnTick() {
    let app = XCUIApplication()
    app.launch()
    app.textFields["order.priceField"].typeText("15000500")
    app.buttons["order.confirmButton"].tap()
    XCTAssertTrue(
        app.staticTexts["order.errorLabel"].waitForExistence(timeout: 5)
    )
}

// 單元測試：同一條規則，直接呼叫純函式
func testPriceNotOnTick() {
    XCTAssertEqual(
        OrderRules.validate(priceJPY: 15_000_500, amountSatoshi: 100_000),
        .priceNotOnTick
    )
}
```

12 條規則各寫一個單元測試，加上 1 個 UI 測試確認「點了確認，畫面真的有顯示錯誤」，就同時有速度和信心。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L01_WrongLayer.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L01_WrongLayer.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing01-strategy/L01_WrongLayer.kt#bug
```

```lab-solution
android/solution/testing/testing01-strategy/L01_WrongLayer.kt#fixed
```

### 演練 2：同一個測試，半夜跑就失敗（第 3 題）

**檔案**：`L02_RealClock.swift`（iOS）、`L02_RealClock.kt`（Android）

**情境**：交易所每天日本時間 04:00 到 04:30 維護，這段時間不能下單。`MaintenanceGuard.canPlaceOrder()` 判斷現在能不能下單。有人寫了測試「平日白天應該可以下單」，在公司下午跑都是綠燈。演練用 `T01SystemClock` 模擬系統時間（程式裡直接寫 `Date()` 或 `ZonedDateTime.now()` 讀到的就是它），並讓同一個測試分別跑在 10:00、04:10、23:50，代表 CI 在不同時間點的執行。

**怎麼寫**

1. **Bug 版**：`canPlaceOrder()` 在裡面直接讀系統時間（iOS：`T01SystemClock.now`；Android：`T01SystemClock.now()`），用 `T01Time.isInMaintenance` 判斷。測試不指定時間：`MaintenanceGuard().canPlaceOrder()`。
2. **修正版**：時間從外面注入。iOS 的 `MaintenanceGuard` 多一個 `now: () -> Date`；Android 多一個 `clock: Clock`（`java.time.Clock`），用 `ZonedDateTime.now(clock)`。測試自己指定時間，驗 04:00、04:29、04:30 這三個邊界和 10:00。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「跑在 10:00」「跑在 23:50」通過；「跑在 04:10」失敗（「不能下單」）：程式沒改，結果卻跟著執行的時間變 |
| 修正版 | 三個系統時間下都是「跑了 4 個案例，通過 4、失敗 0」，04:00、04:29、04:30 的邊界都驗到了 |

**為什麼**：測試裡直接用 `Date()` 或 `ZonedDateTime.now()`，等於讓「現在幾點」變成測試的隱藏輸入，CI 半夜跑、月底跑、跨時區跑，結果都可能不同。把時間注入進去之後，測試可以指定任何時間點，原本很難測的邊界（維護剛開始、剛結束）就能寫成固定的案例。Swift 可以注入 `now: () -> Date`（或 `Clock` 協定），Kotlin 的 `java.time.Clock` 就是標準的做法；需要「等一段時間」的邏輯（`delay`）則用 `runTest` 的虛擬時間，見 Testing03。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L02_RealClock.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L02_RealClock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing01-strategy/L02_RealClock.kt#bug
```

```lab-solution
android/solution/testing/testing01-strategy/L02_RealClock.kt#fixed
```

### 演練 3：覆蓋率 100%，程式被改壞測試還是過（第 4 題）

**檔案**：`L03_CoverageIsNotChecking.swift`（iOS）、`L03_CoverageIsNotChecking.kt`（Android）

**情境**：單筆出金達到 100 萬日圓，就要再做一次兩步驟驗證。`T01Withdrawal.requiresTwoFactor` 是被測的函式。mutation testing 的做法是故意把程式改壞，再跑一次測試：這裡手寫一個 `requiresTwoFactorMutant`（`>=` 變成 `>`）。演練把同一個測試，分別對「正確的實作」和「被改壞的實作」各跑一次，並記下函式被呼叫幾次、`if` 的兩個分支有沒有跑到（覆蓋率）。

**怎麼寫**

1. **Bug 版**：`test(requires, t)` 只驗兩個離邊界很遠的例子：`requires(5_000_000)` 預期 `true`、`requires(100)` 預期 `false`。
2. **修正版**：再補上邊界的例子：`requires(999_999)` 預期 `false`、`requires(1_000_000)` 預期 `true`、`requires(1_000_001)` 預期 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「正確的實作：測試通過」「覆蓋：函式被呼叫 2 次，true 分支有跑到，false 分支有跑到」（覆蓋率 100%），「被改壞的實作（>= 改成 >）：測試通過」：沒抓到 |
| 修正版 | 「正確的實作：測試通過」，「被改壞的實作（>= 改成 >）：測試失敗（預期 true，實際 false）」：抓到了 |

**為什麼**：覆蓋率只能告訴你「這一行有被執行」，不能告訴你「這一行的結果有被檢查」。Bug 版的測試每一行、每個分支都跑到了，可是 `>=` 改成 `>` 只影響剛好 1,000,000 這一個值，離邊界很遠的例子永遠看不出差別。被改壞的程式被測試放行，就是 mutation score（被抓到的修改比例）低的意思。真正的 mutation testing 工具會自動產生這種修改（JVM（Java 虛擬機器）上常見的是 Pitest，Swift 有 Muter；這個專案沒有安裝，也沒有跑過）。AI 產生的測試最容易出現這種「很完整、卻沒檢查邊界」的情況，所以要用這個方法驗證，見 Testing05。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L03_CoverageIsNotChecking.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing01-Strategy/L03_CoverageIsNotChecking.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing01-strategy/L03_CoverageIsNotChecking.kt#bug
```

```lab-solution
android/solution/testing/testing01-strategy/L03_CoverageIsNotChecking.kt#fixed
```

第 2 題（資源有限時先測什麼）是依風險排優先順序的流程題，沒有演練。

## 常見陷阱

- **追求覆蓋率的數字。** 寫了一堆只檢查 getter、沒有真正驗證邏輯的測試，數字好看但抓不到錯。
- **UI 測試用 `sleep` 等畫面出現。** 等太短會失敗、等太長又慢。要用「等到某個元素出現」的 API（例如 XCUITest 的 `waitForExistence`、Compose 的 `waitUntil`）。
- **測試依賴真實的伺服器或真實的時間。** 在 CI（Continuous Integration，持續整合，每次有人推程式碼就自動建置、跑測試的機制）上會隨機失敗。要換成假的實作和可控制的時鐘。
- **端到端測試寫太多。** PR 要等很久，大家開始跳過測試。端到端只放關鍵流程。

## 面試官想聽到的

1. 從**風險**出發決定先測什麼，而不是從工具出發。
2. 知道每一層測試的成本和適合測什麼。
3. 可測試性是設計出來的：依賴注入、純函式、可控制的時間。
4. 用什麼指標判斷測試有沒有用（不只看覆蓋率）。

> [!FIELD]
> 在一個社交 App，我把 AI agent 納入開發流程，用 AI 撰寫單元測試來提升覆蓋率。實作上，UI 測試用依賴注入換成 stub，讓結果可以重現；也規定測試裡不能用 `sleep` 等待。
> 面試時可以這樣說：「我的優先順序是先把最容易出錯、影響最大的純邏輯抽出來測，再用依賴注入讓 UI 測試能穩定重現。」

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 單元測試 | unit test | ユニットテスト（単体テスト） |
| 整合測試 | integration test | 結合テスト |
| 端到端測試 | end-to-end test（E2E） | E2E テスト |
| 截圖比對測試 | snapshot test | スナップショットテスト |
| 時好時壞的測試 | flaky test | 不安定なテスト（フレーキーテスト） |
| 覆蓋率 | code coverage | カバレッジ |
| 變異測試 | mutation testing | ミューテーションテスト |
| 依賴注入 | dependency injection | 依存性注入 |
