---
id: swift-asyncsequence-combine
title: AsyncStream 與 Combine：行情串流怎麼送到畫面、怎麼只留最新價，以及兩者怎麼選
summary: 交易所 App 的核心之一，是把每秒更新很多次的行情安全地送到畫面。這個單元說明 AsyncSequence／AsyncStream 的用法（來不及處理時資料怎麼存、怎麼把 callback 包進來並確實退訂），以及 Combine 常用運算子的真實行為，最後整理三種資料流工具怎麼選。
priority: P0
day: 1
tags: [asyncstream, asyncsequence, combine, conflation, throttle, share]
---

## 重點講解

交易所 App 的行情畫面有一個共同的難題：伺服器每秒推送幾十次價格，畫面卻不需要、也畫不了那麼快。這個單元用「行情怎麼從 WebSocket 送到畫面」這條管線，串起 `AsyncStream` 和 Combine：

```text
WebSocket 收到資料 → 解碼 → AsyncStream（來不及處理時只留最新一筆）→ 固定頻率（約 100–250 ms）→ 更新畫面
```

這個單元回答五個面試常見問題：

1. `AsyncSequence` 和 `AsyncStream` 是什麼？和 Combine 的 publisher 差在哪？
2. 行情來得比畫面處理得快時怎麼辦？（buffering policy 與 conflation）
3. 怎麼把 callback 風格的 WebSocket 包成 `AsyncStream`，並且確實退訂？
4. Combine 的 `debounce`、`throttle`、`share`、`switchToLatest` 實際上怎麼運作？
5. Combine、RxSwift、async/await 怎麼選？

### 1. `AsyncSequence` 和 `AsyncStream` 是什麼？和 Combine 的 publisher 差在哪？

`AsyncSequence` 是「一筆一筆慢慢送來的資料」。它和一般的 `Sequence` 很像，差別在取下一筆的動作是 async 的：資料還沒到，就暫停等待（不佔用執行緒），到了再繼續。讀取時用 `for await` 迴圈。

`AsyncStream` 是標準函式庫提供的 `AsyncSequence` 實作，由你自己用 `yield` 把值丟進去，用 `finish()` 結束。最常用來把 callback、delegate、WebSocket 包成可以 `for await` 的形式。Swift 5.5（iOS 13 起可向下部署，Xcode 13.2 以上）就有；`AsyncStream.makeStream(of:)` 這個同時拿到 stream 與 continuation 的寫法，要 Swift 5.9 以上。

```swift
// makeStream：同時拿到「讀取端 stream」和「寫入端 continuation」，
// 適合生產者和消費者在不同地方
let (stream, continuation) = AsyncStream.makeStream(of: Int.self)
continuation.yield(1) // 生產者丟一筆
continuation.yield(2)
continuation.finish() // 沒有更多資料了

// 消費者一筆一筆讀，stream 結束時迴圈自然結束
for await value in stream {
    print(value) // 輸出：1，然後是 2
}
```

會失敗的來源（例如網路串流）用 `AsyncThrowingStream`，結束時 `finish(throwing: error)`，讀取時用 `for try await`。

**和 Combine 的 publisher 最根本的差別：誰主動。** Combine 是 push（推）：publisher 有資料就推給訂閱者的 `sink`。`AsyncSequence` 是 pull（拉）：消費者準備好了，才去要下一筆。這個差別決定了「消費者比生產者慢」時的處理：`AsyncStream` 必須決定來不及讀的資料要存哪裡、存多少，這就是下一節的 buffering policy。

另外，標準函式庫的 `AsyncSequence` 只有基本的 `map`、`filter` 等。像 `debounce`、`throttle`、`merge`、`combineLatest` 這類運算子，在 Apple 另外維護的 swift-async-algorithms 套件裡。

> [!TIP]
> 面試時可以這樣說：「`AsyncSequence` 是拉的模型，用 `for await` 一筆一筆讀；`AsyncStream` 讓我把 callback 或 WebSocket 包成這種形式。Combine 是推的模型，運算子比較完整。請求回應我用 async/await，持續的串流用 `AsyncStream`。」

### 2. 行情來得比畫面處理得快時怎麼辦？

先講兩個名詞：

- **buffering policy**：消費者讀得比生產者慢時，多出來的資料要怎麼存。`AsyncStream` 建立時一定要決定這件事。
- **conflation**：資料來得比畫面處理得快時，把中間的舊值丟掉，只留最新的一筆。行情顯示最常用，因為使用者只在意「現在的價格」。

`AsyncStream` 有三種 buffering policy：

| policy | 行為 | 適用 |
|---|---|---|
| `.unbounded`（預設） | 全部緩衝，記憶體可能無限成長 | 不能掉資料的事件，例如成交回報、訂單狀態 |
| `.bufferingNewest(n)` | 只留最新 n 筆，舊的丟掉 | **行情的 conflation**：`.bufferingNewest(1)` 代表永遠只看到最新價 |
| `.bufferingOldest(n)` | 只留最舊 n 筆，新的丟掉 | 少見，例如只在意最早的事件 |

用一個例子看差異：生產者連續送出 1 到 5，消費者還沒開始讀，之後才開始 `for await`。

```swift
let (stream, continuation) = AsyncStream.makeStream(
    of: Int.self,
    // 緩衝區只放得下 2 筆，新的進來就擠掉最舊的
    bufferingPolicy: .bufferingNewest(2)
)
for i in 1...5 {
    continuation.yield(i)
}
continuation.finish()

var out: [Int] = []
for await v in stream {
    out.append(v)
}
print(out) // 輸出：[4, 5]
// 換成 .bufferingOldest(2)，輸出是 [1, 2]；.unbounded 則是 [1, 2, 3, 4, 5]
```

`yield` 會回傳結果：`.enqueued(remaining:)`（成功放進去了）、`.dropped(element)`（緩衝區滿了，丟掉了某一筆）或 `.terminated`（stream 已經結束）。可以拿來統計丟掉了幾筆。

**conflation 有一個前提：丟掉中間的值，不會影響正確性。** 顯示最新價可以；但 K 線需要累積每分鐘的最高價、最低價、成交量，如果先 conflate 再累積，丟掉的那些 tick 裡可能就有最高點，算出來的數字就是錯的。所以聚合要在 conflate **之前**做。

整理一下哪些資料可以丟、哪些不行：

- **可以丟（只顯示最新）**：ticker 價格、最佳買賣價。用 `.bufferingNewest(1)`。
- **不能丟**：成交回報、訂單狀態。用 `.unbounded`，並搭配序號或 ack（對方的確認回應）來偵測是否漏掉。
- **要先聚合再 conflate**：K 線的高低價、成交量。

最後一個限制：**`AsyncStream` 只支援單一消費者**。官方不支援多個地方同時對同一個 stream 做 `for await`，它也不是廣播。本機實測，兩個 `for await` 會「分食」元素（一個拿到 1、3、5，另一個拿到 2、4、6）；舊的 runtime 甚至可能 crash，不要依賴這個行為。需要多個訂閱者時，要自己做 fan-out（一個來源分送給多個訂閱者）。

> [!TIP]
> 面試時可以這樣說：「行情我用 `AsyncStream` 搭配 `bufferingNewest(1)` 做 conflation，只留最新價，慢消費者也不會讓記憶體成長。成交回報這種不能丟的，用 `.unbounded` 加序號補漏；K 線的高低價要在 conflate 之前先聚合。」

### 3. 怎麼把 callback 風格的 WebSocket 包成 `AsyncStream`，並且確實退訂？

上一個單元說過，continuation 只能 resume 一次，不適合會一直回呼的 callback。這種情況就用 `AsyncStream` 包：訂閱時把每一筆資料 `yield` 進去，並且**一定要設定 `onTermination` 退訂**。

```swift
import Foundation

struct Tick: Sendable {
    let price: Decimal
}

// 假設的 WebSocket 介面：訂閱一個 topic，會一直回呼；退訂用 token
protocol MarketSocket: Sendable {
    func subscribe(
        _ topic: String,
        handler: @escaping @Sendable (Tick) -> Void
    ) -> UUID
    func unsubscribe(_ token: UUID)
}

func tickStream(topic: String, socket: some MarketSocket) -> AsyncStream<Tick> {
    AsyncStream(
        Tick.self,
        bufferingPolicy: .bufferingNewest(1)
    ) { continuation in
        let token = socket.subscribe(topic) { tick in
            // 每次 callback 回來，就丟進 stream
            continuation.yield(tick)
        }
        continuation.onTermination = { _ in
            // stream 結束時一定要退訂，否則 WebSocket 的 topic 會一直訂閱
            socket.unsubscribe(token)
        }
    }
}
```

幾個要點：

- **為什麼一定要 `onTermination`？** 它在 stream 結束時被呼叫：生產者呼叫了 `finish()`、消費者所在的 Task 被取消，或是 stream 不再被任何地方持有（例如 `for await` 迴圈 `break` 離開之後，沒有變數還留著這個 stream）。沒有在這裡退訂，使用者離開畫面之後，WebSocket 的訂閱與 handler 還是會一直存在（洩漏）。
- **建立 stream 的 closure 會「同步、立刻」執行**，所以訂閱在呼叫 `tickStream` 時就已經發生，不是等到開始 `for await` 才開始。
- **斷線重連、重新訂閱，要放在生產者那一層。** 對消費者來說，stream 應該是「持續到被取消為止」，不需要知道底下重連了幾次。
- 一次性的 callback 用 `withCheckedContinuation`；**會多次回呼的用 `AsyncStream`**。

真實專案裡的例子：一個即時行情 App 的即時 K 線用 SSE（Server-Sent Events，伺服器透過一條 HTTP 連線持續單向推送資料給 App）。iOS 端用 `EventSource` 套件，直接用 `for await event in dataTask.events()` 讀取事件。

> [!TIP]
> 面試時可以這樣說：「會多次回呼的 callback，我用 `AsyncStream` 包，並在 `onTermination` 裡退訂，這樣使用者離開畫面或 Task 被取消時，WebSocket 的 topic 一定會取消訂閱。重連是生產者那一層的事，不暴露給消費者。」

### 4. Combine 的 `debounce`、`throttle`、`share`、`switchToLatest` 實際上怎麼運作？

Combine 是 Apple 的響應式框架（iOS 13 起）。下面這幾個運算子，面試最常被問「實際行為是什麼」。

**`debounce` 和 `throttle` 的差別（行情最常搞混）**

- **`debounce`**：等資料「安靜一段時間」之後，才送出最後一筆。適合搜尋框輸入（使用者停止打字再送出請求）。但用在持續高頻的行情，會**餓死**：只要新資料進來的間隔小於時限，計時就一直被重置，永遠不會送出。
- **`throttle`**：固定間隔最多送一次。行情要用它，並且設 `latest: true`（送出該區間內最新的一筆；`false` 則是區間內第一筆，會顯示較舊的價格）。

```swift
import Combine
import Foundation

struct Quote {
    let price: Decimal
}

let ticks = PassthroughSubject<Quote, Never>()
let uiPrice = ticks
    .map(\.price)
    // 價格沒變就不送
    .removeDuplicates()
    // 每 200 ms 最多一次，送最新價
    .throttle(
        for: .milliseconds(200),
        scheduler: DispatchQueue.main,
        latest: true
    )
```

**`share()`：多個訂閱者共用同一次上游訂閱**，不用每個訂閱者各算一次、各打一次 API。要知道的行為：沒有 `share()` 時，每個訂閱者會各自觸發一次上游的 `map`；有了它，`map` 只執行一次。但它**不會重播**：晚到的訂閱者只收到之後的值，之前的值看不到。需要重播時，用 `multicast` 搭配 `CurrentValueSubject`。

```swift
var mapCalls = 0
let subject = PassthroughSubject<Int, Never>()
let shared = subject
    .map { v -> Int in
        mapCalls += 1
        return v * 2
    }
    .share()

var late: [Int] = []
let c1 = shared.sink { _ in }
let c2 = shared.sink { _ in }
subject.send(1)
subject.send(2)
// 在 send(2) 之後才訂閱
let c3 = shared.sink { late.append($0) }
subject.send(3)
print(mapCalls, late) // 輸出：3 [6]
// map 只執行 3 次（沒有 share 會是 6 次）；晚到的訂閱者只收到 send(3) 的結果 6
```

**`switchToLatest`：新的來了，舊的就取消。** 典型情境：使用者在下單頁快速切換交易對，每切換一次就發一個深度請求。舊交易對的回應可能比新的晚到，把新畫面蓋掉。用 `map` 把交易對轉成請求，再接 `switchToLatest`，新的內層 publisher 一出現，就會取消前一個。

```swift
final class DepthViewModel: ObservableObject {
    @Published var symbol = "btcjpy"
    @Published private(set) var book = Book.empty

    init() {
        $symbol
            .removeDuplicates()
            // 每個 symbol 轉成一個請求
            .map { fetchDepth($0).replaceError(with: Book.empty) }
            // 新的請求出現，就取消前一個
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            // iOS 14 起可用，不會抓住 self
            .assign(to: &$book)
    }
}
```

（`flatMap` 則是讓所有內層 publisher 同時存活，舊的回應仍然會輸出，所以不能用來取代 `switchToLatest`。`flatMap(maxPublishers:)` 可以限制同時執行的數量，`.max(1)` 等於排隊一個一個來。）

async/await 版本的做法是 SwiftUI 的 `.task(id: symbol)`，概念相同：新的一個來，舊的就被取消。

**另外兩個容易踩到的行為：**

- **`receive(on:)` 和 `subscribe(on:)` 的差別**：`receive(on:)` 只影響它「下游」在哪裡接收；`subscribe(on:)` 影響訂閱動作和上游的工作在哪裡執行。另外，`receive(on: DispatchQueue.main)` 就算已經在主執行緒，也會把值 async 排到下一個 run loop（主執行緒的事件迴圈，不斷等事件、處理事件）才送出，也就是晚一拍。本機實測：`send` 之後的程式碼會先執行，值才送達。所以同步的單元測試斷言，可能會因為這一拍的延遲而失敗。
- **`@Published` 是在值「即將」改變時發送（willSet）**：`$x.sink { _ in print(x) }` 收到新值的時候，屬性本身還是舊值。需要新值時，要用 sink 傳進來的參數，不要回頭讀 `self.x`。

最後，Combine 有 `Demand`（背壓：消費者告訴生產者「我來不及了，慢一點」的機制），但 `sink` 預設要求 `.unlimited`（要多少給多少），所以實務上沒有背壓的效果。

> [!TIP]
> 面試時可以這樣說：「行情節流我用 `throttle(latest: true)`，不用 `debounce`，因為 debounce 在持續有資料時永遠不會送出。切換交易對時我用 `switchToLatest` 取消舊請求。`share()` 只共用上游，不會重播給晚到的訂閱者。」

### 5. Combine、RxSwift、async/await 怎麼選？

不同工具擅長的事不一樣，選擇的依據是資料的形狀：

| 面向 | Combine | RxSwift | async/await＋AsyncSequence |
|---|---|---|---|
| 來源 | Apple，iOS 13 起 | 第三方，可以和 Android 的 RxJava 對齊寫法 | 語言內建 |
| 背壓 | 有 Demand，但實務上多半是 unlimited | 沒有原生背壓 | pull 模型（消費者要才給）搭配 buffering policy |
| 運算子 | 中等 | 最完整 | 標準庫很少，要靠 swift-async-algorithms |
| 取消 | `AnyCancellable` | `DisposeBag` | Task 取消 |
| 綁定畫面 | `@Published`（iOS 15／16 唯一內建的方案；iOS 17 之後另有 Observation） | 要自己綁 | 需要 `.task` 或 Task |

選擇的原則：

- **請求—回應**（一次要求、一次回答）：用 async/await。
- **持續的串流**（行情、事件）：`AsyncStream`。
- **複雜的事件組合**（`combineLatest`、`debounce`、重試）：Combine 或 Rx。
- **需要和 Android 的 RxJava 保持寫法一致**：Rx。
- **綁定畫面狀態**：如果最低支援 iOS 15，`@Published` 還是主要選項（Observation 要 iOS 17 以上）。

最重要的一條：**不要在同一條資料流裡混用三種工具**。如果要橋接，橋接點要明確：Combine publisher 有 `values` 屬性（iOS 15 起），可以取得 `AsyncSequence`；反方向，可以用 Subject，或在 Rx 的 `Single.create` 裡包一個 `Task`。

> [!TIP]
> 面試時可以這樣說：「我依資料的形狀選工具：請求回應用 async/await，持續的串流用 AsyncStream，複雜事件組合用 Combine 或 Rx。不會三種混在同一條資料流，橋接的地方會寫清楚。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift06AsyncStream**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift06-AsyncStream/`，解答在 `labs/ios/Solution/Swift/Swift06-AsyncStream/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **關於時間**：多數演練用 `Task.sleep` 模擬行情的節奏，一次執行大約 0.5 到 2 秒。

### 演練 1：行情送完了，畫面還停在好幾秒前（第 2 題）

**檔案**：`L01_SlowConsumer.swift`

**情境**：行情每 1 毫秒左右送來一筆價格（共 300 筆，編號就是第幾筆），畫面每處理一筆要 5 毫秒。

**怎麼寫**

1. **Bug 版**：`makeTicks()` 回傳 `AsyncStream.makeStream(of: Int.self)`，不指定 `bufferingPolicy`（預設 `.unbounded`）。
2. **修正版**：`makeTicks()` 的 `bufferingPolicy` 指定 `.bufferingNewest(1)`，緩衝區只留最新一筆。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 行情送完 300 筆時，畫面只顯示到第 100 筆左右；畫面落後最新價最多約 200 筆；畫面共處理 300 筆（要花一秒多才追完） |
| 修正版 | 行情送完時，畫面顯示到第 295 筆左右；落後最多只有幾筆；畫面共處理約 100 筆（中間的舊價格被丟掉了） |

**為什麼**：`.unbounded` 把來不及處理的價格全部排隊，消費者一筆一筆慢慢追，畫面顯示的是很久以前的價格，記憶體也隨著積壓成長。`.bufferingNewest(1)` 是 conflation：只留最新一筆，行情是可以丟的資料。成交回報、訂單狀態這種不能丟的，要用 `.unbounded` 並搭配序號偵測漏掉。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L01_SlowConsumer.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L01_SlowConsumer.swift#fixed
```

### 演練 2：K 線最高價比真正的最高價低（第 2 題）

**檔案**：`L02_ConflateBeforeAggregate.swift`

**情境**：一分鐘內連續收到 5 筆成交：價格 100、101、150、102、103，成交量 1、2、3、1、2。畫面要顯示最新價，K 線要算最高價（真正是 150）和成交量合計（真正是 9）。

**怎麼寫**

1. **Bug 版**：把五筆成交都 `yield` 進 `.bufferingNewest(1)` 的 stream，再 `finish()`；消費端用 `for await` 讀出來，邊讀邊算最高價、成交量合計和最新價。
2. **修正版**：每筆成交先累積進最高價與成交量，再把成交價 `yield` 進 `.bufferingNewest(1)` 的 stream；`finish()` 後讀出最新價。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面最新價：103；K 線最高價 103、成交量 2 |
| 修正版 | 畫面最新價：103；K 線最高價 150、成交量 9 |

**為什麼**：消費者還沒開始讀的時候，前四筆已經被擠掉，只剩最後一筆，最高點和前面的成交量都丟了。conflation 的前提是丟掉中間的值不影響正確性；要聚合的資料，聚合必須在 conflate 之前做。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L02_ConflateBeforeAggregate.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L02_ConflateBeforeAggregate.swift#fixed
```

### 演練 3：兩個畫面各只收到一半價格（第 2 題）

**檔案**：`L03_TwoConsumers.swift`

**情境**：同一份價格 stream，報價標籤和走勢圖兩個地方都想收到每一筆價格（每 10 毫秒一筆，共 20 筆）。

**怎麼寫**

1. **Bug 版**：開兩個 Task（報價標籤、走勢圖），各自對同一個 stream 做 `for await`，每收到一筆就 `hit()` 自己的計數器。
2. **修正版**：寫 `Broadcaster`：`subscribe()` 回傳各訂閱者自己的 stream；`run(source:)` 只用一個 Task 讀來源，每筆轉送給所有訂閱者，來源結束時 `finish()` 所有訂閱者。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 報價標籤收到 10 筆、走勢圖收到 10 筆：兩個加起來剛好 20 筆，分食同一個 stream |
| 修正版 | 報價標籤收到 20 筆、走勢圖收到 20 筆 |

**為什麼**：`AsyncStream` 不是廣播，只支援單一消費者。兩個 `for await` 同時等同一個 stream，每一筆只會給其中一個（本機實測是輪流分到）。需要多個訂閱者，要自己做 fan-out（一個來源分送給多個訂閱者）。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L03_TwoConsumers.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L03_TwoConsumers.swift#fixed
```

### 演練 4：離開畫面後，行情的訂閱還在（第 3 題）

**檔案**：`L04_Unsubscribe.swift`

**情境**：行情畫面用 `AsyncStream` 包住 WebSocket 的 btcjpy topic，每收到價格就 `yield`。使用者離開畫面，畫面的 Task 被取消。

**怎麼寫**

1. **Bug 版**：`tickStream` 在 `AsyncStream` 的 closure 裡呼叫 `socket.subscribe(topic)`，handler 裡 `continuation.yield(price)`。不設 `onTermination`。
2. **修正版**：同樣的訂閱，保存回傳的 token，並設定 `continuation.onTermination`，stream 結束時 `socket.unsubscribe(token)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面開著時訂閱數 1；離開畫面後訂閱數仍是 1；又推一筆價格，被送進 handler 的次數：1（洩漏） |
| 修正版 | 畫面開著時訂閱數 1；離開畫面後訂閱數 0；又推一筆價格，被送進 handler 的次數：0 |

**為什麼**：`onTermination` 在 stream 結束時被呼叫：生產者 `finish()`、消費者的 Task 被取消，或 stream 不再被任何地方持有。沒有在這裡退訂，使用者離開畫面之後，WebSocket 的訂閱與 handler 還是會一直存在。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L04_Unsubscribe.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L04_Unsubscribe.swift#fixed
```

### 演練 5：同一個價格 API 被打了兩次（第 4 題）

**檔案**：`L05_ShareUpstream.swift`

**情境**：價格的 publisher 每次被訂閱，就會向伺服器發一次請求（`Deferred`，演練用 `FakeAPI` 計次）。首頁和下單頁各訂閱一次。

**怎麼寫**

1. **Bug 版**：`price` 不加 `share()`；首頁、下單頁各自 `sink`（收到就 `home.hit()`／`order.hit()`），存進 `cancellables`。
2. **修正版**：在 `api.ticker()` 後面接 `share()`，讓兩個訂閱者共用同一次上游訂閱。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 首頁收到 1 次、下單頁收到 1 次；API 被呼叫 2 次 |
| 修正版 | 首頁收到 1 次、下單頁收到 1 次；API 被呼叫 1 次 |

**為什麼**：沒有 `share()` 時，每個訂閱者都會各自觸發一次上游。`share()` 讓多個訂閱者共用同一次上游訂閱，但它不會重播：晚到的訂閱者只收到之後的值，需要重播用 `CurrentValueSubject` 或 `multicast`。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L05_ShareUpstream.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L05_ShareUpstream.swift#fixed
```

### 演練 6：行情一直在跳，畫面卻不更新（第 4 題）

**檔案**：`L06_DebounceStarvation.swift`

**情境**：行情每 50 毫秒送來一筆價格，連續送 1 秒（共 20 筆）。畫面想把更新頻率降到每 200 毫秒一次。

**怎麼寫**

1. **Bug 版**：對 `ticks` 用 `debounce(for: 200 毫秒, scheduler: DispatchQueue.main)`，`sink` 時 `updates.hit()`，存進 `cancellables`。
2. **修正版**：改用 `throttle(for: 200 毫秒, scheduler: DispatchQueue.main, latest: true)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 行情持續 1 秒，畫面更新了 0 次；行情停止後，畫面總共更新了 1 次 |
| 修正版 | 行情持續 1 秒，畫面更新了 6 次（第一筆馬上送，之後每 200 毫秒一次） |

**為什麼**：`debounce` 要等資料「安靜一段時間」才送出最後一筆。行情一直進來，計時一直被重置，永遠送不出去（被餓死）。`throttle` 固定間隔最多送一次，`latest: true` 送出該區間最新的一筆。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L06_DebounceStarvation.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L06_DebounceStarvation.swift#fixed
```

### 演練 7：切換到 ETH，畫面被舊的 BTC 蓋掉（第 4 題）

**檔案**：`L07_SwitchToLatest.swift`

**情境**：下單頁切換交易對時重新抓訂單簿深度。BTC 的回應要 300 毫秒，ETH 只要 100 毫秒。使用者先選 BTC，50 毫秒後改選 ETH。

**怎麼寫**

1. **Bug 版**：`$symbol` 接 `dropFirst()`、`removeDuplicates()`，`map` 成 `fetchDepth($0)`，再 `flatMap { $0 }` 展開，`receive(on: DispatchQueue.main)`，`assign(to: &$book)`。
2. **修正版**：把 `flatMap` 換成 `switchToLatest()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 選擇的交易對：ETH/JPY，畫面顯示：BTC/JPY 的深度 |
| 修正版 | 選擇的交易對：ETH/JPY，畫面顯示：ETH/JPY 的深度 |

**為什麼**：`flatMap` 讓所有內層 publisher 同時存活，舊的回應照樣輸出，後到的把畫面蓋掉。`switchToLatest` 在新的內層 publisher 出現時取消前一個。async/await 版本的做法是 SwiftUI 的 `.task(id: symbol)`。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L07_SwitchToLatest.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L07_SwitchToLatest.swift#fixed
```

### 演練 8：切換到 ETH，卻載入了 BTC 的深度（第 4 題）

**檔案**：`L08_PublishedWillSet.swift`

**情境**：交易對選擇器的 `symbol` 是 `@Published`。`symbol` 改變時，要載入新交易對的深度。

**怎麼寫**

1. **Bug 版**：`$symbol` 接 `dropFirst()`，`sink` 裡（用 `[weak self]`）讀 `self.symbol`，印出「載入深度：…」，存進 `cancellables`。
2. **修正版**：`sink` 改用傳進來的參數 `newSymbol`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 載入深度：BTC/JPY（舊值）；使用者選的是：ETH/JPY |
| 修正版 | 載入深度：ETH/JPY；使用者選的是：ETH/JPY |

**為什麼**：`@Published` 是在值「即將」改變時發送（willSet），`sink` 收到通知的時候，屬性本身還是舊值。需要新值時，用 `sink` 傳進來的參數，不要回頭讀 `self.symbol`。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L08_PublishedWillSet.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L08_PublishedWillSet.swift#fixed
```

### 演練 9：send 之後立刻檢查，收到的是空的（第 4 題）

**檔案**：`L09_ReceiveOnDelay.swift`

**情境**：`PriceModel` 把價格推播經過 `receive(on: DispatchQueue.main)` 再存進 `received`，確保在主執行緒更新畫面。寫單元測試時，送出一筆價格，馬上檢查 `received`。

**怎麼寫**

1. **Bug 版**：`run` 裡 `model.input.send(15_000_000)`，馬上印出「send 之後立刻檢查：收到 …」。
2. **修正版**：`send` 之後，用迴圈等 `model.received` 有值（每 10 毫秒檢查一次，最多 1 秒），再印出「等值送達之後再檢查：收到 …」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | send 之後立刻檢查：收到 [] |
| 修正版 | 等值送達之後再檢查：收到 [15000000] |

**為什麼**：`receive(on: DispatchQueue.main)` 就算已經在主執行緒，也會把值排到下一個 run loop（主執行緒的事件迴圈）才送出，所以 `send` 之後的程式碼先執行。單元測試可以用 `XCTestExpectation` 等值送達，或注入 scheduler（測試時用 `ImmediateScheduler`）。

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L09_ReceiveOnDelay.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift06-AsyncStream/L09_ReceiveOnDelay.swift#fixed
```

第 1 題的拉（pull）與推（push）差別，會在演練 1 的緩衝積壓中看到；第 5 題是 Combine、RxSwift、async/await 的選型題，沒有演練。

## 常見陷阱

- **行情用 `debounce`。** 資料一直進來，計時一直被重置，永遠送不出去。行情要用 `throttle(latest: true)`。
- **慢消費者搭配 `.unbounded` 的 stream。** 記憶體持續成長。行情要用 `bufferingNewest`。
- **對 conflate 之後的資料做聚合。** 高低價、成交量要在 conflate 之前累積。
- **忘了 `onTermination` 退訂。** 消費者離開之後，WebSocket 的 topic 仍然在訂閱。
- **以為兩個 `for await` 都會收到每一筆。** `AsyncStream` 不支援多個消費者；新的 runtime 會分食元素，舊的可能 crash。需要多個訂閱者就要自己做 fan-out。
- **`share()` 之後才訂閱的人，以為能看到前面的值。** `share()` 不重播；需要重播用 `CurrentValueSubject` 或 `multicast`。
- **在 `$prop.sink` 裡讀 `self.prop`。** `@Published` 在值即將改變時就發送，這時讀到的是舊值。
- **忽略 `receive(on: DispatchQueue.main)` 會晚一個 run loop。** 同步的測試斷言可能因此失敗。

## 面試官想聽到的

1. 能說出完整的行情管線：WebSocket、解碼、`AsyncStream`（`bufferingNewest(1)`）、節流（約 100–250 ms）、更新畫面，而且說明哪些資料不能 conflate。
2. 知道 `onTermination` 與取消的連動，退訂不會漏。
3. 對 Combine 運算子有精確的行為認知：`debounce` 與 `throttle` 的差別、`share` 不重播、`switchToLatest` 取消舊的。
4. 選型有判準：請求回應、串流、畫面綁定、跨平台寫法一致，而不是「新的比較好」。
5. 能說明現有專案裡各種工具的混用方式，以及如何收斂成清楚的分工。

> [!FIELD]
> 在一個社交 App 的 iOS 版裡，採用 SwiftUI＋UIKit 混合架構，並導入 Swift Concurrency 與 Combine；資料流主要用 Rx 和 Combine 的 relay，Rx 與 async 之間有明確的橋接點（在 `Single.create` 裡開 `Task`，並在 `Disposables.create` 裡取消它）。在一個即時行情 App 的 iOS 版裡，全面使用 SwiftUI：即時 K 線用 SSE，用 `for await` 消費事件流（經過兩段式握手：先用 REST 取得短效 token，再開啟串流）；畫面狀態用 Combine 的 `@Published`；複雜的資料流（聊天狀態、全域登出事件等）用 RxSwift 組合；付費訂閱用 StoreKit 2，同樣是 `for await Transaction.updates` 這種 `AsyncSequence`。App 進入背景時串流會停止，回到前景時，用 REST 補齊缺少的 K 棒。
> 面試時可以這樣說：「我實際用過 Rx、Combine，也用 `for await` 消費過 SSE 的事件流。到交易所 App 的行情畫面，我會用 `AsyncStream` 包 WebSocket 的 topic，`bufferingNewest(1)` 做 conflation，`onTermination` 退訂，畫面端再固定頻率更新；重連也會用指數退避加 jitter（隨機抖動），避免大量裝置同時重連。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 冷串流（有人訂閱才開始產生資料） | 自己實作的 `AsyncSequence`（`AsyncStream` 不算：建立時 closure 就立刻執行，而且只支援一個消費者） | `Flow` |
| 熱串流（不管有沒有人訂閱都在產生） | `PassthroughSubject`／`CurrentValueSubject`、`@Published` | `SharedFlow`／`StateFlow` |
| conflation（只留最新一筆） | `.bufferingNewest(1)` | `conflate()`、`buffer(capacity = CONFLATED)`、`StateFlow`（天生只保留最新值） |
| 節流／去抖 | Combine 的 `throttle`／`debounce`；async 要用 swift-async-algorithms | `debounce()`、`sample()`（沒有內建的 throttle，但 `sample` 可以取固定間隔的最新值） |
| 多個訂閱者共用上游 | `share()`（不重播） | `shareIn`／`stateIn`（可設定 replay 與 `WhileSubscribed`） |
| callback 轉成串流 | `AsyncStream` 加 `onTermination` | `callbackFlow { … awaitClose { } }` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 一筆一筆慢慢送來的資料 | AsyncSequence／AsyncStream | 非同期シーケンス／ストリーム |
| 來不及處理時，多出來的資料怎麼存 | buffering policy | バッファリングポリシー |
| 只留最新一筆，丟掉中間的舊值 | conflation | コンフレーション（間引き） |
| 消費者告訴生產者慢一點 | backpressure | バックプレッシャー |
| 等安靜一段時間才送出 | debounce | デバウンス |
| 固定間隔最多送一次 | throttle | スロットル |
| 一個來源分送給多個訂閱者 | fan-out | ファンアウト |
| 伺服器持續單向推送資料 | SSE（Server-Sent Events） | サーバー送信イベント |
