---
id: exchange-api-signing
title: API 簽名：HMAC-SHA256 的 Signature V2、URL 編碼的陷阱、手機時間不準怎麼辦
summary: 簽名錯一個字元，伺服器就回 api-signature-not-valid。這個單元一步一步拆解這套交易所 API 的簽名方式（HMAC-SHA256、Signature Version 2），說明參數排序和 URL 編碼最容易踩的坑、時間戳怎麼產生，以及手機時間和伺服器差太多時怎麼處理。
priority: P1
day: 4
tags: [signing, hmac, sha256, url-encode, timestamp, clock-skew]
---

## 重點講解

這個單元回答七個面試常見問題：

1. 為什麼 API（程式之間溝通用的介面）請求要簽名？這套交易所 API 的 Signature V2 怎麼做？
2. GET 和 POST 要簽的東西有什麼不同？
3. URL（網址）編碼有哪些陷阱？
4. Timestamp 怎麼產生才不會出錯？
5. 使用者的手機時間不準，簽名全部失敗，怎麼辦？
6. 簽名的程式要怎麼測試？金鑰要怎麼保管？
7. 私有 WebSocket 的簽名有什麼不同？（比較少考，被問到能答就好）

先說明這個單元的範圍：這套交易所 API 的文件裡，需要簽名的 API（帳戶、下單、出金等）要依這套方式簽名。實際的交易所 App 走哪一種認證方式各家不同；但「簽名請求」的原理與常見錯誤，在任何交易所 API 都一樣，面試很常拿來考。

### 1. 為什麼 API 請求要簽名？這套交易所 API 的 Signature V2 怎麼做？

**簽名的目的，是讓伺服器確認「這個請求是持有 Secret Key 的人送的，而且內容沒有被改過」。** 這套交易所用的是 HMAC-SHA256：把請求的內容組成一段文字，用只有你和伺服器知道的 Secret Key 算出一個「指紋」，附在請求裡。Secret Key 本身永遠不會送出去。

先說明幾個名詞：

- **HMAC**（Hash-based Message Authentication Code）：用一把祕密金鑰，對一段文字算出固定長度的指紋。沒有金鑰的人算不出同樣的指紋；文字只要改一個字，指紋就完全不同。SHA-256（Secure Hash Algorithm，安全雜湊演算法）是指紋用的雜湊函式（hash function，把任意長度的資料轉成固定長度的結果）。
- **Base64**：把二進位資料轉成只有 `A-Z a-z 0-9 + / =` 的文字，方便放進請求裡。
- **Timestamp（時間戳）**：請求送出的時間。伺服器可以用它拒絕太舊的請求，防止別人攔截後重新送出同一個請求（稱為重放攻擊）。

伺服器收到請求後，用自己保存的 Secret Key，照同樣的步驟算一次指紋；兩邊算出來一樣，就代表請求可信。

需要簽名的請求，要在 query（網址 `?` 後面的參數）帶 4 個認證參數：

| 參數 | 值 |
|---|---|
| `AccessKeyId` | 使用者在 [マイページ > API] 建立的 Access Key（相當於帳號） |
| `SignatureMethod` | `HmacSHA256` |
| `SignatureVersion` | `2` |
| `Timestamp` | UTC 時間，格式 `YYYY-MM-DDThh:mm:ss`，例如 `2017-05-11T16:22:06`（文件範例） |

**簽名的 5 個步驟：**

1. 組出要簽的參數：4 個認證參數；**GET** 請求要把本身的 query 參數一起簽。
2. 參數名稱依 **ASCII（電腦最基本的字元編碼表）順序**排序，名稱和值都做 **URL 編碼**，用 `&` 連接。
3. 組出簽名字串：`METHOD\n<小寫的 host>\n<path>\n<排序後的 query>`（`\n` 是換行）。
4. 用 Secret Key 對簽名字串做 **HMAC-SHA256**，結果做 **Base64**。
5. 把這個 Base64 字串再 **URL 編碼**，放進 `Signature` 參數，附加在 query 的最後。

用一個完整的例子走一遍。請求是 `GET /v1/order/orders?order-id=1234567890`，Access Key 是測試用的 `test-access-key-0001`（不是真實金鑰）。排序後的參數（注意：大寫開頭的 `AccessKeyId`、`Timestamp` 排在小寫開頭的 `order-id` 之前；`Timestamp` 的冒號被編成 `%3A`），簽名字串是：

```text
GET
api.example-exchange.com
/v1/order/orders
AccessKeyId=test-access-key-0001&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2017-05-11T15%3A19%3A30&order-id=1234567890
```

用測試用的 Secret Key `test-secret-key-0001` 簽名，得到 `KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=`。這個結果可以用 Swift、Kotlin 和 openssl 三種方式交叉確認，是本單元程式題使用的 test vector（固定的測試答案，用來確認程式算對了）。最後一步，Base64 裡的 `/` 和 `=` 要編碼，附加到 query 後變成：

```text
...&order-id=1234567890&Signature=KLn4U%2FAu9BfxNEAqNebkXqZGpgjxOmyhMwOi%2BFmMfdg%3D
```

HMAC 和 Base64 這一步，兩個平台的寫法：

```swift
import CryptoKit
import Foundation

func hmacBase64(_ stringToSign: String, secret: String) -> String {
    let key = SymmetricKey(data: Data(secret.utf8))                  // Secret Key 轉成 UTF-8 的 byte
    let mac = HMAC<SHA256>.authenticationCode(for: Data(stringToSign.utf8), using: key)
    return Data(mac).base64EncodedString()                            // 算出的指紋轉成 Base64
}
// hmacBase64(上面的簽名字串, secret: "test-secret-key-0001")
// 輸出：KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=
```

```kotlin
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun hmacBase64(stringToSign: String, secret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)))
}
// 同樣的輸入，輸出也是 KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=
```

兩邊的輸入都是 UTF-8（把文字轉成 byte 的編碼方式）的 byte，金鑰和字串都不要先 `trim`（去掉前後空白）或重新編碼。

> [!TIP]
> 面試時可以這樣說：「這套交易所的簽名是 HMAC-SHA256，版本 2。把 4 個認證參數加上 GET 的 query 依 ASCII 排序、URL 編碼，組成 `METHOD\nhost\npath\nquery` 的簽名字串，用 Secret Key 算 HMAC 再 Base64，最後把簽名再 URL 編碼，附加在 query 最後。Secret Key 本身不會送出去，伺服器用自己保存的金鑰重算一次比對。」

### 2. GET 和 POST 要簽的東西有什麼不同？

**GET 要把 URL 上的所有 query 參數一起簽；POST 只簽 4 個認證參數，其他資料放在 JSON（常見的文字資料格式）body，body 不參與簽名。**

| | GET | POST |
|---|---|---|
| 簽名的參數 | 4 個認證參數 ＋ 請求本身的 query 參數 | 只有 4 個認證參數 |
| 其他資料放哪 | 都在 query 裡 | JSON body（`Content-Type: application/json`） |
| 簽名字串第一行 | `GET` | `POST` |

例如對 `POST /v1/order/orders/place` 簽名，簽名字串長這樣，`symbol`、`amount` 這些下單參數只出現在 body，簽名字串裡沒有：

```text
POST
api.example-exchange.com
/v1/order/orders/place
AccessKeyId=...&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=...
```

這代表 **body 的內容不受簽名保護**（仍然受 HTTPS，也就是加密的網路連線保護）。這是這套簽名的設計特性，面試時可以點出來。

另外兩個細節：簽名字串裡的 host 要轉成**小寫**；path 只放路徑，**不帶 query**。

> [!TIP]
> 面試時可以這樣說：「GET 的 query 參數要一起簽，POST 只簽 4 個認證參數，其餘資料放在 JSON body，body 不在簽名範圍內，是靠 HTTPS 保護。簽名字串裡 host 要小寫、path 不帶 query。」

### 3. URL 編碼有哪些陷阱？

**簽名時的 URL 編碼，要用 RFC 3986 的規則：`A-Z a-z 0-9 - . _ ~` 這 66 個字元（稱為 unreserved 字元，意思是不需要編碼的字元）不編碼，其他一律編成 `%XX`，而且十六進位要用大寫。** 多數語言內建的編碼函式，規則和這個不一樣，直接拿來用就會算出錯的簽名。

RFC 3986 是定義網址格式的標準文件。常見的陷阱：

- **空白是 `%20`，不是 `+`。** `+` 是表單編碼（`application/x-www-form-urlencoded`）的慣例，兩邊的編碼不一致，簽名就不同。
- **十六進位要大寫**：`%3A`，不是 `%3a`。
- **非 ASCII 字元**要先轉成 UTF-8 的 byte，再逐個 byte 編碼：`é` 變成 `%C3%A9`。
- **`Signature` 本身是 Base64**，含有 `+`、`/`、`=`，放進 query 時**必須**編碼成 `%2B`、`%2F`、`%3D`，否則伺服器會解出不同的簽名。
- **排序依 byte 順序（ASCII）**，不要用跟語言環境有關的比較（Swift 的 `localizedCompare`、Java 的 `Collator`），否則大小寫的順序會錯。ASCII 裡大寫字母在小寫字母之前，所以 `Zeta` 排在 `alpha` 之前。
- 同一個參數名稱不要重複；值前後的空白、換行會被簽進去。

用同一個字串 `tea & cake=1+1` 比較三種編碼方式的結果：

| 方式 | 結果 | 問題 |
|---|---|---|
| 自己寫、只保留 unreserved 字元 | `tea%20%26%20cake%3D1%2B1` | 正確 |
| Swift `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)` | `tea%20&%20cake=1+1` | 保留了 `&`、`=`、`+`，簽名需要把它們編碼 |
| Java／Kotlin `URLEncoder.encode` | `tea+%26+cake%3D1%2B1`（`~` 會變成 `%7E`，`*` 不編碼） | 空白變成 `+`，規則是表單編碼，不是 RFC 3986 |

`.urlQueryAllowed` 是為「整個 query 字串」設計的，所以保留了分隔用的符號；簽名需要的是只保留 unreserved 字元的編碼。正確的寫法是自己逐個 byte 處理：

```swift
func percentEncode(_ s: String) -> String {
    let hex = Array("0123456789ABCDEF")                   // 大寫的十六進位
    var out = ""
    for b in s.utf8 {                                      // 逐個 UTF-8 byte 處理
        switch b {
        case 0x30...0x39, 0x41...0x5A, 0x61...0x7A, 0x2D, 0x2E, 0x5F, 0x7E:   // 0-9 A-Z a-z - . _ ~
            out.unicodeScalars.append(UnicodeScalar(b))    // unreserved 字元不編碼
        default:
            out.append("%")
            out.append(hex[Int(b >> 4)])
            out.append(hex[Int(b & 0x0F)])
        }
    }
    return out
}
// percentEncode("tea & cake=1+1")  → tea%20%26%20cake%3D1%2B1
// percentEncode("é")               → %C3%A9
```

Kotlin 的寫法相同：用 `s.toByteArray(Charsets.UTF_8)` 取得 byte，同樣的判斷，不用 `URLEncoder`。

> [!TIP]
> 面試時可以這樣說：「簽名的 URL 編碼要用 RFC 3986：只有 unreserved 字元不編碼，空白是 `%20` 不是 `+`，十六進位大寫，Base64 的簽名也要再編碼。Java 的 `URLEncoder` 和 Swift 的 `urlQueryAllowed` 都不能直接用，我會自己逐個 byte 寫編碼函式，排序用 ASCII 順序。」

### 4. Timestamp 怎麼產生才不會出錯？

**Timestamp 要求 UTC 時間，格式固定是 `yyyy-MM-ddTHH:mm:ss`：沒有毫秒、沒有時區後綴、24 小時制。** 產生的程式不能受手機的語言、時區、曆法設定影響。

最常出錯的是用 `DateFormatter` 時沒有固定設定。同一個時間 `2017-05-11 15:20:06 UTC`，在不同設定下的結果：

```swift
let date = Date(timeIntervalSince1970: 1_494_516_006)   // 2017-05-11 15:20:06 UTC

// 使用者的手機設成日本曆（和曆）：年份變成「平成 29 年」的 29
// DateFormatter 若沒有固定語言與曆法，輸出會是 0029-05-11T15:20:06
// 佛曆則會輸出 2560-05-11T15:20:06

let formatter = DateFormatter()
formatter.locale = Locale(identifier: "en_US_POSIX")          // 固定的格式，不跟著語言設定
formatter.calendar = Calendar(identifier: .gregorian)          // 固定用西曆
formatter.timeZone = TimeZone(identifier: "UTC")               // 固定用 UTC
formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"                 // HH 是 24 小時制
print(formatter.string(from: date))                            // 輸出：2017-05-11T15:20:06
```

Android 用 `java.time`：

```kotlin
val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)  // Locale.ROOT：不跟著語言設定
val ts = Instant.ofEpochMilli(1_494_516_006_999).atOffset(ZoneOffset.UTC).format(formatter)
// 輸出：2017-05-11T15:20:06（毫秒被忽略）
```

要注意：小時要用大寫 `HH`（24 小時制），不要用小寫 `hh`（12 小時制）；`Locale.ROOT` 是不跟著語言設定的固定格式。iOS 的 `en_US_POSIX` 也是同樣的用途：它是 Apple 提供的固定格式語言設定（POSIX 是 Unix 系統的標準規格），不會隨使用者的語言與地區改變。

伺服器容許的時間差有多大，文件沒有寫（未證實）。但 Timestamp 的用途就是防止重放，所以手機時間偏差太多時，請求就會被拒絕，下一題就是處理這個。

> [!TIP]
> 面試時可以這樣說：「Timestamp 是 UTC 的 `yyyy-MM-ddTHH:mm:ss`。iOS 的 `DateFormatter` 一定要固定成 `en_US_POSIX`、西曆、UTC，否則使用者設成日本曆時年份會變成 0029；Android 用 `DateTimeFormatter` 搭配 `Locale.ROOT` 和 UTC。」

### 5. 使用者的手機時間不準，簽名全部失敗，怎麼辦？

**不要相信手機的時間，改用交易所的伺服器時間：先向伺服器取得它的時間，算出和手機時間的差（offset），之後簽名用「手機現在的時間 ＋ offset」。** 簽名失敗時重新校時，並且只重試一次。

這個問題叫時鐘偏差（clock skew）：手機的時間和伺服器的時間差了多少。使用者手動調整時間、或沒有開自動校時，就會讓所有簽名請求被拒絕（錯誤碼 `api-signature-not-valid` 之類），而且重新登入也沒用。

這套 API 提供取得伺服器時間的端點：`GET /v1/common/timestamp`，回傳毫秒的 Unix timestamp（從 1970 年 1 月 1 日 00:00 UTC 起算的毫秒數）：`{"status":"ok","data":1555667124908}`。

做法：

1. App 啟動和回到前景時，請求一次。
2. 請求前記錄手機時間 `t0`，收到回應後記錄 `t1`；伺服器的時間大約對應 `t0` 和 `t1` 的中間。`offset = 伺服器時間 − (t0 + t1) ÷ 2`。
3. 之後簽名用 `手機現在的時間 + offset`。
4. 簽名失敗（`api-signature-not-valid`）時，重新校時，再重試**一次**。不要一直重試：簽名失敗也可能是編碼寫錯，重試多少次都一樣。

```swift
// 例子：手機比伺服器快 10 分鐘
let t0: Int64 = 1_000_000_000_000            // 送出校時請求前的手機時間（毫秒）
let t1: Int64 = 1_000_000_000_400            // 收到回應後的手機時間（花了 400 毫秒）
let serverMs: Int64 = 999_999_400_200        // 伺服器回傳的時間
let offset = serverMs - (t0 + t1) / 2
print(offset)                                 // 輸出：-600000（要把手機的時間減掉 10 分鐘）
```

**經過的時間要用單調時鐘來量。** 單調時鐘（monotonic clock）是只會往前走、不受使用者調整系統時間影響的時鐘。校時之後，不要再用手機的系統時間相減，否則使用者在校時之後又改了系統時間，offset 就失效了。校時當下記錄「伺服器時間」和「單調時鐘的讀數」，之後用單調時鐘算經過了多久：

```swift
struct ServerClock {
    private let serverMsAtSync: Int64        // 校時當下的伺服器時間
    private let uptimeAtSync: TimeInterval   // 校時當下的單調時鐘讀數（ProcessInfo.processInfo.systemUptime）

    func nowMs(uptime: TimeInterval) -> Int64 {
        serverMsAtSync + Int64((uptime - uptimeAtSync) * 1000)   // 伺服器時間 ＋ 之後經過的時間
    }
}
// 校時當下伺服器時間 1555667124908、讀數 1000；讀數走到 1030.5 時
// nowMs 的結果是 1555667155408（過了 30.5 秒）
```

iOS 用 `ProcessInfo.processInfo.systemUptime`，Android 用 `SystemClock.elapsedRealtime()`。要注意：Apple 文件說明 `systemUptime` 是「系統醒著的時間」，不包含裝置睡眠的時間，所以裝置睡眠之後會慢一點；因此回到前景時一律重新校時，誤差就不會累積。

**用交易所的伺服器時間，還是用 NTP？** NTP（Network Time Protocol）是校準時鐘的網路協定，iOS 有 Kronos 這類函式庫可以取得網路時間，Android 13 起有 `SystemClock.currentNetworkTimeClock()`。對簽名來說，用交易所自己的伺服器時間通常更可靠，因為伺服器驗證時，比對的就是它自己的時間。

> [!TIP]
> 面試時可以這樣說：「手機時間不準會讓所有簽名請求被拒絕。我會在啟動和回前景時呼叫 `/v1/common/timestamp` 取得伺服器時間，算出 offset，之後簽名用手機時間加 offset，經過的時間用單調時鐘量。簽名失敗時重新校時、只重試一次。比起 NTP，直接用交易所自己的時間更可靠。」

### 6. 簽名的程式要怎麼測試？金鑰要怎麼保管？

**簽名函式寫成純函式（同樣的輸入一定得到同樣的輸出），不依賴語言環境、時區、系統設定，再用固定的 test vector 測試，並用另一個實作交叉驗證。**

測試的做法：

- 把「method、host、path、參數、金鑰、timestamp」當輸入，「簽名字串、簽名、完整 query」當輸出，輸出可以直接和 test vector 比對。
- 用另一個獨立的實作交叉驗證 test vector。例如在終端機用 openssl 算：

```bash
printf '%s' $'GET\napi.example-exchange.com\n/v1/order/orders\nAccessKeyId=test-access-key-0001&SignatureMethod=HmacSHA256&SignatureVersion=2&Timestamp=2017-05-11T15%3A19%3A30&order-id=1234567890' \
  | openssl dgst -sha256 -hmac "test-secret-key-0001" -binary | base64
# 輸出：KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=
```

- 測試案例要涵蓋：非 ASCII 的值、保留字元（空白、`+`、`/`、`*`、`~`）、排序（大寫在小寫之前）、POST 忽略 body 參數、host 的大小寫。iOS 和 Android 用同一組 test vector，就能確認雙平台的結果一樣。

**金鑰保管：**

- **Secret Key 只在建立時顯示一次**；絕不寫進 log、crash 報告或截圖。
- **權限要最小化。** 這套 API 的金鑰有三種權限：讀取、取引（下單與取消）、出金；預設只有讀取。錯誤碼 `auth.fail` 的說明包含「API 金鑰綁定 IP 錯誤」，表示金鑰可以綁定來源 IP（網路位址）。
- **行動 App 不應該內嵌長期有效的 Secret。** 一般的 App 使用者登入，應該走 session 或 token，簽名由 App 的後端處理。如果產品允許使用者自己匯入 API 金鑰，要存在 iOS 的 Keychain（設成 `ThisDeviceOnly`，不同步到其他裝置）或 Android 的 Keystore 加密儲存空間，並且要求生物辨識才能使用。

> [!TIP]
> 面試時可以這樣說：「我會把簽名寫成純函式，用固定的 test vector 測試，並用 openssl 這類獨立的實作交叉驗證，兩個平台用同一組案例。金鑰方面，App 不內嵌長期 Secret；如果要讓使用者匯入 API 金鑰，就存 Keychain 或 Keystore，使用時要生物辨識，也不能進 log。」

### 7. 私有 WebSocket 的簽名有什麼不同？（比較少考，被問到能答就好）

**私有 WebSocket（`/ws/v2`）用 SignatureVersion `2.1`，簽名的原理一樣，但參數名稱改成小寫開頭，簽名字串的 path 固定為 `/ws/v2`，而且送出的 JSON 裡的值不需要 URL 編碼。**

連上 `/ws/v2` 之後，要先送一個 `auth` 訊息（WebSocket 的連線與訊息格式見「WebSocket 協定」單元）。差異整理：

| 項目 | REST（Signature V2） | 私有 WebSocket（2.1） |
|---|---|---|
| 版本 | `SignatureVersion=2` | `signatureVersion` 是 `2.1` |
| 參數名稱 | `AccessKeyId`、`SignatureMethod`、`Timestamp` | 小寫開頭的駝峰：`accessKey`、`signatureMethod`、`signatureVersion`、`timestamp` |
| 簽名字串 | `METHOD\nhost\npath\nquery` | `GET\nhost\n/ws/v2\nquery`，path 固定為 `/ws/v2` |
| 簽名字串裡的值 | 要 URL 編碼 | 一樣要 URL 編碼（排序後的 query） |
| 送出的 JSON 裡的值 | 不適用 | **不需要** URL 編碼，用原始字串 |

另外 `authType: "api"` 是訊息本身的欄位，不在簽名的參數裡。

> [!TIP]
> 面試時可以這樣說：「私有 WebSocket 的簽名版本是 2.1，原理和 REST 一樣，差別是參數名稱改成小寫開頭的駝峰、簽名字串的 path 固定是 `/ws/v2`，而且送出的 JSON 裡的值不需要 URL 編碼。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange09Signing**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange09Signing**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange09-Signing/`，解答在 `labs/ios/Solution/Exchange/Exchange09-Signing/`；Android 你寫在 `labs/android/practice/exchange/exchange09-signing/`，解答在 `labs/android/solution/exchange/exchange09-signing/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路**：金鑰、host、時間都是演練用的假資料（就是這個單元裡的 test vector），「伺服器」是程式裡的假伺服器，用自己保存的 Secret Key 照規則重新算一次簽名，一致才受理。每個演練都能直接和 test vector 比對。

### 演練 1：明明有排序，簽名還是被拒絕（第 1 題）

**檔案**：`L01_ParamOrder.swift`（iOS）、`L01_ParamOrder.kt`（Android）

**情境**：送出兩個簽名請求：複習站的 test vector（`GET /v1/order/orders?order-id=1234567890`，簽名應該是 `KLn4U/Au9BfxNEAqNebkXqZGpgjxOmyhMwOi+FmMfdg=`），以及 `GET /v1/order/matchresults?symbol=btcjpy&size=10`。HMAC、Base64、URL 編碼都已經寫好在共用檔案，這題只看「參數排序」。

**怎麼寫**

1. **Bug 版**：把參數名稱轉成小寫再比較大小（一般人會想到的「字母順序」，不分大小寫）。iOS 用 `lowercased()`，Android 用 `lowercase()`。
2. **修正版**：依名稱的 UTF-8 byte 順序（ASCII 順序）排序，不轉小寫。iOS 用 `Array(name.utf8).lexicographicallyPrecedes`，Android 直接用 `sortedBy { it.first }`（`String` 的預設比較對 ASCII 就是逐字元比較）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `order-id` 排在 `AccessKeyId` 和 `SignatureMethod` 中間，「test vector 比對：不一致」，兩個請求都被伺服器拒絕（`api-signature-not-valid`）：「2 個請求，被伺服器拒絕 2 個」 |
| 修正版 | 排序後是 `AccessKeyId, SignatureMethod, SignatureVersion, Timestamp, order-id`，「test vector 比對：一致」，「2 個請求，被伺服器拒絕 0 個」 |

**為什麼**：簽名字串必須和伺服器算的一模一樣，所以排序規則也要一樣。伺服器用的是 ASCII 順序：所有大寫字母排在小寫字母之前，所以大寫開頭的 `AccessKeyId`、`Signature…`、`Timestamp` 都在小寫開頭的 `order-id`、`size`、`symbol` 之前。「有排序」不等於「排對」：不分大小寫的字母順序、`localizedCompare`（iOS）、`Collator`（Java）都會讓順序和伺服器不同。錯誤訊息只有一句 `api-signature-not-valid`，看不出是哪裡錯，所以才需要 test vector：把簽名字串印出來，一行一行和預期的對。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L01_ParamOrder.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L01_ParamOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L01_ParamOrder.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L01_ParamOrder.kt#fixed
```

### 演練 2：查詢都正常，一下單簽名就被拒絕（第 2 題）

**檔案**：`L02_GetPost.swift`（iOS）、`L02_GetPost.kt`（Android）

**情境**：兩個簽名請求：`GET /v1/order/orders?order-id=1234567890`（參數在網址的 query），以及 `POST /v1/order/orders/place`（下單資料 `account-id`、`symbol`、`type`、`amount`、`price` 放在 JSON body，網址上只有 4 個認證參數與 `Signature`）。簽名函式把「要簽哪些參數」交給 `paramsToSign` 決定，4 個認證參數由共用的程式加進去。

**怎麼寫**

1. **Bug 版**：不分 GET 與 POST，一律回傳 `query + body` 的所有欄位（覺得「簽越多越安全」）。
2. **修正版**：GET 回傳 query 的參數；POST 回傳空的集合（只簽認證參數，body 不參與簽名）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | GET 被受理；POST「簽進去的參數」多了 `account-id`、`amount`、`price`、`symbol`、`type`，「POST 簽名與 test vector 不一致」，伺服器拒絕：「2 個請求，被伺服器拒絕 1 個」 |
| 修正版 | POST 只簽 `AccessKeyId, SignatureMethod, SignatureVersion, Timestamp`，「POST 簽名與 test vector 一致」，「2 個請求，被伺服器拒絕 0 個」 |

**為什麼**：這套簽名的設計是：GET 的資料都在網址上，所以 query 要一起簽；POST 的資料在 body，伺服器重新算簽名時只看網址上的 4 個認證參數，body 不在簽名字串裡。客戶端多簽了 body 的欄位，兩邊的簽名字串就不同。這也代表 body 的內容不受簽名保護，只靠 HTTPS（加密的網路連線）保護，是面試時值得主動點出來的設計特性。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L02_GetPost.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L02_GetPost.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L02_GetPost.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L02_GetPost.kt#fixed
```

### 演練 3：帶了空白、冒號或 Base64 的請求，簽名不對（第 3 題）

**檔案**：`L03_UrlEncode.swift`（iOS）、`L03_UrlEncode.kt`（Android）

**情境**：簽名字串裡每個參數的名稱與值都要做 URL 編碼，規則是 RFC 3986：只有 `A-Z a-z 0-9 - . _ ~` 不編碼，其他一律編成大寫的 `%XX`（非 ASCII 字元先轉 UTF-8 的 byte）。演練拿 6 個字串，逐一編碼後和正確答案比對：含空白與 `& = +` 的字串、帶冒號的 Timestamp、`é`、日文、含 `/ * ~` 的字串，以及要放進 `Signature` 的 Base64 簽名。

**怎麼寫**

1. **Bug 版**：直接用平台內建的編碼函式。iOS 用 `addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)`，Android 用 `URLEncoder.encode(s, "UTF-8")`。
2. **修正版**：自己寫：逐個 UTF-8 byte 處理，`A-Z a-z 0-9 - . _ ~` 原樣保留，其他編成大寫的 `%XX`。Kotlin 的 `Byte` 有正負號，要先 `and 0xFF`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：保留了 `& = + / : * ~`，6 筆裡錯 4 筆（`tea & cake=1+1` 變成 `tea%20&%20cake=1+1`，冒號沒編碼，Base64 簽名的 `/ + =` 沒編碼），`é` 和日文是對的。Android：空白變成 `+`、`*` 沒編碼、`~` 被編成 `%7E`，錯 2 筆；兩個平台都是「共 6 筆，編碼錯的有 N 筆」 |
| 修正版 | 6 筆都和正確答案一致，例如 `tea%20%26%20cake%3D1%2B1`，「共 6 筆，編碼錯的有 0 筆」 |

**為什麼**：`.urlQueryAllowed` 是為「整個 query 字串」設計的，所以保留了分隔用的符號 `& = +`；`URLEncoder` 是 HTML 表單的編碼規則，空白變成 `+`。這兩個都不是簽名要的規則，而且是錯在「看起來差不多」：只有帶到特殊字元的請求才會失敗，最難查。放進 `Signature` 的 Base64 含有 `+`、`/`、`=`，一定要編碼，否則伺服器解碼時 `+` 會變成空白。Android 一側的 `URLEncoder` 只錯 2 筆，是因為冒號、`/`、`=` 它恰好也會編碼；換成別的字串，錯的筆數會不同，重點是規則不一樣。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L03_UrlEncode.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L03_UrlEncode.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L03_UrlEncode.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L03_UrlEncode.kt#fixed
```

### 演練 4：只有部分使用者簽名永遠失敗（第 4 題）

**檔案**：`L04_Timestamp.swift`（iOS）、`L04_Timestamp.kt`（Android）

**情境**：同一個時間 2017-05-11 15:20:06 UTC，用三位使用者手機的設定各產生一次 Timestamp：台灣的使用者（西曆、UTC+8）、日本的使用者（日曆設成和曆、UTC+9）、泰國的使用者（佛曆、UTC+7）。三個人都應該得到 `2017-05-11T15:20:06`（UTC、24 小時制、沒有毫秒與時區後綴）。演練把使用者的語言、曆法、時區明確帶進函式，不管模擬器或電腦本身的設定是什麼，結果都一樣。

**怎麼寫**

1. **Bug 版**：iOS 建立 `DateFormatter`，`locale`、`calendar`、`timeZone` 都用使用者的（模擬「沒有另外固定」時的預設值），只設 `dateFormat`；Android 用舊的 `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", user.locale)`，時區用使用者的。
2. **修正版**：iOS 的 `DateFormatter` 固定 `en_US_POSIX`、西曆、UTC；Android 用 `DateTimeFormatter.ofPattern(…, Locale.ROOT).withZone(ZoneOffset.UTC)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 台灣的使用者是 `2017-05-11T23:20:06`（本地時間，不是 UTC）；日本的使用者年份變成平成 29 年（iOS 是 `0029-05-12T00:20:06`，電腦上的 JVM 是 `29-05-12T00:20:06`），連日期都跨日；泰國的使用者是 `2560-05-11T22:20:06`（佛曆）。Android 手機（模擬器 API 29 實測）的 `SimpleDateFormat` 不理會 `u-ca-japanese` 這類曆法設定，一律用西曆，所以只剩時區錯（日本 `2017-05-12T00:20:06`、泰國 `2017-05-11T22:20:06`）：「3 位使用者，Timestamp 算錯的有 3 位」 |
| 修正版 | 三位使用者都是 `2017-05-11T15:20:06`，「3 位使用者，Timestamp 算錯的有 0 位」 |

**為什麼**：`DateFormatter` 沒有另外固定的話，用的是使用者手機的語言、曆法與時區：時區不是 UTC，簽名請求就會被當成過期或來自未來；曆法是和曆或佛曆，年份就完全不同。開發時手機是西曆、台灣或日本時區，用起來都沒問題，只有少數使用者簽名永遠失敗，這種 bug 最難重現。Timestamp 是簽名的一部分，所以不是「差幾個小時伺服器還能容忍」，而是整個簽名字串不同。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L04_Timestamp.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L04_Timestamp.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L04_Timestamp.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L04_Timestamp.kt#fixed
```

### 演練 5：除錯用的 log 把 Secret Key 留下來了（第 6 題）

**檔案**：`L05_SecretInLog.swift`（iOS）、`L05_SecretInLog.kt`（Android）

**情境**：簽名請求被伺服器退回後，工程師為了找原因，在 App 寫了一行「請求紀錄」。這一行會進 App 的 log、crash 報告，也可能被使用者截圖給客服。演練把 method、完整網址（含 `AccessKeyId` 與 `Signature`）、簽名字串、Secret Key 交給 `describe` 函式，由它決定「要寫進紀錄的那一行」，再掃描這一行有沒有洩漏 Secret Key 或完整的簽名。

**怎麼寫**

1. **Bug 版**：把 method、完整網址、簽名字串和 Secret Key 全部接在一起寫進紀錄（「什麼都印出來最好除錯」）。
2. **修正版**：只寫 method 加 path、遮罩後的 `AccessKeyId`（前 5 碼加 `…` 加後 4 碼）和 `Signature=***`；Secret Key 與簽名字串完全不寫。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 紀錄裡有完整網址和 `secret=test-secret-key-0001`：「掃描這一行：Secret Key 洩漏，完整的 Signature 洩漏」 |
| 修正版 | `[App log] GET /v1/order/orders AccessKeyId=test-…0001 Signature=***`：「掃描這一行：Secret Key 沒有洩漏，完整的 Signature 沒有洩漏」 |

**為什麼**：Secret Key 只在建立 API 金鑰時顯示一次，拿到它的人可以偽造這個帳號所有的簽名請求（權限包含出金時就是資金風險）。log 會被 crash 報告、客服截圖、分析工具帶走，所以金鑰、完整的簽名一律不進 log。同樣的原則也適用於分析工具的事件參數與網路除錯用的 proxy 紀錄。紀錄還是要認得出「是哪個請求、哪把金鑰」，用遮罩而不是完全不寫。簽名函式本身的測試，請用 test vector：輸入固定、輸出固定，不需要把金鑰印出來。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L05_SecretInLog.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L05_SecretInLog.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L05_SecretInLog.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L05_SecretInLog.kt#fixed
```

### 演練 6：REST 簽名正常，私有 WebSocket 的 auth 被拒絕（第 7 題）

**檔案**：`L06_WebSocketAuth.swift`（iOS）、`L06_WebSocketAuth.kt`（Android）

**情境**：連上私有 WebSocket（`/ws/v2`）後要先送 `auth` 訊息。簽名原理和 REST 一樣，但欄位名稱改成小寫開頭的駝峰（`accessKey`、`signatureMethod`、`signatureVersion` 的值是 `2.1`、`timestamp`），簽名字串的 path 固定為 `/ws/v2`，送出的 JSON 裡的值不需要 URL 編碼。演練用複習站的時間戳記 `2019-09-01T18:16:16` 組出 auth 訊息，假伺服器收到後重新算一次簽名。

**怎麼寫**

1. **Bug 版**：欄位直接重用 REST 的 `E09.authParams`（`AccessKeyId`、`SignatureMethod`、`SignatureVersion=2`、`Timestamp`）；`jsonValue` 把值做 URL 編碼後放進 JSON。
2. **修正版**：欄位用 `accessKey`、`signatureMethod`（`HmacSHA256`）、`signatureVersion`（`2.1`）、`timestamp`；`jsonValue` 原樣回傳，不編碼。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 送出的欄位是 `AccessKeyId, SignatureMethod, SignatureVersion, Timestamp`，timestamp 的值是 `2019-09-01T18%3A16%3A16`，伺服器拒絕：`auth fail: 缺少欄位 accessKey` |
| 修正版 | 送出的欄位是 `accessKey, signatureMethod, signatureVersion, timestamp`，timestamp 是 `2019-09-01T18:16:16`，「簽名與 test vector 一致」，「伺服器受理：auth 成功」 |

**為什麼**：把 REST 的簽名程式直接拿來用，是最省事的寫法，也是最常見的錯：欄位名稱、版本號、path 都不一樣。更容易忽略的是「簽名字串裡的值要編碼，但 JSON 裡的值不用」：把同一份編碼過的字串同時放進兩個地方，簽名字串對、JSON 的值卻是錯的，伺服器解析後算出的簽名就和客戶端不同。`authType` 是訊息本身的欄位，不在簽名的參數裡。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L06_WebSocketAuth.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange09-Signing/L06_WebSocketAuth.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange09-signing/L06_WebSocketAuth.kt#bug
```

```lab-solution
android/solution/exchange/exchange09-signing/L06_WebSocketAuth.kt#fixed
```

第 5 題（手機時間不準，簽名全部失敗）在 Platform05 的演練 7 練過（用 `Date` 標頭算 offset），這個單元不重複；第 4 題的時間差不是「伺服器容忍多久」的問題（文件沒有寫，未證實），而是格式與時區必須固定。

## 常見陷阱

- **空白編成 `+`、十六進位用小寫、忘了編碼 `Signature` 裡的 `+`、`/`、`=`。** 這三個是最常見的編碼錯誤。
- **直接用 Java 的 `URLEncoder` 或 Swift 的 `urlQueryAllowed`。** 它們的規則和 RFC 3986 不同，要自己寫只保留 unreserved 字元的編碼函式。
- **POST 把 body 的參數也簽進去，或 GET 漏簽 query 參數。** POST 只簽 4 個認證參數；GET 要連 query 一起簽。
- **參數排序用跟語言環境有關的比較。** 大小寫的順序會錯，要用 ASCII 順序。
- **host 沒有轉小寫，或 path 帶了 query。** 簽名字串會和伺服器算的不同。
- **Timestamp 用裝置的時區、帶毫秒，或 `DateFormatter` 沒有固定成 POSIX 與西曆。** 使用者設成日本曆時，年份會變成 0029。
- **沒有處理手機時間不準。** 簽名永遠失敗，畫面卻只顯示「發生錯誤」，使用者完全不知道要校正時間。
- **在 log 印出完整的簽名字串或 Secret。** 金鑰不能出現在 log、crash 報告裡。

## 面試官想聽到的

1. 能完整說出簽名的 5 個步驟，並區分 GET 與 POST 要簽的範圍。
2. 知道 URL 編碼的具體陷阱：`%20` 與 `+`、大寫十六進位、unreserved 字元、Base64 裡的特殊字元要再編碼。
3. 知道 Timestamp 的格式，以及手機時間不準（clock skew）的處理方式：伺服器時間、offset、單調時鐘、失敗時只重試一次。
4. 把簽名寫成純函式，用固定的 test vector 驗證，並且用另一個實作與雙平台交叉驗證。
5. 對金鑰管理有判斷：不內嵌長期 Secret、權限最小化、存 Keychain 或 Keystore、不進 log。

> [!FIELD]
> 在一個社交 App 的 iOS 端，登入憑證存在 Keychain；token 過期時，同一時間只讓一個請求去刷新，其他請求等它完成，而且每個請求最多重試一次，避免大量請求同時刷新。這和「簽名失敗時重新校時、只重試一次」是同一種做法。在一個行情分析 App 的 Android 端，把登入的 cookie 用 Tink（Google 的加密函式庫）加密後存放，主金鑰放在 Android Keystore。另外在一個藍牙智慧鎖 SDK（SDK 是軟體開發套件，給合作廠商整合用）的專案裡，雙平台都用過 AES（Advanced Encryption Standard，常用的對稱式加密）加密通訊。
> 到交易所實作簽名請求，我會把簽名寫成純函式，用固定的 test vector 測試；校時用伺服器時間，失敗時只重試一次；如果要保存使用者的 API 金鑰，會放進 Keychain 與 Keystore。
> 面試時可以這樣說：「我做過 token 過期時單一刷新、最多重試一次的處理，也用過雙平台的加密 API。到交易所做簽名請求，我會寫成純函式、用固定的 test vector 測試，並用交易所的伺服器時間校正手機時間。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| HMAC-SHA256 | CryptoKit 的 `HMAC<SHA256>`（iOS 13 起） | `javax.crypto.Mac.getInstance("HmacSHA256")` |
| Base64 | `Data.base64EncodedString()` | `java.util.Base64`（API 26 起）或 `android.util.Base64` |
| URL 編碼 | 自己逐個 byte 處理，只保留 unreserved 字元 | 自己寫，不用 `URLEncoder` |
| UTC 時間格式 | `DateFormatter`（POSIX、UTC、西曆） | `DateTimeFormatter`（`Locale.ROOT`、`ZoneOffset.UTC`） |
| 單調時鐘 | `ProcessInfo.systemUptime`（不含睡眠時間） | `SystemClock.elapsedRealtime()` |
| 金鑰儲存 | Keychain（`ThisDeviceOnly`） | Keystore 加上 Tink |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 簽名 | signature | 署名 |
| 雜湊式訊息驗證碼 | HMAC（Hash-based Message Authentication Code） | HMAC（ハッシュベースのメッセージ認証コード） |
| 祕密金鑰 | secret key | シークレットキー |
| URL 編碼 | URL encoding／percent-encoding | URLエンコード（パーセントエンコーディング） |
| 時間戳 | timestamp | タイムスタンプ |
| 時鐘偏差 | clock skew | 時計のずれ |
| 單調時鐘 | monotonic clock | 単調増加クロック |
| 重放攻擊 | replay attack | リプレイ攻撃 |
