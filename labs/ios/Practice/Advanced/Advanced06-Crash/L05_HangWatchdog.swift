// L05：主執行緒卡住了，偵測程式卻在卡完之後才說話（卡住偵測要放在別的執行緒）
// 對應複習站：Advanced06Crash 第 4 題「ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？」（怎麼偵測）
//
// 情境：App 啟動時在主執行緒「同步等設定檔」（演練用 Thread.sleep 0.8 秒代替），主執行緒被佔住，畫面不能操作。
//       團隊想自己做一個卡住偵測：主執行緒超過 0.3 秒沒反應就回報（再大概就會被系統的 watchdog 終止）。
//
// 你要寫：
//   ① Bug 版：偵測放在主執行緒的 Timer 上，每 0.1 秒檢查距離上次 tick 的時間。主執行緒被佔住時它跑不了，卡完才回報。
//   ② 修正版：偵測放在背景執行緒：每 0.1 秒請主執行緒回一聲，超過 0.3 秒沒回就回報，這時主執行緒還在卡住。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Advanced/Advanced06-Crash/L05_HangWatchdog.swift
import Foundation
import LabKit

/// 記下偵測程式的回報（兩個版本共用）
private final class HangProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var stalling = false
    private var stallStart = DispatchTime.now()
    private var firstReport: (afterMs: Int, stillBlocked: Bool)?

    func beginStall() {
        lock.withLock { stalling = true; stallStart = .now() }
    }

    func endStall() {
        lock.withLock { stalling = false }
    }

    /// 偵測程式發現卡住時呼叫。只記第一次
    func reportHang() {
        lock.withLock {
            guard firstReport == nil else { return }
            let ms = Int((DispatchTime.now().uptimeNanoseconds - stallStart.uptimeNanoseconds) / 1_000_000)
            firstReport = (ms, stalling)
        }
    }

    var report: (afterMs: Int, stillBlocked: Bool)? { lock.withLock { firstReport } }
}

/// 演練流程（兩個版本共用）：啟動偵測程式 → 主執行緒被佔住 0.8 秒 → 看偵測程式什麼時候回報
/// startDetector 回傳一個「停止偵測」的函式
private func scenario(_ log: LabLog, startDetector: (HangProbe) async -> (() -> Void)) async {
    let probe = HangProbe()
    let stop = await startDetector(probe)
    try? await Task.sleep(for: .milliseconds(300))          // 先讓偵測程式跑一會兒（主執行緒正常的時候不能誤報）
    log("開始：主執行緒同步等設定檔，被佔住 800 ms")
    await MainActor.run {
        probe.beginStall()
        Thread.sleep(forTimeInterval: 0.8)                  // 在主執行緒同步等：畫面完全沒有反應
        probe.endStall()
    }
    try? await Task.sleep(for: .milliseconds(400))          // 讓偵測程式有時間回報
    stop()
    if let report = probe.report {
        log("偵測到卡住：卡住後 \(report.afterMs) ms 才回報")
        log(report.stillBlocked ? "回報時主執行緒還在卡住：可以馬上抓現場" : "回報時主執行緒已經恢復：來不及知道卡在哪裡")
    } else {
        log("沒有偵測到卡住")
    }
}

// MARK: - Bug 版
private enum L05Bug {
    /// Timer 不能跨執行緒傳遞，包一層讓編譯器放心
    private final class TimerBox: @unchecked Sendable {
        var timer: Timer?
    }

    static func startDetector(_ probe: HangProbe) async -> () -> Void {
        // 偵測程式跑在主執行緒的 run loop 上：它和被偵測的對象在同一條執行緒
        let box = TimerBox()
        // TODO ①-1：在 MainActor 上建一個每 0.1 秒執行一次的 Timer（Timer.scheduledTimer），存進 box.timer。
        //           每次 tick 檢查「距離上一次 tick 超過 0.3 秒」就呼叫 probe.reportHang()，然後記下這次 tick 的時間
        // TODO ①-2：回傳一個停止偵測的函式：在主執行緒呼叫 box.timer?.invalidate()
        return { }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, startDetector: startDetector)
    }
}

// MARK: - 修正版
private enum L05Fixed {
    /// 最後一次收到主執行緒回覆的時間
    private final class Pong: @unchecked Sendable {
        private let lock = NSLock()
        private var last = DispatchTime.now()
        func touch() { lock.withLock { last = .now() } }
        var ageSeconds: Double { lock.withLock { Double(DispatchTime.now().uptimeNanoseconds - last.uptimeNanoseconds) / 1e9 } }
    }

    static func startDetector(_ probe: HangProbe) async -> () -> Void {
        let pong = Pong()
        // TODO ②-1：建一條背景的 Thread，迴圈到它被 cancel 為止（Thread.current.isCancelled），每一圈：
        //           ① DispatchQueue.main.async { pong.touch() }：請主執行緒回一聲  ② Thread.sleep(forTimeInterval: 0.1)
        //           ③ 如果 pong.ageSeconds 超過 0.3，就呼叫 probe.reportHang()
        // TODO ②-2：start() 這條 Thread，並回傳一個停止偵測的函式（呼叫 cancel()）
        _ = (pong, probe)
        return { }
    }

    static func run(_ log: LabLog) async {
        await scenario(log, startDetector: startDetector)
    }
}

// MARK: - 演練設定
extension Advanced06Crash {
    static let l05 = Lab(
        id: "L05",
        title: "卡住偵測要等卡完才說話",
        question: "第 4 題：ANR、watchdog、OOM 是什麼？為什麼它們常常沒有一般的 crash 報告？",
        goal: "偵測「主執行緒卡住」的程式，必須在卡住的當下就回報；它自己不能跑在被偵測的那條執行緒上。 先寫出放在主執行緒上的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Advanced/Advanced06-Crash/L05_HangWatchdog.swift，寫 TODO ①。",
            "選「練習」「Bug 版」執行，看「卡住後幾 ms 才回報」，以及回報時主執行緒是不是還在卡住。",
            "想一想：主執行緒被 Thread.sleep 佔住的時候，主執行緒 run loop 上的 Timer 還會跑嗎？",
            "寫 TODO ②，選「修正版」執行：偵測放在背景執行緒，卡住約 200 到 400 ms 就回報，而且回報時主執行緒還在卡住。",
            "進階：Android 的 ANR 是 5 秒（輸入事件）。App 自己的偵測程式要在這之前回報，才來得及記下卡住的 stack。",
        ],
        expectBug: "約卡住後 800 ms（主執行緒恢復之後）才回報：「回報時主執行緒已經恢復：來不及知道卡在哪裡」。",
        expectFixed: "約卡住後 200 到 400 ms 就回報：「回報時主執行緒還在卡住：可以馬上抓現場」。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            if lines.contains("沒有偵測到卡住") { return "偵測程式沒有回報：每 0.1 秒檢查距離上次 tick 超過 0.3 秒就呼叫 probe.reportHang()" }
            if lines.contains(where: { $0.contains("還在卡住") }) { return "Bug 還沒重現：偵測要放在主執行緒的 Timer 上，卡住期間它跑不了，只能卡完才回報" }
            return nil
        },
        checkFixed: { lines in
            if lines.contains("沒有偵測到卡住") { return "偵測程式沒有回報：背景執行緒每 0.1 秒請主執行緒回一聲，超過 0.3 秒沒回就呼叫 probe.reportHang()" }
            if !lines.contains(where: { $0.contains("還在卡住") }) { return "回報太晚：偵測要放在背景執行緒，主執行緒還在卡住的時候就回報" }
            return nil
        }
    )
}
