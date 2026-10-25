---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽：從「畫面為什麼不更新」到 mutableStateOf、remember、derivedStateOf、collectAsState 怎麼選
summary: 給還沒寫過 Jetpack Compose 的人。先用三個觀念（Composable 是描述畫面的函式、State 是 Compose 盯著看的變數、remember 讓重組時不忘記）說明為什麼普通的 var 改了畫面不會動，再用同一張下單表單，一個一個帶出 mutableStateOf、rememberSaveable、derivedStateOf、collectAsStateWithLifecycle 等 API：什麼時候用、不這樣寫會怎樣、和相鄰的 API 差在哪，最後給一張「情境對應 API」的速查表。
priority: P0
day: 5
tags: [compose, state, recomposition, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元假設你會寫 Kotlin，也寫過 Android 的 XML／View 或 iOS 的 UIKit，但還沒寫過 Jetpack Compose。Kotlin06、Kotlin07 講過重組與 effect 的細節，這裡不要求你先看過，用到的觀念都會先用白話講一遍。

這個單元回答九個面試常見問題：

1. Composable、State、`remember` 到底是什麼？為什麼普通的 `var` 改了畫面不會動？
2. `mutableStateOf` 怎麼用？`by` 和 `.value` 差在哪？價格每秒更新幾十次，怎麼只重畫那一行？
3. 清單與對照表怎麼放進 State？`mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？
4. Compose 怎麼判斷「值有沒有變」？什麼時候要換 policy？
5. 數量輸入到一半，旋轉螢幕不能消失：`remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？
6. 價格超過警示線才顯示紅字：`derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？
7. ViewModel 給的價格 Flow 要顯示在畫面上、退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？
8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？
9. 一張對照表：情境對應該用哪個 API？

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

### 1. Composable、State、`remember` 到底是什麼？為什麼普通的 `var` 改了畫面不會動？

**Compose 的畫面是用函式「描述」出來的：State 一改變，Compose 就把讀過它的函式重新執行一次，算出新畫面；而函式每次執行，裡面的區域變數都會從頭開始，所以要保留的資料得用 `remember` 存起來。** 這三件事弄懂了，後面所有 API 都只是它們的變化。

**觀念一，Composable 是描述畫面的函式，不是建立 view 物件。** 在 View 或 UIKit，你建立 `TextView`／`UILabel`，要改字就拿它來 `setText`。Compose 沒有這種物件：用 `@Composable` 標記的函式（Composable）每次執行，都是重新「描述」畫面現在長什麼樣子，例如 `Text("已送出 3 筆")`。要改畫面，就是用新的資料把函式再執行一次，Compose 會比對前後兩次的描述，只更新有變的部分。這個「再執行一次」叫重組（recomposition），所以 Composable 會被重複執行。Compose 目前建好的整棵畫面樹，叫 composition。

**觀念二，State 是 Compose 會盯著看的變數。** 用 `mutableStateOf` 建立的變數，Compose 會記下哪個 Composable 讀過它，它被寫入新值時，就重組讀過它的 Composable。普通的 `var` Compose 看不到，改了畫面不會動，這是初學者撞到的第一面牆。

**觀念三，`remember` 是「重組時不要忘記」。** Composable 每次執行，裡面的程式碼都從頭再跑，直接寫 `mutableStateOf(0)` 就會每次重組都建立新的、值為 0 的 State。`remember { … }` 讓第一次算出的結果存起來，之後重組直接拿那一份。

用最小的例子把三件事串起來：按下「送出」，畫面顯示已送出幾筆。先看**錯誤版一：普通的 `var`**。

```kotlin
@Composable
fun SubmitCounter() {
    var sent = 0                              // 普通的區域變數，Compose 看不到它
    Column {                                  // Column：把裡面的東西由上往下排
        Text("已送出：$sent 筆")               // Text：顯示一行字。這是「描述」畫面，不是建立 view
        Button(onClick = { sent++ }) {        // Button：按鈕。onClick 是點下去時執行的程式
            Text("送出")                       // 按鈕上的字
        }
    }
}
// 點「送出」：sent 真的變成 1，但沒有人通知 Compose，函式不會重新執行，畫面還是「已送出：0 筆」
```

**錯誤版二：用了 State，但沒有 `remember`**，只改一行：

```kotlin
var sent by mutableIntStateOf(0)    // 少了 remember
// 點「送出」：State 被寫入，Compose 也重組了。但重組時函式從頭再跑，又建立一個新的 State、值是 0
// 畫面永遠是「已送出：0 筆」
```

**正確版**，三個觀念都用上：

```kotlin
@Composable
fun SubmitCounter() {
    var sent by remember { mutableIntStateOf(0) }   // State（觀念二）加 remember（觀念三）
    Column {
        Text("已送出：$sent 筆")                      // 讀取 sent：Compose 記下「這個 Composable 依賴 sent」
        Button(onClick = { sent++ }) {               // 寫入 sent：Compose 通知讀過它的 Composable 重組
            Text("送出")
        }
    }
}
```

正確版的 `var sent by remember { mutableIntStateOf(0) }` 這一行，每一段都有用意：`mutableIntStateOf(0)` 建立一個放 `Int` 的 State，初始值 0；`remember { … }` 讓重組時直接拿存起來的同一個 State，不會重建；`by` 是 Kotlin 的屬性委派，讓你像用普通變數一樣讀寫 `sent`，背後實際是讀寫 State 的 `value`（要加 `import androidx.compose.runtime.getValue` 和 `setValue`，第 2 題再講）。點下按鈕之後的流程是：`sent++` 寫入 State → Compose 發現讀過它的 Composable 需要重做 → 重組，`remember` 回傳同一個 State（現在是 1）→ `Text` 描述出「已送出：1 筆」。

| 寫法 | 點一下按鈕之後 | 原因 |
|---|---|---|
| 普通的 `var` | 畫面不動 | Compose 看不到這個變數，沒有人通知它重組 |
| State，但沒有 `remember` | 畫面不動，數字永遠是 0 | 有重組，但每次重組都建立新的 State，值回到 0 |
| `remember` 加 State | 畫面變成 1，再點變成 2 | 正確 |

`remember` 只管「重組之間」不忘記，旋轉螢幕、App 被系統殺掉它就管不到了，那是第 5 題的主題。

> [!TIP]
> 面試時可以這樣說：「Composable 是描述畫面的函式，會被重複執行，不是建立 view 物件。State 是 Compose 追蹤的變數，改變時讀過它的 Composable 會重組；普通的 var 沒人追蹤，改了畫面不會動。因為函式每次重組都從頭執行，State 要用 remember 存起來，否則每次重組都歸零。」

### 2. `mutableStateOf` 怎麼用？`by` 和 `.value` 差在哪？價格每秒更新幾十次，怎麼只重畫那一行？

**`mutableStateOf` 建立 Compose 會盯著看的 State，只有「讀過它」的 Composable 才會在它改變時重組，所以重畫的範圍取決於「哪個 Composable 去讀」。** 即時價格每秒更新幾十次，只要把讀取放在顯示價格的那個小 Composable 裡，重組就只發生在那一行。數字另外有 primitive 版（`mutableLongStateOf` 等），避免每次寫入都多包一層物件。

#### `mutableStateOf`

**這是什麼。** 建立一個 State，裡面放一個值。型別是 `MutableState<T>`，可以讀（`value`）也可以寫。

**什麼時候用。** 表單上任何「改了就要讓畫面跟著變」的值，例如選的是買還是賣。

**最小程式碼。**

```kotlin
var side by remember { mutableStateOf("BUY") }   // 買或賣。remember 讓它在重組時不歸零
Button(onClick = { side = "SELL" }) {            // 點下去寫入新值，讀過 side 的 Composable 會重組
    Text(side)                                    // 在這裡讀取 side，Compose 記下「這裡依賴 side」
}
```

**不這樣寫會怎樣。** 換成普通的 `var`，點了按鈕畫面不動；忘了 `remember`，每次重組都回到 `"BUY"`。兩個症狀都在第 1 題看過。

Compose 把 State 分成兩個介面：`State<T>` 只能讀（只有 `value`），`MutableState<T>` 可以讀也可以寫。慣例是對外只給唯讀的，只有擁有者能寫：

```kotlin
class OrderFormState {
    var qty by mutableStateOf("")      // 外面讀得到
        private set                     // 只有這個 class 自己能寫
    fun onQtyChange(input: String) { qty = input.filter { it.isDigit() || it == '.' } }   // 外面只能透過函式改
}
```

#### `by` 和 `.value` 差在哪

兩個是同一件事的兩種寫法。`by` 是 Kotlin 的語法糖，讓你像用普通變數那樣讀寫；不用 `by` 就要自己寫 `.value`：

```kotlin
// 不用 by：自己寫 .value（Long 版的屬性叫 longValue，一般版叫 value）
val price = remember { mutableLongStateOf(0L) }
Text("${price.longValue}")
```

```kotlin
// 用 by：要 import androidx.compose.runtime.getValue 和 setValue
var price by remember { mutableLongStateOf(0L) }
Text("$price")                       // 像普通變數一樣用，背後一樣是讀 State
```

差別只在寫起來順不順，行為完全一樣。但要記得：**讀取發生在「用到變數的那一行」**，不是宣告的那一行。`by` 讓 State 看起來像普通變數，反而容易忘記「這一行其實在讀 State」。

#### 把讀取往下推：只重畫價格那一行

即時價格每秒更新幾十次。如果讀取價格的是表單最上層的 Composable，每一次價格變，整個表單的函式都要重新跑一遍：

```kotlin
@Composable
fun OrderForm(price: Long) {                  // 參數是 Long：呼叫的人得先讀 State 才能傳進來
    Column {
        QtyField()                             // 和價格無關，但價格一變，整個函式還是要重新執行
        Text("即時價格：$price")
    }
}
// 呼叫端：OrderForm(price = priceState.value)，讀取發生在呼叫端，價格一變，呼叫端和 OrderForm 都重組
```

做法是**把 State 本身往下傳，讀取留在最小的那個 Composable 裡**：

```kotlin
@Composable
fun OrderForm(price: State<Long>) {           // 傳 State 本身，不是 Long
    Column {
        QtyField()                             // 沒有讀價格：價格變了，它完全不動
        LivePrice(price)                       // 把 State 交給小函式，讀取留在那裡
    }
}

@Composable
fun LivePrice(price: State<Long>) {
    Text("即時價格：${price.value}")          // 只在這裡讀取：價格變了，只有 LivePrice 重新執行
}
```

不傳 `State<Long>`，改傳 lambda（`price: () -> Long`，在 `LivePrice` 裡呼叫它）也有同樣的效果。更進一步，在 `Modifier.offset { }` 這類「接收 lambda」的 Modifier（Modifier 是串在元件後面的修飾清單，設定大小、位置、點擊等）裡讀 State，讀取會延後到 Layout 階段（Compose 每個畫面格依序做「決定顯示什麼、決定放哪裡和多大、畫出來」三個階段）才發生，State 改變時連重組都不用。這叫延後讀取，Kotlin08 有完整的講解：

```kotlin
var offsetX by remember { mutableIntStateOf(0) }
val a = offsetX                                    // 寫在函式內容裡：在這裡讀，變了就重組
Box(Modifier.offset { IntOffset(offsetX, 0) })     // Box：可以疊放子項目的容器。大括號裡的 offsetX 等到 Layout 階段才讀，變了不用重組
```

#### `mutableIntStateOf` 家族：數字專用的 State

**這是什麼。** `mutableIntStateOf`、`mutableLongStateOf`、`mutableFloatStateOf`、`mutableDoubleStateOf`，是專門存數字的 State（Compose runtime 1.5 起提供；本單元演練專案用的 Compose BOM 2025.12.00 都有。BOM 是 Bill of Materials，一次指定所有 Compose 函式庫版本的清單）。

**什麼時候用。** 會一直被改寫的數字：價格（若以整數的最小單位存成 `Long`）、未讀數、捲動位置。

**最小程式碼。**

```kotlin
val price = remember { mutableLongStateOf(0L) }   // 存 Long 的 State，不是 MutableState<Long>
price.longValue = 1_000_123L                        // 寫入用 longValue，不是 value
Text("${price.longValue}")                          // 讀取也用 longValue
```

**不這樣寫會怎樣。** 用 `mutableStateOf(0L)` 時，`MutableState<Long>` 的 `Long` 是泛型參數，JVM 上泛型只能裝物件，每次寫入都要把 `Long` 包成 `java.lang.Long` 物件，這叫 boxing（自動裝箱，意思是「多包一層物件、多耗記憶體」）。小整數（-128 到 127）會共用快取，超過就是每次新建一個。每秒更新幾十次的價格，會製造一堆短命的物件，增加記憶體回收的負擔。畫面照樣正常，所以不會出錯、也看不出來，只有 lint 會提醒（lint 是 Android Studio 內建的程式碼檢查工具，這個提醒叫 `AutoboxingStateCreation`）。兩個細節：

- `Boolean` 沒有 primitive 版，因為包起來的 `Boolean` 只有 `TRUE`、`FALSE` 兩個共用物件，不會新建物件。
- 用了 primitive 版卻還去讀 `.value`，一樣會 boxing，lint 的 `AutoboxingStateValueProperty` 會提醒你改讀 `longValue`。

> [!TIP]
> 面試時可以這樣說：「單一值用 mutableStateOf，數字用 mutableIntStateOf 這類 primitive 版，避免每次寫入都 boxing。by 和 .value 只是寫法不同，重點是讀取發生在哪個 Composable。即時價格這種每秒更新很多次的 State，我會把 State 往下傳，讓讀取發生在最小的那個 Composable，重組範圍就只剩那一行。」

### 3. 清單與對照表怎麼放進 State？`mutableStateListOf` 和 `mutableStateOf(listOf())` 差在哪？

**會一筆一筆增減的清單用 `mutableStateListOf`，對照表用 `mutableStateMapOf`；如果把一般的 `mutableListOf()` 放進 `mutableStateOf`，之後原地 `add`，Compose 不會知道，畫面不更新。** 原因是 `mutableStateOf` 追蹤的是「State 的值有沒有被換掉」，而不是「值的內容有沒有變」。

下單表單送出之後，底下要多一筆委託。三種寫法行為不同：

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(...))` | 整個換成新 list：`orders = orders + order` | State 的值被換掉時 |
| `mutableStateOf(mutableListOf(...))` | 原地 `add` | **不會知道**（Kotlin06 演練 2；lint `MutableCollectionMutableState` 會警告） |
| `mutableStateListOf(...)` | `add`、`removeAt`、`set`、`clear` | 清單的內容改變時 |

#### `mutableStateListOf`

**這是什麼。** 一個 Compose 追蹤的清單，型別是 `SnapshotStateList`（名字裡的 Snapshot 是 Compose 管理 State 的機制，第 6 題會講）。對它呼叫 `add`、`removeAt`、`set`、`clear`，Compose 都會通知讀過這個清單的 Composable。

**什麼時候用。** 一筆一筆增減的清單：剛送出的委託、自選幣種。

**最小程式碼。**

```kotlin
val orders = remember { mutableStateListOf<String>() }   // Compose 追蹤的清單。remember 讓它不會在重組時被清空
Button(onClick = { orders.add("BTC_JPY 買 0.25") }) {    // 原地 add：清單自己會通知 Compose
    Text("送出")
}
orders.forEach { Text(it) }                               // 讀取清單的內容：add 之後，這裡會重組
```

**不這樣寫會怎樣。** 把清單放進 `mutableStateOf` 卻原地修改：

```kotlin
val bad = remember { mutableStateOf(mutableListOf<String>()) }
bad.value.add("BTC_JPY 買 0.25")     // State 的值還是同一個 list，Compose 不知道內容變了，畫面不更新
```

改成 `mutableStateOf(listOf())`，每次換成新的 list（`orders = orders + order`），或改用 `mutableStateListOf`，兩者都能運作。

#### `mutableStateMapOf`

**這是什麼。** `mutableStateListOf` 的對照表版本，型別是 `SnapshotStateMap`。**什麼時候用。** 「交易對 → 最新價格」這種一筆一筆更新的對照表。

```kotlin
val latest = remember { mutableStateMapOf<String, Long>() }   // 交易對 → 最新價格
latest["BTC_JPY"] = 1_000_123L                                  // 寫入：讀過這個 map 的 Composable 會重組
Text("${latest["BTC_JPY"] ?: "--"}")                            // 讀取：還沒有資料就顯示 --
```

**不這樣寫會怎樣。** 和清單一樣，`mutableStateOf(mutableMapOf())` 原地 `put` 不會通知。已經有現成的集合，用 `toMutableStateList()`、`toMutableStateMap()`（一串 `Pair`）轉成追蹤版。

`SnapshotStateList` 有四個注意事項：

1. **它只認得「清單本身」的改變，不看項目裡面。** 項目如果是 `class OrderRow(var filled: Double)`，原地改 `filled`，清單沒變，畫面不會更新。項目要用 `data class` 加 `val`，改的時候換掉那一格：`orders[1] = orders[1].copy(filled = 0.5)`（演練 1）。
2. **一邊走訪一邊修改會丟 `ConcurrentModificationException`**（走訪的同時改動集合會出的錯），和一般的 `MutableList` 一樣。要刪除符合條件的項目，用 `removeAll { }`。
3. **預設存不進 `rememberSaveable`**，要自己寫 Saver（第 5 題）。
4. **適合一筆一筆增減**。如果每次都是整份資料換掉（例如每秒收到一份 20 檔的訂單簿快照），`mutableStateOf` 放一份新的不可變 list 比較單純（Kotlin08 的 `ImmutableList`）。

> [!TIP]
> 面試時可以這樣說：「清單用 mutableStateListOf，原地修改一般的 MutableList 不會通知 Compose，因為 mutableStateOf 只看 State 的值有沒有被換掉。要注意 SnapshotStateList 只看清單內容的改變，項目要用不可變的 data class，改的時候換掉那一格。每秒整份換掉的資料，就放一份新的不可變 list。」

### 4. Compose 怎麼判斷「值有沒有變」？什麼時候要換 policy？

**寫入 State 時，Compose 先比較新舊值，「一樣」就把這次寫入當作沒發生，不重組；預設用 `equals` 比。想改變「怎麼比」，在 `mutableStateOf` 的第二個參數傳一個 policy。** 這個「判斷新舊值算不算一樣的規則」，正式名稱叫 `SnapshotMutationPolicy`。

| policy | Compose 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b`（`equals`） | 絕大多數情況：字串、數字、data class |
| `referentialEqualityPolicy()` | `a === b`（同一個物件才算一樣） | `equals` 很貴（幾千筆的 list），或 `equals` 不適合拿來決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

#### `structuralEqualityPolicy()`（預設，不用寫）

**這是什麼。** 用 `equals` 比較，相等就不重組。**什麼時候用。** 幾乎都用它：字串、數字、`data class`（`equals` 會比所有欄位）。

```kotlin
data class Tick(val symbol: String, val price: Long, val receivedAt: Long)

val a = mutableStateOf(Tick("BTC_JPY", 100, receivedAt = 1))   // 預設 policy：equals 相同就算沒變
a.value = Tick("BTC_JPY", 100, receivedAt = 1)                  // 內容完全一樣的新物件：equals 為 true，不重組
```

**不這樣寫會怎樣。** 預設值本身沒有問題；出問題的是 `equals` 寫得不適合。例如領域物件 `Order` 的 `equals` 只比 `id`，訂單 A1 的狀態從 New 變成 Filled，新舊 Order「相等」，這次寫入被丟掉，畫面停在 New，連 State 裡存的都還是舊物件（演練 2）。

#### `referentialEqualityPolicy()`

**這是什麼。** 用 `===` 比較：必須是同一個物件才算一樣，換成另一個物件就算變了。**什麼時候用。** `equals` 只比 id，或 `equals` 很貴的時候。

```kotlin
val order = mutableStateOf(Order("A1", "New"), referentialEqualityPolicy())   // Order 的 equals 只比 id，這裡改用 === 比
order.value = Order("A1", "Filled")                                            // 另一個物件：算變了，畫面更新
```

**不這樣寫會怎樣。** 就是上面演練 2 的症狀。更好的做法是 UI 用的 model 用 `data class`，`equals` 會比所有欄位，不必動 policy。

#### `neverEqualPolicy()`

**這是什麼。** 任何一次寫入都算變了，連同一個物件寫進去也會通知。**什麼時候用。** 訂單簿引擎為了效率，內部就地更新、整個 App 共用一個物件：更新之後再寫一次同一個物件，通知畫面。

```kotlin
val book = mutableStateOf(orderBook, neverEqualPolicy())   // 永遠算變了
orderBook.update(101, 1.5)                                  // 引擎就地更新內容
book.value = orderBook                                      // 同一個物件寫回去，照樣通知
```

**不這樣寫會怎樣。** 用預設 policy，同一個物件 `equals` 當然相等，這次寫入被當成沒變，最佳買價停在舊的（演練 3）。代價是每次寫入都重組，連內容沒變的寫入也一樣。

`neverEqualPolicy` 也**不是**「再顯示一次相同的錯誤訊息 Toast」的工具。它確實會讓讀這個 State 的 Composable 重組（本機實測：寫入同一個字串兩次，重組了兩次），但 `LaunchedEffect(message)`（在 Composable 裡啟動 coroutine 的入口，括號裡的 key 變了才會重新啟動）比較 key 用的還是 `equals`，相同的字串不會重新啟動，Toast 一樣不會再出現。一次性事件請包成帶 id 的物件（`data class UiMessage(val id: Long, val text: String)`），或照 Kotlin05 第 3 題的做法。

#### 自訂 policy

**這是什麼。** 自己決定「什麼叫一樣」。**什麼時候用。** 行情每筆都帶接收時間，但價格沒變就不該重組，只比 symbol 和 price。

```kotlin
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    override fun equivalent(a: Tick, b: Tick) = a.symbol == b.symbol && a.price == b.price   // 只比畫面看得到的欄位
}
val tick = mutableStateOf(Tick("BTC_JPY", 100, 1), tickPolicy)   // 接收時間不同、價格相同的寫入，不重組
```

**不這樣寫會怎樣。** 用預設 policy，`receivedAt` 每筆都不同，每一筆行情都重組，連價格沒變也一樣。

另外，`derivedStateOf`（第 6 題）的結果預設也是用 `equals` 判斷有沒有變，同樣可以傳 policy：`derivedStateOf(referentialEqualityPolicy()) { … }`。本題練習用到的 `SideEffect { }`，是「這次組合成功完成之後執行一次」的區塊（Kotlin07 第 4 題），練習題用它來數「重組了幾次」。

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會直接被丟掉。我會在三種情況換 policy：equals 太貴或不適合時用 referentialEqualityPolicy；同一個物件就地更新要通知時用 neverEqualPolicy；只想比畫面上看得到的欄位時自訂 policy。但多數時候更好的做法是讓 UI model 是不可變的 data class，不用動 policy。」

### 5. 數量輸入到一半，旋轉螢幕不能消失：`remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**看資料要活多久：只要活過重組，用 `remember`；還要撐過旋轉螢幕、App 被系統殺掉再重開，用 `rememberSaveable`（只放小量資料）；業務資料要撐過旋轉，放 ViewModel。** `rememberUpdatedState` 是另一件事：讓長時間運作的 effect 讀到最新的 callback。

先分清楚資料會遇到的三種事件。**重組**就是第 1 題的「函式再執行一次」。**旋轉螢幕**時，Activity 被銷毀再重建，但 App 的行程還在，所以 ViewModel 活著。**行程被殺**（process death）是 App 在背景時，系統為了回收記憶體，把整個行程殺掉；使用者回來時，系統重建畫面並還原先前存的狀態（saved instance state，Android 讓你在畫面被銷毀前存一小份資料、重建時還原的機制），ViewModel 也跟著消失。

| 事件 | `remember` | `rememberSaveable` | ViewModel |
|---|---|---|---|
| 重組 | 還在 | 還在 | 還在 |
| 旋轉螢幕 | 消失 | 還在 | 還在 |
| 行程被系統殺掉後重開 | 消失 | 還在 | 消失（要搭配 `SavedStateHandle`） |

#### `remember`（含 key）

**這是什麼。** 第 1 題講過：第一次執行時算出值並存起來，之後重組直接拿。括號裡可以放 key：key 是「會影響計算結果的輸入」，key 和上次不一樣，就丟掉存起來的值、重新計算。

**什麼時候用。** 只活過重組就夠的東西，或建立起來比較貴的物件。例如價格格式化器，小數位數取決於交易對的 tick size（價格的最小跳動單位）：

```kotlin
val formatter = remember(tickSize) { PriceFormatter(tickSize) }   // tickSize 是 key：換交易對、tickSize 變了就重新建立
Text(formatter.format(price))                                       // 用格式化器顯示價格
```

**不這樣寫會怎樣。** 沒寫 key，切換交易對之後格式還停在上一個（Kotlin06 演練 6）；沒有 `remember`，每次重組都重建格式化器。

#### `rememberSaveable`

**這是什麼。** 寫法和 `remember` 一樣，但值還會存進 saved instance state，所以旋轉螢幕、行程被殺都撐得過。

**什麼時候用。** 使用者輸入到一半的欄位（數量、價格）、選的分頁。只放小量資料，大型資料放資料庫或 repository。

**最小程式碼。**

```kotlin
var qty by rememberSaveable { mutableStateOf("") }                 // 和 remember 寫法一樣；值會存進 Bundle，旋轉後還原
OutlinedTextField(                                                   // 有外框的輸入框
    value = qty,                                                     // 輸入框顯示的字，來自 State
    onValueChange = { qty = it },                                    // 使用者每打一個字，把新的字寫回 State
    label = { Text("數量") },
)
```

**不這樣寫會怎樣。** 用 `remember`，使用者輸入 `0.25` 之後旋轉螢幕，輸入框清空，要重打（Kotlin06 演練 5）。要實際測試行程被殺：App 退到背景，執行 `adb shell am kill <套件名稱>`，再從最近使用的 App 點回來。

**`rememberSaveable` 存得了什麼？** 預設只存得了 Bundle 放得下的型別。Bundle 是 Android 用來暫存畫面狀態的容器，可以想成一個 key-value 字典，只收固定幾種型別：`String`、數字、`Boolean` 這類基本型別，以及實作了 `Parcelable`（Android 的序列化介面，把物件拆成可以放進 Bundle 的資料，通常用 `@Parcelize` 自動產生）或 `Serializable`（Java 的序列化介面）的型別。**存不了的：一般的 data class（沒實作上述介面）、`SnapshotStateList`、lambda。** 這時組合畫面的當下就會丟 `IllegalArgumentException`，訊息是「cannot be saved using the current SaveableStateRegistry」，手機上就是畫面一打開就閃退（演練 4）。解法是給 `Saver`。

#### `Saver`、`listSaver`、`mapSaver`

**這是什麼。** Saver 告訴 Compose 兩件事：`save` 怎麼把物件拆成存得了的東西，`restore` 怎麼組回來。`listSaver` 拆成 List，用位置對應欄位；`mapSaver` 拆成 Map，用 key 對應欄位；`Saver(save, restore)` 給單一值的型別用。

**什麼時候用。** 下單草稿 `OrderDraft`（交易對加數量）是自訂的 data class，要放進 `rememberSaveable`。

**最小程式碼。**

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

val draftSaver = listSaver<OrderDraft, Any>(                         // 拆成 List，用位置對應
    save = { listOf(it.symbol, it.qty) },                             // 物件 → 存得了的 List
    restore = { OrderDraft(it[0] as String, it[1] as Double) },       // List → 物件，位置要和 save 對上
)
var draft by rememberSaveable(stateSaver = draftSaver) {              // 放 MutableState 的，參數叫 stateSaver
    mutableStateOf(OrderDraft("BTC_JPY", 0.1))
}
```

**不這樣寫會怎樣。** 不給 Saver，就是上面的閃退（演練 4）。另一個更隱蔽的坑：後來為 `OrderDraft` 加了新欄位，卻忘了改 Saver，`restore` 用預設值補，編譯器不會提醒，旋轉之後那個欄位就靜悄悄地歸零（演練 5）。給 Saver 寫一個單元測試：存起來再還原，確認每個欄位都一樣。

| 做法 | 適合 | 缺點 |
|---|---|---|
| `@Parcelize` 加 `Parcelable` | 這個型別本來就要放進 Intent 或 Bundle 傳遞 | 要套用 Parcelize plugin |
| `listSaver` | 欄位少而且固定 | 用位置對應（`it[0]`、`it[1]`），加欄位或換順序容易對錯 |
| `mapSaver` | 欄位多，之後可能增加 | 用 key 對應，漏存的欄位不會報錯（演練 5） |
| `Saver(save, restore)` | 單一值的型別，例如把金額存成字串 | 自己寫 |

其他兩種常見寫法：

```kotlin
val draftMapSaver = mapSaver(                                         // mapSaver：用 key 對應，加欄位時比較不容易對錯
    save = { mapOf("symbol" to it.symbol, "qty" to it.qty) },
    restore = { OrderDraft(it["symbol"] as String, it["qty"] as Double) },
)
val amountSaver = Saver<BigDecimal, String>(                          // 單一值：把金額存成字串
    save = { it.toPlainString() },
    restore = { BigDecimal(it) },
)
var amount by rememberSaveable(stateSaver = amountSaver) { mutableStateOf(BigDecimal.ZERO) }   // 放一般物件（不是 MutableState）的，參數叫 saver
```

`rememberSaveable` 也可以像 `remember` 一樣放 key（寫成 `inputs`）：任何一個變了，之前存的值就丟掉、重新初始化。例如 `rememberSaveable(symbol) { mutableStateOf("") }`，切換交易對就清空輸入。ViewModel 要撐過行程被殺，用 `SavedStateHandle`（ViewModel 裡的 key-value 存放處，寫進去的值會跟著 saved instance state 還原）：`savedStateHandle.getStateFlow("qty", "")` 讀、`savedStateHandle["qty"] = value` 寫。它和 `rememberSaveable` 一樣放進 Bundle，所以同樣只適合小量資料。

#### `rememberUpdatedState`

**這是什麼。** 把「最新傳進來的值」包成 State，讓長時間運作的 effect 在需要的那一刻讀到最新的值，又不用重啟。

**什麼時候用。** 送出訂單之後，開始 10 秒的逾時倒數，時間到要呼叫 `onTimeout`。倒數期間，外面可能換了新版的 `onTimeout`，倒數卻不能因此重來。`LaunchedEffect` 是在 Composable 裡啟動 coroutine 的入口：進入畫面時啟動、離開時取消，括號裡的 key 變了就取消重啟（Kotlin07 第 2 題）。

**最小程式碼。**

```kotlin
@Composable
fun SubmitTimeout(onTimeout: () -> Unit) {
    val currentOnTimeout by rememberUpdatedState(onTimeout)   // 每次重組都更新成最新傳進來的 callback
    LaunchedEffect(Unit) {                                     // key 是 Unit：只在進入畫面時啟動一次，重組不會重啟
        delay(10_000)                                          // 等 10 秒
        currentOnTimeout()                                     // 時間到才讀，拿到的是最新的 callback
    }
}
```

**不這樣寫會怎樣。** 直接呼叫 `onTimeout`，effect 抓著的是第一次進來的舊版 callback；把 `onTimeout` 放進 `LaunchedEffect` 的 key，拿得到新的，但每次 callback 換了 effect 就被取消重啟，10 秒重新開始計（Kotlin07 演練 5）。

> [!TIP]
> 面試時可以這樣說：「remember 只活過重組，rememberSaveable 還撐得過旋轉和行程被殺，但只存得了 Bundle 放得下的型別，自訂 class 要給 Saver。欄位少用 listSaver，欄位多或之後會增加用 mapSaver。要特別小心新增欄位卻忘了改 Saver，旋轉後那個欄位會默默歸零，所以我會幫 Saver 寫存了再還原的測試。長時間運作的 effect 要讀最新的 callback，用 rememberUpdatedState，不要放進 key。」

### 6. 價格超過警示線才顯示紅字：`derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**三個 API 的方向不同：`derivedStateOf` 是 State 變成另一個 State，目的是「輸入變很頻繁、結果很少變時，減少通知」；`snapshotFlow` 是 State 變成 Flow；`produceState` 是 suspend 函式或 callback 變成 State。** Kotlin07 第 6 題列過方向對照，這裡講怎麼選，以及最容易搞混的 `derivedStateOf` 和 `remember(key)`、直接計算三者的差別。

#### `derivedStateOf`

**這是什麼。** 用一個計算式，從別的 State 算出新的 State；只有算出來的「結果」真的變了，才通知讀它的 Composable。

**什麼時候用。** 即時價格每秒變幾十次，但紅字只在「超過警示線」和「回到警示線以下」這兩個狀態換邊時才需要變。

**最小程式碼。**

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    val over by remember(alertLine) {                            // alertLine 是一般參數，要當 remember 的 key
        derivedStateOf { price.value > alertLine }               // price 是 State：它變就重算，但只有 true／false 換邊才通知
    }
    if (over) Text("價格超過警示線", color = Color.Red)           // 只在 over 換邊時重組
}
```

**不這樣寫會怎樣。** 直接算，`val over = price.value > alertLine`：Composable 自己讀了 `price.value`，每次價格變，這個 Composable 就重組一次，儘管 `over` 根本沒換邊。`derivedStateOf` 把「價格」藏在計算式裡，Composable 讀的是結果，只在結果換邊時才重組。

**`derivedStateOf`、`remember(key)`、直接算，差在哪？**

| | 直接算 | `remember(key) { 計算 }` | `remember { derivedStateOf { 計算 } }` |
|---|---|---|---|
| 什麼時候重算 | 每次這個 Composable 重組 | 組合時發現 key 和上次不一樣 | 計算式裡讀過的 State 變了 |
| 追蹤什麼 | 函式裡讀到的 State | 你放進 key 的一般參數 | **State 的讀取**，一般參數它看不到 |
| 這個 Composable 什麼時候重組 | 輸入每變一次就重組一次 | 沒有通知，重算只發生在 Composable 重組時 | 結果真的變了，才重組 |
| 適合 | 輸入不常變，或結果本來就跟著輸入變 | 參數變了要重算（排序、篩選、格式化） | State 變很頻繁、結果很少變（有沒有超過門檻） |

口訣是：**一般參數放 `remember` 的括號（key），State 放進 `derivedStateOf` 的大括號裡讀**。上面 `PriceAlert` 兩個都用到了，所以合在一起寫。

`derivedStateOf` 常見的誤用有三個：

1. **漏了 `remember`。** 沒有 `remember`，每次重組都建立一個新的 derived state，快取等於沒有。本機實測：外層重組 10 次，沒有 `remember` 的計算式執行了 11 次，有 `remember` 的只執行 1 次。
2. **結果跟著輸入一起變。** `derivedStateOf { price * qty }`、`derivedStateOf { text.trim() }`，輸入每變一次結果也跟著變，沒有省到任何通知，反而多了一層，直接計算就好。
3. **依賴一般參數卻沒當 key（演練 6）。** 沒有 key 的 `remember { derivedStateOf { price.value > alertLine } }` 會一直用第一次進來的 `alertLine`，使用者把警示線調高了，畫面還是顯示「超過」。

#### `snapshotFlow`

**這是什麼。** 在大括號裡讀 State，回傳一個 Flow（Kotlin 的非同步資料串流，一個值接一個值送出，用 `collect` 接收，第 7 題會講種類）。開始收集時先送出目前的值，之後裡面讀過的 State 變了，就重新計算並送出新值。

**什麼時候用。** 想在 State 改變時「做一件事」，而不是「畫出東西」：例如使用者選的交易對變了，回報給分析（analytics）。

**最小程式碼。**

```kotlin
LaunchedEffect(Unit) {                                       // 進入畫面時啟動 coroutine，離開時取消
    snapshotFlow { symbol }                                  // 大括號裡讀 State：symbol 變了就重新計算
        .collect { analytics.log("symbol_changed", it) }     // 每收到一個新值，回報一次
}
```

連續相同的值會被去重（本機實測：依序寫入 1、1、2、2，只收到 0、1、2；`count > 0` 這種布林值只收到 false、true），來不及處理的中間值會被合併。

**不這樣寫會怎樣。** 把 State 先讀出來再放進 lambda：`val s = symbol; snapshotFlow { s }`，lambda 裡沒有讀 State，永遠只有第一個值。另外，沒有 Compose 在跑的環境（單元測試）收不到後續的變化（見下面的 Snapshot 與演練 8）。

#### `produceState`

**這是什麼。** 把 suspend 函式或 callback 的結果，變成 State。

**什麼時候用。** 表單要顯示伺服器時間（一次性的 suspend 載入），或價格來自 callback 風格的 socket。

**最小程式碼。**

```kotlin
@Composable
fun rememberServerTime(client: TimeClient): State<Long?> =
    produceState<Long?>(initialValue = null, client) {     // initialValue：結果回來前顯示什麼；client 換了就重新執行，離開畫面就取消
        value = client.fetchServerTime()                    // suspend 函式，結果寫進 value
    }

@Composable
fun rememberLastPrice(socket: PriceSocket): State<Long> =
    produceState(initialValue = 0L, socket) {
        val listener = PriceSocket.Listener { value = it }  // callback 風格的 API：收到價格就寫進 value
        socket.addListener(listener)
        awaitDispose { socket.removeListener(listener) }     // 離開畫面時解除，不然 listener 一直掛在 socket 上
    }
```

**不這樣寫會怎樣。** callback 沒有用 `awaitDispose` 解除，畫面離開了 listener 還掛著（Kotlin07 演練 6）。它的 key 規則和 `LaunchedEffect` 完全相同（Kotlin07 演練 2）。資料來源如果是 Flow，也可以寫 `produceState(initial) { flow.collect { value = it } }`，但這正是 `collectAsState` 內部做的事，所以直接用第 7 題的 `collectAsStateWithLifecycle`，不要自己寫。

#### Snapshot 是什麼？

Snapshot 是 Compose 管理 State 的機制，可以想成一本「State 的讀寫記錄本」，有三個概念：

- **讀取追蹤。** Composable、`derivedStateOf`、`snapshotFlow` 執行時，Compose 會記下它們讀了哪些 State；那些 State 之後被寫入，才去通知它們。這就是為什麼「沒有在 lambda 裡讀 State」就收不到變化。
- **寫入要「套用並通知」，別人才看得到。** 有 Compose 畫面在跑的時候，State 一被寫入，Compose 就會自動做這件事。沒有 Compose 的環境（單元測試）沒有人做，`snapshotFlow` 就收不到後續的變化，要自己呼叫 `Snapshot.sendApplyNotifications()`（演練 8；本機實測：呼叫之前只收到第一筆，呼叫之後收到最後一筆）。
- **同時寫多個 State，要一起生效：** `Snapshot.withMutableSnapshot { a = 1; b = 2 }`，離開區塊時一次套用，別人不會看到只改了一半的狀態。

`SideEffect` 也和這個機制有關：它在這次組合成功並套用到畫面之後才執行，做到一半被放棄的組合不會執行（Kotlin07 第 4 題）。所以要把「這次組合確定的結果」交給 Compose 以外的程式（舊的 View、第三方圖表）時，放在 `SideEffect` 裡，不要直接寫在 Composable 的函式內容裡。

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤的是 State 的讀取，適合輸入變很頻繁、輸出很少變，要包 remember；remember(key) 追蹤的是我放進去的參數。兩個常常合起來用：remember(param) { derivedStateOf { … } }。snapshotFlow 是 State 變成 Flow，produceState 是 suspend 或 callback 變成 State。底層都是 snapshot 的讀取追蹤，所以沒有 Compose 在跑的環境，例如單元測試，snapshotFlow 不會自己更新。」

### 7. ViewModel 給的價格 Flow 要顯示在畫面上、退到背景不要收：`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 只適合不在乎 Android lifecycle 的情況。** 兩者名字只差 `WithLifecycle` 這幾個字，行為差在 App 退到背景之後：`collectAsState()` 只要畫面還在 composition 裡就一直收，`collectAsStateWithLifecycle()` 會在退到背景時取消收集，回到前景再重新收集。

先把幾個名詞講清楚：

- **lifecycle（生命週期）** 是 Android 畫面從建立、可見、在前景可操作，到退到背景、銷毀的各個階段。**`STARTED`** 是其中一個：畫面看得到（Activity 已經 start）。App 退到背景，lifecycle 就回到 `STARTED` 以下。
- **Flow** 是 Kotlin 的非同步資料串流，用 `collect` 接收。**cold Flow** 是「有人 `collect` 才開始跑，每個收集者各跑一份」，像按下播放才開始的影片，例如每次收集就重新連一次 WebSocket 的 Flow。**hot Flow** 是「不管有沒有人收都存在」，像廣播電台，收集者只拿到加入之後的資料。
- **`StateFlow`** 是 hot Flow 的一種：永遠保存最新的一個值，可以讀 `.value`，新的收集者一加入就馬上拿到目前的值。**`SharedFlow`** 是沒有「目前值」的 hot Flow，常用來廣播一次性事件。
- **`stateIn`** 是把 cold Flow 轉成 `StateFlow` 的運算子。**`WhileSubscribed(5000)`** 是它的設定：沒有人收集之後，再等 5 秒才停掉上游。這 5 秒是為了撐過旋轉螢幕那一瞬間「收集者消失又出現」，使用者 5 秒內回來，上游就不會停（Kotlin05 第 5 題）。

兩個 API 並排比較：

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 來自哪個套件 | Compose 本體（`androidx.compose.runtime`） | `androidx.lifecycle:lifecycle-runtime-compose`，要自己加依賴（本單元演練專案用 2.9.4） |
| 什麼時候收集 | 進入 composition 就開始，離開才停 | lifecycle 至少到 `minActiveState` 才收，預設是 `STARTED` |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集；回到前景重新收集 |
| 適合 | 不是 Android，或這個畫面刻意要在背景也持續更新 | Android 畫面（行情、訂單、餘額） |

#### `collectAsStateWithLifecycle()`

**這是什麼。** 把 Flow 變成 State，而且跟著 Android lifecycle 開始與停止收集。

**什麼時候用。** 表單上那條即時價格，來源是 ViewModel 的 `StateFlow`；Android 畫面上要顯示 Flow 的資料，預設都用它。

**最小程式碼。**

```kotlin
// build.gradle.kts：implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
val uiState by vm.uiState.collectAsStateWithLifecycle()                          // StateFlow 有目前值，不用給 initial
val lastPrice by vm.priceFlow.collectAsStateWithLifecycle(initialValue = null)   // 一般 Flow：第一個值來之前顯示什麼，必須自己給
Text(lastPrice?.let { "$it JPY" } ?: "--")                                       // null 代表還沒收到，顯示 -- 而不是 0
```

**不這樣寫會怎樣。** 用 `collectAsState()`，本機實測（演練 7，伺服器每 50 ms 推一筆價格）：App 退到背景 0.5 秒，畫面又更新了 9 次，行情還有 1 個收集者在收；用 `collectAsStateWithLifecycle()` 是 0 次、0 個，回到前景後馬上顯示最新的價格。在手機上，這就是耗電、耗流量的差別。`minActiveState` 預設是 `STARTED`（畫面看得到）；改成 `RESUMED` 的話，被別的視窗搶走焦點、Activity 進入 paused 時也會停。再搭配 ViewModel 裡的 `stateIn(WhileSubscribed(5000))`，畫面離開超過 5 秒，連 WebSocket 訂閱都會停掉。

`collectAsStateWithLifecycle` 內部在 Android 的主執行緒上收集（Kotlin coroutines 裡叫 `Dispatchers.Main`），所以純 JVM 的單元測試沒有主執行緒可用，演練 7 要在手機或模擬器上跑。

**用哪一種 Flow 來收？**

| 來源 | 怎麼收 | initial 怎麼給 |
|---|---|---|
| `StateFlow<T>` | `collectAsStateWithLifecycle()` | 不用給，`StateFlow` 一定有目前值 |
| 一般 `Flow<T>`（cold） | `collectAsStateWithLifecycle(initialValue)` | 一定要給：第一個值來之前，畫面要顯示什麼 |
| `SharedFlow<T>`（事件） | 通常不轉成 State，用 `LaunchedEffect` 收 | State 只保留最新值，快速連發的事件會漏 |

cold Flow 要小心：每次回到前景都會重新收集，等於上游從頭再跑一次（例如重開 WebSocket）。所以 cold Flow 通常先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，畫面端只收 `StateFlow`。

**`initial` 不要給看起來像真的數字。** 價格先顯示 `0`、餘額先顯示 `0 JPY`，會誤導使用者以為價格真的是 0。用 `null` 或 `Loading` 這種明確的「還沒有資料」。

還有一個容易犯的錯：**在 Composable 裡直接讀 `stateFlow.value`。** 它不會觀察變化，值變了畫面不會更新。Compose 的 lint `StateFlowValueCalledInComposition` 會警告；要用 `collectAsStateWithLifecycle()`。

#### `LiveData.observeAsState()`

**這是什麼。** 舊專案的 ViewModel 如果還在用 `LiveData`（Android 較早的可觀察資料類別），Compose 這邊用 `observeAsState()` 把它變成 State。要加依賴 `androidx.compose.runtime:runtime-livedata`。

**什麼時候用。** 舊畫面的 ViewModel 還是 LiveData，不必為了統一而大改。LiveData 本身就會跟著 lifecycle，只在畫面可見時才送資料，所以不需要另一個 `WithLifecycle` 版本。

```kotlin
val price by vm.priceLiveData.observeAsState(initial = 0L)   // State<Long>
val maybe by vm.priceLiveData.observeAsState()               // State<Long?>：沒給 initial，還沒有值時是 null
```

**不這樣寫會怎樣。** 在 Composable 裡直接讀 `liveData.value`，和讀 `stateFlow.value` 一樣不會觀察變化。新寫的程式碼用 `StateFlow`。

> [!TIP]
> 面試時可以這樣說：「Android 畫面我用 collectAsStateWithLifecycle，預設在 STARTED 才收集，App 退到背景就取消，回前景再重新收，不會在背景白白重組。collectAsState 不管 lifecycle，所以不用在 Android 畫面。StateFlow 直接收；cold Flow 我會先在 ViewModel 用 stateIn 轉成 StateFlow，initial 給 null 或 Loading，不給 0 這種看起來像真的數字。舊的 LiveData 用 observeAsState 並存。」

### 8. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**畫面的業務狀態預設用 `StateFlow`（對外是 `StateFlow`，內部是 `MutableStateFlow`），畫面端用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。** ViewModel 用 `mutableStateOf` 也能運作，但變化只有 Compose 看得到。

下單表單有三種狀態，放的地方不一樣：數量輸入框的文字只有這個畫面在乎，放在 Composable（`rememberSaveable`）；即時價格和委託是業務資料，要打 API、要撐過旋轉，放 ViewModel 的 `StateFlow`；按下「送出」之後的下單工作必須做完，放 ViewModel 的 `viewModelScope`。

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者（Worker、單元測試、View 畫面、KMP 共用程式） | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，而且沒有 Compose 在跑就收不到變化（演練 8） |
| Flow 運算子（`combine`、`debounce`、`stateIn`） | 直接用 | 要先用 `snapshotFlow` 轉一次 |
| 原子更新 | `_state.update { it.copy(…) }` | 沒有同等的 API |
| 畫面端的寫法 | 要寫 `collectAsStateWithLifecycle()` 並處理 initial | 直接讀 `vm.state`，最簡單 |
| lifecycle | 搭配 `WithLifecycle`，背景會停 | 沒有收集動作，永遠是最新值，沒有停的機制 |
| ViewModel 的依賴 | 不需要 Compose | 依賴 Compose runtime |

Worker 是 WorkManager 在背景執行工作的元件，KMP 是 Kotlin Multiplatform（同一份 Kotlin 程式碼給多個平台用）。「原子更新」的意思是：多個 coroutine 同時修改時，不會互相蓋掉彼此的修改（`update { }` 用比較並交換重試）。單元測試也是同樣的差別：`StateFlow` 用 `runTest` 加 Turbine（測試 Flow 的函式庫）就能驗證每一筆；`mutableStateOf` 讀 `.value` 沒問題，但要「等變化」就得自己處理 snapshot 的通知。

有一個例外是**輸入框的即時文字**。文字如果繞一圈非同步的 Flow 再回到畫面，更新會比輸入法晚一步，輸入中文、日文這類要組字的輸入法時，可能出現游標跳動等怪現象。常見的做法是輸入框的即時文字放在畫面自己的 State，需要時（例如按下送出）才交給 ViewModel。

**狀態到底放哪一層？** 跟著它「誰在乎、要活多久」來決定。「state hoisting（狀態提升）」是把狀態從子 Composable 移到呼叫它的上層，子 Composable 只收「值」和「事件的 callback」，完整的說明見 Kotlin09 第 1 題。

| 這個狀態… | 放哪裡 |
|---|---|
| 只有這個 Composable 在乎（展開或收合、選了哪個分頁） | `remember`；要撐過旋轉用 `rememberSaveable` |
| 兩個以上的兄弟 Composable 共用 | 提到它們共同的上層 Composable |
| 畫面邏輯，不依賴 domain（捲動、動畫、表單驗證） | state holder 類別，搭配 `rememberXxxState()` |
| 業務資料（行情、訂單、餘額），要打 API、要撐過旋轉 | ViewModel 的 `StateFlow` |
| 行程被殺後還要還原 | `rememberSaveable` 或 `SavedStateHandle` |

沒有 ViewModel 的畫面邏輯，可以用 `rememberCoroutineScope` 搭配 `MutableStateFlow` 寫一個 state holder（把畫面邏輯收在一個普通 class 裡）：scope 讓它能在事件裡啟動 coroutine，`MutableStateFlow` 讓畫面和別的程式都能收到變化。

```kotlin
class PriceHolder(private val repo: PriceRepo, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<PriceState>(PriceState.Loading)   // 內部可寫
    val state: StateFlow<PriceState> = _state.asStateFlow()                  // 對外唯讀

    fun refresh() {
        scope.launch { _state.value = PriceState.Loaded(repo.fetch()) }      // 事件裡啟動 coroutine，結果寫進 state
    }
}

@Composable
fun rememberPriceHolder(repo: PriceRepo): PriceHolder {
    val scope = rememberCoroutineScope()                  // 綁在這個 Composable 上，離開畫面就取消
    return remember(repo, scope) { PriceHolder(repo, scope) }
}
```

限制是：holder 跟著 Composable，旋轉螢幕就重建，狀態會重來；scope 在離開畫面時取消，所以不要拿來做送出訂單這種必須做完的工作（Kotlin07 第 5 題）。要撐過旋轉或必須做完的，放 ViewModel。

> [!TIP]
> 面試時可以這樣說：「ViewModel 的畫面狀態我用 StateFlow：非 Compose 的程式也能收，可以用 Flow 運算子，update 是原子更新，ViewModel 也不用依賴 Compose。mutableStateOf 在 ViewModel 裡能用，但只有 Compose 看得到變化，單元測試或 Worker 要觀察它就很麻煩。輸入框的即時文字例外，放在畫面的 State 比較不會游標跳動。純畫面元素的狀態留在 Composable，業務狀態放 ViewModel。」

### 9. 一張對照表：情境對應該用哪個 API？

選的時候先問三件事：

1. **資料從哪來？** 自己產生、從 Flow、從 callback，還是從 suspend 函式？
2. **要活多久？** 只活過重組、撐過旋轉、還是行程被殺也要還原？
3. **誰要觀察它？** 只有 Compose 畫面，還是也有 Worker、單元測試、其他畫面？

| 情境 | 用這個 | 補充 | 第幾題 |
|---|---|---|---|
| 計數、開關這類畫面自己的值 | `remember { mutableStateOf(…) }` | 普通的 `var` 不會讓畫面更新 | 1 |
| 展開或收合、選了哪個分頁 | `remember { mutableStateOf(false) }` | 要撐過旋轉就換 `rememberSaveable` | 1、5 |
| 表單輸入到一半，要撐過旋轉 | `rememberSaveable` | 自訂型別要給 `Saver` | 5 |
| 未讀數、計數器、捲動位置（整數） | `mutableIntStateOf` | 讀寫用 `intValue`，避免 boxing | 2 |
| 以最小單位存的價格（`Long`） | `mutableLongStateOf` | 讀寫用 `longValue` | 2 |
| 每秒更新很多次的價格，只想重畫那一行 | 傳 `State` 或 lambda 給小的 Composable，在那裡才讀取 | 不要在上層先讀成 `Long` 再傳 | 2 |
| 自選幣種，一筆一筆增減 | `mutableStateListOf` | 項目用不可變的 data class，改的時候換掉那一格 | 3 |
| symbol 對最新價格的對照表 | `mutableStateMapOf` | | 3 |
| 每秒換一整份訂單簿快照 | `mutableStateOf(不可變的 list)` | 整份換掉，不要一筆一筆改 | 3 |
| 同一個物件就地更新後要通知 | `mutableStateOf(obj, neverEqualPolicy())` | 每次寫入都重組 | 4 |
| `equals` 不適合判斷畫面要不要更新 | `referentialEqualityPolicy()`，或 UI 用 data class | | 4 |
| 計算結果依賴一般參數 | `remember(key) { }` | | 5、6 |
| 長時間運作的 effect 要用最新的 callback | `rememberUpdatedState` | 不要放進 key | 5 |
| 價格超過警示線才顯示紅字 | `remember { derivedStateOf { … } }` | 輸入頻繁、輸出很少變；一般參數放 `remember` 的 key | 6 |
| 把捲動位置或選的交易對回報給 analytics | `snapshotFlow` | 放在 `LaunchedEffect` 裡 | 6 |
| 一次性的 suspend 載入，或 callback 轉成 State | `produceState` | callback 要用 `awaitDispose` 解除 | 6 |
| 同時寫好幾個 State，要一起生效 | `Snapshot.withMutableSnapshot { }` | 別人不會看到只改一半 | 6 |
| 每秒幾十筆行情，只在 STARTED 時收 | `collectAsStateWithLifecycle` | 搭配 `stateIn(WhileSubscribed(5000))` | 7 |
| 一般的 cold Flow 要顯示 | `collectAsStateWithLifecycle(initialValue)` | 或先在 ViewModel 用 `stateIn` | 7 |
| 導航、Toast 這類一次性事件 | 不轉成 State | 用 `LaunchedEffect` 收，或做成 state 裡「待處理的事件」（Kotlin05 第 3 題） | 7 |
| 舊專案的 LiveData | `observeAsState()` | 要加 `runtime-livedata` 依賴 | 7 |
| ViewModel 暴露畫面狀態 | `StateFlow` | 畫面用 `collectAsStateWithLifecycle` | 8 |
| 純畫面邏輯（捲動、表單驗證） | state holder 類別加 `rememberXxxState()` | | 8 |
| 送出訂單這種必須做完的工作 | ViewModel 的 `viewModelScope` | 不是 `rememberCoroutineScope` | 8 |
| ViewModel 的狀態要撐過行程被殺 | `SavedStateHandle` | 只放小量資料 | 5、8 |

> [!TIP]
> 面試時可以這樣說：「我選狀態 API 先問三件事：資料從哪來、要活多久、誰要觀察。表單輸入用 rememberSaveable，頻繁更新的數字用 primitive 版的 State，價格超過門檻用 derivedStateOf，要回報狀態變化用 snapshotFlow，行情用 collectAsStateWithLifecycle 只在前景收，業務狀態放 ViewModel 的 StateFlow。」

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

| 需求 | Compose | SwiftUI |
|---|---|---|
| 描述畫面的函式 | `@Composable` 函式 | `View` 的 `body` |
| 狀態改變後，畫面重新計算 | 重組（recomposition），只重新執行讀過那個 State 的 Composable | `body` 重新計算 |
| 畫面自己的狀態 | `remember { mutableStateOf(…) }` | `@State` |
| 讓子畫面改父畫面的值 | 值往下傳、事件（lambda）往上傳 | `@Binding` |
| 撐過旋轉、系統回收後重開 | `rememberSaveable`（小量資料）、ViewModel 搭配 `SavedStateHandle` | `@SceneStorage` |
| ViewModel 的畫面狀態 | `StateFlow` 搭配 `collectAsStateWithLifecycle()` | `@Observable` 類別（iOS 17 起）或 `ObservableObject` 搭配 `@Published` |
| 從頻繁的輸入推導出變化少的輸出 | `derivedStateOf` | 沒有直接對應：用計算屬性，或把 view 拆小 |
| 跟著畫面生命週期收資料 | `collectAsStateWithLifecycle()` 自動處理 | `.task` 在 view 消失時會取消；App 退到背景要自己看 `scenePhase` |

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
