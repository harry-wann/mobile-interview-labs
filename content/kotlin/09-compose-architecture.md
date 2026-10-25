---
id: kotlin-compose-architecture
title: Compose 的架構：state hoisting、單向資料流（UDF／MVI）、Navigation，以及和既有 View 系統並存
summary: 面試會問 Compose 畫面的狀態放哪裡、UDF 與 MVI 是什麼、type-safe 導航怎麼寫，也會問「既有的 XML／Fragment 專案怎麼導入 Compose」。這個單元一個一個講清楚，並連結實務上逐頁重構、導入 Compose、Navigation Component、Paging 3 與 Hilt 的經驗。
priority: P1
day: 2
tags: [compose, architecture, udf, mvi, navigation, interop, state-hoisting]
---

## 重點講解

這個單元回答六個面試常見問題：

1. state hoisting 是什麼？狀態應該放在哪一層？
2. UDF 和 MVI 是什麼？怎麼取捨？
3. Navigation Compose 的 type-safe 路由怎麼寫？
4. `CompositionLocal` 是什麼？什麼時候不該用？（比較少考，被問到能答就好）
5. 既有的 XML／Fragment 專案，怎麼嵌入 Compose？反過來呢？
6. 一個舊專案要怎麼逐步導入 Compose？（實務經驗）

### 1. state hoisting 是什麼？狀態應該放在哪一層？

**state hoisting（狀態提升）是指：把狀態從 Composable 裡往上提，讓 Composable 變成 stateless（自己不保存狀態，只負責顯示）。值往下傳，事件往上傳。狀態要提到「需要用它的所有 Composable 的最低共同祖先」，不要一路提到最上層。**

情境：下單畫面有一個「數量」輸入框（`OrderForm`），旁邊的「預估金額」（`Summary`）也要用同一個數量。如果兩個 Composable 各自 `remember` 一份，兩邊會不同步。做法是把 `qty` 提到它們共同的上層：

```kotlin
@Composable
fun OrderScreen() {
    var qty by rememberSaveable { mutableStateOf("") }      // 狀態在共同的上層

    Column {
        OrderForm(qty = qty, onQtyChange = { qty = it })    // 值往下傳，事件往上傳
        Summary(qty = qty)
    }
}

@Composable
fun OrderForm(qty: String, onQtyChange: (String) -> Unit) {  // stateless：自己不保存 qty
    OutlinedTextField(value = qty, onValueChange = onQtyChange)
}

@Composable
fun Summary(qty: String) { Text("預估金額：${estimate(qty)}") }
```

好處是：單一真相來源（只有一份 `qty`）、`OrderForm` 可以重用、容易預覽與測試（直接傳一個值進去就好）。

**狀態可以放在三種地方，依性質選：**

| 放哪裡 | 適合的狀態 | 例子 |
|---|---|---|
| Composable 內的 `remember` | 純畫面元素的狀態 | 一個區塊展開或收合 |
| 狀態持有者類別（plain state holder class，自己寫的一般類別，通常搭配 `rememberXxxState()` 建立） | 畫面邏輯，不依賴 ViewModel | 捲動、動畫、表單欄位驗證 |
| ViewModel | 畫面的業務狀態，要連到 domain／data 層，且要撐過旋轉螢幕 | 下單表單、行情資料 |

兩個原則：只有畫面自己需要的狀態，不要硬塞進 ViewModel；要在 App 行程被系統殺掉後還原的狀態，用 `SavedStateHandle`（ViewModel 專用）或 `rememberSaveable`（上一單元介紹過）。

> [!TIP]
> 面試時可以這樣說：「我會把狀態提到需要它的所有 Composable 的最低共同祖先，值往下傳、事件往上傳，讓 Composable 保持 stateless，好預覽也好測試。純畫面的狀態用 remember，畫面邏輯用 state holder 類別，業務狀態放 ViewModel。」

### 2. UDF 和 MVI 是什麼？怎麼取捨？

**UDF（unidirectional data flow，單向資料流）是指：狀態只往下流，事件只往上流。ViewModel 持有唯一的畫面狀態，畫面只負責顯示，使用者操作變成事件送回 ViewModel。MVI（Model-View-Intent）是 UDF 比較嚴格的版本：整個畫面只有一個不可變的狀態，所有操作都變成明確的事件，由一個函式算出新的狀態。**

先看一般的 UDF 寫法（也就是 MVVM（Model-View-ViewModel）搭配 `StateFlow`）：

```kotlin
sealed interface OrderUiState {
    data object Loading : OrderUiState                       // data object：Kotlin 1.9 起可用
    data class Content(val qty: String, val estimate: String?, val submitting: Boolean) : OrderUiState
    data class Error(val message: String) : OrderUiState
}

@HiltViewModel
class OrderViewModel @Inject constructor(
    private val placeOrder: PlaceOrderUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow<OrderUiState>(OrderUiState.Loading)
    val state: StateFlow<OrderUiState> = _state.asStateFlow()     // 對外只暴露唯讀的 StateFlow

    fun onQtyChange(q: String) = _state.update { s ->             // 事件往上：UI 呼叫這個函式
        if (s is OrderUiState.Content) s.copy(qty = q) else s
    }
}

@Composable
fun OrderRoute(vm: OrderViewModel = hiltViewModel()) {          // Route：負責取得 ViewModel
    val state by vm.state.collectAsStateWithLifecycle()         // 狀態往下：把 Flow 變成 State
    OrderScreen(state = state, onQtyChange = vm::onQtyChange)
}

@Composable
fun OrderScreen(state: OrderUiState, onQtyChange: (String) -> Unit) { /* stateless，可預覽、可測 */ }
```

把畫面拆成 `Route`（取得 ViewModel）和 `Screen`（stateless）很重要：`Screen` 不依賴 ViewModel，才能預覽、才能直接傳假資料測試。

**MVI 的做法：** 把所有操作變成明確的事件（這裡的 Intent 是「使用者想做的事」，不是 Android 的 `Intent` 類別），再用一個純函式 `reduce` 算出新狀態：

```kotlin
sealed interface OrderIntent {
    data class QtyChanged(val qty: String) : OrderIntent
    data object Submit : OrderIntent
}

data class OrderState(val qty: String = "", val submitting: Boolean = false)

// 純函式：同樣的 state 加上同樣的 intent，結果永遠一樣
fun reduce(state: OrderState, intent: OrderIntent): OrderState = when (intent) {
    is OrderIntent.QtyChanged -> state.copy(qty = intent.qty)
    OrderIntent.Submit -> state.copy(submitting = true)
}
```

價值：容易重現與測試（給定狀態和事件，結果固定）。代價：樣板程式碼多；而且如果**所有欄位都塞進同一個狀態，每秒更新很多次的欄位（例如價格）每變一次就產生新的狀態，會連帶讓不相關的區塊也重新檢查**。

實務上的折衷：表單與畫面結構用單一 `UiState`，每秒更新很多次的行情拆成獨立的 Flow，讓讀取價格的小 Composable 自己去收集。

**一次性事件（導航、顯示 Toast）怎麼處理？** 優先轉成狀態：ViewModel 把「要導航」放進 state，UI 處理完之後呼叫 `onNavigated()`，讓 ViewModel 清掉它。用 `Channel(BUFFERED).receiveAsFlow()` 傳事件是次選，有事件遺失的風險（Flow 單元討論過）。

> [!TIP]
> 面試時可以這樣說：「UDF 是狀態往下、事件往上，ViewModel 是唯一的狀態來源。MVI 是更嚴格的版本，單一不可變的狀態加上 reduce 函式，好測但樣板多，而且每秒更新很多次的欄位放進同一個 state 會拖累整個畫面。所以我的折衷是表單用單一 UiState，行情拆成獨立的 Flow。」

### 3. Navigation Compose 的 type-safe 路由怎麼寫？

**type-safe（型別安全）路由是指：用 `@Serializable` 標記的物件或 data class 來代表每個目的地，不再手寫字串路由。從 Navigation 2.8.0 起支援，編譯時就能檢查參數對不對。**

以前的寫法是字串，參數要自己組字串、自己解析：`navController.navigate("detail/${order.id}")`，拼錯了到執行時才發現。新寫法（需要 kotlinx.serialization 的 Gradle plugin）：

```kotlin
@Serializable object OrderList                           // 沒有參數的目的地
@Serializable data class OrderDetail(val orderId: String)  // 有參數的目的地

NavHost(navController, startDestination = OrderList) {
    composable<OrderList> {
        OrderListScreen(onClick = { id -> navController.navigate(OrderDetail(id)) })  // 直接傳物件
    }
    composable<OrderDetail> { entry ->
        val args: OrderDetail = entry.toRoute()          // 取回路由物件
        OrderDetailScreen(args.orderId)
    }
}
```

在 ViewModel 裡也可以直接拿到參數：`savedStateHandle.toRoute<OrderDetail>()`，不用手動從 `SavedStateHandle` 取字串再轉型。

**一個重要原則：路由只傳「識別碼」，不傳整個物件。** 例如只傳 `orderId`，目的地的 ViewModel 再向 repository 取得最新的訂單資料。如果把整個 `Order` 序列化放進路由，會有大小限制，而且資料可能已經過期（訂單狀態在這段時間內已經變了）。

另外，`hiltViewModel()` 預設會把 ViewModel 綁在目前這個目的地（nav back stack entry）上，離開目的地就釋放；如果多個畫面要共用同一個 ViewModel，可以綁在 nav graph 範圍。

> [!TIP]
> 面試時可以這樣說：「Navigation 2.8.0 起支援 type-safe 路由：用 @Serializable 的型別宣告目的地，composable 加上泛型，用 toRoute 取回參數，編譯時就檢查。路由我只傳識別碼，詳細資料由目的地的 ViewModel 向 repository 取，避免傳大物件和資料過期。」

### 4. `CompositionLocal` 是什麼？什麼時候不該用？（比較少考，被問到能答就好）

**`CompositionLocal` 是讓你不必一層一層傳參數，就能把資料「隱含地」提供給整棵 Composable 樹的機制。適合主題、`LocalContext`、`LocalDensity` 這類整棵樹都需要的資料；不適合拿來傳 ViewModel、業務資料或 callback。**

不適合的原因是：依賴被隱藏了，從函式簽名看不出它需要什麼；預覽與測試時要記得額外提供；沒提供時還會在執行時出錯。

建立方式有兩種：

- `compositionLocalOf`：Compose 會追蹤是誰讀了它，值改變時，只有讀取它的地方重組。
- `staticCompositionLocalOf`：不追蹤讀取，省下追蹤的成本，但值一改變，提供範圍裡面**全部**重組。所以只適合幾乎不變的值（例如 App 設定）。每秒會變的值（例如目前價格）不能用它。

> [!TIP]
> 面試時可以這樣說：「CompositionLocal 用在主題、Context 這種整棵樹共用的資料。業務資料和 ViewModel 我會用明確的參數傳，不用它，因為它會隱藏依賴。staticCompositionLocalOf 值一變就重組整個範圍，所以只用在幾乎不變的值。」

### 5. 既有的 XML／Fragment 專案，怎麼嵌入 Compose？反過來呢？

**在 View 系統裡嵌入 Compose 用 `ComposeView`；在 Compose 裡嵌入舊的 View（WebView、地圖、第三方圖表）用 `AndroidView`。**

**(1) XML／Fragment 裡放 Compose：`ComposeView`**

```kotlin
class ProfileFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        // 讓 Compose 的畫面跟著 Fragment 的 view lifecycle 釋放
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MaterialTheme {                  // XML 的 theme 不會自動套用到 Compose，要自己提供
                ProfileRoute()
            }
        }
    }
}
```

幾個要點：

- **Fragment 裡要設 `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`。** `ViewCompositionStrategy` 決定「Compose 畫面什麼時候被釋放」。預設的策略是 view 從 window 上被拆下（detach）就釋放，這個時機和 Fragment 的 view lifecycle 不一致（轉場動畫期間，或 view 被暫時移出時），行為難以預期，所以官方建議在 Fragment 裡改成綁定 view lifecycle。
- **Fragment 被 replace 並放進 back stack 時，`onDestroyView` 會被呼叫，回到這個畫面時是全新的 view 與全新的 composition。** 所以不管用哪種策略，`remember` 的狀態都不會保留，只有 `rememberSaveable`（或 ViewModel）才會還原。
- ViewModel 在 `ComposeView` 裡照常可用，Hilt 的 `hiltViewModel()` 也可以。

**(2) Compose 裡放舊的 View：`AndroidView`**

```kotlin
@Composable
fun WebChart(url: String) {
    AndroidView(
        factory = { context -> WebView(context) },     // 只在建立時執行一次
        update = { webView -> webView.loadUrl(url) },  // 它讀到的 State 變了才會再執行
    )
}
```

`factory` 只在 View 建立時執行一次；`update` 在重組、而且它讀取的 State 有變化時執行。不要在 `update` 裡做昂貴的工作，也不要每次都整份資料重設：先比較資料有沒有變，沒變就不更新（常見做法是像 K 線圖那樣，用 `lastDataId` 記錄資料有沒有變）。

**(3) 導航只留一個權威。** XML 專案通常已經有 Navigation Component 的 nav graph。新的 Compose 畫面先包在 Fragment 裡，沿用同一套導航，避免兩套導航互相搶 back stack（返回堆疊）。

> [!TIP]
> 面試時可以這樣說：「在 Fragment 裡嵌 Compose 用 ComposeView，並把 composition strategy 設成 DisposeOnViewTreeLifecycleDestroyed，讓它跟著 view lifecycle 釋放。XML 的 theme 不會自動套用，要自己提供 MaterialTheme。導航只留一套，新畫面先包在 Fragment 裡，避免兩套導航搶 back stack。」

### 6. 一個舊專案要怎麼逐步導入 Compose？（實務經驗）

**不要一次重寫，而是逐頁替換（這種做法常被稱為 strangler pattern：新的慢慢取代舊的，直到舊的消失）。順序是：新畫面先用 Compose、共用元件先做成 Compose 版本、既有畫面逐頁重寫，最後才評估要不要換導航。**

建議的順序：

1. **新畫面用 Compose**，放在 Fragment 或獨立的 Activity 裡。
2. **共用元件**（按鈕、列表項目、對話框）先做成 Compose 版本，XML 畫面也可以用 `ComposeView` 重用，設計系統集中在 theme。
3. **畫面逐頁重寫**，ViewModel 與 `UiState` 保持不變。因為狀態往下、事件往上的邊界沒變，換掉的只有 UI 那一層。例如 ViewModel 暴露的是 Paging 3 的 `Flow<PagingData>`，XML 畫面用 `PagingDataAdapter` 接，Compose 畫面用 `collectAsLazyPagingItems()` 接，ViewModel 不用改。
4. **全部畫面都完成後**，再評估是否把導航從 Navigation Component 換成 Navigation Compose。

```kotlin
// Compose 畫面接 Paging 3 的資料：ViewModel 暴露的 feed 是 Flow<PagingData<Post>>
val posts = viewModel.feed.collectAsLazyPagingItems()
LazyColumn {
    items(count = posts.itemCount, key = posts.itemKey { it.id }) { index ->
        posts[index]?.let { post -> PostRow(post) }
    }
}
```

這就是一個社交 App 的 Android 版的做法：為了統一程式碼規範與風格、降低技術債，在開發新功能之餘**逐頁重構**，陸續導入 Jetpack Compose、Navigation Component 與 Paging 3，並把依賴注入從 Dagger 遷移到 Hilt。

> [!TIP]
> 面試時可以這樣說：「舊專案我會逐頁替換，不一次重寫。新畫面先用 Compose，共用元件先做 Compose 版本，ViewModel 和 UiState 不動，換的只有 UI 層。導航維持一套，等大部分畫面都完成再評估要不要換。這是我在一個社交 App 的 Android 版做過的路線。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin09ComposeArchitecture**。演練 1 到 4 不用看畫面，按「執行」就有自動檢查；演練 5 要在畫面上操作，看按鈕上的次數和 log。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin09-compose-architecture/`，解答在 `labs/android/solution/kotlin/kotlin09-compose-architecture/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不用看畫面**：演練 1、2、4 用 LabKit 的 `withHeadlessCompose`，在沒有畫面的情況下真的組合與重組 Composable（Compose 的組合、重組、狀態都是 runtime 的功能，不需要畫面），再把結果寫進 log；畫面上的文字改用 `HeadlessText`（`Text` 的替身）。這幾個演練也可以在電腦上跑 `./gradlew :practice-kotlin:testDebugUnitTest` 看結果。

### 演練 1：清空數量後，輸入欄還留著舊的（第 1 題）

**檔案**：`L01_StateHoisting.kt`

**情境**：下單畫面有輸入欄（`OrderForm`）和預估金額（`L01Summary`），兩邊都要用同一個數量 `qty`。使用者打字輸入 0.5，下單成功後，父層（`OrderScreen`）把數量清成空字串。演練會印出輸入欄與預估金額欄各自顯示什麼。

**怎麼寫**

1. **Bug 版**：`OrderForm` 把傳進來的 `qty` 複製進自己的 `var text by remember { mutableStateOf(qty) }`，打字時改 `text`，再呼叫 `onQtyChange(it)` 通知父層，畫面顯示 `text`。
2. **修正版**：`OrderForm` 變成 stateless：打字直接交給 `onQtyChange`，畫面顯示上層傳下來的 `qty`。`qty` 只放在 `OrderScreen` 一份。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 輸入 0.5 之後兩邊都是 0.5；清空之後，輸入欄還是「0.5」，預估金額欄的數量卻是空的 |
| 修正版 | 輸入 0.5 之後兩邊都是 0.5；清空之後，兩邊都是空的 |

**為什麼**：狀態提升（state hoisting）的重點是只有一份狀態（單一真相來源）：值往下傳，事件往上傳，Composable 自己不保存。Bug 版有兩份 `qty`，父層清的是它那一份，`OrderForm` 內部那份沒有跟著清。狀態要提到「需要用它的所有 Composable 的最低共同祖先」。

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L01_StateHoisting.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L01_StateHoisting.kt#fixed
```

### 演練 2：價格一跳，不相干的表單也重組（第 2 題）

**檔案**：`L02_BigUiState.kt`

**情境**：下單畫面有表單（`OrderForm`，只用到數量 `qty`）和即時價格（`PriceText`）。價格更新 10 次（每 30 ms 一次），`qty` 整個過程都沒變。演練會印出 `OrderForm` 與 `PriceText` 各執行了幾次。

**怎麼寫**

1. **Bug 版**：整個畫面只有一個 `OrderUiState(qty, price)`。`OrderScreen` 把 `vm.state` collect 成 State，把整個 `state` 傳給 `OrderForm`，把 `state.price` 傳給 `PriceText`。
2. **修正版**：表單的 state 只有 `qty`（`OrderFormState`），行情拆成獨立的 `StateFlow<Long>`。`OrderScreen` 只把表單的 state 傳給 `OrderForm`，把 `vm.price`（Flow）傳給 `PriceText`，由 `PriceText` 自己 `collectAsState()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「OrderForm 執行了 11 次」：第一次組合，加上價格更新 10 次各 1 次，雖然它沒有用到價格 |
| 修正版 | 「OrderForm 執行了 1 次」，「PriceText 執行了 11 次」 |

**為什麼**：單一 UiState 的好處是容易重現與測試，代價是每秒更新很多次的欄位（價格）每變一次就產生新的狀態，把整個 state 傳下去的 Composable 的參數也跟著變，連帶重組。實務上的折衷：表單與畫面結構用單一 `UiState`，每秒更新很多次的行情拆成獨立的 Flow，讓讀價格的小 Composable 自己去收集。

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L02_BigUiState.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L02_BigUiState.kt#fixed
```

### 演練 3：點進詳情，看到的是舊的訂單狀態（第 3 題）

**檔案**：`L03_RouteIdOnly.kt`

**情境**：訂單列表點進第 1 筆（A1001，狀態「待成交」）。點擊 100 ms 後，伺服器把這筆訂單更新成「已成交」，再過 100 ms 詳情畫面才完成載入。詳情畫面應該顯示最新的狀態。路由用一個 data class 代表目的地（和 type-safe 路由的寫法一樣），這個演練只看路由裡放什麼。

**怎麼寫**

1. **Bug 版**：路由帶整個物件：`data class OrderDetail(val order: L03Order)`；`navigate` 回傳 `OrderDetail(order)`；`showDetail` 直接回傳 `route.order.status`。
2. **修正版**：路由只帶識別碼：`data class OrderDetail(val orderId: String)`；`showDetail` 向 repository 取最新的訂單：`repo.find(route.orderId).status`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「詳情畫面顯示：訂單 A1001：待成交」，但「伺服器上的最新狀態：已成交」：顯示的是點擊當下的快照 |
| 修正版 | 「詳情畫面顯示：訂單 A1001：已成交」，和伺服器一致 |

**為什麼**：路由只傳識別碼，不傳整個物件。物件在導航的當下就被複製進路由，之後伺服器上的狀態變了，詳情畫面拿到的還是舊的；真的序列化進 Navigation 的參數時，還有大小限制。type-safe 路由（Navigation 2.8.0 起）用 `@Serializable` 的型別宣告目的地，編譯時就檢查參數，不用手寫字串路由。這個專案沒有加 Navigation 與 kotlinx.serialization 的套件，所以沒有演練字串路由打錯字的錯誤。

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L03_RouteIdOnly.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L03_RouteIdOnly.kt#fixed
```

### 演練 4：價格一跳，沒用到價格的表單也重組（第 4 題）

**檔案**：`L04_StaticLocal.kt`

**情境**：畫面用 `CompositionLocal` 把「目前價格」提供給整棵樹。`PriceBadge` 會讀它，`OrderForm` 完全沒有讀。價格更新 10 次（每 30 ms 一次），演練會印出兩者各執行了幾次。

**怎麼寫**

1. **Bug 版**：`val LocalPrice = staticCompositionLocalOf { 0L }`。
2. **修正版**：`val LocalPrice = compositionLocalOf { 0L }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「OrderForm 執行了 11 次」：第一次組合，加上價格更新 10 次各 1 次，雖然它沒有讀價格 |
| 修正版 | 「OrderForm 執行了 1 次」，「PriceBadge 執行了 11 次」：只有讀價格的 PriceBadge 重組 |

**為什麼**：`compositionLocalOf` 會追蹤是誰讀了它，值改變時只有讀取它的地方重組。`staticCompositionLocalOf` 不追蹤，省下追蹤的成本，但值一改變，提供範圍裡面全部重組，所以只適合幾乎不變的值（例如 App 設定），不能放會頻繁變動的值。`CompositionLocal` 適合主題、`LocalContext` 這種整棵樹都要的資料；業務資料與 ViewModel 用明確的參數傳，因為它會隱藏依賴。

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L04_StaticLocal.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L04_StaticLocal.kt#fixed
```

### 演練 5：View 暫時被拆下再裝回，狀態歸零（第 5 題）

**檔案**：`L05_ComposeViewStrategy.kt`

**情境**：舊的 View 系統裡嵌了一個 `ComposeView`，裡面有一個「點了 N 次」的按鈕（次數用 `remember` 保存）。按「移出畫面」「放回畫面」模擬 View 被暫時拆下再裝回（轉場動畫、重新排列版面時都可能發生）。`ComposeView` 是用程式建立的，寫法和 Fragment 的 `onCreateView` 裡建立 `ComposeView` 一樣。log 會印出 composition 的建立與釋放。這題要在畫面上操作。

**怎麼寫**

1. **Bug 版**：`ComposeView(context).apply { setContent { L05Counter(log) } }`，沿用預設的 `ViewCompositionStrategy`。
2. **修正版**：先 `setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)`，再 `setContent { … }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 點按鈕 3 次，按「移出畫面」時 log 出現「ComposeView 的 composition 釋放」；按「放回畫面」出現「composition 建立」，按鈕上的次數回到 0 次 |
| 修正版 | 移出再放回之後，按鈕上的次數仍然是 3 次，log 沒有出現「composition 釋放」或第二次「composition 建立」 |

**為什麼**：`ViewCompositionStrategy` 決定「Compose 畫面什麼時候被釋放」。預設的策略是 view 從 window 上被拆下就釋放，composition 一釋放，`remember` 的狀態就跟著消失。Fragment 的 view 有自己的 lifecycle（`onDestroyView` 才結束），拆下和 view lifecycle 結束的時機不一致，所以官方建議在 Fragment 裡改成 `DisposeOnViewTreeLifecycleDestroyed`：綁定 view tree 的 lifecycle owner，要等 lifecycle 結束才釋放。不管用哪種策略，Fragment 被 replace 並放進 back stack 之後回來，都是全新的 view 與全新的 composition，要保留狀態只有 `rememberSaveable` 或 ViewModel。

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L05_ComposeViewStrategy.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin09-compose-architecture/L05_ComposeViewStrategy.kt#fixed
```

第 6 題是逐步導入 Compose 的流程題，沒有演練。

## 常見陷阱

- **ViewModel 對外暴露 `MutableStateFlow`，或把 `Context`、`View` 存進 ViewModel。** 外面的人可以隨意改狀態，也會造成記憶體洩漏。對外只暴露唯讀的 `StateFlow`。
- **一個巨大的 `UiState` 包含每秒更新很多次的行情。** 整個畫面隨每個 tick 重新檢查。把行情拆成獨立的 Flow。
- **Composable 直接依賴 `hiltViewModel()`，無法預覽與測試。** 拆成 `Route`（取得 ViewModel）與 `Screen`（stateless）。
- **在 `NavHost` 傳整個物件。** 有大小限制，資料也可能過期。只傳識別碼。
- **Fragment 裡的 `ComposeView` 沿用預設的 composition strategy。** 釋放時機和 Fragment 的 view lifecycle 不一致。改成 `DisposeOnViewTreeLifecycleDestroyed`。
- **`staticCompositionLocalOf` 放會頻繁變動的值。** 值一變，整個提供範圍都重組。
- **XML 與 Compose 各自一套導航。** 兩套導航搶 back stack。導航只留一個權威。

## 面試官想聽到的

1. 能清楚說明狀態往下、事件往上的流動，並分清 `remember`、state holder 類別、ViewModel 各自適合放什麼。
2. 對 MVI 有取捨的判斷，不是教條：知道單一狀態的好處，也知道每秒更新很多次的欄位放進去的代價。
3. 熟悉 type-safe Navigation，以及「路由只傳識別碼」的原則。
4. 面對既有的 XML／Fragment 專案，能講出可執行的逐步導入計畫，包含 `ComposeView` 的 lifecycle 策略與導航只留一個權威。

> [!FIELD]
> 兩種專案的做法可以對照：
> - 在一個社交 App 的 Android 版裡：為了統一程式碼規範與風格、降低技術債，在開發新功能之餘逐頁重構，陸續導入 Jetpack Compose、Navigation Component 與 Paging 3，並把依賴注入從 Dagger 遷移到 Hilt。畫面由 `ComponentActivity` 或 Fragment 裡的 `ComposeView` 承載；導航使用 Navigation Component 的 XML nav graph 搭配 safeargs；ViewModel 使用 `@HiltViewModel`；Paging 3 的資料以 `Flow<PagingData>` 從 repository 暴露出來。
> - 在一個即時行情 App 的 Android 版裡：全面採用 Jetpack Compose 與 Navigation Compose，ViewModel 採用 Inputs／Outputs 介面搭配 `MutableStateFlow`，屬於 UDF 的做法。
> - 面試時可以說：兩種專案我都做過。社交 App 是逐頁替換的路線；如果接手的是混合專案，我會沿用這個方式：Fragment 裡放 ComposeView、導航只留一個權威，而不是一次重寫。

## iOS / Android 對照

| 主題 | Compose | SwiftUI／UIKit |
|---|---|---|
| 狀態往下、事件往上 | state hoisting、UDF | `@Binding` 加上單向資料流（也有 TCA（The Composable Architecture）這類第三方架構，用 Reducer 處理事件） |
| 畫面狀態持有者 | ViewModel 搭配 `StateFlow<UiState>` | `ObservableObject` 或 `@Observable` 的 ViewModel |
| 隱含地往下傳資料 | `CompositionLocal` | `@Environment`、`@EnvironmentObject` |
| 嵌入舊系統 | `ComposeView`（在 View 裡放 Compose）、`AndroidView`（在 Compose 裡放 View） | `UIHostingController`（在 UIKit 裡放 SwiftUI）、`UIViewRepresentable`（在 SwiftUI 裡放 UIKit） |
| 導航 | Navigation Compose（type-safe 路由） | `NavigationStack` 搭配 path，或 Coordinator／FlowController 模式 |
| 逐步導入 | 新畫面用 Compose，ViewModel 與 UiState 不變 | 做法相同：iOS 常見的是 SwiftUI 與 UIKit 混合，新畫面用 SwiftUI |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 單向資料流 | unidirectional data flow（UDF） | 単方向データフロー |
| state hoisting（狀態提升） | state hoisting | ステートホイスティング（状態の巻き上げ） |
| 型別安全的導航 | type-safe navigation | 型安全なナビゲーション |
| 逐步替換 | strangler pattern | ストラングラーパターン |
| 狀態持有者 | state holder | ステートホルダー |
| 返回堆疊 | back stack | バックスタック |
