---
id: exchange-precision
title: 精度與金額：為什麼不能用 Double 算錢、價格和數量怎麼對齊、捨入方向
summary: 交易所 App 最常出事的三件事：用 Double 算錢、數量的捨入方向錯了、數字的顯示格式跟著手機語言亂跳。這個單元一個一個說明原因和正確做法，最後整理 iOS 和 Android 捨入規則的差異。
priority: P0
day: 3
tags: [decimal, bigdecimal, money, formatting, rounding]
---

## 重點講解

這個單元回答五個問題：

1. 為什麼金額不能用 `Double`？
2. 交易所對價格和數量有哪些規則？App 要怎麼對齊？
3. API 回來的數字要怎麼讀，才不會一開始就不準？
4. iOS 和 Android 的捨入規則有什麼差異？
5. 數字要怎麼顯示？

### 1. 為什麼金額不能用 `Double`？

**結論：`Double` 用二進位存小數，很多十進位的小數存不準，而且有效位數只有 15 到 17 位。金額一律用十進位型別：Swift 的 `Decimal`、Kotlin／Java 的 `BigDecimal`。**

```swift
print(0.1 + 0.2 == 0.3)   // false：Double 算出來是 0.30000000000000004

let a = Decimal(string: "0.1")!
let b = Decimal(string: "0.2")!
print(a + b == Decimal(string: "0.3")!)   // true：Decimal 用十進位存，0.1 就是真的 0.1
```

為什麼會這樣？電腦用二進位存數字。`0.1` 換成二進位是無限循環的小數（就像 1/3 在十進位是 0.333…），只能存一個近似值。單看一筆誤差很小，但加加減減之後，就會出現「餘額顯示 0.30000000000000004」或「明明夠錢卻被判斷不夠」的問題。

位數也是問題。加密貨幣的最小單位很小：1 BTC = 1 億 satoshi（小數第 8 位），1 ETH = 10 的 18 次方 wei（小數第 18 位）。`Double` 只有 15 到 17 位有效數字，金額一大，小數後面的位數就保不住了。

交易所 App 裡和錢有關的值（下單數量、手續費、可用餘額、損益）一律用十進位型別；或者讓伺服器回傳字串，App 只負責顯示。

> [!TIP]
> 面試時可以這樣說：「和錢有關的值，我一律用 Decimal 或 BigDecimal，API 的數字當字串讀進來，不經過 Double。顯示用的字串和計算用的數值分開。」

### 2. 交易所對價格和數量有哪些規則？App 要怎麼對齊？

**結論：交易所規定了價格和數量的「最小跳動單位」，以及單筆的最小下單量。App 要在送出前先對齊、先檢查，不然會被伺服器拒絕。數量一律往下對齊。**

| 名詞 | 意思 | App 要做的事 |
|---|---|---|
| tick size（日文：呼値の単位） | 價格最小的跳動單位。例如 tick size 是 5 円，價格只能是 1230、1235，不能是 1234 | 限價單（指値）的價格要對齊 |
| step size（數量的最小單位） | 數量最小的跳動單位。例如 0.0001 BTC，就不能下 0.00015 BTC | 數量一律**往下**對齊 |
| 最小下單量／最小下單金額 | 單筆的下限：數量不能太小，「數量 × 價格」也不能太小 | 送出前檢查，顯示清楚的錯誤訊息 |

對齊的例子（都是往下）：

- 價格 `1234.567`，tick size `0.01` → `1234.56`。
- 價格 `1234`，tick size `5` → `1230`（5 的整數倍）。
- 數量 `0.123456789`，step size `0.0001` → `0.1234`。
- 數量 `0.00009`，step size `0.0001` → `0`，等於下不了單，要提示使用者。

**為什麼數量要往下？** 使用者按「全部賣出」時，如果數量往上進位，就會比他實際擁有的多一點點，伺服器會拒絕這筆訂單。往下對齊頂多少賣一點點零頭，不會失敗。

```swift
// 往下對齊到 step 的整數倍
func floorToStep(_ value: Decimal, step: Decimal) -> Decimal {
    var units = value / step                      // 有幾個 step
    var result = Decimal()
    NSDecimalRound(&result, &units, 0, .down)     // 去掉小數（正數時 .down 就是往下）
    return result * step
}
print(floorToStep(Decimal(string: "0.123456789")!, step: Decimal(string: "0.0001")!))   // 0.1234
```

交易對的這些規則（精度、最小下單量）通常由 API 的交易對設定提供，實際的欄位名稱以交易所的 API 文件為準。**App 端的計算只用來預估和提早擋掉錯誤，最終以伺服器回傳的結果為準。**

> [!TIP]
> 面試時可以這樣說：「價格對齊 tick size、數量對齊 step size，數量一律往下對齊，因為全部賣出時往上進位會超過持有數量而被拒絕。送出前檢查最小下單量，但最終還是以伺服器為準。」

### 3. API 回來的數字要怎麼讀，才不會一開始就不準？

**結論：把 JSON 裡的數字當成字串讀，再轉成 `Decimal`／`BigDecimal`。如果先變成 `Double` 再轉，誤差在轉換的那一刻就已經發生了。**

- **Swift**：用 `Decimal(string: "0.1")` 建立。不要寫 `let d: Decimal = 0.1`：這種寫法在某些 Foundation 版本會先經過 `Double`，結果不一定精準。統一用字串建立最保險。
- **Kotlin**：不要用 `BigDecimal(0.1)`。它會把 `0.1` 的二進位近似值完整保存下來，變成 `0.1000000000000000055511151231257827…`。要用 `BigDecimal("0.1")`；或 `BigDecimal.valueOf(0.1)`，它會先把 `Double` 轉成字串 `"0.1"` 再建立，結果是 `0.1`。

```kotlin
println(BigDecimal(0.1))           // 0.1000000000000000055511151231257827021181583404541015625
println(BigDecimal("0.1"))         // 0.1
println(BigDecimal.valueOf(0.1))   // 0.1
```

> [!TIP]
> 面試時可以這樣說：「API 的數值我當字串解析，Swift 用 Decimal(string:)、Kotlin 用 BigDecimal(String)。BigDecimal 的 Double 建構子會保留二進位誤差，是常見的坑。」

### 4. iOS 和 Android 的捨入規則有什麼差異？

**結論：同樣叫「down」，Swift 的 `.down` 是往負無限大取（floor），Kotlin 的 `RoundingMode.DOWN` 是往 0 的方向截斷。正數時結果一樣，負數時差一個單位。損益這種可能是負數的值要特別小心。**

先分清楚幾種取整的方式（以取到整數為例）：

| 方式 | 意思 | `1.5` | `-1.5` | `2.5` |
|---|---|---|---|---|
| 往 0 截斷 | 直接丟掉小數 | 1 | −1 | 2 |
| 往負無限大（floor） | 取比它小的最大整數 | 1 | −2 | 2 |
| 往正無限大（ceil） | 取比它大的最小整數 | 2 | −1 | 3 |
| 四捨五入（0.5 遠離 0） | 一般的四捨五入 | 2 | −2 | 3 |
| 銀行家捨入（0.5 取偶數） | 剛好 0.5 時，捨入到偶數那邊，長期統計比較不會偏向一邊 | 2 | −2 | 2 |

兩個平台的對應：

| 需求 | Swift（`NSDecimalRound`） | Swift（`FormatStyle`，iOS 15 起） | Kotlin（`RoundingMode`） |
|---|---|---|---|
| 往 0 截斷 | 沒有直接對應，要依正負號選 `.down` 或 `.up` | `.towardZero` | `DOWN` |
| 往負無限大 | `.down` | `.down` | `FLOOR` |
| 往正無限大 | `.up` | `.up` | `CEILING` |
| 四捨五入 | `.plain` | `.toNearestOrAwayFromZero` | `HALF_UP` |
| 銀行家捨入 | `.bankers` | `.toNearestOrEven` | `HALF_EVEN` |

實測：`NSDecimalRound` 用 `.down` 處理 `-1.5`，結果是 `-2`；Kotlin 的 `RoundingMode.DOWN` 處理 `-1.5`，結果是 `-1`。如果兩個平台的工程師都「照字面」選了 down，負的損益就會差一個單位。

> [!WARNING]
> 防止雙平台不一致的方法：把捨入規則寫成同一份測試案例（輸入、規則、預期結果），iOS 和 Android 各跑一次，而且一定要包含負數和剛好 0.5 的案例。

> [!TIP]
> 面試時可以這樣說：「捨入規則是雙平台最容易對不齊的地方：Swift 的 .down 是 floor，Kotlin 的 DOWN 是往 0 截斷，負數會差一。我會把捨入規則寫成同一份測試案例，兩個平台各跑一次。」

### 5. 數字要怎麼顯示？

**結論：顯示用的格式集中管理；格式化工具要重用、不要邊用邊改；`BigDecimal` 轉字串用 `toPlainString()`。**

- **iOS 15 起可以用 `FormatStyle`**：`price.formatted(.number.precision(.fractionLength(2)).rounded(rule: .down))`。如果 App 的最低支援版本是 iOS 15，可以直接用。
- **`NumberFormatter` 建立很花時間**，要建一次、重複使用。但**共用的 formatter 不要在使用中途改設定**（例如每次呼叫都改 `maximumFractionDigits`）：多個執行緒同時使用時會互相干擾，顯示出錯的小數位數。不同精度就各建一個 formatter。
- **Android 的 `DecimalFormat` 不能被多個執行緒同時使用**，要每個執行緒各一個（`ThreadLocal`），或集中在同一個執行緒格式化。
- **`BigDecimal.toString()` 遇到很小的數會用科學記號**：`0.00000001` 會印成 `1E-8`；`stripTrailingZeros()` 之後的 `100` 也會變成 `1E+2`。**顯示一律用 `toPlainString()`**。
- **送給 API 的字串**要用固定格式（`en_US_POSIX`：小數點永遠是 `.`、不加千分位），不能跟著使用者手機的語言設定變。德文、法文用逗號當小數點，直接用使用者的語言格式送出，伺服器會讀錯。
- 日圓沒有小數；日文、中文介面可能要用「万」「億」這類大數單位（見在地化單元）。

> [!TIP]
> 面試時可以這樣說：「數字的顯示格式我會集中管理。`NumberFormatter` 建立很花時間，所以建一次重複使用，但使用中途不要改設定，不同精度就各建一個。`BigDecimal` 顯示一律用 `toPlainString()`，避免出現科學記號。送給 API 的字串用固定格式 `en_US_POSIX`，不能跟著使用者手機的語言變。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange02Precision**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange02Precision**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange02-Precision/`，解答在 `labs/ios/Solution/Exchange/Exchange02-Precision/`；Android 你寫在 `labs/android/practice/exchange/exchange02-precision/`，解答在 `labs/android/solution/exchange/exchange02-precision/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不會連到外部網路**：這個單元的演練都是數字的運算與格式，用程式裡寫死的字串當作「API 回傳的資料」，用一個函式當作「假伺服器」。金額一律用 `Decimal`（iOS）和 `BigDecimal`（Android）。

### 演練 1：明明剛好夠，卻顯示「餘額不足」（第 1 題）

**檔案**：`L01_DoubleBalance.swift`（iOS）、`L01_DoubleBalance.kt`（Android）

**情境**：使用者的資產有一部分掛在還沒成交的單上（凍結）。可用餘額 = 總額 − 凍結。API 回傳的總額與凍結是字串，三種情況：總額 1.0 凍結 0.9、總額 0.3 凍結 0.2、總額 0.7 凍結 0.6，可用餘額都是剛好 0.1。使用者按「全部賣出」，輸入框填入 0.1，App 判斷「輸入的數量不超過可用餘額」才能送出。

**怎麼寫**

1. **Bug 版**：iOS 用 `Double(total)!`、`Double(locked)!` 相減，再和 `Double(input)!` 比較；Android 用 `total.toDouble()`、`locked.toDouble()`。
2. **修正版**：iOS 改用 `Decimal(string:)`，Android 改用 `BigDecimal(String)`，相減和比較都用十進位型別。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 可用餘額顯示 0.09999999999999998，輸入的 0.1 反而比較大，「3 次「全部賣出」，被誤判餘額不足 3 次」 |
| 修正版 | 三次的可用餘額都是剛好 0.1，都可以送出，「被誤判餘額不足 0 次」 |

**為什麼**：`Double` 用二進位存小數，0.9 和 1.0 相減的結果是 0.09999999999999998，比使用者輸入的 0.1 小一點點，所以「全部賣出」被擋下。Decimal／BigDecimal 用十進位存，1.0 − 0.9 就是 0.1。Platform08 的演練 2 練過同一個原因造成的「價格對齊 tick size 出錯」，這題看的是餘額的加減。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L01_DoubleBalance.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L01_DoubleBalance.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L01_DoubleBalance.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L01_DoubleBalance.kt#fixed
```

### 演練 2：按「全部賣出」，數量被進位後被拒絕（第 2 題）

**檔案**：`L02_StepSize.swift`（iOS）、`L02_StepSize.kt`（Android）

**情境**：交易對的 step size（數量最小單位）和最小下單量都是 0.0001 BTC。使用者按「全部賣出」，App 要把持有量對齊 step size 再送出。兩種情況：持有 0.12349999 BTC、持有 0.00009 BTC。假伺服器的規則：數量超過持有量就拒絕（餘額不足）。

**怎麼寫**

1. **Bug 版**：`align` 把持有量除以 step size，**四捨五入**成整數個 step，再乘回去（iOS 是 `NSDecimalRound(…, 0, .plain)`，Android 是 `divide(step, 0, RoundingMode.HALF_UP)`）。
2. **修正版**：同樣的步驟，模式改成**往下取整**（iOS 的 `.down`，Android 的 `RoundingMode.DOWN`）。對齊後不到最小下單量，演練流程會在 App 端提示，不送出。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 0.12349999 變成 0.1235、0.00009 變成 0.0001，都比持有量多，兩筆都被伺服器拒絕：「送到伺服器 2 筆，被拒絕 2 筆」 |
| 修正版 | 0.12349999 變成 0.1234，伺服器受理；0.00009 變成 0，不到最小下單量，App 提示使用者：「送到伺服器 1 筆，被拒絕 0 筆，App 端提示最小下單量 1 次」 |

**為什麼**：數量四捨五入有一半的機會往上進位，結果超過使用者實際擁有的數量，被伺服器拒絕。往下對齊頂多少賣一點點零頭，不會失敗。如果往下對齊後變成 0（不到最小下單量），要在 App 提示使用者，不要送出一定會被拒絕的單。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L02_StepSize.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L02_StepSize.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L02_StepSize.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L02_StepSize.kt#fixed
```

### 演練 3：伺服器回的位數，讀進來少了幾位（第 3 題）

**檔案**：`L03_JsonNumber.swift`（iOS）、`L03_JsonNumber.kt`（Android）

**情境**：資產 API 回傳 JSON，`balance` 是一個有 18 位小數的數字（ETH 最小單位 wei 是小數第 18 位）：`{"symbol":"ethjpy","balance":1.234567890123456789}`。iOS 用 `JSONDecoder` 解析成 `Decodable` 的結構，Android 用 Gson（Google 的 JSON 函式庫）解析成資料類別。Android 要多一個套件：`practice/exchange` 與 `solution/exchange` 的 `build.gradle.kts` 已經加上 `com.google.code.gson:gson`。

**怎麼寫**

1. **Bug 版**：`Account` 的 `balance` 宣告成 `Double`。
2. **修正版**：`balance` 宣告成 `Decimal`（iOS）／`BigDecimal`（Android）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「App 讀到 balance = 1.2345678901234567」，「和伺服器的原文 1.234567890123456789 不一致，位數被吃掉了」 |
| 修正版 | 「App 讀到 balance = 1.234567890123456789」，「和伺服器的原文一致」 |

**為什麼**：`Double` 只有 15 到 17 位有效數字，超過的位數在 JSON 讀進來的那一刻就丟了，之後再轉成 `Decimal` 也救不回來。欄位直接宣告成十進位型別，解析器會把數字的文字直接轉成 `Decimal`／`BigDecimal`，不經過 `Double`。iOS 的解答實測在 iOS 26.3 模擬器上，`JSONDecoder` 能把 JSON 數字讀成精確的 `Decimal`；較舊的系統版本不一定相同，要支援舊版本時最保險的做法是讓伺服器把金額用字串傳，App 用 `Decimal(string:)` 建立。Android 內建的 `org.json` 解析數字時會先變成 `Double`，位數同樣會掉，所以演練用 Gson。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L03_JsonNumber.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L03_JsonNumber.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L03_JsonNumber.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L03_JsonNumber.kt#fixed
```

### 演練 4：同樣寫 down，負的損益差了 0.01（第 4 題）

**檔案**：`L04_DownMeansDifferent.swift`（iOS）、`L04_DownMeansDifferent.kt`（Android）

**情境**：損益要顯示到小數 2 位。伺服器有兩種規則：floor 是取比它小的最大值（負數往更負的方向，-1.239 變成 -1.24），truncate 是直接捨去小數（往 0 的方向，-1.239 變成 -1.23）。兩個平台共用同一份測試資料（規則、輸入、標準答案共 6 筆，iOS 與 Android 的文字一模一樣），而且一定有負數。

**怎麼寫**

1. **Bug 版**：不管規則是 floor 還是 truncate，都照字面選 down（iOS 的 `.down`，Android 的 `RoundingMode.DOWN`）。
2. **修正版**：iOS：floor 用 `.down`，truncate 遇到負數改用 `.up`（往 0 的方向是「往上」）；Android：truncate 用 `DOWN`，floor 用 `FLOOR`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「共 6 筆共用測試資料，和標準答案不一致 2 筆」。iOS：truncate 的 -1.239、-1.231 變成 -1.24（標準答案 -1.23）；Android：floor 的 -1.239、-1.231 變成 -1.23（標準答案 -1.24） |
| 修正版 | 「共 6 筆共用測試資料，和標準答案不一致 0 筆」 |

**為什麼**：同樣叫 down，Swift 的 `.down` 是往負無限大（floor），Kotlin 的 `RoundingMode.DOWN` 是往 0 截斷，正數時一樣，負數時差一個單位。兩個平台的工程師如果都「照字面」選 down，其中一個平台的負損益就會差 0.01。防止的方法是把捨入規則寫成同一份測試資料，兩個平台各跑一次，而且一定要包含負數。這份測試資料的做法和 Platform08 的演練 2 相同，那題看的是 `Double` 造成的差異，這題看的是兩個平台對 down 的定義不同。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L04_DownMeansDifferent.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L04_DownMeansDifferent.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L04_DownMeansDifferent.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L04_DownMeansDifferent.kt#fixed
```

### 演練 5：畫面上的手續費合計和帳單差了 2 円（第 5 題）

**檔案**：`L05_HalfEven.swift`（iOS）、`L05_HalfEven.kt`（Android）

**情境**：日圓沒有小數，手續費要四捨五入到整數。四筆成交，金額 12,500、37,500、62,500、87,500 円，手續費率 0.1%（演練假設），手續費剛好是 12.5、37.5、62.5、87.5 円。伺服器的帳單規則（演練假設）是四捨五入，剛好 0.5 進位：13、38、63、88，合計 202 円。App 用 `NumberFormatter`（iOS）／`DecimalFormat`（Android）把手續費顯示成整數。

**怎麼寫**

1. **Bug 版**：建立 formatter，小數位數設成 0，**不設** roundingMode。
2. **修正版**：明確設定 roundingMode：iOS 是 `.halfUp`，Android 是 `RoundingMode.HALF_UP`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 12.5 顯示 12、62.5 顯示 62（37.5、87.5 剛好一樣），「畫面手續費合計 200 円，伺服器帳單合計 202 円，差 2 円」 |
| 修正版 | 四筆都和帳單一樣：13、38、63、88，「差 0 円」 |

**為什麼**：兩個平台的 formatter 預設的捨入模式都是 half even（銀行家捨入）：剛好在一半時進位到偶數，長期統計比較不會偏向一邊，但和一般「四捨五入」的帳單對不起來。formatter 的捨入模式不要靠預設值，要明確指定，而且這種剛好 0.5 的案例要放進共用測試資料。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L05_HalfEven.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L05_HalfEven.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L05_HalfEven.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L05_HalfEven.kt#fixed
```

### 演練 6：德文手機下單，數量被伺服器拒絕（第 5 題）

**檔案**：`L06_LocaleDecimal.swift`（iOS）、`L06_LocaleDecimal.kt`（Android）

**情境**：使用者的手機語言是德文（de_DE），小數點是逗號、千分位是句點。App 把下單數量轉成字串送給 API。假伺服器只接受「數字加一個小數點」的格式（不能有千分位，小數點一定是 `.`）。三筆下單：數量 0.5、1234.5、0.00012345。演練把使用者的語言（de_DE）當參數傳進來，所以不管模擬器或電腦設定成什麼語言，結果都一樣。

**怎麼寫**

1. **Bug 版**：formatter 的 locale 用傳進來的使用者語言（iOS 的 `NumberFormatter.locale`，Android 的 `NumberFormat.getNumberInstance(userLocale)`）。
2. **修正版**：送給 API 的字串固定格式：iOS 的 locale 用 `en_US_POSIX` 並關掉 `usesGroupingSeparator`，Android 用 `Locale.ROOT` 並設 `isGroupingUsed = false`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 送出 "0,5"、"1.234,5"、"0,00012345"，三筆都被伺服器拒絕：「3 筆下單，被伺服器拒絕 3 筆」 |
| 修正版 | 送出 "0.5"、"1234.5"、"0.00012345"，「3 筆下單，被伺服器拒絕 0 筆」 |

**為什麼**：德文、法文等語言用逗號當小數點，formatter 預設跟著使用者的語言，格式化出來的字串伺服器讀不懂，下單就失敗，而且只有特定語言的使用者會遇到，很難在自己的手機上重現。送給 API、存檔、寫 log 的字串一律用固定格式；只有顯示給使用者看的字串才跟著手機語言（見在地化單元）。Android 的 `String.format("%.2f", x)` 不指定 `Locale` 時也會用手機的語言。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L06_LocaleDecimal.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L06_LocaleDecimal.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L06_LocaleDecimal.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L06_LocaleDecimal.kt#fixed
```

### 演練 7：1 satoshi 的價格顯示成科學記號（第 5 題）

**檔案**：`L07_ScientificNotation.swift`（iOS）、`L07_ScientificNotation.kt`（Android）

**情境**：行情畫面要顯示三個數字：0.00000001（1 satoshi，BTC 的最小單位）、0.00000015、100.00000000（尾端有多餘的 0）。畫面上要看到一般的小數，不能出現科學記號。

**怎麼寫**

1. **Bug 版**：iOS 把文字轉成 `Double`，再用字串插值轉成字串；Android 把 `BigDecimal` 先 `stripTrailingZeros()`，再呼叫 `toString()`。
2. **修正版**：iOS 保留 `Decimal`，用 `formatted(.number.precision(.fractionLength(0...8)))` 搭配 `grouping(.never)` 與 `en_US_POSIX` 指定格式；Android 改呼叫 `toPlainString()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | iOS：「0.00000001 → 畫面顯示 1e-08」「0.00000015 → 畫面顯示 1.5e-07」，科學記號 2 個。Android：1E-8、1.5E-7、1E+2，科學記號 3 個 |
| 修正版 | 三個數字都是一般的小數：0.00000001、0.00000015、100，科學記號 0 個 |

**為什麼**：`Double` 轉字串、`BigDecimal.toString()` 遇到很小的數字會改用科學記號，`stripTrailingZeros()` 之後的整數 100 在 Android 還會變成 `1E+2`。畫面和送給 K 線圖（WebView）的字串都不能出現科學記號。顯示一律用 Decimal 的格式化 API（iOS）或 `toPlainString()`（Android）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L07_ScientificNotation.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange02-Precision/L07_ScientificNotation.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange02-precision/L07_ScientificNotation.kt#bug
```

```lab-solution
android/solution/exchange/exchange02-precision/L07_ScientificNotation.kt#fixed
```

`BigDecimal` 的 `equals` 連小數位數一起比、除法沒有指定位數會丟例外，這兩個陷阱已經在 Kotlin 區練過（Kotlin01 的演練 6、Kotlin02 的演練 8），這個單元不重複。第 5 題的「共用的 formatter 不要在使用中途改設定」和「`DecimalFormat` 不能多個執行緒同時使用」是多執行緒的問題，每次執行的結果不固定，沒有穩定重現的演練；做法是每個精度各建一個 formatter、重複使用，Android 每個執行緒各一個。

## 常見陷阱

- **`BigDecimal` 用 `equals` 比較。** `BigDecimal("1.0").equals(BigDecimal("1.00"))` 是 `false`，因為 `equals` 連小數位數（scale）也一起比；放進 `Set` 或 `HashMap` 會被當成兩個不同的 key。比大小要用 `compareTo`，當 key 之前先 `stripTrailingZeros()`。
- **`BigDecimal` 除法沒有指定位數。** `BigDecimal.ONE.divide(BigDecimal(3))` 除不盡，會丟 `ArithmeticException`。除法一律指定小數位數和捨入方式。
- **數量用四捨五入。** 全部賣出時往上進位會超過持有數量，被伺服器拒絕。數量一律往下對齊。
- **把顯示的字串拿去計算。** 畫面上顯示的是截斷過的字串，下單時要用原本的 `Decimal`，不要把顯示字串再轉回數字。
- **送給 API 的數字跟著手機語言變格式。** 送出的字串一律用 `en_US_POSIX`。
- **把 `Double` 直接塞進 JavaScript 或 JSON 字串。** K 線用 WebView 畫圖時，`Double` 轉字串可能出現科學記號或精度問題。高精度的值要用字串傳。

## 面試官想聽到的

1. 和錢有關的值一律用十進位型別，API 的數字當字串讀，顯示和計算分開。
2. 熟悉 tick size、step size、最小下單量，知道數量為什麼要往下對齊。
3. 知道兩個平台捨入規則的差異，有雙平台一致的意識（同一份測試案例兩邊跑）。
4. 效能：formatter 要重用，也要考慮多執行緒；行情列表不要每一列都建立新的 formatter。
5. App 端只做預估，最終以伺服器為準；手續費和成交金額以成交回報為準。

> [!FIELD]
> 在一個加密貨幣的市場分析 App 裡，只顯示資料、不下單，數字的顯示邏輯雙平台用同一套演算法（大於 1 的值最多 2 位小數，很小的值顯示到 8 位小數，往下捨入）。
> 面試時可以這樣說：「我之前做的是分析 App，App 端沒有金額運算，所以數字主要是顯示。到交易所，下單和資產相關的值我會改用 Decimal／BigDecimal、字串解析，formatter 依精度各自建立並重複使用。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 十進位型別 | `Decimal`（struct，38 位有效數字） | `BigDecimal`（class，任意精度，不可變） |
| 從字串建立 | `Decimal(string: "0.1")` | `BigDecimal("0.1")` |
| 捨入 | `NSDecimalRound(&r, &v, scale, mode)`、`FormatStyle` 的 `.rounded(rule:)` | `setScale(n, RoundingMode.X)`、`divide(x, scale, mode)` |
| 相等比較 | `==` 只比數值 | `equals` 會連小數位數一起比，要用 `compareTo` |
| 顯示 | `FormatStyle`、`NumberFormatter`（重複使用，不要邊用邊改） | `NumberFormat`／`DecimalFormat`（不能多執行緒共用）、`toPlainString()` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 浮點數誤差 | floating-point error | 浮動小数点誤差 |
| 十進位小數型別 | decimal type | 十進数型 |
| 價格最小跳動單位 | tick size | 呼値の単位 |
| 數量最小單位 | step size／lot size | 数量の最小単位 |
| 最小下單量 | minimum order size | 最小注文数量 |
| 往下捨去／往上進位 | round down／round up | 切り捨て／切り上げ |
| 銀行家捨入 | banker's rounding | 偶数丸め（銀行丸め） |
| 小數位數 | scale | 小数桁数（スケール） |
