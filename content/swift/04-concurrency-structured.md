---
id: swift-concurrency-structured
title: 結構化並行：async let、TaskGroup、Task 取消，以及怎麼把舊的 callback 接成 async
summary: 面試會從「Task 和 Task.detached 差在哪」一路追問到「取消到底怎麼傳」。這個單元說明子任務為什麼不會比父任務活得久、取消為什麼是「舉旗子請對方自己停」，以及把 callback 接成 async 時最容易犯的三個錯。
priority: P0
day: 1
tags: [async-await, taskgroup, async-let, cancellation, continuation, task]
---

## 重點講解

Swift Concurrency 指的是 Swift 5.5（2021 年，Xcode 13）加入的一組語言功能：`async/await`、`Task`、`actor` 等。它是 iOS 面試的重點，這個單元先講「任務怎麼開、怎麼取消」，下一個單元再講 actor 與資料安全。

這個單元回答六個面試常見問題：

1. 什麼是結構化並行（structured concurrency）？和直接開 `Task {}` 差在哪？
2. `async let` 和 TaskGroup 怎麼選？怎麼限制同時進行的請求數量？
3. `Task {}` 和 `Task.detached` 差在哪？
4. Task 的取消是怎麼運作的？
5. continuation 是什麼？把舊的 callback 接成 `async` 時有哪三種常見誤用？
6. 部署目標是 iOS 15 時，Swift Concurrency 有哪些限制？（比較少考，被問到能答就好）

### 1. 什麼是結構化並行？和直接開 `Task {}` 差在哪？

結構化並行的意思是：**子任務（child task）的生命週期，被建立它的父任務（parent task）「框住」。** 離開父任務的那個區塊之前，所有子任務一定已經結束；父任務被取消時，取消會自動傳給子任務；子任務丟出的錯誤，也會沿著 `try await` 往外傳給父任務。`async let` 和 TaskGroup 就是結構化的。

相反的，`Task {}` 和 `Task.detached {}` 開出來的是**非結構化（unstructured）任務**：它不屬於任何區塊，沒有人等它，也會繼續跑下去，取消、錯誤、收尾都要自己負責。

用一個情境來看：使用者進入資產頁，需要同時取得 ticker 清單、帳戶餘額、未成交委託，三個請求互相獨立。

```swift
// 結構化：三個請求同時進行，三個都回來才繼續往下走
func loadAssetScreen() async throws
    -> (tickers: [Ticker], balance: Balance, orders: [Order]) {
    // 立刻開始，不等結果
    async let tickers = fetchTickers()
    async let balance = fetchBalance() // 立刻開始
    async let orders = fetchOpenOrders() // 立刻開始
    // 在這裡才等結果
    return try await (tickers, balance, orders)
}
// 如果其中一個丟錯：錯誤要等 await 輪到它時才往外拋（前面的會照常等完）；
// 函式因錯誤離開時，還沒完成的會被取消，並等它們結束。
// 想要「一個失敗就立刻取消其他的」，要用 withThrowingTaskGroup
```

```swift
// 非結構化：開出去就不管（fire-and-forget）
func onAppear() {
    // 沒有保存這個 Task：使用者離開畫面後，它還是會繼續跑
    Task { await loadAssets() }
}
```

兩者的比較：

| | 結構化（`async let`、TaskGroup） | 非結構化（`Task {}`、`Task.detached`） |
|---|---|---|
| 生命週期 | 不能活得比建立它的區塊久，離開區塊前一定結束 | 有自己的生命週期，沒人等也會繼續跑 |
| 取消 | 父任務被取消，會傳給子任務。`async let`：離開區塊時還沒完成的會被取消，並且等它們結束。TaskGroup：正常結束時會等全部子任務完成，因錯誤離開時才取消其餘的 | 父任務被取消，**不會**傳給它 |
| 錯誤 | 沿著 `try await` 往外拋給父任務 | 存在 Task 裡，要用 `task.value` 或 `task.result` 才讀得到；沒讀就沒人知道出錯了 |

一個容易搞混的細節：`async let` 宣告了但沒有 `await`，它**仍然會執行**，只是在離開區塊時會被取消，並且等它結束。所以不用擔心它跑到區塊外面，但如果裡面有副作用（例如送出請求），要注意它確實有被執行過。

> [!TIP]
> 面試時可以這樣說：「結構化並行的核心保證是子任務不會比父任務活得久，取消和錯誤都沿著這棵樹傳遞。所以我優先用 `async let` 和 TaskGroup；必須用 `Task {}` 的地方，我會保存它的 handle，在適當的時機取消。」

### 2. `async let` 和 TaskGroup 怎麼選？怎麼限制同時進行的請求數量？

選擇的依據有兩個：**要開幾個任務、每個任務回傳的型別一不一樣。**

- **`async let`**：數量在寫程式時就固定，型別可以各不相同。上面進入資產頁的例子就是：一個回傳 `[Ticker]`、一個回傳 `Balance`、一個回傳 `[Order]`。
- **TaskGroup**：數量到執行時才知道（例如依使用者的自選清單決定要抓幾個交易對），而且每個任務回傳的型別要相同。

使用 TaskGroup 要記得兩件事。第一，**`for await` 拿到結果的順序是「完成的順序」**，不是你加入任務的順序：

```swift
let order = await withTaskGroup(of: String.self) { group -> [String] in
    for (name, ms) in [
        ("A", 300), ("B", 100), ("C", 200)
    ] as [(String, UInt64)] {
        group.addTask {
            // 睡 ms 毫秒，模擬網路請求
            try? await Task.sleep(nanoseconds: ms * 1_000_000)
            return name
        }
    }

    var out: [String] = []
    // 誰先做完就先拿到誰
    for await r in group {
        out.append(r)
    }
    return out
}
// 輸出：B C A（B 睡最短，最先完成）
print(order.joined(separator: " "))
```

想要依輸入順序拿到結果，就在回傳值裡一起帶上 index，最後再排序或寫回事先配好位置的陣列。

第二，**錯誤處理**：用 `withThrowingTaskGroup` 時，任何一個子任務丟出的錯誤，會在你 `try await group.next()` 取得它的時候往外拋。區塊因為錯誤而離開時，其餘還沒完成的子任務會被取消，並且等它們結束，所以不會有任務洩漏。

**怎麼限制同時進行的請求數量？** 假設要一次抓幾十個交易對的 ticker，但不能同時發出幾十個請求。做法是「滑動視窗」：先放進 `limit` 個任務，之後每完成一個，就補進下一個。

```swift
func fetchAll(symbols: [String], limit: Int) async throws -> [String: Ticker] {
    try await withThrowingTaskGroup(of: (String, Ticker).self) { group in
        var iterator = symbols.makeIterator()

        // 先放 limit 個任務
        for _ in 0..<limit {
            guard let symbol = iterator.next() else { break }
            group.addTask { (symbol, try await fetchTicker(symbol)) }
        }

        var result: [String: Ticker] = [:]
        // 每完成一個
        while let (symbol, ticker) = try await group.next() {
            result[symbol] = ticker
            // 就補進下一個
            if let next = iterator.next() {
                group.addTask { (next, try await fetchTicker(next)) }
            }
        }
        return result
    }
}
```

為什麼要限制？交易所的公開 REST API 通常有流量限制，例如每個 IP 每秒 10 次請求（實際數字以各家公開 API 文件為準）。一次送出幾十個請求，很容易被擋下來。要注意的是，限制「同時進行的數量」只能避免一次湧出大量請求，不等於「每秒最多 10 次」；如果要嚴格遵守每秒的上限，還需要自己控制發送的間隔。

> [!TIP]
> 面試時可以這樣說：「進頁面要同時抓 ticker、資產、委託，數量固定、型別不同，我用 `async let`。批次抓很多交易對、又有流量限制時，我用限制並行數的 TaskGroup：先放進 N 個，完成一個再補一個。」

### 3. `Task {}` 和 `Task.detached` 差在哪？

兩者都是非結構化任務，差別在於**會不會把「目前的環境」一起帶過去**。

- **`Task {}`** 會繼承三樣東西：目前的 actor isolation（程式屬於哪個 actor；例如在 `@MainActor` 裡開的 `Task {}`，仍然在主執行緒上執行）、priority（優先順序）、task-local 值（綁在 Task 上、會自動傳給底下任務的變數，像是 Task 版的環境變數）。
- **`Task.detached {}`** 不繼承 actor isolation 和 task-local 值：不在任何 actor 上執行、讀 task-local 值只會拿到預設值。過去它常用來把耗時的運算移出主執行緒。
- 優先順序要小心講：`Task.detached` 不會從父任務繼承，但沒有傳 `priority:` 時，執行環境會依目前執行緒的 QoS 決定。實測在 `Task(priority: .userInitiated)` 裡開 `Task.detached`，它的 `Task.currentPriority` 一樣是 `.userInitiated`。需要特定優先順序就明確傳 `priority:`。

```swift
@MainActor
final class PriceViewModel {
    var prices: [Ticker] = []

    func refresh() {
        // 繼承 @MainActor：這段在主執行緒執行
        Task {
            let data = (try? await fetchTickers()) ?? []
            // 可以直接更新畫面用的狀態
            self.prices = data
        }
        // 不繼承：這段不在主執行緒
        Task.detached {
            // 做耗時的運算；要更新畫面就得自己回到 @MainActor
        }
    }
}
```

**最重要的一點：兩者都「不會」跟著父任務被取消。** 外層的 Task 被取消了，裡面用 `Task {}` 開出來的 Task 並不會被取消。所以 `onDisappear` 時需要停掉的 Task，要自己保存 handle 再呼叫 `cancel()`，或是交給 SwiftUI 的 `.task` 修飾器（iOS 15 起可用），它會在畫面消失時自動取消。

版本相關：Swift 6.2（Xcode 26）之後，要把重運算移出主 actor，更好的寫法是 `@concurrent` 函式，不必再用 `Task.detached`。下一個單元會詳細說明。

> [!TIP]
> 面試時可以這樣說：「`Task {}` 會繼承目前的 actor、priority 和 task-local 值，所以在 `@MainActor` 裡開的 Task 仍然在主執行緒；`Task.detached` 不繼承 actor 和 task-local 值，優先順序沒指定時看當下執行緒的 QoS。兩者都不會跟著父任務一起被取消，所以我會保存 Task 或用 `.task` 管理。」

### 4. Task 的取消是怎麼運作的？

Swift 的取消是**協作式**（cooperative）的：呼叫 `task.cancel()` 只是在 Task 上「舉一支旗子」，告訴它「請停下來」，**並不會強制中斷程式**。真正會不會停，要看程式裡有沒有人去檢查這支旗子。

檢查的方式有兩種：

- `Task.isCancelled`：回傳 Bool，你自己決定要做什麼。
- `try Task.checkCancellation()`：已被取消就丟出 `CancellationError`。

系統提供的 `Task.sleep`、`URLSession` 的 async API 內建會檢查旗子，被取消就丟錯。但自己寫的耗時迴圈不會自動檢查，要自己加：

```swift
func parsePrices(_ lines: [String]) async throws -> [Decimal] {
    var result: [Decimal] = []
    for line in lines {
        // 每一圈檢查一次旗子；已被取消就丟 CancellationError
        try Task.checkCancellation()
        result.append(Decimal(string: line) ?? 0)
    }
    return result
}
```

兩個容易誤解的地方，用一段程式來看：

```swift
let task = Task {
    do {
        // 睡一秒
        try await Task.sleep(nanoseconds: 1_000_000_000)
        print("done")
    } catch {
        print("cancelled:", error is CancellationError)
    }
    print("isCancelled:", Task.isCancelled)
}
task.cancel() // 馬上舉旗子
await task.value
// 輸出：cancelled: true
// 輸出：isCancelled: true
```

- `Task.sleep` 發現旗子已經舉起，**立刻丟出** `CancellationError`，不會真的睡滿一秒，所以不會印 `done`。
- `catch` 處理完錯誤後，旗子**仍然是舉起的**。取消的狀態不會因為你處理了錯誤就被清除，之後再呼叫 `Task.sleep` 還是會立刻丟錯。所以取消後如果還需要 `await` 做清理，要放在一個新的 `Task {}` 裡（新 Task 沒有舉旗子）。

**如果底層是舊的 API 該怎麼辦？** 例如你把一個 `URLSessionDataTask` 包成 async 函式，Task 被取消時，需要把底層的請求也取消。這時用 `withTaskCancellationHandler(operation:onCancel:)`。它的行為有兩個重點：`onCancel` 在**取消發生的那一刻，立刻**被呼叫；而且它和 `operation` 是**同時**在執行的。所以 `onCancel` 是同步的 closure，不能 `await`，只適合做「同步而且多執行緒同時呼叫也安全」的事，例如呼叫 `URLSessionTask.cancel()`。

```swift
import Foundation

func download(_ request: URLRequest) async throws -> Data {
    let box = TaskBox()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
            let task = URLSession.shared.dataTask(
                with: request
            ) { data, _, error in
                if let data {
                    continuation.resume(returning: data)
                } else {
                    continuation.resume(throwing: error ?? URLError(.unknown))
                }
            }
            // 如果已經被取消了，這裡會立刻取消這個請求
            box.set(task)
            task.resume()
        }
    } onCancel: {
        // 取消請求；請求以「已取消」的錯誤結束，continuation 因此會被 resume
        box.cancel()
    }
}

// TaskBox 用鎖保護，因為 onCancel 和 operation 可能同時存取它。
// @unchecked Sendable 的意思是：「同步由我自己負責，編譯器不用檢查」，
// 下一個單元會說明
final class TaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionTask?
    private var cancelled = false

    func set(_ t: URLSessionTask) {
        lock.lock()
        defer {
            lock.unlock()
        }
        if cancelled {
            t.cancel()
        } else {
            task = t
        }
    }

    func cancel() {
        lock.lock()
        defer {
            lock.unlock()
        }
        cancelled = true
        task?.cancel()
    }
}
```

（實務上直接用 `URLSession.data(for:)` 的 async 版本就好，它已經處理好取消。這個範例是在說明包裝舊 API 時要怎麼寫。）

**取消之後的錯誤要怎麼處理？** 使用者快速切換頁面或交易對，導致舊請求被取消，這不是真正的錯誤，不該彈出錯誤對話框。要靜默處理：除了 `CancellationError`，`URLSession` 的 async API 被取消時丟的是 `URLError` 的 `.cancelled`，兩種都要判斷。

下單頁切換交易對的標準做法：

```swift
// SwiftUI：symbol 改變時，框架自動取消舊的 Task，並用新的 symbol 重新開始
// symbol 要是 @State 或 @Binding（例如 @Binding var symbol: String），
// 下面的 guard 才讀得到最新的值
.task(id: symbol) {
    let requested = symbol
    do {
        let depth = try await api.depth(requested)
        // 回應回來時 symbol 已經換了：丟掉，避免蓋掉新資料
        guard requested == symbol else { return }
        book = depth
    } catch is CancellationError {
    } catch let error as URLError where error.code == .cancelled {
    } catch {
        showError(error)
    }
}
```

為什麼回應回來之後還要再確認 symbol？因為取消不保證「沒有還在路上的回應」：舊請求可能在被取消之前就已經回來了。UIKit 版本則是保存 Task 的 handle，切換前先 `cancel()` 舊的，再開新的。

> [!TIP]
> 面試時可以這樣說：「Swift 的取消是協作式的，`cancel()` 只是舉旗子，要靠 `Task.isCancelled`、`checkCancellation`，或會檢查取消的 API（像 `Task.sleep`）才會真的停。包舊的 callback API 時，用 `withTaskCancellationHandler` 把取消傳給底層請求。切換交易對時，我用 `.task(id: symbol)`，回應回來時再確認 symbol 沒有變。」

### 5. continuation 是什麼？把舊的 callback 接成 `async` 時有哪三種常見誤用？

continuation 是把舊的 completion handler（callback）式 API 接成 `async` 函式的「接頭」。你在 callback 裡呼叫 `resume`，被 `await` 卡住的那一行才會繼續往下跑。

```swift
// 舊 API：用 completion handler 回傳結果
final class LegacyClient: @unchecked Sendable {
    func fetchPrice(
        _ symbol: String,
        completion: @escaping @Sendable (Result<Decimal, Error>) -> Void
    ) { /* ... */ }
}

let legacyClient = LegacyClient()

// 包成 async 函式
func fetchPrice(symbol: String) async throws -> Decimal {
    try await withCheckedThrowingContinuation { continuation in
        legacyClient.fetchPrice(symbol) { result in
            // callback 回來時 resume，上面的 await 才會繼續
            continuation.resume(with: result)
        }
    }
}
```

唯一的規則是：**`resume` 必須剛好呼叫一次。** 違反這條規則有三種情形：

1. **resume 兩次**：`withCheckedContinuation` 這個「Checked」版本會在執行時偵測到並讓程式 crash，訊息是 `SWIFT TASK CONTINUATION MISUSE: … tried to resume its continuation more than once`。如果用不檢查的 `withUnsafeContinuation`，結果是不可預期的。所以正式環境用 Checked 版本，只有在 Instruments 證明檢查本身是瓶頸時才改用 Unsafe。
2. **沒有 resume**：呼叫端會永遠等下去，這個 Task 也永遠不會結束（洩漏）。Checked 版本會在 continuation 被釋放時印出警告。常見原因是 callback 的某個分支（例如錯誤路徑）忘了呼叫 `resume`。
3. **用在會多次回呼的 API**：例如 delegate 每次價格更新都會呼叫一次、位置更新、WebSocket 訊息。這類「串流」只能 resume 一次，第二次會 crash。應該改用 `AsyncStream`（下下個單元說明）。

另外，取消時 callback 可能永遠不會被呼叫，所以包裝可以被取消的請求時，要搭配前面說的 `withTaskCancellationHandler` 取消底層請求，並確保 callback 仍然會被呼叫，或在 `onCancel` 裡以執行緒安全的方式 resume 一次。一個實用的習慣是：callback 的每個分支都只有一個出口，或先把結果轉成 `Result`，只在最後 resume 一次。

> [!TIP]
> 面試時可以這樣說：「continuation 就是把 callback 接成 async 的接頭，規則是 resume 剛好一次：兩次會 crash、零次會讓呼叫端永遠等不到。如果 API 會多次回呼，例如 delegate 或 WebSocket，我不用 continuation，改用 `AsyncStream`。」

### 6. 部署目標是 iOS 15 時，Swift Concurrency 有哪些限制？（比較少考，被問到能答就好）

許多正式上線的 App 最低支援版本仍是 iOS 15，所以這題有實際意義。

- Swift Concurrency 的 runtime 可以向下部署到 iOS 13（Xcode 13.2 起）。所以 `async/await`、`Task`、TaskGroup、`AsyncStream` 在 iOS 15 都可以用。
- 比較新的 API 要查最低版本：例如 `Task.sleep(for:)` 和 `Clock` 需要 iOS 16；用 `Task.sleep(nanoseconds:)` 在 iOS 15 最保險。SwiftUI 的 `.task` 修飾器從 iOS 15 開始有。

> [!TIP]
> 面試時可以這樣說：「Swift Concurrency 的 runtime 可以向下部署到 iOS 13，所以 iOS 15 的專案可以用 `async/await`、TaskGroup 和 `AsyncStream`。要小心的是比較新的 API，例如 `Task.sleep(for:)` 要 iOS 16，我會用 `Task.sleep(nanoseconds:)`。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift04StructuredConcurrency**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift04-StructuredConcurrency/`，解答在 `labs/ios/Solution/Swift/Swift04-StructuredConcurrency/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **會當掉的演練**：演練 8 的 Bug 版寫對了會讓 App 當掉（畫面上會先提醒），要從 Xcode 執行才看得到當掉的位置。

### 演練 1：離開畫面後，輪詢還在繼續（第 1 題）

**檔案**：`L01_TaskLeak.swift`

**情境**：行情畫面出現時開始每 0.1 秒輪詢一次價格（最多 30 次）。使用者看了約 0.35 秒就離開畫面。

**怎麼寫**

1. **Bug 版**：`onAppear` 用 `Task { }` 開輪詢：最多 30 輪，每輪先 `polls.hit()`，再睡 0.1 秒（睡的時候被取消就結束）。**不要**保存這個 Task。
2. **修正版**：輪詢和 Bug 版一樣（已寫好），改成把 Task 存進 `task` 屬性，`onDisappear` 時呼叫 `cancel()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 離開畫面時輪詢約 4 次；離開 1 秒後又輪詢了約 9 次，一直到 30 次輪詢完才停 |
| 修正版 | 離開畫面時輪詢約 4 次；離開 1 秒後又輪詢了 0 次 |

**為什麼**：`Task { }` 是非結構化任務，沒有人等它，離開畫面也不會自動停，還抓著畫面的物件。保存它的 handle，在適當時機 `cancel()`，`Task.sleep` 發現旗子舉起來就丟錯，輪詢跟著結束。SwiftUI 的 `.task { }` 會在畫面消失時自動做這件事。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L01_TaskLeak.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L01_TaskLeak.swift#fixed
```

### 演練 2：30 個請求同時送出，被交易所擋下（第 2 題）

**檔案**：`L02_TaskGroupLimit.swift`

**情境**：自選清單有 30 個交易對，要各抓一次 ticker。交易所的公開 API 有流量限制，不能同時送出幾十個請求。

**怎麼寫**

1. **Bug 版**：`fetchAll` 用 `withTaskGroup`，一口氣把 30 個交易對都 `addTask`，再用 `for await` 把結果收進字典。
2. **修正版**：滑動視窗：先放進 `limit` 個任務，用 `group.next()` 每收到一個結果就補進下一個，直到全部抓完。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 收到 30 筆行情，同時進行的請求最多 30 個 |
| 修正版 | 收到 30 筆行情，同時進行的請求最多 5 個 |

**為什麼**：TaskGroup 會把加進去的任務全部同時開始。限制同時進行的數量，只能避免一次湧出大量請求，不等於「每秒最多 N 次」；要嚴格遵守每秒的上限，還需要自己控制發送的間隔。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L02_TaskGroupLimit.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L02_TaskGroupLimit.swift#fixed
```

### 演練 3：BTC 顯示成 ETH 的價格（第 2 題）

**檔案**：`L03_TaskGroupOrder.swift`

**情境**：自選清單 BTC、ETH、XRP 同時抓價格。ETH 的回應最快（100 毫秒），XRP 次之（200 毫秒），BTC 最慢（300 毫秒）。

**怎麼寫**

1. **Bug 版**：用 `withTaskGroup(of: Double.self)` 同時抓三個價格，`for await` 依序放進陣列，再用 `zip(symbols, prices)` 配對印出。
2. **修正版**：任務回傳 `(交易對, 價格)`，收進以交易對當 key 的字典，再依 `symbols` 的順序印出。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `BTC/JPY：400000`、`ETH/JPY：80`、`XRP/JPY：15000000`，全部配錯 |
| 修正版 | `BTC/JPY：15000000`、`ETH/JPY：400000`、`XRP/JPY：80` |

**為什麼**：`for await` 拿到結果的順序是「完成的順序」，不是加入任務的順序。要依輸入順序，就在回傳值裡一起帶上識別資料（交易對或 index）。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L03_TaskGroupOrder.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L03_TaskGroupOrder.swift#fixed
```

### 演練 4：下單的子任務讀不到 requestID（第 3 題）

**檔案**：`L04_DetachedInherit.swift`

**情境**：使用者按下「下單」。App 為這一筆委託設定一個 requestID（task-local 值：綁在 Task 上、會自動傳給底下任務的變數），`@MainActor` 的 ViewModel 開一個子任務送出委託，送出時要帶上 requestID。

**怎麼寫**

1. **Bug 版**：`submit` 裡用 `Task.detached` 開子任務，印出子任務裡的 `RequestContext.id` 與是否在主執行緒。
2. **修正版**：改用 `Task { }` 開子任務，印出同樣的內容。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 按下下單時 `requestID = ORD-1001`；子任務裡 `requestID = none`，在主執行緒：false |
| 修正版 | 子任務裡 `requestID = ORD-1001`，在主執行緒：true |

**為什麼**：`Task { }` 會繼承目前的 actor 和 task-local 值；`Task.detached` 什麼都不繼承，所以讀到預設值 none，也不在主 actor 上。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L04_DetachedInherit.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L04_DetachedInherit.swift#fixed
```

### 演練 5：按了取消，迴圈還是跑完全部（第 4 題）

**檔案**：`L05_CancelLoop.swift`

**情境**：匯入 20 萬行歷史價格的 CSV，逐行解析。解析到一千行左右，使用者按下「取消」。

**怎麼寫**

1. **Bug 版**：`parsePrices` 逐行用 `Decimal(string:)` 解析（失敗當 0）放進結果，每行呼叫 `progress.hit()`。迴圈裡不檢查取消。
2. **修正版**：迴圈每一圈先呼叫 `try Task.checkCancellation()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 取消後，已解析 200000 / 200000 行：整個迴圈跑完 |
| 修正版 | 取消後只解析了幾千行（本機實測 3360 行） |

**為什麼**：取消是協作式的，`cancel()` 只是舉旗子。這個迴圈裡只有一般的計算，沒有任何會檢查旗子的地方（`Task.sleep` 這類 API 才會自動檢查），所以要自己在每一圈檢查。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L05_CancelLoop.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L05_CancelLoop.swift#fixed
```

### 演練 6：取消下單後，委託還掛在交易所（第 4 題）

**檔案**：`L06_CancelCleanup.swift`

**情境**：下單流程送出委託後，等交易所確認（300 毫秒）。使用者在等的時候按取消，流程要把已經送出的委託撤掉（撤單 API 要 50 毫秒，裡面用 `Task.sleep`）。

**怎麼寫**

1. **Bug 版**：在 `catch is CancellationError` 裡直接 `try? await exchange.cancelOrder()`。
2. **修正版**：撤單放進新的 `Task { }`，再 `await` 它的 `value`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 撤單請求被呼叫 1 次，但取消流程結束後，交易所的委託：仍掛在交易所 |
| 修正版 | 撤單請求被呼叫 1 次，取消流程結束後，交易所的委託：已撤單 |

**為什麼**：`catch` 處理完錯誤之後，Task 的取消旗子仍然是舉起的。撤單 API 裡的 `Task.sleep` 一看到旗子就立刻丟出 `CancellationError`，撤單根本沒做。新的 `Task { }` 沒有被取消，可以正常 `await`。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L06_CancelCleanup.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L06_CancelCleanup.swift#fixed
```

### 演練 7：切換到 ETH，畫面卻顯示 BTC 的深度（第 4 題）

**檔案**：`L07_StaleResponse.swift`

**情境**：下單頁可以切換交易對，切換時重新抓訂單簿深度。BTC 的回應要 300 毫秒，ETH 只要 100 毫秒。使用者先選 BTC，50 毫秒後改選 ETH。

**怎麼寫**

1. **Bug 版**：`select` 每次開一個新 Task 抓深度，抓到就寫進 `shown`。
2. **修正版**：`select` 先取消舊的 Task、記下 `selected`，再開新 Task 抓深度；抓到之後確認 `symbol == selected` 才寫進 `shown`。這就是 SwiftUI `.task(id: symbol)` 加上回應確認的手寫版。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 選擇的交易對：ETH/JPY，畫面顯示：BTC/JPY 的深度 |
| 修正版 | 選擇的交易對：ETH/JPY，畫面顯示：ETH/JPY 的深度 |

**為什麼**：ETH 的回應先到、BTC 的舊回應後到，後到的把畫面蓋掉。取消舊的請求讓它不再回來；萬一回應已經在路上，回來時再確認一次交易對，不符合就丟掉。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L07_StaleResponse.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L07_StaleResponse.swift#fixed
```

### 演練 8：第二筆價格進來，App 就當掉（第 5 題）

**檔案**：`L08_ContinuationTwice.swift`

**情境**：舊的價格連線（`PriceFeedClient`）每 0.1 秒呼叫一次 `onPrice`，連續三筆。想把它包成 async 函式。

**怎麼寫**

1. **Bug 版**：`nextPrice` 用 `withCheckedContinuation` 包起來：`client.onPrice` 裡 `resume(returning:)`，再 `client.start()`。**從 Xcode 執行**，才看得到當掉的位置。
2. **修正版**：`prices(from:)` 回傳 `AsyncStream<Int>`：`onPrice` 裡 `yield`，`onTermination` 時 `client.stop()`，再 `client.start()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 收到價格 15000000 之後約 0.1 秒 App 當掉，主控台：`SWIFT TASK CONTINUATION MISUSE: … tried to resume its continuation more than once`（這個版本會當掉，所以自動檢查略過它） |
| 修正版 | 依序收到 15000000、15000100、15000200，最後印出「跑到這裡代表沒有當掉」 |

**為什麼**：continuation 只能 resume 一次，`onPrice` 卻會一直被呼叫。Checked 版本在第二次 resume 時直接讓程式當掉。會多次回呼的 API（delegate、WebSocket 訊息）要改用 `AsyncStream`。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L08_ContinuationTwice.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L08_ContinuationTwice.swift#fixed
```

### 演練 9：查詢失敗時，呼叫端永遠在等（第 5 題）

**檔案**：`L09_ContinuationNever.swift`

**情境**：舊的價格查詢 API 用 completion handler 回傳：成功給價格，失敗（例如交易對不存在）給錯誤。

**怎麼寫**

1. **Bug 版**：`fetchPrice` 用 `withCheckedThrowingContinuation` 包 `api.fetch`，只在成功（`.success`）時 `resume(returning:)`，失敗的分支不處理。
2. **修正版**：callback 裡把 `result` 直接 resume 出去（`resume(with:)`），成功、失敗都 resume 一次。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 查詢不存在的交易對，等了 1 秒：呼叫端還在等（那個 Task 永遠不會結束，是洩漏）；主控台印出 `leaked its continuation without resuming it` |
| 修正版 | 查詢不存在的交易對，等了 1 秒：錯誤（交易對不存在） |

**為什麼**：沒有 resume 的 continuation，會讓被 `await` 卡住的那一行永遠等下去。callback 的每個分支都只能有一個出口，或先轉成 `Result`，最後只 resume 一次。

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L09_ContinuationNever.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift04-StructuredConcurrency/L09_ContinuationNever.swift#fixed
```

第 6 題是部署目標（iOS 15 能用哪些 API）的版本查詢題，沒有可以執行的 bug，沒有演練。

## 常見陷阱

- **以為 `await` 之後會換執行緒。** `await` 只是「可能暫停」的位置。暫停之後在哪個執行緒繼續跑，由 executor（決定程式在哪個執行緒繼續的排程者）決定。需要確定在主執行緒執行，就標 `@MainActor`。
- **用 `Task {}` fire-and-forget（開出去就不管），然後忘了取消。** 畫面離開之後它還在跑，還抓著 `self`。至少要保存 handle，或交給 `.task`。
- **用 `DispatchSemaphore` 在同步程式裡等 async 的結果。** Swift Concurrency 共用一組執行緒（cooperative thread pool，數量大約等於 CPU 核心數），用 semaphore 把其中一條卡住，容易造成死結或所有任務都在等執行緒。不要在 async 的世界裡阻塞執行緒。
- **取消之後還寫入畫面或狀態。** `catch` 到 `CancellationError`（或 `URLError.cancelled`）時，通常要靜默處理，不要彈出錯誤。
- **把 `Task.detached` 當成預設。** 它會丟掉 actor 和 task-local 值，優先順序沒指定時看當下執行緒的 QoS，不一定是你以為的預設值。大部分情況用 `Task {}`，搭配 `nonisolated` 函式，或 Swift 6.2 之後的 `@concurrent` 函式就夠了。
- **以為宣告了 `async let` 卻沒使用，它就不會執行。** 它仍然會執行，到區塊結束時才被取消並等待；裡面有副作用時要特別注意。
- **continuation 在 callback 的多個分支都可能被 resume。** 用單一出口，或先轉成 `Result`，只 resume 一次。

## 面試官想聽到的

1. 結構化並行的核心保證：子任務不會比父任務活得久，取消與錯誤沿著任務樹傳遞。
2. 取消是協作式的：能說出 `isCancelled`、`checkCancellation`、`withTaskCancellationHandler` 各自適合的場景，以及 `onCancel` 會和 operation 同時執行，所以只能做同步而且執行緒安全的事。
3. `Task {}` 與 `Task.detached` 各自繼承什麼、不繼承什麼，以及兩者都不會被父任務取消。
4. 能寫出限制並行數的 TaskGroup，並說明為什麼要限制（API 流量限制、記憶體、後端壓力）。
5. 會正確把 callback API 包成 async，知道 continuation 的三種誤用，以及多次回呼要改用 `AsyncStream`。

> [!FIELD]
> 在一個社交 App 的 iOS 版重構時，導入了 Swift Concurrency 與 Combine，原本的 Rx 與新的 async 程式需要互相橋接。網路層同時做了兩個方向：把 async 函式包成 RxSwift 的 `Single`，在 `Single.create` 裡開 `Task`，並在 `Disposables.create` 裡呼叫 `task.cancel()`，讓 Rx 這邊被取消時也會取消 Task；反方向則是用 `withCheckedContinuation` 把 Moya 的 callback 包成 async。建立錢包的流程裡，輔助類別會保存目前的 Task，在 `sleep` 之後檢查 `Task.isCancelled`。在一個即時行情 App 裡，SSE（Server-Sent Events，伺服器單向推送）client 也會在 Task 裡檢查 `Task.isCancelled`。
> 面試時可以這樣說：「橋接 Rx 和 async 時，我特別注意取消要雙向傳遞。到交易所 App，進入頁面時並行抓 ticker、資產、委託，我會用 `async let` 表達；批次請求用限制並行數的 TaskGroup；切換交易對交給 `.task(id:)`。」

## iOS / Android 對照

| 主題 | iOS（Swift Concurrency） | Android（Kotlin coroutines） |
|---|---|---|
| 結構化並行 | `async let`、TaskGroup | `coroutineScope { async { } }`、`supervisorScope` |
| 非結構化 | `Task {}`、`Task.detached` | `GlobalScope.launch`（不建議）、自己建立的 `CoroutineScope` |
| 取消 | 協作式；`isCancelled`／`checkCancellation` | 協作式；`isActive`／`ensureActive()`，suspend 函式內建檢查，`CancellationException` 不可以吞掉 |
| 取消的傳遞 | 父傳給子；`Task {}` 開出來的不繼承 | Job 組成的樹；子任務失敗預設會取消兄弟任務和父任務（`supervisorScope` 可以隔離） |
| callback 轉 async | `withCheckedContinuation` | `suspendCancellableCoroutine`（內建 `invokeOnCancellation`） |
| 綁定畫面的生命週期 | SwiftUI `.task`、手動保存 Task | `viewModelScope`、`lifecycleScope`、`repeatOnLifecycle` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 子任務被父任務框住的並行寫法 | structured concurrency | 構造化された並行処理 |
| 一組同時進行的子任務 | TaskGroup | タスクグループ |
| 舉旗子請對方自己停的取消方式 | cooperative cancellation | 協調的キャンセル |
| `await` 的位置，函式可能在這裡暫停 | suspension point | 中断点 |
| 開出去就不管 | fire-and-forget | 投げっぱなし |
| 把 callback 接成 async 的接頭 | continuation | コンティニュエーション |
| 流量限制 | rate limit | レート制限 |
