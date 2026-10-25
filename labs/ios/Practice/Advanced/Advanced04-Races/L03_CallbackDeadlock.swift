// L03：行情推進來的同時下單，兩邊都卡住不動（持鎖時呼叫 callback，兩把鎖的順序相反造成 deadlock）
// 對應複習站：Advanced04Races 第 4 題「deadlock 是什麼？怎麼避免、怎麼診斷？」
//
// 情境：價格表 PriceStore 有自己的鎖（價格鎖），委託管理 OrderManager 有自己的鎖（委託鎖）。
//        1. WebSocket 執行緒：PriceStore.update 寫入新價格，接著通知 listener（OrderManager.onPrice）去更新持倉的估值，onPrice 需要委託鎖。
//        2. 畫面執行緒：OrderManager.placeOrder 下單，先拿委託鎖，再向 PriceStore 查最新價（latest），latest 需要價格鎖。
//        兩條執行緒同時進來：A 拿著價格鎖等委託鎖，B 拿著委託鎖等價格鎖，互相等對方放開，誰也動不了。
//        （演練用 NSLock 的 lock(before:) 設 300 毫秒逾時當保險，逾時就放棄並記錄，不會真的把 App 卡死。真的 App 沒有這個保險，就是整個凍住。）
//
// 你要寫：
//   ① Bug 版 PriceStore.update：持有價格鎖的時候（defer 才會 unlock），直接呼叫 listener?(symbol, price)。
//   ② 修正版 PriceStore.update：不要用 defer。價格寫進字典、work() 之後，先把 listener 複製出來（let callback = listener），
//      lock.unlock() 放掉鎖，再呼叫 callback?(symbol, price)。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced04-Races/L03_CallbackDeadlock.swift
import Foundation
import LabKit

/// 拿鎖，最多等 300 毫秒；拿不到就記錄「疑似 deadlock」並回傳 false（兩個版本共用）
private func timedLock(_ lock: NSLock, name: String, who: String, log: LabLog) -> Bool {
    if lock.lock(before: Date(timeIntervalSinceNow: 0.3)) { return true }
    log("\(who)：等\(name)等了 300 ms 還拿不到，疑似 deadlock，放棄")
    return false
}

/// 持有鎖的期間做一點事（讓兩條執行緒一定會互相卡到）
private func work() { Thread.sleep(forTimeInterval: 0.03) }

/// 演練流程（兩個版本共用）：兩條執行緒同時進來，等兩邊都結束
private func scenario(_ log: LabLog, update: @escaping () -> Void, placeOrder: @escaping () -> Void) {
    let group = DispatchGroup()
    let start = DispatchSemaphore(value: 0)
    for body in [update, placeOrder] {
        group.enter()
        Thread {
            start.wait()                                        // 兩條執行緒同時開始
            body()
            group.leave()
        }.start()
    }
    start.signal(); start.signal()
    group.wait()
}

// MARK: - Bug 版
private enum L03Bug {
    final class PriceStore {
        let lock = NSLock()
        private var prices: [String: Int] = ["BTC/JPY": 15_000_000]
        var listener: ((String, Int) -> Void)?
        let log: LabLog
        init(log: LabLog) { self.log = log }

        func update(_ symbol: String, _ price: Int) {
            guard timedLock(lock, name: "價格鎖", who: "WebSocket 執行緒", log: log) else { return }
            defer { lock.unlock() }
            prices[symbol] = price
            work()
            // TODO ①：listener?(symbol, price)：還拿著價格鎖，就呼叫 callback，callback 裡面會去拿委託鎖
        }

        func latest(_ symbol: String, who: String) -> Int? {
            guard timedLock(lock, name: "價格鎖", who: who, log: log) else { return nil }
            defer { lock.unlock() }
            return prices[symbol]
        }
    }

    final class OrderManager {
        private let lock = NSLock()
        let store: PriceStore
        let log: LabLog
        init(store: PriceStore, log: LabLog) {
            self.store = store
            self.log = log
        }

        func onPrice(_ symbol: String, _ price: Int) {
            guard timedLock(lock, name: "委託鎖", who: "WebSocket 執行緒", log: log) else { return }
            defer { lock.unlock() }
            log("WebSocket 執行緒：更新持倉估值 \(symbol) \(price)")
        }

        func placeOrder(_ symbol: String) {
            guard timedLock(lock, name: "委託鎖", who: "畫面執行緒", log: log) else { return }
            defer { lock.unlock() }
            work()
            if let price = store.latest(symbol, who: "畫面執行緒") {      // 拿著委託鎖，去拿價格鎖
                log("畫面執行緒：下單成功，用價格 \(price)")
            }
        }
    }

    static func run(_ log: LabLog) async {
        await Task.detached {
            let store = PriceStore(log: log)
            let orders = OrderManager(store: store, log: log)
            store.listener = { orders.onPrice($0, $1) }
            scenario(log, update: { store.update("BTC/JPY", 15_100_000) }, placeOrder: { orders.placeOrder("BTC/JPY") })
        }.value
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class PriceStore {
        let lock = NSLock()
        private var prices: [String: Int] = ["BTC/JPY": 15_000_000]
        var listener: ((String, Int) -> Void)?
        let log: LabLog
        init(log: LabLog) { self.log = log }

        func update(_ symbol: String, _ price: Int) {
            guard timedLock(lock, name: "價格鎖", who: "WebSocket 執行緒", log: log) else { return }
            defer { lock.unlock() }                             // TODO ②：這一行要刪掉，改成下面手動 unlock
            prices[symbol] = price
            work()
            // TODO ②：let callback = listener（先複製出來）；lock.unlock()（先放掉鎖）；再呼叫 callback?(symbol, price)
        }

        func latest(_ symbol: String, who: String) -> Int? {
            guard timedLock(lock, name: "價格鎖", who: who, log: log) else { return nil }
            defer { lock.unlock() }
            return prices[symbol]
        }
    }

    final class OrderManager {
        private let lock = NSLock()
        let store: PriceStore
        let log: LabLog
        init(store: PriceStore, log: LabLog) {
            self.store = store
            self.log = log
        }

        func onPrice(_ symbol: String, _ price: Int) {
            guard timedLock(lock, name: "委託鎖", who: "WebSocket 執行緒", log: log) else { return }
            defer { lock.unlock() }
            log("WebSocket 執行緒：更新持倉估值 \(symbol) \(price)")
        }

        func placeOrder(_ symbol: String) {
            guard timedLock(lock, name: "委託鎖", who: "畫面執行緒", log: log) else { return }
            defer { lock.unlock() }
            work()
            if let price = store.latest(symbol, who: "畫面執行緒") {
                log("畫面執行緒：下單成功，用價格 \(price)")
            }
        }
    }

    static func run(_ log: LabLog) async {
        await Task.detached {
            let store = PriceStore(log: log)
            let orders = OrderManager(store: store, log: log)
            store.listener = { orders.onPrice($0, $1) }
            scenario(log, update: { store.update("BTC/JPY", 15_100_000) }, placeOrder: { orders.placeOrder("BTC/JPY") })
        }.value
    }
}

// MARK: - 演練設定
extension Advanced04Races {
    static let l03 = Lab(
        id: "L03",
        title: "行情推進來的同時下單，兩邊都卡住不動",
        question: "第 4 題：deadlock 是什麼？怎麼避免、怎麼診斷？",
        goal: "持有鎖的時候不要呼叫 callback：先把需要的資料複製出來、放掉鎖，再呼叫。這樣不會同時拿著兩把鎖，也就不會有循環等待。先寫出會 deadlock 的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced04-Races/L03_CallbackDeadlock.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看 log：「等…等了 300 ms 還拿不到，疑似 deadlock」。WebSocket 執行緒拿著價格鎖在等委託鎖，畫面執行緒拿著委託鎖在等價格鎖。",
            "寫 TODO ②，選「修正版」執行：兩條執行緒都完成，沒有逾時，log 裡有「更新持倉估值」與「下單成功」。",
            "診斷練習：在真的 App 卡住時，Xcode 按暫停，左邊每一條執行緒的呼叫堆疊裡，一條停在 NSLock.lock 的是 listener 那一層，另一條停在 PriceStore.latest 那一層，追出「誰在等誰」就是一個循環。",
            "想一想：另一個解法是兩邊都固定「先拿價格鎖、再拿委託鎖」的順序。為什麼「持鎖時不呼叫外部程式碼」通常比固定順序更好維護？（見這個單元的第 4 題）",
        ],
        expectBug: "「WebSocket 執行緒：等委託鎖等了 300 ms 還拿不到，疑似 deadlock，放棄」（也可能是畫面執行緒等價格鎖逾時）；其中一邊放棄之後另一邊才拿得到鎖，晚了 300 ms 才完成。真的 App 沒有逾時保險，就是兩邊永遠卡住。",
        expectFixed: "沒有任何「疑似 deadlock」；log 有「WebSocket 執行緒：更新持倉估值 BTC/JPY 15100000」與「畫面執行緒：下單成功，用價格 …」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            let timeouts = lines.filter { $0.contains("疑似 deadlock") }.count
            if timeouts == 0 { return "Bug 還沒重現：update 要在持有價格鎖的時候呼叫 listener（callback 在鎖裡面）" }
            return nil
        },
        checkFixed: { lines in
            if lines.contains(where: { $0.contains("疑似 deadlock") }) { return "還是 deadlock：update 要先把 listener 複製出來、unlock() 之後才呼叫它" }
            if !lines.contains(where: { $0.hasPrefix("WebSocket 執行緒：更新持倉估值") }) { return "listener 沒有被呼叫：放掉鎖之後要呼叫 callback?(symbol, price)" }
            if !lines.contains(where: { $0.hasPrefix("畫面執行緒：下單成功") }) { return "下單沒有成功" }
            return nil
        }
    )
}
