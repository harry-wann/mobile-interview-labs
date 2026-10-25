---
id: swift-language-features
title: Swift 語言特性：property wrapper、result builder、macro、typed throws、~Copyable
summary: 這些題目是在確認你有沒有跟上 Swift 的演進。重點不是背語法，而是說得出每個特性解決什麼問題、從哪個 Swift 版本開始，以及在交易所 App 裡可以怎麼用。
priority: P2
day: 1
tags: [property-wrapper, result-builder, macro, keypath, typed-throws, noncopyable, opaque-type]
---

## 重點講解

這個單元回答七個面試常見問題：

1. property wrapper 是什麼？解決什麼問題？
2. result builder 是什麼？SwiftUI 的 `body` 為什麼可以那樣寫？
3. macro 是什麼？解決什麼問題？（Swift 5.9）
4. KeyPath 是什麼？什麼時候用？
5. typed throws 解決什麼問題？（Swift 6.0）
6. `~Copyable` 是什麼？怎麼用它保證「只能用一次」？（Swift 5.9）
7. `some` 與 `any` 差在哪？（比較少考，被問到能答就好）

這些特性的共通主題是：**把原本要靠自己小心維持的規則，交給編譯器檢查。** 面試時被問到，先講它解決什麼問題，再講版本與代價，不要只背語法。

### 1. property wrapper 是什麼？解決什麼問題？

property wrapper（屬性包裝器，Swift 5.1 加入）讓你把「存取一個屬性時要附加的規則」寫成一個可重複使用的型別，用在屬性前面，寫法是 `@名稱`。它解決的是：同樣的存取邏輯（驗證範圍、讀寫 `UserDefaults`、延遲初始化）原本每個屬性都要自己寫一份 `get`／`set`，現在寫一次、到處套用。

SwiftUI 的 `@State`、`@Published`、`@AppStorage` 全都是 property wrapper。以交易所的下單數量輸入為例，數量必須落在 1 到 100 之間，超出範圍就自動夾到邊界：

```swift
@propertyWrapper
struct Clamped<Value: Comparable> {
    private var value: Value
    let range: ClosedRange<Value>

    init(wrappedValue: Value, _ range: ClosedRange<Value>) {
        self.range = range
        self.value = min(max(wrappedValue, range.lowerBound), range.upperBound)
    }

    // wrappedValue：屬性本身的值，讀寫 lots 時走這裡
    var wrappedValue: Value {
        get { value }
        set {
            value = min(max(newValue, range.lowerBound), range.upperBound)
        }
    }

    // projectedValue：用 $ 前綴取得的附加資訊，這裡回傳允許的範圍
    var projectedValue: ClosedRange<Value> { range }
}

struct QuantityInput {
    @Clamped(1...100) var lots = 150
}

var input = QuantityInput()
// 輸出：100（初始值 150 超過上限，被夾到 100）
print(input.lots)
input.lots = -5
print(input.lots) // 輸出：1
// 輸出：1...100（$ 取得 projectedValue）
print(input.$lots)
```

運作方式：編譯器會自動替 `lots` 產生一個叫 `_lots` 的底層屬性，用來存放 wrapper 本身；讀寫 `lots` 時，實際上是呼叫 wrapper 的 `wrappedValue`；加上 `$` 則取得 `projectedValue`。在 `init` 裡直接寫 `_lots = Clamped(wrappedValue: 5, 1...100)`，就是直接設定底層的 wrapper（SwiftUI 在 `init` 裡設定 `@State` 初始值時寫的 `_local = State(initialValue: value)`，就是這個用法）。

代價要知道：套用在 `Codable` 的 struct 上時，實際被編碼的是 wrapper 本身，所以 wrapper 也要遵守 `Codable`；多個 wrapper 疊加時順序有影響；忘了寫 `init(wrappedValue:)`，就不能用 `= 預設值` 的寫法。

> [!TIP]
> 面試時可以這樣說：「property wrapper 讓我把存取屬性時的規則寫成可重複使用的型別，`wrappedValue` 是屬性本身，`projectedValue` 對應 `$` 前綴。SwiftUI 的 `@State`、`@Published` 都是這樣做的。在交易所 App 我會用它做輸入欄位的範圍與刻度驗證。」

### 2. result builder 是什麼？SwiftUI 的 `body` 為什麼可以那樣寫？

result builder（Swift 5.4 正式命名，前身叫 function builder）讓你用「一行一個項目」的宣告式寫法，組出一個結果，而不用自己宣告陣列再一個一個 `append`。SwiftUI 的 `body` 裡面可以直接並排好幾個 view、也能寫 `if / else`，靠的就是 `@ViewBuilder` 這個 result builder。

原理是：編譯器把標了 `@resultBuilder` 的 closure 裡面的每一個敘述，轉成對這個 builder 的靜態方法的呼叫：`buildExpression` 處理單一項目、`buildBlock` 把全部合併、`buildOptional` 處理沒有 else 的 `if`、`buildEither(first:)`／`buildEither(second:)` 處理 `if / else`、`buildArray` 處理 `for` 迴圈。想支援哪種語法，就實作對應的方法。

下面用 builder 組出 API 請求的 query 參數：

```swift
import Foundation

@resultBuilder
enum QueryBuilder {
    static func buildExpression(_ item: URLQueryItem) -> [URLQueryItem] {
        [item]
    }

    static func buildBlock(_ parts: [URLQueryItem]...) -> [URLQueryItem] {
        parts.flatMap { $0 }
    }

    static func buildOptional(_ part: [URLQueryItem]?) -> [URLQueryItem] {
        part ?? []
    }

    static func buildEither(first: [URLQueryItem]) -> [URLQueryItem] {
        first
    }

    static func buildEither(second: [URLQueryItem]) -> [URLQueryItem] {
        second
    }
}

func query(@QueryBuilder _ content: () -> [URLQueryItem]) -> [URLQueryItem] {
    content()
}

let precise = false
let items = query {
    URLQueryItem(name: "symbol", value: "btcjpy")
    // if / else 靠 buildEither
    if precise {
        URLQueryItem(name: "depth", value: "step0")
    } else {
        URLQueryItem(name: "depth", value: "step5")
    }
}
print(items.map { "\($0.name)=\($0.value ?? "")" }.joined(separator: " "))
// 輸出：symbol=btcjpy depth=step5
```

適合的用途：組裝簽名請求的參數、測試資料的 DSL（領域專用語言，用專門的寫法描述某一類事情）。代價是：寫錯時編譯器的錯誤訊息很難讀、編譯時間變長，過度使用也會讓程式碼不容易閱讀。

> [!TIP]
> 面試時可以這樣說：「result builder 是編譯器把 closure 裡的每個敘述，轉成對 builder 的 `buildBlock`、`buildOptional`、`buildEither` 等方法的呼叫。SwiftUI 的 `@ViewBuilder` 就是這樣，所以 `body` 裡可以並排 view、寫 `if / else`。」

### 3. macro 是什麼？解決什麼問題？（Swift 5.9）

macro（巨集）是**在編譯時，替你自動產生程式碼的功能**。它解決的是：有些程式碼樣板（boilerplate，每次都要照抄的固定寫法）又長又重複，函式沒辦法簡化它，因為函式只能在執行時運作，沒辦法在編譯時檢查或產生程式碼。macro 從 Swift 5.9（Xcode 15）開始提供。

你已經天天在用它，只是可能沒意識到：

- `@Observable`：自動替類別的每個屬性加上「讀取時登記、修改時通知」的程式碼，所以你不用再寫 `@Published`（見 SwiftUI 狀態單元）。
- `#Preview`：建立 Xcode 預覽。
- `#expect`：Swift Testing（Swift 6.0／Xcode 16）的斷言。失敗時，它會把運算式裡每個部分的值都顯示出來。

```swift
import Testing

@Test func roundsDown() {
    let price = 1234.567
    // #expect 是 macro：失敗時會列出 price 的值與整個運算式的結果，
    // 不用自己寫訊息
    #expect(price.rounded(.down) == 1234)
}
```

macro 分兩類：

- **freestanding macro（獨立型）**：用 `#` 開頭，像函式一樣單獨出現，例如 `#Preview`、`#expect`。
- **attached macro（附加型）**：用 `@` 開頭，附加在某個宣告前面，例如 `@Observable`、SwiftData 的 `@Model`。

macro 有幾個重要的限制和特性：它在編譯期對語法樹（把程式碼拆成結構後的樹狀表示）展開，**只能新增程式碼，不能任意修改你寫的既有程式碼**；macro 的實作放在獨立的編譯器外掛（使用 swift-syntax 這個函式庫），在沙盒（限制權限的隔離環境）裡執行；從套件引入 macro 時，Xcode 會要求你先確認「信任並啟用」。想看展開後的結果，在 Xcode 裡對 macro 按右鍵，選 Expand Macro。

代價是：swift-syntax 讓建置時間變長、除錯展開後的程式碼比較麻煩，新同事也需要時間理解。**不要為了展示新功能而用 macro。**

要注意版本：`@Observable` 是 macro，但它依賴的 Observation 框架需要 iOS 17，所以最低版本 iOS 15 或 16 的專案不能用（見 SwiftUI 狀態單元）。macro 本身是編譯器功能，和部署目標無關。

> [!TIP]
> 面試時可以這樣說：「macro 是編譯時替你產生程式碼，解決重複樣板的問題，Swift 5.9 開始支援。像 `@Observable` 就是 attached macro，`#Preview` 是 freestanding macro。它只能新增程式碼，不能改寫既有的；代價是 swift-syntax 的建置時間和除錯成本，所以我不會為了新而用。」

### 4. KeyPath 是什麼？什麼時候用？

KeyPath 是**一個指向「某個型別的某個屬性」的值**，寫法是 `\型別.屬性`。它解決的是：你想把「哪個欄位」當成參數傳進去，而不是傳那個欄位目前的值。例如「依照成交量排序」、「依照幣種分組」這種通用工具，不需要每個欄位各寫一份。

```swift
struct Ticker {
    let symbol: String
    let volume: Int
}
let tickers = [
    Ticker(symbol: "BTC", volume: 90),
    Ticker(symbol: "ETH", volume: 50)
]

// 用 [keyPath:] 取值
let kp = \Ticker.volume
print(tickers[0][keyPath: kp]) // 輸出：90

// Swift 5.2 起，KeyPath 可以直接當作函式使用：(Ticker) -> String
// 輸出：["BTC", "ETH"]
print(tickers.map(\.symbol))

// 通用工具：不管哪個欄位，只要可以比較，都能取最大的
func maxElement<T, V: Comparable>(_ items: [T], by key: KeyPath<T, V>) -> T? {
    items.max { $0[keyPath: key] < $1[keyPath: key] }
}
// 輸出：BTC
print(maxElement(tickers, by: \.volume)?.symbol ?? "")
```

KeyPath 有幾種型別：`KeyPath`（唯讀）、`WritableKeyPath`（值型別的 `var`，可以寫入）、`ReferenceWritableKeyPath`（class 的 `var`），另外還有型別被抹掉的 `PartialKeyPath`、`AnyKeyPath`，很少用到。

效能方面，用 KeyPath 動態取值比直接寫 `ticker.volume` 慢，所以不要在每秒執行很多次的緊迴圈裡大量使用。

> [!TIP]
> 面試時可以這樣說：「KeyPath 是指向某個屬性的值，讓我可以把『哪個欄位』當參數傳進通用工具，例如排序、分組。Swift 5.2 起 `\.symbol` 還可以直接當函式使用。缺點是比直接存取慢，不適合放在每秒執行很多次的迴圈裡。」

### 5. typed throws 解決什麼問題？（Swift 6.0）

以前 Swift 的 `throws` 只告訴你「這個函式可能丟出錯誤」，但不告訴你是**哪一種**錯誤。丟出來的錯誤型別一律是 `any Error`，所以呼叫端的 `catch` 要自己轉型，而且永遠要處理「還有其他我不知道的錯誤」這種情況。

typed throws（Swift 6.0，Xcode 16）讓你在函式簽名裡寫明錯誤的型別：`throws(NetworkError)`。這樣呼叫端的 `catch` 區塊裡，`error` 的型別就是 `NetworkError`，`switch` 可以窮舉所有 case（不用寫 `default`），新增 case 時編譯器會提醒你每個處理的地方。

```swift
enum NetworkError: Error {
    case offline, badStatus(Int)
}

// 簽名寫明只會丟 NetworkError
func fetch(ok: Bool) throws(NetworkError) -> Int {
    if ok {
        return 1
    }
    throw .badStatus(500)
}

func run() {
    do {
        _ = try fetch(ok: false)
    } catch {
        // error 的型別是 NetworkError，不需要 default
        switch error {
        case .offline: print("offline")
        case .badStatus(let code): print("status", code)
        }
    }
}
run() // 輸出：status 500
```

標準函式庫的 `map` 這類 API 也會把錯誤型別傳遞出去（實測：對一個 `throws(NetworkError)` 的函式做 `try ["x"].map(parse)`，`catch` 裡的 `error` 型別是 `NetworkError`）。

**取捨：Swift 官方的建議是預設仍使用一般的 `throws`**，只在錯誤型別穩定、確定不會經常增減的邊界使用。原因是：如果公開 API 的簽名寫死了錯誤型別，之後在 enum 新增 case，所有呼叫端的 `switch` 都會編譯失敗，這是破壞性變更。函式內也只能丟出宣告的那個型別。

> [!TIP]
> 面試時可以這樣說：「typed throws 是 Swift 6.0 的功能，讓函式簽名寫明錯誤型別，`catch` 拿到具體型別，`switch` 可以窮舉。但公開 API 要小心：之後新增 error case 是破壞性變更，所以預設還是用一般 `throws`，只在錯誤集合穩定的內部邊界使用。」

### 6. `~Copyable` 是什麼？怎麼用它保證「只能用一次」？（Swift 5.9）

Swift 的 struct 預設都可以複製。`~Copyable` 的意思是「**這個型別不可以複製**」（`~` 表示取消預設具備的能力），值只能被「移動」給別人。它解決的是：有些東西語意上只能存在一份、或只能使用一次，例如一次性的簽名下單請求、檔案或網路連線這類資源。以前只能靠一個 `isSubmitted` 旗標，加上執行時檢查（`precondition`），漏檢查就會重複送單。現在可以讓編譯器直接擋住。

noncopyable 型別從 Swift 5.9 開始支援，在泛型與協定上的支援到 Swift 6.0 才補齊。

使用時要標註所有權（ownership，誰負責這個值）：

- `borrowing`：只借來用，呼叫之後原本的值還在。
- `consuming`：所有權轉移進來，呼叫之後原本的變數就不能再用。
- noncopyable 型別的函式參數**必須**標註其中一個，否則編譯器報錯 `parameter of noncopyable type … must specify ownership`。

```swift
struct SignedOrder: ~Copyable {
    let id: Int

    // 借用：看一下，不影響
    borrowing func peek() -> Int {
        id
    }

    // 消耗：送出之後這個值就沒了
    consuming func submit() -> Int {
        id
    }

    // noncopyable 型別（包含 struct）也可以有 deinit
    deinit {
        print("deinit", id)
    }
}

func place() {
    let order = SignedOrder(id: 7)
    print("peek", order.peek()) // 輸出：peek 7
    // 輸出：deinit 7（submit 結束時，被消耗的值就被銷毀）
    let r = order.submit()
    print("submitted", r) // 輸出：submitted 7
    // 編譯錯誤：'order' consumed more than once
    // order.submit()
}
place()
```

實測的結果：對同一個 noncopyable 值呼叫兩次 `consuming` 方法，編譯器報 `'order' consumed more than once`；`let u = order`（把值指派給另一個變數）是**移動**不是複製，之後再用 `order` 會報 `'order' used after consume`。

標準函式庫裡的 `Mutex`（鎖，Synchronization 模組，Swift 6.0 起，需要 iOS 18）就是 noncopyable 型別：一把鎖如果被複製，兩份各自上鎖就沒有意義了。

> [!TIP]
> 面試時可以這樣說：「`~Copyable` 讓一個型別不能被複製，只能移動。搭配 `consuming` 方法，可以讓編譯器保證『已簽名的下單請求只能送出一次』，第二次送會直接編譯失敗，比用旗標加執行時檢查更安全。這是 Swift 5.9 開始的功能，泛型支援在 6.0。」

### 7. `some` 與 `any` 差在哪？（比較少考，被問到能答就好）

`some Protocol` 叫做 opaque result type（不透明回傳型別，Swift 5.1）：由**函式的實作者**決定實際的型別，呼叫端只知道它遵守這個協定。同一個函式每次呼叫，回傳的都是**同一個**底層型別，所以編譯器可以做靜態最佳化。SwiftUI 的 `var body: some View` 就是這個。`any Protocol` 則是 existential type（存在型別，可以放任何遵守這個協定的值，執行時才知道實際是哪一個），型別可以不同，但有一些限制，而且有額外成本。

```swift
// 呼叫端只知道它是 Equatable
func makeIdentifier() -> some Equatable {
    42
}

let a = makeIdentifier()
let b = makeIdentifier()
// 輸出：true（同一個函式，底層型別一致，可以比較）
print(a == b)
```

兩個不同函式各自回傳的 `some Equatable`，是不同的底層型別，不能互相比較。Swift 5.7 起可以搭配 primary associated type 寫成 `some Sequence<Int>`、`any Collection<Int>`（見協定與泛型單元）。

> [!TIP]
> 面試時可以這樣說：「`some` 是由實作者決定單一的具體型別，編譯器知道真實型別，可以最佳化，SwiftUI 的 `body` 就是這樣；`any` 可以裝不同型別的值，但執行時才知道是誰，有額外成本。」

> [!FIELD]
> 在日常的 SwiftUI 開發中，天天都在用 property wrapper（`@State`、`@Published`、`@StateObject`）和 SwiftUI 的 result builder（`@ViewBuilder`），這些可以直接當作實例來講解原理。Swift Concurrency 和 SwiftUI 的經驗，也能自然帶到 macro 的話題：`@Observable` 就是 macro，但因為它依賴的 Observation 框架需要 iOS 17，如果專案最低版本是 iOS 16，就只能繼續用 `ObservableObject`。macro、`~Copyable`、typed throws 比較新，回答的角度是「我理解它解決什麼問題，也知道在交易所 App 可以用在哪裡」，例如用 `consuming` 表達『下單請求只能送出一次』，採用與否再看團隊的 Swift 版本與 API 穩定性取捨。

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift10LanguageFeatures**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift10-LanguageFeatures/`，解答在 `labs/ios/Solution/Swift/Swift10-LanguageFeatures/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：按「＋」之後，張數超過上限（第 1 題）

**檔案**：`L01_PropertyWrapper.swift`

**情境**：下單頁的「張數」必須落在 1 到 100 之間。這個數字有三條路會被改：使用者輸入、按「＋」、還原上次的設定。

**怎麼寫**

1. **Bug 版**：`QuantityInput` 用普通的 `var lots`，`type` 與 `restore` 都自己夾在 1...100 裡（已經寫好），`plus` 直接 `lots += n`，漏了夾。
2. **修正版**：把 `Clamped` property wrapper 寫完整：`init` 把初始值夾在範圍內、`wrappedValue` 的 `set` 每次寫入都夾住、`projectedValue` 回傳允許的範圍。`QuantityInput` 改成 `@Clamped(1...100) var lots = 1`，三個方法都只寫「直接指派或相加」。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 輸入 90 張後按「＋20」，張數變成 110，超過上限 |
| 修正版 | 按「＋20」之後張數是 100；還原成 -5 張得到 1；`$lots` 是 `1...100` |

**為什麼**：把「存取時的規則」寫在每個會改它的地方，只要漏一個就出事。property wrapper 把規則放在屬性本身，所有寫入的路徑（包含之後新增的）都會經過它。

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L01_PropertyWrapper.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L01_PropertyWrapper.swift#fixed
```

### 演練 2：備註含 &，伺服器讀成另一個參數（第 2 題）

**檔案**：`L02_ResultBuilder.swift`

**情境**：出金 API 的網址要帶 `symbol`、`memo`（使用者輸入的備註）、`depth` 三個參數。使用者的備註是 `ETH&BTC=1`。

**怎麼寫**

1. **Bug 版**：用字串拼接出 `https://api.example.com/withdraw?symbol=…&memo=…&depth=…`，`memo` 直接放進字串；`depth` 依 `precise` 選 `step0` 或 `step5`。
2. **修正版**：把 `QueryBuilder`（`@resultBuilder`）的五個方法寫完整：`buildExpression` 把一個 `URLQueryItem` 包成陣列、`buildBlock` 合併每一行、`buildOptional` 處理沒有 else 的 `if`、兩個 `buildEither` 處理 `if / else`。`url(_:_:)` 把結果指派給 `URLComponents` 的 `queryItems`，由它編碼。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 伺服器解析出 4 個參數（symbol、memo、BTC、depth），讀到的 memo 只有 `ETH` |
| 修正版 | 解析出 3 個參數（symbol、memo、depth），memo 是完整的 `ETH&BTC=1`（網址裡的 `&`、`=` 被編碼成 `%26`、`%3D`） |

**為什麼**：字串拼接要自己記得編碼，漏了就會被使用者輸入的 `&` 切開。result builder 讓你用「一行一個參數」的宣告式寫法，每個參數是結構化的 `URLQueryItem`，編碼交給 `URLComponents`；`if / else` 能直接寫在 closure 裡，是因為編譯器把它轉成 `buildEither` 的呼叫。

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L02_ResultBuilder.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L02_ResultBuilder.swift#fixed
```

### 演練 3：漲幅榜第一名，顯示成成交量最大的（第 4 題）

**檔案**：`L03_KeyPath.swift`

**情境**：行情頁有「成交量榜」「漲幅榜」「價格榜」三個榜單，每個榜單都是「找出某個欄位最大的那一檔」。

**怎麼寫**

1. **Bug 版**：三個榜單各寫一個函式。`topByVolume`、`topByPrice` 已經寫好；寫 `topByChange` 時，複製 `topByVolume` 的寫法，但「忘了」把欄位改成 `changePercent`，仍然比較 `volume`。
2. **修正版**：寫一個通用的 `top(_:by:)`，參數是 `KeyPath<Ticker, V>`（`V: Comparable`），用 `list.max` 配合 `[keyPath: key]` 取值比較；三個榜單分別傳 `\.volume`、`\.changePercent`、`\.price`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 漲幅榜第一名顯示 BTC（成交量最大的），應該是 XRP |
| 修正版 | 成交量榜 BTC、漲幅榜 XRP、價格榜 BTC |

**為什麼**：「哪個欄位」是變動的部分，其他邏輯都一樣。把欄位當成 KeyPath 參數傳進去，三個榜單共用同一份邏輯，不會再因為複製貼上漏改而出錯；新增榜單也只要多傳一個 KeyPath。

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L03_KeyPath.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L03_KeyPath.swift#fixed
```

### 演練 4：被限流時，使用者看到「未知錯誤」（第 5 題）

**檔案**：`L04_TypedThrows.swift`

**情境**：網路層的錯誤 `NetworkError` 一開始只有 `offline`、`badStatus`，後來有人新增了 `rateLimited(retryAfter:)`，但顯示錯誤訊息的地方沒有跟著改。

**怎麼寫**

1. **Bug 版**：`message(for:)` 用 `error as? NetworkError` 轉型後 `switch`，處理 `offline`、`badStatus`，其餘用 `default` 回傳「發生未知錯誤」。
2. **修正版**：`fetch` 的簽名改成 `throws(NetworkError)`；`catch` 裡的 `error` 就是 `NetworkError`，直接 `switch error`，三個 case 都處理（`rateLimited` 顯示「請 N 秒後再試」），不寫 `default`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 使用者看到「發生未知錯誤」：`rateLimited` 掉進 `default` |
| 修正版 | 使用者看到「請 30 秒後再試」 |

**為什麼**：一般的 `throws` 丟出來的是 `any Error`，呼叫端要自己轉型，而且永遠要用 `default` 處理「其他我不知道的」，新增的 case 就這樣被悄悄吞掉。typed throws 讓 `switch` 可以窮舉，之後 `NetworkError` 再新增 case 而這裡沒處理，編譯器會直接報錯：`switch must be exhaustive`。代價是：公開 API 寫死錯誤型別，之後新增 case 對呼叫端就是破壞性變更，所以預設還是用一般的 `throws`。

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L04_TypedThrows.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L04_TypedThrows.swift#fixed
```

### 演練 5：網路逾時重試，同一筆委託送了兩次（第 6 題）

**檔案**：`L05_NoncopyableToken.swift`

**情境**：下單前先簽出一個一次性的 `SignedOrderToken`，送出時用它。送出之後網路逾時，程式碼「重試」，又用了同一個 token。

**怎麼寫**

1. **Bug 版**：`SignedOrderToken` 是普通的 struct（已經寫好）。在 `run` 裡先 `token.submit()` 一次，印出送出的結果，再用同一個 token `submit()` 第二次。
2. **修正版**：把 `SignedOrderToken` 宣告成 `~Copyable`，`submit` 改成 `consuming`，再加一個 `deinit` 印出「token 7 已作廢（deinit）」。把 `run` 裡第二次 `token.submit()` 的註解打開，看編譯器的錯誤。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 委託 7 被送出了 2 次 |
| 修正版 | 委託 7 只送出 1 次；`submit` 結束時 `deinit` 印出「token 7 已作廢」；第二次 `submit` 無法編譯：`'token' consumed more than once` |

**為什麼**：普通的 struct 可以複製、重複使用，「只能用一次」只能靠旗標加執行時檢查，漏檢查就會重複送單。`~Copyable` 的型別不能複製，`consuming` 方法呼叫之後，原本的變數就不能再用，編譯器直接擋住第二次使用。要重試就得重新簽一個新的 token。

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L05_NoncopyableToken.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift10-LanguageFeatures/L05_NoncopyableToken.swift#fixed
```

第 3 題（macro）需要另外的 Swift package（swift-syntax）才能自己寫，這個 App 不加第三方套件，所以沒有演練；第 7 題（`some` 與 `any`）是比較少考的概念題，沒有可以執行的 bug，沒有演練。

## 常見陷阱

- **以為 typed throws 越多越好。** 公開 API 新增錯誤 case 會變成破壞性變更，預設仍用一般的 `throws`。
- **property wrapper 疊加時順序錯誤，或忘了寫 `init(wrappedValue:)`。** 順序影響結果；沒有 `init(wrappedValue:)`，就不能用 `= 預設值` 的語法。
- **result builder 沒實作 `buildEither` 或 `buildOptional`。** 這樣 builder 裡就不能寫 `if / else`、單獨的 `if`。
- **為了展示新功能而使用 macro。** 讓建置時間和除錯成本增加，卻沒有解決實際的重複樣板問題。
- **noncopyable 型別的參數沒標 `borrowing` 或 `consuming`。** 編譯器會報錯，必須明確標註。
- **把 KeyPath 當成萬用的欄位存取，放在每秒執行很多次的迴圈裡。** 動態取值比直接存取慢。
- **以為 macro 可以任意改寫既有的程式碼。** macro 只能新增程式碼。

## 面試官想聽到的

1. 能說明每個特性解決的問題，而不是背語法（例如 property wrapper 解決重複的存取邏輯、typed throws 解決錯誤型別資訊遺失）。
2. 知道版本：property wrapper（5.1）、result builder（5.4）、macro（5.9）、`~Copyable`（5.9，泛型支援 6.0）、typed throws（6.0），並能對應到專案部署目標的限制。
3. 有取捨意識：typed throws 的 API 穩定性、macro 的建置成本、result builder 的可讀性。
4. 能舉一個與金融相關的用法：輸入驗證、一次性的下單操作、窮舉錯誤處理。

> [!WARNING]
> 面試官常追問：「你們專案有用過嗎？」不要把學習與實驗說成專案經驗。可以誠實區分：日常使用的是 property wrapper 和 SwiftUI 的 result builder；新功能則說明理解其解決的問題，以及在交易所 App 會怎麼評估採用。

## iOS / Android 對照

| 主題 | iOS（Swift） | Android（Kotlin） |
|---|---|---|
| 把存取規則包起來 | property wrapper | `by` 委託屬性（`by lazy`、`by mutableStateOf`、自訂 `ReadWriteProperty`） |
| 宣告式 DSL | result builder | 帶接收者的 lambda（`buildList { }`、Compose 的 `@Composable` lambda）、`@DslMarker` |
| 編譯期產生程式碼 | macro（swift-syntax） | KSP（Kotlin Symbol Processing）／kapt（Kotlin 的註解處理工具）、編譯器外掛（Compose compiler） |
| 指向屬性的值 | KeyPath | 屬性參照 `Foo::bar`（`KProperty1`） |
| 錯誤型別 | typed throws（6.0） | 例外不在簽名中；用 `Result<T>` 或 sealed class 表達錯誤 |
| 唯一所有權 | `~Copyable`、`consuming` | 沒有對應；JVM 的 GC 不追蹤所有權，用 `Closeable` 和 `use` 管理資源 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 屬性包裝器 | property wrapper | プロパティラッパー |
| 巨集 | macro | マクロ |
| 在簽名中指定錯誤型別 | typed throws | 型付き throws |
| 不能被複製的型別 | noncopyable type（`~Copyable`） | コピー不可型 |
| 不透明回傳型別 | opaque result type（`some`） | 不透明な戻り値型 |
| 指向屬性的值 | KeyPath | キーパス |
| 所有權 | ownership | 所有権 |
