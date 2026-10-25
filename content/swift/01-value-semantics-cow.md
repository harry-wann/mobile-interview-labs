---
id: swift-value-semantics-cow
title: struct 與 class 怎麼選、Array 為什麼複製很快（COW）
summary: 面試很常從「struct 和 class 差在哪」開始，接著追問「struct 一定比較快嗎」「Array 複製會不會很慢」。這個單元把這幾個問題一個一個講清楚，最後補一條 Swift 特有的規則：同一個變數不能同時被兩個地方修改。
priority: P1
day: 1
tags: [struct, class, cow, arc, inout, exclusivity]
---

## 重點講解

這個單元回答五個面試常見問題：

1. struct 和 class 差在哪？什麼時候用哪個？
2. struct 一定比 class 快嗎？
3. Array 是值型別，為什麼 `var b = a` 不會很慢？（COW）
4. 什麼情況下 COW 會失效，讓程式突然變慢？
5. `inout` 參數為什麼有時會編譯錯誤、甚至執行時 crash？（exclusivity）

### 1. struct 和 class 差在哪？什麼時候用哪個？

最根本的差別是：**複製之後，兩個變數是不是同一個東西。**

```swift
struct PointS { var x = 0 }
final class PointC { var x = 0 }

var s1 = PointS()
var s2 = s1      // 複製出一份新的資料
s2.x = 10
print(s1.x)      // 0：s1 沒有被影響

let c1 = PointC()
let c2 = c1      // 只複製了「指向物件的參考」，c1 和 c2 指向同一個物件
c2.x = 10
print(c1.x)      // 10：改 c2 等於改 c1
```

- **struct、enum 是值型別（value type）**：每個變數都有自己的一份資料，改了不會影響別人。`Array`、`Dictionary`、`String` 也都是值型別。
- **class 是參考型別（reference type）**：變數裡存的是「指向物件的參考」。複製變數只是多一個人指向同一個物件，任何人修改，其他人都看得到。

class 還多了一個能力：可以用 `===` 判斷兩個變數是不是指向**同一個物件**。struct 沒有「同一個」的概念，只能用 `==` 比較內容是否相等。

**怎麼選？** 我的判斷順序是：

1. **預設用 struct**。資料不會被別的地方偷偷改掉，程式比較好推理；傳給其他執行緒時，每個執行緒拿到的是自己的一份，也比較安全。
2. **需要「大家共用同一個、改了大家都要看到」時用 class**。例如 WebSocket 連線管理、快取、ViewModel：你希望所有畫面用的是同一條連線、同一份狀態，而不是各自一份。
3. **需要繼承，或要給 Objective-C 使用（繼承 `NSObject`）時**，只能用 class。

> [!TIP]
> 面試時可以這樣說：「我預設用 struct，因為複製後各自獨立，不會發生共用的資料被意外修改。只有需要大家共用同一個實體的東西，例如連線管理或 ViewModel，我才用 class。」

### 2. struct 一定比 class 快嗎？

常聽到的說法是「struct 放在 stack、class 放在 heap，所以 struct 比較快」。這句話太簡化，面試時這樣回答很容易被追問。比較準確的說法是：

**複製 struct 時，裡面每一個 class 型別的欄位，都要讓 ARC 的引用計數 +1。**

先複習一下 ARC（Automatic Reference Counting）：Swift 用「引用計數」管理 class 物件的記憶體。每多一個地方持有這個物件，計數就 +1（retain）；少一個就 −1（release）；歸零時物件就被釋放。為了讓多個執行緒同時操作也不會算錯，每次 +1／−1 都比一般的加減法慢一些。

所以，同樣是 struct，複製的成本可以差很多：

```swift
// 只有數字：複製就是把 24 bytes 原封不動抄過去，非常便宜
struct Tick {
    var price: Int64
    var qty: Int64
    var time: Int64
}

// 有 4 個 class 欄位：每複製一次，4 個物件的引用計數各 +1，用完再各 −1
struct HeavyQuote {
    var symbol: SymbolInfo      // class
    var exchange: ExchangeInfo  // class
    var tags: Tags              // class
    var meta: Meta              // class
}
```

一般的畫面感覺不出差異。但在**每秒要處理幾千筆資料的地方**，例如交易所的行情推播，`HeavyQuote` 每傳一次就多 8 次 retain／release，累積起來就會在 Instruments 的 Time Profiler 上看得到。

另外兩個常見誤解：

- 「struct 一定在 stack」不正確。struct 被 closure 捕獲、放在 class 的屬性裡、或放進 Array 時，實際上都存在 heap。
- `String` 和 `Array` 雖然是值型別，內部也有一塊放在 heap 的儲存空間（很短的字串除外），所以 struct 裡有 `String` 或 `Array` 欄位時，複製也會有一次 retain。這就是下一段 COW 的由來。

> [!TIP]
> 面試時可以這樣說：「struct 快不快，要看裡面有沒有 class 欄位。只有數字的 struct，複製就是單純的記憶體複製；有很多 class 欄位的 struct，每次複製都會帶來 retain／release 的成本。在每秒更新很多次的地方我會注意這點，並且用 Instruments 量，而不是憑感覺。」

### 3. COW：Array 是值型別，為什麼複製很快？

COW 是 copy-on-write 的縮寫，意思是「**先共用，等到真的要修改時才複製**」。

照理說，`Array` 是值型別，`var b = a` 應該要把整個陣列複製一份。如果陣列有 10 萬個元素，每次賦值都要複製 10 萬個元素，那就太慢了。Swift 的做法是：

1. `Array` 本身是一個很小的 struct，裡面只放一個參考，指向真正存放元素的那塊記憶體（以下稱為 storage）。
2. `var b = a` 時，只複製這個參考，`a` 和 `b` 共用同一塊 storage。不管陣列多大，這一步都很快。
3. 等到其中一個變數要修改時，Array 會先檢查「這塊 storage 是不是只有我在用」：
   - 只有我在用：直接修改，不複製。
   - 還有別人在用：先複製一份自己的 storage，再修改。另一個變數完全不受影響。

```swift
var a = [1, 2, 3]
var b = a        // 很快：a 和 b 共用同一塊 storage
b.append(4)      // storage 有兩個人在用，所以 b 先複製一份，再加入 4
print(a)         // [1, 2, 3]
print(b)         // [1, 2, 3, 4]
b.append(5)      // b 已經有自己的 storage，直接修改，不會再複製
```

對使用者來說，Array 的行為就是值型別：複製後各自獨立。但實際上只有在需要時才真的複製，同時兼顧安全與效能。`Dictionary`、`Set`、`String` 也是同樣的做法。

#### 自己寫的型別，要自己實作 COW

COW 是 `Array` 這些標準型別**自己寫出來的功能**，不是所有 struct 自動都有。如果你寫了一個 struct，裡面放了自己的 class：

```swift
final class Storage { var items: [Int] = [] }

struct MyList {
    var storage = Storage()
    mutating func append(_ x: Int) { storage.items.append(x) }
}

var a = MyList()
var b = a                // 複製 struct，但 storage 是 class，a 和 b 指向同一個 Storage
b.append(1)
print(a.storage.items)   // [1]：a 也被改到了
```

`MyList` 宣告成 struct，行為卻像 class，這是 code review 常抓到的問題。修正方法是自己實作 COW，面試有時會請你現場寫出來：

```swift
final class Storage<Element> {
    var items: [Element]
    init(_ items: [Element]) { self.items = items }
}

struct COWList<Element> {
    private var storage: Storage<Element>
    init(_ items: [Element] = []) { storage = Storage(items) }

    // 每次修改前先呼叫：如果 storage 還有別人在用，就先複製一份給自己
    private mutating func makeUnique() {
        if !isKnownUniquelyReferenced(&storage) {
            storage = Storage(storage.items)
        }
    }

    mutating func append(_ element: Element) {
        makeUnique()
        storage.items.append(element)
    }
}
```

- `isKnownUniquelyReferenced(&storage)` 是 Swift 標準函式庫的函式，回傳「目前是不是只有一個地方用一般的（strong）參考持有這個物件」。`weak` 和 `unowned` 參考不算在內。
- **每一個會修改資料的方法，都要先呼叫 `makeUnique()`**。漏掉一個，就會出現「改到別人的資料」的 bug。
- 這個檢查只代表「這一刻」的狀況。如果同一個變數被多個執行緒同時讀寫，結果不可靠，要另外用 actor 或鎖保護。

> [!TIP]
> 面試時可以這樣說：「Array 是 copy-on-write：賦值時兩個變數共用同一塊 storage，第一次修改時如果發現 storage 被共用，才真的複製。自己寫的 struct 如果包了 class，要自己用 `isKnownUniquelyReferenced` 實作同樣的機制，否則複製出來的兩份會共用同一個物件。」

### 4. 什麼情況下 COW 會失效，讓程式突然變慢？

COW 只保證「沒有被共用時不複製」。只要修改的那一刻，還有另一個地方持有同一塊 storage，就會複製整個陣列。實務上最常遇到的是下面三種情況。

**情況一：透過計算屬性（computed property）修改陣列**

```swift
final class PriceViewModel {
    private var _prices: [Decimal] = []
    var prices: [Decimal] {          // 計算屬性：用 get／set 包住 _prices
        get { _prices }
        set { _prices = newValue }
    }
}

vm.prices.append(p)   // 看起來只是加一筆，實際上每次都複製整個陣列
```

`vm.prices.append(p)` 實際上分成三步：

1. 呼叫 `get`，拿出一份陣列。這時 `_prices` 和拿出來的這份共用同一塊 storage，等於有兩個人在用。
2. 對拿出來的那份呼叫 `append`。因為 storage 被共用，所以先複製整個陣列，再加入新的一筆。
3. 呼叫 `set`，把修改後的陣列寫回 `_prices`。

陣列越大，每次 append 就越慢。本機實測：對一個 5 萬筆的陣列 append 5000 次，用一般的儲存屬性（stored property）約 0.06 ms，透過計算屬性約 45 ms，差了大約 700 倍。

修法：直接用儲存屬性 `private(set) var prices: [Decimal] = []`，外部只能讀；需要修改時，在類別裡提供 `func append(_ p: Decimal)` 這類方法，直接修改原本的陣列。

**情況二：修改前先存了一份舊的**

```swift
let snapshot = items   // snapshot 和 items 共用同一塊 storage
items.append(x)        // storage 被共用，所以這次 append 要先複製整份
```

例如為了做 diff 或「復原」功能，每次修改前都保留一份舊資料，那每次修改都會複製。這時要確認這份複製真的必要，或改成只保留需要的部分。

**情況三：一邊跑迴圈、一邊修改同一個陣列**

```swift
for x in items {          // for-in 迴圈會持有 items 的一份
    items.append(x * 2)   // 第一次 append 時 storage 被共用，複製整份
}
```

> [!TIP]
> 面試時可以這樣說：「COW 不是保證不複製，而是只有在共用的時候才複製。實務上最常見的效能問題，是透過 computed property 修改陣列：`get` 拿出來的那份讓 storage 被共用，每次 append 都變成整份複製。」

### 5. `inout` 與 exclusivity：同一個變數不能同時被兩個地方修改

Swift 有一條記憶體安全規則，官方文件稱為 exclusive access to memory，一般簡稱 **exclusivity**：**一個變數正在被修改的期間，不能同時被其他地方讀取或修改。**

最常碰到這條規則的地方是 `inout` 參數。`inout` 的意思是「把變數借給函式修改，函式結束時再把改好的值寫回去」。借出去的整段期間，這個變數都算是「正在被修改」。

**編譯器看得出來的，直接編譯錯誤：**

```swift
var a = [1, 2]
swap(&a[0], &a[1])   // 編譯錯誤：overlapping accesses to 'a'
a.swapAt(0, 1)       // 正確寫法
```

`swap` 要同時借走 `a[0]` 和 `a[1]` 來修改。對 Swift 來說，修改陣列的任何一個元素，都算是在修改整個陣列 `a`，所以等於同一個變數同時被借給兩個地方修改。

**編譯器看不出來的，執行時檢查，違反就 crash（debug 版本一定會；release 版本經過最佳化後可能不報，不要依賴它）：**

```swift
class Counter {
    var n = 0
    func bump(_ x: inout Int) {
        n += 1      // 修改 self.n
        x += 1      // 這裡的 x 其實也是 self.n
    }
}

let c = Counter()
c.bump(&c.n)   // 執行時 crash：Simultaneous accesses to ..., but modification requires exclusive access
```

`c.n` 被借給 `bump` 修改的期間，`bump` 裡面又直接修改 `n`，同一個變數同時被改兩次。class 的屬性可能從任何地方被存取，編譯器沒辦法事先判斷，所以改在執行時檢查。

兩個要記住的重點：

- 兩個地方同時「讀」是允許的，只有牽涉到「修改」才會衝突。
- **這個檢查抓不到多執行緒的問題。** 它只檢查同一個執行緒裡的重疊存取。兩個執行緒同時修改同一個屬性（data race），要用 actor、鎖或 `Mutex` 保護；Swift 6 的 strict concurrency 檢查會在編譯時幫你找出這類問題。

> [!TIP]
> 面試時可以這樣說：「exclusivity 是 Swift 的記憶體安全規則：一個變數在被修改的期間，不能同時被別的地方存取。編譯器能判斷的會直接報錯，例如 `swap(&a[0], &a[1])`；class 屬性這種只能在執行時檢查，違反會 crash。它和多執行緒的 data race 是兩件事，多執行緒要靠 actor 或鎖。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift01ValueSemantics**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift01-ValueSemantics/`，解答在 `labs/ios/Solution/Swift/Swift01-ValueSemantics/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：按了「取消」，價格卻被改掉（第 1 題）

**檔案**：`L01_StructOrClass.swift`

**情境**：價格提醒清單，點一筆打開編輯畫面，改了價格之後按「取消」。

**怎麼寫**

1. **Bug 版**：`PriceAlert` 已經用 class 寫好。在 `run` 裡用 `let draft = alerts[0]` 把清單上的這一筆交給編輯畫面，再把 `draft.price` 改成 16000000。
2. **修正版**：把 `PriceAlert` 改成 struct，改完會發現 `draft` 要用 `var` 才能修改。取消時什麼都不做；示範儲存時，把 `draft` 寫回 `alerts[0]`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 還沒按儲存，清單上的價格就變成 16000000；按取消也改不回來 |
| 修正版 | 取消後清單維持 15000000，按儲存才變成 16000000 |

**為什麼**：class 複製的是參考，`draft` 和清單上的那一筆是同一個物件。struct 複製的是整份資料，`draft` 是自己的一份，改了不會影響清單。

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L01_StructOrClass.swift#fixed
```

### 演練 2：struct 裡放 class，複製就變慢（第 2 題）

**檔案**：`L02_StructCopyCost.swift`

**情境**：行情推播的每一筆報價都要經過「解析 → 過濾 → 換算」三個函式，每經過一個函式就複製一次，共 100 萬筆。

**怎麼寫**

1. **Bug 版**：在 `Quote` 裡加上 4 個 class 欄位：`symbol: SymbolInfo`、`exchange: ExchangeInfo`、`tags: Tags`、`meta: Meta`（這 4 個 class 已經寫好），再把建立 `base` 的地方補上參數。
2. **修正版**：`Quote` 只放數字，幣種和交易所改用編號 `symbolID: Int32`、`exchangeID: Int16`，要顯示名稱時再查表。
3. 兩個版本都跑完後，把 Scheme 的 Build Configuration 改成 Release（Product › Scheme › Edit Scheme › Run），再各跑一次。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | log 顯示 class 欄位 4 個；比修正版慢，Debug 下約慢 1.5 倍，Release 下差到 20 倍以上（本機實測 51 ms 對 2 ms） |
| 修正版 | class 欄位 0 個，複製只是搬數字，幾乎不花時間 |

**為什麼**：複製 struct 時，每個 class 欄位的引用計數都要 +1，用完再 −1（retain／release）。只放數字的 struct 沒有這個成本。用 Instruments 的 Time Profiler 錄 Bug 版，可以看到 `swift_retain`、`swift_release` 占了大部分時間。

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L02_StructCopyCost.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L02_StructCopyCost.swift#fixed
```

### 演練 3：快照被推播偷偷改掉（第 3 題）

**檔案**：`L03_CustomCOW.swift`

**情境**：訂單簿 `OrderBook` 是 struct，裡面包一個 class `Storage` 存「價格 → 數量」。畫面存一份快照顯示，之後的推播只更新 `live`。

**怎麼寫**

1. **Bug 版**：`set` 直接寫 `storage.levels[price] = qty`，不管 storage 是不是被共用。
2. **修正版**：寫 `makeUnique()`：如果 `isKnownUniquelyReferenced(&storage)` 是 false，就用目前的 `levels` 建一個新的 `Storage` 給自己，並把 `copies` 加 1。`set` 先呼叫 `makeUnique()` 再修改。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 快照上 100 元的數量從 5 變成 9：外表是 struct，行為卻像 class |
| 修正版 | 快照維持 5；`live` 只在第一次修改時複製 1 次，之後不再複製 |

**為什麼**：COW 是 `Array` 這些標準型別自己寫出來的功能，自己的 struct 包了 class 就要自己做：修改前先檢查 storage 是不是只有自己在用，有別人在用才複製。

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L03_CustomCOW.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L03_CustomCOW.swift#fixed
```

### 演練 4：append 一筆，卻複製整個陣列（第 4 題）

**檔案**：`L04_COWBreaks.swift`

**情境**：ViewModel 存了 5 萬筆成交價，推播每來一筆就 append 一筆，共 5000 筆。

**怎麼寫**

1. **Bug 版**：把 `prices` 改成計算屬性：`get { _prices }`、`set { _prices = newValue }`。演練流程會用 `vm.prices.append(…)` 加資料。
2. **修正版**：`prices` 改成 `private(set) var` 的儲存屬性（初始值是 5 萬筆），`append(_:)` 方法裡直接 `prices.append(price)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | append 5000 筆要幾十毫秒（本機模擬器約 45 ms），陣列越大越慢；用 Instruments 的 Allocations 會看到大量約 400 KB 的配置 |
| 修正版 | 不到 1 毫秒 |

**為什麼**：`vm.prices.append(x)` 會先 get 出一份，這份和 `_prices` 共用 storage，append 時發現被共用，只好整個複製一次再 set 回去。改成儲存屬性加上修改用的方法，就直接改原本的陣列。

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L04_COWBreaks.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L04_COWBreaks.swift#fixed
```

### 演練 5：inout 期間又讀同一個值，App 當掉（第 5 題）

**檔案**：`L05_Exclusivity.swift`

**情境**：用 `inout` 扣手續費，扣的同時呼叫 callback 通知畫面，畫面會讀餘額。

**怎麼寫**

1. **Bug 版**：`applyFee(to:onChange:)` 裡先 `amount -= 10` 再呼叫 `onChange()`；呼叫端寫 `applyFee(to: &wallet.balance) { log("通知畫面：餘額 \(wallet.balance)") }`。從 Xcode 執行，看 App 停在哪一行。
2. **修正版**：`applyFee(to:)` 只扣款、不呼叫外部程式；呼叫完之後，下一行再通知畫面。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | App 當掉，主控台：`Simultaneous accesses to 0x…, but modification requires exclusive access.` |
| 修正版 | 依序印出扣款前 1000、通知畫面 990、扣款後 990 |

**為什麼**：`wallet.balance` 借給 `applyFee` 修改的整段期間都算「正在被修改」，callback 在這段期間讀它，就是重疊存取。class 的屬性編譯器看不出來，所以在執行時檢查、直接當掉。修法是讓修改先結束，再通知畫面。

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L05_Exclusivity.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift01-ValueSemantics/L05_Exclusivity.swift#fixed
```

## 常見陷阱

- **以為 struct 一定比 class 快。** struct 裡如果有很多 class 欄位，每次複製都要 retain／release。選 struct 或 class，看的是需不需要共用同一個實體；效能問題要用 Instruments 量。
- **透過計算屬性修改大陣列。** `get` 拿出來的那份讓 storage 被共用，每次修改都整份複製。改用儲存屬性，並在類別裡提供修改方法。
- **struct 裡放了自己的 class，卻沒有實作 COW。** 複製出來的兩份共用同一個物件，一邊改了另一邊也跟著變。
- **以為 `let` 宣告的陣列，裡面的物件就不能改。** `let orders: [Order]`（`Order` 是 class）只代表不能增減或替換元素；`orders[0].status = .filled` 仍然可以執行，因為改的是物件本身，不是陣列。
- **以為 exclusivity 檢查能抓到多執行緒問題。** 它只管同一個執行緒裡的重疊存取，多執行緒要另外保護。

## 面試官想聽到的

1. 選 struct 或 class 的理由是「需不需要共用同一個實體」，而不是背「struct 在 stack 比較快」。
2. 知道 struct 的複製成本，取決於裡面有幾個 class 欄位。
3. 能用自己的話解釋 COW，最好能手寫 `makeUnique()`。
4. 知道 COW 會在哪些情況失效，特別是透過計算屬性修改陣列。
5. 知道 exclusivity 是什麼，也知道它不能取代多執行緒的保護。

> [!FIELD]
> 在一個社交 App 裡，iOS 與 Android 都用 Realm 當本機資料庫。Realm 的物件是 class，而且只能在建立它的執行緒使用；如果直接把 Realm 物件交給畫面，就等於讓很多地方共用同一個會變動的物件。iOS 版把 Realm 的存取包在 actor 裡，對外只回傳複製出來的資料；Android 對應的做法是 `copyFromRealm(...)`。
> 面試時可以用這個例子說明「為什麼我偏好用複製出來的值，而不是共用同一個物件」。

## iOS / Android 對照

| 主題 | iOS（Swift） | Android（Kotlin） |
|---|---|---|
| 值型別 | `struct`、`enum`；`Array` 等標準集合有 COW，複製很快 | Kotlin 沒有像 struct 這樣的多欄位值型別。`data class` 仍然是 class（參考型別），`copy()` 只複製一層，裡面的物件仍然共用。`value class` 只能包一個欄位 |
| 不可變 | `let` 宣告的 struct，整份資料都不能改 | `val` 只代表變數不能重新指定；`List` 只是唯讀介面，底層的資料仍可能被別人改。要真正不可變，用 `kotlinx.collections.immutable` |
| 避免共用會變動的資料 | 值型別、actor | 不可變資料、只在單一執行緒修改、`Mutex` |
| 同時修改的保護 | exclusivity（編譯時＋執行時檢查） | 沒有對應機制，靠 `synchronized`、`Mutex` 或只在單一執行緒修改 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 值型別 | value type | 値型 |
| 參考型別 | reference type | 参照型 |
| 引用計數 | reference counting（ARC） | 参照カウント |
| 寫入時才複製 | copy-on-write（COW） | コピーオンライト |
| 計算屬性／儲存屬性 | computed property／stored property | 計算プロパティ／格納プロパティ |
| 同一個變數不能同時被兩處修改的規則 | exclusivity（exclusive access to memory） | 排他アクセス |
