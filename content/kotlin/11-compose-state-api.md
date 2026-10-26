---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽（SwiftUI 對照版）：@State、@Binding、@Observable 在 Compose 裡叫什麼
summary: 給熟 SwiftUI、還沒寫過 Jetpack Compose 的 iOS 工程師。每個 API 都先說 SwiftUI 裡你會怎麼做，再給 Compose 的等價寫法，最後只講兩邊的差異（例如 remember 與 @State、rememberSaveable 與 @SceneStorage、StateFlow 與 @Published），並用同一張下單表單帶出 mutableStateOf、SnapshotMutationPolicy、derivedStateOf、collectAsStateWithLifecycle 等 API，最後給一張「情境、SwiftUI、Compose」三欄的速查表。
priority: P0
day: 5
tags: [compose, state, recomposition, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元寫給熟 SwiftUI、還沒寫過 Jetpack Compose 的 iOS 工程師。`@State`、`@Binding`、`@Observable`、`@Environment`、`.task`、`@SceneStorage` 你都會，也知道 `body` 會重算、`@State` 靠 view 在樹上的位置找回來，所以每個 API 都先說 SwiftUI 怎麼做，再給 Compose 的等價寫法，最後只講差異。不是一對一的地方，標「大致對應」。

這個單元回答九個面試常見問題：

1. Composable、State、`remember` 對應 SwiftUI 的什麼？差在哪？
2. `mutableStateOf`：`by` 和 `.value` 差在哪？怎麼只重畫價格那一行？
3. `mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？
4. `SnapshotMutationPolicy`（SwiftUI 沒有）什麼時候要換？
5. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？
6. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？
7. `collectAsState` 還是 `collectAsStateWithLifecycle`？
8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態放哪一層？
9. 情境對應 SwiftUI 與 Compose 該用哪個 API？

範例是同一張「下單表單」：交易對、數量、價格三個欄位和一個送出按鈕，下面一條一直跳動的即時價格，送出後底下多一筆委託。重組的細節見 Kotlin06，effect 見 Kotlin07，Flow 見 Kotlin05。

### 1. Composable、State、`remember` 對應 SwiftUI 的什麼？差在哪？

**`@Composable` 函式對應 `View.body`，State 一變就重新執行；`remember { mutableStateOf(…) }` 對應 `@State`。最大的差別是：Compose 要你自己用 `remember` 存值，忘了就在每次重組時歸零，SwiftUI 的 `@State` 沒有這個坑。** 按「送出」顯示已送出幾筆，SwiftUI 你會寫 `@State private var sent = 0`，按鈕裡 `sent += 1`。Compose 的等價寫法：

```kotlin
@Composable
fun SubmitCounter() {
    var sent by remember { mutableIntStateOf(0) }    // 對應 @State
    Column {
        Text("已送出：$sent 筆")                       // 讀取 sent：Compose 記下這裡依賴它
        Button(onClick = { sent++ }) { Text("送出") }  // 寫入 sent：重組讀過它的 Composable
    }
}
```

函式每次重新執行叫重組（recomposition），像 `body` 重算。兩種常見的錯：普通的 `var sent = 0` 能編譯也能改，但沒人通知 Compose，畫面不動，類似 `ObservableObject` 的屬性忘了 `@Published`；用了 State 卻漏掉 `remember`，每次重組都重建一個值為 0 的新 State，畫面永遠是 0。

`remember` 和 `@State` 都靠「在樹上的位置」找回來（Compose 存在依呼叫位置編號的 slot table），所以換 `if` 分支、`key()` 的值變了（像 `.id()`），或 `LazyColumn` 的項目被回收，值都會重置。重組的單位也不同：SwiftUI 以 view 為單位重算 `body`，Compose 以 Composable 函式為單位，參數沒變、型別又是 stable（穩定，編譯器確定不會被偷偷改；不穩定的情況見 Kotlin06）的就跳過（SwiftUI 也會比對子 view 的屬性，大致對應），所以第 2 題「把讀取往下推」的效益更直接。

> [!TIP]
> 面試時可以這樣說：「Composable 像 body，State 像 @State，但一定要 remember，否則重組就歸零。」

### 2. `mutableStateOf`：`by` 和 `.value` 差在哪？怎麼只重畫價格那一行？

**`mutableStateOf` 建立 Compose 盯著看的 State，只有「讀過它」的 Composable 會在它變時重組，所以重畫範圍取決於讀取發生在哪。**

**`by` 和 `.value`。** SwiftUI 裡 `price` 是值（`wrappedValue`），`$price` 是可以傳下去的 `Binding`（`projectedValue`）。Compose 的 `var price by remember { … }` 像直接用 `price`；不寫 `by`，`val price = remember { … }` 拿到 State 本體，讀寫要用 `.value`，大致像 `$price`，但它是儲存本體，不是 get／set 包出來的 `Binding`，傳給子元件更常用 state hoisting（第 8 題）。`by` 是 Kotlin 的屬性委派（屬性的讀寫轉給別的物件），要 import `getValue` 和 `setValue`。

**把讀取往下推。** SwiftUI 你會拆小 subview；Compose 同樣，但傳下去的是 State 本身（或 lambda），讀取留在最小的 Composable：

```kotlin
@Composable
fun OrderForm(price: State<Long>) {    // 傳 State 本身，不是 Long
    Column { QtyField(); LivePrice(price) }   // QtyField 沒讀價格，價格變了它不動
}

@Composable
fun LivePrice(price: State<Long>) {
    Text("即時價格：${price.value}")   // 只在這裡讀取：價格變了，只有 LivePrice 重組
}
```

參數若寫成 `price: Long`，呼叫端得先讀 `priceState.value`，價格一變，整個 `OrderForm` 都重組。

**`mutableIntStateOf` 家族。** SwiftUI 的 `@State var price: Int` 直接存整數。Kotlin 的泛型只能裝物件，`mutableStateOf(0L)` 每次寫入都把 `Long` 包成物件，這叫 boxing（自動裝箱，多包一層物件、多耗記憶體）。所以數字用 primitive 版（直接存原始數字）：`mutableIntStateOf`、`mutableLongStateOf`、`mutableFloatStateOf`、`mutableDoubleStateOf`（Compose runtime 1.5 起），讀寫用 `longValue` 這類屬性，例如 `price.longValue = 1_000_123L`。不用的話畫面照樣正常，只是每秒幾十次的寫入多出一堆短命物件，只有 lint（Android Studio 內建的程式碼檢查）的 `AutoboxingStateCreation` 會提醒。`Boolean` 沒有 primitive 版（包起來只有兩個共用物件）；用了 primitive 版卻讀 `.value`，一樣會 boxing。

> [!TIP]
> 面試時可以這樣說：「數字用 primitive 版避免 boxing；頻繁更新的 State 往下傳，在最小的 Composable 才讀。」

### 3. `mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？

**SwiftUI 的 `@State var orders: [Order]` 直接 `append` 就會重畫，因為陣列是值型別，任何修改都等於把整個新值寫回 `@State`。Compose 的 `List` 是參考，原地修改不會通知，所以要用 `mutableStateListOf`，或每次換成新的 list。**

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(…))` | 整個換掉：`orders = orders + order` | 值被換掉時 |
| `mutableStateOf(mutableListOf(…))` | 原地 `add` | 不會（lint 會警告） |
| `mutableStateListOf(…)` | `add`、`removeAt`、`set`、`clear` | 清單內容改變時 |

```kotlin
data class OrderRow(val symbol: String, val filled: Double)   // data class 加 val：像 Swift 的 struct
val orders = remember { mutableStateListOf<OrderRow>() }      // SnapshotStateList：追蹤它的內容
orders.add(OrderRow("BTC_JPY", 0.0))                           // 原地 add 會通知讀過清單的 Composable
orders[0] = orders[0].copy(filled = 0.5)                       // 更新項目：換掉那一格
```

對照表用 `mutableStateMapOf`（`SnapshotStateMap`），規則相同。**最容易踩的是項目。** SwiftUI 裡 struct 項目的 `orders[1].filled = 0.5` 會重畫，因為修改經過陣列的 setter；項目是 class 就不會，Compose 的項目就屬於這一種。`SnapshotStateList` 只認得清單本身的改變，不看項目裡面，所以項目要用 `data class` 加 `val`，用 `copy` 換掉那一格，就像 struct 的值語意（演練 1）。另外，一邊走訪一邊修改會丟 `ConcurrentModificationException`，要用 `removeAll { }`；它預設存不進 `rememberSaveable`（第 5 題）。

> [!TIP]
> 面試時可以這樣說：「List 不是值型別，所以用 mutableStateListOf，項目用 data class 並 copy 換掉。」

### 4. `SnapshotMutationPolicy`（SwiftUI 沒有）什麼時候要換？

**Compose 在寫入 State 的當下先比較新舊值，「一樣」就把這次寫入當作沒發生，不重組；預設用 `equals` 比，想改變比法就在 `mutableStateOf` 的第二個參數傳一個 policy。** SwiftUI 沒有對應的設定，`@State` 沒有可調的比較規則（要不要重算 `body`，由框架比對 view 的屬性決定），所以這層是 Compose 獨有的。

| policy | 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b` | 字串、數字、data class，絕大多數情況 |
| `referentialEqualityPolicy()` | `a === b`（同一個物件才算） | `equals` 很貴，或不適合決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

```kotlin
val book = mutableStateOf(orderBook, neverEqualPolicy())   // policy 是第二個參數
val tickPolicy = object : SnapshotMutationPolicy<Tick> {    // 自訂：實作 equivalent
    override fun equivalent(a: Tick, b: Tick) = a.symbol == b.symbol && a.price == b.price   // 不比接收時間
}
```

各自對應一種症狀。`Order` 的 `equals` 只比 `id`，訂單 A1 從 New 變 Filled，新舊「相等」，寫入被丟掉，畫面停在 New，連 State 裡存的都是舊物件，要用 `referentialEqualityPolicy()`（演練 2）。訂單簿引擎就地更新、整個 App 共用一個物件，更新後再寫一次同一個物件，預設 policy 當作沒變，要用 `neverEqualPolicy()`（演練 3），代價是連內容沒變的寫入也重組。行情每筆都帶接收時間，預設 policy 下每筆都重組，所以自訂 policy 不比接收時間。更好的做法常是讓 UI model 是 data class，`equals` 會比所有欄位。

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會被丟掉。」

### 5. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**`remember` 對應 `@State`，只活過重組；`rememberSaveable` 大致對應 `@SceneStorage`，還撐得過旋轉螢幕和行程被系統殺掉，只放小量資料；業務資料放 ViewModel。** `rememberUpdatedState` 是另一件事：讓長時間運作的 effect 讀到最新的值。

Android 預設旋轉螢幕時會銷毀並重建 Activity（Android 的畫面容器），SwiftUI 的 view 不會，所以 `remember` 的值會消失；ViewModel（放畫面資料的物件，大致像 `@StateObject` 持有的 model）則活著。**行程被殺**（process death）是 App 在背景時被系統整個回收，在 Android 比較常遇到。

| 事件 | `remember` | `rememberSaveable` | ViewModel |
|---|---|---|---|
| 旋轉螢幕 | 消失 | 還在 | 還在 |
| 行程被殺後重開 | 消失 | 還在 | 消失 |

**`rememberSaveable`。** SwiftUI 你會寫 `@SceneStorage("qty") private var qty = ""`。Compose 寫法和 `remember` 一樣，值還會存進 Bundle（Android 暫存畫面狀態的 key-value 容器）：

```kotlin
var qty by rememberSaveable { mutableStateOf("") }    // 旋轉、行程被殺後都還原
```

用 `remember` 的話，輸入 `0.25` 後旋轉，輸入框會清空（Kotlin06 演練 5）。`remember(key)` 的 key 變了就丟掉重算；價格格式化器沒有以 tick size 當 key，切換交易對後格式還停在上一個（Kotlin06 演練 6）。

**`Saver`。** Bundle 只收基本型別、`String`，以及實作了 `Parcelable` 或 `Serializable`（Android 與 Java 的序列化介面）的型別。下單草稿 `OrderDraft(symbol, qty)` 這種一般 data class，還有 `SnapshotStateList`、lambda 都存不了，組合畫面的當下就丟 `IllegalArgumentException`（訊息含「cannot be saved using the current SaveableStateRegistry」），手機上是一打開就閃退（演練 4）。`@SceneStorage` 放自訂型別，你會讓它遵守 `RawRepresentable` 或自己編成 `String`／`Data`，Compose 把這個「拆開與組回」獨立成 `Saver`：

```kotlin
val draftSaver = listSaver<OrderDraft, Any>(
    save = { listOf(it.symbol, it.qty) },                         // 拆成存得了的 List，用位置對應
    restore = { OrderDraft(it[0] as String, it[1] as Double) },   // 位置要和 save 對上
)
var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(OrderDraft("BTC_JPY", 0.1)) }   // 一般物件的參數叫 saver
```

欄位少而固定用 `listSaver`；欄位多、之後會增加用 `mapSaver`（用 key 對應，但漏存的欄位不會報錯）。更隱蔽的坑是後來為 `OrderDraft` 加了欄位，卻忘了改 Saver：`restore` 用預設值補，編譯器不會提醒，旋轉後那個欄位靜悄悄歸零（演練 5），所以要幫 Saver 寫「存起來再還原，每個欄位都一樣」的單元測試。ViewModel 要撐過行程被殺，用 `SavedStateHandle`（ViewModel 裡會跟著還原的 key-value 存放處）。

**`rememberUpdatedState`。** `LaunchedEffect(key)` 在 Composable 裡啟動 coroutine（Kotlin 的輕量非同步任務，約等於 Swift 的 `Task`），key 變了就取消重啟，大致對應 `.task(id:)`。送出訂單後有 10 秒逾時倒數，期間外面可能換了新的 `onTimeout`，倒數卻不能因此重來。SwiftUI 的 `.task` 啟動時捕捉的是當時那份 view 值，之後傳進的新 closure 看不到，常見做法是把最新值放進 `@State` 或 model 再讀；`rememberUpdatedState` 大致就是這件事：把最新值包成 State 給長壽命的 lambda 讀。

```kotlin
val currentOnTimeout by rememberUpdatedState(onTimeout)   // 每次重組都更新成最新的 callback
LaunchedEffect(Unit) {                                     // key 是 Unit：只啟動一次，重組不重啟
    delay(10_000)
    currentOnTimeout()                                     // 時間到才讀，拿到最新的
}
```

直接呼叫 `onTimeout`，抓著的是第一次的舊版；放進 key，每次 callback 換了 effect 就取消重啟，10 秒重新計（Kotlin07 演練 5）。

> [!TIP]
> 面試時可以這樣說：「rememberSaveable 像 @SceneStorage，自訂 class 要給 Saver，新增欄位要同步改。」

### 6. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**`derivedStateOf` 是 State 變成另一個 State，用在「輸入常變、結果少變」；`snapshotFlow` 是 State 變成 Flow，大致對應 `.onChange(of:)`；`produceState` 是 suspend 函式或 callback 變成 State，大致對應 `.task { state = await … }`。**

**`derivedStateOf`。** SwiftUI 沒有直接對應：你會寫計算屬性 `var isOver: Bool { price > alertLine }`，每次 `body` 重算就算一次。Compose 直接寫 `val over = price.value > alertLine`，價格每變一次，Composable 都重組，儘管 `over` 沒換邊。`derivedStateOf` 要你明講，才會只在結果換邊時通知：

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    val over by remember(alertLine) {                // alertLine 是一般參數，要當 remember 的 key
        derivedStateOf { price.value > alertLine }   // price 是 State：變了就重算，但只有 true／false 換邊才通知
    }
    if (over) Text("價格超過警示線", color = Color.Red)
}
```

`remember(key)` 追蹤你放進 key 的一般參數，不會通知；`derivedStateOf` 追蹤 State 的讀取，看不到一般參數。口訣：**一般參數放 `remember` 的括號（key），State 放進 `derivedStateOf` 的大括號裡讀。** 常見誤用：漏了 `remember`，每次重組都建立新的 derived state；結果跟著輸入一起變（`derivedStateOf { price * qty }`），沒省到通知；依賴一般參數卻沒當 key，一直用第一次的 `alertLine`（演練 6）。

**`snapshotFlow`。** SwiftUI 你會寫 `.onChange(of: symbol, initial: true)`（`initial` 參數 iOS 17 起有）。Compose 在大括號裡讀 State，回傳 Flow（Kotlin 的非同步資料串流，像 `AsyncSequence`，第 7 題細講）：

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { symbol }.collect { analytics.log("symbol_changed", it) }   // 大括號裡讀 State，symbol 變了就收到新值
}
```

開始收集時先送出目前的值（像 `initial: true`），連續相同的值會去重（初始值 0，依序寫入 1、1、2、2，只會收到 0、1、2）。先 `val s = symbol` 再 `snapshotFlow { s }` 的話，lambda 裡沒讀 State，永遠只有第一個值。

**`produceState`。** SwiftUI 你會在 `.task` 裡 `await` 之後寫進 `@State`。`produceState` 把這件事包成回傳 State 的 Composable，來源可以是 suspend 函式（Kotlin 的 `async` 函式）或 callback：

```kotlin
val last by produceState(initialValue = 0L, socket) {   // 結果回來前顯示 initialValue；socket 換了就重跑
    val l = PriceSocket.Listener { value = it }          // callback 風格：收到價格就寫進 value
    socket.addListener(l)
    awaitDispose { socket.removeListener(l) }            // 離開畫面時解除，否則一直掛著（Kotlin07 演練 6）
}
```

來源是 Flow 的話，用第 7 題的 `collectAsStateWithLifecycle`。

**Snapshot 是什麼？** 它是 Compose 管理 State 讀寫的機制，像一本記錄本。Composable、`derivedStateOf`、`snapshotFlow` 執行時，Compose 記下它們讀了哪些 State，State 被寫入才通知它們。寫入要「套用並通知」才被別人看到：有 Compose 畫面在跑時它會自動做，單元測試沒人做，`snapshotFlow` 就收不到後續變化，要自己呼叫 `Snapshot.sendApplyNotifications()`（演練 8）。

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤 State 的讀取，輸入頻繁、輸出少變時用，要包 remember。」

### 7. 價格 Flow 退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 不管 lifecycle，畫面只要還在 composition（Compose 目前建好的畫面樹）裡就一直收。** SwiftUI 裡你直接讀 `@Observable` ViewModel 的屬性，或用 `.onReceive(publisher)`；`.task` 只在 view 消失時取消，退到背景 task 照跑，要自己看 `scenePhase`。Compose 要明講「把 Flow 轉成 State」，`collectAsStateWithLifecycle` 把 lifecycle 的判斷內建了。

**Flow** 像 `AsyncSequence` 或 Combine 的 publisher；**cold Flow** 是有人收才開始跑、每個收集者各跑一份（例如每次收集就重連 WebSocket）。**`StateFlow`** 永遠保存最新值，新的收集者馬上拿到，大致對應 `CurrentValueSubject`；**`SharedFlow`** 沒有目前值，常廣播一次性事件，大致對應 `PassthroughSubject`。**lifecycle** 是 Android 畫面從建立到銷毀的各階段，**`STARTED`** 是「畫面看得到」，退到背景就回到 `STARTED` 以下。

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 何時收集 | 進入 composition 就收，離開才停 | lifecycle 至少到 `STARTED`（`minActiveState` 預設值）才收；要加依賴 `lifecycle-runtime-compose` |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集，回前景重新收集 |

```kotlin
val uiState by vm.uiState.collectAsStateWithLifecycle()                          // StateFlow 有目前值，不用給 initial
val lastPrice by vm.priceFlow.collectAsStateWithLifecycle(initialValue = null)   // 一般 Flow：必須自己給 initial
Text(lastPrice?.let { "$it JPY" } ?: "--")                                       // null 代表還沒收到，顯示 -- 而不是 0
```

本機實測（演練 7）：App 退到背景 0.5 秒，`collectAsState()` 的畫面又更新了 9 次，`collectAsStateWithLifecycle()` 是 0 次，手機上就是耗電、耗流量的差別。

一般 cold Flow 每次回前景都會重新收集、上游從頭再跑，所以通常先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，並設 `WhileSubscribed(5000)`：沒有人收集後再等 5 秒才停掉上游，撐過旋轉螢幕那一瞬間（Kotlin05 第 5 題）。`SharedFlow` 的事件通常不轉成 State（State 只留最新值，連發會漏），改用 `LaunchedEffect` 收。直接在 Composable 讀 `stateFlow.value` 不會觀察變化，lint 會警告。

**`LiveData.observeAsState()`** 是 `ObservableObject` 搭配 `@Published` 那個時代的東西（大致對應），舊專案才會碰到。ViewModel 還是 `LiveData`（Android 較早的可觀察資料類別）時，加依賴 `runtime-livedata`，寫 `vm.priceLiveData.observeAsState()`；它本身跟著 lifecycle，不需要 `WithLifecycle` 版本。

> [!TIP]
> 面試時可以這樣說：「Android 畫面用 collectAsStateWithLifecycle，退到背景就取消。」

### 8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**畫面的業務狀態預設用 `StateFlow`（對外 `StateFlow`，內部 `MutableStateFlow`），畫面用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。** 大致對應 SwiftUI 的 `@Observable` 與 `@Published`：`mutableStateOf` 像 `@Observable` 的屬性，畫面裡直接讀最簡單，但變化主要是 Compose 看得到；`StateFlow` 像 `@Published`，是一條串流，非 UI 的程式也能訂閱、用運算子組合。

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者（背景工作、單元測試） | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，沒有 Compose 在跑就收不到（演練 8） |
| Flow 運算子 | `combine`、`debounce` 直接用 | 要先用 `snapshotFlow` 轉一次 |
| 原子更新 | `_state.update { it.copy(…) }` | 沒有同等的 API |

原子更新是多個 coroutine 同時修改時不會互相蓋掉。單元測試可用 Turbine（測試 Flow 的函式庫）逐筆驗證 `StateFlow`。`mutableStateOf` 在畫面裡直接讀，不用 collect，但沒有收集動作，也就沒有 lifecycle 可以停。唯一的例外是**輸入框的即時文字**：繞一圈非同步的 Flow 再回畫面，會比輸入法晚一步，輸入中文、日文這類要組字的輸入法可能游標跳動，所以放在畫面自己的 State，按下送出才交給 ViewModel。

**`@Binding` 的對應是 state hoisting（狀態提升）。** Compose 沒有 `Binding` 型別，子 Composable 只收「值」和「事件的 callback」，值往下、lambda 往上：`fun QtyField(qty: String, onQtyChange: (String) -> Unit)` 對應 `@Binding var qty: String`。傳整個 `MutableState` 下去可行但不建議（見 Kotlin09 第 1 題）。

狀態放哪一層，看「誰在乎、要活多久」：只有一個 Composable 在乎的（展開或收合）用 `remember`；兩個以上的兄弟共用，提到共同的上層；畫面邏輯（捲動、表單驗證）收進 state holder（普通 class）；業務資料（行情、訂單）放 ViewModel 的 `StateFlow`。state holder 旋轉就重建，它的 `rememberCoroutineScope` 離開畫面就取消，所以送出訂單這種必須做完的工作要放 ViewModel 的 `viewModelScope`（Kotlin07 第 5 題）。

> [!TIP]
> 面試時可以這樣說：「ViewModel 的畫面狀態用 StateFlow，非 Compose 的程式也能收；子畫面改值用 state hoisting。」

### 9. 情境對應 SwiftUI 與 Compose 該用哪個 API？

先問三件事：資料從哪來、要活多久、誰要觀察它，再對照下表。

| 情境 | SwiftUI | Compose（第幾題） |
|---|---|---|
| 畫面自己的值（計數、開關） | `@State` | `remember { mutableStateOf(…) }`（1） |
| 子畫面要改父畫面的值 | `@Binding` | 值往下、`onValueChange` 往上，即 state hoisting（8） |
| 輸入到一半，要撐過旋轉或行程被殺 | `@SceneStorage` | `rememberSaveable`，自訂型別給 `Saver`（5） |
| 清單一筆一筆增減 | `@State var items: [T]` 加 `append` | `mutableStateListOf`，項目用 data class（3） |
| 價格超過警示線才顯示紅字 | 計算屬性 | `remember(alertLine) { derivedStateOf { … } }`（5、6） |
| State 變了要做一件事 | `.onChange(of:)` | `LaunchedEffect` 裡 `snapshotFlow`（6） |
| suspend 或 callback 轉成 State | `.task { state = await … }` | `produceState`（6） |
| Flow 顯示，退到背景不要收 | `@Observable` 屬性、`.onReceive`（背景要看 `scenePhase`） | `collectAsStateWithLifecycle`，cold Flow 先 `stateIn`（7） |
| ViewModel 暴露畫面狀態 | `@Observable` 或 `@Published` | `StateFlow` 加 `collectAsStateWithLifecycle`（8） |
| 送出訂單這種必須做完的工作 | 不綁 view 的 model 裡的 `Task` | ViewModel 的 `viewModelScope`（8） |
| 整棵樹共用的值（主題、設定） | `@Environment` | `CompositionLocal`，用 `CompositionLocalProvider` 提供 |

> [!TIP]
> 面試時可以這樣說：「我先問資料從哪來、要活多久、誰要觀察；@State 是 remember，@SceneStorage 是 rememberSaveable。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin11ComposeState**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin11-compose-state/`，解答在 `labs/android/solution/kotlin/kotlin11-compose-state/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不用看畫面**：這個單元的演練都用 LabKit 的 `withHeadlessCompose`，在沒有畫面的情況下真的組合與重組一個 Composable，再把「畫面顯示什麼、重組幾次」寫進 log，所以按「執行」就有自動檢查，也可以在電腦上跑 `./gradlew :practice-kotlin:testDebugUnitTest` 看結果。畫面上的文字改用 `HeadlessText`（`Text` 的替身）。演練 7 用到 Android 的主執行緒，要在手機或模擬器上按「執行」；演練 8 只能在電腦上跑單元測試（原因寫在演練裡）。

### 演練 1：成交了一部分，委託那一列卻沒變（第 3 題）

**檔案**：`L01_ListItemMutate.kt`

**情境**：委託列表放在 `mutableStateListOf` 裡，畫面顯示每一筆委託已成交多少。收到「ETH_JPY 成交了 0.5」的回報後，要更新那一列。

**怎麼寫**

1. **Bug 版**：`OrderRow` 的 `filled` 是 `var`，收到回報就直接 `orders[1].filled = 0.5`。
2. **修正版**：`OrderRow` 改成 `data class`，欄位都是 `val`；收到回報就換掉那一格：`orders[1] = orders[1].copy(filled = 0.5)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「清單裡的資料」是 ETH_JPY 已成交 0.5，「畫面顯示」卻還是 0.0，「畫面共更新了 1 次」 |
| 修正版 | 「畫面顯示」跟清單一樣是 ETH_JPY 已成交 0.5，「畫面共更新了 2 次」 |

**為什麼**：`mutableStateListOf` 只會在清單的內容（新增、刪除、換掉某一格）改變時通知 Compose，看不到物件裡面的欄位。原地修改 `filled`，清單還是同樣的兩個物件，Compose 完全不知道。項目用不可變的 `data class`，更新時換掉那一格，清單的內容變了，Compose 就會重組。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L01_ListItemMutate.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L01_ListItemMutate.kt#fixed
```

### 演練 2：訂單狀態變成 Filled，畫面卻停在 New（第 4 題）

**檔案**：`L02_EqualsById.kt`

**情境**：領域模型 `Order` 的 `equals` 只比 `id`（Room 的 entity 和領域物件常見這樣寫）。同一筆訂單 A1 的狀態從 New 變成 Filled，要把新的 `Order` 寫進 State。

**怎麼寫**

1. **Bug 版**：`mutableStateOf(L02Order("A1", "New"))`，用預設的比較方式。
2. **修正版**：`mutableStateOf(L02Order("A1", "New"), referentialEqualityPolicy())`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「State 裡的訂單：A1：New」，「畫面顯示」也還是 A1：New，「畫面共更新了 1 次」：寫進去的新值被當成沒變而丟掉，連 State 裡存的都是舊的 |
| 修正版 | 「State 裡的訂單：A1：Filled」，「畫面顯示：訂單 A1：Filled」，「畫面共更新了 2 次」 |

**為什麼**：預設的 `structuralEqualityPolicy` 用 `equals` 比較，新舊兩個 `Order` 的 `id` 一樣，`equals` 說相等，這次寫入就被丟掉。`referentialEqualityPolicy` 用 `===`，換成另一個物件就算變了。不換 policy 的做法是讓 UI 用的 model 是 `data class`，`equals` 會比所有欄位。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L02_EqualsById.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L02_EqualsById.kt#fixed
```

### 演練 3：訂單簿更新了，最佳買價卻不動（第 4 題）

**檔案**：`L03_NeverEqual.kt`

**情境**：訂單簿引擎 `OrderBook` 為了效率，內部用 `TreeMap` 就地更新，整個 App 共用同一個物件。收到深度更新（價位 101 有 1.5 BTC 的買單）後，畫面要顯示最新的最佳買價（原本是 100）。

**怎麼寫**

1. **Bug 版**：`mutableStateOf(book)`；更新引擎之後再寫一次 `state.value = book`，以為這樣會通知畫面。
2. **修正版**：`mutableStateOf(book, neverEqualPolicy())`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「訂單簿的最佳買價：101」，「畫面顯示」卻還是最佳買價 100，「畫面共更新了 1 次」 |
| 修正版 | 「畫面顯示：最佳買價 101」，「畫面共更新了 2 次」 |

**為什麼**：寫進去的是同一個物件，`equals` 當然相等，預設的比較方式把這次寫入當成沒變。`neverEqualPolicy` 認為任何一次寫入都算變了，同一個物件寫進去也會通知。代價是每次寫入都重組，只適合「物件就地更新、更新之後明確通知」的情境。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L03_NeverEqual.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L03_NeverEqual.kt#fixed
```

### 演練 4：下單草稿用 rememberSaveable，一打開就當掉（第 5 題）

**檔案**：`L04_NoSaver.kt`（另有共用的 `SimulatedRotation.kt`，不用改）

**情境**：下單畫面把草稿（交易對加數量）放在 `rememberSaveable`，希望旋轉螢幕後還在。草稿是自訂的 `L04Draft`，沒有實作 `Serializable` 或 `Parcelable`。演練用 `SimulatedRotation.kt` 在電腦上模擬旋轉螢幕（存起來、畫面重建、還原），也一併模擬 Android 預設的規則（只收得下 Bundle 能放的型別），所以不用開手機就能重現；手機上的預設規則相同。

**怎麼寫**

1. **Bug 版**：`rememberSaveable { mutableStateOf(L04Draft("BTC_JPY", 0.1)) }`，不給 Saver。
2. **修正版**：用 `listSaver` 寫 `draftSaver`（`save` 回傳 `listOf(it.symbol, it.qty)`，`restore` 用 `it[0] as String`、`it[1] as Double` 組回來），再用 `rememberSaveable(stateSaver = draftSaver) { mutableStateOf(L04Draft("BTC_JPY", 0.1)) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「第一次組合就當掉了：IllegalArgumentException，訊息含「cannot be saved using the current SaveableStateRegistry」=true」 |
| 修正版 | 「旋轉前，畫面顯示：ETH_JPY 0.25」，「旋轉後，畫面顯示：ETH_JPY 0.25」 |

**為什麼**：`rememberSaveable` 的狀態最後要放進 Bundle，預設只收得下基本型別、`Serializable`、`Parcelable` 這幾類。自訂的 class 不在其中，組合畫面的當下就丟 `IllegalArgumentException`（手機上就是閃退）。`Saver` 告訴 Compose 怎麼把物件拆成存得了的 `List`，以及怎麼組回來。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L04_NoSaver.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L04_NoSaver.kt#fixed
```

### 演練 5：旋轉螢幕後，下單數量歸零了（第 5 題）

**檔案**：`L05_HalfSaver.kt`（共用 `SimulatedRotation.kt`）

**情境**：下單草稿 `L05Draft` 本來只有交易對和買賣方向，Saver 是那時候寫的。後來為了數量加了新欄位 `qty`（有預設值 0.0），Saver 沒跟著改。使用者把草稿填成 ETH_JPY、賣出、0.25，然後旋轉螢幕。

**怎麼寫**

1. **Bug 版**：`mapSaver` 只存 `symbol` 和 `side`，`restore` 時 `L05Draft(it["symbol"] as String, it["side"] as String)`，`qty` 用預設值。
2. **修正版**：`save` 多存 `"qty" to it.qty`，`restore` 多傳 `it["qty"] as Double`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「旋轉前，畫面顯示：ETH_JPY SELL 0.25」，「旋轉後，畫面顯示：ETH_JPY SELL 0.0」：沒有錯誤、沒有當掉，數量靜悄悄地歸零 |
| 修正版 | 「旋轉後，畫面顯示：ETH_JPY SELL 0.25」，和旋轉前一樣 |

**為什麼**：`restore` 用預設值補上沒存的欄位，編譯器和執行時都不會報錯，錯誤只表現在「旋轉之後某個欄位不對」，很難發現。欄位多了，Saver 的 `save` 與 `restore` 都要跟著改；給 Saver 寫一個「存起來再還原，確認每個欄位都一樣」的單元測試，以後有人加欄位，測試就會失敗。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L05_HalfSaver.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L05_HalfSaver.kt#fixed
```

### 演練 6：警示線調高了，畫面還是顯示超過（第 6 題）

**檔案**：`L06_DerivedParam.kt`

**情境**：價格提醒元件 `PriceAlert` 的價格是 State，警示線 `alertLine` 是使用者設定、從外面傳進來的一般參數。畫面只關心「價格有沒有超過警示線」，所以用 `derivedStateOf`。價格 120、警示線 100：超過。使用者把警示線調高到 200，價格不變，應該變成「未超過」。

**怎麼寫**

1. **Bug 版**：`val over by remember { derivedStateOf { price.value > alertLine } }`，`remember` 不給 key。
2. **修正版**：`remember(alertLine) { derivedStateOf { price.value > alertLine } }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第一行「超過警示線」，第二行（警示線調高到 200）還是「超過警示線」 |
| 修正版 | 第一行「超過警示線」，第二行（警示線調高到 200）變成「未超過警示線」 |

**為什麼**：`derivedStateOf` 追蹤的是 State 的讀取，`alertLine` 是一般參數，變了它不會知道；沒有 key 的 `remember` 又只建立一次，計算式一直抓著第一次的 `alertLine`（100）。一般參數要當成 `remember` 的 key，參數變了就重建 `derivedStateOf`。如果警示線也是 State（`alertLine: State<Long>`，計算式裡讀 `alertLine.value`），就不需要 key。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L06_DerivedParam.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L06_DerivedParam.kt#fixed
```

### 演練 7：App 退到背景，畫面還一直在更新（第 7 題）

**檔案**：`L07_CollectLifecycle.kt`

**情境**：行情畫面用 `StateFlow` 收價格，伺服器每 50 ms 推一筆，不管畫面在不在前景。演練用自己的 lifecycle（`LifecycleRegistry`，寫法同 Kotlin05 演練 9）模擬畫面：前景 0.3 秒，退到背景 0.5 秒，回到前景 0.2 秒。`collectAsStateWithLifecycle` 內部要用 Android 的主執行緒，所以要在手機或模擬器上按「執行」。

**怎麼寫**

1. **Bug 版**：`val price by prices.collectAsState()`。
2. **修正版**：`val price by prices.collectAsStateWithLifecycle()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「退到背景 0.5 秒，畫面又更新了」約 10 次（實測 9 次），「還有 1 個畫面在收行情」 |
| 修正版 | 「退到背景 0.5 秒，畫面又更新了 0 次」，「還有 0 個畫面在收行情」；回到前景後畫面顯示最新的價格 |

**為什麼**：`collectAsState()` 不管 lifecycle，只要畫面還在 composition 裡就一直收，退到背景後每一筆價格仍然讓畫面重組。`collectAsStateWithLifecycle()` 預設只在 `STARTED` 以上收集，退到背景就取消，回到前景再重新收集，`StateFlow` 馬上給最新的值。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L07_CollectLifecycle.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L07_CollectLifecycle.kt#fixed
```

### 演練 8：價格警示在單元測試裡只收到第一筆（第 8 題）

**檔案**：`L08_ViewModelState.kt`，測試在 `test/Kotlin11StateTest.kt`（已經寫好，不用改）

**情境**：價格警示（可以想成 WorkManager 的 Worker，或測試 ViewModel 的單元測試）要觀察 ViewModel 的價格，它不是 Compose，只會用 Flow。ViewModel 依序收到 5 筆新價格（101 到 105），每筆間隔 40 ms。這題沒有「執行」按鈕：App 本身是 Compose 畫面，畫面在跑時 Compose 會替你通知 snapshot 系統「有寫入了」，看起來就收得到；單元測試和沒有 Compose 畫面的環境沒有人做這件事，問題才看得到。

**怎麼寫**

1. **Bug 版**：ViewModel 用 `var price by mutableLongStateOf(100L)`，`prices()` 回傳 `snapshotFlow { price }`。
2. **修正版**：ViewModel 用 `MutableStateFlow(100L)`，對外給 `asStateFlow()`，`onPrice` 把新價格寫進去。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「ViewModel 裡的價格：105」，但「非 Compose 的價格警示收到：[100]」：只收到開始時的第一筆 |
| 修正版 | 「非 Compose 的價格警示收到：[100, 101, 102, 103, 104, 105]」 |

**為什麼**：`mutableStateOf` 沒有「收集」的 API，只能用 `snapshotFlow` 包成 Flow。`snapshotFlow` 要等 Compose 通知 snapshot 系統「有寫入了」才會重新計算，單元測試裡沒有 Compose 在跑，沒有人通知，就只收到第一筆。在測試裡補一行 `Snapshot.sendApplyNotifications()`，最後一筆才收得到。`StateFlow` 不依賴 Compose，任何 coroutine 都能 `collect`，所以 ViewModel 的畫面狀態預設用它。

**Android 解答**

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L08_ViewModelState.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin11-compose-state/L08_ViewModelState.kt#fixed
```

第 5 題的 `remember` 的 key 與 `rememberSaveable` 的基本用法，已在 Kotlin06 演練 5、6 做過，`rememberUpdatedState` 在 Kotlin07 演練 5 做過，這裡只補 `Saver`（演練 4、5）。第 2 題「把讀取往下推」在 Kotlin06 演練 1 做過；primitive 版的 boxing 沒有獨立演練：它不會讓畫面出錯，只是多了記憶體配置，Android Studio 的 lint（`AutoboxingStateCreation`）會直接提醒，boxing 本身的觀念見 Kotlin01 演練 4。第 6 題 `produceState` 的 key 規則和 Kotlin07 演練 2 相同，`snapshotFlow` 的演練見 Kotlin07 演練 10。第 1 題是觀念題，第 9 題是對照表，都沒有演練。

## 常見陷阱

- **以為普通的 `var` 改了，畫面就會跟著動。** Compose 只盯著 State。普通的 `var` 改了沒有人通知，畫面不會重組。要讓畫面跟著變的值，用 `mutableStateOf` 建立，並放進 `remember`。
- **State 沒有包 `remember`。** 每次重組都建立一個新的 State，值回到初始值，畫面看起來像「怎麼點都沒反應」。Compose 的 lint `UnrememberedMutableState` 會提醒。
- **在上層讀取每秒更新很多次的 State，再把值傳給子 Composable。** 讀取發生在上層，每次價格變，上層整個函式都重組。把 `State`（或 lambda）往下傳，讀取留在最小的那個 Composable 裡。
- **`mutableStateOf(mutableListOf())` 或 `mutableStateOf(mutableMapOf())` 之後原地修改。** State 的值還是同一個物件，Compose 不知道內容變了。改用 `mutableStateListOf`、`mutableStateMapOf`，或每次換成新的集合。
- **`mutableStateListOf` 的項目是 `var` 欄位的 class，原地改欄位。** 清單只認得自己內容的改變，不看項目裡面。項目用 `data class` 加 `val`，更新時換掉那一格。
- **用 `mutableStateOf(0)` 存每秒更新很多次的整數。** 每次寫入都要 boxing（多包一層物件、多耗記憶體）。改用 `mutableIntStateOf`、`mutableLongStateOf`，讀寫用 `intValue`、`longValue`，不要再去讀 `.value`。
- **以為 `neverEqualPolicy` 能讓「相同的訊息再顯示一次」。** 重組會發生，但 `LaunchedEffect` 的 key 還是用 `equals`，不會重新啟動。一次性事件要包成帶 id 的物件，或照 Kotlin05 第 3 題的做法。
- **`rememberSaveable` 放自訂的 class 卻沒給 `Saver`，或新增欄位卻忘了改 `Saver`。** 前者畫面一打開就閃退，後者旋轉後欄位默默歸零。給 `Saver` 寫存了再還原的單元測試。
- **`derivedStateOf` 漏了 `remember`，或計算式依賴一般參數卻沒當 `remember` 的 key。** 前者快取等於沒有，後者一直用第一次的參數。
- **在 Android 畫面用 `collectAsState()` 收行情。** 退到背景後仍然持續收集、持續重組，耗電也耗流量。改用 `collectAsStateWithLifecycle()`。
- **在 Composable 裡直接讀 `stateFlow.value`。** 不會觀察變化，值變了畫面不會更新。用 `collectAsStateWithLifecycle()`，Compose 的 lint（`StateFlowValueCalledInComposition`）也會警告。
- **把 cold Flow 直接交給 `collectAsStateWithLifecycle(initialValue)`。** 每次回到前景，上游都從頭再跑一次（例如重開 WebSocket）。先在 ViewModel 用 `stateIn` 轉成 `StateFlow`。
- **ViewModel 用 `mutableStateOf` 暴露狀態，卻要在 Worker 或單元測試裡觀察它。** `snapshotFlow` 在沒有 Compose 的環境收不到後續變化。ViewModel 對外給 `StateFlow`。

## 面試官想聽到的

1. 能用自己的話講清楚 Compose 的基本模型：Composable 是描述畫面的函式、會被重複執行；State 改變才會重組，普通的 `var` 不會；`remember` 讓值活過重組。
2. 能把 `mutableStateOf` 的家族講完整：primitive 版為什麼存在（boxing）、`mutableStateListOf` 與 `mutableStateOf(mutableListOf())` 的差別、`SnapshotStateList` 只看清單內容不看項目裡面；也知道 `mutableStateOf` 預設用 `equals` 判斷，並能說出 `referentialEqualityPolicy`、`neverEqualPolicy` 各自適合的情境，以及它們不能取代「一次性事件」的設計。
3. 能分清楚 `remember`、`rememberSaveable`、ViewModel 的存活範圍，知道 `rememberSaveable` 存得了與存不了的型別，會寫 `listSaver`、`mapSaver`，並提醒欄位增加時 Saver 要同步修改。
4. 能說出 `derivedStateOf` 和 `remember(key)` 的差別（追蹤 State 的讀取，還是追蹤我放進去的參數）、`snapshotFlow` 與 `produceState` 的方向，並知道 snapshot 的讀取追蹤是它們共同的基礎。
5. 能解釋 `collectAsState` 與 `collectAsStateWithLifecycle` 在背景時的差別、`StateFlow` 與一般 `Flow` 該怎麼收、`initial` 怎麼給，並能說出 ViewModel 用 `StateFlow` 而不是 `mutableStateOf` 的理由。

> [!FIELD]
> 在一個即時行情 App 裡，畫面全面採用 Jetpack Compose，以 Coroutines 與 Flow 處理資料流，即時行情用 Server-Sent Events（伺服器透過一條 HTTP 連線，持續單向推資料給 App）；專案裡也有使用 `derivedStateOf` 和 `snapshotFlow`（見 Kotlin07）。另一個舊專案則是逐頁重構，陸續導入 Jetpack Compose。
> 換到交易所的行情與訂單畫面，我會這樣選：ViewModel 對外給 `StateFlow`，畫面用 `collectAsStateWithLifecycle` 只在前景收；輸入中的欄位用 `rememberSaveable`（自訂型別給 `Saver`）；價格超過門檻用 `derivedStateOf`；必須做完的下單放 ViewModel。

## iOS / Android 對照

整個單元已經是 SwiftUI 對照，完整的「情境、SwiftUI、Compose」三欄速查表在第 9 題。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 狀態 | state | 状態（ステート） |
| 重組 | recomposition | 再コンポジション |
| 快照 | snapshot | スナップショット |
| 自動裝箱 | boxing（autoboxing） | ボクシング（オートボクシング） |
| 行程被系統殺掉 | process death | プロセスデス（プロセスの強制終了） |
| 生命週期 | lifecycle | ライフサイクル |
| 衍生狀態 | derived state | 派生状態 |
| 狀態提升 | state hoisting | 状態のホイスティング |
