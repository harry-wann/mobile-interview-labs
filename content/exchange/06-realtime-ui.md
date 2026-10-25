---
id: exchange-realtime-ui
title: 即時行情畫面：資料每秒進來幾百則，畫面怎麼更新才不會卡
summary: 行情推播的速度，遠超過人眼讀得完、畫面畫得完的速度。這個單元說明為什麼不能每一則都更新畫面、在哪裡只留最新的資料、列表怎麼只更新有變動的列、價格變動的閃爍提示怎麼做，以及數字怎麼顯示才不會抖動。
priority: P0
day: 4
tags: [realtime-ui, conflate, throttle, diff, tabular-figures, formatter]
---

## 重點講解

先設定一個情境，後面每個問題都用它：行情列表有 40 個交易對，每個交易對每秒收到 5 到 10 則價格更新，合計每秒 200 到 400 則。這些資料是從 WebSocket 進來的。WebSocket 是 App 和伺服器之間一條保持連線的雙向通道，伺服器有新資料就能直接推給 App，不用 App 一直詢問。

這個單元回答六個面試常見問題：

1. 行情每秒進來幾百則，為什麼不能每一則都更新畫面？
2. 在哪裡把資料合併、只留最新值？iOS 和 Android 用什麼工具？
3. 畫面多久更新一次？用什麼來驅動？
4. 列表怎麼只更新有變動的列？
5. 價格變動的閃爍提示怎麼做？
6. 數字怎麼顯示，才不會抖動、也不會拖慢畫面？

### 1. 行情每秒進來幾百則，為什麼不能每一則都更新畫面？

**因為「資料進來的速度」「畫面能畫的速度」「人眼能讀的速度」是三件不同的事。** 畫面只需要在每次重畫時，拿到當下最新的價格；中間被蓋掉的價格沒有人看得到，丟掉不會讓使用者漏掉任何有意義的資訊。

以 40 個交易對的情境來看，可以把頻率分成三層：

| 層次 | 頻率 | 要做的事 |
|---|---|---|
| 資料到達 | 每秒 200 到 400 則 | 背景收到後每一則都處理 |
| 最新狀態 | 和資料到達相同 | 背景把每一則套用到「每個交易對的最新報價」，只是改記憶體裡的一個值，很便宜 |
| 畫面刷新 | 每秒約 4 到 10 次 | 固定節奏取一次最新狀態，更新畫面。列表上的價格每 100 到 250 毫秒更新一次，人眼已經讀得很順 |

如果每一則都直接更新 UI（user interface，使用者介面），主執行緒（負責處理畫面與觸控的執行緒）每秒要做幾百次的版面計算和繪製。多數手機螢幕每秒重畫 60 次，每一格畫面只有大約 16.7 毫秒的時間預算；主執行緒被更新洪水塞滿，使用者滑動列表就會卡。

所以流程是這樣：

```text
WebSocket（每秒 200~400 則）
   │  每一則都處理，但只是改記憶體裡的字典
   ▼
背景的「最新狀態」：[交易對代號: 最新報價]
   │  每 100~250 毫秒，固定節奏取一次快照
   ▼
畫面（只更新有變動的列）
```

這種「中間的更新直接丟掉、只留最新的」做法叫 conflation，意思是把多筆更新合併成一筆。合併發生在「最新狀態」和「畫面」之間，不是在資料進來的地方。

> [!WARNING]
> 面試官常追問：合併丟掉的資料裡，如果剛好有當天最高價怎麼辦？答案是：24 小時最高價、最低價、成交量這類統計，要在背景「每一則都算」，算好的結果放在最新狀態裡。合併只用在「畫面要顯示的最新值」，不能拿合併後的資料去算統計。

> [!TIP]
> 面試時可以這樣說：「我會把資料頻率和畫面頻率分開。背景每一則行情都套用到最新狀態，畫面用固定節奏，大約 100 到 250 毫秒取一次最新快照。中間被蓋掉的價格使用者看不到，丟掉沒有影響；但統計值要在合併之前算好。」

### 2. 在哪裡把資料合併、只留最新值？iOS 和 Android 用什麼工具？

**合併要放在「背景處理」和「畫面」之間，而且要「每個交易對各留一筆最新值」，不是整條資料流只留一筆。** iOS 常用 `AsyncStream` 的 `bufferingNewest(1)` 和 Combine 的 `throttle(latest: true)`；Android 常用 Flow 的 `conflate()` 和 `sample()`。`debounce` 不適合行情。

先看 iOS 的 `AsyncStream`（Swift 的非同步資料流，可以用 `for await` 一筆一筆讀）。`bufferingNewest(1)` 的意思是：緩衝區最多放 1 筆，消費者還沒來取時，新的資料會把舊的擠掉。

```swift
struct Ticker: Sendable { let symbol: String; let price: Int }

let stream = AsyncStream<Ticker>(bufferingPolicy: .bufferingNewest(1)) { continuation in
    // 模擬生產者：連續送出 5 筆，這段時間沒有任何人在讀
    for price in 100...104 {
        continuation.yield(Ticker(symbol: "BTC_JPY", price: price))
    }
    continuation.finish()
}

for await ticker in stream {
    print(ticker.price)   // 輸出：104（100 到 103 在緩衝區裡被擠掉了）
}
```

**常見誤解：** 如果 40 個交易對共用同一條 `bufferingNewest(1)` 的 stream，ETH（以太幣）的更新會把 BTC（比特幣）還沒被讀走的更新擠掉，BTC 的最新價就消失了。正確做法有兩種：一個交易對一條 stream；或是用字典保存每個交易對的最新值，畫面固定節奏來取。第二種比較單純，也比較常用：

```swift
actor TickerStore {
    private var latest: [String: Ticker] = [:]   // 每個交易對的最新報價
    private var dirty: Set<String> = []          // 上次畫面取走之後，哪些交易對有變動

    // WebSocket 每收到一則就呼叫：只改記憶體，不碰畫面
    func apply(_ ticker: Ticker) {
        latest[ticker.symbol] = ticker
        dirty.insert(ticker.symbol)
    }

    // 畫面用固定節奏來取：只拿有變動的、而且是最新的
    func takeChanges() -> [Ticker] {
        let changed = dirty.compactMap { latest[$0] }
        dirty.removeAll()
        return changed
    }
}

// 依序呼叫 apply：BTC 100、ETH 50、BTC 101、BTC 102
// 第一次 takeChanges() 的結果：BTC_JPY=102、ETH_JPY=50（BTC 中間的 100、101 被合併掉了）
// 第二次 takeChanges() 的結果：空陣列（沒有新變動，畫面什麼都不用做）
```

這裡用 `actor`（Swift 的一種型別，保證同一時間只有一個地方在讀寫它的資料）保護字典，背景和畫面可以同時使用而不會互相踩到。

Combine 是 Apple 的資料流框架，`throttle` 可以限制送出的頻率。`latest: true` 的意思是：這個區間內有好幾筆時，送出最新的一筆（設成 `false` 則送出第一筆）：

```swift
tickerPublisher
    .throttle(for: .milliseconds(100), scheduler: DispatchQueue.main, latest: true)
    .sink { ticker in render(ticker) }   // 每 100 毫秒最多收到一次，而且一定是最新的
```

Android 的 Flow 是 Kotlin Coroutines 的非同步資料流，對應的工具是：

```kotlin
// conflate：消費者處理得慢時，丟掉中間的值，只留最新的一筆
tickerFlow
    .conflate()
    .collect { ticker -> render(ticker) }   // render 比較慢也沒關係，不會把資料堆積起來

// sample：固定每 100 毫秒，取這段期間內最新的一筆
// sample 在 kotlinx.coroutines 1.11 仍是預覽版功能（FlowPreview），要加 @OptIn 標註
@OptIn(FlowPreview::class)
val uiTickers = tickerFlow.sample(100)
```

兩者的差別是：`conflate()` 沒有固定頻率，消費者多快它就多快；`sample()` 和 `throttle(latest: true)` 有固定頻率。實務上常先用 `conflate()` 保護消費者，再用 `sample()` 控制畫面頻率。

**為什麼不能用 `debounce`？** `debounce` 的意思是「等資料安靜了一段時間，才送出最後一筆」。行情是持續進來的，永遠等不到安靜，結果是畫面一直收不到更新。

```text
資料到達：  a  b  c  d  e  f  g ...（每 30 毫秒一筆，一直進來）
debounce(100 毫秒)：永遠沒有「安靜 100 毫秒」的時候 → 一筆都不送
sample(100 毫秒)：   每 100 毫秒送出當時最新的一筆 → 穩定更新
```

> [!TIP]
> 面試時可以這樣說：「合併要每個交易對各留最新值，我會在背景用字典保存，畫面固定節奏來取。iOS 可以用 `bufferingNewest(1)` 或 Combine 的 `throttle(latest: true)`，Android 用 Flow 的 `conflate()` 或 `sample()`。行情不能用 `debounce`，因為資料一直來，它永遠等不到安靜，畫面就不會更新。」

### 3. 畫面多久更新一次？用什麼來驅動？

**列表上的價格每 100 到 250 毫秒更新一次通常就夠。可以用跟著螢幕重畫節奏的計時器：iOS 的 `CADisplayLink`、Android 的 `Choreographer`，或是 Flow 的 `sample()` 這類固定間隔的工具。**

`CADisplayLink` 是 iOS 在每次螢幕要重畫之前通知你一次的計時器。它預設每秒觸發 60 次（或更高），但行情列表不需要這麼頻繁，iOS 15 開始可以用 `preferredFrameRateRange` 告訴系統想要的頻率：

```swift
@MainActor
final class TickerRefresher {
    private var displayLink: CADisplayLink?
    private let store = TickerStore()

    func start() {
        let link = CADisplayLink(target: self, selector: #selector(tick))
        // 每秒大約 10 次就好，不需要 60 或 120 次
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 8, maximum: 15, preferred: 10)
        // .common：使用者捲動列表時，計時器也不會被暫停
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    func stop() {
        displayLink?.invalidate()   // 一定要呼叫，CADisplayLink 會強參考 target
        displayLink = nil
    }

    @objc private func tick() {
        Task {
            let changes = await store.takeChanges()   // 取出自上次以來有變動的交易對
            // 把 changes 套用到列表（見下一題）
        }
    }
}
```

這段程式有兩個重點：

- `.common` 是 run loop 的模式。run loop 是主執行緒上不斷處理事件的迴圈；使用者捲動畫面時，它會切換到另一個模式，只在預設模式下註冊的計時器會停下來。加到 `.common` 才能在捲動時繼續觸發。要不要在捲動時降低頻率或暫停，是另外的產品決定。
- `CADisplayLink` 會強參考（strong reference）它的 target，如果沒有呼叫 `invalidate()`，畫面關掉之後 `TickerRefresher` 也不會被釋放，造成記憶體洩漏。

Android 的 `Choreographer` 是系統在每一格畫面開始前呼叫你的機制，用 `postFrameCallback` 註冊。但在 Jetpack Compose（Android 的宣告式 UI 框架）或 ViewModel 裡，更常見的做法是直接用 Flow 的 `sample()`：

```kotlin
// ViewModel 裡：背景每一則都套用到 tickerStore，畫面每 100 毫秒才收到一次最新的整份資料
@OptIn(FlowPreview::class)
val rows: StateFlow<List<TickerRow>> = tickerStore.snapshots   // Flow<Map<String, Ticker>>
    .sample(100)
    .map { snapshot -> snapshot.values.map(::toRow) }
    .flowOn(Dispatchers.Default)   // 轉換在背景執行緒做，不佔用主執行緒
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

`stateIn` 把 Flow 變成 StateFlow（永遠有一個最新值、畫面可以隨時讀取的資料流）；`WhileSubscribed(5_000)` 的意思是畫面離開 5 秒後，才停止上游的工作。

**本單元的程式題 `ThrottleLatest` 就是這個「固定節奏取最新值」的規則。** 它把兩件事拆開：`offer(value)` 只負責記下最新值，後來的覆蓋先前還沒送出的；`tick(nowMs)` 由 display link 或計時器呼叫，傳入現在的時間（毫秒）。時間由呼叫端傳入，測試才不用等真實的時鐘。規則是：

- 沒有待送的值：不送，回傳空值。
- 有待送的值，而且「從來沒送過」或「距離上次送出已經過了 `intervalMs`」：送出並清除。
- 其他情況：什麼都不送，待送值保留到下次。
- 時鐘倒退（現在的時間比上次送出的時間還早）時不送出。
- 串流結束時，呼叫 `flush()` 把尾端還沒送出的值取走。

```swift
var throttle = ThrottleLatest<Int>(intervalMs: 100)
throttle.offer(1); throttle.offer(2)   // 2 蓋掉 1
print(throttle.tick(nowMs: 0))         // 輸出：Optional(2)（第一次，立刻送出最新的）
throttle.offer(3); throttle.offer(4)
print(throttle.tick(nowMs: 50))        // 輸出：nil（距離上次送出才 50 毫秒，待送的 4 保留）
print(throttle.tick(nowMs: 100))       // 輸出：Optional(4)（滿 100 毫秒，送出最新的）
print(throttle.tick(nowMs: 200))       // 輸出：nil（沒有新的值，不重複送出）
```

> [!TIP]
> 面試時可以這樣說：「列表上的價格我會每 100 到 250 毫秒更新一次。iOS 用 `CADisplayLink`，用 `preferredFrameRateRange` 把頻率壓低，加到 `.common` 模式讓捲動時也不會停，記得 `invalidate`；Android 用 Flow 的 `sample()` 或 `Choreographer`。時間從外面傳進來，這樣規則可以寫成單元測試。」

### 4. 列表怎麼只更新有變動的列？

**給每一列一個穩定的識別（例如交易對代號），資料更新時只通知「有變動的那幾列」，並且沿用既有的列、只更新裡面的內容，不要整個列表重建。**

如果每次價格變動都呼叫 `reloadData()`（iOS 把整個列表重新建立）或讓整個 `LazyColumn`（Compose 的捲動列表）重新組合，等於為了一檔價格變動，重畫 40 列。除了浪費，捲動位置、選取狀態和動畫都可能被重設。

**iOS（UIKit）：** diffable data source 是 Apple 提供的列表資料來源，用每個項目的識別來比對新舊兩份資料，算出哪些項目有變動。iOS 15 加入的 `reconfigureItems` 會沿用既有的 cell（列表的一列），只重新呼叫設定內容的程式；舊的 `reloadItems` 則會換掉整個 cell，丟掉選取狀態。

```swift
var snapshot = dataSource.snapshot()
snapshot.reconfigureItems(changedSymbols)   // changedSymbols：只有價格變動的交易對代號
dataSource.apply(snapshot, animatingDifferences: false)   // 價格跳動不需要列的位移動畫
```

這裡有個重點：**項目的識別要用交易對代號（字串），不要用包含價格的整個 struct。** 如果識別是整個 struct，價格一變，data source 就會當成「刪掉舊的一列、新增一列」，而不是「同一列的內容變了」。cell 設定內容的程式，再用代號到最新狀態裡讀價格。

**iOS（SwiftUI）：** `ForEach(rows, id: \.symbol)` 用交易對代號當識別；每一列只接收自己那一檔的資料，資料沒有變時就不重新計算畫面。這部分在 swift 的「SwiftUI 的 identity 與效能」單元有詳細說明。

**Android（View 系統）：** DiffUtil 是用來比較新舊兩份列表、算出哪些項目有變動的工具。`getChangePayload` 可以進一步告訴 adapter（把資料接到列表上的類別）「這一列只有價格變了」，`onBindViewHolder` 就只更新價格那個欄位：

```kotlin
class TickerDiff : DiffUtil.ItemCallback<TickerRow>() {
    // 是不是同一檔交易對（同一列）
    override fun areItemsTheSame(old: TickerRow, new: TickerRow) = old.symbol == new.symbol

    // 同一列的內容有沒有變
    override fun areContentsTheSame(old: TickerRow, new: TickerRow) = old == new

    // 變了的話，告訴 adapter「只有價格變了」
    override fun getChangePayload(old: TickerRow, new: TickerRow): Any? =
        if (old.priceText != new.priceText) PRICE_CHANGED else null
}

// onBindViewHolder(holder, position, payloads) 裡：
// payloads 含 PRICE_CHANGED 時，只更新價格欄，不重新設定整列
```

**Android（Compose）：** `items(rows, key = { it.symbol })` 讓 Compose 知道哪一列是哪一檔交易對，即使列表順序改變，也能沿用原本那一列。另外，每一列只接收自己那一檔的資料（用不可變的 data class），並把顯示數字的部分拆成小的 composable，讓重新組合的範圍縮到最小。每秒變動很多次的值（例如閃爍的透明度），可以把讀取延後到 layout 或 draw 階段，用 `Modifier.graphicsLayer { alpha = ... }` 或 `drawBehind { }`，這樣它變動時只會重新繪製，不會觸發整個 composable 重新組合。這部分在 kotlin 的「Compose 效能」單元有詳細說明。

畫面外的列不需要特別更新：列表只會建立畫面上看得到的列，捲動進來時，會用最新狀態設定內容。

> [!TIP]
> 面試時可以這樣說：「列表的識別我會用交易對代號，資料更新時只通知有變動的列。iOS 用 diffable data source 搭配 `reconfigureItems`，不重建 cell；Android View 用 DiffUtil 的 payload 只更新價格欄，Compose 用 `key = symbol`，高頻變動的值把讀取延後到 draw 階段。」

### 5. 價格變動的閃爍提示怎麼做？

**比較「這次要顯示的價格」和「上次畫在畫面上的價格」，得到方向（上漲、下跌、持平），讓背景色或文字色短暫變色，約 200 到 300 毫秒內淡出。同時加上 ▲▼ 符號，不要只靠顏色。**

要拿來比較的是「上次畫在畫面上的價格」，不是「上一則推播」。因為合併之後，中間的價格已經丟掉，使用者根本沒看過。例如推播依序是 100、105、103，畫面只畫了 100 和 103。如果拿上一則推播（105）比較，103 會被判斷成下跌，閃出下跌的顏色；但使用者看到的是數字從 100 變成 103，明明是上漲。

```swift
enum PriceDirection { case up, down, flat }

// old 是「上次畫在畫面上的價格」，第一次顯示時沒有舊值
func direction(old: Decimal?, new: Decimal) -> PriceDirection {
    guard let old else { return .flat }
    if new > old { return .up }
    if new < old { return .down }
    return .flat
}

// 只在畫面真的更新時呼叫，不是每收到一則推播就呼叫
func flash(_ label: UILabel, direction: PriceDirection, style: FlashStyle) {
    // 持平，或使用者在系統設定開了「減少動態效果」：不做動畫
    guard direction != .flat, !UIAccessibility.isReduceMotionEnabled else { return }
    // 漲跌的顏色由使用者設定決定（style.upColor／style.downColor）
    label.backgroundColor = direction == .up ? style.upColor : style.downColor
    UIView.animate(withDuration: 0.25) { label.backgroundColor = .clear }   // 250 毫秒淡出
}
```

做提示時有幾個細節：

- **只在合併後的更新觸發。** 動畫的頻率要和畫面刷新的頻率一致，不要每一則推播都觸發動畫。列表捲動時也可以暫停動畫，避免跟使用者的操作搶資源。
- **不要只靠顏色。** 有色覺異常的使用者分不出紅綠，截圖時也看不出方向。加上 ▲▼ 符號，資訊就不會只存在顏色裡。
- **漲跌的顏色慣例因地區和產品而異。** 亞洲部分市場是紅漲綠跌，歐美相反。各家交易所採用哪一種不同，所以做成使用者可以設定。
- **尊重「減少動態效果」。** iOS 要自己讀 `UIAccessibility.isReduceMotionEnabled`；Android 可以用 `ValueAnimator.areAnimatorsEnabled()`（Android 8.0、API level 26 起；API level 是 Android 版本對應的編號）判斷系統是否關閉了動畫。
- **動畫要用系統提供的動畫功能**（`UIView.animate`、`ViewPropertyAnimator`、Compose 的 `animate*AsState`），不要用 `Thread.sleep` 這類卡住執行緒的做法來控制顏色停留多久。

> [!TIP]
> 面試時可以這樣說：「閃爍提示是拿這次顯示的價格和上次畫在畫面上的價格比較，只在畫面實際更新時觸發，約 250 毫秒淡出。除了顏色還會加 ▲▼ 符號，漲跌顏色做成可設定，並且尊重系統的減少動態效果。」

### 6. 數字怎麼顯示，才不會抖動、也不會拖慢畫面？

**三件事：價格欄用等寬數字、formatter 依精度各建一個重複使用、最好在背景就把要顯示的字串算好，畫面只負責指派。**

**等寬數字（tabular figures）。** 一般字型的數字寬度不一樣，例如「1」比「8」窄，價格從 `1,111` 變成 `8,888`，文字寬度就變了，整欄會左右抖動。等寬數字讓每個數字一樣寬，更新時就不會抖。

| 平台 | 寫法 |
|---|---|
| SwiftUI | `.monospacedDigit()` |
| UIKit | `UIFont.monospacedDigitSystemFont(ofSize:weight:)` |
| Android（View） | `android:fontFeatureSettings="tnum"` |
| Android（Compose） | `TextStyle(fontFeatureSettings = "tnum")` |

`tnum` 是 OpenType 字型功能 tabular numbers 的代號。另外，數字要靠右對齊，價格欄給固定寬度（或最小寬度），避免版面跟著數字長度晃動；千分位和小數位數依交易對的精度決定（見「精度與金額」單元）。

**Formatter 要重用，也要注意執行緒。** `NumberFormatter`（iOS）和 `DecimalFormat`（Android）建立的成本很高，要依精度各建一個、重複使用，不要在列表每一列設定內容時新建。兩者的執行緒規則不同：

- iOS 的 `NumberFormatter` 多個執行緒同時「使用」是安全的，但同時「修改它的屬性」不安全。所以共用的 formatter 不要邊用邊改設定（例如每次呼叫都改 `maximumFractionDigits`）。
- Android 的 `DecimalFormat` 本身不是 thread-safe（多個執行緒同時使用會出錯），要每個執行緒各一個，或集中在同一個執行緒使用。
- iOS 15 的 `FormatStyle` 是值型別，沒有共用的可變狀態，可以直接用。

**最好的做法：在背景就把顯示字串算好。** 背景管線把資料轉成「列表一列要顯示的內容」，畫面只負責把字串指派上去，主執行緒完全沒有格式化的成本，也不用在主執行緒建立 formatter：

```swift
// 在背景算好，畫面只指派
struct TickerRowModel: Equatable, Sendable {
    let symbol: String
    let priceText: String      // 例如 "10,234,567"
    let changeText: String     // 例如 "+1.23%"
    let direction: PriceDirection
}
```

指派時也可以先比較：`if label.text != row.priceText { label.text = row.priceText }`。文字沒變就不重設，省下不必要的工作；在每秒更新很多次的畫面上，省下的工作會累積。

> [!TIP]
> 面試時可以這樣說：「價格欄我會用等寬數字，避免更新時寬度抖動。formatter 依精度各建一個重複使用，不在 cell 設定內容時新建，也不邊用邊改共用的 formatter；格式化放在背景，算好 `priceText`、`changeText` 和方向，畫面只負責指派。」

### 7. 怎麼證明畫面真的變順了？（比較少考，被問到能答就好）

**先量再改。** 改之前和改之後，都要記錄三個數字：每秒畫面更新的次數、主執行緒的忙碌程度、掉幀數（畫面該更新卻沒趕上的次數）。

- iOS：Instruments 的 Time Profiler（看主執行緒的時間花在哪些函式）、Hitches（找出畫面卡頓）和 SwiftUI 模板。
- Android：Perfetto（系統層級的追蹤工具）、JankStats（Jetpack 的函式庫，回報掉幀）、Layout Inspector 的重組次數（Compose 重新組合的計數），以及 Macrobenchmark（自動化量測捲動等效能）。

> [!TIP]
> 面試時可以這樣說：「最佳化之前我會先量：每秒更新畫面幾次、主執行緒多忙、掉幀幾次。iOS 用 Instruments，Android 用 Perfetto 和 JankStats，改完再量一次確認有改善。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange06RealtimeUI**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange06RealtimeUI**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange06-RealtimeUI/`，解答在 `labs/ios/Solution/Exchange/Exchange06-RealtimeUI/`；Android 你寫在 `labs/android/practice/exchange/exchange06-realtime-ui/`，解答在 `labs/android/solution/exchange/exchange06-realtime-ui/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的連 WebSocket**：前三個演練用虛擬的時間（毫秒數當參數傳進來）和假的畫面，每次執行的結果都一樣；演練 4 會真的開兩條執行緒同時格式化，錯誤次數每次不同，但 Bug 版一定大於 0。

### 演練 1：行情停了，畫面停在舊價格（第 3 題）

**檔案**：`L01_ThrottleTrailing.swift`（iOS）、`L01_ThrottleTrailing.kt`（Android）

**情境**：行情每 30 毫秒來一筆價格（價格 = 100 + 第幾筆），從 0 毫秒到 330 毫秒共 12 筆，最新價是 111。畫面每 100 毫秒更新一次（降低頻率）：0、100、200、300 毫秒，畫面的計時器各響一次；330 毫秒是最後一筆，之後行情停了，計時器也跟著停。演練把規則寫成三個函式：`onValue`（一則價格到達）、`onTick`（計時器響了）、`onEnd`（串流結束），時間都從參數傳進來。

**怎麼寫**

1. **Bug 版**：`onValue` 只在「距離上次送給畫面超過 100 毫秒」時才回傳這個價格（並記下 `lastEmitMs`），不到 100 毫秒就回傳 `nil`（Android 是 `null`），把價格丟掉；`onTick`、`onEnd` 什麼都不送。
2. **修正版**：ThrottleLatest：`onValue` 只記下最新的值（`pending`，蓋掉還沒送出的），不送；`onTick` 在有 `pending`、而且距離上次送出超過 100 毫秒時，送出並清掉；`onEnd` 把還沒送出的 `pending` 取走。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面依序顯示 100、104、108，最後三筆價格被丟掉：「最後：畫面顯示 108，最新價是 111」 |
| 修正版 | 畫面依序顯示 100、103、106、110，串流結束再顯示 111：「最後：畫面顯示 111，最新價是 111」 |

**為什麼**：降低更新頻率時，中間的價格丟掉沒有關係，使用者本來就看不到；但「最新的一筆」不能丟。Bug 版的寫法在行情一直進來的時候看不出問題，因為下一筆很快就來補上；一旦行情停了（收盤、休市、斷線前最後一筆），最後一筆剛好落在 100 毫秒之內，就再也沒有人送它，畫面停在舊價格。所以規則是：先記下最新值，由計時器固定節奏送出，串流結束時再把尾端還沒送出的值取走。筆記還提到時鐘倒退時不送出、待送的值保留，這個演練沒有涵蓋。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L01_ThrottleTrailing.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L01_ThrottleTrailing.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L01_ThrottleTrailing.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L01_ThrottleTrailing.kt#fixed
```

### 演練 2：價格一跳，選取的那一列就取消選取了（第 4 題）

**檔案**：`L02_RowIdentity.swift`（iOS）、`L02_RowIdentity.kt`（Android）

**情境**：行情列表有三列（BTC_JPY、ETH_JPY、XRP_JPY）。使用者點了 ETH_JPY，那一列變成選取狀態，狀態存在「列」這個物件上。接著價格更新三次：第 1 次 BTC 和 ETH 變、第 2 次三檔都變、第 3 次只有 ETH 變。列表用「識別」（identity）決定新的一份資料對應到哪一個既有的列：對得上就沿用那一列（只換內容），對不上就新建一列。演練用 `ListView` 模擬這個對應過程，diffable data source 的項目、SwiftUI 的 `ForEach` 的 id、Compose 的 `key`、DiffUtil 的 `areItemsTheSame`，都是這個意思。

**怎麼寫**

1. **Bug 版**：識別 = 整個 `Row`（交易對代號加價格），例如 iOS 的 `"\(row)"`、Android 的 `row.toString()`。
2. **修正版**：識別 = `row.symbol`（交易對代號）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 價格有變的列都被新建：第 1 次新建 2 列、選取變成「沒有」，「最後：選取 沒有，更新期間共新建 6 列，沿用 3 列，識別重複 0 次」 |
| 修正版 | 三次更新都沿用 3 列、不新建，選取一直是 ETH_JPY：「最後：選取 ETH_JPY，更新期間共新建 0 列，沿用 9 列，識別重複 0 次」 |

**為什麼**：識別要用「不會變的東西」。把價格放進識別，價格一變，列表就看不出這是同一列，當成「刪掉舊的一列、新增一列」：選取、展開、輸入到一半的文字這些存在列上的狀態都會消失，動畫也會重播，而且每次都重建整列，白白浪費主執行緒的時間。行情的價格每秒變很多次，所以選取一檔幣種之後，狀態幾百毫秒內就消失。正確做法是用交易對代號當識別，價格變了還是同一列，沿用既有的列、只換內容。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L02_RowIdentity.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L02_RowIdentity.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L02_RowIdentity.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L02_RowIdentity.kt#fixed
```

### 演練 3：數字漲了，閃爍卻是下跌的顏色（第 5 題）

**檔案**：`L03_FlashDirection.swift`（iOS）、`L03_FlashDirection.kt`（Android）

**情境**：行情推播會先合併，畫面每隔一段時間才更新一次，中間的價格使用者根本沒看過。推播依序是：100，畫面更新；105、103，畫面更新；103，畫面更新。使用者看到的價格是 100、103、103：第二次畫面更新，數字從 100 變成 103，是上漲。第一次顯示沒有「上次畫在畫面上的價格」，不閃爍。`direction(old:new:)` 已經寫好：沒有舊值是持平，新的比舊的大是上漲，小是下跌。

**怎麼寫**

1. **Bug 版**：`refresh` 拿「上一則推播」（`previousPush`）和要顯示的價格（`latest`）比：`direction(old: previousPush, new: latest)`。
2. **修正版**：記下「上次畫在畫面上的價格」（`lastShown`），`refresh` 拿它和 `latest` 比，比完把 `lastShown` 換成 `latest`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第二次畫面更新顯示 103，卻是「▼ 下跌」：「最後：三次畫面更新的閃爍方向：－ 持平、▼ 下跌、－ 持平」 |
| 修正版 | 「最後：三次畫面更新的閃爍方向：－ 持平、▲ 上漲、－ 持平（使用者看到的價格：100 → 103 → 103）」 |

**為什麼**：閃爍提示是給使用者看的：它要回答的是「我眼前這個數字，和我上一次看到的相比，是漲還是跌」。合併之後，中間的 105 已經被丟掉，使用者從來沒看過；拿 103 和 105 比，就會出現「數字上漲卻閃下跌顏色」。所以要比較的是「這次要顯示的價格」和「上次畫在畫面上的價格」，而且只在畫面真的更新時才比。除了顏色，還要加 ▲▼ 符號，並尊重系統的減少動態效果設定。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L03_FlashDirection.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L03_FlashDirection.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L03_FlashDirection.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L03_FlashDirection.kt#fixed
```

### 演練 4：同時格式化價格，小數位數錯亂（第 6 題）

**檔案**：`L04_SharedFormatter.swift`（iOS）、`L04_SharedFormatter.kt`（Android）

**情境**：行情列表的價格要依交易對的精度顯示：BTC_JPY 0 位小數（10,234,568）、XRP_JPY 4 位小數（0.1235）。背景有兩個工作同時在格式化價格，一個格式化 BTC 的價格、一個格式化 XRP 的價格，各 10 萬次，每次都和「單獨格式化的正確答案」比對，數有幾次字串不對。這個演練會真的開兩條執行緒，所以錯誤的次數每次執行都不同。

**怎麼寫**

1. **Bug 版**：App 只建一個格式化器共用（iOS 的 `NumberFormatter`、Android 的 `DecimalFormat`）；每次格式化都先把 `minimumFractionDigits`、`maximumFractionDigits` 設成這次要的精度，再格式化。
2. **修正版**：**iOS**：依精度各建一個 `NumberFormatter`（0 到 4 位小數各一個），建好之後不再修改，格式化時依精度取用。**Android**：每個執行緒各有一組 `DecimalFormat`（用 `ThreadLocal`），建好之後不再修改。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 字串不對的次數大於 0（iOS 實測每 20 萬次約幾百次，Android 約幾千到幾萬次，每次都不同）：BTC 的價格被格式化成 4 位小數，XRP 的價格被格式化成整數，甚至丟出例外（Android） |
| 修正版 | 「字串不對的有 0 次（全部正確）」 |

**為什麼**：兩條執行緒各做「改設定、再格式化」兩個步驟，中間可能被另一條插進來：這條剛把小數位數設成 0，另一條就把它設成 4，這條接著格式化，就得到 4 位小數。筆記整理的規則是：iOS 的 `NumberFormatter` 多個執行緒同時「使用」是安全的，但同時「修改它的屬性」不安全，所以共用的 formatter 不要邊用邊改設定；Android 的 `DecimalFormat` 本身不是 thread-safe，要每個執行緒各一個，或集中在同一個執行緒使用。更好的做法是在背景就把顯示字串算好，畫面只負責指派（見 Swift08 演練 7）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L04_SharedFormatter.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L04_SharedFormatter.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L04_SharedFormatter.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L04_SharedFormatter.kt#fixed
```

### 演練 5：字體放大後，價格被截斷成「12,345,…」（第 6 題）

**檔案**：`L05_DynamicTypeTruncation.swift`（iOS）、`L05_FontScaleTruncation.kt`（Android）

**情境**：行情列有交易對（BTC_JPY）與價格（12,345,678）。團隊為了讓價格欄上下對齊，給價格欄一個固定寬度，而且限定只顯示一行。使用者在系統設定把字體調大（iOS 的「更大的文字」拉到最大，也就是 Dynamic Type 的 `accessibilityExtraExtraExtraLarge`；Android 的「字型大小」，字體縮放 `fontScale` 最大約 2.0），價格被截斷，看不到完整的價格就下單。數字怎麼顯示不只是等寬與 formatter，連「放得下嗎」都要想。

**怎麼寫**

1. **Bug 版**：**iOS**：價格 `Text` 加 `.lineLimit(1)`、`.truncationMode(.tail)`，外面用 `.frame(width: 100, alignment: .trailing)` 寫死寬度。**Android**：價格 `Text` 用 `Modifier.width(96.dp)`，加 `maxLines = 1`、`overflow = TextOverflow.Ellipsis`。
2. **修正版**：**iOS**：不寫死寬度、不限一行，用 `ViewThatFits(in: .horizontal)` 放兩種排法：放得下就交易對與價格並排（`HStack`），放不下就上下排（`VStack`）。**Android**：不寫死寬度、不限一行：改用 `Modifier.widthIn(min = 96.dp)`（最小寬度，字變大就跟著變寬），拿掉 `maxLines` 與 `overflow`。

自動檢查怎麼量：**iOS** 把行情列分別用預設字體與最大字體畫在螢幕外（`.dynamicTypeSize(.accessibility5)`），量價格欄實際拿到的寬度，和「這串價格完整寫出來需要的寬度」（用一個看不見的複本量）比較。**Android** 這題在電腦上跑單元測試（Robolectric，不用模擬器），不用按「執行」：點 `test/Exchange06L05Test.kt` 的 `l05()` 旁邊的 ▶，或在終端機執行 `./gradlew :practice-exchange:testDebugUnitTest --tests '*Exchange06L05Test*' -i | grep SELFTEST`。測試用 `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` 把字體縮放設成 1.0、1.3、2.0，再讀 `onTextLayout` 回傳的 `TextLayoutResult.hasVisualOverflow`（文字被截斷時是 true）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | **iOS**：`預設字體：價格需要 92 pt，價格欄拿到 100 pt，完整顯示`；`最大字體（accessibilityExtraExtraExtraLarge）：價格需要 282 pt，價格欄拿到 100 pt，沒顯示完整`；`價格欄寬度不隨字體變化（寫死）`。**Android**：字體縮放 1.0 完整顯示，1.3 與 2.0 被截斷，價格欄寬度三種縮放都一樣。畫面上價格變成「12,345,…」 |
| 修正版 | **iOS**：兩種字體都「完整顯示」，最大字體時價格欄的寬度是 282 pt（預設是 92 pt），交易對移到上一行。**Android**：三種字體縮放都完整顯示，字體縮放 2.0 的價格欄比 1.0 寬 |

**為什麼**：固定寬度在預設字體剛好放得下，使用者放大字體之後，數字需要的寬度跟著變大，固定寬度就不夠了；加上「只顯示一行」，放不下的部分就被截斷成「…」。價格、金額、數量這類資料少看一個字就是錯的，不能用截斷處理。筆記說價格欄要「固定寬度（或最小寬度）」是為了避免版面跟著數字長度晃動：用最小寬度（`widthIn(min:)`）可以兼顧，字變大時價格欄就跟著變寬；真的放不下就換排版，不要截斷。另一種常見做法是 `minimumScaleFactor`（iOS）、`autoSize`（Android）讓數字縮小，但最大字體下縮小之後還是很小，也會讓使用者看不清楚；這個演練的 iOS 自動檢查只認「價格拿到完整的寬度」，所以請用換排版。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L05_DynamicTypeTruncation.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L05_DynamicTypeTruncation.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L05_FontScaleTruncation.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L05_FontScaleTruncation.kt#fixed
```

### 演練 6：螢幕閱讀器只唸數字和「按鈕」，UI 測試也找不到元素（第 6 題）

**檔案**：`L06_Accessibility.swift`（iOS）、`L06_Accessibility.kt`（Android）

**情境**：行情列有交易對 BTC_JPY、價格 12,345,678、漲跌（只用綠色向上的 ▲ 表示上漲 1.23%），最右邊是一顆只有購物車圖示的下單按鈕。視障使用者開螢幕閱讀器（iOS 的 VoiceOver、Android 的 TalkBack）要能聽到「什麼幣、價格、漲還是跌、可以下單」；QA 寫 UI 測試（iOS 的 XCUITest、Android 的 Compose UI 測試）要能用固定的名稱找到這一列和那顆按鈕。演練把這一列畫出來，讀出螢幕閱讀器看到的元素：標籤、值、固定名稱、是不是按鈕。

**怎麼寫**

1. **Bug 版**：漲跌只靠顏色與箭頭，下單按鈕只有圖示，都不加任何無障礙修飾。**iOS**：`Image(systemName: "arrow.up")` 加 `.foregroundStyle(.green)`，按鈕是 `Button(action: {}) { Image(systemName: "cart") }`。**Android**：`Text("▲", color = …)`，按鈕是 `IconButton` 裡面的 `Image(…, contentDescription = null)`。
2. **修正版**：行情那一塊合成一個元素，把幣種、價格、漲跌唸完整，並給固定名稱；圖示按鈕加名稱。**iOS**：`.accessibilityElement(children: .ignore)` 加 `.accessibilityLabel`（幣種）、`.accessibilityValue`（`12,345,678 日圓，上漲 1.23%`）、`.accessibilityIdentifier("ticker.BTC_JPY.quote")`；按鈕加 `.accessibilityLabel("下單 BTC_JPY")` 與 `.accessibilityIdentifier("ticker.BTC_JPY.order")`。**Android**：`Modifier.clearAndSetSemantics { contentDescription = …; stateDescription = …; testTag = "ticker.BTC_JPY.quote" }`；按鈕的 `Image` 加 `contentDescription = "下單 BTC_JPY"`，`IconButton` 加 `Modifier.testTag("ticker.BTC_JPY.order")`。

自動檢查怎麼量：**iOS** 用 `SwiftUILabStage` 把行情列畫在螢幕外，走訪它的 accessibility 樹（SwiftUI 只有在系統的無障礙開著時才會建立這棵樹，檢查時會像 XCUITest 一樣暫時打開），印出每個元素唸出的文字與 identifier。**Android** 這題在電腦上跑單元測試（Robolectric，不用模擬器），不用按「執行」：點 `test/Exchange06L06Test.kt` 的 `l06()` 旁邊的 ▶，或在終端機執行 `./gradlew :practice-exchange:testDebugUnitTest --tests '*Exchange06L06Test*' -i | grep SELFTEST`。測試讀 Compose 的 semantics 樹（TalkBack 看到的就是這棵樹），印出每個元素的文字、`contentDescription`、`stateDescription` 與 `testTag`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 螢幕閱讀器看到 4 個元素（交易對、價格、箭頭、按鈕各一個）：箭頭只唸 `Up`（iOS）、按鈕只唸 `Shopping Cart，按鈕`（iOS）或「（沒有名稱），按鈕」（Android）；`漲跌有被唸出來（提到「上漲」）：否`、`下單按鈕有名稱（提到「下單」）：否`（iOS）；兩個固定名稱都找不到 |
| 修正版 | 只剩 2 個元素：`BTC 兌日圓，12,345,678 日圓，上漲 1.23%` 與 `下單 BTC_JPY，按鈕`；兩個固定名稱（`ticker.BTC_JPY.quote`、`ticker.BTC_JPY.order`）都找得到 |

**為什麼**：螢幕閱讀器唸的是畫面元素的「名稱」和「值」，不是使用者看到的樣子。只有顏色和箭頭的漲跌，視障與色覺異常的使用者都得不到資訊；只有圖示的按鈕沒有名稱，聽到的只是「按鈕」。把一整列合成一個元素，讓使用者一次聽完「哪個幣、多少錢、漲跌」，不用在交易對、價格、箭頭三個元素之間來回滑動。固定名稱（`accessibilityIdentifier`、`testTag`）是給自動化測試用的，不會被唸出來，改文案時測試不會壞。數字顯示（第 6 題）除了等寬與 formatter，還包括「怎麼唸、怎麼被找到」。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L06_Accessibility.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange06-RealtimeUI/L06_Accessibility.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L06_Accessibility.kt#bug
```

```lab-solution
android/solution/exchange/exchange06-realtime-ui/L06_Accessibility.kt#fixed
```

第 1、2 題的合併與節流，前面的單元已經練過，這個單元不重複：統計值要在合併之前算（Swift06 演練 2、Kotlin05 演練 5 的 order book 增量）、背景合併再用固定頻率更新畫面（Platform02 演練 8）、`debounce` 讓畫面一直不更新（Swift06 演練 6、Kotlin05 演練 4）、每個交易對各留一筆最新值（Advanced05 演練 2）。第 3 題的 `CADisplayLink` 要用 `.common` 模式和 `invalidate`（Advanced05 演練 4）、第 4 題的列表沒給 `key` 或用 index 當識別（Swift08 演練 2、Kotlin06 演練 7、Kotlin08 演練 3）、`reconfigureItems`（Advanced02 演練 4）、第 6 題 formatter 不要在 `body` 裡建立（Swift08 演練 7），也都在那些單元練過。第 7 題是量測的流程題（先量再改），沒有演練。

## 常見陷阱

- **每收到一則推播就更新一次畫面。** 主執行緒會被更新洪水塞滿。背景每一則都套用到最新狀態，畫面用固定節奏取快照。
- **40 個交易對共用一條只留 1 筆的資料流。** 一個交易對的更新會把另一個交易對還沒被讀走的最新價擠掉。要每個交易對各留一筆（用字典，或一個交易對一條 stream）。
- **用 `debounce` 處理行情。** 資料持續進來時，永遠等不到安靜，畫面收不到更新。要用 `sample`、`throttle(latest: true)` 這類固定頻率取最新值的工具。
- **拿合併後的資料去算統計。** 最高價、最低價、成交量要在合併之前、每一則都算。合併只用在畫面要顯示的最新值。
- **閃爍方向拿上一則推播比較。** 合併後中間的價格使用者沒看過，會出現「數字上漲卻閃下跌顏色」。要和上次畫在畫面上的價格比較。
- **用 `reloadData()`，或讓整個 `LazyColumn` 重新組合，只為了一檔價格變動。** 要用穩定的識別，只更新有變動的列。
- **diffable data source 的項目識別用包含價格的整個 struct。** 價格一變就變成「刪一列、加一列」。識別要用交易對代號。
- **只靠顏色表達漲跌。** 色覺異常的使用者和截圖都看不出方向，要加上 ▲▼ 符號，顏色慣例也要能設定。
- **價格欄沒有用等寬數字。** 價格更新時文字寬度會跳動。
- **在 cell 設定內容時新建 formatter，或在主執行緒做大量格式化。** formatter 建立成本高，要依精度重複使用，格式化放到背景。
- **`CADisplayLink` 沒有 `invalidate()`。** 它會強參考 target，畫面關掉後物件不會被釋放。

## 面試官想聽到的

1. 能區分資料頻率、狀態頻率和畫面頻率，說明合併發生在「最新狀態」和「畫面」之間，並說得出為什麼畫面約 100 到 250 毫秒更新一次就夠。
2. 知道兩個平台各自的工具，並說得出差別：`bufferingNewest(1)`、`throttle(latest: true)`、`conflate()`、`sample()`，以及為什麼行情不能用 `debounce`。
3. 知道合併要「每個交易對各留最新值」，統計值要在合併之前算。
4. 說得出只更新有變動的列怎麼做：穩定的識別、`reconfigureItems`、DiffUtil payload、Compose 的 `key`，而不是整個列表重建。
5. 閃爍提示、等寬數字、減少動態效果這些細節，以及 formatter 的快取與執行緒規則，並且主張「先量再改」。

> [!FIELD]
> 在一個加密貨幣市場分析 App 裡，即時 K 線用 SSE（Server-Sent Events，伺服器單向把資料推給 App 的串流，走一般的 HTTP 網頁通訊協定）更新最後一根；清算地圖、鯨魚追蹤等其他分析畫面，則是固定間隔自動重新整理，App 進入背景時停止、回到前景時恢復。Android 的圖表元件在資料沒有變化時不會重設，是用資料的雜湊值（hash code）判斷。
> 那個 App 的即時資料只有單一交易對，每個事件直接更新就夠。到交易所面對多個交易對的行情列表，我會在背景合併，用固定節奏取快照，只更新有變動的列，並在背景預先算好顯示字串。
> 面試時可以這樣說：「我做過固定間隔的刷新和進背景暫停，也處理過圖表資料沒變就不重設。到交易所的行情列表，我會把資料頻率和畫面頻率分開，在背景合併，只更新有變動的列。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 只保留最新值 | `AsyncStream` 的 `bufferingNewest(1)` | Flow 的 `conflate()` |
| 固定節奏取最新值 | Combine 的 `throttle(latest: true)` | Flow 的 `sample()` |
| 驅動畫面的計時器 | `CADisplayLink`（加到 `.common` 模式，記得 `invalidate`） | `Choreographer`；或用 `sample()` |
| 局部更新列表 | diffable data source 搭配 `reconfigureItems`（iOS 15） | DiffUtil payload；Compose 的 `key` |
| 只重畫變動的部分 | SwiftUI 每一列只接收自己的值 | Compose 把讀取延後到 layout／draw 階段 |
| 等寬數字 | `.monospacedDigit()` | `fontFeatureSettings = "tnum"` |
| 預先格式化 | 在背景的 actor 或 queue 裡產生 `String` | 在背景的 dispatcher 裡產生 `String` |
| 減少動態效果 | `UIAccessibility.isReduceMotionEnabled` | `ValueAnimator.areAnimatorsEnabled()`（Android 8.0 起） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 合併，只留最新值 | conflation | コンフレーション（最新値だけを残す） |
| 固定節奏取最新值 | throttle（latest） | スロットル |
| 等安靜才送出 | debounce | デバウンス |
| 等寬數字 | tabular figures／monospaced digits | 等幅数字 |
| 螢幕更新率 | refresh rate | リフレッシュレート |
| 掉幀 | dropped frame／hitch | コマ落ち |
| 局部更新列表 | diff 更新（diffable data source） | 差分更新 |
