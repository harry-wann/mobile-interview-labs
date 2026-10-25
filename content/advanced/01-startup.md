---
id: advanced-startup
title: App 啟動為什麼慢：冷啟動、iOS 的 dyld 與 didFinishLaunching、Android 的 Application.onCreate 與 Baseline Profile
summary: 交易所 App 打開到看見行情，每一秒都有人在等。這個單元說明冷啟動、溫啟動、熱啟動的差別，iOS 與 Android 啟動時各階段會卡在哪裡、怎麼改，以及怎麼用 Instruments 和 Macrobenchmark 量出來，而不是憑感覺。
priority: P1
day: 3
tags: [startup, launch-time, dyld, app-startup, baseline-profile, splashscreen, macrobenchmark]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 冷啟動、溫啟動、熱啟動差在哪？
2. 「打開 App 到看見行情」要量到哪一個時間點？
3. iOS 在 `main()` 之前做了什麼事？哪些會讓它變慢？
4. iOS 的 `application(_:didFinishLaunchingWithOptions:)` 做太多事會怎樣？什麼是預熱（prewarm）？
5. Android 的 `Application.onCreate`、ContentProvider、App Startup 是什麼關係？
6. Baseline Profile 是什麼？為什麼能讓啟動變快？
7. 怎麼量啟動時間？

### 1. 冷啟動、溫啟動、熱啟動差在哪？

差別在於 **App 的行程（process，作業系統替 App 開的執行單位）和畫面，還有多少留在記憶體裡**。留得越少，要重做的事越多，啟動就越慢。冷啟動最慢，也是最值得最佳化的一種。

情境：比特幣突然大跌，使用者立刻點開交易所 App。如果這個 App 已經在背景放了半小時，很可能因為系統要回收記憶體，已經被系統終止了。這時使用者看到的就是冷啟動：行程、Application、第一個畫面全部要重做一遍。反過來，如果使用者只是切去看一下 LINE 再切回來，是熱啟動，畫面直接回來，幾乎感覺不到等待。

**Android 把啟動分成三種（Android 官方文件的說法）：**

| | 行程還在嗎 | Activity 還在嗎 | 要做的事 | 速度 |
|---|---|---|---|---|
| 冷啟動（cold start） | 不在 | 不在 | 建立行程、執行 `Application.onCreate`、建立 Activity、畫出第一個畫面 | 最慢 |
| 溫啟動（warm start） | 可能還在 | 要重建 | 使用者按返回離開、Activity 被銷毀後再開；Activity 要重新執行 `onCreate`，可以靠 `savedInstanceState`（系統幫你保存的畫面狀態）還原 | 中間 |
| 熱啟動（hot start） | 在 | 在 | 只是從背景回到前景，執行 `onRestart`、`onStart`、`onResume` | 最快 |

**iOS 沒有固定的三個名字，** Apple 的文件與 WWDC（Apple 每年的開發者大會）影片常用 cold launch、warm launch、resume 三種說法：

- cold launch：App 不在記憶體、沒有行程，系統快取裡也沒有它的資料（例如剛重新開機後第一次開）。
- warm launch：行程已經不存在，但 App 用過的部分資料還留在系統快取裡，所以比 cold launch 快。
- resume：App 被系統暫停（suspended），整個還在記憶體裡，只是回到前景。

量測和最佳化時，這三種一定要分開看，不能混成一個平均數。冷啟動的數字最難看，也最接近「使用者隔了一陣子再打開」的真實感受。

> [!TIP]
> 面試時可以這樣說：「啟動分成冷、溫、熱三種，差別是行程和畫面還有多少留在記憶體。我會以冷啟動為主要的最佳化目標，因為它最慢，也是交易所使用者在行情劇烈變動、隔一段時間再打開 App 時最常遇到的。」

### 2. 「打開 App 到看見行情」要量到哪一個時間點？

要先決定終點。對交易所 App 來說，**使用者在意的是「看到行情數字」，不是「畫面第一次出現」。** 如果只量第一個畫面，很容易做出「很快跳出一個灰色骨架畫面，但行情要再等三秒」的 App，數字好看，使用者卻沒有比較滿意。

兩個平台各有自己的名詞：

| 時間點 | 意思 | 怎麼取得 |
|---|---|---|
| 首幀（first frame），iOS | 系統一開始先顯示 launch screen（一張靜態圖），App 畫出第一個真正的畫面時才換掉它。Apple 建議這一刻要在大約 400 毫秒內（WWDC19 的 Optimizing App Launch） | MetricKit（Apple 提供、收集真實使用者裝置上效能資料的框架）、Xcode Organizer（Xcode 裡可以查看已上架 App 線上效能資料的視窗）、自己在程式裡打 signpost（在程式碼裡打的時間標記，第 7 題會示範） |
| TTID，Android | time to initial display 的縮寫，第一個 frame 畫出來的時間，由系統自動記錄 | logcat 會印 `Displayed ...` 那一行 |
| TTFD，Android | time to full display 的縮寫，內容真的準備好、使用者可以操作的時間，要 App 自己呼叫 `reportFullyDrawn()` 才會記錄 | `reportFullyDrawn()` |

Android 要自己標示「行情真的出來了」：

```kotlin
class MarketActivity : ComponentActivity() {
    // 行情列表第一次有資料（快取的或即時的都算）畫出來之後呼叫
    private fun onFirstPricesShown() {
        reportFullyDrawn()   // 系統記下 TTFD，logcat 也會多印一行 Fully drawn
    }
}
```

如果用 Jetpack Compose（Android 用 Kotlin 描述畫面的 UI 工具包），可以用 `ReportDrawnWhen { uiState.prices.isNotEmpty() }`（在 `androidx.activity.compose` 套件裡），條件成立時自動幫你呼叫。

Google Play 的品質儀表板 Android vitals，對三種啟動各有一個「過長」的門檻：冷啟動 5 秒、溫啟動 2 秒、熱啟動 1.5 秒。超過這些時間的比例太高，Google Play 會把它列為品質問題。

> [!TIP]
> 面試時可以這樣說：「我會把終點定義在使用者看到行情的那一刻，而不只是第一個畫面。Android 上除了系統自動記錄的 TTID，我會在行情列表有資料時呼叫 reportFullyDrawn 記錄 TTFD；iOS 則在第一個行情畫出來時打 signpost。」

### 3. iOS 在 `main()` 之前做了什麼事？哪些會讓它變慢？

使用者點下圖示後，iOS 在執行到你寫的 `main()` 之前，其實已經做了一大串事。這一段叫 **pre-main**，你的程式碼還沒開始跑，但時間已經在算了。

```text
使用者點擊圖示
  → 系統建立行程
  → dyld 載入 App 的執行檔和所有動態 framework
  → rebase 與 bind
  → Objective-C runtime 設定，執行每個類別的 +load 與 static initializer
  → main() → UIApplicationMain
  → application(_:didFinishLaunchingWithOptions:)
  → 第一個畫面（首幀）
```

名詞一個一個解釋：

- **dyld**：dynamic linker（動態連結器）的縮寫，是 iOS 的系統元件，負責把你的執行檔和它用到的函式庫載入記憶體。
- **動態 framework**：App 啟動時才由 dyld 一個一個載入的函式庫。載入的個數越多，花的時間越長。
- **rebase 與 bind**：因為 ASLR（address space layout randomization，每次啟動都把程式隨機放在不同的記憶體位置，增加被攻擊的難度），執行檔裡寫死的位址要全部加上這次的位移，這叫 rebase；呼叫其他函式庫的函式時，要查出它這次實際在哪裡並填進去，這叫 bind。動態 framework 越多，這兩件事越多。
- **`+load` 與 static initializer**：Objective-C 類別如果實作了 `+load`（類別方法），或 C／C++ 有 `__attribute__((constructor))` 的函式、全域物件的建構式，它們都會在 `main()` 之前同步執行，而且每一個都會拖慢啟動。常見用途是 method swizzling（在執行時把一個方法換成另一個實作）。Swift 的全域變數與 `static` 屬性預設是第一次用到才初始化，所以比較沒有這個問題。

```objectivec
@implementation PriceTracker
+ (void)load {
    // 在 main() 之前就會執行，而且每個實作 +load 的類別都會執行一次。
    // 在這裡呼叫第三方函式庫（SDK，software development kit）的初始化，會直接加到啟動時間裡
    [AnalyticsSDK setup];
}
@end
```

**怎麼改：**

| 問題 | 改法 |
|---|---|
| 動態 framework 太多 | 改成靜態連結（static linking：建置時就把函式庫的程式碼併進主執行檔，啟動時不用再載入）。Swift Package Manager（SPM）和 CocoaPods 都可以設定。Xcode 15 起也有 mergeable libraries，可以把多個動態 framework 合併成一個 |
| `+load`、`__attribute__((constructor))` | 改成需要時才執行。Objective-C 的 `+initialize` 會在類別第一次被使用時才呼叫，比 `+load` 好 |
| 沒用到的第三方 SDK | 直接移除，不用載入就不用花時間 |

> [!TIP]
> 面試時可以這樣說：「main 之前的時間主要花在 dyld 載入動態 framework、rebase 和 bind，以及 Objective-C 的 +load 和 static initializer。我會減少動態 framework 的數量，改靜態連結或用 mergeable libraries，並把 +load 裡的工作改成用到時才做。」

### 4. `didFinishLaunching` 做太多事會怎樣？什麼是預熱？

`application(_:didFinishLaunchingWithOptions:)` 是你的程式碼最早能執行的地方之一，**它在主執行緒執行，而且系統要等它回傳之後，才會繼續畫出第一個畫面**。所以你在這裡同步做的每一件事，都會直接加到使用者看到 launch screen 的時間裡。

交易所 App 最常見的做法是在這裡一次初始化所有東西：

```swift
// 不好的做法：全部都在首幀之前同步做完
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    Analytics.configure()                    // 分析 SDK
    AdsSDK.start()                           // 廣告 SDK
    RemoteConfig.fetchSynchronously()        // 同步等網路，網路慢就一直卡著
    try? Database.openAndMigrate()           // 開啟本機資料庫，如果要升級資料表格式會很久
    return true
}
```

這四件事都不是「顯示行情」必須的。比較好的做法是：

```swift
// 好的做法：首幀之前只做必要的事，其餘等首畫面出現後再做
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    CrashReporter.start()    // 當機回報要越早越好，否則啟動時的當機會漏掉
    return true
}

// 第一個畫面出現後（例如行情畫面的 viewDidAppear）才呼叫
func startDeferredWork() {
    Task.detached(priority: .utility) {
        Analytics.configure()
        AdsSDK.start()
        await RemoteConfig.fetch(timeout: 8)   // 網路的工作要設逾時，不能無限等
    }
}
```

這裡有兩個取捨要記得：當機回報工具不能延後，否則啟動階段的當機會留不下紀錄；有些 SDK 規定必須在主執行緒初始化，這時可以延後，但不能放到背景。

**預熱（prewarm）。** 從 iOS 15 開始，系統可能在使用者還沒點擊圖示之前，就先替常用的 App 把啟動流程跑到呼叫 `UIApplicationMain` 之前，等使用者真的點擊時再接著往下跑。這會造成兩個影響：

1. **量測起點不能用「行程建立的時間」。** 預熱的行程可能在使用者點擊的幾分鐘前就建立了，這段時間使用者並沒有在等，如果算進去，數字會又大又不穩定。要用 MetricKit、Xcode Organizer（系統端量測），或自己從使用者看得到的時間點算起。
2. **`+load`、static initializer 和 `main()` 裡的程式碼，可能在使用者還沒打開 App 時就執行了。** 不要在這裡假設使用者正在使用 App。

為了讓使用者先看到內容，交易所 App 常見的做法是：行情列表先顯示上次關閉前保存的價格（要標示時間，或標示「更新中」，不能讓使用者以為那是即時價格，更不能直接拿來下單），WebSocket（保持連線、讓伺服器可以隨時推送資料的網路連線）在首畫面出現後才建立，連上之後再把畫面換成即時價格。

> [!TIP]
> 面試時可以這樣說：「didFinishLaunching 在主執行緒執行，系統要等它回傳才會畫出第一個畫面，所以我只在這裡做首畫面必須的事，當機回報要早，分析、廣告、遠端設定都延到首畫面出現之後，網路相關的工作一定設逾時。另外 iOS 15 之後有預熱，量測不能用行程建立時間當起點。」

> [!FIELD]
> 在一個社交 App 的 iOS 版做過一個和啟動有關的修正：把 Remote Config（Firebase 的遠端設定，用來從伺服器下載開關與參數）的下載加上 8 秒逾時，避免網路很慢時，啟動被它卡住。那個 App 整合了 Firebase、AdMob 廣告、分析等多個 SDK，這些都是檢查啟動路徑時最先會看的地方。
> 面試時可以接著說：換成交易所 App，我會先用 Instruments（iOS）和 Macrobenchmark（Android）建立冷啟動的基準數字，再決定要先延後哪些 SDK 的初始化。

### 5. Android 的 `Application.onCreate`、ContentProvider、App Startup 是什麼關係？

Android 冷啟動時，系統建立好行程之後，會**先建立 App 宣告的 ContentProvider，再執行 `Application.onCreate`**，最後才建立 Activity、畫出第一個畫面。這個順序造成兩個容易被忽略的問題：

1. **`Application.onCreate` 裡同步初始化太多 SDK。** 和 iOS 的 `didFinishLaunching` 一樣，它在主執行緒執行，做多久使用者就等多久。
2. **函式庫偷偷用 ContentProvider 自動初始化。** 很多函式庫（例如 Firebase）為了讓你「不用寫初始化程式碼」，在它們的 manifest 裡宣告一個 ContentProvider，利用系統會在 `Application.onCreate` 之前建立 ContentProvider 的特性自動初始化。每個函式庫各用一個 provider，加起來可能就是一大段時間，而且你的程式碼裡完全看不出來。

**App Startup**（`androidx.startup` 函式庫）就是為了解決這件事：它只用一個共用的 `InitializationProvider`，函式庫和你自己的初始化工作都寫成 `Initializer`，由它依你宣告的相依順序執行。

```kotlin
// 寫成 Initializer：create() 裡做初始化，dependencies() 宣告要等誰先完成
class AnalyticsInitializer : Initializer<Analytics> {
    override fun create(context: Context): Analytics = Analytics.init(context)

    // 這個初始化要等 LoggerInitializer 完成之後才執行
    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(LoggerInitializer::class.java)
}
```

要注意：App Startup 本身**不會讓初始化變快**，它只是減少 provider 的數量，並且讓順序可以控制。真正省時間的做法是「延後」：在 manifest 裡用 `tools:node="remove"` 把某個 Initializer 移除（不要自動執行），等到真的需要時再手動呼叫：

```kotlin
// 例如進入行情畫面之後才初始化分析工具
AppInitializer.getInstance(context)
    .initializeComponent(AnalyticsInitializer::class.java)
```

使用 Hilt（Google 的依賴注入函式庫，依賴注入就是由框架幫你建立並提供物件）時，也要避免在 `@Singleton` 物件的建構子裡做讀檔或網路這類耗時的事。比較重的物件可以用 `Lazy<T>` 或 `Provider<T>` 包起來，用到時才建立。

**SplashScreen API。** 從 Android 12（API 31）開始，系統在冷啟動和溫啟動時一定會顯示一個啟動畫面（splash screen）。用 `androidx.core:core-splashscreen` 的 `installSplashScreen()`，舊版本的 Android 也能得到一致的效果。

```kotlin
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必須在 super.onCreate 之前呼叫
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 啟動畫面停留到「首畫面需要的本機資料」準備好為止。
        // 不要把登入 API、遠端設定這類網路請求放進去，網路慢時使用者會一直卡在啟動畫面
        splash.setKeepOnScreenCondition { !viewModel.cachedPricesLoaded.value }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「Android 冷啟動時，ContentProvider 會在 Application.onCreate 之前就初始化，所以很多函式庫的自動初始化會在我的程式碼之前消耗時間。我會用 App Startup 管理順序，並把不影響首畫面的 SDK 移除自動初始化、改成需要時才手動初始化。SplashScreen 的停留條件我只放本機資料，不放網路請求。」

### 6. Baseline Profile 是什麼？為什麼能讓啟動變快？

Baseline Profile 是一份**記錄啟動與常用操作會用到哪些類別和方法的清單**，隨著 App 一起打包，讓系統在安裝時就把清單裡的程式碼預先編譯好（交給系統處理的是 Google Play，或 ProfileInstaller 函式庫）。

先理解沒有它的時候發生什麼事。Android 的執行環境叫 ART（Android Runtime）。App 的程式碼打包成 DEX（Dalvik Executable）位元碼（bytecode，還不是機器能直接執行的格式），ART 執行時有三種方式：

- **直譯**：一行一行讀位元碼來執行，最慢。
- **JIT**（just-in-time compilation，執行時編譯）：發現某段程式碼常被執行，就在執行當下編譯成機器碼。編譯本身要花時間，而且要等程式跑一陣子才知道哪些是常用的。
- **AOT**（ahead-of-time compilation，執行前編譯）：事先編譯成機器碼，執行時不用再等。

沒有 Baseline Profile 時，剛安裝或剛更新後的第一次啟動，大部分程式碼都是直譯加 JIT，所以最慢；要等系統之後累積了使用紀錄，才會在背景（通常是手機閒置、充電時）把常用的部分改成 AOT。有了 Baseline Profile，系統在安裝時就直接把清單裡的部分做 AOT，第一次啟動就有效果，連第一次捲動行情列表都會比較順。

製作方式是寫一段 Macrobenchmark 測試，用 `BaselineProfileRule` 實際操作一遍「重要的使用者流程」，工具會記錄這些流程用到的程式碼，產生清單。交易所 App 的重要流程可以是：啟動 → 看到行情列表 → 捲動列表 → 進入某個交易對 → 開啟下單畫面。Jetpack Compose 等 AndroidX 函式庫本身已經附有自己的 profile。

Baseline Profile 只改善 ART 這一層，所以不會解決「`Application.onCreate` 同步做太多事」的問題，兩種最佳化要一起做。

> [!TIP]
> 面試時可以這樣說：「Baseline Profile 是一份預先編譯的清單。沒有它時，第一次啟動大部分程式碼是直譯加 JIT，比較慢；有了它，安裝時就把啟動和常用流程預先編譯成機器碼，所以第一次啟動和第一次捲動都會變順。我會用 Macrobenchmark 比較有沒有 profile 的差別，用數字證明效果。」

### 7. 怎麼量啟動時間？

原則是：**先量、再改、改完再量。** 量測要在實機、release 型態（正式版的建置設定）下進行，重複跑多次，看中位數和較慢的那一端（例如 90 百分位），不要只看單次結果。模擬器和 debug build 的數字沒有參考價值。

**iOS：**

| 工具 | 用在哪裡 |
|---|---|
| Instruments 的 App Launch 範本 | 在自己的裝置上分析啟動過程，看 pre-main 和主執行緒各個階段花了多少時間，找出最大的那一段 |
| Xcode Organizer 的 Launch Time | 看真實使用者的啟動時間分佈，不同機型與系統版本分開看 |
| MetricKit 的 `MXAppLaunchMetric` | 在 App 內收集真實使用者實機上的啟動時間，可以自己傳到後端 |
| signpost | 自己在程式碼裡標示起點與終點，Instruments 上會畫出這一段區間 |

```swift
import os

let signposter = OSSignposter(subsystem: "jp.example.app", category: "launch")
var launchState: OSSignpostIntervalState?

// 在 didFinishLaunching 的最前面記下起點
func markLaunchStart() {
    launchState = signposter.beginInterval("LaunchToMarketList")
}

// 行情列表第一次畫出價格時記下終點。Instruments 會顯示這一段花了多久
func markMarketListShown() {
    guard let state = launchState else { return }
    signposter.endInterval("LaunchToMarketList", state)
    launchState = nil
}
```

**Android：**

| 工具 | 用在哪裡 |
|---|---|
| Macrobenchmark | 用自動化測試重複跑啟動，輸出 TTID、TTFD 的統計，可以放進 CI（continuous integration，每次提交程式碼就自動建置與測試）比較有沒有退步 |
| Perfetto | 系統追蹤（trace）工具，把每個執行緒在每一毫秒做什麼畫成時間軸，看出主執行緒卡在哪裡 |
| Android vitals | Google Play Console 上的真實使用者資料 |

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStart() = rule.measureRepeated(
        packageName = "jp.example.app",
        metrics = listOf(StartupTimingMetric()),   // 回報 TTID，有呼叫 reportFullyDrawn 時也回報 TTFD
        iterations = 10,                           // 重複 10 次
        startupMode = StartupMode.COLD,            // 每次都先殺掉行程，確保是冷啟動
        compilationMode = CompilationMode.Partial(), // 套用 Baseline Profile；改成 None() 可以比較差別
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

Macrobenchmark 要放在獨立的測試模組，並用接近正式版（不可除錯）的建置類型，在實機上執行。

> [!TIP]
> 面試時可以這樣說：「我會先建立基準：iOS 用 Instruments 的 App Launch 範本找出最大的一段，再用 Organizer 和 MetricKit 看真實使用者的分佈；Android 用 Macrobenchmark 在實機上重複量冷啟動，再用 Perfetto 看主執行緒。每次改動都和基準比，而且要在 release 型態量。」

## 實際演練

> [!LAB]
> **打開**：iOS：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced01Startup**。Android：Android Studio 打開 `labs/android` 資料夾，Run configuration 選 **app**，按 ▶，App 首頁進入 **Advanced01Startup**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced01-Startup/`，解答在 `labs/ios/Solution/Advanced/Advanced01-Startup/`；Android 你寫在 `labs/android/practice/advanced/advanced01-startup/`，解答在 `labs/android/solution/advanced/advanced01-startup/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：演練用「讓執行緒停幾十毫秒」代表一個 SDK 初始化要花的時間，數字和真機不同，重點是看出「哪些事排在首幀之前」。真正量啟動時間要用 Instruments 與 Macrobenchmark（第 7 題），而且要在實機、release 型態。

### 演練 1：後台說啟動只要 20 毫秒，使用者卻等了將近半秒（第 2 題）

**檔案**：`L01_LaunchEndpoint.swift`（iOS）、`L01_LaunchEndpoint.kt`（Android）

**情境**：App 啟動後先在約 20 毫秒內畫出骨架畫面（首幀），再向伺服器要行情（約 450 毫秒），行情畫出來，使用者才真的能看價格。啟動時間要回報給後台。

**怎麼寫**

1. **Bug 版**：iOS 在 `onFirstFrame`（首幀出現）呼叫 `trace.end()`，結束 `OSSignposter` 的 interval 並回報；Android 在 `onFirstFrame` 呼叫 `trace.reportFullyDrawn()`。
2. **修正版**：iOS 改在 `onPricesShown`（行情第一次畫出來）呼叫 `trace.end()`；Android 改在 `onPricesShown` 呼叫 `trace.reportFullyDrawn()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 首幀約 20 ms，使用者看到行情約 470 ms，回報給後台的卻是約 20 ms，差了二十幾倍 |
| 修正版 | 回報給後台的和「使用者看到行情」的時間幾乎一樣，約 470 ms |

**為什麼**：首幀只代表「畫面不再是空白的 launch screen」，使用者要的是行情。把終點放在首幀，量出來的數字很好看，卻不是使用者的等待時間。Android 的 TTID 由系統自動記錄在首幀，TTFD 要自己呼叫 `reportFullyDrawn()`；iOS 沒有現成的 TTFD，要自己在行情第一次畫出來時結束 signpost。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L01_LaunchEndpoint.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L01_LaunchEndpoint.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced01-startup/L01_LaunchEndpoint.kt#bug
```

```lab-solution
android/solution/advanced/advanced01-startup/L01_LaunchEndpoint.kt#fixed
```

### 演練 2：畫面還沒出現，6 個服務已經全部建好（第 3 題）

**檔案**：`L02_LazyServices.swift`（只有 iOS）

**情境**：Objective-C 的 `+load`、C 的 static initializer 會在 `main()` 之前同步執行，共同的問題是不管這次用不用得到，啟動時都先做完。Swift 沒有 `+load`，但「啟動時把所有服務建好，放進一張表」是同樣的寫法。演練裡有 6 個服務（當機回報、資料庫、分析、廣告、A/B 測試、客服聊天），每個建立要花 10 到 90 毫秒；首畫面只需要前兩個，打開行情頁之後再用到分析，其餘這次根本沒用到。

**怎麼寫**

1. **Bug 版**：`bootAll` 用 `serviceCosts` 的每一筆建立 `Service`，一次建立全部 6 個。
2. **修正版**：寫一個登錄表 `Registry.service(_:)`：`created` 字典裡已經有就直接回傳，沒有才建立並記起來。啟動時只向它要 CrashReporter 與 Database，打開行情頁才要 Analytics。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 首幀前建立了 6 個服務，首幀約 360 ms |
| 修正版 | 首幀前建立了 2 個服務，首幀約 50 ms；整個流程結束一共 3 個（多了 Analytics），廣告、A/B 測試、聊天沒有建立 |

**為什麼**：啟動時間是「首幀之前所有同步工作」的總和，不管這些工作這次有沒有用到。Swift 的全域變數與 `static` 屬性本來就是第一次用到才初始化，所以沒有 `+load` 那種啟動時一定要付的成本；登錄表（或依賴注入框架的 lazy）是自己控制「什麼時候第一次用到」。pre-main 的另外兩段（dyld 載入動態 framework、rebase 與 bind）發生在你的程式碼開始之前，演練 App 裡重現不了，要用 Instruments 的 App Launch 範本看。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L02_LazyServices.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L02_LazyServices.swift#fixed
```

### 演練 3：網路一慢，使用者盯著 launch screen 好幾秒（第 4 題）

**檔案**：`L03_DidFinishLaunching.swift`（只有 iOS；Android 對應的是演練 4）

**情境**：`application(_:didFinishLaunchingWithOptions:)` 在主執行緒執行，系統要等它回傳才畫出首幀。這個 App 啟動時要初始化當機回報（15 ms）、分析 SDK（80 ms）、廣告 SDK（120 ms）、資料庫（開啟並升級資料表，100 ms），還有向伺服器要遠端設定；這次網路很慢，遠端設定要 1500 ms 才回得來。演練用一條背景執行緒代表主執行緒，不會真的卡住 App。

**怎麼寫**

1. **Bug 版**：`didFinishLaunching` 依序同步呼叫 5 個初始化步驟，遠端設定的 `timeout` 傳 `nil`（一直等到回應為止）。
2. **修正版**：首幀之前只呼叫 `crashReporter()`；首幀之後用 `Task.detached(priority: .utility)` 在背景做 `analytics()`、`adsSDK()`、`database()`，遠端設定改成 `timeout: 0.3`，逾時就先用上次的設定。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「首幀畫出來」約 1900 ms（15 + 80 + 120 + 100 + 1500）；5 件事的 log 都排在首幀之前 |
| 修正版 | 「首幀畫出來」約 20 ms，前面只有 CrashReporter；其餘在首幀之後完成，遠端設定在 300 ms 後逾時 |

**為什麼**：首幀之前的同步工作都直接算進使用者看 launch screen 的時間，而網路請求的時間完全不受你控制。當機回報不能延後，否則啟動階段的當機會留不下紀錄；其他可以等首幀之後，網路的工作一定要設逾時。量這段時間時要注意預熱（prewarm）：iOS 15 之後系統可能在使用者點擊之前就先跑完 `main()` 之前的部分，所以不能從行程建立的時間算起。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L03_DidFinishLaunching.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L03_DidFinishLaunching.swift#fixed
```

### 演練 4：畫面還沒出現，SDK 已經全部初始化，還有的順序不對（第 5 題）

**檔案**：`L04_AppInitializer.kt`（只有 Android）

**情境**：App 有 5 個元件：Logger（10 ms）、CrashReporter（15 ms，要先有 Logger）、Analytics（80 ms，要先有 Logger）、AdsSdk（120 ms，要先有 Analytics）、ImageLoader（60 ms）。首畫面只需要 Logger 與 CrashReporter。演練用一個縮小版的 `AppInitializer` 重現 App Startup 的規則（真正的 `androidx.startup` 需要 Context，JVM 單元測試跑不了）。

**怎麼寫**

1. **Bug 版**：`autoInitProviders` 依 `manifestOrder`（AdsSdk、Analytics、ImageLoader、Logger、CrashReporter）把每個元件都 `create()` 一次，模擬每個函式庫各用一個 ContentProvider 自動初始化：順序不管依賴，全部同步。
2. **修正版**：寫 `initializeComponent(startup, name)`：已經初始化過就直接回傳；否則先對每個 `dependencies` 呼叫自己，最後才 `create(name)`。首幀之前只對 CrashReporter 呼叫（Logger 會跟著先做），行情頁才對 AdsSdk 與 ImageLoader 呼叫。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 5 個元件全部在首幀前同步初始化（約 285 ms）；AdsSdk 與 Analytics 的 log 帶「警告」：它們依賴的元件還沒初始化，功能不完整 |
| 修正版 | 首幀前只有 Logger、CrashReporter（約 25 ms）；打開行情頁後依序 Analytics、AdsSdk、ImageLoader，沒有警告，AdsSdk 呼叫兩次也只初始化一次 |

**為什麼**：函式庫為了「不用寫初始化程式碼」各自宣告 ContentProvider，系統在 `Application.onCreate` 之前依 manifest 的順序全部建立，時間全算進啟動，順序也控制不了。App Startup 用一個共用的 provider，讓每個 `Initializer` 宣告 `dependencies()`，由它依相依順序執行；真正省時間的是在 manifest 用 `tools:node="remove"` 移除不必要的自動初始化，用到時才呼叫 `AppInitializer.getInstance(context).initializeComponent(...)`。

**Android 解答**

```lab-solution
android/solution/advanced/advanced01-startup/L04_AppInitializer.kt#bug
```

```lab-solution
android/solution/advanced/advanced01-startup/L04_AppInitializer.kt#fixed
```

### 演練 5：優化後「快了 40%」，其實一點都沒有變快（第 7 題）

**檔案**：`L05_LaunchStats.swift`（iOS）、`L05_LaunchStats.kt`（Android）

**情境**：優化前後各量了 10 次冷啟動。優化前有一次碰巧遇到系統忙碌，花了 3010 ms，其他次都在 400 ms 左右；優化後 10 次都在 400 ms 左右。這個改動其實沒有影響啟動時間。團隊規則：代表值和慢的那一端都改善超過 10%，才算有效。

**怎麼寫**（兩個平台相同）

1. **Bug 版**：`summarize` 用平均值當代表值、用最大值當慢的那一端。
2. **修正版**：`summarize` 用中位數（排序後正中間的值，筆數是偶數就取中間兩個的平均）和 p90（nearest-rank：排序後第 ceil(0.9 × n) 個）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 優化前平均 664 ms、最大 3010 ms；優化後平均 400 ms；結論：有效，平均改善 39%（錯的） |
| 修正版 | 優化前中位數 404 ms、p90 412 ms；優化後中位數 400 ms、p90 404 ms；結論：沒有顯著差異 |

**為什麼**：單次結果或平均值很容易被一次極端值拉動。重複量多次，看中位數和 p90，才是「大部分使用者的體驗」和「比較慢的那一端」。Macrobenchmark 的 `iterations` 就是在做這件事；Instruments 沒有自動重複，要自己多錄幾次。量測還要在實機、release 型態，模擬器與 debug build 的數字沒有參考價值。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L05_LaunchStats.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced01-Startup/L05_LaunchStats.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced01-startup/L05_LaunchStats.kt#bug
```

```lab-solution
android/solution/advanced/advanced01-startup/L05_LaunchStats.kt#fixed
```

第 1 題（冷啟動、溫啟動、熱啟動）是概念比較，沒有演練。第 6 題（Baseline Profile）是 ART 在安裝時把清單裡的程式碼預先編譯成機器碼，效果要在實機上用 Macrobenchmark 比較「有沒有 profile」才看得出來，JVM 單元測試與模擬器上的 debug 版重現不了，沒有演練；做法見第 6、7 題。

## 常見陷阱

- **只在 debug build、模擬器或最新的旗艦手機上量測。** 這些數字和真實使用者的體驗差很多，要在 release 型態的實機上量，最好包含幾年前的中階機型。
- **把預熱（prewarm）造成的數字當成真實的啟動時間。** 從行程建立算起，會把使用者還沒點擊的時間也算進去。要用 MetricKit、Organizer，或自己從使用者看得到的時間點算起。
- **沒有看 trace 就說「是某某框架太慢」。** 啟動慢的原因要用 Instruments 或 Perfetto 看主執行緒的實際時間分佈，再下結論。
- **只把首畫面做得很快，卻讓資料很晚才出現。** 先顯示空白骨架畫面，使用者還是要等行情。要同時關注 TTFD，而不只是 TTID。
- **為了縮短啟動畫面的時間，把所有工作都丟到背景。** 首畫面需要的資料如果沒準備好，使用者會看到空白或轉圈，等待時間反而更長。要分清楚「首畫面必須的」和「可以晚一點的」。
- **`setKeepOnScreenCondition` 裡等網路請求。** 網路慢時，使用者會一直停在啟動畫面。條件只放本機資料。
- **不知道函式庫偷偷用 ContentProvider 初始化。** `Application.onCreate` 之前就花掉的時間，在自己的程式碼裡完全看不到，要用 Perfetto 的 trace 才找得到。

## 面試官想聽到的

1. 能說出啟動的分段與各段常見的瓶頸：iOS 是 pre-main（dyld、`+load`）、`didFinishLaunching`、首幀；Android 是建立行程、ContentProvider、`Application.onCreate`、Activity、首幀。
2. 會先量再改，而且知道用什麼工具：Instruments 的 App Launch、Xcode Organizer、MetricKit；Macrobenchmark、Perfetto、Android vitals。
3. 對策具體：延後非首畫面必須的 SDK 初始化、減少動態 framework、App Startup、Baseline Profile、先顯示快取再補即時資料。
4. 知道量測的陷阱：iOS 的預熱會讓行程建立時間失真，Android 要區分冷／溫／熱啟動與 TTID／TTFD。
5. 會從交易所的使用情境思考：使用者要看的是行情，所以應該先顯示快取的價格（標示時間），風控檢查和 WebSocket 連線不要擋在首幀之前。

> [!WARNING]
> 面試官常追問：「風控檢查（越獄、root 偵測、Play Integrity 驗證）要在首幀之前做完嗎？」不要只回答「要」或「不要」。比較好的回答是：這些檢查改成非同步，結果出來之前先限制敏感操作（例如下單、提領），首畫面的行情照常顯示。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 啟動種類 | cold launch／warm launch／resume | 冷啟動／溫啟動／熱啟動 |
| 首畫面的指標 | 首幀（Apple 建議約 400 毫秒內）；用 MetricKit、signpost 量 | TTID（系統自動記錄）、TTFD（自己呼叫 `reportFullyDrawn()`） |
| 常見瓶頸 | 動態 framework、`+load`、`didFinishLaunching` 裡的 SDK 初始化 | `Application.onCreate`、ContentProvider 自動初始化、沒有預先編譯的程式碼 |
| 管理初始化 | 手動延後到首畫面之後 | App Startup 的 `Initializer`、Hilt 的 `Lazy` |
| 預先最佳化 | 靜態連結、mergeable libraries | Baseline Profile |
| 量測工具 | Instruments 的 App Launch、Xcode Organizer、MetricKit | Macrobenchmark、Perfetto、Android vitals |
| 量測的陷阱 | iOS 15 之後的預熱 | 要區分冷／溫／熱啟動，以及 TTID／TTFD |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 冷啟動／溫啟動／熱啟動 | cold start／warm start／hot start | コールドスタート／ウォームスタート／ホットスタート |
| 首幀（第一個畫面畫出來） | first frame | 最初のフレーム |
| 動態連結器 | dyld（dynamic linker） | 動的リンカ |
| 靜態連結 | static linking | 静的リンク |
| 預熱 | prewarm | プリウォーム |
| 啟動畫面 | splash screen | スプラッシュ画面 |
| 預先編譯的啟動清單 | Baseline Profile | ベースラインプロファイル |
| 初次顯示時間／完整顯示時間 | TTID／TTFD | 初期表示までの時間／完全表示までの時間 |
