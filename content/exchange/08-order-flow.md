---
id: exchange-order-flow
title: 下單流程：訂單狀態怎麼合併、重送不會變兩筆（client order id）、撤單和成交同時發生、限流
summary: 下單最危險的不是「送不出去」，而是「送了卻不知道成功沒有」。這個單元說明下單回應代表什麼、訂單狀態的合併規則、用 client order id 讓重送不會變成兩筆、防止連點、撤單和成交同時發生時怎麼處理，以及用 token bucket 做限流。
priority: P0
day: 4
tags: [order, state-machine, idempotency, client-order-id, cancel, rate-limit, token-bucket]
---

## 重點講解

這個單元以一套 Huobi 相容的交易所 API（程式之間溝通用的介面）文件為藍本。實際的交易所 App 走哪一條下單路徑各家不同，但下單流程要處理的問題是一樣的。

這個單元回答六個面試常見問題：

1. 下單 API 回傳成功，代表訂單成交了嗎？
2. 訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？
3. client order id 是什麼？為什麼它能讓重送不會變成兩筆？
4. 網路逾時、使用者連點、App 被系統殺掉時，怎麼確保不重複下單？
5. 使用者按取消的瞬間，訂單剛好成交了，怎麼處理？
6. 怎麼用 token bucket 做限流？

### 1. 下單 API 回傳成功，代表訂單成交了嗎？

**不是。** `POST /v1/order/orders/place` 的回應只有訂單 ID（放在 `data` 欄位），代表伺服器「受理」了這張單。成交、部分成交、取消，都要靠後續的查詢或私有 WebSocket 確認。畫面不能因為下單回應成功，就顯示「已成交」。

```json
{"status": "ok", "data": "59378"}
```

這個 `59378` 就是訂單 ID。畫面這時應該顯示「已送出，確認中」，之後依事件更新成「委託中」「部分成交」「已成交」。

**下單種類。** 日本交易所的常見說法：成行（市價單，不指定價格、立刻照市價成交）和指値（限價單，指定價格）。這套 API 的下單種類（API 的 `type` 參數）有：

| 種類 | 意思 |
|---|---|
| `buy-market`／`sell-market` | 成行（市價單）的買／賣 |
| `buy-limit`／`sell-limit` | 指値（限價單）的買／賣 |
| `buy-ioc`／`sell-ioc` | IOC（immediate or cancel）：能成交的部分立刻成交，剩下的立刻取消 |
| `buy-limit-maker`／`sell-limit-maker` | 只掛單（post-only）：如果價格會立刻成交，系統就拒絕這張單 |

**訂單狀態。** 文件列出的狀態：

| 狀態 | 意思 |
|---|---|
| `created` | 訂單建立 |
| `submitted` | 已送出（已進入委託） |
| `partial-filled` | 部分成交 |
| `partial-canceled` | 部分成交之後取消（終態） |
| `filled` | 全部成交（終態） |
| `canceled` | 取消（終態） |
| `canceling` | 取消中 |

終態（final state）的意思是：進入之後就不會再離開的狀態。除了文件的三個終態（`filled`、`canceled`、`partial-canceled`），App 本地還會多一個 `rejected`（被拒絕）。

**兩個通道，兩種資料。** 同一張單的資訊會從兩條路徑到達：

- **REST**：下單、取消、查詢。REST 是用一個 HTTP（網頁使用的通訊協定）請求取回資料的介面，查到的資料是權威的，但是比較慢。
- **私有 WebSocket**：WebSocket 是 App 和伺服器之間保持連線的通道，「私有」代表要登入才能訂閱自己的資料。主要有三個頻道：`orders#${symbol}`（訂單事件）、`trade.clearing#${symbol}#${mode}`（成交，`mode` 設 1 時連取消也一起推送）、`accounts.update#${mode}`（資產變動，帶有 `seqNum` 序號，可以用來丟掉過期的更新）。

文件有一個重要的警告：**`trade.clearing` 不保證順序**。例如 IOC 單成交之後，剩下的部分會被取消，取消的通知可能比成交的通知先到。順序有依賴時，要使用 `orders#`。

查詢訂單時的欄位也要看清楚：`field-amount` 是**累計**成交量，`field-cash-amount` 是累計成交金額；`/matchresults` 才是逐筆成交，每筆有 `trade-id`、`match-id`、`role`（taker 是主動吃單的一方，maker 是掛單等人來吃的一方）。

結論：同一張單的訊息，會從多條路徑、以任意順序到達，App 需要一套**能抵抗亂序的合併規則**，這就是下一題。

> [!TIP]
> 面試時可以這樣說：「下單回應只是受理，回傳的是訂單 ID，不是成交結果。成交和取消要靠私有 WebSocket 和查詢確認，畫面先顯示確認中。另外文件說 `trade.clearing` 不保證順序，所以 App 要有抗亂序的合併規則。」

### 2. 訂單有哪些狀態？事件亂序或重複時，怎麼合併成正確的狀態？

**把訂單狀態的更新寫成一個純函式 `reduce(order, event) -> order`：輸入目前的訂單和一個事件，輸出新的訂單。** 再加上四條規則：終態不可離開、成交量用伺服器給的累計值而且只增不減、以伺服器為準、容忍亂序。

先說名詞：狀態機（state machine）是列出所有狀態，以及「在某個狀態收到某個事件，會變成什麼狀態」的規則。reducer 是這個規則寫成的函式；純函式的意思是同樣的輸入一定得到同樣的輸出、沒有副作用，所以可以用一串事件去測試，也可以重播。

App 本地的狀態比文件多幾個：`pending`（本地已送出，還沒收到 ack，ack 是伺服器的確認回覆）、`canceling`（使用者按了取消，等伺服器確認）、`rejected`（被拒絕）。

**四條規則：**

1. **終態不可離開。** `filled`、`canceled`、`partialCanceled`、`rejected` 之後，再收到 `accepted`、重複的取消，一律忽略。（例外：`canceled` 之後如果又收到更大的累計成交量，未滿量時改成 `partialCanceled`，滿量時改成 `filled`，見第 4 條。）
2. **成交量用累計值，只增不減。** 伺服器給的是「到目前為止一共成交多少」。小於或等於目前值的事件，是過期或重複的，忽略；超過委託量的，是無效的，也忽略。
3. **以伺服器為準。** 樂觀更新（先假設會成功，讓畫面先動）的狀態 `pending`、`canceling`，會被伺服器的事件覆蓋。按下取消後只能顯示 `canceling`，不能直接顯示 `canceled`。
4. **容忍亂序。** 成交先於 ack 到達、取消先於最後一筆成交到達，都要能收斂到正確的最終狀態。

為什麼要用累計值，不用增量？看這個例子：伺服器依序送來累計成交量 0.1、0.4、0.2（過期的）、0.4（重複的）、0.9。

```swift
var filled = Decimal(0)
for total in ["0.1", "0.4", "0.2", "0.4", "0.9"] {
    let t = Decimal(string: total)!
    if t > filled {          // 比目前的累計量大，才是新的資料
        filled = t
        print("update", filled)
    } else {                 // 小於或等於目前的值：過期或重複，忽略
        print("ignore", t)
    }
}
// 輸出（每行一個）：update 0.1、update 0.4、ignore 0.2、ignore 0.4、update 0.9
// 最終 filled = 0.9
```

如果誤把這五個數字當成「增量」加起來，會得到 2.0，遠遠超過實際成交的 0.9。用累計值，重複和亂序的事件都不會破壞成交量。

下表是本單元程式題的 reducer 規則（五種事件）：

| 事件 | 規則 |
|---|---|
| `accepted`（伺服器受理） | 只有 `pending` → `submitted`，其他狀態不變 |
| `rejected`（被拒絕） | 只有 `pending` → `rejected`，其他狀態不變 |
| `fill(累計量)` | 如果已經是 `rejected`、累計量小於或等於目前成交量、或超過委託量，整個事件忽略。否則更新成交量，累計量等於委託量就是 `filled`；否則 `pending`／`submitted`／`partialFilled` → `partialFilled`，`canceling` 維持 `canceling`，`canceled`／`partialCanceled` → `partialCanceled` |
| `cancelRequested`（使用者按取消） | `pending`／`submitted`／`partialFilled` → `canceling`，其他不變 |
| `canceled`（伺服器確認取消） | `pending`／`submitted`／`partialFilled`／`canceling` → 已有成交量就是 `partialCanceled`，否則是 `canceled`；終態不變 |

用一串亂序的事件走一遍。委託量 2.0，事件依序到達：

| 事件 | 結果 |
|---|---|
| `accepted` | `submitted`，成交量 0 |
| `fill(累計 0.5)` | `partialFilled`，成交量 0.5 |
| `canceled` | 已有成交量 0.5，變成 `partialCanceled` |
| `fill(累計 0.3)`（晚到的過期事件） | 0.3 小於 0.5，忽略 |
| `fill(累計 1.2)` | 1.2 大於 0.5、小於委託量，成交量更新為 1.2；狀態維持 `partialCanceled`（不會回到 `partialFilled`） |

最後一步代表「取消的通知比成交的通知先到」，正是文件警告的亂序情況。reducer 寫成純函式，這種案例就可以直接用事件序列寫成單元測試。

> [!TIP]
> 面試時可以這樣說：「我會把訂單合併寫成純函式 reducer。終態不可離開，成交量用伺服器給的累計值而且只增不減，所以重複和亂序的事件都不會壞掉。樂觀的狀態會被伺服器事件覆蓋，用事件序列就能測亂序案例。」

### 3. client order id 是什麼？為什麼它能讓重送不會變成兩筆？

**client order id 是 App 自己產生的訂單編號，每一次下單的意圖產生一個，同一次意圖的所有重送都用同一個。** 伺服器看到相同的編號，就知道這是同一次下單，而不是新的一筆。這種「同一個請求不管送幾次，效果都和送一次一樣」的性質，叫冪等（idempotent）；這個編號就是冪等鍵（idempotency key）。

這套 API 的下單參數 `client-order-id`，依文件：最長 64 個字元，**8 小時內必須唯一**。文件的參數表標為必填，但文件的下單範例沒有帶它，私有 WebSocket 的說明又寫成選填，實際是否必填需確認。

編號可以用 UUID（Universally Unique Identifier，隨機產生、幾乎不會重複的識別碼）產生。關鍵是**什麼時候產生**：在使用者按下按鈕、送出請求**之前**就產生，並且跟著訂單草稿一起保存在本機。逾時重送時，用草稿裡同一個編號；不要在每次網路重試時重新產生，否則每次都是新的編號，等於沒有冪等。

```swift
struct OrderDraft: Codable {
    let clientOrderId: String   // 按下按鈕時產生，隨草稿保存；所有重送都用這一個
    let symbol: String
    let amount: Decimal
    let price: Decimal

    static func make(symbol: String, amount: Decimal, price: Decimal) -> OrderDraft {
        // UUID 去掉 "-" 之後是 32 個字元，在 64 字元的上限內
        OrderDraft(clientOrderId: UUID().uuidString.replacingOccurrences(of: "-", with: ""),
                   symbol: symbol, amount: amount, price: price)
    }
}

let draft = OrderDraft.make(symbol: "btcjpy", amount: Decimal(string: "0.01")!, price: Decimal(string: "4800000")!)
print(draft.clientOrderId.count)   // 輸出：32
```

Android 的對應寫法是 `UUID.randomUUID().toString().replace("-", "")`。

有一件事文件沒有說明：用相同的 `client-order-id` 重複送出時，伺服器會拒絕並回傳錯誤，還是回傳原本那一張單（未證實）。所以逾時之後，不能只靠「重送一次，伺服器會自己處理」，要先對帳，下一題說明。

> [!TIP]
> 面試時可以這樣說：「`client-order-id` 是冪等鍵。我在使用者按下按鈕、送出之前就產生，隨草稿保存，同一次意圖的所有重送都用同一個，不是每次網路重試就換。這套 API 的文件規定最長 64 字元、8 小時內唯一。」

### 4. 網路逾時、使用者連點、App 被系統殺掉時，怎麼確保不重複下單？

**三道防線：按鈕和狀態一起擋住連點；逾時不自動重送，先用查詢對帳；未確認的下單存到本機，App 被殺掉再回來時先對帳。**

**第一道：防止連點。** 使用者按下「買入」之後，要立刻把按鈕鎖起來、顯示「確認中」。光鎖按鈕還不夠，因為畫面更新可能晚一個畫面才生效，使用者有機會在這之前點第二次。所以在 ViewModel 裡也要用狀態擋住：已經在送出中，就直接忽略後續的點擊。

```swift
@MainActor
final class OrderViewModel {
    enum Phase { case editing, submitting(OrderDraft) }
    private(set) var phase: Phase = .editing

    func tapBuy(draft: OrderDraft) {
        guard case .editing = phase else { return }   // 已經在送出中，忽略第二次、第三次點擊
        phase = .submitting(draft)
        // ...保存草稿，送出請求
    }
}
// 連續呼叫 tapBuy 三次，只有第一次會送出請求
```

**第二道：逾時不等於失敗。** 逾時只代表沒有收到回應，伺服器可能已經建立了訂單。這時不要自動重送，要先查詢，確認訂單是不是已經存在：

- 這套 API 的文件沒有「用 client-order-id 查單筆訂單」的端點（本機核對）。對帳的做法是：用「未成交委託清單」（`GET /v1/order/openOrders`）或「委託歷史查詢」（`GET /v1/order/orders`），再依回傳資料裡的 `client-order-id` 比對。
- 對帳有幾個限制：文件寫明**已取消的訂單只能查過去 2 小時**，而且委託歷史查詢的 `states` 參數是必填的；另外文件的未成交清單範例裡，回傳的 `client-order-id` 是空字串，要先實測「帶了 id 的訂單是否會回傳」，才能靠它比對。
- 查不到，才讓使用者決定要不要重送；重送時用**同一個** `client-order-id`。

**第三道：App 被殺、畫面重建、進背景。** 畫面旋轉只會重建畫面（Activity），ViewModel 還在；但系統回收記憶體把 App 殺掉（process death）之後，原本記在記憶體裡的「有一筆下單還沒確認」就消失了。所以送出之前，要把草稿（包含 `client-order-id`）存到本機：iOS 可以存資料庫或 `UserDefaults`，Android 用 `SavedStateHandle` 加資料庫。App 重新啟動後，先檢查有沒有未確認的草稿，有的話先對帳，再決定畫面要顯示什麼。回到前景時，也要重新抓取一次最新狀態，並重新訂閱私有 WebSocket。

> [!TIP]
> 面試時可以這樣說：「逾時我不會自動重送，因為伺服器可能已經收到了。我會用未成交委託清單或委託歷史查詢，依 `client-order-id` 對帳，查不到才讓使用者決定；重送時用同一個 id。按鈕鎖定外，ViewModel 也用狀態擋住重複點擊，未確認的草稿存在本機，App 被殺掉之後回來先對帳。」

### 5. 使用者按取消的瞬間，訂單剛好成交了，怎麼處理？

**這是正常情況，不是 bug。** `POST /v1/order/orders/{order-id}/submitcancel` 只是「送出取消請求」，文件寫明實際結果要查詢訂單。如果訂單在取消送到之前已經成交（進入終態），取消請求會得到 `order-orderstate-error`。這時不要彈出錯誤，而是刷新這張單的狀態，顯示「已成交」。

做法是：按下取消後，畫面先進入 `canceling`（取消中），等伺服器的事件確認 `canceled` 或 `partial-canceled`，才算取消完成。取消期間如果又收到成交，累計成交量要照常更新。三種結果：

| 情況 | 事件順序 | 最終結果 |
|---|---|---|
| 取消時還完全沒成交 | `cancelRequested` → `canceled` | `canceled` |
| 取消前已部分成交，取消時剩下的還沒成交 | `fill(0.4)` → `cancelRequested` → `canceled` | `partialCanceled`（成交量 0.4） |
| 取消請求送出時，其實已經全部成交 | `cancelRequested` → `fill(全部)` → 晚到的 `canceled` | `filled`，晚到的取消事件被忽略 |

批次取消（`batchcancel`，一次最多 50 筆）是逐筆回報成功或失敗，畫面也要逐筆更新。文件建議使用 `order-ids`，原因是比 App 自己訂的編號穩定。

> [!TIP]
> 面試時可以這樣說：「取消只是送出請求，我會先顯示取消中，等伺服器事件確認。對已經成交的單取消，會得到 `order-orderstate-error`，這是正常情況，我會刷新訂單，顯示已成交，而不是跳錯誤。取消期間收到的成交，累計成交量照常更新。」

### 6. 怎麼用 token bucket 做限流？

**token bucket（令牌桶）是一個「桶子」：桶裡最多放 N 個 token，每發出一個請求要先拿一個 token，token 以固定的速度補充。桶空了就要等，不能送。** 桶的容量決定允許的突發量，補充速度決定長期的平均速率。

這套 API 的限制（依文件）：

- 需要簽名的 API：**每個 API key 每秒 10 次**；公開 API：每個 IP（網路位址）每秒 10 次。部分 API 另外有更嚴格的限制（文件另有說明）。
- 私有 WebSocket：每個 API key 同時最多 10 條連線，每個 IP 每秒最多新增 100 條連線，每秒最多 50 個請求。

為什麼要在 App 端做？超過限制時，請求會被伺服器拒絕。App 端用 token bucket 先排隊或提示使用者，比送出後才被拒絕好。而且下單、取消、查詢共用同一個額度，查詢如果一直輪詢，會把下單的額度用光，所以查詢要節制，優先用私有 WebSocket 推送。

以「容量 10、每秒補充 10 個」為例：

| 時間 | 動作 | 結果 |
|---|---|---|
| 0 毫秒 | 連續送 10 個請求 | 全部成功，桶空了 |
| 0 毫秒 | 送第 11 個 | 失敗，還要等 100 毫秒 |
| 60 毫秒 | 問還要等多久 | 還要等 40 毫秒 |
| 100 毫秒 | 送第 11 個 | 成功（每 100 毫秒補一個 token） |

實作的重點：

- **注入時鐘（毫秒）**，不要在函式裡直接讀現在的時間，測試才不用等真實的時間。
- **用整數運算**避免浮點數誤差：以「千分之一個 token」為單位。每過 1 毫秒，補充 `refillPerSecond` 個千分之一 token；取得 1 個 token 需要 1000 個千分之一 token。
- **要等多久向上取整**：每秒 3 個時，補一個要 1000 ÷ 3 ＝ 333.33… 毫秒，所以是 334 毫秒；等 333 毫秒還不夠。
- **處理時鐘倒退**：現在的時間比上次記錄的時間早，就不補充，也不能讓之後的補充算錯。
- 桶會同時被很多地方使用，所以要放在 actor（iOS）或用 `Mutex` 保護（Android）。

```swift
// 使用方式（示意）：取不到 token 就等，不要送出之後才被伺服器拒絕
actor RateLimiter {
    private var bucket = TokenBucket(capacity: 10, refillPerSecond: 10, startMs: 0)

    func acquire() async {
        while true {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if bucket.tryAcquire(nowMs: now) { return }
            let wait = bucket.waitMs(nowMs: now)   // 還要等幾毫秒（向上取整），不消耗 token
            try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000)
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「限流我用 token bucket，容量是允許的突發量，補充速度是每秒 10 個。時鐘從外面注入、用整數運算，等待時間向上取整，也處理時鐘倒退。下單、取消、查詢共用同一個額度，所以查詢優先用 WebSocket 推送，不要輪詢。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange08OrderFlow**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange08OrderFlow**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange08-OrderFlow/`，解答在 `labs/ios/Solution/Exchange/Exchange08-OrderFlow/`；Android 你寫在 `labs/android/practice/exchange/exchange08-order-flow/`，解答在 `labs/android/solution/exchange/exchange08-order-flow/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的下單**：這個單元的演練都是假的伺服器、編好的事件序列，時間從參數傳進來（不用等真實的時鐘），每次執行的結果都一樣。

### 演練 1：下單回應一回來就顯示「已成交」（第 1 題）

**檔案**：`L01_AckIsNotFill.swift`（iOS）、`L01_AckIsNotFill.kt`（Android）

**情境**：使用者按下「買入」，送出一張 IOC（immediate or cancel：能成交的立刻成交，剩下的立刻取消）買單，數量 0.01 BTC。這時板上沒有符合價格的賣單。下單回應 `{"status":"ok","data":"59378"}` 很快就回來，只代表伺服器「受理」了這張單；約 0.3 秒後，私有 WebSocket 的訂單事件才通知：這張單被取消了，成交量 0。

**怎麼寫**

1. **Bug 版**：收到下單回應就把畫面改成「已成交」、成交量填委託量 0.01，還讓成交通知加 1 次。
2. **修正版**：下單回應只代表受理：畫面顯示「已送出，確認中」，成交量維持 0、不發成交通知；等訂單事件來了，才依事件更新。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 下單回應後畫面顯示「已成交」、成交量 0.01，彈出 1 次成交通知，0.3 秒後才更正：「最後：下單回應後畫面顯示「已成交」，成交通知 1 次，最終狀態「已取消」」 |
| 修正版 | 下單回應後畫面顯示「已送出，確認中」：「最後：下單回應後畫面顯示「已送出，確認中」，成交通知 0 次，最終狀態「已取消」」 |

**為什麼**：`POST /v1/order/orders/place` 的回應只有訂單 ID（放在 `data`），代表伺服器受理了這張單。成交、部分成交、取消，都要靠後續的查詢或私有 WebSocket 確認。Bug 版的畫面只在 0.3 秒內是錯的，但使用者那一刻看到「已成交」，可能就去做下一步（出金、下別的單），而且成交通知已經發出去了，收不回來。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L01_AckIsNotFill.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L01_AckIsNotFill.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L01_AckIsNotFill.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L01_AckIsNotFill.kt#fixed
```

### 演練 2：成交量比委託量還多，訂單卻顯示已成交（第 2 題）

**檔案**：`L02_CumulativeFill.swift`（iOS）、`L02_CumulativeFill.kt`（Android）

**情境**：委託量 1.0 BTC。伺服器每次推送「到目前為止一共成交多少」（累計值），依序到達：0.1、0.4、0.2（過期的）、0.4（重複的）、0.9。正確的最終狀態是 `partialFilled`（部分成交），成交量 0.9；其中 0.2 和第二個 0.4 是過期或重複的，要忽略（2 個）。reducer 是一個純函式：輸入目前的訂單和一個事件，輸出新的訂單；事件要忽略就原樣回傳。

**怎麼寫**

1. **Bug 版**：把每個 fill 事件的數字加到成交量上（增量的想法），成交量大於等於委託量就是 `filled`，否則 `partialFilled`。
2. **修正版**：累計值只增不減：小於或等於目前成交量（過期、重複），或大於委託量（無效）的事件，原樣回傳訂單；否則成交量換成這個累計值，等於委託量是 `filled`，否則 `partialFilled`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 成交量一路是 0.1、0.5、0.7、1.1（超過委託量，狀態變成 `filled`）、2：「最後：filled 2，忽略了 0 個過期或重複的事件」 |
| 修正版 | 0.2 和第二個 0.4 顯示「（忽略）」：「最後：partialFilled 0.9，忽略了 2 個過期或重複的事件」 |

**為什麼**：為什麼伺服器給累計值，不給增量？因為訊息會重複、會亂序：累計值只要「取最大的那個」，重複和亂序的事件都不會破壞成交量；增量一旦重複，成交量就加倍。查詢訂單時的 `field-amount` 也是累計成交量。同時要注意，累計值超過委託量的事件是無效的，不能直接採用。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L02_CumulativeFill.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L02_CumulativeFill.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L02_CumulativeFill.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L02_CumulativeFill.kt#fixed
```

### 演練 3：訂單取消了，又變回委託中（第 2 題）

**檔案**：`L03_TerminalState.swift`（iOS）、`L03_TerminalState.kt`（Android）

**情境**：委託量 2.0 BTC，本地狀態從 `pending`（本地已送出，還沒收到 ack，ack 是伺服器的確認回覆）開始。私有推送不保證順序，事件依序到達：`accepted`（受理）、fill 累計 0.5、`canceled`（取消，已有成交量，所以是 `partialCanceled`）、`accepted`（晚到的重複事件）、fill 累計 0.3（過期）、fill 累計 1.2。正確的最終狀態是 `partialCanceled`，成交量 1.2：取消的通知比最後一筆成交先到，成交量照常更新，但狀態不會退回 `partialFilled`。成交量只增不減的規則已經寫好，要你寫的是狀態怎麼轉移。

**怎麼寫**

1. **Bug 版**：`accepted` 一律把狀態改成 `submitted`；fill 沒滿量時，狀態一律是 `partialFilled`。
2. **修正版**：`accepted` 只有 `pending` 才變成 `submitted`；fill 沒滿量時，`canceling` 維持 `canceling`，`canceled` 或 `partialCanceled` 維持 `partialCanceled`，其他才是 `partialFilled`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 取消之後，晚到的 `accepted` 把狀態改回 `submitted`，最後的 fill 變成 `partialFilled`：「最後：partialFilled 1.2，忽略了 1 個事件」 |
| 修正版 | 晚到的 `accepted` 與過期的 fill 0.3 顯示「（忽略）」，fill 1.2 更新成交量但狀態維持 `partialCanceled`：「最後：partialCanceled 1.2，忽略了 2 個事件」 |

**為什麼**：終態（final state）的意思是進入之後就不會再離開的狀態：`filled`、`canceled`、`partialCanceled`、`rejected`。晚到的 `accepted` 或過期的事件如果能改寫終態，已經取消的訂單會在畫面上「復活」，使用者可能對一張不存在的委託做決定。文件警告 `trade.clearing` 不保證順序（例如 IOC 單的取消通知可能比成交通知先到），所以 reducer 要能抵抗亂序，同時 `canceled` 之後如果又收到更大的累計成交量，成交量要照常更新。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L03_TerminalState.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L03_TerminalState.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L03_TerminalState.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L03_TerminalState.kt#fixed
```

### 演練 4：只按一次買入，伺服器有三張訂單（第 3 題）

**檔案**：`L04_ClientOrderId.swift`（iOS）、`L04_ClientOrderId.kt`（Android）

**情境**：使用者按一次「買入」。網路層最多自動重試 3 次：第 1、2 次伺服器其實收到了、也記下了這張單，但回應在路上遺失（逾時）；第 3 次才收到回應。假伺服器的規則和這套 API 的 `client-order-id` 一樣：同一個編號只會記一張單；沒有帶編號（空字串）的請求會被拒絕。逾時之後先用查詢確認再決定重不重送，Platform05 演練 5 練過；這個演練看的是：自動重試時，編號什麼時候產生。

**怎麼寫**

1. **Bug 版**：每一次網路嘗試都產生一個新的編號：`clientOrderId(attempt:)` 回傳 `UUID().uuidString`（Android 是 `UUID.randomUUID().toString()`）。
2. **修正版**：使用者按下「買入」的那一刻（`tapBuy`）就產生編號，存進 `draftId`；`clientOrderId(attempt:)` 每次都回傳 `draftId`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 三次嘗試分別帶編號 A、B、C：「最後：伺服器上有 3 張訂單（使用者只按了一次），3 次嘗試用了 3 種不同的編號」 |
| 修正版 | 三次嘗試都帶編號 A：「最後：伺服器上有 1 張訂單（使用者只按了一次），3 次嘗試用了 1 種不同的編號」 |

**為什麼**：client order id 是冪等鍵：同一個請求不管送幾次，效果都和送一次一樣。伺服器靠編號認出「這是同一次下單」，所以編號要在使用者按下按鈕、送出請求之前就產生，隨草稿保存，同一次意圖的所有重送都用同一個。如果每次網路重試都換新的編號，伺服器看到的就是三次不同的下單，等於沒有冪等。這套 API 的文件規定 `client-order-id` 最長 64 個字元、8 小時內必須唯一；用相同的編號重複送出時，伺服器是拒絕還是回傳原本那張單，文件沒有說明（未證實）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L04_ClientOrderId.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L04_ClientOrderId.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L04_ClientOrderId.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L04_ClientOrderId.kt#fixed
```

### 演練 5：按了取消顯示已取消，其實訂單早就成交了（第 5 題）

**檔案**：`L05_CancelRace.swift`（iOS）、`L05_CancelRace.kt`（Android）

**情境**：一張限價買單掛在板上。就在使用者按下取消的前一刻，訂單全部成交了（伺服器上已經是 `filled`）。取消請求送到伺服器，被拒絕，錯誤碼 `order-orderstate-error`（訂單已經是終態）；接著私有 WebSocket 通知訂單全部成交。取消請求只是「送出取消的要求」，結果以伺服器為準；這是正常情況，不是 bug。

**怎麼寫**

1. **Bug 版**：按下取消，畫面馬上變成「已取消」；取消請求被拒絕時，彈出錯誤視窗（`errorPopups` 加 1）。
2. **修正版**：按下取消，畫面顯示「取消中」，等伺服器確認；取消請求被拒絕時，不彈錯誤，改成呼叫 `fetchOrder()` 向伺服器查詢這張單的狀態，更新畫面（已成交）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 按下取消就顯示「已取消」，被拒絕時彈出 1 次錯誤視窗：「最後：畫面「已成交」，錯誤視窗 1 次，使用者曾經看到「已取消」：是」 |
| 修正版 | 按下取消顯示「取消中」，被拒絕後直接變成「已成交」：「最後：畫面「已成交」，錯誤視窗 0 次，使用者曾經看到「已取消」：否」 |

**為什麼**：取消是一個「請求」，不是一個「結果」：按下取消的瞬間，訂單可能已經成交，這是撤單和成交的競態（race，兩件事的先後順序決定結果）。畫面應該先顯示 `canceling`（取消中），等伺服器的事件確認 `canceled` 或 `partialCanceled`，才算取消完成。對已成交的訂單取消會得到 `order-orderstate-error`，這時要刷新這張單的狀態、顯示「已成交」，而不是跳出錯誤。否則使用者看到「已取消」，以為資金釋放了，結果訂單其實已經成交。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L05_CancelRace.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L05_CancelRace.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L05_CancelRace.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L05_CancelRace.kt#fixed
```

### 演練 6：閒置一分鐘，回來一次打出 600 個請求（第 6 題）

**檔案**：`L06_BucketCap.swift`（iOS）、`L06_BucketCap.kt`（Android）

**情境**：限流器是 token bucket：容量 10、每秒補 10 個 token，一個請求用掉 1 個。為了避免浮點數誤差，用整數，單位是「千分之一個 token」：每過 1 毫秒補 `refillPerSecond`（10）個千分之一 token，取得 1 個 token 要 1000 個；時間從參數傳進來（注入時鐘）。演練：0 毫秒時把桶裡的 token 用光（成功 10 次）；閒置 60 秒；60,000 毫秒時在同一毫秒連續取 token，看成功幾次。

**怎麼寫**

1. **Bug 版**：`refill` 補充時沒有封頂：`milliTokens += elapsed * refillPerSecond`。
2. **修正版**：補充之後用 `min(capacity * 1000, …)` 封頂（Android 是 `minOf`）；時鐘倒退（經過的時間是負的）時，經過的時間當作 0。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 閒置 60 秒累積了 600 個 token，一次成功 600 次：「最後：閒置之後一次最多成功 600 次（正確答案：10 次）」 |
| 修正版 | 閒置再久，一次最多只成功 10 次：「最後：閒置之後一次最多成功 10 次（正確答案：10 次）」 |

**為什麼**：token bucket 的容量決定允許的突發量，補充速度決定長期的平均速率。沒有封頂的話，桶子會變成無底洞：閒置越久、累積的 token 越多，回來一次可以打出遠超過限制的請求。這套 API 的簽名請求每個 API key 每秒只能 10 次，一次打 600 個，伺服器全部拒絕，連正常的下單也被擋掉。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L06_BucketCap.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L06_BucketCap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L06_BucketCap.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L06_BucketCap.kt#fixed
```

### 演練 7：一直輪詢，卻拿不到 token（第 6 題）

**檔案**：`L07_BucketFraction.swift`（iOS）、`L07_BucketFraction.kt`（Android）

**情境**：限流器是 token bucket：容量 3、每秒補 3 個 token（約每 333 毫秒補一個）。0 毫秒時先把桶用光，之後每 100 毫秒問一次「有沒有 token」，問 30 次（3 秒）。正確答案：每秒補 3 個，3 秒內應該成功 9 次；之後再隔 1 秒一次問 5 次，成功 3 次（桶補滿了）。

**怎麼寫**

1. **Bug 版**：補充時用整數 token：`whole = elapsed * refillPerSecond / 1000`，不到 1 個就不補，但每次都把 `lastMs`（上次補充的時間）換成現在。
2. **修正版**：以千分之一個 token 為單位累積：`milliTokens = min(capacity * 1000, milliTokens + elapsed * refillPerSecond)`（Android 是 `minOf`），不丟零頭，`lastMs` 換成現在。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問才成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 0 次；隔 1 秒一次問，成功 3 次」 |
| 修正版 | 3 秒內成功 9 次，隔 1 秒成功 3 次：「最後：每 100 毫秒問一次，3 秒內成功 9 次；隔 1 秒一次問，成功 3 次」 |

**為什麼**：每 100 毫秒只經過 100 毫秒，100 × 3 ÷ 1000 用整數除法等於 0，剩下的 0.3 個 token 被丟掉；偏偏 `lastMs` 又被更新成現在，下一次又只經過 100 毫秒，永遠累積不起來。呼叫越頻繁，實際的速率越低，看起來像限流器壞了。筆記的做法是以「千分之一個 token」為單位：每過 1 毫秒補 `refillPerSecond` 個千分之一 token，取得 1 個要 1000 個，用整數就不會丟零頭，也不會有浮點數誤差；要等多久則向上取整（每秒 3 個時補一個要 333.33… 毫秒，所以是 334 毫秒）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L07_BucketFraction.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange08-OrderFlow/L07_BucketFraction.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange08-order-flow/L07_BucketFraction.kt#bug
```

```lab-solution
android/solution/exchange/exchange08-order-flow/L07_BucketFraction.kt#fixed
```

第 4 題的三道防線，前面的單元已經分別練過：下單要先鎖住按鈕再開始非同步工作（Platform02 演練 9）、逾時之後先用 `clientOrderId` 查詢再決定要不要重送（Platform05 演練 5）、回到前景不要重送同一筆委託（Platform01 演練 6），這個單元不重複。把未確認的草稿存到本機、App 被系統殺掉後回來先對帳，是流程題，沒有可以穩定自動檢查的演練。

## 常見陷阱

- **把下單 API 的成功當成成交。** 畫面直接顯示「已成交」。下單回應只是受理，成交要靠後續的事件確認。
- **用「增量」更新成交量。** 重複的事件會讓成交量加倍。要用伺服器給的累計值，而且只增不減。
- **終態被晚到的事件覆蓋。** 例如已經 `canceled` 之後，晚到的 `submitted` 把狀態改回去。
- **逾時就自動重送。** 沒有冪等鍵，或沒有先查詢，造成重複下單。
- **每次網路重試都換新的 client order id。** 等於沒有冪等。
- **把 `trade.clearing` 當成唯一的訂單狀態來源。** 它不保證順序，順序有依賴時要用 `orders#`。
- **撤單失敗就彈出錯誤。** 其實是訂單已經成交，要刷新訂單並顯示「已成交」。
- **輪詢查詢吃光 10 req/s 的額度。** 下單反而被限流。
- **把 App 自己算出的可用餘額當成真相。** 以伺服器為準。

## 面試官想聽到的

1. 完整的訂單狀態和終態規則，並能說明亂序、重複事件怎麼處理（累計值、只增不減）。
2. client order id 的產生時機（送出之前）、生命週期（重送用同一個），以及逾時後先對帳再決定的流程。
3. 撤單競態：為什麼要有 `canceling` 這個中間狀態，以及對已成交的訂單取消會得到什麼。
4. 限流的實作（token bucket，注入時鐘、整數運算）和為什麼要在 App 端做。
5. 區分 REST 和私有 WebSocket 兩個通道，以及誰是權威資料。

> [!FIELD]
> 在一個社交 App 的即時聊天裡，每則訊息用 `UUID` 當 App 端的訊息 id（iOS 存在 Realm 資料庫，當作主鍵），伺服器 ack 之後取代本地的樂觀資料，這和「App 產生的冪等鍵」是同一種想法；聊天的發送佇列一次送一筆，等到伺服器 ack 才送下一筆。另外同一個 App 的 iOS 內購，是伺服器確認收據之後才呼叫 `finishTransaction`，沒有確認的交易會在下次啟動時重新處理，這也是「以伺服器為準」。
> 到交易所的 App，我會把訂單合併規則寫成純函式，用事件序列測試亂序與重複的情況；下單時產生 client order id 並保存草稿；並加上 token bucket 保護每秒 10 次的額度。
> 面試時可以這樣說：「我在一個社交 App 做過帶冪等鍵的樂觀更新（先讓畫面動，等伺服器確認）：App 產生 UUID，伺服器 ack 後取代本地資料；內購也是伺服器確認之後才結束交易。到交易所的下單流程，我會用同樣的思路，加上純函式的訂單合併和 token bucket 限流。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 狀態機 | `enum` 加 `reduce` 純函式 | `sealed class` 加 `reduce`，用 `StateFlow` 保存狀態 |
| 冪等鍵 | `UUID().uuidString`（去掉 `-` 為 32 字元） | `UUID.randomUUID().toString()` |
| 保存未確認的下單 | 本機資料庫或 `UserDefaults`（草稿），App 重啟後恢復 | `SavedStateHandle` 加資料庫 |
| 限流 | actor 內的 token bucket；用 `Task.sleep` 等待 | 用 `Mutex` 保護的 token bucket；用 `delay` 等待 |
| 防止連點 | 送出前鎖定按鈕與狀態 | 同左，ViewModel 內用狀態擋住重複進入 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 市價單 | market order | 成行注文 |
| 限價單 | limit order | 指値注文 |
| 狀態機 | state machine | ステートマシン（状態遷移） |
| 終態 | final state／terminal state | 終端状態 |
| 冪等 | idempotent | 冪等 |
| 令牌桶（限流） | token bucket | トークンバケット |
| 部分成交 | partial fill | 部分約定 |
| 取消 | cancel | 取消（キャンセル） |
