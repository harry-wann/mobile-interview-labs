---
id: exchange-connection-resilience
title: 連線韌性：斷線怎麼重連、等多久再連、背景處理與「資料已過期」的畫面
summary: 手機的連線一定會斷：Wi-Fi 切到行動網路、進電梯、App 進背景。這個單元用狀態機說明重連怎麼設計、每次要等多久（指數退避加隨機誤差）、系統的網路狀態可以信到什麼程度，以及斷線時畫面要怎麼誠實告訴使用者「這個價格是舊的」。
priority: P0
day: 3
tags: [reconnect, backoff, jitter, reachability, nwpathmonitor, connectivitymanager, stale-data]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 為什麼重連要寫成狀態機？狀態和事件有哪些？
2. 斷線後要等多久再重連？指數退避和 jitter 是什麼？
3. 什麼時候才可以把失敗次數歸零？
4. 系統說「有網路」就真的連得上嗎？什麼是半開連線？
5. App 進背景、回前景，以及重連成功之後，要依什麼順序恢復？
6. 斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？

這些設計適用於這套交易所 API 的公開與私有 WebSocket（讓伺服器可以持續主動推送資料給 App 的連線，協定細節見 WebSocket 協定單元）。交易所 App 比一般 App 更需要注意連線：行情停住時，使用者可能還在看著舊價格下單。

### 1. 為什麼重連要寫成狀態機？狀態和事件有哪些？

**重連邏輯要寫成狀態機（state machine）：把「連線」拆成明確的幾個狀態，再規定每個狀態遇到什麼事件，會換到哪個狀態、要去做什麼。這樣重連邏輯就能用一串事件做單元測試，不用真的連網路。**

沒有狀態機的重連，通常是在 `onClose` 回呼裡直接呼叫 `connect()`，再加上幾個 `isConnecting`、`isReconnecting` 的布林旗標。旗標一多，就會出現「網路消失了還在重試」「進背景還在排計時器」「兩個畫面同時各自重連」這類沒人說得清楚的狀況。

狀態機把這些情況全部列成表格。以下是常見的狀態和轉移：

```text
idle ──start──▶ connecting(n) ──opened──▶ connected(n, since)
                   ▲   │closed                │closed
        retryTimer │   ▼                       ▼
                 waiting(n, retryAt) ◀─────────┘
   networkLost: connecting/connected/waiting ──▶ offline ──networkAvailable──▶ connecting(0)
   background:  任何非 idle ──▶ suspended ──foreground──▶ connecting(0)
```

圖的讀法：`n` 是目前連續失敗了幾次；`since` 是連上的時間；`retryAt` 是預定重試的時間。幾個狀態的意思：

- `idle`：還沒開始。`connecting`：正在連。`connected`：已連上。
- `waiting`：連線失敗，等一段時間（退避）再重試。
- `offline`：手機沒有網路，不要再重試。
- `suspended`：App 在背景，主動斷線。

事件有：`start`、`opened`（連上了）、`closed`（連線關了或失敗了）、`retryTimerFired`（等待時間到了）、`networkLost`、`networkAvailable`、`enteredBackground`、`enteredForeground`。

另一個重要的設計：狀態機本身**不直接做事**。它收到事件後，回傳「下一個狀態」加上一串 effect（效果，意思是轉移時要去做的事，例如連線、排計時器、關閉 socket），只用資料描述，由外面的程式去執行。這樣測試時只要檢查「收到這串事件後，狀態是什麼、要做哪些事」，不需要真的連線，也不需要真的等時間：

```swift
// 連線狀態機的最小版本：只放最重要的幾個轉移
enum ConnState: Equatable {
    case idle
    // attempt：已經連續失敗幾次
    case connecting(attempt: Int)
    // sinceMs：連上的時間
    case connected(attempt: Int, sinceMs: Int64)
    // retryAtMs：預定重試的時間
    case waiting(attempt: Int, retryAtMs: Int64)
    // 手機沒有網路
    case offline
}

enum ConnEvent {
    case start
    case opened(nowMs: Int64)
    case closed(nowMs: Int64)
    case retryTimerFired
    case networkLost
    case networkAvailable
}

// 只描述「要去做什麼」，不真的去做
enum ConnEffect: Equatable {
    case connect
    case scheduleRetry(delayMs: Int64)
    case cancelRetry
    case closeSocket
}

// 見下一個問題的退避公式
func delayMs(attempt: Int, random: Double) -> Int64 {
    let ceiling = min(Int64(8000), Int64(500) << Int64(min(attempt, 10)))
    return Int64(Double(ceiling) * random)
}

func reduce(
    _ state: ConnState,
    _ event: ConnEvent,
    random: Double
) -> (ConnState, [ConnEffect]) {
    switch (state, event) {
    case (.idle, .start):
        return (.connecting(attempt: 0), [.connect])
    case (.connecting(let n), .opened(let now)):
        return (.connected(attempt: n, sinceMs: now), [])
    case (.connecting(let n), .closed(let now)):
        let delay = delayMs(attempt: n + 1, random: random)
        return (
            .waiting(attempt: n + 1, retryAtMs: now + delay),
            [.scheduleRetry(delayMs: delay)]
        )
    case (.waiting(let n, _), .retryTimerFired):
        return (.connecting(attempt: n), [.connect])
    case (_, .networkLost):
        return (.offline, [.cancelRetry, .closeSocket])
    case (.offline, .networkAvailable):
        return (.connecting(attempt: 0), [.connect])
    default:
        // 其他組合一律忽略
        return (state, [])
    }
}

// 用一串事件測試，不需要真的連網路
var state = ConnState.idle
for event in [
    ConnEvent.start,
    .closed(nowMs: 100),
    .retryTimerFired,
    .networkLost
] {
    let (next, effects) = reduce(state, event, random: 0.5)
    print("\(event) → \(next)，要做的事：\(effects.map { "\($0)" })")
    state = next
}

// 輸出：
// start → connecting(attempt: 0)，要做的事：["connect"]
// closed(nowMs: 100) → waiting(attempt: 1, retryAtMs: 600)，要做的事：["scheduleRetry(delayMs: 500)"]
// retryTimerFired → connecting(attempt: 1)，要做的事：["connect"]
// networkLost → offline，要做的事：["cancelRetry", "closeSocket"]
```

這個單元的練習題（code 題）就是把完整的狀態機和退避計算寫出來，用注入的亂數和事件帶入的時間，不碰真正的網路和計時器。

另外一個常被問到的原則：**訂閱要有單一的來源**，也就是「目前需要哪些 topic」只記錄在一個地方，重連時依這份清單重新訂閱，不要每個畫面各自重連、各自重新訂閱，否則一次斷線會變成多次連線的「重連風暴」。

> [!TIP]
> 面試時可以這樣說：「我會把連線寫成狀態機：idle、connecting、connected、waiting、offline、suspended，用事件驅動。轉移是純函式，連線和排計時器這些動作只回傳成 effect 描述，由外面去執行，所以可以用一串事件做單元測試，不用真的連網路。」

### 2. 斷線後要等多久再重連？指數退避和 jitter 是什麼？

**指數退避（exponential backoff）是連線失敗後，每次等待的時間都加倍，不要每秒狂重試；jitter（隨機誤差）是在等待時間上加隨機，讓每支手機重連的時間錯開，伺服器才不會在同一個時間點被大量連線打爆。**

公式（常見的 full jitter 寫法）：

```text
延遲 = 隨機(0, min(cap, base × 2^attempt))
```

`base` 是起始等待時間，`cap` 是等待時間的上限，`attempt` 是目前連續失敗的次數。以 base = 500 毫秒、cap = 8 秒為例，每次失敗後「上限」依序是：

| attempt | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| 上限（毫秒） | 500 | 1000 | 2000 | 4000 | 8000 | 8000（被 cap 擋住） |

實際等多久，是在 0 到上限之間隨機挑一個值。

**為什麼要有 jitter？** 假設交易所的伺服器重啟，10,000 支手機同時斷線。如果大家都用「等 1 秒、再等 2 秒、再等 4 秒」重連，那在第 1 秒整，10,000 支手機一起連上來；伺服器撐過去之後，第 3 秒整又是 10,000 支。這種大量連線同時湧入的現象叫 thundering herd（驚群效應）。加了 jitter 之後，attempt 1 的上限是 1000 毫秒，10,000 支手機的重連時間平均分散在 1 秒內，每毫秒約 10 支，伺服器看到的是平滑的流量。

這套交易所的私有 WebSocket 還有連線限制（API（Application Programming Interface，程式介面）文件寫的是：每個 API key 最多 10 條連線、每個 IP（網路位址）每秒最多 100 條新連線、連線與請求合計每秒 50 次），大家同時重連，更容易撞到這些限制而收到 `429`（Too Many Requests，請求太多）。遇到明確的限流錯誤（`429`、`too.many.connection`），要用比較長的 base，不要和一般斷線用同一套節奏。

```swift
// full jitter：延遲 = 0 到「這次的上限」之間的隨機值
// 上限 = min(cap, base × 2^attempt)
func delayMs(
    attempt: Int,
    baseMs: Int64 = 500,
    capMs: Int64 = 8000,
    random: Double
) -> Int64 {
    var ceiling = baseMs
    var i = 0
    // 一次翻一倍，到 cap 就停：attempt 再大也不會溢位
    while i < attempt && ceiling < capMs {
        ceiling = min(ceiling * 2, capMs)
        i += 1
    }
    return Int64(Double(min(ceiling, capMs)) * random)
}

// 固定亂數 0.5，方便看出上限的成長
print((0..<6).map { delayMs(attempt: $0, random: 0.5) })
// 輸出：[250, 500, 1000, 2000, 4000, 4000]

// 實際使用時，亂數每次都不同
let delay = delayMs(attempt: 3, random: Double.random(in: 0..<1))
print(delay >= 0 && delay < 4000)
// 輸出：true（attempt 3 的上限是 4000 毫秒）
```

```kotlin
fun delayMs(
    attempt: Int,
    baseMs: Long = 500,
    capMs: Long = 8000,
    random: Double,
): Long {
    var ceiling = baseMs
    var i = 0
    // 一次翻一倍，到 cap 就停：attempt 再大也不會溢位
    while (i < attempt && ceiling < capMs) {
        ceiling = minOf(ceiling * 2, capMs)
        i++
    }
    return (minOf(ceiling, capMs) * random).toLong()
}

fun main() {
    println((0 until 6).map { delayMs(it, random = 0.5) })
    // 輸出：[250, 500, 1000, 2000, 4000, 4000]
}
```

實作時有三個重點：

- **亂數要從外面傳進來**（像上面的 `random` 參數），測試時才能固定成 0.5，得到可預測的結果。
- **先封頂再計算**：如果直接算 `base << attempt`，attempt 很大時會溢位（數字超過型別能表示的範圍，結果變成負數或 0）。用迴圈一次翻一倍、到 cap 就停，最安全。
- 就算有上限，持續重試的過程中，畫面也要讓使用者看得到「連線中」的狀態，不能默默一直試。

> [!TIP]
> 面試時可以這樣說：「指數退避只是把間隔拉長，如果大家的起點一樣，還是會同時重連。jitter 讓每支手機的等待時間分散開，伺服器看到的是平滑的流量。我用 full jitter：延遲在 0 到 min(cap, base × 2 的 attempt 次方) 之間隨機，亂數從外面注入方便測試，遇到 429 或連線數上限就用更長的 base。」

### 3. 什麼時候才可以把失敗次數歸零？

**不要在連上（`opened`）的當下就歸零，要等連線穩定維持一段時間（例如 10 秒以上）才算成功。** 連上之後馬上被伺服器踢掉（例如被限流），失敗次數要繼續累加。

假設伺服器在高峰期限制新連線，你的 App 連得上，但連上 0.3 秒就被踢掉：

- **一連上就歸零**：連上、被踢、attempt 回到 0，下次只等 0 到 500 毫秒，馬上再連、再被踢…變成緊密迴圈（沒有間隔地連、被踢、再連），耗電又加重伺服器的負擔。
- **穩定 10 秒才歸零**：連上 0.3 秒被踢，attempt 繼續累加成 1、2、3…，等待時間越來越長，給伺服器喘息的時間。

另外兩個會重置次數的時機，因為是「現在可以試」的強烈訊號：

- **網路恢復**（從 `offline` 回來）：立刻重試，次數歸零。如果還在退避等待中，也要取消等待，立刻重試。
- **App 回到前景**：當作一條全新的連線，次數歸零。

相反地，網路消失（`offline`）的時候，要**停掉重試的計時器**，等網路恢復的事件再連。沒有網路時還一直重試，只會耗電，也沒有意義。

> [!TIP]
> 面試時可以這樣說：「失敗次數我不會在連上的當下歸零，而是連線穩定超過一段時間才歸零，不然被限流時連上又被踢，會變成緊密迴圈。網路恢復和回前景是明確的重試訊號，這兩種情況才會立即重連並歸零。」

### 4. 系統說「有網路」就真的連得上嗎？什麼是半開連線？

**不一定。系統的網路狀態 API 只代表「手機認為有一條路」，不保證連得上你的伺服器，所以它只能當提示：用來「馬上重試」與「別浪費重試」，不能用來判斷資料是不是新的。**

| | iOS | Android |
|---|---|---|
| API | `NWPathMonitor`（Network framework）：`path.status == .satisfied`、`isExpensive`、`isConstrained`（低數據模式）、`usesInterfaceType` | `ConnectivityManager.registerDefaultNetworkCallback`（API 24 起）：`onAvailable`／`onLost`／`onCapabilitiesChanged` |
| 「可用」不等於「能上網」 | `satisfied` 只代表有路徑，captive portal 或後端不通時仍可能失敗 | 要看 `NET_CAPABILITY_VALIDATED`（系統驗證過真的連得到網際網路），不能只看 `NET_CAPABILITY_INTERNET` |

captive portal 是咖啡店、機場這類 Wi-Fi 要先在網頁登入才能上網的機制：手機顯示「已連上 Wi-Fi」，其實連不到任何網站。如果在每次請求前先用 `NWPathMonitor` 檢查，不通就不送請求，使用者在這種環境下就完全無法操作。正確用法是：offline 時暫停重試，網路恢復時立即重試，其他一律以實際連線、心跳與資料的結果為準。

```swift
import Network

let monitor = NWPathMonitor()
monitor.pathUpdateHandler = { path in
    if path.status == .satisfied {
        // 系統說有路可走：提示可以立刻重試。但不代表一定連得上伺服器
        print(
            "有網路路徑，Wi-Fi：\(path.usesInterfaceType(.wifi))，行動網路：\(path.usesInterfaceType(.cellular))"
        )
    } else {
        // 沒有網路路徑：暫停重試計時器，等恢復的通知
        print("沒有網路")
    }
}

// 在背景佇列收通知
monitor.start(queue: DispatchQueue(label: "net.monitor"))
```

```kotlin
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

// 需要 ACCESS_NETWORK_STATE 權限；registerDefaultNetworkCallback 從 API 24
// 開始提供
fun watchNetwork(
    context: Context,
    onUsable: (Boolean) -> Unit,
): ConnectivityManager.NetworkCallback {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities,
        ) {
            // 系統確認過真的連得到網際網路，才當成可用（captive portal
            // 之類的情況不會通過）
            onUsable(
                caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                ),
            )
        }

        // 網路消失：暫停重試
        override fun onLost(network: Network) = onUsable(false)
    }
    cm.registerDefaultNetworkCallback(callback)
    // 不再需要時，呼叫 cm.unregisterNetworkCallback(callback)
    return callback
}
```

**半開連線（half-open）是更難抓的問題。** 使用者從 Wi-Fi 走出門換成行動網路，或是進了電梯，原本的 TCP（Transmission Control Protocol，網路上傳送資料的基本連線方式）連線在 App 這端看起來還是開著的，但對方其實已經收不到你、你也收不到資料。系統不會馬上通知你，也不會觸發 `onClose`／`didClose` 回呼，畫面就停在最後一個價格。

所以要有應用層的監看：這套交易所的伺服器約每 5 秒送一次 ping（定時確認「連線還活著」的訊息，App 要回 pong），連續兩次沒回應就斷線（見 WebSocket 協定單元）。App 這端也可以反過來監看：**超過約 15 秒（三次 ping 的時間）沒有收到任何訊息，就主動關閉連線並重連**，同時監聽網路路徑的變化，變化時立刻重連。這個「15 秒」是依伺服器 ping 的間隔推出來的建議值，要依實際測試調整。監看的計時請用單調時鐘（見第 6 個問題），放在 socket 的背景路徑，不要放在 UI（使用者介面）的主執行緒。

> [!TIP]
> 面試時可以這樣說：「NWPathMonitor 的 satisfied 或 Android 的 onAvailable 只代表系統有路，不代表連得上伺服器，Android 我會看 NET_CAPABILITY_VALIDATED。網路狀態只當提示。半開連線系統不會通知，所以我會用應用層監看：伺服器每 5 秒一次 ping，超過約 15 秒沒收到任何訊息就主動重連。」

### 5. App 進背景、回前景，以及重連成功之後，要依什麼順序恢復？

**App 進背景時主動斷線並取消計時器；回前景時當成一條新連線，依序做：標示資料過期、建立連線（私有頻道先重新認證）、重新訂閱所有 topic、對狀態型資料取快照補洞，最後才清除過期標示。**

為什麼進背景要主動斷線？iOS 的 App 進背景後很快會被系統暫停，socket 會被暫停或關閉，不是你能控制的；Android 則有 Doze（省電模式，螢幕關閉一陣子後會限制背景網路）。與其讓連線在背景默默死掉，再讓重連邏輯在背景亂跑，不如進背景時就主動斷線，回前景再重新連。

```swift
import UIKit

final class AppLifecycleObserver {
    private var tokens: [NSObjectProtocol] = []

    init(
        onForeground: @escaping () -> Void,
        onBackground: @escaping () -> Void
    ) {
        let center = NotificationCenter.default
        // 進背景：馬上主動斷線，不要等系統把 socket 關掉
        tokens.append(
            center.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { _ in
                onBackground()
            }
        )
        // 回前景：重新連線、重新訂閱、補資料
        tokens.append(
            center.addObserver(
                forName: UIApplication.willEnterForegroundNotification,
                object: nil,
                queue: .main
            ) { _ in
                onForeground()
            }
        )
    }

    deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }
}
```

```kotlin
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

fun observeAppLifecycle(onForeground: () -> Unit, onBackground: () -> Unit) {
    ProcessLifecycleOwner.get()
        .lifecycle.addObserver(object : DefaultLifecycleObserver {
        // 整個 App 回到前景
        override fun onStart(owner: LifecycleOwner) = onForeground()
        // 整個 App 進背景
        override fun onStop(owner: LifecycleOwner) = onBackground()
    })
}
```

重連成功之後，順序很重要：

1. **標示資料為過期**（畫面顯示「更新中」），避免使用者在恢復前用舊價格操作。
2. **建立連線**；私有頻道要先重新送 `auth`，認證成功才能訂閱。
3. **重新訂閱所有需要的 topic**，依「單一來源」的 topic 清單（帶新的 `id`）。
4. **對狀態型資料取快照補洞**：訂單簿、K 線最後一根、餘額這類資料，是「一份完整資料加上後續的變化」疊出來的。斷線期間漏掉的變化，沒辦法補回來，所以要先把重連後收到的更新暫存起來，抓一份完整的最新資料（snapshot，快照），再套用暫存的更新（細節見訂單簿單元）。
5. **全部恢復後**才清除過期標示，並恢復下單按鈕。

最後一步才解除保護，是因為價格和下單的可用性，要等資料真的恢復才算。

> [!TIP]
> 面試時可以這樣說：「進背景我會主動斷線並取消計時器，回前景當成新連線。恢復的順序是：先標示過期，連線與認證，重新訂閱，對板這類狀態型資料先暫存更新、取快照、再套用，最後才清除過期標示。訂閱清單只有一個來源，不讓各畫面各自重連。」

### 6. 斷線時畫面要怎麼處理，才不會讓使用者拿舊價格下單？

**斷線時要老實告訴使用者「這個價格是舊的」，並且阻止或要求二次確認以該價格下單，市價單（成行）尤其危險。**

做法：

- **追蹤「最後收到有效資料的時間」**，超過門檻（例如 5 到 10 秒）就把價格變灰，顯示「5 秒前更新」，並阻止或二次確認下單。
- **用單調時鐘計時**。單調時鐘（monotonic clock）是只會往前走的計時器，不受使用者手動修改系統時間影響。如果用一般的系統時間（`Date()`），使用者把手機時間調早一小時，「距離上次收到資料過了幾秒」就算錯了。iOS 用 `ProcessInfo.processInfo.systemUptime`，Android 用 `SystemClock.elapsedRealtime()`。
- **不要一斷線就閃橫幅**：先等一小段寬限時間（例如 2 到 3 秒）再顯示，避免網路短暫抖動讓畫面一直閃爍。這種等一下再決定的做法叫去抖動（debounce）。
- **重連成功後告訴使用者**：顯示「已恢復」，並更新資料，不要悄悄換掉價格，讓使用者以為價格是一直在跳動。
- **記錄指標**：重連次數、恢復花了多久、過期持續了多久、最後一次斷線的原因（不含個資），這些資料能幫助找出連線問題。

```swift
import Foundation

struct FreshnessTracker {
    // 最後一次收到有效資料的時間（單調時鐘，單位：秒）
    private(set) var lastDataAt: TimeInterval?
    // 超過 5 秒沒有新資料，就當成過期
    let staleAfter: TimeInterval = 5

    mutating func didReceiveData(now: TimeInterval) { lastDataAt = now }

    func isStale(now: TimeInterval) -> Bool {
        // 從來沒收到過，也算過期
        guard let last = lastDataAt else { return true }
        return now - last >= staleAfter
    }
}

var tracker = FreshnessTracker()
// now 傳入 ProcessInfo.processInfo.systemUptime：只會往前走，
// 使用者改系統時間也不受影響
tracker.didReceiveData(now: 100)
print(tracker.isStale(now: 103), tracker.isStale(now: 106))
// 輸出：false true
```

注意：`systemUptime` 在裝置睡眠時不會增加。不過 App 進背景時我們已經主動斷線，回前景一律先標示過期（見上一個問題的順序），所以不需要靠這個時鐘去跨越背景的時間。

> [!TIP]
> 面試時可以這樣說：「斷線時我會追蹤最後收到資料的時間，用單調時鐘算，超過幾秒就把價格變灰，顯示幾秒前更新，並阻止或二次確認以這個價格下單，市價單尤其危險。顯示橫幅前先等 2 到 3 秒的寬限時間，避免短暫抖動讓畫面閃爍。恢復之後要明確告訴使用者已恢復。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange04Reconnect**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange04Reconnect**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange04-Reconnect/`，解答在 `labs/ios/Solution/Exchange/Exchange04-Reconnect/`；Android 你寫在 `labs/android/practice/exchange/exchange04-reconnect/`，解答在 `labs/android/solution/exchange/exchange04-reconnect/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路，也不用真的等**：這個單元的演練都是重連邏輯，用虛擬的時間（毫秒數當參數傳進來）和假的連線、假伺服器，亂數固定成 0.5，每次執行的結果都一樣。

### 演練 1：App 在背景，連線卻自己又連回來了（第 1 題）

**檔案**：`L01_IllegalTransition.swift`（iOS）、`L01_IllegalTransition.kt`（Android）

**情境**：連線狀態機有 idle、connecting、connected、waiting（等一段時間再重連）、offline、suspended（App 在背景，主動斷線）六個狀態。轉移是純函式 `reduce(狀態, 事件)`，回傳新狀態和一串要做的事（effect，只用資料描述，由外面去執行）。演練依序送進這些事件：開始連線、連上了、連線意外斷了、等待結束、又連上了；然後 App 進背景（狀態機要求關掉 socket、取消計時器）、socket 關閉的通知（我們自己關的，通知會晚一點才到）、先前排的計時器響了、App 回前景。

**怎麼寫**

1. **Bug 版**：`reduce` 先呼叫已經寫好的 `commonReduce`；剩下的 `closed` 在任何狀態都當成連線失敗（回傳 `failed(...)`，排重試），`retryTimerFired` 在任何狀態都連線。
2. **修正版**：`closed` 只有在 connecting 或 connected 時才算連線失敗；`retryTimerFired` 只有在 waiting 時才連線；其他組合一律忽略，狀態不變、不做事。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 進背景之後，`closed` 讓狀態變成 waiting 並排了重試計時器，`retryTimerFired` 又要求連線：「App 在背景時，狀態機要求連線或排計時器 2 次；最後狀態：connecting(attempt: 1)」，回前景時狀態早就不是 suspended，沒有重新連線 |
| 修正版 | 進背景之後的 `closed`、`retryTimerFired` 都被忽略：「App 在背景時，狀態機要求連線或排計時器 0 次；最後狀態：connecting(attempt: 0)」 |

**為什麼**：沒有狀態機的重連，通常是在 `onClose` 裡直接呼叫 `connect()`，再加幾個布林旗標。狀態機把「每個狀態遇到什麼事件要做什麼」列成表格，不合理的組合（背景中收到自己關 socket 的通知、不是等待中卻收到計時器）就能明確地忽略。這個演練的 Bug 版就是最常見的寫法：用 `default` 或萬用的分支一律當成「連線失敗」。因為轉移是純函式、effect 只是資料，所以用一串事件就能寫單元測試，不用真的連網路。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L01_IllegalTransition.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L01_IllegalTransition.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L01_IllegalTransition.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L01_IllegalTransition.kt#fixed
```

### 演練 2：維護後重連的等待時間變成負數（第 2 題）

**檔案**：`L02_BackoffOverflow.swift`（iOS）、`L02_BackoffOverflow.kt`（Android）

**情境**：等待時間 = 隨機(0, min(上限, 起始 × 2 的 attempt 次方))，起始 500 毫秒、上限 8 秒，亂數固定成 0.5，所以 attempt 4 以後都應該是 4000 毫秒。伺服器維護 10 分鐘，每次最多等 8 秒，重試了六十幾次還是失敗，attempt 一路加到很大。演練算 9 種 attempt（0、4、10、40、55、58、62、64、100），和正確答案比對。為什麼要加 jitter，Platform05 的演練 6 已經練過，這題看的是 attempt 變大之後的溢位。

**怎麼寫**

1. **Bug 版**：用位移直接算上限：`min(capMs, baseMs << Int64(attempt))`（Android 是 `minOf(capMs, baseMs shl attempt)`），再乘以亂數。
2. **修正版**：先封頂再計算：`ceiling` 從 `baseMs` 開始，用 `while` 迴圈一次翻一倍（用 `min` 擋在 `capMs` 以內），最多翻 attempt 次，到 `capMs` 就停。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | attempt 55、58 的等待時間是很大的負數，62 是 0；iOS 的 64、100 也是 0（「共 9 種 attempt，等待時間算錯的有 5 種」）；Android 的 64 回到 250（`shl` 把位移數字對 64 取餘數），100 剛好碰對，所以是 4 種 |
| 修正版 | attempt 4 以後都是 4000 ms，「等待時間算錯的有 0 種」 |

**為什麼**：500 左移 55 位以後，結果超出 64 位元整數能表示的範圍：位元被擠進正負號位變成負數，再往上就全部被丟掉變成 0，`min` 又把負數留了下來。等待時間是 0 或負數，計時器馬上響，等於沒有退避，維護中的伺服器會被所有手機狂打。就算有上限，也要先封頂再計算：用迴圈一次翻一倍、到 cap 就停，attempt 再大也不會溢位。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L02_BackoffOverflow.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L02_BackoffOverflow.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L02_BackoffOverflow.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L02_BackoffOverflow.kt#fixed
```

### 演練 3：伺服器連上就踢人，手機每秒重連一次（第 3 題）

**檔案**：`L03_Flapping.swift`（iOS）、`L03_Flapping.kt`（Android）

**情境**：交易高峰期，伺服器限制新連線：App 連得上，但連上 0.3 秒就被踢掉。重連的等待時間用正確的指數退避（起始 500 毫秒、上限 8 秒，亂數固定 0.5）。演練用虛擬的時間跑 60 秒：前 5 次連上都在 0.3 秒後被踢；第 6 次連上後伺服器恢復正常，連線穩定維持了 20 秒才斷；之後又開始每次連上 0.3 秒就被踢。

**怎麼寫**

1. **Bug 版**：`opened`（連上了）一進來就把失敗次數 `attempt` 歸零。
2. **修正版**：`opened` 只記下連上的時間；`closed`（斷線了）時，如果這條連線已經穩定超過 10 秒（10,000 毫秒）才把 `attempt` 歸零，然後 `attempt` 加 1，算出下一次要等多久。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 等待時間一直是 500 毫秒（「前 5 次的等待時間：500、500、500、500、500」），「60 秒內連線了 51 次」 |
| 修正版 | 等待時間 500、1000、2000、4000、4000，穩定 20 秒之後回到 500，「60 秒內連線了 14 次」 |

**為什麼**：連上又馬上被踢，不能算成功。一連上就歸零，等於每次都從最短的等待時間重來，變成緊密的連線迴圈（flapping，連線抖動），耗電又加重伺服器的負擔。要等連線穩定一段時間才歸零；穩定了 20 秒的那一次斷線，才是新的開始，等待時間要回到最短。網路恢復和 App 回到前景是「現在可以試」的強烈訊號，這兩種情況才會立即重連並歸零。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L03_Flapping.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L03_Flapping.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L03_Flapping.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L03_Flapping.kt#fixed
```

### 演練 4：走出電梯，行情停住了卻沒有重連（第 4 題）

**檔案**：`L04_HalfOpen.swift`（iOS）、`L04_HalfOpen.kt`（Android）

**情境**：半開連線：連線在 App 這端看起來還開著，但對方其實已經收不到你、你也收不到資料，系統不會通知，也不會觸發 `onClosed`／`didClose`。App 每 5 秒主動送一次 ping（寫進本機的傳送緩衝就算「送出成功」，半開的連線也一樣）。演練用虛擬的時間一秒一秒跑 60 秒：前 10 秒（第 0 到 9 秒）每秒收到一則行情，之後什麼都收不到。應用層的監看：超過 15 秒沒有「活動」，就判定連線已死、主動重連。

**怎麼寫**

1. **Bug 版**：`didSend`（送出）和 `didReceive`（收到）都把 `lastActivityMs` 更新成現在；`isDead` = 現在減 `lastActivityMs` 超過 15,000 毫秒。
2. **修正版**：只有 `didReceive` 更新 `lastActivityMs`，`didSend` 什麼都不做；`isDead` 一樣。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 60 秒內都沒有偵測到：「最後一次活動是第 60 秒」，畫面停在第 9 秒的價格 |
| 修正版 | 「第 25 秒：超過 15 秒沒有收到任何訊息，判定連線已死，主動關閉並重連」，「最後一次活動是第 9 秒」 |

**為什麼**：半開連線的 `send` 不會失敗，資料只是寫進本機的緩衝，所以「送出成功」不能證明對方還活著。監看只能看「有沒有收到伺服器的訊息」。這套交易所的伺服器約每 5 秒送一次 ping，15 秒是三次 ping 的時間（建議值，要依實際測試調整）。`NWPathMonitor` 的 `satisfied` 與 Android 的 `onAvailable` 只代表系統有路，不能證明連得上伺服器。計時請用單調時鐘，放在 socket 的背景路徑，不要放在主執行緒。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L04_HalfOpen.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L04_HalfOpen.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L04_HalfOpen.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L04_HalfOpen.kt#fixed
```

### 演練 5：斷線重連後，價格倒退回舊的（第 5 題）

**檔案**：`L05_RecoveryOrder.swift`（iOS）、`L05_RecoveryOrder.kt`（Android）

**情境**：重連後要重新訂閱，並對狀態型資料取一份完整的最新資料（快照）補洞。每則資料都有序號 `seq`（BBO 的 `seqId`）。畫面原本停在 seq 95、價格 9,999,500。重新訂閱之後，快照的請求還在路上，推送先到了：seq 100、101、102（價格各增加 100）；最後快照才回來，它是在 seq 100 的時候產生的（價格 10,000,000）。訂單簿的快照與增量，會在訂單簿單元用同樣的做法練習。

**怎麼寫**

1. **Bug 版**：`onPush` 序號比現在新就直接換掉畫面的 seq 和價格；`onSnapshot` 把 seq 和價格整份蓋上去。
2. **修正版**：快照還沒到之前，`onPush` 先把推送暫存起來；`onSnapshot` 先套用快照，再把暫存的推送依序號由小到大套用，序號比快照新的才換；快照到了之後的推送才直接套用。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面先到 seq 102，快照回來後又被蓋回 seq 100：「最後畫面：seq 100，價格 10000000」，比伺服器的最新資料舊 |
| 修正版 | 快照之前的推送先暫存，「最後畫面：seq 102，價格 10000200」 |

**為什麼**：快照是在某個序號當下產生的，回來時推送可能已經超過它了。直接蓋上去，畫面就倒退，要等下一則推送才會對，而交易所的價格這時可能已經在跳。順序是：先暫存，再套用快照，最後套用比快照新的暫存推送。完整的恢復順序是：標示資料過期、建立連線（私有頻道先重新認證）、重新訂閱、取快照補洞，最後才清除過期標示。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L05_RecoveryOrder.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L05_RecoveryOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L05_RecoveryOrder.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L05_RecoveryOrder.kt#fixed
```

### 演練 6：手機時間調早一小時，舊價格被當成最新的（第 6 題）

**檔案**：`L06_MonotonicClock.swift`（iOS）、`L06_MonotonicClock.kt`（Android）

**情境**：App 記錄「最後收到資料的時間」，超過 5 秒沒有新資料，就把價格變灰，並阻止用這個價格下市價單。演練用一個假的時鐘，同時有系統時間（使用者可以改）和單調時鐘（只會往前走）。兩種情況：① 收到資料後連線斷了，過了 20 秒，使用者把系統時間調早一小時；② 剛收到新資料，使用者把系統時間調晚一小時。Platform05 的演練 7 練過「簽名時間要用伺服器的時間校正」，這題是另一件事：算「距離上次收到資料過了幾秒」。

**怎麼寫**

1. **Bug 版**：`didReceiveData` 記下 `clock.systemMs`，`isStale` 用 `clock.systemMs` 算；距離上次 5,000 毫秒以上就過期，從來沒收到過資料也算過期。
2. **修正版**：兩處都改用 `clock.monotonicMs`（單調時鐘）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ① 距離上次變成 −3580 秒，不到 5 秒，舊價格被當成最新的，下單按鈕還開著；② 距離上次變成 3600 秒，剛收到的資料被誤判成過期：「2 種情況裡，過期判斷錯誤 2 次」 |
| 修正版 | ① 已過期，變灰並擋下市價單；② 是最新的，可以下單：「過期判斷錯誤 0 次」 |

**為什麼**：系統時間可以被使用者或自動校時改掉。調早一小時，「現在減最後收到資料的時間」變成負數，舊價格看起來永遠是新的，使用者就可能拿 20 秒前的價格下市價單。單調時鐘（iOS 的 `ProcessInfo.processInfo.systemUptime`、Android 的 `SystemClock.elapsedRealtime()`）只會往前走，不受使用者修改影響。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L06_MonotonicClock.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L06_MonotonicClock.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L06_MonotonicClock.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L06_MonotonicClock.kt#fixed
```

### 演練 7：一次斷線，伺服器收到三條連線和五個訂閱（第 1 題）

**檔案**：`L07_ReconnectStorm.swift`（iOS）、`L07_ReconnectStorm.kt`（Android）

**情境**：App 同時有三個畫面開著，各自需要一些 topic：行情列表要 `market.btcjpy.bbo`、`market.ethjpy.bbo`；K 線要 `market.btcjpy.kline.1min`、`market.btcjpy.bbo`；下單頁要 `market.btcjpy.bbo`。一共 5 個訂閱需求，其中不同的 topic 只有 3 個。連線斷了，三個畫面都收到「連線關閉」的通知。

**怎麼寫**

1. **Bug 版**：每個畫面收到通知就自己 `server.connect()` 重連，再 `server.sub` 自己畫面的每個 topic（`screens[index]`）。
2. **修正版**：連線由一個地方負責：用一個旗標記住「已經在重連了」，後面畫面的通知都忽略；所有畫面的 topic 合併、去重，每個 topic 只 `sub` 一次。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「伺服器收到 3 條連線、5 則 sub（重複的 2 則）」，`market.btcjpy.bbo` 訂閱了 3 次 |
| 修正版 | 「伺服器收到 1 條連線、3 則 sub（重複的 0 則）」 |

**為什麼**：「目前需要哪些 topic」要只記錄在一個地方，重連由一個地方負責，否則一次斷線會變成好幾次連線的重連風暴，還會訂閱重複的 topic（每次推送收到好幾份）。這套交易所的私有 WebSocket 每個 API key 最多 10 條連線，每個 IP 每秒最多 100 條新連線，大家同時重連更容易撞到限制。真實的 App 會把連線、topic 清單和狀態機放在同一個物件裡，畫面離開時從清單移除自己的 topic（引用計數）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L07_ReconnectStorm.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange04-Reconnect/L07_ReconnectStorm.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange04-reconnect/L07_ReconnectStorm.kt#bug
```

```lab-solution
android/solution/exchange/exchange04-reconnect/L07_ReconnectStorm.kt#fixed
```

第 6 題的「斷線後先等 2 到 3 秒的寬限時間，再顯示橫幅」是畫面抖動的處理，第 4 題的 `NWPathMonitor`／`ConnectivityManager` 要在真機上切換網路才看得出差異，這兩個沒有可以穩定自動檢查的演練。

## 常見陷阱

- **固定間隔無限重試，沒有退避和 jitter。** 伺服器一出事，所有手機同步重連，把伺服器打爆。
- **一連上（`opened`）就把失敗次數歸零。** 連上又被立刻踢掉時，會變成緊密迴圈。要等連線穩定一段時間才歸零。
- **網路消失時還用計時器重試。** 耗電又沒有意義，要停掉計時器，等網路恢復的事件。
- **重連後沒有重新訂閱，或沒有取快照。** 畫面凍結，或資料有缺漏。
- **只相信 `NWPathMonitor` 或 `onAvailable`。** 忽略了 captive portal 和半開連線，網路狀態只是提示。
- **心跳超時的判斷放在 UI 執行緒。** 主執行緒一忙，就算連線正常也會被誤判成斷線。
- **斷線期間繼續顯示舊價格，卻沒有標示。** 使用者會以為那是即時報價。
- **每個畫面各自重連。** 一次斷線變成好幾次重連，造成重連風暴。

## 面試官想聽到的

1. 重連有明確的狀態機和事件，邏輯可以測試（純函式、注入亂數和時間）。
2. 說得出退避的公式、jitter 的理由、什麼時候歸零，以及遇到限流（429）時怎麼處理。
3. 知道網路狀態 API 的限制，以及半開連線怎麼偵測（應用層監看）。
4. 說得出重連後的恢復順序：認證、訂閱、快照。
5. 斷線時的畫面處理與下單保護：標示過期、阻止用舊價格下單。

> [!FIELD]
> 做過重連與回前景補資料。在一個即時行情 App 裡，K 線用 SSE（Server-Sent Events，伺服器單向推送資料的 HTTP 串流）：斷線後 1 秒重連，每次重連重新取得短效 token；iOS 端有「10 秒沒收到資料」的監看（伺服器每 15 秒送一個 ping 註解，ping 不算資料）；兩個平台回到前景時都會重新抓資料補齊，iOS 端進背景時停止串流，但保留已經串流進來的 K 線，避免價格跳回上一根的收盤價。在一個社交 App 的聊天室用 Socket.IO，進背景時主動斷線，回前景時先更新 token、重新連線，再用 HTTP（HyperText Transfer Protocol，一般的網路請求）同步補回錯過的訊息。
> 面試時可以這樣說：「我做過重連、token 重新取得和回前景補資料。到交易所的 App，我會把它整理成明確的狀態機，重連改成指數退避加 jitter，斷線時標示資料過期，並阻止用過期的價格下單。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 網路狀態 | `NWPathMonitor` | `ConnectivityManager.NetworkCallback` |
| 計時器 | `Task.sleep`（可以取消）或 `DispatchSourceTimer` | 協程的 `delay`，或 `Handler.postDelayed` |
| 進背景、回前景 | `UIApplication.didEnterBackgroundNotification`／`willEnterForegroundNotification` | `ProcessLifecycleOwner`，或 `repeatOnLifecycle(STARTED)` |
| 單調時鐘 | `ProcessInfo.processInfo.systemUptime` | `SystemClock.elapsedRealtime()` |
| 狀態機實作 | `enum` 加 `reduce`；用 actor 讓事件一個一個處理 | `sealed class` 加 `reduce`；用單一 dispatcher 或 `Mutex` 讓事件一個一個處理 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 指數退避 | exponential backoff | 指数バックオフ |
| 隨機誤差 | jitter | ジッター |
| 驚群效應（大量連線同時湧入） | thundering herd | サンダリングハード（同時再接続の集中） |
| 狀態機 | state machine | 状態機械（ステートマシン） |
| 半開連線 | half-open connection | ハーフオープン接続 |
| 單調時鐘 | monotonic clock | 単調増加クロック |
| 資料過期 | stale data | 古いデータ |
| 快照 | snapshot | スナップショット |
