import Foundation
import Combine

/// 演練用的 log。任何執行緒都可以寫：`log("訊息")`。
///
/// 每一行會記下「從開始到現在幾毫秒」與「在哪條執行緒」，
/// 同時印到 Xcode 的主控台（前綴 `[LAB]`），方便和 Instruments、TSan
/// 的輸出對照。
public final class LabLog: ObservableObject, @unchecked Sendable {
    public struct Line: Identifiable, Sendable {
        public let id: Int
        public let ms: Int
        public let thread: String
        public let text: String
    }

    @Published public private(set) var lines: [Line] = []

    private let lock = NSLock()
    private var buffer: [Line] = []
    private var nextID = 0
    private var start = DispatchTime.now()
    private var publishScheduled = false
    private let echo: Bool
    private let limit = 3000

    public init(echo: Bool = true) {
        self.echo = echo
    }

    public func callAsFunction(_ text: String) {
        let thread = LabLog.currentThreadLabel()
        lock.lock()
        let ms = Int(
            (DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds)
                / 1_000_000)
        let line = Line(id: nextID, ms: ms, thread: thread, text: text)
        nextID += 1
        buffer.append(line)
        if buffer.count > limit { buffer.removeFirst(buffer.count - limit) }
        let needSchedule = !publishScheduled
        publishScheduled = true
        lock.unlock()

        if echo { print("[LAB] +\(ms)ms [\(thread)] \(text)") }
        // 很多執行緒同時寫的時候，合併成一次畫面更新
        if needSchedule {
            DispatchQueue.main.async { [weak self] in self?.publish() }
        }
    }

    /// 目前所有行的文字（自動檢查用）
    public var texts: [String] {
        lock.lock()
        defer { lock.unlock() }
        return buffer.map(\.text)
    }

    /// 記下目前的位置；之後用 `texts(since:)` 拿這之後寫的行（一次執行的 log）
    public func mark() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return nextID
    }

    public func texts(since mark: Int) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return buffer.filter { $0.id >= mark }.map(\.text)
    }

    public func clear() {
        lock.lock()
        buffer.removeAll()
        start = DispatchTime.now()
        let needSchedule = !publishScheduled
        publishScheduled = true
        lock.unlock()
        if needSchedule {
            DispatchQueue.main.async { [weak self] in self?.publish() }
        }
    }

    private func publish() {
        lock.lock()
        let snapshot = buffer
        publishScheduled = false
        lock.unlock()
        lines = snapshot
    }

    /// 主執行緒顯示 `main`，其他顯示 `t` 加上系統的執行緒編號
    public static func currentThreadLabel() -> String {
        if pthread_main_np() != 0 { return "main" }
        return "t\(pthread_mach_thread_np(pthread_self()))"
    }
}
