---
id: swift-protocols-generics
title: 協定與泛型：some 和 any 差在哪、protocol extension 的方法會呼叫到哪一個
summary: 面試常問「some 和 any 差在哪、各有什麼成本」，以及「protocol extension 裡的方法到底會執行哪一個版本」。這個單元用同一條主線把它們串起來：編譯器知不知道具體型別。
priority: P1
day: 1
tags: [protocol, generics, some, any, dispatch, type-erasure]
---

## 重點講解

這個單元回答五個面試常見問題：

1. `some`、`any` 和泛型差在哪？什麼時候用哪個？
2. Swift 5.7 之後，`some` 和 `any` 多了哪些用法？
3. protocol extension 裡的方法，到底會呼叫到哪一個版本？（派發方式）
4. 泛型什麼時候會變慢？（比較少考，被問到能答就好）
5. type erasure 是什麼？為什麼標準函式庫到處都是 `AnyXxx`？（比較少考，被問到能答就好）

### 1. `some`、`any` 和泛型差在哪？什麼時候用哪個？

三者的差別，歸結起來就是：**編譯器在編譯的時候，知不知道這個值實際上是哪一個具體型別。**

- **泛型（`<T: Shape>`）和 `some Shape`**：編譯器知道具體型別。`some Shape` 的意思是「我回傳某一個遵守 `Shape` 的型別，但不告訴呼叫端是哪一個」；呼叫端雖然不知道，編譯器自己是知道的，所以可以直接呼叫那個型別的方法，也有機會做最佳化。代價是：一個函式只能固定回傳同一種具體型別。
- **`any Shape`**：編譯器不知道具體型別，只知道「它遵守 `Shape`」，實際是哪一個要到程式執行時才知道。所以呼叫方法時，要先查表才知道該執行哪個型別的版本（這個查表動作比直接呼叫慢一點）。換來的好處是：同一個變數、同一個陣列，可以放不同的具體型別。

```swift
protocol Shape { func area() -> Double }
struct Circle: Shape { var r: Double; func area() -> Double { .pi * r * r } }
struct Square: Shape { var side: Double; func area() -> Double { side * side } }

// some：固定回傳 Circle，只是對呼叫端隱藏。編譯器知道它是 Circle
func makeDefaultShape() -> some Shape { Circle(r: 1) }

// 參數的 some：每個參數各自是「某一個」Shape，兩個參數可以是不同型別
func totalArea(_ a: some Shape, _ b: some Shape) -> Double { a.area() + b.area() }

// any：陣列裡可以放 Circle，也可以放 Square
let shapes: [any Shape] = [Circle(r: 1), Square(side: 2)]
let total = shapes.reduce(0) { $0 + $1.area() }   // 每個元素呼叫 area() 時，才查表決定執行哪個版本

print(makeDefaultShape().area())                  // 輸出：3.141592653589793
print(totalArea(Circle(r: 1), Square(side: 2)))   // 輸出：7.141592653589793
print(total)                                      // 輸出：7.141592653589793
```

為什麼 `[any Shape]` 可以放不同型別，而用泛型或 `some` 就不行？因為陣列的元素型別在編譯時必須固定。泛型版本會寫成 `[Circle]` 或 `[Square]`，只能擇一；`[any Shape]` 把元素型別固定為「遵守 `Shape` 的任何東西」，所以兩種都能放。

**`some` 常見的誤解**：它不是「任何型別都行」。下面這段會編譯失敗，因為兩個分支回傳了不同的具體型別：

```swift
func makeShape(round: Bool) -> some Shape {
    if round { return Circle(r: 1) } else { return Square(side: 2) }
    // 編譯錯誤：return statements in its body do not have matching underlying types
}
```

兩種修法：真的需要不同型別就改成回傳 `any Shape`；或者把差異放進同一個型別裡（例如一個 enum，或帶欄位的 struct）。

**那該怎麼選？** 我的順序是：

1. 參數：用 `some` 或泛型。
2. 回傳值：能固定型別就用 `some`（SwiftUI 的 `var body: some View` 就是這個用法）。
3. 需要在同一個集合裡放不同型別，或想把實作完全藏起來時，才用 `any`。

在交易所 App 裡，一個很典型的 `any` 用法是「下單前的檢查規則」：`[any OrderRule]`，裡面有「餘額夠不夠」「數量有沒有低於最小單位」「價格有沒有符合 tick size（價格最小跳動單位）」等不同型別的規則，一個一個跑。這種地方一個陣列就要裝不同型別，用 `any` 最自然，而且規則才幾條，查表的成本完全不用在意。

> [!TIP]
> 面試時可以這樣說：「`some` 是編譯器知道具體型別、只是不告訴呼叫端，所以可以直接呼叫並做最佳化；`any` 是執行時才知道具體型別，呼叫方法要先查表，換來可以在同一個陣列放不同型別。所以不是單純的『some 比較快』，而是看我需不需要放不同型別。」

### 2. Swift 5.7 之後，`some` 和 `any` 多了哪些用法？

Swift 5.7（2022 年，Xcode 14）把 `some` 和 `any` 的限制一次放寬了不少。面試時提到這些，表示你有跟上語言的演進。

先補一個前置概念：**associated type** 是協定裡「等實作的人再填」的型別。例如 `Collection` 裡的 `Element`：`Array<Int>` 遵守它時，`Element` 就是 `Int`。有 associated type 的協定，簡稱 PAT（protocol with associated types）。以前 PAT 不能直接當變數型別用，所以很多人只好寫泛型或 type erasure。

Swift 5.7 之後：

1. **PAT 可以直接寫成 `any`**：`let c: any Collection = [1, 2, 3]`，以前這行會編譯錯誤。
2. **`some` 可以寫在參數位置**：`func average(_ xs: some Sequence<Double>)` 就是泛型的簡寫，等同 `func average<S: Sequence>(_ xs: S) where S.Element == Double`。
3. **primary associated type**：協定宣告時挑出來、可以用角括號指定的那個 associated type。例如 `Sequence<Double>`、`any Collection<Int>`，角括號裡填的就是 `Element`。指定了之後，編譯器才知道元素是什麼型別。

```swift
let a: any Collection = [1, 2, 3]
let b: any Collection<Int> = [1, 2, 3]

print(type(of: a.map { $0 }))   // 輸出：Array<Any>：沒有指定 Element，編譯器只好當成 Any
print(type(of: b.map { $0 }))   // 輸出：Array<Int>：指定了 Collection<Int>，元素型別保留下來
```

兩個還是會碰到的限制：

- **`any Hashable` 不會自動遵守 `Hashable`**。所以 `Set<any Hashable>` 無法編譯，錯誤訊息是 `type 'any Hashable' cannot conform to 'Hashable'`。標準函式庫另外提供 `AnyHashable`，就是為了解決這個問題。同樣的道理，把兩個 `any Equatable` 傳給 `func f<T: Equatable>(_ a: T, _ b: T)` 也不行，因為編譯器無法證明這兩個值是同一個型別。
- **`any` 這個關鍵字不是必寫的。** Swift 5.6 起可以寫 `any Shape`，不寫（直接寫 `Shape`）目前仍然能編譯。想強制全專案都要寫 `any`，要自己開啟 `ExistentialAny` 這個尚未預設啟用的新功能（upcoming feature）。

> [!TIP]
> 面試時可以這樣說：「Swift 5.7 之後，有 associated type 的協定可以直接寫成 `any Collection<Int>`，`some` 也可以寫在參數上當泛型的簡寫。我現在寫參數都用 `some Sequence<Double>` 這種寫法，比較好讀。」

### 3. protocol extension 裡的方法，到底會呼叫到哪一個版本？

這是 `any` 相關題目裡**最常被考的坑**。先講一個詞：**派發（dispatch）**，意思是「呼叫方法時，決定要執行哪一份程式碼的方式」。分成兩種：

- **靜態派發**：編譯的時候就決定好了，直接呼叫，最快。
- **動態派發**：程式執行時才決定，要先查表，稍慢，但有彈性（子類別、不同的 conformer 可以有自己的版本）。

先看一個會讓人意外的例子：

```swift
protocol Greeter { func hello() }          // hello 是協定的 requirement（要求）
extension Greeter {
    func hello() { print("default hello") }  // hello 的預設實作
    func bye() { print("default bye") }      // bye 不在協定宣告裡，只存在於 extension
}

struct English: Greeter {
    func hello() { print("hello") }
    func bye() { print("bye") }
}

let e = English()
let g: any Greeter = e
e.hello()   // 輸出：hello
e.bye()     // 輸出：bye
g.hello()   // 輸出：hello：hello 是 requirement，執行時查表，找到 English 自己的版本
g.bye()     // 輸出：default bye：bye 不是 requirement，編譯器只看到 any Greeter，直接呼叫 extension 的版本
```

規則是這樣的：**只有寫在協定宣告裡的方法（requirement，要求遵守者一定要提供的成員）才是動態派發。** 只寫在 extension 裡的方法，編譯器依「變數宣告的型別」直接決定，不會去找 conformer（遵守這個協定的型別，例如上面的 `English`）自己的同名方法。所以 `g.bye()` 印出 `default bye`，`English` 自己寫的 `bye` 被忽略了。

實務上的情境：你在 `extension FeeCalculator` 裡寫了一個 `func displayFee()`，某個 conformer 又自己寫了一個同名的。透過 `any FeeCalculator` 呼叫時，跑的是 extension 版本，行為和你預期的不同，而且編譯器不會有任何警告。修法很簡單：想讓它能被覆寫，就把方法也宣告進協定裡，變成 requirement。

下面是各種呼叫的派發方式，可以當成速記：

| 呼叫的是哪一種方法 | 怎麼決定要執行哪份程式碼 |
|---|---|
| struct／enum 的方法、`final class` 的方法、`private`、`static` | 靜態：編譯時就決定，直接呼叫。編譯器還可以做 inlining（把函式內容直接貼到呼叫的地方，省掉一次呼叫） |
| class 的一般方法 | 動態：因為子類別可能覆寫，預設要查表。不過開啟 WMO（whole module optimization，讓編譯器一次看完整個 module 再最佳化）後，編譯器若確定整個 module 都沒人覆寫，會自己改成直接呼叫。這是最佳化，不是語言保證 |
| 經由 `any P`（或沒有做 specialization 的泛型）呼叫協定的 requirement | 動態：執行時查表 |
| protocol extension 裡**不是 requirement** 的方法 | 靜態：依變數宣告的型別決定，不會被 conformer 的同名方法取代 |
| `@objc dynamic`、KVO（Key-Value Observing，監聽屬性變化） | 動態：走 Objective-C runtime 的訊息傳遞，執行時才決定，因此可以被 KVO、swizzle（執行時把方法換成另一個實作）改寫 |

> [!TIP]
> 面試時可以這樣說：「只有協定宣告裡的 requirement 才會動態派發，只寫在 extension 裡的方法是編譯時就決定的，不會被 conformer 的同名方法取代。所以我如果希望某個方法能被覆寫，就會把它宣告成協定的 requirement。」

### 4. 泛型什麼時候會變慢？（比較少考，被問到能答就好）

泛型本身不慢，要看**編譯器能不能做 specialization**。意思是：編譯器看到你用 `Int` 呼叫泛型函式，就額外產生一份專門給 `Int` 用的版本，彷彿你手寫了 `Int` 版、`Double` 版一樣，然後還可以 inlining。這時泛型的額外成本趨近於零。

什麼時候做不到 specialization？編譯器**看不到函式內容**的時候。最典型的是跨 module（例如泛型函式寫在另一個 framework 或 SPM（Swift Package Manager）的套件裡）：編譯器只有宣告，沒有函式本體，只能呼叫通用版本，通用版本每次都要多帶型別資訊，還要靠查表才能操作值，在每秒執行很多次的地方（例如行情解析、排序）會明顯變慢。要讓跨 module 的泛型也能 specialize，函式要標 `@inlinable`（把實作公開給呼叫端的編譯器看），代價是這份實作從此變成對外的契約，改動要很小心。所以最單純的做法，是把熱點（每秒跑很多次的程式碼）留在同一個 module 裡。

另一個要記得的設計原則：**I/O 邊界（Repository、API client）用 `any` 完全沒問題。** 查表的成本遠小於一次網路請求或資料庫讀取，而 `any` 帶來的可測試性（測試時換成假的實作）更值錢。行情解析、訂單簿的 diff 這種熱點，才需要考慮泛型或具體型別。判斷要用 Instruments 量，不要憑感覺。

> [!TIP]
> 面試時可以這樣說：「泛型在編譯器能 specialize 的時候，成本接近零；跨 module 又沒標 `@inlinable` 的熱點才會變慢。我在 Repository 這種 I/O 邊界放心用 `any`，因為查表的成本比網路小得多；行情解析那種熱點我會先用 Instruments 量，再決定要不要改。」

### 5. type erasure 是什麼？（比較少考，被問到能答就好）

**type erasure** 是把不同的具體型別，包成同一種型別的做法；包起來之後，外面就看不到裡面是哪個具體型別了。標準函式庫與 SwiftUI 到處都是：`AnyPublisher`（Combine）、`AnyHashable`、`AnyView`（SwiftUI）。

用法是：寫一個 `AnyXxx<T>` 的包裝型別，內部用 closure 把呼叫轉發給原本的物件。

```swift
protocol PriceFeed {
    associatedtype Price
    func latest() -> Price?
}

struct AnyPriceFeed<Price>: PriceFeed {
    private let fetchLatest: () -> Price?

    init<F: PriceFeed>(_ feed: F) where F.Price == Price {
        fetchLatest = { feed.latest() }   // 用 closure 把呼叫轉發給原本的 feed
    }
    func latest() -> Price? { fetchLatest() }
}
// 現在 [AnyPriceFeed<Decimal>] 可以放任何 Price 是 Decimal 的來源，不管它們實際是哪個型別
```

Swift 5.7 有了 `any PriceFeed<Decimal>` 之後，很多場合不必再手寫這種包裝。手寫的版本仍然有用的情況是：需要讓包起來的型別符合 `Hashable`／`Equatable`、要放進泛型型別當成一個具體型別，或部署目標比較舊。

代價是多一層間接呼叫，也會丟失編譯時的型別資訊。`AnyView` 還有一個額外的問題：SwiftUI 靠 view 的型別結構辨認「這個畫面是不是同一個」，包成 `AnyView` 後辨認不出來，效能與動畫會受影響（見 SwiftUI 效能單元）。

> [!TIP]
> 面試時可以這樣說：「type erasure 是把不同型別包成同一種，像 `AnyPublisher`。Swift 5.7 之後很多場合可以直接用 `any`，但需要 `Hashable` 或要放進泛型型別時，我還是會手寫 `AnyXxx`。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift03Generics**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift03-Generics/`，解答在 `labs/ios/Solution/Swift/Swift03-Generics/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **編譯錯誤**：有些寫法（例如 `some` 回傳兩種型別）本身就是編譯錯誤，沒辦法放進會執行的程式。這些寫法都放在解答的註解裡，想看錯誤訊息就自己打開來試。

### 演練 1：價格不符 tick size 的委託居然通過（第 1 題）

**檔案**：`L01_OrderRules.swift`

**情境**：下單前要檢查三條規則：餘額夠不夠、數量有沒有低於最小單位、價格是不是 1000 円的倍數（tick size，價格最小跳動單位）。三條規則是不同的型別，要放在同一個陣列裡一條一條跑。

**怎麼寫**

1. **Bug 版**：`validate` 的規則陣列是 `[Any]`，用 `for` 迴圈加 `as?` 轉型判斷 `BalanceRule` 和 `MinQuantityRule`，先不處理 `TickSizeRule`（模擬「後來新增的規則忘了加進判斷」）。
2. **修正版**：`OrderRule` 協定宣告 `check(_:)`，`validate` 走過 `[any OrderRule]`，第一條不通過的規則就回傳它的原因。三條規則的 `check` 已經寫好。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 委託 B（價格 15000123）顯示「通過」，應該被擋下來卻沒有；沒有編譯錯誤，也沒有警告 |
| 修正版 | 委託 B：價格要是 1000 円的倍數；委託 C：數量低於最小單位 |

**為什麼**：`[Any]` 加 `as?` 靠人記得每一種型別都要處理，漏掉一種，編譯器不會提醒。`[any OrderRule]` 讓同一個陣列放不同型別，每條規則自己檢查自己，新增規則只要遵守協定。為什麼不用 `some`？`some OrderRule` 只代表某一個具體型別，一個函式的兩個分支不能回傳不同的規則（編譯錯誤：`return statements in its body do not have matching underlying types`），陣列元素的型別也只能有一種。

```lab-solution
ios/Solution/Swift/Swift03-Generics/L01_OrderRules.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift03-Generics/L01_OrderRules.swift#fixed
```

### 演練 2：成交金額合計居然是 0（第 2 題）

**檔案**：`L02_PrimaryAssociatedType.swift`

**情境**：資產畫面要把一批成交金額（円，整數）加總。函式想接受任何集合（Array、Set、ArraySlice…）。

**怎麼寫**

1. **Bug 版**：`totalValue` 的參數型別寫 `any Collection`（不指定元素型別），回傳 `Double`，用 `reduce` 加總，每個元素用 `as? Double` 轉型，轉不過就當 0。
2. **修正版**：參數型別寫 `some Collection<Int>`（用角括號指定元素型別），回傳 `Int`，直接用 `reduce(0, +)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `any Collection` 的 `map` 結果型別是 `Array<Any>`；成交金額合計：0.0 |
| 修正版 | `any Collection<Int>` 的 `map` 結果型別是 `Array<Int>`；成交金額合計：4501500 |

**為什麼**：`any Collection` 沒有指定 primary associated type（`Collection<Int>` 角括號裡的 `Element`），編譯器只知道元素是 `Any`。`Int` 用 `as? Double` 轉不過去（`Int` 不是 `Double`），每個元素都被當成 0，沒有任何錯誤。指定 `Collection<Int>` 之後，元素型別保留下來，連轉型都不需要。

```lab-solution
ios/Solution/Swift/Swift03-Generics/L02_PrimaryAssociatedType.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift03-Generics/L02_PrimaryAssociatedType.swift#fixed
```

### 演練 3：VIP 的說明文字被預設文字蓋掉（第 3 題）

**檔案**：`L03_ExtensionDispatch.swift`

**情境**：手續費計算器遵守 `FeeCalculator` 協定，extension 提供預設的 `summary` 說明文字。VIP 計算器自己寫了一個同名的 `summary`，顯示「VIP 免手續費」。畫面上用 `[any FeeCalculator]` 放不同的計算器，一個一個顯示說明。

**怎麼寫**

1. **Bug 版**：在 extension 裡寫預設的 `summary(for:)`，回傳「手續費 N 円」。**不要**把 `summary` 宣告進協定。
2. **修正版**：把 `summary(for:)` 也宣告進 `FeeCalculator` 協定，讓它變成 requirement。預設實作已經寫好。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 直接用 `VIPFee` 呼叫是「VIP 免手續費」，透過 `any FeeCalculator` 呼叫 `VIPFee` 卻是「手續費 0 円」 |
| 修正版 | 兩種呼叫 `VIPFee` 都是「VIP 免手續費」，`MakerFee` 是「手續費 1000 円」 |

**為什麼**：只有寫在協定宣告裡的方法（requirement）才是動態派發，執行時才查表找到遵守者自己的版本。只寫在 extension 裡的方法，編譯器依變數宣告的型別在編譯時直接決定：透過 `any FeeCalculator` 呼叫，就直接呼叫 extension 的版本，`VIPFee` 自己的同名方法被忽略，而且沒有任何警告。

```lab-solution
ios/Solution/Swift/Swift03-Generics/L03_ExtensionDispatch.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift03-Generics/L03_ExtensionDispatch.swift#fixed
```

### 演練 4：100 萬筆報價，每筆多配置一塊記憶體（第 4 題）

**檔案**：`L04_ExistentialBox.swift`

**情境**：行情推播解析出 100 萬筆報價（`Quote` 有五個 `Double` 欄位，共 40 bytes），要算每一筆的中間價並加總。

**怎麼寫**

1. **Bug 版**：`totalMid` 的參數改成 `[any Priced]`；`run` 裡把 `makeQuotes` 的結果標註成 `[any Priced]`。
2. **修正版**：`totalMid` 寫成泛型函式 `<Q: Priced>`，參數是 `[Q]`，加總所有 `mid`。陣列維持具體型別 `[Quote]`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 合計中間價相同；每筆報價平均多配置 1.00 個 heap 區塊（100 萬個） |
| 修正版 | 合計中間價相同；每筆報價平均多配置 0.00 個 heap 區塊 |

**為什麼**：`any` 的容器只有三個字組（24 bytes）的空間放值，`Quote` 放不下，每一筆都要另外在 heap 配置一塊記憶體；讀取 `mid` 時還要先查表才知道該呼叫誰的版本，編譯器也沒辦法 inline。具體型別的陣列把報價直接放在陣列裡，泛型函式在編譯器看得到型別時可以 specialization（為 `Quote` 產生專用版本）。

**時間差距和建置設定有關**，所以這個演練用 heap 配置次數判斷。另外用 `swiftc -O` 在 Mac 上單獨量，5 輪各 100 萬筆，`[any Priced]` 約 19 ms、泛型加具體型別約 5 ms（約 4 倍）；Debug 建置沒有最佳化，量出來反而是 `[any Priced]` 比較快。要比時間，請用 Release 建置，並且記得：I/O 邊界（Repository、API client）用 `any` 沒問題，熱點才需要避開。

```lab-solution
ios/Solution/Swift/Swift03-Generics/L04_ExistentialBox.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift03-Generics/L04_ExistentialBox.swift#fixed
```

### 演練 5：選了訂單 1001，成交 1001 也被打勾（第 5 題）

**檔案**：`L05_TypeErasure.swift`

**情境**：交易紀錄畫面同時列出訂單（`OrderID`）和成交（`TradeID`）。兩種編號都是 `Int` 包起來的 struct，數字可能一樣。使用者可以勾選要匯出的紀錄，勾選狀態存在同一個 `Set` 裡。

**怎麼寫**

1. **Bug 版**：用 `Set<Int>` 存勾選狀態，只放編號的數字（`raw`）；勾選訂單 1001，再分別查訂單 1001 和成交 1001 有沒有被勾選，印出「訂單 1001：已勾選」這類 log。
2. **修正版**：用 `Set<AnyHashable>`（標準函式庫的 type erasure 型別），直接放 `OrderID`，再用 `OrderID`、`TradeID` 查有沒有被勾選。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 訂單 1001：已勾選；成交 1001：已勾選（不該勾選） |
| 修正版 | 訂單 1001：已勾選；成交 1001：未勾選 |

**為什麼**：`Set<Int>` 只存了編號的數字，`OrderID` 和 `TradeID` 的型別資訊丟了，數字一樣就被當成同一個。`AnyHashable` 把不同型別的 `Hashable` 包成同一種，比較時型別和值都要一樣。`Set<any Hashable>` 無法編譯（`type 'any Hashable' cannot conform to 'Hashable'`），所以標準函式庫另外提供了 `AnyHashable`。

```lab-solution
ios/Solution/Swift/Swift03-Generics/L05_TypeErasure.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift03-Generics/L05_TypeErasure.swift#fixed
```

## 常見陷阱

- **以為 protocol extension 裡的方法可以被 conformer 覆寫。** 只有寫在協定宣告裡的 requirement 才是動態派發；只在 extension 裡的方法，是依變數的宣告型別在編譯時決定的。想讓它能被覆寫，就加進協定宣告。
- **把 `some` 當成「任何型別都可以」。** `func make() -> some P` 裡面不能一個分支回傳 A、另一個分支回傳 B，會編譯失敗。真的需要不同型別就回傳 `any P`。
- **把 `[any P]` 用在每秒執行很多次的程式碼。** 每個元素呼叫方法都要查表、不能 inline。這種地方改用泛型或具體型別，先量再改。
- **使用有 associated type 的協定，卻沒有指定 primary associated type。** 例如 `any Collection` 的 `map` 會回傳 `[Any]`，要寫 `any Collection<Int>` 才會保留 `Int`。
- **為了抽象而過度泛型化。** 每多一個泛型參數，編譯時間和錯誤訊息的複雜度就增加。先用具體型別，真的需要抽象再改。
- **跨 module 的泛型熱點，沒有標 `@inlinable` 就以為 release 會自動 specialize。** 編譯器看不到函式內容，就沒辦法 specialize。

## 面試官想聽到的

1. 用「編譯器知不知道具體型別」解釋 `some` 和 `any` 的差別，而不是只說「some 比較快」。
2. 清楚 protocol extension 的派發規則：只有 requirement 是動態派發，extension 裡多出來的方法是靜態的。
3. 知道 `any` 呼叫方法要先查表、不能 inline，並且能說出什麼情況可以接受這個成本（例如 I/O 邊界）。
4. 知道 specialization 需要編譯器看得到函式內容，跨 module 時要靠 `@inlinable` 或把熱點放在同一個 module。
5. 能講出 Swift 5.7 的變化（`some` 可以寫在參數、`any Collection<Int>`），表示有跟上語言演進。

> [!FIELD]
> 在一個社交 App 的 iOS 版裡，Repository 都放在協定後面，ViewModel 用預設引數注入，例如 `meRepository: any MeRepositoryProtocol = MeRepository.shared`，測試時傳入假的實作。另外有一個 `enum` 放著各個協定型別的預設值，UI 測試時可以整組換成 stub（測試用的假實作）。這些地方都在 I/O 邊界，`any` 查表的成本相對網路與資料庫可以忽略，換來的是可測試性。
> 面試時可以這樣說：「我在邊界用 `any` 換取可測試性。到交易所 App 的行情解析與訂單簿這類熱點，我會先用 Instruments 量，再決定要不要改用泛型或具體型別。」

## iOS / Android 對照

| 主題 | iOS（Swift） | Android（Kotlin／JVM） |
|---|---|---|
| 泛型的實作方式 | 編譯器可以為每個具體型別產生專用版本（specialization）；做不到時，執行時多傳型別資訊 | 執行時看不到泛型參數（`List<Int>` 和 `List<String>` 是同一個類別，JVM 稱為 type erasure）。`List<Int>` 裡的數字會包成 `Integer` 物件；`inline` 加 `reified` 才能在函式內拿到型別 |
| 呼叫方法時決定版本的方式 | 靜態派發或查表（動態派發） | 以虛擬方法呼叫為主；JIT（just-in-time，執行時才編譯）或 ART（Android 的執行環境）在執行時可能自動最佳化成直接呼叫 |
| 同一個集合放不同型別 | `[any P]`、enum | `List<Interface>`、`sealed interface` 搭配窮舉的 `when` |
| 處理泛型的型別差異 | `AnyXxx<T>` 包裝、`any P<T>` | 在宣告處用 `out`／`in` 標記（variance，型別的協變與逆變） |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 用 `some` 寫的型別（呼叫端看不到具體型別） | opaque type | 不透明型（`some`） |
| 用 `any` 寫的型別（可放不同具體型別） | existential type | 存在型（`any`） |
| 協定裡等實作者填的型別 | associated type | 関連型 |
| 決定要執行哪份程式碼的方式 | dispatch（static／dynamic） | ディスパッチ（静的／動的） |
| 為具體型別產生專用版本 | specialization | 特殊化 |
| 把不同型別包成同一種型別 | type erasure | 型消去 |
| 把函式內容貼進呼叫處 | inlining | インライン展開 |
