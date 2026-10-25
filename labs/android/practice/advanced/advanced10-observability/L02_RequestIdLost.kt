// L02：同時處理三個請求，log 上的 request id 卻對不上自己的請求（coroutine 換執行緒時 id 遺失、被別的請求蓋掉）
// 對應複習站：Advanced10Observability 第 1 題「可觀測性是什麼？log、指標、追蹤各回答什麼問題？」（每筆 log 都帶關聯 ID）
//
// 情境：每個請求進來時，App 要替它記一個 request id，之後這個請求相關的所有 log 都帶著這個 id，才能把一次操作從 App 串到伺服器
//       （就像 Timber 或 MDC 的做法）。演練同時處理三個請求（req-1、req-2、req-3），每個請求「開始 → 等回應 → 收到回應 → 切到 IO 執行緒寫入」，
//       每一行 log 前面印出「logger 認為現在是哪個 request id」，後面寫這一行真正屬於哪個請求，兩邊對得上才正確。
//
// 你要寫：
//   ① Bug 版：request id 放在 ThreadLocal。ThreadLocal 綁的是執行緒，不是請求：交錯執行會被蓋掉，切到別條執行緒就不見。
//   ② 修正版：用 ThreadLocal.asContextElement(id) 把值放進 CoroutineContext，coroutine 在哪條執行緒恢復都讀得到自己的 id。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/advanced/advanced10-observability/L02_RequestIdLost.kt
package tw.harry.labs.practice.advanced10observability

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

private val requestIdLocal = ThreadLocal<String>()

/** 收集 logger 的輸出與對不上的行數（兩個版本共用） */
private class L02Collector {
    val lines = CopyOnWriteArrayList<String>()
    val mismatches = AtomicInteger(0)

    fun emit(currentId: String, owner: String, message: String) {
        lines += "[$currentId] $owner 的$message"
        if (currentId != owner) mismatches.incrementAndGet()
    }
}

/** 演練流程（兩個版本共用）：withRequest 讓 body 在「這個請求的環境」裡執行；currentId 讀目前的 request id */
private suspend fun scenario(
    log: LabLog,
    withRequest: suspend (String, suspend () -> Unit) -> Unit,
    currentId: () -> String,
) {
    val collector = L02Collector()

    suspend fun handle(owner: String, startDelayMs: Long, waitMs: Long) {
        delay(startDelayMs)
        withRequest(owner) {
            collector.emit(currentId(), owner, "開始")
            delay(waitMs)                                                   // 等伺服器回應
            collector.emit(currentId(), owner, "收到回應")
            withContext(Dispatchers.IO) { collector.emit(currentId(), owner, "寫入資料庫") }   // 切到 IO 執行緒
        }
    }

    coroutineScope {
        listOf(
            async { handle("req-1", 0, 200) },
            async { handle("req-2", 30, 120) },
            async { handle("req-3", 60, 160) },
        ).awaitAll()
    }
    collector.lines.forEach { log(it) }
    log("共 ${collector.lines.size} 行 log，request id 對不上的有 ${collector.mismatches.get()} 行")
}

// MARK: - Bug 版
private object L02Bug {
    suspend fun run(log: LabLog) = scenario(
        log,
        withRequest = { id, body ->
            // TODO ①：把 id 存進 ThreadLocal（requestIdLocal.set(id)，之後的 log 都讀它），再執行 body()
            body()
        },
        currentId = { requestIdLocal.get() ?: "—" },
    )
}

// MARK: - 修正版
private object L02Fixed {
    suspend fun run(log: LabLog) = scenario(
        log,
        withRequest = { id, body ->
            // TODO ②：用 withContext(requestIdLocal.asContextElement(id)) { … } 把 body() 包起來執行（值綁在 coroutine 的 context 上）
            body()
        },
        currentId = { requestIdLocal.get() ?: "—" },
    )
}

// MARK: - 演練設定
val l02 = Lab(
    id = "L02",
    title = "三個請求同時跑，log 的 request id 對不上",
    question = "第 1 題：可觀測性是什麼？log、指標、追蹤各回答什麼問題？（每筆 log 都帶關聯 ID）",
    goal = "request id 要跟著「這個請求的 coroutine」走，delay 之後、切換執行緒之後都讀得到自己的 id，不能只放在 ThreadLocal。 先寫出放在 ThreadLocal 的 Bug 版，再寫修正版。",
    steps = listOf(
        "打開 practice/advanced/advanced10-observability/L02_RequestIdLost.kt，寫 TODO ①。",
        "選「練習」「Bug 版」執行，看每一行前面的 [id] 和後面的「req-N 的…」是不是同一個。",
        "想一想：三個請求交錯執行時，ThreadLocal 被誰設定？切到 Dispatchers.IO 的執行緒之後，那裡的 ThreadLocal 有值嗎？",
        "寫 TODO ②，選「修正版」執行：用 asContextElement 把 request id 綁在 CoroutineContext 上。",
        "進階：iOS 用 @TaskLocal 做同樣的事；跨 App 與伺服器則是把 id 放進 X-Request-ID 標頭（用 OkHttp 的 Interceptor 加）。",
    ),
    expectBug = "多行的 [id] 和後面的請求對不上（例如「[—] req-1 的寫入資料庫」：切到 IO 執行緒之後 id 不見），「request id 對不上的有」多行。",
    expectFixed = "每一行都對得上：「共 9 行 log，request id 對不上的有 0 行」。",
    file = "advanced10-observability/L02_RequestIdLost.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L02Bug.run(log)
            Variant.FIXED -> L02Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("共 ") && "request id 對不上的有" in it } -> "演練沒有跑完：要用 scenario 執行"
            (1..3).any { "[req-$it] req-$it 的開始" !in lines } -> "請求一開始就要把 request id 設定好：「開始」那一行的 [id] 應該是自己的 id"
            lines.any { it.endsWith("對不上的有 0 行") } -> "Bug 還沒重現：request id 要放在 ThreadLocal，請求開始時 set，後來的請求會把它蓋掉、換執行緒也讀不到"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("共 ") && "request id 對不上的有" in it } -> "演練沒有跑完：要用 scenario 執行"
            "共 9 行 log，request id 對不上的有 0 行" !in lines -> "還有對不上的行：要用 requestIdLocal.asContextElement(id) 放進 withContext，包住整個請求的處理"
            else -> null
        }
    },
)
