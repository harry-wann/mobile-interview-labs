---
id: platform-build-sign-release
title: 建置、簽署與發布：簽章與憑證管理、TestFlight 與 Play 測試軌、分階段發布、交易所 App 的審查要求
summary: 從寫完程式到上架給使用者，中間有簽署、測試版發放、分階段發布、上傳除錯用的對照檔。這個單元把 iOS 和 Android 的流程並排說明，並補上加密貨幣交易所 App 在 App Store 和 Google Play 的審查要求。
priority: P1
day: 2
tags: [code-signing, fastlane, match, testflight, phased-release, staged-rollout, play-app-signing, dsym, r8, app-review]
---

## 重點講解

這個單元回答七個面試常見問題，每一題都會同時講 iOS 和 Android：

1. App 的簽署是什麼？兩個平台的簽署模型怎麼對照？
2. fastlane 的 `match` 解決什麼問題？CI 怎麼認證、金鑰怎麼管理？
3. 測試版怎麼發給測試人員？TestFlight 和 Google Play 的測試軌差在哪？
4. 分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？
5. 加密貨幣交易所 App 在 App Store 和 Google Play 的審查有什麼特別的要求？
6. 上線後 crash 報告看不懂，要怎麼處理？（要上傳 dSYM，iOS 的 debug symbol 檔，以及 mapping，Android 混淆後的名稱對照檔）
7. 一個完整的發布流程是什麼樣子？

### 1. App 的簽署是什麼？兩個平台的簽署模型怎麼對照？

**簽署（code signing）是用一把只有你持有的金鑰，在 App 上蓋章，讓系統確認「這個 App 確實是這個開發者發布的，而且沒有被改過」。** 沒有簽署的 App，兩個平台都不會讓它安裝在一般使用者的手機上。兩邊的做法差異很大：

| | iOS | Android |
|---|---|---|
| 用來簽署的東西 | 憑證（certificate）：Development 憑證用來在自己的裝置跑 debug，Distribution 憑證用來發布。憑證的私鑰存在 Mac 的 Keychain（鑰匙圈） | 簽署金鑰，存放在 keystore 檔案（`.jks`） |
| 額外的描述檔 | provisioning profile：把 App ID（Bundle ID）、憑證、允許安裝的裝置清單、entitlements（App 需要的特殊權限，例如推播）打包成一個檔案 | 沒有 |
| 上傳到商店的檔案 | `.ipa`（iOS App Store Package，iOS 的安裝檔，在 Xcode 用 Archive 產生） | **AAB**（Android App Bundle）。自 2021 年 8 月起，新的 App 上架 Google Play 必須使用 AAB，由 Google Play 替每種裝置產生專用的 APK（Android Package） |
| 金鑰誰保管 | 憑證與私鑰由你保管，團隊要想辦法共用（見第 2 題） | **Play App Signing**：Google 保管真正的「App 簽署金鑰」，你只持有「上傳金鑰」，用它簽署 AAB 上傳；上傳金鑰遺失，可以向 Google Play 申請重設 |

**為什麼 Android 要把金鑰分成兩把？** 簽署金鑰是 App 的身分，一旦遺失或外洩，後果很嚴重（沒有它就無法再更新同一個 App）。讓 Google 保管簽署金鑰，你手上的上傳金鑰只用來證明「這份檔案是我上傳的」，遺失時可以重設，風險小很多。這個設計也影響到 Deep Link：Deep Link 單元提過，`assetlinks.json` 要填的是 Google 保管的「應用程式簽署金鑰」的指紋，不是上傳金鑰。

> [!TIP]
> 面試時可以這樣說：「iOS 的簽署靠憑證加 provisioning profile，profile 把 App ID、憑證、裝置和 entitlements 綁在一起。Android 現在都用 Play App Signing，Google 保管簽署金鑰，我們只持有上傳金鑰，遺失時可以重設，所以風險比自己保管單一金鑰小。」

### 2. fastlane 的 `match` 解決什麼問題？CI 怎麼認證、金鑰怎麼管理？

先說 fastlane：它是一套把建置、簽署、上傳這些步驟寫成腳本的自動化工具，每個腳本叫一個 lane。

**`match` 解決的問題是：團隊裡每個人、還有 CI（continuous integration，持續整合，也就是自動建置測試的伺服器），都要用同一組憑證與 provisioning profile。** 沒有它的時候，常見的情況是：「在我的機器上可以簽署，CI 上失敗」，或是每個人都各自建立一張新的 Distribution 憑證，把 Apple 帳號的憑證數量用光。

`match` 的做法是：把憑證和 profile 加密後存在一個私有的 git repo（或雲端儲存），所有人和 CI 都從這裡下載同一份。

- 種類有 `development`、`adhoc`、`appstore`，分別對應不同用途。
- **CI 上要加 `readonly: true`**：只下載、不建立新的憑證。否則 CI 拿不到憑證時，可能偷偷建立一張新的，影響到其他人。
- 新增測試裝置後，要重新產生 profile，才會包含新裝置。
- `match nuke` 會撤銷（廢除）憑證，非常危險，不要隨便用。
- 憑證和 profile 都有到期日，要設定提醒，過期的那天 CI 會突然全部失敗。

**CI 的認證怎麼做？**

- **iOS：App Store Connect API key。** 在 App Store Connect 建立一組 API 金鑰（`.p8` 檔案，認證時用 JWT，JSON Web Token，一種簽過名的短效憑證），fastlane 用它上傳 TestFlight、查詢 build 編號。好處是不需要 Apple ID 的帳號密碼，也不會卡在雙重驗證。
- **Android：Play Console 的服務帳號。** 在 Google Cloud 建立服務帳號，下載 JSON 金鑰檔，授權給 Play Console，fastlane 用它上傳 AAB。

```ruby
# iOS：用 App Store Connect API key 認證，不需要 Apple ID 密碼
lane :beta do
  api_key = app_store_connect_api_key(
    key_id: ENV["ASC_KEY_ID"],
    issuer_id: ENV["ASC_ISSUER_ID"],
    key_content: ENV["ASC_KEY_CONTENT"],   # .p8 檔案的內容，放在 CI 的 secret
    is_key_content_base64: true
  )
  match(type: "appstore", readonly: true, api_key: api_key)   # 只下載共用的憑證與 profile
  build_app(scheme: "App")
  upload_to_testflight(api_key: api_key, skip_waiting_for_build_processing: true)
end
```

**簽署金鑰的密碼管理。** Android 不要把 keystore 的密碼寫進 `build.gradle.kts` 再提交進 repo。本機用 `keystore.properties`（要加進 `.gitignore`），CI 用環境變數：

```kotlin
import java.util.Properties

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")   // 這個檔案不進 repo
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            // CI 讀環境變數，本機讀 keystore.properties
            storeFile = file(System.getenv("UPLOAD_KEYSTORE_PATH") ?: keystoreProps.getProperty("storeFile"))
            storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
            keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
            keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")
        }
    }
}
```

> [!TIP]
> 面試時可以這樣說：「團隊的 iOS 簽署我會用 fastlane match，讓大家和 CI 共用同一份憑證，CI 加 readonly 避免誤建新憑證，認證用 App Store Connect API key，不用 Apple ID。Android 的 keystore 密碼不進 repo，放 CI 的 secret，並定期輪換。」

### 3. 測試版怎麼發給測試人員？TestFlight 和 Google Play 的測試軌差在哪？

**兩個平台都有「內部」和「外部」兩層測試：內部給自己團隊，快速、不用審查；外部給更多人，iOS 的外部測試需要經過 Beta 審查。**

| | iOS（TestFlight） | Android（Google Play） |
|---|---|---|
| 內部測試 | 內部測試者是 App Store Connect 裡的使用者，最多 100 人，**不需要 Beta 審查**，build 處理完成後就能裝 | **內部測試軌**（internal testing），最多 100 位測試者，上傳後很快就能用 |
| 外部測試 | 外部測試者最多 10,000 人，每個版本的第一個 build 需要 **Beta App Review**（Apple 對測試版的審查）；build 在 90 天後過期 | 封閉測試（closed testing，指定名單）、公開測試（open testing，任何人都能加入） |
| 正式上線 | 提交 App Review，通過後發布 | 上傳到 production 軌 |

另外，不想經過商店的時候，可以用 Firebase App Distribution：把 build 直接寄給測試者，不用上架到 TestFlight 或 Play 測試軌，適合給公司內部、QA（quality assurance，品質驗證）快速驗證。

版本號也要注意：iOS 有「版本號」（給使用者看的，例如 3.20）和「build 號」，同一個版本內的 build 號必須遞增；Android 的 `versionCode` 必須遞增，每次上傳都要比上次大。

> [!TIP]
> 面試時可以這樣說：「內部測試 iOS 和 Android 都不用審查，我用來給團隊和 QA 快速驗證。外部測試 iOS 每個版本的第一個 build 需要 Beta App Review，Android 有封閉和公開測試軌。版本號要自動遞增，避免人為漏改造成上傳被拒。」

### 4. 分階段發布（phased release 與 staged rollout）怎麼運作？出問題能不能回滾？

**分階段發布是指新版不是一次給所有人，而是先給一小部分人，觀察沒問題再逐步擴大。但兩個平台都沒有辦法把已經更新的使用者降版，所以出問題只能「暫停」加「發修正版」。**

| | iOS：Phased release | Android：Staged rollout |
|---|---|---|
| 怎麼放量 | 固定 7 天：第 1 天 1%，之後依序 2%、5%、10%、20%、50%，第 7 天 100% | 自己指定百分比，可以隨時提高 |
| 對誰有效 | 只針對**開啟自動更新**的使用者；手動到 App Store 更新、或新安裝的人，隨時都拿得到最新版 | 對所有符合條件的使用者，依你設定的比例，由 Google Play 決定哪些人先收到更新 |
| 暫停 | 可以暫停，最多暫停 30 天 | 可以暫停（halt）、再繼續 |
| 回滾 | **不能**。已更新的使用者無法降版；只能暫停，並提交修正版（可以申請加急審查） | **不能**。暫停只是阻止更多人更新；已更新的人不會降版，要發一個 `versionCode` 更高的修正版 |
| 觀察依據 | App Store Connect 的 crash 資料、自己接的 Crashlytics | Android vitals（Google Play 提供的穩定性指標，例如 crash 與 ANR，即 Application Not Responding，App 太久沒有回應）、Crashlytics |

**實際出事時的處置（以 iOS phased release 進行到第 3 天、約 5% 使用者為例）：** 下單失敗率明顯上升。可行的組合是：

1. 暫停 phased release，阻止更多人更新。
2. 伺服器端先止血（調整相容性、限流、修改回應），因為舊版和新版 App 會同時存在，伺服器要能同時支援。
3. 用遠端 feature flag（功能開關，由伺服器控制功能的開啟與關閉）把有問題的功能關掉。
4. 準備修正版並申請加急審查。

**強制更新機制。** 因為不能降版，也為了讓舊版不再使用有漏洞的功能，要先準備強制更新：iOS 要自己做（遠端設定「最低支援版本」，低於這個版本就導向 App Store）；Android 可以用 Play 的 In-App Updates API（有 flexible，使用者可以繼續用；immediate，必須更新才能用），或自己用同樣的方式判斷最低版本。憑證釘選的輪替（網路層單元）、重大安全修正，都需要這個機制。

> [!TIP]
> 面試時可以這樣說：「我會用分階段發布，從 1 到 5% 開始，看 crash-free、ANR，還有下單和入金成功率。兩個平台都不能降版，所以出問題我會先暫停放量，伺服器端止血，用 feature flag 關掉問題功能，同時準備修正版，iOS 申請加急審查。」

### 5. 加密貨幣交易所 App 在 App Store 和 Google Play 的審查有什麼特別的要求？

**交易所 App 屬於高度管制的金融服務，所以審查比一般 App 嚴格：要由有執照的法人提交，只能在有執照的國家或地區上架，審查時要附上證明文件和可用的測試帳號。**

**Apple（App Review Guidelines）：**

- **3.1.5(iii) Exchanges：** 交易所類的 App 只能在**具有相應執照與許可的國家或地區**提供交易所功能。（錢包類的 App 在 3.1.5(i) 另有規定。）
- **5.1.1(ix)：** 銀行、金融服務等高度管制領域的 App，必須由**提供服務的法人**提交，不能用個人開發者帳號。也就是說必須由持牌的公司提交。
- 實務上要把上架地區限定在有執照的地方，例如日本。在日本，交易所必須是向財務局登錄的暗号資産交換業者，審查備註要附上執照證明、可以登入的測試帳號（最好是已通過 eKYC，也就是線上身分驗證的帳號）和功能說明。
- 條號與措辭以 Apple 當前的 Guidelines 為準，面試時不用背死，講得出重點即可。

**其他常見要求：**

- App 內要提供刪除帳號的功能。
- 隱私資料宣告（App Privacy，標示 App 蒐集哪些資料）。
- 加密出口合規：App 使用 HTTPS 以外的加密時需要說明，可在 Info.plist 設定 `ITSAppUsesNonExemptEncryption` 回答。
- **Google Play：** 有金融服務政策，以及 Play Console 裡的 Financial features（金融功能）宣告，需依地區提供資格證明；細節以 Play Console 最新的規定為準。
- 審查期間要保持測試帳號可用，不能被風控或實名驗證擋住。功能開關可以用遠端設定，但不能把審查者看不到的功能偷偷藏起來，等審查通過後再打開，這違反規範。

> [!TIP]
> 面試時可以這樣說：「Apple 的 3.1.5(iii) 要求交易所功能只在有執照的地區提供，5.1.1(ix) 要求受高度管制的金融 App 由法人提交。所以提交時要由持牌主體，限定上架日本，審查備註附上執照證明和能登入的測試帳號，另外還要有帳號刪除和隱私宣告。」

### 6. 上線後 crash 報告看不懂，要怎麼處理？（dSYM 與 mapping）

**原因通常是沒有把「對照檔」上傳到 crash 回報服務（例如 Firebase Crashlytics）。** 對照檔是 build 時產生的，用來把看不懂的位址或混淆後的名稱，還原成原本的函式名稱與行號：

| | iOS | Android |
|---|---|---|
| 對照檔 | dSYM（debug symbol 檔案） | R8 產生的 `mapping.txt` |
| 沒有它會怎樣 | crash 的堆疊（stack trace）只有一串十六進位位址，看不出是哪個函式 | 堆疊裡是 `a.b.c` 這種被混淆過的名稱 |
| 怎麼上傳 | Crashlytics 的 `upload-symbols`（建置腳本，或 fastlane 的 `upload_symbols_to_crashlytics`）。Xcode 14 起已沒有 bitcode，dSYM 在本機 Archive 時就會產生，不需要再從 App Store 下載 | Crashlytics Gradle plugin 會自動上傳；Play Console 也可以上傳 mapping，讓 Play 上的 crash 與 ANR 報告能還原名稱 |

R8 是 Android 的程式碼縮減與混淆工具（取代 ProGuard），release build 開啟後會刪掉沒用到的程式碼、把名稱改短。它也有風險：靠反射或 DI（dependency injection，依賴注入）框架產生的類別可能被誤刪或誤改，要用 keep rules（告訴 R8 哪些類別不能改名或刪除的規則）保護，並且一定要在 release build 完整測試。

**預防的方法是把上傳放進發布流程**，每次發布自動上傳，並保存每個版本的對照檔，不要靠人手動。

> [!TIP]
> 面試時可以這樣說：「crash 堆疊看不懂，通常是沒有上傳 dSYM 或 mapping。我會把上傳放進 CI 的發布流程，每個版本都保留對照檔。R8 開啟之後，我會在 release build 完整跑一次測試，確認 keep rules 沒有漏。」

### 7. 一個完整的發布流程是什麼樣子？

下面是一個兩個平台都適用的流程，按順序走：

1. 建立 release 分支或 tag，遞增版本號與 build 號。
2. CI 建置並簽署（fastlane），上傳 TestFlight／內部測試軌，同時上傳 dSYM／mapping。
3. QA（quality assurance，品質驗證）與自動化測試通過。
4. 送審（iOS）／上傳到 production 軌並設定 staged rollout（Android）。
5. 以 1 到 5% 開始放量，觀察 crash-free users（沒有遇到 crash 的使用者比例）、ANR、核心流程（下單、入金）的成功率。
6. 指標正常就逐步放量到 100%；異常就暫停並準備修正版。
7. 發布後保留對照檔與 release notes，關閉對應的工作單。

用 fastlane 把 iOS 與 Android 的上傳寫成 lane：

```ruby
lane :beta do   # iOS：憑證 → 建置 → 上傳 TestFlight → 上傳 dSYM
  match(type: "appstore", readonly: is_ci)
  increment_build_number(build_number: latest_testflight_build_number + 1)
  build_app(scheme: "App")
  upload_to_testflight(skip_waiting_for_build_processing: true)
  upload_symbols_to_crashlytics
end

lane :internal do   # Android：建置 AAB → 上傳到內部測試軌
  gradle(task: "bundle", build_type: "Release")
  upload_to_play_store(track: "internal", json_key: ENV["PLAY_JSON_KEY_PATH"])
end

lane :go_production do   # Android：把內部測試軌的版本升到 production，先放 5%
  upload_to_play_store(track: "internal", track_promote_to: "production", rollout: "0.05")
end
```

> [!TIP]
> 面試時可以這樣說：「我把發布流程放進 fastlane 和 CI：遞增版本、建置簽署、上傳測試軌和對照檔、QA、送審、從 1 到 5% 開始漸進放量。每一步都有明確的觀察指標和暫停條件，這樣出問題時能快速止血。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Platform07Release**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Platform07Release**。Android 的演練 4 不用開 App，直接在電腦上跑單元測試。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Platform/Platform07-Release/`，解答在 `labs/ios/Solution/Platform/Platform07-Release/`；Android 你寫在 `labs/android/practice/platform/platform07-release/`，解答在 `labs/android/solution/platform/platform07-release/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **只有 Release 看得到的演練**：iOS 的演練 4 在 Debug 建置看不出差別，要把建置組態改成 Release（Xcode 的 Product > Scheme > Edit Scheme > Run > Build Configuration 選 Release）再執行；Android 的演練 4 會在測試裡真的呼叫 R8。

簽署、fastlane、TestFlight 與測試軌、商店審查都是帳號、憑證和商店後台的設定，沒有辦法在專案裡跑出差別，所以這個單元的演練挑的是「發布流程中，程式碼寫錯就會出事」的部分：版本號、強制更新、放量，以及只有 Release 才出現的問題。

### 演練 1：發 3.10.0 時，商店說版本號比以前的小（第 3 題）

**檔案**：`L01_VersionNumber.swift`（iOS）、`L01_VersionNumber.kt`（Android）

**情境**：CI 腳本要自動從版本名稱算出 build 號（iOS 的 build 號、Android 的 `versionCode`），免得人工忘記遞增。商店規定：新上傳的 build 號必須比以前上傳過的都大。演練依序發布 7 個版本：3.2.9、3.2.10、3.9.0、3.9.10、3.10.0、3.20.0、3.21.0，看有幾個版本被商店拒絕。

**怎麼寫**

1. **Bug 版**：把版本名稱的 `.` 拿掉，直接當成整數（3.2.10 變成 3210）。
2. **修正版**：用 `.` 切成三段整數（每段必須小於 1000），回傳 主版號 × 1,000,000 ＋ 次版號 × 1,000 ＋ 修訂號。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 3.9.0 變成 390、3.10.0 變成 3100、3.20.0 變成 3200、3.21.0 變成 3210（和 3.2.10 撞號），「共 7 個版本，被商店拒絕 4 個」 |
| 修正版 | build 號依序是 3002009、3002010、3009000、3009010、3010000、3020000、3021000，「被商店拒絕 0 個」 |

**為什麼**：版本名稱的每一段是獨立的數字，不是數字的各個位數。去掉點再當整數，3.9.0 的 390 就比 3.2.10 的 3210 小，3.21.0 還會和 3.2.10 撞號。每一段固定留 3 位數的位置，才能保證版本越新、build 號越大。Android 的 `versionCode` 上限是 2,100,000,000，所以主版號最多到 2099。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform07-Release/L01_VersionNumber.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform07-Release/L01_VersionNumber.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform07-release/L01_VersionNumber.kt#bug
```

```lab-solution
android/solution/platform/platform07-release/L01_VersionNumber.kt#fixed
```

### 演練 2：3.20 版的使用者被強制更新，3.9 版卻沒有（第 4 題）

**檔案**：`L02_ForceUpdateCompare.swift`（iOS）、`L02_ForceUpdateCompare.kt`（Android）

**情境**：兩個平台都不能降版，出事只能靠強制更新：伺服器設定「最低支援版本」，App 啟動時比較自己的版本，低於最低版本就導向商店。演練用 5 個情況測試 `isUpdateRequired(current, minimum)`，每個情況都有「應該得到的結果」。

**怎麼寫**

1. **Bug 版**：版本是字串，直接用 `current < minimum` 比較。
2. **修正版**：用 `.` 切成整數，從主版號開始一段一段比數字大小；不一樣長的版本（`3.10` 和 `3.10.0`）缺的段落當成 0；`current` 比 `minimum` 小才強制更新，完全一樣不用更新。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「共 5 個情況，判斷錯誤 3 個」：3.9.0 對最低 3.10.0 沒被擋；3.20.1 對最低 3.9.0、3.10 對最低 3.10.0 被誤擋 |
| 修正版 | 「共 5 個情況，判斷錯誤 0 個」 |

**為什麼**：字串是一個字元一個字元比的。「3.9.0」和「3.10.0」比到第三個字元，`'9'` 大於 `'1'`，3.9.0 就被當成比 3.10.0 新，該被強制更新的人沒被擋住；「3.20.1」和「3.9.0」比，`'2'` 小於 `'9'`，最新版的使用者反而被要求更新。強制更新是出事時唯一的止血手段（第 4 題），這段比較邏輯錯了，就等於沒有這個機制。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform07-Release/L02_ForceUpdateCompare.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform07-Release/L02_ForceUpdateCompare.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform07-release/L02_ForceUpdateCompare.kt#bug
```

```lab-solution
android/solution/platform/platform07-release/L02_ForceUpdateCompare.kt#fixed
```

### 演練 3：同一個使用者，一下看得到新功能、一下又看不到（第 4 題）

**檔案**：`L03_RolloutBucket.swift`（iOS）、`L03_RolloutBucket.kt`（Android）

**情境**：新功能先對 20% 的使用者開放（遠端 feature flag），出問題能立刻關掉。演練有 1000 位使用者，每位使用者在這一天內打開 App 5 次，每次 App 都問「我在名單裡嗎？」。放量比例從 10% 調到 50% 時，原本看得到的人不應該消失。

**怎麼寫**

1. **Bug 版**：每次詢問都擲一次骰子：`0..<100` 的亂數小於放量比例就開。
2. **修正版**：用使用者 ID 算出固定的桶號（0 到 99）：把 `"rollout:" + userId` 的每個 UTF-8 位元組做 FNV-1a 雜湊（一種簡單、結果固定的雜湊演算法，這裡用 32 位元版本），除以 100 取餘數；桶號小於放量比例就開。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「答案不一致的有」約 660 位（解答實測 iOS 662 位）；10% 時看得到約 90 到 100 位，調到 50% 後反而看不到的約 40 到 45 位 |
| 修正版 | 「答案不一致的有 0 位」；10% 時看得到 100 位，調到 50% 後反而看不到的有 0 位（iOS、Android 算出來的桶號一樣） |

**為什麼**：放量要「同一位使用者的答案固定」，而且比例調高時看得到的人只增不減，這樣出問題才能分得清是新功能的問題，還是使用者時有時無造成的。做法是把使用者 ID 雜湊成固定的桶號。雜湊演算法要固定，iOS、Android、伺服器才會對同一位使用者得到同一個答案；Swift 的 `hashValue` 每次啟動 App 的種子都不一樣，不能拿來分桶。兩個平台也都不能回滾（第 4 題），所以用遠端開關關掉問題功能，是放量期間最快的止血方式。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform07-Release/L03_RolloutBucket.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform07-Release/L03_RolloutBucket.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform07-release/L03_RolloutBucket.kt#bug
```

```lab-solution
android/solution/platform/platform07-release/L03_RolloutBucket.kt#fixed
```

### 演練 4：只有 Release 版才會出的問題（iOS 第 7 題、Android 第 6 題）

這題兩個平台做的是不同的事，但共通點是「Debug 看不出來，Release 才出事」，所以發布前一定要用 Release 設定完整跑一次。

**檔案**：`L04_DebugSwitch.swift`（iOS）、`L04_R8KeepRules.kt`（Android，測試在 `labs/android/practice/platform/test/Platform07ReleaseTest.kt`，已經寫好，不用改）

**iOS 情境**：測試人員為了用 Charles 看封包，工程師加了一個除錯開關：除錯選單把 `UserDefaults` 的開關打開，App 就不檢查伺服器的憑證。出貨前沒有人記得拿掉。演練把開關打開，再讓攻擊者的假憑證（pin 對不上）來連線。

**Android 情境**：App 用反射把伺服器回傳的 JSON 對應到 Kotlin 物件，做法跟 Gson 的反射模式一樣：用 `getDeclaredConstructor()` 建立物件，欄位名稱等於 JSON 的 key 就把值寫進去。Debug 版沒開 R8（Android 的程式碼縮減與混淆工具），一切正常；Release 版開了 R8，沒有任何程式碼「直接」呼叫那個建構子、讀寫那些欄位，R8 判斷它們沒用到：把建構子刪掉，類別和欄位改成 `a`、`b`。測試會真的呼叫 R8，用你寫的規則處理三個類別（入口、反射對應、DTO，也就是專門裝伺服器資料的物件），再載入處理後的結果執行。

**怎麼寫**

1. **Bug 版**：iOS 的 `skipPinningEnabled()` 直接回傳 `UserDefaults.standard.bool(forKey:)`；Android 的 `L04Bug.rules`（`proguard-rules.pro` 的內容）只寫入口類別的規則：`-keep class …L04Entry { public *; }`。
2. **修正版**：iOS 用 `#if DEBUG … #else return false #endif` 包起來，Debug 建置才有這個開關；Android 的 `L04Fixed.rules` 在入口類別的規則之外，再加 `-keepclassmembers class …L04OrderDto { <init>(); <fields>; }`，保留 DTO 的建構子與欄位。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：Debug、Release 都是「除錯開關已打開，攻擊者的假憑證（pin 對不上）：放行」。Android：R8 處理後執行時丟出 `NoSuchMethodException`（訊息像 `a.a.<init>()`：類別被改名、建構子被刪） |
| 修正版 | iOS：Debug 建置放行（除錯用得到），Release 建置「擋下」。Android：R8 處理後執行得到「symbol=BTC/JPY, amount=0.5」 |

**為什麼**：iOS 的 `#if DEBUG` 是編譯期的條件，Release 建置時，編譯器根本不會編進那段程式，開關不存在；只靠執行期的判斷，上架的 App 一樣有這個後門。Android 的 R8 會把「看起來沒人用」的程式刪掉、把名稱改短；靠反射、序列化函式庫或 DI 框架使用的類別，R8 看不出來，要用 keep rules 保護。只保留建構子、沒保留欄位時更隱晦：不會當掉，但欄位名稱被改掉，每個欄位都是空的（演練實測得到「symbol=, amount=」）。這類問題 Debug 完全重現不了，要用 Release 設定完整測試。R8 混淆後的對照檔 `mapping.txt` 也要和每個版本一起保存，crash 堆疊才看得懂（見第 6 題）。

**iOS 解答**

```lab-solution
ios/Solution/Platform/Platform07-Release/L04_DebugSwitch.swift#bug
```

```lab-solution
ios/Solution/Platform/Platform07-Release/L04_DebugSwitch.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/platform/platform07-release/L04_R8KeepRules.kt#bug
```

```lab-solution
android/solution/platform/platform07-release/L04_R8KeepRules.kt#fixed
```

第 1、2 題（簽署、fastlane 的 `match`）是憑證與 CI 的設定，第 3 題（TestFlight 與測試軌的差別）、第 5 題（審查要求）、第 7 題（完整的發布流程）是流程題，沒有可以在專案裡執行的 bug，沒有演練；第 4 題的分階段發布本身（固定 7 天、不能回滾）是商店後台的功能，演練 2、3 練的是它周邊會寫錯的程式碼。第 6 題的 iOS 部分（dSYM）也是流程，可以用這些指令觀察：

- **iOS 的 dSYM**：`xcrun dwarfdump --uuid MyApp.app.dSYM` 印出 dSYM 的 UUID，要和 crash 報告裡那個版本執行檔的 UUID 一樣才能還原；`atos -arch arm64 -o MyApp.app.dSYM/Contents/Resources/DWARF/MyApp -l <載入位址> <crash 位址>` 可以手動把一個位址還原成函式名稱與行號。
- **Android 的 mapping**：真的專案在 release 的 `buildTypes` 設 `isMinifyEnabled = true` 之後，執行 `./gradlew :app:assembleRelease`，混淆對照檔會產生在 `app/build/outputs/mapping/release/mapping.txt`（演練用的 App 沒有開啟 R8，所以演練 4 在測試裡直接呼叫 R8）；有 crash 堆疊檔時，可以用 Android SDK 的 `retrace`（`cmdline-tools/latest/bin/retrace mapping.txt stacktrace.txt`）還原。

## 常見陷阱

- **簽署金鑰庫的密碼或憑證私鑰進了 repo。** 一旦外洩就必須換金鑰。密碼要放 CI 的 secret 或不進版控的檔案，並定期輪換。
- **以為只要保管好上傳金鑰就好，不知道 Play App Signing 的簽署金鑰在 Google 手上。** 結果 `assetlinks.json` 填了上傳金鑰的指紋，App Links 驗證失敗。
- **CI 不加 `readonly`。** `match` 可能在拿不到憑證時偷偷建立新的，造成憑證數量用完或影響其他人。
- **忘了上傳 dSYM 或 mapping。** 線上 crash 的堆疊只有位址或混淆名稱，追不到問題。
- **以為分階段發布能回滾。** 兩個平台都不能把已更新的使用者降版，只能暫停加發修正版。
- **沒考慮分階段發布期間，新舊版 App 同時存在。** 伺服器的 API 要同時相容新舊版，否則一部分使用者會壞掉。
- **審查用的測試帳號被風控或實名驗證擋住。** 審查員登不進去，App 就會被拒。要準備一個能順利登入、並完成必要驗證的測試帳號。
- **R8 開啟後沒有在 release build 完整測試。** 反射和 DI 相關的 bug 往往只在 release 才出現。

## 面試官想聽到的

1. 能完整說出簽署、測試軌、分階段發布、對照檔上傳的流程，以及兩個平台的差異。
2. 知道不能回滾的限制，並且以監控指標（crash-free、ANR、下單成功率）決定放量或暫停。
3. 知道 Apple 對交易所的要求：持牌主體提交、限定地區，並準備好審查資料。
4. 懂 fastlane 與 CI 的實務：match 與 `readonly`、App Store Connect API key、密碼放 secret。
5. 對強制更新機制與新舊版相容有準備。

> [!FIELD]
> 在一個社交 App 裡，我用 Fastlane 搭配 Codemagic、CircleCI，建立雙平台的自動建置與發布流程：每個 PR（pull request，程式碼合併請求）跑測試，staging 與 production 由 API 觸發建置、上傳 TestFlight 並把 dSYM 上傳到 Crashlytics；Fastlane 也負責版本準備、產生 release note 和關閉 Jira 工作單。那個 App 的 Android 曾遇到 R8 的 full mode 和 Hilt（Google 的依賴注入框架）產生的類別互相衝突，造成 release 版本才出現的執行期錯誤，處理方式是關閉 full mode 並補上 keep rules；也用 ABI（Application Binary Interface，這裡指 CPU 架構）篩選，只保留 `arm64-v8a` 與 `armeabi-v7a` 兩種縮小安裝檔。
> 在一個即時行情 App 裡，我建立了 Fastlane 自動發布流程：版號遞增、建置、簽署、上傳 TestFlight 與 Firebase App Distribution，隨版本附上 release notes，並發送 Slack 通知；iOS 用 App Store Connect API key 認證。
> 換成交易所 App，我會把簽署密碼放進 CI 的 secret 並定期輪換，並在分階段發布時把下單和入金成功率也納入暫停條件。

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 簽署 | 憑證加 provisioning profile，團隊用 fastlane match 共用 | Play App Signing：Google 保管簽署金鑰，我們持有上傳金鑰 |
| 上傳格式 | `.ipa` | AAB（自 2021 年 8 月起新 App 必須使用） |
| CI 認證 | App Store Connect API key | Play Console 服務帳號的 JSON 金鑰 |
| 內部測試 | TestFlight 內部（100 人，免 Beta 審查） | 內部測試軌（100 人） |
| 外部測試 | TestFlight 外部（10,000 人，首個 build 需 Beta 審查，90 天過期） | 封閉測試、公開測試 |
| 分階段發布 | phased release：固定 7 天，只針對自動更新，可暫停最多 30 天 | staged rollout：自訂百分比，可暫停、提高 |
| 回滾 | 不能，暫停加修正版（可加急審查） | 不能，暫停加更高的 `versionCode` |
| 強制更新 | 自己做（遠端設定最低版本） | Play In-App Updates API，或自己做 |
| crash 對照檔 | dSYM | R8 的 `mapping.txt` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 程式碼簽署 | code signing | コード署名 |
| 描述檔 | provisioning profile | プロビジョニングプロファイル |
| 分階段發布（iOS） | phased release | 段階的リリース |
| 分階段發布（Android） | staged rollout | 段階的ロールアウト |
| 持續整合／持續交付 | CI／CD（continuous integration／continuous delivery） | 継続的インテグレーション／継続的デリバリー |
| 功能開關 | feature flag | フィーチャーフラグ |
| 審查 | App Review | アプリ審査 |
