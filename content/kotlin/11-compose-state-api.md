---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽（SwiftUI 對照版）：@State、@Binding、@Observable 在 Compose 裡叫什麼
summary: 給熟 SwiftUI、還沒寫過 Jetpack Compose 的 iOS 工程師。每個 API 用條列固定分四塊：這是什麼、什麼時候用、範例，以及 SwiftUI 對照（先列對應關係與差異，再給 SwiftUI 與 Compose 兩段做同一件事的程式碼；SwiftUI 沒有的概念就說明為什麼沒有）。用同一張下單表單帶出 mutableStateOf、SnapshotMutationPolicy、rememberSaveable、derivedStateOf、collectAsStateWithLifecycle、StateFlow 等 API，最後給一張「情境、SwiftUI、Compose」三欄的速查表。
priority: P0
day: 5
tags: [compose, state, recomposition, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元寫給熟 SwiftUI、還沒寫過 Jetpack Compose（Android 官方的宣告式 UI 框架）的 iOS 工程師。

- 每個 API 固定四塊，全用條列：**這是什麼**、**什麼時候用**、**範例**、**SwiftUI 對照**；坑放在 **常見錯誤**。
- 不是一對一的地方標「大致對應」；SwiftUI 沒有的概念，直接說為什麼沒有。
- 範例是同一張「下單表單」：交易對、數量、價格三個欄位和送出按鈕，下面一條跳動的即時價格。
- 重組見 Kotlin06，effect 見 Kotlin07，Flow 見 Kotlin05。

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

### 1. Composable、State、`remember` 對應 SwiftUI 的什麼？差在哪？

**`@Composable` 函式對應 `View.body`，`remember { mutableStateOf(…) }` 對應 `@State`；差別是 Compose 要自己寫 `remember`，忘了就在每次重組時歸零。**

#### `@Composable` 函式

**這是什麼**

- 描述畫面現在的樣子，不是建立 view 物件。
- 資料變了就重新執行（重組，recomposition），只更新有變的部分。

**什麼時候用**

- 用：所有畫面；拆成小函式，重畫範圍跟著變小。
- 不用：函式裡直接打 API；副作用放進 effect（如 `LaunchedEffect`，見 Kotlin07）。

**範例**

用普通的 `if` 描述畫面。

```kotlin
@Composable
fun OrderSummary(qty: String) {
    if (qty.isEmpty()) {
        Text("請輸入數量", color = Color.Red)
    } else {
        Text("數量：$qty")
    }
}
```

**SwiftUI 對照**

| SwiftUI | Compose | 差異 |
|---|---|---|
| `View` 的 `body` | `@Composable` 函式 | 大致對應 |
| 以 view 為單位重算 | 以函式為單位重組 | 參數沒變、型別 stable（不會被偷偷改，見 Kotlin06）就跳過 |

```swift
struct OrderSummary: View {
    let qty: String

    var body: some View {
        Text(qty.isEmpty ? "請輸入數量" : "數量：\(qty)")
    }
}
```

```kotlin
@Composable
fun OrderSummary(qty: String) {
    Text(if (qty.isEmpty()) "請輸入數量" else "數量：$qty")
}
```

#### State：Compose 盯著看的變數

**這是什麼**

- Compose 會記下「哪個 Composable 讀過它」。
- 被寫入新值，就重組讀過它的 Composable。

**什麼時候用**

- 用：值改了畫面要跟著變，例如已送出筆數。
- 不用：只在事件處理裡暫時用的值。

**範例**

按「送出」，筆數加一（`remember` 下一節講）。

```kotlin
var sent by remember { mutableIntStateOf(0) }

Button(onClick = { sent++ }) { Text("已送出：$sent 筆") }
```

**常見錯誤**

- 用普通的 `var sent = 0`：值變了沒人通知，畫面不動。

**SwiftUI 對照**

- `@State` 大致對應「State 加 `remember`」。
- `body` 改普通屬性，編譯器會擋；Compose 能編譯，只是沒人通知。

```swift
struct SubmitCounter: View {
    @State private var sent = 0

    var body: some View {
        Button("已送出：\(sent) 筆") { sent += 1 }
    }
}
```

```kotlin
var sent by remember { mutableIntStateOf(0) }

Button(onClick = { sent++ }) { Text("已送出：$sent 筆") }
```

#### `remember`

**這是什麼**

- 「重組時不要忘記」：第一次執行並存起來，之後直接拿。
- 存放的依據是這段程式在畫面樹上的位置。

**什麼時候用**

- 用：要跨重組保留的東西，例如 State、建立成本高的物件。
- 不用：旋轉、被系統殺掉後還要保留的值，用 `rememberSaveable`（第 5 題）或 ViewModel（旋轉時不銷毀的畫面資料物件，第 8 題）。

**範例**

格式化器只建一次。

```kotlin
val formatter = remember { PriceFormatter(0.01) }

Text(formatter.format(price))
```

**常見錯誤**

- 計數器漏掉 `remember`：每次重組都建新的 State，值回到 0。三種寫法並排：

| 寫法 | 點一下按鈕之後 | 原因 |
|---|---|---|
| 普通的 `var` | 畫面不動 | Compose 看不到，沒人通知 |
| State，沒有 `remember` | 永遠是 0 | 有重組，但每次都建新的 State |
| `remember` 加 State | 1、2、3… | 正確 |

**SwiftUI 對照**

- 兩邊都靠「在樹上的位置」找回狀態：`if` 分支拿掉再放回、`key()` 變了、`LazyColumn` 項目被回收，值都會重置。
- `@State` 由框架保管，不會忘；Compose 要自己寫 `remember`。

```swift
struct QtyInput: View {
    // 外層 if 拿掉再放回來，qty 就回到空字串
    @State private var qty = ""

    var body: some View {
        TextField("數量", text: $qty)
    }
}
```

```kotlin
@Composable
fun QtyInput() {
    var qty by remember { mutableStateOf("") }

    TextField(value = qty, onValueChange = { qty = it })
}
```

> [!TIP]
> 面試時可以這樣說：「Composable 像 body，State 像 @State，但一定要 remember，否則重組就歸零。」

### 2. `mutableStateOf`：`by` 和 `.value` 差在哪？怎麼只重畫價格那一行？

**`mutableStateOf` 建立 Compose 盯著看的 State，只有讀過它的 Composable 會在它變時重組，所以重畫範圍取決於讀取發生在哪。**

#### `mutableStateOf`

**這是什麼**

- 型別 `MutableState<T>` 可讀可寫，`State<T>` 唯讀。
- 慣例：對外只給唯讀的，擁有者才能寫。

**什麼時候用**

- 用：表單上改了畫面就變的值，例如買賣。
- 不用：頻繁寫入的數字（`mutableIntStateOf`）、整份清單（第 3 題）、要撐過旋轉的輸入（第 5 題）、業務資料（第 8 題）。

**範例**

點按鈕切換買賣方向。

```kotlin
var side by remember { mutableStateOf("BUY") }

Button(onClick = { side = if (side == "BUY") "SELL" else "BUY" }) {
    // 在這裡讀 side：Compose 記下這裡依賴它
    Text("目前：$side")
}
```

**SwiftUI 對照**

- `@State`（第 1 題）對應畫面自己用的值。
- `private set` 對應 `private(set)`；`@Observable` 是 iOS 17 起的 Observation 框架。

```swift
@Observable
final class OrderFormModel {
    private(set) var qty = ""

    func onQtyChange(_ input: String) {
        qty = input.filter { $0.isNumber || $0 == "." }
    }
}
```

```kotlin
class OrderFormState {
    var qty by mutableStateOf("")
        private set

    fun onQtyChange(input: String) {
        qty = input.filter { it.isDigit() || it == '.' }
    }
}
```

#### `by` 和 `.value`

**這是什麼**

- 兩種寫法行為一樣：`by`（屬性委派）像用普通變數。
- 不寫 `by` 就拿到 State 本身，用 `.value` 讀寫。

**什麼時候用**

- 用：多數情況用 `by`，程式最短；但它讓 State 像普通變數，別忘了這行在讀 State。
- 不用：要把 State 本身傳給別的函式時；`by` 只能傳值或 lambda。

| | 用 `by` | 不用 `by` |
|---|---|---|
| 宣告 | `var price by remember { mutableLongStateOf(0L) }` | `val price = remember { mutableLongStateOf(0L) }` |
| 讀 | `price` | `price.longValue`（一般 State 是 `.value`） |
| 寫 | `price = 1_000_123L` | `price.longValue = 1_000_123L` |
| 傳 State 本身 | 不能 | 能 |
| import | 要 `getValue`、`setValue` | 不用 |

**範例**

開盤價用 `by`，最新價保留 State 本身。

```kotlin
var open by remember { mutableLongStateOf(0L) }
val last = remember { mutableLongStateOf(0L) }

Text("開盤 $open，最新 ${last.longValue}")
```

**SwiftUI 對照**

- `price` 是值（`wrappedValue`），`$price` 是 `Binding`（`projectedValue`）。
- `by` 像 `price`；不寫 `by` 的 State 像 `$price`，但是儲存本體，不是 get／set 包出的 `Binding`。傳給子元件更常用 state hoisting（第 8 題）。

```swift
@State private var last = 0

// $last 是 Binding，可傳給子 view 讀寫
PriceEditor(price: $last)
```

```kotlin
val last = remember { mutableLongStateOf(0L) }

// State 本身可以傳下去
PriceEditor(price = last)
```

#### 把讀取往下推

**這是什麼**

- 不是 API，是寫法：讀取 State 的那一行在哪，哪裡就重組。
- 把 State 或 lambda 往下傳，讀取留在最小的 Composable。

**什麼時候用**

- 用：即時價格這種每秒更新很多次的值。
- 用：在 `Modifier.offset { }`（元件的修飾）裡讀 State，延後到 Layout 階段（決定位置與大小）才讀，連重組都省了（Kotlin08）。
- 不用：只在按下按鈕才改一次的值。

**範例**

價格 State 往下傳，只有 `LivePrice` 讀它。

```kotlin
@Composable
fun OrderForm(price: State<Long>) {
    QtyField()

    LivePrice(price)
}

@Composable
fun LivePrice(price: State<Long>) {
    // 只在這裡讀：價格變了，只有 LivePrice 重組
    Text("即時價格：${price.value}")
}
```

**SwiftUI 對照**

- SwiftUI 也拆小 subview，傳整個 `@Observable` model，`body` 讀到屬性才被追蹤。
- Compose 傳 State 或 lambda，讀取留在最小的 Composable。

```swift
struct LivePrice: View {
    // Quote 是 @Observable class
    let quote: Quote

    var body: some View {
        Text("即時價格：\(quote.price)")
    }
}
```

```kotlin
@Composable
fun LivePrice(price: () -> Long) {
    // 傳 lambda 也行：讀取 State 發生在 LivePrice
    Text("即時價格：${price()}")
}
```

#### `mutableIntStateOf` 家族

**這是什麼**

- 數字專用 State：Int、Long、Float、Double 版。
- 直接存原始數字，省掉 boxing（把數字包成物件）。

**什麼時候用**

- 用：一直被改寫的數字，如價格（存成 `Long`）、未讀數（Compose runtime 1.5 起）。
- 不用：`Boolean` 沒有 primitive 版（只有 `TRUE`、`FALSE` 兩個共用物件）。

**範例**

價格用 `Long` 版，未讀數用 `Int` 版。

```kotlin
var price by remember { mutableLongStateOf(0L) }
var unread by remember { mutableIntStateOf(0) }

Text("價格：$price，未讀：$unread")
```

**常見錯誤**

- 不用也能跑，只是多一堆短命物件；lint（程式碼檢查）的 `AutoboxingStateCreation` 會提醒。
- 用了卻讀 `.value`，一樣 boxing；要讀 `longValue` 或用 `by`。

**SwiftUI 對照**

- Swift 的 `Int` 是值型別，`@State` 直接存整數，不會 boxing，所以沒有這組 API。
- Compose 這組只是補 Kotlin 泛型只能裝物件的限制。

```swift
@State private var price = 0
@State private var unread = 0

Text("價格：\(price)，未讀：\(unread)")
```

```kotlin
var price by remember { mutableLongStateOf(0L) }
var unread by remember { mutableIntStateOf(0) }
```

> [!TIP]
> 面試時可以這樣說：「數字用 primitive 版避免 boxing；頻繁更新的 State 往下傳，在最小的 Composable 才讀。」

### 3. `mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？

**SwiftUI 的陣列是值型別，`append` 就會重畫；Compose 的 `List` 是參考，原地修改不會通知，要用 `mutableStateListOf` 或整個換成新的 list。**

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(…))` | 整個換掉：`orders = orders + order` | 值被換掉時 |
| `mutableStateOf(mutableListOf(…))` | 原地 `add` | 不會（lint 的 `MutableCollectionMutableState` 會警告） |
| `mutableStateListOf(…)` | `add`、`removeAt`、`set`、`clear` | 清單內容改變時 |

#### `mutableStateListOf`

**這是什麼**

- Compose 追蹤的清單，`SnapshotStateList`。
- `add`、`removeAt`、`set` 都會通知畫面。
- 現成的 list 用 `toMutableStateList()` 轉換。

**什麼時候用**

- 用：一筆一筆增減的清單，例如剛送出的委託。
- 不用：每次整份換掉的資料；或要存進 `rememberSaveable`（預設存不進去，第 5 題）。

**範例**

按「送出」就 `add` 一筆委託。

```kotlin
data class OrderRow(val symbol: String, val filled: Double)

val orders = remember { mutableStateListOf<OrderRow>() }

Button(onClick = { orders.add(OrderRow("BTC_JPY", 0.0)) }) {
    Text("送出")
}
```

**常見錯誤**

- 項目是 `class` 又原地改欄位：清單沒變，畫面不動；用 `data class` 加 `val`，`copy` 換掉那一格（演練 1）。
- 走訪時修改會丟 `ConcurrentModificationException`；刪項目用 `removeAll { }`。

**SwiftUI 對照**

- SwiftUI 陣列是值型別，`append` 或改欄位都等於寫回 `@State`，所以會重畫。
- Compose 的 `List` 不是，要靠 `mutableStateListOf`；`data class` 加 `copy` 在模仿值語意。

```swift
struct OrderRow {
    let symbol: String
    var filled: Double
}

@State private var orders = [OrderRow(symbol: "ETH_JPY", filled: 0)]

// struct 是值型別：改一個欄位，等於寫回整個陣列
orders[0].filled = 0.5
```

```kotlin
val orders = remember { mutableStateListOf(OrderRow("ETH_JPY", 0.0)) }

// data class 加 val：用 copy 換掉那一格
orders[0] = orders[0].copy(filled = 0.5)
```

#### `mutableStateMapOf`

**這是什麼**

- 對照表版本，`SnapshotStateMap`。
- `put`、`remove` 都會通知畫面。
- 現成的 map 用 `toMutableStateMap()` 轉換。

**什麼時候用**

- 用：「交易對 → 最新價格」這種一筆一筆更新的對照表。
- 不用：`mutableStateOf(mutableMapOf())` 再原地 `put`（不會通知）；值是可原地修改的物件時，要換掉整個值。

**範例**

收到報價就寫進對照表。

```kotlin
val latest = remember { mutableStateMapOf<String, Long>() }

Button(onClick = { latest["BTC_JPY"] = 1_000_123L }) {
    Text("收到 BTC_JPY 報價")
}
```

**SwiftUI 對照**

- `@State private var latest: [String: Int]` 直接對應。
- 字典是值型別，改 key 等於寫回整個字典，所以不需要專用 API。

```swift
@State private var latest: [String: Int] = [:]

latest["BTC_JPY"] = 1_000_123
```

```kotlin
val latest = remember { mutableStateMapOf<String, Long>() }

latest["BTC_JPY"] = 1_000_123L
```

#### `mutableStateOf(listOf(…))`：整個換掉

**這是什麼**

- 不是專用 API，是用 State 存不可變的 list，更新時整個換掉。
- 追蹤「值有沒有被換掉」，不是內容有沒有變。

**什麼時候用**

- 用：整份換掉的資料，如每秒一份訂單簿快照；或清單很小。
- 不用：頻繁原地增減的清單。

**範例**

收到快照就整份換掉，Compose 才知道有變。

```kotlin
var bids by remember { mutableStateOf(listOf<Long>()) }

Button(onClick = { bids = listOf(101L, 100L, 99L) }) {
    Text("收到訂單簿快照")
}
```

**SwiftUI 對照**

- SwiftUI 的 `append` 會重畫，因為值型別的修改等於寫回 `@State`。
- Compose 的 `listOf` 不可變；`orders + x` 等於 `orders = orders + [x]`。

```swift
@State private var orders: [String] = []

// append 原地改，值型別的 @State 照樣重畫
orders.append("BTC_JPY 買 0.25")
```

```kotlin
var orders by remember { mutableStateOf(listOf<String>()) }

// listOf 不可變：建立新 list 再賦值
orders = orders + "BTC_JPY 買 0.25"
```

> [!TIP]
> 面試時可以這樣說：「List 不是值型別，所以用 mutableStateListOf，項目用 data class 並 copy 換掉。」

### 4. `SnapshotMutationPolicy`（SwiftUI 沒有）什麼時候要換？

**Compose 寫入 State 時先比較新舊值，「一樣」就當作沒發生；預設用 `equals` 比，想改比法就在 `mutableStateOf` 的第二個參數傳 policy。**

- 「判斷新舊值算不算一樣的規則」叫 `SnapshotMutationPolicy`；SwiftUI 沒有，所以這一題四個 API 都是 Compose 獨有。

| policy | 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b` | 字串、數字、data class，絕大多數情況 |
| `referentialEqualityPolicy()` | `a === b`（同一個物件才算） | `equals` 很貴，或不適合決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

#### `structuralEqualityPolicy()`（預設，不用寫）

**這是什麼**

- 用 `equals`（`==`）比較新舊值，相等就丟掉寫入。
- 平常寫 `mutableStateOf(x)` 用的就是它。

**什麼時候用**

- 用：幾乎都用它，如字串、數字、`data class`。
- 不用：`equals` 不適合決定畫面更新時，改用下面兩個 policy。

**範例**

內容完全一樣的新物件，不重組。

```kotlin
data class Tick(val symbol: String, val price: Long, val receivedAt: Long)

var tick by remember {
    mutableStateOf(Tick("BTC_JPY", 100, receivedAt = 1))
}

// 內容完全一樣：這次寫入被丟掉
Button(onClick = { tick = Tick("BTC_JPY", 100, receivedAt = 1) }) {
    Text("${tick.symbol} ${tick.price}")
}
```

**常見錯誤**

- 領域物件 `Order` 的 `equals` 只比 `id`：A1 從 New 變 Filled 被當成沒變，畫面停在 New（演練 2）。

**SwiftUI 對照**

- 沒有對應：`@State` 的比對規則由框架決定，不能調整。

#### `referentialEqualityPolicy()`

**這是什麼**

- 用 `===` 比較：必須是同一個物件才算一樣。

**什麼時候用**

- 用：`equals` 只比 id（如 Room，Android 本機資料庫函式庫的 entity），或 `equals` 很貴。
- 不用：更好的做法常是讓 UI 的 model 用 `data class`。

**範例**

`Order` 的 `equals` 只比 `id`，改用 `===` 比。

```kotlin
class Order(val id: String, val status: String) {
    // 只比 id
    override fun equals(other: Any?) = other is Order && other.id == id

    override fun hashCode() = id.hashCode()
}

var order by remember {
    mutableStateOf(Order("A1", "New"), referentialEqualityPolicy())
}

Button(onClick = { order = Order("A1", "Filled") }) {
    Text("訂單 ${order.id}：${order.status}")
}
```

**SwiftUI 對照**

- 沒有對應：struct 是值型別，沒有「同一個物件」可比；class 搭配 `@Observable` 追蹤的是屬性寫入。

#### `neverEqualPolicy()`

**這是什麼**

- 任何一次寫入都算變了，連同一個物件寫進去也會通知。

**什麼時候用**

- 用：引擎就地更新、全 App 共用一個物件，更新後寫回同一個物件來通知（演練 3）。
- 不用：想「再顯示一次相同的錯誤訊息」。

**範例**

引擎就地更新，再把同一個物件寫回去。

```kotlin
@Composable
fun BestBid(book: OrderBook) {
    val state = remember { mutableStateOf(book, neverEqualPolicy()) }

    Button(onClick = {
        // 就地更新後，把同一個物件寫回去
        book.update(101, 1.5)
        state.value = book
    }) {
        Text("最佳買價：${state.value.bestBid()}")
    }
}
```

**常見錯誤**

- 不用它：同一個物件被當成沒變，最佳買價停在舊的；代價是每次寫入都重組。
- 拿它重複顯示訊息沒用：`LaunchedEffect(message)` 比 key 用 `equals`，相同字串不重啟；改包成帶 id 的物件，或照 Kotlin05 第 3 題。

**SwiftUI 對照**

- 沒有對應：`@Observable` 屬性一被寫入就通知，不需要再寫回同一個物件。

#### 自訂 `SnapshotMutationPolicy`

**這是什麼**

- 自己實作 `equivalent(a, b)`，決定「什麼叫一樣」。

**什麼時候用**

- 用：行情帶接收時間，但價格沒變不該重組；不自訂則每筆都重組。`derivedStateOf`（第 6 題）也能傳 policy。
- 不用：預設或內建 policy 夠用時。

**範例**

只比 symbol 和 price，接收時間不同也算一樣。

```kotlin
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    // 只比畫面看得到的欄位，不比接收時間
    override fun equivalent(a: Tick, b: Tick): Boolean {
        return a.symbol == b.symbol && a.price == b.price
    }
}

var tick by remember {
    mutableStateOf(Tick("BTC_JPY", 100, 1), tickPolicy)
}
```

**SwiftUI 對照**

- 沒有對應：SwiftUI 不開放比較規則；控制重算靠拆小 view，或 `Equatable` 加 `.equatable()`（大致）。

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會被丟掉。」

### 5. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**`remember` 對應 `@State`，只活過重組；`rememberSaveable` 大致對應 `@SceneStorage`，撐得過旋轉和行程被殺，只放小量資料；業務資料放 ViewModel。**

- Android 旋轉螢幕會重建 Activity（畫面容器），SwiftUI 的 view 不會，所以 `remember` 的值會消失。
- 行程被殺（process death）是 App 在背景被系統整個回收；ViewModel 大致像 `@StateObject` 的 model。

| 事件 | `remember` | `rememberSaveable` | ViewModel |
|---|---|---|---|
| 重組 | 還在 | 還在 | 還在 |
| 旋轉螢幕 | 消失 | 還在 | 還在 |
| 行程被殺後重開 | 消失 | 還在 | 消失（要搭配 `SavedStateHandle`） |

#### `remember(key)`

**這是什麼**

- 括號裡的 key 是「會影響計算結果的輸入」。
- key 和上次不一樣，就丟掉存的值、重新計算。

**什麼時候用**

- 用：結果取決於某個參數且算起來不便宜，如依 tick size（價格最小跳動單位）決定小數位數的格式化器；沒寫 key，換交易對後格式會停在上一個（Kotlin06 演練 6）。
- 不用：把「一直在變」的值當 key，等於沒有 `remember`。

**範例**

`tickSize` 當 key，換交易對就重建格式化器。

```kotlin
@Composable
fun PriceText(price: Long, tickSize: Double) {
    val formatter = remember(tickSize) { PriceFormatter(tickSize) }

    Text(formatter.format(price))
}
```

**SwiftUI 對照**

- SwiftUI 沒有內建「依輸入快取」：`@State` 初始值只在 view 第一次出現時採用。
- 要自己 `.onChange(of:)` 更新，或 `.id()` 換整個 view 的身分（會連其他 `@State` 一起重置）。

```swift
@State private var formatter = PriceFormatter(tickSize: 0.01)

Text(formatter.format(price))
    // tickSize 變了，自己重建（iOS 17 的寫法）
    .onChange(of: tickSize) { _, newValue in
        formatter = PriceFormatter(tickSize: newValue)
    }
```

```kotlin
val formatter = remember(tickSize) { PriceFormatter(tickSize) }
```

#### `rememberSaveable`

**這是什麼**

- 寫法同 `remember`，值還存進 Bundle（暫存畫面狀態的容器）。
- 旋轉、行程被殺都撐得過；也能放 key。

**什麼時候用**

- 用：輸入到一半的欄位（數量、價格）、選的分頁。
- 不用：大型資料放資料庫或 repository（取得與保存資料的類別），業務資料放 ViewModel。

**範例**

價格用 `symbol` 當 key，切換交易對就清空。

```kotlin
@Composable
fun OrderInputs(symbol: String) {
    var qty by rememberSaveable { mutableStateOf("") }

    // symbol 當 key：切換交易對就清空
    var price by rememberSaveable(symbol) { mutableStateOf("") }

    Text("數量 $qty，價格 $price")
}
```

**常見錯誤**

- 輸入框用 `remember`：輸入 `0.25` 後旋轉就清空（Kotlin06 演練 5）。
- 測行程被殺：App 退到背景，`adb shell am kill <套件名稱>`，再從最近使用的 App 點回來。

**SwiftUI 對照**

- 大致對應 `@SceneStorage`，保存輸入到一半的內容。
- iOS 旋轉不重建，`@State` 平常夠用；Android 旋轉就重建，輸入框幾乎都要 `rememberSaveable`。

```swift
@SceneStorage("qty") private var qty = ""

TextField("數量", text: $qty)
```

```kotlin
var qty by rememberSaveable { mutableStateOf("") }

TextField(value = qty, onValueChange = { qty = it })
```

#### `Saver`（`listSaver`、`mapSaver`）

**這是什麼**

- Bundle 只收基本型別與 `Parcelable` 等。
- data class 存不了，要給 `Saver`。
- `Saver` 定義 `save` 怎麼拆、`restore` 怎麼組。

**什麼時候用**

- 用：自訂型別要放進 `rememberSaveable`，做法見下表。
- 不用：ViewModel 撐過行程被殺，用 `SavedStateHandle`（會還原的 key-value 存放處），同樣只適合小量資料。

| 做法 | 適合 | 缺點 |
|---|---|---|
| `@Parcelize` 加 `Parcelable`（Android 的序列化介面） | 型別本來就要放進 Intent 或 Bundle | 要套用 Parcelize plugin |
| `listSaver` | 欄位少而且固定 | 用位置對應（`it[0]`、`it[1]`），加欄位或換順序容易對錯 |
| `mapSaver` | 欄位多，之後可能增加 | 用 key 對應，漏存的欄位不會報錯（演練 5） |
| `Saver(save, restore)` | 單一值的型別，如把金額存成字串 | 自己寫 |

**範例**

`listSaver` 把草稿拆成 `List`。

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

val draftSaver = listSaver<OrderDraft, Any>(
    save = { listOf(it.symbol, it.qty) },
    restore = { OrderDraft(it[0] as String, it[1] as Double) },
)

// MutableState 的參數叫 stateSaver；一般物件的版本叫 saver
var draft by rememberSaveable(stateSaver = draftSaver) {
    mutableStateOf(OrderDraft("BTC_JPY", 0.1))
}
```

**常見錯誤**

- 沒給 Saver 就放 data class：組合時丟 `IllegalArgumentException`，一打開就閃退（演練 4）。
- 加欄位卻忘了改 Saver：`restore` 用預設值補，旋轉後該欄位靜悄悄歸零（演練 5）；要寫單元測試。

**SwiftUI 對照**

- SwiftUI 沒有 Saver 型別；`@SceneStorage` 只收基本型別（`Bool`、`Int`、`Double`、`String`、`URL`、`Data`）和 `RawRepresentable`。
- 自訂型別要拆成基本型別各存一個；Compose 把拆與組集中在 `Saver`，能單獨測試。

```swift
// SceneStorage 只收基本型別：草稿拆成兩個欄位各存各的
@SceneStorage("draft.symbol") private var symbol = "BTC_JPY"
@SceneStorage("draft.qty") private var qty = 0.1
```

```kotlin
// 整個草稿是一個值；拆與組的規則集中在 draftSaver
var draft by rememberSaveable(stateSaver = draftSaver) {
    mutableStateOf(OrderDraft("BTC_JPY", 0.1))
}
```

#### `rememberUpdatedState`

**這是什麼**

- 把「最新傳進來的值」包成 State。
- 長時間運作的 effect 讀到最新值，不用重啟。

**什麼時候用**

- 用：10 秒逾時倒數（用 coroutine，約等於 Swift 的 `Task`），時間到呼叫 `onTimeout`；期間 callback 換了版，倒數不能重來。
- 不用：想讓 effect 在值變時重啟，就把值放進 `LaunchedEffect` 的 key（Kotlin07 第 2 題）。

**範例**

只啟動一次，時間到才讀最新的 callback。

```kotlin
@Composable
fun SubmitTimeout(onTimeout: () -> Unit) {
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    // key 是 Unit：只啟動一次，重組不重啟
    LaunchedEffect(Unit) {
        delay(10_000)
        currentOnTimeout()
    }
}
```

**常見錯誤**

- 直接呼叫 `onTimeout` 會抓到舊版；放進 key 則 callback 一換就取消重啟，10 秒重新計（Kotlin07 演練 5）。

**SwiftUI 對照**

- 大致對應「`.task` 讀 model 的屬性，而不是啟動時抓到的 view 屬性」。
- `.task` 捕捉啟動時的 view 值，看不到新 closure；常見做法是 callback 放 class 裡，要用時才讀。

```swift
@Observable
final class SubmitModel {
    var onTimeout: () -> Void = {}
}

Color.clear.task {
    try? await Task.sleep(for: .seconds(10))

    // 讀 model 的屬性，不是啟動時抓到的值
    model.onTimeout()
}
```

```kotlin
val currentOnTimeout by rememberUpdatedState(onTimeout)

LaunchedEffect(Unit) {
    delay(10_000)
    currentOnTimeout()
}
```

> [!TIP]
> 面試時可以這樣說：「rememberSaveable 像 @SceneStorage，自訂 class 要給 Saver，新增欄位要同步改。」

### 6. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**`derivedStateOf` 是 State 變 State，用在「輸入常變、結果少變」；`snapshotFlow` 是 State 變 Flow，大致對應 `.onChange(of:)`；`produceState` 是 suspend 函式或 callback 變 State，大致對應 `.task { state = await … }`。**

#### `derivedStateOf`

**這是什麼**

- 用計算式從別的 State 算出新的 State。
- 只有「結果」真的變了，才通知讀它的 Composable。

**什麼時候用**

- 用：價格每秒變幾十次，紅字只在超過、回到警示線時才要變。
- 不用：結果跟著輸入一起變（`price * qty`），沒省到通知。

| | 直接算 | `remember(key) { 計算 }` | `remember { derivedStateOf { 計算 } }` |
|---|---|---|---|
| 重算時機 | 每次重組 | key 變了 | 讀過的 State 變了 |
| 追蹤什麼 | 函式裡讀到的 State | 放進 key 的一般參數 | **State 的讀取**，一般參數它看不到 |
| 重組時機 | 輸入每變一次就重組 | 沒有通知，只在重組時重算 | 結果真的變了才重組 |
| 適合 | 輸入不常變，或結果跟著輸入變 | 參數變了要重算（排序、篩選） | State 變很頻繁、結果很少變 |

**範例**

一般參數放 `remember` 的 key，State 在大括號裡讀。

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    // alertLine 是一般參數，當 key；price 是 State，在大括號裡讀
    val over by remember(alertLine) {
        derivedStateOf { price.value > alertLine }
    }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

**常見錯誤**

- 直接寫 `val over = price.value > alertLine`：每次價格變都重組。
- 漏了 `remember`：每次重組都建新的 derived state。
- 參數沒當 key：警示線調高了還顯示「超過」（演練 6）。

**SwiftUI 對照**

- 沒有對應：SwiftUI 寫計算屬性，每次 `body` 重算就算一次。
- 警示線也是 State 時，計算式裡直接讀，不需要 key。

```swift
var body: some View {
    if quote.price > alertLine {
        Text("價格超過警示線").foregroundStyle(.red)
    }
}
```

```kotlin
val over by remember {
    derivedStateOf { price.value > alertLine.value }
}
```

#### `snapshotFlow`

**這是什麼**

- 在大括號裡讀 State，回傳 Flow（非同步資料串流）。
- 開始收集先送目前的值，之後 State 變了送新值。
- 連續相同的值去重（0、1、1、2 只收到 0、1、2）。

**什麼時候用**

- 用：State 改變時「做一件事」，如交易對變了回報分析（analytics）、數量改了存草稿。
- 不用：只是要顯示 State（直接讀）；或要讓非 Compose 的程式觀察 ViewModel（改用 `StateFlow`，第 8 題）。

**範例**

交易對一變，就回報給分析。

```kotlin
@Composable
fun SymbolPicker(analytics: Analytics) {
    var symbol by remember { mutableStateOf("BTC_JPY") }

    LaunchedEffect(Unit) {
        // 大括號裡讀 State：symbol 變了就收到新值
        snapshotFlow { symbol }.collect {
            analytics.log("symbol_changed", it)
        }
    }
}
```

**常見錯誤**

- `val s = symbol` 再 `snapshotFlow { s }`：lambda 裡沒讀 State，只有第一個值。
- 單元測試（沒有 Compose 在跑）收不到後續變化，見 Snapshot 與演練 8。

**SwiftUI 對照**

- 大致對應 `.onChange(of:initial:)`（`initial` iOS 17 起有）。
- `snapshotFlow` 一定先送目前的值，像 `initial: true`；`.onChange` 預設第一次不執行。要只在變動時做，用 `drop(1)`。

```swift
TextField("數量", text: $qty)
    // initial 預設是 false：第一次不執行，只在變動時執行
    .onChange(of: qty) { _, newValue in
        store.save(qty: newValue)
    }
```

```kotlin
LaunchedEffect(Unit) {
    // snapshotFlow 一開始就送出目前的值，drop(1) 跳過它
    snapshotFlow { qty }
        .drop(1)
        .collect { store.save(it) }
}
```

#### `produceState`

**這是什麼**

- 把 suspend 函式或 callback 的結果變成 State。
- 結果回來前是 `initialValue`；key 換了或離開畫面就取消。

**什麼時候用**

- 用：顯示伺服器時間（一次性的 suspend 函式，約等於 Swift 的 `async` 函式），或 callback 風格的 socket。
- 不用：來源是 Flow（在 `produceState` 裡 `flow.collect` 就是 `collectAsState` 的做法），直接用第 7 題。

**範例**

callback 轉成 State，離開畫面時解除 listener。

```kotlin
@Composable
fun rememberLastPrice(socket: PriceSocket): State<Long> {
    return produceState(initialValue = 0L, socket) {
        val listener = PriceSocket.Listener { value = it }
        socket.addListener(listener)

        // 離開畫面時解除
        awaitDispose { socket.removeListener(listener) }
    }
}
```

**常見錯誤**

- callback 版沒用 `awaitDispose` 解除：listener 一直掛著（Kotlin07 演練 6）。
- 漏放 key：參數變了不重跑，規則同 `LaunchedEffect`（Kotlin07 演練 2）。

**SwiftUI 對照**

- 大致對應 `.task { state = await … }`；`produceState` 包成回傳 State 的 Composable，不用自己宣告 State。

```swift
@State private var serverTime: Int?

Text(serverTime.map { "伺服器時間：\($0)" } ?? "--")
    // view 出現時啟動，消失時取消
    .task { serverTime = await client.fetchServerTime() }
```

```kotlin
// 結果回來前是 null；離開畫面時自動取消
val serverTime by produceState<Long?>(null, client) {
    value = client.fetchServerTime()
}

Text(serverTime?.let { "伺服器時間：$it" } ?: "--")
```

#### Snapshot

**這是什麼**

- Compose 管理 State 讀寫的機制，像一本記錄本。
- 讀取追蹤：記下誰讀了哪個 State，寫入才去通知。
- 寫入要「套用並通知」，別人才看得到。

**什麼時候用**

- 用：單元測試驗證 `snapshotFlow`；多個 State 要一起生效，用 `Snapshot.withMutableSnapshot { … }`。
- 用：理解 `SideEffect`，它在組合成功套用後才執行，被放棄的組合不執行（Kotlin07 第 4 題）。
- 不用：業務邏輯裡自己管理 Snapshot。

**範例**

重設表單，兩個欄位一起生效。

```kotlin
class OrderFormState {
    var symbol by mutableStateOf("BTC_JPY")
    var qty by mutableStateOf("")

    fun reset() {
        // 離開區塊時一起生效，別人看不到只改一半
        Snapshot.withMutableSnapshot {
            symbol = "BTC_JPY"
            qty = ""
        }
    }
}
```

**常見錯誤**

- 單元測試裡 `snapshotFlow` 只收到第一筆：沒有 Compose 就沒人套用寫入，要呼叫 `Snapshot.sendApplyNotifications()`（演練 8；實測：呼叫前只收到第一筆，呼叫後收到最後一筆）。

**SwiftUI 對照**

- 「讀取追蹤」大致對應 iOS 17 的 Observation：`body` 只依賴讀過的屬性。
- 「套用並通知」與 `withMutableSnapshot` 沒有對應：`@Observable` 寫入就通知，多個 `@State` 大致合併成一次更新。

```swift
@Observable
final class Quote {
    var price = 0
    var volume = 0
}

// body 只讀了 price：price 變才重算，volume 變不會
Text("價格：\(quote.price)")
```

```kotlin
class Quote {
    var price by mutableLongStateOf(0L)
    var volume by mutableLongStateOf(0L)
}

// 只讀了 price：price 變才重組，volume 變不會
Text("價格：${quote.price}")
```

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤 State 的讀取，輸入頻繁、輸出少變時用，要包 remember。」

### 7. 價格 Flow 退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 不管 lifecycle，只要還在 composition（Compose 目前建好的畫面樹）裡就一直收。**

- Flow 像 `AsyncSequence`；cold Flow 有人收才開始跑、每個收集者各跑一份。`StateFlow` 永遠保存最新值，像 `CurrentValueSubject`；`SharedFlow` 沒有目前值，常廣播一次性事件，像 `PassthroughSubject`。
- lifecycle 是 Android 畫面各階段；`STARTED` 是「畫面看得到」，退到背景就在它之下。

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 何時收集 | 進入 composition 就收，離開才停 | lifecycle 至少到 `STARTED`（`minActiveState` 預設值）才收 |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集，回前景重新收集 |
| 套件 | Compose 本體 | `androidx.lifecycle:lifecycle-runtime-compose`（演練專案用 2.9.4），要自己加依賴 |

#### `collectAsState()`

**這是什麼**

- 把 Flow 變成 State，進入畫面樹就收，離開才停。
- 不管 Android 的 lifecycle。

**什麼時候用**

- 用：非 Android 環境（如 Compose Multiplatform）；或刻意要背景持續更新。
- 不用：Android 行情、訂單、餘額畫面，背景持續收集、重組，耗電耗流量。實測（演練 7，每 50 ms 推一筆價格）：背景 0.5 秒，畫面又更新 9 次。

**範例**

`StateFlow` 有目前值，不用給 `initial`。

```kotlin
@Composable
fun LivePriceLine(prices: StateFlow<Long>) {
    val price by prices.collectAsState()

    Text("即時價格：$price")
}
```

**SwiftUI 對照**

- 大致對應 `.task` 裡 `for await`：退到背景 task 也不會自己取消；`@State` 要自己宣告。

```swift
Text(price.map { "即時價格：\($0)" } ?? "--")
    // view 消失才取消；退到背景 task 照跑
    .task {
        for await value in prices {
            price = value
        }
    }
```

```kotlin
// 一般 Flow 沒有目前值，一定要給 initial
val price by prices.collectAsState(initial = null)
```

#### `collectAsStateWithLifecycle()`

**這是什麼**

- 把 Flow 變成 State，跟著 lifecycle 開始與停止。
- 至少到 `STARTED`（看得到）才收，退到背景就取消，回前景重新收。

**什麼時候用**

- 用：Android 畫面顯示 Flow 預設用它。實測（同演練 7）：背景 0.5 秒更新 0 次。
- 不用：`SharedFlow` 的一次性事件（State 只留最新值，連發會漏）；純 JVM 單元測試（在 Android 主執行緒收集）。

| 來源 | 怎麼收 | initial |
|---|---|---|
| `StateFlow<T>` | `collectAsStateWithLifecycle()` | 不用給，一定有目前值 |
| 一般 `Flow<T>`（cold） | `collectAsStateWithLifecycle(initialValue)` | 一定要給：第一個值來之前顯示什麼 |
| `SharedFlow<T>`（事件） | 通常不轉 State，用 `LaunchedEffect` 收 | State 只留最新值，連發的事件會漏 |

**範例**

一般 Flow 要自己給 `initialValue`。

```kotlin
@Composable
fun PriceHeader(vm: PriceViewModel) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // 一般 Flow 要自己給 initial
    val last by vm.priceFlow.collectAsStateWithLifecycle(initialValue = null)

    Text("${uiState.symbol} ${last ?: "--"}")
}
```

**常見錯誤**

- cold Flow 回前景會重新收集、上游從頭跑：先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，設 `WhileSubscribed(5000)`（Kotlin05 第 5 題）。
- `initial` 別給 `0` 這種像真的數字，用 `null` 或 `Loading`。
- 在 Composable 讀 `stateFlow.value`（`liveData.value` 同理）不會觀察變化；lint 的 `StateFlowValueCalledInComposition` 會警告。

**SwiftUI 對照**

- `@Observable` 屬性在 `body` 直接讀就被追蹤；要「背景就停」得自己看 `scenePhase`，用 `.task(id:)` 重啟。
- `STARTED` 約等於 `scenePhase != .background`；`minActiveState` 設 `RESUMED` 約等於 `.active`。

```swift
@Environment(\.scenePhase) private var scenePhase

Text(vm.price.map { "\($0) JPY" } ?? "--")
    // scenePhase 一變就取消重啟；退到背景就不收
    .task(id: scenePhase) {
        guard scenePhase != .background else { return }
        await vm.startListening()
    }
```

```kotlin
val price by vm.price.collectAsStateWithLifecycle()
```

#### `LiveData.observeAsState()`

**這是什麼**

- 把 `LiveData`（舊式可觀察資料類別）變成 State。
- 它本身跟著 lifecycle，沒有 `WithLifecycle` 版。

**什麼時候用**

- 用：舊 ViewModel 還是 LiveData，不必為統一而大改。
- 不用：新程式碼，用 `StateFlow`。

**範例**

依賴 `androidx.compose.runtime:runtime-livedata`；沒給 `initial` 時，還沒有值是 `null`。

```kotlin
// 給了 initial（如 initial = 0L），型別就不含 null
val price by vm.priceLiveData.observeAsState()

Text(price?.let { "$it JPY" } ?: "--")
```

**SwiftUI 對照**

- 大致對應 `ObservableObject` 搭配 `@Published` 的時代：view 用 `@ObservedObject` 或 `@StateObject` 接。

```swift
final class LegacyPriceViewModel: ObservableObject {
    @Published var price: Int?
}

@ObservedObject var vm: LegacyPriceViewModel
```

```kotlin
val price by vm.priceLiveData.observeAsState()
```

> [!TIP]
> 面試時可以這樣說：「Android 畫面用 collectAsStateWithLifecycle，退到背景就取消。」

### 8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**業務狀態預設放 ViewModel 的 `StateFlow`（內部 `MutableStateFlow`），畫面用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。**

#### `StateFlow`（`MutableStateFlow`）

**這是什麼**

- 永遠保存最新的值，新收集者馬上拿到。
- 內部可寫，對外用 `asStateFlow()` 唯讀。
- `update { }` 是原子更新，同時改不會互相蓋掉。

**什麼時候用**

- 用：ViewModel 的業務狀態（行情、訂單、餘額）預設用它。
- 用：任何 coroutine 都能 `collect`，Worker、單元測試都用得上。
- 不用：輸入框的即時文字；繞 Flow 比輸入法晚一步，中文、日文組字時游標可能跳，放畫面自己的 State。

**範例**

ViewModel 更新價格，畫面用 `collectAsStateWithLifecycle()` 收。

```kotlin
data class OrderUiState(val price: Long? = null)

class OrderViewModel : ViewModel() {
    // 內部可寫，對外唯讀
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    // 原子更新：同時改不會互相蓋掉
    fun onPrice(price: Long) = _state.update { it.copy(price = price) }
}

// 畫面端：val state by vm.state.collectAsStateWithLifecycle()
```

**SwiftUI 對照**

- 大致對應 `@Published`（或 `CurrentValueSubject`），`$state` 可給非 UI 程式訂閱。
- SwiftUI 沒有 `update { }`，通常用 `@MainActor` 讓寫入排在主執行緒。

```swift
@MainActor
final class OrderViewModel: ObservableObject {
    // 對外唯讀，內部可寫；$state 是一條串流
    @Published private(set) var state = OrderUiState()

    func onPrice(_ price: Int) {
        state.price = price
    }
}
```

```kotlin
_state.update { it.copy(price = price) }
```

#### ViewModel 裡的 `mutableStateOf`

**這是什麼**

- ViewModel 的屬性直接用 `mutableStateOf`。
- 畫面讀它就被追蹤，不用 `collect`。

**什麼時候用**

- 用：只有 Compose 畫面會讀，專案小、不需要 Flow 運算子。
- 不用：要被 Worker、單元測試、View 畫面或 KMP（Kotlin Multiplatform，同一份 Kotlin 給多平台用）觀察時。

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者 | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，沒有 Compose 就收不到（演練 8） |
| Flow 運算子 | `combine`、`debounce` 直接用 | 要先用 `snapshotFlow` 轉 |
| 原子更新 | `_state.update { … }` | 沒有 |
| 畫面端 | 要收集並處理 initial | 直接讀 `vm.state` |
| lifecycle | `WithLifecycle`，背景會停 | 沒有收集，沒有 lifecycle 可停 |
| 依賴 | 不需要 Compose | 依賴 Compose runtime |

**範例**

輸入框文字放 ViewModel，畫面直接讀。

```kotlin
class FormViewModel : ViewModel() {
    var qty by mutableStateOf("")
        private set

    fun onQtyChange(input: String) {
        qty = input
    }
}

@Composable
fun QtyScreen(vm: FormViewModel = viewModel()) {
    OutlinedTextField(value = vm.qty, onValueChange = vm::onQtyChange)
}
```

**SwiftUI 對照**

- 大致對應 `@Observable` 的屬性：`body` 直接讀，不需訂閱。
- 缺點也相同：只有 SwiftUI 看得到，不是可組合的串流。

```swift
@Observable
final class FormViewModel {
    private(set) var qty = ""
}

// body 裡直接讀 vm.qty，不需要訂閱
Text(vm.qty)
```

```kotlin
Text(vm.qty)
```

#### state hoisting（狀態提升）

**這是什麼**

- 把狀態移到上層，子 Composable 只收值和 callback。
- 值往下、事件往上；Compose 沒有 `Binding` 型別。

**什麼時候用**

- 用：子元件要重用、單獨測試，或兩個以上兄弟元件共用，如數量輸入框和「預估金額」。
- 不用：只有一個 Composable 在乎的暫時狀態（展開、收合），用 `remember`。
- 不用：傳整個 `MutableState` 下去，可行但不建議（Kotlin09 第 1 題）。

**範例**

數量放 `OrderForm`，輸入框只顯示與回報。

```kotlin
@Composable
fun OrderForm() {
    var qty by rememberSaveable { mutableStateOf("") }

    // 值往下、onQtyChange 往上
    QtyField(qty = qty, onQtyChange = { qty = it })
    Text("預估金額：${estimate(qty)}")
}

@Composable
fun QtyField(qty: String, onQtyChange: (String) -> Unit) {
    OutlinedTextField(value = qty, onValueChange = onQtyChange)
}
```

**SwiftUI 對照**

- `@Binding var qty` 對應 `qty` 加 `onQtyChange`：Binding 把讀和寫包成一個物件，Compose 拆成值加 callback。

```swift
struct QtyField: View {
    @Binding var qty: String

    var body: some View {
        TextField("數量", text: $qty)
    }
}

// 父 view：QtyField(qty: $qty)，$qty 是 Binding
```

```kotlin
@Composable
fun QtyField(qty: String, onQtyChange: (String) -> Unit) {
    OutlinedTextField(value = qty, onValueChange = onQtyChange)
}

// 父 Composable：QtyField(qty = qty, onQtyChange = { qty = it })
```

#### state holder、`rememberCoroutineScope`、`viewModelScope`：狀態放哪一層

**這是什麼**

- 看「誰在乎、要活多久」決定狀態放哪層。
- state holder 是收納畫面邏輯的普通 class。
- scope 是啟動 coroutine 的範圍，綁誰就跟誰取消。

**什麼時候用**

- 用：畫面邏輯（捲動、動畫、表單驗證）寫成 state holder，用 `rememberCoroutineScope` 在事件裡啟動 coroutine。
- 用：送出訂單這種必須做完的工作放 `viewModelScope`，旋轉不取消（Kotlin07 第 5 題）。
- 不用：必須做完的工作別放 state holder，它旋轉就重建，scope 離開畫面就取消。

| 這個狀態… | 放哪裡 |
|---|---|
| 只有這個 Composable 在乎（展開、選了哪個分頁） | `remember`；要撐過旋轉用 `rememberSaveable` |
| 兩個以上的兄弟 Composable 共用 | 提到共同的上層 Composable |
| 畫面邏輯，不依賴 domain（捲動、動畫、表單驗證） | state holder，搭配 `rememberXxxState()` |
| 業務資料（行情、訂單、餘額），要打 API、撐過旋轉 | ViewModel 的 `StateFlow` |
| 行程被殺後還要還原 | `rememberSaveable` 或 `SavedStateHandle` |

**範例**

畫面邏輯寫成 state holder，用 `rememberCoroutineScope`。

```kotlin
class PriceHolder(
    private val repo: PriceRepo,
    private val scope: CoroutineScope,
) {
    fun refresh() {
        scope.launch { repo.refresh() }
    }
}

@Composable
fun rememberPriceHolder(repo: PriceRepo): PriceHolder {
    // 綁在這個 Composable 上，離開畫面就取消
    val scope = rememberCoroutineScope()

    return remember(repo, scope) { PriceHolder(repo, scope) }
}
```

**SwiftUI 對照**

- 大致對應「`@State` 持有的 `@Observable` model」。
- `.task` 綁 view，像 `rememberCoroutineScope`；model 裡的 `Task { }` 不綁 view，像 `viewModelScope`。

```swift
func submit(_ order: OrderRow) {
    // model 裡的 Task 不綁 view：view 消失，它也會做完
    Task { await api.send(order) }
}
```

```kotlin
fun submit(order: OrderRow) {
    // viewModelScope 綁 ViewModel：旋轉不會取消，送出會做完
    viewModelScope.launch { api.send(order) }
}
```

> [!TIP]
> 面試時可以這樣說：「ViewModel 的畫面狀態用 StateFlow，非 Compose 的程式也能收；子畫面改值用 state hoisting。」

### 9. 情境對應 SwiftUI 與 Compose 該用哪個 API？

**先問三件事：資料從哪來、要活多久、誰要觀察它，再對照下表。**

| 情境 | SwiftUI | Compose（第幾題） |
|---|---|---|
| 畫面自己的值（計數、開關） | `@State` | `remember { mutableStateOf(…) }`（1） |
| 子畫面要改父畫面的值 | `@Binding` | state hoisting：值往下、`onValueChange` 往上（8） |
| 輸入到一半，要撐過旋轉或被殺 | `@SceneStorage` | `rememberSaveable`，自訂型別給 `Saver`（5） |
| 清單一筆一筆增減 | `@State var items: [T]` 加 `append` | `mutableStateListOf`，項目用 data class（3） |
| 價格超過警示線才顯示紅字 | 計算屬性 | `remember(alertLine) { derivedStateOf { … } }`（5、6） |
| State 變了要做一件事 | `.onChange(of:)` | `LaunchedEffect` 裡 `snapshotFlow`（6） |
| suspend 或 callback 轉成 State | `.task { state = await … }` | `produceState`（6） |
| Flow 顯示，背景不要收 | `@Observable` 屬性、`.onReceive`（背景看 `scenePhase`） | `collectAsStateWithLifecycle`，cold Flow 先 `stateIn`（7） |
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
