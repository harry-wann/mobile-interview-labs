// Swift06AsyncStream 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
import Foundation
import LabKit

extension Swift06AsyncStream {
    /// 任何執行緒都能呼叫的計數器
    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        func hit() { lock.withLock { value += 1 } }
        var count: Int { lock.withLock { value } }
    }

    /// 記錄一個會變動的數字，以及它出現過的最大值
    final class Gauge: @unchecked Sendable {
        private let lock = NSLock()
        private var now = 0
        private var maximum = 0
        func set(_ value: Int) {
            lock.withLock {
                now = value; maximum = max(maximum, value)
            }
        }
        var current: Int { lock.withLock { now } }
        var peak: Int { lock.withLock { maximum } }
    }
}
