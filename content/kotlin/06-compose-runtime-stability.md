---
id: kotlin-compose-runtime-stability
title: Compose 的重組：畫面為什麼會重畫、怎麼避免多餘的重畫
summary: 面試很常問「Compose 的重組是什麼」「為什麼這個 Composable 資料沒變還是每次都重組」。這個單元說明重組的範圍、Compose 怎麼追蹤 State、stable 與 unstable 型別的差別、strong skipping 從哪個版本開始預設開啟，最後補上 remember、rememberSaveable、key 的正確用法。
priority: P0
day: 2
tags: [compose, recomposition, snapshot, stability, strong-skipping, remember]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 什麼是重組（recomposition）？State 變了，畫面上哪一塊會重畫？
2. Compose 怎麼知道哪個 State 被誰讀過？從 State 變更到畫面更新，中間發生了什麼事？
3. 為什麼資料明明沒變，這個 Composable 還是每次都重組？（stable 與 skipping）
4. strong skipping mode 改變了什麼？哪個版本開始預設開啟？
5. 怎麼確認真的有「多餘的重組」，而不是憑感覺猜？
6. `remember`、`rememberSaveable` 和 `key` 怎麼用？

### 1. 什麼是重組？State 變了，畫面上哪一塊會重畫？

**重組是指：State 變了之後，Compose 把「讀過這個 State 的那一塊 Composable」重新執行一次，用新的資料算出新畫面。重組的最小單位是讀了 State 的那一塊（稱為 recompose scope），不是整個畫面。**

先把名詞講清楚：

- **Composable** 是用 `@Composable` 標記的函式，用來描述一塊畫面長什麼樣子，概念上類似 SwiftUI 的 `body`。
- **State** 是 Compose 會追蹤的資料，例如 `mutableStateOf("100")` 建立的值。State 變了，用到它的畫面就要跟著更新。
- **recompose scope（重組範圍）** 是重組時被重新執行的那一小塊程式。你可以先把它想成「讀了那個 State 的 Composable 函式」。

用交易所的價格畫面來看：

```kotlin
@Composable
fun PriceScreen(priceFlow: StateFlow<String>) {
    // collectAsState 把 Flow 轉成 Compose 的 State
    val price by priceFlow.collectAsState()

    Column {
        Text("BTC/JPY")          // 標題，永遠不變
        Text("價格：$price")      // 這一行用到 price
    }
}
```

`price` 每秒變好幾次。這時候，`PriceScreen` 整個函式會重新執行一次（因為讀取 `price` 的地方在這個函式裡），但 `Text("BTC/JPY")` 的參數沒變，Compose 會**跳過**它（skip，意思是直接沿用上次的結果、不再執行）。只有真的需要更新的部分才會動。

如果價格畫面很複雜，不想讓整個 `PriceScreen` 都重新執行，做法是把「讀 State 的地方」往下推到一個小的 Composable：

```kotlin
@Composable
fun PriceScreen(priceFlow: StateFlow<String>) {
    Column {
        Text("BTC/JPY")
        PriceText(priceFlow)       // 這裡只傳 Flow，沒有讀取 price
    }
}

@Composable
fun PriceText(priceFlow: StateFlow<String>) {
    val price by priceFlow.collectAsState()   // 讀取只發生在這個小函式裡
    Text("價格：$price")                       // price 變了，只有 PriceText 重組
}
```

現在 `price` 變的時候，只有 `PriceText` 會重新執行，`PriceScreen` 完全不動。

**容易搞錯的地方：哪些 lambda 有自己的重組範圍？**

- `Column`、`Row`、`Box` 是 `inline` 函式。`inline` 是 Kotlin 的功能，意思是編譯時直接把函式內容貼到呼叫的地方。所以它們大括號裡的內容，等於直接寫在外層的 Composable 裡，**在裡面讀 State，會讓外層的 Composable 重組**。上面第一個例子就是這樣：`Text("價格：$price")` 寫在 `Column { }` 裡，實際重組的是 `PriceScreen`。
- `Button { }` 這種不是 `inline` 的 Composable，大括號裡的內容有自己的重組範圍，在裡面讀 State，只會重組那個 lambda。
- 在 `onClick` 這類事件 lambda 裡讀 State，**不會**讓 Composable 重組。事件 lambda 是使用者點擊時才執行，不在「組合畫面」的過程中，Compose 沒有把它記為讀取者。

另外，Composable 要當成「可能被重複執行、被跳過、順序不固定，甚至將來可能平行執行」來寫，所以 Composable 的函式內容不能有副作用（例如修改外部變數、送網路請求）。需要副作用時要用專門的 API，下一個單元會說明。

> [!TIP]
> 面試時可以這樣說：「重組的最小單位是讀取 State 的 recompose scope，不是整個畫面。我如果發現某個 State 讓一大塊畫面重組，會把讀取它的地方往下推，拆成一個獨立的小 Composable。要注意 Column、Row、Box 是 inline，它們裡面讀 State 會算在外層 Composable 身上。」

### 2. Compose 怎麼知道哪個 State 被誰讀過？

**Compose 有一套記錄 State 讀寫的機制，叫 snapshot system。可以把它想成一本「State 的讀寫記錄本」：Composable 執行時讀了哪個 State 就記一筆；State 被寫入新值時，就去找記過帳的那些 recompose scope，標記為「需要重做」，在下一個 frame 重組。**

frame 是指畫面更新一次的週期，一般螢幕每秒更新 60 次，也就是每個 frame 約 16 毫秒。

`mutableStateOf` 建立的就是被這本記錄本管理的 State。從 State 變更到畫面更新，順序是這樣：

1. 在主執行緒寫入新的值，例如 `price = "101"`。
2. snapshot system 發現這個 State 的值變了，找出讀過它的 recompose scope，標記為失效（需要重做）。
3. 下一個 frame，Recomposer（Compose 裡負責排程與執行重組的元件）重新執行失效的 scope；參數沒變的子 Composable 會被跳過。
4. **Layout 階段**：重新計算受影響的元素要放哪裡、多大。
5. **Drawing 階段**：實際把畫面畫出來。

每個 frame 都依序走 **Composition（決定要顯示什麼）→ Layout（決定放哪裡、多大）→ Drawing（實際繪製）** 三個階段。State 在哪個階段被讀取，變更時就只需要從那個階段重做。例如在 `Modifier.offset { }` 這種 lambda 版的 modifier 裡讀取 State，那是在 Layout 階段讀的，變更時會跳過 Composition，這就是第 8 單元要講的「延後讀取」。

有三個行為，面試很常考：

**(1) 寫入的值和舊值 `equals` 相同，不會觸發重組。** `mutableStateOf` 預設用 `equals` 比較新舊值（結構相等，`structuralEqualityPolicy`）：

```kotlin
var price by mutableStateOf("100")
price = "100"   // 和舊值 equals，不會觸發重組
price = "101"   // 不同，讀過 price 的 scope 被標記失效
```

**(2) 重組發生在「下一個 frame」，不是寫入當下。** 所以在同一個事件處理裡連續寫入好幾次，只會重組一次：

```kotlin
Button(onClick = {
    count++      // 寫入，但還沒有重組
    count++      // 再寫入，仍然沒有重組
}) { Text("加 2") }
// 下一個 frame 只重組一次，畫面直接顯示 +2
```

**(3) 原地修改 State 裡的物件，Compose 看不到。** Compose 只知道「State 的值被換掉了」，不知道你改了值裡面的內容：

```kotlin
// 錯誤：State 裡存的還是同一個 list，Compose 不知道內容變了，畫面不會更新
val badTicks = remember { mutableStateOf(mutableListOf<Tick>()) }
badTicks.value.add(tick)

// 正確做法一：換成一個新的 list，State 的值變了
var ticks by remember { mutableStateOf(listOf<Tick>()) }
ticks = ticks + tick

// 正確做法二：改用 Compose 提供的 State 版集合，修改它的內容會通知 Compose
val tickList = remember { mutableStateListOf<Tick>() }
tickList.add(tick)
```

> [!TIP]
> 面試時可以這樣說：「Compose 用 snapshot system 記錄每個 recompose scope 讀了哪些 State。State 被寫入新值，而且和舊值不 equals 時，就把讀過它的 scope 標記失效，在下一個 frame 重組。所以原地修改一般的 MutableList 是沒用的，要換成新的物件，或用 mutableStateListOf。」

### 3. 為什麼資料沒變，這個 Composable 還是每次都重組？

**重組時，Compose 會比較每個參數「和上一次有沒有變」，全部沒變就跳過（skip）這個 Composable。但 Compose 只敢對 stable（穩定）的型別用 `equals` 比較；碰到 unstable（不穩定）的型別，在沒有 strong skipping 的情況下，會保守地假設它「可能變了」，於是每次都重組。**

先看畫面上的現象：行情列表有 300 列，每秒只有一檔幣種的價格在變，但用 Android Studio 的 Layout Inspector（後面會介紹）看，**300 列的重組次數全部一起增加**。每一列的資料明明沒變，卻都被重新執行。最常見的原因，就是列的參數型別被 Compose 判定成 unstable。

**什麼是 stable？** Compose 要能放心地用 `equals` 判斷「參數有沒有變」，這個型別必須同時滿足三件事：`equals` 的結果一致；公開屬性改變時 Compose 會被通知；所有公開屬性的型別也都是 stable。編譯器會自己推斷：

```kotlin
// stable：只有 val，而且型別都是 stable（基本型別、String、函式型別本來就是 stable）
data class Tick(val symbol: String, val price: Long)

// unstable：List 是介面，執行時可能是會被改動的 ArrayList，編譯器無法保證它不會變
data class Board(val name: String, val ticks: List<Tick>)

// unstable：有 var，值可以改，而且改了 Compose 不會被通知
class Config(var depth: Int)
```

還有一種很容易漏掉的 unstable：**定義在沒有套用 Compose compiler 的 module 裡的類別**。例如專案把資料模型放在純 Kotlin 的 domain module，那個 module 沒有 Compose compiler 分析它，即使欄位全是 `val`，也會被當成 unstable。解法有三種：在該 module 套用 Compose compiler；用 stability configuration file（一個文字檔，列出要視為 stable 的型別，支援 `*`、`**` 萬用字元，例如 `java.time.*`）；或在 UI 層另外做一份 stable 的 UI model。

**`@Stable` 和 `@Immutable` 是什麼？** 這兩個註解是你對編譯器的**承諾**：

- `@Immutable`：「這個型別的所有公開屬性，建立之後都不會再變」。
- `@Stable`：「這個型別的 `equals` 結果一致，而且公開屬性改變時會通知 Compose」。

編譯器**不會驗證**這個承諾。說謊的後果是畫面該更新卻沒更新，而且很難找：

```kotlin
@Immutable
class Config(var depth: Int)   // 說謊：depth 可以改，Compose 卻以為它不會變

// 使用者把 depth 從 5 改成 10，Config 還是同一個物件
// Compose 以為參數沒變，跳過重組，畫面還是顯示 5
```

所以不要為了消除警告就亂加註解。正確的順序是：先用工具確認哪個型別是 unstable、而且真的造成多餘重組，再優先改設計（用 `val`、不可變的集合、獨立的 UI model），最後才用註解或 stability configuration file。

最後把「比較」這件事講清楚。Kotlin 有兩種相等：

```kotlin
val a = listOf(1, 2, 3)
val b = listOf(1, 2, 3)
println(a == b)    // 輸出：true，`==` 是 equals，內容一樣就算相等
println(a === b)   // 輸出：false，`===` 要求是同一個物件；a 和 b 是兩個不同的物件
```

stable 的參數用 `equals` 比較，unstable 的參數在沒有 strong skipping 時乾脆不比較、一律當作有變。

> [!TIP]
> 面試時可以這樣說：「Compose 只敢對 stable 型別用 equals 判斷參數有沒有變。含 var 的 class、List 這種介面、還有沒套用 Compose compiler 的 module 裡的類別，都會被當成 unstable。@Stable 和 @Immutable 是我對編譯器的承諾，不會被驗證，所以我不拿來消除警告，而是先用 compiler reports 確認哪個型別真的有問題。」

### 4. strong skipping mode 改變了什麼？哪個版本開始預設開啟？

**strong skipping mode 讓「帶有 unstable 參數的 Composable」也能被跳過，只是 unstable 參數改用 `===`（是不是同一個物件）比較。Kotlin 2.0.20 起，這個模式預設開啟。**

版本差異，面試時被問到要答得出來：

| Kotlin 版本 | Compose compiler 的狀況 | strong skipping |
|---|---|---|
| 2.0.0 之前 | Compose compiler 是獨立發佈的，版本要和 Kotlin 版本配對 | 實驗功能，預設關閉，要自己加編譯器選項才能開 |
| 2.0.0 | Compose compiler 併入 Kotlin 一起發佈，用 Gradle plugin `org.jetbrains.kotlin.plugin.compose` 啟用 | 仍然要手動開啟 |
| 2.0.20 之後 | 同上 | **預設開啟** |

開啟後的規則：

- stable 的參數：用 `equals` 比較（內容一樣就算沒變）。
- unstable 的參數：用 `===` 比較（必須是同一個物件才算沒變）。
- Composable 裡寫的 lambda，會自動被 `remember`（以它捕獲的值當作判斷是否重建的依據），所以同樣的 lambda 不會每次重組都產生新物件。
- 不想讓某個 Composable 被跳過，可以加上 `@NonSkippableComposable`。

**但 strong skipping 不是萬靈丹。** 因為 unstable 參數改用 `===`，只要每次都產生「內容一樣、但是新的物件」，比較還是會失敗，照樣重組：

```kotlin
@Composable
fun FavoriteList(allTicks: List<TickUi>, onClick: (String) -> Unit) {
    // 每次 FavoriteList 重組，filter 都會產生一個新的 List 物件，就算內容完全一樣
    val favorites = allTicks.filter { it.isFavorite }
    TickList(favorites, onClick)   // List 被當成 unstable，用 === 比較，永遠不同 → TickList 重組
}

@Composable
fun FavoriteListFixed(allTicks: List<TickUi>, onClick: (String) -> Unit) {
    // remember：allTicks 沒變就沿用上次算好的結果，是同一個物件
    val favorites = remember(allTicks) { allTicks.filter { it.isFavorite } }
    TickList(favorites, onClick)
}
```

解法有幾種：用 `remember` 保存算好的結果（如上）；讓資料層只在內容真的改變時才產生新物件（例如 Flow 的 `distinctUntilChanged`，它的意思是新值和上一個值相同就不往下送）；或改用 `ImmutableList`（`kotlinx.collections.immutable` 函式庫提供的不可變集合，Compose compiler 認得它是 stable，所以會用 `equals` 比較）。

補充一點：`TickList` 多重組一次，通常不貴，因為裡面每一列如果參數沒變，還是會被跳過。是不是真的需要處理，要先量測才知道。

> [!TIP]
> 面試時可以這樣說：「strong skipping 在 Kotlin 2.0.20 起預設開啟，unstable 參數改用 === 比較，所以含 List 的參數也能跳過。但每次產生內容相同的新 List，=== 還是不相等，照樣重組。我會用 remember 保存算好的結果，或讓資料層在內容沒變時不產生新物件，而且先量測再決定。」

### 5. 怎麼確認真的有「多餘的重組」？

**先量測，再修改。常用的工具有兩個：Android Studio 的 Layout Inspector，用來看每個 Composable 的重組次數；Compose compiler reports，用來看每個型別是 stable 還是 unstable。**

**Layout Inspector：** 在 Android Studio 對執行中的 App 開 Layout Inspector，打開重組計數的選項，每個 Composable 旁邊會顯示「重組了幾次」和「跳過了幾次」。畫面上沒變的東西，重組次數卻一直增加，就是多餘的重組。這是找出「哪一個 Composable 有問題」的工具。

**Compose compiler reports：** 讓編譯器把分析結果輸出成文字檔，列出每個 class 是 stable 還是 unstable，以及每個 Composable 能不能被跳過。在 module 的 `build.gradle.kts` 設定：

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}
```

建置之後會在輸出資料夾看到類似這樣的內容：

```text
unstable class Board {
  stable val name: String
  unstable val ticks: List<Tick>
  <runtime stability> = Unstable
}
```

意思是 `Board` 是 unstable，原因是 `ticks` 這個 `List<Tick>` 屬性。這是找出「為什麼有問題」的工具。

面試被問「Compose 效能怎麼最佳化」，先講量測：用 Layout Inspector 確認真的有多餘的重組，用 compiler reports 找出原因，再依序處理：資料層不要產生內容相同的新物件、UI model 用 `val` 加不可變集合、`LazyColumn` 給 `key`、把每秒變很多次的 State 讀取延後到 Layout 或 Drawing 階段（第 8 單元），最後才是 `@Stable`、`@Immutable` 註解。沒量測就加 `@Immutable` 是反面教材。

> [!TIP]
> 面試時可以這樣說：「我不會憑感覺最佳化。先用 Layout Inspector 看重組次數，確認有沒有多餘的重組，再用 compiler reports 找出是哪個型別 unstable，最後才改設計。改完之後再看一次次數，確認有改善。」

### 6. `remember`、`rememberSaveable` 和 `key` 怎麼用？

**`remember` 讓值活過「重組」，重組之間不會被重新計算；`rememberSaveable` 還能撐過旋轉螢幕、以及 App 的行程被系統殺掉後重開；列表要給 `key`，每一列的狀態才會跟著「那一檔幣種」，而不是跟著「第幾列」。**

這三個東西各自解決一種畫面上看得到的問題：

```kotlin
@Composable
fun OrderForm(tickSize: BigDecimal, ticks: List<Tick>) {
    // 使用者輸入到一半的數量，旋轉螢幕後還在
    var qty by rememberSaveable { mutableStateOf("") }

    // 依照 tickSize 建立的格式化規則；tickSize 變了就重新建立
    val rule = remember(tickSize) { TickRule(tickSize) }

    LazyColumn {
        // 以 symbol 當身分，而不是以位置
        items(ticks, key = { it.symbol }) { tick -> TickRow(tick) }
    }
}
```

**`remember` 的 key 漏寫，是最常見的 bug。** 現象：使用者切換交易對之後，價格的小數位數還是上一個交易對的。

```kotlin
val formatter = remember { PriceFormatter(tickSize) }           // 錯：只在第一次建立，tickSize 變了也不會重建
val formatter = remember(tickSize) { PriceFormatter(tickSize) } // 對：tickSize 變了就重建
```

`remember { }` 的計算只在第一次進入畫面時執行。凡是會影響計算結果的輸入，都要當成 `remember` 的參數（key）傳進去。

**三種「保存狀態」的存活範圍不同：**

| | 重組 | 旋轉螢幕 | App 行程被系統殺掉後重開 |
|---|---|---|---|
| `remember` | 保留 | 消失 | 消失 |
| `rememberSaveable` | 保留 | 保留 | 保留（只適合小量資料） |
| ViewModel | 保留 | 保留 | 消失（要保留的話搭配 `SavedStateHandle`） |

行程被殺是指：App 在背景時，Android 為了回收記憶體把整個 App 的行程殺掉；使用者回到 App，系統會重新建立畫面，並把先前存起來的狀態（saved instance state）還原。`rememberSaveable` 就是存進這個地方，所以只適合小量資料，例如輸入中的文字、選取的分頁。資料量太大會丟 `TransactionTooLargeException`（資料超過系統允許的大小）。大型資料放在 ViewModel 或 repository。自訂型別要提供 `Saver`，告訴 Compose 怎麼把它轉成能放進 saved instance state 的資料。

**`key` 解決「狀態黏錯列」的問題。** 想像行情列表每一列都有一個「展開詳細資料」的 `remember` 狀態：

```kotlin
@Composable
fun TickRow(tick: Tick) {
    var expanded by remember { mutableStateOf(false) }   // 這一列有沒有展開
    // ...
}
```

使用者把 BTC 那一列展開，這時列表最上面插入了一檔新的 ETH。沒有給 `key` 時，狀態是跟著「位置」走：展開的狀態還留在原本的位置，但那個位置現在顯示的是別檔幣種，畫面上就會變成「ETH 被展開了，BTC 卻收起來」。給了 `key = { it.symbol }` 之後，Compose 用 `symbol` 辨認每一列，狀態就會跟著 BTC 走。`key` 必須唯一而且穩定（同一檔幣種的 key 不能變）。

在 `LazyColumn` 以外的地方，也可以用 `key(x) { }` 包住一段 Composable，讓 Compose 用 `x` 當身分。

> [!TIP]
> 面試時可以這樣說：「remember 只活過重組，旋轉螢幕或行程被殺就消失，所以輸入中的文字我用 rememberSaveable，大型資料放 ViewModel。remember 的計算如果依賴參數，一定要把參數當 key 傳進去。LazyColumn 我會給 key，這樣列表重排或插入時，每一列的狀態才會跟著資料走，不會黏錯項目。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin06Recomposition**。這個單元的演練都是畫面操作，沒有自動檢查，要看畫面上的橘色數字（重組次數）或操作結果。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin06-recomposition/`，解答在 `labs/android/solution/kotlin/kotlin06-recomposition/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」，照步驟操作，確認看到的現象和下面的表格一樣 → 寫修正版 → 選「修正版」重新操作 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **橘色數字**：畫面上的「重組 XXX：N 次」由 LabKit 的 `RecomposeCounter` 顯示，數的是「放它的那個 Composable，函式內容被重新執行了幾次」。想用 Android Studio 對照，可以開 Layout Inspector，打開顯示重組次數（Show Recomposition Counts）的選項。

### 演練 1：價格一跳，整個畫面都跟著重組（第 1 題）

**檔案**：`L01_ScopeRead.kt`

**情境**：行情畫面有標題區 `ChartHeader` 和價格文字。價格（`MutableStateFlow<Long>`）每 250 ms 加 1，共 20 次。畫面上的橘色數字顯示 `PriceScreen`、`ChartHeader`、`PriceText` 各重組了幾次。

**怎麼寫**

1. **Bug 版**：在 `PriceScreen` 的最上層 `val price by prices.collectAsState()`，並把 `Text("價格：$price")` 寫在 `Column { }` 裡。
2. **修正版**：`PriceScreen` 裡改成 `PriceText(prices)`，只傳 Flow；在 `PriceText` 裡才 `collectAsState()` 並顯示 `Text`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `PriceScreen` 的次數一直增加，更新完畢後約 21 次；`ChartHeader` 維持 1 次（被跳過） |
| 修正版 | `PriceScreen` 與 `ChartHeader` 都維持 1 次；只有 `PriceText` 一直增加，約 21 次 |

**為什麼**：重組的最小單位是讀了 State 的 recompose scope。`Column`、`Row`、`Box` 是 inline 函式，它們大括號裡的內容等於直接寫在外層，在裡面讀 State 會算在外層的 Composable 身上。把「讀 State 的地方」往下推到一個小的 Composable，State 變了就只有那一小塊重組。`ChartHeader` 的參數沒變，Compose 會跳過它。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L01_ScopeRead.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L01_ScopeRead.kt#fixed
```

### 演練 2：清單變長了，畫面卻沒更新（第 2 題）

**檔案**：`L02_MutateInPlace.kt`

**情境**：行情畫面把收到的報價放進清單，並顯示「畫面顯示 N 筆」。按下「收到一筆新報價」模擬 WebSocket 推來一筆，log 會印出清單裡真正有幾筆。

**怎麼寫**

1. **Bug 版**：`val ticks = remember { mutableStateOf(mutableListOf<FeedTick>()) }`；按鈕裡 `ticks.value.add(FeedTick(…))` 並 log；畫面顯示 `ticks.value.size`。
2. **修正版**：`val ticks = remember { mutableStateListOf<FeedTick>() }`；按鈕裡 `ticks.add(FeedTick(…))`；畫面顯示 `ticks.size`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 連按三次後，log 印出「list 裡現在有 3 筆」，畫面卻一直是「畫面顯示 0 筆」 |
| 修正版 | 畫面跟 log 一樣，按三次後是「畫面顯示 3 筆」 |

**為什麼**：Compose 的 snapshot system 只記錄 State 的讀寫：`ticks.value` 這個 State 的值（同一個 list 物件）沒有被換掉，Compose 看不到你改了 list 的內容，所以不會重組。改用 `mutableStateListOf`（內容改變時會通知 Compose），或每次換成新的 list（`ticks.value = ticks.value + tick`）。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L02_MutateInPlace.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L02_MutateInPlace.kt#fixed
```

### 演練 3：資料沒變，卡片還是每次都重組（第 3 題）

**檔案**：`L03_UnstableType.kt`

**情境**：自選清單畫面上方有「最後更新」計數（每 300 ms 加 1，共 20 次），下方的 `WatchlistCard` 顯示一個 `Board`（名稱加幣種清單）。`Board` 的內容整個過程都沒變，只是畫面每次重組都用同樣的內容建立一個新的 `Board` 傳給卡片。專案是 Kotlin 2.x，strong skipping 預設開啟。

**怎麼寫**

1. **Bug 版**：`data class Board(val name: String, val ticks: List<WatchTick>)`，畫面裡 `WatchlistCard(Board("主要幣種", ticks))`。
2. **修正版**：`Board` 的 `ticks` 改成 `ImmutableList<WatchTick>`（`kotlinx.collections.immutable`），並用 `listOf(…).toImmutableList()` 建立。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `WatchlistCard` 的次數一直增加，更新完畢後約 21 次 |
| 修正版 | `WatchlistCard` 維持 1 次（被跳過） |

**為什麼**：`List` 是介面，編譯器無法保證它不會被改動，所以含 `List` 的 `Board` 是 unstable。strong skipping 對 unstable 的參數用 `===`（是不是同一個物件）比較，每次重組都是新的 `Board`，比較失敗，卡片就重組。`ImmutableList` 是 Compose compiler 認得的 stable 型別，`Board` 變成 stable，改用 `equals` 比較：內容一樣就算沒變。先用 compiler reports 確認哪個型別是 unstable，再改設計。想自己看報告：在 `solution`（或 `practice`）的 `kotlin` 資料夾的 `build.gradle.kts` 最後加上 `composeCompiler { reportsDestination = layout.buildDirectory.dir("compose_reports") }`，建置後打開 `build/compose_reports` 裡的 `*-classes.txt`。解答實測的內容是：

```text
unstable class tw.harry.labs.solution.kotlin06recomposition.L03Bug.Board {
  stable val name: String
  unstable val ticks: List<WatchTick>
  <runtime stability> = Unstable
}
stable class tw.harry.labs.solution.kotlin06recomposition.L03Fixed.Board {
  stable val name: String
  stable val ticks: ImmutableList<WatchTick>
  <runtime stability> =
}
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L03_UnstableType.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L03_UnstableType.kt#fixed
```

### 演練 4：開了 strong skipping，列表還是每次都重組（第 4 題）

**檔案**：`L04_NewListEachTime.kt`

**情境**：自選清單從所有幣種裡篩出「我的最愛」，交給 `TickList` 顯示。畫面上方的「最後更新」計數每 300 ms 加 1（共 20 次），`allTicks` 整個過程都沒變。`TickList` 的參數是 `List<TickUi>`。

**怎麼寫**

1. **Bug 版**：`val favorites = allTicks.filter { it.isFavorite }`，再 `TickList(favorites)`。
2. **修正版**：`val favorites = remember(allTicks) { allTicks.filter { it.isFavorite } }`，再 `TickList(favorites)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `TickList` 的次數一直增加，更新完畢後約 21 次 |
| 修正版 | `TickList` 維持 1 次（被跳過） |

**為什麼**：Kotlin 2.0.20 起 strong skipping 預設開啟，unstable 的參數改用 `===` 比較，所以含 `List` 的參數也能被跳過。但 `filter` 每次都產生一個新的 `List` 物件（內容一樣），`===` 比較還是不相等，照樣重組。`remember(allTicks)` 讓 `allTicks` 沒變時沿用上次算好的同一個物件；也可以讓資料層只在內容真的改變時才產生新物件。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L04_NewListEachTime.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L04_NewListEachTime.kt#fixed
```

### 演練 5：輸入到一半的數量，旋轉螢幕後不見了（第 6 題）

**檔案**：`L05_RememberSaveable.kt`

**情境**：下單畫面有一個「下單數量」輸入欄。使用者輸入到一半旋轉螢幕，Activity 會被銷毀再重建。按鈕「模擬旋轉螢幕」會呼叫 `Activity.recreate()`，效果和真的旋轉一樣；也可以直接旋轉模擬器。畫面重建時 log 會印出「畫面建立：下單數量欄位是…」。

**怎麼寫**

1. **Bug 版**：`var qty by remember { mutableStateOf("") }`。
2. **修正版**：`var qty by rememberSaveable { mutableStateOf("") }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 輸入 0.5 後旋轉，欄位變回空的；log「畫面建立：下單數量欄位是「」」 |
| 修正版 | 旋轉後欄位仍然是 0.5；log「畫面建立：下單數量欄位是「0.5」」 |

**為什麼**：`remember` 只活過重組，Activity 重建後就消失。`rememberSaveable` 把值存進 saved instance state，旋轉螢幕、App 的行程被系統殺掉後重開都還在；因為它有大小限制，只適合小量資料（輸入中的文字、選取的分頁），大型資料放 ViewModel。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L05_RememberSaveable.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L05_RememberSaveable.kt#fixed
```

### 演練 6：切換交易對後，小數位數還是上一個的（第 6 題）

**檔案**：`L06_RememberKey.kt`

**情境**：價格要依交易對的 tick size（最小跳動單位）決定顯示幾位小數：BTC_JPY 的 tick size 是 1（整數），ETH_JPY 的是 0.01（兩位小數）。`PriceFormatter` 依 tick size 建立（已經寫好）。畫面有兩個按鈕切換交易對，價格固定是 12345.6789。

**怎麼寫**

1. **Bug 版**：`val formatter = remember { PriceFormatter(tickSize) }`。
2. **修正版**：`val formatter = remember(tickSize) { PriceFormatter(tickSize) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 先按 BTC_JPY 再按 ETH_JPY，價格仍然顯示 12345（整數） |
| 修正版 | 切到 ETH_JPY 顯示 12345.67；切回 BTC_JPY 顯示 12345 |

**為什麼**：`remember { }` 的計算只在第一次進入畫面時執行。凡是會影響計算結果的輸入，都要當成 `remember` 的參數（key）傳進去，輸入變了才會重新計算。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L06_RememberKey.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L06_RememberKey.kt#fixed
```

### 演練 7：插入新幣種後，展開的狀態黏錯列（第 6 題）

**檔案**：`L07_LazyColumnKey.kt`

**情境**：行情列表每一列（`CoinRow`，已經寫好）有一個「展開詳細資料」的狀態，用 `remember` 保存在那一列裡。使用者把 BTC 展開，這時列表最上面插入了一檔新的 SOL。

**怎麼寫**

1. **Bug 版**：`items(coins) { coin -> CoinRow(coin) }`，不給 key。
2. **修正版**：`items(coins, key = { it.symbol }) { coin -> CoinRow(coin) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 點 BTC 展開，再按「在最上面插入 SOL」：SOL 那一列是展開的，BTC 反而收起來 |
| 修正版 | 插入 SOL 後，SOL 收起來，BTC 仍然展開 |

**為什麼**：沒給 `key` 時，Compose 用「位置」當每一列的身分，展開的狀態留在原本的位置（第 1 列），但第 1 列現在是 SOL。給了 `key = { it.symbol }` 之後，Compose 用 `symbol` 辨認每一列，狀態就會跟著 BTC 走。`key` 必須唯一而且穩定，不能用列表的位置。

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L07_LazyColumnKey.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin06-recomposition/L07_LazyColumnKey.kt#fixed
```

第 5 題（怎麼確認真的有多餘的重組）是流程題，沒有獨立的演練：演練 3、4 的步驟就是照那一題的做法，用橘色數字（或 Layout Inspector 的重組次數）確認問題，再改設計，改完再看一次次數。

## 常見陷阱

- **以為 `@Stable`、`@Immutable` 會被編譯器驗證，在含 `var` 的 class 上亂加。** 編譯器不會檢查，說謊的結果是畫面該更新卻沒更新。先用 compiler reports 確認問題，再改設計。
- **在 Composable 的函式內容裡直接寫入 State（例如 `count++`），或修改外部變數。** Composable 可能被重複執行、跳過、順序不固定，函式內容不能有副作用。需要副作用時，用 `LaunchedEffect`、`SideEffect` 這類專門的 API（下一個單元）。
- **domain module 沒有套用 Compose compiler，導致所有資料模型都被當成 unstable。** 即使欄位全是 `val` 也一樣。要在該 module 套用 compiler，或用 stability configuration file，或在 UI 層另外做 UI model。
- **`rememberSaveable` 放太大的物件。** 會丟 `TransactionTooLargeException` 或存不進去。大型資料放 ViewModel 或 repository。
- **把 `List<Tick>` 當 Composable 的參數，而且每次都產生新的 list。** 即使有 strong skipping，`===` 比較也會失敗。用 `remember` 保存結果，或讓資料層在內容沒變時不產生新物件。
- **`LazyColumn` 沒有給 `key`。** 列表資料變動時，狀態和動畫會黏在錯的項目上。

## 面試官想聽到的

1. 能說出重組的最小單位是讀了 State 的 recompose scope，而且知道 `Column`、`Row`、`Box` 是 inline，它們裡面讀 State 會算在外層 Composable 身上。
2. 能解釋 stable 與 unstable 的判定，也知道 `@Stable`、`@Immutable` 是對編譯器的承諾，不會被驗證。
3. 能講出 strong skipping 從 Kotlin 2.0.20 起預設開啟、參數怎麼比較（stable 用 `equals`、unstable 用 `===`），並且知道它之後仍然要避免每次產生新的物件。
4. 最佳化之前先量測：用 Layout Inspector 和 compiler reports 驗證，而不是憑感覺加註解。
5. 能分清楚 `remember`、`rememberSaveable`、ViewModel 各自活多久。

> [!FIELD]
> 兩種專案的做法可以對照：
> - 在一個社交 App 的 Android 版裡，為了統一程式碼規範、降低技術債，在開發新功能之餘逐頁重構，過程中陸續導入 Jetpack Compose、Navigation Component 與 Paging 3，並把依賴注入從 Dagger 遷移到 Hilt。專案使用的 Kotlin 版本在 2.0.20 之後，所以 strong skipping 是預設開啟的。
> - 在一個即時行情 App 的 Android 版裡，全面採用 Jetpack Compose。Watchlist 畫面把資料用 `toImmutableList()` 轉成 `ImmutableList`，並放在 `remember(displayData)` 裡，避免每次重組都產生新的 list；專案也有使用 `derivedStateOf`，列表有給 `key`。
> - 接手新專案時，我會先量測（Layout Inspector、compiler reports、Macrobenchmark），再決定要不要處理 stability。

## iOS / Android 對照

| 主題 | Compose | SwiftUI |
|---|---|---|
| 畫面失效的單位 | recompose scope：讀了 State 的那一塊 Composable | View 的 `body` 被重新求值 |
| 追蹤的粒度 | snapshot 記錄每一次 State 的讀取，只通知讀過的 scope | `ObservableObject` 的任何 `@Published` 屬性變了，整個物件都會通知所有用到它的 View；iOS 17 的 `@Observable` 才改成屬性層級（只通知真的讀過那個屬性的 View） |
| 跳過重組 | 參數沒變就跳過：stable 用 `equals`，strong skipping 下 unstable 用 `===` | View 是值型別，SwiftUI 比較 View 的屬性有沒有變；可以讓 View 遵守 `Equatable` 並加上 `.equatable()` 自訂比較方式 |
| 列表項目的身分 | `key`、`items(key = )` | `ForEach(id:)`、`Identifiable` |
| 設定變更後保留狀態 | `rememberSaveable`、ViewModel | `@SceneStorage`、`@AppStorage`、ViewModel |
| 找出多餘的重組 | Layout Inspector 的重組次數、Compose compiler reports | Instruments 的 SwiftUI 範本，可以看 View 的 `body` 被執行幾次 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 重組 | recomposition | 再コンポーズ（リコンポジション） |
| recompose scope | recompose scope | 再コンポーズスコープ |
| snapshot system | snapshot system | スナップショットシステム |
| 穩定／不穩定 | stable／unstable | 安定／不安定 |
| 跳過 | skip（skipping） | スキップ |
| strong skipping mode | strong skipping mode | ストロングスキッピングモード |
| 保存狀態 | remember／rememberSaveable | remember／rememberSaveable |
