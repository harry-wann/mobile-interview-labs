// L05：同時丟 60 個「會等待」的任務，執行緒一路暴增（thread explosion）
// 對應複習站：Platform02Threading 第 4 題「iOS 的 GCD 有哪些常見的坑？」
//
// 情境：App 啟動時要預先下載 60 檔幣種的圖示。每個下載都會「等網路」（演練用
// Thread.sleep 0.15 秒代替）。
//        演練記下這 60 個任務一共用了幾條不同的執行緒。
//
// 你要寫：
//   ① Bug 版：把 60 個會等待的下載全部丟進 concurrent queue，執行緒一路暴增。
//   ② 修正版：改用 OperationQueue，maxConcurrentOperationCount 設成 4。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：Solution/Platform/Platform02-Threading/L05_ThreadExplosion.swift
import Foundation
import LabKit

/// 記下用過哪些執行緒（兩個版本共用）
private final class ThreadRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var ids = Set<UInt32>()
    private var completed = 0
    var count: Int { lock.lock(); defer { lock.unlock() }; return ids.count }
    var finished: Int { lock.lock(); defer { lock.unlock() }; return completed }

    /// 假的下載：等 0.15 秒，並記下自己跑在哪條執行緒
    func download() {
        lock.lock()
        ids.insert(pthread_mach_thread_np(pthread_self()))
        lock.unlock()
        Thread.sleep(forTimeInterval: 0.15)
        lock.lock()
        completed += 1
        lock.unlock()
    }
}

/// 演練流程（兩個版本共用）：[start] 把 60 個下載丟出去，全部做完才會呼叫
/// [done]
private func scenario(
    _ log: LabLog,
    start: (ThreadRecorder, @escaping @Sendable () -> Void) -> Void
) async {
    let recorder = ThreadRecorder()
    let began = DispatchTime.now().uptimeNanoseconds
    await withCheckedContinuation { continuation in
        start(recorder) { continuation.resume() }
    }
    let ms = Int((DispatchTime.now().uptimeNanoseconds - began) / 1_000_000)
    log("完成 \(recorder.finished) 個下載，用了 \(recorder.count) 條不同的執行緒（花了 \(ms) ms）")
}

// MARK: - Bug 版
private enum L05Bug {
    static func run(_ log: LabLog) async {
        await scenario(log) { recorder, done in
            let queue = DispatchQueue(label: "icons", attributes: .concurrent)
            let group = DispatchGroup()
            // TODO ①：用迴圈 60 次，每次 queue.async(group: group) { recorder.
            // download() }：60 個會等待的任務一次丟進去
            group.notify(queue: .global()) { done() }
        }
    }
}

// MARK: - 修正版
private enum L05Fixed {
    static func run(_ log: LabLog) async {
        await scenario(log) { recorder, done in
            let queue = OperationQueue()
            // TODO ②-1：限制同一時間最多 4 個（設定 queue.
            // maxConcurrentOperationCount）
            // TODO ②-2：用迴圈 60 次，每次 queue.addOperation { recorder.
            // download() }
            // 前面的全部做完才執行
            queue.addBarrierBlock { done() }
        }
    }
}

// MARK: - 演練設定
private func finished(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("完成 ") }
        .flatMap {
            Int(
                $0.replacingOccurrences(of: "完成 ", with: "").components(
                    separatedBy: " "
                ).first ?? "")
        }
}

private func threads(_ lines: [String]) -> Int? {
    lines.first { $0.hasPrefix("完成 ") }
        .flatMap { $0.components(separatedBy: "用了 ").last }
        .flatMap { Int($0.components(separatedBy: " ").first ?? "") }
}

extension Platform02Threading {
    static let l05 = Lab(
        id: "L05",
        title: "同時丟 60 個等待的任務，執行緒暴增",
        question: "第 4 題：iOS 的 GCD 有哪些常見的坑？",
        goal:
            "會等待的任務不要一次全丟進 concurrent queue。限制同時執行的數量，執行緒才不會暴增。先寫出執行緒暴增的 Bug 版，再寫修正版。",
        steps: [
            "打開 Practice/Platform/Platform02-Threading/L05_ThreadExplosion.swift，寫 TODO ①（Bug 版）。",
            "選「練習」「Bug 版」按執行，看「用了幾條不同的執行緒」。",
            "寫 TODO ②（修正版），選「修正版」執行：用了幾條執行緒？花的時間比較久，為什麼？（一次只做 4 個，其他排隊，這是刻意的取捨）",
            "進階：Bug 版執行時，在 Xcode 的 Debug navigator 看 CPU 頁籤下方的執行緒數量；或用 Instruments 的 System Trace。",
        ],
        expectBug: "60 個下載用了幾十條不同的執行緒（實測 60 條；每次執行會不同），全部完成只要約 0.2 秒。",
        expectFixed:
            "只用了不到 10 條不同的執行緒（實測 5 到 9 條），全部完成約 2.4 秒（60 ÷ 4 × 0.15 秒）。",
        run: { variant, log in
            switch variant {
            case .bug: await L05Bug.run(log)
            case .fixed: await L05Fixed.run(log)
            }
        },
        checkBug: { lines in
            guard let n = threads(lines), let done = finished(lines) else {
                return "演練沒有跑完"
            }
            if done != 60 {
                return
                    "只完成了 \(done) 個下載：要把 60 個 recorder.download() 都丟進 concurrent queue"
            }
            return n >= 16
                ? nil
                : "Bug 還沒重現（只用了 \(n) 條執行緒）：要把 60 個 recorder.download() 全丟進 concurrent queue，而且不限制數量"
        },
        checkFixed: { lines in
            guard let n = threads(lines), let done = finished(lines) else {
                return "演練沒有跑完"
            }
            if done != 60 {
                return
                    "只完成了 \(done) 個下載：要把 60 個 recorder.download() 都加進 OperationQueue"
            }
            return n <= 15
                ? nil
                : "用了 \(n) 條執行緒：用 OperationQueue 並把 maxConcurrentOperationCount 設成 4"
        }
    )
}
