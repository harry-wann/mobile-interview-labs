---
id: advanced-concurrency-races
title: 多執行緒的問題：data race、鎖、deadlock、priority inversion，以及 Swift 6 與 Kotlin 的做法
summary: 行情資料從 WebSocket 執行緒寫進來、畫面在主執行緒讀出去，最容易出現看不見的並發錯誤。這個單元用具體情境說明 data race 與 race condition 的差別、iOS 和 Android 各種鎖怎麼選、deadlock 與 priority inversion 怎麼發生，以及 Thread Sanitizer、Swift 6 編譯時檢查怎麼幫你抓問題。
priority: P0
day: 3
tags: [concurrency, data-race, race-condition, deadlock, priority-inversion, lock, actor, tsan]
---

## 重點講解

這個單元回答六個面試常見問題：

1. data race 和 race condition 差在哪？
2. iOS 有哪些鎖和同步工具？怎麼選？
3. Kotlin 的 `@Volatile`、`Atomic`、`synchronized`、`Mutex` 各自解決什麼問題？
4. deadlock 是什麼？怎麼避免、怎麼診斷？
5. priority inversion 是什麼？
6. 怎麼偵測並發問題？Thread Sanitizer 與 Swift 6 的編譯時檢查能抓什麼？

整個單元用同一個情境：**交易所 App 的最新價格表。WebSocket 在背景執行緒不斷寫入各交易對的最新價格，畫面在主執行緒讀取來顯示。**

### 1. data race 和 race condition 差在哪？

兩個名詞很像，但是不同的問題，工具能抓的也不一樣：

| | data race | race condition |
|---|---|---|
| 意思 | 多個執行緒同時存取同一塊記憶體，至少有一個在寫入，而且**沒有任何同步機制**（鎖等） | 結果取決於操作發生的先後順序，**即使每次存取都有同步，還是可能出錯** |
| 例子 | 兩個執行緒對同一個沒有保護的 `Int` 同時做 `count += 1` | 先檢查再動作（check-then-act）；actor 或協程在 `await` 前後，狀態已經被別人改掉 |
| 能被工具偵測嗎 | 可以：Thread Sanitizer（執行時偵測 data race 的工具）、Swift 6 的編譯時檢查，兩者都在第 6 題說明 | 通常不行，要靠設計與測試 |
| 怎麼處理 | 加同步（鎖、atomic、actor、把資料限制在單一執行緒） | 讓「檢查」與「動作」變成一個不可分割的單位 |

**data race 的例子。** `count += 1` 看起來是一步，其實是三步：讀取舊值、加 1、寫回去。兩個執行緒同時執行時，可能都讀到 `5`，各自寫回 `6`，少算了一次（lost update）。

```swift
import Foundation

var count = 0
DispatchQueue.concurrentPerform(iterations: 1000) { _ in
    // 1000 個工作分給多個執行緒同時執行，沒有任何保護
    count += 1
}
print(count)
// 輸出：不固定。可能是 1000，也可能比 1000 小，例如 898
```

**race condition 的例子：先檢查再動作。** 每一步單獨看都有同步，組合起來卻不安全：

```kotlin
// ConcurrentHashMap 的每個方法單獨都是執行緒安全的
// 檢查：兩個執行緒都發現沒有
if (!prices.containsKey(symbol)) {
    // 動作：兩個執行緒都寫入，後寫的蓋掉先寫的
    prices[symbol] = price
}
// 正確：prices.putIfAbsent(symbol, price)，檢查與寫入是一個不可分割的動作
```

**單一執行緒也會有 race condition。** Swift 的 `actor`（一次只讓一個任務執行它的程式碼的型別）保證不會有 data race，但它是**可重入的（reentrant）**：程式執行到 `await` 時會暫停，把 actor 讓給別的呼叫，所以 `await` 之前讀到的狀態，回來之後可能已經過期。

```swift
actor Counter {
    var value = 0

    func incrementSlowly() async {
        // 步驟 1：讀到 0
        let before = value
        // 步驟 2：暫停，別的呼叫可以進來
        try? await Task.sleep(nanoseconds: 10_000_000)
        // 步驟 3：寫入 0 + 1，蓋掉別人已經加的結果
        value = before + 1
    }

    // 正確：中間沒有 await，不會被打斷
    func increment() { value += 1 }
}
// 同時呼叫兩次 incrementSlowly()，最後 value 是 1，不是 2
```

Kotlin 的協程也一樣。就算只有一條執行緒，兩個協程在 `delay` 處輪流執行，「讀、delay、寫」仍然會互相覆蓋：

```kotlin
suspend fun incSlowly() {
    // 兩個協程都讀到 0
    val before = value
    // 暫停，換另一個協程執行
    delay(10)
    // 兩個都寫入 1
    value = before + 1
}
// 同時啟動兩個 incSlowly()，最後 value 是 1。
// 這沒有 data race，是 race condition
```

修法是用 `Mutex.withLock` 把整段「讀、delay、寫」包起來（第 3 題說明）。

> [!TIP]
> 面試時可以這樣說：「data race 是沒有同步的並行存取，Thread Sanitizer 和 Swift 6 能抓；race condition 是即使每次存取都有同步，結果還是取決於時序，像是先檢查再動作，或 actor 在 await 前後狀態被改掉，工具通常抓不到，要靠設計。」

### 2. iOS 有哪些鎖和同步工具？怎麼選？

**鎖（lock）的用途是讓一段程式碼同一時間只有一個執行緒能執行**，這一段叫臨界區（critical section）。沒拿到鎖的執行緒要等。iOS 常見的工具和適合的情境如下：

| 工具 | 特性 | 適合的情境 |
|---|---|---|
| `actor` | 編譯器保證同一時間只有一個任務在裡面執行；呼叫端要用 `await` | 新程式碼的第一選擇，例如價格表放進 `actor PriceStore` |
| `OSAllocatedUnfairLock`（iOS 16 起） | 輕量的鎖，不用 `await`，受保護的資料和鎖綁在一起，只能透過 `withLock` 存取 | 臨界區很短、需要同步讀寫的地方，例如快取 |
| `Mutex`（`Synchronization` 模組，iOS 18 起） | 標準函式庫的互斥鎖，用法類似上面那個 | 可以只支援 iOS 18 以上的專案 |
| `NSLock`、`NSRecursiveLock` | 物件導向的包裝，好用；遞迴鎖允許同一個執行緒重複取得 | 舊專案、需要重入時 |
| serial `DispatchQueue` | 用佇列把存取排隊，一次執行一個 | 舊的 GCD（Grand Central Dispatch，Apple 的佇列式並發機制，`DispatchQueue` 就是它的一部分）架構 |
| concurrent queue 加 `.barrier` | 讀的時候可以並行，寫的時候用 `.barrier`，等所有讀完成、寫期間獨占（只對自己建立的 concurrent queue 有效） | 讀很多、寫很少的資料 |
| `DispatchSemaphore` | 計數器，沒有「誰持有它」的概念 | 不適合當互斥鎖，在主執行緒 `wait()` 很危險 |
| `OSSpinLock` | 已經棄用，因為會造成優先權反轉（第 5 題說明） | 不要用 |

```swift
import os

// 價格表：背景執行緒寫入，主執行緒讀取。鎖保護的是裡面的字典
let prices = OSAllocatedUnfairLock(initialState: [String: Decimal]())

func updatePrice(_ price: Decimal, for symbol: String) {
    // 寫入：整段在鎖裡面
    prices.withLock { $0[symbol] = price }
}

func latestPrice(for symbol: String) -> Decimal? {
    // 讀取
    prices.withLock { $0[symbol] }
}
```

為什麼不直接用 `os_unfair_lock`？它是底層的 C 結構，**不能被搬動記憶體位置**，在 Swift 裡很容易不小心被複製。`os_unfair_lock` 本身是非遞迴的：同一個執行緒重複取得，程式會直接當掉。`OSAllocatedUnfairLock` 把鎖配置在固定的位置，並把資料和鎖綁在一起，用起來不容易出錯。

有三個常被問到的細節：

- **`os_unfair_lock` 會記錄持有者**，所以等待的高優先權執行緒可以把自己的優先權「借」給持有者，讓它快點做完（優先權捐贈，第 5 題說明）。`DispatchSemaphore` 沒有持有者的概念，不會這樣做。
- **`DispatchQueue.main.sync` 在主執行緒呼叫會 deadlock**（第 4 題說明）。
- **actor 不是萬靈丹**：它擋得住 data race，擋不住 `await` 前後的 race condition。

> [!TIP]
> 面試時可以這樣說：「新程式碼我優先用 actor，因為編譯器會幫忙檢查；需要同步存取的短臨界區，我用 OSAllocatedUnfairLock 或 iOS 18 以上的 Mutex。不會用 DispatchSemaphore 當鎖，因為它不會做優先權捐贈，在主執行緒等待還有 deadlock 風險。」

### 3. Kotlin 的 `@Volatile`、`Atomic`、`synchronized`、`Mutex` 各自解決什麼問題？

Android 的並發工具要看你要解決的是**可見性**（一個執行緒寫的值，另一個執行緒能不能馬上看到）、**原子性**（多個步驟能不能不被打斷），還是**互斥**（一段程式碼同一時間只有一個人能執行）。

| 工具 | 解決什麼 | 具體情境 |
|---|---|---|
| `@Volatile` | 只保證**可見性**：寫入後，其他執行緒馬上看得到最新值。**不保證原子性** | 一個旗標 `isConnected`，socket 執行緒寫、畫面執行緒讀 |
| `AtomicInteger`、`AtomicLong` | 單一變數的**原子操作**，底層用 CAS（compare-and-set：只有當前值等於預期值時才改成新值，否則重試） | 多個執行緒各自累加收到的行情筆數 |
| `synchronized`、`@Synchronized` | 互斥，Java 內建，**可重入**（同一個執行緒可以重複進入） | 保護一小段同步的程式碼 |
| `ReentrantLock` | 比 `synchronized` 多了功能：`tryLock(timeout)`（拿不到就放棄）、公平鎖、`Condition` | 需要逾時，避免永遠等下去 |
| `ReentrantReadWriteLock` | 讀寫分開的鎖，多個讀可以並行 | 讀很多、寫很少的資料 |
| 協程的 `Mutex` | 互斥，**不可重入**，而且等待時是**暫停協程，不是卡住執行緒** | 保護中間有 `delay` 或其他 suspend 函式的臨界區 |
| 執行緒封閉 | 資料只在一條執行緒上存取，就不需要鎖 | `HandlerThread`、單一執行緒的 dispatcher |

```kotlin
class SocketState {
    // 一個執行緒寫、其他執行緒讀：@Volatile 就夠，沒有「讀了再改」的步驟
    @Volatile var isConnected = false

    // 多個執行緒各自 +1：要用原子操作
    val ticks = AtomicLong(0)
    fun onTick() { ticks.incrementAndGet() }
}
```

**`@Volatile` 最常見的誤解**：`@Volatile var count = 0` 之後 `count++`，仍然會漏算（lost update）。因為 `count++` 是「讀、加 1、寫」三步，`@Volatile` 只保證每一步看到最新的值，不保證三步不被打斷。注意這是 race condition（原子性不足），不是 data race：volatile 的讀寫本身已經有同步。這種累加要用 `AtomicInteger`。

**協程的 `Mutex` 解決的是第 1 題的 race condition：**

```kotlin
val mutex = Mutex()

suspend fun incSlowly() {
    // 一次只有一個協程能進來，其他的在這裡暫停等待
    mutex.withLock {
        val before = value
        // 暫停期間，別的協程進不來，所以 value 不會被改掉
        delay(10)
        value = before + 1
    }
}
// 同時啟動兩個 incSlowly()，最後 value 是 2
```

兩個要記住的限制：

- 協程的 `Mutex` **不可重入**：在 `withLock` 裡面再對同一個 `Mutex` 呼叫 `withLock`，會永遠等下去。
- 不能在 `synchronized` 區塊裡呼叫 suspend 函式（例如 `delay`）：暫停期間鎖還抓在手上，執行緒卻可能被拿去做別的事，Kotlin 編譯器會直接報錯。要保護含有 suspend 呼叫的臨界區，用 `Mutex`。

Java／Kotlin 層沒有等價於 Thread Sanitizer 的官方工具，Android 要靠設計規範、code review，以及用 ANR（Application Not Responding，App 卡住沒有回應）trace（App 沒有回應時，系統留下的各執行緒狀態紀錄）和 Perfetto（Android 的系統追蹤工具）觀察鎖爭用。

> [!TIP]
> 面試時可以這樣說：「@Volatile 只保證可見性，不保證原子性，旗標可以用，累加要用 Atomic。協程裡有 suspend 呼叫的臨界區我用 Mutex，因為它是暫停協程而不是卡住執行緒，但要注意它不可重入。」

### 4. deadlock 是什麼？怎麼避免、怎麼診斷？

deadlock（死結）是**幾個執行緒互相等對方放開鎖，誰也動不了，程式永遠卡住**。如果卡住的是主執行緒，使用者就看到整個 App 凍住。

成立的條件有四個，只要破壞其中一個就不會發生：互斥（資源一次只能一個人用）、持有並等待（拿著一個鎖又去等另一個）、不可搶占（別人不能強行拿走你的鎖）、循環等待（A 等 B、B 等 A）。

交易所 App 最常見的三種寫法：

1. **兩把鎖的取得順序相反。** 執行緒 1 先拿「價格鎖」再拿「訂單鎖」，執行緒 2 先拿「訂單鎖」再拿「價格鎖」，兩邊各拿到一把，然後互相等。
2. **持有鎖的時候呼叫外部程式碼。** 例如拿著鎖呼叫 callback 或 delegate，對方又需要另一把鎖，或需要回到主執行緒。
3. **主執行緒等背景工作，但背景工作需要主執行緒。**

```swift
// 在主執行緒上執行：deadlock
// sync：目前的執行緒要等這個區塊執行完才會繼續
DispatchQueue.main.sync {
    // 區塊要排進主佇列才能執行，但主佇列正被這行呼叫占著，永遠輪不到
    updateUI()
}
// 正確：DispatchQueue.main.async { updateUI() }，或標上 @MainActor
```

Android 對應的寫法是在主執行緒用 `runBlocking { withContext(Dispatchers.Main) { ... } }`：`runBlocking` 卡住了主執行緒，裡面卻要等主執行緒才能執行。iOS 上另一種常見的寫法，是在主執行緒呼叫 `DispatchSemaphore.wait()`，等一個要回到主執行緒才會完成的非同步工作。

**預防：**

- 所有地方都用**固定的順序**取得多把鎖。
- 用 `tryLock` 加逾時（例如 `ReentrantLock.tryLock(1, TimeUnit.SECONDS)`），拿不到就放棄，而不是永遠等。
- **持有鎖的期間，不要呼叫不知道會做什麼的程式碼。** 先把需要的資料複製出來，放掉鎖，再呼叫。
- 優先用訊息傳遞、actor、單一執行緒封閉，取代同時用很多把鎖。

**診斷：**

- iOS：卡住的時候，在 Xcode 按暫停，看左邊每一個執行緒的呼叫堆疊，找出各自卡在哪一把鎖、哪個 `sync` 或 `wait`，追出誰在等誰。
- Android：看 ANR（Application Not Responding，主執行緒超過一段時間沒回應，例如處理輸入事件超過 5 秒，系統就會顯示「應用程式無回應」）的 trace。裡面會寫 `waiting to lock <0x0a1b2c3d> held by thread 17`，意思是主執行緒在等某把鎖，而這把鎖被 17 號執行緒持有。再去看 17 號執行緒的堆疊，就能找出循環。

> [!TIP]
> 面試時可以這樣說：「deadlock 是互相等待造成的，我會固定多把鎖的取得順序、持有鎖時不呼叫外部程式碼、用 tryLock 加逾時。診斷上，iOS 暫停後逐個看執行緒堆疊，Android 看 ANR trace 裡的 waiting to lock，追出誰在等誰。」

### 5. priority inversion 是什麼？

priority inversion（優先權反轉）是**高優先權的執行緒，被低優先權的執行緒擋住**，結果整體表現就像高優先權的工作被降級了。

情境：iOS 的 QoS（quality of service，執行緒的優先等級，由高到低是 `userInteractive`、`userInitiated`、`default`、`utility`、`background`）。

1. 一個低優先權（`utility`）的背景執行緒，正在持有「價格表」的鎖。
2. 主執行緒（最高優先權）需要同一把鎖，只好等。
3. 此時有一個中優先權的執行緒（例如在解碼圖片）一直占用 CPU（中央處理器），低優先權的執行緒因此分不到時間，遲遲不能釋放鎖。
4. 結果主執行緒一直被卡住，畫面凍住。

**iOS 的解法是優先權捐贈（priority donation）：** 等待鎖的高優先權執行緒，把自己的優先權「借」給鎖的持有者，讓它趕快做完、放掉鎖。

- **會捐贈**：`os_unfair_lock`（包含 `OSAllocatedUnfairLock`），因為它記錄了持有者；GCD 的 `sync` 也會。
- **不會捐贈**：`DispatchSemaphore`、`DispatchGroup`，因為它們沒有「誰持有」的概念。所以用 `DispatchSemaphore` 當鎖，在主執行緒等待，就很容易發生優先權反轉。
- `OSSpinLock`（忙等待的鎖，等待時一直占用 CPU）已棄用，原因就是會造成優先權反轉。
- Xcode 的 **Thread Performance Checker** 會警告「高 QoS 的執行緒正在等低 QoS 的執行緒」。

**Android：** 背景執行緒（例如設成 `THREAD_PRIORITY_BACKGROUND`）能分到的 CPU 時間比較少。如果它持有主執行緒需要的 `synchronized` 鎖，主執行緒就要等很久。對策是：持有鎖的時間盡可能短、鎖裡面不要做網路或讀寫檔案這類 I/O（輸入輸出）、主執行緒不要去拿「背景可能長時間占用」的鎖。

> [!TIP]
> 面試時可以這樣說：「priority inversion 是高優先權的主執行緒在等低優先權執行緒持有的鎖，而中優先權的工作一直搶 CPU。iOS 的 os_unfair_lock 會做優先權捐贈，DispatchSemaphore 不會，所以我不用 semaphore 當鎖。Xcode 的 Thread Performance Checker 可以抓到這類問題。」

### 6. 怎麼偵測並發問題？Thread Sanitizer 與 Swift 6 的編譯時檢查能抓什麼？

**Thread Sanitizer（TSan）。** 執行時偵測 data race 的工具：App 跑起來之後，它會記錄每一次記憶體存取，發現兩個執行緒沒有同步地存取同一塊記憶體，就立刻報告是哪兩行程式碼。開啟方式：Xcode 的 Scheme → Run → Diagnostics → 勾選 Thread Sanitizer。

- 只能在模擬器和 macOS 使用，**不能在實機上跑**。
- 程式會明顯變慢、記憶體用量變大。
- 只有程式真的執行到有問題的路徑時才會報告，所以測試要涵蓋多執行緒的流程。
- **只抓 data race，抓不到 race condition**（例如 actor 重入）。

其他 Xcode 提供的檢查：**Main Thread Checker** 會在背景執行緒呼叫 UI API 時報告；**Thread Performance Checker** 會警告優先權反轉和主執行緒上的 I/O。Android 的 `StrictMode` 則可以偵測主執行緒上的磁碟與網路存取。

**Swift 6 的編譯時檢查。** TSan 要等到執行才知道，Swift 6 則在**編譯時**就找出 data race。它檢查兩件事：資料要跨過執行緒的邊界時，這個型別是不是 `Sendable`（可以安全地在執行緒之間傳遞的型別）；可以修改的狀態，是不是被某個 actor 或 `@MainActor` 隔離保護。

```swift
// 一般的 class，有可修改的屬性，不是 Sendable
final class PriceCache {
    var last: Decimal = 0
}

func start() {
    let cache = PriceCache()
    Task.detached {
        // 背景任務修改
        cache.last = 1
    }
    // 呼叫端同時修改
    cache.last = 2
}
// Swift 6 語言模式：編譯錯誤
// error: sending value of non-Sendable type '() async -> ()'
// risks causing data races
```

修法是把 `PriceCache` 改成 `actor`，兩邊都透過 `await` 存取，編譯器就沒有意見。

檢查的嚴格程度由 build setting `SWIFT_STRICT_CONCURRENCY` 控制：`minimal`（只檢查已經用了並發功能的程式碼）、`targeted`、`complete`（全部檢查）。**Swift 6 語言模式等於 `complete`，而且把這些警告變成錯誤。** 大型專案通常從 `minimal` 一路提高到 `complete`，逐步遷移。

要小心 `@unchecked Sendable` 和 `nonisolated(unsafe)`：它們的意思是「編譯器不要檢查，我保證安全」。如果背後沒有真的加鎖，只是為了消除警告，bug 還在，只是編譯器不再提醒了。

> [!TIP]
> 面試時可以這樣說：「TSan 在執行時抓 data race，只能在模擬器用；Swift 6 在編譯時就檢查 Sendable 和 actor 隔離，把 data race 變成編譯錯誤。兩者都抓不到 actor 重入這類 race condition，那要靠設計。我不會用 unchecked Sendable 只為了消除警告，除非背後真的有同步機制。」

> [!FIELD]
> 在一個社交 App 和一個即時行情 App 的經驗：社交 App 的 iOS 專案把 Realm（本機資料庫，物件只能在建立它的執行緒使用）放在 actor 後面，呼叫端只拿到複製出來的資料；用 `AsyncSemaphore`（Swift Concurrency 版的號誌）序列化 token 更新和聊天室同步；Swift 的嚴格並發檢查設在 `targeted`，採漸進式遷移。它的即時聊天連線層，iOS 用 `DispatchSemaphore` 保護兩個待送訊息佇列；Android 用 `AtomicBoolean` 防止同時連線、`ConcurrentLinkedQueue` 加 `CompletableDeferred` 管理待送工作，Realm 則放在單一執行緒的 dispatcher 後面。即時行情 App 的 Android 付款流程用兩個 `Mutex` 序列化連線與購買，並用 `Channel` 接收購買結果。
> 換成交易所 App，我會把嚴格並發檢查逐步提高到 `complete`，並在 iOS 的 CI（持續整合，每次提交程式碼就自動建置與測試）裡跑一輪 Thread Sanitizer。

## 實際演練

> [!LAB]
> **打開**：iOS：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced04Races**。Android：Android Studio 打開 `labs/android` 資料夾，Run configuration 選 **app**，按 ▶，App 首頁進入 **Advanced04Races**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced04-Races/`，解答在 `labs/ios/Solution/Advanced/Advanced04-Races/`；Android 你寫在 `labs/android/practice/advanced/advanced04-races/`，解答在 `labs/android/solution/advanced/advanced04-races/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：演練 1、2 的 Bug 版寫對了會讓 App 當掉（畫面上會先提醒），要從 Xcode 執行才看得到當掉的位置，自動檢查略過它們。演練 3 的 deadlock 加了逾時當保險（`NSLock.lock(before:)`、`ReentrantLock.tryLock(timeout)`），逾時就放棄並寫進 log，不會真的把 App 卡死；真的 App 沒有這個保險，就是整個凍住。這一單元的時間數字用的是「睡幾百毫秒」，門檻很寬，不受機器快慢影響。

### 演練 1：寫入一筆價格，App 直接當掉（第 2 題）

**檔案**：`L01_UnfairLockReentry.swift`（只有 iOS；Android 的 Mutex 不可重入見 Kotlin04CoroutineSync 的演練 3）

**情境**：價格表 `PriceBook` 用 `OSAllocatedUnfairLock` 保護字典。寫入一筆價格後，要順便檢查有沒有觸發價格提醒（價格大於等於 15,000,000），檢查的函式 `isAlertTriggered(for:)` 平常也會被別的地方呼叫，所以它自己也會 `withLock` 去讀最新價。

**怎麼寫**

1. **Bug 版**：`updatePrice` 在 `withLock` 裡存好價格，然後呼叫 `isAlertTriggered(for:)`（它又 `withLock` 一次）。
2. **修正版**：把「已經持鎖」的邏輯拆成內部函式 `alertTriggered(in:symbol:)`（直接用傳進來的字典，不拿鎖），`updatePrice` 在 `withLock` 裡呼叫它；對外的 `isAlertTriggered` 才自己 `withLock` 再呼叫它。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | log 只有「寫入 BTC/JPY 15,000,000」，然後 App 當掉：Xcode 停在 `withLock`，Console 印出 `Trying to recursively lock an os_unfair_lock`（這個版本會當掉，所以自動檢查略過它） |
| 修正版 | 寫入完成，價格提醒觸發：是；另外單獨查詢提醒：是 |

**為什麼**：`os_unfair_lock` 是非遞迴的，同一條執行緒持鎖時再拿同一把鎖，系統直接終止程式。`OSAllocatedUnfairLock` 包的就是它，所以一樣。換成 `NSRecursiveLock` 也不會當掉，但「需要重入」通常表示臨界區太大、持鎖時呼叫了不該呼叫的東西，拆成「已持鎖」的內部函式比較好維護。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L01_UnfairLockReentry.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L01_UnfairLockReentry.swift#fixed
```

### 演練 2：從背景呼叫沒事，在主執行緒呼叫就當掉（第 4 題）

**檔案**：`L02_MainSync.swift`（只有 iOS；Android 對應的 `runBlocking` 卡住執行緒見 Kotlin04CoroutineSync 的演練 6）

**情境**：團隊寫了一個小工具函式 `showPrice`，用來「確保更新畫面是在主執行緒做」。它先在背景的行情執行緒用過，一直沒事；後來有人在按鈕的處理函式（本來就在主執行緒）裡也呼叫它。

**怎麼寫**

1. **Bug 版**：`showPrice` 用 `DispatchQueue.main.sync { update() }`。
2. **修正版**：改成 `DispatchQueue.main.async { update() }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 背景呼叫的 log 都正常，到「從主執行緒呼叫 showPrice（按鈕處理函式）」之後 App 當掉，Console：`BUG IN CLIENT OF LIBDISPATCH: dispatch_sync called on queue already owned by current thread` |
| 修正版 | 背景呼叫完成、主執行緒呼叫完成，兩次「更新畫面，在主執行緒：true」 |

**為什麼**：`sync` 是「目前的執行緒要等這個區塊執行完才會繼續」。從背景呼叫，背景等主執行緒執行，沒問題；從主執行緒呼叫，區塊要排進主佇列才能執行，但主佇列正被這行呼叫占著，永遠輪不到，這是 deadlock，libdispatch 偵測到之後直接終止 App。診斷時看當掉的呼叫堆疊，最上面是 `dispatch_sync` 相關的函式；如果是畫面卡住沒有當掉，Xcode 按暫停，看每一條執行緒各卡在哪裡。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L02_MainSync.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L02_MainSync.swift#fixed
```

### 演練 3：行情推進來的同時下單，兩邊都卡住不動（第 4 題）

**檔案**：`L03_CallbackDeadlock.swift`（iOS）、`L03_CallbackDeadlock.kt`（Android）

**情境**：價格表 `PriceStore` 有價格鎖，委託管理 `OrderManager` 有委託鎖。WebSocket 執行緒：`PriceStore.update` 寫入新價格，接著通知 listener（`OrderManager.onPrice`，需要委託鎖）。畫面執行緒：`OrderManager.placeOrder` 先拿委託鎖，再向 `PriceStore.latest` 查最新價（需要價格鎖）。兩條執行緒同時進來：一邊拿著價格鎖等委託鎖，另一邊拿著委託鎖等價格鎖。

**怎麼寫**

1. **Bug 版**：`update` 在持有價格鎖的時候，直接呼叫 listener。
2. **修正版**：持鎖期間只寫資料，把 listener 複製出來；放掉鎖之後才呼叫它（iOS：不用 `defer`，手動 `lock.unlock()`；Android：`try` 裡只複製，`finally` 放掉鎖，之後才 `invoke`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 至少一行「等委託鎖／價格鎖等了 300 ms 還拿不到，疑似 deadlock，放棄」；其中一邊放棄之後另一邊才拿得到鎖，晚了 300 ms 才完成 |
| 修正版 | 沒有「疑似 deadlock」；有「更新持倉估值 BTC/JPY 15100000」與「下單成功，用價格 15100000」 |

**診斷**：iOS 卡住時在 Xcode 按暫停，左邊每一條執行緒的呼叫堆疊裡，一條停在 `NSLock.lock` 的是 listener 那一層，另一條停在 `PriceStore.latest` 那一層；Android 看 ANR 的 trace，主執行緒的堆疊會寫 `waiting to lock <0x…> held by thread 17`，再看 17 號執行緒的堆疊，追出「誰在等誰」，就找到循環。

**為什麼**：deadlock 的成立條件之一是持有並等待、循環等待。「持鎖時呼叫外部程式碼」是最常見的來源，因為你不知道對方還會去拿什麼鎖。把需要的資料複製出來、放掉鎖再呼叫，沒有任何一條執行緒同時拿著兩把鎖，循環就不會形成。另一個做法是固定多把鎖的取得順序，但要靠每一個人都遵守，比較難維護。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L03_CallbackDeadlock.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L03_CallbackDeadlock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced04-races/L03_CallbackDeadlock.kt#bug
```

```lab-solution
android/solution/advanced/advanced04-races/L03_CallbackDeadlock.kt#fixed
```

### 演練 4：存快照的時候，畫面讀一下價格就被卡住（第 5 題）

**檔案**：`L04_LongCriticalSection.swift`（iOS）、`L04_LongCriticalSection.kt`（Android）

**情境**：價格表同時被兩個地方使用：背景（優先權比較低）的執行緒把整份價格寫到檔案（存快照，約 400 毫秒的 I/O），主執行緒要讀一筆最新價來顯示。演練量主執行緒等了多久。

**怎麼寫**

1. **Bug 版**：
   - iOS：用 `DispatchSemaphore(value: 1)` 當鎖，`saveSnapshot` 持鎖的期間 `Thread.sleep(0.4)`（寫檔案）；`latest()` 也 `wait()` 同一個 semaphore。
   - Android：`saveSnapshot` 在 `synchronized(lock)` 裡 `Thread.sleep(400)`；`latest()` 也用 `synchronized(lock)`。
2. **修正版**：
   - iOS：改用 `OSAllocatedUnfairLock`，`saveSnapshot` 持鎖只複製一份資料（`withLock { $0 }`），寫檔案放到鎖外面。
   - Android：持鎖只 `prices.toMap()` 複製，寫檔案放到 `synchronized` 外面。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 主執行緒讀到價格 15000000，等了約 380 ms |
| 修正版 | 主執行緒讀到價格 15000000，等了不到 5 ms（實測 0 ms） |

**為什麼**：臨界區越長，高優先權的執行緒（主執行緒）被低優先權的執行緒擋住的時間越長。iOS 的 `DispatchSemaphore` 沒有「誰持有它」的概念，系統不會把主執行緒的優先權借給持鎖的背景執行緒（優先權捐贈）；`os_unfair_lock`、`OSAllocatedUnfairLock` 記錄了持有者，會捐贈。Xcode 的 Thread Performance Checker（Edit Scheme › Run › Diagnostics）會對「高 QoS 的執行緒在等低 QoS 的執行緒」警告，可以試著用它檢查 Bug 版（是否出現警告依系統版本而定，只在從 Xcode 執行時啟用）。Android 沒有優先權捐贈，所以更要把鎖裡面的工作縮到最短、不做 I/O，主執行緒也不要去拿「背景可能長時間占用」的鎖；StrictMode 的 `detectDiskReads()`、`detectDiskWrites()` 可以抓主執行緒上的磁碟存取。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L04_LongCriticalSection.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced04-Races/L04_LongCriticalSection.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced04-races/L04_LongCriticalSection.kt#bug
```

```lab-solution
android/solution/advanced/advanced04-races/L04_LongCriticalSection.kt#fixed
```

第 1 題（data race 與 race condition）和第 3 題（`@Volatile`、`Atomic`、`synchronized`、`Mutex`）的演練在前面的單元已經做過，不重複：資料競爭與 Thread Sanitizer 見 Swift05Actors 的演練 1，actor 的 reentrancy（單一執行緒也會有 race condition）見演練 2、3，先檢查再行動見 Kotlin04CoroutineSync 的演練 2 與 Kotlin10JavaInterop 的演練 5，`@Volatile` 與 `AtomicInteger` 見 Kotlin10JavaInterop 的演練 3、4，`Mutex` 見 Kotlin04CoroutineSync 的演練 1、3。第 6 題（怎麼偵測並發問題）是工具的用法：Thread Sanitizer 在 Xcode 的 Scheme › Run › Diagnostics 勾選，從 Xcode 執行 Swift05Actors 演練 1 的 Bug 版，會回報 `Data race detected`（而 Swift05Actors 演練 2 的 actor reentrancy，Thread Sanitizer 不會報告，因為每一次存取都有同步）；Swift 6 的編譯時檢查，把專案的 Strict Concurrency Checking 設成 Complete 就看得到，Swift05Actors 的演練 1、6 有說明報錯的位置。這些都需要改專案設定或看工具輸出，沒有可以在演練 App 裡自動檢查的 bug。

## 常見陷阱

- **以為 actor 裡面的程式碼永遠安全。** actor 擋得住 data race，但 `await` 前後狀態可能已經被別的呼叫改掉。讀、改、寫之間不要跨過 `await`。
- **為了消除警告加上 `@unchecked Sendable` 或 `nonisolated(unsafe)`，卻沒有實際的同步。** 警告消失了，bug 還在。
- **在主執行緒呼叫 `DispatchQueue.main.sync`、`DispatchSemaphore.wait()` 或 `runBlocking`。** 很容易 deadlock，或讓主執行緒凍結到 ANR。
- **持有鎖的時候呼叫 callback，或兩把鎖的取得順序不一致。** 這是 deadlock 最常見的來源。
- **用輪詢（睡一下再檢查旗標）取代等待機制。** 浪費執行緒，還會有時序問題。應該用條件變數、`CompletableDeferred`，或 async 等待。
- **以為單一執行緒的 dispatcher 就不會有 race condition。** 協程在 `delay` 或其他暫停點之間，仍然會互相插入。
- **以為 `@Volatile` 能讓 `count++` 變安全。** `@Volatile` 只保證可見性，累加要用 `AtomicInteger`。

## 面試官想聽到的

1. 能清楚區分 data race 與 race condition，並說明 Thread Sanitizer 能抓哪一種、抓不到哪一種。
2. 熟悉兩個平台各自的鎖與取捨：`os_unfair_lock` 非遞迴但會做優先權捐贈、`DispatchSemaphore` 不做優先權捐贈、協程的 `Mutex` 不可重入、actor 可重入。
3. 會系統性地預防 deadlock 與 priority inversion，並知道怎麼診斷（暫停看堆疊、ANR trace）。
4. 有設計原則：能不用鎖就不用（不可變資料、單一寫入者、訊息傳遞），有鎖的地方臨界區要短。
5. 知道 Swift 6 的編譯時檢查在做什麼（Sendable、actor 隔離），以及為什麼不能濫用 `@unchecked Sendable`。

> [!WARNING]
> 面試官常追問：「actor 會有 race condition 嗎？」不要回答「不會」。要說明 actor 擋得住 data race，但它是可重入的，`await` 的時候別的呼叫可以進來，所以讀取後再寫入之間如果有 `await`，仍然可能用到過期的狀態。

## iOS / Android 對照

| 需求 | iOS | Android |
|---|---|---|
| 簡單的互斥 | `OSAllocatedUnfairLock`、`NSLock` | `synchronized`、`ReentrantLock` |
| 暫停式的互斥（等待時不卡住執行緒） | `actor` | 協程的 `Mutex` |
| 讀寫分開 | concurrent queue 加 `.barrier` | `ReentrantReadWriteLock` |
| 單一變數的原子操作 | `Atomic`（`Synchronization` 模組，iOS 18 起）、`OSAllocatedUnfairLock` | `AtomicInteger`、`AtomicLong` |
| 只要可見性 | 不常用，通常用鎖或 actor | `@Volatile` |
| 限制在單一執行緒 | serial queue、`@MainActor`、actor | `HandlerThread`、單一執行緒的 dispatcher |
| 偵測 data race | Thread Sanitizer、Swift 6 的編譯時檢查 | 沒有等價的工具，靠設計與 code review |
| deadlock 的線索 | 暫停後看各執行緒的呼叫堆疊 | ANR trace 裡的 `waiting to lock` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 資料競爭（沒有同步的並行存取） | data race | データ競合 |
| 競態條件（結果取決於時序） | race condition | 競合状態 |
| 鎖 | lock | ロック |
| 死結 | deadlock | デッドロック |
| 優先權反轉 | priority inversion | 優先度逆転 |
| 互斥鎖 | mutex | ミューテックス |
| 可安全跨執行緒傳遞的型別 | Sendable | Sendable |
| 執行緒安全 | thread-safe | スレッドセーフ |
