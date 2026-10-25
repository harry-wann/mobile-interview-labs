// L02：漏收一筆更新，板上留下已經不存在的價位（增量更新沒有檢查序號）
// 對應複習站：Exchange05OrderBook 第 2 題「「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？」
//
// 情境：增量更新（delta）只告訴你「哪些價位變了」，每筆帶遞增的序號 seq。快照在 seq 100，之後依序有 101、102、103、104、105。
//       102 的內容是「賣方 10,010,000 賣光了（數量 0）」，但這一則在網路上漏掉了，App 收到的是 101、103、104、105。
//       （數量 0 代表刪除這一檔，這裡 `E05Depth.apply` 已經處理好。）
//       伺服器 seq 105 的板：最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔。
// Bug 版：收到的增量一律直接套用，不看序號。102 漏掉，賣方 10,010,000×0.1 就永遠留在板上，最佳賣價是一個早就沒有的價位。
// 修正版：套用前檢查 seq 是不是上一筆加 1：小於等於上一筆的忽略；跳號（不是加 1）就不套用，回報 gap，由外面重新抓快照。
import LabKit

private struct Delta {
    let seq: Int
    let bids: [E05Level]
    let asks: [E05Level]
}

private enum ApplyResult {
    case applied
    case stale
    case gap(expected: Int)
}

private protocol DepthScreen: AnyObject {
    var depth: E05Depth { get }
    var seq: Int { get }
    /// 用快照取代整份板（快照代表伺服器在 seq 當下的板）
    func load(snapshot: E05Depth, seq: Int)
    func onDelta(_ delta: Delta) -> ApplyResult
}

/// 演練流程（兩個版本共用）：伺服器一路更新，App 收到除了 102 以外的增量；回報跳號時，立刻重新抓一份最新的快照
private func scenario(_ log: LabLog, screen: DepthScreen) {
    var server = E05Depth()
    server.apply(bids: [E05Level("10000000", "0.4"), E05Level("9990000", "0.6")],
                 asks: [E05Level("10010000", "0.3"), E05Level("10020000", "0.5")])
    screen.load(snapshot: server, seq: 100)
    log("快照 seq 100 → \(screen.depth.summary)")

    let deltas = [
        Delta(seq: 101, bids: [E05Level("10000000", "0.5")], asks: [E05Level("10010000", "0.1")]),
        Delta(seq: 102, bids: [], asks: [E05Level("10010000", "0")]),                          // 這一則在網路上漏掉了
        Delta(seq: 103, bids: [E05Level("9995000", "0.2")], asks: []),
        Delta(seq: 104, bids: [], asks: [E05Level("10030000", "0.4")]),
        Delta(seq: 105, bids: [], asks: [E05Level("10020000", "0.3")]),
    ]
    for delta in deltas {
        server.apply(bids: delta.bids, asks: delta.asks)                                       // 伺服器的板每一筆都更新
        if delta.seq == 102 {
            log("seq 102 在網路上漏掉了，App 沒有收到")
            continue
        }
        switch screen.onDelta(delta) {
        case .applied:
            log("收到 seq \(delta.seq)，套用 → \(screen.depth.summary)")
        case .stale:
            log("收到 seq \(delta.seq)，重複或過期，忽略")
        case .gap(let expected):
            log("跳號：期待 \(expected)，收到 \(delta.seq)，重新抓快照")
            screen.load(snapshot: server, seq: delta.seq)
            log("快照 seq \(delta.seq) → \(screen.depth.summary)")
        }
    }
    log("最後：seq \(screen.seq)，\(screen.depth.summary)（伺服器 seq 105：最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔）")
}

// MARK: - Bug 版
private enum L02Bug {
    final class Screen: DepthScreen {
        private(set) var depth = E05Depth()
        private(set) var seq = 0

        func load(snapshot: E05Depth, seq: Int) {
            depth = snapshot
            self.seq = seq
        }

        func onDelta(_ delta: Delta) -> ApplyResult {
            depth.apply(bids: delta.bids, asks: delta.asks)         // 不看序號，收到就套用
            seq = delta.seq
            return .applied
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: Screen())
    }
}

// MARK: - 修正版
private enum L02Fixed {
    final class Screen: DepthScreen {
        private(set) var depth = E05Depth()
        private(set) var seq = 0

        func load(snapshot: E05Depth, seq: Int) {
            depth = snapshot
            self.seq = seq
        }

        func onDelta(_ delta: Delta) -> ApplyResult {
            if delta.seq <= seq { return .stale }                   // 重複或過期：忽略
            if delta.seq != seq + 1 { return .gap(expected: seq + 1) }   // 跳號：板不可信，不套用，請外面重新抓快照
            depth.apply(bids: delta.bids, asks: delta.asks)
            seq = delta.seq
            return .applied
        }
    }

    static func run(_ log: LabLog) async {
        scenario(log, screen: Screen())
    }
}

// MARK: - 演練設定
extension Exchange05OrderBook {
    static let l02 = Lab(
        id: "L02",
        title: "漏收一筆更新，板上留下不存在的價位",
        question: "第 2 題：「完整快照加增量更新」是什麼？為什麼序號跳號就要重新抓完整資料？",
        goal: "增量更新套用前要檢查序號是不是上一筆加 1。重複的忽略；跳號就不能硬接，要回報 gap，重新抓快照。",
        steps: [
            "選「Bug 版」執行，看漏掉 seq 102 之後，最佳賣價停在哪裡？賣方 10,010,000 在伺服器上早就賣光了。",
            "最後一行和伺服器 seq 105 的板比一比，哪裡不一樣？",
            "選「修正版」執行，要看到「跳號：期待 102，收到 103，重新抓快照」，最後的板和伺服器一致。",
            "進階：跳號之後到快照回來之前，收到的增量該怎麼辦？（暫存、不直接套用，見 Exchange04 L05）",
        ],
        expectBug: "沒有任何跳號訊息；最後「最佳賣 10010000×0.1，買 3 檔，賣 3 檔」，多了一個伺服器上早就賣光的價位。",
        expectFixed: "「跳號：期待 102，收到 103，重新抓快照」，快照回來後再接 104、105，最後「最佳賣 10020000×0.3，買 3 檔，賣 2 檔」，和伺服器一致。",
        run: { variant, log in
            switch variant {
            case .bug: await L02Bug.run(log)
            case .fixed: await L02Fixed.run(log)
            }
        },
        checkBug: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, screen:) 執行" }
            if lines.contains(where: { $0.hasPrefix("跳號") }) { return "Bug 版不檢查序號：onDelta 一律套用並回傳 .applied" }
            return lines.contains(where: { $0.hasPrefix("最後：seq 105，最佳買 10000000×0.5，最佳賣 10010000×0.1，買 3 檔，賣 3 檔") })
                ? nil : "Bug 還沒重現：漏掉 102 之後的 103、104、105 都直接套用，賣方 10,010,000×0.1 會留在板上"
        },
        checkFixed: { lines in
            if !lines.contains(where: { $0.hasPrefix("最後") }) { return "演練沒有跑完：要用 scenario(log, screen:) 執行" }
            if !lines.contains("跳號：期待 102，收到 103，重新抓快照") { return "收到 103 時，上一筆是 101，要回傳 .gap(expected: seq + 1)，不要套用" }
            return lines.contains(where: { $0.hasPrefix("最後：seq 105，最佳買 10000000×0.5，最佳賣 10020000×0.3，買 3 檔，賣 2 檔") })
                ? nil : "跳號之後要重新抓快照；快照之後的 104、105 序號接得上，要正常套用"
        }
    )
}
