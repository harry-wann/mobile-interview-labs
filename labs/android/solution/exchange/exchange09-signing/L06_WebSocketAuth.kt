// L06：REST 簽名好好的，私有 WebSocket 的 auth 訊息卻一直被拒絕（版本 2.1 的欄位名稱與 JSON 的值）
// 對應複習站：Exchange09Signing 第 7 題「私有 WebSocket 的簽名有什麼不同？」
//
// 情境：連上私有 WebSocket（/ws/v2）後，要先送 auth 訊息。簽名原理和 REST 一樣（HMAC-SHA256 加 Base64），但是：
//        ① 參數名稱改成小寫開頭的駝峰：accessKey、signatureMethod、signatureVersion（值是 2.1，不是 2）、timestamp；
//        ② 簽名字串是 GET、host、固定的 /ws/v2、排序並 URL 編碼後的 query；
//        ③ 送出的 JSON 裡，欄位的值不需要 URL 編碼，直接放原始字串（簽名字串裡才編碼）。
//        演練用複習站的時間戳記 2019-09-01T18:16:16 組出 auth 訊息，假伺服器收到後重新算一次簽名。
// Bug 版：直接重用 REST 的認證參數（AccessKeyId、SignatureMethod、SignatureVersion=2、Timestamp），
//        而且把 JSON 裡每個欄位的值也做了 URL 編碼（timestamp 變成 2019-09-01T18%3A16%3A16）。伺服器找不到 accessKey、也不認得版本 2，直接拒絕。
// 修正版：用 WebSocket 的欄位名稱與版本 2.1；JSON 裡的值放原始字串，只有簽名字串裡的 query 才 URL 編碼。
package tw.harry.labs.solution.exchange09signing

import com.google.gson.JsonObject
import tw.harry.labs.kit.Lab
import tw.harry.labs.kit.LabLog
import tw.harry.labs.kit.Variant

/** 演練流程（兩個版本共用）：組出 auth 訊息送給假伺服器 */
private fun scenario(
    log: LabLog,
    authParams: (String) -> List<Pair<String, String>>,
    jsonValue: (String) -> String,
) {
    val params = authParams(E09.WS_TIMESTAMP)
    val ordered = params.sortedBy { it.first }
    val query = ordered.joinToString("&") { "${E09.percentEncode(it.first)}=${E09.percentEncode(it.second)}" }
    val signature = E09.hmacBase64("GET\n${E09.HOST}\n/ws/v2\n$query")

    val fields = JsonObject()
    fields.addProperty("authType", "api")                                  // authType 是訊息本身的欄位，不在簽名裡
    for ((name, value) in params) fields.addProperty(name, jsonValue(value))
    fields.addProperty("signature", jsonValue(signature))
    val message = JsonObject()
    message.addProperty("action", "req")
    message.addProperty("ch", "auth")
    message.add("params", fields)

    log("送出的欄位：${params.joinToString(", ") { it.first }}")
    val sentTimestamp = params.firstOrNull { it.first.lowercase() == "timestamp" }?.let { jsonValue(it.second) } ?: "（沒有）"
    log("送出的 timestamp：$sentTimestamp")
    log("簽名與 test vector ${if (signature == E09.WS_VECTOR) "一致" else "不一致"}")
    val error = E09.verifyWS(message.toString())
    log(if (error != null) "伺服器拒絕：$error" else "伺服器受理：auth 成功")
}

// MARK: - Bug 版
private object L06Bug {
    fun authParams(timestamp: String): List<Pair<String, String>> {
        return E09.authParams(timestamp)                      // 重用 REST 的欄位：AccessKeyId、…、SignatureVersion=2
    }

    fun jsonValue(value: String): String {
        return E09.percentEncode(value)                       // JSON 的值也編碼：timestamp 變成 …18%3A16%3A16
    }

    suspend fun run(log: LabLog) = scenario(log, ::authParams, ::jsonValue)
}

// MARK: - 修正版
private object L06Fixed {
    fun authParams(timestamp: String): List<Pair<String, String>> {
        return listOf(
            "accessKey" to E09.ACCESS_KEY,
            "signatureMethod" to "HmacSHA256",
            "signatureVersion" to "2.1",
            "timestamp" to timestamp,
        )
    }

    fun jsonValue(value: String): String {
        return value                                          // JSON 裡放原始字串，不編碼
    }

    suspend fun run(log: LabLog) = scenario(log, ::authParams, ::jsonValue)
}

// MARK: - 演練設定
val l06 = Lab(
    id = "L06",
    title = "REST 簽名正常，私有 WebSocket 的 auth 被拒絕",
    question = "第 7 題：私有 WebSocket 的簽名有什麼不同？",
    goal = "私有 WebSocket 用 SignatureVersion 2.1：欄位名稱是小寫開頭的駝峰，簽名字串的 path 固定為 /ws/v2，送出的 JSON 裡的值不需要 URL 編碼。",
    steps = listOf(
        "選「Bug 版」執行，看送出的欄位名稱、timestamp 的值，以及伺服器的回應。",
        "對照複習站第 7 題的表格，列出 REST 和私有 WebSocket 有哪四個地方不一樣。",
        "選「修正版」執行，簽名要和 test vector 一致，伺服器受理。",
        "進階：authType 是訊息本身的欄位，不在簽名的參數裡；如果不小心簽進去，伺服器會回什麼？",
    ),
    expectBug = "「送出的欄位：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp」，「送出的 timestamp：2019-09-01T18%3A16%3A16」，「伺服器拒絕：auth fail: 缺少欄位 accessKey」。",
    expectFixed = "「送出的欄位：accessKey, signatureMethod, signatureVersion, timestamp」，「送出的 timestamp：2019-09-01T18:16:16」，「簽名與 test vector 一致」，「伺服器受理：auth 成功」。",
    file = "exchange09-signing/L06_WebSocketAuth.kt",
    run = { variant, log ->
        when (variant) {
            Variant.BUG -> L06Bug.run(log)
            Variant.FIXED -> L06Fixed.run(log)
        }
    },
    checkBug = { lines ->
        when {
            lines.none { it.startsWith("簽名與 test vector") } -> "演練沒有跑完：要用 scenario(log, authParams, jsonValue) 執行"
            "送出的欄位：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp" !in lines ->
                "Bug 還沒重現：直接重用 REST 的欄位（AccessKeyId、SignatureMethod、SignatureVersion、Timestamp）"
            "送出的 timestamp：2019-09-01T18%3A16%3A16" !in lines -> "Bug 還沒重現：JSON 裡的值也做了 URL 編碼，timestamp 變成 2019-09-01T18%3A16%3A16"
            lines.none { it.startsWith("伺服器拒絕") } -> "伺服器應該拒絕這個 auth 訊息"
            else -> null
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("簽名與 test vector") } -> "演練沒有跑完：要用 scenario(log, authParams, jsonValue) 執行"
            "送出的欄位：accessKey, signatureMethod, signatureVersion, timestamp" !in lines ->
                "欄位名稱要是 accessKey、signatureMethod、signatureVersion、timestamp（小寫開頭的駝峰，signatureVersion 的值是 2.1）"
            "送出的 timestamp：2019-09-01T18:16:16" !in lines -> "JSON 裡的值放原始字串，不要 URL 編碼"
            "簽名與 test vector 一致" !in lines -> "簽名要和 test vector 一致：簽名字串是 GET、host、/ws/v2、排序並編碼後的 query"
            "伺服器受理：auth 成功" !in lines -> "伺服器還是拒絕：${lines.lastOrNull() ?: ""}"
            else -> null
        }
    },
)
