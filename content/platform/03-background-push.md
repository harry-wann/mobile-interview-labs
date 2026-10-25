---
id: platform-background-push
title: 背景執行與推播：為什麼 App 在背景不能一直跑、APNs 與 FCM 怎麼送通知
summary: 兩個平台的系統都會限制 App 在背景的執行，不保證準時，甚至不保證會執行。交易所的價格提醒、成交通知因此要靠伺服器推播，不能靠 App 自己在背景輪詢。這個單元說明各種背景機制的限制、推播從伺服器到手機的流程，以及價格提醒怎麼設計。
priority: P0
day: 2
tags: [background, bgtaskscheduler, silent-push, workmanager, doze, fcm, apns, price-alert]
---

## 重點講解

先講一個貫穿整個單元的情境：使用者在交易所 App 設定「BTC/JPY 漲到 1,000 萬日圓就通知我」，然後把手機放進口袋，App 在背景，甚至已經被系統結束。價格到了，手機要在幾秒內響。這個功能怎麼做？答案是靠伺服器推播，而不是靠 App 在背景自己盯著價格。這個單元要說明為什麼。

這個單元回答七個面試常見問題：

1. 為什麼 App 在背景不能一直跑？
2. iOS 有哪些背景執行的機制？各有什麼限制？
3. Android 有哪些背景執行的機制？Doze 和 App Standby 是什麼？
4. 推播怎麼從伺服器送到手機？token 是什麼？
5. APNs 和 FCM 的訊息有哪幾種？優先權、collapse、TTL 是什麼？
6. 價格提醒要怎麼設計？
7. 通知權限與 notification channel 要注意什麼？（比較少考，被問到能答就好）

### 1. 為什麼 App 在背景不能一直跑？

**因為手機的電池、記憶體和網路都有限。兩個平台的系統為了省電、讓前景的 App 順暢，會積極限制背景的 App，所以背景工作不保證準時，甚至不保證會執行。** 需要「即時送達」的訊息，要靠伺服器透過 Apple 或 Google 的推播服務發送。

想像每個 App 都在背景持續跑：連著伺服器、定時輪詢、不停定位。手機電池幾個小時就沒電，前景的 App 也因為記憶體和 CPU 被搶走而變慢。所以兩個平台都在系統層級設限：

| | iOS | Android |
|---|---|---|
| 進背景之後 | 很快被暫停（suspended）：程式碼完全不執行，連 timer 都不會再觸發 | 幾分鐘後，背景的 service 被系統停止（Android 8，API 26 起的 background execution limits） |
| 長時間不用 | 記憶體不足時被系統直接結束，沒有通知 | Doze（Android 6）：手機靜止、沒充電、螢幕關閉一段時間後，延後網路、任務、鬧鐘到特定時間才一起執行 |
| 不常開的 App | 系統依使用習慣，決定要給多少背景時間 | App Standby Bucket（Android 9，API 28）：依使用頻率分級，越少用限制越多 |
| 使用者主動關掉 | 在 App 切換器上滑強制關閉：背景更新和 silent push 都不會再喚醒它，直到使用者再次手動開啟 | 部分手機品牌（OEM）的省電功能會額外結束背景行程，影響推播到達 |

所以，「進背景後用 timer 每分鐘去抓價格」這種想法行不通：

```swift
// iOS：想在背景每分鐘檢查價格
Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { _ in
    checkPrice()
}
// 結果：App 進背景幾秒後就被暫停，這個 timer 不會再觸發
```

```kotlin
// Android：想用 Handler 在背景每分鐘檢查價格
handler.postDelayed(object : Runnable {
    override fun run() {
        checkPrice()
        handler.postDelayed(this, 60_000)
    }
}, 60_000)
// 結果：App 進背景、行程被降級或結束後，這個迴圈就停了；Doze 時網路也被延後
```

兩個平台都有「正式的背景機制」，但它們的設計目標是「系統覺得適合的時候，順便幫你做」，不是「準時執行」。下面兩題分別說明。

> [!TIP]
> 面試時可以這樣說：「背景不能一直跑，是因為電池、記憶體和網路有限。iOS 的 App 進背景很快就被暫停，Android 有 Doze、App Standby 和背景執行限制，兩邊都不保證背景工作準時。所以價格提醒這類需要即時送達的功能，我會讓伺服器判斷、用推播通知，而不是靠 App 在背景輪詢。」

### 2. iOS 有哪些背景執行的機制？各有什麼限制？

**iOS 的背景機制都有時間和次數的限制，而且由系統決定何時執行。** 常考的有六種：

| 機制 | 能做什麼 | 限制 |
|---|---|---|
| `beginBackgroundTask` | 進背景後申請一小段時間做收尾，例如把送到一半的請求送完 | 約 30 秒，由系統決定，不保證；做完要主動結束 |
| `BGAppRefreshTask` | 短時間（約 30 秒）的定期更新，例如預先抓好資料 | `earliestBeginDate` 只是「最早」，不是「排定的時間」，實際何時執行由系統依使用習慣和電量決定；每次執行完要重新排下一次 |
| `BGProcessingTask` | 較長的工作，可要求外接電源、網路 | 通常在裝置閒置、充電時才執行，不保證 |
| Background `URLSession` | 下載或上傳交給系統處理，App 被暫停或被結束後仍會繼續，完成後喚醒 App | 只支援 upload／download 任務，不支援一般的 data task；使用者強制關閉 App 後會被取消 |
| Silent push（靜默推播） | 伺服器發一個不顯示通知的推播，喚醒 App 約 30 秒 | 系統會節流；低電量模式或強制關閉後不會喚醒 |
| Notification Service Extension | 通知顯示前，修改內容（解密、加圖片、在地化） | 約 30 秒、記憶體受限；只對有 alert 的推播有效，推播要帶 `mutable-content: 1` |

`BGTaskScheduler` 是 iOS 13 起提供的背景工作排程 API，要使用它，要先在 Info.plist 的 `BGTaskSchedulerPermittedIdentifiers` 列出工作的識別碼，並在 Xcode 的 Background Modes 勾選對應的能力。使用方式如下：

```swift
import BackgroundTasks

// App 啟動時登記：
// 識別碼要和 Info.plist 的 BGTaskSchedulerPermittedIdentifiers 一致
BGTaskScheduler.shared.register(
    forTaskWithIdentifier: "jp.example.app.refresh",
    using: nil
) { task in
    guard let refresh = task as? BGAppRefreshTask else { return }
    handleRefresh(refresh)
}

func scheduleRefresh() {
    let request = BGAppRefreshTaskRequest(identifier: "jp.example.app.refresh")
    // 最早 15 分鐘後；不代表 15 分鐘後就會執行
    request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
    try? BGTaskScheduler.shared.submit(request)
}

func handleRefresh(_ task: BGAppRefreshTask) {
    // 先排下一次，不然只會執行這一次
    scheduleRefresh()
    let work = Task {
        await syncPortfolio()
        // 做完要告訴系統
        task.setTaskCompleted(success: true)
    }
    // 時間到了系統會呼叫，要立刻收尾
    task.expirationHandler = {
        work.cancel()
        task.setTaskCompleted(success: false)
    }
}
```

另外幾個和推播有關的限制，面試也常問：

- **APNs 推播的大小上限是 4 KB。**
- **Silent push 不是「一定會喚醒 App」**：系統會依電量等條件節流，所以不能拿它當準時的機制。
- **使用者強制關閉 App 之後**，背景更新和 silent push 都不會再喚醒它，直到使用者自己再開啟一次。

> [!TIP]
> 面試時可以這樣說：「iOS 的背景機制都是盡力而為。BGAppRefreshTask 的 earliestBeginDate 只是最早時間，silent push 會被節流，強制關閉之後都不會再喚醒 App，beginBackgroundTask 只有約 30 秒收尾。所以需要準時的事情，我會交給伺服器推播。」

### 3. Android 有哪些背景執行的機制？Doze 和 App Standby 是什麼？

**Android 的標準做法是 WorkManager，但它同樣不準時；再加上 Doze 和 App Standby 會進一步延後背景工作。** 先看你能用的機制：

| 機制 | 能做什麼 | 限制 |
|---|---|---|
| WorkManager | 可延遲、保證「最終會執行」的背景工作；App 被結束、手機重開機後仍會執行；支援條件（網路、充電）、重試與退避 | 週期性工作最短間隔 **15 分鐘**；不準時；Android 12 起 expedited work（盡快執行的工作）有配額 |
| Foreground service（前景服務） | 長時間、使用者看得到的工作，需要常駐通知，例如音樂播放、導航 | Android 12 起，大多不能從背景啟動；targetSdk 34 以上必須宣告 `foregroundServiceType` 與對應權限 |
| Exact alarm（精準鬧鐘） | 準時觸發 | 需要特別的權限（`SCHEDULE_EXACT_ALARM` 或 `USE_EXACT_ALARM`），審核嚴格，不適合價格提醒 |

再看系統加在這些機制上的限制，這也是面試最常問的部分：

| 限制 | 內容 |
|---|---|
| Doze（Android 6） | 手機靜止、沒充電、螢幕關閉一段時間後，系統把網路、任務、鬧鐘延後到maintenance window（系統定期開放、讓大家一起處理的時段）才一起執行；高優先權的 FCM 可以喚醒 |
| App Standby Bucket（Android 9，API 28） | 依使用頻率把 App 分成 Active、Working set、Frequent、Rare 四級；API 30 起還有最嚴格的 Restricted。越少用的 App，任務和鬧鐘越被限制 |
| 背景執行限制（Android 8，API 26） | App 進背景幾分鐘後，背景的 service 會被系統停止 |
| 手機品牌（OEM）的省電功能 | 部分品牌會額外結束背景行程，影響推播到達 |

Doze 是什麼意思？想像手機放在桌上一整晚：靜止、沒充電、螢幕關著。系統判斷「應該沒人在用」，就把所有 App 的網路、任務、鬧鐘都攔下來，每隔一段時間才開放一次 maintenance window 讓大家一起處理，而且時間間隔會越來越長。這時 App 自己排的任務很可能被延後好幾分鐘甚至更久。

WorkManager 的用法如下，注意兩個重點：週期性工作最短 15 分鐘，以及 expedited work 是給「盡快要做完」的一次性工作：

```kotlin
class PortfolioSyncWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // 同步資產、補資料……
        return Result.success()
    }
}

// 週期性工作：最短 15 分鐘，設定更短的值會被提高成 15 分鐘，而且不準時
val periodic = PeriodicWorkRequestBuilder<PortfolioSyncWorker>(
    15,
    TimeUnit.MINUTES,
).setConstraints(
    Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build(),
).build()
WorkManager.getInstance(context)
    .enqueueUniquePeriodicWork(
        "portfolio-sync",
        ExistingPeriodicWorkPolicy.KEEP,
        periodic,
    )

// 一次性、需要盡快做完的工作（例如收到推播後把資料存好）：expedited
val urgent = OneTimeWorkRequestBuilder<PortfolioSyncWorker>()
    // 配額用完時退回一般工作
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    .build()
WorkManager.getInstance(context).enqueue(urgent)
```

手機品牌的省電功能不是 App 能控制的，所以要在說明文件中提醒使用者把 App 加入省電白名單，並讓重要的通知使用高優先權的訊息。

> [!TIP]
> 面試時可以這樣說：「Android 的背景工作標準做法是 WorkManager，它保證最終會執行，但週期性工作最短 15 分鐘，也不準時，還會被 Doze 和 App Standby 延後。所以我不會用它做即時價格提醒，只用它做可延遲的同步，需要盡快完成的用 expedited。」

### 4. 推播怎麼從伺服器送到手機？token 是什麼？

**推播不是 App 自己連到伺服器，而是伺服器把訊息交給 Apple（APNs）或 Google（FCM），由它們送到手機。** token 是 APNs 或 FCM 給每支手機上每個 App 的一個「地址」，伺服器靠它指定要送給誰。

APNs（Apple Push Notification service）是 Apple 的推播服務，FCM（Firebase Cloud Messaging）是 Google 的。為什麼要經過中間這一層？因為手機只需要維持一條長連線到 Apple 或 Google，所有 App 的推播共用這一條，既省電、又不需要每個 App 各自在背景保持連線。

完整流程如下：

```text
1. App 向系統要求推播 token
   iOS：registerForRemoteNotifications() → APNs 回傳 device token
   Android：FirebaseMessaging 取得 registration token → FCM 回傳
2. App 把 token 傳給自己的伺服器，綁在使用者帳號上
3. 事件發生（價格到了）→ 伺服器呼叫 APNs 或 FCM，帶著 token 與內容
4. APNs／FCM 把訊息送到手機 → 系統顯示通知，或喚醒 App 處理
```

在 iOS 上，Firebase 的 FCM 也是包裝 APNs：Firebase SDK 拿 APNs 的 device token 換成 FCM token，訊息從 FCM 送到 APNs，再送到手機。

兩個平台的取得 token 方式：

```swift
// iOS：要先取得使用者同意，再註冊遠端推播
UNUserNotificationCenter.current().requestAuthorization(
    options: [.alert, .sound, .badge]
) { granted, _ in
    guard granted else { return }
    DispatchQueue.main.async {
        UIApplication.shared.registerForRemoteNotifications()
    }
}

// AppDelegate：系統把 device token 交給你
func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
) {
    let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
    // 上傳 hex 給伺服器（登入後綁定帳號）
}
```

```kotlin
// Android：繼承 FirebaseMessagingService，token 產生或更換時會呼叫 onNewToken
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // 已登入的話，把新的 token 上傳給伺服器
    }
}

// 也可以主動取得目前的 token
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    // 上傳 token
}
```

token 的管理，是實務上最容易漏掉的地方：

- **token 會變。** 重新安裝、還原備份、清除資料，token 都可能更換，所以 iOS 每次啟動都會取得 token，Android 則在 `onNewToken` 收到新的 token，都要重新上傳。
- **登入後才綁定帳號，登出時要註銷。** 否則已登出的裝置還會收到前一個帳號的通知，有洩露個資的風險。
- **無效的 token 要清掉。** APNs 回傳 410，或 FCM 回報 `UNREGISTERED`，代表這個 token 已經失效（例如 App 被移除），伺服器要把它從資料庫刪掉。

> [!TIP]
> 面試時可以這樣說：「推播是 App 向 APNs 或 FCM 取得 token、上傳給我們的伺服器，事件發生時伺服器帶著 token 呼叫 APNs 或 FCM，由它們送到手機。token 會變，所以每次啟動或 onNewToken 都要更新；登出要註銷，APNs 回 410 或 FCM 回 UNREGISTERED 時要清除。」

### 5. APNs 和 FCM 的訊息有哪幾種？優先權、collapse、TTL 是什麼？

**兩個平台都把訊息分成「系統直接顯示通知」和「只帶資料、由 App 自己處理」兩種，並且可以用優先權、collapse、TTL 控制送達的方式。**

**iOS（APNs）：**

- **alert 推播**：使用者會看到通知。標頭 `apns-push-type: alert`。
- **silent push**：不顯示通知，只喚醒 App 約 30 秒。payload（推播訊息裡實際攜帶的內容）要帶 `"content-available": 1`，標頭 `apns-push-type: background`，而且 `apns-priority` 必須是 `5`（用 10 會被拒絕）。
- `apns-priority`：`10` 代表立即送達，`5` 代表考量省電、不用馬上送。
- `apns-collapse-id`：相同 ID 的新通知會取代尚未顯示的舊通知。
- `apns-expiration`：超過這個時間還沒送到，就直接丟掉。

```text
# alert 推播（使用者會看到通知）
apns-push-type: alert
apns-priority: 10
apns-collapse-id: price-alert-btcjpy

# silent push（只喚醒 App，不顯示通知）：
# payload 要帶 "aps": { "content-available": 1 }
apns-push-type: background
apns-priority: 5
```

**Android（FCM）：**

- **notification message**：App 在背景時，由系統直接顯示通知，`onMessageReceived` **不會被呼叫**。
- **data message**：只帶資料，不論 App 在前景或背景，都會呼叫 `onMessageReceived`，由你決定怎麼處理，處理時間約 20 秒。
- **優先權**：`HIGH` 可以喚醒 Doze 中的手機，建議用在使用者需要立刻看到的通知；`NORMAL` 可以被延後。如果濫用 `HIGH`（沒有顯示使用者看得到的通知），FCM 會把它降級。
- `collapse_key`：新訊息取代尚未送達的舊訊息。`ttl`（time to live，存活時間）：超過這段時間還沒送到的訊息就會被丟掉。

`onMessageReceived` 回傳之後，行程可能很快被系統回收。如果在裡面開一個 fire-and-forget 的協程（丟出去就不管它），工作做到一半就會消失：

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    // 錯誤：CoroutineScope(Dispatchers.IO).launch { save(message) }
    // 方法一回傳後行程可能被回收，協程做到一半就消失

    // 正確：需要確實完成的工作，交給 WorkManager 的 expedited work
    val request = OneTimeWorkRequestBuilder<SaveAlertWorker>()
        .setInputData(workDataOf("alertId" to message.data["alertId"]))
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(this).enqueue(request)
}
```

> [!TIP]
> 面試時可以這樣說：「APNs 的 silent push 要帶 content-available，標頭 push-type 是 background、priority 一定是 5；FCM 的 data message 一定會進 onMessageReceived，notification message 在背景時由系統顯示。collapse 讓舊通知被新的取代，TTL 控制訊息的存活時間。onMessageReceived 裡需要確實完成的工作，我會交給 WorkManager，不會丟 fire-and-forget 的協程。」

### 6. 價格提醒要怎麼設計？

**由伺服器持續比對行情和使用者設定的門檻，觸發時發 alert 推播。** 不能靠 App 在背景比對：BGAppRefreshTask 和 WorkManager 都不準時，WorkManager 最短還要 15 分鐘，silent push 又會被節流，App 被強制關閉時還完全收不到。

以下是兩邊的 payload 範例，內容都帶 `symbol` 和 `alertId`，讓點擊通知時能開啟對應頁面：

```json
{
  "aps": {
    "alert": {
      "title-loc-key": "PRICE_ALERT_TITLE",
      "loc-key": "PRICE_ALERT_BODY",
      "loc-args": ["BTC/JPY", "10,000,000"]
    },
    "sound": "default",
    "interruption-level": "time-sensitive",
    "thread-id": "price-alert-btcjpy"
  },
  "symbol": "btcjpy",
  "alertId": "a1b2c3"
}
```

```json
{
  "message": {
    "token": "<device token>",
    "data": { "type": "price_alert", "symbol": "btcjpy", "alertId": "a1b2c3" },
    "android": {
      "priority": "HIGH",
      "ttl": "600s",
      "collapse_key": "price_alert_btcjpy"
    }
  }
}
```

設計時要處理的事，依重要性排列：

1. **伺服器評估**：伺服器持續比對行情與門檻，觸發時推播。
2. **去重與折疊**：同一個提醒只觸發一次，或設定冷卻時間；用 `apns-collapse-id`／`collapse_key` 讓過時的提醒被新的取代；TTL 設短，價格過了幾分鐘的通知沒有意義。
3. **在地化**：日本的交易所 App 常同時支援日文、簡體中文、繁體中文、英文等多種語言。iOS 可以用 `loc-key` 讓 App 自己查在地化字串，或由伺服器依使用者語系產生文字；價格要依各幣種的小數位數格式化。
4. **優先權與管道**：Android 為價格提醒建立獨立的 notification channel（高重要性），並使用高優先權訊息；iOS 視情況用 time-sensitive，但不要濫用，否則使用者會把通知整個關掉。
5. **點擊後的導覽**：通知帶 `symbol` 與 `alertId`，用 deep link 開啟行情頁，驗證參數，並重新抓最新價格，因為通知到達時價格可能已經變了。
6. **token 管理**：登入後註冊、變更時更新、登出時註銷、無效的清除（見第 4 題）。
7. **隱私**：鎖定畫面上不要顯示持有數量與餘額。
8. **前景去重**：App 在前景且已經在串流行情時，改用 App 內的橫幅顯示，並與推播去重，避免同一個提醒出現兩次。

> [!TIP]
> 面試時可以這樣說：「價格提醒由伺服器比對門檻，觸發時發 alert 推播，因為 client 在背景無法準時，silent push 會被節流，強制關閉後也收不到。我會處理去重、collapse 加短 TTL，文字用在地化 key，Android 建獨立 channel 並用高優先權，點擊後用 deep link 開行情頁並重新抓最新價格。」

### 7. 通知權限與 notification channel 要注意什麼？（比較少考，被問到能答就好）

**iOS 和 Android 13 以上都要使用者同意才能顯示通知；Android 8 以上通知還要歸屬在 notification channel，使用者可以逐個 channel 調整。**

- **iOS**：用 `requestAuthorization` 詢問使用者。詢問時機很重要，在使用者建立第一個提醒時才問，比一啟動就問的同意率高，因為使用者這時知道為什麼需要通知。被拒絕後，只能引導使用者去系統設定開啟。
- **iOS 15 起有通知的 interruption level（打斷程度）**：`passive`（不響不亮）、`active`（預設）、`time-sensitive`（可以穿透專注模式，需要 App 啟用對應的 capability）、`critical`（連靜音都響，需要 Apple 核准的特別 entitlement）。
- **Android 13（API 33）起**，顯示通知需要執行期權限 `POST_NOTIFICATIONS`，沒有請求就看不到通知。
- **Android 8（API 26）起**，每個通知要屬於一個 notification channel。使用者可以針對每個 channel 單獨調整聲音與重要性，所以要依用途切分：價格提醒、成交通知、行銷訊息各自一個 channel，讓使用者只關掉行銷，而不是整個關閉通知。

```kotlin
// Android：建立價格提醒專用的 channel（Android 8 以上）
val channel = NotificationChannel(
    // channel 的 ID
    "price_alert",
    // 使用者在系統設定看到的名稱
    "價格提醒",
    // 高重要性：會有聲音與浮動通知
    NotificationManager.IMPORTANCE_HIGH
)
getSystemService(NotificationManager::class.java)
    .createNotificationChannel(channel)

// Android 13 以上：請求通知權限（在 Activity 裡呼叫）
requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
```

> [!TIP]
> 面試時可以這樣說：「iOS 我會在使用者建立第一個價格提醒時才詢問通知權限；Android 13 以上要請求 POST_NOTIFICATIONS，而且要建立 notification channel，價格提醒、成交通知、行銷各自一個，讓使用者可以分開調整。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R；Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶。App 首頁進入 **Platform03BackgroundPush**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform03-BackgroundPush/`，解答在 `labs/ios/Solution/Platform/Platform03-BackgroundPush/`；Android 你寫在 `labs/android/practice/platform/platform03-background-push/`，解答在 `labs/android/solution/platform/platform03-background-push/`（Practice／Solution 同名，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **這個單元的演練怎麼做出來的**：背景限制（暫停、Doze）和推播（APNs、FCM）都需要系統或 Apple、Google 的伺服器配合，沒辦法在自動檢查裡重現。所以演練練的是「你寫的程式」：用假的系統（`FakeScheduler`）、假的 APNs／FCM 依文件上的規則檢查你組出來的請求，再檢查你寫的邏輯結果。第 2 題只有 iOS、第 3 題只有 Android，其他題兩個平台都有。

### 演練 2：背景更新只執行了一次，之後再也沒有（第 2 題，只有 iOS）

**檔案**：iOS `L02_BGTaskRefresh.swift`

**情境**：App 用 `BGAppRefreshTask` 在背景同步資產。真的 `BGTaskScheduler` 在模擬器上不能用，`BGAppRefreshTask` 也不能自己建立，所以演練用 `FakeScheduler` 代替系統：記下 App 排的下一次，之後「系統覺得適合的時候」啟動 App、呼叫你的 handler；時間到了（真的約 30 秒，演練用 150 ms）還沒呼叫 `setTaskCompleted`，系統先呼叫 `expirationHandler`。演練連續三次，第三次同步特別慢。

**怎麼寫**

1. **Bug 版**：handler 只開一個 `Task { await syncPortfolio() }`，沒有先排下一次，也沒有呼叫 `setTaskCompleted`。
2. **修正版**：handler 一開始先 `scheduler.submit()` 排下一次；同步放在 `Task` 裡，做完而且沒被取消就呼叫 `setTaskCompleted(success: true)`；另外設定 `task.expirationHandler`：取消同步並呼叫 `setTaskCompleted(success: false)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 系統只啟動了 1 次（沒有排下一次）；時間到了還沒呼叫 `setTaskCompleted` 的有 1 次 |
| 修正版 | 系統啟動了 3 次；沒有「忘了回報」；第三次同步太慢，時間到時主動回報失敗 1 次 |

**怎麼在真機上看**：真正的 `BGTaskScheduler` 只能在真機測。在 Xcode 執行 App，暫停在 debugger，在 console 輸入 `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"tw.harry.MobileLabs.refresh"]`（識別碼要和 Info.plist 的 `BGTaskSchedulerPermittedIdentifiers` 一致），再讓 App 繼續執行，就會呼叫你登記的 handler。

**為什麼**：`earliestBeginDate` 只是「最早」，實際何時執行由系統依使用習慣和電量決定，所以每次執行時要先排下一次，不然只會執行這一次。系統給的時間約 30 秒，做完要主動呼叫 `setTaskCompleted`，時間到了要在 `expirationHandler` 收尾；沒有回報的 App，系統之後會減少給它的背景時間。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L02_BGTaskRefresh.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L02_BGTaskRefresh.swift#fixed
```

### 演練 3：要求每 1 分鐘檢查價格，實際變成 15 分鐘（第 3 題，只有 Android）

**檔案**：Android `L03_WorkManagerLimits.kt`

**情境**：價格提醒想用 WorkManager 的週期性工作「每 1 分鐘檢查一次價格」。WorkManager 不會報錯，但週期性工作的最短間隔是 15 分鐘，設得比較短會被默默提高成 15 分鐘。演練不真的執行工作，只檢查你組出來的 `WorkRequest`：用 `request.workSpec` 看實際排的間隔、是不是加急（expedited）。

**怎麼寫**

1. **Bug 版**：用 `PeriodicWorkRequestBuilder<L03PriceCheckWorker>(minutes, TimeUnit.MINUTES).build()` 要求每 1 分鐘執行；收到推播時用 `OneTimeWorkRequestBuilder<L03AlertWorker>().build()` 排一般的一次性工作。
2. **修正版**：週期性工作只做可以延遲的同步，間隔給 15 分鐘，並要求網路連線（`NetworkType.CONNECTED`）；收到推播時用 `setInputData` 帶 `alertId`、`setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` 排加急的一次性工作。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 要求每 1 分鐘執行一次，WorkManager 實際排的間隔是 15 分鐘；收到推播時排的工作不是加急、沒有 alertId |
| 修正版 | 要求 15 分鐘、實際也是 15 分鐘；收到推播時排的工作是加急，而且帶著 alertId |

**怎麼用 adb 觀察 Doze 與 App Standby**：Doze 要手機靜止、沒充電、螢幕關閉一段時間，不好等，可以用指令強制進入：`adb shell dumpsys battery unplug`（假裝沒充電），`adb shell dumpsys deviceidle force-idle`（進入 Doze），離開用 `adb shell dumpsys deviceidle unforce` 與 `adb shell dumpsys battery reset`。在 Doze 裡，WorkManager 的工作和網路都會被延後到 maintenance window。App Standby Bucket 可以用 `adb shell am set-standby-bucket tw.harry.labs rare` 設定、`adb shell am get-standby-bucket tw.harry.labs` 查詢，越少用的分級，工作和鬧鐘越被限制。

**為什麼**：WorkManager 保證「最終會執行」，不保證準時：週期性工作最短 15 分鐘，Doze 與 App Standby 還會再延後。所以價格提醒不能靠它輪詢，要由伺服器判斷、推播通知；WorkManager 只做可延遲的同步，收到推播之後需要確實做完的事，用 expedited work。

**Android 解答**

```lab-solution
android/solution/platform/platform03-background-push/L03_WorkManagerLimits.kt#bug
```

```lab-solution
android/solution/platform/platform03-background-push/L03_WorkManagerLimits.kt#fixed
```

### 演練 4：登出後還收到別人的提醒，換了 token 後卻收不到（第 4 題）

**檔案**：iOS `L04_TokenLifecycle.swift`、Android `L04_TokenLifecycle.kt`

**情境**：伺服器的對照表是「帳號 → 這個帳號登入過的裝置 token」。演練跑兩段：① App 啟動時系統發了 token T1，帳號 A 登入、登出，帳號 B 登入，看 A 的價格提醒還會不會送到這支手機；② 帳號 B 登入之後，系統換了新的 token T2，看 B 的提醒會送到哪個 token。系統通知 App 的時機：iOS 是 `didRegisterForRemoteNotificationsWithDeviceToken`，Android 是 `onNewToken`。

**怎麼寫**

1. **Bug 版**：登入時把手上的 token 上傳一次（`server.register`）；token 換新不處理（只記下來）；登出什麼都不做。
2. **修正版**：拿到 token 就存起來，已經登入的話，註銷舊的（`server.unregister`）、上傳新的；登入時綁定帳號；登出時註銷這支手機的 token。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | A 登出、B 登入之後，A 的提醒還會送到 1 台裝置；token 換新之後，B 的提醒送到 T1（舊的，這支手機已經收不到） |
| 修正版 | A 登出之後 A 的提醒送到 0 台；token 換新之後，B 的提醒送到 T2 |

**為什麼**：token 會變（重新安裝、還原備份、清除資料），所以每次啟動或 `onNewToken` 都要重新上傳。登入後才綁定帳號，登出一定要註銷，不然已登出的裝置還是會收到前一個帳號的通知，有洩露個資的風險。伺服器那邊還要處理失效的 token：APNs 回 410、FCM 回 `UNREGISTERED`，代表 token 已經失效，要從資料庫刪掉。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L04_TokenLifecycle.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L04_TokenLifecycle.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform03-background-push/L04_TokenLifecycle.kt#bug
```

```lab-solution
android/solution/platform/platform03-background-push/L04_TokenLifecycle.kt#fixed
```

### 演練 5：伺服器發的推播沒有達到想要的效果（第 5 題）

**檔案**：iOS `L05_SilentPush.swift`、Android `L05_FcmMessage.kt`

**情境**：iOS：伺服器想發靜默推播（不顯示通知，只喚醒 App 約 30 秒）叫 App 在背景重新整理資產；你寫 `buildSilentPush()` 組出標頭與內容，`FakeAPNs` 依 Apple 文件上的規則檢查：push-type 是 `background` 時 `apns-priority` 一定要是 5、`aps` 要有 `content-available: 1` 而且不能有 `alert`、`sound`、`badge`。Android：伺服器要把價格提醒推到手機，App 在背景、手機在 Doze；你寫 `buildPriceAlert()` 組出 FCM 訊息，`FakeFcm` 依文件上的規則決定：notification 訊息在背景由系統顯示、不呼叫 `onMessageReceived`；data 訊息一定呼叫；優先權 NORMAL 在 Doze 會被延後，HIGH 可以喚醒。

**怎麼寫**

1. **Bug 版**：iOS 複製價格提醒（alert 推播）的寫法：`apns-push-type` 是 `background`、`apns-priority` 是 10，`aps` 帶 `alert`、`sound` 再加 `content-available`。Android 訊息帶 `notification`（title、body），優先權 NORMAL，沒有 `collapse_key` 與 `ttl`。
2. **修正版**：iOS 的 priority 改 5，`aps` 只有 `content-available: 1`，自己的資料放在 `aps` 外面。Android 只帶 `data`，優先權 HIGH，`collapse_key` 設 `price_alert_btcjpy`，`ttl` 設 600 秒。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：APNs 回應拒絕，`BadPriority：background 的 apns-priority 必須是 5`。Android：訊息被 Doze 延後；`onMessageReceived` 被呼叫：否 |
| 修正版 | iOS：APNs 回應接受。Android：訊息立刻到達；`onMessageReceived` 被呼叫：是；collapse_key、ttl 都有 |

**為什麼**：兩個平台的推播都分成「系統直接顯示」和「只帶資料、由 App 自己處理」兩種。要讓 App 在背景處理（存進資料庫、自己組通知、更新資料），iOS 要用靜默推播，而且標頭與內容要符合規則；Android 要用 data 訊息，notification 訊息在背景時 `onMessageReceived` 不會被呼叫。優先權決定 Doze 時會不會被延後；collapse 讓過時的提醒被新的取代；TTL 讓過了幾分鐘的提醒直接丟掉。靜默推播也不是準時的機制：系統會依電量節流，強制關閉 App 之後完全喚醒不了。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L05_SilentPush.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L05_SilentPush.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform03-background-push/L05_FcmMessage.kt#bug
```

```lab-solution
android/solution/platform/platform03-background-push/L05_FcmMessage.kt#fixed
```

### 演練 6：價格在門檻附近跳，同一個提醒響了好幾次（第 6 題）

**檔案**：iOS `L06_AlertRepeat.swift`、Android `L06_AlertRepeat.kt`

**情境**：使用者設定「BTC/JPY 漲到 1,000 萬日圓就通知我」，伺服器逐筆比對行情，觸發時發推播。演練送進 11 筆價格：在 1,000 萬附近上下跳了一陣，後來跌到 980 萬，再漲到 1,010 萬。看一共發了幾次推播。價格提醒一定要由伺服器比對（client 在背景無法準時，silent push 會被節流，強制關閉後也收不到），這個演練練的是比對邏輯本身。

**怎麼寫**

1. **Bug 版**：每一筆價格只要 `>=` 門檻就回傳 `true`（發推播）。
2. **修正版**：用 `triggered` 記住「已觸發」。已觸發時，價格跌到門檻的 99% 以下才重新武裝（`triggered = false`），這一筆不發；還沒觸發時，價格 `>=` 門檻就設成已觸發並回傳 `true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 11 筆價格一共發了 6 次推播（價格在 1,000 萬以上的每一筆都發） |
| 修正版 | 一共發了 2 次：10000000 與 10100000 |

**為什麼**：價格在門檻附近上下跳是常態，「>= 門檻就發」會讓使用者的手機一直響，最後把通知整個關掉。要在「穿過門檻」的那一刻發一次，再加上重新武裝的條件（這裡是跌到門檻的 99% 以下，也可以用冷卻時間）。真的系統還要用 `apns-collapse-id`／`collapse_key` 讓過時的提醒被新的取代，TTL 設短。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L06_AlertRepeat.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L06_AlertRepeat.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform03-background-push/L06_AlertRepeat.kt#bug
```

```lab-solution
android/solution/platform/platform03-background-push/L06_AlertRepeat.kt#fixed
```

### 演練 7：App 開著，同一個提醒跳了兩次（第 6 題）

**檔案**：iOS `L07_ForegroundDedupe.swift`、Android `L07_ForegroundDedupe.kt`

**情境**：使用者正在看行情頁，App 在前景，已經用 WebSocket 串流收行情與提醒。同一個價格提醒，伺服器同時用兩條路送：WebSocket（App 內橫幅）和推播（iOS 在 `willPresent` 回呼、Android 在 `onMessageReceived`，由 App 決定要不要顯示）。演練送兩個提醒：`a1` 兩條路都到、`a2` 只有推播到。

**怎麼寫**

1. **Bug 版**：兩條路各自收到就各自顯示橫幅（把 `alert.id` 加進 `banners`）。
2. **修正版**：用一個集合（`shown`）記下已經顯示過的 `alertId`，已經顯示過就跳過。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面上一共顯示了 3 次橫幅：a1、a1、a2 |
| 修正版 | 畫面上一共顯示了 2 次橫幅：a1、a2 |

**為什麼**：串流和推播是兩條獨立的路，client 不知道另一條路有沒有到過。所以兩條路要帶同一個由伺服器產生的 `alertId`，client 用它去重。只從一條路到的提醒（例如 App 剛好沒連上串流）還是要顯示。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L07_ForegroundDedupe.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform03-BackgroundPush/L07_ForegroundDedupe.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform03-background-push/L07_ForegroundDedupe.kt#bug
```

```lab-solution
android/solution/platform/platform03-background-push/L07_ForegroundDedupe.kt#fixed
```

第 1 題和第 7 題沒有演練。第 1 題（為什麼 App 在背景不能一直跑）要靠系統真的把 App 暫停或進入 Doze 才看得到：iOS 用真機，在 `Timer` 的 callback 裡每秒印一行 log，按 Home 之後 log 很快就停了（模擬器不一定照真機的規則暫停 App，不能拿來驗證）；Android 用上面演練 3 寫的 adb 指令強制進入 Doze。第 7 題（通知權限與 notification channel）是系統的設定與使用者操作：iOS 的授權對話框只會跳一次，要再測要刪除 App 重新安裝；Android 13 以上可以用 `adb shell pm revoke tw.harry.labs android.permission.POST_NOTIFICATIONS` 收回權限再測。

## 常見陷阱

- **用 silent push 或 BGTask 當作「準時」的機制。** 兩者都是盡力而為，會被節流或延後，也不會在強制關閉後喚醒 App。準時的訊息要發 alert 推播。
- **在 client 輪詢價格來比對提醒。** 延遲數分鐘到數小時，還耗電；App 被暫停後根本不會執行。
- **在 FCM 的 `onMessageReceived` 裡開 fire-and-forget 的協程。** 方法回傳後行程可能被回收，工作做到一半消失。要確實完成的工作交給 WorkManager。
- **Android 13 以上沒有請求 `POST_NOTIFICATIONS`。** 通知完全不會顯示。
- **忘了建立 notification channel（Android 8 以上）。** 通知不顯示，或重要性不對。
- **通知內容含敏感資訊。** 持有數量、餘額會出現在鎖定畫面。
- **登出後沒有註銷 token。** 已登出的裝置還是會收到前一個帳號的通知。
- **用 10 當 silent push 的 `apns-priority`。** background 種類的推播優先權必須是 5。
- **只測 App 在前景或剛進背景的情況。** 要測被強制關閉、Doze、低電量模式下的行為。

## 面試官想聽到的

1. 說得出各機制的限制：BGTask 不準時、silent push 被節流、WorkManager 最短 15 分鐘、Doze 延後背景工作。
2. 價格提醒由伺服器驅動，並處理去重、collapse、TTL、在地化與 deep link。
3. 理解 iOS 強制關閉和 Android 省電機制，對背景工作與推播的影響。
4. 知道 APNs 與 FCM 的優先權、collapse 與 payload 欄位（`content-available`、`apns-push-type`、data message 與 notification message 的差別）。
5. 對權限、notification channel、token 管理有實務概念。

> [!WARNING]
> 面試官常追問：「那為什麼不用 silent push 加本地通知，讓 App 自己比對價格？」不要只回答「不準時」。要說明 silent push 沒有送達保證、會被系統依電量節流，強制關閉後還完全喚醒不了 App；而伺服器有完整的行情，可以在價格到達的當下直接發 alert 推播，即使 App 沒有在執行也能送達。

> [!FIELD]
> 在一個社交 App 裡處理過推播與背景：iOS 使用 Firebase Messaging（把 APNs 的 token 交給 Firebase），並自己做了 App 內橫幅的佇列：App 在前景收到 socket 訊息時顯示，在聊天列表、個人檔案、目前所在的聊天室則不顯示，避免與推播重複。Android 的 FCM service 在 `onMessageReceived` 把通知存進資料庫，依訊息種類（新訊息、更新聊天室、系統通知）分別處理，`onNewToken` 在登入狀態下上傳 token；因為 service 不方便直接用欄位注入，用 Hilt 的 `@EntryPoint` 取得依賴。
> 那個 App 的聊天 socket 進背景就斷線，靠推播通知使用者，回前景時再用 HTTP 同步補上漏掉的訊息，沒有用前景服務長期連線，這個做法符合兩個平台對背景執行的限制。另一個即時行情 App 的 iOS 則把 FCM token 存在 Keychain。
> 換成交易所 App，需要在 `onMessageReceived` 裡確實完成的工作，我會交給 expedited WorkManager。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 背景定期工作 | `BGAppRefreshTask`（約 30 秒）、`BGProcessingTask`，由系統決定何時執行 | WorkManager，週期性工作最短 15 分鐘 |
| 進背景的收尾時間 | `beginBackgroundTask`，約 30 秒 | 重要工作交給 WorkManager（expedited） |
| 系統的省電限制 | 進背景很快暫停；低電量模式；強制關閉後不再喚醒 | Doze、App Standby Bucket、背景 service 限制、手機品牌的省電功能 |
| 推播服務 | APNs（透過 Firebase 時是 FCM 再轉 APNs） | FCM |
| 只帶資料的推播 | silent push：`content-available: 1`、`apns-push-type: background`、`apns-priority: 5` | data message：一律進 `onMessageReceived`，約 20 秒 |
| 優先權 | `apns-priority`：10 立即、5 省電 | `HIGH`（可喚醒 Doze）、`NORMAL` |
| 取代舊通知 | `apns-collapse-id` | `collapse_key` |
| 存活時間 | `apns-expiration` | `ttl` |
| 通知權限 | `requestAuthorization`；iOS 15 起有 interruption level | Android 13 起需 `POST_NOTIFICATIONS`；Android 8 起需 notification channel |
| 顯示前修改通知 | Notification Service Extension（`mutable-content: 1`） | 在 `onMessageReceived` 自己組通知（data message） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 推播通知 | push notification | プッシュ通知 |
| 靜默推播 | silent push／background push | サイレントプッシュ |
| 裝置 token | device token／registration token | デバイストークン |
| 背景工作 | background task | バックグラウンドタスク |
| Doze（Android 的省電休眠機制） | Doze | Doze（ドーズ） |
| 通知管道 | notification channel | 通知チャンネル |
| 前景服務 | foreground service | フォアグラウンドサービス |
| 價格提醒 | price alert | 価格アラート |
