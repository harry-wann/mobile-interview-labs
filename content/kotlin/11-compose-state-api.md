---
id: kotlin-compose-state-api
title: Compose 的狀態 API 全覽：mutableStateOf、remember、derivedStateOf、collectAsState 怎麼選
summary: Compose 的狀態 API 很多，面試常問「mutableStateOf 和 StateFlow 差在哪」「rememberSaveable 存不了自訂型別怎麼辦」「collectAsState 和 collectAsStateWithLifecycle 差在哪」。這個單元把建立、記住、衍生與觀察、從外部資料轉成 State 這四類 API 一個一個列出來，每個都講清楚什麼時候用、不用會怎樣、和相鄰的 API 差在哪，最後給一張「情境對應 API」的速查表。
priority: P0
day: 5
tags: [compose, state, mutablestateof, remember, remembersaveable, saver, derivedstateof, snapshotflow, collectasstatewithlifecycle, stateflow]
---

## 重點講解

這個單元回答七個面試常見問題：

1. `mutableStateOf` 和它的家族（`mutableIntStateOf`、`mutableStateListOf` 等）該怎麼選？
2. `mutableStateOf` 怎麼判斷「值變了沒」？什麼時候要換 policy？
3. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？
4. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？
5. 外部資料怎麼變成 State？`collectAsState` 還是 `collectAsStateWithLifecycle`？
6. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？
7. 一張對照表：情境對應該用的 API

先講這個單元的定位。Compose 的狀態 API 雖然多，其實只在回答兩個問題：**值放在哪裡**（建立、記住），以及**值從哪裡來**（從別的 State 衍生、從 Flow 或 callback 轉進來）。重組怎麼運作，Kotlin06 講過；`LaunchedEffect` 這類 effect API，Kotlin07 講過；Flow 的種類與 `stateIn`，Kotlin05 講過。這裡不重講，只聚焦「該選哪一個」，需要時簡短帶過並指回去。

### 1. `mutableStateOf` 和它的家族，該怎麼選？

**一個值用 `mutableStateOf`；數字用 primitive 版的 `mutableIntStateOf`、`mutableLongStateOf`、`mutableFloatStateOf`、`mutableDoubleStateOf`；會一筆一筆增減的清單與對照表用 `mutableStateListOf`、`mutableStateMapOf`。** 這些建立出來的都是 Compose 追蹤的 State：寫入新值，讀過它的 Composable 才會重組。

先分清楚兩個介面：`State<T>` 只能讀（只有 `value`），`MutableState<T>` 可以讀也可以寫。慣例是對外只給唯讀的，只有擁有者能寫：

```kotlin
class OrderFormState {
    var qty by mutableStateOf("")     // 外面讀得到
        private set                    // 只有這個 class 自己能寫

    fun onQtyChange(input: String) { qty = input.filter { it.isDigit() || it == '.' } }
}
```

`by` 和 `.value` 是同一件事的兩種寫法。`by` 是語法糖（讓你像用一般變數那樣讀寫，要 import `androidx.compose.runtime.getValue` 與 `setValue`）。差別在於**讀取發生在「用到變數的那一行」**，不是宣告的那一行，這是 Kotlin08 延後讀取的關鍵：

```kotlin
var offsetX by remember { mutableIntStateOf(0) }

val a = offsetX                         // 在這裡讀：Composable 的函式內容裡讀，變了就重組
Box(Modifier.offset { IntOffset(offsetX, 0) })   // 寫在 lambda 裡：等到 Layout 階段才讀，變了不用重組
```

**為什麼要有 primitive 版？**（Compose runtime 1.5 起提供，本專案的 Compose BOM 2025.12.00 都有。）`MutableState<Int>` 的 `Int` 是泛型參數，在 JVM 上只能存 `java.lang.Integer` 物件，每次寫入都要把 `Int` 包成物件（boxing，也叫自動裝箱）。Integer 只有 -128 到 127 會共用快取，超過就是每次新建一個物件。每秒更新很多次的計數、位置、價格，會製造一堆短命的物件。`mutableIntStateOf` 直接存 `int`，用 `intValue` 讀寫，沒有這個成本。幾個細節：

- 價格建議用 `Long`（最小單位），對應 `mutableLongStateOf`，讀寫用 `longValue`。
- `Boolean` 沒有 primitive 版，因為包起來的 `Boolean` 只有兩個共用的物件（`TRUE`、`FALSE`），不會產生新物件。
- 用了 primitive 版卻還去讀 `.value`，一樣會 boxing。Compose 的 lint 會提醒：`AutoboxingStateCreation`（該用 primitive 版建立）和 `AutoboxingStateValueProperty`（該讀 `intValue`）。

**集合怎麼選？** 三種寫法行為不同：

| 寫法 | 怎麼更新 | Compose 什麼時候知道 |
|---|---|---|
| `mutableStateOf(listOf(...))` | 整個換成新 list：`ticks = ticks + tick` | State 的值被換掉時 |
| `mutableStateOf(mutableListOf(...))` | 原地 `add` | **不會知道**（Kotlin06 演練 2；lint `MutableCollectionMutableState` 會警告） |
| `mutableStateListOf(...)` | `add`、`removeAt`、`set`、`clear` | 清單的內容改變時 |

`mutableStateMapOf` 和 `mutableStateListOf` 同理，適合「symbol 對最新價格」這種一筆一筆更新的對照表。已經有現成的集合，用 `toMutableStateList()` 轉成 `SnapshotStateList`；一串 `Pair` 用 `toMutableStateMap()` 轉成 `SnapshotStateMap`。

`SnapshotStateList` 有四個注意事項：

1. **它只認得「清單本身」的改變，不看項目裡面。** 項目如果是 `class OrderRow(var filled: Double)`，原地改 `filled`，清單沒變，畫面不會更新。項目要用 `data class` 加 `val`，改的時候換掉那一格：`orders[1] = orders[1].copy(filled = 0.5)`（演練 1）。
2. **一邊迭代一邊修改會丟 `ConcurrentModificationException`**，和一般的 `MutableList` 一樣。要刪除符合條件的項目，用 `removeAll { }`。
3. **預設存不進 `rememberSaveable`**，要自己寫 Saver（第 3 題）。
4. **適合一筆一筆增減**。如果每次都是整份資料換掉（例如每秒收到一份 20 檔的訂單簿快照），`mutableStateOf` 放一份新的不可變 list 比較單純（Kotlin08 的 `ImmutableList`）。

> [!TIP]
> 面試時可以這樣說：「單一值用 mutableStateOf，數字用 mutableIntStateOf 這類 primitive 版，避免每次寫入都 boxing。清單用 mutableStateListOf，原地修改一般的 MutableList 不會通知 Compose。要注意 SnapshotStateList 只看清單內容的改變，項目要用不可變的 data class，改的時候換掉那一格。」

### 2. `mutableStateOf` 怎麼判斷「值變了沒」？什麼時候要換 policy？

**預設用 `equals` 比較新舊值，相等就把這次寫入當作沒發生；`mutableStateOf` 的第二個參數可以換成 `referentialEqualityPolicy()`（用 `===`，同一個物件才算沒變）或 `neverEqualPolicy()`（永遠算變了）。** 這個「判斷新舊值算不算一樣的規則」，正式名稱叫 `SnapshotMutationPolicy`。

| policy | 怎麼判斷「一樣」 | 什麼時候用 |
|---|---|---|
| `structuralEqualityPolicy()`（預設） | `a == b`（`equals`） | 絕大多數情況：字串、數字、data class |
| `referentialEqualityPolicy()` | `a === b`（同一個物件） | `equals` 很貴（幾千筆的 list），或 `equals` 不適合拿來決定畫面要不要更新 |
| `neverEqualPolicy()` | 永遠不一樣 | 同一個物件就地修改後要通知；把 State 當作「觸發器」 |
| 自訂 `SnapshotMutationPolicy` | 自己寫 `equivalent(a, b)` | 只比畫面上看得到的欄位 |

```kotlin
data class Tick(val symbol: String, val price: Long, val receivedAt: Long)

// 1. 預設：equals 相同，寫入被丟掉（連 State 裡存的都不會被換成新物件）
val a = mutableStateOf(Tick("BTC_JPY", 100, receivedAt = 1))
a.value = Tick("BTC_JPY", 100, receivedAt = 1)      // 內容完全一樣的新物件：不重組

// 2. referentialEqualityPolicy：換成另一個物件才算變了
val b = mutableStateOf(order, referentialEqualityPolicy())

// 3. neverEqualPolicy：同一個物件就地更新，再寫一次來通知
val book = mutableStateOf(orderBook, neverEqualPolicy())
orderBook.update(101, 1.5)
book.value = orderBook                               // 同一個物件，照樣通知

// 4. 自訂：行情每筆都帶接收時間，價格沒變就不該重組，只比 symbol 和 price
val tickPolicy = object : SnapshotMutationPolicy<Tick> {
    override fun equivalent(a: Tick, b: Tick) = a.symbol == b.symbol && a.price == b.price
}
val tick = mutableStateOf(Tick("BTC_JPY", 100, 1), tickPolicy)
```

三個實際會遇到的情境：

- **`equals` 只比 id 的領域物件（演練 2）。** 訂單 `A1` 的狀態從 New 變成 Filled，`equals` 只比 `id`，所以新舊 Order「相等」，這次寫入被丟掉，畫面停在 New。換成 `referentialEqualityPolicy()` 可以解決，但更好的做法是 UI 用的 model 用 `data class`，`equals` 會比所有欄位。
- **就地更新的大物件（演練 3）。** 訂單簿引擎為了效率，內部就地更新、整個 App 共用一個物件。更新之後再寫一次同一個物件，預設會被當成沒變；`neverEqualPolicy()` 讓它每次都通知。代價是每次寫入都重組，連內容沒變的寫入也一樣。
- **「再送一次相同的錯誤訊息，要再顯示一次 Toast」，不是 `neverEqualPolicy` 的工作。** 用它確實會讓讀這個 State 的 Composable 重組（本機實測：寫入同一個字串兩次，重組了兩次），但 `LaunchedEffect(message)` 的 key 還是用 `equals` 比較，相同的字串不會重新啟動，Toast 一樣不會再出現。一次性事件請包成帶 id 的物件（`data class UiMessage(val id: Long, val text: String)`），或照 Kotlin05 第 3 題的做法。

另外，`derivedStateOf` 的結果預設也是用 `equals` 判斷有沒有變，同樣可以傳 policy：`derivedStateOf(referentialEqualityPolicy()) { … }`。

> [!TIP]
> 面試時可以這樣說：「mutableStateOf 預設用 equals 判斷，相等的寫入會直接被丟掉。我會在三種情況換 policy：equals 太貴或不適合時用 referentialEqualityPolicy；同一個物件就地更新要通知時用 neverEqualPolicy；只想比畫面上看得到的欄位時自訂 policy。但多數時候更好的做法是讓 UI model 是不可變的 data class，不用動 policy。」

### 3. `remember`、`rememberSaveable`、`rememberUpdatedState` 怎麼選？`Saver` 怎麼寫？

**看資料要活多久：只要活過重組，用 `remember`；還要撐過旋轉螢幕、App 行程被系統殺掉後重開，用 `rememberSaveable`（只放小量資料）；長時間運作的 effect 要讀到最新的 callback 又不想重啟，用 `rememberUpdatedState`；業務資料要撐過旋轉，放 ViewModel。**

| 我要的 | 用這個 | 補充 |
|---|---|---|
| 值活過重組；輸入變了要重算 | `remember(key) { }` | key 是「會影響計算結果的輸入」（Kotlin06 第 6 題） |
| 值還要撐過旋轉、行程被殺 | `rememberSaveable` | 輸入中的文字、選取的分頁；不放大型資料 |
| 長時間運作的 effect 要最新 callback，又不想重啟 | `rememberUpdatedState` | 不放進 key（Kotlin07 第 3 題） |
| 業務資料要撐過旋轉 | ViewModel | 行程被殺還要還原，搭配 `SavedStateHandle` |

`remember` 的 key 與 `rememberUpdatedState` 在前兩個單元有完整的演練，這裡只記重點。`remember { }` 的計算只在第一次進入畫面時執行，計算用到的輸入要當成 key，例如 `remember(tickSize) { PriceFormatter(tickSize) }`；沒寫 key，切換交易對之後格式還停在上一個。`rememberUpdatedState` 處理另一個方向的問題：`LaunchedEffect(Unit)` 只啟動一次，之後外面換了新的 callback，effect 還在呼叫第一次的舊版本；把 callback 放進 key 雖然拿得到新的，但 effect 會被取消重啟，計時重新開始。`val current by rememberUpdatedState(onTimeout)` 讓 effect 不重啟，執行到那一刻卻讀到最新的 callback。

旋轉和行程被殺是兩件事。旋轉螢幕時，Activity 被銷毀再重建，但 App 的行程還在，所以 ViewModel 活著；行程被殺是 App 在背景時系統為了回收記憶體，把整個行程殺掉，使用者回來時系統重建畫面並還原先前存的狀態（saved instance state），ViewModel 也跟著消失。`rememberSaveable` 存的就是 saved instance state，兩種情況都撐得過。要測試行程被殺：App 退到背景，執行 `adb shell am kill <套件名稱>`，再從最近使用的 App 點回來。

**`rememberSaveable` 存得了什麼？** 預設只存得了 Bundle（Android 用來存狀態的容器）放得下的型別：`String`、數字、`Boolean` 這類基本型別，以及實作了 `Parcelable`（Android 的序列化介面，通常用 `@Parcelize` 產生）或 `Serializable` 的型別。**存不了的：一般的 data class（沒實作上述介面）、`SnapshotStateList`、lambda。** 這時組合畫面的當下就會丟 `IllegalArgumentException`，訊息是「cannot be saved using the current SaveableStateRegistry」，手機上就是畫面一打開就閃退（演練 4）。解法是給 `Saver`：告訴 Compose 怎麼把物件拆成存得了的東西，以及怎麼組回來。

| 做法 | 適合 | 缺點 |
|---|---|---|
| `@Parcelize` 加 `Parcelable` | 這個型別本來就要放進 Intent 或 Bundle 傳遞 | 要套用 Parcelize plugin |
| `listSaver` | 欄位少而且固定 | 用位置對應（`it[0]`、`it[1]`），加欄位或換順序容易對錯 |
| `mapSaver` | 欄位多，之後可能增加 | 用 key 對應，漏存的欄位不會報錯（演練 5） |
| `Saver(save, restore)` | 單一值的型別，例如把金額存成字串 | 自己寫 |

```kotlin
data class OrderDraft(val symbol: String, val qty: Double)

// listSaver：拆成 List，用位置對應
val draftSaver = listSaver<OrderDraft, Any>(
    save = { listOf(it.symbol, it.qty) },
    restore = { OrderDraft(it[0] as String, it[1] as Double) },
)

// mapSaver：拆成 Map，用 key 對應
val draftMapSaver = mapSaver(
    save = { mapOf("symbol" to it.symbol, "qty" to it.qty) },
    restore = { OrderDraft(it["symbol"] as String, it["qty"] as Double) },
)

// 自訂 Saver：單一值
val amountSaver = Saver<BigDecimal, String>(
    save = { it.toPlainString() },
    restore = { BigDecimal(it) },
)

// 放 MutableState 的用 stateSaver
var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(OrderDraft("BTC_JPY", 0.1)) }
var amount by rememberSaveable(stateSaver = amountSaver) { mutableStateOf(BigDecimal.ZERO) }

// 放一般物件的用 saver，例如 SnapshotStateList：存成 List，還原時轉回來
val symbols = rememberSaveable(
    saver = listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() },
        restore = { it.toMutableStateList() },
    ),
) { mutableStateListOf("BTC_JPY") }
```

使用 `Saver` 有三個重點：

- **欄位多了，Saver 要跟著改。** 最危險的是 `mapSaver` 或 `listSaver` 沒有加上新欄位，`restore` 用預設值補，編譯器不會提醒，旋轉之後那個欄位就靜悄悄地歸零（演練 5）。給 Saver 寫一個單元測試：存起來再還原，確認每個欄位都一樣。
- **`rememberSaveable(vararg inputs)` 的 `inputs`** 和 `remember` 的 key 一樣：任何一個變了，之前存的值就丟掉、重新初始化。例如 `rememberSaveable(symbol) { mutableStateOf("") }`，切換交易對就清空輸入。
- **ViewModel 要撐過行程被殺，用 `SavedStateHandle`。** `savedStateHandle.getStateFlow("qty", "")` 讀、`savedStateHandle["qty"] = value` 寫。它和 `rememberSaveable` 一樣放進 Bundle，所以同樣只適合小量資料；大型資料放資料庫或 repository。

> [!TIP]
> 面試時可以這樣說：「remember 只活過重組，rememberSaveable 還撐得過旋轉和行程被殺，但只存得了 Bundle 放得下的型別，自訂 class 要給 Saver。欄位少用 listSaver，欄位多或之後會增加用 mapSaver。要特別小心的是新增欄位卻忘了改 Saver，旋轉後那個欄位會默默歸零，所以我會幫 Saver 寫存了再還原的測試。」

### 4. `derivedStateOf`、`snapshotFlow`、`produceState` 怎麼選？Snapshot 是什麼？

**三個 API 的方向不同：`derivedStateOf` 是 State 變成另一個 State，目的是「輸入變很頻繁、輸出很少變時，減少通知」；`snapshotFlow` 是 State 變成 Flow；`produceState` 是 suspend 函式或 callback 變成 State。** Kotlin07 第 6 題已經列過方向對照，這裡講怎麼選，以及最容易搞混的 `derivedStateOf` 和 `remember(key)`。

**`derivedStateOf` 和 `remember(key)` 差在哪？** 兩個都是「算一個衍生的值」，但追蹤的東西完全不同：

| | `remember(key) { 計算 }` | `remember { derivedStateOf { 計算 } }` |
|---|---|---|
| 什麼時候重算 | 組合時發現 key 和上次不一樣 | 計算式裡讀過的 State 變了 |
| 追蹤什麼 | 你放進 key 的一般參數 | **State 的讀取**，一般參數它看不到 |
| 通知誰 | 沒有通知，重算只發生在 Composable 重組時 | 結果真的變了，才通知讀它的人重組 |
| 適合 | 參數變了要重算（排序、篩選、格式化） | State 變很頻繁、結果很少變（有沒有超過門檻） |

兩個合起來用也很常見：價格是 State（每秒變很多次），警示線是使用者設定、從外面傳進來的一般參數，畫面只關心「有沒有超過警示線」：

```kotlin
@Composable
fun PriceAlert(price: State<Long>, alertLine: Long) {
    // alertLine 是一般參數，要當 remember 的 key；price 是 State，derivedStateOf 自己會追蹤
    val over by remember(alertLine) { derivedStateOf { price.value > alertLine } }
    Text(if (over) "超過警示線" else "未超過警示線")
}
```

`derivedStateOf` 常見的誤用有三個：

1. **漏了 `remember`。** 沒有 `remember`，每次重組都建立一個新的 derived state，快取等於沒有。本機實測：外層重組 10 次，沒有 `remember` 的計算式執行了 11 次，有 `remember` 的只執行 1 次。
2. **輸出跟著輸入一起變。** `derivedStateOf { price * qty }`、`derivedStateOf { text.trim() }`，輸入每變一次結果也跟著變，沒有省到任何通知，反而多了一層，直接計算就好。
3. **依賴一般參數卻沒當 key（演練 6）。** 沒有 key 的 `remember { derivedStateOf { price.value > alertLine } }` 會一直用第一次進來的 `alertLine`，使用者把警示線調高了，畫面還是顯示「超過」。

**`snapshotFlow`** 在大括號裡讀 State，回傳一個 cold Flow：開始收集時先發射目前的值，之後裡面讀過的 State 變了就重新計算並發射新值。連續相同的值會被去重（本機實測：依序寫入 1、1、2、2 只收到 0、1、2；`count > 0` 這種布林值只收到 false、true），而且來不及處理的中間值會被合併。常搭配 `LaunchedEffect` 和 `distinctUntilChanged`、`debounce` 使用，例如「把捲動位置回報給 analytics」。常見的錯誤是把 State 先讀出來再放進 lambda：`val i = state.value; snapshotFlow { i }`，lambda 裡沒有讀 State，永遠只有第一個值。

**`produceState`** 把 suspend 函式或 callback 的結果變成 State。有 callback 要解除時，用 `awaitDispose`：

```kotlin
@Composable
fun rememberServerTime(client: TimeClient): State<Long?> =
    produceState<Long?>(initialValue = null, client) {     // client 換了就重新執行，離開 composition 就取消
        value = client.fetchServerTime()                    // suspend 函式，結果寫進 value
    }

@Composable
fun rememberLastPrice(socket: PriceSocket): State<Long> =
    produceState(initialValue = 0L, socket) {
        val listener = PriceSocket.Listener { value = it }   // callback 風格的 API
        socket.addListener(listener)
        awaitDispose { socket.removeListener(listener) }     // 離開 composition 時解除
    }
```

它的 key 規則和 `LaunchedEffect` 完全相同（Kotlin07 演練 2）。資料來源如果是 Flow，也可以寫 `produceState(initial) { flow.collect { value = it } }`，但這正是 `collectAsState` 內部做的事，所以直接用第 5 題的 `collectAsStateWithLifecycle`，不要自己寫。

**Snapshot 是什麼？** 它是 Compose 管理 State 的機制，有三個概念：

- **讀取追蹤。** Composable、`derivedStateOf`、`snapshotFlow` 執行時，Compose 會記下它們讀了哪些 State；那些 State 之後被寫入，才去通知它們。這就是為什麼「沒有在 lambda 裡讀 State」就收不到變化。
- **寫入要「套用並通知」，別人才看得到。** 有 Compose 畫面在跑的時候，State 一被寫入，Compose 就會自動做這件事。沒有 Compose 的環境（單元測試）沒有人做，`snapshotFlow` 就收不到後續的變化，要自己呼叫 `Snapshot.sendApplyNotifications()`（演練 8；本機實測：呼叫之前只收到第一筆，呼叫之後收到最後一筆）。
- **同時寫多個 State，要一起生效：** `Snapshot.withMutableSnapshot { a = 1; b = 2 }`，離開區塊時一次套用，別人不會看到只改了一半的狀態。

`SideEffect` 也和這個機制有關：它在這次組合成功並套用到畫面之後才執行，做到一半被放棄的組合不會執行（Kotlin07 第 4 題）。所以要把「這次組合確定的結果」交給 Compose 以外的程式（舊的 View、第三方圖表）時，放在 `SideEffect` 裡，不要直接寫在 Composable 的函式內容裡。

> [!TIP]
> 面試時可以這樣說：「derivedStateOf 追蹤的是 State 的讀取，適合輸入變很頻繁、輸出很少變，要包 remember；remember(key) 追蹤的是我放進去的參數。兩個常常合起來用：remember(param) { derivedStateOf { … } }。snapshotFlow 是 State 變成 Flow，produceState 是 suspend 或 callback 變成 State。底層都是 snapshot 的讀取追蹤，所以沒有 Compose 在跑的環境，例如單元測試，snapshotFlow 不會自己更新。」

### 5. 外部資料怎麼變成 State？`collectAsState` 還是 `collectAsStateWithLifecycle`？

**在 Android 畫面上，Flow 變成 State 預設用 `collectAsStateWithLifecycle()`；`collectAsState()` 只適合不在乎 Android lifecycle 的情況。** 兩者最大的差別在 App 退到背景之後：`collectAsState()` 只要畫面還在 composition 裡就一直收，`collectAsStateWithLifecycle()` 會在退到背景時取消收集，回到前景再重新收集。

| | `collectAsState()` | `collectAsStateWithLifecycle()` |
|---|---|---|
| 來自哪個套件 | Compose 本體（`androidx.compose.runtime`） | `androidx.lifecycle:lifecycle-runtime-compose`，要自己加依賴（本專案用 2.9.4） |
| 什麼時候收集 | 進入 composition 就開始，離開才停 | lifecycle 至少到 `minActiveState` 才收，預設是 `STARTED` |
| 退到背景 | 繼續收，每一筆都讓畫面重組 | 取消收集；回到前景重新收集 |
| 適合 | 不是 Android，或這個畫面刻意要在背景也持續更新 | Android 畫面（行情、訂單、餘額） |

本機實測（演練 7，伺服器每 50 ms 推一筆價格）：App 退到背景 0.5 秒，`collectAsState()` 讓畫面又更新了 9 次，行情還有 1 個 collector 在收；`collectAsStateWithLifecycle()` 是 0 次、0 個，回到前景後馬上顯示最新的價格。`minActiveState` 預設是 `STARTED`（畫面看得到），改成 `RESUMED` 的話，被別的視窗搶走焦點、Activity 進入 paused 時也會停。再搭配 ViewModel 裡的 `stateIn(WhileSubscribed(5000))`（Kotlin05 第 5 題），畫面離開超過 5 秒，連 WebSocket 訂閱都會停掉。

**用哪一種 Flow 來收？**

| 來源 | 怎麼收 | initial 怎麼給 |
|---|---|---|
| `StateFlow<T>` | `collectAsStateWithLifecycle()` | 不用給，`StateFlow` 一定有目前值 |
| 一般 `Flow<T>`（cold） | `collectAsStateWithLifecycle(initialValue)` | 一定要給：第一個值來之前，畫面要顯示什麼 |
| `SharedFlow<T>`（事件） | 通常不轉成 State，用 `LaunchedEffect` 收 | State 只保留最新值，快速連發的事件會漏 |

cold Flow 要小心：每次回到前景都會重新收集，等於上游從頭再跑一次（例如重開 WebSocket）。所以 cold Flow 通常先在 ViewModel 用 `stateIn` 轉成 `StateFlow`，畫面端只收 `StateFlow`。

**`initial` 不要給看起來合理的假值。** 價格先顯示 `0`、餘額先顯示 `0 JPY`，會誤導使用者。用 `null` 或 `Loading` 這種明確的「還沒有資料」：

```kotlin
// build.gradle.kts: implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
val price by repo.priceFlow(symbol).collectAsStateWithLifecycle(initialValue = null)   // Long?：null 代表還沒收到
Text(price?.let { "$it JPY" } ?: "--")

val uiState by vm.uiState.collectAsStateWithLifecycle()    // StateFlow：不用給 initial
```

還有一個容易犯的錯：**在 Composable 裡直接讀 `stateFlow.value`。** 它不會觀察變化，值變了畫面不會更新。Compose 的 lint `StateFlowValueCalledInComposition` 會警告；要用 `collectAsStateWithLifecycle()`。

**舊專案的 LiveData** 用 `observeAsState()`（要加依賴 `androidx.compose.runtime:runtime-livedata`）。LiveData 本身就會跟著 lifecycle，只在畫面可見時才送資料，所以不需要另一個 `WithLifecycle` 版本：

```kotlin
val price by vm.priceLiveData.observeAsState(initial = 0L)   // State<Long>
val maybe by vm.priceLiveData.observeAsState()               // State<Long?>：沒給 initial，還沒有值時是 null
```

新寫的程式碼用 `StateFlow`；ViewModel 還是 LiveData 的舊畫面，一邊用 `observeAsState()` 就好，不必為了統一而大改。

> [!TIP]
> 面試時可以這樣說：「Android 畫面我用 collectAsStateWithLifecycle，預設在 STARTED 才收集，App 退到背景就取消，回前景再重新收，不會在背景白白重組。collectAsState 不管 lifecycle，所以不用在 Android 畫面。StateFlow 直接收；cold Flow 我會先在 ViewModel 用 stateIn 轉成 StateFlow，initial 給 null 或 Loading，不給 0 這種假值。舊的 LiveData 用 observeAsState 並存。」

### 6. ViewModel 的畫面狀態用 `mutableStateOf` 還是 `StateFlow`？狀態該放哪一層？

**畫面的業務狀態預設用 `StateFlow`（對外是 `StateFlow`，內部是 `MutableStateFlow`），畫面端用 `collectAsStateWithLifecycle()` 收；純畫面元素的狀態留在 Composable 用 `remember`。** ViewModel 用 `mutableStateOf` 也能運作，但變化只有 Compose 看得到。

| | ViewModel 用 `StateFlow` | ViewModel 用 `mutableStateOf` |
|---|---|---|
| 非 Compose 的消費者（WorkManager 的 Worker、單元測試、View 畫面、共用的 KMP 程式） | 任何 coroutine 都能 `collect` | 只能用 `snapshotFlow`，而且沒有 Compose 在跑就收不到變化（演練 8） |
| Flow 運算子（`combine`、`debounce`、`stateIn`） | 直接用 | 要先用 `snapshotFlow` 轉一次 |
| 原子更新 | `_state.update { it.copy(…) }` | 沒有同等的 API |
| 畫面端的寫法 | 要寫 `collectAsStateWithLifecycle()` 並處理 initial | 直接讀 `vm.state`，最簡單 |
| lifecycle | 搭配 `WithLifecycle`，背景會停 | 沒有收集動作，永遠是最新值，沒有停的機制 |
| ViewModel 的依賴 | 不需要 Compose | 依賴 Compose runtime |

單元測試也是同樣的差別：`StateFlow` 用 `runTest` 加 Turbine 就能驗證每一筆；`mutableStateOf` 讀 `.value` 沒問題，但要「等變化」就得自己處理 snapshot 的通知。

有一個例外是**輸入框的即時文字**。文字如果繞一圈非同步的 Flow 再回到畫面，更新會比輸入法晚一步，輸入中文、日文這類要組字的輸入法時，可能出現游標跳動等怪現象。常見的做法是輸入框的即時文字放在畫面自己的 State，需要時（例如按下送出）才交給 ViewModel。

**狀態到底放哪一層？** 跟著它「誰在乎、要活多久」來決定（完整的 state hoisting 見 Kotlin09 第 1 題）：

| 這個狀態… | 放哪裡 |
|---|---|
| 只有這個 Composable 在乎（展開或收合、選了哪個分頁） | `remember`；要撐過旋轉用 `rememberSaveable` |
| 兩個以上的兄弟 Composable 共用 | 提到它們共同的上層 Composable |
| 畫面邏輯，不依賴 domain（捲動、動畫、表單驗證） | state holder 類別，搭配 `rememberXxxState()` |
| 業務資料（行情、訂單、餘額），要打 API、要撐過旋轉 | ViewModel 的 `StateFlow` |
| 行程被殺後還要還原 | `rememberSaveable` 或 `SavedStateHandle` |

沒有 ViewModel 的畫面邏輯，可以用 `rememberCoroutineScope` 搭配 `MutableStateFlow` 寫一個 state holder：scope 讓它能在事件裡啟動 coroutine，`MutableStateFlow` 讓畫面和別的程式都能收到變化。

```kotlin
class PriceHolder(private val repo: PriceRepo, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<PriceState>(PriceState.Loading)
    val state: StateFlow<PriceState> = _state.asStateFlow()

    fun refresh() {
        scope.launch { _state.value = PriceState.Loaded(repo.fetch()) }
    }
}

@Composable
fun rememberPriceHolder(repo: PriceRepo): PriceHolder {
    val scope = rememberCoroutineScope()                  // 綁在這個 Composable 上，離開畫面就取消
    return remember(repo, scope) { PriceHolder(repo, scope) }
}

@Composable
fun PriceScreen(repo: PriceRepo) {
    val holder = rememberPriceHolder(repo)
    val state by holder.state.collectAsStateWithLifecycle()
    // …
}
```

限制是：holder 跟著 Composable，旋轉螢幕就重建，狀態會重來；scope 在離開畫面時取消，所以不要拿來做送出訂單這種必須做完的工作（Kotlin07 第 5 題）。要撐過旋轉或必須做完的，放 ViewModel。

> [!TIP]
> 面試時可以這樣說：「ViewModel 的畫面狀態我用 StateFlow：非 Compose 的程式也能收，可以用 Flow 運算子，update 是原子更新，ViewModel 也不用依賴 Compose。mutableStateOf 在 ViewModel 裡能用，但只有 Compose 看得到變化，單元測試或 Worker 要觀察它就很麻煩。輸入框的即時文字例外，放在畫面的 State 比較不會游標跳動。純畫面元素的狀態留在 Composable，業務狀態放 ViewModel。」

### 7. 一張對照表：情境對應該用的 API

選的時候先問三件事：

1. **資料從哪來？** 自己產生、從 Flow、從 callback，還是從 suspend 函式？
2. **要活多久？** 只活過重組、撐過旋轉、還是行程被殺也要還原？
3. **誰要觀察它？** 只有 Compose 畫面，還是也有 Worker、單元測試、其他畫面？

| 情境 | 用這個 | 補充 |
|---|---|---|
| 表單輸入到一半，要撐過旋轉 | `rememberSaveable` | 自訂型別要給 `Saver` |
| 展開或收合、選了哪個分頁 | `remember { mutableStateOf(false) }` | 要撐過旋轉就換 `rememberSaveable` |
| 未讀數、計數器、捲動位置（整數） | `mutableIntStateOf` | 讀寫用 `intValue`，避免 boxing |
| 以最小單位存的價格（`Long`） | `mutableLongStateOf` | 讀寫用 `longValue` |
| 自選幣種，一筆一筆增減 | `mutableStateListOf` | 項目用不可變的 data class，改的時候換掉那一格 |
| symbol 對最新價格的對照表 | `mutableStateMapOf` | |
| 每秒換一整份訂單簿快照 | `mutableStateOf(不可變的 list)` | 整份換掉，不要一筆一筆改 |
| 同一個物件就地更新後要通知 | `mutableStateOf(obj, neverEqualPolicy())` | 每次寫入都重組 |
| `equals` 不適合判斷畫面要不要更新 | `referentialEqualityPolicy()`，或 UI 用 data class | |
| 計算結果依賴一般參數 | `remember(key) { }` | |
| 捲動位置超過門檻才顯示「回到頂端」 | `remember { derivedStateOf { … } }` | 輸入頻繁、輸出很少變 |
| 把捲動位置回報給 analytics | `snapshotFlow` 加 `distinctUntilChanged` | 放在 `LaunchedEffect` 裡 |
| 一次性的 suspend 載入，或 callback 轉成 State | `produceState` | callback 要用 `awaitDispose` 解除 |
| 長時間運作的 effect 要用最新的 callback | `rememberUpdatedState` | 不要放進 key |
| 每秒幾十筆行情，只在 STARTED 時收 | `collectAsStateWithLifecycle` | 搭配 `stateIn(WhileSubscribed(5000))` |
| 一般的 cold Flow 要顯示 | `collectAsStateWithLifecycle(initialValue)` | 或先在 ViewModel 用 `stateIn` |
| 導航、Toast 這類一次性事件 | 不轉成 State | 用 `LaunchedEffect` 收，或做成 state 裡「待處理的事件」（Kotlin05 第 3 題） |
| 舊專案的 LiveData | `observeAsState()` | 要加 `runtime-livedata` 依賴 |
| ViewModel 暴露畫面狀態 | `StateFlow` | 畫面用 `collectAsStateWithLifecycle` |
| 純畫面邏輯（捲動、表單驗證） | state holder 類別加 `rememberXxxState()` | |
| 送出訂單這種必須做完的工作 | ViewModel 的 `viewModelScope` | 不是 `rememberCoroutineScope` |
| ViewModel 的狀態要撐過行程被殺 | `SavedStateHandle` | 只放小量資料 |
| 同時寫好幾個 State，要一起生效 | `Snapshot.withMutableSnapshot { }` | 別人不會看到只改一半 |

> [!TIP]
> 面試時可以這樣說：「我選狀態 API 先問三件事：資料從哪來、要活多久、誰要觀察。表單輸入用 rememberSaveable，頻繁更新的數字用 primitive 版的 State，捲動超過門檻用 derivedStateOf，要回報捲動位置用 snapshotFlow，行情用 collectAsStateWithLifecycle 只在前景收，業務狀態放 ViewModel 的 StateFlow。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin11ComposeState**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin11-compose-state/`，解答在 `labs/android/solution/kotlin/kotlin11-compose-state/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不用看畫面**：這個單元的演練都用 LabKit 的 `withHeadlessCompose`，在沒有畫面的情況下真的組合與重組一個 Composable，再把「畫面顯示什麼、重組幾次」寫進 log，所以按「執行」就有自動檢查，也可以在電腦上跑 `./gradlew :practice-kotlin:testDebugUnitTest` 看結果。畫面上的文字改用 `HeadlessText`（`Text` 的替身）。演練 7 用到 Android 的主執行緒，要在手機或模擬器上按「執行」；演練 8 只能在電腦上跑單元測試（原因寫在演練裡）。

### 演練 1：成交了一部分，委託那一列卻沒變（第 1 題）

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

### 演練 2：訂單狀態變成 Filled，畫面卻停在 New（第 2 題）

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

### 演練 3：訂單簿更新了，最佳買價卻不動（第 2 題）

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

### 演練 4：下單草稿用 rememberSaveable，一打開就當掉（第 3 題）

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

### 演練 5：旋轉螢幕後，下單數量歸零了（第 3 題）

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

### 演練 6：警示線調高了，畫面還是顯示超過（第 4 題）

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

### 演練 7：App 退到背景，畫面還一直在更新（第 5 題）

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

### 演練 8：價格警示在單元測試裡只收到第一筆（第 6 題）

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

第 3 題的 `remember` 的 key 與 `rememberSaveable` 的基本用法，已在 Kotlin06 演練 5、6 做過，`rememberUpdatedState` 在 Kotlin07 演練 5 做過，這裡只補 `Saver`（演練 4、5）。第 1 題 primitive 版的 boxing 沒有獨立演練：它不會讓畫面出錯，只是多了記憶體配置，Android Studio 的 lint（`AutoboxingStateCreation`）會直接提醒，boxing 本身的觀念見 Kotlin01 演練 4。第 4 題 `produceState` 的 key 規則和 Kotlin07 演練 2 相同，`snapshotFlow` 的演練見 Kotlin07 演練 10。第 7 題是對照表，是整理用的，沒有演練。

## 常見陷阱

- **`mutableStateOf(mutableListOf())` 或 `mutableStateOf(mutableMapOf())` 之後原地修改。** State 的值還是同一個物件，Compose 不知道內容變了。改用 `mutableStateListOf`、`mutableStateMapOf`，或每次換成新的集合。
- **`mutableStateListOf` 的項目是 `var` 欄位的 class，原地改欄位。** 清單只認得自己內容的改變，不看項目裡面。項目用 `data class` 加 `val`，更新時換掉那一格。
- **用 `mutableStateOf(0)` 存每秒更新很多次的整數。** 每次寫入都要 boxing。改用 `mutableIntStateOf`、`mutableLongStateOf`，讀寫用 `intValue`、`longValue`，不要再去讀 `.value`。
- **以為 `neverEqualPolicy` 能讓「相同的訊息再顯示一次」。** 重組會發生，但 `LaunchedEffect` 的 key 還是用 `equals`，不會重新啟動。一次性事件要包成帶 id 的物件，或照 Kotlin05 第 3 題的做法。
- **`rememberSaveable` 放自訂的 class 卻沒給 `Saver`，或新增欄位卻忘了改 `Saver`。** 前者畫面一打開就閃退，後者旋轉後欄位默默歸零。給 `Saver` 寫存了再還原的單元測試。
- **`derivedStateOf` 漏了 `remember`，或計算式依賴一般參數卻沒當 `remember` 的 key。** 前者快取等於沒有，後者一直用第一次的參數。
- **在 Android 畫面用 `collectAsState()` 收行情。** 退到背景後仍然持續收集、持續重組，耗電也耗流量。改用 `collectAsStateWithLifecycle()`。
- **在 Composable 裡直接讀 `stateFlow.value`。** 不會觀察變化，值變了畫面不會更新。用 `collectAsStateWithLifecycle()`，Compose 的 lint（`StateFlowValueCalledInComposition`）也會警告。
- **把 cold Flow 直接交給 `collectAsStateWithLifecycle(initialValue)`。** 每次回到前景，上游都從頭再跑一次（例如重開 WebSocket）。先在 ViewModel 用 `stateIn` 轉成 `StateFlow`。
- **ViewModel 用 `mutableStateOf` 暴露狀態，卻要在 Worker 或單元測試裡觀察它。** `snapshotFlow` 在沒有 Compose 的環境收不到後續變化。ViewModel 對外給 `StateFlow`。

## 面試官想聽到的

1. 能把 `mutableStateOf` 的家族講完整：primitive 版為什麼存在（boxing）、`mutableStateListOf` 與 `mutableStateOf(mutableListOf())` 的差別、`SnapshotStateList` 只看清單內容不看項目裡面。
2. 知道 `mutableStateOf` 預設用 `equals` 判斷，相等的寫入會被丟掉，也能說出 `referentialEqualityPolicy`、`neverEqualPolicy` 各自適合的情境，以及它們不能取代「一次性事件」的設計。
3. 能分清楚 `remember`、`rememberSaveable`、ViewModel 的存活範圍，知道 `rememberSaveable` 存得了與存不了的型別，會寫 `listSaver`、`mapSaver`，並提醒欄位增加時 Saver 要同步修改。
4. 能說出 `derivedStateOf` 和 `remember(key)` 的差別（追蹤 State 的讀取，還是追蹤我放進去的參數）、`snapshotFlow` 與 `produceState` 的方向，並知道 snapshot 的讀取追蹤是它們共同的基礎。
5. 能解釋 `collectAsState` 與 `collectAsStateWithLifecycle` 在背景時的差別、`StateFlow` 與一般 `Flow` 該怎麼收、`initial` 怎麼給，並能說出 ViewModel 用 `StateFlow` 而不是 `mutableStateOf` 的理由。

> [!FIELD]
> 在一個即時行情 App 的 Android 版裡，全面採用 Jetpack Compose，以 Coroutines 與 Flow 處理資料流，即時行情用 Server-Sent Events；專案裡也有使用 `derivedStateOf` 和 `snapshotFlow`（見 Kotlin07）。另一個社交 App 的 Android 版則是逐頁重構，陸續導入 Jetpack Compose。
> 行情與訂單畫面可以這樣選：ViewModel 對外給 `StateFlow`，畫面用 `collectAsStateWithLifecycle` 只在前景收；輸入中的欄位用 `rememberSaveable`（自訂型別給 `Saver`）；捲動超過門檻用 `derivedStateOf`；必須做完的下單放 ViewModel。

## iOS / Android 對照

| 需求 | Compose | SwiftUI |
|---|---|---|
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
| 快照 | snapshot | スナップショット |
| 自動裝箱 | boxing（autoboxing） | ボクシング（オートボクシング） |
| 行程被系統殺掉 | process death | プロセスデス（プロセスの強制終了） |
| 生命週期 | lifecycle | ライフサイクル |
| 衍生狀態 | derived state | 派生状態 |
| 狀態持有者 | state holder | ステートホルダー |
| 保存並還原狀態 | saved instance state | 保存されたインスタンス状態 |
