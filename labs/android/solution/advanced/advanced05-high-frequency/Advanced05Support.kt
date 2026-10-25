// Advanced05 共用的小工具（演練檔案用到的，不屬於任何一個 bug）
package tw.harry.labs.solution.advanced05highfrequency

/** 從 log 找出以 prefix 開頭的第一行，取出後面接的數字 */
internal fun a05Number(lines: List<String>, prefix: String): Double? {
    val line = lines.firstOrNull { it.startsWith(prefix) } ?: return null
    return line.removePrefix(prefix).takeWhile { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
}

/** 單調時鐘（毫秒） */
internal fun a05NowMs(): Double = System.nanoTime() / 1_000_000.0

/** 一筆行情：交易對與價格。交易對的型別用泛型，演練 5 要用「會數比較次數」的型別來量演算法 */
internal data class A05Tick<S>(val symbol: S, val price: Int)
