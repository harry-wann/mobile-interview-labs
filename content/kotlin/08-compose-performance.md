---
id: kotlin-compose-performance
title: Compose 效能：行情列表怎麼不卡（延後讀取、LazyColumn 的 key、ImmutableList、Baseline Profile）
summary: 交易所的行情列表有上百個幣種、價格每秒更新很多次，是 Compose 效能面試最愛的情境。這個單元說明怎麼先量測、怎麼用 lambda 延後讀取 State、LazyColumn 的 key 與 contentType、ImmutableList 的用途、Baseline Profile 是什麼，最後把它們組成一套行情列表的設計。
priority: P0
day: 2
tags: [compose, performance, lazycolumn, baseline-profile, recomposition, market-data]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 使用者說「行情列表捲動會卡」，你怎麼處理？（先量測）
2. 延後讀取（deferred read）是什麼？為什麼價格閃色動畫應該這樣寫？
3. `LazyColumn` 為什麼一定要給 `key`？`contentType` 是什麼？
4. `ImmutableList` 是什麼？為什麼有人把 `List` 換成它？
5. 上百個幣種、每秒更新數十次，整個行情列表怎麼設計才不卡？
6. Baseline Profile 是什麼？解決什麼問題？

### 1. 使用者說「行情列表捲動會卡」，你怎麼處理？

**先量測，再修改。而且一定要在 release build（正式上架的版本）量，不是 debug build。沒有量測就動手最佳化，在面試裡是反面教材。**

先把幾個詞講清楚：

- **debug build 和 release build：** debug 是開發時用的版本，沒有經過 R8 最佳化，Compose 在上面會慢很多，量出來的數字沒有參考價值。release 是正式上架的版本。R8 是 Android 建置時的工具，會把用不到的程式碼刪掉、縮小、混淆並最佳化。
- **掉幀（jank）：** 畫面沒有在時間內畫完一個 frame，使用者看到卡頓或跳格。螢幕每秒更新 60 次的話，一個 frame 只有約 16 毫秒可以用。

不同的問題用不同的工具：

| 想知道什麼 | 工具 |
|---|---|
| 哪個 Composable 在多餘地重組、重組了幾次 | Android Studio 的 **Layout Inspector**（顯示每個 Composable 的重組與跳過次數） |
| 哪個型別是 unstable、哪個 Composable 不能跳過 | **Compose compiler reports**（上一單元介紹過） |
| 啟動、捲動的速度，能不能每次都用同樣方式重現 | **Macrobenchmark**：在真實裝置上自動操作 App（啟動、捲動），並量測每個 frame 花的時間（`FrameTimingMetric`） |
| 使用者實機上有沒有掉幀 | **JankStats**：Jetpack 提供的函式庫，在 App 執行時收集掉幀資料 |
| 某一個特別長的 frame 到底卡在哪 | **Perfetto**（System Trace）：系統層的追蹤工具 |

處理流程是：**在 release build 重現 → 用 Macrobenchmark 量出基準數字 → 用 Layout Inspector 與 compiler reports 找出原因 → 修改 → 重跑基準確認真的變快 → 把基準放進 CI（持續整合：每次推程式碼就自動建置和測試），避免之後又悄悄變慢。**

> [!TIP]
> 面試時可以這樣說：「我先在 release build 重現問題，用 Macrobenchmark 建立基準，再用 Layout Inspector 和 compiler reports 找原因。修完重跑基準確認改善，並把基準放進 CI。我不會一開始就到處加 @Immutable 或 remember。」

### 2. 延後讀取（deferred read）是什麼？

**延後讀取是指：把「讀取 State」的時間點，往後拖到 Layout 或 Drawing 階段。State 在哪個階段被讀，變更時就只重做那個階段；讀得越晚，需要重做的越少。用在每個 frame 都會變的動畫，可以做到完全不重組。**

上一單元說過，每個 frame 有三個階段：Composition（決定要顯示什麼）、Layout（決定放哪裡、多大）、Drawing（實際繪製）。情境是：行情列表中，價格一變，那一列要閃一下綠色。

先看會出問題的寫法：

```kotlin
@Composable
fun TickRow(tick: TickUi) {
    val flash = remember { Animatable(0f) }          // Animatable：Compose 的動畫值容器，value 是 State
    LaunchedEffect(tick.priceText) {                 // 價格文字一變，就播一次閃色動畫
        flash.snapTo(1f)
        flash.animateTo(0f, tween(400))              // 400 毫秒內從 1 漸變到 0
    }

    Row(Modifier.background(Color.Green.copy(alpha = flash.value * 0.3f))) {  // 在這裡讀取 flash.value
        Text(tick.symbol)
        Text(tick.priceText)
    }
}
```

`Modifier.background(color)` 這個寫法，在建立 Modifier 的時候就讀了 `flash.value`，也就是讀取發生在 Composition 階段。動畫播放的 400 毫秒內，每個 frame 的 `flash.value` 都不一樣，所以 `TickRow` **每個 frame 都重組一次**。上百列同時閃，就會掉幀。

改成 lambda 版本：

```kotlin
Row(
    Modifier.drawBehind {                                    // lambda：裡面的讀取發生在 Drawing 階段
        drawRect(Color.Green.copy(alpha = flash.value * 0.3f))
    }
) {
    Text(tick.symbol)
    Text(tick.priceText)
}
```

`drawBehind { }` 的大括號是在 Drawing 階段才執行的，`flash.value` 在那時才讀。動畫期間，值變了只需要重新繪製，**`TickRow` 完全不重組**。

常見的 lambda 版對照：

| 讀取發生在 Composition（每次值變都重組） | 改成延後到 Layout／Drawing（不重組） |
|---|---|
| `Modifier.offset(x = offsetX.value.dp)` | `Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }` |
| `Modifier.alpha(fade.value)` | `Modifier.graphicsLayer { alpha = fade.value }` |
| `Modifier.background(color.value)` | `Modifier.drawBehind { drawRect(color.value) }` |
| 把 `Float` 傳給子 Composable | 傳 `() -> Float`，由子在 Layout 或 Drawing 的 lambda 裡呼叫 |

最後一列的意思是：與其傳 `fade.value`（一個數字，讀取發生在父層），不如傳 `{ fade.value }`（一個函式），讓子層在需要的時候才去讀。

> [!TIP]
> 面試時可以這樣說：「State 在哪個階段被讀，變更時就只重做那個階段。把讀取放進 offset、graphicsLayer、drawBehind 這些 lambda，就移到 Layout 或 Drawing 階段，值變了不用重新執行 Composable。價格閃色這種每個 frame 都在變的動畫，我會用這個方式。」

### 3. `LazyColumn` 為什麼一定要給 `key`？`contentType` 是什麼？

**`LazyColumn` 是 Compose 的捲動列表，只組合畫面上看得到的項目，類似 Android 的 RecyclerView 或 iOS 的 UITableView。`key` 是每個項目的身分（例如幣種代號 `symbol`），`contentType` 是項目的種類。兩者都是讓 Compose 少做事。**

先看沒有給 `key` 會怎樣。使用者在自選清單最前面新增了一個交易對 ETH：

```kotlin
LazyColumn {
    items(watchlist) { tick -> TickRow(tick) }     // 沒有 key：Compose 用「位置」識別項目
}
```

沒有 `key` 時，Compose 用位置（第幾列）當身分。插入之後，位置 0 的資料變成 ETH，位置 1 變成原本的 BTC，每個位置對應的資料都不一樣了。結果是所有項目的參數都變了，**全部重組**；`remember` 保存的狀態（例如這一列有沒有展開）則黏在位置上，出現在錯的交易對。也沒辦法做「項目移動」的動畫。

```kotlin
LazyColumn {
    items(
        items = watchlist,
        key = { it.symbol },            // 以幣種代號識別
        contentType = { "tick" },       // 這一種項目的種類
    ) { tick -> TickRow(tick) }
}
```

給了 `key` 之後，Compose 知道 BTC 那一列只是往下移動了，它的參數沒變，可以跳過重組，狀態也跟著 BTC 走。`key` 必須**唯一而且穩定**。

**`contentType`：** 告訴 Compose 哪些項目的結構相同，可以互相重用已經建好的結構（類似 RecyclerView 的 view type）。列表裡只有一種列時看不出差別；列表混有標題、廣告、一般行情列時，效果明顯。

還有幾個列表常見的效能問題：

- 不要在可以垂直捲動的容器裡再放一個 `LazyColumn`（高度沒有上限，會出錯或失去只組合看得到項目的好處）。
- 不要用 `Column(Modifier.verticalScroll(...))` 放上百個項目，它會把全部項目一次組合出來。
- 項目的 Composable 要輕。格式化價格、排序、解析這些計算，不要放在 Composable 裡，應該在資料層先做成專門給畫面用的資料（UI model，例如價格已經格式化好的字串），或至少用 `remember` 快取。

> [!TIP]
> 面試時可以這樣說：「LazyColumn 我一定給 key，用幣種代號當身分。這樣資料在前面插入或重排時，Compose 知道誰只是移動，不會整批重組，remember 的狀態也跟著對的項目走。contentType 則是讓同種類的項目重用結構，列表混有標題或廣告時效果明顯。」

### 4. `ImmutableList` 是什麼？為什麼有人把 `List` 換成它？

**`ImmutableList` 是 `kotlinx.collections.immutable` 函式庫提供的不可變集合。Compose compiler 認得它是 stable，所以傳進 Composable 時會用 `equals` 比較內容；一般的 `List` 在 Compose 眼裡是 unstable，沒有 strong skipping 的時候，含有 `List` 參數的 Composable 完全不能被跳過。**

上一單元說過，`List` 是介面，執行時可能是會被改動的可變集合，所以 Compose 不敢相信它沒變。Kotlin 2.0.20 起預設開啟 strong skipping，unstable 參數改用 `===`（是不是同一個物件）比較，含 `List` 的 Composable 也能被跳過了。但 ViewModel 或 Composable 裡每次 `map`、`filter` 都會產生新的 list，物件不同，比較就失敗，照樣重組。

換成 `ImmutableList` 之後，比較改用 `equals`，內容相同就算沒變：

```kotlin
data class MarketUiState(
    val ticks: ImmutableList<TickUi> = persistentListOf(),   // persistentListOf：建立空的 ImmutableList
)

// ViewModel 或資料層：在背景把一般的 List 轉成 ImmutableList
val uiState: Flow<MarketUiState> = repo.ticks.map { list ->
    MarketUiState(ticks = list.map { it.toUi() }.toImmutableList())
}
```

要注意的地方：

- `toImmutableList()` 會複製整個 list。放在背景執行緒做，或放在 `remember(source) { source.toImmutableList() }` 裡，不要每次重組都轉。
- 它不是必要的。先用 Layout Inspector 與 compiler reports 確認有多餘的重組，再決定要不要換。
- 另一個做法是把 list 包進自己的類別，加上 `@Immutable`。但這是對編譯器的承諾，不會被驗證，用的時候要確定內容真的不會變。

> [!TIP]
> 面試時可以這樣說：「一般的 List 在 Compose 眼裡是 unstable。strong skipping 之後會用 === 比較，但每次 map 出新的 list 還是會重組。ImmutableList 被 compiler 認為是 stable，用 equals 比較內容，所以我會在量測到有問題時，才在資料層轉成 ImmutableList。」

### 5. 上百個幣種、每秒更新數十次，整個行情列表怎麼設計才不卡？

**核心原則：頻率在資料層控制（降低更新頻率），畫面只更新真的有變動的項目，格式化在背景做，動畫走 Drawing 階段。**

降低更新頻率（英文叫 throttle）是指資料太頻繁時，只挑一部分往下送，例如每 200 毫秒只送最新的一批，而不是每收到一筆訊息就更新畫面。

整套設計，依序是：

**(1) 資料層降低更新頻率。** WebSocket 每收到一筆訊息就更新畫面，一定卡。做法是在背景依幣種代號合併，每個幣種只保留最新一筆，然後固定週期（例如 100 到 250 毫秒）輸出一批：

```kotlin
class MarketRepository(private val socket: TickerSocket, scope: CoroutineScope) {
    private val latest = MutableStateFlow<Map<String, TickUi>>(emptyMap())

    init {
        scope.launch(Dispatchers.Default) {
            socket.ticks.collect { tick ->                       // 每筆 WebSocket 訊息
                latest.update { it + (tick.symbol to tick.toUi()) }   // 只保留每個幣種最新的一筆，不動畫面
            }
        }
    }

    // 固定週期輸出：畫面最多每 200 毫秒收到一次
    val board: Flow<List<TickUi>> = flow {
        while (true) {
            emit(latest.value.values.toList())
            delay(200)
        }
    }.distinctUntilChanged()                                     // 內容完全沒變就不往下送
}
```

**(2) 只更新變動的項目。** 每次輸出的新 list 裡，沒有變的幣種沿用**同一個物件**（上面的 `latest` 裡，沒收到新訊息的幣種就是原本那個物件）。這樣 `TickRow` 的參數沒變，會被跳過；只有價格真的變了的那幾列會重組。

**(3) 預先格式化。** 價格、漲跌幅的字串在背景先做好（放進 UI model），格式化工具依小數位數快取。Composable 裡不要呼叫 `String.format` 或 `NumberFormat`。

**(4) 閃色動畫走 Drawing 階段。** 用上一節的 `drawBehind`、`graphicsLayer`，不觸發重組。

**(5) `LazyColumn` 給 `key` 與 `contentType`**，需要時用 `ImmutableList`。

**(6) 離開畫面就停止更新。** 在畫面用 `collectAsStateWithLifecycle`（Jetpack 提供的函式，畫面不在前景時自動停止收集），在 ViewModel 用 `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), …)`（最後一個訂閱者離開 5 秒後才停止上游，旋轉螢幕時不用重連）。

**(7) 量測確認。** 用 Layout Inspector 看重組次數是不是只落在有變動的列，用 Macrobenchmark 看 frame 時間。

**常見誤解：用 `debounce` 處理行情。** `debounce` 的意思是「等到安靜一段時間後才送出」。行情是持續不斷有更新，永遠等不到安靜，列表會長時間凍結。降低更新頻率要用固定週期輸出，或 `sample`。

> [!NOTE]
> 如果是訂單簿（order book）的增量資料（只送有變動的價位），降低更新頻率時不能直接丟掉中間的訊息，要先依序套用到本地的訂單簿，再發佈整理好的快照給畫面。

> [!TIP]
> 面試時可以這樣說：「我把頻率控制放在資料層：背景依幣種合併、只保留最新，固定每兩百毫秒左右輸出一批。畫面只有變動的列會重組，價格字串預先格式化，閃色動畫在 Drawing 階段讀取 State。LazyColumn 給 key 和 contentType。最後用 Layout Inspector 和 Macrobenchmark 驗證。」

### 6. Baseline Profile 是什麼？解決什麼問題？

**Baseline Profile 是一份「App 啟動和常用流程一定會用到哪些程式碼」的清單。系統在安裝時，就先把清單上的程式碼編譯好，所以 App 第一次執行不用再邊跑邊編譯，冷啟動與第一次捲動比較順。**

先弄清楚兩個詞：

- **ART**：Android Runtime，Android 上執行 App 的環境。
- **JIT 與 AOT**：JIT（just-in-time）是執行時邊跑邊編譯，剛開始比較慢；AOT（ahead-of-time）是事先編譯好，執行時直接用。

沒有 Baseline Profile 時，使用者剛裝好 App、第一次打開，很多程式碼還沒編譯過，要邊跑邊處理，所以啟動慢，第一次捲動行情列表也容易掉幀。有了 Baseline Profile，系統在安裝時就把清單上的 class 與 method 先編譯好。

產生方式：用 Macrobenchmark 提供的 `BaselineProfileRule`，搭配 `androidx.baselineprofile` Gradle plugin，在真實裝置或模擬器上自動執行關鍵的使用者流程，記錄用到了哪些程式碼，產生清單，並隨 release build 一起打包：

```kotlin
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.example.exchange") {
        pressHome()
        startActivityAndWait()          // 冷啟動 App
        // 接著再加上關鍵流程：進入行情列表、捲動
    }
}
```

三個要記住的重點：

- Compose 函式庫自己已經附帶 profile，但你的 App 自己的啟動路徑與畫面（首頁、行情列表）要自己產生。
- 它和 R8 是互補的，不是替代：R8 縮小並最佳化程式碼，Baseline Profile 負責讓關鍵程式碼事先編譯好。
- 它在 release build 才有意義。怎麼確認有效？用 Macrobenchmark，分別在「沒有 profile」和「有 profile」兩種編譯模式下量啟動時間，比較差異。

> [!TIP]
> 面試時可以這樣說：「Baseline Profile 讓系統在安裝時就先編譯關鍵程式碼，避免第一次執行時的 JIT 暖機，改善冷啟動和第一次捲動。我會用 Macrobenchmark 產生，並比較有沒有 profile 的啟動時間，確認真的有效。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin08ComposePerformance**。演練 1 到 3 是畫面操作，沒有自動檢查，要看畫面上的橘色數字（重組次數）；演練 4、5 不用看畫面，按「執行」就有自動檢查。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin08-compose-performance/`，解答在 `labs/android/solution/kotlin/kotlin08-compose-performance/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」，照步驟操作（演練 4、5 按「執行」，出現 ✓ 代表 bug 真的重現了）→ 寫修正版 → 選「修正版」重新操作 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **橘色數字**：畫面上的「重組 XXX：N 次」由 LabKit 的 `RecomposeCounter` 顯示，數的是「放它的那個 Composable，函式內容被重新執行了幾次」。想用 Android Studio 對照，可以開 Layout Inspector，打開顯示重組次數（Show Recomposition Counts）的選項。debug build 上的數字只看「次數有沒有增加」，不看絕對時間。

### 演練 1：價格閃一下，那一列每個畫面格都在重組（第 2 題）

**檔案**：`L01_DeferredRead.kt`

**情境**：行情列表的一列（`TickRow`）在價格變動時，背景閃一下綠色，400 ms 內從綠色漸變到透明（`Animatable` 的值從 1 到 0）。按「價格跳一下」模擬價格變動；進入畫面時也會先閃一次。橘色數字顯示 `TickRow` 重組了幾次。

**怎麼寫**

1. **Bug 版**：在 `Row` 的 Modifier 加 `.background(Color.Green.copy(alpha = flash.value * 0.3f))`，建立 Modifier 的當下就讀 `flash.value`。
2. **修正版**：改成 `.drawBehind { drawRect(Color.Green.copy(alpha = flash.value * 0.3f)) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 進入畫面時先閃一次，`TickRow` 的次數一路增加到約 25 次；之後每按一次「價格跳一下」再增加約 25 次 |
| 修正版 | `TickRow` 維持 1 次，閃色動畫照常播放；每按一次「價格跳一下」只增加 1 次（價格文字變了，那一次是必要的） |

**為什麼**：State 在哪個階段被讀，變更時就只重做那個階段。`Modifier.background(color)` 在建立 Modifier 時就讀了 `flash.value`，這是 Composition 階段，動畫的每個畫面格值都不同，`TickRow` 每格都重組。`drawBehind { }` 的大括號在 Drawing 階段才執行，`flash.value` 在那時才讀，值變了只需要重新繪製，`TickRow` 完全不重組。`offset` 也一樣：`Modifier.offset { IntOffset(…) }` 把讀取延後到 Layout 階段。

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L01_DeferredRead.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L01_DeferredRead.kt#fixed
```

### 演練 2：標籤淡入淡出，連父層的卡片也每格重組（第 2 題）

**檔案**：`L02_AlphaLambda.kt`

**情境**：交易卡片（`TradeCard`）有一個警示標籤（`AlertBadge`）。按「切換警示」時，標籤用 600 ms 淡入或淡出。橘色數字顯示 `TradeCard` 與 `AlertBadge` 各重組了幾次。

**怎麼寫**

1. **Bug 版**：`TradeCard` 用 `val alpha by animateFloatAsState(…)` 取得 alpha，把 `Float` 傳給 `AlertBadge(alpha: Float)`，裡面用 `Modifier.alpha(alpha)`。
2. **修正版**：`TradeCard` 用 `val alpha = animateFloatAsState(…)`（不加 `by`），傳 `{ alpha.value }` 給 `AlertBadge(alpha: () -> Float)`；`AlertBadge` 在 `Modifier.graphicsLayer { this.alpha = alpha() }` 的大括號裡呼叫它。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 按一次「切換警示」，`TradeCard` 與 `AlertBadge` 的次數各增加約 35 次（600 ms 內每個畫面格重組一次） |
| 修正版 | `TradeCard` 只增加 1 次（`alert` 參數變了，那一次是必要的），`AlertBadge` 維持 1 次，標籤照常淡入淡出 |

**為什麼**：`val alpha by …` 在 `TradeCard` 的 Composition 階段就讀了動畫的值，每個畫面格，父層重組，子層的參數也跟著變，子層也重組。改傳函式 `{ alpha.value }`，父層沒有讀值；子層在 `graphicsLayer { }` 的大括號裡才呼叫，讀取發生在 Drawing 階段。與其傳 `fade.value`（讀取發生在父層），不如傳 `{ fade.value }`，讓需要的地方在需要的時候才去讀。

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L02_AlphaLambda.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L02_AlphaLambda.kt#fixed
```

### 演練 3：插入一檔幣種，原本每一列都重組（第 3 題）

**檔案**：`L03_ListKey.kt`

**情境**：自選清單用 `LazyColumn` 顯示 6 檔幣種，每一列右邊的橘色數字是這一列重組了幾次。按「在最上面插入 SOL」新增一檔。（Kotlin06 的演練 7 看的是「展開狀態黏錯列」，這題看的是「重組次數」。）

**怎麼寫**

1. **Bug 版**：`items(coins) { coin -> L03CoinRow(coin) }`，不給 `key`。
2. **修正版**：`items(coins, key = { it.symbol }) { coin -> L03CoinRow(coin) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 插入 SOL 之後，原本那 6 個位置的橘色數字都變成 2 次（每個位置的資料都換了）；最下面新增的第 7 列是新組合的，是 1 次 |
| 修正版 | SOL 是 1 次，原本的 BTC、ETH… 到 LTC 6 列都仍然是 1 次（被跳過，只是往下移動） |

**為什麼**：沒給 `key` 時，Compose 用位置當身分。插入之後，每個位置對應的資料都往下移了一格，所有位置的參數都變了，現有的每一列都重組。給了 `key`，Compose 知道 BTC 只是往下移動，參數沒變，可以跳過；`remember` 的狀態也跟著 BTC 走。`key` 必須唯一而且穩定，不要用位置（index）。`contentType` 則是讓同種類的項目重用結構，列表混有標題或廣告時才看得出差別，這個演練沒有做。

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L03_ListKey.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L03_ListKey.kt#fixed
```

### 演練 4：行情每來一筆，畫面就更新一次（第 5 題）

**檔案**：`L04_ThrottleBoard.kt`

**情境**：WebSocket 每 5 ms 推一筆行情，共 120 筆，輪流更新 20 個幣種（約 0.7 秒）。畫面每收到一份清單就更新一次，演練計算「畫面總共更新了幾次」，以及最後一份清單是不是每個幣種都是最新價。

**怎麼寫**

1. **Bug 版**：`MarketRepository.board` 用 `flow { }`，`socket.collect` 每收到一筆就更新 `latest`，並 `emit(latest.values.toList())`。
2. **修正版**：在 `scope` 裡收集 `socket`，用 `latest.update { it + (symbol to ui) }` 合併成「每個幣種最新的一筆」；`board` 改成每 200 ms 輸出一份：`flow { while (true) { emit(latest.value.values.toList()); delay(200) } }.distinctUntilChanged()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「畫面共更新了 120 次」：每一筆訊息都更新一次畫面 |
| 修正版 | 「畫面共更新了 5 次」左右（每 200 ms 一次），而且「最後一份清單有 20 檔，每一檔都是最新價：是」 |

**為什麼**：頻率要在資料層控制。背景依幣種合併，每個幣種只保留最新的一筆，再用固定週期輸出一批，畫面最多每 200 ms 更新一次，中間的價格不會漏掉最新值。不用 `debounce`：行情不會安靜，要等安靜一段時間才送出的 `debounce` 會讓列表長時間凍結（Kotlin05Flow 的演練 4 做過）。

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L04_ThrottleBoard.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L04_ThrottleBoard.kt#fixed
```

### 演練 5：只有 3 檔變價，20 列全部重組（第 5 題）

**檔案**：`L05_ReuseRowModel.kt`

**情境**：行情板有 20 列（`L05TickRow`）。資料層輸出 5 份新的清單，每次只有 3 檔的價格真的變了。畫面用的 `L05TickUi` 含一個 `List`（走勢），所以它是 unstable，strong skipping 用 `===` 比較。這個演練用 `withHeadlessCompose` 在沒有畫面的情況下真的跑 Compose 的重組，數每一列被執行了幾次。

**怎麼寫**

1. **Bug 版**：`build(raws)` 每次都 `raws.map { it.toUi() }`，20 檔都是新的物件。
2. **修正版**：用 `HashMap<String, Pair<L05Raw, L05TickUi>>` 記下每個幣種上一次的原始資料與 `TickUi`。原始資料沒變就回傳上一次的同一個物件，有變才 `raw.toUi()` 產生新的。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「列的重組共 120 次」：第一次組合 20 列，加上 5 次更新每次 20 列全部重組 |
| 修正版 | 「列的重組共 35 次」：第一次組合 20 列，加上 5 次更新每次只有 3 列；「畫面上每一列都是最新價：是」 |

**為什麼**：`TickUi` 含 `List`，是 unstable，strong skipping 用 `===` 比較參數。每次都重新轉換，哪怕內容一樣，也是新的物件，`===` 比較不相等，20 列全部重組。沒變的幣種沿用同一個物件，`===` 相等，列就被跳過，只有真的變了的列重組。（如果 `TickUi` 的欄位全是 stable 的型別，Compose 用 `equals` 比較，內容一樣就會被跳過，這時重新轉換也不會多重組。）

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L05_ReuseRowModel.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin08-compose-performance/L05_ReuseRowModel.kt#fixed
```

### 沒有做成演練的題目，以及怎麼自己量

- **第 1 題（先量測）和第 6 題（Baseline Profile）**：它們的差別要在 release build 上、用 Macrobenchmark（在真實裝置上自動操作 App 並量測的函式庫）才看得出來，debug build 上的數字沒有參考價值，所以不做成假的演練。想自己練，可以在 Android Studio 的新增 Module 精靈加一個 Macrobenchmark 模組，寫一個冷啟動（`StartupTimingMetric`）與一個捲動行情列表（`FrameTimingMetric`）的測試，在真實裝置上用 release 版本（不開 debuggable）的 build 跑，記下基準數字；再加一個 Baseline Profile 模組（精靈裡有範本），產生 profile 之後，用 `CompilationMode.None()` 與 `CompilationMode.Partial()` 各量一次啟動時間，比較差異。
- **第 1 題的其他工具**：Layout Inspector 看每個 Composable 的重組與跳過次數（就是上面的 Show Recomposition Counts）；Compose compiler reports 看哪個型別是 unstable（做法見 Kotlin06Recomposition 的演練 3）；單獨一個特別長的 frame 卡在哪裡，用 Perfetto（System Trace）錄一段系統層的追蹤來看。
- **第 4 題（`ImmutableList`）**：它要解決的問題（含 `List` 的參數、每次產生新的 List 物件）和 Kotlin06Recomposition 的演練 3、4 相同，請回去做那兩個，以及這個單元的演練 5。

## 常見陷阱

- **在 debug build 做效能判斷。** debug build 的 Compose 慢很多，數字沒有參考價值。一定要在 release build 量。
- **沒有量測，一開始就到處加 `@Immutable`、`remember`。** 先用 Layout Inspector 和 compiler reports 確認問題在哪裡。
- **把 `animateFloatAsState` 的值直接傳給 `Modifier.alpha(...)`。** 每個動畫 frame 都重組。改用 `graphicsLayer { alpha = ... }`。
- **`LazyColumn` 沒給 `key`。** 資料插入時整批項目重組，狀態黏錯。
- **用 `debounce` 處理行情列表。** 行情不會安靜，列表會長時間凍結。用固定週期輸出。
- **在 Composable 裡做 `sortedBy`、`filter`、`NumberFormat.getInstance()`。** 每次重組都重算。放到資料層，或用 `remember` 快取。
- **`AndroidView` 裡的圖表每次重組都重設。** 要先比較資料有沒有變，沒變就不更新。

## 面試官想聽到的

1. 量測優先：知道要用 release build，並能說出 Layout Inspector、compiler reports、Macrobenchmark 各自回答什麼問題。
2. 能解釋延後讀取的原理（三個階段、讀取點決定失效範圍），並舉出 `offset { }`、`graphicsLayer { }`、`drawBehind { }` 的例子。
3. 說得出 `key` 與 `contentType` 的差別與好處。
4. 能講出每秒更新很多次的行情列表的完整策略：資料層降低更新頻率、畫面只更新變動項目、格式化在背景、動畫走 Drawing 階段。
5. 知道 Baseline Profile 是什麼、怎麼產生，以及它和 R8 互補。

> [!FIELD]
> 實務上的幾個做法：
> - 在一個即時行情 App 裡，Watchlist 把資料用 `toImmutableList()` 轉換，並包在 `remember(displayData)` 裡，避免每次重組都轉換；
> - 同一個 App 的 `AndroidView` 圖表用 `lastDataId` 記錄資料有沒有變，沒變就不重設圖表；自訂的圖表 renderer 重用陣列緩衝區，並略過視窗外的長條，圖表也關掉不需要的數值文字（`setDrawValues(false)`）；
> - 在一個社交 App 裡，列表使用 Paging 3 分頁載入。
> 最佳化行情列表時，我會先建立 Macrobenchmark 基準與 Layout Inspector 的檢查流程，再依量測結果調整。

## iOS / Android 對照

| 主題 | Compose | SwiftUI／UIKit |
|---|---|---|
| 延後讀取 | `Modifier.offset { }`、`graphicsLayer { }`、`drawBehind { }`：讀取移到 Layout 或 Drawing 階段 | 把每秒變很多次的部分拆成小 View，縮小 `body` 重新求值的範圍；逐 frame 繪製用 `Canvas`、`TimelineView` |
| 列表項目的身分與重用 | `key`、`contentType` | `ForEach(id:)`；UIKit 用 cell 重用識別碼（reuse identifier） |
| 事先編譯關鍵程式碼 | Baseline Profile（讓 ART 在安裝時先編譯） | iOS App 安裝前就已編譯成機器碼，沒有「第一次執行才邊跑邊編譯」的問題；啟動慢要看的是啟動時做了多少事 |
| 量測工具 | Layout Inspector、Macrobenchmark、Perfetto | Instruments（SwiftUI、Time Profiler、Animation Hitches）、MetricKit（Apple 提供，收集真實使用者的效能與當機資料） |
| 每秒更新很多次的資料 | 背景依幣種合併 → 固定週期輸出快照 | 做法相同：背景合併，再用固定間隔的 Timer 或 `CADisplayLink`（跟著螢幕更新節奏的計時器）更新 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 掉幀 | jank | ジャンク（カクつき） |
| 延後讀取 | deferred read（defer reads） | 読み取りの遅延 |
| 降低更新頻率 | throttle | 間引き（スロットリング） |
| 不可變集合 | immutable collection | イミュータブルコレクション |
| 事先編譯／執行時編譯 | AOT／JIT | AOT／JIT |
| Baseline Profile | Baseline Profile | ベースラインプロファイル |
