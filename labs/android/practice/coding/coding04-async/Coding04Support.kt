// Coding04Async 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：數「同時有幾個在進行」的計數器、一個要抓的項目、API 的錯誤種類（哪些值得重試）、記錄等待時間的假 sleep。
package tw.harry.labs.practice.coding04async

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 數同時有幾個請求在進行，並記下最多的時候是幾個（任何執行緒都可以呼叫） */
class C04Meter {
    private val current = AtomicInteger(0)
    private val highest = AtomicInteger(0)

    fun enter() {
        val now = current.incrementAndGet()
        highest.accumulateAndGet(now) { a, b -> maxOf(a, b) }
    }

    fun leave() {
        current.decrementAndGet()
    }

    /** 同時進行的最高紀錄 */
    val peak: Int get() = highest.get()
}

/** 要向 API 抓的一個項目：名稱，以及這個請求要花幾毫秒 */
class C04Job(val name: String, val ms: Int)

/** API 可能回傳的錯誤 */
enum class C04ApiError(val text: String, val isTransient: Boolean) {
    TIMEOUT("逾時", true),
    SERVER_BUSY("伺服器忙碌 503", true),
    RATE_LIMITED("請求太多 429", true),
    BAD_REQUEST("請求有問題 400", false),
    INSUFFICIENT_BALANCE("餘額不足", false),
}

/** API 回傳錯誤時丟出的例外。isTransient 是 true 的才值得重試：等一下再試有機會成功；其他錯誤重試結果都一樣 */
class C04ApiException(val error: C04ApiError) : Exception(error.text)

/** 假的 sleep：不真的等，只記下「被要求等多久」（毫秒） */
class C04SleepLog {
    private val recorded = CopyOnWriteArrayList<Int>()

    @Suppress("RedundantSuspendModifier")
    suspend fun sleep(ms: Int) {
        recorded.add(ms)
    }

    val waits: List<Int> get() = recorded.toList()
}

/** 數被呼叫了幾次 */
class C04Calls {
    private val value = AtomicInteger(0)

    fun next(): Int = value.incrementAndGet()

    val count: Int get() = value.get()
}
