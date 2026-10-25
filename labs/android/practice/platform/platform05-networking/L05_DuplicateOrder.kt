// L05：下單逾時後自動重送，結果買了兩次（clientOrderId 與先查詢再重送）
// 對應複習站：Platform05Networking 第 5 題「網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？」
//
// 情境：使用者按下「買入」，App 送出 POST /orders。假伺服器收到訂單、記下來了，但回應沒有在時限內回到 App（逾時）。
//        假伺服器是 OkHttp 的最後一個 Interceptor，不碰網路：有帶 X-Client-Order-Id 的訂單，同一個 ID 只會記一次。
//
// 你要寫：
//   ① Bug 版：placeOrder 送出 api.postOrder()（不帶識別碼），逾時（IOException）就自動再送一次。
//   ② 修正版：產生 clientOrderId（UUID），用它 postOrder；逾時後先 orderExists 查詢，查得到就不重送，查不到才用同一個 ID 重送。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/platform/platform05-networking/L05_DuplicateOrder.kt
package tw.harry.labs.practice.platform05networking

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 假的交易所伺服器（兩個版本共用）：訂單簿放在記憶體裡 */
private class L05Exchange {
    private val ids = mutableSetOf<String>()
    private var anonymous = 0

    val total: Int get() = synchronized(this) { ids.size + anonymous }

    /** 有 clientOrderId 的訂單，同一個 ID 只記一次；沒有的，每次都是新的一張 */
    private fun place(clientOrderId: String?) = synchronized(this) {
        if (clientOrderId != null) ids.add(clientOrderId) else anonymous++
    }

    fun interceptor() = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        if (request.method == "POST" && path == "/orders") {
            place(request.header("X-Client-Order-Id"))          // 先記下訂單
            throw SocketTimeoutException("timeout")             // 但回應沒有在時限內回到 App
        }
        val exists = path.startsWith("/orders/") && synchronized(this) { path.removePrefix("/orders/") in ids }
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1)
            .code(if (exists) 200 else 404).message(if (exists) "OK" else "Not Found")
            .body("".toResponseBody())
            .build()
    }
}

/** 網路層（兩個版本共用）：postOrder 送出下單請求，orderExists 用 ID 查詢訂單 */
private class L05Api(private val client: OkHttpClient) {
    fun postOrder(clientOrderId: String? = null) {
        val request = Request.Builder().url("https://api.example.com/orders")
            .post("".toRequestBody())
            .apply { if (clientOrderId != null) header("X-Client-Order-Id", clientOrderId) }
            .build()
        client.newCall(request).execute().close()
    }

    fun orderExists(id: String): Boolean =
        client.newCall(Request.Builder().url("https://api.example.com/orders/$id").build()).execute().use { it.code == 200 }
}

/** 演練流程（兩個版本共用） */
private suspend fun scenario(log: LabLog, placeOrder: (L05Api, LabLog) -> Unit) = withContext(Dispatchers.IO) {
    val exchange = L05Exchange()
    val api = L05Api(OkHttpClient.Builder().addInterceptor(exchange.interceptor()).build())
    placeOrder(api, log)
    log("伺服器上的訂單數：${exchange.total}")
}

// MARK: - Bug 版
private object L05Bug {
    fun placeOrder(api: L05Api, log: LabLog) {
        // TODO ①：最多送 2 次（for (attempt in 1..2)）：try { api.postOrder()（不帶任何識別碼）；log("第 $attempt 次下單成功")；return }
        //        catch (e: IOException) { log("第 $attempt 次下單逾時，沒有收到回應") }
    }

    suspend fun run(log: LabLog) = scenario(log, ::placeOrder)
}

// MARK: - 修正版
private object L05Fixed {
    fun placeOrder(api: L05Api, log: LabLog) {
        // TODO ②：1. val clientOrderId = UUID.randomUUID().toString()（這次按「買入」只產生一個）
        //        2. try { api.postOrder(clientOrderId); log("下單成功") }
        //        3. catch (e: IOException)：log("下單逾時，沒有收到回應")，再 api.orderExists(clientOrderId) 查詢：
        //           查得到 → log("用 clientOrderId 查詢：訂單已經存在，不重送")；
        //           查不到 → 用同一個 clientOrderId 再 postOrder 一次（runCatching），log("查不到訂單，用同一個 clientOrderId 重送")
    }

    suspend fun run(log: LabLog) = scenario(log, ::placeOrder)
}

// MARK: - 演練設定
val l05 = Lab(
    id = "L05",
    title = "下單逾時後自動重送，結果買了兩次",
    question = "第 5 題：網路請求失敗要重試嗎？下單逾時為什麼不能直接重送？",
    goal = "下單的 POST 逾時，不代表伺服器沒成功。讓同一次按「買入」不論逾時幾次，伺服器上都只有一張訂單。",
    steps = listOf(
        "寫 TODO ①，選「練習」「Bug 版」執行，看兩次下單的結果，以及最後一行「伺服器上的訂單數」，出現 ✓。",
        "想一想：App 看到「逾時」的時候，伺服器其實已經做完了嗎？第二次 POST 伺服器怎麼知道它和第一次是同一筆？",
        "寫 TODO ②，選「修正版」執行，看逾時之後 App 先做了什麼，訂單數是不是 1。",
        "進階：429 要等 Retry-After、4xx 不重試、可以自動重試的請求要用指數退避加 jitter（下一個演練）。",
    ),
    expectBug = "「第 1 次下單逾時」「第 2 次下單逾時」，最後「伺服器上的訂單數：2」。",
    expectFixed = "「下單逾時，沒有收到回應」「用 clientOrderId 查詢：訂單已經存在，不重送」，最後「伺服器上的訂單數：1」。",
    file = "platform05-networking/L05_DuplicateOrder.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L05Bug.run(log)
            Variant.FIXED -> L05Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("伺服器上的訂單數") } -> "演練沒有跑完：要用 scenario(log, ::placeOrder) 執行"
            "第 1 次下單逾時，沒有收到回應" !in lines -> "Bug 版要送出 POST 下單（api.postOrder()），假伺服器會讓它逾時"
            "伺服器上的訂單數：2" !in lines -> "Bug 還沒重現：逾時後要自動重送一次 postOrder()（不帶識別碼），伺服器才會記成兩張"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("伺服器上的訂單數") } -> "演練沒有跑完：要用 scenario(log, ::placeOrder) 執行"
            "下單逾時，沒有收到回應" !in lines -> "要先用 clientOrderId 送出 POST 下單（api.postOrder(clientOrderId)）"
            "用 clientOrderId 查詢：訂單已經存在，不重送" !in lines -> "逾時之後要先用 api.orderExists(id) 查詢，查得到就不重送"
            "伺服器上的訂單數：1" !in lines -> "伺服器上應該只有 1 張訂單"
            else -> null
        }
    },
)
