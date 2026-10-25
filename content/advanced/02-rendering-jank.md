---
id: advanced-rendering-jank
title: 畫面卡頓：每一格畫面的時間預算、怎麼找出誰拖慢主執行緒、行情列表為什麼會卡
summary: 行情列表一次顯示上百個交易對、價格每秒更新很多次，最容易出現捲動卡頓。這個單元說明卡頓（jank、hitch）是什麼、一格畫面有多少時間、iOS 與 Android 怎麼找出是誰拖慢，以及列表捲動卡頓的常見原因和修法。
priority: P0
day: 3
tags: [jank, hitch, offscreen-rendering, overdraw, perfetto, jankstats, recyclerview, uikit]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 卡頓（jank、hitch）是什麼？一格畫面有多少時間可以用？
2. 一格畫面是怎麼做出來的？commit hitch 和 render hitch 差在哪？
3. 怎麼找出是誰拖慢了畫面？
4. iOS 列表捲動卡頓的常見原因是什麼？
5. Android 列表捲動卡頓的常見原因是什麼？
6. 行情列表每秒更新幾十次，怎麼做才不會卡？

### 1. 卡頓是什麼？一格畫面有多少時間可以用？

卡頓就是**某一格畫面沒有在預定的時間內準備好，所以螢幕只好繼續顯示上一格**，使用者就會看到畫面停頓一下。一格畫面能用的時間，由螢幕的更新頻率決定：

| 螢幕更新頻率 | 一格畫面的時間 | 常見裝置 |
|---|---|---|
| 60Hz（Hz 是赫茲，每秒更新幾次） | 約 16.67 毫秒 | 一般手機 |
| 90Hz | 約 11.1 毫秒 | 部分 Android |
| 120Hz | 約 8.33 毫秒 | 支援 ProMotion（Apple 的高更新率螢幕）的 iPhone、部分 Android |

這個時間要同時容納兩段工作：App 在**主執行緒**（負責處理觸控、更新畫面的那一條執行緒）把這一格要畫的內容準備好，再交給 GPU（graphics processing unit，負責繪圖的處理器）完成繪製。任何一段超時，這一格就來不及，這就是掉幀。

兩個平台各有量化的方式：

- **iOS 叫 hitch**：畫面比預期的時間晚顯示。Apple 用 **hitch time ratio**（每秒累積了幾毫秒的延誤）分級，小於 5 ms/s 算良好，5 到 10 ms/s 是警告，大於 10 ms/s 就嚴重（出自 Apple 2020 年開發者大會 WWDC20 的分級）。例如一秒鐘內所有延誤加起來是 8 毫秒，就是 8 ms/s，屬於警告。
- **Android 叫 jank**：畫面超過期限才完成。Google Play Console 的 Android vitals（Google Play 提供的 App 品質儀表板）把畫面分成 slow frame（單格超過 16 毫秒）與 frozen frame（單格超過 700 毫秒，使用者幾乎能感覺到畫面「凍住」）。

為什麼不直接看平均 FPS（frames per second，每秒畫面數）？因為使用者感受到的是「偶爾一次的長停頓」，不是平均值。平均 58 FPS 看起來很好，但如果中間夾著一次 200 毫秒的停頓，使用者一定會覺得卡。

> [!TIP]
> 面試時可以這樣說：「60Hz 螢幕一格畫面大約 16.7 毫秒，120Hz 只有 8.3 毫秒，主執行緒和 GPU 都要在這個時間內完成。我不會只看平均 FPS，iOS 看 hitch time ratio，Android 看 slow frame 和 frozen frame 的比例，因為使用者感受到的是偶發的長停頓。」

### 2. 一格畫面是怎麼做出來的？commit hitch 和 render hitch 差在哪？

要找卡頓的原因，先要知道**一格畫面要經過哪幾個階段，卡頓就是其中某一個階段超時**。

**iOS（Apple 稱為 render loop）：**

1. **App 處理事件**：收到觸控或計時器，在主執行緒執行你的程式（更新資料、改畫面內容）。
2. **commit**：App 把這一格畫面變動的部分（版面計算、繪製、整理圖層）打包，送給 render server。
3. **render server 與 GPU**：render server 是系統的另一個行程，負責把所有 App 的圖層合成，轉成 GPU 的指令，由 GPU 畫出來，最後顯示在螢幕上。

所以 Apple 把 hitch 依原因分成兩種：

- **commit hitch**：步驟 1 和 2 太久，也就是 App 自己在主執行緒花太多時間。例如 cell 設定時在格式化字串、同步解碼大圖、版面計算太複雜。
- **render hitch**：步驟 3 太久，也就是 render server 或 GPU 處理不完。例如離屏渲染（offscreen rendering，第 4 題說明）、太多半透明圖層疊在一起、過大的圖層。

**Android 的對應關係：**

1. **UI thread（Android 的主執行緒）**：處理觸控，做 measure、layout、draw（計算版面並記錄要畫的內容）。
2. **RenderThread**：App 內專門負責把要畫的內容轉成 GPU 指令的執行緒。
3. **GPU 與 SurfaceFlinger**：GPU 繪製，SurfaceFlinger 是系統負責把各個 App 畫面合成的元件。

兩種問題的修法完全不同：主執行緒超時，就要減少主執行緒的工作量（把格式化、解碼移到背景）；GPU 超時，就要減少繪圖的複雜度（避免離屏渲染、減少圖層重疊）。如果沒有先判斷是哪一種，很容易花了很多時間最佳化到錯誤的地方。

> [!TIP]
> 面試時可以這樣說：「我會先判斷是 commit hitch 還是 render hitch。commit hitch 是 App 的主執行緒做太多事，要把工作移出主執行緒；render hitch 是 GPU 或 render server 超時，要處理離屏渲染和圖層混合。Android 對應的是 UI thread 超時，還是 RenderThread 與 GPU 超時。」

### 3. 怎麼找出是誰拖慢了畫面？

結論：**不要憑感覺猜，用工具擷取一段 trace（把每個執行緒在每一刻做什麼完整記錄下來），找出超時的那一格，再看那一格的主執行緒在做什麼。** 流程在兩個平台是一樣的：

1. 在**實機、release 型態**重現問題。模擬器和 debug build 的數字沒有參考價值；也要包含支援高更新率的裝置，因為 120Hz 的時間預算只有一半。
2. 用工具擷取 trace。
3. 判斷超時發生在主執行緒還是 GPU，找出具體是哪一格、哪個函式。
4. 修正。
5. 用同一個基準重新量測確認有改善，並在線上持續監控。

**iOS 的工具：**

- **Instruments 的 Animation Hitches 範本**（Xcode 12 起）：時間軸上會標出每一次 hitch，並標明是 commit hitch 還是 render hitch。選取那一段，就能在 Time Profiler 看主執行緒當時的呼叫堆疊。
- **模擬器 Debug 選單的 Color Blended Layers 與 Color Offscreen-Rendered**：用顏色標出哪些圖層有混合、哪些有離屏渲染。
- **Hang**：主執行緒超過 250 毫秒沒有回應，比 hitch 更嚴重。Xcode Organizer 和 Instruments 都可以看。
- **線上資料**：Xcode Organizer 的 Scrolling 指標、MetricKit（Apple 收集真實使用者裝置效能資料的框架）的 `MXAnimationMetric`（裡面有 `scrollHitchTimeRatio`）。

**Android 的工具：**

- **Perfetto**：系統追蹤工具，把所有執行緒每一毫秒在做什麼畫成時間軸。從 Android 12 起有 FrameTimeline，可以看到每一格畫面「預期何時完成」和「實際何時完成」，超時的會標出來。選中那一格，看 UI thread 上的 `Choreographer#doFrame`（每一格畫面的主要工作），底下會有 measure、layout、draw，以及 `RV OnBindView`（RecyclerView 綁定資料）各自花了多久。
- **Android Studio Profiler**：本機操作較方便的介面。
- **Macrobenchmark 的 `FrameTimingMetric`**：寫一個自動化測試去捲動列表，輸出每一格畫面耗時的統計，可以放進 CI（continuous integration，每次提交程式碼就自動建置與測試）比較有沒有退步。
- **JankStats**（`androidx.metrics:metrics-performance` 函式庫）：在實機上收集每一格畫面的耗時，並用 `PerformanceMetricsState` 標記「正在捲動」等狀態，才知道卡頓是發生在使用者做什麼的時候。
- **Android vitals**：Google Play Console 上的整體線上資料，只告訴你有多少 frame 慢，不告訴你是誰造成的。

> [!TIP]
> 面試時可以這樣說：「我先在實機的 release 型態重現，iOS 用 Instruments 的 Animation Hitches，Android 用 Perfetto 的 FrameTimeline，找出超時的那一格，看主執行緒在做什麼。修完用同一個基準比較，線上用 MetricKit、JankStats 和 Android vitals 監控。」

### 4. iOS 列表捲動卡頓的常見原因是什麼？

最常見的有四類。

**(1) 離屏渲染（offscreen rendering）。** 一般情況下，GPU 直接把每個圖層畫到螢幕上。離屏渲染是指 GPU 得先把內容畫到另一塊暫時的緩衝區，處理完再合成回來，需要額外的記憶體，也要來回切換繪圖狀態，成本很高。常見的觸發情況：

- 對含有圖片內容的 view 同時設 `layer.cornerRadius` 和 `layer.masksToBounds = true`（圓角加裁切）。
- 設了陰影（`shadowOpacity`、`shadowRadius`），卻沒有提供 `layer.shadowPath`（系統得逐像素算出陰影的形狀）。
- 使用 `layer.mask`。
- 把 `shouldRasterize = true` 用在每一格都在變動的圖層，系統每次都要重新把圖層變成點陣圖。這個設定只適合內容不變的圖層。

修法：預先把圓角裁在圖片上、提供 `shadowPath`、改用 `CAShapeLayer` 或預先畫好的遮罩圖，用模擬器的 Color Offscreen-Rendered 檢查。

**(2) 過度混合（blending）。** 半透明的圖層疊很多層時，GPU 要把每一層的顏色計算混在一起。修法：能不透明就設 `isOpaque = true`，背景給實色，用 Color Blended Layers 檢查（紅色表示有混合）。

**(3) 主執行緒做重活。** 最典型的是在 `cellForRowAt` 或 cell 設定時才格式化價格字串、同步解碼大圖、版面太複雜。修法：價格、漲跌幅字串在 UI model 階段（把資料整理成畫面要用的格式）就在背景準備好；圖片在背景解碼（`UIImage` 的 `preparingForDisplay()` 或 `byPreparingThumbnail(ofSize:)`，iOS 15 起）。

**(4) 更新方式不佳。** 每筆行情更新都呼叫 `reloadData()`，整個列表所有 cell 都重新建立。修法：用 diffable data source（比對新舊資料、只更新有差異的項目的 UIKit 機制）搭配 `reconfigureItems`（iOS 15 起，只重設既有 cell 的內容，不重新建立），或使用 IGListKit（Instagram 開源的列表更新函式庫，同樣是比對新舊資料）。

SwiftUI 的列表也有類似的問題：`body` 計算太重、用 `AnyView`（會讓 SwiftUI 無法判斷哪裡有變動）、列表項目沒有穩定的 `id`。對策是把 View 拆小、用具體型別、用 `LazyVStack`（只建立畫面上看得到的項目）、給穩定的 identity。

> [!TIP]
> 面試時可以這樣說：「iOS 列表卡頓我會先用 Animation Hitches 判斷是 commit 還是 render。render 那邊最常見的是離屏渲染，像圓角加 masksToBounds、沒有 shadowPath 的陰影。commit 那邊則是 cell 設定時在主執行緒格式化或解碼圖片，我會預先在背景做好，更新用 diffable 加 reconfigureItems，不用 reloadData。」

### 5. Android 列表捲動卡頓的常見原因是什麼？

Android 的 RecyclerView 會重複使用畫面外的項目（ViewHolder），每次顯示一個項目都會呼叫 `onBindViewHolder` 綁定資料。這個函式是在 UI thread 執行，**任何耗時的工作放在這裡，都會直接變成卡頓**。常見原因：

| 原因 | 修法 |
|---|---|
| `onBindViewHolder` 做重活：格式化價格、解析資料、同步載入圖片 | 資料先整理成 UI model（畫面要用的格式），價格字串預先算好；圖片載入函式庫依 View 的大小解碼，不要載入原圖 |
| 用 `notifyDataSetChanged()` 更新，所有可見項目全部重新綁定 | 用 `ListAdapter` 搭配 `DiffUtil`（比對新舊清單，差異在背景執行緒計算），並用 `getChangePayload` 只更新有變的欄位 |
| 高頻更新時，預設的 change animation 讓項目一直淡出淡入、閃爍 | `(itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false` |
| overdraw（同一個像素被畫了很多次，例如多層背景疊在一起） | 開發人員選項的「偵錯 GPU 過度繪製」找出來，移除多餘的背景 |
| 版面層級太深、巢狀的 `weight` | 改用 `ConstraintLayout`，減少巢狀 |
| 剛安裝後第一次捲動特別卡 | 程式碼還沒編譯成機器碼，可以加 Baseline Profile（一份記錄常用程式碼的清單，作法見啟動效能單元），讓系統在安裝時預先編譯 |

如果是 Jetpack Compose 的 `LazyColumn`，概念相同：給每一列穩定的 `key`，這樣資料變動時，只有真的有變的那一列會重新組合（recompose）。

> [!TIP]
> 面試時可以這樣說：「RecyclerView 的 onBindViewHolder 在 UI thread 執行，所以格式化、解析、載圖都不能放在這裡。更新我會用 ListAdapter 加 DiffUtil，配合 payload 只重綁變動的欄位，並關掉 change animation。我會用 Perfetto 確認是 bind 慢，還是版面計算或繪製慢。」

### 6. 行情列表每秒更新幾十次，怎麼做才不會卡？

結論：**不要讓每一筆行情更新都直接觸發一次畫面更新。** 螢幕一秒鐘最多只畫 60 或 120 次，一秒鐘更新 50 次、每次都重畫整個列表，只是浪費主執行緒的時間。

做法有五點：

1. **合併更新**：資料層先在背景依交易對代號（symbol）合併，只保留每檔最新的值，每一格畫面（或更低的頻率）才提交一次給畫面。
2. **局部更新**：只更新有變化的列。iOS 用 diffable 的 `reconfigureItems`，Android 用 payload，不要重建整個列表。
3. **預先格式化**：價格、漲跌幅字串在背景算好，畫面只負責顯示。
4. **關掉不必要的動畫**：Android 的 change animation；iOS 的隱式動畫（改圖層屬性時系統自動加上的動畫），可以用 `CATransaction.setDisableActions(true)` 關掉。
5. **離開畫面就停止更新**：App 進入背景或使用者切到別的頁面，就不要再更新列表。

```swift
// iOS（iOS 15 以上）：收到一批變動的交易對之後，只重設這幾個 cell 的內容
var snapshot = dataSource.snapshot()
// 不重新建立 cell，成本比 reloadItems 低
snapshot.reconfigureItems(changedRows)
// 不要列表動畫，避免閃爍
dataSource.apply(snapshot, animatingDifferences: false)
```

```kotlin
// 節錄示意：TickUi 是畫面用的資料，
// TickVH 是 ViewHolder（含 bind、bindPrice 兩個方法）
class TickAdapter : ListAdapter<TickUi, TickVH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        TickVH.create(parent)

    override fun onBindViewHolder(h: TickVH, pos: Int) = h.bind(getItem(pos))

    // 有 payload 時，只更新價格欄位；其他欄位不動
    override fun onBindViewHolder(
        h: TickVH,
        pos: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PRICE)) {
            h.bindPrice(getItem(pos))
        } else {
            onBindViewHolder(h, pos)
        }
    }

    companion object {
        private const val PRICE = "price"
        private val DIFF = object : DiffUtil.ItemCallback<TickUi>() {
            // 是不是同一檔
            override fun areItemsTheSame(a: TickUi, b: TickUi) =
                a.symbol == b.symbol

            // 內容有沒有變
            override fun areContentsTheSame(a: TickUi, b: TickUi) = a == b

            // 只有價格字串不同、其他欄位都相同時，回傳 PRICE，
            // onBindViewHolder 就只重綁價格；
            // 其他欄位也變了就回傳 null，整列重綁
            override fun getChangePayload(a: TickUi, b: TickUi) =
                if (a.copy(priceText = b.priceText) == b) {
                    PRICE
                } else {
                    null
                }
        }
    }
}
```

合併更新與背壓（生產資料的速度比消費快時怎麼處理）的詳細做法，在高頻行情串流單元會完整說明。

> [!TIP]
> 面試時可以這樣說：「行情每秒更新很多次，但螢幕一秒只畫 60 次，所以我不讓每筆更新直接進畫面。資料層依交易對合併，每一格畫面或固定間隔提交一次，更新時 iOS 用 reconfigureItems、Android 用 payload，只更新有變的格子，價格字串預先在背景格式化。」

> [!FIELD]
> 在一個社交 App 和一個即時行情 App 的經驗：社交 App 的 iOS 列表用 IGListKit 與 diffable data source，都是比對新舊資料、只更新有變的項目；圖片用 Kingfisher（Swift 的圖片下載與快取函式庫），feed 的預先載入有明確的上限。即時行情 App 的 SwiftUI 畫面用 `LazyVStack`，圖表（iOS 用 DGCharts，Android 用 MPAndroidChart，都是圖表函式庫）都關掉了數值文字的繪製，Android 端還會比對資料有沒有變，沒變就不重畫。
> 面試時可以說這些是列表更新和預先載入的設計選擇；換成交易所 App，我會先用 Instruments 的 Animation Hitches 與 Perfetto、Macrobenchmark 建立基準，再用 JankStats 和 MetricKit 做線上監控。

## 實際演練

> [!LAB]
> **打開**：iOS：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced02Jank**。Android：Android Studio 打開 `labs/android` 資料夾，Run configuration 選 **app**，按 ▶，App 首頁進入 **Advanced02Jank**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced02-Jank/`，解答在 `labs/ios/Solution/Advanced/Advanced02-Jank/`；Android 你寫在 `labs/android/practice/advanced/advanced02-jank/`，解答在 `labs/android/solution/advanced/advanced02-jank/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **注意**：卡頓的真實數字要在實機、release 型態用 Instruments 或 Perfetto 量（第 3 題）。這裡的演練改用「圖層設定的數量」「重新綁定的次數」「畫面記錄」這些不受機器快慢影響的數字，來確認你寫的是對的。

### 演練 1：平均 FPS 很好，使用者卻覺得畫面凍住過（第 1 題）

**檔案**：`L01_FrameStats.swift`（iOS）、`L01_FrameStats.kt`（Android）

**情境**：行情列表捲動 10 秒的畫面記錄：60Hz 螢幕（一格 16.7 毫秒），共 600 格，其中兩格花了 40 毫秒，另外一格花了 800 毫秒（畫面凍住將近一秒），其餘 597 格都剛好 16.7 毫秒。這就是 MetricKit、Instruments、JankStats 會給你的那種逐格資料。

**怎麼寫**

1. **Bug 版**：iOS 與 Android 都一樣：`judge` 只算平均 FPS（格數 ÷ 總秒數），大於等於 55 就判定「良好」。
2. **修正版**：
   - iOS：算 hitch time ratio：每一格超出 16.7 毫秒的部分加起來，除以總秒數；小於 5 ms/s 良好、5 到 10 警告、大於 10 嚴重。
   - Android：數 slow frame（間隔超過一格預算的 1.5 倍）與 frozen frame（超過 700 毫秒）；有任何一格 frozen 判定「嚴重」，沒有的話 slow frame 超過 5% 判定「警告」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 平均 55.3 FPS，最長一格 800 ms，判定「良好」（錯的） |
| 修正版 | iOS：hitch time ratio 76.5 ms/s，判定「嚴重」；Android：slow frame 3 格（0.5%）、frozen frame 1 格，判定「嚴重」 |

**為什麼**：使用者感受到的是偶爾一次的長停頓，不是平均值。一格 800 毫秒的凍結，被 597 格正常的畫面一稀釋，平均 FPS 還是很好看。hitch time ratio 與 slow／frozen frame 都是把「超時的部分」單獨算出來，所以看得到這次凍結。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L01_FrameStats.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L01_FrameStats.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced02-jank/L01_FrameStats.kt#bug
```

```lab-solution
android/solution/advanced/advanced02-jank/L01_FrameStats.kt#fixed
```

### 演練 2：100 張卡片一起捲動，GPU 一直在做離屏渲染（第 4 題）

**檔案**：`L02_OffscreenAudit.swift`（只有 iOS）

**情境**：行情頁的每一格是一張價格卡片：圓角的卡片底、有陰影、圓形頭像、一個每秒都在變的漲跌標籤。演練用 `A02LayerAudit` 數 100 張卡片的圖層設定，規則就是第 4 題列的那幾種，也是模擬器 Debug 選單的 Color Offscreen-Rendered 與 Color Blended Layers 在找的條件。

**怎麼寫**

1. **Bug 版**：`style` 裡：卡片背景用半透明的白色（alpha 0.95）、`cornerRadius` 12；卡片有陰影（`shadowOpacity`、`shadowRadius`、`shadowOffset`），不設 `shadowPath`；頭像 `cornerRadius` 28 加 `masksToBounds = true`；漲跌標籤 `shouldRasterize = true`。
2. **修正版**：背景改成不透明的實色（`.white`、`isOpaque = true`）；陰影一樣，再加 `shadowPath = UIBezierPath(roundedRect:cornerRadius:).cgPath`；頭像用寫好的 `circleImage` 預先把圓形裁在圖片上，不設 `masksToBounds`；標籤不開 `shouldRasterize`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 離屏渲染的觸發點 300 個（圓角加 masksToBounds、陰影沒有 shadowPath、shouldRasterize 各 100 個），100 張卡片背景半透明 |
| 修正版 | 觸發點 0 個、混合 0 張；第一張卡片的圓角 12、陰影 0.25、shadowPath 有、頭像還在 |

**在畫面上看**：App 在模擬器執行，進入演練 2，操作區有 30 張卡片可以捲動。模擬器選單列 Debug › Color Offscreen-Rendered 打勾，Bug 版的卡片會蓋上黃色，修正版沒有；再勾 Color Blended Layers，Bug 版的卡片底會變紅色。真機上用 Instruments 的 Animation Hitches 範本，離屏渲染造成的是 render hitch。

**為什麼**：離屏渲染是 GPU 得先把內容畫到另一塊暫時的緩衝區，處理完再合成，單一一個感覺不到，上百個 cell 同時捲動就會超過 GPU 的時間預算。修法的共同想法是「把工作從每一格畫面移到事先做好」：陰影的形狀事先用 `shadowPath` 告訴系統、圓形事先裁在圖片上、不透明的背景不用混合。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L02_OffscreenAudit.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L02_OffscreenAudit.swift#fixed
```

### 演練 3：每來一筆行情，每一列都重新綁定（第 5 題）

**檔案**：`L03_DiffPayload.kt`（只有 Android）

**情境**：行情列表同時顯示 12 列，每次更新只有前 3 檔的價格文字變了，共更新 10 次。RecyclerView 每被通知「這一列要重新綁定」，就會在 UI thread 呼叫一次 `onBindViewHolder`。演練用 `FakeRecycler`（實作 `ListUpdateCallback`）記下被通知了幾次。需要 `androidx.recyclerview` 套件（已經加在這一區的 `build.gradle.kts`）。

**怎麼寫**

1. **Bug 版**：每次更新都呼叫 `recycler.notifyDataSetChanged()`。
2. **修正版**：用 `DiffUtil.calculateDiff(DiffUtil.Callback)` 比對新舊清單：`areItemsTheSame` 比 `symbol`、`areContentsTheSame` 比整份內容、`getChangePayload` 在「只有價格文字不同」時回傳 `PRICE`；再 `result.dispatchUpdatesTo(recycler)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 列表一共重新綁定 120 列（10 次 × 12 列），其中只更新價格欄位的 0 列 |
| 修正版 | 一共重新綁定 30 列（3 列 × 10 次），30 列都只更新價格欄位；新增、移除、移動：0 次 |

**為什麼**：`notifyDataSetChanged()` 告訴 RecyclerView「什麼都可能變了」，所有可見的項目全部重新綁定，而 `onBindViewHolder` 在 UI thread 執行。DiffUtil 比對出實際有變的位置與欄位，只通知那幾列；`ListAdapter` 內部就是這樣做，而且把比對放到背景執行緒。真的 App 用 Perfetto 錄捲動，在 UI thread 的 `Choreographer#doFrame` 底下看 `RV OnBindView` 的次數，就能確認改善。

**Android 解答**

```lab-solution
android/solution/advanced/advanced02-jank/L03_DiffPayload.kt#bug
```

```lab-solution
android/solution/advanced/advanced02-jank/L03_DiffPayload.kt#fixed
```

### 演練 4：行情每跳一次，列表每個 cell 都重新設定（第 6 題）

**檔案**：`L04_ReconfigureItems.swift`（只有 iOS；Android 對應演練 3，以及 Kotlin08ComposePerformance 的演練 4）

**情境**：行情列表有 40 檔，用 `UICollectionView` 的 list 版面加 diffable data source，畫面一次看得到約 11 列。每次更新只有前 3 檔的價格變了，共更新 10 次。演練把 `UICollectionView` 放進螢幕外的視窗真的跑起來，數 cell registration 的 handler 被呼叫幾次。

**怎麼寫**

1. **Bug 版**：每次更新都 `dataSource.applySnapshotUsingReloadData(dataSource.snapshot())`，等於 `reloadData()`。
2. **修正版**：取目前的 snapshot，對有變的代號呼叫 `snapshot.reconfigureItems(changed)`（iOS 15 起），再 `dataSource.apply(snapshot, animatingDifferences: false)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | cell 一共設定了約 110 次（每次畫面上約 11 個 cell 全部重新設定） |
| 修正版 | cell 一共設定了 30 次（3 個 × 10 次），畫面上的價格仍然是最新的（不一樣的 cell：0 個） |

**為什麼**：每設定一個 cell，主執行緒就要多做一份建立內容的工作。`reconfigureItems` 只重設既有 cell 的內容，不重新建立，也只動有變的那幾個；`animatingDifferences: false` 避免高頻更新時列表一直在動畫。價格字串事先在背景格式化好、合併更新讓畫面一格最多更新一次，是另外兩個要一起做的步驟，見 Swift08SwiftUIIdentity 的演練 7（SwiftUI 版）與高頻行情串流單元。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L04_ReconfigureItems.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced02-Jank/L04_ReconfigureItems.swift#fixed
```

第 2、3 題是診斷的流程題（先判斷是 commit hitch 還是 render hitch，再用 Instruments 的 Animation Hitches 或 Perfetto 的 FrameTimeline 找出超時的那一格），要在實機、release 型態用工具擷取 trace，沒有可以自動檢查的 bug，沒有演練；工具的開法與怎麼看，寫在演練 2 與演練 3 的步驟。

## 常見陷阱

- **在模擬器或 debug build 量測卡頓。** 數字不能代表實際使用者的體驗。要用實機、release 型態；ProMotion 與一般 60Hz 的裝置時間預算不同，不能混在一起比。
- **用 `reloadData()` 或 `notifyDataSetChanged()` 處理每一筆行情更新。** 所有可見的項目都會重新建立或綁定，高頻更新時一定卡。改用 diffable 加 `reconfigureItems`，或 `ListAdapter` 加 `DiffUtil` 與 payload。
- **把圓角、陰影、模糊全部加在每個 cell 上，卻沒有檢查離屏渲染。** 單一一個沒感覺，上百個 cell 同時捲動就會超過 GPU 的時間預算。
- **在 `onBindViewHolder` 或 `cellForRowAt` 裡每次都建立新的 `NumberFormatter` 或 `DecimalFormat`。** 建立格式化物件的成本很高，而且 `DecimalFormat` 不是執行緒安全的（多個執行緒同時使用會出錯）。格式化要在 UI model 階段預先做好。
- **沒有判斷是 commit hitch 還是 render hitch，就開始亂改。** 主執行緒的問題去改 GPU 的設定，沒有任何效果。要先用 trace 確認。
- **`shouldRasterize = true` 用在每一格都在變動的圖層。** 系統每次都要重新把圖層變成點陣圖，反而更慢。只適合內容不變的圖層。

## 面試官想聽到的

1. 知道一格畫面的時間預算（16.67 毫秒與 8.33 毫秒），以及 hitch、jank 的定義，並能區分主執行緒（commit、UI thread）的問題和 GPU（render）的問題。
2. 熟悉診斷工具：Instruments 的 Animation Hitches、Perfetto 的 FrameTimeline、Macrobenchmark、JankStats、MetricKit、Xcode Organizer 與 Android vitals，而且是先量測再修改。
3. 對高頻更新的列表有具體做法：合併更新、局部更新、預先格式化、關掉不必要的動畫、離開畫面就停止。
4. 修完會用同一個基準驗證，並且有線上監控，而不是只在自己的手機上「感覺變順」。

> [!WARNING]
> 面試官常追問：「ProMotion 120Hz 的裝置上，行情列表要每一格畫面都更新嗎？」不要只回答「要」。比較好的回答是：使用者的眼睛不可能分辨每秒 120 次的價格跳動，資料層合併之後，用固定間隔（例如每 100 到 250 毫秒）更新畫面通常就夠了，更新次數少，才有餘裕把捲動做得順。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 一格畫面的時間 | 60Hz 約 16.67 毫秒，120Hz 約 8.33 毫秒 | 同左，90Hz 約 11.1 毫秒 |
| 問題的分類 | commit hitch（App 的主執行緒）、render hitch（render server 與 GPU） | UI thread、RenderThread、GPU |
| 檢查離屏渲染與過度繪製 | 模擬器的 Color Offscreen-Rendered、Color Blended Layers | 開發人員選項的偵錯 GPU 過度繪製 |
| 本機量測 | Instruments 的 Animation Hitches、XCTest 的效能指標 | Perfetto 的 FrameTimeline、Macrobenchmark 的 `FrameTimingMetric` |
| 線上量測 | MetricKit 的 `MXAnimationMetric`、Xcode Organizer | JankStats、Android vitals（slow frame 與 frozen frame） |
| 只更新有變的項目 | diffable data source 加 `reconfigureItems`、IGListKit | `ListAdapter` 加 `DiffUtil` 加 payload |
| 關閉不必要的動畫 | `CATransaction.setDisableActions(true)` | `supportsChangeAnimations = false` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 卡頓（iOS 稱 hitch，Android 稱 jank） | hitch／jank | ヒッチ／ジャンク |
| 畫面更新頻率 | refresh rate | リフレッシュレート |
| 離屏渲染 | offscreen rendering | オフスクリーンレンダリング |
| 過度繪製 | overdraw | オーバードロー |
| 主執行緒 | main thread（Android 稱 UI thread） | メインスレッド |
| 局部更新 | partial update | 部分更新 |
| 追蹤記錄（記錄每個執行緒在每一刻做什麼） | trace | トレース |
