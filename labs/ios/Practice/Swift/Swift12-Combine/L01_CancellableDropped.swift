// L01：訂閱了行情，卻一筆都沒收到（AnyCancellable 沒有存起來）
// 對應複習站：Swift12Combine 第 1 題「Observable、Observer、Disposable 在
// Combine 叫什麼？DisposeBag 怎麼換？」
//
// 情境：行情畫面打開時訂閱價格推送，之後推送 5 筆價格；接著關閉畫面，
// 再推 3 筆。
//
// 你要寫：
//   ① Bug 版：onAppear 裡對 feed.ticks 做 sink（收到就 received.hit()），
//     回傳值用 _ = 丟掉，像 RxSwift 裡沒寫 disposed(by:)。
//   ② 修正版：同樣的 sink，接 store(in: &cancellables)，存在畫面物件上。
// 只改有 TODO 的地方。解答：
// Solution/Swift/Swift12-Combine/L01_CancellableDropped.swift
import Combine
import LabKit

/// 假的行情推送（兩個版本共用）：記下被訂閱幾次、被取消幾次
private final class L01Feed {
    let subscriptions = Swift12Combine.Counter()
    let cancels = Swift12Combine.Counter()
    private let subject = PassthroughSubject<Int, Never>()

    var ticks: AnyPublisher<Int, Never> {
        subject
            .handleEvents(
                receiveSubscription: { [subscriptions] _ in
                    subscriptions.hit()
                },
                receiveCancel: { [cancels] in
                    cancels.hit()
                }
            )
            .eraseToAnyPublisher()
    }

    func push(_ price: Int) {
        subject.send(price)
    }
}

/// 行情畫面（兩個版本各自實作 onAppear）
private protocol L01Screen: AnyObject {
    var received: Swift12Combine.Counter { get }
    func onAppear(feed: L01Feed)
}

/// 演練流程（兩個版本共用）：打開畫面、推 5 筆、關閉畫面、再推 3 筆
private func scenario(_ log: LabLog, _ makeScreen: () -> L01Screen) {
    let feed = L01Feed()
    var screen: L01Screen? = makeScreen()
    let received = screen?.received ?? Swift12Combine.Counter()
    screen?.onAppear(feed: feed)

    // 畫面要活到推送完（Release 建置的區域變數用完最後一次就會被釋放）
    withExtendedLifetime(screen) {
        for i in 1...5 {
            feed.push(15_000_000 + i * 1_000)
        }
        log(
            "畫面開著時：訂閱 \(feed.subscriptions.count) 次、"
                + "已取消 \(feed.cancels.count) 次、收到 \(received.count) 筆")
    }

    // 關閉畫面：畫面物件被釋放
    screen = nil
    for i in 6...8 {
        feed.push(15_000_000 + i * 1_000)
    }
    log(
        "關閉畫面後：已取消 \(feed.cancels.count) 次，"
            + "又推 3 筆，總共收到 \(received.count) 筆")
}

// MARK: - Bug 版
private enum L01Bug {
    final class Screen: L01Screen {
        let received = Swift12Combine.Counter()

        func onAppear(feed: L01Feed) {
            // TODO ①：對 feed.ticks 做 sink（收到就 received.hit()），
            // 回傳值用 _ = 丟掉
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Screen() }
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class Screen: L01Screen {
        let received = Swift12Combine.Counter()
        // 對應 RxSwift 的 DisposeBag：畫面物件釋放時，裡面的訂閱一起取消
        private var cancellables = Set<AnyCancellable>()

        func onAppear(feed: L01Feed) {
            // TODO ②：對 feed.ticks 做 sink（收到就 received.hit()），
            // 回傳值存進 cancellables（對應 .disposed(by: disposeBag)）
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log) { Screen() }
    }
}

// MARK: - 演練設定
extension Swift12Combine {
    static let l01 = Lab(
        id: "L01",
        title: "訂閱了行情，卻一筆都沒收到",
        question:
            "第 1 題：Observable、Observer、Disposable 在 Combine 叫什麼？DisposeBag 怎麼換？",
        goal: "畫面開著時收到每一筆價格，畫面關閉後訂閱跟著取消。",
        steps: [
            "寫 TODO ①，選「練習」「Bug 版」執行，看第一行：訂閱了 1 次，卻馬上被取消，收到 0 筆，出現 ✓。",
            "想一想：RxSwift 忘了 disposed(by:) 會怎樣？Combine 忘了存 AnyCancellable 又會怎樣？",
            "寫 TODO ②，選「修正版」執行：畫面開著時收到 5 筆；關閉畫面後訂閱被取消，之後的 3 筆不會再收到。",
            "進階：把 feed 換成 Just(1) 再用 _ = sink 試試，會發現收得到。同步送值的來源會讓這個 bug 在測試時被藏起來。",
        ],
        expectBug: "畫面開著時：訂閱 1 次、已取消 1 次、收到 0 筆。",
        expectFixed:
            "畫面開著時：訂閱 1 次、已取消 0 次、收到 5 筆；關閉畫面後：已取消 1 次，又推 3 筆，總共還是收到 5 筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面開著時：訂閱 1 次") })
            else {
                return "onAppear 還沒訂閱：對 feed.ticks 做 sink"
            }
            return lines.contains("畫面開著時：訂閱 1 次、已取消 1 次、收到 0 筆")
                ? nil : "Bug 還沒重現：sink 的回傳值要用 _ = 丟掉，不要存起來"
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("畫面開著時：訂閱 1 次") })
            else {
                return "onAppear 還沒訂閱：對 feed.ticks 做 sink"
            }
            guard lines.contains("畫面開著時：訂閱 1 次、已取消 0 次、收到 5 筆")
            else {
                return "畫面開著時要收到 5 筆：sink 的回傳值要 store(in: &cancellables)"
            }
            return lines.contains("關閉畫面後：已取消 1 次，又推 3 筆，總共收到 5 筆")
                ? nil : "關閉畫面後訂閱要被取消：cancellables 要存在畫面物件上"
        }
    )
}
