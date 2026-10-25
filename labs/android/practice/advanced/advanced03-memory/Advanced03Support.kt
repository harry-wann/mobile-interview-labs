// Advanced03 共用的小工具（演練檔案用到的，不屬於任何一個 bug）
package tw.harry.labs.practice.advanced03memory

import java.lang.ref.WeakReference

/** 從 log 找出以 prefix 開頭的第一行，取出後面接的數字 */
internal fun a03Number(lines: List<String>, prefix: String): Double? {
    val line = lines.firstOrNull { it.startsWith(prefix) } ?: return null
    return line.removePrefix(prefix).takeWhile {
        it.isDigit() || it == '.' ||
            it == '-'
    }.toDoubleOrNull()
}

/**
 * 先 GC 再量：目前 Java heap 用了幾 MB（要看有沒有一直長，GC 之後的數字才準）
 */
internal fun a03UsedHeapMB(): Double {
    val runtime = Runtime.getRuntime()
    repeat(3) { a03ForceGc() }
    return (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0
}

/**
 * 真的做一次 GC。Android 的 System.gc() 只是提示：沒有剛呼叫過 runFinalization() 時，它只記下「之後要做」而不會馬上回收。
 * LeakCanary 的做法是直接呼叫 Runtime.getRuntime().gc()，等一下讓 WeakReference 被清掉，再 runFinalization()。
 */
internal fun a03ForceGc() {
    Runtime.getRuntime().gc()
    Thread.sleep(100)
    @Suppress("DEPRECATION")
    System.runFinalization()
}

/**
 * 像 LeakCanary 一樣檢查有沒有洩漏：最多做 5 次 GC，WeakReference 還拿得到物件就是洩漏。
 * 不能只用 System.gc()：在 Android 上它只是提示。
 */
internal fun a03IsLeaked(ref: WeakReference<*>): Boolean {
    repeat(5) {
        a03ForceGc()
        if (ref.get() == null) return false
    }
    return true
}
