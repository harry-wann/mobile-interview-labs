# 兩個演練 App 用的技術

只講 `labs/ios/` 與 `labs/android/` 兩個 App，三個主題：UI 架構、網路套件、資料庫。
範例程式碼都從演練的 Solution 精簡而來，用來快速回想。

| | iOS App | Android App |
|---|---|---|
| UI 架構 | SwiftUI、單一 `NavigationStack`、`ObservableObject`／`@Observable` ViewModel、建構子注入 | Jetpack Compose（Material 3）、單一 Activity、`ViewModel` + `StateFlow`、建構子注入 |
| 網路套件 | 系統 `URLSession`（沒有第三方） | OkHttp 4（HTTP 與 WebSocket）、Gson |
| 資料庫 | 沒有常駐資料庫。Keychain 存 token，Core Data 只在示範單元 | 沒有 Room。DataStore Preferences + Tink 加密存 token |

## iOS App

### 1. UI 架構：SwiftUI + ViewModel

- 進入點一個 `WindowGroup`，首頁是單一 `NavigationStack(path:)`，路徑用 enum，沒有 TabView、沒有 coordinator。
- ViewModel 是 `ObservableObject`（iOS 17 的單元示範 `@Observable`）。誰建立誰擁有：view 自己建的用 `@StateObject`。
- 模組之間用「小介面 + 建構子注入」，沒有 DI 框架。

```swift
// 誰建立，誰擁有：view 自己建的 ViewModel 用 @StateObject（@State 不會觀察 @Published）
final class TickerViewModel: ObservableObject {
    @Published var price: Decimal = 0
}

struct TickerScreen: View {
    @StateObject private var vm = TickerViewModel()
    var body: some View {
        Text("BTC/JPY \(vm.price)")
            .task { await vm.feed() }
    }
}

// 依賴方向：下單模組自己宣告需要什麼，行情模組去實作，從外面注入
protocol PriceProviding { var lastPrice: Decimal { get } }

final class OrderPreview {
    private let prices: PriceProviding
    init(prices: PriceProviding) { self.prices = prices }
    func estimate(quantity: Decimal) -> Decimal { quantity * prices.lastPrice }
}
```

### 2. 網路套件：URLSession

- 只用 `URLSession` + async/await，沒有 Alamofire。
- 兩種逾時要分清楚：`timeoutIntervalForRequest` 是「兩次收到資料之間的空檔」，`timeoutIntervalForResource` 才是整個請求的總時限。
- WebSocket 的演練用程式內的假伺服器示範 gzip frame、心跳、重連。

```swift
let config = URLSessionConfiguration.ephemeral
config.timeoutIntervalForRequest = 1    // 兩次收到資料之間最多等 1 秒
config.timeoutIntervalForResource = 1   // 整個請求總共最多 1 秒
let session = URLSession(configuration: config)

do {
    let (data, _) = try await session.data(from: url)
    let order = try JSONDecoder().decode(Order.self, from: data)
} catch let error as URLError where error.code == .timedOut {
    // 逾時
}
```

### 3. 資料庫：Keychain、Core Data

- App 本身沒有資料庫。Token 這類機密存 Keychain，不存 `UserDefaults`。
- Core Data 只在安全儲存單元示範：每個 context 有自己的物件，畫面不要直接拿 `NSManagedObject`；資料庫存取包在 actor 裡，對外只傳獨立的 struct。

```swift
// Keychain：存、讀、刪
let base: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "tw.harry.labs",
    kSecAttrAccount as String: "accessToken",
]

func save(_ token: String) {
    SecItemDelete(base as CFDictionary)           // 先刪舊的，避免重複
    var add = base
    add[kSecValueData as String] = Data(token.utf8)
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    SecItemAdd(add as CFDictionary, nil)
}

func load() -> String? {
    var query = base
    query[kSecReturnData as String] = true
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
          let data = item as? Data else { return nil }
    return String(data: data, encoding: .utf8)
}
```

```swift
// Core Data：存取包在 actor 裡，對外只回傳獨立的 struct
struct OrderModel { let symbol: String; let status: String }

actor OrderStore {
    private let context: NSManagedObjectContext
    init(container: NSPersistentContainer) {
        context = container.newBackgroundContext()
    }

    func markFilled() async {
        await context.perform { [context] in
            let req = NSFetchRequest<NSManagedObject>(entityName: "Order")
            (try? context.fetch(req))?.first?.setValue("Filled", forKey: "status")
            try? context.save()
        }
    }

    func all() async -> [OrderModel] {
        await context.perform { [context] in
            let req = NSFetchRequest<NSManagedObject>(entityName: "Order")
            return ((try? context.fetch(req)) ?? []).map {
                OrderModel(symbol: $0.value(forKey: "symbol") as? String ?? "",
                           status: $0.value(forKey: "status") as? String ?? "")
            }
        }
    }
}
```

## Android App

### 1. UI 架構：Compose + ViewModel

- 單一 Activity，`setContent` 放一個 root composable；畫面由一個 `rememberSaveable` 的 route 字串決定，`BackHandler` 往回，沒有 navigation-compose。
- ViewModel 用 `StateFlow` 對外，畫面用 `collectAsStateWithLifecycle()` 收。
- 一樣是「小 interface + 建構子注入」，沒有 Hilt。

```kotlin
// 單一 Activity，route 用字串
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }
}

@Composable
fun Root() {
    var route by rememberSaveable { mutableStateOf("") }   // "" 首頁、"Kotlin03/L05" 演練
    BackHandler(enabled = route.isNotEmpty()) { route = route.substringBeforeLast('/', "") }
    when {
        route.isEmpty() -> Home(onOpen = { route = it })
        else -> LabScreen(route)
    }
}

// ViewModel + StateFlow
class OrderViewModel(private val prices: PriceProvider) : ViewModel() {
    private val _state = MutableStateFlow(OrderUiState())
    val state: StateFlow<OrderUiState> = _state.asStateFlow()

    fun setQty(qty: String) = _state.update { it.copy(qty = qty) }
}

@Composable
fun OrderScreen(vm: OrderViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    Text("成本 ${state.qty}")
}

// 依賴方向：下單模組自己宣告 interface，從外面注入
interface PriceProvider { val lastPrice: BigDecimal }
class OrderPreview(private val prices: PriceProvider) {
    fun estimate(quantity: BigDecimal) = quantity * prices.lastPrice
}
```

### 2. 網路套件：OkHttp + Gson

- 沒有 Retrofit、Ktor。直接用 OkHttp，看得到逾時、連線池、WebSocket frame。
- `readTimeout` 是「兩次讀取之間最久等多久」，`callTimeout` 才是整個呼叫的總時限。
- JSON 用 Gson（Android 內建的 `org.json` 在 JVM 單元測試是空殼）。

```kotlin
val client = OkHttpClient.Builder()
    .readTimeout(1, TimeUnit.SECONDS)   // 兩次讀取之間最多等 1 秒
    .callTimeout(1, TimeUnit.SECONDS)   // 整個呼叫總共最多 1 秒
    .build()

// HTTP
val request = Request.Builder().url(url).build()
client.newCall(request).execute().use { response ->
    val order = Gson().fromJson(response.body!!.string(), Order::class.java)
}

// WebSocket（交易所行情：每則訊息是 gzip 的 binary frame）
val ws = client.newWebSocket(
    Request.Builder().url(wsUrl).build(),
    object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"sub":"market.btcjpy.depth.step0"}""")
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val json = gunzip(bytes.toByteArray())          // onMessage 在背景執行緒
            val obj = JsonParser.parseString(json).asJsonObject
            if (obj.has("ping")) webSocket.send("""{"pong":${obj["ping"]}}""")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 重連：退避 + jitter，連上後重新訂閱
        }
    },
)
```

### 3. 資料庫：DataStore + Tink

- App 本身沒有 Room。偏好與 token 用 DataStore Preferences。
- Token 不能明文存：用 Tink 的 `Aead` 加密後只存密文，associated data 綁定用途。

```kotlin
private val tokenKey = stringPreferencesKey("accessToken")

fun newAead(): Aead {
    AeadConfig.register()
    val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
    return keyset.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val aead: Aead,
) {
    private val associatedData = "access_token".toByteArray()

    suspend fun save(token: String) {
        val cipherText = aead.encrypt(token.toByteArray(), associatedData)
        dataStore.edit { it[tokenKey] = Base64.getEncoder().encodeToString(cipherText) }
    }

    suspend fun load(): String? {
        val stored = dataStore.data.first()[tokenKey] ?: return null
        return String(aead.decrypt(Base64.getDecoder().decode(stored), associatedData))
    }
}
```

## 面試常問的五題（兩個 App 的回答）

### 1. iOS 用了哪些語言、框架、架構

- **語言**：Swift（語言模式 5，Swift 6 工具鏈），少量 Objective-C 做對照。
- **框架**：SwiftUI 為主，UIKit 只在對照單元（`UIViewRepresentable`、`UITableView`）。Swift Concurrency（async/await、actor）、Combine、`URLSession`、Keychain（`Security`）、Core Data、`CryptoKit`、`BackgroundTasks`。
- **架構**：MVVM。View 擁有 ViewModel（`@StateObject`／`@Observable`），ViewModel 透過 protocol 依賴資料層，建構子注入，不用 DI 框架。單一 `NavigationStack`，路徑是 enum。

### 2. Android 用了哪些語言、框架、架構

- **語言**：Kotlin 2.2。
- **框架**：Jetpack Compose（Material 3）、`ViewModel`、`lifecycle-runtime-compose`、kotlinx.coroutines／Flow、OkHttp、Gson、DataStore Preferences、Tink、WorkManager、`BiometricPrompt`。測試用 JUnit 4、Turbine、MockK、Robolectric、MockWebServer。
- **架構**：MVVM + 單向資料流。ViewModel 對外只露 `StateFlow<UiState>`，畫面用 `collectAsStateWithLifecycle()` 收。單一 Activity，route 字串 + `BackHandler`。建構子注入，不用 Hilt。

### 3. 兩個平台怎麼設計頁面、管理狀態、處理網路

| | iOS | Android |
|---|---|---|
| 頁面 | SwiftUI 宣告式，`body` 只依賴 state；列表用 `List`／`LazyVStack`，`id` 要穩定 | Compose 宣告式，`LazyColumn` 加 `key`；重組範圍要小，state 往下傳、事件往上傳 |
| 狀態 | 畫面內 `@State`；跨畫面 `ObservableObject` + `@Published`；只在需要的欄位上觀察，行情高頻更新要合併（conflation）再推給畫面 | `mutableStateOf` 給畫面內；`ViewModel` 的 `MutableStateFlow` 用 `update { copy() }`；大 UiState 拆小，避免一個價格變動重組整頁 |
| 網路 | `URLSession` + async/await；逾時分 request／resource；401 只刷新一次；重試加 jitter；下單帶 `clientOrderId` 避免重送變兩張單 | OkHttp；逾時分 `readTimeout`／`callTimeout`；WebSocket 用 `WebSocketListener`，解壓與解析在背景執行緒；重試與去重規則同 iOS |

### 4. 生命週期、背景切換、異常恢復

- **生命週期**：iOS `scenePhase` 的 `.inactive`（下拉通知中心）不是進背景，不要斷線；Android `onStop` 是單一 Activity 的事件，App 是否進背景要看 `ProcessLifecycleOwner`。訂閱放在 `.task`／`repeatOnLifecycle(STARTED)`，離開畫面自動取消。
- **背景切換**：進背景先存草稿、斷 WebSocket；回前景「先抓快照、再重新訂閱」，補上背景期間漏掉的 K 線。背景更新 iOS 用 `BGAppRefreshTask`（每次要先排下一次），Android 用 WorkManager（週期最短 15 分鐘）；靜默推播在 Doze／低電量不保證到。
- **程序被殺**：iOS 用 state restoration 存表單，Android 用 `SavedStateHandle`。回來後不要盲目重送：先用 `clientOrderId` 查詢訂單狀態，再決定要不要重下。
- **重連**：連線狀態機（背景時不准重連），指數退避 + jitter，連上後不要立刻把失敗次數歸零；心跳偵測半開連線；一個 App 只有一條連線，各畫面共用，避免重複訂閱。
- **當機**：不強制解包／`!!`，欄位缺了就降級；只掛一個 uncaught exception handler 並串接前一個；取消（`CancellationError`／`CancellationException`）不是錯誤，不要回報；主執行緒卡住偵測放在別的執行緒；背景被系統回收不算 OOM。

### 5. 怎麼保證兩個平台的功能表現一致

- **同一份規格**：每個單元在複習站、iOS、Android 用同一個代號（`Exchange04Reconnect`），同一題的 Bug 版與修正版兩邊各寫一次，`labs/topics.yaml` 是單一來源。
- **邏輯層平台無關**：重連狀態機、逾時與重試規則、`clientOrderId` 去重、價格精度（`Decimal`／`BigDecimal`）、簽名演算法，兩邊用同樣的純函式設計，只換語言。
- **自動檢查**：每個演練有 `checkBug`／`checkFixed`，iOS 用 `check.sh selftest` 在模擬器跑，Android 用 `testDebugUnitTest` 在 JVM 跑，兩邊的預期結果寫成同一段文字。
- **差異寫明**：平台真的不同的地方（JVM 的 GC 不會因循環參考洩漏、`org.json` 在 JVM 是空殼、WorkManager 最短 15 分鐘）在對照單元裡直接標出來，不假裝一樣。
