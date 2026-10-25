// L06：記憶體不足的次數看起來是 crash 的好幾倍（背景被系統回收不算 OOM）
// 對應複習站：Advanced06Crash 第 4 題「ANR、watchdog、OOM
// 是什麼？為什麼它們常常沒有一般的 crash 報告？」
// （自己判斷「上次是不是被記憶體上限終止」）
//
// 情境：iOS 的 OOM（記憶體不足被 jetsam 終止）沒有 crash 報告，只能用推測：
// 每次啟動時，檢查「上一次是不是不正常結束」。
//       App 會在生命週期裡記下狀態（進入前景、進入背景、正常結束），以及
// crash、版本更新、系統更新、重新開機、有沒有接 debugger。
//       演練有 12 次啟動的紀錄，其中真正在前景被記憶體上限終止的只有 2 次。
// Bug 版：只要「上一次沒有正常結束」就算 OOM。但 App
// 在背景被系統回收（本來就是正常的）、crash、剛升級、重新開機、接著 debugger，
//        也都不會走到「正常結束」，通通被算成 OOM，數字膨脹好幾倍。
// 修正版：先排除 crash、版本更新、系統更新、重新開機、接著 debugger，
// 並且只算「上一次最後的狀態是前景」的（背景被回收是系統的正常行為）。
import Foundation
import LabKit

private enum LastState { case foreground, background }

/// 啟動時讀到的「上一次」紀錄（兩個版本共用）
private struct LaunchRecord {
    let note: String
    // 最後一次記下的狀態：didBecomeActive → 前景；didEnterBackground → 背景
    let lastState: LastState
    // applicationWillTerminate 有被呼叫
    var cleanExit = false
    var crashed = false  // 有 crash 報告
    // 這次啟動是版本更新後的第一次
    var appUpdated = false
    // 系統升級後的第一次
    var osUpdated = false
    var rebooted = false  // 裝置重新開機過
    // 開發時接著 debugger
    var debuggerAttached = false
}

private let history: [LaunchRecord] = [
    LaunchRecord(note: "背景被系統回收", lastState: .background),
    LaunchRecord(note: "背景被系統回收", lastState: .background),
    LaunchRecord(note: "背景被系統回收", lastState: .background),
    LaunchRecord(note: "從多工畫面滑掉", lastState: .background),
    LaunchRecord(note: "正常結束", lastState: .background, cleanExit: true),
    LaunchRecord(note: "crash", lastState: .foreground, crashed: true),
    LaunchRecord(note: "版本更新", lastState: .foreground, appUpdated: true),
    LaunchRecord(note: "系統升級", lastState: .foreground, osUpdated: true),
    LaunchRecord(note: "重新開機", lastState: .foreground, rebooted: true),
    LaunchRecord(
        note: "接著 debugger", lastState: .foreground, debuggerAttached: true),
    LaunchRecord(note: "前景被記憶體上限終止", lastState: .foreground),
    LaunchRecord(note: "前景被記憶體上限終止", lastState: .foreground),
]

/// 演練流程（兩個版本共用）
private func scenario(_ log: LabLog, isOOM: (LaunchRecord) -> Bool) {
    let flagged = history.filter(isOOM)
    log("共 \(history.count) 次啟動，判定為前景記憶體不足被終止：\(flagged.count) 次")
    let wrong = flagged.filter { $0.note != "前景被記憶體上限終止" }.map(\.note)
    if !wrong.isEmpty { log("其中誤判：\(wrong.joined(separator: "、"))") }
}

// MARK: - Bug 版
private enum L06Bug {
    static func isOOM(_ record: LaunchRecord) -> Bool {
        // 上次沒有正常結束，就當作記憶體不足
        !record.cleanExit
    }

    static func run(_ log: LabLog) async {
        scenario(log, isOOM: isOOM)
    }
}

// MARK: - 修正版
private enum L06Fixed {
    static func isOOM(_ record: LaunchRecord) -> Bool {
        // 正常結束、已經有 crash 報告
        if record.cleanExit || record.crashed { return false }
        // 更新、重新開機造成的中斷
        if record.appUpdated || record.osUpdated || record.rebooted {
            return false
        }
        // 開發時被 Xcode 停掉
        if record.debuggerAttached { return false }
        // 背景被回收是正常的，只算前景
        return record.lastState == .foreground
    }

    static func run(_ log: LabLog) async {
        scenario(log, isOOM: isOOM)
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l06 = Lab(
        id: "L06",
        title: "記憶體不足的次數比 crash 還多好幾倍",
        question: "第 4 題：ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？",
        goal: "用「上次有沒有正常結束」推測 OOM 時，要排除不是記憶體造成的中斷，也不能把背景被回收算進去。",
        steps: [
            "選「Bug 版」執行，看判定了幾次 OOM，以及「其中誤判」列了哪些情況。",
            "想一想：iOS 把進入背景的 App 回收，需要通知 App 嗎？使用者看得到嗎？它算「前景 OOM」嗎？",
            "選「修正版」執行，確認只剩下 2 次真正的前景 OOM。",
            "進階：iOS 14 以上也可以用 MetricKit 的 MXAppExitMetric 看系統彙整過的退出次數（前景 OOM 與背景被回收分開統計）。",
        ],
        expectBug: "「共 12 次啟動，判定為前景記憶體不足被終止：11 次」，誤判列出背景回收、crash、版本更新等。",
        expectFixed: "「共 12 次啟動，判定為前景記憶體不足被終止：2 次」，沒有誤判。",
        run: { variant, log in
            switch variant {
            case .bug: await L06Bug.run(log)
            case .fixed: await L06Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard lines.contains(where: { $0.hasPrefix("共 12 次啟動") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if !lines.contains("共 12 次啟動，判定為前景記憶體不足被終止：11 次") {
                return "Bug 還沒重現：只要上次沒有正常結束（cleanExit 是 false）就算 OOM，會判定 11 次"
            }
            return nil
        },
        checkFixed: { lines in
            guard lines.contains(where: { $0.hasPrefix("共 12 次啟動") }) else {
                return "演練沒有跑完：要用 scenario 執行"
            }
            if let wrong = lines.first(where: { $0.hasPrefix("其中誤判") }) {
                return "還有誤判：\(wrong)"
            }
            if !lines.contains("共 12 次啟動，判定為前景記憶體不足被終止：2 次") {
                return
                    "應該只有 2 次前景 OOM：要排除 crash、更新、重新開機、debugger，而且只算上次最後狀態是前景的"
            }
            return nil
        }
    )
}
