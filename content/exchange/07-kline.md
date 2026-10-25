---
id: exchange-kline
title: K 線與圖表：成交怎麼變成 K 線、日界與時區、EMA 和 RSI 怎麼算、圖表怎麼畫才不卡
summary: K 線看起來只是畫幾根柱子，面試會追問的是細節：成交量的單位是什麼、日 K 以哪個時區切、成交怎麼聚合成開高低收量、均線和 RSI 怎麼算與更新、圖表放在 WebView 時資料怎麼傳。這個單元一個一個說明，並連到 TradingView Lightweight Charts 的實務經驗。
priority: P0
day: 4
tags: [kline, ohlcv, timezone, ema, rsi, lightweight-charts, webview]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 這套交易所 API 的 K 線資料長什麼樣子？每個欄位是什麼意思？
2. 怎麼把一筆一筆的成交聚合成 K 線？
3. 日 K 的邊界在哪裡？UTC（世界協調時間）和 JST（日本標準時間）差在哪？
4. 移動平均（SMA 簡單移動平均、EMA 指數移動平均）和 RSI（相對強弱指標）怎麼算？即時更新時要注意什麼？
5. 切換週期、接上即時推送時，歷史資料和推送怎麼接起來？
6. 資料量很大時，圖表怎麼只畫看得到的範圍？圖表元件怎麼選？
7. 用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？

### 1. 這套交易所 API 的 K 線資料長什麼樣子？每個欄位是什麼意思？

**K 線（candlestick，又叫蠟燭圖）把一段時間內的所有成交，濃縮成開盤價、最高價、最低價、收盤價和成交量。** 這五個值的英文縮寫是 OHLCV（Open、High、Low、Close、Volume）。這套 API 的 K 線欄位最容易搞混的是兩組：`id` 是「這根 K 線的起點時間，單位是秒」，`amount` 和 `vol` 一個是數量、一個是金額。

資料來自這套交易所的公開 API 文件（API 是 application programming interface，App 和伺服器溝通的規格）：

| 項目 | 內容 |
|---|---|
| REST | `GET /market/history/kline?symbol=&period=&size=`，回傳 `data` 陣列。REST 是用一個 HTTP（網頁使用的通訊協定）請求一次取回資料的介面 |
| WebSocket | 訂閱 `market.$symbol.kline.$period`（這個字串叫 topic，意思是要訂閱的主題），推送內容放在 `tick` 欄位。`tick` 是 API 的欄位名稱，不是「一筆成交」的意思。`req` 可以帶 `from`、`to`（單位秒，UTC）取指定區間的歷史 |
| period（週期） | `1min`、`5min`、`15min`、`30min`、`60min`、`1day`、`1mon`、`1week`、`1year`。文件列出的只有這些，**沒有 3min、4hour**；文件的範例裡訂閱 `3min` 會得到 invalid topic 的錯誤 |

| 欄位 | 意思 | 範例（btcjpy 日 K） |
|---|---|---|
| `id` | 這根 K 線的**起點時間，單位是秒**（不是毫秒） | `1632931200` |
| `open`、`close` | 區間內第一筆、最後一筆成交的價格 | 4,663,135／4,815,018 |
| `low`、`high` | 區間內的最低價、最高價 | 4,580,081／4,830,000 |
| `amount` | 區間內成交的**標的數量**（比特幣，簡稱 BTC） | 約 6.63 |
| `vol` | 區間內成交的**計價金額**（日圓，簡稱 JPY） | 約 31,208,129 |
| `count` | 區間內的成交筆數 | 7,534 |

怎麼確定 `vol` 是日圓金額、`amount` 是 BTC 數量？用範例反推：`vol ÷ amount ≈ 31,208,129 ÷ 6.63 ≈ 4.70M`，這個數字剛好落在當根的最低價 4,580,081 和最高價 4,830,000 之間，正是這段時間的平均成交價。所以顯示「成交量」時，一定要標清楚單位是 BTC 還是 JPY，否則兩者會差好幾個數量級。

```swift
let id: Int64 = 1_632_931_200
// 輸出：2021-09-29 16:00:00 +0000（秒，正確）
print(Date(timeIntervalSince1970: TimeInterval(id)))
// 輸出：1970-01-19 21:35:31 +0000（誤當成毫秒，錯了）
print(Date(timeIntervalSince1970: TimeInterval(id / 1000)))
```

還有幾個資料細節：

- **REST 的範例資料是新的在前**（`1632931200` 排在 `1632844800` 前面）。多數圖表元件要求時間由舊到新，所以進圖表之前要先排序，排序後再檢查有沒有重複的 `id`。
- **`size` 的範圍：** 參數表寫「150 到 2000」，但錯誤訊息的範例是 `valid range: [1,2000]`，兩者不一致。上限 2000 比較確定，下限（未證實），以實測為準。
- **沒有成交的區間：** 文件的 WebSocket 範例出現過 `count: 0`、`amount: 0`，而且 `open`、`close`、`low`、`high` 都相同的 K 線，代表這段時間沒有成交。伺服器會不會對沒有成交的區間推送或補資料，REST 是否補洞（未證實）。畫面端要能處理中間缺了幾根的情況。
- **要拉更舊的歷史：** REST 文件只有 `size` 參數；用 WebSocket 的 `req` 帶 `from`、`to` 可以指定區間。

> [!TIP]
> 面試時可以這樣說：「這套交易所 K 線的 `id` 是這根 K 線的起點時間，單位是秒；`amount` 是 BTC 的數量，`vol` 是日圓的成交金額，我用範例反推過：`vol` 除以 `amount` 剛好落在最高價和最低價之間。REST 回來是新的在前，進圖表前我會先排序。」

### 2. 怎麼把一筆一筆的成交聚合成 K 線？

**先算出每一筆成交屬於哪一根 K 線（算出這根 K 線的起點時間，程式裡常叫 bucket start），同一根的成交依時間排序後：最早的價格是 `open`，最晚的是 `close`，最大和最小是 `high` 和 `low`，數量加總是 `amount`，「價格 × 數量」加總是 `vol`，筆數是 `count`。**

**為什麼需要自己聚合？** 伺服器只提供固定的週期（沒有 3 分鐘、4 小時），而且兩次推送之間，你可能想用最新的成交更新最後一根。但伺服器的 K 線才是權威，本機聚合只用來補足空檔，不要把兩種來源的資料混在同一根 K 線裡。

**K 線起點的算法。** 把時間戳記除以週期、取整數、再乘回週期，就得到這個週期的起點。不同日界要多一個偏移量：

```text
K 線起點（UTC 秒）= floorDiv(秒 + 偏移, 週期秒) × 週期秒 − 偏移
```

其中毫秒要先轉成秒：`秒 = floorDiv(毫秒, 1000)`。`floorDiv` 是「往負無限大取整」的除法，值為正時和一般的除法一樣，值為負時不同：

```swift
func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
    let q = a / b
    // 有餘數而且結果是負的，再往下一格
    return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
}

// 輸出：-3 -4（一般除法往 0 取整，floorDiv 往負無限大取整）
print(-7 / 2, floorDiv(-7, 2))

func bucketStart(
    tsMs: Int64,
    periodSec: Int64,
    originOffsetSec: Int64
) -> Int64 {
    let sec = floorDiv(tsMs, 1000)
    return floorDiv(
        sec + originOffsetSec,
        periodSec
    ) * periodSec - originOffsetSec
}

// 1 分鐘 K，成交時間是 1632931259999 毫秒（59.999 秒）
// 輸出：1632931200（還在這一分鐘內）
print(bucketStart(tsMs: 1_632_931_259_999, periodSec: 60, originOffsetSec: 0))
```

Kotlin 用 `Math.floorDiv(a, b)`。為什麼不能直接用 `/`？因為偏移之後、或遇到負的時間，一般除法會往 0 取整，邊界會錯一格。

**一個完整的例子。** 1 分鐘 K 線，收到四筆成交，順序是亂的（重連補資料時常這樣）：

| 收到的順序 | 成交時間（這一分鐘內的第幾秒） | 價格（JPY） | 數量（BTC） |
|---|---|---|---|
| 1 | 第 40 秒 | 4,800,000 | 0.2 |
| 2 | 第 5 秒 | 4,790,000 | 0.1 |
| 3 | 第 55 秒 | 4,810,000 | 0.05 |
| 4 | 第 20 秒 | 4,780,000 | 0.3 |

先依時間排序，再算：`open` ＝ 第 5 秒那筆的 4,790,000，`close` ＝ 第 55 秒那筆的 4,810,000（**不是**收到的第一筆和最後一筆），`high` ＝ 4,810,000，`low` ＝ 4,780,000，`amount` ＝ 0.1 + 0.3 + 0.2 + 0.05 ＝ 0.65 BTC，`vol` ＝ 479,000 + 1,434,000 + 960,000 + 240,500 ＝ 3,113,500 JPY，`count` ＝ 4。

聚合時的細節：

- **同一毫秒的成交**，用成交編號決定先後。排序的鍵是（時間, 成交編號）。
- **去重。** 重連或補資料時，可能重送相同的成交，用成交編號去重，否則 `count` 和 `vol` 會重複計算。要用的是 WebSocket 的 `tradeId`（REST 是 `trade-id`），**不要用逐筆資料裡的 `id`**。那個 `id` 是 27 位數（文件範例 `137005445109359286410323766`），超出 `Int64` 和 `Double` 能精確表示的範圍，一般的 JSON（常見的文字資料格式）解析會失真；文件的 REST 範例裡，兩筆不同的成交甚至有相同的 `id`（`trade-id` 不同）。非用不可時，要當字串解析。
- **遲到的成交：** 屬於已經收盤的 K 線時，要嘛重算那一根並通知圖表，要嘛忽略並以伺服器的 K 線為準。不要悄悄改動已收盤的 K 線，卻不通知圖表。
- **金額計算：** 價格和數量用整數或十進位型別（見「精度與金額」單元）。本單元的程式題用整數：價格是日圓整數，數量是 1e-8 BTC 的整數（`100_000_000` 代表 1 BTC），乘出來的 `vol` 單位是「日圓 × 1e-8 BTC」，顯示時要除以 1 億。Double 累加金額，誤差會被放大。

> [!TIP]
> 面試時可以這樣說：「本機聚合時，我會先去重，再依（時間, 成交編號）排序，然後用 floor 除法算 K 線的起點時間。open 和 close 看的是時間最早和最晚的成交，不是陣列的第一筆和最後一筆。伺服器的 K 線是權威，本機聚合只補空檔。」

### 3. 日 K 的邊界在哪裡？UTC 和 JST 差在哪？

**一天從幾點開始，是由資料來源決定的，App 不要自己寫死。** 這套 API 文件的日 K 範例，起點是 16:00 UTC，也就是 UTC+8 的午夜（JST 的 01:00），既不是 UTC 的 0 點，也不是日本時間的 0 點。但這是 2021 年的範例資料，現行規則（未證實）。

先釐清兩個縮寫：UTC 是世界協調時間（Coordinated Universal Time），是時間的基準；JST 是日本標準時間（Japan Standard Time），等於 UTC+9，日本沒有夏令時間。

怎麼從範例看出日界？文件的日 K 範例 `id` 是 `1632931200` 和 `1632844800`，對 86400（一天的秒數）取餘數，都是 57600，也就是 16 小時。表示這兩根 K 線都從 16:00 UTC 開始。16:00 UTC 加 8 小時是隔天的 00:00，所以是 UTC+8 的午夜。

同一筆成交，用不同的日界，會落在不同的日 K。以 2021-09-29 20:00 UTC 的一筆成交為例：

| 日界 | 偏移（秒） | 這筆成交所屬那根日 K 的起點（`id`） | 對應的 UTC 時間 |
|---|---|---|---|
| UTC 的 0 點 | 0 | `1632873600` | 9/29 00:00 |
| UTC+8 的 0 點（文件範例） | 28,800 | `1632931200` | 9/29 16:00 |
| JST 的 0 點 | 32,400 | `1632927600` | 9/29 15:00 |

```swift
// 2021-09-29 20:00:00 UTC（毫秒）
let t: Int64 = 1_632_945_600_000
for offset in [0, 28_800, 32_400] as [Int64] {
    print(bucketStart(tsMs: t, periodSec: 86_400, originOffsetSec: offset))
}

// 輸出（每行一個）：1632873600、1632931200、1632927600
```

這就是日界不能寫死的原因。實務上的做法：

- **畫伺服器給的 K 線，一律使用回傳的 `id`**，不要自己重算日界。
- **只有本機聚合才需要決定原點**，而且要和伺服器一致，否則和官方圖表、網頁版對不起來。原點做成設定，並向交易所確認。
- **週 K、月 K 的起點**（週一還是週日、月初怎麼算）也是（未證實），同樣以伺服器的 `id` 為準。
- **資料時區和顯示時區是兩件事。** 內部一律存 UTC 的時間（epoch，也就是自 1970 年 1 月 1 日 00:00 UTC 起算的秒數）；畫面顯示用日本時間還是裝置時區，是產品決定。X 軸標籤、十字線的時間、日界線，都要用同一套規則。
- **「今日」要標清楚。** 今日損益、成交統計和 K 線的「日」可能是不同的切法，畫面文字要標清楚（例如「過去 24 小時」和「今日（JST）」不要混用）。

> [!TIP]
> 面試時可以這樣說：「這套 API 日 K 範例的 `id` 對 86400 的餘數是 57600，推得起點是 UTC+8 的午夜，不是日本時間的 0 點，但這是 2021 年的範例，現行規則我會向交易所確認。所以我不會把日界寫死，畫伺服器的 K 線一律用它給的 `id`；顯示用日本時間還是裝置時區，是另一個決定。」

### 4. 移動平均（SMA、EMA）和 RSI 怎麼算？即時更新時要注意什麼？

**SMA 是最近 N 根收盤價的平均；EMA 讓越新的價格權重越大；RSI 比較一段期間內漲幅和跌幅的大小，結果落在 0 到 100。EMA 和 RSI 都是遞迴計算（每一個值都用前一個值算出來），所以要有足夠的暖機資料，進行中的 K 線也不能把暫時的值寫回去。**

**SMA（Simple Moving Average，簡單移動平均）：** 最近 N 根收盤價加起來除以 N。往右移一根時，不用重算全部，只要「加上新的一根、減掉滑出視窗的那一根」。

**EMA（Exponential Moving Average，指數移動平均）：** `k = 2 ÷ (N + 1)`，`ema = prev + k × (收盤價 − prev)`，`prev` 是上一根的 EMA。第一個值（稱為種子）有兩種常見做法：前 N 根收盤價的 SMA，或乾脆用第一根的收盤價。前者從第 N 根才有值；後者第一根就有值，但前幾十根的數字和前者不同，之後才慢慢收斂。要和某個看盤軟體對齊，就要照它的做法。

例子：收盤價 `[1, 2, 3, 4, 5]`，N＝3，所以 `k = 2 ÷ 4 = 0.5`。第 3 根的值是前 3 根的 SMA ＝ 2；第 4 根 ＝ 2 + 0.5 × (4 − 2) ＝ 3；第 5 根 ＝ 3 + 0.5 × (5 − 3) ＝ 4。前兩根沒有值。

**RSI（Relative Strength Index，相對強弱指標），Wilder 版本：** 先算每根的價差 `d[i] = 收盤價[i] − 收盤價[i−1]`，漲的部分算「漲幅」，跌的部分算「跌幅」（取正數）。第一組平均漲幅、平均跌幅，是前 N 個價差的簡單平均；之後每一根用 `avg = (prev × (N − 1) + 當期) ÷ N` 平滑。最後 `RSI = 100 − 100 ÷ (1 + 平均漲幅 ÷ 平均跌幅)`。需要 N + 1 根收盤價才有第一個值。

例子：收盤價 `[10, 11, 10, 12, 11, 13]`，N＝2。價差是 +1、−1、+2、−1、+2。

| 位置 | 平均漲幅 | 平均跌幅 | RSI |
|---|---|---|---|
| 第 3 根（索引 2） | (1 + 0) ÷ 2 ＝ 0.5 | (0 + 1) ÷ 2 ＝ 0.5 | 100 − 100 ÷ (1 + 1) ＝ 50 |
| 第 4 根（索引 3） | (0.5 × 1 + 2) ÷ 2 ＝ 1.25 | (0.5 × 1 + 0) ÷ 2 ＝ 0.25 | 100 − 100 ÷ (1 + 5) ≈ 83.33 |

平均跌幅是 0 時，除以零，需要約定：常見做法是回傳 100；全部持平時各家軟體的做法不同。

**即時更新的陷阱：暖機與增量更新。**

- **暖機（warm-up）：** EMA 和 RSI 是遞迴的，畫面最左邊那根的值，取決於更早的所有資料。要多抓一些歷史再開始算，經驗法則是至少 3 到 5 倍的週期，否則指標線的左側會和其他軟體不一樣。
- **進行中的那根 K 線，每個 tick 收盤價都在變。** 要用「上一根已收盤的 EMA」當 `prev`，算出進行中的顯示值；**不要把進行中的值寫回 `prev`**，否則每個 tick 都會被重複計入，EMA 會被「這一根 K 線內的 tick 次數」放大。K 線收盤（下一根的 `id` 出現）時，才把最後的值固定成新的 `prev`。

```swift
// 進行中的最後一根：用「上一根已收盤」的 EMA 算出顯示用的值，不寫回 prevClosed
func liveEMA(prevClosed: Double, price: Double, period: Int) -> Double {
    let k = 2.0 / Double(period + 1)
    return prevClosed + k * (price - prevClosed)
}

// N = 9（k = 0.2），上一根已收盤的 EMA 是 100，這根連續三個 tick 的價格都是 110
// 輸出：102.0（正確：每個 tick 都是 102）
print(liveEMA(prevClosed: 100, price: 110, period: 9))

// 錯誤做法：每個 tick 都把結果寫回 prev
var wrong = 100.0
for _ in 1...3 {
    wrong = liveEMA(prevClosed: wrong, price: 110, period: 9)
    // 輸出：102.0、103.6、104.88（越來越高，被 tick 次數放大）
    print(wrong)
}
```

指標在背景計算，結果以陣列傳給圖表；使用者改週期或載入更舊的歷史時，重算整段，不要試著拼接。

> [!TIP]
> 面試時可以這樣說：「EMA 的 k 是 2 除以 N 加 1，種子可以用前 N 根的 SMA，要和看盤軟體對齊就照它的做法。RSI 用 Wilder 平滑。兩者都是遞迴的，要多抓歷史暖機；進行中的 K 線用上一根已收盤的值算顯示值，不寫回，收盤時才固定。」

### 5. 切換週期、接上即時推送時，歷史資料和推送怎麼接起來？

**先訂閱即時推送，並且先暫存起來，再用 REST 抓歷史，最後依 `id` 合併，一次交給圖表；之後的推送才一根一根更新。** 這樣不會漏掉「REST 回應」和「訂閱生效」之間的那幾根，也不會被舊週期的資料污染。

情境：使用者在圖表畫面把週期從 `5min` 切到 `1day`。正確的順序是：

1. 顯示載入中；取消舊週期的訂閱和進行中的 REST 請求，並丟掉之後才到的舊週期推送。
2. 訂閱新週期的 topic，收到的推送先放進暫存區，不直接畫。
3. 用 REST 取得新週期的歷史 K 線，並依 `id` 由舊到新排序。
4. 把暫存區的推送合併進歷史：相同 `id` 以推送為準，比歷史最後一根更舊的丟掉。
5. 在背景算好指標，對圖表一次 `setData`；之後的推送才用 `update`。

為什麼要先訂閱再抓歷史？如果反過來，先抓歷史、再訂閱，在「REST 回應」到「訂閱生效」之間產生的那幾根 K 線就會漏掉。先訂閱並暫存，再合併，就不會有洞。這個思路和「訂單簿」單元的「先暫存增量更新、取快照、再套用」是同一個。

另一個問題是慢回來的回應：使用者快速連點好幾個週期時，慢回來的舊週期回應，不能覆蓋新週期的畫面。做法是每個請求帶一個遞增的編號，回應回來時如果編號不是最新的就丟掉；或使用可以取消的任務。

```swift
struct Candle {
    let id: Int64
    let close: Double
}

// history：REST 回來的歷史（已由舊到新排序）；buffered：訂閱後暫存的推送
func merge(history: [Candle], buffered: [Candle]) -> [Candle] {
    guard let lastHistoryID = history.last?.id else {
        return buffered.sorted { $0.id < $1.id }
    }
    var byID = Dictionary(uniqueKeysWithValues: history.map { ($0.id, $0) })
    // 比歷史最後一根更舊的丟掉
    for candle in buffered where candle.id >= lastHistoryID {
        // 相同 id 以推送為準
        byID[candle.id] = candle
    }
    return byID.values.sorted { $0.id < $1.id }
}

let history = [
    Candle(id: 60, close: 1),
    Candle(id: 120, close: 2),
    Candle(id: 180, close: 3)
]
let buffered = [
    Candle(id: 120, close: 99),
    Candle(id: 180, close: 30),
    Candle(id: 240, close: 4)
]
print(
    merge(history: history, buffered: buffered).map { "\($0.id):\($0.close)" }
)
// 輸出：["60:1.0", "120:2.0", "180:30.0", "240:4.0"]
// 120 比歷史最後一根（180）舊，推送的 99 被丟掉；180 以推送的 30 為準；240
// 是新的一根
```

App 從背景回到前景時，也是同樣的情況：錯過的那幾根要用 REST 補回來，再從上次推送的那一根開始，依序推給圖表，才不會在中間留下洞。

> [!TIP]
> 面試時可以這樣說：「切週期時我會先訂閱新的 topic 並暫存推送，再用 REST 抓歷史，依 `id` 合併後一次 `setData`，這樣不會漏掉兩者之間的幾根。每個請求帶編號，慢回來的舊週期回應會被丟掉。」

### 6. 資料量很大時，圖表怎麼只畫看得到的範圍？圖表元件怎麼選？

**只把畫面可視範圍內的資料交給繪圖。用二分搜尋找出可視時間範圍的起點和終點；縮小到一個畫面塞上千根時，每個像素欄位只畫最高和最低；向左拖時才分頁載入更舊的歷史，不要一次放數萬根。**

為什麼？假設螢幕寬 400 點，一個畫面顯示 2000 根 K 線，每根連 0.2 點寬都不到，畫了也看不出差別，卻要花 2000 根的繪製成本。所以縮小時要做聚合：把落在同一個像素欄位的 K 線合併成一根（開盤取最早的、收盤取最晚的、最高取最大的、最低取最小的）。

資料已經依 `id` 排序，所以可以用二分搜尋，在對數時間內找出可視範圍的起訖索引，而不是從頭掃描：

```swift
// candles 依 id 由舊到新排序；回傳 id 落在 [from, to] 的索引範圍
func visibleRange(_ candles: [Candle], from: Int64, to: Int64) -> Range<Int> {
    // 找第一個 id >= target 的索引（二分搜尋）
    func lowerBound(_ target: Int64) -> Int {
        var lo = 0, hi = candles.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if candles[mid].id < target {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }
    return lowerBound(from)..<lowerBound(to + 1)
}

// id：0、60、120…540
let candles = (0..<10).map { Candle(id: Int64($0) * 60, close: 0) }
// 輸出：2..<6（id 為 120、180、240、300）
print(visibleRange(candles, from: 120, to: 300))
// 輸出：10..<10（空範圍，沒有資料）
print(visibleRange(candles, from: 1000, to: 2000))
```

其他和效能、互動有關的細節：

- **分頁載入歷史：** 使用者向左拖到盡頭，才載入更早的一頁，並且丟掉太遠的舊資料，避免記憶體一直增加。
- **手勢衝突：** 十字線（長按後拖曳看某一根的數值）會和外層的捲動、側滑返回衝突，要明確處理。實務上可以在 JavaScript 裡自己處理觸控：長按顯示十字線，垂直滑動時仍然可以捲動頁面。

**圖表元件怎麼選？** 三種方案的取捨：

| 方案 | 優點 | 代價 |
|---|---|---|
| WebView 加 TradingView Lightweight Charts | 蠟燭圖、副圖（放指標的小圖）、十字線都很成熟；可以和網頁版共用程式 | 資料要跨過 App 與網頁的橋接；WebView 的記憶體與啟動時間；離線與載入失敗要處理 |
| 第三方原生圖表（iOS 的 DGCharts、Android 的 MPAndroidChart） | 手勢與 App 整合得自然 | K 線的互動與副圖需要自行擴充，客製化成本高 |
| 自己繪製（iOS 的 Core Graphics 或 Metal、Android 的 Canvas） | 完全可控，效能最好 | 開發量最大，縮放、十字線、座標軸都要自己做 |

WebView 是 App 裡嵌入網頁的元件（iOS 是 `WKWebView`，Android 是 `WebView`）。

> [!TIP]
> 面試時可以這樣說：「圖表只提交可視範圍的資料，用二分搜尋找出起訖索引；縮到一個畫面上千根時，每個像素欄位做最高和最低的聚合，向左拖才分頁載入歷史。圖表元件我會比較 WebView 加 Lightweight Charts、原生圖表和自繪三條路，看團隊能花多少開發量。」

### 7. 用 WebView 放 TradingView Lightweight Charts，資料怎麼傳過去？要注意什麼？

**App 取得資料、算好指標之後，用 JSON 傳給網頁裡的圖表：第一次用 `setData` 傳整批，之後用 `update` 只推最新的一根。傳資料不要用字串插值，`update` 的時間不能倒退，腳本要打包進 App，也要準備好 WebView 被系統終止後的恢復。**

Lightweight Charts 是 TradingView 提供的網頁圖表函式庫。資料流是：App 取得資料（REST 加 WebSocket）→ 計算指標 → 以 JSON 傳到 JavaScript → `setData` 一次，之後用 `update` 推新的一根。`setData` 會重設整個資料序列和縮放狀態，高頻情況下比 `update` 貴很多，也會打斷使用者的縮放與十字線，所以不要每個 tick 都呼叫。

**傳資料的方式：不要用字串插值把 Double 塞進 JavaScript 程式碼。** Swift 的字串插值把 Double 轉成文字時，`NaN` 會變成 `nan`，無限大會變成 `inf`，這兩個字在 JavaScript 裡都不是合法的識別字（JavaScript 是 `NaN` 和 `Infinity`），組出來的 `update({open: nan})` 會丟出 `ReferenceError`。這類值常來自除以零，例如成交量為 0 的區間算平均價。

```swift
let values: [Double] = [0.1 + 0.2, 2, 1e-7, .nan, -.infinity]
print(values.map { "\($0)" }.joined(separator: " "))
// 輸出：0.30000000000000004 2.0 1e-07 nan -inf
// 0.30000000000000004 是 Double 能還原原值的最短文字；nan 和 -inf 是
// JavaScript 不認得的字
```

比較好的做法：

- **iOS：** 用 `callAsyncJavaScript` 的 `arguments` 以值傳遞（iOS 14 起），或把資料轉成 JSON 字串，在網頁裡再 `JSON.parse`。轉 JSON 之前，先用 `isFinite` 檢查數字。注意：`JSONSerialization.data(withJSONObject:)` 遇到 `NaN` 會丟 Objective-C 的例外（`NSInvalidArgumentException`），Swift 的 `do/catch` 接不到，App 會直接閃退（本機實測結束代碼是 134）。要先用 `JSONSerialization.isValidJSONObject` 檢查，或改用 `JSONEncoder`：它丟的是可以 `catch` 的 `EncodingError`。
- **Android：** `evaluateJavascript` 只吃字串，要自己做 JSON 序列化，並正確跳脫特殊字元（例如用 `JSONObject.quote(json)` 產生合法的字串字面值，再交給 `JSON.parse`）。

```swift
// iOS 14 起：參數以值傳遞，不經過字串拼接；bar 是下面 arguments 傳進去的變數
webView.callAsyncJavaScript(
    "chart.update(bar)",
    arguments: [
        "bar": [
            "time": 1_632_931_200,
            "open": 4_663_135,
            "high": 4_830_000,
            "low": 4_580_081,
            "close": 4_815_018
        ]
    ],
    in: nil,
    in: .page,
    completionHandler: nil
)
```

**`update` 的時間不能倒退。** `update` 只能推相同或較新的時間，時間倒退會丟出例外。所以要記住最後推送的時間（例如叫 `lastPushedTime`），跳過比它舊的資料，並且對每次 `update` 用 `try/catch` 包起來，不然一次例外就讓圖表悄悄停止更新。從背景回到前景、合併補資料時，要把「自上次推送以來的所有 K 線」依序推完，才不會在中間留下洞。

**時間與時區。** Lightweight Charts 把時間當成 UTC 來顯示。常見的做法是把時間加上裝置目前的 UTC 偏移（日本是 +9 小時）。缺點是：資料裡的時間不再是真正的 epoch；而且偏移是「現在」的，橫跨夏令時間的歷史會錯一小時。日本沒有夏令時間，但使用者可能在國外。比較乾淨的做法是資料保持 UTC，改用圖表的時間格式化選項（`localization.timeFormatter`、`timeScale.tickMarkFormatter`，名稱與行為以所用版本的文件為準）顯示成目標時區。

**載入與生命週期：**

- **腳本來源。** 如果圖表的 JavaScript 與 HTML（網頁的結構檔案）來自 CDN（內容傳遞網路，也就是網路上的公開檔案伺服器），離線或被擋住時圖表會空白，也多了供應鏈風險（別人的伺服器被入侵，你的 App 就跟著出事）。交易所 App 應該把腳本打包進 App，並固定版本。
- **WebView 被系統終止。** `WKWebView` 負責渲染網頁的行程可能被系統終止，畫面會變白。iOS 要實作 `webViewWebContentProcessDidTerminate`，Android 要實作 `WebViewClient.onRenderProcessGone`（Android 8.0、API 26 起），偵測後重建 WebView，並重新 `setData`。
- **JavaScript 回呼原生。** iOS 用 `WKScriptMessageHandler`，但 `userContentController` 會強參考這個 handler，不移除就會形成 retain cycle（循環參考，導致記憶體洩漏）；Android 的 `@JavascriptInterface` 只暴露必要的方法。

> [!TIP]
> 面試時可以這樣說：「圖表橋接我會用 JSON 傳資料，不用字串插值，因為 NaN 和無限大會變成 JavaScript 不認得的文字。update 只推新的，記住最後推送的時間、擋掉時間倒退。腳本打包進 App 並固定版本，也要處理 WebView 被系統終止後的重建。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange07Kline**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange07Kline**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange07-Kline/`，解答在 `labs/ios/Solution/Exchange/Exchange07-Kline/`；Android 你寫在 `labs/android/practice/exchange/exchange07-kline/`，解答在 `labs/android/solution/exchange/exchange07-kline/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也沒有真的 WebView**：這個單元的演練都用編好的 K 線與成交資料、假的圖表（收到什麼就檢查什麼），每次執行的結果都一樣。數字是為了說明編的，格式依這套交易所 API 的文件。

### 演練 1：K 線日期變成 1970 年，成交量差好幾個數量級（第 1 題）

**檔案**：`L01_FieldUnits.swift`（iOS）、`L01_FieldUnits.kt`（Android）

**情境**：REST 的日 K 回應，新的在前：第 1 根 `id` 1632931200（2021-09-29 16:00 UTC），`amount` 6.63（BTC 的數量），`vol` 31208129（日圓的成交金額）；第 2 根 `id` 1632844800（2021-09-28 16:00 UTC），`amount` 5.1，`vol` 23900000。用 JSON 解析函式庫（iOS 的 `JSONDecoder`、Android 的 Gson）解成物件，畫面要顯示每根 K 線的起點時間（UTC）與「成交量（BTC）」。

**怎麼寫**

1. **Bug 版**：**iOS**：把 `id` 當成毫秒，除以 1000 再轉成 `Date`。**Android**：直接 `Date(id)`（Java 的 `Date` 吃毫秒）。成交量直接顯示 `vol`，卻標成 BTC。
2. **修正版**：`id` 的單位是秒：iOS 直接 `Date(timeIntervalSince1970: TimeInterval(id))`，Android 用 `Date(id * 1000)`；成交量（BTC）用 `amount`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 1 根「1970-01-19 21:35 UTC，成交量 31208129.00 BTC」，日期錯了、單位也錯了 |
| 修正版 | 第 1 根「2021-09-29 16:00 UTC，成交量 6.63 BTC」，第 2 根「2021-09-28 16:00 UTC，成交量 5.10 BTC」 |

**為什麼**：這套 API 的 `id` 是這根 K 線的起點時間，單位是秒，不是大部分平台 API 預設的毫秒；`vol` 是計價金額（日圓），`amount` 才是標的數量（BTC）。怎麼確定？用範例反推：`vol ÷ amount` 是平均成交價，31208129 ÷ 6.63 約 470 萬，剛好落在這根的最低價 4,580,081 和最高價 4,830,000 之間。顯示成交量時一定要標清楚單位，否則兩者會差好幾個數量級。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L01_FieldUnits.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L01_FieldUnits.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L01_FieldUnits.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L01_FieldUnits.kt#fixed
```

### 演練 2：收盤價是最後收到的那一筆，不是最後發生的（第 2 題）

**檔案**：`L02_OpenCloseOrder.swift`（iOS）、`L02_OpenCloseOrder.kt`（Android）

**情境**：重連補資料時，成交常常亂序到達。一分鐘內收到四筆成交（依收到的順序）：第 40 秒 4,800,000×0.2、第 5 秒 4,790,000×0.1、第 55 秒 4,810,000×0.05、第 20 秒 4,780,000×0.3。正確的 K 線：open 4790000（時間最早的）、close 4810000（時間最晚的）、high 4810000、low 4780000、amount 0.65、vol 3113500、count 4。high、low、amount、vol、count 和順序無關，已經寫好，要你寫的只有 open 和 close。

**怎麼寫**

1. **Bug 版**：open 取收到的第一筆（`trades.first!.price`／`trades.first().price`）、close 取收到的最後一筆。
2. **修正版**：先依（成交時間 `ts`, 成交編號 `tradeId`）排序，open 取排序後的第一筆、close 取排序後的最後一筆。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「最後：open 4800000，close 4780000，…」，和正確答案不同 |
| 修正版 | 「最後：open 4790000，close 4810000，high 4810000，low 4780000，amount 0.65，vol 3113500，count 4」 |

**為什麼**：K 線的 open 和 close 看的是「時間最早」和「時間最晚」的成交，不是陣列的第一筆和最後一筆。補資料、重連、多條連線同時送，都會讓成交亂序。同一毫秒有好幾筆成交時，用成交編號決定先後，所以排序的鍵是（時間, 成交編號）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L02_OpenCloseOrder.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L02_OpenCloseOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L02_OpenCloseOrder.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L02_OpenCloseOrder.kt#fixed
```

### 演練 3：補資料之後，成交筆數反而變少（第 2 題）

**檔案**：`L03_DedupeKey.swift`（iOS）、`L03_DedupeKey.kt`（Android）

**情境**：逐筆成交的 JSON 有兩個編號：`tradeId`（成交編號，一般的整數）和 `id`（27 位數的數字，例如 137005445109359286410323766）。重連之後收到 5 則：成交編號 1、2、2（重送的同一筆）、3、4；4 筆不同的成交，數量 0.2、0.1、0.3、0.05。5 則的 `id` 只差最後幾位數。資料模型把 `id` 宣告成 `Double`，因為 `Int64`／`Long` 放不下 27 位數。正確答案：去重後 4 筆，數量合計 0.65。

**怎麼寫**

1. **Bug 版**：用 `id` 轉成的字串當去重的 key（`"\(trade.id)"`／`trade.id.toString()`）。
2. **修正版**：用 `tradeId` 轉成的字串當去重的 key。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「收到 5 則，去重後 1 筆，數量合計 0.2」：不同的成交被當成重複丟掉 |
| 修正版 | 「收到 5 則，去重後 4 筆，數量合計 0.65」：只丟掉重送的那一筆 |

**為什麼**：`Double` 只有約 16 位有效數字，27 位數的 `id` 轉成 `Double` 之後，最後幾位數全部被吃掉，不同的成交變成同一個值；文件的 REST 範例裡，兩筆不同的成交甚至有相同的 `id`（`trade-id` 不同）。去重要用 `tradeId`（REST 是 `trade-id`）。非用 `id` 不可的時候，要當字串解析，不經過數字型別。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L03_DedupeKey.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L03_DedupeKey.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L03_DedupeKey.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L03_DedupeKey.kt#fixed
```

### 演練 4：同一筆成交，換個國家就歸到別天的日 K（第 3 題）

**檔案**：`L04_DayBoundary.swift`（iOS）、`L04_DayBoundary.kt`（Android）

**情境**：App 要把成交聚合進日 K。這套 API 文件的日 K 範例，起點是 16:00 UTC（UTC+8 的午夜，偏移 28800 秒），要和伺服器的 `id` 一致，聚合才會對得上官方圖表。兩筆成交：2021-09-29 20:00 UTC（1632945600000 毫秒）、15:30 UTC（1632929400000 毫秒），伺服器把它們分別歸在 id 1632931200 與 1632844800 那兩根。演練模擬三支手機：裝置時區分別是 Asia/Tokyo、America/New_York、UTC。

**怎麼寫**

1. **Bug 版**：用 `Calendar`（時區設成裝置的時區）找「這一天的起點」：iOS 用 `startOfDay(for:)`，Android 把時、分、秒、毫秒歸零。
2. **修正版**：用 floor 除法：`floorDiv(秒 + 偏移, 週期秒) × 週期秒 − 偏移`，偏移是 28800（資料來源的日界，做成設定）。iOS 的 `e07FloorDiv` 已經寫好，Android 用 `Math.floorDiv`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 六個結果全部對不上，而且每支手機算出來不一樣（東京 1632927600、紐約 1632888000、UTC 1632873600）：「和伺服器對不上的有 6 個」 |
| 修正版 | 六個結果都 ✓，三支手機算出來一樣：20:00 UTC 的成交是 1632931200，15:30 UTC 的成交是 1632844800：「和伺服器對不上的有 0 個」 |

**為什麼**：日 K 從幾點開始，是資料來源決定的，不是裝置的時區。用裝置的時區切日界，東京的使用者看到 JST 0 點開始的日 K，紐約的使用者看到紐約 0 點開始的日 K，兩邊都和官方圖表、網頁版對不起來。日界要做成設定並向交易所確認；畫伺服器給的 K 線時，一律用它回傳的 `id`，不要自己重算。顯示用日本時間還是裝置時區，是另一個決定（資料時區和顯示時區是兩件事）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L04_DayBoundary.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L04_DayBoundary.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L04_DayBoundary.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L04_DayBoundary.kt#fixed
```

### 演練 5：進行中的 K 線，EMA 越跳越高（第 4 題）

**檔案**：`L05_LiveEMA.swift`（iOS）、`L05_LiveEMA.kt`（Android）

**情境**：EMA 週期 9（k = 2 ÷ (9 + 1) = 0.2），公式 `ema = prev + k × (價格 − prev)`。上一根已收盤的 EMA 是 100。這一根還在進行中，連續收到三個 tick（行情每更新一次價格，就叫一個 tick），價格都是 110；收盤之後，下一根的第一個 tick 價格是 104。正確的顯示值：三個 tick 都是 102；收盤後 EMA 固定為 102；下一根第一個 tick：102 + 0.2 × (104 − 102) = 102.4。

**怎麼寫**

1. **Bug 版**：`onTick` 每次都把算出來的值寫回 `prev`，下一個 tick 又拿它當 `prev`；`onCandleClose` 什麼都不做。
2. **修正版**：`onTick` 用上一根已收盤的值 `prevClosed` 算出 `live` 再回傳，不寫回 `prevClosed`；`onCandleClose` 才把 `prevClosed` 換成最後的 `live`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 同樣是 110 的三個 tick，EMA 是 102、103.6、104.88，下一根第一個 tick 是 104.704：「最後：三個 tick 的 EMA 是 102、103.6、104.88，下一根第一個 tick 是 104.704」 |
| 修正版 | 三個 tick 都是 102，下一根第一個 tick 是 102.4：「最後：三個 tick 的 EMA 是 102、102、102，下一根第一個 tick 是 102.4」 |

**為什麼**：EMA 是遞迴計算，每一個值都用前一個值算出來。進行中的那根 K 線，每個 tick 的收盤價都在變，所以只能用「上一根已收盤的 EMA」算出顯示值；把進行中的值寫回 `prev`，每個 tick 都會被重複計入，EMA 被「這一根 K 線內的 tick 次數」放大。K 線收盤（下一根的 `id` 出現）時，才把最後的值固定成新的 `prev`。演練用 `Decimal`／`BigDecimal` 是為了讓數字剛好是 102、103.6，不被浮點誤差干擾。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L05_LiveEMA.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L05_LiveEMA.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L05_LiveEMA.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L05_LiveEMA.kt#fixed
```

### 演練 6：切換週期之後，圖表一片空白（第 5 題）

**檔案**：`L06_MergeHistory.swift`（iOS）、`L06_MergeHistory.kt`（Android）

**情境**：使用者切換週期。App 先訂閱新週期的推送並暫存，再用 REST 抓歷史，最後把兩邊合併，一次交給圖表。歷史（已由舊到新）：id 60 收 1、id 120 收 2、id 180 收 3。暫存的推送：id 120 收 99（比歷史最後一根舊的舊資料）、id 180 收 30（同一根的最新資料）、id 240 收 4（新的一根）。正確的合併是 60:1、120:2、180:30、240:4。假的圖表的 `setData` 和 Lightweight Charts 一樣，要求資料嚴格由舊到新，id 不能重複，否則丟出例外。

**怎麼寫**

1. **Bug 版**：把歷史和暫存的推送直接接在一起，再依 id 排序。
2. **修正版**：比歷史最後一根（id 180）更舊的推送丟掉；相同 id 以推送為準；新的 id 加進來；最後依 id 排序。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 合併後「60:1、120:2、120:99、180:3、180:30、240:4」，圖表 `setData` 失敗：「最後：圖表有 0 根」 |
| 修正版 | 合併後「60:1、120:2、180:30、240:4」，「最後：圖表有 4 根：60:1、120:2、180:30、240:4」 |

**為什麼**：先訂閱推送並暫存，再抓歷史，是為了不漏掉「REST 回應」和「訂閱生效」之間的那幾根；代價是兩邊會有重疊：同一根 K 線可能同時出現在歷史和推送裡，推送裡也可能夾著比歷史更舊的資料。合併時相同 id 以推送為準（它比較新），比歷史最後一根更舊的丟掉，最後依 id 由舊到新排序，圖表才收得下。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L06_MergeHistory.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L06_MergeHistory.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L06_MergeHistory.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L06_MergeHistory.kt#fixed
```

### 演練 7：縮小圖表之後，暴漲的那根針不見了（第 6 題）

**檔案**：`L07_DownsampleSpike.swift`（iOS）、`L07_DownsampleSpike.kt`（Android）

**情境**：圖表縮小到一個畫面塞進很多根 K 線，每根連 1 個像素都不到，要把落在同一個像素欄位的 K 線合併。演練用 12 根 K 線縮成 4 欄（每 3 根一欄）。第 5 根（索引 4）有一根暴漲的針：最高 130（其他都在 101 到 106 之間）。

**怎麼寫**

1. **Bug 版**：每隔 3 根取 1 根（取索引 0、3、6、9）：iOS 用 `stride`，Android 用 `filterIndexed`。
2. **修正版**：把每 3 根合併成一根：open 取第一根的 open、high 取最大、low 取最小、close 取最後一根的 close（iOS 用 `stride` 加 `min`／`max`，Android 用 `chunked`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 縮成 4 欄「100/101/99/100、100/101/99/100、102/103/101/102、103/105/102/104」，「最後：縮小後的最高價 105（真正的最高價 130）」 |
| 修正版 | 縮成 4 欄「100/102/99/100、100/130/98/102、102/104/101/103、103/106/102/105」，「最後：縮小後的最高價 130（真正的最高價 130）」 |

**為什麼**：每隔幾根取一根是最直覺的縮小方式，但價格的最高點、最低點剛好不在取樣的那幾根上，就消失了：圖表縮小之後，暴漲暴跌的針不見，使用者會以為沒有那段行情。正確的做法是把同一個像素欄位的 K 線合併成一根，最高取最大的、最低取最小的。只畫可視範圍時，則是用二分搜尋在已排序的資料裡找出起訖索引；向左拖才分頁載入更舊的歷史。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L07_DownsampleSpike.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L07_DownsampleSpike.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L07_DownsampleSpike.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L07_DownsampleSpike.kt#fixed
```

### 演練 8：圖表少一筆資料，還回報錯誤（第 7 題）

**檔案**：`L08_JsonNaN.swift`（iOS）、`L08_JsonNaN.kt`（Android）

**情境**：圖表放在 WebView 裡，App 把每根 K 線的「平均成交價」（`vol ÷ amount`）用 JSON 傳給網頁，網頁用 `JSON.parse` 解析後交給圖表。五根 K 線裡，第 3 根這段時間完全沒有成交（`vol` 0、`amount` 0），0.0 ÷ 0.0 得到 NaN（不是數字）。假的圖表用嚴格的 JSON 解析（iOS 的 `JSONSerialization`、Android 的 Gson 嚴格模式）檢查收到的文字，不合法就丟掉並記一筆錯誤。

**怎麼寫**

1. **Bug 版**：用字串插值組 JSON：`{"time":時間,"value":價格}`，`bar.value` 直接放進字串。
2. **修正版**：先檢查 `isFinite`（Android 是 `isFinite()`）：不是有限的數字就不傳 `value`，只傳時間（圖表把只有時間的點當成空白點）；其他的用 JSON 編碼器（iOS 的 `JSONEncoder`、Android 的 `Gson().toJson`）產生。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 3 根的文字是 `{"time":180,"value":nan}`（Android 是 `NaN`），圖表丟掉：「最後：圖表收到 4 筆（其中空白點 0 筆），格式錯誤 1 筆」 |
| 修正版 | 第 3 根的文字是 `{"time":180}`（空白點）：「最後：圖表收到 5 筆（其中空白點 1 筆），格式錯誤 0 筆」 |

**為什麼**：Swift 的字串插值把 NaN 轉成 `nan`、Kotlin 轉成 `NaN`，兩個都不是合法的 JSON，`JSON.parse` 會丟出例外；這類值常來自除以零，例如成交量為 0 的區間算平均價。字串插值還有另一個問題：遇到引號、反斜線等特殊字元也會壞。還有一個 iOS 的陷阱這個演練沒有重現：`JSONSerialization.data(withJSONObject:)` 遇到 NaN 會丟出 Objective-C 例外，Swift 的 `do/catch` 接不到，App 會直接閃退；要先用 `isValidJSONObject` 檢查，或改用丟的是可以 `catch` 的 `EncodingError` 的 `JSONEncoder`。Android 的 `Gson.toJson` 遇到 NaN 也會丟出 `IllegalArgumentException`，所以一樣要先檢查。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L08_JsonNaN.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L08_JsonNaN.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L08_JsonNaN.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L08_JsonNaN.kt#fixed
```

### 演練 9：圖表停止更新，後面的 K 線沒有畫出來（第 7 題）

**檔案**：`L09_UpdateBackwards.swift`（iOS）、`L09_UpdateBackwards.kt`（Android）

**情境**：App 要把 K 線一根一根用 `update` 推給圖表。和 Lightweight Charts 一樣，`update` 只能推相同或較新的時間，時間倒退會丟出例外。從背景回到前景、合併補資料的時候，依序要推的時間是：60、120、180、120（重送的舊資料，已經推過了）、240、300。正確結果：跳過倒退的 120，圖表最後一根是 300，共 5 根。

**怎麼寫**

1. **Bug 版**：用迴圈依序 `chart.update(...)`，沒有擋時間倒退，也沒有接例外。
2. **修正版**：記住最後推送的時間（`lastPushedTime`），比它舊的 log「略過時間倒退的 …」並跳過；每次 `update` 外面包 `do/catch`（Android 是 `try/catch`），成功才更新 `lastPushedTime`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 推到第 4 個（120）時丟出「Cannot update oldest data」，迴圈中斷：「最後：圖表最後一根是 180，共 3 根」，240、300 沒有畫出來 |
| 修正版 | 「略過時間倒退的 120（最後推送的是 180）」，「最後：圖表最後一根是 300，共 5 根」 |

**為什麼**：`update` 遇到時間倒退會丟出例外，沒人接的話，例外會一路往上，整個推送迴圈中斷，圖表從這一刻起悄悄停止更新，使用者看到的是畫面停住，卻沒有任何錯誤提示。所以要記住最後推送的時間，擋掉倒退的資料，而且每次 `update` 都用 `try/catch` 包起來。從背景回到前景時，則要把「自上次推送以來的所有 K 線」依序推完，才不會在中間留下洞。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L09_UpdateBackwards.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange07-Kline/L09_UpdateBackwards.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange07-kline/L09_UpdateBackwards.kt#bug
```

```lab-solution
android/solution/exchange/exchange07-kline/L09_UpdateBackwards.kt#fixed
```

第 5 題「慢回來的舊週期回應，不能蓋掉新週期的畫面」，在 Swift06 演練 7（Combine 的 `switchToLatest`）練過；「先訂閱並暫存推送，再抓歷史」的順序，Exchange04 演練 5 和 Platform01 演練 7 已經練過。第 7 題的 WebView 被系統終止後重建、圖表腳本打包進 App 而不放 CDN，是設計題，沒有可以穩定自動檢查的演練；RSI 的 Wilder 平滑與「平均跌幅是 0」的約定，筆記有例子可以自己驗算。

## 常見陷阱

- **把 `vol` 當成標的數量。** `vol` 是日圓金額，`amount` 才是 BTC 數量。顯示的成交量會差好幾個數量級，單位一定要標清楚。
- **以為 `id` 是毫秒，或把日界寫死成日本時間 0 點或 UTC 0 點。** `id` 是起點時間，單位是秒；日界以伺服器的 `id` 為準。
- **用陣列的第一筆和最後一筆當 `open` 和 `close`。** 成交可能亂序，要先依（時間, 成交編號）排序。
- **用一般除法算 K 線的起點時間。** 偏移之後或遇到負的時間，一般除法會往 0 取整，邊界錯一格，要用 floor 除法。
- **用逐筆資料的 27 位數 `id` 去重。** 它超出 `Int64` 和 `Double` 的精確範圍，用 `tradeId`（REST 是 `trade-id`）。
- **把進行中 K 線的 EMA 值寫回 `prev`。** 每個 tick 都重複計入，指標被放大。
- **指標沒有暖機。** 畫面左側的數字和其他看盤軟體對不上。
- **把 REST 的新到舊直接餵給圖表。** 圖表會丟例外或畫反，進圖表前要排序。
- **用字串插值組 JavaScript。** 遇到 `NaN`、無限大或特殊字元就壞。
- **`update` 沒有擋時間倒退。** 一次例外就讓圖表停止更新。
- **圖表腳本放在 CDN。** 離線或被擋住時，圖表一片空白。
- **所有資料都丟給圖表繪製。** 縮放時會卡頓，只提交可視範圍。

## 面試官想聽到的

1. 欄位的語意：`vol` 是計價金額、`amount` 是標的數量、`id` 是起點時間（秒）；以及「日界不寫死，以伺服器的 `id` 為準」的態度。
2. 聚合的細節：先排序再決定 open／close、用 `tradeId` 去重、用 floor 除法算 K 線的起點時間、`vol` 的算法。
3. 指標：EMA 的種子、Wilder 版本的 RSI、暖機，以及增量更新時不能污染 `prev`。
4. 歷史與即時的接法：先訂閱暫存、再抓歷史、依 `id` 合併；慢回來的舊回應要丟掉。
5. 橋接與效能：只推新的、擋時間倒退、用 JSON 傳資料、處理 UTC 與顯示時區、腳本打包進 App、WebView 被終止的恢復；只畫可視範圍，以及圖表方案取捨的理由。

> [!FIELD]
> 在一個加密貨幣市場分析 App 裡，K 線是這個主題最直接的經驗。圖表用 TradingView Lightweight Charts：原生端組好 HTML，載入 WebView（iOS 的 `WKWebView`、Android 的 `WebView`）；iOS 用 `evaluateJavaScript` 推資料，並在 Coordinator 記住 `lastPushedTime`，把「自上次以來的所有 K 線」依序推送，遇到時間倒退就跳過，每次 `update` 外面包 `try/catch`。圖表的時間加上裝置的 UTC 偏移，因為圖表以 UTC 顯示；副圖（成交量等）用另外的 pane（圖表裡獨立的一塊區域）顯示；觸控在 JavaScript 裡自己處理。
> 即時串流的週期，只選邊界和 REST 資料一致的（1 分到 1 日），週、月不串流，原因是它們的邊界和資料來源不同，硬套會畫出錯的 K 線，這正是「日界以資料來源為準」的實務案例。即時資料用 SSE（Server-Sent Events，伺服器單向把資料推給 App 的 HTTP 串流）。
> 到交易所的 App，我會在這個基礎上做：成交聚合與指標在背景計算、用 `callAsyncJavaScript` 或 JSON 傳資料、腳本打包進 App，並處理 WebView 被終止後的重建。
> 面試時可以這樣說：「我做過 Lightweight Charts 的 WebView 橋接，知道只推新的一根、擋時間倒退、時間要處理 UTC 與顯示的差異。這套 API 的日 K 範例看起來是 UTC+8 的午夜，所以我不會把日界寫死，而是用伺服器給的 `id`；到交易所的 App 我會把腳本打包進 App，並且用 JSON 而不是字串插值傳資料。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 圖表容器 | `WKWebView`（`loadHTMLString`） | `WebView`（`loadDataWithBaseURL`） |
| 傳資料 | `callAsyncJavaScript`（參數以值傳遞）或 `evaluateJavaScript`（字串） | `evaluateJavascript`（只吃字串，需自行序列化與跳脫） |
| JavaScript 回呼原生 | `WKScriptMessageHandler`（記得移除，避免 retain cycle） | `@JavascriptInterface`（只暴露必要的方法） |
| WebView 被系統終止 | `webViewWebContentProcessDidTerminate` | `WebViewClient.onRenderProcessGone` |
| 原生圖表函式庫 | DGCharts | MPAndroidChart |
| 時間與時區 | `TimeZone(identifier: "Asia/Tokyo")`、`Calendar` | `java.time.ZoneId.of("Asia/Tokyo")` |
| floor 除法 | 自己寫（Swift 的 `/` 往 0 取整） | `Math.floorDiv` |
| 背景計算 | actor 或 `Task.detached` | `Dispatchers.Default` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| K 線（蠟燭圖） | candlestick／K-line | ローソク足 |
| 開高低收量 | OHLCV | 始値・高値・安値・終値・出来高 |
| 日 K、週 K | daily candle／weekly candle | 日足・週足 |
| 移動平均 | moving average（SMA／EMA） | 移動平均線 |
| 相對強弱指標 | RSI（Relative Strength Index） | RSI（相対力指数） |
| 暖機 | warm-up | ウォームアップ |
| 成交（一筆一筆的交易紀錄） | trade／execution | 約定 |
| 世界協調時間／日本標準時間 | UTC／JST | 協定世界時／日本標準時 |
