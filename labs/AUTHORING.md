# 演練寫法規範

範本（**格式與品質照這兩個**）：
- iOS：`ios/Solution/Swift/Swift01-ValueSemantics/` 與 `ios/Practice/Swift/Swift01-ValueSemantics/`
- Android：`android/solution/kotlin/kotlin03-coroutines/` 與 `android/practice/kotlin/kotlin03-coroutines/`
- 複習站：`content/swift/01-value-semantics-cow.md`、`content/kotlin/03-coroutines-structured.md` 的 `## 實際演練`

## 1. 演練要練什麼

- 讀者是準備交易所 Mobile 工程師面試的人。每個演練讓他**自己寫出一個真的會發生的 bug，再自己修好**，面試時能說「我實際寫過，Bug 版會…，改成…之後…」。
- **單元裡每一個 `### N.` 題目都要想一個演練。** 只有純概念、流程、組織面的題目（例如「45 分鐘怎麼分配」「TestFlight 和 Play 測試軌差在哪」）可以沒有，在複習站那一段最後列出「第 N 題是流程題，沒有演練」。「比較少考」的題目可以做小一點，但能做就做。
- **Bug 一定要真的會發生**，而且是實務上會寫出來的寫法：結果錯、App 當掉、記憶體洩漏、畫面卡頓、資料競爭、重複下單、用了過期的價格…。不要「故意印錯字」這種假 bug。
- **修正版用複習站那個單元建議的做法**。Bug 版與修正版只差在和 bug 有關的那幾行，方便對照。
- 情境盡量用交易所 App：行情、訂單簿、下單、資產、出金、K 線、推播、登入。
- 不要用任何真實公司的內部程式、網域、API；假伺服器、假資料一律自己造。

## 2. 每個演練兩個檔案：Solution 與 Practice

同一個演練在 `Solution/`（`solution/`）與 `Practice/`（`practice/`）各有一個**同名、同路徑**的檔案。

**Solution（解答）**：完整寫好的 Bug 版與修正版。

**Practice（練習）**：從 Solution 複製，然後把「Bug 版與修正版的關鍵幾行」挖掉，換成 TODO：
- 型別、函式簽名、演練流程（scenario、run）、log、`// MARK: - 演練設定` 整段（含自動檢查）都**和 Solution 一模一樣**。
- 挖掉的只有「這個 bug 的本體」和「修正的本體」，通常各 1–5 行。
- 每個挖空的地方寫 `// TODO ①：…`（Bug 版）或 `// TODO ②：…`（修正版），多個空格用 ①-1、①-2。TODO 用白話寫清楚**要寫什麼**，可以點名要用的 API（`GlobalScope.launch`、`isKnownUniquelyReferenced`），但不要整行程式碼照抄給他。
- 骨架一定要**能編譯、能執行、不會當掉、不會丟例外**：不要用 `fatalError`、`TODO()`；需要回傳值的地方回傳一個安全的預設值（例如 `return Result.failure(IOException("還沒寫"))`）。
- **骨架沒寫的時候，checkBug 和 checkFixed 都要失敗**，而且失敗訊息要告訴他還差什麼（「onOpen 還沒開始輪詢」「Bug 還沒重現（要讓輪詢在離開後繼續跑）」）。所以檢查要同時確認「情境有真的跑起來」和「bug 有／沒有出現」，例如 L04 的「情境 A 要顯示 1.5 BTC」＋「情境 B 離開後不能更新畫面」。
- **小心「什麼都沒寫剛好等於正確行為」**：例如修正版是「一律忽略非法事件」、Bug 版是「解不出來」，骨架什麼都不寫也會 PASS。檢查要多看一行「只有寫對才會出現的證據」（例如「解出 3 則」「忽略了 2 個非法轉移」），確保空白骨架一定 FAIL。
- 檔案開頭的說明把 Solution 的「Bug 版：…／修正版：…」換成「你要寫：① …② …」，最後一行寫解答的路徑。

## 3. 檔案結構

檔名 `L01_<英文短名>.swift`／`.kt`。L 的編號照題號順序；一題兩個演練就往下排，`question` 寫清楚對應第幾題。

**Swift**（Swift 的 module 是一整區，用 `private` 讓每個檔案的型別互不衝突）

```swift
// L01：<看得到的症狀，白話>（<這題的主題>）
// 對應複習站：<單元代號> 第 N 題「<題目原文>」
//
// 情境：…
// Bug 版：…（Practice 改成「你要寫：① … ② …」與解答路徑）
// 修正版：…
import LabKit

（兩個版本共用、和 bug 無關的程式：假的 API、行情產生器、演練流程。用 private。）

// MARK: - Bug 版
private enum L01Bug { … static func run(_ log: LabLog) async { … } }

// MARK: - 修正版
private enum L01Fixed { … }

// MARK: - 演練設定
extension Swift01ValueSemantics {          // 單元的命名空間（單元清單檔裡宣告的 enum）
    static let l01 = Lab(…)
}
```

**Kotlin**（每個單元一個 package：`tw.harry.labs.practice.kotlin03coroutines`／`tw.harry.labs.solution.kotlin03coroutines`）

```kotlin
package tw.harry.labs.solution.kotlin03coroutines
…
private suspend fun scenario(…) { … }      // 共用的放上面，用 private

// MARK: - Bug 版
private object L01Bug { … }

// MARK: - 修正版
private object L01Fixed { … }

// MARK: - 演練設定
val l01 = Lab(…, file = "kotlin03-coroutines/L01_ScopeLeak.kt", …)
```

- **`private` 的型別名稱在單元內要唯一**：Kotlin 同一個 package 的不同檔案，`private` top-level class 同名也會衝突（例如兩個檔案都宣告 `private class Tick`）。加上演練編號（`L03Tick`）或放進 `L0xBug`／`L0xFixed` 裡面。
- `// MARK: - Bug 版`、`// MARK: - 修正版`、`// MARK: - 演練設定` 這三行**一字不差**（Kotlin 也用 `// MARK: -`），複習站靠它擷取解答程式碼。區段裡不要再出現其他 `// MARK: -`。
- 註解用白話的繁體中文（台灣用語），說明「這一行會發生什麼」。用語規則同 `content/SCHEMA.md` §2.1：不自創名詞、縮寫第一次出現要展開。
- 新增演練後，在單元清單檔（`Swift01ValueSemantics.swift`、`Kotlin03Coroutines.kt`）的 `labs` 加上 `l0x`（Practice 與 Solution 都要）。

## 4. `Lab` 的欄位

| 欄位 | 寫什麼 |
|---|---|
| `id` | `"L01"` |
| `title` | 看得到的症狀，10–20 字（「按了取消，價格卻被改掉」） |
| `question` | `"第 1 題：<題目原文>"` |
| `goal` | 要達成什麼，1–2 句 |
| `steps` | 3–5 步，具體到「選哪個版本、看 log 的哪一行、打開哪個工具」。用到 Instruments、Thread Sanitizer、Memory Graph、Logcat、Profiler、Layout Inspector、StrictMode 時寫出怎麼開。Practice 的步驟要包含「寫 TODO ①」「寫 TODO ②」 |
| `expectBug`／`expectFixed` | 寫對了會看到的具體結果（數字、log 的那一行、畫面上的現象） |
| `file`（只有 Kotlin） | 相對於區資料夾的路徑，例如 `"kotlin03-coroutines/L01_ScopeLeak.kt"`。Swift 自動用 `#filePath` |
| `bugCrashes` | Bug 版寫對了會讓 App 當掉時設 true（自動檢查會略過 Bug 版） |
| `needsDevice`（只有 Kotlin） | `run` 用到 Android 系統（Context、Looper、Dispatchers.Main…），JVM 單元測試跑不了時設 true |
| `run` | 按「執行」時跑的程式，從主執行緒開始；把觀察到的結果寫進 `log` |
| `demo` | 需要畫面的演練（SwiftUI／Compose 狀態、列表、生命週期、卡頓）。切換版本時整個重建 |
| `checkBug`／`checkFixed` | 拿到這次執行的 log，回傳 `nil`／`null` 代表符合預期，否則回傳還差什麼（白話、指出要改哪裡） |

- 檢查回傳的訊息不要含 `<` 字元（Android 的測試結果 XML 沒有跳脫，`grep "[^<]*"` 會在那裡截斷）。
- **能寫檢查就寫。** 只有結果不固定（例如資料競爭每次不同）或只能看畫面的演練可以不寫，並在 `steps` 說明要看什麼。
- **兩個平台的執行環境不同時，檢查要接受兩種結果**：例如迭代時 `remove`，電腦上的 JVM 默默漏掉一筆、Android 丟 `ConcurrentModificationException`（Kotlin10 L07）。JVM 測試過了不代表手機上也一樣，主 agent 會在模擬器上抽查。
- 長時間的運算放到背景（Swift：`Task.detached`；Kotlin：`withContext(Dispatchers.Default)`），除非這個演練就是要示範卡住主執行緒。
- 量時間的演練：檢查用寬鬆的門檻（例如 Bug 版 ≥ 5 ms、修正版 < 5 ms），或改用結構上的檢查（例如用 `Mirror` 數 class 欄位）。Debug 與 Release 差很多時，在註解與 `expectBug` 寫清楚，並寫出實測數字。iOS 用 `LABS_CONFIG=Release ./check.sh selftest <代號>` 跑 Release。
- Release 的兩個陷阱：區域變數最後一次使用後就可能被釋放（Combine 的 `AnyCancellable` 存在區域變數會被提早取消，用 `withExtendedLifetime` 或存在物件上）；簡單的迴圈可能被最佳化成一次運算（資料競爭重現不了，用 `@inline(never)` 的函式）。
- `run` 要在 30 秒內結束；Bug 版故意洩漏的背景工作要有上限（例如輪詢 30 次就停），不要無限跑。
- Bug 版洩漏的背景工作會在同一次執行裡繼續跑，可能污染接著跑的修正版：計數用每次執行各自的名稱或物件，不要共用全域計數。
- Swift 的 `run` 不在主執行緒上執行。要碰 UIKit／SwiftUI 時，把流程寫成 `@MainActor static func run(_ log: LabLog) async`。
- `demo` 的 `init` 與 `body` 裡不要寫 log（會在畫面更新途中改 `@Published`）；在 `onAppear`、`.task`、按鈕動作裡寫。
- Objective-C 的類別名稱要加前綴（Practice 用 `PRLab`、Solution 用 `SOLab`），新增的 header 要加進同一層 `include/ObjCLabs.h` 的 `#import`。

## 5. 共用工具（LabKit）

| iOS | Android | 用途 |
|---|---|---|
| `log("…")` | `log("…")` | 任何執行緒都能寫，會標出執行緒 |
| `LiveObjects.shared.add/remove("名稱")`、`LiveObjectsView(names:)` | `LiveObjects.add/remove`、`LiveObjectsView(…)` | 看物件有沒有被釋放 |
| `BodyCount.tick("名稱")`、`BodyCountView(names:)` | `RecomposeCounter("名稱")` | 看 SwiftUI body／Compose 重組了幾次（`RecomposeCounter` 數的是放它的那個 Composable） |
| `FrameMeter()` | `FrameMeter()` | 看 FPS 與卡頓次數 |
| `SwiftUILabStage(view)`、`SwiftUILabTally`、`SwiftUILabProbe` | — | 自動檢查 SwiftUI／UIKit 畫面：把 view 放進螢幕外的視窗跑、數物件建立幾次、記下 body 顯示的值（用法見 Swift07–09） |
| — | `FakeScreenHost(log) { … }` | 模擬打開／關閉畫面（裡面的 `viewModel()` 會在關閉時 onCleared） |
| — | `withHeadlessCompose { setContent { … }; settle() }`、`HeadlessText(text) { … }` | 沒有畫面也能真的跑 Composable（effect、重組、跳過、CompositionLocal），讓 Compose 演練有自動檢查、在 JVM 測試也能跑（用法見 Kotlin07、09）。注意：捕獲「會被修改的 `var`」的 lambda 不會被記住，傳給子 Composable 會讓它每次重組，計數用 `AtomicInteger` |

## 6. 單元代號與資料夾

代號是「編號＋單元重點」，iOS 與 Android 共用。完整清單在 iOS 的 `Practice/<區>/<區>Section.swift`、Android 的 `practice/<區>/section/<區>Section.kt`。

| 複習站單元 | iOS 資料夾（`ios/{Practice,Solution}/`） | Android 資料夾（`android/{practice,solution}/`） | 代號 |
|---|---|---|---|
| swift/01 | `Swift/Swift01-ValueSemantics` | — | `Swift01ValueSemantics` |
| kotlin/03 | — | `kotlin/kotlin03-coroutines` | `Kotlin03Coroutines` |
| platform/01 | `Platform/Platform01-Lifecycle` | `platform/platform01-lifecycle` | `Platform01Lifecycle` |
| exchange/05 | `Exchange/Exchange05-OrderBook` | `exchange/exchange05-order-book` | `Exchange05OrderBook` |
| … | 其他照同樣的規則，已經全部建好空的單元資料夾與清單檔 | | |

雙平台的單元：同一題 iOS、Android 各做一個演練（L 編號一樣），除非那題只和一個平台有關（例如 Doze 只有 Android）。

## 7. 設定檔

- **iOS**：預設 Swift 5 語言模式、`SWIFT_STRICT_CONCURRENCY = minimal`、iOS 17。Info.plist 的權限說明（Face ID、相機…）在 `ios/project.yml` 的 MobileLabs target，改完執行 `./generate.sh`。不加第三方套件。Objective-C 程式放 `Practice/SwiftObjC`、`Solution/SwiftObjC`（SPM 一個 target 不能混 Swift 與 Objective-C）。
- **Android**：每一區的套件在 `practice/<區>/build.gradle.kts` 與 `solution/<區>/build.gradle.kts`（兩邊要一樣）；權限、Activity 寫在同一層的 `AndroidManifest.xml`（需要時建立）。JVM 單元測試放在區資料夾的 `test/`。

## 8. 複習站的 `## 實際演練`

格式見 `content/SCHEMA.md` §2.3 與兩個範本：每個演練寫「檔案、情境、怎麼寫（Bug 版、修正版各一段）、會看到什麼、為什麼」，最後用兩個 ```` ```lab-solution ```` 附上解答（`#bug`、`#fixed`）。
App 裡的 `goal`／`expectBug`／`expectFixed` 和複習站的文字要一致。

## 9. 交付前檢查

**iOS**

```bash
cd labs/ios
./check.sh selftest <代號>          # 解答：每個有檢查的版本都要 PASS
./check.sh practice <代號>          # 練習骨架：都能跑完，而且（除了沒有檢查的）都是 FAIL
./check.sh run <代號> L0x solution bug   # bugCrashes 的演練：確認真的當掉
./check.sh open <代號> L0x solution      # 畫面類演練：截圖檢查
```
（模擬器只有 MobileLabs-Main 一台，大家共用，`check.sh` 會自動排隊。在 worktree 裡用 `LABS_DD=<你的 DerivedData> LABS_SHOT=<截圖路徑>` 指定，建置才不會互相干擾；不要另外開模擬器。）

**Android**

```bash
cd labs/android
./gradlew :app:assembleDebug :solution-<區>:testDebugUnitTest :practice-<區>:testDebugUnitTest
grep -ho "\[SELFTEST\][^<]*" solution/<區>/build/test-results/testDebugUnitTest/*.xml
grep -ho "\[SELFTEST\][^<]*" practice/<區>/build/test-results/testDebugUnitTest/*.xml
```
- `gradle.properties` 開了 build cache，沒改程式時測試結果會從快取還原（數字一模一樣，看起來像很穩定）。驗證量時間或競爭類的演練時加 `--no-build-cache`，重跑 2–3 次。
- Android 要真的做 GC（量記憶體、用 `WeakReference` 判斷洩漏）時，用 `Runtime.getRuntime().gc()`，等約 100 ms 再 `System.runFinalization()`（LeakCanary 的做法）。Android 的 `System.gc()` 只是提示，電腦上的 JVM 會回收、手機上不一定。
- Android 模擬器（API 29）每個 App 的 heap 上限只有 48 MB（`adb shell getprop dalvik.vm.heapgrowthlimit`）。故意累積記憶體的 Bug 版要控制在 30 MB 以內，否則 App 會直接 `OutOfMemoryError` 被殺掉、檢查跑不到。
- 數字格式在兩個平台的預設行為不同，演練要讓兩邊結果一致：Java 的 `"%.2f".format(1.005)` 得到 1.01（先取最短十進位表示再 HALF_UP），Swift／C 的 `printf` 得到 1.00；要示範 `Double` 捨入的 bug，兩邊都用「乘 100、取整、除 100」的寫法。`NumberFormat.setCurrency(JPY)` 不會改小數位數，要另外設 `minimumFractionDigits`／`maximumFractionDigits`。
- Gson 遇到不認識的 enum 值不會丟例外，而是悄悄給 `null`（Kotlin 宣告成非 null 也一樣），之後才 NPE；iOS 的 `Decodable` 則直接丟 `DecodingError`。URL 表單解碼（`+` 變空白）和 RFC 3986 不同，test vector 要包含 `+`。
- Android 解析 JSON 用 Gson（`exchange` 區已加），不要用內建的 `org.json`：JVM 單元測試裡它是空殼（`isReturnDefaultValues = true`，都回 null 或 0），電腦和手機結果會不一樣。
- 需要真的 HTTP 行為（MockWebServer、真的 socket、TLS 憑證）的 Android 演練也放 `test/`：App 的 `run` 沒有 mockwebserver 依賴，裝置上也有明文連線限制。單元測試編譯用的是 mockable android.jar，部分 JDK API（例如 `ClassLoader.getPlatformClassLoader()`）編譯不過，換別的寫法。
- 只能用單元測試驗證的演練（例如要用 `runTest` 的虛擬時間），`run` 設 `null`，檢查寫在區資料夾的 `test/`（解答用 assert，練習只印 `[SELFTEST]` 不讓建置失敗）。

**複習站**：`npm run content:check` 要 0 錯誤、0 警告（lab fence 的路徑或區段寫錯會出警告）。

## 程式碼排版（2026-10-25 起，兩個平台都適用）

複習站的「實際演練」會把這裡的原始碼直接顯示在網頁上，所以排版要以「在網頁和手機上好讀」為準：

- 每行最多 80 欄（中日韓全形字算 2 欄）。字串字面值、URL、ASCII 圖例外，不要為了湊字數改字串內容。
- 註解寫在它說明的那行程式碼**上一行**，同縮排；行尾只放 20 欄以內的短註解。
- 一行只做一件事：不用 `;` 串接、`if` 不寫成單行、closure 裡有兩個以上陳述就換行。
- 宣告、主體、回傳之間空一行。
- `// MARK: - Bug 版`／`// MARK: - 修正版`／`// MARK: - 演練設定` 三行一字不差，複習站靠它擷取區段。

工具（寫完新演練先跑，再跑自動檢查）：

```
# iOS：Xcode 內建的 swift-format，設定檔在 ios/.swift-format
cd ios && xcrun swift-format format --in-place --recursive Practice Solution LabKit App

# Android：ktlint（brew install ktlint），設定檔在 android/.editorconfig
cd android && ktlint -F "practice/**/*.kt" "solution/**/*.kt" "labkit/**/*.kt" "app/**/*.kt" "!**/build/**"
```

兩個工具都不會把行尾註解移到上一行，寫的時候就直接寫在上一行。
