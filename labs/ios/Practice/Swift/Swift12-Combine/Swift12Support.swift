// Swift12Combine 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
import Foundation
import LabKit

extension Swift12Combine {
    /// 任何執行緒都能呼叫的計數器
    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        func hit() {
            lock.withLock {
                value += 1
            }
        }

        /// 加一，並回傳加完之後的值（第幾次）
        func next() -> Int {
            lock.withLock {
                value += 1
                return value
            }
        }

        var count: Int {
            lock.withLock { value }
        }
    }

    /// 任何執行緒都能讀寫的一個值（記下畫面顯示什麼、在哪條執行緒更新）
    final class Box<Value>: @unchecked Sendable {
        private let lock = NSLock()
        private var stored: Value

        init(_ value: Value) {
            stored = value
        }

        var value: Value {
            get {
                lock.withLock { stored }
            }
            set {
                lock.withLock {
                    stored = newValue
                }
            }
        }
    }

    /// 每 10 毫秒檢查一次，條件成立或超過 timeoutMs 就回來；
    /// 不要固定睡一段時間，整批檢查一起跑時機器忙會不夠
    static func waitUntil(
        timeoutMs: Int = 3_000, _ condition: () -> Bool
    ) async {
        var waited = 0
        while !condition() && waited < timeoutMs {
            try? await Task.sleep(for: .milliseconds(10))
            waited += 10
        }
    }
}
