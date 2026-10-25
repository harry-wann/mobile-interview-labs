// Exchange04Reconnect 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：正確的重連等待時間算法（指數退避加隨機誤差，先封頂再計算）。
package tw.harry.labs.practice.exchange04reconnect

object E04Backoff {
    /**
     * full jitter：等待時間 = 0 到「這次的上限」之間的隨機值；上限 = min(cap, base × 2 的 attempt 次方)。
     * 亂數從外面傳進來（0 以上、小於 1），測試時可以固定。上限一次翻一倍、到 cap 就停，attempt 再大也不會溢位。
     */
    fun delayMs(
        attempt: Int,
        baseMs: Long = 500,
        capMs: Long = 8000,
        random: Double,
    ): Long {
        var ceiling = baseMs
        var i = 0
        while (i < attempt && ceiling < capMs) {
            ceiling = minOf(ceiling * 2, capMs)
            i++
        }
        return (minOf(ceiling, capMs) * random).toLong()
    }
}
