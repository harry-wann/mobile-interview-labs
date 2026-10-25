---
id: swift-swiftui-state
title: SwiftUI 狀態管理：@State、@StateObject、@ObservedObject、@Observable 差在哪
summary: 面試官很愛問「@StateObject 和 @ObservedObject 差在哪」「為什麼畫面上的狀態會突然被重置」「@Observable 解決了什麼問題」。這個單元用「誰擁有這份資料、view 被重建時會不會被重置」把每個 property wrapper 一個一個講清楚。
priority: P0
day: 1
tags: [swiftui, state, stateobject, observedobject, observable, observation, bindable, environment]
---

## 重點講解

這個單元回答六個面試常見問題：

1. `@State`、`@Binding`、`@StateObject`、`@ObservedObject` 到底差在哪？
2. 常見的 bug：用 `@ObservedObject` 建立 ViewModel，為什麼畫面上的狀態會被重置？
3. 為什麼用參數去初始化 `@State`，上層改了值，子層卻沒有跟著變？
4. `@Environment` 和 `@EnvironmentObject` 是做什麼的？漏了注入會怎樣？
5. `ObservableObject` 和 iOS 17 的 `@Observable` 差在哪？
6. 專案最低版本是 iOS 15，不能用 `@Observable`，行情列表怎麼避免整頁一直重算？

### 1. `@State`、`@Binding`、`@StateObject`、`@ObservedObject` 到底差在哪？

差別只有一個：**這份資料是誰擁有的。** 擁有者負責建立資料、也負責讓它在畫面更新時不會消失；不是擁有者的，只是從別人那裡拿到資料來用。判斷時問自己兩件事：這份資料是我這個 view 建立的嗎？我的 view 被重建時，它會不會被重置？

先解釋為什麼會有「被重建」這件事。SwiftUI 的 view 是很輕量的 struct，它只是「這個畫面長什麼樣子」的一份描述。父層的 `body` 每重新計算一次，就會用 `init` 重新建立一份子層的 struct，舊的那份直接丟掉。所以如果你把輸入框的文字、載入中的狀態直接放在 struct 的普通屬性裡，下一次重建就會消失。

SwiftUI 的做法是：真正需要保留的資料，另外存在 SwiftUI 自己管理的地方，不放在 struct 裡。你在屬性前面加上 `@State`、`@StateObject` 這類標記，SwiftUI 才知道「這個要幫我保管」。這些標記的正式名稱是 property wrapper（屬性包裝器，寫在屬性前面、以 `@` 開頭的那些標記）。保管的依據是這個 view 在畫面結構中的位置；view 從畫面上消失，狀態就一起被清掉（位置怎麼判斷，下一個單元會講）。

| property wrapper | 誰擁有 | view 被重建時 | 適合用在 | 最低版本 |
|---|---|---|---|---|
| `@State` | 這個 view 自己 | 保留，不會被重置 | 這個畫面自己用的簡單值（開關、輸入框文字、選中的分頁） | iOS 13 |
| `@Binding` | 不擁有，借用上層的 | 跟著上層的值走 | 子層需要讀寫上層的 `@State` | iOS 13 |
| `@StateObject` | 這個 view 自己 | 保留，物件只建立一次 | 這個 view 自己建立的 ViewModel（class，遵守 `ObservableObject`） | iOS 14 |
| `@ObservedObject` | 不擁有，外面建好傳進來 | 不負責保管；傳進來的物件換了，就跟著換 | 外面已經建好的 ViewModel | iOS 13 |

`@Binding` 比較特別：它自己不存資料，只是一個「可以讀寫別人那份資料的把手」。上層在 `@State` 變數前面加 `$`，就能取得這個把手：

```swift
import SwiftUI

struct OrderScreen: View {
    // 這個畫面自己擁有的資料
    @State private var quantity = ""

    var body: some View {
        // $quantity 的型別是 Binding<String>
        QuantityField(text: $quantity)
    }
}

struct QuantityField: View {
    // 借用上層的 quantity，不擁有它
    @Binding var text: String

    var body: some View {
        // 使用者輸入時，改的是上層的 quantity
        TextField("數量", text: $text)
    }
}
```

`@ObservedObject` 和 `@StateObject` 的共同點是：它們指向的物件都要遵守 `ObservableObject`，物件裡用 `@Published` 標記的屬性一變，畫面就會更新。差別只在擁有者：`@StateObject` 是 view 自己建立並擁有，`@ObservedObject` 是別人建立、傳進來給你用。

**規則：誰建立，誰擁有。** view 自己建立的，用 `@State`（簡單的值）或 `@StateObject`（ViewModel）；從外面傳進來的，用 `@Binding`（值）或 `@ObservedObject`（ViewModel）。另外，`@State` 要標成 `private`，因為它是這個 view 的內部細節，不該讓外面的程式碼直接設定。

> [!TIP]
> 面試時可以這樣說：「這幾個 property wrapper 的差別在於誰擁有資料。`@State` 和 `@StateObject` 是 view 自己擁有，SwiftUI 會幫它保管，view 被重建也不會重置；`@Binding` 和 `@ObservedObject` 是借用外面的，自己不負責建立。我的原則是誰建立誰擁有。」

### 2. 常見的 bug：用 `@ObservedObject` 建立 ViewModel，為什麼狀態會被重置？

`@ObservedObject` 不擁有物件，SwiftUI 不會幫它保管。如果你在它後面寫了預設值（例如 `= OrderViewModel()`），那麼每次這個 view 的 struct 被重新建立，就會 new 出一個全新的 ViewModel，舊的連同裡面的狀態一起被丟掉。

畫面上看得到的現象是這樣：下單頁放在一個每秒更新一次的父層裡（例如顯示「報價剩餘幾秒」的倒數）。使用者剛在數量欄位輸入 0.5，一秒後欄位變回空白，進行中的網路請求也被取消、重新發出。這是 SwiftUI 最常見的 bug 之一。

```swift
import SwiftUI

final class OrderViewModel: ObservableObject {
    @Published var quantity = ""

    init() {
        print("建立 OrderViewModel")
    }
}

struct WrongOrderView: View {
    // 錯誤：@ObservedObject 不擁有物件，卻自己建立。
    // 每次 struct 重建都會再 new 一個
    @ObservedObject var vm = OrderViewModel()

    var body: some View {
        TextField("數量", text: $vm.quantity)
    }
}

struct RightOrderView: View {
    // 正確：view 自己建立並擁有，用 @StateObject。只會建立一次
    @StateObject private var vm = OrderViewModel()

    var body: some View {
        TextField("數量", text: $vm.quantity)
    }
}

struct InjectedOrderView: View {
    // 外面建好再傳進來，才用 @ObservedObject
    @ObservedObject var vm: OrderViewModel

    var body: some View {
        TextField("數量", text: $vm.quantity)
    }
}
```

我們實際把「`@ObservedObject` 加預設值」和「`@StateObject`」這兩種寫法放進 SwiftUI 視窗裡跑：父層更新 3 次（父層的 `body` 共計算 4 次，包含第一次），前者的 `init` 被呼叫 **4 次**，`@StateObject` 只被呼叫 **1 次**。

為什麼 `@StateObject` 只建立一次？因為它接收的初始值不是直接建好的物件，而是一段 `@autoclosure`（編譯器自動把你寫的 `OrderViewModel()` 包成一個「需要時才執行」的小函式）。SwiftUI 只在這個 view 第一次出現時才執行它，之後沿用已經保管的物件。`@ObservedObject` 沒有這個機制，預設值就是每次 `init` 都會執行的普通運算式。

**常見誤解：「那我把所有的 `@ObservedObject` 都改成 `@StateObject` 就好？」** 不是。外面已經建好、傳進來的物件，應該用 `@ObservedObject`。如果用 `@StateObject` 去接外面傳進來的物件，SwiftUI 只會在第一次採用它，之後上層換了新的物件，這個 view 還是繼續用最舊的那個。

> [!TIP]
> 面試時可以這樣說：「`@ObservedObject` 不擁有物件，所以不能在後面直接 new。父層重算時 view 的 struct 會被重建，每次都會產生新的 ViewModel，狀態就被重置了。我實際測過，父層更新 3 次，`@ObservedObject` 的預設值被建構 4 次，`@StateObject` 只有 1 次。自己建立的 ViewModel 用 `@StateObject`，外面傳進來的才用 `@ObservedObject`。」

### 3. 為什麼用參數去初始化 `@State`，上層改了值，子層卻沒有跟著變？

`@State` 的初始值**只有在這個 view 第一次出現的時候才會被採用**。之後上層傳了新的參數進來，`@State` 還是保留最早的那個值，不會跟著更新。

具體情境：委託單列表旁邊有一個編輯畫面，編輯畫面用上層傳進來的價格當作輸入框的初始值。使用者在列表點另一筆委託單，編輯畫面收到新的價格，輸入框卻還顯示上一筆的價格。

```swift
import SwiftUI

struct PriceEditor: View {
    // 輸入框正在編輯的值
    @State private var local: Int
    let value: Int // 上層傳進來的值

    init(value: Int) {
        self.value = value
        // 只有第一次建立時才會用到這個值
        _local = State(initialValue: value)
    }

    var body: some View {
        let _ = print("local=\(local) value=\(value)")
        Text("\(local) \(value)")
    }
}

struct Parent: View {
    @State var v = 0

    // v 之後被更新成 1、再更新成 2
    var body: some View {
        PriceEditor(value: v)
    }
}
// 輸出：
// local=0 value=0
// local=0 value=1   <- value 是最新的，local 還是 0
// local=0 value=2
```

`value` 是普通的 `let`，每次都是最新的；`local` 是 `@State`，被 SwiftUI 保管起來，所以停在第一次的 0。

要怎麼修，取決於你想要什麼：

- **想讓子層永遠顯示上層的值**：不要複製成 `@State`，直接用傳進來的 `value`；如果子層還需要修改，就改用 `@Binding`。
- **想要「換一筆資料就從頭開始」**：在使用的地方加上 `.id(order.id)`。`.id` 的意思是告訴 SwiftUI「id 不同就是不同的 view」，id 一換，舊的 view 和它的狀態整個丟掉，重新建立一個。代價是整棵子樹都重建，下一個單元會細講。
- **想在參數改變時重新載入資料**：把載入寫在 `.task(id: value)`。`.task(id:)` 會在 id 改變時，取消舊的非同步工作、重新啟動一次。

`@StateObject` 也有同樣的限制。例如行情頁的 ViewModel 需要知道幣種代號：

```swift
import SwiftUI

struct PriceScreen: View {
    @StateObject private var vm: PriceViewModel

    init(symbol: String) {
        // symbol 只有第一次建立 ViewModel 時有效
        _vm = StateObject(wrappedValue: PriceViewModel(symbol: symbol))
    }

    var body: some View {
        Text(vm.price)
    }
}
```

使用者如果在同一個畫面切換到另一個幣種，這個 ViewModel 不會重建，仍然是舊的幣種。要換幣種時，一樣用 `.id(symbol)` 讓整個畫面重來，或讓 ViewModel 提供 `func load(symbol:)`，由 `.task(id: symbol)` 呼叫。

> [!TIP]
> 面試時可以這樣說：「`@State` 的初始值只在 view 第一次建立時使用，上層之後傳新的參數，它不會跟著變。如果我要它跟著上層，就不複製成 `@State`，直接用傳進來的值或 `@Binding`；如果是換一筆資料要整個重來，就用 `.id` 或 `.task(id:)`。」

### 4. `@Environment` 和 `@EnvironmentObject` 是做什麼的？漏了注入會怎樣？

SwiftUI 有一個「環境」（environment）：祖先 view 把資料放進去，底下所有的子孫 view 都能直接取用，不必一層一層用參數往下傳。有兩種用法。

**第一種：`@Environment(\.keyPath)`，讀取系統或自訂的環境值。** 例如 `\.colorScheme`（目前是深色還是淺色模式）、`\.dismiss`（關閉目前這個畫面）、`\.scenePhase`（App 在前景還是背景）。這些值不是你擁有的，由 SwiftUI 提供，你只是讀取。

**第二種：`@EnvironmentObject`（iOS 13），共用一個 ViewModel。** 祖先用 `.environmentObject(物件)` 放進環境，子孫 view 宣告 `@EnvironmentObject var xxx: 型別`，SwiftUI 就依照「型別」找到那個物件。適合登入狀態、使用者資產這種很多畫面都要用的資料。

```swift
import SwiftUI

final class SessionStore: ObservableObject {
    @Published var userName = "Alice"
}

@main
struct TradeApp: App {
    // 在最上層建立並擁有
    @StateObject private var session = SessionStore()

    var body: some Scene {
        WindowGroup {
            // 放進環境，底下所有 view 都拿得到
            RootView().environmentObject(session)
        }
    }
}

struct RootView: View {
    var body: some View {
        ProfileView()
    }
}

struct ProfileView: View {
    // 依型別從環境取出
    @EnvironmentObject var session: SessionStore
    // 讀取系統提供的「關閉畫面」動作（iOS 15）
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Button("\(session.userName)，關閉") { dismiss() }
    }
}
```

**`@EnvironmentObject` 最大的風險：漏了注入，編譯器不會提醒，到執行時才 crash。** 因為依賴是「隱含」的，函式簽名上看不出 `ProfileView` 需要 `SessionStore`。如果上面的 `.environmentObject(session)` 漏了，`ProfileView` 一讀到 `session` 就會 crash，錯誤訊息是找不到該型別的 ObservableObject。

最容易漏的地方：

- Xcode 的 preview：preview 是獨立的環境，沒有人幫你注入。
- 用 `UIHostingController` 包起來再 present 的 SwiftUI 畫面（混合 UIKit 的專案常見）。`UIHostingController` 是一棵全新的 SwiftUI 樹，不會繼承其他 SwiftUI 畫面的環境，所以每個 `UIHostingController` 的根 view 都要自己注入。
- 在 `init` 裡讀取：`init` 執行時 view 還沒放進畫面，環境物件還沒到，要在 `onAppear` 或 `.task` 裡才能用。

這也是為什麼有些團隊偏好「顯式依賴」：用 `@ObservedObject` 以參數明確傳入，從函式簽名就看得出依賴什麼，也比較好測試。iOS 17 起，`@EnvironmentObject` 有對應的新寫法 `@Environment(Model.self)`，搭配 `.environment(model)` 放進環境，配 `@Observable` 的物件使用，下一題會講。

> [!TIP]
> 面試時可以這樣說：「`@Environment` 讀系統或自訂的環境值，`@EnvironmentObject` 讓祖先把共用的 ViewModel 放進環境，子孫依型別取用，適合登入狀態這類很多畫面共用的資料。風險是漏注入只會在執行時 crash，編譯器抓不到，所以 preview 和 `UIHostingController` 的根 view 要特別檢查；變動很頻繁的值我不會放在裡面。」

### 5. `ObservableObject` 和 iOS 17 的 `@Observable` 差在哪？

`ObservableObject` 是**以整個物件為單位**通知：物件裡任何一個 `@Published` 屬性改變，所有「觀察這個物件」的 view，`body` 都會重新計算，不管這個 view 有沒有讀到被改的那個屬性。`@Observable`（iOS 17 加入）是**以屬性為單位**：SwiftUI 會記錄 `body` 實際讀了哪些屬性，只有讀過被改動屬性的 view 才需要重新計算。

用交易所的行情頁來想：一個 ViewModel 裡放 100 檔幣種的報價。BTC 的價格更新時，用 `ObservableObject`，觀察這個 ViewModel 的每一個 view 都會重新計算；用 `@Observable`，只有真的讀取了 BTC 價格的那一列會重新計算。我們實測過：兩個 view 分別只讀 `a`、`b` 兩個屬性，改動 `a` 之後，`@Observable` 只重算讀 `a` 的 view，`ObservableObject` 兩個都重算。

先看舊寫法與新寫法的對應。`@Observable` 是 Swift 5.9 加入的 macro（巨集，編譯時自動幫你補上程式碼的功能），搭配的 Observation 框架需要 iOS 17：

| 需求 | `ObservableObject` 寫法 | iOS 17 的 `@Observable` 寫法 |
|---|---|---|
| 宣告物件 | `class Model: ObservableObject` | `@Observable class Model` |
| 要通知畫面的屬性 | 每個都要寫 `@Published` | 不用標記，自動追蹤；不想追蹤的標 `@ObservationIgnored` |
| view 自己擁有物件 | `@StateObject` | `@State` |
| 外面傳進來、只讀取 | `@ObservedObject` | 直接宣告 `let model: Model` 即可 |
| 外面傳進來、要用 `$model.xxx` 綁定 | `@ObservedObject` | `@Bindable` |
| 放進環境 | `.environmentObject()` 與 `@EnvironmentObject` | `.environment(model)` 與 `@Environment(Model.self)` |

```swift
import SwiftUI
import Observation

@available(iOS 17, *)
@Observable
final class TickerModel {
    var price = "0"
    var change = "0%"
    // 不追蹤，改它不會觸發畫面更新
    @ObservationIgnored var debugLabel = ""
}

@available(iOS 17, *)
struct TickerScreen: View {
    // 自己擁有，用 @State 取代 @StateObject
    @State private var model = TickerModel()

    var body: some View {
        VStack {
            // body 讀了 price，所以只有 price 改變時才需要重算
            Text(model.price)
            EditField(model: model)
        }
    }
}

@available(iOS 17, *)
struct EditField: View {
    // 需要 $model.xxx 時才用 @Bindable
    @Bindable var model: TickerModel

    var body: some View {
        TextField("價格", text: $model.price)
    }
}
```

SwiftUI 追蹤屬性的做法，你也可以用 `withObservationTracking` 親眼看到：

```swift
import Observation

@Observable final class Model {
    var a = 0
    var b = 0
}

let m = Model()

withObservationTracking {
    _ = m.a
} onChange: {
    print("a 變了")
}
// 沒有輸出：上面的區塊只讀了 a，沒讀 b
m.b = 1
m.a = 1 // 輸出：a 變了
// 沒有輸出：onChange 只會通知一次
m.a = 2
```

注意一個容易踩的地方：`@State private var model = TickerModel()` 的初始值**不是** autoclosure，和 `@StateObject` 不一樣。父層每更新一次，這行就會再執行一次建構式，只是多建的那些會被丟掉，真正保留的只有第一個。我們實測父層更新 3 次，`init` 被呼叫 4 次。所以建構式要保持輕量，需要大量運算或載入資料的工作，放到 `.task`。

> [!TIP]
> 面試時可以這樣說：「`ObservableObject` 是物件層級的通知，任何一個 `@Published` 變動，觀察它的 view 都會重算 body。Observation 的 `@Observable` 在計算 body 時記錄實際讀了哪些屬性，只有讀過被改屬性的 view 才會失效，粒度更細，但需要 iOS 17。」

### 6. 專案最低版本是 iOS 15，不能用 `@Observable`，行情列表怎麼避免整頁一直重算？

不能用 `@Observable` 的時候，策略是**縮小每個 view 觀察的範圍，並降低更新的頻率**。許多已上線的 App 最低版本仍是 iOS 15，所以這是實際會遇到的情況。

問題的根源是：把 100 檔報價放在同一個 `ObservableObject`，任何一檔更新，整個列表都重算。可以做三件事：

1. **資料層先合併再更新。** 行情推播可能每秒來好幾次。與其每筆都更新畫面，不如把一小段時間（例如 200 毫秒）內的多筆更新合併，每個幣種只保留最新的一筆，再一次更新。這種做法叫 conflation（合併、只保留最新），畫面更新的次數就從每秒幾十次降到每秒 5 次。
2. **每一列只拿自己的資料。** 列表的 row 不要整個持有 ViewModel，只傳入自己那一檔的 `Ticker`（值型別，遵守 `Equatable`）。這樣 SwiftUI 比較新舊兩份 `Ticker` 發現沒變，就可以跳過那一列的 `body`。如果 row 的內容比較複雜、SwiftUI 不一定能自己判斷，可以在 row 上加 `.equatable()`，明確要求它用 `Equatable` 的 `==` 來比較（下一個單元會細講）。
3. **必要時每一列有自己的小 ViewModel。** 只觀察自己那一檔，其他檔更新時不受影響。

```swift
import SwiftUI

struct Ticker: Identifiable, Equatable {
    // 幣種代號，例如 "btcjpy"
    let id: String
    var price: Decimal
}

struct MarketList: View {
    // 持有全部報價
    @StateObject private var vm = MarketViewModel()

    var body: some View {
        List(vm.tickers) { ticker in
            // 只傳這一檔的值，不傳整個 vm
            TickerRow(ticker: ticker)
        }
    }
}

struct TickerRow: View {
    // 值沒變，這一列就可以被跳過
    let ticker: Ticker

    var body: some View {
        Text("\(ticker.id) \(ticker.price.description)")
    }
}
```

怎麼確認真的有改善？不要憑感覺，用 Xcode 的 Instruments 裡的 SwiftUI 模板，看哪些 view 的 `body` 被重新計算、算了幾次。

> [!TIP]
> 面試時可以這樣說：「iOS 15 沒有 Observation，我會縮小觀察範圍：資料層先用 conflation 合併，大約 200 毫秒更新一次；每一列只接收自己的 `Ticker` 值，讓沒變的列被 SwiftUI 跳過；有需要再拆成每列自己的小 ViewModel。改完用 Instruments 的 SwiftUI 模板確認，不是憑感覺。」

> [!FIELD]
> 在一個即時行情 App 的 iOS 版裡，全面採用 SwiftUI，用 Combine 綁定畫面狀態，ViewModel 以 `ObservableObject` 搭配 `@StateObject`、`@ObservedObject` 為主。它的最低支援版本是 iOS 16，而 `@Observable` 需要 iOS 17，所以沒有使用，這是部署目標決定的。另一個社交 App 以 UIKit 為主，SwiftUI 透過 `UIHostingController` 嵌進去，最低版本是 iOS 15，同樣不能用 Observation。面試時可以說：「我熟悉擁有權的差異，也知道 `@Observable` 的好處；在 iOS 15 的專案裡，我會用小的 ViewModel 與傳值來縮小重算範圍。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift07SwiftUIState**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift07-SwiftUIState/`，解答在 `labs/ios/Solution/Swift/Swift07-SwiftUIState/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **畫面類演練**：每個演練的畫面都可以直接在 App 上方的操作區操作（輸入、按按鈕、看 body 計算次數）。按「執行」時，App 會把同一個畫面放進螢幕外的視窗自動跑一遍，把「建立了幾次」「body 算了幾次」寫進 log，自動檢查靠這些數字判斷。

### 演練 1：行情一直更新，畫面上的價格卻不動（第 1 題）

**檔案**：`L01_StateVsStateObject.swift`

**情境**：行情頁自己建立一個 ViewModel（遵守 `ObservableObject` 的 class），ViewModel 每收到一筆報價就更新 `@Published` 的 `price`，畫面顯示 `price`。

**怎麼寫**

1. **Bug 版**：在 `vm` 前面加上 `@State private`，用「給簡單的值用的」那個 property wrapper 保管這個 ViewModel。
2. **修正版**：改成 `@StateObject private`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ViewModel 的價格是 103，畫面上的價格卻停在 100；行情頁的 body 只算 1 次 |
| 修正版 | 畫面上的價格跟著變成 103；body 算了 4 次（第一次加上三次價格更新） |

**為什麼**：`@State` 只會在「整個值被換掉」時通知畫面。ViewModel 是 class，`price` 變了，`@State` 裡存的還是同一個物件，它不知道。`@StateObject` 會訂閱 ViewModel 的 `@Published`，所以 `price` 一變，畫面就重算。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L01_StateVsStateObject.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L01_StateVsStateObject.swift#fixed
```

### 演練 2：輸入的數量過一秒就被清空（第 2 題）

**檔案**：`L02_ObservedObjectReset.swift`

**情境**：下單頁上方有「報價剩餘 N 秒」的倒數，每秒更新一次。使用者剛在數量欄位輸入 0.5，一秒後欄位又變回空白。

**怎麼寫**

1. **Bug 版**：`OrderView` 的 `vm` 用 `@ObservedObject`，後面直接寫預設值 `= OrderViewModel()`。
2. **修正版**：改成 `@StateObject private`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 倒數更新 3 次後，`OrderViewModel` 建立了 4 次，數量欄位變回（空白）；在 App 上方的操作區可以看到建立次數每秒加 1 |
| 修正版 | `OrderViewModel` 只建立 1 次，數量欄位還是 0.5 |

**為什麼**：倒數更新時，上層的 `body` 重新計算，`OrderView` 這個 struct 被重新建立，`@ObservedObject` 後面的預設值就再執行一次，new 出全新的 ViewModel，舊的連同裡面的數量一起被丟掉。`@StateObject` 的初始值是 autoclosure，SwiftUI 只在這個 view 第一次出現時才執行它。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L02_ObservedObjectReset.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L02_ObservedObjectReset.swift#fixed
```

### 演練 3：換了一筆委託單，輸入框還是舊價格（第 3 題）

**檔案**：`L03_StateInitialValue.swift`

**情境**：委託單列表旁邊有一個改價畫面，輸入框的初始值是上層傳進來的價格。使用者改選另一筆委託單，上層傳了新的價格，輸入框卻沒變。

**怎麼寫**

1. **Bug 版**：`PriceEditor` 的 `init` 裡，用傳進來的 `price` 建立 `@State`：`_local = State(initialValue: price)`。
2. **修正版**：在 `Screen` 裡使用 `PriceEditor` 的地方，後面加上 `.id(order.id)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 改選 B 之後，上層傳進來的價格是 16000000，輸入框卻還顯示 15000000 |
| 修正版 | 改選 B 之後，輸入框顯示 16000000 |

**為什麼**：`@State` 的初始值只有 view 第一次出現時才會用。`.id(order.id)` 告訴 SwiftUI「id 不同就是不同的 view」，id 一換，舊的 view 連同它的 `@State` 一起丟掉，用新的價格重新建立。代價是整棵子樹都重建，所以只用在「換一筆資料就要整個重來」的地方。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L03_StateInitialValue.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L03_StateInitialValue.swift#fixed
```

### 演練 4：打開個人頁，App 直接當掉（第 4 題）

**檔案**：`L04_MissingEnvironmentObject.swift`

**情境**：個人頁的 SwiftUI view 用 `@EnvironmentObject` 讀登入狀態 `SessionStore`。UIKit 的畫面要打開它，所以用 `UIHostingController` 把個人頁包起來。

**怎麼寫**

1. **Bug 版**：`makeProfileController()` 回傳 `UIHostingController(rootView: ProfileView())`，不注入任何東西。從 Xcode 執行，看 App 停在哪一行。
2. **修正版**：根 view 後面加上 `.environmentObject(session)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | App 當掉，主控台：`Fatal error: No ObservableObject of type SessionStore found. A View.environmentObject(_:) for SessionStore may be missing as an ancestor of this view.` |
| 修正版 | 個人頁畫出來了，讀到的使用者：Alice |

**為什麼**：`@EnvironmentObject` 的依賴藏在屬性裡，函式簽名看不出來，編譯器不會提醒，要到畫面讀到 `session` 的那一刻才當掉。`UIHostingController` 是一棵全新的 SwiftUI 樹，不會繼承其他畫面的環境，所以每一個 `UIHostingController` 的根 view 都要自己注入。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L04_MissingEnvironmentObject.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L04_MissingEnvironmentObject.swift#fixed
```

### 演練 5：只更新 BTC，ETH 那一列也跟著重算（第 5 題）

**檔案**：`L05_ObservationGranularity.swift`

**情境**：ViewModel 放了 BTC、ETH 兩個價格，各有一列 view 只讀自己那一個。BTC 的價格更新時，ETH 那一列完全不需要重算。

**怎麼寫**

1. **Bug 版**：`MarketModel` 遵守 `ObservableObject`（已經寫好），把 `btc`、`eth` 加上 `@Published`。
2. **修正版**：把 `MarketModel` 改成 iOS 17 Observation 框架的 `@Observable`，屬性不用再標記。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 只更新 BTC 的價格後，BTC 那一列算了 2 次，ETH 那一列也算了 2 次 |
| 修正版 | BTC 那一列算了 2 次，ETH 那一列還是 1 次 |

**為什麼**：`ObservableObject` 是以整個物件為單位通知，任何一個 `@Published` 變了，觀察這個物件的每一列都重算 `body`。`@Observable` 會記錄 `body` 實際讀了哪些屬性，ETH 那一列沒有讀 `btc`，就不會被通知。App 上方的操作區可以分別按「BTC 價格 +1」、「ETH 價格 +1」，看兩列的 body 計算次數怎麼變。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L05_ObservationGranularity.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L05_ObservationGranularity.swift#fixed
```

### 演練 6：行情每秒更新幾十次，整頁一直重算（第 6 題）

**檔案**：`L06_MarketListConflation.swift`

**情境**：行情列表有 10 檔幣種，推播很頻繁但大多是 BTC：30 筆推播裡有 24 筆是 BTC、6 筆是其他幣種。假設專案最低版本是 iOS 15，不能用 `@Observable`。

**怎麼寫**

1. **Bug 版**：`receive` 把每一筆推播立刻寫進 `tickers` 並把 `publishCount` 加 1；每一列（`TickerRow`）拿整個 ViewModel（`@ObservedObject`），用 `id` 從 `vm.tickers` 找出自己的價格。
2. **修正版**：先在 ViewModel 加一個 `pending`（字典：檔次 → 最新價格）。`receive` 只存進 `pending`；`flush`（每 200 毫秒呼叫一次）把 `pending` 一次套用到 `tickers`，只發布一次，這就是 conflation（合併、只保留最新）。再把 `TickerRow` 改成只拿自己這一檔的 `Ticker`（值型別，遵守 `Equatable`），上層改成 `TickerRow(ticker: $0)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ViewModel 發布了 30 次；各列的 body 一共算了 310 次（10 列 × 31 次，包含一開始的 10 次） |
| 修正版 | ViewModel 發布了 6 次；各列的 body 一共約 22 次，沒變的列被 SwiftUI 跳過（實測 Debug 22 次） |

**為什麼**：每一列都觀察整個 ViewModel，任何一檔更新，10 列都要重算；每一筆推播都立刻更新，更新的次數就是推播的次數。合併之後，畫面更新的次數降到每 200 毫秒一次；每一列只拿自己的 `Ticker`，SwiftUI 發現這一檔的值沒變，就不用再算那一列的 `body`。這兩件事要一起做：只做其中一個，上面的次數還是會超標。

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L06_MarketListConflation.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift07-SwiftUIState/L06_MarketListConflation.swift#fixed
```

## 常見陷阱

- **`@ObservedObject var vm = ViewModel()`。** `@ObservedObject` 不擁有物件，父層每次重算都會 new 一個新的，狀態和進行中的請求都被重置。自己建立的用 `@StateObject`，外面傳進來的才用 `@ObservedObject`。
- **用參數初始化 `@State`，卻期待它隨參數更新。** `@State` 只用第一次的初始值。要跟著上層變，就直接用傳入的值或 `@Binding`；要整個重來，用 `.id`。
- **把會變的參數傳給 `@StateObject` 的 ViewModel。** 參數只有第一次建立時有效。要在參數改變時重新載入，用 `.task(id:)`。
- **在 view 的 `init` 啟動網路請求或 Task。** struct 會被重複建立，`init` 會一直被執行。請求放在 `.task` 或 ViewModel 內部啟動。
- **把變動很頻繁的 `@Published` 屬性放在一個大物件裡。** 任何一個屬性變動，整頁都跟著重算。拆成小物件，或降低更新頻率。
- **漏注入 `@EnvironmentObject`。** 編譯器不會抓，執行到才 crash，preview 和 `UIHostingController` 的根 view 最容易漏。
- **以為 `@Published` 發出通知時，屬性已經是新值。** `@Published` 是在值變動之前發出通知（`willSet`），所以 `sink` 收到通知的當下，屬性還是舊值；要拿新值，用 `sink` 收到的參數。
- **`@Published` 屬性是 class，卻修改它內部的屬性。** 只有「重新賦值」整個屬性才會通知，改 class 裡面的屬性不會。
- **`@State private var model = Model()` 的建構式很重。** 它的初始值不是 autoclosure，父層每更新一次就會執行一次，多建的會被丟掉。建構式保持輕量，重的工作放到 `.task`。
- **`.onChange(of:)` 的版本差異。** iOS 17 起改成新的寫法（closure 可以拿到舊值與新值，舊的單參數寫法被標為即將淘汰），支援 iOS 15 的專案要使用舊版。

## 面試官想聽到的

1. 能用「誰擁有、誰建立」解釋 `@StateObject` 與 `@ObservedObject` 的差別，並說出 `@ObservedObject` 加預設值會讓狀態被重置的原因。
2. 知道 SwiftUI 的 view 是 struct、會被頻繁重建，真正的狀態由 SwiftUI 另外保管。
3. 能解釋 `ObservableObject` 以物件為單位通知、`@Observable` 以屬性為單位追蹤，並說明 `@Observable` 需要 iOS 17。
4. 在不能用 Observation 的專案裡，知道怎麼縮小觀察範圍、降低更新頻率，避免整頁重算。
5. 能說出 `@EnvironmentObject` 漏注入的風險，以及它和顯式傳參數的取捨。

> [!WARNING]
> 面試官常追問：「如果 ViewModel 需要上層傳進來的參數（例如幣種代號），`@StateObject` 怎麼初始化？」要答出：在 `init` 裡用 `StateObject(wrappedValue:)` 建立，而且參數只有第一次有效；之後參數改變，要用 `.id` 或 `.task(id:)` 處理。

## iOS / Android 對照

| 主題 | iOS（SwiftUI） | Android（Jetpack Compose） |
|---|---|---|
| 畫面自己的狀態 | `@State` | `remember { mutableStateOf(...) }`；要撐過畫面旋轉用 `rememberSaveable` |
| 讓子層修改上層的值 | `@Binding` | state hoisting（狀態提升）：子層收到「值」與 `onValueChange` 兩個參數 |
| 畫面的 ViewModel | `@StateObject` 搭配 ViewModel | `ViewModel` 搭配 `StateFlow`，畫面用 `collectAsStateWithLifecycle()` 收集 |
| 跨很多層傳資料 | `@Environment`、`@EnvironmentObject` | `CompositionLocal`（例如 `LocalContext`） |
| 更新的粒度 | `ObservableObject` 以物件為單位；`@Observable` 以屬性為單位（iOS 17） | 讀取 `State` 的範圍（recompose scope）才會重組，與 Observation 類似；但整個 `UiState` 物件一起傳時，粒度也會變粗 |
| 由別的狀態算出來的值 | computed property | `derivedStateOf` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 屬性包裝器 | property wrapper | プロパティラッパー |
| 狀態 | state | 状態 |
| 綁定 | binding | バインディング |
| 環境 | environment | 環境（Environment） |
| 觀察（Observation 框架） | Observation | オブザベーション |
| 部署目標（最低支援版本） | deployment target | デプロイメントターゲット |
| 合併後只保留最新 | conflation | コンフレーション |
