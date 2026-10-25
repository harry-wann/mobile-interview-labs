---
id: advanced-high-frequency-streams
title: 高頻行情串流：合併、背壓、配合螢幕更新頻率，哪些資料可以丟、哪些不能丟
summary: 交易所行情 App 的核心難題：伺服器每秒推來幾百筆更新，怎麼不拖垮主執行緒。這個單元說明為什麼資料頻率不等於畫面頻率、整條處理管線怎麼切、合併與背壓有哪些做法、怎麼配合螢幕更新頻率輸出，以及哪些資料可以丟、哪些絕對不能丟。
priority: P0
day: 3
tags: [market-data, coalescing, batching, backpressure, throttle, displaylink, choreographer, websocket]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 為什麼資料更新的頻率，不能等於畫面更新的頻率？
2. 行情從伺服器到畫面，整條處理管線怎麼切？
3. 背壓（backpressure）是什麼？有哪些處理策略？
4. 哪些資料可以丟、哪些絕對不能丟？
5. 怎麼配合螢幕的更新頻率輸出？
6. 「同一個交易對只保留最新一筆」的合併函式怎麼寫？（面試現場寫程式常考）
7. 怎麼確認這套設計真的有效？

### 1. 為什麼資料更新的頻率，不能等於畫面更新的頻率？

結論：**畫面一秒鐘最多只畫 60 次（高更新率的螢幕是 120 次），人眼也讀不了更快的數字跳動，所以資料進來的頻率和畫面更新的頻率必須分開。** 每收到一筆資料就更新一次畫面，只是在浪費主執行緒的時間。

算一個簡單的帳。假設 WebSocket（保持連線、讓伺服器隨時推送資料的網路連線）每秒推來 500 筆行情更新，每更新一次列表上的一列要花 0.5 毫秒的主執行緒時間，一秒就是 250 毫秒，主執行緒有四分之一的時間都在更新價格，還沒算捲動、手勢和其他工作。更糟的是，一格畫面只有 16.67 毫秒（60Hz），而且同一個交易對一秒內被更新了好幾次，中間的值使用者根本來不及看到。

所以原則是：

- **資料可以每秒更新幾百次，但畫面一個 frame（一格畫面）最多更新一次**，通常甚至更低，例如價格每 100 到 250 毫秒刷新一次。
- 這中間的差距，就靠**合併**（coalescing）來吸收：同一個交易對在這段時間內收到多筆更新，只保留最新的一筆，其他的直接丟掉，因為使用者看到的永遠是最新價格。

> [!TIP]
> 面試時可以這樣說：「資料頻率和畫面頻率是兩件事。WebSocket 每秒可以推幾百筆，但螢幕一秒最多畫 60 次，所以我會在中間做合併，同一個交易對只保留最新的一筆，再配合畫面的更新時機輸出，而不是每筆資料都更新一次畫面。」

### 2. 行情從伺服器到畫面，整條處理管線怎麼切？

結論：**把整條路切成幾段，每一段放在適合的執行緒，而且每一段的頻率都不一樣。** 前面的段落跟著網路與資料的速度，只有最後一段才跟著畫面。

| 段落 | 在哪條執行緒 | 做什麼 | 為什麼 |
|---|---|---|---|
| 1. Socket 接收 | 專用的背景執行緒 | 接收資料、解壓縮、回應心跳（ping／pong） | 這一段絕對不能被畫面卡住 |
| 2. 解析與套用狀態 | 單一寫入者 | 解析 JSON（常用的文字資料格式），套用到本地的狀態 | 修改狀態的程式碼都在同一個地方，就不用擔心並發衝突 |
| 3. 合併 | 同上 | 同一個交易對只留最新的值，記下哪些交易對有變動 | 把每秒數百筆壓縮成每個交易對一筆 |
| 4. 對齊畫面輸出 | 跟著螢幕更新的時機 | 每個 frame 取走「有變動的交易對」，產生一份快照或差異，送到主執行緒 | 畫面一個 frame 最多更新一次 |
| 5. 列表更新 | 主執行緒 | 只更新有變動的那幾列 | 不重建整個列表 |

名詞解釋：

- **心跳（ping／pong）**：伺服器定期送一個 ping，客戶端要在時限內回一個 pong，證明還在線上。Huobi 相容的交易所公開 WebSocket 大約每 5 秒送一次 `{"ping":ts}`，客戶端回 `{"pong":ts}`，連續兩次沒回，伺服器就會斷線（以交易所的 API 文件為準）。如果心跳和畫面更新排在同一條被卡住的執行緒上，pong 就來不及回，使用者會莫名其妙被斷線重連。
- **gzip**：一種資料壓縮格式。Huobi 相容的交易所公開 WebSocket，每個 frame 都是 gzip 壓縮的二進位資料，客戶端必須先解壓縮才能解析，這件事也要放在 socket 這一段，不能放在主執行緒。
- **單一寫入者（single writer）**：所有「修改狀態」的程式碼，都在同一條執行緒或同一個 actor 上執行。iOS 用 serial `DispatchQueue` 或 `actor`，Android 用單一執行緒的 dispatcher，或 `Channel` 加一個消費者協程。這樣狀態只會有一個人修改，其他人只讀快照。
- **快照（snapshot）**：某個時間點的資料副本。畫面拿到的是快照，後面的更新不會在畫面讀取到一半時改動它。

> [!TIP]
> 面試時可以這樣說：「我會把管線切成四五段：專用執行緒做 socket 接收、gzip 解壓縮和心跳；單一寫入者解析並套用狀態、合併；再配合螢幕的更新時機輸出快照；主執行緒只做列表的局部更新。心跳和解壓縮在最前面，才不會被畫面拖慢，造成斷線。」

### 3. 背壓是什麼？有哪些處理策略？

背壓（backpressure）指的是**資料產生的速度比處理的速度快的時候，上游和下游之間要怎麼處理**。像水龍頭開很大，水槽的排水口來不及，水就會溢出來。行情串流的特殊之處是：WebSocket 是伺服器主動推送，客戶端沒辦法叫它慢一點，所以只能在客戶端自己決定怎麼丟、怎麼合併。（Huobi 相容的交易所公開文件提到，訂閱時可以帶選填的 `freq-ms`，1000 到 5000 毫秒，讓伺服器降低推送頻率，實際哪些頻道支援以交易所的 API 文件為準。）

常見的策略有四種：

| 策略 | 做法 | 適合的資料 |
|---|---|---|
| 合併，只留最新（latest-wins） | 同一個 key 直接覆蓋舊值，用字典實作 | 最新價、最佳買賣價、持倉損益這類「狀態」 |
| 取樣（sample、throttle） | 固定的時間間隔，只取最新的一筆 | 壓低畫面的更新頻率 |
| 批次（batching） | 累積一段時間或一定數量，一次處理 | 寫入資料庫、送出分析事件、套用一批增量更新 |
| 有界佇列 | 佇列有固定大小，滿了就阻塞、丟最舊的，或丟最新的 | 必須按順序處理的指令；要明確決定丟哪一邊 |

**兩種一定要避免的做法：**

- **無上限的佇列。** 上游比下游快的時候，佇列會一直長大，最後記憶體爆掉被系統殺掉。
- **對價格串流用 `debounce`。** debounce 的意思是「等一段安靜期之後才送出」，適合搜尋框（使用者停止打字才搜尋）。高頻行情永遠不會安靜，畫面可能長時間完全不更新。

兩個平台都有現成的工具，但**有一個很容易踩的坑：它們都是對「整條串流」只保留最新一筆，不是對「每個交易對」各留一筆。** 如果一條串流裡混著上百個交易對，其他交易對的更新就會被丟掉。

```swift
// Swift：只保留最新 1 筆的 AsyncStream
// Tick 是一筆行情，包含 symbol（交易對代號）與 price（價格）
let (stream, continuation) = AsyncStream.makeStream(of: Tick.self, bufferingPolicy: .bufferingNewest(1))
continuation.yield(Tick(symbol: "BTC", price: 1))
continuation.yield(Tick(symbol: "BTC", price: 2))
continuation.yield(Tick(symbol: "ETH", price: 9))
continuation.finish()
for await tick in stream { print(tick.symbol, tick.price) }
// 輸出：ETH 9
// BTC 的更新被 ETH 擠掉了，這不是我們要的
```

```kotlin
// Kotlin：Flow 的 conflate 與 sample
priceFlow.conflate()       // 下游處理太慢時，跳過中間的值，只處理最新的一筆
priceFlow.sample(200)      // 每 200 毫秒取最新的一筆（在 kotlinx.coroutines 1.11 仍是預覽 API，要加 @OptIn(FlowPreview::class)）
// Combine 的對應工具是 throttle(for:scheduler:latest: true)
```

所以多個交易對的行情，要先用字典依交易對分組（第 6 題的合併函式），再配合下一題的輸出時機。

> [!TIP]
> 面試時可以這樣說：「背壓是上游比下游快時的處理方式。行情是伺服器推送，我們只能在客戶端處理：狀態資料用合併，只保留每個交易對的最新值；畫面用取樣降低更新頻率；佇列一定要有上限，不能無限長。不能用 debounce，因為高頻行情永遠不會靜默。」

### 4. 哪些資料可以丟、哪些絕對不能丟？

結論：**「狀態」可以丟中間值，「事件」和「增量」一筆都不能丟。** 判斷方法是問自己：下一筆資料有沒有包含前一筆的全部資訊？

| 資料 | 可以丟中間值嗎 | 原因 |
|---|---|---|
| 最新成交價、最佳買價與最佳賣價（best bid／ask） | 可以 | 每一筆都是完整的當前狀態，新的取代舊的 |
| K 線目前這一根的更新 | 可以 | 同一根 K 棒的更新，最新的取代舊的。但已經收盤的 K 棒要保留 |
| 訂單簿（order book，目前所有掛單的價格與數量）的增量更新（delta） | **不行** | delta 只描述「跟上一個狀態比，哪裡變了」。漏掉任何一筆，本地的訂單簿就跟伺服器不一致 |
| 成交明細（每一筆成交） | **不行** | 每一筆都是獨立的事件，使用者要看完整的成交記錄 |
| 自己的委託狀態、成交通知 | **不行** | 使用者的資產和下單結果，漏一筆就是錯帳 |

**增量資料的正確處理方式：** 在背景依序套用每一筆 delta，並**檢查序號**（sequence number）。每一筆 delta 都帶著遞增的序號，本地訂單簿目前套用到 100，下一筆就應該是 101。如果收到 103，代表漏了 101、102，本地的訂單簿已經不可信，唯一安全的做法是**重新同步**：丟掉本地的簿、重新抓一份完整快照、再接續套用序號比快照大的更新。重連之後也一樣，要先重新取得快照。對外則發佈「快照」，快照才可以拿去合併與取樣。（實際的頻道格式與是否有增量頻道，以交易所的 API 文件為準。）

**不能丟的資料還要防重複。** 網路重送可能讓同一筆資料收到兩次，要用唯一的 ID（冪等鍵，idempotency key，同一個 ID 不管送幾次，結果都跟送一次一樣）去重。

> [!TIP]
> 面試時可以這樣說：「我會區分狀態和增量。最新價、最佳買賣價是狀態，可以合併丟中間值；訂單簿的 delta 和成交明細一筆都不能丟，要在背景依序套用並檢查序號，發現缺口就重抓完整快照，再對外發佈快照給畫面。」

> [!FIELD]
> 在一個即時行情 App 和一個社交 App 的經驗：即時行情 App 的即時 K 線是用 SSE（Server-Sent Events，伺服器單向推送資料的 HTTP 串流）。收到事件時，`KlineLive.apply` 的規則是：時間和最後一根 K 棒相同就覆蓋，時間比最後一根晚、而且剛好是 K 線週期的整數倍就新增一根，不符合這兩種情況的就丟掉。這是「狀態可以覆蓋、不合規的資料可以丟」的實際例子。App 回到前景時，會用 REST（一般的 HTTP 請求）重新抓資料，補回中斷期間缺的 K 棒，這是「斷線之後用完整資料重新同步」的做法。社交 App 的聊天訊息則用 UUID（隨機產生、幾乎不會重複的唯一識別碼）當作唯一鍵，伺服器回應之後，用它取代本地先顯示的那一筆，也是用冪等鍵處理重複與重送。
> 換成交易所 App，行情列表會有上百個交易對，我會在這個基礎上加上單一寫入者、依交易對合併、配合畫面更新時機輸出。

### 5. 怎麼配合螢幕的更新頻率輸出？

結論：**用系統提供的「每格畫面一次」的計時器，在那個時間點取走累積的資料。** 這樣畫面更新的次數不會超過螢幕能畫的次數，也不會漏掉任何一個交易對最新的值。

做法是準備一個**緩衝區（buffer）：背景持續把最新的值依交易對寫進去，畫面計時器每次來取走全部，並清空。** 這樣同時完成了合併（同一個交易對只有一筆）和記錄哪些交易對有變動（緩衝區裡有的就是有變動的）。

```swift
import os

final class TickBuffer: Sendable {
    // 字典：交易對 → 最新的 Tick。用鎖保護，因為背景執行緒寫、主執行緒讀
    private let pending = OSAllocatedUnfairLock(initialState: [String: Tick]())

    // 背景執行緒呼叫：同一個交易對直接覆蓋舊值
    func put(_ tick: Tick) {
        pending.withLock { $0[tick.symbol] = tick }
    }

    // 每格畫面呼叫：一次取走所有有變動的 Tick，並清空緩衝區
    func drain() -> [Tick] {
        pending.withLock { dict in
            let batch = Array(dict.values)
            dict.removeAll(keepingCapacity: true)
            return batch
        }
    }
}
```

```kotlin
class TickBuffer {
    private val lock = Any()
    private var pending = HashMap<String, Tick>()

    // 背景執行緒呼叫：同一個交易對直接覆蓋舊值
    fun put(tick: Tick) {
        synchronized(lock) { pending[tick.symbol] = tick }
    }

    // 每格畫面呼叫：換一個新的空字典，並回傳舊的內容
    fun drain(): List<Tick> = synchronized(lock) {
        val batch = pending.values.toList()
        pending = HashMap()
        batch
    }
}
```

**iOS：`CADisplayLink`**，會跟著螢幕更新的節奏觸發。

```swift
// 寫在畫面的類別（例如 PriceScreen）裡
@MainActor
func start() {
    let link = CADisplayLink(target: WeakTarget(self), selector: #selector(WeakTarget.tick(_:)))
    // 在 ProMotion（Apple 的高更新率螢幕）裝置上，把更新頻率限制在 30 到 60Hz（iOS 15 起）
    link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 60, preferred: 60)
    link.add(to: .main, forMode: .common)   // 一定要用 .common，不然捲動時計時器會停
    self.link = link
}

func stop() {
    link?.invalidate()   // 離開畫面一定要呼叫，否則計時器會一直跑
    link = nil
}
```

- `.common` mode 很重要：使用者在捲動列表時，主執行緒的 run loop（不斷處理事件的迴圈）會切換到另一個模式，只加在預設模式的計時器會暫停，列表一捲動就凍住。
- `CADisplayLink` 對它的 target 是強參考，直接傳 `self` 會形成 retain cycle（互相持有，導致記憶體無法釋放）。要傳一個只用 weak 參考持有真正物件的中間物件（範例中的 `WeakTarget`）：

```swift
@MainActor
final class WeakTarget {
    weak var owner: PriceScreen?       // weak：不會讓畫面物件無法釋放
    init(_ owner: PriceScreen) { self.owner = owner }
    @objc func tick(_ link: CADisplayLink) { owner?.onFrame() }   // 每格畫面呼叫，onFrame 裡取走緩衝區
}
```

**Android：`Choreographer`**，負責協調每一格畫面的系統元件。

```kotlin
class FrameTicker(
    private val buffer: TickBuffer,
    private val onBatch: (List<Tick>) -> Unit,
) : Choreographer.FrameCallback {
    fun start() { Choreographer.getInstance().postFrameCallback(this) }
    fun stop() { Choreographer.getInstance().removeFrameCallback(this) }

    override fun doFrame(frameTimeNanos: Long) {
        val batch = buffer.drain()
        if (batch.isNotEmpty()) onBatch(batch)
        // postFrameCallback 只會觸發一次，要持續更新就在回呼裡再註冊下一次
        Choreographer.getInstance().postFrameCallback(this)
    }
}
```

Jetpack Compose 裡可以用 `withFrameNanos`（在 `LaunchedEffect` 的迴圈中等待下一格畫面）達到同樣的效果。

> [!TIP]
> 面試時可以這樣說：「背景把最新的值依交易對寫進緩衝區，畫面端用 CADisplayLink 或 Choreographer 在每一格畫面取走並清空。CADisplayLink 要加到 common mode，不然捲動時會停；離開畫面要 invalidate，而且 target 要用 weak 的中間物件，避免 retain cycle。」

### 6. 「同一個交易對只保留最新一筆」的合併函式怎麼寫？（面試現場寫程式常考）

需求：輸入一串更新，同一個交易對只保留**最後一筆**，輸出順序依該交易對**第一次出現**的順序。例如 `[A1, B1, A2]` 要變成 `[A2, B1]`。

做法是用字典存每個交易對的最新值，再用一個陣列記錄第一次出現的順序，只掃過一次，時間複雜度是 O(n)（輸入有 n 筆，就只做大約 n 次操作）。千萬不要用巢狀迴圈（O(n²)，資料量大時會慢很多）。

```swift
func coalesce(_ updates: [Tick]) -> [Tick] {
    var order: [String] = []                // 記錄交易對第一次出現的順序
    var latest: [String: Tick] = [:]        // 每個交易對的最新值
    for u in updates {
        // updateValue 回傳舊值；舊值是 nil，代表這個交易對是第一次出現
        if latest.updateValue(u, forKey: u.symbol) == nil { order.append(u.symbol) }
    }
    return order.map { latest[$0]! }
}
```

```kotlin
fun coalesce(updates: List<Tick>): List<Tick> {
    val latest = LinkedHashMap<String, Tick>()   // 覆蓋既有的 key，不會改變它第一次插入的位置
    for (u in updates) latest[u.symbol] = u
    return latest.values.toList()
}
```

Kotlin 的 `LinkedHashMap` 會記住 key 第一次插入的順序，覆蓋舊值時順序不變，所以不需要另外的順序陣列。

> [!TIP]
> 面試時可以這樣說：「我用字典存每個交易對的最新值，再用陣列記錄第一次出現的順序，一次掃過就完成，時間複雜度是 O(n)。Kotlin 可以直接用 LinkedHashMap，覆蓋 key 不會改變插入順序。」

### 7. 怎麼確認這套設計真的有效？

不要靠感覺，要量。三類指標：

- **更新次數**：記錄「每秒收到幾筆資料」和「每秒實際更新了幾次畫面」，兩者的比值就是合併的效果。
- **畫面順不順**：iOS 用 Instruments 的 Animation Hitches 看 hitch（畫面延遲顯示），Android 用 Perfetto（系統追蹤工具）與 JankStats（收集每一格畫面耗時的函式庫）看 jank，量測方式見畫面卡頓單元；同時看主執行緒每一格畫面花了多少時間。
- **記憶體**：讓行情畫面連續開幾個小時，觀察記憶體有沒有一直往上長，確認緩衝區和佇列都有上限。

建立好基準數字之後，每次改動都拿同一個基準比較。

> [!TIP]
> 面試時可以這樣說：「我會量三件事：每秒收到幾筆資料和每秒實際更新幾次畫面的比值、hitch 或 jank 的數量，以及畫面開幾個小時後記憶體有沒有一直長。先建立基準，每次改動都拿同一個基準比較，用數字證明合併真的有效。」

## 實際演練

> [!LAB]
> **打開**：iOS：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced05HighFrequency**。Android：Android Studio 打開 `labs/android` 資料夾，Run configuration 選 **app**，按 ▶，App 首頁進入 **Advanced05HighFrequency**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced05-HighFrequency/`，解答在 `labs/ios/Solution/Advanced/Advanced05-HighFrequency/`；Android 你寫在 `labs/android/practice/advanced/advanced05-high-frequency/`，解答在 `labs/android/solution/advanced/advanced05-high-frequency/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：心跳的演練把真實的「約 5 秒一次、連續兩次沒回」縮小成「150 毫秒一次、300 毫秒內要回、連續兩次逾時」，邏輯一樣，數字是真的在跑執行緒量出來的，門檻很寬。演練 4 的 iOS 版用真的 `CADisplayLink` 與 run loop，Android 版因為 JVM 單元測試沒有 Choreographer，用 `FakeChoreographer` 重現同樣的規則。

### 演練 1：資料一大包進來，心跳來不及回，連線被切掉（第 2 題）

**檔案**：`L01_HeartbeatStarvation.swift`（iOS）、`L01_HeartbeatStarvation.kt`（Android）

**情境**：伺服器每 150 毫秒送一次 ping，client 要在 300 毫秒內回 pong，連續兩次逾時就切斷連線。連線一開始，伺服器推來一大包行情，解析（JSON、解壓縮）要花 800 毫秒，後面接著 4 次 ping。

**怎麼寫**（兩個平台相同）

1. **Bug 版**：收到的所有訊息（資料與 ping）都丟進同一條序列佇列（iOS `DispatchQueue`、Android 單執行緒的 `Executor`）處理：資料睡 800 毫秒再 `onParsed()`，ping 算出延遲後 `onPong(…)`。
2. **修正版**：資料丟給另一條佇列去解析；ping 在收到的當下（socket 這一段）直接回 pong，不排隊。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 4 次 ping 的 pong 延遲約 650、490、340、180 ms，前三次逾時，連續 2 次逾時：伺服器切斷連線 |
| 修正版 | 4 次 pong 延遲都是 0 ms 左右，連線正常，資料照樣解析完成 |

**為什麼**：心跳和解析、畫面更新排在同一條會被卡住的佇列或執行緒上，一卡 pong 就來不及回，伺服器切斷連線，使用者莫名其妙被斷線重連。管線切段的原則是：socket 這一段（接收、回心跳、解壓縮）絕對不能被後面的段落卡住，解析、套用狀態、畫面各自在後面的段落。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L01_HeartbeatStarvation.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L01_HeartbeatStarvation.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced05-high-frequency/L01_HeartbeatStarvation.kt#bug
```

```lab-solution
android/solution/advanced/advanced05-high-frequency/L01_HeartbeatStarvation.kt#fixed
```

### 演練 2：3 個交易對各更新兩次，畫面上只有一個有價格（第 3 題）

**檔案**：`L02_PerSymbolCoalesce.swift`（iOS）、`L02_PerSymbolCoalesce.kt`（Android）

**情境**：行情串流混著 BTC、ETH、XRP 三個交易對，一瞬間來了 6 筆（BTC1、ETH1、XRP1、BTC2、ETH2、XRP2，數字是第幾次更新）。畫面處理一筆要 20 毫秒，比來得慢。畫面應該顯示三個交易對各自最新的價格。

**怎麼寫**

1. **Bug 版**：iOS `AsyncStream.makeStream(of:bufferingPolicy: .bufferingNewest(1))`；Android 在 `flow { … }` 後面加 `.conflate()`。整條串流只留最新的一筆。
2. **修正版**：寫一個緩衝區 `QuoteBuffer`：用字典（交易對 → 最新一筆），`put` 同一個交易對後來的蓋掉先來的，`drain` 取走全部的值並清空；畫面一次取走三個交易對各一筆最新的。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | XRP 是第 2 次，ETH 從頭到尾沒有出現，BTC 停在第 1 次的舊價或根本沒出現（看消費者開始讀的時間點） |
| 修正版 | BTC、ETH、XRP 都是第 2 次 |

**為什麼**：`bufferingNewest(1)`、`conflate`、`sample` 都是對「整條串流」只留最新一筆，不是對「每個交易對」各留一筆。一條串流裡混著上百個交易對，其他交易對的更新就被擠掉了。所以要先依交易對分組（字典），再配合輸出的時機取走。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L02_PerSymbolCoalesce.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L02_PerSymbolCoalesce.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced05-high-frequency/L02_PerSymbolCoalesce.kt#bug
```

```lab-solution
android/solution/advanced/advanced05-high-frequency/L02_PerSymbolCoalesce.kt#fixed
```

### 演練 3：重新連線之後，成交明細多了重複的（第 4 題）

**檔案**：`L03_TradeDedupe.swift`（iOS）、`L03_TradeDedupe.kt`（Android）

**情境**：成交明細每一筆都是獨立的事件，不能合併也不能丟；但連線斷了再接上，伺服器會把最近幾筆再送一次。收到的順序：成交 #1（0.5 BTC）、#2（0.2）、#3（0.1），重連之後重送 #2、#3，再來新的 #4（0.3）。每一筆成交有唯一的 ID。

**怎麼寫**

1. **Bug 版**：`receive` 每收到一筆就把 ID 加進成交明細、把數量加進累計成交量。
2. **修正版**：用 `Set` 記下處理過的成交 ID（iOS `seen.insert(trade.id).inserted`、Android `seen.add(trade.id)`）；ID 已經在裡面就跳過，沒有才加。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 成交明細 1、2、3、2、3、4（共 6 筆）；累計成交量 1.4 BTC，多算了 0.3 |
| 修正版 | 成交明細 1、2、3、4（共 4 筆）；累計成交量 1.1 BTC |

**為什麼**：「狀態」可以丟中間值（最新價），「事件」和「增量」一筆都不能丟（成交明細、訂單簿的 delta）。不能丟的資料還要防重複：網路重送、重連後補資料，都會讓同一筆收到兩次，要用唯一的 ID（冪等鍵）去重。訂單簿的 delta 另外要檢查序號，發現缺口就重抓完整快照。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L03_TradeDedupe.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L03_TradeDedupe.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced05-high-frequency/L03_TradeDedupe.kt#bug
```

```lab-solution
android/solution/advanced/advanced05-high-frequency/L03_TradeDedupe.kt#fixed
```

### 演練 4：捲動時行情更新停住／只更新一次就不動，離開畫面後計時器還在跑（第 5 題）

**檔案**：`L04_DisplayLink.swift`（iOS）、`L04_FrameTicker.kt`（Android）

**情境**：畫面端要在每一格畫面取走緩衝區裡的最新價。

- iOS：用 `CADisplayLink`。兩個地方要小心：使用者捲動列表時，主執行緒的 run loop 會切到 tracking mode，只加在預設模式（default）的計時器會暫停；`CADisplayLink` 對 target 是強參考，直接傳 `self` 會讓畫面物件被計時器抓住，離開畫面時 `deinit` 永遠不會被呼叫。演練用 `RunLoop.main.run(mode: .tracking, …)` 模擬正在捲動。
- Android：用 `Choreographer.postFrameCallback`，它只會在下一格畫面呼叫一次，要每一格都更新就得在 `doFrame` 裡再註冊；離開畫面要 `removeFrameCallback`。JVM 單元測試沒有真的 Choreographer，演練用 `FakeChoreographer`（`frame()` 代表螢幕又畫了一格），`FrameTicker` 的寫法和這個單元第 5 題的範例一樣。

**怎麼寫**

1. **Bug 版**：
   - iOS：`CADisplayLink(target: screen, selector: #selector(PriceScreen.onFrame))`，`add(to: .main, forMode: .default)`，存進 `screen.link`。
   - Android：`doFrame` 取走緩衝區、呼叫 `onBatch`，沒有再 `postFrameCallback`；`stop()` 什麼都不做。
2. **修正版**：
   - iOS：寫一個只 weak 持有畫面物件的 `WeakTarget`（有 `@objc func tick(_:)`），`CADisplayLink(target: WeakTarget(screen), selector: #selector(WeakTarget.tick(_:)))`，`add(to: .main, forMode: .common)`。
   - Android：`doFrame` 最後再 `choreographer.postFrameCallback(this)`；`stop()` 呼叫 `choreographer.removeFrameCallback(this)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：捲動中的 0.5 秒 `onFrame` 被呼叫 0 次，平常的 0.3 秒約 18 次，離開畫面之後畫面物件還活著（洩漏）；Android：10 格畫面列表只更新 1 次，最後顯示的價格是第一格的 |
| 修正版 | iOS：捲動中約 30 次、平常約 18 次，離開畫面之後畫面物件已釋放；Android：更新 10 次，最後顯示的價格是第 10 格的，離開畫面之後再過 3 格更新 0 次 |

**為什麼**：行情資料可以每秒更新幾百次，畫面一格最多更新一次；用系統提供的「每格畫面一次」的計時器取走緩衝區，更新次數不會超過螢幕能畫的次數，也不會漏掉任何一個交易對最新的值。`.common` mode 涵蓋捲動時的 tracking mode，所以捲動時計時器不會停；要用 weak 的中間物件，避免計時器抓住畫面物件造成 retain cycle；離開畫面一定要停掉計時器。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L04_DisplayLink.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L04_DisplayLink.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced05-high-frequency/L04_FrameTicker.kt#bug
```

```lab-solution
android/solution/advanced/advanced05-high-frequency/L04_FrameTicker.kt#fixed
```

### 演練 5：行情列表每次更新，各列的順序一直亂跳（第 6 題）

**檔案**：`L05_CoalesceFunction.swift`（iOS）、`L05_CoalesceFunction.kt`（Android）

**情境**：輸入一串更新，同一個交易對只留最後一筆，輸出順序依該交易對「第一次出現」的順序，例如 `[A1, B1, A2]` 要變成 `[A2, B1]`。演練用 4 組不同順序的資料檢查結果與順序，再用 1 萬筆更新、500 個交易對，數「比較兩個交易對相不相等」做了幾次，量演算法的成本。

**怎麼寫**

1. **Bug 版**：iOS `Dictionary`、Android `HashMap` 存每個交易對的最新值，輸出時直接取 `values`。
2. **修正版**：
   - iOS：字典存最新值，另外用陣列 `order` 記下交易對第一次出現的順序（`updateValue` 回傳 `nil` 就是第一次出現），最後依 `order` 取值。
   - Android：改用 `LinkedHashMap`，覆蓋 key 不會改變它第一次插入的位置，不需要另外的順序陣列。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 每個交易對都是最新價（4／4），但順序一致的組數是 0／4（字典與 HashMap 的順序不是第一次出現的順序） |
| 修正版 | 兩個組數都是 4／4；1 萬筆更新合併成 500 筆，比較交易對 iOS 約 1.5 萬次、Android 約 9500 次 |

**為什麼**：只掃一次的做法是 O(n)，1 萬筆更新大約比較 1 萬多次。如果寫成「每一筆更新都從頭找一次有沒有同一個交易對」的巢狀迴圈，是 O(n²)，這個資料量要比較上百萬次。順序亂掉在畫面上的症狀，就是行情列表的列每次更新都換位置。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L05_CoalesceFunction.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced05-HighFrequency/L05_CoalesceFunction.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced05-high-frequency/L05_CoalesceFunction.kt#bug
```

```lab-solution
android/solution/advanced/advanced05-high-frequency/L05_CoalesceFunction.kt#fixed
```

第 1 題（資料頻率不等於畫面頻率）和第 7 題（怎麼確認這套設計有效：收到幾筆、畫面更新幾次、卡頓次數）的演練已經在前面的單元做過，不重複：iOS 見 Swift08SwiftUIIdentity 的演練 7（資料層合併加上數字確認），Android 見 Kotlin08ComposePerformance 的演練 4（資料層降低更新頻率，演練會算「畫面更新了幾次」）；列表局部更新見畫面卡頓單元的演練 3（Android）與演練 4（iOS）。第 3 題的「無上限的佇列」與「debounce 會餓死價格串流」，分別見 Swift06AsyncStream 的演練 1、6 與 Kotlin05Flow 的演練 4；第 4 題的訂單簿增量更新不能 conflate，見 Kotlin05Flow 的演練 5。

## 常見陷阱

- **每收到一筆 WebSocket 訊息，就呼叫一次 `DispatchQueue.main.async` 或 `withContext(Dispatchers.Main)`。** 每秒數百次的呼叫會把主佇列淹沒。要先在背景合併，再批次送到主執行緒。
- **在主執行緒解析 JSON 或做 gzip 解壓縮。** 這些工作要放在 socket 這一段的背景執行緒。
- **心跳（ping／pong）放在會被畫面卡住的執行緒。** 主執行緒一卡，pong 來不及回，伺服器就斷線，造成沒必要的重連。
- **對增量資料（訂單簿的 delta）做 `conflate` 或 `sample`。** 中間的 delta 被丟掉，訂單簿就錯帳了。
- **對整條混著多個交易對的串流用 `conflate`，以為每個交易對都會保留最新值。** 其實只保留整條串流的最新一筆，其他交易對的更新被擠掉了。要先依交易對分組再合併。
- **`CADisplayLink` 加在預設模式，捲動時畫面凍結；離開畫面沒有 `invalidate()`。** 要加在 `.common`，並且在離開畫面時停止。
- **每一格畫面都產生包含全部資料的快照。** 記憶體和 CPU（中央處理器）都吃緊。快照只放有變動的部分，訂單簿的顯示深度也要設上限。
- **重連之後沒有用完整快照重新對齊。** 本地的資料和伺服器不一致，而且使用者看不出來。

## 面試官想聽到的

1. 把頻率分開：資料的頻率和畫面的頻率不同，畫面一個 frame 最多更新一次。
2. 說得出完整的管線，以及每一段所在的執行緒，包含心跳與解壓縮不能被畫面卡住。
3. 會選擇背壓策略：狀態用合併，增量資料不能丟，佇列要有上限，並知道 `debounce` 為什麼不適用。
4. 知道怎麼配合螢幕更新頻率輸出（`CADisplayLink`、`Choreographer`、緩衝區），並且能量測效果。
5. 能寫出「同一個交易對只保留最新一筆」的合併函式，說得出時間複雜度與保持順序的做法。

> [!WARNING]
> 面試官常追問：「合併會不會漏掉中間的最高價、最低價？」不要只回答「不會」。要說明：合併只用在「顯示用的最新價」。最高價、最低價、K 線的開高低收，必須在合併之前的資料層就累計好（例如每收到一筆就更新當根 K 棒的最高、最低價），不能只靠畫面上最後看到的那一筆。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 配合畫面更新 | `CADisplayLink`（`.common` mode、`preferredFrameRateRange`） | `Choreographer.postFrameCallback`、Compose 的 `withFrameNanos` |
| 單一寫入者 | serial `DispatchQueue`、`actor` | 單一執行緒的 dispatcher、`Channel` 加一個消費者協程 |
| 合併與取樣 | 字典加緩衝區；`AsyncStream` 的 `bufferingNewest`；Combine 的 `throttle(latest: true)` | `HashMap` 加緩衝區；`Channel.CONFLATED`；Flow 的 `conflate`、`sample` |
| 列表局部更新 | diffable 加 `reconfigureItems`、IGListKit（開源的列表更新函式庫） | `ListAdapter` 加 `DiffUtil` 加 payload |
| gzip 解壓縮 | zlib 的 `inflateInit2`（windowBits 設 31 才能處理 gzip 標頭），或第三方函式庫；Apple 的 `Compression` framework 的 zlib 是不含 gzip 標頭的 raw deflate，不能直接解 gzip | `GZIPInputStream` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 背壓（資料產生得比處理得快時的處理方式） | backpressure | バックプレッシャー |
| 合併（同一個 key 只留最新） | coalescing | 集約（コアレッシング） |
| 批次處理 | batching | バッチ処理 |
| 心跳 | heartbeat（ping／pong） | ハートビート |
| 訂單簿 | order book | 板（オーダーブック） |
| 增量更新 | delta（incremental update） | 差分更新 |
| 序號 | sequence number | シーケンス番号 |
| 快照 | snapshot | スナップショット |
