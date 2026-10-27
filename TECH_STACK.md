# 技術總覽：三個專案各用什麼

這個 repo 有三個可以執行的專案：**複習站**（網站）、**iOS 演練 App**、**Android 演練 App**。
這份文件統整每個專案的 UI 架構、網路、資料儲存、測試與建置工具，方便一眼對照。
版本號以各專案的設定檔為準（`package.json`、`labs/ios/project.yml`、`labs/android/buildSrc/src/main/kotlin/Versions.kt`），這裡只寫主要版本。

## 一覽表

| | 複習站（網站） | iOS 演練 App | Android 演練 App |
|---|---|---|---|
| 位置 | 根目錄（`src/`、`runner/`、`scripts/`） | `labs/ios/` | `labs/android/` |
| 語言 | TypeScript | Swift（語言模式 5，工具鏈 6） | Kotlin 2.2 |
| UI 框架 | React 19 | SwiftUI（部分演練用 UIKit 對照） | Jetpack Compose（Material 3；一個演練用 RecyclerView 對照） |
| UI 架構 | 單頁 App、hash router、外部 store + `useSyncExternalStore` | 單一 `NavigationStack`、`Lab` 資料驅動畫面 | 單一 Activity、`rememberSaveable` 的字串 route、`Lab` 資料驅動畫面 |
| 模組切分 | 依功能分 `lib/`、`pages/`、`components/` | 3 個 Swift Package：`LabKit`、`Practice`、`Solution`，每一區一個 target | `:app`、`:labkit`、每一區一個 `:practice-<區>`／`:solution-<區>` module |
| 網路 | 瀏覽器 `fetch` 打本機 `/api/*` | `URLSession`（系統內建，沒有第三方套件） | OkHttp 4（HTTP 與 WebSocket） |
| JSON | 原生 `JSON` ＋ zod（內容驗證） | `Codable`／`JSONSerialization` | Gson |
| 資料儲存 | `localStorage` ＋ 本機 JSON 檔（`data/*.json`）；靜態版另有 claude.ai 雲端 db | `UserDefaults`、Keychain、`FileManager`、Core Data（只在安全儲存單元示範） | `SharedPreferences`、DataStore Preferences、Tink 加密（只在安全儲存單元示範） |
| 本機資料庫 | 無（JSON 檔即資料） | Core Data（示範用，App 本身不存資料） | 無（Room 只在筆記中對照） |
| 非同步 | Promise／async-await | Swift Concurrency（async/await、actor）、Combine | kotlinx.coroutines、Flow／StateFlow |
| 相依注入 | 無 | 無（演練裡以建構子注入示範） | 無（演練裡以建構子注入示範，Hilt 只在筆記提及） |
| 測試 | `scripts/regress-data.ts`（資料層回歸）、`tsc --noEmit` | App 內自動檢查＋ `check.sh`（模擬器 selftest） | JUnit 4 ＋ coroutines-test、Turbine、MockK、Robolectric、MockWebServer |
| 建置 | Vite 8 | XcodeGen 產生 `.xcodeproj`、Swift Package Manager | Gradle（AGP 8.13，`buildSrc` 共用設定），Compose BOM |
| 最低版本 | 現代瀏覽器（ES2020） | iOS 17 | minSdk 26、compileSdk 36 |

## 複習站（網站）

### UI 架構

- **React 19 + TypeScript**，Vite 建置。進入點 `src/main.tsx`，根元件 `src/App.tsx`。
- **路由**：`src/lib/router.ts` 是極簡 hash router，網址只用不含斜線的純錨點（`#home`、`#practice`、`#<unitId>`…），以 `useSyncExternalStore` 訂閱。沒有用 react-router。
- **狀態**：`src/lib/store.ts` 是自製的外部 store（進度、我的口徑），元件用 `useStore` 訂閱；純函式的合併規則在 `src/lib/data.ts`，前端與本機 server 共用。沒有 Redux／Zustand 之類的套件。
- **內容**：`content/` 的 YAML 在建置時經 zod 驗證、markdown-it 轉 HTML、shiki 上色，產出 `src/generated/content.json`；前端只負責顯示。
- **編輯器**：程式題用 CodeMirror 6（`@uiw/react-codemirror`）。
- **樣式**：純 CSS，`src/styles/` 依 tokens／base／layout／content／exercise／pages 分檔；沒有 CSS 框架。

### 網路

- 前端用瀏覽器內建的 `fetch`（`src/lib/api.ts`），只打同站的 `/api/*`，沒有第三方 HTTP 套件。
- `/api/*` 掛在 Vite dev／preview server 上（`runner/server-plugin.ts`），不另開 server：
  - `POST /api/run`：編譯執行 Swift／Kotlin 程式題（`runner/run-code.mjs` 以子程序呼叫 `swiftc`、`kotlinc`），只限 localhost。
  - `POST /api/grade`：呼叫本機 `claude` CLI 做 AI 面試官評分，只限 localhost。
  - `GET/PUT /api/answers`、`/api/progress`：讀寫 `data/*.json`，區網需帶 `X-Prep-Token`。
- 靜態版（claude.ai Artifact）沒有 server：AI 評分改走 `sample` capability。

### 資料儲存

- **瀏覽器**：`localStorage`（`src/lib/storage.ts` 一律包 try/catch，失敗退回記憶體）。存進度、口徑、還沒送出的 dirty 欄位。
- **本機版**：`data/progress.json`、`data/my-answers.json`，server 原子寫入、跨行程檔案鎖（`runner/file-lock.ts`），壞檔改名備份不覆寫。時間戳記以 server 為準。
- **靜態版**：claude.ai 的 `db` capability（`src/lib/cloud.ts`），每位使用者存在私有路徑底下，每題一份文件、每份 < 256 KiB。
- 三者之間都是**欄位級合併＋歷史版本**，規則見 `README.md`「口徑不會遺失」。沒有 SQL 資料庫。

### 測試與工具

- `npm run typecheck`（tsc）、`npm run test:data`（資料層回歸＋ store 整合測試，用 vm 模擬多台裝置）。
- `npm run content:check`（zod 嚴格驗證）、`npm run tasks:verify`（程式題的 solution／starter 都真的編譯執行）。
- 沒有用 Vitest／Jest；測試腳本直接用 `tsx` 跑。

## iOS 演練 App（`labs/ios/`）

### UI 架構

- **SwiftUI**，iOS 17 以上。進入點 `App/MobileLabsApp.swift` 只有一個 `WindowGroup`，放 `LabKit` 的 `LabRootView`。
- **導覽**：單一 `NavigationStack(path:)`，路徑是 `LabRoute` enum（單元、演練兩層）。沒有 TabView、沒有 coordinator。
- **資料驅動**：每個演練是一個 `Lab` struct（`LabKit/Sources/LabKit/Lab.swift`），帶 `run`／`demo`／`checkBug`／`checkFixed` closure；畫面由 `LabKit` 統一繪製，演練只提供資料與行為。
- **狀態**：`@State`、`@AppStorage`（首頁排法）；演練內依題目示範 `ObservableObject` 與 `@Observable`。
- **UIKit**：只在需要對照的單元出現（`UIViewRepresentable`、`UIViewController` 生命週期、`UITableView` 捲動效能）。
- **模組**：`LabKit`（共用畫面、log、自動檢查）、`Practice`、`Solution` 三個 Swift Package；Practice／Solution 內每一區一個 target（`PracticeSwift`、`PracticeExchange`…），結構一模一樣。另有 `SwiftObjC` target 放 Objective-C 對照。
- 專案檔由 **XcodeGen**（`project.yml`）產生，演練檔案直接放進資料夾即可，不用重新產生。

### 網路

- 只用系統的 **`URLSession`**（逾時、憑證釘選、401 刷新、重試 jitter 等在 `Platform05-Networking` 示範），沒有 Alamofire 之類的套件。
- WebSocket 相關單元（`Exchange03`、`Exchange04`…）**不連真的伺服器**：用程式內的假伺服器或準備好的訊息，示範 Huobi 相容公開協定的 gzip frame、心跳、重連、重新訂閱。
- JSON 用 `Codable`／`JSONSerialization`；gzip 用 `Compression`；簽名用 `CryptoKit`。

### 資料儲存

- App 本身只存一個 `@AppStorage` 偏好，沒有資料庫。
- `Platform04-SecureStorage` 單元示範各種儲存的正確用法：`UserDefaults`、Keychain（`Security`）、`FileManager` 檔案保護等級、`LocalAuthentication`（Face ID）、**Core Data**（context 過期、migration）；筆記中對照 SwiftData、Room、Realm 的選型。
- 其他單元用到 `UserDefaults` 的只是演練情境（深連結、release 設定、觀測）。

### 測試與工具

- 每個演練內建自動檢查（`checkBug`／`checkFixed` 讀 log 判斷）。
- `check.sh`：不開 Xcode 在模擬器 `MobileLabs-Main` 上跑 `practice`／`selftest`／`run`／`open`，會自動排隊。
- 沒有 XCTest target；`Testing` 區的演練在 App 內示範測試策略。

## Android 演練 App（`labs/android/`）

### UI 架構

- **Jetpack Compose + Material 3**（Compose BOM），Kotlin 2.2，minSdk 26。
- **單一 Activity**：`MainActivity` 繼承 `labkit` 的 `LabRootActivity`，`setContent` 放 `LabRoot`。
- **導覽**：沒有 navigation-compose。`LabRoot` 用一個 `rememberSaveable` 的字串 route（`""`、`Kotlin03Coroutines`、`Kotlin03Coroutines/L05`）決定畫面，`BackHandler` 往回切；可用 `adb shell am start … --es open <代號>/<L編號>` 直接開某個演練。
- **資料驅動**：每個演練是一個 `Lab` class（`labkit/.../Lab.kt`），帶 `run`（suspend）、`demo`（`@Composable`）、`checkBug`／`checkFixed`；另有 `needsDevice` 標記 JVM 測試跑不了的演練。
- **狀態**：Compose state（`mutableStateOf`、`rememberSaveable`）；演練內示範 `ViewModel` + `StateFlow`、`collectAsStateWithLifecycle`。
- **View 系統**：只在 `Advanced02-Jank` 用 RecyclerView 對照。
- **模組**：`:app`、`:labkit`，加上每一區各一個 `:practice-<區>`、`:solution-<區>`（`settings.gradle.kts` 自動掃描）；共用設定在 `buildSrc/src/main/kotlin/lab-section.gradle.kts`，版本集中在 `Versions.kt`。每一區的 `build.gradle.kts` 只列該區用到的套件，practice 與 solution 要一樣。

### 網路

- **OkHttp 4**（`platform`、`exchange`、`advanced`、`testing` 區）：HTTP 逾時、TLS、WebSocket。
- WebSocket 單元同樣**不連真的伺服器**，用假伺服器或 `MockWebServer`。
- JSON 用 **Gson**（Android 內建的 `org.json` 在 JVM 單元測試裡是空殼，Gson 在電腦和手機上行為一樣）。
- Retrofit、Ktor、kotlinx.serialization 都沒有用，只在筆記中提及。

### 資料儲存

- App 本身沒有資料庫，也沒有 Room。
- `Platform04-SecureStorage` 單元示範 `SharedPreferences`、**DataStore Preferences**、**Tink** 加密、生物辨識；其他單元提到的「本機儲存」都是演練裡的假物件。
- 背景工作用 **WorkManager**（`Platform03-BackgroundPush` 真的排程；其他單元只在筆記對照）。

### 測試與工具

- 每一區的 `test/` 是 JVM 單元測試：JUnit 4、`kotlinx-coroutines-test`（`runTest` 虛擬時間）、Turbine（Flow）、MockK、Robolectric（在電腦上跑 Compose 畫面測試）、MockWebServer、`okhttp-tls`。
- `./gradlew :solution-<區>:testDebugUnitTest` 跑解答的自動檢查；要畫面或 Android 系統的演練標 `needsDevice`，在 JVM 上顯示 SKIP。
- debug 版自動帶 **LeakCanary**（`Advanced03-Memory` 的演練會用到）。
- `platform` 區的測試用 R8 驗證混淆規則。

## 三個專案共用的東西

- `labs/topics.yaml` 是「四大主題」（記憶體 → 效能 → 架構 → UI/UX）的單一來源，`npm run topics:gen` 產生 iOS `JdFocus.swift` 與 Android `JdFocus.kt`。
- 單元代號（`Swift02ARC`、`Kotlin03Coroutines`、`Exchange05OrderBook`）在複習站、iOS、Android 三邊一致；雙平台的單元用同一個代號。
- 交易所協定一律寫「Huobi 相容的公開協定」，所有演練都用假伺服器，不打真的交易所。

## 刻意不用的套件

| 類別 | 沒用 | 原因 |
|---|---|---|
| iOS 網路 | Alamofire、Starscream | 演練的重點是 `URLSession` 本身的行為（兩種逾時、憑證釘選、重試） |
| Android 網路 | Retrofit、Ktor | 演練停在 OkHttp 層，看得到逾時、連線池、WebSocket frame |
| 資料庫 | Room、Realm、SwiftData（App 本身） | App 不需要持久化；資料庫只在安全儲存單元用 Core Data 示範 |
| 相依注入 | Hilt、Koin | 演練用建構子注入就看得到依賴方向，不想讓框架遮住重點 |
| 導覽 | navigation-compose、coordinator | 只有兩層畫面，一個 route 字串或 `NavigationStack` path 就夠 |
| 網站狀態 | Redux、Zustand、react-router | store 的合併規則要前後端共用純函式，自製比套件直接 |
