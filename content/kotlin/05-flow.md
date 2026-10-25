---
id: kotlin-flow
title: Flow：cold 與 hot、StateFlow／SharedFlow／Channel 怎麼選、行情怎麼丟舊值、跟著畫面收集
summary: 用交易所行情與訂單的情境，說明 Flow 在 Android 面試的重點：cold 與 hot 的差別、StateFlow／SharedFlow／Channel 怎麼選、conflate／sample／debounce 差在哪、stateIn(WhileSubscribed(5000)) 為什麼是 5 秒、怎麼用 callbackFlow 包 WebSocket，以及怎麼跟著畫面生命週期收集。
priority: P0
day: 2
tags: [flow, stateflow, sharedflow, channel, backpressure, callbackflow, lifecycle]
---

## 重點講解

這個單元回答七個面試常見問題：

1. Flow 是什麼？cold 和 hot 差在哪？
2. `StateFlow`、`SharedFlow`、`Channel` 怎麼選？
3. 導航、Toast 這種一次性事件怎麼傳才不會掉？
4. 行情資料進 UI 時，`conflate`、`sample`、`debounce` 差在哪？
5. `stateIn(WhileSubscribed(5000))` 是什麼？為什麼是 5 秒？
6. 怎麼用 `callbackFlow` 把 WebSocket 的 callback 包成 Flow？
7. 怎麼讓 Flow 的收集跟著畫面的生命週期停啟？

### 1. Flow 是什麼？cold 和 hot 差在哪？

Flow 是「隨著時間陸續送出一連串值」的資料流，例如一檔幣種的即時價格。**cold flow 是沒人收集就不會執行，每個收集者各自從頭跑一次；hot flow 是不管有沒有人在收，資料都在發。**

先把三個詞講清楚：`emit` 是在 Flow 裡發送一筆資料；`collect` 是接收資料；collector 就是在收集的那一方，通常是 UI。

可以用影片來比喻。cold 像 YouTube 影片，每個人按下播放，各自從頭看；hot 像電視直播，不管有沒有人看，節目都在播，你晚進來就錯過前面的內容。

```kotlin
val cold = flow {                  // flow { } 建立的是 cold flow
    println("上游開始")
    emit(1)
    emit(2)
}
cold.collect { println("A $it") }  // 第一個 collector：上游跑一次
cold.collect { println("B $it") }  // 第二個 collector：上游又從頭跑一次
// 輸出：上游開始、A 1、A 2、上游開始、B 1、B 2
```

為什麼這件事在 Android 很重要？如果上游是「開一條 WebSocket 去訂閱行情」，而 ViewModel 把這個 cold flow 直接給畫面，那麼每多一個 collector（旋轉螢幕後畫面重建、兩個元件同時收集），就會多開一條 WebSocket。解法是把 cold flow 轉成 hot flow，讓大家共用同一條上游（第 5 題的 `stateIn` 就是做這件事）。

cold flow 還有一個性質：collector 處理得慢，上游的 `emit` 就會等它處理完才繼續，不會把資料堆起來。這叫天然的 backpressure（背壓：上游發得比下游處理得快時，要等、要丟、還是要堆起來）。

另一個容易搞混的點：`StateFlow` 和 `SharedFlow` 是 hot flow，而且永遠不會「結束」，collector 一直掛在那裡等下一筆資料。

> [!TIP]
> 面試時可以這樣說：「cold flow 沒有人收集就不執行，每個 collector 各自從頭跑一次；hot flow 不管有沒有人收都在發，`StateFlow` 和 `SharedFlow` 都是。所以 ViewModel 不能把開 WebSocket 的 cold flow 直接給畫面，要用 `stateIn` 轉成 hot 共用。」

### 2. `StateFlow`、`SharedFlow`、`Channel` 怎麼選？

**畫面狀態用 `StateFlow`；廣播給多個人看的事件流用 `SharedFlow`；每一筆只能被一個人處理的工作用 `Channel`。** 三個都有各自「會丟資料」的地方，要知道。

| 種類 | 特性 | 重點語意 |
|---|---|---|
| `StateFlow`（hot） | 一定要有初始值，永遠有「目前值」 | **只保留最新值**，設定相同的值（用 `equals` 判斷）不會再發；慢的 collector 會漏掉中間的值；`update { }` 是原子操作（同時更新不會互相蓋掉） |
| `SharedFlow`（hot） | 可設定 `replay`、`extraBufferCapacity`、`onBufferOverflow` | 預設 replay 0、沒有 buffer：**沒有人訂閱時發的資料直接消失** |
| `Channel` | 點對點的管道 | 每一筆資料只會被**一個**接收者拿走；用 `receiveAsFlow()` 變成 Flow 時，多個 collector 是「分食」，不是各拿一份 |

**`StateFlow`** 最適合放畫面狀態，因為畫面只在意「現在是什麼」：

```kotlin
val s = MutableStateFlow(0)
val job = launch(start = CoroutineStart.UNDISPATCHED) {   // 讓 collector 立刻跑到第一個暫停點
    s.collect { println("got $it") }
}
s.value = 1
s.value = 1      // 和目前值相同，不會發射
s.value = 2      // 前面的 1 還沒被收集就被蓋掉
delay(50)
job.cancel()
// 輸出：got 0、got 2
```

collector 先收到目前值 `0`；之後連續設定 `1`、`1`、`2`，期間 collector 沒機會執行，`StateFlow` 只保留最新的 `2`。所以它**不適合每一筆都要處理的資料**（例如 order book 的增量更新）。另一個陷阱：`StateFlow` 裡放可變物件並原地修改，因為 `equals` 判斷相同，不會發射，要換成新的不可變物件。

**`SharedFlow`** 是廣播用的。`replay` 是「新訂閱者進來時，補送最近幾筆給它」：

```kotlin
val plain = MutableSharedFlow<Int>()                 // 預設 replay 0
plain.emit(1)                                        // 沒有訂閱者，值直接消失
val j1 = launch(start = CoroutineStart.UNDISPATCHED) { plain.collect { println("plain $it") } }
plain.emit(2)                                        // 輸出：plain 2

val replayed = MutableSharedFlow<Int>(replay = 1)    // 保留最後 1 筆給新訂閱者
replayed.emit(10)
replayed.emit(11)                                    // 10 被擠掉
val j2 = launch(start = CoroutineStart.UNDISPATCHED) { replayed.collect { println("replay $it") } }
// 輸出：replay 11
```

這是 `SharedFlow` 最常被誤用的地方：以為它像 `StateFlow` 一樣有目前值，結果先發的資料沒人收到。

`onBufferOverflow` 決定 buffer 滿了怎麼辦：`SUSPEND`（預設，`emit` 等待）、`DROP_OLDEST`（丟最舊的）、`DROP_LATEST`（丟最新的）。`StateFlow` 概念上相當於 `replay = 1`、`DROP_OLDEST`，再加上「值相等就不發」。

**`Channel`** 是點對點：

```kotlin
val ch = Channel<Int>(Channel.BUFFERED)
launch { for (x in ch) println("A $x") }   // 兩個接收者
launch { for (x in ch) println("B $x") }
for (i in 1..4) ch.send(i)                  // 送出 4 筆，總共只會被收到 4 次，不是 8 次
ch.close()
```

`Channel` 的容量有幾種：`RENDEZVOUS`（0，沒有緩衝，送的人要等收的人）、`BUFFERED`（有緩衝，大小預設 64）、`CONFLATED`（只留最新一筆）、`UNLIMITED`（沒有上限，要小心記憶體用光）。不指定容量時，`Channel()` 是 `RENDEZVOUS`。

此外，`flow { }` 裡面不能用 `withContext` 切換執行緒來 emit，執行時會丟出 `IllegalStateException`（Flow invariant is violated）。因為 Flow 規定「發送資料的 context 不能亂換」，這叫 context preservation。要換執行緒用 `flowOn`（第 5 題）；需要從多個 context 發送，用 `channelFlow`。

> [!TIP]
> 面試時可以這樣說：「`StateFlow` 是有目前值的狀態，會去重、只保留最新值；`SharedFlow` 是事件流，可以調 replay 和 buffer，也沒有初始值；`Channel` 是點對點，每一筆只給一個接收者。畫面狀態我用 `StateFlow`。」

### 3. 導航、Toast 這種一次性事件怎麼傳才不會掉？

**Android 官方的建議是把事件「當成 state」：ViewModel 保存「待處理的事件」，UI 處理完再通知 ViewModel 清掉。** 這樣畫面在背景或被重建（旋轉）時都不會遺失事件，處理完清掉也不會重複處理。

情境：下單成功後，要導航到訂單詳情，而且這個事件只能處理一次。

```kotlin
data class OrderUiState(val pendingNavigation: OrderId? = null)

class OrderViewModel : ViewModel() {
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    fun onOrderPlaced(id: OrderId) {
        _state.update { it.copy(pendingNavigation = id) }       // 把「要導航」存進 state
    }
    fun onNavigated() {
        _state.update { it.copy(pendingNavigation = null) }     // UI 導航完，通知 ViewModel 清掉
    }
}
// UI 端（Compose）：
// LaunchedEffect(state.pendingNavigation) {
//     state.pendingNavigation?.let { navigate(it); viewModel.onNavigated() }
// }
```

其他做法的問題：

- `SharedFlow(replay = 0)`（預設）：畫面在背景時沒有訂閱者，事件直接消失。
- `SharedFlow(replay = 1)`：最後一個事件會重播給之後的每個訂閱者，旋轉螢幕後新的畫面又收到一次，導航被重複執行。
- `Channel(BUFFERED).receiveAsFlow()`：這是常見的次選，事件會暫存到有人收，簡單好寫。但如果收集端取出事件之後、還沒處理完之前就被取消（畫面被銷毀），這筆事件就沒有人處理了。Toast 這種漏掉也無妨的可以用，導航或下單結果建議放進 state。

> [!TIP]
> 面試時可以這樣說：「導航這種只能處理一次、又不能掉的事件，我放進 state，UI 處理完呼叫 `onNavigated()` 清掉。用 `SharedFlow` 在背景會掉事件，`replay = 1` 旋轉後會重複處理。」

### 4. 行情資料進 UI 時，`conflate`、`sample`、`debounce` 差在哪？

三個都是「丟掉一些值」的方法，但丟的方式不同：**價格只在意最新值，所以用 `conflate` 或 `sample`；`debounce` 是給搜尋框用的，用在持續密集更新的價格串流上會幾乎不更新。**

情境：WebSocket 每秒推送幾十筆價格，但畫面不可能每筆都重畫。用一個簡化的串流測試：每 100 毫秒發一筆，共 6 筆（價格 1 到 6），在 `runTest` 的虛擬時間下觀察（`@時間` 是收到的毫秒數）：

```kotlin
fun ticker() = flow { for (p in 1..6) { emit(p); delay(100) } }

ticker().collect { /* 立即處理 */ }
// 全部收到：1@0、2@100、3@200、4@300、5@400、6@500

ticker().collect { delay(250) }   // collector 每筆要處理 250 毫秒，而且沒有丟舊值
// 1@0、2@350、3@700 … 6@1750（越來越落後：價格 6 在 500 就發生了，卻到 1750 才顯示）

ticker().conflate().collect { delay(250) }
// conflate：collector 忙的時候，只留最新的一筆：1@0、3@250、5@500、6@750

ticker().sample(250).collect { }
// sample：固定週期取最新的一筆：3@250、5@500

ticker().debounce(300).collect { }
// debounce：要「安靜」滿 300 毫秒才發。這個串流每 100 毫秒就來一筆，從不安靜，
// 整個過程一筆都沒發，直到串流結束才發出最後一筆：6@600
```

整理：

| 運算子 | 行為 | 適用 |
|---|---|---|
| `conflate()` | collector 忙的時候丟掉中間的值，永遠只留最新的 | 最新價、best bid／best ask（最優買價／賣價）這種「只看最後狀態」的資料 |
| `sample(ms)` | 每隔固定時間取最新的一筆，這段時間沒有新值就不發 | 想把更新頻率壓到畫面能承受的上限（例如每 100 毫秒） |
| `debounce(ms)` | 安靜滿 `ms` 毫秒後才發 | **搜尋框**（使用者停止輸入才查詢）；不能用在持續密集更新的價格串流 |
| `buffer(n)` | 讓生產與消費的速度脫鉤 | 突發流量；但 `UNLIMITED` 沒有上限，記憶體會一直長 |

**order book 的增量更新不能丟。** order book 是買賣掛單的完整清單（日文是板）；delta 是增量更新（例如「這一檔多了一筆」）；快照（snapshot）是某一刻的完整內容。丟掉任何一筆 delta，本機維護的 order book 就和交易所對不上了，所以 delta 不能 `conflate` 或 `sample`。正確的做法是：在背景執行緒依序套用每一筆 delta、維護本機的 order book，再對外發佈「快照」，UI 層才對快照做 `conflate` 或 `sample`。

幾個常一起問的運算子：

- `combine(a, b)`：兩邊都至少發過一次才開始發，之後任何一邊更新就重新計算（例如價格和匯率組合成日圓價格）。`zip` 則是一對一配對。
- `flatMapLatest`：新值到來就取消前一個內層 flow，用在「切換交易對就取消舊的訂閱」。
- `collectLatest`：新值到來就取消上一次還在處理的區塊。

> [!TIP]
> 面試時可以這樣說：「價格只在意最新值，所以用 `conflate` 或 `sample` 壓到畫面能承受的頻率。`debounce` 要等安靜才發，一直密集更新的價格串流永遠不安靜，所以不適合，它是給搜尋框用的。order book 的 delta 每筆都不能丟，要在背景依序套用，再發快照給 UI。」

### 5. `stateIn(WhileSubscribed(5000))` 是什麼？為什麼是 5 秒？

`stateIn` 把一個 cold flow 轉成 `StateFlow`（hot），讓所有 collector 共用同一份上游。`WhileSubscribed(5000)` 的意思是：**沒有人收集之後，再等 5 秒才停掉上游。** 5 秒是為了撐過螢幕旋轉或短暫切換 App，不必重建 WebSocket 訂閱；真的離開畫面超過 5 秒才停，省電又省流量。

```kotlin
class TickerViewModel(repo: TickerRepo) : ViewModel() {
    val uiState: StateFlow<UiState> = repo.tickers()       // repo.tickers() 是 cold flow（開 WebSocket）
        .map { it.toUi() }
        .stateIn(
            scope = viewModelScope,                         // 共用的上游跑在 ViewModel 的 scope
            started = SharingStarted.WhileSubscribed(5_000),// 什麼時候開始、什麼時候停
            initialValue = UiState.Loading                  // StateFlow 一定要有初始值
        )
}
```

`started` 有三種：

- `Eagerly`：立刻開始，永遠不停。
- `Lazily`：第一個訂閱者出現時才開始，之後永遠不停。
- `WhileSubscribed(stopTimeoutMillis)`：有訂閱者就跑，訂閱者數量降到 0 之後再等 `stopTimeoutMillis` 毫秒才停。

用虛擬時間看一遍旋轉的情境（`starts` 記錄上游啟動了幾次）：

```text
collector 消失（旋轉）  →  4 秒後新的 collector 出現  →  上游沒有重啟（starts 仍是 1），新 collector 立刻拿到最近的值
collector 全部消失  →  超過 5 秒沒有人  →  上游停止
之後又有 collector 出現  →  上游重新啟動（starts 變 2）
```

**為什麼是 5 秒？** 旋轉螢幕時 Activity 會被銷毀再重建，collector 消失到新 collector 出現通常遠少於 5 秒，所以上游不會被打斷；使用者真的離開 App，超過 5 秒沒有人收，才停掉。官方範例常用 5 秒，這是經驗值，不是硬性規定。如果用 `WhileSubscribed(0)`，旋轉的瞬間上游就會停掉再重啟，WebSocket 要重連；用 `Lazily` 或 `Eagerly` 則上游永遠不會停，退到背景也在耗電。

幾個相關的小點：

- `flowOn(dispatcher)` 只影響**它上游**的執行緒；下游仍在 collector 的 context 執行。例如 `flow { 解析 JSON }.flowOn(Dispatchers.Default)`，解析在背景執行緒，收集在主執行緒。
- `shareIn` 和 `stateIn` 類似，但沒有「目前值」，可以設定 `replay`，適合共用事件流。

> [!TIP]
> 面試時可以這樣說：「`WhileSubscribed(5000)` 是沒有人收集之後等 5 秒才停掉上游。5 秒是為了撐過旋轉，不用重連 WebSocket；使用者真的離開才停，省電省流量。如果沒有 `stateIn`，每個 collector 都會重新啟動 cold flow，等於多開一條連線。」

### 6. 怎麼用 `callbackFlow` 把 WebSocket 的 callback 包成 Flow？

**`callbackFlow` 用來把「註冊 listener、等 callback 通知」風格的 API 包成 Flow。寫的時候有三個一定要做的事：callback 裡用 `trySend` 發資料、最後一定要 `awaitClose` 解除註冊、價格型的資料要加 `.conflate()`。**

```kotlin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

fun OkHttpClient.tickers(request: Request): Flow<Ticker> = callbackFlow {
    val ws = newWebSocket(request, object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            trySend(parse(text))               // callback 不是 suspend 函式，所以用 trySend，不能用 send
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            close(t)                           // 連線失敗：讓 Flow 以這個例外結束
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close()                            // 正常關閉：讓 Flow 正常結束
        }
    })
    awaitClose { ws.close(1000, null) }        // 必寫：collector 取消或 Flow 關閉時，關掉連線
}.conflate()                                    // 價格只要最新值，collector 忙時丟掉中間的值
```

三個重點的原因：

1. **`awaitClose`**：它讓 producer（發資料的那一側）停在這裡，直到 Flow 被取消或關閉，並在區塊內解除註冊。少了它，`callbackFlow` 會直接丟 `IllegalStateException`，這是刻意設計來防止你忘記解除 listener 而洩漏。
2. **`trySend` 而不是 `send`**：callback 不是 suspend 函式，沒辦法等待，所以要用不會暫停的 `trySend`。
3. **`.conflate()`**：`callbackFlow` 預設有大小約 64 的 buffer，滿了之後 `trySend` 會失敗，**被丟掉的是最新送進來的那一筆**，結果畫面停在舊價格。本機實測：200 筆價格瞬間送出，collector 每筆處理 50 毫秒，沒有 `conflate` 時最後收到的是第 65 筆；加上 `.conflate()` 後最後收到的是第 200 筆（最新價）。`conflate` 會和 `callbackFlow` 的 channel 融合，變成「只保留最新」。

錯誤用 `close(throwable)`，正常結束用 `close()`。

> [!TIP]
> 面試時可以這樣說：「我用 `callbackFlow` 包 WebSocket：callback 裡用 `trySend`，最後 `awaitClose` 解除註冊，不然會洩漏。價格型的資料我會加 `conflate`，因為預設 buffer 滿了會丟掉最新的那筆，畫面就停在舊價格。」

### 7. 怎麼讓 Flow 的收集跟著畫面的生命週期停啟？

**不要在 `lifecycleScope.launch { }` 裡直接 `collect`，要用 `repeatOnLifecycle`（View 系統）或 `collectAsStateWithLifecycle`（Compose）。**

原因：`lifecycleScope.launch { flow.collect { } }` 在畫面退到背景後仍然持續收集（要等到畫面 destroy 才停），背景還在更新畫面、耗電、耗流量。

先講 lifecycle 的狀態：`STARTED` 是畫面看得到的狀態（前景）；App 退到背景後就離開 `STARTED`。`repeatOnLifecycle(STARTED)` 的意思是：進入 `STARTED` 就開始跑區塊，離開就取消，下次回來再重新開始。

```kotlin
// View／Fragment
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {   // 離開前景就取消，回來重新收集
        launch { vm.prices.collect { renderPrices(it) } }             // 同一個區塊要收多個 flow：各自 launch
        launch { vm.orders.collect { renderOrders(it) } }             // 因為 collect 會一直掛住，不 launch 的話第二個永遠跑不到
    }
}

// Compose
val state by vm.uiState.collectAsStateWithLifecycle()   // 來自 lifecycle-runtime-compose，預設 STARTED 才收集
```

Compose 裡的 `collectAsState()` 不知道 lifecycle，Activity 在背景時，只要 composition 還在就會繼續收集。

這個設計和第 5 題搭配效果最好：畫面退到背景時，collector 消失，等 5 秒後上游（WebSocket 訂閱）也跟著停掉。

> [!TIP]
> 面試時可以這樣說：「`lifecycleScope.launch { collect }` 在背景還是會一直收，所以我用 `repeatOnLifecycle(STARTED)`，Compose 用 `collectAsStateWithLifecycle`。再搭配上游的 `stateIn(WhileSubscribed(5000))`，畫面離開超過 5 秒，連 WebSocket 都會停。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin05Flow**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin05-flow/`，解答在 `labs/android/solution/kotlin/kotlin05-flow/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：兩個元件收行情，開了兩條 WebSocket（第 1 題）

**檔案**：`L01_ColdFlow.kt`

**情境**：行情的上游是「開一條 WebSocket 訂閱價格」，用 `flow { }` 寫成 cold flow（`TickerRepo.tickers()`，已經寫好），每 50 ms 送出價格 1 到 5。行情頁上有兩個元件（價格看板、迷你走勢圖）同時收集同一份價格，各自等到價格 5。

**怎麼寫**

1. **Bug 版**：ViewModel 的 `prices` 直接回傳 `repo.tickers()`（cold flow）。
2. **修正版**：`repo.tickers().stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)`，轉成 hot flow 讓兩個元件共用同一條上游。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「開啟 WebSocket」出現兩次，最後一行是「共開了 2 條 WebSocket」 |
| 修正版 | 「開啟 WebSocket」只出現一次，兩個元件都收到價格 5，最後一行是「共開了 1 條 WebSocket」 |

**為什麼**：cold flow 沒人收集就不執行，每個 collector 各自從頭跑一次。上游如果是「開一條 WebSocket」，每多一個 collector（旋轉後畫面重建、兩個元件同時收集）就多開一條連線。`stateIn` 把 cold flow 轉成 hot flow，所有 collector 共用同一份上游。

```lab-solution
android/solution/kotlin/kotlin05-flow/L01_ColdFlow.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L01_ColdFlow.kt#fixed
```

### 演練 2：5 筆成交回報，只處理了最後 1 筆（第 2 題）

**檔案**：`L02_StateFlowDrops.kt`

**情境**：交易所推送成交回報，每一筆都要寫進成交紀錄，漏掉一筆就是帳對不起來。5 筆成交（#1 到 #5）在同一瞬間到達。

**怎麼寫**

1. **Bug 版**：`val fills = MutableStateFlow<Fill?>(null)`；`launch(start = CoroutineStart.UNDISPATCHED)` 裡 `fills.collect`，不是 `null` 時 `log("寫入成交紀錄 #${fill.id}")`；然後 `repeat(5) { fills.value = Fill(it + 1) }`。
2. **修正版**：`val fills = Channel<Fill>(Channel.BUFFERED)`；收集端用 `fills.receiveAsFlow().collect { … }`；送出端 `repeat(5) { fills.send(Fill(it + 1)) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 只印出「寫入成交紀錄 #5」，#1 到 #4 沒有被處理 |
| 修正版 | 依序印出「寫入成交紀錄 #1」到「#5」，一筆都沒少 |

**為什麼**：`StateFlow` 只保留最新值，collector 還沒來得及處理，中間的值就被後面的蓋掉了，所以它不適合「每一筆都要處理」的資料。每一筆只能被一個人處理的工作用 `Channel`：有緩衝，送進去的每一筆都會等到有人拿走。

```lab-solution
android/solution/kotlin/kotlin05-flow/L02_StateFlowDrops.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L02_StateFlowDrops.kt#fixed
```

### 演練 3：旋轉螢幕後，又導航了一次（第 3 題）

**檔案**：`L03_OneShotEvent.kt`

**情境**：下單成功後要導航到訂單詳情，這個事件只能處理一次。演練模擬使用者下單後馬上旋轉螢幕：舊畫面的 collector 被取消，新畫面再開始收集。兩個畫面的收集程式都已經寫好。

**怎麼寫**

1. **Bug 版**：`onOrderPlaced(id)` 把訂單編號放進 `StateFlow<String?>`（`_navigateTo.value = id`），收集端導航後沒有清掉。
2. **修正版**：事件放進 state：`onOrderPlaced` 做 `_state.update { it.copy(pendingNavigation = id) }`，`onNavigated` 做 `_state.update { it.copy(pendingNavigation = null) }`；兩個畫面導航完都呼叫 `vm.onNavigated()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「旋轉前的畫面：導航到訂單 o-1」和「旋轉後的畫面：導航到訂單 o-1」都出現，最後一行「共導航了 2 次」 |
| 修正版 | 只出現「旋轉前的畫面：導航到訂單 o-1」，最後一行「共導航了 1 次」 |

**為什麼**：`StateFlow` 永遠有「目前值」，新的 collector 一進來就先收到它，所以旋轉後新畫面又收到一次導航事件。官方建議把一次性事件當成 state：ViewModel 保存「待處理的事件」，UI 處理完再通知 ViewModel 清掉。`SharedFlow(replay = 0)` 在背景沒有訂閱者時事件會消失，`replay = 1` 則和這個 bug 一樣會重播。

```lab-solution
android/solution/kotlin/kotlin05-flow/L03_OneShotEvent.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L03_OneShotEvent.kt#fixed
```

### 演練 4：行情一直在跳，畫面卻完全沒更新（第 4 題）

**檔案**：`L04_DebouncePrice.kt`

**情境**：WebSocket 每 100 ms 推送一次價格，共 20 筆（約 2 秒）。畫面更新一次要 250 ms，比價格來得慢，所以要丟掉一些中間的價格。`scenario` 會量「價格全部送完花了多久」和「串流結束時畫面更新了幾次」，已經寫好。

**怎麼寫**

1. **Bug 版**：價格串流用 `debounce(300)`（檔案開頭已經有 `@file:OptIn(FlowPreview::class)`）。
2. **修正版**：改用 `conflate()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「價格全部送完花了」約 2000 ms，「串流結束時，畫面已更新 0 次」，最後才跳到 20 |
| 修正版 | 「價格全部送完花了」約 2000 ms，「串流結束時，畫面已更新 8 次」左右（實測 8），最後顯示的價格是 20 |

**為什麼**：`debounce` 要「安靜」滿指定時間才發，價格每 100 ms 就來一筆，從不安靜，整個串流期間一筆都不發；它是給搜尋框用的。`conflate` 在 collector 忙的時候只留最新的一筆，collector 一閒下來就拿到最新價。`sample(250)` 也可以，它是固定週期取最新的一筆。

```lab-solution
android/solution/kotlin/kotlin05-flow/L04_DebouncePrice.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L04_DebouncePrice.kt#fixed
```

### 演練 5：本機的 order book 和交易所對不上（第 4 題）

**檔案**：`L05_OrderBookDelta.kt`

**情境**：交易所用 delta（增量更新）推送 order book：每筆 delta 是「某個價位現在有多少數量」，共 60 筆、60 個不同的價位，每 1 ms 一筆（`deltas()` 已經寫好）。本機要靠依序套用每一筆 delta，維護出和交易所一樣的 order book（`serverBook`）。

**怎麼寫**

1. **Bug 版**：`deltas().conflate().collect { delta -> delay(5); localBook[delta.price] = delta.qty }`。收集端套用一筆要 5 ms，比 delta 來得慢。
2. **修正版**：一個 coroutine 依序套用每一筆 delta：`localBook[delta.price] = delta.qty`，再 `snapshots.value = localBook.toMap()` 發佈快照；慢的 UI 收集 `snapshots`（`StateFlow` 只保留最新的快照）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「本機 order book 18 個價位，交易所 60 個價位」（電腦上的 JVM 實測 18），「兩邊對不上」 |
| 修正版 | 「本機 order book 60 個價位，交易所 60 個價位」，「兩邊一致」 |

**為什麼**：order book 的增量更新每一筆都建立在前一筆之上，丟掉任何一筆，本機的 order book 就和交易所對不上了，所以 delta 不能 `conflate` 或 `sample`。正確的做法是在背景依序套用每一筆 delta，再對外發佈「快照」（某一刻的完整內容）；快照丟掉舊的沒關係，UI 層才對快照做 `conflate`（`StateFlow` 本來就只保留最新值）。

```lab-solution
android/solution/kotlin/kotlin05-flow/L05_OrderBookDelta.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L05_OrderBookDelta.kt#fixed
```

### 演練 6：旋轉螢幕時，行情連線被重連（第 5 題）

**檔案**：`L06_WhileSubscribed.kt`

**情境**：ViewModel 用 `stateIn` 把行情（cold flow，上游是一條 WebSocket，每次啟動會印「上游啟動」）轉成 hot flow。演練模擬旋轉螢幕：畫面收集 300 ms，消失 200 ms，新畫面再收集 300 ms，然後使用者離開、等 2 秒。

**怎麼寫**

1. **Bug 版**：`repo.tickers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), 0)`。
2. **修正版**：`repo.tickers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 旋轉時「上游停止」之後又出現「上游啟動（第 2 次）」；「離開 2 秒後，上游還在跑嗎：false」；「上游共啟動 2 次」 |
| 修正版 | 只有「上游啟動（第 1 次）」；「離開 2 秒後，上游還在跑嗎：true」（還在 5 秒的等待時間內）；「上游共啟動 1 次」 |

**為什麼**：`WhileSubscribed(stopTimeoutMillis)` 是「沒有人收集之後，再等這麼久才停掉上游」。旋轉螢幕時 collector 消失到新 collector 出現，通常遠短於 5 秒，所以上游不會被打斷，WebSocket 不用重連；使用者真的離開超過 5 秒，才停掉省電省流量。`WhileSubscribed(0)` 會在旋轉的瞬間就停掉上游，`Eagerly`／`Lazily` 則永遠不停。

```lab-solution
android/solution/kotlin/kotlin05-flow/L06_WhileSubscribed.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L06_WhileSubscribed.kt#fixed
```

### 演練 7：畫面關了，行情 listener 還掛在 socket 上（第 6 題）

**檔案**：`L07_AwaitClose.kt`

**情境**：`PriceSocket`（已經寫好）是「`register` 註冊 listener、`unregister` 解除註冊」風格的 API，背景每 20 ms 推送一筆價格。用 `callbackFlow` 包成 Flow，畫面只收 3 筆價格就不再收集，之後 socket 還會繼續推送。

**怎麼寫**

1. **Bug 版**：`callbackFlow { }` 裡宣告 `val listener: (Int) -> Unit = { price -> trySend(price) }`，`socket.register(listener)`，最後 `awaitClose { }`，大括號留空，忘了解除註冊。
2. **修正版**：在 `awaitClose { }` 裡呼叫 `socket.unregister(listener)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「收集中，socket 上註冊著 1 個 listener」；「不再收集之後，socket 上還註冊著 1 個 listener」：洩漏 |
| 修正版 | 「不再收集之後，socket 上還註冊著 0 個 listener」 |

**為什麼**：`awaitClose` 讓 producer 停在那裡，直到 Flow 被取消或關閉，並在區塊內解除註冊。大括號留空，collector 取消之後 listener 還註冊在 socket 上，它（和它抓住的整個畫面）永遠不會被釋放。如果完全沒寫 `awaitClose`，`callbackFlow` 會直接丟 `IllegalStateException`，這是刻意設計來防止忘記解除 listener。

```lab-solution
android/solution/kotlin/kotlin05-flow/L07_AwaitClose.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L07_AwaitClose.kt#fixed
```

### 演練 8：一瞬間來了 200 筆價格，畫面停在舊價格（第 6 題）

**檔案**：`L08_CallbackConflate.kt`

**情境**：WebSocket 在一瞬間推送 200 筆價格（價格 1 到 200，最新的是 200）。畫面處理一筆要 5 ms，比來得慢。`BurstSocket` 與 `scenario` 已經寫好。

**怎麼寫**

1. **Bug 版**：用 `callbackFlow { }` 包 socket（`register`、`trySend`、`awaitClose { socket.unregister(listener) }`），不加 `conflate()`。
2. **修正版**：同樣的 `callbackFlow { }`，後面加 `.conflate()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「最後顯示的價格 #65」（電腦上的 JVM 實測），不是 #200 |
| 修正版 | 「最後顯示的價格 #200」 |

**為什麼**：`callbackFlow` 預設有大小約 64 的 buffer，滿了之後 `trySend` 會失敗，被丟掉的是最新送進來的那一筆，畫面就停在舊價格。價格型的資料只在意最新值，加上 `.conflate()` 之後，buffer 滿的時候丟掉的是舊值，永遠保留最新的一筆。

```lab-solution
android/solution/kotlin/kotlin05-flow/L08_CallbackConflate.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L08_CallbackConflate.kt#fixed
```

### 演練 9：App 退到背景，行情還一直在收（第 7 題）

**檔案**：`L09_RepeatOnLifecycle.kt`

**情境**：行情畫面收集價格（每 50 ms 一筆，`prices()` 已經寫好）。用 `FakeScreen`（已經寫好）模擬畫面的 lifecycle：出現（STARTED）0.3 秒 → 退到背景（STOPPED）0.5 秒 → 回到前景 0.3 秒 → 離開（DESTROYED）。`repeatOnLifecycle` 需要 Android 的主執行緒，所以這題要在模擬器或手機上按執行（電腦上的單元測試會略過）。

**怎麼寫**

1. **Bug 版**：`screen.lifecycleScope.launch { prices().collect { onPrice() } }`，直接收集。
2. **修正版**：在 `lifecycleScope.launch { }` 裡用 `screen.repeatOnLifecycle(Lifecycle.State.STARTED) { prices().collect { onPrice() } }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「在背景時仍然收到」約 10 筆價格（背景 0.5 秒、每 50 ms 一筆） |
| 修正版 | 「在背景時仍然收到 0 筆價格」；回到前景後又重新開始收 |

**為什麼**：`lifecycleScope.launch { collect }` 要等畫面 DESTROYED 才停，退到背景後還在收集，白白耗電耗流量。`repeatOnLifecycle(STARTED)` 在進入 STARTED 時開始執行區塊，離開時取消，下次回來再重新開始。Compose 裡對應的是 `collectAsStateWithLifecycle()`（`collectAsState()` 不認得 lifecycle）。搭配第 5 題的 `WhileSubscribed(5000)`，畫面離開超過 5 秒，連上游的 WebSocket 都會停。

```lab-solution
android/solution/kotlin/kotlin05-flow/L09_RepeatOnLifecycle.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin05-flow/L09_RepeatOnLifecycle.kt#fixed
```

## 常見陷阱

- **以為 `MutableSharedFlow()` 像 `StateFlow` 有初始值。** 預設 replay 0，先發後訂閱的值直接消失。
- **`StateFlow` 放可變物件並原地修改。** `equals` 判斷相同，不會發射。要換成新的不可變物件。
- **用 `debounce` 處理價格串流。** 密集更新的串流永遠不安靜，畫面幾乎不更新。價格用 `conflate` 或 `sample`，`debounce` 留給搜尋框。
- **對 order book 的增量更新用 `conflate`。** 丟掉一筆 delta，本機的 order book 就錯了。
- **`callbackFlow` 的 callback 裡呼叫 `send`，或忘了 `awaitClose`。** callback 要用 `trySend`；沒有 `awaitClose` 會丟例外，也會洩漏 listener。
- **價格型的 `callbackFlow` 沒加 `conflate`。** buffer 滿了會丟掉最新的一筆，畫面停在舊價格。
- **ViewModel 把 cold flow 直接暴露給 UI。** 每個 collector 都重新啟動上游（多開 WebSocket），要用 `stateIn` 或 `shareIn`。
- **`WhileSubscribed(0)` 或 `Lazily`／`Eagerly`。** 前者旋轉時上游重啟，後者上游永遠不停。
- **用 `lifecycleScope.launch { collect }` 收集。** 背景持續收集，要用 `repeatOnLifecycle`。

## 面試官想聽到的

1. 能區分 `StateFlow`（最新值、去重）、`SharedFlow`（事件、可 replay）、`Channel`（點對點），並選對。
2. 對「行情」說得出 `conflate`／`sample` 為何適合、`debounce` 為何不適合，並且知道增量資料不能丟。
3. 會用 `stateIn(WhileSubscribed(5000))` 讓上游隨畫面生命週期停啟，並說明 5 秒的理由。
4. `callbackFlow` 一定有 `awaitClose`，知道 `trySend` 與預設 buffer 的陷阱。
5. 知道要用 `repeatOnLifecycle` 或 `collectAsStateWithLifecycle`，避免背景持續收集。

> [!WARNING]
> 面試官常追問：「StateFlow 和 SharedFlow 差在哪？什麼時候用 SharedFlow？」要答出三點：StateFlow 有初始值、會去重、只保留最新；SharedFlow 沒有初始值、可調 replay 與 buffer；一次性事件不要直接用 SharedFlow 傳，因為背景時會掉、replay 又會重複處理，官方建議放進 state。

> [!FIELD]
> 在一個社交 App 的 Android 版裡，大量使用 `repeatOnLifecycle` 與 `SharedFlow`，Paging 3 的資料也是用 `Flow<PagingData>` 提供給畫面。在一個即時行情 App 的 Android 版裡，ViewModel 用 `MutableStateFlow` 發佈畫面狀態，Compose 端收集；即時 K 線用 SSE（Server-Sent Events，伺服器單向推送）接收，連線跑在 `viewModelScope`，ViewModel 清除時連線就結束，畫面進入背景與回到前景時會通知 ViewModel。
> 如果換成交易所的行情，情況會不同：即時 K 線只有單一交易對，事件量小；但行情列表有很多檔幣種、更新頻率高很多，這時會用 `callbackFlow` 加 `conflate` 包 WebSocket、`stateIn(WhileSubscribed(5000))` 發佈、Compose 端用 `collectAsStateWithLifecycle`；order book 的 delta 則在背景維護本機的 order book，再發快照。

## iOS / Android 對照

| 主題 | Kotlin Flow | Swift／iOS |
|---|---|---|
| cold 串流 | `flow { }`：每個 collector 各跑一次 | 自訂的 `AsyncSequence`（每次 `for await` 從頭開始）、Combine 的 `Publisher`（多數是訂閱時才開始） |
| 有目前值的狀態 | `StateFlow`：自動去重 | `CurrentValueSubject`、`@Published`；要去重得自己加 `removeDuplicates()` |
| 事件 | `SharedFlow`、`Channel` | `PassthroughSubject`；`AsyncStream`（只支援單一消費者，比較像有緩衝的 hot 串流） |
| 背壓與丟舊值 | `conflate`、`buffer`、`sample` | `AsyncStream` 的 `bufferingPolicy`（`.bufferingNewest(1)` 約等於 conflate）、Combine 的 `throttle(for:scheduler:latest:)`（約等於 sample）、`debounce`（同名） |
| 橋接 callback | `callbackFlow` 搭配 `awaitClose` | `AsyncStream` 搭配 `continuation.onTermination` |
| 跟著畫面生命週期 | `repeatOnLifecycle`、`collectAsStateWithLifecycle` | SwiftUI 的 `.task { }`（view 消失時自動取消）、Combine 的 `AnyCancellable`（釋放時取消訂閱） |

兩邊的概念是對得上的：Flow 對應 `AsyncSequence` 和 Combine；`StateFlow` 對應 `CurrentValueSubject`；`callbackFlow` 對應 `AsyncStream` 搭配 continuation。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| cold flow（沒人收集就不執行） | cold flow | コールドフロー |
| hot flow（不管有沒有人收都在發） | hot flow | ホットフロー |
| 背壓 | backpressure | バックプレッシャー |
| order book（買賣掛單的完整清單） | order book | 板 |
| 增量更新 | delta | 差分更新 |
| 快照 | snapshot | スナップショット |
| 生命週期 | lifecycle | ライフサイクル |
| collector（收集資料的那一方） | collector | コレクター |
