// L04：行情畫面開了 6 個小時，App 越來越慢，最後被系統殺掉（沒有上限的累積，用
// soak test 看記憶體曲線）
// 對應複習站：Advanced03Memory 第 6 題「行情畫面開了幾個小時，App
// 變慢然後被殺，會是什麼原因？」（第 5 題的找法也用在這裡）
//
// 情境：行情畫面每秒收到一筆成交（Tick），開著 6 個小時就是 21,600 筆。
// 每一筆都連同伺服器傳來的原始訊息（約 2 KB，除錯用）一起留著。
//        演練把 6 個小時壓縮成一個迴圈，每「過一小時」
// 記一次保留的筆數與記憶體用量（phys_footprint）：這就是 soak
// test（長時間跑同一個操作，看記憶體會不會一直增加）。
// Bug 版：每一筆都 append 到陣列，永遠不丟。每過一小時多 3600 筆、約 9 MB，6
// 小時後約 56 MB，而且沒有盡頭。
// 修正版：所有累積的資料都要有上限：只保留最新的 500 筆，超過就丟掉最舊的。
// 記憶體曲線很快就變平（約 4 到 7 MB 之後不再增加）。
import Foundation
import LabKit

private let ticksPerHour = 3_600
private let hours = 6

/// 一筆成交：交易對、價格，和伺服器傳來的原始訊息（約 2 KB）
private struct Tick {
    let symbol: String
    let price: Int
    let raw: Data
}

/// 行情歷史的共同介面（兩個版本共用）
private protocol TickHistory: AnyObject {
    func append(_ tick: Tick)
    var count: Int { get }
}

/// 演練流程（兩個版本共用）：模擬 6 小時，每小時記一次保留幾筆、記憶體多了多少
private func scenario(_ log: LabLog, history: some TickHistory) {
    let start = a03FootprintMB()
    var n = 0
    for hour in 1...hours {
        for _ in 0..<ticksPerHour {
            n += 1
            history.append(
                Tick(
                    symbol: "BTC/JPY", price: 15_000_000 + n % 1_000,
                    raw: Data(repeating: UInt8(n % 251), count: 2_048)))
        }
        log(
            "第 \(hour) 小時結束：保留 \(history.count) 筆，記憶體比開始時多 \(Int(a03FootprintMB() - start)) MB"
        )
    }
    log("6 小時共收到 \(n) 筆，最後保留 \(history.count) 筆")
}

// MARK: - Bug 版
private enum L04Bug {
    final class UnboundedHistory: TickHistory {
        private var ticks: [Tick] = []

        // 只加不減：沒有任何地方把舊的丟掉
        func append(_ tick: Tick) { ticks.append(tick) }
        var count: Int { ticks.count }
    }

    static func run(_ log: LabLog) async {
        await Task.detached { scenario(log, history: UnboundedHistory()) }.value
    }
}

// MARK: - 修正版
private enum L04Fixed {
    final class BoundedHistory: TickHistory {
        private let capacity = 500
        private var ticks: [Tick] = []

        func append(_ tick: Tick) {
            ticks.append(tick)
            // 超過上限就丟掉最舊的
            if ticks.count > capacity {
                ticks.removeFirst(ticks.count - capacity)
            }
        }

        var count: Int { ticks.count }
    }

    static func run(_ log: LabLog) async {
        await Task.detached { scenario(log, history: BoundedHistory()) }.value
    }
}

// MARK: - 演練設定
/// 第 6 小時結束時，記憶體比開始時多幾 MB
private func lastHourMB(_ lines: [String]) -> Double? {
    guard let line = lines.first(where: { $0.hasPrefix("第 6 小時結束：") }),
        let r = line.range(of: "多 ")
    else { return nil }
    return Double(line[r.upperBound...].prefix { $0.isNumber || $0 == "-" })
}

extension Advanced03Memory {
    static let l04 = Lab(
        id: "L04",
        title: "行情畫面開 6 小時，App 越來越慢，最後被系統殺掉",
        question: "第 6 題：行情畫面開了幾個小時，App 變慢然後被殺，會是什麼原因？",
        goal:
            "所有 buffer、歷史資料都要有明確的上限。用 soak test（長時間重複同一個操作，看記憶體曲線）確認記憶體不會一直長。",
        steps: [
            "選「Bug 版」按「執行」，看每小時一行的 log：保留的筆數和記憶體一路往上，沒有盡頭。",
            "選「修正版」按「執行」：保留的筆數固定是 500，記憶體幾乎不變。",
            "進階：用第 5 題的工具找出是誰在長。Xcode 開 Bug 版，Debug navigator 的 Memory 看曲線；或 Instruments 選 Allocations，錄製途中每過一段時間按一次 Mark Generation，Growth 欄位會顯示每一輪之後多出來的物件，點進去看是 Tick 與 Data。",
            "想一想：除了加上限，進到背景或收到記憶體警告時，還能再放掉什麼？（見這個單元的第 1、6 題）",
        ],
        expectBug:
            "第 1 小時保留 3600 筆、記憶體多約 9 MB；第 6 小時保留 21600 筆、多約 56 MB，一路往上沒有盡頭。",
        expectFixed:
            "每個小時結束都是「保留 500 筆」，記憶體多 4 到 7 MB 就不再增加；6 小時共收到 21600 筆，最後保留 500 筆。",
        run: { variant, log in
            switch variant {
            case .bug: await L04Bug.run(log)
            case .fixed: await L04Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard
                let kept = a03Number(in: lines, prefix: "6 小時共收到 21600 筆，最後保留 ")
            else { return "演練沒有跑完" }
            if kept < 21_600 {
                return "最後只保留 \(Int(kept)) 筆：Bug 版什麼都不丟，要全部留著（21600 筆）"
            }
            guard let mb = lastHourMB(lines) else { return "演練沒有跑完" }
            return mb >= 30
                ? nil : "記憶體只多了 \(Int(mb)) MB：每筆 Tick 都要連同 raw（約 2 KB）一起留著"
        },
        checkFixed: { lines in
            guard
                let kept = a03Number(in: lines, prefix: "6 小時共收到 21600 筆，最後保留 ")
            else { return "演練沒有跑完" }
            if kept > 500 {
                return
                    "最後保留了 \(Int(kept)) 筆，沒有上限：append 之後超過 500 筆就要丟掉最舊的（removeFirst）"
            }
            if kept < 500 { return "最後只保留 \(Int(kept)) 筆：上限是 500 筆，不是更少" }
            guard let mb = lastHourMB(lines) else { return "演練沒有跑完" }
            return mb <= 20
                ? nil : "記憶體多了 \(Int(mb)) MB，還是太多：丟掉舊的 Tick 時，它的 raw 資料才會跟著釋放"
        }
    )
}
