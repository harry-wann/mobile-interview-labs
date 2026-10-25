// Design02MarketData 共用的小工具（和 bug 本身無關，已經寫好，不用改）。
// 內容：假的 WebSocket 伺服器（記下訂閱管理者送出的每一則 sub／unsub，推送時依目前的訂閱決定送幾份）、毫秒時鐘。
package tw.harry.labs.solution.design02marketdata

/** 假的 WebSocket 伺服器：訂閱管理者送出的 sub／unsub 都記在這裡 */
class D02FakeServer {
    /** 收到的一則 sub／unsub，連同收到的時間（毫秒；演練編好的時間，不是真實的時鐘） */
    class Frame(val atMs: Int, val text: String)

    private val _frames = mutableListOf<Frame>()
    val frames: List<Frame> get() = _frames

    /** 目前的時間（毫秒）：由演練流程設定 */
    var nowMs = 0

    /** topic → 伺服器收到幾次 sub（同一個 topic 訂兩次，每次推送就會送兩份；unsub 會取消這個 topic 的全部訂閱） */
    private val active = mutableMapOf<String, Int>()

    fun sendSub(topic: String) {
        _frames += Frame(nowMs, "sub $topic")
        active[topic] = (active[topic] ?: 0) + 1
    }

    fun sendUnsub(topic: String) {
        _frames += Frame(nowMs, "unsub $topic")
        active.remove(topic)
    }

    val subCount: Int get() = _frames.count { it.text.startsWith("sub ") }
    val unsubCount: Int get() = _frames.count { it.text.startsWith("unsub ") }
    val activeTopics: List<String> get() = active.keys.sorted()
    fun isSubscribed(topic: String): Boolean = topic in active

    /** 伺服器推送一則這個 topic 的訊息：每有一次 sub，App 就收到一份 */
    fun push(topic: String): Int = active[topic] ?: 0
}

/** 現在的時間（毫秒，單調時鐘） */
fun d02NowMs(): Double = System.nanoTime() / 1_000_000.0
