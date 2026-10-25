---
id: platform-lifecycle
title: 生命週期：畫面怎麼開關、App 怎麼進出背景、被系統殺掉之後怎麼還原
summary: iOS 和 Android 最大的差別有兩個：旋轉螢幕時畫面會不會被銷毀重建，以及 App 在背景被系統殺掉之後怎麼把使用者填到一半的東西還回來。這個單元用「下單表單填到一半，切去別的 App 再回來」這個情境，把兩個平台並排講清楚。
priority: P1
day: 2
tags: [lifecycle, scene, activity, fragment, process-death, savedstatehandle, state-restoration]
---

## 重點講解

先講一個貫穿整個單元的情境：使用者在交易所 App 的下單頁選好 BTC/JPY、輸入買入數量 0.05，這時 LINE 傳來訊息，他切過去回覆，三十分鐘後才回到交易所 App。在這段時間裡，App 可能只是被暫停，也可能已經被系統殺掉。兩個平台對這件事的處理方式不一樣，面試常考的就是這些差異。

這個單元回答六個面試常見問題：

1. 畫面從打開到關掉，iOS 和 Android 各會經過哪些階段？
2. App 切到背景、回到前景，兩個平台怎麼知道？iOS 的 `.inactive` 又是什麼？
3. 旋轉螢幕或切換深色模式時，兩個平台各發生什麼事？
4. App 在背景被系統殺掉之後，使用者回來會看到什麼？怎麼還原？
5. Fragment 為什麼有兩套生命週期？Flow 要怎麼跟著生命週期收集？
6. 交易所 App 在進背景、回前景時，要做哪些事？

### 1. 畫面從打開到關掉，iOS 和 Android 各會經過哪些階段？

生命週期（lifecycle）指的是：系統在畫面或 App 的狀態改變時，會依固定順序呼叫你寫的函式，讓你在對的時間點做對的事。兩個平台的名稱不同，但大致對應。最大的差別是：**Android 的畫面（Activity）可能被銷毀再重建，iOS 的畫面（ViewController）通常不會；另外 Android 按 Home 鍵時，畫面的函式會被呼叫，iOS 則不會。**

| 階段 | iOS（UIKit 的 ViewController） | Android（Activity） |
|---|---|---|
| 建立畫面 | `viewDidLoad`：畫面載入記憶體，只做一次 | `onCreate`：建立畫面、設定內容 |
| 即將出現 | `viewWillAppear` | `onStart`：畫面變成可見 |
| 可以互動 | `viewDidAppear` | `onResume`：回到最前面，可以操作 |
| 即將離開 | `viewWillDisappear` | `onPause`：失去焦點（可能還看得到一部分） |
| 完全看不到 | `viewDidDisappear` | `onStop`：畫面不可見 |
| 銷毀 | 物件被釋放，執行 `deinit` | `onDestroy` |

兩個平台依序會發生的事如下：

```text
Android：打開下單頁        onCreate → onStart → onResume
Android：按 Home 回桌面    onPause → onStop
Android：從最近工作清單回來  onRestart → onStart → onResume
Android：按返回鍵離開       onPause → onStop → onDestroy

iOS：從行情頁 push 進下單頁   viewDidLoad → viewWillAppear → viewDidAppear
iOS：按返回回到行情頁         viewWillDisappear → viewDidDisappear → deinit
iOS：按 Home 回桌面          （ViewController 的函式完全不會被呼叫）
```

最後一行是兩個平台差異最大的地方。iOS 的 `viewWillAppear`／`viewDidDisappear` 只代表「這個畫面在導覽流程裡出現或消失」，不代表 App 進了背景；App 進出背景要靠 scene 的回呼（下一題會講）。Android 則不同，Activity 的 `onStop` 就代表使用者看不到這個畫面了，包含切去別的 App。

SwiftUI 與 Jetpack Compose 沒有 `viewDidLoad` 或 `onCreate` 這種函式，改用「跟著畫面出現與消失」的修飾器：

```swift
struct OrderView: View {
    var body: some View {
        OrderForm()
            // 可能執行很多次：返回上一頁、切換 tab 都會再觸發
            .onAppear { print("畫面出現") }
            .onDisappear { print("畫面消失") }
            // 畫面出現時開始，畫面消失時系統自動取消
            .task {
                await loadPrices()
            }
    }
}
```

```kotlin
@Composable
fun OrderScreen() {
    // LaunchedEffect 進入畫面時啟動協程，離開畫面時自動取消
    LaunchedEffect(Unit) { loadPrices() }
}
```

**常見誤解：** `onAppear` 不是「只執行一次」。使用者從下單頁進到下一頁再返回，`onAppear` 會再觸發一次，`.task` 也一樣會在每次畫面出現時執行（只是畫面消失時會自動取消）。所以只該做一次的初始化放在 ViewModel 的 `init`，放在 `onAppear`／`.task` 的載入要能重複執行也不出錯。

> [!TIP]
> 面試時可以這樣說：「兩個平台的畫面生命週期大致對應：iOS 是 viewDidLoad、viewWillAppear、viewDidAppear，Android 是 onCreate、onStart、onResume。最大的差別是 Android 的 Activity 會被銷毀重建，而且按 Home 鍵時 onStop 會被呼叫；iOS 的 ViewController 在 App 進背景時不會收到任何通知，要看 scene 的回呼。」

### 2. App 切到背景、回到前景，兩個平台怎麼知道？

**iOS 用 scene 的回呼，或 SwiftUI 的 `scenePhase`；Android 用 `ProcessLifecycleOwner` 代表整個 App 的前景與背景。** 兩邊都不是看單一畫面，而是看「整個 App」。

先說 iOS。從 iOS 13 起，App 由一個或多個 scene（可以想成一個視窗，iPhone 上通常只有一個）組成，狀態變化會通知 scene：

| 時機 | UIKit 的 scene 回呼 | SwiftUI `scenePhase` |
|---|---|---|
| 回到前景，可以操作 | `sceneDidBecomeActive` | `.active` |
| 即將失去焦點 | `sceneWillResignActive` | `.inactive` |
| 進入背景 | `sceneDidEnterBackground` | `.background` |
| 即將回到前景 | `sceneWillEnterForeground` | `.inactive`（接著變 `.active`） |

這裡最容易搞混的是 `.inactive`。它的意思是「App 還看得到，但沒有在接收事件」。下拉通知中心、打開 App 切換器、來電、跳出 Face ID 對話框時，App 都會短暫進入 `.inactive`，**這時還不是背景**。真正進背景才是 `.background`。使用者按 Home 回桌面時，順序是 `.active → .inactive → .background`；回來時是 `.background → .inactive → .active`。

```swift
@main
struct TradeApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup { RootView() }
            // iOS 17 起也可以寫成 { _, newPhase in ... }
            .onChange(of: scenePhase) { newPhase in
                switch newPhase {
                // 例如：重新抓行情快照
                case .active:     print("可以操作")
                // 例如：疊上遮罩，遮住餘額
                case .inactive:   print("看得到但不能操作")
                // 例如：斷開行情連線、存狀態
                case .background: print("進背景")
                @unknown default: break
                }
            }
    }
}
```

再說 Android。Activity 的 `onStart`／`onStop` 只代表單一畫面，App 有三個畫面時會被呼叫很多次。要知道「整個 App 進出前景」，用 Jetpack 提供的 `ProcessLifecycleOwner`（需要加入 `androidx.lifecycle:lifecycle-process` 函式庫）：

```kotlin
class AppLifecycleObserver : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        // 第一個畫面變成可見：整個 App 回到前景
    }

    override fun onStop(owner: LifecycleOwner) {
        // 最後一個畫面不可見：整個 App 進入背景
    }
}

class TradeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle
            .addObserver(AppLifecycleObserver())
    }
}
```

`ProcessLifecycleOwner` 的 `onStop` 會稍微延遲一下才通知，原因是旋轉螢幕時畫面會短暫地全部消失再重建，不能因此誤判成「使用者離開了 App」。

如果只想知道某一個畫面（而不是整個 App）有沒有進背景，Compose 可以用 `DisposableEffect` 搭配 `LifecycleEventObserver` 監聽 `ON_START`／`ON_STOP`。

> [!TIP]
> 面試時可以這樣說：「iOS 用 scene 的回呼或 SwiftUI 的 scenePhase 知道整個 App 的前景與背景，要注意 inactive 只是失去焦點，下拉通知中心、App 切換器、Face ID 對話框都會進入，不等於進背景。Android 用 ProcessLifecycleOwner，它的 ON_START 和 ON_STOP 代表整個 App 進出前景。」

### 3. 旋轉螢幕或切換深色模式時，兩個平台各發生什麼事？

**iOS 不會重建畫面，只重新計算版面；Android 預設會把 Activity 銷毀再重建，但 ViewModel 會保留。** 這是 Android 比較容易出錯的原因。

Android 把「會影響畫面長相的系統設定改變」稱為 configuration change，包含旋轉螢幕、深色模式、系統語言、字級大小，以及折疊螢幕或多視窗造成的視窗大小改變。預設行為是：舊的 Activity 被銷毀，系統建立一個全新的 Activity，這樣新畫面就能依新設定重新載入對應的資源（例如橫向版面、深色主題）。以旋轉為例，Android 9（API 28）以上、App 的 `targetSdk` 也是 28 以上時，順序是：

```text
onPause → onStop → onSaveInstanceState → onDestroy
   → onCreate（這次的 savedInstanceState 不是 null）
   → onStart → onRestoreInstanceState → onResume
```

`onSaveInstanceState` 是系統讓你把小量資料放進一個 Bundle（鍵值對的容器）的時機，重建後的 `onCreate` 會把這個 Bundle 交回來。API 28 之前，`onSaveInstanceState` 是在 `onStop` 之前呼叫，不是之後。

重建後哪些資料還在？這是面試常考的重點：

| 資料放在哪裡 | 旋轉之後 |
|---|---|
| Activity／Fragment 的欄位、Compose 的 `remember` | 消失 |
| `ViewModel` 的欄位 | **保留**（ViewModel 不會跟著 Activity 一起被銷毀） |
| `onSaveInstanceState` 的 Bundle、`SavedStateHandle`、`rememberSaveable` | 保留 |
| Room、DataStore、檔案 | 保留 |

所以使用者下單表單裡輸入的數量，如果只用 `remember` 保存，旋轉後就清空了：

```kotlin
@Composable
fun OrderForm(vm: OrderViewModel = viewModel()) {
    // 旋轉後會消失
    var memo by remember { mutableStateOf("") }
    // 旋轉後還在，被系統殺掉再回來也還在
    var quantity by rememberSaveable { mutableStateOf("") }
    // 更大的狀態（例如行情資料）放在 vm 裡，旋轉時 ViewModel 會保留
}
```

`rememberSaveable` 的值要能放進 Bundle。字串、數字可以直接存；自己定義的類別要實作 `Parcelable`、`Serializable`，或提供自訂的 `Saver`，不然會 crash。

如果在 `AndroidManifest.xml` 用 `android:configChanges` 宣告自己處理某些設定改變，Activity 就不會被重建，但你要自己負責更新畫面，一般不建議這樣做。

iOS 的做法完全不同：旋轉或切換深色模式時，ViewController 不會被重建，只是 Auto Layout（依約束條件計算版面的機制）和 trait（描述版面環境的資訊，例如螢幕大小類別、深色或淺色）改變，系統通知你更新畫面。UIKit 會呼叫 `viewWillTransition(to:with:)` 或 `traitCollectionDidChange(_:)`；SwiftUI 則是依新的環境重新計算 `body`。因為物件沒有被銷毀，iOS 不需要「旋轉時保存狀態」這件事。

> [!TIP]
> 面試時可以這樣說：「iOS 旋轉時 ViewController 不會重建，只重新計算版面。Android 預設會銷毀並重建 Activity，所以畫面上的暫時狀態要放 ViewModel，旋轉就能保留；Compose 裡要用 rememberSaveable，不能只用 remember。」

### 4. App 在背景被系統殺掉之後，使用者回來會看到什麼？

**兩個平台的系統都會在記憶體不足時，直接把背景的 App 結束掉，而且不一定會通知你。** 使用者回來時，看到的是你有沒有把畫面狀態存好、能不能還原。

回到開頭的情境：使用者填到一半的下單表單，切去 LINE 三十分鐘。系統需要記憶體時，會從最久沒用的背景 App 開始結束。

**Android 稱這個情況為 process death。** process（行程）是作業系統為 App 開的執行單位，行程被結束，所有記憶體裡的東西都消失。使用者再從最近工作清單點回 App 時，系統會重新建立行程，並依照先前存下的狀態重建 Activity。這時要特別注意：**ViewModel 也是全新的**，因為 ViewModel 只能撐過 configuration change（旋轉），撐不過行程被結束。單例物件、記憶體裡的快取、WebSocket 連線，全部消失。

| 資料放在哪裡 | 旋轉 | process death |
|---|---|---|
| Activity 欄位、`remember`、`ViewModel` 欄位 | 前者消失、ViewModel 保留 | 全部消失 |
| `SavedStateHandle`、`onSaveInstanceState`、`rememberSaveable` | 保留 | **保留** |
| Room、DataStore、檔案 | 保留 | 保留 |

所以還原用的資料要放在 `SavedStateHandle`。`SavedStateHandle` 是 ViewModel 專用的鍵值對容器，它的內容會透過 Bundle 一起被保存，行程被結束後仍然能還原：

```kotlin
@HiltViewModel
class OrderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repo: OrderRepository
) : ViewModel() {

    // 使用者選的交易對：小量資料，放進 SavedStateHandle，process death 後會還原
    val symbol: StateFlow<String> =
        savedStateHandle.getStateFlow("symbol", "btcjpy")

    fun selectSymbol(value: String) {
        savedStateHandle["symbol"] = value
    }

    // 板資料、帳戶餘額：不存，回來後由 repository 重新載入
    suspend fun loadDepth() = repo.fetchDepth(symbol.value)
}
```

Bundle 有大小限制：它走的是系統的 Binder（Android 行程之間溝通的機制），所有交易共用約 1 MB 的緩衝區，放太多會丟出 `TransactionTooLargeException`。原則是**只存 ID 和小量的狀態**，大型資料從 repository 重新載入。

**iOS 稱這個情況為被 jetsam 結束。** jetsam 是 iOS 在記憶體不足時，依優先順序結束 App 的機制。App 進背景後會被暫停（suspended），這時被結束**沒有任何通知**，`applicationWillTerminate` 不保證會被呼叫。所以要保存的東西，必須在進背景時就存好。

iOS 提供兩種方式還原 UI 狀態，使用者回來時，系統會重新啟動 App，再把存好的資料交回來：

- **SwiftUI 的 `@SceneStorage`**：宣告在 View 裡，系統負責保存與還原，只適合小量、屬於畫面的狀態。
- **UIKit 的 `NSUserActivity`**：在 scene delegate 的 `stateRestorationActivity(for:)` 回傳一個描述「使用者目前在哪」的物件，重新啟動時會從 `scene(_:willConnectTo:options:)` 的 `session.stateRestorationActivity` 拿回來。

```swift
struct OrderFormView: View {
    // 系統會幫你存，App 被結束後再回來還會是這個值
    @SceneStorage("order.symbol") private var symbol = "btcjpy"
    @SceneStorage("order.quantity") private var quantity = ""

    var body: some View {
        Form {
            TextField("數量", text: $quantity)
            // 價格、餘額不存：回來後重新向伺服器取得
        }
    }
}
```

不管哪個平台，都有三條原則：

1. **只還原使用者輸入的東西**（交易對、數量），不要把敏感資料或大量資料放進去；價格、餘額、訂單狀態這種會變的資料，回來後重新向伺服器取得。
2. **不要把「已按下送出」這種會造成副作用的狀態還原**。使用者不知道 App 被結束過，還原後不應該自動送出訂單。
3. **重要的資料要即時寫進持久層**（資料庫、Keychain），不要等到進背景或被結束時才存，因為這兩個時機都不保證有通知。

怎麼測試？很多人只會測旋轉，漏掉這個情境：

- Android 的開發者選項有「不要保留活動」，但這只是每次離開畫面就銷毀 Activity，**行程還活著**，單例和快取都還在，不等於 process death。正確做法是：先把 App 放到背景，執行 `adb shell am kill <套件名稱>`，再從最近工作清單回到 App。`am kill` 只會結束背景的行程，App 在前景時指令不會生效。
- iOS：用 Xcode 在真機執行，把 App 放到背景後，按 Xcode 的停止鍵，再從主畫面點 App 圖示，就能看到系統還原的結果。

> [!TIP]
> 面試時可以這樣說：「Android 的 process death 會讓 ViewModel 也重建，所以還原要靠 SavedStateHandle，而且只存 ID 和小量狀態，其他資料由 repository 重新載入。iOS 被 jetsam 結束時沒有通知，用 SceneStorage 或 NSUserActivity 還原畫面。我會用 adb shell am kill 測試，不會只測旋轉。」

### 5. Fragment 為什麼有兩套生命週期？Flow 要怎麼跟著生命週期收集？

**Fragment 有兩套生命週期：Fragment 物件本身，和它的畫面（view）。** 收集資料、更新畫面這類跟 UI 有關的工作，要綁在 view 的生命週期上，不是 Fragment 本身。

Fragment 是 Android 裡「一個 Activity 之中的一塊畫面」，在 View 系統（XML 版面）中很常用。使用者從 A 頁面前往 B 頁面時，A 這個 Fragment 會進到 back stack（返回堆疊），這時 **Fragment 物件還活著，但它的畫面已經被銷毀**（`onDestroyView`），返回時再重建畫面（`onCreateView`）。

如果在 Fragment 自己的 `lifecycleScope` 裡收集 Flow，進到 back stack 之後協程還在收集，卻去更新一個已經不存在的畫面，輕則洩漏記憶體，重則 crash：

```kotlin
// 錯誤：lifecycleScope 屬於 Fragment 本身，畫面銷毀後協程仍在收集，
// 還會繼續存取已經不存在的 binding
lifecycleScope.launch {
    viewModel.balance.collect { binding.balance.text = it }
}

// 正確：改用 viewLifecycleOwner，並且用 repeatOnLifecycle 控制何時收集
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.balance.collect { binding.balance.text = it }
    }
}
```

`repeatOnLifecycle(STARTED)` 的行為是：生命週期進入 `STARTED`（畫面可見）時啟動區塊，離開 `STARTED`（也就是 `onStop`，進背景或被蓋住）時取消，回來時重新啟動，不用自己管理 Job。對交易所 App 很重要：行情畫面進背景後，不該繼續消耗網路與電量去收集即時資料。

Compose 的對應寫法是 `collectAsStateWithLifecycle()`（在 `lifecycle-runtime-compose` 函式庫中），一行就能達到相同的效果：

```kotlin
@Composable
fun BalanceText(vm: BalanceViewModel = viewModel()) {
    // 畫面進背景時停止收集，回來時重新收集
    val balance by vm.balance.collectAsStateWithLifecycle()
    Text(balance)
}
```

iOS 沒有 Fragment 這種「物件活著、畫面先死」的情況，對應的做法是：SwiftUI 的 `.task` 在畫面消失時自動取消；UIKit 則在 `viewDidDisappear` 取消訂閱或 Task，避免畫面不在了還在背景收資料。

> [!TIP]
> 面試時可以這樣說：「Fragment 的 view 比 Fragment 本身短命，進到 back stack 時 view 會被銷毀，所以收集 Flow 要用 viewLifecycleOwner 加 repeatOnLifecycle(STARTED)；Compose 用 collectAsStateWithLifecycle。這樣畫面進背景就會停止收集，行情不會白白耗電。」

### 6. 交易所 App 在進背景、回前景時，要做哪些事？

**行情連線、敏感畫面、鎖定、進行中的訂單，這四件事都要跟著生命週期處理。** 下面用「事件 → 做法」整理，並在每一項標出兩個平台的差異。

| 事件 | 要做的事 | iOS | Android |
|---|---|---|---|
| 進背景 | 斷開或降低行情連線頻率（背景網路不保證能用，也耗電） | scene 進 `.background` 時斷線 | `ProcessLifecycleOwner` 的 `onStop`，或 `repeatOnLifecycle` 自動取消 |
| 進背景 | 遮住餘額、持有資產，避免出現在多工畫面的縮圖 | 在 `.inactive`（`sceneWillResignActive`）疊上遮罩 | `FLAG_SECURE`；Android 13（API 33）起可用 `setRecentsScreenshotEnabled(false)` |
| 進背景 | 把送到一半的請求收尾 | `beginBackgroundTask` 申請額外的執行時間 | 重要的工作交給 WorkManager（Android 的背景工作排程函式庫，下一個單元會講） |
| 回前景 | 補上背景期間錯過的行情 | 先用 REST（一般的 HTTP API 請求）取最新快照（snapshot，某一刻的完整資料），再重新訂閱 | 同左 |
| 回前景 | 閒置過久要求重新驗證 | 超過設定時間，顯示生物辨識或 PIN 畫面再露出資產 | 同左 |
| 回前景 | 確認進行中的訂單 | 用 `clientOrderId` 查詢狀態，不要重送 | 同左 |

為什麼回前景要「先抓快照，再重新訂閱」？因為背景期間連線已經斷了，漏掉的價格不會補送。如果直接重新訂閱，畫面會從舊價格突然跳到新價格，甚至中間少了一段 K 線。先用 REST 抓最新快照，再接上即時串流，才不會有斷層。

為什麼不能重送訂單？使用者按下送出後，請求在飛行中就進了背景，App 不知道請求有沒有成功。回來後如果直接重送，可能下兩次單。正確做法是送單時帶一個 client 端產生的 `clientOrderId`（唯一識別碼），回來後用這個 ID 查詢，確認狀態再決定要不要重送。

iOS 進背景時要收尾，可以用 `beginBackgroundTask`：

```swift
@MainActor
func finishWorkBeforeSuspend() {
    var taskID = UIBackgroundTaskIdentifier.invalid
    taskID = UIApplication.shared.beginBackgroundTask(
        withName: "flush-pending"
    ) {
        // 時間到了系統會呼叫這裡，必須在這裡結束，否則 App 會被系統終止
        UIApplication.shared.endBackgroundTask(taskID)
        taskID = .invalid
    }
    Task {
        // 約 30 秒內做完收尾工作（系統決定，不保證）
        await flushPendingRequests()
        // 做完要主動結束
        UIApplication.shared.endBackgroundTask(taskID)
        taskID = .invalid
    }
}
```

Android 防止截圖與縮圖外洩的寫法：

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // FLAG_SECURE：畫面不能被截圖、錄影，最近工作清單裡的縮圖也會是空白
    window.setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE
    )
}
```

> [!TIP]
> 面試時可以這樣說：「進背景時我會斷開行情連線、遮住餘額，iOS 在 inactive 就疊遮罩，Android 用 FLAG_SECURE。回前景時先用 REST 補一次快照再重新訂閱，並依閒置時間決定要不要重新驗證。進行中的訂單用 clientOrderId 查詢，不會重送。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R；Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶。App 首頁進入 **Platform01Lifecycle**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform01-Lifecycle/`，解答在 `labs/ios/Solution/Platform/Platform01-Lifecycle/`；Android 你寫在 `labs/android/practice/platform/platform01-lifecycle/`，解答在 `labs/android/solution/platform/platform01-lifecycle/`（兩邊檔案都是 Practice／Solution 同名，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **生命週期的事件怎麼來**：自動檢查沒辦法真的按 Home 或旋轉螢幕，所以按「執行」時，程式會依序送出生命週期事件：iOS 把 UIKit 畫面放進螢幕外的視窗、直接 push／pop；Android 用 Jetpack 的 `LifecycleRegistry` 依真的 Activity 的順序送出 `onStart`、`onStop` 等事件。要在真的裝置上觀察的做法，寫在各演練裡。
> **兩個平台的題目不一定相同**：同一題兩個平台各做一個演練，但踩到的坑不一樣（例如第 3 題，iOS 是深色模式的顏色，Android 是旋轉後的欄位）。

### 演練 1：每回到畫面一次，同一筆報價就多處理一次（第 1 題）

**檔案**：iOS `L01_ViewWillAppear.swift`、Android `L01_ObserverRemoval.kt`

**情境**：行情頁一出現就訂閱價格。使用者來回進出下單頁（iOS）或按 Home 再從最近工作清單回來（Android）三次，最後來一筆新報價，看行情頁處理了幾次。

**怎麼寫**

1. **Bug 版**：iOS 在 `viewWillAppear` 呼叫 `feed.subscribe(onPrice)`；Android 在 `onStart` 呼叫 `feed.add { onPrice() }`。兩邊都不取消。
2. **修正版**：iOS 把回傳的 id 存起來，在 `viewDidDisappear` 呼叫 `feed.unsubscribe`；Android 把監聽函式存成欄位，在 `onStop` 呼叫 `feed.remove(listener)`。訂閱與取消成對出現。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 來回 3 次後有 4 個訂閱者（第一次出現加上三次回來），一筆新報價被處理 4 次 |
| 修正版 | 來回 3 次後只有 1 個訂閱者，一筆新報價處理 1 次 |

**為什麼**：`viewDidLoad`、`onCreate` 只會執行一次，但 `viewWillAppear`、`onStart` 每次「回到這個畫面」都會執行。在這種每次都會重複執行的函式裡訂閱，就一定要在對應的離開函式（`viewDidDisappear`、`onStop`）取消，不然每回來一次就多一個訂閱者。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L01_ViewWillAppear.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L01_ViewWillAppear.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L01_ObserverRemoval.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L01_ObserverRemoval.kt#fixed
```

### 演練 2：下拉通知中心（iOS）、打開下單頁（Android），行情就斷線了（第 2 題）

**檔案**：iOS `L02_ScenePhaseInactive.swift`、Android `L02_ProcessForeground.kt`

**情境**：行情連線要在「整個 App 進背景」時才斷開。iOS：使用者下拉通知中心、跳出 Face ID 對話框，再按 Home 回桌面；演練把這些操作對應的 `scenePhase` 變化交給 `handle(_:)`。Android：App 有行情頁和下單頁兩個 Activity，使用者點進下單頁、返回，再按 Home；演練依真的順序送出兩個 Activity 的事件（新畫面先 `onStart`，舊畫面才 `onStop`）。

**怎麼寫**

1. **Bug 版**：iOS 的 `handle` 把 `.inactive` 和 `.background` 都當成進背景，一律斷線；Android 把行情連線的 observer 掛在行情頁自己的 lifecycle 上，`onStop` 就斷線。
2. **修正版**：iOS 的 `.inactive` 只把 `maskOn` 設成 `true`（遮住餘額），`.background` 才斷線，`.active` 拿掉遮罩並重新連線；Android 把 observer 掛在整個 App 的 lifecycle 上（正式的 App 是 `ProcessLifecycleOwner.get().lifecycle`，演練裡的 `process.lifecycle` 用同樣的規則：所有 Activity 都不可見才算進背景）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：通知中心與 Face ID 對話框之後，行情斷線 2 次、`.inactive` 時疊上遮罩 0 次。Android：行情頁→下單頁→返回，行情斷線 1 次 |
| 修正版 | iOS：斷線 0 次、疊上遮罩 2 次，按 Home 之後才斷開，回到前景重連、遮罩拿掉。Android：跳轉斷線 0 次，按 Home 之後才斷開，回到前景重連 |

**怎麼在真的裝置上看**：iOS 的 App 上方操作區接的是真正的 `scenePhase`。在模擬器從畫面最上方往下拉出通知中心，或按 Home（⇧⌘H）再從 App 切換器回來，log 會依序印出 `inactive`、`background`、`inactive`、`active`。Android 把重點講解裡的 `AppLifecycleObserver` 掛到 `ProcessLifecycleOwner.get().lifecycle`，在 Logcat 看 `onStop`：在 App 內跳轉頁面不會出現，按 Home 約 0.7 秒後才出現。

**為什麼**：`.inactive` 只代表「看得到但沒有在接收事件」，下拉通知中心、App 切換器、系統對話框都會進入，不是進背景。Android 的 `onStop` 只代表「這一個 Activity 看不到了」，跳到下一個 Activity 時，舊畫面一定會 `onStop`。兩個平台要知道「整個 App」有沒有進背景，iOS 看 `.background`，Android 看 `ProcessLifecycleOwner`。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L02_ScenePhaseInactive.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L02_ScenePhaseInactive.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L02_ProcessForeground.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L02_ProcessForeground.kt#fixed
```

### 演練 3：切到深色模式，邊框還是淺色的（iOS）、旋轉螢幕之後數量被清空（Android）（第 3 題）

**檔案**：iOS `L03_TraitChange.swift`、Android `L03_ConfigChange.kt`

**情境**：iOS：下單頁有一張卡片，邊框用 `UIColor.separator`，使用者把系統切到深色模式。演練會確認 iOS 沒有重建 ViewController（前後是同一個物件），只通知它 trait 改變。Android：使用者在下單頁輸入數量 0.05 再旋轉螢幕。演練用 `ViewModelStore` 和 `ViewModelProvider` 模擬旋轉：丟掉舊的畫面物件，用同一個 `ViewModelStore` 建新的。

**怎麼寫**

1. **Bug 版**：iOS 在 `viewDidLoad` 把 `UIColor.separator.cgColor` 設給 `layer.borderColor`，之後不更新；Android 把數量放在畫面（Activity）自己的欄位。
2. **修正版**：iOS 用 `registerForTraitChanges([UITraitUserInterfaceStyle.self])`（iOS 17 起；舊寫法是 `traitCollectionDidChange(_:)`），深淺色改變時用 `resolvedColor(with: traitCollection)` 重新解析顏色再設給 `layer`；Android 把數量放進 `ViewModel`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：畫面仍是同一個 ViewController（是），邊框跟著變成深色版：否。Android：旋轉後畫面物件是全新的，數量欄位是（空白） |
| 修正版 | iOS：同一個 ViewController，邊框跟著變成深色版：是。Android：旋轉後畫面物件是全新的，數量欄位還是 0.05 |

**為什麼**：iOS 旋轉或切換深色模式時不會重建畫面，只通知 trait 改變，所以在 `viewDidLoad` 做一次就設定好的東西，不會跟著更新。尤其 `CGColor` 是「當下解析出來的固定顏色」，系統的動態顏色轉成 `CGColor` 就不會再跟著深淺色變。Android 預設把 Activity 銷毀再重建，畫面物件的欄位（Compose 的 `remember` 也一樣）全部消失；`ViewModel` 不會跟著 Activity 銷毀，所以能保留。要連行程被系統結束都保留，下一個演練會做。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L03_TraitChange.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L03_TraitChange.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L03_ConfigChange.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L03_ConfigChange.kt#fixed
```

### 演練 4：App 被系統結束再回來，表單狀態不對（第 4 題）

**檔案**：iOS `L04_StateRestoration.swift`、Android `L04_ProcessDeath.kt`

**情境**：使用者選好 ETH/JPY、輸入數量 0.05、按下送出（請求還沒回來）就切去 LINE。三十分鐘後系統結束了 App，使用者再打開它。真的讓系統結束 App 很難在自動檢查裡重現，所以演練用縮小版：把要還原的資料存起來，整個物件丟掉，再用存起來的資料重新建立。iOS 用 `NSUserActivity.userInfo`（和 `@SceneStorage` 一樣由系統保管），經過 `PropertyListSerialization` 變成 `Data` 再讀回來；Android 把 `SavedStateHandle` 的內容抄成一份（系統存進 Bundle 的就是這份），再建立新的 `SavedStateHandle` 和 `ViewModel`。

**怎麼寫**

1. **Bug 版**：iOS 的 `save` 把整個表單（交易對、數量、報價、送出中）都存起來，`restore` 全部還原；Android 把交易對、數量放在 `ViewModel` 自己的欄位，不放進 `SavedStateHandle`。
2. **修正版**：兩邊都只存使用者輸入的交易對與數量。iOS 的 `save` 只回傳這兩項；Android 的 `symbol`、`quantity` 改成讀寫 `SavedStateHandle`。報價和送出中不存。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：數量 0.05 還原了，但報價顯示三十分鐘前的 15000000，按鈕停在送出中。Android：交易對回到預設的 btcjpy，數量（空白） |
| 修正版 | 交易對 ethjpy、數量 0.05 還原；報價（沒有，要重新取得）；按鈕可以操作 |

**怎麼在真的裝置上看**：Android 的 Debug 版先按 Home 回桌面，執行 `adb shell am kill tw.harry.labs`，再從最近工作清單回到 App，行程是真的被結束再重建。開發者選項的「不要保留活動」不夠，它只銷毀 Activity，行程還活著。iOS 在真機執行，把 App 放到背景，按 Xcode 的停止鍵，再從主畫面點 App 圖示。

**為什麼**：行程被結束時，`ViewModel` 的欄位、單例、記憶體裡的快取全部消失，只有存進 `SavedStateHandle`（Android）或交給系統保管的資料（iOS）還在。能還原的只有使用者輸入的東西；報價會變，還原就是顯示過期的價格；「送出中」的請求早就不在了，還原會讓按鈕永遠停在送出中，甚至讓畫面再送一次。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L04_StateRestoration.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L04_StateRestoration.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L04_ProcessDeath.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L04_ProcessDeath.kt#fixed
```

### 演練 5：離開畫面之後，背景還在一直收價格（第 5 題）

**檔案**：iOS `L05_TaskLifetime.swift`、Android `L05_ViewLifecycle.kt`

**情境**：iOS：行情頁收集價格串流（每 20 ms 一筆），演練把畫面放進視窗，300 ms 後關掉，再看 300 ms 內又收到幾筆。Android：A 頁是 Fragment，顯示餘額；使用者前往 B 頁時，A 頁進 back stack，Fragment 物件還活著（lifecycle 停在 `CREATED`），但畫面已經銷毀（`onDestroyView`）；返回時建立全新的畫面。演練用兩個 `LifecycleRegistry` 模擬 Fragment 本身和它的畫面。這個演練要在手機（模擬器）上執行，因為 `lifecycleScope` 需要 Android 的主執行緒。

**怎麼寫**

1. **Bug 版**：iOS 在 `.onAppear` 裡用 `Task { for await … }` 收集；Android 在 `onViewCreated` 用 Fragment 自己的 `lifecycleScope.launch { balance.collect { … } }`。
2. **修正版**：iOS 改用 `.task { }` 修飾器；Android 改用 `viewLifecycleOwner.lifecycleScope`，並用 `repeatOnLifecycle(Lifecycle.State.STARTED)` 包住收集。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：離開之前收到約 12 筆，離開之後又收到十幾筆。Android：畫面銷毀之後又收到 3 筆餘額（更新到不存在的畫面）；返回 A 頁之後，一筆新餘額被處理 2 次 |
| 修正版 | iOS：離開之後又收到 0 筆。Android：畫面銷毀之後 0 筆；返回之後一筆新餘額處理 1 次 |

**為什麼**：iOS 的 `.task` 屬於畫面，畫面消失時 SwiftUI 自動取消它；`.onAppear` 裡的 `Task { }` 是獨立的工作，沒有人取消。UIKit 沒有 `.task`，要把 `Task` 存起來，在 `viewDidDisappear` 取消。Android 的 Fragment 有兩套 lifecycle，收集資料、更新畫面要綁畫面的那一套（`viewLifecycleOwner`）；在 Fragment 自己的 lifecycle 收集，畫面銷毀後協程還在，而且每次建立新畫面都多開一個。Compose 直接用 `collectAsStateWithLifecycle()`。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L05_TaskLifetime.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L05_TaskLifetime.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L05_ViewLifecycle.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L05_ViewLifecycle.kt#fixed
```

### 演練 6：回到前景又送了一次，同一筆委託下了兩張單（第 6 題）

**檔案**：iOS `L06_ResendOrder.swift`、Android `L06_ResendOrder.kt`

**情境**：使用者按下買入，請求送出去的瞬間 App 進了背景，沒收到回應。App 不知道伺服器有沒有收到這筆單。演練跑兩種情況：伺服器其實已經收到了、伺服器根本沒收到。

**怎麼寫**

1. **Bug 版**：回前景時發現 `pendingOrderId` 不是空的（上次送出沒等到結果），就再呼叫 `placeOrder()`。`placeOrder()` 每次都產生新的 `clientOrderId`，伺服器當成另一張單。
2. **修正版**：用 `pendingOrderId` 向伺服器查詢（`exchange.query`）。查得到，只要 `confirm()`；查不到，才用同一個 `clientOrderId` 重送。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 伺服器已經收到的情況：回前景後共有 2 張單；請求沒有送到的情況：1 張 |
| 修正版 | 兩種情況，伺服器都只有 1 張單 |

**為什麼**：請求在飛行中進背景，App 不可能知道成功與否。直接重送，伺服器已經收到的那種情況就會重複下單。`clientOrderId` 是 client 端產生的唯一識別碼，回來之後用它查詢，確認狀態再決定要不要重送；重送也沿用同一個 `clientOrderId`，伺服器就能擋掉重複的單。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L06_ResendOrder.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L06_ResendOrder.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L06_ResendOrder.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L06_ResendOrder.kt#fixed
```

### 演練 7：回到前景之後，行情少了一段（第 6 題）

**檔案**：iOS `L07_SnapshotGap.swift`、Android `L07_SnapshotGap.kt`

**情境**：行情每筆資料都有遞增的序號。App 進背景之前收到序號 1 到 5，在背景的這段時間行情走到序號 10（連線已斷，這 5 筆不會補送）。回到前景時重新接上行情。

**怎麼寫**

1. **Bug 版**：`resume` 直接重新訂閱即時行情（`server.subscribe()`）。
2. **修正版**：先用 `server.snapshot()` 取快照，把 `client.lastSeq` 更新到快照涵蓋的序號，再訂閱。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第一筆即時行情是序號 11，漏了 5 筆（序號 6 到 10） |
| 修正版 | 快照涵蓋到序號 10，第一筆即時行情是序號 11，漏了 0 筆 |

**為什麼**：背景期間連線已經斷了，漏掉的價格不會補送。直接重新訂閱，畫面會從舊價格突然跳到新價格，K 線中間少了一段。先用 REST（一般的 HTTP API 請求）抓最新快照，再接上即時串流，兩段資料的序號接得起來，才沒有斷層。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L07_SnapshotGap.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform01-Lifecycle/L07_SnapshotGap.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform01-lifecycle/L07_SnapshotGap.kt#bug
```

```lab-solution
android/solution/platform/platform01-lifecycle/L07_SnapshotGap.kt#fixed
```

## 常見陷阱

- **把 ViewModel 當成可以撐過 process death 的儲存。** ViewModel 只能撐過旋轉這類 configuration change，行程被結束後會重建。需要還原的小量狀態放 `SavedStateHandle`，重要資料放資料庫。
- **在 Fragment 用 `lifecycleScope` 收集 Flow。** Fragment 進到 back stack 時畫面已經銷毀，協程卻還在更新不存在的畫面。要用 `viewLifecycleOwner`，並搭配 `repeatOnLifecycle`。
- **在 Bundle 或 `SavedStateHandle` 放大型物件。** Bundle 的緩衝區大約 1 MB 且與其他交易共用，放太多會丟出 `TransactionTooLargeException`。只存 ID 與小量狀態。
- **以為 `applicationWillTerminate` 或 `onDestroy` 一定會被呼叫。** 兩個平台在 App 被系統結束時都不保證通知，要保存的東西不能等到最後一刻。
- **只測旋轉，沒測 process death。** 旋轉時 ViewModel 還在，問題不會出現；要用 `adb shell am kill` 才會重現。
- **把「不要保留活動」當成 process death 的測試。** 它只銷毀 Activity，行程仍在，單例和快取都還活著，漏洞測不出來。
- **回前景後沿用舊的行情與餘額。** 背景期間資料已經過時，回前景要重新抓快照再訂閱。
- **以為 SwiftUI 的 `onAppear` 只會觸發一次。** 返回上一頁、切換 tab 都會再觸發，初始化要能重複執行。
- **把 `.inactive` 當成進背景。** 下拉通知中心、Face ID 對話框都會進入 `.inactive`，這時不應該斷開行情連線；但遮罩可以在這裡疊上。

## 面試官想聽到的

1. 能清楚區分 Android 的 configuration change 與 process death：前者 ViewModel 保留，後者 ViewModel 也重建，只有 `SavedStateHandle` 的小量狀態與持久層還在。
2. 知道 iOS 旋轉時不會重建畫面，但 App 在背景可能被 jetsam 結束且沒有通知，所以狀態要在進背景前就存好。
3. 行情、登入、敏感畫面、進行中的訂單，這些跟生命週期有關的設計都有具體做法，而且兩個平台的差異說得出來。
4. 知道 Fragment 的 view 有獨立的生命週期，以及 `repeatOnLifecycle`／`collectAsStateWithLifecycle` 的用途。
5. 知道怎麼測試 process death（`adb shell am kill`），而不是只靠旋轉。

> [!WARNING]
> 面試官常追問：「使用者填到一半的表單，App 被系統殺掉再回來，你要還原嗎？」不要只回答「用 SavedStateHandle 存起來」。要說明只還原使用者輸入的小量資料，價格與餘額重新取得，而且不會還原到「已按下送出」的狀態，避免使用者不知情下重複下單。

> [!FIELD]
> 在一個社交 App 的即時聊天裡處理過這件事：iOS 進背景時，聊天 socket 會先離開房間、最多等 5 秒讓送出佇列處理完，再斷線；回前景時先恢復登入狀態，再重新連線並同步聊天室，漏掉的訊息用 HTTP 同步補回來。Android 在整個 App 進入背景時斷線，回前景時先刷新 token，再同步與連線。
> 在一個即時行情 App 裡，Android 用 `DisposableEffect` 搭配 `LifecycleEventObserver`：`ON_STOP` 時停止自動重新整理，`ON_START` 時恢復；iOS 在回前景時，如果離開的時間超過重新整理的間隔，就重新抓資料。K 線的 SSE（Server-Sent Events，伺服器單向推送的串流）在進背景時停止，但保留已經串流進來的 K 線，避免價格跳回前一根 K 線的收盤價。
> 換成交易所 App 的行情頁，我會讓 Flow 收集統一綁定生命週期，畫面進背景就停止收集。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 畫面生命週期 | `viewDidLoad` → `viewWillAppear` → `viewDidAppear` → `viewWillDisappear` → `viewDidDisappear`；SwiftUI 用 `onAppear`、`onDisappear`、`.task` | `onCreate` → `onStart` → `onResume` → `onPause` → `onStop` → `onDestroy`；Compose 用 `LaunchedEffect`、`DisposableEffect` |
| 整個 App 進出前景 | scene 回呼、SwiftUI 的 `scenePhase`（`.active`／`.inactive`／`.background`） | `ProcessLifecycleOwner` |
| 按 Home 鍵時 | ViewController 沒有任何回呼，要看 scene | Activity 的 `onPause`、`onStop` 會被呼叫 |
| 旋轉、深色模式 | 畫面不重建，只更新版面與 trait | Activity 預設銷毀重建，ViewModel 保留 |
| 畫面內部的 view | 通常與 ViewController 同壽命 | Fragment 的 view 有獨立生命週期，用 `viewLifecycleOwner` |
| 被系統殺掉 | jetsam，沒有通知 | process death，ViewModel 也重建 |
| 狀態還原 | `@SceneStorage`、`NSUserActivity` | `onSaveInstanceState`、`SavedStateHandle`、`rememberSaveable` |
| 進背景的收尾時間 | `beginBackgroundTask`（約 30 秒，系統決定） | WorkManager 等背景機制 |
| 防止縮圖外洩 | 在 `.inactive` 疊上遮罩 | `FLAG_SECURE`、`setRecentsScreenshotEnabled(false)`（API 33） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 生命週期 | lifecycle | ライフサイクル |
| 設定變更（旋轉等） | configuration change | 構成の変更 |
| 行程被系統結束 | process death | プロセスの強制終了 |
| 狀態還原 | state restoration | 状態の復元 |
| 前景／背景 | foreground／background | フォアグラウンド／バックグラウンド |
| 返回堆疊 | back stack | バックスタック |
