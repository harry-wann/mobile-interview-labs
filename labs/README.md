# MobileLabs：在 Xcode／Android Studio 實際跑的演練

複習站每個技術單元的「實際演練」都在這裡。iOS、Android 各一個專案、各一個 App；
程式碼分成 **Practice（練習，你寫的）** 和 **Solution（解答）** 兩套，資料夾結構一模一樣，可以左右對照。

```
labs/
├── ios/                                   Xcode 專案
│   ├── MobileLabs.xcodeproj             打開這個
│   ├── App/                               App 進入點（首頁列出所有區、單元、演練）
│   ├── LabKit/                            共用的畫面、log、自動檢查、小工具
│   ├── Practice/                          練習（Swift Package，每一區是一個 module）
│   │   ├── Swift/                           module：PracticeSwift
│   │   │   ├── Swift01-ValueSemantics/        單元
│   │   │   │   ├── Swift01ValueSemantics.swift  單元的演練清單
│   │   │   │   ├── L01_StructOrClass.swift      第 1 題的演練
│   │   │   │   └── …
│   │   │   └── Swift02-ARC/ …
│   │   ├── Platform/  Advanced/  Exchange/  Testing/  Design/  Coding/
│   └── Solution/                          解答（結構和 Practice 一樣）
└── android/                               Android Studio 專案
    ├── app/                               App 進入點
    ├── labkit/                            共用的畫面、log、自動檢查、小工具
    ├── practice/                          練習（每一區是一個 module：practice-kotlin、practice-platform…）
    │   ├── kotlin/
    │   │   ├── kotlin03-coroutines/
    │   │   │   ├── Kotlin03Coroutines.kt
    │   │   │   ├── L01_ScopeLeak.kt
    │   │   │   └── …
    │   │   └── section/KotlinSection.kt   這一區的單元清單
    │   └── platform/  advanced/  exchange/  testing/  design/  coding/
    └── solution/                          解答（結構和 practice 一樣）
```

單元的代號是「編號＋單元重點」，例如 `Swift02ARC`、`Kotlin03Coroutines`、`Exchange05OrderBook`；
雙平台的單元在 iOS、Android 用同一個代號。L01、L02… 對應複習站那個單元的第 1、2… 題。

## 每個演練怎麼練

每個演練檔案都有兩個要寫的版本：

| 版本 | 是什麼 |
|---|---|
| **Bug 版** | 一般人很容易寫出來、而且真的會出錯的寫法：結果錯、當掉、記憶體洩漏、畫面卡頓、資料競爭… |
| **修正版** | 照複習站那一題建議的做法修好 |

Practice 的檔案裡，型別、函式、演練流程、log、自動檢查都寫好了，只有關鍵的幾行留空，用 `TODO ①`（Bug 版）、`TODO ②`（修正版）標出要寫什麼。

1. 打開 `Practice/…/L01_….swift`（Android 是 `practice/…/L01_….kt`），照 `TODO ①` 寫 Bug 版。
2. 執行 App，進入那個演練，選「**練習**」「**Bug 版**」按執行。出現 **✓ 符合預期**，代表你寫出來的 bug 真的重現了；出現 ✗ 會告訴你還差什麼。
3. 照 `TODO ②` 寫修正版，選「**修正版**」執行，直到出現 ✓。
4. 打開 `Solution/` 同名的檔案對照，或在 App 切到「**解答**」直接跑解答。複習站的「實際演練」也有摺疊的解答程式碼。

Bug 版寫對了會當掉的演練，畫面上會先提醒。從 Xcode／Android Studio 執行，才看得到當掉的位置（Xcode 會停在出錯的那一行；Android 看 Logcat）。

## iOS

需要 Xcode 26 以上。打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器，按 ⌘R。要在自己的 iPhone 上跑：在 MobileLabs target 的 Signing & Capabilities 選你的 Team。

- 新增演練檔案：直接放進 `Practice/`、`Solution/` 的單元資料夾，Xcode 會自動看到（Swift Package 的資料夾）。
- 改了 `project.yml`（例如加 Info.plist 的權限說明）才需要執行 `./generate.sh` 重新產生專案（需要 `brew install xcodegen`）。

不開 Xcode 也可以檢查：

```bash
cd labs/ios
./check.sh practice Swift01ValueSemantics          # 你寫的練習：每個演練目前 ✓ 還是 ✗
./check.sh selftest Swift01ValueSemantics          # 解答：確認每個 Bug 版都會重現問題、修正版都正確
./check.sh run Swift01ValueSemantics L05 practice bug   # 只跑一個版本（確認 Bug 版真的會當掉）
```

`check.sh` 預設用名為 `MobileLabs-Main` 的模擬器（沒有的話先建立：`xcrun simctl create MobileLabs-Main com.apple.CoreSimulator.SimDeviceType.iPhone-17`）。只開這一台就好：同時有好幾個檢查要用時會自動排隊。其他選項：
- `./check.sh selftest`（不加代號）一次跑全部單元。
- `LABS_CONFIG=Release ./check.sh selftest <代號>`：用 Release 建置跑（量時間的演練，Debug 與 Release 差很多時用）。
- `LABS_SHOT=<png 路徑> ./check.sh open <代號> L0x solution`：打開某個演練並截圖。

## Android

用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機，按 ▶。

不開 Android Studio 也可以檢查不需要畫面的演練：

```bash
cd labs/android
./gradlew :practice-kotlin:testDebugUnitTest      # 你寫的練習（結果在 practice/kotlin/build/test-results/）
./gradlew :solution-kotlin:testDebugUnitTest      # 解答
grep -ho "\[SELFTEST\][^<]*" practice/kotlin/build/test-results/testDebugUnitTest/*.xml   # 看每個演練的 ✓／✗
```
區名換成 `platform`、`advanced`、`exchange`、`testing`、`design`、`coding` 就是其他區。有些演練要真的 HTTP、R8 或 `runTest` 虛擬時間，只能用這個方式跑（App 裡顯示「在電腦上跑單元測試」）；有些要手機才行（`needsDevice`），電腦上會顯示 SKIP。

模擬器只開一台就好。直接用指令開 App 的某個演練：`adb shell am start -n tw.harry.labs/.MainActivity --es open Kotlin03Coroutines/L05 --es source practice`。

## 自己新增演練

1. 在 `Solution/` 和 `Practice/` 的單元資料夾各放一個 `L0x_….swift`／`.kt`（複製現有的改），裡面的 `L0xBug`、`L0xFixed` 改成新的編號。
2. 在單元的清單檔（`Swift01ValueSemantics.swift`、`Kotlin03Coroutines.kt`）的 `labs` 加上 `l0x`。

寫法規範（檔案結構、自動檢查怎麼寫）見 [AUTHORING.md](AUTHORING.md)。
