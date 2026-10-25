---
id: livecoding-exchange
title: 交易所情境題：撮合引擎、二分搜尋找 K 線、K 線補洞
summary: 交易所面試官很喜歡的三題：簡化版的撮合引擎（價格優先、時間優先）、手指在圖上拖曳時找到對應的 K 線（二分搜尋）、沒有成交的時段把 K 線補上。這個單元先講交易規則，再講每一題的解法思路。
priority: P0
day: 5
tags: [live-coding, matching, binary-search, kline]
---

## 重點講解

這個單元回答三個問題：

1. 撮合引擎怎麼決定誰和誰成交、用什麼價格成交？
2. 手指在 K 線圖上拖曳時，怎麼快速找到對應的那一根 K 線？
3. 某些時段沒有成交、API 沒有回傳那根 K 線時，要怎麼補？

### 1. 撮合引擎怎麼決定誰和誰成交、用什麼價格？

App 不會自己實作撮合，撮合是交易所伺服器做的事。但這題能檢驗你是不是真的懂「訂單簿（板）」：為什麼市價單（成行注文）會滑價、為什麼限價單（指値注文）可能只成交一部分。

先講清楚幾個名詞：

- **訂單簿**：所有還沒成交的掛單。買方那一側（bid）由高價排到低價，賣方那一側（ask）由低價排到高價。
- **maker**：先掛在簿上、等人來成交的那一方。
- **taker**：新送進來、立刻和簿上的掛單成交的那一方。

撮合的規則叫做 **price-time priority（價格優先、時間優先）**：

1. **價格優先**：買單先和「最便宜的賣單」成交；賣單先和「最貴的買單」成交。
2. **時間優先**：同一個價格有好幾筆掛單時，先掛的先成交（FIFO，先進先出）。
3. **成交價是 maker 的價格**：簿上的掛單本來就願意用那個價格成交，所以用它的價格，而不是新訂單的限價。
4. **沒成交完的部分掛到簿上**：限價單吃不完的數量，用自己的限價掛上去，變成新的 maker。

例子：簿上的賣單是 100 円 × 1、101 円 × 1、103 円 × 5。使用者下限價買單 102 円 × 3：

- 先和 100 円的成交 1 個（成交價 100，不是 102）。
- 再和 101 円的成交 1 個。
- 下一個是 103 円，比限價 102 円貴，停止。
- 剩下的 1 個以 102 円掛在買方，成為新的最高買價。

如果同一筆換成**市價單**，就會一路吃到 103 円，平均成交價比畫面上看到的最低賣價高，這就是**滑價**。

**資料結構**：每一側是「價格 → 這個價格的掛單佇列（先來先成交）」，價格要保持排序。面試時用排序好的陣列就可以（插入是 O(n)），寫完再補充：正式的撮合引擎會用平衡樹或 skip list 這類結構，插入是 O(log n)；再另外記住最佳價格那一層，取最佳價格就是 O(1)。

寫程式時的流程：

```text
remaining = 訂單數量
while remaining > 0 且 對面最好的價格可以成交:
    取出對面最好價格那一層
    while remaining > 0 且 這一層還有掛單:
        成交量 = min(remaining, 最前面那筆掛單的數量)
        記錄一筆成交（成交價 = 這一層的價格）
        兩邊都扣掉成交量；掛單數量變 0 就移除
    這一層空了就移除
remaining > 0 的話，用自己的限價掛到自己那一側
```

> [!TIP]
> 面試時可以先確認規則：「成交價是用簿上掛單的價格嗎？同價格是先掛的先成交？沒成交完的部分要掛到簿上嗎？」寫完再補充延伸：市價單、取消訂單、防止自己和自己成交（self-trade prevention）、maker 和 taker 的手續費不同。

> [!TIP]
> 面試時可以這樣說：「撮合的規則是價格優先、時間優先：買單先和最便宜的賣單成交，同一個價格先掛的先成交，成交價用簿上掛單的價格。沒成交完的限價單，剩下的數量掛到簿上。市價單會一路吃到比較貴的價位，這就是滑價。」

### 2. 手指在 K 線圖上拖曳時，怎麼快速找到對應的 K 線？

**結論：K 線依時間排序，所以可以用二分搜尋（binary search）。每一步把範圍砍一半，幾千根 K 線也只要十幾步，是 O(log n)。**

情境：使用者在 K 線圖上長按拖曳，畫面會出現十字線，顯示手指位置那一根 K 線的開高低收。手指的位置換算成時間 `t` 後，要找的是「**開始時間 ≤ t 的最後一根 K 線**」。

做法是先找「**第一根開始時間 > t 的位置**」（這個位置叫做 upper bound），答案就是它的前一根：

```swift
var lo = 0
var hi = times.count              // 搜尋範圍是 [lo, hi)：包含 lo，不包含 hi
while lo < hi {
    let mid = lo + (hi - lo) / 2  // 寫成這樣比 (lo + hi) / 2 安全，不會因為相加而溢位
    if times[mid] <= t {
        lo = mid + 1              // mid 還不夠大，答案在右半邊
    } else {
        hi = mid                  // mid 已經大於 t，答案在左半邊（含 mid）
    }
}
// 迴圈結束時 lo 就是「第一根 > t 的位置」
return lo == 0 ? nil : lo - 1     // lo 是 0 代表 t 比第一根還早，找不到
```

例子：`times = [10, 20, 20, 20, 30]`，找 `t = 20`。第一個大於 20 的是索引 4（30），答案是 4 − 1 = 3。

二分搜尋最容易錯在邊界。避免出錯的方法是：**一開始就決定搜尋範圍包不包含兩端**（上面用的是「包含左邊、不包含右邊」），之後每一行都照這個定義寫。

> [!TIP]
> 面試時可以這樣說：「K 線依時間排序，所以用二分搜尋，O(log n)。我先找第一根時間大於 t 的位置，答案是它的前一根。我用包含左邊、不包含右邊的範圍，迴圈條件是 lo < hi，邊界比較不容易寫錯。」

### 3. 某些時段沒有成交時，K 線要怎麼補？

情境：交易量小的幣種，某些時段完全沒有成交。有些 API 會直接不回傳那一根 K 線。如果圖表直接把拿到的 K 線一根接一根畫出來，時間軸就會「縮起來」：兩根相鄰的 K 線其實差了好幾個時段，使用者會誤判價格變化的速度。

**結論：把缺的時段補上，開高低收都用前一根的收盤價，成交量是 0。**

做法：

1. 從第一根開始，記住「前一根」。
2. 看下一根的時間。如果它比「前一根的時間 + 週期」還晚，代表中間有缺：從「前一根時間 + 週期」開始，每次加一個週期，補一根，直到追上下一根的時間。
3. 只補在第一根和最後一根之間，頭尾外面不補。

```swift
var t = previous.time + period
while t < candle.time {          // 還沒追上下一根，就繼續補
    let c = previous.close
    result.append(Candle(time: t, open: c, high: c, low: c, close: c, volume: 0))
    t += period                  // 一定要前進，否則會無限迴圈
}
```

開始寫之前要確認：K 線的時間是不是都對齊週期（例如 1 小時 K 線都從整點開始）。如果沒對齊，補出來的 K 線時間會錯開。

> [!TIP]
> 面試時可以這樣說：「沒有成交的時段，我用前一根的收盤價補一根開高低收都一樣、成交量 0 的 K 線，避免時間軸縮起來讓使用者誤判。我會先確認時間有沒有對齊週期。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Coding03ExchangeAlgorithms**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Coding03ExchangeAlgorithms**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Coding/Coding03-ExchangeAlgorithms/`，解答在 `labs/ios/Solution/Coding/Coding03-ExchangeAlgorithms/`；Android 你寫在 `labs/android/practice/coding/coding03-exchange-algorithms/`，解答在 `labs/android/solution/coding/coding03-exchange-algorithms/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的連交易所**：這個單元的演練都是用固定的假資料，在記憶體裡直接跑，每次執行的結果都一樣。價格與數量都用整數（價格單位是円），掛單、成交、K 線的型別與印成文字的函式寫在 `Coding03Support` 裡。

### 演練 1：同價格後掛的賣單，先成交了（第 1 題）

**檔案**：`L01_Fifo.swift`（iOS）、`L01_Fifo.kt`（Android）

**情境**：賣方的掛單用陣列存，價格由低到高。依序收到 A（100）、D（101）、B（100）、C（100）四張賣單，其中 A、B、C 都是 100 円、各 1 個，掛單的先後是 A → B → C。接著有人用限價 100 買 2 個，依「價格優先、時間優先」，應該和 A、B 成交。

**怎麼寫**

1. **Bug 版**：寫 `insertIndex`，回傳新賣單該插在陣列的哪個位置：「第一個價格大於等於新價格」的位置（iOS 用 `firstIndex`，Android 用 `indexOfFirst`），找不到就放最後。新單就會插在同價格那一排的最前面。
2. **修正版**：改成「第一個價格大於新價格」的位置（`>`），找不到就放最後。新單排在同價格那一排的最後面。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 賣單簿 [C 100×1, B 100×1, A 100×1, D 101×1]；買進 100 × 2 → 成交 [C:1@100, B:1@100]，先掛的 A 沒輪到 |
| 修正版 | 賣單簿 [A 100×1, B 100×1, C 100×1, D 101×1]；買進 100 × 2 → 成交 [A:1@100, B:1@100] |

**為什麼**：撮合的第二條規則是時間優先：同一個價格，先掛的先成交（FIFO，先進先出）。用排序好的陣列存掛單時，「同價格的新單放在前面還是後面」只差一個比較符號（`>=` 或 `>`），但後掛的搶先成交，對先掛的人是不公平的，在交易所是嚴重的問題。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L01_Fifo.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L01_Fifo.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L01_Fifo.kt#bug
```

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L01_Fifo.kt#fixed
```

### 演練 2：買方限價 102，成交價全變成 102（第 1 題）

**檔案**：`L02_TradePrice.swift`（iOS）、`L02_TradePrice.kt`（Android）

**情境**：賣單簿有 A（100 円 × 1）、B（101 円 × 1）、C（103 円 × 5）。使用者下限價買單：102 円 × 3。C 的 103 比限價貴，不成交；A、B 各成交 1 個，剩下 1 個沒吃到。撮合的迴圈已經寫好，你只要決定每一筆成交的價格。

**怎麼寫**

1. **Bug 版**：成交價用買方自己的限價 `price`。
2. **修正版**：成交價用簿上掛單 `best`（maker）的價格 `best.price`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 成交 [A:1@102, B:1@102]，「買方總共付出 204 円」，多付了 3 円 |
| 修正版 | 成交 [A:1@100, B:1@101]，「買方總共付出 201 円」 |

**為什麼**：成交價是 maker（先掛在簿上的那一方）的價格，因為簿上的掛單本來就願意用那個價格成交；買方的限價只是「最多願意付多少」的上限，決定吃到哪一檔為止。用 taker（新送進來的那一方）的限價當成交價，A 本來願意用 100 賣，卻被記成 102，買方多付、帳也對不起來。市價單會滑價，也是因為吃到比較貴的價位時，每一檔各自用自己的價格成交。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L02_TradePrice.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L02_TradePrice.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L02_TradePrice.kt#bug
```

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L02_TradePrice.kt#fixed
```

### 演練 3：部分成交後，賣方憑空少了一截（第 1 題）

**檔案**：`L03_PartialFill.swift`（iOS）、`L03_PartialFill.kt`（Android）

**情境**：賣單簿有 A（100 × 1）、B（101 × 1）、C（103 × 5）。連續三張限價買單：① 102 × 3，吃掉 A、B 後還剩 1 個，應該用自己的限價 102 掛在買方；② 103 × 2，和 C 成交 2 個，C 是部分成交，還剩 3 個要留在簿上；③ 103 × 3，把 C 剩下的 3 個吃完。最後應該是：買方只剩第 ① 張剩下的 1 個，賣方空了，共成交 7 個。

**怎麼寫**

1. **Bug 版**：①-1 每成交一次，不管 maker 有沒有吃完，都整筆從簿上移除（iOS `asks.removeFirst()`，Android `asks.removeAt(0)`）；①-2 迴圈結束後，吃不完的 `remaining` 不要掛到買方。
2. **修正版**：②-1 maker 扣掉成交量，數量變 0 才移除（沒吃完的留在簿上）；②-2 迴圈結束後 `remaining > 0`，用自己的限價掛到買方（`bids`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 ① 張剩下的 1 個消失（買方一直是 []）；C 被吃 2 個就整筆移除，第 ③ 張買單吃不到：「最後：買方 []，賣方 []，共成交 4 個」 |
| 修正版 | 「最後：買方 [T1 102×1]，賣方 []，共成交 7 個」 |

**為什麼**：部分成交之後，帳要算清楚兩邊：maker 吃了一部分，剩下的數量要留在簿上（排隊的位置不變，這是時間優先）；taker 吃不完的部分，要用自己的限價掛上去，變成新的 maker。漏了前者，簿上的流動性憑空消失；漏了後者，使用者的委託少了一截卻沒有任何通知。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L03_PartialFill.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L03_PartialFill.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L03_PartialFill.kt#bug
```

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L03_PartialFill.kt#fixed
```

### 演練 4：十字線跳到前一根，找不到時回傳 -1（第 2 題）

**檔案**：`L04_BinarySearch.swift`（iOS）、`L04_BinarySearch.kt`（Android）

**情境**：K 線依開始時間排序。手指換算成時間 `t` 後，要找「開始時間小於等於 t 的最後一根」的索引；`t` 比第一根還早就回傳 `nil`（Android 是 `null`）。演練用 6 組固定的資料檢查答案：落在兩根之間、剛好是某一根的開始時間、比第一根還早、比最後一根還晚、有重複的時間、沒有 K 線；最後再用 10 萬根 K 線數「探測了幾次」。

**怎麼寫**

1. **Bug 版**：迴圈裡 `times[mid] < t` 才往右（`lo = mid + 1`），否則 `hi = mid`，找到的是「第一根大於等於 t」的位置；結束後回傳 `lo - 1`，沒有檢查 `lo` 是不是 0。
2. **修正版**：迴圈裡 `times[mid] <= t` 就往右，找「第一根大於 t」的位置；結束後 `lo == 0` 回傳 `nil`（`null`），否則回傳 `lo - 1`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 案例 2 回傳 2（期待 3）、案例 3 回傳 -1（期待 nil）、案例 5 回傳 0（期待 3）、案例 6 回傳 -1：「答對 2/6」 |
| 修正版 | 「答對 6/6」，10 萬根 K 線只探測 17 次 |

**為什麼**：手指剛好按在某一根的開始時間上，是很常發生的情況，少一根就是十字線跳到前一根。找不到時回傳 -1，呼叫端直接拿它當陣列索引，就是越界當掉（Swift 的 `Array` 越界會 crash，Kotlin 丟出 `IndexOutOfBoundsException`）。避免出錯的方法是一開始就決定搜尋範圍是 `[lo, hi)`，之後每一行都照這個定義寫。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L04_BinarySearch.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L04_BinarySearch.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L04_BinarySearch.kt#bug
```

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L04_BinarySearch.kt#fixed
```

### 演練 5：補的 K 線用了很久以前的收盤價（第 3 題）

**檔案**：`L05_FillGaps.swift`（iOS）、`L05_FillGaps.kt`（Android）

**情境**：1 分鐘 K 線（週期 60 秒），API 沒有回傳沒成交的那幾分鐘。收到的 K 線時間是 0、180、240、420，中間缺 60、120（第 1 個缺口，前一根收盤 102）和 300、360（第 2 個缺口，前一根是 240 那根，收盤 115）。缺的時段要補一根開高低收都等於「前一根收盤價」、成交量 0 的 K 線。找缺口的時間迴圈已經寫好。

**怎麼寫**

1. **Bug 版**：用一個變數 `lastClose` 記住收盤價，一開始設成第一根的收盤價，之後都沒有更新；缺口裡每一根都補 `lastClose`。
2. **修正版**：`lastClose` 是 `var`；每放進一根真正的 K 線（`result` 加入 `candle`），就把 `lastClose` 更新成這一根的收盤價。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「補了 4 根（時間→價格）：60→102, 120→102, 300→102, 360→102」：第 2 個缺口還在用第一根的收盤價 |
| 修正版 | 「補了 4 根（時間→價格）：60→102, 120→102, 300→115, 360→115」，時間軸 0 到 420 共 8 根 |

**為什麼**：第 1 個缺口剛好是對的，所以只用一組資料很容易漏看；到第 2 個缺口，圖上 300、360 兩根的價格比實際低了 13 円，使用者會誤判價格變化。補洞的價格一定是「緊鄰缺口前面那一根」的收盤價，所以每放進一根真正的 K 線，就要更新它。另外兩個要在開始寫之前確認的事：K 線的時間有沒有對齊週期，以及迴圈裡一定要讓時間往前走（少了 `t += period` 就是無限迴圈）。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L05_FillGaps.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding03-ExchangeAlgorithms/L05_FillGaps.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L05_FillGaps.kt#bug
```

```lab-solution
android/solution/coding/coding03-exchange-algorithms/L05_FillGaps.kt#fixed
```

## 常見陷阱

- **用 taker 的限價當成交價。** 成交價是簿上 maker 的價格。上面的例子用 102 円成交就錯了。
- **部分成交後，忘了把剩下的數量掛到簿上。** 或者 maker 的數量已經變成 0，卻沒從簿上移除。
- **價格那一層的掛單都成交完了，沒有移除那一層。** 下次取最佳價格時會拿到一個空的價格層。
- **二分搜尋的邊界寫錯。** 一開始沒定義範圍包不包含兩端，`<`、`<=`、`+1`、`-1` 就會寫錯。
- **補 K 線的迴圈時間沒有前進。** 少了 `t += period` 就是無限迴圈。

## 面試官想聽到的

1. 開始寫之前先問清楚規則：成交價、部分成交、同價格的優先順序。
2. 說得出資料結構的選擇和複雜度，以及正式系統會用什麼結構。
3. 能把演算法連回 App 的使用體驗：滑價、部分成交、K 線圖的時間軸。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 訂單簿 | order book | 板（いた） |
| 撮合 | matching | 約定処理（マッチング） |
| 掛單方／吃單方 | maker／taker | メイカー／テイカー |
| 部分成交 | partial fill | 一部約定 |
| 滑價 | slippage | スリッページ |
| 二分搜尋 | binary search | 二分探索 |
| 市價單／限價單 | market order／limit order | 成行注文／指値注文 |
