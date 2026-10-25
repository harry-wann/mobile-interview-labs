---
id: kotlin-compose-side-effects
title: Compose 的副作用（side effect）：LaunchedEffect、DisposableEffect 等 API 該用哪一個
summary: Composable 裡要訂閱行情、註冊 listener、啟動 coroutine 時，不能直接寫在函式裡。這個單元說明為什麼，並一個一個講解 LaunchedEffect、rememberUpdatedState、DisposableEffect、SideEffect、rememberCoroutineScope、produceState、snapshotFlow、derivedStateOf 各自解決什麼問題，最後給一張「什麼情況用哪個」的對照表。
priority: P1
day: 2
tags: [compose, side-effects, launchedeffect, disposableeffect, derivedstateof, snapshotflow]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 什麼是副作用？為什麼不能直接寫在 Composable 裡？
2. `LaunchedEffect` 解決什麼問題？它的 key 是什麼意思？
3. 長時間運作的 effect，怎麼拿到「最新的」callback？（`rememberUpdatedState`）
4. 註冊 listener 要用什麼？`DisposableEffect` 和 `SideEffect` 差在哪？
5. 使用者點按鈕時要啟動 coroutine，為什麼要用 `rememberCoroutineScope`？
6. `produceState`、`snapshotFlow`、`derivedStateOf` 各自在轉換什麼？
7. 什麼情況用哪一個？

### 1. 什麼是副作用？為什麼不能直接寫在 Composable 裡？

**副作用（side effect）是指：Composable 在「描述畫面」之外，對外界做的事，例如訂閱資料、註冊 listener、打 API、修改外部的變數、送 analytics 事件。這些事不能直接寫在 Composable 的函式裡，因為 Composable 可能被重複執行、被跳過，順序也不固定。**

上一個單元說過，State 一變，Compose 就會重組（重新執行受影響的 Composable）。想像一個行情畫面，價格每秒更新好幾次：

```kotlin
@Composable
fun TickerScreen(symbol: String, vm: TickerViewModel) {
    // 錯誤：副作用直接寫在函式裡
    vm.subscribe(symbol)
    Text(symbol)
}
```

`vm.subscribe(symbol)` 會在**每一次重組**時執行。價格每跳一次、畫面重組一次，就再訂閱一次，伺服器收到一堆重複的訂閱，App 越來越慢。Compose 並不保證這個函式只會執行一次，所以要「對外界做事」，就必須放進 Compose 提供的 effect API，由 Compose 決定什麼時候開始、什麼時候清理。

effect API 的開始與清理，綁在兩個時間點：

- **進入 composition**：Composable 第一次出現在畫面上（composition 就是 Compose 目前建好的整棵畫面樹）。
- **離開 composition**：Composable 從畫面上消失，例如使用者切到別的頁面。

不同 API 的差別，就是「什麼時候開始做事」「要不要清理」「做的事是不是 suspend 函式」。後面幾節會一個一個說明。

> [!TIP]
> 面試時可以這樣說：「Composable 可能被重複執行、跳過或順序不固定，所以函式裡不能有副作用。要訂閱、註冊或打 API，我會放進 effect API，例如 LaunchedEffect 或 DisposableEffect，讓 Compose 控制它的開始與清理。」

### 2. `LaunchedEffect` 解決什麼問題？key 是什麼意思？

**`LaunchedEffect` 讓你在 Composable 裡安全地啟動 coroutine：Composable 進入 composition 時啟動；離開 composition 時自動取消；key 一變，就取消舊的、用新值重新啟動。**

suspend 函式（例如收集 Flow、`delay`）只能在 coroutine 裡呼叫，而 Composable 函式本身不是 coroutine。`LaunchedEffect` 就是 Compose 提供的入口，而且它把 coroutine 的生命週期綁在畫面上，不用自己管取消。

```kotlin
@Composable
fun PriceHeader(symbol: String, repo: PriceRepo) {
    // symbol 變了，price 也重設為 "--"，避免暫時顯示上一個交易對的價格
    var price by remember(symbol) { mutableStateOf("--") }

    // symbol 就是 key
    LaunchedEffect(symbol) {
        repo.prices(symbol).collect { price = it }
    }
    Text(price)
}
// 使用者從 btcjpy 切換到 ethjpy：
// 1. 舊的 coroutine 被取消（btcjpy 的訂閱結束）
// 2. 啟動新的 coroutine，收集 ethjpy
// 使用者離開這個畫面：目前的 coroutine 被取消
```

**key 的規則：**

- `LaunchedEffect(Unit)` 或 `LaunchedEffect(true)`：key 永遠不變，所以只在進入 composition 時執行一次。
- key 用 `equals` 比較，前後 equals 相同就不會重啟。
- 要寫幾個 key？**effect 裡面用到、而且變了就該重做的值，都要當成 key。**

key 寫錯，有兩種相反的 bug：

**key 寫太多：effect 一直被重啟。** 把「每次重組都會變」的值當 key：

```kotlin
// price 每秒變好幾次
LaunchedEffect(price) {
    // 想等 5 秒後提示逾時
    delay(5_000)
    // 但每次 price 一變，計時就被取消重來，永遠等不到 5 秒
    onTimeout()
}
```

**key 寫太少：effect 用到舊的值。** 這是下一節 `rememberUpdatedState` 要解決的問題。

另外一個常見的錯誤：在同一個 `LaunchedEffect` 裡，先 `collect` 一個不會結束的 Flow，再寫第二段程式碼。`collect` 會一直掛住，後面的程式碼永遠執行不到；兩段要各自用 `launch { }` 啟動。

> [!NOTE]
> `LaunchedEffect` 的生命週期跟著畫面。使用者旋轉螢幕時，畫面會被重建，effect 也跟著取消再重新啟動。如果訂閱需要撐過旋轉，應該放在 ViewModel（由 ViewModel 的 scope 負責），而不是放在 `LaunchedEffect`。

> [!TIP]
> 面試時可以這樣說：「LaunchedEffect 是在 Composable 裡啟動 coroutine 的入口：進入 composition 時啟動，離開時取消，key 一變就取消重啟。我會把 effect 裡用到、變了就該重做的值都放進 key。」

### 3. 長時間運作的 effect，怎麼拿到最新的 callback？（`rememberUpdatedState`）

**如果 effect 已經啟動，之後外面傳進來的 callback 換成新的，effect 還在用舊的。`rememberUpdatedState` 解決這個問題：它回傳一個「永遠指向最新值」的 State，effect 讀這個 State，就能拿到最新的值，而且不用把它放進 key 而重啟 effect。**

情境：行情畫面 30 秒沒有操作，就呼叫 `onTimeout` 提示使用者。

```kotlin
@Composable
fun IdleTimer(onTimeout: () -> Unit) {
    // key 是 Unit：只啟動一次
    LaunchedEffect(Unit) {
        delay(30_000)
        // 這裡用的是「第一次組合時」傳進來的 onTimeout
        onTimeout()
    }
}
```

如果父層在這 30 秒內換了一個新的 `onTimeout`（例如它捕獲的變數不同），effect 還是呼叫舊的。這種「長時間運作的 lambda，抓到的是當初的舊值」的問題，程式設計上稱為 stale closure。

直覺的修法是 `LaunchedEffect(onTimeout)`，把 `onTimeout` 當 key。但這會讓計時器在每次 `onTimeout` 換掉時**重新開始算 30 秒**，不是我們要的。正確做法是 `rememberUpdatedState`：

```kotlin
@Composable
fun IdleTimer(onTimeout: () -> Unit) {
    // 永遠是最新一次組合傳進來的值
    val currentOnTimeout by rememberUpdatedState(onTimeout)

    // 計時器不會因為 onTimeout 換掉而重啟
    LaunchedEffect(Unit) {
        delay(30_000)
        // 30 秒後呼叫「最新」的 onTimeout
        currentOnTimeout()
    }
}
```

判斷準則：effect **不該因為這個值變了而重啟**，但執行到某一刻時**要讀到它的最新值**，就用 `rememberUpdatedState`。

> [!TIP]
> 面試時可以這樣說：「LaunchedEffect(Unit) 只會啟動一次，如果它捕獲的 callback 之後換掉了，會一直用舊的。我不想為了這個重啟計時，所以用 rememberUpdatedState 包住 callback，effect 裡讀最新的值。」

### 4. 註冊 listener 要用什麼？`DisposableEffect` 和 `SideEffect` 差在哪？

**需要「進入時註冊、離開時解除」的東西，用 `DisposableEffect`；需要「每次重組成功後，把最新的值同步給非 Compose 的物件」，用 `SideEffect`。**

**DisposableEffect：** 解決「註冊了 listener，卻忘了解除」的問題，例如 WebSocket 的 listener、Lifecycle observer、BroadcastReceiver。沒解除，物件會一直被抓著，回呼一直被觸發，這叫 listener 洩漏（leak）。

```kotlin
@Composable
fun SocketListener(socket: Socket, vm: TickerViewModel) {
    DisposableEffect(socket) {
        val listener = Socket.Listener { event -> vm.onSocketEvent(event) }
        // 進入 composition：註冊
        socket.addListener(listener)
        // 離開 composition 或 socket 換了：解除
        onDispose { socket.removeListener(listener) }
    }
}
```

- 區塊最後一定要寫 `onDispose { }`，不寫會編譯錯誤。
- key（這裡的 `socket`）改變時，Compose 會**先執行舊的 `onDispose`，再執行新的區塊**，所以不會同時掛著兩個 listener。
- `onDispose` 要把區塊裡做的事全部還原：註冊了什麼，就解除什麼。

**SideEffect：** 每一次**成功重組之後**都會執行，沒有清理。用途是把 Compose 裡的值，同步給不屬於 Compose 的物件，例如舊的 View、第三方圖表。

```kotlin
@Composable
fun ChartHost(theme: ChartTheme, legacyChart: LegacyChartView) {
    SideEffect {
        // 每次重組成功後，把最新的 theme 交給舊的 View
        legacyChart.setTheme(theme)
    }
}
```

「成功」的意思是：如果這次重組做到一半被放棄（例如 State 又變了），`SideEffect` 不會執行。

**不要用 `SideEffect` 做「只該做一次」的事**，例如送 screen view 事件。畫面每重組一次就會送一次。這種事用 `LaunchedEffect(Unit)`。

> [!TIP]
> 面試時可以這樣說：「DisposableEffect 是進場註冊、離場在 onDispose 清理，key 變了會先清舊的再跑新的。SideEffect 是每次重組成功後，把最新的值同步給非 Compose 的物件，沒有清理。兩個差別在要不要清理，以及什麼時候執行。」

### 5. 使用者點按鈕時要啟動 coroutine，為什麼要用 `rememberCoroutineScope`？

**`rememberCoroutineScope` 是給「使用者操作」用的：它回傳一個綁定在這個 Composable 上的 coroutine scope，讓你可以在 `onClick` 這種事件 callback 裡啟動 coroutine。「進入畫面就做一次」是 `LaunchedEffect` 的工作，不是它的工作。**

`LaunchedEffect` 本身是 Composable，不能在 `onClick` 裡呼叫；而 `onClick` 裡需要啟動 coroutine（例如捲動動畫是 suspend 函式），就要自己有一個 scope：

```kotlin
@Composable
fun TickList(listState: LazyListState) {
    val scope = rememberCoroutineScope()

    Button(onClick = {
        // 正確：在事件裡啟動
        scope.launch { listState.animateScrollToItem(0) }
    }) { Text("回到頂端") }
}
```

**常見的誤用：**

```kotlin
@Composable
fun Wrong(repo: Repo) {
    val scope = rememberCoroutineScope()
    // 錯誤：寫在函式裡，每次重組都再啟動一次
    scope.launch { repo.load() }
}
```

「進入畫面時載入一次資料」要用 `LaunchedEffect(Unit) { repo.load() }`。

另一個要注意：這個 scope 綁在 Composable 的 composition 上，**使用者離開畫面，scope 就取消**。所以不要拿來做「必須做完」的工作，例如送出訂單：使用者按下送出、馬上返回，請求被取消，不知道單到底有沒有送出。這類工作屬於 ViewModel（用 `viewModelScope`），不受畫面離開影響。

> [!TIP]
> 面試時可以這樣說：「rememberCoroutineScope 是在事件回呼，例如 onClick，裡面啟動 coroutine 用的；進入畫面要做一次的事用 LaunchedEffect。scope 會在離開畫面時取消，所以送出訂單這種必須做完的工作我放在 ViewModel。」

### 6. `produceState`、`snapshotFlow`、`derivedStateOf` 各自在轉換什麼？

這三個都是「State 和外界之間的轉換」，容易搞混。記住方向：

| API | 方向 | 解決什麼問題 |
|---|---|---|
| `produceState` | 外面的非同步資料 → Compose 的 State | 把 suspend 函式或 callback 的結果，變成可以直接在畫面讀的 State |
| `snapshotFlow` | Compose 的 State → Flow | 想用 Flow 的運算子（例如 `distinctUntilChanged`）處理 State 的變化 |
| `derivedStateOf` | State → 另一個 State | 輸入變得很頻繁、輸出很少變時，只在輸出變了才通知重組 |

**`produceState`：** 啟動一個 coroutine，把結果寫進 `value`，並以 `State` 回傳。離開 composition 時 coroutine 自動取消。

```kotlin
@Composable
fun rememberBalance(repo: AssetRepo, currency: String): State<Balance?> =
    // 第一個參數是初始值；currency 變了就重新執行
    produceState<Balance?>(null, currency) {
        // suspend 函式，結果寫進 value
        value = repo.fetchBalance(currency)
    }
```

**`snapshotFlow`：** 在大括號裡讀取 State，State 變了就發射新值，而且自動去重（相同的值不會重複發射）。它在 `LaunchedEffect` 裡用：

```kotlin
LaunchedEffect(listState) {
    // 讀 Compose 的 State
    snapshotFlow { listState.firstVisibleItemIndex }
        .distinctUntilChanged()
        // 當成 Flow 處理
        .collect { vm.onScrolled(it) }
}
```

**`derivedStateOf`：** 情境是「回到頂端」按鈕。列表捲動時，`firstVisibleItemIndex` 會從 0 變 1、2、3…，但按鈕只關心「是不是大於 0」。

```kotlin
val listState = rememberLazyListState()

// 沒有 derivedStateOf：index 每變一次，讀取它的地方就重組一次
val showTop1 = listState.firstVisibleItemIndex > 0

// 有 derivedStateOf：布林值從 false 變 true（或相反）時，才通知重組
val showTop2 by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 0 }
}
```

`derivedStateOf` 不是越多越好，有三個邊界：

- 輸出和輸入同步變化（例如 `price * qty`，每次輸入變，結果也變）時沒有效益，直接計算，或用 `remember(price, qty)` 就好。
- 它前面要包 `remember`。沒有 `remember`，每次重組都會建立新的 derived state，快取就失效了。
- 它追蹤的是 **State** 的讀取。如果依賴的是一般的函式參數（不是 State），參數變了它不會自己更新，要寫成 `remember(param) { derivedStateOf { … } }`。

> [!TIP]
> 面試時可以這樣說：「produceState 是把外面的非同步資料拉進 Compose，snapshotFlow 是反過來把 Compose 的 State 變成 Flow。derivedStateOf 只在輸入變得很頻繁、輸出很少變的時候才有用，例如回到頂端按鈕；輸出跟著輸入一起變的話，就是多餘的成本。」

### 7. 什麼情況用哪一個？

先問自己三個問題：

1. **什麼時候做事？** 進入畫面時、某個值變了時、每次重組成功後，還是使用者操作時？
2. **離開時需要清理嗎？** 註冊了 listener 就需要；啟動了 coroutine，Compose 會自動取消。
3. **要做的事會撐過旋轉或離開畫面嗎？** 要的話，放 ViewModel，不是 effect。

| 我想做的事 | 用這個 | 補充 |
|---|---|---|
| 進入畫面時（或某個值變了時）啟動 suspend 工作，離開自動取消 | `LaunchedEffect(key)` | 依 `symbol` 訂閱行情、一次性載入 |
| 長時間運作的 effect，要呼叫最新的 callback，又不想重啟 | `rememberUpdatedState` | 搭配 `LaunchedEffect(Unit)` |
| 註冊 listener 或 observer，離開時要解除 | `DisposableEffect` | 一定要寫 `onDispose { }` |
| 每次重組成功後，把值同步給非 Compose 的物件 | `SideEffect` | 不要拿來做「只該做一次」的事 |
| 使用者點擊時才啟動 coroutine | `rememberCoroutineScope` | 只在事件 callback 裡用 |
| 把 callback 或 suspend 的資料變成 State | `produceState` | 外面 → Compose |
| 把 State 的變化用 Flow 運算子處理 | `snapshotFlow` | Compose → Flow |
| 輸入變得頻繁、輸出很少變 | `derivedStateOf` | 要包 `remember` |
| 必須做完的工作（送出訂單）、要撐過旋轉的訂閱 | ViewModel | 不是 effect |

> [!TIP]
> 面試時可以這樣說：「我選 effect API 的方式，是看三件事：什麼時候執行、離開時要不要清理、工作要不要撐過畫面離開。進場做 suspend 工作用 LaunchedEffect，要註冊解除用 DisposableEffect，使用者操作用 rememberCoroutineScope，必須做完的工作放 ViewModel。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin07SideEffects**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin07-side-effects/`，解答在 `labs/android/solution/kotlin/kotlin07-side-effects/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不用看畫面**：effect 是 Compose runtime（Compose 的執行環境）的功能，不需要畫面也能執行。這個單元的演練都用 LabKit 的 `withHeadlessCompose`，在沒有畫面的情況下真的組合與重組一個 Composable，再把「effect 做了幾次事」寫進 log，所以按「執行」就有自動檢查，也可以在電腦上跑 `./gradlew :practice-kotlin:testDebugUnitTest` 看結果。畫面上的文字改用 `HeadlessText`（`Text` 的替身）。

### 演練 1：價格一跳，就再訂閱一次（第 1 題）

**檔案**：`L01_SideEffectInBody.kt`

**情境**：行情畫面進來時要向伺服器訂閱這個交易對（`socket.subscribe(symbol)`），畫面同時顯示價格。價格每 30 ms 更新一次，共 10 次。`L01Socket` 會記下訂閱了幾次。

**怎麼寫**

1. **Bug 版**：在 `TickerScreen` 的函式裡直接呼叫 `socket.subscribe(symbol)`。
2. **修正版**：把訂閱放進 `LaunchedEffect(symbol) { socket.subscribe(symbol) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 價格更新 10 次之後「共訂閱 11 次」：進入畫面 1 次，加上每次重組各 1 次 |
| 修正版 | 「進入畫面時，共訂閱 1 次」，價格更新之後仍然是「共訂閱 1 次」 |

**為什麼**：Compose 不保證 Composable 只執行一次：價格一變就重組，函式又被執行一次，直接寫在裡面的副作用也跟著再做一次。effect API 由 Compose 決定什麼時候開始、什麼時候清理：`LaunchedEffect(symbol)` 在進入畫面時執行一次，只有 `symbol` 變了才會重新執行。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L01_SideEffectInBody.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L01_SideEffectInBody.kt#fixed
```

### 演練 2：切換交易對，卻還在收舊的行情（第 2 題）

**檔案**：`L02_KeyTooFew.kt`

**情境**：行情畫面依 `symbol` 訂閱價格並顯示。使用者先看 BTC_JPY，0.1 秒後切換成 ETH_JPY。`L02Feed.prices(symbol)` 每 15 ms 送一次價格，並記下目前有哪些交易對正在被收集。

**怎麼寫**

1. **Bug 版**：`LaunchedEffect(Unit) { feed.prices(symbol).collect { price = it } }`，key 寫 `Unit`。
2. **修正版**：key 換成 `symbol`：`LaunchedEffect(symbol) { … }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「切換後，畫面顯示：ETH_JPY：--」，而且「正在收行情的交易對：BTC_JPY」：舊的訂閱沒取消，新的也沒開始 |
| 修正版 | 「切換後，畫面顯示：ETH_JPY：ETH_JPY 價格 …」，「正在收行情的交易對：ETH_JPY」：舊的取消了 |

**為什麼**：key 決定 effect 什麼時候重做。`Unit` 永遠不變，effect 只在進入畫面時執行一次，裡面抓到的 `symbol` 就一直是第一次的 BTC_JPY。規則是：effect 裡面用到、而且變了就該重做的值，都要當成 key。key 一變，Compose 會取消舊的 coroutine（所以 Flow 的 `finally` 才會執行），再用新值啟動新的。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L02_KeyTooFew.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L02_KeyTooFew.kt#fixed
```

### 演練 3：價格一直跳，逾時提醒永遠不響（第 2 題）

**檔案**：`L03_KeyTooMany.kt`

**情境**：行情畫面進來 400 ms 後要響一次逾時提醒（正式版是 5 秒，這裡縮短）。價格每 60 ms 更新一次，共 12 次。`L03Alarm` 記下計時開始了幾次、提醒響了幾次。

**怎麼寫**

1. **Bug 版**：`LaunchedEffect(price) { alarm.start(); delay(400); alarm.ring() }`，把 `price` 當 key。
2. **修正版**：key 改成 `Unit`。計時只和「進入畫面」有關，和價格無關。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「計時開始了 12 次」、「逾時提醒響了 0 次」：計時每 60 ms 就被取消、重新開始 |
| 修正版 | 「計時開始了 1 次」、「逾時提醒響了 1 次」 |

**為什麼**：key 寫太多和寫太少是相反的 bug。把「每次重組都會變」的值（價格）當 key，每變一次，舊的計時就被取消、重新等 400 ms，永遠等不到。這種 bug 不會報錯，只能靠 log 或測試看出來。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L03_KeyTooMany.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L03_KeyTooMany.kt#fixed
```

### 演練 4：委託狀態一筆都沒收到（第 2 題）

**檔案**：`L04_CollectBlocks.kt`

**情境**：交易畫面要同時收兩個不會結束的 Flow：行情（`server.ticks`）和委託狀態（`server.orders`），各自每 20 ms 送一筆。進入畫面 300 ms 後，看各收到幾筆。

**怎麼寫**

1. **Bug 版**：在同一個 `LaunchedEffect(Unit)` 裡，先 `server.ticks.collect { onTick() }`，下一行再 `server.orders.collect { onOrder() }`。
2. **修正版**：兩段 `collect` 各自放進 `launch { }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 收到行情約 10 筆，收到委託狀態 0 筆 |
| 修正版 | 行情和委託狀態都收到約 10 筆 |

**為什麼**：`collect` 是 suspend 函式，Flow 不結束，它就不會回傳，後面的程式碼永遠執行不到。要同時收兩個不會結束的 Flow，就要各自用 `launch { }` 啟動。`LaunchedEffect` 離開畫面時，兩個 coroutine 會一起取消。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L04_CollectBlocks.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L04_CollectBlocks.kt#fixed
```

### 演練 5：計時器響的時候，呼叫的是舊的 callback（第 3 題）

**檔案**：`L05_StaleCallback.kt`

**情境**：`IdleTimer` 在進入畫面 400 ms 後呼叫 `onTimeout`。進入 250 ms 時，父層換了一個新的 `onTimeout`（新的 lambda 抓到的設定版本從 1 變成 2）。計時器響的時候，應該呼叫版本 2，而且不能因此重新計時。

**怎麼寫**

1. **Bug 版**：`LaunchedEffect(Unit) { delay(400); onTimeout() }`。
2. **修正版**：`val currentOnTimeout by rememberUpdatedState(onTimeout)`，effect 裡 `delay(400)` 之後呼叫 `currentOnTimeout()`，key 仍然是 `Unit`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「計時器響的時候，呼叫的是版本 1 的 onTimeout」：用的是第一次傳進來的舊 callback |
| 修正版 | 「呼叫的是版本 2 的 onTimeout」，而且「計時器響在進入畫面後」約 400 ms |

**為什麼**：`LaunchedEffect(Unit)` 只啟動一次，區塊裡抓到的 `onTimeout` 是第一次組合時的版本（stale closure：長時間運作的 lambda 抓著舊值）。直接寫 `LaunchedEffect(onTimeout)` 能拿到新的，但 `onTimeout` 一換，計時就重新等 400 ms（重啟會在 650 ms 以後才響）。`rememberUpdatedState` 的用途就是：effect 不該因為這個值變了而重啟，但執行到某一刻時要讀到最新的值。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L05_StaleCallback.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L05_StaleCallback.kt#fixed
```

### 演練 6：離開畫面了，listener 還掛在 socket 上（第 4 題）

**檔案**：`L06_ListenerLeak.kt`

**情境**：行情畫面進入時向 WebSocket 註冊 listener（`addListener`），離開時應該解除（`removeListener`）。流程：進入畫面 → socket 推 3 筆價格 → 離開畫面 → socket 再推 3 筆價格。

**怎麼寫**

1. **Bug 版**：`DisposableEffect(socket)` 裡建立 listener 並 `socket.addListener(listener)`，`onDispose { }` 的大括號留空。
2. **修正版**：`onDispose { socket.removeListener(listener) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「離開畫面了，socket 上還註冊著 1 個 listener」，「離開畫面之後，又收到 3 筆價格」 |
| 修正版 | 「socket 上還註冊著 0 個 listener」，「離開畫面之後，又收到 0 筆價格」 |

**為什麼**：沒有解除註冊，socket 一直抓著 listener，回呼一直被觸發，listener 抓住的畫面物件也釋放不了（listener 洩漏）。`onDispose` 不寫會編譯錯誤，但寫了卻是空的，編譯器不會提醒你：註冊了什麼，就要在 `onDispose` 解除什麼。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L06_ListenerLeak.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L06_ListenerLeak.kt#fixed
```

### 演練 7：畫面只開一次，瀏覽事件卻送了六次（第 4 題）

**檔案**：`L07_SideEffectOnce.kt`

**情境**：行情畫面打開時要送一筆 `screen_view` 的分析事件（analytics），整個畫面期間只該送一次。價格每 40 ms 更新一次，共 5 次。

**怎麼寫**

1. **Bug 版**：`SideEffect { analytics.track("screen_view") }`。
2. **修正版**：`LaunchedEffect(Unit) { analytics.track("screen_view") }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「screen_view 事件送了 6 次」：進入畫面 1 次，加上價格更新造成的 5 次重組各 1 次 |
| 修正版 | 「screen_view 事件送了 1 次」 |

**為什麼**：`SideEffect` 在「每一次成功重組之後」都會執行，適合把最新的值同步給不屬於 Compose 的物件（舊的 View、第三方圖表），不適合只該做一次的事。只做一次要用 `LaunchedEffect(Unit)`。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L07_SideEffectOnce.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L07_SideEffectOnce.kt#fixed
```

### 演練 8：按了送出馬上返回，訂單沒送出去（第 5 題）

**檔案**：`L08_ScopeCancelled.kt`

**情境**：下單畫面按「送出」會呼叫伺服器（約 400 ms）。使用者按下送出，20 ms 後就返回上一頁。`L08OrderViewModel` 代表 ViewModel：它的 scope（`viewModelScope`）撐過畫面離開。

**怎麼寫**

1. **Bug 版**：`val scope = rememberCoroutineScope()`，`onSubmit` 裡 `scope.launch { vm.api.submit() }`。
2. **修正版**：`onSubmit` 改成呼叫 `vm.submit()`，送單交給 ViewModel 的 scope。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「開始送出訂單」之後沒有「伺服器收到訂單」，最後一行是「伺服器沒有收到訂單」 |
| 修正版 | 離開畫面之後仍然出現「伺服器收到訂單」，最後一行是「伺服器有收到訂單」 |

**為什麼**：`rememberCoroutineScope` 的 scope 綁在 Composable 的 composition 上，使用者離開畫面就取消。請求在半路被中斷，使用者也不知道單到底有沒有送出。送單這種必須做完的工作屬於 ViewModel。`rememberCoroutineScope` 適合只跟畫面有關的事，例如點按鈕後捲動列表的動畫。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L08_ScopeCancelled.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L08_ScopeCancelled.kt#fixed
```

### 演練 9：捲動每動一格，按鈕就重組一次（第 6 題）

**檔案**：`L09_DerivedState.kt`

**情境**：行情列表往下捲，第一個看得到的項目的位置（`firstVisibleItemIndex`）從 0 一路增加到 20，每 15 ms 加 1。「回到頂端」按鈕只關心「位置是不是大於 0」。演練用 `mutableIntStateOf` 代替 `LazyListState` 的位置。

**怎麼寫**

1. **Bug 版**：`val showTop = index.value > 0`，直接在 Composable 裡讀位置。
2. **修正版**：`val showTop by remember { derivedStateOf { index.value > 0 } }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「按鈕共執行了 21 次」：第一次組合，加上位置變動 20 次各重組 1 次 |
| 修正版 | 「按鈕共執行了 2 次」：第一次組合，加上 `false` 變 `true` 的那一次 |

**為什麼**：直接讀 State 的 Composable，State 每變一次就重組一次。`derivedStateOf` 只在計算結果換邊時才通知重組：位置從 1 變 2、3、4… 時結果都是 `true`，按鈕不用重組。它只在「輸入變得很頻繁、輸出很少變」時有幫助；輸出和輸入一起變（例如 `price * qty`）時，直接計算就好，而且 `derivedStateOf` 前面要包 `remember`。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L09_DerivedState.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L09_DerivedState.kt#fixed
```

### 演練 10：捲動位置只回報了第一次（第 6 題）

**檔案**：`L10_SnapshotFlow.kt`

**情境**：列表每捲動一格，要把第一個看得到的項目位置回報給 ViewModel（例如載入下一頁、送分析事件）。位置從 0 每 30 ms 加 1，加到 5。

**怎麼寫**

1. **Bug 版**：`LaunchedEffect(Unit) { report(index.value) }`，在 effect 裡直接讀 State。
2. **修正版**：`snapshotFlow { index.value }.distinctUntilChanged().collect { report(it) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「回報過的位置：[0]」：只通知了第一次 |
| 修正版 | 「回報過的位置：[0, 1, 2, 3, 4, 5]」 |

**為什麼**：`LaunchedEffect` 的區塊只執行一次，裡面讀 State 也只讀一次，之後怎麼變都沒有人通知它。`snapshotFlow` 在大括號裡讀 State，State 變了就發射新值（相同的值自動去重），讓你用 Flow 處理 Compose 的 State。方向和 `produceState` 相反：`snapshotFlow` 是 Compose 的 State 變成 Flow，`produceState` 是外面的非同步資料變成 State。

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L10_SnapshotFlow.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin07-side-effects/L10_SnapshotFlow.kt#fixed
```

第 7 題是整理「什麼情況用哪一個」的對照表，沒有獨立的演練；`produceState` 的 key 規則和演練 2 相同，沒有另外做演練。

## 常見陷阱

- **在 Composable 的函式裡直接寫入 State 或呼叫網路 API。** 每次重組都會執行一次。要放進 `LaunchedEffect` 等 effect API。
- **`DisposableEffect` 的 `onDispose` 裡漏掉解除註冊。** `onDispose` 不寫會編譯錯誤，但寫了卻沒有把每一項註冊都撤銷，一樣會造成 listener 洩漏。
- **`LaunchedEffect(Unit)` 裡捕獲了會變的參數。** 之後讀到的是舊的值。用 `rememberUpdatedState` 包住它。
- **用 `SideEffect` 做「只該做一次」的事（例如送 screen view 事件）。** 結果每次重組都送一次。這種事用 `LaunchedEffect(Unit)`。
- **`derivedStateOf` 沒有包 `remember`。** 每次重組都建立新的 derived state，快取失效，等於白做。
- **在同一個 `LaunchedEffect` 裡先 `collect` 一個不會結束的 Flow，後面還有程式碼。** 後面的程式碼永遠執行不到。各自用 `launch { }` 啟動。
- **用 effect 取代 ViewModel。** 把應該撐過旋轉的訂閱放進 `LaunchedEffect`，旋轉一次就重訂一次。
- **在 `rememberCoroutineScope` 的 scope 裡做必須做完的工作。** 使用者離開畫面，工作就被取消。

## 面試官想聽到的

1. 能依「進入或離開 composition」「每次重組」「使用者操作」三種時機，選對 effect API。
2. 清楚 key 的語意，能說出 key 寫太少（effect 用到舊值）和寫太多（effect 一直重啟）兩種 bug，並知道 `rememberUpdatedState` 的用途。
3. 能說出 `derivedStateOf` 什麼時候有用、什麼時候是多餘的成本。
4. 知道 `produceState` 和 `snapshotFlow` 的方向相反，也知道必須做完的工作要放 ViewModel。

> [!FIELD]
> 在一個即時行情 App 的 Android 版裡，首頁畫面用 `DisposableEffect` 搭配 `LifecycleEventObserver`：`ON_STOP` 時呼叫 `onEnterBackground`，`ON_START` 時呼叫 `onEnterForeground`，正是 `DisposableEffect` 的標準用法。專案裡也有使用 `derivedStateOf` 和 `snapshotFlow`。
> K 線更新是另一個可以講的例子：iOS 版記錄 `lastPushedTime`（已經推送到哪一根 K 棒），把上次之後的每一根都推送，所以 App 從背景回前景、重抓資料合併後，圖表不會缺少中間的 K 棒。在 Android 做 K 線時，也可以採用同樣的做法：用 `remember` 記錄已推送到哪一根，再搭配 `LaunchedEffect` 與 `snapshotFlow` 取出新增的 K 棒全部推送。

## iOS / Android 對照

| 需求 | Compose | SwiftUI |
|---|---|---|
| 進入畫面時啟動非同步工作，離開自動取消 | `LaunchedEffect(key)` | `.task(id:)`：view 出現時啟動，消失時自動取消，`id` 變了會取消重啟 |
| 註冊與解除 listener | `DisposableEffect` 搭配 `onDispose` | `.onAppear`／`.onDisappear`，或 `.onReceive` 訂閱 Publisher |
| 每次更新後同步給外部 | `SideEffect` | `.onChange(of:)`，只在指定的值改變時才觸發，不是每次 |
| 從頻繁的輸入推導出變化少的輸出 | `derivedStateOf` | 在 ViewModel 內推導，或把 view 拆小，縮小 `body` 重新求值的範圍 |
| 把 State 的變化轉成串流 | `snapshotFlow` | `Publisher`（`$property`）或 `AsyncStream` |
| 使用者操作時啟動非同步工作 | `rememberCoroutineScope` | 在按鈕的 action 裡用 `Task { }` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 副作用 | side effect | 副作用（サイドエフェクト） |
| 進入／離開 composition | enter／leave the composition | コンポジションに入る／抜ける |
| effect 的 key | key of an effect | キー |
| 用到舊的值（長時間運作的 lambda 抓著舊值） | stale closure | 古い値を捕獲したクロージャ |
| listener 洩漏 | listener leak | リスナーリーク |
| suspend 函式 | suspend function | サスペンド関数 |
