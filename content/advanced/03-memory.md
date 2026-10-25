---
id: advanced-memory
title: 記憶體：App 怎麼被系統殺掉、圖片為什麼最吃記憶體、洩漏怎麼找
summary: 行情畫面開著幾個小時，App 變慢然後被系統殺掉，是交易所 App 很實際的問題。這個單元說明 iOS 的 jetsam（系統因記憶體不足而終止 App 的機制）、Android 的 LMK（Low Memory Killer）與 OOM（Out Of Memory，記憶體不足）、圖片解碼的記憶體成本、兩個平台的記憶體洩漏，以及怎麼用 Instruments、Memory Graph、LeakCanary 找出來。
priority: P0
day: 3
tags: [memory, jetsam, lmk, retain-cycle, leak, bitmap, downsampling, leakcanary, instruments]
---

## 重點講解

這個單元回答七個面試常見問題：

1. iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？收到警告時該做什麼？
2. 一張圖片實際上占多少記憶體？怎麼減少？
3. iOS 的記憶體洩漏：retain cycle、`weak` 和 `unowned` 怎麼選？
4. Android 有垃圾回收，為什麼還會洩漏？
5. 怎麼找出記憶體問題？
6. 行情畫面開了幾個小時，App 變慢然後被殺，會是什麼原因？
7. WebView 的記憶體要注意什麼？（比較少考，被問到能答就好）

### 1. iOS 和 Android 是怎麼因為記憶體不足而殺掉 App 的？

兩個平台都會在記憶體不足時直接終止 App，而且**這種終止通常不會留下 crash log**，所以用 Crashlytics 這類當機回報工具看不到。差別在於怎麼判斷和怎麼通知。

| | iOS | Android |
|---|---|---|
| 怎麼計算用量 | **memory footprint**：系統認定這個 App 用掉的記憶體，包含有被修改過的記憶體（dirty）與被壓縮過的記憶體（compressed）。不是 RSS（單純看行程占了多少實體記憶體的算法） | 分開看：Java heap（Kotlin、Java 物件）、native heap（C／C++，Android 8 起 Bitmap 的像素資料也放在這裡）、**PSS**（proportional set size，把共用的記憶體按比例分攤給各個行程） |
| 被殺的方式 | **jetsam**：iOS 的記憶體回收機制。單一 App 超過上限，或整個系統記憶體吃緊時，直接終止行程 | Java heap 超過單一 App 的上限，丟出 `OutOfMemoryError`（常簡稱 OOM，out of memory），沒接住就是 crash。系統整體吃緊時，由 **LMK**（low memory killer，系統的低記憶體終止機制，背後是 `lmkd` 這個常駐程式）依行程的重要程度，從背景行程開始殺，前景的很少被殺 |
| 通知 App | `didReceiveMemoryWarning`、`UIApplication.didReceiveMemoryWarningNotification` | `ComponentCallbacks2.onTrimMemory`（`TRIM_MEMORY_UI_HIDDEN` 表示畫面已經看不到，要釋放畫面相關資源）、`onLowMemory` |
| 查詢還剩多少 | `os_proc_available_memory()`（iOS 13 起），回傳還能再用多少記憶體 | `ActivityManager.getMemoryClass()`、`dumpsys meminfo` 指令 |
| 事後佐證 | MetricKit（Apple 收集真實使用者裝置效能資料的框架）的 `MXAppExitMetric`（iOS 14 起，統計各種退出原因的次數，包含記憶體相關）、Xcode Organizer 的終止統計 | `ApplicationExitInfo`（Android 11 起，可以查到自己的行程上次為什麼結束，例如 `REASON_LOW_MEMORY`）、Google Play 的 Android vitals |
| 提高上限 | entitlement（App 向系統申請的特殊權限）`com.apple.developer.kernel.increased-memory-limit` | `largeHeap`（不建議，只是把問題延後） |

情境：使用者把交易所 App 放在背景半小時，切回來發現畫面像是重新啟動，Crashlytics 卻完全沒有 crash。最可能的原因是 App 在背景因為記憶體壓力被系統回收（jetsam），行程直接消失，`applicationWillTerminate` 通常也不會被呼叫。佐證的方法就是上表的 `MXAppExitMetric` 或 Organizer。

**收到記憶體警告時該做什麼？** 立刻清掉**可以重建**的東西：圖片快取、解碼後的結果、預先載入的資料。但不要把「有收到警告」當作安全網，因為 jetsam 常常在沒有任何警告時就發生，尤其是 App 在背景時。真正的做法是平常就讓 footprint 保持低，進入背景時也主動釋放可以重建的快取。

另外，iOS 的 App Extension（推播擴充、Widget）的記憶體上限遠低於主 App，在 Extension 裡解碼大圖很容易被殺。

> [!TIP]
> 面試時可以這樣說：「iOS 是 jetsam，Android 是 OOM 和 LMK，兩種被殺都不會有一般的 crash log。我會用 MetricKit 的 MXAppExitMetric 或 Organizer 看 iOS 的退出原因，Android 用 ApplicationExitInfo 和 Android vitals。收到記憶體警告時，我會立刻釋放可以重建的快取，但不會只依賴警告，因為 jetsam 常常沒有警告就發生。」

### 2. 一張圖片實際上占多少記憶體？怎麼減少？

圖片是 App 記憶體最大宗的來源。關鍵概念：**圖片解碼後占的記憶體 = 寬 × 高 × 每個像素的位元組數**（常見的 ARGB_8888 或 RGBA 格式是每個像素 4 位元組），跟檔案大小、顯示大小都無關。

例如使用者上傳一張 4000 × 3000 的照片，JPEG（一種有壓縮的圖片檔案格式）檔案只有 2 MB，但解碼後是 4000 × 3000 × 4 = 48,000,000 位元組，約 48 MB（約 46 MiB）。就算只顯示在 100 × 100 的頭像裡，系統還是先把整張約 48 MB 的圖解碼進記憶體，再縮小顯示。列表裡有 20 張這樣的圖，就是將近 1 GB。

對策是 **downsampling（縮小取樣）：解碼的時候就直接解成顯示需要的大小**，而不是先解碼原圖再縮小。

**iOS：用 ImageIO 的縮圖 API。**

```swift
import ImageIO
import UIKit

func downsample(
    imageAt url: URL,
    maxPointSize: CGFloat,
    scale: CGFloat
) -> UIImage? {
    // 只建立圖片來源，這一步不會解碼像素
    let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let source = CGImageSourceCreateWithURL(
        url as CFURL,
        sourceOptions
    ) else {
        return nil
    }

    // 最大邊長以「像素」計算：
    // point 數乘上螢幕倍率（3x 螢幕上 1 point = 3 像素）
    let maxPixelSize = maxPointSize * scale
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        // 限制輸出圖片的最大邊長
        kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        // 套用 EXIF（照片內建的資訊）裡的旋轉方向
        kCGImageSourceCreateThumbnailWithTransform: true,
        // 建立縮圖時就解碼，在背景執行緒呼叫時，
        // 不會把解碼成本留給主執行緒
        kCGImageSourceShouldCacheImmediately: true,
    ]
    guard let cgImage = CGImageSourceCreateThumbnailAtIndex(
        source,
        0,
        options as CFDictionary
    ) else {
        return nil
    }
    return UIImage(cgImage: cgImage)
}
```

Kingfisher（Swift 的圖片下載與快取函式庫）的 `DownsamplingImageProcessor` 包裝的就是同一件事。

**Android：解碼時指定縮小倍數。**

```kotlin
// 步驟 1：只讀取圖片的寬高，不解碼像素
val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
BitmapFactory.decodeFile(path, bounds)

// 步驟 2：算出縮小的倍數（2 的次方），用縮小後的尺寸解碼
fun calculateInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
    var sample = 1
    while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) {
        sample *= 2
    }
    return sample
}

val options = BitmapFactory.Options().apply {
    inSampleSize = calculateInSampleSize(
        bounds.outWidth,
        bounds.outHeight,
        100,
        100,
    )
}

// 縮成約 250 × 188 像素，記憶體不到 0.2 MB
val bitmap = BitmapFactory.decodeFile(path, options)
```

Android 9（API 28）起也可以用 `ImageDecoder` 的 `setTargetSize`。Coil、Glide 這類圖片載入函式庫預設就會依 `ImageView` 的大小解碼，所以通常不需要自己寫。`Bitmap.Config.HARDWARE`（API 26 起）則會把像素資料放在 GPU（繪圖處理器）的記憶體，不占 Java heap。

**快取要有上限。** iOS 用 `NSCache`（記憶體不足時會自動清除項目），不要用 `Dictionary`；Android 用 `LruCache`（least recently used，滿了就先丟掉最久沒用過的，大小通常以 KB 計算）。如果專案同時用了多套圖片載入函式庫，各自有各自的快取，同一張圖就會重複占用記憶體。

> [!TIP]
> 面試時可以這樣說：「圖片解碼後的記憶體是寬乘高乘四位元組，跟檔案大小無關，一張 4000 乘 3000 的照片就約 48 MB。所以我會在解碼時就縮小到顯示需要的尺寸，iOS 用 ImageIO 的 thumbnail API，Android 用 inSampleSize 或交給 Coil、Glide，快取則一定要有上限。」

### 3. iOS 的記憶體洩漏：retain cycle、`weak` 和 `unowned` 怎麼選？

iOS 用 ARC（Automatic Reference Counting，自動引用計數）管理物件：每多一個地方用一般（strong）參考持有物件，計數加 1，歸零就釋放。**retain cycle（循環參考）就是兩個物件互相持有，計數永遠不會歸零，記憶體永遠不會釋放。**

```swift
final class Screen {
    let name: String
    var onTick: (() -> Void)?
    init(_ name: String) { self.name = name }
    deinit { print("deinit \(name)") }

    // closure 持有 self，self 又持有 closure
    func bindStrong() { onTick = { print(self.name) } }

    // weak：closure 不持有 self
    func bindWeak() { onTick = { [weak self] in print(self?.name ?? "gone") } }
}

func run() {
    let a = Screen("A")
    a.bindStrong()
    let b = Screen("B")
    b.bindWeak()
}
run()
print("after run")
// 輸出：
// deinit B
// after run
// A 形成循環，永遠不會印出 deinit A
```

iOS 常見的洩漏來源：

- closure 強持有 `self`：`Timer`、`NotificationCenter` 的 closure 版 observer、Combine 的 `sink` 把訂閱存在 `self` 上、`Task { self.xxx() }`。
- `CADisplayLink` 的 target 是強參考，要用一個只 weak 持有真正物件的中間物件（weak proxy）當 target。
- `WKUserContentController.add(_:name:)` 會強持有註冊的 handler。
- delegate 屬性要宣告成 `weak`。

**`weak` 和 `unowned` 怎麼選？** 兩者都不會讓計數加 1。差別在物件被釋放之後：`weak` 會自動變成 `nil`，安全，但要處理 Optional；`unowned` 不會變，再存取就直接 crash。所以 `unowned` 只有在「生命週期保證 self 一定比 closure 活得久」時才安全，例如只由 self 自己持有的子物件。網路、socket 這類非同步回呼，通常沒辦法保證，預設用 `weak`。

**Swift Concurrency 要特別注意：** `Task { self.xxx() }` 在 Task 結束之前會強持有 `self`。如果裡面是 `for await` 這種可能永遠不會結束的迴圈，`self` 就永遠釋放不了。要用 `[weak self]`（並在迴圈的每一輪才取出 `self`），而且在畫面離開時取消 Task（例如 `onDisappear`，或用 SwiftUI 的 `.task` modifier 讓 SwiftUI 在 view 消失時自動取消）。這部分的細節在 Swift 的 ARC 與 capture 單元有完整說明。

**最簡單的檢查習慣：在 `deinit` 印 log。** 離開畫面後，如果沒有看到該畫面的 ViewModel 印出 deinit，就代表有東西抓著它。

> [!TIP]
> 面試時可以這樣說：「retain cycle 是兩個物件互相強持有，計數永遠不歸零。我依擁有關係選擇：只被自己的子物件使用才用 unowned，網路、socket、通知這種外部回呼一律用 weak。Task 裡的長迴圈也會抓住 self，所以要 weak self 加上取消。平常我會在 deinit 印 log 確認物件有被釋放。」

### 4. Android 有垃圾回收，為什麼還會洩漏？

Android 用 GC（garbage collection，垃圾回收）：從「一定活著的地方」（例如 static 變數、正在執行的執行緒）往下找，找不到的物件才會回收。所以兩個物件互相持有沒關係，不會有 iOS 那種 retain cycle。但 **Android 的洩漏是「該被回收的物件，被一個活得更久的東西意外抓著」**，GC 因此不能回收它。最常見的是 Activity 或 Fragment 被抓住，連帶它們整棵 view 樹和 Context 都無法回收。偵測這類洩漏最常用的是 LeakCanary（Square 開發的洩漏偵測函式庫，下一題會說明用法）。

常見的來源：

- static 欄位、單例持有 Activity 當 Context。改用 `applicationContext`。
- 沒有移除的 listener、延遲的 `Handler` 訊息、`GlobalScope` 的協程（不跟隨畫面的生命週期）、沒有 dispose 的 Rx 訂閱。
- 匿名內部類別與 lambda 會隱含持有外部類別。
- **Fragment 的 view binding 沒有在 `onDestroyView` 設成 null。** Fragment 進入 back stack 時，它的 view 已經被銷毀，但 Fragment 實例還活著。如果 Fragment 的欄位還抓著 binding，整棵 view 樹就無法回收。

```kotlin
class OrderFragment : Fragment(R.layout.fragment_order) {
    private val viewModel: OrderViewModel by viewModels()

    // 可為 null 的欄位 _binding，加上一個方便使用的 binding
    private var _binding: FragmentOrderBinding? = null
    // 只在 onViewCreated 到 onDestroyView 之間使用
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentOrderBinding.bind(view)
        binding.submit.setOnClickListener { viewModel.submit() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 放掉 view 樹，GC 才能回收它
        _binding = null
    }
}
```

> [!TIP]
> 面試時可以這樣說：「Android 有 GC，互相持有不是問題，洩漏是『該回收的物件被活得更久的東西抓著』，最常見的是 Activity 被單例或 static 持有，還有 Fragment 的 binding 沒在 onDestroyView 設成 null。我會用 LeakCanary 在 debug build 自動偵測。」

### 5. 怎麼找出記憶體問題？

流程是：**先確認有問題，再用工具找出是誰抓著，修完加測試防止復發。** 每個平台各有一組工具：

| 平台 | 工具 | 怎麼用 |
|---|---|---|
| iOS | Instruments 的 **Allocations** | 重複「進入畫面、離開畫面」幾次，每輪結束按一次 Mark Generation，比較每一輪之後多出來的物件，如果每次都多一組同樣的 ViewModel，就是洩漏 |
| iOS | Instruments 的 **Leaks**、**VM Tracker** | Leaks 找出已經沒有人用得到、卻沒釋放的記憶體；VM Tracker 看整體的虛擬記憶體分佈 |
| iOS | Xcode 的 **Memory Graph Debugger**（偵錯列上三個小圓圈連在一起的按鈕） | 點選應該已經消失的物件，直接看是哪些物件一路抓著它；Xcode 判斷是洩漏的物件會標上紫色驚嘆號 |
| Android | **LeakCanary** | 只加在 debug build，自動偵測 Activity、Fragment、ViewModel 的洩漏，並顯示 reference chain（從哪個 static 或單例一路抓到洩漏的物件） |
| Android | Android Studio 的 **Memory Profiler** | 抓 heap dump（某一刻所有物件的快照），離開畫面後看 Activity 的實例數是否還大於 0；也可以追蹤配置（allocation tracking） |
| Android | `StrictMode.VmPolicy` | 偵測沒有關閉的 `Closeable`、洩漏的 Activity |
| 兩者 | 記憶體基準測試 | iOS 的 `XCTMemoryMetric`（XCTest 的記憶體指標）；Android 用腳本重複操作，再比對 `dumpsys meminfo` |

修完之後，加一個測試固定下來：

```swift
func testOrderViewModelIsReleased() {
    var vm: OrderViewModel? = OrderViewModel()
    // weak 不會讓計數加 1，只用來觀察
    weak var weakVM = vm
    // 放掉唯一的 strong 參考
    vm = nil
    // 如果還有 retain cycle，物件不會被釋放，weakVM 就不是 nil
    XCTAssertNil(weakVM)
}
```

> [!TIP]
> 面試時可以這樣說：「iOS 我會先在 deinit 確認有沒有釋放，沒釋放就用 Memory Graph 看引用鏈，需要量化時用 Instruments 的 Allocations 反覆進出畫面比較每一輪的成長。Android 在 debug build 接 LeakCanary，必要時用 Memory Profiler 抓 heap dump。修完會寫測試，防止之後重構時復發。」

### 6. 行情畫面開了幾個小時，App 變慢然後被殺，會是什麼原因？

這是交易所 App 很實際的問題。結論：**最常見的原因是「無上限的累積」**，資料不斷進來，卻沒有任何地方把舊的丟掉。常見的情況：

- 行情訊息的 buffer（暫存區）沒有大小上限，消費端處理得比較慢時越積越多。
- 訂單簿（order book，目前所有掛單的價格與數量）只加不減：增量更新中數量變成 0 的價位沒有被移除，列表越來越長。
- K 線資料一直往後加，沒有裁掉很久以前、已經看不到的部分。
- 離開畫面時沒有取消訂閱，重複進出幾次之後，同一個畫面有好幾份訂閱同時在收資料。
- retain cycle 或 Fragment 的 binding 沒釋放，每進出一次畫面就留下一整組物件。
- 圖片沒有 downsampling，或快取沒有上限。

排查順序：

1. **先分辨是哪種死法。** iOS 是 jetsam，Android 看 `ApplicationExitInfo` 與 Android vitals 的 OOM、LMK 統計。
2. **做 soak test**（長時間持續執行同一個操作，觀察記憶體會不會一直增加）：讓行情畫面連續開幾個小時，或重複進出畫面幾十次，記錄記憶體曲線。
3. 曲線一路往上，就用上一題的工具找出是哪一類物件在增加，再看是誰抓著它。

修法的原則：**所有 buffer、快取、K 線、訂單簿深度都要有明確的上限**；離開畫面就取消訂閱；收到記憶體警告或 `onTrimMemory` 時釋放可重建的資料。

> [!TIP]
> 面試時可以這樣說：「行情 App 最常見的記憶體問題是無上限的累積，像是 buffer、訂單簿深度、K 線資料。我會先用 MetricKit 或 ApplicationExitInfo 確認死法，再做長時間的 soak test 記錄記憶體曲線，用 Allocations 或 heap dump 找出增加的物件。修法是所有資料結構都設上限，離開畫面就取消訂閱。」

> [!FIELD]
> 在一個社交 App 和一個即時行情 App 的經驗：社交 App 的 iOS 版有在 `deinit` 印 log 檢查洩漏的慣例，離開畫面沒看到該畫面的 deinit，就代表有東西抓著它；也修過聊天室的記憶體洩漏。即時行情 App 的 K 線圖是用 WebView 載入 TradingView Lightweight Charts，`userContentController` 會強持有 `WKScriptMessageHandler`，所以在 SwiftUI 的 `dismantleUIView` 裡移除 handler。
> 換成交易所 App，我會在 Android 的 debug build 接上 LeakCanary，並建立 soak test 的記憶體基準，放進發版前的檢查。

### 7. WebView 的記憶體要注意什麼？（比較少考，被問到能答就好）

兩個平台的 WebView，網頁內容都跑在另一個獨立的行程，可能因為記憶體壓力被系統終止。App 本身沒事，但網頁區塊會變成白畫面。所以 App 要主動處理。

**iOS：** `WKWebView` 的內容行程被終止時，會呼叫 `webViewWebContentProcessDidTerminate`，在這裡重新載入頁面。

**Android：** 從 Android 8.0（API 26）起，WebView 預設使用獨立的 renderer 行程。renderer 被殺或自己 crash 時，會呼叫 `WebViewClient.onRenderProcessGone`。**如果 App 沒有覆寫這個函式並回傳 `true`，App 自己的行程也會跟著被終止。**

```kotlin
webView.webViewClient = object : WebViewClient() {
    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        // 把壞掉的 WebView 從畫面移除並銷毀，再建立新的並重新載入圖表
        container.removeView(view)
        view.destroy()
        recreateChartWebView()
        // 回傳 true 表示「我已經處理了」，App 的行程才不會跟著被終止
        return true
    }
}
```

離開畫面時也要呼叫 `destroy()`，並移除 `@JavascriptInterface` 對 Activity 的引用。

> [!TIP]
> 面試時可以這樣說：「WebView 的內容跑在另一個行程，可能因為記憶體壓力被系統終止。iOS 我會在 webViewWebContentProcessDidTerminate 重新載入；Android 一定要覆寫 onRenderProcessGone 並回傳 true，不然 App 會跟著被終止。K 線圖這類 WebView，被終止後要能自動重建並補回資料。」

## 實際演練

> [!LAB]
> **打開**：iOS：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced03Memory**。Android：Android Studio 打開 `labs/android` 資料夾，Run configuration 選 **app**，按 ▶，App 首頁進入 **Advanced03Memory**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced03-Memory/`，解答在 `labs/ios/Solution/Advanced/Advanced03-Memory/`；Android 你寫在 `labs/android/practice/advanced/advanced03-memory/`，解答在 `labs/android/solution/advanced/advanced03-memory/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：記憶體的演練盡量用「保留幾筆」「解碼後占幾位元組」「WeakReference 還拿不拿得到」這種不受機器影響的數字確認；iOS 的記憶體用量（`phys_footprint`）、Android 的 Java heap 用量則是輔助的觀察數字。Android 的 debug 版已經裝了 LeakCanary，演練 3 的概念可以在真的 App 裡用它看到通知。

### 演練 1：頭像一路滑下去，快取越來越大，收到警告也不放（第 1 題）

**檔案**：`L01_BoundedCache.swift`（iOS）、`L01_BoundedCache.kt`（Android）

**情境**：行情與社群頁顯示很多頭像，每張解碼後約 100 KB，使用者一路往下滑，總共經過 200 張（約 20 MB），接著系統發出記憶體警告（iOS 是 `UIApplication.didReceiveMemoryWarningNotification`，Android 是 `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`）。

**怎麼寫**

1. **Bug 版**：iOS 用 `[String: Data]` 字典、Android 用 `HashMap<String, ByteArray>` 當快取，沒有上限，也不處理警告。
2. **修正版**：
   - iOS：`NSCache` 設 `totalCostLimit = 5 * 1024 * 1024`，`setObject(_:forKey:cost:)` 帶上資料的位元組數當 cost；`init` 裡用 `NotificationCenter` 監聽記憶體警告，收到就 `removeAllObjects()`。
   - Android：`androidx.collection.LruCache`，`maxSize` 設 `5 * 1024`（單位 KB），`sizeOf` 回傳 `value.size / 1024`；`onTrimMemory(level)` 在 `level >= TRIM_MEMORY_UI_HIDDEN` 時 `evictAll()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 滑過 200 張，快取裡還留著 200 張（約 19 MB）；收到警告之後還是 200 張 |
| 修正版 | 滑過 200 張，快取只留 51 張左右（約 4 MB）；收到警告之後是 0 張 |

**在模擬器上看警告**：iOS 模擬器選單 Debug › Simulate Memory Warning 可以對跑著的 App 發出真的記憶體警告；Android 用 `adb shell am send-trim-memory <package> RUNNING_CRITICAL`。

**為什麼**：被 jetsam 或 LMK 殺掉不會有 crash log，所以平常就要讓用量有上限。收到警告時立刻放掉「可以重建」的東西，但不能只靠警告：iOS 的 jetsam 常常在背景、沒有任何警告時就發生。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L01_BoundedCache.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L01_BoundedCache.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L01_BoundedCache.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L01_BoundedCache.kt#fixed
```

### 演練 2：5 個小頭像，記憶體多了一百多 MB（第 2 題）

**檔案**：`L02_ImageDownsampling.swift`（iOS）、`L02_ImageDownsampling.kt`（Android）

**情境**：用戶上傳的照片是 JPEG，檔案只有幾十 KB 到 2 MB；列表上只顯示小小的頭像（56 點，3x 螢幕是 168 像素；Android 是 100 像素）。解碼後的記憶體是「寬 × 高 × 4 位元組」，跟檔案大小、顯示大小都無關。

- iOS：照片 3000 × 2000（演練用單色照片，檔案特別小），畫面上同時有 5 個頭像。
- Android：照片 4000 × 3000，一次顯示 20 個頭像；JVM 單元測試沒有 `BitmapFactory`，所以用一個 256 MB 的假 heap 預算和公式算解碼後的位元組，數字和真的 Bitmap 一樣。

**怎麼寫**

1. **Bug 版**：iOS `UIImage(data:)?.preparingForDisplay()`，整張原圖解碼；Android `inSampleSize` 一律 1，整張解碼。
2. **修正版**：
   - iOS：ImageIO：`CGImageSourceCreateWithData`（`kCGImageSourceShouldCache: false`）建立來源，再 `CGImageSourceCreateThumbnailAtIndex`，選項 `kCGImageSourceThumbnailMaxPixelSize` 設 `56 × 3`、`kCGImageSourceCreateThumbnailFromImageAlways` 與 `kCGImageSourceShouldCacheImmediately` 設 true，包成 `UIImage(cgImage:)`。
   - Android：寫 `calculateInSampleSize`：`sample` 從 1 開始，只要再放大一倍之後寬和高仍然大於等於要求的大小，就把 `sample` 乘 2。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：照片檔案 93 KB，解碼後每張 3000 × 2000 像素，5 張一共 114 MB；Android：每張 46875 KB，解碼第 6 張時 `OutOfMemoryError`，已經用了 228 MB |
| 修正版 | iOS：每張 168 × 112 像素，5 張一共 367 KB；Android：`inSampleSize = 16`，每張 250 × 187 像素約 182 KB，20 張一共 3 MB |

**為什麼**：4000 × 3000 的照片檔案只有 2 MB，解碼後是 4000 × 3000 × 4 = 48,000,000 位元組（約 46 MiB），就算只顯示在 100 × 100 的頭像，系統還是先把整張解碼進記憶體。對策是 downsampling：解碼的時候就縮成顯示需要的大小。Kingfisher 的 `DownsamplingImageProcessor`、Coil 與 Glide 預設依 View 大小解碼，包裝的都是這件事。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L02_ImageDownsampling.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L02_ImageDownsampling.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L02_ImageDownsampling.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L02_ImageDownsampling.kt#fixed
```

### 演練 3：離開畫面之後，整棵 view 樹還留在記憶體裡（第 4 題）

**檔案**：`L03_LeakDetector.kt`（只有 Android；iOS 的洩漏見 Swift02ARC 的演練 1 到 11）

**情境**：Android 有 GC，洩漏是「該被回收的物件，被活得更久的東西意外抓著」。LeakCanary 的做法是畫面銷毀後，用 `WeakReference` 觀察它，做一次 GC，還拿得到物件就是洩漏。演練用同樣的做法檢查兩種最常見的寫法（JVM 單元測試沒有 Activity 與 Fragment，所以用假的畫面物件，各背一份 1 MB 資料代表整棵 view 樹）：A. 單例 `PriceFeed` 的 listener 清單抓著 Activity；B. Fragment 進了 back stack，view 被銷毀了，Fragment 的 binding 欄位卻還抓著整棵 view 樹。

**怎麼寫**

1. **Bug 版**：A. `onCreate` 把 `{ price -> lastPrice = price }`（lambda 抓著 `this`）加進 `PriceFeed.listeners`，`onDestroy` 不移除。B. `onViewCreated` 把 view 樹存進 `_binding`，`onDestroyView` 不設成 null。
2. **修正版**：A. listener 存在欄位上，`onDestroy` 用 `-=` 把同一個 listener 移除。B. `onDestroyView` 裡 `_binding = null`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | A. 關閉 Activity 之後：還抓得到（洩漏）；B. Fragment 的 view 被銷毀之後：view 樹還抓得到（洩漏） |
| 修正版 | A、B 都是已回收 |

**用 LeakCanary 看真的 App**：debug 版已經裝了 LeakCanary 2.14，反覆進出一個有洩漏的畫面，通知列會跳出洩漏通知，點進去看 reference chain（從哪個 static 或單例一路抓到洩漏的物件）。Android Studio 的 Memory Profiler 可以抓 heap dump，離開畫面後看 Activity 的實例數是否還大於 0。

**為什麼**：GC 從「一定活著的地方」（static、單例、執行中的執行緒）往下找，找不到的才回收。單例的 listener 清單、沒有 dispose 的訂閱、沒有設成 null 的 binding，都是讓一個活得更久的東西抓著一個本來該死的畫面。 另外，Android 的 `System.gc()` 只是提示，沒有剛呼叫過 `runFinalization()` 時不會馬上回收；LeakCanary 是直接呼叫 `Runtime.getRuntime().gc()`，等約 100 ms 再檢查 `WeakReference`。演練也用這個做法，在電腦上的 JVM 和手機上結果才一致。

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L03_LeakDetector.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L03_LeakDetector.kt#fixed
```

### 演練 4：行情畫面開 6 小時，App 越來越慢，最後被系統殺掉（第 6 題，第 5 題的找法也用在這裡）

**檔案**：`L04_SoakTest.swift`（iOS）、`L04_SoakTest.kt`（Android）

**情境**：行情畫面每秒收到一筆成交（`Tick`），開 6 小時就是 21,600 筆，每一筆連同伺服器傳來的原始訊息（iOS 約 2 KB、Android 約 1 KB，除錯用）一起留著。Android 用 1 KB，是因為模擬器（API 29）每個 App 的 heap 上限只有 48 MB，2 KB 的 Bug 版到第 6 小時就 `OutOfMemoryError`，App 直接被系統殺掉，這正是題目說的結局。演練把 6 小時壓縮成一個迴圈，每「過一小時」記一次保留的筆數與記憶體用量，這就是 soak test：長時間重複同一個操作，看記憶體會不會一直增加。

**怎麼寫**

1. **Bug 版**：`append` 把每一筆都加進陣列，永遠不丟。
2. **修正版**：加進陣列之後，超過 `capacity`（500）就丟掉最舊的（iOS `removeFirst`、Android `ArrayDeque.removeFirst`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 每小時多 3600 筆；iOS 記憶體從 +9 MB 一路到第 6 小時的 +56 MB，Android 從約 +4 MB 到 +22–25 MB，沒有盡頭 |
| 修正版 | 每小時都是「保留 500 筆」；iOS 記憶體多 4 到 7 MB 就不再增加，Android 約 0 MB；最後保留 500 筆 |

**用第 5 題的工具找**：iOS 用 Instruments 的 Allocations 錄 Bug 版，每過一段時間按一次 Mark Generation，Growth 欄位會顯示每一輪之後多出來的物件，點進去是 `Tick` 與它的 `Data`；Xcode 的 Debug navigator 也看得到 Memory 曲線。Android 用 Memory Profiler 錄製，曲線一路往上就抓一份 heap dump，依 class 排序看哪一類物件最多。LeakCanary 只抓「被意外抓著的畫面物件」，這種「不斷累積的資料」要靠 heap dump 才看得到。

**為什麼**：行情 App 最常見的記憶體問題是無上限的累積：buffer、歷史資料、K 線、訂單簿深度。所有資料結構都要有明確的上限，離開畫面就取消訂閱，收到記憶體警告時釋放可以重建的資料。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L04_SoakTest.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced03-Memory/L04_SoakTest.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L04_SoakTest.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L04_SoakTest.kt#fixed
```

### 演練 5：static 欄位和 30 秒提醒讓 Activity 回收不了（第 4 題）

**檔案**：`L05_StaticAndHandler.kt`（只有 Android；iOS 對應的規則是「活得很久的東西持有 `self`」，見 Swift02ARC 的演練 7、8、13）

**情境**：和演練 3 一樣用 `WeakReference` 加 GC 檢查，用假的畫面物件各背一份 1 MB 資料代表整棵 view 樹，檢查另外兩種常見寫法：A. 下單畫面為了「從任何地方都能跳出提示」，把自己存進全域的 `AppState.currentActivity`（`object` 裡的欄位是 static），忘了在 `onDestroy` 清掉；B. 價格提醒畫面用 `Handler(Looper.getMainLooper()).postDelayed(…, 30_000)` 排一個 30 秒後「提醒使用者」的工作，使用者在 30 秒內離開畫面。JVM 單元測試裡的 `Handler` 是空殼（`postDelayed` 什麼都不做），所以演練用小型的 `FakeHandler`：把 `Runnable` 存進 list，`removeCallbacks` 真的會移除。真的 `Handler` 的行為一樣：`postDelayed` 把 `Runnable` 包成 `Message` 放進主執行緒的 `MessageQueue`，`removeCallbacks(r)` 只移除 callback 是「同一個 r」的 `Message`。

**怎麼寫**

1. **Bug 版**：A. `FakeOrderActivity.onCreate` 把 `this` 存進 `currentActivity`，`onDestroy` 不清。B. `FakeAlertActivity.onCreate` 用 `handler.postDelayed` 排一個 30 秒後呼叫 `showReminder()` 的 lambda，`onDestroy` 不移除。
2. **修正版**：A. `onDestroy` 先確認 `currentActivity` 還是自己（`===`），再設成 `null`。B. `Runnable` 存成欄位 `reminder`，`postDelayed` 排它，`onDestroy` 用 `removeCallbacks(reminder)` 移除同一個。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | A. 關閉下單畫面之後：還抓得到（洩漏）；B. 關閉提醒畫面之後：還抓得到（洩漏），過了 30 秒：已經關掉的畫面又被提醒了 1 次 |
| 修正版 | A. 關閉下單畫面之後：已回收；B. 關閉提醒畫面之後：已回收，過了 30 秒：已經關掉的畫面又被提醒了 0 次 |

**為什麼**：GC 從「一定活著的地方」往下找。`object` 的欄位是 static，活得和行程一樣久，欄位裡的 Activity 就收不掉。主執行緒的 `Looper` 與 `MessageQueue` 也和行程一樣久，`postDelayed` 的訊息在時間到之前一直待在佇列裡：`MessageQueue` → `Message` → `Runnable`（lambda 用到 `this`）→ Activity。B 的洩漏最多撐到訊息執行為止，但這段時間整棵 view 樹都收不掉，而且時間到了還會對已經關掉的畫面執行（真的 App 裡可能是對已銷毀的 Activity 跳 `AlertDialog`，丟出 `WindowManager.BadTokenException`）。A 的修正要先判斷是不是自己，因為從舊畫面進入新畫面時，新畫面的 `onCreate` 會比舊畫面的 `onDestroy` 先執行，直接設成 `null` 會把新畫面剛存進去的也清掉。B 的 `Runnable` 要存成欄位，是因為 `removeCallbacks` 只認同一個物件（下一個演練單獨練這個陷阱）。更好的 B 寫法是 `lifecycleScope.launch { delay(30_000); … }`，畫面銷毀時協程會自動取消，不用自己移除。

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L05_StaticAndHandler.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L05_StaticAndHandler.kt#fixed
```

### 演練 6：呼叫了 `unregister`，Activity 還是被抓著（第 4 題）

**檔案**：`L06_UnregisterWrongObject.kt`（只有 Android）

**情境**：行情畫面在 `onCreate` 向單例 `PriceFeed` 註冊 listener 接收報價，`onDestroy` 有呼叫 `unregister`，看起來該做的都做了。和演練 3 的差別：演練 3 是「忘了解除」，這裡是「以為解除了，其實沒有」。演練用自己寫的 `PriceFeed`（用一般的 list 強持有 listener，和多數自製的 callback 清單一樣），而不是 `SharedPreferences.registerOnSharedPreferenceChangeListener`：真的 `SharedPreferences` 用 `WeakHashMap` 存 listener，不會因為註冊而洩漏，反而是 listener 沒存成欄位時會被 GC 回收、之後收不到通知。

**怎麼寫**

1. **Bug 版**：`onCreate` 用 `PriceFeed.register { price -> render(price) }` 註冊，`onDestroy` 用 `PriceFeed.unregister { price -> render(price) }` 解除，兩邊各寫一個 lambda。
2. **修正版**：把 listener 存成欄位（用 `PriceListener { … }` 建立），`onCreate` 註冊它，`onDestroy` 解除同一個。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `onDestroy` 呼叫了 `unregister` 1 次，真的移除了 0 個；關閉畫面後 `PriceFeed` 還有 1 個 listener，又重畫了 1 次；關閉畫面之後：還抓得到（洩漏） |
| 修正版 | `onDestroy` 呼叫了 `unregister` 1 次，真的移除了 1 個；關閉畫面後 `PriceFeed` 還有 0 個 listener，重畫 0 次；關閉畫面之後：已回收 |

**為什麼**：lambda（和匿名 `object : PriceListener { … }`）只要用到 `this`，每「寫一次」就是一個新物件。`unregister` 是用「是不是同一個物件」在清單裡找，找不到就什麼都不做，也不會報錯，所以程式看起來像有解除，Activity 卻還留在 `PriceFeed` 裡。修法是把 listener 存成欄位，註冊和解除傳同一個參考。面試時可以這樣區分：演練 3 是沒解除，演練 6 是解除的不是註冊時的那一個，兩種都要靠 `WeakReference` 加 GC（或 LeakCanary）才抓得到。

**Android 解答**

```lab-solution
android/solution/advanced/advanced03-memory/L06_UnregisterWrongObject.kt#bug
```

```lab-solution
android/solution/advanced/advanced03-memory/L06_UnregisterWrongObject.kt#fixed
```

第 3 題（iOS 的 retain cycle、`weak` 與 `unowned`）的演練已經在 Swift02ARC 做過（演練 1 到 13，包含 closure、`unowned` 當掉、Combine、Timer、通知、delegate、WKWebView 的 handler，以及用 `weak` 變數寫「有沒有洩漏」的檢查）。第 5 題（怎麼找出記憶體問題）是工具的用法，Instruments 的 Allocations 與 Memory Graph、LeakCanary、Memory Profiler 的開法寫在演練 3、演練 4 的說明。第 7 題（WebView）比較少考，要真的讓 WebView 的內容行程被系統終止才看得到，沒有可以自動檢查的 bug，沒有演練。

## 常見陷阱

- **用 `Dictionary` 或 `HashMap` 當快取，沒有大小上限，也不回應記憶體警告。** 快取會一直成長。iOS 改用 `NSCache`，Android 用 `LruCache`。
- **把 `[unowned self]` 當成「比較快」的 `weak`。** `unowned` 在物件釋放後存取會直接 crash，只有確定 self 一定比 closure 活得久才能用。
- **直接用 `UIImage(named:)` 或 `BitmapFactory` 解碼原圖，再縮小顯示。** 解碼後的記憶體跟原圖尺寸成正比，要在解碼時就縮小。
- **以為「有處理記憶體警告就夠了」。** jetsam 常常在沒有任何警告時就終止背景的 App。要平常就讓 footprint 保持低。
- **只在寫程式時偶爾打開 LeakCanary，沒有把它變成日常流程。** 洩漏很容易在重構後復發，要用測試或 `deinit` log 固定下來。
- **行情資料在背景一直累積。** 無上限的 buffer、訂單簿深度、K 線資料，開幾個小時之後就會爆掉。

## 面試官想聽到的

1. 能區分 iOS 的 footprint 與 jetsam，和 Android 的 Java heap、native heap 與 LMK，並知道被系統殺掉不會有一般的 crash log。
2. 知道圖片解碼後的記憶體是寬乘高乘每像素位元組數，並能說出 downsampling 的具體 API。
3. 對 retain cycle 有系統性的辨識清單，會比較 `weak` 與 `unowned`，也知道 Task 會抓住 `self`。
4. 知道 Android 洩漏的本質是「被活得更久的東西意外抓著」，並說得出 Fragment binding 這類常見例子。
5. 有一套找出問題的流程：Allocations 的 generation、Memory Graph、LeakCanary、heap dump，並在修完後防止復發。
6. 交易所的情境意識：長時間開著的行情畫面，所有 buffer 與快取都要有上限。

> [!WARNING]
> 面試官常追問：「App 被系統殺掉，沒有 crash log，你怎麼知道？」不要只回答「看 Crashlytics」。要說明這種終止不會產生 crash，iOS 用 MetricKit 的 `MXAppExitMetric` 或 Organizer，Android 用 `ApplicationExitInfo` 與 Android vitals 來佐證。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 記憶體管理方式 | ARC，會有 retain cycle | GC，沒有 retain cycle，但「被意外持有」同樣是洩漏 |
| 被系統殺掉 | jetsam（沒有 crash log） | OOM 會 crash；LMK 殺背景行程（沒有 crash log） |
| 通知 App | memory warning | `onTrimMemory` |
| 圖片縮小解碼 | ImageIO 的 thumbnail API、Kingfisher 的 `DownsamplingImageProcessor` | `inSampleSize`、`ImageDecoder`、Coil、Glide |
| 快取 | `NSCache` | `LruCache` |
| 洩漏偵測 | Memory Graph、Instruments 的 Leaks 與 Allocations | LeakCanary、Memory Profiler |
| WebView 行程被終止 | `webViewWebContentProcessDidTerminate` | `onRenderProcessGone`（沒處理 App 會被終止） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 記憶體用量（系統認定 App 用掉的記憶體） | memory footprint | メモリフットプリント |
| 記憶體警告 | memory warning | メモリ警告 |
| 低記憶體終止機制 | LMK（low memory killer） | ローメモリキラー |
| 記憶體不足錯誤 | OOM（out of memory） | メモリ不足（OOM） |
| 記憶體洩漏 | memory leak | メモリリーク |
| 循環參考 | retain cycle | 循環参照 |
| 縮小取樣（解碼時直接縮小圖片） | downsampling | ダウンサンプリング |
| 記憶體快照 | heap dump | ヒープダンプ |
