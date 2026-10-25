---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽（SwiftUI 對照版）：@State、@Binding、@Observable 在 Compose 裡叫什麼
summary: 給熟 SwiftUI、還沒寫過 Jetpack Compose 的 iOS 工程師。每個 API 固定分四段講：這是什麼、使用場景（什麼時候用、什麼時候不要用）、完整範例，以及 SwiftUI 的對照（先講對應關係與差異，再給 SwiftUI 與 Compose 兩段做同一件事的程式碼；SwiftUI 沒有的概念就說明為什麼沒有）。用同一張下單表單帶出 mutableStateOf、SnapshotMutationPolicy、rememberSaveable、derivedStateOf、collectAsStateWithLifecycle、StateFlow 等 API，最後給一張「情境、SwiftUI、Compose」三欄的速查表。
priority: P0
day: 5
tags: [compose, state, recomposition, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元寫給熟 SwiftUI、還沒寫過 Jetpack Compose 的 iOS 工程師。`@State`、`@Binding`、`@Observable`、`@Environment`、`.task`、`@SceneStorage` 你都會，也知道 `body` 會重算、`@State` 靠 view 在樹上的位置找回來。所以每個 API 都用同一個格式，分四段講：

1. **這是什麼**：白話說明它是什麼、解決什麼問題。
2. **使用場景**：用下單表單的情境，說什麼時候用、什麼時候不要用。
3. **範例**：一段看得懂上下文的完整程式碼。
4. **SwiftUI 的對照**：先說對應關係與差異，再給兩段做同一件事的程式碼，先 SwiftUI、再 Compose，可以逐行對照。SwiftUI 沒有的概念，就直接說沒有，以及為什麼沒有。

不是一對一的地方，標「大致對應」。

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

**`@Composable` 函式對應 `View.body`，State 一變就重新執行；`remember { mutableStateOf(…) }` 對應 `@State`。最大的差別是：Compose 要你自己用 `remember` 存值，忘了就在每次重組時歸零，SwiftUI 的 `@State` 沒有這個坑。** 這一題用同一個小例子「按下送出，顯示已送出幾筆」，依序講三個東西：函式、State、`remember`。

#### `@Composable` 函式

**這是什麼。** 用 `@Composable` 標記的 Kotlin 函式。它不是建立 view 物件，而是「描述」畫面現在長什麼樣子，例如 `Text("已送出 3 筆")`。資料變了，Compose 用新的資料把函式重新執行一次，比對前後兩次的描述，只更新有變的部分。這個重新執行叫重組（recomposition）；Compose 目前建好的整棵畫面樹叫 composition。

**使用場景。** 所有畫面都寫成 Composable，整張下單表單、單一個價格欄位都是。因為函式會被重複執行，裡面只放「描述畫面」的程式；打 API、寫檔案這類有副作用的事，不要直接寫在函式內容裡，要放進 effect（Compose 裡專門執行副作用的區塊，例如 `LaunchedEffect`，見 Kotlin07）。重組以函式為單位，把畫面拆成小函式，重畫的範圍也跟著變小。

**範例。** 畫面用普通的 Kotlin 流程控制（`if`、`for`）描述。數量是空的就顯示紅字提醒，不是空的就顯示數量：

```kotlin
@Composable
fun OrderSummary(symbol: String, qty: String) {
    Column {
        Text("交易對：$symbol")

        if (qty.isEmpty()) {
            Text("請輸入數量", color = Color.Red)
        } else {
            Text("數量：$qty")
        }
    }
}
```

**SwiftUI 的對照。** 一個 Composable 函式大致對應一個 `View` struct 的 `body`。重組的單位不同：SwiftUI 以 view 為單位重算 `body`，Compose 以函式為單位；函式的參數沒變、型別又是 stable（穩定，編譯器確定它不會被偷偷改；不穩定的情況見 Kotlin06）時，Compose 會直接跳過它（SwiftUI 也會比對子 view 的屬性，大致對應），所以第 2 題「把讀取往下推」的效益更直接。

```swift
struct OrderSummary: View {
    let symbol: String
    let qty: String

    var body: some View {
        VStack {
            Text("交易對：\(symbol)")
            Text("數量：\(qty)")
        }
    }
}
```

```kotlin
@Composable
fun OrderSummary(symbol: String, qty: String) {
    Column {
        Text("交易對：$symbol")
        Text("數量：$qty")
    }
}
```

#### State：Compose 盯著看的變數

**這是什麼。** State 是 Compose 會盯著看的變數。Compose 執行 Composable 時，會記下「哪個 Composable 讀過哪個 State」；State 被寫入新值，就重組讀過它的 Composable。普通的 `var` Compose 看不到，改了也沒人通知，畫面不會動，這是剛接觸 Compose 最常撞到的第一面牆。建立 State 的函式是 `mutableStateOf`（第 2 題細講）。

**使用場景。** 凡是「值改了，畫面要跟著變」的資料，都要放進 State：已送出幾筆、買賣方向、輸入框的文字。不需要讓畫面跟著變的值，例如只在事件處理裡暫時用到的計算結果，用普通變數就好，不必都包成 State。

**範例。** 先看錯誤寫法，`sent` 是普通的區域變數：

```kotlin
@Composable
fun SubmitCounterWrong() {
    // 普通的區域變數，Compose 看不到它
    var sent = 0

    Column {
        Text("已送出：$sent 筆")

        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

點「送出」之後，`sent` 真的變成 1，但沒有人通知 Compose，函式不會重新執行，畫面還是「已送出：0 筆」。改成 State 就會動，但還少一步 `remember`，下一節補上。

**SwiftUI 的對照。** `@State` 大致對應「State 加 `remember`」。SwiftUI 在這點上比較保護你：在 `body` 裡改 struct 的普通屬性，編譯器直接擋（`self` 是不可變的），寫不出「改了但畫面不動」的程式。Compose 的 `var` 是區域變數，能編譯也能改，只是沒人通知，類似 `ObservableObject` 的屬性忘了加 `@Published`。

```swift
struct SubmitCounterView: View {
    @State private var sent = 0

    var body: some View {
        VStack {
            Text("已送出：\(sent) 筆")

            Button("送出") {
                sent += 1
            }
        }
    }
}
```

```kotlin
@Composable
fun SubmitCounter() {
    // remember 下一節講，先當成 State 的固定搭配
    var sent by remember { mutableIntStateOf(0) }

    Column {
        Text("已送出：$sent 筆")

        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

#### `remember`

**這是什麼。** `remember { … }` 的意思是「重組時不要忘記」。Composable 每次重新執行，裡面的程式碼都從頭再跑，直接寫 `mutableIntStateOf(0)` 會每次重組都建立一個新的、值為 0 的 State。`remember` 在第一次執行時跑大括號、把結果存起來，之後重組直接拿存起來的那一份。存放的依據是這段程式在畫面樹上的位置。

**使用場景。** 用在所有要跨重組保留的東西：State，或建立起來比較貴的物件。它只管「重組之間」，旋轉螢幕、App 被系統殺掉它就管不到了，那是 `rememberSaveable`（第 5 題）；要撐過旋轉的業務資料放 ViewModel（Android 放畫面資料的物件，旋轉螢幕時不會被銷毀，第 8 題）。每次重組都該重算的結果，例如把數字組成字串，不需要 `remember`。

**範例。** 錯誤版：用了 State，卻漏掉 `remember`。

```kotlin
@Composable
fun SubmitCounterNoRemember() {
    // 少了 remember：每次重組都建立新的 State，值是 0
    var sent by mutableIntStateOf(0)

    Column {
        Text("已送出：$sent 筆")

        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

點「送出」，State 被寫入，Compose 也重組了。但重組時函式從頭再跑，又建立一個值是 0 的新 State，畫面永遠是「已送出：0 筆」。正確版是 `var sent by remember { mutableIntStateOf(0) }`（上一節的 `SubmitCounter`）。三種寫法並排：

| 寫法 | 點一下按鈕之後 | 原因 |
|---|---|---|
| 普通的 `var` | 畫面不動 | Compose 看不到這個變數，沒有人通知它重組 |
| State，但沒有 `remember` | 畫面不動，數字永遠是 0 | 有重組，但每次重組都建立新的 State，值回到 0 |
| `remember` 加 State | 畫面變成 1，再點變成 2 | 正確 |

**SwiftUI 的對照。** `remember` 和 `@State` 都靠「在樹上的位置」找回來，所以兩邊都有同一種現象：`if` 分支把 view 拿掉再放回來，裡面的狀態就重置。差別是 SwiftUI 的 `@State` 一寫上去就由框架保管，不可能忘記；Compose 要你自己寫 `remember`。換 `if` 分支、`key()` 的值變了（像 `.id()`），或 `LazyColumn` 的項目被回收，`remember` 的值也會重置。

```swift
struct QtyInput: View {
    @State private var qty = ""

    var body: some View {
        TextField("數量", text: $qty)
    }
}

struct DraftPanel: View {
    @State private var showForm = true

    var body: some View {
        VStack {
            Toggle("顯示表單", isOn: $showForm)

            if showForm {
                // 關掉再打開：QtyInput 是新的，qty 回到空字串
                QtyInput()
            }
        }
    }
}
```

```kotlin
@Composable
fun QtyInput() {
    var qty by remember { mutableStateOf("") }

    TextField(value = qty, onValueChange = { qty = it })
}

@Composable
fun DraftPanel() {
    var showForm by remember { mutableStateOf(true) }

    Column {
        Switch(checked = showForm, onCheckedChange = { showForm = it })

        if (showForm) {
            // 關掉再打開：QtyInput 是新的，qty 回到空字串
            QtyInput()
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「Composable 像 body，State 像 @State，但一定要 remember，否則重組就歸零。」

### 2. `mutableStateOf`：`by` 和 `.value` 差在哪？怎麼只重畫價格那一行？

**`mutableStateOf` 建立 Compose 盯著看的 State，只有「讀過它」的 Composable 會在它變時重組，所以重畫範圍取決於讀取發生在哪。** 這一題依序講四件事：`mutableStateOf` 本身、`by` 和 `.value` 兩種寫法、怎麼把讀取往下推，以及數字專用的 `mutableIntStateOf` 家族。

#### `mutableStateOf`

**這是什麼。** `mutableStateOf(初始值)` 建立一個 State，型別是 `MutableState<T>`，可以讀（`value`）也可以寫。Compose 把 State 分成兩個介面：`State<T>` 只能讀，`MutableState<T>` 可以讀也可以寫。慣例是對外只給唯讀的，只有擁有者能寫。

**使用場景。** 表單上任何「改了就要讓畫面跟著變」的值，例如買或賣、輸入框的文字。不要用在這幾種情況：數字又寫得很頻繁（用 `mutableIntStateOf` 家族，見下面）、一整份清單或對照表（第 3 題）、要撐過旋轉螢幕的輸入（`rememberSaveable`，第 5 題）、業務資料（放 ViewModel，第 8 題）。

**範例。** 買賣方向的切換。`remember` 讓它在重組時不歸零，點按鈕寫入新值，讀過 `side` 的 `Text` 就重組：

```kotlin
@Composable
fun SideSelector() {
    var side by remember { mutableStateOf("BUY") }

    Row {
        Button(onClick = { side = "BUY" }) {
            Text("買")
        }

        Button(onClick = { side = "SELL" }) {
            Text("賣")
        }

        // 在這裡讀取 side，Compose 記下「這裡依賴 side」
        Text("目前：$side")
    }
}
```

**SwiftUI 的對照。** 畫面自己用的值，對應 `@State`（第 1 題）。這裡要看的是「對外只給唯讀」：Compose 的 `private set` 對應 Swift 的 `private(set)`，`@Observable` 是 iOS 17 起的 Observation 框架。

```swift
@Observable
final class OrderFormModel {
    // 外面讀得到，只有這個 class 自己能寫
    private(set) var qty = ""

    // 外面只能透過函式改，函式裡可以順便檢查
    func onQtyChange(_ input: String) {
        qty = input.filter { $0.isNumber || $0 == "." }
    }
}
```

```kotlin
class OrderFormState {
    // 外面讀得到，只有這個 class 自己能寫
    var qty by mutableStateOf("")
        private set

    // 外面只能透過函式改，函式裡可以順便檢查
    fun onQtyChange(input: String) {
        qty = input.filter { it.isDigit() || it == '.' }
    }
}
```

#### `by` 和 `.value`

**這是什麼。** 同一個 State 的兩種讀寫寫法。`by` 是 Kotlin 的屬性委派（屬性的讀寫轉給別的物件處理），讓你像用普通變數一樣讀寫 State；不寫 `by`，就是拿到 State 本身，讀寫要用 `.value`（Long 版叫 `longValue`）。用 `by` 要 import `androidx.compose.runtime.getValue` 和 `setValue`（Android Studio 通常會幫你補）。

**使用場景。** 多數情況用 `by`，程式最短。需要把「State 本身」傳給別的函式時（例如下一節的「把讀取往下推」），才保留成 `val price = remember { … }`，用 `.value` 或 `longValue` 讀寫。兩種行為完全一樣，差別只在寫起來順不順：

| | 用 `by` | 不用 `by` |
|---|---|---|
| 宣告 | `var price by remember { mutableLongStateOf(0L) }` | `val price = remember { mutableLongStateOf(0L) }` |
| 讀 | `price` | `price.longValue`（一般 State 是 `.value`） |
| 寫 | `price = 1_000_123L` | `price.longValue = 1_000_123L` |
| 能不能把 State 本身傳下去 | 不能，只能傳值或 lambda | 能 |
| 要不要 import | 要 `getValue`、`setValue` | 不用 |

但要記得：**讀取發生在「用到變數的那一行」**，不是宣告的那一行；`by` 讓 State 看起來像普通變數，反而容易忘了「這一行其實在讀 State」。

**範例。** 同一個畫面裡兩種寫法並用：

```kotlin
@Composable
fun PriceBoard() {
    // 用 by：像普通變數一樣讀寫
    var openPrice by remember { mutableLongStateOf(0L) }

    // 不用 by：拿到 State 本身，讀寫用 longValue
    val last = remember { mutableLongStateOf(0L) }

    Column {
        Text("開盤：$openPrice")
        Text("最新：${last.longValue}")

        Button(onClick = {
            openPrice = 1_000_000L
            last.longValue = 1_000_123L
        }) {
            Text("更新")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 裡 `price` 是值（`wrappedValue`），`$price` 是可以傳下去的 `Binding`（`projectedValue`）。Compose 的 `by` 像直接用 `price`；不寫 `by` 時拿到的 State 本體，大致像 `$price`，但它是儲存本體，不是 get／set 包出來的 `Binding`，傳給子元件更常用 state hoisting（第 8 題）。

```swift
struct PriceBoard: View {
    @State private var last = 0

    var body: some View {
        VStack {
            // last 是值本身
            Text("最新：\(last)")

            // $last 是 Binding，可以傳下去讓子 view 讀寫
            PriceEditor(price: $last)
        }
    }
}
```

```kotlin
@Composable
fun PriceBoard() {
    val last = remember { mutableLongStateOf(0L) }

    Column {
        // last.longValue 是值本身
        Text("最新：${last.longValue}")

        // last 是 State 本身，可以傳下去（更常用 state hoisting）
        PriceEditor(price = last)
    }
}
```

#### 把讀取往下推

**這是什麼。** 這不是一個 API，是一種寫法：重組的範圍取決於「讀取 State 的那一行在哪個 Composable」，所以把 State 本身（或 lambda）往下傳，讀取留在最小的那個 Composable 裡，而不是在上層先讀成普通的值再傳下去。

**使用場景。** 即時價格每秒更新幾十次，這種每秒更新很多次的值，建議都這樣寫。不需要的情況：只在使用者按下按鈕才改一次的值（例如買賣方向），重組一次的成本可以忽略，不必為了它把程式碼拆得很碎。

**範例。** 先看沒有往下推的寫法：呼叫端讀 `priceState.value`，價格一變，呼叫端和 `OrderFormA` 整個函式都要重新執行。

```kotlin
@Composable
fun OrderScreenA(priceState: State<Long>) {
    // 讀取發生在這裡：價格一變，這個函式要重新執行
    OrderFormA(price = priceState.value)
}

@Composable
fun OrderFormA(price: Long) {
    Column {
        QtyField()
        Text("即時價格：$price")
    }
}
```

改成傳 State 本身，讀取留在最小的 `LivePrice`：

```kotlin
@Composable
fun OrderForm(price: State<Long>) {
    Column {
        // 沒有讀價格：價格變了，它不用重組
        QtyField()

        // 把 State 交給小函式，讀取留在那裡
        LivePrice(price)
    }
}

@Composable
fun LivePrice(price: State<Long>) {
    // 只在這裡讀取：價格變了，只有 LivePrice 重組
    Text("即時價格：${price.value}")
}
```

不傳 `State<Long>`，改傳 lambda（`price: () -> Long`，在 `LivePrice` 裡呼叫它）有同樣的效果。更進一步，在 `Modifier.offset { }` 這類接收 lambda 的 Modifier（串在元件後面的修飾清單，設定大小、位置、點擊等）裡讀 State，讀取會延後到 Layout 階段（Compose 每個畫面格依序做組合、Layout、繪製三個階段，Layout 負責決定位置與大小）才發生，State 變了連重組都不用，這叫延後讀取，Kotlin08 有完整講解。

**SwiftUI 的對照。** SwiftUI 你會把價格那一行拆成小 subview，讓只有它的 `body` 重算；Compose 同樣是拆小函式，差別是傳下去的東西。SwiftUI 傳整個 `@Observable` model，subview 的 `body` 讀它的屬性才會被追蹤；Compose 傳 State 或 lambda，讀取留在最小的 Composable。

```swift
@Observable
final class Quote {
    var price = 0
}

struct OrderForm: View {
    let quote: Quote

    var body: some View {
        VStack {
            QtyField()

            // 只傳 quote，不在這裡讀 quote.price
            LivePrice(quote: quote)
        }
    }
}

struct LivePrice: View {
    let quote: Quote

    var body: some View {
        // 只在這裡讀：price 變了，只有 LivePrice 的 body 重算
        Text("即時價格：\(quote.price)")
    }
}
```

```kotlin
@Composable
fun OrderForm(price: () -> Long) {
    Column {
        QtyField()

        // 只傳 lambda，不在這裡呼叫它
        LivePrice(price)
    }
}

@Composable
fun LivePrice(price: () -> Long) {
    // 只在這裡呼叫：讀取 State 發生在 LivePrice
    Text("即時價格：${price()}")
}
```

#### `mutableIntStateOf` 家族

**這是什麼。** `mutableIntStateOf`、`mutableLongStateOf`、`mutableFloatStateOf`、`mutableDoubleStateOf`，是專門存數字的 State（Compose runtime 1.5 起提供）。Kotlin 的泛型只能裝物件，`mutableStateOf(0L)` 每次寫入都要把 `Long` 包成物件，這叫 boxing（自動裝箱，多包一層物件、多耗記憶體）；primitive 版（直接存原始數字）省掉這一層。

**使用場景。** 會一直被改寫的數字：價格（以最小單位存成 `Long`）、未讀數、捲動位置。不用的話畫面照樣正常，只是每秒幾十次的寫入多出一堆短命物件，只有 lint（Android Studio 內建的程式碼檢查）的 `AutoboxingStateCreation` 會提醒。`Boolean` 沒有 primitive 版，因為包起來只有 `TRUE`、`FALSE` 兩個共用物件，不會新建物件。用了 primitive 版卻去讀 `.value`，一樣會 boxing，要讀 `longValue`，或直接用 `by`。

**範例。** 價格用 `Long` 版，未讀數用 `Int` 版：

```kotlin
@Composable
fun TickerLine() {
    var price by remember { mutableLongStateOf(0L) }
    var unread by remember { mutableIntStateOf(0) }

    Column {
        Text("價格：$price")
        Text("未讀：$unread")

        Button(onClick = {
            price = 1_000_123L
            unread++
        }) {
            Text("收到新行情")
        }
    }
}
```

**SwiftUI 的對照。** Swift 的 `Int` 是值型別，`@State private var price: Int` 直接存整數，不會 boxing，所以 SwiftUI 沒有這一組 API；Compose 這組 API 只是在補 Kotlin 泛型只能裝物件的限制。

```swift
struct TickerLine: View {
    @State private var price = 0
    @State private var unread = 0

    var body: some View {
        VStack {
            Text("價格：\(price)")
            Text("未讀：\(unread)")

            Button("收到新行情") {
                price = 1_000_123
                unread += 1
            }
        }
    }
}
```

```kotlin
@Composable
fun TickerLine() {
    // Long 版與 Int 版：直接存原始數字，不 boxing
    var price by remember { mutableLongStateOf(0L) }
    var unread by remember { mutableIntStateOf(0) }

    Column {
        Text("價格：$price")
        Text("未讀：$unread")
    }
}
```

> [!TIP]
> 面試時可以這樣說：「數字用 primitive 版避免 boxing；頻繁更新的 State 往下傳，在最小的 Composable 才讀。」

### 3. `mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？

**SwiftUI 的 `@State var orders: [Order]` 直接 `append` 就會重畫，因為陣列是值型別，任何修改都等於把整個新值寫回 `@State`。Compose 的 `List` 是參考，原地修改不會通知，所以要用 `mutableStateListOf`，或每次換成新的 list。** 下單表單送出之後，底下要多一筆委託，三種寫法行為不同：

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(…))` | 整個換掉：`orders = orders + order` | 值被換掉時 |
| `mutableStateOf(mutableListOf(…))` | 原地 `add` | 不會（lint 會警告） |
| `mutableStateListOf(…)` | `add`、`removeAt`、`set`、`clear` | 清單內容改變時 |

#### `mutableStateListOf`

**這是什麼。** 一個 Compose 追蹤的清單，型別是 `SnapshotStateList`（名字裡的 Snapshot 是 Compose 管理 State 讀寫的機制，第 6 題講）。對它呼叫 `add`、`removeAt`、`set`、`clear`，Compose 都會通知讀過這個清單的 Composable。

**使用場景。** 一筆一筆增減的清單：剛送出的委託、自選幣種。它有幾個要注意的地方。第一，它只認得「清單本身」的改變，不看項目裡面：項目是 `class OrderRow(var filled: Double)`，原地改 `filled`，清單沒變，畫面不會更新。項目要用 `data class` 加 `val`，更新時用 `copy` 換掉那一格（演練 1）。第二，一邊走訪一邊修改會丟 `ConcurrentModificationException`（走訪的同時改動集合會出的錯），要刪除符合條件的項目用 `removeAll { }`。第三，它預設存不進 `rememberSaveable`（第 5 題）。第四，如果每次都是整份換掉（例如每秒收到一份 20 檔的訂單簿快照），放一份新的不可變 list 比較單純。

**範例。** 送出一筆委託就 `add`；收到成交回報，換掉那一格；清掉已經全部成交的，用 `removeAll`：

```kotlin
data class OrderRow(val symbol: String, val filled: Double)

@Composable
fun OrderList() {
    // Compose 追蹤的清單；remember 讓它不會在重組時被清空
    val orders = remember { mutableStateListOf<OrderRow>() }

    Column {
        Button(onClick = { orders.add(OrderRow("BTC_JPY", 0.0)) }) {
            Text("送出")
        }

        Button(onClick = {
            if (orders.isNotEmpty()) {
                // 不要改欄位，換掉那一格
                orders[0] = orders[0].copy(filled = 1.0)
            }
        }) {
            Text("全部成交")
        }

        Button(onClick = { orders.removeAll { it.filled >= 1.0 } }) {
            Text("清掉已成交")
        }

        orders.forEach { row ->
            Text("${row.symbol} 已成交 ${row.filled}")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 的陣列是值型別，`append` 或改某一項的欄位，都等於把新陣列寫回 `@State`，所以會重畫。Compose 的 `List` 不是值型別，要靠 `mutableStateListOf` 通知；而 `data class` 加 `val` 加 `copy`，就是在模仿 struct 的值語意。注意項目：SwiftUI 裡 struct 項目的 `orders[1].filled = 0.5` 會重畫，因為修改經過陣列的 setter；項目是 class 就不會，Compose 的項目就屬於這一種。下面兩段都是「ETH_JPY 成交了 0.5，更新那一列」（演練 1）：

```swift
struct OrderRow: Identifiable {
    let id = UUID()
    let symbol: String
    var filled: Double
}

struct OrderListView: View {
    @State private var orders = [
        OrderRow(symbol: "BTC_JPY", filled: 0),
        OrderRow(symbol: "ETH_JPY", filled: 0),
    ]

    var body: some View {
        VStack {
            Button("ETH_JPY 成交 0.5") {
                // struct 是值型別：改一個欄位，等於寫回整個陣列
                orders[1].filled = 0.5
            }

            ForEach(orders) { row in
                Text("\(row.symbol) 已成交 \(row.filled)")
            }
        }
    }
}
```

```kotlin
@Composable
fun OrderListView() {
    val orders = remember {
        mutableStateListOf(
            OrderRow("BTC_JPY", 0.0),
            OrderRow("ETH_JPY", 0.0),
        )
    }

    Column {
        Button(onClick = {
            // data class 加 val：用 copy 換掉那一格
            orders[1] = orders[1].copy(filled = 0.5)
        }) {
            Text("ETH_JPY 成交 0.5")
        }

        orders.forEach { row ->
            Text("${row.symbol} 已成交 ${row.filled}")
        }
    }
}
```

#### `mutableStateMapOf`

**這是什麼。** `mutableStateListOf` 的對照表版本，型別是 `SnapshotStateMap`，規則相同：對它 `put`、用 `[]` 賦值、`remove`，Compose 都會通知讀過它的 Composable。已經有現成的集合，用 `toMutableStateList()`、`toMutableStateMap()`（一串 `Pair`）轉成追蹤版。

**使用場景。** 「交易對 → 最新價格」這種一筆一筆更新的對照表。和清單一樣，`mutableStateOf(mutableMapOf())` 之後原地 `put` 不會通知；對照表的值如果是可以原地修改的物件，也一樣要換掉整個值，不要改欄位。

**範例。** 收到某個交易對的報價就寫進對照表，還沒有資料就顯示 `--`：

```kotlin
@Composable
fun LatestPrices() {
    // 交易對 → 最新價格
    val latest = remember { mutableStateMapOf<String, Long>() }

    Column {
        Button(onClick = { latest["BTC_JPY"] = 1_000_123L }) {
            Text("收到 BTC_JPY 報價")
        }

        // 讀取：讀過這個 map 的 Composable 會在它變時重組
        Text("BTC_JPY：${latest["BTC_JPY"] ?: "--"}")
    }
}
```

**SwiftUI 的對照。** `@State private var latest: [String: Int]` 直接對應。和陣列一樣，字典是值型別，改任何一個 key 都等於寫回整個字典，所以 SwiftUI 不需要專用的 API。

```swift
struct LatestPrices: View {
    @State private var latest: [String: Int] = [:]

    private var btcText: String {
        latest["BTC_JPY"].map { String($0) } ?? "--"
    }

    var body: some View {
        VStack {
            Button("收到 BTC_JPY 報價") {
                latest["BTC_JPY"] = 1_000_123
            }

            Text("BTC_JPY：\(btcText)")
        }
    }
}
```

```kotlin
@Composable
fun LatestPrices() {
    val latest = remember { mutableStateMapOf<String, Long>() }
    val btcText = latest["BTC_JPY"]?.toString() ?: "--"

    Column {
        Button(onClick = { latest["BTC_JPY"] = 1_000_123L }) {
            Text("收到 BTC_JPY 報價")
        }

        Text("BTC_JPY：$btcText")
    }
}
```

#### `mutableStateOf(listOf(…))`：整個換掉

**這是什麼。** 這不是專用 API，而是用 `mutableStateOf` 存一份不可變的 list（`listOf`），更新時整個換成新的 list。`mutableStateOf` 追蹤的是「State 的值有沒有被換掉」，不是「值的內容有沒有變」，新 list 和舊 list 不相等，Compose 就知道了。

**使用場景。** 每次都是整份換掉的資料，例如每秒收到一份新的訂單簿快照；或清單很小、一次只增減一兩筆的情況。不要用的寫法是 `mutableStateOf(mutableListOf())` 再原地 `add`：State 的值還是同一個 list，Compose 不知道內容變了，畫面不更新（lint 的 `MutableCollectionMutableState` 會警告）。

**範例。** 每收到一份新的訂單簿快照，就整份換掉：

```kotlin
@Composable
fun BidsPanel() {
    var bids by remember { mutableStateOf(listOf<Long>()) }

    Column {
        Button(onClick = {
            // 整份換成新的 list，Compose 才知道有變
            bids = listOf(101L, 100L, 99L)
        }) {
            Text("收到訂單簿快照")
        }

        bids.forEach {
            Text("買價 $it")
        }
    }
}
```

錯誤寫法是這樣，畫面不會更新：

```kotlin
val bad = remember { mutableStateOf(mutableListOf<String>()) }

// State 的值還是同一個 list，Compose 不知道內容變了
bad.value.add("BTC_JPY 買 0.25")
```

**SwiftUI 的對照。** SwiftUI 的陣列可以直接 `append`，因為值型別的修改都等於寫回 `@State`。Compose 的 `listOf` 回傳的 list 不能改，要「建立新 list 再賦值」：`orders + x` 的效果和 Swift 的 `orders = orders + [x]` 一樣。

```swift
struct SubmittedList: View {
    @State private var orders: [String] = []

    var body: some View {
        VStack {
            Button("送出") {
                // append 原地改，值型別的 @State 照樣重畫
                orders.append("BTC_JPY 買 0.25")
            }

            ForEach(orders, id: \.self) { order in
                Text(order)
            }
        }
    }
}
```

```kotlin
@Composable
fun SubmittedList() {
    var orders by remember { mutableStateOf(listOf<String>()) }

    Column {
        Button(onClick = {
            // listOf 不可變：建立新 list 再賦值
            orders = orders + "BTC_JPY 買 0.25"
        }) {
            Text("送出")
        }

        orders.forEach {
            Text(it)
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「List 不是值型別，所以用 mutableStateListOf，項目用 data class 並 copy 換掉。」
### 4. `SnapshotMutationPolicy`（SwiftUI 沒有）什麼時候要換？

**Compose 在寫入 State 的當下先比較新舊值，「一樣」就把這次寫入當作沒發生，不重組；預設用 `equals` 比，想改變比法就在 `mutableStateOf` 的第二個參數傳一個 policy。** 這個「判斷新舊值算不算一樣的規則」，正式名稱叫 `SnapshotMutationPolicy`。SwiftUI 的 `@State` 沒有可調的比較規則，所以這一題的四個 API 都是 Compose 獨有的，下面每個小節各說一次原因。

| policy | 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b` | 字串、數字、data class，絕大多數情況 |
| `referentialEqualityPolicy()` | `a === b`（同一個物件才算） | `equals` 很貴，或不適合決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

#### `structuralEqualityPolicy()`（預設，不用寫）

**這是什麼。** 用 `equals`（Kotlin 的 `==`）比較新舊值，相等就把這次寫入丟掉，不重組。你平常寫 `mutableStateOf(x)`，用的就是它。

**使用場景。** 幾乎都用它：字串、數字、`data class`（`equals` 會比所有欄位）。它本身沒有問題，出問題的是 `equals` 寫得不適合畫面更新。例如領域物件 `Order` 的 `equals` 只比 `id`，訂單 A1 從 New 變 Filled，新舊「相等」，這次寫入被丟掉，畫面停在 New，連 State 裡存的都還是舊物件（演練 2）。

**範例。** 內容完全一樣的新物件，`equals` 為 `true`，不重組：

```kotlin
data class Tick(val symbol: String, val price: Long, val receivedAt: Long)

@Composable
fun TickView() {
    // 預設 policy：equals 相同就算沒變
    var tick by remember {
        mutableStateOf(Tick("BTC_JPY", 100, receivedAt = 1))
    }

    Column {
        Text("${tick.symbol} ${tick.price}")

        Button(onClick = {
            // 內容完全一樣：這次寫入被丟掉，不重組
            tick = Tick("BTC_JPY", 100, receivedAt = 1)
        }) {
            Text("寫入一樣的 Tick")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 沒有對應的概念，因為 `@State` 被賦予新值時，由 SwiftUI 自己比對新舊值，比對的規則是框架決定的，開發者不能調整。

#### `referentialEqualityPolicy()`

**這是什麼。** 用 `===` 比較：必須是同一個物件才算一樣，換成另一個物件就算變了。

**使用場景。** `equals` 只比 id（Room 是 Android 的本機資料庫函式庫，它的 entity 和領域物件常見這樣寫），或 `equals` 很貴（比幾千筆的 list）的時候。不這樣寫就是上面演練 2 的症狀。更好的做法常是讓 UI 用的 model 是 `data class`，`equals` 會比所有欄位，不必動 policy。

**範例。** `Order` 的 `equals` 只比 `id`，所以要用 `===` 比：

```kotlin
class Order(val id: String, val status: String) {
    // 只比 id
    override fun equals(other: Any?) = other is Order && other.id == id

    override fun hashCode() = id.hashCode()
}

@Composable
fun OrderStatus() {
    var order by remember {
        mutableStateOf(Order("A1", "New"), referentialEqualityPolicy())
    }

    Column {
        Text("訂單 ${order.id}：${order.status}")

        Button(onClick = {
            // 另一個物件：用 === 比，算變了，畫面更新
            order = Order("A1", "Filled")
        }) {
            Text("成交")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 沒有對應的概念，因為 Swift 的 struct 是值型別，沒有「同一個物件」可以比（`===` 只用在 class）；用 class 當 model 時，`@Observable` 追蹤的是屬性有沒有被寫入，也不看物件是不是同一個。

#### `neverEqualPolicy()`

**這是什麼。** 任何一次寫入都算變了，連同一個物件寫進去也會通知。

**使用場景。** 訂單簿引擎為了效率，內部就地更新、整個 App 共用一個物件：更新之後再寫一次同一個物件，通知畫面（演練 3）。不用的話，同一個物件 `equals` 當然相等，這次寫入被當成沒變，最佳買價停在舊的。代價是每次寫入都重組，連內容沒變的寫入也一樣。

它也**不是**「再顯示一次相同的錯誤訊息」的工具。它確實會讓讀這個 State 的 Composable 重組，但 `LaunchedEffect(message)` 比較 key 用的還是 `equals`，相同的字串不會重新啟動，Toast 一樣不會再出現。一次性事件請包成帶 id 的物件（`data class UiMessage(val id: Long, val text: String)`），或照 Kotlin05 第 3 題的做法。

**範例。** 引擎就地更新，再把同一個物件寫回去：

```kotlin
@Composable
fun BestBid(book: OrderBook) {
    // 永遠算變了：同一個物件寫進去也會通知
    val state = remember { mutableStateOf(book, neverEqualPolicy()) }

    Column {
        Text("最佳買價：${state.value.bestBid()}")

        Button(onClick = {
            // 引擎就地更新內容
            book.update(101, 1.5)

            // 同一個物件寫回去，照樣通知
            state.value = book
        }) {
            Text("收到深度更新")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 沒有對應的概念，因為 `@Observable` 的 model 是「屬性一被寫入就通知」，就地修改本來就會通知，不需要「再把同一個物件寫回去」這個動作。

#### 自訂 `SnapshotMutationPolicy`

**這是什麼。** 自己實作 `equivalent(a, b)`，決定「什麼叫一樣」。

**使用場景。** 行情每筆都帶接收時間，但價格沒變就不該重組，所以只比 symbol 和 price。不自訂的話，預設 policy 下 `receivedAt` 每筆都不同，每一筆行情都重組，連價格沒變也一樣。另外，`derivedStateOf`（第 6 題）的結果預設也是用 `equals` 判斷，同樣可以傳 policy：`derivedStateOf(referentialEqualityPolicy()) { … }`。

**範例。** 接收時間不同、價格相同的寫入，不重組：

```kotlin
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    // 只比畫面看得到的欄位，不比接收時間
    override fun equivalent(a: Tick, b: Tick): Boolean {
        return a.symbol == b.symbol && a.price == b.price
    }
}

@Composable
fun TickLine() {
    var tick by remember {
        mutableStateOf(Tick("BTC_JPY", 100, 1), tickPolicy)
    }

    Column {
        Text("${tick.symbol} ${tick.price}")

        Button(onClick = {
            // 價格一樣、接收時間不同：算一樣，不重組
            tick = Tick("BTC_JPY", 100, 2)
        }) {
            Text("收到同價格的行情")
        }
    }
}
```

**SwiftUI 的對照。** SwiftUI 沒有對應的概念，因為它不開放 `@State` 的比較規則。想控制畫面要不要重算，靠的是把 view 拆小，或讓 view 遵守 `Equatable` 並加 `.equatable()`（大致），比的是 view 的輸入，不是寫進 State 的值。

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會被丟掉。」

### 5. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**`remember` 對應 `@State`，只活過重組；`rememberSaveable` 大致對應 `@SceneStorage`，還撐得過旋轉螢幕和行程被系統殺掉，只放小量資料；業務資料放 ViewModel。** `rememberUpdatedState` 是另一件事：讓長時間運作的 effect 讀到最新的值。

Android 預設旋轉螢幕時會銷毀並重建 Activity（Android 的畫面容器），SwiftUI 的 view 不會，所以 `remember` 的值會消失；ViewModel（放畫面資料的物件，大致像 `@StateObject` 持有的 model）則活著。**行程被殺**（process death）是 App 在背景時被系統整個回收，在 Android 比較常遇到。三種放法活多久：

| 事件 | `remember` | `rememberSaveable` | ViewModel |
|---|---|---|---|
| 重組 | 還在 | 還在 | 還在 |
| 旋轉螢幕 | 消失 | 還在 | 還在 |
| 行程被殺後重開 | 消失 | 還在 | 消失（要搭配 `SavedStateHandle`） |

#### `remember(key)`

**這是什麼。** 第 1 題講過 `remember { … }` 只算一次。括號裡可以放 key：key 是「會影響計算結果的輸入」，和上次不一樣，就丟掉存起來的值、重新計算。

**使用場景。** 計算結果取決於某個參數，而且算起來不便宜的時候。例如價格格式化器，小數位數取決於交易對的 tick size（價格的最小跳動單位）。沒寫 key，切換交易對之後格式還停在上一個（Kotlin06 演練 6）；沒有 `remember`，每次重組都重建格式化器。不要把「一直在變」的值當 key，否則每次重組都重算，等於沒有 `remember`。

**範例。**

```kotlin
@Composable
fun PriceText(price: Long, tickSize: Double) {
    // tickSize 是 key：換交易對、tickSize 變了就重新建立
    val formatter = remember(tickSize) { PriceFormatter(tickSize) }

    Text(formatter.format(price))
}
```

**SwiftUI 的對照。** SwiftUI 沒有內建的「依輸入快取」。`@State` 的初始值只在 view 第一次出現時採用，之後參數變了，`@State` 還是舊的，所以要自己用 `.onChange(of:)` 去更新；或用 `.id()` 讓整個 view 換身分、全部的 `@State` 重建（那會連其他狀態一起重置，不像 `remember(key)` 只換一個值）。

```swift
struct PriceText: View {
    let price: Int
    let tickSize: Double
    @State private var formatter: PriceFormatter

    init(price: Int, tickSize: Double) {
        self.price = price
        self.tickSize = tickSize
        _formatter = State(initialValue: PriceFormatter(tickSize: tickSize))
    }

    var body: some View {
        Text(formatter.format(price))
            // tickSize 變了，自己重建格式化器（iOS 17 的寫法）
            .onChange(of: tickSize) { _, newValue in
                formatter = PriceFormatter(tickSize: newValue)
            }
    }
}
```

```kotlin
@Composable
fun PriceText(price: Long, tickSize: Double) {
    // key 變了，Compose 自動重建，不用自己寫 onChange
    val formatter = remember(tickSize) { PriceFormatter(tickSize) }

    Text(formatter.format(price))
}
```

#### `rememberSaveable`

**這是什麼。** 寫法和 `remember` 一樣，但值還會存進 Bundle（Android 暫存畫面狀態的 key-value 容器），所以旋轉螢幕、行程被殺都撐得過。

**使用場景。** 使用者輸入到一半的欄位（數量、價格）、選的分頁。只放小量資料，大型資料放資料庫或 repository（負責取得與保存資料的類別）；業務資料放 ViewModel。用 `remember` 的話，輸入 `0.25` 後旋轉，輸入框會清空，要重打（Kotlin06 演練 5）。要實際測試行程被殺：App 退到背景，執行 `adb shell am kill <套件名稱>`，再從最近使用的 App 點回來。

**範例。** 數量和價格都是輸入到一半的內容。價格多放一個 key：`rememberSaveable` 也可以像 `remember` 一樣放 key，任何一個變了，之前存的值就丟掉、重新初始化：

```kotlin
@Composable
fun OrderInputs(symbol: String) {
    // 和 remember 寫法一樣；值會存進 Bundle，旋轉後還原
    var qty by rememberSaveable { mutableStateOf("") }

    // symbol 當 key：切換交易對，之前輸入的價格就清空
    var price by rememberSaveable(symbol) { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = qty,
            onValueChange = { qty = it },
            label = { Text("數量") },
        )

        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            label = { Text("價格") },
        )
    }
}
```

**SwiftUI 的對照。** 大致對應 `@SceneStorage`，用來保存輸入到一半的內容。差別在背景：iOS 的 view 不會因為旋轉而重建，`@State` 平常就夠用，`@SceneStorage` 主要用在 App 被系統收掉之後還原畫面；Android 旋轉就會重建，所以輸入框幾乎都要用 `rememberSaveable`。

```swift
struct QtyField: View {
    @SceneStorage("qty") private var qty = ""

    var body: some View {
        TextField("數量", text: $qty)
    }
}
```

```kotlin
@Composable
fun QtyField() {
    var qty by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = qty,
        onValueChange = { qty = it },
        label = { Text("數量") },
    )
}
```

#### `Saver`（`listSaver`、`mapSaver`）

**這是什麼。** Bundle 只收基本型別、`String`，以及實作了 `Parcelable`（Android 的序列化介面，把物件拆成可以放進 Bundle 的資料，通常用 `@Parcelize` 自動產生）或 `Serializable`（Java 的序列化介面）的型別。下單草稿 `OrderDraft(symbol, qty)` 這種一般 data class，還有 `SnapshotStateList`、lambda 都存不了，組合畫面的當下就丟 `IllegalArgumentException`（訊息含「cannot be saved using the current SaveableStateRegistry」），手機上是一打開就閃退（演練 4）。`Saver` 告訴 Compose 兩件事：`save` 怎麼把物件拆成存得了的東西，`restore` 怎麼組回來。

**使用場景。** 自訂型別要放進 `rememberSaveable` 時。選哪一種：

| 做法 | 適合 | 缺點 |
|---|---|---|
| `@Parcelize` 加 `Parcelable` | 這個型別本來就要放進 Intent（Android 啟動畫面時附帶資料的訊息物件）或 Bundle 傳遞 | 要套用 Parcelize plugin |
| `listSaver` | 欄位少而且固定 | 用位置對應（`it[0]`、`it[1]`），加欄位或換順序容易對錯 |
| `mapSaver` | 欄位多，之後可能增加 | 用 key 對應，漏存的欄位不會報錯（演練 5） |
| `Saver(save, restore)` | 單一值的型別，例如把金額存成字串 | 自己寫 |

最隱蔽的坑是：後來為 `OrderDraft` 加了新欄位，卻忘了改 Saver，`restore` 用預設值補，編譯器不會提醒，旋轉之後那個欄位靜悄悄地歸零（演練 5）。所以要幫 Saver 寫一個「存起來再還原，每個欄位都一樣」的單元測試。ViewModel 要撐過行程被殺，用 `SavedStateHandle`（ViewModel 裡會跟著還原的 key-value 存放處）：`savedStateHandle.getStateFlow("qty", "")` 讀、`savedStateHandle["qty"] = value` 寫；它和 `rememberSaveable` 一樣放進 Bundle，所以同樣只適合小量資料。

**範例。** `listSaver` 把草稿拆成 `List`，放進 `rememberSaveable`：

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

val draftSaver = listSaver<OrderDraft, Any>(
    // 拆成存得了的 List，用位置對應
    save = { listOf(it.symbol, it.qty) },
    // 組回物件，位置要和 save 對上
    restore = { OrderDraft(it[0] as String, it[1] as Double) },
)

@Composable
fun DraftForm() {
    // MutableState 的參數叫 stateSaver
    var draft by rememberSaveable(stateSaver = draftSaver) {
        mutableStateOf(OrderDraft("BTC_JPY", 0.1))
    }

    Text("${draft.symbol} ${draft.qty}")
}
```

欄位多、之後會增加，改用 `mapSaver`，用 key 對應。單一值的型別用 `Saver(save, restore)`，例如把金額 `BigDecimal` 存成字串。放 `MutableState` 的參數叫 `stateSaver`；放一般物件（不是 `MutableState`）的版本，參數叫 `saver`：

```kotlin
val draftMapSaver = mapSaver(
    // 用 key 對應，加欄位時比較不容易對錯
    save = { mapOf("symbol" to it.symbol, "qty" to it.qty) },
    restore = {
        OrderDraft(it["symbol"] as String, it["qty"] as Double)
    },
)

// 用法和 draftSaver 一樣：
// rememberSaveable(stateSaver = draftMapSaver) { … }
```

**SwiftUI 的對照。** SwiftUI 沒有獨立的 Saver 型別。`@SceneStorage` 只收基本型別（`Bool`、`Int`、`Double`、`String`、`URL`、`Data`）和 `RawRepresentable`；自訂型別通常是拆成基本型別各存一個，或讓型別遵守 `RawRepresentable` 編成 `String`。Compose 把這個「拆開與組回」獨立成 `Saver`，好處是拆組的邏輯集中在一個地方，能單獨寫測試。

```swift
struct OrderDraftView: View {
    // SceneStorage 只收基本型別：草稿拆成兩個欄位各存各的
    @SceneStorage("draft.symbol") private var symbol = "BTC_JPY"
    @SceneStorage("draft.qty") private var qty = 0.1

    var body: some View {
        VStack {
            TextField("交易對", text: $symbol)
            TextField("數量", value: $qty, format: .number)
        }
    }
}
```

```kotlin
@Composable
fun OrderDraftView() {
    // 整個草稿是一個值；拆與組的規則集中在 draftSaver
    var draft by rememberSaveable(stateSaver = draftSaver) {
        mutableStateOf(OrderDraft("BTC_JPY", 0.1))
    }

    Column {
        TextField(
            value = draft.symbol,
            onValueChange = { draft = draft.copy(symbol = it) },
        )
        TextField(
            value = draft.qty.toString(),
            onValueChange = {
                draft = draft.copy(qty = it.toDoubleOrNull() ?: 0.0)
            },
        )
    }
}
```

#### `rememberUpdatedState`

**這是什麼。** 把「最新傳進來的值」包成 State，讓長時間運作的 effect 在需要的那一刻讀到最新的值，又不用重啟。`LaunchedEffect(key)` 是在 Composable 裡啟動 coroutine（Kotlin 的輕量非同步任務，約等於 Swift 的 `Task`）的入口，key 變了就取消重啟（Kotlin07 第 2 題）。

**使用場景。** 送出訂單之後，開始 10 秒的逾時倒數，時間到要呼叫 `onTimeout`。倒數期間外面可能換了新版的 `onTimeout`，倒數卻不能因此重來。直接呼叫 `onTimeout`，effect 抓著的是第一次進來的舊版；把 `onTimeout` 放進 `LaunchedEffect` 的 key，拿得到新的，但每次 callback 換了 effect 就取消重啟，10 秒重新計（Kotlin07 演練 5）。

**範例。**

```kotlin
@Composable
fun SubmitTimeout(onTimeout: () -> Unit) {
    // 每次重組都更新成最新傳進來的 callback
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    // key 是 Unit：只在進入畫面時啟動一次，重組不重啟
    LaunchedEffect(Unit) {
        delay(10_000)

        // 時間到才讀，拿到的是最新的 callback
        currentOnTimeout()
    }
}
```

**SwiftUI 的對照。** 大致對應「`.task` 讀 model 的屬性，而不是讀啟動時抓到的 view 屬性」。SwiftUI 的 `.task` 啟動時捕捉的是當時那份 view 值，之後傳進來的新 closure 它看不到；常見做法是讓 callback 住在一個 class（參考型別）裡，task 在需要的那一刻才去讀。`rememberUpdatedState` 大致就是把這件事包成一個 API。

```swift
@Observable
final class SubmitModel {
    // 外面隨時可以換成新的 callback
    var onTimeout: () -> Void = {}
}

struct SubmitTimeout: View {
    let model: SubmitModel

    var body: some View {
        Color.clear
            .task {
                try? await Task.sleep(for: .seconds(10))

                // 讀 model 的屬性，不是啟動時抓到的值
                model.onTimeout()
            }
    }
}
```

```kotlin
@Composable
fun SubmitTimeout(onTimeout: () -> Unit) {
    // 包成 State：callback 換了，下面讀到的就是新的
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    LaunchedEffect(Unit) {
        delay(10_000)
        currentOnTimeout()
    }
}
```

> [!TIP]
> 面試時可以這樣說：「rememberSaveable 像 @SceneStorage，自訂 class 要給 Saver，新增欄位要同步改。」
### 6. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**`derivedStateOf` 是 State 變成另一個 State，用在「輸入常變、結果少變」；`snapshotFlow` 是 State 變成 Flow，大致對應 `.onChange(of:)`；`produceState` 是 suspend 函式或 callback 變成 State，大致對應 `.task { state = await … }`。** 最後的 Snapshot 是前面三個共同的基礎。

#### `derivedStateOf`

**這是什麼。** 用一個計算式，從別的 State 算出新的 State；只有算出來的「結果」真的變了，才通知讀它的 Composable。

**使用場景。** 即時價格每秒變幾十次，但紅字只在「超過警示線」和「回到警示線以下」這兩個狀態換邊時才需要變。直接寫 `val over = price.value > alertLine` 的話，Composable 自己讀了 `price.value`，每次價格變就重組一次，儘管 `over` 根本沒換邊。`derivedStateOf` 把價格藏在計算式裡，Composable 讀的是結果，只在結果換邊時才重組。

`derivedStateOf`、`remember(key)`、直接算，差在哪：

| | 直接算 | `remember(key) { 計算 }` | `remember { derivedStateOf { 計算 } }` |
|---|---|---|---|
| 什麼時候重算 | 每次這個 Composable 重組 | 組合時發現 key 和上次不一樣 | 計算式裡讀過的 State 變了 |
| 追蹤什麼 | 函式裡讀到的 State | 你放進 key 的一般參數 | **State 的讀取**，一般參數它看不到 |
| 這個 Composable 什麼時候重組 | 輸入每變一次就重組一次 | 沒有通知，重算只發生在重組時 | 結果真的變了，才重組 |
| 適合 | 輸入不常變，或結果本來就跟著輸入變 | 參數變了要重算（排序、篩選、格式化） | State 變很頻繁、結果很少變 |

口訣是：**一般參數放 `remember` 的括號（key），State 放進 `derivedStateOf` 的大括號裡讀。** 常見誤用有三個。第一，漏了 `remember`，每次重組都建立新的 derived state，快取等於沒有。第二，結果跟著輸入一起變（`derivedStateOf { price * qty }`），沒省到任何通知，反而多了一層。第三，依賴一般參數卻沒當 key，一直用第一次的 `alertLine`，使用者把警示線調高了，畫面還是顯示「超過」（演練 6）。

**範例。** `price` 是 State，`alertLine` 是一般參數：

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    // alertLine 是一般參數，要當 remember 的 key
    val over by remember(alertLine) {
        // price 是 State：它變就重算，但只有 true／false 換邊才通知
        derivedStateOf { price.value > alertLine }
    }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

**SwiftUI 的對照。** SwiftUI 沒有直接對應的 API：你會寫計算屬性，每次 `body` 重算就算一次，沒有「只在結果換邊才通知」的機制。Compose 的 `derivedStateOf` 是要你明講這件事。如果警示線也是 State，計算式裡直接讀它，就不需要 key。

```swift
struct PriceAlert: View {
    let quote: Quote
    let alertLine: Int

    // 計算屬性：每次 body 重算就算一次
    private var isOver: Bool {
        quote.price > alertLine
    }

    var body: some View {
        if isOver {
            Text("價格超過警示線")
                .foregroundStyle(.red)
        }
    }
}
```

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: State<Long>) {
    // 兩個都是 State，都在計算式裡讀，不需要 key
    val over by remember {
        derivedStateOf { price.value > alertLine.value }
    }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

#### `snapshotFlow`

**這是什麼。** 在大括號裡讀 State，回傳一個 Flow（Kotlin 的非同步資料串流，像 `AsyncSequence`，第 7 題細講）。開始收集時先送出目前的值，之後裡面讀過的 State 變了，就重新計算並送出新值。連續相同的值會去重（初始值 0，依序寫入 1、1、2、2，只會收到 0、1、2），來不及處理的中間值會被合併。

**使用場景。** 想在 State 改變時「做一件事」，而不是「畫出東西」：選的交易對變了，回報給分析（analytics）；數量改了，存下草稿。不要用的情況：只是要在畫面上顯示 State，直接讀它就好。還有兩個錯誤：先 `val s = symbol` 再 `snapshotFlow { s }`，lambda 裡沒有讀 State，永遠只有第一個值；在沒有 Compose 在跑的環境（單元測試），後續的變化收不到（見 Snapshot 與演練 8），ViewModel 要給非 Compose 的程式觀察，改用 `StateFlow`（第 8 題）。

**範例。**

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

    Row {
        Button(onClick = { symbol = "BTC_JPY" }) {
            Text("BTC_JPY")
        }

        Button(onClick = { symbol = "ETH_JPY" }) {
            Text("ETH_JPY")
        }
    }
}
```

**SwiftUI 的對照。** 大致對應 `.onChange(of:initial:)`（`initial` 參數 iOS 17 起有）。差別在第一次：`snapshotFlow` 開始收集時一定先送出目前的值，像 `initial: true`；SwiftUI 的 `.onChange` 預設不會在第一次執行。想要「只在變動時才做」，Compose 要用 `drop(1)` 跳過第一筆。

```swift
struct QtyDraftField: View {
    let store: DraftStore
    @State private var qty = ""

    var body: some View {
        TextField("數量", text: $qty)
            // initial 預設是 false：第一次不執行，只在變動時執行
            .onChange(of: qty) { _, newValue in
                store.save(qty: newValue)
            }
    }
}
```

```kotlin
@Composable
fun QtyDraftField(store: DraftStore) {
    var qty by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // snapshotFlow 一開始就送出目前的值，drop(1) 跳過它
        snapshotFlow { qty }
            .drop(1)
            .collect { store.save(it) }
    }

    TextField(value = qty, onValueChange = { qty = it })
}
```

#### `produceState`

**這是什麼。** 把 suspend 函式（Kotlin 的 `async` 函式）或 callback 的結果，變成 State。它是回傳 State 的 Composable：結果回來前，State 的值是你給的 `initialValue`；key 換了就取消重跑，離開畫面也會取消。

**使用場景。** 表單要顯示伺服器時間（一次性的 suspend 載入），或價格來自 callback 風格的 socket（用 listener 收資料的舊式 API）。callback 要用 `awaitDispose` 在離開畫面時解除，否則 listener 一直掛在 socket 上（Kotlin07 演練 6）。不要用在來源是 Flow 的情況：`produceState(initial) { flow.collect { value = it } }` 正是 `collectAsState` 內部做的事，直接用第 7 題的 `collectAsStateWithLifecycle`。它的 key 規則和 `LaunchedEffect` 完全相同（Kotlin07 演練 2）。

**範例。** suspend 函式與 callback 各一個：

```kotlin
@Composable
fun rememberServerTime(client: TimeClient): State<Long?> {
    // 結果回來前是 null；client 換了就重新執行
    return produceState<Long?>(initialValue = null, client) {
        // suspend 函式，結果寫進 value
        value = client.fetchServerTime()
    }
}

@Composable
fun rememberLastPrice(socket: PriceSocket): State<Long> {
    return produceState(initialValue = 0L, socket) {
        // callback 風格：收到價格就寫進 value
        val listener = PriceSocket.Listener { value = it }
        socket.addListener(listener)

        // 離開畫面時解除，否則 listener 一直掛著
        awaitDispose { socket.removeListener(listener) }
    }
}
```

**SwiftUI 的對照。** 大致對應 `.task { state = await … }`：在 `.task` 裡 `await`，結果寫進 `@State`。`produceState` 把這個模式包成一個回傳 State 的 Composable，不用自己宣告 State 再寫進去。

```swift
struct ServerClock: View {
    let client: TimeClient
    @State private var serverTime: Int?

    private var label: String {
        serverTime.map { "伺服器時間：\($0)" } ?? "--"
    }

    var body: some View {
        Text(label)
            // view 出現時啟動，消失時取消
            .task {
                serverTime = await client.fetchServerTime()
            }
    }
}
```

```kotlin
@Composable
fun ServerClock(client: TimeClient) {
    // 結果回來前是 null；離開畫面時自動取消
    val serverTime by produceState<Long?>(null, client) {
        value = client.fetchServerTime()
    }

    Text(serverTime?.let { "伺服器時間：$it" } ?: "--")
}
```

#### Snapshot

**這是什麼。** Snapshot 是 Compose 管理 State 讀寫的機制，可以想成一本記錄本，有三個概念。**讀取追蹤**：Composable、`derivedStateOf`、`snapshotFlow` 執行時，Compose 記下它們讀了哪些 State，那些 State 被寫入才去通知它們，這就是為什麼「沒有在 lambda 裡讀 State」就收不到變化。**寫入要「套用並通知」，別人才看得到**：有 Compose 畫面在跑時，Compose 會自動做；沒有 Compose 的環境（單元測試）沒有人做，`snapshotFlow` 就收不到後續變化，要自己呼叫 `Snapshot.sendApplyNotifications()`（演練 8；本機實測：呼叫之前只收到第一筆，呼叫之後收到最後一筆）。**同時寫多個 State 要一起生效**：`Snapshot.withMutableSnapshot { … }`，離開區塊時一次套用。

**使用場景。** 平常寫畫面不會直接碰它，會碰到的有三種情況：在單元測試裡驗證 `snapshotFlow`；多個 State 要一起生效，別人不能看到只改了一半的狀態；理解為什麼 State 要在 lambda 裡讀。不要在業務邏輯裡自己管理 Snapshot，那是 Compose runtime 的工作。`SideEffect` 也和這個機制有關：它在這次組合成功並套用到畫面之後才執行，做到一半被放棄的組合不會執行（Kotlin07 第 4 題）。

**範例。** 重設表單，兩個欄位要一起生效：

```kotlin
class OrderFormState {
    var symbol by mutableStateOf("BTC_JPY")
    var qty by mutableStateOf("")

    fun reset() {
        // 離開區塊時兩個欄位一起生效，別人看不到只改一半
        Snapshot.withMutableSnapshot {
            symbol = "BTC_JPY"
            qty = ""
        }
    }
}
```

**SwiftUI 的對照。** 「讀取追蹤」大致對應 iOS 17 的 Observation：`@Observable` 讓 `body` 只依賴它讀過的屬性。「寫入要套用並通知」SwiftUI 沒有對應的概念，因為 `@Observable` 的屬性一被寫入就通知觀察者（大致），沒有另外一步「套用」；`withMutableSnapshot` 這種「一組寫入一起生效」的 API 也沒有，SwiftUI 在同一個事件裡改多個 `@State`，大致會合併成一次更新。

```swift
@Observable
final class Quote {
    var price = 0
    var volume = 0
}

struct PriceLine: View {
    let quote: Quote

    var body: some View {
        // body 只讀了 price：price 變才重算，volume 變不會
        Text("價格：\(quote.price)")
    }
}
```

```kotlin
class Quote {
    var price by mutableLongStateOf(0L)
    var volume by mutableLongStateOf(0L)
}

@Composable
fun PriceLine(quote: Quote) {
    // 只讀了 price：price 變才重組，volume 變不會
    Text("價格：${quote.price}")
}
```

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤 State 的讀取，輸入頻繁、輸出少變時用，要包 remember。」
### 7. 價格 Flow 退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 不管 lifecycle，畫面只要還在 composition（Compose 目前建好的畫面樹）裡就一直收。** SwiftUI 裡你直接讀 `@Observable` ViewModel 的屬性，或用 `.onReceive(publisher)`；`.task` 只在 view 消失時取消，退到背景 task 照跑，要自己看 `scenePhase`。Compose 要明講「把 Flow 轉成 State」，`collectAsStateWithLifecycle` 把 lifecycle 的判斷內建了。

先把名詞講清楚。**Flow** 像 `AsyncSequence` 或 Combine 的 publisher；**cold Flow** 是有人收才開始跑、每個收集者各跑一份（例如每次收集就重連 WebSocket）。**`StateFlow`** 永遠保存最新值，新的收集者馬上拿到，大致對應 `CurrentValueSubject`；**`SharedFlow`** 沒有目前值，常廣播一次性事件，大致對應 `PassthroughSubject`。**lifecycle** 是 Android 畫面從建立到銷毀的各階段，**`STARTED`** 是「畫面看得到」，退到背景就回到 `STARTED` 以下。兩個 API 並排：

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 何時收集 | 進入 composition 就收，離開才停 | lifecycle 至少到 `STARTED`（`minActiveState` 預設值）才收 |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集，回前景重新收集 |
| 來自哪個套件 | Compose 本體 | `lifecycle-runtime-compose`，要自己加依賴 |

#### `collectAsState()`

**這是什麼。** 把 Flow 變成 State，讓 Composable 讀得到最新值。它只管 composition：進入畫面樹就開始收，離開才停，不管 Android 的 lifecycle。

**使用場景。** 不是 Android 的環境（例如 Compose Multiplatform，把 Compose 用到桌面或 iOS 上的版本），或這個畫面刻意要在背景也持續更新（很少見）。Android 的行情、訂單、餘額畫面不要用它：退到背景後仍然持續收集、持續重組，耗電也耗流量。本機實測（演練 7，伺服器每 50 ms 推一筆價格）：App 退到背景 0.5 秒，畫面又更新了 9 次。

**範例。** `StateFlow` 有目前值，不用給 `initial`：

```kotlin
@Composable
fun LivePriceLine(prices: StateFlow<Long>) {
    // 進入 composition 就收，退到背景也照收
    val price by prices.collectAsState()

    Text("即時價格：$price")
}
```

**SwiftUI 的對照。** 大致對應在 `.task` 裡 `for await`：view 在畫面上就一直收，退到背景 task 也不會自己取消。SwiftUI 的 `@State` 要自己宣告，Compose 的 `collectAsState` 直接回傳 State。

```swift
struct LivePriceLine: View {
    let prices: AsyncStream<Int>
    @State private var price: Int?

    var body: some View {
        Text(price.map { "即時價格：\($0)" } ?? "--")
            // view 消失才取消；退到背景 task 照跑
            .task {
                for await value in prices {
                    price = value
                }
            }
    }
}
```

```kotlin
@Composable
fun LivePriceLine(prices: Flow<Long>) {
    // 一般 Flow 沒有目前值，一定要給 initial
    val price by prices.collectAsState(initial = null)

    Text(price?.let { "即時價格：$it" } ?: "--")
}
```

#### `collectAsStateWithLifecycle()`

**這是什麼。** 把 Flow 變成 State，而且跟著 Android lifecycle 開始與停止收集：畫面至少到 `STARTED`（看得到）才收，退到背景就取消，回到前景再重新收集。`minActiveState` 預設是 `STARTED`；改成 `RESUMED` 的話，被別的視窗搶走焦點、Activity 進入 paused 時也會停。

**使用場景。** Android 畫面上要顯示 Flow 的資料，預設都用它。本機實測（同演練 7）：退到背景 0.5 秒，畫面又更新了 0 次，回到前景後馬上顯示最新的價格。收的 Flow 要分三種：

| 來源 | 怎麼收 | initial 怎麼給 |
|---|---|---|
| `StateFlow<T>` | `collectAsStateWithLifecycle()` | 不用給，`StateFlow` 一定有目前值 |
| 一般 `Flow<T>`（cold） | `collectAsStateWithLifecycle(initialValue)` | 一定要給：第一個值來之前，畫面要顯示什麼 |
| `SharedFlow<T>`（事件） | 通常不轉成 State，用 `LaunchedEffect` 收 | State 只留最新值，快速連發的事件會漏 |

cold Flow 要小心：每次回到前景都會重新收集，上游從頭再跑（例如重開 WebSocket）。所以通常先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，並設 `WhileSubscribed(5000)`：沒有人收集之後再等 5 秒才停掉上游，撐過旋轉螢幕那一瞬間「收集者消失又出現」（Kotlin05 第 5 題）。`initial` 不要給看起來像真的數字：價格先顯示 `0` 會誤導使用者以為價格真的是 0，用 `null` 或 `Loading` 這種明確的「還沒有資料」。另外，直接在 Composable 讀 `stateFlow.value` 不會觀察變化，值變了畫面不會更新，lint 的 `StateFlowValueCalledInComposition` 會警告。它內部在 Android 的主執行緒上收集，所以純 JVM 的單元測試用不了，演練 7 要在手機或模擬器上跑。

**範例。** 依賴是 `androidx.lifecycle:lifecycle-runtime-compose`（本單元演練專案用 2.9.4）：

```kotlin
@Composable
fun PriceHeader(vm: PriceViewModel) {
    // StateFlow 有目前值，不用給 initial
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // 一般 Flow：第一個值來之前顯示什麼，必須自己給
    val lastPrice by vm.priceFlow.collectAsStateWithLifecycle(
        initialValue = null,
    )

    Column {
        Text(uiState.symbol)

        // null 代表還沒收到，顯示 -- 而不是 0
        Text(lastPrice?.let { "$it JPY" } ?: "--")
    }
}
```

**SwiftUI 的對照。** `@Observable` ViewModel 的屬性，在 `body` 裡直接讀就會被追蹤；但是要「退到背景就停」，SwiftUI 得自己看 `scenePhase`，用 `.task(id: scenePhase)` 在 phase 變時取消重啟。Compose 把這段判斷內建在 `collectAsStateWithLifecycle` 裡。`STARTED` 約等於 `scenePhase != .background`（畫面看得到），`RESUMED` 約等於 `.active`。

```swift
struct LivePriceLine: View {
    let vm: PriceViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Text(vm.price.map { "\($0) JPY" } ?? "--")
            // scenePhase 一變就取消重啟這個 task
            .task(id: scenePhase) {
                // 退到背景就不收，回到前景重新收
                guard scenePhase != .background else { return }

                await vm.startListening()
            }
    }
}
```

```kotlin
@Composable
fun LivePriceLine(vm: PriceViewModel) {
    // 預設 STARTED 才收；退到背景自動取消，回前景重新收
    val price by vm.price.collectAsStateWithLifecycle()

    Text(price?.let { "$it JPY" } ?: "--")
}
```

#### `LiveData.observeAsState()`

**這是什麼。** 舊專案的 ViewModel 如果還在用 `LiveData`（Android 較早的可觀察資料類別），Compose 這邊用 `observeAsState()` 把它變成 State。要加依賴 `androidx.compose.runtime:runtime-livedata`。

**使用場景。** 舊畫面的 ViewModel 還是 LiveData，不必為了統一而大改。LiveData 本身就會跟著 lifecycle，只在畫面可見時才送資料，所以不需要另一個 `WithLifecycle` 版本。新寫的程式碼用 `StateFlow`。在 Composable 裡直接讀 `liveData.value`，和讀 `stateFlow.value` 一樣不會觀察變化。

**範例。** 沒給 `initial`，還沒有值時是 `null`：

```kotlin
@Composable
fun LegacyPriceLine(vm: LegacyPriceViewModel) {
    // 沒給 initial：還沒有值時是 null
    val price by vm.priceLiveData.observeAsState()

    Text(price?.let { "$it JPY" } ?: "--")
}
```

給了 `initial` 的話，型別就不含 `null`：`val price by vm.priceLiveData.observeAsState(initial = 0L)`。

**SwiftUI 的對照。** 大致對應 `ObservableObject` 搭配 `@Published` 的那個時代：ViewModel 用 `@Published` 暴露屬性，view 用 `@ObservedObject` 或 `@StateObject` 接，屬性一變 `body` 就重算。

```swift
final class LegacyPriceViewModel: ObservableObject {
    @Published var price: Int?
}

struct LegacyPriceLine: View {
    @ObservedObject var vm: LegacyPriceViewModel

    var body: some View {
        Text(vm.price.map { "\($0) JPY" } ?? "--")
    }
}
```

```kotlin
@Composable
fun LegacyPriceLine(vm: LegacyPriceViewModel) {
    // LiveData → State，跟著 lifecycle
    val price by vm.priceLiveData.observeAsState()

    Text(price?.let { "$it JPY" } ?: "--")
}
```

> [!TIP]
> 面試時可以這樣說：「Android 畫面用 collectAsStateWithLifecycle，退到背景就取消。」

### 8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**畫面的業務狀態預設用 `StateFlow`（對外 `StateFlow`，內部 `MutableStateFlow`），畫面用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。** 大致對應 SwiftUI 的 `@Observable` 與 `@Published`：`mutableStateOf` 像 `@Observable` 的屬性，畫面裡直接讀最簡單，但變化主要是 Compose 看得到；`StateFlow` 像 `@Published`，是一條串流，非 UI 的程式也能訂閱、用運算子組合。

下單表單有三種狀態，放的地方不一樣：數量輸入框的文字只有這個畫面在乎，放在 Composable（`rememberSaveable`）；即時價格和委託是業務資料，要打 API、要撐過旋轉，放 ViewModel 的 `StateFlow`；按下「送出」之後的下單工作必須做完，放 ViewModel 的 `viewModelScope`。這一題依序講四個東西。

#### `StateFlow`（`MutableStateFlow`）

**這是什麼。** `StateFlow` 永遠保存最新的一個值，新的收集者一加入就馬上拿到。慣例是 ViewModel 內部用 `MutableStateFlow` 可寫，對外用 `asStateFlow()` 給唯讀的 `StateFlow`；更新用 `update { }`，是原子更新，意思是多個 coroutine 同時修改時，不會互相蓋掉彼此的修改。

**使用場景。** ViewModel 的業務狀態（行情、訂單、餘額）預設用它：任何 coroutine 都能 `collect`，Worker（WorkManager 在背景執行工作的元件）、單元測試都用得上，單元測試可以用 Turbine（測試 Flow 的函式庫）逐筆驗證；`combine`、`debounce` 這些 Flow 運算子直接用；ViewModel 不用依賴 Compose。不要用在輸入框的即時文字：繞一圈非同步的 Flow 再回畫面，會比輸入法晚一步，輸入中文、日文這類要組字的輸入法時可能游標跳動，所以輸入框的文字放在畫面自己的 State，按下送出才交給 ViewModel。

**範例。** 價格和委託放同一個 `UiState`，畫面用 `collectAsStateWithLifecycle()` 收：

```kotlin
data class OrderUiState(
    val price: Long? = null,
    val orders: List<OrderRow> = emptyList(),
)

class OrderViewModel : ViewModel() {
    // 內部可寫，對外唯讀
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    fun onPrice(price: Long) {
        // 原子更新：多個 coroutine 同時改也不會互相蓋掉
        _state.update { it.copy(price = price) }
    }

    fun onSubmit(order: OrderRow) {
        _state.update { it.copy(orders = it.orders + order) }
    }
}

@Composable
fun OrderScreen(vm: OrderViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column {
        Text("即時價格：${state.price ?: "--"}")

        state.orders.forEach { order ->
            Text("${order.symbol} 已成交 ${order.filled}")
        }
    }
}
```

**SwiftUI 的對照。** 大致對應 `@Published`（或 `CurrentValueSubject`）：都是「有目前值的串流」，`$state` 可以拿到 publisher 給非 UI 的程式訂閱。SwiftUI 沒有 `update { }` 這種原子更新的 API，通常用 `@MainActor` 讓所有寫入排在主執行緒，達到不會同時改的效果。

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
class OrderViewModel : ViewModel() {
    // 內部可寫，對外唯讀
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    fun onPrice(price: Long) {
        // update 是原子更新，不需要靠主執行緒保護
        _state.update { it.copy(price = price) }
    }
}
```

#### ViewModel 裡的 `mutableStateOf`

**這是什麼。** ViewModel 的屬性直接用 `mutableStateOf`，畫面讀它就會被追蹤，不用 `collect`。對 Compose 專案來說寫起來最短，但變化主要是 Compose 看得到。

**使用場景。** 只有 Compose 畫面會讀的狀態，而且專案小、不需要 Flow 運算子。要被 Worker、單元測試、View 畫面或 KMP（Kotlin Multiplatform，同一份 Kotlin 程式碼給多個平台用）共用程式觀察時，就不適合。兩種做法的差別：

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者（背景工作、單元測試） | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，沒有 Compose 在跑就收不到（演練 8） |
| Flow 運算子 | `combine`、`debounce` 直接用 | 要先用 `snapshotFlow` 轉一次 |
| 原子更新 | `_state.update { it.copy(…) }` | 沒有同等的 API |
| 畫面端的寫法 | 要寫 `collectAsStateWithLifecycle()` 並處理 initial | 直接讀 `vm.state`，最簡單 |
| lifecycle | 搭配 `WithLifecycle`，背景會停 | 沒有收集動作，也就沒有 lifecycle 可以停 |
| ViewModel 的依賴 | 不需要 Compose | 依賴 Compose runtime |

**範例。**

```kotlin
class FormViewModel : ViewModel() {
    // 畫面直接讀，不用 collect
    var qty by mutableStateOf("")
        private set

    fun onQtyChange(input: String) {
        qty = input
    }
}

@Composable
fun QtyScreen(vm: FormViewModel = viewModel()) {
    OutlinedTextField(
        value = vm.qty,
        onValueChange = vm::onQtyChange,
    )
}
```

**SwiftUI 的對照。** 大致對應 `@Observable` 的屬性：在 `body` 裡直接讀，不需要訂閱；缺點也相同，只有 SwiftUI（Observation）看得到，不是一條可以拿去組合的串流。

```swift
@Observable
final class FormViewModel {
    private(set) var qty = ""

    func onQtyChange(_ input: String) {
        qty = input
    }
}

struct QtyScreen: View {
    @State private var vm = FormViewModel()

    var body: some View {
        TextField(
            "數量",
            text: Binding(get: { vm.qty }, set: vm.onQtyChange)
        )
    }
}
```

```kotlin
@Composable
fun QtyScreen(vm: FormViewModel = viewModel()) {
    OutlinedTextField(
        // 直接讀 vm.qty：Compose 追蹤這次讀取
        value = vm.qty,
        onValueChange = vm::onQtyChange,
    )
}
```

#### state hoisting（狀態提升）

**這是什麼。** 把狀態從子 Composable 移到呼叫它的上層，子 Composable 只收「值」和「事件的 callback」：值往下、事件往上。Compose 沒有 `Binding` 這種型別，這就是它的做法。傳整個 `MutableState` 下去可行但不建議（見 Kotlin09 第 1 題）。

**使用場景。** 子元件要被重用、要單獨測試，或兩個以上的兄弟元件共用同一份狀態時。例如數量輸入框和下面的「預估金額」都要用數量，數量就提到共同的上層。只有一個 Composable 在乎的暫時狀態（展開或收合），不必提升，留在它自己裡面用 `remember`。

**範例。** 數量放在 `OrderForm`，輸入框只負責顯示與回報：

```kotlin
@Composable
fun OrderForm() {
    var qty by rememberSaveable { mutableStateOf("") }

    Column {
        QtyField(qty = qty, onQtyChange = { qty = it })

        // 兄弟元件也用得到同一份數量
        Text("預估金額：${estimate(qty)}")
    }
}

@Composable
fun QtyField(qty: String, onQtyChange: (String) -> Unit) {
    OutlinedTextField(
        value = qty,
        onValueChange = onQtyChange,
        label = { Text("數量") },
    )
}
```

**SwiftUI 的對照。** `@Binding` 的對應是 state hoisting：`fun QtyField(qty: String, onQtyChange: (String) -> Unit)` 對應 `@Binding var qty: String`。Binding 把「讀和寫」包成一個物件傳下去；Compose 拆成「一個值加一個 callback」，兩邊做的事相同。

```swift
struct OrderForm: View {
    @State private var qty = ""

    var body: some View {
        VStack {
            // $qty 是 Binding，傳給子 view 讀寫
            QtyField(qty: $qty)
            Text("預估金額：\(estimate(qty))")
        }
    }
}

struct QtyField: View {
    @Binding var qty: String

    var body: some View {
        TextField("數量", text: $qty)
    }
}
```

```kotlin
@Composable
fun OrderForm() {
    var qty by rememberSaveable { mutableStateOf("") }

    Column {
        // 值往下、onQtyChange 往上
        QtyField(qty = qty, onQtyChange = { qty = it })
        Text("預估金額：${estimate(qty)}")
    }
}

@Composable
fun QtyField(qty: String, onQtyChange: (String) -> Unit) {
    OutlinedTextField(value = qty, onValueChange = onQtyChange)
}
```

#### state holder、`rememberCoroutineScope`、`viewModelScope`：狀態放哪一層

**這是什麼。** 狀態放哪一層，看「誰在乎、要活多久」。state holder 是把畫面邏輯（捲動、表單驗證）收在一起的普通 class；`rememberCoroutineScope` 給 Composable 一個 coroutine scope（啟動 coroutine 的範圍），綁在這個 Composable 上，離開畫面就取消；`viewModelScope` 綁在 ViewModel 上，旋轉螢幕不會取消。

**使用場景。** 對照下表決定：

| 這個狀態… | 放哪裡 |
|---|---|
| 只有這個 Composable 在乎（展開或收合、選了哪個分頁） | `remember`；要撐過旋轉用 `rememberSaveable` |
| 兩個以上的兄弟 Composable 共用 | 提到它們共同的上層 Composable |
| 畫面邏輯，不依賴 domain（捲動、動畫、表單驗證） | state holder 類別，搭配 `rememberXxxState()` |
| 業務資料（行情、訂單、餘額），要打 API、要撐過旋轉 | ViewModel 的 `StateFlow` |
| 行程被殺後還要還原 | `rememberSaveable` 或 `SavedStateHandle` |

state holder 跟著 Composable，旋轉螢幕就重建，狀態會重來；它的 scope 離開畫面就取消，所以送出訂單這種必須做完的工作不要放在這裡，要放 ViewModel 的 `viewModelScope`（Kotlin07 第 5 題）。

**範例。** 沒有 ViewModel 的畫面邏輯，可以寫一個 state holder，用 `rememberCoroutineScope` 讓它能在事件裡啟動 coroutine：

```kotlin
class PriceHolder(
    private val repo: PriceRepo,
    private val scope: CoroutineScope,
) {
    // 內部可寫，對外唯讀
    private val _state = MutableStateFlow<PriceState>(PriceState.Loading)
    val state: StateFlow<PriceState> = _state.asStateFlow()

    fun refresh() {
        // 事件裡啟動 coroutine，結果寫進 state
        scope.launch {
            _state.value = PriceState.Loaded(repo.fetch())
        }
    }
}

@Composable
fun rememberPriceHolder(repo: PriceRepo): PriceHolder {
    // 綁在這個 Composable 上，離開畫面就取消
    val scope = rememberCoroutineScope()

    return remember(repo, scope) { PriceHolder(repo, scope) }
}
```

**SwiftUI 的對照。** 大致對應「`@State` 持有的 `@Observable` model」。要注意取消行為的差別：SwiftUI 的 `.task` 綁 view，消失就取消，和 `rememberCoroutineScope`、`LaunchedEffect` 一樣綁畫面；model 裡自己開的 `Task { }` 不綁 view，view 消失它還會跑完，這像 `viewModelScope`（`viewModelScope` 連旋轉螢幕都撐得過）。送出訂單這種必須做完的工作，兩邊都放在不綁畫面的地方。

```swift
@MainActor @Observable
final class OrderModel {
    private let api: OrderApi
    private(set) var status = "idle"

    init(api: OrderApi) {
        self.api = api
    }

    func submit(_ order: OrderRow) {
        // 這個 Task 不綁 view：view 消失，它也會做完
        Task {
            status = "sending"
            await api.send(order)
            status = "sent"
        }
    }
}
```

```kotlin
class OrderViewModel(private val api: OrderApi) : ViewModel() {
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun submit(order: OrderRow) {
        // viewModelScope 綁 ViewModel：旋轉不會取消，送出會做完
        viewModelScope.launch {
            _status.value = "sending"
            api.send(order)
            _status.value = "sent"
        }
    }
}
```

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
