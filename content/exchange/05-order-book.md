---
id: exchange-order-book
title: 訂單簿：完整快照與增量更新、序號檢查、依價格間隔合併、累計數量與深度條
summary: 訂單簿是交易所 App 最容易出錯的資料：套錯一筆更新，整張板就不可信。這個單元先說明這套交易所 API 的深度資料長什麼樣，再說明一般交易所「完整快照加增量更新」的做法，跳號時怎麼重新抓完整資料，以及價格合併、累計數量、深度條和畫面更新的做法。
priority: P0
day: 4
tags: [order-book, depth, snapshot, sequence, aggregation, cumulative, diff]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 這套交易所 API 文件裡的 depth（板的深度）資料長什麼樣？它是完整快照還是增量更新？
2. 「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？
3. 本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？
4. 依價格間隔（step）合併時，買方、賣方各往哪個方向取整？
5. 累計數量和深度條怎麼算？
6. 板更新得很頻繁，畫面怎麼更新才不會卡？

訂單簿（日文叫「板」）是一張列出所有人「想用什麼價格買或賣多少」的表：賣方（ask）由低價排到高價，買方（bid）由高價排到低價，最佳價（賣方最低、買方最高）排在最前面。每一列叫一個檔位（price level），意思是某個價格上總共掛了多少數量。訂單簿的基本概念見交易所業務入門單元。

### 1. 這套交易所 API 文件裡的 depth 資料長什麼樣？它是完整快照還是增量更新？

**依這套交易所 API（Application Programming Interface，程式介面）文件，depth 頻道每次推送的資料都帶有完整的 `bids`（買方）和 `asks`（賣方）兩個陣列，沒有序號欄位。這看起來是「每次都推整份最新的板」（完整快照），所以直接取代本地的板就好，不需要序號檢查（未證實，實際行為以實測為準）。**

先看文件裡的格式。WebSocket（讓伺服器可以持續主動推送資料給 App 的連線）的 topic（主題名稱）是 `market.$symbol.depth.$type`，訂閱（`sub`）和一次性請求（`req`）都可以用；推送長這樣，每個檔位是 `[價格, 數量]`：

```json
{
  "ch":"market.btcjpy.depth.step0",
  "ts":1489474082831,
  "data":{
    "bids":[[10000000,0.4],[9990000,0.6]],
    "asks":[[10010000,0.3],[10020000,0.5]]
  }
}
```

（數字是為了說明編的，格式依文件。）幾個要注意的地方：

- `bids` 是買方，由高價排到低價；`asks` 是賣方，由低價排到高價。
- 這則推送把資料放在 `data`；而 REST（一般的 HTTP 請求介面）的 `GET /market/depth?symbol=btcjpy&type=step1` 回應放在 `tick`，裡面有 `bids`、`asks`、`version` 和 `ts`。解析時兩個欄位都要接受。
- `type=step0` 到 `step5` 是合併的粗細，文件寫「step1 是價格精度的 10 倍，step2 是 100 倍，一路到 step5 是 100000 倍」，step0 不合併。例如價格精度是整數円的交易對，step1 就是每 10 円一檔。
- 同一份文件裡，`bbo`（best bid and offer，最佳買價與賣價）頻道有 `seqId`（序號）；公開的 topic 清單中，沒有看到只推送變動檔位的增量板頻道。

既然每次推送都是整份板，App 的處理很單純：收到就整份取代本地的板。用 `ts`（時間戳）可以判斷哪一份比較新，丟掉順序顛倒、比目前更舊的資料，例如同時用 `req` 抓一份、又收到推送的時候。推送頻率很高時，要先在背景合併（多筆更新只留最新一筆），再以固定頻率更新畫面（見第 6 個問題）。

**但是「完整快照加增量更新」的做法，面試很常被問。** 因為很多交易所為了省頻寬，不會每次推整份板，而是先給一份完整快照，之後只推「哪些檔位變了」。面試時用「如果協定提供增量」的口吻說明，不要說成是這套 API 現在的做法。下一個問題就是這個做法。

> [!TIP]
> 面試時可以這樣說：「依這套交易所的 API 文件，depth 頻道每次都推完整的 bids 和 asks，沒有序號，所以我會把它當完整快照，直接取代本地的板，用 ts 丟掉比較舊的資料。如果是有增量更新的交易所，才需要序號檢查和跳號時重新同步。」

### 2. 「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？

**完整快照（snapshot）是一次給你整份最新的板；增量更新（incremental update，也叫 delta）是之後只告訴你「哪些檔位變了」。增量更新會附一個遞增的序號（seq），讓你檢查有沒有漏收。序號對不上，板就不可信，要丟掉重新抓一份完整快照。**

用一個例子走一遍。快照（序號 100）的內容：

| 賣方 | 數量 | 買方 | 數量 |
|---|---|---|---|
| 10,010,000 | 0.3 | 10,000,000 | 0.4 |
| 10,020,000 | 0.5 | 9,990,000 | 0.6 |

接著收到序號 101 的增量：「賣方 10,010,000 的數量變成 0.1；買方 10,000,000 的數量變成 0；買方新增 9,995,000，數量 0.2」。套用之後，買方 10,000,000 被刪除（**數量 0 代表刪除這一檔**，不是顯示 0），板變成：

| 賣方 | 數量 | 買方 | 數量 |
|---|---|---|---|
| 10,010,000 | 0.1 | 9,995,000 | 0.2 |
| 10,020,000 | 0.5 | 9,990,000 | 0.6 |

如果下一個收到的是序號 **103**，期待的是 102，中間漏了一筆。這筆 102 可能刪除了某個檔位，也可能改了數量，沒套用就繼續往下，板上會留下已經不存在的「幽靈檔位」，之後每一筆增量都建立在錯的板上，錯誤越滾越大。所以**序號不連續就不能硬接，要把板標成失效，重新抓完整快照**。

```swift
import Foundation

struct Book {
    var bids: [Decimal: Decimal] = [:]    // 價格 → 數量
    var asks: [Decimal: Decimal] = [:]
    // nil 代表還沒有快照，或已經失效
    var lastSeq: Int64?

    mutating func apply(
        seq: Int64,
        bids newBids: [(Decimal, Decimal)],
        asks newAsks: [(Decimal, Decimal)]
    ) -> String {
        guard let last = lastSeq else { return "要先抓快照" }
        if seq <= last {
            return "重複或過期，忽略"
        }
        if seq != last + 1 {
            // 簿已經不可信，等重新抓快照
            lastSeq = nil
            return "跳號：期待 \(last + 1)，收到 \(seq)，要重新抓快照"
        }
        // 數量 0 代表刪除這一檔
        for (price, qty) in newBids {
            bids[price] = qty == 0 ? nil : qty
        }
        for (price, qty) in newAsks {
            asks[price] = qty == 0 ? nil : qty
        }
        lastSeq = seq
        return "套用成功"
    }
}

func D(_ s: String) -> Decimal { Decimal(string: s)! }

// 快照（seq 100）
var book = Book(
    bids: [D("10000000"): D("0.4"), D("9990000"): D("0.6")],
    asks: [D("10010000"): D("0.3"), D("10020000"): D("0.5")],
    lastSeq: 100
)

print(book.apply(seq: 100, bids: [], asks: []))
// 輸出：重複或過期，忽略
print(
    book.apply(
        seq: 101,
        bids: [(D("10000000"), 0), (D("9995000"), D("0.2"))],
        asks: [(D("10010000"), D("0.1"))]
    )
)
// 輸出：套用成功
print(book.bids.keys.sorted(by: >), book.asks[D("10010000")]!)
// 輸出：[9995000, 9990000] 0.1
print(book.apply(seq: 103, bids: [], asks: []))
// 輸出：跳號：期待 102，收到 103，要重新抓快照
print(book.apply(seq: 102, bids: [], asks: []))
// 輸出：要先抓快照（失效之後，即使序號剛好對上，也要先重新抓快照）
```

**初始化的順序也很重要：先開始暫存增量，再抓快照。** 如果反過來，先抓快照再連線，兩者之間產生的增量會遺失。正確流程：

1. 連上 WebSocket，**先開始暫存**（buffer）收到的增量，先不套用。
2. 抓快照（用 REST 或 `req`），記下快照的序號。例如抓快照的這 200 毫秒內，暫存了序號 98、99、100、101、103 的增量，快照的序號是 100。
3. 丟掉暫存裡序號 **小於等於快照序號** 的增量（98、99、100），因為快照已經包含它們的變更了，重複套用會讓數量算錯。
4. 依序套用剩下的，每一筆都檢查 `seq == lastSeq + 1`：101 接得上，套用；103 期待的是 102，**跳號**（也叫缺口，gap），板失效，重新抓快照。
5. 重新同步（resync）期間，畫面要顯示「更新中」，或維持上一版並標示過期，不要顯示錯誤的板。

重連、App 從背景回前景（背景期間漏掉了增量）、解析失敗，都走同一條「失效、重新同步」的路徑。另外，最佳買價 ≥ 最佳賣價的板，叫交叉簿（crossed book）。正常的板不可能這樣（早就成交了），出現就代表資料出錯，也要重新同步。序號不一定都是「加 1」（有的交易所用「前一個序號」或「起訖範圍」），規則以各家文件為準，原則一樣：**不能確認連續，就重新同步**。

> [!TIP]
> 面試時可以這樣說：「有增量的板，我會先暫存增量、再抓快照，丟掉序號不超過快照的增量，之後每筆檢查序號是不是上一筆加一。跳號就把板標成失效、重新抓快照，期間畫面標示更新中。重連和回前景也走同一條路。數量 0 代表刪除，最佳買價高於等於最佳賣價也要重新同步。」

### 3. 本地的訂單簿要用什麼資料結構？為什麼價格不能用 `Double`？

**買賣兩邊各用一個「價格對應數量」的排序表，價格用 `Decimal`（iOS）或 `BigDecimal`（Android），不要用 `Double`。** 最佳買價、最佳賣價就是兩個表最前面的那一檔，價差（spread）是兩者相減。

兩個重點：

- **價格當作字典的 key 時，`100.0` 和 `100` 必須是同一檔。** Swift 的 `Decimal` 做字典的 key 時，兩者是相等的。但 Kotlin/Java 的 `BigDecimal.equals` 會連小數位數（scale）一起比，`HashMap` 會把它們當成兩個不同的 key，板上就出現兩個價格一樣的檔位。要用 `TreeMap`（用 `compareTo` 比較大小，只看數值），或是先 `stripTrailingZeros()` 再當 key。
- **`Double` 當 key 會有精度問題。** 例如 `0.1 + 0.2` 算出來不等於 `0.3`（見精度與金額單元），價位會對不上。

```kotlin
import java.math.BigDecimal
import java.util.TreeMap

fun main() {
    val hash = HashMap<BigDecimal, Int>()
    hash[BigDecimal("100.0")] = 1
    hash[BigDecimal("100")] = 2
    // 輸出：2（equals 連小數位數都比，被當成兩檔）
    println(hash.size)

    // 買方：價格由高到低（賣方用預設排序，由低到高）
    val bids = TreeMap<BigDecimal, BigDecimal>(Comparator.reverseOrder())
    bids[BigDecimal("100.0")] = BigDecimal("1")
    // compareTo 認為是同一檔，數量被覆蓋成 2
    bids[BigDecimal("100")] = BigDecimal("2")
    bids[BigDecimal("99.5")] = BigDecimal("3")
    // 輸出：2
    println(bids.size)
    // 輸出：2（最佳買價那一檔排在最前面）
    println(bids.firstEntry().value)
}
```

一張板的檔位通常只有幾十到幾百檔，所以簡單的做法就夠用：iOS 可以用字典加上排序的檢視，或維持一個排好序的陣列再用二分搜尋（binary search，每次抓中間比較、一次排除一半）插入；Android 用 `TreeMap` 或排好序的 `ArrayList`。

這個單元的練習題（code 題）就是把這個板（快照、增量、序號檢查、交叉簿）寫出來。

> [!TIP]
> 面試時可以這樣說：「價格用 Decimal 或 BigDecimal，絕對不用 Double。BigDecimal 的 equals 會比小數位數，所以 100.0 和 100 放進 HashMap 會變兩檔，我用 TreeMap 以 compareTo 排序。數量 0 代表刪除這一檔，最佳買價大於等於最佳賣價就當成資料出錯。」

### 4. 依價格間隔（step）合併時，買方、賣方各往哪個方向取整？

**把相近的價格合併成一檔時，買方的價格往下取整到 step 的倍數，賣方往上取整，再把同一檔的數量相加。這樣合併後的最佳買價不會比真實的買價高，最佳賣價不會比真實的賣價低，不會讓使用者以為成交條件比實際更有利。**

例如 step = 1,000 円：

- 買方 `4,860,380` 往下取整，歸到 `4,860,000`。
- 賣方 `4,860,850` 往上取整，歸到 `4,861,000`。

如果買方用四捨五入，買價 4,860,620 會變成 4,861,000，比真實的買價更高，使用者會以為有人願意出更高的價格。

合併的例子：三筆買單 `4,860,380 × 0.2`、`4,860,120 × 0.5`、`4,859,900 × 0.1`，step = 1,000，往下取整後，前兩筆都歸到 4,860,000（數量相加 0.7），第三筆歸到 4,859,000（0.1）。

```swift
import Foundation

func D(_ s: String) -> Decimal { Decimal(string: s)! }

// 把價格取整到 step 的倍數：買方往下（.down），賣方往上（.up）
func bucket(_ price: Decimal, step: Decimal, roundUp: Bool) -> Decimal {
    // 有幾個 step
    var q = price / step
    var r = Decimal()
    // 去掉小數：往下或往上
    NSDecimalRound(&r, &q, 0, roundUp ? .up : .down)
    return r * step
}

// 買方
print(bucket(D("4860380"), step: D("1000"), roundUp: false))
// 輸出：4860000
// 賣方
print(bucket(D("4860850"), step: D("1000"), roundUp: true))
// 輸出：4861000
// 已經對齊的不會多跳一檔
print(bucket(D("4861000"), step: D("1000"), roundUp: true))
// 輸出：4861000

// 合併：同一檔的數量相加
let bids = [
    (D("4860380"), D("0.2")),
    (D("4860120"), D("0.5")),
    (D("4859900"), D("0.1"))
]
var merged: [Decimal: Decimal] = [:]
for (price, qty) in bids {
    merged[bucket(price, step: D("1000"), roundUp: false), default: 0] += qty
}

for price in merged.keys.sorted(by: >) {
    print(price, merged[price]!)
}

// 輸出：
// 4860000 0.7
// 4859000 0.1
```

Android 用 `BigDecimal` 的 `divide(step, 0, RoundingMode.FLOOR)`（買方）或 `RoundingMode.CEILING`（賣方）再乘回 `step`。一律用十進位型別做除法和取整，不要用 `Double` 的 `floor`。

**在哪裡合併？** 可以直接訂閱這套 API 的 `type=step1` 到 `step5`，由伺服器合併，省頻寬而且各裝置一致；也可以 App 自己合併，使用者切換粗細時不用重新訂閱，但要有完整的明細資料。這套 API 的 `stepN` 實際用的取整方向，文件沒有寫（未證實），以實際資料為準；App 自己合併時，方向由你決定，要在規格裡寫清楚。

> [!TIP]
> 面試時可以這樣說：「合併時買方往下取整、賣方往上取整，這樣合併後的最佳買賣價不會比真實的更有利。用 Decimal 做除法和取整，已經對齊的價格不會多跳一檔。可以由伺服器用 stepN 合併，也可以 App 自己合併，看要不要讓使用者即時切換精度。」

### 5. 累計數量和深度條怎麼算？

**累計數量是從最佳價位開始，往外把數量一路加總，意思是「要吃到這個價位，總共需要多少量」。深度條的寬度，通常用「該檔累計量除以兩邊最大的累計量」來算。**

延續第 2 個問題套用 101 之後的板：

- 賣方由最佳價（最低）往上：10,010,000 有 0.1，10,020,000 有 0.5，累計是 0.1、0.6。
- 買方由最佳價（最高）往下：9,995,000 有 0.2，9,990,000 有 0.6，累計是 0.2、0.8。
- 兩邊最大的累計量是 0.8，所以深度條的寬度比例是：賣方 12.5%、75%；買方 25%、100%。

```swift
import Foundation

func D(_ s: String) -> Decimal { Decimal(string: s)! }

// 輸入：從最佳價開始排好的各檔數量。輸出：累計數量
func cumulative(_ qtys: [Decimal]) -> [Decimal] {
    var running: Decimal = 0
    return qtys.map {
        running += $0
        return running
    }
}

// 賣方：10,010,000 有 0.1，10,020,000 有 0.5
let asks = cumulative([D("0.1"), D("0.5")])
// 買方：9,995,000 有 0.2，9,990,000 有 0.6
let bids = cumulative([D("0.2"), D("0.6")])
print(asks, bids)
// 輸出：[0.1, 0.6] [0.2, 0.8]

// 深度條的寬度比例：該檔累計量 ÷ 兩邊最大的累計量
let maxCumulative = max(asks.last!, bids.last!)   // 0.8
print((asks + bids).map { ($0 / maxCumulative) * 100 })
// 輸出：[12.5, 75, 25, 100]
```

累計數量還有一個實用的地方：預估市價單（成行）的成交價。使用者想買 0.5 BTC（Bitcoin，比特幣），賣方累計是 0.1、0.6，所以要吃到第二檔 10,020,000 才夠，預估平均成交價是 (0.1 × 10,010,000 + 0.4 × 10,020,000) ÷ 0.5 = 10,018,000 円。這就是下單前警告「可能的滑價」的依據（見交易所業務入門單元）。但這只是預估，實際成交以伺服器為準。

畫成「深度圖」時，X 軸是價格，Y 軸是累計量，買賣兩邊在中間價附近形成一個「V」字形。

> [!TIP]
> 面試時可以這樣說：「累計數量是從最佳價往外一路加總，深度條的寬度是該檔累計量除以兩邊最大的累計量。累計數量也可以用來預估市價單要吃到哪個價位、平均成交價是多少，不過這只是預估，實際成交以伺服器回報為準。」

### 6. 板更新得很頻繁，畫面怎麼更新才不會卡？

**先在背景套用更新，再以固定頻率取一份目前的板；用價格當識別，比較新舊兩版（diff），只更新有變動的那幾列，不要整個列表重畫。**

diff 的意思是比較新舊兩份資料，找出「新增、移除、數量改變」了哪幾列。板的每一列用價格當識別（同一個價格就是同一列）：

```swift
import Foundation

func D(_ s: String) -> Decimal { Decimal(string: s)! }

struct Level: Equatable {
    // 以價格當 identity：同一個價格就是同一列
    let price: Decimal
    let qty: Decimal
}

/// 比較新舊兩版，回傳新增、移除、數量改變的價格
func diff(old: [Level], new: [Level]) -> (
    added: [Decimal],
    removed: [Decimal],
    changed: [Decimal]
) {
    let oldByPrice = Dictionary(
        uniqueKeysWithValues: old.map { ($0.price, $0.qty) }
    )
    let newByPrice = Dictionary(
        uniqueKeysWithValues: new.map { ($0.price, $0.qty) }
    )
    let added = new.map(\.price).filter { oldByPrice[$0] == nil }
    let removed = old.map(\.price).filter { newByPrice[$0] == nil }
    let changed = new.filter {
        oldByPrice[$0.price] != nil && oldByPrice[$0.price] != $0.qty
    }.map(\.price)
    return (added, removed, changed)
}

let before = [
    Level(price: D("10000000"), qty: D("0.4")),
    Level(price: D("9990000"), qty: D("0.6"))
]
let after = [
    Level(price: D("9995000"), qty: D("0.2")),
    Level(price: D("9990000"), qty: D("0.9"))
]
let result = diff(old: before, new: after)
print(result.added, result.removed, result.changed)
// 輸出：[9995000] [10000000] [9990000]
```

兩個平台都有現成的 diff 工具：

```swift
import UIKit

// 以價格當 identity
struct PriceRow: Hashable { let price: Decimal }

func update(rows: [PriceRow], changed: [PriceRow],
            dataSource: UITableViewDiffableDataSource<Int, PriceRow>) {
    var snapshot = NSDiffableDataSourceSnapshot<Int, PriceRow>()
    snapshot.appendSections([0])
    snapshot.appendItems(rows)
    // iOS 15 起：只更新有變動的列內容，不重建 cell
    snapshot.reconfigureItems(changed)
    dataSource.apply(snapshot, animatingDifferences: false)
}
```

```kotlin
import androidx.recyclerview.widget.DiffUtil
import java.math.BigDecimal

data class LevelRow(val price: BigDecimal, val qty: BigDecimal)

class LevelDiff : DiffUtil.ItemCallback<LevelRow>() {
    // 同一個價格就是同一列
    override fun areItemsTheSame(oldItem: LevelRow, newItem: LevelRow) =
        oldItem.price.compareTo(newItem.price) == 0

    // 數量沒變，這一列就不用更新
    override fun areContentsTheSame(oldItem: LevelRow, newItem: LevelRow) =
        oldItem.qty.compareTo(newItem.qty) == 0
}
```

Jetpack Compose 的 `LazyColumn` 則是用 `key` 指定識別（例如用價格的字串）。

更新的節奏也要處理：板每秒可能更新幾十次，如果每一次都更新畫面，主執行緒會來不及。做法是在背景先套用更新、合併（多筆更新只留最新一筆），再以固定頻率（例如跟著螢幕更新頻率）取一份目前的板做 diff（細節見即時行情 UI 單元）。

畫面上的其他細節：

- 通常每邊顯示 10 到 20 檔；點某個價格，可以帶入下單表單的價格欄，但要對齊 tick size（價格的最小跳動單位，見精度與金額單元）。
- 數字用等寬數字（tabular figures，每個數字寬度一樣的字型設定），數字一直跳動時，欄位寬度才不會跟著抖。
- 板的資料不能是估算成交價的唯一依據，實際成交以伺服器為準。

> [!TIP]
> 面試時可以這樣說：「板更新很頻繁，我會在背景先套用和合併，再以固定頻率取一份目前的板，用價格當識別做 diff，只更新有變動的列：iOS 用 diffable data source 搭配 reconfigureItems，Android 用 DiffUtil，不會整個列表 reloadData。數字用等寬數字，避免欄位寬度抖動。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange05OrderBook**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange05OrderBook**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange05-OrderBook/`，解答在 `labs/ios/Solution/Exchange/Exchange05-OrderBook/`；Android 你寫在 `labs/android/practice/exchange/exchange05-order-book/`，解答在 `labs/android/solution/exchange/exchange05-order-book/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的連 WebSocket**：這個單元的演練都是用假的推送和假的伺服器資料，在記憶體裡直接跑，每次執行的結果都一樣。價格與數量都用 `Decimal`（iOS）、`BigDecimal`（Android）。

### 演練 1：賣光的價位還掛在板上（第 1 題）

**檔案**：`L01_FullSnapshot.swift`（iOS）、`L01_FullSnapshot.kt`（Android）

**情境**：depth 頻道每次推送都帶完整的 `bids`（買方）與 `asks`（賣方），沒有序號，只有時間戳 `ts`。演練依序收到三則推送：ts 1000；ts 1002（買方 10,000,000 和賣方 10,010,000 都成交光了，所以沒有出現在推送裡）；最後才到的 ts 1001（網路上慢了一步的舊資料）。伺服器最新的板是 ts 1002：最佳買 9995000×0.2，最佳賣 10020000×0.5，買賣各 2 檔。

**怎麼寫**

1. **Bug 版**：把每則推送用 `depth.apply` 合併進本地的板（推送裡有的價位更新數量，沒出現的價位不動），也不看 `ts`，一律套用並回傳 `true`。
2. **修正版**：`ts` 不比目前的板新（`push.ts <= lastTs`）就丟掉，回傳 `false`；否則記下 `lastTs`，把 `depth` 換成全新的 `E05Depth()`，再放進這則推送的內容（整份取代）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ts 1002 之後買賣各 3 檔（成交光了的 10,000,000 和 10,010,000 還在），晚到的 ts 1001 也被套用：「最後：最佳買 10000000×0.1，最佳賣 10010000×0.2，買 3 檔，賣 3 檔」 |
| 修正版 | ts 1001 顯示「過期，丟掉」，「最後：最佳買 9995000×0.2，最佳賣 10020000×0.5，買 2 檔，賣 2 檔」，和伺服器一致 |

**為什麼**：依這套交易所的 API 文件，depth 每次推送都是整份最新的板（未證實，以實測為準），所以處理很單純：整份取代，不需要序號。把它當增量合併，是從「快照加增量」的交易所帶過來的習慣：賣光的價位不會再出現在推送裡，合併時沒有人會去刪它，就變成幽靈檔位（板上已經不存在的價位）。`ts` 則用來丟掉順序顛倒的舊資料，例如同時用 `req` 抓一份、又收到推送的時候。如果協定真的是快照加增量，才需要下一題的序號檢查。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L01_FullSnapshot.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L01_FullSnapshot.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L01_FullSnapshot.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L01_FullSnapshot.kt#fixed
```

### 演練 2：漏收一筆更新，板上留下不存在的價位（第 2 題）

**檔案**：`L02_SeqGap.swift`（iOS）、`L02_SeqGap.kt`（Android）

**情境**：增量更新（delta）只告訴你「哪些價位變了」，每筆帶遞增的序號 `seq`。快照在 seq 100，之後依序有 101 到 105。102 的內容是「賣方 10,010,000 賣光了（數量 0）」，但這一則在網路上漏掉了，App 收到的是 101、103、104、105。`E05Depth.apply` 已經處理好「數量 0 就刪除這一檔」。伺服器 seq 105 的板：最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔。

**怎麼寫**

1. **Bug 版**：收到的增量一律直接 `depth.apply`，記下 `seq`，回傳 `.applied`（Android 是 `Applied`），不檢查序號。
2. **修正版**：套用前檢查：`seq` 小於等於目前的，回傳 `.stale`（重複或過期）；不是目前的 `seq + 1`，回傳 `.gap(expected:)`（Android 是 `Gap(seq + 1)`）而且不套用；接得上才 `apply` 並記下 `seq`。回報 gap 時，演練會立刻拿一份最新的快照取代整份板。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 沒有任何跳號訊息；賣方 10,010,000×0.1 永遠留在板上：「最後：seq 105，最佳買 10000000×0.5，最佳賣 10010000×0.1，買 3 檔，賣 3 檔」 |
| 修正版 | 「跳號：期待 102，收到 103，重新抓快照」，快照之後的 104、105 正常套用：「最後：seq 105，最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔」，和伺服器一致 |

**為什麼**：漏掉的那一筆可能刪除了某個價位，沒套用就繼續往下，板上就會留下已經不存在的價位，之後每一筆增量都建立在錯的板上，錯誤越滾越大，而且不會自己修好。所以序號不連續就不能硬接，要把板標成失效，重新抓快照。演練為了簡單，在回報 gap 時立刻換上新的快照；真實的 App 在快照回來之前，收到的增量要先暫存，這個順序在 Exchange04 的演練 5 練過。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L02_SeqGap.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L02_SeqGap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L02_SeqGap.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L02_SeqGap.kt#fixed
```

### 演練 3：最佳買價顯示「數量 0」（第 2 題）

**檔案**：`L03_ZeroQuantity.swift`（iOS）、`L03_ZeroQuantity.kt`（Android）

**情境**：增量更新裡，數量 0 的意思是「這一檔沒有人掛單了」，要把那一檔整個移除，不是顯示 0。快照：買方 10,000,000×0.4、9,990,000×0.6、9,980,000×0.2；賣方 10,010,000×0.3、10,020,000×0.5。增量 1：買方 10,000,000 與賣方 10,010,000 的數量都是 0；增量 2：賣方新增 10,030,000×0.4。伺服器的板：最佳買 9990000×0.6，最佳賣 10020000×0.5，買賣各 2 檔。

**怎麼寫**

1. **Bug 版**：把增量的數量直接存進 `depth.bids`、`depth.asks`（`depth.bids[level.price] = level.qty`），數量 0 也照存。
2. **修正版**：數量是 0 就把那一檔移除（iOS 字典設成 `nil`，Android 用 `remove`），不是 0 才存進去。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 增量 1 之後最佳價位是沒人掛單的幽靈檔位：「最佳買 10000000×0，最佳賣 10010000×0，買 3 檔，賣 2 檔」；最後買 3 檔、賣 3 檔 |
| 修正版 | 增量 1 之後「最佳買 9990000×0.6，最佳賣 10020000×0.5，買 2 檔，賣 1 檔」，最後買賣各 2 檔，和伺服器一致 |

**為什麼**：最佳買價、最佳賣價就是兩邊最前面的那一檔。數量 0 的價位如果留下來，畫面最上面一列就是一個沒有人掛單的價格：使用者以為可以用這個價格成交，市價單的預估成交價也會從這一檔開始算。這是增量更新最常見的實作錯誤，所以面試官常問「數量 0 代表什麼」。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L03_ZeroQuantity.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L03_ZeroQuantity.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L03_ZeroQuantity.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L03_ZeroQuantity.kt#fixed
```

### 演練 4：同一個價位在板上分成兩檔（第 3 題）

**檔案**：`L04_PriceKey.swift`（iOS）、`L04_PriceKey.kt`（Android）

**情境**：買方板的快照（REST）把價格寫成 "4860000.0"、"4859000.0"；之後 WebSocket 的更新把同一個價格寫成 "4860000"（數量變 0.9）、"4859000.00"（數量 0，要刪除這一檔），又新增 "4858000"（數量 0.3）。同一個價格，不同的寫法，兩個來源很常不一致。伺服器的板：4860000×0.9、4858000×0.3，共 2 檔。

**怎麼寫**

1. **Bug 版**：**iOS**：用價格的文字（`priceText`）直接當字典的 key。**Android**：`BigDecimal(priceText)` 直接當 `HashMap` 的 key。
2. **修正版**：**iOS**：先用 `e05(priceText)` 轉成 `Decimal` 再當 key。**Android**：key 先 `stripTrailingZeros()`（去掉尾端的 0）；改用 `TreeMap`（用 `compareTo` 比數值）也可以。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 同一個價位分成兩檔，要刪除的 4,859,000 刪不掉：「最後：共 4 檔：4860000×0.9、4860000×0.4、4859000×0.6、4858000×0.3」 |
| 修正版 | 「最後：共 2 檔：4860000×0.9、4858000×0.3」 |

**為什麼**：價格當作字典的 key 時，`4860000.0` 和 `4860000` 必須是同一檔。Swift 的 `Decimal` 比的是數值，所以沒有問題，問題出在直接拿文字當 key；Kotlin 的 `BigDecimal.equals` 會連小數位數（scale）一起比，`HashMap` 把它們當成兩個 key，板上就出現兩個價格一樣的檔位。至於 `Double`，下一組演練（第 4 題）會看到它的誤差怎麼讓價格歸錯檔。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L04_PriceKey.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L04_PriceKey.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L04_PriceKey.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L04_PriceKey.kt#fixed
```

### 演練 5：合併之後買價變高、賣價變低（第 4 題）

**檔案**：`L05_StepDirection.swift`（iOS）、`L05_StepDirection.kt`（Android）

**情境**：App 自己把價格合併成每 1,000 円一檔（step = 1000），同一檔的數量相加。真實的買方：4,860,620×0.2、4,860,120×0.5、4,859,900×0.1（最佳買價 4,860,620）；真實的賣方：4,861,120×0.3、4,861,850×0.4、4,862,001×0.2（最佳賣價 4,861,120）。合併的原則：不能讓使用者以為成交條件比真實更有利，也就是買價不能比真實高，賣價不能比真實低。

**怎麼寫**

1. **Bug 版**：買方、賣方都四捨五入（iOS 的 `NSDecimalRound` 用 `.plain`，Android 的 `divide(step, 0, RoundingMode.HALF_UP)`）。
2. **修正版**：買方往下取整（`.down`／`RoundingMode.FLOOR`），賣方往上取整（`.up`／`RoundingMode.CEILING`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 最佳買價 4,860,620 變成 4,861,000，最佳賣價 4,861,120 也變成 4,861,000：「買價高於真實，賣價低於真實，是交叉簿」 |
| 修正版 | 買方 4860000×0.7、4859000×0.1，賣方 4862000×0.7、4863000×0.2：「買價不高於真實，賣價不低於真實，不是交叉簿」 |

**為什麼**：買方往下取整、賣方往上取整，合併後的最佳買價不會比真實高，最佳賣價不會比真實低。四捨五入會讓兩邊互相靠近，甚至讓最佳買價大於等於最佳賣價，板變成交叉簿（crossed book）：真實的市場不可能出現這種狀態，因為早就成交了。這套 API 的 `type=step1` 到 `step5` 由伺服器合併，取整方向文件沒有寫（未證實）；App 自己合併時，方向由你決定，要在規格裡寫清楚。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L05_StepDirection.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L05_StepDirection.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L05_StepDirection.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L05_StepDirection.kt#fixed
```

### 演練 6：剛好對齊的價格被歸到下面一檔（第 4 題）

**檔案**：`L06_DoubleBucket.swift`（iOS）、`L06_DoubleBucket.kt`（Android）

**情境**：一檔價格在 80 円左右的幣，買方要合併成每 0.1 円一檔（step = 0.1），往下取整，同一檔的數量相加。買方：80.37×0.2、80.30×0.5、80.29×0.1。80.30 已經對齊 0.1，應該留在 80.3 這一檔；正確的合併結果是 80.3×0.7（80.37 和 80.30）、80.2×0.1（80.29）。

**怎麼寫**

1. **Bug 版**：用 `Double` 計算：`(price / step).rounded(.down) * step`（Android 是 `Math.floor(price / step) * step`）。
2. **修正版**：用十進位型別：iOS 的 `Decimal` 先算 `price / step`，再用 `NSDecimalRound` 往下取整；Android 用 `price.divide(step, 0, RoundingMode.FLOOR)`；最後都乘回 `step`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 剛好對齊的 80.30 被歸到 80.2，80.37 算出來的 80.30000000000001 又自己成為一檔：「合併後的買方：80.3×0.2、80.2×0.6」，「和正確答案不一致」 |
| 修正版 | 「合併後的買方：80.3×0.7、80.2×0.1」，「和正確答案一致」 |

**為什麼**：`Double` 沒有辦法精確表示 0.1，80.30 ÷ 0.1 算出來是 802.9999999999999，往下取整變成 802，剛好對齊的價格就少算了一格；而 80.37 算出來的 80.30000000000001，又和真正的 80.3 不是同一個 key，同一個價位分成兩檔。筆記建議一律用十進位型別做除法和取整，不要用 `Double` 的 `floor`，原因就在這裡。顯示時用格式化函式把小數位數固定住，會把這個誤差藏起來，所以這個 bug 平常看不到，只有數量對不上才會發現。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L06_DoubleBucket.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L06_DoubleBucket.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L06_DoubleBucket.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L06_DoubleBucket.kt#fixed
```

### 演練 7：最佳買價的深度條最長（第 5 題）

**檔案**：`L07_CumulativeOrder.swift`（iOS）、`L07_CumulativeOrder.kt`（Android）

**情境**：深度圖的每一列顯示「累計數量」：從最佳價位開始，往外一路加總，意思是「要吃到這個價位，總共需要多少量」。深度條的寬度是該列的累計量除以兩邊最大的累計量。買方 9,995,000×0.2、9,990,000×0.6；賣方 10,010,000×0.1、10,020,000×0.5。畫面由上到下，最佳價在最上面：賣方由低價排到高價，買方由高價排到低價。

**怎麼寫**

1. **Bug 版**：買方也依價格由低排到高（iOS `$0.sorted { $0.price < $1.price }`，Android `sortedBy { level -> level.price }`），再累計。
2. **修正版**：買方依價格由高排到低（iOS `>`，Android `sortedByDescending`），最佳買價排第一，再累計。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 最佳買價 9,995,000 排在最後，累計 0.8（100%），第一列的 9,990,000 反而只有 0.6：「買方第一列是 9990000，真正的最佳買價是 9995000」 |
| 修正版 | 買方「9995000 累計 0.2（25%）、9990000 累計 0.8（100%）」，賣方「10010000 累計 0.1（12.5%）、10020000 累計 0.6（75%）」 |

**為什麼**：累計數量的意思是「要吃到這一檔，總共需要多少量」，所以一定要從最佳價位開始往外加。賣方最佳價是最低價，買方最佳價是最高價，兩邊的排序方向相反。買方如果忘了反過來排，最佳價位的累計就變成整個買方的總量，深度條的形狀完全顛倒；預估市價單成交價時也是同樣的順序，排錯了算出來的平均成交價會差很多。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L07_CumulativeOrder.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L07_CumulativeOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L07_CumulativeOrder.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L07_CumulativeOrder.kt#fixed
```

### 演練 8：多一檔最佳買價，整張板都重畫（第 6 題）

**檔案**：`L08_DiffByPrice.swift`（iOS）、`L08_DiffByPrice.kt`（Android）

**情境**：買方顯示最好的 5 檔。新的一版比舊的多了一個最佳買價 10,005,000，原本最後一檔 9,980,000 被擠出畫面；另外 9,995,000 的數量從 0.2 變成 0.5，其他列的價格與數量都沒變。該更新的只有 3 列：新增 10,005,000、移除 9,980,000、9,995,000 數量改變。

**怎麼寫**

1. **Bug 版**：比較新舊兩版時用「第幾列」對應：第 index 列和第 index 列比，價格或數量不同就算「數量改變」。
2. **修正版**：用價格當識別：舊版沒有的價格是新增，新版沒有的價格是移除，兩邊都有但數量不同的才是數量改變。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 新增 []，移除 []，數量改變 [10005000, 10000000, 9995000, 9990000, 9985000]：「共要更新 5 列」 |
| 修正版 | 新增 [10005000]，移除 [9980000]，數量改變 [9995000]：「共要更新 3 列」 |

**為什麼**：新增一檔最佳買價之後，原本的每一列都往下移了一格，用位置比較，每一列都在和另一個價格比，全部被判斷成有變動，整張板重畫。板更新得很頻繁，這樣主執行緒會來不及。用價格當識別，才能只更新真正變動的列：iOS 的 diffable data source 用項目的識別（這裡是價格，不要把數量放進識別）加上 `reconfigureItems`，Android 的 `DiffUtil` 用 `areItemsTheSame` 判斷是不是同一列。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L08_DiffByPrice.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange05-OrderBook/L08_DiffByPrice.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange05-order-book/L08_DiffByPrice.kt#bug
```

```lab-solution
android/solution/exchange/exchange05-order-book/L08_DiffByPrice.kt#fixed
```

## 常見陷阱

- **增量更新不檢查序號就套用。** 漏一筆，後面全部都是錯的，而且錯誤不會自己修好。
- **先抓快照，再開始暫存增量。** 兩者之間的增量會遺失。要先暫存、再抓快照。
- **數量 0 沒有刪除。** 板上出現已經不存在的「幽靈檔位」，要把該檔位整個移除，不是顯示 0。
- **用 `Double` 當價格的 key。** 價位對不上。
- **用 `BigDecimal` 當 key，卻用 `equals` 或 `HashMap`。** `1.0` 與 `1.00` 變成兩檔。要用 `TreeMap` 或先去掉尾端的 0。
- **買賣兩邊合併時用同一個取整方向。** 會讓一邊的價格看起來比真實更有利。
- **重新同步期間，仍然顯示舊的板卻沒有標示。** 使用者以為那是目前的價格。
- **板一更新就 `reloadData()`。** 整個列表重畫，高頻更新時主執行緒來不及。

## 面試官想聽到的

1. 說得出完整快照加增量更新的完整流程，包括先暫存再抓快照的順序，以及要丟掉哪些增量。
2. 說得出跳號的處理：偵測、板失效、重新同步，以及畫面上怎麼表現。
3. 資料結構與 Decimal 的使用，以及用最佳買價是否高於等於最佳賣價來檢查資料有沒有出錯。
4. 買方往下、賣方往上取整的理由，以及累計數量、深度條的算法。
5. 用 diff 和局部更新避免整頁重繪，並先在背景合併更新。
6. 能分辨這套交易所 API 文件裡的「每次推完整快照」，與一般交易所的「快照加增量」兩種設計。

> [!FIELD]
> 在一個即時行情 App 裡做過 K 線的串流更新：串流在套用每一筆更新前，都會先檢查能不能接上——時間相同就取代最後一根，時間差剛好是週期的整數倍才新增一根，其他情況就丟掉。這和訂單簿「套用增量前先檢查序號」是同一個思路；回到前景時，也會重新抓資料補齊錯過的部分，和「跳號就重新抓完整快照」的概念一樣。訂單簿的 `apply`、價格合併、累計數量，則可以用 `Decimal` 和純函式練習（就是這個單元的 code 題）。
> 面試時可以這樣說：「我在一個即時行情 App 做過 K 線的即時更新，套用前先檢查能不能接上，回前景會重新抓資料補齊。訂單簿的流程我也練習過：先暫存增量、再抓快照、序號連續才套用、跳號就重新同步，並在背景合併之後再更新畫面。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 價格型別 | `Decimal` | `BigDecimal`（比較用 `compareTo`） |
| 排序結構 | 字典加排序的檢視、排好序的陣列加二分搜尋 | `TreeMap`、排好序的 `ArrayList` |
| 列表只更新有變的列 | `NSDiffableDataSourceSnapshot` 加 `reconfigureItems`（iOS 15 起） | `DiffUtil`／`ListAdapter`；Compose 的 `LazyColumn` 用 `key` |
| 數字顯示 | `monospacedDigit()` 或 `UIFont.monospacedDigitSystemFont` | `fontFeatureSettings = "tnum"` |
| 控制更新頻率 | `CADisplayLink`、`AsyncStream(.bufferingNewest(1))` | `Choreographer`、Flow 的 `conflate()` 或 `sample()` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 訂單簿 | order book | 板（いた） |
| 檔位 | price level | 気配値（けはいね） |
| 完整快照 | snapshot | スナップショット |
| 增量更新 | incremental update／delta | 差分更新 |
| 序號 | sequence number（seq） | シーケンス番号 |
| 重新同步 | resync | 再同期 |
| 交叉簿（買價高於等於賣價） | crossed book | 板の逆転 |
| 累計數量 | cumulative quantity／depth | 累積数量 |
