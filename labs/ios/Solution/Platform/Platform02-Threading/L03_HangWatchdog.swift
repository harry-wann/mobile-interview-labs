// L03：按下「重新整理資產」，畫面停了 0.4 秒（hang 偵測）
// 對應複習站：Platform02Threading 第 3 題「ANR 是什麼？iOS 的卡頓怎麼偵測？」
//
// 情境：資產頁的「重新整理」要呼叫伺服器取得資產（演練用 0.4 秒代替網路時間）。
// App 裡有一個 HangWatchdog：
//        背景執行緒每 20 ms 叫主執行緒回一聲，主執行緒超過 250 ms 沒回，
// 就記一次 hang（iOS 對 hang 的定義：主執行緒對操作沒有反應超過約 250 ms）。
//        Xcode Organizer 與 MetricKit 在上線後統計的 hang，就是這樣算出來的。
// Bug 版：在按鈕的處理函式裡「同步」等伺服器回應（Thread.sleep
// 代替同步網路呼叫）。主執行緒整個停住 0.4 秒。
// 修正版：改成非同步：開一個 Task，用 await 等待。等待的時候主執行緒是空的，
// 可以處理觸控與畫面。
import Foundation
import LabKit

/// 主執行緒卡頓偵測（兩個版本共用）
private final class HangWatchdog: @unchecked Sendable {
    private let lock = NSLock()
    private var running = true
    private var hangs: [Int] = []

    func start(thresholdMs: Int = 250) {
        Thread.detachNewThread { [self] in
            while isRunning {
                let pingedAt = DispatchTime.now().uptimeNanoseconds
                let reply = DispatchSemaphore(value: 0)
                // 叫主執行緒回一聲
                DispatchQueue.main.async { reply.signal() }
                reply.wait()
                let ms = Int(
                    (DispatchTime.now().uptimeNanoseconds - pingedAt)
                        / 1_000_000)
                if ms > thresholdMs { record(ms) }
                Thread.sleep(forTimeInterval: 0.02)
            }
        }
    }

    func stop() -> [Int] {
        lock.lock()
        running = false
        let result = hangs
        lock.unlock()
        return result
    }

    private var isRunning: Bool {
        lock.lock()
        defer { lock.unlock() }
        return running
    }

    private func record(_ ms: Int) {
        lock.lock()
        hangs.append(ms)
        lock.unlock()
    }
}

/// 演練流程（兩個版本共用）：開著 watchdog，按下重新整理，等它做完，看
/// watchdog 記到幾次 hang
@MainActor
private func scenario(
    _ log: LabLog,
    tapRefresh:
        @escaping @MainActor (_ done: @escaping @MainActor () -> Void) -> Void
) async {
    let watchdog = HangWatchdog()
    watchdog.start()
    try? await Task.sleep(for: .milliseconds(100))
    var finished = false
    let began = DispatchTime.now().uptimeNanoseconds
    // 按下「重新整理資產」
    tapRefresh { finished = true }
    var waited = 0
    // 最多等 2 秒，避免沒寫完的時候卡住
    while !finished && waited < 200 {
        try? await Task.sleep(for: .milliseconds(10))
        waited += 1
    }
    let loadMs = Int((DispatchTime.now().uptimeNanoseconds - began) / 1_000_000)
    try? await Task.sleep(for: .milliseconds(100))
    let hangs = watchdog.stop()
    log("資產載入花了 \(loadMs) ms")
    log(
        "watchdog 記到 \(hangs.count) 次 hang（超過 250 ms）\(hangs.isEmpty ? "" : "，最長 \(hangs.max() ?? 0) ms")"
    )
}

// MARK: - Bug 版
private enum L03Bug {
    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { done in
            // 同步等伺服器：主執行緒整個停住
            Thread.sleep(forTimeInterval: 0.4)
            done()
        }
    }
}

// MARK: - 修正版
private enum L03Fixed {
    @MainActor static func run(_ log: LabLog) async {
        await scenario(log) { done in
            Task {
                // 非同步等伺服器：等的時候主執行緒是空的
                try? await Task.sleep(for: .milliseconds(400))
                done()
            }
        }
    }
}

// MARK: - 演練設定
private func hangCount(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("watchdog 記到 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "watchdog 記到 ", with: "")
                    .components(separatedBy: " ").first ?? "")
        }
}

private func loadMs(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("資產載入花了 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "資產載入花了 ", with: "")
                    .replacingOccurrences(of: " ms", with: ""))
        }
}

extension Platform02Threading {
    static let l03 = Lab(
        id: "L03",
        title: "按下重新整理，畫面停了 0.4 秒",
        question: "第 3 題：ANR 是什麼？iOS 的卡頓怎麼偵測？",
        goal: "耗時的等待不能卡住主執行緒。用 hang 偵測確認：主執行緒不該有超過 250 ms 沒回應的時候。",
        steps: [
            "選「Bug 版」按執行，看 watchdog 記到幾次 hang、最長幾 ms。",
            "選「修正版」執行：改成 Task 加 await，watchdog 沒有記到 hang。",
            "想一想：這個 watchdog 看得到「卡住」，但看不到「卡在哪一行」。要找原因用 Instruments 的 Time Profiler 或 Hangs。",
        ],
        expectBug: "資產載入花了約 400 ms，watchdog 記到 1 次 hang，最長約 400 ms。",
        expectFixed: "資產載入一樣花約 400 ms，但 watchdog 記到 0 次 hang。",
        run: { variant, log in
            switch variant {
            case .bug: await L03Bug.run(log)
            case .fixed: await L03Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = hangCount(lines), let ms = loadMs(lines) else {
                return "演練沒有跑完"
            }
            if ms < 350 {
                return
                    "資產載入只花了 \(ms) ms：要用 Thread.sleep(forTimeInterval: 0.4) 代替同步的網路呼叫"
            }
            return n >= 1
                ? nil
                : "Bug 還沒重現：要在主執行緒用 Thread.sleep(forTimeInterval: 0.4) 同步等待"
        },
        checkFixed: { lines in
            guard let n = hangCount(lines), let ms = loadMs(lines) else {
                return "演練沒有跑完"
            }
            if ms < 350 {
                return
                    "資產載入只花了 \(ms) ms：要在 Task 裡 await Task.sleep(for: .milliseconds(400)) 代替非同步的網路呼叫"
            }
            return n == 0
                ? nil
                : "還是記到 \(n) 次 hang：不要在主執行緒 sleep，改用 Task 加 await Task.sleep"
        }
    )
}
