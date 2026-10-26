// L05：按一次買進，送出了四筆訂單（用 combineLatest 代替 withLatestFrom）
// 對應複習站：Swift12Combine 第 4 題「常用運算子怎麼對應？沒有 withLatestFrom
// 怎麼辦？」
//
// 情境：下單頁有買進按鈕（buyTap）和即時價格（price，CurrentValueSubject）。
// 使用者按一次買進，之後價格又跳了 3 次。
// Bug 版：想寫 RxSwift 的 buyTap.withLatestFrom(price)，Combine 沒有，改用
//        combineLatest。它是「任何一邊有新值就輸出最新的組合」：按過一次
//        之後，價格每變一次就再輸出一次，等於多送一筆訂單。
// 修正版：buyTap.map { _ in price.value }：只有按鈕會觸發，按下時讀價格的
//        目前值，就是 withLatestFrom 的效果。
import Combine
import LabKit

/// 買進按鈕的點擊事件
private typealias L05Tap = PassthroughSubject<Void, Never>
/// 即時價格（保留目前的值）
private typealias L05Price = CurrentValueSubject<Int, Never>

/// 演練流程（兩個版本共用）：按一次買進，之後價格跳 3 次
private func scenario(
    _ log: LabLog,
    _ makeOrders: (L05Tap, L05Price) -> AnyPublisher<Int, Never>
) {
    let buyTap = L05Tap()
    let price = L05Price(15_000_000)
    let sent = Swift12Combine.Box<[Int]>([])
    // 每輸出一個值，就送出一筆訂單（值是下單價格）
    let cancellable = makeOrders(buyTap, price).sink { orderPrice in
        sent.value.append(orderPrice)
    }

    // 使用者按一次買進
    buyTap.send(())
    // 之後價格又跳了 3 次
    price.send(15_010_000)
    price.send(15_020_000)
    price.send(15_030_000)
    log("使用者按了 1 次買進，之後價格跳了 3 次")
    log("送出的訂單：\(sent.value.count) 筆，價格 \(sent.value)")
    withExtendedLifetime(cancellable) {}
}

// MARK: - Bug 版
private enum L05Bug {
    static func orders(
        buyTap: L05Tap, price: L05Price
    ) -> AnyPublisher<Int, Never> {
        // 任何一邊有新值就輸出：價格每變一次，就用上一次的點擊再輸出一次
        buyTap
            .combineLatest(price)
            // 輸出是 (點擊, 價格)，取出價格
            .map { $0.1 }
            .eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        scenario(log, orders)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func orders(
        buyTap: L05Tap, price: L05Price
    ) -> AnyPublisher<Int, Never> {
        // 只有按鈕會觸發；按下時讀價格的目前值（withLatestFrom 的效果）
        buyTap
            .map { _ in price.value }
            .eraseToAnyPublisher()
    }

    static func run(_ log: LabLog) async {
        scenario(log, orders)
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l05 = Lab(
        id: "L05",
        title: "按一次買進，送出了四筆訂單",
        question: "第 4 題：常用運算子怎麼對應？沒有 withLatestFrom 怎麼辦？",
        goal: "按一次買進只送出一筆訂單，價格用按下當時的價格。",
        steps: [
            "選「Bug 版」執行，看最後一行：只按了 1 次，卻送出 4 筆訂單。",
            "想一想：combineLatest 什麼時候輸出？withLatestFrom 呢？",
            "選「修正版」執行：只送出 1 筆，價格是按下時的 15000000。",
            "進階：價格不是 CurrentValueSubject、只是一般的 publisher 時，要怎麼拿到「按下當時的價格」？",
        ],
        expectBug: "送出的訂單：4 筆，價格 [15000000, 15010000, 15020000, 15030000]。",
        expectFixed: "送出的訂單：1 筆，價格 [15000000]。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard !lines.contains(where: { $0.hasPrefix("送出的訂單：0 筆") }) else {
                return "按下買進還沒有送出訂單：orders 要由 buyTap 觸發"
            }
            return lines.contains(
                "送出的訂單：4 筆，價格 [15000000, 15010000, 15020000, 15030000]")
                ? nil : "Bug 還沒重現：用 buyTap.combineLatest(price)，再 map 取出價格"
        },
        checkFixed: { lines in
            guard !lines.contains(where: { $0.hasPrefix("送出的訂單：0 筆") }) else {
                return "按下買進還沒有送出訂單：orders 要由 buyTap 觸發"
            }
            return lines.contains("送出的訂單：1 筆，價格 [15000000]")
                ? nil : "只有按鈕可以觸發下單：buyTap 用 map 讀 price.value，不要用 combineLatest"
        }
    )
}
