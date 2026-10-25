// L07：手機時間調快 10 分鐘，所有下單請求都被伺服器拒絕（用伺服器的 Date
// 標頭校正時間）
// 對應複習站：Platform05Networking 第 6 題「簽名請求遇到手機時間不準怎麼辦？」
//
// 情境：下單 API 要求每個請求帶 X-Timestamp 和簽章（HMAC：
// 用雙方共有的密鑰對「時間戳記＋路徑」算出來的簽章）。
//        伺服器只接受時間差在 30 秒內的請求，
// 避免被攔截的請求日後被拿來重送（replay attack）。
//        使用者手動把手機時間調快了 10 分鐘。（假伺服器是 OkHttp 的最後一個
// Interceptor，不碰網路；手機時鐘用一個函式代表。）
// Bug 版：簽名用的時間直接取手機的時鐘，差了 600 秒，伺服器回 401，
// 使用者怎麼試都下不了單。
// 修正版：收到回應時讀 Date 標頭，算出 offset = 伺服器時間 − 手機時間；
// 之後簽名用「手機時間 + offset」。
package tw.harry.labs.solution.platform05networking

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
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

private fun sign(timestamp: Long, path: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec("demo-secret".toByteArray(), "HmacSHA256"))
    }
    return mac.doFinal("${timestamp}POST$path".toByteArray()).joinToString("") {
        "%02x".format(it)
    }
}

/** 手機的時鐘：比真實時間快 10 分鐘（使用者手動調的） */
private fun deviceNowMs(): Long = System.currentTimeMillis() + 600_000

/**
 * 假伺服器（兩個版本共用）：每個回應都帶 Date 標頭；/orders 只接受時間差 30
 * 秒內、簽章正確的請求
 */
private fun fakeServer() = Interceptor { chain ->
    val request = chain.request()
    val (code, body) = if (request.url.encodedPath == "/ticker") {
        200 to "15000000"
    } else {
        val timestamp = request.header("X-Timestamp")?.toLongOrNull() ?: 0
        when {
            abs(System.currentTimeMillis() / 1000 - timestamp) > 30 -> {
                401 to
                    "timestamp out of range"
            }

            request.header("X-Signature") !=
                sign(timestamp, request.url.encodedPath) -> {
                401 to
                    "bad signature"
            }

            else -> {
                200 to "ok"
            }
        }
    }
    val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())
    Response.Builder()
        .request(
            request,
        ).protocol(Protocol.HTTP_1_1).code(code).message("Status")
        .header("Date", date)
        .body(body.toResponseBody())
        .build()
}

/**
 * 演練流程（兩個版本共用）：先打一支不需要簽名的 /ticker，再送簽名的下單請求
 */
private suspend fun scenario(
    log: LabLog,
    timestamp: () -> Long,
    observe: (Response) -> Unit,
) = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().addInterceptor(fakeServer()).build()
    // 每次收到回應，都有機會用 Date 標頭校正時間
    client.newCall(
        Request.Builder().url("https://api.example.com/ticker").build(),
    ).execute().use {
        observe(it)
    }

    val ts = timestamp()
    log("簽名用的時間比伺服器的時間快 ${ts - System.currentTimeMillis() / 1000} 秒")
    val request = Request.Builder().url("https://api.example.com/orders")
        .post("".toRequestBody())
        .header("X-Timestamp", ts.toString())
        .header("X-Signature", sign(ts, "/orders"))
        .build()
    val code = client.newCall(request).execute().use { it.code }
    log("簽名的下單請求：HTTP $code")
}

// MARK: - Bug 版
private object L07Bug {
    class Clock {
        // 直接相信手機的時鐘
        fun timestamp(): Long = deviceNowMs() / 1000
        fun observe(response: Response) {}
    }

    suspend fun run(log: LabLog) {
        val clock = Clock()
        scenario(log, clock::timestamp, clock::observe)
    }
}

// MARK: - 修正版
private object L07Fixed {
    class Clock {
        // 伺服器時間 − 手機時間
        private var offsetMs = 0L

        // 手機時間 + offset
        fun timestamp(): Long = (deviceNowMs() + offsetMs) / 1000

        fun observe(response: Response) {
            val server = response.headers.getDate("Date") ?: return
            // 每次收到回應都更新
            offsetMs = server.time - deviceNowMs()
        }
    }

    suspend fun run(log: LabLog) {
        val clock = Clock()
        scenario(log, clock::timestamp, clock::observe)
    }
}

// MARK: - 演練設定
private fun skew(lines: List<String>): Long? =
    lines.firstOrNull { it.startsWith("簽名用的時間比伺服器的時間快 ") }
        ?.removePrefix("簽名用的時間比伺服器的時間快 ")?.removeSuffix(" 秒")?.toLongOrNull()

val l07 = Lab(
    id = "L07",
    title = "手機時間調快 10 分鐘，所有下單請求被拒絕",
    question = "第 6 題：簽名請求遇到手機時間不準怎麼辦？",
    goal = "手機時間不準時，簽名請求仍然能被伺服器接受。簽名用的時間要從哪裡來？",
    steps = listOf(
        "選「Bug 版」執行，看簽名用的時間和伺服器差幾秒、下單請求的 HTTP 狀態碼。",
        "想一想：伺服器的 /ticker 回應裡，有哪個標頭帶著伺服器現在的時間？",
        "選「修正版」執行，看時間差縮到幾秒、下單請求是不是 HTTP 200。",
        "進階：offset 要用「伺服器時間 − 手機現在的時間」算，而且不要存手機的絕對時間，使用者再改一次時鐘才不會又錯。",
    ),
    expectBug = "「簽名用的時間比伺服器的時間快 600 秒」「簽名的下單請求：HTTP 401」。",
    expectFixed = "「簽名用的時間比伺服器的時間快 0 秒」（差 1 秒以內），「簽名的下單請求：HTTP 200」。",
    file = "platform05-networking/L07_ClockSkew.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L07Bug.run(log)
            Variant.FIXED -> L07Fixed.run(log)
        }
    },
    checkBug = { lines ->
        val diff = skew(lines)
        when {
            diff == null -> "演練沒有跑完：要用 scenario(log, timestamp, observe) 執行"

            abs(
                diff - 600,
            ) > 5 -> "Bug 版簽名要直接用手機的時鐘 deviceNowMs()（目前和伺服器差 $diff 秒，應該約 600）"

            "簽名的下單請求：HTTP 401" !in lines -> "時間差 600 秒，伺服器應該回 HTTP 401"

            else -> null
        }
    },
    checkFixed = { lines ->
        val diff = skew(lines)
        when {
            diff == null -> "演練沒有跑完：要用 scenario(log, timestamp, observe) 執行"

            abs(
                diff,
            ) > 2 -> "簽名用的時間還差 $diff 秒：observe 要讀 Date 標頭算 offset，timestamp 要加上 offset"

            "簽名的下單請求：HTTP 200" !in lines -> "時間已經校正，下單請求應該是 HTTP 200"

            else -> null
        }
    },
)
