---
id: advanced-observability-quality
title: 上線之後怎麼看見問題：log／指標／追蹤、用指標決定要不要擴大發布、kill switch 與強制更新
summary: App 上架之後，你看不到使用者的手機。這個單元說明可觀測性的三種資料（log、指標、追蹤）、crash 與效能該看哪些工具、怎麼用指標決定新版本要不要繼續擴大發布，以及商店無法回復版本時的安全網（kill switch、強制更新），最後補上 analytics 與 A/B 測試的設計重點。
priority: P1
day: 3
tags: [observability, logging, analytics, metrickit, firebase-performance, release-health, phased-release, staged-rollout, kill-switch, force-update, ab-test]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 可觀測性是什麼？log、指標、追蹤各回答什麼問題？
2. 上線之後要監控哪些指標？用什麼工具？
3. 新版本發布時，怎麼用指標決定要不要繼續擴大？
4. 商店不能回復版本，出問題時有哪些安全網？（kill switch、強制更新）
5. analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？
6. A/B 測試有哪些統計陷阱？

### 1. 可觀測性是什麼？log、指標、追蹤各回答什麼問題？

**可觀測性（observability）是指：不用到使用者的手機上，也能從 App 送回來的資料，知道 App 現在好不好、哪裡出了問題。** 這些資料通常分成三種：log、指標（metric）、追蹤（trace）。

| 資料 | 回答的問題 | 例子 |
|---|---|---|
| log | 「那一刻發生了什麼事？」 | 下單被 server 拒絕，錯誤碼是什麼 |
| 指標 | 「整體趨勢是好是壞？」 | 下單成功率、crash-free users、API 的 p95 延遲 |
| 追蹤 | 「一次操作經過了哪些環節，卡在哪裡？」 | 一次下單從 App 按鈕、API 請求到 server 處理的完整路徑 |

p95 是 95th percentile 的縮寫，意思是「有 95% 的請求比這個數字快」，比平均值更能看出少數很慢的請求。

**log 的寫法重點：**

- **用結構化的欄位，不要拼接字串。** 固定 level（debug、info、error）與 category（分類），方便搜尋與過濾。
- **每筆都帶關聯 ID。** 例如 requestId、sessionId、`clientOrderId`（下單時由 App 產生的唯一編號）。有了它，才能把同一次下單從 App、API 到 server 的紀錄串起來，這就是追蹤的基本做法。
- **隱私優先。** token、地址、餘額、個資一律遮罩或不記。
- **遠端 log 要有取樣與預算**（電量、流量、儲存）。與其開全量 log，不如在 crash 時附上最近幾筆 breadcrumb（出事之前使用者做過的動作紀錄）和 custom key（自訂欄位，例如畫面名稱）。Crashlytics（Firebase 的 crash 回報服務）的 `log()`、`setCustomKey` 就是做這件事。
- **區分 fatal 與 non-fatal。** fatal 是讓 App 當掉的 crash；non-fatal 是 App 沒當掉但值得記錄的錯誤，例如資料解析失敗、被拒絕的下單。但**不可行動的雜訊要過濾**，例如被當成錯誤回報的取消事件，否則真正的問題會被淹沒。

```swift
import os
import Foundation

let logger = Logger(subsystem: "jp.example.app", category: "order")
let orderId = UUID().uuidString

var request = URLRequest(url: URL(string: "https://api.example.com/orders")!)
let requestId = UUID().uuidString
request.setValue(requestId, forHTTPHeaderField: "X-Request-ID")   // 讓 server 也記錄同一個 ID，才能串起 App 與 server 的紀錄

// requestId 不是敏感資料，可以公開；orderId 標成 private，在非除錯環境下不會以明文出現在 log
logger.info("order submit requestId=\(requestId, privacy: .public) orderId=\(orderId, privacy: .private)")
```

```kotlin
// Android：用 OkHttp 的 Interceptor 替每個請求加上關聯 ID，並記錄
class RequestIdInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val requestId = UUID.randomUUID().toString()
        val request = chain.request().newBuilder()
            .header("X-Request-ID", requestId)        // server 也記錄同一個 ID
            .build()
        Timber.d("%s %s requestId=%s", request.method, request.url.encodedPath, requestId)
        return chain.proceed(request)
    }
}
```

iOS 的 `os.Logger`（iOS 14 起）會自動遮蔽動態字串，明確標 `privacy: .private` 讓意圖更清楚；Android 用 Timber 時，release 版本只種「不輸出」或「只送 breadcrumb」的 Tree（Timber 的輸出目的地）。

> [!TIP]
> 面試時可以這樣說：「我把可觀測性分成 log、指標和追蹤。log 用結構化欄位，每筆帶關聯 ID，才能把一次下單從 App 串到 server；隱私上 token、地址、餘額一律不記。指標看整體趨勢，追蹤看一次操作卡在哪一段。」

### 2. 上線之後要監控哪些指標？用什麼工具？

**除了 crash，還要看效能、以及更重要的業務流程指標。** 技術指標告訴你 App 有沒有壞，業務指標告訴你使用者有沒有辦法完成他要做的事。

**業務指標（對交易所特別重要）：**

- 下單成功率與延遲
- WebSocket（即時行情用的連線）的連線成功率與重連次數
- 首次看到價格的時間（time-to-first-price）
- API 各個端點的 p95 延遲與錯誤率
- eKYC 完成率（線上身分驗證有多少人走完）

**技術指標與工具：**

| 平台 | 工具 | 說明 |
|---|---|---|
| iOS | MetricKit | Apple 提供的框架，系統約每天一次交付聚合後的 `MXMetricPayload`（啟動時間、hang、記憶體等）；iOS 14 起的 `MXDiagnosticPayload` 可提供 crash、hang、CPU 例外、磁碟寫入例外的診斷。不是即時資料 |
| iOS | Xcode Organizer、`os_signpost` | Organizer 看線上聚合資料；signpost 在程式碼裡標記自訂的時間區間 |
| Android | Firebase Performance | 自動量測 App 啟動、畫面渲染（慢的 frame、凍結的 frame）、HTTP 請求；可以加自訂 trace |
| Android | Play Console 的 Android vitals、JankStats | vitals 看 crash、ANR（App 沒有回應）、啟動、渲染、耗電；JankStats 是 Jetpack 的函式庫，在 App 內量測卡頓 |
| 兩者 | Crashlytics | crash 與 non-fatal 錯誤；velocity alert 會在某個問題突然暴增時通知 |
| 兩者 | Macrobenchmark 或基準測試 | 發版前的效能比對，避免效能退化 |

MetricKit 的用法是註冊一個 subscriber，系統會在背景定期交付資料：

```swift
import MetricKit

final class MetricsSubscriber: NSObject, MXMetricManagerSubscriber {
    // 約每天一次，收到聚合後的指標
    func didReceive(_ payloads: [MXMetricPayload]) {
        for payload in payloads {
            // 可以取出啟動時間、記憶體等；通常整包 JSON 送到自己的後端，在看板上看趨勢
            let json = payload.jsonRepresentation()
            print("收到指標資料：\(json.count) bytes")
        }
    }

    // iOS 14 起：crash、hang 等診斷資料
    @available(iOS 14.0, *)
    func didReceive(_ payloads: [MXDiagnosticPayload]) { }
}

// App 啟動時註冊：MXMetricManager.shared.add(MetricsSubscriber())
```

因為 MetricKit 約每天交付一次，適合看趨勢和分布（搭配 Xcode Organizer），不適合即時告警。即時性要靠 Crashlytics 這類 SDK（software development kit，第三方提供的開發套件）。MetricKit 在 release 版本（含 TestFlight 與 App Store）才有真實的使用者資料；把資料送到自己的後端時，要依隱私政策處理。

> [!TIP]
> 面試時可以這樣說：「我會同時看技術指標和業務指標：crash-free users、ANR、啟動和渲染，以及下單成功率、WebSocket 連線成功率、首次看到價格的時間、API 的 p95。工具上 iOS 用 MetricKit 和 Organizer 看趨勢，Android 用 Play vitals 和 Firebase Performance，即時告警靠 Crashlytics。」

### 3. 新版本發布時，怎麼用指標決定要不要繼續擴大？

**分階段發布，每一階段先看指標，達標才擴大；任何一項指標低於門檻就暫停。** 這個「要過這道檢查才能擴大」的規則，叫 release gate。

**兩個商店的分階段機制：**

- **iOS 的 Phased Release for Automatic Updates：** 7 天內自動分批，比例依序是 1%、2%、5%、10%、20%、50%、100%。可以暫停（最長 30 天）。只影響自動更新的使用者，使用者仍然可以在 App Store 手動更新，所以不是絕對的 1%。
- **Google Play 的 staged rollout（階段式推出）：** 自己選要推給多少百分比，可以暫停、提高比例或停止。

**流程：**

1. 內測（TestFlight、Firebase App Distribution）：跑 release build 的 smoke test（快速跑一遍關鍵流程）與效能比對。
2. 提交審核，並備妥這個版本的符號檔（dSYM、mapping，前面「App 為什麼會當掉」單元說明過）。
3. 階段式推出，先推 1% 到 5%，等待足夠的使用者量與時間。
4. 對照 gate 指標。
5. 達標就擴大比例；不達標就暫停，修好之後再繼續。
6. 100% 之後持續觀察，並清理已經穩定的 feature flag。

**gate 指標要「事前定義、可量化、和上一版比較」：** crash-free users（或 sessions）、ANR 率、關鍵流程成功率（下單成功率）、API 錯誤率與 p95。要注意樣本數：剛開始只推 1% 的時候，使用者還不多，數字的跳動很大，需要等到有足夠的使用者量才有意義。

**情境：** 交易所發布新版下單頁，推到 5% 之後，下單成功率比上一版低了 2 個百分點。照 gate 的規則，立刻暫停放量，查出原因、修好再繼續；如果事前沒有定義門檻，團隊可能要吵很久才決定要不要擴大。

> [!TIP]
> 面試時可以這樣說：「發布時我會先定義 gate 指標，例如 crash-free users、ANR、下單成功率、API 錯誤率，和上一版比較。新版本分階段推出，先 1% 到 5%，等樣本足夠，指標都達標才擴大；任何一項低於門檻就暫停放量。」

### 4. 商店不能回復版本，出問題時有哪些安全網？（kill switch、強制更新）

**App Store 和 Google Play 都沒有真正的回復：已經更新的使用者不會自動退回舊版。** 所以安全網有四道：暫停放量、kill switch、強制更新、hotfix。

1. **暫停放量。** 讓還沒更新的使用者不再受影響（第 3 題）。
2. **kill switch：遠端關閉有問題的功能。** kill switch 是一個遠端的開關（用 Remote Config，也就是 Firebase 提供的遠端設定服務，或自己的 server 設定），出事時把新功能關掉，App 退回舊的實作或隱藏入口。設計重點：
   - **預設值寫在 App 裡**：連不上設定時，高風險的新功能預設關閉。
   - **App 啟動時與回到前景時重新取得設定**，並把上一次的結果存起來，離線時也能用。
   - **關閉要能立刻生效**，不能等下一次發版。
   - 開關只決定「功能出不出現」，不能當權限判斷，權限一律在 server。
3. **強制更新：要求使用者更新到最低版本。** server 或 Remote Config 提供「最低支援版本」，App 啟動時比較自己的版本，低於就顯示更新畫面，擋住使用，引導到商店。
4. **hotfix 流程：** 預先備好 CI（自動建置與測試的流程）、符號檔、加速審核的做法，出事時能盡快送出修正版。

版本號比較有個常見的坑：不能直接比字串，因為字串比較時 `"2.10.0"` 會排在 `"2.9.0"` 前面。要用數字比較：

```swift
import Foundation

// 目前版本低於最低支援版本，就需要強制更新
func needsForceUpdate(current: String, minimum: String) -> Bool {
    current.compare(minimum, options: .numeric) == .orderedAscending
}

print(needsForceUpdate(current: "2.9.0", minimum: "2.10.0"))   // 輸出：true（2.9.0 比 2.10.0 舊）
print(needsForceUpdate(current: "2.10.0", minimum: "2.9.0"))   // 輸出：false
```

Android 除了自己比較版本，還可以用 Google Play 的 In-App Updates（應用程式內更新）：立即更新（IMMEDIATE，全螢幕、必須更新完才能繼續用）或彈性更新（FLEXIBLE，在背景下載、使用者決定何時重新啟動）。

```kotlin
// 需要 com.google.android.play:app-update 函式庫
val appUpdateManager = AppUpdateManagerFactory.create(context)
appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
    val canUpdate = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
        info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
    if (canUpdate) {
        // 啟動「立即更新」：全螢幕流程，更新完才能繼續使用
        appUpdateManager.startUpdateFlowForResult(
            info, updateLauncher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        )
    }
}
```

強制更新會中斷使用者，是最後手段。一般的新版本先用「建議更新」；只有嚴重的 bug 或資安問題才強制。交易所尤其要小心：使用者可能正在看行情、準備下單，更新畫面要說清楚原因，並盡量不要在交易進行中彈出。

> [!TIP]
> 面試時可以這樣說：「商店不能回復版本，所以我會準備四道安全網：暫停放量、kill switch、最低版本強制更新和 hotfix 流程。kill switch 的預設值寫在 App 裡，高風險功能預設關閉，並且在啟動和回到前景時重新取得設定。」

### 5. analytics 事件怎麼設計，才能算出漏斗又不洩漏資料？

**事件要有統一的命名和 schema（欄位定義），包一層自己的介面，並且不把個資和金額明細送給第三方。** analytics 是分析使用者行為的工具，例如 Amplitude、Google Analytics、Mixpanel。

重點：

- **統一命名與 schema。** 例如 `order_submitted`，屬性有型別、有版本。如果同一件事有三種寫法，漏斗就算不出來。
- **包一層自己的 `AnalyticsClient` 介面。** 好處是換供應商時只改一個地方、測試時可以斷言「事件有送出」、集中處理使用者同意（iOS 的 ATT，也就是 App Tracking Transparency，App 追蹤透明度；歐盟的 GDPR，也就是 General Data Protection Regulation 個資法，與 Google 的 UMP，也就是 User Messaging Platform，同意管理工具）與去識別。
- **記錄漏斗每一步的延遲與失敗原因，不只是計數。** 這樣才能回答「為什麼使用者掉下去」。交易所的下單漏斗是：`order_form_viewed`（看到下單表單）、`order_submitted`（送出）、`order_acked`（server 確認收到）、`order_filled`（成交）。
- **不要把個資、地址、餘額、token 放進第三方分析。** 需要客服查詢的資料，走自己的、有存取控制的後端，用去識別的 ID 關聯。

```swift
// 包一層自己的介面：事件有明確的型別，不會打錯名稱
protocol AnalyticsClient {
    func track(_ event: AnalyticsEvent)
}

enum AnalyticsEvent {
    case orderFormViewed(symbol: String)
    case orderSubmitted(symbol: String, latencyMs: Int)
    case orderFailed(symbol: String, reason: String)

    var name: String {
        switch self {
        case .orderFormViewed: return "order_form_viewed"
        case .orderSubmitted: return "order_submitted"
        case .orderFailed: return "order_failed"
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「analytics 我會包一層自己的 AnalyticsClient，事件用 enum 定義名稱和屬性，避免同一件事有多種寫法。漏斗每一步都記錄延遲和失敗原因，個資、地址和餘額不送到第三方。」

### 6. A/B 測試有哪些統計陷阱？

**A/B 測試是把使用者隨機分成兩組，一組看到 A 版、一組看到 B 版，比較哪一個效果比較好。** 要得到可信的結果，要注意幾個陷阱：

- **事先決定假設、主要指標和 guardrail metric（不能變差的指標）。** 主要指標是你想改善的（例如開戶完成率）；guardrail metric 是無論如何都不能變差的（crash、延遲、客訴）。只看主要指標，可能改善了一個數字，卻拖垮了別的。
- **以使用者為單位隨機分組，並記錄曝光（exposure）。** 曝光是指「這位使用者真的看到了這個版本」。同一位使用者在 iOS 與 Android 上的分組要一致（用 user id 分流）。
- **事先決定樣本數與期間，不要偷看就提前下結論。** 每看一次結果都有機會碰巧看到「顯著」，看得越多次，誤判的機率越高。
- **要檢查 SRM（sample ratio mismatch，樣本比例不符）。** 設計是 50% 對 50%，實際卻是 52.5% 對 47.5%，而且樣本數夠大、偏離顯著，代表分流或事件記錄有 bug（例如某一組的畫面更容易 crash 或載入失敗，事件因此遺失），這時轉換率的比較已經被汙染，結果不可信。要先查出問題再重跑。
- **交易所的特別考量：** 不要對金錢流程的正確性、風險揭露做實驗；實驗限於 UI、引導與文案。日本對加密資產的廣告與風險揭露有業界的自主規範（這是一般知識，需以官方資料為準）。

工具：Firebase Remote Config 加 A/B Testing，或自己架設的 GrowthBook。

> [!TIP]
> 面試時可以這樣說：「A/B 測試我會先定義主要指標和 guardrail metric（不能變差的指標），事先決定樣本數，不偷看結果；分組完成後先檢查 SRM，比例明顯偏離就代表分流或事件記錄有問題，結果不能用。交易所的金錢流程和風險揭露，我不會拿來做實驗。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced10Observability**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Advanced10Observability**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced10-Observability/`，解答在 `labs/ios/Solution/Advanced/Advanced10-Observability/`；Android 你寫在 `labs/android/practice/advanced/advanced10-observability/`，解答在 `labs/android/solution/advanced/advanced10-observability/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

可觀測性本身是「看資料」的事，Crashlytics、Play vitals、MetricKit 都是 SDK 或後台，沒辦法在專案裡實作。所以這個單元的演練挑的是**資料送出去之前、算出來之後會出錯**的部分：送了不該送的、算了不該這樣算的、決定下得太早的。所有的後台（log 服務、分析服務、遠端設定、實驗平台）都是演練裡自己造的假物件。

### 演練 1：遠端 log 裡有 token、地址和餘額（第 1 題）

**檔案**：`L01_LogRedaction.swift`（iOS）、`L01_LogRedaction.kt`（Android）

**情境**：下單時 App 把一行 log 送到遠端 log 服務。log 裡有 `requestId`（要留著，才能串起 App 與伺服器的紀錄），也被不小心寫進了 token、錢包地址、餘額。另外有兩種常見的洩漏：token 出現在網址的查詢參數裡、錯誤訊息裡印出整個 `Authorization` 標頭。演練的 log 服務只是一個陣列，token 與地址都是編出來的。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`redact` 原封不動回傳。
2. **修正版**：用正規表示式依序套用遮罩規則（iOS 用 `replacingOccurrences(of:with:options: .regularExpression)`，Android 用 `Regex.replace`）：`Bearer` 後面的 token、`token`／`password`／`secret` 參數的值、`balance` 開頭欄位的值換成 `***`；`bc1` 開頭的錢包地址只留頭尾，中間用「…」代替。`requestId` 不是敏感資料，保留。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `含完整 token 的行：3`，`含完整錢包地址的行：1`：三行 log 都洩漏了 token |
| 修正版 | `含完整 token 的行：0`，`含完整錢包地址的行：0`，`含 requestId 的行：3`；地址變成 `bc1qxy2…0wlh`，餘額變成 `balanceJPY=***` |

**為什麼**：遠端 log 會被存在第三方、被很多人查詢，萬一外洩，token 在有效期內可以直接登入。token 在這三行 log 裡用三種不同的樣子出現（欄位、網址查詢參數、標頭），只處理一種不夠。更好的做法是一開始就不要把敏感值寫進 log（iOS 的 `os.Logger` 用 `privacy: .private`，Android 的 Timber 在 release 不種會輸出的 Tree），遮罩是最後一道防線（第 1 題：隱私優先）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L01_LogRedaction.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L01_LogRedaction.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L01_LogRedaction.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L01_LogRedaction.kt#fixed
```

### 演練 2：三個請求同時跑，log 的 request id 對不上（第 1 題）

**檔案**：`L02_RequestIdLost.swift`（iOS）、`L02_RequestIdLost.kt`（Android）

**情境**：每個請求進來時，App 替它記一個 request id，之後這個請求的所有 log 都帶著它，才能把一次操作從 App 串到伺服器。演練同時處理三個請求（`req-1`、`req-2`、`req-3`），每個請求「開始 → 等回應 → 收到回應 → 寫入（iOS 是開一個子工作，Android 是切到 `Dispatchers.IO`）」。每一行 log 前面印出 logger 認為現在是哪個 id，後面寫這一行真正屬於哪個請求，兩邊對得上才正確。

**怎麼寫**

1. **Bug 版**：iOS 把 id 存進全域變數 `requestId`；Android 存進 `ThreadLocal`。請求開始時設定，之後的 log 都讀它。
2. **修正版**：iOS 用 `@TaskLocal`，`$requestId.withValue(id) { … }` 包住整個請求的處理；Android 用 `withContext(requestIdLocal.asContextElement(id)) { … }`，把值放進 `CoroutineContext`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 多行 `[id]` 和後面的請求對不上。iOS：`共 9 行 log，request id 對不上的有 4 行`（先醒來的請求讀到最後設定的 `req-3`）；Android：對不上的有 5 行，另外切到 IO 執行緒之後 id 變成 `—`（ThreadLocal 在那條執行緒上沒有值） |
| 修正版 | `共 9 行 log，request id 對不上的有 0 行` |

**為什麼**：全域變數和 `ThreadLocal` 綁的都不是「這個請求」：三個請求交錯執行時互相蓋掉，coroutine 或 `Task` 換執行緒之後也跟丟。`@TaskLocal` 與 `asContextElement` 把值綁在請求的非同步工作上，不管中間 `await`、換執行緒、開子工作，都讀得到自己的 id。這是追蹤（trace）能串起來的前提；再延伸到 App 與伺服器之間，就是把 id 放進 `X-Request-ID` 標頭（OkHttp 的 `Interceptor`、`URLRequest` 的標頭）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L02_RequestIdLost.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L02_RequestIdLost.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L02_RequestIdLost.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L02_RequestIdLost.kt#fixed
```

### 演練 3：平均 311 ms 很健康，卻有 6% 的人等了 3 秒（第 2 題）

**檔案**：`L03_Percentile.swift`（iOS）、`L03_Percentile.kt`（Android）

**情境**：新版本放量時，release gate 看下單 API 的延遲。演練有 200 筆請求：188 筆在 100 到 180 毫秒之間，12 筆（6%）因為伺服器逾時重試，花了 3000 毫秒。gate 的門檻是 1000 毫秒，超過就暫停放量。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：看板顯示平均延遲（總和除以筆數）。
2. **修正版**：看 p95。用 nearest-rank 法：排序後取第 ⌈0.95 × 筆數⌉ 筆（名次從 1 開始算，陣列索引要減 1）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `看板顯示：平均 311 ms`，`release gate（門檻 1000 ms）：通過，繼續放量` |
| 修正版 | `看板顯示：p95 3000 ms`，`release gate（門檻 1000 ms）：不通過，暫停放量` |

**為什麼**：延遲的分布不是對稱的鐘形，少數很慢的請求會被大量正常的請求稀釋掉：平均 311 ms，聽起來很健康，但每 100 位使用者就有 6 位下單要乾等 3 秒。p50 看一般人的感受，p95、p99 才看得到最慢的那群人（第 1 題的 p95 定義、第 2 題的「API 的 p95 延遲」）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L03_Percentile.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L03_Percentile.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L03_Percentile.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L03_Percentile.kt#fixed
```

### 演練 4：40 位使用者的數字，就決定要不要擴大（第 3 題）

**檔案**：`L04_ReleaseGate.swift`（iOS）、`L04_ReleaseGate.kt`（Android）

**情境**：新版本分階段放量，每階段結束時 gate 要決定「擴大」或「暫停」，比較的指標是 crash-free users，基準是上一版的 99.2%。演練有四個階段的資料：A（放量 1%，40 位，0 位 crash）、B（放量 1%，40 位，1 位 crash）、C（放量 5%，2000 位，60 位 crash）、D（放量 5%，2000 位，14 位 crash）。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：只比數字：新版 crash-free users 大於等於上一版就「擴大」，否則「暫停」。
2. **修正版**：先看樣本數：使用者不到 1000 位就回傳「等待（樣本不足，繼續收集）」；樣本夠了才比較，並容許 0.3 個百分點的誤差。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | A「擴大」、B「暫停」：只差 1 位使用者 crash，結論卻相反；C「暫停」、D「擴大」 |
| 修正版 | A、B：「等待（樣本不足，繼續收集）」；C：「暫停」；D：「擴大」 |

**為什麼**：40 位使用者的 100%，和 40 位使用者裡有 1 位 crash 的 97.5%，在統計上幾乎無法區分，一位使用者的偶然就改變了結論。第 3 題說的「剛開始只推 1% 的時候，使用者還不多，數字的跳動很大，需要等到有足夠的使用者量才有意義」，在 gate 的邏輯裡就是這個「樣本不足就等待」的分支。門檻（最少使用者數、容許誤差）要事前定義，不是看到數字之後才決定。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L04_ReleaseGate.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L04_ReleaseGate.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L04_ReleaseGate.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L04_ReleaseGate.kt#fixed
```

### 演練 5：按了 kill switch，離線重開 App 功能又出現（第 4 題）

**檔案**：`L05_KillSwitch.swift`（iOS）、`L05_KillSwitch.kt`（Android）

**情境**：新的出金流程放在遠端 kill switch（`newWithdrawFlow`）後面，平常是開的。演練依序發生：① 使用者在線上啟動 App，遠端是開；② 營運按下 kill switch（遠端改成關），使用者把 App 切到背景再回到前景；③ 使用者進了地鐵（沒有網路），重新啟動 App；④ 另一位新使用者在沒有網路的地方，剛裝好 App 就打開。「遠端設定」與「本機儲存」是假物件，模擬 Remote Config 與 UserDefaults／DataStore。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`onLaunch` 只取一次遠端設定，存進記憶體；`onForeground` 什麼都不做；取不到時用程式裡的預設值 `true`；沒有把結果存起來。
2. **修正版**：`onLaunch` 與 `onForeground` 都重新取得，成功就存進本機儲存；`isEnabled` 讀本機存的值；連存的值都沒有，用保守的預設值 `false`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ① 開、② 開（回到前景沒有重新取得）、③ 開（離線用了預設值）、④ 開（高風險功能預設是開的） |
| 修正版 | ① 開、② 關、③ 關（離線用上次存的值）、④ 關（保守的預設值） |

**為什麼**：kill switch 是商店不能回復版本時的安全網，所以它必須「立刻生效」而且離線也有效（第 4 題）：回到前景時重新取得、把上一次的結果存起來、取不到用存的值、連存的都沒有，高風險功能預設是關。開關只決定功能出不出現，不能當權限判斷，權限一律在伺服器。要求更新到最低版本的強制更新，是另一道安全網，版本比較不能用字串，在平台對照的 Platform07Release 演練 2 做過。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L05_KillSwitch.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L05_KillSwitch.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L05_KillSwitch.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L05_KillSwitch.kt#fixed
```

### 演練 6：事件算了兩次，又有事件不見了（第 5 題）

**檔案**：`L06_EventQueue.swift`（iOS）、`L06_EventQueue.kt`（Android）

**情境**：App 要把下單漏斗的三個事件送到分析服務：`order_form_viewed`、`order_submitted`、`order_acked`。網路狀況依序是：送第一個時伺服器收到了，但回應遺失，App 看到逾時；送第二個時沒有網路；送第三個時網路恢復。最後使用者回到前景，App 再試著送一次。分析服務是假的，它記下每個事件被算了幾次；事件有 `eventId` 的話，它會去除重複（idempotent）。

**怎麼寫**

1. **Bug 版**：`track` 直接送出（事件沒有 id），失敗就立刻重送一次，還是失敗就丟掉；`flush` 什麼都不做。
2. **修正版**：`track` 把事件放進本機佇列，每個事件用 `UUID` 當 `eventId`；`flush` 整批送出，成功才清掉佇列，失敗（逾時、沒有網路）就留著，下次再送。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `伺服器算到：order_form_viewed ×2、order_submitted ×0、order_acked ×1`：一個重複、一個遺失，總數剛好還是 3 次 |
| 修正版 | `伺服器算到：order_form_viewed ×1、order_submitted ×1、order_acked ×1` |

**為什麼**：「逾時」不代表伺服器沒收到，沒有 id 的重送就會重複計算；沒有網路時送不出去的事件，如果沒有本機佇列就直接消失。這兩個問題會讓漏斗上的數字錯得很隱密：總數看起來剛好對。解法是兩個一起用：本機佇列保證不丟，唯一的 `eventId` 讓重送變得安全（第 5 題；和出金請求不能直接重送的 `clientOrderId` 是同一個道理）。真正的 App 把佇列存在檔案或資料庫，App 被終止後還在。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L06_EventQueue.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L06_EventQueue.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L06_EventQueue.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L06_EventQueue.kt#fixed
```

### 演練 7：抽樣之後，漏斗的轉換率從 60% 變 10%（第 5 題）

**檔案**：`L07_SamplingByUser.swift`（iOS）、`L07_SamplingByUser.kt`（Android）

**情境**：為了省流量與費用，App 對事件只送 10%。演練有 2000 位使用者，每位都有 `order_form_viewed`，其中 60% 有 `order_submitted`、50% 有 `order_acked`、40% 有 `order_filled`；另有 40 位使用者遇到 1 次 `error` 事件。分析端要算：「有 `order_form_viewed` 紀錄的使用者，有多少比例也有 `order_submitted` 紀錄」，真實值是 60%。

**怎麼寫**

1. **Bug 版**：每個事件各擲一次骰子（隨機數小於 10% 才送）。
2. **修正版**：以使用者為單位抽樣：用 `userId` 算出固定的分桶（演練已經寫好 FNV-1a 雜湊的 `bucket`），桶號小於 10 的使用者所有事件都送；`error` 事件不抽樣，一律送。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 轉換率只剩一成以下（解答實測 iOS 8%、Android 3%，真實是 60%）；`error` 事件只收到幾筆（iOS 1 筆、Android 9 筆，實際 40 筆） |
| 修正版 | 轉換率 56%（兩個平台相同，以收到的 209 位使用者計算，接近真實的 60%）；`error` 事件收到 40 筆 |

**為什麼**：每個事件各自抽，一位使用者的 `order_form_viewed` 和 `order_submitted` 同時被送出的機率只有 10% × 10%，分析端看到的使用者大多只剩零碎的事件，漏斗完全算不出來。以使用者為單位抽樣，被抽到的使用者漏斗是完整的，只是人數變少（分析端再依抽樣比例還原總量）；固定的雜湊還能讓同一位使用者在 iOS、Android、每次啟動都得到一樣的結果。error 這類少數卻重要的事件，抽樣會把真正的問題漏掉，一律送（第 5 題；第 1 題：遠端 log 要有取樣與預算，error 一定要送）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L07_SamplingByUser.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L07_SamplingByUser.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L07_SamplingByUser.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L07_SamplingByUser.kt#fixed
```

### 演練 8：人數比例不對，卻宣布 B 勝出（第 6 題）

**檔案**：`L08_SampleRatioMismatch.swift`（iOS）、`L08_SampleRatioMismatch.kt`（Android）

**情境**：三個實驗都設計成 50% 對 50% 分流。實驗 1：A 50080 人、B 49920 人；實驗 2：A 52500 人、B 47500 人；實驗 3：A 50800 人、B 49200 人。三個實驗都是 B 的轉換率較高。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`hasSRM` 一律回傳 `false`，哪一組轉換率高就宣布哪一組勝出。
2. **修正版**：做卡方檢定（自由度 1）：預期每組是總人數的一半，卡方值是兩組各自的 (實際 − 預期)² ÷ 預期，加起來；大於 10.83（p 值 0.001 的門檻）就回傳 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 三個實驗都是「B 勝出」 |
| 修正版 | 實驗 1（卡方值約 0.5）：「B 勝出」；實驗 2（約 250）、實驗 3（約 25.6）：「分流異常（SRM），結果不可信，先查原因」 |

**為什麼**：SRM（sample ratio mismatch，樣本比例不符）代表分流或事件記錄有 bug，例如 B 組的畫面更容易 crash 或載入失敗，事件因此遺失；這時轉換率的比較已經被汙染，B「贏」的結果不可信（第 6 題）。50080 對 49920 是隨機誤差，52500 對 47500 不是；先檢查人數比例，再看轉換率。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L08_SampleRatioMismatch.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced10-Observability/L08_SampleRatioMismatch.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced10-observability/L08_SampleRatioMismatch.kt#bug
```

```lab-solution
android/solution/advanced/advanced10-observability/L08_SampleRatioMismatch.kt#fixed
```

第 3 題的 release gate 只做了「樣本數」這一個容易出錯的部分（演練 4）；階段式發布的機制本身（Phased Release 的 1%、2%、5%…，Play 的 staged rollout）是商店後台的設定，沒有演練。第 2 題的 MetricKit、Xcode Organizer、Play vitals、Firebase Performance 是工具與後台，只能在真實的上線資料上看，也沒有演練；用 `os_signpost` 與 Firebase Performance 的自訂 trace 量測的是時間，結果每次不固定，不適合自動檢查。

## 常見陷阱

- **只看 crash，不看 ANR、OOM（記憶體不足被終止）與關鍵流程成功率。** 使用者無法下單的原因不一定是 crash。
- **gate 指標沒有基準，或樣本數太小。** 剛放量的 1% 數字不可靠，要等樣本足夠。
- **在 log、analytics 或 Crashlytics 的 custom key 裡放 token 或地址。** 這些資料會被存在第三方。
- **以為商店可以回復版本。** 沒有 kill switch 與強制更新機制，出事時只能乾等審核。
- **版本號用字串比較。** `"2.10.0"` 會被判斷成比 `"2.9.0"` 舊。
- **分析事件沒有 schema。** 同一件事有三種名稱，漏斗算不出來。
- **A/B 沒檢查 SRM，或反覆偷看結果。** 得到的結論可能只是碰巧。

## 面試官想聽到的

1. 把「監控」從 crash 擴大到效能、業務流程與隱私，並知道各工具的定位。
2. 有明確的 release gate：指標、門檻、分階段發布的階段，以及暫停的條件。
3. 知道商店不能回復版本，所以有 kill switch、強制更新和 hotfix 的流程。
4. 對 analytics 與 log 有隱私意識，並包裝成可測試的抽象。
5. 對 A/B 的統計陷阱（SRM、偷看、guardrail metric）有概念。

> [!FIELD]
> 在一個社交 App，我用 Crashlytics 監控穩定性，並以 Fastlane 與 Codemagic 在打包時把 dSYM 上傳到 Crashlytics，也過濾掉「Job was cancelled」這類雜訊；分析用過 Amplitude、Google Analytics 與 Firebase，並使用 Firebase Remote Config；發布時透過 Fastlane 搭配 Slack 通知。我長期負責 App Store Connect 與 Google Play Console 的送審與發布。
> 在一個即時行情 App，iOS 端把 API 錯誤與資料解析失敗記到 Crashlytics，Android 端把網路錯誤記到 Firebase；分析用 Mixpanel，並用 Fastlane 發布到 TestFlight 與 Firebase App Distribution，附上 release notes、發送 Slack 通知。
> 換成交易所 App，我會把這些基礎補成完整的流程：release gate（crash-free users、ANR、下單成功率）加分階段發布、kill switch 與最低版本強制更新，以及以業務指標為主的看板。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 系統 log | `os.Logger`（可標 privacy） | Logcat 與 Timber |
| 線上效能與穩定性 | MetricKit、Organizer、Crashlytics | Play vitals、Firebase Performance、Crashlytics |
| 自訂時間區間 | `os_signpost` | Firebase Performance 的自訂 trace、`Trace.beginSection` |
| 分階段發布 | Phased Release（7 天，可暫停） | 階段式推出（自選百分比） |
| 強制更新與 kill switch | Remote Config 的最低版本、feature flag | 同；另有 Play In-App Updates（立即更新、彈性更新） |
| A/B 測試 | Remote Config 加 A/B Testing、GrowthBook | 同 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 可觀測性 | observability | オブザーバビリティ |
| 遠端關閉功能的開關 | kill switch | キルスイッチ |
| 最低支援版本的強制更新 | force update | 強制アップデート |
| 分階段發布 | phased release／staged rollout | 段階的リリース／段階的ロールアウト |
| 要過這道檢查才能擴大發布的規則 | release gate | リリースゲート |
| 樣本比例不符 | SRM（sample ratio mismatch） | サンプル比率の不一致 |
| 不能變差的指標（例如 crash、延遲） | guardrail metric | ガードレール指標 |
