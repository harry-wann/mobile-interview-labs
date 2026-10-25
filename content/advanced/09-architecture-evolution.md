---
id: advanced-architecture-evolution
title: 架構怎麼逐步演進：strangler 式重構、MVVM／MVI／Clean／TCA 的取捨、模組化與 feature flag
summary: 資深面試問的不是「畫出理想的架構圖」，而是「面對已經在線上跑的專案，你怎麼讓它一步一步變好」。這個單元說明 strangler 式的漸進重構（新功能用新架構，舊的一塊一塊換）、各種架構模式解決什麼問題與付出什麼代價、UseCase 何時有價值、模組化、依賴注入，以及 feature flag 怎麼配合重構。
priority: P1
day: 3
tags: [architecture, mvvm, mvi, clean-architecture, tca, modularization, feature-flag, di, strangler, refactoring]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？（strangler）
2. MVVM（Model-View-ViewModel）、MVI（Model-View-Intent）、Clean Architecture、TCA（The Composable Architecture）各解決什麼問題？代價是什麼？
3. UseCase 什麼時候有價值？Clean Architecture 一定要每個功能都加 UseCase 嗎？
4. 模組化怎麼切才不會切過頭？
5. 依賴注入（DI，dependency injection）怎麼做？建構子注入、service locator、Hilt 差在哪？
6. feature flag 在重構時怎麼用？要注意什麼？

### 1. 面對已經上線的舊專案，怎麼逐步演進，而不是整個重寫？

**用 strangler 的方式：新功能用新架構寫，舊的功能一塊一塊換成新架構，等舊的完全沒人用了，再把舊程式碼刪掉。** 這個名稱來自絞殺榕：新的樹慢慢包住舊的樹，逐漸取代它。它的好處是：每一小步都可以上線、可以回復，新舊並存的期間 App 仍然正常運作。

反面是「big bang 重寫」：開一條新分支，花半年全部重寫，最後一次合併。情境：交易所 App 的下單畫面有一個 1 萬行的 ViewController，沒有測試，主管說「重構成乾淨架構」。如果整個重寫，這半年新功能還是要加在舊的上面，兩邊的程式碼越差越多，合併時沒有人知道行為有沒有變，出事也沒辦法回復。

**漸進重構的順序，可以記成五步：**

1. **先建立安全網。** 在動手之前，先寫 characterization test：不管現在的行為對不對，先用測試把它鎖定下來，改完再確認行為沒變。沒有測試的話，至少準備一份 UI 或整合測試的手動檢查清單。這樣改完才知道行為有沒有被改壞。
2. **找出 seam。** seam 是「可以不改動舊程式碼，就能替換實作的切入點」，通常是邊界：在 repository、service 這些邊界抽出介面（Swift 的 protocol、Kotlin 的 interface），新舊實作就能共存。
3. **新實作放在 feature flag 後面。** flag 是一個遠端或本機的開關，打開用新的、關閉用舊的，出事時能立刻切回舊的。第 6 題會說明。
4. **一個畫面一個畫面遷移呼叫端。** 從邊界往內、一次一個畫面，每一步都能上線。在新舊的邊界放一層 anti-corruption layer：負責把舊的資料格式轉成新的，讓新程式碼不用沾染舊的寫法。
5. **刪除舊程式碼才算完成。** 用清單或表格追蹤進度，避免專案永遠停在「新舊混合」的狀態；混合狀態一旦變成常態，維護成本是原本的兩倍。

**例子：Android 從 RxJava 漸進遷移到 Kotlin Flow。** RxJava 是舊的非同步資料流函式庫，Flow 是 Kotlin 官方的新做法。順序是：

1. 在資料層（repository）的邊界，讓新的 API 回傳 `Flow`，內部先用橋接函式把舊的 `Observable` 轉過來，其他層先不動。
2. ViewModel 內部把 `BehaviorRelay`、`PublishRelay` 換成 `MutableStateFlow`、`MutableSharedFlow`，對外暴露 `StateFlow`，畫面改用 `collectAsStateWithLifecycle` 或 `repeatOnLifecycle` 收集。
3. 逐一改寫剩下的 Rx 運算子鏈與測試。
4. 確認沒有任何地方再用橋接後，移除橋接函式與 Rx 依賴。

```kotlin
// 步驟一：資料層對外先提供 Flow，內部暫時還是 Rx
// asFlow() 來自 kotlinx-coroutines-rx3
// （用 RxJava 2 時是 kotlinx-coroutines-rx2）
class PriceRepositoryImpl(private val api: PriceApi) : PriceRepository {
    override fun prices(): Flow<List<Price>> =
        // 舊的：回傳 Observable<List<Price>>
        api.observePrices()
            // 橋接成 Flow，ViewModel 可以先改成收集 Flow
            .asFlow()
}
```

iOS 的 Combine 遷移到 Swift Concurrency 是同樣的思路：Combine 的 `Publisher` 有 `values` 屬性可以轉成 `AsyncSequence`，也是先在邊界橋接、再往內改。橋接只放在邊界，最後一定要刪，否則專案會永久背著兩套資料流。

> [!TIP]
> 面試時可以這樣說：「重構舊專案我不會整個重寫，而是用 strangler 的方式：先用 characterization test 鎖住現有行為，在邊界抽出介面，新實作放在 feature flag 後面，一個畫面一個畫面遷移，最後把舊程式碼刪掉，並且用表格追蹤進度，避免停在混合狀態。」

### 2. MVVM、MVI、Clean Architecture、TCA 各解決什麼問題？代價是什麼？

**沒有最好的架構，只有適不適合：每一種都是在解決某個問題，同時付出對應的代價。** 面試時講取捨，比背定義重要。

| 模式 | 核心想法 | 解決的問題 | 代價 | 適合 |
|---|---|---|---|---|
| MVVM（Model-View-ViewModel） | ViewModel 暴露狀態與命令，View 觀察它並顯示 | 把畫面邏輯從 View 抽出來，容易測試；Android 官方架構指南建議 ViewModel 加單向資料流（Apple 沒有指定架構） | 狀態常分散成好幾個 observable，容易彼此不一致 | 大多數的畫面 |
| MVI（Model-View-Intent）／UDF（unidirectional data flow，單向資料流） | 整個畫面只有一份不可變的 state；使用者的操作變成 intent；用 reducer 算出新 state | 狀態可以重現、容易除錯與測試 | 樣板程式碼多；每秒更新很多次的欄位如果放進同一個 state，會讓整個畫面一直重畫 | 狀態複雜的流程（下單、KYC，也就是 know your customer 的身分驗證） |
| Clean Architecture | 分成 presentation（畫面）、domain（業務規則）、data（資料來源）三層，依賴只能指向內層 | 業務規則不依賴框架與畫面，可以重用、單獨測試 | 只轉呼叫的 UseCase 會變成樣板；小專案容易過度設計 | 業務規則重、兩個平台要共用概念 |
| TCA（The Composable Architecture，iOS，Point-Free 公司的開源函式庫） | Reducer、Effect、Dependency 三個核心概念，用 `TestStore` 逐步驗證每個 action 造成的狀態變化 | 行為可預測、測試很完整 | 學習曲線陡、編譯時間變長、綁定函式庫的版本 | iOS 團隊願意承擔學習與維護成本 |
| Coordinator（又稱 FlowController） | 把「下一個要開哪個畫面」從 ViewController 抽出來 | 畫面容易重用、導航集中 | 一個 Coordinator 容易什麼都管，變成 god class（巨大的萬能類別） | UIKit 專案 |

UDF 的原則是「**狀態往下、事件往上**」：畫面只顯示狀態，使用者操作送上去變成事件，狀態只在一個地方被改。reducer 是一個純函式（同樣輸入一定得到同樣輸出）：輸入舊的 state 和一個操作，輸出新的 state。

來看同一個下單畫面，用 MVVM 和 MVI 寫有什麼差別。MVVM 常見的寫法是每個欄位各自一個 observable，`canSubmit` 要靠程式自己保證和其他欄位一致：

```swift
final class OrderViewModel: ObservableObject {
    @Published var price = ""
    @Published var quantity = ""
    // 要自己記得在每次欄位改變後更新，否則會和 price、quantity 不一致
    @Published var canSubmit = false
}
```

MVI 把全部放進一份 state，狀態怎麼變只有 reducer 一個地方決定：

```swift
struct OrderState: Equatable {
    var price = ""
    var quantity = ""

    // 由其他欄位算出來，不會不一致
    var canSubmit: Bool {
        Decimal(string: price) != nil && Decimal(string: quantity) != nil
    }
}

enum OrderAction {
    case priceChanged(String)
    case quantityChanged(String)
}

func reduce(_ state: inout OrderState, _ action: OrderAction) {
    switch action {
    // 唯一能修改 state 的地方
    case .priceChanged(let text):
        state.price = text
    case .quantityChanged(let text):
        state.quantity = text
    }
}
```

金融類的 App 偏好兩件事：**可決定性**（同樣的輸入一定得到同樣的結果）和**可稽核**（狀態怎麼一步一步變成這樣，可以記錄下來）。所以 reducer 式的設計很適合下單、KYC 這類流程；但不必每個畫面都套，簡單的列表用 MVVM 就夠了。

> [!TIP]
> 面試時可以這樣說：「MVVM 簡單好測，但狀態容易分散；MVI 用單一 state 解決不一致，代價是樣板多；Clean 把業務規則和框架隔開，代價是容易出現只轉呼叫的樣板；TCA 測試很完整，但學習曲線和編譯時間是成本。我會依畫面的複雜度選，不會每個畫面都套同一種。」

### 3. UseCase 什麼時候有價值？Clean Architecture 一定要每個功能都加 UseCase 嗎？

**不需要。UseCase 的價值在於承載「被多個畫面重用的業務規則」，或「需要協調多個 repository 的流程」；只是把呼叫轉給 repository 的 UseCase 沒有價值，只會增加樣板。** 架構規則是手段，不是目的。

UseCase（又稱 interactor）是 domain 層的一個小單位，代表使用者想做的一件事，例如「下單」。看兩個對比：

```kotlin
// 有價值：協調兩個 repository，並放入下單前的業務規則；
// 一般下單和快速下單兩個畫面都會用到
class PlaceOrderUseCase(
    private val orders: OrderRepository,
    private val balances: BalanceRepository,
) {
    suspend operator fun invoke(draft: OrderDraft): Result<Order> {
        // 價格要符合 tick size（最小跳動單位）
        if (!draft.isAlignedToTick()) {
            return Result.failure(InvalidPriceException())
        }
        if (balances.available() < draft.requiredFunds()) {
            return Result.failure(InsufficientFundsException())
        }
        return runCatching { orders.place(draft) }
    }
}

// 沒有價值：只是把呼叫轉給 repository，多一層而已
class GetPricesUseCase(private val repo: PriceRepository) {
    operator fun invoke() = repo.prices()
}
```

交易所的業務規則，例如下單前的驗證、手續費預估、價格與數量的精度對齊，很適合放在 domain 層：它們需要被多個畫面共用，而且最需要單元測試。如果 iOS 與 Android 用同一份測試案例（相同的輸入與預期輸出），兩個平台的規則就不會悄悄分岔。

漸進演進時，不必一次把所有舊 ViewModel 都改成走 UseCase。先在「重用度高、規則重」的地方導入，其餘維持原樣，而不是為了一致而硬加 pass-through 的 UseCase。

> [!TIP]
> 面試時可以這樣說：「UseCase 我只放在有價值的地方：業務規則被多個畫面重用，或需要協調多個 repository。只是轉呼叫 repository 的 UseCase 沒有價值，我不會為了架構圖好看而加。下單前的驗證和手續費預估這類規則，我會放在 domain 層，兩個平台共用同一份測試案例。」

### 4. 模組化怎麼切才不會切過頭？

**依 feature（功能）切模組，共用的部分放在 core 模組，依賴方向只能單向，feature 之間不互相依賴。** 目的是讓建置更快、每個部分的責任明確、強制邊界。

做法：

- **依 feature 切**，例如 `:feature:order`（下單）、`:feature:market`（行情）；共用的放 `:core:*`（網路、設計系統、資料庫）。
- **feature 之間不互相依賴。** 下單需要開行情畫面時，透過介面或導航契約（約定好的路由名稱）溝通，不直接引用對方的類別。
- 好處：Gradle 或 Xcode 只重新編譯有改動的模組（增量編譯），沒有互相依賴的模組可以同時編譯（平行編譯）；每個模組有明確的負責人；邊界由編譯器強制，不是靠口頭約定。
- 工具：Android 用 Gradle 的 convention plugins（把每個模組共用的設定寫成一個外掛）；iOS 用 SPM（Swift Package Manager，Swift 官方的套件管理工具）的 local package，或 XcodeGen（用設定檔產生 Xcode 專案的工具）管理 target。

**常見的反模式：**

- **切太細**：模組數量比功能還多，建置設定和跨模組溝通的成本，比節省的建置時間還大。
- **循環依賴**：A 依賴 B、B 又依賴 A。解法是改善依賴方向（依賴反轉：兩邊都依賴一個介面），不是把共用的程式碼丟進 `common`。
- **`common` 變成垃圾桶**：什麼都放進去，最後所有模組都依賴它，邊界形同虛設。

模組化的好處要在專案夠大時才划算。建議先從邊界清楚的部分開始（網路層、設計系統、單一 feature），觀察建置時間與耦合的實際痛點，再決定切多細。

> [!TIP]
> 面試時可以這樣說：「我會依 feature 切模組，共用的放 core，依賴單向，feature 之間透過介面溝通。我不會一開始就切很細，而是先從邊界清楚的部分開始，看建置時間和耦合的實際痛點，再決定切多細。」

### 5. 依賴注入（DI）怎麼做？建構子注入、service locator、Hilt 差在哪？

**依賴注入（DI，dependency injection）的意思是：物件需要的東西（例如 repository），由外面傳給它，而不是在物件裡面自己建立。** 這樣測試時可以傳 mock（測試用的替身），也比較容易替換實作。

三種常見做法：

| 做法 | 寫法 | 優點 | 缺點 |
|---|---|---|---|
| 建構子注入 | `init(repo: Repo)`，依賴由呼叫端明確傳入 | 依賴一目了然、容易替換 | 需要有人負責組裝（composition root，App 啟動時統一建立物件的地方） |
| service locator | 全域容器，到處呼叫 `Container.shared.resolve()` | 寫起來最輕，遷移期方便 | 依賴被隱藏、產生全域可變狀態，測試要設全域狀態 |
| 預設參數注入 | `init(repo: Repo = Repo.shared)` | 寫起來輕，測試時可以傳 mock | 預設值仍然偷偷指向單例（全域唯一的物件） |

建構子注入是基礎。service locator 在遷移期或 UI 測試需要「整批替換」實作時有用，但要限制在邊界，不要讓它滲進 domain 層。

**Android 常用 Hilt。** Hilt 是 Android 官方建議的依賴注入函式庫，建立在 Dagger 之上。它在編譯時就驗證整張依賴圖，缺少綁定會直接編譯失敗，這是它相對於 Koin（執行時才解析依賴的 service locator 式函式庫）的主要優點。

```kotlin
@HiltViewModel
// @Inject 告訴 Hilt 怎麼建立這個類別
class OrderViewModel @Inject constructor(
    private val placeOrder: PlaceOrderUseCase,
) : ViewModel()

@Module
// 綁定的存活時間：和整個 Application 一樣長
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient()
}
```

Hilt 的程式碼產生靠 kapt 或 KSP（Kotlin Symbol Processing，Kotlin 的註解處理工具，通常比 kapt 快）。iOS 沒有官方的 DI 函式庫，常見做法是手寫 composition root，或使用 Factory、swift-dependencies 這類函式庫。

**把 Dagger 遷移到 Hilt**，是 strangler 的另一個例子：Hilt 與 Dagger 可以在同一個專案並存，所以可以一個 Activity 或 Fragment 一個個換，最後移除舊的 Dagger component。

> [!TIP]
> 面試時可以這樣說：「DI 我以建構子注入為基礎。Android 用 Hilt，編譯時就驗證依賴圖；iOS 用手寫的 composition root。service locator 我只在遷移期或 UI 測試要整批替換時，限制在邊界使用，不讓它滲進 domain 層。」

### 6. feature flag 在重構時怎麼用？要注意什麼？

**feature flag 是一個可以遠端控制的開關，讓「程式碼上線」和「功能對使用者開放」分開。** 重構時，新實作放在 flag 後面：先上線但關閉，確認沒問題再逐步打開，出事時關掉就回到舊的實作。

常見的 flag 類型：

| 類型 | 用途 |
|---|---|
| release flag | 程式碼先合併、上線但關閉，由開關決定何時對使用者可見 |
| kill switch | 出事時遠端關閉功能，或強制使用者更新到最低版本（詳見「上線之後怎麼看見問題」單元） |
| experiment flag | A/B 測試：不同使用者看到不同版本，比較結果 |
| permission flag | 依使用者或地區啟用（例如某些功能只對特定地區開放） |

**使用時要注意的成本：**

- **取不到設定時的預設值。** 手機離線或請求失敗時，flag 該是開還是關？金融功能的預設要偏保守（關閉）。預設值要寫在 App 裡。
- **同一個 session 內不要讓畫面中途翻轉。** 使用者操作到一半，功能突然從新的變回舊的，會出錯。通常一個 session 只評估一次。
- **flag debt（沒清掉的旗標越積越多，維護成本越來越高）。** 每個 flag 都應該有負責人和到期日，功能穩定後要刪除 flag 與舊程式碼，否則旗標越積越多，沒人敢刪，測試的組合也成倍增加。
- **Remote Config 不是授權機制。** Remote Config 是 Firebase 提供的遠端設定服務，使用者的手機可以讀取，也可能被竄改，所以不能用它保護敏感功能（例如出金）。權限一律在 server 判斷。

> [!TIP]
> 面試時可以這樣說：「重構時我把新實作放在 feature flag 後面，出事能立刻切回舊的。flag 的預設值寫在 App 裡，金融功能偏保守；同一個 session 只評估一次，避免畫面中途翻轉；每個 flag 要有負責人和到期日，穩定後刪除。另外 Remote Config 不能當授權依據，權限在 server。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced09Architecture**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Advanced09Architecture**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced09-Architecture/`，解答在 `labs/ios/Solution/Advanced/Advanced09-Architecture/`；Android 你寫在 `labs/android/practice/advanced/advanced09-architecture/`，解答在 `labs/android/solution/advanced/advanced09-architecture/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

架構題的「bug」不是當掉，而是**改了架構之後行為悄悄不一樣、測試寫不下去、狀態對不上**。所以每個演練都是一個小型的、可以自動檢查的情境：用演練裡自己造的假手續費、假舊 SDK、假行情服務、假遠端設定，看新舊並存的做法有沒有真的守住行為。

### 演練 1：整個換成新實作，手續費悄悄變了（第 1 題）

**檔案**：`L01_ShadowRun.swift`（iOS）、`L01_ShadowRun.kt`（Android）

**情境**：舊的手續費計算「每一筆成交各自算 0.1%、四捨五入到圓、再加總」。團隊把它重寫成乾淨的新實作「先加總成交金額，再算一次 0.1% 並四捨五入」，看起來只是整理程式碼，但四捨五入的時機不同，結果就不同。演練有 6 筆委託，每筆 1 到 3 筆成交。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`makeCalculator` 直接回傳新實作（big bang，呼叫端整個換掉）。
2. **修正版**：strangler 的 shadow run：回傳一個函式，同時算舊實作與新實作，兩者不一樣就呼叫 `onMismatch` 記錄下來，但最後回傳的仍然是舊實作的結果。演練最後會用修好的新實作再比對一輪。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `使用者被收的手續費：[10, 14, 50, 4, 250, 5]`（舊實作是 `[10, 15, 50, 3, 250, 6]`），`和舊實作不一樣的有 3 筆`，但 `系統記錄到的不一致：0 筆`：使用者被多收、少收了 1 圓，沒有人發現 |
| 修正版 | `使用者被收的手續費：[10, 15, 50, 3, 250, 6]`（和舊實作一致），`系統記錄到的不一致：3 筆`；修好新實作之後再比對是 0 筆，這時才安全切換 |

**為什麼**：沒有測試保護的舊程式，行為就是規格。big bang 重寫的風險是「新的看起來更乾淨，但行為不一樣，而且沒有人知道」。strangler 的做法是新舊並存：使用者拿到的仍是舊結果，新實作在旁邊跑、比對、記錄，不一致降到 0 才切換（第 1 題的步驟一到三：安全網、seam、feature flag）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L01_ShadowRun.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L01_ShadowRun.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L01_ShadowRun.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L01_ShadowRun.kt#fixed
```

### 演練 2：維護中的價格顯示 -1，還誤報跌破提醒（第 1 題）

**檔案**：`L02_AntiCorruption.swift`（iOS）、`L02_AntiCorruption.kt`（Android）

**情境**：新畫面要從舊的行情 SDK 取價格。舊 SDK 的習慣是：沒有價格（維護中）時，`last` 傳 `-1`。新程式裡有三個地方用到價格：價格標籤、下單表單的預設價格、「跌破 14000000 時通知我」的價格提醒。演練各用一個正常的報價和一個維護中的報價測試。

**怎麼寫**

1. **Bug 版**：三個地方都直接使用舊 SDK 的型別，假設 `last` 是真的價格。
2. **修正版**：在邊界寫一個 `adapt`（anti-corruption layer）：把舊 SDK 的型別轉成新程式自己的 `Ticker`，`last` 小於 0 就是 `nil`（iOS）／`null`（Android）。三個地方都只認 `Ticker`，各自明確處理沒有價格的情況。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 維護中：標籤 `-1 JPY`、預設價格 `-1`、`跌破 14000000 的提醒：觸發`（誤報，因為 -1 小於 14000000） |
| 修正版 | 維護中：標籤 `—`、預設價格空白、`跌破 14000000 的提醒：不觸發`。正常的報價兩個版本都一樣 |

**為什麼**：舊 SDK 的怪習慣（用 -1 代表沒有、單位不同、命名不同）如果滲進新程式，每個用到的地方都要記得處理，漏掉一個就出事。在邊界轉成自己的型別，怪習慣只留在 adapter 裡；之後把舊 SDK 整個換掉，也只要重寫這一層（第 1 題的步驟四：anti-corruption layer）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L02_AntiCorruption.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L02_AntiCorruption.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L02_AntiCorruption.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L02_AntiCorruption.kt#fixed
```

### 演練 3：數量填對了按鈕還是灰的，改成亂碼按鈕反而亮著（第 2 題）

**檔案**：`L03_SingleState.swift`（iOS）、`L03_SingleState.kt`（Android）

**情境**：下單畫面有價格、數量兩個輸入框和一個送出按鈕，兩個欄位都是大於 0 的數字才能按。演練依序輸入：價格 15000000、數量 0.5、價格 15000001、數量 `abc`，每次輸入之後看按鈕該不該亮。

**怎麼寫**

1. **Bug 版**：ViewModel 裡 `price`、`quantity`、`canSubmit` 各自是儲存的屬性。價格改變時有重算 `canSubmit`（已經寫好），數量改變時你「忘了」重算，只存 `quantity`。
2. **修正版**：單向資料流：state 只有 `price`、`quantity`，所有操作都送進 `reduce` 修改；`canSubmit` 是由 state 算出來的計算屬性。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `輸入數量 0.5：送出按鈕不能按`（應該可以按）；`數量改成 abc：送出按鈕可以按`（應該不能按） |
| 修正版 | 四次依序是：不能按、可以按、可以按、不能按，全部正確 |

**為什麼**：能從別的欄位算出來的值，另外存一份就多了一個要記得同步的地方。每多一個欄位、多一個會改它的地方，就多一個漏掉的機會，這是 MVVM 常見的「狀態分散成好幾個 observable，容易彼此不一致」。MVI／單向資料流把狀態集中成一份、修改集中在 reducer 一個地方，衍生的值用計算的，狀態就不可能自己跟自己對不上（第 2 題；代價是樣板程式碼比較多）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L03_SingleState.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L03_SingleState.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L03_SingleState.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L03_SingleState.kt#fixed
```

### 演練 4：只想測成本估算，卻啟動了整個行情服務（第 4、5 題）

**檔案**：`L04_DependencyDirection.swift`（iOS）、`L04_DependencyDirection.kt`（Android）

**情境**：下單模組要估算「買 0.5 BTC 大概要多少錢」（數量 × 最新價）。最新價來自行情模組的 `MarketDataService`：第一次用到它就會開一條 WebSocket 連線，連線建立、收到第一筆行情之前，`lastPrice` 是 0。演練模擬「寫一個單元測試估算成本」：測試要用固定的價格 15000000，而且不應該啟動任何真的連線。

**怎麼寫**

1. **Bug 版**：下單模組的 `estimate` 直接用行情模組的單例算（`A09Market.shared`／`L04Market.shared`）。
2. **修正版**：依賴反轉：下單模組自己宣告一個很小的介面（iOS 的 `protocol PriceProviding`、Android 的 `interface PriceProvider`，只有 `lastPrice`），用建構子注入；行情模組去實作它，測試傳一個固定價格的替身。Android 的 Practice 要先寫完介面，`StubPrices` 的 `lastPrice` 前面再補回 `override`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `估算買 0.5 BTC 的成本：0 JPY`，`測試過程中啟動了 1 條真的行情連線`：測試決定不了價格，還得起一條連線 |
| 修正版 | `估算買 0.5 BTC 的成本：7500000 JPY`，`測試過程中啟動了 0 條真的行情連線` |

**為什麼**：下單模組依賴行情模組的具體類別，兩邊就綁死了：要測下單，就得把行情（甚至整個 App）起起來，單元測試變成整合測試。依賴反轉讓依賴方向指向「下單模組自己定義的介面」，行情模組反過來去符合它；用建構子注入，依賴一目了然，測試傳替身就好（第 4 題：feature 之間透過介面溝通；第 5 題：以建構子注入為基礎）。預設參數 `= MarketDataService.shared` 看起來方便，卻讓依賴偷偷回到單例。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L04_DependencyDirection.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L04_DependencyDirection.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L04_DependencyDirection.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L04_DependencyDirection.kt#fixed
```

### 演練 5：下單走舊流程，取消時開關翻了走新流程（第 6 題）

**檔案**：`L05_FlagSnapshot.swift`（iOS）、`L05_FlagSnapshot.kt`（Android）

**情境**：新的下單流程放在遠端 feature flag（`newOrderFlow`）後面。舊流程把委託記在舊的儲存，新流程記在新的儲存，兩邊互不認得。使用者用舊流程下了一筆委託，這時營運調高放量比例，App 在背景刷新了遠端設定，開關翻成「開」，使用者接著按取消。演練的順序：開關關 → 下單 → 遠端設定刷新 → 取消 → 重新啟動 App → 下單 → 取消。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`makeFlagReader` 回傳的函式，每次被呼叫才去讀目前的 `remote.newOrderFlow`。
2. **修正版**：建立這個 session 的函式時，只讀一次存成快照，回傳的函式一直回傳這個值。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `下單 #1：走舊流程`，`取消 #1：找不到委託 #1`：開關翻了，取消走去新流程的儲存找，找不到 |
| 修正版 | `取消 #1：取消成功`（整個 session 都走舊流程）；重新啟動之後 `下單 #2：走新流程`、`取消 #2：取消成功` |

**為什麼**：同一個 session 內，一個 flag 如果中途改變，使用者操作到一半，功能就從舊的變成新的，前後兩步用的是不同的流程和資料。所以一個 session 只評估一次，新的值等下一次啟動才生效（第 6 題）。「要立刻生效」的情況是 kill switch，那是另一種旗標，在「上線之後怎麼看見問題」單元（Advanced10Observability）的演練處理。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L05_FlagSnapshot.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L05_FlagSnapshot.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L05_FlagSnapshot.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L05_FlagSnapshot.kt#fixed
```

### 演練 6：測試手續費得先建畫面物件，兩個畫面的數字還不一樣（第 3、2 題）

**檔案**：`L06_DomainFreeOfUI.swift`（iOS）、`L06_DomainFreeOfUI.kt`（Android）

**情境**：現貨的 taker（吃單，也就是拿走掛單簿上現有委託的一方）手續費是成交金額的 0.12%，四捨五入到整數日圓。「一般下單」和「快速下單」兩個畫面都要顯示預估手續費。演練模擬兩件事：① 寫一個單元測試，驗證 12,345 日圓的手續費是 15 日圓（12,345 × 0.12% = 14.814，四捨五入是 15）；② 兩個畫面各輸入 12,345 與 99,999 日圓，比較畫面上顯示的手續費。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：`CalcFeeUseCase` 的建構子直接收「一般下單畫面」的狀態物件（iOS 是 `ObservableObject` 加 `@Published`，Android 是 Compose 的 `MutableState`），算完把結果直接寫進它的 `feeText`。快速下單畫面的型別不同，用不了這個 UseCase，只好把公式抄一份進自己的狀態物件，抄的時候順手改成無條件捨去。
2. **修正版**：Domain 層只收純資料、回傳結果：`FeeRequest`（金額）進、`FeeResult`（手續費）出（iOS 的 struct、Android 的 data class），不 import SwiftUI 或 androidx.compose。兩個畫面各自呼叫同一個 UseCase，再把結果放進自己的狀態；測試只建 UseCase。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `單元測試為了驗證公式，建立了 1 個畫面物件`，`兩個畫面的手續費不一致：2 筆（共 2 筆）`（12345 日圓：一般下單 15、快速下單 14；99999 日圓：120 與 119） |
| 修正版 | `單元測試為了驗證公式，建立了 0 個畫面物件`，`兩個畫面的手續費不一致：0 筆（共 2 筆）`（兩個畫面都是 15 與 120） |

**為什麼**：Clean Architecture 的規則是依賴只能指向內層（第 2 題）：業務規則所在的 Domain 層，不可以認識畫面框架。UseCase 一旦收了畫面的狀態物件，測它就得先把畫面物件建出來，單元測試變成要拉起畫面的測試；別的畫面也用不了它，規則只好被複製，而複製出來的版本很快就和原版分岔，同樣的金額在兩個畫面算出不同的手續費。UseCase 有價值的地方，正是承載「被多個畫面重用的業務規則」（第 3 題）；要重用，它的輸入輸出就必須是純資料，由畫面自己把結果放進狀態。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L06_DomainFreeOfUI.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L06_DomainFreeOfUI.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L06_DomainFreeOfUI.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L06_DomainFreeOfUI.kt#fixed
```

### 演練 7：同一個 API 錯誤，三個畫面三種文案，還重試了不該重試的（第 4、5 題）

**檔案**：`L07_ErrorLayers.swift`（iOS）、`L07_ErrorLayers.kt`（Android）

**情境**：下單 API 會失敗的原因有五種（下單帶有 clientOrderId，所以重送不會重複下單）：回應內容 `{"code": 1043}`（餘額不足）、HTTP 429（太頻繁，Retry-After 標頭寫 3 秒）、連線逾時、`{"code": 1102}`（價格不符合最小跳動單位）、`{"code": 9999}`（沒看過的錯誤碼）。「一般下單」「快速下單」「撤單」三個畫面都會遇到這五種。演練對同一組五個錯誤，列出三個畫面各自顯示的文案與重試決定。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：原始錯誤（錯誤碼、HTTP 狀態碼、逾時）一路傳到畫面，三個畫面各自翻譯：一般下單只認得 1043，其他都顯示「未知錯誤」；快速下單直接顯示原始碼，限流與逾時都立刻重試；撤單的文案自己縮寫，每一種錯誤都重試（包含餘額不足）。
2. **修正版**：分三層。Data 層的 `OrderErrorMapper` 把原始錯誤翻成 Domain 的 `OrderError`（`insufficientBalance`、`rateLimited(retryAfter:)`、`network`、`invalidPrice`、`unknown(code:)`）；可不可以重試由 Domain 的 `OrderError.retry` 回答；Presentation 層只有一張文案對照表（`OrderErrorMessages`），三個畫面共用。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `HTTP 429｜一般下單｜未知錯誤｜不重試`、`code 1043｜快速下單｜錯誤碼 1043｜不重試`、`code 1043｜撤單｜餘額不足｜可以立刻重試`；最後 `5 個錯誤中，三個畫面的文案與重試決定都一致的有 0 個` |
| 修正版 | 五個錯誤在三個畫面一模一樣，例如 `HTTP 429｜…｜操作太頻繁，請 3 秒後再試｜3 秒後可重試`、`code 1043｜…｜餘額不足，請先入金或減少數量｜不重試`；最後 `5 個錯誤中，三個畫面的文案與重試決定都一致的有 5 個` |

**為什麼**：錯誤碼是 API 的細節，只有 Data 層該看得懂。越過 Data 層，每個畫面都得自己翻譯，三個畫面就有三種文案，新增一個錯誤碼要改三個地方；模組之間的邊界，就是不讓底層的細節漏到上層（第 4 題）。做法是由 Domain 先宣告錯誤型別，Data 層實作 repository 時負責翻譯成它：Data 依賴 Domain，不是反過來（第 5 題的依賴方向）。「可不可以重試」是業務判斷：餘額不足重試一百次也不會成功，429 要等 Retry-After 的秒數，逾時能重送是因為下單帶有 clientOrderId。所以這個判斷放在 Domain 的錯誤型別；Presentation 層只決定怎麼顯示（文案、要不要出現重試按鈕）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L07_ErrorLayers.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced09-Architecture/L07_ErrorLayers.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced09-architecture/L07_ErrorLayers.kt#bug
```

```lab-solution
android/solution/advanced/advanced09-architecture/L07_ErrorLayers.kt#fixed
```

第 3 題判斷「UseCase 什麼時候有價值」（有沒有業務規則、要不要協調多個 repository）是設計取捨，不是會出錯的寫法；演練 6 只練其中會真的出錯的部分：規則放進 UseCase 之後，UseCase 不能依賴畫面框架。第 2 題的 MVI 代價（樣板多）、Clean Architecture 的 UseCase 與模組切分的粗細，也屬於取捨題，演練 3、演練 4、演練 6 只挑出其中「狀態不一致」、「依賴方向」與「Domain 層不認識畫面」這幾個會真的出錯的部分。

## 常見陷阱

- **一次重寫（big bang）。** 沒有安全網、分支壽命長、無法回復。要用 strangler 一步一步換。
- **為了「乾淨」而加一堆 pass-through 的 UseCase 或 Mapper。** 團隊反而變慢。只在有業務規則或需要協調時才加。
- **兩種風格長期並存，卻沒有終點。** 混合狀態變成常態，維護成本加倍。要用清單追蹤，直到舊的刪除。
- **用 feature flag 取代設計。** 旗標越積越多，沒人敢刪。每個 flag 要有負責人和到期日。
- **service locator 滲進 domain 層。** 測試必須先設全域狀態，依賴被隱藏。要限制在邊界。
- **架構文件過期。** 文件和程式碼脫節，新人被誤導。重構完成要更新文件。

## 面試官想聽到的

1. 講取捨，不講教條：知道每個架構模式解決什麼、付出什麼代價，並能依團隊與專案的情況選擇。
2. 有漸進重構的實戰框架（安全網、seam、feature flag、逐畫面遷移、刪除舊程式碼），並知道如何追蹤進度。
3. 對依賴注入、模組化、feature flag，都有「何時用、何時不用」的判斷。
4. 能說明新功能用新架構、舊的逐步換掉的具體步驟，而不是「全部重寫」。

> [!FIELD]
> 在一個社交 App，我獨立負責雙平台的重構：iOS 在短時間內完成全 App 重構，採 SwiftUI 與 UIKit 混合的架構（SwiftUI 以 `UIHostingController` 嵌入 UIKit），並導入 Swift Concurrency 與 Combine；導航由 FlowController（Coordinator 的一種）負責，ViewModel 以輸入（Inputs）、輸出（Outputs）協定組成。Android 在開發新功能之餘逐頁重構，陸續導入 Jetpack Compose、Navigation Component 與 Paging 3，並把依賴注入從 Dagger 遷移到 Hilt，這正是第 1 題 strangler 的實例：一頁一頁換，每一步都可以上線。
> 在另一個 BLE 智慧鎖 SDK 的專案，新版的使用者端與管理端 App 採用 MVVM（Model-View-ViewModel）與 Hybrid（WebView）架構，並以 Kotlin Multiplatform（KMP，讓 iOS 與 Android 共用 Kotlin 寫的邏輯）把共用邏輯依領域（common、user、manager）劃分成多個模組，兩端再對應拆成 Android library module 與 iOS framework，可以在模組化的問題上舉例。
> 流程上，也把 AI agent 納入開發，建立「規劃、實作、review、修正」的循環。
> 換成交易所 App，我會把精度、tick size 對齊、下單預檢這類規則放在 domain 層，iOS 與 Android 共用同一份測試案例；依賴注入以明確的 composition root 為主；新功能用新架構寫，舊的功能依 strangler 的方式一塊一塊換。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 常見模式 | MVVM、TCA、Coordinator | MVVM、MVI（UDF）、Clean Architecture |
| 依賴注入 | 手寫 composition root、Factory、swift-dependencies | Hilt（編譯時驗證）、Koin |
| 導航 | Coordinator／FlowController | Navigation Component、Navigation Compose |
| 模組化 | SPM local package、XcodeGen target | Gradle 多模組加 convention plugins |
| 非同步風格遷移 | Combine 或 RxSwift 遷移到 Swift Concurrency | RxJava 遷移到 Flow 與 coroutines |
| 畫面狀態 | `ObservableObject`、`@Observable`、TCA 的 State | `StateFlow<UiState>` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 漸進式重構（新的逐步取代舊的） | strangler pattern | ストラングラーパターン |
| 單向資料流 | UDF（unidirectional data flow） | 単方向データフロー |
| 依賴注入 | DI（dependency injection） | 依存性の注入 |
| 功能開關 | feature flag | フィーチャーフラグ |
| 模組化 | modularization | モジュール化 |
| App 啟動時統一建立與組裝物件的地方 | composition root | コンポジションルート |
| 先把現有行為鎖定下來的測試 | characterization test | キャラクタリゼーションテスト |
