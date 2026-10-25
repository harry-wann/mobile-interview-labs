---
id: platform-deeplink
title: Deep Link：Universal Links 與 App Links 怎麼運作、怎麼驗證、怎麼避免被拿來攻擊
summary: Deep link 是讓外部連結直接開到 App 裡某個畫面的功能。推播、行銷連結、錢包回跳都會用到，也是交易所 App 的攻擊入口。這個單元說明兩個平台的運作與驗證方式，以及怎麼避免外部連結觸發下單這類敏感操作。
priority: P1
day: 2
tags: [deeplink, universal-links, app-links, aasa, assetlinks, deferred-deeplink, walletconnect, routing-security]
---

## 重點講解

Deep link（深層連結）指的是：點一個連結，直接開到 App 裡的某個畫面，例如價格提醒推播點下去直接看到 BTC 的行情頁。這個單元回答六個面試常見問題，每一題都會同時講 iOS 和 Android：

1. 自訂 scheme、Universal Links、App Links 差在哪？為什麼交易所 App 要用 https 連結？
2. 系統怎麼確認「這個網址真的屬於這個 App」？驗證失敗常見的原因是什麼？
3. App 怎麼接到連結？App 沒開著（冷啟動）和已經開著有什麼不同？
4. 使用者還沒安裝 App 時，連結要怎麼處理？（deferred deep link）
5. 怎麼避免外部連結觸發下單這類敏感操作？
6. 錢包 App 回跳（WalletConnect）怎麼運作？（比較少考，被問到能答就好）

### 1. 自訂 scheme、Universal Links、App Links 差在哪？為什麼交易所 App 要用 https 連結？

**自訂 scheme（`myapp://ticker/btcjpy`）任何 App 都能註冊同名的 scheme，系統不會驗證；Universal Links（iOS）和 App Links（Android）是 https 連結，系統會驗證「這個網域真的屬於這個 App」，所以比較安全，而且沒安裝 App 時還能退回開網頁。**

| | 自訂 scheme | Universal Links（iOS） | App Links（Android） |
|---|---|---|---|
| 網址長相 | `myapp://ticker/btcjpy` | `https://www.example.com/ticker/btcjpy` | 同左 |
| 誰能宣稱擁有 | 任何 App 都能註冊同名的 scheme | 只有網域的擁有者 | 只有網域的擁有者 |
| 沒安裝 App 時 | 沒有反應或顯示錯誤 | 開網頁 | 開網頁 |
| 系統有沒有驗證 | 沒有 | 有（靠 AASA 檔案） | 有（靠 assetlinks.json 檔案） |

兩個名詞先說明：Universal Links 是 Apple 的名稱；App Links 是 Android 的名稱，指「已通過驗證的 https 連結」。Android 的 intent-filter 如果只宣告了 https 但沒有通過驗證，只是一般的 deep link，Android 12 起不會直接開 App（第 2 題會說明）。

**為什麼自訂 scheme 不安全？** 攻擊者可以做一個惡意 App，註冊和你一樣的 scheme。使用者點到連結時，系統可能把連結交給惡意 App。如果這個連結帶著 OAuth（Open Authorization，第三方登入常用的授權標準）的授權碼，授權碼就被偷走了。所以登入回呼、授權這類敏感流程，要用已驗證的 https 連結；OAuth 登入則用系統提供的專用元件：iOS 的 `ASWebAuthenticationSession` 和 Android 的 Custom Tabs（在 App 內開啟的瀏覽器分頁）。

> [!TIP]
> 面試時可以這樣說：「自訂 scheme 沒有驗證，任何 App 都能註冊同名的 scheme，所以不能拿來傳敏感資訊。我會用 Universal Links 和 App Links，也就是系統驗證過的 https 連結，沒安裝 App 時還能退回開網頁。」

### 2. 系統怎麼確認「這個網址真的屬於這個 App」？驗證失敗常見的原因是什麼？

**做法是：網域的擁有者在自己的伺服器放一個驗證檔，宣告「這個 App 可以開我的連結」；App 在安裝檔裡也宣告「我要處理這個網域的連結」。系統會抓驗證檔，兩邊對得上才算通過。** 兩個平台的驗證檔不同：

| | iOS：Universal Links | Android：App Links |
|---|---|---|
| 驗證檔 | `https://<網域>/.well-known/apple-app-site-association`（簡稱 AASA，全名 Apple App Site Association） | `https://<網域>/.well-known/assetlinks.json` |
| 檔案內容 | App 的識別：`TeamID.BundleID`，以及允許開啟的路徑規則 | App 的 package name，以及簽署憑證的 SHA-256 指紋 |
| App 端的設定 | Xcode 的 Associated Domains，加上 `applinks:<網域>` | `AndroidManifest.xml` 的 `<intent-filter android:autoVerify="true">` |
| 何時驗證 | App 安裝與更新時，由系統透過 Apple 的 CDN（Content Delivery Network，內容傳遞網路）抓 AASA，所以修改後會有快取延遲 | App 安裝時由系統抓 `assetlinks.json` 驗證 |
| 沒通過驗證 | 連結在瀏覽器開啟 | Android 12 起，連結一律在瀏覽器開啟（Android 11 以前會跳出選擇視窗） |

iOS 的 AASA 檔案（JSON 格式）：

```json
{
  "applinks": {
    "details": [
      {
        "appIDs": ["ABCDE12345.com.example.app"],
        "components": [
          { "/": "/ticker/*" },
          { "/": "/alert/*" },
          { "/": "/admin/*", "exclude": true }
        ]
      }
    ]
  }
}
```

`appIDs` 是 Apple 開發者帳號的 Team ID 加上 Bundle ID；`components` 是允許開啟 App 的路徑規則（iOS 13 起），`exclude` 表示這個路徑不要開 App。App 端還要在 Xcode 的 Signing & Capabilities 加 Associated Domains，填 `applinks:www.example.com`。

Android 的 `assetlinks.json`，以及 manifest 的設定：

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.example.app",
      "sha256_cert_fingerprints": [
        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
      ]
    }
  }
]
```

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop">
    <!-- 要求系統在安裝時驗證這個網域 -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="www.example.com" />
    </intent-filter>
</activity>
```

**驗證失敗最常見的原因：**

- **AASA 被重導向，或用錯格式。** AASA 必須用 HTTPS 提供、**不能被重導向**（301、302 都不行）、檔名**不能有 `.json` 副檔名**、`Content-Type` 要是 `application/json`。失敗時沒有任何錯誤訊息，只是連結打不開 App。可以用 `curl -I https://<網域>/.well-known/apple-app-site-association` 檢查實際回應。
- **Android 的指紋填錯。** 如果 App 使用 Play App Signing（Google 幫你保管簽署金鑰，你只持有上傳金鑰），安裝到使用者手機上的 APK（Android Package，Android 的安裝檔）是由 Google 用「應用程式簽署金鑰」重新簽署的，所以 `assetlinks.json` 要填**這把金鑰**的 SHA-256 指紋（Play Console 的 App integrity 頁面查得到），不是上傳金鑰的指紋。debug build 用的是 debug 憑證，要另外把它的指紋也加進去，否則「本機正常、上架版壞掉」或反過來。
- **Apple 的 CDN 快取。** 你更新了 AASA，但手機抓到的還是舊的。開發時可以在 Associated Domains 寫 `applinks:www.example.com?mode=developer`，讓用 Xcode 直接安裝的 debug build 繞過快取。
- **使用者自己選了瀏覽器。** 在 iOS 長按連結選「在 Safari 中打開」，系統會記住這個選擇，之後該網域的連結可能不再開 App。

Android 可以用指令查驗證狀態與重新驗證：

```bash
# 查看每個網域的驗證狀態
adb shell pm get-app-links com.example.app
# 要求系統重新驗證
adb shell pm verify-app-links --re-verify com.example.app
```

> [!TIP]
> 面試時可以這樣說：「兩個平台都是在網域放驗證檔，系統抓檔案對照。iOS 是 AASA，要 HTTPS、不能重導向、沒有副檔名；Android 是 assetlinks.json，要填 Play App Signing 簽署金鑰的指紋，不是上傳金鑰。連結打不開 App 時，我會先用 curl 看 AASA 的實際回應，Android 用 adb 查驗證狀態。」

### 3. App 怎麼接到連結？App 沒開著（冷啟動）和已經開著有什麼不同？

**連結進來的入口，依「App 是被連結啟動的，還是本來就開著」分成兩條路徑；實務上不管從哪條進來，都應該交給同一個 Router（負責把網址轉成畫面的程式）處理。**

**iOS 的入口：**

- SwiftUI：`.onOpenURL`（iOS 14 起）同時接收自訂 scheme 和 Universal Link，SwiftUI 會直接把網址傳進來。另一個入口是 `.onContinueUserActivity(NSUserActivityTypeBrowsingWeb)`：它收到的是 `NSUserActivity`（描述「使用者正在做什麼」的物件），網址在 `webpageURL`。實務上兩個都註冊，交給同一個 Router，並讓 Router 對短時間內重複收到的同一個網址只處理一次。
- UIKit（使用 scene 架構）：App 開著時，Universal Link 從 `scene(_:continue:)` 進來；App 是被連結啟動的（冷啟動），連結放在 `willConnectTo` 的 `connectionOptions.userActivities`。

```swift
import SwiftUI

struct RootView: View {
    let router: Router

    var body: some View {
        ContentView()
            // 自訂 scheme 和 Universal Link（iOS 14 起）
            .onOpenURL { url in router.handle(url) }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                // Universal Link
                if let url = activity.webpageURL {
                    router.handle(url)
                }
            }
    }
}
```

```swift
import UIKit

// UIKit：SceneDelegate 的三個入口
func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
) {
    // 冷啟動：App 是被連結啟動的，
    // 連結放在 connectionOptions 裡，不會再走 continue
    if let url = connectionOptions.userActivities.first(
        where: { $0.activityType == NSUserActivityTypeBrowsingWeb }
    )?.webpageURL {
        router.handle(url)
    }
}

func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
    // App 已經開著（背景或前景）：Universal Link 從這裡進來
    if userActivity.activityType == NSUserActivityTypeBrowsingWeb,
       let url = userActivity.webpageURL {
        router.handle(url)
    }
}

func scene(
    _ scene: UIScene,
    openURLContexts URLContexts: Set<UIOpenURLContext>
) {
    // 自訂 scheme（myapp://）從這裡進來
    if let url = URLContexts.first?.url {
        router.handle(url)
    }
}
```

**Android 的入口：** 連結放在 Activity 收到的 `Intent`（描述「要做什麼」的訊息物件）的 `data`。冷啟動時，從 `onCreate` 的 `intent` 取得；App 已經開著，而且 Activity 的 `launchMode` 是 `singleTop` 或 `singleTask`（不會重新建立 Activity）時，新的連結從 `onNewIntent` 進來，很容易漏掉。

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷啟動：App 是被這個連結啟動的
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 讓之後取得的 intent 是新的這一個
        setIntent(intent)
        // App 已經開著：新的連結從這裡進來
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val url = intent.data?.toString() ?: return
        // 交給同一個 Router
        router.handle(url)
    }
}
```

> [!TIP]
> 面試時可以這樣說：「連結有冷啟動和已經開著兩種入口，iOS 是 connectionOptions 和 scene continue，Android 是 onCreate 的 intent 和 onNewIntent，最容易漏掉的是已經開著的情況。我會讓所有入口都交給同一個 Router，推播點擊、行銷連結走同一套規則。」

### 4. 使用者還沒安裝 App 時，連結要怎麼處理？（deferred deep link）

**Universal Links 和 App Links 只在 App 已安裝時才會開 App；沒安裝時開的是網頁，連結的資訊不會跟著使用者走過安裝流程。** 如果行銷活動希望「點連結 → 去商店安裝 → 第一次開啟 App 時，直接到活動指定的行情頁」，這種做法叫 deferred deep link（延遲的深層連結）。兩個平台的做法差很多：

- **Android：** 有官方的 Play Install Referrer API。商店連結可以帶一個 `referrer` 參數，App 第一次啟動時用 Install Referrer API 讀回來，就知道原本要去哪裡。
- **iOS：** 沒有官方的等價機制。歸因 SDK（software development kit，用來判斷使用者是從哪個廣告來的工具，例如 Airbridge、Adjust）通常用剪貼簿，或是裝置指紋（拿 IP 位址等資訊做機率比對）來猜測，準確度和隱私上都有取捨。App Clip（輕量版的 App 片段）是另一條路。
- **Firebase Dynamic Links 已於 2025 年 8 月停止服務。** 行銷素材裡如果還有 `page.link` 的連結，要遷移到其他方案。

> [!TIP]
> 面試時可以這樣說：「Universal Links 和 App Links 沒安裝 App 時只會開網頁，要讓安裝後第一次開啟還是到指定頁面，需要 deferred deep link。Android 有官方的 Install Referrer API，iOS 沒有官方做法，通常靠歸因 SDK，準確度有限。Firebase Dynamic Links 已經在 2025 年 8 月停止服務。」

### 5. 怎麼避免外部連結觸發下單這類敏感操作？

**原則是：把所有進來的連結都當成「不可信的輸入」，統一交給一個 Router，只接受白名單內的路徑，而且連結只負責「開到某個畫面」，絕對不能直接執行下單、提領、改設定這類有副作用的操作。**

為什麼這在交易所 App 特別重要？連結可以從推播、簡訊、網頁、別的 App 進來，內容都是別人寫的。如果有人傳給你一個連結 `https://www.example.com/order?symbol=btcjpy&amount=10&action=buy`，而 App 會「直接預填並送出」，使用者點一下就下單了。

具體的做法：

1. **集中式 Router。** 把網址解析成有型別的 `Route`，整個 App 只有一個入口，不要在各個畫面零散處理。
2. **白名單。** 只接受已知的 host 和 path，其他一律當作無效（回到首頁），不嘗試「盡量處理」。
3. **驗證參數。** 檢查型別、長度、可用的字元（例如代號只允許小寫英數）；路徑要先解碼再驗證，才擋得住 `..%2F` 這種把「/」編碼過的繞過手法。
4. **權限。** 需要登入的路由，未登入時先暫存目標，登入完成後再繼續；敏感畫面要重新驗證（例如生物辨識）。
5. **不能直接觸發動作。** 下單、提領、改設定最多開到「確認頁」，讓使用者自己按確認。
6. **連結裡不放 token，也不直接開啟 `redirect=`、`url=` 這類參數指定的網址。** 後者叫 open redirect（開放式重新導向）：攻擊者可以用你的網域做出一個看起來可信、實際導向釣魚網站的連結。
7. **Android 額外注意：** 設定了 intent-filter 的 Activity 必須設成 `android:exported="true"`，代表其他 App 也能直接呼叫它，所以 `Intent` 裡的 extras（附加資料）也要驗證，不能信任。
8. **做成純函式、寫單元測試。** Router 不依賴畫面，輸入網址、輸出 `Route`，就能用大量惡意輸入測試。

Swift 與 Kotlin 的做法一樣，下面是重點寫法（只開放 `/ticker/{代號}` 和 `/alert/{編號}` 兩條路徑）：

```swift
import Foundation

enum Route: Equatable {
    case home
    case ticker(String)
    case alert(String)
    // 未登入：先登入，登入後回到原本的連結
    case login(resume: String)
    case invalid
}

func route(
    for urlString: String,
    isLoggedIn: Bool,
    allowedHosts: Set<String>
) -> Route {
    guard let comps = URLComponents(string: urlString),
          // 只接受 https
          comps.scheme == "https",
          let host = comps.host?.lowercased(),
          // host 不在白名單
          allowedHosts.contains(host)
    else {
        return .invalid
    }
    // comps.path 已經解碼過，%2F 會變成 /，多出來的段落會讓下面的比對失敗
    let parts = comps.path.split(separator: "/").map(String.init)
    switch parts.count {
    case 0: return .home
    case 2 where parts[0] == "ticker"
        && parts[1].range(
            of: "^[a-z0-9]{3,12}$",
            options: .regularExpression
        ) != nil:
        return .ticker(parts[1])
    case 2 where parts[0] == "alert"
        && parts[1].range(
            of: "^[A-Za-z0-9]{6,32}$",
            options: .regularExpression
        ) != nil:
        return isLoggedIn ? .alert(parts[1]) : .login(resume: urlString)
    // /withdraw、/order 之類沒有列出的路徑，一律無效
    default: return .invalid
    }
}

let hosts: Set<String> = ["www.example.com"]
// 輸出：ticker("btcjpy")
print(route(
    for: "https://www.example.com/ticker/btcjpy",
    isLoggedIn: false,
    allowedHosts: hosts
))
// 輸出：invalid
print(route(
    for: "https://www.example.com/withdraw?amount=1&address=abc",
    isLoggedIn: true,
    allowedHosts: hosts
))
// 輸出：invalid
print(route(
    for: "https://evil.example.org/ticker/btcjpy",
    isLoggedIn: true,
    allowedHosts: hosts
))
```

```kotlin
import java.net.URI

sealed class Route {
    object Home : Route()
    data class Ticker(val symbol: String) : Route()
    data class Alert(val id: String) : Route()
    // 未登入：先登入，登入後回到原本的連結
    data class Login(val resume: String) : Route()
    object Invalid : Route()
}

private val symbolRegex = Regex("^[a-z0-9]{3,12}$")
private val alertIdRegex = Regex("^[A-Za-z0-9]{6,32}$")

fun route(
    urlString: String,
    isLoggedIn: Boolean,
    allowedHosts: Set<String>,
): Route {
    val uri = try {
        URI(urlString)
    } catch (e: Exception) {
        return Route.Invalid
    }
    // 只接受 https
    if (uri.scheme != "https") {
        return Route.Invalid
    }
    val host = uri.host?.lowercase() ?: return Route.Invalid
    // host 不在白名單
    if (host !in allowedHosts) {
        return Route.Invalid
    }
    // uri.path 已經解碼過，%2F 會變成 /
    val parts = (uri.path ?: "").split("/").filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> Route.Home
        parts.size == 2 && parts[0] == "ticker" &&
            symbolRegex.matches(parts[1]) -> Route.Ticker(parts[1])
        parts.size == 2 && parts[0] == "alert" &&
            alertIdRegex.matches(parts[1]) ->
            if (isLoggedIn) Route.Alert(parts[1]) else Route.Login(urlString)
        // /withdraw、/order 之類沒有列出的路徑，一律無效
        else -> Route.Invalid
    }
}
```

注意 `query` 參數（`?` 後面那一段）在這個 Router 完全沒有被讀取，所以 `?action=withdraw&amount=...` 這類內容永遠不會影響結果。

> [!WARNING]
> 面試官常追問：「如果行銷同事要求連結直接帶金額和幣種，讓使用者一點就下單，你怎麼辦？」標準答案是：最多開到下單頁並預填，但一定要讓使用者自己確認並按下送出，因為連結的內容是別人寫的，我們無法確定使用者本人是否知情。

> [!TIP]
> 面試時可以這樣說：「我把所有進來的連結都當成不可信的輸入，統一進一個 Router，只接受白名單的 host 和路徑，參數驗證型別與字元，路徑解碼後才驗證。連結只負責開到畫面，下單和提領這類有副作用的操作一律要使用者在 App 裡自己確認。Router 做成純函式，可以用惡意輸入做單元測試。」

### 6. 錢包 App 回跳（WalletConnect）怎麼運作？（比較少考，被問到能答就好）

WalletConnect 是一個讓 App 連接外部加密貨幣錢包 App 的通訊協定。流程是：

1. 你的 App 產生一個配對用的 `wc:` 網址（pairing URI，URI 是 Uniform Resource Identifier，網址的通用格式），透過 universal link 或自訂 scheme 開啟錢包 App。
2. 使用者在錢包 App 裡確認或簽名。
3. 簽名結果**透過 WalletConnect 的中繼伺服器（relay）傳回你的 App**，錢包再用連結把使用者帶回原本的 App。

重點是：**deep link 只負責「切換 App」，簽章資料不在連結裡。** 回跳的連結只用來把使用者帶回 App，裡面不含簽章或密鑰。

兩個平台的實作細節：

- **iOS：** 要判斷使用者有沒有安裝某個錢包 App，要先在 Info.plist 的 `LSApplicationQueriesSchemes` 宣告那個錢包的 scheme（最多約 50 個），才能呼叫 `canOpenURL`。
- **Android：** Android 11 起有套件可見性（package visibility）限制，要在 manifest 用 `<queries>` 宣告想查詢的套件。

**簽名訊息要防止重放（replay）。** 如果每次都請使用者簽同一段固定的訊息，任何人拿到這份簽章，就可以在別的地方拿來冒充。SIWE（Sign-In with Ethereum，EIP-4361，EIP 是 Ethereum Improvement Proposal，以太坊改進提案）規定了訊息的格式：要包含網域、錢包地址、伺服器發出且只能用一次的隨機值（nonce，number used once）、簽發時間與到期時間；伺服器收到簽章後，驗證這些欄位並讓 nonce 作廢。簽章的驗證一定要在伺服器做，不能只在 App 裡驗。

> [!TIP]
> 面試時可以這樣說：「WalletConnect 的 deep link 只負責在 App 之間切換，簽章資料是透過中繼伺服器傳回，不在連結裡。要簽的訊息我會用 SIWE 格式，包含網域、nonce 和到期時間，由伺服器驗證並讓 nonce 作廢，這樣簽章就不能被重放。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Platform06DeepLink**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Platform06DeepLink**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform06-DeepLink/`，解答在 `labs/ios/Solution/Platform/Platform06-DeepLink/`；Android 你寫在 `labs/android/practice/platform/platform06-deeplink/`，解答在 `labs/android/solution/platform/platform06-deeplink/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **關於連結**：演練的 Router、冷啟動入口、登入流程都做成不依賴畫面的純程式（iOS 用 `URLComponents`，Android 用 `java.net.URI`），所以不需要真的網域，也不需要真的推播，執行結果每次都一樣。

### 演練 1：點推播的連結卻沒反應（第 3 題）

**檔案**：`L01_EntryPoints.swift`（iOS）、`L01_EntryPoints.kt`（Android）

**情境**：連結有好幾個入口，不管從哪個入口進來，都要交給同一個 Router。演練用簡化的假 `SceneDelegate`（iOS）和假 `Activity`（Android）模擬入口，Router 只記下「收到什麼連結」。

- iOS 的入口：冷啟動時，連結放在 `willConnectTo` 的 `connectionOptions`：自訂 scheme 在 `urlContexts`，Universal Link 在 `userActivities`，是兩個不同的地方；App 已經開著時，自訂 scheme 走 `openURLContexts`，Universal Link 走 `continue`。劇本：App 先後被自訂 scheme、Universal Link 各冷啟動一次，接著開著的時候再收到兩個連結，共 4 個。
- Android 的入口：冷啟動時連結在 `onCreate` 的 `intent.data`；`MainActivity` 是 `singleTop`，App 已經開著時，新的連結從 `onNewIntent` 進來。劇本：App 被 App Link 冷啟動，接著開著的時候又收到兩個連結，共 3 個。

**怎麼寫**

1. **Bug 版**：iOS 的 `willConnectTo` 只處理 `options.urlContexts`；Android 只覆寫 `onCreate`，不覆寫 `onNewIntent`。
2. **修正版**：iOS 的 `willConnectTo` 再處理 `options.userActivities`；Android 覆寫 `onNewIntent`，先 `setIntent(intent)`，再把 `intent.data` 交給同一個 Router。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：「Router 收到 3 個連結，漏接 1 個（共 4 個）」，沒有收到冷啟動的 Universal Link。Android：「Router 收到 1 個連結，漏接 2 個（共 3 個）」，只收到冷啟動的那一個 |
| 修正版 | 全部收到：iOS「收到 4 個連結，漏接 0 個」，Android「收到 3 個連結，漏接 0 個」 |

**為什麼**：連結進來的路徑，依「App 是被連結啟動的，還是本來就開著」而不同，漏掉任何一條，使用者就會點了沒反應。實務上最容易漏掉的，iOS 是冷啟動時的 Universal Link（放在 `userActivities`，不在 `urlContexts`），Android 是 App 已經開著的 `onNewIntent`。解法是每個入口都交給同一個 Router，規則只寫一份。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L01_EntryPoints.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L01_EntryPoints.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform06-deeplink/L01_EntryPoints.kt#bug
```

```lab-solution
android/solution/platform/platform06-deeplink/L01_EntryPoints.kt#fixed
```

### 演練 2：每次打開 App 都跳到同一個活動頁（第 4 題）

**檔案**：`L02_DeferredOnce.swift`（iOS）、`L02_DeferredOnce.kt`（Android）

**情境**：行銷活動的廣告連結，使用者點了之後去商店安裝，第一次打開 App 時要直接到活動指定的行情頁（deferred deep link）。Android 靠 Play Install Referrer API 讀回來，iOS 靠歸因工具（例如 Airbridge、Adjust）詢問。這個答案每次詢問都一樣，因為它只知道「安裝前點過什麼」，不知道 App 有沒有已經用掉。演練模擬使用者連續冷啟動 3 次，App 用 `UserDefaults`／`SharedPreferences`（演練裡用 `LaunchStore` 代表，關掉 App 再開也還在）記狀態。

**怎麼寫**

1. **Bug 版**：每次冷啟動都問歸因工具（Android 是 Install Referrer），有答案就 `navigate`。
2. **修正版**：先檢查 `store.deferredLinkHandled`，已經處理過就不再問；否則問，有答案就先把 `deferredLinkHandled` 設成 `true`，再 `navigate`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 3 次啟動都「跳到 https://www.example.com/ticker/btcjpy?campaign=autumn」，「3 次冷啟動，被活動連結帶走 3 次」 |
| 修正版 | 第 1 次啟動跳到活動頁，第 2、3 次「照常顯示首頁」，「被活動連結帶走 1 次」 |

**為什麼**：延遲的深層連結是「一次性」的：安裝後第一次開啟帶使用者過去，用完就要自己記下來。歸因工具和 Install Referrer 都不會幫你標記「用過了」，不記的話，每次冷啟動都把使用者拉回同一頁。另外這個連結是行銷同事設定、來自外部的輸入，跳轉前一樣要交給 Router 做白名單與參數驗證（演練 3）。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L02_DeferredOnce.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L02_DeferredOnce.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform06-deeplink/L02_DeferredOnce.kt#bug
```

```lab-solution
android/solution/platform/platform06-deeplink/L02_DeferredOnce.kt#fixed
```

### 演練 3：行銷連結一點就下單，還能導去釣魚網站（第 5 題）

**檔案**：`L03_RouterWhitelist.swift`（iOS）、`L03_RouterWhitelist.kt`（Android）

**情境**：Router 把外部連結轉成要開的畫面。官方網域是 `www.example.com`，只開放 `/ticker/{代號}`（行情頁）和 `/alert/{編號}`（價格提醒，要登入）。演練用 11 個連結測試 Router：3 個正常的，8 個是別人精心做的壞連結（假網域、`http`、帶金額的下單連結、提領連結、用 `%2F` 繞過路徑、代號夾帶特殊字元、`redirect` 參數指向別的網站、沒列出的路徑）。每個連結都有「應該得到的結果」，演練會逐一比對。

**怎麼寫**

1. **Bug 版**：「盡量處理」的寫法：不檢查 scheme 和 host；有 `redirect` 參數就直接當成要開的網址；只看路徑第一段，`ticker`／`alert`／`order`／`withdraw` 各回傳對應的畫面（`order` 的 `action=buy` 會自動送出），其他看不懂的連結就回首頁。
2. **修正版**：白名單：只接受 `https` 加上 `www.example.com`；路徑解碼後只接受根路徑、`/ticker/{代號}`、`/alert/{編號}`，代號與編號用正規表示式限制字元；query 完全不讀；其他一律無效。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「共 11 個連結，處理錯誤 8 個」：帶金額的下單連結變成 `order(btcjpy, 10, 自動送出: true)`，提領連結變成 `withdraw(abc, 1)`，`redirect` 變成 `web(https://evil.example.org/login)` |
| 修正版 | 「共 11 個連結，處理錯誤 0 個」，壞連結全部是 `invalid` |

**為什麼**：連結可以從推播、簡訊、網頁、別的 App 進來，內容都是別人寫的。「盡量處理」的 Router 等於讓外部的人決定 App 要做什麼。白名單的原則是：連結只負責「開到某個畫面」，不能直接下單、提領，也不能指定要開哪個網址（open redirect）；路徑要先解碼再驗證，才擋得住 `%2F` 這種繞過手法。Android 的 `Activity` 為了接連結必須設成 `exported`，其他 App 也能直接呼叫它，所以 `Intent` 的 extras 也要同樣驗證。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L03_RouterWhitelist.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L03_RouterWhitelist.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform06-deeplink/L03_RouterWhitelist.kt#bug
```

```lab-solution
android/solution/platform/platform06-deeplink/L03_RouterWhitelist.kt#fixed
```

### 演練 4：點推播登入後卻停在首頁（第 5 題）

**檔案**：`L04_ResumeAfterLogin.swift`（iOS）、`L04_ResumeAfterLogin.kt`（Android）

**情境**：價格提醒的連結要登入才能看。未登入的使用者點了推播，Router 要求先登入。劇本：使用者點推播 → 看到登入畫面 → 登入成功；接著他登出，過幾天再正常登入一次（沒有連結）。

**怎麼寫**

1. **Bug 版**：未登入的提醒連結只顯示登入畫面，沒有記下原本的連結；`onLoginSuccess` 回到首頁。
2. **修正版**：未登入時把連結存進 `pendingLink`；`onLoginSuccess` 先回首頁，有 `pendingLink` 就清成空值，再用同一個 Router 處理它。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「未登入點到推播：畫面是 登入畫面」，「登入成功後，畫面停在：首頁」 |
| 修正版 | 登入成功後停在「提醒 ab12cd34」，之後單純登入一次停在「首頁」 |

**為什麼**：需要登入的連結要「先暫存目標，登入完成再繼續」。暫存的目標只能用一次：取出來就清掉，否則使用者下次單純登入，又被帶到同一個提醒頁。敏感畫面（提領、下單確認）即使已經登入，也要再做一次驗證，例如生物辨識。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L04_ResumeAfterLogin.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L04_ResumeAfterLogin.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform06-deeplink/L04_ResumeAfterLogin.kt#bug
```

```lab-solution
android/solution/platform/platform06-deeplink/L04_ResumeAfterLogin.kt#fixed
```

### 演練 5：簽章被偷走，攻擊者原封不動重送也能登入（第 6 題）

**檔案**：`L05_SiweReplay.swift`（iOS）、`L05_SiweReplay.kt`（Android）

**情境**：使用者用錢包簽一段訊息，證明自己擁有某個錢包地址，伺服器驗證簽章後讓他登入。簽章曾經外洩，攻擊者拿到「同一份訊息和簽章」，原封不動送給伺服器。真實的錢包簽章是橢圓曲線簽章，伺服器只需要錢包地址就能驗證；演練用 HMAC 代替簽章，重點是「簽的訊息裡有什麼」。劇本：使用者簽名登入 → 攻擊者 1 分鐘後重放同一份簽章 → 另一份簽好但使用者還沒送出的訊息，10 分鐘後被攻擊者送出。

**怎麼寫**

1. **Bug 版**：伺服器每次都要使用者簽同一段固定的文字（`"Sign in to Example Exchange"`），簽章對得上就回傳成功。
2. **修正版**：伺服器每次發一個 SIWE 風格的訊息：網域、錢包地址、一次性的 `Nonce`、簽發時間 `Issued At`、到期時間 `Expiration Time`（5 分鐘）。驗證時依序檢查簽章、`nonce` 是不是伺服器發的、有沒有過期、`nonce` 有沒有用過；通過後把 `nonce` 記進已使用清單。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 三行結果都是「成功」，包含「攻擊者 1 分鐘後重放同一份簽章：成功」 |
| 修正版 | 使用者簽名登入：成功；重放：「拒絕：nonce 已經用過」；10 分鐘後送出：「拒絕：訊息已過期」 |

**為什麼**：固定的訊息每次簽出來的簽章都一樣，誰拿到簽章都能重放。SIWE（Sign-In with Ethereum）規定訊息要帶一次性的 `nonce` 與時間限制，伺服器收到簽章後讓 `nonce` 作廢，同一份簽章就只能用一次、過期也不能用。簽章的驗證一定要在伺服器做；deep link 只負責在 App 之間切換，簽章資料不放在連結裡。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L05_SiweReplay.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform06-DeepLink/L05_SiweReplay.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform06-deeplink/L05_SiweReplay.kt#bug
```

```lab-solution
android/solution/platform/platform06-deeplink/L05_SiweReplay.kt#fixed
```

第 1、2 題（自訂 scheme、Universal Link 與 App Link 的差別，以及驗證檔怎麼運作）要有真的網域和伺服器才驗證得了，沒有可以在專案裡執行的 bug，沒有演練。想親眼觀察，可以用這些指令：

- **iOS**：`curl -I https://<網域>/.well-known/apple-app-site-association` 看回應是不是 200、`application/json`、沒有重導向。在 Mac 上也可以用 `swcutil`（需要 `sudo`）：`sudo swcutil dl -d <網域>` 讓系統現在就下載該網域的 AASA，`sudo swcutil show` 看系統目前記錄的狀態，`sudo swcutil verify` 驗證 AASA 檔案（用法以 `swcutil` 內建說明為準）。專案已經註冊了自訂 scheme `mobilelabs`，`xcrun simctl openurl booted "mobilelabs://ticker/btcjpy"` 會把 Mobile Labs 叫到前景：同一個 scheme 任何 App 都能註冊，這就是自訂 scheme 沒有驗證的意思（演練的 App 本身不處理連結內容，只看得到 App 被打開）。
- **Android**：`adb shell pm get-app-links <package>` 看每個網域的驗證狀態，`adb shell pm verify-app-links --re-verify <package>` 要求系統重新驗證；`adb shell am start -W -a android.intent.action.VIEW -d "https://<網域>/ticker/btcjpy"` 可以模擬點一個連結，看系統是開 App 還是開瀏覽器。

## 常見陷阱

- **AASA 放在會重導向的位置，或用錯 Content-Type。** 驗證失敗卻沒有任何錯誤訊息。要用 `curl -I` 確認回應是 200、`application/json`，沒有重導向。
- **Android 的 `assetlinks.json` 只填上傳金鑰的指紋。** 使用 Play App Signing 時，上架版本是用「應用程式簽署金鑰」簽的，指紋要用那一把。
- **用自訂 scheme 傳授權碼或 token。** 其他 App 可以註冊同名的 scheme 截走。
- **各個畫面自己用字串比對處理連結，沒有白名單。** 規則散落各處，遲早會漏掉一個，要集中到一個 Router。
- **連結直接開到下單或提領頁，而且預填金額，甚至自動送出。** 連結的內容是別人寫的，必須由使用者自己確認。
- **登入後忘了恢復原本的目標。** 使用者點推播，登入後卻停在首頁，要在未登入時暫存目標。
- **行銷素材還在用舊的 `page.link`（Firebase Dynamic Links）。** 這項服務已於 2025 年 8 月停止，連結已經失效。
- **WalletConnect 簽的訊息固定不變。** 簽章可以被重放，要用帶 nonce 的 SIWE 格式。

## 面試官想聽到的

1. 能說出兩個平台的驗證機制（AASA 與 `assetlinks.json`）與常見失敗原因：AASA 的路徑與重導向、Android 的指紋要用 Play App Signing 簽署金鑰的。
2. 路由安全的做法：集中式 Router、白名單、參數驗證、登入與權限，以及連結不能直接觸發有副作用的操作。
3. 能解釋為什麼自訂 scheme 不安全（任何 App 都能註冊同名 scheme）。
4. 對 deferred deep link 的平台差異有認知：Android 的 Install Referrer API，iOS 靠歸因 SDK。
5. 知道錢包回跳的流程，以及簽名訊息為什麼要防重放。

> [!FIELD]
> 在一個社交 App 裡，我做過廣告投放的 Deep Link 行銷導流：使用者點廣告連結後，由歸因 SDK（例如 Airbridge，用來判斷使用者從哪個廣告來的工具）判斷來源，再把使用者帶到對應的畫面；也處理過第三方登入的回呼，以及 WalletConnect 這類加密貨幣錢包要跳到錢包 App 再回來的流程。
> 換成交易所 App，我會把這些做成通用的 Router：型別化的 Route、白名單加參數驗證、連結不能直接觸發下單或提領，錢包簽署使用 SIWE。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 已驗證的 https 連結 | Universal Links | App Links |
| 驗證檔 | `/.well-known/apple-app-site-association`（無副檔名、不能重導向） | `/.well-known/assetlinks.json`（填 Play App Signing 簽署金鑰的指紋） |
| App 端宣告 | Associated Domains：`applinks:<網域>` | `<intent-filter android:autoVerify="true">` |
| 驗證時機與除錯 | 安裝與更新時由 Apple CDN 抓取；`?mode=developer` 繞過快取 | 安裝時驗證；`adb shell pm get-app-links` |
| 接收連結 | `onOpenURL`、`onContinueUserActivity`；UIKit 用 `scene(_:continue:)`，冷啟動看 `connectionOptions` | `intent.data`；已開著時看 `onNewIntent` |
| 沒安裝 App 時帶參數 | 沒有官方機制，靠歸因 SDK | Play Install Referrer API |
| 偵測錢包是否安裝 | `LSApplicationQueriesSchemes` | `<queries>`（Android 11 起） |
| 自訂 scheme | 不唯一，不傳敏感資料 | 同左 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 深層連結 | deep link | ディープリンク |
| 自訂 URL scheme | custom URL scheme | カスタム URL スキーム |
| 延遲的深層連結 | deferred deep link | ディファードディープリンク |
| 開放式重新導向 | open redirect | オープンリダイレクト |
| 重放攻擊 | replay attack | リプレイ攻撃 |
| 隨機值（只能用一次） | nonce | ノンス |
| 歸因 | attribution | アトリビューション |
