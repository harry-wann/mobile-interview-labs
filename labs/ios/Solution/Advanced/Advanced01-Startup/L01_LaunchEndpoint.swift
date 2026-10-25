// L01：後台顯示啟動只要 20 毫秒，
// 使用者卻等了將近半秒才看到行情（啟動時間要量到「看見行情」）
// 對應複習站：Advanced01Startup 第 2 題「「打開 App 到看見行情」
// 要量到哪一個時間點？」
//
// 情境：App 啟動後先在約 20 毫秒內畫出骨架畫面（首幀，launch screen 換成 App
// 自己的畫面），
//        再向伺服器要行情（約 450 毫秒），行情畫出來，使用者才真的能看價格、
// 下單。
//        啟動時間用 OSSignposter 的 interval 記錄（Instruments 的 Points of
// Interest 看得到這一段），結束時回報給後台。
// Bug 版：在首幀畫出來時就結束 interval，回報約 20 毫秒。數字很漂亮，
// 使用者卻還在看灰色骨架。
// 修正版：在行情第一次畫出來時才結束 interval，回報約 470 毫秒；
// 首幀的時間另外記，兩個數字分開看。
import Foundation
import os
import LabKit

/// 啟動時間記錄：begin 放在啟動流程最前面，end
/// 由呼叫端決定放在哪個時間點（兩個版本共用）
private final class LaunchTrace {
    private let signposter = OSSignposter(
        subsystem: "tw.harry.MobileLabs", category: "launch")
    private var state: OSSignpostIntervalState?
    private var startMs = 0.0
    private(set) var reportedMs: Double?

    func begin() {
        startMs = A01Clock.nowMs()
        state = signposter.beginInterval("LaunchToMarketList")
    }

    /// 結束 interval，並把這一刻距離 begin 多久記下來（只有第一次呼叫有效）
    func end() {
        guard let state, reportedMs == nil else { return }
        signposter.endInterval("LaunchToMarketList", state)
        reportedMs = A01Clock.nowMs() - startMs
    }
}

/// 啟動流程（兩個版本共用）：骨架畫面 → 向伺服器要行情 → 行情畫出來。
/// onFirstFrame、onPricesShown 是兩個該不該結束計時的時間點
private func simulateLaunch(
    _ log: LabLog,
    trace: LaunchTrace,
    onFirstFrame: () -> Void,
    onPricesShown: () -> Void
) {
    trace.begin()
    let t0 = A01Clock.nowMs()
    a01Work(20)  // 建立骨架畫面
    log("首幀（骨架畫面）出現：\(Int(A01Clock.nowMs() - t0)) ms")
    onFirstFrame()
    // 向伺服器要行情（網路）
    a01Work(450)
    log("使用者看到行情：\(Int(A01Clock.nowMs() - t0)) ms")
    onPricesShown()
    log("回報給後台的啟動時間：\(Int(trace.reportedMs ?? -1)) ms")
}

// MARK: - Bug 版
private enum L01Bug {
    static func run(_ log: LabLog) async {
        await Task.detached {
            let trace = LaunchTrace()
            simulateLaunch(
                log, trace: trace,
                // 骨架畫面一出現就結束計時
                onFirstFrame: { trace.end() },
                onPricesShown: {})
        }.value
    }
}

// MARK: - 修正版
private enum L01Fixed {
    static func run(_ log: LabLog) async {
        await Task.detached {
            let trace = LaunchTrace()
            simulateLaunch(
                log, trace: trace,
                // 首幀只是中間的一個時間點
                onFirstFrame: {},
                // 行情畫出來，使用者才真的能用，這一刻才結束計時
                onPricesShown: { trace.end() })
        }.value
    }
}

// MARK: - 演練設定
extension Advanced01Startup {
    static let l01 = Lab(
        id: "L01",
        title: "後台說啟動 20 毫秒，使用者等了半秒才看到行情",
        question: "第 2 題：「打開 App 到看見行情」要量到哪一個時間點？",
        goal: "啟動時間要量到使用者看見行情，而不是第一個畫面。把「結束計時」放在對的時間點，回報的數字才代表使用者的等待。",
        steps: [
            "選「Bug 版」執行，比較三行 log：首幀出現的時間、使用者看到行情的時間，和回報給後台的啟動時間。",
            "選「修正版」執行：回報的數字會和「使用者看到行情」的時間幾乎一樣。",
            "進階：Xcode 的 Product › Profile 開 Instruments，選 Logging 或 Points of Interest 模板錄製，執行後可以看到 LaunchToMarketList 這一段 interval 從哪裡畫到哪裡。",
            "想一想：Android 的 TTID 和 TTFD 分別對應 Bug 版和修正版的哪一個時間點？（見這個單元的第 2 題）",
        ],
        expectBug: "首幀約 20 ms、使用者看到行情約 470 ms，回報給後台的卻是約 20 ms：差了二十幾倍。",
        expectFixed: "首幀約 20 ms、使用者看到行情約 470 ms，回報給後台的也是約 470 ms。",
        run: { variant, log in
            switch variant {
            case .bug: await L01Bug.run(log)
            case .fixed: await L01Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let reported = a01Number(in: lines, prefix: "回報給後台的啟動時間："),
                let shown = a01Number(in: lines, prefix: "使用者看到行情：")
            else { return "演練沒有跑完" }
            if reported < 0 { return "沒有回報啟動時間：要在某個時間點呼叫 trace.end()" }
            return reported < 150 && shown >= 300
                ? nil
                : "回報了 \(Int(reported)) ms：Bug 版要在首幀出現時就結束計時（onFirstFrame），數字才會比使用者實際等的短很多"
        },
        checkFixed: { lines in
            guard let reported = a01Number(in: lines, prefix: "回報給後台的啟動時間："),
                let shown = a01Number(in: lines, prefix: "使用者看到行情：")
            else { return "演練沒有跑完" }
            if reported < 0 { return "沒有回報啟動時間：要在某個時間點呼叫 trace.end()" }
            return reported >= shown - 5
                ? nil
                : "回報了 \(Int(reported)) ms，比使用者實際等的 \(Int(shown)) ms 短：要等行情畫出來（onPricesShown）才結束計時"
        }
    )
}
