---
id: livecoding-async
title: 非同步題：限制同時執行的數量、失敗重試、同一份資料只載入一次
summary: 資深行動工程師的 live coding 很常考非同步：同時下載很多東西但最多 N 個、失敗時等一下再重試、好幾個畫面同時要同一份資料時只打一次 API。這個單元說明每一題的思路，以及 Swift 和 Kotlin 各自的寫法。
priority: P0
day: 5
tags: [live-coding, concurrency, async, coroutines]
---

## 重點講解

這個單元回答三個問題：

1. 要同時下載很多東西，怎麼限制最多只有 N 個同時進行？
2. API 失敗時要怎麼重試？哪些錯誤不該重試？
3. 好幾個地方同時要同一份資料，怎麼做到只載入一次？

### 1. 怎麼限制最多只有 N 個同時進行？

情境：行情頁要同時載入 30 個交易對的 K 線。如果一次全部送出，可能超過 API 的限流（交易所的公開 API 常見的限制是每秒 10 次左右），也會讓手機同時開太多連線。所以要限制「同時最多 N 個」，而且回傳的結果要和輸入的順序一樣。

**Swift：用 `withTaskGroup`，先放 N 個，每完成一個再補一個。**

TaskGroup 本身沒有「最多同時幾個」的設定。如果一開始就把 30 個全部 `addTask`，30 個會同時執行，等於沒有限制。正確的做法像一個滑動視窗：

```swift
await withTaskGroup(of: (Int, R).self) { group in
    var results = [R?](repeating: nil, count: items.count)
    var next = 0
    // 1. 先放 limit 個
    for _ in 0..<min(limit, items.count) {
        let index = next
        // 帶著索引，之後才能放回原本的位置
        group.addTask { (index, await transform(items[index])) }
        next += 1
    }
    // 2. 每完成一個，就補下一個進去
    while let (index, value) = await group.next() {
        results[index] = value
        if next < items.count {
            let i = next
            group.addTask { (i, await transform(items[i])) }
            next += 1
        }
    }
    return results.map { $0! }
}
```

TaskGroup 的結果是「誰先完成誰先回來」，不是照輸入的順序，所以每個 task 都要帶著自己的索引回傳，最後依索引放回去。

**Kotlin：用 `Semaphore`。**

`Semaphore(limit)` 像是一個只有 limit 張的通行證。每個 coroutine 要先拿到通行證才能執行，執行完歸還，下一個才能拿。

```kotlin
suspend fun <T, R> mapConcurrently(
    items: List<T>,
    limit: Int,
    transform: suspend (T) -> R,
): List<R> =
    coroutineScope {
        val semaphore = Semaphore(limit)
        items.map { item ->
            // 在 async 裡面拿通行證
            async { semaphore.withPermit { transform(item) } }
        // awaitAll 會照原本的順序回傳
        }.awaitAll()
    }
```

`withPermit` 一定要寫在 `async` **裡面**。如果寫在外面，拿通行證和執行就分開了，限制會失效。

**取消**：這兩種寫法都屬於 structured concurrency（結構化並行：子工作的生命週期被父工作包住）。外面的工作被取消時，裡面所有的下載也會一起取消；Kotlin 的 `coroutineScope` 裡只要有一個失敗，其他的也會被取消。

> [!TIP]
> 面試時可以這樣說：「Swift 我用 TaskGroup 做滑動視窗：先放 limit 個，每完成一個再補一個，每個 task 帶著索引回傳，最後依索引排回原本的順序。Kotlin 用 Semaphore，在 async 裡面 withPermit。兩種都是結構化並行，外層取消時全部一起取消。」

### 2. API 失敗時要怎麼重試？

**結論：用指數退避（exponential backoff）：每次失敗後，等待的時間加倍（100、200、400 毫秒……）。再加上等待上限和隨機的誤差（jitter），而且只重試「重試有機會成功」的錯誤。**

為什麼不能失敗就馬上重試？如果伺服器是因為太忙才失敗，所有 App 都馬上重試，只會讓伺服器更忙。等待時間加倍，給伺服器喘息的時間。

三個實務上要加的細節：

- **等待上限**：例如最多等 30 秒，不然第 10 次會等到幾十秒。
- **jitter（隨機誤差）**：在等待時間上加一點隨機值。如果伺服器剛恢復，所有 App 用完全一樣的時間表重試，會在同一個瞬間一起湧進來。加上隨機誤差，請求就會分散開。
- **把 `sleep` 當成參數傳進來**：測試時傳一個只記錄「被要求等多久」的假 sleep，測試就不用真的等。

**哪些錯誤該重試？**

| 錯誤 | 要重試嗎 | 原因 |
|---|---|---|
| 連線逾時、網路中斷 | 要 | 暫時性問題，等一下可能就好了 |
| HTTP 503（伺服器暫時無法服務） | 要 | 同上 |
| HTTP 429（請求太多） | 要，但依 `Retry-After` 標頭等待 | 伺服器告訴你多久後再來 |
| HTTP 401（token 過期） | 不要原封不動重試 | 要先換新的 token，再重送 |
| HTTP 400、餘額不足 | 不要 | 請求本身有問題，重試結果都一樣 |

**下單要特別小心**：如果下單請求逾時，你不知道伺服器到底有沒有收到。直接重送，可能變成下了兩筆單。所以下單要帶一個 App 產生的 client order id（每筆訂單一個唯一的編號），重送時用同一個編號，伺服器看到重複的編號就知道是同一筆。這種「同一個請求送幾次結果都一樣」的性質叫做冪等（idempotent）。

**Kotlin 的陷阱**：coroutine 被取消時會丟出 `CancellationException`。如果重試的程式寫 `catch (e: Exception)`，會把「取消」也當成錯誤去重試，結果怎麼樣都取消不了。要先把 `CancellationException` 重新丟出去。

> [!TIP]
> 面試時可以這樣說：「我用指數退避，每次失敗等待時間加倍，加上上限和 jitter，避免所有使用者同時重試。只重試逾時、503、429 這類暫時性錯誤；401 要先 refresh token，400 和餘額不足不重試。下單一定帶 client order id，重送時用同一個，避免重複下單。」

### 3. 好幾個地方同時要同一份資料，怎麼只載入一次？

情境：App 一啟動，首頁、行情頁、下單頁都需要「交易對設定」（每個交易對的最小下單量、價格精度）。三個地方幾乎同時呼叫，如果每個都打一次 API，就浪費了兩次。這種「同時的請求合併成一次」的做法叫做 **single-flight**。同樣的問題也出現在 token 過期時的刷新：好幾個 API 同時發現 token 過期，應該只刷新一次。

**結論：記錄「正在進行中的載入」。第一個呼叫開始載入時，先把這個載入工作記下來；之後的呼叫發現已經有人在載入，就等同一個結果。**

**Swift：用 actor，裡面存「key → 正在進行的 Task」。**

actor 保證同一時間只有一段程式碼在存取它的資料。但有一個很重要的特性：**actor 在 `await` 的時候會先讓別人進來**（這叫做 actor reentrancy，可重入）。所以下面這種寫法是錯的：

```swift
actor Config {
    var cached: String?
    func load() async -> String {
        if let cached {
            return cached
        }
        // 等待 API 的期間，其他呼叫會進來
        let value = await api.fetch()
        // 等到這裡才記錄，其他呼叫早就也去打 API 了
        cached = value
        return value
    }
}
```

三個呼叫幾乎同時進來時，第一個在 `await api.fetch()` 暫停，第二個、第三個也進來了，`cached` 還是 nil，所以三個都去打 API。

正確的做法是**在 `await` 之前**就把「進行中的工作」記下來：

```swift
actor SingleFlightCache<Key: Hashable & Sendable, Value: Sendable> {
    private var values: [Key: Value] = [:]
    private var inFlight: [Key: Task<Value, Error>] = [:]

    func value(
        for key: Key,
        load: @escaping @Sendable (Key) async throws -> Value
    ) async throws -> Value {
        // 已經有值
        if let cached = values[key] {
            return cached
        }
        // 有人正在載入：等同一個結果
        if let task = inFlight[key] {
            return try await task.value
        }
        let task = Task { try await load(key) }
        // 在 await 之前就記下來，後面進來的呼叫才看得到
        inFlight[key] = task
        do {
            let loaded = try await task.value
            values[key] = loaded
            inFlight[key] = nil
            return loaded
        } catch {
            // 失敗不要快取，下次呼叫重新載入
            inFlight[key] = nil
            throw error
        }
    }
}
```

**Kotlin：用 `Mutex` 保護一個「key → `CompletableDeferred`」的 Map。** `CompletableDeferred` 是一個「之後才會有結果」的容器：第一個呼叫者建立它、負責載入，載入完呼叫 `complete(value)`；其他呼叫者拿到同一個容器，呼叫 `await()` 等結果。

**失敗不要快取**：如果把失敗的結果也存起來，一次網路錯誤就會讓之後所有的呼叫都拿到同一個錯誤，直到 App 重開。

> [!TIP]
> 面試時可以這樣說：「我用 single-flight：actor 裡記錄每個 key 正在進行的 Task，第二個呼叫進來時直接等同一個 Task。要特別注意 actor 在 await 時會讓其他呼叫進來，所以一定要在 await 之前就把 Task 記下來。失敗不快取，下次重新載入。Token 刷新也是同樣的做法。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Coding04Async**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Coding04Async**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Coding/Coding04-Async/`，解答在 `labs/ios/Solution/Coding/Coding04-Async/`；Android 你寫在 `labs/android/practice/coding/coding04-async/`，解答在 `labs/android/solution/coding/coding04-async/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路**：演練用假的 API（用 `Task.sleep`／`delay` 模擬花的時間）。演練 1 量的是總共花了幾毫秒，用寬鬆的門檻檢查；演練 2 的 `sleep` 由外面傳進來，只記錄「被要求等多久」，不會真的等。
> **和別的單元不重複**：所有請求一口氣全部送出（沒有限制同時數量）的寫法，在 Swift04 演練 2、Kotlin04 演練 4 練過；「檢查快取」與「打 API」之間被插隊，造成重複載入，在 Swift05 演練 3（actor 的 reentrancy）、Kotlin04 演練 2、演練 7（Mutex、token 刷新）練過；下單逾時要帶同一個 client order id、重試要加 jitter，在 Platform05 演練 5、演練 6 練過；Kotlin 重試時吞掉 `CancellationException`，在 Kotlin03 演練 4 練過。這個單元練的是 live coding 現場最常寫成的另外三種樣子。

### 演練 1：限制同時 3 個，卻比預期慢一倍多（第 1 題）

**檔案**：`L01_SlidingWindow.swift`（iOS）、`L01_SlidingWindow.kt`（Android）

**情境**：行情頁要載入 9 個交易對的 K 線，最多同時 3 個。每個請求花的時間不一樣：第 1、4、7 個慢（200 毫秒），其他快（20 毫秒）。回傳的結果要和輸入的順序一樣。

**怎麼寫**

1. **Bug 版**：把 9 個切成 3 批，一批 3 個、一批做完才開始下一批。切批（iOS 的 `while` 迴圈、Android 的 `chunked(limit)`）已經寫好，你要寫「同時跑這一批、等這一批全部完成」：iOS 用 `withTaskGroup`，每個 task 帶著這一批裡的位置 `offset` 回傳，依 `offset` 放回；Android 用 `coroutineScope`，每個 job 一個 `async`，再 `awaitAll()`。
2. **修正版**：iOS 寫滑動視窗：先放 `limit` 個 task（每個帶著索引），然後每完成一個（`group.next()`）就依索引放回 `results`，並補下一個進去。Android 用 `Semaphore(limit)`，每個 job 一個 `async`，在 `async` **裡面**用 `withPermit` 包住請求，最後 `awaitAll()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 同時最多 3 個，結果順序正確，但「總共花了」約 600 毫秒（3 批，每批都等 200 毫秒的慢請求；實測 iOS 約 626、Android 約 627，Debug 與 Release 差不多） |
| 修正版 | 同時最多 3 個，結果順序正確，只花約 260 毫秒（實測 iOS 約 275、Android 約 279） |

**為什麼**：分批執行確實不會超過上限，但每一批都要等最慢的那個，快的請求做完之後空出來的位子沒有人補，白白浪費。滑動視窗（Semaphore）是做完一個就補一個，慢的請求不會拖住別人。TaskGroup 的結果是誰先完成誰先回來，所以每個 task 要帶著索引，最後依索引放回原本的位置；Kotlin 的 `awaitAll()` 會照原本的順序回傳。Android 的 `withPermit` 一定要寫在 `async` 裡面，寫在外面，拿通行證和執行就分開了，限制會失效。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding04-Async/L01_SlidingWindow.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding04-Async/L01_SlidingWindow.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding04-async/L01_SlidingWindow.kt#bug
```

```lab-solution
android/solution/coding/coding04-async/L01_SlidingWindow.kt#fixed
```

### 演練 2：餘額不足也重試 6 次，而且馬上重試（第 2 題）

**檔案**：`L02_Retry.swift`（iOS）、`L02_Retry.kt`（Android）

**情境**：呼叫 API 的 `retry` 函式，最多試 6 次，第一次失敗後等 100 毫秒，之後每次加倍，最多等 1000 毫秒。`sleep` 由外面傳進來，演練的假 `sleep` 只記錄「被要求等多久」。三種情境：A 前 3 次逾時、第 4 次成功；B 餘額不足；C 伺服器一直回 503。「哪些錯誤值得重試」的分類（`isTransient`）已經寫在 Support 裡。

**怎麼寫**

1. **Bug 版**：最多試 `maxAttempts` 次，成功就回傳；任何錯誤都記下來，然後馬上再試（不呼叫 `sleep`）；全部失敗就丟出最後一個錯誤。Android 用 `catch (e: Exception)` 接住所有錯誤。
2. **修正版**：`delay` 從 `baseDelayMs` 開始。失敗時：錯誤不是暫時性的（`isTransient` 是 `false`），或已經是最後一次，就把錯誤丟出去；否則 `sleep(delay)`，`delay` 加倍但不超過 `maxDelayMs`，再試。Android 只接 `C04ApiException`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | A 呼叫 4 次、B（餘額不足）呼叫 6 次、C 呼叫 6 次，三個情境的等待都是 []，一毫秒也沒等 |
| 修正版 | A 等待 [100, 200, 400]；B 呼叫 1 次就失敗；C 呼叫 6 次，等待 [100, 200, 400, 800, 1000]（封頂在 1000） |

**為什麼**：餘額不足、請求有問題（400）這類錯誤，重試結果都一樣，只是白白多送幾次請求；伺服器忙碌（503）時馬上重試，所有手機一起湧入，只會讓伺服器更忙。指數退避讓等待時間加倍，上限避免第 10 次等到幾十秒，次數上限避免無限重試。真實的 App 還要加上隨機的 jitter（見 Platform05 演練 6），下單逾時也不能直接重送，要帶同一個 client order id（見 Platform05 演練 5）。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding04-Async/L02_Retry.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding04-Async/L02_Retry.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding04-async/L02_Retry.kt#bug
```

```lab-solution
android/solution/coding/coding04-async/L02_Retry.kt#fixed
```

### 演練 3：API 故障一次，之後永遠拿到同一個錯誤（第 3 題）

**檔案**：`L03_SingleFlight.swift`（iOS）、`L03_SingleFlight.kt`（Android）

**情境**：交易對設定用 single-flight 快取（iOS 是 actor 裡記錄每個 key 正在進行的 `Task`；Android 是 `Mutex` 保護的 `HashMap`，裡面記錄每個 key 的 `CompletableDeferred`），好幾個畫面同時要同一份就共用同一個請求。演練分三輪：① BTC 同時要 3 次、ETH 同時要 2 次，API 正常；② SOL 同時要 2 次，這一次 API 剛好故障（逾時）；③ API 恢復後，SOL 再要一次。查快取、查進行中的工作、登記的部分已經寫好。

**怎麼寫**

1. **Bug 版**：登記好進行中的工作之後，你要寫「等結果」那幾行：iOS `let loaded = try await task.value`，Android 自己呼叫 `api.fetch(key)`；成功後存進 `values`、把 `inFlight` 的登記清掉、回傳。失敗時不特別處理，錯誤直接丟出去（Android 要 `completeExceptionally` 讓等的人也拿到錯誤），失敗的登記就留在 `inFlight` 裡。
2. **修正版**：同樣的步驟，但失敗時也要把 `inFlight[key]` 清掉再丟出錯誤（iOS 用 `do/catch`，Android 在 `catch` 裡 `inFlight.remove(key)`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ①「5 個拿到設定，API 呼叫 BTC 1 次、ETH 1 次」，②「2 個失敗，API 呼叫 1 次」，③「還是失敗，API 累計 1 次」：API 恢復了，卻根本沒有再呼叫 |
| 修正版 | ①② 和 Bug 版一樣；③「成功（SOL 的設定），API 累計 2 次」 |

**為什麼**：single-flight 做對的部分（同時的請求合併成一次）在 Bug 版也成立，所以第 ① 輪看不出問題。問題出在失敗之後：失敗的工作還留在 `inFlight`，之後每次呼叫都「等到同一個已經失敗的工作」，一次網路錯誤就讓這份資料壞到 App 重開為止。所以失敗不要快取，下一次呼叫要重新載入。Token 過期時的刷新也是同樣的做法。（沒有在 `await` 之前登記進行中的工作，大家各打一次 API，是 actor 的 reentrancy，見 Swift05 演練 3；Android 見 Kotlin04 演練 2、演練 7。）

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding04-Async/L03_SingleFlight.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding04-Async/L03_SingleFlight.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding04-async/L03_SingleFlight.kt#bug
```

```lab-solution
android/solution/coding/coding04-async/L03_SingleFlight.kt#fixed
```

## 常見陷阱

- **TaskGroup 一開始就把全部 `addTask`。** 全部同時執行，等於沒有限制數量。
- **Kotlin 把 `withPermit` 寫在 `async` 外面。** 限制會失效。另外，不要用 `GlobalScope.async`，它不受外層取消的控制。
- **actor 裡先 `await` 載入，之後才記錄進行中的工作。** 同時進來的呼叫會全部各自載入一次。
- **Kotlin 重試時把 `CancellationException` 也當成錯誤。** 取消不了。要先重新丟出 `CancellationException`。
- **下單逾時就直接重送，沒有帶同一個 client order id。** 可能變成兩筆訂單。

## 面試官想聽到的

1. 結構化並行：外層取消時，裡面的工作會一起取消。
2. 知道 actor 在 await 時會讓別人進來，並說得出怎麼避免重複載入。
3. 重試的細節：等待上限、jitter、哪些錯誤該重試、下單要冪等。
4. 怎麼測試：把 sleep 和時間注入進來；用計數器記錄「同時最多幾個在執行」來驗證限制有效。

## iOS / Android 對照

| 主題 | iOS（Swift Concurrency） | Android（Kotlin Coroutines） |
|---|---|---|
| 限制同時執行的數量 | `withTaskGroup`，手動控制同時加入幾個 | `Semaphore(N).withPermit { }` |
| 保持原本的順序 | 每個 task 帶索引回傳，最後排回去 | `awaitAll()` 照原本的順序回傳 |
| 保護共用的資料 | `actor`（注意 await 時會讓別人進來） | `Mutex.withLock { }` |
| 「之後才有結果」的容器 | `Task<Value, Error>` | `CompletableDeferred<V>` |
| 取消 | 父 Task 取消，group 裡全部取消 | `coroutineScope` 裡一個失敗，其他全部取消 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 結構化並行 | structured concurrency | 構造化並行性 |
| 指數退避 | exponential backoff | 指数バックオフ |
| 隨機誤差 | jitter | ジッター |
| 冪等 | idempotent | 冪等（べきとう） |
| 限流 | rate limit | レート制限 |
| 可重入 | reentrancy | リエントランシー（再入可能性） |
