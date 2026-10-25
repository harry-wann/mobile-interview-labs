// L01：行情停了，畫面卻停在幾百毫秒前的價格（降低更新頻率時，最後一筆沒有送出去）
// 對應複習站：Exchange06RealtimeUI 第 3 題「畫面多久更新一次？用什麼來驅動？」
//
// 情境：行情每 30 毫秒來一筆價格（價格 = 100 + 第幾筆），從 0 毫秒到 330 毫秒共 12 筆，最新價是 111。畫面每 100 毫秒更新一次（降頻）。
//       0、100、200、300 毫秒，畫面的計時器各響一次；330 毫秒是最後一筆，之後行情停了，計時器也跟著停。
// Bug 版：「進來的價格，距離上次送給畫面超過 100 毫秒才送，不到 100 毫秒就直接丟掉」。中間的價格丟掉沒關係，但最後一筆剛好落在 100 毫秒之內，
//        也被丟掉，行情停了之後沒有人再送：畫面停在 108，最新價是 111。
// 修正版：ThrottleLatest：進來的價格只記下最新的（蓋掉還沒送的），由計時器響的時候才送，而且距離上次送出超過 100 毫秒才送；
//        串流結束時，把還沒送出的最新值取走（flush）。
import LabKit

private protocol PriceThrottle: AnyObject {
    /// 一則價格到達（背景）。回傳有值代表立刻送給畫面
    func onValue(_ value: Int, nowMs: Int64) -> Int?
    /// 畫面的計時器響了（每 100 毫秒一次）
    func onTick(nowMs: Int64) -> Int?
    /// 串流結束：把還沒送出的值取走
    func onEnd() -> Int?
}

private let intervalMs: Int64 = 100

/// 演練流程（兩個版本共用）：價格每 30 毫秒一筆，計時器每 100 毫秒響一次（同一毫秒時，價格先到）
private func scenario(_ log: LabLog, throttle: PriceThrottle) {
    var shown: Int?
    func show(_ value: Int?, at ms: Int64) {
        guard let value else { return }
        shown = value
        log("t=\(ms)：畫面顯示 \(value)")
    }
    var events: [(ms: Int64, isValue: Bool)] = []
    for i in 0...11 { events.append((Int64(i * 30), true)) }
    for ms in [0, 100, 200, 300] as [Int64] { events.append((ms, false)) }
    events.sort { ($0.ms, $0.isValue ? 0 : 1) < ($1.ms, $1.isValue ? 0 : 1) }
    for event in events {
        if event.isValue {
            show(throttle.onValue(100 + Int(event.ms / 30), nowMs: event.ms), at: event.ms)
        } else {
            show(throttle.onTick(nowMs: event.ms), at: event.ms)
        }
    }
    show(throttle.onEnd(), at: 330)
    log("最後：畫面顯示 \(shown.map(String.init) ?? "-")，最新價是 111")
}

// MARK: - Bug 版
private enum L01Bug {
    final class Throttle: PriceThrottle {
        private var lastEmitMs: Int64?

        func onValue(_ value: Int, nowMs: Int64) -> Int? {
            if let last = lastEmitMs, nowMs - last < intervalMs { return nil }      // 距離上次送出不到 100 毫秒：直接丟掉
            lastEmitMs = nowMs
            return value
        }

        func onTick(nowMs: Int64) -> Int? { nil }
        func onEnd() -> Int? { nil }
    }

    static func run(_ log: LabLog) async {
        scenario(log, throttle: Throttle())
    }
}

// MARK: - 修正版
private enum L01Fixed {
    final class Throttle: PriceThrottle {
        private var pending: Int?
        private var lastEmitMs: Int64?

        func onValue(_ value: Int, nowMs: Int64) -> Int? {
            pending = value                                                         // 只記下最新的，蓋掉還沒送出的
            return nil
        }

        func onTick(nowMs: Int64) -> Int? {
            guard let value = pending else { return nil }                           // 沒有待送的值：不送
            if let last = lastEmitMs, nowMs - last < intervalMs { return nil }      // 還不到 100 毫秒：待送的值留到下次
            pending = nil
            lastEmitMs = nowMs
            return value
        }

        func onEnd() -> Int? {                                                      // 串流結束：把尾端還沒送出的值取走
            defer { pending = nil }
            return pending
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, throttle: Throttle())
    }
}

// MARK: - 演練設定
extension Exchange06RealtimeUI {
    static let l01 = Lab(
        id: "L01",
        title: "行情停了，畫面停在舊價格",
        question: "第 3 題：畫面多久更新一次？用什麼來驅動？",
        goal: "降低更新頻率時，中間的價格可以丟掉，但「最新的一筆」不能丟：先記下最新值，由計時器固定節奏送出，串流結束時再把尾端還沒送出的值取走。",
        steps: [
            "選「Bug 版」執行，看畫面一路顯示了哪些價格，最後停在哪裡？最新價是多少？",
            "想一想：最後一筆 111 在 330 毫秒到達，距離上次送出（240 毫秒）才 90 毫秒，被丟掉之後，還有誰會再送一次？",
            "選「修正版」執行，畫面最後要顯示 111。",
            "進階：時鐘倒退（現在的時間比上次送出的時間還早）時該怎麼辦？規則是不送出，待送的值保留。",
        ],
        expectBug: "畫面依序顯示 100、104、108，「最後：畫面顯示 108，最新價是 111」：最後三筆價格被丟掉，行情停了就沒有人再更新。",
        expectFixed: "畫面依序顯示 100、103、106、110，串流結束再顯示 111：「最後：畫面顯示 111，最新價是 111」。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, throttle:) 執行" }
            if !lines.contains("t=120：畫面顯示 104") { return "Bug 版的 onValue 要「距離上次送出超過 100 毫秒才送出」：沒有上次送出的紀錄，或已經過了 100 毫秒，就更新 lastEmitMs 並回傳這個價格" }
            return lines.contains("最後：畫面顯示 108，最新價是 111")
                ? nil : "Bug 還沒重現：不到 100 毫秒的價格直接丟掉（回傳 nil），onTick、onEnd 都不送，最後一筆 111 就送不出去"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, throttle:) 執行" }
            if !lines.contains("t=100：畫面顯示 103") { return "onValue 只記下最新的值（pending = value）；onTick 在計時器響的時候送出 pending，t=100 要顯示 103" }
            return lines.contains("最後：畫面顯示 111，最新價是 111")
                ? nil : "onEnd 要把還沒送出的 pending 取走（flush）並清掉，不然最後一筆 111 送不出去"
        }
    )
}
