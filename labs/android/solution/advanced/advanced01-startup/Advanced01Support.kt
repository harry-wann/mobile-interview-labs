// Advanced01 共用的小工具（演練檔案用到的，不屬於任何一個 bug）
package tw.harry.labs.solution.advanced01startup

/** 單調時鐘（毫秒）：量啟動時間用 */
internal fun a01NowMs(): Double = System.nanoTime() / 1_000_000.0

/** 模擬「這件事要花 ms 毫秒」：讓目前這條執行緒停住（和主執行緒上同步做事一樣會擋住後面的工作） */
internal fun a01Work(ms: Long) = Thread.sleep(ms)

/** 從 log 找出以 prefix 開頭的第一行，取出後面接的數字 */
internal fun a01Number(lines: List<String>, prefix: String): Double? {
    val line = lines.firstOrNull { it.startsWith(prefix) } ?: return null
    return line.removePrefix(prefix).takeWhile { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
}
