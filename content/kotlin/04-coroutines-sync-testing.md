---
id: kotlin-coroutines-sync-testing
title: Coroutines 的鎖與限流、runBlocking 的坑、token 過期只刷新一次、runTest 怎麼測
summary: 多個 coroutine 同時改同一份資料時要用什麼工具（Mutex、Semaphore、limitedParallelism），runBlocking 為什麼在主執行緒和 OkHttp 都危險，token 過期（401）時怎麼讓並行的請求只刷新一次，以及 runTest 的虛擬時間為什麼要把 dispatcher 注入。
priority: P0
day: 2
tags: [coroutines, mutex, semaphore, limitedparallelism, runblocking, runtest, token-refresh]
---

## 重點講解

這個單元回答五個面試常見問題：

1. 多個 coroutine 同時改同一份資料時，`Mutex`、`Semaphore`、`Atomic` 怎麼選？
2. `limitedParallelism(1)` 是單執行緒嗎？可以拿來保護 Realm 嗎？
3. `runBlocking` 為什麼危險？什麼時候可以用？
4. 多個請求同時收到 401（token 過期），怎麼讓 token 只刷新一次？
5. `runTest` 怎麼測 coroutine？虛擬時間是什麼？

### 1. 多個 coroutine 同時改同一份資料，怎麼選工具？

要看你想解決什麼問題：**要讓一段程式同一時間只有一個人在跑，用 `Mutex`；要限制同時進行的數量，用 `Semaphore`；只是簡單的計數或旗標，用 `Atomic`；整個物件只能在一條執行緒碰，用專屬的單執行緒 dispatcher。**

先說明背景。多個 coroutine 或執行緒同時讀寫同一份資料，叫共享可變狀態（shared mutable state）。最典型的錯誤是「先檢查再動作」（check-then-act）。例如交易所 App 的餘額快取：

```kotlin
class BalanceRepo(private val api: Api) {
    private var cache: Balance? = null

    suspend fun getBalance(): Balance {
        cache?.let { return it }           // 檢查：有快取就直接回傳
        val fresh = api.fetchBalance()     // 動作：這是 suspend，等待期間別的 coroutine 也會進來
        cache = fresh
        return fresh
    }
}
// 10 個畫面元件同時呼叫 getBalance()：10 個都看到 cache 是 null，結果 API 被呼叫 10 次
```

用 `Mutex`（互斥鎖）解決：拿到鎖的人才能進去，其他人排隊等。進去的那一段程式叫臨界區段（critical section），同一時間只能有一個人在裡面。

```kotlin
class BalanceRepo(private val api: Api) {
    private var cache: Balance? = null
    private val mutex = Mutex()

    suspend fun getBalance(): Balance = mutex.withLock {   // 拿到鎖才能進來，離開時自動釋放
        cache ?: api.fetchBalance().also { cache = it }    // 檢查與動作都在鎖裡面
    }
}
// 同樣 10 個並行呼叫：第一個去呼叫 API，其他 9 個等鎖，進來時 cache 已經有了。API 只呼叫 1 次
```

| 需求 | 工具 | 備註 |
|---|---|---|
| 保護「先檢查再動作」的一段程式 | `Mutex.withLock { }` | 是 suspend，等鎖時只暫停 coroutine，不會卡住執行緒；**不可重入** |
| 限制同時進行的數量 | `Semaphore(n).withPermit { }` | 限制 API 同時呼叫數、圖片解碼 |
| 簡單的計數或旗標 | `AtomicInteger`、`AtomicReference` | 不需要上鎖 |
| 整個物件只能被一條執行緒碰 | 專屬的單執行緒 dispatcher | Realm、舊式 SDK 這類有 thread affinity 的資源 |
| 把操作排成佇列 | `Channel` 搭配單一消費者 coroutine | actor 風格，操作自然一個接一個 |

**Mutex 有兩個一定要知道的特性：**

1. **不可重入（non-reentrant）。** 重入的意思是：已經拿著鎖的人，再拿一次同一把鎖。有些鎖允許，Kotlin 的 `Mutex` 不允許，同一個 coroutine 持鎖時再 `lock()` 會永遠等下去，這叫 deadlock（死結：互相等對方，誰都不動，永遠卡住）。

```kotlin
val m = Mutex()
m.withLock {
    println(m.tryLock())                                   // 輸出：false（已經持鎖，拿不到第二次）
    println(m.isLocked)                                    // 輸出：true
    println(withTimeoutOrNull(50) { m.withLock { "inner" } }) // 輸出：null（內層要等自己，50 毫秒後逾時）
}
println(m.isLocked)                                        // 輸出：false（離開後鎖已釋放）
```

   需要巢狀呼叫時，把「已經持鎖」的版本拆成內部函式。

2. **保護的是一段程式，不是一個變數。** 如果「檢查」和「動作」分在兩次 `withLock`，兩次之間仍然會被別人插隊。check-then-act 要放在**同一個** `withLock` 裡。

另外兩個提醒：持鎖期間不要做慢的 I/O；`synchronized` 不要包 suspend 呼叫（編譯器也不允許在 `synchronized` 區塊裡 suspend），否則會卡住執行緒。

**`Semaphore`（號誌）用來限流**：`Semaphore(4)` 表示最多 4 個人同時在做，其他人排隊。交易所的公開 REST API 通常會限制每個 IP 的請求頻率（例如每秒 10 個請求，實際以官方文件為準），一次要查很多交易對的報價時，可以這樣寫：

```kotlin
private val gate = Semaphore(4)

suspend fun fetchAll(ids: List<String>): List<Quote> = coroutineScope {
    ids.map { id -> async { gate.withPermit { api.quote(id) } } }.awaitAll()   // 同時最多 4 個請求在跑
}
```

要注意 `Semaphore` 只限制「同時幾個」，不限制「每秒幾個」。如果 API 限制的是每秒請求數，還需要另外控制速率，例如 token bucket（令牌桶：按固定速度發放請求許可）。

> [!TIP]
> 面試時可以這樣說：「要互斥用 `Mutex`，要限制並行數量用 `Semaphore`，簡單計數用 `Atomic`。`Mutex` 不可重入，所以巢狀呼叫要拆內部函式；先檢查再動作一定要放在同一個 `withLock` 裡，不然兩次之間還是會被插隊。」

### 2. `limitedParallelism(1)` 是單執行緒嗎？可以拿來保護 Realm 嗎？

**不是。** `Dispatchers.Default.limitedParallelism(1)` 只保證「同一時間最多一個任務在跑」，也就是互斥，但**不保證每次都在同一條執行緒**。

```kotlin
val serial = Dispatchers.Default.limitedParallelism(1)    // 同一時間最多一個任務
var counter = 0
repeat(1000) { launch(serial) { counter++ } }             // 不需要鎖，counter 也不會算錯
```

這樣做可以取代鎖來保護變數：任務之間有 happens-before 的保證，意思是前一個任務寫入的資料，後一個任務一定看得到（不會讀到舊值）。

但有些資源有 thread affinity（執行緒親和性）：只能在「建立它的那條執行緒」上使用，換一條就出錯。Realm 的實例就是這樣。`limitedParallelism(1)` 每次可能換不同的執行緒，所以不適合。這種資源要用真正的單執行緒 dispatcher：

```kotlin
val realmDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
// 所有 Realm 操作都用 withContext(realmDispatcher) 包起來
// 不用時要呼叫 realmDispatcher.close()，否則那條執行緒不會被釋放
```

補充：`Dispatchers.IO` 預設最多同時使用 64 條執行緒（或 CPU 核心數，取較大的），但 `Dispatchers.IO.limitedParallelism(n)` 例外，可以超過這個上限，所以適合替資料庫連線池這類資源開專用的並行度。`Dispatchers.Default` 的 `limitedParallelism` 則仍然受 CPU 核心數限制。

> [!TIP]
> 面試時可以這樣說：「`limitedParallelism(1)` 保證的是互斥，不是同一條執行緒。像 Realm 這種只能在建立它的執行緒使用的資源，我用專屬的單執行緒 dispatcher，在一個社交 App 的 `RealmManager` 就是這樣做的。」

### 3. `runBlocking` 為什麼危險？什麼時候可以用？

`runBlocking` 是「把 suspend 函式接到一般阻塞程式」的橋，它會**卡住呼叫它的那條執行緒**，等裡面做完才回來。所以只能用在「阻塞的世界和 suspend 的世界交界的地方」，數量越少越好，而且要加 timeout。

**在主執行緒上用，風險最大：**

- 主執行緒被卡住太久，就是 ANR（Application Not Responding：輸入事件約 5 秒沒處理，系統跳出「App 沒有回應」）。
- 更糟的是 deadlock：

```kotlin
// 在主執行緒上
runBlocking {                          // 主執行緒被卡住，等這個區塊做完
    withContext(Dispatchers.Main) {    // 但這個區塊要回到主執行緒才能執行 → 互相等，永遠卡住
        updateUi()
    }
}
```

**在 OkHttp 的 interceptor 裡用，是常見的橋接。** OkHttp 的 interceptor（攔截每個請求的元件）本來就跑在 OkHttp 自己的阻塞執行緒上，不是主執行緒，所以不會直接卡住畫面。如果需要在 interceptor 裡呼叫 suspend 的 API（例如刷新 token），`runBlocking` 是橋。但仍然有成本：

- 占用 dispatcher 的執行緒池。
- 取消傳不進去：呼叫 `Call.cancel()` 不會取消裡面的 coroutine。
- 如果外面再包一層 `synchronized`，就變成「卡住執行緒」加「卡住 coroutine」兩層疊加，其他請求全部卡在鎖上。

所以我的原則是：`runBlocking` 只出現在邊界（例如 OkHttp 的 `Authenticator`），裡面加 `withTimeout`，不要散落在各處。

> [!TIP]
> 面試時可以這樣說：「`runBlocking` 會卡住呼叫它的執行緒，在主執行緒上會 ANR，甚至 deadlock。我只在像 OkHttp `Authenticator` 這種阻塞世界與 suspend 世界的邊界使用，而且一定加 timeout。」

### 4. 多個請求同時收到 401，怎麼讓 token 只刷新一次？

先講情境：登入憑證（access token）過期時，伺服器會回 HTTP 401。App 要用 refresh token 換一張新的 access token，再重送原本的請求。問題是：畫面同時送出 10 個請求，10 個都收到 401，如果每個都去刷新，就會刷新 10 次。更糟的是，refresh token 如果是一次性的，第二次刷新會失敗，使用者被迫登出。

要的效果叫 single-flight：很多請求同時需要同一件事時，只真的做一次，其他人等結果、共用。做法有三個重點：

1. **用 OkHttp 的 `Authenticator`。** 它是 OkHttp 專門處理 401 的元件，只有收到 401 時才會被呼叫。
2. **用 `Mutex`，而且把「檢查」和「刷新」放在同一個鎖裡。** 進鎖之後，先比對「送出請求時用的 token」和「目前儲存的 token」。如果不一樣，代表前一個等鎖的人已經刷新過了，直接用現有的 token 重送，不用再刷新。
3. **限制重試次數，並加 timeout。** 伺服器如果一直回 401，不能無限重試。

```kotlin
class TokenStore(initial: String, private val refreshRemote: suspend (stale: String) -> String) {
    private val mutex = Mutex()
    @Volatile private var current = initial

    suspend fun refreshAfter401(stale: String): String = mutex.withLock {
        if (current != stale) current                  // 前一個等鎖的人已經刷新過，直接用
        else refreshRemote(stale).also { current = it } // 沒人刷新過，才真的呼叫 API
    }
}

class TokenAuthenticator(private val store: TokenStore) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // 往回數這個請求已經重試過幾次，超過上限就放棄（回傳 null 表示不再重送）
        if (generateSequence(response) { it.priorResponse }.count() > 2) return null
        val stale = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        // 這是整個流程唯一的 runBlocking：Authenticator 是阻塞世界，refreshAfter401 是 suspend
        val fresh = runBlocking { withTimeout(10_000) { store.refreshAfter401(stale) } }
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }
}
```

10 個並行的 401，只會有 1 個真的呼叫 `refreshRemote`，其他 9 個拿到同一個新 token。

為什麼比對的是 token 本身，而不是過期時間 `expiresAt`？因為這樣不依賴手機的時鐘，也能處理伺服器提早撤銷 token 的情況。如果連 refresh token 也失效了，就清除登入資料，導向登入畫面，不要再重試。

> [!TIP]
> 面試時可以這樣說：「並行的 401 我用 `Mutex` 把『比對舊 token、必要時才刷新』放在同一個鎖裡，這樣只會刷新一次。`runBlocking` 只留在 `Authenticator` 這個橋接點，加 timeout，並用 `priorResponse` 限制重試次數。」

### 5. `runTest` 怎麼測 coroutine？虛擬時間是什麼？

一般測試如果有 `delay(10_000)`，就真的要等 10 秒。`kotlinx-coroutines-test` 的 `runTest` 提供**虛擬時間（virtual time）**：`delay` 不會真的等待，而是由測試排程器（scheduler）直接把虛擬時鐘撥快，所以測試瞬間跑完。

```kotlin
runTest {
    delay(10_000)
    println("virtual now = $currentTime")   // 輸出：virtual now = 10000（虛擬時鐘走了 10 秒，實際上幾乎不花時間）
}
```

這組 API 有些標了 `@ExperimentalCoroutinesApi`，使用時要加 `@OptIn(ExperimentalCoroutinesApi::class)`。幾個要知道的行為：

- **`runTest` 預設使用 `StandardTestDispatcher`：`launch` 只是把工作「排進佇列」，不會馬上執行。** 要等測試主體讓出（呼叫 `advanceUntilIdle()`、`runCurrent()` 或 `delay`）才會跑。`UnconfinedTestDispatcher` 則是立即執行，適合收集 `StateFlow`。
- **`advanceTimeBy(n)` 把虛擬時鐘往前推 `n` 毫秒，只執行「嚴格早於目標時間」的任務；剛好排在目標時間的任務不會執行**，要再呼叫 `runCurrent()`。測 debounce（連續事件只取最後一次）、逾時邏輯時，這個邊界常是出錯點。
- **永遠不會結束的 coroutine**（無限收集 flow、輪詢）要放在 `backgroundScope.launch`，測試結束時它會自動取消。否則 `runTest` 會一直等下去。

```kotlin
runTest {
    val log = mutableListOf<String>()
    launch { log += "A"; delay(100); log += "B" }
    log += "main"
    runCurrent()                  // 執行排隊中的 launch：A
    log += "t$currentTime"        // t0
    advanceTimeBy(100)            // 時鐘推進到 100，但剛好排在 100 的 B 還沒執行
    log += "t$currentTime"        // t100
    runCurrent()                  // 現在才執行 B
    log += "end"
    println(log.joinToString(" "))   // 輸出：main A t0 t100 B end
}
```

**最重要的前提：虛擬時間只對「和測試共用同一個 scheduler 的 dispatcher」有效。** 如果程式碼裡寫死 `Dispatchers.IO` 或 `Dispatchers.Default`，`delay` 就是真實時間，測試會真的等。所以 dispatcher 要用建構子注入，測試時換成共用 scheduler 的 `TestDispatcher`：

```kotlin
class Repo(private val io: CoroutineDispatcher) {
    suspend fun load() = withContext(io) { delay(10_000); "ok" }   // 用注入的 dispatcher
}

runTest {
    val repo = Repo(StandardTestDispatcher(testScheduler))   // 共用測試的 scheduler
    println("${repo.load()} at virtual $currentTime")        // 輸出：ok at virtual 10000，瞬間完成
}
// 如果 Repo 裡寫死 withContext(Dispatchers.IO)，這個測試會真的等 10 秒
```

另外，`viewModelScope` 用的是 `Dispatchers.Main`，在單元測試（沒有 Android 主執行緒）裡不存在，所以要用 `Dispatchers.setMain(testDispatcher)` 換掉，測試結束要 `Dispatchers.resetMain()`，通常包成一個 JUnit rule：

```kotlin
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(testDispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
```

`setMain` 改的是全域狀態，沒有 `resetMain()` 會污染後面的測試。

> [!TIP]
> 面試時可以這樣說：「`runTest` 讓 `delay` 不真的等，由 scheduler 撥快虛擬時鐘。前提是程式碼用的 dispatcher 要能被換掉，所以我把 dispatcher 用建構子注入，測試時換成共用 scheduler 的 `TestDispatcher`。ViewModel 的 Main 用 `setMain` 換掉，結束時 `resetMain`。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin04CoroutineSync**。演練 8 不用開 App，直接在電腦上跑單元測試。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin04-coroutine-sync/`，解答在 `labs/android/solution/kotlin/kotlin04-coroutine-sync/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：10 筆入帳同時進來，餘額少算了（第 1 題）

**檔案**：`L01_LostUpdate.kt`

**情境**：10 個 coroutine（例如 10 個畫面元件）各自連續入帳 10 次，每次 1 日圓，預期最後餘額 100。入帳流程是：讀目前餘額 → 呼叫風控檢查 `riskCheck()`（suspend，約 1 ms，已經寫好）→ 把「讀到的餘額 + 入帳金額」寫回去。

**怎麼寫**

1. **Bug 版**：`Wallet.deposit()` 不加任何保護，依序 `val current = balance`、`riskCheck()`、`balance = current + amount`。
2. **修正版**：在 `Wallet` 裡宣告 `Mutex()`，把 `deposit()` 的讀、`riskCheck()`、寫放進同一個 `mutex.withLock { }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「預期餘額 100，實際餘額 10」左右：遠小於 100 |
| 修正版 | 「預期餘額 100，實際餘額 100」 |

**為什麼**：`riskCheck()` 暫停的這一毫秒，別的 coroutine 也讀到同一個舊餘額，大家都寫回「舊餘額 + 1」，後寫的把先寫的蓋掉，這叫 lost update（更新遺失）。`Mutex` 讓同一時間只有一個 coroutine 能進入臨界區段，而且讀、檢查、寫必須放在同一個 `withLock` 裡，分成兩次還是會被插隊。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L01_LostUpdate.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L01_LostUpdate.kt#fixed
```

### 演練 2：10 個元件同時要餘額，API 被打了 10 次（第 1 題）

**檔案**：`L02_CheckThenAct.kt`

**情境**：資產畫面有 10 個元件同時呼叫 `BalanceRepo.getBalance()`。餘額有快取：有快取就直接回傳，沒有才呼叫 `api.fetchBalance()`（約 100 ms，已經寫好，並且會記下被呼叫幾次）。

**怎麼寫**

1. **Bug 版**：`cache?.let { return it }` 檢查快取，沒有就 `api.fetchBalance()`、存進 `cache`、回傳，不加鎖。
2. **修正版**：宣告 `Mutex()`，整個 `getBalance()` 放進 `mutex.withLock { cache ?: api.fetchBalance().also { cache = it } }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「API 被呼叫 10 次」，10 個元件都拿到 1500000 |
| 修正版 | 「API 被呼叫 1 次」，10 個元件都拿到 1500000 |

**為什麼**：這是最典型的 check-then-act（先檢查再動作）。檢查快取和寫入快取之間，API 呼叫會暫停，10 個 coroutine 都在快取還是 `null` 時通過了檢查。把檢查和動作放在同一個 `withLock` 裡，第一個進去的去打 API，其他 9 個等鎖，進來時快取已經有了。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L02_CheckThenAct.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L02_CheckThenAct.kt#fixed
```

### 演練 3：出金時卡住不動，Mutex 死結（第 1 題）

**檔案**：`L03_MutexReentrant.kt`

**情境**：`Account` 用 `Mutex` 保護餘額。`withdraw()` 扣款後，要順便取一份餘額快照寫進稽核紀錄。`snapshot()` 平常也會被別的地方呼叫，所以它自己也會拿鎖。演練用 `withTimeoutOrNull(300)` 當保險，避免真的永遠卡住。

**怎麼寫**

1. **Bug 版**：`withdraw()` 在 `mutex.withLock { }` 裡扣款後，回傳 `snapshot()`（`snapshot()` 裡也有 `mutex.withLock`）。
2. **修正版**：寫一個不拿鎖的 `private fun snapshotLocked(): Long = balance`；`withdraw()` 在鎖裡改呼叫它；對外的 `snapshot()` 改成 `mutex.withLock { snapshotLocked() }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 等 300 ms 後印出「出金 300 ms 內沒有完成：死結」 |
| 修正版 | 「出金完成，餘額 900」，接著「另外單獨取快照：900」 |

**為什麼**：Kotlin 的 `Mutex` 不可重入：已經持鎖的 coroutine 再 `lock()` 同一把鎖，會永遠等下去，這是 deadlock（死結）。解法是把「已經持鎖」的版本拆成內部函式，持鎖的地方呼叫它；對外的函式才自己拿鎖。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L03_MutexReentrant.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L03_MutexReentrant.kt#fixed
```

### 演練 4：一次查 20 個報價，同時送出 20 個請求（第 1 題）

**檔案**：`L04_Semaphore.kt`

**情境**：行情畫面要一次查 20 個交易對的報價，交易所的公開 API 有請求頻率限制，App 自己要限制同時進行的請求數最多 4 個。假的 `QuoteApi` 每次約 50 ms，並記下「同時進行」的最大請求數。

**怎麼寫**

1. **Bug 版**：`symbols.map { symbol -> async(Dispatchers.Default) { api.quote(symbol) } }.awaitAll()`，全部同時送出。
2. **修正版**：宣告 `private val gate = Semaphore(4)`，每個請求改成 `gate.withPermit { api.quote(symbol) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「同時進行的請求數最多 20 個」 |
| 修正版 | 「同時進行的請求數最多 4 個」，仍然查了 20 個交易對 |

**為什麼**：`Semaphore(n)` 限制同時進行的數量，其他人排隊等。注意它只限制「同時幾個」，不限制「每秒幾個」；如果 API 限制的是每秒請求數，還需要另外控制速率，例如 token bucket（令牌桶）。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L04_Semaphore.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L04_Semaphore.kt#fixed
```

### 演練 5：limitedParallelism(1) 保護資料庫，卻跨執行緒失敗（第 2 題）

**檔案**：`L05_LimitedParallelism.kt`

**情境**：本機資料庫（像 Realm）有 thread affinity，只能在建立它的那條執行緒使用，換一條就丟例外。`ThreadBoundDb` 模擬這個行為，`scenario()` 在 dispatcher 上建立資料庫、每次 `delay(10)` 之後寫入，共 5 次，已經寫好。共用的執行緒池用有 2 條執行緒的 `pool` 代表（`Dispatchers.Default`、`Dispatchers.IO` 也是這樣的共用池，只是執行緒更多）。

**怎麼寫**

1. **Bug 版**：`val serial = pool.asCoroutineDispatcher().limitedParallelism(1)`，然後 `scenario(log, serial)`。
2. **修正版**：建立真正的單執行緒 dispatcher：`Executors.newSingleThreadExecutor { Thread(it, "db-thread") }.asCoroutineDispatcher()`，`scenario(log, 它)`，最後 `close()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 至少 1 次「跨執行緒失敗」（解答實測：寫入成功 2 次，跨執行緒失敗 3 次） |
| 修正版 | 「寫入成功 5 次，跨執行緒失敗 0 次」 |

**為什麼**：`limitedParallelism(1)` 只保證「同一時間最多一個任務在跑」，也就是互斥，但每次 `delay` 之後恢復執行，可能被池子裡另一條執行緒接手。需要「永遠同一條執行緒」的資源，要用真正的單執行緒 dispatcher，而且用完要 `close()`，否則那條執行緒不會被釋放。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L05_LimitedParallelism.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L05_LimitedParallelism.kt#fixed
```

### 演練 6：按下按鈕後，畫面卡住不動（第 3 題）

**檔案**：`L06_RunBlocking.kt`

**情境**：用一條叫 `fake-main` 的執行緒代表 Android 主執行緒，上面有一個「畫面」每 10 ms 更新一次。使用者按下按鈕，要向伺服器載入訂單（`loadOrders()`，約 600 ms，已經寫好）。演練會量「畫面最長一次沒有更新」是多久。

**怎麼寫**

1. **Bug 版**：按鈕的處理函式在主執行緒上用 `runBlocking { withContext(Dispatchers.IO) { loadOrders() } }` 等資料。
2. **修正版**：不用 `runBlocking`，處理函式直接 `withContext(Dispatchers.IO) { loadOrders() }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「畫面最長一次沒有更新」約 600 ms（解答實測 609–623 ms） |
| 修正版 | 「畫面最長一次沒有更新」只有幾十 ms（解答實測 15–27 ms），「載入完成：50 筆訂單」 |

**為什麼**：`runBlocking` 會卡住呼叫它的那條執行緒，整個等待期間主執行緒不能更新畫面；真的 App 超過約 5 秒就是 ANR。更糟的是 deadlock：如果 `runBlocking` 裡面要 `withContext(Dispatchers.Main)`，主執行緒等 `runBlocking`，`runBlocking` 又等主執行緒，互相等，永遠卡住。`runBlocking` 只該出現在阻塞世界與 suspend 世界的邊界（例如 OkHttp 的 `Authenticator`），而且要加 `withTimeout`。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L06_RunBlocking.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L06_RunBlocking.kt#fixed
```

### 演練 7：10 個請求同時 401，token 刷新了 10 次（第 4 題）

**檔案**：`L07_TokenRefresh.kt`

**情境**：畫面同時送出 10 個請求，access token 剛好過期，10 個都收到 HTTP 401，每個請求都要刷新 token 再重送。`AuthServer`（已經寫好）模擬伺服器：refresh token 是一次性的，用過一次就作廢，換回新的 access token 與新的 refresh token。

**怎麼寫**

1. **Bug 版**：`TokenStore.refreshAfter401()` 不加鎖，直接 `server.refresh(refresh)`，把 `pair.access`、`pair.refresh` 存回去，回傳 `pair.access`。
2. **修正版**：宣告 `Mutex()`，整段放進 `mutex.withLock { }`。進去之後先比對 `access != stale`：不一樣代表前一個等鎖的人已經刷新過，直接回傳 `access`；一樣才呼叫 `server.refresh(refresh)` 並存回去。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「刷新 API 被呼叫 10 次」；「拿到新 token 的請求 1 個，被登出的請求 9 個」 |
| 修正版 | 「刷新 API 被呼叫 1 次」；「拿到新 token 的請求 10 個，被登出的請求 0 個」 |

**為什麼**：這個效果叫 single-flight：很多請求同時需要同一件事時，只真的做一次，其他人等結果、共用。重點是把「比對舊 token」和「刷新」放在同一個鎖裡；比對的是 token 本身，不依賴手機的時鐘，也能處理伺服器提早撤銷 token 的情況。真的 App 裡這段放在 OkHttp 的 `Authenticator`，並且限制重試次數。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L07_TokenRefresh.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L07_TokenRefresh.kt#fixed
```

### 演練 8：測試真的等了 2 秒，虛擬時間沒用（第 5 題）

**檔案**：`L08_RunTest.kt`，以及測試 `labs/android/practice/kotlin/test/Kotlin04RunTest.kt`（已經寫好，不用改；解答的在 `solution/kotlin/test/`）

**情境**：`OrderRepo.load()` 向伺服器載入訂單，用 `withContext` 切到背景，裡面 `delay(2_000)` 代表等伺服器。測試用 `runTest`，希望 `delay` 不要真的等。這題不用開 App：在 Android Studio 點 `Kotlin04RunTest` 裡 `l08()` 旁邊的 ▶，或在終端機執行 `./gradlew :practice-kotlin:testDebugUnitTest --tests '*Kotlin04RunTest*' -i`，看 `[SELFTEST]` 開頭的行。

**怎麼寫**

1. **Bug 版**：`L08Bug.OrderRepo.load()` 寫死 `withContext(Dispatchers.IO) { delay(2_000); "50 筆訂單" }`。
2. **修正版**：`L08Fixed.OrderRepo` 的 `load()` 改用建構子注入的 `io`：`withContext(io) { delay(2_000); "50 筆訂單" }`。測試傳入 `StandardTestDispatcher(testScheduler)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「真實 2010 ms、虛擬 0 ms」：測試真的等了約 2 秒，runTest 的虛擬時鐘沒動 |
| 修正版 | 「真實 0 ms、虛擬 2000 ms」：瞬間完成，虛擬時鐘走了 2000 ms |

**為什麼**：`runTest` 的虛擬時間只對「和測試共用同一個 scheduler 的 dispatcher」有效。程式碼裡寫死 `Dispatchers.IO`，`delay` 就是真實時間。所以 dispatcher 要用建構子注入，測試時換成共用 scheduler 的 `TestDispatcher`。

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L08_RunTest.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin04-coroutine-sync/L08_RunTest.kt#fixed
```

## 常見陷阱

- **以為 `Mutex.withLock` 可重入。** 函式 A（持鎖）呼叫函式 B（也要鎖）會 deadlock（死結）。把「已持鎖」的版本拆成內部函式。
- **把「檢查」和「動作」分在兩次 `withLock`。** 兩次之間會被別人插隊。check-then-act 要放在**同一個** `withLock`。
- **把 `limitedParallelism(1)` 當成單執行緒，用在 Realm 上。** 它只保證互斥，每次可能換不同的執行緒，Realm 會丟出跨執行緒存取的例外。
- **在 `Dispatchers.Main` 上呼叫 `runBlocking`，或在 `synchronized` 區塊裡用 `runBlocking`。** 前者會 ANR 甚至 deadlock，後者讓其他請求全卡在鎖上。
- **401 之後無上限地重試。** 伺服器如果持續回 401，會變成無限遞迴。用 `priorResponse` 限制次數。
- **`runTest` 裡的 `launch` 沒有 `advanceUntilIdle()` 或 `runCurrent()` 就斷言。** 預設的 `StandardTestDispatcher` 只是排隊，永遠看到舊值。
- **程式碼裡寫死 `Dispatchers.IO`，卻期待虛擬時間生效。** `delay` 會真的等，測試變慢。
- **`Dispatchers.setMain` 沒有 `resetMain`。** 污染其他測試。

## 面試官想聽到的

1. 能依場景選工具：互斥用 `Mutex`、限流用 `Semaphore`、有 thread affinity 的資源用專屬單執行緒，並說明 `limitedParallelism(1)` 不等於同一條執行緒。
2. 講得出 `Mutex` 不可重入與 check-then-act 的做法，能現場寫出 single-flight。
3. `runBlocking` 只在邊界使用，並說明主執行緒與 OkHttp 執行緒各自的風險。
4. 測試時知道虛擬時間的適用範圍，以及為什麼要注入 dispatcher。

> [!FIELD]
> 在一個社交 App 的 Android 版裡，把 Realm 的存取包在單一執行緒的 dispatcher 裡，符合 Realm 只能在建立它的執行緒使用的限制；同一個 App 的 iOS 版用 `AsyncSemaphore` 讓 token 刷新與聊天室同步同一時間只做一次，這就是 single-flight 的做法。在一個即時行情 App 的 Android 版裡，Play Billing 購買流程用 `connectionMutex` 保護連線、`purchaseMutex` 讓購買一個接一個，並用 `Channel` 接收購買結果。被問到「怎麼避免並行問題」時，這幾個都是可以直接舉的例子。

## iOS / Android 對照

| 主題 | Android／Kotlin | iOS／Swift |
|---|---|---|
| 互斥 | `Mutex`（不可重入、suspend） | `actor`（隔離存取；但 `await` 前後 actor 的狀態可能已被別的呼叫改變，這叫 reentrancy） |
| 限流 | `Semaphore` | `AsyncSemaphore`（第三方套件）、`TaskGroup` 控制並行數 |
| 序列化佇列 | 單一消費者 `Channel`、`limitedParallelism(1)` | serial `DispatchQueue`、actor |
| single-flight | `Mutex` 加比對舊 token | `AsyncSemaphore(value: 1)`，或在 actor 裡快取進行中的 `Task` |
| 把 suspend 接到阻塞世界 | `runBlocking`（卡住執行緒） | 沒有對等的官方做法；用號誌（semaphore）等待 `Task` 的結果會卡住執行緒，不建議 |
| 虛擬時間測試 | `runTest`、`advanceTimeBy` | 標準函式庫沒有；注入 `Clock`（第三方套件 swift-clocks 提供 `TestClock`）或自己寫可替換的 sleeper |

注意 Swift 的 actor 和 Kotlin 的 `Mutex` 有一個相反的特性：Kotlin 的 `Mutex` 持鎖期間 suspend 不會讓別人進來；Swift 的 actor 在 `await` 時會讓別的呼叫進來（reentrancy），所以「檢查再動作」中間如果有 `await`，一樣要重新檢查。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 互斥鎖 | Mutex | ミューテックス（排他制御） |
| 號誌（限制同時進行的數量） | Semaphore | セマフォ |
| 臨界區段（同一時間只能一個人進去的程式） | critical section | クリティカルセクション |
| 死結 | deadlock | デッドロック |
| 不可重入 | non-reentrant | 再入不可 |
| 執行緒親和性 | thread affinity | スレッドアフィニティ |
| 虛擬時間 | virtual time | 仮想時間 |
| single-flight（同時只真的做一次，其他人共用結果） | single-flight | シングルフライト |
