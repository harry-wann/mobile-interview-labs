---
id: advanced-stability-crash
title: App 為什麼會當掉：crash 的種類、怎麼看懂 crash 報告，以及 crash-free users 怎麼明顯提升並長期維持
summary: 資深面試很愛問「你怎麼讓 App 更穩定」。這個單元說明 crash-free users 是什麼、iOS 與 Android 常見的 crash 與「被系統終止」有哪些、dSYM 與 mapping 檔為什麼是看懂報告的前提，以及一套可以講出口的方法：依影響人數排序、從架構根治、發布後先看指標再擴大。
priority: P1
day: 3
tags: [crash, crash-free, symbolication, dsym, mapping, anr, watchdog, oom, crashlytics, r8]
---

## 重點講解

這個單元回答六個面試常見問題：

1. crash-free users 是什麼？和 crash-free sessions 差在哪？
2. iOS 和 Android 常見的 crash 有哪幾種？
3. crash 報告為什麼一開始看不懂？dSYM 和 mapping 檔是什麼？
4. ANR（Application Not Responding，App 沒有回應）、watchdog、OOM（out of memory，記憶體不足）是什麼？為什麼它們常常沒有一般的 crash 報告？
5. 怎麼把 crash-free users 明顯提升，而且長期維持？
6. 為什麼有些 crash 只在正式版（release build）才出現？

### 1. crash-free users 是什麼？和 crash-free sessions 差在哪？

**crash-free users 是「在一段期間內，沒有遇過 crash 的使用者，占全部使用者的比例」。** crash-free sessions 的算法一樣，只是把單位從「使用者」換成「session」（一次使用，從打開 App 到離開）。同樣一批資料，crash-free users 通常比 crash-free sessions 低，也就是比較嚴格。

用交易所 App 一天的資料來算比較容易懂。假設這天有 100 位使用者，總共開了 1,000 次 session，其中 5 位使用者各自 crash 了一次：

| 指標 | 算法 | 結果 |
|---|---|---|
| crash-free users | 沒遇過 crash 的使用者 95 位 ÷ 100 位 | 95% |
| crash-free sessions | 沒有 crash 的 session 995 次 ÷ 1,000 次 | 99.5% |

兩個數字差這麼多，是因為一位重度使用者可能一天開 30 次 App，session 的分母很大，但只要 crash 過一次，他在 users 的算法裡就整個算「受影響」。Firebase Crashlytics（Google 的 Firebase 提供的 crash 回報服務）兩個數字都會給。我會優先看 users，因為使用者的感受是「這個 App 會閃退」，不是「閃退占所有 session 的千分之幾」。對交易所尤其如此：行情劇烈波動時，使用者一打開 App 就閃退，他不會在乎其他 99% 的 session 都正常。

**比較前後數字時，一定要固定這幾件事：**

- 用同一個工具。Crashlytics、Google Play Console 的 Android vitals（Google 在後台提供的 App 品質指標）、Xcode Organizer（Xcode 裡可以查看已上架 App 線上資料的視窗）的定義和取樣方式不同，數字不能直接比。
- 同一個版本範圍、同一個平台、同一段期間。
- 同一個分母（users 或 sessions）。
- 是否把 ANR（App 沒有回應）、OOM（記憶體不足被終止）、watchdog（iOS 系統因為 App 卡太久而終止它）這幾種算進去，第 4 題會詳細說明。有些工具預設不算。

說「crash-free 提升了多少」時，面試官很可能追問「是 users 還是 sessions？」，所以這個區別要能講清楚。Google Play 也有一組門檻：Android vitals 的 crash 與 ANR 比例超過門檻，App 在 Play 商店的曝光會受影響，所以穩定性不只是品質問題。

> [!TIP]
> 面試時可以這樣說：「crash-free users 是這段期間沒有遇過 crash 的使用者比例，會比 crash-free sessions 嚴格，因為一位重度使用者開很多次 App，只要 crash 一次就算整個人受影響。我主要看 users，因為它比較接近使用者的真實感受。」

### 2. iOS 和 Android 常見的 crash 有哪幾種？

**crash 可以分成兩大類：程式自己出錯而被中止（通常有 stack trace，也就是出錯當下一層一層的函式呼叫紀錄），以及被系統終止（App 卡住太久、或用太多記憶體，系統直接把它關掉，常常沒有一般的 crash 報告）。**

| 平台 | 種類 | 典型原因 | 怎麼辨認 |
|---|---|---|---|
| iOS | Swift 執行時錯誤 | 對 `nil` 強制解包、陣列越界、整數溢位、`try!`、`fatalError`、`unowned` 參考在物件釋放後被讀取（Swift 會主動中止，訊息是 `Attempted to read an unowned reference but object ... was already deallocated`） | 例外類型 `EXC_BREAKPOINT`（訊號 `SIGTRAP`：作業系統通知行程「發生不該發生的事」） |
| iOS | Objective-C 例外 | `unrecognized selector`（呼叫了物件沒有的方法）、KVO（Key-Value Observing，監聽屬性變化的機制）用錯、Realm（本機資料庫）或 Core Data（Apple 的資料儲存框架）的物件跨執行緒使用 | 訊號 `SIGABRT`，附帶例外名稱和訊息 |
| iOS | 存取無效記憶體 | 懸空指標（指向已釋放記憶體的指標，例如 `unowned(unsafe)` 或 `Unmanaged` 用錯）、無限遞迴造成 stack overflow（呼叫層數多到用完 stack 空間） | `EXC_BAD_ACCESS` |
| iOS | watchdog 終止 | 啟動、回到前景、背景任務時，主執行緒卡太久被系統終止 | 例外碼 `0x8badf00d` |
| iOS | jetsam／OOM | 記憶體不夠，系統直接終止 App | **沒有 crash 報告** |
| Android | 沒有被接住的例外 | `NullPointerException`、`IllegalStateException`、`ClassCastException`、`IndexOutOfBoundsException`、`OutOfMemoryError`、`TransactionTooLargeException`（透過 Intent 或 Bundle 傳了太多資料） | stack trace |
| Android | native crash | C／C++ 寫的程式碼出錯（NDK，也就是 Native Development Kit，用 C／C++ 寫 Android 程式的工具；或第三方 SDK，也就是 software development kit，第三方提供的開發套件） | 訊號 `SIGSEGV`，系統會留下 tombstone 檔（原生程式碼的 crash 紀錄），需要 native 符號才看得懂 |
| Android | ANR | 主執行緒卡住，系統判定 App 沒有回應 | ANR trace、Play Console |
| Android | 行程被系統終止 | 記憶體不足（由 lmkd，也就是 low memory killer daemon 負責）、背景限制 | `ApplicationExitInfo`（Android 11 起提供的 API） |

先看最常見的兩種，程式碼很短，面試時要認得出來：

```swift
let prices: [Decimal] = []
// 陣列越界：執行時直接中止，例外類型 EXC_BREAKPOINT
let first = prices[0]
// 對 nil 強制解包：同樣直接中止
let amount = Int("abc")!
```

```kotlin
val order: Order? = null
// !! 的意思是「我保證不是 null」，保證落空就丟 NullPointerException
val id = order!!.id
```

iOS 與 Android 的這類錯誤有共通的預防方向：**少用強制解包（`!`、`!!`），改用 `guard let`、`?.`、預設值，或在資料解碼的那一層就處理好「欄位可能不存在」的情況**。第 5 題會把這個想法接到方法論。

另外兩個容易搞混的名詞：

- **jetsam** 是 iOS 在記憶體不夠時，挑 App 終止的機制。被它終止的 App 沒有機會留下 crash 報告，所以 Crashlytics 的 crash 數字看不到它。
- **ANR** 是 Application Not Responding 的縮寫，意思是「應用程式沒有回應」。使用者看到的是系統跳出「App 沒有回應」的對話框。

> [!TIP]
> 面試時可以這樣說：「我會把 crash 分成兩類：程式自己出錯的，像 iOS 的強制解包和陣列越界、Android 的 NullPointerException，有 stack trace 可以直接看；另一類是被系統終止的，像 iOS 的 watchdog 和 jetsam、Android 的 ANR 和記憶體不足，沒有一般的 crash 報告，要靠系統提供的指標去看。」

### 3. crash 報告為什麼一開始看不懂？dSYM 和 mapping 檔是什麼？

**正式版 App 的 crash 報告裡，原本的函式名稱和行號都被拿掉了，只剩下位址或亂碼。要還原成人看得懂的樣子，需要「這一次 build 對應的符號檔」：iOS 是 dSYM，Android 是 R8（Android 的程式碼縮減與混淆工具）產生的 `mapping.txt`。**

這個動作叫 symbolication（符號還原）。沒還原的 iOS 報告長這樣：

```text
0   MyApp   0x0000000104a3c1f4   0x104a30000 + 49652
```

還原之後：

```text
0   MyApp   OrderViewModel.submit() (OrderViewModel.swift:88)
```

**iOS：dSYM。** dSYM 是 debug symbols 的縮寫，是每次編譯時一起產生的檔案，記錄「哪個位址對應哪個函式、哪一行」。每一次編譯的二進位檔都有一個唯一的 UUID（隨機產生的識別碼），dSYM 也有，兩邊的 UUID 要一樣才能對上。重點有三個：

1. **每個要發布的版本，dSYM 都要保存下來，並上傳到 Crashlytics。** 做法是在 Fastlane（自動化打包與發布的工具）用 `upload_symbols_to_crashlytics`，或在 Xcode 的 build phase 加上傳腳本。
2. 以前（Xcode 14 之前）App 可以開 Bitcode，由 Apple 在伺服器重新編譯，所以 dSYM 要去 App Store Connect 另外下載。Bitcode 已經在 Xcode 14 移除，現在自己 archive 出來的 dSYM 就是對的那一份，但也代表**要自己保存**。
3. 懷疑對不上時，可以用 `dwarfdump --uuid` 指令檢查 dSYM 和 App 的 UUID：

```bash
# 印出 dSYM 的 UUID，再和 crash 報告裡的 Binary UUID 比對
dwarfdump --uuid MyApp.app.dSYM
```

**Android：`mapping.txt`。** R8 是 Android 的程式碼縮減與混淆工具，release build 會把類別和方法改成 `a`、`b`、`c` 這種短名字（混淆），所以 stack trace 會變成 `a.b.c.d`。R8 在混淆時會寫出一份 `mapping.txt`，記錄「短名字對應原本的名字」。要點：

1. **每個 release build 的 mapping 都不同，也都要保存並上傳。** Crashlytics 的 Gradle plugin 會在 release build 後自動上傳；上架 Google Play 用的 AAB（Android App Bundle，Google Play 規定的上架格式）內也含 mapping，Play Console 的 crash 與 ANR 報告就能還原。
2. 想在還原後看到行號，要在 R8 規則裡保留行號資訊：

```text
# 保留原始檔名與行號資訊，stack trace 才有 File.kt:123
-keepattributes SourceFile,LineNumberTable
# 把檔名統一改成 SourceFile，避免洩漏原始檔名；之後用 mapping 仍然可以還原
-renamesourcefileattribute SourceFile
```

3. 在本機要手動還原，可以用 R8 附的 `retrace` 工具，輸入 mapping 和 stack trace。
4. C／C++ 的 native crash 另外需要上傳未去除符號的 `.so` 檔（原生函式庫）。

**最常見的錯：** 拿 1.2.0 版的 mapping 或 dSYM 去還原 1.3.0 的 crash，不會報錯，只會還原出一堆錯誤的名字，反而誤導判斷。所以符號檔要「跟著版本」保存，最好在 CI（continuous integration，每次提交程式碼就自動建置與測試的流程）打包完成的那一步就自動上傳。

> [!TIP]
> 面試時可以這樣說：「正式版的 crash 報告需要符號檔才看得懂。iOS 是 dSYM，用 UUID 對應到那一次編譯；Android 是 R8 的 mapping.txt。每個發布的版本我都會在打包流程裡自動上傳，不同版本的檔案不能混用。」

### 4. ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？

這三種都是「App 沒有自己出錯，是被系統判定有問題而終止或警告」，所以不會有一般的例外和 stack trace，要用系統提供的指標去看。

**ANR（Android）與 watchdog（iOS）：主執行緒卡太久。** 主執行緒負責處理觸控和畫面更新，如果它被一件很慢的事情佔住，使用者的操作都沒有回應。Android 的 ANR 判定時限：輸入事件（觸控、按鍵）5 秒內沒有處理完；BroadcastReceiver（接收系統或 App 廣播的元件）前景 10 秒、背景 60 秒；Service 另有時限。iOS 的 watchdog 是系統內建的監看機制，App 在啟動、回到前景或執行背景任務時，如果主執行緒太久沒有完成，系統會直接終止 App，crash 報告上的例外碼是 `0x8badf00d`（讀作「ate bad food」）。

兩個平台的典型情境很像：

```swift
// iOS：啟動時在主執行緒同步等網路，網路慢的話，watchdog 會把 App 終止
func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options:
        [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    let data = try? Data(
        contentsOf: URL(string: "https://example.com/config.json")!
    )
    // 這一行會一直等到網路回應，期間主執行緒完全被佔住
    return true
}
```

```kotlin
// Android：按鈕的點擊事件在主執行緒執行，
// runBlocking 會讓主執行緒一直等到結果回來
button.setOnClickListener {
    // 網路慢時，5 秒後就可能被判定為 ANR
    val prices = runBlocking { api.fetchPrices() }
    render(prices)
}
```

常見的成因是：主執行緒做檔案或資料庫的讀寫、同步等網路、搶鎖等待、Android 上同步呼叫跨行程的 Binder（Android 的行程間通訊）、BroadcastReceiver 做太多事。解法的方向一致：**把慢的事移到背景（Swift Concurrency、Kotlin coroutines），主執行緒只負責畫面。**

怎麼偵測：

- Android：Play Console 的 Android vitals 會把 ANR 分群並附上 trace；Crashlytics 在 Android 11 以上也支援回報 ANR；本機開發時可以用 StrictMode（Android 內建的偵測工具，在主執行緒做磁碟或網路操作時發出警告）：

```kotlin
// 只在 debug build 開啟：主執行緒做了磁碟或網路操作，就在 Logcat 印出警告
if (BuildConfig.DEBUG) {
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()
    )
}
```

- iOS：crash 報告裡的 `0x8badf00d`；MetricKit（Apple 提供、收集真實使用者裝置上效能資料的框架）在 iOS 14 以上提供 `MXHangDiagnostic`，會附上主執行緒卡住時的呼叫紀錄；Xcode Organizer 也有 hang 的統計。

**OOM：記憶體不夠。** OOM 是 out of memory 的縮寫。情境：使用者在交易所 App 的 K 線圖不斷往左拖，一次載入好幾年的資料，又同時開著幾張全尺寸的圖片，記憶體越用越多。iOS 會由 jetsam 直接終止 App，沒有 crash 報告，使用者看到的是 App 突然消失；Android 則可能丟出 `OutOfMemoryError`（Java 堆積滿了，這種有 stack trace），或在 App 被放到背景時被 lmkd 終止（沒有 stack trace）。

怎麼看到「沒有報告的終止」：

```swift
import MetricKit

// iOS 14 以上：MetricKit 會定期回傳過去一天各種退出原因的次數
@available(iOS 14.0, *)
final class MetricsSubscriber: NSObject, MXMetricManagerSubscriber {
    func didReceive(_ payloads: [MXMetricPayload]) {
        for payload in payloads {
            guard let exits = payload.applicationExitMetrics else {
                continue
            }
            let foreground = exits.foregroundExitData
            print(
                "前景被記憶體上限終止：",
                foreground.cumulativeMemoryResourceLimitExitCount
            )
            print(
                "前景被 watchdog 終止：",
                foreground.cumulativeAppWatchdogExitCount
            )
        }
    }
}
```

```kotlin
// Android 11 以上：查詢最近幾次行程為什麼結束
val am = context.getSystemService(ActivityManager::class.java)
am.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { info ->
    when (info.reason) {
        ApplicationExitInfo.REASON_ANR ->
            Log.w("Exit", "ANR：${info.description}")
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            Log.w("Exit", "記憶體不足被系統終止")
        ApplicationExitInfo.REASON_CRASH ->
            Log.w("Exit", "一般 crash")
    }
}
```

iOS 也可以自己判斷：App 每次進入背景或正常結束時，記下一個「正常結束」的標記；下次啟動時如果發現上一次沒有這個標記，又不是 crash、不是版本更新、也不是裝置重新開機，就很可能是記憶體不足或 watchdog。這是推測，不是百分之百準確，但足夠看趨勢。

預防 OOM 的方向：圖片依顯示大小縮小後才載入、長列表分頁、收到記憶體警告時清掉快取（iOS 的 `didReceiveMemoryWarning`、Android 的 `onTrimMemory`）。

> [!TIP]
> 面試時可以這樣說：「ANR 和 watchdog 都是主執行緒被卡太久，不是程式拋出例外，所以要看 Play Console 的 ANR trace 或 iOS 的 hang 診斷。OOM 在 iOS 沒有 crash 報告，我會用 MetricKit 的退出統計或自己記錄『上次是否正常結束』來估計。處理方向都是把慢的事移出主執行緒、控制記憶體用量。」

### 5. 怎麼把 crash-free users 明顯提升，而且長期維持？

**答案是一套有順序的做法，而不是「一個一個把 crash 修掉」：先固定怎麼量，依影響人數排序，從架構一次根治同一類問題，發布時先看指標再擴大。** 下面是我認為資深工程師應該要能講出來的順序。

**步驟一：固定怎麼量。** 第 1 題說過：指標（users）、工具（Crashlytics）、版本範圍、期間，先量出「現在是多少」，之後的改善才有基準。

**步驟二：依「影響多少位使用者」排序，不是依 crash 發生的次數。** 一位使用者的 App 卡在無限重試的迴圈裡，可能製造幾千筆 crash 事件，但只影響一個人；另一個只在特定機型出現的 crash 才幾十筆，卻影響幾十位使用者。Crashlytics 的問題列表可以依受影響的使用者數排序。通常最前面的幾個問題就涵蓋了大部分受影響的使用者，先修這幾個，數字會明顯上升。

**步驟三：從架構根治同一類問題，不是只補單一條 stack。** 這是最能分出資深與一般工程師的地方。幾個例子：

- 一堆 crash 都是強制解包或 `!!`：不要一行一行補 `if let`，而是在網路回應解碼的那一層（Swift 的 `Codable`、Kotlin 的 kotlinx.serialization）把「欄位可能缺少」當成預設情況處理，上層的畫面拿到的就已經是安全的資料。
- crash 來自資料庫物件跨執行緒使用（例如 Realm 這個本機資料庫，它的物件只能在建立它的執行緒存取）：規定資料庫只在一個固定的地方存取，對外只傳複製出來的資料。
- crash 來自畫面關閉後，非同步的回呼才回來存取已經不存在的畫面：改成由 ViewModel 持有狀態、畫面只觀察狀態（Android 的 ViewModel + Flow，iOS 的 Combine 或 Swift Concurrency 搭配生命週期取消），讓回呼不需要直接碰畫面。

當同一類問題的根因是「程式碼的組織方式」時，重構（在不改變功能的情況下重新整理程式結構）就是修 crash 最有效的方式。

**步驟四：預防，讓同一類問題進不來。** iOS 用 SwiftLint（Swift 的程式碼風格檢查工具）禁止 `force_unwrapping`、`force_cast`；Android 用 detekt（Kotlin 的靜態分析工具）檢查 `!!`；Swift 6 的 strict concurrency（編譯器在編譯時檢查執行緒安全）可以提早擋下資料競爭（兩個執行緒同時讀寫同一份資料）。每修一個 crash，就補一個能重現它的測試。

**步驟五：發布時先看指標再擴大。** 新版本不要一次推給所有人：

- Google Play Console 可以做 staged rollout（分階段發布），例如先 1%、5%、20%，最後 100%。
- App Store Connect 有 phased release（分階段發行），針對自動更新的使用者，用 7 天時間逐步擴大，可以隨時暫停。
- 每擴大一階之前，先看新版本的 crash-free users 有沒有低於上一版。比較差就暫停、修好再繼續。這個「要過這道檢查才能擴大」的規則叫 release gate。

**步驟六：降低雜訊。** 不需要處理的事件不要上報，否則真正重要的問題會被淹沒。例子：Kotlin coroutine 被取消時會丟出 `CancellationException`，這是正常的流程控制，不是錯誤；如果被當成錯誤回報，Crashlytics 裡就會堆滿「Job was cancelled」。

**步驟七：留下線索，但不要留個資。** Crashlytics 可以記錄 breadcrumbs（麵包屑：出錯之前使用者做過的動作）和 custom keys（自訂欄位，例如目前的畫面名稱），方便重現。交易所尤其要避免寫入帳號、金額、token（登入憑證）。

**如果面試官問「這個數字你怎麼量的」。** 如實回答：這是我在後台持續追蹤的數字，當時沒有留下正式報告。接著把話題帶回做法：我看的是 Crashlytics 的 crash-free users，用上面的順序處理。

> [!TIP]
> 面試時可以這樣說：「我提升穩定性的順序是：先固定指標，依受影響的使用者數排序，從架構一次解決同一類問題，而不是一條一條補，再用 lint 和測試預防。發布時分階段放量，先看新版本的 crash-free users 沒有變差才擴大。」

### 6. 為什麼有些 crash 只在正式版（release build）才出現？

**因為正式版和開發版不是同一份程式：Android 的正式版會經過 R8 縮減和混淆，iOS 的正式版會開編譯器最佳化。開發時一切正常，不代表正式版沒事。** 所以要在發布前，用正式版的設定把關鍵流程跑一遍。

R8 在正式版做了三件事：

1. 縮減（shrink）：移除看起來沒用到的程式碼與資源。
2. 混淆（obfuscate）：把類別和方法改成短名字（第 3 題的 `a.b.c`）。
3. 最佳化（optimize）：把類別合併、把小函式的內容直接展開到呼叫的地方等。

問題在於：R8 是靜態分析，只看得到程式碼裡寫明的呼叫關係。如果某個類別是靠反射（reflection，執行時用名稱找類別）、序列化函式庫、或 Hilt（Android 的依賴注入函式庫，會在編譯時產生大量程式碼）這類工具呼叫的，R8 會誤以為它沒用到而移除或改名，結果就是正式版才丟出 `ClassNotFoundException`、`ClassCastException`、`NoSuchMethodError`。解法是寫 keep 規則，告訴 R8 這些東西不要動：

```text
# 保留這個 package 底下的資料類別，因為 JSON 解析靠名稱對應欄位
-keep class com.example.exchange.data.model.** { *; }
```

```kotlin
// app/build.gradle.kts：release 才開啟縮減、混淆與資源縮減
android {
    buildTypes {
        release {
            // 開啟 R8 的縮減與混淆
            isMinifyEnabled = true
            // 移除沒用到的資源
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // 自己寫的 keep 規則
                "proguard-rules.pro"
            )
        }
    }
}
```

R8 還有一個 full mode，比預設更積極地合併與移除程式碼，從 Android Gradle Plugin 8.0 起預設開啟。遇到 full mode 造成的問題有兩種處理方式：關掉 full mode（保守，但放棄一部分縮小的效果），或保留 full mode、補上精準的 keep 規則（縮得比較小，但需要完整的測試把關）。

**預防的方法是 smoke test。** smoke test（冒煙測試）是每次發布前快速跑一遍最關鍵的流程：開啟 App、登入、看行情、進入下單畫面，確認沒有明顯壞掉。重點是：**要拿 R8 已經開啟的 release build 去測，不是 debug build。** 可以放在 CI 裡自動執行，再搭配上一題的分階段發布，就算漏掉，影響的人也很少。

iOS 的 Release 設定同樣開著最佳化，有些問題只在 Release 才出現，所以送 TestFlight（Apple 的測試版發布管道）的版本、給測試人員驗收的版本，都應該是 Release 設定。

> [!TIP]
> 面試時可以這樣說：「Android 正式版會經過 R8 縮減和混淆，靠反射或程式碼產生的類別可能被誤刪或改名，所以只在 release 才 crash。我會在 CI 用 release build 跑 smoke test，再搭配分階段發布，並且確認每一版的 mapping 檔都有上傳。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced06Crash**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Advanced06Crash**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced06-Crash/`，解答在 `labs/ios/Solution/Advanced/Advanced06-Crash/`；Android 你寫在 `labs/android/practice/advanced/advanced06-crash/`，解答在 `labs/android/solution/advanced/advanced06-crash/`（檔名大多一樣，可以左右對照；演練 4、演練 6 兩個平台的檔名略有不同，下面各自標明）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **會當掉的演練**：演練 2 的 Bug 版寫對了會讓 App 當掉（所以自動檢查略過它）。iOS 從 Xcode 執行才看得到停在哪一行；Android 看 Logcat 的 `FATAL EXCEPTION`。

這個單元大多是「線上看報告」的事，沒辦法在專案裡真的讓使用者當掉，所以演練做的是報告背後那些**算錯、接錯、對錯**的邏輯：數字怎麼算、handler 有沒有串起來、符號檔有沒有對上、偵測程式放在哪一條執行緒。所有資料都是演練裡自己造的，不會連到外部服務。

### 演練 1：後台說 crash-free users 92%，其實是 95%（第 1 題）

**檔案**：`L01_CrashFreeUsers.swift`（iOS）、`L01_CrashFreeUsers.kt`（Android）

**情境**：一天的資料有 100 位使用者、1000 次 session、8 筆 crash 事件，分屬 3 個問題。其中 u07 卡在「失敗就重試」的迴圈，同一個問題連續 crash 了 4 次；另外 4 位使用者各 crash 1 次。演練算 crash-free users、crash-free sessions，並列出問題排行（第 5 題的步驟二）。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：受影響的使用者數直接回傳事件的筆數；每個問題的「影響人數」也是這個問題的事件筆數。
2. **修正版**：用 `userId` 去重（Swift 的 `Set`、Kotlin 的 `toSet()`）再數；每個問題也是數這個問題裡不同的 `userId`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `crash-free users：92.0%（受影響 8 位／共 100 位）`；最優先要修的是只影響 1 位使用者的「OrderViewModel 強制解包」 |
| 修正版 | `crash-free users：95.0%（受影響 5 位／共 100 位）`；最優先要修的是影響 3 位使用者的「Realm 跨執行緒」。兩個版本的 crash-free sessions 都是 99.2% |

**為什麼**：crash-free users 的分子和分母都是「人」，一位使用者 crash 幾次都只算一次；筆數適合拿來算 crash-free sessions（一次 session 最多算一次 crash）。把筆數當人數，數字變低，更糟的是排序會被誤導：u07 一個人製造的問題排在最前面，真正影響最多人的問題反而被擺到後面（第 5 題：依影響人數排序，不是依次數）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L01_CrashFreeUsers.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L01_CrashFreeUsers.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L01_CrashFreeUsers.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L01_CrashFreeUsers.kt#fixed
```

### 演練 2：API 少一個欄位，App 一打開就當掉（第 2 題）

**檔案**：`L02_ForceUnwrap.swift`（iOS）、`L02_ForceUnwrap.kt`（Android）

**情境**：行情 API 回傳的 JSON 裡有最新成交價 `last`。維護期間，API 會回傳不完整的資料：少了 `last`，或 `last` 是 `"--"`。演練依序處理三筆回應：正常、少欄位、`"--"`。Android 的 JSON 以解析完的 `Map` 表示（Gson、Moshi 解析成 Map 的結果也長這樣）。

**怎麼寫**

1. **Bug 版**：iOS 用 `JSONSerialization` 取出字典，`as! [String: Any]` 轉型、`Decimal(string: json["last"] as! String)!` 強制解包；Android 用 `BigDecimal(raw["last"]!!.toString())`。**從 Xcode（或 Android Studio）執行**才看得到當掉的位置。
2. **修正版**：在解碼那一層處理「欄位可能不存在」。iOS 寫一個 `Decodable` 的 `Ticker`，`last` 是 `Decimal?`，缺欄位或格式不對就是 `nil`（注意：`Decimal(string: "--")` 不會回傳 `nil`，要先檢查格式，演練的 `parsePrice` 已經寫好）；Android 的 `Ticker.last` 是 `BigDecimal?`，用 `toBigDecimalOrNull()`。畫面端拿到 `nil`，就沿用上一筆價格。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 印出第一筆價格之後，第二筆回應當掉。iOS：`Fatal error: Unexpectedly found nil while unwrapping an Optional value`（`EXC_BREAKPOINT`）；Android：`NullPointerException`。這個版本會當掉，所以自動檢查略過它 |
| 修正版 | 第一筆 `BTC_JPY 最新價 15000000`；第二、第三筆印出「沿用上一筆」；最後印出「三筆回應都處理完，App 沒有當掉」 |

**為什麼**：`!`、`as!`、`!!` 都是在說「我保證不會是空的」，API 一旦不守這個保證，整個程式就中止。與其一行一行補 `if let`，不如在解碼的那一層，就把「欄位可能缺少」當成預設情況處理：上層的畫面拿到的是已經安全的資料（第 5 題的步驟三）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L02_ForceUnwrap.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L02_ForceUnwrap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L02_ForceUnwrap.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L02_ForceUnwrap.kt#fixed
```

### 演練 3：自己裝了 crash 處理，Crashlytics 收不到了（第 2 題）

**檔案**：`L03_HandlerChain.swift`（iOS）、`L03_HandlerChain.kt`（Android）

**情境**：App 啟動時先初始化 Crashlytics，它會登記自己的「沒被接住的例外」處理函式（iOS 是 `NSSetUncaughtExceptionHandler`，Android 是 `Thread.setDefaultUncaughtExceptionHandler`）。後來有人想在 crash 前補寫一筆本機紀錄，又登記了一次。演練用「假的 Crashlytics」，並直接呼叫目前登記的 handler 來模擬系統在例外沒被接住時做的事，不會真的讓 App 當掉。

**怎麼寫**

1. **Bug 版**：直接登記自己的 handler，裡面只寫自己的紀錄。
2. **修正版**：登記前先取得前一個 handler（iOS 用 `NSGetUncaughtExceptionHandler()`，Android 用 `getDefaultUncaughtExceptionHandler()`）存起來，自己的紀錄寫完後再呼叫它。iOS 的 handler 是 C 函式，不能捕獲外面的變數，所以前一個 handler 要放在 `static` 變數裡。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `自己的紀錄收到 1 筆`、`Crashlytics 收到 0 筆` |
| 修正版 | `自己的紀錄收到 1 筆`、`Crashlytics 收到 1 筆` |

**為什麼**：這種全域的 handler 一次只能登記一個，後登記的會把前一個蓋掉，系統只呼叫最後一個。結果 App 真的當掉了，Crashlytics 的後台卻一筆都沒有，穩定性數字反而變好看。所有「全域只能有一個」的掛鉤（`signal()` 也是），登記前都要先取得前一個並串起來。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L03_HandlerChain.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L03_HandlerChain.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L03_HandlerChain.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L03_HandlerChain.kt#fixed
```

### 演練 4：crash 報告指到不相干的函式（第 3 題）

**檔案**：`L04_WrongDSYM.swift`（iOS）、`L04_WrongMapping.kt`（Android）

**情境**：App 3.1.0 先打包了 build 1，後來為了補一個修正又重新打包了 build 2，版本號還是 3.1.0。兩次編譯的結果不一樣：iOS 的二進位檔 UUID 不同、函式的位址也不同；Android 的 R8 重新取名，`a.a` 代表的函式變了，`mapping.txt` 開頭那行 `# pg_map_id: …` 就是這一次 build 的識別碼。上架的是 build 2，CI 卻只上傳了 build 1 的符號檔。演練分兩步：只有 build 1 的符號檔時還原一次；補上傳 build 2 的符號檔之後再還原一次。

**怎麼寫**

1. **Bug 版**：用版本號（iOS 是 `appVersion`，Android 是 `versionName`）在已上傳的符號檔裡找第一份相同的。
2. **修正版**：用 crash 報告裡的 Binary UUID（iOS）或 `pg_map_id`（Android）找識別碼一樣的那一份；找不到就回傳「未還原」，不要猜。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 兩次都還原成 `OrderViewModel.submit`，和「正確答案」`ChartRenderer.draw` 不同；補上傳了正確的符號檔也沒有改善 |
| 修正版 | 第一次是「未還原（缺少 UUID 9C3F5E7A 的 dSYM）」（Android 是 `pg_map_id 9c3f5e7a`）；補上傳之後還原出 `ChartRenderer.draw`，和正確答案一致 |

**為什麼**：符號檔記錄的是「這一次編譯，位址（或混淆後的名字）對應哪個函式」。版本號一樣，不代表是同一次編譯，位址表（或取名）可能完全不同。用錯的符號檔不會報錯，只會還原出一個看起來很合理的錯誤名字，把人帶去查不相干的程式碼，比看不懂更糟（本單元第 3 題：「最常見的錯」）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L04_WrongDSYM.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L04_WrongDSYM.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L04_WrongMapping.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L04_WrongMapping.kt#fixed
```

### 演練 5：卡住偵測要等卡完才說話（第 4 題）

**檔案**：`L05_HangWatchdog.swift`（iOS）、`L05_HangWatchdog.kt`（Android）

**情境**：啟動（iOS）或點擊按鈕（Android）時，在主執行緒「同步等設定檔」（演練用 `Thread.sleep` 0.8 秒代替同步等網路），主執行緒被佔住，畫面完全沒有反應。團隊想自己做一個卡住偵測：主執行緒超過 0.3 秒沒反應就回報。iOS 的演練用 App 真正的主執行緒；Android 的 JVM 單元測試沒有 `Looper`，演練用一條專用執行緒 `fake-main` 當主執行緒，行為一樣（卡在一個工作上，後面的工作都要等）。

**怎麼寫**

1. **Bug 版**：偵測放在主執行緒自己的排程上（iOS 是主執行緒 run loop 上的 `Timer`，Android 是排進 `fake-main` 佇列的定時工作），每 0.1 秒檢查「距離上一次 tick 超過 0.3 秒」就回報。
2. **修正版**：偵測放在自己的背景執行緒：每 0.1 秒請主執行緒回一聲（iOS 用 `DispatchQueue.main.async`，Android 用 `main.post`），超過 0.3 秒沒回就回報。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「卡住後 800 ms 才回報」（解答實測 iOS 800 ms），「回報時主執行緒已經恢復：來不及知道卡在哪裡」；Android 另外印出 `卡在：（看不出來）` |
| 修正版 | 卡住後約 200 到 400 ms 就回報（解答實測 iOS Debug 223 ms、Release 306 ms），「回報時主執行緒還在卡住：可以馬上抓現場」；Android 另外印出 `卡在：waitForConfigSync`，因為偵測程式趁主執行緒還卡住，讀了它的 stack trace |

**為什麼**：被佔住的主執行緒什麼都做不了，包括跑你寫來偵測它的程式。偵測程式放在同一條執行緒上，只能在卡完之後說「剛才隔了很久」，那時已經沒有現場可以看。放在背景執行緒，就能在卡住的當下回報，而且可以趁它還在卡的時候抓 stack（Android 的 `Looper.getMainLooper().thread.stackTrace`）。這也是 ANR-WatchDog 這類函式庫的做法；系統自己的 ANR 是 5 秒，App 自己的偵測要在這之前回報，才來得及記下線索。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L05_HangWatchdog.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L05_HangWatchdog.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L05_HangWatchdog.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L05_HangWatchdog.kt#fixed
```

### 演練 6：記憶體不足的次數比 crash 還多好幾倍（第 4 題）

**檔案**：`L06_FalseOOM.swift`（iOS）、`L06_FalseOom.kt`（Android）

**情境**：iOS 的 OOM（jetsam 終止）沒有 crash 報告，只能用推測：App 在生命週期裡記下狀態，下次啟動時看「上一次是不是不正常結束」。Android 11 起，`getHistoricalProcessExitReasons` 會回傳每次行程結束的原因（`reason`）和結束當下的重要性（`importance`）。演練各有 12 筆紀錄，其中真正在前景被記憶體不足終止的只有 2 筆；其餘是背景被系統回收（兩個平台每天都在發生，使用者根本不會發現）、crash、版本更新、重新開機、使用者滑掉等。

**怎麼寫**

1. **Bug 版**：iOS 只要上一次沒有正常結束（`cleanExit` 是 `false`）就算 OOM；Android 只要 `reason` 是 `REASON_LOW_MEMORY` 就算。
2. **修正版**：iOS 先排除 crash、版本更新、系統升級、重新開機、接著 debugger，而且只算「上一次最後的狀態是前景」的；Android 的 `REASON_LOW_MEMORY` 還要確認 `importance` 是前景或可見（`≤ IMPORTANCE_VISIBLE`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：`共 12 次啟動，判定為前景記憶體不足被終止：11 次`；Android：`共 12 筆結束紀錄，判定為前景記憶體不足被終止：6 筆`。都另外列出「其中誤判」 |
| 修正版 | 兩個平台都是 2 次（筆），沒有誤判 |

**為什麼**：系統回收背景裡的 App 是正常的記憶體管理，不是使用者遇到的問題；把它算進 OOM，數字會膨脹好幾倍，真正的前景 OOM 被淹沒。推測類的統計，要先排除所有「不是記憶體造成」的中斷（crash、更新、重開機、debugger），再只算使用者看得到的情況。iOS 14 以上也可以用 MetricKit 的 `MXAppExitMetric` 直接看系統彙整過的次數，前景和背景是分開統計的。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L06_FalseOOM.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L06_FalseOOM.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L06_FalseOom.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L06_FalseOom.kt#fixed
```

### 演練 7：Crashlytics 被「Job was cancelled」洗版（第 5 題）

**檔案**：`L07_CancelNoise.swift`（iOS）、`L07_CancelNoise.kt`（Android）

**情境**：使用者快速在畫面之間切換，每個畫面離開時會取消還在載入的工作。被取消的工作會丟出取消的錯誤（iOS 是 `CancellationError`，或 `URLSession` 被取消時的 `URLError(.cancelled)`；Android 是 `CancellationException`）。另外有 1 個畫面載入時真的遇到資料解碼失敗。團隊在每個 `catch` 裡用 non-fatal 回報（Crashlytics 的 `record(error:)`／`recordException`）。

**怎麼寫**

1. **Bug 版**：`catch` 到什麼就回報什麼。
2. **修正版**：iOS 先判斷 `error is CancellationError` 與 `URLError` 的 `code == .cancelled`，直接 `return`；Android 在 `catch (e: Exception)` 的前面加 `catch (e: CancellationException)`，不回報並 `throw e`。其餘才回報。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `Crashlytics 收到 6 筆 non-fatal`，其中 5 筆是取消（iOS：`CancellationError`、`NSURLErrorDomain Code=-999`；Android：`JobCancellationException`） |
| 修正版 | `Crashlytics 收到 1 筆 non-fatal`，只有「資料解碼失敗」 |

**為什麼**：取消是正常的流程控制，不是錯誤。回報它，真正的問題會被淹沒在洗版的訊息裡，Crashlytics 的問題列表也失去意義（這正是第 5 題步驟六在 Crashlytics 過濾「Job was cancelled」這類事件的原因）。Kotlin 還有第二個問題：`CancellationException` 被 `catch (e: Exception)` 吞掉、沒有重新丟出去，取消的訊號就斷了，所以修正版要 `throw e`。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L07_CancelNoise.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced06-Crash/L07_CancelNoise.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced06-crash/L07_CancelNoise.kt#bug
```

```lab-solution
android/solution/advanced/advanced06-crash/L07_CancelNoise.kt#fixed
```

第 6 題（為什麼有些 crash 只在正式版才出現）沒有另外做演練：Android 的 R8 keep rules 在平台對照的 Platform07Release 演練 4 用真的 R8 做過；iOS 的 Release 最佳化差異（例如 `assert` 在 Release 會被移除）要靠 `LABS_CONFIG=Release` 才看得出來，沒有穩定、能自動檢查的重現方式。第 5 題的其他步驟（依影響人數排序、降低雜訊）分別在演練 1、演練 7；第 3 題的保存符號檔、自動上傳是 CI 流程，在演練 4 只練「對錯符號檔」的邏輯。

## 常見陷阱

- **沒有保存每個版本的 dSYM 和 mapping。** 出事之後才發現符號檔不見了，stack 永遠還原不出來。要在 CI 打包的當下就上傳並存檔。
- **只看 crash 的次數，不看受影響的使用者數。** 一位使用者重複觸發幾千次，會讓次數看起來很嚇人，但優先處理的應該是影響最多人的問題。
- **把 OOM、ANR、watchdog 排除在「穩定性」之外。** 使用者不分是 crash 還是被系統終止，只知道 App 閃退或卡死。這幾種都要一起看。
- **把正常的取消當成錯誤上報。** `CancellationException`、使用者主動取消的請求會洗版，真正重要的問題被淹沒。
- **只在 debug build 測試。** R8 與編譯器最佳化的差異，要等到正式版才出現。
- **只修單一 crash，沒有預防機制。** 同一類問題下次換個畫面又出現。要用 lint、測試、架構規則讓它進不來。
- **報告「crash-free 明顯提升」，卻說不出是 users 還是 sessions。** 面試官一追問就失去可信度，先把指標定義清楚。

## 面試官想聽到的

1. 能分清楚兩類 crash（程式出錯、被系統終止），並說出 watchdog、jetsam、ANR 的差別與偵測方式。
2. 知道 symbolication 的前提：dSYM 的 UUID 要對上、每個 release 的 mapping 要保存，不同版本不能混用。
3. 有一套完整方法：固定指標、依影響人數排序、從架構根治同一類問題、預防、分階段發布並用指標決定要不要擴大，而不是只會修單點。
4. 講數字時能說清楚是哪個工具、users 還是 sessions，並且誠實說明這個數字是怎麼追蹤的。
5. 知道 release build 與 debug build 的差異（R8、最佳化），並有 smoke test 的做法。

> [!FIELD]
> 在一個雙平台的社交 App，我獨立負責 iOS 與 Android，把雙平台的 crash-free users（沒有發生 crash 的使用者比例）明顯提升並長期維持。做法的核心是重構：iOS 在短時間內完成全 App 重構，Android 在開發新功能之餘逐頁重構，並把依賴注入（dependency injection，由外部把物件需要的東西傳給它）從 Dagger（設定較繁瑣的依賴注入函式庫，Hilt 就是建立在它之上）遷移到 Hilt。這正是第 5 題「從架構根治」的實例。
> 其他可以舉的細節：Android 在 release 啟用 R8 的縮減程式碼與資源，並處理過一次只在 release 才出現的 `ClassCastException`，原因是 R8 的 full mode 把 Hilt 產生的類別合併了，解法是調整 R8 設定、補上 keep 規則，並逐一檢查各 SDK（software development kit，第三方提供的開發套件）需要的規則；Crashlytics 上過濾掉「Job was cancelled」這類雜訊；iOS 修過聊天室的記憶體洩漏與 `[weak self]` 的問題；打包流程中把 dSYM 上傳到 Crashlytics。
> 被問到「這個數字怎麼量的」，標準回答是：說明用哪個工具（例如 Crashlytics）、固定哪些版本與期間、用的是 crash-free users 還是 crash-free sessions；如果沒有留下正式報告，就誠實說這是持續追蹤的數字，然後回到做法本身。
> 換成交易所 App，我會把「用 release build 跑 smoke test」「分階段發布」「新版本的 crash-free users 沒有變差才擴大」做成固定流程，讓數字每一版都看得到。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 符號還原 | dSYM，用 UUID 對應到那一次編譯 | R8 `mapping.txt`；native 程式碼要另外上傳 `.so` 的符號 |
| 主執行緒卡住 | watchdog（例外碼 `0x8badf00d`）、hang | ANR（輸入事件 5 秒、broadcast 前景 10 秒／背景 60 秒） |
| 記憶體不夠 | jetsam，沒有 crash 報告；用 MetricKit 的 `MXAppExitMetric` 看次數 | `OutOfMemoryError`（有 stack trace）、lmkd 終止（沒有）；`ApplicationExitInfo` 可查退出原因 |
| 官方後台 | Xcode Organizer、MetricKit | Play Console 的 Android vitals、`ApplicationExitInfo` |
| 預防工具 | SwiftLint、Swift 6 strict concurrency | detekt、StrictMode、release build 的 smoke test |
| 分階段發布 | App Store Connect 的 phased release（7 天逐步擴大，可暫停） | Play Console 的 staged rollout（自訂百分比） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 無 crash 使用者比例 | crash-free users | クラッシュフリーユーザー率 |
| 符號還原 | symbolication | シンボル化（シンボリケーション） |
| 主執行緒卡住導致沒有回應 | ANR（Application Not Responding） | ANR（アプリケーションが応答しません） |
| 記憶體不足 | OOM（out of memory） | メモリ不足 |
| 分階段發布 | staged rollout／phased release | 段階的ロールアウト／段階的リリース |
| 冒煙測試（發布前快速檢查關鍵流程） | smoke test | スモークテスト |
