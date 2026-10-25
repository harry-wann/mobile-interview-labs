// Advanced02 共用的小工具（演練檔案用到的，不屬於任何一個 bug）
package tw.harry.labs.solution.advanced02jank

/** 從 log 找出以 prefix 開頭的第一行，取出後面接的數字 */
internal fun a02Number(lines: List<String>, prefix: String): Double? {
    val line = lines.firstOrNull { it.startsWith(prefix) } ?: return null
    return line.removePrefix(prefix).takeWhile { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
}
