// Swift05Actors 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
import Foundation
import LabKit

extension Swift05Actors {
    /// 量主執行緒被卡住多久：每 10 毫秒在主執行緒上記一次時間，記下最長的間隔。
    /// 主執行緒被重運算佔住時，這個間隔會變得很長（畫面也就卡住那麼久）。
    @MainActor
    final class StallMeter {
        private var timer: Timer?
        private var last = DispatchTime.now()
        private var longestGap = 0.0

        func start() {
            last = DispatchTime.now()
            longestGap = 0
            timer = Timer.scheduledTimer(withTimeInterval: 0.01, repeats: true)
            { [weak self] _ in
                MainActor.assumeIsolated { self?.tick() }
            }
        }

        private func tick() {
            let now = DispatchTime.now()
            longestGap = max(
                longestGap,
                Double(now.uptimeNanoseconds - last.uptimeNanoseconds)
                    / 1_000_000)
            last = now
        }

        /// 停止並回傳主執行緒最長一次被卡住的毫秒數
        func stop() -> Double {
            tick()
            timer?.invalidate()
            return longestGap
        }
    }

    /// 目前是不是在主執行緒（Thread.isMainThread 不能直接寫在 async 函式裡，
    /// 包成同步函式）
    static func onMainThread() -> Bool { Thread.isMainThread }
}
