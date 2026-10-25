---
id: swift-swiftui-identity-performance
title: SwiftUI 的 identity 與效能：為什麼狀態會被重置、畫面為什麼一直重算
summary: SwiftUI 效能題幾乎都在問兩件事：SwiftUI 怎麼認定「這是同一個 view」（identity），以及什麼情況會讓 body 一直重新計算。這個單元用畫面上看得到的現象解釋，最後說明行情列表每秒更新很多次時怎麼避免卡頓。
priority: P0
day: 1
tags: [swiftui, identity, performance, equatable, lazy, task, instruments]
---

## 重點講解

這個單元回答七個面試常見問題：

1. SwiftUI 怎麼判斷「這是不是同一個 view」？為什麼切換畫面後狀態會被重置？
2. `.id()` 和 `ForEach` 的 id 要怎麼用才對？
3. body 為什麼會被重新計算？重新計算就等於重新畫畫面嗎？
4. 怎麼讓沒有變的子 view 被跳過，不要白做工？
5. `AnyView` 和 Lazy 容器（`LazyVStack` 等）該怎麼用？
6. `.task` 什麼時候開始、什麼時候取消？
7. 行情列表每秒更新很多次，怎麼避免整頁卡頓？怎麼確認真的有改善？

### 1. SwiftUI 怎麼判斷「這是不是同一個 view」？為什麼切換畫面後狀態會被重置？

SwiftUI 用 **identity（身分）** 判斷「更新前後的這個 view，是不是同一個」。是同一個，就保留它的狀態（`@State`、`@StateObject`、進行中的 `.task`、捲動位置）；不是同一個，就把舊的整個丟掉、建一個新的，狀態從頭開始。identity 有兩種來源：

- **structural identity（結構上的身分）**：由 view 在畫面結構中的**位置與型別**決定。位置相同、型別相同，只改參數，就是同一個 identity，狀態保留。`if / else` 的兩個分支，即使寫的是同一個型別，也是**兩個不同的 identity**。
- **explicit identity（明確指定的身分）**：你自己指定的，包括 `.id(_:)`，以及 `ForEach` 裡每筆資料的 id。

畫面上看得到的現象是這樣：下單頁有一個「市價／限價」的切換，切換時，輸入框裡打到一半的數量突然消失了，載入中的狀態也重來一次。很多時候是因為用了 `if / else` 切換兩個外觀，SwiftUI 認為這是兩個不同的 view，切換時舊的銷毀、新的建立，狀態當然跟著重置。

```swift
import SwiftUI

final class CounterModel: ObservableObject {
    @Published var n = 0
    init() { print("建立 CounterModel") }
}

struct Counter: View {
    @StateObject private var model = CounterModel()
    var body: some View { Text("\(model.n)") }
}

struct Demo: View {
    let flag: Bool
    var body: some View {
        VStack {
            if flag { Counter() } else { Counter() }   // 兩個分支＝兩個不同的 identity：每次切換都重置
            Counter().opacity(flag ? 1 : 0.4)          // 同位置、同型別，只改參數＝同一個 identity：狀態保留
            Counter().id(flag)                         // 明確指定 id：flag 一變就是另一個 identity，重置
        }
    }
}
```

我們把類似的程式放進實際的 SwiftUI 視窗，把 `flag` 切換 2 次：`if / else` 那一種的 `CounterModel` 被建立 **3 次**（第一次加上每次切換各 1 次）；`.id(flag)` 那一種也是 **3 次**；只改參數的那一種只有 **1 次**。

**想讓畫面「顯示／隱藏」但保留狀態，不要用 `if`，改用 modifier**，例如 `.opacity(flag ? 1 : 0)`，需要時再搭配 `.allowsHitTesting(flag)` 讓隱藏時不能點。（`.hidden()` 沒有開關參數，要切換就得包 `if`，又會改變 identity。）反過來，如果你就是想要換了就重來，用 `if / else` 或 `.id` 就是正確的。

> [!TIP]
> 面試時可以這樣說：「SwiftUI 用 identity 判斷是不是同一個 view。structural identity 看位置和型別，`if / else` 的兩個分支就算型別一樣也是不同的 identity；explicit identity 是 `.id` 和 `ForEach` 的 id。`@State`、`@StateObject`、進行中的 `.task` 的壽命都等於 identity 的壽命，identity 一變就是銷毀重建，所以狀態會被重置。」

### 2. `.id()` 和 `ForEach` 的 id 要怎麼用才對？

`.id(_:)` 是**明確告訴 SwiftUI：這個值不一樣，就當作是另一個 view**。值一變，整棵子樹銷毀再重建，狀態、捲動位置、進行中的 `.task`、動畫全部重來；轉場動畫也會變成「舊的移除、新的插入」。

所以 `.id()` 的合理用法是「我刻意要重置」，例如使用者切換帳號後，整個表單要全部清空重來，這時 `.id(account.id)` 就剛好。最糟的寫法是把 `.id(UUID())` 寫在 `body` 裡：`body` 每次計算都會產生新的 UUID，等於每次都換 identity，這個 view 每次都被整個重建。

`ForEach` 的 id 同樣影響 identity，**規則是穩定且唯一**：

- 不要用 index 當 id。
- 不要每次產生新的 `UUID()`。
- 最好讓資料型別遵守 `Identifiable`，用資料本身的固定欄位（例如委託單編號、幣種代號）。

```swift
struct OrderRow: View {
    let order: Order
    @State private var note = ""          // 使用者在這一列打的備註
    var body: some View { TextField("備註", text: $note) }
}

// 錯誤：用 index 當 id
ForEach(orders.indices, id: \.self) { i in OrderRow(order: orders[i]) }

// 正確：Order 遵守 Identifiable，id 是委託單編號
ForEach(orders) { order in OrderRow(order: order) }
```

為什麼用 index 會出事？假設使用者在第 0 列打了備註，接著把第 0 筆委託單刪掉。用 index 當 id 時，SwiftUI 看到的是「index 0 的那一列還在，只是資料換了」，所以保留 index 0 的 `note` 狀態，結果上一筆委託單的備註，出現在原本第 1 筆委託單的那一列。用委託單編號當 id，SwiftUI 就知道被刪掉的是哪一列，狀態會跟著正確的列走。

另外，`ForEach(names, id: \.self)` 如果 `names` 裡有重複的字串，id 就不唯一，會出現警告與錯亂的更新。

> [!TIP]
> 面試時可以這樣說：「`.id()` 是刻意讓 SwiftUI 把它當成另一個 view，值一變整棵子樹就銷毀重建，所以只有『想重置』時才用，絕對不會在 body 裡寫 `.id(UUID())`。`ForEach` 的 id 要穩定且唯一，我用資料本身的固定欄位，不用 index，不然刪除或插入之後，狀態會對到錯的列。」

### 3. body 為什麼會被重新計算？重新計算就等於重新畫畫面嗎？

不等於。流程是這樣：某個狀態改變，SwiftUI 把依賴這個狀態的 view 標記為失效（invalidate，意思是「資料變了，需要重新計算」），重新執行這個 view 的 `body`，把新的結果和上一次的結果比對，**只更新真正有差異的部分**。`body` 只是「描述畫面長什麼樣子」的運算，算得再多次，畫面上沒變的地方也不會被重畫。所以 `body` 要快，而且不要在裡面產生副作用。

但是 `body` 算太多次、太慢，一樣會卡。關鍵問題是：**父層重新計算時，子 view 的 `body` 會不會也跟著算？** 取決於 SwiftUI 能不能判斷「這個子 view 的欄位跟上次一樣」。能判斷沒變，就跳過；判斷不出來，就當作變了，重新計算。

我們在 Xcode 26.3 上實測：父層更新 3 次（`body` 共計算 4 次，包含第一次），看子 view 的 `body` 被計算幾次。

| 子 view 的欄位 | 子 view 的 `body` 計算次數 | 意思 |
|---|---|---|
| 值型別欄位（例如 `Int`、`String`），沒有改變 | 1 | 判斷得出沒變，後面都跳過 |
| 傳了一個 closure，裡面沒有捕獲任何會變動的東西 | 1 | 一樣被跳過 |
| 傳了一個 closure，而且它捕獲了會變動的變數 | 4 | closure 沒辦法比較，每次都當作有變 |
| 每次都 `new` 一個新的 class 物件傳給子 view | 4 | 每次都是不同的物件，每次都有變 |
| 同上面的 closure，但子 view 遵守 `Equatable`，自己定義 `==`（忽略 closure）並用 `.equatable()` | 2 | 明顯減少 |

**除錯小技巧：** 在 `body` 的開頭寫 `let _ = Self._printChanges()`，Xcode 的主控台會印出這次 `body` 被計算的原因，例如 `@self changed.`（view 自己的欄位變了）或 `_price changed.`（`price` 這個狀態變了）。名字前面有底線，表示它是除錯用的 API，查完問題就要拿掉，不要留在正式版本。

**不要在 `body` 裡做重工作**：排序、過濾、建立 `NumberFormatter`、解析字串。這些要預先在 ViewModel 或資料層算好，`body` 只負責顯示結果。因為 `body` 可能一秒被算很多次。

> [!TIP]
> 面試時可以這樣說：「`body` 重新計算不等於重畫，SwiftUI 會比對前後差異，只更新有變的部分。但子 view 的 `body` 會不會被算，要看 SwiftUI 能不能判斷它的欄位沒變。我實測過，傳了捕獲變動變數的 closure，或每次都 new 新物件，子 view 每次都會重算。我會用 `_printChanges()` 看原因，再用 Instruments 確認。」

### 4. 怎麼讓沒有變的子 view 被跳過，不要白做工？

上一題的結果告訴我們：只要子 view 的欄位是「SwiftUI 判斷得出有沒有變」的東西，`body` 就能被跳過。實務上有兩個做法：一是盡量只傳值型別（`struct`、`String`、`Decimal`），不要傳 closure 和每次新建的物件；二是讓子 view 遵守 `Equatable`，自己定義「什麼叫沒變」，再用 `.equatable()` 告訴 SwiftUI 比較時用你的 `==`。

行情列表的每一列就是典型例子：每一列有一個 `onTap` closure，捕獲了父層的東西，如果不處理，每次報價更新，每一列都會重算。

```swift
import SwiftUI

struct Ticker: Equatable, Identifiable {
    let id: String       // 幣種代號，例如 "btcjpy"
    let price: String
    let change: String
}

struct TickerRow: View, Equatable {
    let ticker: Ticker
    let onTap: (String) -> Void   // closure 沒辦法比較，所以 == 裡不比它

    // 只比較 ticker：ticker 沒變，這一列的 body 就可以跳過
    nonisolated static func == (l: Self, r: Self) -> Bool { l.ticker == r.ticker }

    var body: some View {
        HStack {
            Text(ticker.id)
            Spacer()
            Text(ticker.price)
            Text(ticker.change)
        }
        .onTapGesture { onTap(ticker.id) }
    }
}

struct MarketList: View {
    let tickers: [Ticker]
    let onTap: (String) -> Void

    var body: some View {
        ScrollView {
            LazyVStack {
                ForEach(tickers) { t in
                    TickerRow(ticker: t, onTap: onTap).equatable()   // 用上面定義的 == 來比較
                }
            }
        }
    }
}
```

> [!NOTE]
> 這裡的 `==` 前面標了 `nonisolated`，是因為在 Xcode 26.3 的 SDK 下，`View` 預設是 `@MainActor`，如果專案使用 Swift 6 語言模式，讓 view 遵守 `Equatable` 時，`==` 不標 `nonisolated` 會得到「conformance crosses into main actor-isolated code」的編譯錯誤（我們在本機實測）。

使用時要留意：`==` 只比較了 `ticker`，所以如果 `onTap` 的行為日後會改變，這個 `==` 會漏掉。確定 closure 的內容不會變，才這樣寫。

> [!TIP]
> 面試時可以這樣說：「列表的 row 我只傳值，不傳整個 ViewModel；如果一定要傳 closure，會讓 row 遵守 `Equatable`，`==` 只比較會影響畫面的資料，再用 `.equatable()`，讓沒變的 row 被跳過。這個改動有沒有效，我會用 Instruments 或 `_printChanges()` 確認。」

### 5. `AnyView` 和 Lazy 容器該怎麼用？

**`AnyView`** 把 view 的型別資訊藏起來（這種做法叫 type erasure，型別擦除），讓你可以用同一個型別回傳不同的 view。代價是 SwiftUI 失去了靜態的結構資訊，沒辦法做某些最佳化。Apple 的文件也寫到：`AnyView` 裡面的 view 型別一旦改變，SwiftUI 會把舊的整個階層丟掉，重新建立。

比較好的替代方案是 `@ViewBuilder`、`Group`、泛型，或把分支寫成 enum 配合 `switch`：

```swift
import SwiftUI

enum Mode { case price, depth, trades }

// 不推薦：用 AnyView 回傳不同的畫面
func content(for mode: Mode) -> AnyView {
    switch mode {
    case .price:  return AnyView(PriceView())
    case .depth:  return AnyView(DepthView())
    case .trades: return AnyView(TradesView())
    }
}

// 推薦：@ViewBuilder 配合 switch，SwiftUI 看得到每個分支的型別
@ViewBuilder
func content(for mode: Mode) -> some View {
    switch mode {
    case .price:  PriceView()
    case .depth:  DepthView()
    case .trades: TradesView()
    }
}
```

另外，不要用 `any View` 來取代 `AnyView`：`any View` 是「某種遵守 `View` 的型別」，它本身並不遵守 `View`，不能直接放進 `body`。

**常見誤解：「AnyView 一定每次都重算」。** 這個說法不正確。我們實測過，欄位沒變時，被 `AnyView` 包住的 view 一樣可以跳過 `body`。`AnyView` 的問題在於結構資訊與 identity 的行為，不是每次都重算。另外，不要把 `AnyView` 放進 `ForEach` 裡。

**Lazy 容器**解決的是「一次建立太多 view」的問題。普通的 `VStack` 會一次把所有子 view 都建立出來；`LazyVStack`、`LazyHStack`、`LazyVGrid` 只建立「現在需要顯示的」。我們用 500 列實測：放進 `VStack` 會建立 500 個 row，`LazyVStack` 只建立畫面附近的幾個。畫面上的差別是：打開一個有幾百筆成交紀錄的畫面時，`VStack` 會卡一下，記憶體也比較高。

`List` 本身就會只建立看得到的列。長列表請用 `List`，或 `ScrollView` 搭配 `LazyVStack`，並且確保 `ForEach` 的 id 穩定。

> [!TIP]
> 面試時可以這樣說：「`AnyView` 會抹掉型別資訊，型別一變就整個階層重建，所以我用 `@ViewBuilder` 或 `switch` 取代；但它並不是每次都重算，這點我有實測。長列表我用 `List` 或 `LazyVStack`，普通的 `VStack` 會一次建立全部的 row。」

### 6. `.task` 什麼時候開始、什麼時候取消？

`.task { }`（iOS 15 以上）會在 view 出現時啟動一段非同步工作，並且在 view 消失（identity 被移除）時**自動取消**。`.task(id: symbol)` 更進一步：`id` 的值改變時，會取消舊的工作、用新的值重新啟動一次，這是「切換交易對時重新載入資料」的標準做法。

```swift
import SwiftUI

struct MarketView: View {
    let selectedSymbol: String
    let vm: MarketViewModel

    var body: some View {
        List(vm.tickers) { ticker in
            Text(ticker.id)
        }
        .task(id: selectedSymbol) {          // selectedSymbol 一變，舊的取消、新的重新開始
            await vm.load(selectedSymbol)
        }
    }
}
```

它比 `onAppear { Task { … } }` 好用，因為不需要自己保存 `Task` 並在適當時機取消，不容易忘記取消而造成洩漏。

**常見誤解：`.task` 只會執行一次。** 不是。它跟著 view 的出現與消失：消失後取消，再次出現時重新啟動（和 `onAppear` 一樣）。如果你只想「載入一次」，要在 ViewModel 裡用旗標或快取判斷。至於在 push／pop 或 `TabView` 切換的各種情境下，view 到底何時算「消失」，行為不一定如直覺，要實際在機器上驗證，不要假設。

> [!TIP]
> 面試時可以這樣說：「`.task` 的壽命跟 view 的 identity 綁在一起，出現時啟動、消失時自動取消，`.task(id:)` 在 id 變化時會取消舊的再啟動新的，所以切換交易對我會用它，不用自己管 Task。但它在 view 再次出現時會重啟，只載入一次要在 ViewModel 裡判斷。」

### 7. 行情列表每秒更新很多次，怎麼避免整頁卡頓？怎麼確認真的有改善？

卡頓通常來自兩個原因：更新太頻繁，或是每次更新牽動的範圍太大。對應的做法有六件事：

1. **資料層先合併再更新（conflation）。** 行情推播一秒可能來幾十次，不要每筆都更新畫面。把一小段時間（約 100 到 250 毫秒）內的多筆更新合併，每個幣種只留最新的一筆，再一次更新畫面。
2. **更新的範圍越小越好。** 高頻變動的資料，只讓最末端的 view 讀取（例如價格那個 `Text` 自己有小的 ViewModel），父層和同層的其他 view 不要讀它。
3. **row 只接收自己的值，並遵守 `Equatable`。** 不要把整個 ViewModel 傳到每個 row 去觀察（見上一個單元）。
4. **穩定的 `ForEach` id 加 Lazy 容器。** id 用幣種代號，不用 index。
5. **格式化放在資料層，並快取 formatter。** `body` 只負責顯示。
6. **`drawingGroup()` 可以把複雜的圖形合成單一張圖再繪製**，但會影響文字清晰度與互動，要用 Instruments 驗證真的有效才使用。

合併更新的最小範例：

```swift
// 每個幣種只留最新的一筆；到了固定間隔才送出，避免每個 tick 都更新畫面
struct TickBatcher<Value> {
    let intervalMs: Int64
    private var pending: [String: Value] = [:]   // 幣種 -> 最新的值
    private var lastFlush: Int64?

    init(intervalMs: Int64) { self.intervalMs = intervalMs }

    mutating func push(_ symbol: String, _ value: Value, now: Int64) -> [String: Value]? {
        pending[symbol] = value                    // 同一個幣種，後來的蓋掉先來的
        if let last = lastFlush, now - last < intervalMs { return nil }   // 還沒到間隔，先不送
        let batch = pending
        pending = [:]
        lastFlush = now
        return batch                               // 到了間隔，一次送出所有幣種的最新值
    }
}
```

**怎麼確認有改善：用工具量，不要憑感覺。** Xcode 26 的 Instruments 有 SwiftUI 模板，裡面的 **Long View Body Updates** 會找出哪些 `body` 計算太久，**Cause & Effect Graph** 會顯示哪個狀態變化引發了哪些 view 更新；搭配 Time Profiler 和 Hitches（畫面掉幀的紀錄）看實際有沒有卡頓。簡單的做法是 `_printChanges()`，或在 `body` 裡放計數器。每一幀的時間預算：60 Hz 的螢幕約 16.7 毫秒，120 Hz 約 8.3 毫秒。

> [!TIP]
> 面試時可以這樣說：「我會先量再改。如果是更新太頻繁，就在資料層做 conflation，每 100 到 250 毫秒批次更新一次；如果是影響範圍太大，就把高頻變動的資料隔離到最末端的 view，row 只拿自己的值並遵守 `Equatable`。工具是 Instruments 的 SwiftUI 模板，看 body 的更新次數與耗時。」

> [!FIELD]
> 在一個即時行情 App 的 iOS 版裡，全面採用 SwiftUI，列表使用 `LazyVStack`、`LazyVGrid` 等 Lazy 容器；圖表與 WebView 則用 `UIViewRepresentable` 包起來。K 線更新時，iOS 端會記錄上一次推送到圖表的時間，只推送新的資料；Android 的圖表也會用資料的雜湊值判斷，資料沒變就不重新設定。面試時被問到效能，可以從這些「避免白做工」的實作說起，再說明做交易所 App 的行情頁時，我會先用 Instruments 量，再依結果改善。

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift08SwiftUIIdentity**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift08-SwiftUIIdentity/`，解答在 `labs/ios/Solution/Swift/Swift08-SwiftUIIdentity/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **畫面類演練**：每個演練的畫面都可以直接在 App 上方的操作區操作（輸入、按按鈕、看 body 計算次數與 FPS）。按「執行」時，App 會把同一個畫面放進螢幕外的視窗自動跑一遍，把次數寫進 log，自動檢查靠這些數字判斷。

### 演練 1：切換市價／限價，打到一半的數量消失（第 1 題）

**檔案**：`L01_IfElseIdentity.swift`

**情境**：下單頁有「市價／限價」的切換，兩種模式用同一個下單表單 `OrderForm`（裡面有 `@StateObject` 的 ViewModel）。使用者在數量欄位打了 0.5，一切換，欄位就變回空白。

**怎麼寫**

1. **Bug 版**：`Screen` 的 `body` 用 `if / else`：`mode.isMarket` 時畫 `OrderForm(mode: .market)`，否則畫 `OrderForm(mode: .limit)`。
2. **修正版**：只寫一個 `OrderForm`，模式用參數傳進去：`OrderForm(mode: mode.isMarket ? .market : .limit)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 切換 2 次後，`OrderFormModel` 建立了 3 次，數量欄位變回（空白） |
| 修正版 | 只建立 1 次，數量欄位的內容還是 0.5 |

**為什麼**：`if / else` 的兩個分支，就算型別一樣，也是兩個不同的 identity。切換時 SwiftUI 把舊的銷毀、建立新的，裡面的 `@StateObject` 跟著重新建立。只寫一個 `OrderForm`，位置與型別都沒變，就是同一個 identity，狀態保留。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L01_IfElseIdentity.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L01_IfElseIdentity.swift#fixed
```

### 演練 2：刪掉第一筆，備註跑到別筆委託單上（第 2 題）

**檔案**：`L02_ForEachIndexID.swift`

**情境**：委託單列表有 A、B、C 三筆，每一列有一個備註欄位（存在那一列的 `@State`），每一列的備註都寫著自己是哪一筆。把第一筆委託單刪掉之後，備註錯位了。

**怎麼寫**

1. **Bug 版**：`ForEach(list.orders.indices, id: \.self)`，用 index 當 id，每一列用 `list.orders[i]` 取出委託單。
2. **修正版**：`Order` 已經遵守 `Identifiable`，改成 `ForEach(list.orders) { order in … }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 刪掉委託單 A 後，委託單 B 那一列的備註是「備註 A」、委託單 C 那一列的備註是「備註 B」 |
| 修正版 | 委託單 B 那一列的備註還是「備註 B」、委託單 C 那一列還是「備註 C」 |

**為什麼**：用 index 當 id 時，SwiftUI 看到的是「index 0 那一列還在，只是資料換了」，所以保留 index 0 那一列的 `@State`，上一筆的備註就出現在下一筆的列上。用委託單編號當 id，SwiftUI 知道被刪掉的是哪一列，狀態會跟著正確的列走。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L02_ForEachIndexID.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L02_ForEachIndexID.swift#fixed
```

### 演練 3：沒變的子 view，每次都跟著重算（第 3 題）

**檔案**：`L03_BodyRecompute.swift`

**情境**：報價頁上方有一個「報價已更新 N 次」的計數，下面一列顯示固定不變的委託價。為了格式化價格，父層在 `body` 裡 new 了一個 `PriceStyle`（包著 `NumberFormatter` 的 class）傳給子 view `PriceRow`。

**怎麼寫**

1. **Bug 版**：`Screen` 的 `body` 裡寫 `PriceRow(price: 15_000_000, style: PriceStyle())`，每次都 new 一個新的。
2. **修正版**：格式化只做一次（用 `static let` 算出 `priceText`），`PriceRow` 改成只收 `priceText: String`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 父層更新 3 次後，`PriceStyle` 建立了 4 次，`PriceRow` 的 body 算了 4 次 |
| 修正版 | `PriceRow` 的 body 只算 1 次 |

**為什麼**：父層重新計算時，SwiftUI 要判斷子 view 的欄位有沒有變，才能決定要不要跳過它的 `body`。每次 new 出來的 class 物件都是不同的物件，所以每次都被當作「有變」。只傳值型別（這裡是 `String`），沒變就能被跳過。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L03_BodyRecompute.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L03_BodyRecompute.swift#fixed
```

### 演練 4：沒變的行情列，每次都跟著重算（第 4 題）

**檔案**：`L04_EquatableRow.swift`

**情境**：行情列表有 BTC、ETH、XRP 三檔，每一列有一個 `onTap` closure，內容會用到父層「目前更新了幾次」。每次更新只有 BTC 的價格在變。

**怎麼寫**

1. **Bug 版**：在 `Screen` 裡建立每一列時，傳一個 `onTap` closure，closure 裡用 `SwiftUILabProbe.note` 記下點擊，內容要用到 `updates`（每次都在變的值）。
2. **修正版**：`TickerRow` 遵守 `Equatable`，寫 `==` 只比較 `ticker`（closure 不比，`==` 前面加 `nonisolated`），建立時在後面加上 `.equatable()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 父層更新 3 次後，BTC、ETH、XRP 三列的 body 都算了 4 次 |
| 修正版 | BTC 那一列算了 4 次（價格每次都變），ETH、XRP 各只算 1 次 |

**為什麼**：closure 沒辦法比較，捕獲了每次都在變的值，SwiftUI 就當作每次都不一樣。自己定義 `==`（只比較會影響畫面的資料），再用 `.equatable()` 告訴 SwiftUI 比較時用它，沒變的列就被跳過。`==` 只比較 `ticker`，所以如果 closure 的行為日後會改變，這個 `==` 會漏掉，確定 closure 不會變才這樣寫。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L04_EquatableRow.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L04_EquatableRow.swift#fixed
```

### 演練 5：打開成交紀錄，一次建立 500 列（第 5 題）

**檔案**：`L05_LazyContainer.swift`

**情境**：成交紀錄畫面有 500 筆，放在 `ScrollView` 裡，畫面一次只看得到十幾列。

**怎麼寫**

1. **Bug 版**：`ScrollView` 裡用普通的 `VStack`，裡面 `ForEach(0..<500, id: \.self)`。
2. **修正版**：把 `VStack` 換成 `LazyVStack`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面打開後建立了 500 列，包括根本沒顯示的 |
| 修正版 | 只建立畫面附近的十幾列（實測 16 列），往下捲才會一點一點增加 |

**為什麼**：`VStack` 會一次把所有子 view 都建立出來，`LazyVStack` 只建立現在需要顯示的。筆記裡講的 `AnyView` 部分是概念比較，沒有做成演練。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L05_LazyContainer.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L05_LazyContainer.swift#fixed
```

### 演練 6：離開畫面之後，背景還在一直輪詢（第 6 題）

**檔案**：`L06_TaskCancel.swift`

**情境**：行情畫面出現時開始輪詢報價（每 50 毫秒一次，最多 40 次）。使用者離開這個畫面之後，輪詢應該要停。

**怎麼寫**

1. **Bug 版**：在 `onAppear` 裡開一個 `Task { … }`，用 `for _ in 0..<40` 輪詢：每次呼叫 `pollQuote(counter)`，再 `Task.sleep` 等 50 毫秒。
2. **修正版**：改用 `.task { … }`，同樣輪詢；因為 `try? await Task.sleep` 不會中斷迴圈，迴圈裡要檢查 `Task.isCancelled`，被取消就 `break`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 離開畫面 0.5 秒後，又多輪詢了約 10 次：Task 沒有被取消，一直跑到 40 次才停 |
| 修正版 | 離開畫面後輪詢立刻停住（又多輪詢 0 次） |

**為什麼**：`onAppear` 裡開的 `Task` 和畫面的壽命沒有關係，沒有人會取消它。`.task` 的壽命綁在 view 的 identity 上，view 消失時它會被自動取消；但取消只是「通知」，迴圈要自己檢查 `Task.isCancelled`（或用會丟出取消錯誤的 `try await Task.sleep`）才會真的停下來。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L06_TaskCancel.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L06_TaskCancel.swift#fixed
```

### 演練 7：行情每秒更新幾十次，畫面卡頓（第 7 題）

**檔案**：`L07_MarketHitch.swift`

**情境**：行情列表有 200 檔，推播每 25 毫秒送來一份「全部幣種」的新報價（約每秒 40 次），每一列要把價格格式化成有千分位的文字。App 上方的操作區有 `FrameMeter`，按「開始推播」可以看 FPS 與卡頓次數。

**怎麼寫**

1. **Bug 版**：`receiveSnapshot` 每一份都立刻更新 `quotes` 並把 `publishCount` 加 1；每一列在 `body` 裡用 `makeFormatter()` 建立 formatter 來格式化價格。
2. **修正版**：把複習站裡的 `TickBatcher.push` 寫完整（同一檔只留最新、到了間隔才送出一批）；格式化放在資料層，用快取的 formatter 做好 `quote.text`；每一列的 body 只顯示 `quote.text`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 40 份推播，ViewModel 發布 40 次；`NumberFormatter` 建立了 8000 次；各列的 body 算了 8000 次；主執行緒卡頓約 40 次、累計約 0.9 秒（Debug 實測） |
| 修正版 | 發布 5 次；推播期間 `NumberFormatter` 建立 0 次；各列的 body 算了約 800 次；卡頓約 4 次 |

**為什麼**：卡頓來自兩件事：更新太頻繁、每次更新做的事太貴。合併之後，更新次數從每份推播一次降到每 200 毫秒一次；formatter 建立很貴，放在 `body` 裡等於每次重算都再建一個，移到資料層用快取的 formatter 做一次就好。改完要用數字確認：FPS 與卡頓次數看 `FrameMeter`，更細的可以用 Instruments 的 SwiftUI 模板（Long View Body Updates、Cause & Effect Graph）。

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L07_MarketHitch.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift08-SwiftUIIdentity/L07_MarketHitch.swift#fixed
```

## 常見陷阱

- **用 `if / else` 切換兩個外觀。** 兩個分支是不同的 identity，每次切換都重置狀態、重新載入。想保留狀態，改用 modifier（例如 `.opacity`）。
- **把 `.id(UUID())` 寫在 `body` 裡。** 每次計算 `body` 都換新的 identity，整棵子樹每次都重建。`.id` 只用在「刻意重置」。
- **`ForEach(items.indices, id: \.self)`。** 刪除或插入之後，index 對應的資料換了，狀態會留在錯的列。改成遵守 `Identifiable`，用穩定的 id。
- **傳 closure 給子 view，卻不處理比較。** 如果 closure 捕獲了會變動的值，子 view 每次都會重算 `body`。讓子 view 遵守 `Equatable`，或改成只傳值。
- **整個列表共用一個 `ObservableObject`。** 任何一檔更新，就重算全部（見上一個單元）。
- **在 `body` 裡建立 formatter 或做排序。** `body` 可能一秒被算很多次，這些工作要預先在資料層算好。
- **在 `ScrollView { VStack { … } }` 裡放長列表。** `VStack` 一次建立全部的 row，改用 `LazyVStack` 或 `List`。
- **以為 `.task` 只會執行一次。** view 再次出現時它會重新啟動，只載入一次要在 ViewModel 裡判斷。

## 面試官想聽到的

1. 能用 structural identity 與 explicit identity，解釋「為什麼狀態被重置」，以及 `.id()` 的副作用。
2. 能區分「`body` 重新計算」和「畫面重畫」，知道什麼情況會讓子 view 無法被判斷為沒變（捕獲變動變數的 closure、每次新建的物件）。
3. 對 `AnyView`、Lazy 容器、`.task` 有具體且正確的描述，不給出過度簡化的結論。
4. 能設計高頻更新的處理方式：conflation、把變動隔離到最末端的 view、`Equatable` 的 row。
5. 會用工具驗證：`_printChanges()` 和 Instruments 的 SwiftUI 模板，而不是憑感覺猜。

> [!WARNING]
> 面試官常追問：「你怎麼知道是 SwiftUI 的哪個地方慢？」不要只回答「用 Profiler」。要說出具體流程：先用 Instruments 的 SwiftUI 模板找出 `body` 太慢或更新太頻繁的 view，再用 `_printChanges()` 看是什麼原因觸發，最後才決定是縮小更新範圍、降低頻率，還是把重工作移出 `body`。

## iOS / Android 對照

| 主題 | iOS（SwiftUI） | Android（Jetpack Compose） |
|---|---|---|
| identity | structural 加 `.id()`、`ForEach` 的 id | 依呼叫位置記憶，加 `key()`、`LazyColumn(key = …)` |
| 讓狀態重置 | identity 改變 | `key(x) { }`：key 改變時，裡面的 `remember` 重置 |
| 跳過重新計算 | 欄位比對、`Equatable` | 參數是否穩定（`@Stable`、`@Immutable`；strong skipping 模式對不穩定的參數放寬） |
| 只建立看得到的列 | `LazyVStack`、`List` | `LazyColumn`、`LazyVerticalGrid` |
| 副作用的生命週期 | `.task`、`.task(id:)` | `LaunchedEffect(key)` |
| 剖析工具 | Instruments 的 SwiftUI 模板、`_printChanges()` | Layout Inspector 的重組次數、Compose 編譯器報告、Perfetto |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 身分（view 的身分） | identity | アイデンティティ |
| 結構上的身分／明確指定的身分 | structural identity／explicit identity | 構造的アイデンティティ／明示的アイデンティティ |
| 失效（標記為需要重新計算） | invalidate | 無効化 |
| 型別擦除 | type erasure | 型消去 |
| 合併後只保留最新 | conflation | コンフレーション |
| 延遲載入 | lazy loading | 遅延読み込み |
| 掉幀 | hitch | ヒッチ |
