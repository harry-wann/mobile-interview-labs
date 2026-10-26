---
id: swift-combine-basics
title: Combine 基礎（RxSwift 對照版）：Observable、DisposeBag、Relay、Driver 在 Combine 裡叫什麼
summary: 給寫過 RxSwift、還沒正式用過 Combine 的 iOS 工程師。每個概念都先說 RxSwift 裡你會怎麼寫，再給 Combine 的等價寫法，最後只講兩邊不一樣、會讓你寫出 bug 的地方：訂閱沒存起來會立刻被取消、Future 一建立就執行、subscribe(_:) 會把 subject 結束掉、沒有 withLatestFrom、錯誤型別寫在泛型裡、receive(on:) 和 Driver 的差別。
priority: P1
day: 1
tags: [combine, rxswift, publisher, anycancellable, future, deferred, subject, withlatestfrom, error-handling, receive-on]
---

## 重點講解

這個單元寫給寫過 RxSwift、還沒正式用過 Combine 的 iOS 工程師。`Observable`、`DisposeBag`、`BehaviorRelay`、`flatMapLatest`、`Driver` 你都會，也知道 cold／hot、錯誤會讓串流結束，所以每個概念都先說 RxSwift 怎麼寫，再給 Combine 的等價寫法，最後只講差異。不是一對一的地方，標「大致對應」。

Combine 是 Apple 從 iOS 13 開始內建的響應式框架（reactive framework，用「資料流＋運算子」描述事件），概念和 RxSwift 幾乎一樣，所以面試官很常問「你會 Rx，那 Combine 呢？」。真正會出事的是那些「名字很像、行為不一樣」的地方。

這個單元回答七個面試常見問題：

1. `Observable`、`Observer`、`Disposable` 在 Combine 叫什麼？`DisposeBag` 怎麼換？
2. `Single`、`Completable`、`Observable.create` 在 Combine 怎麼寫？為什麼 `Future` 要包 `Deferred`？
3. `PublishRelay`、`BehaviorRelay` 對應什麼？為什麼 `bind(to:)` 不能直接換成 `subscribe(_:)`？
4. 常用運算子怎麼對應？沒有 `withLatestFrom` 怎麼辦？
5. 錯誤處理：`Failure` 型別是什麼？`catch` 要放在哪裡？
6. `observe(on:)`、`Driver`、`bind(to:)` 在 Combine 怎麼寫？
7. 專案裡 RxSwift 和 Combine 怎麼共存、怎麼遷移？

範例都是交易所 App 的情境：行情推送、資產餘額、下單按鈕、委託列表。`debounce`、`throttle`、`share`、`switchToLatest` 的細節在 Swift06 第 4 題，`sink` 的 retain cycle 在 Swift02，這裡不重複。

### 1. `Observable`、`Observer`、`Disposable` 在 Combine 叫什麼？`DisposeBag` 怎麼換？

**`Observable` 對應 `Publisher`，`subscribe(onNext:)` 對應 `sink`，`Disposable` 對應 `AnyCancellable`，`DisposeBag` 對應 `Set<AnyCancellable>`。最大的差別是生命週期方向相反：RxSwift 忘了 `disposed(by:)`，訂閱會一直活著（洩漏）；Combine 忘了把 `AnyCancellable` 存起來，訂閱會立刻被取消（一筆都收不到）。**

| RxSwift | Combine | 差異 |
|---|---|---|
| `Observable<Element>` | `Publisher` 協定；對外傳 `AnyPublisher<Output, Failure>` | Combine 把錯誤型別也寫進泛型（第 5 題） |
| `asObservable()` | `eraseToAnyPublisher()` | 都是把具體型別藏起來 |
| `subscribe(onNext:onError:onCompleted:)` | `sink(receiveCompletion:receiveValue:)` | `onError`、`onCompleted` 合成一個 `Subscribers.Completion`（`.finished` 或 `.failure(error)`） |
| `Disposable` | `AnyCancellable` | `AnyCancellable` 被釋放時會自動 `cancel()` |
| `DisposeBag` | `Set<AnyCancellable>` 加 `store(in:)` | 擁有者釋放時一起取消，行為相同 |
| `disposeBag = DisposeBag()`（重設） | `cancellables.removeAll()` | |

```swift
final class TickerViewModel {
    @Published private(set) var lastPrice = 0
    // 對應 RxSwift 的 let disposeBag = DisposeBag()
    private var cancellables = Set<AnyCancellable>()

    init(prices: AnyPublisher<Int, Never>) {
        prices
            .sink { [weak self] price in
                self?.lastPrice = price
            }
            // 對應 .disposed(by: disposeBag)
            .store(in: &cancellables)
    }
}
```

**生命週期的方向是最常踩的坑。** RxSwift 的 `subscribe` 回傳 `Disposable`，你不 dispose，它就一直訂閱到串流結束為止，所以 Rx 的典型 bug 是洩漏。Combine 的 `AnyCancellable` 在 `deinit` 時會呼叫 `cancel()`：寫成 `_ = prices.sink { … }`，回傳值馬上被釋放，訂閱當場取消，之後的價格一筆都收不到（演練 1）。Xcode 會對沒用到的 `sink` 回傳值出警告（`sink` 沒有標 `@discardableResult`），寫 `_ =` 就是把警告蓋掉。

更麻煩的是「測試時看起來正常」：`Just(1)` 或 `CurrentValueSubject` 在訂閱的當下就同步送出值，所以 `_ = Just(1).sink { print($0) }` 會印出 1，換成真的非同步來源才發現收不到。存在區域變數也一樣，Release 建置會在最後一次使用後就釋放它。

**背壓（比較少考）。** Combine 的 `Subscriber` 會用 `Demand` 告訴上游「我還要幾筆」，這是 RxSwift 沒有的。但 `sink` 和 `assign` 一律要求 `.unlimited`，所以日常寫法和 Rx 沒有差別。

> [!TIP]
> 面試時可以這樣說：「Publisher 對應 Observable，AnyCancellable 對應 Disposable，Set<AnyCancellable> 對應 DisposeBag。要注意方向相反：Rx 忘了 dispose 會洩漏，Combine 忘了存 AnyCancellable 會立刻被取消，一筆都收不到。」

### 2. `Single`、`Completable`、`Observable.create` 在 Combine 怎麼寫？為什麼 `Future` 要包 `Deferred`？

**Combine 沒有 `Single`、`Completable`、`Maybe` 這種「保證幾筆」的型別，一律是 `AnyPublisher`；最接近 `Single.create` 的是 `Future`。但 `Future` 一建立就執行，而且會把結果存起來給之後的每個訂閱者；`Single` 要等有人訂閱才執行，每次訂閱都重新執行。要得到 `Single` 的行為，就寫 `Deferred { Future { … } }`。**

| RxSwift | Combine | 備註 |
|---|---|---|
| `Observable.just(x)` | `Just(x)` | `Just` 的 `Failure` 是 `Never` |
| `Observable.empty()` | `Empty()` | |
| `Observable.error(e)` | `Fail(error: e)` | |
| `Observable.deferred { … }` | `Deferred { … }` | 每次訂閱才呼叫 closure |
| `Single.create { … }` | `Deferred { Future { promise in … } }` | 只有 `Future` 的話，建立當下就執行 |
| `Completable` | `AnyPublisher<Never, Error>` 或 `Future<Void, Error>` | 沒有專用型別 |
| `Observable.create { … }`（多筆） | 沒有直接對應；用 `Deferred` 包 `PassthroughSubject` | 見下方範例 |

**`Future` 和 `Single` 的差別。** RxSwift 寫 `func fetchBalance() -> Single<Int>`，呼叫這個函式只是「描述要做什麼」，沒人訂閱就不會發請求，加上 `.retry(2)` 每次重試都會重新發請求。照字面翻成 Combine：

```swift
func fetchBalance() -> AnyPublisher<Int, Error> {
    // Future 的 closure 在 Future 建立時就執行：
    // 呼叫 fetchBalance() 的當下就發出請求
    Future { promise in
        api.requestBalance { result in
            promise(result)
        }
    }
    .eraseToAnyPublisher()
}
```

會有兩個問題（演練 2）。第一，ViewModel 在 `init` 裡先把 publisher 組好，使用者還沒打開資產畫面，請求就已經發出去了。第二，`.retry(2)` 沒有用：`Future` 只執行一次並記住結果，重試時重新訂閱的是同一個 `Future`，拿到的還是同一個失敗，伺服器只被呼叫一次。修正方式是外面包一層 `Deferred`：

```swift
func fetchBalance() -> AnyPublisher<Int, Error> {
    // 每次被訂閱才建立一個新的 Future：沒人訂閱不發請求，
    // retry 重新訂閱時會重新發請求
    Deferred {
        Future { promise in
            api.requestBalance { result in
                promise(result)
            }
        }
    }
    .eraseToAnyPublisher()
}
```

**`Observable.create`（會送很多筆、要清理資源）。** Combine 沒有一模一樣的建立方式，常見寫法是在 `Deferred` 裡建立 `PassthroughSubject`，用 `handleEvents(receiveCancel:)` 做 RxSwift 在 `Disposables.create { }` 裡做的清理：

```swift
func prices(
    _ socket: PriceSocket, topic: String
) -> AnyPublisher<Int, Never> {
    Deferred { () -> AnyPublisher<Int, Never> in
        let subject = PassthroughSubject<Int, Never>()
        let token = socket.subscribe(topic) { price in
            subject.send(price)
        }
        return subject
            // 對應 Disposables.create { socket.unsubscribe(token) }
            .handleEvents(receiveCancel: {
                socket.unsubscribe(token)
            })
            .eraseToAnyPublisher()
    }
    .eraseToAnyPublisher()
}
```

（這個寫法有一個限制：`socket.subscribe` 如果在 closure 裡同步送出值，那時下游還沒接上 subject，值會掉。WebSocket 的推送都是之後才非同步送來，所以沒有問題。）另外，一次性的請求現在很多團隊直接用 async/await，不再包成 `Future`。

> [!TIP]
> 面試時可以這樣說：「Combine 沒有 Single，最接近的是 Future，但 Future 一建立就執行、結果會被記住，retry 也不會重發。所以我會寫 Deferred 包 Future，才和 Single 一樣是訂閱時才執行。」

### 3. `PublishRelay`、`BehaviorRelay` 對應什麼？為什麼 `bind(to:)` 不能直接換成 `subscribe(_:)`？

**`PublishSubject`／`PublishRelay` 對應 `PassthroughSubject`，`BehaviorSubject`／`BehaviorRelay` 對應 `CurrentValueSubject`（在 `ObservableObject` 裡常用 `@Published`），`ReplaySubject` 沒有內建對應。Combine 沒有 Relay：`Failure == Never` 只保證不會送錯誤，subject 還是可以被結束，結束後再 `send` 會被默默忽略。**

| RxSwift | Combine | 晚到的訂閱者收到什麼 |
|---|---|---|
| `PublishSubject`／`PublishRelay` | `PassthroughSubject<Output, Failure>` | 只收到之後的值 |
| `BehaviorSubject`／`BehaviorRelay` | `CurrentValueSubject<Output, Failure>`；`@Published` | 先收到目前的值，再收到之後的值 |
| `ReplaySubject` | 沒有內建 | 要自己實作或用社群套件 |
| `relay.accept(x)` | `subject.send(x)` | |
| `relay.value` | `subject.value`（`@Published` 直接讀屬性） | |

**選錯 subject，畫面打開時會是空白。** 登入後先用 REST 拿到資產餘額，送進共用的 store；使用者幾秒後才打開資產畫面。store 用 `PassthroughSubject` 的話，畫面訂閱時那一筆已經送完了，畫面顯示「—」，要等下一次 WebSocket 推送才有數字（演練 3）。這和 Rx 裡選 `PublishRelay` 還是 `BehaviorRelay` 完全一樣：代表「狀態」的用 `CurrentValueSubject`，代表「事件」（按鈕點擊、Toast）的用 `PassthroughSubject`。

```swift
final class BalanceStore {
    // nil 代表還沒載入；對應 BehaviorRelay<Int?>(value: nil)
    private let subject = CurrentValueSubject<Int?, Never>(nil)

    // 晚到的訂閱者會先收到目前的餘額
    var balance: AnyPublisher<Int, Never> {
        subject.compactMap { $0 }.eraseToAnyPublisher()
    }

    func update(_ value: Int) {
        subject.send(value)
    }
}
```

**`subscribe(_:)` 會把結束事件一起轉送。** RxSwift 裡把 REST 快照接到 relay，你會寫 `api.snapshot().asObservable().bind(to: priceRelay)`。Relay 不能被結束，所以快照送完、`Single` 結束時，relay 不受影響。Combine 對應的寫法看起來是 `api.snapshot().subscribe(priceSubject)`，但它會把上游的 `.finished` 也轉送給 subject：快照送出一筆就結束，subject 跟著結束，之後 WebSocket 推來的價格 `send` 進去全部被忽略，畫面停在快照的價格，也沒有任何錯誤訊息（演練 4）。

```swift
// 錯誤：Just 送完一筆就 finished，priceSubject 也跟著結束
api.snapshot()
    .subscribe(priceSubject)
    .store(in: &cancellables)

// 正確：只轉送值，不轉送結束事件（對應 bind(to: relay)）
api.snapshot()
    .sink { [priceSubject] price in
        priceSubject.send(price)
    }
    .store(in: &cancellables)
```

另外，`@Published` 是在值「即將」改變時發送（willSet），`$price.sink` 裡回頭讀 `self.price` 拿到的是舊值，這和 `BehaviorRelay` 不一樣（Swift06 演練 8）。

> [!TIP]
> 面試時可以這樣說：「PublishRelay 對應 PassthroughSubject，BehaviorRelay 對應 CurrentValueSubject 或 @Published。Combine 沒有 Relay，Never 只代表不會出錯，subject 還是會被結束，所以我不用 subscribe(_:) 把有限的 publisher 接到共用的 subject，而是用 sink 只轉送值。」

### 4. 常用運算子怎麼對應？沒有 `withLatestFrom` 怎麼辦？

**大部分運算子同名或只差一點名字；要特別記的是 `flatMapLatest` 要寫成 `map` 加 `switchToLatest`，而 Combine 沒有內建 `withLatestFrom`。直接用 `combineLatest` 代替會出事：按鈕按過一次之後，另一邊每變一次就再送出一次。**

| RxSwift | Combine | 備註 |
|---|---|---|
| `map`、`filter`、`scan`、`merge`、`zip`、`combineLatest` | 同名 | |
| `compactMap` | `compactMap` | |
| `flatMap` | `flatMap` | 所有內層同時存活 |
| `flatMapLatest` | `map { … }.switchToLatest()` | 新的來就取消舊的（Swift06 第 4 題） |
| `concatMap` | `flatMap(maxPublishers: .max(1))` | 一次一個，排隊執行（大致對應） |
| `distinctUntilChanged` | `removeDuplicates` | |
| `startWith` | `prepend` | |
| `skip(n)`、`take(n)` | `dropFirst(n)`、`prefix(n)` | |
| `take(until:)` | `prefix(untilOutputFrom:)` | |
| `do(onNext:)` | `handleEvents(receiveOutput:)` | |
| `debug()` | `print()` | |
| `toArray()` | `collect()` | |
| `share(replay: 1)` | 沒有直接對應；`share()` 不重播 | 要重播用 `CurrentValueSubject` |
| `withLatestFrom` | 沒有內建 | 見下方 |

**`withLatestFrom` 為什麼不能用 `combineLatest` 代替？** 下單頁的 Rx 寫法：`buyTap.withLatestFrom(price)`，意思是「按下買進時，取當下的價格送出一次」，只有 `buyTap` 會觸發輸出。`combineLatest` 是「任何一邊有新值就輸出最新的組合」：使用者按一次買進，送出一筆訂單；之後價格每跳一次，`combineLatest` 又用「上一次的按鈕事件＋新價格」輸出一次，每一次都是一筆新訂單（演練 5）。

```swift
// 錯誤：按一次之後，價格每變一次就多送一筆訂單
let orders = buyTap
    .combineLatest(price)
    .map { $0.1 }

// 正確：只有按鈕會觸發；按下時讀 CurrentValueSubject 的目前值
let orders = buyTap
    .map { _ in price.value }
```

價格本來就存在 `CurrentValueSubject` 或 `@Published` 的話，按下時直接讀目前值就好，這是最常見的替代寫法。價格只是一般的 publisher 時，可以先把它存進一個 `CurrentValueSubject`，或引入社群套件（例如 CombineExt）提供的 `withLatestFrom`。

> [!TIP]
> 面試時可以這樣說：「flatMapLatest 在 Combine 是 map 加 switchToLatest。Combine 沒有 withLatestFrom，不能用 combineLatest 代替，否則按過一次之後價格每跳一次就多送一單；我會在按下時讀 CurrentValueSubject 的目前值。」

### 5. 錯誤處理：`Failure` 型別是什麼？`catch` 要放在哪裡？

**Combine 的 publisher 有兩個泛型：`Output` 和 `Failure`，錯誤型別在編譯時就確定，`Never` 代表不會出錯（對應 RxSwift 6 的 `Infallible`）。錯誤會讓整條串流結束，這點和 RxSwift 一樣；所以 `catch`、`replaceError` 要放在 `flatMap` 裡面的內層請求上，放在外層的話，第一次失敗就讓整條「按鈕觸發請求」的串流結束。**

RxSwift 的 `Observable<Element>` 錯誤一律是 `Error`，型別上看不出會不會出錯。Combine 要求運算子兩邊的 `Failure` 對得上，所以多了幾個只為了轉換型別的運算子：

| 要做的事 | Combine | RxSwift 大致對應 |
|---|---|---|
| `Never` 轉成可以出錯的型別 | `setFailureType(to: Error.self)` | 不需要 |
| 換錯誤型別 | `mapError { … }` | 不需要 |
| 可能丟錯的 `map` | `tryMap { … }`（`Failure` 變成 `Error`） | 在 `map` 裡 `throw` |
| 出錯時換成固定值 | `replaceError(with:)` | `catchAndReturn` |
| 出錯時換成另一個 publisher | `catch { _ in … }` | `catch` |
| 重試 | `retry(n)` | `retry(n)` |

**型別系統會把你推向錯的位置。** `assign(to: &$orders)` 和 SwiftUI 綁定都要求 `Failure == Never`，所以最直覺的寫法是在整條串流最後補一個 `.replaceError(with: [])`，型別就對了：

```swift
// 錯誤：第一次刷新失敗，replaceError 送出 [] 然後整條串流結束；
// 之後再按刷新都沒有反應，委託列表還被清空
refreshTap
    .setFailureType(to: Error.self)
    .flatMap { _ in api.fetchOrders() }
    .replaceError(with: [])
    .assign(to: &$orders)

// 正確：錯誤在內層處理，只結束這一次請求，外層的按鈕串流繼續活著
refreshTap
    .flatMap { _ in
        api.fetchOrders()
            .catch { _ in Empty<[Order], Never>() }
    }
    .assign(to: &$orders)
```

外層的 `replaceError` 收到錯誤時，會送出 `[]` 然後送出 `.finished`，整條串流就結束了，之後的 `refreshTap` 沒人接（演練 6）。內層的 `catch` 回傳 `Empty`，只是讓「這一次請求」安靜地結束，`flatMap` 外面的串流不受影響，畫面也保留上一次成功的資料。實務上會在 `catch` 裡另外記下錯誤，顯示「更新失敗」的提示。

> [!TIP]
> 面試時可以這樣說：「Combine 把錯誤型別寫在泛型裡，Never 等於 Rx 的 Infallible。錯誤一樣會結束串流，所以 catch 要放在 flatMap 裡的內層請求，不能為了湊 Never 在最外層補 replaceError，不然第一次失敗之後按鈕就再也沒反應。」

### 6. `observe(on:)`、`Driver`、`bind(to:)` 在 Combine 怎麼寫？

**`observe(on:)` 對應 `receive(on:)`，`subscribe(on:)` 同名。Combine 沒有 `Driver`，要自己組出 `Driver` 保證的三件事：在主執行緒送出（`receive(on: DispatchQueue.main)`）、不會出錯（`Failure == Never`）、晚到的訂閱者拿得到目前值（用 `@Published` 或 `CurrentValueSubject`，`share()` 不重播）。`bind(to:)`／`drive` 對應 `assign(to: &$property)`（iOS 14 起）或 `sink`。**

RxSwift 的 `Driver` 把「畫面綁定要注意的事」做進型別：`asDriver(onErrorJustReturn:)` 之後一定在主執行緒、一定不會出錯、會 `share(replay: 1)`。Combine 沒有這個型別，忘了任何一項，編譯器都不會提醒。最常漏的是執行緒：`URLSession` 的 `dataTaskPublisher`、在背景 queue 解碼的 WebSocket 訊息，都在背景執行緒送出；沒有 `receive(on:)` 就 `assign` 給 `@Published`，畫面就在背景執行緒被更新（演練 7）。SwiftUI 會出現紫色的執行期警告「Publishing changes from background threads is not allowed」，UIKit 可能畫面不更新或直接 crash。

```swift
final class TickerViewModel: ObservableObject {
    @Published private(set) var priceText = "—"

    init(api: TickerAPI) {
        api.ticker()
            .map { "價格：\($0)" }
            // 對應 Driver 的「一定在主執行緒」
            .receive(on: DispatchQueue.main)
            // 不會抓住 self（iOS 14 起）；
            // assign(to: \.priceText, on: self) 會抓住 self，造成 retain cycle
            .assign(to: &$priceText)
    }
}
```

| RxSwift | Combine |
|---|---|
| `observe(on: MainScheduler.instance)` | `receive(on: DispatchQueue.main)`（或 `RunLoop.main`） |
| `subscribe(on:)` | `subscribe(on:)` |
| `asDriver(onErrorJustReturn: x)` | `replaceError(with: x)` 加 `receive(on: DispatchQueue.main)`；要重播就存進 `@Published` |
| `drive(label.rx.text)`／`bind(to:)` | `assign(to: &$text)`；UIKit 用 `sink { [weak self] … }` |
| `Signal`（不重播的 Driver） | `PassthroughSubject` 加 `receive(on:)` |

還有一個時機上的差別：RxSwift 的 `MainScheduler.instance` 在已經位於主執行緒、而且沒有排隊中的工作時會直接執行（要一律排隊用 `MainScheduler.asyncInstance`），Combine 的 `receive(on: DispatchQueue.main)` 一律排到下一輪 run loop（主執行緒的事件迴圈）才送出。從 Rx 搬過來的同步單元測試，可能因此失敗（Swift06 演練 9）。

> [!TIP]
> 面試時可以這樣說：「Combine 沒有 Driver，我會自己補上 receive(on: main)、把錯誤處理成 Never，狀態存在 @Published。綁定用 assign(to: &$property)，不用會抓住 self 的 assign(to:on:)。」

### 7. 專案裡 RxSwift 和 Combine 怎麼共存、怎麼遷移？（比較少考，被問到能答就好）

**不要在同一條資料流裡來回轉換；在模組邊界（例如 Repository 對外的介面）選定一種，橋接點寫成一個明確的轉換函式。新功能用 Combine 或 async/await，舊的 Rx 程式碼在改到時才換。**

Combine 轉 RxSwift 可以自己寫，重點是把 `AnyCancellable` 的取消接到 `Disposable` 上：

```swift
import Combine
import RxSwift

extension Publisher {
    func asObservable() -> Observable<Output> {
        Observable.create { observer in
            let cancellable = self.sink(
                receiveCompletion: { completion in
                    switch completion {
                    case .finished:
                        observer.onCompleted()
                    case .failure(let error):
                        observer.onError(error)
                    }
                },
                receiveValue: { value in
                    observer.onNext(value)
                }
            )
            // Rx 這邊 dispose 時，取消 Combine 的訂閱
            return Disposables.create {
                cancellable.cancel()
            }
        }
    }
}
```

反方向（RxSwift 轉 Combine）要處理「Rx 在訂閱當下就同步送出的值」，自己寫容易漏，通常用社群套件（例如 RxCombine）。轉成 async/await 時，Combine 有 `values` 屬性（iOS 15 起）可以直接 `for await`。

遷移時要注意的三件事：一、Combine 最低支援 iOS 13，`assign(to: &$x)` 要 iOS 14；二、RxSwift 的運算子比較完整，`withLatestFrom`、`ReplaySubject`、`share(replay:)` 這些要先想好替代寫法；三、測試的 scheduler 不同，RxTest 的 `TestScheduler` 在 Combine 沒有內建對應，通常改成注入 scheduler，或用 `XCTestExpectation` 等待。

> [!TIP]
> 面試時可以這樣說：「共存時我會在模組邊界選定一種，橋接寫成一個函式，不在同一條資料流裡來回轉。新功能用 Combine 或 async/await，舊的 Rx 改到才換，先想好 withLatestFrom、ReplaySubject 這些沒有內建的替代寫法。」

## 實際演練

> [!LAB]
> **打開**：Xcode 打開 `labs/ios/MobileLabs.xcodeproj`，選模擬器按 ⌘R，App 首頁進入 **Swift12Combine**。
> **檔案**：你寫在 `labs/ios/Practice/Swift/Swift12-Combine/`，解答在 `labs/ios/Solution/Swift/Swift12-Combine/`（檔名一樣，可以左右對照）。
> **練法**：照檔案裡的 TODO 寫 Bug 版 → App 選「練習」「Bug 版」執行，出現 ✓ 代表 bug 真的重現了 → 寫修正版 → 選「修正版」執行 → 打開下面的解答對照。卡住時，App 切到「解答」可以直接跑解答。
> **關於時間**：多數演練是同步送值，執行完馬上有結果；演練 2、7 會等假的 API 回應，大約 0.1 到 0.5 秒。

### 演練 1：訂閱了行情，卻一筆都沒收到（第 1 題）

**檔案**：`L01_CancellableDropped.swift`

**情境**：行情畫面打開時訂閱價格推送（`feed.ticks`，演練會記下被訂閱幾次、被取消幾次），之後推送 5 筆價格；接著關閉畫面，再推 3 筆。

**怎麼寫**

1. **Bug 版**：`onAppear(feed:)` 裡對 `feed.ticks` 做 `sink`（收到就 `received.hit()`），回傳值用 `_ =` 丟掉，就像 RxSwift 裡沒寫 `disposed(by:)`。
2. **修正版**：同樣的 `sink`，接 `.store(in: &cancellables)`，存在畫面物件上。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面開著時：訂閱 1 次、已取消 1 次、收到 0 筆 |
| 修正版 | 畫面開著時：訂閱 1 次、已取消 0 次、收到 5 筆；關閉畫面後：已取消 1 次，又推 3 筆，總共還是收到 5 筆 |

**為什麼**：`AnyCancellable` 被釋放時會自動 `cancel()`。`_ =` 讓它在 `sink` 回傳的下一刻就被釋放，訂閱當場取消。RxSwift 方向相反：沒有 dispose 的訂閱會一直活著。修正版的訂閱跟著畫面物件的 `cancellables` 走，畫面關閉時一起取消，這就是 `DisposeBag` 的行為。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L01_CancellableDropped.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L01_CancellableDropped.swift#fixed
```

### 演練 2：還沒打開畫面就發了請求，失敗後重試也沒用（第 2 題）

**檔案**：`L02_FutureEager.swift`

**情境**：資產畫面的 ViewModel 先把「取得餘額」的 publisher 組好，使用者 0.1 秒後才打開畫面、開始訂閱，失敗時 `retry(2)`。假的 API 第 1 次請求會逾時失敗，第 2 次之後成功（餘額 1200000）。

**怎麼寫**

1. **Bug 版**：`fetchBalance` 回傳 `Future { promise in api.request(promise) }`，照 `Single.create` 的樣子直接翻。
2. **修正版**：外面包一層 `Deferred { … }`，每次被訂閱才建立新的 `Future`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 還沒訂閱：API 被呼叫 1 次；畫面顯示：失敗；API 一共被呼叫 1 次 |
| 修正版 | 還沒訂閱：API 被呼叫 0 次；畫面顯示：餘額 1200000；API 一共被呼叫 2 次 |

**為什麼**：`Future` 的 closure 在建立時就執行，結果會被記住、給之後的每個訂閱者。所以還沒人訂閱就發了請求，`retry` 重新訂閱的也是同一個已經失敗的 `Future`。`Deferred` 讓每次訂閱都重新建立 `Future`，行為才和 RxSwift 的 `Single` 一樣。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L02_FutureEager.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L02_FutureEager.swift#fixed
```

### 演練 3：打開資產畫面，餘額是空白的（第 3 題）

**檔案**：`L03_LateSubscriber.swift`

**情境**：登入後先用 REST 拿到餘額 1200000，送進共用的 `BalanceStore`；之後使用者才打開資產畫面、訂閱餘額；接著 WebSocket 推來新的餘額 1500000。

**怎麼寫**

1. **Bug 版**：`BalanceStore` 用 `PassthroughSubject<Int, Never>`（對應 `PublishRelay`）：`update` 時 `send`，`balance` 回傳 `subject.eraseToAnyPublisher()`。
2. **修正版**：改用 `CurrentValueSubject<Int?, Never>(nil)`（對應 `BehaviorRelay`），`balance` 用 `compactMap { $0 }` 濾掉還沒載入的 `nil`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 打開資產畫面時顯示：—；下一次推送後顯示：1500000 |
| 修正版 | 打開資產畫面時顯示：1200000；下一次推送後顯示：1500000 |

**為什麼**：`PassthroughSubject` 不保留目前的值，晚到的訂閱者只收到之後的值。「餘額」是狀態，要用會保留目前值的 `CurrentValueSubject`（或 `@Published`），和 Rx 裡選 `BehaviorRelay` 的理由一樣。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L03_LateSubscriber.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L03_LateSubscriber.swift#fixed
```

### 演練 4：價格停在快照，之後的推送都不見了（第 3 題）

**檔案**：`L04_SubscribeSubject.swift`

**情境**：行情畫面訂閱共用的 `store.price`（`PassthroughSubject`）。先用 REST 快照（`Just`，送一筆就結束）填入第一筆價格 15000000，之後 WebSocket 推來 15010000、15020000。

**怎麼寫**

1. **Bug 版**：照 RxSwift 的 `bind(to: relay)`，寫 `api.snapshot().subscribe(store.price)`，存進 `cancellables`。
2. **修正版**：改用 `sink`，收到快照就 `store.price.send(…)`，只轉送值。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面收到的價格：[15000000]；price 已經結束：是 |
| 修正版 | 畫面收到的價格：[15000000, 15010000, 15020000]；price 已經結束：否 |

**為什麼**：`subscribe(_:)` 會把上游的值和結束事件都轉送給 subject。`Just` 送完一筆就 `.finished`，`store.price` 跟著結束，之後的 `send` 全部被忽略，而且沒有任何錯誤訊息。RxSwift 的 Relay 不能被結束，`bind(to: relay)` 不會有這個問題；Combine 沒有 Relay，`Never` 只保證不會送錯誤。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L04_SubscribeSubject.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L04_SubscribeSubject.swift#fixed
```

### 演練 5：按一次買進，送出了四筆訂單（第 4 題）

**檔案**：`L05_WithLatestFrom.swift`

**情境**：下單頁有買進按鈕（`buyTap`）和即時價格（`price`，`CurrentValueSubject`，目前 15000000）。使用者按一次買進，之後價格又跳了 3 次。

**怎麼寫**

1. **Bug 版**：想寫 RxSwift 的 `buyTap.withLatestFrom(price)`，Combine 沒有，改用 `buyTap.combineLatest(price)`，再 `map` 取出價格。
2. **修正版**：`buyTap.map { _ in price.value }`，按下時讀價格的目前值。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 送出的訂單：4 筆，價格 [15000000, 15010000, 15020000, 15030000] |
| 修正版 | 送出的訂單：1 筆，價格 [15000000] |

**為什麼**：`combineLatest` 是「任何一邊有新值就輸出最新的組合」，按鈕按過一次之後，價格每變一次都會再輸出一次。`withLatestFrom` 只有主動的那一邊（按鈕）會觸發。價格存在 `CurrentValueSubject` 時，按下時讀 `.value` 就是 `withLatestFrom` 的效果。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L05_WithLatestFrom.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L05_WithLatestFrom.swift#fixed
```

### 演練 6：刷新失敗一次，之後再按都沒反應（第 5 題）

**檔案**：`L06_ErrorEndsStream.swift`

**情境**：委託列表每按一次刷新（`refreshTap`）就呼叫 `api.fetchOrders()`。假的 API 第 2 次呼叫會逾時失敗，其他都成功（回傳 2 筆委託）。使用者連按 3 次刷新。

**怎麼寫**

1. **Bug 版**：`refreshTap` 接 `setFailureType(to: Error.self)`、`flatMap` 呼叫 `api.fetchOrders()`，為了讓 `Failure` 變成 `Never`，在最外層接 `replaceError(with: [])`。
2. **修正版**：錯誤在 `flatMap` 裡面處理：`api.fetchOrders().catch { _ in Empty() }`，外層不用 `setFailureType`，也不用 `replaceError`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 按了 3 次刷新：API 被呼叫 2 次、畫面更新 2 次；最後畫面上的委託：0 筆；刷新的串流已經結束：是 |
| 修正版 | 按了 3 次刷新：API 被呼叫 3 次、畫面更新 2 次；最後畫面上的委託：2 筆；刷新的串流已經結束：否 |

**為什麼**：錯誤會讓串流結束。外層的 `replaceError` 收到錯誤時送出 `[]`（把列表清空）再送出 `.finished`，整條「按鈕觸發請求」的串流就結束了，第 3 次刷新沒人接。放在內層的 `catch` 只結束那一次請求，外層繼續活著，畫面保留上一次成功的資料。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L06_ErrorEndsStream.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L06_ErrorEndsStream.swift#fixed
```

### 演練 7：價格在背景執行緒更新到畫面上（第 6 題）

**檔案**：`L07_ReceiveOnMain.swift`

**情境**：`TickerViewModel` 的 `priceText` 是 `@Published`，資料來自假的 API，回應像 `URLSession` 的 `dataTaskPublisher` 一樣在背景執行緒送出。演練記下 `priceText` 被更新時在哪條執行緒。

**怎麼寫**

1. **Bug 版**：`init` 裡 `api.ticker()` 接 `map { "價格：\($0)" }`，直接 `assign(to: &$priceText)`。
2. **修正版**：在 `assign` 之前加 `receive(on: DispatchQueue.main)`。

| 版本 | 寫對了會看到什麼 |
|---|---|
| Bug 版 | 畫面顯示：價格：15000000；更新畫面的執行緒：背景執行緒 |
| 修正版 | 畫面顯示：價格：15000000；更新畫面的執行緒：主執行緒 |

**為什麼**：Combine 沒有 `Driver`，不會自動切到主執行緒；值在哪條執行緒送出，`assign` 就在哪條執行緒寫入 `@Published`。在 SwiftUI 畫面上會出現紫色的執行期警告。`receive(on: DispatchQueue.main)` 補上 `Driver` 保證的「一定在主執行緒」。

```lab-solution
ios/Solution/Swift/Swift12-Combine/L07_ReceiveOnMain.swift#bug
```

```lab-solution
ios/Solution/Swift/Swift12-Combine/L07_ReceiveOnMain.swift#fixed
```

第 7 題是 RxSwift 與 Combine 共存、遷移的流程題，沒有演練。

## 常見陷阱

- **把 `sink` 的回傳值用 `_ =` 丟掉。** `AnyCancellable` 被釋放時會自動取消，訂閱當場結束。要 `store(in: &cancellables)`，存在擁有者身上。
- **用 `Just` 或 `CurrentValueSubject` 測試，以為訂閱沒問題。** 它們在訂閱當下就同步送值，就算訂閱馬上被取消也收得到第一筆；換成非同步來源才會發現。
- **把 `Single.create` 直接翻成 `Future`。** `Future` 一建立就執行，結果會被記住，`retry` 不會重發請求。要寫 `Deferred { Future { … } }`。
- **以為 `PassthroughSubject<_, Never>` 就是 Relay。** `Never` 只代表不會送錯誤，subject 還是可以被結束；`subscribe(_:)` 會把上游的結束轉送過去，之後的 `send` 全部被忽略。
- **代表狀態的資料用 `PassthroughSubject`。** 晚到的訂閱者收不到目前值，畫面打開時是空白。狀態用 `CurrentValueSubject` 或 `@Published`。
- **用 `combineLatest` 代替 `withLatestFrom`。** 另一邊每變一次就再輸出一次，下單按鈕會重複送單。按下時讀 `CurrentValueSubject` 的 `.value`。
- **為了湊 `Never`，在最外層補 `replaceError`。** 第一次錯誤就讓整條串流結束。錯誤要在 `flatMap` 裡的內層處理。
- **以為 Combine 會像 `Driver` 一樣自動切回主執行緒。** 要自己加 `receive(on: DispatchQueue.main)`。

## 面試官想聽到的

1. 能把 RxSwift 的型別一一對到 Combine（`Publisher`、`AnyCancellable`、`Set<AnyCancellable>`、`PassthroughSubject`、`CurrentValueSubject`），並且主動說出生命週期方向相反：Combine 忘了存 `AnyCancellable` 會立刻取消。
2. 知道 `Future` 是 eager（一建立就執行）而且會記住結果，要用 `Deferred` 包起來才和 `Single` 一樣。
3. 知道 Combine 沒有 Relay、沒有 `withLatestFrom`、沒有 `Driver`、`share()` 不重播，而且說得出各自的替代寫法。
4. 理解 `Failure` 型別與錯誤會結束串流，知道 `catch` 要放在內層請求上。
5. 對既有的 Rx 專案有務實的看法：在模組邊界選定一種、橋接點明確，不在同一條資料流裡混用。

> [!FIELD]
> 在一個同時有 RxSwift 和 Combine 的 App 裡，舊的資料流用 Rx 的 relay 串起來，新的 SwiftUI 畫面用 `@Published` 綁定。最常出問題的是從 Rx 照字面翻過來的程式：`subscribe(onNext:)` 翻成 `sink` 卻沒有 `store(in:)`，在模擬器上用假資料（`Just`）測起來正常，接上真的 API 才發現收不到；`bind(to: relay)` 翻成 `subscribe(subject)`，把共用的 subject 結束掉。後來的做法是：Repository 對外只提供 Combine 的 `AnyPublisher` 或 async 函式，Rx 只留在還沒改的舊畫面，兩者之間只透過一個轉換函式橋接。
> 面試時可以這樣說：「我實際維護過 Rx 和 Combine 共存的專案。照字面翻譯最容易出事的是訂閱沒存起來、Future 一建立就執行、subscribe 把 subject 結束掉這三個，所以我們把橋接集中在 Repository 的邊界。」

## iOS / Android 對照

Android 這邊的 RxJava 和 RxSwift 是同一套概念；現在的 Android 專案多半改用 Kotlin 的 Flow，所以這張表列出三邊的對應，方便和 Android 同事溝通。

| 主題 | RxSwift | Combine | Kotlin Flow |
|---|---|---|---|
| 資料流型別 | `Observable<T>` | `AnyPublisher<Output, Failure>` | `Flow<T>` |
| 只有一個結果 | `Single<T>` | `Deferred { Future { … } }` | `suspend fun` |
| 取消訂閱 | `Disposable`、`DisposeBag` | `AnyCancellable`、`Set<AnyCancellable>` | `Job`、`CoroutineScope` |
| 沒有目前值的熱串流 | `PublishRelay` | `PassthroughSubject` | `MutableSharedFlow` |
| 有目前值的熱串流 | `BehaviorRelay` | `CurrentValueSubject`、`@Published` | `MutableStateFlow` |
| 新的來就取消舊的 | `flatMapLatest` | `map` 加 `switchToLatest` | `flatMapLatest` |
| 切執行緒 | `observe(on:)` | `receive(on:)` | `flowOn`（改的是上游）、在 `Dispatchers.Main` 收集 |
| 錯誤處理 | `catch`、`catchAndReturn` | `catch`、`replaceError` | `catch { }` |

## 名詞對照

| 中文 | 英文 | 日文 |
|---|---|---|
| 發布者（送出資料的一方） | Publisher | パブリッシャー |
| 訂閱者 | Subscriber | サブスクライバー |
| 取消訂閱 | cancel／dispose | 購読解除（キャンセル） |
| 一建立就執行 | eager | 即時実行（イーガー） |
| 訂閱時才執行 | lazy（deferred） | 遅延実行 |
| 錯誤型別 | Failure type | エラー型 |
| 響應式程式設計 | reactive programming | リアクティブプログラミング |
