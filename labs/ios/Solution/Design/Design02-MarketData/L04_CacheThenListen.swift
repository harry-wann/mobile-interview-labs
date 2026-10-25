// L04：切到詳細頁，畫面顯示的是舊價格，要等下一筆推送才更新（先給畫面快取，再登記監聽，中間的推送漏掉了）
// 對應複習站：Design02MarketData 第 3 題「一條連線、好幾個畫面，要怎麼共用？」（Repository 的快取與推送要接得起來）
//
// 情境：Repository 把最新的價格存在快取裡，WebSocket 每推來一筆就更新快取、並通知已登記的畫面。
//       畫面打開詳細頁時呼叫 observe(symbol, onPrice:)：要先拿到快取的價格（馬上有東西可以顯示），再收到之後的推送。
//       快取裡是 10000000 円。訂閱 topic 要等伺服器回應（await），就在這段時間裡，伺服器剛好推出一筆新價格 10000500 円。
//       這個交易對不常成交，下一筆推送要很久以後才會來。
// Bug 版：observe 先把快取交給畫面，接著 await 訂閱，最後才登記畫面的監聽。等待訂閱的那段時間推來的 10000500 円，
//        快取收到了、更新了，但畫面還沒登記，沒有人通知它。畫面停在 10000000 円，而快取裡明明已經是 10000500 円。
// 修正版：先登記監聽，再把快取交給畫面（這兩步中間沒有 await，不會被插隊），最後才 await 訂閱。
//        之後不管推送在哪個時間點來，畫面都收得到。
import LabKit

private let oldPrice = 10_000_000
private let newPrice = 10_000_500

/// 假的 WebSocket
private final class FakeSocket {
    var onMessage: ((String, Int) -> Void)?

    /// 向伺服器訂閱：要等伺服器回應。就在這段時間裡，伺服器剛好推出一筆新價格
    func subscribe(_ topic: String) async {
        await Task.yield()
        onMessage?(topic, newPrice)
    }
}

/// Repository：快取＋監聽。收到推送的處理（receive）兩個版本一樣：先更新快取，再通知已登記的畫面
private class Repository {
    let socket = FakeSocket()
    var cache: [String: Int] = ["btcjpy": oldPrice]
    var listeners: [String: [(Int) -> Void]] = [:]

    init() {
        socket.onMessage = { [unowned self] topic, price in receive(topic: topic, price: price) }
    }

    func receive(topic: String, price: Int) {
        let symbol = String(topic.split(separator: ".")[1])
        cache[symbol] = price
        for listener in listeners[symbol] ?? [] { listener(price) }
    }

    /// 畫面要看某個交易對：先拿到快取，再收到之後的推送（兩個版本各自覆寫）
    func observe(_ symbol: String, onPrice: @escaping (Int) -> Void) async {}
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, repository: Repository) async {
    var shown: Int?
    log("快取裡是 \(oldPrice) 円")
    await repository.observe("btcjpy") { price in
        shown = price
        log("畫面收到 \(price) 円")
    }
    log("訂閱完成。快取裡是 \(repository.cache["btcjpy"] ?? 0) 円；伺服器最新的價格是 \(newPrice) 円")
    log("最後：畫面顯示 \(shown.map(String.init) ?? "（沒有）") 円，快取裡是 \(repository.cache["btcjpy"] ?? 0) 円")
}

// MARK: - Bug 版
private enum L04Bug {
    final class Repo: Repository {
        override func observe(_ symbol: String, onPrice: @escaping (Int) -> Void) async {
            if let cached = cache[symbol] { onPrice(cached) }              // 步驟 1：先把快取交給畫面
            await socket.subscribe("market.\(symbol).bbo")                 // 步驟 2：訂閱（這段時間推來的價格，還沒有人登記要聽）
            listeners[symbol, default: []].append(onPrice)                 // 步驟 3：最後才登記監聽
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, repository: Repo())
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class Repo: Repository {
        override func observe(_ symbol: String, onPrice: @escaping (Int) -> Void) async {
            listeners[symbol, default: []].append(onPrice)                 // 步驟 1：先登記監聽，之後推來的價格都收得到
            if let cached = cache[symbol] { onPrice(cached) }              // 步驟 2：再把快取交給畫面（和步驟 1 之間沒有 await，不會被插隊）
            await socket.subscribe("market.\(symbol).bbo")                 // 步驟 3：最後才訂閱
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, repository: Repo())
    }
}

// MARK: - 演練設定
extension Design02MarketData {
    static let l04 = Lab(
        id: "L04",
        title: "切到詳細頁，畫面停在舊價格不更新",
        question: "第 3 題：一條連線、好幾個畫面，要怎麼共用？",
        goal: "畫面要先拿快取、再接推送時，順序是「先登記監聽，再給快取，最後才訂閱」。先給快取、最後才登記，中間（await 訂閱的時候）推來的價格會漏掉，畫面停在舊價格。",
        steps: [
            "選「Bug 版」執行，看畫面收到哪幾個價格、最後顯示什麼；對照快取裡的價格。",
            "想一想：快取明明已經是新價格，畫面為什麼還是舊的？（推送來的時候，畫面的監聽還沒登記。）",
            "選「修正版」執行：畫面先收到快取的 10000000 円，再收到 10000500 円。",
            "想一想：登記監聽和給快取這兩步為什麼要連著做、中間不能有 await？（有 await 就可能被別的推送插隊，順序就亂了。）",
        ],
        expectBug: "畫面只收到 10000000 円，快取卻已經是 10000500 円：「最後：畫面顯示 10000000 円，快取裡是 10000500 円」。",
        expectFixed: "畫面先收到 10000000 円（快取），再收到 10000500 円（推送）：「最後：畫面顯示 10000500 円，快取裡是 10000500 円」。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, repository:) 執行" }
            if lines.contains("最後：畫面顯示 （沒有） 円，快取裡是 10000000 円") { return "observe 還沒寫：要先把快取交給畫面、await socket.subscribe，最後才登記監聽" }
            return lines.contains("最後：畫面顯示 10000000 円，快取裡是 10000500 円")
                ? nil : "Bug 還沒重現：先把快取交給畫面，接著 await socket.subscribe，最後才把 onPrice 加進 listeners，訂閱期間的推送就沒人收"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, repository:) 執行" }
            if lines.contains("最後：畫面顯示 （沒有） 円，快取裡是 10000000 円") { return "observe 還沒寫：先登記監聽，再給畫面快取，最後 await socket.subscribe" }
            return lines.contains("最後：畫面顯示 10000500 円，快取裡是 10000500 円")
                ? nil : "先 listeners[symbol, default: []].append(onPrice)，再交出快取，最後才 await socket.subscribe"
        }
    )
}
