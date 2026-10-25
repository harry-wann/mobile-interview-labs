---
id: sysdesign-market
title: 案例一：即時行情與訂單簿模組
summary: 交易所 App 最核心的模組：一條 WebSocket 連線、好幾個畫面同時要資料、每秒很多則訊息，但主執行緒的時間有限。這個單元一步一步說明怎麼設計，讓資料正確、畫面順暢、耗電和記憶體都在控制內。
priority: P0
day: 5
tags: [system-design, websocket, order-book, market-data]
---

## 重點講解

這個單元照系統設計的作答順序，回答八個問題：

1. 開始設計前，要先確認哪些需求？
2. 伺服器的 WebSocket 協定長什麼樣子？
3. 一條連線、好幾個畫面，要怎麼共用？
4. 連線斷了怎麼辦？
5. 每秒很多則訊息，畫面怎麼保持順暢？
6. 訂單簿畫面要怎麼畫？
7. 有哪些例外情況？怎麼測試和監控？
8. iOS 和 Android 各用什麼工具實作？

### 1. 開始設計前，要先確認哪些需求？

先把假設寫下來（數字可以和面試官確認後調整）：

- **行情列表**：約 40 個交易對，每列顯示最新價、24 小時漲跌、成交量。
- **交易對詳細頁**：訂單簿（買賣各 20 檔，可以切換價格的合併間隔）、最新成交、K 線。
- **延遲**：伺服器推送到畫面顯示，500 毫秒以內；列表每秒最多重畫 10 次。
- **背景**：App 進背景要省電；回到前景 1 到 2 秒內要恢復正確的資料。
- **斷線**：網路不穩時，要讓使用者知道畫面上的資料可能已經過時。

> [!TIP]
> 面試時可以這樣開場：「我先假設 40 個交易對、詳細頁有買賣各 20 檔的訂單簿，延遲目標 500 毫秒，列表每秒最多重畫 10 次。這些數字如果和實際不同，我再調整設計。」

### 2. 伺服器的 WebSocket 協定長什麼樣子？

以 Huobi 相容的交易所公開 API 為例：

- **連線位址**：`wss://api.example-exchange.com/ws`。
- **訊息格式**：伺服器送來的每一則訊息都是 **gzip 壓縮過的二進位資料**，App 要先解壓縮，才能解析裡面的 JSON。
- **訂閱**：送出 `{"sub": "market.btcjpy.depth.step0", "id": "…"}` 開始接收某個主題（topic）的推送；`unsub` 取消；`req` 是只要一次資料、不持續推送。
- **主題的格式**：`market.$symbol.kline.$period`（K 線）、`.depth.$type`（訂單簿）、`.bbo`（最佳買賣價）、`.trade.detail`（成交明細）、`.detail`（24 小時統計）。
- **心跳**：伺服器大約每 5 秒送 `{"ping": 時間}`，App 要回 `{"pong": 同一個時間}`。**連續 2 次沒回，伺服器就會斷線。**
- **訂單簿的推送方式**：Huobi 系的 `depth.stepN` 慣例是每次推送「這個合併間隔下的完整訂單簿」，App 收到後直接取代舊的就好。如果交易所提供的是「先給完整的、之後只給變化的部分」（快照加增量），就要另外檢查序號有沒有跳號，實際以 API 文件為準。

> [!TIP]
> 面試時可以這樣說：「Huobi 相容的交易所，WebSocket 訊息是 gzip 壓縮的二進位資料，要先解壓縮再解析 JSON。用 `sub`、`unsub` 訂閱和取消主題，伺服器大約每 5 秒送一次 ping，App 要回 pong，連續兩次沒回就會被斷線。訂單簿如果每次推完整的資料，就直接取代舊的；如果是增量，就要檢查序號有沒有跳號。」

### 3. 一條連線、好幾個畫面，要怎麼共用？

**結論：整個 App 只開一條 WebSocket。用一個「訂閱管理者」記錄每個主題有幾個畫面在用，第一個畫面要時才訂閱，最後一個畫面離開才取消。**

如果每個畫面各自開一條連線，連線數會隨著畫面變多，伺服器和手機的負擔都會增加，也更容易超過限制。

整體架構：

```text
WebSocket（只有 1 條）
  └─ 解碼器（背景）：gzip 解壓縮 → 解析 JSON → 轉成型別；收到 ping 直接回 pong
       └─ 分派器：依主題把訊息交給對應的 Store
            ├─ TickerStore      每個交易對最新的行情（每 100 毫秒對外發一次）
            ├─ OrderBookStore   每個交易對最新的訂單簿 → 合併、累計、比較差異
            ├─ TradeStore       最近 100 筆成交（固定大小的環狀緩衝區）
            └─ KlineStore       K 線（依週期對齊後合併）
訂閱管理者     記錄每個主題有幾個畫面在用：畫面 A 和 B 都要 btcjpy 的訂單簿，只訂閱一次
連線管理者     連線狀態、斷線重連、監聽網路變化、App 進背景時的處理
ViewModel     讀取 Store，把「畫面直接能用」的狀態交給畫面
```

**訂閱管理者的細節**：每個主題記一個計數（reference count，有幾個畫面在用）。畫面出現時 +1，從 0 變 1 才真的送出 `sub`；畫面離開時 −1，變成 0 時**等 3 秒**再送 `unsub`。為什麼要等？使用者常在列表和詳細頁之間來回切換，等一下可以避免頻繁地訂閱、取消。

> [!TIP]
> 面試時可以這樣說：「整個 App 只開一條 WebSocket，由訂閱管理者記錄每個主題有幾個畫面在用，第一個畫面需要時才訂閱，最後一個離開後延遲幾秒才取消，避免使用者來回切換時一直重新訂閱。」

### 4. 連線斷了怎麼辦？

**結論：把連線管理寫成一個明確的狀態機，斷線後等待時間逐次加倍再重連；重連成功後，重新送出所有還在用的訂閱，並先拿一次完整資料，不要讓畫面停在斷線前的數字。**

狀態機（state machine）的意思是：把連線可能的狀態列出來，並規定每個狀態在什麼事件下會變成哪個狀態。這樣不會出現「同時在連線又同時在等待重試」這種混亂。

```text
idle（尚未連線） → connecting（連線中） → connected（已連線）
                      ↑                         │ 斷線或錯誤
                      │                         ↓
                      └──── waitingRetry（等待 delay 後重試）
  網路恢復、App 回到前景：不等 delay，立刻重試
  App 進背景超過 30 秒：closed（取消全部訂閱、關閉連線）
```

重連的細節：

- **等待時間逐次加倍**（指數退避，exponential backoff）：1、2、4、8 秒……最多 30 秒。每次再加上 0 到 20% 的隨機誤差（jitter），避免伺服器恢復時，所有使用者在同一瞬間一起重連。
- **網路恢復時立刻試一次**：iOS 用 `NWPathMonitor`、Android 用 `ConnectivityManager.NetworkCallback` 監聽網路變化，網路一恢復就不等了。
- **重連成功後**：重新送出所有計數還大於 0 的訂閱；並用 REST 或 `req` 拿一次完整資料，把斷線期間錯過的變化補回來。

> [!TIP]
> 面試時可以這樣說：「我會把連線寫成狀態機，斷線後用指數退避加 jitter 重連，網路恢復時立刻重試。重連成功後重新訂閱所有還在用的主題，並先拿一次完整資料，避免畫面停在斷線前的數字。」

### 5. 每秒很多則訊息，畫面怎麼保持順暢？

**結論：把「收到訊息」和「更新畫面」分開。Store 收到多少訊息都只更新「最新狀態」，對畫面則固定每 100 毫秒發一次最新的狀態。**

估算：40 個交易對、每個每秒 2 次，一秒 80 則訊息。如果每則都更新畫面，主執行緒每秒要處理 80 次，加上解析、排序，畫面一定卡。

做法：

- **背景合併**：Store 在背景執行緒收訊息，同一個交易對的新訊息直接蓋掉舊的，只保留最新的一份。
- **固定節奏發出**：每 100 毫秒（或配合螢幕更新的節奏：iOS 的 `CADisplayLink`、Android 的 `Choreographer`）把最新狀態交給畫面一次。中間被蓋掉的價格不用顯示，使用者看到的永遠是最新價格。
- **Swift**：`AsyncStream(bufferingPolicy: .bufferingNewest(1))`（緩衝區只留最新的一筆），或 Combine 的 `throttle(for:scheduler:latest: true)`。
- **Kotlin**：`StateFlow` 本身就只保留最新值，再加上 `sample(100)` 固定每 100 毫秒取一次。

> [!TIP]
> 面試時可以這樣說：「收到訊息的頻率和畫面更新的頻率要分開。Store 在背景只保留每個交易對的最新狀態，每 100 毫秒交給畫面一次，所以不管伺服器推多快，主執行緒每秒最多只更新 10 次。」

### 6. 訂單簿畫面要怎麼畫？

**結論：所有計算在背景做完，主執行緒只拿到「20 列要顯示的文字和比例」。**

在背景先算好：

- **依合併間隔整理價格**：例如間隔 100 円時，把 10,001,050 円和 10,001,020 円的掛單合併到同一列。
- **取前 20 檔**。
- **累計數量**：從最佳價格往外，數量一路加總，用來畫每一列後面的深度條（橫條越長代表這個價格以內的掛單越多）。
- **和上一版比較**：找出哪幾列的數字變了，用來做短暫的閃爍提示。
- **格式化文字**：依交易對的精度把數字轉成字串。建立格式化工具（`NumberFormatter`）很花時間，不要在主執行緒、更不要每一列都建立一次。

畫面上：

- 固定 20 列，用價格或固定的列號當列表的 key（Compose 的 `LazyColumn` 的 `key`、SwiftUI 的 `id`），讓框架知道哪一列是哪一列，只重畫有變化的列。
- 數字用等寬數字字型（tabular figures，每個數字寬度一樣），數字變化時文字寬度不會跳來跳去。

> [!TIP]
> 面試時可以這樣說：「訂單簿的合併、取前 20 檔、累計數量、深度條比例和文字格式化，我都在背景算好，主執行緒只拿到 20 列要顯示的內容。列表用價格當 key，數字用等寬字型，避免畫面跳動。」

### 7. 有哪些例外情況？怎麼測試和監控？

| 情況 | 怎麼處理 |
|---|---|
| 畫面卡住，來不及回 pong | ping／pong 在背景執行緒處理，完全不經過主執行緒 |
| 斷線超過 5 秒 | 顯示「連線中斷」，數字變灰色，讓使用者知道資料可能過時；重連後先拿完整資料 |
| 收到已經取消訂閱的主題的訊息 | 分派器直接丟掉 |
| App 進背景 | 先停止更新畫面；30 秒後取消訂閱並關閉連線；回前景時重連並拿完整資料 |
| 判斷資料新舊 | 用伺服器送來的時間 `ts`，不要用手機的時間（手機時間可能不準） |
| 交易對很多 | 列表只訂閱畫面上看得到的幾列，再多預先載入幾列；或改用 REST 的全部交易對行情 API（例如 `/market/tickers`）定期拉取 |

**測試**：

- 單元測試：用事先錄下來的 gzip 訊息測試解碼器；測試分派器、訂單簿的合併和比較；測試重連的等待時間（把時鐘和隨機數注入進去，結果才固定）。
- 壓力測試：寫一個假的 WebSocket 伺服器，每秒推 200 則訊息，量畫面的掉幀率和記憶體用量。

**上線後監控**：重連次數、每次斷線多久、訊息延遲（伺服器時間 `ts` 和收到時間的差）、每秒訊息數、主執行緒卡頓的次數。

> [!TIP]
> 面試時可以這樣說：「例外情況我會想到幾件事：ping／pong 在背景處理，才不會因為主執行緒忙碌而被斷線；斷線超過幾秒就把數字變灰，重連後先拿完整資料；判斷資料新舊用伺服器送來的 `ts`。測試上我會用錄下來的 gzip 訊息測解碼器，再用假的伺服器做壓力測試；上線後監控重連次數、訊息延遲和主執行緒卡頓的次數。」

### 8. iOS 和 Android 各用什麼工具實作？

**結論：兩個平台都有現成的 WebSocket 工具，要特別注意的是 iOS 的 gzip 解壓縮不能直接用 `NSData.decompressed(using: .zlib)`。**

| 主題 | iOS | Android |
|---|---|---|
| WebSocket | `URLSessionWebSocketTask` | OkHttp 的 `WebSocket`，二進位訊息從 `onMessage(webSocket, bytes: ByteString)` 收到 |
| gzip 解壓縮 | 注意：`NSData.decompressed(using: .zlib)` 只處理不含 gzip 標頭的資料，直接丟 gzip 進去會失敗。要用 zlib 的 `inflateInit2`（windowBits 設成 16 + 15），或自己去掉 gzip 的標頭和結尾 | `GZIPInputStream(bytes.toByteArray().inputStream())` |
| 背景處理 | 一個專用的 actor 或 serial queue（照順序一個一個處理） | `Dispatchers.Default.limitedParallelism(1)`（一次只跑一個，保證順序） |
| 對畫面發送狀態 | `AsyncStream`（只留最新一筆）、Combine | `StateFlow`、`sample` |
| 監聽網路變化 | `NWPathMonitor` | `ConnectivityManager.NetworkCallback` |

> [!TIP]
> 面試時可以這樣說：「WebSocket 在 iOS 用 `URLSessionWebSocketTask`，在 Android 用 OkHttp 的 `WebSocket`。Huobi 相容的交易所，訊息是 gzip 壓縮的，iOS 的 `decompressed(using: .zlib)` 不處理 gzip 標頭，要改用 zlib 的 `inflateInit2` 或自己去掉標頭。解析和合併放在專用的 actor 或 serial queue，Android 用 `Dispatchers.Default.limitedParallelism(1)`（一次只跑一個，保證順序），再用 `AsyncStream` 或 `StateFlow` 把最新狀態交給畫面。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Design02MarketData**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Design02MarketData**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Design/Design02-MarketData/`，解答在 `labs/ios/Solution/Design/Design02-MarketData/`；Android 你寫在 `labs/android/practice/design/design02-market-data/`，解答在 `labs/android/solution/design/design02-market-data/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的連線**：這個單元的演練都是假的伺服器、編好的事件序列，時間從參數傳進來（不用等真實的時鐘），每次執行的結果都一樣。只有演練 3 會量真實的時間，門檻訂得很寬鬆。

這個單元的題目，前面的單元大多已經分別練過（清單放在這一段的最後）。這裡的 4 個演練只練「把好幾個元件串成一個系統」才會出現的問題：每個元件單獨看都沒錯，接在一起才出錯。

### 演練 1：離開詳細頁，列表上的價格就停了（第 3 題）

**檔案**：`L01_RefCount.swift`（iOS）、`L01_RefCount.kt`（Android）

**情境**：整個 App 只開一條 WebSocket，訂閱管理者替所有畫面送 `sub`、`unsub`。行情列表需要 `market.btcjpy.bbo`、`market.ethjpy.bbo`；BTC 詳細頁需要 `market.btcjpy.bbo`、`market.btcjpy.depth.step0`，兩個畫面都要 BTC 的最佳買賣價。劇本：列表先出現 → 詳細頁出現 → 伺服器推送一次 `market.btcjpy.bbo` → 使用者按返回，詳細頁離開 → 伺服器再推送一次（列表還開著，還在看 BTC 價格）→ 列表也離開。假伺服器的規則：同一個 topic 每被 `sub` 一次，每次推送就多送一份；`unsub` 會取消這個 topic 的全部訂閱。

**怎麼寫**

1. **Bug 版**：`subscribe` 每次都送 `sub`、`unsubscribe` 每次都送 `unsub`，不計數。
2. **修正版**：用字典 `counts` 記每個 topic 有幾個畫面在用：加 1 之後剛好是 1 才送 `sub`；減 1 之後變成 0 才送 `unsub`，並把 topic 從字典移除。（iOS、Android 做法相同。）

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `market.btcjpy.bbo` 被訂了兩次，推送收到 2 份；詳細頁離開時又把它整個退訂，列表收到 0 份：「最後：sub 一共送出 4 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 2 份，詳細頁離開後收到 0 份；全部離開後伺服器上剩 0 個訂閱」 |
| 修正版 | sub 只送 3 則，推送一直收到 1 份：「最後：sub 一共送出 3 則（不同的 topic 只有 3 個）；兩個畫面都開著時推送收到 1 份，詳細頁離開後收到 1 份；全部離開後伺服器上剩 0 個訂閱」 |

**為什麼**：畫面各自送 `sub`、`unsub` 的時候，沒有人知道「別的畫面還要不要」。Bug 版有兩種症狀：同一個 topic 被訂兩次（每則訊息收到兩份、處理兩次，白白浪費流量和電力），以及先離開的畫面把還有人在用的 topic 退掉（價格不動了，但連線沒斷、也沒有錯誤，使用者只看到「價格停了」）。引用計數（reference count，記錄有幾個畫面在用）讓「第一個要的畫面才訂閱、最後一個離開的畫面才退訂」成為唯一的規則，也就是筆記第 3 題的做法。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design02-MarketData/L01_RefCount.swift#bug
```

```lab-solution
ios/Solution/Design/Design02-MarketData/L01_RefCount.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design02-market-data/L01_RefCount.kt#bug
```

```lab-solution
android/solution/design/design02-market-data/L01_RefCount.kt#fixed
```

### 演練 2：列表和詳細頁來回切換，訂閱一直重訂（第 3 題）

**檔案**：`L02_GracePeriod.swift`（iOS）、`L02_GracePeriod.kt`（Android）

**情境**：引用計數已經寫好了（演練 1 練過）。BTC 詳細頁獨自使用 `market.btcjpy.depth.step0`（訂單簿）。使用者在列表和詳細頁之間來回切換：0 ms 進詳細頁 → 1000 ms 返回列表 → 2000 ms 又進詳細頁 → 4000 ms 返回列表，之後不再進去。時間是演練編好的，由 `tick` 往前走（真的 App 是用計時器；這裡不用等真實的時鐘）。延遲退訂的等待時間是 3000 ms。你要決定的是：計數變成 0 的時候，什麼時候才送 `unsub`。

**怎麼寫**

1. **Bug 版**：計數變成 0（`decrement` 回傳 true）就馬上送 `unsub`。
2. **修正版**：計數變成 0 時，只把期限（`ms + graceMs`，Android 是 `atMs + L02_GRACE_MS`）記進字典 `unsubAt`，先不退訂；在期限內又有畫面要（`subscribe`），就把它從 `unsubAt` 移除，不用再送 `sub`；`tick` 發現期限到了，才真的送 `unsub`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | sub 2 則、unsub 2 則，2000 ms 回到詳細頁時訂閱已經不在：「最後：sub 2 則、unsub 2 則；第 2 次回到詳細頁時訂閱已經退訂；全部離開後伺服器上剩 0 個訂閱」 |
| 修正版 | 只有 0 ms 的 1 則 sub 和 7000 ms 的 1 則 unsub：「最後：sub 1 則、unsub 1 則；第 2 次回到詳細頁時訂閱還在；全部離開後伺服器上剩 0 個訂閱」，log 裡有一行「伺服器收到：7000 ms unsub market.btcjpy.depth.step0」 |

**為什麼**：使用者在列表和詳細頁之間來回切換是很常見的操作。計數一變 0 就退訂，每次回來都要重新 `sub`：多一輪網路來回，而且從重新訂閱到收到第一筆資料之間，畫面是空白的。等 3 秒再退訂，來回切換的期間訂閱一直都在；代價只是最後離開之後，多佔 3 秒的訂閱。Kotlin 的 `stateIn`／`shareIn` 搭配 `SharingStarted.WhileSubscribed(stopTimeoutMillis = 3000)` 就是現成的做法（Kotlin05 演練 1 用過 `WhileSubscribed(5_000)`）；手寫一次，比較容易看懂它在做什麼。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design02-MarketData/L02_GracePeriod.swift#bug
```

```lab-solution
ios/Solution/Design/Design02-MarketData/L02_GracePeriod.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design02-market-data/L02_GracePeriod.kt#bug
```

```lab-solution
android/solution/design/design02-market-data/L02_GracePeriod.kt#fixed
```

### 演練 3：開著訂單簿詳細頁，行情列表跟著變慢（第 5 題）

**檔案**：`L03_SlowSubscriber.swift`（iOS）、`L03_SlowSubscriber.kt`（Android）

**情境**：分派器收到 socket 傳來的價格，要交給兩個畫面：行情列表（處理很快）和 BTC 訂單簿詳細頁（每筆要算 20 檔、格式化，約 40 毫秒）。一瞬間來了 8 筆價格（編號 1 到 8），都在 socket 的佇列（Android 是執行緒）上交給分派器。看行情列表什麼時候收到最後一筆（#8），以及詳細頁處理了幾筆。這個演練會量真實的時間，但門檻很寬鬆：Bug 版要晚 150 毫秒以上，修正版要在 100 毫秒內。

**怎麼寫**

1. **Bug 版**：`register` 把 handler 存進清單；`publish` 在 socket 的佇列上，依序直接呼叫每個 handler。
2. **修正版**：
   - iOS：`register` 替每個畫面建立自己的緩衝區 `AsyncStream.makeStream(of: Int.self, bufferingPolicy: .bufferingNewest(1))`（只留最新一筆），再開一個 `Task.detached`，用 `for await` 讀自己的 stream、呼叫 handler；`publish` 只對每個畫面的 continuation 呼叫 `yield`。
   - Android：`register` 替每個畫面建立自己的 `Channel<Int>(Channel.CONFLATED)`（只留最新一筆），再用 `scope.launch` 開一個消費者協程，讀自己的 Channel、呼叫 handler；`publish` 只對每個畫面的 Channel 呼叫 `trySend`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 行情列表收到 #8 時，距離這批價格送到約 300 毫秒（實測：iOS Debug 302 ms、Release 306 ms；Android 在電腦上跑 306 到 321 ms），詳細頁把 8 筆全部處理：「最後：行情列表被拖慢了（最後一筆晚了 150 毫秒以上）；詳細頁把 8 筆全部依序處理，最後處理的是 #8」 |
| 修正版 | 行情列表 0 到 2 毫秒就收到 #8，詳細頁只處理 2 筆（#1 和 #8）：「最後：行情列表不受影響（最後一筆在 100 毫秒內收到）；詳細頁只處理最新的幾筆（少於 8 筆），最後處理的是 #8」 |

**為什麼**：分派器如果在 socket 的佇列上一個一個呼叫畫面的處理函式，所有畫面就共用同一條「生產線」：詳細頁處理一筆要 40 毫秒，後面每一筆都要等它，行情列表明明很快，最後一筆也跟著晚了 7 × 40 毫秒。這還會拖到心跳（Advanced05 演練 1）。每個畫面有自己的緩衝區和消費者之後，慢的畫面只處理最新的幾筆，快的畫面完全不受影響。行情只在乎最新的價格，所以少處理幾筆沒有問題；成交明細這類「不能丟」的資料不適用這個做法（見 Advanced05 第 4 題）。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design02-MarketData/L03_SlowSubscriber.swift#bug
```

```lab-solution
ios/Solution/Design/Design02-MarketData/L03_SlowSubscriber.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design02-market-data/L03_SlowSubscriber.kt#bug
```

```lab-solution
android/solution/design/design02-market-data/L03_SlowSubscriber.kt#fixed
```

### 演練 4：切到詳細頁，畫面停在舊價格不更新（第 3 題）

**檔案**：`L04_CacheThenListen.swift`（iOS）、`L04_CacheThenListen.kt`（Android）

**情境**：Repository 把最新的價格存在快取裡，WebSocket 每推來一筆就更新快取，並通知已登記的畫面（這個 `receive` 已經寫好）。畫面打開詳細頁時呼叫 `observe(symbol, onPrice)`：要先拿到快取的價格（馬上有東西可以顯示），再收到之後的推送。快取裡是 10000000 円。訂閱 topic 要等伺服器回應（iOS 是 `await`，Android 是 `suspend`），就在這段時間裡，伺服器剛好推出一筆新價格 10000500 円。這個交易對不常成交，下一筆推送要很久以後才會來。

**怎麼寫**

1. **Bug 版**：`observe` 依序做三件事：先把快取交給畫面、接著訂閱（要等伺服器回應）、最後才把畫面的 callback 登記進 `listeners`。
2. **修正版**：改成先登記 callback、再把快取交給畫面（這兩步中間不能有 `await`／`suspend`）、最後才訂閱。（iOS、Android 做法相同。）

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面只收到 10000000 円，快取裡卻已經是 10000500 円：「最後：畫面顯示 10000000 円，快取裡是 10000500 円」 |
| 修正版 | 畫面先收到 10000000 円（快取），再收到 10000500 円（推送）：「最後：畫面顯示 10000500 円，快取裡是 10000500 円」 |

**為什麼**：「先拿快取，再接推送」看起來是兩件事，其實是一個要連在一起的動作。中間只要有一個會等待的呼叫（訂閱要等伺服器回應），這段時間推來的資料就會被 Repository 收進快取，卻沒有人通知畫面：畫面和快取不一致，而且沒有任何錯誤，要等下一筆推送才會自己好起來。修正版把「登記監聽」放在最前面，之後不管推送在哪個時間點來，畫面都收得到；登記和給快取之間沒有等待，所以也不會被插隊。Exchange04 演練 5 練過另一種順序問題（重連之後，快照比推送晚到），那裡靠序號判斷新舊；這裡是畫面開啟時，快取與推送要怎麼接起來。

**iOS 解答**

```lab-solution
ios/Solution/Design/Design02-MarketData/L04_CacheThenListen.swift#bug
```

```lab-solution
ios/Solution/Design/Design02-MarketData/L04_CacheThenListen.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/design/design02-market-data/L04_CacheThenListen.kt#bug
```

```lab-solution
android/solution/design/design02-market-data/L04_CacheThenListen.kt#fixed
```

第 1 題（需求）是說明題，第 7 題的測試與監控（壓力測試、重連次數、訊息延遲）是流程與度量題，沒有演練。其他題目的 bug，前面的單元已經分別練過，這個單元不重複：

- **第 2 題**（WebSocket 協定：gzip、ping／pong、`sub`／`unsub`／`req`、主題格式）：Exchange03 演練 1 到 7。
- **第 3 題**其他面向：兩個元件各開一條 WebSocket（Kotlin05 演練 1）、離開畫面後訂閱還在（Swift06 演練 4、Kotlin05 演練 7）、兩個畫面各只收到一半價格（Swift06 演練 3）、重連時每個畫面各自重連並重複訂閱（Exchange04 演練 7）。
- **第 4 題**（連線斷了怎麼辦）：重連狀態機（Exchange04 演練 1）、等待時間與歸零（Exchange04 演練 2、3）、半開連線（Exchange04 演練 4）、重連後的恢復順序（Exchange04 演練 5）、重連後重新訂閱（Exchange03 演練 7）、回前景先抓快照再訂閱（Platform01 演練 7）。
- **第 5 題**（收訊息的頻率和更新畫面的頻率分開）：Swift06 演練 1、Kotlin05 演練 8、Advanced05 演練 2、Exchange06 演練 1。
- **第 6 題**（訂單簿畫面）：Exchange05 演練 1 到 8，以及 Exchange06 演練 2 到 4（列表的 key、閃爍方向、數字格式）。
- **第 7 題**的例外情況：心跳不能被主執行緒或解析擋住（Exchange03 演練 3、Advanced05 演練 1）、App 在背景不該自己重連（Exchange04 演練 1）、用伺服器時間判斷新舊（Exchange04 演練 6）。
- **第 8 題**（工具）：iOS 的 gzip 解壓縮（Exchange03 演練 2）。

## 常見陷阱

- **每個畫面各開一條 WebSocket。** 連線數越來越多，浪費資源，也容易超過伺服器的限制。只開一條，用訂閱管理者共用。
- **收到一則訊息就重畫一次畫面。** 每秒幾十次的更新會讓主執行緒忙不過來。要在背景合併，固定節奏更新。
- **重連後沒有重新訂閱，或沒有先拿完整資料。** 畫面會停在斷線前的數字，使用者以為價格沒動。
- **在主執行緒做 gzip 解壓縮和 JSON 解析。** 主執行緒一忙，畫面卡頓，還可能來不及回 pong 而被斷線。

## 面試官想聽到的

1. 一條連線、多個畫面共用，用計數管理訂閱。
2. 收到訊息的頻率和畫面更新的頻率分開。
3. 連線狀態機和重連策略的細節（等待加倍、上限、隨機誤差、網路恢復立刻重試）。
4. 資料正確性：用伺服器時間判斷新舊、重連後先拿完整資料、有增量時檢查序號。
5. 量得出來的指標：延遲、重連次數、掉幀率。

> [!FIELD]
> 可以連結自己的實務經驗：社交 App 的即時聊天用 Socket.IO，處理過斷線重連與背景、前景切換；即時行情 App 用 Server-Sent Events，搭配 TradingView Lightweight Charts 顯示 K 線。
> 面試時可以這樣說：「我做過聊天和行情兩種即時資料。換成交易所的高頻行情，我會用一條連線加訂閱計數、背景合併、固定節奏更新畫面，重連用指數退避加 jitter。」

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 訂單簿 | order book | 板（いた） |
| 訂閱 | subscribe | 購読（サブスクライブ） |
| 心跳 | heartbeat（ping／pong） | ハートビート |
| 指數退避 | exponential backoff | 指数バックオフ |
| 狀態機 | state machine | 状態遷移（ステートマシン） |
| 引用計數 | reference count | 参照カウント |
