package tw.harry.labs.kit

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 演練用的 log。任何執行緒都可以寫：`log("訊息")`。
 *
 * 每一行會記下「從開始到現在幾毫秒」與「在哪條執行緒」，
 * 同時印到 Logcat（tag 是 LAB），方便和 Profiler、StrictMode 的輸出對照。
 */
class LabLog(private val echo: Boolean = true) {
    data class Line(
        val id: Long,
        val ms: Long,
        val thread: String,
        val text: String,
    )

    private val lock = Any()
    private val buffer = ArrayList<Line>()
    private var nextId = 0L
    private var start = System.nanoTime()
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    operator fun invoke(text: String) {
        val thread = Thread.currentThread().name
        val line: Line
        synchronized(lock) {
            val ms = (System.nanoTime() - start) / 1_000_000
            line = Line(nextId++, ms, thread, text)
            buffer.add(line)
            if (buffer.size >
                LIMIT
            ) {
                buffer.subList(0, buffer.size - LIMIT).clear()
            }
            _lines.value = buffer.toList()
        }
        if (echo) {
            val message = "+${line.ms}ms [$thread] $text"
            // JVM 單元測試沒有 Logcat，改印到標準輸出
            if (isAndroidRuntime) {
                Log.d(
                    "LAB",
                    message,
                )
            } else {
                println("[LAB] $message")
            }
        }
    }

    /** 目前所有行的文字（自動檢查用） */
    val texts: List<String>
        get() = synchronized(lock) { buffer.map { it.text } }

    /** 記下目前的位置；之後用 [textsSince] 拿這之後寫的行（一次執行的 log） */
    fun mark(): Long = synchronized(lock) { nextId }

    fun textsSince(mark: Long): List<String> = synchronized(lock) {
        buffer.filter {
            it.id >=
                mark
        }.map { it.text }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            start = System.nanoTime()
            _lines.value = emptyList()
        }
    }

    private companion object {
        const val LIMIT = 3000
        val isAndroidRuntime =
            System.getProperty("java.vm.vendor")?.contains("Android") == true ||
                System.getProperty("java.runtime.name")?.contains("Android") ==
                true
    }
}
