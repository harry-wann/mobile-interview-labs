---
id: kotlin-java-interop-jmm
title: Kotlin 和 Java 混用要注意什麼，以及 Java Memory Model（volatile、happens-before）
summary: 職缺同時寫了 Java 和 Kotlin，面試會問混用時的註解與型別陷阱，也會問 volatile、synchronized、ConcurrentHashMap 這些多執行緒的基本功。這個單元用具體例子說明 @JvmStatic、@JvmOverloads、platform type、nullability 註解，以及 Java Memory Model 裡的可見性與 happens-before。
priority: P1
day: 2
tags: [java, interop, jvm-annotations, jmm, volatile, synchronized, concurrenthashmap]
---

## 重點講解

這個單元回答六個面試常見問題：

1. Kotlin 寫的程式給 Java 呼叫時，要加哪些註解？（`@JvmStatic`、`@JvmOverloads`、`@JvmField`、`@Throws`）
2. Java 回傳的型別在 Kotlin 裡是 platform type，是什麼？nullability 註解有什麼用？
3. 混用時還有哪些容易踩到的地方？（SAM、`suspend fun`、checked exception）
4. Java Memory Model（JMM）在規範什麼？什麼是可見性？`volatile` 解決什麼問題？
5. happens-before 是什麼？`volatile`、`synchronized`、Atomic 各保證什麼？
6. `HashMap` 和 `ConcurrentHashMap` 差在哪？

### 1. Kotlin 寫的程式給 Java 呼叫時，要加哪些註解？

**Kotlin 的 `companion object`、預設參數、屬性，在 Java 看起來都和直覺不一樣。加上 `@JvmStatic`、`@JvmOverloads`、`@JvmField` 這些註解，Java 呼叫端才能像呼叫一般的 Java 程式那樣使用。**

先看 Kotlin 這一邊的程式碼：

```kotlin
class Client @JvmOverloads constructor(val baseUrl: String, val timeoutMs: Long = 10_000) {
    companion object {
        @JvmStatic fun create() = Client("https://api.example.com")
        @JvmField val TAG = "Client"
    }

    @Throws(java.io.IOException::class)
    fun load(path: String): String = TODO()
}
```

Java 呼叫端的差別：

```java
// 有加註解
Client c1 = new Client("https://api.example.com");   // @JvmOverloads：可以只傳第一個參數
Client c2 = Client.create();                          // @JvmStatic：像一般的 static 方法
String tag = Client.TAG;                              // @JvmField：直接讀欄位

// 沒加註解的話，Java 要這樣寫
Client c3 = new Client("https://api.example.com", 10_000L);  // 看不到預設值，要傳滿所有參數
Client c4 = Client.Companion.create();                        // 要經過 Companion
String tag2 = Client.Companion.getTAG();                      // 要呼叫 getter
```

| 註解 | 效果 | 不加會怎樣 |
|---|---|---|
| `@JvmStatic`（寫在 `companion object` 或 `object` 裡） | 額外產生一個真正的 `static` 方法 | Java 要寫 `Foo.Companion.create()`；`object` 則是 `Foo.INSTANCE.create()` |
| `@JvmField` | 屬性直接變成 public 欄位，不產生 getter／setter | Java 只能呼叫 `getX()` |
| `@JvmOverloads` | 為有預設值的參數，產生一組由右往左省略的多載版本 | Java 看不到預設值，只能傳滿所有參數 |
| `@JvmName` | 改變在 JVM（Java Virtual Machine，執行 Java／Kotlin 程式的虛擬機器）上看到的名稱，例如檔案 `Utils.kt` 的頂層函式預設在 `UtilsKt` 類別裡，加 `@file:JvmName("Utils")` 就變成 `Utils` | Java 要寫 `UtilsKt.foo()` |
| `@Throws(IOException::class)` | 在方法簽名加上 `throws IOException` | 見第 3 節 |

補充幾個小規則：`const val` 會變成 `static final` 常數，不需要 `@JvmField`；Kotlin 屬性在 Java 看起來是 getter 和 setter，`Boolean` 屬性 `isActive` 的 getter 就叫 `isActive()`。

> [!TIP]
> 面試時可以這樣說：「Kotlin 給 Java 用的 API，我會加 @JvmStatic 讓 companion 的函式變成真正的 static 方法，@JvmOverloads 讓 Java 也能用預設參數，@JvmField 讓屬性直接暴露成欄位。會丟 checked exception 的函式要標 @Throws。」

### 2. Java 回傳的型別在 Kotlin 裡是 platform type，是什麼？nullability 註解有什麼用？

**Java 的型別系統沒有「可不可以是 null」的資訊，所以 Kotlin 看到 Java 回傳的 `String`，不知道它會不會是 null，就把它標成 platform type，寫成 `String!`。編譯器不會強迫你處理 null，把它當成 `String` 或 `String?` 都可以，但如果實際上是 null 而你當成非 null 用，就會在執行時丟出 `NullPointerException`。**

先看問題。假設 Java 這邊有一個查使用者名稱的類別，沒有任何標註：

```java
public class UserRepo {
    public String findName(int id) { return null; }   // 沒標註：可能回傳 null
}
```

```kotlin
val repo = UserRepo()
val a = repo.findName(1)             // 型別是 String!（platform type），編譯器不擋你
val b: String = repo.findName(1)     // 宣告成非 null：Java 回傳 null 時，這一行就丟 NullPointerException
val c: String? = repo.findName(1)    // 保守的寫法：當成可能是 null，之後用 ?. 或 ?: 處理
```

**nullability 註解**是 Java 這邊的補救：在 Java 程式碼上加 `@Nullable` 或 `@NonNull`（JetBrains 的 `org.jetbrains.annotations`、AndroidX 的 `androidx.annotation` 都有提供），Kotlin 編譯器讀到之後，就能推斷出明確的型別，而不是 `String!`：

```java
public class UserRepo {
    @Nullable public String findNickname(int id) { return null; }  // Kotlin 看到 String?
    @NonNull public String findEmail(int id) { return "a@b.com"; }  // Kotlin 看到 String
}
```

```kotlin
repo.findNickname(1).length    // 編譯錯誤：只能用 ?. 呼叫，因為它是 String?
repo.findEmail(1).length       // 可以直接用
```

Android 本身的 API 就有這些標註。例如 `intent.getStringExtra("id")` 在 Kotlin 裡的型別是 `String?`，編譯器會提醒你處理 null。

反過來，Kotlin 的非 null 參數，Java 若傳了 null 進來，Kotlin 會在函式一開始就丟例外，而不是等到用到的時候才出錯。

**實務做法：在 Java 與 Kotlin 的邊界，把 platform type 收斂成明確的型別。** 呼叫沒有標註的 Java 方法時，立刻宣告成 `String?`（保守）或 `String`（確定不會是 null），不要讓 `String!` 一路傳進 Kotlin 的程式裡。

> [!TIP]
> 面試時可以這樣說：「Java 回傳的型別在 Kotlin 是 platform type，編譯器不檢查 null，用錯會在執行時 NPE。所以我在邊界會明確宣告成可為 null 或非 null，或在 Java 那邊加 @Nullable、@NonNull 讓 Kotlin 編譯器推斷。」

### 3. 混用時還有哪些容易踩到的地方？

**除了註解和 null，最常被問的是三件事：SAM 轉換、`suspend fun` 對 Java 不友善、checked exception。**

**(1) SAM 轉換。** SAM 是 single abstract method 的縮寫，指「只有一個抽象方法的介面」，例如 `Runnable`。Java 的 SAM 介面，在 Kotlin 可以直接傳 lambda：

```kotlin
executor.execute { println("run") }       // Java 的 Executor.execute(Runnable)
val r = Runnable { println("run") }
```

Kotlin 自己宣告的介面，要寫成 `fun interface`（Kotlin 1.4 起）才能這樣用；給 Java 使用的回呼，也建議宣告成 Java 風格的介面或 `fun interface`，因為 Kotlin 的函式型別 `(A) -> B` 在 Java 看起來是 `Function1<A, B>`，回傳 `Unit` 的 lambda 在 Java 還要 `return Unit.INSTANCE`，很不好寫。

另外，如果 lambda 裡用到了 `this`（例如 Activity），它就持有那個物件的參考，和 Java 的匿名類別一樣，可能造成記憶體洩漏。

**(2) `suspend fun` 對 Java 不友善。** 編譯後會多出一個 `Continuation` 參數，Java 很難正確呼叫。需要給 Java 用的時候，另外提供 `CompletableFuture` 或 callback 的包裝（例如 `scope.future { }`）。

**(3) checked exception。** Java 有 checked exception（編譯器強制你處理或宣告的例外，例如 `IOException`），Kotlin 沒有。如果 Kotlin 函式會丟 `IOException` 卻沒有標 `@Throws`，Java 呼叫端寫了：

```java
try {
    client.load("a");
} catch (IOException e) { }   // 編譯錯誤：這個例外從未被宣告可能丟出
```

因為 Kotlin 產生的方法簽名沒有 `throws`。所以公開給 Java 的 API 要標 `@Throws`。同樣的規則也適用於 Kotlin Multiplatform（KMP，用 Kotlin 寫一份共用邏輯給 iOS 與 Android 使用）匯出給 Swift 的情況：`suspend fun` 若沒有標 `@Throws`，丟出的例外 Swift 端接不住，會讓 App 直接終止。

Kotlin 的 `use { }` 則對應 Java 的 try-with-resources（用完自動關閉資源）。

> [!TIP]
> 面試時可以這樣說：「混用時我會注意：Java 的 SAM 介面可以直接傳 lambda，Kotlin 的介面要宣告 fun interface；suspend 函式對 Java 不友善，要另外包 future 或 callback；Kotlin 沒有 checked exception，給 Java 或 Swift 用的 API 要標 @Throws。」

### 4. Java Memory Model（JMM）在規範什麼？什麼是可見性？

**JMM（Java Memory Model，Java 記憶體模型）是 JVM 規格的一部分，規範「一個執行緒寫入的值，什麼時候、以什麼順序，才會被另一個執行緒看到」。沒有適當的同步，就沒有保證：另一個執行緒可能很久都看不到你的寫入，甚至看到的順序和程式碼寫的不一樣。**

為什麼會這樣？因為編譯器、JIT（執行時才編譯程式碼的編譯器）與 CPU 為了加速，會把變數暫存在 CPU 的快取或暫存器裡，也會調整指令的執行順序（重排），只要「在單一執行緒看起來結果一樣」就行。多執行緒時，這些最佳化就會被看到。

**例子一：可見性（visibility）。** 一個背景執行緒用旗標控制迴圈，另一個執行緒負責停止它：

```kotlin
class Poller {
    var running = true                       // 沒有 @Volatile

    fun loop() {                             // 在執行緒 A 執行
        while (running) { /* 輪詢行情 */ }
    }

    fun stop() { running = false }           // 在執行緒 B 呼叫
}
```

B 已經把 `running` 設成 `false`，但 A 的迴圈可能**永遠不結束**：A 可能一直讀自己快取裡的舊值，或 JIT 發現迴圈裡沒有人改 `running`，就把它的讀取移到迴圈外，只讀一次。這就是「寫入對另一個執行緒不可見」。

修法是加上 `@Volatile`（對應 Java 的 `volatile`）：

```kotlin
@Volatile var running = true    // 每次讀都讀最新的值，每次寫都立刻讓其他執行緒看得到
```

**例子二：重排（reordering）。**

```kotlin
var data = 0
@Volatile var ready = false

// 執行緒 A
data = 42
ready = true

// 執行緒 B
if (ready) println(data)    // 因為 ready 是 volatile，只要 B 讀到 ready == true，一定印出 42
```

如果 `ready` 不是 volatile，B 可能讀到 `ready == true` 卻讀到 `data == 0`，因為 A 的兩行寫入可能被重排，或其中一個還沒讓 B 看到。`@Volatile` 保證：A 在寫入 `ready` **之前**做的所有寫入（包含 `data = 42`），B 讀到這個 `ready` 之後都看得到。

**`volatile` 保證可見性與順序，但不保證原子性。** 原子性（atomicity）是指一個操作不能被切開、不會被別的執行緒插進來。`count++` 其實是「讀出、加一、寫回」三步：

```kotlin
@Volatile var count = 0
// 兩個執行緒同時執行 count++：
// A 讀到 5，B 也讀到 5；A 寫回 6，B 也寫回 6。兩次加一只算了一次，少了一筆
```

所以計數器要用 `AtomicInteger`（下一節）或鎖。

> [!TIP]
> 面試時可以這樣說：「JMM 規範一個執行緒的寫入何時、以什麼順序被另一個執行緒看到。沒有同步，另一個執行緒可能永遠讀到舊值，例如停止旗標沒加 volatile，迴圈會停不下來。volatile 保證可見性和順序，但 count++ 這種讀、改、寫不是原子的，要用 Atomic 或鎖。」

### 5. happens-before 是什麼？`volatile`、`synchronized`、Atomic 各保證什麼？

**happens-before 是 JMM 的核心概念：如果動作 A happens-before 動作 B，那 A 的結果，B 一定看得到。沒有 happens-before 關係的兩個動作，就沒有任何可見性的保證。**

上一節 `ready` 的例子就是 happens-before 的應用：「對 volatile 變數的寫入」happens-before「之後對同一個變數的讀取」，所以 A 在寫入之前做的事，B 讀到之後都看得到。常用的規則有這幾條：

| 規則 | 具體的意思 |
|---|---|
| 同一個執行緒內的程式順序 | 同一個執行緒裡，前面的程式碼 happens-before 後面的程式碼 |
| `volatile` 寫入 → 之後的讀取 | 上面 `ready` 的例子 |
| 解鎖 → 之後對同一把鎖的加鎖 | 執行緒 A 離開 `synchronized(lock) { }` 之前的寫入，之後進入同一把鎖的執行緒 B 看得到 |
| `Thread.start()` → 新執行緒內的動作 | 啟動執行緒之前做的寫入，新執行緒一定看得到 |
| 執行緒內的動作 → `join()` 回傳 | 呼叫 `join()` 等到執行緒結束之後，那個執行緒做的寫入都看得到 |

三種常用的同步機制，保證的東西不一樣：

| 機制 | 可見性 | 原子性 | 適合 |
|---|---|---|---|
| `@Volatile` | 有 | 單次讀或寫是原子的；`count++` 不是 | 旗標、單一參考的發佈 |
| `synchronized`／`ReentrantLock` | 有（解鎖 → 加鎖的 happens-before） | 有，同一時間只有一個執行緒進入 | 多個變數要一起改、複合操作 |
| `Atomic*`（例如 `AtomicInteger`） | 有 | 單一變數的讀改寫是原子的 | 計數器、旗標、參考更新 |

```kotlin
class Counter {
    private val lock = Any()
    private var count = 0
    fun increment() = synchronized(lock) { count++ }   // 同一時間只有一個執行緒進來
    fun get() = synchronized(lock) { count }            // 讀也要用同一把鎖，才看得到最新值
}

val atomic = java.util.concurrent.atomic.AtomicInteger()
atomic.incrementAndGet()    // 原子地加一。底層用 CAS：先讀出目前的值，只有值還沒被別人改過，才寫入新值，否則重試
```

CAS 是 compare-and-set 的縮寫，「比較再設定」：如果現在的值還是我剛才讀到的那個值，才改成新值；已經被別人改過，就重來一次。

**兩個容易被混淆的詞：**

- **data race（資料競爭）：** 沒有同步的並行存取，而且其中至少一個是寫入。例如上面沒加 `@Volatile` 的 `running`。
- **race condition（競爭條件）：** 結果取決於執行的時間順序。**即使每一次存取都有同步，仍然可能發生**，例如 check-then-act（先檢查、再行動）：

```kotlin
// balance 的讀和寫各自都有同步，但這段仍然有問題
if (balance >= amount) {      // 兩筆委託同時檢查，餘額都夠
    balance -= amount         // 兩筆都扣，餘額變成負的
}
```

要把「檢查加扣款」放進同一個 `synchronized` 區塊，或用 CAS 迴圈。

**另外兩個相關的重點：**

- Kotlin 的 `val` 會編譯成 Java 的 `final` 欄位。物件建構完成之後，其他執行緒看到這個物件時，一定看得到 `final` 欄位的值，所以不可變物件可以安全地給多個執行緒共用，不需要鎖。
- double-checked locking（雙重檢查鎖，先檢查再加鎖的單例寫法）的欄位一定要 `@Volatile`，否則別的執行緒可能看到非 null 的參考，物件裡面的欄位卻還沒初始化完成。

```kotlin
class Repo private constructor() {
    companion object {
        @Volatile private var instance: Repo? = null
        fun get(): Repo = instance ?: synchronized(this) {
            instance ?: Repo().also { instance = it }
        }
    }
}
```

實務上更簡單的做法是用 `object`（JVM 保證類別初始化是執行緒安全的）或 `by lazy`（預設就是執行緒安全的模式）。

> [!TIP]
> 面試時可以這樣說：「happens-before 是 JMM 的核心：A happens-before B，B 就一定看得到 A 的結果。volatile 寫入對之後的讀取、解鎖對之後的加鎖，都建立這種關係。volatile 只保證可見性，synchronized 同時給互斥和 happens-before，Atomic 用 CAS 讓單一變數的讀改寫是原子的。」

### 6. `HashMap` 和 `ConcurrentHashMap` 差在哪？

**`HashMap` 不是執行緒安全的，多個執行緒同時寫入會遺失資料；`ConcurrentHashMap` 是為多執行緒設計的，單一操作是安全的，也提供原子的複合操作。但它不允許 null，而且「先檢查、再寫入」仍然不安全。**

| | `HashMap` | `ConcurrentHashMap` |
|---|---|---|
| 多執行緒同時寫入 | 不安全：可能遺失更新、資料損壞 | 安全：單一操作是安全的，讀取幾乎不用等鎖 |
| null | key 和 value 都可以是 null | **都不可以是 null**，否則丟 `NullPointerException` |
| 迭代時集合被修改 | fail-fast：丟 `ConcurrentModificationException`（簡稱 CME） | 不丟 CME，迭代器可能看得到、也可能看不到迭代中發生的修改 |
| 複合操作 | 沒有 | `putIfAbsent`、`computeIfAbsent`、`merge`，對單一 key 是原子的 |
| `size()` | 精確 | 多執行緒同時修改時，是近似值 |

**check-then-act 仍然不安全：**

```kotlin
val map = ConcurrentHashMap<String, Session>()

// 錯誤：兩個執行緒可能同時通過 containsKey，各自 put，後寫的覆蓋先寫的
if (!map.containsKey(id)) { map[id] = Session(id) }

// 正確：合成一個原子操作
map.putIfAbsent(id, Session(id))
map.computeIfAbsent(id) { Session(id) }   // 只在 key 不存在時才建立 Session
```

注意 Kotlin 的 `ConcurrentMap.getOrPut` **不是原子的**：預設值的函式在多執行緒下可能被執行多次（只保證不會覆蓋已經存在的值）。要確保只建立一次，用 `computeIfAbsent`。

**null 的例子：**

```kotlin
val hm = HashMap<String?, Int?>()
hm[null] = null            // 可以，size 是 1

val chm = ConcurrentHashMap<String, Int?>()
chm["a"] = null            // 可以編譯，但執行時丟 NullPointerException
```

Kotlin 的型別系統不會替你擋下這個問題。

**迭代時修改，不要依賴 CME 來發現錯誤：**

```kotlin
val a = mutableListOf(1, 2, 3)
for (x in a) { if (x == 2) a.remove(x) }
println(a)    // 輸出：[1, 3]，沒有丟例外，而且 3 根本沒有被走訪到

val b = mutableListOf(1, 2, 3, 4)
for (x in b) { if (x == 2) b.remove(x) }   // 這個會丟 ConcurrentModificationException
```

第一個迴圈沒丟例外，是因為迭代器判斷「還有沒有下一個」的方式，是比較目前位置和目前的大小：移除 `2` 後，位置剛好等於大小，迴圈就默默結束了。要在迭代時修改集合，用 `removeIf`、`iterator.remove()`，或先複製一份。

讀多寫少的集合（例如監聽器清單），可以用 `CopyOnWriteArrayList`：每次修改都複製一份新陣列，讀取完全不用鎖。

> [!TIP]
> 面試時可以這樣說：「HashMap 多執行緒寫入不安全，ConcurrentHashMap 單一操作安全，但不允許 null，而且 containsKey 再 put 還是 check-then-act，要用 putIfAbsent 或 computeIfAbsent。Kotlin 的 getOrPut 在 ConcurrentMap 上不是原子的，預設值可能算多次。」

## 實際演練

> [!LAB]
> **打開**：Android Studio 打開 `labs/android` 資料夾，等 Gradle Sync 完成，Run configuration 選 **app**，選模擬器或手機按 ▶，App 首頁進入 **Kotlin10JavaInterop**。這個單元的演練都不用看畫面，按「執行」就有自動檢查（演練 4 的 Bug 版例外，見該題說明），也可以在電腦上跑 `./gradlew :practice-kotlin:testDebugUnitTest` 看結果。
> **檔案**：你寫在 `labs/android/practice/kotlin/kotlin10-java-interop/`，解答在 `labs/android/solution/kotlin/kotlin10-java-interop/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **多執行緒的演練**：演練 3、5、6 的結果每次執行都不太一樣（例如少算多少筆），但 Bug 版幾乎每次都會出錯；檢查只確認「有出錯」或「全部正確」，所以多按幾次也不會時好時壞。

### 演練 1：Java 呼叫 Kotlin 的 API 很難用（第 1 題）

**檔案**：`L01_JvmAnnotations.kt`

**情境**：App 有一個 Kotlin 寫的 `Client`，要給 Java 寫的模組（或合作廠商的 SDK）呼叫。它有三個地方：有預設值的建構子參數（`timeoutMs`）、`companion object` 裡的 `create()`、`companion object` 裡的 `TAG`。演練用 Java 的反射（reflection，執行時查看類別有哪些成員）查看「Java 眼中的 `Client`」，結果等於 Java 程式碼 `new Client(url)`、`Client.create()`、`Client.TAG` 能不能這樣寫。

**怎麼寫**

1. **Bug 版**：`Client(val baseUrl: String, val timeoutMs: Long = 10_000)`，`companion object` 裡 `fun create() = Client("https://api.example.com")` 和 `val TAG = "Client"`，什麼註解都不加。
2. **修正版**：同樣的 `Client`，建構子加 `@JvmOverloads`、`create()` 加 `@JvmStatic`、`TAG` 加 `@JvmField`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「Kotlin 這邊的 API 正常」是「是」，三行「Java 能寫…」（只傳一個參數的建構子、`Client.create()`、`Client.TAG`）全部是「否」 |
| 修正版 | 三行「Java 能寫…」全部是「是」 |

**為什麼**：沒加註解時，Java 看不到預設參數，只能傳滿所有參數（`new Client(url, 10_000L)`）；`create()` 在 `Client.Companion` 這個物件上，要寫 `Client.Companion.create()`；`TAG` 是 private 欄位加 getter，要寫 `Client.Companion.getTAG()`。三個註解就是把它們變成 Java 程式員預期的樣子。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L01_JvmAnnotations.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L01_JvmAnnotations.kt#fixed
```

### 演練 2：Java 的 catch (IOException) 編譯不過（第 3 題）

**檔案**：`L02_ThrowsChecked.kt`

**情境**：Kotlin 寫的 `Loader.load()` 連線逾時時會丟 `IOException`（Java 的 checked exception：編譯器強制呼叫端處理或宣告的例外）。Java 呼叫端會寫 `try { loader.load("a"); } catch (IOException e) { … }`。演練用反射查看 Java 眼中的 `load()` 簽名有沒有 `throws IOException`。

**怎麼寫**

1. **Bug 版**：`fun load(path: String): String = throw IOException("連線逾時：$path")`，不標 `@Throws`。
2. **修正版**：同樣的函式，加上 `@Throws(IOException::class)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「Java 眼中的簽名：String load(String path) throws （沒有）」，「Java 的 catch (IOException e) 能編譯：否」 |
| 修正版 | 「Java 眼中的簽名：String load(String path) throws IOException」，「Java 的 catch (IOException e) 能編譯：是」 |

**為什麼**：Kotlin 沒有 checked exception，編譯出來的方法簽名不會有 `throws`。Java 的 `catch (IOException e)` 在 `try` 裡沒有任何東西宣告會丟 `IOException` 時會編譯錯誤（「這個例外從未被宣告可能丟出」）。所以公開給 Java 的 API 要標 `@Throws`。同樣的規則也適用於 Kotlin Multiplatform 匯出給 Swift 的 `suspend fun`：沒標 `@Throws`，丟出的例外 Swift 端接不住，App 直接終止。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L02_ThrowsChecked.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L02_ThrowsChecked.kt#fixed
```

### 演練 3：volatile 的計數器還是少算了（第 4 題）

**檔案**：`L03_VolatileCounter.kt`

**情境**：行情 SDK 的 `Counter` 要統計收到幾筆訊息。4 條執行緒各自呼叫 `increment()` 100,000 次，預期總共 400,000。

**怎麼寫**

1. **Bug 版**：`@Volatile var count = 0`，`increment()` 裡寫 `count++`。
2. **修正版**：改用 `AtomicInteger`，`increment()` 呼叫 `incrementAndGet()`，`get()` 回傳目前的值。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「預期 400000，實際 N」，N 遠小於 400000（實測約 12 萬，每次不同） |
| 修正版 | 「預期 400000，實際 400000」 |

**為什麼**：`volatile` 保證可見性與順序，但不保證原子性。`count++` 是「讀出、加一、寫回」三步，兩條執行緒可能讀到同一個值，各自寫回加一，兩次加一只算了一次。`AtomicInteger` 用 CAS（compare-and-set，比較再設定：現在的值還是我剛才讀到的那個，才寫入新值，否則重試）讓這三步成為一個不可分割的操作。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L03_VolatileCounter.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L03_VolatileCounter.kt#fixed
```

### 演練 4：停止旗標設成 false，迴圈卻停不下來（第 4 題）

**檔案**：`L04_StopFlag.kt`

**情境**：`Poller` 在背景執行緒用 `while (running)` 持續輪詢行情，另一條執行緒呼叫 `stop()` 把 `running` 設成 `false` 要它停下來。演練先讓迴圈跑 100 ms，呼叫 `stop()`，再等 300 ms，看迴圈結束了沒有。為了不讓 Bug 版一直空轉，迴圈最多跑 10 億圈（約 1 到 3 秒）就自己結束。

**怎麼寫**

1. **Bug 版**：`var running = true`，不加 `@Volatile`。
2. **修正版**：`@Volatile var running = true`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 電腦上通常是「stop() 之後 300 ms，迴圈已結束：否」（Android 不一定，見下面說明） |
| 修正版 | 「stop() 之後 300 ms，迴圈已結束：是」 |

**為什麼**：沒有同步時，JMM（Java Memory Model，Java 記憶體模型）不保證一條執行緒的寫入什麼時候被另一條看到。JIT（執行時才編譯程式碼的編譯器）發現迴圈裡沒有人改 `running`，可以把它的讀取移到迴圈外只讀一次，`stop()` 寫入的 `false` 這條執行緒永遠看不到。`@Volatile` 禁止這種最佳化。因為這個 bug 取決於 JIT 有沒有最佳化那個迴圈（電腦上的 HotSpot JVM 幾乎每次都會；Android 的 ART 不一定），Bug 版沒有自動檢查，要看 log 判斷；修正版有檢查。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L04_StopFlag.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L04_StopFlag.kt#fixed
```

### 演練 5：每個存取都同步了，餘額還是超額扣款（第 5 題）

**檔案**：`L05_CheckThenAct.kt`

**情境**：帳戶餘額 100 日圓，4 條執行緒同時開始，各嘗試提領 1 日圓 100 次（共 400 次嘗試），正確結果是剛好成功 100 次。每次提領在「檢查餘額」和「扣款」之間，還要做一次風控檢查（`riskCheck()`，約 20 微秒）。演練重複最多 100 輪，看有沒有哪一輪成功的次數超過 100。

**怎麼寫**

1. **Bug 版**：`balance()` 和 `setBalance()` 都有 `@Synchronized`。`withdraw` 先 `if (balance() >= amount)` 檢查，接著 `riskCheck()`，再 `setBalance(balance() - amount)` 扣款。
2. **修正版**：整個 `withdraw` 放進同一個 `synchronized(lock) { … }`：檢查、`riskCheck()`、扣款在同一個鎖裡。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「跑了 3 輪，其中 3 輪成功提領的次數超過餘額（100 次）」：累積 3 輪超額就停止，通常前幾輪就出現 |
| 修正版 | 「跑了 100 輪，其中 0 輪成功提領的次數超過餘額（100 次）」 |

**為什麼**：data race（資料競爭）是沒有同步的並行存取；race condition（競爭條件）是結果取決於執行的時間順序，即使每一次存取都有同步，仍然可能發生。這題的 `balance()` 與 `setBalance()` 各自都同步，沒有 data race，但「檢查加扣款」是 check-then-act（先檢查、再行動）：兩條執行緒都看到餘額夠，都去扣款。要把「檢查加扣款」放進同一個 `synchronized` 區塊，或用 CAS 迴圈。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L05_CheckThenAct.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L05_CheckThenAct.kt#fixed
```

### 演練 6：同一個 Session 被建立了好幾次（第 6 題）

**檔案**：`L06_ConcurrentGetOrPut.kt`

**情境**：`SessionRegistry` 用 `ConcurrentHashMap` 保存每個帳號的 Session，沒有就建立（建立 Session 要開一條連線，所以只能建立一次）。每一輪有 8 條執行緒同時取得同一個帳號的 Session，共 20 輪，應該只建立 20 個 Session。

**怎麼寫**

1. **Bug 版**：`sessions.getOrPut("harry") { L06Session("harry", created) }`，用 Kotlin 的 `getOrPut`。
2. **修正版**：`sessions.computeIfAbsent("harry") { L06Session("harry", created) }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「共建立了」的數字大於 20（通常接近 160，也就是每輪每條執行緒各建立一個） |
| 修正版 | 「共建立了 20 個（應該是 20 個）」 |

**為什麼**：Kotlin 的 `ConcurrentMap.getOrPut` 不是原子的：先 `get`，沒有才執行預設值函式，再 `putIfAbsent`。只保證不會覆蓋已經存在的值，預設值函式在多執行緒下可能被執行多次。`containsKey` 再 `put` 也是同樣的 check-then-act。要確保只建立一次，用 `computeIfAbsent`（或 `putIfAbsent`）：對單一 key 是原子的。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L06_ConcurrentGetOrPut.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L06_ConcurrentGetOrPut.kt#fixed
```

### 演練 7：迴圈裡移除訂單：漏掉一筆，或直接丟例外（第 6 題）

**檔案**：`L07_RemoveWhileIterating.kt`

**情境**：未成交訂單清單有 3 筆：O1（掛單中）、O2（已成交）、O3（已成交）。要把已成交的訂單都從清單移除，應該只剩 O1。

**怎麼寫**

1. **Bug 版**：`for (order in orders) { if (order.filled) orders.remove(order) }`。
2. **修正版**：`orders.removeIf { it.filled }`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 電腦上的 JVM：「清理時丟出 ConcurrentModificationException：否」，「清單剩下：O1、O3」，「清單裡還留著已成交的訂單：1 筆」。Android（模擬器 API 29 實測）：「清理時丟出 ConcurrentModificationException：是」 |
| 修正版 | 「清單剩下：O1」，「清單裡還留著已成交的訂單：0 筆」 |

**為什麼**：電腦上的 JVM（OpenJDK）的 `ArrayList` 迭代器，判斷「還有沒有下一個」是比較目前位置和**目前的**大小。移除 O2 之後位置剛好等於大小，迴圈默默結束，沒有丟 `ConcurrentModificationException`，O3 根本沒被檢查到。Android 的 `ArrayList` 迭代器比較的是**迭代開始時的**大小，所以還會再取下一筆，這時發現清單被改過，丟出例外。同一段程式在兩個平台的症狀不同，所以不要依賴例外來發現這種錯誤。迭代時要修改集合，用 `removeIf`、`iterator.remove()`，或先複製一份。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L07_RemoveWhileIterating.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L07_RemoveWhileIterating.kt#fixed
```

### 演練 8：查不到的報價放進快取，直接 NPE（第 6 題）

**檔案**：`L08_ConcurrentNull.kt`

**情境**：`QuoteCache` 把查過的報價快取在 `ConcurrentHashMap`。已下市的幣種（OLD_JPY）查不到報價，API 回傳 `null`。

**怎麼寫**

1. **Bug 版**：快取是 `ConcurrentHashMap<String, Int?>`；`load(symbol)` 先查快取，沒有就 `fetchQuote(symbol)`，不管查到沒有都 `cache[symbol] = price`，最後回傳 `price`。
2. **修正版**：快取是 `ConcurrentHashMap<String, Int>`；查到才放進快取（`price?.let { cache[symbol] = it }`）。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 「查 BTC_JPY：5000000」，「查 OLD_JPY：丟出 NullPointerException」 |
| 修正版 | 「查 BTC_JPY：5000000」，「查 OLD_JPY：查不到」，沒有例外 |

**為什麼**：`HashMap` 的 key 和 value 都可以是 `null`，`ConcurrentHashMap` 都不可以，放進去執行時丟 `NullPointerException`。Kotlin 的型別系統不會替你擋下來：`ConcurrentHashMap<String, Int?>` 可以編譯。把 value 型別寫成不可為 null 的 `Int`，讓編譯器幫忙檢查，查不到的結果就不要放進去。

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L08_ConcurrentNull.kt#bug
```

```lab-solution
android/solution/kotlin/kotlin10-java-interop/L08_ConcurrentNull.kt#fixed
```

第 2 題（platform type 與 `@Nullable`）的 bug 和 Kotlin01Types 的演練 1 相同，請回去做那一個。第 3 題的 SAM 轉換與 `suspend fun` 對 Java 不友善，只能從簽名看出差別，沒有可以執行的 bug。double-checked locking 的欄位少了 `@Volatile`，在一般的電腦與手機上沒有穩定的重現方法，所以也沒有做成演練。

## 常見陷阱

- **以為 `@Volatile var count` 的 `count++` 是原子的。** 它是讀、加、寫三步，多執行緒會少算。改用 `AtomicInteger` 或鎖。
- **用 `Collections.synchronizedMap` 之後，迭代時沒有手動 `synchronized(map)`。** 單一方法有同步，但整個迭代過程沒有，仍然可能出錯。
- **把 Java 回傳的 `String!` 一路傳進 Kotlin 的程式。** null 會在離開邊界很遠的地方才出現。在邊界就宣告成明確的 `String?` 或 `String`。
- **Java 呼叫 Kotlin 的預設參數函式，卻沒有 `@JvmOverloads`。** Java 找不到簡短的版本，只能傳滿所有參數。
- **在迭代 `MutableList` 時移除元素。** 在電腦上的 JVM，移除倒數第二個元素時剛好不丟例外，而且會漏掉最後一個元素；在 Android 上同一段程式會丟 `ConcurrentModificationException`。
- **單例用 `lateinit var` 或非 volatile 的欄位做延遲初始化。** 別的執行緒可能看到沒初始化完成的物件。
- **用 `ConcurrentHashMap` 就以為什麼都安全。** `containsKey` 再 `put`、Kotlin 的 `getOrPut` 仍然有 race condition。

## 面試官想聽到的

1. 能講出 Kotlin 與 Java 混用的邊界要注意的事：platform type 與 nullability 註解、`@Throws`、`@JvmStatic`、`@JvmOverloads`、SAM、`suspend` 對 Java 不友善。
2. 能用具體例子解釋 `volatile` 提供可見性，但不提供原子性；`synchronized` 同時提供互斥與 happens-before。
3. 能分清楚 data race 與 race condition，並舉 check-then-act 的例子。
4. 知道 `HashMap` 與 `ConcurrentHashMap` 的差別（null、迭代、複合操作），以及 Kotlin `getOrPut` 的非原子性。

> [!FIELD]
> 實務上可以對照這幾個場景：
> - Java：在一個藍牙智慧鎖 SDK 專案裡，Android 端以 Kotlin 為主，專案裡同時有 Java 檔案（AES 加密的實作，AES 是常見的對稱式加密演算法）；SDK 是給合作廠商整合的，API 要考慮不同語言的呼叫端用起來順不順，這正是本單元註解的用途。
> - Kotlin Multiplatform：同一個 SDK 專案用 Kotlin Multiplatform 讓 iOS 與 Android 共用邏輯（依領域拆成 common／user／manager 模組），`@Throws` 匯出給 Swift 的規則正好用得上。
> - 並行：在一個社交 App 的 Android 版裡，`SocketManager` 用 `AtomicBoolean`（`isNotConnecting`）避免同時連線，並用 `ConcurrentLinkedQueue` 放待處理的工作；`TokenManager` 用 `synchronized(lock)`；`RealmManager` 把資料庫的存取包在單一執行緒的 dispatcher 裡。這些剛好對應本單元的 CAS、`synchronized` 鎖與執行緒封閉（所有存取都在同一個執行緒）。

## iOS / Android 對照

| 主題 | Java／Kotlin | Swift／Objective-C |
|---|---|---|
| 可見性與重排 | JMM：`volatile`、`synchronized`、happens-before | 沒有像 JMM 這樣獨立的規格；實務靠 GCD（Grand Central Dispatch，Apple 的工作佇列機制）的 queue、鎖（`OSAllocatedUnfairLock`）、actor 來保證順序與可見性；Swift 6 的 strict concurrency 會在編譯時檢查 data race |
| 單純的原子計數 | `AtomicInteger` | `Atomic`（Swift 6 的 `Synchronization` 框架，iOS 18 以上）或 `Mutex` |
| 多執行緒用的集合 | `ConcurrentHashMap`、`CopyOnWriteArrayList` | 沒有同等的標準集合；用 actor 或 serial queue 把集合包起來 |
| 兩種語言混用時的 null | Java 沒標註的型別在 Kotlin 是 platform type（`String!`）；用 `@Nullable`／`@NonNull` 補救 | Objective-C 沒標註的指標在 Swift 是隱含解包的 Optional（`String!`）；用 `_Nullable`／`_Nonnull` 或 `NS_ASSUME_NONNULL_BEGIN` 補救 |
| 混用語言的橋接 | `@JvmStatic`、`@JvmOverloads`、`@Throws` | `@objc`、`NS_SWIFT_NAME` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| Java 記憶體模型 | Java Memory Model（JMM） | Java メモリモデル |
| 可見性 | visibility | 可視性 |
| 原子性 | atomicity | 原子性 |
| happens-before 關係 | happens-before | happens-before 関係 |
| 資料競爭 | data race | データ競合 |
| 競爭條件 | race condition | 競合状態 |
| 平台型別 | platform type | プラットフォーム型 |
| CAS（比較再設定） | compare-and-set（CAS） | コンペア・アンド・セット |
