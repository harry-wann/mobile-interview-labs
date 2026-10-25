---
id: exchange-ws-protocol
title: WebSocket 協定：gzip 壓縮、心跳 ping／pong、訂閱 sub／unsub／req 與私有頻道
summary: 這套交易所 API（Huobi 相容）的 WebSocket 每則訊息都是 gzip 壓縮的二進位資料，伺服器會定時送 ping，App 要馬上回 pong，否則連線會被切斷。這個單元用實際的訊息範例，說明怎麼解壓、怎麼訂閱、私有頻道怎麼認證，以及收到訊息後怎麼分派。
priority: P0
day: 3
tags: [websocket, gzip, heartbeat, subscribe, topic, private-ws, protocol]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？
2. 為什麼每則訊息都要先解壓縮？iOS 和 Android 怎麼解 gzip？
3. 心跳 ping／pong 是什麼？為什麼要最先處理？
4. `sub`、`unsub`、`req` 差在哪？訊息和錯誤長什麼樣子？
5. 私有頻道怎麼用？認證、限流、順序不保證是什麼意思？
6. 收到訊息後怎麼分派？斷線重連後要做什麼？

這個單元的內容都以一套 Huobi 相容的交易所 API（Application Programming Interface，程式介面）文件為準（範例中用 `api.example-exchange.com` 代表它的主機名稱）。WebSocket 是一種讓伺服器可以持續主動推送資料給 App 的連線，不像一般的 REST API（Representational State Transfer，App 問一次、伺服器答一次）。這套 API 沿用 Huobi（火幣）交易所的格式，所以格式可以對照 Huobi 的文件；細節仍以實際使用的交易所 API 文件為準。

### 1. 這套交易所 API 有哪幾個 WebSocket 端點？公開和私有差在哪？

**這套交易所 API 的文件列出三個 WebSocket 位址：公開行情（`/ws`）、私有的訂單與資產（`/ws/v2`）、販売所報價（`/retail/ws`）。三個的訊息格式都不一樣，公開行情誰都能連，私有的要先送認證訊息（auth）證明你是誰。**

| 位址 | 用途 | 格式與認證 |
|---|---|---|
| `wss://api.example-exchange.com/ws` | 公開行情：K 線、板、BBO、成交、24 小時概要 | 用 `sub`／`unsub`／`req` 欄位，不需要認證 |
| `wss://api.example-exchange.com/ws/v2` | 私有：你自己的訂單、成交、資產變動 | 用 `action`／`ch`／`data` 欄位，連線後要先送 `auth` |
| `wss://api.example-exchange.com/retail/ws` | 販売所的報價與成交紀錄 | 用數字的 `action`：1 訂閱、2 退訂、4 ping、5 pong；`topic` 1 是價格、2 是成交紀錄 |

BBO 是 best bid and offer 的縮寫，意思是最佳買價和最佳賣價，也就是訂單簿最上面那一檔（訂單簿的說明見業務入門單元）。

用一個場景串起來：使用者打開 BTC/JPY 的行情頁，App 連到 `/ws`，訂閱 `market.btcjpy.bbo` 看最佳買賣價；切到「注文履歴」（訂單紀錄）頁，App 連到 `/ws/v2`，認證後訂閱自己的訂單；切到販売所頁，App 連到 `/retail/ws`，收到販売所的買賣報價。三個位址是三套協定，所以 App 通常把「怎麼連線、怎麼收發」包成一層（Transport），上面再各自寫解析（Protocol）。

兩個要記住的差異：

- 公開 WebSocket 的所有回傳資料都是 gzip 壓縮的二進位資料；販売所 WebSocket 的回應文件也寫明是 gzip 壓縮，要解壓。
- 販売所 WebSocket 要求 App **每 60 秒送一次 ping 指令**才能維持連線，由 App 主動送，和公開行情「伺服器送 ping、App 回 pong」的方向相反。

以上都是 API 文件的內容，不代表交易所的 App 本身一定直接使用這些端點。

> [!TIP]
> 面試時可以這樣說：「這套交易所 API 的文件有三個 WebSocket：公開行情 `/ws`、私有 `/ws/v2`、販売所 `/retail/ws`，格式各不相同。私有的要先送簽名過的 auth 訊息。我會把連線收發和協定解析分成兩層，三套協定共用同一個連線層。」

### 2. 為什麼每則訊息都要先解壓縮？iOS 和 Android 怎麼解 gzip？

**公開 WebSocket 回傳的每一則訊息都是 gzip 壓縮過的二進位資料，不是文字，App 要自己解壓縮才看得到 JSON（JavaScript Object Notation，一種常見的文字資料格式）。Android 的 `GZIPInputStream` 可以直接解；iOS 的 Compression framework 只能解沒有 gzip 標頭的 DEFLATE 資料，要自己先把 gzip 的頭尾去掉。**

先把名詞說清楚。frame 是 WebSocket 傳送的一則訊息；gzip 是常見的壓縮格式；DEFLATE 是 gzip 裡面真正在壓縮資料的演算法。一份 gzip 資料由三段組成：

```text
[ 標頭 10 bytes（固定識別碼 1f 8b 開頭，另有可能出現的選用欄位）]
[ 中間：DEFLATE 壓縮資料 ]
[ 結尾 8 bytes（CRC-32 是檢查資料有沒有損壞的檢查碼，另外還有原始長度）]
```

Apple 的 Compression framework（`COMPRESSION_ZLIB`）和 `NSData.decompressed(using: .zlib)` 解的是中間那一段「裸的 DEFLATE」，不認得 gzip 的標頭和結尾。所以直接把整份 gzip 資料交給它，會解壓失敗（`compression_decode_buffer` 回傳 0，`decompressed(using:)` 丟出錯誤）。解法是自己跳過標頭和結尾，只把中間那段交給它；也可以連結 libz（zlib 函式庫）、用 `inflateInit2` 設成 gzip 模式。

下面的範例用 `{"ping":18212558000}` 用 gzip 指令壓縮出來的 40 個位元組做測試：

```swift
import Compression
import Foundation

/// 把 gzip 資料解成原始資料。失敗回傳 nil。
func gunzip(_ data: Data) -> Data? {
    let bytes = [UInt8](data)
    // gzip 開頭：0x1f 0x8b 是固定的識別碼，第 3 個位元組 8 代表壓縮方式是 DEFLATE
    guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b, bytes[2] == 8 else { return nil }

    let flags = bytes[3]
    var offset = 10                                  // 固定標頭 10 bytes
    if flags & 0x04 != 0 {                           // 有 extra 欄位：先讀 2 bytes 長度再跳過
        guard offset + 2 <= bytes.count else { return nil }
        offset += 2 + Int(bytes[offset]) + (Int(bytes[offset + 1]) << 8)
    }
    if flags & 0x08 != 0 {                           // 有檔名：跳到字串結尾（值為 0 的位元組）
        while offset < bytes.count, bytes[offset] != 0 { offset += 1 }
        offset += 1
    }
    if flags & 0x10 != 0 {                           // 有註解：同樣跳到字串結尾
        while offset < bytes.count, bytes[offset] != 0 { offset += 1 }
        offset += 1
    }
    if flags & 0x02 != 0 { offset += 2 }             // 有標頭檢查碼：跳過 2 bytes

    let end = bytes.count - 8                        // 結尾 8 bytes 是 CRC-32 與原始長度
    guard offset < end else { return nil }

    // 中間這段是「裸的 DEFLATE」，系統的 .zlib 剛好解得開
    let deflated = Data(bytes[offset..<end]) as NSData
    return try? deflated.decompressed(using: .zlib) as Data
}

let frame = Data([
    0x1f, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03,   // 固定標頭 10 bytes
    0xab, 0x56, 0x2a, 0xc8, 0xcc, 0x4b, 0x57, 0xb2, 0x32, 0xb4, 0x30, 0x32, 0x34, 0x32,
    0x35, 0xb5, 0x30, 0x30, 0x30, 0xa8, 0x05, 0x00,               // DEFLATE 壓縮資料
    0x53, 0xa1, 0x30, 0x3f, 0x14, 0x00, 0x00, 0x00,               // 結尾 8 bytes
])
print(String(data: gunzip(frame)!, encoding: .utf8)!)
// 輸出：{"ping":18212558000}
print(gunzip(Data([1, 2, 3])) == nil)
// 輸出：true（不是 gzip 的資料，回傳 nil，不會崩潰）
```

Android 就簡單多了，`GZIPInputStream` 認得 gzip 的標頭和結尾：

```kotlin
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

fun gunzip(bytes: ByteArray): String =
    GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
// 傳入上面那 40 個位元組，回傳 {"ping":18212558000}
```

**常見疑問：URLSession 不是會自動解壓縮嗎？** 那是 HTTP（HyperText Transfer Protocol，一般的網路請求方式）的 `Content-Encoding: gzip`，只對一般的 HTTP 回應有效。這裡的 gzip 是這套交易所協定自己規定的壓縮方式，壓在 WebSocket 的 binary frame 裡面，URLSession 和 OkHttp 都不會幫你解。

解壓時還有三件事要注意：

- 解壓和解碼放在 socket 的背景執行緒（iOS 的背景 queue，Android 的背景 dispatcher），不要在主執行緒做，不然行情一多，畫面會卡。
- 解壓失敗、JSON 解碼失敗，都只記錄一下並丟掉這則訊息，不能讓整條連線崩潰。
- 要先解壓才看得到 `ping`，所以「解壓」是整條處理流程的第一步，緊接著就是處理心跳。

> [!TIP]
> 面試時可以這樣說：「這套交易所的 WebSocket 每則訊息都是 gzip 壓縮的二進位資料。Android 直接用 GZIPInputStream；iOS 的 Compression framework 只吃沒有標頭的 DEFLATE，所以我會自己跳過 10 個位元組的標頭和結尾 8 個位元組，或是連結 libz。解壓和解碼都在背景執行緒做，失敗就丟掉那一則。」

### 3. 心跳 ping／pong 是什麼？為什麼要最先處理？

**伺服器約每 5 秒（文件註明可能變更）送一則 `{"ping": 數字}`，App 要在收到之後，原樣回 `{"pong": 同一個數字}`。連續兩次沒有回應，伺服器就會切斷連線。** 所以心跳要在背景最先處理，不能經過畫面更新或行情處理。

心跳（heartbeat）就是定時確認「對方還活著」的小訊息。具體走一遍：

```json
// 伺服器送來（解壓後）
{"ping": 18212558000}
// App 要回（數字必須和收到的相同）
{"pong": 18212558000}
```

假設使用者在行情列表快速捲動，主執行緒忙著畫畫面，如果心跳是在主執行緒處理，ping 排隊排了 10 秒，伺服器已經送了兩次 ping 都沒收到回應，連線就被切掉了。使用者看到的是行情突然停住，還不知道為什麼。這就是為什麼心跳要在背景處理、而且排在行情處理之前。

幾個細節：

- pong 的數字必須和 ping 一模一樣，不能自己填現在的時間，也不能寫死。
- App 也可以主動送 `{"ping": 整數}`，伺服器會回 `{"pong": 同一個數字}`。如果送的不是整數，會收到錯誤 `{"status":"error","err-code":"bad-request","err-msg":"invalid ping"}`。
- 私有 WebSocket 的格式不一樣：`{"action":"ping","data":{"ts":1575537778295}}`，要回 `{"action":"pong","data":{"ts":1575537778295}}`。
- 販売所 WebSocket 是 App 每 60 秒主動送 `{"action":4,"ts":...}`（數字 4 是 ping）。

**常見誤解：** WebSocket 協定本身也有 ping／pong frame（OkHttp 的 `pingInterval`、`URLSessionWebSocketTask` 的 `sendPing` 用的就是這個），但那是另一層的機制。這套協定的 `{"ping":...}` 是 JSON 訊息，屬於這個協定自己定義的心跳，函式庫不會幫你回，一定要自己處理。

```swift
import Foundation

// 收到 {"ping":18212558000}，原樣回 {"pong":18212558000}
func pongReply(for json: [String: Any]) -> String? {
    guard let ping = (json["ping"] as? NSNumber)?.int64Value else { return nil }
    return "{\"pong\":\(ping)}"
}
print(pongReply(for: ["ping": 18212558000]) ?? "沒有 ping")
// 輸出：{"pong":18212558000}
print(pongReply(for: ["ch": "market.btcjpy.bbo"]) ?? "沒有 ping")
// 輸出：沒有 ping
```

把心跳寫成不依賴任何狀態的純函式（輸入一則訊息，輸出要回什麼），好處是可以單獨測試，也不會因為其他邏輯卡住而回不了。

> [!TIP]
> 面試時可以這樣說：「伺服器約每 5 秒送 ping，連續兩次沒回就斷線，所以我會把心跳放在解壓之後最先處理，不依賴任何狀態，也不經過主執行緒。pong 的值要原樣回傳。OkHttp 的 pingInterval 是 WebSocket 協定層的 ping，和這個 JSON 心跳是兩回事。」

### 4. `sub`、`unsub`、`req` 差在哪？訊息和錯誤長什麼樣子？

**`sub` 是訂閱，之後伺服器每有更新就推送給你；`unsub` 是退訂；`req` 是一次性請求，問一次、回一次，不會持續推送。訊息裡的 `topic`（主題名稱）用點分隔，例如 `market.btcjpy.kline.1min` 是 BTC/JPY 的 1 分鐘 K 線。**

打開 K 線頁面時，常見的做法是先用 `req` 一次抓回歷史 K 線（可帶 `from`／`to`，單位是秒，UTC 世界標準時間），再用 `sub` 接上即時更新。一個 `sub` 的完整往返如下：

```json
{"sub":"market.btcjpy.bbo","id":"id1"}
{"id":"id1","status":"ok","subbed":"market.btcjpy.bbo","ts":1489474081631}
{"ch":"market.btcjpy.bbo","ts":1630994555540,"tick":{"seqId":137005210233,"ask":52665.02,"askSize":1.502181,"bid":52665.01,"bidSize":0.178567,"quoteTime":1630994555539,"symbol":"btcjpy"}}
```

第一行是 App 送出的訂閱；第二行是伺服器的確認（ack，意思是「我收到並處理了」），`subbed` 欄位帶著 topic；第三行起是持續的推送，`ch` 是它屬於哪個 topic，資料放在 `tick`。`id` 是 App 自己產生的識別碼，伺服器回覆時會帶回來，讓你知道這個回覆對應哪個請求；文件上這個欄位是選填，但建議一律帶上。

三種請求的格式與回覆差異：

| 請求 | 格式 | 成功的回覆 |
|---|---|---|
| 訂閱 | `{"sub":"<topic>","id":"id1"}`，可選 `"freq-ms":2000` | `{"id":"id1","status":"ok","subbed":"<topic>",...}`，之後持續推送 `{"ch":...,"tick":{...}}` |
| 退訂 | `{"unsub":"<topic>","id":"id4"}` | `{"id":"id4","status":"ok","unsubbed":"<topic>",...}` |
| 一次性請求 | `{"req":"<topic>","id":"id10"}` | `{"status":"ok","rep":"<topic>","tick":[...]}`（注意是 `rep`，不是 `ch`） |

`freq-ms` 是用來調整推送頻率，只能填 1000、2000、3000、4000、5000（毫秒）；不填就是有更新就立刻推。

目前文件列出的 topic：

| topic | 內容 |
|---|---|
| `market.$symbol.kline.$period` | K 線；period 有 1min、5min、15min、30min、60min、1day、1mon、1week、1year，**沒有 3min** |
| `market.$symbol.depth.$type` | 板的深度；type 是 step0 到 step5，數字越大，價位合併得越粗 |
| `market.$symbol.bbo` | 最佳買價與賣價 |
| `market.$symbol.trade.detail` | 逐筆成交 |
| `market.$symbol.detail` | 24 小時概要 |

出錯時，伺服器會回 `status: error`，附上錯誤碼和說明。例如訂閱了文件裡沒有的 `kline.3min`：

```json
{"id":"id3","status":"error","err-code":"bad-request","err-msg":"invalid topic market.btcjpy.kline.3min","ts":1494310283622}
```

對沒有訂閱過的 topic 送 `unsub` 也會得到錯誤（`unsub with not subbed topic …`）。所以 App 要處理 `status: error`，用 `id` 對回是哪個請求失敗，不能只處理成功的情況。

自己寫解析器時容易踩到的欄位差異：

- `req` 的回覆用 `rep` 標示 topic，推送才用 `ch`。
- 推送資料放的欄位不一定一樣：K 線、BBO 放在 `tick`；depth 在文件的 WebSocket 範例裡放在 `data`（含 `bids`、`asks`），而 REST 的 depth 回應放在 `tick`。所以解析時兩個欄位都要接受。
- `trade.detail` 的 `tick.data` 是陣列，一則推送可能含有多筆成交。
- 逐筆成交裡的 `id` 有 27 位數，超過 `Int64` 和 `Double` 能精確表示的範圍，識別成交要用 `tradeId`。

> [!TIP]
> 面試時可以這樣說：「sub 是持續推送，req 是問一次答一次，每個請求都帶自己產生的 id，回覆才對得回去。req 的回覆用 rep、推送用 ch，depth 和其他 topic 資料放的欄位也不同，所以我會用有型別的 enum 加測試把這些差異固定下來。收到 status error 要用 id 對回請求，不能只處理成功的情況。」

### 5. 私有頻道怎麼用？認證、限流、順序不保證是什麼意思？

**私有頻道（`/ws/v2`）是你自己的訂單、成交和資產變動。連線後要先送一則簽名過的 `auth` 訊息，成功回 `code: 200` 之後，才能訂閱。格式和公開的不一樣，用 `action`、`ch`、`data` 欄位。另外，成交和取消的通知順序不保證。**

**認證流程：**

```json
{"action":"req","ch":"auth","params":{"authType":"api","accessKey":"你的 API key","signatureMethod":"HmacSHA256","signatureVersion":"2.1","timestamp":"2019-09-01T18:16:16","signature":"簽名"}}
```

成功會回 `{"action":"req","code":200,"ch":"auth","data":{}}`。簽名用 HmacSHA256（一種用密鑰產生簽名的演算法，HMAC 是 Hash-based Message Authentication Code），簽名的原文是 `GET\napi.example-exchange.com\n/ws/v2\n<排序後的參數>`，簽名的做法見 API 簽名單元。注意 JSON 裡的值不需要再做 URL 編碼。

**訂閱：**

```json
{"action":"sub","ch":"orders#btcjpy"}
{"action":"sub","code":200,"ch":"orders#btcjpy","data":{}}
{"action":"push","ch":"orders#btcjpy","data":{...}}
```

目前文件列出三種 topic：`orders#${symbol}`（訂單資料）、`trade.clearing#${symbol}#${mode}`（成交與取消的通知，mode 為 0 只通知成交、1 成交與取消都通知）、`accounts.update#${mode}`（資產變動）。symbol 可以用 `*` 代表所有交易對。

**限流（rate limit，伺服器限制單位時間內的請求或連線數）：** 文件寫的是「連線與請求合計每秒 50 次」「每個 API key 最多 10 條連線」「每個 IP（網路位址）每秒最多 100 次新連線」。超過時會收到錯誤碼 `429`（Too Many Requests）或 `4000`（`too.many.request`、`too.many.connection`）。遇到這些錯誤，要等一段時間再連（退避），不能馬上重連，不然只會被限得更嚴格。重連時間的算法見連線韌性單元。

**順序不保證：** 文件明確寫到，`trade.clearing` 的成交與取消通知，**接收的順序不保證**。舉例：使用者下一張 IOC 單（Immediate Or Cancel，能立刻成交的部分就成交，剩下的立刻取消），買 1 BTC，但板上只有 0.4 BTC 能成交。伺服器的實際順序是「成交 0.4，取消剩下的 0.6」，但 App 可能先收到「取消」通知，才收到「成交」通知。如果 App 把先到的「取消」當成最後的結果，成交的 0.4 BTC 就被蓋掉了，畫面顯示這張單完全沒成交。

正確的做法是狀態合併時容許亂序：已成交的數量只增加不減少，已經結束的狀態（全部成交、已取消）不能被較舊的事件覆蓋。如果順序有依賴，文件建議改用 `orders#${symbol}`。細節見下單流程單元。

> [!TIP]
> 面試時可以這樣說：「私有頻道連上之後要先送簽名過的 auth，成功才能訂閱訂單和資產。限流方面，每個 API key 最多 10 條連線，收到 429 或 too.many.connection 要退避，不是立刻重連。成交和取消的通知順序不保證，所以我的狀態合併要容許亂序：成交量只增不減，結束的狀態不能被舊事件蓋掉。」

### 6. 收到訊息後怎麼分派？斷線重連後要做什麼？

**每則訊息解壓、解碼之後，照固定的順序判斷「這是什麼」，再交給對應的處理者。判斷順序是：心跳、錯誤、確認（`subbed`／`unsubbed`／`rep`）、推送、其他（忽略）。** 這個單元的練習題（code 題）就是把這件事寫成純函式：輸入一則 JSON 字串，輸出 App 下一步該做什麼，完全不碰網路和畫面，所以可以直接寫單元測試。

順序的理由：

1. **心跳最優先**，而且不依賴任何狀態，回覆的 pong 值要和 ping 一模一樣。
2. **錯誤**其次：有 `status: error` 就用 `id` 對回請求。
3. **確認**（`subbed`／`unsubbed`／`rep`）：表示請求成功，同樣用 `id` 對回請求。
4. **推送**：有 `ch`，而且有 `tick` 或 `data`，就依 `ch` 拆成「交易對、種類、參數」，例如 `market.btcjpy.kline.1min` 是交易對 `btcjpy`、種類 `kline`、參數 `1min`，再交給 K 線的處理者。碰到不認得的 topic 要容錯，忽略就好。
5. **其他**一律忽略，不要崩潰。

**組出 `sub`／`unsub` 的 JSON 時，要先檢查 topic 和 id 的字元。** 如果直接把使用者輸入接進字串，輸入裡的引號會破壞 JSON 的結構，這叫 JSON 注入（injection）：

```swift
import Foundation

let topic = #"market.btcjpy.bbo","x":"y"#          // 錯誤或惡意的輸入，裡面多了引號
let bad = "{\"sub\":\"\(topic)\",\"id\":\"id1\"}"   // 直接把字串接進 JSON
print(bad)
// 輸出：{"sub":"market.btcjpy.bbo","x":"y","id":"id1"}   ← 多出一個欄位，JSON 結構被改掉了

// 正確：先檢查字元，只允許英數字和 . _ # -，長度 1 到 64
func isValidToken(_ s: String) -> Bool {
    s.range(of: "^[A-Za-z0-9._#-]{1,64}$", options: .regularExpression) != nil
}
print(isValidToken(topic), isValidToken("market.btcjpy.bbo"))
// 輸出：false true
```

下面是 iOS 端接收訊息並處理心跳的最小骨架（解壓用前面的 `gunzip`）：

```swift
import Foundation

final class MarketSocket {
    private let task: URLSessionWebSocketTask
    private let queue = DispatchQueue(label: "market.socket")   // 背景佇列：解壓、解碼、心跳都在這裡做

    init(url: URL) {
        task = URLSession.shared.webSocketTask(with: url)
    }

    func start() {
        task.resume()
        receiveNext()
    }

    private func receiveNext() {
        task.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(.data(let frame)):
                queue.async { self.handle(frame: frame) }
                receiveNext()                 // receive 一次只收一則，要自己再呼叫一次
            case .success:
                receiveNext()                 // 這個協定不會送文字訊息，直接略過
            case .failure:
                break                         // 連線斷了：交給重連邏輯（見連線韌性單元）
            }
        }
    }

    private func handle(frame: Data) {
        guard let jsonData = gunzip(frame),
              let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any]
        else { return }                       // 解壓或解碼失敗：丟掉這一則，不讓連線崩潰

        // 心跳最先檢查：收到 {"ping":N}，原樣回 {"pong":N}
        if let ping = (json["ping"] as? NSNumber)?.int64Value {
            task.send(.string("{\"pong\":\(ping)}")) { _ in }
            return
        }
        // …其他訊息（確認、錯誤、推送）在這裡處理
    }
}
```

Android 端同樣的骨架（OkHttp 的回呼本來就在背景執行緒）：

```kotlin
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class MarketSocketListener : WebSocketListener() {

    // OkHttp 在自己的背景執行緒呼叫這個函式，不是主執行緒
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val text = try {
            GZIPInputStream(ByteArrayInputStream(bytes.toByteArray()))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return                                    // 解壓失敗：丟掉這一則，不讓連線崩潰
        }
        val json = try { JSONObject(text) } catch (e: Exception) { return }   // 解碼失敗也一樣丟掉

        // 心跳最先檢查：收到 {"ping":N}，原樣回 {"pong":N}
        if (json.has("ping")) {
            webSocket.send("""{"pong":${json.getLong("ping")}}""")
            return
        }
        // …其他訊息（確認、錯誤、推送）在這裡處理
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        // 連線斷了：交給重連邏輯（見連線韌性單元）
    }
}
```

**斷線重連後要做什麼？** 連線一斷，伺服器就不記得你訂閱過什麼了。所以 App 要自己記錄目前訂閱的所有 topic，重連成功後：私有頻道先重新送 `auth`，再重新 `sub` 所有 topic，板的深度這類「要靠一份完整資料加上後續變化才能還原」的資料，還要重新抓一份完整的最新資料（snapshot，快照）。不重新訂閱的話，畫面就會停在斷線前的最後一個價格。重連的等待時間和狀態處理見連線韌性單元；板的快照與增量見訂單簿單元。

> [!TIP]
> 面試時可以這樣說：「我會把訊息分派寫成純函式：輸入一則解壓後的 JSON，輸出下一步要做什麼，順序是心跳、錯誤、確認、推送、忽略，這樣可以直接寫單元測試。組 sub 的 JSON 時我會檢查 topic 的字元，避免使用者輸入破壞 JSON。重連後要重新 auth、重新訂閱，並對板的深度重新抓快照。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange03WebSocket**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange03WebSocket**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange03-WebSocket/`，解答在 `labs/ios/Solution/Exchange/Exchange03-WebSocket/`；Android 你寫在 `labs/android/practice/exchange/exchange03-websocket/`，解答在 `labs/android/solution/exchange/exchange03-websocket/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路**：這個單元的演練都不連真的 WebSocket 伺服器，而是用程式裡的假伺服器（自己 gzip 壓縮的 binary frame、本機的假連線）或直接準備好的訊息。訊息格式照上面的交易所（Huobi 相容）協定；演練 5 假伺服器回的錯誤碼、演練 6 的交易對 wbtcjpy 是演練的假設，不是交易所的規格。Android 的 JSON 用 Gson（`build.gradle.kts` 已經加上 `com.google.code.gson:gson`），因為 Android 內建的 `org.json` 在電腦上的單元測試是空殼，Gson 在電腦和手機上行為一樣。

### 演練 1：私有頻道連上沒多久就被伺服器切斷（第 1 題）

**檔案**：`L01_PrivatePing.swift`（iOS）、`L01_PrivatePing.kt`（Android）

**情境**：App 同時連兩個端點：公開行情 `/ws` 和私有的 `/ws/v2`。兩個端點都會定時送 ping，連續 2 次沒有收到正確的 pong 就切斷連線。`/ws` 的 ping 是 `{"ping":數字}`，要回 `{"pong":同一個數字}`；`/ws/v2` 的 ping 是 `{"action":"ping","data":{"ts":數字}}`，要回 `{"action":"pong","data":{"ts":同一個數字}}`。為了專心看心跳的格式，這個演練的訊息已經解壓好了。

**怎麼寫**

1. **Bug 版**：`reply` 只處理 `/ws` 的格式：解析 JSON，取出 `ping` 的數字，回傳 `{"pong":N}`；其他格式回傳 `nil`（Android 是 `null`）。
2. **修正版**：依 `endpoint` 分別處理：`/ws` 一樣回 `{"pong":N}`；`/ws/v2` 要確認 `action` 是 `ping`，取出 `data.ts`，回 `{"action":"pong","data":{"ts":N}}`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `/ws` 連線維持；`/ws/v2` 的第 1、2 次 ping 都沒有回，「/ws/v2：連續 2 次沒回 pong，伺服器切斷連線」，「被伺服器切斷的連線有 1 條」 |
| 修正版 | 兩個端點都維持連線，「被伺服器切斷的連線有 0 條」 |

**為什麼**：這套交易所 API 的三個 WebSocket 端點是三套協定，公開的用 `ping`／`pong` 欄位，私有的用 `action`／`data`，販売所的用數字 `action`。把「怎麼連線、怎麼收發」包成一層，上面各自寫解析，心跳的格式就不會被混在一起。只看公開格式的心跳處理，私有頻道連上沒多久就會被切斷，而且錯誤只在連上私有頻道時才出現。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L01_PrivatePing.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L01_PrivatePing.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L01_PrivatePing.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L01_PrivatePing.kt#fixed
```

### 演練 2：連上之後每一則訊息都解析失敗（第 2 題）

**檔案**：`L02_Gunzip.swift`（iOS）、`L02_Gunzip.kt`（Android）

**情境**：公開 WebSocket 的每一則訊息都是 gzip 壓縮過的 binary frame，不是文字。假伺服器依序送來三則：一則 ping、一則最佳買賣價（BBO）的推送、一則訂閱確認；frame 是演練用 gzip 格式現做的（iOS 的假伺服器先用系統的 `.zlib` 壓縮，再補上 10 位元組標頭和 8 位元組結尾）。App 要先把 frame 解成文字，才能解析 JSON。

**怎麼寫**

1. **Bug 版**：把 frame 直接當成 UTF-8 文字：iOS 用 `String(decoding: frame, as: UTF8.self)`，Android 用 `String(frame, Charsets.UTF_8)`。
2. **修正版**：iOS：檢查前兩個位元組是 `0x1f`、`0x8b`，切掉標頭 10 個位元組和結尾 8 個位元組，中間那段轉成 `NSData`，交給 `decompressed(using: .zlib)`，再轉成文字。Android：`GZIPInputStream(ByteArrayInputStream(frame))` 加 `bufferedReader(Charsets.UTF_8).readText()`，不是 gzip 時接住例外。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 三則都是「解析失敗（轉成的文字長度 40 左右）」，「共 3 則，成功解析 0 則」 |
| 修正版 | 「共 3 則，成功解析 3 則」，第一則解出 `{"ping":18212558000}` |

**為什麼**：gzip 資料的開頭是固定的識別碼 `0x1f 0x8b`，不是 UTF-8 文字，當成文字讀只會得到亂碼，JSON 全部解析失敗。Android 的 `GZIPInputStream` 認得 gzip 的標頭和結尾；Apple 的 Compression framework（`.zlib`）解的是中間那段裸的 DEFLATE，把整份 gzip 直接交給它會解壓失敗，所以要自己先去掉標頭和結尾（或連結 libz）。URLSession 與 OkHttp 的自動解壓只對 HTTP 的 `Content-Encoding: gzip` 有效，不會幫你解 WebSocket frame。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L02_Gunzip.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L02_Gunzip.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L02_Gunzip.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L02_Gunzip.kt#fixed
```

### 演練 3：行情一多，伺服器就把連線切斷（第 3 題）

**檔案**：`L03_HeartbeatQueue.swift`（iOS）、`L03_HeartbeatQueue.kt`（Android）

**情境**：伺服器一次推來 20 則行情，緊接著送一則 `{"ping":777}`，並規定 0.5 秒內要收到 `{"pong":777}`，否則切斷連線。每則行情要在「畫面佇列」上處理（iOS 是一條序列 `DispatchQueue`，Android 是單一執行緒的 `Executor`），一則花 40 毫秒，用 `Thread.sleep` 代表列表正在忙著畫畫面；訊息從 socket 的背景佇列進來。演練會真的等約 1.3 秒。0.5 秒的時限是為了縮短等待而假設的，真正的伺服器約每 5 秒送一次 ping。

**怎麼寫**

1. **Bug 版**：`makeHandler` 回傳的處理函式把每一則訊息（包括 ping）都丟到畫面佇列裡：解壓、解析之後，有 `ping` 就 `send` 回 `{"pong":N}`，否則呼叫 `renderQuote()`。
2. **修正版**：解壓、解析之後，先在 socket 的背景佇列檢查：有 `ping` 就立刻 `send`（數字原樣回傳）並 `return`；只有行情才丟到畫面佇列。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「收到 {"pong":777}，在 ping 送出後 870 到 920 ms」（解答實測 iOS 的 Debug 與 Release 都約 875 ms，Android 約 920 ms，一定超過 800 ms），「伺服器：沒有在 0.5 秒內收到正確的 pong，切斷連線」 |
| 修正版 | pong 在幾毫秒內就回去（解答實測 0 ms），「伺服器：pong 準時，連線維持」 |

**為什麼**：伺服器連續兩次沒收到 pong 就會切斷連線。ping 排在 20 則行情後面，要等 20 × 40 = 800 毫秒才輪到，使用者在行情列表快速捲動時，畫面忙著繪圖，連線就這樣斷掉，而且看起來只是「行情突然停住」。心跳要在解壓之後最先處理，不依賴任何狀態、不經過畫面執行緒；pong 的數字要和 ping 一模一樣。OkHttp 的 `pingInterval` 和 `URLSessionWebSocketTask` 的 `sendPing` 是 WebSocket 協定層的 ping，不能取代這個 JSON 心跳。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L03_HeartbeatQueue.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L03_HeartbeatQueue.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L03_HeartbeatQueue.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L03_HeartbeatQueue.kt#fixed
```

### 演練 4：K 線頁一直轉圈，回覆被丟掉了（第 4 題）

**檔案**：`L04_ReqReply.swift`（iOS）、`L04_ReqReply.kt`（Android）

**情境**：打開 BTC/JPY 的 K 線頁，App 送出三個請求：`req` 歷史 K 線（id r1）、`sub` 即時 K 線（id s1）、`sub` 一個文件裡沒有的 `market.btcjpy.kline.3min`（id s2）。畫面上每個「還沒收到結果的請求」都會轉圈。伺服器依序回來：s1 的確認（`subbed`）、r1 的回覆（用 `rep` 欄位，不是 `ch`）、s2 的錯誤（`status` 是 `error`）、一則即時 K 線的推送（`ch`）、一則 ping。

**怎麼寫**

1. **Bug 版**：`classify` 除了心跳，只認得有 `ch` 欄位的推送，其他一律忽略。
2. **修正版**：照固定的順序判斷：心跳 → `status` 是 `error`（用 `id` 對回請求，訊息取 `err-msg`）→ 有 `subbed` 或 `unsubbed`（確認）、有 `rep`（req 的回覆）→ 有 `ch` 而且有 `tick` 或 `data`（推送）→ 其他忽略。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | r1、s1、s2 都沒有結果：「還在轉圈、沒有收到結果的請求有 3 個（r1、s1、s2）」，只看到推送與心跳 |
| 修正版 | s1 訂閱成功、r1 收到歷史 K 線、s2 失敗並顯示錯誤，「還在轉圈、沒有收到結果的請求有 0 個」 |

**為什麼**：`sub` 是訂閱後持續推送，`req` 是問一次答一次，回覆用的欄位也不同（`rep` 與 `ch`）。每個請求都帶自己的 `id`，回覆才對得回去；訂閱不存在的 topic 時，伺服器回 `status: error`，如果只處理成功的情況，畫面就會一直等一個永遠不會來的資料。判斷「是不是推送」要放在最後，因為確認和回覆也可能帶有 `tick`。推送的資料有時放 `tick`、有時放 `data`，兩個欄位都要接受。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L04_ReqReply.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L04_ReqReply.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L04_ReqReply.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L04_ReqReply.kt#fixed
```

### 演練 5：私有頻道連上了，卻一直收不到自己的訂單（第 5 題）

**檔案**：`L05_AuthFirst.swift`（iOS）、`L05_AuthFirst.kt`（Android）

**情境**：連上私有頻道 `/ws/v2` 之後，要先送簽名過的 `auth`，收到 `{"action":"req","code":200,"ch":"auth",...}` 才能訂閱。假伺服器：auth 的結果要過一小段時間才會回來；認證成功之前收到的 `sub` 一律拒絕。App 想訂閱 `orders#btcjpy`（自己的訂單）。

**怎麼寫**

1. **Bug 版**：`onOpen` 把 auth 和 sub 連續送出，不等認證結果。
2. **修正版**：`onOpen` 只送 auth；`onMessage` 收到認證成功的回覆（`ch` 是 `auth`、`code` 是 200）時，才送 sub。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「伺服器拒絕了 1 次 sub」，認證成功之後 App 沒有再送 sub，「訂閱 orders#btcjpy：沒有成功，畫面永遠收不到訂單」 |
| 修正版 | 「伺服器拒絕了 0 次 sub」，收到認證成功之後才送 sub，「訂閱 orders#btcjpy：成功，會收到自己的訂單」 |

**為什麼**：認證是非同步的，auth 送出去不代表已經認證成功；在那之前送的 sub 會被伺服器拒絕，而且 App 如果不再重送，使用者看到的是「連上了，但訂單永遠不更新」。順序是「連線、送 auth、收到 code 200、送 sub」。另外兩個私有頻道的注意事項，這個演練沒有做：成交與取消的通知順序不保證，狀態合併要容許亂序，這是下單流程單元的演練；限流的 429 或 `too.many.connection` 要退避再連，退避在連線韌性單元練習。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L05_AuthFirst.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L05_AuthFirst.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L05_AuthFirst.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L05_AuthFirst.kt#fixed
```

### 演練 6：行情頁混進別的交易對，K 線混進別的週期（第 6 題）

**檔案**：`L06_TopicRoute.swift`（iOS）、`L06_TopicRoute.kt`（Android）

**情境**：收到推送後，要依 `ch`（topic）決定交給哪一個畫面。行情頁要 `market.btcjpy.bbo`，K 線頁要 `market.btcjpy.kline.5min`。連線上同時有六個 topic 的推送：`market.btcjpy.bbo`、`market.wbtcjpy.bbo`、`market.btcjpy.kline.5min`、`market.btcjpy.kline.15min`、`market.wbtcjpy.kline.5min`、`market.btcjpy.depth.step0`（交易對 wbtcjpy 是演練假設的，不代表交易所有上架）。

**怎麼寫**

1. **Bug 版**：`matches` 用字串「包含」比對：topic 含有交易對、含有種類，有參數的話也要含有參數。
2. **修正版**：把 topic 用 `.` 拆開（`market.btcjpy.kline.5min` 變成 `market`、`btcjpy`、`kline`、`5min`），每一段都用完全相等來比對；有參數的 topic 剛好 4 段，沒有參數的剛好 3 段。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「行情頁收到 2 則（錯的 1 則），K 線頁收到 3 則（錯的 2 則）」：`wbtcjpy` 的 BBO 混進行情頁，`15min` 與 `wbtcjpy` 的 K 線混進 5 分鐘 K 線頁 |
| 修正版 | 「行情頁收到 1 則（錯的 0 則），K 線頁收到 1 則（錯的 0 則）」 |

**為什麼**：`"wbtcjpy"` 含有 `"btcjpy"`，`"15min"` 含有 `"5min"`，用字串包含判斷 topic，別的交易對和別的週期的資料就會混進畫面，使用者看到的價格或 K 線是錯的，而且只在特定交易對同時被訂閱時才出現。topic 的結構是「交易對、種類、參數」，拆開之後每一段各自比對，才不會誤判；不認得的 topic 就忽略，不要崩潰。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L06_TopicRoute.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L06_TopicRoute.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L06_TopicRoute.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L06_TopicRoute.kt#fixed
```

### 演練 7：斷線重連之後，畫面停在最後一個價格不動（第 6 題）

**檔案**：`L07_Resubscribe.swift`（iOS）、`L07_Resubscribe.kt`（Android）

**情境**：畫面訂閱了兩個 topic：`market.btcjpy.bbo` 和 `market.btcjpy.depth.step0`。連線一斷，伺服器就不記得這條連線訂閱過什麼；重新連上之後，伺服器只有在收到新的 `sub` 之後才會推送。演練用假的連線和假伺服器，伺服器每次「推送一輪」，會對每個已訂閱的 topic 各推一則。

**怎麼寫**

1. **Bug 版**：`onReconnected` 只呼叫 `server.connect()` 重新建立連線。
2. **修正版**：`server.connect()` 之後，依 `topics` 清單把每一個 topic 都用新的 id 重新 `sub` 一次。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「斷線前：伺服器推送一輪，App 收到 2 則」，「重連後：伺服器推送一輪，App 收到 0 則，畫面停在斷線前的最後一個價格」 |
| 修正版 | 「重連後：伺服器推送一輪，App 收到 2 則」 |

**為什麼**：WebSocket 斷線再重連是一條全新的連線，伺服器不記得舊連線的訂閱。App 要自己記錄目前需要的所有 topic，重連成功後重新訂閱；沒有重新訂閱，畫面就停在斷線前的最後一個價格，使用者看到的是「凍結」。私有頻道重連後要先重新 auth（演練 5），板的深度這類狀態型資料還要重新抓快照，見訂單簿單元；重連的等待時間與恢復順序見連線韌性單元。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L07_Resubscribe.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange03-WebSocket/L07_Resubscribe.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange03-websocket/L07_Resubscribe.kt#bug
```

```lab-solution
android/solution/exchange/exchange03-websocket/L07_Resubscribe.kt#fixed
```

第 6 題的「組 `sub` 的 JSON 時要先檢查 topic 的字元，避免 JSON 注入」是輸入驗證的小函式，這個單元沒有做獨立的演練。第 5 題的限流退避與訊息亂序，分別在連線韌性單元與下單流程單元練習。

## 常見陷阱

- **pong 的值寫死，或填現在的時間。** pong 的數字必須原樣回傳收到的 ping 的數字，不一樣伺服器不認。
- **把心跳放在 UI（使用者介面）的主執行緒處理。** 列表捲動時主執行緒卡住，連續兩次沒回 pong，連線就被伺服器切斷。
- **忘了 gzip。** 把 binary frame 當成 UTF-8 文字解析，每一則都失敗。
- **iOS 直接把整份 gzip 資料交給 `COMPRESSION_ZLIB`。** Apple 的解壓只認裸的 DEFLATE，不認 gzip 的標頭和結尾，會解壓失敗。要先跳過標頭和結尾，或連結 libz。
- **用 `ch` 判斷 `req` 的回覆。** `req` 的回覆用的是 `rep` 欄位。
- **訂閱不存在的 topic，例如 `kline.3min`，卻沒處理錯誤訊息。** 畫面會一直等一個永遠不會來的資料。每個請求都帶 `id`，收到 `status: error` 時才知道是哪個請求失敗。
- **重連後沒有重新訂閱。** 畫面停在最後一個價格，使用者看到的是「凍結」。
- **收到 429 馬上重連。** 會被限流得更嚴重，要退避再連。
- **相信私有頻道的推送順序。** 成交和取消的通知可能亂序，狀態合併要容許亂序。
- **把使用者輸入直接接進 `sub` 的 JSON。** 引號會破壞 JSON 結構，要先檢查字元。

## 面試官想聽到的

1. 能完整描述協定的流程：連線、gzip 解壓、ping／pong、`sub`／`unsub`／`req`、錯誤處理、重連與重新訂閱。
2. 知道心跳要放在背景最先處理，而且說得出原因：連續兩次沒回，伺服器就切斷連線。
3. 說得出兩個平台處理 gzip 的差異（Android 直接解，iOS 要自己去掉標頭和結尾），以及處理要放在背景執行緒。
4. 知道私有頻道要先認證、有連線與請求次數的限制，而且推送順序不保證，狀態合併要容許亂序。
5. 會把分派邏輯寫成可以單元測試的純函式：輸入一則訊息，輸出要做什麼，不碰網路和畫面。

> [!FIELD]
> 做過長連線的重連與生命週期管理。在一個即時行情 App 裡，K 線用 SSE（Server-Sent Events，伺服器單向推送資料的 HTTP 串流）：先用 REST 取得短效 token，再用 token 開串流；斷線後 1 秒重連，並重新取得 token；iOS 端有「10 秒沒收到資料」的監看，回到前景時會重新抓資料補齊。在一個社交 App 裡，聊天室用 Socket.IO（WebSocket 傳輸），自訂了重連、ack（送出後等伺服器確認）和待送佇列，回到前景時用 HTTP 同步補回錯過的訊息。這種 gzip 壓縮的 WebSocket 協定，則是從 API 文件學的，並用純函式練習過訊息分派和心跳（就是這個單元的 code 題）。
> 面試時可以這樣說：「我在前一個即時行情 App 做過 SSE 的重連和回前景補資料，在一個社交 App 做過 Socket.IO 的重連、ack 和待送佇列。這套交易所的 WebSocket 協定我讀過 API 文件，也用純函式練習了分派和心跳；到交易所的 App，我會把重連改成指數退避加隨機誤差。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| WebSocket 用戶端 | `URLSessionWebSocketTask`（iOS 13 起）；`receive` 一次只收一則，要自己再呼叫 | OkHttp 的 `WebSocket` 加 `WebSocketListener` |
| 收到的二進位訊息 | `Message.data(Data)` | `onMessage(webSocket, bytes: ByteString)` |
| gzip 解壓 | Compression framework 只吃裸的 DEFLATE，要自己處理標頭；或連結 libz | `GZIPInputStream` 或 okio 的 `GzipSource`，直接處理 |
| 這個協定的 JSON 心跳 | 自己處理 `{"ping"}` 訊息，再用 `send` 回 `{"pong"}` | 自己處理，再用 `webSocket.send` 回 `{"pong"}`；OkHttp 的 `pingInterval` 是協定層的 ping，不能取代 |
| 解析放哪裡 | 背景的 DispatchQueue 或 actor | 背景的 dispatcher；OkHttp 的回呼本來就不在主執行緒 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 心跳 | heartbeat（ping／pong） | ハートビート |
| 訂閱／退訂 | subscribe／unsubscribe | 購読／購読解除 |
| 主題名稱 | topic | トピック |
| 壓縮／解壓縮 | compress／decompress（gzip） | 圧縮／解凍 |
| 認證 | authentication（auth） | 認証 |
| 流量限制 | rate limit | レート制限 |
| 最佳買賣價 | best bid and offer（BBO） | 板情報（BBO） |
