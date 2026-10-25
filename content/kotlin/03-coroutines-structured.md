---
id: kotlin-coroutines-structured
title: Coroutines 的父子關係、例外怎麼往上傳、取消是怎麼運作的
summary: Android 面試最愛追問的一塊。用「ViewModel 載入資料」的情境說明：coroutine 之間的父子規則（父等子、取消往下、失敗往上）、SupervisorJob 改了哪一條、launch 與 async 的例外各自去哪、取消為什麼要靠程式自己配合，以及為什麼不能把 CancellationException 吞掉。
priority: P0
day: 2
tags: [coroutines, structured-concurrency, cancellation, exception, viewmodelscope]
---

## 重點講解

這個單元回答五個面試常見問題：

1. 什麼是 structured concurrency（結構化並行）？`CoroutineScope` 和 `Job` 各是什麼？
2. `SupervisorJob` 和 `supervisorScope` 改變了哪一條規則？
3. 例外會怎麼往上傳？`launch` 和 `async` 的例外各自去哪？`CoroutineExceptionHandler` 什麼時候才有效？
4. 取消是怎麼運作的？為什麼不能把 `CancellationException` 吞掉？
5. `viewModelScope` 和 `lifecycleScope` 是什麼？dispatcher 為什麼要注入？

整個單元用同一個情境：使用者進入交易所 App 的「資產」頁，ViewModel 要同時載入餘額和委託單。

### 1. 什麼是 structured concurrency？

structured concurrency（結構化並行）的意思是：**每個 coroutine 都屬於某個 scope，coroutine 之間有父子關係，由三條規則管理生命週期：父等子、取消往下、失敗往上。** 好處是使用者離開畫面時，底下所有的工作會一起被收掉，不會有沒人管的工作留在背景。

先把用到的詞講清楚：

- **coroutine**：可以「暫停再繼續」的非同步工作。暫停的時候不佔著執行緒，所以可以同時開很多個。會暫停的位置叫 suspend 點，例如 `delay()`、`await()`。能暫停的函式要標 `suspend`。
- **`CoroutineScope`**：coroutine 的「所屬範圍」，決定它能活多久。例如 `viewModelScope` 屬於 ViewModel，ViewModel 被清除時，這個 scope 也跟著結束。
- **`Job`**：每個 coroutine 的「工作憑證」。`launch { }` 會回傳一個 `Job`，可以用它查狀態（`isActive`、`isCancelled`）、呼叫 `cancel()` 取消、呼叫 `join()` 等它結束。父子關係就是 Job 之間的關係。

三條規則：

- **父等子**：父 Job 要等所有子 Job 都完成，自己才算完成。
- **取消往下**：取消父，所有子一起被取消。
- **失敗往上**：子因為例外失敗，會取消父，父再取消其他兄弟。

```kotlin
fun main() = runBlocking {
    // 規則一：父等子
    val job = launch {
        launch {
            delay(100)
            println("child A done")
        }
        launch {
            delay(200)
            println("child B done")
        }

        // 父自己的程式先跑完，但父 Job 還不算完成
        println("parent body done")
    }

    // 等到 A、B 都完成，父才算完成
    job.join()
    println("all done")
    // 輸出：parent body done、child A done、child B done、all done

    // 規則二：取消往下
    val parent = launch {
        launch {
            try {
                delay(10_000)
            } finally {
                println("child cancelled")
            }
        }
    }
    delay(50)

    // 取消父，子也跟著被取消
    parent.cancelAndJoin()
    // 輸出：child cancelled
}
```

回到資產頁的情境：

```kotlin
class PortfolioViewModel(private val repo: PortfolioRepo) : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    fun load() {
        // 這個 coroutine 屬於 viewModelScope
        viewModelScope.launch {
            try {
                // 在裡面建立一個新的範圍
                coroutineScope {
                    // 兩個請求同時進行
                    val balance = async { repo.fetchBalance() }
                    val orders = async { repo.fetchOrders() }
                    _state.value = UiState.Success(
                        balance.await(),
                        orders.await(),
                    )
                }
            } catch (e: CancellationException) {
                // 取消不是錯誤，要往外丟（第 4 題詳述）
                throw e
            } catch (e: IOException) {
                _state.value = UiState.Error(e)
            }
        }
    }
}
```

- 使用者離開頁面，ViewModel 被清除，`viewModelScope` 取消，兩個請求一起被取消（取消往下）。
- `fetchBalance` 失敗，另一個 `async` 會被取消（失敗往上，再傳給兄弟），例外從 `coroutineScope { }` 重新丟出來，剛好被外面的 `try/catch` 接住。這就是 `coroutineScope` 的作用：任何子失敗都會取消其他子，並且把例外**重新丟出**，所以可以用 try/catch 包住。

一個細節：子 coroutine 被**取消**（`CancellationException`）不算失敗，不會往上傳，只有真正的例外才會。

**有哪些做法會繞過這個結構？**

- `GlobalScope.launch { }`：不屬於任何生命週期，ViewModel 清除了它還在跑。官方標成 `@DelicateCoroutinesApi`，意思是要很小心才可以用。
- `launch(Job()) { }`：傳進去一個全新的 `Job` 當父，原本的父子關係就被切斷。外層取消時它不會被取消，外層也不會等它。

> [!TIP]
> 面試時可以這樣說：「structured concurrency 就是三條規則：父等子、取消往下、失敗往上。在 ViewModel 裡用 `viewModelScope` 啟動，使用者離開畫面，底下所有的請求會一起被取消，不需要自己追蹤每個 Job。」

### 2. `SupervisorJob` 和 `supervisorScope` 改變了哪一條規則？

它們改的是「**失敗往上**」這一條：子失敗時，**不會**取消父和兄弟。「取消往下」仍然有效。

資產頁的例子：如果餘額和委託單是兩個互相獨立的區塊，委託單載入失敗，不應該讓餘額也跟著取消。這時就適合用 supervisor。

```kotlin
val handler = CoroutineExceptionHandler { _, e ->
    println("handler ${e.message}")
}

// 一般的 Job：一個子失敗，兄弟跟著被取消
val scope = CoroutineScope(Job() + handler)

// 失敗
scope.launch {
    delay(100)
    error("boom")
}

// 被牽連，印出 sibling cancelled
scope.launch {
    try {
        delay(10_000)
    } finally {
        println("sibling cancelled")
    }
}
// 之後這個 scope 也是取消狀態（scope.isActive 是 false），不能再用

// SupervisorJob：一個子失敗，兄弟不受影響
val sscope = CoroutineScope(SupervisorJob() + handler)

// 失敗，由 handler 印出 handler boom2
sscope.launch {
    delay(100)
    error("boom2")
}

// 照常執行
sscope.launch {
    delay(300)
    println("sibling survives")
}
// sscope.isActive 仍然是 true
```

有兩種寫法：

- **`SupervisorJob()`**：用在建立 scope 的時候（`CoroutineScope(SupervisorJob() + ...)`）。
- **`supervisorScope { }`**：在一個 suspend 函式裡面，臨時開一個 supervisor 範圍。

**重要提醒：supervisor 只是不連累別人，例外還是要有人處理。** 沒人處理的話，例外會一路掉到執行緒的 uncaught handler（沒有任何地方 catch 的例外最後會交給它），在 Android 上預設的結果就是 App crash。「用了 `SupervisorJob` 就不會 crash」是常見的誤解。

下一題會講例外怎麼處理。

> [!TIP]
> 面試時可以這樣說：「`SupervisorJob` 打破的是『失敗往上』這條，子失敗不會取消兄弟和父，但取消往下仍然有效。例外沒處理一樣會 crash，所以還是要在 `launch` 裡面 try/catch 或者設 `CoroutineExceptionHandler`。」

### 3. 例外怎麼往上傳？`launch` 和 `async` 的例外去哪？

**`launch` 的例外會立刻往上傳給父；`async` 的例外會存在它回傳的 `Deferred` 裡，要等你呼叫 `await()` 才重新丟出來。** 但如果這個 `async` 是某個 coroutine 的子，它失敗時一樣會立刻取消父，只 catch `await()` 擋不住。

`Deferred` 是 `async` 回傳的「未來才有的結果」，呼叫 `await()` 才拿得到值。

| | `launch` | `async` |
|---|---|---|
| 例外去向 | 立即往上傳給父；傳到最上層（沒有父的那個，英文叫 root coroutine，下面簡稱根）才交給 `CoroutineExceptionHandler`，沒有的話交給執行緒的 uncaught handler（Android 上就是 crash） | 存在 `Deferred`，`await()` 時才重新丟出 |
| 用 try/catch 包 | 包住 `launch { }` **沒用**，要把 try/catch 寫在大括號裡面 | 包住 `await()` 有用 |
| 常見陷阱 | 子 `launch` 的例外會把父與兄弟都取消 | 在一般 scope 裡作為**子**的 `async`，失敗時仍會立刻取消父；只 catch `await()` 擋不住 |

最後一列的陷阱，可以用程式碼確認：

```kotlin
try {
    coroutineScope {
        // 子 async 失敗：存進 Deferred，同時取消父 coroutineScope
        val d = async { error("x") }
        try {
            // 這裡接得到例外
            d.await()
        } catch (e: Exception) {
            // 輸出：inner caught
            println("inner caught")
        }

        // 輸出：inner done（println 不是 suspend 點，不會因取消而中斷）
        println("inner done")
    }
} catch (e: Exception) {
    // 輸出：outer caught x（coroutineScope 帶著例外結束）
    println("outer caught ${e.message}")
}
```

內層的 try/catch 只接住了「你自己讀結果」這條路，擋不住「失敗往上傳給父」這條路。想讓 `async` 的失敗只由 `await` 處理，要放在 `supervisorScope` 底下。

**`CoroutineExceptionHandler` 什麼時候有效？** 它是「最後防線」，用來上報錯誤、顯示錯誤訊息，不是用來控制流程或「恢復」的。它只在**根 coroutine** 的 `launch` 有效（`supervisorScope` 的直接子也算）：

```kotlin
val handler = CoroutineExceptionHandler { _, e ->
    println("handler ${e.message}")
}
val scope = CoroutineScope(SupervisorJob() + handler)

scope.launch { throw RuntimeException("one") }.join()
// 輸出：handler one（根 launch，handler 有效）

scope.launch { launch { throw RuntimeException("two") } }.join()
// 輸出：handler two（子 launch 失敗，例外往上傳給父，由根的 handler 處理；
//                    如果把 handler 掛在子 launch 上，不會被呼叫）

val d = scope.async { throw RuntimeException("three") }
runCatching { d.await() }
// 沒有輸出：根 async 的例外存在 Deferred，await() 才丟出，
// 不會呼叫 handler（這裡被 runCatching 接住）
```

所以真正需要處理的例外（例如網路失敗要顯示錯誤畫面），我會直接在 `launch { }` 的大括號裡面 try/catch。

> [!TIP]
> 面試時可以這樣說：「`launch` 的例外立刻往上傳，在外面包 try/catch 沒用，要寫在 lambda 裡面；`async` 的例外存在 `Deferred`，`await()` 才丟出。`CoroutineExceptionHandler` 只對根 `launch` 有效，我只拿它當最後防線上報，不拿來回復流程。」

### 4. 取消是怎麼運作的？為什麼不能把 `CancellationException` 吞掉？

取消不是強制中斷，而是「舉旗通知」：這種機制叫 cooperative cancellation（協作式取消）。coroutine 必須自己走到「會檢查取消的地方」，才會真正停下來。被取消時，coroutine 會在那個地方丟出一個 `CancellationException`，代表「被取消了」。**它不是錯誤**，所以不能當成錯誤吞掉或上報。

**哪些地方會檢查取消？** `delay`、`withContext`、`yield`、channel 和 flow 的 suspend 點都會。但是純計算的迴圈沒有 suspend 點，不會自己停，要自己檢查：

```kotlin
val job = launch(Dispatchers.Default) {
    var count = 0L

    // 每一圈檢查 isActive；寫成 while (true) 就永遠停不下來
    while (isActive) {
        count++
    }

    // 取消後會執行到這裡
    println("stopped loop")
}
delay(50)

// 輸出：stopped loop
job.cancelAndJoin()
```

在迴圈裡也可以呼叫 `ensureActive()`（已取消時直接丟出 `CancellationException`）或 `yield()`（檢查取消，順便讓出執行緒給別人）。如果呼叫的是會卡住執行緒的阻塞 API（`Thread.sleep`、舊式的 `InputStream.read`），它們也不會響應取消，要換成可取消的版本，或用 `runInterruptible` 包起來。

**為什麼不能吞掉 `CancellationException`？** `CancellationException` 是 `IllegalStateException` 的子類別，所以 `catch (e: Exception)` 和 `runCatching { }` 都會一併接住它。吞掉它並不會讓取消失效：Job 本身仍然是取消狀態，下一個 suspend 點還是會再丟一次。真正的危害是：**catch 之後那些不是 suspend 的程式碼，照樣會執行**。

```kotlin
// 使用者已經離開畫面，這個 coroutine 被取消了
val j = launch {
    // 取消時被 runCatching 接住，data 是 null
    val data = runCatching { fetchSlow() }.getOrNull()

    // 輸出：update ui with null
    println("update ui with $data")
}
// 呼叫端把「被取消」當成「沒有資料」，照常往下走
```

另一個常見的情況是 `catch (e: Exception)` 裡把例外 log 或上報給 Crashlytics，結果出現一堆「Job was cancelled」的雜訊。正確的做法是**先 rethrow `CancellationException`**，或只 catch 具體的例外型別（`IOException`、`HttpException`）：

```kotlin
suspend fun <T> safeCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        // 取消不是錯誤，一定要往外丟
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
```

**清理動作要用 `NonCancellable`。** 已經被取消的 coroutine，到了下一個 suspend 點就會再丟一次 `CancellationException`。所以在 `finally` 裡如果要呼叫 suspend 函式（登出 API、取消訂閱），會在第一個 suspend 點就被跳過。要用 `withContext(NonCancellable)` 讓這一小段不受取消影響：

```kotlin
try {
    api.subscribe().collect { handle(it) }
} finally {
    // 不受取消影響
    withContext(NonCancellable) {
        // 這個 suspend 呼叫才跑得完
        api.unsubscribe()
    }
}
```

只用在短小且必要的清理，不要把長時間的工作放進去。

最後是逾時：`withTimeout` 逾時時丟出的 `TimeoutCancellationException` 也是 `CancellationException` 的子類別。想在逾時時得到 `null` 而不是例外，用 `withTimeoutOrNull`。

> [!TIP]
> 面試時可以這樣說：「怎麼讓取消真正生效，我會講四件事：suspend 點都會檢查取消；純計算的迴圈要自己加 `ensureActive()`；catch 時要先 rethrow `CancellationException`，不要用 `runCatching` 包 suspend 呼叫；`finally` 裡的清理用 `withContext(NonCancellable)`。」

### 5. `viewModelScope` 和 `lifecycleScope` 是什麼？dispatcher 為什麼要注入？

`viewModelScope` 和 `lifecycleScope` 都是 Android 提供的現成 scope，內容是 **`SupervisorJob()` 加上 `Dispatchers.Main.immediate`**。

- `viewModelScope`：在 `ViewModel.onCleared()` 時取消。
- `lifecycleScope`：在 lifecycle 進入 `DESTROYED` 時取消。
- `SupervisorJob`：一個 `launch` 失敗不會牽連其他 `launch`；但例外沒人處理照樣 crash（見第 2 題）。
- `Dispatchers.Main.immediate`：Dispatcher 決定 coroutine 在哪個執行緒跑。`Main` 是主執行緒（畫面），`IO` 是網路和檔案，`Default` 是運算。`immediate` 的意思是：如果已經在主執行緒，就直接執行，不用再排一次訊息。

要注意：`lifecycleScope.launch { }` 在畫面進入 `onStop`（退到背景）後**不會**停，只有 destroy 才會停。想讓它在背景時停下來、回到前景再重開，要用 `repeatOnLifecycle`（下一個單元的 Flow 會講）。

**為什麼 dispatcher 要注入？** 因為 `viewModelScope` 預設在主執行緒，在 ViewModel 裡做網路或檔案工作，要自己切到 `IO`。比較好的寫法是：

```kotlin
class PortfolioRepo(private val ioDispatcher: CoroutineDispatcher) {
    // 函式自己切到 IO
    suspend fun fetchBalance(): String = withContext(ioDispatcher) {
        // 網路請求
        "1.5 BTC"
    }
}
```

- suspend 函式自己用 `withContext(dispatcher)` 切到適合的執行緒，呼叫端不用管現在在哪個執行緒呼叫。這種寫法叫 main-safe（在主執行緒呼叫也安全）。
- dispatcher 用建構子傳進來（注入）而不是寫死 `Dispatchers.IO`，測試時才能換成測試用的 dispatcher（下一個單元會講）。

最後是「fire-and-forget」：丟出一個 coroutine 就不管了，沒有人持有它、追蹤它。App 被系統殺掉時，工作就消失。每個 coroutine 都應該有明確的 scope owner（誰負責它的生命週期）：跟著畫面的工作用 `viewModelScope`；要活得比畫面久的工作（例如登出後要送出的事件），用 App 層級注入的 scope，需要保證一定執行就交給 WorkManager。

> [!TIP]
> 面試時可以這樣說：「`viewModelScope` 是 `SupervisorJob` 加 `Dispatchers.Main.immediate`，ViewModel 清除時自動取消。在裡面做 IO 要自己 `withContext` 切 dispatcher，而且 dispatcher 我用建構子注入，測試時才能換掉。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin03Coroutines**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin03-coroutines/`，解答在 `labs/android/solution/kotlin/kotlin03-coroutines/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：離開畫面了，輪詢還在跑（第 1 題）

**檔案**：`L01_ScopeLeak.kt`

**情境**：行情畫面打開時開始每 100 ms 輪詢一次價格，350 ms 後使用者離開畫面。

**怎麼寫**

1. **Bug 版**：在 `onOpen()` 用 `GlobalScope.launch(Dispatchers.Default) { pollLoop() }` 開始輪詢。`onClose()` 已經寫好，只印「離開畫面」。
2. **修正版**：在 `PriceScreen` 裡宣告自己的 scope：`CoroutineScope(SupervisorJob() + Dispatchers.Default)`；`onOpen()` 用它 `launch`，`onClose()` 呼叫 `scope.cancel()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「離開畫面」之後還繼續印「輪詢價格」，最後印出「離開畫面後又輪詢了 5 次」左右；演練結束後 log 還在跳 |
| 修正版 | 「離開畫面後又輪詢了 0 次」 |

**為什麼**：`GlobalScope` 不屬於任何畫面，開在裡面的 coroutine 沒有人能取消。讓畫面擁有一個 scope，coroutine 開在這個 scope 裡，離開時取消 scope，底下的工作就一起停。真正的 App 裡，這個 scope 就是 `viewModelScope` 或 `lifecycleScope`。

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L01_ScopeLeak.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L01_ScopeLeak.kt#fixed
```

### 演練 2：一個幣種斷線，整個列表都停了（第 2 題）

**檔案**：`L02_Supervisor.kt`

**情境**：行情列表同時訂閱 BTC、ETH、XRP，每個幣種一個 coroutine。`subscribe()` 已經寫好：XRP 在第 3 筆時丟出 `IOException`。

**怎麼寫**

1. **Bug 版**：在 `try` 裡寫 `coroutineScope { }`，對每個幣種 `launch { subscribe(symbol, received, log) }`。
2. **修正版**：先只把 `coroutineScope` 換成 `supervisorScope` 跑一次，看看在手機上會發生什麼事；再建立 `CoroutineExceptionHandler`（收到例外時印「只有這個幣種失敗」），改成 `launch(handler) { … }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | XRP 出錯時，BTC、ETH 也被取消，各只收到 2–3 筆，log 出現「整個列表停止」 |
| 修正版 | BTC、ETH 收完 5 筆；XRP 停在 2 筆，log 出現「只有這個幣種失敗」 |

**為什麼**：一般的父子關係是「一個孩子失敗，父親取消所有孩子」。`supervisorScope` 改掉的就是這一條：孩子失敗不牽連兄弟。但失敗的例外還是要有人處理，所以要加 `CoroutineExceptionHandler`（或在孩子裡面 try／catch），否則在 Android 上一樣會讓 App 當掉。

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L02_Supervisor.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L02_Supervisor.kt#fixed
```

### 演練 3：try／catch 包住 launch，App 還是當掉（第 3 題）

**檔案**：`L03_UncaughtException.kt`

**情境**：按下「下單」後開一個 coroutine 呼叫下單 API。`placeOrder()` 已經寫好：100 ms 後丟出 `IOException("下單 API 逾時")`。

**怎麼寫**

1. **Bug 版**：在 `try` 裡寫 `screenScope.launch { val id = placeOrder(); log("下單成功：$id") }.join()`，也就是 try／catch 包在 `launch` 的外面。先打開 Logcat 再執行。
2. **修正版**：一樣 `screenScope.launch { … }.join()`，但把 try／catch 寫在大括號裡面，接到 `IOException` 時印「顯示錯誤：…，請稍後再試」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | App 當掉，Logcat 出現 `FATAL EXCEPTION` 和 `IOException: 下單 API 逾時`；log 裡沒有「接到例外」 |
| 修正版 | 印出「顯示錯誤：下單 API 逾時，請稍後再試」，接著「下單流程結束」 |

**為什麼**：`launch` 只是把工作交出去就回來，例外是之後在 coroutine 裡面丟的，外面的 catch 接不到。例外一路往上傳到 scope，沒有人處理，就交給執行緒的 uncaught exception handler，Android 上就是 App 當掉。

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L03_UncaughtException.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L03_UncaughtException.kt#fixed
```

### 演練 4：離開畫面後，還用 null 更新畫面（第 4 題）

**檔案**：`L04_SwallowCancellation.kt`

**情境**：資產畫面載入餘額要 0.3 秒。情境 A 使用者一直停在畫面上；情境 B 使用者 0.1 秒就離開，coroutine 被取消。

**怎麼寫**

1. **Bug 版**：`load()` 裡用 `runCatching { fetchBalance() }.getOrNull()` 取得餘額，接著印「更新畫面：餘額 = …」。
2. **修正版**：寫 `safeCall`：`try` 回傳 `Result.success(block())`；`catch (e: CancellationException)` 直接 `throw e`；`catch (e: IOException)` 回傳 `Result.failure(e)`。`load()` 改用 `safeCall`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 情境 A 正常顯示 1.5 BTC；情境 B「使用者離開畫面」之後還印出「更新畫面：餘額 = null」 |
| 修正版 | 情境 A 正常顯示 1.5 BTC；情境 B 離開畫面之後只剩「載入流程結束」 |

**為什麼**：取消時會丟出 `CancellationException`，`runCatching` 也會把它接住，變成「沒有資料」，後面不是 suspend 的程式照樣執行。遇到 `CancellationException` 一定要往外丟，只處理真正的錯誤。

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L04_SwallowCancellation.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L04_SwallowCancellation.kt#fixed
```

### 演練 5：關掉畫面後，ViewModel 還在背景輪詢（第 5 題）

**檔案**：`L05_ViewModelScope.kt`

**情境**：行情畫面的 ViewModel 一建立就每 0.5 秒輪詢價格。這個演練用畫面操作：按「打開行情畫面」「關閉行情畫面」。

**怎麼寫**

1. **Bug 版**：在 ViewModel 裡宣告 `private val scope = CoroutineScope(Dispatchers.Main)`，`init` 裡 `scope.launch { pollPrices(log) }`。
2. **修正版**：不要自己建 scope，`init` 裡改用 `viewModelScope.launch { pollPrices(log) }`。
3. 兩個版本都反覆開關畫面 3 次，看上方「存活中 輪詢中」的數字。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 關掉畫面後 log 繼續印；每開關一次「輪詢中」就加 1，log 越印越快（每個輪詢最多跑 30 次，約 15 秒後才停） |
| 修正版 | 關掉畫面後立刻停止，「輪詢中」回到 0 |

**為什麼**：畫面關閉時 ViewModel 的 `onCleared` 會被呼叫，但自己建立的 scope 沒有人取消。`viewModelScope` 會在 `onCleared` 時自動取消，底下的 coroutine 一起停。

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L05_ViewModelScope.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin03-coroutines/L05_ViewModelScope.kt#fixed
```

## 常見陷阱

- **`try { launch { throw … } } catch` 抓不到。** 例外是在 coroutine 裡面丟的，不是在 `launch` 這行丟的。要把 try/catch 寫在大括號裡面，或用 `coroutineScope { launch … }` 讓例外被重新丟出。
- **以為用了 `SupervisorJob` 就不會 crash。** 它只是不連累兄弟，沒人處理的例外照樣交給 uncaught handler。
- **在 `catch (e: Exception)` 裡把 `CancellationException` 當錯誤上報。** 會出現大量「Job was cancelled」的雜訊。先 rethrow 取消，或只 catch 具體的例外型別。
- **用 `runCatching` 包 suspend 呼叫，卻沒處理取消。** 取消被轉成 `Result.failure`，後面的程式照樣執行，呼叫端把「被取消」當成「沒有資料」。
- **`launch(Job())` 或 `CoroutineScope(Job())` 之後忘了 `cancel()`。** 生命週期失控，工作可能比畫面活得還久。
- **在 `finally` 裡呼叫 suspend 函式，卻沒包 `NonCancellable`。** 清理動作在第一個 suspend 點就被跳過。
- **以為只 catch `await()` 就能擋住 `async` 的失敗。** 一般 scope 裡的 `async` 失敗時，仍會立刻取消父；要放在 `supervisorScope`。
- **`async { }` 之後沒有 `await()`。** 例外與結果被默默忽略，或父被取消卻不知道為什麼。

## 面試官想聽到的

1. 能用「父等子、取消往下、失敗往上」三句話描述 structured concurrency，並說出 `supervisorScope` 打破的是哪一條。
2. 區分 `launch` 與 `async` 的例外路徑，並說出 `CoroutineExceptionHandler` 只對根 `launch` 有效、只能當最後防線。
3. 會主動提到 `CancellationException` 不能吞、`runCatching` 的坑，以及 `NonCancellable` 的用途。
4. 知道 `viewModelScope` 的組成（`SupervisorJob` 加 `Dispatchers.Main.immediate`），並能說明為什麼要把 dispatcher 注入。
5. 能講出「我怎麼避免 fire-and-forget」：每個 coroutine 都有明確的 scope owner。

> [!WARNING]
> 面試官常追問：「`viewModelScope` 裡的 `launch` 丟出沒人處理的例外，會怎樣？」答案是：`SupervisorJob` 不會連累其他 `launch`，但這個例外交給 uncaught handler，App 會 crash。所以要在 `launch` 裡面 try/catch，或加上 `CoroutineExceptionHandler`。

> [!FIELD]
> 在一個社交 App 的 Android 版裡，大量使用 `viewModelScope`、`lifecycleScope` 與 `repeatOnLifecycle`，並透過 Hilt 的 `DispatcherModule` 注入 `@IoDispatcher`、`@MainDispatcher`、`@DefaultDispatcher`，dispatcher 是注入的，測試時可以替換。在一個即時行情 App 的 Android 版裡，把即時 K 線的 SSE（Server-Sent Events，伺服器單向推送） 連線放在 `viewModelScope`，ViewModel 清除時連線就跟著結束；畫面進入背景時會通知 ViewModel，回到前景時再通知一次。被問到「怎麼避免 coroutine 洩漏」時，這是可以直接舉的例子。

## iOS / Android 對照

| 主題 | Kotlin Coroutines | Swift Concurrency |
|---|---|---|
| 結構化並行 | `coroutineScope { }`、`supervisorScope { }` | `async let`、`TaskGroup`（`withTaskGroup`、`withThrowingTaskGroup`） |
| 非結構化 | `scope.launch { }`、`GlobalScope` | `Task { }`（繼承目前的 actor 與優先權）、`Task.detached` |
| 跟著畫面生命週期 | `viewModelScope`、`lifecycleScope` | SwiftUI 的 `.task { }` modifier：view 消失時自動取消 |
| 沒人處理的例外 | 根 `launch` 的例外交給 uncaught handler，App 會 crash | `Task { }` 裡丟出的錯誤會存在 Task 裡，沒人去讀就不會有任何提示（不會 crash，但會被默默忽略） |
| 取消檢查 | `ensureActive()`、`isActive` | `Task.isCancelled`、`try Task.checkCancellation()` |
| 取消例外 | `CancellationException` | `CancellationError` |
| 吞掉取消的坑 | `catch (e: Exception)`、`runCatching` | 空的 `catch { }` 和 `try?` 同樣會吞掉 `CancellationError` |
| 清理與取消回呼 | `finally { withContext(NonCancellable) { … } }` | `withTaskCancellationHandler(operation:onCancel:)`，`onCancel` 是同步 closure，取消當下立刻執行 |

兩邊的取消都是協作式的：要在適當的地方檢查，才會真的停下來。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 結構化並行 | structured concurrency | 構造化された並行性 |
| 協作式取消（要靠程式自己檢查才會停） | cooperative cancellation | 協調的キャンセル |
| coroutine 所屬的範圍 | CoroutineScope | コルーチンスコープ |
| 可查狀態、可取消的控制物件 | Job／SupervisorJob | ジョブ／SupervisorJob |
| 會暫停的位置（suspend 點） | suspension point | 中断点 |
| 沒人 catch 的例外最後交給的處理器 | uncaught exception handler | 未捕捉例外ハンドラ |
| 決定 coroutine 在哪個執行緒跑 | Dispatcher | ディスパッチャ |
