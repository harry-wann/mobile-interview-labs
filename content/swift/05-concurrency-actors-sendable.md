---
id: swift-concurrency-actors-sendable
title: actor、Sendable 與 Swift 6／6.2：資料怎麼安全地跨執行緒使用
summary: actor 最常被追問的是 reentrancy（await 的時候，別人可能插隊進來把狀態改掉）。Sendable 和 Swift 6 的嚴格並行檢查，是舊專案升級時最大的工程。Swift 6.2 又改了「async 函式預設在哪裡執行」。這個單元把這些一個一個講清楚。
priority: P0
day: 1
tags: [actor, mainactor, sendable, swift6, strict-concurrency, approachable-concurrency]
---

## 重點講解

上一個單元講了任務怎麼開、怎麼取消。這個單元處理另一半：**資料怎麼在多個執行緒之間安全地共用。**

先複習一個基本概念：data race（資料競爭）是指多個執行緒同時存取同一份資料，其中至少一個在寫入，而且沒有任何同步機制。結果不可預期，通常是很難重現的 crash 或資料錯亂。Swift Concurrency 的 actor、Sendable，都是為了讓編譯器能幫你擋掉這類問題。

這個單元回答六個面試常見問題：

1. actor 是什麼？它解決了什麼問題？
2. actor 的 reentrancy 是什麼？怎麼修？（面試最常追問）
3. `@MainActor` 與 global actor 是什麼？該怎麼用？
4. `Sendable` 是什麼？`@unchecked Sendable` 什麼時候才合理？
5. Swift 6 的語言模式與嚴格並行檢查（strict concurrency）是什麼？舊專案怎麼升級？
6. Swift 6.2 的預設 MainActor 與 `@concurrent` 改了什麼？

### 1. actor 是什麼？它解決了什麼問題？

actor 是一種把自己的可變資料「圍起來」的型別。**從外部要碰 actor 裡的可變狀態，一定要用 `await`，而且一次只會有一個呼叫在 actor 裡面執行。** 其他呼叫要排隊，所以不需要自己加鎖，也不會發生 data race。

用交易所的情境來看，訂單簿會被很多地方同時讀寫：WebSocket 推送更新、畫面讀取最佳買價、背景計算深度。把它放進 actor 裡，這些存取就會自動排隊：

```swift
actor OrderBook {
    // 價格 -> 數量
    private var bids: [Decimal: Decimal] = [:]

    func update(price: Decimal, qty: Decimal) {
        bids[price] = qty
    }

    func bestBid() -> Decimal? {
        bids.keys.max()
    }
}

let book = OrderBook()
// 從 actor 外面呼叫要 await：如果 actor 正忙，就在這裡排隊等
await book.update(price: 100, qty: 1)
let best = await book.bestBid()
```

用「isolation（隔離）」這個詞來描述這件事：一份資料被某個 actor 隔離，就代表「這份資料歸這個 actor 管，只有它能直接存取」。從外面存取就要 `await`，讓出去排隊。

有幾個細節要知道：

- **不需要 `await` 的例外**：標了 `nonisolated` 的成員。例如 `let` 宣告的 Sendable 屬性（不會變，誰讀都安全）、不碰可變狀態的純函式。
- **每一次跨 actor 的 `await` 都是一次排程**，細碎的呼叫成本不低。與其呼叫十次 getter，不如提供一個方法，一次回傳當下資料的快照（snapshot）。
- **不要在 actor 裡呼叫會卡住的同步 I/O**（例如同步讀檔、`Thread.sleep`）。因為一次只能有一個呼叫在裡面執行，卡住了，其他所有呼叫都得等。

> [!TIP]
> 面試時可以這樣說：「actor 把可變狀態圍起來，外部存取都要 `await` 排隊，一次只有一個呼叫在裡面，所以不會有 data race，也不用自己加鎖。我會把訂單簿、快取這種被很多地方共用的可變資料放進 actor。」

### 2. actor 的 reentrancy 是什麼？怎麼修？

**reentrancy（重入）：actor 的方法執行到 `await` 暫停時，會把 actor 讓出來，別的呼叫可以趁這個空檔進來執行。等原本的方法恢復時，之前讀到的狀態，可能已經被別人改掉了。**

所以 actor 保證的是：**兩個 `await` 之間的程式碼不會被打斷**；它**不保證整個方法**從頭到尾不被插隊。很多人以為「actor 就是 mutex（互斥鎖）」，這是面試最常見的誤解。

看一個交易所的情境：「檢查可用餘額 → 向伺服器確認 → 扣款」。

```swift
actor Account {
    private(set) var balance = 100

    // 有 bug 的版本：檢查和扣款之間隔了一個 await
    func withdrawBuggy(_ n: Int) async -> Bool {
        // 步驟 1：檢查餘額
        guard balance >= n else { return false }
        // 步驟 2：模擬向伺服器確認，這裡會暫停、讓出 actor
        try? await Task.sleep(nanoseconds: 10_000_000)
        // 步驟 3：扣款
        balance -= n
        return true
    }
}

let account = Account()
async let a = account.withdrawBuggy(80)
async let b = account.withdrawBuggy(80)
print(await a, await b, await account.balance)
// 輸出：true true -60
```

兩個 `withdraw(80)` 同時呼叫，初始餘額 100。發生了什麼事：

1. 呼叫 A 通過檢查（100 ≥ 80），然後在 `Task.sleep` 暫停，讓出 actor。
2. 呼叫 B 趁這個空檔進來，此時餘額還是 100，也通過檢查，也在 `Task.sleep` 暫停。
3. A 醒來扣 80，餘額變 20；B 醒來也扣 80，餘額變成 −60。

交易所的「可用餘額」「掛單凍結」「使用者連點兩次下單」，全都是這個模式。有**三種修法**：

**修法一：在 `await` 之前就先改好狀態**（先保留額度），失敗了再補回去。

```swift
func withdraw(
    _ n: Int,
    confirm: @Sendable () async throws -> Void
) async -> Bool {
    guard balance >= n else { return false }
    // 先扣掉（保留額度），再去 await
    balance -= n
    do {
        try await confirm()
        return true
    } catch {
        // 確認失敗：把額度還回去
        balance += n
        return false
    }
}
```

**修法二：`await` 之後重新檢查。** 恢復之後再確認一次前提是否還成立，不成立就放棄。

**修法三：把正在進行的工作存起來合併。** 適合「同一件事只做一次」，例如快取。用字典記住每個 key 正在進行的 Task，第二個呼叫進來時，不重新載入，而是等同一個結果：

```swift
actor PriceCache {
    private var cache: [String: Decimal] = [:]
    // 正在載入的工作
    private var inFlight: [String: Task<Decimal, Error>] = [:]

    func price(for symbol: String) async throws -> Decimal {
        if let cached = cache[symbol] {
            return cached
        }
        // 已經有人在載入：等同一個結果
        if let running = inFlight[symbol] {
            return try await running.value
        }

        let task = Task { try await fetchPrice(symbol) }
        // 在 await 之前就登記，後面進來的呼叫才看得到
        inFlight[symbol] = task
        defer {
            inFlight[symbol] = nil
        }
        let value = try await task.value
        cache[symbol] = value
        return value
    }
}
```

> [!TIP]
> 面試時可以這樣說：「actor 不是 mutex。方法裡只要有 `await`，狀態在前後就可能被別人改掉。像扣款這種『檢查、await、扣』，我會在 await 之前就先扣掉，失敗再補回；要避免重複載入，就把進行中的 Task 存起來合併。」

### 3. `@MainActor` 與 global actor 是什麼？該怎麼用？

**global actor 是「全域只有一個」的 actor，可以用一個標註套在很多型別、函式上，讓它們共用同一個 isolation。** `@MainActor` 是系統內建的 global actor，代表主執行緒；標了它，程式就一定在主執行緒執行。

- 標在 class 上，所有成員預設都在主 actor 上；個別成員可以用 `nonisolated` 跳出來。在較新的 SDK 中，`UIView`、`UIViewController` 本身就已經標了 `@MainActor`。
- 在主 actor 裡建立的 `Task {}` 和 closure 會繼承主 actor；`Task.detached` 和 `@concurrent` 函式不會（上一個單元說過）。
- 可以自訂 global actor，讓分散在各處的型別共用同一個 isolation。適合「某個資源的所有存取都要排隊、一個一個來」的情況。例如在一個社交 App 的 iOS 版，就用自訂的 global actor 序列化聊天資料庫的存取：

```swift
import Foundation

@globalActor
actor ChatRoomActor {
    // global actor 必須提供的 shared 實例
    static let shared = ChatRoomActor()
}

// 這個型別的所有成員，都在 ChatRoomActor 上排隊執行
@ChatRoomActor
final class ChatRoomStore {
    var unread = 0
}
```

**常見的誤用**：整個 ViewModel 不加區分地標 `@MainActor`，然後在裡面解析大型 JSON、排序、計算 diff，結果這些重運算全部卡在主執行緒，畫面掉格。比較好的做法是：重運算放進另外的 `nonisolated` 或 `@concurrent` 函式（`@concurrent` 在第 6 節說明），算完之後再回到主 actor 更新狀態。

> [!TIP]
> 面試時可以這樣說：「`@MainActor` 保證程式在主執行緒，我用它保護和畫面有關的狀態。但我不會把整個 ViewModel 都標上去、再在裡面做重運算；解析和排序會放在背景的函式，只在最後回主 actor 更新狀態。」

### 4. `Sendable` 是什麼？`@unchecked Sendable` 什麼時候才合理？

**`Sendable` 是一個標記，代表「這個型別的值可以放心地傳到別的 actor 或執行緒」。** 編譯器用它檢查資料競爭：一個值要跨越 actor 的邊界（例如傳給另一個 actor 的方法、或帶進 `Task` 的 closure），型別就必須是 `Sendable`。

什麼樣的型別可以是 `Sendable`？道理很簡單：**不可變的，或自己有保護機制的。**

| 型別 | 能不能跨 isolation 傳 |
|---|---|
| struct、enum，所有成員都是 `Sendable` | 可以（非 public 時，編譯器自動認定，不用自己寫） |
| `final class`，所有存放屬性都是 `let`，而且型別是 `Sendable` | 可以（要自己明確寫 `: Sendable`，而且不能繼承非 Sendable 的父類別） |
| actor | 永遠可以（它自己保護自己的狀態） |
| 一般的 class（有可變屬性） | 不行，除非用 `@unchecked Sendable` 自己保證 |
| closure | 要標 `@Sendable`，而且不能捕獲可變的區域變數，或不是 `Sendable` 的值 |

**Swift 6.0（Xcode 16）起**，編譯器還多了一種判斷方式，叫 region-based isolation：它會追蹤一個值「現在還有誰拿著」。如果確認傳出去之後，原本的地方已經沒有其他參照了，那麼這個值即使不是 `Sendable`，也可以安全地傳過去。`sending` 參數／回傳值，就是把這個保證寫進函式的簽名裡。

**`@unchecked Sendable`** 的意思是：你向編譯器保證「同步由我自己負責，你不用檢查」。唯一站得住腳的用法，是類別內部用鎖、serial queue（一次只做一件事的佇列）等機制保護所有可變狀態，並且把這些機制封裝在型別裡面：

```swift
import Foundation

final class LockedBox<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    // 可變的，但只有在鎖裡才會被存取
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withValue<R>(_ body: (inout Value) -> R) -> R {
        lock.lock()
        defer {
            lock.unlock()
        }
        return body(&value)
    }
}
```

另一個類似的逃生口是 `nonisolated(unsafe)`（Swift 5.10 起可用）：把全域或靜態的可變變數標成「我自己負責」，編譯器就不再檢查它。

鎖的選擇要看部署目標：`Synchronization` 模組的 `Mutex` 需要 iOS 18；`OSAllocatedUnfairLock` 需要 iOS 16。如果專案最低支援 iOS 15，就只能用 `NSLock`、`DispatchQueue` 或 actor。

> [!WARNING]
> `@unchecked Sendable` 與 `nonisolated(unsafe)` 沒有讓資料競爭消失，只是把它從「編譯時的錯誤」搬到「執行時才爆」，後果是很難重現的 crash 或資料損壞。每一處使用，都應該能回答：「誰負責同步？用什麼機制？有用 Thread Sanitizer（Xcode 在執行時偵測資料競爭的工具）測過嗎？」

> [!TIP]
> 面試時可以這樣說：「Sendable 是讓編譯器檢查資料能不能跨執行緒傳的標記，不可變的 struct 和 final class 可以直接是 Sendable，可變的 class 就要放進 actor 或自己加鎖。`@unchecked Sendable` 我只在類別內部真的有鎖保護、而且寫明同步機制時才用，不會為了消除警告就加上去。」

### 5. Swift 6 的語言模式與嚴格並行檢查是什麼？舊專案怎麼升級？

先把兩個名詞分清楚：

- **Swift 6 語言模式**（Swift 6.0，Xcode 16，2024 年）：資料競爭的檢查變成**編譯錯誤**。違反就編譯不過。
- **strict concurrency checking（嚴格並行檢查）**：在 Swift 5 語言模式下，用 build setting `SWIFT_STRICT_CONCURRENCY` 逐級加嚴的檢查，違反時是警告，讓舊專案可以慢慢升級。有三個等級：

| 等級 | 檢查範圍 |
|---|---|
| `minimal` | 只檢查明確寫了 `Sendable`、actor 標註的地方 |
| `targeted` | 檢查所有已經用到 `async/await`、`Sendable`、actor 的程式碼 |
| `complete` | 檢查全部程式碼，範圍和 Swift 6 相同，只是違規時是警告 |

一個大專案不可能一次升到 Swift 6，建議的順序是：

1. 從最底層、依賴最少的 module（資料模型、網路層）開始。
2. 把該 module 的設定開到 `complete`，逐一修掉警告。
3. 修完之後，再打開這個 module 的 Swift 6 語言模式。
4. 再往上一層 module 重複同樣的步驟。

實務上的幾個做法：先把資料模型改成 struct 加 `Sendable`，再處理單例與全域狀態（可變狀態收進 actor）；第三方 SDK 還沒有標註 `Sendable` 時，用 `@preconcurrency import` 先放寬對它的檢查；**不要為了消除警告就一口氣加上大量 `@unchecked Sendable`**，那只是把問題延後。

> [!TIP]
> 面試時可以這樣說：「Swift 6 語言模式是把資料競爭變成編譯錯誤。舊專案我會用 `SWIFT_STRICT_CONCURRENCY` 從 targeted 一路加嚴到 complete，從最底層的 module 開始，先把資料模型改成 Sendable，第三方 SDK 用 `@preconcurrency import`，最後才切換 Swift 6 模式。」

### 6. Swift 6.2 的預設 MainActor 與 `@concurrent` 改了什麼？

**先看背景。** 在 Swift 6.2 之前（從 Swift 5.7 起），一個 `nonisolated` 的 async 函式，被主 actor 上的程式呼叫時，會自動跳到背景的共用執行緒池執行。這個行為看起來很貼心，卻有一個副作用：資料要跨界傳過去，編譯器就開始要求 `Sendable`，讓只是想寫「一個簡單的單執行緒 App」的人，一直碰到難懂的 `Sendable` 錯誤。

**Swift 6.2（Xcode 26，2025 年）** 的方向是：讓 App 預設更像單執行緒，要並行的地方再明確寫出來。新增的設定與語法如下：

| 設定／語法 | 效果 |
|---|---|
| `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`（編譯器參數 `-default-isolation=MainActor`） | module 內沒有標註的宣告，預設都在主 actor 上。單執行緒的 App 幾乎不用自己寫 `@MainActor`，`Sendable` 錯誤也少很多 |
| `NonisolatedNonsendingByDefault`（尚未預設啟用的新功能，upcoming feature） | `nonisolated` 的 async 函式，預設改成**在呼叫者所在的 actor 上執行**（呼叫者在哪個 actor，它就在哪個 actor），不再自動跳到背景 |
| `@concurrent` | 明確要求「離開呼叫者的 actor，到背景並行執行」 |
| `nonisolated(nonsending)` | 明確標示「留在呼叫者的 actor 上執行」 |

Xcode 26 的設定 `SWIFT_APPROACHABLE_CONCURRENCY` 是一組設定的開關，會一次打開幾個新功能，其中包含上表的 `NonisolatedNonsendingByDefault`；它和 `SWIFT_DEFAULT_ACTOR_ISOLATION` 是兩個獨立的設定。Xcode 26 新建的 App 專案，這兩項預設都會開啟（本機從 Xcode 26 的專案範本確認）；既有專案不會自動改變，要自己開。

用一個小程式看三種函式的差別（`pthread_main_np()` 回傳 1 代表在主執行緒、0 代表不在）：

```swift
import Foundation

nonisolated func a() async -> Int32 {
    pthread_main_np()
}

nonisolated(nonsending) func b() async -> Int32 {
    pthread_main_np()
}

@concurrent func c() async -> Int32 {
    pthread_main_np()
}

@MainActor func run() async {
    print(await a(), await b(), await c())
}

await run()
// 沒有啟用 NonisolatedNonsendingByDefault：輸出 0 1 0
//   a：舊行為，離開呼叫者的 actor，所以不在主執行緒
//   b：明確要求留在呼叫者的 actor，所以在主執行緒
//   c：@concurrent 一律離開
// 啟用 NonisolatedNonsendingByDefault 後：輸出 1 1 0（a 變成留在主執行緒）
```

再看一個實務的例子：

```swift
struct Candle: Sendable {
    var close: Decimal
}

@concurrent
func parseCandles(_ data: Data) async throws -> [Candle] {
    // 解析大量 K 線資料：重運算，明確離開呼叫者的 actor，在背景執行
    []
}
```

**遷移要小心**：既有程式裡，原本「會自動離開主 actor」的 async 函式，在新預設下會**留在主執行緒**，可能讓畫面卡住。需要並行的地方要補上 `@concurrent`。Xcode 的 build setting 可以把這些新功能設成 `MIGRATE` 模式，先把受影響的地方找出來，再決定要不要真的打開。這些都是編譯時的語意，不要求更新的 iOS 版本（本機以 iOS 15 為目標做型別檢查可以通過），但需要 Xcode 26／Swift 6.2 的工具鏈才能使用。

> [!TIP]
> 面試時可以這樣說：「Swift 6.2 讓 App 預設更像單執行緒：module 可以設成預設 `@MainActor`，`nonisolated` 的 async 函式也不再自動跳去背景，而是留在呼叫者的 actor。要並行的地方，我會明確寫 `@concurrent`。升級時要注意原本靠 `nonisolated async` 離開主執行緒的程式，現在可能會留在主執行緒卡住畫面。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift05Actors**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift05-Actors/`，解答在 `labs/ios/Solution/Swift/Swift05-Actors/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：這個專案是 Swift 5 語言模式（`SWIFT_STRICT_CONCURRENCY = minimal`），所以 Bug 版都能編譯。演練裡的註解和步驟會說明「打開 Swift 6 語言模式後，編譯器會在哪裡報錯」。演練 5 的 Bug 版寫對了會讓 App 當掉（畫面上會先提醒），要從 Xcode 執行才看得到當掉的位置。

### 演練 1：100 萬筆成交，只數到不到 100 萬（第 1 題）

**檔案**：`L01_DataRace.swift`

**情境**：行情畫面統計成交筆數。10 個任務同時處理推播，每個任務處理 10 萬筆，每筆呼叫一次 `increment()`，總數應該是 100 萬。

**怎麼寫**

1. **Bug 版**：`TradeCounter` 是一般的 `final class`，標 `@unchecked Sendable`（為了讓編譯器不抱怨），有 `private(set) var count` 和 `increment()`（`func` 前面加 `@inline(never)`，不然 Release 建置會把迴圈合併成一次加法，看不到競爭），不加任何鎖。
2. **修正版**：`TradeCounter` 改成 `actor`，成員一樣，呼叫時加 `await`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 實際數到的筆數少於 1000000（Debug 建置本機實測約 34 萬），每次執行都不一樣；用 Thread Sanitizer 會回報 `Data race detected` |
| 修正版 | 實際是 1000000，每次都一樣（每次呼叫要 `await` 排隊，比 Bug 版慢，這是 actor 的成本） |

**用 Thread Sanitizer 看**：Edit Scheme › Run › Diagnostics 勾選 Thread Sanitizer，從 Xcode 執行 Bug 版，Issues navigator 會出現 Data race detected，指出兩條執行緒同時存取 `count`。

**為什麼**：`count += 1` 其實是「讀、加一、寫回」三步，多個執行緒同時做，會有人把別人剛寫的值蓋掉。`@unchecked Sendable` 只是向編譯器保證「同步由我自己負責」，不是真的保護。actor 一次只讓一個呼叫進去，外部要 `await` 排隊，不需要自己加鎖。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L01_DataRace.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L01_DataRace.swift#fixed
```

### 演練 2：連點兩次提領，餘額變成負的（第 2 題）

**檔案**：`L02_Reentrancy.swift`

**情境**：提領 80 円的流程是「檢查可用餘額 → 向伺服器確認（`await`）→ 扣款」。使用者連點兩次，兩個提領幾乎同時進來，餘額 100。

**怎麼寫**

1. **Bug 版**：`actor Account` 的 `withdraw`：先檢查餘額夠不夠，再 `await Task.sleep`（模擬向伺服器確認），然後才扣款。
2. **修正版**：檢查餘額後，在 `await` 之前就先扣款（保留額度），再 `await Task.sleep`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 兩次提領 80，成功 2 筆；最後餘額：-60 |
| 修正版 | 兩次提領 80，成功 1 筆；最後餘額：20 |

**為什麼**：actor 保證兩個 `await` 之間的程式碼不會被打斷，但不保證整個方法不被插隊。第一個呼叫在 `await` 暫停、讓出 actor 的時候，第二個呼叫進來，看到的餘額還是 100，也通過檢查。在 `await` 之前就先改好狀態，後面的呼叫看到的就是最新的值。另一種修法是 `await` 之後重新檢查一次。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L02_Reentrancy.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L02_Reentrancy.swift#fixed
```

### 演練 3：同一個價格被下載了三次（第 2 題）

**檔案**：`L03_ReentrantCache.swift`

**情境**：價格快取是 actor。首頁、下單頁、通知三個地方同時要 BTC/JPY 的價格，快取還是空的，下載要 100 毫秒。

**怎麼寫**

1. **Bug 版**：`price(for:)` 快取裡有就回傳，沒有就 `await network.fetchPrice`，下載完才寫進 `cache`。
2. **修正版**：用 `inFlight` 字典記住正在下載的 Task：快取沒有、`inFlight` 也沒有，就建立 Task，在 `await` 之前先登記；後面進來的呼叫看到有人在下載，就等同一個 Task 的結果。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 三個呼叫都拿到 15000000，但實際送出的下載請求：3 次 |
| 修正版 | 三個呼叫都拿到 15000000，實際送出的下載請求：1 次 |

**為什麼**：第一個呼叫在 `await` 下載的時候，快取還是空的，第二、三個呼叫進來也判斷「沒有快取」，各自再下載一次。把進行中的工作存起來合併，同一件事只做一次。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L03_ReentrantCache.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L03_ReentrantCache.swift#fixed
```

### 演練 4：載入 K 線時畫面卡住（第 3 題）

**檔案**：`L04_MainActorStall.swift`

**情境**：K 線圖的 ViewModel 為了更新畫面狀態，整個標了 `@MainActor`。載入時要解析 40 萬行 K 線資料（重運算）。`StallMeter` 每 10 毫秒在主執行緒記一次時間，記下最長的間隔，也就是畫面被卡住多久。

**怎麼寫**

1. **Bug 版**：`@MainActor` 的 `ChartViewModel.load` 直接呼叫 `sumCloses(lines)`，把結果存進 `total`。
2. **修正版**：寫一個 `@concurrent` 的 `parse(_:)` 呼叫 `sumCloses`；`load` 改成 `async`，`await parse` 之後把結果存進 `total`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 主執行緒最長被卡住幾百毫秒（Debug 建置本機模擬器實測約 430 毫秒），執行的時候畫面卡住 |
| 修正版 | 主執行緒最長只被卡住幾十毫秒以內（實測約 10 毫秒），合計中間價一樣 |

**為什麼**：`@MainActor` 的方法一定在主執行緒執行，裡面的重運算就佔著主執行緒，畫面掉格。重運算放進 `@concurrent`（Swift 6.2；之前用 `nonisolated` 的 async 函式或 `Task.detached`）明確離開主 actor，算完再回到主 actor 更新狀態。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L04_MainActorStall.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L04_MainActorStall.swift#fixed
```

### 演練 5：背景任務改畫面文字，App 當掉（第 3 題）

**檔案**：`L05_BackgroundUI.swift`

**情境**：報價標籤 `StatusLabel` 和 UIKit 的 `UILabel` 一樣，只能在主執行緒修改。ViewModel 用背景任務下載價格，下載完要更新標籤。演練用 `dispatchPrecondition` 模擬 UIKit 的主執行緒檢查。

**怎麼寫**

1. **Bug 版**：`StatusLabel` 沒有任何標註，`setText` 先 `dispatchPrecondition(condition: .onQueue(.main))` 再存文字；背景任務（`Task.detached`）裡直接呼叫 `label.setText`。**從 Xcode 執行**，才看得到當掉的位置。
2. **修正版**：`StatusLabel` 標 `@MainActor`；背景任務用 `await` 呼叫 `label.setText`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | App 當掉，呼叫堆疊最上面是 `_dispatch_assert_queue_fail`，往下找得到 `setText` 裡 `dispatchPrecondition` 那一行（這個版本會當掉，所以自動檢查略過它） |
| 修正版 | 畫面上的文字：BTC/JPY 15000000；更新時在主執行緒：true |

**為什麼**：沒有標 isolation 的 class，編譯器不管你從哪裡呼叫，錯誤要到執行時才爆。標了 `@MainActor` 之後，從背景呼叫必須寫 `await`，編譯器會強迫你回到主 actor；把 `await` 拿掉就是編譯錯誤。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L05_BackgroundUI.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L05_BackgroundUI.swift#fixed
```

### 演練 6：價差算到一半被推播改掉（第 4 題）

**檔案**：`L06_SendableSnapshot.swift`

**情境**：行情推播每次更新買價（`bid`）和賣價（`ask`）。分析任務拿到一份快照，先讀買價，等 0.1 秒（做別的運算）再讀賣價，算價差。就在這中間，推播把買價與賣價都更新了。真正的價差一直是 100。

**怎麼寫**

1. **Bug 版**：`QuoteSnapshot` 是一般的 `final class`，有可變的 `bid`、`ask` 和 `init`。
2. **修正版**：`QuoteSnapshot` 是 `struct`，有可變的 `bid`、`ask`，自動是 `Sendable`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 分析任務算出的價差：600；實際的價差：100 |
| 修正版 | 分析任務算出的價差：100；實際的價差：100 |

**為什麼**：class 傳出去的是同一個物件的參考，分析任務讀到舊的買價和新的賣價。struct 傳出去的是一份複製，推播怎麼改都不影響它。可變的 class 要跨 isolation 傳遞，把 Strict Concurrency Checking 設成 Complete 會出現警告，Swift 6 語言模式則是編譯錯誤：`sending value of non-Sendable type '() async -> ()' risks causing data races`。這時要改成 struct、放進 actor，或自己加鎖；替 class 加上 `@unchecked Sendable` 只是讓訊息消失，bug 還在。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L06_SendableSnapshot.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L06_SendableSnapshot.swift#fixed
```

### 演練 7：升級後 async 函式留在主執行緒，畫面卡住（第 6 題）

**檔案**：`L07_NonsendingVsConcurrent.swift`

**情境**：K 線解析函式 `parseCandles` 是 `nonisolated` 的 async 函式，從 `@MainActor` 的畫面呼叫。升級到 Swift 6.2 並啟用 `NonisolatedNonsendingByDefault`（Xcode 26 的 Approachable Concurrency 會一起打開）之後，`nonisolated` 的 async 函式預設「留在呼叫者的 actor」，不再自動跳到背景。這個專案還沒有啟用新預設，所以 Bug 版要自己寫 `nonisolated(nonsending)` 來模擬。

**怎麼寫**

1. **Bug 版**：`parseCandles` 在 `static func` 前面加 `nonisolated(nonsending)`，回傳解析結果和「是不是在主執行緒」。
2. **修正版**：改加 `@concurrent`，回傳同樣的內容。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第一行（一般 nonisolated、nonsending、@concurrent 的函式在主執行緒嗎）是 `false true false`；解析在主執行緒：true；主執行緒最長被卡住幾百毫秒（實測約 440 毫秒） |
| 修正版 | 第一行同上；解析在主執行緒：false；主執行緒最長只被卡住幾十毫秒以內（實測約 11 毫秒） |

**為什麼**：升級前，`nonisolated` 的 async 函式從主 actor 呼叫時，會自動跳到背景。新預設改成留在呼叫者的 actor，原本靠它離開主執行緒的重運算，升級後會留在主執行緒。需要並行的地方要明確寫 `@concurrent`。想看新預設的效果，在 Build Settings 把 Approachable Concurrency 設成 Yes，第一行的第一個值會從 false 變成 true。

```lab-solution
ios/Solution/Swift/Swift05-Actors/L07_NonsendingVsConcurrent.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift05-Actors/L07_NonsendingVsConcurrent.swift#fixed
```

第 5 題是升級策略題（用 `SWIFT_STRICT_CONCURRENCY` 逐級加嚴、從底層 module 開始），打開設定後的編譯警告和錯誤取決於整個專案的程式，沒有單一可以執行的 bug，沒有演練。想看實際的錯誤訊息，可以在這個專案把 Strict Concurrency Checking 設成 Complete（或 Swift Language Version 設成 6）：演練 6 的 Bug 版會報 `sending value of non-Sendable type … risks causing data races`；演練 1 的 Bug 版把 `@unchecked Sendable` 拿掉，會報 `passing closure as a 'sending' parameter risks causing data races`。

## 常見陷阱

- **以為 actor 等於 mutex。** actor 方法裡只要有 `await`，條件在暫停前後就可能不再成立。檢查和修改之間不要隔著 `await`，或改用前面的三種修法。
- **在 actor 裡 `await` 之後，沿用 `await` 之前讀到的狀態。** 例如先把餘額讀到區域變數，`await` 完再拿來計算，用的是過期的值。
- **整個 ViewModel 不加區分地標 `@MainActor`。** 整個 ViewModel 標 `@MainActor`，結果解析 JSON、排序也在主執行緒，畫面掉格。
- **為了通過編譯，加上 `@unchecked Sendable` 或 `nonisolated(unsafe)`，卻沒有寫下同步機制。** 問題只是被藏起來，會在執行時才發作。
- **升級到 Swift 6.2 的新預設後，沒有檢查原本靠 `nonisolated async` 離開主 actor 的程式。** 它們現在會留在主執行緒。
- **誤以為 `Task {}` 一定在背景。** 在 `@MainActor` 裡建立的 `Task {}` 仍然在主 actor 上執行。

## 面試官想聽到的

1. 能講出 reentrancy 的具體 bug（檢查、await、修改）與三種修法，而不是只背「actor 是 thread-safe」。
2. 清楚 `Sendable` 的規則，以及 `@unchecked Sendable` 什麼時候合理、什麼時候只是在掩蓋問題。
3. 有遷移嚴格並行檢查的策略：由底層 module 開始、逐步加嚴到 `complete`、第三方用 `@preconcurrency import`。
4. 知道 Swift 6.2 的預設 MainActor、`nonisolated(nonsending)` 與 `@concurrent`，並能說明行為改變帶來的風險。
5. 能把自己專案的現況，搭配具體的改善計畫說清楚。

> [!FIELD]
> 在一個社交 App 的 iOS 版重構時導入 Swift Concurrency，依領域拆成多個 actor：聊天、使用者資料、聊天室同步、連結預覽、動態牆預載入，另外有一個 UI 測試用的 gate。Realm 被關在 actor 後面，呼叫端拿到的是複製出來、脫離資料庫的 model，不會直接碰到 Realm 物件；其中一個 actor 是自訂的 global actor，用來序列化聊天資料庫的存取。`@MainActor` 在專案裡大量使用。Android 端對應的做法是用單一執行緒的 dispatcher 包住 Realm 的存取。Token 刷新和聊天室同步則用 `AsyncSemaphore`（Semaphore 套件）排隊。專案的嚴格並行檢查設在 `targeted`，屬於逐步遷移的路線。
> 面試時可以這樣說：「到交易所 App，我會先把行情與訂單的資料模型改成 Sendable 的 struct，單例的可變狀態收進 actor，每一個 `@unchecked` 都寫明同步機制並用 Thread Sanitizer 驗證，再從底層 module 逐步把嚴格檢查開到 `complete`。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 讓存取排隊一個一個來 | actor、global actor | 單執行緒 dispatcher（`limitedParallelism(1)`）、`Mutex` |
| 編譯時的資料競爭檢查 | Swift 6 語言模式（`Sendable`、isolation） | 沒有；靠慣例、`@Volatile`（讓多執行緒看到最新值）、不可變資料、`StateFlow` |
| 主執行緒 | `@MainActor` | `Dispatchers.Main`／`Dispatchers.Main.immediate`、`@MainThread` 註解 |
| 資料跨執行緒傳遞 | `Sendable`、`sending` | 不可變的 data class、`copy` |
| 類似的陷阱 | actor 的 `await` 會讓出 isolation，狀態可能被改掉 | `kotlinx.coroutines` 的 `Mutex` 不可重入（同一個協程再次 `lock` 會卡死）；`withLock` 裡 suspend 時仍然持有鎖；`StateFlow.update { }` 以 CAS（先比對沒變才寫入，否則重試）的方式重試 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 把可變資料圍起來、一次只讓一個呼叫進入的型別 | actor | アクター |
| 這份資料歸哪個 actor 管 | isolation（actor isolation） | アクター隔離 |
| await 暫停時別人可以插隊進來 | reentrancy | リエントランシー |
| 多個執行緒同時存取同一份資料並且有寫入 | data race | データ競合 |
| 可以安全傳到別的 actor／執行緒的標記 | Sendable | Sendable |
| 全域唯一的 actor（例如主執行緒的 `@MainActor`） | global actor | グローバルアクター |
| 編譯器對資料競爭的嚴格檢查 | strict concurrency checking | 厳格な並行性チェック |
