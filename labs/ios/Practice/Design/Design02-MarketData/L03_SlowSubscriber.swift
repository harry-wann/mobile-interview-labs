// L03：開著訂單簿詳細頁，行情列表跟著變慢（分派訊息時，
// 一個慢的畫面拖住所有畫面）
// 對應複習站：Design02MarketData 第 5 題「每秒很多則訊息，畫面怎麼保持順暢？」
//
// 情境：分派器收到 socket 傳來的價格，要交給兩個畫面：行情列表（處理很快）和
// BTC 訂單簿詳細頁（每筆要算 20 檔、格式化，約 40 毫秒）。
//       一瞬間來了 8 筆價格（編號 1 到 8），都在 socket 的佇列上交給分派器。
//       看行情列表什麼時候收到最後一筆（#8），以及詳細頁處理了幾筆。
//
// 你要寫：
//   ① Bug 版（L03Bug.Hub）：register 把 handler 存起來；publish 在 socket
// 的佇列上，依序直接呼叫每個 handler。
//   ② 修正版（L03Fixed.Hub）：每個畫面一個 AsyncStream（.bufferingNewest(1)）
// 加一個消費者 Task；publish 只負責 yield 進每個畫面的緩衝區。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Design/Design02-MarketData/L03_SlowSubscriber.swift
import Foundation
import LabKit

private let tickCount = 8
private let slowCostMs = 40

private protocol Dispatcher: AnyObject, Sendable {
    /// 登記一個畫面：之後每一筆價格都要交給它的 handler
    func register(_ name: String, handler: @escaping (Int) -> Void)
    /// 收到一筆價格（在 socket 的佇列上呼叫）
    func publish(_ tick: Int)
}

/// 記下兩個畫面各自收到什麼（兩個版本共用）
private final class Board: @unchecked Sendable {
    private let lock = NSLock()
    private var listGotLastAt: Double?
    private var detailTicks: [Int] = []

    func listGot(_ tick: Int) {
        guard tick == tickCount else { return }
        lock.lock(); listGotLastAt = d02NowMs(); lock.unlock()
    }

    func detailDone(_ tick: Int) {
        lock.lock(); detailTicks.append(tick); lock.unlock()
    }

    var snapshot: (listGotLastAt: Double?, detailTicks: [Int]) {
        lock.lock(); defer { lock.unlock() }
        return (listGotLastAt, detailTicks)
    }
}

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, makeDispatcher: () -> Dispatcher) async {
    let dispatcher = makeDispatcher()
    let board = Board()
    dispatcher.register("行情列表") { tick in board.listGot(tick) }
    dispatcher.register("訂單簿詳細頁") { tick in
        // 算 20 檔、格式化：40 毫秒
        Thread.sleep(forTimeInterval: Double(slowCostMs) / 1000)
        board.detailDone(tick)
    }

    let socket = DispatchQueue(label: "socket")
    let start = d02NowMs()
    for tick in 1...tickCount {
        // 一瞬間來了 8 筆
        socket.async { dispatcher.publish(tick) }
    }
    log("一瞬間來了 \(tickCount) 筆價格（#1 到 #\(tickCount)），交給分派器")

    // 最多等 2 秒
    let deadline = start + 2000
    while d02NowMs() < deadline {
        let now = board.snapshot
        if now.listGotLastAt != nil && now.detailTicks.last == tickCount {
            break
        }
        try? await Task.sleep(for: .milliseconds(5))
    }

    let result = board.snapshot
    guard let listAt = result.listGotLastAt else {
        log("行情列表沒有收到最後一筆（#\(tickCount)）：register 與 publish 還沒寫完")
        log("最後：行情列表沒有收到最後一筆")
        return
    }
    let lag = Int(listAt - start)
    log("行情列表收到最後一筆（#\(tickCount)）：距離這批價格送到 \(lag) 毫秒")
    log(
        "訂單簿詳細頁處理了 \(result.detailTicks.count) 筆：\(result.detailTicks.map { "#\($0)" }.joined(separator: " "))"
    )
    let listText =
        lag >= 150
        ? "被拖慢了（最後一筆晚了 150 毫秒以上）"
        : (lag < 100 ? "不受影響（最後一筆在 100 毫秒內收到）" : "有點慢（最後一筆晚了 \(lag) 毫秒）")
    let detailText =
        result.detailTicks.count == tickCount
        ? "把 \(tickCount) 筆全部依序處理" : "只處理最新的幾筆（少於 \(tickCount) 筆）"
    log(
        "最後：行情列表\(listText)；詳細頁\(detailText)，最後處理的是 #\(result.detailTicks.last ?? 0)"
    )
}

// MARK: - Bug 版
private enum L03Bug {
    final class Hub: Dispatcher, @unchecked Sendable {
        private var handlers: [(Int) -> Void] = []

        func register(_ name: String, handler: @escaping (Int) -> Void) {
            // TODO ①-1：把 handler 存進 handlers
        }

        func publish(_ tick: Int) {
            // TODO ①-2：依序呼叫 handlers 裡的每個 handler(tick)，
            // 就直接在這個函式裡呼叫（不要另外開佇列或 Task）
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { Hub() }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    final class Hub: Dispatcher, @unchecked Sendable {
        private var inboxes: [AsyncStream<Int>.Continuation] = []

        func register(_ name: String, handler: @escaping (Int) -> Void) {
            // TODO ②-1：替這個畫面建立自己的緩衝區：AsyncStream.makeStream(of:
            // Int.self, bufferingPolicy: bufferingNewest(1))，
            //           把回傳的 continuation 存進 inboxes；再開一個 Task.
            // detached，for await 讀自己的 stream，每一筆呼叫 handler
        }

        func publish(_ tick: Int) {
            // TODO ②-2：對 inboxes 裡的每個 continuation 呼叫 yield(tick)。
            // 只丟進緩衝區，不要在這裡呼叫 handler
        }
    }

    static func run(_ log: LabLog) async {
        await scenario(log) { Hub() }
    }
}

// MARK: - 演練設定
extension Design02MarketData {
    static let l03 = Lab(
        id: "L03",
        title: "開著訂單簿詳細頁，行情列表跟著變慢",
        question: "第 5 題：每秒很多則訊息，畫面怎麼保持順暢？",
        goal:
            "分派器不能在 socket 的佇列上直接呼叫每個畫面的處理函式：一個慢的畫面會拖住所有畫面。每個畫面要有自己的緩衝區（只留最新一筆）和自己的消費者，分派器只負責把價格丟進去。先寫出同步呼叫的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Design/Design02-MarketData/L03_SlowSubscriber.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」執行，看行情列表收到最後一筆（#8）距離這批價格送到幾毫秒，以及詳細頁處理了幾筆。",
            "想一想：行情列表的處理明明很快，為什麼會被詳細頁拖慢？（在同一條佇列上一個接一個呼叫。）",
            "寫 TODO ②（修正版），選「修正版」執行：行情列表幾乎馬上收到 #8；詳細頁只處理最新的幾筆，最後一筆仍然是 #8。",
            "想一想：詳細頁少處理了幾筆，使用者會看到錯的資料嗎？（行情只在乎最新的價格；成交明細這類不能丟的資料不能這樣做，見 Advanced05 第 4 題。）",
        ],
        expectBug:
            "行情列表最後一筆 #8 晚了約 300 毫秒才收到（超過 150 毫秒）；詳細頁把 8 筆全部依序處理：「最後：行情列表被拖慢了（最後一筆晚了 150 毫秒以上）；詳細頁把 8 筆全部依序處理，最後處理的是 #8」。",
        expectFixed:
            "行情列表幾乎馬上（0 到幾毫秒）收到 #8；詳細頁只處理最新的幾筆（通常是 #1 和 #8 共 2 筆），最後處理的仍是 #8：「最後：行情列表不受影響（最後一筆在 100 毫秒內收到）；詳細頁只處理最新的幾筆（少於 8 筆），最後處理的是 #8」。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeDispatcher:) 執行"
            }
            if last == "最後：行情列表沒有收到最後一筆" {
                return
                    "行情列表沒有收到價格：register 要把 handler 存起來，publish 要依序呼叫每個 handler"
            }
            return last
                == "最後：行情列表被拖慢了（最後一筆晚了 150 毫秒以上）；詳細頁把 8 筆全部依序處理，最後處理的是 #8"
                ? nil
                : "Bug 還沒重現：publish 要在 socket 的佇列上直接 for handler in handlers { handler(tick) }，慢的詳細頁才會拖住行情列表"
        },
        checkFixed: { lines in
            guard let last = lines.last(where: { $0.hasPrefix("最後") }) else {
                return "演練沒有跑完：要用 scenario(log, makeDispatcher:) 執行"
            }
            if last == "最後：行情列表沒有收到最後一筆" {
                return
                    "行情列表沒有收到價格：register 要建立 AsyncStream 與消費者 Task，publish 要 yield 給每個畫面"
            }
            if !last.hasPrefix("最後：行情列表不受影響（最後一筆在 100 毫秒內收到）") {
                return
                    "行情列表還是被拖慢：publish 不能直接呼叫 handler，要 yield 進每個畫面各自的 AsyncStream（.bufferingNewest(1)）"
            }
            return last.hasSuffix("最後處理的是 #8") && last.contains("只處理最新的幾筆")
                ? nil
                : "每個畫面自己的緩衝區要用 .bufferingNewest(1)：慢的詳細頁只處理最新的幾筆，最後一筆是 #8"
        }
    )
}
