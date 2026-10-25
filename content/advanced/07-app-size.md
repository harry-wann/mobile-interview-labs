---
id: advanced-app-size
title: App 體積怎麼量、怎麼縮小：下載大小與安裝大小、精簡依賴、圖片格式、R8 與 ABI
summary: 面試問「你怎麼讓 App 變小」時，第一件事是先說清楚是哪一種大小。這個單元說明下載大小和安裝大小的差別、iOS 與 Android 各自的縮小手段（精簡第三方依賴、圖片與動畫格式、R8、ABI 篩選、延後下載），以及縮小之後怎麼確認沒有弄壞東西。
priority: P1
day: 3
tags: [app-size, app-thinning, r8, aab, abi, asset-catalog, dependencies]
---

## 重點講解

這個單元回答六個面試常見問題：

1. App 體積有哪幾種？為什麼說「變小了」一定要先講是哪一種？
2. iOS 要怎麼縮小？
3. Android 要怎麼縮小？
4. 為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？
5. 靜態連結和動態 framework，對 iOS 的體積有什麼影響？（比較少考，被問到能答就好）
6. 縮小體積有什麼風險？怎麼確認沒有弄壞東西？

### 1. App 體積有哪幾種？為什麼說「變小了」一定要先講是哪一種？

**App 的大小至少有三種：開發端上傳的檔案大小、使用者實際下載的大小（下載大小），以及安裝到手機後佔用的大小（安裝大小）。三個數字不一樣，講「變小了 N MB」時一定要說是哪一個。**

| | iOS | Android |
|---|---|---|
| 上傳給商店的檔案 | `.ipa`（IPA，iOS App Store Package，App 的壓縮封裝檔） | `.aab`（Android App Bundle，上架 Google Play 的標準格式） |
| 使用者實際下載 | 下載大小：App Store 依使用者的裝置，只送那台裝置需要的部分 | Google Play 依裝置產生 APK（Android Package，Android 的安裝檔）：一個基本 APK，加上依 ABI（原生函式庫對應的 CPU 架構；CPU 是手機的中央處理器，第 3 題會說明）、螢幕解析度、語系拆出來的 config split（小的附加安裝檔） |
| 安裝到手機之後 | 安裝大小（下載後解壓縮、展開，通常比下載大小大） | 安裝大小 |

為什麼會這樣：App Store 與 Google Play 都不是把你上傳的那一包原封不動丟給使用者。

- iOS 有一組機制叫 App Thinning（App 瘦身）。最常用的是 slicing：同一個 App 的資源裡，同時放了 @2x 與 @3x 的圖片（不同螢幕密度），iPhone 15 只會下載它需要的那一份。另一個是 On-Demand Resources（隨選資源）：把不常用的內容放在 App Store 上，等到需要時才下載。以前還有 Bitcode，但 Xcode 14 之後已經移除。
- Android 用 AAB 上架。Google Play 會依使用者的手機，組出只含「這台手機需要的程式碼、原生函式庫、解析度、語系」的 APK。

所以只看開發端的檔案大小會誤導。情境：團隊把 AAB 縮小了 30 MB，主管很高興，但使用者的下載大小可能只少幾 MB，因為那 30 MB 裡有一大部分，本來就不會送到使用者的手機上。反過來，圖片放錯位置，沒被 slicing 處理，每位使用者都會多下載。

使用者在乎的是兩件事：**下載大小**（影響要不要用行動網路下載、願不願意下載）和**安裝大小**（影響手機剩餘空間，空間不夠時，使用者常常先刪最大的 App）。

怎麼量：

- iOS：Xcode 做完 Archive 後，匯出 Ad Hoc 或 Development 版本時，把 App Thinning 選為 All compatible device variants，會產生 App Thinning Size Report，列出每一種裝置的壓縮後（接近下載大小）與解壓縮後（安裝大小）的大小。
- Android：Google Play Console 的 App size 報告可以看各裝置的下載大小與安裝大小。在本機用 bundletool（Google 提供的 AAB 處理工具）估算：

```bash
# 把 AAB 轉成針對各種裝置的 APK 集合，再估算下載大小
bundletool build-apks --bundle=app.aab --output=app.apks
bundletool get-size total --apks=app.apks
# 輸出的是各種裝置組合下，下載大小的最小值與最大值（單位 bytes）
```

> [!TIP]
> 面試時可以這樣說：「講體積要先分清楚是上傳的檔案、使用者的下載大小，還是安裝大小，三個數字不一樣。我會用 App Thinning Size Report 和 Play Console 的 App size 看真實的下載與安裝大小，不只看 IPA 或 AAB 的大小。」

### 2. iOS 要怎麼縮小？

**我會先看資源（圖片、動畫、影片、字型），再看第三方依賴，最後才調編譯器設定。** 因為資源和依賴通常占最多空間，而且改了不影響執行效能；編譯器設定的收益比較小，還可能改變效能。

**手段一：圖片放進 asset catalog，不要散落在 bundle 裡。** asset catalog（Xcode 的 Assets.xcassets，統一管理圖片與顏色的容器）裡的圖片才會被 slicing，使用者只下載自己螢幕需要的那一份。直接把 `.png` 檔丟進專案當 loose 檔案（散落的檔案），每位使用者都會完整下載。圖示優先用向量格式（asset catalog 的向量圖或 SF Symbols，Apple 提供的系統圖示庫），一個檔案就能放大縮小。

**手段二：換掉太大的動畫與圖片格式。** GIF 的壓縮效率很差，同樣的動畫換成 animated WebP（WebP 是 Google 開發的圖片格式，通常比 GIF、PNG 小很多，iOS 14 之後系統可以解碼），或影片，常常能縮小一個數量級。要注意品質：交易所的 KYC（know your customer，身分驗證）流程要拍證件、圖表要看得清楚，這些不能為了縮小而壓到糊掉。

**手段三：移除沒用到的東西。** 沒用到的圖片、字型、Lottie（用 JSON 描述的動畫）檔案、影片、舊的語系，還有沒人呼叫的程式碼與 import。這件事要靠工具找，不能憑印象。

**手段四：編譯器設定。** 在 Xcode 的 Build Settings 裡：

```text
# 編譯最佳化目標設為「縮小程式碼大小」，可能比 -O 稍慢，要量測
SWIFT_OPTIMIZATION_LEVEL = -Osize
# 連結時移除沒被用到的程式碼
DEAD_CODE_STRIPPING = YES
# 從 App 裡移除除錯用的符號資訊
# （dSYM 這份符號檔要另外保存，才看得懂 crash 報告）
STRIP_INSTALLED_PRODUCT = YES
```

`-Osize` 會讓編譯器優先縮小程式碼，而不是追求最快，所以改完要量一下效能，確認每秒更新很多次的畫面（例如行情列表）沒有變慢。

**手段五：延後下載。** 不是每位使用者都會用到的大型內容，可以放在 On-Demand Resources。比較新的做法是 Background Assets（iOS 16 開始提供的框架，讓系統在背景下載資源）。

**怎麼找出誰最大：** 在 Xcode 開啟 `Write Link Map File`，編譯後會得到一份文字檔，列出每個函式庫、每個檔案各占了多少位元組，再用工具或指令排序。

> [!TIP]
> 面試時可以這樣說：「iOS 我會先看資源：圖片是否在 asset catalog、GIF 能不能換成 animated WebP、有沒有沒用到的檔案；再用 link map 看各個第三方函式庫占多少；最後才調 -Osize 這類編譯設定，並量測效能有沒有受影響。」

### 3. Android 要怎麼縮小？

**Android 的核心手段是：用 AAB 上架，開啟 R8 縮減程式碼與資源，限制不需要的 ABI 與語系，精簡依賴。** 做完之後，要用 Play Console 或 bundletool 確認使用者真正的下載大小，而不是只看 AAB。

**R8 做了三件事。** R8 是 Android 的程式碼縮減工具，在 release build 時處理：

1. 縮減（shrink）：移除看起來沒被用到的類別與方法。
2. 混淆（obfuscate）：把類別名稱與方法名稱換成 `a`、`b` 這種短名字，檔案更小。
3. 最佳化（optimize）：合併類別、把小函式的內容直接展開到呼叫的地方等。

R8 的 full mode 比預設的更積極，從 Android Gradle Plugin（AGP，建置 Android App 的 Gradle 外掛）8.0 起預設開啟。越積極，縮得越小，但是 keep 規則（告訴 R8 哪些東西不要動）寫得不夠時，越容易發生「release 才出錯」的問題，細節在穩定性單元（App 為什麼會當掉）。

**資源縮減（resource shrinking）。** 會移除沒有被程式碼引用的圖片、字串等資源。設定如下，注意 `isShrinkResources` 要搭配 `isMinifyEnabled`：

```kotlin
android {
    defaultConfig {
        // 只打包這兩種 CPU 架構的原生函式庫
        // （arm64-v8a：64 位元 ARM；armeabi-v7a：32 位元 ARM）
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            // 開啟 R8：縮減、混淆、最佳化
            isMinifyEnabled = true
            // 移除沒被引用的資源（要先開 isMinifyEnabled）
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                // 自己寫的 keep 規則
                "proguard-rules.pro"
            )
        }
    }
}
```

資源縮減的陷阱：如果程式用名稱動態取資源，例如 `resources.getIdentifier("ic_$name", "drawable", packageName)`，工具靜態分析看不到引用，會把這些資源當成沒用到而刪掉，結果只有 release 版少圖示。解法是在 `res/raw/keep.xml` 保留，或改成明確列出的對應：

```text
<!-- res/raw/keep.xml：告訴資源縮減不要刪 ic_ 開頭的 drawable -->
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@drawable/ic_*" />
```

**ABI 篩選：效果要看量哪一個大小。** ABI（Application Binary Interface）可以先理解成「原生函式庫是為哪一種 CPU 編譯的」：`arm64-v8a` 是現在大多數手機的 64 位元 ARM，`armeabi-v7a` 是較舊的 32 位元 ARM，`x86`、`x86_64` 主要是模擬器。用 AAB 上架時，Google Play 本來就只送對應那台手機的 ABI，所以 `abiFilters` 縮小的主要是 AAB 與 universal APK（包含所有 ABI 的單一安裝檔）的大小。是否還要保留 32 位元的 `armeabi-v7a`，要看目標使用者的裝置。

**其他手段：**

- 語系過濾：只保留 App 支援的語系，避免第三方函式庫內建的幾十種語系字串全部被打包。
- 圖片與動畫：用 WebP、向量圖（VectorDrawable），動畫用 Lottie 取代一格一格的圖片序列。
- 延後下載：Play Feature Delivery（功能模組，需要時才下載）與 Play Asset Delivery（大型資源，需要時才下載）。
- 分析工具：Android Studio 的 APK Analyzer 可以看程式碼（dex，Android 存放編譯後程式碼的格式）、資源、原生函式庫各占多少。

> [!TIP]
> 面試時可以這樣說：「Android 我會開 R8 和 resource shrinking，限制不需要的 ABI 與語系，圖片改用 WebP 和向量圖。但 ABI 篩選主要縮小的是 AAB，因為 Play 本來就只送對應的 ABI，所以我會用 bundletool 或 Play Console 看使用者真正的下載大小。」

### 4. 為什麼精簡第三方依賴通常效果最大？怎麼找出誰最占空間？

**因為一個第三方 SDK（software development kit，第三方提供的開發套件）帶來的往往不只是它自己的程式碼，還有它的資源、原生函式庫，以及它又依賴的其他函式庫。** 你只用到其中一個功能，整包卻都跟著打包進 App。

情境：交易所 App 為了「看圖表、推播、分析、廣告」整合了幾個 SDK。其中一個廣告 SDK 為了支援各家廣告商，還要再加好幾個 mediation adapter（把不同廣告商接到同一個廣告介面的轉接元件）。另一個功能只用到某個函式庫一個小小的格式化工具，卻因此帶進一整套工具庫。這些加起來，常常比所有自己寫的程式碼還大。

怎麼做：

1. **列出清單，量每一個的貢獻。** iOS 用 link map 看每個函式庫占多少位元組；Android 用 APK Analyzer，再用 `./gradlew app:dependencies` 看依賴樹（A 依賴 B、B 又依賴 C）。
2. **分類。** 必要而且沒替代方案的（例如圖表函式庫、付費相關）留著；同一類功能有兩套以上的（例如兩個圖片載入函式庫、兩套分析），合併成一套；只用到一小部分功能的，改用系統內建 API，或自己寫幾十行程式碼取代。
3. **確認商業與合規影響。** 廣告、分析、同意流程（使用者同意蒐集資料）是商務需求，移除前要和產品確認，不能只因為它占空間就砍。
4. **一次處理一項，每項都記錄前後的數字。** 這樣之後才說得清楚每一項各貢獻了多少。

另一個好處：依賴越少，啟動時要載入的程式碼越少，遇到的版本相容問題也越少。

> [!TIP]
> 面試時可以這樣說：「我縮小體積通常先從第三方依賴下手，因為一個 SDK 常常帶進整套資源和它自己的依賴。做法是先量每個依賴的貢獻，再把重複功能合併、能用系統 API 取代的就取代，每一項記錄前後的數字，並且先和產品確認合規與商業需求。」

### 5. 靜態連結和動態 framework，對 iOS 的體積有什麼影響？（比較少考，被問到能答就好）

**靜態連結會把用到的程式碼直接放進 App 的執行檔，沒用到的部分可以在連結時被移除；動態 framework 則是整個放進 App，執行時才載入。** 兩者的取捨是體積和啟動速度。

- **靜態連結（static linking）：** 連結器（把編譯後的檔案組合成執行檔的工具）會做 dead code stripping，把沒被呼叫的函式去掉，所以通常比較小，啟動時要載入的檔案也比較少。
- **動態 framework（dynamic framework）：** 整個 framework 一起出貨，不管你用到幾個函式。好處是同一個 App 的主程式與多個 App Extension（推播、Widget、分享等附屬程式）可以共用同一份。

陷阱：假設主 App 與 3 個 App Extension 都使用同一個自家模組。如果把它從動態 framework 改成靜態庫，每個 target（主 App 與每個 extension，各自會編成獨立的執行檔）都會各自連結一份，程式碼被複製 4 份，App 反而變大。Xcode 15 的 mergeable libraries（可合併的函式庫）設計成兼顧兩者：開發時是動態的，release 時合併成靜態的。

決策時要同時看 size report 與啟動時間（動態 framework 要付載入成本，細節在「App 啟動為什麼慢」單元），不要只看其中一個。

> [!TIP]
> 面試時可以這樣說：「靜態連結可以把沒用到的程式碼移除，通常比較小也比較快啟動；但如果主 App 和好幾個 extension 都連結同一個靜態庫，會各複製一份，反而變大。所以我會用 size report 和啟動時間一起評估。」

### 6. 縮小體積有什麼風險？怎麼確認沒有弄壞東西？

**縮小體積的手段，大多會改變 release build 的行為或畫質，所以每一項改動都要用 release build 驗證。** 縮小是手段，不是目標；App 能正常用、畫面清楚、速度沒有變慢，才算成功。

常見風險：

- **R8 混淆讓 stack trace 看不懂。** 一定要保存每個版本的 mapping 檔（穩定性單元有說明），否則出事後無法還原。
- **R8 或資源縮減移除了需要的東西。** 用反射、動態取資源的地方，只在 release 才出錯。要有 release build 的 smoke test（冒煙測試：發布前快速跑一遍最關鍵的流程，例如開啟、登入、看行情、進入下單畫面）。
- **`-Osize` 可能比一般最佳化慢。** 要在每秒更新很多次的畫面上量測。
- **壓縮過頭。** 圖片壓太狠，KYC 的證件照或 K 線圖會變模糊；這不能為了體積犧牲。
- **刪了 SDK 之後功能或合規壞掉。** 例如廣告、分析、同意流程。

建議的流程：

1. 釐清目標：哪一種大小、哪個平台、目標值。
2. 量基準：App Thinning Size Report、`bundletool get-size`，記下版本與數字。
3. 找出前幾大的貢獻者。
4. 逐項處理，每項單獨提交並記錄前後數字。
5. 用 release build 跑 smoke test 與效能比對，確認沒有行為或效能退化。
6. 在 CI（continuous integration，每次提交程式碼就自動建置與測試的流程）裡加入體積檢查，超過門檻就提醒，避免之後悄悄變大。

> [!TIP]
> 面試時可以這樣說：「縮小體積的每一項我都會單獨做、單獨記錄前後數字，並且用 release build 跑 smoke test 和效能比對，確認沒有弄壞功能。我也會把體積加進 CI 的檢查，避免之後又悄悄變大。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Advanced07AppSize**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Advanced07AppSize**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Advanced/Advanced07-AppSize/`，解答在 `labs/ios/Solution/Advanced/Advanced07-AppSize/`；Android 你寫在 `labs/android/practice/advanced/advanced07-app-size/`，解答在 `labs/android/solution/advanced/advanced07-app-size/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

App 體積的手段大多是**建置期的工具**：App Thinning Size Report、APK Analyzer、R8 縮減、資源縮減、`abiFilters`，都是在 Xcode、Gradle 裡設定，執行時看不出差別，沒辦法在一個小專案裡「跑出 bug」。所以這個單元的演練挑的是**量的時候、算的時候會算錯**的部分：該量哪一種大小、誰才是真正占空間的、檢查怎麼才擋得住悄悄變大，以及一個執行時真的會差很多的東西：圖片解碼後的記憶體。演練裡的大小都是自己造的資料（單位 MB），重點是算法，不是數字。

### 演練 1：圖片搬進 asset catalog，.ipa 一樣大，下載卻少了（第 1 題）

**檔案**：`L01_DownloadSize.swift`（iOS）、`L01_DownloadSize.kt`（Android，情境是 `abiFilters`）

**情境**：iOS：一組促銷圖 promo（@2x 5 MB、@3x 11 MB）原本是散落在 bundle 裡的 `.png`，工程師把它搬進 asset catalog，比較搬移前後 .ipa 與 iPhone 15（@3x）、iPhone SE（@2x）的下載大小。Android：AAB 裡有 4 種 ABI 的原生函式庫（arm64-v8a 20 MB、armeabi-v7a 16 MB、x86 18 MB、x86_64 21 MB）與各密度的資源，工程師用 `abiFilters` 拿掉 x86 與 x86_64，比較 AAB 與 arm64-v8a 手機、armeabi-v7a 手機的下載大小。演練用未壓縮的大小模擬，只看相對差距。

**怎麼寫**

1. **Bug 版**：下載大小直接把所有檔案加起來（以為使用者下載的就是整個 `.ipa` 或 AAB）。
2. **修正版**：依裝置算。iOS：放在 asset catalog 的圖片只算這台裝置螢幕倍率的那一份，執行檔、framework、散落的檔案都要算；Android：不分 ABI 的檔案都要算，原生函式庫只算這支手機的 ABI，資源只算這支手機的螢幕密度。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：`.ipa 大小：117 MB → 117 MB`，兩台裝置都是「少 0 MB」，得到「沒有效果」的錯誤結論。Android：`AAB 大小：129 MB → 90 MB`，兩支手機都是「少 39 MB」，把 AAB 的差距當成使用者的差距 |
| 修正版 | iOS：`iPhone 15 下載大小：107 MB → 102 MB（少 5 MB）`、`iPhone SE 下載大小：96 MB → 85 MB（少 11 MB）`。Android：AAB 一樣 129 → 90 MB，但 `arm64-v8a 手機的下載大小：59 MB → 59 MB（少 0 MB）`、`armeabi-v7a 手機：52 MB → 52 MB（少 0 MB）` |

**為什麼**：商店不會把上傳的那一包原封不動送給使用者（第 1 題）。iOS 只會對 asset catalog 裡的圖片做 slicing，散落的檔案每個人都完整下載，所以搬進 asset catalog，`.ipa` 沒變，使用者的下載卻少了。Android 的 Google Play 本來就只送這支手機需要的 ABI，`abiFilters` 縮小的主要是 AAB 與 universal APK，使用者的下載大小要用 `bundletool get-size total` 或 Play Console 的 App size 報告看（第 3 題）。講「變小了」之前，先說是哪一種大小。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L01_DownloadSize.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L01_DownloadSize.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced07-app-size/L01_DownloadSize.kt#bug
```

```lab-solution
android/solution/advanced/advanced07-app-size/L01_DownloadSize.kt#fixed
```

### 演練 2：縮圖列表吃掉幾百 MB 記憶體（第 2、3 題）

**檔案**：`L02_DecodedMemory.swift`（iOS）、`L02_DecodedMemory.kt`（Android）

**情境**：KYC 頁的列表用 100 pt（dp）寬的縮圖顯示 20 張證件照，每張是 4000 × 3000 的 JPEG。有人想把 JPEG 品質降低來「省空間」。iOS 的演練真的用 ImageIO 解碼：先畫一張測試照片，再看解碼出來的點陣圖用掉多少位元組。Android 的 JVM 單元測試沒有 `BitmapFactory`，演練用公式算：ARGB_8888 一個像素 4 位元組，真正的 App 用 `inJustDecodeBounds` 先讀尺寸、再設 `inSampleSize` 解碼。

**怎麼寫**

1. **Bug 版**：iOS 用 `CGImageSourceCreateImageAtIndex` 把整張圖完整解碼；Android 的 `inSampleSize` 一律回傳 1。
2. **修正版**：iOS 改用 `CGImageSourceCreateThumbnailAtIndex`，選項放 `kCGImageSourceThumbnailMaxPixelSize: 300`（100 pt × 3 倍）、`kCGImageSourceCreateThumbnailFromImageAlways`、`kCGImageSourceShouldCacheImmediately`；Android 用官方文件的 `calculateInSampleSize`：從 1 開始，在寬高仍不小於顯示大小的條件下不斷乘以 2。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 品質降低後檔案變小（iOS 實測約 629 KB → 347 KB），但完整解碼的記憶體「不變」（45.8 MB → 45.8 MB）；每張縮圖解碼後 4000 × 3000，20 張共佔 915.5 MB |
| 修正版 | iOS：每張縮圖 300 × 225（0.3 MB），20 張共佔 5.1 MB。Android：`inSampleSize = 8`，每張 500 × 375（0.7 MB），20 張共佔 14.3 MB |

**為什麼**：圖片檔案的大小（JPEG、PNG、WebP 壓得多小）只影響下載大小與安裝大小；顯示的時候一定要解碼成點陣圖，大小是「寬 × 高 × 4 位元組」，和檔案壓得多小無關。4000 × 3000 的證件照，解碼後約 46 MB，20 張接近 1 GB，iOS 會被 jetsam 終止，Android 會丟 `OutOfMemoryError`（穩定性單元第 4 題）。要省記憶體，要在解碼的時候就縮成顯示需要的大小。順帶一提：KYC 的證件照上傳時不能為了體積壓到看不清楚，縮的是「列表上顯示的那份」，不是送給後端審核的原圖（第 6 題的「壓縮過頭」）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L02_DecodedMemory.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L02_DecodedMemory.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced07-app-size/L02_DecodedMemory.kt#bug
```

```lab-solution
android/solution/advanced/advanced07-app-size/L02_DecodedMemory.kt#fixed
```

### 演練 3：說移除廣告 SDK 能省 22 MB，實際只省 18 MB（第 4 題）

**檔案**：`L03_DependencyShare.swift`（iOS）、`L03_DependencyShare.kt`（Android）

**情境**：App 整合了 5 個 SDK，它們各自又依賴其他函式庫（單位 MB）：Charts 6 → Utils 4；Push 2 → Utils、FirebaseCore 3；Analytics 3 → FirebaseCore；AdsSDK 5 → AdsAdapterA 6、AdsAdapterB 7、Utils；ImageLoader 2。Utils 被三個 SDK 共用，FirebaseCore 被兩個共用。演練要算「移除某個 SDK 後 App 實際少多少」，並依這個數字排出最值得處理的順序。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：把這個 SDK 底下所有依賴（含自己）的大小加起來（`totalSize(reachable(from: [root]))`）。
2. **修正版**：算「移除之前所有依賴的總和」減「移除之後還留著的依賴總和」：先挑出移除它之後剩下的 SDK，再各自算總和，相減。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | AdsSDK 可省 22 MB、Charts 10、Push 9、Analytics 6、ImageLoader 2；`各項加總 49 MB（App 所有依賴總共 38 MB）`：加總比整體還大，顯然算錯 |
| 修正版 | AdsSDK 可省 18 MB、Charts 6、Analytics 3、ImageLoader 2、Push 2；`各項加總 31 MB`，小於 38 MB（剩下的 7 MB 是共用的 Utils 與 FirebaseCore，要同時移除多個 SDK 才會消失） |

**為什麼**：共用的依賴只要還有一個 SDK 在用，就不會從 App 消失。把它算進每一個 SDK 頭上，會高估每一項能省的量，也會把順序排錯（Push 看起來有 9 MB，其實只有 2 MB 是它獨有的）。找依賴的實際做法：Android 用 `./gradlew app:dependencies` 看依賴樹、APK Analyzer 看大小；iOS 用 link map 看每個函式庫的位元組，再用同樣的方法算（第 4 題的步驟一、二）。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L03_DependencyShare.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L03_DependencyShare.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced07-app-size/L03_DependencyShare.kt#bug
```

```lab-solution
android/solution/advanced/advanced07-app-size/L03_DependencyShare.kt#fixed
```

### 演練 4：每版只大一點點，兩年後大了 12 MB（第 6 題）

**檔案**：`L04_SizeGate.swift`（iOS）、`L04_SizeGate.kt`（Android）

**情境**：團隊在 CI 加了體積檢查。基準版本是 100.0 MB，之後連續 12 個 build，每個都只比上一個大 0.6 MB（正常的成長），第 13 個 build 一次大了 4.5 MB（有人把原圖放進去）。預算：整體不超過基準 + 3 MB，單一 build 不超過上一個 + 1 MB。

**怎麼寫**（兩個平台做法相同）

1. **Bug 版**：只檢查「這個 build 比上一個大多少」，超過 1 MB 才擋下。
2. **修正版**：除了單一 build 的增幅，還要檢查「比基準版本大多少」，兩個條件都成立才通過。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `共 13 個 build，被擋下 1 個：#13`，最後一個 build 比基準版本大 11.7 MB：前 12 個每次只大 0.6 MB，全部放行 |
| 修正版 | `共 13 個 build，被擋下 8 個：#6、#7、…、#13`：從累積超過預算的第 6 個 build（比基準大 3.6 MB）開始擋下，團隊必須說明原因，或有意識地調整基準 |

**為什麼**：只跟上一個 build 比，每一步都在容許範圍內，慢性成長永遠不會被發現。體積檢查要有「基準版本」和「整體預算」（第 6 題的步驟二、六；常見陷阱的「沒有基準就說變小了」同樣的道理）。真正的檢查做法：Android 在 CI 用 `bundletool get-size total` 讀下載大小，iOS 讀 App Thinning Size Report；基準放進版本控制，要調整就留下紀錄。

**iOS 解答**

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L04_SizeGate.swift#bug
```

```lab-solution
ios/Solution/Advanced/Advanced07-AppSize/L04_SizeGate.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/advanced/advanced07-app-size/L04_SizeGate.kt#bug
```

```lab-solution
android/solution/advanced/advanced07-app-size/L04_SizeGate.kt#fixed
```

第 2、3 題的其他手段（`-Osize`、`DEAD_CODE_STRIPPING`、R8 的縮減與混淆、資源縮減、語系過濾）都是建置設定，執行時看不到差別，沒有做成演練；第 5 題（靜態連結和動態 framework）是 Xcode 的連結設定，要用 App Thinning Size Report 或 link map 比較，也沒有演練。這些的看法：iOS 在 Archive 匯出時產生 App Thinning Size Report，比較改動前後的壓縮後與解壓縮後大小；Android 用 Android Studio 的 Build > Analyze APK 打開 APK／AAB 看 dex、資源、原生函式庫各占多少，再用 `bundletool get-size total` 看下載大小。R8 的 keep rules 與只在 release 才出現的問題，在平台對照的 Platform07Release 演練 4 做過。

## 常見陷阱

- **把 AAB 變小，說成使用者的安裝容量減少。** AAB 是上傳用的格式，使用者的下載大小與安裝大小要另外量。
- **只開了 R8，卻沒有保存 mapping 檔。** crash 報告會看不懂；R8 的 full mode 出問題時，也不應該直接放棄混淆，而是補 keep 規則或調整設定。
- **用名稱動態取資源，卻被資源縮減刪掉。** 只有 release 才出錯，debug 完全正常。
- **把靜態庫同時連結到主 App 和多個 extension。** 程式碼被複製多份，體積反而變大。
- **圖片散落在 bundle 裡，沒有放進 asset catalog。** 這些檔案不會被 slicing，每位使用者都完整下載。
- **沒有基準就說「變小了」。** 說不出是減了多少、相對於哪個版本、量哪一種大小。

## 面試官想聽到的

1. 一開始就釐清是哪一種大小，分得清上傳檔案、下載大小與安裝大小。
2. 說得出兩個平台的主要手段，以及各自的取捨與陷阱。
3. 知道精簡第三方依賴、換圖片與動畫格式通常效果最大，並說得出怎麼找出誰最占空間。
4. 知道縮小體積可能帶來風險（R8、`-Osize`、壓縮畫質），用 release build 驗證。
5. 提到數字時能說清楚是哪個大小、怎麼量的。

> [!FIELD]
> 在一個社交 App，我做過「精簡第三方依賴，讓安裝容量明顯縮小」。除了精簡依賴，也做過這些事：iOS 把散落的 GIF 動畫換成 animated WebP，並移除沒用到的程式碼與 import；Android 的 release 啟用 R8 的程式碼縮減與資源縮減，並限制 ABI 只保留 `arm64-v8a` 與 `armeabi-v7a`。
> 被問到「這個數字怎麼量的」，標準回答是：說明量的是哪一種大小（安裝容量、下載大小或上傳大小）、用什麼工具、比較哪兩個版本；如果沒有留下正式報告，就誠實說是持續追蹤的數字。接著可以把話題帶到做法：先量每個依賴的貢獻，再合併重複功能、用系統 API 取代。
> 換成交易所 App，我會先做 size report 找出前幾大的貢獻者（多語系與 SDK 通常是大宗），每一項記錄前後數字，並把體積檢查加進 CI。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 上傳格式 | `.ipa`（App Store 端做 slicing） | `.aab`（Google Play 端產生 split APK） |
| 程式碼縮減 | `-Osize`、dead code stripping | R8：縮減、混淆、最佳化 |
| 資源縮減 | asset catalog slicing、移除沒用到的資源 | resource shrinking、WebP、語系過濾 |
| CPU 架構 | 單一架構的 slice | ABI split（`arm64-v8a`、`armeabi-v7a` 等） |
| 延後下載 | On-Demand Resources、Background Assets | Play Feature Delivery、Play Asset Delivery |
| 分析工具 | App Thinning Size Report、link map | APK Analyzer、`bundletool get-size` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 下載大小／安裝大小 | download size／install size | ダウンロードサイズ／インストールサイズ |
| App 瘦身（依裝置只送需要的部分） | App Thinning（slicing） | アプリシンニング（スライシング） |
| 隨選資源（需要時才下載） | On-Demand Resources | オンデマンドリソース |
| 縮減程式碼與資源 | shrink（minify） | 縮小（シュリンク） |
| 混淆 | obfuscate | 難読化 |
| CPU 架構（原生函式庫的版本） | ABI（Application Binary Interface） | ABI |
| 冒煙測試（發布前快速檢查關鍵流程） | smoke test | スモークテスト |
