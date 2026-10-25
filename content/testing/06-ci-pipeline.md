---
id: testing-ci
title: CI 整合：PR 要快、合併後要完整、夜間要全面，以及安全地發布
summary: 測試寫得再好，沒有放進自動化流程就沒有保護力。這個單元說明 CI 要怎麼分段（PR 十分鐘內、合併後完整、夜間全面）、怎麼讓 CI 變快、結果怎麼通知，以及發布時怎麼用分階段發布和指標當安全網。
priority: P1
day: 5
tags: [ci, cd, fastlane, testing]
---

## 重點講解

這個單元回答四個問題：

1. CI 要分成哪幾段？每段跑什麼？
2. CI 太慢的時候，怎麼讓它變快？
3. 測試結果要怎麼通知、怎麼呈現？
4. 發布的時候，怎麼避免把有問題的版本推給所有使用者？

CI（Continuous Integration，持續整合）是每次有人推程式碼，就自動建置、跑測試的機制；CD（Continuous Delivery／Deployment）是自動打包、發布。

### 1. CI 要分成哪幾段？每段跑什麼？

**結論：依「多久跑一次」分段。越常跑的越要快，完整、慢的測試放到比較少跑的階段。**

| 階段 | 什麼時候跑 | 跑什麼 | 目標時間 |
|---|---|---|---|
| PR | 每次推程式碼到 PR | 程式碼風格檢查（lint）、編譯、單元測試、截圖比對；（可選）AI review | 10–15 分鐘內 |
| 合併後 | main 分支更新 | 完整的單元與整合測試、Maestro 關鍵流程、建置測試版並發給內部測試 | 30 分鐘內 |
| 每天晚上 | 排程 | device farm 上完整的端到端測試、效能基準、AI 探索 agent、檢查套件有沒有新版本 | 幾個小時也可以接受 |
| 發布 | 打版本 tag | 建置正式版、上傳符號檔、送審、分階段發布 | — |

為什麼 PR 要快？如果 PR 要等 40 分鐘，工程師會開始不等結果就合併，或想辦法跳過測試，測試就失去了保護的作用。

> [!TIP]
> 面試時可以這樣說：「我會把 CI 分成三層：PR 只跑快速的單元測試和截圖比對，控制在十分鐘左右；合併後跑完整測試和關鍵流程；每天晚上在實機上跑完整的端到端測試和 AI 探索。」

### 2. CI 太慢的時候，怎麼讓它變快？

- **快取**：Gradle 的 build cache 和 configuration cache；SPM（Swift Package Manager，Apple 官方的套件管理工具）、CocoaPods 的套件快取；iOS 的 DerivedData（編譯的中間產物）。要注意快取失效時重建的成本。
- **平行執行和分片**：iOS 用 `xcodebuild -parallel-testing-enabled YES` 讓測試在多個模擬器上同時跑；Android 的多模組可以平行建置；device farm 可以把測試分成好幾份（分片），在多台裝置上同時跑。
- **只跑受影響的部分**：模組化之後，可以依「這次改了哪些模組」決定要跑哪些測試。
- **取消過時的建置**：同一個 PR 又推了新的程式碼時，自動取消舊的那次建置，不浪費機器時間。

> [!TIP]
> 面試時可以這樣說：「CI 太慢的時候，我會用快取，例如 Gradle 的 build cache 和套件快取；讓測試平行執行、分片，在多個模擬器或多台裝置上同時跑；模組化之後只跑這次改到的部分；同一個 PR 又推了新的程式碼，就取消舊的建置。」

### 3. 測試結果要怎麼通知、怎麼呈現？

- 測試結果輸出成標準格式（JUnit XML、iOS 的 `.xcresult`），在 PR 頁面上直接顯示哪些測試失敗、附上截圖。
- 失敗時發 Slack 通知，附上連結和失敗的分類（可以讓 AI 幫忙判斷是產品 bug、時好時壞的測試，還是環境問題）。
- 時好時壞的測試統計和隔離名單，要讓整個團隊看得到。

> [!TIP]
> 面試時可以這樣說：「測試結果我會輸出成標準格式，在 PR 頁面上直接看到哪些測試失敗，並附上截圖。失敗時發 Slack 通知，附上連結和失敗的分類；時好時壞的測試統計和隔離名單，也要讓整個團隊看得到。」

### 4. 發布的時候，怎麼避免把有問題的版本推給所有使用者？

**結論：自動化發布流程（不靠人手操作），加上分階段發布，用 crash 率等指標決定要不要繼續擴大。**

- **Fastlane 自動化**：版號遞增、建置、簽章（用 match 或 App Store Connect API key 管理憑證）、上傳 TestFlight 或 Google Play 的內部測試軌、上傳符號檔（iOS 的 dSYM、Android 的 mapping 檔）。
- **符號檔一定要上傳**：正式版的程式碼經過編譯和混淆，crash 報告上只會看到記憶體位址或被改過的名稱。上傳 dSYM／mapping 之後，Crashlytics 才能把它還原成看得懂的函式名稱和行號。
- **分階段發布**：iOS 的 phased release（7 天內逐步開放給更多使用者）、Google Play 的 staged rollout（自己設定百分比）。先給一小部分使用者，看 crash-free 比例、ANR（Application Not Responding，App 卡住沒有回應）和關鍵指標，有異常就暫停。
- **交易所 App 還要考慮**：審查需要的時間、緊急修正的流程，以及和後端 API 的相容性（舊版 App 還有人在用，API 不能說改就改）。

> [!TIP]
> 面試時可以這樣說：「發布流程我用 Fastlane 自動化，從版號、建置、簽章到上傳和符號檔都不靠人手。發布用分階段，先開放一小部分使用者，看 crash-free 和 ANR，有異常就暫停，修好再用新版本重新開始。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Testing06CI**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Testing06CI**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Testing/Testing06-CI/`，解答在 `labs/ios/Solution/Testing/Testing06-CI/`；Android 你寫在 `labs/android/practice/testing/testing06-ci/`，解答在 `labs/android/solution/testing/testing06-ci/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到真的 CI 服務**：CI 的設定檔（GitHub Actions、Codemagic 等）要推到遠端才會跑，這個專案不連外部服務，所以演練把 CI 裡「容易寫錯的判斷」抽成純函式：合併條件、測試分片、快取的 key、分階段發布的決定。每次執行的結果都一樣。每個演練的「為什麼」附上對應的真實設定檔寫法。

### 演練 1：單元測試根本沒跑，PR 卻可以合併（第 1 題）

**檔案**：`L01_GatingSkipped.swift`（iOS）、`L01_GatingSkipped.kt`（Android）

**情境**：PR 的 CI 有幾個檢查（lint、建置、單元測試、截圖比對，另外有一個非必要的夜間檢查）。`canMerge(checks)` 決定「這個 PR 能不能合併」。每個檢查的結果有四種：成功、失敗、被取消（例如逾時）、被略過（例如條件寫錯）。演練用五種情況各判斷一次：① 全部成功；② 單元測試失敗；③ 單元測試逾時被取消；④ 單元測試被略過；⑤ 非必要的夜間檢查失敗。

**怎麼寫**

1. **Bug 版**：只要「沒有必要（`required` 是 true）的檢查失敗」就回傳 true。
2. **修正版**：只看必要的檢查，而且每一個的結果都必須是「成功」才回傳 true。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ① 允許合併；② 擋下；③ 允許合併、④ 允許合併（單元測試沒有跑完，或根本沒跑）；⑤ 允許合併 |
| 修正版 | ① 允許合併；② ③ ④ 擋下；⑤ 允許合併（非必要的檢查失敗不影響） |

**為什麼**：「沒有失敗」和「全部成功」不一樣。被取消、被略過的檢查沒有驗證任何東西，卻不是「失敗」。GitHub Actions 的 required check 還有一個陷阱：被略過的 job 會被當成通過。解法是再加一個 gate job，要求前面所有 job 的結果都是 `success`：

```yaml
jobs:
  # lint、build 這兩個 job 省略
  unit-tests:
    runs-on: macos-15
    steps:
      - run: xcodebuild test -scheme ExchangeApp -destination 'platform=iOS Simulator,name=iPhone 17'

  gate:
    if: always()                        # 前面的 job 失敗、被取消、被略過時，這個 job 仍然要跑
    needs: [lint, build, unit-tests]
    runs-on: ubuntu-latest
    steps:
      - name: 前面的 job 都必須成功
        run: echo '${{ toJSON(needs) }}' | jq -e 'all(.[]; .result == "success")'
```

branch protection 的 required check 只設 `gate` 這一個，不用逐一列出每個 job。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing06-CI/L01_GatingSkipped.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing06-CI/L01_GatingSkipped.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing06-ci/L01_GatingSkipped.kt#bug
```

```lab-solution
android/solution/testing/testing06-ci/L01_GatingSkipped.kt#fixed
```

### 演練 2：分成 3 份同時跑，還是等了 12 分鐘（第 2 題）

**檔案**：`L02_UnbalancedShards.swift`（iOS）、`L02_UnbalancedShards.kt`（Android）

**情境**：12 個測試檔（每個有上一次量到的耗時，總共 1160 秒）要分成 3 份，在 3 台機器上同時跑。整體要等「最慢的那一份」跑完。`shard(tests, count)` 決定每份分到哪些測試檔。最理想的情況是每份約 387 秒。

**怎麼寫**

1. **Bug 版**：照檔案順序平分數量，每份連續 4 個檔案（Swift 用 `stride`，Kotlin 用 `chunked`）。
2. **修正版**：依耗時分配：先把測試依耗時由大到小排序，每個測試都放進「目前總耗時最少」的那一份。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「每份耗時：770 秒、310 秒、80 秒」「整體要等 770 秒」 |
| 修正版 | 「每份耗時：390 秒、390 秒、380 秒」「整體要等 390 秒（最理想約 387 秒）」 |

**為什麼**：分片以後，整體時間是「最慢的那一份」，不是平均。Bug 版平分的是檔案數量，慢的檔案剛好排在前面，第一份 770 秒，第三份只要 80 秒，三台機器同時跑只比一台快 1.5 倍。依耗時分配（先排慢的、放進目前最空的那一份，是 greedy 的作法，不一定是最佳解，但很接近）就接近平均。耗時要用上一次 CI 量到的資料，並且持續更新。真實的做法是把測試分成幾份，再讓每台機器只跑自己的那份：

```bash
# iOS：每台機器只跑分到的測試（-only-testing 可以重複指定）
xcodebuild test-without-building -scheme ExchangeApp -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:ExchangeAppTests/OrderBookTests -only-testing:ExchangeAppTests/PrecisionTests

# Android：Firebase Test Lab 可以自動分片
gcloud firebase test android run --type instrumentation --app app-debug.apk --test app-debug-androidTest.apk \
  --num-uniform-shards=3
```

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing06-CI/L02_UnbalancedShards.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing06-CI/L02_UnbalancedShards.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing06-ci/L02_UnbalancedShards.kt#bug
```

```lab-solution
android/solution/testing/testing06-ci/L02_UnbalancedShards.kt#fixed
```

### 演練 3：套件升級了，CI 還在用舊版本的快取（第 2 題）

**檔案**：`L03_StaleCache.swift`（iOS）、`L03_StaleCache.kt`（Android）

**情境**：CI 把「編好的套件」存進快取，下次有同樣的 key 就直接拿來用，省下重新編譯的時間。`cacheKey(os, lockfile)` 決定快取的 key。三天的情況：第 1 天鎖定檔寫舊版本，第一次建置，沒有快取，編好後存進去；第 2 天有人把套件升級，鎖定檔改了；第 3 天鎖定檔沒有再改。iOS 的鎖定檔相當於 `Package.resolved`（`Alamofire@5.9.0` 升到 `5.10.0`），Android 相當於 Gradle 的依賴鎖定檔（`okhttp@4.11.0` 升到 `4.12.0`）。

**怎麼寫**

1. **Bug 版**：`cacheKey` 回傳固定的字串（iOS `"spm-macos"`、Android `"gradle-linux"`），和鎖定檔無關。
2. **修正版**：key 加上鎖定檔內容的雜湊：`"spm-\(os)-\(T06Hash.shortHash(lockfile))"`（Android 同理，`"gradle-$os-${T06Hash.shortHash(lockfile)}"`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第 1 天沒命中；第 2 天**命中**，CI 拿到「用舊版本編出來的套件」，並印出 ⚠ 鎖定檔要的是新版本；第 3 天命中，還是舊版本 |
| 修正版 | 第 1 天沒命中；第 2 天沒命中，重新編譯成新版本；第 3 天命中，拿到新版本編出來的套件，沒有 ⚠ |

**為什麼**：快取命中通常是好事，但 key 不包含「會影響內容的輸入」時，命中就變成 bug：PR 在 CI 上測的是舊版本的套件，本機升級後的行為卻沒有被測到。key 要包含鎖定檔的雜湊（內容一改 key 就變），但不要包含日期或亂數（否則永遠不會命中，快取就沒有意義）。GitHub Actions 的寫法：

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.gradle/caches
      ~/.gradle/wrapper
    key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties', 'gradle/libs.versions.toml') }}
    restore-keys: gradle-${{ runner.os }}-      # 沒有完全相同的 key 時，退而求其次用同一個作業系統最近的快取
```

iOS 的 SPM 套件快取，key 改用 `hashFiles('**/Package.resolved')`。注意 `restore-keys` 會拿到「舊的、部分相符的」快取，所以快取裡存的最好是可以增量更新的東西（下載來的套件），不要存會讓舊產物混進去的東西（編譯出的最後成品）。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing06-CI/L03_StaleCache.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing06-CI/L03_StaleCache.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing06-ci/L03_StaleCache.kt#bug
```

```lab-solution
android/solution/testing/testing06-ci/L03_StaleCache.kt#fixed
```

### 演練 4：新版才放 1% 就被暫停，舊版有問題時一直暫停（第 4 題）

**檔案**：`L04_RolloutDecision.swift`（iOS）、`L04_RolloutDecision.kt`（Android）

**情境**：分階段發布時，用 crash-free session 比例（沒有 crash 的 session 佔幾 %）決定下一步：擴大發布、再等一等、暫停發布。`decide(newVersion, baseline)` 拿新版和舊版（baseline）的統計來判斷。演練用四種情況：① 1% 階段，只有 40 個 session、其中 1 個 crash（crash-free 97.5%），舊版 99.4%，資料太少；② 10% 階段，5,000 個 session，crash-free 99.5%，舊版 99.4%，正常；③ 25% 階段，12,000 個 session，crash-free 99.0%，舊版 99.4%，明顯變差；④ 舊版本身就只有 98.8%，新版 98.9%（10,000 個 session），新版沒有比較差。

**怎麼寫**

1. **Bug 版**：新版的 crash-free 低於固定門檻 99.5% 就暫停，否則擴大。
2. **修正版**：session 不到 1,000 個先「再等一等」；夠多了就和舊版比，比舊版低超過 0.3 個百分點才暫停，否則擴大。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ① 暫停發布（資料太少，誤判）；② 擴大發布；③ 暫停發布；④ 暫停發布（新版沒有比舊版差，誤判） |
| 修正版 | ① 再等一等；② 擴大發布；③ 暫停發布；④ 擴大發布 |

**為什麼**：樣本太小時，一個 crash 就讓比例掉到 97.5%，這個數字沒有意義；要先有足夠的 session 再判斷。固定門檻也有問題：舊版本身已經只有 98.8% 時，新版 98.9% 其實沒有變差，卻因為低於 99.5% 一直被暫停；應該和舊版比較。門檻（1,000 個 session、0.3 個百分點）要依 App 的使用量與歷史資料調整。分階段發布本身是商店後台的設定：iOS 的 Phased Release 在 7 天內逐步開放給更多使用者，Google Play 的 staged rollout 自己設定百分比；crash-free 與 ANR 的數據來自 Crashlytics 或 Play vitals，這個專案沒有連這些服務。

**iOS 解答**

```lab-solution
ios/Solution/Testing/Testing06-CI/L04_RolloutDecision.swift#bug
```

```lab-solution
ios/Solution/Testing/Testing06-CI/L04_RolloutDecision.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/testing/testing06-ci/L04_RolloutDecision.kt#bug
```

```lab-solution
android/solution/testing/testing06-ci/L04_RolloutDecision.kt#fixed
```

第 3 題（測試結果怎麼通知與呈現）是 CI 服務與 Slack 的設定，沒有可以自動檢查的 bug，沒有演練。做法：測試結果輸出成標準格式（Gradle 預設就產生 JUnit XML，`xcodebuild` 加上 `-resultBundlePath` 產生 `.xcresult`），用 CI 的測試報告功能在 PR 頁面顯示失敗的測試；失敗時用 Slack 的 incoming webhook 發通知，附上連結與失敗的分類。

## 常見陷阱

- **PR 要跑 40 分鐘。** 大家開始跳過或不等結果就合併。PR 只跑快的，慢的放到合併後或晚上。
- **憑證和金鑰直接寫在 repo 或 CI 設定裡。** 任何能看到程式碼的人都拿得到。要用 CI 的加密變數或 secret 管理工具。
- **CI 只在某個人的電腦上跑。** 那個人不在，就沒人能發布。團隊開發要放到共用的 CI 服務上。
- **沒有上傳 dSYM／mapping。** 正式版的 crash 報告看不懂，沒辦法修。

## 面試官想聽到的

1. PR 快、合併後完整、夜間全面的分段設計。
2. 具體的加速方法：快取、平行、只跑受影響的部分、取消過時的建置。
3. 發布的安全網：分階段發布、用指標決定是否繼續、符號檔。
4. 憑證和金鑰的管理意識。

> [!FIELD]
> 實務上的 CI/CD 經驗可以這樣整理：
> - 社交 App：以 Fastlane 搭配 Codemagic、CircleCI，建立雙平台自動建置與發布流程；協作工具是 Bitbucket，也用過 Bitbucket Pipelines。
> - 即時行情 App：建立 Fastlane 自動發布流程，涵蓋版號遞增、建置、簽署、上傳 TestFlight 與 Firebase App Distribution，並隨版本附上 release notes、發送 Slack 通知。
> - 日本團隊的專案：CI 用 CircleCI、GitHub Actions。
> - 長期負責 App Store Connect 與 Google Play Console 的送審與發布。

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 持續整合 | continuous integration（CI） | 継続的インテグレーション |
| 分階段發布 | phased release／staged rollout | 段階的リリース |
| 符號檔 | dSYM／mapping file | シンボルファイル |
| 快取 | cache | キャッシュ |
| 分片（把測試分成幾份同時跑） | sharding | シャーディング |
| 送審 | app review submission | 審査提出 |
