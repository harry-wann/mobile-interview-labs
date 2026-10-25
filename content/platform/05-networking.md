---
id: platform-networking
title: 網路層：URLSession 與 OkHttp 怎麼對照、憑證釘選、token 過期只刷新一次、下單重試
summary: 交易所 App 的網路題集中在三件事：怎麼防止中間人攻擊（憑證釘選）、很多請求同時收到 401 時怎麼只刷新一次 token、逾時之後怎麼重試才不會重複下單。這個單元把 iOS 和 Android 的做法並排講清楚。
priority: P0
day: 2
tags: [urlsession, okhttp, ats, network-security-config, certificate-pinning, token-refresh, authenticator, retry]
---

## 重點講解

這個單元回答六個面試常見問題，每一題都會同時講 iOS 和 Android 的做法：

1. URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？
2. 什麼是憑證釘選（certificate pinning）？兩個平台怎麼設定？
3. 伺服器的憑證要換的時候，釘選怎麼輪替才不會讓使用者連不上？
4. 很多請求同時收到 401，怎麼只刷新一次 token？
5. 網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？
6. 簽名請求遇到手機時間不準怎麼辦？（比較少考，被問到能答就好）

### 1. URLSession 和 OkHttp 怎麼對照？預設的逾時和傳輸安全規則差在哪？

兩邊能做的事幾乎一樣：發 HTTP 請求、HTTP/2、WebSocket、攔截請求。真正需要記的是**預設值**和**傳輸安全的預設規則**，因為面試官喜歡問「逾時預設是多久」「為什麼我的 App 打不了 HTTP」這類問題。

URLSession 是 Apple 內建的網路 API。OkHttp 是 Square 開發的 Android HTTP 使用者端，Retrofit 建立在它上面，負責把 API 宣告成 Kotlin 介面。

| 項目 | iOS（URLSession） | Android（OkHttp＋Retrofit） |
|---|---|---|
| HTTP/2（HTTP 的新版本，一條連線可以同時跑多個請求） | 預設啟用。連線建立時，使用者端和伺服器透過 ALPN（Application-Layer Protocol Negotiation）協商要用 HTTP/2 還是 HTTP/1.1 | 同樣預設啟用，也是 ALPN。連線池預設保留 5 條閒置連線，閒置 5 分鐘後關閉 |
| 逾時 | `timeoutIntervalForRequest` 預設 60 秒，意思是「兩個封包之間最久等多久」，不是整個請求的總時間；`timeoutIntervalForResource` 預設 7 天，才是整個請求的總時限 | connect（建立連線）、read（讀資料）、write（寫資料）預設各 10 秒；`callTimeout`（整個呼叫的總時限）預設不限制 |
| 同時請求的數量 | `httpMaximumConnectionsPerHost` 控制每個主機的同時連線數 | `Dispatcher` 預設同時最多 64 個請求，同一個主機最多 5 個 |
| 攔截請求 | `URLProtocol`、`URLSessionDelegate`；Moya、Alamofire 這類套件有 plugin | `Interceptor`（每個請求都經過）、`Authenticator`（只在 401 時被呼叫，第 4 題會用到） |
| WebSocket | `URLSessionWebSocketTask`（iOS 13 起） | OkHttp 的 `WebSocket` |
| 網路狀態 | `NWPathMonitor`；`waitsForConnectivity` | `ConnectivityManager.NetworkCallback` |

兩邊設定逾時的寫法，註解標出每個數字管的是哪一段：

```swift
let config = URLSessionConfiguration.default
// 兩個封包之間最久等 15 秒，超過就逾時
config.timeoutIntervalForRequest = 15
// 整個請求（含重新連線）最多 60 秒
config.timeoutIntervalForResource = 60
let session = URLSession(configuration: config)
```

```kotlin
val client = OkHttpClient.Builder()
    // 建立連線最久等 10 秒
    .connectTimeout(10, TimeUnit.SECONDS)
    // 等伺服器回資料，兩次讀取之間最久 10 秒
    .readTimeout(10, TimeUnit.SECONDS)
    // 送出資料最久 10 秒
    .writeTimeout(10, TimeUnit.SECONDS)
    // 整個呼叫最多 30 秒（預設是不限制）
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
```

**傳輸安全的預設規則**也不一樣，而且都會影響除錯：

- **iOS 的 ATS（App Transport Security）**：從 iOS 9 起，App 預設只能連 HTTPS，而且要求 TLS（Transport Layer Security，HTTPS 底層的加密協定）1.2 以上、支援前向保密（forward secrecy：就算將來伺服器的私鑰外洩，過去被錄下來的流量也解不開）。如果真的需要放寬，要在 Info.plist 的 `NSAppTransportSecurity` 宣告例外，上架審查時 Apple 可能會要求說明理由。
- **Android 的 Network Security Config**：Android 7.0（API 24）開始提供的 XML 設定檔。`targetSdk` 28 以上的 App 預設禁止明文（HTTP）流量；`targetSdk` 24 以上的 App 預設只信任系統內建的 CA（Certificate Authority，憑證授權單位，也就是負責簽發憑證的機構），**不信任使用者自己安裝的 CA**。

第二點的實際影響是：用 Charles 這類 Proxy 工具看 HTTPS 內容時，Android 的 debug build 要在 Network Security Config 的 `debug-overrides` 裡額外信任使用者 CA；release build 則不應該有這個設定。

> [!TIP]
> 面試時可以這樣說：「兩邊的功能對應得很整齊。我會特別注意預設值：URLSession 的逾時是兩個封包之間 60 秒，OkHttp 的 connect、read、write 各 10 秒、整個呼叫預設不限制，所以我會自己設 callTimeout。安全方面 iOS 有 ATS 預設只允許 HTTPS，Android 有 Network Security Config，預設禁止明文，也不信任使用者安裝的 CA。」

### 2. 什麼是憑證釘選（certificate pinning）？兩個平台怎麼設定？

憑證釘選的意思是：**App 事先記住自己伺服器的公鑰，連線時只接受這把公鑰，即使系統認為別的憑證也有效，一樣拒絕連線。** 目的是防止中間人攻擊（man-in-the-middle attack，攻擊者夾在 App 和伺服器之間，偷看或修改封包）。

為什麼光靠 HTTPS 不夠？一般的 HTTPS 驗證是：伺服器出示一張憑證，手機檢查「這張憑證是不是由我信任的 CA 簽發的」。如果攻擊者能讓手機信任一個假的 CA（例如使用者被騙去安裝描述檔、或連上被動過手腳的公司網路），他就可以用假憑證冒充你的伺服器。交易所 App 的登入、下單、資產資料都走這條線，所以要再多一層檢查：App 自己知道「我們的伺服器公鑰長這樣」，不是它就不連。

**釘什麼？** 釘公鑰的 SHA-256 雜湊（SHA-256 是一種雜湊演算法，把任意資料算成固定長度的指紋）。這一段公鑰資料在憑證裡叫 SPKI（Subject Public Key Info）。不要釘整張憑證檔案，因為憑證每次續期都會換成新的一張；但只要伺服器沿用同一組金鑰，公鑰的雜湊就不會變。

用 openssl 算出一個網域目前的 pin：

```bash
# 取得 api.example.com 目前這張憑證的公鑰，算 SHA-256，再轉成 Base64
openssl s_client -connect api.example.com:443 \
  -servername api.example.com </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | base64
# 輸出類似：AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=（44 個字元，下面的範例
# 都用這種佔位字串）
```

**iOS 的兩種做法：**

1. **宣告式（iOS 14 起）**：在 Info.plist 的 `NSAppTransportSecurity` 底下加 `NSPinnedDomains`，列出網域和要釘的 `SPKI-SHA256-BASE64`。可以釘葉憑證（leaf certificate，網站自己用的那張）的金鑰，也可以釘 CA 的金鑰。

```xml
<key>NSAppTransportSecurity</key>
<dict>
  <key>NSPinnedDomains</key>
  <dict>
    <key>api.example.com</key>
    <dict>
      <key>NSIncludesSubdomains</key><true/>
      <key>NSPinnedLeafIdentities</key>
      <array>
        <dict>
          <key>SPKI-SHA256-BASE64</key>
          <string>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</string>
        </dict>
        <dict>
          <key>SPKI-SHA256-BASE64</key>
          <string>BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</string>
        </dict>
      </array>
    </dict>
  </dict>
</dict>
```

2. **在程式裡檢查**：實作 `URLSessionDelegate` 的 `urlSession(_:didReceive:completionHandler:)`，先讓系統驗證憑證，再比對公鑰雜湊（也可以用 TrustKit 這類套件幫你處理）：

```swift
final class PinningDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (
            URLSession.AuthChallengeDisposition, URLCredential?
        ) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod
                == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              // 先通過系統一般的憑證驗證
              SecTrustEvaluateWithError(trust, nil),
              // 再比對公鑰雜湊（自己實作）
              isPinned(trust)
        else {
            // 任何一步失敗就中止連線
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    private func isPinned(_ trust: SecTrust) -> Bool {
        /* 取出憑證的公鑰，算 SHA-256，和內建的 pin 清單比對 */
        false
    }
}
```

**Android 的兩種做法：**

1. **Network Security Config**：在 `res/xml/network_security_config.xml` 設定，並在 `AndroidManifest.xml` 的 `<application>` 加上 `android:networkSecurityConfig="@xml/network_security_config"`。

```xml
<network-security-config>
  <domain-config>
    <domain includeSubdomains="true">api.example.com</domain>
    <!-- 這個日期之後，釘選自動失效 -->
    <pin-set expiration="2027-06-30">
      <pin digest="SHA-256">AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin>
      <pin digest="SHA-256">BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</pin>
    </pin-set>
  </domain-config>
  <!-- 只在 debug build 生效：信任使用者安裝的 CA，方便用 Charles -->
  <debug-overrides>
    <trust-anchors>
      <certificates src="user" />
    </trust-anchors>
  </debug-overrides>
</network-security-config>
```

2. **OkHttp 的 `CertificatePinner`**：

```kotlin
val client = OkHttpClient.Builder()
    .certificatePinner(
        CertificatePinner.Builder()
            .add(
                "api.example.com",
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
            .add(
                "api.example.com",
                "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
            )
            .build()
    )
    .build()
```

釘選**不是萬能的**：在越獄（jailbreak）或 root 的裝置上，攻擊者可以用工具繞過 App 內的檢查。它的作用是「提高攻擊成本」，要和其他保護措施一起用。

> [!TIP]
> 面試時可以這樣說：「憑證釘選是 App 自己記住伺服器公鑰的雜湊，只接受這把公鑰，這樣即使攻擊者讓手機信任了假的 CA，也沒辦法做中間人攻擊。我會釘公鑰雜湊，不釘整張憑證，因為憑證續期會換。iOS 14 以後可以在 Info.plist 用 NSPinnedDomains 宣告，Android 可以用 Network Security Config 或 OkHttp 的 CertificatePinner。」

### 3. 伺服器的憑證要換的時候，釘選怎麼輪替才不會讓使用者連不上？

**輪替的核心是：新的 pin 必須比新的憑證更早到使用者手上，而且永遠至少放兩個 pin（目前用的、備援的）。** 這才是釘選的面試重點，因為釘選設定錯誤的代價是「所有使用者都連不上」，對交易所來說等於停擺。

想像一個最糟的情境：App 裡只有一個 pin，伺服器某天換了新金鑰。所有舊版 App 立刻全部連線失敗，唯一的解法是發新版，再等使用者更新，這段時間使用者無法下單。

下面是一個安全的輪替時程，pin 清單的變化是重點：

```text
第 1 天   App 3.20 上架：pin 清單 = [金鑰 A（正在用）, 金鑰 B（備援，還沒上線）]
第 30 天  確認大多數使用者已更新到 3.20 以上
第 31 天  伺服器的憑證改用金鑰 B → 3.20 以上的 App 仍然連得上
第 32 天  App 3.21 上架：pin 清單 = [金鑰 B（正在用）, 金鑰 C（新的備援）]
```

實務上要遵守的規則，以及背後的原因：

1. **至少兩個 pin，其中一個是預先產生、還沒上線的備援金鑰。** 輪替時伺服器直接換成備援金鑰，舊版 App 仍然通過檢查。備援金鑰要離線保管好。
2. **釘中繼 CA（intermediate CA）的公鑰，或釘有保留金鑰的公鑰，不要釘每 90 天就換新金鑰的葉憑證。** 憑證鏈（certificate chain）是「根 CA → 中繼 CA → 葉憑證」一層層簽發下來，葉憑證在最下面、換得最勤。釘中繼 CA 比較不怕換憑證，但範圍比較大（同一個 CA 簽發的任何憑證都會通過），安全性稍低，要自己權衡。
3. **新 pin 要在舊憑證到期之前就隨 App 發布，並確認多數使用者已更新，才更換伺服器的憑證。** 另外要準備強制更新機制：發現版本太舊時，提示使用者必須更新。
4. **Android 的 `pin-set` 可以設 `expiration`，過期後釘選自動失效，改回一般的憑證驗證**（這叫 fail-open：出錯時放行而不是擋住）。好處是萬一忘記更新，使用者不會全部斷線；壞處是失效之後就沒有保護了。iOS 的 `NSPinnedDomains` 沒有等價的設定，只能靠發版節奏和強制更新。
5. **只釘自己的 API 網域，不釘第三方 SDK（software development kit，軟體開發套件）的網域。** 第三方何時換憑證你控制不了。
6. **監控釘選失敗的比例。** 比例突然上升，可能是你的輪替出錯，也可能是使用者連到公司的 Proxy 或遇到攻擊，要能快速分辨。
7. **debug 專用的信任設定（`debug-overrides`、`NSAllowsArbitraryLoads`）不能進 release。** 否則釘選形同虛設。

> [!TIP]
> 面試時可以這樣說：「釘選最大的風險是弄錯之後全部使用者連不上，所以我會至少放兩個 pin，一個是正在用的，一個是預先產生的備援金鑰。新 pin 提前隨 App 發布、確認多數使用者更新後才換伺服器憑證。Android 還可以在 pin-set 設 expiration 當保險；iOS 沒有這個機制，我會搭配強制更新和監控釘選失敗率。」

### 4. 很多請求同時收到 401，怎麼只刷新一次 token？

**答案是 single-flight：同一時間只讓一個請求真的去刷新 token，其他也收到 401 的請求等著，共用這一次刷新的結果。** 而且每個請求在刷新之前，要先比較「我失敗時用的 token」和「現在的 token」是不是同一個，如果不一樣，代表別人已經刷新過了，直接用新的重送就好。

先看問題。使用者把交易所 App 放在背景一個小時，回來時 access token（登入後拿到的短效憑證，例如 15 分鐘就過期）已經失效，畫面同時打了 8 個 API（資產、行情、委託單……），全部回 **401**（Unauthorized，意思是「沒有通過驗證」）。如果 8 個請求各自去刷新：

- 浪費 8 次請求，還可能觸發伺服器的流量限制。
- 更嚴重的是，很多伺服器採用 **refresh token 輪替**（rotation）：每用一次 refresh token 換新 token，舊的 refresh token 就立刻作廢。第 1 次刷新成功之後，第 2 到第 8 次拿著已作廢的舊 refresh token，全部失敗。App 以為登入失效，就把使用者登出了。

所以有兩個關鍵：

1. **同一時間只有一個刷新在進行**，其他人等待並共用結果。
2. **進入刷新前先比較 token。** 第 1 個請求刷新完成後，第 2 到第 8 個才輪到執行；如果它們不比較就再刷新一次，一樣會出事。

#### Android：Interceptor 和 Authenticator 的分工

OkHttp 有兩個攔截點，職責不同：

- `Interceptor`：**每個請求**都會經過，適合加上 `Authorization: Bearer <token>` 標頭。
- `Authenticator`：**只有收到 401 時**才會被呼叫。它回傳一個帶新 token 的新請求，OkHttp 就會自動重送；回傳 `null` 代表放棄。

刷新要放在 `Authenticator`，並用 Mutex（coroutine 版本的鎖，同一時間只有一個 coroutine 能進入，其他的會暫停等待，不會卡住執行緒）做 single-flight：

```kotlin
class TokenProvider(
    initial: String,
    private val refresh: suspend () -> String,
) {
    private val mutex = Mutex()
    // @Volatile：讓其他執行緒讀到最新的值
    @Volatile private var token: String = initial

    fun currentToken(): String = token

    suspend fun refreshIfNeeded(failedToken: String): String = mutex.withLock {
        // 別人已經刷新過了，直接用新的，不再刷新
        if (token != failedToken) {
            return@withLock token
        }
        val fresh = refresh()
        token = fresh
        fresh
    }
}

// 每個請求都加上目前的 token
class AuthInterceptor(private val tokens: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("Authorization", "Bearer ${tokens.currentToken()}")
                .build()
        )
}

// 只有收到 401 才會進來
class TokenAuthenticator(private val tokens: TokenProvider) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // 用新 token 重送之後還是 401，就放棄，避免無限迴圈
        if (response.priorResponse != null) {
            return null
        }
        val failedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ") ?: return null
        // OkHttp 的這個方法跑在背景執行緒，允許阻塞等待
        val fresh = runBlocking { tokens.refreshIfNeeded(failedToken) }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
    }
}
```

八個請求同時 401 的過程是：第 1 個拿到鎖，發現 token 還是失敗時那個，於是真的去刷新；第 2 到第 8 個在鎖外面等，輪到它們時發現目前的 token 已經不是自己失敗時用的，直接回傳新 token，不再刷新。

#### iOS：沒有內建的 Authenticator，自己在請求層處理

URLSession 對 Bearer token 沒有內建的刷新機制（它的驗證 delegate 只處理 Basic、Digest 這類驗證挑戰），常見做法是在自己包的網路層攔截 401。用 actor（Swift 的一種型別，同一時間只處理一個呼叫，保護內部的資料不會同時被改）保存 token。要注意的是：actor 在 `await` 等待的期間，**可以讓別的呼叫進來**，所以不能只靠 actor，還要把「進行中的刷新」存起來，讓後來的人等同一個結果：

```swift
actor TokenProvider {
    private var token: String
    // 進行中的刷新
    private var inFlight: Task<String, Error>?
    private let refresh: @Sendable () async throws -> String

    init(
        initial: String,
        refresh: @escaping @Sendable () async throws -> String
    ) {
        self.token = initial
        self.refresh = refresh
    }

    func currentToken() -> String {
        token
    }

    func refreshIfNeeded(failedToken: String) async throws -> String {
        // 別人已經刷新過了
        if token != failedToken {
            return token
        }
        // 已經有人在刷新，等同一個結果
        if let running = inFlight {
            return try await running.value
        }

        let task = Task { [refresh] in try await refresh() }
        inFlight = task
        do {
            let fresh = try await task.value
            token = fresh
            inFlight = nil
            return fresh
        } catch {
            // 失敗後清掉，下一個呼叫可以再試
            inFlight = nil
            throw error
        }
    }
}

struct APIClient {
    let tokens: TokenProvider

    func data(for request: URLRequest) async throws -> Data {
        let used = await tokens.currentToken()
        let (data, response) = try await URLSession.shared.data(
            for: authorized(request, token: used)
        )
        guard (response as? HTTPURLResponse)?.statusCode == 401
        else { return data }

        // 8 個 401 同時進來，只會刷新一次
        let fresh = try await tokens.refreshIfNeeded(failedToken: used)
        let (retryData, _) = try await URLSession.shared.data(
            for: authorized(request, token: fresh)
        )
        // 只重送一次，不再遞迴
        return retryData
    }

    private func authorized(
        _ request: URLRequest,
        token: String
    ) -> URLRequest {
        var request = request
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }
}
```

#### 兩個平台共通的後續處理

- **refresh token 本身也失效時**（例如使用者在別台裝置登出了全部連線），要清除本機的憑證、導向登入畫面，不要無限重試。
- **刷新成功之後，已經建立的 WebSocket 或 SSE（Server-Sent Events，伺服器單向推送資料的 HTTP 連線）要用新 token 重新連線**，否則它們會一直拿著舊 token，直到被伺服器踢掉。

> [!TIP]
> 面試時可以這樣說：「多個請求同時 401 時，我用 single-flight：同一時間只有一個刷新在進行，其他人等同一個結果，而且進入刷新前先比較失敗請求用的 token 和目前的 token，不一樣就直接重送。Android 我會用 OkHttp 的 Authenticator 搭配 Mutex，它只在 401 時被呼叫；iOS 用 actor 加上進行中的 Task。重送只做一次，refresh token 失效就清除憑證、回到登入畫面。」

### 5. 網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？

**只對「冪等」的請求自動重試；下單這種 POST 不能自動重送，要用 `clientOrderId` 當作冪等鍵，逾時後先查詢訂單狀態，再決定要不要重送。**

冪等（idempotent）的意思是：同一個請求送一次和送很多次，對伺服器造成的結果相同。`GET /ticker`（查行情）是冪等的，多送幾次只是多查幾次；`POST /orders`（下單）不是，送兩次可能變成兩張單。

使用者按下「買入」，POST 下單請求在網路不穩時逾時。要小心的是：**逾時只代表 App 沒收到回應，不代表伺服器沒成功。** 伺服器可能早就收到並成交了，只是回應在路上掉了。這時自動重送或讓使用者再按一次，都可能重複下單。

標準做法是：

1. 送出前，App 先產生一個 `clientOrderId`（用 UUID，也就是 universally unique identifier，一個隨機產生、幾乎不會重複的識別碼），隨請求送給伺服器。這種讓伺服器辨認「這是同一個請求」的識別碼，叫冪等鍵（idempotency key）。（假設下單 API 有提供這類欄位，很多交易所的 API 都有。）
2. 逾時後，用這個 ID 查詢訂單：查得到，就顯示對應的結果；查不到，才讓使用者決定要不要重送，而且重送仍然帶**同一個 ID**，伺服器看到重複的 ID 就能拒絕第二張單。
3. UI 上鎖定「買入」按鈕、顯示「確認中」，避免使用者連點。

**可以自動重試的請求，也要小心怎麼重試。** 用指數退避（exponential backoff：每失敗一次，等待時間加倍）加上 jitter（隨機抖動）：

```kotlin
fun backoffMillis(
    attempt: Int,
    baseMs: Long = 1_000,
    capMs: Long = 30_000,
): Long {
    // 上限依序是 1、2、4、8 秒……最多 30 秒
    val ceiling = minOf(capMs, baseMs * (1L shl attempt))
    // 在 0 到上限之間隨機挑一個時間
    return Random.nextLong(0, ceiling + 1)
}
// attempt = 0, 1, 2, 3 時，等待時間的上限分別是 1、2、4、8 秒；
// 實際等多久每次都不一樣
```

```swift
import Foundation

func backoffSeconds(
    attempt: Int,
    base: Double = 1,
    cap: Double = 30
) -> Double {
    // 上限依序是 1、2、4、8 秒……最多 30 秒
    let ceiling = min(cap, base * pow(2, Double(attempt)))
    // 在 0 到上限之間隨機挑一個時間
    return Double.random(in: 0...ceiling)
}
```

為什麼需要 jitter？假設伺服器短暫當機，數萬個 App 同時斷線。如果大家都「等整整 1 秒、2 秒、4 秒」再重連，就會在同一個瞬間一起湧入，把剛恢復的伺服器再次打垮，這種現象叫 thundering herd（驚群效應）。加上隨機，重連的時間就會分散開來。

**哪些錯誤該重試，哪些不該：**

| 狀況 | 做法 | 原因 |
|---|---|---|
| 4xx（例如 400 參數錯誤） | 不重試 | 請求本身有問題，重送結果一樣 |
| 401 | 刷新 token 後重送一次（第 4 題） | 不是暫時性錯誤，是憑證過期 |
| 429（Too Many Requests，請求太頻繁） | 等到 `Retry-After` 標頭指定的時間再重試 | 伺服器已經告訴你要等多久 |
| 5xx 與網路錯誤 | 冪等請求才用退避重試 | 可能是伺服器暫時的問題 |

還有一個 Android 的小細節：OkHttp 預設 `retryOnConnectionFailure` 是 `true`，遇到某些連線問題（例如使用到已經失效的連線）會自動重新嘗試。所以下單請求不能只靠「我沒有寫重試」來避免重複，伺服器端也要有冪等鍵保護。

> [!TIP]
> 面試時可以這樣說：「逾時不代表伺服器沒成功，所以下單 POST 我不會自動重送。我會在送出前產生 clientOrderId，逾時後先用它查詢訂單狀態，確認不存在才讓使用者重送。可以自動重試的請求，我用指數退避加 jitter，4xx 不重試，429 遵守 Retry-After。」

### 6. 簽名請求遇到手機時間不準怎麼辦？（比較少考，被問到能答就好）

有些 API 要求每個請求帶上簽章：用雙方共有的密鑰，對請求內容（包含時間戳記）算出 HMAC（hash-based message authentication code，一種用密鑰產生的簽章），伺服器用同樣方式再算一次，一致才接受。交易所的公開 API（例如 Huobi 相容的 API）就是這種設計，簽章裡包含 UTC（世界標準時間）的時間戳記。

伺服器通常會拒絕時間相差太大的請求，防止被攔截到的請求日後被拿來重送（replay attack）。問題是手機的時間可以被使用者手動調錯，一旦誤差太大，所有簽名請求都會被拒絕。

處理方式是**用伺服器的時間校正**：每次收到回應時，讀取 `Date` 標頭，算出伺服器和手機的時間差，之後簽名時使用校正後的時間。

```text
offset = 伺服器 Date 標頭的時間 − 手機現在的時間
簽名用的時間 = 手機現在的時間 + offset
```

> [!TIP]
> 面試時可以這樣說：「簽名請求通常帶時間戳記，手機時間不準就會被伺服器拒絕。我會用回應裡的 Date 標頭算出時間差，簽名時用校正後的時間，不直接相信手機的時鐘。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Platform05Networking**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Platform05Networking**。Android 的演練 1 到 4 不用開 App，直接在電腦上跑單元測試。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform05-Networking/`，解答在 `labs/ios/Solution/Platform/Platform05-Networking/`；Android 你寫在 `labs/android/practice/platform/platform05-networking/`，解答在 `labs/android/solution/platform/platform05-networking/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路**：iOS 的演練用 App 內自己啟動的本機假伺服器（只聽 127.0.0.1）；Android 的演練用 OkHttp 的 `Interceptor` 當假伺服器，或在單元測試裡用 MockWebServer（OkHttp 提供的本機假伺服器），憑證釘選用 okhttp-tls 現做實驗用的憑證。

### 演練 1：逾時設 1 秒，下載卻等了 2 秒多（第 1 題）

**檔案**：`L01_RequestTimeout.swift`（iOS）、`L01_RequestTimeout.kt`（Android，測試在 `labs/android/practice/platform/test/Platform05NetworkingTest.kt`，已經寫好，不用改）

**情境**：App 下載一份資產明細。假伺服器每 0.2 秒只送出 1 個位元組，共 12 個，全部送完約 2.2 秒：網速很慢，但連線沒有斷，資料一直有進來。工程師希望「整個下載最多等 1 秒，超過就逾時」。

**怎麼寫**

1. **Bug 版**：iOS 在 `URLSessionConfiguration` 設 `timeoutIntervalForRequest = 1`；Android 在 `OkHttpClient.Builder` 設 `readTimeout(1, TimeUnit.SECONDS)`，不設 `callTimeout`。
2. **修正版**：iOS 改設 `timeoutIntervalForResource = 1`；Android 在 `readTimeout` 之外再加 `callTimeout(1, TimeUnit.SECONDS)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 沒有逾時，下載成功，花了約 2200 毫秒（解答實測 iOS 約 2260 到 2310、Android 約 2300 毫秒） |
| 修正版 | 約 1000 毫秒就逾時（iOS 是 `URLError.timedOut`，Android 是 `InterruptedIOException`，解答實測約 1010 到 1110 毫秒） |

**為什麼**：`timeoutIntervalForRequest` 和 `readTimeout` 量的都是「兩次收到資料之間最久等多久」，每收到一點資料就重新計時。這個伺服器每 0.2 秒就送一點，空檔永遠不到 1 秒，所以永遠不會逾時。要限制「整個請求最多多久」，iOS 要設 `timeoutIntervalForResource`，Android 要設 `callTimeout`。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L01_RequestTimeout.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L01_RequestTimeout.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L01_RequestTimeout.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L01_RequestTimeout.kt#fixed
```

### 演練 2：憑證續期那天，所有使用者都連不上（第 2 題）

**檔案**：`L02_PinWholeCert.swift`（iOS）、`L02_PinWholeCert.kt`（Android，測試同上）

**情境**：App 要確保只連到自己的伺服器。伺服器的憑證每 90 天自動續期：換成新的一張憑證，但背後的金鑰沒換。演練準備了三種伺服器憑證：目前的、續期後的（同一把金鑰）、攻擊者的（另一把金鑰）。iOS 版用事先以 `openssl` 做好的測試憑證，內建的 pin 清單用發版時伺服器的憑證算出來；Android 版用 okhttp-tls 現做一個實驗用的憑證機構（CA，代表手機信任的機構），簽出這三張憑證，再用 MockWebServer 當 HTTPS 伺服器。

**怎麼寫**

1. **Bug 版**：iOS 的 `pin(of:)` 把整張憑證檔案拿去算 SHA-256；Android 的 `L02Bug.client` 只信任 App 內附的那一張憑證（用 `l02TrustOnly(bundled)` 建 TLS 設定，像把 `.crt` 放進 `res/raw` 再建一個只含它的 KeyStore）。
2. **修正版**：iOS 的 `pin(of:)` 先用 `P05Pinning.spki(of:)` 取出憑證裡的公鑰，再算 SHA-256；Android 的 `L02Fixed.client` 沿用系統信任的 CA，再加 `CertificatePinner.Builder().add(host, CertificatePinner.pin(憑證))`，釘公鑰的雜湊。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「續期前的伺服器：連得上」，「續期後的伺服器（同一把金鑰、新的憑證）：連不上（拒絕連線）」，攻擊者也連不上。Android 的續期後丟出 `SSLHandshakeException` |
| 修正版 | 續期前、續期後都連得上，攻擊者連不上。iOS 印出內建的 pin `AztowyLM1lqAuk1bhe3RTFIdhN7cDwpBPolu/uILc1A=`（和 `openssl` 指令算出來的相同）；Android 的攻擊者丟出 `SSLPeerUnverifiedException`（Certificate pinning failure） |

**為什麼**：憑證續期時，伺服器換的是憑證這個檔案（序號、有效期限都不同），只要沿用同一把金鑰，公鑰的 SHA-256 就不會變。pin 算在「整張憑證」或「只信任那一張憑證」上，續期那天所有使用者都會連不上；算在公鑰（SPKI，Subject Public Key Info）上才撐得過續期，而攻擊者拿另一把金鑰的憑證，公鑰雜湊對不上，仍然被擋下。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L02_PinWholeCert.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L02_PinWholeCert.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L02_PinWholeCert.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L02_PinWholeCert.kt#fixed
```

### 演練 3：伺服器換金鑰那天，舊版 App 全部連不上（第 3 題）

**檔案**：`L03_SinglePin.swift`（iOS）、`L03_SinglePin.kt`（Android，測試同上）

**情境**：App 3.19 上架時內建 pin。維運計畫在第 31 天，把伺服器換成預先產生、一直離線保管的備援金鑰 B。演練依序檢查三種伺服器：目前的金鑰 A、備援金鑰 B、攻擊者的金鑰 X。

**怎麼寫**

1. **Bug 版**：`shippedPins` 只回傳目前金鑰 A 的 pin（iOS 用檔案裡的 `pin(of:)` 算，Android 用 `CertificatePinner.pin(憑證)`）。
2. **修正版**：回傳兩個 pin：目前的金鑰 A，加上預先產生的備援金鑰 B。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 內建 1 個 pin；「第 1 天，伺服器用金鑰 A：連得上」；「第 31 天，伺服器換成備援金鑰 B：連不上（拒絕連線）」 |
| 修正版 | 內建 2 個 pin；第 1 天、第 31 天都連得上；攻擊者的金鑰 X 連不上 |

**為什麼**：已經上架的 App 3.19 內建的 pin 改不了，要等使用者更新才行。只放一個 pin，伺服器一換金鑰，所有舊版 App 同時連不上，交易所等於停擺。所以 pin 清單至少放兩個：正在用的，以及預先產生、還沒上線的備援金鑰。換金鑰那天，備援金鑰的 pin 早就躺在每個使用者的手機裡了。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L03_SinglePin.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L03_SinglePin.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L03_SinglePin.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L03_SinglePin.kt#fixed
```

### 演練 4：帳號被凍結，App 對伺服器狂打請求（第 4 題）

**檔案**：`L04_RetryOnce.swift`（iOS）、`L04_RetryOnce.kt`（Android，測試同上）

**情境**：使用者的帳號被風控凍結，伺服器對 `/assets` 一律回 401，即使換了新 token 也一樣。App 的規則是「收到 401 就刷新 token，然後重送」。多個請求同時 401 時共用一次刷新（single-flight）已經在 Kotlin04 的演練 7 與 Swift05 的演練 3 練過，這題只看網路層「重送幾次」。iOS 的假伺服器為了保護演練，`/assets` 被打超過 25 次之後會改回 503。

**怎麼寫**

1. **Bug 版**：iOS 的 `fetch` 收到 401 就 `refreshToken()`，再遞迴呼叫 `fetch` 重送，沒有次數限制；Android 的 `authenticate()` 每次都 `refresh()`，都回傳帶新 token 的請求，不檢查 `priorResponse`。
2. **修正版**：iOS 的 `fetch` 多一個 `retried` 旗標，401 只刷新一次、只重送一次；Android 的 `authenticate()` 先檢查 `response.priorResponse != null`，是的話代表這個請求已經重送過一次還是 401，回傳 `null` 放棄。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：伺服器收到 `/assets` 26 次、刷新 token 25 次，最後是 503（伺服器的保護才讓它停）。Android：收到 21 次、刷新 21 次，最後丟出 `ProtocolException: Too many follow-up requests: 21`（OkHttp 自己的上限） |
| 修正版 | 只打 2 次 `/assets`、刷新 1 次，呼叫端拿到 401（iOS 接著印出「清除登入狀態，回到登入畫面」） |

**為什麼**：刷新 token 之後的重送必須只做一次。重送後還是 401，代表問題不是 token 過期（帳號被凍結、權限不足、伺服器的 bug），再刷新也沒有用，只會把伺服器打爆。OkHttp 的 `Authenticator` 用 `priorResponse` 判斷「這是不是重送過的請求」；URLSession 沒有這種機制，也沒有內建上限，要自己用旗標或計數器限制。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L04_RetryOnce.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L04_RetryOnce.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L04_RetryOnce.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L04_RetryOnce.kt#fixed
```

### 演練 5：下單逾時後自動重送，結果買了兩次（第 5 題）

**檔案**：`L05_DuplicateOrder.swift`（iOS）、`L05_DuplicateOrder.kt`（Android）

**情境**：使用者按下「買入」，App 送出 `POST /orders`。假伺服器收到訂單、記下來了，但回應沒有在時限內回到 App（iOS 是伺服器處理 0.6 秒，App 只等 0.3 秒；Android 的假伺服器直接丟出 `SocketTimeoutException`），App 以為失敗。假伺服器對有帶 `X-Client-Order-Id` 標頭的訂單，同一個 ID 只會記一次。

**怎麼寫**

1. **Bug 版**：`placeOrder` 送出不帶識別碼的 `postOrder()`，逾時就自動再送一次（最多 2 次）。
2. **修正版**：先產生 `clientOrderId`（`UUID`），用它送 `postOrder`；逾時後先用 `orderExists` 查詢，查得到就不重送，查不到才用同一個 ID 重送。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「第 1 次下單逾時」「第 2 次下單逾時」，最後「伺服器上的訂單數：2」 |
| 修正版 | 「下單逾時，沒有收到回應」「用 clientOrderId 查詢：訂單已經存在，不重送」，最後「伺服器上的訂單數：1」 |

**為什麼**：逾時只代表 App 沒收到回應，不代表伺服器沒做。伺服器看到兩個沒有識別碼的 POST，只能當成兩張訂單。用 `clientOrderId` 當冪等鍵（idempotency key）之後，伺服器看到同一個 ID 就知道是同一筆；先查詢再決定要不要重送，則讓 App 在逾時之後也能知道真正的結果。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L05_DuplicateOrder.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L05_DuplicateOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L05_DuplicateOrder.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L05_DuplicateOrder.kt#fixed
```

### 演練 6：伺服器一恢復，1000 支手機同一瞬間湧入（第 5 題）

**檔案**：`L06_BackoffJitter.swift`（iOS）、`L06_BackoffJitter.kt`（Android）

**情境**：伺服器短暫當機，1000 支手機同時斷線，各自用指數退避決定第 3 次重試要等多久。演練用固定種子的亂數，把這 1000 個等待時間依 0.1 秒分組，看同一個 0.1 秒內最多有幾支手機一起重連。

**怎麼寫**

1. **Bug 版**：`backoffSeconds`（Android 是 `backoffMillis`）只回傳 `min(30, 2 的 attempt 次方)` 秒，不加亂數。
2. **修正版**：先算出上限，再在 0 到上限之間隨機挑一個時間（iOS 用 `Double.random(in:using:)`，Android 用 `random.nextLong(0, ceiling + 1)`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「等待時間上限 8 秒，實際在 8.0 到 8.0 秒之間」，同一個 0.1 秒內湧入最多的有 1000 支 |
| 修正版 | 實際等待時間分散在 0 到 8 秒之間，同一個 0.1 秒內最多只有二十幾支（解答實測 iOS 21 支、Android 22 支） |

**為什麼**：指數退避讓重試的間隔越來越長，但如果每支手機算出來的時間一模一樣，所有人還是在同一個瞬間一起重連，剛恢復的伺服器再次被打垮，這叫 thundering herd（驚群效應）。加上 jitter（隨機抖動）之後，重連的時間散開，伺服器才有喘息的空間。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L06_BackoffJitter.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L06_BackoffJitter.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L06_BackoffJitter.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L06_BackoffJitter.kt#fixed
```

### 演練 7：手機時間調快 10 分鐘，所有下單請求被拒絕（第 6 題）

**檔案**：`L07_ClockSkew.swift`（iOS）、`L07_ClockSkew.kt`（Android）

**情境**：下單 API 要求每個請求帶 `X-Timestamp` 和簽章（用雙方共有的密鑰，對「時間戳記加路徑」算出來的 HMAC）。假伺服器只接受時間差在 30 秒內的請求，每個回應都帶 `Date` 標頭。使用者手動把手機時間調快了 10 分鐘（演練用一個函式代表手機的時鐘）。App 先打一支不需要簽名的 `/ticker`，再送簽名的下單請求。

**怎麼寫**

1. **Bug 版**：簽名用的時間直接取手機的時鐘（`deviceNow()`／`deviceNowMs()`）。
2. **修正版**：`observe` 讀回應的 `Date` 標頭，算出 offset = 伺服器時間 − 手機現在的時間；`timestamp()` 改用「手機時間 + offset」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「簽名用的時間比伺服器的時間快 600 秒」，「簽名的下單請求：HTTP 401」 |
| 修正版 | 時間差在 1 秒以內，「簽名的下單請求：HTTP 200」 |

**為什麼**：伺服器拒絕時間差太大的請求，是為了防止被攔截的請求日後被拿來重送（replay attack）。手機的時鐘使用者可以隨意調，不能直接相信；每個回應的 `Date` 標頭帶著伺服器的時間，用它算出時間差再校正，簽名就不受手機時鐘影響。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform05-Networking/L07_ClockSkew.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform05-Networking/L07_ClockSkew.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform05-networking/L07_ClockSkew.kt#bug
```

```lab-solution
android/solution/platform/platform05-networking/L07_ClockSkew.kt#fixed
```

第 1 題的傳輸安全規則（ATS 與 Network Security Config）是設定檔層級的規則，沒有可以執行的 bug，沒有獨立的演練；多個請求同時 401 的 single-flight（第 4 題）在 Kotlin04 與 Swift05 已經練過，這個單元只練重送次數。

## 常見陷阱

- **釘整張憑證。** 憑證每次續期都會換，整批使用者會在換憑證的那天連不上。要釘公鑰的雜湊。
- **只放一個 pin，沒有備援金鑰。** 一旦伺服器換金鑰，舊版 App 全部斷線。至少放兩個，其中一個是預先產生的備援。
- **在 `Interceptor` 內做 401 刷新，並且遞迴呼叫 `chain.proceed`。** 沒有重試上限，容易無限迴圈，而且並行的請求會各自刷新。刷新應該放在 `Authenticator`，並檢查 `priorResponse`。
- **刷新時不先比較 token，每個 401 都刷新一次。** 在 refresh token 輪替的伺服器上，第二次起會拿到已作廢的 refresh token，使用者被登出。
- **對下單 POST 自動重試。** 逾時不代表沒成功，會造成重複下單。要用 `clientOrderId` 查詢狀態。
- **把 debug 的信任設定（`debug-overrides`、`NSAllowsArbitraryLoads`）帶進 release。** 這樣釘選就沒有意義了。
- **把 token 放在網址參數。** 網址會被記錄在伺服器日誌和 Referer 標頭裡。token 要放在 `Authorization` 標頭。
- **在 interceptor 裡用 `runBlocking` 卻沒設逾時。** 如果刷新請求卡住，OkHttp 的執行緒會被長時間占住。刷新請求本身也要有逾時。

## 面試官想聽到的

1. 能說明憑證釘選釘的是公鑰雜湊，而且講得出輪替策略：至少兩個 pin、備援金鑰、新 pin 提前發布、Android 的 `expiration`、強制更新機制。
2. 能解釋 `Authenticator` 只在 401 時被呼叫，和 `Interceptor` 的分工；能說明 single-flight 的兩個重點：同一時間只刷新一次、刷新前先比較 token。
3. 重試的原則：只重試冪等的請求；下單用 `clientOrderId` 加狀態查詢；退避要加 jitter；429 要遵守 `Retry-After`。
4. 知道兩個平台的傳輸安全預設：iOS 的 ATS、Android 的 Network Security Config，以及 Android 預設不信任使用者安裝的 CA。
5. 知道簽名請求和手機時鐘偏差有關，並說得出校正方式。

> [!FIELD]
> 在一個社交 App 的 iOS 版，我做過 token 刷新的 single-flight。網路層用 Moya（建立在 Alamofire 上的網路抽象層），遇到 401 時，用 AsyncSemaphore（Semaphore 套件提供的非同步版 semaphore，一次只讓一個呼叫進入）讓刷新一次只進行一個，加上節流（短時間內不重複刷新），同一個請求只重送一次；refresh token 失效時清除憑證，刷新成功後讓 Socket.IO 連線用新 token 重連。
> 換成交易所 App 的 Android 版，我會用 OkHttp 的 `Authenticator` 搭配 Mutex 做出同樣的行為，再加上指數退避加 jitter、下單的 `clientOrderId`，並建立憑證釘選與輪替的流程。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 網路 API | URLSession（Moya、Alamofire 是常見的封裝） | OkHttp，搭配 Retrofit |
| 傳輸安全預設 | ATS：只允許 HTTPS、TLS 1.2 以上；例外要在 Info.plist 宣告 | Network Security Config：`targetSdk` 28 以上禁止明文；`targetSdk` 24 以上不信任使用者安裝的 CA |
| 憑證釘選 | Info.plist 的 `NSPinnedDomains`（iOS 14 起），或在 `URLSessionDelegate` 裡比對 | `network_security_config.xml` 的 `pin-set`，或 OkHttp 的 `CertificatePinner` |
| 釘選的保險機制 | 沒有 `expiration`，靠發版節奏與強制更新 | `pin-set` 的 `expiration`，過期後釘選失效 |
| 401 處理 | 沒有內建機制，自己在請求層攔截；用 actor 加進行中的 Task | `Authenticator`（只在 401 呼叫）加 Mutex |
| 逾時預設 | 請求逾時 60 秒（封包之間）、資源逾時 7 天 | connect、read、write 各 10 秒；`callTimeout` 不限制 |
| 網路狀態 | `NWPathMonitor` | `ConnectivityManager.NetworkCallback` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 憑證釘選 | certificate pinning | 証明書ピンニング |
| 中間人攻擊 | man-in-the-middle attack | 中間者攻撃 |
| 冪等 | idempotent | 冪等 |
| 指數退避 | exponential backoff | 指数バックオフ |
| 存取權杖／重新整理權杖 | access token／refresh token | アクセストークン／リフレッシュトークン |
| 憑證授權單位 | certificate authority（CA） | 認証局 |
| 公鑰 | public key | 公開鍵 |
