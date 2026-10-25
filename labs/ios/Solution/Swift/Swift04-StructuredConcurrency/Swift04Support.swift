// Swift04StructuredConcurrency 共用的小工具（和 bug 本身無關，已經寫好，
// 不用改）。
import Foundation
import LabKit

extension Swift04StructuredConcurrency {
    /// 任何執行緒都能呼叫的計數器
    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0
        func hit() { lock.withLock { value += 1 } }
        var count: Int { lock.withLock { value } }
    }

    /// 記錄「同時進行」的最大數量：開始一件事呼叫 enter()，結束呼叫 leave()
    final class ConcurrencyMeter: @unchecked Sendable {
        private let lock = NSLock()
        private var current = 0
        private var maximum = 0
        func enter() {
            lock.withLock {
                current += 1; maximum = max(maximum, current)
            }
        }
        func leave() { lock.withLock { current -= 1 } }
        var peak: Int { lock.withLock { maximum } }
    }
}
