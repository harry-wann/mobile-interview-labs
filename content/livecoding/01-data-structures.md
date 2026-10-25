---
id: livecoding-ds
title: 資料結構題：LRU 快取、排行榜前 k 名、找出還沒載入的區間
summary: 行動端面試最常出的資料結構題：圖片快取用的 LRU、行情排行榜的前 k 名、K 線分段載入時找出缺的區間。這個單元先講 live coding 的作答節奏和複雜度怎麼講，再一題一題說明解法的思路。
priority: P0
day: 5
tags: [live-coding, data-structures]
---

## 重點講解

這個單元回答五個問題：

1. Live coding 時，從聽到題目到寫完，要照什麼順序進行？
2. 時間複雜度（Big O）要怎麼用白話講？
3. LRU 快取怎麼做到 `get`、`put` 都很快？
4. 排行榜取前 k 名，要排序還是用 heap？
5. 已經載入幾段 K 線，怎麼算出還缺哪幾段？

題目的完整實作在下方的練習題，建議先讀思路，再自己寫一次、按「執行測試」。

### 1. Live coding 要照什麼順序進行？

面試官看的不只是「寫不寫得出來」，還有你怎麼思考、怎麼溝通。建議固定照這五步：

1. **複述題目、確認規則**：用自己的話講一次題目，接著問清楚邊界情況。例如：容量可以是 0 嗎？有重複的值嗎？陣列是空的怎麼辦？k 比資料筆數大怎麼辦？
2. **先講思路和複雜度**：說明你打算用什麼資料結構、為什麼、時間複雜度是多少。等面試官點頭再開始寫，避免寫到一半才發現方向錯。
3. **寫程式**：命名清楚。先寫主要流程，再補邊界情況。
4. **自己跑一次**：挑一個正常的例子、兩個邊界的例子，在腦中（或在白板上）一行一行走過。
5. **延伸**：主動提到多執行緒安全、記憶體上限，以及 production 會用哪個現成的工具。

> [!TIP]
> 面試時可以這樣開場：「我先確認一下題目：……。我打算用 HashMap 加雙向鏈結串列，get 和 put 都是 O(1)。這樣的方向可以嗎？」

### 2. 時間複雜度要怎麼用白話講？

時間複雜度（Big O）描述的是「資料變多時，花的時間會怎麼增加」。面試時講得出下面幾種就夠了：

| 寫法 | 白話 | 例子 |
|---|---|---|
| O(1) | 不管資料有多少筆，花的時間都一樣 | 用 key 從 Dictionary 取值 |
| O(log n) | 每一步把範圍砍一半，資料多 1000 倍也只多走 10 步左右 | 在排序好的陣列做二分搜尋 |
| O(n) | 每一筆資料看一次 | 掃過整個陣列找最大值 |
| O(n log n) | 排序的成本 | `sorted()` |
| O(n²) | 每一筆都要和其他每一筆比一次，資料多一點就很慢 | 兩層迴圈 |

講複雜度時，順便說出「n 實際上有多大」，會加分。例如交易所的幣種只有幾十到幾百個，O(n log n) 的排序完全夠用，不需要為了漂亮的複雜度寫更複雜的程式。

> [!TIP]
> 面試時可以這樣說：「時間複雜度是在說資料變多時，花的時間會怎麼增加：O(1) 是不管多少筆都一樣快，O(n) 是每一筆看一次，O(n log n) 是排序的成本。我會順便說出 n 實際上有多大，例如交易所的幣種只有幾百個，排序就夠用了，不必把程式寫得更複雜。」

### 3. LRU 快取怎麼做到 `get`、`put` 都很快？

**結論：用 HashMap 加雙向鏈結串列。HashMap 負責「用 key 立刻找到資料」，串列負責「記住使用的先後順序」，兩個操作都是 O(1)。**

LRU 是 Least Recently Used 的縮寫，意思是「容量滿了，就丟掉最久沒用到的那一筆」。圖片快取最常用這種策略：使用者剛看過的圖片，很可能等一下又要看。

為什麼需要兩個資料結構？

- 只用 Dictionary：可以 O(1) 找到資料，但不知道哪一筆最久沒用。
- 只用陣列記順序：知道順序，但每次「把某一筆移到最前面」要先找到它、再搬移其他元素，是 O(n)。
- 兩個一起用：Dictionary 存「key → 串列上的節點」，找到節點後，在雙向鏈結串列上把它拿出來、放到最前面，只要改幾個指標，是 O(1)。

操作流程：

- `get(key)`：用 Dictionary 找到節點 → 把它移到串列最前面（代表剛用過）→ 回傳值。
- `put(key, value)`：
  - key 已存在：更新值，移到最前面。**不要**當成新的一筆加入。
  - key 不存在：建立新節點放到最前面；如果超過容量，把串列最後面（最久沒用）的節點刪掉，也從 Dictionary 刪掉。

實作上常用的小技巧：串列頭尾各放一個「假節點」（不存資料，只當邊界），這樣插入和刪除都不用特別處理「串列是空的」或「刪的是第一個」這些情況。

```swift
// 核心的兩個串列操作（完整實作見練習題）
// 把節點從原本的位置拿出來
private func unlink(_ node: Node) {
    node.prev?.next = node.next
    node.next?.prev = node.prev
}

// 放到最前面（最近使用）
private func insertAfterHead(_ node: Node) {
    node.next = head.next
    node.prev = head
    head.next?.prev = node
    head.next = node
}
```

**Production 用什麼？**

- Android 有現成的 `LruCache`（照大小淘汰，可以覆寫 `sizeOf` 決定每筆算多大）。Kotlin／Java 的 `LinkedHashMap(capacity, 0.75f, true)`（第三個參數 `accessOrder = true`，代表依存取順序排列），再覆寫 `removeEldestEntry`，就是一個 LRU。
- iOS 有 `NSCache`：系統記憶體不足時會自動清掉內容，但它**不保證照 LRU 的順序**清，也不保證什麼時候清。需要嚴格照「最久沒用」淘汰時，就要自己寫。
- 圖片快取通常是兩層：記憶體一層 LRU、磁碟一層，Kingfisher、Coil 這些套件都是這樣設計。

**多執行緒**：快取通常會被多個執行緒同時存取。Swift 可以把它包成 actor 或用鎖保護；Android 的 `LruCache` 本身已經有同步保護。

> [!TIP]
> 面試時可以這樣說：「我用 Dictionary 加雙向鏈結串列。Dictionary 讓我用 key 直接拿到節點，串列記錄使用順序，最近用的放前面。get 和 put 都只改幾個指標，所以是 O(1)。實務上 Android 可以直接用 LruCache；iOS 的 NSCache 不保證照 LRU 淘汰，需要嚴格順序時我會自己實作，並用 actor 保護。」

### 4. 排行榜取前 k 名，要排序還是用 heap？

**結論：先看 n 有多大。交易所的幣種通常只有幾十到幾百個，直接排序最清楚；n 非常大、k 很小時，才值得用 heap。**

兩種做法：

- **全部排序再取前 k 個**：O(n log n)。程式最短、最好讀。
- **用大小為 k 的 min-heap**：heap 是一種「隨時能拿到最小值」的結構。只保留目前最大的 k 筆，新的一筆比 heap 裡最小的還大，就把最小的換掉。每筆資料的成本是 O(log k)，總共 O(n log k)。n 是一百萬、k 是 10 的時候，這個差距才明顯。

面試時，先講出「n 不大，排序就夠了」的判斷，再補一句「如果 n 很大，我會用 min-heap」，比一開始就硬寫 heap 更能展現判斷力。

另一個容易被忽略的細節：**數值相同時要有固定的排序規則**。例如兩個幣種成交量一樣，要再依代號字母排序。不然每次更新時，兩個幣種的位置可能互換，畫面上的列表會一直跳動。

```swift
volumes
    .sorted { lhs, rhs in
        // 成交量不同：大的在前；成交量相同：代號字母順序在前
        lhs.value != rhs.value ? lhs.value > rhs.value : lhs.key < rhs.key
    }
    // k 大於筆數時，prefix 會回傳全部，不會 crash
    .prefix(k)
    .map(\.key)
```

> [!TIP]
> 面試時可以這樣說：「排行榜的幣種只有幾百個，我直接排序，O(n log n) 完全夠用，程式也最好維護。成交量相同時我會再依代號排序，避免畫面跳動。如果資料量是百萬等級、只要前 10 名，我會改用大小為 k 的 min-heap，變成 O(n log k)。」

### 5. 已經載入幾段 K 線，怎麼算出還缺哪幾段？

**結論：先把已載入的區間依起點排序，再用一個游標從要看的範圍起點往後掃，遇到空隙就記下來。排序 O(n log n)，掃描 O(n)。**

情境：K 線圖讓使用者左右滑動，資料是分段向 API 載入的。使用者滑到某個範圍時，只應該請求「還沒載入的部分」，不要重複下載已經有的資料。

做法（以 K 線的索引表示區間，`[1...3]` 代表第 1 到第 3 根）：

1. 把已載入的區間依起點排序。
2. 游標 `cursor` 從要看的範圍起點開始。
3. 依序看每一段已載入的區間：
   - 如果這段整個在游標前面，跳過。
   - 如果這段的起點在游標後面，代表中間有空隙：`cursor ... 起點 - 1` 就是缺的區間。
   - 把游標移到這段的終點 + 1。
4. 全部看完後，如果游標還沒超過範圍終點，剩下的部分也是缺的。

例子：要看 `[1...10]`，已載入 `[3...4]` 和 `[6...8]`，結果是 `[1...2]`、`[5...5]`、`[9...10]`。

一開始要和面試官確認的事：**區間的兩端是不是都包含**（閉區間 `[a, b]` 還是半開區間 `[a, b)`），以及「相鄰」算不算連續（`[1...3]` 和 `[4...6]` 中間有沒有缺口）。這兩點會直接影響 +1、−1 要寫在哪裡。

> [!TIP]
> 面試時可以這樣說：「我先把已載入的區間依起點排序，再用一個游標從請求的起點往後掃，遇到空隙就記下來，最後補上尾巴。整體是 O(n log n)，瓶頸在排序。開始寫之前我想先確認：區間是閉區間嗎？相鄰的區間算連續嗎？」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Coding01DataStructures**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Coding01DataStructures**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Coding/Coding01-DataStructures/`，解答在 `labs/ios/Solution/Coding/Coding01-DataStructures/`；Android 你寫在 `labs/android/practice/coding/coding01-data-structures/`，解答在 `labs/android/solution/coding/coding01-data-structures/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的等**：這個單元的演練都是 live coding 常見的「寫錯的版本」，用固定的測試資料在記憶體裡直接跑，每次執行的結果都一樣。算複雜度的演練用計數器數「比較了幾次」「碰了幾個元素」，不是量秒數，所以 Debug 與 Release 的結果相同。LRU 用的雙向鏈結串列（頭尾各一個假節點）已經寫在 `Coding01Support` 裡，演練只練 LRU 本身。

### 演練 1：剛讀過的圖示，下一次放新圖示時反而被淘汰（第 3 題）

**檔案**：`L01_LRUGet.swift`（iOS）、`L01_LRUGet.kt`（Android）

**情境**：圖示快取，容量 3。依序放進 BTC、ETH、XRP；使用者又點開 BTC（`get`），接著放進 SOL，快取滿了要淘汰一筆。

**怎麼寫**

1. **Bug 版**：`get` 只用字典（Android 是 `HashMap`）找到節點，回傳它的 `value`，完全不動串列。`put` 已經寫好，淘汰的是串列最後面那一筆。
2. **修正版**：`get` 找到節點後，先 `list.unlink(node)` 把它拿出來，再 `list.insertAfterHead(node)` 放到最前面，最後回傳 `value`；找不到回傳 `nil`（Android 是 `null`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `get(BTC)` 之後 BTC 還排在最後面，放進 SOL 時被淘汰：「最後：快取裡有 [SOL, XRP, ETH]，被淘汰的是 BTC」 |
| 修正版 | BTC 被移到最前面，淘汰的是真正最久沒用的 ETH：「最後：快取裡有 [SOL, BTC, XRP]，被淘汰的是 ETH」 |

**為什麼**：LRU 的「用過」包含讀取。圖示剛被讀過，很可能等一下又要用，卻因為 `get` 沒更新順序而第一個被丟掉，快取就失去意義。修正之後 `get` 多做的事只是改幾個指標，仍然是 O(1)。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L01_LRUGet.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L01_LRUGet.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L01_LRUGet.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L01_LRUGet.kt#fixed
```

### 演練 2：剛更新的價格讀不到，快取還超過容量（第 3 題）

**檔案**：`L02_LRUPut.swift`（iOS）、`L02_LRUPut.kt`（Android）

**情境**：價格快取，容量 2。放進 BTC=1、ETH=2，BTC 的價格更新成 3，最後放進 XRP。

**怎麼寫**

1. **Bug 版**：`put` 不管 key 存不存在，一律建立新節點（`C01Node`），存進字典，再 `insertAfterHead`。超過容量時淘汰最久沒用的那一段已經寫好。
2. **修正版**：key 已經存在，就更新 `node.value`、`unlink` 再 `insertAfterHead`（移到最前面），然後結束；不存在才建立新節點。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 串列上有 3 個節點（容量只有 2），順序 [XRP, BTC, ETH]；剛更新成 3 的 BTC 讀不到：「get(BTC) → 找不到」 |
| 修正版 | 串列只有 2 個節點 [XRP, BTC]，「get(BTC) → 3」 |

**為什麼**：BTC 的舊節點還留在串列上，字典卻已經指向新節點。放進 XRP 超過容量時，淘汰的是串列最後面的舊 BTC 節點，同時把字典裡「BTC」這個 key 也刪了，剛更新的 BTC 就消失；串列和字典的筆數也對不起來。這就是「重複的 key 要更新，不是新增」這個常見陷阱。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L02_LRUPut.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L02_LRUPut.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L02_LRUPut.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L02_LRUPut.kt#fixed
```

### 演練 3：容量變 10 倍，每次 get 也慢 10 倍（第 3 題、第 2 題）

**檔案**：`L03_LRUCost.swift`（iOS）、`L03_LRUCost.kt`（Android）

**情境**：圖示快取。容量 100 與容量 1000 各做 1000 次 `get`（每次都讀得到），數「每次 `get` 平均碰了幾個元素」，看容量變大時成本怎麼變。這是第 2 題「資料變多時，花的時間會怎麼增加」的實測。

**怎麼寫**

1. **Bug 版**：用陣列（Android 是 `ArrayList`）`order` 記使用順序，寫 `touch` 把 key 移到最前面：先從頭找到 key，再移除，最後插到最前面。每一步依註解把碰到的元素數加進 `cost`：找的時候碰了 `index + 1` 個，移除後面的元素往前搬，插入時全部往後搬。
2. **修正版**：字典加雙向鏈結串列。`get` 用字典找到節點，`list.unlink` 再 `list.insertAfterHead`。串列記錄「改了幾次指標」當成本，一次 `get` 固定是 6 次。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 容量 100 平均碰 200 個元素，容量 1000 碰 2000 個：「容量變 10 倍，每次 get 的成本變成 10.0 倍」 |
| 修正版 | 兩個容量都是 6 個：「容量變 10 倍，每次 get 的成本變成 1.0 倍」 |

**為什麼**：只用陣列記順序，「把某一筆移到最前面」要先找到它、再搬移其他元素，是 O(n)；用字典直接找到節點、在串列上改幾個指標，是 O(1)。面試時不用背符號，說「資料變 10 倍，成本也變 10 倍」就是 O(n)，「成本不變」就是 O(1)。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L03_LRUCost.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L03_LRUCost.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L03_LRUCost.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L03_LRUCost.kt#fixed
```

### 演練 4：成交量一樣，排名每次都換（第 4 題）

**檔案**：`L04_TopKTie.swift`（iOS）、`L04_TopKTie.kt`（Android）

**情境**：成交量排行榜取前 3 名。ETH、XRP、ADA 三個幣種的成交量一樣（5100），第 3 名只能留一個。伺服器每次回傳的幣種順序不一定相同（字典轉成陣列，順序會變），演練用兩次更新模擬：第二次的輸入順序剛好反過來。

**怎麼寫**

1. **Bug 版**：只依成交量由大到小排序（iOS 用 `sorted`，Android 用 `sortedByDescending`），取前 k 個。成交量一樣時，沒有第二個排序條件。
2. **修正版**：成交量不同，大的在前；成交量一樣，再依 `symbol` 字母順序排（小的在前），取前 k 個。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 1 次前 3 名 [BTC, DOGE, ETH]，第 2 次 [BTC, DOGE, ADA]：「兩次排名相同：否」 |
| 修正版 | 兩次都是 [BTC, DOGE, ADA]：「兩次排名相同：是」 |

**為什麼**：成交量一樣時，排序結果只剩「輸入的順序」可以決定，而輸入順序每次不一樣。真的 App 裡這個榜單每秒更新很多次，這幾個幣種的位置會一直互換，畫面一直跳。多加一個固定的第二條件（代號字母），結果就只取決於資料本身。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L04_TopKTie.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L04_TopKTie.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L04_TopKTie.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L04_TopKTie.kt#fixed
```

### 演練 5：只要前 10 名，卻排了 2 萬筆（第 4 題、第 2 題）

**檔案**：`L05_TopKHeap.swift`（iOS）、`L05_TopKHeap.kt`（Android）

**情境**：逐筆成交的統計表有 2 萬個項目，要取成交量最高的前 10 名，成交量一樣時依代號字母排序。演練用計數器數「比較了幾次」。（幣種只有幾百個的排行榜，直接排序就夠了；這裡是 n 很大、k 很小的情況。）

**怎麼寫**

1. **Bug 版**：全部排序再取前 10 個。比較函式裡先把計數器加 1，再回傳 `isBetter` 的結果（Android 要回傳 -1、1 或 0）。
2. **修正版**：用大小為 k 的 min-heap（隨時能拿到最小值的結構，`C01MinHeap` 已經寫在 Support 裡）。heap 最上面是「目前保留的 10 筆裡最差的」，所以 heap 比較時要回傳 `isBetter(rhs, lhs)`。還沒滿 10 筆就 `push`；滿了就和 `heap.top` 比一次，新的比較好才 `replaceTop`。最後 `drainAscending()` 是由差到好，反過來就是由好到差。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 前 10 名正確，但比較了 26 萬到 39 萬次（iOS 約 39 萬次、Android 約 26 萬次，兩邊的排序演算法不同） |
| 修正版 | 前 10 名一樣正確，只比較了 20349 次，約等於筆數 |

**為什麼**：全部排序是 O(n log n)，其中大部分比較都花在「永遠不會顯示」的名次上。min-heap 只保留 k 筆，每筆資料先和 heap 最差的比一次，沒有比它好就直接略過，所以是 O(n log k)。但這是 n 很大時才有差別：幣種只有幾百個時，排序最清楚也最好維護，面試時先講這個判斷，再補充 heap。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L05_TopKHeap.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L05_TopKHeap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L05_TopKHeap.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L05_TopKHeap.kt#fixed
```

### 演練 6：缺口多包一根，相鄰區間被當成有缺口（第 5 題）

**檔案**：`L06_MissingRanges.swift`（iOS）、`L06_MissingRanges.kt`（Android）

**情境**：K 線分段載入，區間用 K 線的索引表示，兩端都包含（閉區間）。使用者要看一個範圍，只該請求還沒載入的部分。演練用 5 組固定的資料檢查答案：中間缺兩段、相鄰的兩段、什麼都沒載入、已經全部載入、重疊與範圍外。

**怎麼寫**

1. **Bug 版**：先排序（已經寫好），游標從範圍起點往右掃。遇到空隙時，缺口寫成「游標到這一段的起點」（`cursor...range.lowerBound`，Android 是 `cursor..range.first`），少減了 1；掃過這一段後，游標只移到這一段的終點（`max(cursor, range.upperBound)`），少加了 1。
2. **修正版**：這一段的起點已經超過範圍終點就 `break`；有空隙時缺口停在這一段的前一根（起點 − 1）；掃過之後游標移到下一根（終點 + 1）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 案例 1 得到 [1...3, 4...6, 8...10]，案例 2（相鄰的兩段）得到 [3...4, 6...10]；5 組只答對 2 組：「答對 2/5」 |
| 修正版 | 5 組全部答對：「答對 5/5」 |

**為什麼**：閉區間的兩端都包含，所以缺口的終點是「下一段起點的前一根」，游標則要移到「這一段終點的下一根」，+1、−1 各差一個，整段的結果就全錯。相鄰的 `1...3` 與 `4...6` 中間沒有缺口，少加 1 的游標會誤判成有缺口，多下載一根已經有的資料。這也是為什麼開始寫之前，要先向面試官確認區間包不包含兩端、相鄰算不算連續。

**iOS 解答**

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L06_MissingRanges.swift#bug
```

```lab-solution
ios/Solution/Coding/Coding01-DataStructures/L06_MissingRanges.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/coding/coding01-data-structures/L06_MissingRanges.kt#bug
```

```lab-solution
android/solution/coding/coding01-data-structures/L06_MissingRanges.kt#fixed
```

第 1 題（live coding 的作答順序）是流程題，沒有演練。第 2 題（時間複雜度怎麼用白話講）是觀念題，沒有獨立的演練：演練 3、演練 5 用計數器實際數出「資料變多時成本怎麼變」。

## 常見陷阱

- **LRU 的 `put` 遇到已經存在的 key，當成新的一筆加入。** 應該更新值、移到最前面，數量不變。否則容量會算錯，還會留下重複的節點。
- **LRU 的 `get` 忘了更新使用順序。** 被讀取也算「最近使用」，要移到最前面。
- **沒有處理容量很小的情況。** 容量是 1 時，每次 put 新的 key 都會淘汰前一筆。題目沒說的話，要問容量可不可以是 0。
- **排行榜沒有處理數值相同的情況。** 排序結果不固定，畫面會跳動。
- **沒先確認區間的端點有沒有包含。** 閉區間和半開區間的 +1、−1 寫法不同，寫完才發現會整段重來。

## 面試官想聽到的

1. 選資料結構的理由，以及正確的時間複雜度。
2. 知道平台上現成的工具和它們的限制，例如 `NSCache` 不保證 LRU 順序。
3. 會根據實際的資料量做判斷，而不是一律追求最低的複雜度。
4. 主動提到多執行緒安全和記憶體上限。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 記憶體快取 | `NSCache`：記憶體不足時自動清除，不保證 LRU 順序 | `LruCache`：嚴格照最久沒用淘汰，可以用 `sizeOf` 決定每筆的大小 |
| 依存取順序排列的 Map | 沒有內建，要自己做 | `LinkedHashMap(capacity, 0.75f, true)` |
| Heap（優先佇列） | 標準函式庫沒有；Apple 的 swift-collections 套件有 `Heap` | `PriorityQueue` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 時間複雜度 | time complexity（Big O） | 時間計算量（オーダー） |
| 雙向鏈結串列 | doubly linked list | 双方向連結リスト |
| 雜湊表 | hash map | ハッシュマップ |
| 最久未使用淘汰 | LRU（least recently used） | LRU |
| 堆積（優先佇列） | heap（priority queue） | ヒープ（優先度付きキュー） |
| 閉區間／半開區間 | closed range／half-open range | 閉区間／半開区間 |
