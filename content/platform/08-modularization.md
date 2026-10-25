---
id: platform-modularization
title: 模組化：SPM 與 XCFramework、Gradle 多模組、依賴方向、建置速度與 Kotlin Multiplatform
summary: 模組化是把 App 切成有明確邊界的幾個部分，目的是平行開發、獨立測試，條件適合時還能加快建置，不是切得越細越好。這個單元說明兩個平台的工具、依賴方向的規則、對效能的影響，以及 Kotlin Multiplatform 的現況與取捨。
priority: P1
day: 2
tags: [modularization, spm, xcframework, gradle, convention-plugin, version-catalog, kmp, build-speed]
---

## 重點講解

模組化指的是把一個大 App 切成幾個有明確邊界的部分，每個部分只公開必要的功能給別人用。這個單元回答六個面試常見問題，每一題都會同時講 iOS 和 Android：

1. 為什麼要模組化？模組之間的依賴方向要怎麼規劃？
2. iOS 怎麼切模組？SPM（Swift Package Manager）、XCFramework 和存取控制怎麼搭配？
3. Android 的 Gradle 多模組怎麼做？`api` 和 `implementation`、convention plugin、version catalog 是什麼？
4. 模組化對建置速度和 App 啟動速度有什麼影響？
5. Kotlin Multiplatform（KMP）是什麼？該共用什麼？有什麼代價？
6. KMP 在 iOS 端呼叫起來是什麼樣子？Kotlin 1.4 時代和現在有什麼差別？

### 1. 為什麼要模組化？模組之間的依賴方向要怎麼規劃？

**模組化的目的是畫出邊界，讓不同的人可以平行開發、各自獨立測試；依賴方向比「怎麼切」更重要：Feature（功能）模組往下依賴 Domain（業務規則）和 Core（共用基礎），Feature 之間絕對不互相依賴。**

以交易所 App 為例，有行情（market）、下單（order）、資產（wallet）三個功能。下單成功後，要跳到資產頁。如果 `order` 直接依賴 `wallet`，而 `wallet` 的「入金後去下單」又依賴 `order`，兩個模組就形成循環：誰也無法單獨建置和測試，改動一個就要重編另一個。

正確的結構長這樣：

```text
App（Composition Root：整個 App 唯一負責組裝實作的地方）
 ├─ Feature A（行情）─┐
 ├─ Feature B（下單）─┼─▶ Domain（純邏輯與介面，不含 UI 與平台框架）
 └─ Feature C（資產）─┘        ▲
                               │ 實作介面
                     Data / Network / Storage
```

四條規則：

1. **依賴只往下，往穩定的方向。** Feature 依賴 Domain 和 Core；Feature 之間不互相依賴。要跨功能跳轉，透過介面或路由事件，由 App 層組裝。
2. **Domain 不依賴 UI，也不依賴具體的網路或資料庫。** Data 層去實作 Domain 定義的介面。這樣 Domain 可以用最快的單元測試驗證。
3. **Composition Root 在 App 層。** Composition Root 指的是整個 App 唯一負責「把各個實作組裝起來」的地方，通常在 App 啟動時，由它建立實作並透過 DI（dependency injection，依賴注入）交給需要的人。
4. **公開的介面越少越好。** 只把真正要給別人用的型別公開，其他都藏在模組內（兩個平台的做法見第 2、3 題）。

跨功能跳轉的寫法，兩個平台都一樣：Feature 只依賴一個介面，真正的實作由 App 層注入。

```kotlin
// :core:navigation 模組定義介面，Feature 只看得到這個介面
interface WalletNavigator {
    fun openWallet()
}

// :feature:order 只呼叫介面，完全不知道 :feature:wallet 存在
class OrderViewModel(private val walletNavigator: WalletNavigator) {
    // 下單成功後，請 App 層帶使用者去資產頁
    fun onOrderPlaced() = walletNavigator.openWallet()
}
// :app 模組：實作 WalletNavigator（真正去開資產頁），
// 並用 Hilt（Google 的依賴注入函式庫）或手動注入給 OrderViewModel
```

```swift
// Core 模組定義的協定，Feature 只依賴它
protocol WalletNavigator {
    func openWallet()
}

// OrderFeature 模組只呼叫協定
final class OrderViewModel {
    private let walletNavigator: WalletNavigator

    init(walletNavigator: WalletNavigator) {
        self.walletNavigator = walletNavigator
    }

    func onOrderPlaced() {
        walletNavigator.openWallet()
    }
}
// App 層（例如 FlowController／Coordinator，負責管理畫面跳轉的物件）
// 實作 WalletNavigator 並注入
```

> [!TIP]
> 面試時可以這樣說：「我會先看痛點再決定怎麼切，不為了模組化而模組化。依賴方向是 Feature 依賴 Domain 和 Core，Feature 之間不互相依賴，跨功能跳轉透過介面，由 App 層的 Composition Root 組裝。Domain 保持純淨，不依賴 UI 或具體的網路資料庫，這樣最容易測試。」

### 2. iOS 怎麼切模組？SPM（Swift Package Manager）、XCFramework 和存取控制怎麼搭配？

**iOS 現在最常見的做法是用 SPM（Swift Package Manager）的 local package 切模組，用 Swift 的存取控制決定哪些型別對外公開，需要散佈預先編譯好的函式庫時才用 XCFramework。**

三個工具各自的角色：

| 工具 | 用途 |
|---|---|
| SPM local package | 專案裡的一個資料夾，放 `Package.swift`，裡面可以有多個 target（模組）。改了程式碼，Xcode 只重編受影響的 target。 |
| XCFramework | 一種可以放多個平台與 CPU 架構（真機、模擬器）的函式庫格式，用來散佈**預先編譯好的**二進位檔，例如廠商給的 SDK（software development kit，軟體開發套件）。SPM 用 `.binaryTarget` 引用它（本機路徑，或遠端 URL 加 checksum）。 |
| `BUILD_LIBRARY_FOR_DISTRIBUTION` | 建置 XCFramework 時要開的設定（也叫 library evolution）。Swift 預設不保證不同版本編譯器之間二進位相容，開了之後會產生 `.swiftinterface` 檔，讓其他版本的 Xcode 也能使用。 |

一個 `Package.swift` 的範例，把 Domain 和兩個 Feature 切開，Feature 之間沒有互相依賴：

```swift
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Features",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "MarketFeature", targets: ["MarketFeature"]),
        .library(name: "OrderFeature", targets: ["OrderFeature"]),
    ],
    targets: [
        .target(name: "Domain"),
        .target(name: "MarketFeature", dependencies: ["Domain"]),
        // 只依賴 Domain，不依賴 MarketFeature
        .target(name: "OrderFeature", dependencies: ["Domain"]),
        .testTarget(name: "OrderFeatureTests", dependencies: ["OrderFeature"]),
    ]
)
// 如果要引用廠商提供、預先編譯好的 SDK：
//   .binaryTarget(name: "VendorSDK", path: "Frameworks/VendorSDK.xcframework")
```

**存取控制**決定模組的邊界：

- `internal`（預設）：只有同一個模組內看得到。
- `public`：其他模組也看得到。要給別人用的 API 才需要 `public`。
- `package`（Swift 5.9 起）：同一個 package 內的**其他模組**看得到，但 package 外面的 App 看不到。適合 Domain 和 Feature 之間共用、但不想暴露給整個 App 的型別。

```swift
// Domain 模組
// 其他模組要用，所以 public
public protocol OrderRepository {
    func place(_ request: OrderRequest) async throws -> String
}

public struct OrderRequest {
    public let symbol: String
    // public struct 的 init 預設是 internal，要自己加 public
    public init(symbol: String) {
        self.symbol = symbol
    }
}

// 同一個 package 的其他模組（例如 OrderFeature）可用，App 看不到
package func validate(_ request: OrderRequest) -> Bool {
    !request.symbol.isEmpty
}

// internal：只有 Domain 模組自己看得到
func internalHelper() {}
```

測試時用 `@testable import`：測試目標可以存取被測模組的 `internal` 成員，但不能存取 `private`；被測模組要用 debug 設定建置（`-enable-testing`）。

> [!TIP]
> 面試時可以這樣說：「iOS 我用 SPM 的 local package 切模組，每個 Feature 一個 target，只依賴 Domain。存取控制預設 internal，只有要給別人用的才 public，同一個 package 內共用的用 Swift 5.9 的 package。需要散佈預先編譯好的函式庫時才用 XCFramework，並且開 library evolution 保持相容。」

### 3. Android 的 Gradle 多模組怎麼做？`api` 和 `implementation`、convention plugin、version catalog 是什麼？

**Android 的模組單位是 Gradle module，如 `:app`、`:feature:order`、`:core:network`。三個重點工具：`implementation` 和 `api` 控制依賴有沒有外洩、convention plugin 集中放共用的建置設定、version catalog 集中管理版本。**

**(1) `api` 和 `implementation`。** 假設 `:core:network` 用了 OkHttp，只在內部使用；它的公開介面回傳 `:core:model` 的型別。

```kotlin
// :core:network 的 build.gradle.kts
dependencies {
    // 型別出現在本模組的公開 API，使用者也要看得到，所以用 api
    api(project(":core:model"))
    // 純內部實作，不外洩給使用者
    implementation(libs.okhttp)
}
```

用 `implementation` 的依賴不會傳給下游模組：OkHttp 升級時，只有 `:core:network` 要重編，下游不用，建置比較快，也不會有人誤用到不該直接用的函式庫。濫用 `api` 會讓依賴圖變粗，增量建置（只重編有變動的部分）的優勢就消失了。

**(2) convention plugin。** 每個 library 模組都要設定 compileSdk、Kotlin 版本、測試設定……逐個模組複製貼上，改一次要改幾十個檔案。convention plugin 是把這些共用設定寫成一個 Gradle plugin，放在專案裡一個叫 `build-logic` 的獨立資料夾（included build，一個被主專案引入的獨立 Gradle 專案），每個模組只要一行 `id("myapp.android.library")` 就套用完成。

```kotlin
// build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")
            extensions.configure<LibraryExtension> {
                compileSdk = 35
                defaultConfig.minSdk = 26
            }
        }
    }
}
// 各模組的 build.gradle.kts 只要：plugins { id("myapp.android.library") }
```

過去有人把共用設定放在 `buildSrc` 資料夾，但 `buildSrc` 裡的任何變動，都會讓整個專案的建置快取失效，所以現在建議改用 `build-logic` 這種 included build。

**(3) version catalog。** 把所有依賴的版本集中寫在 `gradle/libs.versions.toml`（TOML 是一種簡單的設定檔格式），各模組用 `libs.okhttp` 這樣的方式引用，升級時只改一個地方：

```toml
[versions]
okhttp = "4.12.0"
kotlin = "2.0.21"

[libraries]
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }

[plugins]
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

**存取控制**：Kotlin 的 `internal` 代表只有同一個 Gradle module 內看得到，`public` 才是對外 API。AAR（Android Archive，Android 的函式庫打包格式）是散佈預先編譯好的 Android 函式庫的方式，對應 iOS 的 XCFramework。

> [!TIP]
> 面試時可以這樣說：「Android 我用 Gradle 多模組，依賴預設用 implementation，只有型別出現在公開 API 時才用 api，避免依賴外洩、減少重編範圍。共用的建置設定放 build-logic 的 convention plugin，不用 buildSrc，因為 buildSrc 一改整個快取都失效。版本集中用 version catalog 管理。」

### 4. 模組化對建置速度和 App 啟動速度有什麼影響？

**模組化不保證建置變快，只有拆得合理、依賴圖是「淺而寬」（層數少、每層模組多）時才會變快。** 另外在 iOS 上，動態 framework 太多還會拖慢 App 的冷啟動（App 從完全沒執行，到開始執行的那一次啟動）。

**Android 的建置速度：**

- 多模組可以平行編譯，增量建置只重編被影響的模組；`implementation` 讓上游的變更不會連帶重編下游。
- 啟用 build cache（重複的工作直接用快取結果）與 configuration cache（快取 Gradle 的設定階段）：

```properties
# gradle.properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

- annotation processing（編譯時產生程式碼的工具）盡量用 **KSP**（Kotlin Symbol Processing）取代 **kapt**（Kotlin Annotation Processing Tool）。kapt 要先產生 Java 的中介檔案，比較慢；Hilt 也支援 KSP。
- Android Gradle Plugin（AGP）8.0 起，R class（存放資源編號的類別）預設不再傳遞（non-transitive），也減少了重編的範圍。
- 找瓶頸用 build scan 和 Android Studio 的 Build Analyzer。

**iOS 的建置速度與啟動：**

- 動態 framework 是獨立的 dylib（動態函式庫）。App 啟動時，系統的 dyld（dynamic linker，動態連結器）要找到、載入並初始化每一個，數量越多啟動越慢。可以改成靜態連結（把程式碼併入主執行檔），或合併模組。SPM library 預設的 `automatic` 種類，會由系統依使用情況選擇靜態或動態。
- 靜態函式庫如果被多個動態 framework 同時連結，會造成重複的符號，要注意連結方式一致。
- 太多細碎模組也有成本：每個模組都有介面編譯與連結的開銷。
- 量測：啟動時間用 Instruments 的 App Launch；建置時間用 Xcode 的 build timeline。

**重點是先量測，再決定怎麼調整。** 模組化真正的收益是邊界清楚、可平行開發、測試隔離。

> [!TIP]
> 面試時可以這樣說：「模組化不保證建置變快，要看依賴圖是不是淺而寬。Android 我會用 implementation、build cache、configuration cache 和 KSP，用 build scan 找瓶頸；iOS 我會注意動態 framework 太多會拖慢冷啟動，可以改靜態連結或合併模組，並用 Instruments 的 App Launch 量測。」

### 5. Kotlin Multiplatform（KMP）是什麼？該共用什麼？有什麼代價？

**KMP 是 Kotlin 的功能，讓你用同一份 Kotlin 程式碼同時編譯成 Android 和 iOS（以及其他平台）可以用的函式庫，適合共用「不含 UI 的邏輯」，例如網路、資料模型、業務規則。**

**怎麼運作：** 程式碼分成 `commonMain`（共用，兩個平台都編譯）、`androidMain`、`iosMain`（各平台專屬）。共用程式碼遇到平台才有的功能，用 `expect`／`actual` 處理：共用程式碼用 `expect` 宣告「我需要這個功能」，各平台用 `actual` 提供實作。

```kotlin
// commonMain：只宣告需要什麼
expect fun platformName(): String
fun greeting() = "Hello from ${platformName()}"

// androidMain
actual fun platformName(): String = "Android"

// iosMain
actual fun platformName(): String = "iOS"
```

實務上常見的 `expect`／`actual` 例子是資料庫驅動（SQLDelight 在 Android 用 `AndroidSqliteDriver`，在 iOS 用 `NativeSqliteDriver`）、日誌、以及呼叫廠商 SDK。

**該共用什麼？**

- 網路與序列化（Ktor 是 JetBrains 開發的 Kotlin 網路函式庫）、資料模型、業務規則與驗證、資料庫（SQLDelight 是把 SQL 轉成型別安全的 Kotlin 程式碼的資料庫函式庫）。
- 不一定要共用 UI。UI 要共用的話是另一個選擇（Compose Multiplatform）。
- 對交易所特別有價值的是**規則一致**：價格與數量的精度、tick size（最小跳動單位）的對齊，兩個平台用同一份，就不會出現四捨五入不一致。如果不想導入 KMP，至少共用**測試資料**（例如 JSON 檔），讓兩個平台各跑一次。

**代價：**

- Kotlin/Native（編譯成 iOS 原生程式碼的部分）的建置時間較長。
- 在 Xcode 裡除錯 Kotlin 程式碼比較困難。
- iOS 團隊要學 Kotlin，跨語言的 API 設計也需要花心力。
- 共用了不穩定的東西（頻繁變動的 API 層），反而增加同步成本。

> [!TIP]
> 面試時可以這樣說：「KMP 我只考慮共用不含 UI 且穩定的邏輯，像是價格和數量的精度規則、驗證、資料模型，這對交易所的雙平台一致性很有價值。平台差異用 expect／actual 處理。導入前我會先用共用的測試資料驗證，並評估建置時間、除錯和團隊的學習成本。」

### 6. KMP 在 iOS 端呼叫起來是什麼樣子？Kotlin 1.4 時代和現在有什麼差別？

**Kotlin/Native 會把共用程式碼輸出成 Objective-C 的 header 給 Swift 呼叫，所以有些 Kotlin 特性在 Swift 端不太自然。這個互通的體驗，隨著 Kotlin 版本變化很大。**

**Swift 端會遇到的情況：**

- `suspend` 函式（Kotlin 的協程函式）在 Swift 端會變成 completion handler（完成時被呼叫的 closure）的形式。Kotlin 1.4 時代，例外要加 `@Throws` 才會轉成 `NSError`，沒加的話例外會讓 App 直接 crash。
- 泛型、`sealed class`、集合型別在 Swift 端的體驗不夠自然。
- Swift 5.5 之後，這種 completion handler 的函式可以直接用 `await` 呼叫，不過取消 Swift 的 Task 預設不會傳回 Kotlin 端。SKIE 這類工具可以進一步把 Kotlin 的 `Flow` 轉成 Swift 的 `AsyncSequence`、讓 `sealed class` 在 Swift 可以用 `switch` 窮舉。

**版本的重要節點：**

| 時間 | 變化 |
|---|---|
| Kotlin 1.4 時代（2020–2021） | 舊記憶體模型：Kotlin/Native 的物件要跨執行緒傳遞，必須先 freeze（凍結，凍結後就不能修改），否則丟 `InvalidMutabilityException`；coroutines 要用專門的 `-native-mt` 版本才能多執行緒 |
| Kotlin 1.7.20（2022 年） | 新記憶體模型預設啟用，**不再需要 freezing**，也不需要 `-native-mt` 版本的 coroutines |
| Kotlin 1.9.20（2023 年 11 月） | KMP 宣告穩定 |

> [!TIP]
> 面試時可以這樣說：「KMP 輸出到 Swift 是透過 Objective-C header，suspend 函式在 Swift 端是 completion handler，要加 @Throws 例外才會正確轉成 NSError。早期的 Kotlin 1.4 時代有舊記憶體模型，物件跨執行緒要 freeze；Kotlin 1.7.20 起新記憶體模型預設啟用，不再需要，1.9.20 起 KMP 也宣告穩定。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Platform08Modules**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Platform08Modules**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform08-Modules/`，解答在 `labs/ios/Solution/Platform/Platform08-Modules/`；Android 你寫在 `labs/android/practice/platform/platform08-modules/`，解答在 `labs/android/solution/platform/platform08-modules/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

模組化的設定（SPM 的 `Package.swift`、Gradle 的 `build.gradle.kts`）是編譯期的事：依賴寫錯了，結果是編譯失敗或建置變慢，不是執行時出錯，沒有辦法在一個 App 裡「執行」出差別。所以這個單元的演練挑的是「模組邊界畫錯，在程式碼裡會怎麼壞」，用兩個類別代表兩個模組。

### 演練 1：測下單頁卻被迫準備資產頁（第 1 題）

**檔案**：`L01_FeatureCycle.swift`（iOS）、`L01_FeatureCycle.kt`（Android）

**情境**：交易所 App 有「下單」和「資產」兩個功能模組。下單成功後要跳到資產頁；資產頁的「入金後去下單」又要跳回下單頁。演練單獨測試下單頁（`onOrderPlaced`），看資產頁被牽連多少。iOS 另外在兩個畫面都關閉後，用 `LiveObjects` 數還有幾個 ViewModel 活著；Android 數資產頁的物件被建立幾個（JVM 的垃圾回收會處理循環參考，看不到洩漏）。

**怎麼寫**

1. **Bug 版**：`OrderViewModel` 直接建立 `WalletViewModel`（iOS 用 `lazy var`，Android 用 `by lazy`，把自己傳進去），`onOrderPlaced` 呼叫 `wallet.open()`；`WalletViewModel` 又直接持有 `OrderViewModel`。這等於 `:feature:order` 與 `:feature:wallet` 兩個模組互相依賴。
2. **修正版**：Feature 只依賴 Core 定義的協定／介面（`WalletNavigator`、`OrderNavigator`）。`OrderViewModel.onOrderPlaced` 只呼叫 `walletNavigator.openWallet()`；真正打開資產頁的是 App 層（Composition Root）。iOS 的 ViewModel 對 App 層只用 `weak` 參考，避免互相強參考。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「單獨測試下單：資產頁的 API 被呼叫 1 次，導覽請求 0 次」；iOS「兩個畫面都關閉後，還活著的 ViewModel：2 個」，Android「被建立出來的資產頁物件：1 個」 |
| 修正版 | 「資產頁的 API 被呼叫 0 次，導覽請求 1 次」；iOS 還活著 0 個，Android 建立 0 個 |

**為什麼**：Feature 之間一旦互相依賴，就形成循環：誰也不能單獨建置或測試（測下單頁必須先準備資產頁需要的 API），在 iOS 還會因為互相強參考而洩漏記憶體。把跳轉抽成 Core 裡的介面，兩個 Feature 都只依賴這個介面，由 App 層組裝，依賴方向就變成「Feature 往下依賴 Core，App 往下依賴所有人」，測試下單頁只要給一個假的導覽。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform08-Modules/L01_FeatureCycle.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform08-Modules/L01_FeatureCycle.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform08-modules/L01_FeatureCycle.kt#bug
```

```lab-solution
android/solution/platform/platform08-modules/L01_FeatureCycle.kt#fixed
```

### 演練 2：同一個價格，iOS 和 Android 對齊的結果不一樣（第 5 題）

**檔案**：`L02_SharedVectors.swift`（iOS）、`L02_SharedVectors.kt`（Android）

**情境**：限價單的價格必須是 tick size（最小跳動單位）的整數倍。App 要把使用者輸入的價格「往下對齊」到 tick size 的倍數，例如價格 0.57、tick size 0.01，結果還是 0.57。這個規則兩個平台和伺服器都要一模一樣。就算不導入 KMP，至少共用同一份測試資料（價格、tick size、標準答案），各自跑一次，不一致就是 bug。演練把 8 筆測試資料直接寫在檔案裡，iOS 與 Android 的文字一模一樣（真的專案會放成一份 JSON 檔，兩邊都讀）。

**怎麼寫**

1. **Bug 版**：用 `Double` 計算：價格 ÷ tick size 往下取整，再乘回 tick size，最後轉成文字。
2. **修正版**：iOS 用 `Decimal`（`NSDecimalRound` 往下取整）、Android 用 `BigDecimal`（`divide(t, 0, RoundingMode.DOWN)`），都是十進位的精確運算。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「共 8 筆共用測試資料，和標準答案不一致 5 筆」：0.3 變成 0.2、0.57 變成 0.56、4.35 變成 4.3、19.99 變成 19.98、0.00012345 變成 0.00012344，iOS、Android 的結果一樣 |
| 修正版 | 「和標準答案不一致 0 筆」 |

**為什麼**：`Double` 是二進位的浮點數，0.01 這種十進位的小數無法精確表示，0.57 ÷ 0.01 算出來是 56.99999999999999，往下取整就變成 56，使用者輸入的正確價格被悄悄改小一格。價格與數量要用十進位的型別（`Decimal`、`BigDecimal`）。更重要的是流程：共用測試資料讓兩個平台各跑一次，規則有任何一邊寫歪了，馬上看得到；KMP 則是更進一步，直接共用同一份程式碼。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform08-Modules/L02_SharedVectors.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform08-Modules/L02_SharedVectors.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform08-modules/L02_SharedVectors.kt#bug
```

```lab-solution
android/solution/platform/platform08-modules/L02_SharedVectors.kt#fixed
```

第 2、3、4、6 題是建置設定與工具的題目（SPM 與存取控制、Gradle 的 `api`／`implementation`、建置與啟動速度、KMP 在 Swift 端的呼叫方式），結果是編譯錯誤或建置速度，不是執行時出錯，沒有演練。想自己觀察，可以這樣做：

- **存取控制（iOS 第 2 題）**：在一個 local package 的 Domain target 裡，把 `public struct OrderRequest` 的 `public init` 拿掉，在 App 呼叫它的初始化，會得到編譯錯誤「initializer is inaccessible due to 'internal' protection level」；Swift 5.9 的 `package` 則讓同一個 package 內的其他 target 看得到、App 看不到。
- **`api` 與 `implementation`（Android 第 3 題）**：執行 `./gradlew :feature:order:dependencies --configuration debugCompileClasspath`，看下游模組的編譯類別路徑裡有沒有出現上游用 `implementation` 宣告的函式庫。
- **建置與啟動速度（第 4 題）**：Android 用 Android Studio 的 Build Analyzer 看哪個工作最慢；iOS 用 Xcode 的 Product > Perform Action > Build With Timing Summary 看建置時間，用 Instruments 的 App Launch 看冷啟動。

## 常見陷阱

- **Feature 之間互相依賴。** 形成循環，無法獨立建置、測試，改一個就要重編全部。要改成依賴介面，由 App 層組裝。
- **所有東西都 `public`，或所有依賴都用 `api`。** 模組邊界形同虛設，增量建置的優勢也消失。預設 `internal` 與 `implementation`，只公開必要的。
- **模組切得過細。** 每個模組都有建置與連結的開銷，反而更慢。先看痛點再切。
- **用 `buildSrc` 放共用建置設定。** 任何改動都讓整個專案的快取失效，改用 `build-logic`。
- **iOS 動態 framework 過多。** 冷啟動變慢，可以改成靜態連結或合併模組，並用 Instruments 量測。
- **KMP 共用了不穩定的東西。** 例如 UI 或頻繁變動的 API 層，反而增加同步成本。
- **把舊版 KMP 的做法當成現在的最佳做法。** Kotlin 1.4 時代的 freeze 和 `-native-mt`，在新記憶體模型下都不需要了。

## 面試官想聽到的

1. 依賴方向與 Composition Root：Feature 之間不互相依賴，Domain 保持純淨。
2. 兩個平台的工具與設定：SPM 與 XCFramework、Gradle 多模組、`api` 與 `implementation`、convention plugin、version catalog。
3. 建置與啟動效能的具體手段，以及怎麼量測。
4. KMP 的現況與取捨：哪些適合共用，哪些不適合，以及版本演進（舊記憶體模型到新記憶體模型）。
5. 不為模組化而模組化：先看痛點（建置時間、團隊規模、測試隔離）。

> [!FIELD]
> 在一個 BLE（Bluetooth Low Energy，低功耗藍牙）智慧鎖 SDK 的專案裡，我做過 Kotlin Multiplatform：新版的使用者端與管理端 App 採用 MVVM（Model-View-ViewModel，把畫面和邏輯分開的架構）與 Hybrid（WebView）架構，並以 KMP 把 iOS 與 Android 的共用邏輯依領域（common／user／manager）劃分成多個模組，兩端再對應拆成 Android library module 與 iOS framework。
> 實作上，共用模組以 Kotlin/Native 的 CocoaPods 外掛輸出成 iOS 能引入的 framework；iOS 端用原生 Swift framework 包一層去呼叫；平台差異用 `expect`／`actual` 處理，例如資料庫驅動、日誌，以及透過 cinterop（Kotlin/Native 呼叫 Objective-C／C 函式庫的機制）呼叫智慧鎖廠商的 SDK。suspend 函式在 Swift 端是 completion handler，我們寫了一層 Swift 的 `ApiHandler` 統一處理。
> 那是 Kotlin 1.4 的時代（舊記憶體模型），所以第 6 題的版本差異我可以從實際經驗說明。換成交易所 App，我會以新記憶體模型和 SKIE 這類新工具為前提來設計共用層，並先從價格與數量的精度規則這類穩定的邏輯開始。
> 在一個社交 App 裡，我也用過 SPM 和 CocoaPods 管理依賴、Android 用 version catalog 集中管理版本；iOS 採用 Coordinator（FlowController）管理畫面跳轉，這跟第 1 題的「由 App 層組裝跨功能跳轉」是同一個概念。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 模組單位 | SPM local package 的 target、Xcode target（framework） | Gradle module（`:app`、`:feature:*`、`:core:*`） |
| 預先編譯的函式庫 | XCFramework；SPM 用 `.binaryTarget` 引用；要開 `BUILD_LIBRARY_FOR_DISTRIBUTION` | AAR（透過 Maven 散佈） |
| 依賴管理 | SPM、CocoaPods | Gradle 加 version catalog（`gradle/libs.versions.toml`） |
| 共用的建置設定 | `.xcconfig`、XcodeGen 或 Tuist（用設定檔產生 Xcode 專案的工具）的專案描述 | convention plugin（`build-logic`） |
| 存取控制 | `internal`（預設，模組內）、`package`（Swift 5.9，同一個 package 內）、`public` | `internal`（模組內）、`public`；`implementation` 與 `api` 控制依賴有沒有外洩 |
| 建置速度的重點 | 動態 framework 數量、靜態連結、Xcode build timeline | build cache、configuration cache、KSP、`implementation` |
| 啟動速度的重點 | 動態 framework 太多，dyld 載入變慢 | 沒有對應問題：模組最後會合併進同一個 APK（Android 的安裝檔），不會因為模組多就逐一載入 |
| 與 KMP 的介面 | 輸出成 Objective-C header 給 Swift；suspend 變 completion handler | 直接是 Kotlin 函式庫，沒有互通問題 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 模組化 | modularization | モジュール化 |
| 依賴注入 | dependency injection（DI） | 依存性の注入 |
| 整個 App 唯一負責組裝實作的地方 | composition root | コンポジションルート |
| 預先編譯的函式庫格式（可含多平台） | XCFramework | XCFramework |
| 集中管理套件版本的檔案 | version catalog | バージョンカタログ |
| 增量建置 | incremental build | インクリメンタルビルド |
| 動態連結器 | dyld（dynamic linker） | 動的リンカー |
| 跨平台共用程式碼（Kotlin） | Kotlin Multiplatform（KMP） | Kotlin Multiplatform |
