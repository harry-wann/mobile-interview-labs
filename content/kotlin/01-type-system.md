---
id: kotlin-type-system
title: Kotlin 型別系統：Java 傳來的 null、泛型 in／out、inline 與 reified、value class、sealed
summary: Kotlin 面試最常從型別系統開始問。這個單元用具體例子說明：Java 傳進來的值為什麼會突然 NPE、泛型的 in／out 是什麼意思、reified 解決什麼問題、value class 為什麼不是零成本，以及為什麼訂單狀態要用 sealed 來寫。
priority: P1
day: 2
tags: [kotlin, null-safety, generics, variance, inline, value-class, sealed, data-class]
---

## 重點講解

這個單元回答六個面試常見問題：

1. Java 傳進來的值，為什麼會在 Kotlin 裡突然 NPE？（platform type）
2. 泛型的 `out`、`in`、`*` 是什麼意思？為什麼 `MutableList<Int>` 不能當 `MutableList<Number>` 用？（variance）
3. `inline`、`reified`、`crossinline`、`noinline` 各自解決什麼問題？
4. value class 是什麼？它是零成本嗎？
5. 為什麼訂單狀態要用 `sealed` 來寫？新增一種狀態時會發生什麼事？
6. data class 的 `equals` 有哪些容易踩的坑？

### 1. Java 傳進來的值，為什麼會在 Kotlin 裡突然 NPE？

Kotlin 的 null safety 只能保護「Kotlin 自己寫的程式碼」。Java 的型別沒有「這個值可不可能是 null」的資訊，所以 Kotlin 把它當成 platform type（平台型別），編譯器不檢查，NPE（NullPointerException，對 null 呼叫方法時丟出的例外）要等到你「用到它的那一行」才發生，離真正的來源可能很遠。

先複習：Kotlin 把 `String`（絕對不是 null）和 `String?`（可能是 null）分成兩種型別，編譯器強迫你處理 `String?`。但 Java 只有一個 `String`，它可能是 null 也可能不是。如果 Java 端沒有用 `@Nullable` 或 `@NonNull` 標註，Kotlin 就不知道該怎麼辦，只好把它標成 `String!`。這個 `!` 只會出現在 IDE 的提示和錯誤訊息裡，你不能自己在程式碼裡寫，意思是「我不知道，你自己決定要當 `String` 還是 `String?`」。

```kotlin
// Java SDK 端：public String getNickname() { ... }，沒有任何標註，實際上可能回傳 null
val n = sdk.nickname            // 推斷出的型別是 String!（platform type），編譯器不攔你
println(n.length)               // 如果 nickname 實際是 null，NPE 在「這一行」才爆

val safe: String? = sdk.nickname   // 在邊界處明確宣告成可為 null
println(safe?.length ?: 0)         // 輸出 0：null 時用預設值

val strict: String = sdk.nickname  // 指派給非 null 型別：編譯器在這裡插入檢查，null 就立刻丟例外
```

為什麼 NPE 離來源很遠？因為 `val n = sdk.nickname` 這行不會出錯，你把 `n` 傳進三層函式、存進 ViewModel，最後在畫面上呼叫 `n.length` 才爆，要往回追好幾個地方才找得到是 SDK 回了 null。

**對策是在邊界處就轉成明確的型別。** 所有 Java、第三方 SDK、Retrofit 回來的值，在 data 層就明確宣告成 `String?` 或 `String`，不要讓 `String!` 流進 domain 層（業務邏輯那一層）。自己維護的 Java 程式碼，也要補上 `@Nullable`／`@NonNull`，Kotlin 看得懂這些標註。

跟 null 有關的另外三個工具，面試也常一起問：

- **`!!`**：告訴編譯器「我保證不是 null」，如果是 null 就在這行丟 NPE。等於把檢查從編譯時延到執行時，少用。
- **`lateinit`**：讓非 null 的 `var` 屬性可以先不給值，之後才初始化（例如 Hilt 注入、`onCreate` 裡才建立的物件）。如果還沒初始化就讀取，會丟 `UninitializedPropertyAccessException`；可以用 `::x.isInitialized` 先判斷。
- **smart cast**：你檢查過 `x != null` 之後，編譯器自動把 `x` 當成非 null 使用。但只有在編譯器「能證明檢查完到使用之間，值不會被改掉」時才有效：

```kotlin
class Session { var token: String? = null }

fun printLength(s: Session) {
    if (s.token != null) {
        // println(s.token.length)  // 編譯錯誤：token 是 var，檢查完之後可能被別的執行緒改掉
        val t = s.token ?: return   // 先存成區域變數，這個區域變數不會被別人改
        println(t.length)           // t 已經確定是 String，可以使用
    }
}
```

`val` 區域變數可以 smart cast；`var` 屬性、有自訂 getter 的屬性、其他 module 宣告的 `val` 都不行，解法就是先存成區域變數。

> [!TIP]
> 面試時可以這樣說：「Java 或第三方 SDK 回來、沒有 null 標註的值，Kotlin 會當成 platform type，編譯器不檢查，NPE 會在使用的那一行才爆。所以我在 data 層就把它轉成明確的 `T?` 或 `T`，不讓 `String!` 流進 domain 層。」

### 2. 泛型的 `out`、`in`、`*` 是什麼意思？

這組關鍵字是為了回答一個問題：**`Box<Int>` 可不可以當成 `Box<Number>` 來用？** 答案取決於這個 `Box` 是「只會讀」、「只會寫」，還是「又讀又寫」，這個性質叫做 variance。

先看一個具體例子。標準函式庫的 `List` 宣告成 `List<out E>`，`out` 的意思是「這個型別只會把 E 拿出來給你，不會讓你放進去」：

```kotlin
fun printAll(items: List<Any>) { for (i in items) println(i) }

val names: List<String> = listOf("BTC", "ETH")
printAll(names)   // 可以：List<String> 能當 List<Any> 傳進去。輸出：BTC、ETH
```

這樣做安全，是因為 `List` 只能讀，沒有 `add` 可以呼叫，不可能有人透過 `List<Any>` 把一個 `Int` 塞進原本只該放 `String` 的清單。這種「只讀所以可以放寬」叫 covariant（共變），用 `out` 標記，典型例子有 `List<out E>`、`Flow<out T>`。

反過來，`MutableList` 既讀又寫，所以**不能**放寬：

```kotlin
val ints: MutableList<Int> = mutableListOf(1, 2)
val nums: MutableList<Number> = ints   // 編譯錯誤：型別不符
// 如果這行被允許，就能 nums.add(1.5)，ints 裡面就混進一個 Double，
// 之後 ints[2] 當 Int 取出來就會出錯。
```

這就是 invariant（不變）：`MutableList<Int>` 和 `MutableList<Number>` 完全不能互換。

反方向是 `in`，意思是「這個型別只會接收 T，不會把 T 拿出來」，叫 contravariant（逆變）：

```kotlin
interface Sink<in T> { fun accept(item: T) }

val numberSink: Sink<Number> = object : Sink<Number> {
    override fun accept(item: Number) = println(item)
}
val intSink: Sink<Int> = numberSink   // 可以：能處理任何 Number 的，一定也能處理 Int
intSink.accept(5)                     // 輸出：5
```

標準函式庫裡的例子是 `Comparable<in T>`：能比較任何 `Number` 的比較器，當然也能拿來比較 `Int`。

| 寫法 | 白話意思 | 例子 |
|---|---|---|
| `out T`（covariant） | 只會把 T 拿出來，`Box<Int>` 可以當 `Box<Number>` 用 | `List<out E>`、`Flow<out T>` |
| `in T`（contravariant） | 只會接收 T，`Sink<Number>` 可以當 `Sink<Int>` 用 | `Comparable<in T>` |
| 沒有標記（invariant） | 又讀又寫，兩邊都不能放寬 | `MutableList<E>` |
| `*`（star projection，星號投影） | 「我不知道裡面是什麼型別」：可以讀，讀出來是 `Any?`；不能寫 | `MutableList<*>` |

**宣告處與使用處。** 上面的 `out`／`in` 寫在類別宣告上，叫 declaration-site variance。如果某個類別本身沒有標（像 `MutableList`），你也可以只在「用的那一行」限制它，叫 use-site projection（使用處投影）：

```kotlin
fun firstOf(list: MutableList<out Number>): Number = list[0]   // 在這個函式裡只讀，所以接受 MutableList<Int>
fun fillOne(list: MutableList<in Int>) { list.add(1) }         // 在這個函式裡只寫，所以接受 MutableList<Number>
```

Java 有一個對應的口訣 PECS（Producer Extends, Consumer Super）：只讀的用 `? extends T`（對應 Kotlin 的 `out T`），只寫的用 `? super T`（對應 `in T`）。要注意的是，Java 介面在 Kotlin 裡預設是 invariant，例如 `java.util.Comparator<T>`，所以 `Comparator<Number>` 不能直接當 `Comparator<Int>` 用，要在使用處寫 `Comparator<in Int>`。

> [!TIP]
> 面試時可以這樣說：「`List` 只會讀，所以 `List<Int>` 可以當 `List<Number>` 用，這是 `out`；`MutableList` 既讀又寫，放寬就可能被塞進 `Double`，所以是 invariant。反過來，只會接收的 `Comparable<in T>`，能比較 `Number` 的就能比較 `Int`，這是 `in`。」

### 3. `inline`、`reified`、`crossinline`、`noinline` 各自解決什麼問題？

`inline` 解決的是效能問題，`reified` 解決的是「泛型型別在執行時消失」的問題，`crossinline` 和 `noinline` 是處理 `inline` 帶來的限制。先從 `inline` 講起。

**`inline`：把函式和它的 lambda 直接貼進呼叫的地方。** 傳給一般（非 inline）高階函式的 lambda，在執行時會變成一個物件；如果 lambda 用到外面的變數，每次呼叫都要建立一個新物件。例如自己寫的 `fun repeatTask(block: () -> Unit)`，在每秒呼叫很多次的地方，這些物件會帶來額外的記憶體負擔。`inline` 讓編譯器把函式內容連同 lambda 的內容直接複製到呼叫處，就不用建立物件。標準函式庫的 `forEach`、`map`、`let`、`apply` 都是 `inline`，所以用它們不會有這個成本。

`inline` 還有一個副作用：lambda 裡面可以寫 non-local return，也就是 `return` 會直接結束「外層函式」，而不只是離開 lambda：

```kotlin
fun findFirstBtc(symbols: List<String>): String? {
    symbols.forEach {                 // forEach 是 inline 函式
        if (it == "BTC") return it    // non-local return：直接結束 findFirstBtc
    }
    return null
}
println(findFirstBtc(listOf("ETH", "BTC")))   // 輸出：BTC
```

**`reified`：讓泛型的 `T` 在執行時還看得到。** 泛型有一個限制叫 type erasure（型別擦除）：`List<String>` 和 `List<Int>` 在編譯完之後，執行時都只是 `List`，`T` 到底是什麼型別已經不見了。所以一般的泛型函式裡，不能寫 `x is T`。`inline` 函式因為會把內容貼到呼叫處，那裡的 `T` 是明確的型別，所以可以搭配 `reified` 讓 `T` 在函式內當成真正的型別使用：

```kotlin
inline fun <reified T> Any?.asOrNull(): T? = this as? T   // reified：這裡的 as? 是真的會檢查

val x: Any = "BTC"
println(x.asOrNull<String>())   // 輸出：BTC
println(x.asOrNull<Int>())      // 輸出：null，因為 x 不是 Int

fun <T> Any?.unsafeAs(): T? = this as? T   // 沒有 reified：編譯器只會警告「unchecked cast」，不會真的檢查
// 函式內部不檢查，所以不會回傳 null；例外出現在呼叫端真的把結果當 Int 用的地方，
// 例如 val n: Int? = x.unsafeAs<Int>() 這一行就會丟 ClassCastException
```

JSON 解析函式庫的 `fromJson<T>()` 就是用這個機制：呼叫端寫 `fromJson<Order>(json)`，函式內部才拿得到 `Order` 這個型別。

**`crossinline`：lambda 會被丟到別的地方執行，不能 `return`。** 如果 inline 函式把收到的 lambda 放進另一個 `Runnable` 或執行緒，lambda 執行的時候，外層函式可能早就結束了，這時 `return` 沒有地方可以跳回去，所以編譯器要求你標 `crossinline`，明確禁止 non-local return：

```kotlin
inline fun runLater(crossinline block: () -> Unit) {
    Thread { block() }.start()   // block 被放進 Runnable，在另一個執行緒執行
}
fun f() {
    runLater {
        println("hi")
        // return   // 編譯錯誤：'return' is prohibited here
    }
}
```

**`noinline`：這個 lambda 要被存起來，不能展開。** `inline` 會把 lambda 貼進呼叫處，lambda 就不再是一個物件。如果你要把 lambda 存進變數、或當作值傳給別的函式，就需要它真的是一個物件，這時要標 `noinline`：

```kotlin
var pending: (() -> Unit)? = null
inline fun track(noinline onDone: () -> Unit, action: () -> Unit) {
    action()
    pending = onDone   // 存起來需要真的物件，所以 onDone 要標 noinline
}
```

最後提醒：不要把很大的函式標成 `inline`，因為內容會被複製到每個呼叫的地方，編譯出來的程式會變大。`inline` 適合「接收 lambda 的小函式」。

> [!TIP]
> 面試時可以這樣說：「`reified` 解決泛型在執行時被擦除的問題，必須搭配 `inline`。`crossinline` 用在 lambda 會被丟到別的執行緒或物件裡執行的時候，禁止 `return`。`noinline` 用在 lambda 要被存起來或當值傳遞的時候。」

### 4. value class 是什麼？它是零成本嗎？

value class 是用來「包一個值，讓它在編譯時變成獨立型別」的語法，目的是防止參數傳錯。它**不是零成本**：被當成 `Any`、介面型別或泛型參數使用時，會多配置一個物件。

先看它解決的問題。交易所 App 裡，訂單編號、交易對代號、帳戶編號全都是 `String`，函式有兩個 `String` 參數時，順序傳反了編譯器不會提醒，執行時才會出現怪結果。把它們包成 value class：

```kotlin
@JvmInline
value class OrderId(val raw: String)
@JvmInline
value class Symbol(val raw: String)

fun cancel(orderId: OrderId, symbol: Symbol) = println("cancel ${orderId.raw} ${symbol.raw}")

cancel(OrderId("o-1"), Symbol("BTC_JPY"))   // 輸出：cancel o-1 BTC_JPY
// cancel(Symbol("BTC_JPY"), OrderId("o-1")) // 編譯錯誤：argument type mismatch
```

和普通的 class 比，value class 的好處是：編譯器盡量在執行時把它還原成底層的值（這裡就是 `String`），不用每個都建立一個 `OrderId` 物件，所以通常比包一層 class 省記憶體。

**但是會有 boxing。** boxing 的意思是：本來直接存成底層的值，卻被迫多包一層成真正的物件。value class 在這些情況會 boxing：被當成 `Any`、被當成介面型別、被放進泛型（`List<OrderId>`、`Flow<Price>`、`StateFlow<Price>`）。原因是泛型在執行時只認得物件，沒辦法直接存底層的值。在行情這種每秒大量資料的地方，要用 profiler（效能分析工具）量過再決定，不要假設它沒有成本。

另外三個要知道的限制：

- value class 只能有一個屬性，可以實作介面，但不能繼承其他 class。
- 編譯器會改掉接收 value class 的函式在 Java 看到的名稱，所以要給 Java 呼叫的公開函式，參數不建議用 value class。
- `@JvmInline value class` 是 Kotlin 1.5 起的正式寫法。

還有一個常見的誤解：value class 只負責「擋傳錯參數」，不負責運算規則。金額底層仍然要用 `BigDecimal`（以字串建立，避免浮點數誤差），捨入規則另外處理。

> [!TIP]
> 面試時可以這樣說：「我會把 `Price`、`Quantity`、`Symbol`、`OrderId` 做成 value class，函式簽名帶著語意，編譯時就能擋掉傳錯參數。但它不是零成本，放進 `List` 或 `Flow` 這類泛型時會 boxing，每秒更新很多次的行情資料我會先量。金額運算仍然用 `BigDecimal`。」

### 5. 為什麼訂單狀態要用 `sealed` 來寫？

`sealed` 的意思是：**這個型別有哪些子型別，在編譯時就全部確定了。** 因為編譯器知道所有可能，`when` 就可以要求你每一種都處理到，漏掉任何一種就編譯錯誤，這叫 exhaustive check（窮盡檢查）。

先看一個沒有 sealed 的寫法：用 `enum` 加上一堆可能是 null 的欄位。

```kotlin
enum class Status { NEW, PARTIALLY_FILLED, FILLED, REJECTED }
data class Order(val status: Status, val filledQty: BigDecimal?, val rejectReason: String?)
```

這個寫法允許「`FILLED` 但 `filledQty` 是 null」、「`NEW` 卻有 `rejectReason`」這些不合理的組合，每個使用的地方都要自己檢查 null。用 sealed 把每個狀態需要的資料綁在一起：

```kotlin
sealed interface OrderState {
    data object New : OrderState                                         // 沒有附帶資料的狀態用 data object
    data class PartiallyFilled(val filledQty: BigDecimal) : OrderState   // 部分成交：一定有已成交量
    data object Filled : OrderState
    data class Rejected(val reason: String) : OrderState                 // 被拒絕：一定有原因
}

fun label(s: OrderState): String = when (s) {   // 沒有 else：每個子型別都要寫到
    OrderState.New -> "已送出"
    is OrderState.PartiallyFilled -> "部分成交 ${s.filledQty}"   // 這裡 s 自動變成 PartiallyFilled，可以直接讀 filledQty
    OrderState.Filled -> "全部成交"
    is OrderState.Rejected -> "被拒絕：${s.reason}"
}
println(label(OrderState.Rejected("餘額不足")))   // 輸出：被拒絕：餘額不足
```

現在「已成交卻沒有成交量」的組合根本寫不出來。`data object` 是 Kotlin 1.9 起的正式寫法，專門給「只有一個實例、沒有資料」的狀態使用。

**新增一種狀態時會發生什麼？** 假設後端新增 `Expired`（已過期）：

- 同一次編譯裡，每個沒有 `else` 的 `when` 都會編譯錯誤，編譯器告訴你所有要補的地方。Kotlin 1.7 起，sealed、enum、Boolean 的 `when` 敘述（只執行、不回傳值的 `when`）也必須窮盡，不再只限於拿來回傳值的 `when`。
- 這就是為什麼 `when` 不要隨手寫 `else`：寫了 `else`，新增狀態時編譯器就不會提醒你。
- 例外是「跨 module 而且消費端沒有重新編譯」。module 就是 Gradle 的一個模組。如果 library（例如獨立發布的 SDK）先改好了，使用它的 App 沒有重新編譯，舊的 `when` 不認得新子型別，會在執行時丟 `NoWhenBranchMatchedException`。所以公開給外部使用的 sealed 階層，新增子型別要當成會讓舊程式壞掉的改動，或預先留一個 `Unknown` 子型別接住還不認識的狀態。

另外，sealed 的子型別必須和它在同一個 package、同一個 module。

> [!TIP]
> 面試時可以這樣說：「訂單狀態我用 sealed interface，`PartiallyFilled` 帶已成交量、`Rejected` 帶原因，『已成交卻沒有成交量』這種不合理的組合根本寫不出來。`when` 不寫 `else`，新增狀態時編譯器會告訴我所有要改的地方。」

### 6. data class 的 `equals` 有哪些容易踩的坑？

data class 會根據**主建構子**（class 名稱後面括號裡的屬性）自動產生 `equals`、`hashCode`、`toString`、`copy`。所有坑都來自同一件事：自動產生的函式只看主建構子的屬性，而且 `equals` 是逐個屬性呼叫它們自己的 `equals`。

```kotlin
import java.math.BigDecimal

data class Quote(val symbol: String, val price: BigDecimal) {
    var seen: Int = 0   // 宣告在 body 裡，不在主建構子的括號內
}
data class Blob(val bytes: ByteArray)

val a = Quote("BTC", BigDecimal("1.0"))
val b = Quote("BTC", BigDecimal("1.00"))
println(a == b)                          // false：BigDecimal.equals 連小數位數都比
println(a.price.compareTo(b.price) == 0) // true：要比數值大小，用 compareTo

a.seen = 3
val c = a.copy()
println(c.seen)                          // 0：body 裡的屬性不會被 copy 帶過去
println(a == c)                          // true：seen 不參與 equals，但兩個物件的狀態其實不同
println(Blob(byteArrayOf(1)) == Blob(byteArrayOf(1)))   // false：ByteArray 比的是「是不是同一個物件」
```

逐項說明：

- **body 裡的屬性不參與 `equals`、`hashCode`、`toString`、`copy`。** `copy()` 之後它會回到初始值，`a == c` 卻是 `true`，這很容易造成誤判。
- **`BigDecimal` 的 `equals` 連 scale（小數位數）都比**，`1.0` 和 `1.00` 不相等。交易所 App 裡，後端回傳 `"1.0"`、本機算出 `"1.00"`，比較結果就會是 `false`。要比數值大小用 `compareTo`。
- **`ByteArray`、`Array` 屬性用參考比較**（比的是是不是同一個物件，不是內容），內容一樣也是 `false`。要比內容，就自己覆寫 `equals`／`hashCode`，或改用 `List`。
- **可變的 data class 當 `HashMap` 的 key，改欄位後就再也找不到了。** 因為 key 存進去時是依當時的 `hashCode` 放進某個位置，欄位改了以後 `hashCode` 變了，用新的值找就找不到。作為 key 的物件要用不可變的（全部 `val`）。

最後一個和型別轉換有關的習慣：`as` 轉型失敗會丟 `ClassCastException`，`as?` 失敗時回傳 null。外部來的資料（JSON、deep link 參數）一律用 `as?` 加上預設處理，不要用 `as`。

> [!TIP]
> 面試時可以這樣說：「data class 的 `equals` 只看主建構子的屬性。所以 body 裡的屬性不參與比較，`ByteArray` 是比參考，`BigDecimal` 會連 scale 一起比，`1.0` 和 `1.00` 不相等，金額比較我會用 `compareTo`。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin01Types**。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin01-types/`，解答在 `labs/android/solution/kotlin/kotlin01-types/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。

### 演練 1：SDK 回了 null，畫面顯示時才 NPE（第 1 題）

**檔案**：`L01_PlatformType.kt`（旁邊的 `SdkProfile.java` 是模擬的第三方 SDK，不用改）

**情境**：App 用第三方 SDK（Java 寫的）取得會員資料。使用者還沒設定暱稱時，`getNickname()` 回傳 `null`，但 SDK 沒有任何 `@Nullable` 標註。資料層 `ProfileRepository` 把暱稱交給 `ProfileViewModel`，畫面層 `render` 再用 `nickname.length` 顯示。會員 A 有暱稱 `harry`，會員 B 沒有。

**怎麼寫**

1. **Bug 版**：`ProfileRepository.nickname()` 寫成 `fun nickname() = sdk.nickname`，不寫回傳型別，也不處理 null。Kotlin 推斷出 `String!`（platform type），一路流到 `ProfileViewModel`。
2. **修正版**：在資料層（邊界）明確寫回傳型別 `String`，用 `sdk.nickname ?: "（未設定）"` 把 null 換成預設值。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 會員 A 正常顯示「歡迎，harry（5 個字）」；會員 B 先印「資料層取得完成，沒有出錯」，接著才印「畫面顯示時 NullPointerException」 |
| 修正版 | 會員 B 顯示「歡迎，（未設定）（5 個字）」，沒有任何例外 |

**為什麼**：Java 的型別沒有「可不可能是 null」的資訊，Kotlin 只好把它當成 platform type，編譯器不檢查，NPE 要等到真的使用那個值的那一行才發生。你把 `String!` 存進 ViewModel、傳進畫面層都不會出錯，離 SDK 回 null 的地方越遠越難追。在邊界處明確寫成 `String?` 或 `String`，編譯器就會替你守住後面的每一層。

```lab-solution
android/solution/kotlin/kotlin01-types/L01_PlatformType.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L01_PlatformType.kt#fixed
```

### 演練 2：硬轉型後，排序數量時 ClassCastException（第 2 題）

**檔案**：`L02_Variance.kt`

**情境**：下單畫面的數量是 `MutableList<Int>`（單位：張），內容 `[3, 1, 2]`。要把手續費 `0.5` 加進清單，算「含手續費」的總量。

**怎麼寫**

1. **Bug 版**：`withFee(amounts: MutableList<Number>, fee: Double)` 裡 `amounts.add(fee)`。呼叫時編譯器不讓 `MutableList<Int>` 傳進去，用 `lots as MutableList<Number>` 強制轉型繞過（加 `@Suppress("UNCHECKED_CAST")`）。之後印 `lots.sum()`，再用 `try` 包住 `lots.sorted()`。
2. **修正版**：`withFee` 只讀，參數改成 `List<Number>`，回傳 `amounts + fee`（新的清單）。`List<out E>` 可以放寬，`MutableList<Int>` 直接傳進去，不用轉型。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 含手續費後的 lots 是 `[3, 1, 2, 0.5]`；「加總：6」（0.5 默默變成 0，沒有任何錯誤）；排序時印出「排序時 ClassCastException」 |
| 修正版 | 含手續費後是 `[3, 1, 2, 0.5]`（新的清單）；lots 的加總是 6，排序成 `[1, 2, 3]`，沒有例外 |

**為什麼**：`MutableList` 又讀又寫，所以是 invariant，編譯器不讓你放寬。用 `as` 強制轉型只是讓編譯器閉嘴：`Double` 真的被塞進 `Int` 的清單，加總時用 `Number` 的 `intValue()` 取值，所以不會出錯、只是少算；排序時才需要比較 `Int` 和 `Double`，這時才丟出 `ClassCastException`。函式如果只讀，就用 `List<out E>`（`List` 本來就是），不需要轉型。

```lab-solution
android/solution/kotlin/kotlin01-types/L02_Variance.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L02_Variance.kt#fixed
```

### 演練 3：泛型取值沒檢查，用到才 ClassCastException（第 3 題）

**檔案**：`L03_Reified.kt`

**情境**：App 把後端回傳的 JSON 解析成 `Map<String, Any?>`，再用自己寫的 `getAs` 取出欄位。後端把 `amount` 從數字 `0.5` 改成字串 `"0.5"`（交易所常見：為了避免浮點數誤差，改用字串傳金額）。

**怎麼寫**

1. **Bug 版**：寫 `fun <T> Map<String, Any?>.getAs(key: String): T? = this[key] as? T`，沒有 `inline`、沒有 `reified`（編譯器會警告 unchecked cast，加 `@Suppress("UNCHECKED_CAST")`）。
2. **修正版**：改成 `inline fun <reified T> Map<String, Any?>.getAs(key: String): T? = this[key] as? T`。型別不對時回傳 `null`，呼叫端再改用 `getAs<String>` 取字串、用 `BigDecimal` 解析。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | `symbol = BTC_JPY` 正常；取 `amount` 時印出「取 amount 時 ClassCastException」（字串被當成 `Double` 傳出來） |
| 修正版 | `getAs<Double>("amount")` 回傳 `null`，印出「amount 不是數字，改用字串解析：0.5」 |

**為什麼**：泛型有 type erasure（型別擦除），執行時 `T` 已經不見了，`as? T` 沒辦法真的檢查，字串就被原樣當成 `Double` 傳出去，直到呼叫端用到它的那一行才 `ClassCastException`。`inline` 會把函式內容貼進呼叫處，那裡的 `T` 是明確的型別；`reified` 讓 `T` 在函式裡可以當成真正的型別用，`as? T` 才會真的檢查。

```lab-solution
android/solution/kotlin/kotlin01-types/L03_Reified.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L03_Reified.kt#fixed
```

### 演練 4：value class 放進 List，記憶體大好幾倍（第 4 題）

**檔案**：`L04_ValueClassBoxing.kt`

**情境**：行情畫面要把最近 30 萬筆成交價存在記憶體裡畫 K 線。為了防止價格和數量傳錯，價格包成 `@JvmInline value class TickPrice(val raw: Long)`。`measure` 會強制 GC 之後量這份資料佔了多少記憶體，已經寫好。

**怎麼寫**

1. **Bug 版**：在 `measure { }` 裡回傳 `List(COUNT) { TickPrice(1_000_000L + it) }`，用 `List<TickPrice>` 存。
2. **修正版**：`PriceSeries` 內部用 `LongArray` 存原始的 `Long`，`get(index)` 才包成 `TickPrice`；在 `measure { }` 裡回傳 `PriceSeries(LongArray(COUNT) { 1_000_000L + it })`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「同一個價格轉成 Any 兩次：兩個不同的物件（每次都 boxing）」；每筆 16 bytes 以上（電腦上的 JVM 單元測試實測約 31 bytes，Android 模擬器 API 29 約 20 bytes） |
| 修正版 | 每筆約 8–10 bytes（電腦上的 JVM 單元測試實測約 10 bytes，Android 模擬器約 8 bytes，就是一個 `Long`），第 123 筆價格還是 1000123 |

**為什麼**：value class 平常會還原成底層的值，不用多配置物件；但放進泛型（`List<T>`、`Flow<T>`）時，泛型在執行時只認得物件，編譯器必須把它包成真的物件，這叫 boxing。所以 value class 擋得住「傳錯參數」，卻不是零成本。行情這種每秒大量資料的地方，內部用原始型別的陣列存，對外才包成 value class，並且用 Android Studio 的 Memory Profiler 量過再決定。

```lab-solution
android/solution/kotlin/kotlin01-types/L04_ValueClassBoxing.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L04_ValueClassBoxing.kt#fixed
```

### 演練 5：新增訂單狀態，App 默默顯示成「處理中」（第 5 題）

**檔案**：`L05_SealedState.kt`

**情境**：後端新增訂單狀態 `EXPIRED`（限價單超過有效期限，已作廢），同時還有一個舊版 App 完全不認識的 `EXPIRED_V2`。畫面依狀態顯示文字，並決定「取消」按鈕要不要出現。

**怎麼寫**

1. **Bug 版**：狀態是 `enum class Status { NEW, FILLED, EXPIRED }`。`label()` 和 `canCancel()` 的 `when` 為了「保險」寫了 `else`（`else -> "處理中"`、`else -> true`），`EXPIRED` 沒有單獨的分支。
2. **修正版**：狀態改成 `sealed interface OrderState`（`New`、`Filled`、`Expired` 用 `data object`，`Unknown(raw)` 用 `data class`）。`parse` 把還不認識的字串轉成 `Unknown`；`label()`、`canCancel()` 的 `when` 不寫 `else`，每個子型別各一個分支。寫完後試著刪掉其中一個分支，看編譯器怎麼說。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「EXPIRED：處理中，可以取消」；`EXPIRED_V2` 被默默當成「已送出，可以取消」 |
| 修正版 | 「EXPIRED：已過期，不能取消」；「EXPIRED_V2：未知狀態（EXPIRED_V2），不能取消」 |

**為什麼**：`else` 會把所有「沒想到的」都吞掉，後端新增狀態時編譯器不會提醒你。`sealed` 讓子型別在編譯時全部確定，`when` 不寫 `else` 就會要求每種都處理，漏掉任何一個都是編譯錯誤。但從 API 解析回來的字串，編譯器管不到，所以要在解析的邊界轉成 `Unknown`，不能讓它變成執行時的例外。

```lab-solution
android/solution/kotlin/kotlin01-types/L05_SealedState.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L05_SealedState.kt#fixed
```

### 演練 6：價格沒變，畫面卻一直刷新（第 6 題）

**檔案**：`L06_DataClassEquals.kt`

**情境**：行情畫面收到新報價時，先和上一筆比較，不一樣才刷新畫面。後端依序傳來 `"1.0"`、`"1.00"`、`"1.000"`、`"1.5"`、`"1.50"`：前三筆數值都是 1，後兩筆都是 1.5，只是小數位數（scale）不同。

**怎麼寫**

1. **Bug 版**：`data class Quote(val symbol: String, val price: BigDecimal)`，不覆寫 `equals`。
2. **修正版**：同一個 data class，在 body 裡覆寫 `equals`：other 是 `Quote`、`symbol` 相同，而且 `price.compareTo(other.price) == 0`；再覆寫 `hashCode`，價格用 `price.stripTrailingZeros().hashCode()`，讓相等的物件有相同的 `hashCode`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「1.0 和 1.00 相等嗎：false」、「放進 HashSet 剩幾個：2」；五筆報價每一筆都刷新，最後印出「共刷新 5 次」 |
| 修正版 | 「1.0 和 1.00 相等嗎：true」、「放進 HashSet 剩幾個：1」；只有 1.0 和 1.5 刷新，最後印出「共刷新 2 次」 |

**為什麼**：data class 自動產生的 `equals` 會逐個屬性呼叫它們自己的 `equals`，而 `BigDecimal.equals` 連 scale 都比，所以 `1.0` 和 `1.00` 不相等。比數值大小要用 `compareTo`。覆寫 `equals` 時一定要一起覆寫 `hashCode`，否則放進 `HashSet`、當 `HashMap` 的 key 都會出問題。

```lab-solution
android/solution/kotlin/kotlin01-types/L06_DataClassEquals.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin01-types/L06_DataClassEquals.kt#fixed
```

## 常見陷阱

- **把 Java 或 SDK 回傳的值直接用，沒有轉成明確的型別。** `val n = sdk.nickname` 推斷出 `String!`，NPE 會在很遠的使用處才出現。在 data 層就宣告成 `String?`，讓 domain 層看不到 `String!`。
- **`List<Int>` 傳給需要 `MutableList<Number>` 的函式。** `MutableList` 是 invariant，放寬會讓你把 `Double` 塞進整數清單。只需要讀的函式，參數改成 `List<Number>` 或 `MutableList<out Number>`。
- **在 inline 函式裡把 lambda 丟給別的執行緒，卻沒標 `crossinline`。** 編譯器會報錯，因為 lambda 執行時外層函式可能早就結束了，`return` 沒有地方可以跳。
- **把 value class 說成零成本。** 放進 `List`、`Flow`、`StateFlow` 這類泛型時會 boxing。行情這種每秒大量資料的地方，要用 profiler 量過。
- **在 `when` 裡對 sealed 型別加 `else`。** 加了之後，新增狀態時編譯器不會提醒你，漏處理的狀態會默默走進 `else`。
- **以為 data class 的 `==` 比的是全部欄位。** 只比主建構子的屬性。`BigDecimal("1.0") == BigDecimal("1.00")` 是 `false`，`ByteArray` 比的是參考。
- **用可變的 data class 當 `HashMap` 的 key。** 改欄位後 `hashCode` 變了，原本存的資料就找不到了。

## 面試官想聽到的

1. 能說出 platform type 的風險（NPE 在使用處才爆），以及「在 Java／SDK 邊界立刻轉成明確的 nullability」這個做法。
2. 能用具體例子解釋 `out` 和 `in`（`List` 只讀所以可以放寬、`MutableList` 又讀又寫所以不行），並對應到 Java 的 PECS。
3. 能分清 `reified`、`crossinline`、`noinline` 各自解決什麼問題，而不是只背名詞。
4. 知道 value class 何時會 boxing，不會把它講成零成本，也知道它不取代 `BigDecimal` 的運算規則。
5. 會用 sealed 建模訂單狀態，並知道 data class 的 `equals` 邊界，尤其是金額型別。

> [!WARNING]
> 面試官常追問：「後端新增了一種訂單狀態，舊版 App 會怎樣？」要能回答兩件事：同一次編譯裡，沒有 `else` 的 `when` 會被編譯器擋下來；但從 API 解析回來的未知狀態，要在解析時就轉成 `Unknown`，不能讓它變成執行時的例外。

> [!FIELD]
> 在一個藍牙智慧鎖 SDK 專案裡，Android 版以 Kotlin 為主，另外有 Java 寫的 AES（常見的對稱式加密演算法）加密工具。Kotlin 呼叫 Java 就是 platform type 會出現的場景。實務上的做法是：Java 或第三方 SDK 的邊界一律轉成明確型別，再用 sealed 表示下單狀態。

## iOS / Android 對照

| 主題 | Kotlin | Swift |
|---|---|---|
| 可為 null | `T?`；Java 來源沒標註時是 platform type `T!`，編譯器不檢查 | `T?`（Optional）；Objective-C 沒標註時會變成 `T!`，同樣是檢查的漏洞，要用 `_Nullable`／`_Nonnull`（或 `nullable`／`nonnull`）補上 |
| 泛型的型別資訊 | 執行時被擦除，需要 `inline` 加 `reified` 才能在函式內使用 `T` | 泛型不擦除，`T.self`、`x as? T` 在泛型函式裡本來就能用，不需要特別的關鍵字 |
| variance | 宣告處 `out`／`in`、使用處投影 | 沒有 `out`／`in`。標準函式庫的 `Array`、`Dictionary`、`Optional` 由編譯器特別處理成可以放寬，自己寫的泛型型別都是 invariant |
| 包一層防止傳錯 | `value class`，放進泛型會 boxing | 單一欄位的 `struct`，本來就是值型別，`[OrderId]` 直接存值，不會多配置物件 |
| 窮盡檢查 | `sealed` 搭配 `when`，跨 module 有 `NoWhenBranchMatchedException` 的風險 | `enum` 搭配關聯值與 `switch`；來自系統或會持續演進的 library 的 enum 可能新增 case，所以要加 `@unknown default` |
| 比較兩個金額 | `BigDecimal.equals` 連 scale 都比，要用 `compareTo` | `Decimal` 的 `==` 比的是數值，`1.0 == 1.00` 為 `true` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 平台型別 | platform type | プラットフォーム型 |
| 型別擦除 | type erasure | 型消去 |
| 共變／逆變／不變 | covariant／contravariant／invariant（variance） | 共変／反変／非変（変性） |
| 使用處投影 | use-site projection | 使用側の変性指定 |
| inline 函式（編譯時把內容貼進呼叫處） | inline function | インライン関数 |
| value class（包一個值的型別） | value class | バリュークラス |
| sealed class／sealed interface（子型別在編譯時全部確定） | sealed class／sealed interface | シールドクラス／シールドインターフェース |
| 窮盡檢查 | exhaustive check | 網羅性チェック |
