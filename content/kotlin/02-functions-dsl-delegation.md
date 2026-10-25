---
id: kotlin-functions-dsl-delegation
title: Kotlin 的函式與語法糖：extension、scope function、DSL、by 委派、lazy、BigDecimal 的 `/`
summary: 這個單元把 Kotlin 面試常問的語法機制講清楚：extension 為什麼沒有多型、五個 scope function 怎麼選、DSL 怎麼防止寫錯地方、by 委派有什麼陷阱、lazy 的三種模式差在哪，以及 BigDecimal 的除法為什麼會默默算出 0。
priority: P1
day: 2
tags: [kotlin, extension, scope-functions, dsl, delegation, lazy, operator, bigdecimal]
---

## 重點講解

這個單元回答六個面試常見問題：

1. extension function 是什麼？為什麼它沒有多型？
2. `let`、`run`、`with`、`apply`、`also` 五個 scope function 怎麼選？
3. 什麼是 lambda with receiver？DSL 怎麼做，`@DslMarker` 是做什麼的？
4. `by` 委派是什麼？class delegation 有什麼陷阱？
5. `lazy` 的三種模式差在哪？初始化丟例外會怎樣？
6. `BigDecimal("1") / BigDecimal("3")` 為什麼是 `0`？

### 1. extension function 是什麼？為什麼它沒有多型？

extension function（擴充函式）是「不改原本的類別，在外面幫它加一個函式」，例如 `fun String.toPrice()`。但它不是真的塞進那個類別，而是編譯器產生的靜態函式，呼叫 `b.name()` 時，編譯器**只看變數宣告的型別**就決定要呼叫哪一個，不看執行時實際的物件是誰。這個行為叫 static dispatch（靜態分派），所以 extension 不會有多型（polymorphism，意思是：變數宣告成父類別、實際是子類別時，執行的是子類別覆寫過的版本）。

```kotlin
open class Base
class Derived : Base()

fun Base.name() = "base-ext"
fun Derived.name() = "derived-ext"

val b: Base = Derived()   // 宣告的型別是 Base，實際的物件是 Derived
println(b.name())         // 輸出：base-ext（編譯器只看宣告的型別 Base）
println(Derived().name()) // 輸出：derived-ext
```

如果是成員函式（寫在類別裡面的 `open fun`），第一行會印出子類別的版本，這才是多型。所以「想用 extension 達到覆寫的效果」是行不通的，需要多型就用介面或成員函式。

另外幾個一定要知道的規則：

- **成員永遠贏過 extension。** 類別裡已經有 `fun hi()`，你再寫一個同名同參數的 extension `fun Greeter.hi()`，extension 永遠不會被呼叫（編譯器只給警告）。參數不同（多載）則是另一個函式，可以呼叫：

```kotlin
class Greeter { fun hi() = "member" }
fun Greeter.hi() = "ext"                 // 被成員蓋掉，永遠不會被呼叫
fun Greeter.hi(times: Int) = "ext-$times"

println(Greeter().hi())    // 輸出：member
println(Greeter().hi(2))   // 輸出：ext-2
```

- extension 不能存取類別的 `private` 成員，因為它本質上是外面的函式。
- extension property（擴充屬性）不能有 backing field（屬性背後真正存值的欄位），所以只能是每次都重新計算的屬性。
- 可以用 nullable 的 receiver（receiver 就是函式裡 `this` 指到的物件）：`fun String?.orDash() = this ?: "--"`，對 null 呼叫也安全。

**為什麼這會影響測試？** 從 Java 看，extension 就是一個靜態方法。如果 extension 裡面直接用到全域單例（例如 `Context.showError()` 裡呼叫某個 `Analytics.shared`），測試時沒辦法把它換成假的（也就是 mock）。所以需要被替換的行為，要寫成注入的類別，extension 只放不依賴外部狀態的純函式，例如格式化、轉換。

另一個風險是命名空間汙染：`String.toPrice()`、`Context.dp()` 這類 extension 整個專案都看得到，名稱含糊就很難知道在做什麼。建議用 `internal` 限制範圍、依領域分檔。

> [!TIP]
> 面試時可以這樣說：「extension 是靜態分派，編譯時只看宣告的型別，所以沒有多型。我只把純函式（格式化、轉換）寫成 extension，需要被 mock 或替換的行為，我用注入的類別。」

### 2. `let`、`run`、`with`、`apply`、`also` 怎麼選？

這五個函式叫 scope function，用途是「在一個區塊裡操作某個物件」。它們只有兩個差別：**區塊裡用 `this` 還是 `it` 代表那個物件**，以及**最後回傳物件自己還是區塊最後一行的結果**。

| 函式 | 區塊內用 | 回傳 | 典型用途 |
|---|---|---|---|
| `let` | `it` | 區塊最後一行的結果 | 處理 nullable：`x?.let { }`；轉換成別的值 |
| `run` | `this` | 區塊最後一行的結果 | 以物件為上下文，算出一個新值 |
| `with(x)` | `this` | 區塊最後一行的結果 | 和 `run` 一樣，但寫成 `with(x) { }`，不是 extension |
| `apply` | `this` | 物件自己 | 設定物件的屬性（builder 風格） |
| `also` | `it` | 物件自己 | 順便做件事：log、驗證 |

```kotlin
val req = OrderRequest().apply {     // apply：區塊內用 this，回傳 OrderRequest 本身
    symbol = "btcjpy"                //   直接寫屬性名稱，等於 this.symbol
    qty = BigDecimal("0.01")
}
val notional = req.run { price * qty }   // run：區塊內用 this，回傳最後一行 price * qty 的結果
req.also { println("下單：${it.symbol}") } // also：區塊內用 it，回傳 req 本身，適合 log
```

選擇時我自己用三條簡單的準則：設定物件用 `apply`、順便做副作用（log）用 `also`、處理 nullable 用 `let`，並且**區塊不要巢狀超過一層**，因為 `this` 一多，看不出現在的 `this` 是哪個物件。

有兩個常見的陷阱：

**陷阱一：`x?.let { A } ?: B` 不等於 if-else。** 如果 `A` 的結果剛好是 null，`?:` 也會走 `B`：

```kotlin
val x: String? = "a"
val r = x?.let { null as String? } ?: "else"   // x 不是 null，但 let 區塊回傳 null
println(r)   // 輸出：else（以為 x 不是 null 就不會走到 else，結果走到了）
```

需要明確的兩條路徑時，直接用 `if`。

**陷阱二：同名的區域變數會蓋掉 receiver 的屬性。** 在 `apply { }` 裡面，如果外面剛好有同名的區域變數或參數，寫的是那個區域變數，不是物件的屬性：

```kotlin
class Form { var name = "init" }

var name = "local"
val form = Form().apply { name = "BTC" }   // 這個 name 指的是外面的區域變數，不是 form.name
println(form.name)   // 輸出：init（物件的屬性沒被改到）
println(name)        // 輸出：BTC（被改的是區域變數）
```

要設定物件的屬性，明確寫 `this.name = ...`。

> [!TIP]
> 面試時可以這樣說：「設定物件用 `apply`、順便做副作用用 `also`、處理 nullable 用 `let`，區塊不巢狀超過一層。另外 `x?.let { } ?: y` 在 `let` 區塊回傳 null 的時候會誤走 `y`，需要明確分支時我用 `if`。」

### 3. lambda with receiver 與 DSL：`@DslMarker` 是做什麼的？

lambda with receiver 是指型別寫成 `T.() -> Unit` 的 lambda：呼叫它的時候，裡面的 `this` 就是 `T`，所以可以直接寫 `T` 的成員，不用加前綴。DSL（Domain-Specific Language，領域專用語言）就是靠它做出來的：用 Kotlin 語法寫出像設定檔的結構。Gradle 的 `build.gradle.kts`、標準函式庫的 `buildString`、Compose 的 `LazyColumn { items(...) }` 都是這個機制。

```kotlin
val s = buildString {      // buildString 的參數型別是 StringBuilder.() -> Unit
    append("BTC")          // 這裡的 this 是 StringBuilder，所以直接呼叫 append
    append("/JPY")
}
println(s)   // 輸出：BTC/JPY
```

自己做一個下單的 DSL 時，每一層都是一個 builder（用來一步步組出物件的輔助類別）：

```kotlin
@DslMarker annotation class OrderDsl   // 自己定義一個標註，下面標在每個 builder 上

class Leg(val price: BigDecimal, val qty: BigDecimal)
class Order(val symbol: String, val legs: List<Leg>)

@OrderDsl class LegBuilder {
    var price = BigDecimal.ZERO
    var qty = BigDecimal.ZERO
    fun build() = Leg(price, qty)
}

@OrderDsl class OrderBuilder {
    var symbol = ""
    private val legs = mutableListOf<Leg>()
    fun leg(block: LegBuilder.() -> Unit) { legs += LegBuilder().apply(block).build() }
    fun build() = Order(symbol, legs.toList())
}

fun order(block: OrderBuilder.() -> Unit) = OrderBuilder().apply(block).build()

val o = order {
    symbol = "btcjpy"
    leg {
        price = BigDecimal("100")
        qty = BigDecimal("0.5")
        // symbol = "oops"   // 編譯錯誤：不能在 leg 區塊裡隱含地使用外層 OrderBuilder 的 symbol
    }
}
```

**為什麼需要 `@DslMarker`？** 巢狀的 lambda with receiver 會把所有外層的 `this` 都放進可用範圍：在 `leg { }` 裡面找不到 `symbol` 時，Kotlin 會往外層的 `OrderBuilder` 找，找到了就默默用它。沒有 `@DslMarker` 的話，上面那行 `symbol = "oops"` 會編譯通過，卻是改到外層訂單的 `symbol`，錯誤很難發現。把同一個 `@DslMarker` 標註標在所有 builder 類別上之後，區塊裡只有最近的 receiver 能被隱含使用，要用外層的必須明確寫 `this@order.symbol`。

DSL 的取捨：讀起來很清楚，但 IDE 的跳轉、除錯比較不方便，錯誤訊息也比較奇怪。只在「巢狀、宣告式」的結構使用，例如 UI、設定、測試資料，不要什麼都做成 DSL。

> [!TIP]
> 面試時可以這樣說：「DSL 靠 lambda with receiver，裡面可以直接寫 receiver 的成員。巢狀的 DSL 我會加 `@DslMarker`，不然內層區塊可以隱含地用到外層 builder 的成員，寫錯地方編譯器也不會提醒。」

### 4. `by` 委派是什麼？class delegation 有什麼陷阱？

delegation（委派）就是「這件事交給別人做」，Kotlin 用 `by` 這個關鍵字表示。分兩種：對類別是「把所有方法轉給另一個物件」，對屬性是「把讀寫交給另一個物件處理」。

**class delegation（類別委派）：** `class Wrapper(r: Repo) : Repo by r`，意思是編譯器會幫你產生所有轉發到 `r` 的方法，你只需要覆寫想改的那幾個。常用在 decorator（包一層、加上功能，例如快取）：

```kotlin
interface Repo {
    fun price(): Int
    fun double(): Int
}
class Real : Repo {
    override fun price() = 10
    override fun double() = price() * 2    // 這裡的 price() 是 Real 自己的 this.price()
}
class Wrapper(r: Repo) : Repo by r {
    override fun price() = 99              // 只覆寫 price()
}

println(Wrapper(Real()).price())    // 輸出：99
println(Wrapper(Real()).double())   // 輸出：20，不是 198
```

陷阱就在第二行：`double()` 沒有被覆寫，編譯器產生的轉發方法直接交給 `Real` 去執行，而 `Real.double()` 裡面呼叫的 `price()` 是 `Real` 自己的，它完全不知道外面還有一層 `Wrapper`，所以用的是 `10`。這和繼承不同：如果 `Wrapper` 是繼承 `Real`，父類別裡呼叫的 `price()` 會走到子類別覆寫的版本。所以用 delegation 做快取或 decorator 時，要確認被包的類別內部沒有依賴「自己的方法被覆寫」。

**property delegation（屬性委派）：** `var theme: String by 某個物件`，意思是這個屬性的讀取和寫入，會轉給那個物件的 `getValue`／`setValue` 處理。標準函式庫有現成的：`by lazy`（下一個問題）、`Delegates.observable`（值改變時通知）、`Delegates.notNull`（宣告時先不給值、之後才設定，還沒設定就讀會丟例外）。也可以自己寫，例如把 SharedPreferences 的欄位包成一般的屬性：

```kotlin
class PrefString(
    private val store: MutableMap<String, String>,   // 範例用 Map 代替 SharedPreferences
    private val key: String,
    private val default: String
) : ReadWriteProperty<Any?, String> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String = store[key] ?: default
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { store[key] = value }
}

class Prefs(store: MutableMap<String, String>) {
    var currency: String by PrefString(store, "currency", "JPY")
}

val store = mutableMapOf<String, String>()
val p = Prefs(store)
println(p.currency)   // 輸出：JPY（還沒存過，用預設值）
p.currency = "USD"    // 實際上呼叫 setValue，寫進 store
println(store)        // 輸出：{currency=USD}
```

自己寫委派類別時，`getValue`／`setValue` 前面要加 `operator`（像上面這樣實作 `ReadWriteProperty` 介面時，介面已經宣告成 operator，用 `override` 就好），這是 Kotlin 的約定：照名字寫，編譯器就會在讀寫屬性時呼叫它們（原因見第 6 個問題的 operator overloading）。要注意的是，每次存取委派屬性都會經過一次 `getValue`，在每秒執行很多次的地方（例如行情更新），用 `Delegates.observable` 會有額外成本。

> [!TIP]
> 面試時可以這樣說：「`by` 是委派：類別層級是把方法轉給另一個物件，屬性層級是把讀寫交給 `getValue`／`setValue`。class delegation 要注意被包的物件內部呼叫自己的方法時，不會走到我覆寫的版本，這點和繼承不一樣。」

### 5. `lazy` 的三種模式差在哪？初始化丟例外會怎樣？

`by lazy { ... }` 的意思是「第一次用到這個屬性時才執行大括號裡的程式碼（叫初始化器），結果存起來，之後都用同一個值」。適合建立成本高、不一定會用到的東西，例如解析器、資料庫存取物件。

多個執行緒同時第一次存取時怎麼辦？`lazy` 有三種模式，用參數指定：`by lazy(LazyThreadSafetyMode.NONE) { ... }`。

| 模式 | 行為 | 什麼時候用 |
|---|---|---|
| `SYNCHRONIZED`（預設） | 加鎖，初始化器最多只執行一次 | 不確定會不會有多個執行緒存取 |
| `PUBLICATION` | 多個執行緒可能**同時**執行初始化器，但只有第一個寫入的結果會被採用，大家最後看到同一個值 | 初始化器沒有副作用、不想用鎖 |
| `NONE` | 完全不保證執行緒安全，沒有任何鎖 | 確定只在單一執行緒存取，例如只在主執行緒用的 UI 輔助物件 |

`PUBLICATION` 要特別小心：雖然最後大家看到同一個值，但初始化器可能已經被執行了好幾次，所以裡面不能做有副作用的事（例如開連線、註冊 listener）。

**初始化丟出例外時，`lazy` 不會把失敗記起來**，下次存取會重新執行初始化器：

```kotlin
var n = 0
val v by lazy {
    n++
    if (n < 2) error("boom")   // 第一次執行時丟例外
    n
}

try { println(v) } catch (e: IllegalStateException) { println("fail") }   // 輸出：fail
println(v)   // 輸出：2（沒有記住失敗，重新執行初始化器，這次成功，結果被快取）
println(v)   // 輸出：2（已經快取，不會重跑）
println(n)   // 輸出：2
```

這讓「失敗後重試」很自然，但如果初始化器很重（讀檔、建立連線）又一直失敗，每次存取都會再付一次成本。

另外，`lazy` 在初始化完成之前會一直持有那段初始化器。如果初始化器用到 `Activity`，這段時間 `Activity` 都被抓著，要小心記憶體洩漏。在 Android 上，預設模式如果在主執行緒做很重的初始化，第一次顯示畫面時會卡頓。

> [!TIP]
> 面試時可以這樣說：「`lazy` 預設是執行緒安全的，用鎖保證初始化器只執行一次。`PUBLICATION` 允許重複執行初始化器，所以不能有副作用；`NONE` 完全不保證，只在確定單一執行緒時用。另外初始化器丟例外時不會快取，下次存取會重跑。」

### 6. `BigDecimal("1") / BigDecimal("3")` 為什麼是 `0`？

先講背景：operator overloading（運算子重載）是讓 `+`、`[]` 這些符號可以用在自己的型別上。在 Kotlin 裡，這些符號背後其實是呼叫固定名稱的函式，`a + b` 是 `a.plus(b)`，`a[i]` 是 `a.get(i)`，`a / b` 是 `a.div(b)`，函式前面要加 `operator`；`a == b` 呼叫的是 `equals`，`a < b` 呼叫的是 `compareTo`。

```kotlin
data class Money(val amount: BigDecimal) {
    operator fun plus(other: Money) = Money(amount + other.amount)
}
println(Money(BigDecimal("1.5")) + Money(BigDecimal("2")))   // 輸出：Money(amount=3.5)
```

Kotlin 的標準函式庫替 `BigDecimal` 提供了 `+ - * / %`，寫起來很方便，但 **`/` 有一個很隱蔽的坑**：它等於 `divide(other, RoundingMode.HALF_EVEN)`，結果的 scale 會跟**被除數**一樣。scale 是 `BigDecimal` 小數點後有幾位數字，`1` 的 scale 是 0，`1.00` 的 scale 是 2。HALF_EVEN 是銀行家捨入：剛好在中間時（例如 2.5），捨入到偶數那一邊。

```kotlin
println(BigDecimal("1") / BigDecimal("3"))      // 輸出：0（被除數 scale 是 0，結果只保留整數）
println(BigDecimal("1.00") / BigDecimal("3"))   // 輸出：0.33（被除數 scale 是 2）
println(BigDecimal("10") / BigDecimal("4"))     // 輸出：2（2.5 捨入到偶數的 2）
println(BigDecimal("1").divide(BigDecimal("3"), 8, RoundingMode.HALF_EVEN))   // 輸出：0.33333333
```

這個坑更危險的地方是：它**不會丟例外**，只是默默給你一個錯的答案。直接呼叫沒有指定捨入的 `divide(x)`，遇到除不盡（例如 1 除以 3）至少會丟 `ArithmeticException`，馬上發現。所以金額的除法一律明確寫出小數位數和捨入方向：`divide(x, scale, RoundingMode.XXX)`。

另一個容易混淆的地方是比較：`==` 呼叫 `equals`，會連 scale 一起比；`<`、`>=` 呼叫 `compareTo`，只比數值大小：

```kotlin
println(BigDecimal("1.0") == BigDecimal("1.00"))   // 輸出：false（scale 不同）
println(BigDecimal("1.0") >= BigDecimal("1.00"))   // 輸出：true（數值相同）
```

> [!TIP]
> 面試時可以這樣說：「Kotlin 的 `BigDecimal` 的 `/` 會用被除數的小數位數做銀行家捨入，`1` 除以 `3` 得到 `0`，而且不會丟例外。所以金額除法我一律寫 `divide(x, scale, RoundingMode)`，把小數位數和捨入方向寫清楚；比較數值大小用 `compareTo`，不用 `==`。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin02Functions**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin02-functions/`，解答在 `labs/android/solution/kotlin/kotlin02-functions/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：市價單的手續費少收了（第 1 題）

**檔案**：`L01_ExtensionDispatch.kt`

**情境**：一般委託的手續費是成交金額的 0.1%，市價單是 0.3%。清單 `List<Order>` 裡放了一張一般委託（BTC_JPY）和一張市價單（ETH_JPY），成交金額都是 1,000,000 日圓。

**怎麼寫**

1. **Bug 版**：寫兩個 extension：`fun Order.fee(): Long = notional / 1000` 和 `fun MarketOrder.fee(): Long = notional * 3 / 1000`，以為市價單會用到自己的版本。
2. **修正版**：`Order` 裡寫成員函式 `open fun fee()`，`MarketOrder` 用 `override fun fee()` 覆寫。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | ETH_JPY 的手續費是 1000（應該是 3000），「手續費合計 2000」 |
| 修正版 | ETH_JPY 的手續費是 3000，「手續費合計 4000」 |

**為什麼**：extension 是編譯器產生的靜態函式，呼叫 `o.fee()` 時只看變數宣告的型別。`orders` 的元素宣告成 `Order`，所以固定選 `Order.fee()`，不管實際的物件是不是 `MarketOrder`。需要多型就用成員函式或介面。

```lab-solution
android/solution/kotlin/kotlin02-functions/L01_ExtensionDispatch.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L01_ExtensionDispatch.kt#fixed
```

### 演練 2：apply 設定了數量，送出的數量卻是 0（第 2 題）

**檔案**：`L02_ApplyShadowing.kt`

**情境**：下單畫面把輸入框目前的文字存在區域變數 `var qty = "0"`，建立 `OrderRequest` 時用 `apply { }` 設定它的 `symbol` 和 `qty`。

**怎麼寫**

1. **Bug 版**：在 `OrderRequest().apply { }` 裡寫 `symbol = "BTC_JPY"` 和 `qty = "0.5"`（不加 `this`）。
2. **修正版**：把 `qty = "0.5"` 改成 `this.qty = "0.5"`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「送出的 symbol = BTC_JPY」，但「送出的 qty = 0」、「外面的區域變數 qty = 0.5」：被改到的是區域變數 |
| 修正版 | 「送出的 qty = 0.5」，「外面的區域變數 qty = 0」 |

**為什麼**：`apply` 裡面，同名的區域變數優先於 receiver 的屬性。`symbol` 沒問題，是因為外面沒有叫 `symbol` 的區域變數；`qty` 有，所以 `qty = "0.5"` 改到的是外面的區域變數，編譯器完全不會提醒。要設定物件的屬性，明確寫 `this.qty`。

```lab-solution
android/solution/kotlin/kotlin02-functions/L02_ApplyShadowing.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L02_ApplyShadowing.kt#fixed
```

### 演練 3：已經有處理中的訂單，又下了一張（第 2 題）

**檔案**：`L03_LetElvis.kt`

**情境**：使用者送出訂單後網路很慢，又按了一次下單。系統先找有沒有處理中的訂單：有就沿用，沒有才送出新的。處理中的訂單還沒收到交易所的確認，所以 `exchangeId` 是 `null`。演練會先跑一次沒有處理中訂單的下單，再跑一次有處理中訂單的下單。

**怎麼寫**

1. **Bug 版**：`val result = pending?.let { it.exchangeId } ?: placeOrder()`。
2. **修正版**：改用 `if (pending != null)` 寫兩條明確的路徑：有處理中的訂單就沿用 `pending.exchangeId`，如果是 `null` 就顯示 `"處理中（等交易所確認）"`，不下單；沒有才 `placeOrder()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「又按一次：結果：新訂單，共送出 1 張新訂單」：重複下單 |
| 修正版 | 第一次正常送出；「又按一次：結果：處理中（等交易所確認），共送出 0 張新訂單」 |

**為什麼**：`x?.let { A } ?: B` 不等於 if-else。`pending` 明明不是 `null`，但 `let` 區塊的結果 `exchangeId` 是 `null`，`?:` 看到 `null` 就走右邊，等於又下了一張單。需要明確的兩條路徑時，直接用 `if`。

```lab-solution
android/solution/kotlin/kotlin02-functions/L03_LetElvis.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L03_LetElvis.kt#fixed
```

### 演練 4：在 leg 裡寫錯，整張訂單的 symbol 被改掉（第 3 題）

**檔案**：`L04_DslMarker.kt`

**情境**：用 DSL 組訂單：`order { symbol = …; leg { price = …; qty = … } }`。`OrderBuilder` 有 `symbol` 和 `leg { }`，`LegBuilder` 只有 `price` 和 `qty`。builder 與 `order { }` 函式都已經寫好。

**怎麼寫**

1. **Bug 版**：不加 `@DslMarker`，在 `leg { }` 裡手滑寫 `symbol = "ETH_JPY"`。
2. **修正版**：已經寫好 `@DslMarker` 標註 `OrderDsl`，你在 `OrderBuilder` 和 `LegBuilder` 上各標一個 `@OrderDsl`。寫完後試著在 `leg { }` 裡取消註解 `symbol = "ETH_JPY"`，看 Android Studio 怎麼報錯。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「訂單 symbol = ETH_JPY，1 筆 leg」：leg 裡的 `symbol = …` 默默改掉整張訂單的 symbol |
| 修正版 | 「訂單 symbol = BTC_JPY，1 筆 leg」，「OrderBuilder 標了 @OrderDsl：true」「LegBuilder 標了 @OrderDsl：true」 |

**為什麼**：巢狀的 lambda with receiver 會把所有外層的 `this` 都放進可用範圍。`leg { }` 裡找不到 `symbol` 時，Kotlin 會往外找到 `OrderBuilder.symbol` 並默默使用。每一層 builder 都標上同一個 `@DslMarker` 標註之後，區塊裡只能隱含地使用最近的一層 receiver，要用外層必須明確寫 `this@order.symbol`。

```lab-solution
android/solution/kotlin/kotlin02-functions/L04_DslMarker.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L04_DslMarker.kt#fixed
```

### 演練 5：加了快取，API 還是被打了好幾次（第 4 題）

**檔案**：`L05_ClassDelegation.kt`

**情境**：`PriceApi` 有 `price()`（打 API 取價格）和 `priceWithFee()`（含手續費的價格，內部呼叫 `price()`）。用 `CachedApi` 包一層：`class CachedApi(private val inner: PriceApi) : PriceApi by inner`。`RealApi` 每呼叫一次 `price()` 就算打了一次 API。

**怎麼寫**

1. **Bug 版**：`CachedApi` 只覆寫 `price()`：`cache.getOrPut(symbol) { inner.price(symbol) }`。
2. **修正版**：`price()` 一樣加快取，再覆寫 `priceWithFee()`：`price(symbol) * 1001 / 1000`，呼叫的是 `CachedApi` 自己的 `price()`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 取 3 次 `price()` 後 API 打了 1 次；再取 3 次 `priceWithFee()` 後變成 4 次 |
| 修正版 | 兩種取法各 3 次，API 一共只打 1 次 |

**為什麼**：沒有覆寫的 `priceWithFee()`，編譯器產生的轉發方法直接交給 `RealApi` 執行，而 `RealApi.priceWithFee()` 裡呼叫的 `price()` 是 `RealApi` 自己的，它不知道外面還有一層快取。這和繼承不同：如果 `CachedApi` 繼承 `RealApi`，父類別裡呼叫的 `price()` 會走到子類別覆寫的版本。

```lab-solution
android/solution/kotlin/kotlin02-functions/L05_ClassDelegation.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L05_ClassDelegation.kt#fixed
```

### 演練 6：兩個執行緒同時第一次用，建了兩條連線（第 5 題）

**檔案**：`L06_LazyThreadMode.kt`

**情境**：`Gateway` 用 `by lazy` 延後建立交易所的連線，建立一次要 100 ms。畫面載入時，行情和下單兩個地方同時（在 `Dispatchers.Default` 的不同執行緒）第一次讀 `socket`。

**怎麼寫**

1. **Bug 版**：`val socket by lazy(LazyThreadSafetyMode.NONE) { dialer.connect() }`。
2. **修正版**：`val socket by lazy { dialer.connect() }`（預設是 `SYNCHRONIZED`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「建立連線 #1」「建立連線 #2」都出現；行情與下單拿到不同的 socket；「共建立 2 條連線」 |
| 修正版 | 只有「建立連線 #1」；兩邊都拿到 socket-1；「共建立 1 條連線」 |

**為什麼**：`NONE` 完全不保證執行緒安全，兩個執行緒都看到「還沒初始化」，各自執行一次初始化器，多出來的那條連線沒有人管。預設的 `SYNCHRONIZED` 加鎖，初始化器最多只執行一次。`PUBLICATION` 則允許多個執行緒同時執行初始化器，最後大家看到同一個值，但初始化器裡不能有副作用。

```lab-solution
android/solution/kotlin/kotlin02-functions/L06_LazyThreadMode.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L06_LazyThreadMode.kt#fixed
```

### 演練 7：載入設定失敗後，每次讀取都再打一次伺服器（第 5 題）

**檔案**：`L07_LazyException.kt`

**情境**：畫面一次繪製會讀 3 次 `config`。`config` 用 `by lazy` 向伺服器要，這時伺服器連不上，`loadFromServer()` 丟出 `IOException`（已經寫好）。

**怎麼寫**

1. **Bug 版**：`val config by lazy { remote.loadFromServer() }`。
2. **修正版**：`val config by lazy { runCatching { remote.loadFromServer() } }`，成功或失敗都存起來；讀取時用 `onFailure` 處理。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「向伺服器要設定」出現 3 次，最後一行「共向伺服器要了 3 次」 |
| 修正版 | 只出現 1 次「向伺服器要設定」，3 次讀取都印出「讀取失敗」，最後一行「共向伺服器要了 1 次」 |

**為什麼**：`lazy` 的初始化器丟出例外時，不會把失敗記起來，下次讀取會重新執行初始化器。這讓「失敗後重試」很自然，但如果初始化器很重（讀檔、打 API）又一直失敗，每次讀取都要再付一次成本。把成功或失敗一起快取，要不要重試由呼叫端明確決定。

```lab-solution
android/solution/kotlin/kotlin02-functions/L07_LazyException.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L07_LazyException.kt#fixed
```

### 演練 8：1 BTC 平分給 3 個子帳戶，每人拿到 0（第 6 題）

**檔案**：`L08_BigDecimalDivide.kt`

**情境**：使用者要把 1 BTC 平均分給 3 個子帳戶，BTC 最小單位是 0.00000001（小數 8 位）。`report()` 會印出每個子帳戶拿到多少、三個加起來多少、原帳戶剩下多少，已經寫好。

**怎麼寫**

1. **Bug 版**：`val share = total / accounts`。
2. **修正版**：`val share = total.divide(accounts, 8, RoundingMode.DOWN)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「每個子帳戶：0」「三個子帳戶合計：0」「原帳戶剩下：1」，沒有任何例外 |
| 修正版 | 「每個子帳戶：0.33333333」「三個子帳戶合計：0.99999999」「原帳戶剩下：0.00000001」 |

**為什麼**：Kotlin 的 `BigDecimal.div` 等於 `divide(other, RoundingMode.HALF_EVEN)`，結果的 scale 和被除數一樣。`1` 的 scale 是 0，所以結果只保留整數，得到 `0`，而且不會丟例外。金額的除法要明確寫出小數位數和捨入方向；分帳用無條件捨去（`RoundingMode.DOWN`），才不會分出去的比原本有的還多。

```lab-solution
android/solution/kotlin/kotlin02-functions/L08_BigDecimalDivide.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin02-functions/L08_BigDecimalDivide.kt#fixed
```

## 常見陷阱

- **把行為寫成 extension，想靠子類別覆寫。** 不會生效，extension 只看宣告的型別。需要多型就用介面或成員函式。
- **extension 裡直接用全域單例。** 測試時換不掉，只能放純函式；需要替換的行為用注入的類別。
- **`apply { }` 裡的同名區域變數蓋掉物件的屬性。** `name = "BTC"` 改到的是外面的區域變數，物件的屬性沒變。要設定物件的屬性，明確寫 `this.name`。
- **`x?.let { A } ?: B` 當成 if-else。** `A` 回傳 null 時，`B` 也會被執行。
- **在 `by lazy` 選錯模式。** 會被不同執行緒讀取的欄位用了 `NONE`，可能建立出多個實例；或在 Android 主執行緒用預設模式做很重的初始化，造成畫面卡頓。
- **以為 class delegation 的行為和繼承一樣。** 被包的物件內部呼叫自己的方法，不會走到你覆寫的版本。
- **對 `BigDecimal` 直接用 `/`。** 小數位數取被除數的，1 除以 3 變成 0 而且不會報錯。要寫 `divide(x, scale, mode)`。
- **用 `==` 比較 `BigDecimal`。** `1.0` 和 `1.00` 不相等，比大小要用 `compareTo`。

## 面試官想聽到的

1. 能解釋 extension 是靜態分派（編譯時只看宣告的型別），以及為什麼它在測試與多型上受限。
2. 五個 scope function 有明確的選用準則，而不是隨手 `apply` 或 `let` 全包。
3. 設計 DSL 時知道 `@DslMarker` 的用途，也知道 DSL 的維護成本。
4. 講得出 `lazy` 三種模式的差異與例外行為，能依場景選。
5. 知道 class delegation 的陷阱：被包的物件呼叫自己的方法，不會走到你的覆寫。
6. 金額除法不直接用 `/`，而是明確指定小數位數與捨入方向。

> [!FIELD]
> 在一個社交 App 的 Android 版裡，用 Hilt 提供 `@IoDispatcher`、`@MainDispatcher`、`@DefaultDispatcher`，也就是 dispatcher 是用注入的，測試時可以換成測試用的版本。被問到「extension 很難 mock，那怎麼辦」時，這是很好的實際例子。在一個即時行情 App 的 Android 版裡，則把價格的顯示格式化寫成 `Double.cryptoString()` 這種純函式的 extension，不依賴外部狀態，適合寫成 extension。

## iOS / Android 對照

| 主題 | Kotlin | Swift |
|---|---|---|
| 擴充既有型別 | extension function，靜態分派 | `extension`；protocol extension 裡沒有宣告在 protocol 本身的方法，同樣是靜態分派 |
| scope function | `let`、`run`、`with`、`apply`、`also` | 沒有對應的內建函式。處理 Optional 用 `map`、`flatMap`、`if let`；設定物件通常直接寫在初始化後面 |
| builder DSL | lambda with receiver、`@DslMarker` | result builder（`@resultBuilder`，SwiftUI 的 `ViewBuilder` 就是用它） |
| 屬性委派 | `by lazy`、`Delegates.observable`、自訂 `ReadWriteProperty` | `lazy var`、property wrapper（`@State`、自訂 `@propertyWrapper`） |
| lazy 的執行緒安全 | 預設用鎖保證只初始化一次，可選 `PUBLICATION`、`NONE` | `lazy var` 不保證執行緒安全：多個執行緒同時第一次存取，可能初始化不只一次 |
| 類別層級的轉發 | `class X : Y by y` 由編譯器產生轉發方法 | 語言沒有支援，要手寫轉發 |
| 金額運算 | `BigDecimal`，`/` 的結果取被除數的小數位數，`1 / 3` 會變成 0 | `Decimal` 的 `/` 會保留很多位有效數字，不會變成 0；要固定小數位數與捨入方向，再用 `NSDecimalRound` 處理 |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 擴充函式 | extension function | 拡張関数 |
| 靜態分派 | static dispatch | 静的ディスパッチ |
| 多型 | polymorphism | ポリモーフィズム |
| scope function（在區塊裡操作物件的 `let`／`apply` 這類函式） | scope function | スコープ関数 |
| 領域專用語言 | DSL（Domain-Specific Language） | ドメイン固有言語（DSL） |
| 委派 | delegation（`by`） | 委譲 |
| 運算子重載 | operator overloading | 演算子オーバーロード |
| 小數位數 | scale | スケール |
