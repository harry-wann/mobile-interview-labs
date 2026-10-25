---
id: platform-threading
title: 執行緒與主執行緒：為什麼畫面會卡、ANR 是什麼、GCD 與 Dispatchers 怎麼用
summary: 兩個平台都規定「畫面只能在主執行緒更新」，所以主執行緒一旦被卡住，使用者就會感覺到當機。這個單元說明主執行緒在做什麼、每一格畫面有多少時間、ANR 與 iOS 的卡頓怎麼來、GCD 和 Dispatchers 怎麼選，以及行情這種高頻資料的執行緒怎麼設計。
priority: P0
day: 2
tags: [threading, runloop, gcd, looper, handler, anr, choreographer, dispatchers, frame-budget]
---

## 重點講解

先講一個貫穿整個單元的情境：使用者打開交易所 App 的行情頁，WebSocket 每秒推來幾百筆報價，這時他想按下「買入」按鈕。如果處理報價的工作佔住了主執行緒，按鈕按下去就沒有反應，畫面也會一格一格地卡。兩個平台的工具名稱不一樣，但道理完全相同。

這個單元回答六個面試常見問題：

1. 主執行緒到底在做什麼？為什麼不能被卡住？
2. 每一格畫面有多少時間？60 Hz 和 120 Hz 差在哪？
3. ANR 是什麼？iOS 的卡頓怎麼偵測？
4. iOS 的 GCD 有哪些常見的坑？
5. Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 `runBlocking`？
6. 行情每秒推幾百筆，執行緒要怎麼設計？

### 1. 主執行緒到底在做什麼？為什麼不能被卡住？

**主執行緒（main thread，Android 也叫 UI thread）負責處理使用者的觸控和更新畫面，而且一次只能做一件事。** 它手上的工作如果拖太久，後面排隊的觸控和繪製就都得等，使用者看到的就是卡住。

執行緒（thread）是一個 App 裡可以同時進行的一條工作流程。App 啟動時，系統會準備好主執行緒；其他執行緒由你自己決定要不要開。兩個平台都規定：**畫面只能在主執行緒更新**。在背景執行緒碰畫面，Android 會直接丟出 `CalledFromWrongThreadException`，iOS 則可能隨機 crash 或畫面異常（Xcode 的 Main Thread Checker 會在開發時幫你抓出來）。

主執行緒是怎麼運作的？它跑著一個「迴圈」：不斷等待事件進來，一個一個依序處理，處理完再等下一個。

| | iOS | Android |
|---|---|---|
| 這個迴圈叫什麼 | RunLoop | Looper（搭配 MessageQueue） |
| 排隊的東西 | 觸控、timer、`DispatchQueue.main` 排進來的工作、畫面更新 | MessageQueue 裡的訊息（Message），包含觸控、`Handler.post` 排進來的工作、畫面繪製 |
| 把工作丟進主執行緒 | `DispatchQueue.main.async { }` | `Handler(Looper.getMainLooper()).post { }` |

Android 的 `Handler` 是把工作排進某個 Looper 的 MessageQueue 的工具；你平常用的 `Dispatchers.Main`，底層就是一個綁定主執行緒 Looper 的 Handler。

用情境來看：使用者按下買入按鈕時，觸控事件被排進主執行緒的佇列。如果這時主執行緒正在處理一份 100 ms 的 JSON 解碼，按鈕的事件就得等這 100 ms 結束才會被處理，使用者感覺到的就是「按下去慢半拍」。解碼放在背景，結果再丟回主執行緒更新畫面，才是正確的做法：

```swift
// iOS：重工作在背景，只把更新畫面的部分丟回主執行緒
DispatchQueue.global(qos: .userInitiated).async {
    let book = decodeOrderBook(raw)        // 解碼、排序：在背景執行緒做
    DispatchQueue.main.async {             // 排進主執行緒的佇列，輪到時執行
        self.render(book)                  // 只有更新畫面在主執行緒
    }
}
```

```kotlin
// Android：協程版本（Handler 版本的寫法是 mainHandler.post { render(book) }）
lifecycleScope.launch {                                        // 預設在主執行緒開始
    val book = withContext(Dispatchers.Default) {              // 切到背景做 CPU 運算
        decodeOrderBook(raw)
    }
    render(book)                                               // 回到主執行緒更新畫面
}
```

> [!TIP]
> 面試時可以這樣說：「主執行緒跑著一個事件迴圈，iOS 叫 RunLoop，Android 是 Looper 加 MessageQueue，它一次只處理一件事，負責觸控和畫面更新。所以磁碟、網路、JSON 解碼這類耗時的工作一定要放背景，做完再切回主執行緒更新畫面。」

### 2. 每一格畫面有多少時間？60 Hz 和 120 Hz 差在哪？

**畫面每秒更新幾次，主執行緒每一格就有多少時間：時間預算＝1000 毫秒除以刷新率。** 60 Hz 約 16.7 ms，90 Hz 約 11.1 ms，120 Hz 約 8.3 ms。

Hz（赫茲）在這裡是「螢幕每秒更新幾次」。60 Hz 的螢幕每秒更新 60 次，每一格只有 1000 ÷ 60 ≈ 16.7 毫秒。主執行緒必須在這段時間內把這一格要顯示的內容準備好；超過了，這一格就趕不上螢幕的更新，使用者會看到畫面停頓。

| 螢幕刷新率 | 每一格的時間 |
|---|---|
| 60 Hz | 約 16.7 ms |
| 90 Hz | 約 11.1 ms |
| 120 Hz（iPhone 的 ProMotion、高階 Android 手機） | 約 8.3 ms |

重點是：**你的程式碼只能用到其中一部分。** 系統本身還要在同一段時間裡做版面計算（layout）和送出繪製指令，所以實際可以用的時間比表格上更少。同樣是一個 5 ms 的函式，在 60 Hz 的裝置上沒事，到 120 Hz 的裝置就可能讓畫面掉格。

掉格在兩個平台有不同的名稱：iOS 叫 **hitch**（畫面比預期更晚顯示），Android 常說 **jank**（卡頓的影格）。

兩個平台的運作方式也略有不同：

- **Android**：`Choreographer` 在每次 VSYNC（螢幕每次更新前發出的同步訊號）時，依序處理輸入、動畫、繪製，實際把畫面畫出來的是另一條 `RenderThread`。可以用 Perfetto、Jetpack 的 JankStats 看哪些影格太慢，`adb shell dumpsys gfxinfo <套件名稱> framestats` 可以取得每一格的時間。
- **iOS**：用 Instruments 的 Animation Hitches 看。如果在 ProMotion 機種上用 `CADisplayLink` 或 Core Animation 做自訂動畫，想跑到 120 Hz，要在 Info.plist 加上 `CADisableMinimumFrameDurationOnPhone`；一般的 UIKit、SwiftUI 動畫由系統處理，不需要這個設定。

可以在開發時自己量測每一格的間隔：

```swift
// iOS：CADisplayLink 在每一格螢幕更新時呼叫
final class FrameMonitor {
    private var link: CADisplayLink?
    private var last: CFTimeInterval = 0

    func start() {
        link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link?.add(to: .main, forMode: .common)
    }

    @objc private func tick(_ link: CADisplayLink) {
        let budget = link.targetTimestamp - link.timestamp        // 這一格該有的時間：120 Hz 約 0.0083 秒
        if last != 0, link.timestamp - last > budget * 1.5 {
            print("掉格：這一格花了 \((link.timestamp - last) * 1000) ms")
        }
        last = link.timestamp
    }
}
```

```kotlin
// Android：Choreographer 在每個 VSYNC 呼叫 doFrame（以下以 60 Hz 為例，超過約 20 ms 就算掉格）
Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
    private var last = 0L
    override fun doFrame(frameTimeNanos: Long) {
        if (last != 0L) {
            val ms = (frameTimeNanos - last) / 1_000_000.0
            if (ms > 20) Log.w("Jank", "掉格：這一格花了 $ms ms")
        }
        last = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)    // 再登記下一格
    }
})
```

> [!TIP]
> 面試時可以這樣說：「畫面的時間預算是一秒除以刷新率，60 Hz 是 16.7 毫秒，120 Hz 只有 8.3 毫秒，而且主執行緒還要留時間給版面計算和送出繪製。所以高頻行情我會在背景解碼和合併，再用固定頻率更新畫面，不會每一筆報價都碰主執行緒。」

### 3. ANR 是什麼？iOS 的卡頓怎麼偵測？

**ANR（Application Not Responding，應用程式沒有回應）是 Android 在主執行緒被卡太久時，跳出「App 沒有回應」對話框的機制。** 條件有兩個：輸入事件（觸控、按鍵）超過 5 秒沒處理完，或前景的 BroadcastReceiver（接收系統廣播的元件）超過 10 秒沒執行完。ANR 會被計入 Play Console 的 Android vitals，過高還會影響 App 在 Google Play 的曝光。

ANR 最常見的原因，全都是「主執行緒做了不該做的事」：

- 在主執行緒讀寫磁碟或呼叫網路（也就是 I/O，input/output，輸入輸出）。
- 同步呼叫 Binder（Android 行程之間溝通的機制），而對方很慢。
- 鎖競爭或死鎖（兩條執行緒互相等對方放開鎖，誰也動不了）。
- 在主執行緒用 `runBlocking` 等協程完成。
- 在 `BroadcastReceiver.onReceive` 裡做重工作。

**iOS 沒有「ANR 對話框」，但有兩個對應的概念：**

- **hang**：主執行緒對使用者的操作沒有反應，超過約 250 ms 就算一次 hang。使用者的感覺是「按下去沒反應」。
- **watchdog 終止**：在 App 啟動、回到前景、進入背景這類關鍵時刻，如果主執行緒花太久，系統會直接結束 App，crash log 上的錯誤碼是 `0x8badf00d`。

兩個平台偵測與預防的工具：

| 用途 | iOS | Android |
|---|---|---|
| 開發時抓違規 | Main Thread Checker：偵測在背景執行緒碰 UIKit | StrictMode：偵測主執行緒的磁碟與網路存取 |
| 找出卡在哪裡 | Instruments 的 Time Profiler、Hangs、Animation Hitches | Perfetto／System Trace、ANR 的 trace 檔 |
| 上線後的統計 | MetricKit、Xcode Organizer 的 hang 統計 | Android vitals、JankStats |
| 自動化回歸 | XCTest 的效能指標（metrics） | Macrobenchmark |

Android 開發時建議打開 StrictMode，主執行緒一做磁碟或網路就會在 Logcat 留下警告：

```kotlin
class TradeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()      // 主執行緒讀檔
                    .detectDiskWrites()     // 主執行緒寫檔
                    .detectNetwork()        // 主執行緒打網路
                    .penaltyLog()           // 違規時寫進 Logcat
                    .build()
            )
        }
    }
}
```

iOS 的 Main Thread Checker 在 Xcode 執行時預設就是開的，違規時會在 console 印出像這樣的訊息：

```text
Main Thread Checker: UI API called on a background thread: -[UIView setBackgroundColor:]
```

> [!TIP]
> 面試時可以這樣說：「ANR 是 Android 主執行緒超過 5 秒沒處理輸入事件，常見原因是主執行緒做 I/O 或 runBlocking。iOS 對應的是 hang，主執行緒超過 250 毫秒沒有回應，嚴重時啟動或回前景太久會被 watchdog 終止。開發時我會開 StrictMode 和 Main Thread Checker，上線後看 Android vitals 和 MetricKit。」

### 4. iOS 的 GCD 有哪些常見的坑？

**GCD（Grand Central Dispatch）是 Apple 提供的「把工作丟進佇列，由系統分配執行緒」的函式庫。** 它最常見的問題有四個：在同一個佇列上 `sync` 造成死鎖、大量阻塞任務造成執行緒暴增、不清楚 barrier 的順序、以及優先權（QoS）亂設。

先分清楚兩組概念：

- **serial queue（序列佇列）**：一次只執行一個任務，照加入的順序，結果可預期。**concurrent queue（並行佇列）**：可以同時執行多個任務。主佇列 `DispatchQueue.main` 是一個 serial queue。
- **`async`**：把任務丟進去就繼續往下走，不等結果。**`sync`**：丟進去之後，目前的執行緒會停下來，等到任務做完才繼續。

**坑一：死鎖。** 在一個 serial queue 的任務裡，對**同一個佇列**呼叫 `sync`，等於「我等自己做完」，永遠等不到。主執行緒呼叫 `DispatchQueue.main.sync { }` 是最經典的例子：

```swift
let queue = DispatchQueue(label: "serial")
queue.async {
    queue.sync {            // 目前的任務正在這個佇列上，sync 要等佇列有空，但佇列要等目前的任務做完
        print("永遠到不了")   // 死鎖（新版系統常會直接偵測到並 crash）
    }
}
```

**坑二：thread explosion（執行緒暴增）。** 往 concurrent queue 丟大量「會阻塞」的任務（例如每個任務都在等網路或鎖），任務卡住不會釋放執行緒，系統為了讓其他任務有機會跑，只好一直開新的執行緒，最後資源耗盡。解法：用 serial queue 隔離、`OperationQueue` 的 `maxConcurrentOperationCount` 限制數量、`DispatchSemaphore` 限流，或改用 async/await。

**坑三：barrier 的順序。** 在 concurrent queue 加上 `.barrier` 旗標的任務，會等前面提交的任務全部做完才執行，而且後面提交的任務要等它做完才開始。常用來做讀寫鎖：讀取可以同時進行，寫入用 barrier 獨佔。下面這段程式會印出什麼？

```swift
import Foundation

let q = DispatchQueue(label: "q", attributes: .concurrent)
q.async { print("1") }
q.async(flags: .barrier) { print("2") }   // 等 1 做完才執行，3 要等它做完
q.async { print("3") }
q.sync(flags: .barrier) {}                // 等前面全部做完才返回

let s = DispatchQueue(label: "s")         // serial queue
s.async { print("A") }
s.sync { print("B") }                     // 排在 A 後面；sync 會讓目前的執行緒等它做完
print("C")
// 輸出：1 2 3 A B C
```

**坑四：QoS（Quality of Service，服務品質，決定系統給任務多高的優先權）。** 由高到低是 `userInteractive`、`userInitiated`、`default`、`utility`、`background`。`userInteractive` 是給「直接影響畫面動畫」的短任務，不要拿來做長時間運算，會和主執行緒搶 CPU；一般的長時間運算用 `utility` 或 `userInitiated`。另外，高優先權的任務如果在等低優先權的任務持有的鎖，系統會暫時提升後者的優先權，所以不要在高優先權的佇列上等低優先權的佇列。

Swift concurrency（async/await）有自己的一組執行緒，數量大約等於 CPU 核心數，叫 cooperative thread pool。**在 async 函式裡用 `DispatchSemaphore.wait()` 這類會卡住執行緒的呼叫是危險的**：池子裡的執行緒不多，被卡住就沒有人能繼續跑，嚴重時整個非同步系統停擺，而且系統不會幫你補執行緒。

> [!TIP]
> 面試時可以這樣說：「GCD 最常見的坑是在 serial queue 上對自己 sync 造成死鎖，包括在主執行緒呼叫 main.sync；還有往 concurrent queue 丟大量阻塞任務造成 thread explosion。長時間運算我會用 utility 或 userInitiated 的 QoS，不用 userInteractive。在 async 函式裡我不會用 semaphore 阻塞執行緒。」

### 5. Android 的 Dispatchers 怎麼選？為什麼不能在主執行緒 `runBlocking`？

**Kotlin 協程用 Dispatcher 決定程式碼在哪條執行緒上跑：畫面更新用 `Main`，CPU 運算用 `Default`，會等待的 I/O（磁碟、資料庫、網路）用 `IO`。**

| Dispatcher | 用途 | 重點 |
|---|---|---|
| `Dispatchers.Main` | 更新畫面 | 一律把工作排進主執行緒的佇列，下一個輪到時才執行 |
| `Dispatchers.Main.immediate` | 更新畫面 | 如果目前已經在主執行緒，就直接執行，不排隊；否則才排進佇列 |
| `Dispatchers.Default` | CPU 密集的運算：解碼、排序、計算 | 執行緒數量約等於 CPU 核心數（至少 2 條） |
| `Dispatchers.IO` | 會阻塞等待的 I/O：資料庫、檔案、網路 | 可以開更多執行緒，預設上限 64 條；與 `Default` **共用同一個底層執行緒池** |

`limitedParallelism(n)` 可以從某個 Dispatcher 切出一個「最多同時用 n 條執行緒」的版本，例如 `Dispatchers.IO.limitedParallelism(1)` 讓資料庫操作排隊一個一個做，不會同時執行。

為什麼 CPU 運算和 I/O 要分開？`Default` 的執行緒只有核心數那麼多，目的是讓所有 CPU 運算剛好吃滿 CPU。如果你在 `Default` 上做阻塞 I/O（等資料庫回應），那條執行緒被佔住什麼都不能做，其他運算就得排隊。`IO` 的執行緒上限比較高，才適合讓很多任務同時在「等」。

```kotlin
suspend fun parse(raw: ByteArray): Depth =
    withContext(Dispatchers.Default) { decode(raw) }        // 解碼是 CPU 運算：Default

suspend fun save(order: Order) =
    withContext(Dispatchers.IO) { dao.insert(order) }       // 寫資料庫會等待磁碟：IO
```

**`runBlocking` 是什麼？** 它會讓「呼叫它的那條執行緒」停下來，一直等到裡面的協程跑完，是把 suspend 函式變成普通呼叫的橋梁。問題在於「停下來」的是誰：

| 在哪裡呼叫 `runBlocking` | 後果 |
|---|---|
| 主執行緒（`onCreate`、Compose 的 composable） | 畫面卡住，網路慢時等超過 5 秒就 ANR |
| `Dispatchers.Default` 的協程裡 | 佔住池子裡有限的執行緒，拖慢其他運算 |
| OkHttp 的 interceptor | 這條執行緒本來就在背景、被設計成可以阻塞，所以不會 ANR；但仍會佔住一條 OkHttp 的執行緒，和 `synchronized` 一起用還可能死鎖 |
| JVM 單元測試、`main()` | 沒有畫面，可以接受 |

> [!TIP]
> 面試時可以這樣說：「解碼這類 CPU 運算用 Dispatchers.Default，資料庫和檔案這類會等待的 I/O 用 Dispatchers.IO，兩者共用同一個底層執行緒池，IO 的上限預設是 64。runBlocking 會卡住呼叫它的執行緒，在主執行緒呼叫就會造成 ANR，在 Default 的協程裡呼叫會佔住有限的執行緒。」

### 6. 行情每秒推幾百筆，執行緒要怎麼設計？

**原則是：解碼和合併都在背景做，每一檔幣種只保留最新的一筆，再用固定頻率（約每 100 到 250 毫秒）把整份資料交給主執行緒更新畫面。** 不要每收到一筆報價就碰一次主執行緒。

為什麼？假設行情頁同時看 300 檔幣種，每檔每秒更新 3 次，每秒就有約 900 筆。如果每一筆都排進主執行緒，主執行緒的佇列會被塞滿，觸控事件排在後面，使用者感覺到的就是畫面卡、按鈕慢。而且人眼看不出差別：一檔價格 100 毫秒內跳了兩次，使用者只會看到最後那一次，中間被覆蓋掉的價格不需要顯示。

設計分三段：

1. **接收與解碼（背景）**：WebSocket 收到的是 gzip 壓縮的二進位資料，解壓縮和 JSON 解碼都放背景執行緒。
2. **合併（背景）**：用一個字典，以幣種代號為 key，每次收到新報價就覆蓋舊的，只保留最新值。這種「只留最新、丟掉中間值」的做法，Kotlin 的 Flow 叫 `conflate`。
3. **固定頻率交給畫面（主執行緒）**：每 100 到 250 毫秒取一次字典的內容（快照，也就是某一刻的完整資料），更新畫面一次。

```swift
// iOS：actor 保證字典一次只被一條執行緒存取；ViewModel 在主執行緒，每 200 ms 取一次
struct Ticker { let symbol: String; let price: Decimal }

actor TickerStore {
    private var latest: [String: Ticker] = [:]              // 每檔只保留最新一筆
    func update(_ t: Ticker) { latest[t.symbol] = t }       // 解碼後在背景呼叫
    func snapshot() -> [Ticker] { latest.values.sorted { $0.symbol < $1.symbol } }
}

@MainActor
final class TickerListViewModel: ObservableObject {
    @Published private(set) var rows: [Ticker] = []
    private let store = TickerStore()

    func startRefreshing() {
        Task {                                                    // 繼承 @MainActor，在主執行緒執行
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 200_000_000)   // 等 200 ms
                rows = await store.snapshot()                     // 拿到整份快照，只更新畫面一次
            }
        }
    }
}
```

```kotlin
// Android：背景合併，每 200 ms 發出一份快照
class TickerViewModel(feed: Flow<Ticker>) : ViewModel() {
    private val latest = ConcurrentHashMap<String, Ticker>()    // 每檔只保留最新一筆

    init {
        feed.onEach { latest[it.symbol] = it }                  // 只更新字典，不碰畫面
            .flowOn(Dispatchers.Default)                        // 解碼和合併在背景
            .launchIn(viewModelScope)
    }

    val rows: StateFlow<List<Ticker>> = flow {
        while (true) {
            delay(200)                                          // 固定頻率
            emit(latest.values.sortedBy { it.symbol })
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

另外三個和執行緒有關的原則：

- **Keychain、Keystore、資料庫、檔案存取都可能意外地慢**（第一次存取、裝置忙碌，或需要生物辨識時還會等使用者操作），不要放在主執行緒。
- **下單按鈕要立即回饋**：按下去先鎖住按鈕、顯示 loading，再開始非同步工作，避免使用者以為沒按到而重複送出。
- **用 annotation 標明執行緒的假設**：Android 可以用 `@MainThread`、`@WorkerThread`；Swift 用 `@MainActor`，讓編譯器幫你檢查。

> [!TIP]
> 面試時可以這樣說：「行情我會在背景解碼，用字典每檔只保留最新一筆，再用固定頻率，大約每 100 到 250 毫秒，把快照交給主執行緒更新一次。這樣主執行緒不會被每一筆報價淹沒，使用者看到的也永遠是最新價格。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R；Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶。App 首頁進入 **Platform02Threading**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform02-Threading/`，解答在 `labs/ios/Solution/Platform/Platform02-Threading/`；Android 你寫在 `labs/android/practice/platform/platform02-threading/`，解答在 `labs/android/solution/platform/platform02-threading/`（Practice／Solution 同名，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **兩個平台的題目不一定相同**：第 4 題是 iOS 的 GCD，第 5 題是 Android 的 Dispatchers，各自只有一個平台的演練；其他題兩個平台各做一個。
> **量時間的演練**：用忙碌迴圈代替真的運算（`decodeOrderBook` 固定花約 80 ms），所以 Debug 與 Release 的結果一樣，數字會有幾 ms 的誤差。Android 的第 1、3、8 題要用 Android 的主執行緒，要在手機（模擬器）上執行。

### 演練 1：按下買入之後，要等一下才有反應（第 1 題）

**檔案**：iOS `L01_MainThreadBlock.swift`、Android `L01_MainThreadBlock.kt`

**情境**：行情頁收到一份訂單簿資料，解碼約 80 ms。收到資料之後 10 ms，使用者按下「買入」。按鈕的事件要排進主執行緒的佇列，等主執行緒有空才會處理。演練從別的執行緒把「按下買入」排進主執行緒，記下排進去的時間，等主執行緒處理它的時候再量，就是「事件在佇列裡等了多久」。

**怎麼寫**

1. **Bug 版**：iOS 在主執行緒直接呼叫 `decodeOrderBook(raw)`，再呼叫 `render`；Android 在 `scope.launch { }`（預設就在主執行緒）裡直接解碼。
2. **修正版**：iOS 用 `Task.detached` 在背景解碼，再用 `await MainActor.run` 回主執行緒更新畫面；Android 用 `withContext(Dispatchers.Default)` 解碼，再回主執行緒呼叫 `render(book)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 按下買入的事件，在佇列裡等了約 60 到 80 ms 才被處理 |
| 修正版 | 等了不到 5 ms，幾乎立刻被處理 |

**為什麼**：主執行緒一次只能做一件事，解碼佔住它 80 ms，按鈕的事件只能排在後面。使用者感覺到的就是「按下去慢半拍」。解碼放背景，主執行緒只剩更新畫面那一點點工作，按鈕的事件就不用排隊。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L01_MainThreadBlock.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L01_MainThreadBlock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L01_MainThreadBlock.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L01_MainThreadBlock.kt#fixed
```

### 演練 2：120 Hz 的手機上，掉格沒被偵測到（第 2 題）

**檔案**：iOS `L02_FrameBudget.swift`、Android `L02_FrameBudget.kt`

**情境**：App 裡有一個卡頓偵測：量每一格花了多少 ms，超過「時間預算」的 1.5 倍就算掉格。演練用兩份錄好的資料：60 Hz 的手機實際掉了 2 次，120 Hz 的手機實際掉了 3 次。

**怎麼寫**

1. **Bug 版**：時間預算寫死 16.7 ms（只想到 60 Hz）。
2. **修正版**：時間預算 = 1000 ÷ 刷新率（iOS 正式寫法：`UIScreen` 的 `maximumFramesPerSecond` 或 `CADisplayLink` 的 `targetTimestamp - timestamp`；Android：`display.refreshRate`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 60 Hz 的手機偵測到 2 次（正確）；120 Hz 的手機只偵測到 1 次，漏了 2 次 |
| 修正版 | 60 Hz 偵測到 2 次、120 Hz 偵測到 3 次，都和實際相同 |

**為什麼**：120 Hz 的手機一格只有 8.3 ms，掉一格就是 16.7 ms。用 60 Hz 的預算算，門檻是 25 ms，這種掉格就被當成正常。同樣一個 5 ms 的函式，在 60 Hz 的手機上沒事，到 120 Hz 的手機就可能讓畫面掉格，所以預算一定要依這支手機的刷新率算。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L02_FrameBudget.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L02_FrameBudget.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L02_FrameBudget.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L02_FrameBudget.kt#fixed
```

### 演練 3：主執行緒卡住，開發時沒有人發現（第 3 題）

**檔案**：iOS `L03_HangWatchdog.swift`、Android `L03_StrictMode.kt`

**情境**：iOS：資產頁的「重新整理」要呼叫伺服器（演練用 0.4 秒代替網路時間）。App 裡有一個 `HangWatchdog`：背景執行緒每 20 ms 叫主執行緒回一聲，超過 250 ms 沒回就記一次 hang。Android：有一個函式要讀設定檔，開發時檔案小感覺不到卡；演練開著 StrictMode 的磁碟讀取偵測，看主執行緒讀檔會不會被抓到（需要在手機上執行，Android 9 以上）。

**怎麼寫**

1. **Bug 版**：iOS 在按鈕的處理函式裡用 `Thread.sleep(forTimeInterval: 0.4)` 同步等待；Android 在主執行緒直接呼叫 `readConfig()` 讀檔。
2. **修正版**：iOS 改成 `Task` 加 `try? await Task.sleep(for: .milliseconds(400))`，等待時主執行緒是空的；Android 用 `withContext(Dispatchers.IO)` 把讀檔放到背景。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：資產載入花了約 400 ms，watchdog 記到 1 次 hang，最長約 400 ms。Android：StrictMode 抓到 1 次以上的主執行緒磁碟讀取（模擬器 API 29 實測 4 次） |
| 修正版 | iOS：載入一樣花約 400 ms，但 watchdog 記到 0 次 hang。Android：StrictMode 抓到 0 次 |

**為什麼**：iOS 的 hang 指主執行緒對操作沒有反應超過約 250 ms；Android 的 ANR 是輸入事件超過 5 秒沒處理。兩個平台的原因都是「主執行緒做了不該做的事」，同步等待網路、讀寫磁碟最常見。工具上，iOS 在開發時用 Main Thread Checker、Instruments 的 Hangs，上線後看 MetricKit；Android 開發時開 StrictMode，上線後看 Android vitals。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L03_HangWatchdog.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L03_HangWatchdog.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L03_StrictMode.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L03_StrictMode.kt#fixed
```

### 演練 4：按下更新餘額，App 直接當掉（第 4 題，只有 iOS）

**檔案**：iOS `L04_MainSyncDeadlock.swift`

**情境**：有一個共用函式 `updateBalance(_:_:)`，為了「保證在主執行緒更新畫面」，所有地方都用它。有時從背景執行緒呼叫（收到推播），有時從主執行緒呼叫（按下按鈕）。

**怎麼寫**

1. **Bug 版**：函式裡用 `DispatchQueue.main.sync { }` 切到主執行緒更新 `label.text`。從 Xcode 執行（⌘R），才看得到當掉的位置。
2. **修正版**：先判斷 `Thread.isMainThread`：是就直接更新；不是才用 `DispatchQueue.main.async { }` 排進主執行緒。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 從背景呼叫沒問題；接著從主執行緒呼叫，App 當掉，主控台：`BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread` |
| 修正版 | 背景更新後餘額 BTC 0.5，主執行緒更新後餘額 BTC 0.8 |

**為什麼**：`sync` 會讓目前的執行緒停下來，等任務做完。在主執行緒對主佇列 `sync`，等於主執行緒等自己：任務要等主執行緒有空才能執行，主執行緒要等任務做完才有空。新版系統會直接偵測到這種情況並讓 App 當掉。從背景呼叫沒事，因為主執行緒是空的，可以處理排進去的工作。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L04_MainSyncDeadlock.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L04_MainSyncDeadlock.swift#fixed
```

### 演練 5：同時丟 60 個等待的任務，執行緒暴增（第 4 題，只有 iOS）

**檔案**：iOS `L05_ThreadExplosion.swift`

**情境**：App 啟動時要預先下載 60 檔幣種的圖示，每個下載都會等網路（用 `Thread.sleep(forTimeInterval: 0.15)` 代替）。演練記下這 60 個任務一共用了幾條不同的執行緒。

**怎麼寫**

1. **Bug 版**：建立一個 concurrent queue，用迴圈 60 次 `queue.async(group: group) { recorder.download() }`，一次全部丟進去。
2. **修正版**：改用 `OperationQueue`，`maxConcurrentOperationCount` 設成 4，用迴圈 60 次 `addOperation`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 完成 60 個下載，用了幾十條不同的執行緒（在模擬器實測 60 條，每次不同），全部完成約 0.2 秒 |
| 修正版 | 用了不到 10 條不同的執行緒（實測 5 到 9 條），全部完成約 2.4 秒（60 ÷ 4 × 0.15 秒） |

**為什麼**：任務卡在等待時不會釋放執行緒，GCD 為了讓其他任務有機會跑，只好一直開新的。修正版刻意用比較久的時間換比較少的執行緒：同時最多 4 個，其他排隊。實際的執行緒數量會依機器不同（模擬器跑在 Mac 上，數字和真的 iPhone 不一樣），重點是「有沒有上限」。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L05_ThreadExplosion.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L05_ThreadExplosion.swift#fixed
```

### 演練 6：資料庫在等，簡單的運算卻等了好久（第 5 題，只有 Android）

**檔案**：Android `L06_DefaultStarvation.kt`

**情境**：App 同時送出好幾個資料庫查詢（數量等於 CPU 核心數，每個等 300 ms，用 `Thread.sleep` 代替「等磁碟」，執行緒被佔住什麼都不能做）。查詢開始 50 ms 之後，來了一個很快的 CPU 運算，丟進 `Dispatchers.Default`，看它等了多久才開始。

**怎麼寫**

1. **Bug 版**：用 `repeat(count)` 開協程，每個在 `Dispatchers.Default` 上呼叫 `query()`（阻塞的 I/O 放在 Default）。
2. **修正版**：同樣的寫法，改成 `Dispatchers.IO`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | CPU 運算等了約 250 ms 才開始（Default 的執行緒全被查詢佔住，要等第一個查詢做完） |
| 修正版 | CPU 運算等了不到 50 ms，幾乎立刻開始 |

**為什麼**：`Default` 的執行緒數量只有 CPU 核心數那麼多（至少 2 條），目的是讓所有 CPU 運算剛好吃滿 CPU。在 `Default` 上做阻塞 I/O，那條執行緒被佔住什麼都不能做，其他運算就得排隊。`IO` 的執行緒上限比較高（預設 64 條），適合讓很多任務同時在「等」。

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L06_DefaultStarvation.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L06_DefaultStarvation.kt#fixed
```

### 演練 7：訂單狀態亂了順序，最後留下 New（第 5 題，只有 Android）

**檔案**：Android `L07_IoOrdering.kt`

**情境**：同一張訂單的狀態依序更新三次：New → PartiallyFilled → Filled。三次寫入都交給資料庫，但每次花的時間不一樣（用 `Thread.sleep` 代替：60 ms、30 ms、5 ms）。看資料庫最後留下哪個狀態。

**怎麼寫**

1. **Bug 版**：`coroutineScope { }` 裡用 `launch(Dispatchers.IO)` 開三個協程，依序寫三個狀態。
2. **修正版**：先用 `Dispatchers.IO.limitedParallelism(1)` 做出「最多同時 1 條執行緒」的 `dbDispatcher`，三次寫入都 `launch(dbDispatcher)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 資料庫最後的狀態是 New |
| 修正版 | 資料庫最後的狀態是 Filled |

**為什麼**：`IO` 可以同時用很多條執行緒，三次寫入同時進行，誰先做完誰先寫進去，最慢的第一次最後才寫完，蓋掉了後面的狀態。`limitedParallelism(1)` 讓寫入排隊，一個一個照送出的順序做。需要保證順序的資料庫寫入，都要這樣處理。

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L07_IoOrdering.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L07_IoOrdering.kt#fixed
```

### 演練 8：每一筆報價都更新畫面，主執行緒被淹沒（第 6 題）

**檔案**：iOS `L08_TickFlood.swift`、Android `L08_TickFlood.kt`

**情境**：行情頁收到 WebSocket 推來的報價。演練在背景送出 300 筆報價（6 檔幣種輪流，每 2 ms 一筆），看畫面被更新了幾次，以及最後畫面上的價格對不對。

**怎麼寫**

1. **Bug 版**：每收到一筆報價就排進主執行緒更新一次畫面：iOS 用 `DispatchQueue.main.async`，Android 用 `uiScope.launch`（`uiScope` 是 `Dispatchers.Main` 的 scope）。
2. **修正版**：背景先合併，每檔只留最新一筆，再用固定頻率（每 200 ms）把整份快照交給主執行緒，只更新畫面一次。iOS 用 `AsyncStream` 依序把報價放進 `actor TickerStore` 的字典，主執行緒每 200 ms 呼叫 `await store.snapshot()`；Android 放進 `ConcurrentHashMap`，用 `flow { delay(200); emit(latest.toMap()) }.flowOn(Dispatchers.Default)` 取快照。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 收到 300 筆報價，畫面更新了 300 次；最後價格正確 |
| 修正版 | 畫面只更新約 3 到 5 次（每 200 ms 一次）；最後畫面上的價格和每檔最後一筆一致 |

**為什麼**：一檔價格 100 ms 內跳了兩次，使用者只會看到最後那一次，中間被覆蓋的價格不用顯示。每一筆都排進主執行緒，佇列被塞滿，觸控排在後面，使用者感覺到的就是畫面卡、按鈕慢。要注意：這份合併只是給畫面顯示用；K 線的最高價、最低價要在合併之前的資料層累計，或由伺服器提供。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L08_TickFlood.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L08_TickFlood.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L08_TickFlood.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L08_TickFlood.kt#fixed
```

### 演練 9：連按兩次買入，下了兩張單（第 6 題）

**檔案**：iOS `L09_DoubleTap.swift`、Android `L09_DoubleTap.kt`

**情境**：按下「買入」之後，要先向伺服器取最新報價（約 100 ms），再送出委託。使用者看沒有反應，30 ms 之後又按了一次。

**怎麼寫**

1. **Bug 版**：函式開頭檢查 `isSubmitting`，但取到報價（`await exchange.fetchQuote()`，Android 是 `exchange.fetchQuote()`）之後才把它設成 `true`。
2. **修正版**：檢查通過之後，在任何 `await`（Android：任何 suspend 函式、`launch`）之前，先同步把 `isSubmitting` 設成 `true`，做完再放開。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 連按兩次買入，伺服器收到 2 張委託 |
| 修正版 | 連按兩次買入，伺服器收到 1 張委託 |

**為什麼**：`await`／suspend 函式一等待，函式就先離開了，使用者可以再按一次。旗標要在「第一個會等待的地方」之前就設好，按鈕才會馬上鎖住、顯示 loading。這是「下單按鈕要立即回饋」最基本的做法；再加上送單時帶 `clientOrderId`，伺服器端也能擋掉重複的單。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform02-Threading/L09_DoubleTap.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform02-Threading/L09_DoubleTap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform02-threading/L09_DoubleTap.kt#bug
```

```lab-solution
android/solution/platform/platform02-threading/L09_DoubleTap.kt#fixed
```

## 常見陷阱

- **在 serial queue 的任務裡，對同一個佇列呼叫 `sync`。** 等於自己等自己，死鎖。在主執行緒呼叫 `DispatchQueue.main.sync` 也是同樣的問題。
- **在 async 函式裡用 `DispatchSemaphore.wait()` 等非同步結果。** cooperative thread pool 的執行緒很少，被卡住就沒有人能繼續跑，系統也不會補執行緒。
- **在 Android 主執行緒呼叫 `runBlocking`。** 畫面卡住，網路慢就 ANR。在 `Dispatchers.Default` 的協程裡呼叫，也會佔住池子裡有限的執行緒。
- **每一筆報價都 `withContext(Dispatchers.Main)` 或 `DispatchQueue.main.async`。** 主執行緒的佇列被淹沒，觸控排在後面。應該在背景合併，再用固定頻率更新畫面。
- **在主執行緒讀寫資料庫。** 資料量大的時候會造成 ANR 或 hang，Realm 等資料庫雖然可以設定允許在主執行緒存取，但交易所的 App 不該依賴它。
- **以為 `Dispatchers.IO` 可以無限開執行緒。** 預設上限是 64 條，超過的任務要排隊。
- **在背景執行緒更新畫面。** iOS 會隨機 crash 或畫面異常，Android 直接丟出 `CalledFromWrongThreadException`。
- **在 `userInteractive` 的 QoS 做長時間運算。** 會和主執行緒搶 CPU。

## 面試官想聽到的

1. 說得出畫面的時間預算（60 Hz 約 16.7 ms、120 Hz 約 8.3 ms），並說明為什麼主執行緒要保持空閒。
2. 能清楚講出 ANR 與 hang 的定義、常見原因，以及偵測與預防的工具。
3. 清楚 GCD 的 `sync` 死鎖與 thread explosion，以及 `Dispatchers.Default` 和 `IO` 的分工。
4. 行情高頻更新的執行緒設計：背景解碼、每檔保留最新值、固定頻率才交給主執行緒。
5. 知道不要在 async／協程的世界裡阻塞執行緒（semaphore、`runBlocking`）。

> [!WARNING]
> 面試官常追問：「如果收到的行情是每秒幾千筆，但畫面每 200 毫秒才更新一次，中間的最高價、最低價會不會漏掉？」不要只回答「不會」。要說明這份合併是給畫面顯示用的；K 線的最高價、最低價要在合併前的資料層累計，或由伺服器提供，不能只靠畫面看到的快照。

> [!FIELD]
> 在一個社交 App 裡，本機資料庫 Realm 的物件只能在建立它的執行緒使用，所以 iOS 把 Realm 的存取包在 actor 裡，Android 則用單一執行緒的 dispatcher 包住，對外只傳複製出來的資料，不讓畫面直接碰到 Realm 物件。Android 用 Hilt 提供 `@IoDispatcher`、`@MainDispatcher`、`@DefaultDispatcher` 三個 Dispatcher，測試時可以替換。
> 在一個即時行情 App 裡，iOS 的 SSE client 標成 `@MainActor`，串流在 `Task` 裡讀取；Android 在 IO 上用 kotlinx-serialization 解碼，再用 `withContext(Dispatchers.Main)` 把結果交給畫面。
> 換成交易所 App 的行情頁，我會在這個基礎上加上「背景合併、固定頻率更新畫面」。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 主執行緒的事件迴圈 | RunLoop | Looper＋MessageQueue，用 Handler 投遞；Choreographer 跟隨 VSYNC 處理輸入、動畫、繪製 |
| 背景工作 | GCD（`DispatchQueue`）、`OperationQueue`、Swift concurrency | `ExecutorService`、`HandlerThread`、Kotlin 協程（Dispatchers） |
| 優先權 | QoS：`userInteractive`／`userInitiated`／`default`／`utility`／`background` | Dispatchers：`Default` 給 CPU 運算、`IO` 給阻塞 I/O |
| 主執行緒卡住 | hang（超過約 250 ms）；啟動或轉場太久被 watchdog 結束（`0x8badf00d`） | ANR：輸入事件 5 秒、前景 BroadcastReceiver 10 秒 |
| 掉格 | hitch；Instruments 的 Animation Hitches | jank；Perfetto、JankStats |
| 開發時抓違規 | Main Thread Checker、Thread Sanitizer | StrictMode |
| 上線後監控 | MetricKit、Xcode Organizer | Android vitals |
| 標明執行緒 | `@MainActor` | `@MainThread`、`@WorkerThread` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 主執行緒 | main thread | メインスレッド |
| 事件迴圈 | RunLoop／Looper | ランループ／ルーパー |
| 應用程式沒有回應 | ANR（Application Not Responding） | ANR（アプリケーションが応答しない） |
| 掉格 | hitch／jank | ヒッチ／ジャンク |
| 死鎖 | deadlock | デッドロック |
| 序列佇列／並行佇列 | serial queue／concurrent queue | シリアルキュー／コンカレントキュー |
| 服務品質（優先權） | QoS（Quality of Service） | QoS（サービス品質） |
