// L04：價格停在快照，之後的推送都不見了（subscribe(_:) 把 subject 結束掉）
// 對應複習站：Swift12Combine 第 3 題「PublishRelay、BehaviorRelay 對應什麼？
// 為什麼 bind(to:) 不能直接換成 subscribe(_:)？」
//
// 情境：行情畫面訂閱共用的 store.price（PassthroughSubject）。先用 REST
// 快照（Just，送一筆就結束）填入第一筆價格，之後 WebSocket 推來兩筆。
// Bug 版：照 RxSwift 的 bind(to: relay) 寫成
//        snapshot().subscribe(store.price)。subscribe(_:) 會把上游的結束
//        事件也轉送給 subject：快照送完就 .finished，store.price 跟著結束，
//        之後的 send 全部被忽略，沒有錯誤訊息。
// 修正版：用 sink 收到快照就 store.price.send(…)，只轉送值、不轉送結束事件
//        （RxSwift 的 Relay 不能被結束，Combine 沒有 Relay）。
import Combine
import LabKit

/// 共用的價格 store（兩個版本共用）
private final class L04PriceStore {
    let price = PassthroughSubject<Int, Never>()
}

/// 假的 REST API（兩個版本共用）：快照送出一筆就結束，像 RxSwift 的 Single
private enum L04FakeAPI {
    static func snapshot() -> AnyPublisher<Int, Never> {
        Just(15_000_000).eraseToAnyPublisher()
    }
}

/// 演練流程（兩個版本共用）：畫面訂閱、接上快照、WebSocket 再推兩筆
private func scenario(
    _ log: LabLog,
    _ connectSnapshot: (L04PriceStore, inout Set<AnyCancellable>) -> Void
) {
    let store = L04PriceStore()
    let shown = Swift12Combine.Box<[Int]>([])
    let finished = Swift12Combine.Box(false)
    var cancellables = Set<AnyCancellable>()

    // 行情畫面訂閱共用的價格
    store.price
        .sink(
            receiveCompletion: { _ in
                finished.value = true
            },
            receiveValue: { price in
                shown.value.append(price)
            }
        )
        .store(in: &cancellables)

    // 先用 REST 快照填入第一筆價格
    connectSnapshot(store, &cancellables)

    // 之後 WebSocket 推來兩筆
    store.price.send(15_010_000)
    store.price.send(15_020_000)
    log("畫面收到的價格：\(shown.value)")
    log("price 已經結束：\(finished.value ? "是" : "否")")
    withExtendedLifetime(cancellables) {}
}

// MARK: - Bug 版
private enum L04Bug {
    static func connectSnapshot(
        _ store: L04PriceStore, _ cancellables: inout Set<AnyCancellable>
    ) {
        // 值和結束事件都轉送給 store.price：Just 送完一筆就 finished，
        // store.price 也跟著結束
        L04FakeAPI.snapshot()
            .subscribe(store.price)
            .store(in: &cancellables)
    }

    static func run(_ log: LabLog) async {
        scenario(log, connectSnapshot)
    }
}

// MARK: - 修正版
private enum L04Fixed {
    static func connectSnapshot(
        _ store: L04PriceStore, _ cancellables: inout Set<AnyCancellable>
    ) {
        // 只轉送值，不轉送結束事件（對應 RxSwift 的 bind(to: relay)）
        L04FakeAPI.snapshot()
            .sink { price in
                store.price.send(price)
            }
            .store(in: &cancellables)
    }

    static func run(_ log: LabLog) async {
        scenario(log, connectSnapshot)
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l04 = Lab(
        id: "L04",
        title: "價格停在快照，之後的推送都不見了",
        question:
            "第 3 題：PublishRelay、BehaviorRelay 對應什麼？為什麼 bind(to:) 不能直接換成 subscribe(_:)？",
        goal: "快照先填入第一筆價格，之後 WebSocket 推來的價格也要送到畫面。",
        steps: [
            "選「Bug 版」執行，看第一行：畫面只收到快照那一筆。",
            "看第二行：store.price 已經結束了。之後的 send 被默默忽略，沒有任何錯誤。",
            "選「修正版」執行：畫面收到 3 筆價格，store.price 沒有結束。",
            "想一想：RxSwift 的 bind(to: relay) 為什麼不會有這個問題？PassthroughSubject 的 Failure 是 Never，為什麼還是會結束？",
        ],
        expectBug: "畫面收到的價格：[15000000]；price 已經結束：是。",
        expectFixed: "畫面收到的價格：[15000000, 15010000, 15020000]；price 已經結束：否。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面收到的價格：[15000000") })
            else {
                return "快照還沒送到畫面：把 snapshot() 接到 store.price"
            }
            guard lines.contains("畫面收到的價格：[15000000]"),
                lines.contains("price 已經結束：是")
            else {
                return "Bug 還沒重現：用 subscribe(_:) 把快照直接接到 store.price"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面收到的價格：[15000000") })
            else {
                return "快照還沒送到畫面：把 snapshot() 接到 store.price"
            }
            guard lines.contains("畫面收到的價格：[15000000, 15010000, 15020000]"),
                lines.contains("price 已經結束：否")
            else {
                return "store.price 被結束了：改用 sink，收到快照就 store.price.send，只轉送值"
            }
            return nil
        }
    )
}
