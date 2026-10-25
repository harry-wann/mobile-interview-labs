---
id: testing-android
title: Android 測試工具：runTest、Turbine、MockK、Compose UI 測試、Robolectric
summary: Android 測試的關鍵在 coroutines 和 Flow：怎麼讓 `delay` 不用真的等、怎麼控制程式在哪個執行緒跑、怎麼一個一個檢查 Flow 發出的值。畫面則用 Compose 的 UI 測試和截圖測試。這個單元說明每個工具解決什麼問題。
priority: P1
day: 5
tags: [testing, android, mockk, turbine, compose-test]
---

## 重點講解

這個單元回答五個問題：

1. 有 `delay` 的 coroutine，測試時怎麼不用真的等？
2. ViewModel 用了 `Dispatchers.IO` 或 `viewModelScope`，測試時要怎麼處理？
3. Flow 發出的一連串值，要怎麼檢查？
4. 假的依賴要用 mock 還是自己寫 fake？
5. 畫面要怎麼測？

### 1. 有 `delay` 的 coroutine，測試時怎麼不用真的等？

**結論：用 `kotlinx-coroutines-test` 的 `runTest`。它使用虛擬時間：`delay(5_000)` 不會真的等 5 秒，而是把測試裡的時鐘直接往前撥。**

```kotlin
@Test
fun retryWaitsBeforeSecondAttempt() = runTest {
    var attempts = 0
    val job = launch {
        attempts++
        delay(5_000)          // 虛擬時間：不會真的等 5 秒
        attempts++
    }
    runCurrent()              // 執行目前已經可以執行的工作：第一次 attempts++
    assertEquals(1, attempts)
    advanceTimeBy(5_001)      // 把虛擬時鐘往前撥 5 秒多一點
    assertEquals(2, attempts)
    job.join()
}
```

手動推進時間的三個函式：

- `runCurrent()`：執行「現在這個時間點」已經排好的工作。
- `advanceTimeBy(ms)`：把時鐘往前撥指定的毫秒數，並執行這段時間內到期的工作。
- `advanceUntilIdle()`：一直往前撥，直到沒有任何待執行的工作。

兩種測試用的 dispatcher（dispatcher 決定 coroutine 在哪裡、什麼時候執行）：

- `StandardTestDispatcher`：新啟動的 coroutine 會先排隊，要呼叫上面的函式推進時間才會執行。適合想精確控制順序的測試。
- `UnconfinedTestDispatcher`：新啟動的 coroutine 立刻開始執行。適合「一直收集 StateFlow」這種只想看結果的測試。

> [!TIP]
> 面試時可以這樣說：「coroutine 的測試我用 runTest，它用虛擬時間，delay 不會真的等，我可以用 advanceTimeBy 精確控制時間，測重試間隔或 debounce 這類邏輯。」

### 2. ViewModel 用了 `Dispatchers.IO` 或 `viewModelScope`，測試時要怎麼處理？

**結論：dispatcher 不要寫死在程式裡，從建構子傳進來，測試時換成測試用的 dispatcher。`viewModelScope` 用的是主執行緒的 dispatcher，測試前要用 `Dispatchers.setMain` 換成同一個測試用 dispatcher。**

```kotlin
// 不好測：寫死 Dispatchers.IO，測試無法控制
class PriceRepository {
    suspend fun load() = withContext(Dispatchers.IO) { api.fetch() }
}

// 好測：從外面傳進來
class PriceRepository(private val io: CoroutineDispatcher) {
    suspend fun load() = withContext(io) { api.fetch() }
}
```

用 Hilt 的專案，常見做法是用 qualifier（例如自訂的 `@IoDispatcher` 註解）提供 dispatcher，測試時再換掉。

`viewModelScope` 預設在 `Dispatchers.Main`（Android 的主執行緒）上執行。單元測試跑在一般的 JVM 上，沒有 Android 的主執行緒。舊版 lifecycle 會直接丟 `IllegalStateException`；lifecycle 2.8 起（這個專案用 2.9.4 實測），`viewModelScope` 找不到主執行緒時改用預設的背景執行緒，不會丟例外，但 coroutine 跑在測試控制不到的執行緒上，結果時好時壞，比丟例外更難發現。所以測試開始前呼叫 `Dispatchers.setMain(testDispatcher)`，結束後呼叫 `Dispatchers.resetMain()`。通常會寫成一個 JUnit rule，每個測試類別共用。

> [!TIP]
> 面試時可以這樣說：「dispatcher 我不會寫死在程式裡，而是從建構子傳進來，測試時換成測試用的 dispatcher。`viewModelScope` 用的是主執行緒的 dispatcher，單元測試沒有 Android 的主執行緒；新版 lifecycle 這時不會丟例外，而是悄悄改在背景執行緒跑，測試會時好時壞。所以測試前要呼叫 `Dispatchers.setMain`，結束後呼叫 `resetMain`，通常包成一個 JUnit rule 共用。」

### 3. Flow 發出的一連串值，要怎麼檢查？

**結論：用 Turbine 這個函式庫，一個值一個值地取出來檢查。沒取完的值會讓測試失敗，所以不會漏掉意料之外的輸出。**

```kotlin
@Test
fun emitsLoadingThenData() = runTest {
    viewModel.state.test {                       // Turbine 提供的 test { }
        assertEquals(UiState.Loading, awaitItem())
        assertEquals(UiState.Data(price = 102), awaitItem())
        cancelAndIgnoreRemainingEvents()        // StateFlow 不會自己結束，檢查完要取消
    }
}
```

測 StateFlow 要注意：StateFlow 只保留最新的值。如果值更新得很快，收集的一端可能只看到最後一個，中間的值被跳過了。測試要依這個特性設計，例如只檢查最後的狀態，而不是期待看到每一個中間值。

在 `runTest` 裡收集一個永遠不會結束的 Flow（例如 StateFlow），可以放在 `backgroundScope` 裡啟動，測試結束時會自動取消，不會讓測試一直卡著。

> [!TIP]
> 面試時可以這樣說：「Flow 我用 Turbine 一個值一個值檢查，沒處理到的值會讓測試失敗。StateFlow 只保留最新值，所以快速更新時我只檢查最後的狀態。」

### 4. 假的依賴要用 mock 還是自己寫 fake？

- **mock**（用 MockK）：用函式庫動態產生一個假物件，設定「呼叫這個方法時回傳什麼」，還能檢查「這個方法有沒有被呼叫」。suspend 函式要用 `coEvery`、`coVerify`。
  ```kotlin
  val repo = mockk<PriceRepository>()
  coEvery { repo.load() } returns Price(102)
  ```
  `mockk(relaxed = true)` 會讓沒設定的方法也回傳預設值，寫起來方便，但也可能把「忘了設定」的錯誤藏起來。
- **fake**：自己寫一個簡單的實作，例如用一個 List 假裝是資料庫。

**結論：能用 fake 就用 fake。** fake 的行為比較接近真實的實作，重構時也比較不會壞；mock 常常綁死「呼叫了哪些方法、照什麼順序」，程式內部一改，測試就要跟著改。mock 適合用在「真的需要確認某個呼叫有發生」的地方，例如確認有呼叫分析事件。

> [!TIP]
> 面試時可以這樣說：「假的依賴我會優先寫 fake，因為它的行為比較接近真實的實作，重構時也比較不會壞。mock 會綁死呼叫了哪些方法、照什麼順序，程式內部一改，測試就要跟著改，所以我只在真的需要確認某個呼叫有發生的地方用，例如確認有送出分析事件。」

### 5. 畫面要怎麼測？

| 工具 | 用途 |
|---|---|
| Compose UI 測試 | 用 `createComposeRule()` 建立測試環境，用 `Modifier.testTag("buyButton")` 標記元件，再用 `onNodeWithTag("buyButton").performClick()` 點擊、`assertTextEquals(...)` 檢查文字。`mainClock.autoAdvance = false` 可以手動控制動畫和時間 |
| Espresso | 傳統 View 系統（XML layout）的畫面測試；用 `IdlingResource` 告訴 Espresso「還在載入，先等一下」 |
| Robolectric | 在一般的 JVM 上模擬 Android 系統來跑測試，不需要開模擬器，快很多；但畢竟是模擬，不能完全取代實機 |
| 截圖測試 | Roborazzi（搭配 Robolectric）、Paparazzi（不需要模擬器）：把畫面畫成圖片，和存好的正確圖片比對 |
| Hilt 測試 | 測試類別加 `@HiltAndroidTest`，搭配自訂的 test runner；用 `@TestInstallIn` 把正式的 module 換成測試用的 |

`testTag` 和 iOS 的 `accessibilityIdentifier` 是同樣的概念：用固定的名稱找元件，不受畫面文字和語言影響。

> [!TIP]
> 面試時可以這樣說：「Compose 的畫面測試，我用 `createComposeRule` 搭配 `testTag` 找元件；傳統 View 用 Espresso。Robolectric 在一般的 JVM 上跑，不用開模擬器，快很多，但不能完全取代實機。截圖測試可以用 Roborazzi 或 Paparazzi。`testTag` 和 iOS 的 `accessibilityIdentifier` 是同樣的概念。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Testing03AndroidTesting**。這個單元只有 Android；iOS 的對應在 Testing02。5 個演練都不用開 App，直接在電腦上跑單元測試（在 JVM，也就是 Java 虛擬機器上執行，不用模擬器）。
> **檔案**：你寫在 `labs/android/practice/testing/`，解答在 `labs/android/solution/testing/`（檔名一樣，可以左右對照）。每個演練有兩個地方：`testing03-android-testing/` 底下的 `L0x_….kt`（被測的程式，加上演練說明）和 `test/` 底下的 `Testing03L0xTest.kt`（測試）。演練 1、3、4、5 要寫的是測試（TODO 在 `test/`），演練 2 要寫的是被測的 ViewModel（TODO 在 `L02_ViewModelDispatcher.kt`）。
> **練法**：照 TODO 寫完 Bug 版 → 在 Android Studio 點測試旁邊的 ▶，或在終端機執行下面的指令 → 看 `[SELFTEST]` 開頭的行：Bug 版寫對了會印 PASS（代表 bug 真的重現了）→ 寫修正版 → 再執行 → 打開下面的解答對照。練習版還沒寫完時會印 FAIL 並說出還差什麼，這是正常的，測試本身不會失敗。
> **指令**：`cd labs/android && ./gradlew :practice-testing:testDebugUnitTest --tests '*Testing03L01Test*' -i | grep SELFTEST`（解答把 `practice` 換成 `solution`；`L01` 換成要跑的演練）。
> **這一區練的是「測試本身」**：用真的 `runTest`、Turbine、MockK、Compose UI 測試與 Robolectric。多數演練把同一個測試對「正確的實作」和「故意改壞的實作」各跑一次，Bug 版是「改壞了測試卻還是過」的假綠燈，修正版是「改壞了測試會失敗」。第 5 題的 Robolectric 第一次執行會下載 Android 的模擬環境，需要網路，會比較久。

### 演練 1：測試真的等了 3 秒，看不到中間狀態（第 1 題）

**檔案**：`L01_VirtualTime.kt`（被測的程式）、`test/Testing03L01Test.kt`（你寫的測試）

**情境**：`t03Retry` 在遇到 `IOException` 時，等 1 秒再試、再失敗就等 2 秒再試（每次加倍），最多試 3 次。假的伺服器 `T03FlakyApi` 前兩次呼叫逾時、第三次成功，所以整個流程要「等」3 秒。

**怎麼寫**

1. **Bug 版**：`L01Bug.run()` 用 `runBlocking { price = t03Retry { api.fetchPrice() } }` 取得價格，並用 `System.nanoTime()` 量真實花了幾 ms。
2. **修正版**：`L01Fixed.run()` 用 `runTest`：`async { t03Retry { api.fetchPrice() } }` 啟動；依序 `runCurrent()`、`advanceTimeBy(1_000)`、再 `runCurrent()`、`advanceTimeBy(2_000)` 加 `runCurrent()`，每一步記下 `api.calls`；最後 `await()` 取得價格，並記下 `currentTime`（虛擬時鐘走了幾 ms）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「真實花了 3044 ms」左右：`runBlocking` 裡的 `delay` 是真實時間 |
| 修正版 | 「真實 0 ms、虛擬 3000 ms、呼叫次數 [1, 1, 2, 3]」 |

**為什麼**：`runTest` 把 `delay` 變成虛擬時間：時鐘不是真的走，而是被測試往前撥，3 秒的流程瞬間跑完，而且可以在每個時間點停下來檢查。注意呼叫次數是 1、1、2、3，不是 1、2、3、…：`advanceTimeBy(1_000)` 不會執行「剛好排在 1,000 這個時間點」的工作，所以呼叫次數還是 1；要再 `runCurrent()` 才會跑到第 2 次。這是用 `advanceTimeBy` 時最常見的 off-by-one（差一）。用 `runBlocking` 測有 `delay` 的程式，測試會真的等，不但慢，也沒辦法檢查中間的狀態。

**解答**

```lab-solution
android/solution/testing/test/Testing03L01Test.kt#bug
```

```lab-solution
android/solution/testing/test/Testing03L01Test.kt#fixed
```

### 演練 2：ViewModel 測試要 sleep 等結果，時過時不過（第 2 題）

**檔案**：`L02_ViewModelDispatcher.kt`（你寫的 ViewModel）、`test/Testing03L02Test.kt`（測試，已經寫好）

**情境**：`PriceViewModel.load()` 用 `viewModelScope` 啟動，切到背景載入價格，載入完把價格存進 `price`。假的伺服器 `T03LatencyApi` 每次要等一段時間（演練用 6 種：30、150、50、130、70、170 ms）。測試已經寫好：Bug 版的測試呼叫 `vm.load()` 之後 `Thread.sleep(100)` 再檢查；修正版的測試用 `StandardTestDispatcher`，`Dispatchers.setMain` 換掉主執行緒的 dispatcher，再 `advanceUntilIdle()`。

**怎麼寫**

1. **Bug 版**：`load()` 裡 `viewModelScope.launch { price.value = withContext(Dispatchers.IO) { api.fetchPrice() } }`，寫死 `Dispatchers.IO`。
2. **修正版**：`PriceViewModel` 的建構子多一個 `io: CoroutineDispatcher`，`load()` 的 `withContext` 改用注入的 `io`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「6 種延遲 3 種通過」：伺服器比 100 ms 快的 3 種（30、50、70 ms）通過，其他 3 種失敗，整輪真實花了約 600 ms |
| 修正版 | 「6 種延遲全部通過」，真實只花幾 ms |

**為什麼**：`Dispatchers.IO` 寫死之後，測試沒辦法控制時間，只好 `Thread.sleep` 等一個「應該夠久」的時間：太短會失敗，太長又慢，而且 CI 機器一忙就變成時過時不過。把 dispatcher 從建構子傳進來，測試換成 `StandardTestDispatcher`，時間就由測試控制。另外要設定 `Dispatchers.setMain`：這個專案用的 lifecycle 2.9.4，沒有主執行緒時 `viewModelScope` 不會丟例外，而是悄悄改在背景執行緒跑，`withContext` 回來之後也在背景執行緒更新狀態，測試沒辦法控制，所以測試開始前要 `Dispatchers.setMain(testDispatcher)`，結束後 `Dispatchers.resetMain()`（通常包成一個 JUnit rule）。直接存取 `Dispatchers.Main` 而沒有設定時，才會丟 `IllegalStateException`。

**解答**（被測的 ViewModel）

```lab-solution
android/solution/testing/testing03-android-testing/L02_ViewModelDispatcher.kt#bug
```

```lab-solution
android/solution/testing/testing03-android-testing/L02_ViewModelDispatcher.kt#fixed
```

### 演練 3：資料流漏發資料，測試卻顯示通過（第 3 題）

**檔案**：`L03_FlowFalseGreen.kt`（被測的程式）、`test/Testing03L03Test.kt`（你寫的測試）

**情境**：`T03PriceStream` 的行情資料流應該「先發 `Loading`，再發 `Data(15_000_000)`」。演練把同一個測試，分別對三個實作各跑一次：正確的、漏發 `Data` 的（`forgetsData`，例如 API 失敗時沒有處理，直接結束）、價格算錯（`Data(0)`）的。

**怎麼寫**

1. **Bug 版**：`L03Bug.test(stream)` 用 `runTest`：`launch { stream.collect { state -> 遇到 T03UiState.Data 才 assertEquals(15_000_000, state.price) } }`，最後 `job.join()`。
2. **修正版**：`L03Fixed.test(stream)` 用 Turbine：`stream.test { … }`，裡面依序 `assertEquals(T03UiState.Loading, awaitItem())`、`assertEquals(T03UiState.Data(15_000_000), awaitItem())`、`awaitComplete()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「正確的實作通過；價格算錯的實作失敗（抓到）；漏發 Data 的實作也通過（沒抓到）」 |
| 修正版 | 「正確的實作通過；漏發 Data 與價格算錯的實作都失敗（抓到）」 |

**為什麼**：把斷言寫在 `collect { }` 裡，只有「資料流真的發出值」的時候斷言才會執行。資料流少發了一筆，斷言一次都沒有執行，測試就通過了：測試沒有說「一定要收到 `Data`」。Turbine 的 `awaitItem()` 相反：它要求「這裡一定要有一個值」，沒有就失敗，漏發時第二個 `awaitItem()` 遇到的是資料流結束，測試立刻失敗；沒處理完的事件也會讓測試失敗，所以不會漏掉意料之外的輸出。

**解答**

```lab-solution
android/solution/testing/test/Testing03L03Test.kt#bug
```

```lab-solution
android/solution/testing/test/Testing03L03Test.kt#fixed
```

### 演練 4：訂單數量錯了十倍，測試卻顯示通過（第 4 題）

**檔案**：`L04_MockVerifyOnly.kt`（被測的程式）、`test/Testing03L04Test.kt`（你寫的測試）

**情境**：`PlaceOrder.place()` 把使用者的價格、數量、買賣方向包成 `OrderRequest`，交給 `OrderApi.submit()` 送出。演練把同一個測試，分別對三個實作各跑一次：正確的、數量多乘了 10 的、根本沒有送出的。

**怎麼寫**

1. **Bug 版**：`L04Bug.test(newPlacer)` 用 MockK：`val api = mockk<T03OrderApi>(relaxed = true)`，`place(15_000_000, 2, "BUY")` 之後，只用 `coVerify { api.submit(any()) }` 確認「有呼叫」。
2. **修正版**：用 `T03FakeOrderApi`（已經寫好，會把收到的請求記在 `requests`）：`place(…)` 之後 `assertEquals(listOf(T03OrderRequest(15_000_000, 2, "BUY")), api.requests)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「正確的實作通過；根本沒送出的實作失敗（抓到）；數量多乘 10 的實作也通過（沒抓到）」 |
| 修正版 | 「正確的實作通過；數量多乘 10 與根本沒送出的實作都失敗（抓到）」 |

**為什麼**：只驗證「mock 有被呼叫」，只能保證流程有走到，不能保證送出的內容是對的；`relaxed = true` 還會讓沒設定的方法也回傳預設值，連「忘了設定」的錯誤都藏起來。fake 把送出的內容記下來，測試直接檢查結果；它的行為比較接近真實的實作，程式內部重構也不容易讓測試壞掉。mock 留給「真的只需要確認某個呼叫有發生」的地方，例如確認有送出分析事件。

**解答**

```lab-solution
android/solution/testing/test/Testing03L04Test.kt#bug
```

```lab-solution
android/solution/testing/test/Testing03L04Test.kt#fixed
```

### 演練 5：表單一直顯示錯誤，UI 測試卻通過（第 5 題）

**檔案**：`L05_ComposeNegativeAssert.kt`（被測的畫面）、`test/Testing03L05Test.kt`（你寫的測試）

**情境**：下單表單在「數量低於 10,000 satoshi」時，要用 `testTag` 是 `"order.error"` 的文字顯示「數量低於最小下單量」。測試要驗證「數量足夠時，不會顯示錯誤訊息」。演練用 Robolectric 在電腦上跑 Compose UI 測試，把同一個測試分別對三個實作各跑一次：正確的、一直顯示錯誤訊息的、從來不顯示錯誤訊息的。

**怎麼寫**

1. **Bug 版**：`L05Bug.test(form)` 用 `runComposeUiTest`：`setContent { form(50_000) }`，再 `onNodeWithTag("order.errorLabel").assertDoesNotExist()`。標籤故意寫錯，實際是 `"order.error"`。
2. **修正版**：`L05Fixed.test(form)` 寫兩個 `runComposeUiTest`：數量 50,000 時 `onNodeWithTag("order.error").assertDoesNotExist()`；數量 9,999 時 `onNodeWithTag("order.error").assertTextEquals("數量低於最小下單量")`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「正確的實作通過；一直顯示錯誤的實作也通過（沒抓到）」 |
| 修正版 | 「正確的實作通過；一直顯示與從來不顯示錯誤的實作都失敗（抓到）」 |

**為什麼**：「找不到」類的檢查（`assertDoesNotExist()`）有一個陷阱：標籤名字打錯、或改版後標籤被改名，這個元件永遠找不到，檢查就永遠通過。解法是搭配一個「找得到」的檢查（positive control）：錯誤情況下，同一個標籤要找得到、文字也要對。這樣標籤寫錯就會在第二個檢查失敗；一直顯示或從來不顯示錯誤訊息的實作，也各有一個檢查失敗。`testTag` 和 iOS 的 `accessibilityIdentifier` 是同樣的概念：用固定的名稱找元件，不受畫面文字和語言影響。

**解答**

```lab-solution
android/solution/testing/test/Testing03L05Test.kt#bug
```

```lab-solution
android/solution/testing/test/Testing03L05Test.kt#fixed
```

這個單元的每一題都有演練。Hilt 測試（`@HiltAndroidTest`）、Roborazzi 與 Paparazzi 的截圖測試需要另外加套件，這個專案沒有加，沒有做成演練；截圖比對誤報的概念，在 iOS 的 Testing02 演練 4 做過。

## 常見陷阱

- **ViewModel 裡寫死 `Dispatchers.IO`。** 測試沒辦法控制，最後只好用 `Thread.sleep` 等，又慢又不穩定。dispatcher 要從外面傳進來。
- **忘了 `Dispatchers.setMain`。** 舊版 lifecycle 的 `viewModelScope` 會丟例外；2.8 起不會丟，而是悄悄改在背景執行緒跑，測試變成時好時壞。直接用 `Dispatchers.Main` 的程式仍然會丟 `IllegalStateException`。
- **用 `runBlocking` 測有 `delay` 的程式。** 測試會真的等。要用 `runTest`。
- **在 `runTest` 裡收集 StateFlow 卻沒有取消。** StateFlow 不會自己結束，測試一直卡著。用 `backgroundScope` 或 Turbine 的 `cancelAndIgnoreRemainingEvents()`。

## 面試官想聽到的

1. 虛擬時間（runTest）和 dispatcher 從外面傳入。
2. 用 Turbine 測 Flow 的習慣，並知道 StateFlow 只保留最新值的特性。
3. fake 和 mock 的取捨。
4. 知道 Robolectric 和截圖測試各自的定位。

> [!FIELD]
> 在一個社交 App 的 Android 版，用的是 Coroutines／Flow 和 Hilt，測試也是這套組合：`runTest`、Turbine、MockK、Robolectric，搭配 Hilt 的測試 runner。用 AI 協助撰寫單元測試提升覆蓋率，這個做法在 Android 也一樣適用。
> 面試時可以這樣說：「Android 的測試我用 runTest 控制虛擬時間，dispatcher 都用 Hilt 注入，測試時換掉；Flow 用 Turbine 檢查。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 控制時間 | 注入 `Clock`，測試時手動推進 | `runTest` 的虛擬時間（`advanceTimeBy`） |
| 控制執行的地方 | 測試標 `@MainActor`；actor 和 Task 的設計 | 注入 dispatcher、`Dispatchers.setMain` |
| 檢查一連串的值 | 把 publisher 轉成 `values` 用 `for await`；或 `AsyncStream` | Turbine 的 `test { awaitItem() }` |
| 假的依賴 | 手寫 fake、protocol 注入 | MockK、手寫 fake |
| 畫面測試 | XCUITest（`accessibilityIdentifier`） | Compose UI 測試（`testTag`）、Espresso |
| 截圖比對 | swift-snapshot-testing | Roborazzi、Paparazzi |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 虛擬時間 | virtual time | 仮想時間 |
| 測試替身（假物件） | mock／fake／stub | モック／フェイク／スタブ |
| 截圖比對測試 | screenshot test | スクリーンショットテスト |
| 決定 coroutine 在哪裡執行的元件 | dispatcher | ディスパッチャー |
| 依賴注入 | dependency injection | 依存性注入 |
