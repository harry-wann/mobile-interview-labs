---
id: testing-e2e
title: 端到端測試與實機測試：Maestro、Appium、原生 UI 測試、device farm
summary: 端到端測試（E2E）最接近使用者的真實操作，也最容易時好時壞。這個單元說明怎麼選工具、交易所 App 的測試環境要注意什麼（不能用真錢、兩步驟驗證怎麼過），以及時好時壞的測試要怎麼管理。
priority: P1
day: 5
tags: [testing, e2e, maestro, appium, device-farm, flaky]
---

## 重點講解

這個單元回答四個問題：

1. 端到端測試有哪些工具？怎麼選？
2. 交易所 App 的測試環境要注意什麼？
3. 哪些流程一定要有端到端測試？
4. 時好時壞（flaky）的測試要怎麼管理？

### 1. 端到端測試有哪些工具？怎麼選？

端到端測試（end-to-end，E2E）是在模擬器或實機上，從頭到尾跑一遍使用者的操作，例如「打開 App → 登入 → 下單 → 撤單」。

| 工具 | 特點 | 適合的情況 |
|---|---|---|
| **Maestro** | 用 YAML 寫操作流程（`launchApp`、`tapOn`、`inputText`、`assertVisible`），會自動等待畫面出現；只要兩個平台的元件 ID 一致，同一份流程可以在 iOS 和 Android 跑 | 雙平台、想快速寫出關鍵流程、非工程師也看得懂 |
| **原生 UI 測試**（XCUITest、Espresso、Compose UI 測試） | 速度快、和程式碼放在同一個 repo；兩個平台各寫一份 | 需要細部控制、想和單元測試共用 stub |
| **Appium** | 用 WebDriver 協定（瀏覽器自動化的標準）控制 App，可以用很多種程式語言寫；比較慢、設定比較多 | QA（quality assurance，品質保證／測試人員）團隊已經熟悉 Selenium、要測很多不同的 App |
| **Device farm**（Firebase Test Lab、AWS Device Farm、BrowserStack） | 雲端的大量實機，各種型號，可以同時跑 | 每天晚上跑完整的端到端測試、檢查不同機型的相容性 |

```yaml
# Maestro 的流程範例：限價買單後撤單
appId: jp.co.example.exchange
---
- launchApp
- tapOn:
    id: "market.btcjpy"
- tapOn:
    id: "order.limitTab"
- tapOn:
    id: "order.priceField"
- inputText: "15000000"
- tapOn:
    id: "order.buyButton"
- assertVisible:
    id: "openOrders.firstRow"
```

**結論**：雙平台的關鍵流程用 Maestro 寫一份共用，寫得快、好讀；需要細部控制的部分用原生 UI 測試補；Appium 只有在團隊已經有相關經驗時才考慮。

> [!TIP]
> 面試時可以這樣說：「我會先用 Maestro 把關鍵流程寫成雙平台共用的測試，因為它好讀、寫得快、會自動等待；需要細部控制的地方再用原生 UI 測試。晚上在 device farm 的實機上跑完整一輪。」

### 2. 交易所 App 的測試環境要注意什麼？

- **絕對不在正式環境用真錢測試**。使用測試環境（staging）或測試鏈（testnet）、測試帳號、假的入金。
- **兩步驟驗證**：測試帳號使用一組固定的 TOTP（Time-based One-Time Password，兩步驟驗證用的 6 位數驗證碼）金鑰（產生驗證碼的密鑰），測試程式自己用同樣的演算法算出驗證碼（和交易所單元的 TOTP 實作題是同一個演算法）。
- **eKYC（線上身分驗證）**：測試環境用廠商提供的 sandbox，或直接回傳固定結果的假服務。
- **每次測試前重設資料**：用 API 建立乾淨的帳號狀態（例如清空所有訂單），測試之間才不會互相影響。

> [!TIP]
> 面試時可以這樣說：「交易所 App 不會在正式環境用真錢測試，我會用測試環境、測試帳號和假的入金。兩步驟驗證的部分，測試帳號用一組固定的 TOTP 金鑰，測試程式自己算出驗證碼；eKYC 用廠商的 sandbox 或回傳固定結果的假服務；每次測試前用 API 重設資料，測試之間才不會互相影響。」

### 3. 哪些流程一定要有端到端測試？

範例清單：

1. 第一次打開 App → 登入 → 兩步驟驗證 → 首頁顯示行情。
2. 交易對頁 → 指値（限價）買單 → 確認 → 未成交列表出現這筆訂單 → 撤單。
3. 販売所（交易所直接報價給使用者的市場）買入：固定報價、報價過期時的處理。
4. 資產頁 → 日圓入金說明畫面。
5. 出庫：新增地址（填寫トラベルルール表單）→ 確認畫面 → 兩步驟驗證。
6. 多種語言和深色模式下，主要畫面都正常。

> [!TIP]
> 面試時可以這樣說：「端到端測試我會放在最關鍵的流程：登入加兩步驟驗證、限價買單到撤單、販売所買入、入金說明畫面，還有出庫新增地址。另外會確認多種語言和深色模式下，主要畫面都正常。」

### 4. 時好時壞（flaky）的測試要怎麼管理？

flaky test 是指「程式沒有改，測試卻有時候過、有時候不過」。放著不管，大家會開始不相信測試結果，最後直接忽略失敗。

管理的方法：

- **找出原因並分類**：
  - 時間：等待不夠久、動畫還沒結束。
  - 資料：好幾個測試共用同一個帳號，互相改到資料。
  - 環境：測試伺服器不穩定、網路問題。
  - 順序：測試之間有依賴，換了執行順序就失敗。
  - 真的 bug：例如程式本身有 race condition（多個工作執行的先後不同，結果就不同），只在特定時機出錯。
- **隔離**：連續出現時好時壞的測試，移到隔離區（quarantine）：仍然執行，但失敗不會擋住 PR（pull request，把程式碼提交給團隊審查、通過後才合併的請求）；同時開一張單，限期修好。
- **重試要留紀錄**：失敗後自動重試一次可以接受，但要記錄「重試後才通過」的次數。不記錄的話，問題就被掩蓋了。
- **追蹤指標**：flaky 的比例、平均多久修好。

> [!TIP]
> 面試時可以這樣說：「時好時壞的測試我會先分類原因：時間、資料、環境、順序，還是真的 bug。連續 flaky 的測試先隔離，不擋 PR 但限期修；自動重試可以，但要記錄重試後才通過的次數，否則問題會被藏起來。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Testing04E2E**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Testing04E2E**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Testing/Testing04-E2E/`，解答在 `labs/ios/Solution/Testing/Testing04-E2E/`；Android 你寫在 `labs/android/practice/testing/testing04-e2e/`，解答在 `labs/android/solution/testing/testing04-e2e/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的等**：Maestro、Appium、device farm 需要真的裝置與外部服務，這個專案沒有安裝，所以演練把 E2E 測試的「環境問題」做成可以在 App 裡重現的小模型：假的時鐘（用 `advance` 代表花了多少秒）、假的驗證伺服器、假的交易所後端，加上一個很小的迷你測試執行器（`T04Suite`、`T04Expect`，在 `Testing04Support` 檔案裡）。每次執行的結果都一樣。

### 演練 1：登入測試偶爾失敗，只發生在 30 秒的尾巴（第 2 題）

**檔案**：`L01_TotpBoundary.swift`（iOS）、`L01_TotpBoundary.kt`（Android）

**情境**：測試帳號用固定的 TOTP 密鑰，測試程式自己用同樣的算法（`T04Totp.code`，照 RFC 6238 的演算法：用密鑰對「現在的時間 ÷ 30 秒」做 HMAC-SHA1，也就是一種用密鑰算出驗證用雜湊值的方法，再取 6 位數；每 30 秒換一個）算出驗證碼；演練開頭會用 RFC 附錄的測試向量確認算法是對的。登入流程：輸入帳號密碼（2.5 秒）→ 等兩步驟驗證畫面出現（1.5 秒）→ 送出驗證碼（0.5 秒送到伺服器）。伺服器很嚴格，只接受「現在這個 30 秒」的驗證碼。時間用假的時鐘推進，不會真的等。演練把同一個登入測試跑 4 次，分別從某個 30 秒區間的第 3、12、22、27.5 秒開始。

**怎麼寫**

1. **Bug 版**：`login` 一開始就用 `T04Totp.code` 算好驗證碼，再依序 `typeCredentials()`、`waitForTwoFactorScreen()`、`submitCode(code)`。
2. **修正版**：`login` 先 `typeCredentials()`、`waitForTwoFactorScreen()`；再用 `T04Totp.secondsLeft` 看這個 30 秒還剩幾秒，少於 5 秒就 `clock.advance(剩下的秒數 + 0.1)` 等到下一個區間（真實測試裡是 sleep）；最後才算驗證碼並 `submitCode`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「登入測試：跑了 4 個測試，通過 3、失敗 1」：從第 27.5 秒開始的那次失敗，「登入失敗：驗證碼過期或錯誤」 |
| 修正版 | 「通過 4、失敗 0」；從第 22 秒開始的那次，流程多等了一下才送出 |

**為什麼**：驗證碼只有 30 秒的壽命。Bug 版在流程一開始就算好，等走完後面的步驟才送出，如果中間剛好跨過 30 秒的邊界，伺服器就拒絕。這種失敗大約每 30 次有 4 次，而且重跑就好了，很容易被當成「環境不穩」放著不管。正確的做法是：算驗證碼放在要用之前；離邊界太近（例如剩不到 5 秒）就先等到下一個區間。伺服器端通常會多接受前後各一個區間，但測試環境不一定，而且手機與伺服器的時間也可能有誤差，測試不要靠這個。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing04-E2E/L01_TotpBoundary.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing04-E2E/L01_TotpBoundary.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing04-e2e/L01_TotpBoundary.kt#bug
```

```lab-solution
android/solution/testing/testing04-e2e/L01_TotpBoundary.kt#fixed
```

### 演練 2：「撤單」測試單獨跑就失敗（第 2 題）

**檔案**：`L02_OrderDependency.swift`（iOS）、`L02_OrderDependency.kt`（Android）

**情境**：兩個 E2E 測試：A「限價買單後，未成交列表有這筆訂單」、B「撤單後，未成交列表是空的」。後端是 `T04FakeExchange`（每個帳號有自己的未成交訂單；`createAccount()` 建立全新的帳號）。演練用三種方式跑：依序 A→B、順序換成 B→A、只跑 B（例如 CI 只重跑失敗的測試）。每一輪開始前，後端都是乾淨的。

**怎麼寫**

1. **Bug 版**：A、B 兩個測試都用共用帳號 `"e2e-user"`。A：`placeLimitBuy` 15,000,000，檢查 `openOrders` 筆數是 1。B：不自己下單，直接 `cancelFirst`，用 `isTrue` 檢查「撤得掉」，再檢查 `openOrders` 筆數是 0。
2. **修正版**：每個測試用 `exchange.createAccount()` 建立自己的帳號。A 同上。B 先自己 `placeLimitBuy` 當作前置條件，再撤單並檢查。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「順序 A→B」全過；「順序 B→A」和「只跑 B」的 B 失敗：「沒有未成交的訂單可以撤」 |
| 修正版 | 三種跑法都沒有失敗：「順序 A→B」「順序 B→A」通過 2，「只跑 B」通過 1 |

**為什麼**：B 能通過，是因為 A 先留下了一筆訂單；B 沒有失敗在「撤單功能有 bug」，而是失敗在「測試依賴別的測試留下的資料」。順序一換、平行執行、只重跑失敗的測試，就變成時好時壞，這是 flaky 的「資料」與「順序」兩種原因。每個 E2E 測試自己準備需要的資料（用 API 建立乾淨的帳號、自己下一筆單），才不會互相影響。共用同一個測試帳號時，即使每個測試開頭都「重設」，平行跑還是會互相覆蓋，所以最好每個測試一個帳號。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing04-E2E/L02_OrderDependency.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing04-E2E/L02_OrderDependency.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing04-e2e/L02_OrderDependency.kt#bug
```

```lab-solution
android/solution/testing/testing04-e2e/L02_OrderDependency.kt#fixed
```

### 演練 3：CI 只說 1 個失敗，其實 2 個第一次就失敗（第 4 題）

**檔案**：`L03_RetryHidesFlaky.swift`（iOS）、`L03_RetryHidesFlaky.kt`（Android）

**情境**：CI 的 E2E 測試每個最多試 2 次（第一次失敗就自動重試一次）。4 個測試：「登入加兩步驟驗證」「日圓入金說明畫面」穩定通過；「限價買單到撤單」第一次失敗（等畫面等不夠久）、重試才通過（flaky）；「出庫新增地址」每次都失敗（真的 bug）。`runAll` 負責跑完所有測試，回傳報告 `T04Report`（`passed`、`flakyPassed`、`failed` 三份清單）。

**怎麼寫**

1. **Bug 版**：`runAll` 對每個測試最多試 `maxAttempts` 次（`test.run(attempt)`，`attempt` 從 1 開始），有一次通過就放進 `report.passed`，都沒通過放 `report.failed`；不記錄試了幾次。
2. **修正版**：記下是第幾次才通過：第 1 次就通過放 `report.passed`，重試後才通過放 `report.flakyPassed`，都沒通過放 `report.failed`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「CI 報告：通過 3、重試後才通過 0、失敗 1」；但最後一行「實際上第一次就失敗的測試有 2 個：限價買單到撤單、出庫新增地址」 |
| 修正版 | 「CI 報告：通過 2、重試後才通過 1、失敗 1」，「flaky 清單：限價買單到撤單」；失敗的測試仍然擋下 PR |

**為什麼**：自動重試可以接受，但重試只是讓 PR 先過，問題還在。Bug 版的報告上，flaky 的測試和穩定的測試長得一樣，沒有人知道它有問題，直到哪天重試兩次都失敗，才在最不方便的時候被發現。報告要把「重試後才通過」獨立列出來，才能追蹤 flaky 的比例、限期修好或移到隔離區（quarantine：仍然執行，但失敗不擋 PR）。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing04-E2E/L03_RetryHidesFlaky.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing04-E2E/L03_RetryHidesFlaky.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing04-e2e/L03_RetryHidesFlaky.kt#bug
```

```lab-solution
android/solution/testing/testing04-e2e/L03_RetryHidesFlaky.kt#fixed
```

第 1 題（工具怎麼選）是選型題，第 3 題（哪些流程一定要有 E2E）是範例清單，沒有演練。Maestro、Appium、device farm 都需要真的裝置或外部服務，這個專案沒有安裝，下面是怎麼跑的範例（沒有在這個專案裡跑過）。

**Maestro**（雙平台共用的 YAML 流程；測試帳號的 TOTP 在跑之前先算好，用環境變數傳進去，所以同樣要注意演練 1 的 30 秒邊界）：

```yaml
# flows/login.yaml
appId: jp.co.example.exchange
---
- launchApp
- tapOn:
    id: "login.email"
- inputText: "e2e-user@example.com"
- tapOn:
    id: "login.password"
- inputText: ${PASSWORD}
- tapOn:
    id: "login.submit"
- tapOn:
    id: "twofactor.field"
- inputText: ${OTP}
- assertVisible:
    id: "home.marketList"
```

```bash
# 在要跑之前才算驗證碼（oathtool 是產生 TOTP 的命令列工具）；
# --format 與 --output 產生 JUnit XML 報告給 CI 顯示
maestro test -e PASSWORD="$E2E_PASSWORD" \
  -e OTP="$(oathtool --totp -b "$E2E_TOTP_SECRET")" \
  --format=junit --output=report.xml flows/login.yaml
```

**Appium**（用 WebDriver 協定；先啟動 `appium` 伺服器，再用任何語言的 client 送 capabilities）：

```json
{
  "platformName": "iOS",
  "appium:automationName": "XCUITest",
  "appium:deviceName": "iPhone 17",
  "appium:app": "/path/to/ExchangeApp.app"
}
```

**Firebase Test Lab**（Android 的 instrumentation 測試，分成 3 個 shard 在多台裝置上同時跑；iOS 用 `gcloud firebase test ios run`）：

```bash
gcloud firebase test android run --type instrumentation \
  --app app/build/outputs/apk/debug/app-debug.apk \
  --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --device model=Pixel2,version=30,locale=ja,orientation=portrait \
  --num-uniform-shards=3
```

## 常見陷阱

- **端到端測試太多、太慢，每個 PR 都跑全部。** PR 只跑關鍵的幾條，完整的放到每天晚上。
- **好幾個測試共用同一個測試帳號。** 互相改到資料，結果時好時壞。每個測試用自己的帳號，或測試前重設資料。
- **用畫面上的文字找元素。** 換語言就失敗。用固定的元件 ID。
- **把自動重試當成修好了。** 重試只是讓 PR 先過，問題還在。

## 面試官想聽到的

1. 依團隊和雙平台的需求選工具，並說出理由。
2. 交易所特有的測試環境問題：不能用真錢、兩步驟驗證、身分驗證、資料重設。
3. flaky 測試的分類、隔離和追蹤指標。

> [!FIELD]
> 在一個社交 App，UI 測試用依賴注入把網路和第三方 SDK 換成 stub，也規定不能用 `sleep` 等待，所以結果能穩定重現。那個專案用 Fastlane 搭配 Codemagic、CircleCI 自動建置；另一個即時行情 App 用 Fastlane 自動發布。
> 面試時可以這樣說：「換成交易所 App，我會先用 Maestro 把關鍵流程寫成雙平台共用的測試，需要細部控制的部分再用原生 UI 測試。」

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 端到端測試 | end-to-end test（E2E） | E2E テスト |
| 時好時壞的測試 | flaky test | 不安定なテスト（フレーキーテスト） |
| 實機測試平台 | device farm | デバイスファーム |
| 隔離區 | quarantine | 隔離 |
| 測試環境 | staging environment | ステージング環境 |
| 重要的操作流程 | critical path／critical user journey | 重要導線 |
