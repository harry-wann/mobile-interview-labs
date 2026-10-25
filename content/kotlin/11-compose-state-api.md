---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽：mutableStateOf、remember、derivedStateOf、collectAsState 怎麼選
summary: 給會 Kotlin、但還沒寫過 Jetpack Compose 的人。先用三個觀念（Composable 是描述畫面的函式、State 是 Compose 盯著看的變數、remember 讓重組時不忘記）說明為什麼普通的 var 改了畫面不會動，再用同一張下單表單，一個 API 一個小節，帶出 mutableStateOf、rememberSaveable、derivedStateOf、collectAsStateWithLifecycle、StateFlow 等：這是什麼、什麼時候用、範例、不這樣寫會怎樣。每一題的最後有一小節「對應到 SwiftUI」，給寫過 SwiftUI 的人對照，沒寫過可以跳過；最後給一張「情境對應 API」的速查表。
priority: P0
day: 5
tags: [compose, state, recomposition, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元假設你會寫 Kotlin，但還沒寫過 Jetpack Compose（Android 官方的宣告式 UI 框架。「宣告式」的意思是：你描述畫面現在該長什麼樣子，而不是一步一步指揮「把這個按鈕改成那樣」）。Kotlin06、Kotlin07 講過重組與 effect 的細節，這裡不要求你先看過，用到的觀念都會先用白話講一遍。

這個單元回答九個面試常見問題：

1. Composable、State、`remember` 到底是什麼？為什麼普通的 `var` 改了畫面不會動？
2. `mutableStateOf` 怎麼用？`by` 和 `.value` 差在哪？價格每秒更新幾十次，怎麼只重畫那一行？
3. 清單與對照表怎麼放進 State？`mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？
4. Compose 怎麼判斷「值有沒有變」？什麼時候要換 policy？
5. 數量輸入到一半，旋轉螢幕不能消失：`remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？
6. 價格超過警示線才顯示紅字：`derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？
7. ViewModel 給的價格 Flow 要顯示在畫面上、退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？
8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？
9. 一張速查表：情境對應該用哪個 API？

整個單元用同一張「下單表單」當例子。表單有交易對、數量、價格三個欄位和一個送出按鈕，下面有一條會一直跳動的即時價格，送出之後底下多一筆委託。每一題的 API，都是為了解決這張表單的某個需求：

| 下單表單的需求 | 用到的 API | 第幾題 |
|---|---|---|
| 按下「送出」，畫面要顯示已送出幾筆 | `mutableStateOf`、`remember` | 1 |
| 即時價格每秒更新幾十次，只有那一行要重畫 | 把 State 的讀取往下推、`mutableLongStateOf` | 2 |
| 送出後，底下的委託清單多一筆 | `mutableStateListOf` | 3 |
| 同一筆訂單的狀態改了，畫面卻沒動 | `SnapshotMutationPolicy` | 4 |
| 數量輸入到一半，旋轉螢幕不能消失 | `rememberSaveable`、`Saver` | 5 |
| 價格超過警示線，才顯示紅字 | `derivedStateOf` | 6 |
| ViewModel（Android 用來放畫面資料、旋轉螢幕時不會被銷毀的物件）給的價格 Flow（Kotlin 的非同步資料串流）要顯示，退到背景不要收 | `collectAsStateWithLifecycle` | 7 |
| 這些狀態該放在 ViewModel，還是放在畫面裡 | `StateFlow`、state hoisting | 8 |

Compose 的狀態 API 雖然多，其實只在回答兩個問題：**值放在哪裡**（建立、記住），以及**值從哪裡來**（從別的 State 衍生、從 Flow 或 callback 轉進來）。重組怎麼運作，Kotlin06 有完整的講解；`LaunchedEffect` 這類 effect API，Kotlin07 講過；Flow 的種類與 `stateIn`，Kotlin05 講過。這裡只聚焦「該選哪一個」，需要時簡短帶過並指回去。

每個 API 是一個小節，固定分成四塊：

- **這是什麼**：它解決什麼問題、怎麼運作。
- **什麼時候用**：下單表單的哪個需求會用到它，哪些情況不要用。
- **範例**：一段完整的程式碼，以及執行起來會看到什麼。
- **不這樣寫會怎樣**：常見的錯誤寫法，每一條講症狀和原因。沒有什麼坑的小節會省略這一塊。

第 1 到 8 題的最後，各有一個「對應到 SwiftUI」小節，給寫過 SwiftUI 的人把 Compose 接到已經熟悉的東西上。沒寫過 SwiftUI 的話，整個小節跳過就好，其他地方都不需要它。

### 1. Composable、State、`remember` 到底是什麼？為什麼普通的 `var` 改了畫面不會動？

**Compose 的畫面是用函式「描述」出來的：State 一改變，Compose 就把讀過它的函式重新執行一次，算出新畫面；而函式每次執行，裡面的區域變數都會從頭開始，所以要保留的資料得用 `remember` 存起來。** 這三件事（Composable、State、`remember`）弄懂了，後面所有 API 都只是它們的變化。

這一題用最小的例子串起來：下單表單上有一個「送出」按鈕，按一下，畫面顯示「已送出幾筆」。

#### `@Composable` 函式

**這是什麼**

用 `@Composable` 標記的函式叫做 Composable。它不是在「建立一個按鈕物件」，而是在「描述畫面現在長什麼樣子」，例如 `Text("已送出 3 筆")` 的意思是「這裡有一行字，內容是已送出 3 筆」。Android 傳統的 View 系統，你會建立 `TextView` 再呼叫 `setText` 改字；Compose 沒有這種可以拿在手上的物件。

要改畫面，做法是用新的資料把函式再執行一次，Compose 會比對前後兩次的描述，只更新有變的部分。這個「再執行一次」叫重組（recomposition），所以 Composable 會被反覆執行；Compose 目前建好的整棵畫面樹，叫 composition。

**什麼時候用**

所有畫面都用 Composable 組出來。重組是以函式為單位，函式拆得越小，資料改變時需要重新執行的範圍就越小。

因為函式會被反覆執行，次數和時機都由 Compose 決定，所以函式本體只負責描述畫面，不要在裡面直接打 API 或寫檔案。這類會影響外面世界的事叫副作用（side effect），要放進 `LaunchedEffect` 這類 effect（Compose 專門讓你安全放副作用的地方，Kotlin07 講）。

**範例**

依數量有沒有填，描述兩種不同的畫面。

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

傳入空字串，畫面是紅字「請輸入數量」；傳入 `"0.25"`，畫面是「數量：0.25」。用的就是普通的 `if`，傳進來的資料一變，Compose 再執行一次，畫面就換成另一種。

**不這樣寫會怎樣**

- 在函式本體直接呼叫下單 API，重組幾次就下幾次單。因為 Composable 會被反覆執行，Compose 也不保證會執行幾次。

```kotlin
@Composable
fun OrderSummaryWrong(qty: String, api: OrderApi) {
    // 錯：函式本體直接打 API，每次重組都會再下一次單
    api.submit(qty)

    Text("數量：$qty")
}
```

畫面每重組一次（輸入的數量改了、旋轉螢幕重建都算），就多送出一筆訂單，同一張單可能被送出好幾次。

#### State：Compose 盯著看的變數

**這是什麼**

State 是 Compose 會盯著看的變數。Compose 執行 Composable 的時候，會記下「哪個函式讀過哪個 State」；之後只要這個 State 被寫入新值，Compose 就重新執行讀過它的那些函式，畫面跟著更新。

用 `mutableStateOf` 建立的才是 State。普通的 `var` Compose 看不到，所以改了也沒人通知它。

**什麼時候用**

凡是「值改了，畫面要跟著變」的資料，都放進 State，例如已送出的筆數、買賣方向、輸入框裡的字。只在按鈕事件裡暫時用一下、畫面不需要顯示的值，不必做成 State，因為追蹤每個 State 都有成本。

**範例**

按「送出」，筆數加一。這裡的 `remember` 下一節才講，先照著寫。

```kotlin
@Composable
fun SubmitCounter() {
    // State 加 remember：值會變，重組時也不會歸零
    var sent by remember { mutableIntStateOf(0) }

    // Column：把裡面的東西由上往下排
    Column {
        // 讀取 sent：Compose 記下這個函式依賴它
        Text("已送出：$sent 筆")

        // onClick：點下去時執行的程式
        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

點一下「送出」，畫面從「已送出：0 筆」變成「已送出：1 筆」，再點變成 2。過程是：`sent++` 寫入 State，Compose 找到讀過它的函式，重新執行，`Text` 描述出新的數字。

**不這樣寫會怎樣**

- 把 `sent` 寫成普通的區域變數，點了按鈕，畫面永遠是「已送出：0 筆」。`sent` 真的變成了 1，但 Compose 看不到這個變數，沒有人通知它重組。

```kotlin
@Composable
fun SubmitCounterWrong() {
    // 錯：普通的區域變數，Compose 看不到它
    var sent = 0

    Column {
        Text("已送出：$sent 筆")

        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

不管點幾次「送出」，畫面都停在「已送出：0 筆」。

#### `remember`

**這是什麼**

`remember` 的意思是「重組時不要忘記」。Composable 每次重組都會從頭再跑一遍，如果直接寫 `mutableIntStateOf(0)`，每次重組都會建出一個新的、值為 0 的 State。

`remember { … }` 會把第一次算出來的結果存起來，之後重組直接拿同一份。可以想成：函式每次重組都像拿到一張白紙，`remember` 則是貼在牆上的便利貼，下次回來還在；Compose 用這段程式在畫面樹上的位置，當作便利貼的標籤。

**什麼時候用**

任何要跨重組保留的東西都用它：State，或是建立起來比較貴的物件，例如價格格式化器。`remember` 只管「重組之間」不忘記。旋轉螢幕、App 被系統殺掉，它就管不到了，那是第 5 題 `rememberSaveable` 的工作。

**範例**

價格格式化器只建一次，之後每次價格變都拿同一個來用。`PriceFormatter` 是範例假設的類別，負責把價格排成想要的小數位數。

```kotlin
@Composable
fun PriceText(price: Long) {
    // 第一次執行時建立並存起來，之後重組直接拿
    val formatter = remember { PriceFormatter(0.01) }

    Text(formatter.format(price))
}
```

價格每變一次，`PriceText` 就重組一次，但 `formatter` 還是第一次建的那一個，不會重建。

**不這樣寫會怎樣**

- 把計數器的 `remember` 拿掉（`var sent by mutableIntStateOf(0)`），點按鈕畫面永遠是 0。State 有被寫入、Compose 也重組了，但重組時函式從頭跑，又建出一個值為 0 的新 State。

```kotlin
@Composable
fun SubmitCounterNoRemember() {
    // 錯：少了 remember，每次重組都建一個值為 0 的新 State
    var sent by mutableIntStateOf(0)

    Column {
        Text("已送出：$sent 筆")

        Button(onClick = { sent++ }) {
            Text("送出")
        }
    }
}
```

點「送出」之後畫面還是「已送出：0 筆」，因為每次重組，`sent` 都被重建成 0。

三種寫法並排比較：

| 寫法 | 點一下按鈕之後 | 原因 |
|---|---|---|
| 普通的 `var` | 畫面不動 | Compose 看不到這個變數，沒有人通知它重組 |
| State，但沒有 `remember` | 畫面不動，數字永遠是 0 | 有重組，但每次重組都建立新的 State，值回到 0 |
| `remember` 加 State | 畫面變成 1，再點變成 2 | 正確 |

#### 對應到 SwiftUI

在 SwiftUI 裡，`View` 的 `body` 做的是同一件事：資料變了，就重新算一次「畫面現在長什麼樣子」。`@State` 對應的是「State 加 `remember`」這兩件事合起來。

最大的差別是：SwiftUI 的 `@State` 由框架依 view 在樹上的位置保管，`body` 重算多少次值都不會歸零；Compose 要自己寫 `remember`，漏了就每次重組都歸零。另外，SwiftUI 的 struct 在 `body` 裡改普通屬性，編譯器會直接擋；Compose 的區域變數卻能編譯，所以是靜悄悄地失敗。

| SwiftUI | Compose | 差異 |
|---|---|---|
| `View` 的 `body` | `@Composable` 函式 | 都在描述畫面；Compose 以函式為單位重組 |
| `@State` | `remember { mutableStateOf(…) }` | 前者框架保管，後者要自己寫 `remember` |

```swift
struct SubmitCounter: View {
    @State private var sent = 0

    var body: some View {
        Button("已送出：\(sent) 筆") { sent += 1 }
    }
}
```

```kotlin
@Composable
fun SubmitCounter() {
    var sent by remember { mutableIntStateOf(0) }

    Button(onClick = { sent++ }) {
        Text("已送出：$sent 筆")
    }
}
```

> [!TIP]
> 面試時可以這樣說：「Composable 是描述畫面的函式，State 是 Compose 盯著看的變數，但一定要 remember，否則重組就歸零。」

### 2. `mutableStateOf` 怎麼用？`by` 和 `.value` 差在哪？價格每秒更新幾十次，怎麼只重畫那一行？

**`mutableStateOf` 建立 Compose 會盯著看的 State，只有「讀過它」的 Composable 才會在它改變時重組，所以重畫的範圍取決於「哪個 Composable 去讀」。** 即時價格每秒更新幾十次，只要把讀取放在顯示價格的那個小 Composable 裡，重組就只發生在那一行。數字另外有 primitive 版（`mutableLongStateOf` 等），避免每次寫入都多包一層物件。

#### `mutableStateOf`

**這是什麼**

`mutableStateOf` 建立一個 State，裡面放一個值。它的型別是 `MutableState<T>`，可以讀（`value`）也可以寫；Compose 另外有 `State<T>`，只能讀。慣例是對外只給唯讀的，只有擁有者能寫：在屬性後面加 `private set`，外面讀得到，卻改不了。

**什麼時候用**

表單上任何「改了就要讓畫面跟著變」的值，例如選的是買還是賣。以下情況不是用它的最佳選擇：每秒更新很多次的數字（用本題最後的 primitive 版）、整份清單（第 3 題）、要撐過旋轉螢幕的輸入文字（第 5 題）、業務資料（第 8 題）。

**範例**

點按鈕，在買和賣之間切換。

```kotlin
@Composable
fun SideToggle() {
    var side by remember { mutableStateOf("BUY") }

    // 點下去寫入新值，讀過 side 的地方會重組
    val onToggle = {
        side = if (side == "BUY") "SELL" else "BUY"
    }

    Button(onClick = onToggle) {
        // 在這裡讀 side：Compose 記下這裡依賴它
        Text("目前：$side")
    }
}
```

按鈕上一開始寫「目前：BUY」，點一下變成「目前：SELL」，再點又變回來。

**不這樣寫會怎樣**

- 把 `MutableState` 直接公開給外面（沒有 `private set`），任何程式都能改它的值，出了問題找不到是誰改的。因為 State 本身不限制誰可以寫，限制要自己加。

```kotlin
class OrderFormWrong {
    // 錯：沒有 private set，外面任何程式都能直接改
    var side by mutableStateOf("BUY")
}

fun onLogoutClicked(form: OrderFormWrong) {
    // 別的檔案裡的程式也能偷偷改，之後很難追是誰改的
    form.side = "SELL"
}
```

畫面上的買賣方向被改成 SELL，卻沒有人知道是從哪裡改的。

#### `by` 和 `.value` 差在哪

**這是什麼**

這是同一件事的兩種寫法，行為完全一樣。`by` 是 Kotlin 的屬性委派（property delegation），讓你像用普通變數一樣讀寫，背後實際是在讀寫 State 的 `value`。

不寫 `by`，拿到的就是 State 本身，要自己加 `.value`。Int、Long、Float、Double 的 primitive 版，屬性名稱則是 `intValue`、`longValue` 這類。

**什麼時候用**

多數情況用 `by`，程式碼最短。需要把 State 本身傳給別的函式時，才不用 `by`，因為 `by` 的變數只能傳出它的值。

要記得：讀取發生在「用到變數的那一行」，不是宣告的那一行。`by` 讓 State 看起來像普通變數，反而容易忘記那一行其實在讀 State。

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
@Composable
fun PriceBoard() {
    // by：像普通變數一樣讀寫
    var open by remember { mutableLongStateOf(0L) }

    // 不用 by：保留 State 本身，讀寫要加 longValue
    val last = remember { mutableLongStateOf(0L) }

    Text("開盤 $open，最新 ${last.longValue}")
}
```

兩個價格都會顯示在同一行，值變了，這行就跟著重組。

**不這樣寫會怎樣**

- 用了 `by` 卻沒 import `androidx.compose.runtime.getValue` 和 `setValue`，編譯不過。因為 `by` 靠這兩個函式才知道怎麼讀寫 State（Android Studio 通常會替你補上）。

```kotlin
@Composable
fun PriceBoardWrong() {
    // 錯：檔案頂端沒有 import getValue 和 setValue
    var open by remember { mutableLongStateOf(0L) }

    Text("開盤 $open")
}
```

建置時編譯失敗，訊息說這個型別沒有 `getValue`，不能當 delegate（Android Studio 通常會提示補上 import）。

#### 把讀取往下推：只重畫價格那一行

**這是什麼**

這不是一個 API，是一種寫法：重組的範圍，由「讀取 State 的那一行在哪個 Composable」決定。做法是把 State 本身（或一個回傳值的 lambda）往下傳，讀取留在最小的那個 Composable 裡。State 變的時候，只有那個小 Composable 重組。

**什麼時候用**

即時價格這種每秒更新很多次的值。只在按下按鈕時才改一次的值，不必這麼費工。

更進一步，在 `Modifier.offset { }` 這類接收 lambda 的修飾裡讀 State（Modifier 是串在元件後面的設定清單，管大小、位置、點擊等），讀取會延後到 Layout 階段才發生。Layout 階段是 Compose 每個畫面格三個階段的第二個：決定顯示什麼、決定放哪裡和多大、畫出來。這樣連重組都省了，Kotlin08 有完整的講解。

**範例**

價格 State 往下傳，只有 `LivePrice` 讀它。

```kotlin
@Composable
fun OrderForm(price: State<Long>) {
    Column {
        // 沒有讀價格：價格變了，這行完全不動
        Text("下單表單")

        // 把 State 交給小函式，讀取留在那裡
        LivePrice(price)
    }
}

@Composable
fun LivePrice(price: State<Long>) {
    // 只在這裡讀：價格變了，只有 LivePrice 重組
    Text("即時價格：${price.value}")
}
```

價格一秒變幾十次，只有「即時價格」那一行在重新執行；`OrderForm` 本身和上面的標題都不動。傳 `() -> Long` 這樣的 lambda，在 `LivePrice` 裡呼叫它，效果一樣。

**不這樣寫會怎樣**

- 在上層先讀成 `Long` 再傳下去（`OrderForm(price = priceState.value)`），價格每變一次，呼叫端和 `OrderForm` 都要重組。因為讀取發生在呼叫端，Compose 記下的是「呼叫端依賴價格」。

```kotlin
@Composable
fun ScreenWrong(priceState: State<Long>) {
    // 錯：在呼叫端先讀 value，ScreenWrong 就依賴了價格
    OrderFormWrong(price = priceState.value)
}

@Composable
fun OrderFormWrong(price: Long) {
    Column {
        Text("下單表單")
        Text("即時價格：$price")
    }
}
```

價格每變一次，`ScreenWrong` 和 `OrderFormWrong` 都要重新執行，不只價格那一行。

#### `mutableIntStateOf` 家族

**這是什麼**

`mutableIntStateOf`、`mutableLongStateOf`、`mutableFloatStateOf`、`mutableDoubleStateOf` 是專門存數字的 State，從 Compose runtime 1.5 起提供。它們直接存原始數字（primitive）。`mutableStateOf(0L)` 的 `Long` 是泛型參數，而 JVM 的泛型只能裝物件，每次寫入都要把 `Long` 包成物件，這個動作叫 boxing（自動裝箱）。

**什麼時候用**

會一直被改寫的數字：價格（以最小單位存成 `Long`）、未讀數、捲動位置。`Boolean` 沒有 primitive 版，因為包起來的 `Boolean` 只有 `TRUE`、`FALSE` 兩個共用物件，不會每次新建。

**範例**

價格用 `Long` 版，未讀數用 `Int` 版。

```kotlin
@Composable
fun PriceAndUnread() {
    var price by remember { mutableLongStateOf(0L) }
    var unread by remember { mutableIntStateOf(0) }

    Text("價格：$price，未讀：$unread")
}
```

畫面顯示「價格：0，未讀：0」，值被改寫時照常更新，行為和 `mutableStateOf` 沒有差別。

**不這樣寫會怎樣**

- 用 `mutableStateOf(0L)` 存每秒更新幾十次的價格，畫面照樣正常，看不出問題，卻每次寫入都多一個短命物件（-128 到 127 的小整數會共用快取，超過就新建），增加記憶體回收的負擔。Android Studio 的 lint（內建的程式碼檢查工具）`AutoboxingStateCreation` 會提醒。

```kotlin
@Composable
fun PriceAndUnreadWrong() {
    // 錯：數字用 mutableStateOf，每次寫入都要把數字裝箱成物件
    var price by remember { mutableStateOf(0L) }
    var unread by remember { mutableStateOf(0) }

    Text("價格：$price，未讀：$unread")
}
```

畫面顯示和執行結果都正常，只有 Android Studio 的 lint 會在這兩行標出 `AutoboxingStateCreation` 警告。

- 用了 primitive 版卻去讀 `.value`，一樣會 boxing。要改讀 `longValue` 或用 `by`，lint 的 `AutoboxingStateValueProperty` 會提醒。

```kotlin
@Composable
fun PriceLabelWrong() {
    val price = remember { mutableLongStateOf(0L) }

    // 錯：用了 Long 版卻讀 .value，一樣會裝箱
    Text("價格：${price.value}")
}
```

畫面照常顯示，但 lint 會標出 `AutoboxingStateValueProperty`，提醒改讀 `longValue`。

#### 對應到 SwiftUI

在 SwiftUI 裡，你也會把價格那一行拆成小的 subview，把 `@Observable` 的 model 傳進去，只有在 `body` 裡讀到屬性的 view 才會被追蹤。Compose 的想法一樣，差別是傳下去的是 State 或 lambda，讀取留在最小的 Composable。

`price` 是值，`$price` 是 `Binding`。`by` 的變數像 `price`；不寫 `by` 的 State 像 `$price`，但它是儲存本體，不是用 get 和 set 包出來的 `Binding`。Swift 的 `Int` 是值型別，`@State` 直接存整數，不會 boxing，所以 SwiftUI 沒有 `mutableIntStateOf` 這組 API。

| SwiftUI | Compose | 差異 |
|---|---|---|
| 拆小 subview，傳 `@Observable` model | 傳 State 或 lambda 給小 Composable | 都是縮小讀取的範圍 |
| `$price`（`Binding`） | 不用 `by` 的 State 本身 | State 是儲存本體，`Binding` 是包出來的讀寫 |

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
    // 傳 lambda 也行：讀取 State 發生在 LivePrice 裡
    Text("即時價格：${price()}")
}
```

> [!TIP]
> 面試時可以這樣說：「數字用 primitive 版避免 boxing；頻繁更新的 State 往下傳，在最小的 Composable 才讀。」

### 3. 清單與對照表怎麼放進 State？`mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？

**會一筆一筆增減的清單用 `mutableStateListOf`，對照表用 `mutableStateMapOf`；如果把一般的 `mutableListOf()` 放進 `mutableStateOf`，之後原地 `add`，Compose 不會知道，畫面不更新。** 原因是 `mutableStateOf` 追蹤的是「State 的值有沒有被換掉」，而不是「值的內容有沒有變」。

下單表單送出之後，底下要多一筆委託。三種寫法的行為不同：

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(…))` | 整個換成新 list：`orders = orders + order` | State 的值被換掉時 |
| `mutableStateOf(mutableListOf(…))` | 原地 `add` | **不會知道**（lint 的 `MutableCollectionMutableState` 會警告） |
| `mutableStateListOf(…)` | `add`、`removeAt`、`set`、`clear` | 清單的內容改變時 |

#### `mutableStateListOf`

**這是什麼**

這是一個 Compose 追蹤的清單，型別是 `SnapshotStateList`（名字裡的 Snapshot 是 Compose 管理 State 的機制，第 6 題會講）。對它呼叫 `add`、`removeAt`、`set`、`clear`，Compose 都會通知讀過這個清單的 Composable。手上已經有現成的 list，用 `toMutableStateList()` 轉成追蹤版。

**什麼時候用**

一筆一筆增減的清單：剛送出的委託、自選幣種。每次都整份換掉的資料（例如每秒一份訂單簿快照）不適合，用本題最後的寫法比較單純。它也預設存不進 `rememberSaveable`，要自己寫 Saver（第 5 題）。

**範例**

按「送出」就 `add` 一筆委託，下面列出所有委託。

```kotlin
data class OrderRow(val symbol: String, val filled: Double)

@Composable
fun OrderList() {
    val orders = remember { mutableStateListOf<OrderRow>() }

    Column {
        Button(onClick = { orders.add(OrderRow("BTC_JPY", 0.0)) }) {
            Text("送出")
        }

        // 讀取清單的內容：add 之後，這裡會重組
        orders.forEach {
            Text("${it.symbol} 已成交 ${it.filled}")
        }
    }
}
```

每按一次「送出」，按鈕下面就多一行「BTC_JPY 已成交 0.0」。

**不這樣寫會怎樣**

- 項目是 `class` 又原地改欄位（`orders[1].filled = 0.5`），清單那一列的畫面不更新。因為清單只認得自己內容的改變（新增、刪除、換掉某一格），不看項目裡面；項目要用 `data class` 加 `val`，更新時換掉那一格：`orders[1] = orders[1].copy(filled = 0.5)`（演練 1）。

```kotlin
class OrderRowWrong(val symbol: String, var filled: Double)

@Composable
fun OrderListWrong(orders: SnapshotStateList<OrderRowWrong>) {
    Button(onClick = {
        // 錯：原地改欄位，清單本身沒變，Compose 不知道
        orders[0].filled = 0.5
    }) {
        Text("已成交 ${orders[0].filled}")
    }
}
```

按下按鈕後，欄位其實已經變成 0.5，但按鈕上的「已成交」還是 0.0。

- 一邊走訪一邊修改，會丟 `ConcurrentModificationException`（走訪的同時改動集合會出的錯）。因為走訪到一半，清單的內容被改了；要刪除符合條件的項目，用 `removeAll { }`。

```kotlin
@Composable
fun CleanupButtonWrong(orders: SnapshotStateList<OrderRow>) {
    Button(onClick = {
        for (row in orders) {
            if (row.filled >= 1.0) {
                // 錯：一邊走訪一邊刪除
                orders.remove(row)
            }
        }
    }) {
        Text("清掉已成交")
    }
}
```

清單裡有好幾筆已成交的項目時，按下按鈕會丟 `ConcurrentModificationException`，App 閃退。

#### `mutableStateMapOf`

**這是什麼**

這是 `mutableStateListOf` 的對照表（Map）版本，型別是 `SnapshotStateMap`。對它 `put`、`remove`，Compose 都會通知讀過它的 Composable。手上已經有現成的 map，用 `toMutableStateMap()` 轉成追蹤版。

**什麼時候用**

「交易對 對應 最新價格」這種一筆一筆更新的對照表。值如果是可以原地修改的物件，道理和清單一樣：要換掉整個值，不要改物件裡面的欄位。

**範例**

收到報價，就寫進對照表，畫面顯示最新價格。

```kotlin
@Composable
fun LatestPrices() {
    // 交易對 → 最新價格
    val latest = remember { mutableStateMapOf<String, Long>() }

    Column {
        Button(onClick = { latest["BTC_JPY"] = 1_000_123L }) {
            Text("收到 BTC_JPY 報價")
        }

        // 還沒有資料就顯示 --
        Text("BTC_JPY：${latest["BTC_JPY"] ?: "--"}")
    }
}
```

一開始顯示「BTC_JPY：--」，點按鈕之後變成「BTC_JPY：1000123」。

**不這樣寫會怎樣**

- `mutableStateOf(mutableMapOf())` 之後原地 `put`，畫面不更新。State 的值還是同一個 map，Compose 以為沒有換。

```kotlin
@Composable
fun LatestPricesWrong() {
    val latest = remember {
        mutableStateOf(mutableMapOf<String, Long>())
    }

    Button(onClick = {
        // 錯：原地 put，State 的值還是同一個 map
        latest.value["BTC_JPY"] = 1_000_123L
    }) {
        Text("BTC_JPY：${latest.value["BTC_JPY"] ?: "--"}")
    }
}
```

按下按鈕後，按鈕上還是「BTC_JPY：--」，lint 的 `MutableCollectionMutableState` 也會在宣告那一行警告。

#### `mutableStateOf(listOf(…))`：整個換掉

**這是什麼**

這不是專用 API，而是用 State 存一份不可變的 list，更新時整份換成新的。Compose 追蹤的是「值有沒有被換掉」，每次換掉，讀過它的 Composable 就重組。

**什麼時候用**

整份換掉的資料，例如每秒收到一份 20 檔的訂單簿快照；或是清單很小，複製一份也無所謂。頻繁在原地增減的清單不適合，因為每次都要複製出一份新的 list。

**範例**

收到快照就整份換掉，Compose 才知道有變。

```kotlin
@Composable
fun BidBook() {
    var bids by remember { mutableStateOf(listOf<Long>()) }

    Button(onClick = { bids = listOf(101L, 100L, 99L) }) {
        Text("收到訂單簿快照，目前 ${bids.size} 檔")
    }
}
```

一開始顯示「目前 0 檔」，點按鈕之後變成「目前 3 檔」。

**不這樣寫會怎樣**

- 把 `mutableListOf()` 放進 `mutableStateOf` 再原地 `add`，畫面不動（Kotlin06 演練 2）。因為 State 的值還是同一個 list 物件，Compose 只比「換沒換」，看不到裡面多了一筆。

```kotlin
@Composable
fun BidBookWrong() {
    val bids = remember { mutableStateOf(mutableListOf<Long>()) }

    Button(onClick = {
        // 錯：原地 add，State 的值還是同一個 list
        bids.value.add(101L)
    }) {
        Text("收到訂單簿快照，目前 ${bids.value.size} 檔")
    }
}
```

按幾次按鈕，畫面都停在「目前 0 檔」。

#### 對應到 SwiftUI

在 SwiftUI 裡，`@State var orders: [OrderRow]` 加上 `append`，畫面就會更新，連 `orders[0].filled = 0.5` 這種改欄位的寫法也會。原因是 Swift 的陣列和 struct 都是值型別，任何一處的修改，等於把整個陣列重新寫回 `@State`。

Compose 的 `List` 是參考，原地修改 Compose 完全看不到，所以才需要 `mutableStateListOf`，項目也要用 `data class` 加 `copy` 換掉那一格，這是在模仿值型別的行為。字典同理：SwiftUI 的 `[String: Int]` 直接放 `@State`，Compose 要用 `mutableStateMapOf`。

| SwiftUI | Compose | 差異 |
|---|---|---|
| `@State` 陣列加 `append` | `mutableStateListOf` 加 `add` | 值型別的修改等於寫回，Compose 靠專用清單通知 |
| `orders[0].filled = 0.5` | `orders[0] = orders[0].copy(filled = 0.5)` | Compose 要換掉整個項目 |

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
val orders = remember {
    mutableStateListOf(OrderRow("ETH_JPY", 0.0))
}

// data class 加 val：用 copy 換掉那一格
orders[0] = orders[0].copy(filled = 0.5)
```

> [!TIP]
> 面試時可以這樣說：「List 不是值型別，所以用 mutableStateListOf，項目用 data class 並 copy 換掉。」

### 4. Compose 怎麼判斷「值有沒有變」？什麼時候要換 policy？

**寫入 State 時，Compose 先比較新舊值，「一樣」就把這次寫入當作沒發生，不重組；預設用 `equals` 比。想改變「怎麼比」，在 `mutableStateOf` 的第二個參數傳一個 policy。** 這個「判斷新舊值算不算一樣的規則」，正式名稱叫 `SnapshotMutationPolicy`。

| policy | Compose 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b`（`equals`） | 絕大多數情況：字串、數字、data class |
| `referentialEqualityPolicy()` | `a === b`（同一個物件才算） | `equals` 很貴，或不適合拿來決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

#### `structuralEqualityPolicy()`（預設，不用寫）

**這是什麼**

用 `equals`（也就是 `==`）比較新舊值，相等就把這次寫入丟掉，不重組。你平常寫 `mutableStateOf(x)`，用的就是它。

**什麼時候用**

幾乎都用它：字串、數字、`data class`（`data class` 的 `equals` 會比所有欄位）。預設值本身沒有問題，出問題的是 `equals` 寫得不適合拿來決定畫面要不要更新的時候，才需要換成下面幾個 policy。

**範例**

寫進一個內容完全一樣的新物件，Compose 不重組。

```kotlin
data class Tick(val symbol: String, val price: Long, val receivedAt: Long)

@Composable
fun TickLabel() {
    var tick by remember {
        mutableStateOf(Tick("BTC_JPY", 100, receivedAt = 1))
    }

    // 內容完全一樣：equals 為 true，這次寫入被丟掉
    Button(onClick = { tick = Tick("BTC_JPY", 100, receivedAt = 1) }) {
        Text("${tick.symbol} ${tick.price}")
    }
}
```

點按鈕之後，畫面沒有任何變化，也沒有重組，因為新舊兩個 `Tick` 的每個欄位都相等。

**不這樣寫會怎樣**

- 領域物件 `Order` 的 `equals` 只比 `id`，訂單 A1 的狀態從 New 變成 Filled，畫面卻停在 New，連 State 裡存的都還是舊物件。因為新舊 `Order` 的 `id` 一樣，`equals` 說相等，寫入就被丟掉了（演練 2）。

```kotlin
class Order(val id: String, val status: String) {
    override fun equals(other: Any?) = other is Order && other.id == id

    override fun hashCode() = id.hashCode()
}

@Composable
fun OrderBadgeWrong() {
    // 錯：預設 policy 用 equals 比，id 一樣就被當成沒變
    var order by remember { mutableStateOf(Order("A1", "New")) }

    Button(onClick = { order = Order("A1", "Filled") }) {
        Text("訂單 ${order.id}：${order.status}")
    }
}
```

點按鈕後，畫面還是「訂單 A1：New」，連 `order` 裡存的都還是舊的那個物件。

#### `referentialEqualityPolicy()`

**這是什麼**

它用 `===` 比較：必須是同一個物件才算一樣，換成另一個物件，就算變了。`===` 問的是「是不是同一個物件」，不看裡面的欄位內容，所以不受 `equals` 怎麼寫影響。

**什麼時候用**

`equals` 只比 id 的物件，例如 Room（Android 的本機資料庫函式庫）的 entity；或是 `equals` 很貴，像幾千筆的 list。更好的做法常是讓 UI 用的 model 用 `data class`，`equals` 會比所有欄位，就不必動 policy。

**範例**

`Order` 的 `equals` 只比 `id`，改用 `===` 比。

```kotlin
class Order(val id: String, val status: String) {
    // equals 只比 id
    override fun equals(other: Any?) = other is Order && other.id == id

    override fun hashCode() = id.hashCode()
}

@Composable
fun OrderStatus() {
    var order by remember {
        mutableStateOf(Order("A1", "New"), referentialEqualityPolicy())
    }

    Button(onClick = { order = Order("A1", "Filled") }) {
        Text("訂單 ${order.id}：${order.status}")
    }
}
```

點按鈕之後，畫面從「訂單 A1：New」變成「訂單 A1：Filled」，因為寫進去的是另一個物件。

**不這樣寫會怎樣**

- 用預設 policy，同樣的程式點了按鈕，畫面還是 New。因為 `equals` 只比 `id`，寫入被當成沒變而丟掉（演練 2）。

```kotlin
@Composable
fun OrderStatusWrong() {
    // 錯：沒有傳 referentialEqualityPolicy()，預設用 equals 比
    var order by remember { mutableStateOf(Order("A1", "New")) }

    Button(onClick = { order = Order("A1", "Filled") }) {
        Text("訂單 ${order.id}：${order.status}")
    }
}
```

這裡的 `Order` 就是上面範例那個只比 `id` 的類別；點按鈕後，畫面還是「訂單 A1：New」。

#### `neverEqualPolicy()`

**這是什麼**

它認為任何一次寫入都算變了，連同一個物件再寫進去也會通知。換句話說，這個 policy 不比較新舊值，只要有人寫，就一律當成有變。

**什麼時候用**

訂單簿引擎為了效率，內部就地更新，整個 App 共用同一個物件。更新之後，再把同一個物件寫回 State，通知畫面重組（演練 3）。代價是每次寫入都會重組，連內容沒變的寫入也一樣，所以只用在「就地更新、明確通知」的情況。

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

`OrderBook` 是範例假設的訂單簿類別。點按鈕之後，最佳買價從 100 變成 101。

**不這樣寫會怎樣**

- 用預設 policy，最佳買價停在舊的 100。因為寫進去的是同一個物件，`equals` 當然相等，這次寫入被當成沒變（演練 3）。

```kotlin
@Composable
fun BestBidWrong(book: OrderBook) {
    // 錯：預設 policy，同一個物件寫回去被當成沒變
    val state = remember { mutableStateOf(book) }

    Button(onClick = {
        book.update(101, 1.5)
        state.value = book
    }) {
        Text("最佳買價：${state.value.bestBid()}")
    }
}
```

點按鈕後，訂單簿裡的資料已經更新，畫面上的最佳買價卻還是 100。

- 拿它當成「再顯示一次相同的錯誤訊息」的工具，訊息不會再出現。因為 `LaunchedEffect(message)`（在 Composable 裡啟動 coroutine 的入口，coroutine 是 Kotlin 可以中途暫停等待的輕量非同步工作；key 變了才重新啟動）比較 key 用的還是 `equals`，相同字串不會重啟；一次性事件要包成帶 id 的物件，或照 Kotlin05 第 3 題的做法。

```kotlin
@Composable
fun ErrorMessageWrong(snackbar: SnackbarHostState) {
    val message = remember {
        mutableStateOf("網路錯誤", neverEqualPolicy())
    }

    // 錯：key 用 equals 比，相同字串不會重啟 effect
    LaunchedEffect(message.value) {
        snackbar.showSnackbar(message.value)
    }

    Button(onClick = { message.value = "網路錯誤" }) {
        Text("再顯示一次")
    }
}
```

畫面進來時顯示一次「網路錯誤」，之後再按「再顯示一次」，Composable 雖然重組了，訊息卻不會再出現。

#### 自訂 `SnapshotMutationPolicy`

**這是什麼**

當內建的三種比法都不合用時，可以自己實作 `SnapshotMutationPolicy`，裡面只有一個要寫的函式 `equivalent(a, b)`，回傳 `true` 就代表「這兩個算一樣」。比法完全由你決定，例如只比其中幾個欄位。

**什麼時候用**

行情每筆都帶接收時間，但價格沒變就不該重組，所以只比 symbol 和 price。`derivedStateOf`（第 6 題）的結果也是用 `equals` 判斷有沒有變，同樣可以傳 policy，寫成 `derivedStateOf(referentialEqualityPolicy()) { … }`。

**範例**

只比 symbol 和 price，接收時間不同也算一樣。

```kotlin
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    // 只比畫面看得到的欄位，不比接收時間
    override fun equivalent(a: Tick, b: Tick): Boolean {
        return a.symbol == b.symbol && a.price == b.price
    }
}

@Composable
fun PolicyTick() {
    var tick by remember {
        mutableStateOf(Tick("BTC_JPY", 100, 1), tickPolicy)
    }

    // 只有接收時間不同：算一樣，不重組
    Button(onClick = { tick = Tick("BTC_JPY", 100, 2) }) {
        Text("${tick.symbol} ${tick.price}")
    }
}
```

點按鈕寫進一筆價格沒變、只有接收時間不同的行情，畫面不重組。

**不這樣寫會怎樣**

- 用預設 policy，每一筆行情都重組，連價格沒變也一樣。因為 `receivedAt` 每筆都不同，`equals` 永遠說不相等。

```kotlin
@Composable
fun PolicyTickWrong() {
    // 錯：預設 policy 連接收時間都比，時間不同就算不一樣
    var tick by remember {
        mutableStateOf(Tick("BTC_JPY", 100, 1))
    }

    Button(onClick = { tick = Tick("BTC_JPY", 100, 2) }) {
        Text("${tick.symbol} ${tick.price}")
    }
}
```

畫面上的文字看起來沒變，但每寫入一筆價格相同的行情，這個 Composable 就白白重組一次。

#### 對應到 SwiftUI

SwiftUI 沒有這一題的 API：`@State` 怎麼比較新舊值，由框架決定，沒有讓你挑比較規則的參數。這是因為 SwiftUI 的 view 是 struct 值，它判斷要不要重算 `body`，靠的是「這個 view 的輸入值有沒有變」，並不是去比一個 State 裡的物件；class 搭配 `@Observable`，追蹤的是屬性被寫入，也沒有「同一個物件」的問題，所以不需要 `neverEqualPolicy` 這種硬逼通知的辦法。

你唯一能影響比較規則的地方，是讓 view 遵循 `Equatable`，再加上 `.equatable()`，由你決定哪些欄位算相同。這最接近 Compose 的自訂 policy。

| SwiftUI | Compose | 差異 |
|---|---|---|
| 框架決定怎麼比 | 四種 policy 自己選 | SwiftUI 不開放 State 的比較規則 |
| `Equatable` 加 `.equatable()` | 自訂 `SnapshotMutationPolicy` | 都是自己決定哪些欄位算一樣 |

```swift
struct TickLabel: View, Equatable {
    let symbol: String
    let price: Int
    let receivedAt: Int

    // 只比畫面看得到的欄位
    static func == (a: Self, b: Self) -> Bool {
        a.symbol == b.symbol && a.price == b.price
    }

    var body: some View {
        Text("\(symbol) \(price)")
    }
}

// 使用時加上 .equatable()：TickLabel(…).equatable()
```

```kotlin
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    // 只比畫面看得到的欄位
    override fun equivalent(a: Tick, b: Tick): Boolean {
        return a.symbol == b.symbol && a.price == b.price
    }
}
```

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會被丟掉。」

### 5. 數量輸入到一半，旋轉螢幕不能消失：`remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**看資料要活多久：只要活過重組，用 `remember`；還要撐過旋轉螢幕、App 被系統殺掉再重開，用 `rememberSaveable`（只放小量資料）；業務資料要撐過旋轉，放 ViewModel。** `rememberUpdatedState` 是另一件事：讓長時間運作的 effect 讀到最新的 callback（交給別人、事件發生時才被呼叫的函式）。

先分清楚資料會遇到的三種事件。**重組**就是第 1 題的「函式再執行一次」。**旋轉螢幕**時，Activity（Android 的畫面容器）會被銷毀再重建，但 App 的行程還在，所以 ViewModel 還活著。**行程被殺**（process death）是 App 在背景時，系統為了回收記憶體，把整個行程殺掉；使用者回來時，系統重建畫面並還原先前存的狀態（saved instance state，Android 讓你在畫面被銷毀前存一小份資料、重建時還原的機制），ViewModel 也跟著消失。

| 事件 | `remember` | `rememberSaveable` | ViewModel |
|---|---|---|---|
| 重組 | 還在 | 還在 | 還在 |
| 旋轉螢幕 | 消失 | 還在 | 還在 |
| 行程被系統殺掉後重開 | 消失 | 還在 | 消失（要搭配 `SavedStateHandle`） |

#### `remember(key)`

**這是什麼**

`remember` 第 1 題講過：第一次執行時算出值並存起來，之後重組直接拿。括號裡可以放 key，key 是「會影響計算結果的輸入」。key 和上次不一樣，就丟掉存起來的值，重新計算。

**什麼時候用**

結果取決於某個參數、而且算起來不便宜的時候。例如價格格式化器，小數位數取決於交易對的 tick size（價格的最小跳動單位），換了交易對就要重建。不要拿「一直在變」的值當 key，那樣每次都重算，等於沒有 `remember`。

**範例**

`tickSize` 當 key，換交易對時重建格式化器。

```kotlin
@Composable
fun PriceText(price: Long, tickSize: Double) {
    val formatter = remember(tickSize) { PriceFormatter(tickSize) }

    Text(formatter.format(price))
}
```

`tickSize` 沒變時，每次重組拿同一個 formatter；從 BTC_JPY 換到 ETH_JPY、`tickSize` 變了，就建一個新的。

**不這樣寫會怎樣**

- 沒寫 key，切換交易對之後價格的格式停在上一個交易對的。因為 `remember` 只算一次，不知道 `tickSize` 變了（Kotlin06 演練 6）。

```kotlin
@Composable
fun PriceTextWrong(price: Long, tickSize: Double) {
    // 錯：沒有放 key，tickSize 變了也不會重建
    val formatter = remember { PriceFormatter(tickSize) }

    Text(formatter.format(price))
}
```

從 BTC_JPY 切到 ETH_JPY 之後，價格的小數位數還是 BTC_JPY 的格式。

- 沒有 `remember`，每次重組都重建一個格式化器。因為函式每次執行都從頭跑。

```kotlin
@Composable
fun PriceTextNoRemember(price: Long, tickSize: Double) {
    // 錯：沒有 remember，每次重組都重建一個格式化器
    val formatter = PriceFormatter(tickSize)

    Text(formatter.format(price))
}
```

畫面顯示是對的，但價格每變一次，就多建一個格式化器，白白浪費時間和記憶體。

#### `rememberSaveable`

**這是什麼**

寫法和 `remember` 一樣，差別是值還會存進 Bundle，所以旋轉螢幕、行程被殺都撐得過。Bundle 是 Android 用來暫存畫面狀態的容器，可以想成一個 key-value 字典，只收固定幾種型別。它也能像 `remember` 一樣放 key：任何一個 key 變了，之前存的值就丟掉、重新初始化。

**什麼時候用**

使用者輸入到一半的欄位（數量、價格）、選的分頁。只放小量資料，大型資料放資料庫或 repository（負責取得與保存資料的類別），業務資料放 ViewModel。

**範例**

數量撐過旋轉，價格用 `symbol` 當 key，切換交易對就清空。兩個輸入框都是 `OutlinedTextField`（有外框的輸入框）。

```kotlin
@Composable
fun OrderInputs(symbol: String) {
    var qty by rememberSaveable { mutableStateOf("") }

    // symbol 當 key：切換交易對就清空
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

在數量欄輸入 `0.25`，旋轉螢幕，欄位裡還是 0.25；在價格欄輸入 `100`，再把 `symbol` 換成另一個交易對，價格欄就清空了。要實際測試行程被殺：App 退到背景，執行 `adb shell am kill <套件名稱>`，再從最近使用的 App 點回來。

**不這樣寫會怎樣**

- 輸入框用 `remember`，輸入 `0.25` 後旋轉螢幕，欄位清空要重打。因為旋轉時 Activity 被銷毀重建，`remember` 的值跟著一起沒了（Kotlin06 演練 5）。

```kotlin
@Composable
fun OrderInputsWrong() {
    // 錯：用 remember，旋轉螢幕後值就沒了
    var qty by remember { mutableStateOf("") }

    OutlinedTextField(
        value = qty,
        onValueChange = { qty = it },
        label = { Text("數量") },
    )
}
```

在數量欄輸入 `0.25` 之後旋轉螢幕，欄位變成空的。

#### `Saver`（`listSaver`、`mapSaver`）

**這是什麼**

Bundle 只收基本型別（`String`、數字、`Boolean`），以及實作了 `Parcelable`（Android 的序列化介面，通常用 `@Parcelize` 自動產生）或 `Serializable`（Java 的序列化介面）的型別。自訂的 data class 不在其中。

`Saver` 告訴 Compose 兩件事：`save` 怎麼把物件拆成存得了的東西，`restore` 怎麼組回來。`listSaver` 拆成 List、用位置對應欄位；`mapSaver` 拆成 Map、用 key 對應；`Saver(save, restore)` 給單一值的型別用。

**什麼時候用**

下單草稿 `OrderDraft`（交易對加數量）是自訂的 data class，要放進 `rememberSaveable` 的時候。四種做法的取捨如下表。

| 做法 | 適合 | 缺點 |
|---|---|---|
| `@Parcelize` 加 `Parcelable` | 型別本來就要放進 Intent 或 Bundle | 要套用 Parcelize plugin |
| `listSaver` | 欄位少而且固定 | 用位置對應（`it[0]`、`it[1]`），加欄位或換順序容易對錯 |
| `mapSaver` | 欄位多，之後可能增加 | 用 key 對應，漏存的欄位不會報錯（演練 5） |
| `Saver(save, restore)` | 單一值的型別，如把金額存成字串 | 自己寫 |

ViewModel 想撐過行程被殺，用 `SavedStateHandle`（ViewModel 裡的 key-value 存放處，寫進去的值會跟著 saved instance state 還原）。它也是放進 Bundle，所以同樣只適合小量資料。

**範例**

`listSaver` 把草稿拆成 `List`，旋轉後再組回來。

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

val draftSaver = listSaver<OrderDraft, Any>(
    // 物件 → 存得了的 List
    save = { listOf(it.symbol, it.qty) },
    // List → 物件，位置要和 save 對上
    restore = { OrderDraft(it[0] as String, it[1] as Double) },
)

@Composable
fun DraftEditor() {
    // MutableState 的參數叫 stateSaver；一般物件的版本叫 saver
    var draft by rememberSaveable(stateSaver = draftSaver) {
        mutableStateOf(OrderDraft("BTC_JPY", 0.1))
    }

    Text("${draft.symbol} ${draft.qty}")
}
```

旋轉螢幕前後，畫面上都是同一份草稿。欄位多、之後可能增加的話，改用 `mapSaver`，用 key 對應：

```kotlin
val draftMapSaver = mapSaver<OrderDraft>(
    save = { mapOf("symbol" to it.symbol, "qty" to it.qty) },
    restore = {
        OrderDraft(it["symbol"] as String, it["qty"] as Double)
    },
)
```

**不這樣寫會怎樣**

- 不給 Saver 就放 data class，一打開畫面就閃退，丟 `IllegalArgumentException`。因為 Bundle 收不下自訂型別，組合畫面的當下就檢查出來了（演練 4）。

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

@Composable
fun DraftEditorWrong() {
    // 錯：OrderDraft 不是 Bundle 收得下的型別，又沒給 Saver
    var draft by rememberSaveable {
        mutableStateOf(OrderDraft("BTC_JPY", 0.1))
    }

    Text("${draft.symbol} ${draft.qty}")
}
```

畫面一打開就丟 `IllegalArgumentException`，手機上就是閃退。

- 為 `OrderDraft` 加了新欄位，卻忘了改 Saver，旋轉後那個欄位靜悄悄地歸零。因為 `restore` 用預設值補沒存的欄位，編譯器不會提醒（演練 5）；給 Saver 寫一個「存起來再還原，確認每個欄位都一樣」的單元測試。

```kotlin
data class OrderDraft(
    val symbol: String,
    val qty: Double,
    val side: String = "BUY",
)

val draftSaver = listSaver<OrderDraft, Any>(
    // 錯：新增了 side 欄位，save 和 restore 都沒跟著改
    save = { listOf(it.symbol, it.qty) },
    restore = { OrderDraft(it[0] as String, it[1] as Double) },
)
```

使用者選了賣出（`SELL`）再旋轉螢幕，畫面回來時 `side` 變回預設的 `BUY`，沒有任何錯誤訊息。

#### `rememberUpdatedState`

**這是什麼**

`rememberUpdatedState` 把「最新傳進來的值」包成 State，讓長時間運作的 effect 在需要的那一刻讀到最新的值，又不用重啟。effect 是 Compose 裡專門放副作用的地方。`LaunchedEffect` 是其中一種：進入畫面時啟動一個 coroutine，離開時取消，括號裡的 key 變了就取消重啟（Kotlin07 第 2 題）。

**什麼時候用**

送出訂單之後，開始 10 秒的逾時倒數，時間到要呼叫 `onTimeout`。倒數期間，外面可能換了新版的 `onTimeout`，但倒數不能因此重來。如果希望 effect 在值變的時候重啟，就把值放進 `LaunchedEffect` 的 key，不用 `rememberUpdatedState`。

**範例**

effect 只啟動一次，時間到才讀最新的 callback。

```kotlin
@Composable
fun SubmitTimeout(onTimeout: () -> Unit) {
    // 每次重組都更新成最新傳進來的 callback
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    // key 是 Unit：只啟動一次，重組不重啟
    LaunchedEffect(Unit) {
        delay(10_000)

        // 時間到才讀，拿到的是最新的 callback
        currentOnTimeout()
    }
}
```

畫面出現後等 10 秒，呼叫的是 10 秒那一刻最新傳進來的 `onTimeout`，期間 `onTimeout` 換了幾次版，倒數都不受影響。

**不這樣寫會怎樣**

- 直接呼叫 `onTimeout`，時間到時呼叫到舊版 callback。因為 effect 抓著的是第一次進來的那一份。

```kotlin
@Composable
fun SubmitTimeoutWrong(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(10_000)

        // 錯：抓著第一次進來的 onTimeout，之後換了新版也不知道
        onTimeout()
    }
}
```

倒數期間 `onTimeout` 換成新版，10 秒到時呼叫的還是最早的那一份。

- 把 `onTimeout` 放進 `LaunchedEffect` 的 key，callback 一換，10 秒就重新計時。因為 key 變了，effect 被取消重啟（Kotlin07 演練 5）。

```kotlin
@Composable
fun SubmitTimeoutKeyWrong(onTimeout: () -> Unit) {
    // 錯：把 onTimeout 放進 key，callback 一換 effect 就重啟
    LaunchedEffect(onTimeout) {
        delay(10_000)
        onTimeout()
    }
}
```

只要父層重組、傳進新的 lambda，10 秒就重新計時，如果一直換，可能永遠等不到時間到。

#### 對應到 SwiftUI

在 SwiftUI 裡，`@State` 是你平常用的畫面狀態，`@SceneStorage` 則是把輸入到一半的內容保存下來，App 被系統回收後重開可以還原。對應到 Compose，`@State` 大致像 `remember`，`@SceneStorage` 大致像 `rememberSaveable`。

最大的差別在旋轉螢幕：iOS 旋轉時 view 不會被銷毀，`@State` 平常就夠用；Android 旋轉會重建 Activity，輸入框幾乎都要用 `rememberSaveable`。另外，`@SceneStorage` 只收基本型別和 `RawRepresentable`，自訂型別要拆成基本型別各存一個；Compose 把拆與組集中在 `Saver`，可以單獨測試。

| SwiftUI | Compose | 差異 |
|---|---|---|
| `@State` | `remember` | iOS 旋轉不影響；Android 旋轉會讓 `remember` 的值消失 |
| `@SceneStorage` | `rememberSaveable` | 都撐得過 App 被系統回收後重開，只放小量資料 |

```swift
struct QtyInput: View {
    // App 被系統回收後重開，qty 會還原
    @SceneStorage("qty") private var qty = ""

    var body: some View {
        TextField("數量", text: $qty)
    }
}
```

```kotlin
@Composable
fun QtyInput() {
    // 旋轉螢幕或行程被殺後重開，qty 會還原
    var qty by rememberSaveable { mutableStateOf("") }

    TextField(value = qty, onValueChange = { qty = it })
}
```

> [!TIP]
> 面試時可以這樣說：「rememberSaveable 撐得過旋轉和行程被殺，自訂 class 要給 Saver，新增欄位要同步改。」

### 6. 價格超過警示線才顯示紅字：`derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**三個 API 的方向不同：`derivedStateOf` 是 State 變成另一個 State，目的是「輸入變很頻繁、結果很少變時，減少通知」；`snapshotFlow` 是 State 變成 Flow；`produceState` 是 suspend 函式或 callback 變成 State。** Kotlin07 第 6 題列過方向對照，這裡講怎麼選，以及最容易搞混的 `derivedStateOf`、`remember(key)`、直接計算三者的差別。

#### `derivedStateOf`

**這是什麼**

`derivedStateOf` 用一個計算式，從別的 State 算出新的 State。價格每變一次，計算式就重算一次，但只有算出來的「結果」真的變了，才通知讀它的 Composable。

**什麼時候用**

即時價格每秒變幾十次，但紅字只在「超過警示線」和「回到警示線以下」這兩個狀態換邊時才需要變。如果結果跟著輸入一起變，例如 `derivedStateOf { price * qty }`，輸入每變一次結果也變一次，沒有省到任何通知，反而多了一層，直接計算就好。

三種寫法的差別：

| | 直接算 | `remember(key) { 計算 }` | `remember { derivedStateOf { 計算 } }` |
|---|---|---|---|
| 什麼時候重算 | 每次重組 | 組合時發現 key 和上次不一樣 | 計算式裡讀過的 State 變了 |
| 追蹤什麼 | 函式裡讀到的 State | 你放進 key 的一般參數 | **State 的讀取**，一般參數它看不到 |
| 什麼時候重組 | 輸入每變一次就重組 | 沒有通知，只在重組時重算 | 結果真的變了才重組 |
| 適合 | 輸入不常變，或結果跟著輸入變 | 參數變了要重算（排序、篩選） | State 變很頻繁、結果很少變 |

口訣是：**一般參數放 `remember` 的括號（key），State 放進 `derivedStateOf` 的大括號裡讀。**

**範例**

價格是 State，警示線是一般參數，兩個一起用。

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    // alertLine 是一般參數，當 key；price 是 State，在大括號裡讀
    val over by remember(alertLine) {
        derivedStateOf { price.value > alertLine }
    }

    // 只在 over 換邊時重組
    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

價格在警示線上下來回跳，畫面只在越線和回落的那一刻重組；價格在線的同一側繼續漲跌，畫面完全不動。

**不這樣寫會怎樣**

- 直接寫 `val over = price.value > alertLine`，價格每變一次，這個 Composable 就重組一次，儘管 `over` 根本沒換邊。因為 Composable 自己讀了 `price.value`。

```kotlin
@Composable
fun PriceAlertWrong(price: State<Long>, alertLine: Long) {
    // 錯：直接讀 price.value，價格每變一次就重組
    val over = price.value > alertLine

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

價格在警示線同一側漲跌時，畫面看起來沒變，但這個 Composable 每次都重新執行一遍。

- 漏了 `remember`，每次重組都建一個新的 derived state，快取等於沒有。實測：外層重組 10 次，沒有 `remember` 的計算式執行 11 次，有 `remember` 的只執行 1 次。

```kotlin
@Composable
fun PriceAlertNoRememberWrong(price: State<Long>, alertLine: Long) {
    // 錯：沒有 remember，每次重組都建一個新的 derived state
    val over by derivedStateOf { price.value > alertLine }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

畫面顯示是對的，但快取等於沒有，每次重組計算式都重新執行。

- 一般參數沒當 key，警示線調高了，畫面還顯示「超過」。因為 `derivedStateOf` 看不到一般參數，沒有 key 的 `remember` 又只建一次，一直抓著第一次的 `alertLine`（演練 6）。

```kotlin
@Composable
fun PriceAlertKeyWrong(price: State<Long>, alertLine: Long) {
    // 錯：alertLine 是一般參數，卻沒有當 remember 的 key
    val over by remember {
        derivedStateOf { price.value > alertLine }
    }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

價格 120、警示線 100 時顯示「超過」；警示線調高到 200 之後，畫面還是顯示「超過」。

#### `snapshotFlow`

**這是什麼**

`snapshotFlow` 在大括號裡讀 State，回傳一個 Flow。Flow 是 Kotlin 的非同步資料串流，一個值接一個值送出，用 `collect` 接收，第 7 題會講種類。

開始收集時先送出目前的值，之後裡面讀過的 State 變了，就重新計算並送出新值。連續相同的值會去重（0、1、1、2 只收到 0、1、2）。

**什麼時候用**

想在 State 改變時「做一件事」，而不是「畫出東西」：例如使用者選的交易對變了，回報給分析（analytics）；數量改了，存一份草稿。只是要顯示 State，直接讀就好。要讓非 Compose 的程式觀察 ViewModel 的狀態，用 `StateFlow`（第 8 題）。

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

`Analytics` 是範例假設的類別。畫面一出現就回報一次 `BTC_JPY`（開始收集時先送目前的值），之後每換一次交易對，就回報一次新的。

**不這樣寫會怎樣**

- 先把 State 讀出來再放進 lambda（`val s = symbol` 再 `snapshotFlow { s }`），永遠只收到第一個值。因為 lambda 裡面沒有讀 State，Compose 沒有東西可以追蹤。

```kotlin
@Composable
fun SymbolPickerWrong(analytics: Analytics) {
    var symbol by remember { mutableStateOf("BTC_JPY") }

    LaunchedEffect(Unit) {
        // 錯：先把 State 讀成普通的值，大括號裡沒有讀 State
        val current = symbol
        snapshotFlow { current }.collect {
            analytics.log("symbol_changed", it)
        }
    }
}
```

只在畫面出現時回報一次 `BTC_JPY`，之後不管換幾次交易對，都沒有再回報。

#### `produceState`

**這是什麼**

`produceState` 把 suspend 函式或 callback 的結果，變成 State。suspend 函式是可以中途暫停、等結果回來的函式，callback 則是「資料來了再通知我」的寫法。

結果回來之前，State 的值是你給的 `initialValue`。key 換了會重新執行，離開畫面就取消。

**什麼時候用**

畫面要顯示伺服器時間（一次性的 suspend 載入），或價格來自 callback 風格的 socket。如果資料來源是 Flow，在 `produceState` 裡 `flow.collect { value = it }` 就是 `collectAsState` 內部做的事，所以直接用第 7 題的 API，不要自己寫。

**範例**

把 socket 的 callback 轉成 State，離開畫面時解除 listener。

```kotlin
@Composable
fun rememberLastPrice(socket: PriceSocket): State<Long> {
    return produceState(initialValue = 0L, socket) {
        // callback 風格的 API：收到價格就寫進 value
        val listener = PriceSocket.Listener { value = it }
        socket.addListener(listener)

        // 離開畫面時解除
        awaitDispose { socket.removeListener(listener) }
    }
}
```

`PriceSocket` 是範例假設的類別。畫面上讀這個 State 的人，會看到價格隨 socket 推來的值一直更新；離開畫面時，listener 會被拿掉。

**不這樣寫會怎樣**

- callback 版沒用 `awaitDispose` 解除，畫面離開了 listener 還掛在 socket 上。因為 Compose 不知道要怎麼拆掉你加的 listener（Kotlin07 演練 6）。

```kotlin
@Composable
fun rememberLastPriceWrong(socket: PriceSocket): State<Long> {
    return produceState(initialValue = 0L, socket) {
        val listener = PriceSocket.Listener { value = it }

        // 錯：加了 listener，卻沒有用 awaitDispose 拿掉
        socket.addListener(listener)
    }
}
```

離開畫面之後，listener 還掛在 socket 上，一直收到價格，造成記憶體洩漏。

- 漏放 key，參數變了卻不重跑。規則和 `LaunchedEffect` 完全相同（Kotlin07 演練 2）。

```kotlin
@Composable
fun rememberLastPriceKeyWrong(socket: PriceSocket): State<Long> {
    // 錯：沒有把 socket 放進 key，換了 socket 也不會重跑
    return produceState(initialValue = 0L) {
        val listener = PriceSocket.Listener { value = it }
        socket.addListener(listener)

        awaitDispose { socket.removeListener(listener) }
    }
}
```

`socket` 換成另一條連線之後，State 還是在聽舊的那一條，價格不再更新。

#### Snapshot 是什麼？

**這是什麼**

Snapshot 是 Compose 管理 State 讀寫的機制，可以想成一本「State 的讀寫記錄本」。它有三個概念：讀取追蹤、寫入要「套用並通知」、同時寫多個 State 要一起生效。

讀取追蹤是：Composable、`derivedStateOf`、`snapshotFlow` 執行時，Compose 記下它們讀了哪些 State，那些 State 之後被寫入，才去通知它們。這就是為什麼「沒有在 lambda 裡讀 State」就收不到變化。

第二個概念是：State 被寫入之後，要先「套用」，別人才看得到新值，同時通知讀過它的人。有 Compose 畫面在跑的時候，Compose 會自動做這件事；沒有 Compose 的環境，例如單元測試，就沒有人做。

**什麼時候用**

多個 State 要一起生效時，用 `Snapshot.withMutableSnapshot { … }`，離開區塊時一次套用，別人不會看到只改了一半的狀態。寫單元測試驗證 `snapshotFlow` 時，也會用到 Snapshot 的知識。

理解 `SideEffect` 也需要它：`SideEffect` 在這次組合成功並套用到畫面之後才執行，做到一半被放棄的組合不會執行（Kotlin07 第 4 題）。業務邏輯裡不需要自己管理 Snapshot。

**範例**

重設表單，讓兩個欄位一起生效。

```kotlin
class OrderFormState {
    var symbol by mutableStateOf("BTC_JPY")
    var qty by mutableStateOf("")

    fun reset() {
        // 離開區塊時一起生效，別人不會看到只改了一半
        Snapshot.withMutableSnapshot {
            symbol = "BTC_JPY"
            qty = ""
        }
    }
}
```

呼叫 `reset()` 之後，讀這兩個欄位的人，看到的要嘛是改之前，要嘛是兩個都改完，不會剛好看到交易對改了、數量還沒清。

**不這樣寫會怎樣**

- 單元測試裡 `snapshotFlow` 只收到第一筆。因為寫入要「套用並通知」，別人才看得到；有 Compose 畫面在跑時，Compose 自動做這件事，單元測試裡沒有人做，要自己呼叫 `Snapshot.sendApplyNotifications()`（演練 8；實測：呼叫之前只收到第一筆，呼叫之後收到最後一筆）。

```kotlin
@Test
fun snapshotFlowInTestWrong() = runTest {
    var price by mutableLongStateOf(100L)
    val seen = mutableListOf<Long>()
    val job = launch(UnconfinedTestDispatcher(testScheduler)) {
        snapshotFlow { price }.toList(seen)
    }

    // 錯：沒有 Compose 在跑，寫入沒有人套用並通知
    price = 105L
    job.cancel()
}
```

`seen` 裡只有 `[100]`，收不到後來寫入的 105。

#### 對應到 SwiftUI

SwiftUI 的計算屬性，每次 `body` 重算就算一次，沒有 `derivedStateOf` 這種「結果沒換邊就不通知」的機制；所以 `derivedStateOf` 大致沒有對應。警示線也是 State 時，計算式裡直接讀它就好，不需要 key。

`snapshotFlow` 大致對應 `.onChange(of:initial:)`（`initial` 要 iOS 17 起）。`snapshotFlow` 一定先送目前的值，像 `initial: true`；`.onChange` 預設第一次不執行。想只在變動時做事，Compose 這邊加 `drop(1)`。`produceState` 大致對應 `.task { state = await … }`，差別是它包成回傳 State 的 Composable，不用自己宣告 State。

| SwiftUI | Compose | 差異 |
|---|---|---|
| 計算屬性 | `derivedStateOf` | 前者每次 `body` 重算；後者結果沒變就不通知 |
| `.onChange(of:initial:)` | `snapshotFlow` | 後者一定先送目前的值 |
| `.task { state = await … }` | `produceState` | 後者直接回傳 State |

```swift
struct PriceAlert: View {
    let quote: Quote
    let alertLine: Int

    // 計算屬性：每次 body 重算就算一次
    private var isOver: Bool { quote.price > alertLine }

    var body: some View {
        if isOver {
            Text("價格超過警示線").foregroundStyle(.red)
        }
    }
}
```

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    // 價格每變一次就重算，但只有結果換邊才通知
    val over by remember(alertLine) {
        derivedStateOf { price.value > alertLine }
    }

    if (over) {
        Text("價格超過警示線", color = Color.Red)
    }
}
```

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤 State 的讀取，輸入頻繁、輸出少變時用，要包 remember。」

### 7. ViewModel 給的價格 Flow 要顯示在畫面上、退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 只適合不在乎 Android lifecycle 的情況。** 兩者名字只差 `WithLifecycle` 這幾個字，行為差在 App 退到背景之後：`collectAsState()` 只要畫面還在 composition（Compose 目前建好的畫面樹）裡就一直收，`collectAsStateWithLifecycle()` 會在退到背景時取消收集，回到前景再重新收集。

先把幾個名詞講清楚：

- **lifecycle（生命週期）** 是 Android 畫面從建立、可見、在前景可操作，到退到背景、銷毀的各個階段。**`STARTED`** 是其中一個：畫面看得到。App 退到背景，lifecycle 就回到 `STARTED` 以下。
- **Flow** 是 Kotlin 的非同步資料串流，用 `collect` 接收。**cold Flow** 是「有人 `collect` 才開始跑，每個收集者各跑一份」，像按下播放才開始的影片，例如每次收集就重連一次 WebSocket（App 和伺服器之間持續開著的即時連線）的 Flow。**hot Flow** 是「不管有沒有人收都存在」，像廣播電台，收集者只拿到加入之後的資料。
- **`StateFlow`** 是 hot Flow 的一種：永遠保存最新的一個值，可以讀 `.value`，新的收集者一加入就馬上拿到目前的值。**`SharedFlow`** 是沒有「目前值」的 hot Flow，常用來廣播一次性事件。
- **`stateIn`** 是把 cold Flow 轉成 `StateFlow` 的運算子。**`WhileSubscribed(5000)`** 是它的設定：沒有人收集之後，再等 5 秒才停掉上游。這 5 秒是為了撐過旋轉螢幕那一瞬間「收集者消失又出現」（Kotlin05 第 5 題）。

兩個 API 並排比較：

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 來自哪個套件 | Compose 本體 | `androidx.lifecycle:lifecycle-runtime-compose`，要自己加依賴（演練專案用 2.9.4） |
| 什麼時候收集 | 進入 composition 就開始，離開才停 | lifecycle 至少到 `STARTED`（`minActiveState` 的預設值）才收 |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集，回到前景重新收集 |
| 適合 | 不是 Android，或刻意要在背景持續更新 | Android 畫面（行情、訂單、餘額） |

#### `collectAsState()`

**這是什麼**

`collectAsState()` 把 Flow 變成 State，畫面進入 composition 就開始收，離開才停。它不管 Android 的 lifecycle，也就不管 App 是否在前景。

**什麼時候用**

非 Android 的環境（例如 Compose Multiplatform，同一份 Compose 程式碼跑在多個平台），或是刻意要在背景也持續更新的畫面。Android 的行情、訂單、餘額畫面不要用，因為退到背景後仍會持續收集、持續重組，耗電又耗流量。

**範例**

`StateFlow` 有目前值，所以不用給 `initial`。

```kotlin
@Composable
fun LivePriceLine(prices: StateFlow<Long>) {
    val price by prices.collectAsState()

    Text("即時價格：$price")
}
```

畫面顯示「即時價格：」加上 `StateFlow` 目前的值，每推一筆新價格，這行就更新。如果來源是一般的 Flow，沒有目前值，一定要給 `initial`：`prices.collectAsState(initial = null)`。

**不這樣寫會怎樣**

- 在 Android 畫面用它收行情，App 退到背景後，畫面還在更新。實測（演練 7，伺服器每 50 ms 推一筆價格）：退到背景 0.5 秒，畫面又更新了 9 次，行情還有 1 個收集者在收。因為它只看畫面還在不在 composition 裡，不看 lifecycle。

```kotlin
@Composable
fun LivePriceLineWrong(prices: StateFlow<Long>) {
    // 錯：Android 畫面用 collectAsState，不管 lifecycle
    val price by prices.collectAsState()

    Text("即時價格：$price")
}
```

App 退到背景之後，畫面還在每一筆新價格都重組（演練 7：退到背景 0.5 秒，又更新了 9 次）。

#### `collectAsStateWithLifecycle()`

**這是什麼**

`collectAsStateWithLifecycle()` 把 Flow 變成 State，而且跟著 Android lifecycle 開始與停止收集。lifecycle 至少到 `STARTED`（畫面看得到）才收，退到背景就取消，回到前景再重新收集。

**什麼時候用**

表單上那條即時價格，來源是 ViewModel 的 `StateFlow`。Android 畫面上要顯示 Flow 的資料，預設都用它。兩種情況不適合：`SharedFlow` 的一次性事件，因為 State 只留最新值，快速連發的事件會漏；純 JVM 的單元測試，因為它內部要在 Android 的主執行緒上收集。

不同來源的收法：

| 來源 | 怎麼收 | initial 怎麼給 |
|---|---|---|
| `StateFlow<T>` | `collectAsStateWithLifecycle()` | 不用給，一定有目前值 |
| 一般 `Flow<T>`（cold） | `collectAsStateWithLifecycle(initialValue)` | 一定要給：第一個值來之前，畫面要顯示什麼 |
| `SharedFlow<T>`（事件） | 通常不轉成 State，用 `LaunchedEffect` 收 | State 只留最新值，連發的事件會漏 |

**範例**

`StateFlow` 直接收；一般 Flow 要自己給 `initialValue`。

```kotlin
@Composable
fun PriceHeader(vm: PriceViewModel) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()

    // 一般 Flow 沒有目前值：第一個值來之前，先給 null
    val last by vm.priceFlow.collectAsStateWithLifecycle(initialValue = null)

    // null 代表還沒收到，顯示 -- 而不是 0
    Text("${uiState.symbol} ${last ?: "--"}")
}
```

`PriceViewModel` 是範例假設的類別。沒收到第一筆價格之前顯示「BTC_JPY --」，收到之後顯示價格。同樣的實測（演練 7）：退到背景 0.5 秒，畫面更新 0 次、0 個收集者，回到前景馬上顯示最新價格，因為退到背景時 lifecycle 低於 `STARTED`，收集被取消了。

**不這樣寫會怎樣**

- 把 cold Flow 直接交給它，每次回到前景，上游都從頭再跑一次（例如重開 WebSocket）。因為每次重新收集，cold Flow 都是新的一份；先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，設 `WhileSubscribed(5000)`。

```kotlin
class PriceViewModelWrong(client: PriceClient) : ViewModel() {
    // 錯：cold Flow 直接給畫面，沒有用 stateIn 轉成 StateFlow
    val priceFlow: Flow<Long> = flow {
        // 每次有人收集，就重新連線、從頭開始
        client.connect()
        emitAll(client.prices())
    }
}
```

App 退到背景再回到前景，連線會被重開一次，上游從頭再跑。

- `initial` 給 `0` 這種像真的數字，使用者會以為價格真的是 0。用 `null` 或 `Loading` 這種明確的「還沒有資料」。

```kotlin
@Composable
fun PriceHeaderInitWrong(vm: PriceViewModel) {
    // 錯：initialValue 給 0，看起來像真的價格
    val last by vm.priceFlow.collectAsStateWithLifecycle(initialValue = 0L)

    Text("$last JPY")
}
```

收到第一筆價格之前，畫面顯示「0 JPY」，使用者會以為價格真的是 0。

- 在 Composable 裡直接讀 `stateFlow.value`，值變了畫面不會更新。因為讀 `.value` 不會觀察變化，lint 的 `StateFlowValueCalledInComposition` 會警告。

```kotlin
@Composable
fun PriceHeaderValueWrong(vm: PriceViewModel) {
    // 錯：直接讀 .value，Compose 沒有在觀察它
    val price = vm.price.value

    Text("即時價格：$price")
}
```

畫面只顯示讀到的那一刻的價格，之後價格再怎麼變，畫面都不會跟著動。

#### `LiveData.observeAsState()`

**這是什麼**

舊專案的 ViewModel 如果還在用 `LiveData`（Android 較早的可觀察資料類別），Compose 這邊用 `observeAsState()` 把它變成 State。要加依賴 `androidx.compose.runtime:runtime-livedata`。LiveData 本身就會跟著 lifecycle，只在畫面可見時才送資料，所以沒有 `WithLifecycle` 版本。

**什麼時候用**

舊畫面的 ViewModel 還是 LiveData，不必為了統一而大改。新寫的程式碼用 `StateFlow`。

**範例**

沒給 `initial` 時，還沒有值就是 `null`。

```kotlin
@Composable
fun LegacyPrice(vm: LegacyPriceViewModel) {
    // 給了 initial（如 initial = 0L），型別就不含 null
    val price by vm.priceLiveData.observeAsState()

    Text(price?.let { "$it JPY" } ?: "--")
}
```

沒收到資料之前畫面顯示「--」，收到之後顯示價格加 JPY。

**不這樣寫會怎樣**

- 在 Composable 裡直接讀 `liveData.value`，值變了畫面不會更新。因為和 `stateFlow.value` 一樣，讀 `.value` 不會觀察變化。

```kotlin
@Composable
fun LegacyPriceWrong(vm: LegacyPriceViewModel) {
    // 錯：直接讀 LiveData 的 value，不會觀察變化
    val price = vm.priceLiveData.value

    Text(price?.let { "$it JPY" } ?: "--")
}
```

畫面停在第一次讀到的值（常常是還沒有資料的「--」），之後 LiveData 更新了也不會變。

#### 對應到 SwiftUI

SwiftUI 的 `@Observable` model，屬性在 `body` 裡直接讀就會被追蹤，不用另外「收集」。Compose 的 ViewModel 暴露的是 Flow，要先用 `collectAsStateWithLifecycle()` 變成 State。

退到背景的處理也不同：SwiftUI 的 `.task` 不會自己停，要停得自己看 `scenePhase`；Compose 把「誰負責停」包進 `collectAsStateWithLifecycle()`，預設的 `STARTED` 約等於 `scenePhase != .background`。

| SwiftUI | Compose | 差異 |
|---|---|---|
| `@Observable` 屬性直接讀 | `collectAsStateWithLifecycle()` | Compose 要先把 Flow 變成 State |
| `CurrentValueSubject` | `StateFlow` | 都永遠有目前值 |
| `PassthroughSubject` | `SharedFlow` | 都沒有目前值，適合事件 |
| `.onReceive`、`.task` | `LaunchedEffect` 加 `collect` | 誰負責停不同 |

```swift
struct PriceHeader: View {
    // vm 是 @Observable class
    let vm: PriceViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Text(vm.price.map { "\($0) JPY" } ?? "--")
            // 退到背景就不收
            .task(id: scenePhase) {
                guard scenePhase != .background else { return }
                await vm.startListening()
            }
    }
}
```

```kotlin
@Composable
fun PriceHeader(vm: PriceViewModel) {
    // 退到背景自動取消收集，回前景重新收集
    val price by vm.price.collectAsStateWithLifecycle()

    Text(price?.let { "$it JPY" } ?: "--")
}
```

> [!TIP]
> 面試時可以這樣說：「Android 畫面用 collectAsStateWithLifecycle，退到背景就取消。」

### 8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**畫面的業務狀態預設用 `StateFlow`（對外是 `StateFlow`，內部是 `MutableStateFlow`），畫面端用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。** ViewModel 用 `mutableStateOf` 也能運作，但變化只有 Compose 看得到。

下單表單有三種狀態，放的地方不一樣：數量輸入框的文字只有這個畫面在乎，放在 Composable（`rememberSaveable`）；即時價格和委託是業務資料，要打 API、要撐過旋轉，放 ViewModel 的 `StateFlow`；按下「送出」之後的下單工作必須做完，放 ViewModel 的 `viewModelScope`。

#### `StateFlow`（`MutableStateFlow`）

**這是什麼**

`StateFlow` 永遠保存最新的值，新的收集者一加入就馬上拿到。慣例是 ViewModel 內部用可寫的 `MutableStateFlow`，對外用 `asStateFlow()` 轉成唯讀。`update { }` 是原子更新：多個 coroutine 同時修改時，不會互相蓋掉彼此的修改。

**什麼時候用**

ViewModel 的業務狀態（行情、訂單、餘額）預設用它。任何 coroutine 都能 `collect` 它，Worker（WorkManager 在背景執行工作的元件）和單元測試也用得上，ViewModel 不用依賴 Compose。

輸入框的即時文字是例外：文字繞一圈非同步的 Flow 再回到畫面，更新比輸入法晚一步，輸入中文、日文這類要組字的輸入法時，游標可能跳動。輸入框的即時文字放在畫面自己的 State，需要時（例如按下送出）才交給 ViewModel。

**範例**

ViewModel 更新價格，畫面用 `collectAsStateWithLifecycle()` 收。

```kotlin
data class OrderUiState(val price: Long? = null)

class OrderViewModel : ViewModel() {
    // 內部可寫，對外唯讀
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    // 原子更新：同時改不會互相蓋掉
    fun onPrice(price: Long) {
        _state.update { it.copy(price = price) }
    }
}

@Composable
fun OrderPrice(vm: OrderViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    Text("價格：${state.price ?: "--"}")
}
```

沒收到價格之前顯示「價格：--」；ViewModel 每呼叫一次 `onPrice`，畫面就跟著更新。

**不這樣寫會怎樣**

- 對外直接公開 `MutableStateFlow`，任何地方都能改，出問題時找不到是誰改的。因為可寫的版本沒有被藏起來。

```kotlin
class OrderViewModelWrong : ViewModel() {
    // 錯：直接公開可寫的 MutableStateFlow
    val state = MutableStateFlow(OrderUiState())
}

fun onSomethingElse(vm: OrderViewModelWrong) {
    // 任何地方都能直接改，之後很難追是誰改的
    vm.state.value = OrderUiState(price = 0L)
}
```

畫面上的價格被別的程式碼改成 0，ViewModel 自己完全不知情。

- 兩個 coroutine 各自 `_state.value = _state.value.copy(…)`，其中一個的修改會被蓋掉。因為「讀、改、寫」不是原子的，要用 `update { }`。

```kotlin
data class FormUi(val price: Long? = null, val qty: String = "")

class FormViewModelWrong : ViewModel() {
    private val _state = MutableStateFlow(FormUi())

    fun onPrice(p: Long) {
        // 錯：讀、改、寫不是原子的，會蓋掉別人剛寫的欄位
        _state.value = _state.value.copy(price = p)
    }

    fun onQty(q: String) {
        _state.value = _state.value.copy(qty = q)
    }
}
```

兩個 coroutine 同時呼叫 `onPrice` 和 `onQty` 時，偶爾其中一個欄位的更新會憑空消失。

#### ViewModel 裡的 `mutableStateOf`

**這是什麼**

ViewModel 的屬性直接用 `mutableStateOf`。畫面讀它就被追蹤，不用 `collect`，也不用處理 initial。

**什麼時候用**

只有 Compose 畫面會讀、專案小、不需要 Flow 運算子的時候。要被 Worker、單元測試、傳統 View 畫面，或 KMP（Kotlin Multiplatform，同一份 Kotlin 程式碼給多個平台用）觀察時，不適合。

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者 | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，沒有 Compose 就收不到（演練 8） |
| Flow 運算子 | `combine`、`debounce` 直接用 | 要先用 `snapshotFlow` 轉一次 |
| 原子更新 | `_state.update { … }` | 沒有同等的 API |
| 畫面端 | 要收集並處理 initial | 直接讀 `vm.qty`，最簡單 |
| lifecycle | `WithLifecycle`，背景會停 | 沒有收集動作，沒有 lifecycle 可停 |
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

使用者打字，`onQtyChange` 把字寫進 ViewModel，畫面直接讀 `vm.qty` 就更新；旋轉螢幕時 ViewModel 還活著，字也還在。

**不這樣寫會怎樣**

- 在 Worker 或單元測試裡觀察它，收不到後續的變化。因為 `snapshotFlow` 要有 Compose 在跑，才會有人通知它「有寫入了」（演練 8）。

```kotlin
class FormViewModel : ViewModel() {
    var qty by mutableStateOf("")
        private set
}

class QtyUploaderWrong(private val vm: FormViewModel) {
    suspend fun watch() {
        // 錯：沒有 Compose 在跑，snapshotFlow 收不到後續變化
        snapshotFlow { vm.qty }.collect { upload(it) }
    }
}
```

在單元測試或沒有 Compose 畫面的環境裡，只會上傳一開始的空字串，使用者後來打的字都收不到。

#### state hoisting（狀態提升）

**這是什麼**

state hoisting 是把狀態從子 Composable 移到呼叫它的上層，子 Composable 只收「值」和「事件的 callback」。資料的流向是：值往下、事件往上。

這樣父子之間共用的是同一份狀態，子 Composable 自己不存資料，也比較容易單獨測試。完整的說明在 Kotlin09 第 1 題。

**什麼時候用**

子元件要重用、要單獨測試，或兩個以上的兄弟元件要共用狀態。例如數量輸入框和旁邊的「預估金額」，兩個都要用到數量。

只有一個 Composable 在乎的暫時狀態（例如展開、收合），留在那個 Composable 裡用 `remember` 就好。把整個 `MutableState` 傳下去也行，但不建議，子元件就能隨意寫入。

**範例**

數量放在 `OrderForm`，輸入框只負責顯示和回報。

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

`estimate` 是範例假設的函式。使用者在輸入框打字，`onQtyChange` 把新的字送回 `OrderForm`，`OrderForm` 更新 `qty`，輸入框和「預估金額」同時更新。

**不這樣寫會怎樣**

- 每個子元件各自 `remember` 一份數量，輸入框改了，「預估金額」不會動。因為兩邊各是一份獨立的 State，沒有共用。

```kotlin
@Composable
fun OrderFormWrong() {
    var qty by rememberSaveable { mutableStateOf("") }

    QtyFieldWrong()
    Text("預估金額：${estimate(qty)}")
}

@Composable
fun QtyFieldWrong() {
    // 錯：輸入框自己存一份 qty，父層的 qty 不會跟著變
    var qty by remember { mutableStateOf("") }

    OutlinedTextField(value = qty, onValueChange = { qty = it })
}
```

在輸入框打字，輸入框顯示正常，但「預估金額」始終不變。

#### state holder、`rememberCoroutineScope`、`viewModelScope`：狀態放哪一層

**這是什麼**

決定狀態放哪一層，看的是「誰在乎、要活多久」。state holder 是把畫面邏輯收在一起的普通 class。scope 是啟動 coroutine 的範圍，綁在誰身上，就跟著誰一起取消。`rememberCoroutineScope` 的 scope 綁在 Composable，離開畫面就取消；`viewModelScope` 綁在 ViewModel，旋轉螢幕不會取消。

| 這個狀態… | 放哪裡 |
|---|---|
| 只有這個 Composable 在乎（展開、選了哪個分頁） | `remember`；要撐過旋轉用 `rememberSaveable` |
| 兩個以上的兄弟 Composable 共用 | 提到共同的上層 Composable |
| 畫面邏輯，不牽涉業務規則（捲動、動畫、表單驗證） | state holder，搭配 `rememberXxxState()` |
| 業務資料（行情、訂單、餘額），要打 API、撐過旋轉 | ViewModel 的 `StateFlow` |
| 行程被殺後還要還原 | `rememberSaveable` 或 `SavedStateHandle` |

**什麼時候用**

畫面邏輯（捲動、動畫、表單驗證）寫成 state holder，用 `rememberCoroutineScope` 在事件裡啟動 coroutine。送出訂單這種必須做完的工作，放 ViewModel 的 `viewModelScope`（Kotlin07 第 5 題）。不要放在 state holder 裡，它旋轉就重建，scope 離開畫面就取消。

**範例**

把重新整理價格的畫面邏輯寫成 state holder。

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

`PriceRepo` 是範例假設的類別。畫面在事件裡呼叫 `holder.refresh()`，重新整理的工作在畫面存在期間執行，離開畫面時一起取消。

**不這樣寫會怎樣**

- 把送出訂單放在 `rememberCoroutineScope` 裡，使用者一離開畫面，訂單可能只送到一半。因為這個 scope 綁在 Composable 上，離開畫面就取消。

```kotlin
@Composable
fun SubmitButtonWrong(api: OrderApi, order: OrderRow) {
    // 錯：送出訂單綁在 Composable 的 scope，離開畫面就取消
    val scope = rememberCoroutineScope()

    Button(onClick = { scope.launch { api.send(order) } }) {
        Text("送出")
    }
}
```

按下「送出」後馬上離開畫面（或旋轉螢幕），這個 scope 被取消，送單的請求可能只送到一半。

- 把畫面邏輯 holder 當成業務資料的家，旋轉螢幕後狀態重來。因為 holder 是用 `remember` 建的，旋轉時跟著重建。

```kotlin
class PriceHolderWrong(private val repo: PriceRepo) {
    // 錯：業務資料放在 holder，旋轉螢幕時跟著重建而歸零
    var price by mutableLongStateOf(0L)
        private set

    suspend fun refresh() {
        price = repo.fetch()
    }
}
```

載入完價格之後旋轉螢幕，holder 被重建，價格變回 0，要重新載入一次。

#### 對應到 SwiftUI

`@Binding var qty` 對應的是 `qty` 加 `onQtyChange`：Binding 把「讀」和「寫」包成一個物件傳給子 view，Compose 沒有這個型別，拆成一個值加一個 callback。

ViewModel 的部分，`@Observable` 的屬性（或 `ObservableObject` 的 `@Published`）大致對應 `StateFlow` 或 ViewModel 裡的 `mutableStateOf`。SwiftUI 沒有 `update { }` 這種原子更新，通常靠 `@MainActor` 讓寫入排在主執行緒。`@Environment` 往下傳整棵樹共用的值，大致對應 `CompositionLocal`（用 `CompositionLocalProvider` 提供）。

| SwiftUI | Compose | 差異 |
|---|---|---|
| `@Binding` | state hoisting：值加 callback | 後者拆成兩個參數，沒有 `Binding` 型別 |
| `@Observable`、`@Published` | `StateFlow`，或 ViewModel 的 `mutableStateOf` | `StateFlow` 要先收集才讀得到，`mutableStateOf` 直接讀 |
| `@Environment` | `CompositionLocal` | 都是整棵樹共用的值 |
| model 裡的 `Task { }` | `viewModelScope.launch` | 都不綁畫面，離開畫面照樣做完 |

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

> [!TIP]
> 面試時可以這樣說：「ViewModel 的畫面狀態用 StateFlow，非 Compose 的程式也能收；子畫面改值用 state hoisting。」

### 9. 一張速查表：情境對應該用哪個 API？

**先問三件事：資料從哪來、要活多久、誰要觀察它，再對照下表。**

1. **資料從哪來？** 自己產生、從 Flow、從 callback，還是從 suspend 函式？
2. **要活多久？** 只活過重組、撐過旋轉，還是行程被殺也要還原？
3. **誰要觀察它？** 只有 Compose 畫面，還是也有 Worker、單元測試、其他畫面？

| 情境 | Compose API（第幾題） |
|---|---|
| 計數、開關這類畫面自己的值 | `remember { mutableStateOf(…) }`（1）；普通的 `var` 不會讓畫面更新 |
| 展開或收合、選了哪個分頁 | `remember { mutableStateOf(false) }`（1）；要撐過旋轉就換 `rememberSaveable`（5） |
| 表單輸入到一半，要撐過旋轉 | `rememberSaveable`（5）；自訂型別要給 `Saver` |
| 未讀數、計數器、捲動位置（整數） | `mutableIntStateOf`（2）；用 `intValue` 讀寫，避免 boxing |
| 以最小單位存的價格（`Long`） | `mutableLongStateOf`（2）；用 `longValue` 讀寫 |
| 每秒更新很多次的價格，只想重畫那一行 | 傳 `State` 或 lambda 給小的 Composable，在那裡才讀取（2）；不要在上層先讀成 `Long` 再傳 |
| 自選幣種，一筆一筆增減 | `mutableStateListOf`（3）；項目用 data class，改的時候換掉那一格 |
| 交易對對最新價格的對照表 | `mutableStateMapOf`（3） |
| 每秒換一整份訂單簿快照 | `mutableStateOf(不可變的 list)`（3）；整份換掉，不要一筆一筆改 |
| 同一個物件就地更新後要通知 | `mutableStateOf(obj, neverEqualPolicy())`（4）；每次寫入都重組 |
| `equals` 不適合判斷畫面要不要更新 | `referentialEqualityPolicy()`，或 UI 用 data class（4） |
| 計算結果依賴一般參數 | `remember(key) { … }`（5、6） |
| 長時間運作的 effect 要用最新的 callback | `rememberUpdatedState`（5）；不要放進 key |
| 價格超過警示線才顯示紅字 | `remember { derivedStateOf { … } }`（6）；一般參數放 `remember` 的 key |
| 把交易對變化回報給 analytics | `LaunchedEffect` 裡的 `snapshotFlow`（6） |
| 一次性的 suspend 載入，或 callback 轉成 State | `produceState`（6）；callback 要用 `awaitDispose` 解除 |
| 同時寫好幾個 State，要一起生效 | `Snapshot.withMutableSnapshot { }`（6） |
| 每秒幾十筆行情，只在畫面看得到時收 | `collectAsStateWithLifecycle`（7）；搭配 `stateIn(WhileSubscribed(5000))` |
| 一般的 cold Flow 要顯示 | `collectAsStateWithLifecycle(initialValue)`（7）；或先在 ViewModel 用 `stateIn` |
| 導航、Toast 這類一次性事件 | 不轉成 State，用 `LaunchedEffect` 收（7）；或做成 state 裡「待處理的事件」（Kotlin05 第 3 題） |
| 舊專案的 LiveData | `observeAsState()`（7）；要加 `runtime-livedata` 依賴 |
| ViewModel 暴露畫面狀態 | `StateFlow` 加 `collectAsStateWithLifecycle`（8） |
| 子畫面要改父畫面的值 | state hoisting：值往下、`onValueChange` 往上（8） |
| 純畫面邏輯（捲動、表單驗證） | state holder 加 `rememberXxxState()`（8） |
| 送出訂單這種必須做完的工作 | ViewModel 的 `viewModelScope`（8）；不是 `rememberCoroutineScope` |
| ViewModel 的狀態要撐過行程被殺 | `SavedStateHandle`（5、8）；只放小量資料 |
| 整棵樹共用的值（主題、設定） | `CompositionLocal`，用 `CompositionLocalProvider` 提供（8） |

SwiftUI 的對應見各題最後的「對應到 SwiftUI」。

> [!TIP]
> 面試時可以這樣說：「我先問資料從哪來、要活多久、誰要觀察；畫面自己的值用 remember，要撐過旋轉用 rememberSaveable。」

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
