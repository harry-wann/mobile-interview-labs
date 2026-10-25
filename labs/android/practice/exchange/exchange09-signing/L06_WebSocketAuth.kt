// L06：REST 簽名好好的，私有 WebSocket 的 auth 訊息卻一直被拒絕（版本 2.1
// 的欄位名稱與 JSON 的值）
// 對應複習站：Exchange09Signing 第 7 題「私有 WebSocket 的簽名有什麼不同？」
//
// 情境：連上私有 WebSocket（/ws/v2）後，要先送 auth 訊息。簽名原理和 REST
// 一樣（HMAC-SHA256 加 Base64），但是：
//        ① 參數名稱改成小寫開頭的駝峰：accessKey、signatureMethod、
// signatureVersion（值是 2.1，不是 2）、timestamp；
//        ② 簽名字串是 GET、host、固定的 /ws/v2、排序並 URL 編碼後的 query；
//        ③ 送出的 JSON 裡，欄位的值不需要 URL 編碼，
// 直接放原始字串（簽名字串裡才編碼）。
//        演練用複習站的時間戳記 2019-09-01T18:16:16 組出 auth 訊息，
// 假伺服器收到後重新算一次簽名。
//
// 你要寫：
//   ① Bug 版（L06Bug.authParams、L06Bug.jsonValue）：欄位用 REST 的 E09.
// authParams(timestamp)（名稱和版本 2 都不改）；
//      jsonValue 把值做 URL 編碼（E09.percentEncode）後放進 JSON。
//   ② 修正版（L06Fixed.authParams、L06Fixed.jsonValue）：欄位用 accessKey、
// signatureMethod（HmacSHA256）、signatureVersion（2.1）、timestamp；
//      jsonValue 原樣回傳，不編碼。
// 只改有 TODO 的地方。寫完在 App 選「練習」執行，自動檢查會告訴你寫對了沒有。
// 解答：solution/exchange/exchange09-signing/L06_WebSocketAuth.kt
package tw.harry.labs.practice.exchange09signing

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
    val query = ordered.joinToString("&") {
        "${E09.percentEncode(it.first)}=${E09.percentEncode(it.second)}"
    }
    val signature = E09.hmacBase64("GET\n${E09.HOST}\n/ws/v2\n$query")

    val fields = JsonObject()
    // authType 是訊息本身的欄位，不在簽名裡
    fields.addProperty("authType", "api")
    for ((name, value) in params) fields.addProperty(name, jsonValue(value))
    fields.addProperty("signature", jsonValue(signature))
    val message = JsonObject()
    message.addProperty("action", "req")
    message.addProperty("ch", "auth")
    message.add("params", fields)

    log("送出的欄位：${params.joinToString(", ") { it.first }}")
    val sentTimestamp =
        params.firstOrNull {
            it.first.lowercase() == "timestamp"
        }?.let { jsonValue(it.second) }
            ?: "（沒有）"
    log("送出的 timestamp：$sentTimestamp")
    log("簽名與 test vector ${if (signature == E09.WS_VECTOR) "一致" else "不一致"}")
    val error = E09.verifyWS(message.toString())
    log(if (error != null) "伺服器拒絕：$error" else "伺服器受理：auth 成功")
}

// MARK: - Bug 版
private object L06Bug {
    fun authParams(timestamp: String): List<Pair<String, String>> {
        // TODO ①-1：直接回傳 REST 的認證參數 E09.authParams(timestamp)
        return emptyList()
    }

    fun jsonValue(value: String): String {
        // TODO ①-2：把 value 用 E09.percentEncode 編碼後回傳
        return ""
    }

    suspend fun run(log: LabLog) = scenario(log, ::authParams, ::jsonValue)
}

// MARK: - 修正版
private object L06Fixed {
    fun authParams(timestamp: String): List<Pair<String, String>> {
        // TODO ②-1：回傳 4 個欄位：accessKey（E09.ACCESS_KEY）、
        // signatureMethod（"HmacSHA256"）、signatureVersion（"2.1"）、timestamp
        return emptyList()
    }

    fun jsonValue(value: String): String {
        // TODO ②-2：原樣回傳 value（JSON 的值不需要 URL 編碼）
        return ""
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
        "打開 practice/exchange/exchange09-signing/L06_WebSocketAuth.kt，寫 TODO ①-1、①-2（Bug 版）。",
        "在 App 選「練習」「Bug 版」執行，看送出的欄位名稱、timestamp 的值，以及伺服器的回應。",
        "對照複習站第 7 題的表格，列出 REST 和私有 WebSocket 有哪四個地方不一樣。",
        "寫 TODO ②-1、②-2（修正版），選「修正版」執行，簽名要和 test vector 一致，伺服器受理。",
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
            lines.none { it.startsWith("簽名與 test vector") } -> {
                "演練沒有跑完：要用 scenario(log, authParams, jsonValue) 執行"
            }

            "送出的欄位：AccessKeyId, SignatureMethod, SignatureVersion, Timestamp" !in
                lines -> {
                "Bug 還沒重現：直接重用 REST 的欄位（AccessKeyId、SignatureMethod、SignatureVersion、Timestamp）"
            }

            "送出的 timestamp：2019-09-01T18%3A16%3A16" !in lines -> {
                "Bug 還沒重現：JSON 裡的值也做了 URL 編碼，timestamp 變成 2019-09-01T18%3A16%3A16"
            }

            lines.none { it.startsWith("伺服器拒絕") } -> {
                "伺服器應該拒絕這個 auth 訊息"
            }

            else -> {
                null
            }
        }
    },
    checkFixed = { lines ->
        when {
            lines.none { it.startsWith("簽名與 test vector") } -> {
                "演練沒有跑完：要用 scenario(log, authParams, jsonValue) 執行"
            }

            "送出的欄位：accessKey, signatureMethod, signatureVersion, timestamp" !in
                lines -> {
                "欄位名稱要是 accessKey、signatureMethod、signatureVersion、timestamp（小寫開頭的駝峰，signatureVersion 的值是 2.1）"
            }

            "送出的 timestamp：2019-09-01T18:16:16" !in lines -> {
                "JSON 裡的值放原始字串，不要 URL 編碼"
            }

            "簽名與 test vector 一致" !in lines -> {
                "簽名要和 test vector 一致：簽名字串是 GET、host、/ws/v2、排序並編碼後的 query"
            }

            "伺服器受理：auth 成功" !in lines -> {
                "伺服器還是拒絕：${lines.lastOrNull() ?: ""}"
            }

            else -> {
                null
            }
        }
    },
)
