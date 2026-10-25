// L05：首頁和下單頁同時要價格，同一個 API 被打了兩次（Combine 沒有 share()）
// 對應複習站：Swift06AsyncStream 第 4 題「Combine 的 debounce、throttle、
// share、switchToLatest 實際上怎麼運作？」
//
// 情境：價格的 publisher 每次被訂閱，就會向伺服器發一次請求。
// 首頁和下單頁各訂閱一次。
// Bug 版：兩個訂閱者各自訂閱同一個 publisher，上游被觸發兩次，打了兩次 API。
// 修正版：publisher 後面接 share()：兩個訂閱者共用同一次上游訂閱，只打一次
// API。
//        （share() 不會重播：晚到的訂閱者只收到之後的值。）
import Combine
import Foundation
import LabKit

/// 假的 API（兩個版本共用）：每次有人訂閱，就算一次請求；50 毫秒後回應價格
private final class FakeAPI: @unchecked Sendable {
    private let requests = Swift06AsyncStream.Counter()
    var requestCount: Int { requests.count }

    func ticker() -> AnyPublisher<Int, Never> {
        Deferred { [requests] () -> AnyPublisher<Int, Never> in
            // 上游被訂閱一次，就打一次 API
            requests.hit()
            return Just(15_000_000)
                .delay(
                    for: .milliseconds(50), scheduler: DispatchQueue.global()
                )
                .eraseToAnyPublisher()
        }
        .eraseToAnyPublisher()
    }
}

/// 等兩個訂閱者都收到價格（最多 3 秒），再多等一下讓晚到的事件進來；不要固定睡
/// 300 毫秒，整批檢查一起跑時機器忙會不夠
private func waitForBoth(
    _ a: Swift06AsyncStream.Counter, _ b: Swift06AsyncStream.Counter
) async {
    var waited = 0
    while (a.count == 0 || b.count == 0) && waited < 3_000 {
        try? await Task.sleep(for: .milliseconds(20))
        waited += 20
    }
    try? await Task.sleep(for: .milliseconds(100))
}

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        let api = FakeAPI()
        // 沒有 share()
        let price = api.ticker()
        let home = Swift06AsyncStream.Counter()
        let order = Swift06AsyncStream.Counter()
        var cancellables = Set<AnyCancellable>()
        price.sink { _ in home.hit() }.store(in: &cancellables)  // 首頁訂閱
        // 下單頁訂閱
        price.sink { _ in order.hit() }.store(in: &cancellables)
        await waitForBoth(home, order)
        log("首頁收到 \(home.count) 次、下單頁收到 \(order.count) 次")
        log("API 被呼叫 \(api.requestCount) 次")
        // 訂閱要活到這裡（Release 建置的區域變數用完最後一次就會被釋放）
        withExtendedLifetime(cancellables) {}
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        let api = FakeAPI()
        // 共用同一次上游訂閱
        let price = api.ticker().share()
        let home = Swift06AsyncStream.Counter()
        let order = Swift06AsyncStream.Counter()
        var cancellables = Set<AnyCancellable>()
        price.sink { _ in home.hit() }.store(in: &cancellables)
        price.sink { _ in order.hit() }.store(in: &cancellables)
        await waitForBoth(home, order)
        log("首頁收到 \(home.count) 次、下單頁收到 \(order.count) 次")
        log("API 被呼叫 \(api.requestCount) 次")
        // 訂閱要活到這裡（Release 建置的區域變數用完最後一次就會被釋放）
        withExtendedLifetime(cancellables) {}
    }
}

// MARK: - 演練設定
extension Swift06AsyncStream {
    static let l05 = Lab(
        id: "L05",
        title: "同一個價格 API 被打了兩次",
        question:
            "第 4 題：Combine 的 debounce、throttle、share、switchToLatest 實際上怎麼運作？",
        goal: "首頁和下單頁都要收到價格，但只能向伺服器請求一次。",
        steps: [
            "選「Bug 版」執行，看最後一行：API 被呼叫幾次。",
            "想一想：一個 publisher 被訂閱兩次，上游的工作會做幾次？",
            "選「修正版」執行：兩個畫面都收到價格，API 只被呼叫 1 次。",
            "進階：share() 不會重播。在第一個價格回來之後才訂閱的第三個訂閱者，收不到那一筆（需要重播用 CurrentValueSubject 或 multicast）。",
        ],
        expectBug: "首頁收到 1 次、下單頁收到 1 次；API 被呼叫 2 次。",
        expectFixed: "首頁收到 1 次、下單頁收到 1 次；API 被呼叫 1 次。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains("首頁收到 1 次、下單頁收到 1 次") else {
                return "兩個訂閱者都要收到價格：各自對 price 做 sink"
            }
            return lines.last == "API 被呼叫 2 次"
                ? nil : "Bug 還沒重現：API 應該被呼叫 2 次（不要用 share()）"
        },
        checkFixed: { lines in
            guard lines.contains("首頁收到 1 次、下單頁收到 1 次") else {
                return "兩個訂閱者都要收到價格：各自對 price 做 sink"
            }
            return lines.last == "API 被呼叫 1 次"
                ? nil : "API 應該只被呼叫 1 次：在 api.ticker() 後面接 share()"
        }
    )
}
