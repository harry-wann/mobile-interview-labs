// Exchange04Reconnect 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：正確的重連等待時間算法（指數退避加隨機誤差，先封頂再計算）。
import Foundation

enum E04Backoff {
    /// full jitter：等待時間 = 0 到「這次的上限」之間的隨機值；上限 = min(cap,
    /// base × 2 的 attempt 次方)。
    /// 亂數從外面傳進來（0..<1），測試時可以固定。上限一次翻一倍、到 cap 就停，
    /// attempt 再大也不會溢位。
    static func delayMs(
        attempt: Int, baseMs: Int64 = 500, capMs: Int64 = 8000, random: Double
    ) -> Int64 {
        var ceiling = baseMs
        var i = 0
        while i < attempt && ceiling < capMs {
            ceiling = min(ceiling * 2, capMs)
            i += 1
        }
        return Int64(Double(min(ceiling, capMs)) * random)
    }
}
