---
id: livecoding-streams
title: 時間與串流題：debounce、最近 N 秒的最高價、移動平均
summary: 行情 App 的題目常和「時間」有關：輸入停下來才送出（debounce）、最近一分鐘的最高價、移動平均線。這個單元說明為什麼要把時間當成參數傳進去，以及每一題的解法思路。
priority: P0
day: 5
tags: [live-coding, streams, time]
---

## 重點講解

這個單元回答五個問題：

1. 和時間有關的題目，為什麼要把時間當成參數傳進去？
2. debounce、throttle、sample 差在哪？行情畫面要用哪一個？
3. debounce 怎麼寫成不需要真的等待、就能測試的函式？
4. 「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？
5. 移動平均怎麼做到每加一筆都很快？

### 1. 為什麼要把時間當成參數傳進去？

**結論：函式裡直接呼叫 `Date()` 或 `System.currentTimeMillis()`，結果會隨著執行的時間點改變，測試就沒辦法穩定重現。把時間當成輸入，同樣的輸入永遠得到同樣的輸出，測試不用真的等待。**

舉例：「每 300 毫秒最多觸發一次」這種邏輯，如果在函式裡讀現在時間，測試時就得真的 `sleep` 300 毫秒，測試又慢又不穩定（CI 機器一忙，時間就不準）。

兩種常見的做法：

- **事件自己帶時間**：輸入是 `(time, value)` 的陣列，函式只根據這些時間計算。下面的練習題都是這種寫法。
- **把「取得現在時間」注入進來**：例如傳入 `now: () -> Int`，或使用 Swift 的 `Clock` protocol。正式環境傳真正的時鐘，測試時傳一個可以手動快轉的假時鐘。Kotlin 的 `runTest` 也提供虛擬時間，`delay(1000)` 不會真的等 1 秒。

面試官出這類題目，很多時候就是想看你會不會主動這樣設計。

> [!TIP]
> 面試時可以這樣說：「我會把時間當成輸入，而不是在函式裡讀現在時間。這樣函式的結果只取決於輸入，測試可以用固定的時間資料，不需要真的等待，也不會因為機器忙碌而失敗。正式程式碼裡，我會注入 Clock，測試時換成可以快轉的假時鐘。」

### 2. debounce、throttle、sample 差在哪？行情畫面用哪一個？

三個都是「控制事件送出的頻率」，但留下的值不同：

| 名稱 | 行為 | 典型用途 |
|---|---|---|
| debounce | 輸入**停下來**一段時間後，才送出最後一個值。只要一直有新的輸入，就一直延後 | 搜尋框：使用者停止打字 300 毫秒後才查詢 |
| throttle（送第一個） | 第一個值立刻送出，接下來一段時間內的值全部忽略 | 防止按鈕被連點 |
| sample（每段時間送最新的） | 每隔固定時間，送出這段時間內**最新**的值 | **行情畫面**：最多每 200 毫秒更新一次，而且永遠顯示最新價格 |

> [!WARNING]
> 行情不能用 debounce。價格每秒跳很多次，「輸入停下來」的時刻可能一直不會到，debounce 會一直延後，畫面就完全不更新。行情要用 sample：Combine 有 `throttle(for:scheduler:latest: true)`，Kotlin Flow 有 `sample(200)`，或用 `conflate()`（處理不過來時，只保留最新的一筆）。

> [!TIP]
> 面試時可以這樣說：「debounce 是等輸入停下來才送，適合搜尋框；行情一直在變，用 debounce 會一直延後，所以我會用 sample，每 200 毫秒取一次最新價格更新畫面。」

### 3. debounce 怎麼寫成不需要等待就能測試的函式？

**結論：把「每個事件會不會被下一個事件取消」改成用時間差來判斷。看下一個事件距離這一個多久：小於 `wait` 就被取消，否則在 `time + wait` 送出。**

規則（練習題的版本）：

- 事件在時間 `t` 發生。如果下一個事件在 `t + wait` **之前**（嚴格小於）就到了，這個事件被取消。
- 否則，它在 `t + wait` 這個時間點被送出。
- 最後一個事件後面沒有人取消它，所以一定會送出。

例子：`wait = 100`，事件發生在 0、50、300 毫秒。

- 0 的下一個是 50，相差 50 < 100，被取消。
- 50 的下一個是 300，相差 250 ≥ 100，在 50 + 100 = 150 送出。
- 300 是最後一個，在 400 送出。

```swift
for (index, event) in events.enumerated() {
    let isLast = index == events.count - 1
    // 是最後一個，或下一個事件和這個相差至少 wait，就會被送出
    if isLast || events[index + 1].time - event.time >= wait {
        result.append(Event(time: event.time + wait, value: event.value))
    }
}
```

每個事件只看一次，O(n)。開始寫之前，要和面試官確認邊界：**下一個事件剛好在 `t + wait` 到達，算不算取消？** 這會決定要寫 `>=` 還是 `>`。

> [!TIP]
> 面試時可以這樣說：「我不會真的等待，而是用時間差判斷：下一個事件距離這一個小於 wait，這一個就被取消，否則在時間加 wait 的時間點送出，最後一個一定會送出。每個事件只看一次，是 O(n)。開始寫之前我會先確認，下一個事件剛好在 wait 之後到達算不算取消。」

### 4. 「最近 N 毫秒內的最高價」怎麼做到每筆資料只處理一次？

題目：成交依時間排序。對每一筆成交，算出「時間在 `(t − window, t]` 之內的所有成交中，最高的價格」。

**最直覺的做法**：每一筆都往回看整個時間範圍找最大值。如果範圍內平均有 w 筆，總共是 O(n·w)，資料多就很慢。

**結論：用一個「價格由大到小排列」的佇列（單調佇列，monotonic queue），每筆資料最多進出佇列一次，總共 O(n)。**

佇列裡只保留「之後還有可能成為最高價」的成交。關鍵的想法是：

> 如果一筆新的成交價格比佇列尾端的還高（或一樣），那尾端那一筆以後**不可能**再成為最高價了。因為新的這筆比它晚進來、會比它晚離開時間範圍，價格又不比它低。所以可以直接把它丟掉。

每來一筆新成交，做三件事：

1. **從尾端丟掉比較便宜的**：佇列尾端的價格只要 ≤ 新價格，就丟掉。
2. **把新的放進尾端**：這時佇列一定還是由大到小排列。
3. **從前端丟掉已經過期的**：前端的成交如果時間已經超出 `(t − window, t]`，就移除。

做完後，**佇列最前面那筆就是目前範圍內的最高價**。

為什麼是 O(n)？每一筆成交最多被放進佇列一次、被丟出去一次，不管迴圈裡的 `while` 看起來有幾層，總次數都不會超過 2n。

實作小細節：Swift 的 `Array.removeFirst()` 每次都要搬移後面所有元素，是 O(n)。用一個 `head` 索引記錄「前端在哪裡」，取代真的刪除，才能維持 O(n)。

> [!TIP]
> 面試時可以這樣說：「暴力法是每筆都往回找，O(n 乘以視窗大小)。我用單調佇列：佇列裡價格由大到小，新成交進來時把尾端比它便宜的丟掉，因為它們以後不可能再是最高價；前端過期的也移除，前端就是答案。每筆最多進出一次，所以是 O(n)。」

### 5. 移動平均怎麼做到每加一筆都很快？

**結論：維持「最近 n 筆的總和」。新的一筆進來就加上去，最舊的一筆離開就減掉，平均 = 總和 ÷ n，每次都是 O(1)。**

SMA（Simple Moving Average，簡單移動平均）是 K 線圖上最常見的指標，例如「MA20 = 最近 20 根 K 線收盤價的平均」。如果每加一筆就把最近 20 筆重新加一次，是 O(n)；維持一個總和就只要一次加法、一次減法。

要記住「哪一筆是最舊的」，用一個固定大小的環狀緩衝區（ring buffer）：一個長度為 n 的陣列，加上一個索引指向下一個要寫入的位置，寫到尾端就繞回開頭。新值寫進去的位置，剛好就是最舊那筆所在的位置。

```swift
if count == period {
    // 緩衝區已滿：先減掉即將被覆蓋的最舊值
    sum -= buffer[index]
} else {
    count += 1
}

buffer[index] = price
sum += price
// 繞回開頭
index = (index + 1) % period
// 資料不足 period 筆時回傳 nil
return count == period ? sum / Double(period) : nil
```

兩個延伸：

- **浮點數誤差**：一直加加減減 `Double`，誤差會慢慢累積。長時間執行時可以定期重新加總一次，或改用 `Decimal`。
- **EMA**（Exponential Moving Average，指數移動平均）連緩衝區都不需要：`新的 EMA = α × 價格 + (1 − α) × 舊的 EMA`，其中 `α = 2 ÷ (n + 1)`。越新的價格權重越大，所以比 SMA 更快反應價格變化。

> [!TIP]
> 面試時可以這樣說：「我維持最近 n 筆的總和，用環狀緩衝區記住最舊的那筆，新值進來時加上新的、減掉最舊的，每次都是 O(1)。長時間執行時要注意浮點數誤差，可以定期重新加總。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Coding02TimeStreams**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Coding02TimeStreams**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Coding/Coding02-TimeStreams/`，解答在 `labs/ios/Solution/Coding/Coding02-TimeStreams/`；Android 你寫在 `labs/android/practice/coding/coding02-time-streams/`，解答在 `labs/android/solution/coding/coding02-time-streams/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的等**：除了演練 1 的 Bug 版（它的重點就是「要真的等」），其他演練的時間都是資料裡的數字（毫秒），在記憶體裡直接算，每次執行的結果都一樣。算複雜度的演練（4、5）用計數器數「處理了幾次」，不是量秒數，所以 Debug 與 Release 的結果相同。

### 演練 1：防連點的測試要真的等 300 毫秒（第 1 題）

**檔案**：`L01_ThrottleClock.swift`（iOS）、`L01_ThrottleClock.kt`（Android）

**情境**：「買入」按鈕防連點：第一次點擊立刻送出，之後 100 毫秒內的點擊都忽略（throttle，送第一個）。測試要驗證這組點擊：第 0、30、150、300 毫秒各點一次，預期結果是 [送出, 忽略, 送出, 送出]。

**怎麼寫**

1. **Bug 版**：`Throttle` 在 `shouldFire()` 裡自己讀現在時間（iOS 用 `Date()`，Android 用 `System.currentTimeMillis()`）。距離上一次送出不到 100 毫秒就回傳 `false`；否則記下這次的時間，回傳 `true`。演練的流程只能真的等到 30、150、300 毫秒再點。
2. **修正版**：`Throttle` 不讀現在時間，由建構時傳進來的函式 `now` 取得現在幾毫秒，其餘邏輯一樣。演練傳入假時鐘（`C02FakeClock`），時間直接撥到 30、150、300。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 結果 [送出, 忽略, 送出, 送出]，但「這個測試花了」300 多毫秒（實測 iOS、Android 都約 305 到 312 毫秒，每次略有不同） |
| 修正版 | 結果一樣，「這個測試花了 0 毫秒」 |

**為什麼**：函式裡直接讀現在時間，測試就得真的等，要測 30 秒的防連點就得等 30 秒；機器忙的時候 `sleep` 會多睡一點，剛好卡在邊界的點擊結果就會時對時錯（flaky test，時過時不過的測試）。把時間當成輸入，同樣的輸入永遠得到同樣的結果，測試不用等，也不會因為機器忙碌而失敗。Kotlin 的 `runTest` 提供虛擬時間，`delay` 不會真的等，也是同樣的想法。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L01_ThrottleClock.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L01_ThrottleClock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L01_ThrottleClock.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L01_ThrottleClock.kt#fixed
```

### 演練 2：行情一直跳，畫面卻不更新（第 2 題）

**檔案**：`L02_QuoteSample.swift`（iOS）、`L02_QuoteSample.kt`（Android）

**情境**：行情每 50 毫秒送來一筆價格，從第 0 到第 1000 毫秒共 21 筆。畫面希望「最多每 200 毫秒更新一次，而且永遠顯示最新價格」。演練用純函式處理：輸入是帶時間的價格，輸出是畫面實際更新的時間與價格。

**怎麼寫**

1. **Bug 版**：呼叫已經寫好的 `c02Debounce`，等待時間 300 毫秒（輸入停下來 300 毫秒才送出最後一個值）。
2. **修正版**：寫 `sample`：每隔 `interval` 毫秒送出一次，內容是這段時間內最新的一筆。每一筆行情屬於哪一段，用 `(time / interval + 1) * interval` 算出那一段的結束時間；同一段後來的取代前面的，新的一段就加一筆。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「畫面共更新 1 次，更新的時間：[1300]」，畫面最長 1300 毫秒沒有更新 |
| 修正版 | 「畫面共更新 6 次」，時間 [200, 400, 600, 800, 1000, 1200]，最長 200 毫秒沒有更新，每次都是當下最新的價格 |

**為什麼**：debounce 要等輸入「停下來」才送出，行情每 50 毫秒就來一筆，永遠等不到停下來，畫面整整 1 秒沒有動，等行情停了才更新一次。行情要用 sample（每段時間送最新的）：Combine 是 `throttle(for:scheduler:latest: true)`，Kotlin Flow 是 `sample(200)`。搜尋框則相反，使用者停止打字才查詢，適合 debounce。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L02_QuoteSample.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L02_QuoteSample.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L02_QuoteSample.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L02_QuoteSample.kt#fixed
```

### 演練 3：最後一個字沒送出，邊界被當成取消（第 3 題）

**檔案**：`L03_DebounceBoundary.swift`（iOS）、`L03_DebounceBoundary.kt`（Android）

**情境**：把 debounce 寫成純函式：輸入是依時間排序的事件，輸出是實際送出的事件，送出時間是「事件時間 + wait」。規則：下一個事件在 `time + wait` 之前（嚴格小於）到達，這一個才被取消；剛好在 `time + wait` 到達不算取消；最後一個事件一定送出。演練用 4 組固定的資料（wait = 100 毫秒）檢查答案：打字停頓、剛好間隔 wait、只有一個事件、沒有事件。

**怎麼寫**

1. **Bug 版**：迴圈只跑到倒數第二個事件（最後一個沒有「下一個」可以比，就被漏掉了）；判斷寫成「下一個事件和這一個相差大於 wait（`> wait`）才送出」。
2. **修正版**：每個事件都看；是最後一個，或下一個事件和這一個相差大於等於 wait（`>= wait`）就送出。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 案例 1 只得到 [(150, bt)]（少了最後的 btc），案例 2、3 得到 []；4 組只答對 1 組（空輸入）：「答對 1/4」 |
| 修正版 | 4 組全部答對：「答對 4/4」 |

**為什麼**：debounce 的送出條件是「沒有人在 wait 之內取消它」，最後一個事件後面沒有人，所以一定送出；邊界是 `>=` 還是 `>`，決定剛好間隔 wait 的事件算不算取消，這要在開始寫之前先向面試官確認。把時間當成資料裡的數字，這些邊界才能用固定的測試資料一個一個檢查，不用真的等。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L03_DebounceBoundary.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L03_DebounceBoundary.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L03_DebounceBoundary.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L03_DebounceBoundary.kt#fixed
```

### 演練 4：每筆成交都往回掃整個視窗（第 4 題）

**檔案**：`L04_WindowMax.swift`（iOS）、`L04_WindowMax.kt`（Android）

**情境**：成交依時間排序，每 2 毫秒一筆，共 3000 筆。對每一筆成交，算出時間在 (t − 500, t] 之內的最高價。演練用計數器數「處理了幾次」，同時和標準答案比對（兩種做法算出來的結果一樣，差的是成本）。

**怎麼寫**

1. **Bug 版**：每一筆成交都往回看，直到時間不在視窗內，邊看邊記最大的價格。每看一筆，計數器加 1。
2. **修正版**：單調佇列。佇列存成交的索引，價格由大到小：新成交進來，先把尾端價格小於或等於新價格的丟掉，再放進去，再把前端已經過期的丟掉，前端那筆就是答案。每丟掉或放進去一次，計數器加 1。iOS 用 `head` 索引記錄前端在哪裡（避免 `removeFirst()` 的 O(n)），Android 用 `ArrayDeque`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 結果正確，但「處理了 715875 次」：每筆往回看 249 筆 |
| 修正版 | 結果一樣正確，「處理了 5988 次」，不超過筆數的 3 倍 |

**為什麼**：暴力法是 O(n·w)（n 筆、視窗裡平均 w 筆），資料多一倍、視窗大一倍，成本就變 4 倍。單調佇列裡每一筆成交最多進佇列一次、出佇列一次，所以不管 `while` 看起來有幾層，總次數都不會超過 2n（再加上丟掉過期的，演練的檢查放寬到 3n）。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L04_WindowMax.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L04_WindowMax.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L04_WindowMax.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L04_WindowMax.kt#fixed
```

### 演練 5：每加一筆，就把 200 筆重新加一次（第 5 題）

**檔案**：`L05_SmaRecompute.swift`（iOS）、`L05_SmaRecompute.kt`（Android）

**情境**：K 線圖的 MA200（最近 200 根收盤價的平均），收盤價一根一根進來，共 2000 根。演練用計數器數「做了幾次加減」，並和標準答案比對最後一筆的平均。

**怎麼寫**

1. **Bug 版**：環狀緩衝區（固定大小的陣列加一個索引，寫到尾端就繞回開頭）已經寫好；你要把緩衝區裡的價格全部重新加總，每加一次計數器加 1，緩衝區滿了回傳 `sum / period`，沒滿回傳 `nil`（Android 是 `null`）。
2. **修正版**：維持 `sum`：緩衝區滿了先減掉即將被覆蓋的最舊值 `buffer[index]`，再把新價格放進去、加進 `sum`，`index` 往前一格並繞回開頭，滿了回傳平均。每次加或減，計數器加 1。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 平均正確，但「做了 380100 次加減」：每筆加 200 次 |
| 修正版 | 平均一樣正確，「做了 3800 次加減」：每筆只做 2 次，緩衝區還沒滿時每筆 1 次 |

**為什麼**：每次都重新加總是 O(期數)；維持總和，每次只做一次加法、一次減法，是 O(1)，不管期數是 20 還是 200。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L05_SmaRecompute.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L05_SmaRecompute.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L05_SmaRecompute.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L05_SmaRecompute.kt#fixed
```

### 演練 6：壞資料離開視窗後，平均永遠差好幾円（第 5 題的延伸）

**檔案**：`L06_SmaDrift.swift`（iOS）、`L06_SmaDrift.kt`（Android）

**情境**：5 期移動平均，價格約 1500 萬円。第 11 筆資料來源送來一筆壞資料（價格 1e18），第 16 筆之後它已經離開視窗。`Double` 只有約 16 位有效數字：總和裡有 1e18 時，一千五百萬那部分的小數位被吃掉，之後減掉 1e18 也補不回來。

**怎麼寫**

1. **Bug 版**：一直在同一個 `sum` 上加加減減（上一題修正版的寫法）：緩衝區滿了先減掉 `buffer[index]`，放進新價格、加進 `sum`，回傳平均。
2. **修正版**：加減的部分已經寫好，你要加上「每加滿一輪（`added % period == 0`），從緩衝區重新加總 `sum`」，丟掉累積的誤差。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 壞資料早就離開視窗，最後一筆的平均卻還差 4.6 円：「誤差 4.600 円，超過 1 円：是」 |
| 修正版 | 誤差消失：「誤差 0.000 円，超過 1 円：否」 |

**為什麼**：浮點數加減會有誤差，一直在同一個總和上累加，誤差只會留著、不會消失。長時間執行的移動平均，要定期（例如每一輪）從緩衝區重新加總一次；每 `period` 筆做一次 O(period) 的加總，平均下來每筆還是 O(1)。要完全避免浮點誤差，也可以改用 `Decimal`（iOS）、`BigDecimal`（Android）。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L06_SmaDrift.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding02-TimeStreams/L06_SmaDrift.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding02-time-streams/L06_SmaDrift.kt#bug
```

```lab-solution
android/solution/coding/coding02-time-streams/L06_SmaDrift.kt#fixed
```

## 常見陷阱

- **沒先確認邊界。** debounce 的下一個事件剛好在 `t + wait` 到達算不算取消？視窗是 `(t − w, t]` 還是 `[t − w, t]`？這些會決定 `>`、`>=` 要怎麼寫，一開始就要問。
- **行情用 debounce。** 價格一直在變，debounce 會一直延後，畫面不更新。行情要用 sample 或 conflate。
- **單調佇列用 `removeFirst()`。** Swift 的 `removeFirst()` 是 O(n)，整體就退化成 O(n²)。用 `head` 索引取代。
- **移動平均資料不足時沒有處理。** 前 n − 1 筆要回傳 nil 還是部分平均？要先和面試官確認。

## 面試官想聽到的

1. 主動把時間做成參數，並說出理由：可以測試、結果可以重現。
2. 能說出 debounce、throttle、sample 的差別，而且知道行情畫面要用 sample。
3. 能解釋單調佇列為什麼是 O(n)：每筆資料最多進出一次。
4. 移動平均能做到 O(1)，並提到浮點數誤差。

## iOS / Android 對照

| 主題 | iOS（Combine／Swift Concurrency） | Android（Kotlin Flow） |
|---|---|---|
| debounce | `debounce(for:scheduler:)` | `debounce(300)` |
| 每段時間送最新的 | `throttle(for:scheduler:latest: true)` | `sample(200)` |
| 處理不過來只留最新 | `AsyncStream` 的 `bufferingPolicy: .bufferingNewest(1)` | `conflate()` |
| 可控制的時間 | `Clock` protocol，測試時注入假時鐘 | `runTest` 的虛擬時間 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 等輸入停下來才送出 | debounce | デバウンス |
| 節流 | throttle | スロットル |
| 取樣 | sample | サンプリング |
| 單調佇列 | monotonic queue（deque） | 単調キュー |
| 移動平均 | moving average（SMA／EMA） | 移動平均（単純／指数） |
| 環狀緩衝區 | ring buffer | リングバッファ |
