---
id: swift-objc-crash-course
title: Objective-C 速成：看得懂舊程式、能和 Swift 互通
summary: 不少 iOS 職缺會把 Objective-C 與 Swift 並列。你不需要天天寫 Objective-C，但要讀得懂舊程式、說得出 strong、weak、copy、assign 各自的用意、看得懂 block 的 weak／strong 寫法，並知道 Objective-C 和 Swift 互相呼叫時要注意什麼。
priority: P1
day: 1
tags: [objective-c, property, block, nullability, bridging, swizzling, kvo]
---

## 重點講解

不少 iOS 職缺會把 Objective-C 與 Swift 並列為必備技能。日常開發以 Swift 為主的人，這個單元的定位是：**看得懂舊的 Objective-C 程式、說得出重點觀念、能和 Swift 互通**，不是要你背完整的語法。

Objective-C（以下簡稱 ObjC）是 Swift 出現之前 iOS 的主要語言。很多歷史悠久的 App 和第三方 SDK 還是 ObjC 寫的，所以面試官常用幾個問題確認你「讀得懂、改得動」。這個單元回答七個問題：

1. `@property` 後面的 `nonatomic`、`strong`、`weak`、`copy`、`assign` 各是什麼意思？
2. 為什麼字串屬性要用 `copy`？
3. ObjC 的 block 怎麼捕獲變數？weak／strong 的寫法是什麼？
4. nullability（可不可以是空值的標註）是什麼？Swift 看到的 ObjC API 長什麼樣子？
5. Swift 和 ObjC 要怎麼互相呼叫？
6. 對 `nil` 傳訊息會怎樣？什麼是 method swizzling？（比較少考，被問到能答就好）
7. KVO 與 category 是什麼？（比較少考，被問到能答就好）

### 1. `@property` 後面的 `nonatomic`、`strong`、`weak`、`copy`、`assign` 各是什麼意思？

`@property` 用來宣告一個屬性，編譯器會自動替它產生儲存用的實例變數（ivar，名稱是底線加屬性名，例如 `_symbol`）以及 getter、setter。括號裡的詞叫做屬性特性（attribute），決定這個屬性怎麼持有資料。

要看懂這些特性，先複習 ARC（Automatic Reference Counting，自動引用計數）：ObjC 和 Swift 一樣用引用計數管理物件的記憶體，每多一個「持有」就 +1，歸零就釋放。`strong`、`weak` 這些詞，說的就是「這個屬性要不要讓物件的計數 +1」。

| 特性 | 意思 | 什麼時候用 | Swift 的對應寫法 |
|---|---|---|---|
| `strong` | 持有，計數 +1 | 一般物件的預設 | 一般的 `var` |
| `weak` | 不持有；物件釋放後，屬性自動變成 `nil` | delegate、指回父層的屬性，避免互相持有 | `weak var` |
| `copy` | 設值時複製一份，持有複製品 | `NSString`、`NSArray`、`NSDictionary`、block | `String`、`Array` 本身就是值型別，不需要 |
| `assign` | 單純賦值，不持有 | 基本型別（`int`、`double`、`BOOL`、`NSInteger`） | 一般的 `var` |
| `nonatomic` | 不做執行緒同步 | iOS 的慣例，幾乎都寫它 | 沒有對應 |
| `atomic`（預設） | 單次讀或寫是完整的 | 很少明確使用 | 沒有對應 |
| `readonly` | 只有 getter | 對外唯讀 | `private(set) var` |
| `nullable`、`nonnull` | 可不可以是 `nil`（見第 4 題） | 讓 Swift 匯入正確 | `Optional` 或非 `Optional` |

一個典型的類別介面看起來像這樣：

```objectivec
#import <Foundation/Foundation.h>

@protocol OrderDelegate;
@class OrderViewModel;

@interface OrderController : NSObject
@property (nonatomic, weak) id<OrderDelegate> delegate;       // 不持有 delegate，避免互相持有
@property (nonatomic, copy) NSString *symbol;                  // 字串用 copy
@property (nonatomic, strong) OrderViewModel *viewModel;       // 一般物件用 strong
@property (nonatomic, assign) NSInteger lots;                  // 基本型別用 assign
@property (nonatomic, copy) void (^onClose)(void);             // block 用 copy
@property (nonatomic, readonly) NSString *title;               // 對外唯讀
@end
```

兩個最常被問到的重點：

**`atomic` 不等於執行緒安全。** `atomic`（預設值）只保證單次的 getter 或 setter 不會讀到寫了一半的資料。`self.count += 1` 其實是「讀、加、寫」三步，兩個執行緒同時做仍然會算錯。而且 `atomic` 比較慢，所以 iOS 的慣例都寫 `nonatomic`，需要並行安全時另外用鎖或 serial queue（一次只執行一個工作的佇列）處理。

**`assign` 用在物件上會變成野指標。** `assign` 不會讓計數 +1，物件被釋放後，屬性還指著那塊已經釋放的記憶體（這叫野指標，dangling pointer），之後使用就會隨機 crash。物件要「不持有」請用 `weak`，它會在物件釋放時自動變成 `nil`。

> [!TIP]
> 面試時可以這樣說：「一般物件用 `strong`；delegate 和指回父層的參考用 `weak`，避免互相持有；字串、陣列、字典和 block 用 `copy`；基本型別用 `assign`。`nonatomic` 是 iOS 的慣例，因為 `atomic` 只保證單次存取完整，並不是真的執行緒安全。」

### 2. 為什麼字串屬性要用 `copy`？

`NSString` 有一個可變的子類 `NSMutableString`。因此，一個型別是 `NSString *` 的變數，實際上可能指向一個**之後會被修改**的可變字串。如果屬性用 `strong`，只是持有同一個物件，外部之後修改它，你的屬性值就偷偷跟著變了。`copy` 則在設值的當下複製一份，之後外部怎麼改都不影響。

```objectivec
#import <Foundation/Foundation.h>

@interface Order : NSObject
@property (nonatomic, strong) NSString *strongSymbol;
@property (nonatomic, copy) NSString *copiedSymbol;
@end
@implementation Order
@end

int main(void) {
    @autoreleasepool {
        Order *order = [Order new];
        NSMutableString *m = [@"BTC" mutableCopy];   // 可變字串
        order.strongSymbol = m;                       // strong：持有的就是 m 本身
        order.copiedSymbol = m;                       // copy：複製了一份 "BTC"
        [m appendString:@"/JPY"];                     // 修改 m
        NSLog(@"%@ %@", order.strongSymbol, order.copiedSymbol);
        // 輸出：BTC/JPY BTC
    }
    return 0;
}
```

放心使用 `copy` 的另一個原因是成本很低：如果傳進來的本來就是不可變的 `NSString`，`copy` 通常只是「多持有一次」，不會真的複製資料。所以 `NSString`、`NSArray`、`NSDictionary` 這類有可變子類的型別，慣例都用 `copy`，這是一種防禦性寫法。

block 屬性也慣例用 `copy`。這是歷史原因：以前沒有 ARC 的時代，block 一開始建立在 stack 上，必須 `copy` 才會搬到 heap 保存。現在 ARC 會自動處理，但慣例還是保留，看到舊程式碼要看得懂。

對照 Swift：`String`、`Array` 本來就是值型別，複製後各自獨立（見值型別與 COW 單元），所以 Swift 沒有這個問題。

> [!TIP]
> 面試時可以這樣說：「`NSString` 可能指向可變的 `NSMutableString`，用 `strong` 的話，外部之後修改它，我的屬性值會被偷偷改掉；用 `copy` 在設值時複製一份就不會。對不可變字串來說，`copy` 幾乎沒有成本。」

### 3. ObjC 的 block 怎麼捕獲變數？weak／strong 的寫法是什麼？

block 是 ObjC 版的 closure，宣告長得像函式指標：`void (^handler)(double price)` 的意思是「一個名叫 handler、接收一個 double、沒有回傳值的 block」。

**捕獲變數的規則和 Swift 相反。** ObjC 的 block 預設**以值捕獲**外部的區域變數（建立 block 當下複製一份），所以之後外部改了變數，block 裡看到的還是舊值，而且 block 裡不能修改它。要在 block 裡修改，或要看到外部後來的改動，變數要加 `__block`：

```objectivec
int x = 1;
void (^byValue)(void) = ^{ printf("%d ", x); };    // 捕獲建立當下的值
__block int y = 1;
void (^byRef)(void) = ^{ printf("%d\n", y); };     // __block：和外部共用同一份
x = 2;
y = 2;
byValue();   // 輸出：1
byRef();     // 輸出：2
```

Swift 的 closure 剛好相反：預設捕獲變數本身，要用 capture list（`[x]`）才是捕獲值（見 ARC 單元）。

**weak／strong 的寫法，用來避免循環參考。** 如果 block 存成物件自己的屬性，而 block 裡又直接用 `self`，物件持有 block、block 持有物件，形成循環，永遠不會釋放（編譯器會警告 `Capturing 'self' strongly in this block is likely to lead to a retain cycle`）。標準寫法是兩步：

```objectivec
#import <Foundation/Foundation.h>

@interface PriceListener : NSObject
@property (nonatomic, copy) void (^handler)(double price);   // block 屬性用 copy
- (void)start;
- (void)step1;
- (void)step2;
@end

@implementation PriceListener
- (void)start {
    __weak typeof(self) weakSelf = self;                  // 步驟 1：block 外面先拿一個 weak 參考
    self.handler = ^(double price) {
        __strong typeof(weakSelf) strongSelf = weakSelf;  // 步驟 2：block 一開始，轉成 strong
        if (!strongSelf) { return; }                      // self 已經釋放就直接結束
        [strongSelf step1];
        [strongSelf step2];
    };
}
- (void)step1 {}
- (void)step2 {}
@end
```

為什麼 block 裡還要多一步轉成 `strongSelf`？如果只寫 `[weakSelf step1]; [weakSelf step2];`，雖然不會 crash（對 `nil` 傳訊息是安全的，見第 6 題），但在兩個呼叫之間，`weakSelf` 可能剛好變成 `nil`，結果 `step1` 做了、`step2` 沒做，邏輯只執行一半。轉成 `strongSelf` 後，block 執行期間會持有它，要嘛全做、要嘛全不做。這和 Swift 的 `[weak self]` 加 `guard let self` 是同一件事（見 ARC 單元）。

舊的程式碼常看到 `@weakify(self)` 和 `@strongify(self)`，那是第三方函式庫（libextobjc）提供的巨集，作用就是上面這兩行。

> [!TIP]
> 面試時可以這樣說：「ObjC 的 block 預設以值捕獲，要修改要加 `__block`。避免循環參考的標準寫法是 weak／strong 兩步：block 外面 `__weak`，block 開頭轉成 `__strong` 並檢查是否為 `nil`，這樣執行期間 self 不會中途被釋放。這等同 Swift 的 `[weak self]` 加 `guard let self`。」

### 4. nullability 是什麼？Swift 看到的 ObjC API 長什麼樣子？

nullability 是在 ObjC 的宣告裡標註「這個指標可不可以是 `nil`」：`nullable`（可以是 `nil`）、`nonnull`（不會是 `nil`）。在 Swift 眼中，這會決定一個 ObjC API 被匯入成 `String?` 還是 `String`。

最省力的做法是用 `NS_ASSUME_NONNULL_BEGIN` 和 `NS_ASSUME_NONNULL_END` 包住整個 header：區間內沒有特別標的指標，都當作 `nonnull`，只需要替真正可能是空的標 `nullable`。

```objectivec
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN
@interface Order : NSObject
@property (nonatomic, copy) NSString *symbol;                      // Swift：String
@property (nonatomic, copy, nullable) NSString *memo;              // Swift：String?
@property (nonatomic, strong) NSArray<NSString *> *tags;           // Swift：[String]
- (nullable NSDecimalNumber *)priceForSymbol:(NSString *)symbol;   // Swift：price(forSymbol:) -> NSDecimalNumber?
@end
NS_ASSUME_NONNULL_END

@interface Legacy : NSObject          // 這個 header 沒有任何標註
@property (nonatomic, copy) NSString *name;                        // Swift：String!
@property (nonatomic, strong) NSArray *items;                      // Swift：[Any]!
@end
```

**沒有標註的宣告，在 Swift 裡會被匯入成 implicitly unwrapped optional**，也就是 `String!`。它可以直接當成 `String` 使用，不用 unwrap，但如果實際是 `nil`，一用就會 crash。等於把「到底會不會是空」的風險丟給呼叫端，所以舊的 ObjC 程式碼要盡量補上 nullability 標註。

還有兩個和 Swift 匯入有關的標註：

- **輕量泛型**：把 `NSArray *` 寫成 `NSArray<NSString *> *`，Swift 就會得到 `[String]`；沒寫的話是 `[Any]`，要自己轉型。
- **`NS_SWIFT_NAME`**：可以改善匯入 Swift 後的方法命名，讓 Swift 這邊讀起來更自然。

> [!TIP]
> 面試時可以這樣說：「ObjC 沒標 nullability 的指標，Swift 會匯入成 `T!`，等於呼叫端要自己承擔 `nil` 的風險。我會用 `NS_ASSUME_NONNULL_BEGIN／END` 包住 header，只替真的可能為空的標 `nullable`，再加上輕量泛型，這樣 Swift 這邊拿到的型別就乾淨了。」

### 5. Swift 和 ObjC 要怎麼互相呼叫？

在同一個專案裡，兩個方向各有一個橋梁：

- **Swift 呼叫 ObjC：bridging header（橋接標頭）。** App target 建一個叫 `<Target>-Bridging-Header.h` 的檔案，在 Build Settings 的 Objective-C Bridging Header 指定它，裡面 `#import` 要給 Swift 用的 ObjC header，Swift 就能直接使用那些類別。如果是 framework，則是透過 umbrella header（把所有公開標頭集中的總標頭）和 module map，讓 Swift 用 `import 模組名稱`。
- **ObjC 呼叫 Swift：`<Target>-Swift.h`。** 這是編譯器自動產生的標頭，在 `.m` 檔裡 `#import "<Target>-Swift.h"`。Swift 的類別要繼承 `NSObject`，並且用 `@objc` 標出要給 ObjC 看的成員（或在類別前面加 `@objcMembers`，整個類別的成員都公開）。

```swift
import Foundation

@objcMembers
final class Quote: NSObject {          // 繼承 NSObject，ObjC 才看得到
    var symbol: String                 // 會匯出成 NSString，並且是 copy
    var price: Double
    init(symbol: String, price: Double) { self.symbol = symbol; self.price = price }
    func displayText() -> String { "\(symbol) \(price)" }
}

struct Tick { let price: Double }                 // struct：ObjC 看不到
enum Side { case buy(Double), sell(Double) }      // 有關聯值的 enum：ObjC 看不到
@objc enum OrderSide: Int { case buy, sell }      // 純整數的 enum：ObjC 看得到
```

Swift 專有的功能沒辦法暴露給 ObjC：泛型、有關聯值的 enum、struct、tuple（多個值組成的組合）、全域函式。要給 ObjC 用的資料，常見做法是用 class 包一層。

**ObjC 的非同步 API，Swift 可以直接用 `async`。** 帶 completion handler 的 ObjC 方法（最後一個參數是 `(void (^)(X * _Nullable, NSError * _Nullable))`），從 Swift 5.5 開始，會自動匯入成 `async throws` 的方法。舊的 SDK 包裝不用改，就能在 Swift 用 `await`：

```swift
// ObjC 宣告：- (void)fetchQuoteForSymbol:(NSString *)symbol
//                     completion:(void (^)(NSDecimalNumber *_Nullable quote, NSError *_Nullable error))completion;
func load(order: Order) async throws {
    let quote = try await order.fetchQuote(forSymbol: "btcjpy")   // 自動匯入成 async throws
    print(quote)
}
```

同樣地，ObjC 的 `NSError **` 錯誤參數，會被匯入成 Swift 的 `throws`。

> [!TIP]
> 面試時可以這樣說：「Swift 呼叫 ObjC 用 bridging header，ObjC 呼叫 Swift 用自動產生的 `-Swift.h`，Swift 的類別要繼承 `NSObject` 並標 `@objc`。泛型、有關聯值的 enum、struct 都無法暴露給 ObjC。帶 completion handler 的 ObjC 方法，Swift 會自動匯入成 `async throws`，舊 SDK 不用改就能用 async／await。」

### 6. 對 `nil` 傳訊息會怎樣？什麼是 method swizzling？（比較少考，被問到能答就好）

ObjC 呼叫方法的說法是「**傳訊息**」：`[obj doSomething]` 是把 `doSomething` 這個訊息傳給 `obj`，執行時才去找對應的實作。這帶來兩個和 Swift 很不同的行為：

- **對 `nil` 傳訊息是安全的**，不會 crash，回傳 0 或 `nil`。好處是少寫很多判斷；壞處是邏輯錯誤被靜默吞掉，和 Swift 的 Optional 完全不同。
- **傳了物件不認得的訊息，執行時會 crash。** 錯誤訊息是 `-[Quote nonexistent]: unrecognized selector sent to instance …`（`NSInvalidArgumentException`）。直接呼叫沒有宣告過的方法，ARC 下會編譯錯誤；會拖到執行時才 crash 的，是「有宣告但沒有實作」（例如 category 沒被連結進來）、用 `performSelector:` 動態呼叫，或物件的型別轉錯了。

```objectivec
Order *nothing = nil;
NSLog(@"%@", nothing.strongSymbol);    // 輸出：(null)，沒有 crash
double d = [(NSNumber *)nothing doubleValue];
NSLog(@"%f", d);                       // 輸出：0.000000
```

**method swizzling（方法交換）** 是利用這個執行時機制，在執行期把兩個方法的實作對調。Firebase 等 SDK 就會用它來攔截系統事件，也有 App 的自建多語系系統用它攔截 `NSLocalizedString`。

```objectivec
#import <Foundation/Foundation.h>
#import <objc/runtime.h>

@interface Greeter : NSObject
- (void)hello;
@end
@implementation Greeter
- (void)hello { printf("hello\n"); }
@end

@interface Greeter (Swizzle)
- (void)xo_hello;
@end
@implementation Greeter (Swizzle)
+ (void)load {                                  // 程式啟動時執行
    static dispatch_once_t once;
    dispatch_once(&once, ^{                     // 確保只交換一次
        Method original = class_getInstanceMethod(self, @selector(hello));
        Method swizzled = class_getInstanceMethod(self, @selector(xo_hello));
        method_exchangeImplementations(original, swizzled);
    });
}
- (void)xo_hello {
    printf("before\n");
    [self xo_hello];       // 交換之後，這行實際執行的是原本的 hello
    printf("after\n");
}
@end

// [[Greeter new] hello] 的輸出：before、hello、after
```

規則：在 `+load` 裡搭配 `dispatch_once` 交換；新方法名稱加上前綴避免和別人衝突；一定要呼叫原本的實作。風險：和其他 SDK 的 swizzle 互相覆蓋、呼叫順序難以預測、系統更新後內部實作改變、除錯困難。Swift 的方法要能被 swizzle，必須標成 `@objc dynamic`。

> [!TIP]
> 面試時可以這樣說：「ObjC 是傳訊息，對 `nil` 傳訊息是安全的、回傳 0 或 `nil`，所以邏輯錯誤會被靜默吞掉；傳了不認得的訊息則是執行時 crash。method swizzling 是執行期對調兩個方法的實作，要在 `+load` 搭配 `dispatch_once`，並且一定要呼叫原實作，風險是和其他 SDK 互相覆蓋。」

### 7. KVO 與 category 是什麼？（比較少考，被問到能答就好）

**KVO（Key-Value Observing）** 讓你可以「觀察某個物件的某個屬性，值一變就通知我」。

- ObjC：用 `addObserver:forKeyPath:options:context:` 註冊，用 `removeObserver:forKeyPath:` 移除，兩者要成對；移除沒有註冊過的 observer 會 crash（`NSRangeException`）。`context` 要用唯一的指標，避免父類別和子類別的觀察互相混淆。
- Swift：屬性要標 `@objc dynamic`，而且要在 `NSObject` 的子類別裡，然後用 `observe(\.price, options:changeHandler:)`。回傳的 token 被釋放時，觀察會自動移除，比手動 add／remove 安全很多。

```swift
import Foundation

final class Quote: NSObject {
    @objc dynamic var price: Double = 0
}

let q = Quote()
let token = q.observe(\.price, options: [.old, .new]) { _, change in
    print(change.oldValue ?? 0, "->", change.newValue ?? 0)
}
q.price = 100   // 輸出：0.0 -> 100.0
q.price = 101   // 輸出：100.0 -> 101.0
```

**category（分類）** 是 ObjC 替既有類別加方法的方式，包括系統類別，寫法是 `@interface Foo (Name)`。和它長得很像的是 **class extension（類別擴充）**，寫法是 `@interface Foo ()`，通常放在 `.m` 檔裡宣告私有屬性。Swift 的 extension 則兩者都不一樣：

| | Category | Class extension | Swift extension |
|---|---|---|---|
| 加方法 | 可以，對既有類別（包括系統類別） | 可以，但必須和主類別在同一個編譯單元 | 可以 |
| 加屬性 | 不能有實例變數（只能用 associated object 變通） | 可以加私有屬性與實例變數 | 只能加計算屬性 |
| 有沒有名稱 | 有 | 沒有 | 沒有，但可以加協定遵循 |

category 的陷阱有兩個：兩個 category 加了同名方法，執行結果未定義；放在靜態函式庫裡的 category，App 要加 `-ObjC` 這個 linker flag（連結器參數）才會被載入。

> [!TIP]
> 面試時可以這樣說：「KVO 是觀察屬性變化，ObjC 的 add／remove 要成對，否則會 crash；Swift 用 `observe(\.price)`，token 釋放就自動移除，更安全。category 可以替既有類別加方法，但不能加實例變數，class extension 才可以。」

> [!FIELD]
> 以 Swift 為主的專案，Objective-C 的定位是「看得懂、能維護、能和 Swift 互通」。實務上會接觸到 ObjC 邊界的地方有：一個藍牙（BLE，Bluetooth Low Energy）智慧鎖 SDK 專案，Swift 端呼叫的 framework 標頭是 ObjC 寫的（例如 `NSData+AES128.h`）；一個社交 App 自建的多語系系統，用 `Bundle` 的 method swizzling 攔截舊的 `NSLocalizedString`，讓舊程式不必改就能讀到新的字串；一個即時行情 App 的 K 線 WebView 用 `WKScriptMessageHandler`，這是 `NSObject` 協定。
> 面試時可以這樣說：「我日常以 Swift 為主，Objective-C 我能閱讀與維護，也處理過 Swift 與 ObjC 的互通，包含呼叫 ObjC 標頭的 framework，以及用 runtime 的 method swizzling 做多語系。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift11ObjC**。
> **檔案**：這個單元的 Objective-C 程式放在另一個 Swift Package target（一個 target 不能同時有 Swift 和 Objective-C）。你寫在 `labs/ios/Practice/SwiftObjC/`（`include/PRLab…h` 與 `PRLab…m`）與 `labs/ios/Practice/Swift/Swift11-ObjC/`；解答在 `labs/ios/Solution/SwiftObjC/`（`SOLab…`）與 `labs/ios/Solution/Swift/Swift11-ObjC/`。Objective-C 的類別名稱是全域的，所以 Practice 用 `PRLab` 前綴、Solution 用 `SOLab` 前綴，避免同一個 App 裡衝突。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **當掉的演練**：演練 1、4 的 Bug 版寫對了會讓 App 當掉，要從 Xcode 執行才看得到當掉的位置。

### 演練 1：關掉下單畫面之後，成交通知讓 App 當掉（第 1 題）

**檔案**：`PRLabL01.h`（Objective-C）與 `L01_AssignDangling.swift`

**情境**：舊的 Objective-C 下單控制器（OrderController）用 delegate 通知畫面「委託成交了」。畫面關掉之後，控制器還活著，成交通知又來了一次。

**怎麼寫**

1. **Bug 版**：在 `PRLabL01.h` 把 Bug 版 OrderController 的 `delegate` 屬性寫成 `assign`。
2. **修正版**：把修正版 OrderController 的 `delegate` 屬性寫成 `weak`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 第一次通知正常；畫面釋放後第二次通知，App 當掉（`EXC_BAD_ACCESS`） |
| 修正版 | 第二次通知結果：沒有人可以通知，不會當掉 |

**為什麼**：`assign` 不持有物件，但物件釋放後，屬性不會自動變成 `nil`，變成指向已釋放記憶體的野指標（dangling pointer），之後傳訊息給它就會隨機 crash。`weak` 同樣不持有，但物件釋放時會自動變成 `nil`。`assign` 只該用在 `int`、`BOOL` 這類基本型別。

**Objective-C 解答**

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL01.h#bug
```

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL01.h#fixed
```

**Swift 解答**

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L01_AssignDangling.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L01_AssignDangling.swift#fixed
```

### 演練 2：下單物件的幣種被別人偷偷改掉（第 2 題）

**檔案**：`PRLabL02.h`（Objective-C）與 `L02_StringCopy.swift`

**情境**：行情解析器為了省記憶體，共用一個 `NSMutableString` 緩衝來組字串。下單物件從緩衝取出幣種「BTC」存起來，解析器接著把同一個緩衝繼續往後接，變成 BTC/JPY。

**怎麼寫**

1. **Bug 版**：在 `PRLabL02.h` 把 Bug 版 Order 的 `symbol` 屬性寫成 `strong`。
2. **修正版**：把修正版 Order 的 `symbol` 屬性寫成 `copy`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 緩衝被接長之後，`order.symbol` 變成 BTC/JPY |
| 修正版 | `order.symbol` 還是 BTC |

**為什麼**：`NSString *` 實際上可能指向可變的 `NSMutableString`。`strong` 持有的就是傳進來的同一個物件，外部之後修改它，屬性值就跟著變；`copy` 在設值的當下複製一份，之後外部怎麼改都不影響。如果傳進來的本來就是不可變的字串，`copy` 通常只是多持有一次，幾乎沒有成本。

**Objective-C 解答**

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL02.h#bug
```

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL02.h#fixed
```

**Swift 解答**

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L02_StringCopy.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L02_StringCopy.swift#fixed
```

### 演練 3：價格監聽器關掉之後沒有被釋放（第 3 題）

**檔案**：`PRLabL03.m`（Objective-C）與 `L03_BlockRetainCycle.swift`

**情境**：`PriceListener` 把「收到價格要做的事」存成 block 屬性 `handler`，收到價格時更新 `lastPrice`。

**怎麼寫**

1. **Bug 版**：在 `PRLabL03.m` 的 Bug 版 `start` 裡，`handler` 的 block 直接用 `self.lastPrice = price`。編譯器會警告 `Capturing 'self' strongly in this block is likely to lead to a retain cycle`，用 `#pragma clang diagnostic ignored "-Warc-retain-cycles"` 包起來，刻意不理它。
2. **修正版**：改成 weak／strong 兩步：block 外面 `__weak typeof(self) weakSelf = self;`，block 一開始 `__strong typeof(weakSelf) strongSelf = weakSelf;`，如果是 `nil` 就直接 `return`，其他地方都用 `strongSelf`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 收到價格 101 之後 `lastPrice` 是 101；離開之後還活著 1 個 PriceListener |
| 修正版 | `lastPrice` 是 101；離開之後還活著 0 個 |

**為什麼**：物件持有 block（`handler` 屬性），block 又持有物件（用了 `self`），繞成一圈（retain cycle），離開之後永遠不會釋放。block 開頭多轉一步成 `strongSelf`，是為了 block 執行期間 self 不會中途被釋放，要嘛全做、要嘛全不做。這和 Swift 的 `[weak self]` 加 `guard let self` 是同一件事。

**Objective-C 解答**

```lab-solution
ios/Solution/SwiftObjC/SOLabL03.m#bug
```

```lab-solution
ios/Solution/SwiftObjC/SOLabL03.m#fixed
```

**Swift 解答**

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L03_BlockRetainCycle.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L03_BlockRetainCycle.swift#fixed
```

### 演練 4：舊 SDK 回傳 nil，Swift 端一用就當掉（第 4 題）

**檔案**：`L04_Nullability.swift`（Bug 版）與 `PRLabL04.h`（修正版，Objective-C）

**情境**：舊的 Objective-C SDK 提供 `memoForOrder:`，這筆委託沒有備註時回傳 `nil`。Swift 端把它當成 `String` 來用。

**怎麼寫**

1. **Bug 版**：舊 SDK 的 header（`PRLabL04Legacy.h`）已經寫好，完全沒有 nullability 標註。在 Swift 的 Bug 版 `run` 裡，寫 `let memo: String = service.memo(forOrder: "BTC-1")`，再印出 `memo.count`。
2. **修正版**：在 `PRLabL04.h` 用 `NS_ASSUME_NONNULL_BEGIN／END` 包住整個 header，並把 `memoForOrder:` 的回傳型別標成 `nullable`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | symbol 的型別是 `Optional<String>`（`String!`）；`let memo: String` 接回傳值時 App 當掉：`Unexpectedly found nil while implicitly unwrapping an Optional value` |
| 修正版 | symbol 的型別是 `String`；`memo(forOrder:)` 的型別是 `Optional<String>`；`nil` 被 `if let` 處理 |

**為什麼**：沒有標註的指標，Swift 匯入成 implicitly unwrapped optional（`String!`）：可以直接當 `String` 用、不用 unwrap，但實際是 `nil` 時一用就 crash。標上 `nullable` 之後，Swift 看到 `String?`，編譯器強迫你先處理 `nil`；用 `NS_ASSUME_NONNULL_BEGIN／END` 包起來，沒特別標的指標都是乾淨的 `String`。

**Objective-C 解答**

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL04Legacy.h
```

```lab-solution
ios/Solution/SwiftObjC/include/SOLabL04.h
```

**Swift 解答**

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L04_Nullability.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L04_Nullability.swift#fixed
```

### 演練 5：手續費被靜默算成 0（第 6 題）

**檔案**：`PRLabL05.m`（Objective-C）與 `L05_NilMessaging.swift`

**情境**：Objective-C 的 FeeCalculator 用手續費率表（`table`）算手續費：金額乘以費率。呼叫端忘了設定 `table`，它是 `nil`。

**怎麼寫**

1. **Bug 版**：在 `PRLabL05.m` 的 Bug 版 `feeForAmount:symbol:` 直接回傳 `amount * [self.table rateForSymbol:symbol]`，不檢查 `table`。
2. **修正版**：先檢查 `self.table == nil`：是的話，如果 `error` 不是 `NULL`，填入一個 `NSError`（`NSLocalizedDescriptionKey` 寫「手續費率表還沒設定」），回傳 `nil`；不是才算出手續費回傳。Swift 端會看到 `throws`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 手續費：0.0 日圓。沒有 crash、沒有錯誤 |
| 修正版 | 忘了設定率表時得到「手續費率表還沒設定」；設定之後手續費是 1000 日圓 |

**為什麼**：Objective-C 的方法呼叫是「傳訊息」，對 `nil` 傳訊息是安全的，回傳 0 或 `nil`。好處是少寫很多判斷；壞處是邏輯錯誤被靜默吞掉，使用者少付了手續費，要很久之後才被發現。和 Swift 的 Optional 完全不同：Swift 要先 unwrap，沒設定就會被編譯器或 `guard` 擋下來。

**Objective-C 解答**

```lab-solution
ios/Solution/SwiftObjC/SOLabL05.m#bug
```

```lab-solution
ios/Solution/SwiftObjC/SOLabL05.m#fixed
```

**Swift 解答**

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L05_NilMessaging.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift11-ObjC/L05_NilMessaging.swift#fixed
```

第 5 題（Swift 與 Objective-C 互相呼叫）要靠 App target 的 bridging header 與自動產生的 `-Swift.h`，這個 Package 結構沒辦法重現；第 7 題（KVO 與 category）是比較少考的概念題，也沒有演練。

## 常見陷阱

- **字串、陣列屬性用 `strong` 而不是 `copy`。** 外部傳入可變物件之後被修改，屬性值跟著變。
- **block 裡直接用 `self`，又把 block 存成自己的屬性。** 造成循環參考，物件永遠不會釋放。
- **只寫 `weakSelf`，沒有在 block 開頭轉成 `strongSelf`。** 多步驟的邏輯可能只執行到一半。
- **對物件屬性使用 `assign`。** 物件釋放後變成野指標，之後使用會隨機 crash。物件的「不持有」要用 `weak`。
- **ObjC header 沒有標註 nullability。** Swift 會匯入成 `T!`，實際是 `nil` 時使用就 crash。
- **以為 `atomic` 就是執行緒安全。** 它只保證單次存取完整，`count += 1` 這類複合操作仍然會出錯。
- **swizzle 時沒有呼叫原本的實作，或沒有搭配 `dispatch_once`。** 行為會出錯或被重複交換。
- **KVO 的 add／remove 沒有成對。** 移除沒有註冊過的 observer 會 crash。

## 面試官想聽到的

1. 能說出每個 `@property` 特性的理由，特別是 `copy`（可變子類的問題）與 `weak`（避免循環參考）。
2. 熟悉 block 的捕獲規則（預設以值捕獲、`__block`）與 weak／strong 的寫法，並能對應到 Swift 的 `[weak self]` 加 `guard let self`。
3. 理解 ObjC 的訊息傳遞，知道對 `nil` 傳訊息與 unrecognized selector 的行為差異。
4. 知道 Swift 與 ObjC 互通要注意 nullability、泛型標註與 bridging header，以及 completion handler 會自動匯入成 `async`。
5. 對 swizzling 與 KVO 的風險有基本認識，被問到能清楚說明。

> [!WARNING]
> 面試官常追問：「你在 Swift 專案裡，遇到沒標 nullability 的 ObjC API 怎麼辦？」不要只回答「用 `!`」。要說出：自己補上 `NS_ASSUME_NONNULL_BEGIN／END` 與 `nullable`；如果是不能改的第三方 SDK，在 Swift 端用 `if let` 或 `guard let` 當作 Optional 處理。

## iOS / Android 對照

| 主題 | iOS（Objective-C／Swift） | Android（Java／Kotlin） |
|---|---|---|
| 空值標註 | `nullable`／`nonnull`，沒標的匯入成 `T!` | `@Nullable`／`@NonNull`，沒標的 Java 型別在 Kotlin 是 platform type（`String!`） |
| 執行緒安全的屬性 | `atomic`（只保證單次存取） | `volatile`、`synchronized`、`AtomicReference` |
| 執行期修改行為 | method swizzling | 沒有等價的官方手段；用 Proxy、AOP（aspect-oriented programming，在不改原始碼的情況下插入行為）或位元組碼工具（ASM、ByteBuddy）、Hook 框架 |
| 觀察屬性變化 | KVO | `LiveData`、`Flow`，或 `Delegates.observable` |
| 擴充既有類別 | category、Swift extension | Kotlin 擴充函式（靜態解析，並沒有真的修改類別） |
| 互通的標註 | `NS_SWIFT_NAME`、`NS_REFINED_FOR_SWIFT` | `@JvmStatic`、`@JvmOverloads`、`@JvmField`、`@JvmName` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 屬性特性 | property attribute | プロパティ属性 |
| 野指標 | dangling pointer | ダングリングポインタ |
| 循環參考 | retain cycle | 循環参照 |
| 橋接標頭 | bridging header | ブリッジングヘッダ |
| 訊息傳遞 | message passing | メッセージ送信 |
| 方法交換 | method swizzling | メソッドスウィズリング |
| 分類 | category | カテゴリ |
