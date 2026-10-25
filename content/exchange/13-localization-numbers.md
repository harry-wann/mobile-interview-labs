---
id: exchange-localization-numbers
title: 在地化與數字顯示：四種語言、万／億等大數單位、日圓沒有小數、JST 時區、全形輸入
summary: 日本的加密資產交易所 App 常見日文、簡體中文、繁體中文、英文等多種語言。金融 App 在地化最容易出事的，是數字怎麼顯示、使用者輸入的數字怎麼解析，其次才是文字翻譯。這個單元說明語系標籤與降級、數字格式與預設捨入、万／億這類大數單位、日期與時區、複數與文字長度，以及日本使用者常用的全形輸入。
priority: P1
day: 4
tags: [localization, i18n, number-format, compact, cldr, rounding, date, calendar, plural, fullwidth]
---

## 重點講解

這個單元回答六個面試常見問題：

1. 四種語言（日文、簡體中文、繁體中文、英文）要怎麼標記與降級？
2. 數字怎麼顯示？千分位、日圓沒有小數、預設捨入有什麼陷阱？
3. 万、億這類大數單位怎麼顯示？
4. 日期與時區怎麼處理？JST 是什麼？
5. 文字的複數、長度與字形要注意什麼？（比較少考，被問到能答就好）
6. 日本使用者輸入全形數字時，要怎麼解析？

### 1. 四種語言要怎麼標記與降級？

**結論：簡體和繁體是「文字（script）」的差異，不是地區的差異，所以語系標籤用 `zh-Hans`（簡體）和 `zh-Hant`（繁體），不要寫死成 `zh-CN`、`zh-TW`。找不到字串時，依「完整標籤、基礎語言、預設語言、key 本身」的順序降級。App 內選的語言和系統的地區設定是兩件事，要統一成同一個 Locale。**

先講幾個名詞。語系標籤遵循 BCP 47（Best Current Practice 47，網際網路標準中規定語言標籤寫法的文件），結構是「語言－文字－地區」，例如 `zh-Hant-HK` 是「中文、繁體字、香港」。Locale 指一組在地化設定，包含語言、地區，以及數字、日期的格式習慣。CLDR 是 Common Locale Data Repository 的縮寫，一份由 Unicode 協會維護的各語言格式資料庫，iOS 和 Android 的格式化功能都用它。

日本的加密資產交易所 App 常見日文、簡體中文、繁體中文、英文等多種語言。為什麼不能寫死 `zh-CN` 和 `zh-TW`？因為香港、澳門的使用者看的是繁體，但用語和格式可能和台灣不同；也不能只提供繁中、請簡中使用者自己轉換，或用程式自動做簡繁轉換，因為用語差異（例如「軟體」和「软件」）無法自動轉，金融術語尤其不行。

降級鏈（找不到字串時的查找順序）：

```text
zh-Hant-HK（完整標籤）→ zh-Hant（基礎語言）→ 預設語言（例如 en）→ key 本身
```

**App 內語言和系統地區是兩件事：**

- iOS 的 `Locale.current` 取決於系統設定的「地區」，不是使用者在 App 內選的語言。如果 App 提供語言切換，格式化要用明確的 `Locale(identifier:)`，並讓整個 App 一致。
- iOS 13 起，系統設定可以單獨設定每個 App 的語言；Android 13 起也有 per-app language（`LocaleManager`、`locales_config.xml`）。是否另外提供 App 內的切換是產品決定，不要兩套並存卻互相矛盾。

> [!TIP]
> 面試時可以這樣說：「簡繁中文是文字的差異，我會用 zh-Hans 和 zh-Hant，不寫死 zh-CN、zh-TW。找不到字串時依完整標籤、基礎語言、預設語言、key 的順序降級。App 內選的語言和系統地區要統一用同一個 Locale，數字和日期格式才不會不一致。」

### 2. 數字怎麼顯示？千分位、日圓沒有小數、預設捨入有什麼陷阱？

**結論：顯示用的格式交給平台依 Locale 處理，但要知道幾個陷阱：日圓沒有小數；`NumberFormatter` 和 `DecimalFormat` 預設的捨入模式是 half-even（銀行家捨入），不是一般人以為的四捨五入；送給 API（Application Programming Interface，伺服器提供給 App 呼叫的介面）的字串絕對不能用顯示用的格式。**

| 項目 | 注意 |
|---|---|
| 分組與小數點 | 日文、中文、英文都是 `1,234,567.89`；德文是 `1.234.567,89`。**日文的千分位也是三位一組**，只有大數單位（万、億）是四位一組 |
| 日圓沒有小數 | 日圓（JPY）的最小單位就是 1 圓，沒有小數位數。本機實測 iOS 的 `NumberFormatter` 設定 `currencyCode = "JPY"`，`maximumFractionDigits` 是 0；Java 的 `Currency.getInstance("JPY").defaultFractionDigits` 也是 0（美元是 2）。1234567.8 圓顯示成 `¥1,234,568` |
| 貨幣符號 | 符號依 Locale 而不同：本機實測日圓在 `ja_JP` 顯示 `¥`，在 `zh_Hans_CN` 顯示 `JP¥`。加密資產用代號（BTC、ETH），不用符號 |
| 漲跌 | 漲跌幅用明確的 `+` 與 `-`，不要只靠顏色 |
| 顯示和 API | 送給 API 的字串一律用固定格式（`en_US_POSIX`，或 `Decimal.description`、`BigDecimal.toPlainString()`），不要用顯示用的 formatter |

formatter 的重用與多執行緒的問題，見「精度與金額」單元。

**預設捨入是 half-even**。half-even 的意思是：剛好在中間的數字（例如 2.5）捨入到最近的偶數，長期統計比較不會偏向一邊，但跟一般人期待的「四捨五入」不同。Java 的 `DecimalFormat`（Android 也一樣）預設就是 `HALF_EVEN`，iOS 的 `NumberFormatter` 也是。

```kotlin
import java.math.RoundingMode
import java.text.DecimalFormat

val half = listOf(0.5, 1.5, 2.5)
val df = DecimalFormat("0")                       // 取到整數
println(half.map { df.format(it) })               // 輸出：[0, 2, 2]（預設 half-even：2.5 變成偶數 2）
df.roundingMode = RoundingMode.HALF_UP            // 明確改成一般的四捨五入
println(half.map { df.format(it) })               // 輸出：[1, 2, 3]
```

顯示用的價格、漲跌幅，如果要和 Android 或網頁版一致，兩邊都要明確設定相同的捨入模式。實際下單的數量與價格，則依「精度與金額」單元的規則，用截斷。

> [!TIP]
> 面試時可以這樣說：「顯示格式我交給平台依 Locale 處理，但捨入模式我會明確設定，因為 NumberFormatter 和 DecimalFormat 預設是 half-even。日圓沒有小數。顯示字串和送給 API 的字串完全分開，API 用固定格式。」

### 3. 万、億這類大數單位怎麼顯示？

**結論：日文和中文是「四位一組」的大數單位（万是 10 的 4 次方，億是 10 的 8 次方），英文是「三位一組」（K、M、B、T），所以不能把 K、M、B 簡單換成万、億。平台提供的 compact formatter 只保留約兩位有效數字、而且依系統版本結果不同，需要明確規則時，要自己實作。**

| 語言 | 單位 |
|---|---|
| 日文（ja） | 万（10^4）、億（10^8）、兆（10^12） |
| 繁體中文（zh-Hant，台灣） | 萬、億、兆 |
| 簡體中文（zh-Hans） | 万、亿、万亿（10^12） |
| 英文（en） | K（10^3）、M（10^6）、B（10^9）、T（10^12） |

同一個數字 1,234,567,890,123（約 1.23 乘以 10 的 12 次方），日文和繁體中文是 `1.23兆`，簡體中文是 `1.23万亿`（簡體沒有把「兆」當作 10^12 的標準單位），英文是 `1.23T`（英文的 B 是 10 的 9 次方，不是 12 次方）。

**平台提供的 compact formatter**：iOS 15 起的 `.number.notation(.compactName)`，Android API 24 起的 `CompactDecimalFormat`，會依 CLDR 資料選擇單位。但預設保留的有效位數是固定的，約兩位。本機實測（macOS 的 Foundation，結果依系統與 CLDR 版本而異，Android 的版本不同，要另外實測）：

```swift
let value = 123_456_789
print(value.formatted(.number.notation(.compactName).locale(Locale(identifier: "ja_JP"))))   // 輸出：1.2億
print(value.formatted(.number.notation(.compactName).locale(Locale(identifier: "en_US"))))   // 輸出：123M
print(9_999.formatted(.number.notation(.compactName).locale(Locale(identifier: "ja_JP"))))   // 輸出：9999（日文、中文在 10,000 以下不縮寫，也沒有千分位）
print(9_999.formatted(.number.notation(.compactName).locale(Locale(identifier: "en_US"))))   // 輸出：10K
```

另一個要小心的地方：`zh_Hant_HK`（香港繁體）在同樣的測試裡得到的是英文式的單位 `123M`，和 `zh_Hant_TW` 的 `1.2億` 不同，所以不能假設所有繁體中文 locale 都用萬、億。

**compact 是有損的顯示**：`1.2億` 已經丟掉了後面的位數。它只適合市值、成交量這類輔助標籤，**不可以**用在下單數量、確認頁、損益明細，也不可以把顯示字串解析回數字再計算。

如果產品要的是「兩位小數」（`1.23億`），就不能直接用平台的 compact formatter，要自己實作一個可預測的版本。這個單元的程式題就是這個：規則明確（兩位小數、half-up 的四捨五入、去掉尾端的 0、捨入後如果剛好到下一個單位就進位升級），不依賴 ICU 的版本（ICU 是 International Components for Unicode 的縮寫，平台內建的國際化函式庫，iOS 和 Android 都用它）。幾個預期的結果：

| 輸入 | 語言 | 輸出 | 說明 |
|---|---|---|---|
| 9,999 | ja | `9,999` | 小於 1 万不縮寫，但有千分位 |
| 12,345 | ja | `1.23万` | 兩位小數 |
| 100,000,000 | ja | `1億` | 沒有小數就不顯示小數點 |
| 99,999,999 | ja | `1億` | 9999.9999 万 捨入成 10000 万，進位升級為 `1億` |
| 123,456,789 | en | `123.46M` | 英文是三位一組 |

做法上要用整數運算，不要用 Double，避免浮點數誤差（見「精度與金額」單元）。

> [!TIP]
> 面試時可以這樣說：「日文和中文的大數單位是四位一組，英文是三位一組，所以不能直接把 K、M、B 換成万、億；簡體的 10 的 12 次方是万亿，日文和繁體是兆。平台的 compact formatter 只保留約兩位有效數字、依系統版本結果不同，產品要明確規則的話我會自己實作，並用固定輸入的測試案例固定下來。compact 只用在輔助標籤，不用在下單金額和確認頁。」

### 4. 日期與時區怎麼處理？JST 是什麼？

**結論：資料內部一律存 UTC（世界協調時間，或 Unix 時間戳記），與 API 交換的時間字串用固定格式（`en_US_POSIX`、西曆、明確時區）；只有顯示給使用者時，才使用使用者的 Locale。日本使用 JST（Japan Standard Time，日本標準時間，UTC＋9），沒有夏令時間，但使用者可能在國外，所以顯示 JST 還是裝置時區是產品決定，標籤一定要寫明確。**

這套交易所 API 的文件裡，出入金記錄的 `created-at` 是 Unix 時間戳記（例如 `1510912472199`，13 位數，單位是毫秒），App 收到之後轉成要顯示的時區。

幾個容易踩到的坑：

**一、`yyyy` 和 `YYYY` 不一樣。** `yyyy` 是西曆年，`YYYY` 是週年（week-based year，以「週」為單位算的年份）。跨年那一週的日期會顯示成下一年。K 線（呈現價格走勢的蠟燭圖）和交易紀錄的時間標籤在年底年初最容易踩到。

```swift
let f = DateFormatter()
f.locale = Locale(identifier: "en_US_POSIX")
f.calendar = Calendar(identifier: .gregorian)            // 西曆
f.timeZone = TimeZone(identifier: "Asia/Tokyo")!          // 明確指定時區
let date = f.calendar.date(from: DateComponents(timeZone: f.timeZone, year: 2024, month: 12, day: 31, hour: 12))!

f.dateFormat = "yyyy-MM-dd"
print(f.string(from: date))      // 輸出：2024-12-31
f.dateFormat = "YYYY-MM-dd"
print(f.string(from: date))      // 輸出：2025-12-31（12 月 31 日那一週屬於 2025 年的第 1 週，年份被算成下一年）
```

**二、曆法。** 使用者如果把 iOS 的日曆設為日本曆（和暦），用 `Locale.current` 的 `DateFormatter` 配固定格式 `yyyy-MM-dd`，會輸出令和年（本機實測 2025-12-29 得到 `0007-12-29`，令和 7 年）。所以與 API 交換的時間字串，一律固定 `en_US_POSIX`、西曆、明確時區；顯示給使用者時，才用本地化的日期範本。

**三、時區縮寫。** 本機實測 `Asia/Tokyo` 的 `zzz` 在 `ja_JP` 是 `JST`，在 `en_US`、`zh_Hans_CN`、`zh_Hant_TW` 都是 `GMT+9`。想顯示 JST，就自己加上後綴，不要依賴系統給的縮寫：

```swift
f.dateFormat = "yyyy-MM-dd HH:mm:ss"
print(f.string(from: Date(timeIntervalSince1970: 1_700_000_000)) + " JST")
// 輸出：2023-11-15 07:13:20 JST
```

**四、12 小時制與 24 小時制。** 日本習慣用 24 小時制；英文環境的預設可能是 12 小時制加 AM／PM。交易紀錄與 K 線建議使用固定、沒有歧義的格式（`2026-10-23 14:05:09 JST`），一般的提示文字才用本地化的範本。

> [!TIP]
> 面試時可以這樣說：「資料內部存 UTC，與 API 交換的時間用 en_US_POSIX、西曆、明確時區；只有顯示時才用使用者的 Locale。要小心 YYYY 是週年、日本曆會讓 yyyy 變成令和年。交易紀錄我會用固定的 24 小時格式並標示 JST。」

### 5. 文字的複數、長度與字形要注意什麼？（比較少考，被問到能答就好）

**結論：不要用字串拼接組句子，複數交給平台的複數機制；文字長度因語言而異，價格和數字不能被截斷；同一個漢字在日文和中文的字形可能不同。**

**不要拼接字串。** `"You have " + n + " orders"` 在日文裡語序就不對（「未約定の注文が N 件あります」）。要用格式字串搭配位置參數，複數用平台的機制：iOS 的 `.stringsdict` 或 String Catalog 的 plural variation，Android 的 `<plurals>`。

CLDR 把複數分成幾種類別：日文和中文只有 `other`，英文有 `one`（單數）和 `other`，其他語言還有 `few`、`many` 等。來源語言（英文）的 plural 變體要完整提供。

```text
英文（values/strings.xml）：
<plurals name="open_orders">
    <item quantity="one">You have %d open order</item>
    <item quantity="other">You have %d open orders</item>
</plurals>

日文（values-ja/strings.xml），只需要 other：
<plurals name="open_orders">
    <item quantity="other">未約定の注文が%d件あります</item>
</plurals>
```

**文字長度。** 同一個意思，英文的句子通常比日文長，按鈕和標籤要能換行或縮小。**數字、價格不能被截斷**（用較小的字級或換行，不要用省略號）。可以用偽在地化（把文字刻意加長）和最長的語言去檢查版面。

**字形。** 日文、簡體、繁體共用同一批 Unicode 字碼，渲染出來的字形取決於字型和語言標記，同一個字在日文和中文的字形可能不同。iOS 選擇 CJK（中日韓文字）字型的依據是使用者的偏好語言順序，App 內選的語言不一定會被考慮；Android 的 `Locale` 和 Compose 的 `LocaleList` 會影響字形選擇；WebView（App 內嵌的網頁畫面）的內容要加上 `<html lang="ja">`。這一項沒有逐項實測（未證實），列為上線前的檢查項目。

**金融術語要統一。** 例如成行（市價單）、指値（限價單）、板（訂單簿）、約定（成交），每個語言都需要一份詞彙表，翻譯時才不會同一個詞出現好幾種說法。

> [!TIP]
> 面試時可以這樣說：「我不拼接字串，用格式字串和平台的 plural 機制，英文有 one 和 other，日文只需要 other。價格不會被截斷，我會用偽在地化和最長的語言檢查版面。金融術語會維護一份各語言的詞彙表。」

### 6. 日本使用者輸入全形數字時，要怎麼解析？

**結論：先做 NFKC（Normalization Form KC，Unicode 的一種正規化方式）正規化，把全形轉成半形，再用嚴格的規則解析，最後才轉成 Decimal。不要依賴寬鬆的解析函式，它可能悄悄把 `123.5` 截成 `123`，對交易所是致命的。**

日文輸入法（IME，Input Method Editor）可能輸入全形的數字與符號，例如 `１２３．５`。NFKC 會把全形的數字、小數點轉成半形的 ASCII（美國資訊交換標準代碼，最基本的半形英數字元集）。本機實測的結果：

- Swift 的 `Double("１２３．５")` 和 `Decimal(string:)` 都回傳 `nil`；先做 NFKC 正規化，才得到 `123.5`。
- JDK 21（Java Development Kit 第 21 版）的 `NumberFormat.getInstance(Locale.JAPAN).parse("１２３．５")` 得到 **`123`**，在全形小數點處悄悄截斷，沒有任何錯誤；`parse("1,234.5abc")` 得到 `1234.5`（忽略尾端的垃圾）。`BigDecimal("１２３")` 反而可以解析 Unicode 的數字，`Double.parseDouble("１２３．５")` 卻丟例外。各個函式行為不一致，正是要自己先正規化、再嚴格驗證的原因。Android 的行為要另外實測。

```kotlin
import java.math.BigDecimal
import java.text.Normalizer

// 先 NFKC 正規化（全形 → 半形），再用嚴格規則檢查，最後才轉成 BigDecimal
fun parseAmount(input: String): BigDecimal? {
    val normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
    if (!Regex("[0-9]+(\\.[0-9]+)?").matches(normalized)) return null   // 只允許數字與一個小數點
    return BigDecimal(normalized)
}
// parseAmount("１２３．５")  輸出：123.5
// parseAmount("1,234.5abc") 輸出：null（格式不合法，不要悄悄採用部分結果）
// parseAmount("−5")         輸出：null
```

```swift
// Swift 版本
func parseAmount(_ input: String) -> Decimal? {
    let normalized = input.trimmingCharacters(in: .whitespaces).precomposedStringWithCompatibilityMapping   // NFKC
    guard normalized.range(of: #"^[0-9]+(\.[0-9]+)?$"#, options: .regularExpression) != nil else { return nil }
    return Decimal(string: normalized, locale: Locale(identifier: "en_US_POSIX"))
}
// parseAmount("１２３．５") 輸出：Optional(123.5)；parseAmount("1,234.5abc") 輸出：nil
```

幾個補充：

- 負號 `−`（U+2212）和長音符 `ー` 不會被 NFKC 轉成 `-`。交易所的數量與價格欄位不需要負號，直接拒絕。
- 數字鍵盤的小數點，會依鍵盤的地區可能是逗號；使用者貼上的字串可能有千分位、空白（NBSP，不換行空白）。解析前先定義清楚接受哪些格式，其餘一律當作錯誤並顯示訊息，不可以靜默採用部分結果，更不可以把失敗當作 0。

**測試**：格式化函式用「注入的 Locale」搭配固定輸入的 golden test（把預期輸出固定下來比對的測試），不依賴 `Locale.current`。iOS 可以用啟動參數 `-AppleLanguages (ja)`、`-AppleLocale ja_JP` 測試，Android 用 per-app language 或 `adb`（Android Debug Bridge，連接手機的命令列工具）。每個語言、每種文字縮放大小（iOS 的 Dynamic Type、Android 的字型縮放）都要檢查版面，特別是價格欄。

> [!TIP]
> 面試時可以這樣說：「日本使用者會用全形數字，所以我會先做 NFKC 正規化，再用嚴格的規則解析，最後才轉成 Decimal。我不依賴寬鬆的 parse，因為它可能悄悄把 123.5 截成 123，解析失敗要顯示錯誤，不能當成 0。」

## 實際演練

> [!LAB]
> **打開**：iOS 用 Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Exchange13Localization**。Android 用 Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Exchange13Localization**。
> **檔案**：iOS 你寫在 `labs/ios/Practice/Exchange/Exchange13-Localization/`，解答在 `labs/ios/Solution/Exchange/Exchange13-Localization/`；Android 你寫在 `labs/android/practice/exchange/exchange13-localization/`，解答在 `labs/android/solution/exchange/exchange13-localization/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → 執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 再執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **不依賴手機的設定**：語言、時區、使用者輸入都是演練裡明確傳進來的資料（`Locale(identifier:)`、`TimeZone`、固定的字串），不管模擬器或電腦本身設定成什麼，結果都一樣。

### 演練 1：香港和簡體區的使用者看到英文介面（第 1 題）

**檔案**：`L01_LocaleFallback.swift`（iOS）、`L01_LocaleFallback.kt`（Android）

**情境**：App 提供四種語言：`ja`、`en`、`zh-Hans`（簡體）、`zh-Hant`（繁體）。使用者的系統語言標籤五花八門：`zh-Hant-TW`、`zh-Hant-HK`、`zh-Hans-CN`、舊式的 `zh-TW`、`zh-CN`、`zh-HK`、`ja-JP`、`en-US`，甚至沒有支援的 `fr-FR`。`resolve` 要回傳 App 該用的語言；找不到時，依「完整標籤、去掉最後一段、繁簡推斷、預設語言 en」的順序降級。

**怎麼寫**

1. **Bug 版**：把 `zh-CN` 和 `zh-TW` 寫死成簡體和繁體，其他標籤只比對「語言」（第一個 `-` 前面的部分），App 沒有 `zh` 就退回 `en`。
2. **修正版**：先看完整標籤是不是支援的；語言是 `zh` 時，標籤裡有 4 個字母的 script 段（`Hans`、`Hant`）就用它，沒有就用地區推斷（`TW`、`HK`、`MO` 是繁體，其他是簡體）；再看語言碼；都找不到才用預設語言 `en`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `zh-Hant-TW`、`zh-Hant-HK`、`zh-Hans-CN`、`zh-HK` 都變成 `en`（舊式的 `zh-TW`、`zh-CN` 因為寫死所以剛好對）：「共 9 個語言標籤，選錯語言的有 4 個」 |
| 修正版 | 九個標籤都選對：`zh-Hant-HK` → `zh-Hant`、`zh-HK` → `zh-Hant`、`zh-Hans-CN` → `zh-Hans`，`fr-FR` → `en`：「共 9 個語言標籤，選錯語言的有 0 個」 |

**為什麼**：簡體和繁體是文字（script）的差異，不是地區的差異，所以標籤是 `zh-Hans`、`zh-Hant`，不是 `zh-CN`、`zh-TW`。現在的系統給的標籤常常是 `zh-Hant-TW` 這種三段式；寫死兩個舊式標籤，香港、澳門、新加坡的使用者就會掉到英文。降級鏈的意思是「找不到就退一步」：`zh-Hant-HK` 退一步是 `zh-Hant`，不是 `zh`，因為 App 根本沒有 `zh`。App 內選的語言和系統地區是兩件事，格式化數字與日期要用同一個 `Locale`。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L01_LocaleFallback.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L01_LocaleFallback.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L01_LocaleFallback.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L01_LocaleFallback.kt#fixed
```

### 演練 2：日圓顯示成 ¥1,234,567.80，負數的負號在符號後面（第 2 題）

**檔案**：`L02_YenFormat.swift`（iOS）、`L02_YenFormat.kt`（Android）

**情境**：日圓（JPY）的最小單位就是 1 圓，沒有小數位數；貨幣符號和負號的位置依語系不同（本機實測 `ja_JP` 與 `en_US` 是 `-¥1,234`，`zh_Hans_CN` 是 `-JP¥1,234`）。演練把四筆資料交給 `formatYen`：1234567.8（ja）、-1234.4（ja）、1234567.8（en_US）、1234567.8（zh_Hans_CN）。檢查看三件事：數字是不是整數、有千分位（日圓沒有小數）；負數的 `-` 是不是在最前面；符號出現在哪裡不檢查（交給平台依語系決定）。四捨五入的模式在 Exchange02 的演練 5 練過，這題的資料都避開剛好在中間的值。

**怎麼寫**

1. **Bug 版**：自己拼字串：寫死 `"¥"`，數字用固定兩位小數的格式（iOS 的 `NumberFormatter` `.decimal`、Android 的 `DecimalFormat("#,##0.00")`）。
2. **修正版**：用平台的貨幣格式，`currencyCode` 設 `JPY`，`locale` 用明確傳進來的使用者語系，捨入模式明確設成 half-up。iOS 的 `.currency` 設了 `JPY` 之後小數位數自動是 0；Android 的 `NumberFormat.getCurrencyInstance` 只設 `currency` 不會改小數位數，要自己把 `minimumFractionDigits`、`maximumFractionDigits` 設成 `currency.defaultFractionDigits`（JPY 是 0）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「1234567.8，ja → ¥1,234,567.80 ✗」（多了不存在的小數），「-1234.4，ja → ¥-1,234.40 ✗」（負號在符號後面），其他兩筆同樣多了小數：「共 4 筆金額，顯示錯的有 4 筆」 |
| 修正版 | 「¥1,234,568」「-¥1,234」，簡體中文是「JP¥1,234,568」（Android 電腦上的 ja-JP 符號是全形的 ￥）：「共 4 筆金額，顯示錯的有 0 筆」 |

**為什麼**：自己拼符號和小數位數，會同時漏掉三件事：日圓沒有小數、符號的位置依語系不同、負號的位置依語系不同。這些規則都在平台的 CLDR 資料裡，要做的是明確告訴它「幣別是 JPY、語系是這個」。Android 的陷阱更隱蔽：用 `en-US` 的格式化器再 `setCurrency(JPY)`，符號變成 ¥，小數卻還是兩位，看起來「已經設了日圓」。另外兩個原則：顯示用的字串和送給 API 的字串要完全分開（API 用固定格式，不能用顯示用的 formatter）；加密資產用代號（BTC、ETH），不用符號。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L02_YenFormat.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L02_YenFormat.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L02_YenFormat.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L02_YenFormat.kt#fixed
```

### 演練 3：成交量顯示 10000万，1.005万 少了零頭（第 3 題）

**檔案**：`L03_CompactUnits.swift`（iOS）、`L03_CompactUnits.kt`（Android）

**情境**：產品規格要把市值、成交量縮寫成大數單位：兩位小數、四捨五入（half-up）、去掉尾端的 0、捨入後剛好到下一個單位要進位升級。日文：万（10 的 4 次方）、億、兆；繁體中文：萬、億、兆；簡體中文：万、亿、万亿（10 的 12 次方）；英文：K、M、B、T（三位一組）。小於最小單位的數字不縮寫，但要有千分位。演練檢查 11 筆，例如：ja 的 9999 → `9,999`、12345 → `1.23万`、10050 → `1.01万`、99999999 → `1億`（9999.9999万 捨入成 10000万，要升級成 1億）、1234567890123 → `1.23兆`；en 的 123456789 → `123.46M`；zh-Hans 的 1234567890123 → `1.23万亿`。

**怎麼寫**

1. **Bug 版**：用 `Double` 除以單位，乘 100 之後取整、再除以 100（`(x * 100).rounded() / 100`；Android 用 `Math.round(x * 100) / 100.0`），去掉結尾的 `.0`；小於最小單位的數字直接轉字串，沒有千分位。
2. **修正版**：全程用整數運算：`scaled = (value × 100 + 單位 ÷ 2) ÷ 單位`（兩位小數、half-up）；如果 `scaled` 已經達到「下一個單位 ÷ 這個單位 × 100」，就改用下一個單位重算（可以連續升級）；整數部分是 `scaled ÷ 100`，小數是 `scaled % 100`（去掉尾端的 0）；小於最小單位用千分位格式。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「ja 10050 → 1万 ✗」（應該是 1.01万）、「ja 99999999 → 10000万 ✗」（應該升級成 1億）、「ja 9999 → 9999 ✗」（沒有千分位），其他 8 筆剛好對：「共 11 筆，縮寫錯的有 3 筆」 |
| 修正版 | 11 筆都和正確答案一致：「共 11 筆，縮寫錯的有 0 筆」 |

**為什麼**：`Double` 存不準 1.005（二進位裡其實是 1.00499999…），乘 100 得到 100.49999999999999，取整後是 100，所以 1.005万 變成 1万。這種差一點的錯誤只會在特定的數字出現，單元測試不挑到那個數字就看不到。捨入後進位是另一個容易漏的邊界：99999999 ÷ 10000 = 9999.9999，捨入成兩位小數是 10000.00，這時單位已經不對了，要升級成 1億。全程用整數運算，這兩個問題都不會出現。補充：Java 的 `"%.2f".format(1.005)` 反而會得到 1.01，因為它是用 `Double` 的最短十進位表示再 HALF_UP，和 C 語言的 `printf` 不一樣；平台之間的行為不同，所以不要靠 `Double` 的捨入。compact 是有損的顯示，只用在市值、成交量這類輔助標籤，不能用在下單數量、確認頁、損益明細。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L03_CompactUnits.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L03_CompactUnits.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L03_CompactUnits.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L03_CompactUnits.kt#fixed
```

### 演練 4：日報表的筆數和後台對不上，半夜的交易算到前一天（第 4 題）

**檔案**：`L04_JstDay.swift`（iOS）、`L04_JstDay.kt`（Android）

**情境**：日本的交易所，日報表的「一天」以日本標準時間（JST，UTC＋9，沒有夏令時間）的 0 點為界。使用者出差到台北（UTC＋8），手機的時區是 `Asia/Taipei`。伺服器回傳的交易時間是一個時間點。演練有四筆 JST 的交易：2026-10-23 23:50、2026-10-24 00:10、2026-10-24 08:55、2026-10-24 23:59，`dayKey` 要回傳這筆交易屬於哪一天（`yyyy-MM-dd`），再統計每一天有幾筆。後台（JST）的答案是：10-23 有 1 筆，10-24 有 3 筆。

**怎麼寫**

1. **Bug 版**：用傳進來的裝置時區（台北）切日：iOS 的 `Calendar` 設 `timeZone = deviceTimeZone`，Android 用 `instant.atZone(deviceZone)`。
2. **修正版**：日報表的「日」固定用 JST（`Asia/Tokyo`）切日，iOS 用西曆 `Calendar` 加固定時區，Android 用 `ZoneId.of("Asia/Tokyo")`，不看手機在哪個時區。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「日報表 2026-10-23：2 筆 ✗（後台是 1 筆）」「日報表 2026-10-24：2 筆 ✗（後台是 3 筆）」：「日報表有 2 天的筆數和後台不一致」 |
| 修正版 | 「日報表 2026-10-23：1 筆 ✓」「日報表 2026-10-24：3 筆 ✓」：「日報表有 0 天的筆數和後台不一致」 |

**為什麼**：資料內部存時間點（UTC 或 Unix 時間戳記），只有「顯示、分組」時才換算成某個時區。「以哪一天為界」是業務規則，不是手機的設定：日本的交易所用 JST，所以日報表要固定 JST，否則出差的使用者、不同時區的使用者看到的日報表都不一樣，和後台、帳單永遠對不上。到底畫面上的時間顯示 JST 還是裝置時區，是產品決定，標籤一定要寫明確（`2026-10-24 00:10 JST`）。與 API 交換的時間字串則固定 `en_US_POSIX`、西曆、明確時區（Exchange09 的演練 4 練過）。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L04_JstDay.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L04_JstDay.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L04_JstDay.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L04_JstDay.kt#fixed
```

### 演練 5：跨年那天的紀錄顯示成明年，晚上 11 點顯示 11:59（第 4 題）

**檔案**：`L05_WeekYear.swift`（iOS）、`L05_WeekYear.kt`（Android）

**情境**：交易紀錄的時間標籤要用固定、沒有歧義的格式：`2026-10-23 14:05:09 JST`（24 小時制，加上 JST 後綴）。formatter 的語言、曆法（iOS）、時區都已經固定好，只剩日期格式字串要寫。演練有三筆 JST 的時間：2024-12-31 23:59:59、2025-01-01 00:00:05、2026-10-24 13:05:09。語言、曆法、時區不固定造成的問題，在 Exchange09 的演練 4 練過，這題只看格式字串。

**怎麼寫**

1. **Bug 版**：格式字串寫成 `"YYYY-MM-dd hh:mm:ss"`。
2. **修正版**：格式字串用 `"yyyy-MM-dd HH:mm:ss"`，再接上 `" JST"`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「跨年前一秒 → 2025-12-31 11:59:59 JST ✗」（年份變成 2025，小時變成 11），「新年第一天 0 點 → 2025-01-01 12:00:05 JST ✗」，「一般的下午 → 2026-10-24 01:05:09 JST ✗」（13 點顯示成 01）：「共 3 筆時間標籤，顯示錯的有 3 筆」 |
| 修正版 | 三筆都和正確答案一致，例如 `2024-12-31 23:59:59 JST`：「共 3 筆時間標籤，顯示錯的有 0 筆」 |

**為什麼**：`YYYY` 是週年（week-based year，以「週」為單位算的年份）：12 月 31 日那一週屬於 2025 年的第 1 週，所以 2024-12-31 顯示成 2025-12-31；`yyyy` 才是西曆年。這種 bug 一年只有年底年初幾天會出現，平常測試完全看不到，K 線和交易紀錄的時間標籤最容易中。`hh` 是 12 小時制（1 到 12），`HH` 才是 24 小時制。時區縮寫（`zzz`）在 `ja_JP` 是 JST，在 `en_US`、`zh_Hant_TW` 是 `GMT+9`，想顯示 JST 就自己加上後綴，不要依賴系統給的縮寫。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L05_WeekYear.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L05_WeekYear.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L05_WeekYear.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L05_WeekYear.kt#fixed
```

### 演練 6：全形輸入 １２３．５ 變成 0，不合法的輸入被悄悄採用（第 6 題）

**檔案**：`L06_FullWidth.swift`（iOS）、`L06_FullWidth.kt`（Android）

**情境**：日文輸入法可能輸入全形的數字與符號，例如 `１２３．５`。下單的數量欄位要解析成數字。規則：先做 NFKC 正規化（把全形轉半形），再用嚴格的格式解析（只接受數字加最多一個小數點），不合法的一律拒絕並顯示錯誤，不可以悄悄採用部分結果，更不可以把失敗當成 0。八筆輸入：`１２３．５`、`123.5`、`０．００１２`、`1,234.5abc`、`−5`（U+2212 的負號）、空字串、`1 000`（中間有空白）、`12.5.3`。

**怎麼寫**

1. **Bug 版**：直接用 `Decimal(string:)`（iOS）或 `toBigDecimalOrNull()`（Android）解析，失敗就當成 0（`?? 0`、`?: BigDecimal.ZERO`）。
2. **修正版**：先去掉前後空白，做 NFKC 正規化（iOS 用 `precomposedStringWithCompatibilityMapping`，Android 用 `Normalizer.normalize(…, Normalizer.Form.NFKC)`），用正規表示式 `^[0-9]+(\.[0-9]+)?$` 嚴格檢查，通過才轉成 `Decimal`／`BigDecimal`；其他回傳 `nil`（`null`），畫面顯示錯誤。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 全形的 `１２３．５`、`０．００１２` 變成 `0`；不合法的輸入：Android 全部變成 `0`；iOS 的 `Decimal(string:)` 對「前面像數字」的字串很寬鬆，`1,234.5abc` 解成 `1`、`1 000` 解成 `1`、`12.5.3` 解成 `12.5`，只有半形的 `123.5` 是對的：「共 8 筆輸入，解析結果錯的有 7 筆」 |
| 修正版 | 全形的 `１２３．５` 解出 `123.5`，`０．００１２` 解出 `0.0012`，其餘不合法的都「拒絕」：「共 8 筆輸入，解析結果錯的有 0 筆」 |

**為什麼**：平台的解析函式行為不一致：Swift 的 `Decimal(string:)` 不認得全形（回傳 `nil`），卻會悄悄把 `1,234.5abc` 截成 `1`；Java 的 `NumberFormat.parse` 遇到全形小數點悄悄截成 123（本機 JDK 21 實測），遇到 `1,234.5abc` 得到 1234.5；`BigDecimal(String)` 認得全形數字卻不認得全形小數點。所以不能直接把使用者輸入丟給它們：自己先正規化、再用嚴格的規則驗證。數量變成 0 或被悄悄改成別的數字，使用者按下買入就是錯的訂單。負號 `−`（U+2212）和長音符 `ー` 不會被 NFKC 轉成 `-`，交易所的數量與價格欄位不需要負號，直接拒絕。

**iOS 解答**

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L06_FullWidth.swift#bug
```

```lab-solution
ios/Solution/Exchange/Exchange13-Localization/L06_FullWidth.swift#fixed
```

**Android 解答**

```lab-solution
android/solution/exchange/exchange13-localization/L06_FullWidth.kt#bug
```

```lab-solution
android/solution/exchange/exchange13-localization/L06_FullWidth.kt#fixed
```

第 5 題（複數、文字長度、字形）要看畫面與字型才檢查得出來（版面被截斷、同一個漢字在日文與中文的字形不同），沒有可以穩定自動檢查的演練。第 2 題的 half-even 預設捨入在 Exchange02 的演練 5 練過，不重複。

## 常見陷阱

- **用 `Locale.current` 格式化送給 API 的資料。** 使用者的曆法或地區一變，格式就壞了。API 用固定的 `en_US_POSIX`、西曆、明確時區。
- **以為預設捨入是四捨五入。** 其實是 half-even，邊界值會捨入到偶數。要明確設定捨入模式。
- **把 compact 顯示（`1.2億`）當成精確數值，或直接把 K、M、B 換成萬、億。** compact 是有損的，而且中日文是四位一組，英文是三位一組。
- **把 `YYYY` 當成年份。** `YYYY` 是週年，跨年那一週的日期會顯示成下一年。
- **把 `zh-CN`、`zh-TW` 當成簡繁的唯一代表。** 忽略了 `zh-Hant-HK` 等，要用 `zh-Hans`、`zh-Hant`。
- **字串拼接，或寫死單數複數。** 不同語言的語序和複數規則不同。
- **把全形數字輸入直接丟給 `Double()` 或 `NumberFormat.parse`。** 會得到 `nil`，或悄悄截斷的數值。
- **價格被省略號截斷。** 價格和數字要換行或縮小字級，不能被截斷。
- **App 內語言和系統地區混用。** 同一個畫面的日期和數字格式會不一致。

## 面試官想聽到的

1. 對四種語言的理解：簡繁是文字（script）的差異、降級鏈、App 內語言和系統地區的差異。
2. 大數單位的差異（万／億是四位一組，K／M／B 是三位一組），以及平台 compact formatter 的限制和自訂規則。
3. 預設捨入模式、日圓沒有小數、貨幣與百分比等數字格式的陷阱，以及顯示字串和 API 字串分開。
4. 日期的 `yyyy` 與 `YYYY`、曆法與時區（JST）的處理。
5. 複數、文字長度與字形的考量，金融術語的統一。
6. 輸入的正規化與嚴格解析（全形、貼上、鍵盤）。

> [!FIELD]
> 與這個主題最相關的經驗：
> - 在一個社交 App 用 AI agent 協作自建字串管理系統，取代第三方的字串管理服務，統一管理英、日、繁中三種語系，並內建 AI 翻譯。字串可以不發版就更新（OTA，over-the-air，透過網路直接更新）；伺服器用版本號同步變更，刪除的字串用 tombstone（刪除標記）記錄；支援複數；離線時退回 App 內建的字串。
> - 在一個行情分析 App，iOS 與 Android 用同一套數字顯示規則（大於 1 的值最多 2 位小數，很小的值顯示到 8 位小數，往下捨入）。
> - 在日本團隊用日文開發的工作經驗，可以讀懂日文的需求與金融術語。
> 到交易所的 App，我會依產品規格明確處理大數單位和捨入規則，自己實作可預測的 formatter，用固定 Locale 的 golden test 驗證，並在使用者輸入時先 NFKC 正規化再嚴格解析。
> 面試時可以這樣說：「我在一個社交 App 做過多語系字串的同步和複數規則，在一個行情分析 App 做過雙平台一致的數字顯示。到交易所，我會先確認大數單位和捨入的產品規格，自己實作可預測的 formatter，用固定 Locale 的 golden test 驗證；使用者輸入先 NFKC 正規化再嚴格解析。」

## iOS / Android 對照

| 主題 | iOS | Android |
|---|---|---|
| 簡繁標籤 | `zh-Hans`、`zh-Hant` | 資源限定詞 `b+zh+Hans`、`b+zh+Hant`（BCP 47 寫法）或 `zh-rCN`、`zh-rTW` |
| 數字格式 | `FormatStyle`、`NumberFormatter`（預設 half-even） | `NumberFormat`、`DecimalFormat`（預設 `HALF_EVEN`，不能多執行緒共用） |
| compact | `.number.notation(.compactName)`（iOS 15 起） | `CompactDecimalFormat`（ICU，API 24 起） |
| 日期範本 | `setLocalizedDateFormatFromTemplate` | `DateFormat.getBestDateTimePattern` |
| 複數 | `.stringsdict`、String Catalog | `<plurals>`、`getQuantityString` |
| 全形正規化（NFKC） | `precomposedStringWithCompatibilityMapping` | `Normalizer.normalize(s, NFKC)` |
| App 內語言 | 系統的 App 語言設定 | `AppCompatDelegate.setApplicationLocales`、`LocaleManager`（Android 13 起） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 在地化 | localization | ローカライズ |
| 語系 | locale | ロケール |
| 大數單位 | large-number units（compact notation） | 万・億・兆などの単位 |
| 銀行家捨入 | half-even（banker's rounding） | 偶数丸め（銀行丸め） |
| 日本標準時間 | JST（Japan Standard Time） | 日本標準時 |
| 複數 | plural | 複数形 |
| 全形／半形 | full-width／half-width | 全角／半角 |
| 正規化 | normalization | 正規化 |
